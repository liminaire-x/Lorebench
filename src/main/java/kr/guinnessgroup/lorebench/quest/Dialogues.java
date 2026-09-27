/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import com.mojang.logging.LogUtils;
import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.Speech;
import kr.guinnessgroup.lorebench.npc.NpcDoc;
import kr.guinnessgroup.lorebench.npc.NpcEntity;
import kr.guinnessgroup.lorebench.npc.Npcs;
import kr.guinnessgroup.lorebench.runtime.LorebenchRuntime;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * NPC dialogue on the server (0009): opens it when a player right-clicks an NPC, and
 * carries out what they choose. Each player's dialogue is their own; the server
 * remembers which NPC they are talking to and checks every choice again (the NPC is
 * still there and near, the quest is still theirs to accept or hand in). Server thread only.
 */
public final class Dialogues {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** How far (in blocks) a player may be from the NPC and still answer. */
    private static final double REACH = 8;

    private static volatile Dialogues current;

    private final LorebenchRuntime runtime;
    private final Quests quests;
    private final Map<UUID, Talk> talks = new HashMap<>();

    /** Who a player is talking to: the NPC definition and the placement they clicked. */
    private record Talk(String npcId, UUID entity) {}

    public Dialogues(LorebenchRuntime runtime, Quests quests) {
        this.runtime = runtime;
        this.quests = quests;
    }

    /** The running server's dialogues, or {@code null} when no server is running. */
    public static Dialogues current() {
        return current;
    }

    public void start() {
        talks.clear();
        current = this;
    }

    public void stop() {
        if (current == this) {
            current = null;
        }
        talks.clear();
    }

    /**
     * A player right-clicked an NPC. Opens its dialogue if it has anything to say: a
     * greeting (for this player), or a quest to take in, offer or wait on.
     */
    public void open(ServerPlayer player, NpcEntity npc) {
        NpcDoc.NpcDef def = runtime.npc(npc.npcId());
        List<Dialogue.Entry> plan = def == null ? List.of() : plan(player, def.id());
        if (def == null || (plan.isEmpty() && def.greeting().pick(facts(player, null)).isEmpty())) {
            talks.remove(player.getUUID());
            return;
        }
        talks.put(player.getUUID(), new Talk(def.id(), npc.getUUID()));
        send(player, def, npc, plan, false, List.of());
    }

    /**
     * The player accepted or turned down an offer, or handed a quest in. Anything that no
     * longer holds is ignored. A refusal is remembered (0012), before picking what the NPC
     * says to it, so the fifth refusal is said to with the lines for five.
     */
    public void choose(ServerPlayer player, DialogueChoicePayload choice) {
        Talk talk = talks.get(player.getUUID());
        Npcs npcs = Npcs.current();
        NpcEntity npc = (talk == null || npcs == null) ? null : npcs.loaded(player.getServer(), talk.entity());
        NpcDoc.NpcDef def = talk == null ? null : runtime.npc(talk.npcId());
        if (npc == null || def == null || !npc.npcId().equals(def.id())
                || npc.level() != player.level() || player.distanceToSqr(npc) > REACH * REACH) {
            talks.remove(player.getUUID());
            return;
        }
        // Handing in also takes the rewards of a quest whose wait is over (0013).
        Set<Dialogue.Kind> needed = choice.action() == DialogueChoicePayload.Action.HAND_IN
                ? EnumSet.of(Dialogue.Kind.READY, Dialogue.Kind.TAKE) : EnumSet.of(Dialogue.Kind.OFFER);
        boolean allowed = plan(player, def.id()).stream()
                .anyMatch(e -> needed.contains(e.kind()) && e.quest().id().equals(choice.questId()));
        List<DialogueLines.Line> said = List.of();
        String questId = choice.questId();
        if (!allowed) {
            LOGGER.debug("[Lorebench] {} chose {} {} with {}, which no longer holds",
                    player.getGameProfile().getName(), choice.action(), choice.questId(), def.id());
        } else {
            switch (choice.action()) {
                case ACCEPT -> {
                    quests.reveal(player, questId);
                    said = runtime.quest(questId).flow().lines().accepted().pick(facts(player, questId));
                }
                case DECLINE -> {
                    quests.decline(player, questId);
                    said = runtime.quest(questId).flow().lines().declined().pick(facts(player, questId));
                }
                case HAND_IN -> {
                    QuestDoc.Stage stage = quests.stage(player, runtime.quest(questId));
                    boolean handedBefore = quests.handedIn(player, questId);
                    if (quests.complete(player, questId) && !handedBefore) {
                        // Right after handing the stage in, whether it now waits (0013) or goes on (0015).
                        // Taking what comes of a wait has no lines after it.
                        said = stage.lines().handed().pick(facts(player, questId));
                    }
                }
            }
        }
        send(player, def, npc, plan(player, def.id()), true, said);
    }

    /** The player left: forget who they were talking to. */
    public void forget(ServerPlayer player) {
        talks.remove(player.getUUID());
    }

    private List<Dialogue.Entry> plan(ServerPlayer player, String npcId) {
        return Dialogue.plan(npcId, runtime.quests(), id -> quests.state(player, id), id -> quests.handedIn(player, id),
                q -> quests.stage(player, q));
    }

    /**
     * What conditions on lines ask about this player, looked up only when asked.
     *
     * @param questId the quest the lines belong to, or null for a greeting
     */
    private Speech.Facts facts(ServerPlayer player, String questId) {
        return new Speech.Facts() {
            @Override
            public int timesDeclined() {
                return questId == null ? 0 : quests.timesDeclined(player, questId);
            }

            @Override
            public QuestState questState(String id) {
                return quests.state(player, id);
            }
        };
    }

    private void send(ServerPlayer player, NpcDoc.NpcDef def, NpcEntity npc, List<Dialogue.Entry> plan, boolean resume,
                      List<DialogueLines.Line> said) {
        List<DialoguePayload.Entry> entries = new ArrayList<>();
        for (Dialogue.Entry e : plan) {
            QuestDoc.Quest q = e.quest();
            // The stages up to this one, never those ahead. An offer shows the rewards to win; a stage
            // shows them only when handing it in gives them, as the last one (0015).
            QuestDoc.Quest seen = q.upTo(q.stages().indexOf(e.stage()),
                    e.kind() == Dialogue.Kind.OFFER || q.after(e.stage()) == null);
            entries.add(new DialoguePayload.Entry(e.kind(), seen, Dialogue.lines(e).pick(facts(player, q.id())),
                    quests.progress(player, q.id())));
        }
        // Only the picked lines are sent: the conditions stay on the server.
        List<DialogueLines.Line> greeting = def.greeting().pick(facts(player, null));
        PacketDistributor.sendToPlayer(player,
                new DialoguePayload(def.name(), npc.getId(), def.voice(), greeting, List.copyOf(entries), resume, said));
    }
}
