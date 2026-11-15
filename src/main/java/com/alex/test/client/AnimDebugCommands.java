package com.alex.test.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.AnimationProcessor;
import software.bernie.geckolib.animation.RawAnimation;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Locale;

/**
 * {@code /animdebug}: what each animation channel of the nearest villager is
 * doing, for the "stiff limbs after pressing Escape" report (10-08). Run it
 * once before pausing and once after.
 *
 * <p>Per channel it shows the clip our code last asked for ("asked") next to
 * the clip GeckoLib is really playing ("playing"). When those differ on a
 * running channel the switch got stuck half done, which is the suspect. The
 * last line counts how many villagers within 32 blocks have such a channel.
 * Everything also goes to the log. Client side, read only.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class AnimDebugCommands {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double RANGE = 32;

    private static final Field TICK_OFFSET = field("tickOffset");
    private static final Field LAST_POLL_TIME = field("lastPollTime");

    private AnimDebugCommands() {}

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("animdebug")
                .executes(ctx -> report(ctx.getSource()));
        event.getDispatcher().register(root);
    }

    private static int report(CommandSourceStack source) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return 0;

        Entity nearest = null;
        double nearestDist = Double.MAX_VALUE;
        int villagers = 0;
        int stuckVillagers = 0;
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof GeoEntity)) continue;
            double dist = entity.distanceToSqr(player);
            if (dist > RANGE * RANGE) continue;
            villagers++;
            if (countStuck(entity, null) > 0) stuckVillagers++;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = entity;
            }
        }
        if (nearest == null) {
            source.sendSystemMessage(Component.literal("No villager within 32 blocks.").withStyle(ChatFormatting.GRAY));
            return 0;
        }

        StringBuilder text = new StringBuilder();
        AnimatableManager<?> manager = manager(nearest);
        text.append(String.format(Locale.ROOT, "%s (id %d) tick %d, paused %s, clock %.2f",
                nearest.getName().getString(), nearest.getId(), nearest.tickCount, mc.isPaused(),
                manager.getLastUpdateTime()));
        countStuck(nearest, text);
        text.append(String.format(Locale.ROOT, "\nNearby: %d of %d villagers have a stuck channel",
                stuckVillagers, villagers));

        LOGGER.info("[animdebug] {}", text);
        source.sendSystemMessage(Component.literal(text.toString()));
        return 1;
    }

    /** Stuck channels on this entity; with {@code out}, one line per channel is added to it. */
    private static int countStuck(Entity entity, @Nullable StringBuilder out) {
        int stuck = 0;
        for (AnimationController<?> controller : manager(entity).getAnimationControllers().values()) {
            RawAnimation asked = controller.getCurrentRawAnimation();
            AnimationProcessor.QueuedAnimation playing = controller.getCurrentAnimation();
            String playingName = playing == null ? null : playing.animation().name();
            boolean mismatch = controller.getAnimationState() != AnimationController.State.STOPPED
                    && asked != null && playingName != null && !asks(asked, playingName);
            if (mismatch) stuck++;
            if (out == null) continue;
            if (controller.getAnimationState() == AnimationController.State.STOPPED && !mismatch) {
                out.append("\n ").append(controller.getName()).append(": stopped");
                continue;
            }
            out.append(String.format(Locale.ROOT, "\n %s%s: %s, asked %s, playing %s, start %.2f, last switch %.2f",
                    mismatch ? "STUCK " : "", controller.getName(), controller.getAnimationState(),
                    names(asked), playingName, read(TICK_OFFSET, controller), read(LAST_POLL_TIME, controller)));
        }
        return stuck;
    }

    private static AnimatableManager<?> manager(Entity entity) {
        return ((GeoEntity) entity).getAnimatableInstanceCache().getManagerForId(entity.getId());
    }

    private static boolean asks(RawAnimation raw, String name) {
        for (RawAnimation.Stage stage : raw.getAnimationStages()) {
            if (stage.animationName().equals(name)) return true;
        }
        return false;
    }

    private static String names(@Nullable RawAnimation raw) {
        if (raw == null) return "nothing";
        StringBuilder names = new StringBuilder();
        for (RawAnimation.Stage stage : raw.getAnimationStages()) {
            if (!names.isEmpty()) names.append('+');
            names.append(stage.animationName());
        }
        return names.toString();
    }

    @Nullable
    private static Field field(String name) {
        try {
            Field field = AnimationController.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static double read(@Nullable Field field, AnimationController<?> controller) {
        if (field == null) return Double.NaN;
        try {
            return field.getDouble(controller);
        } catch (IllegalAccessException e) {
            return Double.NaN;
        }
    }
}
