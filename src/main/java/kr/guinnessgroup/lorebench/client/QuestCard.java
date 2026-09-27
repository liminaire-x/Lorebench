/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.quest.Crops;
import kr.guinnessgroup.lorebench.quest.QuestDoc;
import kr.guinnessgroup.lorebench.quest.Quests;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * A quest's needs and rewards as the quest screen and the dialogue show them, with
 * the items read once per screen. Progress comes from this player's inventory and
 * the counts (kills, harvests, babies) the server sent.
 */
final class QuestCard {

    static final int ROW_H = 18;
    static final int WHITE = 0xFFFFFFFF;
    static final int GRAY = 0xFF909090;
    static final int GREEN = 0xFF55FF55;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final Map<String, ItemStack> rewards = new HashMap<>();
    private final Map<String, ItemStack> displays = new HashMap<>();
    private final Map<String, Predicate<ItemStack>> conditions = new HashMap<>();

    /** How tall {@link #needsAndRewards} draws a quest. */
    int height(Font font, QuestDoc.Quest q, boolean supplies, boolean needs) {
        int h = 0;
        if (supplies && !q.supplies().isEmpty()) {
            h += 6 + font.lineHeight + 2 + q.supplies().size() * ROW_H;
        }
        if (needs && !q.goals().isEmpty()) {
            h += 6 + font.lineHeight + 2 + q.goals().size() * ROW_H;
        }
        if (!q.rewards().isEmpty()) {
            h += 4 + font.lineHeight + 2 + q.rewards().size() * ROW_H;
        }
        return h;
    }

    /**
     * Needs and rewards from {@code y} down, after what accepting gives when {@code supplies} is on.
     *
     * @param progress     this player's counted progress, by {@link QuestDoc.Goal#progressKey()}
     * @param showProgress show "3/10" from this player's inventory and progress; off shows "× 10"
     * @param supplies     show the supplies first (an offer: what accepting gives)
     * @param needs        show the needs (off: taking the rewards of a quest handed in, 0013)
     * @return the item under the mouse, for a tooltip, or empty
     */
    ItemStack needsAndRewards(GuiGraphics g, Font font, QuestDoc.Quest q, Map<String, Integer> progress, boolean showProgress,
                              boolean supplies, boolean needs, int x, int y, int mouseX, int mouseY) {
        ItemStack hovered = ItemStack.EMPTY;
        LocalPlayer player = minecraft.player;
        if (supplies && !q.supplies().isEmpty()) {
            y += 6;
            g.drawString(font, Component.translatable("lorebench.quests.supplies"), x, y, GRAY);
            y += font.lineHeight + 2;
            for (QuestDoc.Stack supply : q.supplies()) {
                ItemStack stack = rewardStack(supply.item());
                hovered = row(g, font, stack, stack.getHoverName(), " × " + supply.count(), WHITE, true, x, y, mouseX, mouseY, hovered);
                y += ROW_H;
            }
        }
        if (needs && !q.goals().isEmpty()) {
            y += 6;
            g.drawString(font, Component.translatable("lorebench.quests.needs"), x, y, GRAY);
            y += font.lineHeight + 2;
            for (QuestDoc.Goal goal : q.goals()) {
                ItemStack icon = goalIcon(goal);
                Component name = switch (goal.kind()) {
                    case KILL, BREED -> entityName(goal.target());
                    case HARVEST -> cropName(goal.target());
                    case ITEM -> goal.target().startsWith("#") ? Component.literal(goal.target()) // a tag: any item in it
                            : icon.getHoverName();
                    case COLLECT -> icon.getHoverName();
                };
                if (goal.kind().counted()) {
                    // "Kill Cow" and "Breed Cow" can sit in one quest; say which.
                    name = Component.translatable("lorebench.quests.goal." + goal.kind().key, name);
                }
                String amount;
                int color;
                if (!showProgress || player == null) {
                    amount = " × " + goal.count();
                    color = WHITE;
                } else {
                    int have = Quests.have(player.getInventory(), progress, q, goal, this::condition);
                    amount = " " + Math.min(have, goal.count()) + "/" + goal.count();
                    color = have >= goal.count() ? GREEN : WHITE;
                }
                // A counted goal's icon only stands for it (a spawn egg, a crop's seed):
                // its tooltip would name that item, so none.
                hovered = row(g, font, icon, name, amount, color, !goal.kind().counted(), x, y, mouseX, mouseY, hovered);
                y += ROW_H;
            }
        }
        if (!q.rewards().isEmpty()) {
            y += 4;
            g.drawString(font, Component.translatable("lorebench.quests.rewards"), x, y, GRAY);
            y += font.lineHeight + 2;
            for (QuestDoc.Stack reward : q.rewards()) {
                ItemStack stack = rewardStack(reward.item());
                hovered = row(g, font, stack, stack.getHoverName(), " × " + reward.count(), WHITE, true, x, y, mouseX, mouseY, hovered);
                y += ROW_H;
            }
        }
        return hovered;
    }

    /**
     * An icon, a name and an amount. Returns the icon's stack if the mouse is over it
     * and {@code tooltip} is on (so the caller shows its tooltip), else {@code hovered}.
     */
    private static ItemStack row(GuiGraphics g, Font font, ItemStack icon, Component name, String amount, int color,
                                 boolean tooltip, int x, int y, int mouseX, int mouseY, ItemStack hovered) {
        g.renderItem(icon, x, y);
        g.drawString(font, name.copy().append(amount), x + 20, y + 5, color);
        boolean over = mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16;
        return (over && tooltip) ? icon : hovered;
    }

    /**
     * An item goal shows the item its condition names (with a name or enchantments if
     * it lists them); a quest item goal, the item as it drops; a kill or breed goal shows the
     * mob's spawn egg, if it has one; a harvest goal shows what picking the crop gives (wheat
     * seeds, a potato, cocoa beans ...).
     */
    private ItemStack goalIcon(QuestDoc.Goal goal) {
        return switch (goal.kind()) {
            case ITEM -> displays.computeIfAbsent(goal.target(), s -> minecraft.player == null
                    ? ItemStack.EMPTY
                    : Quests.display(s, minecraft.player.registryAccess()));
            case COLLECT -> rewardStack(goal.target());
            case KILL, BREED -> {
                SpawnEggItem egg = SpawnEggItem.byId(Quests.entityType(goal.target()));
                yield egg == null ? ItemStack.EMPTY : new ItemStack(egg);
            }
            case HARVEST -> {
                Block crop = Crops.block(goal.target());
                yield (crop == null || minecraft.level == null) ? ItemStack.EMPTY
                        : crop.getCloneItemStack(minecraft.level, BlockPos.ZERO, crop.defaultBlockState());
            }
        };
    }

    /** A hand-in goal's condition, read once per screen. */
    Predicate<ItemStack> condition(String spec) {
        return conditions.computeIfAbsent(spec, s -> minecraft.player == null
                ? stack -> false
                : Quests.conditionOrNothing(s, minecraft.player));
    }

    private static Component entityName(String id) {
        EntityType<?> type = Quests.entityType(id);
        return type == null ? Component.literal(id) : type.getDescription();
    }

    private static Component cropName(String id) {
        Block crop = Crops.block(id);
        return crop == null ? Component.literal(id) : crop.getName();
    }

    /** A reward with its components (name, enchantments, ...), read once per screen. */
    private ItemStack rewardStack(String spec) {
        return rewards.computeIfAbsent(spec, s -> minecraft.player == null
                ? ItemStack.EMPTY
                : Quests.stack(s, minecraft.player.registryAccess()));
    }

    /** The list icon: the quest's icon, else its first goal's icon, else a book. */
    ItemStack icon(QuestDoc.Quest q) {
        if (!q.icon().isEmpty()) {
            return new ItemStack(Quests.item(q.icon()));
        }
        ItemStack first = q.goals().isEmpty() ? ItemStack.EMPTY : goalIcon(q.goals().get(0));
        return first.isEmpty() ? new ItemStack(Items.BOOK) : first;
    }
}
