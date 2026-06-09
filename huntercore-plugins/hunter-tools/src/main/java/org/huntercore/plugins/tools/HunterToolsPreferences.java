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
                this.config.getString(path + ".password", "")
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
                (float) this.config.getDouble(path + ".pitch")
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
        changed |= this.setDefault("modules.npcs.enabled", true);
        changed |= this.setDefault("modules.npcs.max-active", 64);
        changed |= this.setDefault("modules.npcs.persist", true);
        changed |= this.setDefault("modules.npcs.remove-on-disable", true);
        changed |= this.setDefault("modules.npcs.default-type", "villager");
        changed |= this.setDefault("modules.npcs.villager-ai", false);
        for (final String command : actorCommands()) {
            changed |= this.setDefault("modules.npcs.commands." + command, true);
        }
        changed |= this.setDefault("modules.web-panel.enabled", true);
        changed |= this.setDefault("modules.web-panel.bind-address", "127.0.0.1");
        changed |= this.setDefault("modules.web-panel.port", 8088);
        changed |= this.setDefault("modules.web-panel.public-map", true);
        changed |= this.setDefault("modules.web-panel.map-url", "http://%host%:8100/");
        changed |= this.setDefault("modules.web-panel.session-minutes", 360);
        changed |= this.setDefault("modules.web-panel.command-timeout-seconds", 10);
        changed |= this.setDefault("modules.web-panel.admin-command-execution", true);
        changed |= this.setDefault("modules.web-panel.player-command-execution", true);
        changed |= this.setDefault("modules.web-panel.player-allowed-commands", List.of("help", "list", "me", "msg", "tell", "spawn", "tps", "htps"));
        changed |= this.setDefault("modules.web-panel.users.admin.display-name", "admin");
        changed |= this.setDefault("modules.web-panel.users.admin.role", "admin");
        changed |= this.setDefault("modules.web-panel.users.admin.password", "");
        changed |= this.setDefault("modules.web-panel.users.player.display-name", "player");
        changed |= this.setDefault("modules.web-panel.users.player.role", "player");
        changed |= this.setDefault("modules.web-panel.users.player.password", "");
        changed |= this.setDefault("optimizations.enabled", true);
        changed |= this.setDefault("optimizations.hunter-tools.async-rendering", true);
        changed |= this.setDefault("optimizations.hunter-tools.async-save", true);
        changed |= this.setDefault("optimizations.hunter-tools.player-cache", true);
        changed |= this.setDefault("optimizations.hunter-tools.render-workers", Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors())));
        changed |= this.setDefault("optimizations.hunter-tools.actor-async-load", true);
        changed |= this.setDefault("optimizations.hunter-tools.actor-batch-save", true);
        changed |= this.setDefault("optimizations.hunter-tools.web-panel-workers", Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors())));
        changed |= this.setDefault("optimizations.cpu.enabled", true);
        changed |= this.setDefault("optimizations.cpu.mode", "balanced");
        changed |= this.setDefault("optimizations.cpu.prefer-existing-jvm-flags", true);
        changed |= this.setDefault("optimizations.cpu.paper-worker-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.divine-worker-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.netty-io-threads", "auto");
        changed |= this.setDefault("optimizations.cpu.common-pool-parallelism", "auto");
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
        return List.of("heal", "feed", "fly", "gm", "day", "night", "sun", "rain", "thunder", "broadcast", "clearchat", "speed", "spawn", "setspawn", "back");
    }

    static List<String> managementCommands() {
        return List.of("reload", "modules", "plugins", "memory", "gc", "threads", "command", "module", "optimize", "web");
    }

    static List<String> actorCommands() {
        return List.of("spawn", "remove", "list", "tp", "clear");
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
        float pitch
    ) {
        static ActorDefinition of(final String module, final String name, final String kind, final Location location) {
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
                location.getPitch()
            );
        }

        Location location() {
            final World world = Bukkit.getWorld(this.world);
            return world == null ? null : new Location(world, this.x, this.y, this.z, this.yaw, this.pitch);
        }
    }

    record WebUser(String id, String displayName, String role, String passwordHash) {
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
