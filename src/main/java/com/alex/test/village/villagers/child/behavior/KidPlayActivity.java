package com.alex.test.village.villagers.child.behavior;

import com.alex.test.animation.HurryGait;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.MineGround;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.teacher.PupilRecord;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.Random;

/**
 * Out of school, children play: chasing another child about, running round
 * by themselves, or standing and watching a villager at the job they like
 * best at school. One short game per leisure slice.
 */
public final class KidPlayActivity extends Activity {

    private static final int GAME_TICKS = 300;
    private static final double FRIEND_RANGE_SQ = 24.0 * 24.0;
    private static final double WATCH_RANGE_SQ = 24.0 * 24.0;
    private static final float RUN_SPEED = 0.65f;

    private enum Game { CHASE, WATCH, RUN_ABOUT }

    private Game game = Game.RUN_ABOUT;
    @Nullable private NpcEntity other;
    @Nullable private BlockPos spot;
    private int ticks;
    private int repath;
    private final Random random = new Random();

    /** Whether a child could play here now: another child or a worker to watch nearby. */
    public static boolean worthPlaying(Context ctx) {
        return ctx.npc().getRandom().nextFloat() < 0.6f;
    }

    @Override
    protected void start(Context ctx) {
        random.setSeed(ctx.npc().getRandom().nextLong());
        NpcEntity friend = nearest(ctx, Profession.CHILD, FRIEND_RANGE_SQ);
        Profession loves = PupilRecord.favourite(ctx.npc().getBrainData());
        NpcEntity worker = loves != null ? nearest(ctx, loves, WATCH_RANGE_SQ) : null;
        // Watched from up here only: a child who loved mining followed the Miner down its tunnel.
        if (worker != null && MineGround.isDownAMine(ctx.mgr(), worker.blockPosition())) worker = null;
        int roll = random.nextInt(100);
        if (worker != null && roll < 30) {
            game = Game.WATCH;
            other = worker;
        } else if (friend != null && roll < 80) {
            game = Game.CHASE;
            other = friend;
            ctx.brain().showIcon(ThoughtIcon.EMOTE_LAUGHTER, 40, ctx.level());
        } else {
            game = Game.RUN_ABOUT;
        }
    }

    @Override
    protected Status tick(Context ctx) {
        if (++ticks > GAME_TICKS) return Status.DONE;
        switch (game) {
            case CHASE -> {
                if (other == null || !other.isAlive()) return Status.DONE;
                ctx.npc().hurry(HurryGait.RUN);
                boolean chasing = ticks < GAME_TICKS / 2;
                if (--repath <= 0 || !ctx.model().isMoving()) {
                    repath = 15;
                    BlockPos goal = chasing ? other.blockPosition()
                            : randomStrollSpot(ctx, ctx.npc().blockPosition(), 8, 2, random);
                    if (goal == null) return Status.DONE;
                    ctx.model().requestMoveTo(ctx.mgr().getGraph(), goal, RUN_SPEED);
                }
            }
            case WATCH -> {
                if (other == null || !other.isAlive()) return Status.DONE;
                if (ctx.npc().distanceToSqr(other) > 16.0) {
                    if (--repath <= 0 || !ctx.model().isMoving()) {
                        repath = 40;
                        if (MineGround.isDownAMine(ctx.mgr(), other.blockPosition())) return Status.DONE;
                        ctx.model().requestMoveTo(ctx.mgr().getGraph(), other.blockPosition(), VillagerModel.WALK_SPEED);
                    }
                } else {
                    ctx.model().stopMoving();
                    ctx.npc().getLookControl().setLookAt(other, 20f, 20f);
                    if (ticks % 100 == 50 && random.nextInt(3) == 0) {
                        ctx.brain().showIcon(ThoughtIcon.EMOTE_ADMIRATION, 40, ctx.level());
                    }
                }
            }
            case RUN_ABOUT -> {
                if (spot == null || !ctx.model().isMoving()) {
                    if (spot != null && random.nextInt(3) == 0
                            && ctx.npc().getAnimation() != VillagerAnimationState.DANCE) {
                        ctx.npc().setAnimation(VillagerAnimationState.DANCE);
                        spot = null;
                        return Status.RUNNING;
                    }
                    if (ctx.npc().getAnimation() == VillagerAnimationState.DANCE && ticks % 60 != 0) {
                        return Status.RUNNING;
                    }
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    spot = randomStrollSpot(ctx, ctx.npc().blockPosition(), 10, 2, random);
                    if (spot == null) return Status.DONE;
                    ctx.model().requestMoveTo(ctx.mgr().getGraph(), spot, RUN_SPEED);
                }
                ctx.npc().hurry(HurryGait.JOG);
            }
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        ctx.model().stopMoving();
        ctx.npc().stopHurry();
        if (ctx.npc().getAnimation() == VillagerAnimationState.DANCE) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
    }

    @Override
    public String name() {
        return "play";
    }

    @Nullable
    private static NpcEntity nearest(Context ctx, Profession profession, double rangeSq) {
        NpcEntity best = null;
        double bestDist = rangeSq;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            if (m.brain().profession() != profession) continue;
            NpcEntity npc = m.puppet();
            if (npc == null || npc == ctx.npc() || !npc.isAlive() || npc.isSleeping()) continue;
            double d = npc.distanceToSqr(ctx.npc());
            if (d < bestDist) {
                bestDist = d;
                best = npc;
            }
        }
        return best;
    }
}
