package org.huntercore.plugins.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.huntercore.api.HunterCoreProvider;
import org.huntercore.api.fakeplayer.FakePlayerActionResult;
import org.huntercore.api.fakeplayer.FakePlayerSnapshot;
import org.huntercore.api.fakeplayer.HunterFakePlayerService;
import org.jetbrains.annotations.Nullable;

final class HunterRealFakePlayerManager {
    private static final String MODULE = "real-fake-players";

    private final JavaPlugin plugin;
    private final HunterToolsPreferences preferences;
    private final Map<String, BukkitTask> loops = new HashMap<>();
    private final Map<String, String> clickCommands = new HashMap<>();

    HunterRealFakePlayerManager(final JavaPlugin plugin, final HunterToolsPreferences preferences) {
        this.plugin = plugin;
        this.preferences = preferences;
    }

    void shutdown() {
        this.stopAllLoops();
        if (this.preferences.booleanValue("modules.real-fake-players.remove-on-disable", true)) {
            this.service().removeAll();
        }
        this.clickCommands.clear();
    }

    int liveCount() {
        return this.service().list().size();
    }

    boolean command(final CommandSender sender, final String label, final String[] args) {
        if (!this.preferences.moduleEnabled(MODULE)) {
            sender.sendMessage("HunterCore real fake players module is disabled in preferences.yml.");
            return true;
        }
        if (args.length == 0) {
            this.usage(sender, label);
            return true;
        }
        final String sub = HunterToolsPreferences.normalize(args[0]);
        final String command = sub.equals("kill") ? "remove" : sub;
        if (!this.preferences.commandEnabled(MODULE, command)) {
            sender.sendMessage("HunterCore real fake players command " + command + " is disabled in preferences.yml.");
            return true;
        }
        return switch (sub) {
            case "spawn" -> this.spawn(sender, label, args);
            case "remove", "kill" -> this.remove(sender, label, args);
            case "list" -> this.list(sender);
            case "tp" -> this.teleport(sender, label, args);
            case "tphere" -> this.teleportHere(sender, label, args);
            case "look" -> this.look(sender, label, args);
            case "sneak" -> this.toggle(sender, label, args, "sneak");
            case "sprint" -> this.toggle(sender, label, args, "sprint");
            case "jump" -> this.repeating(sender, label, args, "jump");
            case "use" -> this.repeating(sender, label, args, "use");
            case "attack" -> this.repeating(sender, label, args, "attack");
            case "stop" -> this.stop(sender, label, args);
            case "click" -> this.clickCommand(sender, label, args);
            case "drop" -> this.drop(sender, label, args, false);
            case "dropstack" -> this.drop(sender, label, args, true);
            case "swap" -> this.swap(sender, label, args);
            case "gm", "gamemode" -> this.gameMode(sender, label, args);
            case "slot" -> this.slot(sender, label, args);
            case "info" -> this.info(sender, label, args);
            case "clear" -> this.clear(sender);
            default -> {
                this.usage(sender, label);
                yield true;
            }
        };
    }

    List<String> completions(final String[] args) {
        if (args.length == 1) {
            return matching(args[0], HunterToolsPreferences.realFakePlayerCommands());
        }
        final String sub = HunterToolsPreferences.normalize(args[0]);
        if (args.length == 2 && List.of(
            "remove", "kill", "tp", "tphere", "look", "sneak", "sprint", "jump", "use", "attack", "stop",
            "click", "drop", "dropstack", "swap", "gm", "gamemode", "slot", "info"
        ).contains(sub)) {
            return matching(args[1], this.names());
        }
        if (args.length == 3 && List.of("use", "attack", "jump").contains(sub)) {
            return matching(args[2], List.of("once", "continuous", "stop"));
        }
        if (args.length == 3 && List.of("sneak", "sprint").contains(sub)) {
            return matching(args[2], List.of("on", "off"));
        }
        if (args.length == 3 && sub.equals("look")) {
            return matching(args[2], List.of("north", "south", "east", "west", "up", "down"));
        }
        if (args.length == 3 && List.of("gm", "gamemode").contains(sub)) {
            return matching(args[2], List.of("survival", "creative", "adventure", "spectator"));
        }
        if (args.length == 3 && sub.equals("slot")) {
            return matching(args[2], List.of("1", "2", "3", "4", "5", "6", "7", "8", "9"));
        }
        if ((sub.equals("spawn") && args.length == 3) || (sub.equals("tp") && args.length == 3)) {
            return matching(args[2], Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        return List.of();
    }

    private boolean spawn(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " spawn <name> [world x y z [yaw pitch]]");
            return true;
        }
        final int max = Math.max(1, this.preferences.intValue("modules.real-fake-players.max-active", 16));
        if (this.liveCount() >= max) {
            sender.sendMessage("HunterCore real fake player limit reached: " + max);
            return true;
        }
        final Location location = this.location(sender, args, 2);
        if (location == null) {
            return true;
        }
        this.send(sender, this.service().spawn(args[1], location));
        return true;
    }

    private boolean remove(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " remove <name>");
            return true;
        }
        this.stopLoops(args[1]);
        this.clickCommands.remove(playerId(args[1]));
        this.send(sender, this.service().remove(args[1]));
        return true;
    }

    private boolean list(final CommandSender sender) {
        final List<FakePlayerSnapshot> snapshots = new ArrayList<>(this.service().list());
        snapshots.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        sender.sendMessage(ChatColor.GOLD + "HunterCore real fake players: " + ChatColor.WHITE + snapshots.size() + " live");
        for (final FakePlayerSnapshot snapshot : snapshots) {
            sender.sendMessage("- " + snapshot.name()
                + ": " + snapshot.gameMode().name().toLowerCase(Locale.ROOT)
                + ", " + locationLine(snapshot.location())
                + ", sneaking=" + snapshot.sneaking()
                + ", sprinting=" + snapshot.sprinting()
                + ", loops=" + this.loopLine(snapshot.name()));
        }
        return true;
    }

    private boolean teleport(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " tp <name> [world x y z [yaw pitch]]");
            return true;
        }
        final Location location = this.location(sender, args, 2);
        if (location == null) {
            return true;
        }
        this.send(sender, this.service().teleport(args[1], location));
        return true;
    }

    private boolean teleportHere(final CommandSender sender, final String label, final String[] args) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("Usage: /" + label + " tphere <name> must be run by a player.");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " tphere <name>");
            return true;
        }
        this.send(sender, this.service().teleport(args[1], player.getLocation()));
        return true;
    }

    private boolean look(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2 || args.length > 4) {
            sender.sendMessage("Usage: /" + label + " look <name> [yaw pitch|north|south|east|west|up|down]");
            return true;
        }
        final float[] rotation = this.rotation(sender, label, args, 2);
        if (rotation == null) {
            return true;
        }
        this.send(sender, this.service().look(args[1], rotation[0], rotation[1]));
        return true;
    }

    private boolean toggle(final CommandSender sender, final String label, final String[] args, final String action) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /" + label + " " + action + " <name> <on|off>");
            return true;
        }
        final Boolean enabled = parseToggle(args[2]);
        if (enabled == null) {
            sender.sendMessage("Use on/off.");
            return true;
        }
        final FakePlayerActionResult result = action.equals("sneak")
            ? this.service().setSneaking(args[1], enabled)
            : this.service().setSprinting(args[1], enabled);
        this.send(sender, result);
        return true;
    }

    private boolean repeating(final CommandSender sender, final String label, final String[] args, final String action) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage("Usage: /" + label + " " + action + " <name> [once|continuous|stop]");
            return true;
        }
        final String mode = args.length == 3 ? HunterToolsPreferences.normalize(args[2]) : "once";
        if (mode.equals("stop")) {
            this.stopLoop(args[1], action);
            sender.sendMessage("Stopped " + action + " loop for " + args[1] + ".");
            return true;
        }
        if (mode.equals("continuous")) {
            this.startLoop(sender, args[1], action);
            return true;
        }
        if (!mode.equals("once")) {
            sender.sendMessage("Mode must be once, continuous, or stop.");
            return true;
        }
        this.send(sender, this.runAction(action, args[1]));
        return true;
    }

    private boolean stop(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " stop <name>");
            return true;
        }
        this.stopLoops(args[1]);
        this.send(sender, this.service().stopActions(args[1]));
        return true;
    }

    private boolean clickCommand(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " click <name> [command|clear]");
            return true;
        }
        final var snapshot = this.service().snapshot(args[1]);
        if (snapshot.isEmpty()) {
            sender.sendMessage("Fake player not found: " + args[1]);
            return true;
        }
        final String id = snapshot.get().id();
        if (args.length == 2) {
            final String command = this.clickCommands.getOrDefault(id, "");
            sender.sendMessage("HunterCore real fake player " + snapshot.get().name() + " click command: "
                + (command.isBlank() ? "not configured" : command));
            return true;
        }
        final String command = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
        final String savedCommand = List.of("clear", "none", "off", "-").contains(HunterToolsPreferences.normalize(command)) ? "" : sanitizeCommand(command);
        if (!this.setClickCommand(args[1], savedCommand)) {
            sender.sendMessage("Fake player not found: " + args[1]);
            return true;
        }
        sender.sendMessage("HunterCore real fake player " + snapshot.get().name() + " click command "
            + (savedCommand.isBlank() ? "cleared." : "set."));
        return true;
    }

    private boolean drop(final CommandSender sender, final String label, final String[] args, final boolean stack) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " " + (stack ? "dropstack" : "drop") + " <name>");
            return true;
        }
        this.send(sender, this.service().dropSelected(args[1], stack));
        return true;
    }

    private boolean swap(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /" + label + " swap <name>");
            return true;
        }
        this.send(sender, this.service().swapHands(args[1]));
        return true;
    }

    private boolean gameMode(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /" + label + " gm <name> <survival|creative|adventure|spectator>");
            return true;
        }
        final GameMode mode = parseGameMode(args[2]);
        if (mode == null) {
            sender.sendMessage("Game mode must be survival, creative, adventure, or spectator.");
            return true;
        }
        this.send(sender, this.service().setGameMode(args[1], mode));
        return true;
    }

    private boolean slot(final CommandSender sender, final String label, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /" + label + " slot <name> <1-9>");
            return true;
        }
        try {
            this.send(sender, this.service().setSelectedSlot(args[1], Integer.parseInt(args[2])));
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Hotbar slot must be 1-9.");
        }
        return true;
    }

    private boolean info(final CommandSender sender, final String label, final String[] args) {
        if (args.length > 2) {
            sender.sendMessage("Usage: /" + label + " info [name]");
            return true;
        }
        if (args.length == 1) {
        sender.sendMessage(ChatColor.GOLD + "HunterCore real fake players");
        sender.sendMessage("- True ServerPlayer instances: online list, chunk loading, player events and plugin visibility.");
        sender.sendMessage("- Commands: spawn, remove, list, tp, tphere, look, sneak, sprint, jump, use, attack, stop, click, drop, dropstack, swap, gm, slot, info, clear.");
        sender.sendMessage("- use/attack/jump support once, continuous, and stop.");
        sender.sendMessage("- Click command placeholders: %player%, %player_uuid%, %actor%, %actor_name%, %actor_uuid%, %module%, %world%, %x%, %y%, %z%.");
        return true;
        }
        final String name = args[1];
        final var snapshot = this.service().snapshot(name);
        if (snapshot.isEmpty()) {
            sender.sendMessage("Fake player not found: " + name);
            return true;
        }
        final FakePlayerSnapshot view = snapshot.get();
        sender.sendMessage(ChatColor.GOLD + "HunterCore real fake player " + view.name());
        sender.sendMessage("- uuid: " + view.uuid());
        sender.sendMessage("- game mode: " + view.gameMode().name().toLowerCase(Locale.ROOT));
        sender.sendMessage("- location: " + locationLine(view.location()));
        sender.sendMessage("- sneaking: " + view.sneaking() + ", sprinting: " + view.sprinting() + ", using item: " + view.usingItem());
        sender.sendMessage("- loops: " + this.loopLine(view.name()));
        sender.sendMessage("- click command: " + this.clickCommands.getOrDefault(view.id(), "not configured"));
        return true;
    }

    private boolean clear(final CommandSender sender) {
        this.stopAllLoops();
        this.clickCommands.clear();
        this.send(sender, this.service().removeAll());
        return true;
    }

    List<RealFakePlayerView> views() {
        final List<FakePlayerSnapshot> snapshots = new ArrayList<>(this.service().list());
        snapshots.sort((left, right) -> left.name().compareToIgnoreCase(right.name()));
        final List<RealFakePlayerView> views = new ArrayList<>();
        for (final FakePlayerSnapshot snapshot : snapshots) {
            final Location location = snapshot.location();
            views.add(new RealFakePlayerView(
                MODULE,
                snapshot.id(),
                snapshot.name(),
                location.getWorld() == null ? "" : location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch(),
                snapshot.gameMode().name().toLowerCase(Locale.ROOT),
                this.loopLine(snapshot.name()),
                this.clickCommands.getOrDefault(snapshot.id(), ""),
                snapshot.uuid().toString()
            ));
        }
        return views;
    }

    boolean setClickCommand(final String name, final String command) {
        final var snapshot = this.service().snapshot(name);
        if (snapshot.isEmpty()) {
            return false;
        }
        final String sanitized = sanitizeCommand(command);
        if (sanitized.isBlank()) {
            this.clickCommands.remove(snapshot.get().id());
        } else {
            this.clickCommands.put(snapshot.get().id(), sanitized);
        }
        return true;
    }

    boolean handleInteract(final Player player, final Entity clicked) {
        if (!this.preferences.moduleEnabled(MODULE)) {
            return false;
        }
        if (!(clicked instanceof Player)) {
            return false;
        }
        for (final FakePlayerSnapshot snapshot : this.service().list()) {
            if (!snapshot.uuid().equals(clicked.getUniqueId())) {
                continue;
            }
            final String command = this.clickCommands.getOrDefault(snapshot.id(), "");
            if (command.isBlank()) {
                return false;
            }
            final String rendered = renderClickCommand(command, player, snapshot);
            if (rendered.isBlank()) {
                return false;
            }
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rendered);
            return true;
        }
        return false;
    }

    private void startLoop(final CommandSender sender, final String name, final String action) {
        this.stopLoop(name, action);
        final String key = loopKey(name, action);
        final long interval = Math.max(1L, this.preferences.intValue("modules.real-fake-players.action-interval-ticks", 1));
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            final FakePlayerActionResult result = this.runAction(action, name);
            if (!result.success()) {
                this.stopLoop(name, action);
                sender.sendMessage(ChatColor.RED + result.message());
            }
        }, 0L, interval);
        this.loops.put(key, task);
        sender.sendMessage("Started " + action + " loop for " + name + ".");
    }

    private FakePlayerActionResult runAction(final String action, final String name) {
        return switch (action) {
            case "jump" -> this.service().jump(name);
            case "use" -> this.service().use(name);
            case "attack" -> this.service().attack(name);
            default -> FakePlayerActionResult.fail("Unknown action: " + action);
        };
    }

    private void stopLoops(final String name) {
        for (final String action : List.of("jump", "use", "attack")) {
            this.stopLoop(name, action);
        }
    }

    private void stopLoop(final String name, final String action) {
        final BukkitTask task = this.loops.remove(loopKey(name, action));
        if (task != null) {
            task.cancel();
        }
    }

    private void stopAllLoops() {
        for (final BukkitTask task : this.loops.values()) {
            task.cancel();
        }
        this.loops.clear();
    }

    private String loopLine(final String name) {
        final List<String> active = new ArrayList<>();
        for (final String action : List.of("jump", "use", "attack")) {
            if (this.loops.containsKey(loopKey(name, action))) {
                active.add(action);
            }
        }
        return active.isEmpty() ? "none" : String.join(",", active);
    }

    private Location location(final CommandSender sender, final String[] args, final int index) {
        if (args.length >= index + 4) {
            final World world = Bukkit.getWorld(args[index]);
            if (world == null) {
                sender.sendMessage("World not found: " + args[index]);
                return null;
            }
            try {
                final double x = Double.parseDouble(args[index + 1]);
                final double y = Double.parseDouble(args[index + 2]);
                final double z = Double.parseDouble(args[index + 3]);
                final float yaw = args.length >= index + 5 ? Float.parseFloat(args[index + 4]) : 0.0F;
                final float pitch = args.length >= index + 6 ? Float.parseFloat(args[index + 5]) : 0.0F;
                return new Location(world, x, y, z, yaw, pitch);
            } catch (final NumberFormatException ex) {
                sender.sendMessage("Coordinates must be numbers.");
                return null;
            }
        }
        if (sender instanceof final Player player) {
            return player.getLocation();
        }
        final World world = Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
        if (world == null) {
            sender.sendMessage("No loaded worlds are available.");
            return null;
        }
        return world.getSpawnLocation().add(0.5D, 0.0D, 0.5D);
    }

    private @Nullable float[] rotation(final CommandSender sender, final String label, final String[] args, final int index) {
        if (args.length == index) {
            if (sender instanceof final Player player) {
                final Location location = player.getLocation();
                return new float[] {location.getYaw(), location.getPitch()};
            }
            sender.sendMessage("Usage: /" + label + " look <name> <yaw pitch|north|south|east|west|up|down>");
            return null;
        }
        if (args.length == index + 1) {
            return switch (HunterToolsPreferences.normalize(args[index])) {
                case "south" -> new float[] {0.0F, 0.0F};
                case "west" -> new float[] {90.0F, 0.0F};
                case "north" -> new float[] {180.0F, 0.0F};
                case "east" -> new float[] {-90.0F, 0.0F};
                case "up" -> new float[] {0.0F, -90.0F};
                case "down" -> new float[] {0.0F, 90.0F};
                default -> {
                    sender.sendMessage("Direction must be one of north, south, east, west, up, down.");
                    yield null;
                }
            };
        }
        try {
            return new float[] {Float.parseFloat(args[index]), Math.max(-90.0F, Math.min(90.0F, Float.parseFloat(args[index + 1])))};
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Yaw and pitch must be numbers.");
            return null;
        }
    }

    private void usage(final CommandSender sender, final String label) {
        sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|tphere|look|sneak|sprint|jump|use|attack|stop|click|drop|dropstack|swap|gm|slot|info|clear>");
    }

    private void send(final CommandSender sender, final FakePlayerActionResult result) {
        sender.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED) + result.message());
    }

    private HunterFakePlayerService service() {
        return HunterCoreProvider.get().fakePlayers();
    }

    private List<String> names() {
        return this.service().list().stream().map(FakePlayerSnapshot::name).sorted(String::compareToIgnoreCase).toList();
    }

    private static String loopKey(final String name, final String action) {
        return playerId(name) + ":" + action;
    }

    private static String playerId(final String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static String locationLine(final Location location) {
        return location.getWorld().getName()
            + " "
            + String.format(Locale.ROOT, "%.1f %.1f %.1f", location.getX(), location.getY(), location.getZ());
    }

    private static @Nullable Boolean parseToggle(final String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true", "yes" -> Boolean.TRUE;
            case "off", "disable", "disabled", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static @Nullable GameMode parseGameMode(final String input) {
        return switch (HunterToolsPreferences.normalize(input)) {
            case "survival", "s", "0" -> GameMode.SURVIVAL;
            case "creative", "c", "1" -> GameMode.CREATIVE;
            case "adventure", "a", "2" -> GameMode.ADVENTURE;
            case "spectator", "sp", "3" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static String sanitizeCommand(final String command) {
        if (command == null) {
            return "";
        }
        String sanitized = command.replace('\n', ' ').replace('\r', ' ').trim();
        if (sanitized.startsWith("/")) {
            sanitized = sanitized.substring(1).trim();
        }
        return sanitized.length() > 512 ? sanitized.substring(0, 512).trim() : sanitized;
    }

    private static String renderClickCommand(final String command, final Player player, final FakePlayerSnapshot snapshot) {
        final Location location = snapshot.location();
        return sanitizeCommand(command)
            .replace("%player%", player.getName())
            .replace("%player_uuid%", player.getUniqueId().toString())
            .replace("%actor%", snapshot.id())
            .replace("%actor_name%", snapshot.name())
            .replace("%actor_uuid%", snapshot.uuid().toString())
            .replace("%module%", MODULE)
            .replace("%world%", location.getWorld() == null ? "" : location.getWorld().getName())
            .replace("%x%", String.format(Locale.ROOT, "%.2f", location.getX()))
            .replace("%y%", String.format(Locale.ROOT, "%.2f", location.getY()))
            .replace("%z%", String.format(Locale.ROOT, "%.2f", location.getZ()));
    }

    private static List<String> matching(final String prefix, final List<String> values) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }

    record RealFakePlayerView(
        String module,
        String id,
        String displayName,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String gameMode,
        String loops,
        String clickCommand,
        String entityUuid
    ) {
    }
}
