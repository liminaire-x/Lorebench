/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * What an NPC says, as a list of lines shown one page at a time. Used by a quest's
 * lines and an NPC's greeting, alone or in groups picked by condition ({@link Speech}). A line is text, or text with an animation the NPC
 * plays when the page shows, by name (once) or as { name, play }:
 * <pre>[ "고맙네!", { "text": "약속한 에메랄드일세.", "animation": "animation.chief.happy" },
 *   { "text": "이 밭 좀 보게.", "animation": { "name": "animation.chief.point", "play": "loop" } } ]</pre>
 * A line without an animation is always written as plain text, and an animation played
 * once always as a name, so older documents look as before. See
 * docs/decisions/0009-quest-workbench.md and docs/decisions/0011-talk-gestures.md.
 */
public final class DialogueLines {

    /** How a line's animation plays. Saved by {@link #json} name, never rename. */
    public enum Play {
        /** Once when the page shows (written as the bare name). */
        ONCE("once"),
        /** Over and over while the page shows. */
        LOOP("loop");

        public final String json;

        Play(String json) {
            this.json = json;
        }
    }

    /**
     * One page of dialogue.
     *
     * @param animation played by the NPC when the page shows, or ""
     * @param play      how it plays ({@link Play#ONCE} when there is no animation)
     */
    public record Line(String text, String animation, Play play) {

        public Line(String text, String animation) {
            this(text, animation, Play.ONCE);
        }

        public static Line of(String text) {
            return new Line(text, "");
        }
    }

    /** The keys of a line written as an object. Never rename: they are saved. */
    private static final Set<String> KEYS = Set.of("text", "animation");
    /** The keys of an animation written as an object. Never rename: they are saved. */
    private static final Set<String> ANIMATION_KEYS = Set.of("name", "play");

    private DialogueLines() {}

    /** Plain text lines, e.g. for tests and defaults. */
    public static List<Line> text(String... lines) {
        List<Line> out = new ArrayList<>();
        for (String l : lines) {
            out.add(Line.of(l));
        }
        return List.copyOf(out);
    }

    /**
     * Reads a list of lines (absent = none). Each line's text must not be blank (cues aside), its
     * cues must read ({@link Cues}, 0014), and an animation, if given, must not be blank either.
     *
     * @param where names the list in error messages, e.g. "quest 'quest_a' offer"
     */
    public static List<Line> read(JsonElement e, String where, List<String> errors) {
        if (e == null) {
            return List.of();
        }
        if (!e.isJsonArray()) {
            errors.add(where + ": lines must be a list");
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        for (JsonElement line : e.getAsJsonArray()) {
            Line read = line.isJsonObject() ? readObject(line.getAsJsonObject(), where, errors)
                    : isText(line) ? new Line(line.getAsString(), "")
                    : null;
            if (read == null) {
                if (!line.isJsonObject()) {
                    errors.add(where + ": a line must be text, or an object with text and animation");
                }
            } else if (Cues.plain(read.text()).isBlank()) {
                errors.add(where + ": a line is empty");
            } else {
                List<String> problems = Cues.problems(read.text());
                problems.forEach(p -> errors.add(where + ": in '" + shorten(read.text()) + "', " + p));
                if (problems.isEmpty()) {
                    lines.add(read);
                }
            }
        }
        return List.copyOf(lines);
    }

    private static Line readObject(JsonObject o, String where, List<String> errors) {
        for (String key : o.keySet()) {
            if (!KEYS.contains(key)) {
                errors.add(where + ": unknown line '" + key + "' (use text, animation)");
                return null;
            }
        }
        JsonElement text = o.get("text");
        JsonElement animation = o.get("animation");
        if (!isText(text)) {
            errors.add(where + ": a line needs its text");
            return null;
        }
        if (animation == null) {
            return Line.of(text.getAsString());
        }
        String of = "the animation of '" + text.getAsString() + "'";
        JsonElement name = animation;
        Play play = Play.ONCE;
        if (animation.isJsonObject()) {
            JsonObject a = animation.getAsJsonObject();
            for (String key : a.keySet()) {
                if (!ANIMATION_KEYS.contains(key)) {
                    errors.add(where + ": unknown '" + key + "' in " + of + " (use name, play)");
                    return null;
                }
            }
            name = a.get("name");
            JsonElement p = a.get("play");
            // Only "loop" for now: playing once is written as the bare name (0011).
            if (p != null) {
                if (!isText(p) || !p.getAsString().equals(Play.LOOP.json)) {
                    errors.add(where + ": " + of + " can only play 'loop' (write just the name to play it once)");
                    return null;
                }
                play = Play.LOOP;
            }
        }
        if (!isText(name) || name.getAsString().isBlank()) {
            errors.add(where + ": " + of + " must be a name like animation.chief.happy");
            return null;
        }
        return new Line(text.getAsString(), name.getAsString().trim(), play);
    }


    /** A line in a message: its start, if it is long. */
    private static String shorten(String text) {
        return text.length() > 30 ? text.substring(0, 30) + "…" : text;
    }

    private static boolean isText(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    public static JsonArray write(List<Line> lines) {
        JsonArray arr = new JsonArray();
        for (Line l : lines) {
            if (l.animation().isEmpty()) {
                arr.add(l.text());
            } else {
                JsonObject o = new JsonObject();
                o.addProperty("text", l.text());
                if (l.play() == Play.ONCE) {
                    o.addProperty("animation", l.animation());
                } else {
                    JsonObject a = new JsonObject();
                    a.addProperty("name", l.animation());
                    a.addProperty("play", l.play().json);
                    o.add("animation", a);
                }
                arr.add(o);
            }
        }
        return arr;
    }
}
