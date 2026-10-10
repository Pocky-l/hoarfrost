package com.pockyl.hoarfrost.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.Season;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHooks;

import com.pockyl.hoarfrost.Hoarfrost;

/**
 * Calls into Serene Seasons. Only used by {@link Climate} after checking the mod is loaded; any failure (an API change in
 * a newer version) turns the integration off instead of breaking the weather.
 */
final class SereneSeasons {
    private static boolean broken;

    private SereneSeasons() {
    }

    /** The seasonal precipitation, or {@code null} when the integration does not work. */
    static Biome.Precipitation precipitationAt(Level level, BlockPos pos) {
        if (broken) {
            return null;
        }
        try {
            return SeasonHooks.getPrecipitationAtSeasonal(level, level.getBiome(pos), pos);
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    static float winter(Level level) {
        Season.SubSeason sub = subSeason(level);
        if (sub == null) {
            return -1.0F;
        }
        return switch (sub) {
            case EARLY_WINTER -> 0.55F;
            case MID_WINTER -> 1.0F;
            case LATE_WINTER -> 0.65F;
            default -> 0.0F;
        };
    }

    static String seasonName(Level level) {
        Season.SubSeason sub = subSeason(level);
        return sub == null ? null : sub.getSerializedName();
    }

    private static Season.SubSeason subSeason(Level level) {
        if (broken) {
            return null;
        }
        try {
            ISeasonState state = SeasonHelper.getSeasonState(level);
            return state == null ? null : state.getSubSeason();
        } catch (RuntimeException | LinkageError e) {
            fail(e);
            return null;
        }
    }

    private static void fail(Throwable e) {
        broken = true;
        Hoarfrost.LOGGER.warn("Serene Seasons integration disabled: the installed version does not match", e);
    }
}
