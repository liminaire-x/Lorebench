/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The day a waiting quest was handed in (0013). It is a saved record: changing it moves everyone's wait. */
class QuestWaitsTest {

    @Test
    void theRecordIsTheDayHandedInUnderItsOwnKey() {
        assertEquals("handed_quest_order", QuestWaits.key("quest_order"));
        assertEquals("12", QuestWaits.write(12));
        assertEquals(12, QuestWaits.read("12"));
        assertEquals(-1, QuestWaits.read(null));
        assertEquals(-1, QuestWaits.read("soon"));
        assertEquals(-1, QuestWaits.read("-3"));
    }

    @Test
    void theDayTurnsAtSixInTheMorningAsF3CountsIt() {
        assertEquals(0, QuestWaits.day(0));      // 6:00 on the first day
        assertEquals(0, QuestWaits.day(18000));  // midnight: still the same day
        assertEquals(0, QuestWaits.day(23999));
        assertEquals(1, QuestWaits.day(24000));  // 6:00 next morning, also where waking up lands
        assertEquals(12, QuestWaits.day(12 * 24000 + 1000));
    }

    @Test
    void aWaitIsOverOnceItsDaysHavePassed() {
        String handed = QuestWaits.write(12);
        assertFalse(QuestWaits.over(handed, 1, 12)); // the same day, even just before the next morning
        assertTrue(QuestWaits.over(handed, 1, 13));
        assertFalse(QuestWaits.over(handed, 2, 13));
        assertTrue(QuestWaits.over(handed, 2, 14));
        assertTrue(QuestWaits.over(handed, 1, 40));
        // The author took the wait away: over at once.
        assertTrue(QuestWaits.over(handed, 0, 12));
    }

    @Test
    void noOneWaitsForeverBecauseTimeWentBackOrTheRecordBroke() {
        // /time set day turns the day number back to 0.
        assertTrue(QuestWaits.over(QuestWaits.write(12), 1, 0));
        assertTrue(QuestWaits.over(null, 1, 12));
        assertTrue(QuestWaits.over("soon", 1, 12));
    }
}
