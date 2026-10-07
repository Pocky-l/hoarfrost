package com.pockyl.hoarfrost.sim;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * What snow lies on, and how that changes it:
 * <ul>
 *   <li>ground (earth, stone, wood): snow builds up deep and fluffy, feet sink into it;</li>
 *   <li>crowns of trees: they catch a cap of snow, never deep;</li>
 *   <li>old snow (vanilla snow layers, snow and powder snow blocks): fresh powder on top;</li>
 *   <li>ice: smooth and cold - only a thin dusting stays, the wind sweeps it off easily, feet only scuff it;</li>
 *   <li>glass and other smooth tops: a thin layer that slides and blows off easily;</li>
 *   <li>hot blocks (magma, lit campfires and furnaces, fire): snow melts as it lands.</li>
 * </ul>
 */
public final class SurfaceKind {
    public static final byte GROUND = 0;
    public static final byte LEAVES = 1;
    public static final byte ON_SNOW = 2;
    public static final byte ICE = 3;
    public static final byte SMOOTH = 4;
    public static final byte HOT = 5;

    /** Deepest snow that settles out of the air on open, windless ground of each kind. */
    private static final float[] NATURAL = {0.42F, 0.12F, 0.32F, 0.05F, 0.08F, 0.0F};
    /** Wind needed to move the loose snow, relative to ground. */
    private static final float[] GRIP = {1.0F, 1.0F, 1.0F, 0.5F, 0.7F, 1.0F};
    /** Share of the loose snow a foot packs down instead of pushing aside (ice: a scuff, the snow skids away). */
    private static final float[] PACKING = {0.3F, 0.3F, 0.3F, 0.15F, 0.2F, 0.3F};

    private SurfaceKind() {
    }

    public static byte of(BlockState state) {
        if (state.is(BlockTags.LEAVES)) {
            return LEAVES;
        }
        if (state.getBlock() instanceof SnowLayerBlock || state.is(Blocks.SNOW_BLOCK) || state.is(Blocks.POWDER_SNOW)) {
            return ON_SNOW;
        }
        if (state.is(BlockTags.ICE)) {
            return ICE;
        }
        if (state.is(Blocks.MAGMA_BLOCK) || state.is(BlockTags.FIRE) || state.is(Blocks.LAVA_CAULDRON)
                || state.getBlock() instanceof CampfireBlock && state.getValue(CampfireBlock.LIT)
                || state.getBlock() instanceof AbstractFurnaceBlock && state.getValue(AbstractFurnaceBlock.LIT)) {
            return HOT;
        }
        if (state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES)) {
            return SMOOTH;
        }
        return GROUND;
    }

    private static int index(byte kind) {
        return kind >= 0 && kind < NATURAL.length ? kind : 0;
    }

    public static float natural(byte kind) {
        return NATURAL[index(kind)];
    }

    public static float grip(byte kind) {
        return GRIP[index(kind)];
    }

    public static float packing(byte kind) {
        return PACKING[index(kind)];
    }
}
