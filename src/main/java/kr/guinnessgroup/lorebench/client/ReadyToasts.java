/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.client;

import kr.guinnessgroup.lorebench.quest.QuestDoc;
import kr.guinnessgroup.lorebench.quest.QuestState;
import kr.guinnessgroup.lorebench.quest.QuestSyncPayload;
import kr.guinnessgroup.lorebench.quest.Quests;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watches, every client tick, whether the player meets their stages' needs, as the quest screen works it out,
 * and shows a {@link ReadyToast} the moment they do it themselves (0015 "다 채웠을 때 문장"). Not when it was
 * already so on joining or on reaching the stage, nor while a dialogue is open (the NPC says where to go), nor
 * when a wait is over; once per stage, remembered until leaving the server. Nothing is stored: it only shows.
 */
final class ReadyToasts {

    /** A quest's stage as last seen, and whether its needs were met. */
    private record Seen(String stage, boolean met) {}

    private static final Map<String, Seen> seen = new HashMap<>();
    /** "quest id/stage id" of those already told since joining. */
    private static final Set<String> told = new HashSet<>();
    private static QuestCard card = new QuestCard();
    private static List<QuestSyncPayload.Entry> cardFor;

    private ReadyToasts() {}

    static void tick(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        List<QuestSyncPayload.Entry> quests = ClientQuests.all();
        if (quests != cardFor) {
            card = new QuestCard(); // a new sync may carry new content: read the items again
            cardFor = quests;
        }
        boolean talking = mc.screen instanceof DialogueScreen;
        for (QuestSyncPayload.Entry e : quests) {
            QuestDoc.Quest q = e.quest();
            QuestDoc.Stage stage = q.current();
            boolean met = e.state() == QuestState.ACTIVE && stage != null
                    && Quests.goalsMet(player.getInventory(), e.progress(), q, stage, card::condition);
            String stageId = stage == null ? "" : stage.id();
            Seen before = seen.put(q.id(), new Seen(stageId, met));
            if (met && !talking && before != null && before.stage().equals(stageId) && !before.met()
                    && told.add(q.id() + "/" + stageId)) {
                mc.getToasts().addToast(new ReadyToast(card.icon(q), q.title(), stage.doneText()));
            }
        }
    }

    /** Leaving a server forgets what was seen and told. */
    static void clear() {
        seen.clear();
        told.clear();
        cardFor = null;
    }
}
