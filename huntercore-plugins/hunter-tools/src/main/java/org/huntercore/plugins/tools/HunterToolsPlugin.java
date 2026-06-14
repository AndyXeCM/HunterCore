package org.huntercore.plugins.tools;

import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HunterToolsPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private static final List<String> MODULES = List.of("tps-display", "sidebar", "motd", "command-overrides", "essentials", "management", "fake-players", "real-fake-players", "npcs", "ai", "web-panel");
    private static final String MOTD = "motd";
    private static final String COMMAND_OVERRIDES = "command-overrides";
    private static final String ESSENTIALS = "essentials";
    private static final String MANAGEMENT = "management";
    private static final String FAKE_PLAYERS = "fake-players";
    private static final String REAL_FAKE_PLAYERS = "real-fake-players";
    private static final String NPCS = "npcs";
    private static final String AI = "ai";
    private static final String WEB_PANEL = "web-panel";
    private static final String[] SIDEBAR_KEYS = {
        "§0", "§1", "§2", "§3", "§4", "§5", "§6", "§7", "§8", "§9", "§a", "§b", "§c", "§d", "§e", "§f"
    };

    private final Map<UUID, SidebarBoard> sidebars = new HashMap<>();
    private final Map<UUID, Location> backLocations = new HashMap<>();
    private HunterToolsPreferences preferences;
    private HunterActorManager actorManager;
    private HunterRealFakePlayerManager realFakePlayerManager;
    private HunterAiManager aiManager;
    private HunterWebPanelManager webPanelManager;
    private ExecutorService workerExecutor;
    private MetricsSnapshot snapshot = MetricsSnapshot.empty();
    private volatile List<String> cachedPlayerNames = List.of();
    private BukkitTask metricsTask;
    private BukkitTask actionbarTask;
    private BukkitTask sidebarTask;

    @Override
    public void onEnable() {
        this.preferences = HunterToolsPreferences.loadOrCreate(this);
        this.applyServerBrand();
        this.workerExecutor = this.createWorkerExecutor();
        this.actorManager = new HunterActorManager(this, this.preferences, this.workerExecutor);
        this.aiManager = new HunterAiManager(this, this.preferences, this.workerExecutor);
        this.realFakePlayerManager = new HunterRealFakePlayerManager(this, this.preferences, this.aiManager);
        this.webPanelManager = new HunterWebPanelManager(this, this.preferences);
        this.registerCommands();
        this.getServer().getPluginManager().registerEvents(this, this);
        this.startTasks();
        this.actorManager.reload();
        this.webPanelManager.start();
        this.getLogger().info("HunterTools enabled with preferences at " + this.preferences.file().getPath());
    }

    @Override
    public void onDisable() {
        this.cancelTasks();
        if (this.actorManager != null) {
            this.actorManager.shutdown();
        }
        if (this.realFakePlayerManager != null) {
            this.realFakePlayerManager.shutdown();
        }
        if (this.webPanelManager != null) {
            this.webPanelManager.stop();
        }
        this.clearSidebars();
        if (this.preferences != null) {
            this.preferences.flushPendingSaves();
        }
        if (this.workerExecutor != null) {
            this.workerExecutor.shutdownNow();
        }
        if (this.preferences != null) {
            this.preferences.saveNow();
        }
    }

    @Override
    public boolean onCommand(
        @NotNull final CommandSender sender,
        @NotNull final Command command,
        @NotNull final String label,
        @NotNull final String[] args
    ) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        return switch (name) {
            case "htps" -> this.showTps(sender);
            case "hunteradmin" -> this.admin(sender, args);
            case "heal" -> this.heal(sender, args);
            case "feed" -> this.feed(sender, args);
            case "fly" -> this.fly(sender, args);
            case "gm", "gms", "gmc", "gma", "gmsp" -> this.gameMode(sender, name, args);
            case "day", "night" -> this.time(sender, name, args);
            case "sun", "rain", "thunder" -> this.weather(sender, name, args);
            case "broadcast" -> this.broadcast(sender, args);
            case "clearchat" -> this.clearChat(sender);
            case "speed" -> this.speed(sender, args);
            case "spawn" -> this.spawn(sender, args);
            case "setspawn" -> this.setSpawn(sender);
            case "back" -> this.back(sender);
            case "hat" -> this.hat(sender);
            case "craft" -> this.craft(sender);
            case "enderchest" -> this.enderChest(sender, args);
            case "trash" -> this.trash(sender);
            case "fakeplayer" -> this.fakePlayer(sender, args);
            case "hplayer" -> this.realFakePlayer(sender, label, args);
            case "npc" -> this.npc(sender, args);
            default -> false;
        };
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull final CommandSender sender,
        @NotNull final Command command,
        @NotNull final String alias,
        @NotNull final String[] args
    ) {
        final String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("hunteradmin")) {
            return this.adminCompletions(args);
        }
        if (name.equals("fakeplayer")) {
            return this.actorManager == null ? List.of() : this.actorManager.completions(FAKE_PLAYERS, args);
        }
        if (name.equals("hplayer")) {
            return this.realFakePlayerManager == null ? List.of() : this.realFakePlayerManager.completions(args);
        }
        if (name.equals("npc")) {
            return this.actorManager == null ? List.of() : this.actorManager.completions(NPCS, args);
        }
        if (name.equals("gm") && args.length == 1) {
            return matching(args[0], List.of("survival", "creative", "adventure", "spectator"));
        }
        if (name.equals("fly") && args.length == 2) {
            return matching(args[1], List.of("on", "off"));
        }
        if ((name.equals("day") || name.equals("night") || name.equals("sun") || name.equals("rain") || name.equals("thunder")) && args.length == 1) {
            return matching(args[0], Bukkit.getWorlds().stream().map(World::getName).toList());
        }
        if (name.equals("enderchest") && args.length == 1 && sender.hasPermission("huntertools.command.enderchest.other")) {
            return matching(args[0], this.onlinePlayerNames());
        }
        if (List.of("heal", "feed", "fly", "gm", "gms", "gmc", "gma", "gmsp", "spawn", "speed").contains(name)) {
            final int playerArg = name.equals("speed") ? 1 : args.length - 1;
            if (args.length - 1 == playerArg) {
                return matching(args[args.length - 1], this.onlinePlayerNames());
            }
        }
        if (name.equals("speed") && args.length == 3) {
            return matching(args[2], List.of("walk", "fly"));
        }
        return List.of();
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        if (this.preferences.moduleEnabled("sidebar")) {
            this.updateSidebarSoon(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.sidebars.remove(event.getPlayer().getUniqueId());
        this.backLocations.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        if (this.preferences.moduleEnabled(ESSENTIALS) && this.preferences.commandEnabled(ESSENTIALS, "back")) {
            this.backLocations.put(event.getPlayer().getUniqueId(), event.getFrom().clone());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(final PlayerDeathEvent event) {
        if (this.preferences.moduleEnabled(ESSENTIALS) && this.preferences.commandEnabled(ESSENTIALS, "back")) {
            this.backLocations.put(event.getPlayer().getUniqueId(), event.getPlayer().getLocation().clone());
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onServerListPing(final ServerListPingEvent event) {
        if (!this.preferences.moduleEnabled(MOTD)) {
            return;
        }
        final String line1 = this.renderMotdLine(this.preferences.stringValue("modules.motd.line-1", "&b\"HunterCraft\" Server &8| &fHunterCore"), event);
        final String line2 = this.renderMotdLine(this.preferences.stringValue("modules.motd.line-2", "&7%online%/%max% players &8- &aTPS %tps% &8- &e%version%"), event);
        event.setMotd(color(line1 + "\n" + line2));
        final int maxPlayers = this.preferences.intValue("modules.motd.max-players", -1);
        if (maxPlayers > 0) {
            event.setMaxPlayers(maxPlayers);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommandPreprocess(final PlayerCommandPreprocessEvent event) {
        if (!this.preferences.moduleEnabled(COMMAND_OVERRIDES)) {
            return;
        }
        final String root = commandRoot(event.getMessage());
        switch (root) {
            case "about" -> {
                event.setCancelled(true);
                this.sendCommandOverride(event.getPlayer(), "about");
            }
            case "plugins", "pl" -> {
                event.setCancelled(true);
                this.sendCommandOverride(event.getPlayer(), "plugins");
            }
            case "op" -> {
                if (!this.canUseOp(event.getPlayer())) {
                    event.setCancelled(true);
                    this.sendCommandOverride(event.getPlayer(), "op-denied");
                }
            }
            default -> {
            }
        }
    }

    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerChat(final AsyncPlayerChatEvent event) {
        if (this.realFakePlayerManager != null && this.realFakePlayerManager.handleChatControl(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
            return;
        }
        if (this.aiManager != null && this.aiManager.handleChat(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onActorInteract(final PlayerInteractEntityEvent event) {
        if (this.actorManager != null) {
            if (this.actorManager.handleInteract(event.getPlayer(), event.getRightClicked())) {
                event.setCancelled(true);
                return;
            }
            final HunterActorManager.ActorInteraction interaction = this.actorManager.interaction(event.getRightClicked());
            if (interaction != null && this.aiManager != null && this.aiManager.handleActorInteract(event.getPlayer(), interaction)) {
                event.setCancelled(true);
                return;
            }
        }
        if (this.realFakePlayerManager != null && this.realFakePlayerManager.handleInteract(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    private void registerCommands() {
        for (final String command : List.of(
            "htps", "hunteradmin", "heal", "feed", "fly", "gm", "gms", "gmc", "gma", "gmsp",
            "day", "night", "sun", "rain", "thunder", "broadcast", "clearchat", "speed", "spawn", "setspawn", "back",
            "hat", "craft", "enderchest", "trash",
            "fakeplayer", "hplayer", "npc"
        )) {
            final org.bukkit.command.PluginCommand pluginCommand = this.getCommand(command);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
                pluginCommand.setTabCompleter(this);
            }
        }
    }

    private ExecutorService createWorkerExecutor() {
        final int configured = this.preferences.intValue("optimizations.hunter-tools.render-workers", 4);
        final int workers = this.preferences.singleThreadMode()
            ? 1
            : Math.max(1, Math.min(configured, Math.min(8, Runtime.getRuntime().availableProcessors())));
        final AtomicInteger id = new AtomicInteger();
        final ThreadFactory factory = task -> {
            final Thread thread = new Thread(task, "HunterTools worker " + id.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newFixedThreadPool(workers, factory);
    }

    private void startTasks() {
        this.cancelTasks();
        this.sampleMetrics();
        this.metricsTask = this.getServer().getScheduler().runTaskTimer(this, this::sampleMetrics, 20L, 20L);

        if (this.preferences.moduleEnabled("tps-display") && this.preferences.booleanValue("modules.tps-display.actionbar", true)) {
            final long interval = Math.max(20L, this.preferences.intValue("modules.tps-display.interval-ticks", 40));
            this.actionbarTask = this.getServer().getScheduler().runTaskTimer(this, this::tickActionBar, interval, interval);
        }

        if (this.preferences.moduleEnabled("sidebar")) {
            final long interval = Math.max(20L, this.preferences.intValue("modules.sidebar.interval-ticks", 40));
            this.sidebarTask = this.getServer().getScheduler().runTaskTimer(this, this::tickSidebar, 10L, interval);
        } else {
            this.clearSidebars();
        }
    }

    private void cancelTasks() {
        for (final BukkitTask task : new BukkitTask[] {this.metricsTask, this.actionbarTask, this.sidebarTask}) {
            if (task != null) {
                task.cancel();
            }
        }
        this.metricsTask = null;
        this.actionbarTask = null;
        this.sidebarTask = null;
    }

    private void sampleMetrics() {
        final double[] tps = Bukkit.getTPS();
        final Runtime runtime = Runtime.getRuntime();
        final long total = runtime.totalMemory();
        final long free = runtime.freeMemory();
        final double mspt = Bukkit.getAverageTickTime();
        final AdaptiveBudget adaptiveBudget = HunterRuntimeSampler.adaptiveBudget(this.preferences, mspt);
        final List<QueuePressure> queuePressures = HunterRuntimeSampler.queuePressures();
        final List<HotPathSample> hotPathSamples = HunterRuntimeSampler.hotPathSamples(
            Bukkit.getWorlds(),
            Bukkit.getOnlinePlayers().size(),
            this.realFakePlayerManager == null ? 0 : this.realFakePlayerManager.liveCount(),
            queuePressures
        );
        this.snapshot = new MetricsSnapshot(
            tps.length > 0 ? tps[0] : 20.0D,
            tps.length > 1 ? tps[1] : 20.0D,
            tps.length > 2 ? tps[2] : 20.0D,
            mspt,
            total - free,
            total,
            runtime.maxMemory(),
            Bukkit.getOnlinePlayers().size(),
            Bukkit.getMaxPlayers(),
            adaptiveBudget,
            queuePressures,
            hotPathSamples
        );
        if (this.preferences.booleanValue("optimizations.enabled", true) && this.preferences.booleanValue("optimizations.hunter-tools.player-cache", true)) {
            this.cachedPlayerNames = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
        }
    }

    void applyServerBrand() {
        try {
            final Class<?> purpurConfig = Class.forName("org.purpurmc.purpur.PurpurConfig");
            final Field f3Name = purpurConfig.getField("f3Name");
            final String brand = this.preferences == null
                ? "\"HunterCraft\" Server"
                : this.preferences.stringValue("modules.management.f3-server-name", "\"HunterCraft\" Server");
            f3Name.set(null, brand == null || brand.isBlank() ? "\"HunterCraft\" Server" : brand);
        } catch (final ReflectiveOperationException ex) {
            this.getLogger().fine("Unable to set runtime F3 server brand: " + ex.getMessage());
        }
    }

    private void tickActionBar() {
        if (!this.preferences.moduleEnabled("tps-display")) {
            return;
        }
        final MetricsSnapshot current = this.snapshot;
        final Component message = Component.text()
            .append(Component.text("TPS ", NamedTextColor.GRAY))
            .append(Component.text(MetricsSnapshot.formatTps(current.tps1()), tpsColor(current.tps1())))
            .append(Component.text("  MSPT ", NamedTextColor.GRAY))
            .append(Component.text(String.format(Locale.ROOT, "%.1f", current.mspt()), current.mspt() > 50.0D ? NamedTextColor.RED : NamedTextColor.GREEN))
            .append(Component.text("  Players ", NamedTextColor.GRAY))
            .append(Component.text(current.onlinePlayers() + "/" + current.maxPlayers(), NamedTextColor.WHITE))
            .build();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("huntertools.display.tps")) {
                player.sendActionBar(message);
            }
        }
    }

    private void tickSidebar() {
        if (!this.preferences.moduleEnabled("sidebar")) {
            this.clearSidebars();
            return;
        }
        final List<PlayerView> players = Bukkit.getOnlinePlayers().stream()
            .filter(player -> player.hasPermission("huntertools.display.sidebar"))
            .map(player -> new PlayerView(player.getUniqueId(), player.getName(), player.getWorld().getName(), player.getPing()))
            .toList();
        final MetricsSnapshot current = this.snapshot;
        if (players.isEmpty()) {
            return;
        }
        if (this.preferences.booleanValue("optimizations.enabled", true) && this.preferences.booleanValue("optimizations.hunter-tools.async-rendering", true)) {
            CompletableFuture
                .supplyAsync(() -> renderSidebars(current, players), this.workerExecutor)
                .thenAccept(rendered -> this.getServer().getScheduler().runTask(this, () -> applySidebars(rendered)));
        } else {
            this.applySidebars(renderSidebars(current, players));
        }
    }

    private void updateSidebarSoon(final Player player) {
        this.getServer().getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline() || !this.preferences.moduleEnabled("sidebar")) {
                return;
            }
            this.applySidebar(player, buildSidebarLines(this.snapshot, new PlayerView(player.getUniqueId(), player.getName(), player.getWorld().getName(), player.getPing())));
        }, 20L);
    }

    private static Map<UUID, List<String>> renderSidebars(final MetricsSnapshot snapshot, final List<PlayerView> players) {
        final Map<UUID, List<String>> rendered = new HashMap<>();
        for (final PlayerView player : players) {
            rendered.put(player.uuid(), buildSidebarLines(snapshot, player));
        }
        return rendered;
    }

    private static List<String> buildSidebarLines(final MetricsSnapshot snapshot, final PlayerView player) {
        return List.of(
            ChatColor.GRAY + "TPS: " + colorCode(snapshot.tps1()) + MetricsSnapshot.formatTps(snapshot.tps1()),
            ChatColor.GRAY + "MSPT: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.1f", snapshot.mspt()),
            ChatColor.GRAY + "Players: " + ChatColor.WHITE + snapshot.onlinePlayers() + "/" + snapshot.maxPlayers(),
            ChatColor.GRAY + "Memory: " + ChatColor.WHITE + snapshot.memoryLine(),
            ChatColor.GRAY + "World: " + ChatColor.WHITE + player.world(),
            ChatColor.GRAY + "Ping: " + ChatColor.WHITE + player.ping() + "ms"
        );
    }

    private void applySidebars(final Map<UUID, List<String>> rendered) {
        final Set<UUID> seen = new HashSet<>();
        for (final Map.Entry<UUID, List<String>> entry : rendered.entrySet()) {
            final Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                seen.add(player.getUniqueId());
                this.applySidebar(player, entry.getValue());
            }
        }
        this.sidebars.keySet().removeIf(uuid -> {
            if (seen.contains(uuid)) {
                return false;
            }
            final Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
            return true;
        });
    }

    private void applySidebar(final Player player, final List<String> lines) {
        final boolean dirtyOnly = this.preferences.booleanValue("modules.sidebar.dirty-updates-only", true);
        final SidebarBoard board = this.sidebars.computeIfAbsent(player.getUniqueId(), ignored -> this.createSidebar());
        final List<String> encoded = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size() && i < SIDEBAR_KEYS.length; i++) {
            encoded.add(SIDEBAR_KEYS[i] + lines.get(i));
        }
        if (dirtyOnly && encoded.equals(board.entries)) {
            if (player.getScoreboard() != board.scoreboard) {
                player.setScoreboard(board.scoreboard);
            }
            return;
        }
        for (final String old : board.entries) {
            board.scoreboard.resetScores(old);
        }
        int score = encoded.size();
        for (final String entry : encoded) {
            board.objective.getScore(entry).setScore(score--);
        }
        board.entries = encoded;
        if (player.getScoreboard() != board.scoreboard) {
            player.setScoreboard(board.scoreboard);
        }
    }

    private SidebarBoard createSidebar() {
        final ScoreboardManager manager = Bukkit.getScoreboardManager();
        final Scoreboard scoreboard = manager.getNewScoreboard();
        final Objective objective = scoreboard.registerNewObjective("huntercore", Criteria.DUMMY, Component.text("HunterCore", NamedTextColor.GOLD));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.setAutoUpdateDisplay(false);
        return new SidebarBoard(scoreboard, objective, List.of());
    }

    private void clearSidebars() {
        final Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        for (final UUID uuid : new ArrayList<>(this.sidebars.keySet())) {
            final Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                player.setScoreboard(main);
            }
        }
        this.sidebars.clear();
    }

    private boolean showTps(final CommandSender sender) {
        if (!this.preferences.moduleEnabled("tps-display")) {
            sender.sendMessage("HunterCore TPS display is disabled in preferences.yml.");
            return true;
        }
        final MetricsSnapshot current = this.snapshot;
        sender.sendMessage(ChatColor.GOLD + "HunterCore TPS");
        sender.sendMessage(ChatColor.GRAY + "1m/5m/15m: " + colorCode(current.tps1()) + MetricsSnapshot.formatTps(current.tps1())
            + ChatColor.GRAY + " / " + colorCode(current.tps5()) + MetricsSnapshot.formatTps(current.tps5())
            + ChatColor.GRAY + " / " + colorCode(current.tps15()) + MetricsSnapshot.formatTps(current.tps15()));
        sender.sendMessage(ChatColor.GRAY + "MSPT: " + ChatColor.WHITE + String.format(Locale.ROOT, "%.2f", current.mspt()));
        sender.sendMessage(ChatColor.GRAY + "Memory: " + ChatColor.WHITE + current.memoryLine());
        return true;
    }

    private boolean admin(final CommandSender sender, final String[] args) {
        if (!this.require(sender, "huntertools.command.admin")) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "HunterAdmin: " + ChatColor.YELLOW + "reload, modules, module, command, plugins, memory, gc, threads, optimize, motd, web, ai");
            return true;
        }
        final String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "reload" -> this.adminReload(sender);
            case "modules" -> this.adminModules(sender);
            case "module" -> this.adminToggleModule(sender, args);
            case "command" -> this.adminToggleCommand(sender, args);
            case "plugins" -> this.adminPlugins(sender);
            case "memory" -> this.adminMemory(sender);
            case "gc" -> this.adminGc(sender);
            case "threads" -> this.adminThreads(sender);
            case "optimize" -> this.adminOptimize(sender, args);
            case "motd" -> this.adminMotd(sender, args);
            case "web" -> this.adminWeb(sender, args);
            case "ai" -> this.adminAi(sender, args);
            default -> {
                sender.sendMessage("Usage: /hunteradmin <reload|modules|module|command|plugins|memory|gc|threads|optimize|motd|web|ai>");
                yield true;
            }
        };
    }

    private boolean adminReload(final CommandSender sender) {
        this.preferences.reload();
        if (this.workerExecutor != null) {
            this.workerExecutor.shutdownNow();
        }
        this.workerExecutor = this.createWorkerExecutor();
        if (this.actorManager != null) {
            this.actorManager.setExecutor(this.workerExecutor);
            this.actorManager.reload();
        }
        if (this.aiManager != null) {
            this.aiManager.setExecutor(this.workerExecutor);
        }
        if (this.realFakePlayerManager != null) {
            this.realFakePlayerManager.setAiManager(this.aiManager);
        }
        if (this.realFakePlayerManager != null && !this.preferences.moduleEnabled(REAL_FAKE_PLAYERS)) {
            this.realFakePlayerManager.shutdown();
        }
        if (this.webPanelManager != null) {
            this.webPanelManager.restart();
        }
        this.startTasks();
        sender.sendMessage("HunterCore preferences reloaded from " + this.preferences.file().getPath() + ".");
        return true;
    }

    private boolean adminModules(final CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "HunterCore modules:");
        for (final String module : MODULES) {
            sender.sendMessage("- " + module + ": " + (this.preferences.moduleEnabled(module) ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
        }
        return true;
    }

    private boolean adminToggleModule(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin module <module> <on|off>");
            return true;
        }
        final String module = HunterToolsPreferences.normalize(args[1]);
        if (!MODULES.contains(module)) {
            sender.sendMessage("Unknown module. Available: " + String.join(", ", MODULES));
            return true;
        }
        final Boolean enabled = parseToggle(args[2]);
        if (enabled == null) {
            sender.sendMessage("Use on/off.");
            return true;
        }
        this.preferences.setModuleEnabled(module, enabled);
        this.preferences.save(this.workerExecutor);
        this.startTasks();
        if (this.actorManager != null && (module.equals(FAKE_PLAYERS) || module.equals(NPCS))) {
            this.actorManager.reload();
        }
        if (this.realFakePlayerManager != null && module.equals(REAL_FAKE_PLAYERS) && !enabled) {
            this.realFakePlayerManager.shutdown();
        }
        if (this.webPanelManager != null && module.equals(WEB_PANEL)) {
            this.webPanelManager.restart();
        }
        sender.sendMessage("HunterCore module " + module + " set to " + enabled + ".");
        return true;
    }

    private boolean adminToggleCommand(final CommandSender sender, final String[] args) {
        if (args.length != 4) {
            sender.sendMessage("Usage: /hunteradmin command <essentials|management|fake-players|real-fake-players|npcs> <command> <on|off>");
            return true;
        }
        final String module = HunterToolsPreferences.normalize(args[1]);
        final String command = HunterToolsPreferences.normalize(args[2]);
        if (!module.equals(ESSENTIALS) && !module.equals(MANAGEMENT) && !module.equals(FAKE_PLAYERS) && !module.equals(REAL_FAKE_PLAYERS) && !module.equals(NPCS)) {
            sender.sendMessage("Command toggles are available for essentials, management, fake-players, real-fake-players, and npcs.");
            return true;
        }
        final Boolean enabled = parseToggle(args[3]);
        if (enabled == null) {
            sender.sendMessage("Use on/off.");
            return true;
        }
        this.preferences.setCommandEnabled(module, command, enabled);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore command " + module + "." + command + " set to " + enabled + ".");
        return true;
    }

    private boolean adminPlugins(final CommandSender sender) {
        if (!this.managementCommandEnabled(sender, "plugins")) {
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "Loaded plugins (" + Bukkit.getPluginManager().getPlugins().length + "):");
        for (final Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            sender.sendMessage("- " + plugin.getName() + " " + plugin.getPluginMeta().getVersion() + " " + (plugin.isEnabled() ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
        }
        return true;
    }

    private boolean adminMemory(final CommandSender sender) {
        if (!this.managementCommandEnabled(sender, "memory")) {
            return true;
        }
        final MetricsSnapshot current = this.snapshot;
        sender.sendMessage(ChatColor.GOLD + "Memory: " + ChatColor.WHITE + current.memoryLine());
        sender.sendMessage(ChatColor.GRAY + "Allocated: " + ChatColor.WHITE + MetricsSnapshot.formatBytes(current.totalMemory()));
        return true;
    }

    private boolean adminGc(final CommandSender sender) {
        if (!this.managementCommandEnabled(sender, "gc")) {
            return true;
        }
        final long before = this.snapshot.usedMemory();
        CompletableFuture.runAsync(System::gc, this.workerExecutor).thenRun(() -> this.getServer().getScheduler().runTask(this, () -> {
            this.sampleMetrics();
            sender.sendMessage("GC requested off the main thread. Used memory: " + MetricsSnapshot.formatBytes(before) + " -> " + MetricsSnapshot.formatBytes(this.snapshot.usedMemory()));
        }));
        return true;
    }

    private boolean adminThreads(final CommandSender sender) {
        if (!this.managementCommandEnabled(sender, "threads")) {
            return true;
        }
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        sender.sendMessage(ChatColor.GOLD + "Threads: " + ChatColor.WHITE + bean.getThreadCount() + " live, " + bean.getDaemonThreadCount() + " daemon, peak " + bean.getPeakThreadCount());
        sender.sendMessage(ChatColor.GRAY + "HunterTools render workers: " + ChatColor.WHITE + this.preferences.intValue("optimizations.hunter-tools.render-workers", 4));
        sender.sendMessage(ChatColor.GRAY + "Fake players: " + ChatColor.WHITE + (this.actorManager == null ? 0 : this.actorManager.liveCount(FAKE_PLAYERS)) + " live");
        sender.sendMessage(ChatColor.GRAY + "Real fake players: " + ChatColor.WHITE + (this.realFakePlayerManager == null ? 0 : this.realFakePlayerManager.liveCount()) + " live");
        sender.sendMessage(ChatColor.GRAY + "NPCs: " + ChatColor.WHITE + (this.actorManager == null ? 0 : this.actorManager.liveCount(NPCS)) + " live");
        return true;
    }

    private boolean adminOptimize(final CommandSender sender, final String[] args) {
        if (!this.managementCommandEnabled(sender, "optimize")) {
            return true;
        }
        if (args.length >= 2 && !args[1].equalsIgnoreCase("status")) {
            if (!validCpuMode(args[1])) {
                sender.sendMessage("Usage: /hunteradmin optimize <single-thread|high-clock|high-core|multi-thread|status>");
                return true;
            }
            final String mode = normalizeCpuMode(args[1]);
            final boolean asyncEnabled = !mode.equals("single-thread");
            this.preferences.setValue("optimizations.cpu.mode", mode);
            this.preferences.setValue("optimizations.hunter-tools.async-rendering", asyncEnabled);
            this.preferences.setValue("optimizations.hunter-tools.async-save", asyncEnabled);
            this.preferences.setValue("optimizations.hunter-tools.actor-async-load", asyncEnabled);
            this.preferences.setValue("optimizations.hunter-tools.actor-batch-save", asyncEnabled);
            this.preferences.setValue("optimizations.hunter-tools.render-workers", this.preferences.defaultWorkerCount());
            this.preferences.setValue("optimizations.hunter-tools.web-panel-workers", this.preferences.defaultWorkerCount());
            this.preferences.save(this.workerExecutor);
            sender.sendMessage("HunterCore CPU mode saved as " + mode + ". Restart the server to fully apply core thread settings.");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "HunterCore CPU optimization");
        sender.sendMessage(ChatColor.GRAY + "Mode: " + ChatColor.WHITE + this.preferences.stringValue("optimizations.cpu.mode", "single-thread"));
        sender.sendMessage(ChatColor.GRAY + "CPU threads: " + ChatColor.WHITE + Runtime.getRuntime().availableProcessors());
        sender.sendMessage(ChatColor.GRAY + "Paper workers: " + ChatColor.WHITE + System.getProperty("Paper.WorkerThreadCount", "auto"));
        sender.sendMessage(ChatColor.GRAY + "DivineMC workers: " + ChatColor.WHITE + System.getProperty("DivineMC.WorkerThreadCount", "auto"));
        sender.sendMessage(ChatColor.GRAY + "Netty IO threads: " + ChatColor.WHITE + System.getProperty("io.netty.eventLoopThreads", "auto"));
        sender.sendMessage(ChatColor.GRAY + "ForkJoin common parallelism: " + ChatColor.WHITE + System.getProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "auto"));
        sender.sendMessage(ChatColor.GRAY + "HunterTools workers: " + ChatColor.WHITE + this.preferences.intValue("optimizations.hunter-tools.render-workers", 4));
        sender.sendMessage(ChatColor.GRAY + "Async actor load: " + ChatColor.WHITE + this.preferences.booleanValue("optimizations.hunter-tools.actor-async-load", true));
        sender.sendMessage(ChatColor.GRAY + "Async/batched actor save: " + ChatColor.WHITE + this.preferences.booleanValue("optimizations.hunter-tools.actor-batch-save", true));
        sender.sendMessage(ChatColor.GRAY + "Web panel workers: " + ChatColor.WHITE + this.preferences.intValue("optimizations.hunter-tools.web-panel-workers", 4));
        sender.sendMessage(ChatColor.GRAY + "Experimental region ticking: " + ChatColor.WHITE + this.preferences.booleanValue("optimizations.cpu.allow-experimental-region-ticking", false));
        sender.sendMessage(ChatColor.GRAY + "Guest status cache: " + ChatColor.WHITE + this.preferences.intValue("modules.web-panel.status-cache-millis", 1000) + "ms");
        sender.sendMessage(ChatColor.DARK_GRAY + "Use /hunteradmin optimize single-thread, high-clock, high-core, or multi-thread, then restart.");
        return true;
    }

    private boolean adminMotd(final CommandSender sender, final String[] args) {
        if (!this.managementCommandEnabled(sender, "motd")) {
            return true;
        }
        if (args.length < 2 || args.length == 2 && args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "HunterCore MOTD");
            sender.sendMessage(ChatColor.GRAY + "Enabled: " + ChatColor.WHITE + this.preferences.moduleEnabled(MOTD));
            sender.sendMessage(ChatColor.GRAY + "Line 1: " + ChatColor.WHITE + this.preferences.stringValue("modules.motd.line-1", ""));
            sender.sendMessage(ChatColor.GRAY + "Line 2: " + ChatColor.WHITE + this.preferences.stringValue("modules.motd.line-2", ""));
            sender.sendMessage(ChatColor.GRAY + "Max players: " + ChatColor.WHITE + this.preferences.intValue("modules.motd.max-players", -1));
            sender.sendMessage(ChatColor.GRAY + "Placeholders: " + ChatColor.WHITE + "%online%, %max%, %tps%, %mspt%, %version%.");
            return true;
        }
        final String sub = HunterToolsPreferences.normalize(args[1]);
        if ((sub.equals("line1") || sub.equals("line-1") || sub.equals("line2") || sub.equals("line-2")) && args.length >= 3) {
            final String key = sub.endsWith("2") || sub.equals("line-2") ? "line-2" : "line-1";
            final String text = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
            if (text.length() > 256) {
                sender.sendMessage("MOTD line must be 256 characters or fewer.");
                return true;
            }
            this.preferences.setValue("modules.motd." + key, text);
            this.preferences.save(this.workerExecutor);
            sender.sendMessage("HunterCore MOTD " + key + " updated.");
            return true;
        }
        if (sub.equals("max") && args.length == 3) {
            if (List.of("default", "off", "reset", "-").contains(HunterToolsPreferences.normalize(args[2]))) {
                this.preferences.setValue("modules.motd.max-players", -1);
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore MOTD max players reset to the server default.");
                return true;
            }
            try {
                final int maxPlayers = Integer.parseInt(args[2]);
                if (maxPlayers < 1 || maxPlayers > 1_000_000) {
                    sender.sendMessage("Max players must be between 1 and 1000000, or default.");
                    return true;
                }
                this.preferences.setValue("modules.motd.max-players", maxPlayers);
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore MOTD max players set to " + maxPlayers + ".");
                return true;
            } catch (final NumberFormatException ex) {
                sender.sendMessage("Max players must be a number, or default.");
                return true;
            }
        }
        sender.sendMessage("Usage: /hunteradmin motd <status|line1 <text>|line2 <text>|max <number|default>>");
        return true;
    }

    private boolean adminWeb(final CommandSender sender, final String[] args) {
        if (!this.managementCommandEnabled(sender, "web")) {
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage("Usage: /hunteradmin web <status|restart|bind|port|map|public-map|user|remove|users|allow|execution>");
            return true;
        }
        final String sub = args[1].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "status" -> {
                final boolean running = this.webPanelManager != null && this.webPanelManager.running();
                sender.sendMessage(ChatColor.GOLD + "HunterCore web panel: " + (running ? ChatColor.GREEN + "running " : ChatColor.RED + "stopped ") + (this.webPanelManager == null ? "" : this.webPanelManager.addressLine()));
                sender.sendMessage(ChatColor.GRAY + "Bind: " + ChatColor.WHITE + this.preferences.stringValue("modules.web-panel.bind-address", "127.0.0.1")
                    + ChatColor.GRAY + " Port: " + ChatColor.WHITE + this.preferences.intValue("modules.web-panel.port", 8088));
                sender.sendMessage(ChatColor.GRAY + "Public map: " + ChatColor.WHITE + this.preferences.booleanValue("modules.web-panel.public-map", true)
                    + ChatColor.GRAY + " URL: " + ChatColor.WHITE + this.preferences.stringValue("modules.web-panel.map-url", "http://%host%:8100/"));
                yield true;
            }
            case "restart" -> {
                if (this.webPanelManager != null) {
                    this.webPanelManager.restart();
                }
                sender.sendMessage("HunterCore web panel restarted.");
                yield true;
            }
            case "bind", "address" -> this.adminWebBind(sender, args);
            case "port" -> this.adminWebPort(sender, args);
            case "map" -> this.adminWebMap(sender, args);
            case "public-map" -> this.adminWebPublicMap(sender, args);
            case "users" -> {
                sender.sendMessage(ChatColor.GOLD + "HunterCore web users:");
                for (final String id : this.preferences.webUserIds()) {
                    final HunterToolsPreferences.WebUser user = this.preferences.webUser(id);
                    if (user != null) {
                        sender.sendMessage("- " + user.displayName() + ": " + user.role()
                            + (user.passwordConfigured() ? "" : " (password not set)")
                            + ", execution=" + user.commandExecution()
                            + ", allowed=" + webAllowedLine(user));
                    }
                }
                yield true;
            }
            case "user" -> this.adminWebUser(sender, args);
            case "remove" -> this.adminWebRemove(sender, args);
            case "allow" -> this.adminWebAllow(sender, args);
            case "execution" -> this.adminWebExecution(sender, args);
            default -> {
                sender.sendMessage("Usage: /hunteradmin web <status|restart|bind|port|map|public-map|user|remove|users|allow|execution>");
                yield true;
            }
        };
    }

    private boolean adminWebUser(final CommandSender sender, final String[] args) {
        if (args.length != 5) {
            sender.sendMessage("Usage: /hunteradmin web user <name> <admin|player> <password>");
            return true;
        }
        final String role = HunterToolsPreferences.normalize(args[3]);
        if (!role.equals("admin") && !role.equals("player")) {
            sender.sendMessage("Role must be admin or player.");
            return true;
        }
        this.preferences.setWebUser(args[2], role, HunterWebPanelManager.hashPassword(args[4]));
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore web user " + HunterToolsPreferences.webUserId(args[2]) + " saved as " + role + ".");
        return true;
    }

    private boolean adminWebRemove(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin web remove <name>");
            return true;
        }
        this.preferences.removeWebUser(args[2]);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore web user " + HunterToolsPreferences.webUserId(args[2]) + " removed.");
        return true;
    }

    private boolean adminWebAllow(final CommandSender sender, final String[] args) {
        if (args.length < 4) {
            sender.sendMessage("Usage: /hunteradmin web allow <name> <inherit|none|*|command...>");
            return true;
        }
        final HunterToolsPreferences.WebUser user = this.preferences.webUser(args[2]);
        if (user == null) {
            sender.sendMessage("Unknown web user.");
            return true;
        }
        final String mode = args[3].toLowerCase(Locale.ROOT);
        final List<String> commands;
        if (mode.equals("inherit")) {
            commands = null;
        } else if (mode.equals("none")) {
            commands = List.of();
        } else {
            final List<String> parsed = new ArrayList<>();
            for (int i = 3; i < args.length; i++) {
                for (final String raw : args[i].split(",")) {
                    final String command = normalizeWebCommand(raw);
                    if (!command.isBlank() && !parsed.contains(command)) {
                        parsed.add(command);
                    }
                }
            }
            commands = parsed;
        }
        this.preferences.setWebUserAllowedCommands(args[2], commands);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore web user " + HunterToolsPreferences.webUserId(args[2]) + " allowed commands set to " + (commands == null ? "inherit" : commands) + ".");
        return true;
    }

    private boolean adminWebExecution(final CommandSender sender, final String[] args) {
        if (args.length != 4) {
            sender.sendMessage("Usage: /hunteradmin web execution <name> <on|off>");
            return true;
        }
        if (this.preferences.webUser(args[2]) == null) {
            sender.sendMessage("Unknown web user.");
            return true;
        }
        final Boolean enabled = parseToggle(args[3]);
        if (enabled == null) {
            sender.sendMessage("Use on/off.");
            return true;
        }
        this.preferences.setWebUserCommandExecution(args[2], enabled);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore web user " + HunterToolsPreferences.webUserId(args[2]) + " command execution set to " + enabled + ".");
        return true;
    }

    private boolean adminWebBind(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin web bind <address>");
            return true;
        }
        final String bindAddress = args[2].trim();
        if (bindAddress.isBlank() || bindAddress.length() > 128 || bindAddress.contains(" ")) {
            sender.sendMessage("Invalid bind address.");
            return true;
        }
        this.preferences.setValue("modules.web-panel.bind-address", bindAddress);
        this.preferences.save(this.workerExecutor);
        if (this.webPanelManager != null) {
            this.webPanelManager.restart();
        }
        sender.sendMessage("HunterCore web bind address set to " + bindAddress + " and panel restarted.");
        return true;
    }

    private boolean adminWebPort(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin web port <1-65535>");
            return true;
        }
        final int port;
        try {
            port = Integer.parseInt(args[2]);
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Port must be a number.");
            return true;
        }
        if (port < 1 || port > 65535) {
            sender.sendMessage("Port must be between 1 and 65535.");
            return true;
        }
        this.preferences.setValue("modules.web-panel.port", port);
        this.preferences.save(this.workerExecutor);
        if (this.webPanelManager != null) {
            this.webPanelManager.restart();
        }
        sender.sendMessage("HunterCore web port set to " + port + " and panel restarted.");
        return true;
    }

    private boolean adminWebMap(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin web map <url>");
            return true;
        }
        final String mapUrl = args[2].trim();
        if (mapUrl.isBlank() || mapUrl.length() > 512) {
            sender.sendMessage("Invalid map URL.");
            return true;
        }
        this.preferences.setValue("modules.web-panel.map-url", mapUrl);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore web map URL set to " + mapUrl + ".");
        return true;
    }

    private boolean adminWebPublicMap(final CommandSender sender, final String[] args) {
        if (args.length != 3) {
            sender.sendMessage("Usage: /hunteradmin web public-map <on|off>");
            return true;
        }
        final Boolean enabled = parseToggle(args[2]);
        if (enabled == null) {
            sender.sendMessage("Use on/off.");
            return true;
        }
        this.preferences.setValue("modules.web-panel.public-map", enabled);
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore public map access set to " + enabled + ".");
        return true;
    }

    private boolean adminAi(final CommandSender sender, final String[] args) {
        if (!this.managementCommandEnabled(sender, "ai")) {
            return true;
        }
        if (args.length < 2 || args[1].equalsIgnoreCase("status")) {
            sender.sendMessage(ChatColor.GOLD + "HunterCore AI");
            sender.sendMessage(ChatColor.GRAY + "Module: " + ChatColor.WHITE + this.preferences.moduleEnabled(AI));
            sender.sendMessage(ChatColor.GRAY + "Base URL: " + ChatColor.WHITE + this.preferences.stringValue("modules.ai.base-url", "https://api.openai.com/v1"));
            sender.sendMessage(ChatColor.GRAY + "Model: " + ChatColor.WHITE + this.preferences.stringValue("modules.ai.model", "gpt-4o-mini"));
            sender.sendMessage(ChatColor.GRAY + "API key: " + ChatColor.WHITE + (this.aiApiKeyConfigured() ? "configured" : "missing"));
            sender.sendMessage(ChatColor.GRAY + "Chat: " + ChatColor.WHITE + this.preferences.booleanValue("modules.ai.chat.enabled", true)
                + ChatColor.GRAY + " prefix " + ChatColor.WHITE + this.preferences.stringValue("modules.ai.chat.trigger-prefix", "@ai"));
            sender.sendMessage(ChatColor.GRAY + "NPC: " + ChatColor.WHITE + this.preferences.booleanValue("modules.ai.npc.enabled", true)
                + ChatColor.GRAY + " actions " + ChatColor.WHITE + this.preferences.booleanValue("modules.ai.npc.allow-actions", true));
            return true;
        }

        final String sub = HunterToolsPreferences.normalize(args[1]);
        switch (sub) {
            case "enable", "on" -> {
                this.preferences.setModuleEnabled(AI, true);
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI module enabled.");
                return true;
            }
            case "disable", "off" -> {
                this.preferences.setModuleEnabled(AI, false);
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI module disabled.");
                return true;
            }
            case "model" -> {
                if (args.length != 3 || args[2].isBlank() || args[2].length() > 128) {
                    sender.sendMessage("Usage: /hunteradmin ai model <model>");
                    return true;
                }
                this.preferences.setValue("modules.ai.model", args[2].trim());
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI model set to " + args[2].trim() + ".");
                return true;
            }
            case "base-url", "baseurl", "url" -> {
                if (args.length != 3 || args[2].isBlank() || args[2].length() > 512) {
                    sender.sendMessage("Usage: /hunteradmin ai base-url <url>");
                    return true;
                }
                this.preferences.setValue("modules.ai.base-url", args[2].trim());
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI base URL updated.");
                return true;
            }
            case "key", "api-key" -> {
                if (args.length != 3 || args[2].isBlank() || args[2].length() > 512) {
                    sender.sendMessage("Usage: /hunteradmin ai key <api-key>");
                    return true;
                }
                this.preferences.setValue("modules.ai.api-key", args[2].trim());
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI API key saved.");
                return true;
            }
            case "clear-key", "clearkey" -> {
                this.preferences.setValue("modules.ai.api-key", "");
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI API key cleared. Env fallback can still be used.");
                return true;
            }
            case "env", "api-key-env" -> {
                if (args.length != 3 || args[2].isBlank() || args[2].length() > 128) {
                    sender.sendMessage("Usage: /hunteradmin ai env <ENV_NAME>");
                    return true;
                }
                this.preferences.setValue("modules.ai.api-key-env", args[2].trim());
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI API key env set to " + args[2].trim() + ".");
                return true;
            }
            case "prefix" -> {
                if (args.length != 3 || args[2].isBlank() || args[2].length() > 32) {
                    sender.sendMessage("Usage: /hunteradmin ai prefix <trigger>");
                    return true;
                }
                this.preferences.setValue("modules.ai.chat.trigger-prefix", args[2].trim());
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI chat prefix set to " + args[2].trim() + ".");
                return true;
            }
            case "chat", "npc" -> {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /hunteradmin ai " + sub + " <on|off>");
                    return true;
                }
                final Boolean enabled = parseToggle(args[2]);
                if (enabled == null) {
                    sender.sendMessage("Use on/off.");
                    return true;
                }
                this.preferences.setValue("modules.ai." + sub + ".enabled", enabled);
                this.preferences.save(this.workerExecutor);
                sender.sendMessage("HunterCore AI " + sub + " set to " + enabled + ".");
                return true;
            }
            case "temperature" -> {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /hunteradmin ai temperature <0.0-2.0>");
                    return true;
                }
                try {
                    final double temperature = Double.parseDouble(args[2]);
                    if (temperature < 0.0D || temperature > 2.0D) {
                        sender.sendMessage("Temperature must be between 0.0 and 2.0.");
                        return true;
                    }
                    this.preferences.setValue("modules.ai.temperature", temperature);
                    this.preferences.save(this.workerExecutor);
                    sender.sendMessage("HunterCore AI temperature set to " + temperature + ".");
                } catch (final NumberFormatException ex) {
                    sender.sendMessage("Temperature must be a number.");
                }
                return true;
            }
            case "max-tokens", "maxtokens" -> {
                if (args.length != 3) {
                    sender.sendMessage("Usage: /hunteradmin ai max-tokens <16-4096>");
                    return true;
                }
                try {
                    final int maxTokens = Integer.parseInt(args[2]);
                    if (maxTokens < 16 || maxTokens > 4096) {
                        sender.sendMessage("Max tokens must be between 16 and 4096.");
                        return true;
                    }
                    this.preferences.setValue("modules.ai.max-tokens", maxTokens);
                    this.preferences.save(this.workerExecutor);
                    sender.sendMessage("HunterCore AI max tokens set to " + maxTokens + ".");
                } catch (final NumberFormatException ex) {
                    sender.sendMessage("Max tokens must be a number.");
                }
                return true;
            }
            case "test" -> {
                if (args.length < 3) {
                    sender.sendMessage("Usage: /hunteradmin ai test <prompt>");
                    return true;
                }
                final String prompt = String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length)).trim();
                sender.sendMessage("HunterCore AI test request started...");
                this.testAiPrompt(prompt).whenComplete((response, error) -> this.getServer().getScheduler().runTask(this, () -> {
                    if (error != null) {
                        sender.sendMessage(ChatColor.RED + "HunterCore AI test failed: " + (error.getCause() == null ? error.getMessage() : error.getCause().getMessage()));
                    } else {
                        sender.sendMessage(ChatColor.AQUA + "AI > " + ChatColor.WHITE + response);
                    }
                }));
                return true;
            }
            default -> {
                sender.sendMessage("Usage: /hunteradmin ai <status|enable|disable|model|base-url|key|clear-key|env|prefix|chat|npc|temperature|max-tokens|test>");
                return true;
            }
        }
    }

    private boolean heal(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "heal")) {
            return true;
        }
        final Player target = this.target(sender, args, 0);
        if (target == null) {
            return true;
        }
        final AttributeInstance maxHealthAttribute = target.getAttribute(Attribute.MAX_HEALTH);
        final double maxHealth = maxHealthAttribute == null ? 20.0D : maxHealthAttribute.getValue();
        target.setHealth(maxHealth);
        target.setFireTicks(0);
        target.sendMessage("You have been healed.");
        if (!sender.equals(target)) {
            sender.sendMessage("Healed " + target.getName() + ".");
        }
        return true;
    }

    private boolean feed(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "feed")) {
            return true;
        }
        final Player target = this.target(sender, args, 0);
        if (target == null) {
            return true;
        }
        target.setFoodLevel(20);
        target.setSaturation(20.0F);
        target.sendMessage("You have been fed.");
        if (!sender.equals(target)) {
            sender.sendMessage("Fed " + target.getName() + ".");
        }
        return true;
    }

    private boolean fly(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "fly")) {
            return true;
        }
        final Player target = args.length > 0 ? this.player(args[0], sender) : this.self(sender);
        if (target == null) {
            return true;
        }
        final Boolean explicit = args.length > 1 ? parseToggle(args[1]) : null;
        final boolean enabled = explicit == null ? !target.getAllowFlight() : explicit;
        target.setAllowFlight(enabled);
        if (!enabled) {
            target.setFlying(false);
        }
        target.sendMessage("Flight " + (enabled ? "enabled" : "disabled") + ".");
        if (!sender.equals(target)) {
            sender.sendMessage("Flight for " + target.getName() + " " + (enabled ? "enabled" : "disabled") + ".");
        }
        return true;
    }

    private boolean gameMode(final CommandSender sender, final String command, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "gm")) {
            return true;
        }
        final GameMode mode;
        int playerArg = 0;
        switch (command) {
            case "gms" -> mode = GameMode.SURVIVAL;
            case "gmc" -> mode = GameMode.CREATIVE;
            case "gma" -> mode = GameMode.ADVENTURE;
            case "gmsp" -> mode = GameMode.SPECTATOR;
            default -> {
                if (args.length == 0) {
                    sender.sendMessage("Usage: /gm <survival|creative|adventure|spectator> [player]");
                    return true;
                }
                mode = parseGameMode(args[0]);
                playerArg = 1;
            }
        }
        if (mode == null) {
            sender.sendMessage("Unknown game mode.");
            return true;
        }
        final Player target = args.length > playerArg ? this.player(args[playerArg], sender) : this.self(sender);
        if (target == null) {
            return true;
        }
        target.setGameMode(mode);
        target.sendMessage("Game mode set to " + mode.name().toLowerCase(Locale.ROOT) + ".");
        if (!sender.equals(target)) {
            sender.sendMessage("Set " + target.getName() + " to " + mode.name().toLowerCase(Locale.ROOT) + ".");
        }
        return true;
    }

    private boolean time(final CommandSender sender, final String command, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, command)) {
            return true;
        }
        final World world = this.world(sender, args);
        if (world == null) {
            return true;
        }
        world.setTime(command.equals("day") ? 1000L : 13000L);
        sender.sendMessage("Set time in " + world.getName() + " to " + command + ".");
        return true;
    }

    private boolean weather(final CommandSender sender, final String command, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, command)) {
            return true;
        }
        final World world = this.world(sender, args);
        if (world == null) {
            return true;
        }
        world.setStorm(!command.equals("sun"));
        world.setThundering(command.equals("thunder"));
        world.setWeatherDuration(20 * 60 * 10);
        sender.sendMessage("Set weather in " + world.getName() + " to " + command + ".");
        return true;
    }

    private boolean broadcast(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "broadcast")) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /broadcast <message>");
            return true;
        }
        Bukkit.broadcast(Component.text("[HunterCore] ", NamedTextColor.GOLD).append(Component.text(String.join(" ", args), NamedTextColor.YELLOW)));
        return true;
    }

    private boolean clearChat(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "clearchat")) {
            return true;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            for (int i = 0; i < 80; i++) {
                player.sendMessage("");
            }
            player.sendMessage(ChatColor.GRAY + "Chat was cleared by " + sender.getName() + ".");
        }
        sender.sendMessage("Cleared chat for " + Bukkit.getOnlinePlayers().size() + " players.");
        return true;
    }

    private boolean speed(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "speed")) {
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("Usage: /speed <1-10> [player] [walk|fly]");
            return true;
        }
        final float speed;
        try {
            speed = Math.max(0.0F, Math.min(1.0F, Float.parseFloat(args[0]) / 10.0F));
        } catch (final NumberFormatException ex) {
            sender.sendMessage("Speed must be a number from 1 to 10.");
            return true;
        }
        final Player target = args.length > 1 ? this.player(args[1], sender) : this.self(sender);
        if (target == null) {
            return true;
        }
        final String type = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : (target.isFlying() ? "fly" : "walk");
        if (type.equals("fly")) {
            target.setFlySpeed(speed);
        } else {
            target.setWalkSpeed(speed);
        }
        sender.sendMessage("Set " + target.getName() + " " + type + " speed to " + args[0] + ".");
        return true;
    }

    private boolean spawn(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "spawn")) {
            return true;
        }
        final Player target = args.length > 0 ? this.player(args[0], sender) : this.self(sender);
        if (target == null) {
            return true;
        }
        final Location spawn = this.preferences.spawn();
        if (spawn == null) {
            sender.sendMessage("No valid spawn is configured.");
            return true;
        }
        target.teleportAsync(spawn).thenAccept(success -> {
            if (success) {
                target.sendMessage("Teleported to spawn.");
            }
        });
        return true;
    }

    private boolean setSpawn(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "setspawn")) {
            return true;
        }
        final Player player = this.self(sender);
        if (player == null) {
            return true;
        }
        this.preferences.setSpawn(player.getLocation());
        this.preferences.save(this.workerExecutor);
        sender.sendMessage("HunterCore spawn set to your location.");
        return true;
    }

    private boolean back(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "back")) {
            return true;
        }
        final Player player = this.self(sender);
        if (player == null) {
            return true;
        }
        final Location location = this.backLocations.get(player.getUniqueId());
        if (location == null) {
            player.sendMessage("No previous location is available.");
            return true;
        }
        player.teleportAsync(location).thenAccept(success -> {
            if (success) {
                player.sendMessage("Returned to your previous location.");
            }
        });
        return true;
    }

    private boolean hat(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "hat")) {
            return true;
        }
        final Player player = this.self(sender);
        if (player == null) {
            return true;
        }
        final ItemStack hand = player.getInventory().getItemInMainHand();
        final ItemStack helmet = player.getInventory().getHelmet();
        player.getInventory().setHelmet(isAir(hand) ? null : hand.clone());
        player.getInventory().setItemInMainHand(helmet == null ? new ItemStack(Material.AIR) : helmet.clone());
        player.updateInventory();
        player.sendMessage("Swapped your helmet and main hand item.");
        return true;
    }

    private boolean craft(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "craft")) {
            return true;
        }
        final Player player = this.self(sender);
        if (player == null) {
            return true;
        }
        player.openWorkbench(player.getLocation(), true);
        return true;
    }

    private boolean enderChest(final CommandSender sender, final String[] args) {
        if (!this.essentialsCommandEnabled(sender, "enderchest")) {
            return true;
        }
        final Player viewer = this.self(sender);
        if (viewer == null) {
            return true;
        }
        final Player owner;
        if (args.length == 0) {
            owner = viewer;
        } else {
            if (!sender.hasPermission("huntertools.command.enderchest.other")) {
                sender.sendMessage(Bukkit.permissionMessage());
                return true;
            }
            owner = this.player(args[0], sender);
            if (owner == null) {
                return true;
            }
        }
        viewer.openInventory(owner.getEnderChest());
        if (!viewer.equals(owner)) {
            viewer.sendMessage("Opened " + owner.getName() + "'s ender chest.");
        }
        return true;
    }

    private boolean trash(final CommandSender sender) {
        if (!this.essentialsCommandEnabled(sender, "trash")) {
            return true;
        }
        final Player player = this.self(sender);
        if (player == null) {
            return true;
        }
        final Inventory inventory = Bukkit.createInventory(null, 54, ChatColor.DARK_GRAY + "Trash");
        player.openInventory(inventory);
        player.sendMessage("Items left in this inventory will be discarded when it closes.");
        return true;
    }

    private boolean fakePlayer(final CommandSender sender, final String[] args) {
        if (!this.require(sender, "huntertools.command.fakeplayer")) {
            return true;
        }
        return this.actorManager != null && this.actorManager.fakePlayerCommand(sender, args);
    }

    private boolean realFakePlayer(final CommandSender sender, final String label, final String[] args) {
        if (!this.require(sender, "huntertools.command.hplayer")) {
            return true;
        }
        return this.realFakePlayerManager != null && this.realFakePlayerManager.command(sender, label, args);
    }

    private boolean npc(final CommandSender sender, final String[] args) {
        if (!this.require(sender, "huntertools.command.npc")) {
            return true;
        }
        return this.actorManager != null && this.actorManager.npcCommand(sender, args);
    }

    private boolean essentialsCommandEnabled(final CommandSender sender, final String command) {
        if (!this.preferences.moduleEnabled(ESSENTIALS)) {
            sender.sendMessage("HunterCore essentials module is disabled in preferences.yml.");
            return false;
        }
        if (!this.preferences.commandEnabled(ESSENTIALS, command)) {
            sender.sendMessage("HunterCore command " + command + " is disabled in preferences.yml.");
            return false;
        }
        return true;
    }

    private boolean managementCommandEnabled(final CommandSender sender, final String command) {
        if (!this.preferences.moduleEnabled(MANAGEMENT)) {
            sender.sendMessage("HunterCore management module is disabled in preferences.yml.");
            return false;
        }
        if (!this.preferences.commandEnabled(MANAGEMENT, command)) {
            sender.sendMessage("HunterCore management command " + command + " is disabled in preferences.yml.");
            return false;
        }
        return true;
    }

    private boolean require(final CommandSender sender, final String permission) {
        if (!sender.hasPermission(permission)) {
            sender.sendMessage(Bukkit.permissionMessage());
            return false;
        }
        return true;
    }

    private Player target(final CommandSender sender, final String[] args, final int index) {
        return args.length > index ? this.player(args[index], sender) : this.self(sender);
    }

    private Player self(final CommandSender sender) {
        if (sender instanceof final Player player) {
            return player;
        }
        sender.sendMessage("Only players can use this without a target.");
        return null;
    }

    private Player player(final String name, final CommandSender sender) {
        final Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            sender.sendMessage("Player not found: " + name);
            return null;
        }
        return player;
    }

    private World world(final CommandSender sender, final String[] args) {
        if (args.length > 0) {
            final World world = Bukkit.getWorld(args[0]);
            if (world == null) {
                sender.sendMessage("World not found: " + args[0]);
            }
            return world;
        }
        if (sender instanceof final Player player) {
            return player.getWorld();
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    private String renderMotdLine(final String line, final ServerListPingEvent event) {
        final MetricsSnapshot current = this.snapshot;
        return line
            .replace("%online%", Integer.toString(event.getNumPlayers()))
            .replace("%max%", Integer.toString(event.getMaxPlayers()))
            .replace("%tps%", MetricsSnapshot.formatTps(current.tps1()))
            .replace("%mspt%", String.format(Locale.ROOT, "%.1f", current.mspt()))
            .replace("%version%", Bukkit.getMinecraftVersion());
    }

    private static GameMode parseGameMode(final String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "0", "s", "survival" -> GameMode.SURVIVAL;
            case "1", "c", "creative" -> GameMode.CREATIVE;
            case "2", "a", "adventure" -> GameMode.ADVENTURE;
            case "3", "sp", "spectator" -> GameMode.SPECTATOR;
            default -> null;
        };
    }

    private static Boolean parseToggle(final String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "on", "enable", "enabled", "true", "yes" -> Boolean.TRUE;
            case "off", "disable", "disabled", "false", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static boolean validCpuMode(final String input) {
        if (input == null) {
            return false;
        }
        final String normalized = input.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.equals("single-thread")
            || normalized.equals("high-clock")
            || normalized.equals("high-core")
            || normalized.equals("multi-thread")
            || normalized.equals("single")
            || normalized.equals("multi")
            || normalized.equals("stable")
            || normalized.equals("performance")
            || normalized.equals("clock")
            || normalized.equals("core")
            || normalized.equals("balanced");
    }

    private static String normalizeCpuMode(final String input) {
        final String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (normalized) {
            case "high-clock", "clock" -> "high-clock";
            case "high-core", "core" -> "high-core";
            case "multi-thread", "multi", "performance" -> "multi-thread";
            default -> "single-thread";
        };
    }

    private List<String> adminCompletions(final String[] args) {
        if (args.length == 1) {
            return matching(args[0], List.of("reload", "modules", "module", "command", "plugins", "memory", "gc", "threads", "optimize", "motd", "web", "ai"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("motd")) {
            return matching(args[1], List.of("status", "line1", "line2", "max"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("optimize")) {
            return matching(args[1], List.of("status", "single-thread", "high-clock", "high-core", "multi-thread"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("motd") && args[1].equalsIgnoreCase("max")) {
            return matching(args[2], List.of("default", "100", "500", "1000"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("web")) {
            return matching(args[1], List.of("status", "restart", "bind", "address", "port", "map", "public-map", "user", "remove", "users", "allow", "execution"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("web") && (args[1].equalsIgnoreCase("bind") || args[1].equalsIgnoreCase("address"))) {
            return matching(args[2], List.of("127.0.0.1", "0.0.0.0"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("port")) {
            return matching(args[2], List.of("8088", "8090", "8100"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("map")) {
            return matching(args[2], List.of("http://%host%:8100/"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("public-map")) {
            return matching(args[2], List.of("on", "off"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("user")) {
            return matching(args[3], List.of("admin", "player"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("allow")) {
            return matching(args[3], List.of("inherit", "none", "*", "help", "list", "spawn", "tps", "htps"));
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("web") && args[1].equalsIgnoreCase("execution")) {
            return matching(args[3], List.of("on", "off"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ai")) {
            return matching(args[1], List.of("status", "enable", "disable", "model", "base-url", "key", "clear-key", "env", "prefix", "chat", "npc", "temperature", "max-tokens", "test"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ai") && (args[1].equalsIgnoreCase("chat") || args[1].equalsIgnoreCase("npc"))) {
            return matching(args[2], List.of("on", "off"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ai") && args[1].equalsIgnoreCase("model")) {
            return matching(args[2], List.of("gpt-4o-mini", "gpt-4.1-mini"));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("ai") && (args[1].equalsIgnoreCase("base-url") || args[1].equalsIgnoreCase("url"))) {
            return matching(args[2], List.of("https://api.openai.com/v1"));
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("module")) {
            return matching(args[1], MODULES);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("command")) {
            return matching(args[1], List.of(ESSENTIALS, MANAGEMENT, FAKE_PLAYERS, REAL_FAKE_PLAYERS, NPCS));
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("command")) {
            final String module = args[1].toLowerCase(Locale.ROOT);
            if (module.equals(ESSENTIALS)) {
                return matching(args[2], HunterToolsPreferences.essentialsCommands());
            }
            if (module.equals(MANAGEMENT)) {
                return matching(args[2], HunterToolsPreferences.managementCommands());
            }
            if (module.equals(FAKE_PLAYERS) || module.equals(NPCS)) {
                return matching(args[2], HunterToolsPreferences.actorCommands());
            }
            if (module.equals(REAL_FAKE_PLAYERS)) {
                return matching(args[2], HunterToolsPreferences.realFakePlayerCommands());
            }
        }
        if ((args.length == 3 && args[0].equalsIgnoreCase("module")) || (args.length == 4 && args[0].equalsIgnoreCase("command"))) {
            return matching(args[args.length - 1], List.of("on", "off"));
        }
        return List.of();
    }

    private List<String> onlinePlayerNames() {
        if (this.preferences.booleanValue("optimizations.enabled", true) && this.preferences.booleanValue("optimizations.hunter-tools.player-cache", true)) {
            return this.cachedPlayerNames;
        }
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }

    MetricsSnapshot metricsSnapshot() {
        return this.snapshot;
    }

    int actorLiveCount(final String module) {
        if (module.equals(REAL_FAKE_PLAYERS)) {
            return this.realFakePlayerManager == null ? 0 : this.realFakePlayerManager.liveCount();
        }
        return this.actorManager == null ? 0 : this.actorManager.liveCount(module);
    }

    List<HunterActorManager.ActorView> actorViews(final String module) {
        return this.actorManager == null ? List.of() : this.actorManager.views(module);
    }

    List<HunterRealFakePlayerManager.RealFakePlayerView> realFakePlayerViews() {
        return this.realFakePlayerManager == null ? List.of() : this.realFakePlayerManager.views();
    }

    List<HunterRealFakePlayerManager.PendingRiskApprovalView> pendingRiskApprovalViews() {
        return this.realFakePlayerManager == null ? List.of() : this.realFakePlayerManager.pendingApprovalViews();
    }

    boolean setActorClickCommand(final String module, final String id, final String command) {
        if (module.equals(REAL_FAKE_PLAYERS)) {
            return this.realFakePlayerManager != null && this.realFakePlayerManager.setClickCommand(id, command);
        }
        return this.actorManager != null && this.actorManager.setClickCommand(module, id, command);
    }

    boolean setActorAi(final String module, final String id, final boolean enabled, final String persona) {
        if (module.equals(REAL_FAKE_PLAYERS)) {
            return this.realFakePlayerManager != null && this.realFakePlayerManager.setAi(id, enabled, persona);
        }
        return this.actorManager != null && this.actorManager.setActorAi(module, id, enabled, persona);
    }

    CompletableFuture<String> testAiPrompt(final String prompt) {
        return this.aiManager == null
            ? CompletableFuture.failedFuture(new IllegalStateException("HunterCore AI manager is not available."))
            : this.aiManager.completeTest(prompt);
    }

    boolean aiApiKeyConfigured() {
        return this.aiManager != null && this.aiManager.apiKeyConfigured();
    }

    private void sendCommandOverride(final Player player, final String target) {
        final List<String> lines = this.preferences.stringList(
            "modules.command-overrides.messages." + target,
            HunterToolsPreferences.defaultCommandOverrideLines(target)
        );
        for (final String line : lines) {
            player.sendMessage(color(this.renderCommandOverrideLine(line, player)));
        }
    }

    private String renderCommandOverrideLine(final String line, final Player player) {
        final String pluginCount = String.valueOf(Bukkit.getPluginManager().getPlugins().length);
        return (line == null ? "" : line)
            .replace("%player%", player.getName())
            .replace("%player_uuid%", player.getUniqueId().toString())
            .replace("%online%", String.valueOf(Bukkit.getOnlinePlayers().size()))
            .replace("%max%", String.valueOf(Bukkit.getMaxPlayers()))
            .replace("%server%", this.preferences.stringValue("modules.web-panel.server-name", "HunterCore"))
            .replace("%version%", Bukkit.getVersion())
            .replace("%plugins%", pluginCount)
            .replace("%plugin_count%", pluginCount);
    }

    private boolean canUseOp(final Player player) {
        return player.isOp() || player.hasPermission("minecraft.command.op") || player.hasPermission("bukkit.command.op");
    }

    private static List<String> matching(final String prefix, final Collection<String> values) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }
        return matches;
    }

    private static String webAllowedLine(final HunterToolsPreferences.WebUser user) {
        if (!user.allowedCommandsConfigured()) {
            return "inherit";
        }
        if (user.allowedCommands().isEmpty()) {
            return "none";
        }
        return String.join(",", user.allowedCommands());
    }

    private static String normalizeWebCommand(final String command) {
        return command.replaceFirst("^/+", "").trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static String commandRoot(final String message) {
        final String root = normalizeWebCommand(message);
        final int namespace = root.indexOf(':');
        return namespace >= 0 && namespace + 1 < root.length() ? root.substring(namespace + 1) : root;
    }

    private static boolean isAir(final ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static String color(final String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private static NamedTextColor tpsColor(final double tps) {
        if (tps >= 18.0D) {
            return NamedTextColor.GREEN;
        }
        if (tps >= 15.0D) {
            return NamedTextColor.YELLOW;
        }
        return NamedTextColor.RED;
    }

    private static ChatColor colorCode(final double tps) {
        if (tps >= 18.0D) {
            return ChatColor.GREEN;
        }
        if (tps >= 15.0D) {
            return ChatColor.YELLOW;
        }
        return ChatColor.RED;
    }

    private record PlayerView(UUID uuid, String name, String world, int ping) {
    }

    private static final class SidebarBoard {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private List<String> entries;

        private SidebarBoard(final Scoreboard scoreboard, final Objective objective, final List<String> entries) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.entries = entries;
        }
    }
}
