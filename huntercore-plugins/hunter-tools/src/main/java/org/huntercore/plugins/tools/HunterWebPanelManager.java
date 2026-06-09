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
import java.util.Set;
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
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.conversations.Conversation;
import org.bukkit.conversations.ConversationAbandonedEvent;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

final class HunterWebPanelManager {
    private static final String SESSION_COOKIE = "HCSESSION";
    private static final String CSRF_HEADER = "X-HunterCore-CSRF";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final List<String> MODULES = List.of("tps-display", "sidebar", "essentials", "management", "fake-players", "npcs", "web-panel");
    private static final Map<String, List<String>> MODULE_COMMANDS = Map.of(
        "essentials", HunterToolsPreferences.essentialsCommands(),
        "management", HunterToolsPreferences.managementCommands(),
        "fake-players", HunterToolsPreferences.actorCommands(),
        "npcs", HunterToolsPreferences.actorCommands()
    );

    private final HunterToolsPlugin plugin;
    private final HunterToolsPreferences preferences;
    private final Map<String, WebSession> sessions = new ConcurrentHashMap<>();
    private HttpServer server;
    private ExecutorService executor;
    private volatile CachedResponse guestStatusCache;

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
        this.guestStatusCache = null;
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
            if (path.equals("/api/admin/module")) {
                this.requireMethod(exchange, "POST");
                this.adminModule(exchange);
                return;
            }
            if (path.equals("/api/admin/command")) {
                this.requireMethod(exchange, "POST");
                this.adminCommand(exchange);
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
        if (!detailed) {
            final CachedResponse cached = this.guestStatusCache;
            if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) {
                return cached.body();
            }
        }

        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        final String json = Bukkit.getScheduler().callSyncMethod(this.plugin, () -> this.buildStatusJson(session, detailed)).get(timeout, TimeUnit.SECONDS);
        if (!detailed) {
            final int cacheMillis = Math.max(0, this.preferences.intValue("modules.web-panel.status-cache-millis", 1000));
            if (cacheMillis > 0) {
                this.guestStatusCache = new CachedResponse(json, System.currentTimeMillis() + cacheMillis);
            }
        }
        return json;
    }

    private String buildStatusJson(final WebSession session, final boolean detailed) {
        final MetricsSnapshot snapshot = this.plugin.metricsSnapshot();
        final List<WorldPanelStats> worlds = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            worlds.add(new WorldPanelStats(
                world.getName(),
                world.getPlayers().size(),
                world.getLoadedChunks().length,
                world.getEntityCount(),
                world.getTime()
            ));
        }

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

        json.append(",\"optimization\":{");
        numberField(json, "cpuThreads", Runtime.getRuntime().availableProcessors()).append(',');
        field(json, "paperWorkers", System.getProperty("Paper.WorkerThreadCount", "auto")).append(',');
        field(json, "divineWorkers", System.getProperty("DivineMC.WorkerThreadCount", "auto")).append(',');
        field(json, "nettyIoThreads", System.getProperty("io.netty.eventLoopThreads", "auto")).append(',');
        field(json, "forkJoinParallelism", System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "auto")).append(',');
        numberField(json, "hunterToolsWorkers", this.preferences.intValue("optimizations.hunter-tools.render-workers", 4)).append(',');
        booleanField(json, "asyncActorLoad", this.preferences.booleanValue("optimizations.hunter-tools.actor-async-load", true)).append(',');
        booleanField(json, "actorBatchSave", this.preferences.booleanValue("optimizations.hunter-tools.actor-batch-save", true)).append(',');
        numberField(json, "webPanelWorkers", this.preferences.intValue("optimizations.hunter-tools.web-panel-workers", 4)).append(',');
        numberField(json, "guestStatusCacheMillis", this.preferences.intValue("modules.web-panel.status-cache-millis", 1000)).append(',');
        numberField(json, "bundledPluginInstallWorkers", this.preferences.intValue("optimizations.bundled-plugin-parallel-install.max-workers", 4));
        json.append('}');

        json.append(",\"health\":").append(this.healthJson(snapshot, worlds, detailed));

        json.append(",\"worlds\":[");
        boolean first = true;
        for (final WorldPanelStats world : worlds) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "name", world.name()).append(',');
            numberField(json, "players", world.players()).append(',');
            numberField(json, "loadedChunks", world.loadedChunks()).append(',');
            numberField(json, "entities", world.entities()).append(',');
            numberField(json, "time", world.time());
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
        if (session != null && session.admin()) {
            json.append(",\"modules\":").append(this.modulesJson());
        }
        json.append('}');
        return json.toString();
    }

    private String healthJson(final MetricsSnapshot snapshot, final List<WorldPanelStats> worlds, final boolean detailed) {
        final StringBuilder json = new StringBuilder(768);
        final double memoryPercent = memoryUsagePercent(snapshot);
        if (!this.preferences.booleanValue("modules.web-panel.health.enabled", true)) {
            return "{\"status\":\"disabled\",\"memoryUsagePercent\":" + String.format(Locale.ROOT, "%.3f", memoryPercent) + ",\"alerts\":[]}";
        }

        final List<HealthAlert> alerts = this.healthAlerts(snapshot, worlds, memoryPercent, detailed);
        final String status = alerts.stream().anyMatch(alert -> alert.severity().equals("critical"))
            ? "critical"
            : alerts.isEmpty() ? "ok" : "warning";
        json.append('{');
        field(json, "status", status).append(',');
        numberField(json, "memoryUsagePercent", memoryPercent).append(',');
        json.append("\"alerts\":[");
        boolean first = true;
        for (final HealthAlert alert : alerts) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "severity", alert.severity()).append(',');
            field(json, "label", alert.label()).append(',');
            field(json, "detail", alert.detail());
            json.append('}');
        }
        json.append("]}");
        return json.toString();
    }

    private List<HealthAlert> healthAlerts(
        final MetricsSnapshot snapshot,
        final List<WorldPanelStats> worlds,
        final double memoryPercent,
        final boolean detailed
    ) {
        final List<HealthAlert> alerts = new ArrayList<>();
        final double lowTpsWarning = this.preferences.doubleValue("modules.web-panel.health.low-tps-warning", 18.0D);
        final double lowTpsCritical = this.preferences.doubleValue("modules.web-panel.health.low-tps-critical", 15.0D);
        final double highMsptWarning = this.preferences.doubleValue("modules.web-panel.health.high-mspt-warning", 50.0D);
        final double highMsptCritical = this.preferences.doubleValue("modules.web-panel.health.high-mspt-critical", 75.0D);
        final double memoryWarning = this.preferences.doubleValue("modules.web-panel.health.memory-warning-percent", 85.0D);
        final double memoryCritical = this.preferences.doubleValue("modules.web-panel.health.memory-critical-percent", 95.0D);
        final int chunkWarning = Math.max(1, this.preferences.intValue("modules.web-panel.health.loaded-chunks-warning", 12_000));
        final int entityWarning = Math.max(1, this.preferences.intValue("modules.web-panel.health.entities-warning", 4_000));

        if (snapshot.tps1() <= lowTpsCritical) {
            alerts.add(new HealthAlert("critical", "Low TPS", "1m TPS is " + MetricsSnapshot.formatTps(snapshot.tps1()) + "."));
        } else if (snapshot.tps1() <= lowTpsWarning) {
            alerts.add(new HealthAlert("warning", "Low TPS", "1m TPS is " + MetricsSnapshot.formatTps(snapshot.tps1()) + "."));
        }

        if (snapshot.mspt() >= highMsptCritical) {
            alerts.add(new HealthAlert("critical", "High MSPT", String.format(Locale.ROOT, "Average tick time is %.1f ms.", snapshot.mspt())));
        } else if (snapshot.mspt() >= highMsptWarning) {
            alerts.add(new HealthAlert("warning", "High MSPT", String.format(Locale.ROOT, "Average tick time is %.1f ms.", snapshot.mspt())));
        }

        if (memoryPercent >= memoryCritical) {
            alerts.add(new HealthAlert("critical", "Memory pressure", String.format(Locale.ROOT, "%.1f%% of max heap is in use.", memoryPercent)));
        } else if (memoryPercent >= memoryWarning) {
            alerts.add(new HealthAlert("warning", "Memory pressure", String.format(Locale.ROOT, "%.1f%% of max heap is in use.", memoryPercent)));
        }

        for (final WorldPanelStats world : worlds) {
            if (world.loadedChunks() >= chunkWarning) {
                alerts.add(new HealthAlert("warning", "World chunks", world.name() + " has " + world.loadedChunks() + " loaded chunks."));
            }
            if (world.entities() >= entityWarning) {
                alerts.add(new HealthAlert("warning", "World entities", world.name() + " has " + world.entities() + " entities."));
            }
        }

        if (this.preferences.booleanValue("modules.web-panel.health.disabled-plugins-warning", true)) {
            final List<String> disabled = new ArrayList<>();
            for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
                if (!plugin.isEnabled()) {
                    disabled.add(plugin.getName());
                }
            }
            if (!disabled.isEmpty()) {
                final String detail = detailed
                    ? String.join(", ", disabled)
                    : disabled.size() + " plugin(s) are disabled.";
                alerts.add(new HealthAlert("warning", "Disabled plugins", detail));
            }
        }
        return alerts;
    }

    private static double memoryUsagePercent(final MetricsSnapshot snapshot) {
        if (snapshot.maxMemory() <= 0L) {
            return 0.0D;
        }
        return Math.min(100.0D, snapshot.usedMemory() * 100.0D / snapshot.maxMemory());
    }

    private String modulesJson() {
        final StringBuilder json = new StringBuilder(1024);
        json.append('[');
        boolean firstModule = true;
        for (final String module : MODULES) {
            if (!firstModule) {
                json.append(',');
            }
            firstModule = false;
            json.append('{');
            field(json, "name", module).append(',');
            booleanField(json, "enabled", this.preferences.moduleEnabled(module)).append(',');
            booleanField(json, "toggleable", !module.equals("web-panel")).append(',');
            json.append("\"commands\":[");
            boolean firstCommand = true;
            for (final String command : MODULE_COMMANDS.getOrDefault(module, List.of())) {
                if (!firstCommand) {
                    json.append(',');
                }
                firstCommand = false;
                json.append('{');
                field(json, "name", command).append(',');
                booleanField(json, "enabled", this.preferences.commandEnabled(module, command));
                json.append('}');
            }
            json.append("]}");
        }
        json.append(']');
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
            this.newToken(),
            user.id(),
            user.displayName(),
            role,
            user.commandExecution(),
            user.allowedCommandsConfigured(),
            List.copyOf(user.allowedCommands()),
            Instant.now().plusSeconds(minutes * 60L).toEpochMilli()
        );
        this.sessions.put(session.token(), session);
        exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (minutes * 60L));
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true,\"session\":" + sessionJson(session) + "}");
    }

    private void logout(final HttpExchange exchange) {
        final WebSession session = this.session(exchange);
        if (session != null) {
            if (!this.csrfAllowed(exchange, session)) {
                this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"csrf_required\"}");
                return;
            }
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
        if (!this.csrfAllowed(exchange, session)) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"csrf_required\"}");
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

        this.sendCommandResult(exchange, 200, this.dispatchConfiguredCommand(command));
    }

    private void adminModule(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String module = HunterToolsPreferences.normalize(body.getOrDefault("module", ""));
        final Boolean enabled = parseBoolean(body.get("enabled"));
        if (!MODULES.contains(module)) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_module\"}");
            return;
        }
        if (module.equals("web-panel")) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"self_protection\"}");
            return;
        }
        if (enabled == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_enabled\"}");
            return;
        }
        final CommandResult result = this.dispatchConfiguredCommand("hunteradmin module " + module + " " + onOff(enabled));
        this.guestStatusCache = null;
        this.sendCommandResult(exchange, 200, result);
    }

    private void adminCommand(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String module = HunterToolsPreferences.normalize(body.getOrDefault("module", ""));
        final String command = HunterToolsPreferences.normalize(body.getOrDefault("command", ""));
        final Boolean enabled = parseBoolean(body.get("enabled"));
        final List<String> commands = MODULE_COMMANDS.get(module);
        if (commands == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_command_module\"}");
            return;
        }
        if (!commands.contains(command)) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_command\"}");
            return;
        }
        if (enabled == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_enabled\"}");
            return;
        }
        final CommandResult result = this.dispatchConfiguredCommand("hunteradmin command " + module + " " + command + " " + onOff(enabled));
        this.guestStatusCache = null;
        this.sendCommandResult(exchange, 200, result);
    }

    private CommandResult dispatchConfiguredCommand(final String command) throws InterruptedException, ExecutionException, TimeoutException {
        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        final int maxLines = Math.max(1, this.preferences.intValue("modules.web-panel.command-output-lines", 80));
        final int maxChars = Math.max(256, this.preferences.intValue("modules.web-panel.command-output-chars", 12_000));
        return Bukkit.getScheduler().callSyncMethod(this.plugin, () -> this.dispatchWebCommand(command, maxLines, maxChars)).get(timeout, TimeUnit.SECONDS);
    }

    private void sendCommandResult(final HttpExchange exchange, final int status, final CommandResult result) {
        this.send(exchange, status, "application/json; charset=utf-8",
            "{\"ok\":true,\"dispatched\":" + result.dispatched()
                + ",\"message\":\"" + escapeJson(result.message()) + '"'
                + ",\"output\":\"" + escapeJson(result.output()) + "\"}");
    }

    private WebSession adminOperator(final HttpExchange exchange) {
        final WebSession session = this.session(exchange);
        if (session == null) {
            this.send(exchange, 401, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"login_required\"}");
            return null;
        }
        if (!this.csrfAllowed(exchange, session)) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"csrf_required\"}");
            return null;
        }
        if (!session.admin()) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"admin_required\"}");
            return null;
        }
        if (!this.commandAllowed(session, "hunteradmin")) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"command_denied\"}");
            return null;
        }
        return session;
    }

    private CommandResult dispatchWebCommand(final String command, final int maxLines, final int maxChars) {
        final CapturingConsoleCommandSender sender = new CapturingConsoleCommandSender(Bukkit.getConsoleSender(), maxLines, maxChars);
        final boolean dispatched = Bukkit.getServer().getCommandMap().dispatch(sender, command);
        final String output = sender.output();
        final String message;
        if (!dispatched) {
            message = "Command returned false.";
        } else if (output.isBlank()) {
            message = "Command dispatched as console. No command output was captured.";
        } else {
            message = "Command dispatched as console.";
        }
        return new CommandResult(dispatched, message, output);
    }

    private boolean commandAllowed(final WebSession session, final String command) {
        if (!session.commandExecution()) {
            return false;
        }

        final String root = commandRoot(command);
        if (session.admin()) {
            if (!this.preferences.booleanValue("modules.web-panel.admin-command-execution", true)) {
                return false;
            }
            return !session.allowedCommandsConfigured() || allowedCommand(session.allowedCommands(), root);
        }

        if (!this.preferences.booleanValue("modules.web-panel.player-command-execution", true)) {
            return false;
        }
        if (session.allowedCommandsConfigured()) {
            return allowedCommand(session.allowedCommands(), root);
        }
        return allowedCommand(this.preferences.stringList("modules.web-panel.player-allowed-commands", List.of()), root);
    }

    private boolean csrfAllowed(final HttpExchange exchange, final WebSession session) {
        if (!this.preferences.booleanValue("modules.web-panel.require-csrf", true)) {
            return true;
        }
        final String provided = exchange.getRequestHeaders().getFirst(CSRF_HEADER);
        return provided != null && MessageDigest.isEqual(
            session.csrfToken().getBytes(StandardCharsets.UTF_8),
            provided.getBytes(StandardCharsets.UTF_8)
        );
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
            + ",\"csrf\":\"" + escapeJson(session.csrfToken()) + '"'
            + ",\"commandExecution\":" + session.commandExecution()
            + ",\"allowedCommandsConfigured\":" + session.allowedCommandsConfigured()
            + ",\"allowedCommands\":" + stringArrayJson(session.allowedCommands())
            + ",\"expiresAt\":" + session.expiresAtMillis() + "}";
    }

    private static boolean allowedCommand(final List<String> allowedCommands, final String root) {
        for (final String value : allowedCommands) {
            final String allowed = commandRoot(value);
            if (allowed.equals("*") || allowed.equals(root)) {
                return true;
            }
        }
        return false;
    }

    private static String commandRoot(final String command) {
        return command.replaceFirst("^/+", "").trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static Boolean parseBoolean(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("on") || normalized.equals("yes") || normalized.equals("1")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("off") || normalized.equals("no") || normalized.equals("0")) {
            return false;
        }
        return null;
    }

    private static String onOff(final boolean enabled) {
        return enabled ? "on" : "off";
    }

    private static String stringArrayJson(final List<String> values) {
        final StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (final String value : values) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escapeJson(value)).append('"');
        }
        return json.append(']').toString();
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

    private record WebSession(
        String token,
        String csrfToken,
        String username,
        String displayName,
        String role,
        boolean commandExecution,
        boolean allowedCommandsConfigured,
        List<String> allowedCommands,
        long expiresAtMillis
    ) {
        boolean admin() {
            return this.role.equals("admin");
        }
    }

    private record CachedResponse(String body, long expiresAtMillis) {
    }

    private record CommandResult(boolean dispatched, String message, String output) {
    }

    private record WorldPanelStats(String name, int players, int loadedChunks, int entities, long time) {
    }

    private record HealthAlert(String severity, String label, String detail) {
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static final class CapturingConsoleCommandSender implements ConsoleCommandSender {
        private final ConsoleCommandSender delegate;
        private final int maxLines;
        private final int maxChars;
        private final List<String> lines = new ArrayList<>();
        private final Spigot spigot = new Spigot() {
            @Override
            public void sendMessage(final BaseComponent component) {
                CapturingConsoleCommandSender.this.capture(component.toLegacyText());
            }

            @Override
            public void sendMessage(final BaseComponent... components) {
                CapturingConsoleCommandSender.this.capture(new TextComponent(components).toLegacyText());
            }

            @Override
            public void sendMessage(final java.util.UUID sender, final BaseComponent component) {
                CapturingConsoleCommandSender.this.capture(component.toLegacyText());
            }

            @Override
            public void sendMessage(final java.util.UUID sender, final BaseComponent... components) {
                CapturingConsoleCommandSender.this.capture(new TextComponent(components).toLegacyText());
            }
        };
        private int chars;
        private boolean truncated;

        private CapturingConsoleCommandSender(final ConsoleCommandSender delegate, final int maxLines, final int maxChars) {
            this.delegate = delegate;
            this.maxLines = maxLines;
            this.maxChars = maxChars;
        }

        @Override
        public void sendMessage(final String message) {
            this.capture(message);
        }

        @Override
        public void sendMessage(final String... messages) {
            for (final String message : messages) {
                this.capture(message);
            }
        }

        @Override
        public void sendMessage(final java.util.UUID sender, final String message) {
            this.capture(message);
        }

        @Override
        public void sendMessage(final java.util.UUID sender, final String... messages) {
            for (final String message : messages) {
                this.capture(message);
            }
        }

        @Override
        public Server getServer() {
            return this.delegate.getServer();
        }

        @Override
        public String getName() {
            return "HunterCore Web Console";
        }

        @Override
        public net.kyori.adventure.text.Component name() {
            return net.kyori.adventure.text.Component.text(this.getName());
        }

        @Override
        public Spigot spigot() {
            return this.spigot;
        }

        @Override
        public boolean isPermissionSet(final String name) {
            return this.delegate.isPermissionSet(name);
        }

        @Override
        public boolean isPermissionSet(final Permission perm) {
            return this.delegate.isPermissionSet(perm);
        }

        @Override
        public boolean hasPermission(final String name) {
            return this.delegate.hasPermission(name);
        }

        @Override
        public boolean hasPermission(final Permission perm) {
            return this.delegate.hasPermission(perm);
        }

        @Override
        public PermissionAttachment addAttachment(final Plugin plugin, final String name, final boolean value) {
            return this.delegate.addAttachment(plugin, name, value);
        }

        @Override
        public PermissionAttachment addAttachment(final Plugin plugin) {
            return this.delegate.addAttachment(plugin);
        }

        @Override
        public PermissionAttachment addAttachment(final Plugin plugin, final String name, final boolean value, final int ticks) {
            return this.delegate.addAttachment(plugin, name, value, ticks);
        }

        @Override
        public PermissionAttachment addAttachment(final Plugin plugin, final int ticks) {
            return this.delegate.addAttachment(plugin, ticks);
        }

        @Override
        public void removeAttachment(final PermissionAttachment attachment) {
            this.delegate.removeAttachment(attachment);
        }

        @Override
        public void recalculatePermissions() {
            this.delegate.recalculatePermissions();
        }

        @Override
        public Set<PermissionAttachmentInfo> getEffectivePermissions() {
            return this.delegate.getEffectivePermissions();
        }

        @Override
        public boolean isOp() {
            return this.delegate.isOp();
        }

        @Override
        public void setOp(final boolean value) {
            this.delegate.setOp(value);
        }

        @Override
        public boolean isConversing() {
            return this.delegate.isConversing();
        }

        @Override
        public void acceptConversationInput(final String input) {
            this.delegate.acceptConversationInput(input);
        }

        @Override
        public boolean beginConversation(final Conversation conversation) {
            return this.delegate.beginConversation(conversation);
        }

        @Override
        public void abandonConversation(final Conversation conversation) {
            this.delegate.abandonConversation(conversation);
        }

        @Override
        public void abandonConversation(final Conversation conversation, final ConversationAbandonedEvent details) {
            this.delegate.abandonConversation(conversation, details);
        }

        @Override
        public void sendRawMessage(final String message) {
            this.capture(message);
        }

        @Override
        public void sendRawMessage(final java.util.UUID sender, final String message) {
            this.capture(message);
        }

        private void capture(final String message) {
            if (message == null || this.truncated) {
                return;
            }
            for (final String rawLine : message.split("\\R", -1)) {
                if (this.lines.size() >= this.maxLines) {
                    this.truncated = true;
                    return;
                }
                String line = ChatColor.stripColor(rawLine);
                if (line == null) {
                    line = rawLine;
                }
                final int remaining = this.maxChars - this.chars;
                if (remaining <= 0) {
                    this.truncated = true;
                    return;
                }
                if (line.length() > remaining) {
                    this.lines.add(line.substring(0, remaining));
                    this.chars += remaining;
                    this.truncated = true;
                    return;
                }
                this.lines.add(line);
                this.chars += line.length() + 1;
            }
        }

        private String output() {
            final String output = String.join("\n", this.lines);
            return this.truncated ? output + "\n[output truncated]" : output;
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
                <div class="sectionTitle">
                  <h2>Health</h2>
                  <strong id="healthStatus" class="healthBadge">--</strong>
                </div>
                <div id="healthList" class="list"></div>
              </article>
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
                <h2>Optimization</h2>
                <div id="optimizationList" class="list"></div>
              </article>
              <article id="opsPanel" hidden>
                <div class="sectionTitle">
                  <h2>Operations</h2>
                  <strong class="healthBadge">admin</strong>
                </div>
                <h3>Modules</h3>
                <div id="moduleControls" class="list"></div>
                <h3>Commands</h3>
                <div id="commandControls" class="list"></div>
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
        :root { color-scheme: dark; --bg:#0d1117; --panel:#161b22; --line:#30363d; --text:#e6edf3; --muted:#8b949e; --accent:#2f81f7; --good:#3fb950; --warn:#d29922; --bad:#f85149; }
        * { box-sizing: border-box; }
        body { margin:0; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background:var(--bg); color:var(--text); }
        .shell { width:min(1280px, calc(100% - 32px)); margin:0 auto; padding:24px 0 32px; }
        .topbar { display:flex; justify-content:space-between; gap:16px; align-items:center; padding-bottom:18px; border-bottom:1px solid var(--line); }
        h1, h2, h3, p { margin:0; }
        h1 { font-size:28px; }
        h2 { font-size:16px; margin-bottom:12px; }
        h3 { color:var(--muted); font-size:12px; font-weight:700; margin:12px 0 4px; text-transform:uppercase; }
        p, .muted { color:var(--muted); }
        input, button { height:36px; border-radius:6px; border:1px solid var(--line); background:#0d1117; color:var(--text); padding:0 10px; }
        button { background:var(--accent); border-color:var(--accent); font-weight:650; cursor:pointer; }
        .login, .command { display:flex; gap:8px; flex-wrap:wrap; }
        .metrics { display:grid; grid-template-columns:repeat(4, minmax(0,1fr)); gap:12px; margin:18px 0; }
        .metrics div, article, .mapPanel { background:var(--panel); border:1px solid var(--line); border-radius:8px; padding:14px; }
        .metrics span { display:block; color:var(--muted); font-size:12px; }
        .metrics strong { display:block; margin-top:4px; font-size:22px; }
        .grid { display:grid; grid-template-columns:repeat(2, minmax(0,1fr)); gap:12px; }
        .sectionTitle { display:flex; justify-content:space-between; align-items:center; gap:12px; margin-bottom:12px; }
        .sectionTitle h2 { margin-bottom:0; }
        .list { display:grid; gap:8px; }
        .item { display:flex; justify-content:space-between; gap:12px; padding:8px 0; border-top:1px solid var(--line); }
        .item:first-child { border-top:0; }
        .healthBadge { border:1px solid var(--line); border-radius:999px; padding:2px 8px; font-size:12px; text-transform:uppercase; }
        .healthBadge.ok { color:var(--good); border-color:color-mix(in srgb, var(--good) 55%, var(--line)); }
        .healthBadge.warning { color:var(--warn); border-color:color-mix(in srgb, var(--warn) 55%, var(--line)); }
        .healthBadge.critical { color:var(--bad); border-color:color-mix(in srgb, var(--bad) 55%, var(--line)); }
        .healthBadge.disabled { color:var(--muted); }
        .alert.warning strong { color:var(--warn); }
        .alert.critical strong { color:var(--bad); }
        .toggle input { width:18px; height:18px; padding:0; accent-color:var(--accent); }
        .toggle input:disabled { opacity:.45; cursor:not-allowed; }
        .commandGroup { display:grid; gap:6px; }
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
        const state = { session: null, csrf: '' };
        const $ = (id) => document.getElementById(id);
        const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (char) => ({
          '&': '&amp;',
          '<': '&lt;',
          '>': '&gt;',
          '"': '&quot;',
          "'": '&#39;'
        })[char]);
        const item = (left, right = '') => `<div class="item"><span>${esc(left)}</span><strong>${esc(right)}</strong></div>`;
        const toggleItem = (left, checked, attrs = '', disabled = false) =>
          `<label class="item toggle"><span>${esc(left)}</span><input type="checkbox" ${checked ? 'checked' : ''} ${disabled ? 'disabled' : ''} ${attrs}></label>`;
        const severityClass = (value) => ['ok', 'warning', 'critical', 'disabled'].includes(value) ? value : 'ok';
        const alertItem = (alert) => `<div class="item alert ${severityClass(alert.severity)}"><span>${esc(alert.label)}</span><strong>${esc(alert.detail)}</strong></div>`;

        async function json(url, options = {}) {
          const headers = { ...(options.headers || {}) };
          if (state.csrf) headers['X-HunterCore-CSRF'] = state.csrf;
          if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
          const response = await fetch(url, { credentials: 'same-origin', ...options, headers });
          return response.json();
        }

        async function refresh() {
          const data = await json('/api/status');
          state.session = data.session;
          state.csrf = data.session?.csrf || state.csrf;
          $('serverLine').textContent = `${data.server.name} · ${data.server.version}`;
          $('tps').textContent = Number(data.server.tps1).toFixed(2);
          $('mspt').textContent = Number(data.server.mspt).toFixed(1);
          $('players').textContent = `${data.server.online}/${data.server.maxPlayers}`;
          $('memory').textContent = data.server.memory;
          const health = data.health || { status: 'unknown', alerts: [] };
          $('healthStatus').textContent = health.status;
          $('healthStatus').className = `healthBadge ${severityClass(health.status)}`;
          $('healthList').innerHTML = health.alerts.length
            ? health.alerts.map(alertItem).join('')
            : `<p class="muted">No health alerts · Heap ${Number(health.memoryUsagePercent || 0).toFixed(1)}%</p>`;
          $('worlds').innerHTML = data.worlds.map(w => item(w.name, `${w.players} players · ${w.loadedChunks} chunks · ${w.entities} entities`)).join('');
          $('optimizationList').innerHTML = [
            item('CPU threads', data.optimization.cpuThreads),
            item('Paper workers', data.optimization.paperWorkers),
            item('DivineMC workers', data.optimization.divineWorkers),
            item('Netty IO', data.optimization.nettyIoThreads),
            item('ForkJoin', data.optimization.forkJoinParallelism),
            item('HunterTools workers', data.optimization.hunterToolsWorkers),
            item('Web workers', data.optimization.webPanelWorkers),
            item('Guest cache', `${data.optimization.guestStatusCacheMillis}ms`)
          ].join('');
          $('logoutButton').hidden = !state.session;
          $('loginForm').classList.toggle('isLoggedIn', !!state.session);
          if (data.players) $('playerList').innerHTML = data.players.map(p => item(p.name, `${p.world} · ${p.ping}ms`)).join('') || '<p class="muted">No players online.</p>';
          if (data.plugins) $('pluginList').innerHTML = data.plugins.map(p => item(p.name, p.enabled ? p.version : 'disabled')).join('');
          renderOperations(data.modules || []);
        }

        function renderOperations(modules) {
          const admin = Boolean(state.session?.admin && modules.length);
          $('opsPanel').hidden = !admin;
          if (!admin) return;
          $('moduleControls').innerHTML = modules
            .map(module => toggleItem(module.name, module.enabled, `data-module="${esc(module.name)}"`, !module.toggleable))
            .join('');
          $('commandControls').innerHTML = modules
            .filter(module => module.commands?.length)
            .map(module => `<div class="commandGroup"><h3>${esc(module.name)}</h3>${module.commands
              .map(command => toggleItem(command.name, command.enabled, `data-command-module="${esc(module.name)}" data-command="${esc(command.name)}"`))
              .join('')}</div>`)
            .join('');
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
          if (result.ok) {
            state.session = result.session;
            state.csrf = result.session.csrf || '';
          }
          $('commandResult').textContent = result.ok ? `Logged in as ${result.session.username} (${result.session.role}).` : 'Login failed.';
          await refresh();
        });

        $('logoutButton').addEventListener('click', async () => {
          await json('/api/logout', { method: 'POST' });
          state.session = null;
          state.csrf = '';
          $('commandResult').textContent = 'Logged out.';
          await refresh();
        });

        $('commandForm').addEventListener('submit', async (event) => {
          event.preventDefault();
          const payload = JSON.stringify({ command: $('commandInput').value });
          const result = await json('/api/command', { method: 'POST', body: payload });
          $('commandResult').textContent = result.ok ? `${result.message}${result.output ? `\\n\\n${result.output}` : ''}` : `Error: ${result.error}`;
          await refresh();
        });

        $('opsPanel').addEventListener('change', async (event) => {
          const target = event.target;
          if (!(target instanceof HTMLInputElement)) return;
          const endpoint = target.dataset.module ? '/api/admin/module' : '/api/admin/command';
          const payload = target.dataset.module
            ? { module: target.dataset.module, enabled: String(target.checked) }
            : { module: target.dataset.commandModule, command: target.dataset.command, enabled: String(target.checked) };
          const result = await json(endpoint, { method: 'POST', body: JSON.stringify(payload) });
          $('commandResult').textContent = result.ok ? `${result.message}${result.output ? `\\n\\n${result.output}` : ''}` : `Error: ${result.error}`;
          if (!result.ok) target.checked = !target.checked;
          await refresh();
        });

        refresh().catch(err => $('serverLine').textContent = err.message);
        refreshMap().catch(() => {});
        setInterval(refresh, 5000);
        """;
}
