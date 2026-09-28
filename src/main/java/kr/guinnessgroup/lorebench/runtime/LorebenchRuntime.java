/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.Ids;
import kr.guinnessgroup.lorebench.graph.GraphDoc;
import kr.guinnessgroup.lorebench.graph.GraphFormat;
import kr.guinnessgroup.lorebench.npc.NpcDoc;
import kr.guinnessgroup.lorebench.npc.NpcFormat;
import kr.guinnessgroup.lorebench.quest.QuestDoc;
import kr.guinnessgroup.lorebench.quest.QuestFormat;
import kr.guinnessgroup.lorebench.record.Owner;
import kr.guinnessgroup.lorebench.record.RecordStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds the published content (graphs, NPC definitions, quests) and starts graphs
 * when events happen. Publishing checks the documents together, replaces everything
 * at once with no server restart, and saves {@code graphs.json}, {@code npcs.json}
 * and {@code quests.json}.
 */
public final class LorebenchRuntime {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final NodeRegistry registry;
    private final RecordStore records;
    private final Path graphsFile;
    private final Path npcsFile;
    private final Path questsFile;
    private final ContentChecks checks;
    private volatile Owner serverOwner = Owner.server("main");
    private volatile Runnable onPublish = () -> {};
    private volatile StageMoves stageMoves = StageMoves.NONE;

    /** Everything that changes on publish, swapped in one step. */
    private record Active(GraphDoc graphs, NpcDoc npcs, QuestDoc quests, Map<String, List<Start>> startsByTrigger) {}

    private record Start(Graph graph, String nodeId) {}

    private static final Active EMPTY = new Active(GraphDoc.EMPTY, NpcDoc.EMPTY,
            QuestDoc.EMPTY, Map.of());

    private volatile Active active = EMPTY;

    /** @param checks how publish asks the running game whether quest items and mobs exist */
    public LorebenchRuntime(NodeRegistry registry, RecordStore records, Path dir, ContentChecks checks) {
        this.registry = registry;
        this.records = records;
        this.graphsFile = dir.resolve("graphs.json");
        this.npcsFile = dir.resolve("npcs.json");
        this.questsFile = dir.resolve("quests.json");
        this.checks = checks;
    }

    /** Run after every accepted publish, on the publishing (web) thread. */
    public void onPublish(Runnable action) {
        this.onPublish = (action == null) ? () -> {} : action;
    }

    /** Called on every publish that passed its checks, before it goes live, on the publishing (web) thread. */
    public void onStageMoves(StageMoves moves) {
        this.stageMoves = (moves == null) ? StageMoves.NONE : moves;
    }

    /**
     * Check, swap in, and save new content. The body is
     * {@code {"graphs": <graph document>, "npcs": <NPC document>, "quests": <quest document>}}, and
     * {@code "moves": {"<removed stage id>": "<stage id>"}} when players are on stages it removes (0015).
     * Throws {@link DocumentException} if anything is rejected; then nothing changes.
     */
    public synchronized void publish(String json) {
        JsonObject body;
        try {
            body = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new DocumentException(List.of("not a JSON object"));
        }
        JsonElement graphs = body.get("graphs");
        JsonElement npcs = body.get("npcs");
        JsonElement quests = body.get("quests");
        if (graphs == null || npcs == null || quests == null) {
            throw new DocumentException(List.of("publish needs 'graphs', 'npcs' and 'quests'"));
        }
        NpcDoc npcDoc = NpcFormat.read(npcs.toString());
        QuestDoc questDoc = QuestFormat.read(quests.toString());
        GraphDoc graphDoc = GraphFormat.read(graphs.toString());
        activate(graphDoc, npcDoc, questDoc, moves(body.get("moves")));
        write(npcsFile, NpcFormat.write(npcDoc));
        write(questsFile, QuestFormat.write(questDoc));
        write(graphsFile, GraphFormat.write(graphDoc));
        onPublish.run();
    }

    /** Load saved content. If a file is broken it is left untouched and no graph runs. */
    public synchronized void load(Owner serverOwner) {
        this.serverOwner = serverOwner;
        try {
            NpcDoc npcDoc = Files.exists(npcsFile)
                    ? NpcFormat.read(Files.readString(npcsFile, StandardCharsets.UTF_8))
                    : NpcDoc.EMPTY;
            QuestDoc questDoc = Files.exists(questsFile)
                    ? QuestFormat.read(Files.readString(questsFile, StandardCharsets.UTF_8))
                    : QuestDoc.EMPTY;
            GraphDoc graphDoc = Files.exists(graphsFile)
                    ? GraphFormat.read(Files.readString(graphsFile, StandardCharsets.UTF_8))
                    : GraphDoc.EMPTY;
            activate(graphDoc, npcDoc, questDoc, null);
        } catch (DocumentException e) {
            LOGGER.error("[Lorebench] Saved content was not loaded; nothing will run until it is fixed or republished: {}",
                    e.errors());
        } catch (IOException e) {
            LOGGER.error("[Lorebench] Reading saved content failed; nothing will run", e);
        }
    }

    /**
     * The publish's {@code moves}: a removed stage's id → the stage its players go to (0015), none
     * when it has none.
     */
    private static Map<String, String> moves(JsonElement e) {
        if (e == null) {
            return Map.of();
        }
        DocumentException shape = new DocumentException(
                List.of("'moves' must be like { \"<removed stage id>\": \"<stage id>\" }"));
        if (!e.isJsonObject()) {
            throw shape;
        }
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> m : e.getAsJsonObject().entrySet()) {
            JsonElement to = m.getValue();
            if (!to.isJsonPrimitive() || !to.getAsJsonPrimitive().isString()) {
                throw shape;
            }
            out.put(m.getKey(), to.getAsString());
        }
        return Map.copyOf(out);
    }

    /** @param moves a publish's stage moves ({@link StageMoves}), or {@code null} when loading saved content */
    private void activate(GraphDoc graphDoc, NpcDoc npcDoc, QuestDoc questDoc, Map<String, String> moves) {
        checkItems(questDoc);
        Set<String> npcIds = npcDoc.npcs().stream().map(NpcDoc.NpcDef::id).collect(Collectors.toUnmodifiableSet());
        List<String> npcErrors = questDoc.npcErrors(npcIds);
        if (!npcErrors.isEmpty()) {
            throw new DocumentException(npcErrors);
        }
        Set<String> questIds = questDoc.quests().stream().map(QuestDoc.Quest::id).collect(Collectors.toUnmodifiableSet());
        List<String> questErrors = npcDoc.questErrors(questIds);
        if (!questErrors.isEmpty()) {
            throw new DocumentException(questErrors);
        }
        List<Graph> graphs = GraphBuilder.build(graphDoc, registry, new Catalog(npcIds, questIds));
        Map<String, List<Start>> starts = new HashMap<>();
        for (Graph g : graphs) {
            for (Graph.Placed n : g.nodes().values()) {
                if (n.type().trigger()) {
                    starts.computeIfAbsent(n.type().id(), k -> new ArrayList<>()).add(new Start(g, n.id()));
                }
            }
        }
        starts.replaceAll((k, v) -> List.copyOf(v));
        if (moves != null) {
            stageMoves.apply(active.quests(), questDoc, moves); // may reject; then nothing changes
        }
        this.active = new Active(graphDoc, npcDoc, questDoc, Map.copyOf(starts));
        LOGGER.info("[Lorebench] {} graph(s), {} NPC(s), {} quest(s) active",
                graphs.size(), npcDoc.npcs().size(), questDoc.quests().size());
    }

    /**
     * Quest items, kill and breed targets and crops must exist in this game (a typo, a
     * missing mod or bad components is rejected), crops must be ones whose full growth
     * shows, and breed targets animals whose babies are born when fed.
     */
    private void checkItems(QuestDoc questDoc) {
        List<String> errors = new ArrayList<>();
        for (QuestDoc.Quest q : questDoc.quests()) {
            String where = Ids.named("quest", q.id(), q.title()) + ": ";
            if (!q.icon().isEmpty()) {
                check(errors, where + "icon '" + q.icon() + "': ", checks.item(q.icon()));
            }
            for (QuestDoc.Goal g : q.stages().stream().flatMap(s -> s.goals().stream()).toList()) {
                String problem = switch (g.kind()) {
                    case ITEM -> checks.itemCondition(g.target());
                    case KILL -> checks.entity(g.target());
                    case HARVEST -> checks.crop(g.target());
                    case BREED -> checks.breedable(g.target());
                    case COLLECT -> {
                        String item = checks.item(g.target());
                        String from = g.from().isEmpty() ? null // brought by an earlier stage (0015)
                                : checks.entity(g.from().substring(g.from().indexOf(':') + 1)); // kill:<entity>
                        yield item != null ? item : from == null ? null : "from '" + g.from() + "': " + from;
                    }
                };
                String what = g.kind() == QuestDoc.Goal.Kind.ITEM ? "goal" : g.kind().key;
                check(errors, where + what + " '" + g.target() + "': ", problem);
            }
            for (QuestDoc.Stack s : q.rewards()) {
                check(errors, where + "reward '" + s.item() + "': ", checks.item(s.item()));
            }
            for (QuestDoc.Stack s : q.stages().stream().flatMap(st -> st.gives().stream()).toList()) {
                check(errors, where + "gift '" + s.item() + "': ", checks.item(s.item()));
            }
            for (QuestDoc.Stack s : q.supplies()) {
                check(errors, where + "supply '" + s.item() + "': ", checks.item(s.item()));
            }
        }
        if (!errors.isEmpty()) {
            throw new DocumentException(errors);
        }
    }

    private static void check(List<String> errors, String where, String problem) {
        if (problem != null) {
            errors.add(where + problem);
        }
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[Lorebench] Published, but saving {} failed", file, e);
        }
    }

    /** The current graph document, for the editor. */
    public String graphsJson() {
        return GraphFormat.write(active.graphs());
    }

    /** The current NPC document, for the editor. */
    public String npcsJson() {
        return NpcFormat.write(active.npcs());
    }

    /** The current quest document, for the editor. */
    public String questsJson() {
        return QuestFormat.write(active.quests());
    }

    /** An NPC definition, or {@code null} if none has that id. */
    public NpcDoc.NpcDef npc(String id) {
        return active.npcs().find(id);
    }

    public List<NpcDoc.NpcDef> npcs() {
        return active.npcs().npcs();
    }

    /** A quest, or {@code null} if none has that id. */
    public QuestDoc.Quest quest(String id) {
        return active.quests().find(id);
    }

    public List<QuestDoc.Quest> quests() {
        return active.quests().quests();
    }

    public Owner serverOwner() {
        return serverOwner;
    }

    /**
     * Run every graph that starts with the given trigger type. Call on the server thread.
     *
     * @param event facts about the event for trigger nodes, e.g. {@code npc -> npc_7ha2m0qe}
     */
    public void fire(String triggerType, MinecraftServer server, ServerPlayer player, Map<String, String> event) {
        List<Start> starts = active.startsByTrigger().getOrDefault(triggerType, List.of());
        for (Start s : starts) {
            Runner.run(s.graph(), s.nodeId(), new Context(server, player, records, serverOwner, event));
        }
    }

    public synchronized void clear() {
        this.active = EMPTY;
    }
}
