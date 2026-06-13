package org.huntercore.api.fakeplayer;

import java.util.Collection;
import java.util.Optional;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

public interface HunterFakePlayerService {

    boolean available();

    @NotNull Collection<FakePlayerSnapshot> list();

    @NotNull Optional<FakePlayerSnapshot> snapshot(@NotNull String name);

    @NotNull FakePlayerActionResult spawn(@NotNull String name, @NotNull Location location);

    @NotNull FakePlayerActionResult remove(@NotNull String name);

    @NotNull FakePlayerActionResult removeAll();

    @NotNull FakePlayerActionResult teleport(@NotNull String name, @NotNull Location location);

    @NotNull FakePlayerActionResult look(@NotNull String name, float yaw, float pitch);

    @NotNull FakePlayerActionResult setSneaking(@NotNull String name, boolean sneaking);

    @NotNull FakePlayerActionResult setSprinting(@NotNull String name, boolean sprinting);

    @NotNull FakePlayerActionResult move(@NotNull String name, double forward, double sideways, boolean jump, boolean sprinting, boolean sneaking);

    @NotNull FakePlayerActionResult jump(@NotNull String name);

    @NotNull FakePlayerActionResult use(@NotNull String name);

    @NotNull FakePlayerActionResult attack(@NotNull String name);

    @NotNull FakePlayerActionResult stopActions(@NotNull String name);

    @NotNull FakePlayerActionResult dropSelected(@NotNull String name, boolean stack);

    @NotNull FakePlayerActionResult swapHands(@NotNull String name);

    @NotNull FakePlayerActionResult setGameMode(@NotNull String name, @NotNull GameMode gameMode);

    @NotNull FakePlayerActionResult setSelectedSlot(@NotNull String name, int slot);
}
