/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.Cues;

import java.util.List;
import java.util.function.Consumer;

/**
 * One page of dialogue typing out, a letter per tick at the usual speed (0014), each letter
 * but spaces and punctuation with the NPC's voice. Its cues change the speed, pause, have the
 * NPC play an animation, or turn the voice off and on as the typing reaches them. Ticked by
 * the dialogue screen, 20 times a second. Showing the rest of the page at once makes no sound.
 */
final class Typing {

    private static final int TICKS_PER_SECOND = 20;
    /** Fewest ticks between two letter sounds, so fast typing doesn't blur into a buzz. */
    private static final int SOUND_GAP = 2;

    private final List<Cues.Part> parts;
    private final String plain;
    private final Consumer<Cues.Animate> animate;
    private final Runnable letterSound;
    private boolean voiceOn = true;
    private int ticks;
    private int lastSound = -SOUND_GAP;
    /** The next part to reach, and how far into it when it is text. */
    private int part;
    private int inPart;
    /** Letters of {@link #plain} shown. */
    private int shown;
    private double speed = 1;
    /** Letters owed at the current speed: a speed of 0.5 shows one every second tick. */
    private double budget;
    private int pauseTicks;

    /**
     * @param animate     plays an animation cue on the NPC talking
     * @param letterSound plays the NPC's voice once, for a letter typed
     */
    Typing(String line, Consumer<Cues.Animate> animate, Runnable letterSound) {
        this.parts = Cues.parse(line);
        this.plain = Cues.plain(line);
        this.animate = animate;
        this.letterSound = letterSound;
    }

    /** The page's words, without cues. */
    String plain() {
        return plain;
    }

    /** How many letters of {@link #plain} are shown. */
    int shown() {
        return shown;
    }

    boolean done() {
        return part >= parts.size();
    }

    void tick() {
        if (done()) {
            return;
        }
        ticks++;
        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }
        budget += speed;
        while (!done() && pauseTicks == 0) {
            if (parts.get(part) instanceof Cues.Text t) {
                if (budget < 1) {
                    break;
                }
                // A letter outside the basic plane (an emoji) is two chars: show both at once.
                int letter = t.text().codePointAt(inPart);
                int n = Character.charCount(letter);
                if (voiceOn && Cues.voiced(letter) && ticks - lastSound >= SOUND_GAP) {
                    letterSound.run();
                    lastSound = ticks;
                }
                inPart += n;
                shown += n;
                budget -= 1;
                if (inPart >= t.text().length()) {
                    part++;
                    inPart = 0;
                }
            } else {
                reach(parts.get(part));
                part++;
            }
        }
        if (done()) {
            budget = 0;
        }
    }

    private void reach(Cues.Part cue) {
        switch (cue) {
            case Cues.Speed s -> speed = s.times();
            case Cues.Pause p -> {
                pauseTicks = (int) Math.round(p.seconds() * TICKS_PER_SECOND);
                budget = 0;
            }
            case Cues.Animate a -> animate.accept(a);
            case Cues.Voice v -> voiceOn = v.on();
            case Cues.Text t -> { }
        }
    }

    /**
     * Show the whole page now (a click while typing). Of the animations skipped over, only the
     * last plays, so the NPC ends the page as it would have.
     */
    void finish() {
        Cues.Animate last = null;
        for (; part < parts.size(); part++) {
            if (parts.get(part) instanceof Cues.Animate a) {
                last = a;
            }
        }
        shown = plain.length();
        inPart = 0;
        pauseTicks = 0;
        budget = 0;
        if (last != null) {
            animate.accept(last);
        }
    }
}
