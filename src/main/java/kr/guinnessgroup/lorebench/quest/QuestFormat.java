/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.quest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Folders;
import kr.guinnessgroup.lorebench.Ids;
import kr.guinnessgroup.lorebench.Speech;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes the quest document (format 1):
 * <pre>{ "format": 1,
 *   "folders": [ { "id": "folder_2kq8d1xz", "name": "마을" }, { "id": "folder_9fm3a0pe", "name": "촌장", "parent": "folder_2kq8d1xz" } ],
 *   "quests": [ {
 *   "id": "quest_k3f9x2ma", "title": "밀 배달", "icon": "minecraft:wheat", "text": "...", "folder": "folder_9fm3a0pe",
 *   "giver": "npc_7ha2m0qe", "requires": [ "quest_p0a8s1dd" ],
 *   "lines": { "offer": [ "밀 10개만 구해다 주겠나?" ], "accepted": [ "부탁하네." ], "declined": [ "그런가…" ] },
 *   "supplies": [ { "item": "minecraft:wheat_seeds", "count": 5 } ],
 *   "stages": [ {
 *     "id": "stage_a1b2c3d4", "text": "촌장에게 밀 가져가기", "to": "npc_7ha2m0qe",
 *     "goals": [ { "item": "minecraft:wheat",   "count": 10 }, { "kill": "minecraft:wolf", "count": 3 },
 *                { "harvest": "minecraft:potatoes", "count": 5 }, { "breed": "minecraft:cow", "count": 2 },
 *                { "collect": "minecraft:amethyst_shard[custom_name='\"목걸이 조각\"']", "count": 3,
 *                  "from": "kill:minecraft:wolf", "chance": 0.5 } ],
 *     "wait": { "days": 1 },
 *     "gives": [ { "item": "minecraft:amethyst_shard[custom_name='\"고친 목걸이\"']", "count": 1, "quest": true } ],
 *     "lines": { "active": [ "아직 부족하구먼." ], "complete": [ "고맙네!" ],
 *                "handed": [ "내일 오게." ], "waiting": [ "아직 망치질 중일세." ], "ready": [ "다 됐네!" ] } } ],
 *   "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }</pre>
 * {@code folders}, a folder's {@code parent}, a quest's {@code icon}, {@code text}, {@code folder},
 * {@code giver}, {@code requires}, {@code lines} and {@code supplies}, and a stage's {@code to}, {@code goals},
 * {@code wait}, {@code gives} and {@code lines} are optional (no parent or folder = the top, no {@code to} = the giver,
 * no goals = just talk, no wait = go on right away; see docs/decisions/0009-quest-workbench.md, 0013-waiting.md and
 * 0015-quest-stages.md for the rest). A quest has one stage or more; stage ids are unique in the document. A goal
 * may say {@code "keep": true} (item and collect goals: only shown), a collect goal may leave out {@code from} (it
 * counts the quest's items an earlier stage drops or gives), and a stage's gift may say {@code "quest": true}.
 * Each of {@code lines} may instead be groups picked by condition ({@link Speech}, 0012). Required quests, and quests
 * the conditions name, must exist, and requirements never lead back to the quest. Beyond that this checks only the
 * shape; whether the items, entities, crops and NPCs exist is checked on publish, where the game's lists are available.
 */
public final class QuestFormat {

    public static final int VERSION = 1;

    public static final int MAX_COUNT = 9999;

    /** An item id with its namespace, e.g. {@code minecraft:wheat}. */
    public static final Pattern ITEM = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * An item as {@code /give} writes it: an id, optionally followed by components,
     * e.g. {@code minecraft:iron_sword[custom_name='"Blade"']}. Only the
     * shape is checked here; the game's item parser checks the rest on publish.
     */
    public static final Pattern ITEM_WITH_COMPONENTS = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+(\\[.*])?", Pattern.DOTALL);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private QuestFormat() {}

    public static QuestDoc read(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new DocumentException(List.of("quest document is not a JSON object"));
        }
        JsonElement format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()) {
            throw new DocumentException(List.of("quest document: missing 'format' number"));
        }
        int version = format.getAsInt();
        if (version > VERSION) {
            throw new DocumentException(List.of("quest document format " + version
                    + " is newer than this Lorebench supports (" + VERSION + ")"));
        }
        if (version != VERSION) {
            throw new DocumentException(List.of("quest document: unknown format " + version));
        }
        JsonElement arr = root.get("quests");
        if (arr == null || !arr.isJsonArray()) {
            throw new DocumentException(List.of("quest document: missing 'quests' list"));
        }

        List<String> errors = new ArrayList<>();
        List<Folders.Folder> folders = Folders.read(root.get("folders"), "quest document", errors);
        List<QuestDoc.Quest> quests = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> stageIds = new HashSet<>();
        for (JsonElement el : (JsonArray) arr) {
            if (!el.isJsonObject()) {
                errors.add("a quest is not an object");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = string(o, "id");
            if (!Ids.valid(Ids.QUEST, id)) {
                errors.add("quest id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.QUEST)));
                continue;
            }
            String title = string(o, "title");
            String where = Ids.named("quest", id, title);
            int before = errors.size();
            if (!ids.add(id)) {
                errors.add("duplicate quest id '" + id + "'");
            }
            if (title == null || title.isBlank()) {
                errors.add(where + ": missing 'title'");
            }
            String icon = optional(o, "icon");
            if (!icon.isEmpty() && !ITEM.matcher(icon).matches()) {
                errors.add(where + ": icon '" + icon + "' is not an item id like minecraft:wheat");
            }
            String text = string(o, "text");
            String folder = Folders.placement(o, folders, where, errors);
            for (String moved : List.of("goals", "wait", "receiver")) {
                if (o.has(moved)) {
                    errors.add(where + ": '" + moved + "' belongs in a stage now (\"stages\": [ { … } ], see 0015)");
                }
            }
            List<QuestDoc.Stage> stages = stages(o.get("stages"), where, stageIds, errors);
            List<QuestDoc.Stack> rewards = stacks(o, "rewards", ITEM_WITH_COMPONENTS, false, where, errors);
            List<QuestDoc.Stack> supplies = o.has("supplies")
                    ? stacks(o, "supplies", ITEM_WITH_COMPONENTS, false, where, errors) : List.of();
            QuestDoc.Flow flow = flow(o, where, errors);
            if (errors.size() == before) {
                quests.add(new QuestDoc.Quest(id, title.trim(), icon, text == null ? "" : text, rewards, supplies, folder,
                        flow, stages));
            }
        }
        checkRequires(quests, ids, errors);
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
        return new QuestDoc(folders, List.copyOf(quests));
    }

    /** Who offers a quest, what must be done first, and what the giver says about taking it. */
    private static QuestDoc.Flow flow(JsonObject o, String where, List<String> errors) {
        String giver = npc(o, "giver", where, errors);
        List<String> requires = new ArrayList<>();
        JsonElement r = o.get("requires");
        if (r != null && !r.isJsonArray()) {
            errors.add(where + ": 'requires' is not a list");
        } else if (r != null) {
            for (JsonElement e : r.getAsJsonArray()) {
                String q = (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString().trim() : null;
                if (!Ids.valid(Ids.QUEST, q)) {
                    errors.add(where + ": required quest " + (q == null ? "is not a quest id" : "'" + q + "' " + Ids.rule(Ids.QUEST)));
                } else if (requires.contains(q)) {
                    errors.add(where + ": requires '" + q + "' twice");
                } else {
                    requires.add(q);
                }
            }
        }
        List<Speech> lines = lines(o.get("lines"), LINE_KEYS, STAGE_LINE_KEYS, "a stage", where, errors);
        return new QuestDoc.Flow(giver, List.copyOf(requires), new QuestDoc.Lines(lines.get(0), lines.get(1), lines.get(2)));
    }

    /**
     * A quest's stages, in order: at least one. Each needs an id unique in the document
     * ({@code seen} holds those so far) and a line of text saying what to do.
     */
    private static List<QuestDoc.Stage> stages(JsonElement e, String quest, Set<String> seen, List<String> errors) {
        if (e == null || !e.isJsonArray() || e.getAsJsonArray().isEmpty()) {
            errors.add(quest + ": needs a 'stages' list with at least one stage");
            return List.of();
        }
        List<QuestDoc.Stage> out = new ArrayList<>();
        Set<String> brought = new HashSet<>(); // quest items earlier stages drop or give, by item id
        int n = 0;
        for (JsonElement s : e.getAsJsonArray()) {
            n++;
            if (!s.isJsonObject()) {
                errors.add(quest + ": stage " + n + " is not an object");
                continue;
            }
            JsonObject o = s.getAsJsonObject();
            String text = optional(o, "text");
            String where = quest + " stage " + n + (text.isEmpty() ? "" : " '" + text + "'");
            int before = errors.size();
            String id = string(o, "id");
            if (!Ids.valid(Ids.STAGE, id)) {
                errors.add(where + ": id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.STAGE)));
            } else if (!seen.add(id)) {
                errors.add(where + ": duplicate stage id '" + id + "'");
            }
            if (text.isEmpty()) {
                errors.add(where + ": missing 'text' (what to do, shown in the quest screen)");
            }
            String to = npc(o, "to", where, errors);
            List<QuestDoc.Goal> goals = o.has("goals") ? goals(o, where, errors) : List.of();
            for (QuestDoc.Goal g : goals) {
                if (g.kind() == QuestDoc.Goal.Kind.COLLECT && g.from().isEmpty() && !brought.contains(g.itemId())) {
                    errors.add(where + ": goal collect '" + g.target() + "' has no 'from', so an earlier stage must drop it"
                            + " (a collect goal with 'from') or give it (\"gives\" with \"quest\": true)");
                }
            }
            int waitDays = waitDays(o.get("wait"), where, errors);
            List<QuestDoc.Stack> gives = o.has("gives")
                    ? stacks(o, "gives", ITEM_WITH_COMPONENTS, true, where, errors) : List.of();
            List<Speech> lines = lines(o.get("lines"), STAGE_LINE_KEYS, LINE_KEYS, "the quest", where, errors);
            goals.stream().filter(g -> g.kind() == QuestDoc.Goal.Kind.COLLECT && !g.from().isEmpty())
                    .forEach(g -> brought.add(g.itemId()));
            gives.stream().filter(QuestDoc.Stack::quest).forEach(g -> brought.add(g.itemId()));
            if (errors.size() == before) {
                out.add(new QuestDoc.Stage(id, text, to, goals, waitDays, gives, new QuestDoc.StageLines(
                        lines.get(0), lines.get(1), lines.get(2), lines.get(3), lines.get(4))));
            }
        }
        return List.copyOf(out);
    }

    private static String npc(JsonObject o, String key, String where, List<String> errors) {
        String id = optional(o, key);
        if (!id.isEmpty() && !Ids.valid(Ids.NPC, id)) {
            errors.add(where + ": " + key + " '" + id + "' " + Ids.rule(Ids.NPC));
        }
        return id;
    }

    /** The keys of a quest's {@code lines}, in {@link QuestDoc.Lines#all()} order. Never rename: they are saved. */
    private static final List<String> LINE_KEYS = List.of("offer", "accepted", "declined");

    /** The keys of a stage's {@code lines}, in {@link QuestDoc.StageLines#all()} order. Never rename: they are saved. */
    private static final List<String> STAGE_LINE_KEYS = List.of("active", "complete", "handed", "waiting", "ready");

    /**
     * Lines under {@code keys}, in their order ({@link Speech#NONE} for those not written).
     *
     * @param elsewhere     the keys that belong to the other place (quest or stage), to say so
     * @param elsewhereName that place, for the message: "a stage" or "the quest"
     */
    private static List<Speech> lines(JsonElement e, List<String> keys, List<String> elsewhere, String elsewhereName,
                                      String where, List<String> errors) {
        List<Speech> out = new ArrayList<>();
        JsonObject o = new JsonObject();
        if (e != null && !e.isJsonObject()) {
            errors.add(where + ": 'lines' is not an object");
        } else if (e != null) {
            o = e.getAsJsonObject();
        }
        for (String key : o.keySet()) {
            if (elsewhere.contains(key)) {
                errors.add(where + ": lines '" + key + "' belong in " + elsewhereName
                        + " (here: " + String.join(", ", keys) + ")");
            } else if (!keys.contains(key)) {
                errors.add(where + ": unknown lines '" + key + "' (use " + String.join(", ", keys) + ")");
            }
        }
        for (String key : keys) {
            out.add(Speech.read(o.get(key), true, where + " " + key, errors));
        }
        return out;
    }

    /**
     * Required quests must exist, and following them must never come back to the quest
     * (it could never be offered). Quests the lines' conditions name must exist too.
     */
    private static void checkRequires(List<QuestDoc.Quest> quests, Set<String> ids, List<String> errors) {
        Map<String, List<String>> stages = new HashMap<>(); // a quest that did not read has its stages unknown (null)
        for (String id : ids) {
            stages.put(id, null);
        }
        for (QuestDoc.Quest q : quests) {
            stages.put(q.id(), q.stages().stream().map(QuestDoc.Stage::id).toList());
        }
        Map<String, List<String>> requires = new HashMap<>();
        for (QuestDoc.Quest q : quests) {
            requires.put(q.id(), q.flow().requires());
            String where = Ids.named("quest", q.id(), q.title());
            for (String r : q.flow().requires()) {
                if (r.equals(q.id())) {
                    errors.add(where + " requires itself");
                } else if (!ids.contains(r)) {
                    errors.add(where + " requires quest '" + r + "' that does not exist");
                }
            }
            namesQuestsThatExist(q.flow().lines().all(), LINE_KEYS, where, stages, errors);
            for (int n = 0; n < q.stages().size(); n++) {
                QuestDoc.Stage s = q.stages().get(n);
                namesQuestsThatExist(s.lines().all(), STAGE_LINE_KEYS, where + " stage " + (n + 1) + " '" + s.text() + "'",
                        stages, errors);
            }
        }
        for (QuestDoc.Quest q : quests) {
            if (!q.flow().requires().contains(q.id()) && leadsBack(q.id(), requires)) {
                errors.add(Ids.named("quest", q.id(), q.title()) + " ends up requiring itself");
            }
        }
    }

    private static void namesQuestsThatExist(List<Speech> lines, List<String> keys, String where,
                                             Map<String, List<String>> stages, List<String> errors) {
        for (int i = 0; i < keys.size(); i++) {
            for (String problem : lines.get(i).namingErrors(stages)) {
                errors.add(where + " " + keys.get(i) + ": " + problem);
            }
        }
    }

    private static boolean leadsBack(String start, Map<String, List<String>> requires) {
        Deque<String> todo = new ArrayDeque<>(requires.getOrDefault(start, List.of()));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            String at = todo.pop();
            if (at.equals(start)) {
                return true;
            }
            if (seen.add(at)) {
                todo.addAll(requires.getOrDefault(at, List.of()));
            }
        }
        return false;
    }

    /** What a counted goal's target looks like, for messages. */
    private static final Map<QuestDoc.Goal.Kind, String> TARGET_EXAMPLE = Map.of(
            QuestDoc.Goal.Kind.KILL, "an entity id like minecraft:wolf",
            QuestDoc.Goal.Kind.HARVEST, "a crop block id like minecraft:wheat",
            QuestDoc.Goal.Kind.BREED, "an entity id like minecraft:cow",
            QuestDoc.Goal.Kind.COLLECT, "an item like minecraft:amethyst_shard or minecraft:amethyst_shard[...]");

    /** Where a collect goal's item drops. Only kills for now (0012). */
    private static final Pattern FROM = Pattern.compile("kill:[a-z0-9_.-]+:[a-z0-9_./-]+");

    /**
     * Goals: each names exactly one kind: {@code item} (hand in; an item condition as
     * {@code /clear} reads it), {@code kill} (an entity type id), {@code harvest} (a
     * crop block id), {@code breed} (an entity type id) or {@code collect} (an item that drops, with {@code from}
     * and an optional {@code chance}, or without them one an earlier stage brings, 0015). Item and collect goals
     * may be {@code keep}: only shown. Counted goals of one kind must name different targets, because
     * progress is saved per kind and target; collect goals of one stage must name different items, because
     * the quest's items are told apart by item (stages go one at a time, so each may name the same item, 0015).
     */
    private static List<QuestDoc.Goal> goals(JsonObject o, String where, List<String> errors) {
        JsonElement e = o.get("goals");
        if (e == null || !e.isJsonArray()) {
            errors.add(where + ": 'goals' is not a list");
            return List.of();
        }
        List<QuestDoc.Goal> out = new ArrayList<>();
        Set<String> counted = new HashSet<>();
        for (JsonElement g : e.getAsJsonArray()) {
            if (!g.isJsonObject()) {
                errors.add(where + ": a goal is not an object");
                continue;
            }
            JsonObject go = g.getAsJsonObject();
            QuestDoc.Goal.Kind kind = null;
            int kinds = 0;
            for (QuestDoc.Goal.Kind k : QuestDoc.Goal.Kind.values()) {
                if (!optional(go, k.key).isEmpty()) {
                    kind = k;
                    kinds++;
                }
            }
            if (kinds != 1) {
                errors.add(where + ": a goal needs exactly one of 'item', 'kill', 'harvest', 'breed' or 'collect'");
                continue;
            }
            String target = optional(go, kind.key);
            // An item goal is a condition as /clear reads it (minecraft:wheat,
            // minecraft:iron_sword[custom_data={...}], #minecraft:logs ...); the
            // game's parser checks it on publish.
            if ((kind.counted() && !ITEM.matcher(target).matches())
                    || (kind == QuestDoc.Goal.Kind.COLLECT && !ITEM_WITH_COMPONENTS.matcher(target).matches())) {
                errors.add(where + ": goal " + kind.key + " '" + target + "' is not " + TARGET_EXAMPLE.get(kind));
                continue;
            }
            String from = optional(go, "from");
            double chance = 1;
            if (kind == QuestDoc.Goal.Kind.COLLECT) {
                if (!from.isEmpty() && !FROM.matcher(from).matches()) {
                    errors.add(where + ": goal collect '" + target + "' 'from' must be like kill:minecraft:wolf");
                    continue;
                }
                JsonElement c = go.get("chance");
                if (c != null && from.isEmpty()) {
                    errors.add(where + ": goal collect '" + target + "' has a 'chance' but no 'from' to drop from");
                    continue;
                }
                if (c != null) {
                    chance = c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber() ? c.getAsDouble() : -1;
                    if (!(chance > 0 && chance <= 1)) {
                        errors.add(where + ": goal collect '" + target + "' chance must be above 0 and at most 1");
                        continue;
                    }
                }
            } else if (go.has("from") || go.has("chance")) {
                errors.add(where + ": only collect goals have 'from' and 'chance'");
                continue;
            }
            int count = count(go.get("count"));
            if (count == 0) {
                errors.add(where + ": goal count of '" + target + "' must be a whole number from 1 to " + MAX_COUNT);
                continue;
            }
            if (kind.counted() && !counted.add(QuestDoc.Goal.progressKey(kind, target))) {
                errors.add(where + ": two " + kind.key + " goals for '" + target + "'; use one with the total count");
                continue;
            }
            JsonElement k = go.get("keep");
            if (k != null && !(k.isJsonPrimitive() && k.getAsJsonPrimitive().isBoolean())) {
                errors.add(where + ": goal '" + target + "' 'keep' must be true or false");
                continue;
            }
            boolean keep = k != null && k.getAsBoolean();
            if (keep && kind != QuestDoc.Goal.Kind.ITEM && kind != QuestDoc.Goal.Kind.COLLECT) {
                errors.add(where + ": only item and collect goals have 'keep' (nothing is handed in for " + kind.key + ")");
                continue;
            }
            QuestDoc.Goal goal = new QuestDoc.Goal(kind, target, count, from, chance, keep);
            if (kind == QuestDoc.Goal.Kind.COLLECT && !counted.add("collect:" + goal.itemId())) {
                errors.add(where + ": two collect goals for " + goal.itemId() + "; use a different item for each");
                continue;
            }
            out.add(goal);
        }
        return List.copyOf(out);
    }

    /**
     * The wait between handing in and the rewards: {@code {"days": 1}}, in game days (0013), or 0
     * when there is none. The unit is named so other kinds of wait can sit beside it later.
     */
    private static int waitDays(JsonElement e, String where, List<String> errors) {
        if (e == null) {
            return 0;
        }
        if (!e.isJsonObject() || !e.getAsJsonObject().keySet().equals(Set.of("days"))) {
            errors.add(where + ": 'wait' must be like { \"days\": 1 }");
            return 0;
        }
        int days = count(e.getAsJsonObject().get("days"));
        if (days == 0) {
            errors.add(where + ": wait days must be a whole number from 1 to " + MAX_COUNT);
        }
        return days;
    }

    /** A whole number from 1 to {@link #MAX_COUNT}, or 0 if it is not one. */
    private static int count(JsonElement c) {
        if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber()) {
            double d = c.getAsDouble();
            return (d == Math.rint(d) && d >= 1 && d <= MAX_COUNT) ? (int) d : 0;
        }
        return 0;
    }

    /** @param questItems whether an entry may say {@code "quest": true} (a stage's gifts, 0015) */
    private static List<QuestDoc.Stack> stacks(JsonObject o, String key, Pattern shape, boolean questItems, String where,
                                               List<String> errors) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            errors.add(where + ": missing '" + key + "' list");
            return List.of();
        }
        List<QuestDoc.Stack> out = new ArrayList<>();
        for (JsonElement s : e.getAsJsonArray()) {
            if (!s.isJsonObject()) {
                errors.add(where + ": an entry of '" + key + "' is not an object");
                continue;
            }
            String item = optional(s.getAsJsonObject(), "item");
            if (!shape.matcher(item).matches()) {
                errors.add(where + ": " + key + " item " + (item.isEmpty() ? "is missing"
                        : "'" + item + "' is not an item like minecraft:iron_sword or minecraft:iron_sword[...]"));
                continue;
            }
            int count = count(s.getAsJsonObject().get("count"));
            if (count == 0) {
                errors.add(where + ": " + key + " count of '" + item + "' must be a whole number from 1 to " + MAX_COUNT);
                continue;
            }
            JsonElement q = s.getAsJsonObject().get("quest");
            if (q != null && !(questItems && q.isJsonPrimitive() && q.getAsJsonPrimitive().isBoolean())) {
                errors.add(where + ": " + key + " item '" + item + "': " + (questItems ? "'quest' must be true or false"
                        : "only a stage's gives can be quest items"));
                continue;
            }
            out.add(new QuestDoc.Stack(item, count, q != null && q.getAsBoolean()));
        }
        return List.copyOf(out);
    }

    public static String write(QuestDoc doc) {
        JsonArray arr = new JsonArray();
        for (QuestDoc.Quest q : doc.quests()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", q.id());
            o.addProperty("title", q.title());
            if (!q.icon().isEmpty()) {
                o.addProperty("icon", q.icon());
            }
            if (!q.text().isEmpty()) {
                o.addProperty("text", q.text());
            }
            Folders.writePlacement(o, q.folder());
            writeFlow(o, q.flow());
            if (!q.supplies().isEmpty()) {
                o.add("supplies", writeStacks(q.supplies()));
            }
            JsonArray stages = new JsonArray();
            q.stages().forEach(s -> stages.add(writeStage(s)));
            o.add("stages", stages);
            o.add("rewards", writeStacks(q.rewards()));
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", VERSION);
        Folders.write(root, doc.folders());
        root.add("quests", arr);
        return GSON.toJson(root);
    }

    /** Writes only the parts that are set, so a quest without them looks as before. */
    private static void writeFlow(JsonObject o, QuestDoc.Flow flow) {
        if (!flow.giver().isEmpty()) {
            o.addProperty("giver", flow.giver());
        }
        if (!flow.requires().isEmpty()) {
            JsonArray requires = new JsonArray();
            flow.requires().forEach(requires::add);
            o.add("requires", requires);
        }
        writeLines(o, flow.lines().all(), LINE_KEYS);
    }

    /** A stage, with only the optional parts that are set. */
    private static JsonObject writeStage(QuestDoc.Stage s) {
        JsonObject o = new JsonObject();
        o.addProperty("id", s.id());
        o.addProperty("text", s.text());
        if (!s.to().isEmpty()) {
            o.addProperty("to", s.to());
        }
        if (!s.goals().isEmpty()) {
            JsonArray goals = new JsonArray();
            for (QuestDoc.Goal g : s.goals()) {
                JsonObject go = new JsonObject();
                go.addProperty(g.kind().key, g.target());
                go.add("count", new JsonPrimitive(g.count()));
                if (!g.from().isEmpty()) {
                    go.addProperty("from", g.from());
                    if (g.chance() < 1) {
                        go.addProperty("chance", g.chance());
                    }
                }
                if (g.keep()) {
                    go.addProperty("keep", true);
                }
                goals.add(go);
            }
            o.add("goals", goals);
        }
        if (s.waitDays() > 0) {
            JsonObject wait = new JsonObject();
            wait.add("days", new JsonPrimitive(s.waitDays()));
            o.add("wait", wait);
        }
        if (!s.gives().isEmpty()) {
            o.add("gives", writeStacks(s.gives()));
        }
        writeLines(o, s.lines().all(), STAGE_LINE_KEYS);
        return o;
    }

    private static void writeLines(JsonObject o, List<Speech> all, List<String> keys) {
        JsonObject lines = new JsonObject();
        for (int i = 0; i < keys.size(); i++) {
            if (!all.get(i).isEmpty()) {
                lines.add(keys.get(i), Speech.write(all.get(i)));
            }
        }
        if (lines.size() > 0) {
            o.add("lines", lines);
        }
    }

    private static JsonArray writeStacks(List<QuestDoc.Stack> stacks) {
        JsonArray arr = new JsonArray();
        for (QuestDoc.Stack s : stacks) {
            JsonObject o = new JsonObject();
            o.addProperty("item", s.item());
            o.add("count", new JsonPrimitive(s.count()));
            if (s.quest()) {
                o.addProperty("quest", true);
            }
            arr.add(o);
        }
        return arr;
    }

    /** An optional text field: trimmed, "" when absent. */
    private static String optional(JsonObject o, String key) {
        String s = string(o, key);
        return s == null ? "" : s.trim();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : null;
    }
}
