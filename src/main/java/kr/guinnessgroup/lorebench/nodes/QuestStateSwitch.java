/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.nodes;

import com.google.gson.JsonObject;
import kr.guinnessgroup.lorebench.quest.QuestState;
import kr.guinnessgroup.lorebench.quest.Quests;
import kr.guinnessgroup.lorebench.runtime.Catalog;
import kr.guinnessgroup.lorebench.runtime.Field;
import kr.guinnessgroup.lorebench.runtime.Node;
import kr.guinnessgroup.lorebench.runtime.NodeResult;
import kr.guinnessgroup.lorebench.runtime.NodeType;

import java.util.Arrays;
import java.util.List;

/**
 * Where the player is with a quest: leaves through hidden / active / waiting / ready / done.
 * "Ready" means active with every goal in the player's inventory right now, or handed
 * in and the wait is over (0013).
 */
public final class QuestStateSwitch implements NodeType {

    private static final Field QUEST = Field.quest("quest", "Quest");

    @Override public String id() { return "lorebench:quest_state"; }
    @Override public String label() { return "Quest State"; }
    @Override public String category() { return "condition"; }
    @Override public List<String> outs() { return Arrays.stream(QuestState.values()).map(s -> s.out).toList(); }
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
            return NodeResult.next(quests.state(ctx.player(), quest).out);
        };
    }
}
