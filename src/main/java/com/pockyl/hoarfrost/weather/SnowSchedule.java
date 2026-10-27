package com.pockyl.hoarfrost.weather;

/**
 * The natural course of a snowfall, derived only from the game time (which the server syncs to every client), so all
 * players see the same snow even when the server does not have this mod. The kind is rolled for every window of
 * {@link #WINDOW} ticks, so a long snowfall changes character - light snow thickening into heavy snow and thinning out
 * again; a thunderstorm in snowy land is one of the blizzards.
 */
public final class SnowSchedule {
    /** Five minutes. */
    public static final int WINDOW = 6000;
    private static final int KIND_SEED = 0x5A0E_0001;
    private static final int STORM_SEED = 0x5A0E_0002;
    private static final int FAIR_SEED = 0x5A0E_0004;
    /** Ticks over which the snow from a clear sky eases from one window into the next. */
    private static final int FAIR_EASE = 1200;
    /** Share of the snowy spells under a clear sky that are light snow; the rest are a steady snowfall. */
    private static final float FAIR_LIGHT = 0.8F;
    private static final SnowKind[] KINDS = SnowKind.values();

    private SnowSchedule() {
    }

    /**
     * The kind of snow at this time if nobody forces one.
     *
     * @param weights relative chances in {@link SnowKind} order; a season may have shifted them (see
     *        {@link #seasonWeights})
     */
    public static SnowKind naturalKind(long gameTime, boolean thundering, boolean thunderBlizzard, int[] weights) {
        long window = Math.floorDiv(gameTime, WINDOW);
        if (thundering && thunderBlizzard) {
            float roll = Noise.unit(window, STORM_SEED);
            return roll < 0.3F ? SnowKind.LIGHT_BLIZZARD : roll < 0.8F ? SnowKind.BLIZZARD : SnowKind.HEAVY_BLIZZARD;
        }
        int total = 0;
        for (int i = 0; i < KINDS.length && i < weights.length; i++) {
            total += Math.max(0, weights[i]);
        }
        if (total <= 0) {
            return SnowKind.SNOW;
        }
        float roll = Noise.unit(window, KIND_SEED) * total;
        for (int i = 0; i < KINDS.length && i < weights.length; i++) {
            roll -= Math.max(0, weights[i]);
            if (roll < 0.0F) {
                return KINDS[i];
            }
        }
        return SnowKind.SNOW;
    }

    /**
     * The snow that falls in snowy land under a clear sky (as in real snowy country, where it snows on most days without
     * a storm): light snow most of the time, now and then a steady snowfall, and in {@code clearSpells} percent of the
     * windows none at all. Rolled per window like {@link #naturalKind}, so every player sees the same.
     *
     * @return the kind, or {@code null} in a clear spell
     */
    public static SnowKind fairKind(long gameTime, int clearSpells) {
        return fairKind(Math.floorDiv(gameTime, WINDOW), clearSpells / 100.0F);
    }

    /**
     * How much snow falls under a clear sky, 0..1: a little more or less from window to window, eased over a minute
     * into the next one, so a clear spell sets in and ends gradually.
     */
    public static float fairLevel(long gameTime, int clearSpells) {
        float clear = clearSpells / 100.0F;
        long window = Math.floorDiv(gameTime, WINDOW);
        float here = fairStrength(window, clear);
        long into = gameTime - window * WINDOW;
        if (into >= FAIR_EASE) {
            return here;
        }
        float t = into / (float) FAIR_EASE;
        t = t * t * (3.0F - 2.0F * t);
        float before = fairStrength(window - 1, clear);
        return before + (here - before) * t;
    }

    private static SnowKind fairKind(long window, float clear) {
        float roll = Noise.unit(window, FAIR_SEED);
        if (roll < clear) {
            return null;
        }
        return (roll - clear) < (1.0F - clear) * FAIR_LIGHT ? SnowKind.LIGHT_SNOW : SnowKind.SNOW;
    }

    private static float fairStrength(long window, float clear) {
        SnowKind kind = fairKind(window, clear);
        if (kind == null) {
            return 0.0F;
        }
        return (kind == SnowKind.SNOW ? 0.55F : 0.45F) + 0.4F * Noise.unit(window, FAIR_SEED + 1);
    }

    /**
     * Shifts the chances with the season: deep winter brings heavy snow and blizzards, the edges of winter light snow.
     *
     * @param winter how deep in winter, 0 (late autumn or spring) .. 1 (mid winter); negative when there is no season
     */
    public static int[] seasonWeights(int[] weights, float winter) {
        if (winter < 0.0F) {
            return weights;
        }
        int[] shifted = weights.clone();
        float edge = 1.0F - winter;
        for (int i = 0; i < shifted.length && i < KINDS.length; i++) {
            SnowKind kind = KINDS[i];
            float factor = switch (kind) {
                case LIGHT_SNOW -> 1.0F + 1.5F * edge;
                case SNOW -> 1.0F;
                case HEAVY_SNOW, HEAVY_LARGE_FLAKES -> 0.5F + 0.9F * winter;
                case LIGHT_BLIZZARD -> 0.3F + winter;
                case BLIZZARD, HEAVY_BLIZZARD -> 0.15F + 1.2F * winter;
            };
            shifted[i] = Math.round(weights[i] * factor * 4.0F);
        }
        return shifted;
    }
}
