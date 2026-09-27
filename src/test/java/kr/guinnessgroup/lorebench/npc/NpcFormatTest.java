/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.npc;

import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Speech;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The NPC document (format 1) and the placement record. Breaking these loses NPCs. */
class NpcFormatTest {

    static final String CHIEF = """
            { "format": 1, "npcs": [ { "id": "npc_chief", "name": "촌장" } ] }
            """;

    @Test
    void readsTheDocumentedExample() {
        NpcDoc doc = NpcFormat.read(CHIEF);
        assertEquals(new NpcDoc.NpcDef("npc_chief", "촌장"), doc.find("npc_chief"));
        assertNull(doc.find("npc_smith"));
    }

    @Test
    void writeThenReadGivesTheSameDocument() {
        NpcDoc doc = NpcFormat.read(CHIEF);
        assertEquals(doc, NpcFormat.read(NpcFormat.write(doc)));
    }

    @Test
    void looksAreOptionalAndWrittenOnlyWhenSet() {
        NpcDoc doc = NpcFormat.read("""
                { "format": 1, "npcs": [
                  { "id": "npc_chief", "name": "촌장", "model": "chief", "idle": "animation.chief.wave" },
                  { "id": "npc_smith", "name": "대장장이" } ] }
                """);
        assertEquals(new NpcDoc.NpcDef("npc_chief", "촌장", "chief", "animation.chief.wave"), doc.find("npc_chief"));
        assertEquals(new NpcDoc.NpcDef("npc_smith", "대장장이", "", ""), doc.find("npc_smith"));
        String written = NpcFormat.write(doc);
        assertEquals(1, written.split("\"model\"", -1).length - 1);
        assertEquals(doc, NpcFormat.read(written));
    }

    @Test
    void aGreetingCanDependOnQuestsThatMustExist() {
        NpcDoc doc = NpcFormat.read("""
                { "format": 1, "npcs": [ { "id": "npc_guard", "name": "경비대장", "greeting": [
                  { "when": { "questState": { "quest_necklace": "done" } }, "lines": [ "목걸이 덕에 딸이 다시 웃는다네." ] },
                  { "lines": [ "오, 자네 왔군." ] } ] } ] }
                """);
        assertEquals(doc, NpcFormat.read(NpcFormat.write(doc)));
        assertEquals(List.of(), doc.questErrors(Set.of("quest_necklace")));
        assertEquals(List.of("NPC '경비대장' (npc_guard) greeting: quest 'quest_necklace' does not exist"),
                doc.questErrors(Set.of()));
        // A greeting has no quest of its own to count refusals of.
        assertThrows(DocumentException.class, () -> NpcFormat.read(
                "{\"format\":1,\"npcs\":[{\"id\":\"npc_a\",\"name\":\"A\",\"greeting\":"
                        + "[{\"when\":{\"timesDeclined\":1},\"lines\":[\"hi\"]}]}]}"));
    }

    @Test
    void greetingIsOptionalLinesOfText() {
        NpcDoc doc = NpcFormat.read("""
                { "format": 1, "npcs": [ { "id": "npc_guard", "name": "경비대장", "greeting": [ "오, 자네 왔군.", "무슨 일인가?" ] } ] }
                """);
        assertEquals(Speech.text("오, 자네 왔군.", "무슨 일인가?"), doc.find("npc_guard").greeting());
        assertEquals(doc, NpcFormat.read(NpcFormat.write(doc)));
        assertEquals(Speech.NONE, NpcFormat.read(CHIEF).find("npc_chief").greeting());
        assertTrue(!NpcFormat.write(NpcFormat.read(CHIEF)).contains("greeting"));
        for (String greeting : new String[] {"\"hi\"", "[ 1 ]", "[ \"\" ]"}) {
            assertThrows(DocumentException.class, () -> NpcFormat.read(
                    "{\"format\":1,\"npcs\":[{\"id\":\"npc_a\",\"name\":\"A\",\"greeting\":" + greeting + "}]}"), greeting);
        }
    }

    @Test
    void talkSetIsOptionalAndEachNameToo() {
        NpcDoc doc = NpcFormat.read("""
                { "format": 1, "npcs": [
                  { "id": "npc_smith", "name": "대장장이", "talk": { "start": "animation.smith.put_down",
                    "loop": "animation.smith.nod", "end": "animation.smith.pick_up" } },
                  { "id": "npc_guard", "name": "경비대장", "talk": { "loop": "animation.guard.nod" } } ] }
                """);
        assertEquals(new NpcDoc.Talk("animation.smith.put_down", "animation.smith.nod", "animation.smith.pick_up"),
                doc.find("npc_smith").talk());
        assertEquals(new NpcDoc.Talk("", "animation.guard.nod", ""), doc.find("npc_guard").talk());
        String written = NpcFormat.write(doc);
        assertEquals(doc, NpcFormat.read(written));
        assertEquals(1, written.split("\"start\"", -1).length - 1);
        assertEquals(NpcDoc.Talk.NONE, NpcFormat.read(CHIEF).find("npc_chief").talk());
        assertTrue(!NpcFormat.write(NpcFormat.read(CHIEF)).contains("talk"));
        for (String talk : new String[] {"\"animation.a.nod\"", "{ \"begin\": \"a\" }", "{ \"loop\": 1 }", "[ ]"}) {
            assertThrows(DocumentException.class, () -> NpcFormat.read(
                    "{\"format\":1,\"npcs\":[{\"id\":\"npc_a\",\"name\":\"A\",\"talk\":" + talk + "}]}"), talk);
        }
    }

    @Test
    void npcsSitInFolders() {
        NpcDoc doc = NpcFormat.read("""
                { "format": 1, "folders": [ { "id": "folder_town", "name": "마을" } ],
                  "npcs": [ { "id": "npc_chief", "name": "촌장", "folder": "folder_town" },
                            { "id": "npc_smith", "name": "대장장이" } ] }
                """);
        assertEquals("folder_town", doc.find("npc_chief").folder());
        assertEquals("", doc.find("npc_smith").folder());
        assertEquals(1, doc.folders().size());
        assertEquals(doc, NpcFormat.read(NpcFormat.write(doc)));
        assertTrue(!NpcFormat.write(NpcFormat.read(CHIEF)).contains("folder"));
        assertThrows(DocumentException.class, () -> NpcFormat.read("""
                { "format": 1, "npcs": [ { "id": "npc_chief", "name": "촌장", "folder": "folder_x" } ] }
                """));
    }

    @Test
    void rejectsBadModelName() {
        assertThrows(DocumentException.class, () -> NpcFormat.read(
                "{\"format\":1,\"npcs\":[{\"id\":\"npc_chief\",\"name\":\"a\",\"model\":\"Chief Model\"}]}"));
    }

    @Test
    void rejectsNewerFormat() {
        DocumentException e = assertThrows(DocumentException.class,
                () -> NpcFormat.read("{\"format\":2,\"npcs\":[]}"));
        assertTrue(e.errors().get(0).contains("newer"));
    }

    @Test
    void rejectsBadIdsMissingNamesAndDuplicates() {
        DocumentException e = assertThrows(DocumentException.class, () -> NpcFormat.read(
                "{\"format\":1,\"npcs\":["
                        + "{\"id\":\"npc_Chief\",\"name\":\"a\"},"
                        + "{\"id\":\"npc_smith\"},"
                        + "{\"id\":\"npc_chief\",\"name\":\"b\"},{\"id\":\"npc_chief\",\"name\":\"c\"}]}"));
        assertEquals(3, e.errors().size());
    }

    @Test
    void idsMustCarryTheNpcKind() {
        for (String id : new String[] {"chief", "graph_chief", "npc_", "npc_chief_2"}) {
            assertThrows(DocumentException.class, () -> NpcFormat.read(
                    "{\"format\":1,\"npcs\":[{\"id\":\"" + id + "\",\"name\":\"a\"}]}"), id);
        }
    }

    @Test
    void placementRecordRoundTrips() {
        Placement p = new Placement(UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                "npc_chief", "minecraft:overworld", 1.5, 64.0, -3.5);
        assertEquals("placement_00000000-0000-0000-0000-00000000000a", p.key());
        assertEquals(p, Placement.fromRecord(p.key(), p.toValue()));
    }

    @Test
    void placementIgnoresOtherRecordsAndBrokenValues() {
        assertNull(Placement.fromRecord("flag_greeted", "true"));
        assertNull(Placement.fromRecord("placement_not-a-uuid", "{}"));
        assertNull(Placement.fromRecord("placement_00000000-0000-0000-0000-00000000000a", "{\"npc\":\"npc_chief\"}"));
    }
}
