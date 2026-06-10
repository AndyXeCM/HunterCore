package org.huntercore.plugins.tools;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(15L))
        .build();
    private static final int HASH_ITERATIONS = 120_000;
    private static final int HASH_BITS = 256;
    private static final List<String> MODULES = List.of("tps-display", "sidebar", "motd", "command-overrides", "essentials", "management", "fake-players", "real-fake-players", "npcs", "web-panel");
    private static final Map<String, List<String>> MODULE_COMMANDS = Map.of(
        "essentials", HunterToolsPreferences.essentialsCommands(),
        "management", HunterToolsPreferences.managementCommands(),
        "real-fake-players", HunterToolsPreferences.realFakePlayerCommands(),
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

    private static boolean verifyHunterAuthPassword(final String username, final String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            return false;
        }
        final Path usersPath = Bukkit.getPluginsFolder().toPath().resolve("HunterAuth").resolve("users.yml");
        if (!Files.isRegularFile(usersPath)) {
            return false;
        }
        final YamlConfiguration users = YamlConfiguration.loadConfiguration(usersPath.toFile());
        final ConfigurationSection section = users.getConfigurationSection("users");
        if (section == null) {
            return false;
        }
        for (final String id : section.getKeys(false)) {
            final String path = "users." + id;
            final String name = users.getString(path + ".name", "");
            if (!id.equalsIgnoreCase(username) && !name.equalsIgnoreCase(username)) {
                continue;
            }
            final String salt = users.getString(path + ".salt");
            final String expectedHash = users.getString(path + ".hash");
            if (salt == null || expectedHash == null) {
                return false;
            }
            try {
                final byte[] expected = Base64.getDecoder().decode(expectedHash);
                final byte[] actual = pbkdf2(password.toCharArray(), Base64.getDecoder().decode(salt), HASH_ITERATIONS, HASH_BITS);
                return MessageDigest.isEqual(expected, actual);
            } catch (final IllegalArgumentException ex) {
                return false;
            }
        }
        return false;
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
                this.send(exchange, 200, "text/html; charset=utf-8", webAsset("index.html", INDEX_HTML));
                return;
            }
            if (path.equals("/assets/app.css")) {
                this.send(exchange, 200, "text/css; charset=utf-8", webAsset("app.css", APP_CSS));
                return;
            }
            if (path.equals("/assets/app.js")) {
                this.send(exchange, 200, "application/javascript; charset=utf-8", webAsset("app.js", APP_JS));
                return;
            }
            if (path.equals("/assets/panel-bg.jpg")) {
                this.sendBytes(exchange, 200, "image/jpeg", webAssetBytes("panel-bg.jpg"));
                return;
            }
            if (path.equals("/assets/server-icon.png")) {
                this.sendServerIcon(exchange);
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
            if (path.equals("/api/admin/actor/spawn")) {
                this.requireMethod(exchange, "POST");
                this.adminActorSpawn(exchange);
                return;
            }
            if (path.equals("/api/admin/actor/remove")) {
                this.requireMethod(exchange, "POST");
                this.adminActorRemove(exchange);
                return;
            }
            if (path.equals("/api/admin/actor/click-command")) {
                this.requireMethod(exchange, "POST");
                this.adminActorClickCommand(exchange);
                return;
            }
            if (path.equals("/api/admin/web-user/save")) {
                this.requireMethod(exchange, "POST");
                this.adminWebUserSave(exchange);
                return;
            }
            if (path.equals("/api/admin/web-user/remove")) {
                this.requireMethod(exchange, "POST");
                this.adminWebUserRemove(exchange);
                return;
            }
            if (path.equals("/api/admin/luckperms")) {
                this.requireMethod(exchange, "POST");
                this.adminLuckPerms(exchange);
                return;
            }
            if (path.equals("/api/admin/web-settings")) {
                this.requireMethod(exchange, "POST");
                this.adminWebSettings(exchange);
                return;
            }
            if (path.equals("/api/admin/command-messages")) {
                this.requireMethod(exchange, "POST");
                this.adminCommandMessages(exchange);
                return;
            }
            if (path.equals("/api/admin/plugin")) {
                this.requireMethod(exchange, "POST");
                this.adminPlugin(exchange);
                return;
            }
            if (path.equals("/api/admin/plugin-update")) {
                this.requireMethod(exchange, "POST");
                this.adminPluginUpdate(exchange);
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
        field(json, "name", this.webServerName()).append(',');
        field(json, "software", Bukkit.getName()).append(',');
        field(json, "iconUrl", "/assets/server-icon.png").append(',');
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
        numberField(json, "realFakePlayers", this.plugin.actorLiveCount("real-fake-players")).append(',');
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

            json.append(",\"plugins\":").append(this.pluginsJson());
        }
        if (session != null && session.admin()) {
            json.append(",\"modules\":").append(this.modulesJson());
            json.append(",\"actorDetails\":").append(this.actorDetailsJson());
            json.append(",\"webUsers\":").append(this.webUsersJson());
            json.append(",\"webSettings\":").append(this.webSettingsJson());
            json.append(",\"commandMessages\":").append(this.commandMessagesJson());
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

    private String webUsersJson() {
        final StringBuilder json = new StringBuilder(1024);
        json.append('[');
        boolean first = true;
        for (final String id : this.preferences.webUserIds()) {
            final HunterToolsPreferences.WebUser user = this.preferences.webUser(id);
            if (user == null) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "id", user.id()).append(',');
            field(json, "displayName", user.displayName()).append(',');
            field(json, "role", normalizeRole(user.role())).append(',');
            booleanField(json, "admin", normalizeRole(user.role()).equals("admin")).append(',');
            booleanField(json, "passwordConfigured", user.passwordConfigured()).append(',');
            booleanField(json, "commandExecution", user.commandExecution()).append(',');
            booleanField(json, "allowedCommandsConfigured", user.allowedCommandsConfigured()).append(',');
            json.append("\"allowedCommands\":").append(stringArrayJson(user.allowedCommands()));
            json.append('}');
        }
        json.append(']');
        return json.toString();
    }

    private String pluginsJson() {
        final Map<String, PluginJarScan> scannedJars = this.pluginJarScanLookup();
        final Map<String, Plugin> loadedPlugins = new HashMap<>();
        for (final Plugin installedPlugin : Bukkit.getPluginManager().getPlugins()) {
            loadedPlugins.put(installedPlugin.getName().toLowerCase(Locale.ROOT), installedPlugin);
        }

        final StringBuilder json = new StringBuilder(2048);
        json.append('[');
        boolean first = true;
        for (final Plugin installedPlugin : Bukkit.getPluginManager().getPlugins()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            final PluginJarScan scan = scannedJars.get(installedPlugin.getName().toLowerCase(Locale.ROOT));
            this.appendPluginJson(json, installedPlugin, scan);
        }

        final List<PluginJarScan> unloaded = new ArrayList<>();
        for (final PluginJarScan scan : scannedJars.values()) {
            if (!loadedPlugins.containsKey(scan.metadata().name().toLowerCase(Locale.ROOT))) {
                unloaded.add(scan);
            }
        }
        unloaded.sort((left, right) -> left.metadata().name().compareToIgnoreCase(right.metadata().name()));
        for (final PluginJarScan scan : unloaded) {
            if (!first) {
                json.append(',');
            }
            first = false;
            this.appendPluginJson(json, null, scan);
        }
        json.append(']');
        return json.toString();
    }

    private void appendPluginJson(final StringBuilder json, final Plugin installedPlugin, final PluginJarScan scan) {
        final String name = installedPlugin == null ? scan.metadata().name() : installedPlugin.getName();
        final String version = installedPlugin == null ? scan.metadata().version() : installedPlugin.getPluginMeta().getVersion();
        final boolean loaded = installedPlugin != null;
        final boolean enabled = installedPlugin != null && installedPlugin.isEnabled();
        final boolean protectedPlugin = this.protectedPlugin(name);
        json.append('{');
        field(json, "name", name).append(',');
        field(json, "version", version).append(',');
        field(json, "sourceJar", scan == null ? "" : scan.path().getFileName().toString()).append(',');
        field(json, "descriptor", scan == null ? "" : scan.metadata().descriptor()).append(',');
        field(json, "status", loaded ? (enabled ? "enabled" : "disabled") : "installed").append(',');
        booleanField(json, "loaded", loaded).append(',');
        booleanField(json, "enabled", enabled).append(',');
        booleanField(json, "controllable", !protectedPlugin).append(',');
        booleanField(json, "updateable", !protectedPlugin);
        json.append('}');
    }

    private String actorDetailsJson() {
        final StringBuilder json = new StringBuilder(1024);
        json.append('[');
        boolean first = true;
        for (final String module : List.of("fake-players", "npcs")) {
            for (final HunterActorManager.ActorView actor : this.plugin.actorViews(module)) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('{');
                field(json, "module", actor.module()).append(',');
                field(json, "id", actor.id()).append(',');
                field(json, "displayName", actor.displayName()).append(',');
                field(json, "kind", actor.kind()).append(',');
                field(json, "world", actor.world()).append(',');
                numberField(json, "x", actor.x()).append(',');
                numberField(json, "y", actor.y()).append(',');
                numberField(json, "z", actor.z()).append(',');
                numberField(json, "yaw", actor.yaw()).append(',');
                numberField(json, "pitch", actor.pitch()).append(',');
                field(json, "pose", actor.pose()).append(',');
                field(json, "clickCommand", actor.clickCommand()).append(',');
                booleanField(json, "live", actor.live()).append(',');
                field(json, "entityUuid", actor.entityUuid());
                json.append('}');
            }
        }
        for (final HunterRealFakePlayerManager.RealFakePlayerView actor : this.plugin.realFakePlayerViews()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{');
            field(json, "module", actor.module()).append(',');
            field(json, "id", actor.id()).append(',');
            field(json, "displayName", actor.displayName()).append(',');
            field(json, "kind", "server-player").append(',');
            field(json, "world", actor.world()).append(',');
            numberField(json, "x", actor.x()).append(',');
            numberField(json, "y", actor.y()).append(',');
            numberField(json, "z", actor.z()).append(',');
            numberField(json, "yaw", actor.yaw()).append(',');
            numberField(json, "pitch", actor.pitch()).append(',');
            field(json, "pose", actor.gameMode()).append(',');
            field(json, "loops", actor.loops()).append(',');
            field(json, "clickCommand", actor.clickCommand()).append(',');
            booleanField(json, "live", true).append(',');
            field(json, "entityUuid", actor.entityUuid());
            json.append('}');
        }
        json.append(']');
        return json.toString();
    }

    private String webSettingsJson() {
        final StringBuilder json = new StringBuilder(256);
        json.append('{');
        field(json, "bindAddress", this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1")).append(',');
        numberField(json, "port", Math.max(1, Math.min(65535, this.preferences.intValue("modules.web-panel.port", 8088)))).append(',');
        booleanField(json, "publicMap", this.preferences.booleanValue("modules.web-panel.public-map", true)).append(',');
        field(json, "mapUrl", this.preferences.stringValue("modules.web-panel.map-url", "http://%host%:8100/")).append(',');
        field(json, "serverName", this.webServerName()).append(',');
        field(json, "address", this.addressLine());
        json.append('}');
        return json.toString();
    }

    private String commandMessagesJson() {
        final StringBuilder json = new StringBuilder(512);
        json.append('{');
        json.append("\"about\":").append(stringArrayJson(this.preferences.stringList(
            "modules.command-overrides.messages.about",
            HunterToolsPreferences.defaultCommandOverrideLines("about")
        ))).append(',');
        json.append("\"plugins\":").append(stringArrayJson(this.preferences.stringList(
            "modules.command-overrides.messages.plugins",
            HunterToolsPreferences.defaultCommandOverrideLines("plugins")
        ))).append(',');
        json.append("\"opDenied\":").append(stringArrayJson(this.preferences.stringList(
            "modules.command-overrides.messages.op-denied",
            HunterToolsPreferences.defaultCommandOverrideLines("op-denied")
        )));
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

    private String webServerName() {
        final String configured = this.preferences.stringValue("modules.web-panel.server-name", "HunterCore").trim();
        return configured.isBlank() ? "HunterCore" : configured;
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
        final boolean webPassword = user != null && user.passwordConfigured() && verifyPassword(password, user.passwordHash());
        final boolean hunterAuthPassword = verifyHunterAuthPassword(username, password);
        if (!webPassword && !hunterAuthPassword) {
            this.send(exchange, 401, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_login\"}");
            return;
        }
        final String role = user == null ? "player" : normalizeRole(user.role());
        final String id = user == null ? HunterToolsPreferences.webUserId(username) : user.id();
        final String displayName = user == null ? username : user.displayName();
        final boolean commandExecution = user == null || user.commandExecution();
        final boolean allowedCommandsConfigured = user != null && user.allowedCommandsConfigured();
        final List<String> allowedCommands = user == null ? List.of() : List.copyOf(user.allowedCommands());
        final long minutes = Math.max(5L, this.preferences.intValue("modules.web-panel.session-minutes", 360));
        final WebSession session = new WebSession(
            this.newToken(),
            this.newToken(),
            id,
            displayName,
            role,
            webPassword ? "web" : "hunterauth",
            commandExecution,
            allowedCommandsConfigured,
            allowedCommands,
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

    private void adminActorSpawn(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String module = HunterToolsPreferences.normalize(body.getOrDefault("module", "npcs"));
        final String label = actorCommandLabel(module);
        if (label == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_actor_module\"}");
            return;
        }
        final WebSession session = this.adminOperator(exchange, label);
        if (session == null) {
            return;
        }
        final String name = commandToken(body.getOrDefault("name", ""), 32);
        if (name.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_actor_name\"}");
            return;
        }
        String kind = HunterToolsPreferences.normalize(body.getOrDefault("kind", "villager"));
        if (module.equals("fake-players") || module.equals("real-fake-players")) {
            kind = "mannequin";
        } else if (!kind.equals("villager") && !kind.equals("mannequin")) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_actor_kind\"}");
            return;
        }

        final StringBuilder command = new StringBuilder(label).append(" spawn ").append(name);
        if (module.equals("npcs")) {
            command.append(' ').append(kind);
        }
        final String location = actorLocationArguments(body);
        if (location == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_actor_location\"}");
            return;
        }
        if (!location.isBlank()) {
            command.append(' ').append(location);
        }
        final CommandResult result = this.dispatchConfiguredCommand(command.toString());
        this.guestStatusCache = null;
        this.sendCommandResult(exchange, 200, result);
    }

    private void adminActorRemove(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String module = HunterToolsPreferences.normalize(body.getOrDefault("module", ""));
        final String label = actorCommandLabel(module);
        if (label == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_actor_module\"}");
            return;
        }
        final WebSession session = this.adminOperator(exchange, label);
        if (session == null) {
            return;
        }
        final String id = commandToken(body.getOrDefault("id", ""), 32);
        if (id.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_actor_id\"}");
            return;
        }
        final CommandResult result = this.dispatchConfiguredCommand(label + " remove " + id);
        this.guestStatusCache = null;
        this.sendCommandResult(exchange, 200, result);
    }

    private void adminActorClickCommand(final HttpExchange exchange) throws IOException {
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String module = HunterToolsPreferences.normalize(body.getOrDefault("module", ""));
        final String label = actorCommandLabel(module);
        if (label == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_actor_module\"}");
            return;
        }
        final WebSession session = this.adminOperator(exchange, label);
        if (session == null) {
            return;
        }
        final String id = commandToken(body.getOrDefault("id", ""), 32);
        if (id.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_actor_id\"}");
            return;
        }
        final String command = actorClickCommand(body.getOrDefault("command", ""));
        if (command == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_click_command\"}");
            return;
        }
        if (!this.plugin.setActorClickCommand(module, id, command)) {
            this.send(exchange, 404, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"actor_not_found\"}");
            return;
        }
        this.guestStatusCache = null;
        final StringBuilder json = new StringBuilder(96);
        json.append("{\"ok\":true,");
        field(json, "message", command.isBlank() ? "Actor click command cleared." : "Actor click command saved.");
        json.append('}');
        this.send(exchange, 200, "application/json; charset=utf-8", json.toString());
    }

    private void adminWebUserSave(final HttpExchange exchange) throws IOException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String username = commandToken(body.getOrDefault("username", ""), 32);
        if (username.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_web_user\"}");
            return;
        }
        final String role = body.getOrDefault("role", "player").toLowerCase(Locale.ROOT);
        if (!role.equals("admin") && !role.equals("player")) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_role\"}");
            return;
        }
        final HunterToolsPreferences.WebUser existing = this.preferences.webUser(username);
        if (existing != null && existing.role().equals("admin") && role.equals("player") && this.lastAdmin(existing.id())) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"last_admin\"}");
            return;
        }
        final String password = body.getOrDefault("password", "").trim();
        if (existing == null && password.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"password_required\"}");
            return;
        }
        final String passwordHash = password.isBlank() ? existing.passwordHash() : hashPassword(password);
        final Boolean commandExecution = parseBoolean(body.getOrDefault("commandExecution", "true"));
        if (commandExecution == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_command_execution\"}");
            return;
        }
        final ParsedAllowedCommands allowedCommands = parseAllowedCommands(
            body.getOrDefault("allowedCommandsMode", "inherit"),
            body.getOrDefault("allowedCommands", "")
        );
        if (!allowedCommands.valid()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_allowed_commands\"}");
            return;
        }

        this.preferences.setWebUser(username, role, passwordHash);
        this.preferences.setWebUserCommandExecution(username, commandExecution);
        this.preferences.setWebUserAllowedCommands(username, allowedCommands.commands());
        this.savePreferences();
        this.expireWebUserSessions(username);
        this.guestStatusCache = null;
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void adminWebUserRemove(final HttpExchange exchange) throws IOException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String username = commandToken(body.getOrDefault("username", ""), 32);
        if (username.isBlank() || this.preferences.webUser(username) == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_web_user\"}");
            return;
        }
        if (this.lastAdmin(HunterToolsPreferences.webUserId(username))) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"last_admin\"}");
            return;
        }
        this.preferences.removeWebUser(username);
        this.savePreferences();
        this.expireWebUserSessions(username);
        this.guestStatusCache = null;
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void adminLuckPerms(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.adminOperator(exchange, "lp");
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String action = HunterToolsPreferences.normalize(body.getOrDefault("action", ""));
        final String target = commandToken(body.getOrDefault("target", ""), 64);
        final String group = commandToken(body.getOrDefault("group", ""), 64);
        final String permission = permissionToken(body.getOrDefault("permission", ""));
        final String value = body.getOrDefault("value", "true").equalsIgnoreCase("false") ? "false" : "true";
        final String command;
        switch (action) {
            case "user-permission-set" -> {
                if (target.isBlank() || permission.isBlank()) {
                    this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_luckperms_user_permission\"}");
                    return;
                }
                command = "lp user " + target + " permission set " + permission + " " + value;
            }
            case "user-permission-unset" -> {
                if (target.isBlank() || permission.isBlank()) {
                    this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_luckperms_user_permission\"}");
                    return;
                }
                command = "lp user " + target + " permission unset " + permission;
            }
            case "user-parent-set", "user-parent-add", "user-parent-remove" -> {
                if (target.isBlank() || group.isBlank()) {
                    this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_luckperms_user_parent\"}");
                    return;
                }
                final String operation = action.substring("user-parent-".length());
                command = "lp user " + target + " parent " + operation + " " + group;
            }
            case "group-permission-set" -> {
                if (group.isBlank() || permission.isBlank()) {
                    this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_luckperms_group_permission\"}");
                    return;
                }
                command = "lp group " + group + " permission set " + permission + " " + value;
            }
            case "group-permission-unset" -> {
                if (group.isBlank() || permission.isBlank()) {
                    this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_luckperms_group_permission\"}");
                    return;
                }
                command = "lp group " + group + " permission unset " + permission;
            }
            case "editor" -> command = "lp editor";
            case "sync" -> command = "lp sync";
            default -> {
                this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"unknown_luckperms_action\"}");
                return;
            }
        }
        this.sendCommandResult(exchange, 200, this.dispatchConfiguredCommand(command));
    }

    private void adminWebSettings(final HttpExchange exchange) throws IOException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String bindAddress = body.getOrDefault("bindAddress", this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1")).trim();
        final String rawPort = body.getOrDefault("port", String.valueOf(this.preferences.intValue("modules.web-panel.port", 8088))).trim();
        final String mapUrl = body.getOrDefault("mapUrl", this.preferences.stringValue("modules.web-panel.map-url", "http://%host%:8100/")).trim();
        final String serverName = body.getOrDefault("serverName", this.webServerName()).trim();
        final Boolean publicMap = parseBoolean(body.getOrDefault("publicMap", String.valueOf(this.preferences.booleanValue("modules.web-panel.public-map", true))));
        final int port;
        try {
            port = Integer.parseInt(rawPort);
        } catch (final NumberFormatException ex) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_port\"}");
            return;
        }
        if (bindAddress.isBlank() || bindAddress.length() > 128 || bindAddress.contains(" ") || port < 1 || port > 65535
            || mapUrl.isBlank() || serverName.isBlank() || serverName.length() > 64 || publicMap == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_web_settings\"}");
            return;
        }

        final boolean restart = !bindAddress.equals(this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1"))
            || port != this.preferences.intValue("modules.web-panel.port", 8088);
        this.preferences.setValue("modules.web-panel.bind-address", bindAddress);
        this.preferences.setValue("modules.web-panel.port", port);
        this.preferences.setValue("modules.web-panel.public-map", publicMap);
        this.preferences.setValue("modules.web-panel.map-url", mapUrl);
        this.preferences.setValue("modules.web-panel.server-name", serverName);
        this.savePreferences();
        this.guestStatusCache = null;
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true,\"restart\":" + restart + ",\"settings\":" + this.webSettingsJson() + "}");
        if (restart) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(250L);
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                this.restart();
            });
        }
    }

    private void adminCommandMessages(final HttpExchange exchange) throws IOException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 32 * 1024));
        final List<String> about = commandMessageLines(body.getOrDefault("about", ""));
        final List<String> plugins = commandMessageLines(body.getOrDefault("plugins", ""));
        final List<String> opDenied = commandMessageLines(body.getOrDefault("opDenied", ""));
        if (about == null || plugins == null || opDenied == null) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_command_messages\"}");
            return;
        }
        this.preferences.setValue("modules.command-overrides.messages.about", about);
        this.preferences.setValue("modules.command-overrides.messages.plugins", plugins);
        this.preferences.setValue("modules.command-overrides.messages.op-denied", opDenied);
        this.savePreferences();
        this.guestStatusCache = null;
        this.send(exchange, 200, "application/json; charset=utf-8", "{\"ok\":true,\"messages\":" + this.commandMessagesJson() + "}");
    }

    private void adminPlugin(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String pluginName = commandToken(body.getOrDefault("plugin", body.getOrDefault("name", "")), 64);
        final String action = HunterToolsPreferences.normalize(body.getOrDefault("action", ""));
        if (pluginName.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_plugin\"}");
            return;
        }
        if (!action.equals("enable") && !action.equals("disable") && !action.equals("reload")) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_plugin_action\"}");
            return;
        }

        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        final PluginOperationResult result = Bukkit.getScheduler()
            .callSyncMethod(this.plugin, () -> this.applyPluginAction(pluginName, action))
            .get(timeout, TimeUnit.SECONDS);
        this.guestStatusCache = null;
        this.sendPluginOperationResult(exchange, result);
    }

    private void adminPluginUpdate(final HttpExchange exchange) throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final WebSession session = this.adminOperator(exchange);
        if (session == null) {
            return;
        }
        final Map<String, String> body = parseJsonObject(this.body(exchange, 16 * 1024));
        final String pluginName = commandToken(body.getOrDefault("plugin", body.getOrDefault("name", "")), 64);
        final String rawUrl = body.getOrDefault("url", "").trim();
        if (pluginName.isBlank()) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_plugin\"}");
            return;
        }
        if (this.protectedPlugin(pluginName)) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"self_protection\"}");
            return;
        }

        final URI uri;
        try {
            uri = URI.create(rawUrl);
        } catch (final IllegalArgumentException ex) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_update_url\"}");
            return;
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null || rawUrl.length() > 2048) {
            this.send(exchange, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"invalid_update_url\"}");
            return;
        }

        final Path pluginsDir = Bukkit.getPluginsFolder().toPath();
        Files.createDirectories(pluginsDir);
        final Path download = Files.createTempFile(pluginsDir, "huntercore-plugin-update-", ".jar.part");
        try {
            final PluginJarMetadata metadata = this.downloadPluginJar(uri, download);
            if (!metadata.name().equalsIgnoreCase(pluginName)) {
                Files.deleteIfExists(download);
                this.send(exchange, 400, "application/json; charset=utf-8",
                    "{\"ok\":false,\"error\":\"plugin_name_mismatch\",\"message\":\"Downloaded jar is "
                        + escapeJson(metadata.name()) + ", not " + escapeJson(pluginName) + ".\"}");
                return;
            }
            final PluginOperationResult result = this.installDownloadedPlugin(metadata, download);
            this.guestStatusCache = null;
            this.sendPluginOperationResult(exchange, result);
        } catch (final IOException ex) {
            Files.deleteIfExists(download);
            this.send(exchange, 400, "application/json; charset=utf-8",
                "{\"ok\":false,\"error\":\"plugin_update_failed\",\"message\":\"" + escapeJson(ex.getMessage()) + "\"}");
        } catch (final InterruptedException ex) {
            Files.deleteIfExists(download);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (final ExecutionException | TimeoutException ex) {
            Files.deleteIfExists(download);
            throw ex;
        }
    }

    private PluginOperationResult applyPluginAction(final String pluginName, final String action) {
        final Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target == null) {
            if (action.equals("enable")) {
                return this.loadInstalledPlugin(pluginName);
            }
            return PluginOperationResult.fail("plugin_not_loaded", "Plugin " + pluginName + " is installed but not loaded, or it is unknown.");
        }
        if (this.protectedPlugin(target.getName())) {
            return PluginOperationResult.fail("self_protection", target.getName() + " hosts the web panel and cannot be controlled from the web panel.");
        }

        try {
            switch (action) {
                case "enable" -> {
                    if (!target.isEnabled()) {
                        Bukkit.getPluginManager().enablePlugin(target);
                    }
                    return PluginOperationResult.ok("enabled", target.getName() + " is enabled.", pluginLine(target));
                }
                case "disable" -> {
                    if (target.isEnabled()) {
                        Bukkit.getPluginManager().disablePlugin(target);
                    }
                    return PluginOperationResult.ok("disabled", target.getName() + " is disabled.", pluginLine(target));
                }
                case "reload" -> {
                    if (target.isEnabled()) {
                        Bukkit.getPluginManager().disablePlugin(target);
                    }
                    Bukkit.getPluginManager().enablePlugin(target);
                    return PluginOperationResult.ok("reloaded", target.getName() + " was reloaded with Bukkit enable/disable.", pluginLine(target));
                }
                default -> {
                    return PluginOperationResult.fail("invalid_plugin_action", "Unsupported plugin action.");
                }
            }
        } catch (final RuntimeException ex) {
            return PluginOperationResult.fail("plugin_action_failed", target.getName() + " action failed: " + ex.getMessage());
        }
    }

    private PluginOperationResult loadInstalledPlugin(final String pluginName) {
        final PluginJarScan scan = this.pluginJarScanLookup().get(pluginName.toLowerCase(Locale.ROOT));
        if (scan == null) {
            return PluginOperationResult.fail("unknown_plugin", "No installed jar was found for " + pluginName + ".");
        }
        if (this.protectedPlugin(scan.metadata().name())) {
            return PluginOperationResult.fail("self_protection", scan.metadata().name() + " hosts the web panel and cannot be controlled from the web panel.");
        }
        try {
            final Plugin loaded = Bukkit.getPluginManager().loadPlugin(scan.path().toFile());
            Bukkit.getPluginManager().enablePlugin(loaded);
            return PluginOperationResult.ok(
                loaded.isEnabled() ? "loaded" : "installed_disabled",
                loaded.getName() + " was loaded from " + scan.path().getFileName() + ".",
                pluginLine(loaded)
            );
        } catch (final Exception ex) {
            return PluginOperationResult.fail("plugin_load_failed", scan.metadata().name() + " could not be loaded: " + ex.getMessage());
        }
    }

    private PluginJarMetadata downloadPluginJar(final URI uri, final Path target) throws IOException {
        final int maxMegabytes = Math.max(1, Math.min(512, this.preferences.intValue("modules.web-panel.plugin-update-max-mb", 64)));
        final long maxBytes = maxMegabytes * 1024L * 1024L;
        final HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(45L))
            .header("User-Agent", "HunterCore-WebPanel/1.0")
            .GET()
            .build();
        final HttpResponse<InputStream> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted.", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Download failed with HTTP " + response.statusCode() + ".");
        }
        final long contentLength = response.headers().firstValueAsLong("content-length").orElse(-1L);
        if (contentLength > maxBytes) {
            throw new IOException("Download is larger than " + maxMegabytes + " MB.");
        }
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(target)) {
            final byte[] buffer = new byte[8192];
            long copied = 0L;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                copied += read;
                if (copied > maxBytes) {
                    throw new IOException("Download is larger than " + maxMegabytes + " MB.");
                }
                output.write(buffer, 0, read);
            }
        }
        return this.readPluginJarMetadata(target);
    }

    private PluginOperationResult installDownloadedPlugin(final PluginJarMetadata metadata, final Path download)
        throws IOException, InterruptedException, ExecutionException, TimeoutException {
        final Path pluginsDir = Bukkit.getPluginsFolder().toPath();
        final Path existingJar = this.pluginJarLookup().get(metadata.name().toLowerCase(Locale.ROOT));
        final Path targetJar = existingJar == null ? pluginsDir.resolve(safePluginJarName(metadata.name())) : existingJar;

        final int timeout = Math.max(1, this.preferences.intValue("modules.web-panel.command-timeout-seconds", 10));
        final PluginOperationResult disableResult = Bukkit.getScheduler()
            .callSyncMethod(this.plugin, () -> this.disablePluginForUpdate(metadata.name()))
            .get(timeout, TimeUnit.SECONDS);
        if (!disableResult.ok()) {
            Files.deleteIfExists(download);
            return disableResult;
        }

        Path backup = null;
        try {
            if (Files.exists(targetJar)) {
                backup = nextBackupPath(targetJar);
                Files.move(targetJar, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(download, targetJar, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            if (backup != null && Files.exists(backup) && !Files.exists(targetJar)) {
                Files.move(backup, targetJar, StandardCopyOption.REPLACE_EXISTING);
            }
            throw new IOException("Could not replace plugin jar: " + ex.getMessage(), ex);
        }

        final Path backupPath = backup;
        return Bukkit.getScheduler()
            .callSyncMethod(this.plugin, () -> this.loadUpdatedPlugin(metadata, targetJar, backupPath))
            .get(timeout, TimeUnit.SECONDS);
    }

    private PluginOperationResult disablePluginForUpdate(final String pluginName) {
        final Plugin existing = Bukkit.getPluginManager().getPlugin(pluginName);
        if (existing == null) {
            return PluginOperationResult.ok("new_plugin", pluginName + " is not currently loaded. The downloaded jar will be installed.", "");
        }
        if (this.protectedPlugin(existing.getName())) {
            return PluginOperationResult.fail("self_protection", existing.getName() + " hosts the web panel and cannot be updated from the web panel.");
        }
        try {
            if (existing.isEnabled()) {
                Bukkit.getPluginManager().disablePlugin(existing);
            }
            return PluginOperationResult.ok("disabled_for_update", existing.getName() + " was disabled before jar replacement.", pluginLine(existing));
        } catch (final RuntimeException ex) {
            return PluginOperationResult.fail("plugin_disable_failed", existing.getName() + " could not be disabled: " + ex.getMessage());
        }
    }

    private PluginOperationResult loadUpdatedPlugin(final PluginJarMetadata metadata, final Path targetJar, final Path backupPath) {
        try {
            final Plugin loaded = Bukkit.getPluginManager().loadPlugin(targetJar.toFile());
            Bukkit.getPluginManager().enablePlugin(loaded);
            return PluginOperationResult.ok(
                loaded.isEnabled() ? "hot_updated" : "installed_disabled",
                metadata.name() + " " + metadata.version() + " was installed" + (loaded.isEnabled() ? " and enabled without stopping the server." : "."),
                "jar=" + targetJar.getFileName() + (backupPath == null ? "" : "\nbackup=" + backupPath.getFileName()) + "\n" + pluginLine(loaded)
            );
        } catch (final Exception ex) {
            final Plugin existing = Bukkit.getPluginManager().getPlugin(metadata.name());
            String reenabled = "";
            if (existing != null && !existing.isEnabled()) {
                try {
                    Bukkit.getPluginManager().enablePlugin(existing);
                    reenabled = "\nExisting in-memory plugin was re-enabled.";
                } catch (final RuntimeException ignored) {
                    reenabled = "\nExisting in-memory plugin could not be re-enabled.";
                }
            }
            return PluginOperationResult.ok(
                "restart_required",
                metadata.name() + " jar was installed, but Paper/Bukkit could not hot-load it. Restart or manually reload this plugin to activate the new jar.",
                "jar=" + targetJar.getFileName()
                    + (backupPath == null ? "" : "\nbackup=" + backupPath.getFileName())
                    + "\nreason=" + ex.getMessage()
                    + reenabled
            );
        }
    }

    private Map<String, Path> pluginJarLookup() {
        final Map<String, Path> jars = new HashMap<>();
        for (final Map.Entry<String, PluginJarScan> entry : this.pluginJarScanLookup().entrySet()) {
            jars.put(entry.getKey(), entry.getValue().path());
        }
        return jars;
    }

    private Map<String, PluginJarScan> pluginJarScanLookup() {
        final Map<String, PluginJarScan> jars = new HashMap<>();
        final Path pluginsDir = Bukkit.getPluginsFolder().toPath();
        if (!Files.isDirectory(pluginsDir)) {
            return jars;
        }
        try (var stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (final Path path : stream) {
                try {
                    final PluginJarMetadata metadata = this.readPluginJarMetadata(path);
                    jars.putIfAbsent(metadata.name().toLowerCase(Locale.ROOT), new PluginJarScan(metadata, path));
                } catch (final IOException ignored) {
                }
            }
        } catch (final IOException ignored) {
        }
        return jars;
    }

    private PluginJarMetadata readPluginJarMetadata(final Path jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            for (final String descriptorName : List.of("paper-plugin.yml", "plugin.yml")) {
                final ZipEntry entry = jarFile.getEntry(descriptorName);
                if (entry == null) {
                    continue;
                }
                try (InputStream input = jarFile.getInputStream(entry);
                     InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                    final YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(reader);
                    final String name = commandToken(descriptor.getString("name", ""), 64);
                    if (name.isBlank()) {
                        continue;
                    }
                    return new PluginJarMetadata(
                        name,
                        descriptor.getString("version", ""),
                        descriptor.getString("main", ""),
                        descriptorName
                    );
                }
            }
        }
        throw new IOException("Missing plugin.yml or paper-plugin.yml.");
    }

    private boolean protectedPlugin(final String pluginName) {
        return pluginName != null && pluginName.equalsIgnoreCase(this.plugin.getName());
    }

    private static String safePluginJarName(final String pluginName) {
        final String safe = pluginName.replaceAll("[^A-Za-z0-9_.-]", "_");
        return (safe.isBlank() ? "plugin" : safe) + ".jar";
    }

    private static Path nextBackupPath(final Path source) {
        final String fileName = source.getFileName().toString();
        Path candidate = source.resolveSibling(fileName + ".bak-" + System.currentTimeMillis());
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = source.resolveSibling(fileName + ".bak-" + System.currentTimeMillis() + "-" + suffix++);
        }
        return candidate;
    }

    private static String pluginLine(final Plugin plugin) {
        return plugin.getName() + " " + plugin.getPluginMeta().getVersion() + " " + (plugin.isEnabled() ? "enabled" : "disabled");
    }

    private void sendPluginOperationResult(final HttpExchange exchange, final PluginOperationResult result) {
        this.send(exchange, result.ok() ? 200 : 400, "application/json; charset=utf-8",
            "{\"ok\":" + result.ok()
                + ",\"status\":\"" + escapeJson(result.status()) + '"'
                + ",\"message\":\"" + escapeJson(result.message()) + '"'
                + ",\"output\":\"" + escapeJson(result.output()) + '"'
                + (result.ok() ? "" : ",\"error\":\"" + escapeJson(result.status()) + '"')
                + "}");
    }

    private void savePreferences() {
        if (this.executor == null) {
            this.preferences.saveNow();
        } else {
            this.preferences.save(this.executor);
        }
    }

    private void expireWebUserSessions(final String username) {
        final String id = HunterToolsPreferences.webUserId(username);
        this.sessions.values().removeIf(session -> session.username().equals(id));
    }

    private boolean lastAdmin(final String id) {
        int admins = 0;
        boolean targetAdmin = false;
        for (final String userId : this.preferences.webUserIds()) {
            final HunterToolsPreferences.WebUser user = this.preferences.webUser(userId);
            if (user != null && user.passwordConfigured() && normalizeRole(user.role()).equals("admin")) {
                admins++;
                if (user.id().equals(id)) {
                    targetAdmin = true;
                }
            }
        }
        return targetAdmin && admins <= 1;
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
        return this.adminOperator(exchange, "hunteradmin");
    }

    private WebSession adminOperator(final HttpExchange exchange, final String requiredCommand) {
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
        if (!this.commandAllowed(session, requiredCommand)) {
            this.send(exchange, 403, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"command_denied\"}");
            return null;
        }
        return session;
    }

    private static String actorCommandLabel(final String module) {
        return switch (module) {
            case "fake-players" -> "fakeplayer";
            case "real-fake-players" -> "hplayer";
            case "npcs" -> "npc";
            default -> null;
        };
    }

    private static String actorLocationArguments(final Map<String, String> body) {
        final String world = commandToken(body.getOrDefault("world", ""), 64);
        final String x = body.getOrDefault("x", "").trim();
        final String y = body.getOrDefault("y", "").trim();
        final String z = body.getOrDefault("z", "").trim();
        if (world.isBlank() && x.isBlank() && y.isBlank() && z.isBlank()) {
            return "";
        }
        if (world.isBlank() || x.isBlank() || y.isBlank() || z.isBlank()) {
            return null;
        }
        try {
            final double parsedX = Double.parseDouble(x);
            final double parsedY = Double.parseDouble(y);
            final double parsedZ = Double.parseDouble(z);
            final float yaw = parseOptionalFloat(body.getOrDefault("yaw", ""), 0.0F);
            final float pitch = parseOptionalFloat(body.getOrDefault("pitch", ""), 0.0F);
            return world + " "
                + String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %.3f", parsedX, parsedY, parsedZ, yaw, pitch);
        } catch (final NumberFormatException ex) {
            return null;
        }
    }

    private static String actorClickCommand(final String value) {
        if (value == null) {
            return "";
        }
        String command = value.trim();
        if (command.isBlank()) {
            return "";
        }
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0 || command.length() > 512) {
            return null;
        }
        if (command.startsWith("/")) {
            command = command.substring(1).trim();
        }
        return command;
    }

    private static float parseOptionalFloat(final String value, final float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Float.parseFloat(value.trim());
    }

    private static String commandToken(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        final String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > maxLength || !trimmed.matches("[A-Za-z0-9_.-]+")) {
            return "";
        }
        return trimmed;
    }

    private static String permissionToken(final String value) {
        if (value == null) {
            return "";
        }
        final String trimmed = value.trim();
        if (trimmed.isBlank() || trimmed.length() > 160 || !trimmed.matches("[A-Za-z0-9*._:-]+")) {
            return "";
        }
        return trimmed;
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

    private void sendBytes(final HttpExchange exchange, final int status, final String type, final byte[] bytes) {
        try {
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

    private void sendServerIcon(final HttpExchange exchange) {
        final Path icon = Bukkit.getWorldContainer().toPath().resolve("server-icon.png");
        if (!Files.isRegularFile(icon)) {
            this.send(exchange, 404, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"not_found\"}");
            return;
        }
        try {
            this.sendBytes(exchange, 200, "image/png", Files.readAllBytes(icon));
        } catch (final IOException ex) {
            this.send(exchange, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"icon_unavailable\"}");
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
            + ",\"authSource\":\"" + escapeJson(session.authSource()) + '"'
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

    private static ParsedAllowedCommands parseAllowedCommands(final String mode, final String rawCommands) {
        final String normalizedMode = mode == null ? "inherit" : mode.trim().toLowerCase(Locale.ROOT);
        if (normalizedMode.equals("inherit")) {
            return new ParsedAllowedCommands(true, null);
        }
        if (normalizedMode.equals("none")) {
            return new ParsedAllowedCommands(true, List.of());
        }
        if (!normalizedMode.equals("custom")) {
            return new ParsedAllowedCommands(false, List.of());
        }
        final List<String> commands = new ArrayList<>();
        for (final String raw : rawCommands.split("[,\\s]+")) {
            final String command = commandRoot(raw);
            if (!command.isBlank() && !commands.contains(command)) {
                commands.add(command);
            }
        }
        return new ParsedAllowedCommands(true, commands);
    }

    private static List<String> commandMessageLines(final String raw) {
        final String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n');
        if (value.length() > 4096) {
            return null;
        }
        final List<String> lines = new ArrayList<>(List.of(value.split("\n", -1)));
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }
        if (lines.size() > 24) {
            return null;
        }
        for (final String line : lines) {
            if (line.length() > 256) {
                return null;
            }
        }
        return lines;
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

    private static String webAsset(final String name, final String fallback) {
        try (InputStream input = HunterWebPanelManager.class.getResourceAsStream("/web-panel/" + name)) {
            if (input == null) {
                return fallback;
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException ex) {
            return fallback;
        }
    }

    private static byte[] webAssetBytes(final String name) {
        try (InputStream input = HunterWebPanelManager.class.getResourceAsStream("/web-panel/" + name)) {
            if (input == null) {
                return new byte[0];
            }
            return input.readAllBytes();
        } catch (final IOException ex) {
            return new byte[0];
        }
    }

    private record WebSession(
        String token,
        String csrfToken,
        String username,
        String displayName,
        String role,
        String authSource,
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

    private record PluginOperationResult(boolean ok, String status, String message, String output) {
        static PluginOperationResult ok(final String status, final String message, final String output) {
            return new PluginOperationResult(true, status, message, output == null ? "" : output);
        }

        static PluginOperationResult fail(final String status, final String message) {
            return new PluginOperationResult(false, status, message, "");
        }
    }

    private record PluginJarMetadata(String name, String version, String main, String descriptor) {
    }

    private record PluginJarScan(PluginJarMetadata metadata, Path path) {
    }

    private record ParsedAllowedCommands(boolean valid, List<String> commands) {
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
              <article id="actorPanel" hidden>
                <div class="sectionTitle">
                  <h2>Actors</h2>
                  <strong class="healthBadge">admin</strong>
                </div>
                <form id="actorForm" class="command actorForm">
                  <select id="actorModule">
                    <option value="npcs">npc</option>
                    <option value="fake-players">fake player</option>
                  </select>
                  <select id="actorKind">
                    <option value="villager">villager</option>
                    <option value="mannequin">mannequin</option>
                  </select>
                  <input id="actorName" placeholder="name">
                  <select id="actorWorld">
                    <option value="">spawn</option>
                  </select>
                  <input id="actorX" class="coord" placeholder="x">
                  <input id="actorY" class="coord" placeholder="y">
                  <input id="actorZ" class="coord" placeholder="z">
                  <button type="submit">Spawn</button>
                </form>
                <div id="actorList" class="list muted">Login as admin to manage actors.</div>
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
              <article id="usersPanel" hidden>
                <div class="sectionTitle">
                  <h2>Web Users</h2>
                  <strong class="healthBadge">admin</strong>
                </div>
                <form id="webUserForm" class="command userForm">
                  <input id="webUserName" placeholder="username">
                  <select id="webUserRole">
                    <option value="player">player</option>
                    <option value="admin">admin</option>
                  </select>
                  <input id="webUserPassword" type="password" autocomplete="new-password" placeholder="new password">
                  <label class="inlineToggle"><input id="webUserCommandExecution" type="checkbox" checked> commands</label>
                  <select id="webUserAllowedMode">
                    <option value="inherit">inherit</option>
                    <option value="custom">custom</option>
                    <option value="none">none</option>
                  </select>
                  <input id="webUserAllowedCommands" placeholder="list spawn or *">
                  <button type="submit">Save</button>
                </form>
                <div id="webUserList" class="list muted">Login as admin to manage web users.</div>
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
        input, select, button { height:36px; border-radius:6px; border:1px solid var(--line); background:#0d1117; color:var(--text); padding:0 10px; }
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
        .inlineToggle { height:36px; display:inline-flex; align-items:center; gap:6px; color:var(--muted); }
        .inlineToggle input { width:18px; height:18px; accent-color:var(--accent); }
        .commandGroup { display:grid; gap:6px; }
        .actorForm .coord { width:74px; }
        .userForm input[type="password"] { min-width:150px; }
        .userActions { display:flex; gap:6px; align-items:center; }
        small { display:block; color:var(--muted); margin-top:2px; }
        .item button { height:30px; padding:0 8px; }
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
        const state = { session: null, csrf: '', webUsers: [] };
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
        const actorLine = (actor) => {
          const location = actor.world ? `${actor.world} ${Number(actor.x).toFixed(1)} ${Number(actor.y).toFixed(1)} ${Number(actor.z).toFixed(1)}` : 'not configured';
          return `<div class="item"><span>${esc(actor.displayName)} <small>${actor.live ? 'live' : 'configured'} · ${esc(actor.module)} · ${esc(actor.kind)} · pose: ${esc(actor.pose || 'standing')} · ${esc(location)}</small></span><button type="button" data-actor-remove="true" data-actor-module="${esc(actor.module)}" data-actor-id="${esc(actor.id)}">Remove</button></div>`;
        };
        const allowedLine = (user) => {
          if (!user.allowedCommandsConfigured) return 'inherit';
          return user.allowedCommands?.length ? user.allowedCommands.join(', ') : 'none';
        };
        const webUserLine = (user) =>
          `<div class="item"><span>${esc(user.displayName)} <small>${esc(user.role)} · ${user.passwordConfigured ? 'password set' : 'no password'} · commands ${user.commandExecution ? 'on' : 'off'} · ${esc(allowedLine(user))}</small></span><span class="userActions"><button type="button" data-user-edit="${esc(user.id)}">Edit</button><button type="button" data-user-remove="${esc(user.id)}">Remove</button></span></div>`;

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
          renderActorWorlds(data.worlds || []);
          renderActors(data.actorDetails || []);
          renderOperations(data.modules || []);
          renderWebUsers(data.webUsers || []);
        }

        function renderActorWorlds(worlds) {
          const selected = $('actorWorld').value;
          const names = worlds.map(world => world.name);
          $('actorWorld').innerHTML = '<option value="">spawn</option>' + names.map(name => `<option value="${esc(name)}">${esc(name)}</option>`).join('');
          if (names.includes(selected)) $('actorWorld').value = selected;
        }

        function renderActors(actors) {
          const admin = Boolean(state.session?.admin);
          $('actorPanel').hidden = !admin;
          if (!admin) return;
          $('actorList').classList.remove('muted');
          $('actorList').innerHTML = actors.length ? actors.map(actorLine).join('') : '<p class="muted">No configured actors.</p>';
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

        function renderWebUsers(users) {
          const admin = Boolean(state.session?.admin);
          $('usersPanel').hidden = !admin;
          state.webUsers = users;
          if (!admin) return;
          $('webUserList').classList.remove('muted');
          $('webUserList').innerHTML = users.length ? users.map(webUserLine).join('') : '<p class="muted">No web users.</p>';
        }

        function editWebUser(id) {
          const user = state.webUsers.find(candidate => candidate.id === id);
          if (!user) return;
          $('webUserName').value = user.displayName;
          $('webUserRole').value = user.role;
          $('webUserPassword').value = '';
          $('webUserCommandExecution').checked = Boolean(user.commandExecution);
          $('webUserAllowedMode').value = user.allowedCommandsConfigured
            ? (user.allowedCommands?.length ? 'custom' : 'none')
            : 'inherit';
          $('webUserAllowedCommands').value = user.allowedCommands?.join(', ') || '';
        }

        function updateActorKind() {
          const fake = $('actorModule').value === 'fake-players';
          $('actorKind').disabled = fake;
          if (fake) $('actorKind').value = 'mannequin';
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

        $('actorModule').addEventListener('change', updateActorKind);

        $('actorForm').addEventListener('submit', async (event) => {
          event.preventDefault();
          const payload = {
            module: $('actorModule').value,
            kind: $('actorKind').value,
            name: $('actorName').value
          };
          if ($('actorWorld').value || $('actorX').value || $('actorY').value || $('actorZ').value) {
            payload.world = $('actorWorld').value;
            payload.x = $('actorX').value;
            payload.y = $('actorY').value;
            payload.z = $('actorZ').value;
          }
          const result = await json('/api/admin/actor/spawn', { method: 'POST', body: JSON.stringify(payload) });
          $('commandResult').textContent = result.ok ? `${result.message}${result.output ? `\\n\\n${result.output}` : ''}` : `Error: ${result.error}`;
          if (result.ok) $('actorName').value = '';
          await refresh();
        });

        $('actorList').addEventListener('click', async (event) => {
          const target = event.target;
          if (!(target instanceof HTMLButtonElement) || !target.dataset.actorRemove) return;
          const payload = { module: target.dataset.actorModule, id: target.dataset.actorId };
          const result = await json('/api/admin/actor/remove', { method: 'POST', body: JSON.stringify(payload) });
          $('commandResult').textContent = result.ok ? `${result.message}${result.output ? `\\n\\n${result.output}` : ''}` : `Error: ${result.error}`;
          await refresh();
        });

        $('webUserForm').addEventListener('submit', async (event) => {
          event.preventDefault();
          const payload = {
            username: $('webUserName').value,
            role: $('webUserRole').value,
            password: $('webUserPassword').value,
            commandExecution: String($('webUserCommandExecution').checked),
            allowedCommandsMode: $('webUserAllowedMode').value,
            allowedCommands: $('webUserAllowedCommands').value
          };
          const result = await json('/api/admin/web-user/save', { method: 'POST', body: JSON.stringify(payload) });
          $('commandResult').textContent = result.ok ? 'Web user saved.' : `Error: ${result.error}`;
          if (result.ok) $('webUserPassword').value = '';
          await refresh();
        });

        $('webUserList').addEventListener('click', async (event) => {
          const target = event.target;
          if (!(target instanceof HTMLButtonElement)) return;
          if (target.dataset.userEdit) {
            editWebUser(target.dataset.userEdit);
            return;
          }
          if (!target.dataset.userRemove) return;
          const result = await json('/api/admin/web-user/remove', {
            method: 'POST',
            body: JSON.stringify({ username: target.dataset.userRemove })
          });
          $('commandResult').textContent = result.ok ? 'Web user removed.' : `Error: ${result.error}`;
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
        updateActorKind();
        setInterval(refresh, 5000);
        """;
}
