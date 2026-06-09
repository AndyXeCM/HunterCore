package org.huntercore.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import org.bukkit.configuration.file.YamlConfiguration;
import org.huntercore.plugin.HunterBundledPluginRecord;

public final class HunterPreferences {
    public static final String DIRECTORY_NAME = "HunterCore";
    public static final String FILE_NAME = "preferences.yml";

    private final Path path;
    private final YamlConfiguration config;

    private HunterPreferences(final Path path, final YamlConfiguration config) {
        this.path = path;
        this.config = config;
    }

    public static HunterPreferences loadOrCreate(final Path pluginDirectory, final List<HunterBundledPluginRecord> bundledPlugins) throws IOException {
        final Path configDirectory = pluginDirectory.resolve(DIRECTORY_NAME);
        final Path path = configDirectory.resolve(FILE_NAME);
        Files.createDirectories(configDirectory);

        final YamlConfiguration config = Files.exists(path)
            ? YamlConfiguration.loadConfiguration(path.toFile())
            : new YamlConfiguration();
        final HunterPreferences preferences = new HunterPreferences(path, config);
        boolean changed = preferences.applyDefaults(bundledPlugins);
        changed |= preferences.migrateLegacyBundledConfig(pluginDirectory, bundledPlugins);
        if (changed || !Files.exists(path)) {
            preferences.save();
        }
        return preferences;
    }

    public Path path() {
        return this.path;
    }

    public YamlConfiguration raw() {
        return this.config;
    }

    public boolean bundledPluginsEnabled() {
        return this.config.getBoolean("bundled-plugins.enabled", true);
    }

    public boolean updateExistingBundledPlugins() {
        return this.config.getBoolean("bundled-plugins.update-existing", true);
    }

    public boolean bundledPluginEnabled(final String id) {
        return this.config.getBoolean("bundled-plugins.plugins." + normalize(id), true);
    }

    public void setBundledPluginEnabled(final String id, final boolean enabled) throws IOException {
        this.config.set("bundled-plugins.plugins." + normalize(id), enabled);
        this.save();
    }

    public boolean moduleEnabled(final String module) {
        return this.config.getBoolean("modules." + normalize(module) + ".enabled", true);
    }

    public void setModuleEnabled(final String module, final boolean enabled) throws IOException {
        this.config.set("modules." + normalize(module) + ".enabled", enabled);
        this.save();
    }

    public boolean commandEnabled(final String module, final String command) {
        return this.config.getBoolean("modules." + normalize(module) + ".commands." + normalize(command), true);
    }

    public void setCommandEnabled(final String module, final String command, final boolean enabled) throws IOException {
        this.config.set("modules." + normalize(module) + ".commands." + normalize(command), enabled);
        this.save();
    }

    public boolean booleanValue(final String path, final boolean fallback) {
        return this.config.getBoolean(path, fallback);
    }

    public int intValue(final String path, final int fallback) {
        return this.config.getInt(path, fallback);
    }

    public String stringValue(final String path, final String fallback) {
        return this.config.getString(path, fallback);
    }

    public boolean parallelBundledPluginInstall() {
        return this.config.getBoolean("optimizations.bundled-plugin-parallel-install.enabled", true);
    }

    public int bundledPluginInstallWorkers() {
        final int configured = this.config.getInt("optimizations.bundled-plugin-parallel-install.max-workers", 4);
        final int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        return Math.max(1, Math.min(configured, Math.min(8, cpu)));
    }

    public void save() throws IOException {
        Files.createDirectories(this.path.getParent());
        final Path temp = Files.createTempFile(this.path.getParent(), "preferences-", ".yml.tmp");
        try {
            this.config.save(temp.toFile());
            try {
                Files.move(temp, this.path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException ex) {
                Files.move(temp, this.path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException ex) {
            throw ex;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private boolean applyDefaults(final List<HunterBundledPluginRecord> bundledPlugins) {
        boolean changed = false;
        changed |= this.setDefault("bundled-plugins.enabled", true);
        changed |= this.setDefault("bundled-plugins.update-existing", true);
        changed |= this.setDefault("bundled-plugins.write-disabled-marker", true);
        for (final HunterBundledPluginRecord plugin : bundledPlugins) {
            changed |= this.setDefault("bundled-plugins.plugins." + normalize(plugin.id()), true);
        }

        changed |= this.setDefault("modules.tps-display.enabled", true);
        changed |= this.setDefault("modules.tps-display.actionbar", true);
        changed |= this.setDefault("modules.tps-display.interval-ticks", 40);
        changed |= this.setDefault("modules.sidebar.enabled", true);
        changed |= this.setDefault("modules.sidebar.interval-ticks", 40);
        changed |= this.setDefault("modules.sidebar.dirty-updates-only", true);
        changed |= this.setDefault("modules.essentials.enabled", true);
        for (final String command : List.of(
            "heal", "feed", "fly", "gm", "day", "night", "sun", "rain", "thunder",
            "broadcast", "clearchat", "speed", "spawn", "setspawn", "back"
        )) {
            changed |= this.setDefault("modules.essentials.commands." + command, true);
        }
        changed |= this.setDefault("modules.management.enabled", true);
        for (final String command : List.of("reload", "modules", "plugins", "memory", "gc", "threads", "command", "module", "optimize", "web")) {
            changed |= this.setDefault("modules.management.commands." + command, true);
        }
        changed |= this.setDefault("modules.fake-players.enabled", true);
        changed |= this.setDefault("modules.fake-players.max-active", 64);
        changed |= this.setDefault("modules.fake-players.persist", true);
        changed |= this.setDefault("modules.fake-players.remove-on-disable", true);
        for (final String command : List.of("spawn", "remove", "list", "tp", "clear")) {
            changed |= this.setDefault("modules.fake-players.commands." + command, true);
        }
        changed |= this.setDefault("modules.npcs.enabled", true);
        changed |= this.setDefault("modules.npcs.max-active", 64);
        changed |= this.setDefault("modules.npcs.persist", true);
        changed |= this.setDefault("modules.npcs.remove-on-disable", true);
        changed |= this.setDefault("modules.npcs.default-type", "villager");
        changed |= this.setDefault("modules.npcs.villager-ai", false);
        for (final String command : List.of("spawn", "remove", "list", "tp", "clear")) {
            changed |= this.setDefault("modules.npcs.commands." + command, true);
        }
        changed |= this.setDefault("modules.web-panel.enabled", true);
        changed |= this.setDefault("modules.web-panel.bind-address", "127.0.0.1");
        changed |= this.setDefault("modules.web-panel.port", 8088);
        changed |= this.setDefault("modules.web-panel.public-map", true);
        changed |= this.setDefault("modules.web-panel.map-url", "http://%host%:8100/");
        changed |= this.setDefault("modules.web-panel.status-cache-millis", 1000);
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
        changed |= this.setDefault("optimizations.bundled-plugin-parallel-install.enabled", true);
        changed |= this.setDefault("optimizations.bundled-plugin-parallel-install.max-workers", Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors())));
        changed |= this.setDefault("optimizations.hunter-tools.async-rendering", true);
        changed |= this.setDefault("optimizations.hunter-tools.async-save", true);
        changed |= this.setDefault("optimizations.hunter-tools.player-cache", true);
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

        if (changed) {
            this.config.options().header("""
                HunterCore preferences.
                bundled-plugins controls which built-in jar files HunterCore installs before plugin scanning.
                modules controls HunterCore's self-written runtime features.
                Commands can change these values, but disabling bundled plugins still requires a restart to unload them.
                """);
        }
        return changed;
    }

    private boolean migrateLegacyBundledConfig(final Path pluginDirectory, final List<HunterBundledPluginRecord> bundledPlugins) {
        final Path legacyPath = pluginDirectory.resolve(DIRECTORY_NAME).resolve("bundled-plugins.yml");
        if (!Files.exists(legacyPath)) {
            return false;
        }

        final YamlConfiguration legacy = YamlConfiguration.loadConfiguration(legacyPath.toFile());
        boolean changed = false;
        if (legacy.contains("enabled") && !this.config.contains("bundled-plugins.enabled")) {
            this.config.set("bundled-plugins.enabled", legacy.getBoolean("enabled", true));
            changed = true;
        }
        if (legacy.contains("update-existing") && !this.config.contains("bundled-plugins.update-existing")) {
            this.config.set("bundled-plugins.update-existing", legacy.getBoolean("update-existing", true));
            changed = true;
        }
        for (final HunterBundledPluginRecord plugin : bundledPlugins) {
            final String oldPath = "plugins." + plugin.id();
            final String newPath = "bundled-plugins.plugins." + normalize(plugin.id());
            if (legacy.contains(oldPath) && !this.config.contains(newPath)) {
                this.config.set(newPath, legacy.getBoolean(oldPath, true));
                changed = true;
            }
        }
        return changed;
    }

    private boolean setDefault(final String path, final Object value) {
        if (this.config.contains(path)) {
            return false;
        }
        this.config.set(path, value);
        return true;
    }

    public static String normalize(final String id) {
        return id.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
