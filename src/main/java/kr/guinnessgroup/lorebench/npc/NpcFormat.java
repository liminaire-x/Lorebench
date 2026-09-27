/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.npc;

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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes the NPC document (format 1):
 * <pre>{ "format": 1, "folders": [ ... ],
 *   "npcs": [ { "id": "npc_7ha2m0qe", "name": "촌장", "model": "chief", "idle": "wave", "folder": "folder_2kq8d1xz",
 *               "greeting": [ "오, 자네 왔군.", "무슨 일인가?" ],
 *               "talk": { "start": "animation.chief.talk_start", "loop": "animation.chief.talk", "end": "animation.chief.talk_end" },
 *               "voice": { "sound": "minecraft:block.note_block.bass", "pitch": 0.8 } } ] }</pre>
 * {@code model} and {@code idle} are optional (a plain NPC has neither). {@code folders} and
 * {@code folder} group NPCs in the editor ({@link Folders}). {@code greeting} is optional
 * ({@link Speech}: lines, or groups picked by quest state). {@code talk} is optional, and so is each of its names
 * (docs/decisions/0011-talk-gestures.md). {@code voice} is optional, a sound name or a sound with a pitch
 * (docs/decisions/0014-typing.md).
 * This file has its own format number so NPCs can grow (looks, animations,
 * cinematics) without touching the graph document.
 */
public final class NpcFormat {

    public static final int VERSION = 1;

    /** Model names are resource pack file names: lowercase letters, digits, underscore. */
    private static final Pattern MODEL = Pattern.compile("[a-z0-9_]+");

    /** The keys of the talk set. Never rename: they are saved. */
    private static final Set<String> TALK_KEYS = Set.of("start", "loop", "end");

    /** The keys of a voice written as an object. Never rename: they are saved. */
    private static final Set<String> VOICE_KEYS = Set.of("sound", "pitch");

    /** A sound name with its namespace, as resource packs name them. */
    private static final Pattern SOUND = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    /** The game plays a sound's pitch only in this range (SoundEngine.calculatePitch). */
    public static final double MIN_PITCH = 0.5;
    public static final double MAX_PITCH = 2.0;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private NpcFormat() {}

    public static NpcDoc read(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new DocumentException(List.of("NPC document is not a JSON object"));
        }
        JsonElement format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()) {
            throw new DocumentException(List.of("NPC document: missing 'format' number"));
        }
        int version = format.getAsInt();
        if (version > VERSION) {
            throw new DocumentException(List.of("NPC document format " + version
                    + " is newer than this Lorebench supports (" + VERSION + ")"));
        }
        if (version != VERSION) {
            throw new DocumentException(List.of("NPC document: unknown format " + version));
        }
        JsonElement arr = root.get("npcs");
        if (arr == null || !arr.isJsonArray()) {
            throw new DocumentException(List.of("NPC document: missing 'npcs' list"));
        }

        List<String> errors = new ArrayList<>();
        List<Folders.Folder> folders = Folders.read(root.get("folders"), "NPC document", errors);
        List<NpcDoc.NpcDef> npcs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonElement el : (JsonArray) arr) {
            if (!el.isJsonObject()) {
                errors.add("an NPC is not an object");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = string(o, "id");
            String name = string(o, "name");
            if (!Ids.valid(Ids.NPC, id)) {
                errors.add("NPC id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.NPC)));
                continue;
            }
            if (name == null || name.isBlank()) {
                errors.add("NPC '" + id + "': missing 'name'");
                continue;
            }
            if (!ids.add(id)) {
                errors.add("duplicate NPC id '" + id + "'");
                continue;
            }
            String where = Ids.named("NPC", id, name);
            String model = optional(o, "model");
            String idle = optional(o, "idle");
            if (!model.isEmpty() && !MODEL.matcher(model).matches()) {
                errors.add(where + ": model '" + model + "' must use a-z, 0-9, _");
                continue;
            }
            String folder = Folders.placement(o, folders, where, errors);
            Speech greeting = Speech.read(o.get("greeting"), false, where + " greeting", errors);
            NpcDoc.Talk talk = readTalk(o.get("talk"), where, errors);
            NpcDoc.Voice voice = readVoice(o.get("voice"), where, errors);
            npcs.add(new NpcDoc.NpcDef(id, name, model, idle, folder, greeting, talk, voice));
        }
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
        return new NpcDoc(folders, List.copyOf(npcs));
    }

    public static String write(NpcDoc doc) {
        JsonArray arr = new JsonArray();
        for (NpcDoc.NpcDef n : doc.npcs()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", n.id());
            o.addProperty("name", n.name());
            if (!n.model().isEmpty()) {
                o.addProperty("model", n.model());
            }
            if (!n.idle().isEmpty()) {
                o.addProperty("idle", n.idle());
            }
            Folders.writePlacement(o, n.folder());
            if (!n.greeting().isEmpty()) {
                o.add("greeting", Speech.write(n.greeting()));
            }
            if (!n.talk().isEmpty()) {
                o.add("talk", writeTalk(n.talk()));
            }
            if (!n.voice().isEmpty()) {
                o.add("voice", writeVoice(n.voice()));
            }
            arr.add(o);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", VERSION);
        Folders.write(root, doc.folders());
        root.add("npcs", arr);
        return GSON.toJson(root);
    }

    /** The optional talk set: an object of animation names, any of them may be left out. */
    private static NpcDoc.Talk readTalk(JsonElement e, String where, List<String> errors) {
        if (e == null) {
            return NpcDoc.Talk.NONE;
        }
        if (!e.isJsonObject()) {
            errors.add(where + ": talk must be an object like { \"start\": …, \"loop\": …, \"end\": … }");
            return NpcDoc.Talk.NONE;
        }
        JsonObject o = e.getAsJsonObject();
        for (String key : o.keySet()) {
            if (!TALK_KEYS.contains(key)) {
                errors.add(where + ": unknown talk '" + key + "' (use start, loop, end)");
                return NpcDoc.Talk.NONE;
            }
            JsonElement v = o.get(key);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isString()) {
                errors.add(where + ": talk " + key + " must be an animation name");
                return NpcDoc.Talk.NONE;
            }
        }
        return new NpcDoc.Talk(optional(o, "start"), optional(o, "loop"), optional(o, "end"));
    }

    private static JsonObject writeTalk(NpcDoc.Talk talk) {
        JsonObject o = new JsonObject();
        if (!talk.start().isEmpty()) {
            o.addProperty("start", talk.start());
        }
        if (!talk.loop().isEmpty()) {
            o.addProperty("loop", talk.loop());
        }
        if (!talk.end().isEmpty()) {
            o.addProperty("end", talk.end());
        }
        return o;
    }

    /**
     * The optional voice (0014): a sound name, or {@code { "sound": …, "pitch": 0.8 }}. Whether the sound
     * exists is not checked: a resource pack's sounds are known to clients only (like models).
     */
    private static NpcDoc.Voice readVoice(JsonElement e, String where, List<String> errors) {
        if (e == null) {
            return NpcDoc.Voice.NONE;
        }
        JsonElement sound = e;
        float pitch = 1;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            for (String key : o.keySet()) {
                if (!VOICE_KEYS.contains(key)) {
                    errors.add(where + ": unknown voice '" + key + "' (use sound, pitch)");
                    return NpcDoc.Voice.NONE;
                }
            }
            sound = o.get("sound");
            JsonElement p = o.get("pitch");
            if (p != null) {
                double d = p.isJsonPrimitive() && p.getAsJsonPrimitive().isNumber() ? p.getAsDouble() : -1;
                if (!(d >= MIN_PITCH && d <= MAX_PITCH)) {
                    errors.add(where + ": voice pitch must be from " + MIN_PITCH + " to " + MAX_PITCH);
                    return NpcDoc.Voice.NONE;
                }
                pitch = (float) d;
            }
        }
        String name = (sound != null && sound.isJsonPrimitive() && sound.getAsJsonPrimitive().isString())
                ? sound.getAsString().trim() : "";
        if (!SOUND.matcher(name).matches()) {
            errors.add(where + ": voice must be a sound name like minecraft:block.note_block.bass, "
                    + "or { \"sound\": …, \"pitch\": 0.8 }");
            return NpcDoc.Voice.NONE;
        }
        return new NpcDoc.Voice(name, pitch);
    }

    /** A voice at the recorded pitch is written as just its sound name. */
    private static JsonElement writeVoice(NpcDoc.Voice voice) {
        if (voice.pitch() == 1) {
            return new JsonPrimitive(voice.sound());
        }
        JsonObject o = new JsonObject();
        o.addProperty("sound", voice.sound());
        o.addProperty("pitch", voice.pitch());
        return o;
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
