package com.pockyl.hoarfrost.mixin;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The OpenAL source behind a channel, to attach the low-pass filter of the snow's hush. The game keeps it private and
 * NeoForge has no API for source filters.
 */
@Mixin(Channel.class)
public interface ChannelAccessor {
    @Accessor("source")
    int hoarfrost$source();
}
