package com.pockyl.hoarfrost.sim;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.pockyl.hoarfrost.compat.Climate;

import java.util.Arrays;
import java.util.function.BiPredicate;

/**
 * Looks at the land of a chunk once (again after blocks change) and works out where snow can lie and how much of it each
 * spot gets - in still air and in a gale from each direction:
 * <ul>
 *   <li>open ground gets the snowfall; in wind, flat open land loses some of it and wind-swept ridges and roofs more, while
 *   the lee of walls, trees and hills collects it in drifts that are deepest a little behind the obstacle and taper over
 *   a few times its height, and a small bank forms at the foot of walls facing the wind; hollows fill up;</li>
 *   <li>under a crown, snow comes through the leaves - evenly, much less than in the open;</li>
 *   <li>under a roof, an overhang or in a cave, snow only gets in from an opening: in still air it spills a block or so
 *   in, with wind it is blown in from upwind, decaying quickly with the distance to the opening and with how much of
 *   the opening faces the wind. Nothing ever reaches far down a tunnel.</li>
 * </ul>
 * The factors are smoothed over a few blocks of the same level, so the snow surface has no steps from column to column.
 * Nothing here moves snow around: depth only grows by what each spot gets, which keeps the surface calm and smooth.
 */
public final class CoverSurvey {
    /** Columns around the chunk surveyed too, so the smoothing has neighbours. */
    private static final int MARGIN = 2;
    private static final int WINDOW = 16 + 2 * MARGIN;
    /** How far upwind obstacles shelter the snow. */
    private static final int LEE_REACH = 10;
    /** Heights are kept for the window plus the lee reach. */
    private static final int HEIGHT_MARGIN = MARGIN + LEE_REACH + 1;
    private static final int HEIGHTS = 16 + 2 * HEIGHT_MARGIN;
    /** How far below the top the surface under a crown, roof or overhang is looked for. */
    private static final int UNDER_SCAN = 40;
    /** Sheltered floors further below the top than this are only looked at when sky light reaches them. */
    private static final int DEEP = 10;
    /** Surfaces whose heights differ by at most this are one snow surface. */
    public static final float SAME_LEVEL = 0.6F;
    /** Distance over which snow blown in through an opening fades, at full gale. */
    private static final float INTAKE_REACH = 2.6F;
    private static final float CALM_REACH = 0.7F;
    private static final float RAY_STEP = 0.4F;
    private static final float RAY_LENGTH = 14.0F;
    private static final float[] DIR_X = new float[CoverChunk.DIRECTIONS];
    private static final float[] DIR_Z = new float[CoverChunk.DIRECTIONS];

    static {
        for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
            double angle = d * Math.PI * 2.0 / CoverChunk.DIRECTIONS;
            DIR_X[d] = (float) Math.cos(angle);
            DIR_Z[d] = (float) Math.sin(angle);
        }
    }

    private final int[] heightBlocking = new int[HEIGHTS * HEIGHTS];
    private final int[] heightGround = new int[HEIGHTS * HEIGHTS];
    private final float[] base = new float[2 * WINDOW * WINDOW];
    private final byte[] flags = new byte[2 * WINDOW * WINDOW];
    private final float[] calm = new float[2 * WINDOW * WINDOW];
    private final float[] windy = new float[2 * WINDOW * WINDOW * CoverChunk.DIRECTIONS];
    private final int[] light = new int[2 * WINDOW * WINDOW];
    private final byte[] capBox = new byte[2 * WINDOW * WINDOW * 4];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
    private final float[] blurred = new float[1 + CoverChunk.DIRECTIONS];
    private final int[] reach = new int[25];
    private Level level;
    private int originX;
    private int originZ;
    private int heightOriginX;
    private int heightOriginZ;

    /**
     * Surveys a chunk. Its neighbours should be loaded (the lee looks a few blocks into them).
     *
     * @param cold where snow falls and stays; normally {@link Climate#snowsAt}
     */
    public void survey(Level level, CoverChunk chunk, BiPredicate<Level, BlockPos> cold) {
        this.level = level;
        originX = chunk.x * 16 - MARGIN;
        originZ = chunk.z * 16 - MARGIN;
        heightOriginX = chunk.x * 16 - HEIGHT_MARGIN;
        heightOriginZ = chunk.z * 16 - HEIGHT_MARGIN;
        for (int hz = 0; hz < HEIGHTS; hz++) {
            for (int hx = 0; hx < HEIGHTS; hx++) {
                int x = heightOriginX + hx;
                int z = heightOriginZ + hz;
                heightBlocking[hz * HEIGHTS + hx] = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                heightGround[hz * HEIGHTS + hx] = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            }
        }
        for (int wz = 0; wz < WINDOW; wz++) {
            for (int wx = 0; wx < WINDOW; wx++) {
                surveyColumn(level, wx, wz, cold);
            }
        }
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int column = CoverChunk.index(lx, lz);
                for (int s = 0; s < CoverChunk.SURFACES; s++) {
                    int w = window(s, lx + MARGIN, lz + MARGIN);
                    int e = s * CoverChunk.COLUMNS + column;
                    chunk.base[e] = base[w];
                    chunk.flags[e] = flags[w];
                    chunk.light[e] = light[w];
                    System.arraycopy(capBox, w * 4, chunk.capBox, e * 4, 4);
                    if (Float.isNaN(base[w])) {
                        chunk.calm[e] = 0.0F;
                        for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
                            chunk.windy[e * CoverChunk.DIRECTIONS + d] = 0;
                        }
                        continue;
                    }
                    blur(s, lx + MARGIN, lz + MARGIN);
                    chunk.calm[e] = blurred[0];
                    for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
                        chunk.windy[e * CoverChunk.DIRECTIONS + d] = (byte) Mth.clamp(Math.round(blurred[1 + d] * 64.0F), 0, 255);
                    }
                }
            }
        }
        chunk.surveyed = true;
        chunk.carryOver();
        chunk.touched();
        this.level = null;
    }

    private static int window(int surface, int wx, int wz) {
        return surface * WINDOW * WINDOW + wz * WINDOW + wx;
    }

    private int heightBlocking(int x, int z) {
        int hx = x - heightOriginX;
        int hz = z - heightOriginZ;
        if (hx < 0 || hz < 0 || hx >= HEIGHTS || hz >= HEIGHTS) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        }
        return heightBlocking[hz * HEIGHTS + hx];
    }

    private int heightGround(int x, int z) {
        int hx = x - heightOriginX;
        int hz = z - heightOriginZ;
        if (hx < 0 || hz < 0 || hx >= HEIGHTS || hz >= HEIGHTS) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        return heightGround[hz * HEIGHTS + hx];
    }

    /** Height of the top of what stands at a column for the wind: leaves let it through in part. */
    private float obstacle(int x, int z) {
        int ground = heightGround(x, z);
        int top = heightBlocking(x, z);
        return ground + (top - ground) * 0.55F;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Surfaces of a column
    // ------------------------------------------------------------------------------------------------------------

    private void surveyColumn(Level level, int wx, int wz, BiPredicate<Level, BlockPos> cold) {
        int x = originX + wx;
        int z = originZ + wz;
        int top = window(CoverChunk.TOP, wx, wz);
        int under = window(CoverChunk.UNDER, wx, wz);
        base[top] = Float.NaN;
        base[under] = Float.NaN;
        flags[top] = 0;
        flags[under] = 0;
        int topY = heightBlocking(x, z) - 1;
        if (topY < level.getMinBuildHeight()) {
            return;
        }
        // A thin vanilla snow layer does not count in the heightmap: the snow lies on top of it, not through it.
        pos.set(x, topY + 1, z);
        if (level.getBlockState(pos).getBlock() instanceof SnowLayerBlock) {
            topY++;
        }
        pos.set(x, topY, z);
        BlockState state = level.getBlockState(pos);
        if (surface(level, state, topY, top)) {
            finishSurface(level, x, z, top, cold);
            topFactors(x, z, top);
        }
        // Down through the block(s) at the top to an air gap, and through the gap to its floor.
        int leaves = state.is(BlockTags.LEAVES) ? 1 : 0;
        int solid = leaves == 0 && !open(level, state) ? 1 : 0;
        int y = topY - 1;
        int bottom = Math.max(level.getMinBuildHeight(), topY - UNDER_SCAN);
        boolean gap = false;
        for (; y >= bottom; y--) {
            pos.setY(y);
            BlockState at = level.getBlockState(pos);
            if (open(level, at)) {
                gap = true;
            } else if (gap) {
                if (surface(level, at, y, under)) {
                    finishSurface(level, x, z, under, cold);
                    if (topY - y > DEEP && (light[under] >> 20 & 0xF) == 0) {
                        // Deep below and no sky light reaches it: no opening is near, nothing can blow in.
                        calm[under] = 0.0F;
                        Arrays.fill(windy, under * CoverChunk.DIRECTIONS, (under + 1) * CoverChunk.DIRECTIONS, 0.0F);
                    } else {
                        underFactors(level, x, z, under, leaves, solid);
                    }
                }
                return;
            } else if (at.is(BlockTags.LEAVES)) {
                leaves++;
            } else {
                solid++;
            }
        }
    }

    /** Air, plants, torches: nothing snow lies on or that stops it. */
    private boolean open(Level level, BlockState state) {
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty() && !state.is(BlockTags.LEAVES)
                && !(state.getBlock() instanceof SnowLayerBlock);
    }

    /**
     * Thin things lying on a block that the player must keep seeing: rails, redstone dust, tripwire and pressure plates.
     * Snow does not lie under them, as vanilla snow layers do not.
     */
    private static boolean keptClear(BlockState state) {
        return state.is(BlockTags.RAILS) || state.is(BlockTags.PRESSURE_PLATES) || state.getBlock() instanceof RedStoneWireBlock
                || state.getBlock() instanceof TripWireBlock;
    }

    /** Sets up the surface of the block at {@code y} in window slot {@code w}; false when snow cannot lie on it. */
    private boolean surface(Level level, BlockState state, int y, int w) {
        if (!state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        if (keptClear(level.getBlockState(above.setWithOffset(pos, Direction.UP)))) {
            return false;
        }
        double maxY = shape.max(Direction.Axis.Y);
        if (maxY > 1.0) {
            // Fences and walls reach higher for collision only; their outline stays within the block.
            maxY = 1.0;
        }
        double area = 0.0;
        double minX = 1.0;
        double maxX = 0.0;
        double minZ = 1.0;
        double maxZ = 0.0;
        for (AABB box : shape.toAabbs()) {
            if (box.maxY >= maxY - 0.01) {
                area += (box.maxX - box.minX) * (box.maxZ - box.minZ);
                minX = Math.min(minX, box.minX);
                maxX = Math.max(maxX, box.maxX);
                minZ = Math.min(minZ, box.minZ);
                maxZ = Math.max(maxZ, box.maxZ);
            }
        }
        base[w] = (float) (y + maxY);
        byte f = 0;
        if (state.is(BlockTags.LEAVES)) {
            f |= CoverChunk.LEAVES;
        }
        if (area < 0.42) {
            f |= CoverChunk.CAP;
            capBox[w * 4] = (byte) Math.round(minX * 16.0);
            capBox[w * 4 + 1] = (byte) Math.round(maxX * 16.0);
            capBox[w * 4 + 2] = (byte) Math.round(minZ * 16.0);
            capBox[w * 4 + 3] = (byte) Math.round(maxZ * 16.0);
        }
        flags[w] = f;
        return true;
    }

    private void finishSurface(Level level, int x, int z, int w, BiPredicate<Level, BlockPos> cold) {
        probe.set(x, (int) Math.floor(base[w] + 0.02F), z);
        int block = level.getBrightness(LightLayer.BLOCK, probe);
        // The top surface is under the open sky; the client may not have lit new chunks yet, which would draw it black.
        int sky = w < WINDOW * WINDOW ? 15 : level.getBrightness(LightLayer.SKY, probe);
        light[w] = block << 4 | sky << 20;
        if (block >= 11) {
            flags[w] |= CoverChunk.LIT;
        }
        if (cold.test(level, probe)) {
            flags[w] |= CoverChunk.COLD;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Open surfaces: lee, banks, ridges and hollows
    // ------------------------------------------------------------------------------------------------------------

    private void topFactors(int x, int z, int w) {
        float b = base[w];
        float catchShare = (flags[w] & CoverChunk.LEAVES) != 0 ? 0.7F : 1.0F;
        if ((flags[w] & CoverChunk.CAP) != 0) {
            calm[w] = 1.0F;
            for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
                windy[w * CoverChunk.DIRECTIONS + d] = 0.45F;
            }
            return;
        }
        // Height against the land around (7x7): a ridge or roof stands out and is swept, a hollow fills.
        float sum = 0.0F;
        for (int dz = -3; dz <= 3; dz++) {
            for (int dx = -3; dx <= 3; dx++) {
                sum += heightGround(x + dx, z + dz);
            }
        }
        float relief = b - sum / 49.0F;
        float ridge = Mth.clamp((relief - 0.4F) / 3.0F, 0.0F, 1.0F);
        float hollow = Mth.clamp((-relief - 0.3F) / 2.0F, 0.0F, 1.0F);
        calm[w] = catchShare * Mth.clamp(1.0F + 0.18F * hollow - 0.1F * ridge, 0.3F, 1.3F);
        for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
            float lee = 0.0F;
            for (int k = 1; k <= LEE_REACH; k++) {
                float rel = obstacle(x + Math.round(-DIR_X[d] * k), z + Math.round(-DIR_Z[d] * k)) - b;
                if (rel > 0.4F) {
                    lee = Math.max(lee, leeStrength(rel, k));
                }
            }
            float bank = 0.0F;
            for (int k = 1; k <= 2; k++) {
                float rel = obstacle(x + Math.round(DIR_X[d] * k), z + Math.round(DIR_Z[d] * k)) - b;
                if (rel > 0.6F) {
                    bank = Math.max(bank, k == 1 ? 0.7F : 0.3F);
                }
            }
            windy[w * CoverChunk.DIRECTIONS + d] = catchShare
                    * Mth.clamp(0.62F + 2.3F * lee + 0.7F * bank + 0.6F * hollow - 0.55F * ridge, 0.06F, 3.9F);
        }
    }

    /**
     * How much an obstacle {@code height} blocks above the snow, {@code distance} blocks upwind, makes the snow
     * settle: rising over the first height behind it, then fading over a few heights.
     */
    public static float leeStrength(float height, float distance) {
        float x = distance / Math.max(height, 0.8F);
        float profile = x < 1.0F ? 0.5F + 0.5F * x : (float) Math.exp(-(x - 1.0F) / 2.2F);
        return Math.min(1.0F, height / 2.2F) * profile;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sheltered surfaces: through a crown, in through an opening
    // ------------------------------------------------------------------------------------------------------------

    private void underFactors(Level level, int x, int z, int w, int leaves, int solid) {
        float b = base[w];
        float through = solid > 0 ? 0.0F : 0.5F * (float) Math.pow(0.62, Math.max(0, leaves - 1));
        double ox = x + 0.5;
        double oy = b + 0.5;
        double oz = z + 0.5;
        // Still air: snow drifting down at a slant spills in around the edges of the cover.
        float spill = 0.0F;
        for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
            spill = Math.max(spill, ray(level, ox, oy, oz, DIR_X[d], 1.4F, DIR_Z[d], CALM_REACH, 6.0F));
        }
        calm[w] = Mth.clamp(through + spill * 0.9F, 0.0F, 1.0F);
        for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
            // The wind blows towards d: the snow comes in from the opposite side.
            float intake = 0.0F;
            for (int r = 0; r < 4; r++) {
                double turn = (r & 1) == 0 ? -0.38 : 0.38;
                float rise = r < 2 ? 0.12F : 0.55F;
                double c = Math.cos(turn);
                double s = Math.sin(turn);
                float rx = (float) (-DIR_X[d] * c + DIR_Z[d] * s);
                float rz = (float) (-DIR_Z[d] * c - DIR_X[d] * s);
                intake += ray(level, ox, oy, oz, rx, rise, rz, INTAKE_REACH, RAY_LENGTH);
            }
            intake = Math.min(1.3F, intake / 4.0F * 1.6F);
            windy[w * CoverChunk.DIRECTIONS + d] = Mth.clamp(through * 0.75F + intake * 1.25F + spill * 0.4F, 0.0F, 2.5F);
        }
    }

    /**
     * Follows a ray from a sheltered spot until it reaches open sky: {@code exp(-distance / reach)}, less for leaves on
     * the way; 0 when a block stops it or it runs out.
     */
    private float ray(Level level, double ox, double oy, double oz, float dx, float dy, float dz, float reach, float length) {
        float norm = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float sx = dx / norm * RAY_STEP;
        float sy = dy / norm * RAY_STEP;
        float sz = dz / norm * RAY_STEP;
        double px = ox;
        double py = oy;
        double pz = oz;
        float through = 1.0F;
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (float travelled = RAY_STEP; travelled <= length; travelled += RAY_STEP) {
            px += sx;
            py += sy;
            pz += sz;
            int bx = (int) Math.floor(px);
            int by = (int) Math.floor(py);
            int bz = (int) Math.floor(pz);
            if (py >= heightBlocking(bx, bz)) {
                return through * (float) Math.exp(-travelled / reach);
            }
            if (bx == lastX && by == lastY && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastY = by;
            lastZ = bz;
            probe.set(bx, by, bz);
            BlockState state = level.getBlockState(probe);
            if (state.is(BlockTags.LEAVES)) {
                through *= 0.7F;
            } else if (!state.getCollisionShape(level, probe).isEmpty()) {
                return 0.0F;
            }
        }
        return 0.0F;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Smoothing
    // ------------------------------------------------------------------------------------------------------------

    private static final float[] KERNEL = {1.0F, 4.0F, 6.0F, 4.0F, 1.0F};

    /**
     * Binomial 5x5 average of the factors over the neighbours on the same level that can be reached from the column
     * without crossing a wall (snow does not smooth out through the wall of a house).
     */
    private void blur(int surface, int wx, int wz) {
        int self = window(surface, wx, wz);
        Arrays.fill(blurred, 0.0F);
        Arrays.fill(reach, -1);
        reach[12] = self;
        if ((flags[self] & CoverChunk.CAP) == 0) {
            // Spread out ring by ring: a neighbour counts when one of its inner neighbours does and it lies on the same level.
            for (int ring = 1; ring <= 2; ring++) {
                for (int pass = 0; pass < 2; pass++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        for (int dx = -ring; dx <= ring; dx++) {
                            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring || reach[(dz + 2) * 5 + dx + 2] >= 0) {
                                continue;
                            }
                            reach[(dz + 2) * 5 + dx + 2] = connect(wx, wz, dx, dz);
                        }
                    }
                }
            }
        }
        float total = 0.0F;
        for (int dz = -2; dz <= 2; dz++) {
            for (int dx = -2; dx <= 2; dx++) {
                int n = reach[(dz + 2) * 5 + dx + 2];
                if (n < 0) {
                    continue;
                }
                float weight = KERNEL[dx + 2] * KERNEL[dz + 2];
                total += weight;
                blurred[0] += calm[n] * weight;
                for (int d = 0; d < CoverChunk.DIRECTIONS; d++) {
                    blurred[1 + d] += windy[n * CoverChunk.DIRECTIONS + d] * weight;
                }
            }
        }
        for (int i = 0; i < blurred.length; i++) {
            blurred[i] /= total;
        }
    }

    /** The surface at an offset that lies on the level of an already reached side neighbour, or -1. */
    private int connect(int wx, int wz, int dx, int dz) {
        int nx = wx + dx;
        int nz = wz + dz;
        if (nx < 0 || nz < 0 || nx >= WINDOW || nz >= WINDOW) {
            return -1;
        }
        for (int k = 0; k < 4; k++) {
            int ox = dx + (k == 0 ? -1 : k == 1 ? 1 : 0);
            int oz = dz + (k == 2 ? -1 : k == 3 ? 1 : 0);
            if (Math.abs(ox) > 2 || Math.abs(oz) > 2 || Math.max(Math.abs(ox), Math.abs(oz)) > Math.max(Math.abs(dx), Math.abs(dz))) {
                continue;
            }
            int from = reach[(oz + 2) * 5 + ox + 2];
            if (from < 0) {
                continue;
            }
            float level = base[from];
            int best = -1;
            float bestDistance = SAME_LEVEL;
            for (int s = 0; s < CoverChunk.SURFACES; s++) {
                int n = window(s, nx, nz);
                if (!Float.isNaN(base[n]) && (flags[n] & CoverChunk.CAP) == 0 && Math.abs(base[n] - level) <= bestDistance) {
                    bestDistance = Math.abs(base[n] - level);
                    best = n;
                }
            }
            if (best >= 0) {
                return best;
            }
        }
        return -1;
    }
}
