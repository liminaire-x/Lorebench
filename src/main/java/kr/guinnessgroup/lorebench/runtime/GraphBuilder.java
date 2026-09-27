/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.runtime;

import kr.guinnessgroup.lorebench.graph.GraphDoc;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Ids;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a saved document into runnable graphs, checking what the format alone
 * cannot: that node types exist, their settings are usable, and links use real
 * ways out and never lead into a trigger.
 */
public final class GraphBuilder {

    private GraphBuilder() {}

    public static List<Graph> build(GraphDoc doc, NodeRegistry registry, Catalog catalog) {
        List<String> errors = new ArrayList<>();
        List<Graph> graphs = new ArrayList<>();
        for (GraphDoc.DocGraph g : doc.graphs()) {
            graphs.add(buildGraph(g, registry, catalog, errors));
        }
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
        return List.copyOf(graphs);
    }

    private static Graph buildGraph(GraphDoc.DocGraph g, NodeRegistry registry, Catalog catalog, List<String> errors) {
        String where = Ids.named("graph", g.id(), g.name());

        Map<String, NodeType> typeOf = new HashMap<>();
        Map<String, Node> runnable = new HashMap<>();
        for (GraphDoc.DocNode n : g.nodes()) {
            NodeType type = registry.get(n.type());
            if (type == null) {
                errors.add(where + ": node '" + n.id() + "' has unknown type '" + n.type() + "'");
                continue;
            }
            typeOf.put(n.id(), type);
            try {
                runnable.put(n.id(), type.create(n.config(), catalog));
            } catch (IllegalArgumentException e) {
                errors.add(where + ": node '" + n.id() + "' (" + type.label() + "): " + e.getMessage());
            }
        }

        Map<String, Map<String, String>> next = new HashMap<>();
        for (GraphDoc.DocLink l : g.links()) {
            NodeType from = typeOf.get(l.from());
            NodeType to = typeOf.get(l.to());
            if (from == null || to == null) {
                continue; // already reported
            }
            if (!from.outs().contains(l.out())) {
                errors.add(where + ": node '" + l.from() + "' has no way out named '" + l.out() + "'");
                continue;
            }
            if (to.trigger()) {
                errors.add(where + ": node '" + l.to() + "' is a trigger; nothing can lead into it");
                continue;
            }
            Map<String, String> outs = next.computeIfAbsent(l.from(), k -> new HashMap<>());
            if (outs.putIfAbsent(l.out(), l.to()) != null) {
                errors.add(where + ": node '" + l.from() + "' way out '" + l.out() + "' is linked twice");
            }
        }

        Map<String, Graph.Placed> placed = new LinkedHashMap<>();
        for (GraphDoc.DocNode n : g.nodes()) {
            NodeType type = typeOf.get(n.id());
            Node node = runnable.get(n.id());
            if (type != null && node != null) {
                placed.put(n.id(), new Graph.Placed(n.id(), type, node, Map.copyOf(next.getOrDefault(n.id(), Map.of()))));
            }
        }
        return new Graph(g.id(), g.name(), placed);
    }
}
