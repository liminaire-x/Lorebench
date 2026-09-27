/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The quest item mark. It lives in players' inventories: changing it makes their pieces stop counting. */
class QuestItemsTest {

    static final UUID DEV1 = UUID.fromString("380df991-f603-344c-a090-369bad2a924a");
    static final UUID DEV2 = UUID.fromString("5b2c1d7e-0000-4000-8000-000000000002");

    @Test
    void theMarkIsTheQuestAndTheOwnerUnderItsOwnKey() {
        CompoundTag data = new CompoundTag();
        data.put(QuestItems.MARK, QuestItems.mark("quest_necklace", DEV1));
        assertEquals("lorebench_quest_item", QuestItems.MARK);
        CompoundTag mark = data.getCompound("lorebench_quest_item");
        assertEquals("quest_necklace", mark.getString("quest"));
        // A UUID as Minecraft writes it in NBT: four ints.
        assertArrayEquals(NbtUtils.createUUID(DEV1).getAsIntArray(), mark.getIntArray("owner"));
    }

    @Test
    void itCountsOnlyForItsQuestAndOwner() {
        CompoundTag data = new CompoundTag();
        data.putString("lorebench", "smith_sword"); // an author's own hidden mark (0006) stays beside it
        data.put(QuestItems.MARK, QuestItems.mark("quest_necklace", DEV1));
        assertTrue(QuestItems.marks(data, "quest_necklace", DEV1));
        assertFalse(QuestItems.marks(data, "quest_necklace", DEV2));
        assertFalse(QuestItems.marks(data, "quest_wolf", DEV1));
        assertFalse(QuestItems.marks(new CompoundTag(), "quest_necklace", DEV1));
        CompoundTag broken = new CompoundTag();
        broken.putString(QuestItems.MARK, "quest_necklace");
        assertFalse(QuestItems.marks(broken, "quest_necklace", DEV1));
    }

    @Test
    void anyQuestsMarkIsAQuestItemWhoeverOwnsIt() {
        CompoundTag mine = new CompoundTag();
        mine.put(QuestItems.MARK, QuestItems.mark("quest_necklace", DEV1));
        CompoundTag theirs = new CompoundTag();
        theirs.put(QuestItems.MARK, QuestItems.mark("quest_wolf", DEV2));
        assertTrue(QuestItems.isMarked(mine));
        assertTrue(QuestItems.isMarked(theirs));
        CompoundTag authors = new CompoundTag();
        authors.putString("lorebench", "smith_sword"); // 0006's hidden mark is not a quest item
        assertFalse(QuestItems.isMarked(authors));
        assertFalse(QuestItems.isMarked(new CompoundTag()));
        CompoundTag broken = new CompoundTag();
        broken.putString(QuestItems.MARK, "quest_necklace");
        assertFalse(QuestItems.isMarked(broken));
    }
}
