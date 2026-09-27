/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.Speech;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which quests an NPC talks about with a player, and where the talk starts (0009). */
class DialogueTest {

    // The second story: the smith gives the sword; the guard gives the wolf hunt, handed in to the smith.
    static final QuestDoc DOC = QuestFormat.read("""
            { "format": 1, "quests": [
              { "id": "quest_sword", "title": "칼 만들기", "giver": "npc_smith",
                "lines": { "offer": ["철 5개만 가져오게."], "complete": ["자, 자네 칼일세."] }, "goals": [], "rewards": [] },
              { "id": "quest_wolf", "title": "늑대 사냥", "giver": "npc_guard", "receiver": "npc_smith",
                "requires": ["quest_sword"], "lines": { "active": ["아직인가?"] }, "goals": [], "rewards": [] },
              { "id": "quest_herb", "title": "약초", "giver": "npc_guard", "goals": [], "rewards": [] },
              { "id": "quest_graph", "title": "그래프로만", "goals": [], "rewards": [] } ] }
            """);

    static List<String> plan(String npc, Map<String, QuestState> states) {
        return Dialogue.plan(npc, DOC.quests(), id -> states.getOrDefault(id, QuestState.HIDDEN)).stream()
                .map(e -> e.kind() + " " + e.quest().id()).toList();
    }

    @Test
    void aNewPlayerIsOfferedWhatHasNoRequirements() {
        assertEquals(List.of("OFFER quest_sword"), plan("npc_smith", Map.of()));
        // The wolf hunt waits for the sword; the herb quest has no requirement.
        assertEquals(List.of("OFFER quest_herb"), plan("npc_guard", Map.of()));
        assertEquals(List.of(), plan("npc_chief", Map.of()));
    }

    @Test
    void requiredQuestsMustBeDoneNotJustTaken() {
        assertEquals(List.of("OFFER quest_herb"), plan("npc_guard", Map.of("quest_sword", QuestState.ACTIVE)));
        assertEquals(List.of("OFFER quest_herb"), plan("npc_guard", Map.of("quest_sword", QuestState.READY)));
        assertEquals(List.of("OFFER quest_wolf", "OFFER quest_herb"), plan("npc_guard", Map.of("quest_sword", QuestState.DONE)));
    }

    @Test
    void inProgressAndReadyShowAtTheReceiverNotTheGiver() {
        Map<String, QuestState> states = new HashMap<>(Map.of("quest_sword", QuestState.DONE, "quest_wolf", QuestState.ACTIVE));
        assertEquals(List.of("ACTIVE quest_wolf"), plan("npc_smith", states));
        assertEquals(List.of("OFFER quest_herb"), plan("npc_guard", states));
        states.put("quest_wolf", QuestState.READY);
        assertEquals(List.of("READY quest_wolf"), plan("npc_smith", states));
        states.put("quest_wolf", QuestState.DONE);
        assertEquals(List.of(), plan("npc_smith", states));
    }

    @Test
    void readyComesFirstThenOffersThenInProgress() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_a", "title": "A", "giver": "npc_x", "goals": [], "rewards": [] },
                  { "id": "quest_b", "title": "B", "giver": "npc_x", "goals": [], "rewards": [] },
                  { "id": "quest_c", "title": "C", "giver": "npc_x", "goals": [], "rewards": [] },
                  { "id": "quest_d", "title": "D", "giver": "npc_x", "goals": [], "rewards": [] } ] }
                """);
        Map<String, QuestState> states = Map.of("quest_a", QuestState.ACTIVE, "quest_c", QuestState.READY);
        List<Dialogue.Entry> entries = Dialogue.plan("npc_x", doc.quests(), id -> states.getOrDefault(id, QuestState.HIDDEN));
        assertEquals(List.of("READY quest_c", "OFFER quest_b", "OFFER quest_d", "ACTIVE quest_a"),
                entries.stream().map(e -> e.kind() + " " + e.quest().id()).toList());
        assertEquals("quest_c", Dialogue.start(entries).quest().id());
    }

    @Test
    void talkStartsWithTheGreetingWhenNothingCanBeDone() {
        List<Dialogue.Entry> entries = Dialogue.plan("npc_smith", DOC.quests(),
                id -> Map.of("quest_sword", QuestState.DONE, "quest_wolf", QuestState.ACTIVE).getOrDefault(id, QuestState.HIDDEN));
        assertNull(Dialogue.start(entries));
        assertNull(Dialogue.start(List.of()));
    }

    @Test
    void eachKindSaysItsOwnLines() {
        QuestDoc.Quest sword = DOC.find("quest_sword");
        assertEquals(Speech.text("철 5개만 가져오게."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.OFFER, sword)));
        assertEquals(Speech.text("자, 자네 칼일세."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.READY, sword)));
        assertEquals(Speech.NONE, Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.ACTIVE, sword)));
    }
}
