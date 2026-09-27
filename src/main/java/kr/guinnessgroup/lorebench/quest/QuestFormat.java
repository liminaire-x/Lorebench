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
import kr.guinnessgroup.lorebench.DialogueLines;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Folders;
import kr.guinnessgroup.lorebench.Ids;

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
 *   "giver": "npc_7ha2m0qe", "receiver": "npc_7ha2m0qe", "requires": [ "quest_p0a8s1dd" ],
 *   "lines": { "offer": [ "밀 10개만 구해다 주겠나?" ], "accepted": [ "부탁하네." ], "active": [ "아직 부족하구먼." ],
 *              "complete": [ "고맙네!" ] },
 *   "supplies": [ { "item": "minecraft:wheat_seeds", "count": 5 } ],
 *   "goals":   [ { "item": "minecraft:wheat",   "count": 10 }, { "kill": "minecraft:wolf", "count": 3 },
 *                { "harvest": "minecraft:potatoes", "count": 5 }, { "breed": "minecraft:cow", "count": 2 } ],
 *   "rewards": [ { "item": "minecraft:emerald", "count": 5 } ] } ] }</pre>
 * {@code folders}, a folder's {@code parent}, and a quest's {@code icon}, {@code text}, {@code folder},
 * {@code giver}, {@code receiver}, {@code requires}, {@code lines} and {@code supplies} are optional (no parent or
 * folder = the top; see docs/decisions/0009-quest-workbench.md for the rest). Required quests must
 * exist and never lead back to the quest. Beyond that this checks only the shape; whether the items,
 * entities, crops and NPCs exist is checked on publish, where the game's lists are available.
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
            List<QuestDoc.Goal> goals = goals(o, where, errors);
            List<QuestDoc.Stack> rewards = stacks(o, "rewards", ITEM_WITH_COMPONENTS, where, errors);
            List<QuestDoc.Stack> supplies = o.has("supplies")
                    ? stacks(o, "supplies", ITEM_WITH_COMPONENTS, where, errors) : List.of();
            QuestDoc.Flow flow = flow(o, where, errors);
            if (errors.size() == before) {
                quests.add(new QuestDoc.Quest(id, title.trim(), icon, text == null ? "" : text, goals, rewards, supplies, folder, flow));
            }
        }
        checkRequires(quests, ids, errors);
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
        return new QuestDoc(folders, List.copyOf(quests));
    }

    /** Who offers and receives a quest, what must be done first, and what the NPCs say. */
    private static QuestDoc.Flow flow(JsonObject o, String where, List<String> errors) {
        String giver = npc(o, "giver", where, errors);
        String receiver = npc(o, "receiver", where, errors);
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
        return new QuestDoc.Flow(giver, receiver, List.copyOf(requires), lines(o.get("lines"), where, errors));
    }

    private static String npc(JsonObject o, String key, String where, List<String> errors) {
        String id = optional(o, key);
        if (!id.isEmpty() && !Ids.valid(Ids.NPC, id)) {
            errors.add(where + ": " + key + " '" + id + "' " + Ids.rule(Ids.NPC));
        }
        return id;
    }

    /** The keys of {@code lines}. Never rename: they are saved. */
    private static final List<String> LINE_KEYS = List.of("offer", "accepted", "active", "complete");

    private static QuestDoc.Lines lines(JsonElement e, String where, List<String> errors) {
        if (e == null) {
            return QuestDoc.Lines.NONE;
        }
        if (!e.isJsonObject()) {
            errors.add(where + ": 'lines' is not an object");
            return QuestDoc.Lines.NONE;
        }
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!LINE_KEYS.contains(key)) {
                errors.add(where + ": unknown lines '" + key + "' (use " + String.join(", ", LINE_KEYS) + ")");
            }
        }
        return new QuestDoc.Lines(
                DialogueLines.read(o.get("offer"), where + " offer", errors),
                DialogueLines.read(o.get("accepted"), where + " accepted", errors),
                DialogueLines.read(o.get("active"), where + " active", errors),
                DialogueLines.read(o.get("complete"), where + " complete", errors));
    }

    /**
     * Required quests must exist, and following them must never come back to the quest
     * (it could never be offered).
     */
    private static void checkRequires(List<QuestDoc.Quest> quests, Set<String> ids, List<String> errors) {
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
        }
        for (QuestDoc.Quest q : quests) {
            if (!q.flow().requires().contains(q.id()) && leadsBack(q.id(), requires)) {
                errors.add(Ids.named("quest", q.id(), q.title()) + " ends up requiring itself");
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
            QuestDoc.Goal.Kind.BREED, "an entity id like minecraft:cow");

    /**
     * Goals: each names exactly one kind: {@code item} (hand in; an item condition as
     * {@code /clear} reads it), {@code kill} (an entity type id), {@code harvest} (a
     * crop block id) or {@code breed} (an entity type id). Counted goals of one kind must name different targets, because
     * progress is saved per kind and target.
     */
    private static List<QuestDoc.Goal> goals(JsonObject o, String where, List<String> errors) {
        JsonElement e = o.get("goals");
        if (e == null || !e.isJsonArray()) {
            errors.add(where + ": missing 'goals' list");
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
                errors.add(where + ": a goal needs exactly one of 'item', 'kill', 'harvest' or 'breed'");
                continue;
            }
            String target = optional(go, kind.key);
            // An item goal is a condition as /clear reads it (minecraft:wheat,
            // minecraft:iron_sword[custom_data={...}], #minecraft:logs ...); the
            // game's parser checks it on publish.
            if (kind.counted() && !ITEM.matcher(target).matches()) {
                errors.add(where + ": goal " + kind.key + " '" + target + "' is not " + TARGET_EXAMPLE.get(kind));
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
            out.add(new QuestDoc.Goal(kind, target, count));
        }
        return List.copyOf(out);
    }

    /** A whole number from 1 to {@link #MAX_COUNT}, or 0 if it is not one. */
    private static int count(JsonElement c) {
        if (c != null && c.isJsonPrimitive() && c.getAsJsonPrimitive().isNumber()) {
            double d = c.getAsDouble();
            return (d == Math.rint(d) && d >= 1 && d <= MAX_COUNT) ? (int) d : 0;
        }
        return 0;
    }

    private static List<QuestDoc.Stack> stacks(JsonObject o, String key, Pattern shape, String where, List<String> errors) {
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
            out.add(new QuestDoc.Stack(item, count));
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
            JsonArray goals = new JsonArray();
            for (QuestDoc.Goal g : q.goals()) {
                JsonObject go = new JsonObject();
                go.addProperty(g.kind().key, g.target());
                go.add("count", new JsonPrimitive(g.count()));
                goals.add(go);
            }
            o.add("goals", goals);
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
        if (!flow.receiver().isEmpty()) {
            o.addProperty("receiver", flow.receiver());
        }
        if (!flow.requires().isEmpty()) {
            JsonArray requires = new JsonArray();
            flow.requires().forEach(requires::add);
            o.add("requires", requires);
        }
        JsonObject lines = new JsonObject();
        QuestDoc.Lines l = flow.lines();
        List<List<DialogueLines.Line>> all = List.of(l.offer(), l.accepted(), l.active(), l.complete());
        for (int i = 0; i < LINE_KEYS.size(); i++) {
            if (!all.get(i).isEmpty()) {
                lines.add(LINE_KEYS.get(i), DialogueLines.write(all.get(i)));
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
