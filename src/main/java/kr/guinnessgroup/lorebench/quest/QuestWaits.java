/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

/**
 * The day a player handed a waiting quest in, saved as their record
 * {@code handed_<quest id>} = {@code 12}: the game's day number then, as F3 shows it
 * ({@code dayTime / 24000}, turning at 6:00 in the morning). The wait is over once the
 * quest's days have passed since, so an author's new wait counts for everyone at once.
 * Removed when the rewards are taken, and when the editor takes them back to before
 * the quest. See docs/decisions/0013-waiting.md.
 */
public final class QuestWaits {

    /** Ticks in a game day. */
    public static final long DAY = 24000L;

    private QuestWaits() {}

    public static String key(String questId) {
        return "handed_" + questId;
    }

    /** The game's day number at this day time. */
    public static long day(long dayTime) {
        return Math.floorDiv(dayTime, DAY);
    }

    /** The day in a stored value, or -1 if there is none or it is broken. */
    public static long read(String value) {
        if (value == null) {
            return -1;
        }
        try {
            long day = Long.parseLong(value.trim());
            return day < 0 ? -1 : day;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String write(long day) {
        return Long.toString(day);
    }

    /**
     * Whether a wait of {@code days} is over on {@code today}. Also over when the day is
     * unknown (a broken record) or earlier than the day handed in ({@code /time set day}
     * turns the day number back to 0): no one waits forever because time went back.
     *
     * @param handed the stored value of {@link #key}
     */
    public static boolean over(String handed, int days, long today) {
        long day = read(handed);
        return day < 0 || today < day || today - day >= days;
    }
}
