package org.huntercore.command;

import io.papermc.paper.command.CommandUtil;
import java.io.IOException;
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
import org.huntercore.config.HunterPreferences;
import org.huntercore.plugin.HunterBundledPluginInstaller;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HunterCoreCommand extends Command {
    public static final String COMMAND_LABEL = "huntercore";
    public static final String BASE_PERMISSION = "huntercore.command";

    private final Map<String, HunterCommandExtension> builtIns = new LinkedHashMap<>();

    public HunterCoreCommand() {
        super(COMMAND_LABEL, "HunterCore related commands", "/huntercore [about|system|plugins|preferences|reload]", List.of("hc"));
        HunterCoreBootstrap.init();
        this.setPermission(BASE_PERMISSION);
        this.registerBuiltIn(new AboutExtension());
        this.registerBuiltIn(new SystemExtension());
        this.registerBuiltIn(new PluginsExtension());
        this.registerBuiltIn(new PreferencesExtension());
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
            sender.sendMessage("HunterCore preferences reloaded. Restart the server to unload disabled bundled plugin jars.");
            return true;
        }
    }

    private static final class PreferencesExtension implements HunterCommandExtension {
        private static final List<String> MODULES = List.of("tps-display", "sidebar", "motd", "essentials", "management", "fake-players", "real-fake-players", "npcs");
        private static final List<String> ESSENTIALS_COMMANDS = List.of("heal", "feed", "fly", "gm", "day", "night", "sun", "rain", "thunder", "broadcast", "clearchat", "speed", "spawn", "setspawn", "back", "hat", "craft", "enderchest", "trash");
        private static final List<String> MANAGEMENT_COMMANDS = List.of("reload", "modules", "plugins", "memory", "gc", "threads", "command", "module", "optimize", "motd");
        private static final List<String> ACTOR_COMMANDS = List.of("spawn", "remove", "list", "tp", "tphere", "look", "pose", "click", "info", "clear");
        private static final List<String> REAL_FAKE_PLAYER_COMMANDS = List.of(
            "spawn", "remove", "kill", "list", "tp", "tphere", "look", "sneak", "sprint", "jump", "use", "attack",
            "stop", "click", "drop", "dropstack", "swap", "gm", "gamemode", "slot", "info", "clear"
        );

        @Override
        public @NotNull String name() {
            return "preferences";
        }

        @Override
        public @NotNull Collection<String> aliases() {
            return List.of("prefs", "pref");
        }

        @Override
        public @Nullable String permission() {
            return BASE_PERMISSION + ".preferences";
        }

        @Override
        public boolean execute(@NotNull final CommandSender sender, @NotNull final String label, @NotNull final String[] args) {
            final HunterPreferences preferences = preferences();
            if (preferences == null) {
                sender.sendMessage("HunterCore preferences are not loaded yet.");
                return true;
            }
            if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
                this.list(sender, preferences);
                return true;
            }
            final String sub = args[0].toLowerCase(Locale.ROOT);
            try {
                switch (sub) {
                    case "reload" -> {
                        HunterBundledPluginInstaller.install(Bukkit.getPluginsFolder().toPath());
                        sender.sendMessage("HunterCore preferences reloaded from disk.");
                    }
                    case "bundled" -> this.toggleBundled(sender, preferences, args);
                    case "module" -> this.toggleModule(sender, preferences, args);
                    case "command" -> this.toggleCommand(sender, preferences, args);
                    default -> sender.sendMessage("Usage: /huntercore preferences <list|reload|bundled|module|command>");
                }
            } catch (final IOException ex) {
                sender.sendMessage("Failed to save HunterCore preferences: " + ex.getMessage());
            }
            return true;
        }

        @Override
        public @NotNull List<String> tabComplete(@NotNull final CommandSender sender, @NotNull final String alias, @NotNull final String[] args) {
            if (args.length == 1) {
                return CommandUtil.getListMatchingLast(sender, args, List.of("list", "reload", "bundled", "module", "command"));
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("bundled")) {
                final HunterPreferences preferences = preferences();
                if (preferences == null) {
                    return List.of();
                }
                return CommandUtil.getListMatchingLast(sender, args, HunterCoreRuntime.get().bundledPlugins().stream().map(plugin -> plugin.id()).toList());
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
                return CommandUtil.getListMatchingLast(sender, args, MODULES);
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("command")) {
                return CommandUtil.getListMatchingLast(sender, args, List.of("essentials", "management", "fake-players", "real-fake-players", "npcs"));
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("command")) {
                final String module = args[1].toLowerCase(Locale.ROOT);
                if (module.equals("essentials")) {
                    return CommandUtil.getListMatchingLast(sender, args, ESSENTIALS_COMMANDS);
                }
                if (module.equals("management")) {
                    return CommandUtil.getListMatchingLast(sender, args, MANAGEMENT_COMMANDS);
                }
                if (module.equals("fake-players") || module.equals("npcs")) {
                    return CommandUtil.getListMatchingLast(sender, args, ACTOR_COMMANDS);
                }
                if (module.equals("real-fake-players")) {
                    return CommandUtil.getListMatchingLast(sender, args, REAL_FAKE_PLAYER_COMMANDS);
                }
            }
            if ((args.length == 3 && (args[0].equalsIgnoreCase("bundled") || args[0].equalsIgnoreCase("module")))
                || (args.length == 4 && args[0].equalsIgnoreCase("command"))) {
                return CommandUtil.getListMatchingLast(sender, args, List.of("on", "off"));
            }
            return List.of();
        }

        private void list(final CommandSender sender, final HunterPreferences preferences) {
            sender.sendMessage("HunterCore preferences: " + preferences.path());
            sender.sendMessage("Bundled plugin installer: " + (preferences.bundledPluginsEnabled() ? "enabled" : "disabled")
                + ", update-existing=" + preferences.updateExistingBundledPlugins()
                + ", parallel-install=" + preferences.parallelBundledPluginInstall()
                + " (" + preferences.bundledPluginInstallWorkers() + " workers)");
            sender.sendMessage("Bundled plugins:");
            for (final var plugin : HunterCoreRuntime.get().bundledPlugins()) {
                sender.sendMessage("- " + plugin.id() + ": " + (preferences.bundledPluginEnabled(plugin.id()) ? "enabled" : "disabled"));
            }
            sender.sendMessage("Modules:");
            for (final String module : MODULES) {
                sender.sendMessage("- " + module + ": " + (preferences.moduleEnabled(module) ? "enabled" : "disabled"));
            }
        }

        private void toggleBundled(final CommandSender sender, final HunterPreferences preferences, final String[] args) throws IOException {
            if (args.length != 3) {
                sender.sendMessage("Usage: /huntercore preferences bundled <plugin-id> <on|off>");
                return;
            }
            final Boolean enabled = parseToggle(args[2]);
            if (enabled == null) {
                sender.sendMessage("Use on/off.");
                return;
            }
            preferences.setBundledPluginEnabled(args[1], enabled);
            HunterBundledPluginInstaller.install(Bukkit.getPluginsFolder().toPath());
            sender.sendMessage("Bundled plugin " + HunterPreferences.normalize(args[1]) + " set to " + enabled + ". Restart to unload already loaded jars.");
        }

        private void toggleModule(final CommandSender sender, final HunterPreferences preferences, final String[] args) throws IOException {
            if (args.length != 3) {
                sender.sendMessage("Usage: /huntercore preferences module <module> <on|off>");
                return;
            }
            final String module = HunterPreferences.normalize(args[1]);
            if (!MODULES.contains(module)) {
                sender.sendMessage("Unknown module. Available: " + String.join(", ", MODULES));
                return;
            }
            final Boolean enabled = parseToggle(args[2]);
            if (enabled == null) {
                sender.sendMessage("Use on/off.");
                return;
            }
            preferences.setModuleEnabled(module, enabled);
            sender.sendMessage("Module " + module + " set to " + enabled + ". Run /hunteradmin reload if HunterTools is already loaded.");
        }

        private void toggleCommand(final CommandSender sender, final HunterPreferences preferences, final String[] args) throws IOException {
            if (args.length != 4) {
                sender.sendMessage("Usage: /huntercore preferences command <essentials|management|fake-players|real-fake-players|npcs> <command> <on|off>");
                return;
            }
            final String module = HunterPreferences.normalize(args[1]);
            if (!module.equals("essentials") && !module.equals("management") && !module.equals("fake-players") && !module.equals("real-fake-players") && !module.equals("npcs")) {
                sender.sendMessage("Command toggles are available for essentials, management, fake-players, real-fake-players, and npcs.");
                return;
            }
            final Boolean enabled = parseToggle(args[3]);
            if (enabled == null) {
                sender.sendMessage("Use on/off.");
                return;
            }
            preferences.setCommandEnabled(module, args[2], enabled);
            sender.sendMessage("Command " + module + "." + HunterPreferences.normalize(args[2]) + " set to " + enabled + ". Run /hunteradmin reload if needed.");
        }

        private static HunterPreferences preferences() {
            HunterPreferences preferences = HunterCoreRuntime.get().preferences();
            if (preferences == null) {
                HunterBundledPluginInstaller.install(Bukkit.getPluginsFolder().toPath());
                preferences = HunterCoreRuntime.get().preferences();
            }
            return preferences;
        }

        private static Boolean parseToggle(final String input) {
            return switch (input.toLowerCase(Locale.ROOT)) {
                case "on", "enable", "enabled", "true", "yes" -> Boolean.TRUE;
                case "off", "disable", "disabled", "false", "no" -> Boolean.FALSE;
                default -> null;
            };
        }
    }
}
