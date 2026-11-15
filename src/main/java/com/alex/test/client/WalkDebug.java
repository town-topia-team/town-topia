package com.alex.test.client;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

/**
 * Finds out why a villager is moving without its walk ({@code /walkdebug}).
 *
 * <p>The walk is the bottom layer: every clip played over it that keys the legs
 * -- the shield block, a sword swing, a gesture, a work clip -- wins, and the
 * villager glides. While this is on, a nearby guard, captain or archer (or any
 * villager, with {@code /walkdebug all}) that keeps moving without its walk for
 * {@value #REPORT_AFTER_TICKS} ticks gets one chat line saying what holds its
 * legs, written to the log as well; the villager under the crosshair shows its
 * state on the action bar. Client side, not saved, off by default.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class WalkDebug {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Villagers this far away (blocks) are watched. */
    private static final double RANGE = 48.0;
    /** Over the ground faster than this (blocks a second) counts as moving. */
    private static final double MOVING_SPEED = 0.5;
    /** Moving without the walk this long (ticks) is reported... */
    private static final int REPORT_AFTER_TICKS = 10;
    /** ...again for the same villager and reason no sooner than this. */
    private static final int REPORT_AGAIN_TICKS = 100;
    /** An overlay this recently ended may still be easing out, the walk waiting for it. */
    private static final int EASE_OUT_TICKS = 8;
    /** Starting off this many times inside {@link #RESTART_WINDOW_TICKS} next to an enemy is stop-start. */
    private static final int RESTARTS = 3;
    private static final int RESTART_WINDOW_TICKS = 40;
    /** The action-bar readout looks at the villager within this angle of the crosshair. */
    private static final double LOOK_DOT = 0.995;

    private static boolean enabled;
    private static boolean everyone;

    private static final class Track {
        @Nullable String reason;
        long since;
        @Nullable String reported;
        long reportedAt = Long.MIN_VALUE / 2;
        @Nullable VillagerAnimationState anim;
        @Nullable VillagerAnimationState previous;
        long animChangedAt = Long.MIN_VALUE / 2;
        boolean moving;
        final Deque<Long> startsNearEnemy = new ArrayDeque<>();
    }

    private static final Map<NpcEntity, Track> TRACKS = new WeakHashMap<>();

    private WalkDebug() {}

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) return;
        long now = level.getGameTime();

        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        NpcEntity lookedAt = null;
        double lookedDist = Double.MAX_VALUE;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof NpcEntity npc) || npc.distanceToSqr(player) > RANGE * RANGE) continue;
            if (!everyone && !npc.getProfession().isDefender()) continue;
            Track track = TRACKS.computeIfAbsent(npc, k -> new Track());
            String reason = observe(npc, track, now);
            if (reason != null && now - track.since >= REPORT_AFTER_TICKS
                    && (!reason.equals(track.reported) || now - track.reportedAt >= REPORT_AGAIN_TICKS)) {
                track.reported = reason;
                track.reportedAt = now;
                report(player, npc, reason, now - track.since);
            }
            Vec3 to = npc.position().add(0.0, npc.getBbHeight() * 0.6, 0.0).subtract(eye);
            double dist = to.length();
            if (dist > 0.1 && to.scale(1.0 / dist).dot(look) > LOOK_DOT && dist < lookedDist) {
                lookedDist = dist;
                lookedAt = npc;
            }
        }
        if (lookedAt != null) {
            Track track = TRACKS.get(lookedAt);
            mc.gui.setOverlayMessage(status(lookedAt, track != null ? track.reason : null), false);
        }
    }

    /** Updates this villager's track; what holds its legs while it moves, or null when it walks (or stands). */
    @Nullable
    private static String observe(NpcEntity npc, Track track, long now) {
        VillagerAnimationState anim = npc.getAnimation();
        if (anim != track.anim) {
            track.previous = track.anim;
            track.anim = anim;
            track.animChangedAt = now;
        }
        boolean moving = npc.clientGroundSpeed() > MOVING_SPEED;
        if (moving && !track.moving && npc.isCombatReady()) track.startsNearEnemy.addLast(now);
        track.moving = moving;
        while (!track.startsNearEnemy.isEmpty() && now - track.startsNearEnemy.peekFirst() > RESTART_WINDOW_TICKS) {
            track.startsNearEnemy.removeFirst();
        }
        String reason = moving ? legOwner(npc, track, now) : null;
        if (!Objects.equals(reason, track.reason)) {
            track.reason = reason;
            track.since = now;
        }
        return reason;
    }

    /** What is showing on the legs of a moving villager instead of its walk; null when the walk shows. */
    @Nullable
    private static String legOwner(NpcEntity npc, Track track, long now) {
        if (npc.isInWaterOrBubble()) return "in water (the walk is off while swimming)";
        if (npc.isSleeping()) return "asleep";
        if (npc.isChairSeated()) return "seated on a chair";
        if (npc.showsShieldUp()) return "shield up (the block pose holds the legs)";
        VillagerAnimationState anim = npc.getAnimation();
        String clip = legClip(anim);
        if (clip != null) return clip;
        if (track.previous != null && legClip(track.previous) != null && now - track.animChangedAt < EASE_OUT_TICKS) {
            return "easing out of " + name(track.previous) + " (the walk waits for it)";
        }
        if (track.startsNearEnemy.size() >= RESTARTS) {
            return "stop-start by an enemy (the ready stance keeps restarting the walk)";
        }
        return null;
    }

    /** The clip a state plays that keys the legs, described; null for one that leaves them to the walk. */
    @Nullable
    private static String legClip(VillagerAnimationState anim) {
        return switch (anim) {
            case ATTACK -> "sword swing (the slash holds the legs)";
            case KICK, POWER_UP, SALUTE, WAVE, THINK, ARM_ON_HIP, SHRUG, LOOK_AROUND -> "gesture " + name(anim) + " (holds the legs)";
            case CAST -> "healing throw (holds the legs)";
            case CHOP, LUMBER_CHOP, TILL, TILL_SKILLED, PLANT, HARVEST, PICK, PICK_LOW, PICK_BUSH, PICK_HIGH, HARVEST_CHOP,
                 MINE, FORGE, DANCE, CRAFTING, CRAFT_TABLE, WASH, SMITH_ANVIL, SMITH_ANVIL_HEAVY, SMITH_ENCHANT,
                 SMITH_ENCHANT_TAP, SMITH_SMITHING, SMITH_RECYCLE, SMITH_FURNACE, SMITH_ANVIL_DRAW,
                 VIOLIN_PLAY, VIOLIN_DANCE, FLUTE_PLAY, FLUTE_DANCE, GUITAR_PLAY, GUITAR_JAM, MAGE_BOLT,
                 MAGE_NOVA, MAGE_CHAIN, MAGE_WARD, MAGE_STARFALL,
                 COOK_KNEAD, COOK_COOKIES, COOK_WHISK, COOK_PIE, COOK_CHOP_STIR, COOK_GILD, COOK_WHITTLE,
                 COOK_PRESS_CANE, COOK_FIRE_LIGHT, COOK_FIRE_FEED, COOK_LOAD, COOK_WAIT_PEEK, COOK_WAIT_HIPS,
                 COOK_WAIT_SPOON, COOK_WAIT_TIDY, COOK_WAIT_SMOKE, COOK_TAKE, COOK_CAMP_LAY, COOK_CAMP_TURN,
                 COOK_CAMP_WAIT, COOK_CAMP_TAKE, COOK_WAIT_STATION, COOK_PRESENT, COOK_KISS,
                 COOK_SLEEVES -> "work clip " + name(anim) + " (holds the legs)";
            case SITDOWN, CHAIR_SIT, SLEEP_SITTING_DOWN, SITUP, SLEEP_LYING_DOWN,
                 WALL_SITDOWN, WALL_SLEEP, WALL_SITUP -> "sitting or lying (" + name(anim) + ")";
            // The archer's draw blends walking legs in (AimWalkLegs); eating and reading key the arms only.
            default -> null;
        };
    }

    private static String name(VillagerAnimationState anim) {
        return anim.name().toLowerCase(Locale.ROOT);
    }

    private static String who(NpcEntity npc) {
        String name = npc.getCustomName() != null ? npc.getCustomName().getString() : "";
        String status = npc.workStatus();
        String label = name.isEmpty() || status.isEmpty() ? name + status : name + " · " + status;
        String profession = npc.getProfession().displayName();
        String id = npc.getNpcId() != null ? npc.getNpcId().toString().substring(0, 8) : "?";
        return (label.isEmpty() ? profession : label + " (" + profession + ")") + " [" + id + "]";
    }

    private static void report(LocalPlayer player, NpcEntity npc, String reason, long forTicks) {
        String line = String.format(Locale.ROOT, "%s moving %.1f b/s with no walk for %.1f s: %s",
                who(npc), npc.clientGroundSpeed(), forTicks / 20.0, reason);
        player.displayClientMessage(Component.literal("[walk] " + line).withStyle(ChatFormatting.GRAY), false);
        LOGGER.info("[WalkDebug] {} | anim={} shieldUp={} ready={} hurry={} at {} {} {}",
                line, name(npc.getAnimation()), npc.showsShieldUp(), npc.isCombatReady(), npc.hurryGait(),
                npc.getBlockX(), npc.getBlockY(), npc.getBlockZ());
    }

    private static Component status(NpcEntity npc, @Nullable String reason) {
        boolean moving = npc.clientGroundSpeed() > MOVING_SPEED;
        if (!moving) {
            return Component.literal(who(npc) + ": standing (" + name(npc.getAnimation()) + ")").withStyle(ChatFormatting.GRAY);
        }
        if (reason == null) {
            return Component.literal(String.format(Locale.ROOT, "%s: walking %.1f b/s", who(npc), npc.clientGroundSpeed()))
                    .withStyle(ChatFormatting.GREEN);
        }
        return Component.literal(String.format(Locale.ROOT, "%s: NO WALK %.1f b/s - %s", who(npc), npc.clientGroundSpeed(), reason))
                .withStyle(ChatFormatting.RED);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("walkdebug")
                .executes(ctx -> set(ctx.getSource(), !enabled, everyone))
                .then(Commands.literal("on").executes(ctx -> set(ctx.getSource(), true, everyone)))
                .then(Commands.literal("off").executes(ctx -> set(ctx.getSource(), false, everyone)))
                .then(Commands.literal("all").executes(ctx -> set(ctx.getSource(), true, true)))
                .then(Commands.literal("defenders").executes(ctx -> set(ctx.getSource(), true, false))));
    }

    private static int set(CommandSourceStack source, boolean on, boolean all) {
        enabled = on;
        everyone = all;
        TRACKS.clear();
        source.sendSuccess(() -> Component.literal(on
                ? "Walk debug ON for " + (all ? "every villager" : "guards, captains and archers")
                        + ": a chat line when one moves without its walk. Look at one for its live state."
                : "Walk debug OFF."), false);
        return 1;
    }
}
