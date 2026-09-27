/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

/**
 * How many times a player turned a quest down, saved as their record
 * {@code declined_<quest id>} = {@code 3} (no record = never). Kept apart from the quest's
 * state so the state stays hidden / active / done; kept when they accept later, removed
 * when the editor takes them back to before the quest.
 * See docs/decisions/0012-lost-necklace.md.
 */
public final class QuestDeclines {

    private QuestDeclines() {}

    public static String key(String questId) {
        return "declined_" + questId;
    }

    /** The count in a stored value; 0 if there is none or it is broken. */
    public static int read(String value) {
        if (value == null) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String write(int times) {
        return Integer.toString(times);
    }
}
