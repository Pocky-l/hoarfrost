package com.pockyl.hoarfrost.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * A gust heard where it is: it comes from upwind, rushes past the listener with the wind and fades away downwind.
 * Muffled indoors like the rest of the wind.
 */
final class GustSound extends AbstractTickableSoundInstance implements Hush.Muffled {
    private final float dirX;
    private final float dirZ;
    private final float speed;
    private final float gain;
    private final int life;
    private int age;
    private float loudness = 1.0F;
    private float highs = 1.0F;
    private float targetHighs = 1.0F;
    private float appliedHighs = 1.0F;

    GustSound(double x, double y, double z, float dirX, float dirZ, float speed, float gain, int life, RandomSource random) {
        super(HoarfrostSounds.GUST, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.speed = speed;
        this.gain = gain;
        this.life = life;
        looping = false;
        delay = 0;
        volume = 0.0001F;
        pitch = 0.85F + random.nextFloat() * 0.3F;
        attenuation = Attenuation.LINEAR;
        this.x = x;
        this.y = y;
        this.z = z;
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
        if (++age >= life) {
            stop();
            return;
        }
        x += dirX * speed;
        z += dirZ * speed;
        float envelope = Mth.sin((float) Math.PI * age / life);
        volume = Math.max(0.0001F, gain * envelope * loudness);
        highs += Math.clamp(targetHighs - highs, -0.04F, 0.04F);
        appliedHighs = Hush.update(this, highs, appliedHighs);
    }
}
