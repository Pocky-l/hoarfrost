package com.pockyl.hoarfrost.client;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.sim.SnowCover;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * The snow lying on the land out to the render distance, beyond the simulated snow around the camera: only the top
 * surface of each column, with the depth snow settles to there in still air. Cheap to look at and to draw (see
 * {@code FarSnowRenderer}); it also fills the near land until the simulated snow there is ready.
 */
public final class FarSnow {
    /** Natural depth on open cold land and on crowns. */
    private static final float OPEN = SnowCover.capacity(1.0F) * 0.6F;
    private static final float CROWN = SnowCover.capacity(0.7F) * 0.6F;

    /** One chunk column of distant snow. */
    public static final class Chunk {
        public final int x;
        public final int z;
        /** Height of the top surface per column, NaN where snow cannot lie (water, lamps). */
        public final float[] base = new float[256];
        public final float[] depth = new float[256];
        public final int[] light = new int[256];
        boolean sampled;
        public int revision;

        Chunk(int x, int z) {
            this.x = x;
            this.z = z;
            Arrays.fill(base, Float.NaN);
        }

        public boolean sampled() {
            return sampled;
        }
    }

    private final Long2ObjectOpenHashMap<Chunk> chunks = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private long[] order = new long[0];
    private int resampleCursor;
    private int ticks;

    public Chunk chunk(int chunkX, int chunkZ) {
        return chunks.get(ChunkPos.asLong(chunkX, chunkZ));
    }

    public Iterable<Chunk> chunks() {
        return chunks.values();
    }

    public void clear() {
        chunks.clear();
    }

    /** Blocks changed in a column: look at its chunk again. */
    public void blockChanged(int x, int z) {
        Chunk chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk != null) {
            chunk.sampled = false;
        }
    }

    /**
     * Keeps the chunks within {@code radius} chunks of the camera, looks at new ones nearest first within a time budget,
     * and looks at one again now and then (seasons change where snow lies).
     */
    public void tick(ClientLevel level, Vec3 camera, int radius, long budgetNanos) {
        ticks++;
        int cx = Mth.floor(camera.x) >> 4;
        int cz = Mth.floor(camera.z) >> 4;
        if (ticks % 20 == 1) {
            for (Iterator<Chunk> it = chunks.values().iterator(); it.hasNext(); ) {
                Chunk chunk = it.next();
                if (Math.abs(chunk.x - cx) > radius + 1 || Math.abs(chunk.z - cz) > radius + 1
                        || !level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    it.remove();
                }
            }
            for (int x = cx - radius; x <= cx + radius; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    long key = ChunkPos.asLong(x, z);
                    if (!chunks.containsKey(key) && level.getChunkSource().hasChunk(x, z)) {
                        chunks.put(key, new Chunk(x, z));
                    }
                }
            }
            order = chunks.keySet().toLongArray();
        }
        List<Chunk> pending = new ArrayList<>();
        for (Chunk chunk : chunks.values()) {
            if (!chunk.sampled) {
                pending.add(chunk);
            }
        }
        long deadline = System.nanoTime() + budgetNanos;
        if (!pending.isEmpty()) {
            pending.sort((a, b) -> Integer.compare(Math.max(Math.abs(a.x - cx), Math.abs(a.z - cz)),
                    Math.max(Math.abs(b.x - cx), Math.abs(b.z - cz))));
            for (Chunk chunk : pending) {
                sample(level, chunk);
                if (System.nanoTime() > deadline) {
                    break;
                }
            }
        } else if (order.length > 0 && ticks % 4 == 0) {
            Chunk chunk = chunks.get(order[resampleCursor++ % order.length]);
            if (chunk != null) {
                sample(level, chunk);
            }
        }
    }

    private void sample(ClientLevel level, Chunk chunk) {
        chunk.sampled = true;
        chunk.revision++;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int column = lz << 4 | lx;
                int x = chunk.x * 16 + lx;
                int z = chunk.z * 16 + lz;
                chunk.base[column] = Float.NaN;
                chunk.depth[column] = 0.0F;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
                if (y < level.getMinBuildHeight()) {
                    continue;
                }
                pos.set(x, y + 1, z);
                if (level.getBlockState(pos).getBlock() instanceof SnowLayerBlock) {
                    y++;
                }
                pos.set(x, y, z);
                BlockState state = level.getBlockState(pos);
                if (!state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty()) {
                    continue;
                }
                VoxelShape shape = state.getShape(level, pos);
                if (shape.isEmpty()) {
                    continue;
                }
                float base = y + (float) Math.min(1.0, shape.max(Direction.Axis.Y));
                pos.set(x, Mth.floor(base + 0.02F), z);
                int block = level.getBrightness(LightLayer.BLOCK, pos);
                // The top of the land is under the open sky (the client may not have lit a new chunk yet).
                chunk.base[column] = base;
                chunk.light[column] = block << 4 | 15 << 20;
                if (block < 11 && Climate.snowsAt(level, pos)) {
                    chunk.depth[column] = state.is(BlockTags.LEAVES) ? CROWN : OPEN;
                }
            }
        }
    }
}
