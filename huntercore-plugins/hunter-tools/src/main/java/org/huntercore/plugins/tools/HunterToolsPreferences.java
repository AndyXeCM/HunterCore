package org.huntercore.plugins.tools;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class HunterToolsPreferences {
    private final JavaPlugin plugin;
    private final Path path;
    private final Object lock = new Object();
    private final Object saveLock = new Object();
    private CompletableFuture<Void> pendingSave = CompletableFuture.completedFuture(null);
    private YamlConfiguration config;

    private HunterToolsPreferences(final JavaPlugin plugin, final Path path, final YamlConfiguration config) {
        this.plugin = plugin;
        this.path = path;
        this.config = config;
    }

    static HunterToolsPreferences loadOrCreate(final JavaPlugin plugin) {
        final Path path = Bukkit.getPluginsFolder().toPath().resolve("HunterCore").resolve("preferences.yml");
        final YamlConfiguration config = Files.exists(path) ? YamlConfiguration.loadConfiguration(path.toFile()) : new YamlConfiguration();
        final HunterToolsPreferences preferences = new HunterToolsPreferences(plugin, path, config);
        if (preferences.applyDefaults() || !Files.exists(path)) {
            preferences.saveNow();
        }
        return preferences;
    }

    void reload() {
        this.flushPendingSaves();
        synchronized (this.lock) {
            this.config = Files.exists(this.path) ? YamlConfiguration.loadConfiguration(this.path.toFile()) : new YamlConfiguration();
            if (this.applyDefaults()) {
                this.saveNow();
            }
        }
    }

    boolean moduleEnabled(final String module) {
        synchronized (this.lock) {
            return this.config.getBoolean("modules." + normalize(module) + ".enabled", true);
        }
    }

    boolean commandEnabled(final String module, final String command) {
        synchronized (this.lock) {
            return this.config.getBoolean("modules." + normalize(module) + ".commands." + normalize(command), true);
        }
    }

    boolean booleanValue(final String path, final boolean fallback) {
        synchronized (this.lock) {
            return this.config.getBoolean(path, fallback);
        }
    }

    int intValue(final String path, final int fallback) {
        synchronized (this.lock) {
            return this.config.getInt(path, fallback);
        }
    }

    double doubleValue(final String path, final double fallback) {
        synchronized (this.lock) {
            return this.config.getDouble(path, fallback);
        }
    }

    String stringValue(final String path, final String fallback) {
        synchronized (this.lock) {
            return this.config.getString(path, fallback);
        }
    }

    List<String> stringList(final String path, final List<String> fallback) {
        synchronized (this.lock) {
            return this.config.contains(path) ? this.config.getStringList(path) : fallback;
        }
    }

    void setModuleEnabled(final String module, final boolean enabled) {
        synchronized (this.lock) {
            this.config.set("modules." + normalize(module) + ".enabled", enabled);
        }
    }

    void setCommandEnabled(final String module, final String command, final boolean enabled) {
        synchronized (this.lock) {
            this.config.set("modules." + normalize(module) + ".commands." + normalize(command), enabled);
        }
    }

    void setValue(final String path, final Object value) {
        synchronized (this.lock) {
            this.config.set(path, value);
        }
    }

    Set<String> webUserIds() {
        synchronized (this.lock) {
            final ConfigurationSection section = this.config.getConfigurationSection("modules.web-panel.users");
            return section == null ? Set.of() : new TreeSet<>(section.getKeys(false));
        }
    }

    WebUser webUser(final String username) {
        synchronized (this.lock) {
            final String id = webUserId(username);
            final String path = "modules.web-panel.users." + id;
            if (!this.config.contains(path)) {
                return null;
            }
            return new WebUser(
                id,
                this.config.getString(path + ".display-name", username),
                normalize(this.config.getString(path + ".role", "player")),
                this.config.getString(path + ".password", ""),
                this.config.getBoolean(path + ".command-execution", true),
                this.config.contains(path + ".allowed-commands"),
                this.config.getStringList(path + ".allowed-commands")
            );
        }
    }

    void setWebUser(final String username, final String role, final String passwordHash) {
        synchronized (this.lock) {
            final String id = webUserId(username);
            final String path = "modules.web-panel.users." + id;
            this.config.set(path + ".display-name", username);
            this.config.set(path + ".role", normalize(role));
            this.config.set(path + ".password", passwordHash);
            this.config.set(path + ".command-execution", true);
        }
    }

    void setWebUserCommandExecution(final String username, final boolean enabled) {
        synchronized (this.lock) {
            this.config.set("modules.web-panel.users." + webUserId(username) + ".command-execution", enabled);
        }
    }

    void setWebUserAllowedCommands(final String username, final List<String> commands) {
        synchronized (this.lock) {
            final String path = "modules.web-panel.users." + webUserId(username) + ".allowed-commands";
            this.config.set(path, commands == null ? null : commands);
        }
    }

    void removeWebUser(final String username) {
        synchronized (this.lock) {
            this.config.set("modules.web-panel.users." + webUserId(username), null);
        }
    }

    Set<String> actorIds(final String module) {
        synchronized (this.lock) {
            final ConfigurationSection section = this.config.getConfigurationSection(actorPath(module));
            return section == null ? Set.of() : new TreeSet<>(section.getKeys(false));
        }
    }

    ActorDefinition actorDefinition(final String module, final String id) {
        synchronized (this.lock) {
            final String path = actorPath(module) + "." + normalize(id);
            if (!this.config.contains(path)) {
                return null;
            }
            final String world = this.config.getString(path + ".world", "");
            if (world == null || world.isBlank()) {
                return null;
            }
            return new ActorDefinition(
                normalize(id),
                this.config.getString(path + ".display-name", id),
                this.config.getString(path + ".kind", module.equals("npcs") ? this.stringValue("modules.npcs.default-type", "villager") : "mannequin"),
                actorUuid(module, normalize(id), this.config.getString(path + ".uuid")),
                world,
                this.config.getDouble(path + ".x"),
                this.config.getDouble(path + ".y"),
                this.config.getDouble(path + ".z"),
                (float) this.config.getDouble(path + ".yaw"),
                (float) this.config.getDouble(path + ".pitch"),
                normalize(this.config.getString(path + ".pose", "standing")),
                this.config.getString(path + ".click-command", ""),
                this.config.contains(path + ".ai-enabled") ? this.config.getBoolean(path + ".ai-enabled") : module.equals("npcs"),
                this.config.getString(path + ".ai-persona", "")
            );
        }
    }

    void setActorDefinition(final String module, final ActorDefinition actor) {
        synchronized (this.lock) {
            final String path = actorPath(module) + "." + normalize(actor.id());
            this.config.set(path + ".display-name", actor.displayName());
            this.config.set(path + ".kind", normalize(actor.kind()));
            this.config.set(path + ".uuid", actor.uuid().toString());
            this.config.set(path + ".world", actor.world());
            this.config.set(path + ".x", actor.x());
            this.config.set(path + ".y", actor.y());
            this.config.set(path + ".z", actor.z());
            this.config.set(path + ".yaw", actor.yaw());
            this.config.set(path + ".pitch", actor.pitch());
            this.config.set(path + ".pose", normalize(actor.pose()));
            final String clickCommand = actor.clickCommand() == null ? "" : actor.clickCommand().trim();
            this.config.set(path + ".click-command", clickCommand.isBlank() ? null : clickCommand);
            this.config.set(path + ".ai-enabled", actor.aiEnabled());
            final String aiPersona = actor.aiPersona() == null ? "" : actor.aiPersona().trim();
            this.config.set(path + ".ai-persona", aiPersona.isBlank() ? null : aiPersona);
        }
    }

    void removeActorDefinition(final String module, final String id) {
        synchronized (this.lock) {
            this.config.set(actorPath(module) + "." + normalize(id), null);
        }
    }

    void clearActorDefinitions(final String module) {
        synchronized (this.lock) {
            this.config.set(actorPath(module), null);
        }
    }

    void setSpawn(final Location location) {
        synchronized (this.lock) {
            this.config.set("modules.essentials.spawn.world", location.getWorld().getName());
            this.config.set("modules.essentials.spawn.x", location.getX());
            this.config.set("modules.essentials.spawn.y", location.getY());
            this.config.set("modules.essentials.spawn.z", location.getZ());
            this.config.set("modules.essentials.spawn.yaw", location.getYaw());
            this.config.set("modules.essentials.spawn.pitch", location.getPitch());
        }
    }

    Location spawn() {
        synchronized (this.lock) {
            final String worldName = this.config.getString("modules.essentials.spawn.world", "");
            final World world = worldName == null || worldName.isBlank() ? defaultWorld() : Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            if (!this.config.contains("modules.essentials.spawn.x")) {
                return world.getSpawnLocation();
            }
            return new Location(
                world,
                this.config.getDouble("modules.essentials.spawn.x"),
                this.config.getDouble("modules.essentials.spawn.y"),
                this.config.getDouble("modules.essentials.spawn.z"),
                (float) this.config.getDouble("modules.essentials.spawn.yaw"),
                (float) this.config.getDouble("modules.essentials.spawn.pitch")
            );
        }
    }

    CompletableFuture<Void> save(final Executor executor) {
        if (!this.booleanValue("optimizations.hunter-tools.async-save", true)) {
            this.saveNow();
            return CompletableFuture.completedFuture(null);
        }
        synchronized (this.saveLock) {
            this.pendingSave = this.pendingSave
                .exceptionally(throwable -> null)
                .thenRunAsync(this::saveNow, executor);
            return this.pendingSave;
        }
    }

    void flushPendingSaves() {
        final CompletableFuture<Void> pending;
        synchronized (this.saveLock) {
            pending = this.pendingSave;
        }
        pending.join();
    }

    void saveNow() {
        synchronized (this.lock) {
            try {
                Files.createDirectories(this.path.getParent());
                final Path temp = Files.createTempFile(this.path.getParent(), "preferences-", ".yml.tmp");
                try {
                    this.config.save(temp.toFile());
                    try {
                        Files.move(temp, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (final IOException ex) {
                        Files.move(temp, this.path, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temp);
                }
            } catch (final IOException ex) {
                this.plugin.getLogger().severe("Failed to save HunterCore preferences: " + ex.getMessage());
            }
        }
    }

    File file() {
        return this.path.toFile();
    }

    private boolean applyDefaults() {
        boolean changed = false;
        changed |= this.setDefault("modules.tps-display.enabled", true);
        changed |= this.setDefault("modules.tps-display.actionbar", true);
        changed |= this.setDefault("modules.tps-display.interval-ticks", 40);
        changed |= this.setDefault("modules.sidebar.enabled", true);
        changed |= this.setDefault("modules.sidebar.interval-ticks", 40);
        changed |= this.setDefault("modules.sidebar.dirty-updates-only", true);
        changed |= this.setDefault("modules.motd.enabled", true);
        changed |= this.setDefault("modules.motd.line-1", "&b\"HunterCraft\" Server &8| &fHunterCore");
        changed |= this.setDefault("modules.motd.line-2", "&7%online%/%max% players &8- &aTPS %tps% &8- &e%version%");
        changed |= this.setDefault("modules.motd.max-players", -1);
        changed |= this.setDefault("modules.command-overrides.enabled", true);
        changed |= this.setDefault("modules.command-overrides.messages.about", defaultCommandOverrideLines("about"));
        changed |= this.setDefault("modules.command-overrides.messages.plugins", defaultCommandOverrideLines("plugins"));
        changed |= this.setDefault("modules.command-overrides.messages.op-denied", defaultCommandOverrideLines("op-denied"));
        changed |= this.setDefault("modules.essentials.enabled", true);
        for (final String command : essentialsCommands()) {
            changed |= this.setDefault("modules.essentials.commands." + command, true);
        }
        changed |= this.setDefault("modules.management.enabled", true);
        for (final String command : managementCommands()) {
            changed |= this.setDefault("modules.management.commands." + command, true);
        }
        changed |= this.setDefault("modules.fake-players.enabled", true);
        changed |= this.setDefault("modules.fake-players.max-active", 64);
        changed |= this.setDefault("modules.fake-players.persist", true);
        changed |= this.setDefault("modules.fake-players.remove-on-disable", true);
        for (final String command : actorCommands()) {
            changed |= this.setDefault("modules.fake-players.commands." + command, true);
        }
        changed |= this.setDefault("modules.real-fake-players.enabled", true);
        changed |= this.setDefault("modules.real-fake-players.max-active", 16);
        changed |= this.setDefault("modules.real-fake-players.remove-on-disable", true);
        changed |= this.setDefault("modules.real-fake-players.action-interval-ticks", 1);
        for (final String command : realFakePlayerCommands()) {
            changed |= this.setDefault("modules.real-fake-players.commands." + command, true);
        }
        changed |= this.setDefault("modules.npcs.enabled", true);
        changed |= this.setDefault("modules.npcs.max-active", 64);
        changed |= this.setDefault("modules.npcs.persist", true);
        changed |= this.setDefault("modules.npcs.remove-on-disable", true);
        changed |= this.setDefault("modules.npcs.default-type", "villager");
        changed |= this.setDefault("modules.npcs.villager-ai", false);
        for (final String command : actorCommands()) {
            changed |= this.setDefault("modules.npcs.commands." + command, true);
        }
        changed |= this.setDefault("modules.ai.enabled", false);
        changed |= this.setDefault("modules.ai.provider", "openai-compatible");
        changed |= this.setDefault("modules.ai.base-url", "https://api.openai.com/v1");
        changed |= this.setDefault("modules.ai.api-key", "");
        changed |= this.setDefault("modules.ai.api-key-env", "OPENAI_API_KEY");
        changed |= this.setDefault("modules.ai.model", "gpt-4o-mini");
        changed |= this.setDefault("modules.ai.temperature", 0.7D);
        changed |= this.setDefault("modules.ai.max-tokens", 300);
        changed |= this.setDefault("modules.ai.timeout-seconds", 30);
        changed |= this.setDefault("modules.ai.chat.enabled", true);
        changed |= this.setDefault("modules.ai.chat.trigger-prefix", "@ai");
        changed |= this.setDefault("modules.ai.chat.cooldown-seconds", 5);
        changed |= this.setDefault("modules.ai.chat.broadcast", true);
        changed |= this.setDefault("modules.ai.chat.response-format", "&bAI &8> &f%response%");
        changed |= this.setDefault("modules.ai.chat.system-prompt", "You are the native AI assistant for a Minecraft server running HunterCore. Answer in the same language as the player when possible. Keep responses useful, friendly, and concise.");
        changed |= this.setDefault("modules.ai.npc.enabled", true);
        changed |= this.setDefault("modules.ai.npc.cooldown-seconds", 5);
        changed |= this.setDefault("modules.ai.npc.response-radius-blocks", 16);
        changed |= this.setDefault("modules.ai.npc.allow-actions", true);
        changed |= this.setDefault("modules.ai.npc.response-format", "&d%npc% &8> &f%response%");
        changed |= this.setDefault("modules.ai.npc.system-prompt", "You control a HunterCore Minecraft NPC. Reply as the NPC in one or two short chat lines. You may add action lines on their own line: [look], [pose:standing], [pose:sneaking], or [command:say text]. Only use commands when they are helpful and safe.");
        changed |= this.setDefault("modules.ai.npc.command-whitelist", List.of("say", "tell", "msg", "title", "effect", "playsound"));
        changed |= this.setDefault("modules.ai.fake-players.enabled", true);
        changed |= this.setDefault("modules.ai.fake-players.interval-seconds", 6);
        changed |= this.setDefault("modules.ai.fake-players.max-actions", 5);
        changed |= this.setDefault("modules.ai.fake-players.max-move-ticks", 40);
        changed |= this.setDefault("modules.ai.fake-players.max-action-ticks", 80);
        changed |= this.setDefault("modules.ai.fake-players.nearby-radius-blocks", 6);
        changed |= this.setDefault("modules.ai.fake-players.allow-movement", true);
        changed |= this.setDefault("modules.ai.fake-players.allow-breaking", true);
        changed |= this.setDefault("modules.ai.fake-players.allow-interaction", true);
        changed |= this.setDefault("modules.ai.fake-players.chat-control.enabled", true);
        changed |= this.setDefault("modules.ai.fake-players.chat-control.trigger-prefix", "@bot");
        changed |= this.setDefault("modules.ai.fake-players.chat-control.cooldown-seconds", 3);
        changed |= this.setDefault("modules.ai.fake-players.chat-control.require-permission", false);
        changed |= this.setDefault("modules.ai.fake-players.chat-control.permission", "huntertools.ai.fakeplayer");
        changed |= this.setDefault("modules.ai.fake-players.system-prompt", "You control a HunterCore real fake player in Minecraft. Return only bracketed action lines, no prose. Available actions: [look:yaw pitch], [look-at:x y z], [turn:yaw pitch], [look-at-player:player=name], [move:forward=1,sideways=0,ticks=20,sprint=true,jump=false,sneak=false], [goto:x y z,ticks=60,sprint=true], [follow:player=name,ticks=80,distance=2.5], [mine:ticks=40], [use], [attack], [jump], [sneak:on], [sprint:off], [slot:1], [say:text], [drop], [dropstack], [swap], [wait:ticks=20], [stop]. Use small safe steps. For player chat work requests, act like a cooperative helper. Mine only when the goal requires it and the target block is visible.");
        changed |= this.setDefault("modules.ai.adaptive-throttling.enabled", true);
        changed |= this.setDefault("modules.ai.adaptive-throttling.warning-mspt", 40.0D);
        changed |= this.setDefault("modules.ai.adaptive-throttling.critical-mspt", 55.0D);
        changed |= this.setDefault("modules.ai.adaptive-throttling.severe-mspt", 75.0D);
        changed |= this.setDefault("modules.web-panel.enabled", true);
        changed |= this.setDefault("modules.web-panel.bind-address", "127.0.0.1");
        changed |= this.setDefault("modules.web-panel.port", 8088);
        changed |= this.setDefault("modules.web-panel.server-name", "HunterCore");
        changed |= this.setDefault("modules.web-panel.public-map", true);
        changed |= this.setDefault("modules.web-panel.map-url", "http://%host%:8100/");
        changed |= this.setDefault("modules.web-panel.status-cache-millis", 1000);
        changed |= this.setDefault("modules.web-panel.status-cache-player-millis", 700);
        changed |= this.setDefault("modules.web-panel.status-cache-admin-millis", 400);
        changed |= this.setDefault("modules.web-panel.require-csrf", true);
        changed |= this.setDefault("modules.web-panel.session-minutes", 360);
        changed |= this.setDefault("modules.web-panel.command-timeout-seconds", 10);
        changed |= this.setDefault("modules.web-panel.command-output-lines", 80);
        changed |= this.setDefault("modules.web-panel.command-output-chars", 12000);
        changed |= this.setDefault("modules.web-panel.plugin-operation-min-interval-millis", 1500);
        changed |= this.setDefault("modules.web-panel.health.enabled", true);
        changed |= this.setDefault("modules.web-panel.health.low-tps-warning", 18.0D);
        changed |= this.setDefault("modules.web-panel.health.low-tps-critical", 15.0D);
        changed |= this.setDefault("modules.web-panel.health.high-mspt-warning", 50.0D);
        changed |= this.setDefault("modules.web-panel.health.high-mspt-critical", 75.0D);
        changed |= this.setDefault("modules.web-panel.health.memory-warning-percent", 85.0D);
        changed |= this.setDefault("modules.web-panel.health.memory-critical-percent", 95.0D);
        changed |= this.setDefault("modules.web-panel.health.loaded-chunks-warning", 12000);
        changed |= this.setDefault("modules.web-panel.health.entities-warning", 4000);
        changed |= this.setDefault("modules.web-panel.health.disabled-plugins-warning", true);
        changed |= this.setDefault("modules.web-panel.admin-command-execution", true);
        changed |= this.setDefault("modules.web-panel.player-command-execution", true);
        changed |= this.setDefault("modules.web-panel.player-allowed-commands", defaultWebPlayerCommands());
        changed |= this.setDefault("modules.web-panel.users.admin.display-name", "admin");
        changed |= this.setDefault("modules.web-panel.users.admin.role", "admin");
        changed |= this.setDefault("modules.web-panel.users.admin.password", "");
        changed |= this.setDefault("modules.web-panel.users.admin.command-execution", true);
        changed |= this.setDefault("modules.web-panel.users.admin.allowed-commands", List.of("*"));
        changed |= this.setDefault("modules.web-panel.users.player.display-name", "player");
        changed |= this.setDefault("modules.web-panel.users.player.role", "player");
        changed |= this.setDefault("modules.web-panel.users.player.password", "");
        changed |= this.setDefault("modules.web-panel.users.player.command-execution", true);
        changed |= this.setDefault("modules.web-panel.users.player.allowed-commands", defaultWebPlayerCommands());
        changed |= this.setDefault("optimizations.cpu.enabled", true);
        changed |= this.setDefault("optimizations.cpu.mode", "single-thread");
        changed |= this.setDefault("optimizations.cpu.prefer-existing-jvm-flags", true);
        changed |= this.setDefault("optimizations.cpu.allow-experimental-region-ticking", false);
        changed |= this.setDefault("optimizations.cpu.paper-worker-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.divine-worker-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.netty-io-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.common-pool-parallelism", "auto");
        changed |= this.setDefault("optimizations.enabled", true);
        changed |= this.setDefault("optimizations.hunter-tools.async-rendering", !this.singleThreadMode());
        changed |= this.setDefault("optimizations.hunter-tools.async-save", !this.singleThreadMode());
        changed |= this.setDefault("optimizations.hunter-tools.player-cache", true);
        changed |= this.setDefault("optimizations.hunter-tools.render-workers", this.defaultWorkerCount());
        changed |= this.setDefault("optimizations.hunter-tools.actor-async-load", !this.singleThreadMode());
        changed |= this.setDefault("optimizations.hunter-tools.actor-batch-save", !this.singleThreadMode());
        changed |= this.setDefault("optimizations.hunter-tools.web-panel-workers", this.defaultWorkerCount());
        return changed;
    }

    private boolean setDefault(final String path, final Object value) {
        if (this.config.contains(path)) {
            return false;
        }
        this.config.set(path, value);
        return true;
    }

    static List<String> essentialsCommands() {
        return List.of(
            "heal", "feed", "fly", "gm", "day", "night", "sun", "rain", "thunder", "broadcast", "clearchat",
            "speed", "spawn", "setspawn", "back", "hat", "craft", "enderchest", "trash"
        );
    }

    boolean singleThreadMode() {
        return "single-thread".equalsIgnoreCase(this.stringValue("optimizations.cpu.mode", "single-thread"));
    }

    int defaultWorkerCount() {
        final String mode = this.stringValue("optimizations.cpu.mode", "single-thread");
        if (this.singleThreadMode()) {
            return 1;
        }
        final int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        if ("high-clock".equalsIgnoreCase(mode)) {
            return Math.min(2, Math.max(1, cpu / 4));
        }
        if ("high-core".equalsIgnoreCase(mode)) {
            return Math.min(6, Math.max(3, cpu / 2));
        }
        return Math.min(4, Math.max(2, cpu / 2));
    }

    static List<String> managementCommands() {
        return List.of("reload", "modules", "plugins", "memory", "gc", "threads", "command", "module", "optimize", "motd", "web", "ai");
    }

    static List<String> actorCommands() {
        return List.of("spawn", "remove", "list", "tp", "tphere", "look", "pose", "click", "info", "clear");
    }

    static List<String> realFakePlayerCommands() {
        return List.of(
            "spawn", "remove", "kill", "list", "tp", "tphere", "look", "move", "sneak", "sprint", "jump", "use", "attack",
            "stop", "click", "drop", "dropstack", "swap", "gm", "gamemode", "slot", "ai", "info", "clear"
        );
    }

    static List<String> defaultWebPlayerCommands() {
        return List.of("help", "list", "me", "msg", "tell", "spawn", "tps", "htps");
    }

    static List<String> defaultCommandOverrideLines(final String target) {
        return switch (normalize(target)) {
            case "plugins" -> List.of(
                "&6插件列表 &8| &f此服务器由 &bHunterCore &f托管",
                "&7基础插件和管理能力已集成，具体插件列表由管理员维护。"
            );
            case "op-denied" -> List.of(
                "&c你没有权限使用 /op。",
                "&7如需管理员权限，请联系服务器管理组。"
            );
            default -> List.of(
                "&b\"HunterCraft\" Server &8| &fPowered by &6HunterCore",
                "&7高性能自定义核心 · 网页面板 · 假人调试 · 地图管理"
            );
        };
    }

    static String normalize(final String id) {
        return id.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static String actorId(final String id) {
        final String normalized = normalize(id).replaceAll("[^a-z0-9-]", "");
        return normalized.isBlank() ? "actor" : normalized;
    }

    static String webUserId(final String username) {
        final String normalized = username.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "");
        return normalized.isBlank() ? "user" : normalized;
    }

    private static String actorPath(final String module) {
        return "modules." + normalize(module) + ".actors";
    }

    private static World defaultWorld() {
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    record ActorDefinition(
        String id,
        String displayName,
        String kind,
        UUID uuid,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String pose,
        String clickCommand,
        boolean aiEnabled,
        String aiPersona
    ) {
        static ActorDefinition of(final String module, final String name, final String kind, final Location location) {
            return of(module, name, kind, location, "standing", "");
        }

        static ActorDefinition of(final String module, final String name, final String kind, final Location location, final String pose) {
            return of(module, name, kind, location, pose, "");
        }

        static ActorDefinition of(
            final String module,
            final String name,
            final String kind,
            final Location location,
            final String pose,
            final String clickCommand
        ) {
            return of(module, name, kind, location, pose, clickCommand, module.equals("npcs"), "");
        }

        static ActorDefinition of(
            final String module,
            final String name,
            final String kind,
            final Location location,
            final String pose,
            final String clickCommand,
            final boolean aiEnabled,
            final String aiPersona
        ) {
            final String id = actorId(name);
            return new ActorDefinition(
                id,
                name,
                normalize(kind),
                UUID.nameUUIDFromBytes(("huntercore:" + module + ":" + id).getBytes(StandardCharsets.UTF_8)),
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                normalize(pose),
                clickCommand == null ? "" : clickCommand.trim(),
                aiEnabled,
                aiPersona == null ? "" : aiPersona.trim()
            );
        }

        Location location() {
            final World world = Bukkit.getWorld(this.world);
            return world == null ? null : new Location(world, this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }

    record WebUser(
        String id,
        String displayName,
        String role,
        String passwordHash,
        boolean commandExecution,
        boolean allowedCommandsConfigured,
        List<String> allowedCommands
    ) {
        boolean passwordConfigured() {
            return this.passwordHash != null && !this.passwordHash.isBlank();
        }
    }

    private static UUID actorUuid(final String module, final String id, final String configured) {
        if (configured != null && !configured.isBlank()) {
            try {
                return UUID.fromString(configured);
            } catch (final IllegalArgumentException ignored) {
            }
        }
        return UUID.nameUUIDFromBytes(("huntercore:" + module + ":" + id).getBytes(StandardCharsets.UTF_8));
    }
}
