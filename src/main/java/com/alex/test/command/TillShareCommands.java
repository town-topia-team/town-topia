package com.alex.test.command;

import com.alex.test.village.villagers.farmer.FarmerManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /villager tillshare [plots]}: how many dirt plots each tiller on duty
 * keeps to itself before the planting farmers help till again
 * ({@link FarmerManager#tillShare}). Live tuning only: not saved, restart = 4.
 */
public final class TillShareCommands {
    private TillShareCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("tillshare")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.argument("plots", IntegerArgumentType.integer(0, 64))
                        .executes(ctx -> set(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "plots"))));
    }

    private static int show(CommandSourceStack source) {
        int share = FarmerManager.tillShare();
        source.sendSuccess(() -> Component.literal(share == 0
                ? "Tillers keep no plots to themselves: every farmer tills (0, default "
                        + FarmerManager.DEFAULT_TILL_SHARE + ")."
                : "Planters leave tilling to the tillers until more than " + share
                        + " plots per tiller wait (default " + FarmerManager.DEFAULT_TILL_SHARE + ")."), false);
        return share;
    }

    private static int set(CommandSourceStack source, int plots) {
        FarmerManager.setTillShare(plots);
        return show(source);
    }
}
