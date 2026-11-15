package com.alex.test.client;

import com.alex.test.animation.AnimationBlend;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
 * {@code /blend}: live tuning for the smoothing between villager animations
 * ({@link AnimationBlend}). Client side and not saved: copy a value that looks
 * right into AnimationBlend's defaults.
 *
 * <pre>
 *   /blend                          show every group
 *   /blend &lt;group&gt; on|off           smoothing for that group, to compare
 *   /blend &lt;group&gt; out &lt;ticks&gt;      how long that group eases out (20 ticks = 1 s)
 *   /blend all on|off
 *   /blend reset
 *
 *   groups: resume (standing back up after sitting, water, eating),
 *           gestures, hands (eating, reading, crafting), work, cast
 * </pre>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class AnimationBlendCommands {

    private AnimationBlendCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("blend")
                .executes(ctx -> report(ctx.getSource()));
        for (AnimationBlend.Group group : AnimationBlend.Group.values()) {
            String name = group.name().toLowerCase(Locale.ROOT);
            root.then(Commands.literal(name)
                    .then(Commands.literal("on").executes(ctx -> {
                        AnimationBlend.setEnabled(group, true);
                        return report(ctx.getSource());
                    }))
                    .then(Commands.literal("off").executes(ctx -> {
                        AnimationBlend.setEnabled(group, false);
                        return report(ctx.getSource());
                    }))
                    .then(Commands.literal("out").then(Commands.argument("ticks", IntegerArgumentType.integer(1, 40))
                            .executes(ctx -> {
                                AnimationBlend.setOutTicks(group, IntegerArgumentType.getInteger(ctx, "ticks"));
                                return report(ctx.getSource());
                            }))));
        }
        root.then(Commands.literal("all")
                .then(Commands.literal("on").executes(ctx -> setAll(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setAll(ctx.getSource(), false))));
        root.then(Commands.literal("reset").executes(ctx -> {
            AnimationBlend.reset();
            return report(ctx.getSource());
        }));
        event.getDispatcher().register(root);
    }

    private static int setAll(CommandSourceStack source, boolean on) {
        for (AnimationBlend.Group group : AnimationBlend.Group.values()) AnimationBlend.setEnabled(group, on);
        return report(source);
    }

    private static int report(CommandSourceStack source) {
        StringBuilder line = new StringBuilder("Animation smoothing:");
        for (AnimationBlend.Group group : AnimationBlend.Group.values()) {
            line.append("\n  ").append(group.name().toLowerCase(Locale.ROOT)).append(": ")
                    .append(AnimationBlend.enabled(group) ? "ON" : "OFF");
            if (group != AnimationBlend.Group.RESUME) {
                line.append(String.format(Locale.ROOT, ", eases out over %d ticks (%.2f s)",
                        AnimationBlend.outTicks(group), AnimationBlend.outTicks(group) / 20.0));
            }
        }
        String text = line.toString();
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
