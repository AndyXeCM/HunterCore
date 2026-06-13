package org.huntercore.bootstrap;

import java.nio.file.Path;
import org.huntercore.api.HunterCoreProvider;
import org.huntercore.optimization.HunterCoreOptimizer;

public final class HunterCoreBootstrap {
    private static boolean initialized;

    private HunterCoreBootstrap() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }
        HunterCoreOptimizer.applyStartupDefaults();
        HunterCoreOptimizer.applyEarlyPreferenceDefaults(Path.of("plugins", "HunterCore", "preferences.yml"));
        HunterCoreProvider.register(HunterCoreRuntime.get());
        initialized = true;
    }
}
