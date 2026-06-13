package org.huntercore.plugins.tools;

import java.util.List;
import java.util.Locale;

record MetricsSnapshot(
    double tps1,
    double tps5,
    double tps15,
    double mspt,
    long usedMemory,
    long totalMemory,
    long maxMemory,
    int onlinePlayers,
    int maxPlayers,
    AdaptiveBudget adaptiveBudget,
    List<QueuePressure> queuePressures,
    List<HotPathSample> hotPathSamples
) {
    static MetricsSnapshot empty() {
        return new MetricsSnapshot(20.0D, 20.0D, 20.0D, 0.0D, 0L, 0L, 0L, 0, 0,
            AdaptiveBudget.defaults(0.0D),
            List.of(),
            List.of()
        );
    }

    String shortTps() {
        return formatTps(this.tps1) + " TPS / " + String.format(Locale.ROOT, "%.1f", this.mspt) + " mspt";
    }

    String memoryLine() {
        return formatBytes(this.usedMemory) + " / " + formatBytes(this.maxMemory);
    }

    static String formatTps(final double tps) {
        return String.format(Locale.ROOT, "%.2f", Math.min(20.0D, tps));
    }

    static String formatBytes(final long bytes) {
        final double gib = bytes / 1024.0D / 1024.0D / 1024.0D;
        if (gib >= 1.0D) {
            return String.format(Locale.ROOT, "%.2f GiB", gib);
        }
        return String.format(Locale.ROOT, "%.0f MiB", bytes / 1024.0D / 1024.0D);
    }
}
