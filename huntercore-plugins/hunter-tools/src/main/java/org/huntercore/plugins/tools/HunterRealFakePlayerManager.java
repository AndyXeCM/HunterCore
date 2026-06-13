package org.huntercore.plugins.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
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
    private final Map<String, FakeAiProfile> aiProfiles = new HashMap<>();
    private final Map<String, BukkitTask> aiTasks = new HashMap<>();
    private final Map<String, String> aiLastActions = new HashMap<>();
    private final Map<String, Long> chatControlCooldowns = new HashMap<>();
    private final List<String> aiBusy = new ArrayList<>();
    private HunterAiManager aiManager;

    HunterRealFakePlayerManager(final JavaPlugin plugin, final HunterToolsPreferences preferences, final HunterAiManager aiManager) {
        this.plugin = plugin;
        this.preferences = preferences;
        this.aiManager = aiManager;
    }

    void setAiManager(final HunterAiManager aiManager) {
        this.aiManager = aiManager;
    }

    void shutdown() {
        this.stopAllAi();
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
            case "move" -> this.move(sender, label, args);
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
            case "ai" -> this.ai(sender, label, args);
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
            "remove", "kill", "tp", "tphere", "look", "move", "sneak", "sprint", "jump", "use", "attack", "stop",
            "click", "drop", "dropstack", "swap", "gm", "gamemode", "slot", "ai", "info"
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
        if (args.length == 3 && sub.equals("move")) {
            return matching(args[2], List.of("forward", "back", "left", "right", "stop", "1", "-1"));
        }
        if (args.length == 3 && sub.equals("ai")) {
            return matching(args[2], List.of("status", "on", "off", "goal", "once"));
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
        this.stopAi(args[1]);
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

    private boolean move(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 3 || args.length > 6) {
            sender.sendMessage("Usage: /" + label + " move <name> <forward|back|left|right|stop|forwardValue> [sidewaysValue] [ticks] [jump]");
            return true;
        }
        final MovePlan plan = movePlan(String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)));
        if (plan == null) {
            sender.sendMessage("Move must be forward/back/left/right/stop or numeric forward/sideways values.");
            return true;
        }
        if (plan.ticks() <= 1) {
            this.send(sender, this.service().move(args[1], plan.forward(), plan.sideways(), plan.jump(), plan.sprinting(), plan.sneaking()));
            return true;
        }
        this.startMoveLoop(args[1], plan);
        sender.sendMessage("Started move loop for " + args[1] + " (" + plan.ticks() + " ticks).");
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

    private boolean ai(final CommandSender sender, final String label, final String[] args) {
        if (args.length < 2) {
            sender.sendMessage("Usage: /" + label + " ai <name> [status|on|off|goal <text>|once]");
            return true;
        }
        final var snapshot = this.service().snapshot(args[1]);
        if (snapshot.isEmpty()) {
            sender.sendMessage("Fake player not found: " + args[1]);
            return true;
        }
        final FakePlayerSnapshot fake = snapshot.get();
        final FakeAiProfile profile = this.aiProfiles.get(fake.id());
        if (args.length == 2 || args[2].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "HunterCore real fake player AI " + fake.name());
            sender.sendMessage(ChatColor.GRAY + "Enabled: " + ChatColor.WHITE + (profile != null && profile.enabled()));
            sender.sendMessage(ChatColor.GRAY + "Goal: " + ChatColor.WHITE + (profile == null || profile.goal().isBlank() ? defaultFakeAiGoal(fake.name()) : profile.goal()));
            sender.sendMessage(ChatColor.GRAY + "Last action: " + ChatColor.WHITE + this.aiLastActions.getOrDefault(fake.id(), "idle"));
            return true;
        }
        final String action = HunterToolsPreferences.normalize(args[2]);
        switch (action) {
            case "on", "enable", "enabled", "true" -> {
                final String goal = args.length >= 4
                    ? String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim()
                    : profile == null || profile.goal().isBlank() ? defaultFakeAiGoal(fake.name()) : profile.goal();
                this.setAi(fake.name(), true, goal);
                sender.sendMessage("HunterCore AI control enabled for " + fake.name() + ".");
                return true;
            }
            case "off", "disable", "disabled", "false" -> {
                this.setAi(fake.name(), false, "");
                sender.sendMessage("HunterCore AI control disabled for " + fake.name() + ".");
                return true;
            }
            case "goal" -> {
                if (args.length < 4) {
                    sender.sendMessage("Usage: /" + label + " ai <name> goal <text>");
                    return true;
                }
                final String goal = String.join(" ", java.util.Arrays.copyOfRange(args, 3, args.length)).trim();
                this.setAi(fake.name(), profile != null && profile.enabled(), goal);
                sender.sendMessage("HunterCore AI goal updated for " + fake.name() + ".");
                return true;
            }
            case "once" -> {
                final String goal = profile == null || profile.goal().isBlank() ? defaultFakeAiGoal(fake.name()) : profile.goal();
                this.aiProfiles.put(fake.id(), new FakeAiProfile(fake.id(), fake.name(), profile != null && profile.enabled(), goal));
                this.requestAi(fake.id(), true);
                sender.sendMessage("HunterCore AI one-shot requested for " + fake.name() + ".");
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /" + label + " ai <name> [status|on|off|goal <text>|once]");
                return true;
            }
        }
    }

    private boolean info(final CommandSender sender, final String label, final String[] args) {
        if (args.length > 2) {
            sender.sendMessage("Usage: /" + label + " info [name]");
            return true;
        }
        if (args.length == 1) {
            sender.sendMessage(ChatColor.GOLD + "HunterCore real fake players");
            sender.sendMessage("- True ServerPlayer instances: online list, chunk loading, player events and plugin visibility.");
            sender.sendMessage("- Commands: spawn, remove, list, tp, tphere, look, move, sneak, sprint, jump, use, attack, stop, click, drop, dropstack, swap, gm, slot, ai, info, clear.");
            sender.sendMessage("- use/attack/jump support once, continuous, and stop. AI can drive look, move, mine, use, slot and toggles.");
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
        final FakeAiProfile profile = this.aiProfiles.get(view.id());
        sender.sendMessage("- AI: " + (profile != null && profile.enabled() ? "enabled" : "disabled")
            + ", goal: " + (profile == null || profile.goal().isBlank() ? defaultFakeAiGoal(view.name()) : profile.goal())
            + ", last: " + this.aiLastActions.getOrDefault(view.id(), "idle"));
        return true;
    }

    private boolean clear(final CommandSender sender) {
        this.stopAllAi();
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
            final FakeAiProfile profile = this.aiProfiles.get(snapshot.id());
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
                profile != null && profile.enabled(),
                profile == null ? "" : profile.goal(),
                this.aiLastActions.getOrDefault(snapshot.id(), "idle"),
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

    boolean setAi(final String name, final boolean enabled, final String goal) {
        final var snapshot = this.service().snapshot(name);
        if (snapshot.isEmpty()) {
            return false;
        }
        final FakePlayerSnapshot fake = snapshot.get();
        final String cleanGoal = sanitizeGoal(goal);
        if (!enabled) {
            this.stopAi(fake.id());
            if (!cleanGoal.isBlank()) {
                this.aiProfiles.put(fake.id(), new FakeAiProfile(fake.id(), fake.name(), false, cleanGoal));
            }
            return true;
        }
        this.aiProfiles.put(fake.id(), new FakeAiProfile(
            fake.id(),
            fake.name(),
            true,
            cleanGoal.isBlank() ? defaultFakeAiGoal(fake.name()) : cleanGoal
        ));
        this.startAiLoop(fake.id());
        this.requestAi(fake.id(), true);
        return true;
    }

    boolean handleChatControl(final Player player, final String rawMessage) {
        final String message = rawMessage == null ? "" : rawMessage.trim();
        final String prefix = this.preferences.stringValue("modules.ai.fake-players.chat-control.trigger-prefix", "@bot").trim();
        if (prefix.isBlank()
            || !this.preferences.booleanValue("modules.ai.fake-players.chat-control.enabled", true)
            || !message.startsWith(prefix)) {
            return false;
        }
        final String body = message.substring(prefix.length()).trim();
        final UUID playerId = player.getUniqueId();
        this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.handleChatControlMain(playerId, prefix, body));
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

    private void handleChatControlMain(final UUID playerId, final String prefix, final String body) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        if (!this.preferences.moduleEnabled(MODULE) || !this.preferences.moduleEnabled("ai")) {
            player.sendMessage(ChatColor.RED + "HunterCore real fake player AI is disabled.");
            return;
        }
        if (!this.preferences.booleanValue("modules.ai.fake-players.enabled", true)) {
            player.sendMessage(ChatColor.RED + "HunterCore fake player AI planning is disabled.");
            return;
        }
        if (this.preferences.booleanValue("modules.ai.fake-players.chat-control.require-permission", false)) {
            final String permission = this.preferences.stringValue("modules.ai.fake-players.chat-control.permission", "huntertools.ai.fakeplayer").trim();
            if (!permission.isBlank() && !player.hasPermission(permission)) {
                player.sendMessage(ChatColor.RED + "You do not have permission to control fake player AI from chat.");
                return;
            }
        }
        final long waitMillis = this.chatControlCooldown(player.getUniqueId().toString());
        if (waitMillis > 0L) {
            player.sendMessage(ChatColor.YELLOW + "Fake player chat control cooldown: " + Math.max(1L, (waitMillis + 999L) / 1000L) + "s.");
            return;
        }
        if (body.isBlank() || body.equalsIgnoreCase("help")) {
            player.sendMessage(ChatColor.GOLD + "Usage: " + prefix + " <fakePlayer|all> <task>");
            player.sendMessage(ChatColor.GRAY + "Examples: " + prefix + " Bot follow me, " + prefix + " Bot mine the stone in front, " + prefix + " stop Bot");
            return;
        }
        if (body.equalsIgnoreCase("list")) {
            final List<String> names = this.service().list().stream().map(FakePlayerSnapshot::name).sorted(String::compareToIgnoreCase).toList();
            player.sendMessage(ChatColor.GOLD + "Real fake players: " + ChatColor.WHITE + (names.isEmpty() ? "none" : String.join(", ", names)));
            return;
        }

        final String normalized = HunterToolsPreferences.normalize(firstValue(body));
        if (normalized.equals("stop")) {
            this.handleChatStop(player, body.substring(Math.min(body.length(), firstValue(body).length())).trim());
            return;
        }
        if (normalized.equals("all")) {
            final String task = body.substring(Math.min(body.length(), firstValue(body).length())).trim();
            if (task.isBlank()) {
                player.sendMessage(ChatColor.RED + "Usage: " + prefix + " all <task>");
                return;
            }
            final List<FakePlayerSnapshot> snapshots = new ArrayList<>(this.service().list());
            if (snapshots.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No real fake players are online.");
                return;
            }
            for (final FakePlayerSnapshot snapshot : snapshots) {
                this.assignChatTask(player, snapshot, task);
            }
            player.sendMessage(ChatColor.GREEN + "Assigned task to " + snapshots.size() + " real fake player(s).");
            return;
        }

        final TargetTask targetTask = this.targetTask(player, body);
        if (targetTask == null) {
            player.sendMessage(ChatColor.RED + "Could not choose a fake player. Use " + prefix + " list, then " + prefix + " <name> <task>.");
            return;
        }
        if (targetTask.task().isBlank()) {
            player.sendMessage(ChatColor.RED + "Tell " + targetTask.snapshot().name() + " what to do after its name.");
            return;
        }
        this.assignChatTask(player, targetTask.snapshot(), targetTask.task());
        player.sendMessage(ChatColor.GREEN + targetTask.snapshot().name() + " received task: " + targetTask.task());
    }

    private void handleChatStop(final Player player, final String target) {
        if (target.isBlank() || HunterToolsPreferences.normalize(target).equals("all")) {
            for (final FakePlayerSnapshot snapshot : this.service().list()) {
                this.stopAi(snapshot.id());
                this.stopLoops(snapshot.name());
                this.service().stopActions(snapshot.name());
            }
            player.sendMessage(ChatColor.GREEN + "Stopped all real fake player AI tasks.");
            return;
        }
        final var snapshot = this.service().snapshot(firstValue(target));
        if (snapshot.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Fake player not found: " + firstValue(target));
            return;
        }
        this.stopAi(snapshot.get().id());
        this.stopLoops(snapshot.get().name());
        this.service().stopActions(snapshot.get().name());
        player.sendMessage(ChatColor.GREEN + "Stopped " + snapshot.get().name() + ".");
    }

    private TargetTask targetTask(final Player player, final String body) {
        final String first = firstValue(body);
        final String rest = body.substring(Math.min(body.length(), first.length())).trim();
        if (!first.isBlank()) {
            final var named = this.service().snapshot(first);
            if (named.isPresent()) {
                return new TargetTask(named.get(), rest);
            }
        }
        final List<FakePlayerSnapshot> snapshots = new ArrayList<>(this.service().list());
        if (snapshots.isEmpty()) {
            return null;
        }
        if (snapshots.size() == 1) {
            return new TargetTask(snapshots.getFirst(), body);
        }
        FakePlayerSnapshot nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (final FakePlayerSnapshot snapshot : snapshots) {
            final Location location = snapshot.location();
            if (location.getWorld() == null || !location.getWorld().equals(player.getWorld())) {
                continue;
            }
            final double distance = location.distanceSquared(player.getLocation());
            if (distance < nearestDistance) {
                nearest = snapshot;
                nearestDistance = distance;
            }
        }
        return nearest == null ? null : new TargetTask(nearest, body);
    }

    private void assignChatTask(final Player player, final FakePlayerSnapshot fake, final String task) {
        final Location location = player.getLocation();
        final String goal = "Player " + player.getName() + " assigned this real fake player a chat task: " + sanitizeGoal(task) + "\n"
            + "Controller location: world=" + player.getWorld().getName()
            + " x=" + format(location.getX())
            + " y=" + format(location.getY())
            + " z=" + format(location.getZ())
            + " yaw=" + format(location.getYaw())
            + " pitch=" + format(location.getPitch()) + "\n"
            + "Behave like a cooperative Minecraft helper. For follow/come tasks use [follow:player=" + player.getName() + "]. "
            + "For travel tasks use [goto:x y z]. For mining or tool work, look at the target and use [mine:ticks=40] or [use]. "
            + "Stop if the task says stop.";
        this.setAi(fake.name(), true, goal);
        this.aiLastActions.put(fake.id(), "assigned by " + player.getName() + ": " + truncatePlain(task, 80));
    }

    private void startAiLoop(final String id) {
        this.stopAiTask(id);
        final FakeAiProfile profile = this.aiProfiles.get(playerId(id));
        if (profile == null || !profile.enabled()) {
            return;
        }
        final long interval = Math.max(2L, this.preferences.intValue("modules.ai.fake-players.interval-seconds", 6)) * 20L;
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> this.requestAi(profile.id(), false), interval, interval);
        this.aiTasks.put(profile.id(), task);
    }

    private void requestAi(final String id, final boolean manual) {
        final String key = playerId(id);
        final FakeAiProfile profile = this.aiProfiles.get(key);
        if (profile == null || this.aiBusy.contains(key)) {
            return;
        }
        if (!manual && (!profile.enabled()
            || !this.preferences.moduleEnabled("ai")
            || !this.preferences.booleanValue("modules.ai.fake-players.enabled", true))) {
            return;
        }
        if (this.aiManager == null) {
            this.aiLastActions.put(key, "AI manager unavailable");
            return;
        }
        final var snapshot = this.service().snapshot(profile.name());
        if (snapshot.isEmpty()) {
            this.stopAi(key);
            return;
        }
        final FakePlayerSnapshot fake = snapshot.get();
        final String system = this.preferences.stringValue("modules.ai.fake-players.system-prompt", defaultFakeAiPrompt());
        final String prompt = this.fakeAiContext(profile, fake);
        this.aiBusy.add(key);
        this.aiLastActions.put(key, "thinking");
        this.aiManager.completeFakePlayerPlan(system, prompt).whenComplete((response, error) -> {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
                this.aiBusy.remove(key);
                if (error != null) {
                    this.aiLastActions.put(key, "AI failed: " + cleanError(error));
                    this.plugin.getLogger().warning("HunterCore fake player AI failed for " + fake.name() + ": " + cleanError(error));
                    return;
                }
                this.applyAiPlan(fake.name(), response);
            });
        });
    }

    private String fakeAiContext(final FakeAiProfile profile, final FakePlayerSnapshot snapshot) {
        final StringBuilder context = new StringBuilder(1024);
        final Location location = snapshot.location();
        context.append("Goal: ").append(profile.goal().isBlank() ? defaultFakeAiGoal(snapshot.name()) : profile.goal()).append('\n');
        context.append("Fake player: ").append(snapshot.name())
            .append(" mode=").append(snapshot.gameMode().name().toLowerCase(Locale.ROOT))
            .append(" sneaking=").append(snapshot.sneaking())
            .append(" sprinting=").append(snapshot.sprinting())
            .append(" usingItem=").append(snapshot.usingItem())
            .append(" loops=").append(this.loopLine(snapshot.name())).append('\n');
        context.append("Location: world=").append(location.getWorld() == null ? "unknown" : location.getWorld().getName())
            .append(" x=").append(format(location.getX()))
            .append(" y=").append(format(location.getY()))
            .append(" z=").append(format(location.getZ()))
            .append(" yaw=").append(format(location.getYaw()))
            .append(" pitch=").append(format(location.getPitch())).append('\n');

        final World world = location.getWorld();
        if (world == null) {
            return context.append("World is not loaded.\n").toString();
        }
        final int radius = Math.max(2, Math.min(12, this.preferences.intValue("modules.ai.fake-players.nearby-radius-blocks", 6)));
        final Location eye = location.clone().add(0.0D, 1.62D, 0.0D);
        final RayTraceResult ray = world.rayTraceBlocks(eye, location.getDirection(), Math.max(2.0D, radius), FluidCollisionMode.NEVER, true);
        if (ray != null && ray.getHitBlock() != null) {
            context.append("Looking at block: ").append(blockLine(ray.getHitBlock())).append('\n');
        } else {
            context.append("Looking at block: none\n");
        }
        context.append("Feet: ").append(blockLine(location.getBlock())).append('\n');
        context.append("Below: ").append(blockLine(location.clone().add(0.0D, -1.0D, 0.0D).getBlock())).append('\n');
        final Vector direction = location.getDirection().setY(0.0D);
        if (direction.lengthSquared() > 0.0D) {
            direction.normalize();
            context.append("Front: ").append(blockLine(location.clone().add(direction).getBlock())).append('\n');
        }

        context.append("Nearby blocks:");
        int blockCount = 0;
        final int baseX = location.getBlockX();
        final int baseY = location.getBlockY();
        final int baseZ = location.getBlockZ();
        for (int y = -1; y <= 2 && blockCount < 24; y++) {
            for (int x = -2; x <= 2 && blockCount < 24; x++) {
                for (int z = -2; z <= 2 && blockCount < 24; z++) {
                    final Block block = world.getBlockAt(baseX + x, baseY + y, baseZ + z);
                    final Material type = block.getType();
                    if (!type.isAir()) {
                        context.append(' ').append(type.key().value()).append('@').append(x).append(',').append(y).append(',').append(z).append(';');
                        blockCount++;
                    }
                }
            }
        }
        if (blockCount == 0) {
            context.append(" none");
        }
        context.append('\n');

        context.append("Nearby entities:");
        int entityCount = 0;
        for (final Entity entity : world.getNearbyEntities(location, radius, radius, radius)) {
            if (entity.getUniqueId().equals(snapshot.uuid())) {
                continue;
            }
            final Location other = entity.getLocation();
            context.append(' ')
                .append(entity.getType().key().value())
                .append('(').append(entity.getName()).append(')')
                .append('@')
                .append(format(other.getX() - location.getX())).append(',')
                .append(format(other.getY() - location.getY())).append(',')
                .append(format(other.getZ() - location.getZ())).append(';');
            entityCount++;
            if (entityCount >= 12) {
                break;
            }
        }
        if (entityCount == 0) {
            context.append(" none");
        }
        context.append('\n');
        context.append("Nearby players:");
        int playerCount = 0;
        for (final Player player : world.getPlayers()) {
            if (player.getUniqueId().equals(snapshot.uuid()) || player.getLocation().distanceSquared(location) > radius * radius) {
                continue;
            }
            final Location other = player.getLocation();
            context.append(' ')
                .append(player.getName())
                .append('@')
                .append(format(other.getX() - location.getX())).append(',')
                .append(format(other.getY() - location.getY())).append(',')
                .append(format(other.getZ() - location.getZ())).append(';');
            playerCount++;
            if (playerCount >= 8) {
                break;
            }
        }
        if (playerCount == 0) {
            context.append(" none");
        }
        context.append('\n');
        context.append("Return only action lines. Prefer small steps and continue mining with [mine:ticks=40] when breaking blocks. Use [goto:x y z] or [follow:player=name] for player work requests.");
        return context.toString();
    }

    private void applyAiPlan(final String name, final String response) {
        final int budget = Math.max(1, Math.min(12, this.preferences.intValue("modules.ai.fake-players.max-actions", 5)));
        final List<String> results = new ArrayList<>();
        int applied = 0;
        for (final String rawLine : response.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            if (applied >= budget) {
                break;
            }
            final String line = rawLine.trim();
            if (!line.startsWith("[") || !line.endsWith("]")) {
                continue;
            }
            final String body = line.substring(1, line.length() - 1).trim();
            if (body.isBlank()) {
                continue;
            }
            final String action;
            final String args;
            final int colon = body.indexOf(':');
            if (colon >= 0) {
                action = HunterToolsPreferences.normalize(body.substring(0, colon).trim());
                args = body.substring(colon + 1).trim();
            } else {
                action = HunterToolsPreferences.normalize(body);
                args = "";
            }
            final String result = this.applyAiAction(name, action, args);
            if (!result.isBlank()) {
                results.add(result);
                applied++;
            }
        }
        this.aiLastActions.put(playerId(name), results.isEmpty() ? "no valid action" : String.join("; ", results));
    }

    private String applyAiAction(final String name, final String action, final String args) {
        return switch (action) {
            case "look" -> this.aiLook(name, args);
            case "look-at", "lookat" -> this.aiLookAt(name, args);
            case "turn", "rotate" -> this.aiTurn(name, args);
            case "look-at-player", "lookatplayer", "face-player", "faceplayer" -> this.aiLookAtPlayer(name, args);
            case "move", "walk" -> this.aiMove(name, args);
            case "goto", "go-to", "walk-to", "walkto" -> this.aiGoto(name, args);
            case "follow" -> this.aiFollow(name, args);
            case "mine", "dig", "break" -> this.aiTimedAction(name, "attack", args, "mine", true);
            case "attack", "left-click", "leftclick" -> this.aiTimedAction(name, "attack", args, "attack", true);
            case "use", "right-click", "rightclick", "interact" -> this.aiTimedAction(name, "use", args, "use", false);
            case "say", "chat" -> this.aiSay(name, args);
            case "wait", "pause" -> "wait " + Math.max(1, intArg(args, "ticks", 20)) + " ticks";
            case "jump" -> resultLine(this.service().jump(name));
            case "sneak" -> resultLine(this.service().setSneaking(name, parseOnOff(args, true)));
            case "sprint" -> resultLine(this.service().setSprinting(name, parseOnOff(args, true)));
            case "slot" -> this.aiSlot(name, args);
            case "drop" -> resultLine(this.service().dropSelected(name, false));
            case "dropstack" -> resultLine(this.service().dropSelected(name, true));
            case "swap" -> resultLine(this.service().swapHands(name));
            case "stop" -> {
                this.stopLoops(name);
                yield resultLine(this.service().stopActions(name));
            }
            default -> "";
        };
    }

    private String aiLook(final String name, final String args) {
        final double keyedYaw = doubleArg(args, "yaw", Double.NaN);
        final double keyedPitch = doubleArg(args, "pitch", Double.NaN);
        if (!Double.isNaN(keyedYaw) && !Double.isNaN(keyedPitch)) {
            return resultLine(this.service().look(name, (float) keyedYaw, (float) keyedPitch));
        }
        final double[] values = parseNumbers(args, 2);
        if (values.length < 2) {
            return "";
        }
        return resultLine(this.service().look(name, (float) values[0], (float) values[1]));
    }

    private String aiLookAt(final String name, final String args) {
        final var snapshot = this.service().snapshot(name);
        final double[] values = coordinateArgs(args);
        if (snapshot.isEmpty() || values.length < 3) {
            return "";
        }
        final float[] rotation = rotationTo(snapshot.get().location(), values[0], values[1], values[2]);
        return resultLine(this.service().look(name, rotation[0], rotation[1]));
    }

    private String aiTurn(final String name, final String args) {
        final var snapshot = this.service().snapshot(name);
        if (snapshot.isEmpty()) {
            return "";
        }
        final double yaw = doubleArg(args, "yaw", doubleArg(args, "dyaw", Double.NaN));
        final double pitch = doubleArg(args, "pitch", doubleArg(args, "dpitch", 0.0D));
        final double[] values = parseNumbers(args, 2);
        final double yawDelta = Double.isNaN(yaw) ? (values.length >= 1 ? values[0] : 0.0D) : yaw;
        final double pitchDelta = values.length >= 2 && !hasKey(args, "pitch") && !hasKey(args, "dpitch") ? values[1] : pitch;
        final Location location = snapshot.get().location();
        return resultLine(this.service().look(
            name,
            (float) (location.getYaw() + clamp(yawDelta, -180.0D, 180.0D)),
            (float) clamp(location.getPitch() + clamp(pitchDelta, -90.0D, 90.0D), -90.0D, 90.0D)
        ));
    }

    private String aiLookAtPlayer(final String name, final String args) {
        final var snapshot = this.service().snapshot(name);
        final Player target = playerArg(args, snapshot.map(FakePlayerSnapshot::location).orElse(null));
        if (snapshot.isEmpty() || target == null) {
            return "";
        }
        final Location eye = target.getEyeLocation();
        final float[] rotation = rotationTo(snapshot.get().location(), eye.getX(), eye.getY(), eye.getZ());
        return resultLine(this.service().look(name, rotation[0], rotation[1]));
    }

    private String aiMove(final String name, final String args) {
        if (!this.preferences.booleanValue("modules.ai.fake-players.allow-movement", true)) {
            return "movement disabled";
        }
        final MovePlan plan = movePlan(args);
        if (plan == null) {
            return "";
        }
        if (plan.ticks() <= 1) {
            return resultLine(this.service().move(name, plan.forward(), plan.sideways(), plan.jump(), plan.sprinting(), plan.sneaking()));
        }
        this.startMoveLoop(name, plan);
        return "move " + plan.ticks() + " ticks";
    }

    private String aiGoto(final String name, final String args) {
        if (!this.preferences.booleanValue("modules.ai.fake-players.allow-movement", true)) {
            return "movement disabled";
        }
        final double[] values = coordinateArgs(args);
        if (values.length < 3) {
            return "";
        }
        final int ticks = Math.max(1, Math.min(
            Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-move-ticks", 40)),
            intArg(args, "ticks", 60)
        ));
        final boolean sprint = parseBooleanArg(args, "sprint", true);
        this.startGotoLoop(name, values[0], values[1], values[2], ticks, sprint);
        return "goto " + format(values[0]) + "," + format(values[1]) + "," + format(values[2]);
    }

    private String aiFollow(final String name, final String args) {
        if (!this.preferences.booleanValue("modules.ai.fake-players.allow-movement", true)) {
            return "movement disabled";
        }
        final var snapshot = this.service().snapshot(name);
        final Player target = playerArg(args, snapshot.map(FakePlayerSnapshot::location).orElse(null));
        if (target == null) {
            return "";
        }
        final int ticks = Math.max(1, Math.min(
            Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-move-ticks", 40)),
            intArg(args, "ticks", 80)
        ));
        final double distance = clamp(doubleArg(args, "distance", 2.5D), 1.0D, 8.0D);
        final boolean sprint = parseBooleanArg(args, "sprint", true);
        this.startFollowLoop(name, target.getUniqueId(), ticks, distance, sprint);
        return "follow " + target.getName();
    }

    private String aiTimedAction(final String name, final String action, final String args, final String label, final boolean breaking) {
        if (breaking && !this.preferences.booleanValue("modules.ai.fake-players.allow-breaking", true)) {
            return "breaking disabled";
        }
        if (!breaking && !this.preferences.booleanValue("modules.ai.fake-players.allow-interaction", true)) {
            return "interaction disabled";
        }
        final int ticks = Math.max(1, Math.min(
            Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-action-ticks", 80)),
            intArg(args, "ticks", action.equals("attack") ? 40 : 1)
        ));
        if (ticks <= 1) {
            return resultLine(this.runAction(action, name));
        }
        this.startTimedActionLoop(name, action, ticks);
        return label + " " + ticks + " ticks";
    }

    private String aiSlot(final String name, final String args) {
        final int slot = intArg(args, "slot", -1);
        if (slot < 1 || slot > 9) {
            return "";
        }
        return resultLine(this.service().setSelectedSlot(name, slot));
    }

    private String aiSay(final String name, final String args) {
        final var snapshot = this.service().snapshot(name);
        if (snapshot.isEmpty()) {
            return "";
        }
        final String message = sanitizeChat(args);
        if (message.isBlank()) {
            return "";
        }
        Bukkit.broadcastMessage("<" + snapshot.get().name() + "> " + message);
        return "say";
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

    private void startTimedActionLoop(final String name, final String action, final int ticks) {
        this.stopLoop(name, action);
        final int[] remaining = {Math.max(1, ticks)};
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            final FakePlayerActionResult result = this.runAction(action, name);
            if (!result.success() || --remaining[0] <= 0) {
                this.stopLoop(name, action);
                if (action.equals("attack")) {
                    this.service().stopActions(name);
                }
            }
        }, 1L, Math.max(1L, this.preferences.intValue("modules.real-fake-players.action-interval-ticks", 1)));
        this.loops.put(loopKey(name, action), task);
    }

    private void startMoveLoop(final String name, final MovePlan plan) {
        this.stopLoop(name, "move");
        final int maxTicks = Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-move-ticks", 40));
        final int[] remaining = {Math.max(1, Math.min(maxTicks, plan.ticks()))};
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            final FakePlayerActionResult result = this.service().move(name, plan.forward(), plan.sideways(), plan.jump(), plan.sprinting(), plan.sneaking());
            if (!result.success() || --remaining[0] <= 0) {
                this.stopLoop(name, "move");
            }
        }, 1L, 1L);
        this.loops.put(loopKey(name, "move"), task);
    }

    private void startGotoLoop(final String name, final double x, final double y, final double z, final int ticks, final boolean sprinting) {
        this.stopLoop(name, "goto");
        final int maxTicks = Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-move-ticks", 40));
        final int[] remaining = {Math.max(1, Math.min(maxTicks, ticks))};
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            final var snapshot = this.service().snapshot(name);
            if (snapshot.isEmpty()) {
                this.stopLoop(name, "goto");
                return;
            }
            final Location location = snapshot.get().location();
            final double dx = x - location.getX();
            final double dy = y - location.getY();
            final double dz = z - location.getZ();
            if ((dx * dx) + (dy * dy) + (dz * dz) <= 1.44D || --remaining[0] <= 0) {
                this.service().move(name, 0.0D, 0.0D, false, false, false);
                this.stopLoop(name, "goto");
                return;
            }
            final float[] rotation = rotationTo(location, x, y, z);
            this.service().look(name, rotation[0], rotation[1]);
            this.service().move(name, 1.0D, 0.0D, false, sprinting, false);
        }, 1L, 1L);
        this.loops.put(loopKey(name, "goto"), task);
    }

    private void startFollowLoop(final String name, final UUID targetId, final int ticks, final double distance, final boolean sprinting) {
        this.stopLoop(name, "follow");
        final int maxTicks = Math.max(1, this.preferences.intValue("modules.ai.fake-players.max-move-ticks", 40));
        final int[] remaining = {Math.max(1, Math.min(maxTicks, ticks))};
        final BukkitTask task = this.plugin.getServer().getScheduler().runTaskTimer(this.plugin, () -> {
            final Player target = Bukkit.getPlayer(targetId);
            final var snapshot = this.service().snapshot(name);
            if (target == null || snapshot.isEmpty()) {
                this.stopLoop(name, "follow");
                return;
            }
            final Location location = snapshot.get().location();
            final Location targetLocation = target.getLocation();
            if (location.getWorld() == null || targetLocation.getWorld() == null || !location.getWorld().equals(targetLocation.getWorld())) {
                this.stopLoop(name, "follow");
                return;
            }
            if (--remaining[0] <= 0) {
                this.service().move(name, 0.0D, 0.0D, false, false, false);
                this.stopLoop(name, "follow");
                return;
            }
            if (location.distanceSquared(targetLocation) <= distance * distance) {
                this.service().move(name, 0.0D, 0.0D, false, false, false);
                return;
            }
            final Location eye = target.getEyeLocation();
            final float[] rotation = rotationTo(location, eye.getX(), eye.getY(), eye.getZ());
            this.service().look(name, rotation[0], rotation[1]);
            this.service().move(name, 1.0D, 0.0D, false, sprinting, false);
        }, 1L, 1L);
        this.loops.put(loopKey(name, "follow"), task);
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
        for (final String action : List.of("jump", "use", "attack", "move", "goto", "follow")) {
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

    private void stopAi(final String nameOrId) {
        final String id = playerId(nameOrId);
        this.stopAiTask(id);
        this.aiProfiles.remove(id);
        this.aiBusy.remove(id);
        this.aiLastActions.remove(id);
    }

    private void stopAiTask(final String id) {
        final BukkitTask task = this.aiTasks.remove(playerId(id));
        if (task != null) {
            task.cancel();
        }
    }

    private void stopAllAi() {
        for (final BukkitTask task : this.aiTasks.values()) {
            task.cancel();
        }
        this.aiTasks.clear();
        this.aiProfiles.clear();
        this.aiBusy.clear();
        this.aiLastActions.clear();
    }

    private String loopLine(final String name) {
        final List<String> active = new ArrayList<>();
        for (final String action : List.of("jump", "use", "attack", "move", "goto", "follow")) {
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
        sender.sendMessage("Usage: /" + label + " <spawn|remove|list|tp|tphere|look|move|sneak|sprint|jump|use|attack|stop|click|drop|dropstack|swap|gm|slot|ai|info|clear>");
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

    private static String sanitizeGoal(final String goal) {
        if (goal == null) {
            return "";
        }
        final String sanitized = goal.replace("\r\n", "\n").replace('\r', '\n').trim();
        return sanitized.length() > 2048 ? sanitized.substring(0, 2048).trim() : sanitized;
    }

    private long chatControlCooldown(final String key) {
        final long now = System.currentTimeMillis();
        final long expires = this.chatControlCooldowns.getOrDefault(key, 0L);
        if (expires > now) {
            return expires - now;
        }
        final int seconds = Math.max(0, this.preferences.intValue("modules.ai.fake-players.chat-control.cooldown-seconds", 3));
        if (seconds > 0) {
            this.chatControlCooldowns.put(key, now + seconds * 1000L);
        }
        return 0L;
    }

    private static MovePlan movePlan(final String args) {
        double forward = doubleArg(args, "forward", Double.NaN);
        double sideways = doubleArg(args, "sideways", doubleArg(args, "strafe", 0.0D));
        int ticks = intArg(args, "ticks", 20);
        boolean jump = parseBooleanArg(args, "jump", false);
        boolean sprint = parseBooleanArg(args, "sprint", false);
        boolean sneak = parseBooleanArg(args, "sneak", false);
        for (final String token : tokens(args)) {
            final String normalized = HunterToolsPreferences.normalize(token);
            switch (normalized) {
                case "forward", "ahead" -> forward = 1.0D;
                case "back", "backward" -> forward = -1.0D;
                case "left" -> sideways = -1.0D;
                case "right" -> sideways = 1.0D;
                case "stop" -> {
                    forward = 0.0D;
                    sideways = 0.0D;
                    ticks = 1;
                }
                case "jump" -> jump = true;
                case "sprint" -> sprint = true;
                case "sneak", "shift" -> sneak = true;
                default -> {
                    if (!token.contains("=") && Double.isNaN(forward)) {
                        try {
                            forward = Double.parseDouble(token);
                        } catch (final NumberFormatException ignored) {
                        }
                    }
                }
            }
        }
        if (Double.isNaN(forward)) {
            forward = 0.0D;
        }
        final double[] positional = parseNumbers(args, 3);
        if (positional.length >= 1 && !hasKey(args, "forward")) {
            forward = positional[0];
        }
        if (positional.length >= 2 && !hasKey(args, "sideways") && !hasKey(args, "strafe")) {
            sideways = positional[1];
        }
        if (positional.length >= 3 && !hasKey(args, "ticks")) {
            ticks = (int) Math.round(positional[2]);
        }
        if (Double.isNaN(forward) || Double.isNaN(sideways)) {
            return null;
        }
        return new MovePlan(clamp(forward, -1.0D, 1.0D), clamp(sideways, -1.0D, 1.0D), Math.max(1, ticks), jump, sprint, sneak);
    }

    private static String resultLine(final FakePlayerActionResult result) {
        return (result.success() ? "ok: " : "failed: ") + result.message();
    }

    private static boolean parseOnOff(final String args, final boolean fallback) {
        final String value = firstValue(args);
        if (value.isBlank()) {
            return fallback;
        }
        final Boolean parsed = parseToggle(value);
        return parsed == null ? fallback : parsed;
    }

    private static int intArg(final String args, final String key, final int fallback) {
        final String keyed = valueFor(args, key);
        final String value = keyed.isBlank() ? firstValue(args) : keyed;
        if (value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static double doubleArg(final String args, final String key, final double fallback) {
        final String keyed = valueFor(args, key);
        if (keyed.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(keyed.trim());
        } catch (final NumberFormatException ex) {
            return fallback;
        }
    }

    private static boolean parseBooleanArg(final String args, final String key, final boolean fallback) {
        final String value = valueFor(args, key);
        if (value.isBlank()) {
            return fallback;
        }
        final Boolean parsed = parseToggle(value);
        return parsed == null ? fallback : parsed;
    }

    private static double[] parseNumbers(final String args, final int max) {
        final List<Double> values = new ArrayList<>();
        for (final String token : tokens(args)) {
            if (token.contains("=")) {
                continue;
            }
            try {
                values.add(Double.parseDouble(token));
            } catch (final NumberFormatException ignored) {
            }
            if (values.size() >= max) {
                break;
            }
        }
        final double[] result = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private static double[] coordinateArgs(final String args) {
        final double x = doubleArg(args, "x", Double.NaN);
        final double y = doubleArg(args, "y", Double.NaN);
        final double z = doubleArg(args, "z", Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y) && !Double.isNaN(z)) {
            return new double[] {x, y, z};
        }
        return parseNumbers(args, 3);
    }

    private static String valueFor(final String args, final String key) {
        final String normalizedKey = HunterToolsPreferences.normalize(key);
        for (final String token : tokens(args)) {
            final int equals = token.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final String left = HunterToolsPreferences.normalize(token.substring(0, equals));
            if (left.equals(normalizedKey)) {
                return token.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private static boolean hasKey(final String args, final String key) {
        return !valueFor(args, key).isBlank();
    }

    private static String firstValue(final String args) {
        for (final String token : tokens(args)) {
            if (!token.contains("=")) {
                return token;
            }
        }
        return "";
    }

    private static @Nullable Player playerArg(final String args, final @Nullable Location origin) {
        final String keyed = valueFor(args, "player");
        final String value = keyed.isBlank() ? firstValue(args) : keyed;
        if (!value.isBlank()) {
            final Player exact = Bukkit.getPlayerExact(value);
            if (exact != null) {
                return exact;
            }
            final Player partial = Bukkit.getPlayer(value);
            if (partial != null) {
                return partial;
            }
        }
        if (origin == null || origin.getWorld() == null) {
            return null;
        }
        Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (final Player player : origin.getWorld().getPlayers()) {
            final double distance = player.getLocation().distanceSquared(origin);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static List<String> tokens(final String args) {
        final List<String> values = new ArrayList<>();
        if (args == null) {
            return values;
        }
        for (final String token : args.replace(',', ' ').split("\\s+")) {
            final String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                values.add(trimmed);
            }
        }
        return values;
    }

    private static String sanitizeChat(final String message) {
        if (message == null) {
            return "";
        }
        final String sanitized = message.replace('\n', ' ').replace('\r', ' ').trim();
        return sanitized.length() > 160 ? sanitized.substring(0, 160).trim() : sanitized;
    }

    private static String truncatePlain(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        final String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength).trim() + "..." : trimmed;
    }

    private static float[] rotationTo(final Location from, final double x, final double y, final double z) {
        final Location eye = from.clone().add(0.0D, 1.62D, 0.0D);
        final double dx = x - eye.getX();
        final double dy = y - eye.getY();
        final double dz = z - eye.getZ();
        final double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new float[] {
            (float) Math.toDegrees(Math.atan2(-dx, dz)),
            (float) Math.max(-90.0D, Math.min(90.0D, Math.toDegrees(-Math.atan2(dy, horizontal))))
        };
    }

    private static double clamp(final double value, final double min, final double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String cleanError(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private static String defaultFakeAiPrompt() {
        return "You control a HunterCore real fake player in Minecraft. Return only bracketed action lines, no prose. "
            + "Available actions: [look:yaw pitch], [look-at:x y z], [turn:yaw pitch], [look-at-player:player=name], "
            + "[move:forward=1,sideways=0,ticks=20,sprint=true,jump=false,sneak=false], [goto:x y z,ticks=60,sprint=true], "
            + "[follow:player=name,ticks=80,distance=2.5], [mine:ticks=40], [use], [attack], [jump], [sneak:on], [sprint:off], "
            + "[slot:1], [say:text], [drop], [dropstack], [swap], [wait:ticks=20], [stop]. "
            + "Use small safe steps. For player chat work requests, act like a cooperative helper. Mine only when the goal requires it and the target block is visible.";
    }

    private static String defaultFakeAiGoal(final String name) {
        return "Follow the operator's intent, explore nearby blocks carefully, and use tools only when useful. Fake player name: " + name + ".";
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

    private static String blockLine(final Block block) {
        return block.getType().key().value() + "@" + block.getX() + "," + block.getY() + "," + block.getZ();
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
        boolean aiEnabled,
        String aiPersona,
        String aiStatus,
        String entityUuid
    ) {
    }

    private record FakeAiProfile(String id, String name, boolean enabled, String goal) {
    }

    private record MovePlan(double forward, double sideways, int ticks, boolean jump, boolean sprinting, boolean sneaking) {
    }

    private record TargetTask(FakePlayerSnapshot snapshot, String task) {
    }
}
