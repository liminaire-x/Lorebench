/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.npc.LorebenchEntities;
import kr.guinnessgroup.lorebench.quest.DialoguePayload;
import kr.guinnessgroup.lorebench.quest.QuestItemEntity;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only setup. Only referenced when running on a client, so a dedicated
 * server never loads client classes.
 */
public final class LorebenchClient {

    /** Opens (and closes) the quest screen. J by default; players can rebind it. */
    static final KeyMapping OPEN_QUESTS = new KeyMapping("key.lorebench.quests", GLFW.GLFW_KEY_J, "key.categories.lorebench");

    private LorebenchClient() {}

    public static void init(IEventBus modEventBus) {
        modEventBus.addListener(LorebenchClient::onRegisterRenderers);
        modEventBus.addListener(LorebenchClient::onRegisterKeys);
        NeoForge.EVENT_BUS.addListener(LorebenchClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(LorebenchClient::onLoggingOut);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(LorebenchEntities.NPC.get(), NpcRenderer::new);
        // A quest item is drawn like any dropped item.
        event.registerEntityRenderer(LorebenchEntities.QUEST_ITEM.get(), LorebenchClient::questItemRenderer);
    }

    @SuppressWarnings("unchecked")
    private static EntityRenderer<QuestItemEntity> questItemRenderer(EntityRendererProvider.Context context) {
        return (EntityRenderer<QuestItemEntity>) (EntityRenderer<?>) new ItemEntityRenderer(context);
    }

    private static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_QUESTS);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        DialoguePayload talk = ClientDialogue.take();
        if (talk != null && mc.player != null) {
            if (mc.screen instanceof DialogueScreen screen) {
                screen.update(talk);
            } else if (!talk.resume() && mc.screen == null) {
                mc.setScreen(new DialogueScreen(talk));
            }
        }
        while (OPEN_QUESTS.consumeClick()) {
            if (mc.screen == null && mc.player != null) {
                mc.setScreen(new QuestScreen());
            }
        }
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientQuests.clear();
        ClientDialogue.clear();
    }
}
