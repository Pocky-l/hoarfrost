package com.pockyl.hoarfrost.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.pockyl.hoarfrost.Config;
import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.network.SnowSyncPayload;
import com.pockyl.hoarfrost.weather.SnowData;
import com.pockyl.hoarfrost.weather.SnowSchedule;
import com.pockyl.hoarfrost.weather.SnowType;
import com.pockyl.hoarfrost.world.DriftBuilder;

import java.util.Map;
import java.util.WeakHashMap;

/** Server side of the snow: keeps clients in sync and lets the wind build drifts around the players. */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID)
public final class SnowEvents {
    private static final int SYNC_INTERVAL = 40;
    /** Wind (blocks per tick) from which snow starts drifting. */
    private static final float DRIFT_WIND = 0.12F;
    private static final int DRIFT_RANGE = 40;
    private static final float DRIFT_ATTEMPTS = 8.0F;
    private static final Map<ServerLevel, SnowType> LAST_SENT = new WeakHashMap<>();

    private SnowEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !SnowData.hasWeather(level)) {
            return;
        }
        SnowData data = SnowData.get(level);
        data.tick(level);
        SnowType type = data.currentType(level);
        boolean changed = !LAST_SENT.containsKey(level) || LAST_SENT.get(level) != type;
        if (changed || level.getGameTime() % SYNC_INTERVAL == 0) {
            LAST_SENT.put(level, type);
            PacketDistributor.sendToPlayersInDimension(level, new SnowSyncPayload(type == null ? -1 : type.ordinal()));
        }
        if (type != null && Config.DRIFTS.get()) {
            buildDrifts(level, type);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sendTo(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sendTo(event.getEntity());
    }

    private static void sendTo(Player player) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level) {
            SnowType type = SnowData.hasWeather(level) ? SnowData.get(level).currentType(level) : null;
            PacketDistributor.sendToPlayer(serverPlayer, new SnowSyncPayload(type == null ? -1 : type.ordinal()));
        }
    }

    /** Sends the current state right away (after a command changed it). */
    public static void syncLevel(ServerLevel level) {
        SnowType type = SnowData.get(level).currentType(level);
        LAST_SENT.put(level, type);
        PacketDistributor.sendToPlayersInDimension(level, new SnowSyncPayload(type == null ? -1 : type.ordinal()));
    }

    /** Wind speed of the snowfall in blocks per tick (the same the clients see). */
    public static float windSpeed(ServerLevel level, SnowType type) {
        if (type == null) {
            return 0.0F;
        }
        return SnowSchedule.windSpeed(level.getGameTime(), type.wind, type.gustiness) * level.getRainLevel(1.0F);
    }

    private static void buildDrifts(ServerLevel level, SnowType type) {
        float wind = windSpeed(level, type);
        float strength = Mth.clamp((wind - DRIFT_WIND) / 0.2F, 0.0F, 1.0F) * Config.DRIFT_SPEED.get().floatValue();
        if (strength <= 0.0F) {
            return;
        }
        float angle = SnowSchedule.windAngle(level.getGameTime());
        float dirX = Mth.cos(angle);
        float dirZ = Mth.sin(angle);
        int maxLayers = Config.DRIFT_MAX_LAYERS.get();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            float attempts = DRIFT_ATTEMPTS * strength;
            int n = (int) attempts + (level.random.nextFloat() < attempts - (int) attempts ? 1 : 0);
            for (int k = 0; k < n; k++) {
                int x = player.getBlockX() + level.random.nextInt(DRIFT_RANGE * 2 + 1) - DRIFT_RANGE;
                int z = player.getBlockZ() + level.random.nextInt(DRIFT_RANGE * 2 + 1) - DRIFT_RANGE;
                DriftBuilder.grow(level, x, z, dirX, dirZ, maxLayers, false);
            }
        }
    }
}
