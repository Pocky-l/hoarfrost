package com.pockyl.hoarfrost.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where snow lies on a leaves block in a snowy biome: a cap on its top when nothing covers it and the sky is open above,
 * and a band hanging over each side of a capped block that faces the open air. Leaves deeper in the crown stay green,
 * so a tree reads as green foliage with snow on it, not as a white block.
 *
 * <p>Common code: the client builds the chunk meshes from it, the game tests check it on the server.
 */
public final class LeafSnow {
    /** Like Bedrock's white leaves: biomes colder than this are snowy. */
    public static final float COLD = 0.15F;

    /** 0xFF where the biome is snowy, 0 elsewhere; blended over the biome border like grass colours. */
    public static final ColorResolver SNOWY = (biome, x, z) -> biome.getBaseTemperature() < COLD ? 0xFF : 0;

    /** Sky light above a top that still gets snow: it falls through a leaf layer or two, not under a roof. */
    private static final int SKY = 13;

    public static final int TOP = 1;
    /** Bits of the four sides, {@code 1 << direction.get3DDataValue()} (north 4, south 8, west 16, east 32). */
    public static final int SIDES = 0b111100;
    private static final int ROTATION_SHIFT = 6;
    public static final int MIRROR = 1 << 8;

    private LeafSnow() {
    }

    /** The snow on the leaves block at {@code pos}, 0 for none. Over a biome border the snowy blocks thin out. */
    public static int mask(BlockAndTintGetter level, BlockPos pos) {
        float snowy = (level.getBlockTint(pos, SNOWY) & 0xFF) / 255.0F;
        if (snowy <= 0.0F || snowy < 1.0F && hash(pos, 0x51) >= snowy) {
            return 0;
        }
        return shape(level, pos);
    }

    /** The snow on a leaves block if its biome is snowy: which faces carry it and how its texture is turned. */
    public static int shape(BlockAndTintGetter level, BlockPos pos) {
        BlockPos above = pos.above();
        if (covers(level, above, level.getBlockState(above)) || level.getBrightness(LightLayer.SKY, above) < SKY) {
            return 0;
        }
        int mask = TOP;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos next = pos.relative(side);
            if (!covers(level, next, level.getBlockState(next))) {
                mask |= side(side);
            }
        }
        int variant = Mth.floor(hash(pos, 0x7A) * 8.0F);
        return mask | (variant & 3) << ROTATION_SHIFT | (variant & 4) << ROTATION_SHIFT;
    }

    public static int side(Direction side) {
        return 1 << side.get3DDataValue();
    }

    /** Quarter turns of the top texture. */
    public static int rotation(int mask) {
        return mask >> ROTATION_SHIFT & 3;
    }

    /** Leaves or a full block hide the face next to them. */
    private static boolean covers(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof LeavesBlock || state.isSolidRender(level, pos);
    }

    /** A stable value in [0, 1) per block. */
    private static float hash(BlockPos pos, int salt) {
        long h = Mth.getSeed(pos.getX(), pos.getY(), pos.getZ()) ^ salt * 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return (h >>> 40) / (float) (1L << 24);
    }
}
