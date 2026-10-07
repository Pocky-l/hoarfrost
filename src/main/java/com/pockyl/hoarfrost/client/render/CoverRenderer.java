package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.compat.ShaderPacks;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.SnowCover;
import com.pockyl.hoarfrost.sim.Terrain;
import com.pockyl.hoarfrost.weather.Noise;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the snow lying on the world as a smooth surface over the blocks. Near the camera every quarter-block cell is a
 * quad whose corners average the snow around them, so drifts are smooth mounds, footprints soft dents and the snow
 * thins out to nothing at its edges; where snow ends at a drop (the edge of a roof, a fence post) it shows its depth
 * on the side. Thin snow is drawn as a dusting with gaps (the ground shows through), deeper snow solid, trodden snow
 * packed. Tiles of 4x4 blocks are static GPU buffers rebuilt only when their snow visibly changed; far away a whole
 * chunk is one coarse buffer of block-sized quads.
 *
 * <p>The vanilla cutout terrain shader and the block atlas are used, so fog, light and day/night match the world, and
 * shader packs (Iris) draw the snow with their terrain program.
 */
final class CoverRenderer implements AutoCloseable {
    private static final RenderType COVER = RenderType.create(Hoarfrost.MOD_ID + "_snow_cover", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 1 << 16, false, false, RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_MIPPED_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final ResourceLocation[] TEXTURES = {
            Hoarfrost.id("block/snow_cover"), Hoarfrost.id("block/snow_packed"), Hoarfrost.id("block/print")};
    private static final int TEX_SOLID = 0;
    private static final int TEX_PACKED = 1;
    private static final int TEX_PRINT = 2;
    /** Fresh snow is not flat: small lumps, up to this high, fading out as it is packed or thin. */
    private static final float FLUFF = 0.022F;
    private static final int FLUFF_SEED = 0x7F1F;
    /** Prints lie this far above the snow, so the two never flicker into each other far away. */
    private static final float PRINT_LIFT = 0.012F;
    /** A print shows until this much new snow has fallen into it. */
    private static final float PRINT_FILL = 0.05F;
    /** Depths below this are not drawn at all. */
    private static final float MIN_DEPTH = 0.004F;
    /**
     * The surface always sits this much above the block top (a quarter pixel): any less and far away the depth buffer
     * cannot tell them apart - the block shows through the snow in flickering holes, worst on translucent ice.
     */
    private static final float LIFT = 0.015F;
    private static final long REBUILD_BUDGET_NANOS = 3_000_000L;
    private static final double FAR_DISTANCE = 64.0;
    private static final double FAR_HYSTERESIS = 8.0;
    private static final int RING = CoverChunk.TILE + 2;
    private static final int RING_AREA = RING * RING;
    private static final int BLOCK_RING = 18;
    private static final float TILE_BLOCKS = CoverChunk.TILE * SnowCover.CELL;

    private final SnowCover cover;
    private final Terrain terrain;
    private final Long2ObjectOpenHashMap<ChunkView> views = new Long2ObjectOpenHashMap<>();
    private final List<Mesh> dirty = new ArrayList<>();
    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
    /** The cells of a tile and the ring around it, both surfaces: index {@code layer * RING_AREA + z * RING + x}. */
    private final boolean[] ringHas = new boolean[RING_AREA * 2];
    private final float[] ringBase = new float[RING_AREA * 2];
    private final float[] ringDepth = new float[RING_AREA * 2];
    private final float[] ringPacked = new float[RING_AREA * 2];
    private final float[] blockBase = new float[BLOCK_RING * BLOCK_RING];
    private final float[] blockDepth = new float[BLOCK_RING * BLOCK_RING];
    private final float[] blockPacked = new float[BLOCK_RING * BLOCK_RING];
    private final boolean[] blockHas = new boolean[BLOCK_RING * BLOCK_RING];
    private final float[] u0 = new float[TEXTURES.length];
    private final float[] v0 = new float[TEXTURES.length];
    private final float[] u1 = new float[TEXTURES.length];
    private final float[] v1 = new float[TEXTURES.length];
    private boolean shaderPack;
    private double originY;
    private int tileCellX;
    private int tileCellZ;
    private final int[] cornerGroup = new int[4];
    private final boolean[] cornerTaken = new boolean[4];
    private final float[] cornerBases = new float[5];
    private int frame;
    private int lastQuads;

    CoverRenderer(SnowCover cover, Terrain terrain) {
        this.cover = cover;
        this.terrain = terrain;
    }

    /** A static GPU mesh with its origin, bounds and the revision it was built from. */
    private abstract static class Mesh {
        final CoverChunk chunk;
        double originX;
        double originY;
        double originZ;
        AABB bounds;
        VertexBuffer buffer;
        boolean empty = true;
        int builtRevision = Integer.MIN_VALUE;
        double distanceSq;
        int quads;

        Mesh(CoverChunk chunk) {
            this.chunk = chunk;
        }

        boolean built() {
            return builtRevision != Integer.MIN_VALUE;
        }

        void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            builtRevision = Integer.MIN_VALUE;
        }
    }

    private static final class Tile extends Mesh {
        final int index;
        final int cellX;
        final int cellZ;

        Tile(CoverChunk chunk, int index) {
            super(chunk);
            this.index = index;
            cellX = chunk.x * CoverChunk.SIZE + index % CoverChunk.TILES * CoverChunk.TILE;
            cellZ = chunk.z * CoverChunk.SIZE + index / CoverChunk.TILES * CoverChunk.TILE;
            originX = cellX * SnowCover.CELL;
            originZ = cellZ * SnowCover.CELL;
        }
    }

    private static final class FarMesh extends Mesh {
        FarMesh(CoverChunk chunk) {
            super(chunk);
            originX = chunk.x * 16;
            originZ = chunk.z * 16;
        }
    }

    private static final class ChunkView {
        final CoverChunk chunk;
        final Tile[] tiles = new Tile[CoverChunk.TILES * CoverChunk.TILES];
        FarMesh far;
        boolean farMode;
        boolean tilesReady;
        int seenFrame;

        ChunkView(CoverChunk chunk) {
            this.chunk = chunk;
        }

        void closeTiles() {
            for (int t = 0; t < tiles.length; t++) {
                if (tiles[t] != null) {
                    tiles[t].close();
                    tiles[t] = null;
                }
            }
        }

        void close() {
            closeTiles();
            if (far != null) {
                far.close();
                far = null;
            }
        }

        boolean drawFar() {
            return far != null && far.built() && (farMode || !tilesReady);
        }
    }

    int lastQuads() {
        return lastQuads;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    void render(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection, double radius) {
        updateSprites();
        Vec3 cam = camera.getPosition();
        frame++;
        // While a shader pack is active, Iris reads BLOCK vertices in its larger terrain layout - also from buffers
        // built before: switching a pack on or off rebuilds every mesh.
        if (ShaderPacks.inUse() != shaderPack) {
            shaderPack = !shaderPack;
            for (ChunkView view : views.values()) {
                view.close();
            }
            views.clear();
        }
        collect(cam, radius);
        rebuild();

        COVER.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            COVER.clearRenderState();
            return;
        }
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        Uniform offset = shader.CHUNK_OFFSET;
        int quads = 0;
        for (ChunkView view : views.values()) {
            if (view.drawFar()) {
                quads += draw(view.far, frustum, offset, cam);
            } else {
                for (Tile tile : view.tiles) {
                    if (tile != null) {
                        quads += draw(tile, frustum, offset, cam);
                    }
                }
            }
        }
        lastQuads = quads;
        if (offset != null) {
            offset.set(0.0F, 0.0F, 0.0F);
        }
        VertexBuffer.unbind();
        shader.clear();
        COVER.clearRenderState();
    }

    private static int draw(Mesh mesh, Frustum frustum, Uniform offset, Vec3 cam) {
        if (mesh.buffer == null || mesh.empty || mesh.bounds == null || !frustum.isVisible(mesh.bounds)) {
            return 0;
        }
        if (offset != null) {
            offset.set((float) (mesh.originX - cam.x), (float) (mesh.originY - cam.y), (float) (mesh.originZ - cam.z));
            offset.upload();
        }
        mesh.buffer.bind();
        mesh.buffer.draw();
        return mesh.quads;
    }

    private void updateSprites() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        for (int t = 0; t < TEXTURES.length; t++) {
            TextureAtlasSprite sprite = atlas.getSprite(TEXTURES[t]);
            u0[t] = sprite.getU0();
            v0[t] = sprite.getV0();
            u1[t] = sprite.getU1();
            v1[t] = sprite.getV1();
        }
    }

    private void collect(Vec3 cam, double radius) {
        dirty.clear();
        double nearSq = (FAR_DISTANCE - FAR_HYSTERESIS) * (FAR_DISTANCE - FAR_HYSTERESIS);
        double farSq = (FAR_DISTANCE + FAR_HYSTERESIS) * (FAR_DISTANCE + FAR_HYSTERESIS);
        double limitSq = (radius + 8.0) * (radius + 8.0);
        for (CoverChunk chunk : cover.chunks()) {
            if (!chunk.hasSurfaces()) {
                continue;
            }
            double cx = chunk.x * 16 + 8 - cam.x;
            double cz = chunk.z * 16 + 8 - cam.z;
            double distanceSq = cx * cx + cz * cz;
            if (distanceSq > limitSq) {
                continue;
            }
            long key = ChunkPos.asLong(chunk.x, chunk.z);
            ChunkView view = views.get(key);
            if (view == null || view.chunk != chunk) {
                if (view != null) {
                    view.close();
                }
                view = new ChunkView(chunk);
                views.put(key, view);
            }
            view.seenFrame = frame;
            view.farMode = view.farMode ? distanceSq > nearSq : distanceSq > farSq;
            if (view.farMode) {
                if (view.far == null) {
                    view.far = new FarMesh(chunk);
                }
                view.far.distanceSq = distanceSq;
                if (view.far.builtRevision != chunk.revision()) {
                    dirty.add(view.far);
                }
                if (view.far.built()) {
                    view.closeTiles();
                    view.tilesReady = false;
                }
                continue;
            }
            boolean ready = true;
            for (int t = 0; t < view.tiles.length; t++) {
                Tile tile = view.tiles[t];
                if (tile == null) {
                    tile = new Tile(chunk, t);
                    view.tiles[t] = tile;
                }
                double dx = tile.originX + TILE_BLOCKS * 0.5 - cam.x;
                double dz = tile.originZ + TILE_BLOCKS * 0.5 - cam.z;
                tile.distanceSq = dx * dx + dz * dz;
                if (tile.builtRevision != chunk.tileRevision[t]) {
                    dirty.add(tile);
                }
                ready &= tile.built();
            }
            view.tilesReady = ready;
            if (ready && view.far != null) {
                view.far.close();
                view.far = null;
            }
        }
        for (Iterator<Long2ObjectMap.Entry<ChunkView>> it = views.long2ObjectEntrySet().iterator(); it.hasNext(); ) {
            ChunkView view = it.next().getValue();
            if (view.seenFrame != frame) {
                view.close();
                it.remove();
            }
        }
    }

    private void rebuild() {
        if (dirty.isEmpty()) {
            return;
        }
        dirty.sort((a, b) -> Double.compare(a.distanceSq, b.distanceSq));
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        for (Mesh mesh : dirty) {
            if (mesh instanceof Tile tile) {
                buildTile(tile);
            } else {
                buildFar((FarMesh) mesh);
            }
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        VertexBuffer.unbind();
    }

    private void upload(Mesh mesh, BufferBuilder builder) {
        MeshData data = builder.build();
        if (data == null) {
            mesh.empty = true;
            return;
        }
        if (mesh.buffer == null) {
            mesh.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        mesh.buffer.bind();
        mesh.buffer.upload(data);
        mesh.empty = false;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Near tiles
    // ------------------------------------------------------------------------------------------------------------

    private void loadRing(Tile tile) {
        for (int rz = 0; rz < RING; rz++) {
            for (int rx = 0; rx < RING; rx++) {
                int cellX = tile.cellX + rx - 1;
                int cellZ = tile.cellZ + rz - 1;
                CoverChunk chunk = rx > 0 && rx < RING - 1 && rz > 0 && rz < RING - 1 ? tile.chunk : cover.chunkAtCell(cellX, cellZ);
                int c = SnowCover.index(cellX, cellZ);
                for (int layer = 0; layer < 2; layer++) {
                    int r = layer * RING_AREA + rz * RING + rx;
                    int i = c + layer * CoverChunk.AREA;
                    boolean has = chunk != null && chunk.hasSurface(i);
                    ringHas[r] = has;
                    if (has) {
                        ringBase[r] = chunk.base[i];
                        float depth = chunk.depth(i);
                        ringDepth[r] = depth;
                        ringPacked[r] = depth > 0.0F ? chunk.packed[i] / depth : 0.0F;
                    } else {
                        ringDepth[r] = 0.0F;
                    }
                }
            }
        }
    }

    /**
     * The surface of a ring cell (either layer) at the level {@code base}, or -1: the open ground and the ground under a
     * crown beside it are one surface.
     */
    private int match(int rz, int rx, float base) {
        int best = -1;
        float bestGap = SnowCover.LEVEL_STEP;
        for (int layer = 0; layer < 2; layer++) {
            int r = layer * RING_AREA + rz * RING + rx;
            if (ringHas[r]) {
                float gap = Math.abs(ringBase[r] - base);
                if (gap < bestGap) {
                    best = r;
                    bestGap = gap;
                }
            }
        }
        return best;
    }

    private void buildTile(Tile tile) {
        CoverChunk chunk = tile.chunk;
        tile.builtRevision = chunk.tileRevision[tile.index];
        loadRing(tile);
        tileCellX = tile.cellX;
        tileCellZ = tile.cellZ;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int layer = 0; layer < 2; layer++) {
            for (int lz = 0; lz < CoverChunk.TILE; lz++) {
                for (int lx = 0; lx < CoverChunk.TILE; lx++) {
                    int r = layer * RING_AREA + (lz + 1) * RING + lx + 1;
                    int i = SnowCover.index(tile.cellX + lx, tile.cellZ + lz) + layer * CoverChunk.AREA;
                    chunk.shown[i] = ringDepth[r];
                    if (ringHas[r] && ringDepth[r] >= MIN_DEPTH) {
                        minY = Math.min(minY, ringBase[r]);
                        maxY = Math.max(maxY, ringBase[r] + ringDepth[r]);
                    }
                }
            }
        }
        if (minY == Double.MAX_VALUE) {
            tile.empty = true;
            tile.quads = 0;
            return;
        }
        tile.originY = Math.floor(minY);
        originY = tile.originY;
        tile.bounds = new AABB(tile.originX, minY - 0.1, tile.originZ, tile.originX + TILE_BLOCKS, maxY + 0.2, tile.originZ + TILE_BLOCKS);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        for (int layer = 0; layer < 2; layer++) {
            for (int lz = 0; lz < CoverChunk.TILE; lz++) {
                for (int lx = 0; lx < CoverChunk.TILE; lx++) {
                    quads += cell(builder, tile, layer, lx, lz);
                }
            }
        }
        quads += prints(builder, tile);
        tile.quads = quads;
        upload(tile, builder);
    }

    /** One cell of one surface: its smooth top and, where the snow ends at a drop, its side. */
    private int cell(BufferBuilder builder, Tile tile, int layer, int lx, int lz) {
        int r = layer * RING_AREA + (lz + 1) * RING + lx + 1;
        float depth = ringDepth[r];
        if (!ringHas[r] || depth < MIN_DEPTH) {
            return 0;
        }
        float base = ringBase[r];
        int cellX = tile.cellX + lx;
        int cellZ = tile.cellZ + lz;
        int light = terrain.lightAt((cellX + 0.5) * SnowCover.CELL, base + depth + 0.1, (cellZ + 0.5) * SnowCover.CELL);
        float h00 = corner(layer, lx, lz, base, depth);
        float h10 = corner(layer, lx + 1, lz, base, depth);
        float h11 = corner(layer, lx + 1, lz + 1, base, depth);
        float h01 = corner(layer, lx, lz + 1, base, depth);
        int texture = ringPacked[r] > 0.5F ? TEX_PACKED : TEX_SOLID;
        int tint = shadowTint(lx + 1, lz + 1, base, depth);
        float x0 = lx * SnowCover.CELL;
        float z0 = lz * SnowCover.CELL;
        float x1 = x0 + SnowCover.CELL;
        float z1 = z0 + SnowCover.CELL;
        float du = (u1[texture] - u0[texture]) * 0.25F;
        float dv = (v1[texture] - v0[texture]) * 0.25F;
        float u = u0[texture] + du * (cellX & 3);
        float v = v0[texture] + dv * (cellZ & 3);
        // Counter-clockwise seen from above: shader packs recompute the normal from the winding.
        topVertex(builder, x0, h00, z0, u, v, light, h10 - h00, h01 - h00, tint);
        topVertex(builder, x0, h01, z1, u, v + dv, light, h11 - h01, h01 - h00, tint);
        topVertex(builder, x1, h11, z1, u + du, v + dv, light, h11 - h01, h11 - h10, tint);
        topVertex(builder, x1, h10, z0, u + du, v, light, h10 - h00, h11 - h10, tint);
        int quads = 1;
        float floor = (float) (base - originY);
        if (depth > 0.0F) {
            quads += side(builder, lz + 1, lx + 2, base, x1, z1, x1, z0, h11, h10, floor, 1.0F, 0.0F, light);
            quads += side(builder, lz + 1, lx, base, x0, z0, x0, z1, h00, h01, floor, -1.0F, 0.0F, light);
            quads += side(builder, lz + 2, lx + 1, base, x0, z1, x1, z1, h01, h11, floor, 0.0F, 1.0F, light);
            quads += side(builder, lz, lx + 1, base, x1, z0, x0, z0, h10, h00, floor, 0.0F, -1.0F, light);
        }
        return quads;
    }

    /**
     * Snow in a hollow (a print, the trough beside a drift) lies in the shade of its rims and takes the blue of the
     * sky, like real snow: the deeper the cell lies below its neighbours, the bluer it is drawn.
     */
    private int shadowTint(int rx, int rz, float base, float depth) {
        float own = base + depth;
        float sum = 0.0F;
        int n = 0;
        for (int k = 0; k < 4; k++) {
            int o = match(rz + (k == 2 ? 1 : k == 3 ? -1 : 0), rx + (k == 0 ? 1 : k == 1 ? -1 : 0), base);
            if (o >= 0) {
                sum += ringBase[o] + ringDepth[o];
                n++;
            }
        }
        float hollow = n == 0 ? 0.0F : Mth.clamp((sum / n - own) / 0.12F, 0.0F, 1.0F);
        float red = 1.0F - 0.2F * hollow;
        float green = 1.0F - 0.12F * hollow;
        return (int) (red * 255.0F) << 16 | (int) (green * 255.0F) << 8 | 255;
    }

    /**
     * Height of a cell corner relative to the mesh origin: the average snow surface of the cells around it that belong
     * to one surface with this cell. Cells belong together when their grounds are within {@link SnowCover#LEVEL_STEP}
     * of each other, also through one another (the open ground, the ground under a crown and a low snow layer beside
     * them are one surface), so every cell sharing a corner finds the same group and the same height - the surface
     * has no cracks. A neighbor without snow counts with its bare ground (the snow thins out towards it). Fresh powder
     * gets small lumps.
     */
    private float corner(int layer, int cornerX, int cornerZ, float base, float ownDepth) {
        // The corner's four cells are (cornerX..+1, cornerZ..+1); the group grows from this cell's ground.
        int[] group = cornerGroup;
        int count = 0;
        boolean[] taken = cornerTaken;
        for (int k = 0; k < 4; k++) {
            taken[k] = false;
        }
        float[] bases = cornerBases;
        int size = 0;
        bases[size++] = base;
        boolean grown = true;
        while (grown) {
            grown = false;
            for (int k = 0; k < 4; k++) {
                if (taken[k]) {
                    continue;
                }
                int rz = cornerZ + (k >> 1);
                int rx = cornerX + (k & 1);
                int best = -1;
                float bestGap = SnowCover.LEVEL_STEP;
                for (int m = 0; m < size; m++) {
                    int r = match(rz, rx, bases[m]);
                    if (r >= 0 && Math.abs(ringBase[r] - bases[m]) < bestGap) {
                        best = r;
                        bestGap = Math.abs(ringBase[r] - bases[m]);
                    }
                }
                if (best >= 0) {
                    taken[k] = true;
                    group[count++] = best;
                    bases[size++] = ringBase[best];
                    grown = true;
                }
            }
        }
        float sum = 0.0F;
        float floor = Float.NEGATIVE_INFINITY;
        float depth = 0.0F;
        float packed = 0.0F;
        for (int g = 0; g < count; g++) {
            int r = group[g];
            sum += ringBase[r] + ringDepth[r];
            floor = Math.max(floor, ringBase[r]);
            depth += ringDepth[r];
            packed += ringPacked[r] * ringDepth[r];
        }
        if (count == 0) {
            sum = base + ownDepth;
            floor = base;
            count = 1;
        }
        float lumps = 0.0F;
        if (depth > 0.0F) {
            float fluff = Math.min(1.0F, depth / count / 0.12F) * (1.0F - packed / depth);
            lumps = (Noise.unit(tileCellX + cornerX, tileCellZ + cornerZ, FLUFF_SEED) - 0.35F) * FLUFF * fluff;
        }
        return (float) (Math.max(sum / count + lumps, floor + LIFT) - originY);
    }

    /**
     * Height of the drawn surface of a layer at a point inside the tile (relative to the origin), from the corners
     * around it; NaN where that surface has no snow.
     */
    private float surfaceAt(int layer, float x, float z) {
        float gx = Mth.clamp(x / SnowCover.CELL, 0.0F, CoverChunk.TILE - 0.001F);
        float gz = Mth.clamp(z / SnowCover.CELL, 0.0F, CoverChunk.TILE - 0.001F);
        int lx = (int) gx;
        int lz = (int) gz;
        int r = layer * RING_AREA + (lz + 1) * RING + lx + 1;
        if (!ringHas[r]) {
            return Float.NaN;
        }
        float base = ringBase[r];
        float depth = ringDepth[r];
        float fx = gx - lx;
        float fz = gz - lz;
        float a = corner(layer, lx, lz, base, depth);
        float b = corner(layer, lx + 1, lz, base, depth);
        float c = corner(layer, lx, lz + 1, base, depth);
        float d = corner(layer, lx + 1, lz + 1, base, depth);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    /**
     * The footprints in the tile: square dents of whole pixels pressed into the snow, square to the block grid like
     * everything in the game, sized by the foot that made them, following the surface. A print is gone once new snow has filled it
     * or the snow has melted.
     */
    private int prints(BufferBuilder builder, Tile tile) {
        CoverChunk chunk = tile.chunk;
        int quads = 0;
        float tileX = (float) (tile.originX - chunk.x * 16);
        float tileZ = (float) (tile.originZ - chunk.z * 16);
        for (int p = 0; p < chunk.printCount(); p++) {
            float px = chunk.printX[p] - tileX;
            float pz = chunk.printZ[p] - tileZ;
            if (px < 0.0F || pz < 0.0F || px >= TILE_BLOCKS || pz >= TILE_BLOCKS) {
                continue;
            }
            int lx = (int) (px / SnowCover.CELL);
            int lz = (int) (pz / SnowCover.CELL);
            // The surface the print was pressed into.
            int layer = -1;
            float gap = 0.4F;
            for (int l = 0; l < 2; l++) {
                int r = l * RING_AREA + (lz + 1) * RING + lx + 1;
                if (ringHas[r] && Math.abs(ringBase[r] + ringDepth[r] - chunk.printTop[p]) < gap) {
                    gap = Math.abs(ringBase[r] + ringDepth[r] - chunk.printTop[p]);
                    layer = l;
                }
            }
            if (layer < 0) {
                continue;
            }
            int r = layer * RING_AREA + (lz + 1) * RING + lx + 1;
            if (ringDepth[r] < 0.01F || (ringBase[r] + ringDepth[r] - chunk.printTop[p]) / PRINT_FILL > 0.6F) {
                continue;
            }
            int texture = TEX_PRINT;
            // A square dent of whole pixels, like everything in the game.
            float halfL = Math.max(1.5F, Math.round(chunk.printSize[p] * 16.0F * 0.5F)) / 16.0F;
            float halfW = halfL;
            // Headings are along the axes: the print's pixels line up with the blocks.
            int quarter = Math.floorMod(Math.round(chunk.printYaw[p] / (Mth.PI * 0.5F)), 4);
            float ax = quarter == 0 ? 1.0F : quarter == 2 ? -1.0F : 0.0F;
            float az = quarter == 1 ? 1.0F : quarter == 3 ? -1.0F : 0.0F;
            int light = terrain.lightAt(tile.originX + px, ringBase[r] + ringDepth[r] + 0.1, tile.originZ + pz);
            float[] cx = {px + ax * halfL - az * halfW, px + ax * halfL + az * halfW, px - ax * halfL + az * halfW,
                    px - ax * halfL - az * halfW};
            float[] cz = {pz + az * halfL + ax * halfW, pz + az * halfL - ax * halfW, pz - az * halfL - ax * halfW,
                    pz - az * halfL + ax * halfW};
            float[] us = {u0[texture], u1[texture], u1[texture], u0[texture]};
            float[] vs = {v0[texture], v0[texture], v1[texture], v1[texture]};
            float[] hy = new float[4];
            boolean ok = true;
            for (int k = 0; k < 4; k++) {
                hy[k] = surfaceAt(layer, cx[k], cz[k]);
                if (Float.isNaN(hy[k])) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            // Wound counter-clockwise seen from above whichever way the print faces.
            boolean flip = (cx[1] - cx[0]) * (cz[3] - cz[0]) - (cz[1] - cz[0]) * (cx[3] - cx[0]) > 0.0F;
            for (int n = 0; n < 4; n++) {
                int k = flip ? 3 - n : n;
                builder.addVertex(cx[k], hy[k] + PRINT_LIFT, cz[k], 0xFFFFFFFF, us[k], vs[k], OverlayTexture.NO_OVERLAY, light, 0.0F, 1.0F,
                        0.0F);
            }
            quads++;
        }
        return quads;
    }

    /** One corner of the top surface with a normal from the slopes of the cell's edges; lit like vanilla faces. */
    private void topVertex(BufferBuilder builder, float x, float y, float z, float u, float v, int light, float riseX, float riseZ,
            int tint) {
        float slopeX = riseX / SnowCover.CELL;
        float slopeZ = riseZ / SnowCover.CELL;
        float length = Mth.sqrt(slopeX * slopeX + 1.0F + slopeZ * slopeZ);
        float nx = -slopeX / length;
        float ny = 1.0F / length;
        float nz = -slopeZ / length;
        float shade = nx * nx * 0.6F + ny * ny + nz * nz * 0.8F;
        int r = (int) ((tint >> 16 & 0xFF) * shade);
        int g = (int) ((tint >> 8 & 0xFF) * shade);
        int b = (int) ((tint & 0xFF) * shade);
        builder.addVertex(x, y, z, 0xFF000000 | r << 16 | g << 8 | b, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }

    /**
     * The side of the snow along an edge where the neighbor is not part of the same surface: down from this cell's edge
     * to the snow of a lower neighbor (a little below it, so no slit stays open on slopes), or to this cell's own ground
     * at a drop or over water. The edge runs from (xa, za) to (xb, zb), counter-clockwise seen from outside. Nothing
     * where the neighbor's snow is as high (it draws its own side down to ours).
     */
    private int side(BufferBuilder builder, int rz, int rx, float base, float xa, float za, float xb, float zb, float ha, float hb,
            float floor, float nx, float nz, int light) {
        if (match(rz, rx, base) >= 0) {
            return 0;
        }
        float edge = Math.max(ha, hb);
        float bottom = floor;
        for (int layer = 0; layer < 2; layer++) {
            int r = layer * RING_AREA + rz * RING + rx;
            if (ringHas[r]) {
                float top = (float) (ringBase[r] + ringDepth[r] - originY);
                if (top >= edge - 0.001F) {
                    return 0;
                }
                bottom = Math.max(bottom, top - 0.1F);
            }
        }
        if (edge - bottom < 0.004F) {
            return 0;
        }
        int texture = TEX_SOLID;
        float du = (u1[texture] - u0[texture]) * 0.25F;
        float dv = (v1[texture] - v0[texture]) * 0.25F;
        float u = u0[texture];
        float vTop = v0[texture];
        // One block of height spans the whole sprite.
        float vBottom = vTop + dv * 4.0F * Math.min(1.0F, edge - bottom);
        float shade = Math.abs(nx) > 0.5F ? 0.6F : 0.8F;
        int c = (int) (255.0F * shade);
        int color = 0xFF000000 | c << 16 | c << 8 | c;
        int overlay = OverlayTexture.NO_OVERLAY;
        builder.addVertex(xa, ha, za, color, u, vTop, overlay, light, nx, 0.0F, nz);
        builder.addVertex(xa, bottom, za, color, u, vBottom, overlay, light, nx, 0.0F, nz);
        builder.addVertex(xb, bottom, zb, color, u + du, vBottom, overlay, light, nx, 0.0F, nz);
        builder.addVertex(xb, hb, zb, color, u + du, vTop, overlay, light, nx, 0.0F, nz);
        return 1;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Far chunks
    // ------------------------------------------------------------------------------------------------------------

    private void buildFar(FarMesh mesh) {
        CoverChunk chunk = mesh.chunk;
        mesh.builtRevision = chunk.revision();
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        for (int bz = 0; bz < BLOCK_RING; bz++) {
            for (int bx = 0; bx < BLOCK_RING; bx++) {
                int b = bz * BLOCK_RING + bx;
                int blockX = chunk.x * 16 + bx - 1;
                int blockZ = chunk.z * 16 + bz - 1;
                CoverChunk source = bx > 0 && bx < 17 && bz > 0 && bz < 17 ? chunk : cover.chunkAtCell(blockX * 4, blockZ * 4);
                float baseSum = 0.0F;
                float depthSum = 0.0F;
                float packedSum = 0.0F;
                int cells = 0;
                if (source != null) {
                    for (int c = 0; c < 16; c++) {
                        int i = SnowCover.index(blockX * 4 + (c & 3), blockZ * 4 + (c >> 2));
                        if (source.hasSurface(i)) {
                            baseSum += source.base[i];
                            depthSum += source.depth(i);
                            packedSum += source.packed[i];
                            cells++;
                        }
                    }
                }
                blockHas[b] = cells > 8;
                if (blockHas[b]) {
                    blockBase[b] = baseSum / cells;
                    blockDepth[b] = depthSum / 16.0F;
                    blockPacked[b] = depthSum > 0.0F ? packedSum / depthSum : 0.0F;
                    if (bx > 0 && bx < 17 && bz > 0 && bz < 17 && blockDepth[b] >= MIN_DEPTH * 2.0F) {
                        minY = Math.min(minY, blockBase[b]);
                        maxY = Math.max(maxY, blockBase[b] + blockDepth[b]);
                    }
                }
            }
        }
        if (minY == Double.MAX_VALUE) {
            mesh.empty = true;
            mesh.quads = 0;
            return;
        }
        mesh.originY = Math.floor(minY);
        originY = mesh.originY;
        mesh.bounds = new AABB(mesh.originX, minY - 0.1, mesh.originZ, mesh.originX + 16, maxY + 0.3, mesh.originZ + 16);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        for (int bz = 1; bz < 17; bz++) {
            for (int bx = 1; bx < 17; bx++) {
                int b = bz * BLOCK_RING + bx;
                float depth = blockDepth[b];
                if (!blockHas[b] || depth < MIN_DEPTH * 2.0F) {
                    continue;
                }
                float base = blockBase[b];
                float h00 = farCorner(bx, bz, b);
                float h10 = farCorner(bx + 1, bz, b);
                float h11 = farCorner(bx + 1, bz + 1, b);
                float h01 = farCorner(bx, bz + 1, b);
                int texture = blockPacked[b] > 0.5F ? TEX_PACKED : TEX_SOLID;
                int light = terrain.lightAt(mesh.originX + bx - 0.5, base + depth + 0.1, mesh.originZ + bz - 0.5);
                float x0 = bx - 1;
                float z0 = bz - 1;
                topVertex(builder, x0, h00, z0, u0[texture], v0[texture], light, 0.0F, 0.0F, 0xFFFFFF);
                topVertex(builder, x0, h01, z0 + 1.0F, u0[texture], v1[texture], light, 0.0F, 0.0F, 0xFFFFFF);
                topVertex(builder, x0 + 1.0F, h11, z0 + 1.0F, u1[texture], v1[texture], light, 0.0F, 0.0F, 0xFFFFFF);
                topVertex(builder, x0 + 1.0F, h10, z0, u1[texture], v0[texture], light, 0.0F, 0.0F, 0xFFFFFF);
                quads++;
            }
        }
        mesh.quads = quads;
        upload(mesh, builder);
    }

    private float farCorner(int cornerX, int cornerZ, int self) {
        float base = blockBase[self];
        float own = base + blockDepth[self];
        float sum = 0.0F;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int b = (cornerZ + dz) * BLOCK_RING + cornerX + dx;
                sum += blockHas[b] && Math.abs(blockBase[b] - base) < 0.6F ? blockBase[b] + blockDepth[b] : own;
            }
        }
        return (float) (Math.max(sum * 0.25F, base + LIFT * 2.0F) - originY);
    }

    @Override
    public void close() {
        for (ChunkView view : views.values()) {
            view.close();
        }
        views.clear();
        bytes.close();
    }
}
