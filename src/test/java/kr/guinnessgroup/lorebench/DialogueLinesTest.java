/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dialogue lines, in quests and NPC greetings. Breaking these loses what NPCs say. */
class DialogueLinesTest {

    static List<DialogueLines.Line> read(String json, List<String> errors) {
        return DialogueLines.read(JsonParser.parseString(json), "lines", errors);
    }

    @Test
    void linesAreTextOrTextWithAnAnimation() {
        List<String> errors = new ArrayList<>();
        List<DialogueLines.Line> lines = read("""
                [ "고맙네!", { "text": "약속한 에메랄드일세.", "animation": " animation.chief.happy " },
                  { "text": "잘 가게." } ]
                """, errors);
        assertEquals(List.of(), errors);
        assertEquals(List.of(DialogueLines.Line.of("고맙네!"),
                new DialogueLines.Line("약속한 에메랄드일세.", "animation.chief.happy"),
                DialogueLines.Line.of("잘 가게.")), lines);
        // A line without an animation is written as plain text, so old documents look the same.
        String written = DialogueLines.write(lines).toString();
        assertEquals("[\"고맙네!\",{\"text\":\"약속한 에메랄드일세.\",\"animation\":\"animation.chief.happy\"},\"잘 가게.\"]", written);
        assertEquals(lines, read(written, errors));
    }

    @Test
    void anAnimationCanLoopWhileThePageShows() {
        List<String> errors = new ArrayList<>();
        List<DialogueLines.Line> lines = read("""
                [ { "text": "이 밭 좀 보게.", "animation": { "name": " animation.chief.point ", "play": "loop" } },
                  { "text": "고맙네!", "animation": { "name": "animation.chief.happy" } } ]
                """, errors);
        assertEquals(List.of(), errors);
        assertEquals(List.of(
                new DialogueLines.Line("이 밭 좀 보게.", "animation.chief.point", DialogueLines.Play.LOOP),
                new DialogueLines.Line("고맙네!", "animation.chief.happy", DialogueLines.Play.ONCE)), lines);
        // Playing once is always written as the bare name.
        String written = DialogueLines.write(lines).toString();
        assertEquals("[{\"text\":\"이 밭 좀 보게.\",\"animation\":{\"name\":\"animation.chief.point\",\"play\":\"loop\"}},"
                + "{\"text\":\"고맙네!\",\"animation\":\"animation.chief.happy\"}]", written);
        assertEquals(lines, read(written, errors));
    }

    @Test
    void badLinesAreRejected() {
        for (String bad : new String[] {
                "\"hi\"",                                                    // not a list
                "[ 1 ]",                                                     // not text
                "[ \" \" ]",                                                 // empty
                "[ { \"animation\": \"animation.a\" } ]",                    // no text
                "[ { \"text\": \" \", \"animation\": \"animation.a\" } ]",   // empty text
                "[ { \"text\": \"hi\", \"animation\": \" \" } ]",            // empty animation
                "[ { \"text\": \"hi\", \"animation\": 3 } ]",                // animation not text
                "[ { \"text\": \"hi\", \"anim\": \"animation.a\" } ]",       // unknown key
                "[ { \"text\": \"hi\", \"animation\": { \"play\": \"loop\" } } ]",                  // no name
                "[ { \"text\": \"hi\", \"animation\": { \"name\": \" \" } } ]",                     // empty name
                "[ { \"text\": \"hi\", \"animation\": { \"name\": \"a\", \"play\": \"once\" } } ]", // only loop
                "[ { \"text\": \"hi\", \"animation\": { \"name\": \"a\", \"play\": \"hold\" } } ]", // not yet
                "[ { \"text\": \"hi\", \"animation\": { \"name\": \"a\", \"loop\": true } } ]"}) {  // unknown key
            List<String> errors = new ArrayList<>();
            read(bad, errors);
            assertFalse(errors.isEmpty(), bad);
        }
    }

    @Test
    void noLinesIsNothing() {
        List<String> errors = new ArrayList<>();
        assertTrue(DialogueLines.read(null, "lines", errors).isEmpty());
        assertTrue(errors.isEmpty());
    }
}
