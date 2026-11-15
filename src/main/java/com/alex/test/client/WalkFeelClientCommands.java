package com.alex.test.client;

import com.alex.test.village.WalkFeel;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Locale;

/**
 * {@code /walkfeel}: live tuning for the drawing-only round of the
 * walking-feel plan. Client side and not saved: copy a value that looks right
 * into WalkFeel's defaults. The movement switches are on {@code /villager walkfeel}.
 *
 * <pre>
 *   /walkfeel                 the setting
 *   /walkfeel turn &lt;deg&gt;      round 4: most the drawn body turns per tick while walking
 *                             (20 = default; lower is softer; 0 = snap as before)
 * </pre>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class WalkFeelClientCommands {

    private WalkFeelClientCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("walkfeel")
                .executes(ctx -> report(ctx.getSource()));
        root.then(Commands.literal("turn").then(Commands.argument("degrees", FloatArgumentType.floatArg(0f, 180f))
                .executes(ctx -> {
                    WalkFeel.drawnTurnDegrees = FloatArgumentType.getFloat(ctx, "degrees");
                    return report(ctx.getSource());
                })));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        float turn = WalkFeel.drawnTurnDegrees;
        String turning = turn <= 0f || turn >= 180f ? "snaps (off)"
                : String.format(Locale.ROOT, "%.0f deg per tick", turn);
        source.sendSuccess(() -> Component.literal("Drawn body turn while walking: " + turning
                + ". Movement switches: /villager walkfeel"), false);
        return 1;
    }
}
