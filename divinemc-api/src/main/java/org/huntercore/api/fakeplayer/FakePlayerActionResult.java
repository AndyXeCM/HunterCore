package org.huntercore.api.fakeplayer;

import org.jetbrains.annotations.NotNull;

public record FakePlayerActionResult(boolean success, @NotNull String message) {

    public static @NotNull FakePlayerActionResult ok(@NotNull final String message) {
        return new FakePlayerActionResult(true, message);
    }

    public static @NotNull FakePlayerActionResult fail(@NotNull final String message) {
        return new FakePlayerActionResult(false, message);
    }
}
