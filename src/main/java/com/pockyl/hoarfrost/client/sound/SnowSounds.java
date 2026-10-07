package com.pockyl.hoarfrost.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.client.ClientSnow;

/**
 * The sound of snow, which is mostly the sound of its absence:
 * <ul>
 *   <li>the hush - falling snow muffles the world outdoors, deeper in calm, heavy snowfall than in a windy one;</li>
 *   <li>the wind of the snowfall from where it blows: a soft breeze in a snowfall, the roar of a blizzard with gusts
 *   sweeping past; indoors a muffled howl outside the walls, underground nothing;</li>
 * </ul>
 * Footsteps in snow are swapped in by {@link #stepSound}.
 */
public final class SnowSounds {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final float LIGHT_GAIN = 0.3F;
    private static final float STRONG_GAIN = 0.85F;
    private static final float GUST_GAIN = 0.55F;
    private static final int BURIED_SCAN = 6;

    private static LoopSound light;
    private static LoopSound strong;
    private static float outside = 1.0F;
    private static float outsideTarget = 1.0F;
    private static boolean covered;
    private static boolean wasRising;
    private static float lastGust = 1.0F;
    private static int gustCooldown;
    private static int ticks;

    private SnowSounds() {
    }

    public static void tick(ClientLevel level, Vec3 eye) {
        ticks++;
        if (ticks % 10 == 1) {
            survey(level, eye);
        }
        outside += (outsideTarget - outside) * 0.1F;
        float snow = ClientSnow.snow();
        float hush = (float) (double) ClientConfig.HUSH.get() * ClientSnow.hush * snow * outside * (covered ? 0.35F : 1.0F);
        Hush.setWorld(hush);

        float speed = ClientSnow.windSpeed();
        float stormy = (float) Math.pow(Mth.clamp((speed - 0.08F) / 0.3F, 0.0F, 1.0F), 1.2);
        float breezy = Mth.clamp(speed / 0.06F, 0.0F, 1.0F) * (1.0F - 0.7F * stormy);
        float volume = snow * outside * (float) (double) ClientConfig.WIND_VOLUME.get() * (covered ? 0.55F : 1.0F);
        float highs = covered ? 0.2F : 1.0F;
        double ux = speed > 1.0E-4F ? -ClientSnow.windX() / speed : 1.0;
        double uz = speed > 1.0E-4F ? -ClientSnow.windZ() / speed : 0.0;
        double x = eye.x + ux * 10.0;
        double y = eye.y + 2.0;
        double z = eye.z + uz * 10.0;
        float gust = ClientSnow.gust();
        light = drive(light, HoarfrostSounds.WIND_LIGHT, volume * breezy * LIGHT_GAIN, x, y, z, highs);
        strong = drive(strong, HoarfrostSounds.WIND_STRONG, volume * stormy * STRONG_GAIN * (float) Math.sqrt(Math.max(0.3F, gust)), x, y,
                z, highs);

        // A gust peaking in a storm sweeps past.
        boolean rising = gust > lastGust;
        if (gustCooldown > 0) {
            gustCooldown--;
        } else if (wasRising && !rising && gust > 1.1F && stormy > 0.2F && volume > 0.05F) {
            gustCooldown = 50 + RANDOM.nextInt(80);
            double side = (RANDOM.nextFloat() - 0.5F) * 8.0;
            float gain = Math.min(1.0F, volume * stormy * GUST_GAIN * (gust - 0.6F));
            Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(HoarfrostSounds.GUST.getLocation(), SoundSource.WEATHER,
                    gain, 0.85F + RANDOM.nextFloat() * 0.3F, RANDOM, false, 0, SoundInstance.Attenuation.NONE,
                    x - uz * side, y, z + ux * side, false));
        }
        wasRising = rising;
        lastGust = gust;
    }

    private static LoopSound drive(LoopSound loop, SoundEvent sound, float volume, double x, double y, double z, float highs) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        if (loop != null && (loop.isStopped() || !sounds.isActive(loop))) {
            loop = null;
        }
        if (loop == null) {
            if (volume < 0.004F) {
                return null;
            }
            loop = new LoopSound(sound, x, y, z, highs, RANDOM);
            sounds.play(loop);
        }
        loop.setTarget(volume, x, y, z, highs);
        return loop;
    }

    /**
     * Outdoors is where the sky light reaches, or under no more than a building's floors and roof; below that, rock.
     * Indoors (under a roof) the storm is a muffled howl outside.
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
     * The step sound to play instead of a vanilla footstep on snow, or null to keep it: deep fresh powder crunches
     * softly, packed snow and thin snow squeak.
     *
     * @param depth snow lying at the foot, in blocks
     * @param packedShare how much of it is packed, 0..1
     */
    public static SoundEvent stepSound(float depth, float packedShare, boolean vanillaSnow) {
        if (!ClientConfig.SNOW_STEPS.get()) {
            return null;
        }
        if (depth >= 0.03F) {
            return packedShare > 0.6F ? HoarfrostSounds.STEP_PACKED : HoarfrostSounds.STEP_POWDER;
        }
        return vanillaSnow ? HoarfrostSounds.STEP_POWDER : null;
    }

    public static float outside() {
        return outside;
    }

    public static String debugSummary() {
        return String.format("outside %.2f%s, wind %s/%s", outside, covered ? " (roof)" : "", light == null ? "-" : "light",
                strong == null ? "-" : "strong");
    }

    public static void stopAll() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        if (light != null) {
            sounds.stop(light);
            light = null;
        }
        if (strong != null) {
            sounds.stop(strong);
            strong = null;
        }
        Hush.setWorld(0.0F);
    }
}
