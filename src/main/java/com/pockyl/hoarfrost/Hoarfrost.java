package com.pockyl.hoarfrost;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

import com.pockyl.hoarfrost.network.ModNetwork;

@Mod(Hoarfrost.MOD_ID)
public final class Hoarfrost {
    public static final String MOD_ID = "hoarfrost";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Hoarfrost(IEventBus modBus, ModContainer container) {
        // No items: nothing goes into the shared Pocky Mods tab.
        modBus.addListener(ModNetwork::register);
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
