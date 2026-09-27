/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import kr.guinnessgroup.lorebench.record.Owner;
import kr.guinnessgroup.lorebench.record.RecordStore;
import kr.guinnessgroup.lorebench.runtime.LorebenchRuntime;
import kr.guinnessgroup.lorebench.runtime.ContentChecks;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.commands.arguments.item.ItemPredicateArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Quests as the game sees them: definitions (from the published quest document)
 * and each player's state (their records). Nodes reach this through
 * {@link #current()} while the server runs. Call on the server thread.
 * <p>
 * Items are read with the game's own command parsers: rewards as {@code /give}
 * writes them, hand-in goals as {@code /clear} reads them (listed components must
 * match, others are ignored). See docs/decisions/0006-item-syntax.md.
 */
public final class Quests {

    private static volatile Quests current;

    private final LorebenchRuntime runtime;
    private final RecordStore records;

    /** Goal conditions already read, by their text. Registries change only between server runs. */
    private final Map<String, Predicate<ItemStack>> conditions = new HashMap<>();

    public Quests(LorebenchRuntime runtime, RecordStore records) {
        this.runtime = runtime;
        this.records = records;
    }

    /** The running server's quests, or {@code null} when no server is running. */
    public static Quests current() {
        return current;
    }

    public void start() {
        conditions.clear();
        current = this;
    }

    public void stop() {
        if (current == this) {
            current = null;
        }
    }

    public QuestState state(ServerPlayer player, String questId) {
        Owner owner = Owner.player(player.getUUID());
        QuestState stored = QuestState.fromRecord(records.get(owner, questId));
        QuestDoc.Quest quest = runtime.quest(questId);
        if (stored == QuestState.ACTIVE && quest != null
                && goalsMet(player.getInventory(), progress(owner, questId), quest, s -> condition(player, s))) {
            return QuestState.READY;
        }
        return stored;
    }

    /** What the player has done toward a quest's counted goals so far, by {@link QuestDoc.Goal#progressKey()}. */
    public Map<String, Integer> progress(ServerPlayer player, String questId) {
        return progress(Owner.player(player.getUUID()), questId);
    }

    private Map<String, Integer> progress(Owner owner, String questId) {
        return QuestProgress.read(records.get(owner, QuestProgress.key(questId)));
    }

    private Predicate<ItemStack> condition(ServerPlayer player, String spec) {
        return conditions.computeIfAbsent(spec, s -> conditionOrNothing(s, player));
    }

    /**
     * A player killed something: count it toward their active kill goals for that
     * entity, and maybe drop quest items for them. Tamed animals (someone's pet wolf,
     * cat, parrot, horse ...) never count.
     */
    public void onKill(ServerPlayer player, LivingEntity victim) {
        if ((victim instanceof TamableAnimal pet && pet.isTame())
                || (victim instanceof AbstractHorse horse && horse.isTamed())) {
            return;
        }
        String entity = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        tally(player, QuestDoc.Goal.Kind.KILL, entity);
        dropQuestItems(player, victim, QuestDoc.Goal.progressKey(QuestDoc.Goal.Kind.KILL, entity));
    }

    /**
     * For each active quest of the player's with a collect goal from {@code from}, by its
     * chance: one of its items, marked for them, drops where {@code source} was, seen and
     * picked up by them only. None while they already carry enough (0012).
     */
    private void dropQuestItems(ServerPlayer player, Entity source, String from) {
        Owner owner = Owner.player(player.getUUID());
        for (QuestDoc.Quest q : runtime.quests()) {
            if (QuestState.fromRecord(records.get(owner, q.id())) != QuestState.ACTIVE) {
                continue;
            }
            for (QuestDoc.Goal goal : q.goals()) {
                if (goal.kind() != QuestDoc.Goal.Kind.COLLECT || !goal.from().equals(from)
                        || count(player.getInventory(), QuestItems.of(goal, q.id(), player.getUUID())) >= goal.count()
                        || player.getRandom().nextDouble() >= goal.chance()) {
                    continue;
                }
                ItemStack stack = stack(goal.target(), player.registryAccess());
                if (!stack.isEmpty()) {
                    QuestItems.mark(stack, q.id(), player.getUUID());
                    QuestItemEntity.drop(player.serverLevel(), source, stack, player.getUUID());
                }
            }
        }
    }

    /**
     * A player broke a block: if it is a fully grown crop, count one plant toward their
     * active harvest goals for it. It counts at the moment of breaking, whoever planted it.
     */
    public void onHarvest(ServerPlayer player, BlockState state) {
        if (Crops.ripe(state)) {
            tally(player, QuestDoc.Goal.Kind.HARVEST, Crops.id(state.getBlock()));
        }
    }

    /**
     * A baby was born to animals this player fed: count it toward their active breed
     * goals for its kind. It counts at birth, whenever the parents were fed.
     */
    public void onBreed(ServerPlayer player, Entity baby) {
        tally(player, QuestDoc.Goal.Kind.BREED, BuiltInRegistries.ENTITY_TYPE.getKey(baby.getType()).toString());
    }

    /**
     * Count one toward every active quest of the player's with a {@code kind} goal for
     * {@code target}, up to the goal's count.
     */
    private void tally(ServerPlayer player, QuestDoc.Goal.Kind kind, String target) {
        Owner owner = Owner.player(player.getUUID());
        String key = QuestDoc.Goal.progressKey(kind, target);
        boolean changed = false;
        for (QuestDoc.Quest q : runtime.quests()) {
            if (QuestState.fromRecord(records.get(owner, q.id())) != QuestState.ACTIVE) {
                continue;
            }
            for (QuestDoc.Goal goal : q.goals()) {
                if (goal.kind() != kind || !goal.target().equals(target)) {
                    continue;
                }
                Map<String, Integer> progress = progress(owner, q.id());
                int have = progress.getOrDefault(key, 0);
                if (have < goal.count()) {
                    progress.put(key, have + 1);
                    records.set(owner, QuestProgress.key(q.id()), QuestProgress.write(progress));
                    changed = true;
                }
            }
        }
        if (changed) {
            sync(player);
        }
    }

    /**
     * Show a hidden quest to the player (it becomes active) and give its supplies, once:
     * whether they accepted it in dialogue or a graph revealed it. Does nothing if already
     * revealed. Supplies that do not fit drop at the player's feet.
     */
    public void reveal(ServerPlayer player, String questId) {
        Owner owner = Owner.player(player.getUUID());
        if (QuestState.fromRecord(records.get(owner, questId)) != QuestState.HIDDEN) {
            return;
        }
        records.set(owner, questId, QuestState.ACTIVE_VALUE);
        QuestDoc.Quest quest = runtime.quest(questId);
        if (quest != null) {
            for (QuestDoc.Stack supply : quest.supplies()) {
                give(player, stack(supply.item(), player.registryAccess()), supply.count());
            }
        }
        sync(player);
    }

    /** How many times the player has turned this quest down (0012). */
    public int timesDeclined(ServerPlayer player, String questId) {
        return QuestDeclines.read(records.get(Owner.player(player.getUUID()), QuestDeclines.key(questId)));
    }

    /**
     * The player turned the quest down once more.
     *
     * @return how many times they have now, this time included
     */
    public int decline(ServerPlayer player, String questId) {
        Owner owner = Owner.player(player.getUUID());
        int times = timesDeclined(player, questId) + 1;
        records.set(owner, QuestDeclines.key(questId), QuestDeclines.write(times));
        return times;
    }

    /**
     * Hand in a ready quest: take the goal items, give the rewards, and record it
     * done (dropping its progress), all at once on the server thread. Rewards
     * that do not fit drop at the player's feet (like {@code /give}).
     *
     * @return false (and nothing changes) if the quest is not ready for this player
     */
    public boolean complete(ServerPlayer player, String questId) {
        QuestDoc.Quest quest = runtime.quest(questId);
        if (quest == null || state(player, questId) != QuestState.READY) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (QuestDoc.Goal goal : quest.goals()) {
            if (goal.kind() == QuestDoc.Goal.Kind.ITEM) {
                take(inventory, condition(player, goal.target()), goal.count());
            } else if (goal.kind() == QuestDoc.Goal.Kind.COLLECT) {
                take(inventory, QuestItems.of(goal, questId, player.getUUID()), goal.count());
            }
        }
        for (QuestDoc.Stack reward : quest.rewards()) {
            give(player, stack(reward.item(), player.registryAccess()), reward.count());
        }
        Owner owner = Owner.player(player.getUUID());
        records.set(owner, questId, QuestState.DONE_VALUE);
        records.set(owner, QuestProgress.key(questId), null);
        sync(player);
        return true;
    }

    /** Remove {@code count} matching items from the same slots {@link #count} counts. */
    private static void take(Inventory inventory, Predicate<ItemStack> matches, int count) {
        int left = count;
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matches.test(stack)) {
                int n = Math.min(left, stack.getCount());
                stack.shrink(n);
                left -= n;
            }
        }
        inventory.setChanged();
    }

    /** How many items in the inventory (main, armor, offhand) match. */
    public static int count(Inventory inventory, Predicate<ItemStack> matches) {
        int n = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && matches.test(stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** Give copies of {@code item} in stacks no larger than it allows; what does not fit drops. */
    private static void give(ServerPlayer player, ItemStack item, int count) {
        if (item.isEmpty()) {
            return;
        }
        int max = item.getMaxStackSize();
        for (int left = count; left > 0; ) {
            int n = Math.min(left, max);
            ItemHandlerHelper.giveItemToPlayer(player, item.copyWithCount(n));
            left -= n;
        }
    }

    /**
     * A player's standing on a quest, for the editor.
     *
     * @param state    hidden (only turned it down so far), active, ready (online players only; it
     *                 depends on their inventory) or done
     * @param progress their counted progress ({@link QuestDoc.Goal#progressKey()})
     * @param timesDeclined how many times they turned it down
     */
    public record Standing(UUID player, String name, boolean online, QuestState state, Map<String, Integer> progress,
                           int timesDeclined) {}

    /** Everyone who is on this quest, has done it or has turned it down, online or not, by name. Server thread. */
    public List<Standing> standings(MinecraftServer server, String questId) {
        List<Standing> out = new ArrayList<>();
        Set<Owner> owners = new LinkedHashSet<>(records.ownersWith(Owner.Kind.PLAYER, questId));
        owners.addAll(records.ownersWith(Owner.Kind.PLAYER, QuestDeclines.key(questId)));
        for (Owner owner : owners) {
            UUID uuid;
            try {
                uuid = UUID.fromString(owner.id());
            } catch (IllegalArgumentException e) {
                continue;
            }
            ServerPlayer online = server.getPlayerList().getPlayer(uuid);
            QuestState state = online != null ? state(online, questId) : QuestState.fromRecord(records.peek(owner, questId));
            Map<String, Integer> progress = QuestProgress.read(records.peek(owner, QuestProgress.key(questId)));
            int declined = QuestDeclines.read(records.peek(owner, QuestDeclines.key(questId)));
            out.add(new Standing(uuid, name(server, uuid, online), online != null, state, progress, declined));
        }
        out.sort(Comparator.comparing(Standing::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** A player's name: as they are online, else from the server's memory of who joined, else their UUID. */
    private static String name(MinecraftServer server, UUID uuid, ServerPlayer online) {
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? uuid.toString() : cache.get(uuid).map(GameProfile::getName).orElse(uuid.toString());
    }

    /**
     * Take a player back to before a quest, online or not: no state, no progress and no
     * refusals, so it is offered again as the first time and its supplies are given again on
     * accepting. What they were already given (supplies, rewards) stays theirs. Other quests
     * are left alone.
     */
    public void forget(MinecraftServer server, UUID uuid, String questId) {
        Owner owner = Owner.player(uuid);
        records.setAny(owner, questId, null);
        records.setAny(owner, QuestProgress.key(questId), null);
        records.setAny(owner, QuestDeclines.key(questId), null);
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            sync(online);
        }
    }

    /** Send the player every quest revealed to them, and nothing else. */
    public void sync(ServerPlayer player) {
        Owner owner = Owner.player(player.getUUID());
        List<QuestSyncPayload.Entry> revealed = new ArrayList<>();
        for (QuestDoc.Quest q : runtime.quests()) {
            QuestState s = QuestState.fromRecord(records.get(owner, q.id()));
            if (s != QuestState.HIDDEN) {
                revealed.add(new QuestSyncPayload.Entry(q, s == QuestState.DONE, progress(owner, q.id())));
            }
        }
        PacketDistributor.sendToPlayer(player, new QuestSyncPayload(List.copyOf(revealed)));
    }

    /** After a publish: quest content may have changed for everyone online. */
    public void syncAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sync(player);
        }
    }

    /**
     * Whether every goal is met: items in the inventory, counted goals in {@code progress}.
     * Both sides use this, so the screen agrees with the server.
     *
     * @param condition a hand-in goal's item condition, from its text
     */
    public static boolean goalsMet(Inventory inventory, Map<String, Integer> progress, QuestDoc.Quest quest,
                                   Function<String, Predicate<ItemStack>> condition) {
        for (QuestDoc.Goal goal : quest.goals()) {
            if (have(inventory, progress, quest, goal, condition) < goal.count()) {
                return false;
            }
        }
        return true;
    }

    /**
     * How far along one goal is: hand-in items and the quest's own items (marked for
     * this inventory's player) in the inventory, counted goals in {@code progress}.
     */
    public static int have(Inventory inventory, Map<String, Integer> progress, QuestDoc.Quest quest, QuestDoc.Goal goal,
                           Function<String, Predicate<ItemStack>> condition) {
        return switch (goal.kind()) {
            case ITEM -> count(inventory, condition.apply(goal.target()));
            case COLLECT -> count(inventory, QuestItems.of(goal, quest.id(), inventory.player.getUUID()));
            case KILL, HARVEST, BREED -> progress.getOrDefault(goal.progressKey(), 0);
        };
    }

    /** An item's id without components, e.g. {@code minecraft:amethyst_shard}. */
    public static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    // --- items and entities, read the way the game's commands read them ---

    /** The item with this plain id; air if there is none (publish rejects unknown items). */
    public static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    /**
     * One item as {@code /give} writes it ({@code minecraft:iron_sword[...]}), read by
     * the game's own parser, so components from other mods work too. Empty if it
     * cannot be read (publish rejects those).
     *
     * @param registries the running game's registries (enchantments live there)
     */
    public static ItemStack stack(String spec, HolderLookup.Provider registries) {
        try {
            ItemParser.ItemResult r = parseItem(spec, registries);
            return new ItemStack(r.item(), 1, r.components());
        } catch (CommandSyntaxException e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * An item condition as {@code /clear} reads it: {@code minecraft:wheat},
     * {@code minecraft:iron_sword[custom_data={...}]}, {@code #minecraft:logs} ...
     * Listed components must match; unlisted ones are ignored.
     */
    public static Predicate<ItemStack> condition(String spec, HolderLookup.Provider registries, FeatureFlagSet features)
            throws CommandSyntaxException {
        StringReader reader = new StringReader(spec);
        Predicate<ItemStack> p = new ItemPredicateArgument(CommandBuildContext.simple(registries, features)).parse(reader);
        requireEnd(reader);
        return p;
    }

    /** A condition for a player's side of the game; matches nothing if it cannot be read. */
    public static Predicate<ItemStack> conditionOrNothing(String spec, Player player) {
        try {
            return condition(spec, player.registryAccess(), player.level().enabledFeatures());
        } catch (CommandSyntaxException e) {
            return stack -> false;
        }
    }

    /**
     * What to show for a hand-in goal: the item it names, with any components that
     * read as {@code /give} would; for a tag ({@code #minecraft:logs}), its first item.
     */
    public static ItemStack display(String spec, HolderLookup.Provider registries) {
        if (spec.startsWith("#")) {
            ResourceLocation rl = ResourceLocation.tryParse(spec.substring(1));
            return rl == null ? ItemStack.EMPTY : BuiltInRegistries.ITEM.getTag(TagKey.create(Registries.ITEM, rl))
                    .filter(tag -> tag.size() > 0)
                    .map(tag -> new ItemStack(tag.get(0)))
                    .orElse(ItemStack.EMPTY);
        }
        ItemStack exact = stack(spec, registries);
        if (!exact.isEmpty()) {
            return exact;
        }
        int bracket = spec.indexOf('[');
        return new ItemStack(item(bracket < 0 ? spec : spec.substring(0, bracket)));
    }

    /**
     * What a player holds in their main hand, as {@code /give} writes it, with every
     * component (name, enchantments, damage, other mods' data). Empty if nothing.
     */
    public static String held(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return "";
        }
        return new ItemInput(stack.getItemHolder(), stack.getComponentsPatch()).serialize(player.registryAccess());
    }

    /** The entity type with this id, or {@code null} if there is none. */
    public static EntityType<?> entityType(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(rl).orElse(null);
    }

    /** For publish: reads content with the running server's registries. */
    public static final ContentChecks CHECKS = new ContentChecks() {
        @Override
        public String item(String spec) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return "the server is not running";
            }
            try {
                parseItem(spec, server.registryAccess());
                return null;
            } catch (CommandSyntaxException e) {
                return e.getMessage();
            }
        }

        @Override
        public String itemCondition(String spec) {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return "the server is not running";
            }
            try {
                condition(spec, server.registryAccess(), server.getWorldData().enabledFeatures());
                return null;
            } catch (CommandSyntaxException e) {
                return e.getMessage();
            }
        }

        @Override
        public String entity(String id) {
            return entityType(id) == null ? "no entity '" + id + "' in this game" : null;
        }

        @Override
        public String crop(String id) {
            Block block = Crops.block(id);
            if (block == null) {
                return "no block '" + id + "' in this game";
            }
            return Crops.harvestable(block) ? null
                    : "not a crop whose full growth the game shows (wheat, carrots, potatoes, beetroots, nether wart, "
                    + "cocoa ...); pumpkins, melons, sugar cane, cactus, bamboo and berries are not supported yet";
        }

        @Override
        public String breedable(String id) {
            EntityType<?> type = entityType(id);
            if (type == null) {
                return "no entity '" + id + "' in this game";
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return "the server is not running";
            }
            boolean ok;
            try {
                // Making an entity belongs on the server thread (runs right away when already on it).
                ok = server.submit(() -> Breeding.breedable(type, server.overworld())).get(5, TimeUnit.SECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException e) {
                return "the game did not answer whether it can be bred";
            }
            return ok ? null
                    : "not an animal whose baby is born when two are fed (cows, sheep, pigs, chickens, wolves ...); "
                    + "turtles, frogs, sniffers and villagers are not supported yet";
        }
    };

    private static ItemParser.ItemResult parseItem(String spec, HolderLookup.Provider registries) throws CommandSyntaxException {
        StringReader reader = new StringReader(spec);
        ItemParser.ItemResult r = new ItemParser(registries).parse(reader);
        requireEnd(reader);
        return r;
    }

    private static void requireEnd(StringReader reader) throws CommandSyntaxException {
        if (reader.canRead()) {
            throw new SimpleCommandExceptionType(Component.literal(
                    "unexpected text after the item: '" + reader.getRemaining() + "'")).createWithContext(reader);
        }
    }
}
