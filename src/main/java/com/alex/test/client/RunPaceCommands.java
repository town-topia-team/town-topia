package com.alex.test.client;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.RunPace;
import com.alex.test.entity.NpcEntity;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.Locale;

/**
 * {@code /runpace}: live tuning for how fast the run, sword run, injured run and
 * jog play ({@link RunPace}). Client side and not saved: copy a value that looks
 * right into RunPace's defaults.
 *
 * <pre>
 *   /runpace                 the settings, and the villager you look at: its pace, gait, playback
 *   /runpace stride &lt;x&gt;      ground covered per foot movement: 2.05 = as grounded as the walk,
 *                            1 = feet planted exactly (legs twice as fast)
 *   /runpace min &lt;x&gt;         slowest playback
 *   /runpace max &lt;x&gt;         fastest playback
 *   /runpace air &lt;x&gt;         playback while a running leap is held in the air
 *   /runpace reset
 * </pre>
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class RunPaceCommands {

    private RunPaceCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("runpace")
                .executes(ctx -> report(ctx.getSource()));
        root.then(Commands.literal("stride").then(Commands.argument("x", FloatArgumentType.floatArg(0.5f, 4f))
                .executes(ctx -> {
                    RunPace.setStride(FloatArgumentType.getFloat(ctx, "x"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("min").then(Commands.argument("x", FloatArgumentType.floatArg(0.2f, 3f))
                .executes(ctx -> {
                    RunPace.setMin(FloatArgumentType.getFloat(ctx, "x"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("max").then(Commands.argument("x", FloatArgumentType.floatArg(0.5f, 4f))
                .executes(ctx -> {
                    RunPace.setMax(FloatArgumentType.getFloat(ctx, "x"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("air").then(Commands.argument("x", FloatArgumentType.floatArg(0.02f, 2f))
                .executes(ctx -> {
                    RunPace.setAir(FloatArgumentType.getFloat(ctx, "x"));
                    return report(ctx.getSource());
                })));
        root.then(Commands.literal("reset").executes(ctx -> {
            RunPace.reset();
            return report(ctx.getSource());
        }));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        StringBuilder line = new StringBuilder(String.format(Locale.ROOT,
                "Run pace: stride %.2f, playback %.2f to %.2f, %.2f in the air",
                RunPace.stride(), RunPace.min(), RunPace.max(), RunPace.air()));
        for (HurryGait gait : HurryGait.values()) {
            if (gait == HurryGait.NONE) continue;
            line.append(String.format(Locale.ROOT, "\n  %s plays at 1 at %.1f blocks/s",
                    gait.name().toLowerCase(Locale.ROOT), RunPace.speedAtRateOne(gait)));
        }
        NpcEntity npc = lookedAt();
        if (npc != null) {
            HurryGait shown = npc.playingHurryGait();
            line.append(String.format(Locale.ROOT, "\n%s: %.1f blocks/s, %s, playback %.2f%s",
                    npc.getName().getString(), npc.clientGroundSpeed(),
                    shown == HurryGait.NONE ? "not hurrying" : shown.name().toLowerCase(Locale.ROOT),
                    npc.playingHurryRate(), npc.isClientHopping() ? ", in a hop" : ""));
        }
        String text = line.toString();
        source.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** The villager under the crosshair, else the nearest within 16 blocks. */
    @Nullable
    private static NpcEntity lookedAt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return null;
        Entity picked = mc.crosshairPickEntity;
        if (picked instanceof NpcEntity npc) return npc;
        return mc.level.getEntitiesOfClass(NpcEntity.class, mc.player.getBoundingBox().inflate(16.0))
                .stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(mc.player)))
                .orElse(null);
    }
}
