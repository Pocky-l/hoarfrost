package com.pockyl.hoarfrost.weather;

/**
 * The large-scale wind of a snowfall, derived only from the game time (synced to every client), so every player and the
 * server feel the same wind:
 * <ul>
 *   <li>its course turns slowly over tens of minutes, with the same noise as Petrichor's rain, so rain and snow blow the
 *   same way where they meet; around that course it wanders over tens of seconds, widely in a storm;</li>
 *   <li>its speed swells and slackens over minutes;</li>
 *   <li>gusts and lulls are fronts carried along with the air: they roll over the land from upwind, each with its own
 *   width, so a squall can be seen coming.</li>
 * </ul>
 */
public final class Wind {
    /** Shared with Petrichor: do not change. */
    private static final int COURSE_SEED = 0x5EED_0003;
    private static final int WANDER_SEED = 0x5A0E_0011;
    private static final int SWELL_SEED = 0x5A0E_0012;
    private static final int GUST_SEED = 0x5A0E_0013;

    private Wind() {
    }

    /** The slow course of the wind in radians; turns over tens of minutes. Identical to Petrichor's rain. */
    public static float course(double time) {
        return (float) (Noise.value(time / 24000.0, COURSE_SEED) * Math.PI * 4.0);
    }

    /** Direction in radians: the course plus a wander of up to ~40 degrees in a storm, ~15 in calm snow. */
    public static float angle(double time, float gustiness) {
        float slow = (Noise.value(time / 1100.0, WANDER_SEED) - 0.5F) * 2.0F;
        float quick = (Noise.value(time / 420.0, WANDER_SEED + 1) - 0.5F) * 2.0F;
        return course(time) + slow * (0.25F + 0.35F * gustiness) + quick * 0.05F * gustiness;
    }

    /** Mean speed in blocks per tick for a snowfall whose base wind is {@code base}: swells and slackens over minutes. */
    public static float speed(double time, float base, float gustiness) {
        float swell = Noise.value(time / 1600.0, SWELL_SEED);
        float breath = Noise.value(time / 500.0, SWELL_SEED + 1);
        return base * (0.6F + 0.65F * swell + gustiness * 0.35F * (breath - 0.5F));
    }

    /**
     * The gust factor around 1 at a point: above 1 in a gust, below in a lull. The pattern is fixed to the moving air
     * (shifted by the drift), so gusts roll past from upwind.
     *
     * @param driftX how far the wind has carried the air
     */
    public static float gust(double x, double z, double driftX, double driftZ, float gustiness) {
        if (gustiness <= 0.0F) {
            return 1.0F;
        }
        // The pattern only moves with the air: tied to the drift, not to the direction (turning it around a far origin
        // would sweep the gusts across the land).
        double rx = x - driftX;
        double rz = z - driftZ;
        float big = Noise.value(rx / 48.0, rz / 48.0, GUST_SEED) - 0.5F;
        float small = Noise.value(rx / 22.0, rz / 22.0, GUST_SEED + 1) - 0.5F;
        float g = 1.0F + gustiness * 0.85F * (big * 2.2F + small * 0.55F);
        return Math.clamp(g, 0.12F, 2.4F);
    }
}
