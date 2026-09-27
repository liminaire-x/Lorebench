/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Folders;
import kr.guinnessgroup.lorebench.Speech;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest document (format 1). Breaking these loses quests. */
class QuestFormatTest {

    static final String WHEAT = """
            { "format": 1, "quests": [ {
              "id": "quest_k3f9x2ma", "title": "밀 배달", "icon": "minecraft:wheat",
              "text": "촌장에게 밀 10개를 가져다주자.\\n빨리!",
              "stages": [ { "id": "stage_a1b2c3d4", "text": "촌장에게 밀 가져가기",
                            "goals": [ { "item": "minecraft:wheat", "count": 10 } ] } ],
              "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }
            """;

    /** A quest's one stage (id {@code id}, text "할 일"), with {@code more} (goals, wait, lines ...) in it. */
    static String stage(String id, String more) {
        return "\"stages\": [ { \"id\": \"" + id + "\", \"text\": \"할 일\"" + (more.isEmpty() ? "" : ", " + more) + " } ]";
    }

    /** A document of one quest {@code quest_a} with {@code parts} and one stage holding {@code stagePart}. */
    static QuestDoc one(String parts, String stagePart) {
        return QuestFormat.read("{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\","
                + (parts.isEmpty() ? "" : parts + ",") + stage("stage_a", stagePart) + ",\"rewards\":[]}]}");
    }

    static QuestDoc.Stage onlyStage(QuestDoc doc) {
        return doc.find("quest_a").stages().getFirst();
    }

    @Test
    void readsTheDocumentedExample() {
        QuestDoc doc = QuestFormat.read(WHEAT);
        QuestDoc.Quest q = doc.find("quest_k3f9x2ma");
        assertEquals(new QuestDoc.Quest("quest_k3f9x2ma", "밀 배달", "minecraft:wheat", "촌장에게 밀 10개를 가져다주자.\n빨리!",
                List.of(new QuestDoc.Stack("minecraft:emerald", 5)), List.of(), "", QuestDoc.Flow.NONE,
                List.of(new QuestDoc.Stage("stage_a1b2c3d4", "촌장에게 밀 가져가기", "",
                        List.of(QuestDoc.Goal.item("minecraft:wheat", 10)), 0, QuestDoc.StageLines.NONE))), q);
        assertNull(doc.find("quest_other"));
        assertEquals(List.of(), doc.folders());
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"folders\"") && !written.contains("\"folder\""), written);
    }

    @Test
    void stagesKeepTheAuthorsOrderAndTheirIds() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_necklace", "title": "목걸이 찾기", "giver": "npc_guard",
                  "stages": [
                    { "id": "stage_show", "text": "경비대장에게 조각 보여주기",
                      "goals": [ { "collect": "minecraft:amethyst_shard", "count": 3, "from": "kill:minecraft:wolf" } ] },
                    { "id": "stage_mend", "text": "대장장이에게 조각 맡기기", "to": "npc_smith", "wait": { "days": 1 },
                      "goals": [ { "kill": "minecraft:wolf", "count": 1 } ] },
                    { "id": "stage_give", "text": "딸에게 목걸이 전하기", "to": "npc_daughter" } ],
                  "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }
                """);
        QuestDoc.Quest q = doc.find("quest_necklace");
        assertEquals(List.of("stage_show", "stage_mend", "stage_give"), q.stages().stream().map(QuestDoc.Stage::id).toList());
        QuestDoc.Stage show = q.stage("stage_show");
        QuestDoc.Stage mend = q.stage("stage_mend");
        QuestDoc.Stage give = q.stage("stage_give");
        assertNull(q.stage("stage_gone"));
        assertEquals(mend, q.after(show));
        assertEquals(give, q.after(mend));
        assertNull(q.after(give));
        // No 'to': the giver.
        assertEquals("npc_guard", q.npcOf(show));
        assertEquals("npc_smith", q.npcOf(mend));
        assertEquals(1, mend.waitDays());
        assertEquals(List.of(), give.goals()); // just talk to her
        String written = QuestFormat.write(doc);
        assertEquals(2, written.split("\"goals\"", -1).length - 1, written); // a stage without goals writes none
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void aClientSeesTheStagesUpToItsOwnAndTheRewardsOnlyWhenAllowed() {
        QuestDoc.Quest q = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "stages": [
                    { "id": "stage_one", "text": "하나" }, { "id": "stage_two", "text": "둘" }, { "id": "stage_three", "text": "셋" } ],
                  "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }
                """).find("quest_a");
        QuestDoc.Quest second = q.upTo(1, false);
        assertEquals(List.of("stage_one", "stage_two"), second.stages().stream().map(QuestDoc.Stage::id).toList());
        assertEquals("stage_two", second.current().id());
        assertEquals(List.of(), second.rewards());
        assertEquals(q, q.upTo(2, true));
        // A removed stage: none of them.
        assertNull(q.upTo(-1, true).current());
    }

    @Test
    void aQuestHasStagesEachWithAnIdUniqueInTheDocumentAndText() {
        for (String quests : new String[] {
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"rewards\": [] }",                                     // no stages
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [], \"rewards\": [] }",                     // none
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": {}, \"rewards\": [] }",                     // not a list
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ { \"text\": \"할 일\" } ], \"rewards\": [] }", // no id
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ { \"id\": \"one\", \"text\": \"할 일\" } ], \"rewards\": [] }",
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ { \"id\": \"stage_a\" } ], \"rewards\": [] }", // no text
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ { \"id\": \"stage_a\", \"text\": \" \" } ], \"rewards\": [] }",
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ { \"id\": \"stage_a\", \"text\": \"1\" },"
                        + " { \"id\": \"stage_a\", \"text\": \"2\" } ], \"rewards\": [] }",                       // twice
                "{ \"id\": \"quest_a\", \"title\": \"A\", " + stage("stage_a", "") + ", \"rewards\": [] },"
                        + "{ \"id\": \"quest_b\", \"title\": \"B\", " + stage("stage_a", "") + ", \"rewards\": [] }", // in two quests
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"stages\": [ \"stage_a\" ], \"rewards\": [] }"}) {     // not an object
            assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":[" + quests + "]}"), quests);
        }
    }

    @Test
    void whatMovedIntoStagesIsToldWhereItGoesNow() {
        DocumentException e = assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "receiver": "npc_smith",
                  "goals": [ { "item": "minecraft:wheat", "count": 1 } ], "wait": { "days": 1 },
                  "lines": { "active": [ "아직인가?" ] }, "rewards": [] } ] }
                """));
        String all = String.join("\n", e.errors());
        for (String moved : List.of("'goals' belongs in a stage", "'wait' belongs in a stage", "'receiver' belongs in a stage",
                "lines 'active' belong in a stage")) {
            assertTrue(all.contains(moved), all);
        }
        e = assertThrows(DocumentException.class, () -> one("", "\"lines\": { \"offer\": [ \"하나?\" ] }"));
        assertTrue(e.errors().get(0).contains("lines 'offer' belong in the quest"), e.errors().toString());
        assertTrue(e.errors().get(0).startsWith("quest 'A' (quest_a) stage 1 '할 일'"), e.errors().toString());
    }

    @Test
    void foldersNestAndHoldQuests() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1,
                  "folders": [ { "id": "folder_town", "name": "마을" },
                               { "id": "folder_chief", "name": " 촌장 ", "parent": "folder_town" },
                               { "id": "folder_empty", "name": "빈 폴더" } ],
                  "quests": [ { "id": "quest_a", "title": "A", "folder": "folder_chief",
                                "stages": [ { "id": "stage_a", "text": "가" } ], "rewards": [] },
                              { "id": "quest_b", "title": "B", "stages": [ { "id": "stage_b", "text": "나" } ], "rewards": [] } ] }
                """);
        assertEquals(List.of(new Folders.Folder("folder_town", "마을", ""),
                new Folders.Folder("folder_chief", "촌장", "folder_town"),
                new Folders.Folder("folder_empty", "빈 폴더", "")), doc.folders());
        assertEquals("folder_chief", doc.find("quest_a").folder());
        assertEquals("", doc.find("quest_b").folder());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
    }

    @Test
    void flowAndLinesRoundTrip() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_sword", "title": "칼 만들기", "giver": "npc_smith",
                    "stages": [ { "id": "stage_sword", "text": "철 가져가기" } ], "rewards": [] },
                  { "id": "quest_wolf", "title": "늑대 사냥", "giver": "npc_guard", "requires": [ "quest_sword" ],
                    "lines": { "offer": [ "늑대 3마리만 잡아주게.", "요즘 가축이 자꾸 사라지거든." ] },
                    "stages": [ { "id": "stage_wolf", "text": "대장장이에게 알리기", "to": "npc_smith",
                                  "lines": { "complete": [ "대단하군!" ] } } ],
                    "rewards": [] } ] }
                """);
        QuestDoc.Quest wolf = doc.find("quest_wolf");
        assertEquals(new QuestDoc.Flow("npc_guard", List.of("quest_sword"),
                new QuestDoc.Lines(Speech.text("늑대 3마리만 잡아주게.", "요즘 가축이 자꾸 사라지거든."), Speech.NONE, Speech.NONE)),
                wolf.flow());
        assertEquals(new QuestDoc.StageLines(Speech.NONE, Speech.text("대단하군!"), Speech.NONE, Speech.NONE, Speech.NONE),
                wolf.stages().getFirst().lines());
        assertEquals("npc_smith", wolf.npcOf(wolf.stages().getFirst()));
        QuestDoc.Quest sword = doc.find("quest_sword");
        assertEquals("npc_smith", sword.npcOf(sword.stages().getFirst()));
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"active\""), written);
        assertEquals(doc, QuestFormat.read(written));
        // A quest without them is written as before.
        assertEquals(QuestDoc.Flow.NONE, QuestFormat.read(WHEAT).find("quest_k3f9x2ma").flow());
        String plain = QuestFormat.write(QuestFormat.read(WHEAT));
        for (String key : new String[] {"giver", "receiver", "to", "requires", "lines", "supplies", "wait"}) {
            assertTrue(!plain.contains("\"" + key + "\""), plain);
        }
    }

    @Test
    void suppliesAndLinesForRightAfterAcceptingRoundTrip() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_farm", "title": "밭일 배우기", "giver": "npc_farmer",
                  "lines": { "offer": [ "밭일을 배워 보겠나?" ],
                             "accepted": [ { "text": "자, 이 씨앗으로 시작하게.", "animation": "animation.chief.wave" } ] },
                  "supplies": [ { "item": "minecraft:wheat_seeds", "count": 5 } ],
                  "stages": [ { "id": "stage_farm", "text": "밀 수확하기",
                                "goals": [ { "harvest": "minecraft:wheat", "count": 10 } ] } ], "rewards": [] } ] }
                """);
        QuestDoc.Quest q = doc.find("quest_farm");
        assertEquals(List.of(new QuestDoc.Stack("minecraft:wheat_seeds", 5)), q.supplies());
        assertEquals(Speech.of(List.of(new DialogueLines.Line("자, 이 씨앗으로 시작하게.", "animation.chief.wave"))),
                q.flow().lines().accepted());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
        assertThrows(DocumentException.class, () -> one("\"supplies\":[{\"item\":\"seeds\",\"count\":1}]", ""));
    }

    /** A quest with its own stage, for documents of several quests. */
    static String quest(String id, String parts) {
        return "{ \"id\": \"" + id + "\", \"title\": \"" + id + "\", " + (parts.isEmpty() ? "" : parts + ", ")
                + stage("stage_" + id.substring("quest_".length()), "") + ", \"rewards\": [] }";
    }

    @Test
    void requiredQuestsMustExistAndNeverLeadBack() {
        for (String quests : new String[] {
                quest("quest_a", "\"requires\": [\"quest_x\"]"),                                         // unknown
                quest("quest_a", "\"requires\": [\"quest_a\"]"),                                         // itself
                quest("quest_a", "\"requires\": [\"quest_b\"]") + "," + quest("quest_b", "\"requires\": [\"quest_a\"]"), // loop
                quest("quest_a", "") + "," + quest("quest_b", "\"requires\": [\"quest_a\", \"quest_a\"]"),  // twice
                quest("quest_a", "\"requires\": \"quest_b\"")}) {                                        // not a list
            assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":[" + quests + "]}"), quests);
        }
        // A chain is fine: C needs B, B needs A.
        QuestFormat.read("{\"format\":1,\"quests\":[" + quest("quest_c", "\"requires\": [ \"quest_b\" ]") + ","
                + quest("quest_b", "\"requires\": [ \"quest_a\" ]") + "," + quest("quest_a", "") + "]}");
    }

    @Test
    void aWaitIsGameDaysBetweenHandingAStageInAndGoingOn() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_order", "title": "칼 주문", "giver": "npc_smith",
                  "stages": [ { "id": "stage_order", "text": "철 맡기기",
                                "goals": [ { "item": "minecraft:iron_ingot", "count": 5 } ], "wait": { "days": 1 } } ],
                  "rewards": [ { "item": "minecraft:iron_sword", "count": 1 } ] } ] }
                """);
        assertEquals(1, doc.find("quest_order").stages().getFirst().waitDays());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"wait\": {") && written.contains("\"days\": 1"), written);
        assertEquals(doc, QuestFormat.read(written));
        // No wait: going on right away, and nothing is written.
        assertEquals(0, QuestFormat.read(WHEAT).find("quest_k3f9x2ma").stages().getFirst().waitDays());
        assertTrue(!QuestFormat.write(QuestFormat.read(WHEAT)).contains("\"wait\""));
        for (String wait : List.of("1", "{}", "{ \"days\": 0 }", "{ \"days\": 1.5 }", "{ \"days\": \"1\" }",
                "{ \"days\": 1, \"hours\": 2 }", "{ \"ticks\": 24000 }")) {
            assertThrows(DocumentException.class, () -> one("", "\"wait\":" + wait), wait);
        }
    }

    @Test
    void stageLinesAroundTheWaitRoundTrip() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_order", "title": "칼 주문", "giver": "npc_smith",
                  "stages": [ { "id": "stage_order", "text": "철 맡기기",
                    "lines": { "complete": [ "철은 다 모았군. 어디 보세." ],
                               "handed": [ "칼을 벼리는 데 하루는 걸리네.", "내일 오게." ],
                               "waiting": [ { "when": { "questState": { "quest_order": "waiting" } }, "lines": [ "아직 망치질 중일세." ] },
                                            { "lines": [ "음?" ] } ],
                               "ready": [ { "text": "다 됐네!", "animation": "animation.chief.happy" } ] },
                    "goals": [ { "item": "minecraft:iron_ingot", "count": 5 } ], "wait": { "days": 1 } } ],
                  "rewards": [] } ] }
                """);
        QuestDoc.StageLines lines = doc.find("quest_order").stages().getFirst().lines();
        assertEquals(Speech.text("칼을 벼리는 데 하루는 걸리네.", "내일 오게."), lines.handed());
        assertEquals(2, lines.waiting().groups().size());
        assertEquals(Speech.of(List.of(new DialogueLines.Line("다 됐네!", "animation.chief.happy"))), lines.ready());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"handed\"") && written.contains("\"waiting\"") && written.contains("\"ready\""), written);
        assertEquals(doc, QuestFormat.read(written));
        // Conditions in them name quests that exist, as in the quest's lines.
        DocumentException e = assertThrows(DocumentException.class, () -> one("",
                "\"lines\": { \"waiting\": [ { \"when\": { \"questState\": { \"quest_gone\": \"done\" } }, \"lines\": [ \"음?\" ] } ] }"));
        assertEquals(List.of("quest 'A' (quest_a) stage 1 '할 일' waiting: quest 'quest_gone' does not exist"), e.errors());
    }

    @Test
    void linesForRightAfterDecliningRoundTrip() {
        QuestDoc doc = one("\"giver\": \"npc_guard\", \"lines\": { \"offer\": [ \"조각이라도 찾아 주겠나?\" ],"
                + " \"declined\": [ \"그래… 무리한 부탁이지.\" ] }", "");
        assertEquals(Speech.text("그래… 무리한 부탁이지."), doc.find("quest_a").flow().lines().declined());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"declined\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void linesPickedByConditionRoundTripAndNameQuestsThatExist() {
        String necklace = """
                { "id": "quest_necklace", "title": "목걸이 찾기", "giver": "npc_guard",
                  "lines": { "offer": [
                    { "when": { "timesDeclined": { "min": 5 } }, "lines": [ "…자네, 일부러 그러는 거지?" ] },
                    { "when": { "timesDeclined": { "min": 1 } }, "lines": [ "마음이 바뀌었나?" ] },
                    { "lines": [ "늑대가 딸의 목걸이를 물고 달아났네." ] } ] },
                  "stages": [ { "id": "stage_necklace", "text": "조각 찾기" } ], "rewards": [] }""";
        QuestDoc doc = QuestFormat.read("{ \"format\": 1, \"quests\": [" + necklace + "] }");
        assertEquals(3, doc.find("quest_necklace").flow().lines().offer().groups().size());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
        String sword = """
                { "id": "quest_sword", "title": "칼 만들기", "lines": { "offer": [
                  { "when": { "questState": { "quest_necklace": "done" } }, "lines": [ "자네라면 믿고 맡기지." ] },
                  { "lines": [ "철 5개만 가져오게." ] } ] }, "stages": [ { "id": "stage_sword", "text": "철" } ], "rewards": [] }""";
        QuestFormat.read("{ \"format\": 1, \"quests\": [" + necklace + "," + sword + "] }");
        DocumentException e = assertThrows(DocumentException.class,
                () -> QuestFormat.read("{ \"format\": 1, \"quests\": [" + sword + "] }"));
        assertEquals(List.of("quest '칼 만들기' (quest_sword) offer: quest 'quest_necklace' does not exist"), e.errors());
    }

    @Test
    void messagesNameAQuestByItsTitle() {
        DocumentException e = assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":["
                + quest("quest_a", "\"requires\": [ \"quest_b\" ]").replace("\"title\": \"quest_a\"", "\"title\": \"늑대 사냥\"") + ","
                + quest("quest_b", "\"requires\": [ \"quest_a\" ]").replace("\"title\": \"quest_b\"", "\"title\": \" \"") + "]}"));
        assertTrue(e.errors().contains("quest 'quest_b': missing 'title'"), e.errors().toString());
        e = assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":["
                + quest("quest_a", "\"requires\": [ \"quest_a\" ]").replace("\"title\": \"quest_a\"", "\"title\": \"늑대 사냥\"")
                + "]}"));
        assertEquals(List.of("quest '늑대 사냥' (quest_a) requires itself"), e.errors());
    }

    @Test
    void giversAndStageNpcsAreNpcIdsAndLinesAreText() {
        for (String part : new String[] {
                "\"giver\": \"chief\"",                                   // not an NPC id
                "\"lines\": [ \"hi\" ]",                                  // not an object
                "\"lines\": { \"offfer\": [ \"hi\" ] }",                // unknown key
                "\"lines\": { \"offer\": \"hi\" }",                     // not a list
                "\"lines\": { \"offer\": [ 1 ] }",                        // not text
                "\"lines\": { \"offer\": [ \" \" ] }"}) {               // empty line
            assertThrows(DocumentException.class, () -> one(part, ""), part);
        }
        for (String part : new String[] {
                "\"to\": \"quest_a\"",                                    // not an NPC id
                "\"lines\": { \"complet\": [ \"hi\" ] }",               // unknown key
                "\"lines\": { \"complete\": [ \" \" ] }",               // empty line
                "\"goals\": {}"}) {                                        // not a list
            assertThrows(DocumentException.class, () -> one("", part), part);
        }
    }

    @Test
    void publishFindsGiversAndStageNpcsThatAreNotNpcs() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "giver": "npc_chief",
                  "stages": [ { "id": "stage_a", "text": "가", "to": "npc_gone" }, { "id": "stage_b", "text": "나", "to": "npc_gone" },
                              { "id": "stage_c", "text": "다", "to": "npc_chief" } ], "rewards": [] } ] }
                """);
        assertEquals(List.of(), doc.npcErrors(Set.of("npc_chief", "npc_gone")));
        assertEquals(1, doc.npcErrors(Set.of("npc_chief")).size()); // named once, though two stages go there
        assertEquals(2, doc.npcErrors(Set.of()).size());
    }

    @Test
    void aQuestMustSitInAFolderThatExists() {
        assertThrows(DocumentException.class, () -> one("\"folder\": \"folder_x\"", ""));
    }

    @Test
    void writeThenReadGivesTheSameDocument() {
        QuestDoc doc = QuestFormat.read(WHEAT);
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
    }

    @Test
    void iconTextAndEmptyListsAreAllowed() {
        QuestDoc doc = one("", "");
        QuestDoc.Quest q = doc.find("quest_a");
        assertEquals("", q.icon());
        assertEquals("", q.text());
        assertEquals(List.of(), onlyStage(doc).goals());
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"icon\"") && !written.contains("\"goals\""), written);
        assertEquals(doc, QuestFormat.read(written));
        assertEquals(doc, one("", "\"goals\": []"));
    }

    @Test
    void rewardsMayCarryComponentsAsGiveWritesThem() {
        String sword = "minecraft:iron_sword[custom_name='\\\"대장장이의 칼\\\"',enchantments={levels:{'minecraft:sharpness':2}}]";
        QuestDoc doc = QuestFormat.read("{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\"," + stage("stage_a", "")
                + ",\"rewards\":[{\"item\":\"" + sword + "\",\"count\":1}]}]}");
        assertEquals(sword.replace("\\\"", "\""), doc.find("quest_a").rewards().get(0).item());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
    }

    @Test
    void handInGoalsAreConditionsAsClearReadsThemIconsAreIds() {
        QuestDoc doc = one("", """
                "goals": [ { "item": "minecraft:iron_sword[custom_data={lorebench:'smith_sword'}]", "count": 1 },
                           { "item": "#minecraft:logs", "count": 8 } ]""");
        assertEquals(List.of(QuestDoc.Goal.item("minecraft:iron_sword[custom_data={lorebench:'smith_sword'}]", 1),
                QuestDoc.Goal.item("#minecraft:logs", 8)), onlyStage(doc).goals());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
        assertThrows(DocumentException.class, () -> one("\"icon\": \"minecraft:wheat[x=1]\"", ""));
    }

    @Test
    void killGoalsMixWithItemGoalsInTheAuthorsOrder() {
        QuestDoc doc = one("", "\"goals\": [ { \"kill\": \"minecraft:wolf\", \"count\": 3 }, { \"item\": \"minecraft:leather\", \"count\": 5 } ]");
        assertEquals(List.of(QuestDoc.Goal.kill("minecraft:wolf", 3), QuestDoc.Goal.item("minecraft:leather", 5)),
                onlyStage(doc).goals());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"kill\": \"minecraft:wolf\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void harvestGoalsNameACropBlockAndMayShareATargetWithOtherKinds() {
        QuestDoc doc = one("", """
                "goals": [ { "harvest": "minecraft:potatoes", "count": 10 }, { "item": "minecraft:potato", "count": 20 },
                           { "harvest": "minecraft:wheat", "count": 5 }, { "kill": "minecraft:cow", "count": 1 } ]""");
        assertEquals(List.of(QuestDoc.Goal.harvest("minecraft:potatoes", 10), QuestDoc.Goal.item("minecraft:potato", 20),
                        QuestDoc.Goal.harvest("minecraft:wheat", 5), QuestDoc.Goal.kill("minecraft:cow", 1)),
                onlyStage(doc).goals());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"harvest\": \"minecraft:potatoes\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void breedGoalsNameAnAnimalAndCountApartFromKillingIt() {
        QuestDoc doc = one("", "\"goals\": [ { \"breed\": \"minecraft:cow\", \"count\": 2 }, { \"kill\": \"minecraft:cow\", \"count\": 1 } ]");
        List<QuestDoc.Goal> goals = onlyStage(doc).goals();
        assertEquals(List.of(QuestDoc.Goal.breed("minecraft:cow", 2), QuestDoc.Goal.kill("minecraft:cow", 1)), goals);
        assertEquals("breed:minecraft:cow", goals.get(0).progressKey());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"breed\": \"minecraft:cow\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void collectGoalsNameAnItemWhereItDropsAndHowOften() {
        QuestDoc doc = one("", """
                "goals": [ { "collect": "minecraft:amethyst_shard[custom_name='\\"목걸이 조각\\"']", "count": 3,
                             "from": "kill:minecraft:wolf", "chance": 0.5 },
                           { "collect": "minecraft:paper", "count": 1, "from": "kill:minecraft:zombie" },
                           { "kill": "minecraft:wolf", "count": 1 } ]""");
        List<QuestDoc.Goal> goals = onlyStage(doc).goals();
        assertEquals(QuestDoc.Goal.collect("minecraft:amethyst_shard[custom_name='\"목걸이 조각\"']", 3,
                "kill:minecraft:wolf", 0.5), goals.get(0));
        assertEquals("minecraft:amethyst_shard", goals.get(0).itemId());
        assertEquals(1.0, goals.get(1).chance()); // no chance = every time
        assertFalse(goals.get(0).kind().counted()); // counted from the inventory, not the progress record
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"from\": \"kill:minecraft:wolf\""), written);
        assertTrue(written.contains("\"chance\": 0.5"), written);
        assertEquals(1, written.split("\"chance\"", -1).length - 1, written); // 1 is left out
        assertEquals(doc, QuestFormat.read(written));
        for (String bad : new String[] {
                "{ \"collect\": \"minecraft:paper\", \"count\": 1 }",                                        // no from
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"minecraft:wolf\" }",           // no kind
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"harvest:minecraft:wheat\" }",  // kills only
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\", \"chance\": 0 }",
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\", \"chance\": 1.5 }",
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\", \"chance\": \"half\" }",
                "{ \"collect\": \"paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\" }",                // not an item
                "{ \"kill\": \"minecraft:wolf\", \"count\": 1, \"from\": \"kill:minecraft:wolf\" }",         // not a collect goal
                "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\" },"
                        + "{ \"collect\": \"minecraft:paper[custom_name='\\\"편지\\\"']\", \"count\": 1, \"from\": \"kill:minecraft:zombie\" }"}) {
            assertThrows(DocumentException.class, () -> one("", "\"goals\":[" + bad + "]"), bad);
        }
    }

    @Test
    void stagesGoOneAtATimeSoEachMayAskForTheSameThings() {
        // The same pieces shown in one stage and handed in the next (0015); the same kills, counted afresh.
        String collect = "{ \"collect\": \"minecraft:paper\", \"count\": 1, \"from\": \"kill:minecraft:wolf\" }";
        String kill = "{ \"kill\": \"minecraft:wolf\", \"count\": 2 }";
        QuestDoc doc = QuestFormat.read("{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\",\"stages\":["
                + "{\"id\":\"stage_one\",\"text\":\"하나\",\"goals\":[" + collect + "," + kill + "]},"
                + "{\"id\":\"stage_two\",\"text\":\"둘\",\"goals\":[" + collect + "," + kill + "]}],\"rewards\":[]}]}");
        assertEquals(doc.find("quest_a").stages().get(0).goals(), doc.find("quest_a").stages().get(1).goals());
    }

    @Test
    void aGoalNamesExactlyOneKnownKindAndEachTargetOncePerKind() {
        for (String goals : new String[] {
                "{ \"count\": 1 }",                                                             // neither
                "{ \"item\": \"minecraft:wheat\", \"kill\": \"minecraft:wolf\", \"count\": 1 }", // both
                "{ \"harvest\": \"minecraft:wheat\", \"kill\": \"minecraft:wolf\", \"count\": 1 }",
                "{ \"kill\": \"Wolf\", \"count\": 1 }",                                          // not an id
                "{ \"harvest\": \"wheat crops\", \"count\": 1 }",
                "{ \"harvest\": \"minecraft:wheat\", \"count\": 0 }",
                "{ \"kill\": \"minecraft:wolf\", \"count\": 1 }, { \"kill\": \"minecraft:wolf\", \"count\": 2 }",
                "{ \"harvest\": \"minecraft:wheat\", \"count\": 1 }, { \"harvest\": \"minecraft:wheat\", \"count\": 2 }",
                "{ \"breed\": \"minecraft:cow\", \"kill\": \"minecraft:cow\", \"count\": 1 }",
                "{ \"breed\": \"Cow\", \"count\": 1 }",
                "{ \"breed\": \"minecraft:cow\", \"count\": 1 }, { \"breed\": \"minecraft:cow\", \"count\": 2 }"}) {
            assertThrows(DocumentException.class, () -> one("", "\"goals\":[" + goals + "]"), goals);
        }
    }

    @Test
    void rejectsNewerFormat() {
        DocumentException e = assertThrows(DocumentException.class,
                () -> QuestFormat.read("{\"format\":2,\"quests\":[]}"));
        assertTrue(e.errors().get(0).contains("newer"));
    }

    @Test
    void idsMustCarryTheQuestKind() {
        for (String id : new String[] {"wheat", "npc_wheat", "quest_", "quest_Wheat"}) {
            assertThrows(DocumentException.class, () -> QuestFormat.read(
                    "{\"format\":1,\"quests\":[{\"id\":\"" + id + "\",\"title\":\"a\"," + stage("stage_a", "") + ",\"rewards\":[]}]}"),
                    id);
        }
    }

    @Test
    void reportsEveryProblem() {
        DocumentException e = assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":["
                + "{ \"id\": \"quest_a\", " + stage("stage_a", "") + ", \"rewards\": [] },"
                + quest("quest_b", "\"icon\": \"wheat\"") + ","
                + "{ \"id\": \"quest_c\", \"title\": \"C\", " + stage("stage_c", "\"goals\": [ { \"item\": \"minecraft:wheat\", \"count\": 0 } ]") + ", \"rewards\": [] },"
                + "{ \"id\": \"quest_d\", \"title\": \"D\", " + stage("stage_d", "\"goals\": [ { \"item\": \"minecraft:wheat\", \"count\": 2.5 } ]") + ", \"rewards\": [] },"
                + "{ \"id\": \"quest_e\", \"title\": \"E\", " + stage("stage_e", "") + ", \"rewards\": [ { \"item\": \"Emerald\", \"count\": 1 } ] },"
                + "{ \"id\": \"quest_f\", \"title\": \"F\", " + stage("stage_f", "") + " },"
                + quest("quest_g", "") + ","
                + quest("quest_g", "").replace("stage_g", "stage_h") + "]}"));
        // missing title, bad icon, count 0, fractional count, bad item, missing rewards, duplicate id
        assertEquals(7, e.errors().size(), e.errors().toString());
    }
}
