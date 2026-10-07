package com.pockyl.hoarfrost.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import com.pockyl.hoarfrost.sim.AirField;

/**
 * The roar of a squall of snow, coming from where the squall is and moving with it: it rises as the cloud comes
 * closer, sweeps past and fades behind. Muffled indoors like the rest of the wind.
 */
final class SquallSound extends AbstractTickableSoundInstance implements Hush.Muffled {
    private static final float GAIN = 0.8F;

    private final AirField.Gust gust;
    private float highs = 1.0F;
    private float appliedHighs = 1.0F;
    private float targetHighs = 1.0F;
    private float loudness = 1.0F;

    SquallSound(AirField.Gust gust, RandomSource random) {
        super(HoarfrostSounds.SQUALL, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        this.gust = gust;
        looping = true;
        delay = 0;
        volume = 0.0001F;
        pitch = 0.9F + random.nextFloat() * 0.25F;
        attenuation = Attenuation.LINEAR;
        x = gust.x;
        y = gust.y;
        z = gust.z;
    }

    AirField.Gust gust() {
        return gust;
    }

    /** Volume factor (wind volume, how much of the outdoors reaches the ear) and muffling, set every tick. */
    void hear(float loudness, float highs) {
        this.loudness = loudness;
        this.targetHighs = highs;
    }

    @Override
    public float highs() {
        return highs;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        if (gust.done()) {
            stop();
            return;
        }
        x = gust.x;
        y = gust.y;
        z = gust.z;
        volume = Math.max(0.0001F, gust.envelope() * gust.strength * GAIN * loudness);
        highs += Math.clamp(targetHighs - highs, -0.04F, 0.04F);
        appliedHighs = Hush.update(this, highs, appliedHighs);
    }
}
