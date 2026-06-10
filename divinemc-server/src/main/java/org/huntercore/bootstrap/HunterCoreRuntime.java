package org.huntercore.bootstrap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.huntercore.api.HunterBundledPlugin;
import org.huntercore.api.HunterCommandExtension;
import org.huntercore.api.HunterCoreApi;
import org.huntercore.api.fakeplayer.HunterFakePlayerService;
import org.huntercore.config.HunterPreferences;
import org.huntercore.fakeplayer.HunterFakePlayerManager;
import org.huntercore.plugin.HunterBundledPluginInstaller;
import org.jetbrains.annotations.NotNull;

public final class HunterCoreRuntime implements HunterCoreApi {
    public static final String BRAND_NAME = "HunterCore";
    public static final String BRAND_ID = "huntercore:huntercore";
    public static final String COMMAND_NAMESPACE = "HunterCore";

    private static final HunterCoreRuntime INSTANCE = new HunterCoreRuntime();

    private final Map<String, HunterCommandExtension> commandExtensions = new LinkedHashMap<>();
    private final HunterFakePlayerService fakePlayers = new HunterFakePlayerManager();
    private volatile List<HunterBundledPlugin> bundledPlugins = List.of();
    private volatile HunterBundledPluginInstaller.InstallReport lastInstallReport = HunterBundledPluginInstaller.InstallReport.empty();
    private volatile HunterPreferences preferences;

    private HunterCoreRuntime() {
    }

    public static HunterCoreRuntime get() {
        return INSTANCE;
    }

    @Override
    public @NotNull String name() {
        return BRAND_NAME;
    }

    @Override
    public @NotNull String version() {
        final Package pkg = HunterCoreRuntime.class.getPackage();
        final String implementationVersion = pkg == null ? null : pkg.getImplementationVersion();
        return implementationVersion == null || implementationVersion.isBlank() ? "local-SNAPSHOT" : implementationVersion;
    }

    @Override
    public @NotNull Collection<HunterBundledPlugin> bundledPlugins() {
        return Collections.unmodifiableList(this.bundledPlugins);
    }

    public void setBundledPlugins(final Collection<? extends HunterBundledPlugin> bundledPlugins) {
        this.bundledPlugins = List.copyOf(bundledPlugins);
    }

    @Override
    public @NotNull HunterFakePlayerService fakePlayers() {
        return this.fakePlayers;
    }

    public HunterBundledPluginInstaller.InstallReport lastInstallReport() {
        return this.lastInstallReport;
    }

    public void setLastInstallReport(final HunterBundledPluginInstaller.InstallReport report) {
        this.lastInstallReport = report;
        this.setBundledPlugins(report.plugins());
    }

    public HunterPreferences preferences() {
        return this.preferences;
    }

    public void setPreferences(final HunterPreferences preferences) {
        this.preferences = preferences;
    }

    @Override
    public synchronized void registerCommandExtension(@NotNull final HunterCommandExtension extension) {
        final String key = extension.name().toLowerCase(Locale.ROOT);
        if (this.commandExtensions.containsKey(key)) {
            throw new IllegalArgumentException("HunterCore command extension already registered: " + extension.name());
        }
        this.commandExtensions.put(key, extension);
    }

    @Override
    public synchronized @NotNull Collection<HunterCommandExtension> commandExtensions() {
        return Collections.unmodifiableList(new ArrayList<>(this.commandExtensions.values()));
    }
}
