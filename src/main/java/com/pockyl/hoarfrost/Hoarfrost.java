package com.pockyl.hoarfrost;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

import com.pockyl.hoarfrost.network.ModNetwork;

@Mod(Hoarfrost.MOD_ID)
public final class Hoarfrost {
    public static final String MOD_ID = "hoarfrost";
    public static final Logger LOGGER = LogUtils.getLogger();

    // The no-argument constructor keeps the jar loadable on NeoForge for 1.20.1 as well.
    public Hoarfrost() {
        // No items: nothing goes into the shared Pocky Mods tab.
        ModNetwork.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
