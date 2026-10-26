package com.pockyl.hoarfrost.mixin;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/**
 * The colour handlers already registered, so the frost on leaves can wrap them instead of replacing them. Forge keys them
 * by the block's registry holder.
 */
@Mixin(BlockColors.class)
public interface BlockColorsAccessor {
    @Accessor("blockColors")
    Map<Holder.Reference<Block>, BlockColor> hoarfrost$colors();
}
