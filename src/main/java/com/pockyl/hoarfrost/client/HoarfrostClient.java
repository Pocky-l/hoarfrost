package com.pockyl.hoarfrost.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.pockyl.hoarfrost.Hoarfrost;

@Mod(value = Hoarfrost.MOD_ID, dist = Dist.CLIENT)
public final class HoarfrostClient {
    public HoarfrostClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }
}
