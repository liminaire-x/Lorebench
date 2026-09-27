/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Stored quest state and the graph's ways out. Changing these breaks saved records and graphs. */
class QuestStateTest {

    @Test
    void onlyActiveWaitingAndDoneAreStored() {
        assertEquals("active", QuestState.ACTIVE_VALUE);
        assertEquals("waiting", QuestState.WAITING_VALUE);
        assertEquals("done", QuestState.DONE_VALUE);
        assertEquals(QuestState.HIDDEN, QuestState.fromRecord(null));
        assertEquals(QuestState.ACTIVE, QuestState.fromRecord("active"));
        assertEquals(QuestState.WAITING, QuestState.fromRecord("waiting"));
        assertEquals(QuestState.DONE, QuestState.fromRecord("done"));
        assertEquals(QuestState.HIDDEN, QuestState.fromRecord("ready")); // never stored
    }

    @Test
    void waysOutKeepTheirNames() {
        assertEquals(List.of("hidden", "active", "waiting", "ready", "done"),
                Arrays.stream(QuestState.values()).map(s -> s.out).toList());
    }
}
