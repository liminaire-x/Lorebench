/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.Speech;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * What an NPC has to talk about with one player: the quests it can take in, offer,
 * or is waiting on. The player's states come from outside, so this is plain logic.
 * See docs/decisions/0009-quest-workbench.md.
 */
public final class Dialogue {

    /** In the order the dialogue lists them. Sent to the client by name order, never saved. */
    public enum Kind {
        /** The player can hand it in to this NPC now. */
        READY,
        /** The player handed it in and the wait is over: they take the rewards from this NPC now (0013). */
        TAKE,
        /** This NPC can offer it: the player has not taken it and has done every required quest. */
        OFFER,
        /** The player is on it and hands it in to this NPC, but is not ready yet. */
        ACTIVE,
        /** The player handed it in to this NPC and waits for the rewards (0013). */
        WAITING;

        /** Something the player can do now, so the talk starts with it. */
        public boolean startsTalk() {
            return this == READY || this == TAKE || this == OFFER;
        }
    }

    public record Entry(Kind kind, QuestDoc.Quest quest) {}

    private Dialogue() {}

    /**
     * Everything this NPC can talk about with the player, in {@link Kind}'s order, each kind
     * in the quest document's order.
     *
     * @param state    the player's state of a quest, by id (READY when an active quest's goals are met,
     *                 or a waiting quest's wait is over)
     * @param handedIn whether the player has handed a quest in, by id (it is waiting, or ready to take)
     */
    public static List<Entry> plan(String npcId, List<QuestDoc.Quest> quests, Function<String, QuestState> state,
                                   Predicate<String> handedIn) {
        List<Entry> entries = new ArrayList<>();
        for (QuestDoc.Quest q : quests) {
            QuestDoc.Flow flow = q.flow();
            boolean handsInHere = npcId.equals(flow.handInTo());
            QuestState s = state.apply(q.id());
            if (s == QuestState.READY && handsInHere) {
                entries.add(new Entry(handedIn.test(q.id()) ? Kind.TAKE : Kind.READY, q));
            } else if (s == QuestState.HIDDEN && npcId.equals(flow.giver())
                    && flow.requires().stream().allMatch(r -> state.apply(r) == QuestState.DONE)) {
                entries.add(new Entry(Kind.OFFER, q));
            } else if (s == QuestState.ACTIVE && handsInHere) {
                entries.add(new Entry(Kind.ACTIVE, q));
            } else if (s == QuestState.WAITING && handsInHere) {
                entries.add(new Entry(Kind.WAITING, q));
            }
        }
        entries.sort(Comparator.comparing(Entry::kind)); // stable: keeps the document's order within a kind
        return List.copyOf(entries);
    }

    /** Where the talk starts: the first thing the player can do now, or null to start with the greeting. */
    public static Entry start(List<Entry> entries) {
        return entries.isEmpty() || !entries.get(0).kind().startsTalk() ? null : entries.get(0);
    }

    /**
     * What the NPC says for an entry, before picking by condition ({@link Speech#pick}).
     * Waiting and taking have no lines yet (their own come with the fifth story's second part).
     */
    public static Speech lines(Entry entry) {
        QuestDoc.Lines lines = entry.quest().flow().lines();
        return switch (entry.kind()) {
            case READY -> lines.complete();
            case OFFER -> lines.offer();
            case ACTIVE -> lines.active();
            case TAKE, WAITING -> Speech.NONE;
        };
    }
}
