package com.pockyl.hoarfrost.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.sim.CoverSurvey;

/**
 * Real snow drifts on the server: while snow falls with wind, snow layers pile up where the wind drops its load - in the
 * lee of walls, trees and hills, deepest a little behind them and tapering off over a few times their height - the
 * same lee the snow cover of the clients uses. Open, windswept ground gets nothing beyond what vanilla snowfall adds.
 * Only ever adds layers (never removes blocks), only under the open sky, never on leaves.
 */
public final class DriftBuilder {
    private static final int LEE_REACH = 10;

    private DriftBuilder() {
    }

    /** How strongly the wind piles snow on the column (x, z): 0 in the open, up to 1 in a deep lee. */
    public static float lee(ServerLevel level, int x, int z, float dirX, float dirZ) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        float lee = 0.0F;
        for (int d = 1; d <= LEE_REACH; d++) {
            int upwindX = Mth.floor(x + 0.5F - dirX * d);
            int upwindZ = Mth.floor(z + 0.5F - dirZ * d);
            int solid = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, upwindX, upwindZ);
            int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, upwindX, upwindZ);
            float height = solid + (top - solid) * 0.55F - ground;
            if (height > 0.4F) {
                lee = Math.max(lee, CoverSurvey.leeStrength(height, d));
            }
        }
        return lee;
    }

    /** How many snow layers the wind piles on the column. */
    public static int targetLayers(ServerLevel level, int x, int z, float dirX, float dirZ, int maxLayers) {
        float lee = lee(level, x, z, dirX, dirZ);
        return Mth.clamp(Math.round((lee - 0.15F) / 0.75F * maxLayers), 0, maxLayers);
    }

    /**
     * Adds one snow layer to the column if it is a drift spot that is not full yet.
     *
     * @param anyClimate also outside snowy land (game tests run in warm land)
     * @return whether a layer was added
     */
    public static boolean grow(ServerLevel level, int x, int z, float dirX, float dirZ, int maxLayers, boolean anyClimate) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState at = level.getBlockState(pos);
        int layers = 0;
        if (at.getBlock() instanceof SnowLayerBlock) {
            layers = at.getValue(SnowLayerBlock.LAYERS);
        } else {
            BlockState below = level.getBlockState(pos.below());
            if (below.getBlock() instanceof SnowLayerBlock) {
                // Thicker layers count as ground in the heightmap: the drift grows inside that block.
                layers = below.getValue(SnowLayerBlock.LAYERS);
                pos.move(0, -1, 0);
            } else if (below.is(BlockTags.LEAVES) || !at.isAir()) {
                return false;
            }
        }
        if (layers >= 8 || !level.canSeeSky(pos)) {
            return false;
        }
        if (!anyClimate && !Climate.snowsAt(level, pos)) {
            return false;
        }
        if (layers >= targetLayers(level, x, z, dirX, dirZ, maxLayers)) {
            return false;
        }
        BlockState next = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers + 1);
        if (layers == 0 && !next.canSurvive(level, pos)) {
            return false;
        }
        level.setBlockAndUpdate(pos, next);
        return true;
    }
}
