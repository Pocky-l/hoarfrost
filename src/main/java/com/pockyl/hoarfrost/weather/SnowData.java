package com.pockyl.hoarfrost.weather;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import com.pockyl.hoarfrost.Config;
import com.pockyl.hoarfrost.compat.Climate;

/** The server's snow state for one level: a kind of snow forced by command, saved with the world. */
public final class SnowData extends SavedData {
    private static final String NAME = "hoarfrost_snow";
    private static final SavedData.Factory<SnowData> FACTORY = new SavedData.Factory<>(SnowData::new, SnowData::load);

    private int forcedKind = -1;
    private long forcedUntil;

    public static SnowData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Whether this level has weather at all (the overworld and dimensions like it). */
    public static boolean hasWeather(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    private static SnowData load(CompoundTag tag, HolderLookup.Provider registries) {
        SnowData data = new SnowData();
        SnowKind kind = SnowKind.byId(tag.getString("forced"));
        data.forcedKind = kind == null ? -1 : kind.ordinal();
        data.forcedUntil = tag.getLong("forcedUntil");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        SnowKind kind = SnowKind.byOrdinal(forcedKind);
        tag.putString("forced", kind == null ? "" : kind.id());
        tag.putLong("forcedUntil", forcedUntil);
        return tag;
    }

    /** The snow falling now where it snows, or {@code null} when there is no precipitation. */
    public SnowKind currentKind(ServerLevel level) {
        if (!level.getLevelData().isRaining()) {
            return null;
        }
        SnowKind forced = forcedKind();
        if (forced != null && level.getGameTime() < forcedUntil) {
            return forced;
        }
        return SnowSchedule.naturalKind(level.getGameTime(), level.getLevelData().isThundering(), Config.thunderBlizzard(),
                SnowSchedule.seasonWeights(Config.kindWeights(), Climate.winter(level)));
    }

    /** The kind forced by command, or {@code null}. */
    public SnowKind forcedKind() {
        return SnowKind.byOrdinal(forcedKind);
    }

    public long forcedUntil() {
        return forcedUntil;
    }

    public void force(SnowKind kind, long until) {
        forcedKind = kind == null ? -1 : kind.ordinal();
        forcedUntil = until;
        setDirty();
    }

    /** Drops an expired override. */
    public void tick(ServerLevel level) {
        if (forcedKind >= 0 && (level.getGameTime() >= forcedUntil || !level.getLevelData().isRaining())) {
            forcedKind = -1;
            setDirty();
        }
    }
}
