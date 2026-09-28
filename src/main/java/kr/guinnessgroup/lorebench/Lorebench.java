/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import kr.guinnessgroup.lorebench.client.LorebenchClient;
import kr.guinnessgroup.lorebench.nodes.BuiltinNodes;
import kr.guinnessgroup.lorebench.nodes.OnPlayerJoin;
import kr.guinnessgroup.lorebench.npc.LorebenchEntities;
import kr.guinnessgroup.lorebench.npc.NpcCommands;
import kr.guinnessgroup.lorebench.npc.Npcs;
import kr.guinnessgroup.lorebench.quest.DialoguePayload;
import kr.guinnessgroup.lorebench.quest.Dialogues;
import kr.guinnessgroup.lorebench.quest.QuestSyncPayload;
import kr.guinnessgroup.lorebench.quest.Quests;
import kr.guinnessgroup.lorebench.record.H2RecordBackend;
import kr.guinnessgroup.lorebench.record.Owner;
import kr.guinnessgroup.lorebench.record.RecordStore;
import kr.guinnessgroup.lorebench.runtime.LorebenchRuntime;
import kr.guinnessgroup.lorebench.runtime.NodeRegistry;
import kr.guinnessgroup.lorebench.web.LorebenchWebServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Mod entry point: wires the pieces together and connects them to game events. */
@Mod(Lorebench.MODID)
public final class Lorebench {

    public static final String MODID = "lorebench";

    private final Path dir = FMLPaths.CONFIGDIR.get().resolve(MODID);
    private final NodeRegistry nodes = new NodeRegistry();
    private final RecordStore records = new RecordStore();
    private final LorebenchRuntime runtime = new LorebenchRuntime(nodes, records, dir, Quests.CHECKS);
    private final Npcs npcs = new Npcs(runtime, records);
    private final Quests quests = new Quests(runtime, records);
    private final Dialogues dialogues = new Dialogues(runtime, quests);
    private final LorebenchWebServer web = new LorebenchWebServer(runtime, nodes, npcs);

    public Lorebench(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, LorebenchConfig.SPEC);
        LorebenchEntities.TYPES.register(modEventBus);
        modEventBus.addListener(LorebenchEntities::onAttributes);
        modEventBus.addListener(QuestSyncPayload::register);
        modEventBus.addListener(DialoguePayload::register);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            LorebenchClient.init(modEventBus);
        }
        BuiltinNodes.registerAll(nodes);
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Owner server = Owner.server(LorebenchConfig.serverName());
        records.open(new H2RecordBackend(dir.resolve("records")), server);
        runtime.load(server);
        npcs.start();
        quests.start();
        dialogues.start();
        // Publish arrives on the web thread; quest content goes out on the server thread.
        MinecraftServer mc = event.getServer();
        runtime.onPublish(() -> mc.execute(() -> quests.syncAll(mc)));
        // Players on removed stages move with the records, which the server thread uses (0015).
        runtime.onStageMoves((before, after, moves) ->
                onServerThread(mc, () -> quests.moveStages(before, after, moves)));
        web.start();
    }

    /** Runs {@code task} on the server thread and waits for it; a rejection comes back as itself. */
    private static void onServerThread(MinecraftServer mc, Runnable task) {
        try {
            mc.submit(task).get(5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DocumentException rejected) {
                throw rejected;
            }
            throw new IllegalStateException("moving players between stages failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted; nothing was published", e);
        } catch (TimeoutException e) {
            throw new IllegalStateException("the game did not answer in time; nothing was published", e);
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        web.stop();
        runtime.onPublish(null);
        runtime.onStageMoves(null);
        dialogues.stop();
        quests.stop();
        npcs.stop();
        runtime.clear();
        records.close();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        NpcCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            // Load the player's records before any graph can read them.
            records.load(Owner.player(player.getUUID()));
            quests.sync(player);
            runtime.fire(OnPlayerJoin.ID, player.getServer(), player, Map.of());
        }
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            dialogues.forget(player);
            records.release(Owner.player(player.getUUID()));
            // The game saves the player's inventory as they leave; save their records
            // now too, so a crash before the next world save cannot split the two
            // (e.g. keep a quest reward but lose the "done" record).
            records.flush();
        }
    }

    /**
     * Last in line, and only if no other mod cancelled the death, so only real kills
     * count. The killer is whoever the damage came from (the shooter, for arrows).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            quests.onKill(player, event.getEntity());
        }
    }

    /**
     * Last in line, and only if no other mod (or the game, e.g. adventure mode) stopped
     * the break, so only real harvests count. Only players' breaks arrive here; water,
     * pistons and explosions don't. Creative-mode breaks count too.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player) {
            quests.onHarvest(player, event.getState());
        }
    }

    /**
     * Last in line, and only if no other mod cancelled the birth. Every player who fed
     * one of the two parents counts it (the game's own statistic credits only one).
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onBabyBorn(BabyEntitySpawnEvent event) {
        AgeableMob baby = event.getChild();
        if (baby == null) {
            return; // another mod took the baby away
        }
        Set<ServerPlayer> feeders = new LinkedHashSet<>();
        for (Mob parent : new Mob[] {event.getParentA(), event.getParentB()}) {
            if (parent instanceof Animal animal && animal.getLoveCause() != null) {
                feeders.add(animal.getLoveCause());
            }
        }
        feeders.forEach(player -> quests.onBreed(player, baby));
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        quests.onServerTick(event.getServer());
    }

    @SubscribeEvent
    public void onWorldSave(LevelEvent.Save event) {
        // Save records together with the world. The first dimension's save writes
        // everything; later ones find nothing left to write.
        records.flush();
    }
}
