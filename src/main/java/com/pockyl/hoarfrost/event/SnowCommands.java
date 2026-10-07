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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import com.pockyl.hoarfrost.Hoarfrost;
import com.pockyl.hoarfrost.weather.SnowData;
import com.pockyl.hoarfrost.weather.SnowType;

import java.util.Arrays;

/**
 * {@code /hoarfrost} for operators: let it snow with a given type, clear the weather, show the state. Only vanilla
 * argument types are used, so clients without the mod can use the command too.
 */
@EventBusSubscriber(modid = Hoarfrost.MOD_ID)
public final class SnowCommands {
    private static final int DEFAULT_SECONDS = 600;
    private static final DynamicCommandExceptionType UNKNOWN_TYPE = new DynamicCommandExceptionType(
            id -> Component.translatableWithFallback("commands.hoarfrost.unknown_type", "Unknown snow type: %s", id));
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
                        .then(Commands.argument("type", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(SnowType.values()).map(SnowType::id), builder))
                                .executes(context -> setType(context, DEFAULT_SECONDS))
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(10, 86400))
                                        .executes(context -> setType(context, IntegerArgumentType.getInteger(context, "seconds"))))))
                .then(Commands.literal("status").executes(SnowCommands::status)));
    }

    private static ServerLevel weatherLevel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        if (!SnowData.hasWeather(level)) {
            throw NO_WEATHER.create();
        }
        return level;
    }

    private static int setType(CommandContext<CommandSourceStack> context, int seconds) throws CommandSyntaxException {
        String id = StringArgumentType.getString(context, "type");
        SnowType type = SnowType.byId(id);
        if (type == null) {
            throw UNKNOWN_TYPE.create(id);
        }
        ServerLevel level = weatherLevel(context);
        int ticks = seconds * 20;
        level.setWeatherParameters(0, ticks, true, false);
        SnowData.get(level).forceType(type, level.getGameTime() + ticks);
        SnowEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.hoarfrost.weather.set",
                "Snow set to %s for %s seconds (where it snows)", Component.translatableWithFallback(type.translationKey(), type.id()),
                seconds), true);
        return 1;
    }

    private static int clear(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        level.setWeatherParameters(DEFAULT_SECONDS * 20, 0, false, false);
        SnowData.get(level).forceType(null, 0L);
        SnowEvents.syncLevel(level);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.hoarfrost.weather.clear",
                "Weather cleared"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = weatherLevel(context);
        SnowType type = SnowData.get(level).currentType(level);
        Component typeName = type == null
                ? Component.translatableWithFallback("hoarfrost.snow_type.none", "none")
                : Component.translatableWithFallback(type.translationKey(), type.id());
        String wind = String.format("%.1f", SnowEvents.windSpeed(level, type) * 20.0F);
        context.getSource().sendSuccess(() -> Component.translatableWithFallback("commands.hoarfrost.status",
                "Snow: %s, wind %s blocks/s", typeName, wind), false);
        return 1;
    }
}
