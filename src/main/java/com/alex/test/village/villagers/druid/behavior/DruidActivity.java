package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.TickStagger;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.druid.settings.DruidSettingsSchema;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.lumberjack.behavior.TreeApproachPlanner;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.MineReservations;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;

/**
 * The Druid's working day: magic, in three duties, in this order -- push the
 * village's crops along, grow its saplings into trees, and grow worked-out
 * mines back into rock.
 *
 * <p>Every duty is done in the same unit, a <em>step</em> (one crop stage,
 * one sapling stage, one mine block), and a step costs as many dances as the
 * druid's level says, or for a sapling its band's time; see
 * {@link DruidBalance}. Each duty has its own
 * allowance per work session, so one continuous stint gets round all three
 * instead of spending the whole day on whichever comes first. The druid no
 * longer plants trees of its own: it only grows what the lumberjack, or the
 * player, planted.
 *
 * <p>Every target is claimed, so two druids never pick the same crop, sapling
 * or mine. Nothing used to be, and three druids walked to the same tile at the
 * edge of town and stood inside each other there.
 *
 * <p>A mine is only regrown while nobody is digging it, and a miner that
 * turns up wanting it can ask for it back -- see {@link #requestHandoff}.
 */
public final class DruidActivity extends WorkActivity {

    /** Crop steps per work session -- an accelerant for the fields, not a replacement for growth. */
    private static final int MAX_CROP_STEPS_PER_SESSION = 60;
    /** Saplings grown into trees per work session. */
    private static final int MAX_TREES_PER_SESSION = 8;
    /*
     * Mine blocks have no per-session cap. Thirty-two used to end every visit
     * after a few minutes, and a miner dug those back out in about as long, so
     * the mine never filled. A visit now runs until the mine is full, and a
     * miner who turns up gets it back after the dance in progress (see
     * requestHandoff), so nobody is kept waiting by the missing cap.
     */

    /** Close enough to a sapling or mine spot to skip the walk and just step onto it. */
    private static final double SETTLE_NEAR_SQ = 2.25;
    /** Ticks allowed for the last step onto the exact spot. */
    private static final int SETTLE_MAX_TICKS = 40;
    private static final double SETTLE_DONE_SQ = 0.04;
    /** Walking out of a new canopy: close enough to a cell to take the next, and how long without progress before giving up. */
    private static final double EXIT_CELL_REACHED_SQ = 0.3 * 0.3;
    private static final int EXIT_STALL_TICKS = 40;
    private static final double EXIT_SPEED = VillagerModel.WALK_SPEED;
    /** Leaf crumbs for each leaf cut quietly on the way out (vanilla's own break shows up to 64). */
    private static final int LEAF_CRUMBS = 16;
    /** After growing a tree, the longest the druid waits for the village map to learn it: 3 s. */
    private static final int NEW_TREE_MAP_WAIT_TICKS = 60;
    /** How far round the new tree's base the map has to be up to date before the druid walks on. */
    private static final int NEW_TREE_MAP_RADIUS = 6;
    /** How often a sapling dance checks the sapling is still there (vanilla may grow it): once a second. */
    private static final int SAPLING_CHECK_TICKS = 20;

    /** A ready sapling vanilla could not grow is left alone this long: half a day. */
    private static final long BLOCKED_SAPLING_TICKS = 12_000L;
    /** After handing a mine to a miner, druids keep away from it this long. */
    private static final long YIELDED_MINE_TICKS = 2_400L;
    /**
     * Blocks a mine visit grows back before the druid hands the mine to a
     * miner who asked. A miner always asks now, and handing over at once made
     * the two take turns a block at a time: one block grown, the miner back
     * in, the block dug, the mine empty again. Sixteen is a minute or two of
     * dancing, well inside the miner's wait.
     */
    private static final int MIN_BLOCKS_BEFORE_HANDOFF = 16;
    /** How long to wait for a player or villager to leave the tunnel before trying elsewhere. */
    private static final int OCCUPIED_GIVE_UP_TICKS = 600;
    /** Thought-bubble cadence of the handover chat; the same as ordinary conversations. */
    private static final int HANDOFF_LINE_TICKS = 40;

    /** How long a druid's hold on a field lasts; renewed every crop step, so it only runs out if the druid is gone. */
    private static final long FIELD_HOLD_TICKS = 600L;
    /** Within this many blocks of another druid's work spot a target counts as crowded (compared squared). */
    private static final double CROWD_RADIUS = 10.0;
    private static final double CROWD_RADIUS_SQ = CROWD_RADIUS * CROWD_RADIUS;
    /** Added to a crowded target's distance, so any free target within about 64 blocks wins over it. */
    private static final double CROWD_PENALTY = 64.0 * 64.0;

    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;

    private enum Duty { CROP, SAPLING, MINE }

    /** Where the other druids are working, and what kind of job they are on; built once per choice. */
    private record Crowd(List<BlockPos> spots, int onCrop, int onOther) {
        boolean near(BlockPos pos) {
            for (BlockPos spot : spots) {
                if (spot.distSqr(pos) <= CROWD_RADIUS_SQ) return true;
            }
            return false;
        }

        double score(BlockPos pos, BlockPos from) {
            return pos.distSqr(from) + (near(pos) ? CROWD_PENALTY : 0.0);
        }
    }

    /** The nearest crop found, and a field passed over because another druid holds it. */
    private record CropPick(@Nullable BlockPos tile, @Nullable UUID farm, @Nullable UUID skippedField) {}

    @Nullable private Duty duty;
    /** What the druid was last doing; read by other druids in the one tick between two steps, when {@link #duty} is empty. */
    @Nullable private Duty lastDuty;
    /** The field this druid holds and keeps taking crops from; null when it holds none. */
    @Nullable private UUID fieldId;
    /** A held field the last crop search passed over; logged once if the druid goes elsewhere. */
    @Nullable private UUID skippedField;
    private boolean leftFieldLogged;
    /** The claim held for the current job: crop tile, sapling corner or mine mouth. */
    @Nullable private BlockPos claimed;
    @Nullable private BlockPos cropTile;
    @Nullable private DruidSaplings.Target sapling;
    @Nullable private MineBuilding mine;
    @Nullable private MineShaftPlan plan;
    private long minePlanRevision = -1;
    @Nullable private MineReservations.Border minePlanBorder;
    /** Mine cells still to grow back on this visit, deepest first. */
    private final Deque<BlockPos> mineBlocks = new ArrayDeque<>();
    @Nullable private BlockPos stand;
    @Nullable private Vec3 lookTarget;
    @Nullable private MoveActivity move;
    @Nullable private Activity sub;

    private boolean settling;
    private int settleTicks;
    /** Cells out from under a tree the druid just grew, walked before anything else; null when not leaving one. */
    @Nullable private List<BlockPos> exitPath;
    private int exitIndex;
    private int exitStallTicks;
    private double exitBestDistSq;
    /** The tree just grown, while the druid waits for the village map to learn it; null when not waiting. */
    @Nullable private BlockPos newTree;
    @Nullable private Vec3 newTreeLook;
    private long newTreeWaitUntil;
    private boolean newTreeIdle;
    /**
     * The first half of a tree is done and the second has not started: the
     * druid is not called away here, so a tree it started always stands.
     */
    private boolean betweenHalves;
    /** This crop or mine step's finish sound has played (led before the step ends); reset each step. */
    private boolean finishCued;
    /** Ticks left in the current step's dances; above zero the druid is mid-step. */
    private int danceTimer;
    /** The current step's full length in ticks, for paying the dances of a step cut short. */
    private int stepTicks;
    private int stepDances;
    private int waitTicks;

    private int cropSteps;
    private int trees;
    private int visitBlocks;
    private int visitTorches;
    private boolean workedThisSession;
    private final TickStagger.RetryGate chooseGate = new TickStagger.RetryGate();

    /** Targets that failed this session, by claim key; skipped rather than re-chosen. */
    private final Set<BlockPos> unreachable = new HashSet<>();

    /** A miner that asked for the mine; handed over when the current dance ends. */
    @Nullable private NpcEntity handoffMiner;

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        if (!settings(ctx).getBool(DruidSettingsSchema.WORK_ENABLED)) return Status.DONE;

        if (handoffMiner != null && danceTimer <= 0
                && (duty != Duty.MINE || visitBlocks >= MIN_BLOCKS_BEFORE_HANDOFF)) {
            return handOver(ctx);
        }

        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            sub = null;
            return workedThisSession ? Status.DONE : Status.FAILED;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                diary(ctx, "DRUID could not reach " + describe());
                if (claimed != null) unreachable.add(claimed);
                endJob(ctx);
                return Status.RUNNING;
            }
            arrive(ctx);
            return Status.RUNNING;
        }

        if (exitPath != null) {
            tickExit(ctx);
            return Status.RUNNING;
        }
        if (newTree != null && tickNewTreeWait(ctx)) return Status.RUNNING;
        if (settling) {
            tickSettle(ctx);
            return Status.RUNNING;
        }
        if (danceTimer > 0) return tickDance(ctx);
        if (duty != null) {
            // Between steps of one job, or waiting at a mine for the tunnel to clear.
            beginStep(ctx);
            return Status.RUNNING;
        }
        return chooseNext(ctx);
    }

    @Override
    protected void doStop(Context ctx) {
        if (move != null) { move.stop(ctx); move = null; }
        if (sub != null) { sub.stop(ctx); sub = null; }
        handoffMiner = null;
        if (exitPath != null) haltExit(ctx);
        newTree = null;
        newTreeLook = null;
        endJob(ctx);
        releaseField(ctx);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    @Override
    public boolean allowsRoutineInterruption() {
        // Never mid-step, and never inside a canopy: a walk home from in
        // there plans through leaves the graph does not know about yet.
        // Nor while waiting for the map to learn a new tree, for the same reason.
        // Nor halfway through a tree: called away there, it left a half-grown
        // sapling behind and the tree's finish sound never came.
        return danceTimer <= 0 && exitPath == null && newTree == null && !betweenHalves;
    }

    /**
     * Mid-dance. A player opening the druid's screen does not cut the magic
     * short: the brain lets the step finish, and in the tick {@link #tickDance}
     * leaves between steps it stops the druid and turns it to the player.
     */
    public boolean isPerformingMagic() {
        return danceTimer > 0;
    }

    /**
     * On a mine job, from the walk there until it ends. An open screen does
     * not stop the druid between steps here; it turns to the player once the
     * job is over.
     */
    public boolean isRegrowingMine() {
        return duty == Duty.MINE;
    }

    // =========================================================================
    // Miner handoff
    // =========================================================================

    /** Whether this druid is growing back the mine whose mouth is {@code mouth}. */
    public boolean isRegrowing(BlockPos mouth) {
        return duty == Duty.MINE && mine != null && mine.mouth().equals(mouth);
    }

    /**
     * A miner has come to ask for the mine. The druid finishes the dance it is
     * in, the two have a short chat -- mining, tools, mining -- and the druid
     * leaves the mine to it.
     *
     * @return false when this druid is not working a mine any more
     */
    public boolean requestHandoff(NpcEntity miner) {
        if (duty != Duty.MINE || mine == null) return false;
        handoffMiner = miner;
        return true;
    }

    private Status handOver(Context ctx) {
        NpcEntity miner = handoffMiner;
        handoffMiner = null;
        if (duty != Duty.MINE || mine == null || miner == null || !miner.isAlive()
                || miner.getNpcId() == null) {
            return Status.RUNNING;
        }
        VillagerModel minerModel = ctx.mgr().getModel(miner.getNpcId());
        if (minerModel == null) return Status.RUNNING;

        BlockPos mouth = mine.mouth();
        // Kept off it for a while, or the druid could walk straight back in
        // and claim it again before the miner's brain gets round to working.
        DruidVillageMemory.of(ctx.mgr()).yieldMine(mouth,
                ctx.level().getGameTime() + YIELDED_MINE_TICKS);
        diary(ctx, "DRUID handed the mine at " + mouth.toShortString() + " to a miner");
        endJob(ctx);
        ctx.model().stopMoving();
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);

        ConversationScript script = new ConversationScript(List.of(
                new ConversationScript.Line(true, ThoughtIcon.TOPIC_MINING),
                new ConversationScript.Line(false, ThoughtIcon.TOPIC_TOOLS),
                new ConversationScript.Line(true, ThoughtIcon.TOPIC_MINING)),
                HANDOFF_LINE_TICKS);
        int duration = script.durationTicks();
        // A work chat, not a social one: it does not use up either villager's
        // conversations for the day.
        minerModel.brain().forceActivity(
                new ConverseActivity(ctx.npc(), true, duration, true, false, script));
        ctx.brain().forceActivity(
                new ConverseActivity(miner, false, duration, true, false, script));
        return Status.RUNNING;
    }

    // =========================================================================
    // Choosing the next job
    // =========================================================================

    private Status chooseNext(Context ctx) {
        if (!chooseGate.tryPass(ctx.level().getGameTime(), ctx.npc().getId())) return Status.RUNNING;
        ProfessionSettings settings = settings(ctx);
        boolean mines = settings.getBool(DruidSettingsSchema.REGROW_MINES_ENABLED);
        // A mine dug to its end comes first: its miner has nothing to do until
        // it grows back, and the gap while the miner is away is the druid's
        // only chance at it. Other mines wait their turn after crops and saplings.
        if (mines && startMineJob(ctx, true)) return started(ctx, "a mine");
        boolean cropsOn = settings.getBool(DruidSettingsSchema.TEND_CROPS_ENABLED)
                && cropSteps < MAX_CROP_STEPS_PER_SESSION;
        boolean saplingsOn = settings.getBool(DruidSettingsSchema.GROW_SAPLINGS_ENABLED)
                && trees < MAX_TREES_PER_SESSION;

        // Built once for this choice, not per tile.
        Crowd crowd = otherDruids(ctx);
        // Crops are the default. If the others are all on crops, this druid
        // takes saplings and mines first, so the work is split by kind too.
        boolean cropsFirst = !(crowd.onCrop() > 0 && crowd.onOther() == 0);

        if (cropsFirst && cropsOn && startCropJob(ctx, crowd, false)) return started(ctx, "crops");
        if (saplingsOn && startSaplingJob(ctx, crowd)) return started(ctx, "saplings");
        if (mines && startMineJob(ctx, false)) return started(ctx, "a mine");
        if (!cropsFirst && cropsOn && startCropJob(ctx, crowd, false)) return started(ctx, "crops");
        // Every other kind of work is done or off: crops in a field another
        // druid holds are better than standing idle. The crowd penalty still
        // keeps this druid away from where that druid works.
        if (cropsOn && startCropJob(ctx, crowd, true)) return started(ctx, "crops");
        releaseField(ctx);
        return finishSession(ctx);
    }

    /** A job has just begun: say so once if it meant leaving a held field, and let go of the field if the job is not a crop. */
    private Status started(Context ctx, String what) {
        if (duty != Duty.CROP) releaseField(ctx);
        if (skippedField != null && !leftFieldLogged) {
            leftFieldLogged = true;
            diary(ctx, "DRUID left field " + skippedField.toString().substring(0, 8) + " to " + what);
        }
        skippedField = null;
        return Status.RUNNING;
    }

    /**
     * Where every other druid in the village is working right now, and what
     * they are doing. A druid between jobs counts as standing where it is.
     */
    private Crowd otherDruids(Context ctx) {
        List<BlockPos> spots = new ArrayList<>();
        int onCrop = 0;
        int onOther = 0;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            if (model.npcId.equals(ctx.model().npcId)) continue;
            if (!(model.brain().currentActivity() instanceof DruidActivity other)) continue;
            BlockPos spot = other.workSpot();
            if (spot == null) {
                NpcEntity puppet = model.puppet();
                if (puppet == null || !puppet.isAlive()) continue;
                spot = puppet.blockPosition();
            }
            spots.add(spot);
            Duty kind = other.currentDuty();
            if (kind == Duty.CROP) onCrop++;
            else if (kind != null) onOther++;
        }
        return new Crowd(spots, onCrop, onOther);
    }

    /** Where this druid is working, for the others to keep their distance; null when between jobs. */
    @Nullable
    BlockPos workSpot() {
        if (stand != null) return stand;
        if (cropTile != null) return cropTile;
        return sapling != null ? sapling.base() : null;
    }

    @Nullable
    private Duty currentDuty() {
        return duty != null ? duty : lastDuty;
    }

    private void releaseField(Context ctx) {
        if (fieldId == null) return;
        DruidVillageMemory.of(ctx.mgr()).releaseFields(ctx.model().npcId);
        fieldId = null;
    }

    /** Takes or renews the hold on {@code farm}, letting go of the field held before it. */
    private void holdField(Context ctx, UUID farm) {
        DruidVillageMemory memory = DruidVillageMemory.of(ctx.mgr());
        long now = ctx.level().getGameTime();
        UUID holder = memory.fieldHolder(farm, now);
        if (holder != null && !holder.equals(ctx.model().npcId)) return;
        if (fieldId != null && !fieldId.equals(farm)) memory.releaseFields(ctx.model().npcId);
        memory.holdField(farm, ctx.model().npcId, now + FIELD_HOLD_TICKS);
        fieldId = farm;
    }

    private Status finishSession(Context ctx) {
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        // Torches taken out of a mine go to the storage room, where the
        // miner restocks from.
        if (carriesTorches(ctx) && DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    /** @param joinHeld also take crops from a field another druid holds (the last resort) */
    private boolean startCropJob(Context ctx, Crowd crowd, boolean joinHeld) {
        for (int attempt = 0; attempt < 8; attempt++) {
            CropPick pick = nearestCrop(ctx, unreachable, crowd, fieldId, joinHeld);
            skippedField = pick.skippedField();
            BlockPos tile = pick.tile();
            if (tile == null) return false;
            BlockPos spot = cropStandFor(ctx, tile);
            if (spot == null) {
                diary(ctx, "DRUID no stand tile for crop at " + tile.toShortString());
                unreachable.add(tile);
                continue;
            }
            if (!claim(ctx, tile)) {
                unreachable.add(tile);
                continue;
            }
            duty = Duty.CROP;
            lastDuty = duty;
            // The whole field is this druid's while it works it; moving to a
            // different one lets go of the old one first.
            if (pick.farm() != null) {
                if (fieldId != null && !fieldId.equals(pick.farm())) releaseField(ctx);
                holdField(ctx, pick.farm());
            }
            cropTile = tile;
            stand = spot;
            // A crop occupies the druid's feet cell. Looking at its centre
            // would aim the head straight down and can make the dance twitch.
            lookTarget = null;
            goToStand(ctx);
            return true;
        }
        return false;
    }

    private boolean startSaplingJob(Context ctx, Crowd crowd) {
        DruidVillageMemory memory = DruidVillageMemory.of(ctx.mgr());
        long now = ctx.level().getGameTime();
        BlockPos npcPos = ctx.npc().blockPosition();
        Set<BlockPos> seen = new HashSet<>();
        // Nearest first, but a sapling next to another druid comes after any free one.
        List<BlockPos> saplings = memory.saplings(ctx).stream()
                .sorted(Comparator.comparingDouble(p -> crowd.score(p, npcPos)))
                .toList();
        for (BlockPos pos : saplings) {
            DruidSaplings.Target target = DruidSaplings.targetAt(ctx.level(), pos);
            if (target == null || !seen.add(target.base())) continue;
            BlockPos key = target.base();
            if (unreachable.contains(key) || memory.isSaplingBlocked(key, now)
                    || ctx.mgr().getClaims().isClaimedByOther(key, ctx.model().npcId)) {
                continue;
            }
            BlockPos spot = saplingStandFor(ctx, target);
            if (spot == null) {
                unreachable.add(key);
                continue;
            }
            if (!claim(ctx, key)) continue;
            duty = Duty.SAPLING;
            lastDuty = duty;
            sapling = target;
            stand = spot;
            lookTarget = target.centre();
            goToStand(ctx);
            return true;
        }
        return false;
    }

    /** @param workedOutOnly only a mine whose miner has dug all of it */
    private boolean startMineJob(Context ctx, boolean workedOutOnly) {
        DruidVillageMemory memory = DruidVillageMemory.of(ctx.mgr());
        long now = ctx.level().getGameTime();
        MineBuilding best = null;
        MineShaftPlan bestPlan = null;
        MineRegrowth.Scan bestScan = null;
        MineReservations.Snapshot reservations = MineReservations.snapshot(ctx.level());
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof MineBuilding candidate) || !candidate.isValid()) continue;
            BlockPos mouth = candidate.mouth();
            if (unreachable.contains(mouth) || memory.isMineYielded(mouth, now)
                    || ctx.mgr().getClaims().isClaimedByOther(mouth, ctx.model().npcId)
                    || ctx.mgr().mineShape(candidate.getItemFrameId()).paused()) {
                continue;
            }
            MineShaftPlan candidatePlan = MineReservations.planFor(candidate, reservations, ctx.mgr());
            MineRegrowth.Scan scan = MineRegrowth.scan(ctx, candidatePlan);
            if (scan.blocks().isEmpty()) continue;
            if (workedOutOnly && !scan.workedOut()) continue;
            // Worked-out mines first: their miner has nothing to do until they
            // come back. Then whichever has the most tunnel to grow back.
            if (bestScan == null
                    || (scan.workedOut() && !bestScan.workedOut())
                    || (scan.workedOut() == bestScan.workedOut()
                        && scan.blocks().size() > bestScan.blocks().size())) {
                best = candidate;
                bestPlan = candidatePlan;
                bestScan = scan;
            }
        }
        if (best == null) return false;

        BlockPos spot = MineRegrowth.standSpot(ctx, best);
        if (spot == null || !claim(ctx, best.mouth())) {
            unreachable.add(best.mouth());
            return false;
        }
        duty = Duty.MINE;
        lastDuty = duty;
        mine = best;
        plan = bestPlan;
        minePlanRevision = reservations.revision();
        minePlanBorder = MineReservations.Border.of(ctx.mgr());
        mineBlocks.clear();
        mineBlocks.addAll(bestScan.blocks());
        visitBlocks = 0;
        visitTorches = 0;
        stand = spot;
        BlockPos mouth = best.mouth();
        lookTarget = new Vec3(mouth.getX() + 0.5, mouth.getY() + 1.0, mouth.getZ() + 0.5);
        goToStand(ctx);
        return true;
    }

    /** Walks to {@link #stand}, or steps straight onto it when it is right here. */
    private void goToStand(Context ctx) {
        if (stand == null) return;
        NpcEntity npc = ctx.npc();
        double dx = stand.getX() + 0.5 - npc.getX();
        double dz = stand.getZ() + 0.5 - npc.getZ();
        if (duty == Duty.CROP && NavGraph.feetCell(npc).equals(stand)
                && dx * dx + dz * dz <= SETTLE_DONE_SQ) {
            // Often this is the next growth step on the same crop. Keep the
            // dance controller running through the between-step pause.
            beginStep(ctx);
            return;
        }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (ctx.npc().blockPosition().distSqr(stand) <= SETTLE_NEAR_SQ) {
            startSettle(ctx);
        } else {
            move = new MoveActivity(stand, WALK_SPEED, true, true);
        }
    }

    private void arrive(Context ctx) {
        if (duty != null) {
            startSettle(ctx);
        }
    }

    private void startSettle(Context ctx) {
        settling = true;
        settleTicks = 0;
        ctx.model().stopMoving();
    }

    /**
     * The last step onto the exact tile. A sapling spot may be full of leaves,
     * which the nav graph treats as solid, so the walk there ends a block
     * short; the leaves are cut as the druid steps in, the way the
     * lumberjack cuts its way to a trunk.
     */
    private void tickSettle(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (stand == null) {
            settling = false;
            return;
        }
        if (duty == Duty.SAPLING) clearLeavesAt(ctx, stand, null);
        double x = stand.getX() + 0.5;
        double z = stand.getZ() + 0.5;
        double dx = x - npc.getX();
        double dz = z - npc.getZ();
        boolean centred = dx * dx + dz * dz <= SETTLE_DONE_SQ;
        boolean timedOut = !centred && ++settleTicks > SETTLE_MAX_TICKS;
        if (centred || timedOut) {
            npc.getNavigation().stop();
            npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0);
            settling = false;
            // A crop dance must actually happen on the crop. If the last
            // step was blocked, leave this tile for another visit.
            if (duty == Duty.CROP && (timedOut || !NavGraph.feetCell(npc).equals(stand))) {
                if (claimed != null) unreachable.add(claimed);
                endJob(ctx);
                return;
            }
            beginStep(ctx);
            return;
        }
        npc.getMoveControl().setWantedPosition(x, stand.getY(), z, 0.5);
    }

    // =========================================================================
    // Steps
    // =========================================================================

    /** Starts the next step of the current job, or ends the job when there is none. */
    private void beginStep(Context ctx) {
        if (duty == null) return;
        betweenHalves = false;
        finishCued = false;
        int level = ctx.brain().druidSkillLevel();
        int ticks = DruidBalance.dancesPerStep(level) * DruidBalance.DANCE_TICKS;
        switch (duty) {
            case CROP -> {
                if (!isGrowableCrop(ctx, cropTile)) { endJob(ctx); return; }
                // Renewed each step, so the hold only lapses if the druid is gone.
                if (fieldId != null) holdField(ctx, fieldId);
            }
            case SAPLING -> {
                if (sapling == null || !DruidSaplings.stillThere(ctx.level(), sapling)) {
                    endJob(ctx);
                    return;
                }
                // A tree takes its band's time, not a count of dances.
                ticks = DruidBalance.saplingStageTicks(level, sapling.twoByTwo());
            }
            case MINE -> {
                if (!readyForNextBlock(ctx)) return;
            }
        }
        // Clients hum from here. A tree's stays on through the one-tick gap
        // between its two stages, so the hum carries straight on; a crop's and
        // a mine's go off for that tick (endJob), which only dips the hum.
        ctx.npc().setDruidCast(switch (duty) {
            case CROP -> NpcEntity.DruidCast.CROPS;
            case SAPLING -> NpcEntity.DruidCast.TREE;
            case MINE -> NpcEntity.DruidCast.MINE;
        });
        // Experience is per dance, so time spent on a tree pays the same
        // per second as time spent on crops or mines.
        stepDances = Math.max(1, Math.round(ticks / (float) DruidBalance.DANCE_TICKS));
        stepTicks = ticks;
        danceTimer = ticks;
        ctx.npc().setAnimation(VillagerAnimationState.DANCE);
        lookAtTarget(ctx);
    }

    /**
     * Whether the mine visit can go on to another block. Waits, standing idle,
     * while a player or villager is inside the tunnel.
     */
    private boolean readyForNextBlock(Context ctx) {
        if (mine == null || plan == null || !mine.isValid()) {
            endJob(ctx);
            return false;
        }
        if (!refreshMinePlan(ctx)) return false;
        if (MineRegrowth.someoneInside(ctx, plan)) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            lookAtTarget(ctx);
            if (++waitTicks > OCCUPIED_GIVE_UP_TICKS) {
                diary(ctx, "DRUID gave up waiting for the mine at "
                        + mine.mouth().toShortString() + " to empty");
                unreachable.add(mine.mouth());
                endJob(ctx);
            }
            return false;
        }
        waitTicks = 0;
        while (!mineBlocks.isEmpty()
                && !MineRegrowth.canRegrow(ctx.level(), mineBlocks.peekFirst())) {
            mineBlocks.pollFirst();
        }
        if (mineBlocks.isEmpty()) {
            endJob(ctx);
            return false;
        }
        return true;
    }

    /**
     * Re-read only when the registered mine layout or the map edge changes. A
     * change to a mine's settings or the village's side length bumps the
     * layout revision (VillageManager.onMineLayoutChanged).
     *
     * @return false when the player has paused the mine: the job is over
     */
    private boolean refreshMinePlan(Context ctx) {
        if (mine == null) return false;
        if (ctx.mgr().mineShape(mine.getItemFrameId()).paused()) {
            diary(ctx, "DRUID the mine at " + mine.mouth().toShortString()
                    + " was paused by the player, leaving");
            endJob(ctx);
            return false;
        }
        MineReservations.Snapshot reservations = MineReservations.snapshot(ctx.level());
        MineReservations.Border border = MineReservations.Border.of(ctx.mgr());
        if (reservations.revision() == minePlanRevision
                && java.util.Objects.equals(border, minePlanBorder)) return true;
        plan = MineReservations.planFor(mine, reservations, ctx.mgr());
        minePlanRevision = reservations.revision();
        minePlanBorder = border;
        mineBlocks.clear();
        mineBlocks.addAll(MineRegrowth.scan(ctx, plan).blocks());
        return true;
    }

    private Status tickDance(Context ctx) {
        danceTimer--;
        // Held every tick: setting the state it already has costs nothing, and
        // holding it stops NpcEntity's 60-tick auto-return from dropping the
        // druid to IDLE part-way through a longer bout.
        ctx.npc().setAnimation(VillagerAnimationState.DANCE);
        lookAtTarget(ctx);
        danceEffects(ctx);
        // Vanilla can grow the sapling itself (a random tick). Noticed within
        // a second, that ends the dance the way a tree the druid grew does,
        // rather than dancing on beside the tree until the time is up.
        if (duty == Duty.SAPLING && sapling != null && danceTimer > 0
                && danceTimer % SAPLING_CHECK_TICKS == 0
                && !DruidSaplings.stillThere(ctx.level(), sapling)) {
            saplingGoneMidDance(ctx);
            return Status.RUNNING;
        }
        // The finish sound leads the tree by a moment, so it lands as the tree
        // pops; the hum fades out under it.
        if (duty == Duty.SAPLING && sapling != null && danceTimer == DruidSounds.leadTicks()
                && DruidSaplings.nextStageMakesTree(ctx.level(), sapling)) {
            DruidSounds.treeFinishCue(ctx.level(), sapling.centre());
            ctx.npc().setDruidCast(NpcEntity.DruidCast.NONE);
        }
        // Crops: only the stage that makes the crop fully grown, and the hum
        // fades under it. Mines: every block, if the next one can still grow;
        // the hum carries on.
        if (duty == Duty.CROP && !finishCued && danceTimer == DruidSounds.cropLeadTicks()
                && nextStageRipens(ctx)) {
            cropFinishCue(ctx);
        }
        if (duty == Duty.MINE && !finishCued && danceTimer == DruidSounds.mineLeadTicks()
                && nextMineBlockCanGrow(ctx)) {
            mineFinishCue(ctx, null);
        }
        if (danceTimer > 0) return Status.RUNNING;

        int level = ctx.brain().druidSkillLevel();
        boolean twice = DruidBalance.rollDoubleStep(level, ctx.npc().getRandom());
        ctx.brain().onDruidDances(ctx, stepDances);
        workedThisSession = true;
        chooseGate.progressed();
        finishStep(ctx, level, twice);

        // Nothing new starts on this tick. Between steps the brain gets one
        // tick in which the druid is interruptible (except between the two
        // halves of a tree, see betweenHalves), and that is what lets
        // nightfall send it home and hunger send it to eat: starting the next
        // step here, in the same tick, kept it mid-step every time the brain
        // looked, and it worked the mine all night. The same gap lets an open
        // screen stop it and turn it to face the player. The next step or job
        // starts on the next tick; DANCE is left set, so a druid that carries
        // straight on does not restart its looping clip.
        return Status.RUNNING;
    }

    /** The stream of magic from the druid's hands into whatever it is working on. */
    private void danceEffects(Context ctx) {
        if (duty == null) return;
        Vec3 target = switch (duty) {
            case CROP -> cropTile == null ? null
                    : new Vec3(cropTile.getX() + 0.5, cropTile.getY() + 0.3, cropTile.getZ() + 0.5);
            case SAPLING -> sapling == null ? null : sapling.centre();
            // The mouth, not the block being grown: that is often deep in
            // the tunnel, and a stream through solid rock would look wrong.
            case MINE -> lookTarget;
        };
        if (target == null) return;
        DruidEffects.Kind kind = switch (duty) {
            case CROP -> DruidEffects.Kind.CROP;
            case SAPLING -> DruidEffects.Kind.SAPLING;
            case MINE -> DruidEffects.Kind.MINE;
        };
        DruidEffects.danceTick(ctx.level(), ctx.npc(), kind, target, ctx.brain().druidSkillLevel());
    }

    private void finishStep(Context ctx, int level, boolean twice) {
        switch (duty) {
            case CROP -> finishCropStep(ctx, twice);
            case SAPLING -> finishSaplingStep(ctx);
            case MINE -> finishMineStep(ctx, level, twice);
        }
    }

    /** Re-checked against the world: a whole walk and dance happened since the crop was chosen. */
    private void finishCropStep(Context ctx, boolean twice) {
        BlockPos tile = cropTile;
        BlockState state = tile == null ? null : ctx.level().getBlockState(tile);
        if (state != null && state.getBlock() instanceof CropBlock crop
                && crop.getAge(state) < crop.getMaxAge()) {
            int age = crop.getAge(state);
            int nextAge = Math.min(crop.getMaxAge(), age + (twice ? 2 : 1));
            ctx.level().setBlock(tile, crop.getStateForAge(nextAge), 3);
            // A master's double from two short of full had no warning: its sound now.
            if (nextAge == crop.getMaxAge() && !finishCued) cropFinishCue(ctx);
            // Only a real double gets the flourish, not one capped at full grown.
            DruidEffects.cropGrown(ctx.level(), tile, nextAge - age >= 2);
            cropSteps++;
            diary(ctx, "DRUID tended crop at " + tile.toShortString() + " -> age " + nextAge);
        }
        // Always re-choose: the nearest crop is usually this one again, and
        // choosing keeps a second druid from being locked out of a whole field.
        endJob(ctx);
    }

    /**
     * One of the sapling's two stages. There is no master double step here:
     * a tree takes exactly its band's time (see
     * {@link DruidBalance#saplingStageTicks}).
     */
    private void finishSaplingStep(Context ctx) {
        DruidSaplings.Target target = sapling;
        if (target == null) { endJob(ctx); return; }
        DruidSaplings.Growth growth = DruidSaplings.grow(ctx.level(), target, 1);
        Vec3 centre = target.centre();
        switch (growth) {
            case STAGED -> {
                DruidEffects.saplingStaged(ctx.level(), centre);
                betweenHalves = true;
            }
            case TREE -> treeStood(ctx, target, false);
            case BLOCKED -> {
                DruidVillageMemory.of(ctx.mgr()).blockSapling(target.base(),
                        ctx.level().getGameTime() + BLOCKED_SAPLING_TICKS);
                diary(ctx, "DRUID sapling at " + target.base().toShortString()
                        + " has no room to grow");
                endJob(ctx);
            }
            // Vanilla may have grown it in the last moments of the dance.
            case GONE -> {
                if (DruidSaplings.treeStandsAt(ctx.level(), target)) treeStood(ctx, target, true);
                else endJob(ctx);
            }
        }
    }

    /**
     * The sapling vanished mid-dance. The dances so far still count; if a
     * tree stands there now, vanilla grew it, and the druid ends as if it had.
     */
    private void saplingGoneMidDance(Context ctx) {
        DruidSaplings.Target target = sapling;
        int danced = Math.round((stepTicks - danceTimer) / (float) DruidBalance.DANCE_TICKS);
        if (danced > 0) {
            ctx.brain().onDruidDances(ctx, danced);
            workedThisSession = true;
            chooseGate.progressed();
        }
        if (target != null && DruidSaplings.treeStandsAt(ctx.level(), target)) {
            treeStood(ctx, target, true);
        } else {
            endJob(ctx);
        }
    }

    /**
     * A tree stands where the sapling was: its burst, then out from under it.
     * One vanilla grew came unannounced, so its finish sound plays now.
     */
    private void treeStood(Context ctx, DruidSaplings.Target target, boolean byItself) {
        Vec3 centre = target.centre();
        // Still humming means the cue has not played yet.
        if (ctx.npc().getDruidCast() == NpcEntity.DruidCast.TREE) DruidSounds.treeFinishCue(ctx.level(), centre);
        DruidEffects.treeGrown(ctx.level(), centre, target.twoByTwo());
        trees++;
        String kind = target.twoByTwo() ? "2x2 " : "";
        diary(ctx, byItself
                ? "DRUID " + kind + "sapling at " + target.base().toShortString() + " grew into a tree on its own"
                : "DRUID grew a " + kind + "tree at " + target.base().toShortString());
        endJob(ctx);
        beginExit(ctx);
        if (WalkFeel.newTree) {
            newTree = target.base();
            newTreeLook = centre.add(0.0, 1.5, 0.0);
            newTreeWaitUntil = ctx.level().getGameTime() + NEW_TREE_MAP_WAIT_TICKS;
            newTreeIdle = false;
        }
    }

    private void finishMineStep(Context ctx, int level, boolean twice) {
        if (plan == null) { endJob(ctx); return; }
        if (!refreshMinePlan(ctx)) return;
        if (mineBlocks.isEmpty()) { endJob(ctx); return; }
        int wanted = twice ? 2 : 1;
        int grown = 0;
        int kept = 0;
        while (grown < wanted && !mineBlocks.isEmpty()) {
            BlockPos pos = mineBlocks.pollFirst();
            if (!MineRegrowth.canRegrow(ctx.level(), pos)) continue;
            // Never grow rock into something standing there; the block waits
            // for another visit.
            if (MineRegrowth.hasEntityAt(ctx.level(), pos)) continue;
            kept += MineRegrowth.fill(ctx, pos, level);
            // One sound per step, even when two blocks grow. Normally led
            // in tickDance; here only when the lead was longer than the step.
            if (grown == 0 && !finishCued) mineFinishCue(ctx, pos);
            grown++;
        }
        // At the mouth, where it can be seen; the blocks themselves are often
        // deep in the tunnel behind rock.
        if (grown >= 2 && lookTarget != null) DruidEffects.doubleStep(ctx.level(), lookTarget);
        visitBlocks += grown;
        if (kept > 0) {
            visitTorches += kept;
            // Stamped with the village's ID, like the torches the miner makes.
            ItemStack torches = VillageItemOwnership.produce(
                    new ItemStack(Items.TORCH, kept), ctx.mgr());
            if (!ctx.npc().getInventory().addItem(torches)) ItemLossLog.drop(ctx, torches, "mine regrowth torches, pack full");
        }
    }

    /** The crop is one stage short of fully grown, so this step ripens it. */
    private boolean nextStageRipens(Context ctx) {
        if (cropTile == null) return false;
        BlockState state = ctx.level().getBlockState(cropTile);
        return state.getBlock() instanceof CropBlock crop && crop.getAge(state) == crop.getMaxAge() - 1;
    }

    private void cropFinishCue(Context ctx) {
        if (cropTile == null) return;
        finishCued = true;
        DruidSounds.cropFinishCue(ctx.level(), Vec3.atCenterOf(cropTile));
        ctx.npc().setDruidCast(NpcEntity.DruidCast.NONE);
    }

    /** A block of this visit would grow if the step ended now: the same test {@link #finishMineStep} makes. */
    private boolean nextMineBlockCanGrow(Context ctx) {
        for (BlockPos pos : mineBlocks) {
            if (MineRegrowth.canRegrow(ctx.level(), pos) && !MineRegrowth.hasEntityAt(ctx.level(), pos)) {
                return true;
            }
        }
        return false;
    }

    /** @param block the block grown, for a mine with no mouth to play from */
    private void mineFinishCue(Context ctx, @Nullable BlockPos block) {
        Vec3 at = lookTarget != null ? lookTarget : block != null ? Vec3.atCenterOf(block) : null;
        if (at == null) return;
        finishCued = true;
        DruidSounds.mineFinishCue(ctx.level(), at);
    }

    // =========================================================================
    // Jobs
    // =========================================================================

    /** Releases the job's claim and forgets it. Leaves the animation to the caller. */
    private void endJob(Context ctx) {
        if (duty == Duty.MINE && mine != null && visitBlocks > 0) {
            diary(ctx, "DRUID regrew " + visitBlocks + " block(s) of the mine at "
                    + mine.mouth().toShortString()
                    + (visitTorches > 0 ? ", kept " + visitTorches + " torch(es)" : ""));
        }
        if (claimed != null) {
            ctx.mgr().getClaims().release(claimed, ctx.model().npcId);
            claimed = null;
        }
        duty = null;
        cropTile = null;
        sapling = null;
        mine = null;
        plan = null;
        minePlanRevision = -1;
        minePlanBorder = null;
        mineBlocks.clear();
        visitBlocks = 0;
        visitTorches = 0;
        stand = null;
        lookTarget = null;
        settling = false;
        waitTicks = 0;
        danceTimer = 0;
        betweenHalves = false;
        finishCued = false;
        // Every way out of a job (done, blocked, gone, stopped) ends here.
        ctx.npc().setDruidCast(NpcEntity.DruidCast.NONE);
    }

    private boolean claim(Context ctx, BlockPos key) {
        if (!ctx.mgr().getClaims().claim(key, ctx.model().npcId)) return false;
        claimed = key.immutable();
        return true;
    }

    private void lookAtTarget(Context ctx) {
        if (lookTarget == null) return;
        ctx.npc().getLookControl().setLookAt(lookTarget.x, lookTarget.y, lookTarget.z, 30f, 30f);
    }

    // =========================================================================
    // Crops
    // =========================================================================

    /**
     * The best below-max-age crop in a discovered field. A field this druid
     * already holds comes first, so it finishes one field before leaving it;
     * a field another druid holds is passed over unless {@code joinHeld}.
     * Within those, nearest wins, with a penalty for being close to another
     * druid (see {@link Crowd}). {@code crowd} and {@code ownField} may be
     * null for a plain "is there any work" check.
     */
    private static CropPick nearestCrop(Context ctx, Set<BlockPos> skip, @Nullable Crowd crowd,
                                        @Nullable UUID ownField, boolean joinHeld) {
        BlockPos npcPos = ctx.npc().blockPosition();
        UUID me = ctx.model().npcId;
        DruidVillageMemory memory = DruidVillageMemory.of(ctx.mgr());
        long now = ctx.level().getGameTime();
        BlockPos bestOwn = null;
        double bestOwnScore = Double.MAX_VALUE;
        BlockPos bestOther = null;
        UUID bestOtherFarm = null;
        double bestOtherScore = Double.MAX_VALUE;
        UUID skipped = null;
        for (FarmerManager.FarmSnapshot farm : ctx.mgr().farmers().snapshots()) {
            UUID holder = memory.fieldHolder(farm.id(), now);
            boolean heldByOther = holder != null && !holder.equals(me);
            boolean own = !heldByOther && farm.id().equals(ownField);
            for (BlockPos tile : farm.planted()) {
                if (skip.contains(tile)) continue;
                if (!isGrowableCrop(ctx, tile)) continue;
                if (ctx.mgr().getClaims().isClaimedByOther(tile, me)) continue;
                if (heldByOther && !joinHeld) {
                    // There is work here, but it is another druid's.
                    skipped = farm.id();
                    break;
                }
                double score = crowd == null ? tile.distSqr(npcPos) : crowd.score(tile, npcPos);
                if (own) {
                    if (score < bestOwnScore) {
                        bestOwn = tile;
                        bestOwnScore = score;
                    }
                } else if (score < bestOtherScore) {
                    bestOther = tile;
                    bestOtherFarm = farm.id();
                    bestOtherScore = score;
                }
            }
        }
        return bestOwn != null ? new CropPick(bestOwn, ownField, skipped)
                : new CropPick(bestOther, bestOtherFarm, skipped);
    }

    private static boolean isGrowableCrop(Context ctx, @Nullable BlockPos tile) {
        if (tile == null) return false;
        BlockState state = ctx.level().getBlockState(tile);
        return state.getBlock() instanceof CropBlock crop && crop.getAge(state) < crop.getMaxAge();
    }

    @Nullable
    private static BlockPos cropStandFor(Context ctx, BlockPos tile) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        NavNode tileNode = graph.nodeAt(tile.getX(), tile.getY(), tile.getZ());
        return tileNode != null && tileNode.isRoutable() ? tile : null;
    }

    // =========================================================================
    // Saplings
    // =========================================================================

    /**
     * The tile touching the sapling (or the square) to stand on: a walkable
     * node if there is one, otherwise a tile that would be walkable once its
     * natural leaves are cut. Nearest to the druid wins within each kind.
     */
    @Nullable
    private static BlockPos saplingStandFor(Context ctx, DruidSaplings.Target target) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos npcPos = ctx.npc().blockPosition();
        BlockPos bestNode = null;
        BlockPos bestLeafy = null;
        double bestNodeDist = Double.MAX_VALUE;
        double bestLeafyDist = Double.MAX_VALUE;
        for (BlockPos side : target.sides()) {
            for (int dy : new int[] {0, -1, 1}) {
                BlockPos feet = side.above(dy);
                if (!ctx.level().isLoaded(feet)
                        || !TreeApproachPlanner.isWalkable(ctx, graph, feet)) {
                    continue;
                }
                double d = feet.distSqr(npcPos);
                if (graph.nodeAt(feet.getX(), feet.getY(), feet.getZ()) != null) {
                    if (d < bestNodeDist) { bestNodeDist = d; bestNode = feet; }
                } else if (d < bestLeafyDist) {
                    bestLeafyDist = d;
                    bestLeafy = feet;
                }
                break;
            }
        }
        return bestNode != null ? bestNode : bestLeafy;
    }

    /**
     * Out from under the tree just grown. The druid stands a block from the
     * trunk, so the new canopy comes down round its head, and the nav graph
     * only learns of those leaves after its rebuild debounce -- until then it
     * routes straight through them, and the next walk pressed the druid into
     * the leaves and left it stuck there. The whole way out is cut at once,
     * so even a druid called away part-way leaves by an open path, and then
     * walked a cell at a time, the way the lumberjack walks its way in.
     *
     * <p>The leaves go quietly, with one soft rustle for the lot: this runs on
     * the tick the tree appears, and vanilla's full crunch per leaf (often ten
     * or more at once) buried the finish sound (10-25).
     */
    private void beginExit(Context ctx) {
        NpcEntity npc = ctx.npc();
        List<SoundEvent> cut = new ArrayList<>();
        clearLeavesOnBody(ctx, cut);
        List<BlockPos> path = TreeApproachPlanner.wayOut(ctx, NavGraph.feetCell(npc));
        if (path.size() < 2) {
            leafRustle(ctx, cut);
            return;
        }
        for (int i = 0; i < path.size(); i++) {
            BlockPos cell = path.get(i);
            clearLeavesAt(ctx, cell, cut);
            if (i > 0 && path.get(i - 1).getY() != cell.getY()) {
                // Room over the lower cell for the head, going up or coming down.
                BlockPos prev = path.get(i - 1);
                cutLeaf(ctx, (prev.getY() < cell.getY() ? prev : cell).above(2), cut);
            }
        }
        leafRustle(ctx, cut);
        // A branch grown through the druid's body is wood, not leaves, and
        // is not cut; the druid is set down on the first cell of the way out.
        if (!ctx.level().noCollision(npc)) {
            BlockPos out = path.get(1);
            // Not into a chunk still loading (FarAwake): then it walks the way out instead.
            if (com.alex.test.village.farawake.FarAwake.mayPlace(ctx.level(), out.getX() + 0.5, out.getZ() + 0.5,
                    "druid set out of a branch")) {
                npc.teleportTo(out.getX() + 0.5, out.getY(), out.getZ() + 0.5);
            }
        }
        npc.setAnimation(VillagerAnimationState.IDLE);
        exitPath = path;
        exitIndex = 1;
        exitStallTicks = 0;
        exitBestDistSq = Double.MAX_VALUE;
    }

    private void tickExit(Context ctx) {
        NpcEntity npc = ctx.npc();
        List<BlockPos> path = exitPath;
        List<SoundEvent> cut = new ArrayList<>(0);
        clearLeavesOnBody(ctx, cut);
        while (exitIndex < path.size() && isOnCell(npc, path.get(exitIndex))) {
            exitIndex++;
            exitStallTicks = 0;
            exitBestDistSq = Double.MAX_VALUE;
        }
        if (exitIndex >= path.size()) {
            leafRustle(ctx, cut);
            haltExit(ctx);
            return;
        }
        BlockPos next = path.get(exitIndex);
        clearLeavesAt(ctx, next, cut);
        leafRustle(ctx, cut);
        npc.getMoveControl().setWantedPosition(
                next.getX() + 0.5, next.getY(), next.getZ() + 0.5, EXIT_SPEED);

        double dx = next.getX() + 0.5 - npc.getX();
        double dz = next.getZ() + 0.5 - npc.getZ();
        double dy = npc.getY() - next.getY();
        double dist = dx * dx + dz * dz + dy * dy;
        if (dist < exitBestDistSq - 0.01) {
            exitBestDistSq = dist;
            exitStallTicks = 0;
        } else if (++exitStallTicks > EXIT_STALL_TICKS) {
            // The way is open behind it either way; the next walk takes over.
            diary(ctx, "DRUID stalled walking out from under a tree at "
                    + npc.blockPosition().toShortString());
            haltExit(ctx);
        }
    }

    /**
     * Stands facing the tree just grown until the village map has re-read the
     * ground round it (about a second, {@code NavDirtyTracker.markSoon}), at
     * most {@link #NEW_TREE_MAP_WAIT_TICKS}. The next walk used to be planned
     * in the very next tick, on a map that still showed the sapling's tile as
     * open grass: it went straight through the new trunk and was cut there as
     * a stuck (10-18). Runs after the walk out from under the canopy.
     *
     * @return true while still waiting
     */
    private boolean tickNewTreeWait(Context ctx) {
        BlockPos tree = newTree;
        long now = ctx.level().getGameTime();
        boolean timedOut = now >= newTreeWaitUntil;
        if (tree == null || !WalkFeel.newTree || timedOut
                || !ctx.mgr().isNavCatchingUpNear(tree, NEW_TREE_MAP_RADIUS)) {
            if (tree != null) {
                long waited = now - (newTreeWaitUntil - NEW_TREE_MAP_WAIT_TICKS);
                diary(ctx, "DRUID waited " + waited + " ticks for the map to learn the tree at "
                        + tree.toShortString() + (timedOut ? "; walking on before it did" : ""));
            }
            newTree = null;
            newTreeLook = null;
            return false;
        }
        if (!newTreeIdle) {
            newTreeIdle = true;
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        Vec3 look = newTreeLook;
        if (look != null) ctx.npc().getLookControl().setLookAt(look.x, look.y, look.z, 30f, 30f);
        return true;
    }

    /** Ends the walk out: the move control otherwise keeps pushing towards its last cell. */
    private void haltExit(Context ctx) {
        exitPath = null;
        NpcEntity npc = ctx.npc();
        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0);
    }

    private static boolean isOnCell(NpcEntity npc, BlockPos cell) {
        double dx = cell.getX() + 0.5 - npc.getX();
        double dz = cell.getZ() + 0.5 - npc.getZ();
        return dx * dx + dz * dz <= EXIT_CELL_REACHED_SQ
                && Math.abs(npc.getY() - cell.getY()) <= 0.6;
    }

    private static void clearLeavesAt(Context ctx, BlockPos feet, @Nullable List<SoundEvent> quiet) {
        for (int dy = 0; dy <= 1; dy++) cutLeaf(ctx, feet.above(dy), quiet);
    }

    private static void clearLeavesOnBody(Context ctx, List<SoundEvent> quiet) {
        AABB box = ctx.npc().getBoundingBox().inflate(0.1, 0.0, 0.1);
        for (BlockPos pos : BlockPos.betweenClosed(
                (int) Math.floor(box.minX), (int) Math.floor(box.minY), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxY), (int) Math.floor(box.maxZ))) {
            cutLeaf(ctx, pos.immutable(), quiet);
        }
    }

    /**
     * Only grown leaves, never a hedge or leaves against a building -- the lumberjack's rule.
     * With {@code quiet} null the leaf breaks as a player's would, crunch and all; with a list
     * it goes without a sound ({@link #cutLeafQuietly}) and its break sound is added to the
     * list for the caller's one soft rustle ({@link #leafRustle}).
     */
    private static void cutLeaf(Context ctx, BlockPos pos, @Nullable List<SoundEvent> quiet) {
        if (!TreeApproachPlanner.isClearableLeaf(ctx, pos)) return;
        boolean cut;
        if (quiet == null) {
            cut = ctx.level().destroyBlock(pos, false, ctx.npc());
        } else {
            BlockState state = ctx.level().getBlockState(pos);
            cut = cutLeafQuietly(ctx, pos, state);
            if (cut) quiet.add(state.getSoundType().getBreakSound());
        }
        if (cut) ctx.mgr().markNavigationDirtyAt(pos);
    }

    /**
     * Takes a leaf away as {@code destroyBlock} does -- water stays in a
     * waterlogged one, sculk sensors still hear it -- but with crumbs only and
     * no break sound.
     */
    private static boolean cutLeafQuietly(Context ctx, BlockPos pos, BlockState state) {
        if (state.isAir()) return false;
        if (!ctx.level().setBlock(pos, ctx.level().getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL)) {
            return false;
        }
        ctx.level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, LEAF_CRUMBS, 0.25, 0.25, 0.25, 0.0);
        ctx.level().gameEvent(GameEvent.BLOCK_DESTROY, pos, GameEvent.Context.of(ctx.npc(), state));
        return true;
    }

    /** One soft rustle at the druid for the leaves cut quietly this tick, in the first one's own sound. */
    private static void leafRustle(Context ctx, List<SoundEvent> cut) {
        if (!cut.isEmpty()) DruidSounds.leafRustle(ctx.level(), ctx.npc().position(), cut.get(0));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static ProfessionSettings settings(Context ctx) {
        return ProfessionSettings.of(ctx.npc(), DruidSettingsSchema.SCHEMA);
    }

    private static boolean carriesTorches(Context ctx) {
        for (ItemStack stack : ctx.npc().getInventory().getSlots()) {
            if (stack.is(Items.TORCH)) return true;
        }
        return false;
    }

    private String describe() {
        if (duty == null) return "none";
        return switch (duty) {
            case CROP -> "crop at " + (cropTile == null ? "?" : cropTile.toShortString());
            case SAPLING -> "sapling at " + (sapling == null ? "?" : sapling.base().toShortString());
            case MINE -> "mine at " + (mine == null ? "?" : mine.mouth().toShortString());
        };
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /**
     * Cheap "is there anything to do" pre-check for brain scoring. The sapling
     * list comes from a shared scan at most ten seconds old, and a mine check
     * reads its plan's cells, a few hundred block reads at most.
     */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = settings(ctx);
        if (!settings.getBool(DruidSettingsSchema.WORK_ENABLED)) return false;

        if (settings.getBool(DruidSettingsSchema.TEND_CROPS_ENABLED)
                && nearestCrop(ctx, Set.of(), null, null, true).tile() != null) {
            return true;
        }
        long now = ctx.level().getGameTime();
        DruidVillageMemory memory = DruidVillageMemory.of(ctx.mgr());
        if (settings.getBool(DruidSettingsSchema.GROW_SAPLINGS_ENABLED)) {
            for (BlockPos pos : memory.saplings(ctx)) {
                DruidSaplings.Target target = DruidSaplings.targetAt(ctx.level(), pos);
                if (target != null && !memory.isSaplingBlocked(target.base(), now)
                        && !ctx.mgr().getClaims().isClaimedByOther(target.base(), ctx.model().npcId)) {
                    return true;
                }
            }
        }
        if (settings.getBool(DruidSettingsSchema.REGROW_MINES_ENABLED)) {
            MineReservations.Snapshot reservations = MineReservations.snapshot(ctx.level());
            for (Building building : ctx.mgr().getBuildings()) {
                if (!(building instanceof MineBuilding candidate) || !candidate.isValid()) continue;
                BlockPos mouth = candidate.mouth();
                if (memory.isMineYielded(mouth, now)
                        || ctx.mgr().getClaims().isClaimedByOther(mouth, ctx.model().npcId)
                        || ctx.mgr().mineShape(candidate.getItemFrameId()).paused()) {
                    continue;
                }
                MineShaftPlan candidatePlan = MineReservations.planFor(candidate, reservations, ctx.mgr());
                if (!MineRegrowth.scan(ctx, candidatePlan).blocks().isEmpty()) return true;
            }
        }
        return false;
    }
}
