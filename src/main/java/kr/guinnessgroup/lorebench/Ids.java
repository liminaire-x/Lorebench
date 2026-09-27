/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench;

import java.util.regex.Pattern;

/**
 * Ids of authored things: {@code <kind>_<a-z, 0-9>}, e.g. {@code npc_7ha2m0qe}.
 * The editor makes them up at random when something is created and nobody edits
 * them afterwards; other documents and saved records refer to them. The kind
 * prefix says what an id is wherever it shows up (files, records, logs).
 * See docs/decisions/0004-ids-and-record-keys.md.
 */
public final class Ids {

    public static final String GRAPH = "graph";
    public static final String NODE = "node";
    public static final String NPC = "npc";
    public static final String QUEST = "quest";
    public static final String FOLDER = "folder";

    private static final Pattern REST = Pattern.compile("[a-z0-9]+");

    private Ids() {}

    public static boolean valid(String kind, String id) {
        String prefix = kind + "_";
        return id != null && id.startsWith(prefix) && REST.matcher(id.substring(prefix.length())).matches();
    }

    /** For error messages, e.g. "must look like npc_ followed by a-z, 0-9". */
    public static String rule(String kind) {
        return "must look like " + kind + "_ followed by a-z, 0-9";
    }

    /**
     * Names a thing in error messages the way the author knows it, e.g.
     * "quest '늑대 사냥' (quest_nj46mr8g)", or "quest 'quest_nj46mr8g'" while it has no name.
     */
    public static String named(String label, String id, String name) {
        return name == null || name.isBlank()
                ? label + " '" + id + "'"
                : label + " '" + name.trim() + "' (" + id + ")";
    }
}
