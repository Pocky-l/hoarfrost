package com.pockyl.hoarfrost;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
