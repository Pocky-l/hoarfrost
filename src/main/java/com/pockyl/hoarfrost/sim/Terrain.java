package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * Block queries of the snow: collision against points, the surface snow settles on, and per-column facts cached in a
 * world-anchored ring of 128x128 columns - the height of the column, whether it snows there, and how sheltered from
 * the wind its ground is.
 *
 * <p>Shelter is what makes drifts: the ground behind an obstacle (as seen from the wind) is in its lee, where the wind
 * drops and the snow it carries settles; the foot of a wall facing the wind catches snow too (the air rolls down in
 * front of it). Both depend on the wind direction, which turns slowly: the cache is refreshed when it turns by more
 * than a sixteenth of a circle.
 */
public final class Terrain {
    private static final int SIZE = 128;
    private static final int MASK = SIZE - 1;
    private static final int EXPIRE = 60;
    private static final int LEE_REACH = 8;
    private static final int SECTORS = 16;

    private final ShapeCache shapes = new ShapeCache();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos lightCursor = new BlockPos.MutableBlockPos();
    private final long[] keys = new long[SIZE * SIZE];
    private final int[] stamps = new int[SIZE * SIZE];
    private final int[] heights = new int[SIZE * SIZE];
    private final boolean[] snowy = new boolean[SIZE * SIZE];
    private final long[] shelterKeys = new long[SIZE * SIZE];
    private final float[] shelter = new float[SIZE * SIZE];
    private final float[] windward = new float[SIZE * SIZE];
    private Level level;
    private int now;
    private int sector = -1;
    private boolean forceSnow;
    private float windX = 1.0F;
    private float windZ;

    public Terrain() {
        Arrays.fill(keys, Long.MIN_VALUE);
        Arrays.fill(shelterKeys, Long.MIN_VALUE);
    }

    public void begin(Level level, int tick) {
        if (this.level != level) {
            this.level = level;
            Arrays.fill(keys, Long.MIN_VALUE);
            Arrays.fill(shelterKeys, Long.MIN_VALUE);
        }
        now = tick;
    }

    public Level level() {
        return level;
    }

    /** The direction the wind blows to; shelter is recomputed when it turns to another sector. */
    public void setWind(float dirX, float dirZ) {
        float angle = (float) Math.atan2(dirZ, dirX);
        int s = Math.floorMod(Math.round(angle / Mth.TWO_PI * SECTORS), SECTORS);
        if (s != sector) {
            sector = s;
            float a = s * Mth.TWO_PI / SECTORS;
            windX = Mth.cos(a);
            windZ = Mth.sin(a);
            Arrays.fill(shelterKeys, Long.MIN_VALUE);
        }
    }

    /** Forgets everything cached about the columns around a changed block. */
    public void invalidate(int x, int z) {
        for (int dx = -LEE_REACH; dx <= LEE_REACH; dx++) {
            for (int dz = -LEE_REACH; dz <= LEE_REACH; dz++) {
                int s = ((z + dz) & MASK) * SIZE + ((x + dx) & MASK);
                shelterKeys[s] = Long.MIN_VALUE;
                if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) {
                    keys[s] = Long.MIN_VALUE;
                }
            }
        }
    }

    private static long key(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    private int slot(int x, int z) {
        int s = (z & MASK) * SIZE + (x & MASK);
        long key = key(x, z);
        if (keys[s] != key || now - stamps[s] > EXPIRE + (s & 15)) {
            keys[s] = key;
            stamps[s] = now;
            int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
            heights[s] = h;
            cursor.set(x, h, z);
            Biome biome = level.getBiome(cursor).value();
            snowy[s] = biome.hasPrecipitation() && biome.getPrecipitationAt(cursor) == Biome.Precipitation.SNOW;
        }
        return s;
    }

    /** The first y above the column that nothing blocks (leaves and fluids included). */
    public int height(int x, int z) {
        return heights[slot(x, z)];
    }

    /** Whether precipitation falls as snow at the top of this column. */
    public boolean snowy(int x, int z) {
        return forceSnow || snowy[slot(x, z)];
    }

    /** Lets it snow everywhere regardless of the biome (game tests run in warm land). */
    public void forceSnow(boolean force) {
        forceSnow = force;
    }

    private int shelterSlot(int x, int z) {
        int s = slot(x, z);
        long key = key(x, z);
        if (shelterKeys[s] != key) {
            shelterKeys[s] = key;
            int ground = heights[s];
            float lee = 0.0F;
            for (int d = 1; d <= LEE_REACH; d++) {
                int h = height(Mth.floor(x + 0.5F - windX * d), Mth.floor(z + 0.5F - windZ * d)) - ground;
                if (h > 0) {
                    lee = Math.max(lee, leeStrength(h, d));
                }
            }
            float front = 0.0F;
            for (int d = 1; d <= 2; d++) {
                int h = height(Mth.floor(x + 0.5F + windX * d), Mth.floor(z + 0.5F + windZ * d)) - ground;
                if (h > 0) {
                    front = Math.max(front, Math.min(h, 2) * 0.5F * (d == 1 ? 1.0F : 0.45F));
                }
            }
            // Heightmaps of different columns refresh at different times; the shelter goes with the column's own.
            s = slot(x, z);
            shelter[s] = lee;
            windward[s] = front;
        }
        return s;
    }

    /**
     * How strongly an obstacle {@code height} blocks above a point {@code distance} blocks downwind of it shelters
     * that point: fully right behind it, fading out over a wake a few times as long as the obstacle is high.
     */
    public static float leeStrength(float height, float distance) {
        return Mth.clamp(height / (0.2F * distance + 0.3F), 0.0F, 1.0F) * Math.max(0.0F, 1.0F - distance / (LEE_REACH + 1.0F));
    }

    /** Lee of the ground of this column, 0 (open to the wind) .. 1 (right behind a wall). */
    public float shelter(int x, int z) {
        return shelter[shelterSlot(x, z)];
    }

    /** Whether the column stands at the foot of a wall that faces the wind, 0..1. */
    public float windward(int x, int z) {
        return windward[shelterSlot(x, z)];
    }

    /** The current wind direction as used for the shelter (quantized). */
    public float windX() {
        return windX;
    }

    public float windZ() {
        return windZ;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Points
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Top of the collision box containing the point, or NaN when the point is free. Foliage does not count: flakes
     * drift into crowns (see {@link #inFoliage}).
     */
    public double solidTop(double x, double y, double z) {
        cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState state = level.getBlockState(cursor);
        if (state.isAir() || state.is(BlockTags.LEAVES)) {
            return Double.NaN;
        }
        double[] boxes = shapes.boxes(state, level, cursor);
        double lx = x - cursor.getX();
        double ly = y - cursor.getY();
        double lz = z - cursor.getZ();
        for (int b = 0; b < boxes.length; b += 6) {
            if (lx >= boxes[b] && lx <= boxes[b + 3] && ly >= boxes[b + 1] && ly < boxes[b + 4]
                    && lz >= boxes[b + 2] && lz <= boxes[b + 5]) {
                return cursor.getY() + boxes[b + 4];
            }
        }
        return Double.NaN;
    }

    public boolean inFoliage(double x, double y, double z) {
        cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return level.getBlockState(cursor).is(BlockTags.LEAVES);
    }

    /** Whether the point is in water or lava (flakes melt there). */
    public boolean inFluid(double x, double y, double z) {
        cursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return !level.getFluidState(cursor).isEmpty();
    }

    /**
     * The surface snow settles on at (x, z): the top of the highest block of the column there as it looks (the cap of a
     * fence post, the step of a stair, the visible top of a snow layer), leaves included - crowns turn white. Plants and
     * other blocks without collision are skipped, snow covers their base. NaN over water, lava and unloaded ground.
     *
     * @param kind receives {@link CoverChunk#LEAVES}, {@link CoverChunk#ON_SNOW} or 0
     */
    public double surface(double x, double z, byte[] kind) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        int top = height(bx, bz);
        kind[0] = 0;
        cursor.set(bx, top, bz);
        if (!level.isLoaded(cursor)) {
            return Double.NaN;
        }
        double lx = x - bx;
        double lz = z - bz;
        // From the heightmap's own block: snow layers, carpets and the like do not count as blocking motion, so they
        // sit right at the height the heightmap gives and would be missed starting one below.
        for (int by = top; by >= top - 4; by--) {
            cursor.set(bx, by, bz);
            BlockState state = level.getBlockState(cursor);
            if (state.isAir()) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                return Double.NaN;
            }
            // Snow layers and powder snow have no (or a lower) collision box but are snow to stand on in looks.
            boolean layer = state.getBlock() instanceof SnowLayerBlock || state.is(Blocks.POWDER_SNOW);
            if (!layer && shapes.boxes(state, level, cursor).length == 0) {
                continue;
            }
            double[] boxes = shapes.outline(state, level, cursor);
            double best = Double.NEGATIVE_INFINITY;
            for (int b = 0; b < boxes.length; b += 6) {
                if (lx >= boxes[b] && lx <= boxes[b + 3] && lz >= boxes[b + 2] && lz <= boxes[b + 5] && boxes[b + 4] > best) {
                    best = boxes[b + 4];
                }
            }
            if (best == Double.NEGATIVE_INFINITY) {
                // Between the posts of a fence or the bars of a pane: the snow falls through to the ground.
                continue;
            }
            if (state.is(BlockTags.LEAVES)) {
                kind[0] = CoverChunk.LEAVES;
            } else if (layer || state.is(Blocks.SNOW_BLOCK)) {
                kind[0] = CoverChunk.ON_SNOW;
            }
            return by + best;
        }
        return Double.NaN;
    }

    /** Block light at a position, 0..15 (lamps, torches and fire melt the snow around them). */
    public int blockLight(double x, double y, double z) {
        lightCursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        return level.getBrightness(LightLayer.BLOCK, lightCursor);
    }

    /**
     * Packed light coordinates (block light in bits 4..7, sky light in bits 20..23) at a position. Inside a block the
     * stored light is 0, which would draw the snow black; there the block above counts.
     */
    public int lightAt(double x, double y, double z) {
        lightCursor.set(Mth.floor(x), Mth.floor(y), Mth.floor(z));
        BlockState state = level.getBlockState(lightCursor);
        int sky = level.getBrightness(LightLayer.SKY, lightCursor);
        int block = level.getBrightness(LightLayer.BLOCK, lightCursor);
        if (!state.isAir()) {
            boolean solid = state.isSolidRender(level, lightCursor);
            lightCursor.move(0, 1, 0);
            int skyAbove = level.getBrightness(LightLayer.SKY, lightCursor);
            int blockAbove = level.getBrightness(LightLayer.BLOCK, lightCursor);
            sky = solid ? skyAbove : Math.max(sky, skyAbove);
            block = solid ? blockAbove : Math.max(block, blockAbove);
        }
        return block << 4 | sky << 20;
    }

    public boolean isLoaded(int x, int z) {
        cursor.set(x, 64, z);
        return level.isLoaded(cursor);
    }
}
