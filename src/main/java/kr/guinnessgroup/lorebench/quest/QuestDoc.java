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
import java.util.LinkedHashSet;
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
     * What happens once (offer, refusal, rewards) and the stages the player goes through in
     * order. See docs/decisions/0015-quest-stages.md.
     *
     * @param id      stable; graphs and players' records refer to it
     * @param title   shown in the quest screen
     * @param icon    item id shown in the quest list, or "" for the first stage's first goal's item
     * @param text    the quest's story text, may be ""
     * @param rewards items given when the last stage is done
     * @param supplies items given once, the moment the quest becomes active (seeds to plant,
     *                 a letter to deliver); see docs/decisions/0010-farming-goals.md
     * @param folder  the folder id it sits in, or "" for the top
     * @param flow    who offers it, what comes first, and what the giver says
     * @param stages  one or more, in order. On a client, only those up to the player's own
     *                (see {@link #upTo})
     */
    public record Quest(String id, String title, String icon, String text, List<Stack> rewards, List<Stack> supplies,
                        String folder, Flow flow, List<Stage> stages) {

        /** The stage with this id, or {@code null} if the quest has none (it was removed). */
        public Stage stage(String stageId) {
            for (Stage s : stages) {
                if (s.id().equals(stageId)) {
                    return s;
                }
            }
            return null;
        }

        /** The stage after this one, or {@code null} if it is the last. */
        public Stage after(Stage stage) {
            int i = stages.indexOf(stage);
            return i < 0 || i + 1 >= stages.size() ? null : stages.get(i + 1);
        }

        /** The NPC a stage is done with: its own, or the giver when it names none. */
        public String npcOf(Stage stage) {
            return stage.to().isEmpty() ? flow.giver() : stage.to();
        }

        /**
         * What a player's client may see: the stages up to the one at {@code last} (never those after it,
         * so the story ahead never reaches them), with the rewards only when {@code rewards} is on.
         */
        public Quest upTo(int last, boolean rewards) {
            return new Quest(id, title, icon, text, rewards ? this.rewards : List.of(), supplies, folder, flow,
                    List.copyOf(stages.subList(0, Math.clamp(last + 1, 0, stages.size()))));
        }

        /** On a client: the stage the player is on (the last one sent), or {@code null} if none was sent. */
        public Stage current() {
            return stages.isEmpty() ? null : stages.getLast();
        }
    }

    /**
     * One step of a quest: a thing to do and whom to see about it (0015).
     *
     * @param id       stable; players' records name the stage they are on by it
     * @param text     what to do, a line in the quest screen
     * @param to       the NPC id it is done with, or "" for the giver
     * @param goals    all must be met, shown in this order; none = just talk to the NPC
     * @param waitDays game days between handing the goals in and going on, or 0 for none;
     *                 see docs/decisions/0013-waiting.md
     * @param gives    items given when the stage is done (after its wait), before the next one; a quest item
     *                 ({@link Stack#quest}) is marked for the player like dropped ones (0012). Never sent to clients
     * @param lines    what the NPC says during this stage
     */
    public record Stage(String id, String text, String to, List<Goal> goals, int waitDays, List<Stack> gives,
                        StageLines lines) {}

    /**
     * How a quest is offered. See docs/decisions/0009-quest-workbench.md.
     *
     * @param giver    the NPC id that offers it, or "" (then only graphs reveal it)
     * @param requires quest ids that must all be done before it is offered
     * @param lines    what the giver says about taking it
     */
    public record Flow(String giver, List<String> requires, Lines lines) {

        public static final Flow NONE = new Flow("", List.of(), Lines.NONE);
    }

    /**
     * What the giver says about taking a quest, each lines shown one page at a time, or
     * groups of them picked by condition ({@link Speech}).
     *
     * @param offer    when the giver offers it
     * @param accepted right after the player accepts it (0010)
     * @param declined right after the player turns it down (0012)
     */
    public record Lines(Speech offer, Speech accepted, Speech declined) {

        public static final Lines NONE = new Lines(Speech.NONE, Speech.NONE, Speech.NONE);

        /** In the order of their keys in the document. */
        public List<Speech> all() {
            return List.of(offer, accepted, declined);
        }
    }

    /**
     * What a stage's NPC says during it (0015).
     *
     * @param active   when the player talks to the NPC before the goals are met
     * @param complete when the player hands the goals in (or, with none, just talks to the NPC)
     * @param handed   right after handing in (0015 widened 0013's "to wait" to every stage)
     * @param waiting  when the player talks to the NPC while waiting (0013)
     * @param ready    when the wait is over, before the player takes what comes of it (0013)
     */
    public record StageLines(Speech active, Speech complete, Speech handed, Speech waiting, Speech ready) {

        public static final StageLines NONE = new StageLines(Speech.NONE, Speech.NONE, Speech.NONE, Speech.NONE, Speech.NONE);

        /** In the order of their keys in the document. */
        public List<Speech> all() {
            return List.of(active, complete, handed, waiting, ready);
        }
    }

    /**
     * Some number of one item, e.g. {@code minecraft:emerald} × 5. A reward item may be
     * written as {@code /give} writes it, with components (name, enchantments, data
     * from other mods): {@code minecraft:iron_sword[custom_name=...]}.
     *
     * @param quest a stage's gift that is this quest's own item, marked for the player (0015); else false
     */
    public record Stack(String item, int count, boolean quest) {

        public Stack(String item, int count) {
            this(item, count, false);
        }

        /** The item's id without components: {@code minecraft:amethyst_shard}. */
        public String itemId() {
            return idOf(item);
        }
    }

    /** An item's id without its components: {@code minecraft:amethyst_shard[...]} → {@code minecraft:amethyst_shard}. */
    static String idOf(String item) {
        int bracket = item.indexOf('[');
        return bracket < 0 ? item : item.substring(0, bracket);
    }

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
     * (an item as {@code /give} writes it; see docs/decisions/0012-lost-necklace.md), or without {@code from},
     * one the player already has from an earlier stage (0015). An item or collect goal with
     * {@code "keep": true} is only shown: handing in leaves it with the player (0015).
     *
     * @param target an item condition, an entity type id, a block id or an item, depending on {@code kind}
     * @param from   a collect goal's source, {@code <kind>:<target>} like a progress key ({@code kill:minecraft:wolf}),
     *               or "" (a collect goal's items come from an earlier stage and never drop)
     * @param chance a collect goal's chance to drop per source, above 0 up to 1; else 1
     * @param keep   handing in leaves the items with the player (an item or collect goal only)
     */
    public record Goal(Kind kind, String target, int count, String from, double chance, boolean keep) {

        public Goal(Kind kind, String target, int count) {
            this(kind, target, count, "", 1, false);
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
            return new Goal(Kind.COLLECT, item, count, from, chance, false);
        }

        /** This goal, only shown: handing in leaves its items with the player. */
        public Goal kept() {
            return new Goal(kind, target, count, from, chance, true);
        }

        /** Whether handing the stage in takes this goal's items from the player. */
        public boolean takes() {
            return (kind == Kind.ITEM || kind == Kind.COLLECT) && !keep;
        }

        /** The id a collect goal's item has, without components: {@code minecraft:amethyst_shard}. */
        public String itemId() {
            return idOf(target);
        }

        /** Where a counted goal's count is kept in the progress record, e.g. {@code kill:minecraft:wolf}. */
        public String progressKey() {
            return progressKey(kind, target);
        }

        public static String progressKey(Kind kind, String target) {
            return kind.key + ":" + target;
        }
    }

    /** A problem for each giver or stage NPC that is not one of {@code npcIds}. */
    public List<String> npcErrors(Set<String> npcIds) {
        List<String> errors = new ArrayList<>();
        for (Quest q : quests) {
            List<String> npcs = new ArrayList<>(List.of(q.flow().giver()));
            q.stages().forEach(s -> npcs.add(s.to()));
            for (String npc : new LinkedHashSet<>(npcs)) {
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
