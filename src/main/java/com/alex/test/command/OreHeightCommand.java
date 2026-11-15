package com.alex.test.command;

import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.miner.OreHeights;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * {@code /villager oreheight [y] [druid level]}: the ore mix at a height, as
 * vanilla has it, as a miner's scrap ore gets it and as a druid of that level
 * grows it back. Without a height it uses where the player stands. Nothing
 * changes in the world: it only prints, so no permission is needed.
 */
public final class OreHeightCommand {

    /** Druid level shown when none is given: a master, who can grow every ore. */
    private static final int DEFAULT_DRUID_LEVEL = DruidBalance.MASTER_LEVEL;

    private OreHeightCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("oreheight")
                .executes(ctx -> show(ctx.getSource(),
                        Mth.floor(ctx.getSource().getPosition().y), DEFAULT_DRUID_LEVEL))
                .then(Commands.argument("y", IntegerArgumentType.integer(OreHeights.MIN_Y, OreHeights.MAX_Y))
                        .executes(ctx -> show(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "y"), DEFAULT_DRUID_LEVEL))
                        .then(Commands.argument("level", IntegerArgumentType.integer(1, DruidBalance.MAX_LEVEL))
                                .executes(ctx -> show(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "y"),
                                        IntegerArgumentType.getInteger(ctx, "level")))));
    }

    private static int show(CommandSourceStack source, int y, int druidLevel) {
        source.sendSuccess(() -> Component.literal("Ore at Y " + y).withStyle(ChatFormatting.GOLD), false);
        source.sendSuccess(() -> Component.literal("Vanilla: " + OreHeights.describe(OreHeights.mix(y)))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Miner scrap: " + OreHeights.describe(MinerBalance.scrapMix(y)))
                .withStyle(ChatFormatting.GRAY), false);
        source.sendSuccess(() -> Component.literal("Druid level " + druidLevel + " ("
                        + Math.round(DruidBalance.oreChance(druidLevel) * 1000) / 10.0 + "% of regrown rock is ore): "
                        + druidMix(y, druidLevel))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    /** The druid's mix with its emerald and ancient debris taken off the top, as regrownRock rolls them. */
    private static String druidMix(int y, int level) {
        int band = DruidBalance.band(level);
        double emerald = band >= 4 ? DruidBalance.EMERALD_SHARE : 0.0;
        double debris = band >= 5 && y <= DruidBalance.DEBRIS_MAX_Y ? DruidBalance.DEBRIS_SHARE : 0.0;
        double[] shares = DruidBalance.oreMix(level, y);
        double rest = 1.0 - emerald - debris;
        for (int i = 0; i < shares.length; i++) shares[i] *= rest;
        StringBuilder out = new StringBuilder(OreHeights.describe(shares));
        if (emerald > 0) out.append(", Emerald ").append(Math.round(emerald * 100)).append('%');
        if (debris > 0) out.append(", Ancient debris ").append(Math.round(debris * 100)).append('%');
        return out.toString();
    }
}
