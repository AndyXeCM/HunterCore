package org.huntercore.command;

import io.papermc.paper.command.CommandUtil;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;
import org.huntercore.api.HunterCommandExtension;
import org.huntercore.bootstrap.HunterCoreBootstrap;
import org.huntercore.bootstrap.HunterCoreRuntime;
import org.huntercore.plugin.HunterBundledPluginInstaller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HunterCoreCommand extends Command {
    public static final String COMMAND_LABEL = "huntercore";
    public static final String BASE_PERMISSION = "huntercore.command";

    private final Map<String, HunterCommandExtension> builtIns = new LinkedHashMap<>();

    public HunterCoreCommand() {
        super(COMMAND_LABEL, "HunterCore related commands", "/huntercore [about|system|plugins|reload]", List.of("hc"));
        HunterCoreBootstrap.init();
        this.setPermission(BASE_PERMISSION);
        this.registerBuiltIn(new AboutExtension());
        this.registerBuiltIn(new SystemExtension());
        this.registerBuiltIn(new PluginsExtension());
        this.registerBuiltIn(new ReloadExtension());
        this.registerPermissions();
    }

    @Override
    public boolean execute(@NotNull final CommandSender sender, @NotNull final String commandLabel, @NotNull final String[] args) {
        if (!sender.hasPermission(BASE_PERMISSION)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(HunterMessages.about());
            return true;
        }

        final HunterCommandExtension extension = this.resolve(args[0]);
        if (extension == null) {
            sender.sendMessage("Usage: " + this.usageMessage);
            return true;
        }

        final String permission = extension.permission();
        if (permission != null && !sender.hasPermission(permission)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return true;
        }

        return extension.execute(sender, args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
    }

    @Override
    public @NotNull List<String> tabComplete(
        @NotNull final CommandSender sender,
        @NotNull final String alias,
        @NotNull final String[] args,
        @Nullable final Location location
    ) {
        if (args.length <= 1) {
            final List<String> labels = new ArrayList<>();
            for (final HunterCommandExtension extension : this.availableExtensions(sender)) {
                labels.add(extension.name());
                labels.addAll(extension.aliases());
            }
            return CommandUtil.getListMatchingLast(sender, args, labels);
        }

        final HunterCommandExtension extension = this.resolve(args[0]);
        if (extension == null || !this.canUse(sender, extension)) {
            return List.of();
        }
        return extension.tabComplete(sender, args[0].toLowerCase(Locale.ROOT), Arrays.copyOfRange(args, 1, args.length));
    }

    private void registerBuiltIn(final HunterCommandExtension extension) {
        this.builtIns.put(extension.name().toLowerCase(Locale.ROOT), extension);
    }

    private HunterCommandExtension resolve(final String label) {
        final String normalized = label.toLowerCase(Locale.ROOT);
        for (final HunterCommandExtension extension : this.allExtensions()) {
            if (extension.name().equalsIgnoreCase(normalized)) {
                return extension;
            }
            for (final String alias : extension.aliases()) {
                if (alias.equalsIgnoreCase(normalized)) {
                    return extension;
                }
            }
        }
        return null;
    }

    private Collection<HunterCommandExtension> allExtensions() {
        final List<HunterCommandExtension> extensions = new ArrayList<>(this.builtIns.values());
        extensions.addAll(HunterCoreRuntime.get().commandExtensions());
        return extensions;
    }

    private Collection<HunterCommandExtension> availableExtensions(final CommandSender sender) {
        final List<HunterCommandExtension> extensions = new ArrayList<>();
        for (final HunterCommandExtension extension : this.allExtensions()) {
            if (this.canUse(sender, extension)) {
                extensions.add(extension);
            }
        }
        return extensions;
    }

    private boolean canUse(final CommandSender sender, final HunterCommandExtension extension) {
        final String permission = extension.permission();
        return permission == null || sender.hasPermission(permission);
    }

    private void registerPermissions() {
        final PluginManager pluginManager = Bukkit.getServer().getPluginManager();
        this.addPermission(pluginManager, new Permission(BASE_PERMISSION, PermissionDefault.TRUE));
        for (final HunterCommandExtension extension : this.builtIns.values()) {
            final String permission = extension.permission();
            if (permission != null) {
                this.addPermission(pluginManager, new Permission(permission, PermissionDefault.TRUE));
            }
        }
    }

    private void addPermission(final PluginManager pluginManager, final Permission permission) {
        if (pluginManager.getPermission(permission.getName()) == null) {
            pluginManager.addPermission(permission);
        }
    }

    private static final class AboutExtension implements HunterCommandExtension {
        @Override
        public @NotNull String name() {
            return "about";
        }

        @Override
        public @NotNull Collection<String> aliases() {
            return List.of("info");
        }

        @Override
        public @Nullable String permission() {
            return BASE_PERMISSION + ".about";
        }

        @Override
        public boolean execute(@NotNull final CommandSender sender, @NotNull final String label, @NotNull final String[] args) {
            sender.sendMessage(HunterMessages.about());
            return true;
        }
    }

    private static final class SystemExtension implements HunterCommandExtension {
        @Override
        public @NotNull String name() {
            return "system";
        }

        @Override
        public @NotNull Collection<String> aliases() {
            return List.of("sys");
        }

        @Override
        public @Nullable String permission() {
            return BASE_PERMISSION + ".system";
        }

        @Override
        public boolean execute(@NotNull final CommandSender sender, @NotNull final String label, @NotNull final String[] args) {
            sender.sendMessage(HunterMessages.systemInfo());
            return true;
        }
    }

    private static final class PluginsExtension implements HunterCommandExtension {
        @Override
        public @NotNull String name() {
            return "plugins";
        }

        @Override
        public @Nullable String permission() {
            return BASE_PERMISSION + ".plugins";
        }

        @Override
        public boolean execute(@NotNull final CommandSender sender, @NotNull final String label, @NotNull final String[] args) {
            final HunterBundledPluginInstaller.InstallReport report = HunterCoreRuntime.get().lastInstallReport();
            sender.sendMessage("HunterCore bundled plugins (" + report.plugins().size() + "):");
            if (report.results().isEmpty()) {
                for (final var plugin : HunterCoreRuntime.get().bundledPlugins()) {
                    sender.sendMessage("- " + plugin.name() + " " + plugin.version() + " -> " + plugin.fileName());
                }
                return true;
            }
            for (final HunterBundledPluginInstaller.InstallResult result : report.results()) {
                sender.sendMessage("- " + result.plugin().name() + " " + result.plugin().version() + ": " + result.state().name().toLowerCase(Locale.ROOT) + " (" + result.message() + ")");
            }
            return true;
        }
    }

    private static final class ReloadExtension implements HunterCommandExtension {
        @Override
        public @NotNull String name() {
            return "reload";
        }

        @Override
        public @Nullable String permission() {
            return BASE_PERMISSION + ".reload";
        }

        @Override
        public boolean execute(@NotNull final CommandSender sender, @NotNull final String label, @NotNull final String[] args) {
            HunterBundledPluginInstaller.install(Bukkit.getPluginsFolder().toPath());
            sender.sendMessage("HunterCore bundled plugin configuration reloaded. Restart the server to load newly installed plugin jars.");
            return true;
        }
    }
}
