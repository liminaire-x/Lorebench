/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The refusal record. Changing it forgets how often everyone said no. */
class QuestDeclinesTest {

    @Test
    void storedUnderItsKindNextToTheQuestState() {
        assertEquals("declined_quest_k3f9x2ma", QuestDeclines.key("quest_k3f9x2ma"));
    }

    @Test
    void aPlainCountRoundTrips() {
        assertEquals("3", QuestDeclines.write(3));
        assertEquals(3, QuestDeclines.read(QuestDeclines.write(3)));
    }

    @Test
    void missingOrBrokenMeansNeverDeclined() {
        assertEquals(0, QuestDeclines.read(null));
        assertEquals(0, QuestDeclines.read("often"));
        assertEquals(0, QuestDeclines.read("-2"));
    }
}
