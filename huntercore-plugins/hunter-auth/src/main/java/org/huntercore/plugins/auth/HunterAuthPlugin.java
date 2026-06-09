package org.huntercore.plugins.auth;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

public final class HunterAuthPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final int SALT_BYTES = 16;
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final Set<String> ALLOWED_COMMANDS = Set.of("/login", "/l", "/register", "/reg");

    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> authenticated = new HashSet<>();
    private File usersFile;
    private YamlConfiguration users;

    @Override
    public void onEnable() {
        this.getConfig().addDefault("online-mode-bypass", true);
        this.getConfig().addDefault("minimum-password-length", 4);
        this.getConfig().options().copyDefaults(true);
        this.saveConfig();

        this.getDataFolder().mkdirs();
        this.usersFile = new File(this.getDataFolder(), "users.yml");
        this.users = YamlConfiguration.loadConfiguration(this.usersFile);

        for (final String command : List.of("register", "login", "logout", "changepassword")) {
            final org.bukkit.command.PluginCommand pluginCommand = this.getCommand(command);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
            }
        }
        this.getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(
        @NotNull final CommandSender sender,
        @NotNull final Command command,
        @NotNull final String label,
        @NotNull final String[] args
    ) {
        if (!(sender instanceof final Player player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        return switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "register" -> this.register(player, args);
            case "login" -> this.login(player, args);
            case "logout" -> this.logout(player);
            case "changepassword" -> this.changePassword(player, args);
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        if (this.shouldBypass()) {
            this.authenticated.add(player.getUniqueId());
            return;
        }
        if (this.isRegistered(player)) {
            player.sendMessage("Please log in with /login <password>.");
        } else {
            player.sendMessage("Please register with /register <password> <password>.");
        }
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.authenticated.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(final PlayerMoveEvent event) {
        if (this.isAuthenticated(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
            || event.getFrom().getBlockY() != event.getTo().getBlockY()
            || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onChat(final AsyncPlayerChatEvent event) {
        if (!this.isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Please log in before chatting.");
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(final PlayerCommandPreprocessEvent event) {
        if (this.isAuthenticated(event.getPlayer())) {
            return;
        }
        final String lower = event.getMessage().toLowerCase(Locale.ROOT);
        final String root = lower.split(" ", 2)[0];
        if (!ALLOWED_COMMANDS.contains(root)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("Please log in before using commands.");
        }
    }

    private boolean register(final Player player, final String[] args) {
        if (this.shouldBypass()) {
            player.sendMessage("HunterAuth is bypassed while the server is in online mode.");
            return true;
        }
        if (this.isRegistered(player)) {
            player.sendMessage("You are already registered. Use /login or /changepassword.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage("Usage: /register <password> <password>");
            return true;
        }
        if (!args[0].equals(args[1])) {
            player.sendMessage("Passwords do not match.");
            return true;
        }
        if (args[0].length() < this.getConfig().getInt("minimum-password-length", 4)) {
            player.sendMessage("Password is too short.");
            return true;
        }

        this.setPassword(player, args[0]);
        this.authenticated.add(player.getUniqueId());
        player.sendMessage("Registered and logged in.");
        return true;
    }

    private boolean login(final Player player, final String[] args) {
        if (this.shouldBypass()) {
            player.sendMessage("HunterAuth is bypassed while the server is in online mode.");
            return true;
        }
        if (!this.isRegistered(player)) {
            player.sendMessage("You are not registered. Use /register <password> <password>.");
            return true;
        }
        if (args.length != 1) {
            player.sendMessage("Usage: /login <password>");
            return true;
        }
        if (!this.verifyPassword(player, args[0])) {
            player.sendMessage("Incorrect password.");
            return true;
        }
        this.authenticated.add(player.getUniqueId());
        player.sendMessage("Logged in.");
        return true;
    }

    private boolean logout(final Player player) {
        if (this.shouldBypass()) {
            player.sendMessage("HunterAuth is bypassed while the server is in online mode.");
            return true;
        }
        this.authenticated.remove(player.getUniqueId());
        player.sendMessage("Logged out.");
        return true;
    }

    private boolean changePassword(final Player player, final String[] args) {
        if (this.shouldBypass()) {
            player.sendMessage("HunterAuth is bypassed while the server is in online mode.");
            return true;
        }
        if (!this.isRegistered(player)) {
            player.sendMessage("You are not registered.");
            return true;
        }
        if (args.length != 2) {
            player.sendMessage("Usage: /changepassword <old> <new>");
            return true;
        }
        if (!this.verifyPassword(player, args[0])) {
            player.sendMessage("Incorrect old password.");
            return true;
        }
        if (args[1].length() < this.getConfig().getInt("minimum-password-length", 4)) {
            player.sendMessage("New password is too short.");
            return true;
        }
        this.setPassword(player, args[1]);
        this.authenticated.add(player.getUniqueId());
        player.sendMessage("Password changed.");
        return true;
    }

    private boolean shouldBypass() {
        return Bukkit.getOnlineMode() && this.getConfig().getBoolean("online-mode-bypass", true);
    }

    private boolean isAuthenticated(final Player player) {
        return this.shouldBypass() || this.authenticated.contains(player.getUniqueId());
    }

    private boolean isRegistered(final Player player) {
        return this.users.contains(path(player) + ".hash");
    }

    private boolean verifyPassword(final Player player, final String password) {
        final String path = path(player);
        final String salt = this.users.getString(path + ".salt");
        final String expected = this.users.getString(path + ".hash");
        if (salt == null || expected == null) {
            return false;
        }
        return expected.equals(hash(password, Base64.getDecoder().decode(salt)));
    }

    private void setPassword(final Player player, final String password) {
        final byte[] salt = new byte[SALT_BYTES];
        this.random.nextBytes(salt);
        final String path = path(player);
        this.users.set(path + ".name", player.getName());
        this.users.set(path + ".salt", Base64.getEncoder().encodeToString(salt));
        this.users.set(path + ".hash", hash(password, salt));
        this.saveUsers();
    }

    private void saveUsers() {
        try {
            this.users.save(this.usersFile);
        } catch (final IOException ex) {
            this.getLogger().severe("Failed to save users.yml: " + ex.getMessage());
        }
    }

    private static String path(final Player player) {
        return "users." + player.getUniqueId();
    }

    private static String hash(final String password, final byte[] salt) {
        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            final KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
            return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
        } catch (final NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("Unable to hash password", ex);
        }
    }
}
