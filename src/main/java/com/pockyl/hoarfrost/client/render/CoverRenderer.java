package com.pockyl.hoarfrost.client.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.compat.ShaderPacks;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.CoverSurvey;
import com.pockyl.hoarfrost.sim.SnowCover;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Draws the snow lying on the world as one smooth, soft surface over the blocks.
 *
 * <ul>
 *   <li>The height of the snow is known per block column; the surface through those heights is a quadratic B-spline, so it
 *   is smooth everywhere - drifts are rounded swells, the snow rises softly over slabs, stairs and the steps of vanilla
 *   snow layers, and there are no steps from block to block. Near the camera every block is drawn as 4x4 small quads,
 *   further away 2x2 and 1.</li>
 *   <li>Where the snow thins out it does not end in a line: thin snow is drawn as a dusting with gaps, the ground showing
 *   through more and more (the snow texture's alpha holds soft noise, and the depth sets how much of it is drawn), so
 *   snow fades out in an organic pattern.</li>
 *   <li>Where snow ends at a drop (the edge of a roof, a cliff, the shore) it rounds off into a soft rim; on narrow tops
 *   (fences, walls, posts) it is a small rounded cap that overhangs a little.</li>
 *   <li>Footprints are plain soft dents.</li>
 * </ul>
 * Each chunk is a static GPU buffer, rebuilt when its snow visibly changed. The vanilla cutout terrain shader and the block
 * atlas are used, so fog, light and day and night match the world, and shader packs draw the snow with their terrain
 * program.
 */
public final class CoverRenderer implements AutoCloseable {
    private static final RenderType COVER = RenderType.create(Hoarfrost.MOD_ID + "_snow_cover", DefaultVertexFormat.BLOCK,
            VertexFormat.Mode.QUADS, 1 << 16, false, false, RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_MIPPED_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET_MIPPED)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));
    private static final ResourceLocation TEXTURE = Hoarfrost.id("block/snow_cover");
    /** Blocks the snow texture spans in each direction. */
    private static final int TEXTURE_BLOCKS = 4;
    /**
     * The surface always sits this much above the block top: any less and far away the depth buffer cannot tell them
     * apart - the block shows through the snow in flickering holes.
     */
    private static final float LIFT = 0.012F;
    /** Columns with less snow than this are not drawn (unless snow next to them fades into them). */
    private static final float MIN_DEPTH = 0.0015F;
    /** Depth from which the snow covers the ground completely; below it the ground shows through in gaps. */
    private static final float FULL_COVER = 0.05F;
    private static final long REBUILD_BUDGET_NANOS = 2_500_000L;
    private static final double NEAR = 28.0;
    private static final double MIDDLE = 64.0;
    private static final double HYSTERESIS = 6.0;
    /** Direction of the soft light that shapes the snow surface (from above, a little from the side). */
    private static final float LIGHT_X = -0.32F;
    private static final float LIGHT_Y = 0.9F;
    private static final float LIGHT_Z = -0.28F;

    private final SnowCover cover;
    private final Long2ObjectOpenHashMap<Mesh> meshes = new Long2ObjectOpenHashMap<>();
    private final List<Mesh> dirty = new ArrayList<>();
    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
    private float u0;
    private float v0;
    private float u1;
    private float v1;
    private boolean shaderPack;
    private int lastQuads;

    // Scratch for building: the 3x3 neighbourhood of a column and the vertex grid of a block.
    private final float[] nHeight = new float[9];
    private final float[] nDepth = new float[9];
    private final float[] nBlock = new float[9];
    private final float[] nSky = new float[9];
    private final boolean[] nDrop = new boolean[9];
    private final float[] gridY = new float[25];
    private final float[] gridAlpha = new float[25];
    private final float[] gridNx = new float[25];
    private final float[] gridNy = new float[25];
    private final float[] gridNz = new float[25];
    private final int[] gridLight = new int[25];
    private final float[] wu = new float[3];
    private final float[] wv = new float[3];
    private final float[] du = new float[3];
    private final float[] dv = new float[3];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    // Dents around the chunk being built.
    private final float[] dentX = new float[CoverChunk.MAX_DENTS * 9];
    private final float[] dentZ = new float[CoverChunk.MAX_DENTS * 9];
    private final float[] dentR = new float[CoverChunk.MAX_DENTS * 9];
    private final float[] dentD = new float[CoverChunk.MAX_DENTS * 9];
    private final float[] dentBase = new float[CoverChunk.MAX_DENTS * 9];
    private int dentCount;
    private int quads;
    private float minY;
    private float maxY;

    public CoverRenderer(SnowCover cover) {
        this.cover = cover;
    }

    /** A static GPU mesh of one chunk. */
    private static final class Mesh {
        final CoverChunk chunk;
        final double originX;
        final double originZ;
        double originY;
        AABB bounds;
        VertexBuffer buffer;
        boolean empty = true;
        int builtRevision = Integer.MIN_VALUE;
        int builtDetail;
        int builtNeighbours;
        int detail = 1;
        double distanceSq;
        int quads;

        Mesh(CoverChunk chunk) {
            this.chunk = chunk;
            originX = chunk.x * 16;
            originZ = chunk.z * 16;
        }

        void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
            builtRevision = Integer.MIN_VALUE;
        }
    }

    public int lastQuads() {
        return lastQuads;
    }

    public int meshCount() {
        return meshes.size();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------------------------------------------------

    public void render(Camera camera, Frustum frustum, Matrix4f modelView, Matrix4f projection, double radius) {
        updateSprite();
        Vec3 cam = camera.getPosition();
        // While a shader pack is active, Iris reads BLOCK vertices in its larger terrain layout - also from buffers built
        // before: switching a pack on or off rebuilds every mesh.
        if (ShaderPacks.inUse() != shaderPack) {
            shaderPack = !shaderPack;
            closeAll();
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
        int drawn = 0;
        for (Mesh mesh : meshes.values()) {
            if (mesh.buffer == null || mesh.empty || mesh.bounds == null || !frustum.isVisible(mesh.bounds)) {
                continue;
            }
            if (offset != null) {
                offset.set((float) (mesh.originX - cam.x), (float) (mesh.originY - cam.y), (float) (mesh.originZ - cam.z));
                offset.upload();
            }
            mesh.buffer.bind();
            mesh.buffer.draw();
            drawn += mesh.quads;
        }
        lastQuads = drawn;
        if (offset != null) {
            offset.set(0.0F, 0.0F, 0.0F);
        }
        VertexBuffer.unbind();
        shader.clear();
        COVER.clearRenderState();
    }

    private void updateSprite() {
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite sprite = atlas.getSprite(TEXTURE);
        u0 = sprite.getU0();
        v0 = sprite.getV0();
        u1 = sprite.getU1();
        v1 = sprite.getV1();
    }

    private void collect(Vec3 cam, double radius) {
        dirty.clear();
        double keepSq = (radius + 24.0) * (radius + 24.0);
        for (Iterator<Mesh> it = meshes.values().iterator(); it.hasNext(); ) {
            Mesh mesh = it.next();
            if (cover.chunk(mesh.chunk.x, mesh.chunk.z) != mesh.chunk) {
                mesh.close();
                it.remove();
            }
        }
        for (CoverChunk chunk : cover.chunks()) {
            double dx = chunk.x * 16 + 8 - cam.x;
            double dz = chunk.z * 16 + 8 - cam.z;
            double distanceSq = dx * dx + dz * dz;
            long key = ChunkPos.asLong(chunk.x, chunk.z);
            Mesh mesh = meshes.get(key);
            if (distanceSq > keepSq || !chunk.surveyed()) {
                continue;
            }
            if (mesh == null) {
                mesh = new Mesh(chunk);
                meshes.put(key, mesh);
            }
            mesh.distanceSq = distanceSq;
            double distance = Math.sqrt(distanceSq);
            int detail = mesh.detail;
            // Finer near the camera, with some slack so a mesh does not flip between two levels.
            if (distance < NEAR - HYSTERESIS) {
                detail = 4;
            } else if (distance > NEAR + HYSTERESIS && distance < MIDDLE - HYSTERESIS) {
                detail = 2;
            } else if (distance > MIDDLE + HYSTERESIS) {
                detail = 1;
            } else if (detail == 1 && distance < MIDDLE - HYSTERESIS || detail == 4 && distance > NEAR + HYSTERESIS) {
                detail = 2;
            }
            mesh.detail = detail;
            int neighbours = neighbourState(chunk);
            if (mesh.builtRevision != chunk.revision() || mesh.builtDetail != detail || mesh.builtNeighbours != neighbours) {
                dirty.add(mesh);
            }
        }
    }

    /** Which neighbouring chunks are there and surveyed: the edges of a mesh look into them. */
    private int neighbourState(CoverChunk chunk) {
        int state = 0;
        int bit = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                CoverChunk neighbour = cover.chunk(chunk.x + dx, chunk.z + dz);
                if (neighbour != null && neighbour.surveyed()) {
                    state |= 1 << bit;
                }
                bit++;
            }
        }
        return state;
    }

    private void rebuild() {
        if (dirty.isEmpty()) {
            return;
        }
        dirty.sort((a, b) -> Double.compare(a.distanceSq, b.distanceSq));
        long deadline = System.nanoTime() + REBUILD_BUDGET_NANOS;
        for (Mesh mesh : dirty) {
            build(mesh);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
        VertexBuffer.unbind();
    }

    private void closeAll() {
        for (Mesh mesh : meshes.values()) {
            mesh.close();
        }
        meshes.clear();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building a chunk
    // ------------------------------------------------------------------------------------------------------------

    private void build(Mesh mesh) {
        CoverChunk chunk = mesh.chunk;
        mesh.builtRevision = chunk.revision();
        mesh.builtDetail = mesh.detail;
        mesh.builtNeighbours = neighbourState(chunk);
        float lowest = Float.MAX_VALUE;
        for (int e = 0; e < CoverChunk.ENTRIES; e++) {
            float b = chunk.base(e / CoverChunk.COLUMNS, e % CoverChunk.COLUMNS);
            if (!Float.isNaN(b) && chunk.depth(e / CoverChunk.COLUMNS, e % CoverChunk.COLUMNS) > 0.0F) {
                lowest = Math.min(lowest, b);
            }
        }
        if (lowest == Float.MAX_VALUE) {
            mesh.empty = true;
            mesh.quads = 0;
            return;
        }
        mesh.originY = Math.floor(lowest);
        gatherDents(chunk, mesh.detail == 4);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        quads = 0;
        minY = Float.MAX_VALUE;
        maxY = -Float.MAX_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int column = CoverChunk.index(lx, lz);
                for (int s = 0; s < CoverChunk.SURFACES; s++) {
                    if (!chunk.has(s, column)) {
                        continue;
                    }
                    if ((chunk.flags(s, column) & CoverChunk.CAP) != 0) {
                        if (chunk.depth(s, column) > 0.008F) {
                            cap(builder, chunk, s, column, lx, lz, mesh.originY);
                        }
                    } else {
                        column(builder, chunk, s, column, lx, lz, mesh.detail, mesh.originY);
                    }
                }
            }
        }
        MeshData data = builder.build();
        if (data == null) {
            mesh.empty = true;
            mesh.quads = 0;
            return;
        }
        if (mesh.buffer == null) {
            mesh.buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        mesh.buffer.bind();
        mesh.buffer.upload(data);
        mesh.empty = false;
        mesh.quads = quads;
        mesh.bounds = new AABB(mesh.originX, mesh.originY + minY - 0.1, mesh.originZ, mesh.originX + 16.0, mesh.originY + maxY + 0.1,
                mesh.originZ + 16.0);
    }

    private void gatherDents(CoverChunk chunk, boolean near) {
        dentCount = 0;
        if (!near) {
            return;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                CoverChunk source = cover.chunk(chunk.x + dx, chunk.z + dz);
                if (source == null) {
                    continue;
                }
                for (int i = 0; i < source.dentCount(); i++) {
                    float x = source.dentX(i);
                    float z = source.dentZ(i);
                    float r = source.dentRadius(i);
                    if (x + r < chunk.x * 16 - 1 || x - r > chunk.x * 16 + 17 || z + r < chunk.z * 16 - 1 || z - r > chunk.z * 16 + 17) {
                        continue;
                    }
                    int column = CoverChunk.index((int) Math.floor(x), (int) Math.floor(z));
                    dentX[dentCount] = x;
                    dentZ[dentCount] = z;
                    dentR[dentCount] = r;
                    dentD[dentCount] = source.dentDepth(i);
                    dentBase[dentCount] = source.base(source.dentSurface(i), column);
                    dentCount++;
                }
            }
        }
    }

    /**
     * Fills the 3x3 neighbourhood of a column: the height the snow reaches and its depth on each neighbour of the same
     * level. A neighbour without snow at that level is either a wall rising above it (the snow simply runs up to it) or a
     * drop (the snow rounds off towards it).
     */
    private void neighbourhood(CoverChunk chunk, int surface, int lx, int lz) {
        int column = CoverChunk.index(lx, lz);
        float b = chunk.base(surface, column);
        float d = chunk.depth(surface, column);
        int light = chunk.light(surface, column);
        float block = (light >> 4 & 0xF);
        float sky = (light >> 20 & 0xF);
        int worldX = chunk.x * 16 + lx;
        int worldZ = chunk.z * 16 + lz;
        for (int oz = -1; oz <= 1; oz++) {
            for (int ox = -1; ox <= 1; ox++) {
                int n = (oz + 1) * 3 + ox + 1;
                nDrop[n] = false;
                nBlock[n] = block;
                nSky[n] = sky;
                if (ox == 0 && oz == 0) {
                    nHeight[n] = b + d;
                    nDepth[n] = d;
                    continue;
                }
                int x = worldX + ox;
                int z = worldZ + oz;
                CoverChunk other = (x >> 4) == chunk.x && (z >> 4) == chunk.z ? chunk : cover.chunkAt(x, z);
                if (other == null || !other.surveyed()) {
                    nHeight[n] = b + d;
                    nDepth[n] = d;
                    continue;
                }
                int otherColumn = CoverChunk.index(x, z);
                int best = -1;
                float bestDistance = CoverSurvey.SAME_LEVEL;
                for (int s = 0; s < CoverChunk.SURFACES; s++) {
                    if (!other.has(s, otherColumn)) {
                        continue;
                    }
                    float distance = Math.abs(other.base(s, otherColumn) - b);
                    if ((other.flags(s, otherColumn) & CoverChunk.CAP) == 0 && distance <= bestDistance) {
                        bestDistance = distance;
                        best = s;
                    }
                }
                if (best >= 0) {
                    float nd = other.depth(best, otherColumn);
                    nHeight[n] = other.base(best, otherColumn) + nd;
                    nDepth[n] = nd;
                    int otherLight = other.light(best, otherColumn);
                    nBlock[n] = otherLight >> 4 & 0xF;
                    nSky[n] = otherLight >> 20 & 0xF;
                } else if (wallBeside(x, b, z)) {
                    nHeight[n] = b + d;
                    nDepth[n] = d;
                } else {
                    nHeight[n] = b + d * 0.3F;
                    nDepth[n] = d * 0.3F;
                    nDrop[n] = true;
                }
            }
        }
    }

    /** Whether a block stands beside the snow at its level: a wall the snow runs up to, rather than a drop. */
    private boolean wallBeside(int x, float level, int z) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return true;
        }
        probe.set(x, (int) Math.floor(level + 0.25F), z);
        return !world.getBlockState(probe).getCollisionShape(world, probe).isEmpty();
    }

    /** Quadratic B-spline weights and their derivatives at t in [0, 1] across a block (neighbours at -1, 0, +1). */
    private static void spline(float t, float[] w, float[] dw) {
        w[0] = (1.0F - t) * (1.0F - t) * 0.5F;
        w[1] = (-2.0F * t * t + 2.0F * t + 1.0F) * 0.5F;
        w[2] = t * t * 0.5F;
        dw[0] = t - 1.0F;
        dw[1] = 1.0F - 2.0F * t;
        dw[2] = t;
    }

    private void column(BufferBuilder builder, CoverChunk chunk, int surface, int column, int lx, int lz, int detail, double originY) {
        neighbourhood(chunk, surface, lx, lz);
        float b = chunk.base(surface, column);
        float d = chunk.depth(surface, column);
        float most = d;
        for (int n = 0; n < 9; n++) {
            if (!nDrop[n]) {
                most = Math.max(most, nDepth[n]);
            }
        }
        if (d < MIN_DEPTH && most < 0.006F) {
            return;
        }
        int worldX = chunk.x * 16 + lx;
        int worldZ = chunk.z * 16 + lz;
        float packed = chunk.packed(surface, column);
        int size = detail + 1;
        for (int j = 0; j <= detail; j++) {
            float v = j / (float) detail;
            spline(v, wv, dv);
            for (int i = 0; i <= detail; i++) {
                float u = i / (float) detail;
                spline(u, wu, du);
                float height = 0.0F;
                float depth = 0.0F;
                float slopeX = 0.0F;
                float slopeZ = 0.0F;
                float block = 0.0F;
                float sky = 0.0F;
                for (int c = 0; c < 3; c++) {
                    for (int a = 0; a < 3; a++) {
                        int n = c * 3 + a;
                        float w = wu[a] * wv[c];
                        height += w * nHeight[n];
                        depth += w * nDepth[n];
                        slopeX += du[a] * wv[c] * nHeight[n];
                        slopeZ += wu[a] * dv[c] * nHeight[n];
                        block += w * nBlock[n];
                        sky += w * nSky[n];
                    }
                }
                if (dentCount > 0) {
                    float px = worldX + u;
                    float pz = worldZ + v;
                    for (int k = 0; k < dentCount; k++) {
                        float ex = px - dentX[k];
                        float ez = pz - dentZ[k];
                        float r = dentR[k];
                        float q = (ex * ex + ez * ez) / (r * r);
                        if (q >= 1.0F || Math.abs(dentBase[k] - b) > CoverSurvey.SAME_LEVEL) {
                            continue;
                        }
                        float press = Math.min(dentD[k] * (1.0F - q) * (1.0F - q), depth * 0.85F);
                        height -= press;
                        depth -= press;
                        float grad = 4.0F * dentD[k] * (1.0F - q) / (r * r);
                        slopeX += grad * ex;
                        slopeZ += grad * ez;
                    }
                }
                int g = j * size + i;
                float y = Math.max(height, b + LIFT);
                if (depth <= 0.0F) {
                    y = b + LIFT;
                }
                gridY[g] = (float) (y - originY);
                float cover = Math.clamp(depth / FULL_COVER, 0.0F, 1.0F);
                cover = cover * cover * (3.0F - 2.0F * cover);
                gridAlpha[g] = depth <= 0.0005F ? 0.0F : 0.47F + 0.53F * cover;
                float length = (float) Math.sqrt(slopeX * slopeX + 1.0F + slopeZ * slopeZ);
                gridNx[g] = -slopeX / length;
                gridNy[g] = 1.0F / length;
                gridNz[g] = -slopeZ / length;
                gridLight[g] = (int) (block * 16.0F) | (int) (sky * 16.0F) << 16;
            }
        }
        float step = 1.0F / detail;
        float texU = (u1 - u0) / TEXTURE_BLOCKS;
        float texV = (v1 - v0) / TEXTURE_BLOCKS;
        float tu = u0 + texU * Math.floorMod(worldX, TEXTURE_BLOCKS);
        float tv = v0 + texV * Math.floorMod(worldZ, TEXTURE_BLOCKS);
        for (int j = 0; j < detail; j++) {
            for (int i = 0; i < detail; i++) {
                int g00 = j * size + i;
                int g10 = g00 + 1;
                int g01 = g00 + size;
                int g11 = g01 + 1;
                if (gridAlpha[g00] < 0.49F && gridAlpha[g10] < 0.49F && gridAlpha[g01] < 0.49F && gridAlpha[g11] < 0.49F) {
                    continue;
                }
                float x0 = lx + i * step;
                float x1 = x0 + step;
                float z0 = lz + j * step;
                float z1 = z0 + step;
                float ua = tu + texU * i * step;
                float ub = ua + texU * step;
                float va = tv + texV * j * step;
                float vb = va + texV * step;
                // Counter-clockwise seen from above.
                topVertex(builder, x0, z0, g00, ua, va, packed);
                topVertex(builder, x0, z1, g01, ua, vb, packed);
                topVertex(builder, x1, z1, g11, ub, vb, packed);
                topVertex(builder, x1, z0, g10, ub, va, packed);
                quads++;
            }
        }
        // Rims where the snow ends at a drop.
        float bottom = (float) (b - originY);
        if (nDrop[3]) {
            rim(builder, lx, lz, 0, size, size, detail, bottom, -1.0F, 0.0F, false, tu, tv, texU, texV);
        }
        if (nDrop[5]) {
            rim(builder, lx + 1, lz, detail, size, size, detail, bottom, 1.0F, 0.0F, false, tu, tv, texU, texV);
        }
        if (nDrop[1]) {
            rim(builder, lx, lz, 0, 1, size, detail, bottom, 0.0F, -1.0F, true, tu, tv, texU, texV);
        }
        if (nDrop[7]) {
            rim(builder, lx, lz + 1, detail * size, 1, size, detail, bottom, 0.0F, 1.0F, true, tu, tv, texU, texV);
        }
    }

    private void topVertex(BufferBuilder builder, float x, float z, int g, float u, float v, float packed) {
        float y = gridY[g];
        float nx = gridNx[g];
        float ny = gridNy[g];
        float nz = gridNz[g];
        float lit = Math.max(0.0F, (nx * LIGHT_X + ny * LIGHT_Y + nz * LIGHT_Z) / 0.987F);
        // Soft shading: snow facing away from the light is a little darker and bluer, like snow in its own shadow.
        float shade = 0.8F + 0.2F * lit;
        float trodden = 1.0F - 0.08F * packed;
        int r = (int) (247.0F * shade * trodden);
        int gr = (int) (250.0F * shade * trodden);
        int b = (int) (255.0F * Math.min(1.0F, shade * 1.04F) * (1.0F - 0.04F * packed));
        int alpha = (int) (gridAlpha[g] * 255.0F);
        builder.addVertex(x, y, z, alpha << 24 | r << 16 | gr << 8 | b, u, v, OverlayTexture.NO_OVERLAY, gridLight[g], nx, ny, nz);
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
    }

    /**
     * The side of the snow along one edge of a block, from the surface down to the block top, where the snow ends at a
     * drop. {@code first}/{@code stride} walk the grid vertices along the edge.
     */
    private void rim(BufferBuilder builder, float ex, float ez, int first, int stride, int size, int detail, float bottom, float nx,
            float nz, boolean alongX, float tu, float tv, float texU, float texV) {
        float step = 1.0F / detail;
        for (int k = 0; k < detail; k++) {
            int ga = first + stride * k;
            int gb = ga + stride;
            float ya = gridY[ga];
            float yb = gridY[gb];
            if (ya - bottom < 0.02F && yb - bottom < 0.02F || gridAlpha[ga] < 0.49F && gridAlpha[gb] < 0.49F) {
                continue;
            }
            float xa = alongX ? ex + k * step : ex;
            float za = alongX ? ez : ez + k * step;
            float xb = alongX ? xa + step : ex;
            float zb = alongX ? ez : za + step;
            float ua = tu + texU * k * step;
            float ub = ua + texU * step;
            float va = tv;
            float vTopA = va + texV * Math.min(1.0F, ya - bottom);
            float vTopB = va + texV * Math.min(1.0F, yb - bottom);
            int alphaA = (int) (gridAlpha[ga] * 255.0F);
            int alphaB = (int) (gridAlpha[gb] * 255.0F);
            int colorA = alphaA << 24 | 0xDCE2EC;
            int colorB = alphaB << 24 | 0xDCE2EC;
            int lightA = gridLight[ga];
            int lightB = gridLight[gb];
            // Counter-clockwise seen from outside: which end comes first depends on the side.
            boolean flip = alongX ? nz < 0.0F : nx > 0.0F;
            if (flip) {
                builder.addVertex(xb, bottom, zb, colorB, ub, va, OverlayTexture.NO_OVERLAY, lightB, nx, 0.0F, nz);
                builder.addVertex(xa, bottom, za, colorA, ua, va, OverlayTexture.NO_OVERLAY, lightA, nx, 0.0F, nz);
                builder.addVertex(xa, ya, za, colorA, ua, vTopA, OverlayTexture.NO_OVERLAY, lightA, nx, 0.0F, nz);
                builder.addVertex(xb, yb, zb, colorB, ub, vTopB, OverlayTexture.NO_OVERLAY, lightB, nx, 0.0F, nz);
            } else {
                builder.addVertex(xa, bottom, za, colorA, ua, va, OverlayTexture.NO_OVERLAY, lightA, nx, 0.0F, nz);
                builder.addVertex(xb, bottom, zb, colorB, ub, va, OverlayTexture.NO_OVERLAY, lightB, nx, 0.0F, nz);
                builder.addVertex(xb, yb, zb, colorB, ub, vTopB, OverlayTexture.NO_OVERLAY, lightB, nx, 0.0F, nz);
                builder.addVertex(xa, ya, za, colorA, ua, vTopA, OverlayTexture.NO_OVERLAY, lightA, nx, 0.0F, nz);
            }
            minY = Math.min(minY, bottom);
            quads++;
        }
    }

    /**
     * A small rounded cap of snow on a narrow top (a fence post, a wall): highest in the middle, soft at the edges,
     * overhanging the post a little.
     */
    private void cap(BufferBuilder builder, CoverChunk chunk, int surface, int column, int lx, int lz, double originY) {
        float b = chunk.base(surface, column);
        float d = Math.min(chunk.depth(surface, column), 0.28F);
        float overhang = Math.min(0.05F, d * 0.3F);
        float x0 = lx + chunk.capBox(surface, column, 0) - overhang;
        float x1 = lx + chunk.capBox(surface, column, 1) + overhang;
        float z0 = lz + chunk.capBox(surface, column, 2) - overhang;
        float z1 = lz + chunk.capBox(surface, column, 3) + overhang;
        int light = chunk.light(surface, column);
        int packedLight = (light >> 4 & 0xF) * 16 | (light >> 20 & 0xF) * 16 << 16;
        float bottom = (float) (b - originY) - Math.min(0.04F, d * 0.4F);
        float top = (float) (b - originY) + LIFT;
        int n = 4;
        int size = n + 1;
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                float u = i / (float) n;
                float v = j / (float) n;
                float shapeU = 1.0F - (float) Math.pow(Math.abs(2.0F * u - 1.0F), 3.0);
                float shapeV = 1.0F - (float) Math.pow(Math.abs(2.0F * v - 1.0F), 3.0);
                int g = j * size + i;
                gridY[g] = top + d * (0.15F + 0.85F * shapeU * shapeV);
                float slopeU = -3.0F * Math.signum(2.0F * u - 1.0F) * (2.0F * u - 1.0F) * (2.0F * u - 1.0F) * 2.0F * shapeV * 0.85F * d
                        / Math.max(0.05F, x1 - x0);
                float slopeV = -3.0F * Math.signum(2.0F * v - 1.0F) * (2.0F * v - 1.0F) * (2.0F * v - 1.0F) * 2.0F * shapeU * 0.85F * d
                        / Math.max(0.05F, z1 - z0);
                float length = (float) Math.sqrt(slopeU * slopeU + 1.0F + slopeV * slopeV);
                gridNx[g] = -slopeU / length;
                gridNy[g] = 1.0F / length;
                gridNz[g] = -slopeV / length;
                gridAlpha[g] = 1.0F;
                gridLight[g] = packedLight;
            }
        }
        float texU = (u1 - u0) / TEXTURE_BLOCKS;
        float texV = (v1 - v0) / TEXTURE_BLOCKS;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int g00 = j * size + i;
                float xa = x0 + (x1 - x0) * i / n;
                float xb = x0 + (x1 - x0) * (i + 1) / n;
                float za = z0 + (z1 - z0) * j / n;
                float zb = z0 + (z1 - z0) * (j + 1) / n;
                float ua = u0 + texU * (xa - lx);
                float ub = u0 + texU * (xb - lx);
                float va = v0 + texV * (za - lz);
                float vb = v0 + texV * (zb - lz);
                topVertex(builder, xa, za, g00, ua, va, 0.0F);
                topVertex(builder, xa, zb, g00 + size, ua, vb, 0.0F);
                topVertex(builder, xb, zb, g00 + size + 1, ub, vb, 0.0F);
                topVertex(builder, xb, za, g00 + 1, ub, va, 0.0F);
                quads++;
            }
        }
        // The four sides of the cap, each a strip along its edge.
        capSide(builder, x0, z0, 0, size, n, bottom, -1.0F, 0.0F, false, x1 - x0, z1 - z0, texU, texV);
        capSide(builder, x1, z0, n, size, n, bottom, 1.0F, 0.0F, false, x1 - x0, z1 - z0, texU, texV);
        capSide(builder, x0, z0, 0, 1, n, bottom, 0.0F, -1.0F, true, x1 - x0, z1 - z0, texU, texV);
        capSide(builder, x0, z1, n * size, 1, n, bottom, 0.0F, 1.0F, true, x1 - x0, z1 - z0, texU, texV);
    }

    private void capSide(BufferBuilder builder, float ex, float ez, int first, int stride, int n, float bottom, float nx, float nz,
            boolean alongX, float width, float depth, float texU, float texV) {
        float step = (alongX ? width : depth) / n;
        for (int k = 0; k < n; k++) {
            int ga = first + stride * k;
            int gb = ga + stride;
            float ya = gridY[ga];
            float yb = gridY[gb];
            float xa = alongX ? ex + k * step : ex;
            float za = alongX ? ez : ez + k * step;
            float xb = alongX ? xa + step : ex;
            float zb = alongX ? ez : za + step;
            int color = 0xFFDCE2EC;
            int light = gridLight[ga];
            float ua = u0 + texU * k * step;
            float ub = ua + texU * step;
            float vTop = v0 + texV * Math.min(1.0F, ya - bottom);
            boolean flip = alongX ? nz < 0.0F : nx > 0.0F;
            if (flip) {
                builder.addVertex(xb, bottom, zb, color, ub, v0, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xa, bottom, za, color, ua, v0, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xa, ya, za, color, ua, vTop, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xb, yb, zb, color, ub, vTop, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
            } else {
                builder.addVertex(xa, bottom, za, color, ua, v0, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xb, bottom, zb, color, ub, v0, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xb, yb, zb, color, ub, vTop, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
                builder.addVertex(xa, ya, za, color, ua, vTop, OverlayTexture.NO_OVERLAY, light, nx, 0.0F, nz);
            }
            minY = Math.min(minY, bottom);
            quads++;
        }
    }

    @Override
    public void close() {
        closeAll();
        bytes.close();
    }
}
