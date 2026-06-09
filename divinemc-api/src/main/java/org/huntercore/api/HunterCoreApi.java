package org.huntercore.api;

import java.util.Collection;
import org.jetbrains.annotations.NotNull;

public interface HunterCoreApi {

    @NotNull String name();

    @NotNull String version();

    @NotNull Collection<HunterBundledPlugin> bundledPlugins();

    void registerCommandExtension(@NotNull HunterCommandExtension extension);

    @NotNull Collection<HunterCommandExtension> commandExtensions();
}
