package com.pockyl.hoarfrost.weather;

/**
 * The natural course of the snowfall, derived only from the game time (which the server syncs to every client), so
 * all players see the same snow even when the server does not have this mod, and the server builds drifts with the
 * same wind the clients see.
 *
 * <ul>
 *   <li>the type of snow is rolled for every window of {@link #WINDOW} ticks, so a long snowfall changes character
 *   (flurries thickening into large flakes and thinning out again); a thunderstorm in snowy land is a blizzard;</li>
 *   <li>intensity breathes with slow and fast noise - a denser cloud passing, gusts;</li>
 *   <li>wind turns slowly over minutes and pulses with the gusts. Its direction uses the same noise as the rain of
 *   Petrichor, so rain and snow blow the same way where the two meet.</li>
 * </ul>
 */
public final class SnowSchedule {
    /** Five minutes. */
    public static final int WINDOW = 6000;
    private static final int TYPE_SEED = 0x5A0E_0001;
    private static final int GUST_SEED = 0x5A0E_0002;
    private static final int WIND_ANGLE_SEED = 0x5EED_0003;
    private static final int WIND_SPEED_SEED = 0x5A0E_0004;

    private SnowSchedule() {
    }

    /**
     * The type of snow at this time if nobody overrides it.
     *
     * @param weights relative chances of flurries, snowfall, large flakes and blizzard
     */
    public static SnowType naturalType(long gameTime, boolean thundering, boolean thunderBlizzard, int[] weights) {
        if (thundering && thunderBlizzard) {
            return SnowType.BLIZZARD;
        }
        int total = 0;
        for (int weight : weights) {
            total += Math.max(0, weight);
        }
        if (total <= 0) {
            return SnowType.SNOWFALL;
        }
        long window = Math.floorDiv(gameTime, WINDOW);
        float roll = Noise.unit(window, TYPE_SEED) * total;
        SnowType[] types = SnowType.values();
        for (int i = 0; i < types.length && i < weights.length; i++) {
            roll -= Math.max(0, weights[i]);
            if (roll < 0.0F) {
                return types[i];
            }
        }
        return SnowType.SNOWFALL;
    }

    /** Intensity multiplier around 1 for the given gustiness: slow swells over ~40 s plus quick gusts over ~4 s. */
    public static float gust(double time, float gustiness) {
        float slow = Noise.value(time / 800.0, GUST_SEED) - 0.5F;
        float fast = Noise.value(time / 80.0, GUST_SEED + 1) - 0.5F;
        return Math.max(0.3F, 1.0F + gustiness * (slow * 1.1F + fast * 0.5F));
    }

    /** Wind direction in radians; turns slowly over tens of minutes. */
    public static float windAngle(double time) {
        return (float) (Noise.value(time / 24000.0, WIND_ANGLE_SEED) * Math.PI * 4.0);
    }

    /** Wind speed in blocks per tick for a snowfall whose base wind is {@code base}. */
    public static float windSpeed(double time, float base, float gustiness) {
        float swell = 0.55F + 0.9F * Noise.value(time / 1200.0, WIND_SPEED_SEED);
        float gusts = 1.0F + gustiness * 0.8F * (Noise.value(time / 60.0, WIND_SPEED_SEED + 1) - 0.4F);
        return Math.max(0.0F, base * swell * gusts);
    }
}
