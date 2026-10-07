package com.pockyl.hoarfrost.weather;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import com.pockyl.hoarfrost.Config;

/** The server's snow state for one level: a snow type forced by command, saved with the world. */
public final class SnowData extends SavedData {
    private static final String NAME = "hoarfrost_snow";
    private static final SavedData.Factory<SnowData> FACTORY = new SavedData.Factory<>(SnowData::new, SnowData::load);

    private int overrideType = -1;
    private long overrideUntil;

    public static SnowData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Whether this level has weather at all (the overworld and dimensions like it). */
    public static boolean hasWeather(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    private static SnowData load(CompoundTag tag, HolderLookup.Provider registries) {
        SnowData data = new SnowData();
        data.overrideType = tag.contains("override") ? tag.getInt("override") : -1;
        data.overrideUntil = tag.getLong("overrideUntil");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("override", overrideType);
        tag.putLong("overrideUntil", overrideUntil);
        return tag;
    }

    /** The snow falling now where it snows, or {@code null} when there is no precipitation. */
    public SnowType currentType(ServerLevel level) {
        if (!level.getLevelData().isRaining()) {
            return null;
        }
        SnowType forced = SnowType.byOrdinal(overrideType);
        if (forced != null && level.getGameTime() < overrideUntil) {
            return forced;
        }
        return SnowSchedule.naturalType(level.getGameTime(), level.getLevelData().isThundering(), Config.THUNDER_BLIZZARD.get(),
                Config.typeWeights());
    }

    public void forceType(SnowType type, long until) {
        overrideType = type == null ? -1 : type.ordinal();
        overrideUntil = until;
        setDirty();
    }

    /** Drops an expired override. */
    public void tick(ServerLevel level) {
        if (overrideType >= 0 && (level.getGameTime() >= overrideUntil || !level.getLevelData().isRaining())) {
            overrideType = -1;
            setDirty();
        }
    }
}
