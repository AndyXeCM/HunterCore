package org.huntercore.api;

import java.util.Collection;
import java.util.Collections;
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
        public void registerCommandExtension(@NotNull final HunterCommandExtension extension) {
            throw new IllegalStateException("HunterCore API is not available yet");
        }

        @Override
        public @NotNull Collection<HunterCommandExtension> commandExtensions() {
            return Collections.emptyList();
        }
    }
}
