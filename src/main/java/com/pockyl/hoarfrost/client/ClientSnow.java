package com.pockyl.hoarfrost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.Config;
import com.pockyl.hoarfrost.sim.SnowWeather;
import com.pockyl.hoarfrost.weather.SnowSchedule;
import com.pockyl.hoarfrost.weather.SnowType;

/**
 * The snow as the client draws and plays it, updated every tick.
 *
 * <p>The snow type comes from the server when it has this mod, otherwise from the shared {@link SnowSchedule}. All
 * parameters glide towards the current type over ~10 seconds, so flurries thicken into a snowfall instead of switching.
 *
 * <p>Everything that fills the whole view or the ears (fog, sky, hush, wind, curtains) follows the land around the
 * listener: it fades towards land where it rains or never snows. Flakes and the snow on the ground go column by
 * column and use the world's precipitation level.
 */
public final class ClientSnow {
    private static final int SERVER_TIMEOUT = 200;
    private static final float BLEND = 0.006F;

    private static long ticks;
    private static long lastSync = Long.MIN_VALUE;
    private static int serverType = -1;
    private static boolean initialized;
    private static SnowType type = SnowType.SNOWFALL;
    private static float worldSnow;
    private static float snow;
    private static float presence;
    private static float presenceTarget;
    private static float gust = 1.0F;
    private static float windX;
    private static float windZ;
    private static float windBase = SnowType.SNOWFALL.wind;
    private static double driftX;
    private static double driftZ;
    private static double fallen;
    private static float skyView = 1.0F;
    private static float previousSkyView = 1.0F;
    private static final SnowWeather BLENDED = new SnowWeather();

    // Blended parameters of the current type that only the client uses.
    public static float visibility = SnowType.SNOWFALL.visibility;
    public static float hush = SnowType.SNOWFALL.hush;
    public static float curtains = SnowType.SNOWFALL.curtains;

    private ClientSnow() {
    }

    public static void onServerSync(int type) {
        serverType = type;
        lastSync = ticks;
    }

    public static void reset() {
        initialized = false;
        lastSync = Long.MIN_VALUE;
        serverType = -1;
        snow = 0.0F;
        worldSnow = 0.0F;
        presence = presenceTarget = 0.0F;
    }

    private static boolean serverActive() {
        return ticks - lastSync < SERVER_TIMEOUT;
    }

    public static boolean syncedWithServer() {
        return serverActive();
    }

    public static void tick(ClientLevel level) {
        ticks++;
        if (ticks % 10 == 1 || !initialized) {
            surveyLand(level);
        }
        presence = initialized ? presence + Math.clamp(presenceTarget - presence, -0.02F, 0.02F) : presenceTarget;
        worldSnow = level.getRainLevel(1.0F);
        snow = worldSnow * presence;
        SnowType target = targetType(level);
        if (target != null) {
            type = target;
        }
        if (!initialized) {
            initialized = true;
            BLENDED.snapTo(type);
            windBase = type.wind;
            visibility = type.visibility;
            hush = type.hush;
            curtains = type.curtains;
        } else {
            BLENDED.blendTo(type, BLEND);
            windBase += (type.wind - windBase) * BLEND;
            visibility += (type.visibility - visibility) * BLEND;
            hush += (type.hush - hush) * BLEND;
            curtains += (type.curtains - curtains) * BLEND;
        }
        double time = level.getGameTime();
        gust = SnowSchedule.gust(time, BLENDED.gustiness);
        float angle = SnowSchedule.windAngle(time);
        float speed = SnowSchedule.windSpeed(time, windBase, BLENDED.gustiness) * (0.3F + 0.7F * worldSnow);
        windX = Mth.cos(angle) * speed;
        windZ = Mth.sin(angle) * speed;
        driftX += windX;
        driftZ += windZ;
        fallen += BLENDED.fallSpeed;
        tickSkyView(level);
    }

    /** Copies the blended weather into the simulation's input. */
    public static void apply(SnowWeather weather) {
        weather.snow = worldSnow;
        weather.gust = gust;
        weather.windX = windX;
        weather.windZ = windZ;
        weather.driftX = driftX;
        weather.driftZ = driftZ;
        weather.fallSpeed = BLENDED.fallSpeed;
        weather.flutter = BLENDED.flutter;
        weather.sizeMin = BLENDED.sizeMin;
        weather.sizeMax = BLENDED.sizeMax;
        weather.density = BLENDED.density;
        weather.accumulation = BLENDED.accumulation;
        weather.gustiness = BLENDED.gustiness;
    }

    /**
     * Where around the listener it snows: 25 points over a 48-block square, each checked at the height the snow
     * reaches there (snowy peaks above rainy valleys count as snowy).
     */
    private static void surveyLand(ClientLevel level) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int snowy = 0;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = Mth.floor(cam.x) + i * 12;
                int z = Mth.floor(cam.z) + j * 12;
                pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                Biome biome = level.getBiome(pos).value();
                if (biome.hasPrecipitation() && biome.getPrecipitationAt(pos) == Biome.Precipitation.SNOW) {
                    snowy++;
                }
            }
        }
        presenceTarget = snowy / 25.0F;
    }

    private static SnowType targetType(ClientLevel level) {
        if (serverActive()) {
            return SnowType.byOrdinal(serverType);
        }
        if (worldSnow <= 0.0F) {
            return null;
        }
        return SnowSchedule.naturalType(level.getGameTime(), level.getThunderLevel(1.0F) > 0.5F, Config.THUNDER_BLIZZARD.get(),
                Config.typeWeights());
    }

    private static void tickSkyView(ClientLevel level) {
        previousSkyView = skyView;
        BlockPos at = Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition();
        int light = level.getBrightness(LightLayer.SKY, at);
        // Sky light drops by one per block into a cave: the sky fades in over the last 14 blocks to the exit.
        float target = Mth.clamp((light - 1) / 14.0F, 0.0F, 1.0F);
        target *= target;
        skyView += (target - skyView) * 0.15F;
    }

    /** How much of the sky the camera sees, 0 deep indoors or underground, 1 outdoors (eased). */
    public static float skyView(float partialTick) {
        return Mth.lerp(partialTick, previousSkyView, skyView);
    }

    public static SnowType type() {
        return type;
    }

    /** Snow level for whole-view effects: the world's precipitation times the share of snowy land around. */
    public static float snow() {
        return snow;
    }

    /** The world's precipitation level, for what goes column by column. */
    public static float worldSnow() {
        return worldSnow;
    }

    /** Share of snowy land around the listener, gliding. */
    public static float presence() {
        return presence;
    }

    public static float gust() {
        return gust;
    }

    /** How hard it snows right now around the listener. */
    public static float intensity() {
        return snow * gust;
    }

    public static float windX() {
        return windX;
    }

    public static float windZ() {
        return windZ;
    }

    public static float windSpeed() {
        return Mth.sqrt(windX * windX + windZ * windZ);
    }

    /** How far the wind has carried the air, in blocks, with the partial tick. */
    public static double driftX(float partialTick) {
        return driftX + windX * partialTick;
    }

    public static double driftZ(float partialTick) {
        return driftZ + windZ * partialTick;
    }

    /** How far the snow has fallen so far, in blocks, with the partial tick. */
    public static double fallen(float partialTick) {
        return fallen + BLENDED.fallSpeed * partialTick;
    }

    public static float fallSpeed() {
        return BLENDED.fallSpeed;
    }
}
