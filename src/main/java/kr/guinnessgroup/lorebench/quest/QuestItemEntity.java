/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.npc.LorebenchEntities;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * A quest item lying on the ground (0012): a dropped item that only its owner sees and
 * picks up. The server asks every entity whether to send it to a player
 * ({@link #broadcastToPlayer}, from {@code ChunkMap.TrackedEntity.updatePlayer}); this one
 * says yes to its owner only, so nobody else's game even hears of it. Picking up is the
 * vanilla owner-only pickup ({@link ItemEntity#setTarget}, saved as {@code Owner}).
 * Hoppers and mobs don't ask either; what they take is still marked for the owner
 * ({@link QuestItems}), so useless to anyone else. Gone after five minutes, like any item.
 */
public class QuestItemEntity extends ItemEntity {

    public QuestItemEntity(EntityType<? extends QuestItemEntity> type, Level level) {
        super(type, level);
    }

    /** Drop {@code stack} where {@code source} is, for {@code owner} only, the way a mob's loot drops. */
    public static void drop(ServerLevel level, Entity source, ItemStack stack, UUID owner) {
        QuestItemEntity item = new QuestItemEntity(LorebenchEntities.QUEST_ITEM.get(), level);
        item.setPos(source.getX(), source.getY() + 0.5, source.getZ());
        item.setDeltaMovement(level.random.nextDouble() * 0.2 - 0.1, 0.2, level.random.nextDouble() * 0.2 - 0.1);
        item.setItem(stack);
        item.lifespan = stack.getEntityLifespan(level);
        item.setTarget(owner);
        item.setDefaultPickUpDelay();
        level.addFreshEntity(item);
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        UUID owner = getTarget();
        return owner != null && owner.equals(player.getUUID());
    }
}
