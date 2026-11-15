package com.alex.test.command;

import com.alex.test.performance.PerformanceSettings;
import com.alex.test.village.farawake.FarAwake;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * {@code /villager farawake}: far villagers stay awake (FarAwake). Live test switches, not saved: a restart
 * takes {@code on|off} from the config ({@code farVillagersAwake}), turns the others back on, then applies the
 * world's saved choices for {@code on|off} and {@code sync} (the Mayor's Performance page, PerformanceSettings).
 * The report's last line shows those saved choices, so a live change can be told from a saved one.
 *
 * <pre>
 *   /villager farawake                     which are on, and each village's numbers in this dimension
 *   /villager farawake on|off              the whole system
 *   /villager farawake sleepskip on|off    asleep far villagers are held but not moved
 *   /villager farawake extras on|off       arrows, led animals and killed creatures of far villagers move too
 *   /villager farawake placeguard on|off   no teleport into a chunk that is not loaded
 *   /villager farawake wake on|off         far villagers' chunks load when a village wakes or the server starts
 *   /villager farawake log on|off          the [FarAwake] minute line
 *   /villager farawake sync on|off         far bodies the mod moves are sent to the players who see them
 * </pre>
 */
public final class FarAwakeCommands {

    private FarAwakeCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> commandTree() {
        return Commands.literal("farawake")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> {
                    FarAwake.setAwake(true);
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    FarAwake.setAwake(false);
                    return report(ctx.getSource());
                }))
                .then(toggle("sleepskip", on -> FarAwake.sleepSkip = on))
                .then(toggle("extras", on -> FarAwake.extras = on))
                .then(toggle("placeguard", on -> FarAwake.placeGuard = on))
                .then(toggle("wake", FarAwake::setWake))
                .then(toggle("log", on -> FarAwake.log = on))
                .then(toggle("sync", FarAwake::setSync));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> toggle(String name, Consumer<Boolean> set) {
        return Commands.literal(name)
                .then(Commands.literal("on").executes(ctx -> {
                    set.accept(true);
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    set.accept(false);
                    return report(ctx.getSource());
                }));
    }

    private static int report(CommandSourceStack source) {
        for (String line : FarAwake.status(source.getLevel())) {
            source.sendSuccess(() -> Component.literal(line), false);
        }
        String saved = PerformanceSettings.describe(source.getServer());
        source.sendSuccess(() -> Component.literal(saved), false);
        return 1;
    }
}
