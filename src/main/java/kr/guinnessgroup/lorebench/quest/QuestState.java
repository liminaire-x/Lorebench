/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

/**
 * Where a player is with a quest. Stored as the player's record whose key is the
 * quest id itself (e.g. {@code quest_k3f9x2ma}). Only three values are stored:
 * no record = hidden, {@code active}, {@code waiting} (handed in, waiting for the
 * rewards, 0013), {@code done}. "Ready" is never stored: it is an active quest whose
 * goals are in the player's inventory right now, so dropping the items can never
 * leave a stale "ready" behind, or a waiting quest whose wait is over.
 */
public enum QuestState {
    HIDDEN("hidden"),
    ACTIVE("active"),
    WAITING("waiting"),
    READY("ready"),
    DONE("done");

    /** The way out of the Quest State node, saved in graphs. Never rename. */
    public final String out;

    QuestState(String out) {
        this.out = out;
    }

    /** Stored record values. Never rename. */
    public static final String ACTIVE_VALUE = "active";
    public static final String WAITING_VALUE = "waiting";
    public static final String DONE_VALUE = "done";

    /** The stored state, before looking at the inventory or the day: HIDDEN, ACTIVE, WAITING or DONE. */
    public static QuestState fromRecord(String value) {
        if (ACTIVE_VALUE.equals(value)) {
            return ACTIVE;
        }
        if (WAITING_VALUE.equals(value)) {
            return WAITING;
        }
        if (DONE_VALUE.equals(value)) {
            return DONE;
        }
        return HIDDEN;
    }
}
