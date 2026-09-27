/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cues inside dialogue text (0014). They are saved in the text: changing how they read changes what NPCs say. */
class CuesTest {

    static final String CARROTS = "<loop=animation.chief.point><speed=0.5>이 밭 좀 보게<pause=1>……"
            + "<speed=2><play=animation.chief.shocked>헉, 토끼들이 당근을 다 파먹었잖아!";

    @Test
    void theStorysLineReadsIntoTextAndCuesInOrder() {
        assertEquals(List.of(
                new Cues.Animate("animation.chief.point", true),
                new Cues.Speed(0.5),
                new Cues.Text("이 밭 좀 보게"),
                new Cues.Pause(1),
                new Cues.Text("……"),
                new Cues.Speed(2),
                new Cues.Animate("animation.chief.shocked", false),
                new Cues.Text("헉, 토끼들이 당근을 다 파먹었잖아!")), Cues.parse(CARROTS));
        assertEquals("이 밭 좀 보게……헉, 토끼들이 당근을 다 파먹었잖아!", Cues.plain(CARROTS));
        assertEquals(List.of(), Cues.problems(CARROTS));
    }

    @Test
    void theVoiceGoesQuietAndComesBack() {
        assertEquals(List.of(new Cues.Voice(false), new Cues.Text("* 농부의 한숨이 깊다. "), new Cues.Voice(true),
                new Cues.Text("휴…")), Cues.parse("<voice=none>* 농부의 한숨이 깊다. <voice>휴…"));
    }

    @Test
    void onlyALessThanBeforeALetterStartsACue() {
        for (String text : List.of("A < B", "<- 저쪽", "고마워 <3", "1<2", "끝에 <", "그냥 글자")) {
            assertEquals(List.of(new Cues.Text(text)), Cues.parse(text), text);
            assertEquals(List.of(), Cues.problems(text), text);
        }
        // Braces are text: they are kept for putting values in later.
        assertEquals(List.of(new Cues.Text("{player}, 왔군!")), Cues.parse("{player}, 왔군!"));
        assertEquals("", Cues.plain("<pause=1>"));
    }

    @Test
    void badCuesAreReportedWithWhereTheyAreAndStayText() {
        List<String> problems = Cues.problems("안녕<spd=1>하세요");
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("'<spd=1>' at character 3"), problems.get(0));
        // A screen never loses words: an unread cue shows as it was written.
        assertEquals("안녕<spd=1>하세요", Cues.plain("안녕<spd=1>하세요"));
        List<String> bad = new ArrayList<>(List.of("<speed>", "<speed=0>", "<speed=11>", "<speed=-1>", "<speed=fast>",
                "<speed=1e1>", "<pause=11>", "<pause=>", "<play>", "<play=>", "<loop=a b>", "<voice=loud>", "<Speed=1>",
                "<shake>", "열림<speed=2"));
        for (String line : bad) {
            assertFalse(Cues.problems(line).isEmpty(), line);
        }
        assertEquals(List.of(), Cues.problems("<speed=10><speed=0.1><pause=0><pause=10><pause=0.25>"));
    }

    @Test
    void anEmojiIsOneLetter() {
        // Typing shows a letter outside the basic plane whole; the text keeps it as written.
        assertEquals("🥕 당근", Cues.plain("<speed=0.5>🥕 당근"));
    }
}
