package org.huntercore.plugins.tools;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class HunterWebPanelManager {
    private static final String SESSION_COOKIE = "HCSESSION";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;

    private final HunterToolsPlugin plugin;
    private final HunterToolsPreferences preferences;
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;

    HunterWebPanelManager(final HunterToolsPlugin plugin, final HunterToolsPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    synchronized void start() {
        this.stop();
        if (!this.preferences.moduleEnabled("web-panel")) {
            return;
        }

        final String bindAddress = this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1");
        final int port = Math.max(1, Math.min(65535, this.preferences.intValue("modules.web-panel.port", 8088)));
        try {
            this.server = HttpServer.create(new InetSocketAddress(bindAddress, port), 64);
        } catch (final IOException ex) {
            this.plugin.getLogger().severe("Failed to start HunterCore web panel on " + bindAddress + ":" + port + ": " + ex.getMessage());
            return;
        }

        this.executor = this.createExecutor();
        this.server.setExecutor(this.executor);
        this.server.createContext("/", this::handle);
        this.server.start();
        this.plugin.getLogger().info("HunterCore web panel listening on http://" + bindAddress + ":" + port + "/");
    }

    synchronized void restart() {
        this.start();
    }

    synchronized void stop() {
        if (this.server != null) {
            this.server.stop(0);
            this.server = null;
        }
        if (this.executor != null) {
            this.executor.shutdownNow();
            this.executor = null;
        }
    }

    boolean running() {
        return this.server != null;
    }

    String addressLine() {
        final String bindAddress = this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1");
        final int port = Math.max(1, Math.min(65535, this.preferences.intValue("modules.web-panel.port", 8088)));
        return "http://" + bindAddress + ":" + port + "/";
    }

    static String hashPassword(final String password) {
        final byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        final byte[] hash = pbkdf2(password.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS);
        return "pbkdf2$" + HASH_ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
    }

    static boolean verifyPassword(final String password, final String stored) {
        if (stored == null || stored.isBlank() || !stored.startsWith("pbkdf2$")) {
            return false;
        }
        final String[] parts = stored.split("\\$");
        if (parts.length != 4) {
            return false;
        }
        try {
            final int iterations = Integer.parseInt(parts[1]);
            final byte[] salt = Base64.getDecoder().decode(parts[2]);
            final byte[] expected = Base64.getDecoder().decode(parts[3]);
            final byte[] actual = pbkdf2(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (final IllegalArgumentException ex) {
            return false;
        }
    }

    private ExecutorService createExecutor() {
        final int configured = this.preferences.intValue("optimizations.hunter-tools.web-panel-workers", 4);
        final int workers = Math.max(1, Math.min(configured, Math.min(8, Runtime.getRuntime().availableProcessors())));
        final AtomicInteger id = new AtomicInteger();
        final ThreadFactory factory = task -> {
            final Thread thread = new Thread(task, "HunterTools web panel " + id.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(workers, factory);
    }

    private void handle(final HttpExchange exchange) {
        try {
            final URI uri = exchange.getRequestURI();
            final String path = normalizePath(uri.getPath());
            if (path.equals("/")) {
                this.send(exchange, 200, "text/html; charset=utf-8", INDEX_HTML);
                return;
            }
            if (path.equals("/assets/app.css")) {
                this.send(exchange, 200, "text/css; charset=utf-8", APP_CSS);
                return;
            }
            if (path.equals("/assets/app.js")) {
                this.send(exchange, 200, "application/javascript; charset=utf-8", APP_JS);
                return;
            }
            if (path.equals("/health")) {
                this.send(exchange, 200, "text/plain; charset=utf-8", "ok\n");
                return;
            }
            if (path.equals("/api/status")) {
                this.requireMethod(exchange, "GET");
                this.send(exchange, 200, "application/json; charset=utf-8", this.statusJson(exchange));
                return;
            }
            if (path.equals("/api/map")) {
                this.requireMethod(exchange, "GET");
                this.send(exchange, 200, "application/json; charset=utf-8", this.mapJson(exchange));
                return;
            }
            if (path.equals("/api/session")) {
                this.requireMethod(exchange, "GET");
                this.send(exchange, 200, "application/json; charset=utf-8", this.sessionJson(exchange));
                return;
            }
            if (path.equals("/api/login")) {
                this.requireMethod(exchange, "POST");
                this.login(exchange);
                return;
            }
            if (path.equals("/api/logout")) {
                this.requireMethod(exchange, "POST");
                this.logout(exchange);
                return;
            }
            if (path.equals("/api/command")) {
                this.requireMethod(exchange, "POST");
                this.command(exchange);
                return;
            }
            this.send(exchange, 404, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"not_found\"}");
        } catch (final MethodMismatchException ex) {
            this.send(exchange, 405, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"method_not_allowed\"}");
        } catch (final Exception ex) {
            this.plugin.getLogger().warning("HunterCore web panel request failed: " + ex.getMessage());
            this.send(exchange, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal_error\"}");
        } finally {
            exchange.close();
        }
    }

    private String statusJson(final HttpExchange exchange) throws InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.session(exchange);
        final boolean detailed = session != null;
        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        return Bukkit.getScheduler().callSyncMethod(this.plugin, () -> this.buildStatusJson(session, detailed)).get(timeout, TimeUnit.SECONDS);
    }

    private String buildStatusJson(final WebSession session, final boolean detailed) {
        final MetricsSnapshot snapshot = this.plugin.metricsSnapshot();
        final StringBuilder json = new StringBuilder(2048);
        json.append("{\"ok\":true");
        json.append(",\"session\":").append(session == null ? "null" : sessionJson(session));
        json.append(",\"server\":{");
        field(json, "name", Bukkit.getName()).append(',');
        field(json, "version", Bukkit.getVersion()).append(',');
        numberField(json, "online", snapshot.onlinePlayers()).append(',');
        numberField(json, "maxPlayers", snapshot.maxPlayers()).append(',');
        numberField(json, "tps1", snapshot.tps1()).append(',');
        numberField(json, "tps5", snapshot.tps5()).append(',');
        numberField(json, "tps15", snapshot.tps15()).append(',');
        numberField(json, "mspt", snapshot.mspt()).append(',');
        field(json, "memory", snapshot.memoryLine());
        json.append('}');

        json.append(",\"actors\":{");
        numberField(json, "fakePlayers", this.plugin.actorLiveCount("fake-players")).append(',');
        numberField(json, "npcs", this.plugin.actorLiveCount("npcs"));
        json.append('}');

        json.append(",\"worlds\":[");
        boolean first = true;
        for (final World world : Bukkit.getWorlds()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "name", world.getName()).append(',');
            numberField(json, "players", world.getPlayers().size()).append(',');
            numberField(json, "loadedChunks", world.getLoadedChunks().length).append(',');
            numberField(json, "entities", world.getEntityCount()).append(',');
            numberField(json, "time", world.getTime());
            json.append('}');
        }
        json.append(']');

        if (detailed) {
            json.append(",\"players\":[");
            first = true;
            for (final Player player : Bukkit.getOnlinePlayers()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('{');
                field(json, "name", player.getName()).append(',');
                field(json, "world", player.getWorld().getName()).append(',');
                numberField(json, "ping", player.getPing());
                json.append('}');
            }
            json.append(']');

            json.append(",\"plugins\":[");
            first = true;
            for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('{');
                field(json, "name", plugin.getName()).append(',');
                field(json, "version", plugin.getPluginMeta().getVersion()).append(',');
                booleanField(json, "enabled", plugin.isEnabled());
                json.append('}');
            }
            json.append(']');
        }
        json.append('}');
        return json.toString();
    }

    private String mapJson(final HttpExchange exchange) {
        final boolean publicMap = this.preferences.booleanValue("modules.web-panel.public-map", true);
        if (!publicMap && this.session(exchange) == null) {
            return "{\"ok\":false,\"error\":\"login_required\"}";
        }
        final String rawUrl = this.preferences.stringValue("modules.web-panel.map-url", "http://%host%:8100/");
        final String host = requestHost(exchange);
        final String mapUrl = rawUrl.replace("%host%", host);
        return "{\"ok\":true,\"public\":" + publicMap + ",\"url\":\"" + escapeJson(mapUrl) + "\"}";
    }

    private String sessionJson(final HttpExchange exchange) {
        final WebSession session = this.session(exchange);
        return "{\"ok\":true,\"session\":" + (session == null ? "null" : sessionJson(session)) + "}";
    }

    private void login(final HttpExchange exchange) throws IOException {
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String username = body.getOrDefault("username", "");
        final String password = body.getOrDefault("password", "");
        final HunterToolsPreferences.WebUser user = this.preferences.webUser(username);
        if (user == null || !user.passwordConfigured() || !verifyPassword(password, user.passwordHash())) {
            this.send(exchange, 401, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_login\"}");
            return;
        }
        final String role = normalizeRole(user.role());
        final long minutes = Math.max(5L, this.preferences.intValue("modules.web-panel.session-minutes", 360));
        final WebSession session = new WebSession(
            this.newToken(),
            user.id(),
            user.displayName(),
            role,
            Instant.now().plusSeconds(minutes * 60L).toEpochMilli()
        );
        this.sessions.put(session.token(), session);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (minutes * 60L));
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true,\"session\":" + sessionJson(session) + "}");
    }

    private void logout(final HttpExchange exchange) {
        final WebSession session = this.session(exchange);
        if (session != null) {
            this.sessions.remove(session.token());
        }
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void command(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.session(exchange);
        if (session == null) {
            this.send(exchange, 401, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"login_required\"}");
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String command = body.getOrDefault("command", "").replaceFirst("^/+", "").trim();
        if (command.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"empty_command\"}");
            return;
        }
        if (!this.commandAllowed(session, command)) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"command_denied\"}");
            return;
        }

        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        final boolean dispatched = Bukkit.getScheduler().callSyncMethod(this.plugin, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)).get(timeout, TimeUnit.SECONDS);
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true,\"dispatched\":" + dispatched + ",\"message\":\"Command dispatched as console.\"}");
    }

    private boolean commandAllowed(final WebSession session, final String command) {
        if (session.admin()) {
            return this.preferences.booleanValue("modules.web-panel.admin-command-execution", true);
        }
        if (!this.preferences.booleanValue("modules.web-panel.player-command-execution", true)) {
            return false;
        }
        final String root = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        final List<String> allowed = new ArrayList<>();
        for (final String value : this.preferences.stringList("modules.web-panel.player-allowed-commands", List.of())) {
            allowed.add(value.toLowerCase(Locale.ROOT));
        }
        return allowed.contains(root);
    }

    private WebSession session(final HttpExchange exchange) {
        final String token = cookie(exchange, SESSION_COOKIE);
        if (token == null || token.isBlank()) {
            return null;
        }
        final WebSession session = this.sessions.get(token);
        if (session == null) {
            return null;
        }
        if (session.expiresAtMillis() < System.currentTimeMillis()) {
            this.sessions.remove(token);
            return null;
        }
        return session;
    }

    private void requireMethod(final HttpExchange exchange, final String method) {
        if (!exchange.getRequestMethod().equalsIgnoreCase(method)) {
            throw new MethodMismatchException();
        }
    }

    private String body(final HttpExchange exchange, final int maxBytes) throws IOException {
        final byte[] bytes = exchange.getRequestBody().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IOException("request body too large");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void send(final HttpExchange exchange, final int status, final String type, final String body) {
        try {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            final Headers headers = exchange.getResponseHeaders();
            headers.set("Content-Type", type);
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (final IOException ignored) {
        }
    }

    private String newToken() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] pbkdf2(final char[] password, final byte[] salt, final int iterations, final int bits) {
        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            final KeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
            return factory.generateSecret(spec).getEncoded();
        } catch (final Exception ex) {
            throw new IllegalStateException("PBKDF2 is unavailable", ex);
        }
    }

    private static Map<String, String> parseJsonObject(final String json) {
        final Map<String, String> values = new HashMap<>();
        int index = 0;
        while (index < json.length()) {
            final int keyStart = json.indexOf('"', index);
            if (keyStart < 0) {
                break;
            }
            final int keyEnd = findStringEnd(json, keyStart + 1);
            if (keyEnd < 0) {
                break;
            }
            final String key = unescapeJson(json.substring(keyStart + 1, keyEnd));
            final int colon = json.indexOf(':', keyEnd + 1);
            if (colon < 0) {
                break;
            }
            final int valueStart = json.indexOf('"', colon + 1);
            if (valueStart < 0) {
                index = colon + 1;
                continue;
            }
            final int valueEnd = findStringEnd(json, valueStart + 1);
            if (valueEnd < 0) {
                break;
            }
            values.put(key, unescapeJson(json.substring(valueStart + 1, valueEnd)));
            index = valueEnd + 1;
        }
        return values;
    }

    private static int findStringEnd(final String json, final int start) {
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            final char c = json.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescapeJson(final String value) {
        return value
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    private static String normalizePath(final String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path;
    }

    private static String cookie(final HttpExchange exchange, final String name) {
        final List<String> cookies = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        for (final String header : cookies) {
            for (final String item : header.split(";")) {
                final String[] parts = item.trim().split("=", 2);
                if (parts.length == 2 && parts[0].equals(name)) {
                    return parts[1];
                }
            }
        }
        return null;
    }

    private static String requestHost(final HttpExchange exchange) {
        final String header = exchange.getRequestHeaders().getFirst("Host");
        if (header == null || header.isBlank()) {
            return "127.0.0.1";
        }
        final int colon = header.indexOf(':');
        return colon > 0 ? header.substring(0, colon) : header;
    }

    private static String sessionJson(final WebSession session) {
        return "{\"username\":\"" + escapeJson(session.displayName())
            + "\",\"role\":\"" + escapeJson(session.role())
            + "\",\"admin\":" + session.admin()
            + ",\"expiresAt\":" + session.expiresAtMillis() + "}";
    }

    private static StringBuilder field(final StringBuilder json, final String name, final String value) {
        json.append('"').append(escapeJson(name)).append("\":\"").append(escapeJson(value == null ? "" : value)).append('"');
        return json;
    }

    private static StringBuilder numberField(final StringBuilder json, final String name, final double value) {
        json.append('"').append(escapeJson(name)).append("\":").append(String.format(Locale.ROOT, "%.3f", value));
        return json;
    }

    private static StringBuilder numberField(final StringBuilder json, final String name, final long value) {
        json.append('"').append(escapeJson(name)).append("\":").append(value);
        return json;
    }

    private static StringBuilder booleanField(final StringBuilder json, final String name, final boolean value) {
        json.append('"').append(escapeJson(name)).append("\":").append(value);
        return json;
    }

    private static String escapeJson(final String value) {
        final StringBuilder builder = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (c < 0x20) {
                        builder.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
                }
            }
        }
        return builder.toString();
    }

    private static String normalizeRole(final String role) {
        final String normalized = role == null ? "player" : role.toLowerCase(Locale.ROOT);
        return normalized.equals("admin") ? "admin" : "player";
    }

    private record WebSession(String token, String username, String displayName, String role, long expiresAtMillis) {
        boolean admin() {
            return this.role.equals("admin");
        }
    }

    private static final class MethodMismatchException extends RuntimeException {
    }

    private static final String INDEX_HTML = """
        <!doctype html>
        <html lang="zh-CN">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>HunterCore Panel</title>
          <link rel="stylesheet" href="/assets/app.css">
        </head>
        <body>
          <main class="shell">
            <section class="topbar">
              <div>
                <h1>HunterCore</h1>
                <p id="serverLine">Loading server status...</p>
              </div>
              <form id="loginForm" class="login">
                <input id="username" name="username" autocomplete="username" placeholder="username">
                <input id="password" name="password" type="password" autocomplete="current-password" placeholder="password">
                <button type="submit">Login</button>
                <button id="logoutButton" type="button" hidden>Logout</button>
              </form>
            </section>
            <section class="metrics">
              <div><span>TPS</span><strong id="tps">--</strong></div>
              <div><span>MSPT</span><strong id="mspt">--</strong></div>
              <div><span>Players</span><strong id="players">--</strong></div>
              <div><span>Memory</span><strong id="memory">--</strong></div>
            </section>
            <section class="grid">
              <article>
                <h2>Worlds</h2>
                <div id="worlds" class="list"></div>
              </article>
              <article>
                <h2>Players</h2>
                <div id="playerList" class="list muted">Login to view player detail.</div>
              </article>
              <article>
                <h2>Plugins</h2>
                <div id="pluginList" class="list muted">Login to view plugin detail.</div>
              </article>
              <article>
                <h2>Command</h2>
                <form id="commandForm" class="command">
                  <input id="commandInput" placeholder="list">
                  <button type="submit">Run</button>
                </form>
                <pre id="commandResult">Login to run allowed commands.</pre>
              </article>
            </section>
            <section class="mapPanel">
              <div class="mapHeader">
                <h2>BlueMap</h2>
                <a id="mapLink" href="#" target="_blank" rel="noreferrer">Open map</a>
              </div>
              <iframe id="mapFrame" title="BlueMap"></iframe>
            </section>
          </main>
          <script src="/assets/app.js"></script>
        </body>
        </html>
        """;

    private static final String APP_CSS = """
        :root { color-scheme: dark; --bg:#0d1117; --panel:#161b22; --line:#30363d; --text:#e6edf3; --muted:#8b949e; --accent:#2f81f7; --good:#3fb950; --bad:#f85149; }
        * { box-sizing: border-box; }
        body { margin:0; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background:var(--bg); color:var(--text); }
        .shell { width:min(1280px, calc(100% - 32px)); margin:0 auto; padding:24px 0 32px; }
        .topbar { display:flex; justify-content:space-between; gap:16px; align-items:center; padding-bottom:18px; border-bottom:1px solid var(--line); }
        h1, h2, p { margin:0; }
        h1 { font-size:28px; }
        h2 { font-size:16px; margin-bottom:12px; }
        p, .muted { color:var(--muted); }
        input, button { height:36px; border-radius:6px; border:1px solid var(--line); background:#0d1117; color:var(--text); padding:0 10px; }
        button { background:var(--accent); border-color:var(--accent); font-weight:650; cursor:pointer; }
        .login, .command { display:flex; gap:8px; flex-wrap:wrap; }
        .metrics { display:grid; grid-template-columns:repeat(4, minmax(0,1fr)); gap:12px; margin:18px 0; }
        .metrics div, article, .mapPanel { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
        .metrics span { display:block; color:var(--muted); font-size:12px; }
        .metrics strong { display:block; margin-top:4px; font-size:22px; }
        .grid { display:grid; grid-template-columns:repeat(2, minmax(0,1fr)); gap:12px; }
        .list { display:grid; gap:8px; }
        .item { display:flex; justify-content:space-between; gap:12px; padding:8px 0; border-top:1px solid var(--line); }
        .item:first-child { border-top:0; }
        pre { min-height:72px; white-space:pre-wrap; color:var(--muted); }
        .mapPanel { margin-top:12px; padding:0; overflow:hidden; }
        .mapHeader { display:flex; justify-content:space-between; align-items:center; padding:14px; border-bottom:1px solid var(--line); }
        a { color:#58a6ff; }
        iframe { display:block; width:100%; height:min(70vh, 720px); border:0; background:#0d1117; }
        @media (max-width: 800px) {
          .topbar, .grid { grid-template-columns:1fr; display:grid; }
          .metrics { grid-template-columns:repeat(2, minmax(0,1fr)); }
        }
        """;

    private static final String APP_JS = """
        const state = { session: null };
        const $ = (id) => document.getElementById(id);
        const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
          '&': '&amp;',
          '<': '&lt;',
          '>': '&gt;',
          '"': '&quot;',
          "'": '&#39;'
        })[char]);
        const item = (left, right = '') => `<div class="item"><span>${esc(left)}</span><strong>${esc(right)}</strong></div>`;

        async function json(url, options = {}) {
          const response = await fetch(url, { credentials: 'same-origin', ...options });
          return response.json();
        }

        async function refresh() {
          const data = await json('/api/status');
          state.session = data.session;
          $('serverLine').textContent = `${data.server.name} · ${data.server.version}`;
          $('tps').textContent = Number(data.server.tps1).toFixed(2);
          $('mspt').textContent = Number(data.server.mspt).toFixed(1);
          $('players').textContent = `${data.server.online}/${data.server.maxPlayers}`;
          $('memory').textContent = data.server.memory;
          $('worlds').innerHTML = data.worlds.map(w => item(w.name, `${w.players} players · ${w.loadedChunks} chunks`)).join('');
          $('logoutButton').hidden = !state.session;
          $('loginForm').classList.toggle('isLoggedIn', !!state.session);
          if (data.players) $('playerList').innerHTML = data.players.map(p => item(p.name, `${p.world} · ${p.ping}ms`)).join('') || '<p class="muted">No players online.</p>';
          if (data.plugins) $('pluginList').innerHTML = data.plugins.map(p => item(p.name, p.enabled ? p.version : 'disabled')).join('');
        }

        async function refreshMap() {
          const map = await json('/api/map');
          if (map.ok) {
            $('mapLink').href = map.url;
            $('mapFrame').src = map.url;
          }
        }

        $('loginForm').addEventListener('submit', async (event) => {
          event.preventDefault();
          const payload = JSON.stringify({ username: $('username').value, password: $('password').value });
          const result = await json('/api/login', { method: 'POST', body: payload });
          $('password').value = '';
          $('commandResult').textContent = result.ok ? `Logged in as ${result.session.username} (${result.session.role}).` : 'Login failed.';
          await refresh();
        });

        $('logoutButton').addEventListener('click', async () => {
          await json('/api/logout', { method: 'POST' });
          $('commandResult').textContent = 'Logged out.';
          await refresh();
        });

        $('commandForm').addEventListener('submit', async (event) => {
          event.preventDefault();
          const payload = JSON.stringify({ command: $('commandInput').value });
          const result = await json('/api/command', { method: 'POST', body: payload });
          $('commandResult').textContent = result.ok ? result.message : `Error: ${result.error}`;
          await refresh();
        });

        refresh().catch(err => $('serverLine').textContent = err.message);
        refreshMap().catch(() => {});
        setInterval(refresh, 5000);
        """;
}
