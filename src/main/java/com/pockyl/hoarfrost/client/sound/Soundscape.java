package com.pockyl.hoarfrost.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.client.ClientWeather;
import com.pockyl.hoarfrost.weather.SnowConditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The sound of snowy weather, which is mostly the sound of its absence:
 * <ul>
 *   <li>light snow is silent - no wind, no ambience; only the hush of falling snow, which muffles the world;</li>
 *   <li>snow brings a soft breeze; heavy snow a breeze with soft gusts; large flakes fall in a deep hush, barely a
 *   breath of air;</li>
 *   <li>blizzards grow from a fresh wind to the roar of a storm with a howl around every edge; the wind swells in gusts
 *   and drops in lulls, and single gusts rush past from upwind and fade away downwind;</li>
 *   <li>part of the wind comes from upwind, so the ear can tell where it blows from without it sitting in one ear;</li>
 *   <li>indoors the storm is a muffled roar outside the walls; underground there is nothing.</li>
 * </ul>
 * Footsteps in snow are swapped in by {@link #stepSound}.
 */
public final class Soundscape {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final int BURIED_SCAN = 6;
    /** Share of the wind that comes from upwind; the rest is all around. */
    private static final float DIRECTED = 0.35F;
    private static final float BREEZE_GAIN = 1.0F;
    private static final float WIND_GAIN = 0.8F;
    private static final float STORM_GAIN = 0.85F;
    private static final float HOWL_GAIN = 0.7F;
    private static final float GUST_GAIN = 0.75F;

    private static final Layer BREEZE = new Layer(HoarfrostSounds.BREEZE);
    private static final Layer WIND = new Layer(HoarfrostSounds.WIND);
    private static final Layer STORM = new Layer(HoarfrostSounds.STORM);
    private static final Layer HOWL = new Layer(HoarfrostSounds.HOWL);
    private static final Layer[] LAYERS = {BREEZE, WIND, STORM, HOWL};
    private static final List<GustSound> GUSTS = new ArrayList<>();
    private static float outside = 1.0F;
    private static float outsideTarget = 1.0F;
    private static boolean covered;
    private static float upwindGust = 1.0F;
    private static boolean rising;
    private static int gustCooldown;
    private static int ticks;

    private Soundscape() {
    }

    /** One layer of the wind: all around the listener, and a quieter part from upwind. */
    private static final class Layer {
        final SoundEvent event;
        LoopSound around;
        LoopSound upwind;
        float volume;

        Layer(SoundEvent event) {
            this.event = event;
        }

        void drive(float target, double x, double y, double z, float highs) {
            volume = target;
            around = LoopSound.drive(around, event, target * (1.0F - DIRECTED), x, y, z, highs, true, RANDOM);
            upwind = LoopSound.drive(upwind, event, target * DIRECTED, x, y, z, highs, false, RANDOM);
        }

        void stop(SoundManager sounds) {
            if (around != null) {
                sounds.stop(around);
                around = null;
            }
            if (upwind != null) {
                sounds.stop(upwind);
                upwind = null;
            }
            volume = 0.0F;
        }
    }

    public static void tick(ClientLevel level, Vec3 eye) {
        ticks++;
        if (ticks % 10 == 1) {
            survey(level, eye);
        }
        outside += (outsideTarget - outside) * 0.1F;
        SnowConditions weather = ClientWeather.conditions();
        float snow = ClientWeather.snow();
        float hush = (float) (double) ClientConfig.HUSH.get() * weather.hush * snow * outside * (covered ? 0.4F : 1.0F);
        Hush.setWorld(hush);

        float loud = snow * outside * (float) (double) ClientConfig.WIND_VOLUME.get() * (covered ? 0.5F : 1.0F);
        float highs = covered ? 0.22F : 1.0F;
        float gust = ClientWeather.gust();
        // Gusts swell the wind and lulls let it sink, more so the stormier it is.
        float swell = Mth.lerp(weather.gustiness, 1.0F, Math.clamp(0.55F + 0.45F * gust, 0.35F, 1.5F));
        float howl = Math.clamp(0.35F + (gust - 0.9F) * 1.2F, 0.0F, 1.2F);
        float speed = weather.windSpeed;
        double ux = speed > 1.0E-4F ? -weather.windDirX : 1.0;
        double uz = speed > 1.0E-4F ? -weather.windDirZ : 0.0;
        double x = eye.x + ux * 10.0;
        double y = eye.y + 2.0;
        double z = eye.z + uz * 10.0;
        BREEZE.drive(loud * weather.breeze * BREEZE_GAIN * Mth.lerp(0.5F, 1.0F, swell), x, y, z, highs);
        WIND.drive(loud * weather.windSound * WIND_GAIN * swell, x, y, z, highs);
        STORM.drive(loud * weather.storm * STORM_GAIN * swell, x, y, z, highs);
        HOWL.drive(loud * weather.howl * HOWL_GAIN * howl, x, y, z, highs * 0.9F);
        gusts(eye, weather, loud, highs);
    }

    /** A gust front coming in from upwind is heard rushing past. */
    private static void gusts(Vec3 eye, SnowConditions weather, float loud, float highs) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        GUSTS.removeIf(GustSound::isStopped);
        for (GustSound sound : GUSTS) {
            sound.hear(loud, highs);
        }
        if (gustCooldown > 0) {
            gustCooldown--;
        }
        float rate = weather.gusts;
        if (rate <= 0.01F || loud < 0.02F) {
            return;
        }
        double upX = eye.x - weather.windDirX * 24.0;
        double upZ = eye.z - weather.windDirZ * 24.0;
        float ahead = weather.gustAt(upX, upZ);
        boolean nowRising = ahead > upwindGust;
        if (gustCooldown == 0 && rising && !nowRising && ahead > 1.2F && RANDOM.nextFloat() < rate) {
            gustCooldown = 40 + RANDOM.nextInt(60);
            float travel = Math.max(0.45F, weather.windSpeed * 1.3F);
            double side = (RANDOM.nextFloat() - 0.5F) * 16.0;
            double startX = upX - weather.windDirZ * side;
            double startZ = upZ + weather.windDirX * side;
            int life = Math.round(48.0F / travel);
            float gain = Math.min(1.0F, GUST_GAIN * (ahead - 0.7F) * (0.5F + rate * 0.5F));
            GustSound sound = new GustSound(startX, eye.y + 1.5, startZ, weather.windDirX, weather.windDirZ, travel, gain, life, RANDOM);
            sound.hear(loud, highs);
            GUSTS.add(sound);
            sounds.play(sound);
        }
        rising = nowRising;
        upwindGust = ahead;
    }

    /**
     * Outdoors is where the sky light reaches, or under no more than a building's floors and roof; below that, rock.
     * Indoors (under a roof) the storm is a muffled roar outside.
     */
    private static void survey(ClientLevel level, Vec3 eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(eye.x), Mth.floor(eye.y), Mth.floor(eye.z));
        int light = level.getBrightness(LightLayer.SKY, pos);
        float sky = Mth.clamp((light - 1) / 14.0F, 0.0F, 1.0F);
        sky *= sky;
        int ey = pos.getY();
        int height = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        covered = height > eye.y + 0.5;
        int top = Math.min(height, ey + 1 + BURIED_SCAN);
        int layers = 0;
        for (int y = ey + 1; y < top; y++) {
            pos.setY(y);
            if (level.getBlockState(pos).canOcclude()) {
                layers++;
            }
        }
        if (top == ey + 1 + BURIED_SCAN) {
            layers = BURIED_SCAN;
        }
        outsideTarget = Math.max(sky, layers <= 2 ? 1.0F : 0.0F);
    }

    /**
     * The step sound to play instead of a vanilla footstep on snow, or null to keep it: fresh powder crunches softly,
     * trodden snow and thin snow squeak.
     *
     * @param depth  snow lying at the foot, in blocks
     * @param packed how trodden it is, 0..1
     */
    public static SoundEvent stepSound(float depth, float packed, boolean vanillaSnow) {
        if (!ClientConfig.SNOW_STEPS.get()) {
            return null;
        }
        if (depth >= 0.03F) {
            return packed > 0.6F ? HoarfrostSounds.STEP_PACKED : HoarfrostSounds.STEP_POWDER;
        }
        return vanillaSnow ? HoarfrostSounds.STEP_POWDER : null;
    }

    public static String debugSummary() {
        return String.format(Locale.ROOT, "outside %.2f%s, wind %.2f/%.2f/%.2f/%.2f, gusts %d", outside, covered ? " (roof)" : "",
                BREEZE.volume, WIND.volume, STORM.volume, HOWL.volume, GUSTS.size());
    }

    public static void stopAll() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        for (Layer layer : LAYERS) {
            layer.stop(sounds);
        }
        for (GustSound gust : GUSTS) {
            sounds.stop(gust);
        }
        GUSTS.clear();
        Hush.setWorld(0.0F);
    }
}
