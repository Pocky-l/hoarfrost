package com.pockyl.hoarfrost.client;

import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
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
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.SnowLayerBlock;
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
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import com.pockyl.hoarfrost.ClientConfig;
import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.client.render.CoverRenderer;
import com.pockyl.hoarfrost.client.render.FlakeRenderer;
import com.pockyl.hoarfrost.client.render.SnowFog;
import com.pockyl.hoarfrost.client.render.SnowSky;
import com.pockyl.hoarfrost.client.sound.Hush;
import com.pockyl.hoarfrost.client.sound.Soundscape;
import com.pockyl.hoarfrost.sim.CoverChunk;
import com.pockyl.hoarfrost.sim.Flakes;
import com.pockyl.hoarfrost.sim.SnowCover;
import com.pockyl.hoarfrost.sim.SnowSettings;
import com.pockyl.hoarfrost.sim.SnowSimulation;
import com.pockyl.hoarfrost.weather.SnowConditions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owns the client-side snow: the simulation for the current level and the snow on the ground around the camera (loaded,
 * surveyed, seeded, saved); feeds it moving creatures, footsteps, landings, blasts and block changes; drives drawing, fog
 * and sound.
 */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID, value = Dist.CLIENT)
public final class SnowClient {
    private static final double TELEPORT_DISTANCE = 4.0;
    private static final double STEP_RANGE = 48.0;
    private static final int SAVE_INTERVAL = 20 * 30;
    private static final float STEP_VOLUME = 0.25F;
    /** How far one sees in the heart of a squall of snow. */
    private static final float SQUALL_VISIBILITY = 12.0F;

    private static final SnowSettings SETTINGS = new SnowSettings();
    /** Distance walked since the last footprint, and the fall speed of the tick before, per entity. */
    private static final Int2FloatOpenHashMap STRIDE = new Int2FloatOpenHashMap();
    private static final Int2FloatOpenHashMap LAST_FALL = new Int2FloatOpenHashMap();

    private static volatile boolean configChanged = true;
    private static SnowSimulation simulation;
    private static FlakeRenderer flakeRenderer;
    private static CoverRenderer coverRenderer;
    private static CoverStorage storage;
    private static final FarSnow FAR = new FarSnow();
    /** Time per tick for looking at distant land. */
    private static final long FAR_BUDGET_NANOS = 1_500_000L;
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

    /** The leaves setting the chunk meshes were built with. */
    private static Boolean snowyLeaves;

    private static void applyConfig() {
        ClientConfig.Quality quality = ClientConfig.quality();
        int capacity = SETTINGS.nearFlakes + SETTINGS.farFlakes + SETTINGS.streamFlakes + SETTINGS.powderGrains + SETTINGS.clouds;
        boolean hadCover = SETTINGS.cover;
        SETTINGS.nearFlakes = quality.nearFlakes;
        SETTINGS.radius = quality.radius;
        SETTINGS.farFlakes = quality.farFlakes;
        SETTINGS.farRadius = quality.farRadius;
        SETTINGS.streamFlakes = ClientConfig.STREAM.get() ? quality.streamFlakes : 0;
        SETTINGS.powderGrains = quality.powderGrains;
        SETTINGS.clouds = ClientConfig.CLOUDS.get() ? quality.nearFlakes / 15 : 0;
        SETTINGS.coverRadius = quality.coverRadius;
        SETTINGS.surveyBudget = quality.surveyMillis * 1_000_000L;
        SETTINGS.density = ClientConfig.SNOWFALL.get() ? (float) (double) ClientConfig.DENSITY.get() : 0.0F;
        SETTINGS.farDensity = ClientConfig.DISTANT.get() ? 1.0F : 0.0F;
        SETTINGS.cover = ClientConfig.COVER.get();
        SETTINGS.footprints = ClientConfig.FOOTPRINTS.get();
        SETTINGS.powder = ClientConfig.POWDER.get();
        if (snowyLeaves != null && snowyLeaves != ClientConfig.SNOWY_LEAVES.get()) {
            LeafFrost.rebuild();
        }
        snowyLeaves = ClientConfig.SNOWY_LEAVES.get();
        if (capacity != SETTINGS.nearFlakes + SETTINGS.farFlakes + SETTINGS.streamFlakes + SETTINGS.powderGrains + SETTINGS.clouds
                || hadCover != SETTINGS.cover) {
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
            ClientWeather.reset();
        }
        if (!hasWeather(level)) {
            Soundscape.stopAll();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;
        Vec3 camera = cameraPosition(minecraft);
        ClientWeather.tick(level, camera);
        if (simulation == null) {
            simulation = new SnowSimulation(SETTINGS, ClientWeather.conditions());
            flakeRenderer = new FlakeRenderer();
            coverRenderer = new CoverRenderer(simulation.cover(), FAR);
            storage = new CoverStorage(storageDirectory(minecraft, level));
        }
        if (SETTINGS.cover) {
            updateCoverChunks(camera);
            if (ClientConfig.DISTANT_COVER.get()) {
                FAR.tick(level, camera, minecraft.options.getEffectiveRenderDistance(), FAR_BUDGET_NANOS);
            }
        }
        feedEntities(camera);
        simulation.tick(level, camera.x, camera.y, camera.z);
        Soundscape.tick(level, camera);
        if (ticks % SAVE_INTERVAL == 0) {
            save(camera);
        }
        if (ticks % 200 == 0) {
            STRIDE.clear();
            LAST_FALL.clear();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        ClientWeather.reset();
        Soundscape.stopAll();
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
        if (coverRenderer != null) {
            coverRenderer.close();
            coverRenderer = null;
        }
        if (flakeRenderer != null) {
            flakeRenderer.close();
            flakeRenderer = null;
        }
        simulation = null;
        FAR.clear();
        STRIDE.clear();
        LAST_FALL.clear();
    }

    private static Vec3 cameraPosition(Minecraft minecraft) {
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.isInitialized()) {
            return camera.getPosition();
        }
        return minecraft.player != null ? minecraft.player.getEyePosition() : Vec3.ZERO;
    }

    /** Moving creatures stir the air; where they walk or land in snow they press prints and kick up powder. */
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
                simulation.wake(entity.getX(), entity.getY(), entity.getZ(), (float) moveX, (float) moveY, (float) moveZ, width,
                        entity.getBbHeight());
            }
            int id = entity.getId();
            float lastFall = LAST_FALL.get(id);
            LAST_FALL.put(id, (float) moveY);
            boolean flying = entity instanceof Player player && player.getAbilities().flying;
            if (!entity.onGround() || flying) {
                continue;
            }
            float weight = Math.clamp(width * entity.getBbHeight() / 1.08F, 0.4F, 2.5F);
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
                // Alternate feet a little to each side of the path.
                float side = (ticks + id) % 2 == 0 ? 0.13F : -0.13F;
                double footX = entity.getX() - moveZ / horizontal * side * width / 0.6F;
                double footZ = entity.getZ() + moveX / horizontal * side * width / 0.6F;
                simulation.step(footX, entity.getY(), footZ, (float) moveX, (float) moveZ, 0.17F * Math.max(1.0F, width / 0.6F), weight);
            }
            STRIDE.put(id, walked);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Snow on the ground: load around the camera, survey and seed new land, save and unload far away
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
        // Land is surveyed nearest first, within a time budget per tick, once its neighbours are there (the lee looks
        // into them).
        List<CoverChunk> pending = new ArrayList<>();
        for (CoverChunk chunk : cover.chunks()) {
            if ((!chunk.surveyed() || !chunk.seeded()) && neighborsLoaded(chunk.x, chunk.z)) {
                pending.add(chunk);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        pending.sort((a, b) -> Double.compare(chunkDistanceSq(a.x, a.z, camera), chunkDistanceSq(b.x, b.z, camera)));
        long deadline = System.nanoTime() + SETTINGS.surveyBudget;
        for (CoverChunk chunk : pending) {
            simulation.prepare(level, chunk);
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

    /** A block changed: the land around is surveyed again. */
    public static void onBlockChanged(ClientLevel changed, BlockPos pos, BlockState oldState, BlockState newState) {
        if (simulation == null || changed != level || oldState == newState) {
            return;
        }
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
        if (pos.getY() < top - 48) {
            return;
        }
        FAR.blockChanged(pos.getX(), pos.getZ());
        if (oldState.getLightEmission(level, pos) > 0 || newState.getLightEmission(level, pos) > 0) {
            simulation.lightChanged(pos.getX(), pos.getZ());
        }
        boolean oldLayer = oldState.getBlock() instanceof SnowLayerBlock;
        boolean newLayer = newState.getBlock() instanceof SnowLayerBlock;
        if ((oldLayer || newLayer) && (oldLayer || oldState.isAir()) && (newLayer || newState.isAir())) {
            float base = pos.getY() + (newLayer ? newState.getValue(SnowLayerBlock.LAYERS) / 8.0F : 0.0F);
            if (simulation.cover().snowLayerChanged(pos.getX(), pos.getZ(), base)) {
                return;
            }
        }
        simulation.cover().blockChanged(pos.getX(), pos.getZ());
    }

    /**
     * Explosions and wind charges: the air bursts out, carrying the flakes with it, and the snow around is blown up into
     * a cloud of powder.
     */
    public static void onExplosion(ClientboundExplodePacket packet) {
        if (simulation == null) {
            return;
        }
        boolean windCharge = isGust(packet.getSmallExplosionParticles()) || isGust(packet.getLargeExplosionParticles());
        float power = packet.getPower();
        float radius = windCharge ? 1.6F + power * 0.9F : 1.2F + power * 1.25F;
        float strength = windCharge ? 0.42F + power * 0.04F : 0.55F + power * 0.08F;
        simulation.blast(packet.getX(), packet.getY(), packet.getZ(), radius, strength);
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
        if (!ownsSnowfall() || flakeRenderer == null) {
            return;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        flakeRenderer.render(simulation.flakes(), lightTexture, camera, partialTick, camX, camY, camZ);
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS || coverRenderer == null || !SETTINGS.cover) {
            return;
        }
        coverRenderer.render(event.getCamera(), event.getFrustum(), event.getModelViewMatrix(), event.getProjectionMatrix(),
                Math.min(SETTINGS.coverRadius, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0));
    }

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (event.getMode() == FogRenderer.FogMode.FOG_TERRAIN) {
            SnowFog.clear();
        }
        if (!hasWeather(Minecraft.getInstance().level) || event.getType() != FogType.NONE) {
            return;
        }
        float partialTick = (float) event.getPartialTick();
        float open = ClientWeather.skyView(partialTick);
        float snow = ClientWeather.snow() * open;
        double strength = ClientConfig.FOG.get();
        if (snow <= 0.0F || strength <= 0.0) {
            return;
        }
        SnowConditions weather = ClientWeather.conditions();
        float far = event.getFarPlaneDistance();
        // Gusts thicken the air, lulls clear it a little.
        float gust = ClientWeather.gust();
        float target = weather.visibility * 1.15F / Mth.lerp(weather.gustiness, 1.0F, Math.clamp(gust, 0.6F, 1.8F));
        float k = (float) Math.min(1.0, snow * strength);
        float newFar = target < far ? far + (target - far) * k : far;
        // In the heart of a squall of snow the world is gone for a moment.
        float squall = Math.clamp((gust - 1.35F) / 0.6F, 0.0F, 1.0F) * Math.clamp((weather.gustiness - 0.5F) * 2.0F, 0.0F, 1.0F) * snow
                * (float) Math.min(1.0, strength);
        if (squall > 0.0F) {
            newFar = Math.min(newFar, Mth.lerp(squall, far, SQUALL_VISIBILITY));
            k = Math.max(k, squall);
        }
        if (newFar >= far) {
            return;
        }
        if (event.getMode() == FogRenderer.FogMode.FOG_SKY) {
            // The sky itself fades into the snowy air.
            event.setFarPlaneDistance(newFar);
            event.setCanceled(true);
            return;
        }
        // The haze thickens gradually from just in front of the camera instead of standing as a wall.
        float newNear = Math.min(event.getNearPlaneDistance(), newFar * (0.18F - 0.16F * k));
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(Math.max(0.0F, newNear));
        event.setCanceled(true);
        if (event.getMode() == FogRenderer.FogMode.FOG_TERRAIN) {
            SnowFog.setTerrain(Math.max(0.0F, newNear), newFar, k);
        }
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
            SnowFog.setColor(color[0], color[1], color[2]);
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

    /** Whether snowy land is around the listener: no thunderstorm there. */
    public static boolean snowyAround() {
        return hasWeather(level) && ClientWeather.presence() > 0.2F;
    }

    /** No lightning in snowy land, also on servers without the mod. */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() && event.getEntity() instanceof LightningBolt && snowyAround()) {
            event.setCanceled(true);
        }
    }

    /** Footsteps in snow crunch: a step sound where snow lies is swapped for one of the mod's. No thunder in snowy land. */
    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound != null && event.getName().startsWith("entity.lightning_bolt.") && snowyAround()) {
            event.setSound(null);
            return;
        }
        if (simulation == null || sound == null || !event.getName().endsWith(".step")) {
            return;
        }
        double x = sound.getX();
        double y = sound.getY();
        double z = sound.getZ();
        SnowCover cover = simulation.cover();
        float depth = cover.depthAt(x, y + 0.1, z);
        float packed = cover.packedAt(x, y + 0.1, z);
        boolean vanillaSnow = event.getName().equals("block.snow.step") || event.getName().equals("block.powder_snow.step");
        SoundEvent replacement = Soundscape.stepSound(depth, packed, vanillaSnow);
        if (replacement == null) {
            return;
        }
        // The vanilla instance is not resolved yet (its volume and pitch are unknown): ours are fixed. Deeper snow crunches
        // louder.
        float volume = STEP_VOLUME * (0.7F + 0.8F * Math.min(1.0F, depth / 0.2F));
        event.setSound(new SimpleSoundInstance(replacement.getLocation(), sound.getSource(), volume,
                0.9F + level.random.nextFloat() * 0.2F, SoundInstance.createUnseededRandom(), false, 0,
                SoundInstance.Attenuation.LINEAR, x, y, z, false));
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
        SnowConditions weather = ClientWeather.conditions();
        Flakes flakes = simulation.flakes();
        event.getRight().add("");
        event.getRight().add(String.format(Locale.ROOT, "Hoarfrost: %s%s, snow %.2f (land %.2f), wind %.1f m/s, gust %.2f",
                ClientWeather.kind().id(), ClientWeather.syncedWithServer() ? " (server)" : "", ClientWeather.worldSnow(),
                ClientWeather.presence(), weather.windSpeed * 20.0F, ClientWeather.gust()));
        event.getRight().add(String.format(Locale.ROOT, "Flakes %d near, %d far, %d stream, %d powder; drawn %d",
                flakes.count(Flakes.NEAR), flakes.count(Flakes.FAR), flakes.count(Flakes.STREAM), flakes.count(Flakes.POWDER),
                flakeRenderer.lastCount()));
        event.getRight().add(String.format(Locale.ROOT, "Snow chunks %d, meshes %d, quads %d", simulation.cover().chunks().size(),
                coverRenderer.meshCount(), coverRenderer.lastQuads()));
        if (minecraft.player != null) {
            Vec3 feet = minecraft.player.position();
            event.getRight().add(String.format(Locale.ROOT, "Snow here %.3f (trodden %.2f), %s",
                    simulation.cover().depthAt(feet.x, feet.y + 0.2, feet.z), simulation.cover().packedAt(feet.x, feet.y + 0.2, feet.z),
                    Soundscape.debugSummary()));
        }
    }
}
