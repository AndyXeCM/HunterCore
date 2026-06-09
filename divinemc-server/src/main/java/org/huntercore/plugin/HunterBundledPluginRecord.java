package org.huntercore.plugin;

import org.huntercore.api.HunterBundledPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public record HunterBundledPluginRecord(
    @NotNull String id,
    @NotNull String name,
    @NotNull String version,
    @NotNull String fileName,
    @Nullable String source,
    @NotNull String resource,
    @Nullable String sha256
) implements HunterBundledPlugin {
}
