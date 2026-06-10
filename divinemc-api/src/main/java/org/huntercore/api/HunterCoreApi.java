package org.huntercore.api;

import java.util.Collection;
import org.huntercore.api.fakeplayer.HunterFakePlayerService;
import org.jetbrains.annotations.NotNull;

public interface HunterCoreApi {

    @NotNull String name();

    @NotNull String version();

    @NotNull Collection<HunterBundledPlugin> bundledPlugins();

    @NotNull HunterFakePlayerService fakePlayers();

    void registerCommandExtension(@NotNull HunterCommandExtension extension);

    @NotNull Collection<HunterCommandExtension> commandExtensions();
}
