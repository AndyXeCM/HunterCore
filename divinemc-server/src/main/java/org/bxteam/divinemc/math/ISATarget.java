package org.bxteam.divinemc.math;

import org.bxteam.divinemc.math.isa.ISA_aarch64;
import org.bxteam.divinemc.math.isa.ISA_x86_64;

public interface ISATarget {
    int ordinal();

    String getSuffix();

    boolean isNativelySupported();

    static Class<? extends Enum<? extends ISATarget>> getInstance() {
        return switch (NativeLoader.NORMALIZED_ARCH) {
            case "x86_64" -> ISA_x86_64.class;
            case "aarch_64" -> ISA_aarch64.class;
            default -> null;
        };
    }
}
