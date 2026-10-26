package com.pockyl.hoarfrost.client.sound;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.ModList;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

import com.pockyl.hoarfrost.mixin.ChannelAccessor;
import com.pockyl.hoarfrost.mixin.SoundEngineAccessor;
import com.pockyl.hoarfrost.mixin.SoundManagerAccessor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Low-pass filters (OpenAL EFX) on sound sources:
 * <ul>
 *   <li>the hush of falling snow: fresh snow swallows the high, sharp part of every sound around, so under a snowfall
 *   the world sounds soft, close and quiet. Applied to the sounds of the world (blocks, mobs, players, ambience), not
 *   to music or the interface, and not to sounds of other mods that filter their own (Petrichor's rain);</li>
 *   <li>the mod's own wind, muffled when heard from indoors.</li>
 * </ul>
 * OpenAL calls run on the sound engine's thread through the channel handle, like the game's own. Without EFX, or with
 * Sound Physics Remastered (which filters every source itself), nothing is filtered.
 */
public final class Hush {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final float EPSILON = 0.015F;

    /** Implemented by the mod's sounds, which carry their own filter. */
    public interface Muffled {
        /** How much of the highs pass, 1 = all. */
        float highs();
    }

    /** The filter of the world's sounds right now; read on the sound thread. */
    private static volatile float worldHighs = 1.0F;
    private static volatile float worldGain = 1.0F;
    private static float sentHighs = 1.0F;
    private static float sentGain = 1.0F;
    // Touched only on the sound thread.
    private static int filter;
    private static boolean checked;
    private static boolean available;

    private Hush() {
    }

    public static boolean enabled() {
        return !ModList.get().isLoaded("sound_physics_remastered");
    }

    /** The sound engine restarted: the old filter is gone with the old context. */
    public static void reset() {
        filter = 0;
        checked = false;
        available = false;
        sentHighs = 1.0F;
        sentGain = 1.0F;
    }

    /** Whether the hush applies to a sound: sounds of the world, but not music, the interface or self-filtered sounds. */
    private static boolean hushed(SoundInstance sound) {
        if (sound instanceof Muffled) {
            return false;
        }
        SoundSource source = sound.getSource();
        if (source == SoundSource.MASTER || source == SoundSource.MUSIC) {
            return false;
        }
        return !sound.getLocation().getNamespace().equals("petrichor");
    }

    /** A sound got its source and is about to start: set its filter right away. Sound thread. */
    public static void onSourceReady(SoundInstance sound, Channel channel) {
        if (!enabled()) {
            return;
        }
        int source = ((ChannelAccessor) channel).hoarfrost$source();
        if (sound instanceof Muffled muffled) {
            apply(source, muffled.highs(), 1.0F);
        } else if (worldHighs < 0.999F && hushed(sound)) {
            apply(source, worldHighs, worldGain);
        }
    }

    /**
     * Sets the hush of the world's sounds; playing sounds follow once it changed noticeably. Main thread.
     *
     * @param amount 0 (no hush) .. 1 (the deep hush of a heavy snowfall)
     */
    public static void setWorld(float amount) {
        float a = Mth.clamp(amount, 0.0F, 1.0F);
        float highs = 1.0F - 0.6F * a;
        float gain = 1.0F - 0.25F * a;
        worldHighs = highs;
        worldGain = gain;
        if (!enabled() || Math.abs(highs - sentHighs) < EPSILON && Math.abs(gain - sentGain) < EPSILON) {
            return;
        }
        sentHighs = highs;
        sentGain = gain;
        List<ChannelAccess.ChannelHandle> handles = new ArrayList<>();
        for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : channels().entrySet()) {
            if (hushed(entry.getKey())) {
                handles.add(entry.getValue());
            }
        }
        for (ChannelAccess.ChannelHandle handle : handles) {
            handle.execute(channel -> apply(((ChannelAccessor) channel).hoarfrost$source(), highs, gain));
        }
    }

    /**
     * Sends the current filter of one of the mod's playing sounds to its source.
     *
     * @return the value now in effect
     */
    public static float update(SoundInstance sound, float highs, float applied) {
        if (Math.abs(highs - applied) < 0.01F || !enabled()) {
            return applied;
        }
        ChannelAccess.ChannelHandle handle = channels().get(sound);
        if (handle == null) {
            return applied;
        }
        handle.execute(channel -> apply(((ChannelAccessor) channel).hoarfrost$source(), highs, 1.0F));
        return highs;
    }

    private static Map<SoundInstance, ChannelAccess.ChannelHandle> channels() {
        SoundEngine engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).hoarfrost$engine();
        return ((SoundEngineAccessor) engine).hoarfrost$channels();
    }

    private static void apply(int source, float highs, float gain) {
        if (!checked) {
            checked = true;
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            available = device != 0L && ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
            if (available) {
                filter = EXTEfx.alGenFilters();
                EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    available = false;
                }
            }
            LOGGER.info("Snow hush {}", available ? "uses OpenAL EFX low-pass filters" : "is unavailable (no OpenAL EFX)");
        }
        if (!available) {
            return;
        }
        if (highs >= 0.999F && gain >= 0.999F) {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        } else {
            // A filter is copied into the source when attached, so one filter object serves every source.
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, Mth.clamp(gain, 0.0F, 1.0F));
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Mth.clamp(highs, 0.0F, 1.0F));
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
        }
        AL10.alGetError();
    }
}
