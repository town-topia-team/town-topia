package com.alex.test.village.villagers.miner.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavReachability;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.villagers.druid.behavior.MinerHandoff;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.MineReservations;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.miner.OreBlocks;
import com.alex.test.village.villagers.miner.OreHeights;
import com.alex.test.village.villagers.miner.PickaxeUtils;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Miner's working day: drive a strip mine out from the mine room.
 *
 * <p>What a miner used to do was stand in its room and chip at the wall in
 * front of it, reaching about six blocks and stopping. It never walked into
 * its own tunnel, never lit it, never met anything dangerous, and its ore came
 * from a dice roll rather than from the rock.
 *
 * <p>What it does now is what a player does: cut one long corridor out from
 * the mine mouth and throw short branches off it every few blocks — see
 * {@link MineShaftPlan} for the geometry and why that shape. Along the way it
 * <ul>
 *   <li>follows any ore vein it breaks into, rather than leaving the rest of
 *       it in the wall,</li>
 *   <li>walls off lava, water and open cave mouths as it meets them, so the
 *       corridor stays a corridor and nothing follows it home,</li>
 *   <li>lights the corridor, crafting torches from the coal and sticks the
 *       village already has,</li>
 *   <li>and refuses to dig any block another building is standing on.</li>
 * </ul>
 *
 * <p>The corridor is real: each block is removed from the world and the
 * village is told, so the nav graph grows down the shaft and the miner walks
 * in behind its own digging.
 */
public final class MinerActivity extends WorkActivity {

    /*
     * Blocks broken in one stint before the miner goes to bank the load come
     * from MinerBalance.stintBlocks (64 at level 1 up to 128 at level 100).
     *
     * The walk back to the storage room is most of a mine's cost, and at
     * thirty-two the miner paid it every 32 blocks -- which from the outside
     * looks like one that potters at a junction and then leaves. Measured over
     * one perpetual-noon day in a fixed scenario: 160 blocks and an 18-block
     * corridor at thirty-two, 216 blocks and a 23-block corridor at
     * ninety-six, with time in work going from 53% of the day to 61%.
     *
     * Not the real limit on a load: loadIsFull is, and ends the stint early
     * whenever the arms fill first. The stint cap is what stops a miner staying
     * down the hole all day when the rock is poor and nothing takes up room.
     */

    /*
     * Digging reach is a block beside the miner, one across or one across
     * each way, from the floor below its feet to just over its head: see
     * inMiningReach. It used to be five blocks in any direction, so a miner
     * stood back and chipped at rock it was nowhere near.
     */

    /**
     * How far a plug of water or lava can be set or scooped from, squared.
     *
     * <p>Much longer than digging reach, and only for fluid: water spreads
     * up to seven blocks down a tunnel, every wet tile is one nobody can
     * stand on, and a leak that could only be plugged from beside it was
     * never plugged at all.
     */
    private static final double PLUG_REACH_SQ = 100.0;

    /** A plug in progress is given up past this, squared; looser for the same reason as ever. */
    private static final double PLUG_ABANDON_REACH_SQ = 144.0;

    /** Half-width of the box searched for somewhere to stand and plug from. */
    private static final int PLUG_SEARCH_XZ = 8;

    /** Ticks after a plug before the leak check runs again, for the water to drain. */
    private static final int DRAIN_TICKS = 60;

    /**
     * Furthest, squared, from the middle of the block being dug that the
     * miner may stand and still swing at it: a tile beside it is 1 to 1.4
     * away, and a walk counts as arrived up to most of a block off the
     * tile's middle. A tile two away, stood on squarely, is 2.
     */
    private static final double SWING_REACH_SQ = 1.85 * 1.85;

    /** Furthest, squared, the miner steps straight onto a stand tile without a walk. */
    private static final double STEP_REACH_SQ = 2.0 * 2.0;

    /** Close enough, squared, to the middle of a stand tile to count as on it. */
    private static final double STEP_DONE_SQ = 0.3 * 0.3;

    /** Ticks a straight step gets before it counts as blocked. */
    private static final int STEP_MAX_TICKS = 40;

    /**
     * Block light at or below which an open cell gets a torch: with a torch
     * giving 14 and losing one a block, about one every six blocks.
     *
     * <p>Read off the world rather than counted from the last torch placed.
     * The count started again every shift, so the first cell of every shift
     * got a torch however close the last one was, and side branches never got
     * one at all.
     */
    public static final int TORCH_LIGHT_LEVEL = 8;

    /** Ticks one plug takes: a short swing to set the block. */
    public static final int PLUG_TICKS = 10;

    /** How often the dug part of the shaft is checked for leaks and holes. */
    private static final int LEAK_SCAN_TICKS = 200;

    /** Most plugs one leak check queues. */
    private static final int MAX_REPAIRS = 32;

    /**
     * Times one cell's fluid is filled in a shift before the miner leaves it.
     * A source it cannot reach refills the cell after every dig, and each
     * round paid out cobblestone and experience for the same block, forever.
     */
    private static final int MAX_FLUID_FILLS = 3;

    /** Stand tiles checked for a walk from where the miner is before a block is set aside. */
    private static final int MAX_STAND_REACH_TESTS = 16;

    /** Deepest hole a miner climbs out of block by block; deeper is a fall. */
    private static final int MAX_CLIMB_DEPTH = 6;

    /** Ticks between climbing or digging-out steps. */
    private static final int RESCUE_STEP_TICKS = 8;

    /**
     * Most ticks a trapped miner tries to get itself out before it is put
     * back at the entrance, even while it is still digging or plugging.
     */
    private static final long RESCUE_GIVE_UP_TICKS = 1200;

    /**
     * Ticks a trapped miner may go with nothing to dig, plug or climb before
     * it is put back at the entrance. It used to wait out the whole minute
     * however little it had left to try. Long enough for water to drain
     * after the last plug and for the nav graph, which rebuilds within ten
     * seconds of a change, to see the tiles that dried.
     */
    private static final long RESCUE_IDLE_GIVE_UP_TICKS = 300;

    /**
     * Ticks a walk may go without taking the miner a block further before it
     * is stopped as going nowhere. A walk only gave up when the villager
     * stopped moving, and one bobbing in water or pressed into a corner by
     * it never stops moving: one swam in place for minutes.
     */
    private static final int WALK_NOWHERE_TICKS = 100;

    /** Ticks between checks that the way out is still open, while nothing is wrong. */
    private static final int TRAP_CHECK_TICKS = 40;

    /**
     * Open blocks behind a hole, past which it counts as a cave and is walled
     * off. Fewer is a pocket: an old ore hole, a dead-end side tunnel, a gap
     * the size of a cupboard, and nothing comes out of those.
     */
    public static final int CAVE_AIR_LIMIT = 24;


    /** What one coal and one stick make, as vanilla. */
    public static final int TORCHES_PER_CRAFT = 4;

    /** Height either way of the box searched for somewhere to stand and dig from. */
    private static final int STAND_SEARCH_Y = 2;

    /** Blocks across from a planned cell that still count as in the shaft, for getting out. */
    private static final int NEAR_SHAFT_XZ = 2;

    /**
     * Ticks to wait after filling ore holes back in, before choosing where to
     * walk: the nav graph takes a moment to see the new floor, and a cell
     * past it chosen in that moment read as unreachable and was set aside.
     */
    private static final int SETTLE_TICKS = 40;

    /**
     * Items carried, past which a stint that ended early is still worth a
     * trip to storage. A full stint or full arms always banks.
     */
    private static final int WORTH_BANKING = 64;

    /**
     * Ore blocks taken from the walls of the dug tunnel in one stint (see
     * {@link #wallOre}). It is a clean-up on the way to the face, not the
     * day's work: a tunnel lined with ore would otherwise eat the whole stint
     * before the corridor got a block longer.
     */
    private static final int MAX_WALL_ORE_PER_STINT = 8;

    /** Most wall ore one scan of the dug tunnel keeps. */
    private static final int MAX_WALL_ORE_FOUND = 32;

    @Nullable private MineShaftPlan plan;
    @Nullable private UUID planMineId;
    @Nullable private MineReservations.Snapshot mineSnapshot;
    private long plannedRevision = -1;
    @Nullable private MineReservations.Border plannedBorder;
    @Nullable private BlockPos face;
    /** Where the miner was sent to stand for the current face. */
    @Nullable private BlockPos faceStand;
    /**
     * Ticks into the short straight step onto {@link #faceStand}, or -1 when
     * not stepping. See {@link #canStepTo}.
     */
    private int stepTicks = -1;
    @Nullable private MoveActivity move;
    @Nullable private Activity sub;
    private int planIndex;
    /**
     * Set when cobblestone went into a shaft cell, which may be one the dig
     * has already passed: the next choice scans the plan from the start, so
     * that cell is dug back out this shift rather than blocking the way on.
     */
    private boolean replanFromStart;
    private int workTimer;
    private int animTimer;
    /** Ticks per mining loop at the current pickaxe's playback speed. */
    private int strokeTicks = 1;
    /** Ticks the block now being broken takes, which is what its experience is paid on. */
    private int blockTicks = 1;
    private int blocksThisSession;
    private boolean workedThisSession;

    /** Whether the running sub-activity is the end-of-shift deposit. */
    private boolean subEndsSession;

    /** The trip already checked for a walk out of the tunnel first. */
    @Nullable private Activity tripChecked;

    /** The walk out to the mine entrance before a trip; see the sub branch of doTick. */
    @Nullable private MoveActivity walkOut;

    /** One pickaxe run per session: without this the miner retries forever. */
    private boolean triedPickaxe;
    private long lastSeenToolStorageChangeId;

    /** Walking up to, asking or waiting on a druid regrowing the mine. */
    private final MinerHandoff druidWait = new MinerHandoff();

    /** Torch-supply trips this stint, bounded for the same reason. Only a trip that starts counts. */
    private int torchSupplyTrips;
    public static final int MAX_TORCH_SUPPLY_TRIPS = 2;
    /** A look in storage that found nothing to light the mine with waits this long before the next. */
    public static final int TORCH_LOOK_WAIT_TICKS = 30 * 20;
    /** Game time of the next look for torch makings; reset each stint. */
    private long torchLookAfter = Long.MIN_VALUE;

    /** Ore blocks found next to something just broken, still to be taken. */
    private final Deque<BlockPos> vein = new ArrayDeque<>();

    /** Ore blocks already taken off the current vein, against the skill's vein length. */
    private int veinTaken;

    /**
     * Ore sense's side tunnel: the blocks between the corridor and a hidden
     * ore, then the ore itself, in dig order.
     */
    private final Deque<BlockPos> detour = new ArrayDeque<>();

    /** Every block a detour opened or means to open; never walled back up. */
    private final Set<BlockPos> detourCells = new java.util.HashSet<>();

    /**
     * Ore showing in the walls, roof or floor of the tunnel already dug,
     * still to be taken this stint. Found once a stint by
     * {@link #scanWallOre}, the first time there is nothing else to do
     * before the plan; taken nearest the miner first.
     *
     * <p>Ore a vein block had nowhere to stand for, ore the vein cap left
     * behind and ore the tunnel was cut past before the rules were right all
     * ended up here: in plain sight of the corridor, walked past by the
     * miner every shift and never taken.
     */
    private final List<BlockPos> wallOre = new ArrayList<>();

    /** Whether {@link #wallOre} has been filled this stint (or since the last replan). */
    private boolean wallOreScanned;

    /** Wall ore taken this stint, against {@link #MAX_WALL_ORE_PER_STINT}. */
    private int wallOreTaken;

    /**
     * The face picked off {@link #wallOre}, so {@link #breakBlock} knows the
     * block it is taking is wall ore and not part of a vein; null otherwise.
     */
    @Nullable private BlockPos wallOreFace;

    /** Inventory slot of the pickaxe swinging at the current face. */
    private int pickaxeSlot = -1;

    /** Set when the stint has to end before its block count: a full pack or a broken pickaxe. */
    private boolean endStint;

    /** Blocks this session could not get to; skipped rather than re-chosen. */
    private final Set<BlockPos> unreachable = new java.util.HashSet<>();

    /** Mouth of the mine this miner holds, or null while it holds none. */
    @Nullable private BlockPos claimedMine;

    /**
     * Blocks skipped in a row without anything being broken.
     *
     * <p>Skipping is the right answer to one awkward block and the wrong one to
     * a whole plan the miner cannot reach; this bounds the second case so the
     * session still ends rather than spinning down the shaft forever.
     */
    private int skipsWithoutProgress;

    /** Attempts before a miner that is getting nowhere gives up for the shift. */
    private static final int MAX_SKIPS_WITHOUT_PROGRESS = 8;

    /**
     * Leaks and holes to see to before digging on: fluid getting into the dug
     * shaft, a source standing in it, a gap in its floor. Found by
     * {@link #scanForLeaks}; nearest the miner first.
     */
    private final Deque<BlockPos> repairs = new ArrayDeque<>();

    /** Whether the current face is a block to set (or scoop out) rather than one to break. */
    private boolean facePlug;

    /**
     * Ore holes dug below the corridor floor, filled back in once the vein
     * they belong to is done. Left open they were pits in the floor that a
     * villager could drop into and never climb out of.
     */
    private final Set<BlockPos> pits = new java.util.HashSet<>();

    /** Times each cell's fluid was filled this shift, keyed by the cell's floor. */
    private final Map<BlockPos, Integer> fluidFills = new HashMap<>();

    /**
     * Every block this miner broke this shift. Its own openings are never
     * walled back up: the nav graph takes a couple of seconds to notice a new
     * opening, and until then a pocket the miner had just dug to stand in
     * looked exactly like a hole into a cave.
     */
    private final Set<BlockPos> dug = new java.util.HashSet<>();

    private long nextLeakScan;
    private long nextTrapCheck;

    /** {@link Context#clock()} when the miner was first found unable to walk out of its mine, or -1. */
    private long trappedSince = -1;

    /** {@link Context#clock()} when a trapped miner last dug, plugged or climbed; see {@link #RESCUE_IDLE_GIVE_UP_TICKS}. */
    private long lastRescueAction = -1;

    /** Where the current walk last got a block further, and when ({@link Context#clock()}); see {@link #walkGoingNowhere}. */
    @Nullable private net.minecraft.world.phys.Vec3 walkAnchor;
    private long walkAnchorTime;
    private int rescueCooldown;

    /** Game time before which nothing new is started; see {@link #SETTLE_TICKS}. */
    private long settleUntil;

    /** Floors of the open shaft cells that held water or lava at the last leak check. */
    private final Set<BlockPos> wetCells = new java.util.HashSet<>();

    /** Floors of dry cells the nav graph had no tile for, already sent back to it this shift. */
    private final Set<BlockPos> navRechecked = new java.util.HashSet<>();

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), MinerSettingsSchema.SCHEMA);
        if (!settings.getBool(MinerSettingsSchema.WORK_ENABLED)) return Status.DONE;

        // Stuck in a block or standing in lava: dealt with on the spot, whatever
        // else is going on, the way a player would before anything else.
        tickSafety(ctx);

        if (sub != null) {
            // A trip to storage from down the tunnel starts with the walk out
            // of it. The chest walk gives up when it has not got closer to
            // the chest for eight seconds, and a tunnel that runs toward the
            // village takes the miner away from the storage room all the way
            // back to its mouth: chest after chest was given up halfway out,
            // and the miner turned round and walked back in to dig.
            if (tripChecked != sub) {
                tripChecked = sub;
                BlockPos exit = exitIfDownTunnel(ctx);
                if (exit != null) {
                    walkOut = new MoveActivity(exit, VillagerModel.WALK_SPEED);
                    walkAnchor = null;
                }
            }
            if (walkOut != null) {
                if (walkOut.tickOuter(ctx) == Status.RUNNING && !walkGoingNowhere(ctx)) {
                    return Status.RUNNING;
                }
                // Out, or as far as the walk got: the trip starts from there.
                walkOut.stop(ctx);
                walkOut = null;
                return Status.RUNNING;
            }
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            sub = null;
            // A deposit is the end of the shift; a supply run is not.
            if (subEndsSession) {
                // Enchanted books go on at the blacksmith's anvil now (BookFitting),
                // a trip of the miner's own when a useful book waits in storage.
                return Status.DONE;
            }
            return Status.RUNNING;
        }

        // Out of a mine the player paused, with nothing to bank (see
        // leavePausedMine). Away from a trip, only that walk sets walkOut.
        if (walkOut != null) {
            if (walkOut.tickOuter(ctx) == Status.RUNNING && !walkGoingNowhere(ctx)) {
                return Status.RUNNING;
            }
            walkOut.stop(ctx);
            walkOut = null;
            return workedThisSession ? Status.DONE : Status.FAILED;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING && walkGoingNowhere(ctx)) {
                diary(ctx, "MINE walk got nowhere in " + WALK_NOWHERE_TICKS / 20 + " s at "
                        + ctx.npc().blockPosition().toShortString() + ", stopping it");
                status = Status.FAILED;
            }
            if (status == Status.RUNNING) return Status.RUNNING;
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED) {
                // Set aside and carry on rather than ending the shift. A walk
                // fails for passing reasons -- another villager in the corridor,
                // a block that changed under the path -- and treating each one
                // as the end of the day was most of why a miner in a busy mine
                // dug almost nothing.
                diary(ctx, "MINE could not reach the face at " + face + ", setting it aside");
                if (face != null) unreachable.add(face);
                face = null;
                facePlug = false;
                // A walk that fails inside the mine can mean the way out has
                // gone too: look now rather than at the next routine check.
                nextTrapCheck = 0;
                // Mid-rescue a failed walk is no reason to end the shift: the
                // deposit trip it would start cannot get out either.
                if (trappedSince >= 0) return Status.RUNNING;
                return skipOrFinish(ctx);
            }
            // A walk counts as there from a block or so short, which can be
            // out of digging reach: the last bit is a straight step.
            if (!facePlug && face != null && !canSwingAt(ctx.npc(), face)) {
                if (faceStand != null && canStepTo(ctx, faceStand)) {
                    startStep(ctx);
                    return Status.RUNNING;
                }
                return cannotGetBeside(ctx);
            }
            beginWork(ctx);
            return Status.RUNNING;
        }

        if (stepTicks >= 0) return tickStep(ctx);

        if (workTimer > 0) {
            tickMining(ctx);
            return Status.RUNNING;
        }

        // Holes just filled in: let the nav graph see the new floor first.
        if (ctx.level().getGameTime() < settleUntil) return Status.RUNNING;

        // Before banking too: a trapped miner's deposit trip fails at every
        // chest, and that is how it used to write off the whole storage room.
        // A shift that starts with full arms goes straight to banking, before
        // any plan existed for this to look at: a miner stranded down its
        // tunnel with a full pack tried the chests, failed, and did the same
        // every shift after.
        if (plan == null) preparePlan(ctx);
        if (plan != null && rescueIfTrapped(ctx)) return Status.RUNNING;

        if (endStint || blocksThisSession
                >= MinerBalance.stintBlocks(ctx.brain().minerSkillLevel())) {
            return finishSession(ctx);
        }
        if (loadIsFull(ctx)) {
            diary(ctx, "MINE arms full after " + blocksThisSession + " blocks, banking");
            return finishSession(ctx);
        }

        return startNextStint(ctx);
    }

    @Override
    public void doStop(Context ctx) {
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        if (walkOut != null) { walkOut.stop(ctx); walkOut = null; }
        stepTicks = -1;
        druidWait.stop(ctx);
        // Cut off mid-vein (bedtime, a meal): the next shift starts with no
        // list of these holes, so they are filled now or never.
        backfillPits(ctx);
        releaseMine(ctx);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    /** Partway through breaking (or plugging) a block. */
    public boolean isMiningBlock() {
        return workTimer > 0;
    }

    // =========================================================================
    // Choosing the next block
    // =========================================================================

    /**
     * Claims the mine and makes sure the plan is the one for it, as it is
     * now. Null while a druid regrowing the mine holds it.
     */
    @Nullable
    private MineBuilding preparePlan(Context ctx) {
        MineBuilding mine = claimMine(ctx);
        if (mine == null) return null;
        if (plan == null || planMineId == null
                || !planMineId.equals(mine.getItemFrameId())
                // A change to this mine's settings or the village's side
                // length bumps the revision (VillageManager.onMineLayoutChanged).
                || plannedRevision != MineReservations.snapshot(ctx.level()).revision()
                || !java.util.Objects.equals(plannedBorder, MineReservations.Border.of(ctx.mgr()))) {
            plan = planFor(ctx, mine);
            planIndex = 0;
            vein.clear();
            veinTaken = 0;
            detour.clear();
            detourCells.clear();
            unreachable.clear();
            repairs.clear();
            // A new plan can mean a different shaft: the walls are looked
            // over again for it, the next time there is nothing else to do.
            wallOre.clear();
            wallOreScanned = false;
        }
        return mine;
    }

    private Status startNextStint(Context ctx) {
        MineBuilding mine = preparePlan(ctx);
        if (mine == null) {
            // A druid regrowing the mine holds its claim. Go over, and either
            // ask for it or wait for the druid to finish; claimMine takes it
            // on the first tick the druid lets go.
            Status waiting = druidWait.tick(ctx);
            return waiting != null ? waiting : finishSession(ctx);
        }
        druidWait.stop(ctx);
        if (plan == null) return finishSession(ctx);

        // Getting out of a hole, a fall of gravel or a corridor closed behind
        // it comes before any work: nothing else can be reached until it has.
        if (rescueIfTrapped(ctx)) return Status.RUNNING;

        // Paused in its Mine screen while the miner was down it (claimMine
        // keeps such a mine only for this): out, and nothing more dug.
        if (paused(ctx, mine)) return leavePausedMine(ctx);

        if (!hasPickaxe(ctx)) {
            // Broken or never issued. Fetch or craft one rather than ending
            // the miner's career -- see GetPickaxeActivity for the deadlock
            // this replaced.
            if (!triedPickaxe) {
                triedPickaxe = true;
                sub = new GetPickaxeActivity();
                subEndsSession = false;
                return Status.RUNNING;
            }
            diary(ctx, "MINE no usable pickaxe, and none to fetch or craft");
            return finishSession(ctx);
        }
        if (tryStartPickaxeUpgrade(ctx)) return Status.RUNNING;

        // Open the two blocks below the marked roof before driving the shaft.
        // Until that entrance is open the corridor is a sealed pocket:
        // the miner cannot walk into it and the nav graph's flood fill cannot
        // reach it either. It cut thirty blocks of tunnel it could never enter
        // and then stood at the wall, out of ideas.
        BlockPos doorway = mouthToOpen(ctx, mine);
        if (doorway != null) {
            BlockPos doorStand = standNear(ctx, doorway, false);
            if (doorStand != null) return workAt(ctx, doorway, doorStand, false);
        }

        // Nothing to light the mine with, and it is dark enough to matter.
        // Fetching is a sub-activity of this one, so the two trips happen back
        // to back with no deposit pass between them to undo the first.
        Status supply = fetchTorchMakings(ctx);
        if (supply != null) return supply;

        // Water or lava getting into the part already dug, or a hole in its
        // floor, before anything else: it only gets worse while the miner
        // works on at the far end.
        scanForLeaks(ctx);
        BlockPos repair = nextRepair(ctx);
        if (repair != null) {
            BlockPos repairStand = standNear(ctx, repair, true);
            if (repairStand != null) return workAt(ctx, repair, repairStand, true);
            // Set aside without using up one of the shift's tries: the leak
            // check now looks over the whole open tunnel, and a few leaks out
            // of reach would otherwise end the shift before any digging.
            unreachable.add(repair);
            return Status.RUNNING;
        }

        // A vein already broken into is finished before the plan resumes: the
        // ore is right there, and leaving half of it in the wall to come back
        // for later means never coming back for it.
        // A side tunnel ore sense started is finished first: it is at most two
        // blocks and an ore, and the vein behind it is the reason for it.
        BlockPos next = nextDetourBlock(ctx);
        boolean fromDetour = next != null;
        if (next == null) next = nextVeinBlock(ctx);
        // Vein and side tunnel done: close the holes they left under the floor,
        // and give the nav graph a moment to see the floor before walking on.
        if (next == null && backfillPits(ctx)) {
            settleUntil = ctx.level().getGameTime() + SETTLE_TICKS;
            return Status.RUNNING;
        }
        // Then the ore showing in the walls of the tunnel already dug, a few
        // a stint, before the face moves on. Each floor hole one leaves is
        // filled by the backfill just above on the next pass, before the
        // next one is picked.
        boolean fromWall = false;
        if (next == null) {
            next = nextWallOre(ctx);
            fromWall = next != null;
        }
        BlockPos stand;
        boolean plug = false;
        if (next != null) {
            stand = standNear(ctx, next, false);
            if (stand == null && fromWall) {
                // Seen from the tunnel but with no tile beside it to swing
                // from. Dropped from this stint's list; the next stint looks
                // again, and it costs none of the shift's tries.
                wallOre.remove(next);
                diary(ctx, "MINE nowhere to stand for the wall ore at " + next.toShortString()
                        + ", leaving it for now");
                return Status.RUNNING;
            }
            if (stand == null) {
                // Ore off the line of the tunnel -- up through the roof, down
                // under the floor, deeper into the wall -- with nowhere beside
                // it to stand. It is extra, not the plan, so it costs none of
                // the shift's tries: with digging reach a block beside the
                // miner, counting these ended shifts in the middle of a vein.
                //
                // Dropped from its queue, not set aside. Set aside, it was
                // never looked at again for the rest of the shift: the vein
                // skipped it, and so did the plan, whose skip list is the same
                // set. Ore one cell ahead of the face was queued the moment
                // the block beside it came out, had no stand until the tile in
                // front of it was dug, went on the list, and then the plan
                // left that block standing in its own cell -- a corridor one
                // block high, and the next eight cells all "nowhere to stand"
                // until the stint gave up. Nothing remembers it now, so the
                // next block broken beside it queues it again (collectVein),
                // which is just when a tile beside it has been opened. Only a
                // real break queues it, so it is never retried tick on tick.
                if (fromDetour) {
                    // A side tunnel is dropped whole: its blocks are in dig
                    // order, and the ore at the end is no use without the
                    // rock before it. What was not dug is no longer the
                    // miner's opening to keep open.
                    for (BlockPos left : detour) {
                        if (!dug.contains(left)) detourCells.remove(left);
                    }
                    detour.clear();
                } else {
                    vein.remove(next);
                }
                diary(ctx, "MINE nowhere to stand for " + (fromDetour ? "the side tunnel at " : "ore at ")
                        + next.toShortString() + " yet, back for it when the tunnel reaches it");
                return Status.RUNNING;
            }
            if (fromWall) {
                if (!next.equals(wallOreFace)) {
                    diary(ctx, "MINE back for " + ctx.level().getBlockState(next).getBlock()
                            .getName().getString() + " left in the wall at " + next.toShortString());
                }
                Status started = workAt(ctx, next, stand, false);
                wallOreFace = next;
                return started;
            }
        } else {
            if (replanFromStart) {
                planIndex = 0;
                replanFromStart = false;
            }
            int index = nextPlanIndex(ctx, mine);
            if (index < 0) return finishSession(ctx);
            planIndex = index;
            MineShaftPlan.Cell cell = plan.cell(index);
            if (cell == null) return finishSession(ctx);
            // A fluid in the corridor's own path cannot be dug, only filled.
            // Left alone it is simply skipped, and the miner strolls past an
            // open lava pocket at head height on its way down the tunnel.
            if (fillFluidInCell(ctx, cell)) return Status.RUNNING;
            next = firstSolidOfCell(ctx, cell, unreachable);
            if (next == null) {
                // Already open but with nothing under it: the shaft has run
                // into a cave or an underground lake. Bridge it, as a player
                // would, rather than leave a gap nobody can walk across.
                BlockPos gap = missingFloor(ctx, cell.floor());
                if (gap != null && !unreachable.contains(gap)) {
                    next = gap;
                    plug = true;
                }
            }
            if (next == null) {
                // Every block of this cell is already gone or set aside --
                // usually because somebody else opened it. Step past it.
                planIndex = index + 1;
                return skipOrFinish(ctx);
            }
            stand = plug ? null : plan.standFor(index);
            // The plan's own stand tile only if the block is in reach from it
            // and the miner can step or walk to it from here: a tile on the
            // far side of a blockage is a node all the same, and walking at
            // it failed every time.
            if (stand == null || !inMiningReach(stand, next)
                    || (!canStepTo(ctx, stand) && (!isWalkable(ctx, stand) || !canWalkTo(ctx, stand)))) {
                stand = standNear(ctx, next, plug);
            }
        }
        if (stand == null) {
            diary(ctx, "MINE nowhere to stand to reach " + next.toShortString()
                    + " (shaft from " + plan.mouth().toShortString()
                    + " " + plan.direction() + ", cell " + planIndex + ")");
            // Set aside, or the very next tick picks the same block again.
            unreachable.add(next);
            return skipOrFinish(ctx);
        }
        return workAt(ctx, next, stand, plug);
    }

    /** Takes {@code target} as the face and walks to {@code stand}, or starts at once if already there. */
    private Status workAt(Context ctx, BlockPos target, BlockPos stand, boolean plug) {
        if (!plug) {
            // Asked before the walk and the swing, not only once the swing is
            // over: a block it may not take was dug at in full, then set aside,
            // and looked to the player like a miner that could not break rock.
            String refusal = digRefusal(ctx, target);
            if (refusal != null) {
                diary(ctx, "MINE may not take " + target.toShortString() + " (" + refusal + "), setting it aside");
                unreachable.add(target);
                face = null;
                facePlug = false;
                // A rescue ignores the answer: no ending the stint from there.
                if (trappedSince >= 0) return Status.RUNNING;
                return skipOrFinish(ctx);
            }
        }
        face = target;
        facePlug = plug;
        // Any new face is not wall ore unless the wall-ore step says so
        // after this call (see startNextStint).
        wallOreFace = null;
        faceStand = stand.immutable();
        // Starts from where it is only with the block already in reach: for a
        // block to dig, beside it (which also keeps it off the block under its
        // own feet -- a step away from a stand tile beside an ore under the
        // floor, the miner used to swing from where it was, dig out its own
        // floor and drop into the hole); for a plug, on the stand's level and
        // a step from it at most.
        BlockPos at = NavGraph.feetCell(ctx.npc());
        boolean there = plug
                ? at.equals(stand) || (at.getY() == stand.getY() && at.distSqr(stand) <= 2.25)
                : canSwingAt(ctx.npc(), target);
        if (there) {
            beginWork(ctx);
        } else if (!plug && canStepTo(ctx, stand)) {
            // The tile just dug, right in front of it: a straight step, with
            // no walk. The nav graph takes a few seconds to learn of a block
            // that was dug, and waiting on it to walk one tile forward made
            // every cell of the tunnel "nowhere to stand".
            startStep(ctx);
        } else {
            // Onto the tile itself where it can, or as near as the walk gets.
            move = new MoveActivity(stand, VillagerModel.WALK_SPEED, false, !plug);
            walkAnchor = null;
        }
        return Status.RUNNING;
    }

    /**
     * Whether the walk under way has gone {@link #WALK_NOWHERE_TICKS} without
     * taking the miner a block further. Called once a tick while it runs.
     */
    private boolean walkGoingNowhere(Context ctx) {
        net.minecraft.world.phys.Vec3 at = ctx.npc().position();
        long now = ctx.clock();
        if (walkAnchor == null || at.distanceToSqr(walkAnchor) > 1.0) {
            walkAnchor = at;
            walkAnchorTime = now;
            return false;
        }
        return now - walkAnchorTime > WALK_NOWHERE_TICKS;
    }

    /**
     * Whether the miner, where it really stands, can swing at {@code target}:
     * from a tile beside it, so 1 to 1.5 blocks off, give or take where on
     * the tile it stopped; never from over or under it; from the floor below
     * its feet to just over its head.
     *
     * <p>Judged from its position rather than the tile its feet are counted
     * in: a walk counts as arrived from most of a block off the middle of
     * the tile, and a miner that stopped a hair into the tile before read as
     * two tiles away, set the block aside and went home.
     */
    private static boolean canSwingAt(NpcEntity npc, BlockPos target) {
        double dx = target.getX() + 0.5 - npc.getX();
        double dz = target.getZ() + 0.5 - npc.getZ();
        if (Math.abs(dx) < 0.5 && Math.abs(dz) < 0.5) return false;   // over or under it
        if (dx * dx + dz * dz > SWING_REACH_SQ) return false;
        int dy = target.getY() - NavGraph.feetCell(npc).getY();
        return dy >= -1 && dy <= MineShaftPlan.TUNNEL_HEIGHT;
    }

    /**
     * Whether the miner can step straight onto {@code stand} from where it is,
     * with no walk: on its own level, close, and the tile and the one it
     * crosses on the way open to stand in. A tile it has just dug qualifies
     * before the nav graph knows it is there.
     */
    private boolean canStepTo(Context ctx, BlockPos stand) {
        NpcEntity npc = ctx.npc();
        if (NavGraph.feetCell(npc).getY() != stand.getY()) return false;
        double dx = stand.getX() + 0.5 - npc.getX();
        double dz = stand.getZ() + 0.5 - npc.getZ();
        if (dx * dx + dz * dz > STEP_REACH_SQ) return false;
        BlockPos between = BlockPos.containing(npc.getX() + dx / 2, stand.getY(), npc.getZ() + dz / 2);
        return isStepable(ctx, stand) && isStepable(ctx, between);
    }

    /** A dry tile with a floor under it and room for a villager. */
    private static boolean isStepable(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        BlockPos below = pos.below();
        return NavGraph.isDryWalkable(level, pos)
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    private void startStep(Context ctx) {
        ctx.model().stopMoving();
        stepTicks = 0;
    }

    /** One tick of the straight step onto {@link #faceStand}; work starts once on it. */
    private Status tickStep(Context ctx) {
        NpcEntity npc = ctx.npc();
        if (faceStand == null) {
            stepTicks = -1;
            return Status.RUNNING;
        }
        double x = faceStand.getX() + 0.5;
        double z = faceStand.getZ() + 0.5;
        double dx = x - npc.getX();
        double dz = z - npc.getZ();
        boolean onIt = dx * dx + dz * dz <= STEP_DONE_SQ;
        if (!onIt && ++stepTicks <= STEP_MAX_TICKS) {
            npc.getMoveControl().setWantedPosition(x, npc.getY(), z, 0.5);
            return Status.RUNNING;
        }
        stepTicks = -1;
        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0);
        if (face == null) return Status.RUNNING;
        if (facePlug || canSwingAt(npc, face)) {
            beginWork(ctx);
            return Status.RUNNING;
        }
        return cannotGetBeside(ctx);
    }

    /** Sets the current face aside: the miner could not get to a tile it can swing at it from. */
    private Status cannotGetBeside(Context ctx) {
        if (face != null) {
            diary(ctx, "MINE could not get beside " + face.toShortString() + " from "
                    + String.format("(%.1f, %.1f, %.1f)", ctx.npc().getX(), ctx.npc().getY(), ctx.npc().getZ())
                    + (faceStand != null ? " (stand " + faceStand.toShortString() + ")" : "")
                    + ", setting it aside");
            unreachable.add(face);
        }
        face = null;
        facePlug = false;
        if (trappedSince >= 0) return Status.RUNNING;
        return skipOrFinish(ctx);
    }

    /**
     * Whether a block is in digging reach of a miner whose feet are at
     * {@code feet}: in a column beside its own -- one across, or one across
     * each way, so 1 to 1.5 blocks off -- from the block under the floor
     * there up to the one just over its head. Never its own column: that is
     * either the floor it stands on or the roof straight over it.
     */
    private static boolean inMiningReach(BlockPos feet, BlockPos target) {
        int dx = target.getX() - feet.getX();
        int dz = target.getZ() - feet.getZ();
        int across = dx * dx + dz * dz;
        int dy = target.getY() - feet.getY();
        return across >= 1 && across <= 2 && dy >= -1 && dy <= MineShaftPlan.TUNNEL_HEIGHT;
    }

    /**
     * The first plan cell that still has rock in it.
     *
     * <p>Progress is read from the world rather than remembered: a cell that is
     * already open has already been dug, whoever dug it and however long ago.
     * That makes the shaft survive a reload, a miner replaced by a different
     * one, and a player carving part of it out by hand, with no bookkeeping to
     * get out of step. It no longer has to cope with two miners in the same
     * shaft at once -- see {@link #claimMine} for why that stopped being
     * something to support and started being something to prevent.
     */
    private int nextPlanIndex(Context ctx, MineBuilding mine) {
        // (mine is kept in the signature for symmetry with the other lookups;
        //  the plan itself is what says where the shaft goes.)
        if (plan == null) return -1;
        Set<BlockPos> wayOut = wayOut(ctx);
        for (int scanned = 0; scanned < plan.cellCount(); scanned++) {
            int index = planIndex + scanned;
            MineShaftPlan.Cell cell = plan.cell(index);
            if (cell == null) return -1;
            if (isCellBlocked(ctx, cell)) continue;   // another building owns it
            if (firstSolidOfCell(ctx, cell, unreachable) != null) return index;
            // A cell that is nothing but fluid has no diggable block, so
            // skipping it here left the lava pocket in the middle of the
            // corridor: the miner tunnelled up to it, stepped over it in the
            // plan, and then could not reach anything beyond it.
            if (cellNeedsFluidWork(ctx, cell, wayOut)
                    && fluidFills.getOrDefault(cell.floor(), 0) < MAX_FLUID_FILLS) return index;
            // Open, but over a cave or a lake: needs a floor before anyone
            // can walk on to the cells past it.
            BlockPos gap = missingFloor(ctx, cell.floor());
            if (gap != null && !unreachable.contains(gap)) return index;
        }
        return -1;
    }

    /**
     * The block under an open cell that ought to be its floor and is not --
     * air or fluid where a cave or a lake crosses the shaft -- or null.
     *
     * <p>Underground only. A shaft that has come out of the hillside runs on
     * over open ground, and bridging a valley in cobblestone is not the
     * miner's call.
     */
    @Nullable
    private BlockPos missingFloor(Context ctx, BlockPos floor) {
        ServerLevel level = ctx.level();
        if (isDiggable(level.getBlockState(floor))
                || isDiggable(level.getBlockState(floor.above()))) {
            return null;   // still rock: dug first, its floor looked at after
        }
        BlockPos below = floor.below();
        // The miner's own ore hole: left open while the vein goes on down,
        // and filled from the bottom by backfillPits once it is done.
        if (pits.contains(below)) return null;
        if (!canPlugOver(level.getBlockState(below))) return null;
        if (isProtected(ctx, below) || !permitted(ctx, below)) return null;
        if (level.canSeeSky(floor)) return null;
        return below;
    }

    /** True if this cell belongs to somebody else and must be tunnelled around. */
    private boolean isCellBlocked(Context ctx, MineShaftPlan.Cell cell) {
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = cell.floor().above(dy);
            if (isProtected(ctx, pos)) return true;
        }
        return false;
    }

    /**
     * The next block of this cell to break, head first.
     *
     * <p>Top down, so the miner never stands in a hole it has dug under
     * itself, and so the floor it is about to walk on is the last thing to
     * open rather than the first.
     */
    @Nullable
    private static BlockPos firstSolidOfCell(Context ctx, MineShaftPlan.Cell cell,
                                             Set<BlockPos> skip) {
        for (int dy = MineShaftPlan.TUNNEL_HEIGHT - 1; dy >= 0; dy--) {
            BlockPos pos = cell.floor().above(dy);
            if (skip.contains(pos)) continue;
            if (isDiggable(ctx.level().getBlockState(pos))) return pos;
        }
        return null;
    }

    // =========================================================================
    // Mining
    // =========================================================================

    /**
     * Starts one block: picks the pickaxe that breaks it fastest, and sets
     * the time from that pickaxe's swing count and swing speed.
     *
     * <p>The pickaxe decides how many swings (wood 5 down to netherite 1,
     * fewer with Efficiency) and how fast each plays (1x up to 1.4x with
     * netherite and Efficiency V). Skill does not change either.
     */
    private void beginMining(Context ctx) {
        if (face == null) return;
        pickaxeSlot = PickaxeUtils.findFastestUsablePickaxeSlot(
                ctx.npc().getInventory(), allowedPickaxes(ctx), ctx.level());
        ItemStack pickaxe = pickaxeSlot >= 0
                ? ctx.npc().getInventory().getSlot(pickaxeSlot) : ItemStack.EMPTY;
        Item pickaxeItem = pickaxe.isEmpty() ? Items.WOODEN_PICKAXE : pickaxe.getItem();
        double efficiency = PickaxeUtils.efficiencyLevel(ctx.level(), pickaxe);

        workTimer = MinerBalance.mineTicks(pickaxeItem, efficiency);
        blockTicks = workTimer;
        float animationSpeed = MinerBalance.animationSpeed(pickaxeItem, efficiency);
        // One stone-hit sound per loop, landing mid-swing.
        strokeTicks = Math.max(1, Math.round(MinerBalance.MINE_CLIP_TICKS / animationSpeed));
        animTimer = strokeTicks / 2;
        if (!pickaxe.isEmpty()) {
            ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, pickaxe.copy());
        }
        lookAtFace(ctx);
        ctx.npc().startWorkAnimation(VillagerAnimationState.MINE, animationSpeed);
    }

    /** Starts on the current face: a block to break, or one to set. */
    private void beginWork(Context ctx) {
        if (facePlug) beginPlug(ctx);
        else beginMining(ctx);
    }

    /**
     * Starts a plug: a short swing with cobblestone in hand. Setting a block
     * is quicker than breaking one, so no pickaxe and no swing count.
     */
    private void beginPlug(Context ctx) {
        if (face == null) return;
        workTimer = PLUG_TICKS;
        blockTicks = PLUG_TICKS;
        strokeTicks = MinerBalance.MINE_CLIP_TICKS;
        animTimer = strokeTicks;
        // A prop, not a real block: plugs cost nothing. A real cobblestone in
        // hand belongs to nobody, so the villager threw it on the ground as
        // equipment it may not hold -- one dropped block for every plug.
        ctx.npc().holdDisplayItem(new ItemStack(Items.COBBLESTONE),
                ctx.level().getGameTime() + PLUG_TICKS + 5);
        lookAtFace(ctx);
        ctx.npc().startWorkAnimation(VillagerAnimationState.MINE, 1.0f);
    }

    private void lookAtFace(Context ctx) {
        if (face == null) return;
        ctx.npc().getLookControl().setLookAt(
                face.getX() + 0.5, face.getY() + 0.5, face.getZ() + 0.5, 30f, 30f);
    }

    private void tickMining(Context ctx) {
        boolean stillWanted = face != null && (facePlug
                ? needsPlug(ctx, face)
                : isDiggable(ctx.level().getBlockState(face)));
        if (!stillWanted) {
            workTimer = 0;
            face = null;
            facePlug = false;
            return;
        }
        if (!facePlug && digsOwnHole(ctx, face)) {
            // Walked, slid or was pushed over the block it is digging: taking
            // it now drops the miner into its own hole.
            diary(ctx, "MINE " + face.toShortString() + " is under its own feet, setting it aside");
            unreachable.add(face);
            workTimer = 0;
            face = null;
            facePlug = false;
            return;
        }
        if (facePlug && ctx.npc().blockPosition().distSqr(face) > PLUG_ABANDON_REACH_SQ) {
            // Set aside rather than simply dropped: picking the next block is
            // deterministic, so a face abandoned without being remembered is
            // the face that gets chosen again on the very next tick.
            diary(ctx, "MINE out of reach, setting aside " + face.toShortString());
            unreachable.add(face);
            workTimer = 0;
            face = null;
            facePlug = false;
            return;
        }
        if (!facePlug && !canSwingAt(ctx.npc(), face)) {
            // Nudged off its tile mid-swing. Dropped, not set aside: the next
            // choice is the same block again, and walking back beside it is
            // what follows (workAt). A block it cannot get beside is set
            // aside there, after the last step onto the tile.
            workTimer = 0;
            face = null;
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            return;
        }
        workTimer--;
        // Held every tick, as the farmer does: MINE is a timed state and would
        // otherwise drop back to idle partway through a four-loop block.
        ctx.npc().setAnimation(VillagerAnimationState.MINE);
        lookAtFace(ctx);

        if (!facePlug && animTimer-- <= 0) {
            animTimer = strokeTicks;
            ctx.level().playSound(null, face, SoundEvents.STONE_HIT,
                    SoundSource.BLOCKS, 0.5f, 1.0f);
        }

        if (workTimer <= 0) {
            if (facePlug) plug(ctx, face);
            else breakBlock(ctx, face);
            face = null;
            facePlug = false;
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
    }

    /** Takes one block: yield, wear, Mending, removal, hazards, vein, ore sense, light. */
    private void breakBlock(Context ctx, BlockPos pos) {
        String refusal = digRefusal(ctx, pos);
        if (refusal != null) {
            diary(ctx, "MINE may not take " + pos.toShortString() + " (" + refusal + "), setting it aside");
            unreachable.add(pos);
            return;
        }
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        int skill = ctx.brain().minerSkillLevel();
        InventoryManager inventory = ctx.npc().getInventory();
        ItemStack tool = pickaxeSlot >= 0 && PickaxeUtils.isUsablePickaxe(inventory.getSlot(pickaxeSlot))
                ? inventory.getSlot(pickaxeSlot) : ItemStack.EMPTY;

        // What the block counts as: itself, or for plain rock sometimes the
        // ore a scrap of it came from. Drops and experience both follow it.
        BlockState source = yieldSource(state, pos.getY(), skill, level.random);
        boolean ore = isOre(source);
        // A player's pickaxe digs with its enchantments at their share (PlayerGear).
        ItemStack working = com.alex.test.village.tokens.PlayerGear.effective(tool, level.random);
        int experience = ore ? EnchantmentHelper.processBlockExperience(level, working,
                source.getBlock().getExpDrop(source, level, pos, null, ctx.npc(), working)) : 0;
        List<ItemStack> drops = yieldOf(ctx, pos, state, source, working);

        for (ItemStack drop : drops) {
            ItemStack yield = com.alex.test.village.tokens.VillageItemOwnership.produce(drop, ctx.mgr());
            if (yield.isEmpty()) continue;
            String described = yield.getCount() + "x " + yield.getItem().getDescription().getString();
            if (!inventory.addItem(yield)) {
                // Nothing is thrown away quietly: the stint is capped by
                // loadIsFull, so reaching here means an estimate was wrong, and
                // a miner silently voiding its ore is the kind of thing that is
                // only ever noticed as "the mine produces less than it should".
                diary(ctx, "MINE could not carry " + described + ", banking now");
                endStint = true;
            } else {
                diary(ctx, "MINE " + described + " at " + pos.toShortString());
            }
        }

        wearPickaxe(ctx, experience);

        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.7f, 1.0f);
        // The village listens for player breaks, not villager ones, so the nav
        // graph would never learn the corridor exists and the miner could not
        // walk into it.
        ctx.mgr().noteBlockChanged(pos);
        dug.add(pos.immutable());

        blocksThisSession++;
        workedThisSession = true;
        skipsWithoutProgress = 0;
        lastRescueAction = ctx.clock();
        // Wall ore is taken as it shows, one block at a time: it never counts
        // against a vein, and nothing is followed from it (below).
        boolean wallPick = pos.equals(wallOreFace);
        if (wallPick) {
            wallOreFace = null;
            wallOreTaken++;
        }
        wallOre.remove(pos);
        if (vein.remove(pos) && !wallPick) veinTaken++;
        detour.remove(pos);
        // Below the corridor floor: a hole somebody could drop into. Filled
        // back in once the vein it belongs to is finished -- or, for ore taken
        // out of the tunnel floor, before the next wall ore is picked.
        if (plan != null && pos.getY() < plan.mouth().getY()) pits.add(pos.immutable());
        ctx.brain().onMinerBlockMined(ctx, ore, blockTicks);

        sealHazardsAround(ctx, pos);
        // Not from wall ore. Only what shows in the tunnel walls is taken
        // there: following each one into the rock would turn a short clean-up
        // into the whole veins behind them, past the skill's vein length, and
        // a novice is meant to leave the hidden part of a big vein. Ore sense
        // is skipped for the same reason; it already had its look past this
        // wall when the corridor block in front of it came out.
        if (!wallPick) {
            collectVein(ctx, pos);
            senseOre(ctx, pos);
        }
        maybeLightCorridor(ctx);
    }

    /**
     * One vanilla durability point for the block (Unbreaking applied), then
     * Mending from the block's experience. The equipped copy is refreshed so
     * the pickaxe in hand shows its wear; a pickaxe that breaks ends the stint
     * so the load is banked before a new one is fetched.
     */
    private void wearPickaxe(Context ctx, int experience) {
        if (pickaxeSlot < 0) return;
        InventoryManager inventory = ctx.npc().getInventory();
        boolean survived = PickaxeUtils.damageForBlock(ctx.level(), inventory, pickaxeSlot);
        if (!survived) {
            ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            ctx.level().playSound(null, ctx.npc().blockPosition(), SoundEvents.ITEM_BREAK,
                    SoundSource.NEUTRAL, 0.8f, 1.0f);
            pickaxeSlot = -1;
            if (!hasPickaxe(ctx)) {
                diary(ctx, "MINE pickaxe broke, banking the load and fetching another");
                endStint = true;
            } else {
                diary(ctx, "MINE pickaxe broke, switching to a spare");
            }
            return;
        }
        ItemStack pickaxe = inventory.getSlot(pickaxeSlot);
        int repaired = PickaxeUtils.repairFromExperience(ctx.level(), pickaxe, experience);
        if (repaired > 0) diary(ctx, "MINE Mending repaired " + repaired + " durability");
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, pickaxe.copy());
    }

    /**
     * Walls off anything dangerous the new opening exposed.
     *
     * <p>Lava and water get replaced outright — a miner that opens a lava
     * pocket at head height dies, and one that opens a water pocket floods the
     * corridor it just cut. A cave mouth is filled the same way: an open shaft
     * into a cave system is a road for everything in it, and the village's own
     * pathfinder will happily send villagers down it.
     *
     * <p>Only the four sides and the floor and ceiling of the block just taken
     * are checked, so this costs six block reads per swing.
     *
     * <p>Above the ceiling and under the floor count too. They used to be
     * skipped for every block over or under the shaft, so lava sitting on the
     * roof poured in on the miner the moment the head block came out, water
     * there flooded the corridor for good, and a cave under the floor left a
     * hole in it that villagers fell through.
     */
    private void sealHazardsAround(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        Set<BlockPos> wayOut = wayOut(ctx);
        for (Direction dir : Direction.values()) {
            BlockPos side = pos.relative(dir);
            if (isProtected(ctx, side)) continue;
            // An ore-sense side tunnel is the miner's own opening, not a cave.
            if (detourCells.contains(side)) continue;

            BlockState state = level.getBlockState(side);
            boolean fluid = isLooseFluid(state);
            // Open air in the neighbouring mine's rock is its tunnel, and is
            // never walled; water and lava there are (see mayPlugNow).
            if (!ownedByMine(side) && !fluid) continue;
            if (isPartOfShaft(side) || (fluid && isOwnFluidSpace(side, wayOut))) {
                // Fluid in the shaft's own path: plugged where the rock is
                // still to be dug, scooped out where it already has been.
                // Skipping it let a source one cell ahead pour into every
                // cell the miner opened. Ahead of the miner it is filled and
                // dug through (damsFluid); on its way out the cell's leaks
                // are plugged from the outside first, or the scoop refills.
                if (fluid) {
                    if (isPartOfShaft(side) && !damsFluid(ctx, side, wayOut)) {
                        sealCellFromOutside(ctx, cellFloor(side), wayOut);
                    }
                    applyPlug(ctx, side);
                }
                continue;
            }
            // Air leading into something else -- a cave, a ravine, a hole in
            // the floor -- gets walled off; the miner's own openings do not.
            boolean opening = isForeignOpening(ctx, side, dir, wayOut);
            if (!fluid && !opening) continue;
            // Never on top of anybody, and never across the miner's own way
            // out: this is how a miner bricked itself in.
            if (!mayBlock(ctx, side, wayOut)) continue;

            level.setBlock(side, Blocks.COBBLESTONE.defaultBlockState(), 3);
            ctx.mgr().noteBlockChanged(side);
            diary(ctx, "MINE sealed " + (fluid ? "fluid" : "opening")
                    + " at " + side.toShortString());
        }
    }

    /**
     * The block of the mine's own face still standing between the room and the
     * shaft, or {@code null} if the doorway is already open (or cannot be cut
     * without invalidating the room).
     */
    @Nullable
    private BlockPos mouthToOpen(Context ctx, MineBuilding mine) {
        if (plan == null) return null;
        List<BlockPos> closed = new ArrayList<>(MineShaftPlan.TUNNEL_HEIGHT);
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = plan.mouth().above(dy);
            if (unreachable.contains(pos)) continue;
            if (isDiggable(ctx.level().getBlockState(pos))) closed.add(pos);
        }
        if (closed.isEmpty()) return null;
        // Opening the entrance leaves the marked rock and roof above it intact.
        return closed.get(closed.size() - 1); // top down, as with any cell
    }

    /**
     * Whether this cell has fluid the miner has to deal with before going on:
     * any at all where the shaft is still to be dug, but only a standing
     * source in a part already open. Water merely running through open
     * tunnel drains once its leak is plugged (see {@link #scanForLeaks}), and
     * counting it as work turned every wet cell into a skipped one, ending
     * the shift after eight.
     */
    private boolean cellNeedsFluidWork(Context ctx, MineShaftPlan.Cell cell, Set<BlockPos> wayOut) {
        boolean dam = damsFluid(ctx, cell.floor(), wayOut);
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = cell.floor().above(dy);
            if (isProtected(ctx, pos)) continue;
            if (!permitted(ctx, pos)) continue;
            BlockState state = ctx.level().getBlockState(pos);
            if (!isLooseFluid(state)) continue;
            if (dam || (state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether water or lava in the shaft cell holding {@code pos} is filled
     * with cobblestone and then dug out like rock: a cell still to be dug,
     * or any cell that is not on the miner's way back to the mouth.
     *
     * <p>That is how the miner crosses water standing in its tunnel: fill
     * the wet cell in front of it, dig it out -- which seals its walls, roof
     * and floor, and fills the next wet cell -- step into it, and go on in a
     * straight line until the tunnel is dry. Scooping instead left water in
     * a cell open at the head (a cave pool, the edge of a lake, a branch end
     * that had flooded) for the lake to refill, and wet tiles are not
     * walkable, so the miner turned back at the water's edge every shift.
     *
     * <p>Behind it, on its way out, it is never filled -- not even a cell
     * full of water from floor to roof: that is how a miner bricked itself
     * in. There the leak is plugged from outside the tunnel and the rest
     * drains (see {@link #scanForLeaks}). Such a cell used to count as an
     * undug pocket, to be filled, on the way out, where filling is refused:
     * a miner cut off past it tried to fill it every ten seconds for good,
     * and the leak search stopped at it and never saw the real hole beyond.
     */
    private boolean damsFluid(Context ctx, BlockPos pos, Set<BlockPos> wayOut) {
        return !onWayOut(cellFloor(pos), wayOut);
    }

    /**
     * Whether the shaft cell holding {@code pos} is open tunnel: dug out, or
     * full of water or lava on the miner's way out -- it got past it, so it
     * is tunnel that has flooded, not a pocket still to dig.
     */
    private boolean cellIsTunnel(Context ctx, BlockPos pos, Set<BlockPos> wayOut) {
        if (cellHasRock(ctx, pos)) return false;
        return !cellIsAllFluid(ctx, cellFloor(pos)) || onWayOut(cellFloor(pos), wayOut);
    }

    /**
     * Whether {@code pos} is outside the open tunnel, where water or lava
     * coming in is plugged: anything not part of the shaft, and shaft cells
     * still to be dug (rock, or nothing but fluid). The open tunnel itself is
     * never filled in, wet or not: it is what the miner walks on.
     */
    private boolean outsideTunnel(Context ctx, BlockPos pos, Set<BlockPos> wayOut) {
        return !isPartOfShaft(pos) || !cellIsTunnel(ctx, pos, wayOut);
    }

    /**
     * Plugs every block of water or lava touching this open shaft cell from
     * outside the tunnel. The leak stops where it comes in, the cell stays
     * open to walk through, and what is left in it can be scooped or drains.
     *
     * <p>A cave pool or the edge of a lake looks like this when the miner
     * digs up to it: water or lava at the feet, air at the head. It used to
     * scoop the one block, the lake filled it straight back up, and the
     * tunnel behind flooded.
     */
    private void sealCellFromOutside(Context ctx, BlockPos floor, Set<BlockPos> wayOut) {
        ServerLevel level = ctx.level();
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = floor.above(dy);
            for (Direction dir : Direction.values()) {
                BlockPos side = pos.relative(dir);
                if (!outsideTunnel(ctx, side, wayOut) || isProtected(ctx, side)) continue;
                if (!isLooseFluid(level.getBlockState(side)) || !mayBlock(ctx, side, wayOut)) continue;
                level.setBlock(side, Blocks.COBBLESTONE.defaultBlockState(), 3);
                ctx.mgr().noteBlockChanged(side);
                if (isPartOfShaft(side)) replanFromStart = true;
                nextLeakScan = Math.min(nextLeakScan, level.getGameTime() + DRAIN_TICKS);
                diary(ctx, "MINE plugged a leak at " + side.toShortString());
            }
        }
    }

    /**
     * A cell with nothing in it but water or lava, none of it opened by this
     * miner: a pocket the plan runs through, never dug. A cell the miner dug
     * that has since flooded is corridor, and gets scooped, not walled.
     */
    private boolean cellIsAllFluid(Context ctx, BlockPos floor) {
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = floor.above(dy);
            if (dug.contains(pos) || !isLooseFluid(ctx.level().getBlockState(pos))) return false;
        }
        return true;
    }

    /**
     * Whether the shaft cell holding {@code pos} is still to be dug: rock in
     * it, or a pocket of fluid the plan runs through. Every fluid rule asks
     * this one question. The wall step used to ask only about rock, so a
     * lava pocket ahead had its sources scooped instead of being walled, and
     * the flowing lava left behind ran into the cell the miner had just opened.
     */
    private boolean cellStillToDig(Context ctx, BlockPos pos) {
        return cellHasRock(ctx, pos) || cellIsAllFluid(ctx, cellFloor(pos));
    }

    /**
     * Plugs any fluid standing in a cell the shaft is about to run through.
     *
     * <p>Never on a block somebody is standing in, and only a few times per
     * cell per shift: see {@link #MAX_FLUID_FILLS}.
     *
     * @return true if something was filled, so the caller yields the tick
     */
    private boolean fillFluidInCell(Context ctx, MineShaftPlan.Cell cell) {
        int fills = fluidFills.getOrDefault(cell.floor(), 0);
        if (fills >= MAX_FLUID_FILLS) return false;
        ServerLevel level = ctx.level();
        // Filled then dug like rock everywhere but the miner's way out (see
        // damsFluid). A cell on the way out that water is running through was
        // filled the same way, which walled the corridor shut -- with the
        // miner on the far side of the wall whenever the water was behind it.
        // There the source is scooped out instead, the leak is plugged from
        // outside the tunnel (scanForLeaks), and the rest drains.
        Set<BlockPos> wayOut = wayOut(ctx);
        boolean dam = damsFluid(ctx, cell.floor(), wayOut);
        boolean filled = false;
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos pos = cell.floor().above(dy);
            if (isProtected(ctx, pos)) continue;
            if (!permitted(ctx, pos)) continue;
            if (!isLooseFluid(level.getBlockState(pos))) continue;
            if (!dam) {
                if (applyPlug(ctx, pos)) filled = true;
                continue;
            }
            if (!mayBlock(ctx, pos, wayOut)) continue;
            level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
            ctx.mgr().noteBlockChanged(pos);
            diary(ctx, "MINE filled fluid in the shaft at " + pos.toShortString());
            filled = true;
        }
        if (filled) {
            fluidFills.put(cell.floor().immutable(), fills + 1);
            if (fills + 1 >= MAX_FLUID_FILLS) {
                diary(ctx, "MINE fluid keeps coming back at " + cell.floor().toShortString()
                        + ", leaving that cell for now");
            }
        }
        return filled;
    }

    // =========================================================================
    // Leaks, holes and getting out
    // =========================================================================

    /**
     * Water or lava a plug can go over: the fluid itself, or something soft
     * standing in it (glow lichen, seagrass). Not a waterlogged chest, slab or
     * lantern -- cobblestone set over one of those destroyed it and whatever
     * was inside.
     */
    public static boolean isLooseFluid(BlockState state) {
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) return false;
        if (!fluid.is(FluidTags.LAVA) && !fluid.is(FluidTags.WATER)) return false;
        return canPlugOver(state);
    }

    /** A block a plug may replace: air, a fluid, or something soft that belongs to nobody. */
    public static boolean canPlugOver(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return true;
        return state.canBeReplaced() && !state.hasBlockEntity();
    }

    /**
     * Whether any creature is in this block; nothing is ever set on top of
     * one. A body merely pressed against the block's face does not count:
     * a miner shoved into a corner by running water touched the wall it
     * needed to plug, and the plug was refused for good.
     */
    private static boolean occupied(ServerLevel level, BlockPos pos) {
        return !level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).deflate(0.05),
                entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
    }

    /** Whether the shaft cell holding {@code pos} still has rock in it, so is still to be dug. */
    private boolean cellHasRock(Context ctx, BlockPos pos) {
        if (plan == null) return false;
        BlockPos floor = cellFloor(pos);
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            if (isDiggable(ctx.level().getBlockState(floor.above(dy)))) return true;
        }
        return false;
    }

    /** The feet-level block of the shaft cell holding {@code pos}. */
    private BlockPos cellFloor(BlockPos pos) {
        if (plan == null) return pos;
        int height = Math.max(0, Math.min(MineShaftPlan.TUNNEL_HEIGHT - 1,
                pos.getY() - plan.mouth().getY()));
        return pos.below(height);
    }

    /**
     * Inside the mine's rock, past the doorway: where a miner should only ever
     * be on the corridor floor.
     *
     * @param minAlong 1 for past the doorway, 0 to count the doorway itself
     */
    private boolean insideMine(BlockPos pos, int minAlong) {
        if (plan == null) return false;
        Direction dir = plan.direction();
        Direction side = dir.getClockWise();
        BlockPos mouth = plan.mouth();
        int dx = pos.getX() - mouth.getX();
        int dz = pos.getZ() - mouth.getZ();
        int along = dx * dir.getStepX() + dz * dir.getStepZ();
        int lateral = dx * side.getStepX() + dz * side.getStepZ();
        int dy = pos.getY() - mouth.getY();
        return along >= minAlong && along <= MineShaftPlan.MAX_MAIN_LENGTH + 1
                && Math.abs(lateral) <= plan.widest() + 2
                && dy >= -32 && dy <= MineShaftPlan.TUNNEL_HEIGHT + 2;
    }

    /**
     * Sets the block, or scoops the fluid out of it, as a plug calls for.
     *
     * <p>On the miner's way out, a standing source is removed outright:
     * cobblestone there would close the corridor behind it. Flowing fluid is
     * left to drain once nothing feeds it. Anywhere else -- the walls, the
     * roof, under the floor, shaft cells ahead of the miner -- it is
     * cobblestone, never on top of anybody. A shaft cell filled this way is
     * dug out again like rock.
     *
     * @return whether anything changed
     */
    private boolean applyPlug(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        if (isProtected(ctx, pos)) return false;
        BlockState state = level.getBlockState(pos);
        Set<BlockPos> wayOut = wayOut(ctx);
        boolean inShaft = isPartOfShaft(pos);
        if (scoopRatherThanPlug(ctx, pos, wayOut)) {
            if (!(state.getBlock() instanceof LiquidBlock) || !state.getFluidState().isSource()) {
                return false;
            }
            boolean lava = state.getFluidState().is(FluidTags.LAVA);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            ctx.mgr().noteBlockChanged(pos);
            level.playSound(null, pos, lava ? SoundEvents.BUCKET_FILL_LAVA : SoundEvents.BUCKET_FILL,
                    SoundSource.BLOCKS, 0.8f, 1.0f);
            diary(ctx, "MINE scooped " + (lava ? "lava" : "water") + " out of the shaft at "
                    + pos.toShortString());
            return true;
        }
        // In the shaft only fluid is walled: open air there is the corridor.
        if (inShaft && !isLooseFluid(state)) return false;
        if (!canPlugOver(state) || !mayBlock(ctx, pos, wayOut)) return false;
        String what = state.getFluidState().is(FluidTags.LAVA) ? "lava"
                : !state.getFluidState().isEmpty() ? "water" : "a hole";
        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
        ctx.mgr().noteBlockChanged(pos);
        level.playSound(null, pos, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
        diary(ctx, "MINE plugged " + what + " at " + pos.toShortString());
        if (inShaft) replanFromStart = true;
        return true;
    }

    /** Whether a queued plug still has something to do. */
    private boolean needsPlug(Context ctx, BlockPos pos) {
        BlockState state = ctx.level().getBlockState(pos);
        if (scoopRatherThanPlug(ctx, pos, wayOut(ctx))) {
            return state.getBlock() instanceof LiquidBlock && state.getFluidState().isSource();
        }
        if (isPartOfShaft(pos)) return isLooseFluid(state);
        return canPlugOver(state);
    }

    /**
     * Where fluid is scooped out rather than walled in: the miner's own way
     * back to the mouth, or outside the shaft a block it broke this shift.
     * Cobblestone in either closes a way somebody needs; the leak feeding it
     * is plugged from the outside instead (see {@link #outsideTunnel}). Shaft
     * cells ahead of the miner are filled and dug through: {@link #damsFluid}.
     */
    private boolean scoopRatherThanPlug(Context ctx, BlockPos pos, Set<BlockPos> wayOut) {
        if (isPartOfShaft(pos)) return !damsFluid(ctx, pos, wayOut);
        return isOwnFluidSpace(pos, wayOut);
    }

    /** Outside the shaft, a fluid block that is in the miner's own opening or its way out. */
    private boolean isOwnFluidSpace(BlockPos pos, Set<BlockPos> wayOut) {
        return dug.contains(pos) || onWayOut(pos, wayOut);
    }

    /**
     * The floor cells between the miner and the mouth (see
     * {@link #routeToMouth}), when it is inside the mine; empty otherwise.
     */
    private Set<BlockPos> wayOut(Context ctx) {
        if (plan == null) return Set.of();
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        if (!insideMine(feet, 1)) return Set.of();
        return new java.util.HashSet<>(routeToMouth(feet));
    }

    /** Whether {@code pos} is either block of a floor cell on the way out. */
    private boolean onWayOut(BlockPos pos, Set<BlockPos> wayOut) {
        if (wayOut.isEmpty() || plan == null) return false;
        int floorY = plan.mouth().getY();
        int height = pos.getY() - floorY;
        if (height < 0 || height >= MineShaftPlan.TUNNEL_HEIGHT) return false;
        return wayOut.contains(pos.below(height));
    }

    /**
     * Whether a block may be set here at all: not part of a building, nobody
     * standing in it, and not across the miner's way back to the mouth. Every
     * cobblestone the miner sets asks this first.
     */
    private boolean mayBlock(Context ctx, BlockPos pos, Set<BlockPos> wayOut) {
        return !isProtected(ctx, pos)
                && !occupied(ctx.level(), pos)
                && !onWayOut(pos, wayOut);
    }

    /**
     * Whether open air beside the shaft is a way into somewhere else, to be
     * walled off.
     *
     * <p>The old test was "air the nav graph has no node at". Nodes sit where
     * feet go, never at head height, so the head room of every side tunnel the
     * miner had dug itself -- an ore pocket, an ore-sense tunnel, an old
     * branch end -- passed as a hole into a cave and was bricked up, leaving a
     * one-block slot nobody can walk through. A block opened a moment ago had
     * no node yet either. Bricking up the slot it was standing in, or the one
     * behind it, is how a miner walled itself in.
     *
     * <p>Now: a hole in the corridor floor is always filled. Otherwise it is
     * left open if the miner dug it this shift, if a villager can stand there
     * or it is the head room over such a spot, if it is on the miner's way
     * out, or if it is a small closed pocket rather than the start of a cave.
     */
    private boolean isForeignOpening(Context ctx, BlockPos pos, Direction dir, Set<BlockPos> wayOut) {
        ServerLevel level = ctx.level();
        if (!level.getBlockState(pos).isAir()) return false;
        // Its own openings stay open, an ore hole under the floor included.
        // Capping that hole as the vein went on down meant mining the rest of
        // the vein through the cap, and a miner that had dropped into the
        // hole had it closed over its head. backfillPits fills it from the
        // bottom once the vein is done.
        if (dug.contains(pos) || onWayOut(pos, wayOut)) return false;
        // Under the corridor floor: a hole to fall into, whatever it leads to.
        if (isPartOfShaft(pos.above())) return true;
        // Open sky over a cut that has come out of the hillside: a roof there
        // is not the miner's to build.
        if (dir == Direction.UP && level.canSeeSky(pos)) return false;
        if (isWalkway(ctx, pos)) return false;
        return leadsSomewhere(ctx, pos);
    }

    /** A tile a villager can stand on, or the head room over one. */
    public static boolean isWalkway(Context ctx, BlockPos pos) {
        return isWalkable(ctx, pos) || isWalkable(ctx, pos.below());
    }

    /**
     * Whether the open space behind {@code start} goes anywhere: more than
     * {@link #CAVE_AIR_LIMIT} open blocks, or out under the sky. Counted
     * without passing through the shaft or anything the miner dug, so an old
     * ore pocket off the corridor reads as the dead end it is.
     */
    private boolean leadsSomewhere(Context ctx, BlockPos start) {
        ServerLevel level = ctx.level();
        Set<BlockPos> seen = new java.util.HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        seen.add(start);
        open.add(start);
        while (!open.isEmpty()) {
            BlockPos pos = open.poll();
            if (level.canSeeSky(pos)) return true;
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (seen.contains(next) || isPartOfShaft(next) || dug.contains(next)) continue;
                if (!level.isLoaded(next)) return true;
                if (!level.getBlockState(next).getCollisionShape(level, next).isEmpty()) continue;
                seen.add(next);
                if (seen.size() > CAVE_AIR_LIMIT) return true;
                open.add(next);
            }
        }
        return false;
    }

    /** The timed plug's end: the block is set, and a bridged gap gets its walls. */
    private void plug(Context ctx, BlockPos pos) {
        if (!mayPlugNow(ctx, pos)) {
            unreachable.add(pos);
            return;
        }
        if (!applyPlug(ctx, pos)) {
            // Set aside for the shift, and said why. A plug that did nothing
            // used to be found again by the next leak check and tried again,
            // every ten seconds, with nothing in the diary to show for it.
            String why = occupied(ctx.level(), pos) ? "somebody is in it"
                    : onWayOut(pos, wayOut(ctx)) ? "it is on the way out"
                    : "nothing there to plug or scoop";
            diary(ctx, "MINE could not plug " + pos.toShortString() + ": " + why);
            unreachable.add(pos);
            return;
        }
        skipsWithoutProgress = 0;
        workedThisSession = true;
        // Look again once the water has had time to drain, so the village
        // hears about the tiles that dried out (recheckNav) without waiting
        // out the usual ten seconds.
        nextLeakScan = Math.min(nextLeakScan, ctx.level().getGameTime() + DRAIN_TICKS);
        lastRescueAction = ctx.clock();
        // The floor under a shaft cell: the shaft is crossing a cave, so the
        // cell it carries is open to it on the sides and above as well.
        if (plan != null && !isPartOfShaft(pos) && plan.contains(pos.above())) {
            queueWalls(ctx, pos.above());
        }
    }

    /** Queues the open sides and roof of a shaft cell that crosses a cave. */
    private void queueWalls(Context ctx, BlockPos floor) {
        ServerLevel level = ctx.level();
        Set<BlockPos> wayOut = wayOut(ctx);
        for (int dy = 0; dy <= MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            BlockPos at = floor.above(dy);
            // Sides at feet and head height, and the roof over the cell.
            for (Direction dir : dy < MineShaftPlan.TUNNEL_HEIGHT
                    ? Direction.Plane.HORIZONTAL.stream().toList() : List.of(Direction.UP)) {
                BlockPos pos = dy < MineShaftPlan.TUNNEL_HEIGHT ? at.relative(dir) : at;
                if (isPartOfShaft(pos) || detourCells.contains(pos)) continue;
                if (isProtected(ctx, pos)) continue;
                BlockState state = level.getBlockState(pos);
                boolean fluid = isLooseFluid(state);
                if (!fluid && !permitted(ctx, pos)) continue;
                boolean leak = fluid && !isOwnFluidSpace(pos, wayOut);
                if (leak || isForeignOpening(ctx, pos, dir, wayOut)) addRepair(pos);
            }
        }
    }

    private void addRepair(BlockPos pos) {
        if (repairs.size() >= MAX_REPAIRS || unreachable.contains(pos) || repairs.contains(pos)) return;
        repairs.add(pos.immutable());
    }

    /**
     * Looks over the open tunnel for water or lava getting in, and queues the
     * fix where it comes in: a plug outside the tunnel -- in the wall, roof
     * or floor, or in a shaft cell still to be dug -- so the tunnel itself
     * stays open to walk through. A source standing in the tunnel is scooped
     * out, a missing floor is filled, and the rest drains once nothing feeds
     * it.
     *
     * <p>The miner used to look only at the cell it was about to dig. Water
     * that got in anywhere behind it -- through the roof, from a cave, from
     * before the walls were sealed -- stayed for good, ran out of the mouth,
     * knocked the torches off the floor, and pushed everybody walking the
     * corridor about.
     *
     * <p>The open tunnel is every shaft cell that can be walked to from the
     * mouth through other open cells. The scan used to stop at the first cell
     * in dig order with rock in it, and dig order takes a branch before the
     * corridor past it, so one branch closed at its root hid the rest of the
     * mine -- including the branch end a lake was pouring in through.
     *
     * <p>It also tells the village about cells whose water has come or gone
     * (see {@link #recheckNav}). At most every {@link #LEAK_SCAN_TICKS}; a
     * few block reads a cell.
     */
    private void scanForLeaks(Context ctx) {
        if (plan == null) return;
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        if (now < nextLeakScan) return;
        nextLeakScan = now + LEAK_SCAN_TICKS;
        int before = repairs.size();
        NavGraph graph = ctx.mgr().getGraph();
        Set<BlockPos> wayOut = wayOut(ctx);
        Set<BlockPos> wetNow = new java.util.HashSet<>();
        Set<BlockPos> seen = new java.util.HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        BlockPos mouth = plan.mouth();
        if (level.isLoaded(mouth) && cellIsTunnel(ctx, mouth, wayOut)) {
            seen.add(mouth);
            open.add(mouth);
        }
        while (!open.isEmpty()) {
            BlockPos floor = open.poll();
            boolean wet = false;
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos pos = floor.above(dy);
                BlockState state = level.getBlockState(pos);
                if (isLooseFluid(state)) {
                    wet = true;
                    if (state.getFluidState().isSource()) addRepair(pos);
                }
                // Where it comes in: fluid touching the tunnel from outside
                // it, in the neighbouring mine's rock as well (mayPlugNow).
                for (Direction dir : Direction.values()) {
                    BlockPos side = pos.relative(dir);
                    if (!outsideTunnel(ctx, side, wayOut) || isProtected(ctx, side)) continue;
                    if (isLooseFluid(level.getBlockState(side))) addRepair(side);
                }
            }
            if (wet) wetNow.add(floor);
            recheckNav(ctx, graph, floor, wet);
            BlockPos gap = missingFloor(ctx, floor);
            if (gap != null) addRepair(gap);
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = floor.relative(dir);
                if (!plan.contains(next) || !seen.add(next)) continue;
                if (!level.isLoaded(next)) continue;
                if (isProtected(ctx, next)
                        || isProtected(ctx, next.above())) continue;
                // Rock, or nothing but fluid ahead: outside the tunnel, and
                // not walked through. Its fluid was queued above, as a leak.
                // Flooded on the miner's way out, it is tunnel, and the real
                // leak may be past it (cellIsTunnel).
                if (!cellIsTunnel(ctx, next, wayOut)) continue;
                open.add(next);
            }
        }
        wetCells.clear();
        wetCells.addAll(wetNow);
        if (repairs.size() > before) {
            diary(ctx, "MINE found " + (repairs.size() - before) + " leaks or holes in the shaft to fix");
        }
    }

    /**
     * Tells the village to look again at an open cell whose water has come
     * or gone since the last check, or that is dry and has no nav tile.
     *
     * <p>The village hears about blocks that players and villagers change,
     * not water spreading or draining by itself. A cell it last saw wet
     * stayed "not walkable" after the water had gone, so the miner could not
     * walk back down its own dry tunnel -- or out of the dry end of a branch
     * it had been cut off in.
     */
    private void recheckNav(Context ctx, @Nullable NavGraph graph, BlockPos floor, boolean wet) {
        boolean changed = wet != wetCells.contains(floor);
        // Once a shift for a dry cell with no tile: some never get one
        // (no floor under them), and asking every ten seconds is a rebuild
        // every ten seconds.
        boolean stale = !wet && graph != null
                && graph.nodeAt(floor.getX(), floor.getY(), floor.getZ()) == null
                && navRechecked.add(floor.immutable());
        if (changed || stale) ctx.mgr().noteBlockChanged(floor);
    }

    /** The queued repair nearest the miner, if it is in plug reach of {@code feet}; taken off the queue. */
    @Nullable
    private BlockPos repairInReach(Context ctx, BlockPos feet) {
        BlockPos best = nextRepair(ctx);
        if (best == null || best.distSqr(feet) <= PLUG_REACH_SQ) return best;
        repairs.addFirst(best);
        return null;
    }

    /** The queued repair nearest the miner that still needs doing, taken off the queue. */
    @Nullable
    private BlockPos nextRepair(Context ctx) {
        repairs.removeIf(pos -> unreachable.contains(pos) || !needsPlug(ctx, pos));
        BlockPos at = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : repairs) {
            double d = pos.distSqr(at);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        if (best != null) repairs.remove(best);
        return best;
    }

    /**
     * Fills the ore holes under the floor once the vein is done, deepest
     * first. A hole with somebody in it, or anywhere down it, is left for the
     * next pass; the climb in {@link #rescueIfTrapped} fills that one as the
     * miner comes up.
     *
     * @return whether anything was filled
     */
    private boolean backfillPits(Context ctx) {
        if (pits.isEmpty()) return false;
        ServerLevel level = ctx.level();
        List<BlockPos> order = new ArrayList<>(pits);
        order.sort(Comparator.comparingInt(BlockPos::getY));
        int filled = 0;
        for (BlockPos pos : order) {
            if (!canPlugOver(level.getBlockState(pos))) {
                pits.remove(pos);   // filled already: sealed off, or a gravel fall
                continue;
            }
            // Not over anybody further down: a miner two deep has its head a
            // block below the top of the hole, and the top was filled on it.
            if (isProtected(ctx, pos) || occupiedDownFrom(level, pos)) continue;
            level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
            ctx.mgr().noteBlockChanged(pos);
            pits.remove(pos);
            filled++;
        }
        if (filled > 0) {
            level.playSound(null, ctx.npc().blockPosition(), SoundEvents.STONE_PLACE,
                    SoundSource.BLOCKS, 0.8f, 1.0f);
            diary(ctx, "MINE filled " + filled + " ore holes back in under the floor");
        }
        return filled > 0;
    }

    /** Whether any creature is in this block or further down the open hole under it. */
    private static boolean occupiedDownFrom(ServerLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos at = pos.mutable();
        for (int depth = 0; depth <= MAX_CLIMB_DEPTH + 2; depth++, at.move(Direction.DOWN)) {
            if (depth > 0 && !level.getBlockState(at).getCollisionShape(level, at).isEmpty()) return false;
            if (occupied(level, at)) return true;
        }
        return false;
    }

    /**
     * What the miner does about being somewhere bad, every tick, inside its
     * mine: stuck inside a block (a gravel fall, a block set on it), it digs
     * the block out; standing in lava, it scoops out the lava it is in and
     * walls off the lava around it. Neither used to happen at all, and a
     * villager has no other way out of either.
     *
     * <p>Floating in a hole of water in the tunnel floor, it steps up and
     * fills the hole. The swim-out cannot lift a villager up a one-block
     * ledge -- it bobs against it -- and a miner swam in one for minutes,
     * with the walk it was on never timing out. Its feet read as on the
     * floor while it floats, so the climb out of holes never saw it.
     */
    private void tickSafety(Context ctx) {
        if (plan == null) return;
        NpcEntity npc = ctx.npc();
        BlockPos feet = npc.blockPosition();
        if (!insideMine(feet, 0) || !inOwnShaft(ctx, feet)) return;
        if (npc.isInWall()) {
            digOutOf(ctx, BlockPos.containing(npc.getEyePosition()));
            digOutOf(ctx, feet);
        }
        if (npc.isInLava() && npc.tickCount % 5 == 0) douseLava(ctx, feet);
        if (npc.isInWater() && feet.getY() < plan.mouth().getY()
                && isPartOfShaft(feet.above()) && !ctx.level().canSeeSky(feet.above())) {
            if (move != null) {
                move.stop(ctx);
                move = null;
            }
            stepTicks = -1;
            diary(ctx, "MINE climbing out of a water hole in the floor at " + feet.toShortString());
            climbOneBlock(ctx, feet);
        }
    }

    /** Breaks a block the miner is stuck inside, keeping what it drops. */
    private void digOutOf(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        if (!state.isSuffocating(level, pos)) return;
        if (!isDiggable(state) || isProtected(ctx, pos)) return;
        keepDrops(ctx, yieldOf(ctx, pos, state, state, ItemStack.EMPTY));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        ctx.mgr().noteBlockChanged(pos);
        dug.add(pos.immutable());
        lastRescueAction = ctx.clock();
        level.playSound(null, pos, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.7f, 1.0f);
        diary(ctx, "MINE dug itself out of " + state.getBlock().getName().getString()
                + " at " + pos.toShortString());
    }

    private void keepDrops(Context ctx, List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            ItemStack owned = com.alex.test.village.tokens.VillageItemOwnership.produce(drop, ctx.mgr());
            if (!owned.isEmpty()) ctx.npc().getInventory().addItem(owned);
        }
    }

    /**
     * Lava in or around the miner: scooped out of its own body and its way
     * out, walled off everywhere else.
     */
    private void douseLava(Context ctx, BlockPos feet) {
        ServerLevel level = ctx.level();
        Set<BlockPos> wayOut = wayOut(ctx);
        int changed = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (!state.getFluidState().is(FluidTags.LAVA)) continue;
                    if (isProtected(ctx, pos)) continue;
                    boolean inBody = dx == 0 && dz == 0 && (dy == 0 || dy == 1);
                    // Scooped where it stands and on its way out; walled
                    // ahead of it, where scooping a lake's edge only lets
                    // the next block of lava run in.
                    if (inBody || scoopRatherThanPlug(ctx, pos, wayOut)) {
                        if (!(state.getBlock() instanceof LiquidBlock)) continue;
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    } else {
                        if (!canPlugOver(state) || !mayBlock(ctx, pos, wayOut)) continue;
                        level.setBlock(pos, Blocks.COBBLESTONE.defaultBlockState(), 3);
                        if (isPartOfShaft(pos)) replanFromStart = true;
                    }
                    ctx.mgr().noteBlockChanged(pos);
                    changed++;
                }
            }
        }
        if (changed > 0) {
            level.playSound(null, feet, SoundEvents.LAVA_EXTINGUISH, SoundSource.BLOCKS, 0.8f, 1.0f);
            diary(ctx, "MINE put out lava around itself at " + feet.toShortString());
            nextLeakScan = 0;   // find where it came from
        }
    }

    /**
     * Gets a miner out of a place it cannot walk out of, inside its own mine.
     *
     * <p>The miner used to have no answer to this. Every walk failed, the
     * shift ended, the deposit trip failed at every chest in turn, and it
     * stood -- or jumped -- in the same spot until somebody dug it out. What
     * it does now, in order:
     * <ol>
     *   <li>Down a hole (below the corridor floor): climbs out, setting a
     *       block under itself each step, which also fills the hole.</li>
     *   <li>On the corridor floor with the way out closed: digs back toward
     *       the doorway along the corridor, and bridges any gap on the way.</li>
     *   <li>Still stuck after a minute, or fallen too deep to climb: put back
     *       at the mine entrance, with a diary line saying so.</li>
     * </ol>
     *
     * @return true if it is busy getting out, so nothing else runs this tick
     */
    private boolean rescueIfTrapped(Context ctx) {
        if (plan == null) return false;
        ServerLevel level = ctx.level();
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        if (!insideMine(feet, 1) || !inOwnShaft(ctx, feet)) {
            trappedSince = -1;
            return false;
        }
        long now = ctx.clock(); // time paused for a player's screen is not time stuck
        int floorY = plan.mouth().getY();

        // Under open sky it is the hillside outside, not a hole.
        if (feet.getY() < floorY && !level.canSeeSky(feet.above())) {
            // Below the floor but with a walk out from where it stands -- a
            // cave the shaft crossed, a stair -- is not a hole. The deep case
            // used to teleport at once without asking.
            if (trappedSince < 0 && canWalkOut(ctx, feet)) return false;
            if (feet.getY() < floorY - MAX_CLIMB_DEPTH) {
                return putBackAtEntrance(ctx, "fell too deep to climb out");
            }
            markTrapped(ctx, now, feet, "is down a hole");
            if (now - trappedSince >= RESCUE_GIVE_UP_TICKS
                    || now - lastRescueAction >= RESCUE_IDLE_GIVE_UP_TICKS) {
                return putBackAtEntrance(ctx, "could not climb out of a hole");
            }
            if (rescueCooldown-- > 0) return true;
            rescueCooldown = RESCUE_STEP_TICKS;
            climbOneBlock(ctx, feet);
            return true;
        }

        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return false;
        if (trappedSince < 0 && now < nextTrapCheck) return false;
        nextTrapCheck = now + TRAP_CHECK_TICKS;
        BlockPos exit = MineBuilding.approachTiles(graph, plan.mouth(), plan.direction())
                .iterator().next();
        // No node at the entrance: nothing to judge "out" by, so no rescue.
        if (graph.nodeAt(exit.getX(), exit.getY(), exit.getZ()) == null) return false;
        if (NavReachability.canReach(graph, feet, exit)) {
            if (trappedSince >= 0) diary(ctx, "MINE the way out is open again");
            trappedSince = -1;
            return false;
        }
        markTrapped(ctx, now, feet, "cannot walk out of the mine");
        if (now - trappedSince >= RESCUE_GIVE_UP_TICKS) {
            return putBackAtEntrance(ctx, "could not dig a way out");
        }
        if (now - lastRescueAction >= RESCUE_IDLE_GIVE_UP_TICKS) {
            return putBackAtEntrance(ctx, "had nothing left to dig or plug for "
                    + RESCUE_IDLE_GIVE_UP_TICKS / 20 + " s");
        }
        if (rescueCooldown-- > 0) return true;
        rescueCooldown = RESCUE_STEP_TICKS;
        // Cut off by water rather than rock: wet tiles are not walkable, and a
        // miner stranded on the last dry one had nothing to dig and waited
        // out the minute. The leak feeding the water is often in plug reach
        // from where it stands; once it is plugged the tunnel drains.
        scanForLeaks(ctx);
        BlockPos leak = repairInReach(ctx, feet);
        if (leak != null) return rescueAt(ctx, feet, leak, true);
        return digTowardMouth(ctx, feet);
    }

    /**
     * The tile in front of the mine, when the miner is down its own tunnel
     * and has some way to go to get out; null otherwise, and when the nav
     * graph has no tile there to walk to.
     */
    @Nullable
    private BlockPos exitIfDownTunnel(Context ctx) {
        if (plan == null) return null;
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        if (!insideMine(feet, 3) || !inOwnShaft(ctx, feet)) return null;
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos exit = MineBuilding.approachTiles(graph, plan.mouth(), plan.direction())
                .iterator().next();
        if (graph.nodeAt(exit.getX(), exit.getY(), exit.getZ()) == null) return null;
        diary(ctx, "MINE walking out of the tunnel before the trip to storage");
        return exit;
    }

    /**
     * Whether the miner can walk from {@code feet} to the mine entrance.
     *
     * <p>Only a tile with a nav node of its own counts. A pit dug a moment
     * ago has none yet, and the reach test would match the miner to the
     * corridor tile above it and call it free -- and a miner that is really
     * down a hole would then never climb out.
     */
    private boolean canWalkOut(Context ctx, BlockPos feet) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || plan == null) return false;
        if (graph.nodeAt(feet.getX(), feet.getY(), feet.getZ()) == null) return false;
        BlockPos exit = MineBuilding.approachTiles(graph, plan.mouth(), plan.direction())
                .iterator().next();
        return graph.nodeAt(exit.getX(), exit.getY(), exit.getZ()) != null
                && NavReachability.canReach(graph, feet, exit);
    }

    private void markTrapped(Context ctx, long now, BlockPos feet, String what) {
        if (trappedSince >= 0) return;
        trappedSince = now;
        lastRescueAction = now;
        rescueCooldown = 0;
        nextLeakScan = 0;   // water may be what it is cut off by: look now
        diary(ctx, "MINE " + what + " at " + feet.toShortString() + ", getting out");
    }

    /**
     * One step up out of a hole: the block over its head is dug if need be,
     * then it rises a block and sets cobblestone where its feet were.
     */
    private void climbOneBlock(Context ctx, BlockPos feet) {
        ServerLevel level = ctx.level();
        BlockPos overHead = feet.above(2);
        BlockState over = level.getBlockState(overHead);
        if (!over.getCollisionShape(level, overHead).isEmpty()) {
            // Dig it this step, rise on the next.
            if (isDiggable(over) && !isProtected(ctx, overHead)) {
                lastRescueAction = ctx.clock();
                keepDrops(ctx, yieldOf(ctx, overHead, over, over, ItemStack.EMPTY));
                level.setBlock(overHead, Blocks.AIR.defaultBlockState(), 3);
                ctx.mgr().noteBlockChanged(overHead);
                dug.add(overHead.immutable());
                level.playSound(null, overHead, SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 0.7f, 1.0f);
            }
            return;
        }
        // Not into a chunk still loading (FarAwake): this step is skipped, the next one tries again.
        if (!com.alex.test.village.farawake.FarAwake.mayPlace(level, feet.getX() + 0.5, feet.getZ() + 0.5,
                "miner climbing out of a pit")) return;
        lastRescueAction = ctx.clock();
        ctx.model().stopMoving();
        ctx.npc().teleportTo(feet.getX() + 0.5, feet.getY() + 1, feet.getZ() + 0.5);
        if (canPlugOver(level.getBlockState(feet))) {
            level.setBlock(feet, Blocks.COBBLESTONE.defaultBlockState(), 3);
            ctx.mgr().noteBlockChanged(feet);
            level.playSound(null, feet, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.8f, 1.0f);
        }
        pits.remove(feet);
    }

    /**
     * Digs or bridges the first thing between the miner and the doorway,
     * following the corridor: across to it along the branch the miner is in,
     * then back down it to the mouth.
     *
     * @return true always: while trapped, waiting is the only other choice
     */
    private boolean digTowardMouth(Context ctx, BlockPos feet) {
        ServerLevel level = ctx.level();
        for (BlockPos step : routeToMouth(feet)) {
            for (int dy = MineShaftPlan.TUNNEL_HEIGHT - 1; dy >= 0; dy--) {
                BlockPos pos = step.above(dy);
                BlockState state = level.getBlockState(pos);
                if (state.getCollisionShape(level, pos).isEmpty()) continue;
                if (!isDiggable(state) || isProtected(ctx, pos) || !permitted(ctx, pos)) {
                    continue;
                }
                return rescueAt(ctx, feet, pos, false);
            }
            BlockPos below = step.below();
            if (canPlugOver(level.getBlockState(below)) && !level.canSeeSky(step)
                    && !isProtected(ctx, below) && permitted(ctx, below)) {
                return rescueAt(ctx, feet, below, true);
            }
        }
        // Nothing in the way that can be dug: the nav graph may just not have
        // caught up with the last block. Wait; the minute's limit still holds.
        return true;
    }

    /** Works on one block of the way out: from here if in reach, else from the nearest tile it can walk to. */
    private boolean rescueAt(Context ctx, BlockPos feet, BlockPos target, boolean plug) {
        if (plug ? feet.distSqr(target) <= PLUG_REACH_SQ : canSwingAt(ctx.npc(), target)) {
            face = target;
            facePlug = plug;
            beginWork(ctx);
            return true;
        }
        BlockPos stand = standNear(ctx, target, plug);
        if (stand != null) workAt(ctx, target, stand, plug);
        return true;
    }

    /** Floor cells from the miner back to the doorway: across to the corridor, then along it. */
    private List<BlockPos> routeToMouth(BlockPos feet) {
        List<BlockPos> route = new ArrayList<>();
        Direction dir = plan.direction();
        Direction side = dir.getClockWise();
        BlockPos mouth = plan.mouth();
        int dx = feet.getX() - mouth.getX();
        int dz = feet.getZ() - mouth.getZ();
        int along = Math.max(0, Math.min(MineShaftPlan.MAX_MAIN_LENGTH,
                dx * dir.getStepX() + dz * dir.getStepZ()));
        int lateral = dx * side.getStepX() + dz * side.getStepZ();
        for (int l = lateral; l != 0; l -= Integer.signum(lateral)) {
            route.add(mouth.relative(dir, along).relative(side, l));
        }
        for (int a = along; a >= 0; a--) route.add(mouth.relative(dir, a));
        return route;
    }

    /** Last resort: back to the tile in front of the mine, with the reason in the diary. */
    private boolean putBackAtEntrance(Context ctx, String why) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || plan == null) return false;
        BlockPos exit = MineBuilding.approachTiles(graph, plan.mouth(), plan.direction())
                .iterator().next();
        // The entrance's chunk still loading (FarAwake): the miner waits where it is, busy
        // getting out, and is put back once that ground is there.
        if (!com.alex.test.village.farawake.FarAwake.mayPlace(ctx.level(), exit.getX() + 0.5, exit.getZ() + 0.5,
                "miner put back at the mine entrance")) return true;
        BlockPos from = ctx.npc().blockPosition();
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
        face = null;
        facePlug = false;
        workTimer = 0;
        ctx.model().stopMoving();
        ctx.npc().teleportTo(exit.getX() + 0.5, NavGraph.standY(ctx.level(), exit), exit.getZ() + 0.5);
        diary(ctx, "MINE " + why + " at " + from.toShortString() + ", back at the entrance");
        trappedSince = -1;
        return true;
    }

    /**
     * Whether the miner at {@code feet} is in its shaft or right beside it: a
     * planned cell within {@link #NEAR_SHAFT_XZ} blocks across, or a block it
     * dug itself, and not inside a building's room.
     *
     * <p>{@link #insideMine} alone is a box ninety-seven blocks long and as
     * wide as the branches, and it takes in whatever sits in line with the
     * shaft: a house over the hill, a cellar, the shade of a tree. A miner
     * starting its shift in one of those was "down a hole" by the box alone
     * and climbed out of it -- breaking the block over its head and setting
     * cobblestone under its feet, in somebody's house.
     *
     * <p>And only in this mine's share of the rock (see {@link
     * MineReservations}). The planned-cell test looks at the plan's floor
     * level only, so a miner holding one mine while standing in another
     * mine's tunnel, under or beside the end of its own, read as fallen down
     * a hole in its own mine and was teleported to that mine's entrance. That
     * happens when two miners swap mines after a break, or when a miner walks
     * through a tunnel that has broken into the other.
     */
    private boolean inOwnShaft(Context ctx, BlockPos feet) {
        if (plan == null || isProtected(ctx, feet) || !ownedByMine(feet)) return false;
        if (dug.contains(feet) || dug.contains(feet.above()) || pits.contains(feet)) return true;
        int floorY = plan.mouth().getY();
        for (int dx = -NEAR_SHAFT_XZ; dx <= NEAR_SHAFT_XZ; dx++) {
            for (int dz = -NEAR_SHAFT_XZ; dz <= NEAR_SHAFT_XZ; dz++) {
                if (isPartOfShaft(new BlockPos(feet.getX() + dx, floorY, feet.getZ() + dz))) return true;
            }
        }
        return false;
    }

    /**
     * Whether breaking {@code pos} digs a hole under the miner: straight below
     * its feet and below the corridor floor. Dropping from a block back down
     * to the corridor floor is fine; going under the floor is how it fell
     * into its own vein holes.
     */
    private boolean digsOwnHole(Context ctx, BlockPos pos) {
        BlockPos feet = ctx.npc().blockPosition();
        return pos.getX() == feet.getX() && pos.getZ() == feet.getZ() && pos.getY() < feet.getY()
                && (plan == null || pos.getY() < plan.mouth().getY());
    }

    /** Whether some face of this block is open, so a pickaxe can get at it without going through rock. */
    private static boolean hasOpenFace(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (level.getBlockState(next).getCollisionShape(level, next).isEmpty()) return true;
        }
        return false;
    }

    /** Whether open air beside this block reaches the sky: ore on a slope or a cliff, not in the mine's rock. */
    private static boolean openToSky(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos next = pos.relative(dir);
            if (level.getBlockState(next).isAir() && level.canSeeSky(next)) return true;
        }
        return false;
    }

    /** Whether this position is one the shaft plan means to open anyway. */
    private boolean isPartOfShaft(BlockPos pos) {
        if (plan == null) return false;
        if (plan.contains(pos)) return true;
        // The doorway cut through the room's face belongs to the shaft too,
        // and walling it up again would seal the miner out of its own mine.
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            if (plan.mouth().above(dy).equals(pos)) return true;
        }
        return false;
    }

    /**
     * Queues any ore touching the block just taken, up to the skill's vein
     * length (6 blocks at level 1, 24 from level 75). A novice leaves the
     * rest of a big vein in the wall.
     */
    private void collectVein(Context ctx, BlockPos pos) {
        int limit = MinerBalance.veinBlocks(ctx.brain().minerSkillLevel());
        if (veinTaken + vein.size() >= limit) return;
        for (Direction dir : Direction.values()) {
            BlockPos side = pos.relative(dir);
            // Ore in the shaft's own path is the plan's to dig, head first
            // from the tile behind it. Queued as vein ore, the head of the
            // next cell was tried before that tile was open, had nowhere to
            // stand, and was set aside -- and the plan skips what is set
            // aside, so the cell was left with its ore standing, one block
            // high, and the tunnel stopped there.
            if (isPartOfShaft(side)) continue;
            if (isProtected(ctx, side)) continue;
            if (!permitted(ctx, side)) continue;
            if (!isOre(ctx.level().getBlockState(side))) continue;
            // Only the mine's own rock. A vein by the doorway led the miner
            // back out, under the mine room and up the hillside, and every
            // ore it broke in the open got cobblestone set in the air beside it.
            if (!insideMine(side, 1) || openToSky(ctx.level(), side)) continue;
            if (vein.contains(side) || detour.contains(side) || unreachable.contains(side)) continue;
            vein.add(side);
            if (veinTaken + vein.size() >= limit) return;
        }
    }

    /**
     * The next queued ore block that is still ore, dropping any that is not
     * or that was set aside. The set-aside check matters: one vein block the
     * miner had nowhere to stand for stayed at the head of the queue, was
     * picked again every tick, and used up all the shift's skips in eight.
     */
    @Nullable
    private BlockPos nextVeinBlock(Context ctx) {
        while (!vein.isEmpty()) {
            BlockPos candidate = vein.peek();
            // With an open face only: a vein block sealed over since it was
            // queued would be mined through solid rock.
            if (!unreachable.contains(candidate) && permitted(ctx, candidate)
                    && isOre(ctx.level().getBlockState(candidate))
                    && hasOpenFace(ctx.level(), candidate)) return candidate;
            vein.poll();
        }
        veinTaken = 0;   // that vein is finished; the next one starts fresh
        return null;
    }

    /**
     * Ore sense, for a highly skilled miner: after a block comes out, look one
     * block (level 75+) or two (level 100) past the new opening in each
     * direction for ore hiding behind the wall, and queue a short side tunnel
     * to it. How often it looks rises with level, 25% at 75 to 50% at 100.
     *
     * <p>Only real ore, only through rock the miner would dig anyway, and never
     * into another building or a fluid. One side tunnel at a time.
     */
    private void senseOre(Context ctx, BlockPos pos) {
        int skill = ctx.brain().minerSkillLevel();
        int radius = MinerBalance.oreSenseRadius(skill);
        if (radius <= 0 || !detour.isEmpty()) return;
        if (ctx.level().random.nextDouble() >= MinerBalance.oreSenseChance(skill)) return;

        for (Direction dir : Direction.values()) {
            for (int reach = 2; reach <= radius + 1; reach++) {
                BlockPos target = pos.relative(dir, reach);
                if (isProtected(ctx, target)) break;
                if (!permitted(ctx, target)) break;
                // The shaft's own blocks are the plan's, ore or not, dug in
                // its own order (see collectVein): a side tunnel through them
                // or to them dug a cell's head before the tile it is dug from.
                if (isPartOfShaft(target)) break;
                if (!isOre(ctx.level().getBlockState(target))) continue;
                if (vein.contains(target)) break;
                // Same bounds as a vein: inside the mine's rock, not out in the open.
                if (!insideMine(target, 1) || openToSky(ctx.level(), target)) break;

                List<BlockPos> tunnel = new ArrayList<>();
                boolean clear = true;
                for (int step = 1; step < reach; step++) {
                    BlockPos between = pos.relative(dir, step);
                    BlockState state = ctx.level().getBlockState(between);
                    if (isProtected(ctx, between)
                            || isPartOfShaft(between)
                            || !permitted(ctx, between)
                            || !state.getFluidState().isEmpty()) { clear = false; break; }
                    if (state.isAir()) continue;
                    if (!isDiggable(state)) { clear = false; break; }
                    tunnel.add(between);
                }
                if (!clear) break;
                tunnel.add(target);
                detour.addAll(tunnel);
                detourCells.addAll(tunnel);
                ctx.brain().rememberAndShow(MemoryTopic.WORK_DONE,
                        ThoughtIcon.EMOTE_IDEA, 40, ctx.level());
                diary(ctx, "MINE ore sense spotted " + ctx.level().getBlockState(target)
                        .getBlock().getName().getString() + " behind the wall at "
                        + target.toShortString());
                return;
            }
        }
    }

    /** The next side-tunnel block still standing, dropping any that is gone. */
    @Nullable
    private BlockPos nextDetourBlock(Context ctx) {
        while (!detour.isEmpty()) {
            BlockPos candidate = detour.peek();
            if (!unreachable.contains(candidate) && permitted(ctx, candidate)
                    && isDiggable(ctx.level().getBlockState(candidate))
                    && hasOpenFace(ctx.level(), candidate)) {
                return candidate;
            }
            detour.poll();
        }
        return null;
    }

    /**
     * The wall ore nearest the miner still to take this stint, or null once
     * {@link #MAX_WALL_ORE_PER_STINT} have been taken or none is left. Looks
     * the dug tunnel over the first time it is asked (see {@link #scanWallOre}).
     * Not taken off the list until it is broken or has nowhere to stand, so a
     * miner nudged off its tile mid-swing comes back to the same block.
     */
    @Nullable
    private BlockPos nextWallOre(Context ctx) {
        if (wallOreTaken >= MAX_WALL_ORE_PER_STINT) return null;
        if (!wallOreScanned) {
            wallOreScanned = true;
            scanWallOre(ctx);
        }
        // Each still checked now: dug by somebody else, set aside after a
        // failed walk, sealed over or claimed by a building since the scan.
        wallOre.removeIf(pos -> !wallOreWanted(ctx, pos));
        BlockPos at = ctx.npc().blockPosition();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos pos : wallOre) {
            double d = pos.distSqr(at);
            if (d < bestDist) {
                bestDist = d;
                best = pos;
            }
        }
        return best;
    }

    /**
     * Fills {@link #wallOre} with the ore showing in the walls, roof and floor
     * of the open tunnel: the open cells reached from the mouth through open
     * cells, the way {@link #scanForLeaks} walks them, and for each the block
     * under its floor, the one over its roof and the four sides at feet and
     * head height. At most {@link #MAX_WALL_ORE_FOUND}.
     *
     * <p>Only what shows, not the veins behind it (see {@link #breakBlock}).
     * A vein that ran out of skill, a vein block with nowhere to stand and
     * ore the corridor was cut past all sat in plain sight of the tunnel, and
     * the miner walked by them every shift on its way to the face.
     */
    private void scanWallOre(Context ctx) {
        if (plan == null) return;
        ServerLevel level = ctx.level();
        Set<BlockPos> wayOut = wayOut(ctx);
        Set<BlockPos> seen = new java.util.HashSet<>();
        Deque<BlockPos> open = new ArrayDeque<>();
        BlockPos mouth = plan.mouth();
        if (level.isLoaded(mouth) && cellIsTunnel(ctx, mouth, wayOut)) {
            seen.add(mouth);
            open.add(mouth);
        }
        List<BlockPos> sides = new ArrayList<>(2 + 4 * MineShaftPlan.TUNNEL_HEIGHT);
        while (!open.isEmpty() && wallOre.size() < MAX_WALL_ORE_FOUND) {
            BlockPos floor = open.poll();
            sides.clear();
            sides.add(floor.below());
            sides.add(floor.above(MineShaftPlan.TUNNEL_HEIGHT));
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    sides.add(floor.above(dy).relative(dir));
                }
            }
            for (BlockPos pos : sides) {
                if (wallOre.size() >= MAX_WALL_ORE_FOUND) break;
                if (wallOre.contains(pos)) continue;
                if (wallOreWanted(ctx, pos)) wallOre.add(pos.immutable());
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos next = floor.relative(dir);
                if (!plan.contains(next) || !seen.add(next)) continue;
                if (!level.isLoaded(next)) continue;
                if (isProtected(ctx, next)
                        || isProtected(ctx, next.above())) continue;
                if (!cellIsTunnel(ctx, next, wayOut)) continue;
                open.add(next);
            }
        }
        if (!wallOre.isEmpty()) {
            diary(ctx, "MINE found " + wallOre.size() + " ore in the walls of the dug tunnel");
        }
    }

    /**
     * Whether a block beside the open tunnel is wall ore worth going back
     * for: real ore, in the mine's own rock and not out in the open, with a
     * face open to the tunnel, and not the plan's own (that is dug in plan
     * order) nor already queued or set aside.
     */
    private boolean wallOreWanted(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        if (!level.isLoaded(pos) || isPartOfShaft(pos)) return false;
        if (unreachable.contains(pos) || vein.contains(pos) || detour.contains(pos)) return false;
        if (!isOre(level.getBlockState(pos))) return false;
        if (isProtected(ctx, pos) || !permitted(ctx, pos)) return false;
        return insideMine(pos, 1) && !openToSky(level, pos) && hasOpenFace(level, pos);
    }

    // =========================================================================
    // Light
    // =========================================================================

    /**
     * Makes sure the miner has something to light the mine with, when a torch
     * is due and it carries none.
     *
     * <p>In order: the village's own banked torches; then, if it already has
     * coal or charcoal but no sticks, sticks from storage or cut from a log;
     * otherwise a batch made from scratch at a storage room with a furnace --
     * logs burned into charcoal, planks cut into sticks, sixteen torches (see
     * {@link MakeTorchesActivity}); and only if no room can do that, coal
     * from storage.
     *
     * <p>At most two trips per stint. A supply trip that fails -- a chest written
     * off as unreachable, a furnace someone else holds -- used to be retried
     * on every decision, which is how a miner got stuck announcing that no
     * storage held charcoal. Only a trip that starts counts: a look that found
     * nothing used up a trip too, so two empty looks early in a stint left the
     * coal or sticks stored later in it unfetched and the dark part unlit. An
     * empty look now waits {@link #TORCH_LOOK_WAIT_TICKS} before the next.
     *
     * @return a status to return from the caller, or {@code null} to carry on
     */
    @Nullable
    private Status fetchTorchMakings(Context ctx) {
        // At the start of a shift the miner stocks up whatever the light at
        // the mouth says: the dark part is at the far end, and a mid-shift
        // trip back up the whole corridor for torches is the costly kind.
        boolean shiftStart = blocksThisSession == 0 && !workedThisSession;
        if (!shiftStart && torchCell(ctx) == null) return null;
        if (countItem(ctx, Items.TORCH) > 0) return null;
        boolean hasFuel = countItem(ctx, Items.COAL) > 0 || countItem(ctx, Items.CHARCOAL) > 0;
        boolean hasSticks = countItem(ctx, Items.STICK) > 0;
        if (hasFuel && hasSticks) return null;   // maybeLightCorridor crafts on the next block
        // Two trips at most: coal first, then its sticks.
        if (torchSupplyTrips >= MAX_TORCH_SUPPLY_TRIPS) return null;
        long now = ctx.level().getGameTime();
        if (now < torchLookAfter) return null;
        Activity trip = torchTrip(ctx, hasFuel);
        if (trip == null) {
            torchLookAfter = now + TORCH_LOOK_WAIT_TICKS;
            return null;
        }
        torchSupplyTrips++;
        subEndsSession = false;
        sub = trip;
        return Status.RUNNING;
    }

    /** The trip that gets this miner something to light with, or null when storage has nothing for it. */
    @Nullable
    private static Activity torchTrip(Context ctx, boolean hasFuel) {
        if (storageHasItem(ctx, Items.TORCH)) {
            return FetchFromStorageActivity.forItem(new ItemStack(Items.TORCH), 16, "fetch_torches");
        }
        if (hasFuel) {
            if (storageHasItem(ctx, Items.STICK)) {
                return FetchFromStorageActivity.forItem(new ItemStack(Items.STICK), 4, "fetch_sticks");
            }
            return MakeTorchesActivity.canRun(ctx, true) ? new MakeTorchesActivity(true) : null;
        }
        if (MakeTorchesActivity.canRun(ctx, false)) return new MakeTorchesActivity(false);
        // No furnace room or no logs: coal from storage still makes torches,
        // with sticks fetched or cut on the next pass.
        if (storageHasItem(ctx, Items.COAL)) {
            return FetchFromStorageActivity.forItem(new ItemStack(Items.COAL), 2, "fetch_fuel");
        }
        return null;
    }

    /**
     * Whether a storage room holds {@code item} in a chest this miner can
     * actually get to -- the same test the fetch itself makes. Asking only
     * "does any chest hold it" sent the miner after items in chests it had
     * already written off as unreachable, and the fetch failed every time.
     */
    private static boolean storageHasItem(Context ctx, Item item) {
        ItemStack wanted = new ItemStack(item);
        long now = ctx.level().getGameTime();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos chest : storage.findChestsWithItem(ctx.level(), wanted)) {
                if (!ctx.brain().isChestUnreachable(chest, now)) return true;
            }
        }
        return false;
    }

    /**
     * Puts a torch in every so often, making them from the village's coal and
     * sticks when the miner has run out.
     *
     * <p>An unlit strip mine fills with whatever spawns in the dark, which
     * turns the mine into the thing the guards then have to deal with.
     */
    private void maybeLightCorridor(Context ctx) {
        MineShaftPlan.Cell cell = torchCell(ctx);
        if (cell == null) return;
        if (countItem(ctx, Items.TORCH) <= 0 && !craftTorches(ctx)) return;

        // The cell's floor block, once the whole cell is open. Torches used to
        // go wherever the last swing landed, which was as often a head-height
        // block, a branch stub or a pocket inside an ore vein -- and at head
        // height the floor below is air, so the placement was simply dropped
        // and the corridor stayed dark.
        BlockPos spot = cell.floor();
        if (!ctx.level().getBlockState(spot).isAir()) return;
        if (!ctx.level().getBlockState(spot.above()).isAir()) return;
        if (!ctx.level().getBlockState(spot.below()).isFaceSturdy(
                ctx.level(), spot.below(), Direction.UP)) {
            return;
        }
        // Not into the miner's own feet: it would stand in the torch until it
        // walked on, and a passing villager would knock it out.
        if (ctx.npc().blockPosition().equals(spot)) return;
        if (!consumeItem(ctx, Items.TORCH, 1)) return;

        ctx.level().setBlock(spot, Blocks.TORCH.defaultBlockState(), 3);
        ctx.mgr().noteBlockChanged(spot);
        diary(ctx, "MINE lit " + spot.toShortString()
                + " (" + cell.mainDistance() + " down the shaft)");
    }

    /**
     * The cell being dug, if it is dark enough to want a torch; else null.
     *
     * <p>Judged by the cell, not by broken blocks: counting blocks included
     * ore, branches and hazard fill, so "every six blocks" meant every six
     * swings wherever they happened to land. Branch cells count too, so a
     * long branch gets its own torch instead of staying dark enough to spawn
     * in.
     */
    @Nullable
    private MineShaftPlan.Cell torchCell(Context ctx) {
        if (plan == null) return null;
        MineShaftPlan.Cell cell = plan.cell(planIndex);
        if (cell == null) return null;
        return lightAround(ctx, cell.floor()) <= TORCH_LIGHT_LEVEL ? cell : null;
    }

    /**
     * Block light a cell will have: its brightest neighbour's, less one. Read
     * off the neighbours because a block opened this very tick has not had
     * its own light worked out yet and reads as pitch dark, which would put a
     * torch in every cell.
     */
    private static int lightAround(Context ctx, BlockPos pos) {
        int light = 0;
        for (Direction dir : Direction.values()) {
            light = Math.max(light, ctx.level().getBrightness(LightLayer.BLOCK, pos.relative(dir)) - 1);
        }
        return light;
    }

    /** One coal and one stick make four torches, as vanilla. */
    private boolean craftTorches(Context ctx) {
        Item fuel = countItem(ctx, Items.COAL) > 0 ? Items.COAL
                : (countItem(ctx, Items.CHARCOAL) > 0 ? Items.CHARCOAL : null);
        if (fuel == null || countItem(ctx, Items.STICK) <= 0) return false;
        if (!consumeItem(ctx, fuel, 1) || !consumeItem(ctx, Items.STICK, 1)) return false;
        ctx.npc().getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.TORCH, TORCHES_PER_CRAFT), ctx.mgr()));
        diary(ctx, "MINE made " + TORCHES_PER_CRAFT + " torches");
        return true;
    }

    // =========================================================================
    // Yields
    // =========================================================================

    /**
     * What a block counts as when it is broken: itself, or -- for natural rock
     * (stone, deepslate, granite, diorite, andesite, tuff), at the skill's
     * scrap chance (2% at level 1 to 5% from level 75) -- the ore a scrap of
     * it came from. Keeps a village without a real ore body from stalling
     * entirely. Never bricks, a player's blocks, Nether rock, calcite or
     * loose ground: a stone brick wall is not where coal comes from. The mix
     * follows the block's height, as vanilla's ore does: coal high up, iron
     * around Y 16, redstone and diamond at the bottom (MinerBalance.scrapMix,
     * which also caps diamond and keeps some coal everywhere). Never emerald
     * or ancient debris: the miner only gets those by digging what a druid
     * grew back into the mine.
     */
    public static BlockState yieldSource(BlockState state, int y, int skill, RandomSource random) {
        if (isOre(state) || !state.is(BlockTags.BASE_STONE_OVERWORLD)) return state;
        if (random.nextDouble() >= MinerBalance.scrapOreChance(skill)) return state;
        OreHeights.Ore ore = OreHeights.pick(MinerBalance.scrapMix(y), random.nextDouble());
        return ore == null ? state : OreBlocks.stone(ore).defaultBlockState();
    }

    /**
     * What one block gives up: whatever it gives a player, through its vanilla
     * loot table with the miner's pickaxe. Stone gives cobblestone, deepslate
     * cobbled deepslate, granite, tuff and the like themselves, Nether quartz
     * ore quartz, a raw iron block itself, amethyst its shards, gravel now and
     * then flint (for the archers' arrows); ice and budding amethyst give
     * nothing. Scrap ore ({@code source}, see {@link #yieldSource}) drops
     * what that ore drops. Fortune works exactly as it does for a player.
     * Loot tables do not check pickaxe tier (only the player break path does),
     * so any pickaxe the village can make takes any ore. Silk Touch is left
     * out, see {@link #withoutSilkTouch}.
     */
    private static List<ItemStack> yieldOf(Context ctx, BlockPos pos, BlockState state,
                                           BlockState source, ItemStack tool) {
        return yieldOf(ctx.level(), pos, source, ctx.npc(), tool);
    }

    /** {@link #yieldOf(Context, BlockPos, BlockState, BlockState, ItemStack)} for the away miner. */
    public static List<ItemStack> yieldOf(ServerLevel level, BlockPos pos, BlockState source,
                                          @Nullable LivingEntity breaker, ItemStack tool) {
        return Block.getDrops(source, level, pos, null, breaker, withoutSilkTouch(tool));
    }

    /**
     * The pickaxe as the loot sees it: Silk Touch taken off a copy. A miner
     * with it would bank stone and ore blocks, which no smith smelts and no
     * merchant buys. A player's pickaxe already lost it in PlayerGear.effective;
     * this is for one the village enchanted itself.
     */
    private static ItemStack withoutSilkTouch(ItemStack tool) {
        if (tool.isEmpty() || !tool.isEnchanted()) return tool;
        ItemStack copy = tool.copy();
        EnchantmentHelper.updateEnchantments(copy, enchantments ->
                enchantments.removeIf(enchantment -> enchantment.is(Enchantments.SILK_TOUCH)));
        return copy;
    }

    public static boolean isOre(BlockState state) {
        return state.is(BlockTags.COAL_ORES) || state.is(BlockTags.IRON_ORES)
                || state.is(BlockTags.COPPER_ORES) || state.is(BlockTags.GOLD_ORES)
                || state.is(BlockTags.REDSTONE_ORES) || state.is(BlockTags.LAPIS_ORES)
                || state.is(BlockTags.DIAMOND_ORES) || state.is(BlockTags.EMERALD_ORES)
                // Nether ore: quartz, and blackstone with gold in it.
                || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Tags.Blocks.ORES_QUARTZ)
                || state.is(Blocks.GILDED_BLACKSTONE)
                // A master druid grows the odd block of it back into a mine.
                || state.is(Blocks.ANCIENT_DEBRIS);
    }

    /**
     * Blocks the miner is willing to cut through. Includes every block tagged
     * as pickaxe-mineable, so constructed stone and Nether stone work too.
     * Public because a druid regrowing a mine finds the end of the tunnel
     * with the same rule the miner digs by.
     */
    public static boolean isDiggable(BlockState state) {
        if (state.isAir()) return false;
        if (!state.getFluidState().isEmpty()) return false;
        // Somebody's things, not rock. Everything a pickaxe takes used to
        // count, so a shaft run into a cellar dug through the furnace,
        // hopper or spawner, threw away what was in it and paid cobblestone.
        if (state.hasBlockEntity()) return false;
        if (state.is(BlockTags.RAILS)) return false;
        if (state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
                || state.is(Blocks.RESPAWN_ANCHOR)) return false;
        return MineBuilding.isMineableStone(state) || isOre(state)
                || state.is(Blocks.DIRT) || state.is(Blocks.GRAVEL) || state.is(Blocks.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD);
    }

    // =========================================================================
    // Session
    // =========================================================================

    /**
     * Carry on to the next block, unless nothing has been broken for a while.
     *
     * <p>The bound matters: skipping is right for one unreachable block and
     * wrong for a plan the miner cannot get into at all, where it would walk
     * the whole shaft rejecting every cell, every tick, for the rest of the day.
     */
    private Status skipOrFinish(Context ctx) {
        if (++skipsWithoutProgress >= MAX_SKIPS_WITHOUT_PROGRESS) {
            diary(ctx, "MINE nothing reachable after " + skipsWithoutProgress
                    + " tries, ending the stint");
            return finishSession(ctx);
        }
        return Status.RUNNING;
    }

    /**
     * Whether the miner has run out of arms.
     *
     * <p>Asked before starting a block rather than after breaking one, so the
     * yield always has somewhere to go. A free slot takes any drop, which is
     * why the question is only about empty slots and not about what is in the
     * full ones.
     */
    private static boolean loadIsFull(Context ctx) {
        return !ctx.npc().getInventory().hasEmptySlot();
    }

    private Status finishSession(Context ctx) {
        // Ore holes under the floor are filled before the miner leaves, while
        // it still knows where they are: a stint that ended mid-vein (arms
        // full, pickaxe broken) left them open for good. The walk to storage
        // waits for the nav graph to see the new floor, then comes back here.
        if (backfillPits(ctx)) {
            settleUntil = ctx.level().getGameTime() + SETTLE_TICKS;
            endStint = true;
            return Status.RUNNING;
        }
        boolean fullStint = blocksThisSession >= MinerBalance.stintBlocks(ctx.brain().minerSkillLevel());
        skipsWithoutProgress = 0;
        blocksThisSession = 0;
        endStint = false;
        triedPickaxe = false;
        torchSupplyTrips = 0;
        torchLookAfter = Long.MIN_VALUE;
        wallOre.clear();
        wallOreScanned = false;
        wallOreTaken = 0;
        wallOreFace = null;
        int depositable = DepositActivity.countDepositable(ctx);
        if (depositable > 0 && (fullStint || loadIsFull(ctx) || depositable >= WORTH_BANKING)) {
            sub = new DepositActivity();
            subEndsSession = true;
            return Status.RUNNING;
        }
        // A stint cut short with a light pack keeps it: every stint that
        // ended early used to bank whatever it held, and a miner whose next
        // cell the nav graph had not caught up with yet walked to storage
        // with two stone and all the way back. FAILED, for the brain's pause
        // before work is picked again, which also gives the graph that time;
        // the evening deposit banks whatever is left.
        if (depositable > 0) {
            diary(ctx, "MINE stint ended early, keeping a light load of " + depositable + " item(s)");
            return Status.FAILED;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    /**
     * The player paused the mine the miner is down. Vein holes are filled as
     * at any stint end, then it walks out, by way of storage when it has
     * something to bank. Ending the shift where it stood would not do: the
     * next shift would find it still down the paused tunnel, take that mine
     * again (the one it stands in comes first) and end at once, every time.
     */
    private Status leavePausedMine(Context ctx) {
        if (backfillPits(ctx)) {
            settleUntil = ctx.level().getGameTime() + SETTLE_TICKS;
            return Status.RUNNING;
        }
        diary(ctx, "MINE paused by the player, leaving");
        if (DepositActivity.countDepositable(ctx) > 0) {
            // The trip starts with the walk out of the tunnel (doTick).
            sub = new DepositActivity();
            subEndsSession = true;
            return Status.RUNNING;
        }
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || plan == null) return workedThisSession ? Status.DONE : Status.FAILED;
        BlockPos exit = MineBuilding.approachTiles(graph, plan.mouth(), plan.direction())
                .iterator().next();
        if (graph.nodeAt(exit.getX(), exit.getY(), exit.getZ()) == null) {
            return workedThisSession ? Status.DONE : Status.FAILED;
        }
        walkOut = new MoveActivity(exit, VillagerModel.WALK_SPEED);
        walkAnchor = null;
        return Status.RUNNING;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Where the shaft starts and which way it runs.
     *
     * <p>Taken from the mine room's own rock face: the wall block is the mouth
     * and the direction is straight out through it, so the corridor leaves the
     * room the way somebody standing in it would.
     */
    @Nullable
    /**
     * Where this mine's shaft runs.
     *
     * <p>A single answer now, straight off the building: the mouth is two blocks
     * below the marked roof block and the shaft runs away from the token. It used
     * to be a search — try each exposed rock face of the mine room, keep the
     * first one with somewhere to stand beside it — because a room could touch
     * rock on any of its walls and only the geometry knew which. An outcrop has
     * one marked block and one direction, so there is nothing left to search
     * for, and the miner no longer picks a different wall on a day when it
     * happens to approach from elsewhere.
     */
    private MineShaftPlan planFor(Context ctx, MineBuilding mine) {
        planMineId = mine.getItemFrameId();
        mineSnapshot = MineReservations.snapshot(ctx.level());
        plannedRevision = mineSnapshot.revision();
        plannedBorder = MineReservations.Border.of(ctx.mgr());
        return MineReservations.planFor(mine, mineSnapshot, ctx.mgr());
    }

    /**
     * Applies this mine's shape and the mine partition to any dig, veins
     * included: no further left or right than its side tunnels, and no
     * further along than a main tunnel the player set shorter (a full-length
     * mine keeps taking ore in its end wall, as it always did).
     */
    private boolean permitted(Context ctx, BlockPos pos) {
        if (plan == null || planMineId == null || mineSnapshot == null) return false;
        Direction dir = plan.direction();
        Direction side = dir.getClockWise();
        int dx = pos.getX() - plan.mouth().getX();
        int dz = pos.getZ() - plan.mouth().getZ();
        int lateral = dx * side.getStepX() + dz * side.getStepZ();
        int along = dx * dir.getStepX() + dz * dir.getStepZ();
        boolean sideways = lateral >= 0 ? lateral <= plan.rightLength() : -lateral <= plan.leftLength();
        boolean lengthwise = plan.mainLength() >= MineShaftPlan.MAX_MAIN_LENGTH || along <= plan.mainLength();
        return sideways && lengthwise && ownedByMine(pos);
    }

    private boolean ownedByMine(BlockPos pos) {
        return planMineId != null && mineSnapshot != null
                && mineSnapshot.owns(planMineId, pos);
    }

    private boolean permittedNow(Context ctx, BlockPos pos) {
        return digRefusal(ctx, pos) == null;
    }

    /** Why {@code pos} may not be dug right now, for the diary, or null if it may. */
    @Nullable
    private String digRefusal(Context ctx, BlockPos pos) {
        mineSnapshot = MineReservations.snapshot(ctx.level());
        if (plan == null || planMineId == null || mineSnapshot == null) return "no shaft plan";
        if (!ownedByMine(pos)) return "rock of another mine";
        if (!permitted(ctx, pos)) return "past this mine's tunnel lengths";
        if (isProtected(ctx, pos)) return "part of a building";
        return null;
    }

    /**
     * Whether {@code pos} is a room block the miner must leave alone.
     *
     * <p>The mine's own entrance is the exception. A mine's "room" is the one
     * tile outside the mouth, and the protected box around it reaches a block
     * over, onto the mouth and the block above it: on a new mine the miner
     * swung at its own entrance, was refused after the swing, and came back
     * to it every shift. Those two blocks still count when another
     * building's box covers them.
     */
    private boolean isProtected(Context ctx, BlockPos pos) {
        if (plan != null && planMineId != null
                && (pos.equals(plan.mouth()) || pos.equals(plan.mouth().above()))) {
            return ctx.mgr().isProtectedRoomBlockExcept(pos, planMineId);
        }
        return ctx.mgr().isProtectedRoomBlock(pos);
    }

    /**
     * Whether a plug may go at {@code pos}: anywhere a dig may, and on water
     * or lava in the neighbouring mine's rock as well.
     *
     * <p>Two mines side by side split the rock between them, and a lake on
     * the line between the two belongs half to each. The miner would not
     * touch the other half, so the lake poured into its tunnel through the
     * end of a branch for good, flooded the corridor and stranded it on the
     * last dry tile. Cobblestone in a lake is what the other mine's miner
     * would set there anyway, and it digs it out again like rock.
     */
    private boolean mayPlugNow(Context ctx, BlockPos pos) {
        if (permittedNow(ctx, pos)) return true;
        return !isProtected(ctx, pos) && isLooseFluid(ctx.level().getBlockState(pos));
    }

    private static boolean isWalkable(Context ctx, BlockPos pos) {
        var graph = ctx.mgr().getGraph();
        return graph != null && isStandTile(ctx, graph, pos);
    }

    /**
     * A tile a walk can end on and a villager can really stand on: a nav
     * tile that routes -- not the surface of water, a bed or a chest top --
     * and is dry, floored and has head room in the world as it is now.
     *
     * <p>Any nav tile used to do. The graph keeps tiles on the surface of
     * water, and does not hear about water spreading or a wall being sealed,
     * so the stand chosen beside a block was at times on top of the wall the
     * miner had just sealed. A walk there ends at the nearest real tile --
     * in the next branch over -- where the miner stopped, out of reach, set
     * the block aside and walked back.
     */
    private static boolean isStandTile(Context ctx, NavGraph graph, BlockPos pos) {
        com.alex.test.village.NavNode node = graph.nodeAt(pos.getX(), pos.getY(), pos.getZ());
        return node != null && node.isRoutable() && isStepable(ctx, pos);
    }

    /**
     * A nav tile within reach of {@code target}, preferring the closest: for
     * a block to dig, one beside it (see {@link #inMiningReach}); for a plug,
     * anywhere in {@link #PLUG_REACH_SQ}, since the tiles next to a leak are
     * the wet ones nobody can stand on.
     *
     * <p>For digging, a tile the miner can step straight onto counts too,
     * nav tile or not (see {@link #canStepTo}): the tile it has just dug, in
     * front of it, is where it digs the next one from, and the nav graph
     * learns of a dug block seconds later.
     */
    @Nullable
    private BlockPos standNear(Context ctx, BlockPos target, boolean plug) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        BlockPos npcPos = ctx.npc().blockPosition();
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        int across = plug ? PLUG_SEARCH_XZ : 1;
        List<BlockPos> candidates = new ArrayList<>();
        Set<BlockPos> stepOnly = new java.util.HashSet<>();
        for (int dx = -across; dx <= across; dx++) {
            for (int dz = -across; dz <= across; dz++) {
                // Never straight under the block (whatever is loose above it
                // falls on the miner's head, and lava pours down on it) nor
                // straight over it (digging out its own floor).
                if (dx == 0 && dz == 0) continue;
                for (int dy = -STAND_SEARCH_Y; dy <= STAND_SEARCH_Y; dy++) {
                    BlockPos candidate = target.offset(dx, dy, dz);
                    if (plug ? candidate.distSqr(target) > PLUG_REACH_SQ
                             : !inMiningReach(candidate, target)) continue;
                    // Inside the rock, only ever the corridor floor. The
                    // tile nearest an ore under the floor was the bottom of
                    // the hole the vein had made, and a villager cannot jump
                    // two blocks: that is how a miner got stuck in its shaft
                    // with every walk failing.
                    if (insideMine(candidate, 1) && candidate.getY() < plan.mouth().getY()) continue;
                    if (!isStandTile(ctx, graph, candidate)) {
                        if (plug || !canStepTo(ctx, candidate)) continue;
                        stepOnly.add(candidate);
                    }
                    candidates.add(candidate);
                }
            }
        }
        // Closest to the FACE, with distance to the miner only breaking ties.
        // Picking the tile nearest the miner instead put a plug at the far
        // edge of its reach, where arriving a single block short meant the
        // block it had walked over to was suddenly out of range -- so it
        // re-picked the same block, walked to the same tile, and did that for
        // the rest of the day without a line in its diary.
        candidates.sort(Comparator.comparingDouble(
                c -> c.distSqr(target) * 1000.0 + c.distSqr(npcPos)));
        // And one it can walk to from here. The nearest tile to a face is
        // often on the far side of it, or past a gap, and walking at that
        // failed every time.
        int tested = 0;
        for (BlockPos candidate : candidates) {
            if (candidate.equals(feet) || stepOnly.contains(candidate)
                    || NavReachability.canReach(graph, feet, candidate)) return candidate;
            if (++tested >= MAX_STAND_REACH_TESTS) break;
        }
        return null;
    }

    /** Whether the miner can walk from where it stands to {@code stand}. */
    private static boolean canWalkTo(Context ctx, BlockPos stand) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return false;
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        return feet.equals(stand) || NavReachability.canReach(graph, feet, stand);
    }

    private static int countItem(Context ctx, Item item) {
        int total = 0;
        var inv = ctx.npc().getInventory();
        for (int i = 0; i < com.alex.test.entity.inventory.InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUse(stack) && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static boolean consumeItem(Context ctx, Item item, int amount) {
        if (countItem(ctx, item) < amount) return false;
        var inv = ctx.npc().getInventory();
        int remaining = amount;
        for (int i = 0; i < com.alex.test.entity.inventory.InventoryManager.SIZE && remaining > 0; i++) {
            ItemStack stack = inv.getSlot(i);
            if (!inv.mayUse(stack) || stack.getItem() != item) continue;
            int take = Math.min(remaining, stack.getCount());
            inv.reduceSlot(i, take);
            remaining -= take;
        }
        return remaining == 0;
    }

    private static boolean hasPickaxe(Context ctx) {
        return ToolSelectionHelper.findUsableToolSlot(
                ctx.npc().getInventory(), ItemTags.PICKAXES, allowedPickaxes(ctx),
                ToolSelectionHelper::isUsableByDefault) >= 0;
    }

    /**
     * Filtered by {@link MinerSettingsSchema#ALLOWED_PICKAXE_TIERS} so
     * disallowing a tier in the settings screen genuinely stops this miner
     * from picking up or equipping it, rather than just hiding an unused
     * toggle -- mirrors {@code GuardSettings.SwordTier}'s equipment-tier gate.
     */
    public static Set<Item> allowedPickaxes(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), MinerSettingsSchema.SCHEMA);
        Set<Item> allowed = new LinkedHashSet<>();
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_WOODEN)) {
            allowed.add(Items.WOODEN_PICKAXE);
        }
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_STONE)) {
            allowed.add(Items.STONE_PICKAXE);
        }
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_GOLDEN)) {
            allowed.add(Items.GOLDEN_PICKAXE);
        }
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_IRON)) {
            allowed.add(Items.IRON_PICKAXE);
        }
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_DIAMOND)) {
            allowed.add(Items.DIAMOND_PICKAXE);
        }
        if (settings.isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS, MinerSettingsSchema.TIER_NETHERITE)) {
            allowed.add(Items.NETHERITE_PICKAXE);
        }
        return allowed;
    }

    /**
     * Whether a miner with no usable pickaxe could get one: a spare of an
     * allowed tier in a chest, or a crafting table and the wood for a wooden
     * one (carried wood counts, since a miner comes home with its pack full).
     * The same lookup GetPickaxeActivity runs: a looser copy of it ("any wood
     * at all") let the miner start sessions that failed at the pickaxe trip.
     */
    private static boolean canGetPickaxe(Context ctx) {
        return GetToolActivity.canAcquire(ctx, GetPickaxeActivity.PICKAXE_REQUIREMENT);
    }

    /**
     * Between stints, trade the pickaxe for a better one in storage; the old one
     * goes back (see {@link ToolUpgradeHelper}). Checked again only when storage
     * tools change, like the other professions.
     */
    private boolean tryStartPickaxeUpgrade(Context ctx) {
        long newestChange = ToolUpgradeHelper.newestToolStorageChangeId(ctx);
        if (newestChange <= lastSeenToolStorageChangeId) return false;
        lastSeenToolStorageChangeId = newestChange;
        StorageBuilding storage = ToolUpgradeHelper.nearestStorageWithBetterTool(
                ctx, GetPickaxeActivity.PICKAXE_REQUIREMENT);
        if (storage == null) return false;
        sub = GetPickaxeActivity.forUpgrade(storage);
        subEndsSession = false;
        diary(ctx, "MINE heading to storage for a better pickaxe");
        return true;
    }

    /**
     * The mine this miner is working, claiming it if it is not already taken.
     *
     * <p>A shaft holds one miner. This used to return the first valid mine in
     * the village to whoever asked, so every miner drove the same corridor:
     * they stood in each other's way, broke the block the other was three
     * seconds into swinging at, and knocked out each other's torches.
     *
     * <p>Measured, in {@code miner_one_per_shaft}: two miners sharing a shaft
     * were both at work on 389 of 400 samples and produced 118 items; one
     * miner at a time in the same shaft produces 98, and a shaft each produces
     * 185. So exclusion is not a throughput win on its own -- with a single
     * mine and two miners the spare one now idles, and the village wants a
     * second Mine token. What it buys is that the corridor stays the shape one
     * miner dug it and the torches stay in it.
     *
     * <p>The claim is on the mine's mouth, which is stable for the life of the
     * building, and is released in {@link #doStop} like every other claim.
     *
     * <p>Which free mine: the one whose tunnel it is down, then the order of
     * {@link #mineOrder} (best height for its wanted ores first), skipping
     * mines with nothing left to dig while another still has some. Only when
     * no free mine has work does it take the first free one, as it always did,
     * and the shift finds nothing to do there and ends.
     */
    @Nullable
    private MineBuilding claimMine(Context ctx) {
        if (claimedMine != null) {
            for (Building building : ctx.mgr().getBuildings()) {
                if (building instanceof MineBuilding mine && mine.isValid()
                        && mine.mouth().equals(claimedMine)) {
                    // Paused since it was taken: kept only while the miner is
                    // down it, for the walk out (startNextStint).
                    if (!paused(ctx, mine) || standsIn(ctx, mine)) return mine;
                    break;
                }
            }
            releaseMine(ctx);   // the mine it held is gone, no longer valid, or paused
        }
        MineOrder order = mineOrder(ctx);
        UUID self = ctx.model().npcId;
        MineBuilding firstFree = null;
        for (MineBuilding mine : order.mines()) {
            if (ctx.mgr().getClaims().isClaimedByOther(mine.mouth(), self)) continue;
            if (firstFree == null) firstFree = mine;
            // The mine it stands in is taken whatever is left in it: the shift
            // is what climbs a miner back out of its own tunnel.
            if ((mine == order.inside() || planHasWork(ctx, mine)) && takeMine(ctx, mine)) return mine;
        }
        return firstFree != null && takeMine(ctx, firstFree) ? firstFree : null;
    }

    private boolean takeMine(Context ctx, MineBuilding mine) {
        if (!ctx.mgr().getClaims().claim(mine.mouth(), ctx.model().npcId)) return false;
        claimedMine = mine.mouth().immutable();
        Set<OreHeights.Ore> wanted = wantedOres(ctx.npc());
        if (!wanted.isEmpty()) {
            diary(ctx, "MINE took the shaft at Y " + mine.mouth().getY() + " for " + wanted
                    + " (" + mine.mouth().toShortString() + ")");
        }
        return true;
    }

    private void releaseMine(Context ctx) {
        if (claimedMine == null) return;
        ctx.mgr().getClaims().release(claimedMine, ctx.model().npcId);
        claimedMine = null;
    }

    /**
     * The village's valid mines in the order a miner tries them, and the one
     * whose tunnel it is down (null when none).
     */
    private record MineOrder(List<MineBuilding> mines, @Nullable MineBuilding inside) {}

    /**
     * The mine whose rock it is standing on first, then the rest. Taking the
     * first free mine in the list swapped the miners of two mines side by
     * side after every break: one went back to work holding the other's mine
     * while still down its own tunnel, and was judged by a plan for somewhere
     * it was not.
     *
     * <p>The rock split gives every block in the world to its nearest mine,
     * so that first mine is only "inside" (taken with nothing left to dig)
     * when the miner is really down its tunnel ({@link #standsIn}). It used to
     * count from anywhere: a miner nearer a dug-out mine than one with rock
     * took the dug-out one, ended the shift at once, and did the same every
     * shift after.
     *
     * <p>A miner whose settings want some ores tries every mine best fit first,
     * the nearest one too unless it is down it: the mine whose height holds the
     * most of those ores ({@link OreHeights#fit}). With none wanted, the nearest
     * comes first and the rest keep the village's own order.
     */
    private static MineOrder mineOrder(Context ctx) {
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        MineReservations.Snapshot shares = MineReservations.snapshot(ctx.level());
        List<MineBuilding> order = new ArrayList<>();
        MineBuilding nearest = null;
        MineBuilding inside = null;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof MineBuilding mine) || !mine.isValid()) continue;
            boolean owns = nearest == null && shares.owns(mine.getItemFrameId(), feet);
            boolean down = owns && standsIn(ctx, mine);
            // Paused by the player: left out, unless the miner is down it, when
            // it still comes first so the shift can climb and walk it out.
            if (paused(ctx, mine) && !down) continue;
            if (owns) {
                nearest = mine;
                if (down) inside = mine;
            } else {
                order.add(mine);
            }
        }
        Set<OreHeights.Ore> wanted = wantedOres(ctx.npc());
        if (!wanted.isEmpty()) {
            // Some mine is "nearest" from anywhere, so putting it first ignored the wish whenever
            // it was free. It is ranked with the rest (first among equal fits; the sort keeps order),
            // unless the miner is down it.
            if (nearest != null && inside == null) {
                order.add(0, nearest);
                nearest = null;
            }
            order.sort(Comparator.comparingDouble(
                    (MineBuilding mine) -> OreHeights.fit(wanted, mine.mouth().getY())).reversed());
        }
        if (nearest != null) order.add(0, nearest);
        return new MineOrder(order, inside);
    }

    /** The ores this miner's settings ask for; empty when none are on. */
    public static Set<OreHeights.Ore> wantedOres(NpcEntity npc) {
        ProfessionSettings settings = ProfessionSettings.of(npc, MinerSettingsSchema.SCHEMA);
        Set<OreHeights.Ore> wanted = EnumSet.noneOf(OreHeights.Ore.class);
        for (OreHeights.Ore ore : OreHeights.Ore.values()) {
            if (settings.isAllowed(MinerSettingsSchema.WANTED_ORES, MinerSettingsSchema.oreEntry(ore))) {
                wanted.add(ore);
            }
        }
        return wanted;
    }

    /** The player paused this mine in its Mine screen: nobody goes in. */
    private static boolean paused(Context ctx, MineBuilding mine) {
        return ctx.mgr().mineShape(mine.getItemFrameId()).paused();
    }

    /**
     * Whether the miner is down {@code mine}'s tunnel, read as a fresh miner
     * would (the test of the "down a hole" start in {@link #hasWork}). Not the
     * rock split: every block in the world belongs to its nearest mine, so
     * that would call a miner in the village square inside.
     */
    private static boolean standsIn(Context ctx, MineBuilding mine) {
        MinerActivity probe = new MinerActivity();
        probe.plan = probe.planFor(ctx, mine);
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        return probe.plan != null && probe.insideMine(feet, 1) && probe.inOwnShaft(ctx, feet);
    }

    /** Whether {@code mine}'s plan still has rock or repairs in it, read as a fresh miner would. */
    private static boolean planHasWork(Context ctx, MineBuilding mine) {
        MinerActivity probe = new MinerActivity();
        probe.plan = probe.planFor(ctx, mine);
        return probe.plan != null && probe.nextPlanIndex(ctx, mine) >= 0;
    }

    /**
     * The order {@link #claimMine} tries the mines in ({@link #mineOrder}), paused
     * ones left out even when the miner is down one: for the away miner
     * ({@code economy/away/jobs/MinerAway}), which digs nothing in a paused mine.
     */
    public static List<MineBuilding> awayMineOrder(Context ctx) {
        List<MineBuilding> mines = new ArrayList<>(mineOrder(ctx).mines());
        mines.removeIf(mine -> paused(ctx, mine));
        return mines;
    }

    /**
     * One mine's plan and the live dig rules on it, read-only, for the away
     * miner: the same fresh probe {@link #planHasWork} uses, so the away copy
     * asks exactly what a miner here would ({@link #permitted},
     * {@link #isProtected}, {@link #insideMine}, {@link #isPartOfShaft}).
     */
    public static final class Rules {
        private final MinerActivity probe = new MinerActivity();
        private final Context ctx;

        public Rules(Context ctx, MineBuilding mine) {
            this.ctx = ctx;
            probe.plan = probe.planFor(ctx, mine);
        }

        /** Null when the mine has no plan (no direction, no rock). */
        @Nullable
        public MineShaftPlan plan() { return probe.plan; }

        /** Inside this mine's tunnel lengths and its share of the rock. */
        public boolean permitted(BlockPos pos) { return probe.permitted(ctx, pos); }

        /** A room block the miner leaves alone (its own doorway excepted). */
        public boolean isProtected(BlockPos pos) { return probe.isProtected(ctx, pos); }

        /** This mine's share of the rock, whatever the tunnel lengths. */
        public boolean ownedByMine(BlockPos pos) { return probe.ownedByMine(pos); }

        /** Past the doorway, in the mine's own rock. */
        public boolean insideMine(BlockPos pos) { return probe.insideMine(pos, 1); }

        /** Where the plan means the tunnel to be open, the doorway included. */
        public boolean isPartOfShaft(BlockPos pos) { return probe.isPartOfShaft(pos); }
    }

    /** The single-step horizontal direction from {@code from} to {@code to}. */
    @Nullable
    private static Direction horizontalDirection(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        if (dx == 1 && dz == 0) return Direction.EAST;
        if (dx == -1 && dz == 0) return Direction.WEST;
        if (dz == 1 && dx == 0) return Direction.SOUTH;
        if (dz == -1 && dx == 0) return Direction.NORTH;
        return null;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** Cheap pre-check for brain scoring. */
    public static boolean hasWork(Context ctx) {
        if (!ProfessionSettings.of(ctx.npc(), MinerSettingsSchema.SCHEMA)
                .getBool(MinerSettingsSchema.WORK_ENABLED)) return false;
        if (!hasPickaxe(ctx) && !canGetPickaxe(ctx)) return false;
        // Every free mine in the order claimMine tries them, until one has
        // work. Only the first free mine used to be looked at, so a worked-out
        // shaft early in the list kept the miner idle beside a second shaft
        // full of rock.
        BlockPos feet = NavGraph.feetCell(ctx.npc());
        boolean anyFree = false;
        for (MineBuilding mine : mineOrder(ctx).mines()) {
            if (ctx.mgr().getClaims().isClaimedByOther(mine.mouth(), ctx.model().npcId)) continue;
            anyFree = true;
            MinerActivity probe = new MinerActivity();
            probe.plan = probe.planFor(ctx, mine);
            if (probe.plan == null) continue;
            // Down a hole in the mine is reason enough to start: the shift is
            // what climbs a miner back out of one.
            if (probe.insideMine(feet, 1) && probe.inOwnShaft(ctx, feet)
                    && feet.getY() < probe.plan.mouth().getY()
                    && !ctx.level().canSeeSky(feet.above())) return true;
            // Paused (only listed while the miner is down it): the climb out
            // above is the one reason to start; nothing is dug there.
            if (paused(ctx, mine)) continue;
            if (probe.nextPlanIndex(ctx, mine) >= 0) return true;
        }
        // Every shaft is taken. A druid regrowing one is worth walking over to.
        return !anyFree && MinerHandoff.druidHoldsAMine(ctx);
    }
}
