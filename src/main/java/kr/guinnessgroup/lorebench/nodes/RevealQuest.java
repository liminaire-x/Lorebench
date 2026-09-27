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
 * Reveals a quest to the player: it becomes active at its first stage and appears in their
 * quest screen. Only this player's client receives it. Does nothing if already revealed.
 */
public final class RevealQuest implements NodeType {

    private static final Field QUEST = Field.quest("quest", "Quest");

    @Override public String id() { return "lorebench:reveal_quest"; }
    @Override public String label() { return "Reveal Quest"; }
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
            quests.reveal(ctx.player(), quest);
            return NodeResult.next();
        };
    }
}
