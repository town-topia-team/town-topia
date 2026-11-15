package com.alex.test.village.villagers.miner;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The shape of a strip mine, as a pure function of how far along it is.
 *
 * <p>The miner used to stand in its room and chip at the wall in front of it,
 * reaching about six blocks out and no further. It never walked into its own
 * tunnel, never lit anything, and its ore came from a dice roll rather than
 * from the rock. What it dug was a shallow scallop around the room, and once
 * that scallop was cut there was nothing left to do.
 *
 * <p>A strip mine is what a player digs instead: one long corridor, with short
 * branches off it every few blocks, so a wide slab of rock is exposed for the
 * least digging. This class holds that geometry and nothing else — no world
 * access, no entity, no state beyond the plan itself — so the shape is easy to
 * reason about and the activity can stay about walking, digging and hazards.
 *
 * <h3>The layout</h3>
 * <pre>
 *      . . . . . . . . . . . . . .     branch (left)
 *      ────┬───────┬───────┬──────     main shaft, running `direction`
 *      . . . . . . . . . . . . . .     branch (right)
 * </pre>
 * The main shaft runs from the mouth in one direction. Every {@link
 * #BRANCH_SPACING} blocks it throws a branch out to each side, {@link
 * #BRANCH_LENGTH} long by default. The Mayor can change the maximum, each mine
 * can set its own left and right lengths and a shorter corridor
 * ({@link MineSettings}), and a neighboring mine can shorten individual branches. The village map's edge
 * ends the corridor and the branches too: no miner can stand past it, and
 * cells out there kept a cut-off mine from ever counting as worked out.
 * Branches are spaced so that the ore they expose does
 * not overlap: three blocks apart leaves no stone unseen between two branches,
 * which is the whole point of the pattern.
 *
 * <p>Positions come out in dig order — the corridor first, then that
 * junction's two branches, then on down the corridor — so the miner is always
 * digging next to where it already is, and never has to walk past open air it
 * has not yet lit.
 */
public final class MineShaftPlan {

    /** Blocks of main corridor between one pair of branches and the next. */
    public static final int BRANCH_SPACING = 3;

    /** Legacy/default branch length used by callers without village settings. */
    public static final int BRANCH_LENGTH = MinerSettingsSchema.DEFAULT_SIDE_BRANCH_DISTANCE;

    /**
     * How long the main corridor may get before the mine is called finished.
     *
     * <p>A mine is finite in two ways, and the shorter one usually wins: this
     * length, and the body of rock actually behind the anchor. A miner that
     * reaches the end of either has no work for the rest of the game, which is
     * what {@code miner_endurance} exists to catch. Raising this alone does not
     * help -- the rock runs out first -- so it is left where it was; a mine
     * that dropped a level and started again is the fix, and is not written.
     */
    public static final int MAX_MAIN_LENGTH = 96;

    /** A corridor is two blocks tall: one for the feet, one for the head. */
    public static final int TUNNEL_HEIGHT = 2;

    private final BlockPos mouth;
    private final Direction direction;
    private final int leftLength;
    private final int rightLength;
    private final int mainLength;
    private final List<Cell> cells = new ArrayList<>();
    private final Set<BlockPos> floors = new HashSet<>();
    private final List<End> ends = new ArrayList<>();
    private int corridorLength;
    @Nullable private Stop corridorStop;

    /** Why a corridor or branch ends before its full length. */
    public enum Stop {
        /** The village map ends there: past it no miner has anywhere to stand. */
        BORDER,
        /** The rock there is closer to another mine's corridor. */
        NEXT_MINE
    }

    /** Why {@code pos} may not be part of the shaft, or null when it may. */
    @FunctionalInterface
    public interface Limit {
        @Nullable Stop why(BlockPos pos);
    }

    /** The first block a corridor or branch may not take, and why. */
    public record End(BlockPos at, Stop why, boolean branch) {}

    public MineShaftPlan(BlockPos mouth, Direction direction) {
        this(mouth, direction, BRANCH_LENGTH, BRANCH_LENGTH, MAX_MAIN_LENGTH, pos -> null);
    }

    /** Builds a contiguous branch on each side, stopping at the first reserved cell. */
    public MineShaftPlan(BlockPos mouth, Direction direction, int maximumBranchDistance,
                         Predicate<BlockPos> allowed) {
        this(mouth, direction, maximumBranchDistance, maximumBranchDistance, MAX_MAIN_LENGTH,
                pos -> allowed.test(pos) ? null : Stop.NEXT_MINE);
    }

    /**
     * Builds the corridor up to {@code mainLength} with a contiguous branch on
     * each side, {@code left} long on the left and {@code right} on the right
     * (as seen from the mouth looking along {@code direction}), each stopping
     * at the first block {@code limit} turns down. Every such stop is kept in
     * {@link #ends()}, so a screen can say where the shaft ends and why.
     * Reaching {@code mainLength} itself is not a stop.
     */
    public MineShaftPlan(BlockPos mouth, Direction direction, int left, int right, int mainLength,
                         Limit limit) {
        this.mouth = mouth.immutable();
        this.direction = direction;
        this.leftLength = side(left);
        this.rightLength = side(right);
        this.mainLength = Math.max(0, Math.min(MAX_MAIN_LENGTH, mainLength));
        for (int along = 1; along <= this.mainLength; along++) {
            BlockPos corridor = this.mouth.relative(direction, along);
            Stop stop = limit.why(corridor);
            if (stop != null) {
                corridorStop = stop;
                ends.add(new End(corridor, stop, false));
                break;
            }
            add(new Cell(corridor, false, along));
            corridorLength = along;
            if (along % BRANCH_SPACING != 0) continue;
            for (Direction side : new Direction[] {
                    direction.getCounterClockWise(), direction.getClockWise() }) {
                int length = side == direction.getClockWise() ? rightLength : leftLength;
                for (int out = 1; out <= length; out++) {
                    BlockPos branch = corridor.relative(side, out);
                    Stop branchStop = limit.why(branch);
                    if (branchStop != null) {
                        ends.add(new End(branch, branchStop, true));
                        break;
                    }
                    add(new Cell(branch, true, along));
                }
            }
        }
    }

    private static int side(int length) {
        return Math.max(0, Math.min(MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE, length));
    }

    /** Corridor blocks in the plan, from the mouth: {@link #mainLength()} unless it was stopped. */
    public int corridorLength() { return corridorLength; }

    /** Why the corridor stops short of {@link #mainLength()}, or null when it reaches it. */
    @Nullable
    public Stop corridorStop() { return corridorStop; }

    /** Every corridor or branch that stops short, in dig order. */
    public List<End> ends() { return java.util.Collections.unmodifiableList(ends); }

    /** How many side branches stop short for {@code why}. */
    public int branchesCut(Stop why) {
        int count = 0;
        for (End end : ends) if (end.branch() && end.why() == why) count++;
        return count;
    }

    private void add(Cell cell) {
        cells.add(cell);
        floors.add(cell.floor());
    }

    public int cellCount() { return cells.size(); }

    /** Side tunnel length on the left (counter-clockwise from {@link #direction()}). */
    public int leftLength() { return leftLength; }

    /** Side tunnel length on the right (clockwise from {@link #direction()}). */
    public int rightLength() { return rightLength; }

    /** The longer of the two side tunnel lengths. */
    public int widest() { return Math.max(leftLength, rightLength); }

    /** How long the corridor may get: this mine's setting, at most {@link #MAX_MAIN_LENGTH}. */
    public int mainLength() { return mainLength; }

    /**
     * Cell count of the default, unrestricted plan. Variable plans use
     * {@link #cellCount()}.
     *
     * <p>Exposed so a caller that scans the plan can size its budget off the
     * plan rather than off a constant of its own. Getting that wrong is silent
     * and total: a scan that stops short of the end reports "no work left" for
     * a shaft with a hundred cells still in it, and the miner retires.
     */
    public static int totalCells() {
        return (MAX_MAIN_LENGTH / BRANCH_SPACING) * (BRANCH_SPACING + 2 * BRANCH_LENGTH);
    }

    public BlockPos mouth() {
        return mouth;
    }

    public Direction direction() {
        return direction;
    }

    /**
     * One cell of the plan: a floor position the miner will stand on or dig
     * out, and whether it is corridor or branch.
     *
     * @param floor the feet-level block; the head-level block directly above it
     *              belongs to the same cell
     */
    public record Cell(BlockPos floor, boolean branch, int mainDistance) {}

    /**
     * The {@code index}-th cell in dig order, or {@code null} once the plan
     * runs past {@link #MAX_MAIN_LENGTH}.
     *
     * <p>The cycle is: {@link #BRANCH_SPACING} corridor cells, then the left
     * branch, then the right branch, then the next stretch of corridor.
     */
    @Nullable
    public Cell cell(int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : null;
    }

    /**
     * Whether this block is somewhere the shaft is meant to be open.
     *
     * <p>Answered from the geometry rather than by walking the cell list, so it
     * is exact and costs nothing. That matters more than it sounds: the miner
     * walls off open air it meets, and the first version tested "is this on the
     * plan" by checking a couple of cells either side of wherever it had got
     * to. Branch cells interleave with corridor cells in dig order, so the
     * corridor block physically behind the miner was often dozens of indices
     * away and failed the test -- and the miner bricked up the tunnel it was
     * standing in, dug it out again, and bricked it up again.
     */
    public boolean contains(BlockPos pos) {
        int height = pos.getY() - mouth.getY();
        if (height < 0 || height >= TUNNEL_HEIGHT) return false;
        return floors.contains(pos.below(height));
    }

    /**
     * Where the miner stands to dig {@code index}: the cell before it.
     *
     * <p>Digging the cell you are standing in drops you into your own hole, and
     * digging from two cells back leaves a block you cannot reach. The cell
     * immediately behind is both walkable by then and within arm's reach.
     */
    @Nullable
    public BlockPos standFor(int index) {
        Cell target = cell(index);
        if (target == null) return null;
        if (index == 0) return mouth;
        Cell previous = cell(index - 1);
        if (previous == null) return mouth;
        // Stepping from a finished branch to the next corridor cell means the
        // previous cell is out at the end of a branch, which is nowhere near
        // the next target. Fall back to the junction it hangs off.
        if (previous.floor().distManhattan(target.floor()) != 1) {
            return mouth.relative(direction, previous.mainDistance());
        }
        if (!previous.branch() && target.branch()) {
            return mouth.relative(direction, target.mainDistance());
        }
        return previous.floor();
    }
}
