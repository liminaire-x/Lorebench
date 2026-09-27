/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.graph;

import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Folders;
import kr.guinnessgroup.lorebench.Ids;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Reads and writes the graph document (format 1). This class checks only the
 * document's shape: the version, required fields, unique ids, and that links point
 * at nodes that exist. Whether node types exist is the runtime's job. {@code folders}
 * and a graph's {@code folder} group graphs in the editor ({@link Folders}).
 * <p>
 * The server owns this format; the editor converts to and from it.
 */
public final class GraphFormat {

    /** The format this code reads and writes. Bump only with a migration from the old one. */
    public static final int VERSION = 1;

    /** The out port a link uses when it names none. */
    public static final String DEFAULT_OUT = "next";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private GraphFormat() {}

    public static GraphDoc read(String json) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new DocumentException(List.of("not a JSON object"));
        }
        List<String> errors = new ArrayList<>();

        JsonElement format = root.get("format");
        if (format == null || !format.isJsonPrimitive() || !format.getAsJsonPrimitive().isNumber()) {
            throw new DocumentException(List.of("missing 'format' number"));
        }
        int version = format.getAsInt();
        if (version > VERSION) {
            throw new DocumentException(List.of("format " + version + " is newer than this Lorebench supports (" + VERSION + ")"));
        }
        if (version != VERSION) {
            throw new DocumentException(List.of("unknown format " + version));
        }

        List<Folders.Folder> folders = Folders.read(root.get("folders"), "graph document", errors);
        JsonArray graphsJson = array(root, "graphs", "document", errors);
        List<GraphDoc.DocGraph> graphs = new ArrayList<>();
        Set<String> graphIds = new HashSet<>();
        if (graphsJson != null) {
            for (JsonElement el : graphsJson) {
                GraphDoc.DocGraph g = readGraph(el, folders, errors);
                if (g == null) {
                    continue;
                }
                if (!graphIds.add(g.id())) {
                    errors.add("duplicate graph id '" + g.id() + "'");
                }
                graphs.add(g);
            }
        }
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
        return new GraphDoc(folders, List.copyOf(graphs));
    }

    private static GraphDoc.DocGraph readGraph(JsonElement el, List<Folders.Folder> folders, List<String> errors) {
        if (!el.isJsonObject()) {
            errors.add("a graph is not an object");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String id = string(o, "id");
        if (!Ids.valid(Ids.GRAPH, id)) {
            errors.add("graph id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.GRAPH)));
            return null;
        }
        String name = string(o, "name");
        String where = Ids.named("graph", id, name);
        if (name == null || name.isBlank()) {
            errors.add(where + ": missing 'name'");
        }

        List<GraphDoc.DocNode> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        JsonArray nodesJson = array(o, "nodes", where, errors);
        if (nodesJson != null) {
            for (JsonElement n : nodesJson) {
                GraphDoc.DocNode node = readNode(n, where, errors);
                if (node == null) {
                    continue;
                }
                if (!nodeIds.add(node.id())) {
                    errors.add(where + ": duplicate node id '" + node.id() + "'");
                }
                nodes.add(node);
            }
        }

        List<GraphDoc.DocLink> links = new ArrayList<>();
        JsonArray linksJson = array(o, "links", where, errors);
        if (linksJson != null) {
            for (JsonElement l : linksJson) {
                GraphDoc.DocLink link = readLink(l, where, errors);
                if (link == null) {
                    continue;
                }
                if (!nodeIds.contains(link.from())) {
                    errors.add(where + ": link from unknown node '" + link.from() + "'");
                }
                if (!nodeIds.contains(link.to())) {
                    errors.add(where + ": link to unknown node '" + link.to() + "'");
                }
                links.add(link);
            }
        }
        String folder = Folders.placement(o, folders, where, errors);
        return new GraphDoc.DocGraph(id, name, List.copyOf(nodes), List.copyOf(links), folder);
    }

    private static GraphDoc.DocNode readNode(JsonElement el, String where, List<String> errors) {
        if (!el.isJsonObject()) {
            errors.add(where + ": a node is not an object");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String id = string(o, "id");
        String type = string(o, "type");
        if (!Ids.valid(Ids.NODE, id)) {
            errors.add(where + ": node id " + (id == null ? "is missing" : "'" + id + "' " + Ids.rule(Ids.NODE)));
            return null;
        }
        if (type == null || type.isBlank()) {
            errors.add(where + ": node '" + id + "' is missing 'type'");
            return null;
        }
        JsonObject config = new JsonObject();
        JsonElement c = o.get("config");
        if (c != null && !c.isJsonNull()) {
            if (!c.isJsonObject()) {
                errors.add(where + ": node '" + id + "' config is not an object");
                return null;
            }
            config = c.getAsJsonObject().deepCopy();
        }
        // pos is cosmetic: a missing one places the node at the origin. Whole pixels
        // are enough; a fraction (e.g. 12.0 from an older save) is rounded.
        int x = 0;
        int y = 0;
        JsonElement p = o.get("pos");
        if (p != null && p.isJsonArray() && p.getAsJsonArray().size() == 2) {
            try {
                x = (int) Math.round(p.getAsJsonArray().get(0).getAsDouble());
                y = (int) Math.round(p.getAsJsonArray().get(1).getAsDouble());
            } catch (RuntimeException e) {
                errors.add(where + ": node '" + id + "' pos must be two numbers");
            }
        }
        return new GraphDoc.DocNode(id, type, config, x, y);
    }

    private static GraphDoc.DocLink readLink(JsonElement el, String where, List<String> errors) {
        if (!el.isJsonObject()) {
            errors.add(where + ": a link is not an object");
            return null;
        }
        JsonObject o = el.getAsJsonObject();
        String from = string(o, "from");
        String to = string(o, "to");
        if (from == null || to == null) {
            errors.add(where + ": a link needs 'from' and 'to'");
            return null;
        }
        String out = string(o, "out");
        return new GraphDoc.DocLink(from, out == null ? DEFAULT_OUT : out, to);
    }

    public static String write(GraphDoc doc) {
        JsonObject root = new JsonObject();
        root.addProperty("format", VERSION);
        Folders.write(root, doc.folders());
        JsonArray graphs = new JsonArray();
        for (GraphDoc.DocGraph g : doc.graphs()) {
            JsonObject go = new JsonObject();
            go.addProperty("id", g.id());
            go.addProperty("name", g.name());
            Folders.writePlacement(go, g.folder());
            JsonArray nodes = new JsonArray();
            for (GraphDoc.DocNode n : g.nodes()) {
                JsonObject no = new JsonObject();
                no.addProperty("id", n.id());
                no.addProperty("type", n.type());
                no.add("config", n.config().deepCopy());
                JsonArray pos = new JsonArray();
                pos.add(n.x());
                pos.add(n.y());
                no.add("pos", pos);
                nodes.add(no);
            }
            go.add("nodes", nodes);
            JsonArray links = new JsonArray();
            for (GraphDoc.DocLink l : g.links()) {
                JsonObject lo = new JsonObject();
                lo.addProperty("from", l.from());
                if (!DEFAULT_OUT.equals(l.out())) {
                    lo.addProperty("out", l.out());
                }
                lo.addProperty("to", l.to());
                links.add(lo);
            }
            go.add("links", links);
            graphs.add(go);
        }
        root.add("graphs", graphs);
        // Gson spreads every array over lines; keep "pos": [x, y] on one line for readability.
        return POS_ARRAY.matcher(GSON.toJson(root)).replaceAll("\"pos\": [$1, $2]");
    }

    private static final Pattern POS_ARRAY = Pattern.compile("\"pos\": \\[\\s*(-?\\d+),\\s*(-?\\d+)\\s*]");

    // --- helpers ---

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) ? e.getAsString() : null;
    }

    private static JsonArray array(JsonObject o, String key, String where, List<String> errors) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) {
            errors.add(where + ": missing '" + key + "' list");
            return null;
        }
        return e.getAsJsonArray();
    }
}
