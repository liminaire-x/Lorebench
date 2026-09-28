/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import kr.guinnessgroup.lorebench.DialogueLines.Line;
import kr.guinnessgroup.lorebench.quest.QuestState;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What an NPC says in one place (a quest's offer, an NPC's greeting ...): lines
 * ({@link DialogueLines}), or groups of lines of which the first whose {@code when}
 * holds for the player is said (if / else if / else). A group without {@code when}
 * always holds.
 * <pre>[ { "when": { "timesDeclined": { "min": 5 } }, "lines": [ "…자네, 일부러 그러는 거지?" ] },
 *   { "when": { "questState": { "quest_necklace": "done" } }, "lines": [ "목걸이 덕에 딸이 다시 웃는다네." ] },
 *   { "when": { "stage": { "quest_necklace": "stage_e5f6g7h8" } }, "lines": [ "대장장이에게는 가 봤나?" ] },
 *   { "lines": [ "오, 자네 왔군." ] } ]</pre>
 * Plain lines are one group without {@code when} and are written as before. Numbers are
 * written as Minecraft writes ranges: {@code 5} (exactly), {@code { "min": 1 }}, {@code { "max": 2 }}.
 * The server picks the lines, so conditions never reach the client.
 * See docs/decisions/0012-lost-necklace.md ({@code stage}: "단계 조건").
 */
public record Speech(List<Group> groups) {

    public static final Speech NONE = new Speech(List.of());

    /** Condition names. Never rename: they are saved. */
    public static final String TIMES_DECLINED = "timesDeclined";
    public static final String QUEST_STATE = "questState";
    public static final String STAGE = "stage";

    private static final Set<String> GROUP_KEYS = Set.of("when", "lines");
    private static final Set<String> RANGE_KEYS = Set.of("min", "max");

    /** Some lines, said when {@code when} holds. */
    public record Group(When when, List<Line> lines) {}

    /**
     * Everything in it must hold.
     *
     * @param timesDeclined how many times the player turned the quest down, or null for any
     * @param questState    quest id → the state it must be in for the player, in written order
     * @param stage         quest id → the stage the player must be on, in written order
     */
    public record When(Range timesDeclined, Map<String, QuestState> questState, Map<String, String> stage) {

        public static final When ALWAYS = new When(null, Map.of(), Map.of());

        public boolean always() {
            return timesDeclined == null && questState.isEmpty() && stage.isEmpty();
        }

        public boolean holds(Facts facts) {
            if (timesDeclined != null && !timesDeclined.contains(facts.timesDeclined())) {
                return false;
            }
            for (Map.Entry<String, QuestState> e : questState.entrySet()) {
                if (facts.questState(e.getKey()) != e.getValue()) {
                    return false;
                }
            }
            for (Map.Entry<String, String> e : stage.entrySet()) {
                if (!e.getValue().equals(facts.stage(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
    }

    /** From {@code min} to {@code max}, both included; null = no bound. */
    public record Range(Integer min, Integer max) {

        public static Range exactly(int n) {
            return new Range(n, n);
        }

        public boolean contains(int n) {
            return (min == null || n >= min) && (max == null || n <= max);
        }
    }

    /** What a condition asks about the player talking. */
    public interface Facts {
        /** How many times they turned this quest down (asked only in a quest's lines). */
        int timesDeclined();

        /** Where they are with a quest (READY when an active quest's goals are met). */
        QuestState questState(String questId);

        /** The id of the stage they are on, whatever its state; null when they are not on the quest (not taken, done). */
        String stage(String questId);
    }

    /** Lines said always; none = {@link #NONE}. */
    public static Speech of(List<Line> lines) {
        return lines.isEmpty() ? NONE : new Speech(List.of(new Group(When.ALWAYS, List.copyOf(lines))));
    }

    /** Plain text lines said always, e.g. for tests. */
    public static Speech text(String... lines) {
        return of(DialogueLines.text(lines));
    }

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    /** The lines of the first group that holds for the player; none if no group does. */
    public List<Line> pick(Facts facts) {
        for (Group g : groups) {
            if (g.when().holds(facts)) {
                return g.lines();
            }
        }
        return List.of();
    }

    /** Every quest a condition names, to check they exist. */
    public Set<String> questsNamed() {
        Set<String> out = new LinkedHashSet<>();
        for (Group g : groups) {
            out.addAll(g.when().questState().keySet());
            out.addAll(g.when().stage().keySet());
        }
        return out;
    }

    /** Quest id → every stage a condition names in it, to check the quest has them. */
    public Map<String, Set<String>> stagesNamed() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Group g : groups) {
            g.when().stage().forEach((quest, stage) -> out.computeIfAbsent(quest, k -> new LinkedHashSet<>()).add(stage));
        }
        return out;
    }

    /**
     * What is wrong with the quests and stages the conditions name.
     *
     * @param stages quest id → its stage ids (null when not known), for every quest there is
     */
    public List<String> namingErrors(Map<String, ? extends Collection<String>> stages) {
        List<String> errors = new ArrayList<>();
        for (String quest : questsNamed()) {
            if (!stages.containsKey(quest)) {
                errors.add("quest '" + quest + "' does not exist");
            }
        }
        stagesNamed().forEach((quest, named) -> {
            Collection<String> has = stages.get(quest);
            for (String stage : named) {
                if (has != null && !has.contains(stage)) {
                    errors.add("quest '" + quest + "' has no stage '" + stage + "'");
                }
            }
        });
        return errors;
    }

    /**
     * Reads what an NPC says (absent = nothing).
     *
     * @param inQuest whether this belongs to a quest, where {@code timesDeclined} means that quest
     * @param where   names it in error messages, e.g. "quest '늑대 사냥' (quest_a) offer"
     */
    public static Speech read(JsonElement e, boolean inQuest, String where, List<String> errors) {
        if (e == null) {
            return NONE;
        }
        if (!e.isJsonArray()) {
            errors.add(where + ": lines must be a list");
            return NONE;
        }
        JsonArray arr = e.getAsJsonArray();
        int groupCount = 0;
        for (JsonElement el : arr) {
            if (isGroup(el)) {
                groupCount++;
            }
        }
        if (groupCount == 0) {
            return of(DialogueLines.read(e, where, errors));
        }
        if (groupCount != arr.size()) {
            errors.add(where + ": write either all lines or all groups ({ \"when\": …, \"lines\": […] }), not both");
            return NONE;
        }
        List<Group> groups = new ArrayList<>();
        boolean always = false;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            for (String key : o.keySet()) {
                if (!GROUP_KEYS.contains(key)) {
                    errors.add(where + ": unknown '" + key + "' in a group (use when, lines)");
                }
            }
            if (always) {
                errors.add(where + ": a group after one without 'when' is never said");
            }
            When when = o.has("when") ? readWhen(o.get("when"), inQuest, where, errors) : When.ALWAYS;
            always |= when.always();
            groups.add(new Group(when, DialogueLines.read(o.get("lines"), where, errors)));
        }
        if (groups.size() == 1 && groups.get(0).when().always() && groups.get(0).lines().isEmpty()) {
            return NONE;
        }
        return new Speech(List.copyOf(groups));
    }

    private static boolean isGroup(JsonElement e) {
        return e.isJsonObject() && e.getAsJsonObject().has("lines");
    }

    private static When readWhen(JsonElement e, boolean inQuest, String where, List<String> errors) {
        String known = (inQuest ? TIMES_DECLINED + ", " : "") + QUEST_STATE + ", " + STAGE;
        if (e == null || !e.isJsonObject() || e.getAsJsonObject().size() == 0) {
            errors.add(where + ": 'when' must name at least one condition (" + known + "), or be left out to always say these");
            return When.ALWAYS;
        }
        Range times = null;
        Map<String, QuestState> states = new LinkedHashMap<>();
        Map<String, String> stages = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> c : e.getAsJsonObject().entrySet()) {
            switch (c.getKey()) {
                case TIMES_DECLINED -> {
                    if (inQuest) {
                        times = readRange(c.getValue(), where + " " + TIMES_DECLINED, errors);
                    } else {
                        errors.add(where + ": " + TIMES_DECLINED + " only works in a quest's lines");
                    }
                }
                case QUEST_STATE -> readStates(c.getValue(), where, states, errors);
                case STAGE -> readStages(c.getValue(), where, stages, errors);
                default -> errors.add(where + ": unknown condition '" + c.getKey() + "' (use " + known + ")");
            }
        }
        return new When(times, Collections.unmodifiableMap(states), Collections.unmodifiableMap(stages));
    }

    private static Range readRange(JsonElement e, String where, List<String> errors) {
        if (isCount(e)) {
            return Range.exactly(e.getAsInt());
        }
        if (e != null && e.isJsonObject() && e.getAsJsonObject().size() > 0) {
            JsonObject o = e.getAsJsonObject();
            boolean ok = o.keySet().stream().allMatch(RANGE_KEYS::contains)
                    && (!o.has("min") || isCount(o.get("min"))) && (!o.has("max") || isCount(o.get("max")));
            if (ok) {
                Integer min = o.has("min") ? o.get("min").getAsInt() : null;
                Integer max = o.has("max") ? o.get("max").getAsInt() : null;
                if (min == null || max == null || min <= max) {
                    return new Range(min, max);
                }
            }
        }
        errors.add(where + " must be a count like 5, { \"min\": 1 } or { \"min\": 1, \"max\": 3 }");
        return null;
    }

    private static boolean isCount(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        double d = e.getAsDouble();
        return d >= 0 && d == Math.floor(d) && d <= Integer.MAX_VALUE;
    }

    private static void readStates(JsonElement e, String where, Map<String, QuestState> into, List<String> errors) {
        if (e == null || !e.isJsonObject() || e.getAsJsonObject().size() == 0) {
            errors.add(where + ": " + QUEST_STATE + " must name quests, like { \"quest_a\": \"done\" }");
            return;
        }
        for (Map.Entry<String, JsonElement> s : e.getAsJsonObject().entrySet()) {
            QuestState state = null;
            JsonElement v = s.getValue();
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                for (QuestState q : QuestState.values()) {
                    if (q.out.equals(v.getAsString())) {
                        state = q;
                    }
                }
            }
            if (!Ids.valid(Ids.QUEST, s.getKey())) {
                errors.add(where + ": " + QUEST_STATE + " quest '" + s.getKey() + "' " + Ids.rule(Ids.QUEST));
            } else if (state == null) {
                errors.add(where + ": " + QUEST_STATE + " of '" + s.getKey() + "' must be hidden, active, waiting, ready or done");
            } else {
                into.put(s.getKey(), state);
            }
        }
    }

    private static void readStages(JsonElement e, String where, Map<String, String> into, List<String> errors) {
        if (e == null || !e.isJsonObject() || e.getAsJsonObject().size() == 0) {
            errors.add(where + ": " + STAGE + " must name a quest and its stage, like { \"quest_a\": \"stage_b\" }");
            return;
        }
        for (Map.Entry<String, JsonElement> s : e.getAsJsonObject().entrySet()) {
            JsonElement v = s.getValue();
            String stage = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : null;
            if (!Ids.valid(Ids.QUEST, s.getKey())) {
                errors.add(where + ": " + STAGE + " quest '" + s.getKey() + "' " + Ids.rule(Ids.QUEST));
            } else if (!Ids.valid(Ids.STAGE, stage)) {
                errors.add(where + ": " + STAGE + " of '" + s.getKey() + "' " + Ids.rule(Ids.STAGE));
            } else {
                into.put(s.getKey(), stage);
            }
        }
    }

    /** Plain lines when said always, as before; groups otherwise. */
    public static JsonArray write(Speech speech) {
        List<Group> groups = speech.groups();
        if (groups.size() == 1 && groups.get(0).when().always()) {
            return DialogueLines.write(groups.get(0).lines());
        }
        JsonArray arr = new JsonArray();
        for (Group g : groups) {
            JsonObject o = new JsonObject();
            if (!g.when().always()) {
                o.add("when", writeWhen(g.when()));
            }
            o.add("lines", DialogueLines.write(g.lines()));
            arr.add(o);
        }
        return arr;
    }

    private static JsonObject writeWhen(When when) {
        JsonObject o = new JsonObject();
        Range r = when.timesDeclined();
        if (r != null) {
            if (r.min() != null && r.min().equals(r.max())) {
                o.add(TIMES_DECLINED, new JsonPrimitive(r.min()));
            } else {
                JsonObject range = new JsonObject();
                if (r.min() != null) {
                    range.addProperty("min", r.min());
                }
                if (r.max() != null) {
                    range.addProperty("max", r.max());
                }
                o.add(TIMES_DECLINED, range);
            }
        }
        if (!when.questState().isEmpty()) {
            JsonObject states = new JsonObject();
            when.questState().forEach((quest, state) -> states.addProperty(quest, state.out));
            o.add(QUEST_STATE, states);
        }
        if (!when.stage().isEmpty()) {
            JsonObject stages = new JsonObject();
            when.stage().forEach(stages::addProperty);
            o.add(STAGE, stages);
        }
        return o;
    }
}
