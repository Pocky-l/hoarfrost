package com.pockyl.hoarfrost.event;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.compat.Climate;
import com.pockyl.hoarfrost.weather.SnowConditions;
import com.pockyl.hoarfrost.weather.SnowData;
import com.pockyl.hoarfrost.weather.SnowKind;

import java.util.Arrays;
import java.util.Locale;

/**
 * {@code /hoarfrost} for operators: let it snow with a given kind ({@code /hoarfrost weather <kind> [seconds]}), clear the
 * weather, show the state. Only vanilla argument types are used, so clients without the mod can use the command too.
 */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID)
public final class SnowCommands {
    private static final int DEFAULT_SECONDS = 600;
    private static final String[] COMPASS = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
    private static final DynamicCommandExceptionType UNKNOWN_KIND = new DynamicCommandExceptionType(
            id -> Component.translatableWithFallback("commands.hoarfrost.unknown_kind", "Unknown kind of snow: %s", id));
    private static final SimpleCommandExceptionType NO_WEATHER = new SimpleCommandExceptionType(
            Component.translatableWithFallback("commands.hoarfrost.no_weather", "This dimension has no weather"));

    private SnowCommands() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Hoarfrost.MOD_ID)
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("weather")
                        .then(Commands.literal("clear").executes(SnowCommands::clear))
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(SnowKind.values()).map(SnowKind::id), builder))
                                .executes(context -> setKind(context, DEFAULT_SECONDS))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                                        .executes(context -> setKind(context, IntegerArgumentType.getInteger(context, "seconds"))))))
                .then(Commands.literal("status").executes(SnowCommands::status)));
    }

    private static ServerLevel weatherLevel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        if (!SnowData.hasWeather(level)) {
            throw NO_WEATHER.create();
        }
        return level;
    }

    private static Component kindName(SnowKind kind) {
        return kind == null
                ? Component.translatableWithFallback("hoarfrost.snow_kind.none", "none")
                : Component.translatableWithFallback(kind.translationKey(), kind.id());
    }

    private static int setKind(CommandContext<CommandSourceStack> context, int seconds) throws CommandSyntaxException {
        String id = StringArgumentType.getString(context, "kind");
        SnowKind kind = SnowKind.byId(id);
        if (kind == null) {
            throw UNKNOWN_KIND.create(id);
        }
        ServerLevel level = weatherLevel(context);
        int ticks = seconds * 20;
        // Thunder would turn the sky dark; a blizzard is told by its kind, not by thunder.
        level.setWeatherParameters(0, ticks, true, false);
        SnowData.get(level).force(kind, level.getGameTime() + ticks);
        SnowEvents.syncLevel(level);
        boolean snowsHere = Climate.snowsAt(level, BlockPos.containing(context.getSource().getPosition()));
        context.getSource().sendSuccess(() -> Component.translatableWithFallback(
                snowsHere ? "commands.hoarfrost.weather.set" : "commands.hoarfrost.weather.set_warm",
                snowsHere ? "Weather set to %s for %s seconds" : "Weather set to %s for %s seconds (it rains here: snow falls in cold land)",
                kindName(kind), seconds), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        level.setWeatherParameters(DEFAULT_SECONDS * 20, 0, false, false);
        SnowData.get(level).force(null, 0L);
        SnowEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.hoarfrost.weather.clear",
                "Weather cleared"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        SnowData data = SnowData.get(level);
        SnowKind kind = data.currentKind(level);
        SnowConditions wind = SnowEvents.wind(level, kind);
        float angle = (float) Math.atan2(wind.windDirZ, wind.windDirX);
        int sector = Math.floorMod(Math.round(angle / (float) (Math.PI / 4.0)), 8);
        String speed = String.format(Locale.ROOT, "%.1f", wind.windSpeed * 20.0F);
        long forcedTicks = data.forcedKind() != null ? Math.max(0L, data.forcedUntil() - level.getGameTime()) : 0L;
        Component forced = forcedTicks > 0L
                ? Component.translatableWithFallback("commands.hoarfrost.status.forced", " (forced, %s s left)", forcedTicks / 20)
                : Component.empty();
        String season = Climate.seasonName(level);
        Component seasonPart = season != null
                ? Component.translatableWithFallback("commands.hoarfrost.status.season", ", season: %s", season)
                : Component.empty();
        boolean snowsHere = Climate.snowsAt(level, BlockPos.containing(context.getSource().getPosition()));
        Component here = Component.translatableWithFallback(snowsHere ? "commands.hoarfrost.status.cold" : "commands.hoarfrost.status.warm",
                snowsHere ? "snow falls here" : "too warm here for snow");
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.hoarfrost.status",
                "Snow: %s%s, wind %s m/s towards %s%s; %s", kindName(kind), forced, speed, COMPASS[sector], seasonPart, here), false);
        return 1;
    }
}
