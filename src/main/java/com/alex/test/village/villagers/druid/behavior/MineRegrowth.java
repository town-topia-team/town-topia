package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Which part of a mine a druid may grow back, and the growing, one block at a
 * time.
 *
 * <p>Only blocks of the mine's own plan, the whole length of it. Water and lava
 * in a cell are grown over like air. Cells that are still whole rock and cells
 * another building owns have nothing to grow, and the scan reads on past them:
 * it used to end at the first main-corridor cell that was still rock or wet,
 * and a puddle, a cobbled lava pocket or a block the miner skipped near the
 * entrance left everything past it open for good -- the druid refilled the few
 * cells before it, the miner dug them out again in minutes, and the mine was
 * always empty. Rock is judged with the miner's own
 * {@link MinerActivity#isDiggable}. A cell that is part rock,
 * part air -- the one the miner stopped half-way through, or one the druid
 * half-filled on its last visit -- gets its open blocks filled.
 *
 * <p>The plan starts one block into the rock, so the mouth, the rock over it
 * and the block the Mine token hangs on are never part of it: the entrance
 * stays open and the mine stays registered.
 *
 * <p>Blocks come back deepest cell first, floor before head. Whatever the druid
 * has not reached yet is the part nearest the entrance, so nobody inside is
 * ever walled in behind new rock, and a mine left half-grown is still an open
 * corridor up to fresh stone.
 */
public final class MineRegrowth {

    /** The blocks a druid could grow back right now, in the order it grows them. */
    record Scan(List<BlockPos> blocks, boolean workedOut) {}

    private MineRegrowth() {}

    static Scan scan(Activity.Context ctx, MineShaftPlan plan) {
        ServerLevel level = ctx.level();
        List<List<BlockPos>> cells = new ArrayList<>();
        boolean workedOut = true;
        for (int index = 0; ; index++) {
            MineShaftPlan.Cell cell = plan.cell(index);
            if (cell == null) break;
            // A half-loaded mine cannot be read to its end; leave it for later.
            if (!level.isLoaded(cell.floor())) return new Scan(List.of(), false);
            if (isBlocked(ctx, cell)) continue;
            // Open sky over the cell: the plan runs out of the rock here, and
            // this air was never dug. On a superflat the whole plan lies on
            // the grass, and filling it grew a stone wall across the village
            // that the miner then dug out and bricked up with cobblestone.
            if (isOutdoors(level, cell)) continue;

            List<BlockPos> open = new ArrayList<>(MineShaftPlan.TUNNEL_HEIGHT);
            int rock = 0;
            boolean foreign = false;
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos pos = cell.floor().above(dy);
                BlockState state = level.getBlockState(pos);
                // Water and lava in the tunnel's shape are grown over like air:
                // the cell is part of the mine either way. Water at the top of
                // a pond open to the sky is not the mine's.
                if (isOpen(state)) {
                    if (!level.canSeeSky(pos)) open.add(pos);
                }
                else if (MinerActivity.isDiggable(state)) rock++;
                else foreign = true;
            }
            // Whole rock: nothing to grow, and the miner still has it to dig.
            // The cells past it are still read.
            if (rock == MineShaftPlan.TUNNEL_HEIGHT) {
                workedOut = false;
                continue;
            }
            // A rail, a chest, a waterlogged slab, a player's block: that cell
            // is left alone.
            if (foreign || open.isEmpty()) continue;
            cells.add(open);
        }
        Collections.reverse(cells);
        List<BlockPos> blocks = new ArrayList<>();
        for (List<BlockPos> cell : cells) blocks.addAll(cell);
        return new Scan(blocks, workedOut);
    }

    /** Still air, a torch, water or lava, so still something to grow back. */
    static boolean canRegrow(ServerLevel level, BlockPos pos) {
        return isOpen(level.getBlockState(pos)) && !level.canSeeSky(pos);
    }

    /**
     * Air, a torch, or loose water or lava (with seagrass and the like in it).
     * Not a waterlogged chest, slab or lantern: rock grown over one of those
     * would destroy it and whatever was inside.
     */
    private static boolean isOpen(BlockState state) {
        if (state.isAir() || isTorch(state)) return true;
        if (state.getFluidState().isEmpty()) return false;
        return state.getBlock() instanceof LiquidBlock
                || (state.canBeReplaced() && !state.hasBlockEntity());
    }

    /**
     * Whether the top of this cell is open under the sky. A solid head block
     * shades the floor too, so only the head needs checking.
     */
    private static boolean isOutdoors(ServerLevel level, MineShaftPlan.Cell cell) {
        BlockPos head = cell.floor().above(MineShaftPlan.TUNNEL_HEIGHT - 1);
        return isOpen(level.getBlockState(head)) && level.canSeeSky(head);
    }

    /** Any creature, item or other entity in the block. */
    static boolean hasEntityAt(ServerLevel level, BlockPos pos) {
        return !level.getEntities((Entity) null, new AABB(pos), entity -> true).isEmpty();
    }

    /**
     * A player or another villager anywhere in the mine: regrowing waits for
     * them to leave. Anywhere, not only the corridor itself: a miner in an ore
     * pocket off the corridor, or down a hole under it, was not "in the
     * tunnel", and the rock grown back across the corridor shut it in.
     */
    static boolean someoneInside(Activity.Context ctx, MineShaftPlan plan) {
        BlockPos mouth = plan.mouth();
        Direction direction = plan.direction();
        return !ctx.level().getEntitiesOfClass(LivingEntity.class,
                shaftBox(plan).expandTowards(0, -8, 0).expandTowards(0, 2, 0),
                e -> e != ctx.npc() && e.isAlive() && !e.isSpectator()
                        && (e instanceof Player || e instanceof NpcEntity)
                        // Past the doorway: a miner waiting by the druid at
                        // the entrance is outside, as it always was.
                        && (e.getBlockX() - mouth.getX()) * direction.getStepX()
                           + (e.getBlockZ() - mouth.getZ()) * direction.getStepZ() >= 1).isEmpty();
    }

    /**
     * Grows one block back: the rock of the walls around it, now and then ore
     * of its height.
     *
     * @return 1 when the block held a torch the druid kept, else 0; a torch it
     *         does not keep is simply gone, as the rock grows over it
     */
    static int fill(Activity.Context ctx, BlockPos pos, int skillLevel) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        int kept = isTorch(state) && DruidBalance.keepsTorches(skillLevel) ? 1 : 0;
        BlockState grown = DruidBalance.regrownRock(skillLevel, pos.getY(), wallRock(level, pos), level.random);
        level.setBlock(pos, grown, 3);
        // The village listens for player edits, not villager ones; without
        // this the nav graph would go on routing through solid rock.
        ctx.mgr().noteBlockChanged(pos);
        // No stone place sound: the druid's mine finish (DruidSounds.mineFinishCue) marks each block.
        DruidEffects.mineBlockGrown(level, pos, grown);
        return kept;
    }

    /**
     * The rock to grow at {@code pos}: one of its neighbours that is natural
     * stone (stone, granite, diorite, andesite, tuff or deepslate), picked at
     * random, so the regrown tunnel matches its walls and blends where they
     * change. Plain stone, or deepslate below Y 0, when none is. Unloaded
     * neighbours are not read: that would load their chunk.
     */
    private static BlockState wallRock(ServerLevel level, BlockPos pos) {
        List<Block> around = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            BlockPos next = pos.relative(side);
            if (!level.isLoaded(next)) continue;
            BlockState state = level.getBlockState(next);
            if (state.is(BlockTags.BASE_STONE_OVERWORLD)) around.add(state.getBlock());
        }
        if (around.isEmpty()) return (pos.getY() < 0 ? Blocks.DEEPSLATE : Blocks.STONE).defaultBlockState();
        return around.get(level.random.nextInt(around.size())).defaultBlockState();
    }

    /**
     * Where the druid stands to regrow {@code mine}: just outside the mouth,
     * one tile to the side of the approach, so the doorway itself stays free
     * for the miner and the player. The approach tile is the fallback.
     */
    @Nullable
    static BlockPos standSpot(Activity.Context ctx, MineBuilding mine) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Direction direction = mine.shaftDirection();
        BlockPos approach = MineBuilding.approachTiles(graph, mine.mouth(), direction)
                .iterator().next();
        for (Direction side : new Direction[] {direction.getClockWise(), direction.getCounterClockWise()}) {
            NavNode beside = graph.nearestRoutable(approach.relative(side), 0, 1);
            if (beside != null) return beside.feetPos();
        }
        NavNode front = graph.nearestRoutable(approach, 1, 1);
        return front == null ? null : front.feetPos();
    }

    private static boolean isBlocked(Activity.Context ctx, MineShaftPlan.Cell cell) {
        for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
            if (ctx.mgr().isProtectedRoomBlock(cell.floor().above(dy))) return true;
        }
        return false;
    }

    private static boolean isTorch(BlockState state) {
        return state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH);
    }

    /** Everything the plan could ever cover, for one entity query (also the mine screen's "who is inside"). */
    public static AABB shaftBox(MineShaftPlan plan) {
        BlockPos mouth = plan.mouth();
        Direction right = plan.direction().getClockWise();
        BlockPos a = mouth.relative(right, plan.rightLength());
        BlockPos b = mouth.relative(plan.direction(), plan.mainLength())
                .relative(right.getOpposite(), plan.leftLength());
        return new AABB(
                Math.min(a.getX(), b.getX()), mouth.getY(), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, mouth.getY() + MineShaftPlan.TUNNEL_HEIGHT,
                Math.max(a.getZ(), b.getZ()) + 1);
    }
}
