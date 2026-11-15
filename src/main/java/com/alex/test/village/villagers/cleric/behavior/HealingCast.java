package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.HealingOrbEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcUiInteractionHandler;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * One healing throw: the orb appears on the cleric's right palm, the arm winds
 * back and snaps forward, and the orb leaves at the front of the snap and
 * curves after the patient. The cleric stands still facing the patient the
 * whole time; the throw is paced by its skill ({@link ClericBalance#castSpeed}).
 *
 * <p>A villager patient is asked to wait and face the cleric while the orb
 * flies (VillagerBrain#requestCareHold). A guard or archer in a fight, or
 * anyone fleeing or sheltering, says no and is healed on the move: the orb
 * finds it wherever it is.
 */
final class HealingCast {

    /** The patient may have moved off a little; the orb still goes if it is this far. */
    private static final double RELEASE_RANGE = ClericBalance.CAST_RANGE * 1.5;

    private final UUID patientId;
    private final String patientName;
    private final int level;
    private final int releaseAt;
    private final int endAt;
    private final HealingOrbEntity.Blessing blessing;
    private final java.util.function.Predicate<LivingEntity> splashWho;
    @Nullable private VillagerBrain heldBrain;
    private int ticks;
    private boolean released;

    HealingCast(Activity.Context ctx, LivingEntity patient, int level, ProfessionSettings settings) {
        this.patientId = patient.getUUID();
        this.patientName = ClericCare.nameOf(patient);
        this.level = level;
        this.releaseAt = ClericBalance.releaseTicks(level);
        this.endAt = ClericBalance.castTicks(level);
        this.blessing = ClericCare.blessing(level);
        this.splashWho = ClericCare.splashWho(ctx.mgr(), settings);
        NpcEntity cleric = ctx.npc();
        ctx.model().stopMoving();
        cleric.setRunning(false);
        cleric.setHoldingOrb(true);
        cleric.startWorkAnimation(VillagerAnimationState.CAST, ClericBalance.castSpeed(level));
        NpcUiInteractionHandler.faceEntity(cleric, patient, true);
        if (patient instanceof NpcEntity npc) {
            VillagerBrain brain = ClericCare.brainOf(ctx.mgr(), npc);
            // Waits for the throw and the flight; released early when the orb lands.
            if (brain != null && brain.requestCareHold(cleric, endAt + 30)) heldBrain = brain;
        }
        ClericCare.claim(cleric, patient, ctx.level().getGameTime());
    }

    UUID patientId() { return patientId; }

    /** @return true once the throw is over, follow-through and all */
    boolean tick(Activity.Context ctx) {
        ticks++;
        NpcEntity cleric = ctx.npc();
        cleric.holdStillForAnimation();
        LivingEntity patient = ClericCare.resolve(ctx, patientId);
        if (patient != null) {
            NpcUiInteractionHandler.faceEntity(cleric, patient, true);
            ClericCare.claim(cleric, patient, ctx.level().getGameTime());
        }
        if (!released && ticks >= releaseAt) {
            released = true;
            cleric.setHoldingOrb(false);
            Vec3 hand = handPosition(cleric);
            if (patient != null && cleric.distanceToSqr(patient) <= RELEASE_RANGE * RELEASE_RANGE) {
                HealingOrbEntity orb = HealingOrbEntity.throwAt(ctx.level(), cleric, hand, patient, blessing, splashWho,
                        landing -> onLanded(ctx, landing));
                com.alex.test.village.farawake.FarExtras.trackShot(orb, cleric); // flies on past the simulation distance
            } else {
                // Nobody left to throw at: the orb fades in the hand.
                ctx.level().sendParticles(ParticleTypes.HAPPY_VILLAGER, hand.x, hand.y, hand.z,
                        6, 0.1, 0.1, 0.1, 0.0);
                releaseHold(cleric);
            }
        }
        return ticks >= endAt;
    }

    /**
     * Ends the throw early or on time: the orb is put away and the patient let
     * go if it never flew.
     *
     * @param resetPose false when another throw may follow at once: the clip
     *                  holds its last frame, which is its first, so the next
     *                  throw starts without a one-tick blink to idle
     */
    void stop(Activity.Context ctx, boolean resetPose) {
        NpcEntity cleric = ctx.npc();
        cleric.setHoldingOrb(false);
        if (!released) releaseHold(cleric);
        if (resetPose && cleric.getAnimation() == VillagerAnimationState.CAST) {
            cleric.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    private void releaseHold(NpcEntity cleric) {
        if (heldBrain != null) heldBrain.releaseCareHold(cleric);
        heldBrain = null;
    }

    /**
     * Where the orb leaves from: exactly where the palm orb is drawn at the
     * release frame (clip 0.85 s), measured on the shipping rig with
     * tools/HeldItemPreview.java: 1.22 blocks up, 0.77 ahead and 0.52 to the
     * right, the body being turned into the throw by then. It used to leave
     * from a guess 0.13 higher and nearer the chest.
     */
    private static final double RELEASE_UP = 1.22;
    private static final double RELEASE_AHEAD = 0.77;
    private static final double RELEASE_RIGHT = 0.52;

    static Vec3 handPosition(NpcEntity cleric) {
        double yaw = Math.toRadians(cleric.yBodyRot);
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        return cleric.position().add(fx * RELEASE_AHEAD + rx * RELEASE_RIGHT, RELEASE_UP,
                fz * RELEASE_AHEAD + rz * RELEASE_RIGHT);
    }

    /** The orb landed: pay the cleric, thank it, and let the patient go on. */
    private void onLanded(Activity.Context ctx, HealingOrbEntity.Landing landing) {
        NpcEntity cleric = ctx.npc();
        releaseHold(cleric);
        LivingEntity patient = landing.patient();
        // A starving villager the orb reached uses one of its three heals, cleric alive or not.
        ClericCare.countHeal(ctx.mgr(), patient, "cleric orb from " + ClericCare.nameOf(cleric), ctx.level());
        for (LivingEntity other : landing.splashed()) {
            ClericCare.countHeal(ctx.mgr(), other, "cleric splash from " + ClericCare.nameOf(cleric), ctx.level());
        }
        if (!cleric.isAlive()) return;
        float healed = landing.healed() + landing.splashHealed();
        float points = healed / 2f * ClericBalance.POINTS_PER_HEART;
        // Cleansing an ailment with no health to give back still counts for a little.
        if (points < 0.5f && (blessing.cleanse() || !landing.splashed().isEmpty())) points = 0.5f;
        String what = String.format("healed %s %.1f hp%s", patientName, landing.healed(),
                landing.splashed().isEmpty() ? ""
                        : String.format(" + %d nearby %.1f hp", landing.splashed().size(), landing.splashHealed()));
        ctx.brain().onClericCare(ctx, points, what);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " CLERIC orb landed on " + patientName
                + String.format(" (hp %.1f/%.1f, level %d)", patient.getHealth(), patient.getMaxHealth(), level));

        if (patient instanceof NpcEntity npc) {
            VillagerBrain brain = ClericCare.brainOf(ctx.mgr(), npc);
            if (brain != null) {
                brain.rememberAndShow(MemoryTopic.CARED_FOR, ThoughtIcon.EMOTE_GRATITUDE, 50, ctx.level());
            } else {
                npc.showBubble(ThoughtIcon.EMOTE_GRATITUDE, false, 50);
            }
        } else if (patient instanceof ServerPlayer player && landing.healed() > 0f) {
            player.displayClientMessage(Component.literal(ClericCare.nameOf(cleric) + " healed you"), true);
        }
    }
}
