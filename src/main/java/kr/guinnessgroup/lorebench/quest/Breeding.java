/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.horse.SkeletonHorse;
import net.minecraft.world.entity.animal.horse.ZombieHorse;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The animals a breed goal can name: those whose baby is born as soon as two are fed
 * (the game's breeding, where NeoForge's {@code BabyEntitySpawnEvent} says who fed
 * them). Egg layers (turtles, frogs, sniffers: the baby hatches later, when who fed
 * the parents is gone) and villagers (not fed) are left for later. Some animals are
 * never born from breeding at all: parrots and polar bears take no food, skeleton and
 * zombie horses never mate (mules stay: a horse and a donkey make one).
 * See docs/decisions/0010-farming-goals.md.
 */
public final class Breeding {

    /** Entity types never change while the game runs, so each is looked at once. */
    private static final Map<EntityType<?>, Boolean> BREEDABLE = new ConcurrentHashMap<>();

    private Breeding() {}

    /**
     * Whether a breed goal may name this entity type. The game only tells what an
     * entity is by making one, so this makes one (never added to the world) in
     * {@code level}. Call on the server thread.
     */
    public static boolean breedable(EntityType<?> type, Level level) {
        return BREEDABLE.computeIfAbsent(type, t -> {
            if (t.getCategory() == MobCategory.MISC) {
                return false; // items, arrows, villagers, golems ...
            }
            try {
                Entity e = t.create(level);
                return e instanceof Animal && !(e instanceof Turtle || e instanceof Frog || e instanceof Sniffer
                        || e instanceof Parrot || e instanceof PolarBear
                        || e instanceof SkeletonHorse || e instanceof ZombieHorse);
            } catch (RuntimeException ex) {
                return false; // a mob that can't be made outside the world
            }
        });
    }

    /** Every animal in this game a breed goal may name, for the editor's list. Server thread. */
    public static List<EntityType<?>> all(Level level) {
        List<EntityType<?>> out = new ArrayList<>();
        for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
            if (breedable(type, level)) {
                out.add(type);
            }
        }
        return out;
    }
}
