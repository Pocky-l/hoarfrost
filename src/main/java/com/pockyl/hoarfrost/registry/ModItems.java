package com.pockyl.hoarfrost.registry;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.pockyl.hoarfrost.Hoarfrost;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Hoarfrost.MOD_ID);

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
