package org.huntercore.plugin;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.huntercore.bootstrap.HunterCoreBootstrap;
import org.huntercore.bootstrap.HunterCoreRuntime;
import org.slf4j.Logger;

public final class HunterBundledPluginInstaller {
    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final List<String> MANIFEST_RESOURCES = List.of(
        "META-INF/huntercore/bundled-plugins.yml",
        "META-INF/huntercore/bundled-plugins.external.yml"
    );

    private HunterBundledPluginInstaller() {
    }

    public static InstallReport install(final Path pluginDirectory) {
        HunterCoreBootstrap.init();

        if (Boolean.getBoolean("huntercore.bundledPlugins.disabled")) {
            LOGGER.info("HunterCore bundled plugin installer disabled by system property.");
            final InstallReport report = InstallReport.empty();
            HunterCoreRuntime.get().setLastInstallReport(report);
            return report;
        }

        try {
            Files.createDirectories(pluginDirectory);
            final List<HunterBundledPluginRecord> plugins = loadManifests();
            final YamlConfiguration config = loadOrCreateConfig(pluginDirectory, plugins);

            if (!config.getBoolean("enabled", true)) {
                LOGGER.info("HunterCore bundled plugin installer disabled by configuration.");
                final InstallReport report = new InstallReport(plugins, List.of());
                HunterCoreRuntime.get().setLastInstallReport(report);
                return report;
            }

            final boolean updateExisting = config.getBoolean("update-existing", true);
            final List<InstallResult> results = new ArrayList<>();
            for (final HunterBundledPluginRecord plugin : plugins) {
                if (!config.getBoolean("plugins." + plugin.id(), true)) {
                    results.add(new InstallResult(plugin, InstallState.DISABLED, "disabled in plugins/HunterCore/bundled-plugins.yml"));
                    continue;
                }
                results.add(installPlugin(pluginDirectory, plugin, updateExisting));
            }

            final InstallReport report = new InstallReport(plugins, results);
            HunterCoreRuntime.get().setLastInstallReport(report);
            logReport(report);
            return report;
        } catch (final Exception ex) {
            LOGGER.error("Failed to install HunterCore bundled plugins", ex);
            final InstallReport report = InstallReport.empty();
            HunterCoreRuntime.get().setLastInstallReport(report);
            return report;
        }
    }

    private static List<HunterBundledPluginRecord> loadManifests() {
        final List<HunterBundledPluginRecord> plugins = new ArrayList<>();
        for (final String manifestResource : MANIFEST_RESOURCES) {
            try (InputStream stream = HunterBundledPluginInstaller.class.getClassLoader().getResourceAsStream(manifestResource)) {
                if (stream == null) {
                    continue;
                }
                final YamlConfiguration manifest = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
                for (final Map<?, ?> entry : manifest.getMapList("plugins")) {
                    plugins.add(readPlugin(entry));
                }
            } catch (final IOException ex) {
                LOGGER.error("Failed to read HunterCore bundled plugin manifest {}", manifestResource, ex);
            }
        }
        return plugins;
    }

    private static HunterBundledPluginRecord readPlugin(final Map<?, ?> entry) {
        final String id = require(entry, "id").toLowerCase(Locale.ROOT);
        final String name = require(entry, "name");
        final String version = require(entry, "version");
        final String fileName = require(entry, "file");
        final String resource = require(entry, "resource");
        final String source = optional(entry, "source");
        final String sha256 = optional(entry, "sha256");
        return new HunterBundledPluginRecord(id, name, version, fileName, source, resource, normalizeSha256(sha256));
    }

    private static String require(final Map<?, ?> entry, final String key) {
        final Object value = entry.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing bundled plugin manifest field: " + key);
        }
        return value.toString();
    }

    private static String optional(final Map<?, ?> entry, final String key) {
        final Object value = entry.get(key);
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    private static String normalizeSha256(final String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
    }

    private static YamlConfiguration loadOrCreateConfig(final Path pluginDirectory, final List<HunterBundledPluginRecord> plugins) throws IOException {
        final Path configDirectory = pluginDirectory.resolve("HunterCore");
        final Path configPath = configDirectory.resolve("bundled-plugins.yml");
        Files.createDirectories(configDirectory);

        final YamlConfiguration config = Files.exists(configPath)
            ? YamlConfiguration.loadConfiguration(configPath.toFile())
            : new YamlConfiguration();

        boolean changed = false;
        changed |= setDefault(config, "enabled", true);
        changed |= setDefault(config, "update-existing", true);
        for (final HunterBundledPluginRecord plugin : plugins) {
            changed |= setDefault(config, "plugins." + plugin.id(), true);
        }
        if (changed || !Files.exists(configPath)) {
            config.options().header("""
                HunterCore bundled plugin installer.
                Set enabled=false to stop installing bundled plugins.
                Set update-existing=false to keep manually replaced jar files.
                Toggle individual plugins under the plugins section.
                """);
            config.save(configPath.toFile());
        }
        return config;
    }

    private static boolean setDefault(final YamlConfiguration config, final String path, final Object value) {
        if (config.contains(path)) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private static InstallResult installPlugin(final Path pluginDirectory, final HunterBundledPluginRecord plugin, final boolean updateExisting) {
        final Path target = pluginDirectory.resolve(plugin.fileName());
        try (InputStream stream = HunterBundledPluginInstaller.class.getClassLoader().getResourceAsStream(plugin.resource())) {
            if (stream == null) {
                return new InstallResult(plugin, InstallState.FAILED, "missing bundled resource " + plugin.resource());
            }

            if (Files.exists(target)) {
                final String currentHash = sha256(target);
                if (plugin.sha256() == null || plugin.sha256().equalsIgnoreCase(currentHash)) {
                    return new InstallResult(plugin, InstallState.UNCHANGED, "already installed");
                }
                if (!updateExisting) {
                    return new InstallResult(plugin, InstallState.SKIPPED, "existing jar differs and update-existing=false");
                }
            }

            final Path temp = Files.createTempFile(pluginDirectory, plugin.id() + "-", ".jar.tmp");
            try {
                Files.copy(stream, temp, StandardCopyOption.REPLACE_EXISTING);
                if (plugin.sha256() != null) {
                    final String stagedHash = sha256(temp);
                    if (!plugin.sha256().equalsIgnoreCase(stagedHash)) {
                        Files.deleteIfExists(temp);
                        return new InstallResult(plugin, InstallState.FAILED, "sha256 mismatch: expected " + plugin.sha256() + ", got " + stagedHash);
                    }
                }
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                return new InstallResult(plugin, InstallState.INSTALLED, "installed to " + target.getFileName());
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (final IOException ex) {
            return new InstallResult(plugin, InstallState.FAILED, ex.getMessage());
        }
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStreamDiscard.INSTANCE);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void logReport(final InstallReport report) {
        long installed = 0;
        long failed = 0;
        for (final InstallResult result : report.results()) {
            if (result.state() == InstallState.INSTALLED) {
                installed++;
            } else if (result.state() == InstallState.FAILED) {
                failed++;
            }
        }
        LOGGER.info("HunterCore bundled plugins prepared: {} installed/updated, {} failed, {} total", installed, failed, report.results().size());
        for (final InstallResult result : report.results()) {
            if (result.state() == InstallState.FAILED || LOGGER.isDebugEnabled()) {
                LOGGER.info("HunterCore bundled plugin {}: {} ({})", result.plugin().name(), result.state().name().toLowerCase(Locale.ROOT), result.message());
            }
        }
    }

    public record InstallReport(List<HunterBundledPluginRecord> plugins, List<InstallResult> results) {
        public static InstallReport empty() {
            return new InstallReport(List.of(), List.of());
        }
    }

    public record InstallResult(HunterBundledPluginRecord plugin, InstallState state, String message) {
    }

    public enum InstallState {
        INSTALLED,
        UNCHANGED,
        SKIPPED,
        DISABLED,
        FAILED
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(final int b) {
        }
    }
}
