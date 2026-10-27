package com.pockyl.hoarfrost.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.hoarfrost.Config;
import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.compat.LivingWind;
import com.pockyl.hoarfrost.weather.SnowConditions;
import com.pockyl.hoarfrost.weather.SnowKind;
import com.pockyl.hoarfrost.weather.SnowSchedule;

/**
 * The snowy weather as the client sees and hears it, updated every tick.
 *
 * <p>The kind comes from the server when it has this mod, otherwise from the shared {@link SnowSchedule}. All parameters
 * glide towards the current kind over ten seconds or so, so light snow thickens into heavy snow instead of switching.
 *
 * <p>Whatever fills the whole view or the ears (fog, sky, hush, wind) follows the land around the listener - it fades
 * where it rains or never snows - and how open the sky above the camera is: none of it reaches indoors or underground.
 * Flakes and the snow on the ground go column by column.
 */
public final class ClientWeather {
    private static final int SERVER_TIMEOUT = 200;
    private static final float BLEND = 0.006F;

    private static final SnowConditions CONDITIONS = new SnowConditions();
    /** Living Wind's heading and how hard it blows (see {@link LivingWind#readClient}). */
    private static final float[] OUTER_WIND = new float[3];
    private static long ticks;
    private static long lastSync = Long.MIN_VALUE;
    private static int serverKind = -1;
    private static boolean initialized;
    private static SnowKind kind = SnowKind.SNOW;
    private static float worldSnow;
    private static float snow;
    private static float presence;
    private static float presenceTarget;
    private static float skyView = 1.0F;
    private static float previousSkyView = 1.0F;
    private static float gust = 1.0F;

    private ClientWeather() {
    }

    public static void onServerSync(int kindOrdinal) {
        serverKind = kindOrdinal;
        lastSync = ticks;
    }

    public static void reset() {
        initialized = false;
        lastSync = Long.MIN_VALUE;
        serverKind = -1;
        snow = 0.0F;
        worldSnow = 0.0F;
        presence = presenceTarget = 0.0F;
    }

    public static boolean syncedWithServer() {
        return ticks - lastSync < SERVER_TIMEOUT;
    }

    /** The blended weather; the simulation reads it directly. */
    public static SnowConditions conditions() {
        return CONDITIONS;
    }

    public static void tick(ClientLevel level, Vec3 camera) {
        ticks++;
        if (ticks % 10 == 1 || !initialized) {
            surveyLand(level, camera);
        }
        presence = initialized ? presence + Math.clamp(presenceTarget - presence, -0.02F, 0.02F) : presenceTarget;
        float rain = level.getRainLevel(1.0F);
        int clearSpells = Config.clearSpells();
        float fair = clearSpells >= 100 ? 0.0F : SnowSchedule.fairLevel(level.getGameTime(), clearSpells);
        // The snow from a clear sky turns into the storm's as the vanilla precipitation sets in.
        worldSnow = rain + (1.0F - rain) * fair;
        snow = worldSnow * presence;
        SnowKind target = targetKind(level, rain, clearSpells);
        if (target != null) {
            kind = target;
        }
        if (!initialized) {
            initialized = true;
            CONDITIONS.snapTo(kind);
        } else {
            CONDITIONS.blendTo(kind, BLEND);
        }
        CONDITIONS.snow = worldSnow;
        boolean outer = LivingWind.readClient(worldSnow, level.getThunderLevel(1.0F), OUTER_WIND);
        CONDITIONS.advanceWind(level.getGameTime(), worldSnow, outer ? OUTER_WIND : null);
        gust = CONDITIONS.gustAt(camera.x, camera.z);
        tickSkyView(level);
    }

    /**
     * Where around the listener it snows: 25 points over a 48-block square, each checked at the height the snow
     * reaches there (snowy peaks above rainy valleys count as snowy).
     */
    private static void surveyLand(ClientLevel level, Vec3 camera) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int snowy = 0;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = Mth.floor(camera.x) + i * 12;
                int z = Mth.floor(camera.z) + j * 12;
                pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                if (Climate.snowsAt(level, pos)) {
                    snowy++;
                }
            }
        }
        presenceTarget = snowy / 25.0F;
    }

    private static SnowKind targetKind(ClientLevel level, float rain, int clearSpells) {
        if (syncedWithServer() && serverKind >= 0) {
            return SnowKind.byOrdinal(serverKind);
        }
        if (rain <= 0.0F) {
            return clearSpells >= 100 ? null : SnowSchedule.fairKind(level.getGameTime(), clearSpells);
        }
        if (syncedWithServer()) {
            // The server's precipitation ended and the vanilla one is fading out: keep the kind.
            return null;
        }
        return SnowSchedule.naturalKind(level.getGameTime(), level.getThunderLevel(1.0F) > 0.5F, Config.thunderBlizzard(),
                SnowSchedule.seasonWeights(Config.kindWeights(), Climate.winter(level)));
    }

    private static void tickSkyView(ClientLevel level) {
        previousSkyView = skyView;
        BlockPos at = Minecraft.getInstance().gameRenderer.getMainCamera().getBlockPosition();
        int light = level.getBrightness(LightLayer.SKY, at);
        // Sky light drops by one per block into a cave: the sky fades in over the last blocks to the exit.
        float target = Mth.clamp((light - 1) / 14.0F, 0.0F, 1.0F);
        target *= target;
        skyView += (target - skyView) * 0.15F;
    }

    /** How much of the sky the camera sees, 0 deep indoors or underground, 1 outdoors (eased). */
    public static float skyView(float partialTick) {
        return Mth.lerp(partialTick, previousSkyView, skyView);
    }

    public static SnowKind kind() {
        return kind;
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

    /** The gust factor at the camera: above 1 in a gust, below in a lull. */
    public static float gust() {
        return gust;
    }
}
