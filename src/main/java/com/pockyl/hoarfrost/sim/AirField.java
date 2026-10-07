package com.pockyl.hoarfrost.sim;

import net.minecraft.util.Mth;

import com.pockyl.hoarfrost.weather.Noise;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The air that carries the snow. Flakes are so light that they go wherever it goes, so this field is what the
 * snowfall looks like:
 * <ul>
 *   <li>the wind of the weather, slower near the ground (a log profile), with gust waves sweeping along it;</li>
 *   <li>terrain: behind an obstacle (seen from the wind) the air is calm - the lee where snow settles - and right
 *   behind it rolls back along the ground and up its back (a rotor), so flakes swirl there; in front of a wall the
 *   air is pushed up and over it;</li>
 *   <li>eddies: a turbulent field drifting with the wind, stronger in gusty weather and in the lee;</li>
 *   <li>events: whirls that lift powder in strong wind, the air pulse of an explosion, and the wake of anything that
 *   moves (a running player parts the falling snow).</li>
 * </ul>
 * All speeds are in blocks per tick.
 */
public final class AirField {
    private static final int TURBULENCE_SEED = 0x7A51;
    private static final int MAX_WHIRLS = 3;
    private static final int MAX_WAKES = 24;
    public static final int MAX_GUSTS = 3;
    private static final int LEE_REACH = 8;

    private final Terrain terrain;
    private final SnowWeather weather;
    private final List<Whirl> whirls = new ArrayList<>();
    private final List<Blast> blasts = new ArrayList<>();
    private final List<Gust> gusts = new ArrayList<>();
    private int nextGustId = 1;
    private final double[] wakes = new double[MAX_WAKES * 7];
    private int wakeCount;
    private float time;
    private float dirX = 1.0F;
    private float dirZ;
    private float speed;
    private boolean turbulence = true;

    AirField(Terrain terrain, SnowWeather weather) {
        this.terrain = terrain;
        this.weather = weather;
    }

    /** A spinning column of air that lifts snow off the ground and carries it along. */
    public static final class Whirl {
        public double x;
        public double y;
        public double z;
        public final float radius;
        public final float height;
        final float strength;
        final float spin;
        final int life;
        int age;

        Whirl(double x, double y, double z, float radius, float height, float strength, float spin, int life) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.height = height;
            this.strength = strength;
            this.spin = spin;
            this.life = life;
        }

        /** 0..1, fading in and out. */
        public float intensity() {
            return Math.min(1.0F, Math.min(age, life - age) / 30.0F);
        }
    }

    /**
     * A squall of snow: a gust of wind that gathers the snow into a dense cloud and carries it along the ground, rolling
     * - in a blizzard every few seconds, in a quiet snowfall now and then a soft puff. It comes from upwind, follows the
     * land at about head height, passes over (or through) the player and dies away.
     */
    public static final class Gust {
        public final int id;
        public double x;
        public double y;
        public double z;
        public final float dirX;
        public final float dirZ;
        public final float speed;
        /** Half sizes: across the ground and up. */
        public final float radius;
        public final float height;
        public final float strength;
        /** Which way it rolls. */
        final float roll;
        final int life;
        int age;

        Gust(int id, double x, double y, double z, float dirX, float dirZ, float speed, float radius, float height, float strength,
                float roll, int life) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.speed = speed;
            this.radius = radius;
            this.height = height;
            this.strength = strength;
            this.roll = roll;
            this.life = life;
        }

        /** 0..1: it builds up, holds and dies away. */
        public float envelope() {
            float t = age / (float) life;
            float in = Math.min(1.0F, t / 0.15F);
            float out = Math.min(1.0F, (1.0F - t) / 0.3F);
            return Math.max(0.0F, Math.min(in, out));
        }

        /** How deep a point is inside the cloud, 0 (outside) .. 1 (in its heart), with the envelope. */
        public float inside(double px, double py, double pz) {
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            double d = (dx * dx + dz * dz) / (radius * radius) + dy * dy / (height * height);
            if (d >= 1.0) {
                return 0.0F;
            }
            float f = (float) (1.0 - d);
            return f * f * envelope();
        }

        public boolean done() {
            return age >= life;
        }
    }

    /** The air of an explosion: an expanding ring of outflow and a rising column in the middle, gone within ~2 s. */
    static final class Blast {
        final double x;
        final double y;
        final double z;
        final float radius;
        final float strength;
        final float swirl;
        int age;

        Blast(double x, double y, double z, float radius, float strength, float swirl) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
            this.strength = strength;
            this.swirl = swirl;
        }

        boolean done() {
            return age > 40;
        }

        void add(double px, double py, double pz, float[] out) {
            float dx = (float) (px - x);
            float dy = (float) (py - y);
            float dz = (float) (pz - z);
            float flat = Mth.sqrt(dx * dx + dz * dz);
            if (flat > radius * 1.8F || dy < -2.0F || dy > radius * 1.8F) {
                return;
            }
            float decay = (float) Math.exp(-age / 10.0);
            float front = radius * (1.0F - (float) Math.exp(-age / 6.0)) * 1.3F;
            float width = 0.8F + radius * 0.25F;
            float ring = (flat - front) / width;
            float outflow = strength * decay * (float) Math.exp(-ring * ring);
            float nx = flat > 1.0E-3F ? dx / flat : 0.0F;
            float nz = flat > 1.0E-3F ? dz / flat : 0.0F;
            out[0] += (nx - nz * swirl) * outflow;
            out[2] += (nz + nx * swirl) * outflow;
            out[1] += outflow * 0.45F;
            if (flat < radius * 0.6F) {
                float core = 1.0F - flat / (radius * 0.6F);
                out[1] += strength * 0.6F * core * (float) Math.exp(-age / 14.0);
            }
        }
    }

    void tick(int tick, FastRandom random, double cameraX, double cameraZ, boolean allowWhirls, boolean allowGusts) {
        time = tick;
        speed = weather.windSpeed();
        if (speed > 1.0E-4F) {
            dirX = weather.windX / speed;
            dirZ = weather.windZ / speed;
        }
        wakeCount = 0;
        for (Blast blast : blasts) {
            blast.age++;
        }
        blasts.removeIf(Blast::done);
        for (Iterator<Whirl> it = whirls.iterator(); it.hasNext(); ) {
            Whirl whirl = it.next();
            if (++whirl.age >= whirl.life) {
                it.remove();
                continue;
            }
            whirl.x += weather.windX * 0.6F;
            whirl.z += weather.windZ * 0.6F;
            if ((whirl.age & 7) == 0) {
                int bx = Mth.floor(whirl.x);
                int bz = Mth.floor(whirl.z);
                if (!terrain.isLoaded(bx, bz) || !terrain.snowy(bx, bz)) {
                    it.remove();
                    continue;
                }
                whirl.y = terrain.height(bx, bz);
            }
        }
        tickGusts(random, cameraX, cameraZ, allowGusts);
        // Whirls rise where strong wind meets the ground and does not stay long.
        float strong = Mth.clamp((speed - 0.18F) / 0.25F, 0.0F, 1.0F);
        if (allowWhirls && strong > 0.0F && whirls.size() < MAX_WHIRLS && random.next() < strong * weather.snow / (20.0F * 25.0F)) {
            float angle = random.next() * Mth.TWO_PI;
            float distance = 6.0F + random.next() * 16.0F;
            double x = cameraX + Mth.cos(angle) * distance;
            double z = cameraZ + Mth.sin(angle) * distance;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            if (terrain.isLoaded(bx, bz) && terrain.snowy(bx, bz) && terrain.shelter(bx, bz) < 0.3F) {
                whirls.add(new Whirl(x, terrain.height(bx, bz), z, 0.7F + random.next() * 0.9F, 2.5F + random.next() * 3.5F,
                        0.16F + random.next() * 0.18F, random.next() < 0.5F ? -1.0F : 1.0F, 50 + (int) (random.next() * 120)));
            }
        }
    }

    private void tickGusts(FastRandom random, double cameraX, double cameraZ, boolean spawn) {
        for (Iterator<Gust> it = gusts.iterator(); it.hasNext(); ) {
            Gust gust = it.next();
            if (++gust.age >= gust.life) {
                it.remove();
                continue;
            }
            gust.x += gust.dirX * gust.speed;
            gust.z += gust.dirZ * gust.speed;
            if ((gust.age & 7) == 0) {
                // Hugging the land: its middle stays about head height above the ground below it.
                int bx = Mth.floor(gust.x);
                int bz = Mth.floor(gust.z);
                if (terrain.isLoaded(bx, bz)) {
                    gust.y += (terrain.height(bx, bz) + gust.height * 0.45 - gust.y) * 0.3;
                }
            }
        }
        float chance = weather.snow * (0.2F + weather.gustiness) * (0.15F + speed) / 90.0F;
        if (!spawn || gusts.size() >= MAX_GUSTS || random.next() >= chance) {
            return;
        }
        float dirX = speed > 1.0E-3F ? this.dirX : 1.0F;
        float dirZ = speed > 1.0E-3F ? this.dirZ : 0.0F;
        float travel = 35.0F + random.next() * 15.0F;
        float side = (random.next() - 0.5F) * 30.0F;
        double x = cameraX - dirX * travel - dirZ * side;
        double z = cameraZ - dirZ * travel + dirX * side;
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (!terrain.isLoaded(bx, bz) || !terrain.snowy(bx, bz)) {
            return;
        }
        float storm = Mth.clamp(speed / 0.5F, 0.0F, 1.0F);
        float radius = 3.5F + random.next() * (3.0F + 5.0F * storm);
        float height = 2.5F + random.next() * (1.5F + 3.0F * storm);
        float gustSpeed = Math.max(0.08F, speed * (1.2F + 0.4F * random.next()));
        int life = Math.min(1600, (int) ((travel + 40.0F) / gustSpeed));
        gusts.add(new Gust(nextGustId++, x, terrain.height(bx, bz) + height * 0.45, z, dirX, dirZ, gustSpeed, radius, height,
                0.45F + 0.55F * random.next(), random.next() < 0.5F ? -1.0F : 1.0F, life));
    }

    /** The squalls of snow passing now. */
    public List<Gust> gusts() {
        return gusts;
    }

    /** A squall at a spot moving along a direction (tests). */
    public Gust addGust(double x, double y, double z, float dirX, float dirZ, float gustSpeed, float radius, float height, int life) {
        Gust gust = new Gust(nextGustId++, x, y, z, dirX, dirZ, gustSpeed, radius, height, 1.0F, 1.0F, life);
        gust.age = life / 4;
        gusts.add(gust);
        return gust;
    }

    /** The strongest squall at a point, 0..1 (how deep inside a snow cloud it is). */
    public float gustAt(double x, double y, double z) {
        float strongest = 0.0F;
        for (int g = 0; g < gusts.size(); g++) {
            Gust gust = gusts.get(g);
            strongest = Math.max(strongest, gust.inside(x, y, z) * gust.strength);
        }
        return strongest;
    }

    /** Starts the air pulse of an explosion. */
    void addBlast(double x, double y, double z, float radius, float strength, float swirl) {
        blasts.add(new Blast(x, y, z, radius, strength, swirl));
    }

    /** Adds a whirl at a spot (tests). */
    public Whirl addWhirl(double x, double y, double z, float radius, float height, float strength, int life) {
        Whirl whirl = new Whirl(x, y, z, radius, height, strength, 1.0F, life);
        whirl.age = 30;
        whirls.add(whirl);
        return whirl;
    }

    /** Something moving through the air this tick: it drags the air around it along. */
    void addWake(double x, double y, double z, double moveX, double moveZ, float radius, float height) {
        if (wakeCount >= MAX_WAKES || moveX * moveX + moveZ * moveZ < 0.0025) {
            return;
        }
        int w = wakeCount++ * 7;
        wakes[w] = x;
        wakes[w + 1] = y;
        wakes[w + 2] = z;
        wakes[w + 3] = moveX;
        wakes[w + 4] = moveZ;
        wakes[w + 5] = radius;
        wakes[w + 6] = height;
    }

    /** Switches the eddies off, leaving the mean flow (tests). */
    public void setTurbulence(boolean on) {
        turbulence = on;
    }

    public List<Whirl> whirls() {
        return whirls;
    }

    public float dirX() {
        return dirX;
    }

    public float dirZ() {
        return dirZ;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sampling
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Gust factor of the wind at a horizontal position: waves of stronger wind sweeping along it, around 1, wider in
     * gusty weather (0.4 .. 1.6 in a blizzard).
     */
    public float gust(double x, double z) {
        float s = time * 0.03F - (float) ((x * dirX + z * dirZ) * 0.06);
        float g = 0.32F * Mth.sin(s) + 0.18F * Mth.sin(s * 2.7F + 1.1F);
        return 1.0F + weather.gustiness * g * 1.35F;
    }

    /** Wind speed in the lowest air at a height above the ground: the wind slows down towards the surface. */
    static float profile(float aboveGround) {
        return Math.min(1.15F, 0.55F + 0.22F * (float) Math.log(1.0 + Math.max(0.0, aboveGround)));
    }

    /**
     * Wind speed just above the snow of a column (a quarter block up), with gusts and the column's shelter: what moves
     * the snow lying there.
     */
    public float surfaceSpeed(int x, int z) {
        return speed * gust(x + 0.5, z + 0.5) * profile(0.25F) * (1.0F - 0.92F * terrain.shelter(x, z));
    }

    /** Air velocity at a point into {@code out[0..2]}. */
    public void sample(double x, double y, double z, float[] out) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int ground = terrain.height(bx, bz);
        float above = (float) (y - ground);
        float wind = speed * gust(x, z) * profile(above);
        // Lee: obstacles upwind that reach above the point.
        float lee = 0.0F;
        int nearest = 0;
        if (speed > 1.0E-4F) {
            for (int d = 1; d <= LEE_REACH; d++) {
                float h = terrain.height(Mth.floor(x - dirX * d), Mth.floor(z - dirZ * d)) - (float) y;
                if (h > 0.0F) {
                    float s = Terrain.leeStrength(h, d);
                    if (s > lee) {
                        lee = s;
                    }
                    if (nearest == 0) {
                        nearest = d;
                    }
                }
            }
        }
        float u = wind * (1.0F - 0.85F * lee);
        float vy = 0.0F;
        if (lee > 0.3F && nearest > 0) {
            // The rotor behind an obstacle: up along its back, back towards it near the ground, down further on.
            float rotor = speed * lee;
            if (nearest <= 1) {
                vy += rotor * 0.4F;
            } else if (nearest <= 4) {
                vy -= rotor * 0.15F;
                if (above < 1.5F) {
                    u -= rotor * 0.45F;
                }
            }
        }
        // In front of a wall the air rises over it.
        if (speed > 1.0E-4F) {
            for (int d = 1; d <= 2; d++) {
                float h = terrain.height(Mth.floor(x + dirX * d), Mth.floor(z + dirZ * d)) - (float) y;
                if (h > 0.0F) {
                    float block = Math.min(1.0F, h) * (d == 1 ? 1.0F : 0.5F);
                    vy += wind * 0.7F * block;
                    u *= 1.0F - 0.5F * block;
                    break;
                }
            }
        }
        out[0] = dirX * u;
        out[1] = vy;
        out[2] = dirZ * u;
        if (turbulence) {
            addEddies(x, y, z, 1.0F + lee, out);
        }

        for (int w = 0; w < whirls.size(); w++) {
            addWhirl(whirls.get(w), x, y, z, out);
        }
        for (int g = 0; g < gusts.size(); g++) {
            addGust(gusts.get(g), x, y, z, out);
        }
        for (int b = 0; b < blasts.size(); b++) {
            blasts.get(b).add(x, y, z, out);
        }
        for (int w = 0; w < wakeCount; w++) {
            addWake(w * 7, x, y, z, out);
        }
    }

    /**
     * Eddies drifting with the wind, stronger in gusty weather (and by {@code boost}); calm air still moves a little.
     * Added to {@code out[0..2]}.
     */
    private void addEddies(double x, double y, double z, float boost, float[] out) {
        float amplitude = 0.006F + speed * 0.35F * (0.4F + 0.6F * weather.gustiness) * boost;
        double sx = (x - weather.driftX) / 3.5;
        double sy = y / 3.5 + time * 0.006;
        double sz = (z - weather.driftZ) / 3.5;
        out[0] += (Noise.value(sx, sy, sz, TURBULENCE_SEED) - 0.5F) * 2.0F * amplitude;
        out[1] += (Noise.value(sx, sy, sz, TURBULENCE_SEED + 1) - 0.5F) * 1.2F * amplitude;
        out[2] += (Noise.value(sx, sy, sz, TURBULENCE_SEED + 2) - 0.5F) * 2.0F * amplitude;
    }

    /** Only the eddies of the air at a point (for flakes that do not need the shape of the land), into {@code out[0..2]}. */
    public void eddies(double x, double y, double z, float[] out) {
        out[0] = 0.0F;
        out[1] = 0.0F;
        out[2] = 0.0F;
        addEddies(x, y, z, 1.0F, out);
    }

    private void addWake(int w, double x, double y, double z, float[] out) {
        double dy = y - wakes[w + 1];
        if (dy < -0.3 || dy > wakes[w + 6] + 0.6) {
            return;
        }
        double dx = x - wakes[w];
        double dz = z - wakes[w + 2];
        double reach = wakes[w + 5] * 2.2;
        double d2 = dx * dx + dz * dz;
        if (d2 > reach * reach) {
            return;
        }
        float falloff = (float) (1.0 - Math.sqrt(d2) / reach);
        falloff *= falloff;
        out[0] += (float) wakes[w + 3] * 0.8F * falloff;
        out[2] += (float) wakes[w + 4] * 0.8F * falloff;
        // Air pushed aside: outward around the body, a little upwards.
        double length = Math.sqrt(d2) + 1.0E-3;
        float push = (float) Math.sqrt(wakes[w + 3] * wakes[w + 3] + wakes[w + 4] * wakes[w + 4]) * 0.35F * falloff;
        out[0] += (float) (dx / length) * push;
        out[2] += (float) (dz / length) * push;
        out[1] += push * 0.4F;
    }

    /** Inside a squall: the wind is stronger along it, and the air rolls over - up at its front, down at its back. */
    private static void addGust(Gust gust, double x, double y, double z, float[] out) {
        float f = gust.inside(x, y, z) * gust.strength;
        if (f <= 0.0F) {
            return;
        }
        float push = gust.speed * 0.9F * f;
        out[0] += gust.dirX * push;
        out[2] += gust.dirZ * push;
        // Rolling about the horizontal axis across the gust.
        float along = (float) ((x - gust.x) * gust.dirX + (z - gust.z) * gust.dirZ) / gust.radius;
        float up = (float) (y - gust.y) / gust.height;
        float roll = gust.roll * gust.speed * 0.6F * f;
        out[1] += along * roll;
        out[0] -= gust.dirX * up * roll;
        out[2] -= gust.dirZ * up * roll;
    }

    private static void addWhirl(Whirl whirl, double x, double y, double z, float[] out) {
        float dx = (float) (x - whirl.x);
        float dz = (float) (z - whirl.z);
        float r = Mth.sqrt(dx * dx + dz * dz);
        float reach = whirl.radius * 2.5F;
        float h = (float) (y - whirl.y) / whirl.height;
        if (r >= reach || h < -0.2F || h > 1.15F || r < 1.0E-3F) {
            return;
        }
        float intensity = whirl.intensity();
        float radius = whirl.radius * (1.0F + 0.5F * Math.max(0.0F, h));
        float tangential = r < radius ? r / radius : radius / r * (1.0F - (r - radius) / (reach - radius));
        tangential *= whirl.strength * intensity;
        float nx = dx / r;
        float nz = dz / r;
        float height = Mth.clamp(h, 0.0F, 1.0F);
        float radial = tangential * (0.6F * height - 0.35F);
        float updraft = r < radius * 1.3F ? 0.16F * intensity * (1.0F - r / (radius * 1.3F)) * (1.0F - height * height) : 0.0F;
        out[0] += -nz * tangential * whirl.spin + nx * radial;
        out[1] += updraft;
        out[2] += nx * tangential * whirl.spin + nz * radial;
    }

    void reset() {
        gusts.clear();
        whirls.clear();
        blasts.clear();
        wakeCount = 0;
    }
}
