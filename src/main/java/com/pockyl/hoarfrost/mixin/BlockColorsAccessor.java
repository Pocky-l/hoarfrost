package com.pockyl.hoarfrost.mixin;

import net.minecraft.client.color.block.BlockColor;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

/** The colour handlers already registered, so the frost on leaves can wrap them instead of replacing them. */
@Mixin(BlockColors.class)
public interface BlockColorsAccessor {
    @Accessor("blockColors")
    Map<Block, BlockColor> hoarfrost$colors();
}
