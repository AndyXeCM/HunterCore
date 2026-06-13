package org.huntercore.plugins.tools;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import org.bukkit.World;

final class HunterRuntimeSampler {
    private HunterRuntimeSampler() {
    }

    static AdaptiveBudget adaptiveBudget(final HunterToolsPreferences preferences, final double mspt) {
        final int baseFakePlayerInterval = Math.max(1, preferences.intValue("modules.ai.fake-players.interval-seconds", 6));
        final double warningMspt = preferences.doubleValue("modules.ai.adaptive-throttling.warning-mspt", 40.0D);
        final double criticalMspt = preferences.doubleValue("modules.ai.adaptive-throttling.critical-mspt", 55.0D);
        final double severeMspt = preferences.doubleValue("modules.ai.adaptive-throttling.severe-mspt", 75.0D);
        final int baseGuestCacheMillis = Math.max(0, preferences.intValue("modules.web-panel.status-cache-millis", 1000));
        final int basePlayerCacheMillis = Math.max(0, preferences.intValue("modules.web-panel.status-cache-player-millis", 700));
        final int baseAdminCacheMillis = Math.max(0, preferences.intValue("modules.web-panel.status-cache-admin-millis", 400));

        double factor = 1.0D;
        if (mspt >= severeMspt) {
            factor = 4.0D;
        } else if (mspt >= criticalMspt) {
            factor = 2.5D;
        } else if (mspt >= warningMspt) {
            factor = 1.5D;
        }

        return new AdaptiveBudget(
            factor,
            Math.max(baseFakePlayerInterval, (int) Math.round(baseFakePlayerInterval * factor)),
            scaledCache(baseGuestCacheMillis, factor, 350, 8000),
            scaledCache(basePlayerCacheMillis, factor, 250, 5000),
            scaledCache(baseAdminCacheMillis, factor, 150, 2500)
        );
    }

    private static int scaledCache(final int baseMillis, final double factor, final int floor, final int ceiling) {
        if (baseMillis <= 0) {
            return 0;
        }
        return Math.max(floor, Math.min(ceiling, (int) Math.round(baseMillis * factor)));
    }

    static List<QueuePressure> queuePressures() {
        return List.of(
            executorPressure("chunk-send", reflectExecutor("org.bxteam.divinemc.async.AsyncChunkSend", "POOL")),
            executorPressure("entity-tracker", reflectExecutor("org.bxteam.divinemc.async.tracking.MultithreadedTracker", "TRACKER_EXECUTOR")),
            executorPressure("pathfinding", reflectExecutor("org.bxteam.divinemc.async.pathfinding.AsyncPath", "EXECUTOR"))
        );
    }

    private static QueuePressure executorPressure(final String name, final ExecutorService executor) {
        if (!(executor instanceof final ThreadPoolExecutor pool)) {
            return new QueuePressure(name, false, 0, 0, 0, -1, "inactive");
        }
        final int maxThreads = Math.max(pool.getMaximumPoolSize(), pool.getPoolSize());
        final int remaining = pool.getQueue().remainingCapacity();
        final int queued = pool.getQueue().size();
        final double saturation = maxThreads <= 0 ? 0.0D : (double) pool.getActiveCount() / (double) maxThreads;
        final double queuedPressure;
        if (remaining < 0) {
            queuedPressure = queued > 0 ? Math.min(1.0D, queued / 128.0D) : 0.0D;
        } else {
            queuedPressure = (double) queued / (double) Math.max(1, queued + remaining);
        }
        final double pressure = Math.max(saturation, queuedPressure);
        final String state = pressure >= 0.85D ? "critical" : pressure >= 0.55D ? "warning" : "ok";
        return new QueuePressure(name, true, queued, pool.getActiveCount(), maxThreads, remaining, state);
    }

    private static ExecutorService reflectExecutor(final String className, final String fieldName) {
        try {
            final Class<?> type = Class.forName(className);
            final Field field = type.getField(fieldName);
            final Object value = field.get(null);
            return value instanceof ExecutorService executor ? executor : null;
        } catch (final ReflectiveOperationException ignored) {
            return null;
        }
    }

    static List<HotPathSample> hotPathSamples(
        final List<World> worlds,
        final int onlinePlayers,
        final int fakePlayers,
        final List<QueuePressure> queues
    ) {
        int loadedChunks = 0;
        int entities = 0;
        for (final World world : worlds) {
            loadedChunks += world.getLoadedChunks().length;
            entities += world.getEntityCount();
        }

        final List<HotPathSample> samples = new ArrayList<>();
        samples.add(new HotPathSample(
            "world-entities",
            entities / 1200.0D,
            entities + " entities across loaded worlds"
        ));
        samples.add(new HotPathSample(
            "loaded-chunks",
            loadedChunks / 3000.0D,
            loadedChunks + " loaded chunks"
        ));
        samples.add(new HotPathSample(
            "player-network",
            onlinePlayers / 24.0D,
            onlinePlayers + " online players"
        ));
        samples.add(new HotPathSample(
            "fake-player-ai",
            fakePlayers / 6.0D,
            fakePlayers + " active real fake players"
        ));
        for (final QueuePressure queue : queues) {
            if (!queue.active()) {
                continue;
            }
            final double queueScore = Math.max(
                queue.maxThreads() <= 0 ? 0.0D : (double) queue.activeThreads() / (double) queue.maxThreads(),
                queue.queued() / 24.0D
            );
            samples.add(new HotPathSample(
                queue.name(),
                queueScore,
                queue.queued() + " queued, " + queue.activeThreads() + "/" + queue.maxThreads() + " workers busy"
            ));
        }
        samples.sort(Comparator.comparingDouble(HotPathSample::score).reversed());
        return samples.stream()
            .limit(5)
            .map(sample -> new HotPathSample(
                sample.category(),
                Math.round(sample.score() * 100.0D) / 100.0D,
                sample.detail()
            ))
            .toList();
    }
}

record AdaptiveBudget(
    double aiThrottleFactor,
    int fakePlayerIntervalSeconds,
    int guestCacheMillis,
    int playerCacheMillis,
    int adminCacheMillis
) {
    static AdaptiveBudget defaults(final double mspt) {
        final double factor = mspt >= 75.0D ? 4.0D : mspt >= 55.0D ? 2.5D : mspt >= 40.0D ? 1.5D : 1.0D;
        return new AdaptiveBudget(factor, 6, 1000, 700, 400);
    }

    String factorLabel() {
        return String.format(Locale.ROOT, "%.1fx", this.aiThrottleFactor);
    }
}

record QueuePressure(
    String name,
    boolean active,
    int queued,
    int activeThreads,
    int maxThreads,
    int remainingCapacity,
    String state
) {
}

record HotPathSample(String category, double score, String detail) {
}
