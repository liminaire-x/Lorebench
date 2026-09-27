/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Folders;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest document (format 1). Breaking these loses quests. */
class QuestFormatTest {

    static final String WHEAT = """
            { "format": 1, "quests": [ {
              "id": "quest_k3f9x2ma", "title": "밀 배달", "icon": "minecraft:wheat",
              "text": "촌장에게 밀 10개를 가져다주자.\\n빨리!",
              "goals":   [ { "item": "minecraft:wheat",   "count": 10 } ],
              "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }
            """;

    @Test
    void readsTheDocumentedExample() {
        QuestDoc doc = QuestFormat.read(WHEAT);
        QuestDoc.Quest q = doc.find("quest_k3f9x2ma");
        assertEquals(new QuestDoc.Quest("quest_k3f9x2ma", "밀 배달", "minecraft:wheat", "촌장에게 밀 10개를 가져다주자.\n빨리!",
                List.of(QuestDoc.Goal.item("minecraft:wheat", 10)),
                List.of(new QuestDoc.Stack("minecraft:emerald", 5)), List.of(), "", QuestDoc.Flow.NONE), q);
        assertNull(doc.find("quest_other"));
        assertEquals(List.of(), doc.folders());
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"folders\"") && !written.contains("\"folder\""), written);
    }

    @Test
    void foldersNestAndHoldQuests() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1,
                  "folders": [ { "id": "folder_town", "name": "마을" },
                               { "id": "folder_chief", "name": " 촌장 ", "parent": "folder_town" },
                               { "id": "folder_empty", "name": "빈 폴더" } ],
                  "quests": [ { "id": "quest_a", "title": "A", "folder": "folder_chief", "goals": [], "rewards": [] },
                              { "id": "quest_b", "title": "B", "goals": [], "rewards": [] } ] }
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
                  { "id": "quest_sword", "title": "칼 만들기", "giver": "npc_smith", "goals": [], "rewards": [] },
                  { "id": "quest_wolf", "title": "늑대 사냥", "giver": "npc_guard", "receiver": "npc_smith",
                    "requires": [ "quest_sword" ],
                    "lines": { "offer": [ "늑대 3마리만 잡아주게.", "요즘 가축이 자꾸 사라지거든." ],
                               "complete": [ "대단하군!" ] },
                    "goals": [], "rewards": [] } ] }
                """);
        QuestDoc.Flow wolf = doc.find("quest_wolf").flow();
        assertEquals(new QuestDoc.Flow("npc_guard", "npc_smith", List.of("quest_sword"),
                new QuestDoc.Lines(DialogueLines.text("늑대 3마리만 잡아주게.", "요즘 가축이 자꾸 사라지거든."), List.of(),
                        List.of(), List.of(), DialogueLines.text("대단하군!"))),
                wolf);
        assertEquals("npc_smith", wolf.handInTo());
        assertEquals("npc_smith", doc.find("quest_sword").flow().handInTo());
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"active\""), written);
        assertEquals(doc, QuestFormat.read(written));
        // A quest without them is written as before.
        assertEquals(QuestDoc.Flow.NONE, QuestFormat.read(WHEAT).find("quest_k3f9x2ma").flow());
        String plain = QuestFormat.write(QuestFormat.read(WHEAT));
        for (String key : new String[] {"giver", "receiver", "requires", "lines", "supplies"}) {
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
                  "goals": [ { "harvest": "minecraft:wheat", "count": 10 } ], "rewards": [] } ] }
                """);
        QuestDoc.Quest q = doc.find("quest_farm");
        assertEquals(List.of(new QuestDoc.Stack("minecraft:wheat_seeds", 5)), q.supplies());
        assertEquals(List.of(new DialogueLines.Line("자, 이 씨앗으로 시작하게.", "animation.chief.wave")),
                q.flow().lines().accepted());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
        assertThrows(DocumentException.class, () -> QuestFormat.read(
                "{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\",\"goals\":[],\"rewards\":[],"
                        + "\"supplies\":[{\"item\":\"seeds\",\"count\":1}]}]}"));
    }

    @Test
    void requiredQuestsMustExistAndNeverLeadBack() {
        for (String quests : new String[] {
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"requires\": [\"quest_x\"], \"goals\": [], \"rewards\": [] }", // unknown
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"requires\": [\"quest_a\"], \"goals\": [], \"rewards\": [] }", // itself
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"requires\": [\"quest_b\"], \"goals\": [], \"rewards\": [] },"
                        + "{ \"id\": \"quest_b\", \"title\": \"B\", \"requires\": [\"quest_a\"], \"goals\": [], \"rewards\": [] }", // loop
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"goals\": [], \"rewards\": [] },"
                        + "{ \"id\": \"quest_b\", \"title\": \"B\", \"requires\": [\"quest_a\", \"quest_a\"], \"goals\": [], \"rewards\": [] }", // twice
                "{ \"id\": \"quest_a\", \"title\": \"A\", \"requires\": \"quest_b\", \"goals\": [], \"rewards\": [] }"}) { // not a list
            assertThrows(DocumentException.class, () -> QuestFormat.read("{\"format\":1,\"quests\":[" + quests + "]}"), quests);
        }
        // A chain is fine: C needs B, B needs A.
        QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_c", "title": "C", "requires": [ "quest_b" ], "goals": [], "rewards": [] },
                  { "id": "quest_b", "title": "B", "requires": [ "quest_a" ], "goals": [], "rewards": [] },
                  { "id": "quest_a", "title": "A", "goals": [], "rewards": [] } ] }
                """);
    }

    @Test
    void linesForRightAfterDecliningRoundTrip() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_necklace", "title": "목걸이 찾기", "giver": "npc_guard",
                  "lines": { "offer": [ "조각이라도 찾아 주겠나?" ], "declined": [ "그래… 무리한 부탁이지." ] },
                  "goals": [], "rewards": [] } ] }
                """);
        assertEquals(DialogueLines.text("그래… 무리한 부탁이지."), doc.find("quest_necklace").flow().lines().declined());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"declined\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void messagesNameAQuestByItsTitle() {
        DocumentException e = assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_a", "title": "늑대 사냥", "requires": [ "quest_b" ], "goals": [], "rewards": [] },
                  { "id": "quest_b", "title": " ", "requires": [ "quest_a" ], "goals": [], "rewards": [] } ] }
                """));
        assertTrue(e.errors().contains("quest 'quest_b': missing 'title'"), e.errors().toString());
        e = assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_a", "title": "늑대 사냥", "requires": [ "quest_a" ], "goals": [], "rewards": [] } ] }
                """));
        assertEquals(List.of("quest '늑대 사냥' (quest_a) requires itself"), e.errors());
    }

    @Test
    void giversAreNpcIdsAndLinesAreText() {
        for (String part : new String[] {
                "\"giver\": \"chief\"",                                   // not an NPC id
                "\"receiver\": \"quest_a\"",                              // not an NPC id
                "\"lines\": [ \"hi\" ]",                                  // not an object
                "\"lines\": { \"offfer\": [ \"hi\" ] }",                // unknown key
                "\"lines\": { \"offer\": \"hi\" }",                     // not a list
                "\"lines\": { \"offer\": [ 1 ] }",                        // not text
                "\"lines\": { \"offer\": [ \" \" ] }"}) {               // empty line
            assertThrows(DocumentException.class, () -> QuestFormat.read(
                    "{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\"," + part + ",\"goals\":[],\"rewards\":[]}]}"),
                    part);
        }
    }

    @Test
    void publishFindsGiversAndReceiversThatAreNotNpcs() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_a", "title": "A", "giver": "npc_chief", "receiver": "npc_gone", "goals": [], "rewards": [] } ] }
                """);
        assertEquals(List.of(), doc.npcErrors(Set.of("npc_chief", "npc_gone")));
        assertEquals(1, doc.npcErrors(Set.of("npc_chief")).size());
        assertEquals(2, doc.npcErrors(Set.of()).size());
    }

    @Test
    void aQuestMustSitInAFolderThatExists() {
        assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "folder": "folder_x", "goals": [], "rewards": [] } ] }
                """));
    }

    @Test
    void writeThenReadGivesTheSameDocument() {
        QuestDoc doc = QuestFormat.read(WHEAT);
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
    }

    @Test
    void iconTextAndEmptyListsAreAllowed() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "goals": [], "rewards": [] } ] }
                """);
        QuestDoc.Quest q = doc.find("quest_a");
        assertEquals("", q.icon());
        assertEquals("", q.text());
        String written = QuestFormat.write(doc);
        assertTrue(!written.contains("\"icon\"") && !written.contains("\"text\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void rewardsMayCarryComponentsAsGiveWritesThem() {
        String sword = "minecraft:iron_sword[custom_name='\\\"대장장이의 칼\\\"',enchantments={levels:{'minecraft:sharpness':2}}]";
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "goals": [],
                  "rewards": [ { "item": "%s", "count": 1 } ] } ] }
                """.formatted(sword));
        assertEquals(sword.replace("\\\"", "\""), doc.find("quest_a").rewards().get(0).item());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
    }

    @Test
    void handInGoalsAreConditionsAsClearReadsThemIconsAreIds() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A",
                  "goals": [ { "item": "minecraft:iron_sword[custom_data={lorebench:'smith_sword'}]", "count": 1 },
                             { "item": "#minecraft:logs", "count": 8 } ], "rewards": [] } ] }
                """);
        assertEquals(List.of(QuestDoc.Goal.item("minecraft:iron_sword[custom_data={lorebench:'smith_sword'}]", 1),
                QuestDoc.Goal.item("#minecraft:logs", 8)), doc.find("quest_a").goals());
        assertEquals(doc, QuestFormat.read(QuestFormat.write(doc)));
        assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A", "icon": "minecraft:wheat[x=1]",
                  "goals": [], "rewards": [] } ] }
                """));
    }

    @Test
    void killGoalsMixWithItemGoalsInTheAuthorsOrder() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A",
                  "goals": [ { "kill": "minecraft:wolf", "count": 3 }, { "item": "minecraft:leather", "count": 5 } ],
                  "rewards": [] } ] }
                """);
        assertEquals(List.of(QuestDoc.Goal.kill("minecraft:wolf", 3), QuestDoc.Goal.item("minecraft:leather", 5)),
                doc.find("quest_a").goals());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"kill\": \"minecraft:wolf\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void harvestGoalsNameACropBlockAndMayShareATargetWithOtherKinds() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A",
                  "goals": [ { "harvest": "minecraft:potatoes", "count": 10 }, { "item": "minecraft:potato", "count": 20 },
                             { "harvest": "minecraft:wheat", "count": 5 }, { "kill": "minecraft:cow", "count": 1 } ],
                  "rewards": [] } ] }
                """);
        assertEquals(List.of(QuestDoc.Goal.harvest("minecraft:potatoes", 10), QuestDoc.Goal.item("minecraft:potato", 20),
                        QuestDoc.Goal.harvest("minecraft:wheat", 5), QuestDoc.Goal.kill("minecraft:cow", 1)),
                doc.find("quest_a").goals());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"harvest\": \"minecraft:potatoes\""), written);
        assertEquals(doc, QuestFormat.read(written));
    }

    @Test
    void breedGoalsNameAnAnimalAndCountApartFromKillingIt() {
        QuestDoc doc = QuestFormat.read("""
                { "format": 1, "quests": [ { "id": "quest_a", "title": "A",
                  "goals": [ { "breed": "minecraft:cow", "count": 2 }, { "kill": "minecraft:cow", "count": 1 } ],
                  "rewards": [] } ] }
                """);
        List<QuestDoc.Goal> goals = doc.find("quest_a").goals();
        assertEquals(List.of(QuestDoc.Goal.breed("minecraft:cow", 2), QuestDoc.Goal.kill("minecraft:cow", 1)), goals);
        assertEquals("breed:minecraft:cow", goals.get(0).progressKey());
        String written = QuestFormat.write(doc);
        assertTrue(written.contains("\"breed\": \"minecraft:cow\""), written);
        assertEquals(doc, QuestFormat.read(written));
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
            assertThrows(DocumentException.class, () -> QuestFormat.read(
                    "{\"format\":1,\"quests\":[{\"id\":\"quest_a\",\"title\":\"A\",\"goals\":[" + goals + "],\"rewards\":[]}]}"),
                    goals);
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
                    "{\"format\":1,\"quests\":[{\"id\":\"" + id + "\",\"title\":\"a\",\"goals\":[],\"rewards\":[]}]}"), id);
        }
    }

    @Test
    void reportsEveryProblem() {
        DocumentException e = assertThrows(DocumentException.class, () -> QuestFormat.read("""
                { "format": 1, "quests": [
                  { "id": "quest_a", "goals": [], "rewards": [] },
                  { "id": "quest_b", "title": "B", "icon": "wheat", "goals": [], "rewards": [] },
                  { "id": "quest_c", "title": "C", "goals": [ { "item": "minecraft:wheat", "count": 0 } ], "rewards": [] },
                  { "id": "quest_d", "title": "D", "goals": [ { "item": "minecraft:wheat", "count": 2.5 } ], "rewards": [] },
                  { "id": "quest_e", "title": "E", "goals": [], "rewards": [ { "item": "Emerald", "count": 1 } ] },
                  { "id": "quest_f", "title": "F", "goals": [] },
                  { "id": "quest_g", "title": "G", "goals": [], "rewards": [] },
                  { "id": "quest_g", "title": "G2", "goals": [], "rewards": [] } ] }
                """));
        // missing title, bad icon, count 0, fractional count, bad item, missing rewards, duplicate id
        assertEquals(7, e.errors().size(), e.errors().toString());
    }
}
