package com.pockyl.hoarfrost.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraftforge.fml.ModList;

import com.pockyl.hoarfrost.Config;

/**
 * Where it snows, and how deep in winter the world is. With <a href="https://www.curseforge.com/minecraft/mc-mods/serene-seasons">
 * Serene Seasons</a> the answer follows the season (snow in temperate land in winter, rain again in spring, nothing in a
 * warm summer); without it, the biome's own climate. Serene Seasons is only touched through {@link SereneSeasons}, which
 * is never loaded when the mod is absent.
 */
public final class Climate {
    private static final boolean SERENE_SEASONS = ModList.get() != null && ModList.get().isLoaded("sereneseasons");

    private Climate() {
    }

    /** Whether the season of Serene Seasons decides where it snows. */
    public static boolean seasons() {
        return SERENE_SEASONS && Config.followSeasons();
    }

    /** What falls from the sky at this position when it rains in the level. */
    public static Biome.Precipitation precipitationAt(Level level, BlockPos pos) {
        if (seasons()) {
            Biome.Precipitation seasonal = SereneSeasons.precipitationAt(level, pos);
            if (seasonal != null) {
                return seasonal;
            }
        }
        Biome biome = level.getBiome(pos).value();
        return biome.hasPrecipitation() ? biome.getPrecipitationAt(pos) : Biome.Precipitation.NONE;
    }

    /** Whether snow (not rain) falls and stays here. */
    public static boolean snowsAt(Level level, BlockPos pos) {
        return precipitationAt(level, pos) == Biome.Precipitation.SNOW;
    }

    /**
     * How deep in winter the level is: 0 at its edges (late autumn, early spring) .. 1 in mid winter; -1 when there are
     * no seasons.
     */
    public static float winter(Level level) {
        return seasons() ? SereneSeasons.winter(level) : -1.0F;
    }

    /** The current sub-season for status messages, or {@code null} without seasons. */
    public static String seasonName(Level level) {
        return seasons() ? SereneSeasons.seasonName(level) : null;
    }
}
