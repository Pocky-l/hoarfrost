package com.pockyl.hoarfrost.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.compat.DistantHorizonsFog;
import com.pockyl.hoarfrost.client.sound.Hush;

/**
 * Client-only setup on the mod bus. A subscriber class instead of code in the mod constructor, so a dedicated server never
 * loads any client class.
 */
@Mod.EventBusSubscriber(modid = Hoarfrost.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class HoarfrostClient {
    private HoarfrostClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(Hoarfrost.MOD_ID).ifPresent(container -> container.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> ConfigScreen.root(parent))));
        if (ModList.get().isLoaded("distanthorizons")) {
            DistantHorizonsFog.register();
        }
    }

    @SubscribeEvent
    public static void onConfigLoading(ModConfigEvent.Loading event) {
        SnowClient.onConfigChanged();
    }

    @SubscribeEvent
    public static void onConfigReloading(ModConfigEvent.Reloading event) {
        SnowClient.onConfigChanged();
    }

    @SubscribeEvent
    public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
        // A restarted sound engine has a new OpenAL context: the filter must be made again.
        Hush.reset();
    }

    // Last, so the leaves' colours of other mods are wrapped too.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRegisterColors(RegisterColorHandlersEvent.Block event) {
        LeafFrost.onRegisterColors(event);
    }

    @SubscribeEvent
    public static void onRegisterResolvers(RegisterColorHandlersEvent.ColorResolvers event) {
        LeafFrost.onRegisterResolvers(event);
    }

    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        LeafFrost.onModifyBakingResult(event);
    }
}
