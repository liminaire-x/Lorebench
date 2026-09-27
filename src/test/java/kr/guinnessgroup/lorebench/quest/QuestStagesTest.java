/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import org.junit.jupiter.api.Test;

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

    @Test
    void aStageTheQuestNoLongerHasIsNotGuessed() {
        // The player waits there until the author moves them (0015); never quietly the first stage.
        assertNull(QuestStages.of(QUEST, "stage_gone"));
        assertNull(QuestStages.of(QUEST, ""));
    }
}
