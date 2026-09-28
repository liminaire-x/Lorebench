/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The stage a player is on (0015). It is a saved record: changing it moves everyone to another stage. */
class QuestStagesTest {

    static final QuestDoc.Quest QUEST = QuestFormat.read("""
            { "format": 1, "quests": [ { "id": "quest_necklace", "title": "목걸이 찾기", "stages": [
              { "id": "stage_show", "text": "보여주기" }, { "id": "stage_mend", "text": "맡기기" } ], "rewards": [] } ] }
            """).find("quest_necklace");

    @Test
    void theRecordIsTheStageIdUnderItsOwnKey() {
        assertEquals("stage_quest_necklace", QuestStages.key("quest_necklace"));
        assertEquals("stage_mend", QuestStages.of(QUEST, "stage_mend").id());
    }

    @Test
    void noRecordIsTheFirstStage() {
        // Right after accepting, and quests taken before there were stages.
        assertEquals("stage_show", QuestStages.of(QUEST, null).id());
    }

    /** The quest with its stages as {@code ids} (texts don't matter). */
    static QuestDoc.Quest withStages(String... ids) {
        StringBuilder stages = new StringBuilder();
        for (String id : ids) {
            stages.append(stages.isEmpty() ? "" : ",")
                    .append("{\"id\":\"").append(id).append("\",\"text\":\"").append(id).append("\"}");
        }
        return QuestFormat.read("{\"format\":1,\"quests\":[{\"id\":\"quest_necklace\",\"title\":\"목걸이 찾기\",\"stages\":["
                + stages + "],\"rewards\":[]}]}").find("quest_necklace");
    }

    static final QuestDoc.Quest BEFORE = withStages("stage_show", "stage_mend", "stage_give");

    static QuestStages.Move where(String stored, QuestDoc.Quest after, Map<String, String> moves) {
        return QuestStages.whereTo(BEFORE, after, stored, moves);
    }

    @Test
    void insertingAndReorderingMovesNobody() {
        QuestDoc.Quest inserted = withStages("stage_show", "stage_new", "stage_mend", "stage_give");
        assertEquals(QuestStages.Move.STAY, where("stage_mend", inserted, Map.of()));
        assertEquals(QuestStages.Move.STAY, where(null, inserted, Map.of())); // still first
        // Another stage becomes first: one with no record was on the old first and is pinned to it.
        QuestDoc.Quest reordered = withStages("stage_mend", "stage_show", "stage_give");
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.PIN, "stage_show"), where(null, reordered, Map.of()));
        assertEquals(QuestStages.Move.STAY, where("stage_show", reordered, Map.of()));
        assertEquals(QuestStages.Move.STAY, where("stage_mend", reordered, Map.of()));
        QuestDoc.Quest before = withStages("stage_new", "stage_show", "stage_mend", "stage_give");
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.PIN, "stage_show"), where(null, before, Map.of()));
    }

    @Test
    void aRemovedStagesPlayersGoWhereTheAuthorChoseAndNowhereElse() {
        QuestDoc.Quest removed = withStages("stage_show", "stage_give");
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.MOVE, "stage_show"),
                where("stage_mend", removed, Map.of("stage_mend", "stage_show")));
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.UNCHOSEN, "stage_mend"), where("stage_mend", removed, Map.of()));
        // A stage of another quest, or one that is gone too, is not a choice.
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.UNCHOSEN, "stage_mend"),
                where("stage_mend", removed, Map.of("stage_mend", "stage_elsewhere")));
        assertEquals(QuestStages.Move.STAY, where("stage_give", removed, Map.of("stage_mend", "stage_show")));
        // The first stage removed: those with no record were on it.
        QuestDoc.Quest firstGone = withStages("stage_mend", "stage_give");
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.MOVE, "stage_give"),
                where(null, firstGone, Map.of("stage_show", "stage_give")));
        assertEquals(new QuestStages.Move(QuestStages.Move.Kind.UNCHOSEN, "stage_show"), where(null, firstGone, Map.of()));
    }

    @Test
    void oneAlreadyOnAnUnknownStageIsLeftForTheAuthor() {
        assertEquals(QuestStages.Move.STAY, where("stage_gone", withStages("stage_show"), Map.of()));
    }

    @Test
    void aStageTheQuestNoLongerHasIsNotGuessed() {
        // The player waits there until the author moves them (0015); never quietly the first stage.
        assertNull(QuestStages.of(QUEST, "stage_gone"));
        assertNull(QuestStages.of(QUEST, ""));
    }
}
