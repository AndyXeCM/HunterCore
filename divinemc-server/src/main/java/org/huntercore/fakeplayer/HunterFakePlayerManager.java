package org.huntercore.fakeplayer;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.huntercore.api.fakeplayer.FakePlayerActionResult;
import org.huntercore.api.fakeplayer.FakePlayerSnapshot;
import org.huntercore.api.fakeplayer.HunterFakePlayerService;
import org.jetbrains.annotations.NotNull;
import org.jspecify.annotations.Nullable;

public final class HunterFakePlayerManager implements HunterFakePlayerService {
    private static final String SCOREBOARD_TAG = "huntercore_real_fake_player";
    private static final String UUID_PREFIX = "huntercore:real-fake-player:";

    private final Map<String, FakeEntry> players = new LinkedHashMap<>();
    private final Map<String, MiningState> mining = new LinkedHashMap<>();
    private int sequence;

    @Override
    public boolean available() {
        return MinecraftServer.getServer() != null;
    }

    @Override
    public @NotNull Collection<FakePlayerSnapshot> list() {
        return this.sync(() -> {
            this.prune();
            final Collection<FakePlayerSnapshot> snapshots = new ArrayList<>();
            for (final FakeEntry entry : this.players.values()) {
                final ServerPlayer player = this.server().getPlayerList().getPlayer(entry.uuid());
                if (player != null && !player.hasDisconnected()) {
                    snapshots.add(snapshot(entry.id(), player));
                }
            }
            return snapshots;
        });
    }

    @Override
    public @NotNull Optional<FakePlayerSnapshot> snapshot(@NotNull final String name) {
        return this.sync(() -> Optional.ofNullable(this.player(name)).map(player -> snapshot(id(name), player)));
    }

    @Override
    public @NotNull FakePlayerActionResult spawn(@NotNull final String name, @NotNull final Location location) {
        return this.sync(() -> {
            final String profileName = validProfileName(name);
            if (profileName == null) {
                return FakePlayerActionResult.fail("Fake player names must be 1-16 characters: letters, numbers, and underscore.");
            }
            if (location.getWorld() == null) {
                return FakePlayerActionResult.fail("Location has no world.");
            }
            final MinecraftServer server = this.server();
            final String id = id(profileName);
            this.prune();
            if (this.players.containsKey(id)) {
                return FakePlayerActionResult.fail("Fake player already exists: " + profileName);
            }
            if (server.getPlayerList().getPlayerByName(profileName) != null) {
                return FakePlayerActionResult.fail("A player with that name is already online: " + profileName);
            }

            final ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
            final UUID uuid = UUID.nameUUIDFromBytes((UUID_PREFIX + id).getBytes(StandardCharsets.UTF_8));
            final GameProfile profile = new GameProfile(uuid, profileName);
            final ServerPlayer player = new ServerPlayer(server, level, profile, net.minecraft.server.level.ClientInformation.createDefault());
            player.snapTo(location.getX(), location.getY(), location.getZ(), location.getYaw(), location.getPitch());
            player.getBukkitEntity().setPersistent(false);
            player.getBukkitEntity().addScoreboardTag(SCOREBOARD_TAG);

            final HunterFakeConnection connection = new HunterFakeConnection();
            final CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            server.getPlayerList().placeNewPlayer(connection, player, cookie);

            if (player.hasDisconnected() || server.getPlayerList().getPlayer(uuid) != player) {
                return FakePlayerActionResult.fail("Fake player join was rejected by the server or a plugin: " + profileName);
            }
            player.getBukkitEntity().setPersistent(false);
            player.getBukkitEntity().addScoreboardTag(SCOREBOARD_TAG);
            this.players.put(id, new FakeEntry(id, profileName, uuid));
            return FakePlayerActionResult.ok("Spawned real fake player " + profileName + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult remove(@NotNull final String name) {
        return this.sync(() -> {
            final String id = id(name);
            final ServerPlayer player = this.player(id);
            this.players.remove(id);
            this.mining.remove(id);
            if (player == null) {
                return FakePlayerActionResult.fail("Fake player not found: " + name);
            }
            player.getBukkitEntity().setPersistent(false);
            this.server().getPlayerList().remove(player, net.kyori.adventure.text.Component.text("HunterCore fake player removed"));
            return FakePlayerActionResult.ok("Removed real fake player " + player.getGameProfile().name() + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult removeAll() {
        return this.sync(() -> {
            int removed = 0;
            for (final String id : new ArrayList<>(this.players.keySet())) {
                final ServerPlayer player = this.player(id);
                this.players.remove(id);
                this.mining.remove(id);
                if (player != null) {
                    player.getBukkitEntity().setPersistent(false);
                    this.server().getPlayerList().remove(player, net.kyori.adventure.text.Component.text("HunterCore fake player removed"));
                    removed++;
                }
            }
            return FakePlayerActionResult.ok("Removed " + removed + " real fake player(s).");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult teleport(@NotNull final String name, @NotNull final Location location) {
        return this.withPlayer(name, player -> {
            if (location.getWorld() == null) {
                return FakePlayerActionResult.fail("Location has no world.");
            }
            this.abortMining(id(name), player);
            final boolean teleported = player.getBukkitEntity().teleport(location);
            return teleported
                ? FakePlayerActionResult.ok("Teleported " + player.getGameProfile().name() + ".")
                : FakePlayerActionResult.fail("Teleport failed for " + player.getGameProfile().name() + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult look(@NotNull final String name, final float yaw, final float pitch) {
        return this.withPlayer(name, player -> {
            player.getBukkitEntity().setRotation(yaw, clampPitch(pitch));
            return FakePlayerActionResult.ok("Rotated " + player.getGameProfile().name() + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult setSneaking(@NotNull final String name, final boolean sneaking) {
        return this.withPlayer(name, player -> {
            player.setShiftKeyDown(sneaking);
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " sneaking=" + sneaking + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult setSprinting(@NotNull final String name, final boolean sprinting) {
        return this.withPlayer(name, player -> {
            player.setSprinting(sprinting);
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " sprinting=" + sprinting + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult jump(@NotNull final String name) {
        return this.withPlayer(name, player -> {
            if (player.onGround()) {
                player.jumpFromGround();
                return FakePlayerActionResult.ok(player.getGameProfile().name() + " jumped.");
            }
            return FakePlayerActionResult.fail(player.getGameProfile().name() + " is not on the ground.");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult use(@NotNull final String name) {
        return this.withPlayer(name, player -> {
            this.abortMining(id(name), player);
            final RayTarget target = this.rayTarget(player);
            final InteractionHand hand = InteractionHand.MAIN_HAND;
            final InteractionResult result;
            if (target.entityHit() != null) {
                final Entity entity = target.entityHit().getEntity();
                final Vec3 relative = target.entityHit().getLocation().subtract(entity.position());
                result = entity.interact(player, hand, relative);
            } else if (target.blockHit().getType() == HitResult.Type.BLOCK) {
                result = player.gameMode.useItemOn(player, player.level(), player.getItemInHand(hand), hand, target.blockHit());
            } else {
                final ItemStack item = player.getItemInHand(hand);
                result = player.gameMode.useItem(player, player.level(), item, hand);
            }
            if (result.consumesAction()) {
                player.swing(hand, true);
            }
            player.inventoryMenu.broadcastChanges();
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " used main hand (" + result + ").");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult attack(@NotNull final String name) {
        return this.withPlayer(name, player -> {
            final String id = id(name);
            final RayTarget target = this.rayTarget(player);
            if (target.entityHit() != null) {
                this.abortMining(id, player);
                player.attack(target.entityHit().getEntity());
                player.swing(InteractionHand.MAIN_HAND, true);
                return FakePlayerActionResult.ok(player.getGameProfile().name() + " attacked entity " + target.entityHit().getEntity().getScoreboardName() + ".");
            }
            if (target.blockHit().getType() != HitResult.Type.BLOCK) {
                this.abortMining(id, player);
                player.swing(InteractionHand.MAIN_HAND, true);
                return FakePlayerActionResult.ok(player.getGameProfile().name() + " swung at air.");
            }
            final BlockHitResult blockHit = target.blockHit();
            final BlockPos pos = blockHit.getBlockPos();
            if (player.level().getBlockState(pos).isAir()) {
                this.abortMining(id, player);
                player.swing(InteractionHand.MAIN_HAND, true);
                return FakePlayerActionResult.ok(player.getGameProfile().name() + " swung at air.");
            }
            final MiningState current = this.mining.get(id);
            if (current != null && current.pos().equals(pos)) {
                return FakePlayerActionResult.ok(player.getGameProfile().name() + " is mining " + blockLine(pos) + ".");
            }
            this.abortMining(id, player);
            player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, blockHit.getDirection(), player.level().getMaxY(), this.nextSequence());
            this.mining.put(id, new MiningState(pos.immutable(), blockHit.getDirection()));
            player.swing(InteractionHand.MAIN_HAND, true);
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " started mining " + blockLine(pos) + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult stopActions(@NotNull final String name) {
        return this.withPlayer(name, player -> {
            this.abortMining(id(name), player);
            player.releaseUsingItem();
            player.stopUsingItem();
            player.setShiftKeyDown(false);
            player.setSprinting(false);
            return FakePlayerActionResult.ok("Stopped actions for " + player.getGameProfile().name() + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult dropSelected(@NotNull final String name, final boolean stack) {
        return this.withPlayer(name, player -> {
            final boolean dropped = player.drop(stack);
            player.inventoryMenu.broadcastChanges();
            return dropped
                ? FakePlayerActionResult.ok(player.getGameProfile().name() + " dropped selected " + (stack ? "stack" : "item") + ".")
                : FakePlayerActionResult.fail(player.getGameProfile().name() + " has nothing to drop.");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult swapHands(@NotNull final String name) {
        return this.withPlayer(name, player -> {
            final ItemStack main = player.getItemInHand(InteractionHand.MAIN_HAND);
            final ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
            player.setItemInHand(InteractionHand.MAIN_HAND, off);
            player.setItemInHand(InteractionHand.OFF_HAND, main);
            player.stopUsingItem();
            player.inventoryMenu.broadcastChanges();
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " swapped hands.");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult setGameMode(@NotNull final String name, @NotNull final GameMode gameMode) {
        return this.withPlayer(name, player -> {
            player.setGameMode(GameType.byId(gameMode.getValue()));
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " game mode set to " + gameMode.name().toLowerCase(Locale.ROOT) + ".");
        });
    }

    @Override
    public @NotNull FakePlayerActionResult setSelectedSlot(@NotNull final String name, final int slot) {
        return this.withPlayer(name, player -> {
            if (slot < 1 || slot > 9) {
                return FakePlayerActionResult.fail("Hotbar slot must be 1-9.");
            }
            player.getInventory().setSelectedSlot(slot - 1);
            player.inventoryMenu.broadcastChanges();
            return FakePlayerActionResult.ok(player.getGameProfile().name() + " selected hotbar slot " + slot + ".");
        });
    }

    private @NotNull FakePlayerActionResult withPlayer(final String name, final PlayerOperation operation) {
        return this.sync(() -> {
            final ServerPlayer player = this.player(name);
            if (player == null) {
                return FakePlayerActionResult.fail("Fake player not found: " + name);
            }
            return operation.apply(player);
        });
    }

    private @Nullable ServerPlayer player(final String name) {
        final String id = id(name);
        final FakeEntry entry = this.players.get(id);
        if (entry == null) {
            return null;
        }
        final ServerPlayer player = this.server().getPlayerList().getPlayer(entry.uuid());
        if (player == null || player.hasDisconnected()) {
            this.players.remove(id);
            this.mining.remove(id);
            return null;
        }
        return player;
    }

    private void prune() {
        for (final String id : new ArrayList<>(this.players.keySet())) {
            final FakeEntry entry = this.players.get(id);
            final ServerPlayer player = entry == null ? null : this.server().getPlayerList().getPlayer(entry.uuid());
            if (player == null || player.hasDisconnected()) {
                this.players.remove(id);
                this.mining.remove(id);
            }
        }
    }

    private void abortMining(final String id, final ServerPlayer player) {
        final MiningState state = this.mining.remove(id);
        if (state != null) {
            player.gameMode.handleBlockBreakAction(state.pos(), ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK, state.direction(), player.level().getMaxY(), this.nextSequence());
        }
    }

    private RayTarget rayTarget(final ServerPlayer player) {
        final double blockRange = player.blockInteractionRange();
        final double entityRange = player.entityInteractionRange();
        final Vec3 from = player.getEyePosition();
        final Vec3 look = player.getViewVector(1.0F);
        final Vec3 blockTo = from.add(look.scale(blockRange));
        final BlockHitResult blockHit = player.level().clip(new ClipContext(from, blockTo, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        final double blockDistance = blockHit.getType() == HitResult.Type.MISS ? blockRange * blockRange : from.distanceToSqr(blockHit.getLocation());

        final Vec3 entityTo = from.add(look.scale(entityRange));
        final AABB searchBox = player.getBoundingBox().expandTowards(look.scale(entityRange)).inflate(1.0D);
        final EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
            player.level(),
            player,
            from,
            entityTo,
            searchBox,
            entity -> entity != player && !entity.isSpectator() && entity.isPickable() && !entity.isPassengerOfSameVehicle(player),
            0.0F
        );
        if (entityHit != null && from.distanceToSqr(entityHit.getLocation()) <= Math.min(entityRange * entityRange, blockDistance)) {
            return new RayTarget(blockHit, entityHit);
        }
        return new RayTarget(blockHit, null);
    }

    private int nextSequence() {
        return ++this.sequence;
    }

    private MinecraftServer server() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) {
            throw new IllegalStateException("MinecraftServer is not available");
        }
        return server;
    }

    private <T> T sync(final Supplier<T> supplier) {
        final MinecraftServer server = this.server();
        if (server.isSameThread()) {
            return supplier.get();
        }
        final CompletableFuture<T> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (final Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future.join();
    }

    private static FakePlayerSnapshot snapshot(final String id, final ServerPlayer player) {
        return new FakePlayerSnapshot(
            id,
            player.getGameProfile().name(),
            player.getUUID(),
            player.getBukkitEntity().getLocation(),
            player.getBukkitEntity().getGameMode(),
            player.isShiftKeyDown(),
            player.isSprinting(),
            player.isUsingItem()
        );
    }

    private static @Nullable String validProfileName(final String name) {
        final String trimmed = name.trim();
        if (trimmed.length() < 1 || trimmed.length() > 16) {
            return null;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            final char c = trimmed.charAt(i);
            if (!(c == '_' || c >= '0' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')) {
                return null;
            }
        }
        return trimmed;
    }

    private static String id(final String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static float clampPitch(final float pitch) {
        return Math.max(-90.0F, Math.min(90.0F, pitch));
    }

    private static String blockLine(final BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private interface PlayerOperation {
        FakePlayerActionResult apply(ServerPlayer player);
    }

    private record FakeEntry(String id, String name, UUID uuid) {
    }

    private record MiningState(BlockPos pos, Direction direction) {
    }

    private record RayTarget(BlockHitResult blockHit, @Nullable EntityHitResult entityHit) {
    }
}
