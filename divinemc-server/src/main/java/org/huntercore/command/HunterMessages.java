package org.huntercore.command;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.huntercore.bootstrap.HunterCoreRuntime;

public final class HunterMessages {
    private HunterMessages() {
    }

    public static Component about() {
        return Component.text()
            .append(Component.text("HunterCore", NamedTextColor.GOLD))
            .append(Component.text(" ", NamedTextColor.GRAY))
            .append(Component.text(HunterCoreRuntime.get().version(), NamedTextColor.YELLOW))
            .append(Component.newline())
            .append(Component.text("A private DivineMC/Purpur/Paper based Minecraft server core.", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("Minecraft: ", NamedTextColor.GRAY))
            .append(Component.text(Bukkit.getMinecraftVersion(), NamedTextColor.WHITE))
            .append(Component.text("  Bukkit: ", NamedTextColor.GRAY))
            .append(Component.text(Bukkit.getBukkitVersion(), NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text("Bundled plugins: ", NamedTextColor.GRAY))
            .append(Component.text(HunterCoreRuntime.get().bundledPlugins().size(), NamedTextColor.WHITE))
            .build();
    }

    public static Component systemInfo() {
        final Runtime runtime = Runtime.getRuntime();
        final long max = runtime.maxMemory();
        final long total = runtime.totalMemory();
        final long free = runtime.freeMemory();
        final long used = total - free;
        final Duration uptime = Duration.ofMillis(ManagementFactory.getRuntimeMXBean().getUptime());

        return Component.text()
            .append(Component.text("HunterCore System", NamedTextColor.GOLD))
            .append(Component.newline())
            .append(line("Server", Bukkit.getName() + " " + Bukkit.getVersion()))
            .append(line("Java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")"))
            .append(line("OS", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")))
            .append(line("CPU", runtime.availableProcessors() + " threads"))
            .append(line("Memory", formatBytes(used) + " used / " + formatBytes(total) + " allocated / " + formatBytes(max) + " max"))
            .append(line("Uptime", formatDuration(uptime)))
            .append(line("Players", Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers()))
            .append(line("Plugins folder", Bukkit.getPluginsFolder().getPath()))
            .build();
    }

    private static Component line(final String key, final String value) {
        return Component.text()
            .append(Component.text(key + ": ", NamedTextColor.GRAY))
            .append(Component.text(value, NamedTextColor.WHITE))
            .append(Component.newline())
            .build();
    }

    private static String formatBytes(final long bytes) {
        final double gib = bytes / 1024.0 / 1024.0 / 1024.0;
        if (gib >= 1.0) {
            return String.format(java.util.Locale.ROOT, "%.2f GiB", gib);
        }
        final double mib = bytes / 1024.0 / 1024.0;
        return String.format(java.util.Locale.ROOT, "%.0f MiB", mib);
    }

    private static String formatDuration(final Duration duration) {
        final long days = duration.toDaysPart();
        final int hours = duration.toHoursPart();
        final int minutes = duration.toMinutesPart();
        final int seconds = duration.toSecondsPart();
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m " + seconds + "s";
        }
        return minutes + "m " + seconds + "s";
    }
}
