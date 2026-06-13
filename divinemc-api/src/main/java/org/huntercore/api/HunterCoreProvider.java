package org.huntercore.api;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.huntercore.api.fakeplayer.FakePlayerActionResult;
import org.huntercore.api.fakeplayer.FakePlayerSnapshot;
import org.huntercore.api.fakeplayer.HunterFakePlayerService;
import org.jetbrains.annotations.NotNull;

public final class HunterCoreProvider {
    private static HunterCoreApi api = new UnavailableHunterCoreApi();

    private HunterCoreProvider() {
    }

    public static @NotNull HunterCoreApi get() {
        return api;
    }

    public static void register(@NotNull final HunterCoreApi api) {
        HunterCoreProvider.api = api;
    }

    private static final class UnavailableHunterCoreApi implements HunterCoreApi {
        @Override
        public @NotNull String name() {
            return "HunterCore";
        }

        @Override
        public @NotNull String version() {
            return "unavailable";
        }

        @Override
        public @NotNull Collection<HunterBundledPlugin> bundledPlugins() {
            return Collections.emptyList();
        }

        @Override
        public @NotNull HunterFakePlayerService fakePlayers() {
            return UnavailableHunterFakePlayerService.INSTANCE;
        }

        @Override
        public void registerCommandExtension(@NotNull final HunterCommandExtension extension) {
            throw new IllegalStateException("HunterCore API is not available yet");
        }

        @Override
        public @NotNull Collection<HunterCommandExtension> commandExtensions() {
            return Collections.emptyList();
        }
    }

    private enum UnavailableHunterFakePlayerService implements HunterFakePlayerService {
        INSTANCE;

        @Override
        public boolean available() {
            return false;
        }

        @Override
        public @NotNull Collection<FakePlayerSnapshot> list() {
            return Collections.emptyList();
        }

        @Override
        public @NotNull Optional<FakePlayerSnapshot> snapshot(@NotNull final String name) {
            return Optional.empty();
        }

        @Override
        public @NotNull FakePlayerActionResult spawn(@NotNull final String name, @NotNull final Location location) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult remove(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult removeAll() {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult teleport(@NotNull final String name, @NotNull final Location location) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult look(@NotNull final String name, final float yaw, final float pitch) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult setSneaking(@NotNull final String name, final boolean sneaking) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult setSprinting(@NotNull final String name, final boolean sprinting) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult move(
            @NotNull final String name,
            final double forward,
            final double sideways,
            final boolean jump,
            final boolean sprinting,
            final boolean sneaking
        ) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult jump(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult use(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult attack(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult stopActions(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult dropSelected(@NotNull final String name, final boolean stack) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult swapHands(@NotNull final String name) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult setGameMode(@NotNull final String name, @NotNull final GameMode gameMode) {
            return unavailable();
        }

        @Override
        public @NotNull FakePlayerActionResult setSelectedSlot(@NotNull final String name, final int slot) {
            return unavailable();
        }

        private static @NotNull FakePlayerActionResult unavailable() {
            return FakePlayerActionResult.fail("HunterCore API is not available yet.");
        }
    }
}
