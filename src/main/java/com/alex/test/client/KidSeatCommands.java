package com.alex.test.client;

import com.alex.test.entity.ChairSeatEntity;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import java.util.Locale;

/**
 * {@code /kidseat}: live tuning for where a child sits on a chair
 * ({@link ChairSeatEntity}). Children are drawn smaller than adults, so the
 * adult seat offsets would sink them into the chair. Values are in pixels
 * (1/16 block), not saved: copy one that looks right into ChairSeatEntity.
 *
 * <pre>
 *   /kidseat                 the current values
 *   /kidseat down &lt;px&gt;       how far below the seat top the child is placed (more = lower)
 *   /kidseat forward &lt;px&gt;    how far towards the chair's front (more = further forward)
 *   /kidseat reset
 * </pre>
 *
 * In a single player world the game and its server share these values, so a
 * change shows at once on the children already sitting.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class KidSeatCommands {

    private KidSeatCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("kidseat")
                .executes(ctx -> report(ctx.getSource()));
        root.then(Commands.literal("down").then(Commands.argument("px", FloatArgumentType.floatArg(-8f, 12f))
                .executes(ctx -> {
                    ChairSeatEntity.setChildSeat(ChairSeatEntity.childForwardPixels(),
                            FloatArgumentType.getFloat(ctx, "px"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("forward").then(Commands.argument("px", FloatArgumentType.floatArg(-8f, 8f))
                .executes(ctx -> {
                    ChairSeatEntity.setChildSeat(FloatArgumentType.getFloat(ctx, "px"),
                            ChairSeatEntity.childDownPixels());
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("reset").executes(ctx -> {
            ChairSeatEntity.resetChildSeat();
            return report(ctx.getSource());
        }));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        String text = String.format(Locale.ROOT,
                "Child on a chair: %.1f px down, %.1f px forward (adults: 4.5 down, 1 forward)",
                ChairSeatEntity.childDownPixels(), ChairSeatEntity.childForwardPixels());
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
