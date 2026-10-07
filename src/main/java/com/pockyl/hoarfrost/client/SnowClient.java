package com.pockyl.hoarfrost.client;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.render.SnowRenderer;
import com.pockyl.hoarfrost.client.render.SnowSky;
import com.pockyl.hoarfrost.client.sound.Hush;
import com.pockyl.hoarfrost.client.sound.SnowSounds;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.SnowCover;
import com.pockyl.hoarfrost.sim.SnowSettings;
import com.pockyl.hoarfrost.sim.SnowSimulation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Owns the client-side snow: the simulation for the current level, the snow cover around the camera (loaded, seeded,
 * saved), feeds it entities, footsteps, landings, explosions and block changes, and drives drawing, fog and sound.
 */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID, value = Dist.CLIENT)
public final class SnowClient {
    private static final double TELEPORT_DISTANCE = 4.0;
    private static final double STEP_RANGE = 48.0;
    private static final int SAVE_INTERVAL = 20 * 30;
    private static final long SEED_BUDGET_NANOS = 4_000_000L;
    private static final float STEP_VOLUME = 0.25F;

    private static final SnowSettings SETTINGS = new SnowSettings();
    /** Distance walked since the last footprint and which foot is next, per entity. */
    private static final Int2FloatOpenHashMap STRIDE = new Int2FloatOpenHashMap();
    private static final Int2IntOpenHashMap FOOT = new Int2IntOpenHashMap();
    private static final Int2FloatOpenHashMap LAST_FALL = new Int2FloatOpenHashMap();

    private static volatile boolean configChanged = true;
    private static SnowSimulation simulation;
    private static SnowRenderer renderer;
    private static CoverStorage storage;
    private static ClientLevel level;
    private static int ticks;

    private SnowClient() {
    }

    /** Called from the config event, possibly on another thread; applied on the next client tick. */
    static void onConfigChanged() {
        configChanged = true;
    }

    private static boolean hasWeather(ClientLevel level) {
        return level != null && level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    /** Whether the mod draws the snowfall (and vanilla only the rain). */
    public static boolean ownsSnowfall() {
        return simulation != null && ClientConfig.SNOWFALL.get() && hasWeather(Minecraft.getInstance().level);
    }

    private static void applyConfig() {
        ClientConfig.Quality quality = ClientConfig.quality();
        int capacity = SETTINGS.maxFlakes + SETTINGS.farFlakes + SETTINGS.cloudFlakes;
        SETTINGS.maxFlakes = quality.maxFlakes;
        SETTINGS.farFlakes = quality.farFlakes;
        SETTINGS.cloudFlakes = quality.cloudFlakes;
        SETTINGS.radius = quality.flakeRadius;
        SETTINGS.coverRadius = quality.coverRadius;
        SETTINGS.coverChunksPerTick = quality.coverChunksPerTick;
        SETTINGS.flakeDensity = ClientConfig.SNOWFALL.get() ? (float) (double) ClientConfig.DENSITY.get() : 0.0F;
        SETTINGS.farDensity = ClientConfig.CURTAINS.get() ? SETTINGS.flakeDensity : 0.0F;
        SETTINGS.cover = ClientConfig.COVER.get();
        SETTINGS.drifting = ClientConfig.DRIFTING.get();
        SETTINGS.footprints = ClientConfig.FOOTPRINTS.get();
        if (capacity != SETTINGS.maxFlakes + SETTINGS.farFlakes + SETTINGS.cloudFlakes || !SETTINGS.cover) {
            reset();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (configChanged) {
            configChanged = false;
            applyConfig();
        }
        if (minecraft.level != level) {
            reset();
            level = minecraft.level;
            ClientSnow.reset();
        }
        if (!hasWeather(level)) {
            SnowSounds.stopAll();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;
        ClientSnow.tick(level);
        if (simulation == null) {
            simulation = new SnowSimulation(SETTINGS);
            renderer = new SnowRenderer(simulation);
            storage = new CoverStorage(storageDirectory(minecraft, level));
        }
        Vec3 camera = cameraPosition(minecraft);
        ClientSnow.apply(simulation.weather());
        simulation.begin(level);
        if (SETTINGS.cover) {
            updateCoverChunks(camera);
        }
        feedEntities(camera);
        simulation.tick(level, camera.x, camera.y, camera.z);
        SnowSounds.tick(level, camera);
        if (ticks % SAVE_INTERVAL == 0) {
            save(camera);
        }
        if (ticks % 200 == 0) {
            STRIDE.clear();
            FOOT.clear();
            LAST_FALL.clear();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        ClientSnow.reset();
        SnowSounds.stopAll();
    }

    private static void reset() {
        if (simulation != null && storage != null) {
            for (CoverChunk chunk : simulation.cover().chunks()) {
                if (chunk.takeDirty()) {
                    storage.store(chunk);
                }
            }
            storage.close();
        }
        storage = null;
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        simulation = null;
        STRIDE.clear();
        FOOT.clear();
        LAST_FALL.clear();
    }

    private static Vec3 cameraPosition(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isInitialized()) {
            return camera.getPosition();
        }
        return minecraft.player != null ? minecraft.player.getEyePosition() : Vec3.ZERO;
    }

    /** Moving entities stir the air, and where they walk or land in snow they leave prints. */
    private static void feedEntities(Vec3 camera) {
        double wakeRange = SETTINGS.radius + 4.0;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator() || entity.isPassenger()) {
                continue;
            }
            double distanceSq = entity.distanceToSqr(camera);
            if (distanceSq > STEP_RANGE * STEP_RANGE) {
                continue;
            }
            double moveX = entity.getX() - entity.xo;
            double moveY = entity.getY() - entity.yo;
            double moveZ = entity.getZ() - entity.zo;
            if (Math.abs(moveX) > TELEPORT_DISTANCE || Math.abs(moveY) > TELEPORT_DISTANCE || Math.abs(moveZ) > TELEPORT_DISTANCE) {
                continue;
            }
            float width = entity.getBbWidth();
            if (distanceSq < wakeRange * wakeRange) {
                simulation.disturb(entity.getX(), entity.getY(), entity.getZ(), moveX, moveZ, width, entity.getBbHeight());
            }
            int id = entity.getId();
            float lastFall = LAST_FALL.get(id);
            LAST_FALL.put(id, (float) moveY);
            boolean flying = entity instanceof Player player && player.getAbilities().flying;
            if (!entity.onGround() || flying) {
                continue;
            }
            if (lastFall < -0.3F) {
                simulation.land(entity.getX(), entity.getY(), entity.getZ(), width, -lastFall);
            }
            float horizontal = (float) Math.sqrt(moveX * moveX + moveZ * moveZ);
            if (horizontal < 0.01F) {
                continue;
            }
            float stride = Math.max(0.45F, width * 1.1F);
            float walked = STRIDE.get(id) + horizontal;
            if (walked >= stride) {
                walked = walked > stride * 2.0F ? 0.0F : walked - stride;
                int foot = FOOT.get(id) == 1 ? -1 : 1;
                FOOT.put(id, foot);
                simulation.step(entity.getX(), entity.getY(), entity.getZ(), (float) moveX / horizontal, (float) moveZ / horizontal, width,
                        foot, horizontal, entity.isCrouching());
            }
            STRIDE.put(id, walked);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cover chunks: load around the camera, seed new ground, save and unload far away
    // ------------------------------------------------------------------------------------------------------------

    private static void updateCoverChunks(Vec3 camera) {
        SnowCover cover = simulation.cover();
        double renderDistance = Math.max(32.0, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0);
        double radius = Math.min(SETTINGS.coverRadius, renderDistance);
        int camChunkX = Mth.floor(camera.x) >> 4;
        int camChunkZ = Mth.floor(camera.z) >> 4;
        int reach = Mth.ceil(radius / 16.0) + 1;
        double keepSq = (radius + 24.0) * (radius + 24.0);
        double loadSq = (radius + 8.0) * (radius + 8.0);
        if (ticks % 10 == 0) {
            List<CoverChunk> far = new ArrayList<>();
            for (CoverChunk chunk : cover.chunks()) {
                if (chunkDistanceSq(chunk.x, chunk.z, camera) > keepSq || !level.getChunkSource().hasChunk(chunk.x, chunk.z)) {
                    far.add(chunk);
                }
            }
            for (CoverChunk chunk : far) {
                if (chunk.takeDirty()) {
                    storage.store(chunk);
                }
                cover.remove(chunk.x, chunk.z);
            }
            for (int cx = camChunkX - reach; cx <= camChunkX + reach; cx++) {
                for (int cz = camChunkZ - reach; cz <= camChunkZ + reach; cz++) {
                    if (cover.chunk(cx, cz) != null || chunkDistanceSq(cx, cz, camera) > loadSq
                            || !level.getChunkSource().hasChunk(cx, cz)) {
                        continue;
                    }
                    CoverChunk chunk = storage.load(cx, cz);
                    cover.put(chunk != null ? chunk : new CoverChunk(cx, cz));
                }
            }
        }
        // Surfaces and first snow nearest first, within a time budget per tick, once the neighbors are there (the
        // shelter of a column looks at the land around it).
        List<CoverChunk> pending = new ArrayList<>();
        for (CoverChunk chunk : cover.chunks()) {
            if ((!chunk.hasSurfaces() || !chunk.seeded()) && neighborsLoaded(chunk.x, chunk.z)) {
                pending.add(chunk);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        pending.sort((a, b) -> Double.compare(chunkDistanceSq(a.x, a.z, camera), chunkDistanceSq(b.x, b.z, camera)));
        long deadline = System.nanoTime() + SEED_BUDGET_NANOS;
        for (CoverChunk chunk : pending) {
            simulation.prepare(chunk);
            if (System.nanoTime() > deadline) {
                break;
            }
        }
    }

    private static boolean neighborsLoaded(int chunkX, int chunkZ) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!level.getChunkSource().hasChunk(chunkX + dx, chunkZ + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static double chunkDistanceSq(int chunkX, int chunkZ, Vec3 camera) {
        double dx = chunkX * 16 + 8 - camera.x;
        double dz = chunkZ * 16 + 8 - camera.z;
        return dx * dx + dz * dz;
    }

    private static void save(Vec3 camera) {
        for (CoverChunk chunk : simulation.cover().chunks()) {
            if (chunk.takeDirty()) {
                storage.store(chunk);
            }
        }
        storage.flush(Mth.floor(camera.x) >> 4, Mth.floor(camera.z) >> 4);
    }

    /** Per world (singleplayer: inside the save) or per server address, then per dimension. */
    private static Path storageDirectory(Minecraft minecraft, ClientLevel level) {
        Path root;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            root = server.getWorldPath(LevelResource.ROOT).resolve(Hoarfrost.MOD_ID);
        } else {
            ServerData data = minecraft.getCurrentServer();
            String name = data != null ? data.ip : "unknown";
            root = minecraft.gameDirectory.toPath().resolve(Hoarfrost.MOD_ID).resolve("servers")
                    .resolve(name.replaceAll("[^A-Za-z0-9._-]", "_"));
        }
        ResourceLocation dimension = level.dimension().location();
        return root.resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hooks from mixins
    // ------------------------------------------------------------------------------------------------------------

    /** A block changed: the snow on its column finds its surface again (and the light around may have changed). */
    public static void onBlockChanged(ClientLevel changed, BlockPos pos, BlockState oldState, BlockState newState) {
        if (simulation == null || changed != level || oldState == newState) {
            return;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (pos.getY() < top - 5) {
            return;
        }
        simulation.cover().blockChanged(pos.getX(), pos.getZ());
    }

    /** Explosions and wind charges blow the snow away and fill the air with powder. */
    public static void onExplosion(ClientboundExplodePacket packet) {
        if (simulation == null) {
            return;
        }
        boolean windCharge = isGust(packet.getSmallExplosionParticles()) || isGust(packet.getLargeExplosionParticles());
        simulation.explode(packet.getX(), packet.getY(), packet.getZ(), packet.getPower(), windCharge);
    }

    private static boolean isGust(ParticleOptions particle) {
        return particle.getType() == ParticleTypes.GUST_EMITTER_SMALL || particle.getType() == ParticleTypes.GUST_EMITTER_LARGE
                || particle.getType() == ParticleTypes.GUST || particle.getType() == ParticleTypes.SMALL_GUST;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    /** Draws the falling snow at the start of the vanilla weather pass. */
    public static void renderSnowfall(LightTexture lightTexture, float partialTick, double camX, double camY, double camZ) {
        if (!ownsSnowfall() || renderer == null) {
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        renderer.renderSnowfall(lightTexture, camera, partialTick, camX, camY, camZ);
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS || renderer == null || !SETTINGS.cover) {
            return;
        }
        renderer.renderCover(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix(),
                Math.min(SETTINGS.coverRadius, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!hasWeather(Minecraft.getInstance().level) || event.getType() != FogType.NONE) {
            return;
        }
        float partialTick = (float) event.getPartialTick();
        float snow = ClientSnow.snow() * ClientSnow.skyView(partialTick);
        double strength = ClientConfig.FOG.get();
        if (snow <= 0.0F || strength <= 0.0) {
            return;
        }
        float far = event.getFarPlaneDistance();
        float target = ClientSnow.visibility * 0.7F;
        if (target >= far) {
            return;
        }
        float k = (float) Math.min(1.0, snow * Math.sqrt(Math.max(ClientSnow.gust(), 0.0F)) * strength);
        float newFar = far + (target - far) * k;
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            // The sky itself disappears in a whiteout.
            event.setFarPlaneDistance(newFar);
            event.setCanceled(true);
            return;
        }
        float near = event.getNearPlaneDistance();
        float newNear = Math.min(near, newFar * (0.35F - 0.3F * k));
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(Math.max(0.0F, newNear));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        if (!hasWeather(Minecraft.getInstance().level) || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float[] color = SnowSky.fogColor(event.getRed(), event.getGreen(), event.getBlue(), (float) event.getPartialTick());
        if (color != null) {
            event.setRed(color[0]);
            event.setGreen(color[1]);
            event.setBlue(color[2]);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Sound
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onSourceReady(PlaySoundSourceEvent event) {
        Hush.onSourceReady(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onStreamReady(PlayStreamingSourceEvent event) {
        Hush.onSourceReady(event.getSound(), event.getChannel());
    }

    /** Footsteps in snow crunch: a step sound where snow lies is swapped for one of the mod's. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (simulation == null || sound == null || !event.getName().endsWith(".step")) {
            return;
        }
        double x = sound.getX();
        double z = sound.getZ();
        SnowCover cover = simulation.cover();
        float depth = cover.depthNear(x, sound.getY(), z);
        float powder = cover.powderNear(x, sound.getY(), z);
        boolean vanillaSnow = event.getName().equals("block.snow.step") || event.getName().equals("block.powder_snow.step");
        SoundEvent replacement = SnowSounds.stepSound(depth, depth > 0.0F ? 1.0F - powder / depth : 0.0F, vanillaSnow);
        if (replacement == null) {
            return;
        }
        // The vanilla instance is not resolved yet at this point (its volume and pitch are not known): ours are fixed.
        // Deeper snow crunches louder.
        float volume = STEP_VOLUME * (0.7F + 0.8F * Math.min(1.0F, depth / 0.2F));
        event.setSound(new SimpleSoundInstance(replacement.getLocation(), sound.getSource(), volume,
                0.9F + level.random.nextFloat() * 0.2F, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.LINEAR, x, sound.getY(), z, false));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Debug
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.getDebugOverlay().showDebugScreen() || simulation == null || !hasWeather(minecraft.level)) {
            return;
        }
        event.getRight().add("");
        event.getRight().add(String.format("Hoarfrost: %s%s, snow %.2f (land %.2f), wind %.2f, hush %.2f",
                ClientSnow.type().id(), ClientSnow.syncedWithServer() ? " (server)" : "", ClientSnow.worldSnow(), ClientSnow.presence(),
                ClientSnow.windSpeed(), ClientSnow.hush));
        event.getRight().add(String.format("Flakes %d, grains %d, drawn %d, cover chunks %d, quads %d, whirls %d",
                simulation.fallingCount(), simulation.grainCount(), renderer.flakesDrawn(), simulation.cover().chunks().size(),
                renderer.coverQuads(), simulation.air().whirls().size()));
        if (minecraft.player != null) {
            Vec3 feet = minecraft.player.position();
            event.getRight().add(String.format("Snow here %.3f (powder %.3f), shelter %.2f, sound: %s",
                    simulation.cover().depthAt(feet.x, feet.z), simulation.cover().powderAt(feet.x, feet.z),
                    simulation.terrain().shelter(Mth.floor(feet.x), Mth.floor(feet.z)), SnowSounds.debugSummary()));
        }
    }
}
