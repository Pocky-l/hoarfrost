package com.pockyl.hoarfrost.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import com.pockyl.hoarfrost.ClientConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The world wind of <a href="https://www.curseforge.com/minecraft/mc-mods/living-wind">Living Wind</a> (mod id
 * {@code livingwind}): its server simulates one wind per level and syncs it to the clients, and while it blows the snow
 * flies and drifts the way its wind vanes point. The speed stays this mod's (soft snow drifts down, a blizzard drives
 * it sideways), scaled by how much harder or softer Living Wind blows than it usually does in this weather. The
 * server's drifts follow its server wind, the falling snow the synced client wind.
 *
 * <p>Living Wind is never compiled against: its public {@code WindApi} (server) and {@code ClientWindApi} are called
 * through method handles, found on first use. Directions blow towards (-sin, cos) of the angle, like an entity's yaw;
 * the strength is unitless and its weather bonuses are known (see {@link #usualStrength}). If its API changes, that
 * side switches itself off and the snow keeps its own wind.
 */
public final class LivingWind {
    public static final String MOD_ID = "livingwind";
    private static final String CLIENT_API = "com.macatmovie.livingwind.api.ClientWindApi";
    private static final String SERVER_API = "com.macatmovie.livingwind.api.WindApi";
    private static final String SNAPSHOT = "com.macatmovie.livingwind.wind.WindSnapshot";

    // Living Wind's default strength: its clear-weather base wanders between 0.04 and 0.48, rain adds 0.24, thunder 0.44,
    // all times its global multiplier 1.3.
    private static final float CLEAR = 0.34F;
    private static final float RAIN = 0.31F;
    private static final float THUNDER = 0.57F;
    private static final float MIN_SCALE = 0.3F;
    private static final float MAX_SCALE = 2.0F;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Boolean loaded;
    private static volatile boolean clientBroken;
    private static volatile boolean serverBroken;
    private static MethodHandle hasWindState;
    private static MethodHandle clientStrength;
    private static MethodHandle clientDirection;
    private static MethodHandle snapshot;
    private static MethodHandle snapshotStrength;
    private static MethodHandle snapshotDirection;

    private LivingWind() {
    }

    /** Whether Living Wind is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(MOD_ID);
        }
        return loaded;
    }

    /**
     * Reads the client's synced wind into {@code out}: the heading it blows towards as a unit vector ({@code out[0]} x,
     * {@code out[1]} z) and how hard it blows against its usual wind in this weather ({@code out[2]}, 1 = usual).
     * Returns false when it has no wind to give (not installed, the integration is off, or the server does not run it).
     */
    public static boolean readClient(float rain, float thunder, float[] out) {
        if (clientBroken || !loaded() || !ClientConfig.LIVING_WIND.get() || !findClientApi()) {
            return false;
        }
        try {
            if (!(boolean) hasWindState.invokeExact()) {
                return false;
            }
            return fill((float) clientStrength.invokeExact(), (float) clientDirection.invokeExact(), rain, thunder, out);
        } catch (Throwable e) {
            clientBroken = true;
            LOGGER.warn("Reading the wind of Living Wind failed; the snow keeps its own wind", e);
            return false;
        }
    }

    /** The same for the server's wind in a level (snowdrifts). */
    public static boolean readServer(ServerLevel level, float rain, float thunder, float[] out) {
        if (serverBroken || !loaded() || !findServerApi()) {
            return false;
        }
        try {
            Object wind = (Object) snapshot.invokeExact(level);
            if (wind == null) {
                return false;
            }
            return fill((float) snapshotStrength.invokeExact(wind), (float) snapshotDirection.invokeExact(wind), rain, thunder, out);
        } catch (Throwable e) {
            serverBroken = true;
            LOGGER.warn("Reading the wind of Living Wind failed; snowdrifts keep their own wind", e);
            return false;
        }
    }

    /** Living Wind's strength on an average day in this weather (with its default settings). */
    public static float usualStrength(float rain, float thunder) {
        return CLEAR + RAIN * rain + THUNDER * thunder;
    }

    private static boolean fill(float strength, float degrees, float rain, float thunder, float[] out) {
        if (!Float.isFinite(strength) || !Float.isFinite(degrees)) {
            return false;
        }
        float radians = degrees * Mth.DEG_TO_RAD;
        out[0] = -Mth.sin(radians);
        out[1] = Mth.cos(radians);
        out[2] = Mth.clamp(strength / usualStrength(rain, thunder), MIN_SCALE, MAX_SCALE);
        return true;
    }

    private static boolean findClientApi() {
        if (clientDirection != null) {
            return true;
        }
        try {
            Class<?> api = Class.forName(CLIENT_API);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            hasWindState = lookup.findStatic(api, "hasWindState", MethodType.methodType(boolean.class));
            clientStrength = lookup.findStatic(api, "globalStrength", MethodType.methodType(float.class));
            clientDirection = lookup.findStatic(api, "directionDegrees", MethodType.methodType(float.class));
            LOGGER.info("The snow blows with the wind of Living Wind");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            clientBroken = true;
            LOGGER.warn("Living Wind is installed, but its wind could not be found; the snow keeps its own wind", e);
            return false;
        }
    }

    private static boolean findServerApi() {
        if (snapshotDirection != null) {
            return true;
        }
        try {
            Class<?> api = Class.forName(SERVER_API);
            Class<?> record = Class.forName(SNAPSHOT);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            snapshot = lookup.findStatic(api, "get", MethodType.methodType(record, ServerLevel.class))
                    .asType(MethodType.methodType(Object.class, ServerLevel.class));
            snapshotStrength = lookup.findVirtual(record, "strength", MethodType.methodType(float.class))
                    .asType(MethodType.methodType(float.class, Object.class));
            snapshotDirection = lookup.findVirtual(record, "directionDegrees", MethodType.methodType(float.class))
                    .asType(MethodType.methodType(float.class, Object.class));
            LOGGER.info("Snowdrifts follow the wind of Living Wind");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            serverBroken = true;
            LOGGER.warn("Living Wind is installed, but its wind could not be found; snowdrifts keep their own wind", e);
            return false;
        }
    }
}
