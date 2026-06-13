package org.huntercore.optimization;

import com.mojang.logging.LogUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;
import org.huntercore.config.HunterPreferences;
import org.slf4j.Logger;

public final class HunterCoreOptimizer {
    public static final String MODE_SINGLE_THREAD = "single-thread";
    public static final String MODE_HIGH_CLOCK = "high-clock";
    public static final String MODE_HIGH_CORE = "high-core";
    public static final String MODE_MULTI_THREAD = "multi-thread";
    public static final String DEFAULT_MODE = MODE_SINGLE_THREAD;

    private static final Logger LOGGER = LogUtils.getClassLogger();
    private static final int CPU = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static final String PROP_MANAGED = "huntercore.threading.managed";
    private static final String PROP_MODE = "huntercore.threading.mode";
    private static final String PROP_ALLOW_EXPERIMENTAL_REGION_TICKING = "huntercore.threading.allow-experimental-region-ticking";
    private static final String PROP_PATHFINDING_THREADS = "huntercore.threading.pathfinding-threads";
    private static final String PROP_TRACKER_THREADS = "huntercore.threading.tracker-threads";
    private static final String PROP_CHUNK_SEND_THREADS = "huntercore.threading.chunk-send-threads";
    private static final String PROP_REGION_TICK_THREADS = "huntercore.threading.region-tick-threads";
    private static volatile OptimizationSnapshot lastSnapshot = OptimizationSnapshot.empty();

    private HunterCoreOptimizer() {
    }

    public static synchronized OptimizationSnapshot applyStartupDefaults() {
        if (Boolean.getBoolean("huntercore.optimizations.disabled")) {
            System.setProperty(PROP_MANAGED, "false");
            lastSnapshot = new OptimizationSnapshot(false, MODE_SINGLE_THREAD, CPU, Map.of(), "disabled by huntercore.optimizations.disabled");
            return lastSnapshot;
        }

        final String mode = normalizeMode(System.getProperty(PROP_MODE, DEFAULT_MODE));
        return applyMode(mode, true, false, "startup defaults");
    }

    public static synchronized OptimizationSnapshot applyEarlyPreferenceDefaults(final Path preferencesPath) {
        if (!Files.isRegularFile(preferencesPath) || Boolean.getBoolean("huntercore.optimizations.disabled")) {
            return lastSnapshot;
        }

        try {
            final YamlConfiguration config = YamlConfiguration.loadConfiguration(preferencesPath.toFile());
            if (!config.getBoolean("optimizations.cpu.enabled", true)) {
                System.setProperty(PROP_MANAGED, "false");
                lastSnapshot = new OptimizationSnapshot(false, MODE_SINGLE_THREAD, CPU, Map.of(), "disabled in preferences.yml");
                logSnapshot(lastSnapshot);
                return lastSnapshot;
            }

            final String mode = normalizeMode(config.getString("optimizations.cpu.mode", DEFAULT_MODE));
            final boolean preferExisting = config.getBoolean("optimizations.cpu.prefer-existing-jvm-flags", true);
            final boolean allowExperimentalRegionTicking = config.getBoolean("optimizations.cpu.allow-experimental-region-ticking", false);
            final Map<String, String> applied = new LinkedHashMap<>();
            applyJvmProperties(mode, preferExisting, config, applied);
            applyHunterThreadingProperties(mode, allowExperimentalRegionTicking, applied);

            lastSnapshot = new OptimizationSnapshot(true, mode, CPU, applied, "early preferences");
            logSnapshot(lastSnapshot);
            return lastSnapshot;
        } catch (final Exception ex) {
            LOGGER.warn("Failed to read early HunterCore CPU preferences from {}, using startup defaults", preferencesPath, ex);
            return lastSnapshot;
        }
    }

    public static synchronized OptimizationSnapshot applyPreferences(final HunterPreferences preferences) {
        if (!preferences.booleanValue("optimizations.cpu.enabled", true)) {
            System.setProperty(PROP_MANAGED, "false");
            lastSnapshot = new OptimizationSnapshot(false, MODE_SINGLE_THREAD, CPU, Map.of(), "disabled in preferences.yml");
            return lastSnapshot;
        }

        final String mode = normalizeMode(preferences.stringValue("optimizations.cpu.mode", DEFAULT_MODE));
        final boolean preferExisting = preferences.booleanValue("optimizations.cpu.prefer-existing-jvm-flags", true);
        final boolean allowExperimentalRegionTicking = preferences.booleanValue("optimizations.cpu.allow-experimental-region-ticking", false);
        final Map<String, String> applied = new LinkedHashMap<>();
        applyJvmProperties(mode, preferExisting, preferences, applied);
        applyHunterThreadingProperties(mode, allowExperimentalRegionTicking, applied);

        lastSnapshot = new OptimizationSnapshot(true, mode, CPU, applied, preferExisting ? "preferences, existing JVM flags preserved" : "preferences, JVM flags overwritten");
        logSnapshot(lastSnapshot);
        return lastSnapshot;
    }

    public static OptimizationSnapshot lastSnapshot() {
        return lastSnapshot;
    }

    public static boolean managedThreading() {
        return Boolean.parseBoolean(System.getProperty(PROP_MANAGED, "true"));
    }

    public static String currentMode() {
        return normalizeMode(System.getProperty(PROP_MODE, DEFAULT_MODE));
    }

    public static boolean singleThreadMode() {
        return managedThreading() && MODE_SINGLE_THREAD.equals(currentMode());
    }

    public static boolean multiThreadMode() {
        if (!managedThreading()) {
            return false;
        }
        final String mode = currentMode();
        return mode.equals(MODE_MULTI_THREAD) || mode.equals(MODE_HIGH_CLOCK) || mode.equals(MODE_HIGH_CORE);
    }

    public static boolean experimentalRegionTickingAllowed() {
        return managedThreading() && Boolean.parseBoolean(System.getProperty(PROP_ALLOW_EXPERIMENTAL_REGION_TICKING, "false"));
    }

    public static int recommendedPathfindingThreads() {
        return intProperty(PROP_PATHFINDING_THREADS, pathfindingThreads(currentMode()));
    }

    public static int recommendedTrackerThreads() {
        return intProperty(PROP_TRACKER_THREADS, trackerThreads(currentMode()));
    }

    public static int recommendedChunkSendThreads() {
        return intProperty(PROP_CHUNK_SEND_THREADS, chunkSendThreads(currentMode()));
    }

    public static int recommendedRegionTickThreads() {
        return intProperty(PROP_REGION_TICK_THREADS, regionTickThreads(currentMode()));
    }

    public static String normalizeMode(final String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MODE;
        }
        final String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case MODE_SINGLE_THREAD, "single", "stable", "compat", "compatible", "compatibility", "safe", "paper" -> MODE_SINGLE_THREAD;
            case "clock", "high-clock" -> MODE_HIGH_CLOCK;
            case "core", "high-core" -> MODE_HIGH_CORE;
            case MODE_MULTI_THREAD, "multi", "multithread", "multi-threaded", "parallel", "performance", "balanced" -> MODE_MULTI_THREAD;
            default -> {
                LOGGER.warn("Unknown HunterCore CPU mode '{}', using {}", raw, DEFAULT_MODE);
                yield DEFAULT_MODE;
            }
        };
    }

    public static boolean validMode(final String raw) {
        if (raw == null) {
            return false;
        }
        final String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.equals(MODE_SINGLE_THREAD) || normalized.equals(MODE_HIGH_CLOCK) || normalized.equals(MODE_HIGH_CORE) || normalized.equals(MODE_MULTI_THREAD)
            || normalized.equals("single") || normalized.equals("multi")
            || normalized.equals("stable") || normalized.equals("performance")
            || normalized.equals("high-clock") || normalized.equals("high-core")
            || normalized.equals("clock") || normalized.equals("core")
            || normalized.equals("balanced");
    }

    private static OptimizationSnapshot applyMode(
        final String mode,
        final boolean preferExisting,
        final boolean allowExperimentalRegionTicking,
        final String reason
    ) {
        final Map<String, String> applied = new LinkedHashMap<>();
        applyJvmProperties(mode, preferExisting, (YamlConfiguration) null, applied);
        applyHunterThreadingProperties(mode, allowExperimentalRegionTicking, applied);

        lastSnapshot = new OptimizationSnapshot(true, mode, CPU, applied, reason);
        logSnapshot(lastSnapshot);
        return lastSnapshot;
    }

    private static void applyJvmProperties(
        final String mode,
        final boolean preferExisting,
        final YamlConfiguration preferences,
        final Map<String, String> applied
    ) {
        setProperty("Paper.WorkerThreadCount", resolveThreadSetting(
            preferences == null ? "auto" : preferences.getString("optimizations.cpu.paper-worker-threads", "auto"),
            workerThreads(mode)
        ), preferExisting, applied);
        setProperty("DivineMC.WorkerThreadCount", resolveThreadSetting(
            preferences == null ? "auto" : preferences.getString("optimizations.cpu.divine-worker-threads", "auto"),
            workerThreads(mode)
        ), preferExisting, applied);
        setProperty("io.netty.eventLoopThreads", resolveThreadSetting(
            preferences == null ? "auto" : preferences.getString("optimizations.cpu.netty-io-threads", "auto"),
            nettyThreads(mode)
        ), preferExisting, applied);
        setProperty("io.netty.allocator.numDirectArenas", Integer.toString(nettyArenaCount(mode)), preferExisting, applied);
        setProperty("io.netty.allocator.numHeapArenas", Integer.toString(nettyArenaCount(mode)), preferExisting, applied);
        setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", resolveThreadSetting(
            preferences == null ? "auto" : preferences.getString("optimizations.cpu.common-pool-parallelism", "auto"),
            commonPoolParallelism(mode)
        ), preferExisting, applied);
    }

    private static void applyJvmProperties(
        final String mode,
        final boolean preferExisting,
        final HunterPreferences preferences,
        final Map<String, String> applied
    ) {
        setProperty("Paper.WorkerThreadCount", resolveThreadSetting(preferences.stringValue("optimizations.cpu.paper-worker-threads", "auto"), workerThreads(mode)), preferExisting, applied);
        setProperty("DivineMC.WorkerThreadCount", resolveThreadSetting(preferences.stringValue("optimizations.cpu.divine-worker-threads", "auto"), workerThreads(mode)), preferExisting, applied);
        setProperty("io.netty.eventLoopThreads", resolveThreadSetting(preferences.stringValue("optimizations.cpu.netty-io-threads", "auto"), nettyThreads(mode)), preferExisting, applied);
        setProperty("io.netty.allocator.numDirectArenas", Integer.toString(nettyArenaCount(mode)), preferExisting, applied);
        setProperty("io.netty.allocator.numHeapArenas", Integer.toString(nettyArenaCount(mode)), preferExisting, applied);
        setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", resolveThreadSetting(preferences.stringValue("optimizations.cpu.common-pool-parallelism", "auto"), commonPoolParallelism(mode)), preferExisting, applied);
    }

    private static void applyHunterThreadingProperties(final String mode, final boolean allowExperimentalRegionTicking, final Map<String, String> applied) {
        setInternalProperty(PROP_MANAGED, "true", applied);
        setInternalProperty(PROP_MODE, mode, applied);
        setInternalProperty(PROP_ALLOW_EXPERIMENTAL_REGION_TICKING, Boolean.toString(allowExperimentalRegionTicking), applied);
        setInternalProperty(PROP_PATHFINDING_THREADS, Integer.toString(pathfindingThreads(mode)), applied);
        setInternalProperty(PROP_TRACKER_THREADS, Integer.toString(trackerThreads(mode)), applied);
        setInternalProperty(PROP_CHUNK_SEND_THREADS, Integer.toString(chunkSendThreads(mode)), applied);
        setInternalProperty(PROP_REGION_TICK_THREADS, Integer.toString(regionTickThreads(mode)), applied);
    }

    private static void setInternalProperty(final String key, final String value, final Map<String, String> applied) {
        System.setProperty(key, value);
        applied.put(key, value);
    }

    private static void setProperty(final String key, final String value, final boolean preferExisting, final Map<String, String> applied) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (preferExisting && System.getProperty(key) != null) {
            return;
        }
        System.setProperty(key, value);
        applied.put(key, value);
    }

    private static String resolveThreadSetting(final String raw, final int autoValue) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("auto")) {
            return Integer.toString(autoValue);
        }
        try {
            final int value = Integer.parseInt(raw.trim());
            return Integer.toString(Math.max(1, Math.min(value, Math.max(4, CPU * 2))));
        } catch (final NumberFormatException ex) {
            LOGGER.warn("Invalid HunterCore CPU optimization value '{}', using auto value {}", raw, autoValue);
            return Integer.toString(autoValue);
        }
    }

    private static int workerThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode) || CPU <= 2) {
            return 1;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.35D), 2, 8);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.65D), 3, 20);
        }
        return clamp((int) Math.round(CPU * 0.50D), 2, 16);
    }

    private static int pathfindingThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 0;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.15D), 1, 3);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.35D), 2, 10);
        }
        return clamp((int) Math.round(CPU * 0.25D), 1, 8);
    }

    private static int trackerThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 0;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.15D), 1, 3);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.35D), 2, 10);
        }
        return clamp((int) Math.round(CPU * 0.25D), 1, 8);
    }

    private static int chunkSendThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 0;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.15D), 1, 3);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.30D), 2, 8);
        }
        return clamp((int) Math.round(CPU * 0.25D), 1, 8);
    }

    private static int regionTickThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 1;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.30D), 2, 6);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.70D), 4, 24);
        }
        return clamp((int) Math.round(CPU * 0.50D), 2, 24);
    }

    private static int nettyThreads(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode) || CPU <= 2) {
            return 1;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.20D), 2, 4);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp((int) Math.round(CPU * 0.45D), 3, 16);
        }
        return clamp((int) Math.round(CPU * 0.35D), 2, 12);
    }

    private static int nettyArenaCount(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 1;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp(Math.max(2, CPU / 2), 2, 8);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp(CPU, 4, 24);
        }
        return clamp(CPU, 2, 16);
    }

    private static int commonPoolParallelism(final String mode) {
        if (MODE_SINGLE_THREAD.equals(mode)) {
            return 1;
        }
        if (MODE_HIGH_CLOCK.equals(mode)) {
            return clamp(Math.max(2, CPU / 2), 2, 6);
        }
        if (MODE_HIGH_CORE.equals(mode)) {
            return clamp(CPU - 1, 4, 32);
        }
        return clamp(CPU - 2, 2, 24);
    }

    private static int intProperty(final String key, final int fallback) {
        final String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void logSnapshot(final OptimizationSnapshot snapshot) {
        if (!snapshot.enabled() || snapshot.appliedProperties().isEmpty()) {
            LOGGER.info("HunterCore CPU optimizer: {} on {} CPU threads", snapshot.reason(), snapshot.cpuThreads());
            return;
        }
        LOGGER.info("HunterCore CPU optimizer applied {} setting(s) in {} mode on {} CPU threads: {}",
            snapshot.appliedProperties().size(),
            snapshot.mode(),
            snapshot.cpuThreads(),
            snapshot.appliedProperties());
    }

    public record OptimizationSnapshot(boolean enabled, String mode, int cpuThreads, Map<String, String> appliedProperties, String reason) {
        public static OptimizationSnapshot empty() {
            return new OptimizationSnapshot(false, DEFAULT_MODE, CPU, Map.of(), "not applied");
        }

        public String summary() {
            if (!this.enabled) {
                return "disabled (" + this.reason + ")";
            }
            if (this.appliedProperties.isEmpty()) {
                return "enabled, no new JVM properties applied in " + this.mode + " mode (" + this.reason + ")";
            }
            return "enabled, " + this.appliedProperties.size() + " JVM properties applied in " + this.mode + " mode (" + this.reason.toLowerCase(Locale.ROOT) + ")";
        }
    }
}
