package com.pockyl.hoarfrost.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pockyl.hoarfrost.sim.Terrain;

/**
 * Real snow drifts on the server: while snow falls with wind, snow layers pile up where the wind drops its load - in
 * the lee of walls, trees and hills (deepest right behind them, tapering off over a wake several times as long as the
 * obstacle is high) and at the foot of walls facing the wind. Open, windswept ground gets nothing beyond what vanilla
 * snowfall adds. Only ever adds layers (never removes blocks), only under the open sky, never on leaves.
 */
public final class DriftBuilder {
    private static final int LEE_REACH = 8;

    private DriftBuilder() {
    }

    /** How many snow layers the wind would pile on the column (x, z), 0 when it is not a drift spot. */
    public static int targetLayers(ServerLevel level, int x, int z, float dirX, float dirZ, int maxLayers) {
        int ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        float lee = 0.0F;
        for (int d = 1; d <= LEE_REACH; d++) {
            int upwindX = Mth.floor(x + 0.5F - dirX * d);
            int upwindZ = Mth.floor(z + 0.5F - dirZ * d);
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, upwindX, upwindZ) - ground;
            if (h > 0) {
                lee = Math.max(lee, Terrain.leeStrength(h, d));
            }
        }
        float front = 0.0F;
        int ahead = level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x + 0.5F + dirX), Mth.floor(z + 0.5F + dirZ)) - ground;
        if (ahead > 0) {
            front = Math.min(ahead, 2) * 0.5F;
        }
        float strength = Math.max(lee * 0.95F, front * 0.75F);
        return Mth.clamp(Math.round(strength * maxLayers), 0, maxLayers);
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
        Biome biome = level.getBiome(pos).value();
        if (!anyClimate && (!biome.hasPrecipitation() || biome.getPrecipitationAt(pos) != Biome.Precipitation.SNOW)) {
            return false;
        }
        int target = targetLayers(level, x, z, dirX, dirZ, maxLayers);
        if (layers >= target) {
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
