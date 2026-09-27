/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.Lorebench;
import kr.guinnessgroup.lorebench.client.ClientQuests;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Server → one player: every quest revealed to that player, with its content.
 * Sent whole each time (on join, on reveal, after publish); a quest that was never
 * revealed to the player never reaches their client.
 */
public record QuestSyncPayload(List<Entry> quests) implements CustomPacketPayload {

    /** A revealed quest, whether the player has completed it, and their counted progress so far. */
    public record Entry(QuestDoc.Quest quest, boolean done, Map<String, Integer> progress) {}

    public static final Type<QuestSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Lorebench.MODID, "quests"));

    public static final StreamCodec<FriendlyByteBuf, QuestSyncPayload> CODEC =
            StreamCodec.ofMember(QuestSyncPayload::write, QuestSyncPayload::read);

    public static void register(RegisterPayloadHandlersEvent event) {
        // Handled on the client's main thread (the registrar's default).
        event.registrar("4").playToClient(TYPE, CODEC, (payload, context) -> ClientQuests.accept(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(quests.size());
        for (Entry e : quests) {
            writeQuest(buf, e.quest());
            buf.writeBoolean(e.done());
            writeProgress(buf, e.progress());
        }
    }

    private static QuestSyncPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> quests = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            QuestDoc.Quest q = readQuest(buf);
            boolean done = buf.readBoolean();
            quests.add(new Entry(q, done, readProgress(buf)));
        }
        return new QuestSyncPayload(List.copyOf(quests));
    }

    /**
     * What a player's screen shows of a quest: id, title, icon, story, goals, rewards and supplies.
     * Folders are for the editor only and givers and lines stay on the server (dialogue
     * sends the lines it needs), so they aren't sent. Shared with {@link DialoguePayload}.
     */
    static void writeQuest(FriendlyByteBuf buf, QuestDoc.Quest q) {
        buf.writeUtf(q.id());
        buf.writeUtf(q.title());
        buf.writeUtf(q.icon());
        buf.writeUtf(q.text());
        buf.writeVarInt(q.goals().size());
        for (QuestDoc.Goal g : q.goals()) {
            buf.writeEnum(g.kind());
            buf.writeUtf(g.target());
            buf.writeVarInt(g.count());
        }
        writeStacks(buf, q.rewards());
        writeStacks(buf, q.supplies());
    }

    static QuestDoc.Quest readQuest(FriendlyByteBuf buf) {
        return new QuestDoc.Quest(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                readGoals(buf), readStacks(buf), readStacks(buf), "", QuestDoc.Flow.NONE);
    }

    static void writeProgress(FriendlyByteBuf buf, Map<String, Integer> progress) {
        buf.writeVarInt(progress.size());
        progress.forEach((key, n) -> {
            buf.writeUtf(key);
            buf.writeVarInt(n);
        });
    }

    static Map<String, Integer> readProgress(FriendlyByteBuf buf) {
        int k = buf.readVarInt();
        Map<String, Integer> progress = new HashMap<>();
        for (int j = 0; j < k; j++) {
            progress.put(buf.readUtf(), buf.readVarInt());
        }
        return Map.copyOf(progress);
    }

    private static List<QuestDoc.Goal> readGoals(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<QuestDoc.Goal> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new QuestDoc.Goal(buf.readEnum(QuestDoc.Goal.Kind.class), buf.readUtf(), buf.readVarInt()));
        }
        return List.copyOf(out);
    }

    private static void writeStacks(FriendlyByteBuf buf, List<QuestDoc.Stack> stacks) {
        buf.writeVarInt(stacks.size());
        for (QuestDoc.Stack s : stacks) {
            buf.writeUtf(s.item());
            buf.writeVarInt(s.count());
        }
    }

    private static List<QuestDoc.Stack> readStacks(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<QuestDoc.Stack> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new QuestDoc.Stack(buf.readUtf(), buf.readVarInt()));
        }
        return List.copyOf(out);
    }
}
