package com.pockyl.hoarfrost.client.sound;

import net.minecraft.sounds.SoundEvent;

import com.pockyl.hoarfrost.Hoarfrost;

/**
 * The mod's sound events. They are not registered, only listed in sounds.json, so the client works on servers without
 * the mod; the client plays them directly.
 */
public final class HoarfrostSounds {
    /** A soft breeze carrying snow. */
    public static final SoundEvent BREEZE = event("ambient.breeze");
    /** A fresh, steady wind over open snow. */
    public static final SoundEvent WIND = event("ambient.wind");
    /** The roar of a snowstorm. */
    public static final SoundEvent STORM = event("ambient.storm");
    /** The howl of a storm around corners and edges. */
    public static final SoundEvent HOWL = event("ambient.howl");
    /** A gust sweeping past. */
    public static final SoundEvent GUST = event("ambient.gust");
    /** Feet in fresh powder: a soft, deep crunch. */
    public static final SoundEvent STEP_POWDER = event("step.snow.powder");
    /** Feet on trodden snow: a firm, squeaky crunch. */
    public static final SoundEvent STEP_PACKED = event("step.snow.packed");

    private HoarfrostSounds() {
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Hoarfrost.id(path));
    }
}
