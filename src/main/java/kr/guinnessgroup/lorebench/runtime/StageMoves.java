/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.runtime;

import kr.guinnessgroup.lorebench.quest.QuestDoc;

import java.util.Map;

/**
 * What a publish does about players on the quest stages it removes (0015): called once the new
 * documents passed every check, right before they go live. See docs/decisions/0015-quest-stages.md.
 */
@FunctionalInterface
public interface StageMoves {

    StageMoves NONE = (before, after, moves) -> {};

    /**
     * @param before the quest document live now
     * @param after  the one about to go live
     * @param moves  a removed stage's id → the id of the stage of the same quest its players go to,
     *               as the author chose when publishing
     * @throws kr.guinnessgroup.lorebench.DocumentException to reject the publish; then nothing changes
     */
    void apply(QuestDoc before, QuestDoc after, Map<String, String> moves);
}
