package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.mojang.brigadier.arguments.FloatArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import software.bernie.geckolib.animation.Animation;
import software.bernie.geckolib.animation.EasingType;
import software.bernie.geckolib.animation.keyframe.AnimationPoint;
import software.bernie.geckolib.animation.keyframe.BoneAnimation;
import software.bernie.geckolib.animation.keyframe.Keyframe;
import software.bernie.geckolib.cache.GeckoLibCache;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.loading.math.MathValue;
import software.bernie.geckolib.loading.math.value.Constant;
import software.bernie.geckolib.loading.object.BakedAnimations;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Function;

/**
 * Legs for an archer that steps back while it draws.
 *
 * <p>The shot clip ({@code ki_bow_shot}) keys every bone, legs included, and it
 * outranks the walk, so an archer backing away from a mob mid-draw glided
 * across the ground in its planted stance. While it moves, this blends its legs
 * into the villager's own walk cycle, played backwards when it backs away. The
 * upper body -- bow arm, drawing hand, head -- stays on the shot clip, so the aim
 * and the release frame are untouched.
 *
 * <p>The stance turns the lower body 44 degrees side-on (the clip's {@code body}
 * key). The walking legs are turned back toward the target by {@link #turn} of
 * that: all the way, they would twist right round under a side-on chest; not at
 * all, they would step out at 44 degrees to the way the archer moves.
 *
 * <p>Steps are paced by distance, off vanilla's leg-swing counter, at the rate
 * the walk clip has on an ordinary walk. The blend follows vanilla's leg-swing
 * speed, which eases in and out over a few ticks, so an archer that stops
 * mid-draw settles back into its stance instead of snapping to it.
 *
 * <p>{@code /aimwalk} tunes it live; see {@link #registerCommands}.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class AimWalkLegs {

    private static final ResourceLocation ANIMATIONS = ResourceLocation.fromNamespaceAndPath(
            DynamicVillages.MODID, "animations/base_villager.animation.json");
    private static final String WALK_CLIP = "mixamo_walk";
    private static final String[] LEG_BONES = {"left_leg", "left_shin", "right_leg", "right_shin"};

    /**
     * Walk clip ticks per unit of vanilla's leg swing. An ordinary villager walk
     * swings about 0.8 a tick while the clip plays one tick a tick, so 1.25
     * keeps the steps as long as on any other walk.
     */
    static final float DEFAULT_PACE = 1.25f;
    /** How much of the stance's side-on turn the walking legs take back: 0 none, 1 all. */
    static final float DEFAULT_TURN = 0.5f;
    /** Leg-swing speed at which the walk has fully replaced the stance (backing off swings about 0.5). */
    static final float DEFAULT_FULL_SWING = 0.3f;

    static float pace = DEFAULT_PACE;
    static float turn = DEFAULT_TURN;
    static float fullSwing = DEFAULT_FULL_SWING;
    static boolean enabled = true;

    /** Per archer, client side: where it is in the walk cycle and which way it last walked. */
    private static final class State {
        float lastSwing = Float.NaN;
        double phaseTicks;
        /** -1 backing away from what it faces, +1 walking toward it. */
        int direction = -1;
    }

    /** Render thread only. Weak, so a villager that unloads takes its state with it. */
    private static final Map<NpcEntity, State> STATES = new WeakHashMap<>();

    private AimWalkLegs() {}

    /**
     * Called once a frame from the model, after GeckoLib has posed the bones: when
     * {@code npc} is mid-shot and moving, its leg bones are blended toward the walk.
     */
    public static void apply(NpcEntity npc, Function<String, GeoBone> bones, float partialTick) {
        if (!enabled || npc.getAnimation() != VillagerAnimationState.AIM
                || npc.isProfessionAnimationPreview() || npc.getMocapClip() > 0
                || npc.isInWaterOrBubble() || npc.isPassenger()) {
            STATES.remove(npc);
            return;
        }
        State state = STATES.computeIfAbsent(npc, k -> new State());
        Animation walk = walkClip();
        if (walk == null || walk.length() <= 0) return;

        // Which way it walks, against the way it faces. Mostly sideways (or still)
        // keeps the last answer, so the cycle does not flip back and forth.
        double dx = npc.getX() - npc.xo;
        double dz = npc.getZ() - npc.zo;
        double moved = dx * dx + dz * dz;
        if (moved > 1.0E-5) {
            float yaw = npc.yBodyRot * Mth.DEG_TO_RAD;
            double ahead = -Mth.sin(yaw) * dx + Mth.cos(yaw) * dz;
            if (ahead * ahead > 0.1 * moved) state.direction = ahead < 0 ? -1 : 1;
        }

        // Distance walked since the last frame, from vanilla's leg swing.
        float swing = npc.walkAnimation.position(partialTick);
        float stepped = Float.isNaN(state.lastSwing) ? 0f : Mth.clamp(swing - state.lastSwing, 0f, 3f);
        state.lastSwing = swing;
        state.phaseTicks = Mth.positiveModulo(state.phaseTicks + state.direction * stepped * pace, walk.length());

        float weight = Mth.clamp(npc.walkAnimation.speed(partialTick) / Math.max(0.01f, fullSwing), 0f, 1f);
        weight = weight * weight * (3f - 2f * weight);
        if (weight <= 0.001f) return;

        GeoBone body = bones.apply("body");
        float bodyYaw = body == null ? 0f : body.getRotY();
        Quaternionf stance = new Quaternionf();
        Quaternionf walking = new Quaternionf();
        Vector3f euler = new Vector3f();
        float[] sample = new float[6];
        for (String name : LEG_BONES) {
            GeoBone bone = bones.apply(name);
            BoneAnimation track = boneTrack(walk, name);
            if (bone == null || track == null) continue;
            sampleWalk(track, bone, state.phaseTicks, sample);

            walking.rotationZYX(sample[2], sample[1], sample[0]);
            // The thighs hang from the turned lower body; the shins follow the thighs.
            if (turn != 0f && !name.endsWith("shin")) {
                walking.premul(new Quaternionf().rotationY(-turn * bodyYaw));
            }
            stance.rotationZYX(bone.getRotZ(), bone.getRotY(), bone.getRotX());
            stance.slerp(walking, weight).getEulerAnglesZYX(euler);
            bone.updateRotation(euler.x, euler.y, euler.z);
            bone.updatePosition(Mth.lerp(weight, bone.getPosX(), sample[3]),
                    Mth.lerp(weight, bone.getPosY(), sample[4]),
                    Mth.lerp(weight, bone.getPosZ(), sample[5]));
            // The setters mark the bone "animated this frame", and only GeckoLib's posing clears
            // that. Left on, the next villager posed on these shared bones whose clips leave its
            // legs alone would skip easing them back and wear this archer's walking legs.
            bone.resetStateChanges();
        }
    }

    private static Animation walkClip() {
        Map<ResourceLocation, BakedAnimations> all = GeckoLibCache.getBakedAnimations();
        BakedAnimations anims = all == null ? null : all.get(ANIMATIONS);
        return anims == null ? null : anims.getAnimation(WALK_CLIP);
    }

    private static BoneAnimation boneTrack(Animation clip, String bone) {
        for (BoneAnimation track : clip.boneAnimations()) {
            if (track.boneName().equals(bone)) return track;
        }
        return null;
    }

    /**
     * The walk clip's pose for one bone at {@code tick}, in the terms GeckoLib
     * leaves on a bone: rotation in radians on top of the rest pose, then position.
     */
    private static void sampleWalk(BoneAnimation track, GeoBone bone, double tick, float[] out) {
        var rest = bone.getInitialSnapshot();
        var rot = track.rotationKeyFrames();
        out[0] = rest.getRotX();
        out[1] = rest.getRotY();
        out[2] = rest.getRotZ();
        if (!rot.xKeyframes().isEmpty()) {
            out[0] += (float) valueAt(rot.xKeyframes(), tick, true, true);
            out[1] += (float) valueAt(rot.yKeyframes(), tick, true, true);
            out[2] += (float) valueAt(rot.zKeyframes(), tick, true, false);
        }
        var pos = track.positionKeyFrames();
        boolean hasPos = !pos.xKeyframes().isEmpty();
        out[3] = hasPos ? (float) valueAt(pos.xKeyframes(), tick, false, false) : 0f;
        out[4] = hasPos ? (float) valueAt(pos.yKeyframes(), tick, false, false) : 0f;
        out[5] = hasPos ? (float) valueAt(pos.zKeyframes(), tick, false, false) : 0f;
    }

    /**
     * One axis of a keyframe track at {@code tick}, read the way GeckoLib's own
     * controller reads it: constants were turned to radians (X and Y negated)
     * when the file was baked, anything else is turned here.
     */
    private static double valueAt(List<Keyframe<MathValue>> frames, double tick, boolean rotation, boolean negate) {
        Keyframe<MathValue> frame = frames.getLast();
        double into = frame.length();
        double end = 0;
        for (Keyframe<MathValue> f : frames) {
            end += f.length();
            if (end > tick) {
                frame = f;
                into = tick - (end - f.length());
                break;
            }
        }
        double from = frame.startValue().get();
        double to = frame.endValue().get();
        if (rotation) {
            if (!(frame.startValue() instanceof Constant)) from = Math.toRadians(from) * (negate ? -1 : 1);
            if (!(frame.endValue() instanceof Constant)) to = Math.toRadians(to) * (negate ? -1 : 1);
        }
        if (frame.length() <= 0) return to;
        return EasingType.lerpWithOverride(new AnimationPoint(frame, into, frame.length(), from, to), null);
    }

    // -------------------------------------------------------------------------
    // /aimwalk
    // -------------------------------------------------------------------------

    /**
     * <pre>
     *   /aimwalk               show the values
     *   /aimwalk pace &lt;x&gt;      steps per distance (default 1.25; higher = quicker, shorter steps)
     *   /aimwalk turn &lt;x&gt;      0 = legs stay side-on with the hips, 1 = legs face the target (default 0.5)
     *   /aimwalk blend &lt;x&gt;     leg swing at which the walk is fully in (default 0.3; lower = sooner)
     *   /aimwalk on|off|reset
     * </pre>
     * Client side and not saved: copy a good value into the defaults above.
     */
    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aimwalk")
                .executes(ctx -> report(ctx.getSource()))
                .then(Commands.literal("pace").then(Commands.argument("value", FloatArgumentType.floatArg(0.1f, 5f))
                        .executes(ctx -> {
                            pace = FloatArgumentType.getFloat(ctx, "value");
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("turn").then(Commands.argument("value", FloatArgumentType.floatArg(-1f, 2f))
                        .executes(ctx -> {
                            turn = FloatArgumentType.getFloat(ctx, "value");
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("blend").then(Commands.argument("value", FloatArgumentType.floatArg(0.01f, 2f))
                        .executes(ctx -> {
                            fullSwing = FloatArgumentType.getFloat(ctx, "value");
                            return report(ctx.getSource());
                        })))
                .then(Commands.literal("on").executes(ctx -> {
                    enabled = true;
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    enabled = false;
                    return report(ctx.getSource());
                }))
                .then(Commands.literal("reset").executes(ctx -> {
                    pace = DEFAULT_PACE;
                    turn = DEFAULT_TURN;
                    fullSwing = DEFAULT_FULL_SWING;
                    enabled = true;
                    return report(ctx.getSource());
                })));
    }

    private static int report(CommandSourceStack source) {
        String line = String.format(Locale.ROOT, "Archer walking legs %s: pace %.2f, turn %.2f, blend %.2f",
                enabled ? "ON" : "OFF", pace, turn, fullSwing);
        source.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }
}
