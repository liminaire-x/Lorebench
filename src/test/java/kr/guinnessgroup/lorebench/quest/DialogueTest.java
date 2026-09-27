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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which quests an NPC talks about with a player, and where the talk starts (0009, 0015). */
class DialogueTest {

    // The second story: the smith gives the sword; the guard gives the wolf hunt, handed in to the smith.
    static final QuestDoc DOC = QuestFormat.read("""
            { "format": 1, "quests": [
              { "id": "quest_sword", "title": "칼 만들기", "giver": "npc_smith", "lines": { "offer": ["철 5개만 가져오게."] },
                "stages": [ { "id": "stage_sword", "text": "철", "lines": { "complete": ["자, 자네 칼일세."] } } ], "rewards": [] },
              { "id": "quest_wolf", "title": "늑대 사냥", "giver": "npc_guard", "requires": ["quest_sword"],
                "stages": [ { "id": "stage_wolf", "text": "늑대", "to": "npc_smith", "lines": { "active": ["아직인가?"] } } ],
                "rewards": [] },
              { "id": "quest_herb", "title": "약초", "giver": "npc_guard", "stages": [ { "id": "stage_herb", "text": "약초" } ],
                "rewards": [] },
              { "id": "quest_graph", "title": "그래프로만", "stages": [ { "id": "stage_graph", "text": "?" } ], "rewards": [] } ] }
            """);

    static List<String> plan(String npc, Map<String, QuestState> states) {
        return plan(DOC, npc, states, Set.of(), Map.of());
    }

    /**
     * @param handedIn quests the player handed in to wait (0013)
     * @param stages   the stage each quest's player is on, by quest id; the first when missing (0015)
     */
    static List<String> plan(QuestDoc doc, String npc, Map<String, QuestState> states, Set<String> handedIn,
                             Map<String, String> stages) {
        return entries(doc, npc, states, handedIn, stages).stream()
                .map(e -> e.kind() + " " + e.quest().id()).toList();
    }

    static List<Dialogue.Entry> entries(QuestDoc doc, String npc, Map<String, QuestState> states, Set<String> handedIn,
                                        Map<String, String> stages) {
        return Dialogue.plan(npc, doc.quests(), id -> states.getOrDefault(id, QuestState.HIDDEN), handedIn::contains,
                q -> QuestStages.of(q, stages.get(q.id())));
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
    void inProgressAndReadyShowAtTheStagesNpcNotTheGiver() {
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
                  { "id": "quest_a", "title": "A", "giver": "npc_x", "stages": [ { "id": "stage_a", "text": "a" } ], "rewards": [] },
                  { "id": "quest_b", "title": "B", "giver": "npc_x", "stages": [ { "id": "stage_b", "text": "b" } ], "rewards": [] },
                  { "id": "quest_c", "title": "C", "giver": "npc_x", "stages": [ { "id": "stage_c", "text": "c" } ], "rewards": [] },
                  { "id": "quest_d", "title": "D", "giver": "npc_x", "stages": [ { "id": "stage_d", "text": "d" } ], "rewards": [] } ] }
                """);
        List<Dialogue.Entry> entries = entries(doc, "npc_x", Map.of("quest_a", QuestState.ACTIVE, "quest_c", QuestState.READY),
                Set.of(), Map.of());
        assertEquals(List.of("READY quest_c", "OFFER quest_b", "OFFER quest_d", "ACTIVE quest_a"),
                entries.stream().map(e -> e.kind() + " " + e.quest().id()).toList());
        assertEquals("quest_c", Dialogue.start(entries).quest().id());
    }

    @Test
    void talkStartsWithTheGreetingWhenNothingCanBeDone() {
        List<Dialogue.Entry> entries = entries(DOC, "npc_smith",
                Map.of("quest_sword", QuestState.DONE, "quest_wolf", QuestState.ACTIVE), Set.of(), Map.of());
        assertNull(Dialogue.start(entries));
        assertNull(Dialogue.start(List.of()));
    }

    // The fifth story: the smith takes the iron, and the sword is ready the next day (0013).
    static final QuestDoc ORDER = QuestFormat.read("""
            { "format": 1, "quests": [
              { "id": "quest_order", "title": "칼 주문", "giver": "npc_smith",
                "stages": [ { "id": "stage_order", "text": "철", "goals": [ { "item": "minecraft:iron_ingot", "count": 5 } ],
                              "wait": { "days": 1 } } ], "rewards": [] },
              { "id": "quest_herb", "title": "약초", "giver": "npc_smith", "stages": [ { "id": "stage_herb", "text": "약초" } ],
                "rewards": [] },
              { "id": "quest_after", "title": "그 뒤", "giver": "npc_smith", "requires": ["quest_order"],
                "stages": [ { "id": "stage_after", "text": "그 뒤" } ], "rewards": [] } ] }
            """);

    @Test
    void aQuestHandedInWaitsAtTheStagesNpcThenWhatComesOfItIsTaken() {
        Set<String> handed = Set.of("quest_order");
        // Waiting: a line in the list after the offers; the talk starts with the herb offer, not with it.
        assertEquals(List.of("OFFER quest_herb", "WAITING quest_order"),
                plan(ORDER, "npc_smith", Map.of("quest_order", QuestState.WAITING), handed, Map.of()));
        assertEquals(List.of("WAITING quest_order"), plan(ORDER, "npc_smith",
                Map.of("quest_order", QuestState.WAITING, "quest_herb", QuestState.DONE), handed, Map.of()));
        // The wait is over: taking comes first, and the next quest waits until it is taken.
        List<Dialogue.Entry> take = entries(ORDER, "npc_smith", Map.of("quest_order", QuestState.READY), handed, Map.of());
        assertEquals(List.of("TAKE quest_order", "OFFER quest_herb"),
                take.stream().map(e -> e.kind() + " " + e.quest().id()).toList());
        assertEquals("quest_order", Dialogue.start(take).quest().id());
        // Ready but not handed in yet: handing in, as before.
        assertEquals(List.of("READY quest_order", "OFFER quest_herb"),
                plan(ORDER, "npc_smith", Map.of("quest_order", QuestState.READY), Set.of(), Map.of()));
        assertEquals(List.of("OFFER quest_herb", "OFFER quest_after"),
                plan(ORDER, "npc_smith", Map.of("quest_order", QuestState.DONE), Set.of(), Map.of()));
    }

    // The seventh story: shown to the guard, left with the smith, given to the daughter (0015).
    static final QuestDoc NECKLACE = QuestFormat.read("""
            { "format": 1, "quests": [ { "id": "quest_necklace", "title": "목걸이 찾기", "giver": "npc_guard",
              "lines": { "offer": ["조각이라도 찾아 주겠나?"] },
              "stages": [
                { "id": "stage_show", "text": "보여주기", "lines": { "active": ["셋 다 모으면 가져오게."], "complete": ["이건…"] } },
                { "id": "stage_mend", "text": "맡기기", "to": "npc_smith", "wait": { "days": 1 },
                  "lines": { "complete": ["어디 보세."], "waiting": ["아직 손보는 중일세."], "ready": ["다 됐네."] } },
                { "id": "stage_give", "text": "전하기", "to": "npc_daughter", "lines": { "complete": ["고마워요!"] } } ],
              "rewards": [] } ] }
            """);

    @Test
    void eachStageIsTalkedAboutWithItsOwnNpc() {
        Map<String, QuestState> active = Map.of("quest_necklace", QuestState.ACTIVE);
        // The first stage: with the giver, who names no other.
        assertEquals(List.of("ACTIVE quest_necklace"), plan(NECKLACE, "npc_guard", active, Set.of(), Map.of()));
        assertEquals(List.of(), plan(NECKLACE, "npc_smith", active, Set.of(), Map.of()));
        // On to the smith: the guard has nothing more to say of it.
        Map<String, String> mend = Map.of("quest_necklace", "stage_mend");
        assertEquals(List.of(), plan(NECKLACE, "npc_guard", active, Set.of(), mend));
        assertEquals(List.of("ACTIVE quest_necklace"), plan(NECKLACE, "npc_smith", active, Set.of(), mend));
        List<Dialogue.Entry> give = entries(NECKLACE, "npc_daughter", Map.of("quest_necklace", QuestState.READY), Set.of(),
                Map.of("quest_necklace", "stage_give"));
        assertEquals("stage_give", give.getFirst().stage().id());
        assertEquals(Speech.text("고마워요!"), Dialogue.lines(give.getFirst()));
        // A stage the quest no longer has: nobody talks about it (0015).
        assertEquals(List.of(), plan(NECKLACE, "npc_guard", active, Set.of(), Map.of("quest_necklace", "stage_gone")));
    }

    @Test
    void anOfferIsAboutTheFirstStageWhateverWasLeftBehind() {
        List<Dialogue.Entry> offer = entries(NECKLACE, "npc_guard", Map.of(), Set.of(), Map.of("quest_necklace", "stage_gone"));
        assertEquals("OFFER quest_necklace", offer.getFirst().kind() + " " + offer.getFirst().quest().id());
        assertEquals("stage_show", offer.getFirst().stage().id());
        assertEquals(Speech.text("조각이라도 찾아 주겠나?"), Dialogue.lines(offer.getFirst()));
    }

    @Test
    void eachKindSaysItsOwnLines() {
        QuestDoc.Quest necklace = NECKLACE.find("quest_necklace");
        QuestDoc.Stage show = necklace.stage("stage_show");
        QuestDoc.Stage mend = necklace.stage("stage_mend");
        assertEquals(Speech.text("셋 다 모으면 가져오게."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.ACTIVE, necklace, show)));
        assertEquals(Speech.text("이건…"), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.READY, necklace, show)));
        assertEquals(Speech.NONE, Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.WAITING, necklace, show)));
        assertEquals(Speech.text("어디 보세."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.READY, necklace, mend)));
        assertEquals(Speech.text("아직 손보는 중일세."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.WAITING, necklace, mend)));
        assertEquals(Speech.text("다 됐네."), Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.TAKE, necklace, mend)));
        assertEquals(Speech.NONE, Dialogue.lines(new Dialogue.Entry(Dialogue.Kind.ACTIVE, necklace, mend)));
    }
}
