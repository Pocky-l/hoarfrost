package com.pockyl.hoarfrost.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * A looping wind sound coming from a direction. Volume, position and muffling glide towards their targets, so the wind
 * swings round smoothly when it turns, a door opening lets the storm in over a second, and the loop stops itself once
 * silent.
 */
final class LoopSound extends AbstractTickableSoundInstance implements Hush.Muffled {
    private static final float VOLUME_STEP = 0.015F;
    private static final float MUFFLE_STEP = 0.04F;
    private static final double MOVE = 0.1;
    private float target;
    private double targetX;
    private double targetY;
    private double targetZ;
    private float highs;
    private float targetHighs;
    private float appliedHighs;
    private int silentTicks;

    LoopSound(SoundEvent event, double x, double y, double z, float highs, RandomSource random) {
        super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        volume = 0.0001F;
        pitch = 0.96F + random.nextFloat() * 0.08F;
        // The position only gives the direction: the wind is everywhere, its loudness is the caller's.
        attenuation = Attenuation.NONE;
        this.x = targetX = x;
        this.y = targetY = y;
        this.z = targetZ = z;
        this.highs = targetHighs = appliedHighs = highs;
    }

    void setTarget(float volume, double x, double y, double z, float highs) {
        target = Math.clamp(volume, 0.0F, 1.0F);
        targetX = x;
        targetY = y;
        targetZ = z;
        targetHighs = highs;
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
        volume += Math.clamp(target - volume, -VOLUME_STEP, VOLUME_STEP);
        highs += Math.clamp(targetHighs - highs, -MUFFLE_STEP, MUFFLE_STEP);
        x += (targetX - x) * MOVE;
        y += (targetY - y) * MOVE;
        z += (targetZ - z) * MOVE;
        appliedHighs = Hush.update(this, highs, appliedHighs);
        if (target <= 0.0F && volume <= 0.001F) {
            if (++silentTicks > 40) {
                stop();
            }
        } else {
            silentTicks = 0;
        }
    }
}
