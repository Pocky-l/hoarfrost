package com.pockyl.hoarfrost.client.compat;

import net.minecraft.core.Holder;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.biome.Biome;

import java.lang.reflect.Field;

/**
 * Snowy leaves on the distant land of <a href="https://www.curseforge.com/minecraft/mc-mods/distant-horizons">Distant
 * Horizons</a>. Its tint getter caches one colour per block and biome, whatever colour resolver asked for it, and later
 * uses that colour as the block's tint: the snowy-biome flag of the leaf frost turned distant spruce and birch leaves
 * blue or black. Leaves seen by Distant Horizons read their biome from its getter instead and never ask it for the flag.
 *
 * <p>Works by reflection, so nothing here needs Distant Horizons to be installed; if its internals change, the frost
 * is simply left out on the distant land.
 */
public final class DistantHorizonsTint {
    private static final String PACKAGE = "com.seibel.distanthorizons.";

    private static Class<?> getterClass;
    private static Field biomeWrapper;
    private static Field biome;
    private static boolean broken;

    private DistantHorizonsTint() {
    }

    /** Whether the getter belongs to Distant Horizons. */
    public static boolean handles(BlockAndTintGetter getter) {
        return getter.getClass().getName().startsWith(PACKAGE);
    }

    /** 1 where the biome the getter is building is colder than {@code cold}, 0 elsewhere or when it is unknown. */
    @SuppressWarnings("unchecked")
    public static float snowy(BlockAndTintGetter getter, float cold) {
        if (broken) {
            return 0.0F;
        }
        try {
            if (getterClass != getter.getClass()) {
                biomeWrapper = find(getter.getClass(), "biomeWrapper");
                getterClass = getter.getClass();
            }
            Object wrapper = biomeWrapper.get(getter);
            if (wrapper == null) {
                return 0.0F;
            }
            if (biome == null || biome.getDeclaringClass() != wrapper.getClass()) {
                biome = find(wrapper.getClass(), "biome");
            }
            Holder<Biome> holder = (Holder<Biome>) biome.get(wrapper);
            return holder != null && holder.value().getBaseTemperature() < cold ? 1.0F : 0.0F;
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            return 0.0F;
        }
    }

    private static Field find(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Declared further up.
            }
        }
        throw new NoSuchFieldException(name);
    }
}
