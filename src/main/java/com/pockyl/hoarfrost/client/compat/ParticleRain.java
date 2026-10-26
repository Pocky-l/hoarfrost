package com.pockyl.hoarfrost.client.compat;

import net.minecraft.util.Mth;
import net.minecraftforge.fml.ModList;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.Hoarfrost;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Sharing the sky with <a href="https://www.curseforge.com/minecraft/mc-mods/particle-rain">Particle Rain</a>, a client
 * mod that draws the weather with particles: this mod keeps the snowfall (flakes, squalls, the snow cover, the sounds of
 * snow), Particle Rain everything else it does - rain, sandstorms and dust, fog and its other ambient particles.
 *
 * <p>Particle Rain is never compiled against. Its few touch points are mixins that apply only when its classes are
 * there ({@code mixin.compat}) and the reflection below. Particle Rain's config is only ever read, never changed. If its
 * internals change under us, the integration switches itself off instead of crashing the game: Particle Rain then works
 * as without this mod's help.
 */
public final class ParticleRain {
    public static final String MOD_ID = "particlerain";

    private static final String LOADER = "pigcart.particlerain.ParticleLoader";
    /** {@code ParticleData.Weather} values that spawn a particle only during precipitation: falling snow and the like. */
    private static final Set<String> FALLING_WEATHER = Set.of("DURING_WEATHER", "ONLY_DURING_NORMAL_WEATHER", "ONLY_DURING_STORMY_WEATHER");

    private static Boolean loaded;
    private static volatile boolean broken;
    private static Field presets;
    private static Field weather;
    private static Field precipitation;
    /** Precipitation lists of the presets this mod takes over in its snowfall, by identity. */
    private static Set<Object> fallingLists = Set.of();

    private ParticleRain() {
    }

    /** Whether Particle Rain is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** Whether the two mods share the weather now: Particle Rain is installed and the integration is on. */
    public static boolean active() {
        return !broken && loaded() && ClientConfig.PARTICLE_RAIN.get();
    }

    /**
     * Whether a Particle Rain preset falls with the weather: it spawns only during precipitation. Presets that show in
     * any weather or after it (fog, mist) do not, nor does anything Particle Rain spawns where nothing falls (dust).
     */
    public static boolean fallingPreset(String weather) {
        return FALLING_WEATHER.contains(weather);
    }

    /**
     * Whether Particle Rain should skip a spawn: it checks a preset's precipitation list against the precipitation
     * where the particle would appear, and this answers in place of that check. Only falling presets on snow ground are
     * skipped.
     */
    public static boolean yieldsSnow(Object presetPrecipitation, boolean snowGround) {
        return snowGround && fallingLists.contains(presetPrecipitation);
    }

    /**
     * Particle Rain's wind turned into this mod's wind direction: its own strength (gusts, height) is kept so its
     * particles drift as hard as their presets say, but they all drift the same way as the snow. Calm air in this mod
     * leaves Particle Rain's wind as it is.
     *
     * @return {@code {x, z}}
     */
    public static float[] alignWind(float windX, float windZ, float ourX, float ourZ) {
        float ours = Mth.sqrt(ourX * ourX + ourZ * ourZ);
        if (ours < 1.0E-3F) {
            return new float[] {windX, windZ};
        }
        float strength = Mth.sqrt(windX * windX + windZ * windZ);
        return new float[] {ourX / ours * strength, ourZ / ours * strength};
    }

    /**
     * Reads which of Particle Rain's presets fall with the weather. They can be edited in its config screen and are
     * reloaded with the resource packs, so this runs every client tick while the integration is active.
     */
    public static void refresh() {
        if (!active()) {
            fallingLists = Set.of();
            return;
        }
        try {
            if (presets == null) {
                presets = Class.forName(LOADER).getField("particles");
            }
            Map<?, ?> particles = (Map<?, ?>) presets.get(null);
            if (particles == null) {
                fallingLists = Set.of();
                return;
            }
            Set<Object> lists = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Object data : particles.values()) {
                if (weather == null) {
                    weather = data.getClass().getField("weather");
                    precipitation = data.getClass().getField("precipitation");
                }
                Object list = precipitation.get(data);
                if (list != null && weather.get(data) instanceof Enum<?> when && fallingPreset(when.name())) {
                    lists.add(list);
                }
            }
            fallingLists = lists;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable(e);
        }
    }

    /** Turns the integration off for the rest of the session after Particle Rain did not look as expected. */
    public static void disable(Throwable e) {
        fallingLists = Set.of();
        if (!broken) {
            broken = true;
            Hoarfrost.LOGGER.warn("Particle Rain integration turned off: this version of Particle Rain is not supported", e);
        }
    }
}
