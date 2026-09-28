/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import com.google.gson.JsonParser;
import kr.guinnessgroup.lorebench.quest.QuestState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lines picked by condition (0012). Breaking these changes what NPCs say to whom. */
class SpeechTest {

    static final String NECKLACE_OFFER = """
            [ { "when": { "timesDeclined": { "min": 5 } }, "lines": [ "…자네, 일부러 그러는 거지?" ] },
              { "when": { "timesDeclined": { "min": 1 } }, "lines": [ "마음이 바뀌었나?" ] },
              { "lines": [ "늑대가 딸의 목걸이를 물고 달아났네." ] } ]
            """;

    static Speech read(String json, boolean inQuest, List<String> errors) {
        return Speech.read(JsonParser.parseString(json), inQuest, "lines", errors);
    }

    /** A player who turned the quest down {@code times} times and is in these states. */
    static Speech.Facts player(int times, Map<String, QuestState> states) {
        return player(times, states, Map.of());
    }

    /** ... and on these stages (quest id → stage id) of the quests they are on. */
    static Speech.Facts player(int times, Map<String, QuestState> states, Map<String, String> stages) {
        return new Speech.Facts() {
            @Override
            public int timesDeclined() {
                return times;
            }

            @Override
            public QuestState questState(String questId) {
                return states.getOrDefault(questId, QuestState.HIDDEN);
            }

            @Override
            public String stage(String questId) {
                return stages.get(questId);
            }
        };
    }

    static String said(Speech speech, Speech.Facts facts) {
        List<DialogueLines.Line> lines = speech.pick(facts);
        return lines.isEmpty() ? "" : lines.get(0).text();
    }

    @Test
    void theFirstGroupThatHoldsIsSaid() {
        List<String> errors = new ArrayList<>();
        Speech offer = read(NECKLACE_OFFER, true, errors);
        assertEquals(List.of(), errors);
        assertEquals("늑대가 딸의 목걸이를 물고 달아났네.", said(offer, player(0, Map.of())));
        assertEquals("마음이 바뀌었나?", said(offer, player(1, Map.of())));
        assertEquals("마음이 바뀌었나?", said(offer, player(4, Map.of())));
        assertEquals("…자네, 일부러 그러는 거지?", said(offer, player(5, Map.of())));
        assertEquals("…자네, 일부러 그러는 거지?", said(offer, player(9, Map.of())));
    }

    @Test
    void countsAreWrittenAsMinecraftRanges() {
        List<String> errors = new ArrayList<>();
        Speech declined = read("""
                [ { "when": { "timesDeclined": 5 }, "lines": [ "다섯 번째라니!" ] },
                  { "when": { "timesDeclined": { "min": 2, "max": 3 } }, "lines": [ "또인가." ] },
                  { "when": { "timesDeclined": { "max": 1 } }, "lines": [ "그래… 무리한 부탁이지." ] } ]
                """, true, errors);
        assertEquals(List.of(), errors);
        assertEquals("그래… 무리한 부탁이지.", said(declined, player(1, Map.of())));
        assertEquals("또인가.", said(declined, player(3, Map.of())));
        assertEquals("", said(declined, player(4, Map.of()))); // no group holds: nothing to say
        assertEquals("다섯 번째라니!", said(declined, player(5, Map.of())));
        assertEquals("", said(declined, player(6, Map.of())));
        String written = Speech.write(declined).toString();
        assertTrue(written.contains("\"timesDeclined\":5"), written);
        assertTrue(written.contains("\"timesDeclined\":{\"min\":2,\"max\":3}"), written);
        assertEquals(declined, read(written, true, errors));
    }

    @Test
    void everyConditionInOneWhenMustHold() {
        List<String> errors = new ArrayList<>();
        Speech greeting = read("""
                [ { "when": { "questState": { "quest_sword": "done", "quest_necklace": "done" } }, "lines": [ "둘 다 해냈군!" ] },
                  { "when": { "questState": { "quest_necklace": "active" } }, "lines": [ "조각은 찾았나?" ] },
                  { "lines": [ "오, 자네 왔군." ] } ]
                """, false, errors);
        assertEquals(List.of(), errors);
        assertEquals("오, 자네 왔군.", said(greeting, player(0, Map.of("quest_sword", QuestState.DONE))));
        assertEquals("조각은 찾았나?", said(greeting, player(0, Map.of("quest_necklace", QuestState.ACTIVE))));
        assertEquals("둘 다 해냈군!", said(greeting,
                player(0, Map.of("quest_sword", QuestState.DONE, "quest_necklace", QuestState.DONE))));
        assertEquals(Set.of("quest_sword", "quest_necklace"), greeting.questsNamed());
        assertEquals(greeting, read(Speech.write(greeting).toString(), false, errors));
    }

    @Test
    void aStageHoldsWhateverTheStateAndCanBeNarrowedByIt() {
        List<String> errors = new ArrayList<>();
        Speech greeting = read("""
                [ { "when": { "stage": { "quest_necklace": "stage_smith" }, "questState": { "quest_necklace": "waiting" } },
                    "lines": [ "대장장이 솜씨라면 믿을 만하지." ] },
                  { "when": { "stage": { "quest_necklace": "stage_smith" } }, "lines": [ "대장장이에게는 가 봤나?" ] },
                  { "when": { "stage": { "quest_necklace": "stage_daughter" } }, "lines": [ "어서 딸에게 전해 주게." ] },
                  { "lines": [ "요즘 늑대가 부쩍 늘었어." ] } ]
                """, false, errors);
        assertEquals(List.of(), errors);
        Map<String, String> atSmith = Map.of("quest_necklace", "stage_smith");
        // Holding the shards makes the quest ready, and the stage still holds.
        assertEquals("대장장이에게는 가 봤나?", said(greeting, player(0, Map.of("quest_necklace", QuestState.READY), atSmith)));
        assertEquals("대장장이에게는 가 봤나?", said(greeting, player(0, Map.of("quest_necklace", QuestState.ACTIVE), atSmith)));
        assertEquals("대장장이 솜씨라면 믿을 만하지.",
                said(greeting, player(0, Map.of("quest_necklace", QuestState.WAITING), atSmith)));
        assertEquals("어서 딸에게 전해 주게.", said(greeting,
                player(0, Map.of("quest_necklace", QuestState.READY), Map.of("quest_necklace", "stage_daughter"))));
        assertEquals("요즘 늑대가 부쩍 늘었어.", said(greeting, player(0, Map.of())));
        assertEquals(Set.of("quest_necklace"), greeting.questsNamed());
        assertEquals(Map.of("quest_necklace", Set.of("stage_smith", "stage_daughter")), greeting.stagesNamed());
        assertEquals(greeting, read(Speech.write(greeting).toString(), false, errors));
        assertTrue(Speech.write(greeting).toString().contains("\"stage\":{\"quest_necklace\":\"stage_smith\"}"));
    }

    @Test
    void namedQuestsAndStagesMustExist() {
        List<String> errors = new ArrayList<>();
        Speech greeting = read("""
                [ { "when": { "questState": { "quest_sword": "done" } }, "lines": [ "칼은 잘 쓰고 있나?" ] },
                  { "when": { "stage": { "quest_necklace": "stage_smith" } }, "lines": [ "대장장이에게는 가 봤나?" ] } ]
                """, false, errors);
        assertEquals(List.of(), errors);
        assertEquals(List.of(), greeting.namingErrors(Map.of("quest_sword", List.of("stage_a"),
                "quest_necklace", List.of("stage_shards", "stage_smith"))));
        assertEquals(List.of("quest 'quest_necklace' has no stage 'stage_smith'"),
                greeting.namingErrors(Map.of("quest_sword", List.of("stage_a"), "quest_necklace", List.of("stage_shards"))));
        assertEquals(List.of("quest 'quest_sword' does not exist", "quest 'quest_necklace' does not exist"),
                greeting.namingErrors(Map.of()));
    }

    @Test
    void plainLinesStayPlain() {
        List<String> errors = new ArrayList<>();
        Speech plain = read("[ \"고맙네!\", { \"text\": \"잘 가게.\", \"animation\": \"animation.chief.wave\" } ]", true, errors);
        assertEquals(List.of(), errors);
        assertEquals(1, plain.groups().size());
        assertEquals("[\"고맙네!\",{\"text\":\"잘 가게.\",\"animation\":\"animation.chief.wave\"}]", Speech.write(plain).toString());
        assertEquals(Speech.NONE, read("[]", true, errors));
        assertEquals(Speech.NONE, Speech.read(null, true, "lines", errors));
        assertEquals(Speech.NONE, read("[ { \"lines\": [] } ]", true, errors));
        assertEquals(List.of(), errors);
        // A group that holds but says nothing: the NPC says nothing in that case.
        Speech quiet = read("[ { \"when\": { \"timesDeclined\": { \"min\": 3 } }, \"lines\": [] }, { \"lines\": [ \"hi\" ] } ]",
                true, errors);
        assertEquals(List.of(), errors);
        assertEquals("", said(quiet, player(3, Map.of())));
        assertEquals(quiet, read(Speech.write(quiet).toString(), true, errors));
    }

    @Test
    void badGroupsAreRejected() {
        for (String bad : new String[] {
                "[ \"hi\", { \"lines\": [ \"hi\" ] } ]",                                        // lines and groups mixed
                "[ { \"lines\": [ \"a\" ] }, { \"when\": { \"timesDeclined\": 1 }, \"lines\": [ \"b\" ] } ]", // never said
                "[ { \"when\": {}, \"lines\": [ \"hi\" ] } ]",                                   // empty when
                "[ { \"when\": { \"timesDeclined\": 1 }, \"lines\": [ \"hi\" ], \"if\": 1 } ]",   // unknown key
                "[ { \"when\": { \"hour\": 1 }, \"lines\": [ \"hi\" ] } ]",                       // unknown condition
                "[ { \"when\": { \"timesDeclined\": -1 }, \"lines\": [ \"hi\" ] } ]",             // not a count
                "[ { \"when\": { \"timesDeclined\": 1.5 }, \"lines\": [ \"hi\" ] } ]",            // not a count
                "[ { \"when\": { \"timesDeclined\": \"5..\" }, \"lines\": [ \"hi\" ] } ]",        // not our range
                "[ { \"when\": { \"timesDeclined\": { \"min\": 3, \"max\": 1 } }, \"lines\": [ \"hi\" ] } ]", // backwards
                "[ { \"when\": { \"timesDeclined\": { \"atLeast\": 3 } }, \"lines\": [ \"hi\" ] } ]",         // unknown bound
                "[ { \"when\": { \"questState\": {} }, \"lines\": [ \"hi\" ] } ]",                // no quest
                "[ { \"when\": { \"questState\": { \"wolf\": \"done\" } }, \"lines\": [ \"hi\" ] } ]",        // not a quest id
                "[ { \"when\": { \"questState\": { \"quest_a\": \"finished\" } }, \"lines\": [ \"hi\" ] } ]", // not a state
                "[ { \"when\": { \"stage\": {} }, \"lines\": [ \"hi\" ] } ]",                     // no quest
                "[ { \"when\": { \"stage\": \"stage_a\" }, \"lines\": [ \"hi\" ] } ]",                  // no quest
                "[ { \"when\": { \"stage\": { \"wolf\": \"stage_a\" } }, \"lines\": [ \"hi\" ] } ]",      // not a quest id
                "[ { \"when\": { \"stage\": { \"quest_a\": \"done\" } }, \"lines\": [ \"hi\" ] } ]",      // not a stage id
                "[ { \"when\": { \"stage\": { \"quest_a\": 2 } }, \"lines\": [ \"hi\" ] } ]",             // not a stage id
                "[ { \"when\": { \"questState\": { \"quest_a\": \"done\" } }, \"lines\": [ \" \" ] } ]"}) {    // empty line
            List<String> errors = new ArrayList<>();
            read(bad, true, errors);
            assertFalse(errors.isEmpty(), bad);
        }
    }
}
