/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

/**
 * The stage a player is on, saved as their record {@code stage_<quest id>} = the stage's id
 * ({@code stage_e5f6g7h8}). No record means the first stage (right after accepting, and quests
 * taken before there were stages). The id stays put when the author inserts or reorders
 * stages; a record naming a stage the quest no longer has is left as it is, and the player
 * waits there until the author takes them back. Removed when the quest is done, and when the
 * editor takes a player back to before the quest. See docs/decisions/0015-quest-stages.md.
 */
public final class QuestStages {

    private QuestStages() {}

    public static String key(String questId) {
        return "stage_" + questId;
    }

    /**
     * The stage a stored value names: the first stage when there is none, or {@code null}
     * when the quest has no such stage (it was removed).
     *
     * @param value the stored value of {@link #key}, or {@code null}
     */
    public static QuestDoc.Stage of(QuestDoc.Quest quest, String value) {
        if (value == null) {
            return quest.stages().isEmpty() ? null : quest.stages().getFirst();
        }
        return quest.stage(value);
    }
}
