/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package kr.guinnessgroup.lorebench.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import kr.guinnessgroup.lorebench.DocumentException;
import kr.guinnessgroup.lorebench.npc.Npcs;
import kr.guinnessgroup.lorebench.quest.Breeding;
import kr.guinnessgroup.lorebench.quest.Crops;
import kr.guinnessgroup.lorebench.quest.Quests;
import kr.guinnessgroup.lorebench.runtime.LorebenchRuntime;
import kr.guinnessgroup.lorebench.runtime.NodeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Serves the web editor and its API on port 8080:
 * <ul>
 *   <li>{@code GET /api/health}</li>
 *   <li>{@code GET /api/schema} — node types for the palette</li>
 *   <li>{@code GET /api/graphs} — the current graph document</li>
 *   <li>{@code GET /api/npcs} — the current NPC document</li>
 *   <li>{@code GET /api/npc-placements} — where each NPC stands</li>
 *   <li>{@code GET /api/quests} — the current quest document</li>
 *   <li>{@code POST /api/publish} — replace all documents: {@code {"graphs": ..., "npcs": ..., "quests": ...}}</li>
 *   <li>{@code GET /api/players} — who is online</li>
 *   <li>{@code GET /api/held-item?player=Name} — what they hold, as {@code /give} writes it</li>
 *   <li>{@code GET /api/crops} — the crops a harvest goal may name</li>
 *   <li>{@code GET /api/animals} — the animals a breed goal may name</li>
 *   <li>{@code GET /api/quest-players?quest=<id>} — who is on that quest, has done it or turned it down</li>
 *   <li>{@code POST /api/quest-reset} — take one player back to before a quest: {@code {"quest": ..., "player": <uuid>}}</li>
 * </ul>
 */
public final class LorebenchWebServer {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String EDITOR_INDEX = "/lorebench/web/index.html";
    private static final String JSON = "application/json; charset=utf-8";
    public static final int PORT = 8080;

    private final LorebenchRuntime runtime;
    private final NodeRegistry registry;
    private final Npcs npcs;
    private HttpServer server;

    public LorebenchWebServer(LorebenchRuntime runtime, NodeRegistry registry, Npcs npcs) {
        this.runtime = runtime;
        this.registry = registry;
        this.npcs = npcs;
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }
        try {
            server = HttpServer.create(new InetSocketAddress(PORT), 0);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Lorebench-Web");
                t.setDaemon(true);
                return t;
            }));
            server.createContext("/", this::handleRoot);
            server.createContext("/api/health", ex -> send(ex, 200, JSON, "{\"status\":\"ok\"}"));
            server.createContext("/api/schema", ex -> send(ex, 200, JSON, registry.schemaJson()));
            server.createContext("/api/graphs", ex -> getOnly(ex, runtime.graphsJson()));
            server.createContext("/api/npcs", ex -> getOnly(ex, runtime.npcsJson()));
            server.createContext("/api/npc-placements", ex -> getOnly(ex, npcs.placementsJson()));
            server.createContext("/api/quests", ex -> getOnly(ex, runtime.questsJson()));
            server.createContext("/api/publish", this::handlePublish);
            server.createContext("/api/players", ex -> fromGame(ex, LorebenchWebServer::players));
            server.createContext("/api/crops", ex -> fromGame(ex, LorebenchWebServer::crops));
            server.createContext("/api/animals", ex -> fromGame(ex, LorebenchWebServer::animals));
            server.createContext("/api/quest-players", ex -> {
                String quest = query(ex, "quest");
                fromGame(ex, mc -> questPlayers(mc, quest));
            });
            server.createContext("/api/quest-reset", this::handleQuestReset);
            server.createContext("/api/held-item", ex -> {
                String player = query(ex, "player");
                fromGame(ex, mc -> heldItem(mc, player));
            });
            server.start();
            LOGGER.info("[Lorebench] Web editor at http://localhost:{}", PORT);
        } catch (IOException e) {
            LOGGER.error("[Lorebench] Failed to start web server on port {}", PORT, e);
            server = null;
        }
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void handleRoot(HttpExchange ex) throws IOException {
        byte[] page = readResource(EDITOR_INDEX);
        if (page == null) {
            send(ex, 200, "text/html; charset=utf-8",
                    "<!doctype html><meta charset=\"utf-8\"><h1>Lorebench</h1><p>Editor build not found.</p>");
            return;
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, page.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(page);
        }
    }

    private static void getOnly(HttpExchange ex, String json) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        send(ex, 200, JSON, json);
    }

    private void handlePublish(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            runtime.publish(body);
            send(ex, 200, JSON, "{\"accepted\":true}");
        } catch (DocumentException e) {
            LOGGER.warn("[Lorebench] Publish rejected: {}", e.errors());
            send(ex, 400, JSON, rejected(e.errors()));
        } catch (RuntimeException e) {
            LOGGER.error("[Lorebench] Publish failed", e);
            send(ex, 500, JSON, rejected(List.of("server error: " + e.getMessage())));
        }
    }

    /** Answer a GET with something read from the game, on the server thread. */
    private static void fromGame(HttpExchange ex, Function<MinecraftServer, String> read) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        if (mc == null) {
            send(ex, 503, JSON, error("the server is not running"));
            return;
        }
        try {
            send(ex, 200, JSON, mc.submit(() -> read.apply(mc)).get(5, TimeUnit.SECONDS));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.warn("[Lorebench] Reading from the game failed", e);
            send(ex, 500, JSON, error("reading from the game failed"));
        }
    }

    /**
     * {@code {"players": [{"uuid", "name", "online", "state": "hidden|active|waiting|ready|done", "progress": {...},
     * "timesDeclined", "handedDay", "stage"}]}}: everyone on the quest, done with it or who turned it down, online or not.
     * {@code handedDay} only while they wait (0013); {@code stage} (the id of theirs, which the quest may no longer
     * have) only while they are on it (0015).
     */
    private static String questPlayers(MinecraftServer mc, String questId) {
        Quests quests = Quests.current();
        if (quests == null || questId == null) {
            return error("no quest given, or the server is not running");
        }
        JsonArray list = new JsonArray();
        for (Quests.Standing s : quests.standings(mc, questId)) {
            JsonObject p = new JsonObject();
            p.addProperty("uuid", s.player().toString());
            p.addProperty("name", s.name());
            p.addProperty("online", s.online());
            p.addProperty("state", s.state().out);
            JsonObject progress = new JsonObject();
            s.progress().forEach(progress::addProperty);
            p.add("progress", progress);
            p.addProperty("timesDeclined", s.timesDeclined());
            if (s.handedDay() >= 0) {
                p.addProperty("handedDay", s.handedDay());
            }
            if (!s.stage().isEmpty()) {
                p.addProperty("stage", s.stage());
            }
            list.add(p);
        }
        JsonObject o = new JsonObject();
        o.add("players", list);
        return o.toString();
    }

    /** {@code {"quest": "quest_…", "player": "<uuid>"}} → that player is back to before the quest. */
    private void handleQuestReset(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) {
            send(ex, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            return;
        }
        String questId;
        UUID player;
        try {
            JsonObject body = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            questId = body.get("quest").getAsString();
            player = UUID.fromString(body.get("player").getAsString());
        } catch (RuntimeException e) {
            send(ex, 400, JSON, error("needs {\"quest\": <quest id>, \"player\": <uuid>}"));
            return;
        }
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        Quests quests = Quests.current();
        if (mc == null || quests == null) {
            send(ex, 503, JSON, error("the server is not running"));
            return;
        }
        try {
            mc.submit(() -> quests.forget(mc, player, questId)).get(5, TimeUnit.SECONDS);
            LOGGER.info("[Lorebench] Editor reset quest {} for player {}", questId, player);
            send(ex, 200, JSON, "{\"ok\":true}");
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.warn("[Lorebench] Resetting a quest failed", e);
            send(ex, 500, JSON, error("resetting the quest failed"));
        }
    }

    /** {@code {"players": ["Dev1", "Dev2"]}}: who is online, for picking whose hand to read. */
    private static String players(MinecraftServer mc) {
        JsonArray names = new JsonArray();
        mc.getPlayerList().getPlayers().forEach(p -> names.add(p.getGameProfile().getName()));
        JsonObject o = new JsonObject();
        o.add("players", names);
        return o.toString();
    }

    /**
     * {@code {"crops": [{"id": "minecraft:wheat", "name": "Wheat Crops"}]}}: every crop in this
     * game a harvest goal may name ({@link Crops}), including other mods' crops built the same way.
     */
    private static String crops(MinecraftServer mc) {
        JsonArray list = new JsonArray();
        for (Block block : Crops.all()) {
            JsonObject c = new JsonObject();
            c.addProperty("id", Crops.id(block));
            c.addProperty("name", block.getName().getString());
            list.add(c);
        }
        JsonObject o = new JsonObject();
        o.add("crops", list);
        return o.toString();
    }

    /**
     * {@code {"animals": [{"id": "minecraft:cow", "name": "Cow"}]}}: every animal in this game
     * a breed goal may name ({@link Breeding}), including other mods' animals.
     */
    private static String animals(MinecraftServer mc) {
        JsonArray list = new JsonArray();
        for (EntityType<?> type : Breeding.all(mc.overworld())) {
            JsonObject a = new JsonObject();
            a.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
            a.addProperty("name", type.getDescription().getString());
            list.add(a);
        }
        JsonObject o = new JsonObject();
        o.add("animals", list);
        return o.toString();
    }

    /**
     * {@code {"item": "minecraft:iron_sword[...]"}}: what that player holds in their main
     * hand, as {@code /give} writes it, with every component. Or {@code {"error": ...}}.
     */
    private static String heldItem(MinecraftServer mc, String name) {
        ServerPlayer player = (name == null) ? null : mc.getPlayerList().getPlayerByName(name);
        if (player == null) {
            return error("'" + name + "' is not online");
        }
        String item = Quests.held(player);
        if (item.isEmpty()) {
            return error(name + " is holding nothing");
        }
        JsonObject o = new JsonObject();
        o.addProperty("item", item);
        return o.toString();
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o.toString();
    }

    /** One query parameter, decoded; {@code null} if absent. */
    private static String query(HttpExchange ex, String key) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String rejected(List<String> errors) {
        JsonArray arr = new JsonArray();
        errors.forEach(arr::add);
        JsonObject o = new JsonObject();
        o.addProperty("accepted", false);
        o.add("errors", arr);
        return o.toString();
    }

    private byte[] readResource(String path) {
        try (InputStream in = LorebenchWebServer.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            LOGGER.error("[Lorebench] Failed to read {}", path, e);
            return null;
        }
    }

    private static void send(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
