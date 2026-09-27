/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.npc;

import kr.guinnessgroup.lorebench.Lorebench;
import kr.guinnessgroup.lorebench.quest.QuestItemEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Lorebench's entity types. The ids are saved in worlds: never rename. */
public final class LorebenchEntities {

    public static final DeferredRegister<EntityType<?>> TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Lorebench.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<NpcEntity>> NPC = TYPES.register("npc",
            () -> EntityType.Builder.<NpcEntity>of(NpcEntity::new, MobCategory.MISC)
                    .sized(0.6f, 1.8f)
                    .build("npc"));

    /** A quest item on the ground, seen by its owner only (0012). Sized and tracked like a vanilla item. */
    public static final DeferredHolder<EntityType<?>, EntityType<QuestItemEntity>> QUEST_ITEM = TYPES.register("quest_item",
            () -> EntityType.Builder.<QuestItemEntity>of(QuestItemEntity::new, MobCategory.MISC)
                    .sized(0.25f, 0.25f)
                    .eyeHeight(0.2125f)
                    .clientTrackingRange(6)
                    .updateInterval(20)
                    .build("quest_item"));

    private LorebenchEntities() {}

    /** Mod bus: living entities need their attributes registered. */
    public static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(NPC.get(), Mob.createMobAttributes().build());
    }
}
