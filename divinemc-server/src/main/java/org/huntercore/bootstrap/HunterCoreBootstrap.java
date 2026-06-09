package org.huntercore.bootstrap;

import org.huntercore.api.HunterCoreProvider;

public final class HunterCoreBootstrap {
    private static boolean initialized;

    private HunterCoreBootstrap() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        HunterCoreProvider.register(HunterCoreRuntime.get());
        initialized = true;
    }
}
