/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Folders in the editor's trees. The quest, NPC and graph documents each keep their
 * own optional list, the same way:
 * <pre>"folders": [ { "id": "folder_2kq8d1xz", "name": "마을" },
 *               { "id": "folder_9fm3a0pe", "name": "촌장", "parent": "folder_2kq8d1xz" } ]</pre>
 * and each quest, NPC or graph may name the {@code folder} it sits in. The game
 * doesn't use folders. See docs/decisions/0008-quest-folders.md.
 */
public final class Folders {

    /**
     * A folder in the editor's tree.
     *
     * @param parent the folder id it sits in, or "" for the top
     */
    public record Folder(String id, String name, String parent) {}

    private Folders() {}

    /**
     * Reads a document's {@code folders} (absent = none): ids are unique, each parent is
     * another folder in the list, and following parents always ends at the top.
     *
     * @param doc names the document in error messages, e.g. "quest document"
     */
    public static List<Folder> read(JsonElement e, String doc, List<String> errors) {
        if (e == null) {
            return List.of();
        }
        if (!e.isJsonArray()) {
            errors.add(doc + ": 'folders' is not a list");
            return List.of();
        }
        Map<String, Folder> byId = new LinkedHashMap<>();
        for (JsonElement el : e.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                errors.add(doc + ": a folder is not an object");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String id = string(o, "id");
            if (!Ids.valid(Ids.FOLDER, id)) {
                errors.add(doc + ": folder id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.FOLDER)));
                continue;
            }
            String name = string(o, "name");
            if (name == null || name.isBlank()) {
                errors.add(doc + ": folder '" + id + "': missing 'name'");
                continue;
            }
            String parent = string(o, "parent");
            if (byId.putIfAbsent(id, new Folder(id, name.trim(), parent == null ? "" : parent.trim())) != null) {
                errors.add(doc + ": duplicate folder id '" + id + "'");
            }
        }
        for (Folder f : byId.values()) {
            if (!f.parent().isEmpty() && !byId.containsKey(f.parent())) {
                errors.add(doc + ": " + Ids.named("folder", f.id(), f.name()) + ": parent '" + f.parent() + "' does not exist");
                continue;
            }
            Set<String> seen = new HashSet<>();
            for (String at = f.id(); !at.isEmpty() && byId.containsKey(at); at = byId.get(at).parent()) {
                if (!seen.add(at)) {
                    errors.add(doc + ": " + Ids.named("folder", f.id(), f.name()) + " ends up inside itself");
                    break;
                }
            }
        }
        return List.copyOf(byId.values());
    }

    /**
     * Reads the optional {@code folder} of a quest, NPC or graph, checking that it is
     * one of {@code folders}. Returns "" for the top.
     *
     * @param where names the thing in error messages, e.g. "quest 'quest_a'"
     */
    public static String placement(JsonObject o, List<Folder> folders, String where, List<String> errors) {
        String folder = string(o, "folder");
        if (folder == null || folder.isBlank()) {
            return "";
        }
        folder = folder.trim();
        for (Folder f : folders) {
            if (f.id().equals(folder)) {
                return folder;
            }
        }
        errors.add(where + ": folder '" + folder + "' does not exist");
        return folder;
    }

    /** Adds {@code folders} to a written document, unless there are none. */
    public static void write(JsonObject root, List<Folder> folders) {
        if (folders.isEmpty()) {
            return;
        }
        JsonArray arr = new JsonArray();
        for (Folder f : folders) {
            JsonObject o = new JsonObject();
            o.addProperty("id", f.id());
            o.addProperty("name", f.name());
            if (!f.parent().isEmpty()) {
                o.addProperty("parent", f.parent());
            }
            arr.add(o);
        }
        root.add("folders", arr);
    }

    /** Adds a quest's, NPC's or graph's {@code folder}, unless it sits at the top. */
    public static void writePlacement(JsonObject o, String folder) {
        if (!folder.isEmpty()) {
            o.addProperty("folder", folder);
        }
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : null;
    }
}
