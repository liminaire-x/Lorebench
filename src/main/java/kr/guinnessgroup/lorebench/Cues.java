/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Cues inside a line of dialogue, in angle brackets, each working from where it stands to the
 * end of the page (there are no closing cues):
 * <pre>&lt;loop=animation.chief.point&gt;&lt;speed=0.5&gt;이 밭 좀 보게&lt;pause=1&gt;……&lt;speed=2&gt;&lt;play=animation.chief.shocked&gt;헉!</pre>
 * {@code <speed=times>} (1 = the usual speed, above 0 up to 10), {@code <pause=seconds>} (0 to 10),
 * {@code <play=animation>} (once), {@code <loop=animation>} (until the page turns), {@code <voice=none>}
 * (no sound from here) and {@code <voice>} (the NPC's own voice again). Only a {@code <} followed by an
 * English letter starts a cue, so "A &lt; B", "&lt;-" and "&lt;3" are text. Braces are left free for
 * putting values in later ({@code {player}}). Saved in quest and NPC documents inside the text: never
 * rename a cue. See docs/decisions/0014-typing.md.
 */
public final class Cues {

    /** One piece of a line, in order. */
    public sealed interface Part permits Text, Speed, Pause, Animate, Voice {}

    /** Text shown as it types. */
    public record Text(String text) implements Part {}

    /** From here, this many times the usual speed. */
    public record Speed(double times) implements Part {}

    /** Stop typing for a while here. */
    public record Pause(double seconds) implements Part {}

    /** The NPC plays an animation here, once or until the page turns. */
    public record Animate(String animation, boolean loop) implements Part {}

    /** From here, no sound ({@code on} false), or the NPC's own voice again. */
    public record Voice(boolean on) implements Part {}

    public static final double MAX_SPEED = 10;
    public static final double MAX_PAUSE = 10;

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final String KNOWN = "speed, pause, play, loop, voice";

    private Cues() {}

    /**
     * The parts of a line. A cue that can't be read stays as text (publish rejects those, see
     * {@link #problems}), so a screen never loses words.
     */
    public static List<Part> parse(String line) {
        return read(line, null);
    }

    /** The line as it reads on screen, without its cues. */
    public static String plain(String line) {
        StringBuilder out = new StringBuilder();
        for (Part p : parse(line)) {
            if (p instanceof Text t) {
                out.append(t.text());
            }
        }
        return out.toString();
    }

    /** Letters typed without the voice's sound: spaces and . , ! ? … ~ (0014). */
    private static final String QUIET = ".,!?…~";

    /** Whether a letter makes the NPC's voice sound as it types. */
    public static boolean voiced(int codePoint) {
        return !Character.isWhitespace(codePoint) && QUIET.indexOf(codePoint) < 0;
    }

    /** What is wrong with the line's cues, each with where it is ("at character 12"); empty if nothing. */
    public static List<String> problems(String line) {
        List<String> problems = new ArrayList<>();
        read(line, problems);
        return problems;
    }

    private static List<Part> read(String line, List<String> problems) {
        List<Part> parts = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '<' && i + 1 < line.length() && isLetter(line.charAt(i + 1))) {
                int end = line.indexOf('>', i);
                String at = " at character " + (i + 1);
                if (end < 0) {
                    report(problems, "'" + shorten(line.substring(i)) + "'" + at + " has no closing '>'");
                } else {
                    String cue = line.substring(i, end + 1);
                    String[] problem = new String[1];
                    Part part = cue(line.substring(i + 1, end), problem);
                    if (part != null) {
                        if (!text.isEmpty()) {
                            parts.add(new Text(text.toString()));
                            text.setLength(0);
                        }
                        parts.add(part);
                        i = end + 1;
                        continue;
                    }
                    report(problems, "'" + cue + "'" + at + " " + problem[0]);
                }
            }
            text.append(c);
            i++;
        }
        if (!text.isEmpty()) {
            parts.add(new Text(text.toString()));
        }
        return List.copyOf(parts);
    }

    /** A cue from what is between its brackets, or null with {@code problem[0]} saying why. */
    private static Part cue(String inside, String[] problem) {
        int eq = inside.indexOf('=');
        String name = eq < 0 ? inside : inside.substring(0, eq);
        String value = eq < 0 ? null : inside.substring(eq + 1);
        switch (name) {
            case "speed", "pause" -> {
                double max = name.equals("speed") ? MAX_SPEED : MAX_PAUSE;
                if (value == null || !NUMBER.matcher(value).matches()) {
                    problem[0] = "needs a number, like <" + name + "=" + (name.equals("speed") ? "0.5" : "1") + ">";
                    return null;
                }
                double n = Double.parseDouble(value);
                if (name.equals("speed") ? (n <= 0 || n > max) : n > max) {
                    problem[0] = name.equals("speed") ? "must be above 0 and at most " + (int) max
                            : "must be at most " + (int) max + " seconds";
                    return null;
                }
                return name.equals("speed") ? new Speed(n) : new Pause(n);
            }
            case "play", "loop" -> {
                if (value == null || value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
                    problem[0] = "needs an animation name, like <" + name + "=animation.chief.happy>";
                    return null;
                }
                return new Animate(value, name.equals("loop"));
            }
            case "voice" -> {
                if (value == null) {
                    return new Voice(true);
                }
                if (value.equals("none")) {
                    return new Voice(false);
                }
                problem[0] = "can only be <voice=none> (no sound) or <voice> (the NPC's voice again)";
                return null;
            }
            default -> {
                problem[0] = "is not a cue (use " + KNOWN + "; a '<' before a letter starts a cue)";
                return null;
            }
        }
    }

    private static boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static String shorten(String s) {
        return s.length() > 20 ? s.substring(0, 20) + "…" : s;
    }

    private static void report(List<String> problems, String problem) {
        if (problems != null) {
            problems.add(problem);
        }
    }
}
