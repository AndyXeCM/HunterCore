package org.huntercore.plugins.tpa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class HunterTpaPlugin extends JavaPlugin implements CommandExecutor, TabCompleter {
    private static final long REQUEST_TTL_MILLIS = 60_000L;

    private final Map<UUID, TeleportRequest> incoming = new HashMap<>();
    private final Map<UUID, UUID> outgoing = new HashMap<>();

    @Override
    public void onEnable() {
        for (final String command : List.of("tpa", "tpaccept", "tpdeny", "tpcancel")) {
            final org.bukkit.command.PluginCommand pluginCommand = this.getCommand(command);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(this);
                pluginCommand.setTabCompleter(this);
            }
        }
        this.getServer().getScheduler().runTaskTimer(this, this::expireRequests, 20L * 10L, 20L * 10L);
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
            case "tpa" -> this.requestTeleport(player, args);
            case "tpaccept" -> this.answerRequest(player, args, true);
            case "tpdeny" -> this.answerRequest(player, args, false);
            case "tpcancel" -> this.cancelRequest(player);
            default -> false;
        };
    }

    private boolean requestTeleport(final Player requester, final String[] args) {
        if (args.length != 1) {
            requester.sendMessage("Usage: /tpa <player>");
            return true;
        }

        final Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null || !target.isOnline()) {
            requester.sendMessage("That player is not online.");
            return true;
        }
        if (target.getUniqueId().equals(requester.getUniqueId())) {
            requester.sendMessage("You cannot send a teleport request to yourself.");
            return true;
        }

        this.removeOutgoing(requester.getUniqueId());
        final TeleportRequest oldIncoming = this.incoming.remove(target.getUniqueId());
        if (oldIncoming != null) {
            this.outgoing.remove(oldIncoming.requester());
        }

        final TeleportRequest request = new TeleportRequest(requester.getUniqueId(), target.getUniqueId(), System.currentTimeMillis() + REQUEST_TTL_MILLIS);
        this.incoming.put(target.getUniqueId(), request);
        this.outgoing.put(requester.getUniqueId(), target.getUniqueId());

        requester.sendMessage("Teleport request sent to " + target.getName() + ".");
        target.sendMessage(requester.getName() + " wants to teleport to you.");
        target.sendMessage("Use /tpaccept " + requester.getName() + " or /tpdeny " + requester.getName() + ".");
        return true;
    }

    private boolean answerRequest(final Player target, final String[] args, final boolean accept) {
        TeleportRequest request = this.incoming.get(target.getUniqueId());
        if (args.length == 1) {
            final Player requester = Bukkit.getPlayerExact(args[0]);
            if (requester == null || request == null || !request.requester().equals(requester.getUniqueId())) {
                request = null;
            }
        }

        if (request == null || request.isExpired()) {
            this.incoming.remove(target.getUniqueId());
            target.sendMessage("You do not have a pending teleport request.");
            return true;
        }

        this.incoming.remove(target.getUniqueId());
        this.outgoing.remove(request.requester());

        final Player requester = Bukkit.getPlayer(request.requester());
        if (requester == null || !requester.isOnline()) {
            target.sendMessage("The requesting player is no longer online.");
            return true;
        }

        if (!accept) {
            target.sendMessage("Teleport request denied.");
            requester.sendMessage(target.getName() + " denied your teleport request.");
            return true;
        }

        requester.teleportAsync(target.getLocation()).thenAccept(success -> {
            if (success) {
                requester.sendMessage("Teleported to " + target.getName() + ".");
                target.sendMessage("Accepted teleport request from " + requester.getName() + ".");
            } else {
                requester.sendMessage("Teleport failed.");
            }
        });
        return true;
    }

    private boolean cancelRequest(final Player requester) {
        final UUID targetId = this.outgoing.remove(requester.getUniqueId());
        if (targetId == null) {
            requester.sendMessage("You do not have an outgoing teleport request.");
            return true;
        }
        this.incoming.remove(targetId);
        final Player target = Bukkit.getPlayer(targetId);
        requester.sendMessage("Teleport request cancelled.");
        if (target != null) {
            target.sendMessage(requester.getName() + " cancelled their teleport request.");
        }
        return true;
    }

    private void removeOutgoing(final UUID requesterId) {
        final UUID oldTarget = this.outgoing.remove(requesterId);
        if (oldTarget != null) {
            this.incoming.remove(oldTarget);
        }
    }

    private void expireRequests() {
        final long now = System.currentTimeMillis();
        final List<TeleportRequest> expired = this.incoming.values().stream()
            .filter(request -> request.expiresAt() <= now)
            .toList();
        for (final TeleportRequest request : expired) {
            this.incoming.remove(request.target());
            this.outgoing.remove(request.requester());
            final Player requester = Bukkit.getPlayer(request.requester());
            if (requester != null) {
                requester.sendMessage("Your teleport request expired.");
            }
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(
        @NotNull final CommandSender sender,
        @NotNull final Command command,
        @NotNull final String alias,
        @NotNull final String[] args
    ) {
        if (args.length != 1 || !(sender instanceof Player)) {
            return List.of();
        }
        final String prefix = args[0].toLowerCase(Locale.ROOT);
        final List<String> matches = new ArrayList<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                matches.add(player.getName());
            }
        }
        return matches;
    }

    private record TeleportRequest(UUID requester, UUID target, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() >= this.expiresAt;
        }
    }
}
