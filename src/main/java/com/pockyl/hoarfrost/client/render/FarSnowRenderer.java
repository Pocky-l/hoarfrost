package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.client.FarSnow;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * Draws the distant snow of {@link FarSnow} where the detailed snow is not drawn: one quad per block whose corners average
 * the neighbouring columns on the same level, so the land stays smooth and white out to the horizon. Chunks are merged
 * into regions of 4x4 chunks, one static buffer each, rebuilt only when their land was looked at again or the detailed
 * snow took over or gave up a chunk of them.
 */
final class FarSnowRenderer implements AutoCloseable {
    private static final int REGION = 4;
    private static final float LIFT = 0.012F;
    private static final float STEP = 0.3F;
    private static final long BUDGET_NANOS = 1_500_000L;

    private final FarSnow snow;
    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    /** Shared by the regions of every world: a builder's memory is never given back. */
    private static final BufferBuilder BUILDER = new BufferBuilder(1 << 16);
    private final List<Region> dirty = new ArrayList<>();
    private final float[] corner = new float[4];
    private int quads;
    private float minY;
    private float maxY;

    private static final class Region {
        final int x;
        final int z;
        VertexBuffer buffer;
        boolean empty = true;
        long builtKey = Long.MIN_VALUE;
        long key;
        double originY;
        AABB bounds;
        double distanceSq;
        int quads;

        Region(int x, int z) {
            this.x = x;
            this.z = z;
        }

        void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            builtKey = Long.MIN_VALUE;
        }
    }

    FarSnowRenderer(FarSnow snow) {
        this.snow = snow;
    }

    /** Forgets every mesh (the look of the snow changed). */
    void invalidate() {
        for (Region region : regions.values()) {
            region.close();
        }
        regions.clear();
    }

    /**
     * Brings the regions up to date, nearest first within a time budget.
     *
     * @param near whether the detailed snow draws a chunk
     */
    void update(CoverRenderer look, Vec3 cam, BiPredicate<Integer, Integer> near) {
        for (FarSnow.Chunk chunk : snow.chunks()) {
            long key = ChunkPos.asLong(Math.floorDiv(chunk.x, REGION), Math.floorDiv(chunk.z, REGION));
            if (!regions.containsKey(key)) {
                regions.put(key, new Region(Math.floorDiv(chunk.x, REGION), Math.floorDiv(chunk.z, REGION)));
            }
        }
        dirty.clear();
        for (Iterator<Region> it = regions.values().iterator(); it.hasNext(); ) {
            Region region = it.next();
            long key = 1L;
            boolean any = false;
            for (int dz = 0; dz < REGION; dz++) {
                for (int dx = 0; dx < REGION; dx++) {
                    int cx = region.x * REGION + dx;
                    int cz = region.z * REGION + dz;
                    FarSnow.Chunk chunk = snow.chunk(cx, cz);
                    boolean drawn = chunk != null && chunk.sampled() && !near.test(cx, cz);
                    any |= chunk != null;
                    key = key * 31L + (drawn ? chunk.revision + 1 : 0);
                }
            }
            if (!any) {
                region.close();
                it.remove();
                continue;
            }
            region.key = key;
            double ddx = region.x * REGION * 16 + REGION * 8 - cam.x;
            double ddz = region.z * REGION * 16 + REGION * 8 - cam.z;
            region.distanceSq = ddx * ddx + ddz * ddz;
            if (region.builtKey != key) {
                dirty.add(region);
            }
        }
        dirty.sort((a, b) -> Double.compare(a.distanceSq, b.distanceSq));
        long deadline = System.nanoTime() + BUDGET_NANOS;
        for (Region region : dirty) {
            build(look, region, near);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        VertexBuffer.unbind();
    }

    /** Draws the regions; the render state is set up by the caller. */
    int draw(Vec3 cam, Frustum frustum, Uniform offset) {
        int drawn = 0;
        for (Region region : regions.values()) {
            if (region.buffer == null || region.empty || !frustum.isVisible(region.bounds)) {
                continue;
            }
            if (offset != null) {
                offset.set((float) (region.x * REGION * 16 - cam.x), (float) (region.originY - cam.y), (float) (region.z * REGION * 16 - cam.z));
                offset.upload();
            }
            region.buffer.bind();
            region.buffer.draw();
            drawn += region.quads;
        }
        return drawn;
    }

    private void build(CoverRenderer look, Region region, BiPredicate<Integer, Integer> near) {
        region.builtKey = region.key;
        float lowest = Float.MAX_VALUE;
        for (int dz = 0; dz < REGION; dz++) {
            for (int dx = 0; dx < REGION; dx++) {
                FarSnow.Chunk chunk = snow.chunk(region.x * REGION + dx, region.z * REGION + dz);
                if (chunk != null && chunk.sampled()) {
                    for (float b : chunk.base) {
                        if (!Float.isNaN(b)) {
                            lowest = Math.min(lowest, b);
                        }
                    }
                }
            }
        }
        if (lowest == Float.MAX_VALUE) {
            region.empty = true;
            return;
        }
        region.originY = Math.floor(lowest);
        BufferBuilder builder = BUILDER;
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        quads = 0;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        for (int dz = 0; dz < REGION; dz++) {
            for (int dx = 0; dx < REGION; dx++) {
                int cx = region.x * REGION + dx;
                int cz = region.z * REGION + dz;
                FarSnow.Chunk chunk = snow.chunk(cx, cz);
                if (chunk != null && chunk.sampled() && !near.test(cx, cz)) {
                    chunk(look, builder, chunk, dx * 16, dz * 16, (float) region.originY);
                }
            }
        }
        BufferBuilder.RenderedBuffer data = builder.endOrDiscardIfEmpty();
        if (data == null) {
            region.empty = true;
            return;
        }
        if (region.buffer == null) {
            region.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        region.buffer.bind();
        region.buffer.upload(data);
        region.empty = false;
        region.quads = quads;
        double x0 = region.x * REGION * 16;
        double z0 = region.z * REGION * 16;
        region.bounds = new AABB(x0, region.originY + minY - 0.5, z0, x0 + REGION * 16, region.originY + maxY + 0.5, z0 + REGION * 16);
    }

    /** Height the snow reaches on a column, or NaN without snow. */
    private float top(int x, int z) {
        FarSnow.Chunk chunk = snow.chunk(x >> 4, z >> 4);
        if (chunk == null || !chunk.sampled()) {
            return Float.NaN;
        }
        int column = (z & 15) << 4 | x & 15;
        return chunk.base[column] + chunk.depth[column];
    }

    private void chunk(CoverRenderer look, BufferBuilder builder, FarSnow.Chunk chunk, int ox, int oz, float originY) {
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int column = lz << 4 | lx;
                float depth = chunk.depth[column];
                float b = chunk.base[column];
                if (Float.isNaN(b) || depth < 0.002F) {
                    continue;
                }
                int x = chunk.x * 16 + lx;
                int z = chunk.z * 16 + lz;
                float h = b + depth;
                // Each corner: the mean of the columns around it that lie on the same level.
                for (int c = 0; c < 4; c++) {
                    int cx = (c & 1) == 0 ? -1 : 0;
                    int cz = (c & 2) == 0 ? -1 : 0;
                    float sum = 0.0F;
                    int n = 0;
                    for (int k = 0; k < 4; k++) {
                        float t = top(x + cx + (k & 1), z + cz + (k >> 1));
                        if (!Float.isNaN(t) && Math.abs(t - h) <= STEP + depth) {
                            sum += t;
                            n++;
                        }
                    }
                    corner[c] = Math.max(b + LIFT, n > 0 ? sum / n : h) - originY;
                }
                float x0 = ox + lx;
                float z0 = oz + lz;
                float nx = (corner[0] + corner[2] - corner[1] - corner[3]) * 0.5F;
                float nz = (corner[0] + corner[1] - corner[2] - corner[3]) * 0.5F;
                float length = (float) Math.sqrt(nx * nx + 1.0F + nz * nz);
                nx /= length;
                nz /= length;
                float ny = 1.0F / length;
                int color = 0xFF000000 | look.tint(Math.min(1.0F, 0.85F + 0.15F * ny));
                int light = chunk.light[column];
                int packed = (light >> 4 & 0xF) * 16 | (light >> 20 & 0xF) * 16 << 16;
                float u = look.u(x, 0.0F);
                float v = look.v(z, 0.0F);
                float u2 = look.u(x, 1.0F);
                float v2 = look.v(z, 1.0F);
                // Counter-clockwise seen from above. Corners: 0 (-x,-z), 1 (+x,-z), 2 (-x,+z), 3 (+x,+z).
                CoverRenderer.vertex(builder, x0, corner[0], z0, color, u, v, packed, nx, ny, nz);
                CoverRenderer.vertex(builder, x0, corner[2], z0 + 1.0F, color, u, v2, packed, nx, ny, nz);
                CoverRenderer.vertex(builder, x0 + 1.0F, corner[3], z0 + 1.0F, color, u2, v2, packed, nx, ny, nz);
                CoverRenderer.vertex(builder, x0 + 1.0F, corner[1], z0, color, u2, v, packed, nx, ny, nz);
                quads++;
                for (float y : corner) {
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
    }

    @Override
    public void close() {
        invalidate();
    }
}
