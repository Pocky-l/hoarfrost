package com.pockyl.hoarfrost.event;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.pockyl.hoarfrost.Config;
import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.network.ModNetwork;
import com.pockyl.hoarfrost.network.SnowSyncPayload;
import com.pockyl.hoarfrost.weather.SnowConditions;
import com.pockyl.hoarfrost.weather.SnowData;
import com.pockyl.hoarfrost.weather.SnowKind;
import com.pockyl.hoarfrost.world.DriftBuilder;

import java.util.Map;
import java.util.WeakHashMap;

/** Server side of the snow: keeps clients in sync and lets the wind build drifts around the players. */
@Mod.EventBusSubscriber(modid = Hoarfrost.MOD_ID)
public final class SnowEvents {
    private static final int SYNC_INTERVAL = 40;
    /** Wind (blocks per tick) from which snow starts drifting. */
    private static final float DRIFT_WIND = 0.15F;
    private static final int DRIFT_RANGE = 40;
    private static final float DRIFT_ATTEMPTS = 8.0F;
    private static final double SNOWY_THUNDER_RANGE = 192.0;
    private static final Map<ServerLevel, SnowKind> LAST_SENT = new WeakHashMap<>();
    private static final Map<ServerLevel, SnowConditions> WIND = new WeakHashMap<>();

    private SnowEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level) || !SnowData.hasWeather(level)) {
            return;
        }
        SnowData data = SnowData.get(level);
        data.tick(level);
        SnowKind kind = data.currentKind(level);
        boolean changed = !LAST_SENT.containsKey(level) || LAST_SENT.get(level) != kind;
        if (changed || level.getGameTime() % SYNC_INTERVAL == 0) {
            LAST_SENT.put(level, kind);
            ModNetwork.sendToPlayersInDimension(level, new SnowSyncPayload(kind == null ? -1 : kind.ordinal()));
        }
        if (kind != null && Config.value(Config.DRIFTS)) {
            buildDrifts(level, wind(level, kind));
        }
    }

    /**
     * No thunderstorm where it snows: natural lightning near a player in snowy land does not strike (a thunderstorm in
     * snowy land is a blizzard). Bolts with a cause (tridents) and bolts far from snow are left alone.
     */
    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof LightningBolt bolt) || bolt.getCause() != null
                || !(event.getLevel() instanceof ServerLevel level) || !level.isRaining()) {
            return;
        }
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(bolt) < SNOWY_THUNDER_RANGE * SNOWY_THUNDER_RANGE
                    && Climate.snowsAt(level, player.blockPosition().above())) {
                event.setCanceled(true);
                return;
            }
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
            SnowKind kind = SnowData.hasWeather(level) ? SnowData.get(level).currentKind(level) : null;
            ModNetwork.sendToPlayer(serverPlayer, new SnowSyncPayload(kind == null ? -1 : kind.ordinal()));
        }
    }

    /** Sends the current state right away (after a command changed it). */
    public static void syncLevel(ServerLevel level) {
        SnowKind kind = SnowData.get(level).currentKind(level);
        LAST_SENT.put(level, kind);
        ModNetwork.sendToPlayersInDimension(level, new SnowSyncPayload(kind == null ? -1 : kind.ordinal()));
    }

    /** The wind of the snowfall now (the same the clients feel). */
    public static SnowConditions wind(ServerLevel level, SnowKind kind) {
        SnowConditions conditions = WIND.computeIfAbsent(level, key -> new SnowConditions());
        if (kind != null) {
            conditions.blendTo(kind, 0.006F);
        }
        conditions.advanceWind(level.getGameTime(), level.getRainLevel(1.0F));
        return conditions;
    }

    private static void buildDrifts(ServerLevel level, SnowConditions wind) {
        float strength = Mth.clamp((wind.windSpeed - DRIFT_WIND) / 0.25F, 0.0F, 1.0F) * Config.value(Config.DRIFT_SPEED).floatValue();
        if (strength <= 0.0F) {
            return;
        }
        int maxLayers = Config.value(Config.DRIFT_MAX_LAYERS);
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            float attempts = DRIFT_ATTEMPTS * strength;
            int n = (int) attempts + (level.random.nextFloat() < attempts - (int) attempts ? 1 : 0);
            for (int k = 0; k < n; k++) {
                int x = player.getBlockX() + level.random.nextInt(DRIFT_RANGE * 2 + 1) - DRIFT_RANGE;
                int z = player.getBlockZ() + level.random.nextInt(DRIFT_RANGE * 2 + 1) - DRIFT_RANGE;
                DriftBuilder.grow(level, x, z, wind.windDirX, wind.windDirZ, maxLayers, false);
            }
        }
    }
}
