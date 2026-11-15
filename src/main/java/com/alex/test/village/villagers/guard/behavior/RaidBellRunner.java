package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.raid.RaidManager;
import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A Bad Omen raid comes with no warning to the village (see
 * {@link RaidManager#needsWarningBell}). The first guard on duty to see its
 * raiders drops everything and runs to the village bell to ring it; one guard
 * per village at a time, the rest fight. A guard that is hit on the way fights
 * back first and then runs on. Any other monster, or a raid the village already
 * knows of, is fought as always: this only ever runs while that bell is unrung.
 */
final class RaidBellRunner {

    /** Village id -> the guard running to its bell, and since when. */
    private static final Map<UUID, Claim> RINGERS = new HashMap<>();
    /** Village id -> the bell found by its Town Hall, and when it was looked for. */
    private static final Map<UUID, FoundBell> BELLS = new HashMap<>();

    private record Claim(UUID npcId, long since) {}
    private record FoundBell(BlockPos pos, long at) {}

    /** A run this long (1 min) without ringing frees the job for another guard. */
    private static final int CLAIM_STALE_TICKS = 60 * 20;
    /** This guard gives up a run after 45 s. */
    private static final int GIVE_UP_TICKS = 45 * 20;
    /** And then leaves the job to others for 20 s. */
    private static final int FAILED_COOLDOWN_TICKS = 20 * 20;
    private static final int SPOT_CHECK_TICKS = 10;
    /** A guard sees raiders this far off (when nothing is in the way). */
    private static final double SPOT_RADIUS = 24.0;
    /** Arm's reach to the bell, from the eyes. */
    private static final double REACH_SQ = 3.0 * 3.0;
    /** Rung from where the walk ended when this close. */
    private static final double END_REACH_SQ = 4.5 * 4.5;
    /** How far around the village centre a real bell block is looked for. */
    private static final int BELL_SEARCH_RADIUS = 6;
    private static final int BELL_RECHECK_TICKS = 30 * 20;

    @Nullable private MoveActivity move;
    @Nullable private BlockPos bell;
    private int ticks;
    private int spotCheck;
    private long failedUntil;

    boolean isRunning() {
        return move != null;
    }

    /**
     * Runs the bell job for this tick. True while the guard is on its way to
     * the bell (it does nothing else this tick); false when there is no job
     * for it, and the guard goes about its fighting as usual.
     */
    boolean tick(Activity.Context ctx) {
        RaidManager raids = ctx.mgr().raids();
        if (!raids.needsWarningBell()) {
            stop(ctx);
            return false;
        }
        NpcEntity npc = ctx.npc();
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        UUID villageId = ctx.mgr().getVillageId();

        if (move == null) {
            if (now < failedUntil) return false;
            if (--spotCheck > 0) return false;
            spotCheck = SPOT_CHECK_TICKS;
            Claim claim = RINGERS.get(villageId);
            boolean mine = claim != null && claim.npcId().equals(npc.getNpcId());
            if (claim != null && !mine && now - claim.since() < CLAIM_STALE_TICKS && holderAlive(ctx, claim)) {
                return false;
            }
            // A guard already on the job (hit on the way, now free again) runs on without looking again.
            if (!mine && !seesRaider(npc, level, raids)) return false;
            bell = findBell(ctx.mgr(), level);
            move = new MoveActivity(standingSpot(ctx.mgr(), bell), 0.75f);
            ticks = 0;
            if (!mine) {
                RINGERS.put(villageId, new Claim(npc.getNpcId(), now));
                npc.showBubble(ThoughtIcon.EMOTE_WARNING, true, 40);
                ctx.brain().diary().log(VillagerDiary.ts(level) + " GUARD saw raiders: running to ring the bell");
            }
        }

        if (++ticks > GIVE_UP_TICKS) {
            fail(ctx, now, "could not get to the bell");
            return false;
        }
        Vec3 bellCentre = Vec3.atCenterOf(bell);
        if (npc.getEyePosition().distanceToSqr(bellCentre) <= REACH_SQ) {
            ring(ctx, bellCentre);
            return true;
        }
        Activity.Status status = move.tickOuter(ctx);
        if (status == Activity.Status.RUNNING) {
            npc.hurry(GuardActivity.runGait(npc));
            return true;
        }
        // The walk ended: ring it if it is near enough, else leave it to someone else.
        if (npc.getEyePosition().distanceToSqr(bellCentre) <= END_REACH_SQ) {
            ring(ctx, bellCentre);
            return true;
        }
        fail(ctx, now, "the walk to the bell failed");
        return false;
    }

    /** Hit on the way: the guard fights back, keeping the job, and runs on afterwards. */
    void interrupt(Activity.Context ctx) {
        if (move == null) return;
        move.stop(ctx);
        move = null;
        ctx.npc().stopHurry();
    }

    /** The guard leaves its post or the bell no longer needs ringing: the job is free. */
    void stop(Activity.Context ctx) {
        interrupt(ctx);
        release(ctx);
    }

    private void ring(Activity.Context ctx, Vec3 bellCentre) {
        NpcEntity npc = ctx.npc();
        ServerLevel level = ctx.level();
        npc.getLookControl().setLookAt(bellCentre.x, bellCentre.y, bellCentre.z, 30f, 30f);
        npc.swing(InteractionHand.MAIN_HAND);
        if (level.getBlockState(bell).getBlock() instanceof BellBlock bellBlock) {
            bellBlock.attemptToRing(npc, level, bell, null);
        } else {
            // No bell block by the Town Hall: the guard rings the alarm with its voice and a borrowed clang.
            level.playSound(null, npc.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
        }
        GuardAlertSystem.soundAlarm(ctx.mgr(), level);
        VillageDamageAlerts.announceRaidBell(ctx.mgr(), level, npc);
        ctx.brain().diary().log(VillagerDiary.ts(level) + " GUARD rang the bell for the raid");
        stop(ctx);
    }

    private void fail(Activity.Context ctx, long now, String why) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " GUARD gave up ringing the bell: " + why);
        failedUntil = now + FAILED_COOLDOWN_TICKS;
        stop(ctx);
    }

    private void release(Activity.Context ctx) {
        Claim claim = RINGERS.get(ctx.mgr().getVillageId());
        if (claim != null && claim.npcId().equals(ctx.npc().getNpcId())) {
            RINGERS.remove(ctx.mgr().getVillageId());
        }
    }

    /** Whether the guard holding {@code claim} is still alive and about (killed on the way: another goes). */
    private static boolean holderAlive(Activity.Context ctx, Claim claim) {
        for (com.alex.test.village.VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity puppet = model.puppet();
            if (puppet != null && puppet.isAlive() && claim.npcId().equals(puppet.getNpcId())) return true;
        }
        return false;
    }

    private static boolean seesRaider(NpcEntity npc, ServerLevel level, RaidManager raids) {
        return !level.getEntitiesOfClass(Mob.class, npc.getBoundingBox().inflate(SPOT_RADIUS, 8.0, SPOT_RADIUS),
                mob -> mob.isAlive() && raids.isRaider(mob) && npc.hasLineOfSight(mob)).isEmpty();
    }

    /** A real bell block near the village centre (the Town Hall), or the centre itself. */
    private static BlockPos findBell(VillageManager manager, ServerLevel level) {
        long now = level.getGameTime();
        FoundBell found = BELLS.get(manager.getVillageId());
        if (found != null && now - found.at() < BELL_RECHECK_TICKS
                && level.getBlockState(found.pos()).getBlock() instanceof BellBlock) {
            return found.pos();
        }
        BlockPos centre = manager.getBellPos();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(centre.offset(-BELL_SEARCH_RADIUS, -BELL_SEARCH_RADIUS, -BELL_SEARCH_RADIUS),
                centre.offset(BELL_SEARCH_RADIUS, BELL_SEARCH_RADIUS, BELL_SEARCH_RADIUS))) {
            if (!(level.getBlockState(pos).getBlock() instanceof BellBlock)) continue;
            double d = pos.distSqr(centre);
            if (d < bestSq) {
                bestSq = d;
                best = pos.immutable();
            }
        }
        BlockPos bell = best != null ? best : centre;
        BELLS.put(manager.getVillageId(), new FoundBell(bell, now));
        return bell;
    }

    private static BlockPos standingSpot(VillageManager manager, BlockPos bell) {
        NavGraph graph = manager.getGraph();
        NavNode node = graph != null ? graph.nearest(bell, 10, 4) : null;
        return node != null ? node.feetPos() : bell;
    }
}
