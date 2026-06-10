package org.huntercore.api.fakeplayer;

import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

public record FakePlayerSnapshot(
    @NotNull String id,
    @NotNull String name,
    @NotNull UUID uuid,
    @NotNull Location location,
    @NotNull GameMode gameMode,
    boolean sneaking,
    boolean sprinting,
    boolean usingItem
) {
}
