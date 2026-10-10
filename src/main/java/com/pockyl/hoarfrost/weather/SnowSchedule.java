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
