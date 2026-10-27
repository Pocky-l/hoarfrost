package com.pockyl.hoarfrost.client.compat;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;
import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Map;

/**
 * Publishes weather to shader packs: a row of float texels registered as {@code <modid>:shader_data}, which a pack
 * binds with {@code customTexture.<name> = <modid>:shader_data} and reads with {@code texelFetch}. Texel 0 is a marker
 * with the format version, texels 1 and 2 the mod's wind and the air it carried; the mod writes its own values from
 * texel 3 on. Layout and the wind shares: docs/shader-data.md of Pocky Shaders.
 * <p>
 * The same class lives in Petrichor, Hoarfrost and Rustling Leaves (only the package differs): the wind shares must be
 * computed by all of them in exactly the same way.
 */
public final class ShaderData {
    public static final int FORMAT = 1;

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PETRICHOR = "petrichor";
    private static final String HOARFROST = "hoarfrost";
    private static final String LEAVES = "rustling_leaves";
    /** The first version of each mod that publishes its data. */
    private static final Map<String, String> FIRST_VERSION = Map.of(PETRICHOR, "1.7.0", HOARFROST, "1.4.0", LEAVES, "2.6.0");
    /** Who covers the rain, snow and calm shares of the weather: the first of each list that is installed. */
    private static final String[][] OWNERS = {
            {PETRICHOR, LEAVES, HOARFROST},
            {HOARFROST, PETRICHOR, LEAVES},
            {LEAVES, PETRICHOR, HOARFROST},
    };
    /** The air's drift wraps here, so float texels keep a precision of about 0.01 blocks. */
    private static final double DRIFT_WRAP = 100000.0;

    private final String modId;
    private final ResourceLocation id;
    private final float[] values;
    private final boolean[] ownsShare = new boolean[3];
    private DataTexture texture;
    private boolean broken;
    private long lastFrame;
    private float frameTicks;
    private float dirX = 1.0F;
    private float dirZ = 0.35F;
    private double driftX;
    private double driftZ;

    public ShaderData(String modId, int texels) {
        this.modId = modId;
        this.id = ResourceLocation.fromNamespaceAndPath(modId, "shader_data");
        this.values = new float[texels * 4];
        for (int share = 0; share < OWNERS.length; share++) {
            for (String owner : OWNERS[share]) {
                if (publishes(owner)) {
                    ownsShare[share] = owner.equals(modId);
                    break;
                }
            }
        }
    }

    private static boolean publishes(String modId) {
        return ModList.get().getModContainerById(modId)
                .map(container -> container.getModInfo().getVersion().compareTo(new DefaultArtifactVersion(FIRST_VERSION.get(modId))) >= 0)
                .orElse(false);
    }

    /**
     * Starts a frame: call on the render thread before the world is drawn. Returns false when nothing is to be
     * published (no shader pack, no world); otherwise fill the texels and call {@link #upload}.
     */
    public boolean begin(boolean packInUse) {
        Minecraft minecraft = Minecraft.getInstance();
        if (broken || !(ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus"))) {
            return false;
        }
        try {
            if (texture == null) {
                // Registered before the first pack frame, so Iris finds it instead of a missing texture.
                texture = new DataTexture(values.length / 4);
                minecraft.getTextureManager().register(id, texture);
            }
        } catch (Throwable e) {
            stop(e);
            return false;
        }
        long now = System.nanoTime();
        if (!packInUse || minecraft.level == null) {
            lastFrame = 0L;
            return false;
        }
        // Game ticks since the last frame, for the drift; nothing moves while the game is paused.
        frameTicks = lastFrame == 0L || minecraft.isPaused() ? 0.0F : Math.min((now - lastFrame) / 50_000_000.0F, 5.0F);
        lastFrame = now;
        Arrays.fill(values, 0.0F);
        set(0, 0.123F, 0.456F, 0.789F, FORMAT);
        return true;
    }

    /**
     * The mod's wind (blocks per tick): texel 1 gets its direction, speed and the share of the weather this mod covers,
     * texel 2 the drift of that share.
     */
    public void wind(float windX, float windZ, float partialTick) {
        float speed = (float) Math.sqrt(windX * windX + windZ * windZ);
        if (speed > 1.0E-4F) {
            dirX = windX / speed;
            dirZ = windZ / speed;
        }
        float share = coveredShare(partialTick);
        driftX = (driftX + windX * share * frameTicks) % DRIFT_WRAP;
        driftZ = (driftZ + windZ * share * frameTicks) % DRIFT_WRAP;
        set(1, dirX, dirZ, speed, share);
        set(2, (float) driftX, (float) driftZ, 0.0F, 0.0F);
    }

    /** The share of the weather at the camera this mod's wind covers (docs/shader-data.md, "Wind shares"). */
    public float coveredShare(float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level == null) {
            return 0.0F;
        }
        boolean open = level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
        float rain = open ? level.getRainLevel(partialTick) : 0.0F;
        BlockPos pos = minecraft.gameRenderer.getMainCamera().getBlockPosition();
        float snowy = level.getBiome(pos).value().getPrecipitationAt(pos) == Biome.Precipitation.SNOW ? 1.0F : 0.0F;
        float[] shares = {rain * (1.0F - snowy), rain * snowy, 1.0F - rain};
        float covered = 0.0F;
        for (int share = 0; share < shares.length; share++) {
            if (ownsShare[share]) {
                covered += shares[share];
            }
        }
        return covered;
    }

    public void set(int texel, float r, float g, float b, float a) {
        values[texel * 4] = r;
        values[texel * 4 + 1] = g;
        values[texel * 4 + 2] = b;
        values[texel * 4 + 3] = a;
    }

    public void upload() {
        try {
            texture.upload(values);
        } catch (Throwable e) {
            stop(e);
        }
    }

    /** Never take the game down for an extra: packs fall back to vanilla weather. */
    private void stop(Throwable e) {
        broken = true;
        LOGGER.error("Weather data for shader packs ({}) stopped", modId, e);
    }

    /** The texture itself; Iris looks it up in the texture manager on every bind. Render thread only. */
    private static final class DataTexture extends AbstractTexture {
        private static final int[] UNPACK_PARAMETERS = {GL11.GL_UNPACK_ROW_LENGTH, GL11.GL_UNPACK_SKIP_ROWS,
                GL11.GL_UNPACK_SKIP_PIXELS, GL11.GL_UNPACK_ALIGNMENT};
        private static final int[] UNPACK_DEFAULTS = {0, 0, 0, 4};

        private final int width;
        private final FloatBuffer buffer;
        private boolean freed;

        DataTexture(int width) {
            RenderSystem.assertOnRenderThread();
            this.width = width;
            this.buffer = MemoryUtil.memAllocFloat(width * 4);
            bind();
            // No mipmaps: with the default minifying filter the texture would be incomplete and read as zero.
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            int[] saved = cleanUnpackState();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, width, 1, 0, GL11.GL_RGBA, GL11.GL_FLOAT, (FloatBuffer) null);
            restoreUnpackState(saved);
        }

        void upload(float[] values) {
            if (freed) {
                return;
            }
            buffer.clear();
            buffer.put(values, 0, width * 4).flip();
            bind();
            int[] saved = cleanUnpackState();
            GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, width, 1, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
            restoreUnpackState(saved);
        }

        /**
         * Uploads read client memory with the current unpack state, which other renderers leave behind: a pixel buffer
         * still bound turns the pointer into an offset into it, a row length or skip of a large image made the driver
         * read far past this small buffer (a native crash in the driver). Clean state for the upload, the old one put
         * back.
         */
        private static int[] cleanUnpackState() {
            int[] saved = new int[UNPACK_PARAMETERS.length + 1];
            for (int i = 0; i < UNPACK_PARAMETERS.length; i++) {
                saved[i] = GL11.glGetInteger(UNPACK_PARAMETERS[i]);
                GlStateManager._pixelStore(UNPACK_PARAMETERS[i], UNPACK_DEFAULTS[i]);
            }
            saved[UNPACK_PARAMETERS.length] = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            if (saved[UNPACK_PARAMETERS.length] != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            }
            return saved;
        }

        private static void restoreUnpackState(int[] saved) {
            for (int i = 0; i < UNPACK_PARAMETERS.length; i++) {
                GlStateManager._pixelStore(UNPACK_PARAMETERS[i], saved[i]);
            }
            if (saved[UNPACK_PARAMETERS.length] != 0) {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, saved[UNPACK_PARAMETERS.length]);
            }
        }

        @Override
        public void load(ResourceManager resourceManager) {
            // Filled from code, nothing to load.
        }

        @Override
        public void close() {
            super.close();
            if (!freed) {
                freed = true;
                MemoryUtil.memFree(buffer);
            }
        }
    }
}
