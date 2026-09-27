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

    /**
     * A revealed quest, where the player is with it, and their counted progress so far.
     *
     * @param quest the stages up to the player's (all once it is done, none if theirs was removed),
     *              never those ahead; the last is the one they are on (0015)
     * @param state ACTIVE (the screen tells "ready" from the inventory itself), WAITING (handed in),
     *              READY (handed in and the wait is over: the rewards can be taken) or DONE
     */
    public record Entry(QuestDoc.Quest quest, QuestState state, Map<String, Integer> progress) {}

    public static final Type<QuestSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Lorebench.MODID, "quests"));

    public static final StreamCodec<FriendlyByteBuf, QuestSyncPayload> CODEC =
            StreamCodec.ofMember(QuestSyncPayload::write, QuestSyncPayload::read);

    public static void register(RegisterPayloadHandlersEvent event) {
        // Handled on the client's main thread (the registrar's default).
        event.registrar("7").playToClient(TYPE, CODEC, (payload, context) -> ClientQuests.accept(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeVarInt(quests.size());
        for (Entry e : quests) {
            writeQuest(buf, e.quest());
            buf.writeEnum(e.state());
            writeProgress(buf, e.progress());
        }
    }

    private static QuestSyncPayload read(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Entry> quests = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            QuestDoc.Quest q = readQuest(buf);
            QuestState state = buf.readEnum(QuestState.class);
            quests.add(new Entry(q, state, readProgress(buf)));
        }
        return new QuestSyncPayload(List.copyOf(quests));
    }

    /**
     * What a player's screen shows of a quest: id, title, icon, story, rewards, supplies, and each
     * stage given (the caller cuts them with {@link QuestDoc.Quest#upTo}) with what to do and its goals.
     * Folders are for the editor only, and givers, NPCs, lines, waits and gifts stay on the server (dialogue
     * sends the lines it needs, the state says whether it is waiting, and a gift is seen when it is given),
     * so they aren't sent.
     * Shared with {@link DialoguePayload}.
     */
    static void writeQuest(FriendlyByteBuf buf, QuestDoc.Quest q) {
        buf.writeUtf(q.id());
        buf.writeUtf(q.title());
        buf.writeUtf(q.icon());
        buf.writeUtf(q.text());
        writeStacks(buf, q.rewards());
        writeStacks(buf, q.supplies());
        buf.writeVarInt(q.stages().size());
        for (QuestDoc.Stage s : q.stages()) {
            buf.writeUtf(s.id());
            buf.writeUtf(s.text());
            buf.writeVarInt(s.goals().size());
            for (QuestDoc.Goal g : s.goals()) {
                buf.writeEnum(g.kind());
                buf.writeUtf(g.target());
                buf.writeVarInt(g.count());
                buf.writeBoolean(g.keep());
            }
        }
    }

    static QuestDoc.Quest readQuest(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String title = buf.readUtf();
        String icon = buf.readUtf();
        String text = buf.readUtf();
        List<QuestDoc.Stack> rewards = readStacks(buf);
        List<QuestDoc.Stack> supplies = readStacks(buf);
        int n = buf.readVarInt();
        List<QuestDoc.Stage> stages = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            stages.add(new QuestDoc.Stage(buf.readUtf(), buf.readUtf(), "", readGoals(buf), 0, List.of(),
                    QuestDoc.StageLines.NONE));
        }
        return new QuestDoc.Quest(id, title, icon, text, rewards, supplies, "", QuestDoc.Flow.NONE, List.copyOf(stages));
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
            out.add(new QuestDoc.Goal(buf.readEnum(QuestDoc.Goal.Kind.class), buf.readUtf(), buf.readVarInt(), "", 1,
                    buf.readBoolean()));
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
