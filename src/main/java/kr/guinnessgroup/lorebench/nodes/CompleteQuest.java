/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.nodes;

import com.google.gson.JsonObject;
import kr.guinnessgroup.lorebench.quest.Quests;
import kr.guinnessgroup.lorebench.runtime.Catalog;
import kr.guinnessgroup.lorebench.runtime.Field;
import kr.guinnessgroup.lorebench.runtime.Node;
import kr.guinnessgroup.lorebench.runtime.NodeResult;
import kr.guinnessgroup.lorebench.runtime.NodeType;

import java.util.List;

/**
 * Hands in a ready quest's stage, one step at a time as the dialogue's buttons do: takes the
 * goal items, then a stage with a wait starts it (0013) and, once it is over (ready again), the
 * next run goes on. Going on is the next stage (0015), or after the last the rewards, and the
 * quest is done. Place it after the "ready" way out of Quest State. If the quest is not
 * ready for this player, nothing changes and the run stops (logged). A
 * "not ready" way out can be added later without breaking graphs that use "next".
 */
public final class CompleteQuest implements NodeType {

    private static final Field QUEST = Field.quest("quest", "Quest");

    @Override public String id() { return "lorebench:complete_quest"; }
    @Override public String label() { return "Complete Quest"; }
    @Override public String category() { return "action"; }
    @Override public List<Field> fields() { return List.of(QUEST); }

    @Override
    public Node create(JsonObject config, Catalog catalog) {
        String quest = QUEST.read(config);
        if (quest.isBlank()) {
            throw new IllegalArgumentException("choose a quest");
        }
        if (!catalog.hasQuest(quest)) {
            throw new IllegalArgumentException("no quest with id '" + quest + "'");
        }
        return ctx -> {
            if (ctx.player() == null) {
                return NodeResult.fail("no player in this event");
            }
            Quests quests = Quests.current();
            if (quests == null) {
                return NodeResult.fail("quests are not running");
            }
            if (!quests.complete(ctx.player(), quest)) {
                return NodeResult.fail("quest '" + quest + "' is not ready for "
                        + ctx.player().getGameProfile().getName() + "; nothing taken or given");
            }
            return NodeResult.next();
        };
    }
}
