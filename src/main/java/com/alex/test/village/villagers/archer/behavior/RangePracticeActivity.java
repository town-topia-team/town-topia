package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.BarracksDrills;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Range time: an off-duty archer practises in the same window the guards
 * drill in (see {@link BarracksDrills#inWindow}), in daylight only. In
 * practice that is the night shift's afternoon before its shift; the day
 * shift practises on duty instead, while nothing is happening.
 *
 * <p>Sessions ({@link ArcheryPractice}) follow one another with a short rest
 * in between, standing at the lane, which the archer keeps for the whole
 * window. Started by {@link VillagerBrain}'s override; it ends when the window
 * or daylight does, at an alarm, when the archer goes on duty or gets hungry.
 * A defender that is attacked is switched to its work by the brain.
 *
 * <p>Stock tab orders for arrows or bows come first ({@link ArcherOrders}):
 * range time starts with one when there is one, and between sessions.
 */
public final class RangePracticeActivity extends Activity {

    /** Rest at the lane between two sessions. */
    private static final int REST_TICKS = 400;
    private static final int REST_JITTER_TICKS = 400;
    /** No lane free, or the lane unreachable: not tried again for this long. */
    private static final int RETRY_TICKS = 1200;
    private static final long MAX_WAIT_TICKS = 24000L;

    /** Archer npc id -> game time before which range time is not tried again. */
    private static final Map<UUID, Long> NOT_BEFORE = new HashMap<>();

    /** How often range time looks for a Stock tab order between sessions. */
    private static final int ORDER_CHECK_TICKS = 200;

    private final ArrowRecovery recovery;
    @Nullable private ArcheryPractice session;
    /** A Stock tab order being made, if any. */
    @Nullable private FletchArrowsActivity order;
    private long nextOrderCheckAt;
    /** The lane kept between sessions: target, and where to rest. Null before the first session. */
    @Nullable private BlockPos laneTarget;
    @Nullable private BlockPos laneStand;
    private long restUntil;

    private RangePracticeActivity(ArrowRecovery recovery, @Nullable ArcheryPractice session,
                                  @Nullable FletchArrowsActivity order) {
        this.recovery = recovery;
        this.session = session;
        this.order = order;
        if (session != null) {
            this.laneTarget = session.target();
            this.laneStand = session.stand();
        }
    }

    /** Range time for this off-duty archer now, with its lane claimed; null if not now. */
    @Nullable
    public static Activity offDutyFor(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.ARCHER) return null;
        if (!ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA).getBool(ArcherSettingsSchema.WORK_ENABLED)) {
            return null;
        }
        if (!BarracksDrills.inWindow(ctx) || VillagerBrain.isNight(ctx)) return null;
        long now = ctx.level().getGameTime();
        Long until = NOT_BEFORE.get(ctx.model().npcId);
        if (until != null && now < until && until - now <= MAX_WAIT_TICKS) return null;
        ArrowRecovery recovery = ArrowRecovery.of(ctx.model().npcId);
        FletchArrowsActivity order = ArcherOrders.next(ctx);
        if (order != null) return new RangePracticeActivity(recovery, null, order);
        ArcheryPractice session = ArcheryPractice.begin(ctx, recovery, true);
        if (session == null) {
            waitBeforeRetry(ctx);
            return null;
        }
        return new RangePracticeActivity(recovery, session, null);
    }

    private static void waitBeforeRetry(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        NOT_BEFORE.put(ctx.model().npcId, now + RETRY_TICKS);
        if (NOT_BEFORE.size() > 256) NOT_BEFORE.values().removeIf(until -> until < now);
    }

    @Override
    protected Status tick(Context ctx) {
        NpcEntity npc = ctx.npc();
        VillagerStats needs = ctx.brain().needs();
        if (npc.getProfession() != Profession.ARCHER
                || VillagerRegistry.shouldWorkNow(npc.getProfession(), ctx)
                || GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                || !BarracksDrills.inWindow(ctx) || VillagerBrain.isNight(ctx)
                || !needs.hungerRules().canRoutineWork(needs)) {
            return Status.DONE;
        }
        long now = ctx.level().getGameTime();
        if (order != null) {
            Status status = order.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            order.stop(ctx);
            order = null;
            nextOrderCheckAt = now + (status == Status.FAILED ? RETRY_TICKS : 0);
            return Status.RUNNING;
        }
        if (session != null) {
            Status status = session.tick(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            laneTarget = session.target();
            laneStand = session.stand();
            session.stop(ctx);
            session = null;
            if (status == Status.FAILED) {
                waitBeforeRetry(ctx);
                return Status.DONE;
            }
            if (!ArcheryPractice.canPractiseNow(ctx)) {
                // No more practice today; an order may still be worth the time.
                order = ArcherOrders.next(ctx);
                if (order != null) return Status.RUNNING;
                waitBeforeRetry(ctx);
                return Status.DONE;
            }
            restUntil = now + REST_TICKS + npc.getRandom().nextInt(REST_JITTER_TICKS);
            return Status.RUNNING;
        }
        // Between sessions: a Stock tab order first.
        if (now >= nextOrderCheckAt) {
            nextOrderCheckAt = now + ORDER_CHECK_TICKS;
            order = ArcherOrders.next(ctx);
            if (order != null) return Status.RUNNING;
        }
        // Resting at its own lane, keeping it for the next session. If another
        // archer has taken it meanwhile (this one stood frozen too long, say
        // with its screen open), it looks for a free lane now instead.
        if (now < restUntil && laneTarget != null
                && ArcheryLanes.renew(ctx.level(), laneTarget, laneStand, ctx.model().npcId)) {
            npc.holdStillForAnimation();
            GuardCombatRules.face(npc, Vec3.atCenterOf(laneTarget));
            return Status.RUNNING;
        }
        if (!ArcheryPractice.canPractiseNow(ctx)) {
            waitBeforeRetry(ctx);
            return Status.DONE;
        }
        session = ArcheryPractice.begin(ctx, recovery, true);
        if (session == null) {
            waitBeforeRetry(ctx);
            return Status.DONE;
        }
        laneTarget = session.target();
        laneStand = session.stand();
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (session != null) {
            session.stop(ctx);
            session = null;
        }
        if (order != null) {
            order.stop(ctx);
            order = null;
        }
        if (laneTarget != null) ArcheryLanes.release(ctx.level(), laneTarget, ctx.model().npcId);
        recovery.cancel(ctx.npc());
        ctx.model().stopMoving();
    }

    @Override
    public String name() {
        return "range_practice";
    }
}
