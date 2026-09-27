/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Quest items (collect goals, 0012): items that drop only for the player on the quest,
 * marked with the quest and that player in their custom data:
 * <pre>custom_data={ lorebench_quest_item: { quest: "quest_necklace", owner: [I; …] } }</pre>
 * Players in survival can't write custom data, so the mark can't be faked, and a piece
 * someone else picks up or is handed never counts for them. The mark lives in players'
 * inventories and chests: never rename its keys. It sits beside whatever custom data the
 * author's item has (0006's hidden marks use the key {@code lorebench}, so this one differs).
 */
public final class QuestItems {

    /** Never rename: saved in items. */
    public static final String MARK = "lorebench_quest_item";
    private static final String QUEST = "quest";
    private static final String OWNER = "owner";

    private QuestItems() {}

    /** The mark itself, put under {@link #MARK}. */
    public static CompoundTag mark(String questId, UUID owner) {
        CompoundTag mark = new CompoundTag();
        mark.putString(QUEST, questId);
        mark.putUUID(OWNER, owner);
        return mark;
    }

    /** Whether custom data carries the mark of this quest and owner. */
    public static boolean marks(CompoundTag customData, String questId, UUID owner) {
        if (!customData.contains(MARK, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag mark = customData.getCompound(MARK);
        return questId.equals(mark.getString(QUEST)) && mark.hasUUID(OWNER) && owner.equals(mark.getUUID(OWNER));
    }

    /** Marks the stack as this quest's item for this owner, keeping its other custom data. */
    public static void mark(ItemStack stack, String questId, UUID owner) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.put(MARK, mark(questId, owner)));
    }

    /** Items of this goal's kind marked for this quest and owner: what the goal counts and hand-in takes. */
    public static Predicate<ItemStack> of(QuestDoc.Goal goal, String questId, UUID owner) {
        String itemId = goal.itemId();
        return stack -> {
            CustomData data = stack.get(DataComponents.CUSTOM_DATA);
            return data != null && Quests.itemId(stack).equals(itemId) && marks(data.copyTag(), questId, owner);
        };
    }
}
