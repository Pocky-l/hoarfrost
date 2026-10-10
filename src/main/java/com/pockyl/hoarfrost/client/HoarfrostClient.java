package com.pockyl.hoarfrost.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.compat.DistantHorizonsFog;
import com.pockyl.hoarfrost.client.sound.Hush;

@Mod(value = Hoarfrost.MOD_ID, dist = Dist.CLIENT)
public final class HoarfrostClient {
    public HoarfrostClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener((ModConfigEvent.Loading event) -> SnowClient.onConfigChanged());
        modBus.addListener((ModConfigEvent.Reloading event) -> SnowClient.onConfigChanged());
        // A restarted sound engine has a new OpenAL context: the filter must be made again.
        modBus.addListener((SoundEngineLoadEvent event) -> Hush.reset());
        // Last, so the leaves' colours of other mods are wrapped too.
        modBus.addListener(EventPriority.LOWEST, LeafFrost::onRegisterColors);
        modBus.addListener(LeafFrost::onRegisterResolvers);
        if (ModList.get().isLoaded("distanthorizons")) {
            DistantHorizonsFog.register();
        }
    }
}
