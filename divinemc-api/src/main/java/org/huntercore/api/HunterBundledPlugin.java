package org.huntercore.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Metadata for a plugin shipped by HunterCore.
 */
public interface HunterBundledPlugin {

    @NotNull String id();

    @NotNull String name();

    @NotNull String version();

    @NotNull String fileName();

    @Nullable String source();
}
