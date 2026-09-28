/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import java.util.Map;

/**
 * The stage a player is on, saved as their record {@code stage_<quest id>} = the stage's id
 * ({@code stage_e5f6g7h8}). No record means the first stage (right after accepting, and quests
 * taken before there were stages). The id stays put when the author inserts or reorders
 * stages; players on a stage a publish removes go where the author chose ({@link #whereTo}).
 * A record naming a stage the quest no longer has otherwise (the file changed by hand) is left
 * as it is, and the player waits there until the author takes them back. Removed when the quest
 * is done, and when the editor takes a player back to before the quest. See
 * docs/decisions/0015-quest-stages.md.
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

    /**
     * What a publish does to one player on a quest (0015).
     *
     * @param stage the stage id to write (PIN, MOVE), or the removed stage they are on (UNCHOSEN)
     */
    public record Move(Kind kind, String stage) {

        public static final Move STAY = new Move(Kind.STAY, "");

        public enum Kind {
            /** Nothing to write: their stage is still there (or was unknown before; left for the author). */
            STAY,
            /** Still on the stage that was first, which no longer is: write it, so they stay on it. */
            PIN,
            /** Their stage is removed: go to the one the author chose, from its start. */
            MOVE,
            /** Their stage is removed and the author chose nowhere: the publish is rejected. */
            UNCHOSEN
        }
    }

    /**
     * Where a player on {@code before} stands once {@code after} is published. Inserting and
     * reordering stages never moves anyone: one with no record was on the first stage and stays
     * on it even if another becomes first. One on a removed stage goes where {@code moves} says,
     * if that is a stage of {@code after}; never quietly anywhere else.
     *
     * @param stored their stored value of {@link #key}, or {@code null}
     * @param moves  a removed stage's id → the id of the stage its players go to
     */
    public static Move whereTo(QuestDoc.Quest before, QuestDoc.Quest after, String stored, Map<String, String> moves) {
        String at = stored != null ? stored : before.stages().isEmpty() ? null : before.stages().getFirst().id();
        if (at == null || after.stages().isEmpty()) {
            return Move.STAY;
        }
        if (after.stage(at) != null) {
            boolean firstChanged = !after.stages().getFirst().id().equals(at);
            return stored == null && firstChanged ? new Move(Move.Kind.PIN, at) : Move.STAY;
        }
        if (before.stage(at) == null) {
            return Move.STAY;
        }
        String to = moves.get(at);
        return to != null && after.stage(to) != null
                ? new Move(Move.Kind.MOVE, to) : new Move(Move.Kind.UNCHOSEN, at);
    }
}
