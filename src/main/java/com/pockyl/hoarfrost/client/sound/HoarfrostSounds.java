package com.pockyl.hoarfrost.client.sound;

import net.minecraft.sounds.SoundEvent;

import com.pockyl.hoarfrost.Hoarfrost;

/**
 * The mod's sound events. They are not registered, only listed in sounds.json, so the client works on servers without
 * the mod; the client plays them directly.
 */
public final class HoarfrostSounds {
    /** A breeze carrying snow, soft and airy. */
    public static final SoundEvent WIND_LIGHT = event("ambient.wind.light");
    /** The roar of a snowstorm. */
    public static final SoundEvent WIND_STRONG = event("ambient.wind.strong");
    /** The roar of a squall of snow passing by. */
    public static final SoundEvent SQUALL = event("ambient.wind.squall");
    /** A single gust sweeping past. */
    public static final SoundEvent GUST = event("ambient.wind.gust");
    /** Feet in fresh powder: a soft, deep crunch. */
    public static final SoundEvent STEP_POWDER = event("step.snow.powder");
    /** Feet on packed snow: a firm, squeaky crunch. */
    public static final SoundEvent STEP_PACKED = event("step.snow.packed");

    private HoarfrostSounds() {
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Hoarfrost.id(path));
    }
}
