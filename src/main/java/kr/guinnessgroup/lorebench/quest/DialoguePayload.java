/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.Lorebench;
import kr.guinnessgroup.lorebench.client.ClientDialogue;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server → one player: what an NPC has to talk about with them (0009). Only this
 * player's quests for this NPC are in it, so an offer reaches the client only when
 * the NPC can make it.
 *
 * @param npcEntity the network id of the NPC entity the player clicked, which plays the
 *                  lines' animations on this player's screen only
 * @param resume sent after the player accepted, declined or handed something in: carry on with
 *               the list instead of starting over
 * @param said   with {@code resume}: what the NPC says first (a quest's lines for right after
 *               accepting or declining it), or empty
 */
public record DialoguePayload(String npcName, int npcEntity, List<DialogueLines.Line> greeting, List<Entry> entries,
                              boolean resume, List<DialogueLines.Line> said)
        implements CustomPacketPayload {

    /**
     * @param lines what the NPC says about it (offer, in progress or hand-in lines)
     * @param progress the player's counted progress ({@link QuestDoc.Goal#progressKey()})
     */
    public record Entry(Dialogue.Kind kind, QuestDoc.Quest quest, List<DialogueLines.Line> lines, Map<String, Integer> progress) {}

    public static final Type<DialoguePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Lorebench.MODID, "dialogue"));

    public static final StreamCodec<FriendlyByteBuf, DialoguePayload> CODEC =
            StreamCodec.ofMember(DialoguePayload::write, DialoguePayload::read);

    /** Registers both dialogue messages. Handled on the main thread (the registrar's default). */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("6")
                .playToClient(TYPE, CODEC, (payload, context) -> ClientDialogue.accept(payload))
                .playToServer(DialogueChoicePayload.TYPE, DialogueChoicePayload.CODEC, (choice, context) -> {
                    Dialogues dialogues = Dialogues.current();
                    if (dialogues != null && context.player() instanceof ServerPlayer player) {
                        dialogues.choose(player, choice);
                    }
                });
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(npcName);
        buf.writeVarInt(npcEntity);
        writeLines(buf, greeting);
        buf.writeVarInt(entries.size());
        for (Entry e : entries) {
            buf.writeEnum(e.kind());
            QuestSyncPayload.writeQuest(buf, e.quest());
            writeLines(buf, e.lines());
            QuestSyncPayload.writeProgress(buf, e.progress());
        }
        buf.writeBoolean(resume);
        writeLines(buf, said);
    }

    private static DialoguePayload read(FriendlyByteBuf buf) {
        String npcName = buf.readUtf();
        int npcEntity = buf.readVarInt();
        List<DialogueLines.Line> greeting = readLines(buf);
        int n = buf.readVarInt();
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Dialogue.Kind kind = buf.readEnum(Dialogue.Kind.class);
            QuestDoc.Quest quest = QuestSyncPayload.readQuest(buf);
            List<DialogueLines.Line> lines = readLines(buf);
            entries.add(new Entry(kind, quest, lines, QuestSyncPayload.readProgress(buf)));
        }
        boolean resume = buf.readBoolean();
        return new DialoguePayload(npcName, npcEntity, greeting, List.copyOf(entries), resume, readLines(buf));
    }

    private static void writeLines(FriendlyByteBuf buf, List<DialogueLines.Line> lines) {
        buf.writeVarInt(lines.size());
        for (DialogueLines.Line l : lines) {
            buf.writeUtf(l.text());
            buf.writeUtf(l.animation());
            buf.writeEnum(l.play());
        }
    }

    private static List<DialogueLines.Line> readLines(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<DialogueLines.Line> lines = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            lines.add(new DialogueLines.Line(buf.readUtf(), buf.readUtf(), buf.readEnum(DialogueLines.Play.class)));
        }
        return List.copyOf(lines);
    }
}
