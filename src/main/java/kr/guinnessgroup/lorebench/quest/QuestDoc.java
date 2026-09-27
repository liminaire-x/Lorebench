/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import kr.guinnessgroup.lorebench.Folders.Folder;
import kr.guinnessgroup.lorebench.Ids;
import kr.guinnessgroup.lorebench.Speech;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The saved quest document ({@code quests.json}): every quest, authored in the
 * editor. Who has which quest is a record, not part of this document.
 * See docs/decisions/0005-quests.md.
 *
 * @param folders how the editor groups quests; the game doesn't use them ({@link kr.guinnessgroup.lorebench.Folders})
 */
public record QuestDoc(List<Folder> folders, List<Quest> quests) {

    public static final QuestDoc EMPTY = new QuestDoc(List.of(), List.of());

    /**
     * @param id      stable; graphs and players' records refer to it
     * @param title   shown in the quest screen
     * @param icon    item id shown in the quest list, or "" for the first goal's item
     * @param text    the quest's story text, may be ""
     * @param goals   all must be met, shown in this order
     * @param waitDays game days between handing the goals in and taking the rewards, or 0 for none
     *                 (the rewards come right away); see docs/decisions/0013-waiting.md
     * @param rewards items given on completion
     * @param supplies items given once, the moment the quest becomes active (seeds to plant,
     *                 a letter to deliver); see docs/decisions/0010-farming-goals.md
     * @param folder  the folder id it sits in, or "" for the top
     * @param flow    who offers and receives it, what comes first, and what the NPCs say
     */
    public record Quest(String id, String title, String icon, String text, List<Goal> goals, int waitDays,
                        List<Stack> rewards, List<Stack> supplies, String folder, Flow flow) {}

    /**
     * How a quest runs through NPC dialogue. See docs/decisions/0009-quest-workbench.md.
     *
     * @param giver    the NPC id that offers it, or "" (then only graphs reveal it)
     * @param receiver the NPC id it is handed in to, or "" for the giver
     * @param requires quest ids that must all be done before it is offered
     * @param lines    what the NPCs say
     */
    public record Flow(String giver, String receiver, List<String> requires, Lines lines) {

        public static final Flow NONE = new Flow("", "", List.of(), Lines.NONE);

        /** The NPC it is handed in to: the receiver, or the giver when none is set. */
        public String handInTo() {
            return receiver.isEmpty() ? giver : receiver;
        }
    }

    /**
     * What the NPCs say about a quest, each lines shown one page at a time, or groups of
     * them picked by condition ({@link Speech}).
     *
     * @param offer    when the giver offers it
     * @param accepted right after the player accepts it (0010)
     * @param declined right after the player turns it down (0012)
     * @param active   when the player talks to the receiver while it is in progress
     * @param complete when the player hands it in
     */
    public record Lines(Speech offer, Speech accepted, Speech declined, Speech active, Speech complete) {

        public static final Lines NONE = new Lines(Speech.NONE, Speech.NONE, Speech.NONE, Speech.NONE, Speech.NONE);

        /** In the order of their keys in the document. */
        public List<Speech> all() {
            return List.of(offer, accepted, declined, active, complete);
        }
    }

    /**
     * Some number of one item, e.g. {@code minecraft:emerald} × 5. A reward item may be
     * written as {@code /give} writes it, with components (name, enchantments, data
     * from other mods): {@code minecraft:iron_sword[custom_name=...]}.
     */
    public record Stack(String item, int count) {}

    /**
     * One thing a quest asks for. Saved as {@code {"item": "minecraft:wheat", "count": 10}}
     * (hand in: an item condition as {@code /clear} reads it; listed components must
     * match, others are ignored), or as something the player does while the quest is
     * active, counted in their progress record: {@code {"kill": "minecraft:wolf", "count": 3}}
     * (an entity type id), {@code {"harvest": "minecraft:wheat", "count": 10}} (a crop
     * block id; fully grown ones, one per plant) or {@code {"breed": "minecraft:cow", "count": 2}}
     * (an entity type id; babies born). See docs/decisions/0010-farming-goals.md. Or a
     * quest item that drops only for the player on the quest:
     * {@code {"collect": "minecraft:amethyst_shard[...]", "count": 3, "from": "kill:minecraft:wolf", "chance": 0.5}}
     * (an item as {@code /give} writes it; see docs/decisions/0012-lost-necklace.md).
     *
     * @param target an item condition, an entity type id, a block id or an item, depending on {@code kind}
     * @param from   a collect goal's source, {@code <kind>:<target>} like a progress key ({@code kill:minecraft:wolf}); else ""
     * @param chance a collect goal's chance to drop per source, above 0 up to 1; else 1
     */
    public record Goal(Kind kind, String target, int count, String from, double chance) {

        public Goal(Kind kind, String target, int count) {
            this(kind, target, count, "", 1);
        }

        public enum Kind {
            ITEM("item"),
            KILL("kill"),
            HARVEST("harvest"),
            BREED("breed"),
            COLLECT("collect");

            /** The key that names the target in the saved goal and in progress records. Never rename. */
            public final String key;

            Kind(String key) {
                this.key = key;
            }

            /** Whether this goal counts something the player does (kept in their progress record). */
            public boolean counted() {
                return this != ITEM && this != COLLECT;
            }
        }

        public static Goal item(String item, int count) {
            return new Goal(Kind.ITEM, item, count);
        }

        public static Goal kill(String entity, int count) {
            return new Goal(Kind.KILL, entity, count);
        }

        public static Goal harvest(String crop, int count) {
            return new Goal(Kind.HARVEST, crop, count);
        }

        public static Goal breed(String entity, int count) {
            return new Goal(Kind.BREED, entity, count);
        }

        public static Goal collect(String item, int count, String from, double chance) {
            return new Goal(Kind.COLLECT, item, count, from, chance);
        }

        /** The id a collect goal's item has, without components: {@code minecraft:amethyst_shard}. */
        public String itemId() {
            int bracket = target.indexOf('[');
            return bracket < 0 ? target : target.substring(0, bracket);
        }

        /** Where a counted goal's count is kept in the progress record, e.g. {@code kill:minecraft:wolf}. */
        public String progressKey() {
            return progressKey(kind, target);
        }

        public static String progressKey(Kind kind, String target) {
            return kind.key + ":" + target;
        }
    }

    /** A problem for each quest whose giver or receiver is not one of {@code npcIds}. */
    public List<String> npcErrors(Set<String> npcIds) {
        List<String> errors = new ArrayList<>();
        for (Quest q : quests) {
            for (String npc : List.of(q.flow().giver(), q.flow().receiver())) {
                if (!npc.isEmpty() && !npcIds.contains(npc)) {
                    errors.add(Ids.named("quest", q.id(), q.title()) + ": NPC '" + npc + "' does not exist");
                }
            }
        }
        return errors;
    }

    public Quest find(String id) {
        for (Quest q : quests) {
            if (q.id().equals(id)) {
                return q;
            }
        }
        return null;
    }
}
