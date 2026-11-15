package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The village's mines for one catch-up (round 8), shared by the miners: each
 * mine's plan and live dig rules ({@code MinerActivity.Rules}, made the first
 * time a miner looks at it), the one miner working it this run (a shaft holds
 * one miner, as live {@code claimMine}), and every block the miners changed so
 * far. Day 2 digs on from where day 1 stopped: {@link #state} answers from those
 * changes first, then from the real block, and never from a chunk that is not
 * loaded (null).
 *
 * <p>WRITE: one change per changed block, from the block first seen to the last
 * one wanted, in three passes so nothing opens before its walls are shut:
 * cobblestone (seals, plugs, filled holes) first, then the dug blocks, then
 * the torches. A block that no longer looks as it was seen is left alone;
 * cobblestone and torches never go on top of anybody.
 */
public final class AwayMines {

    /** One mine this run. Its working state (vein, holes, set aside) is its one miner's. */
    public static final class Mine {
        private final MineBuilding building;
        private final MinerActivity.Rules rules;
        /** The miner working it this run, or null while nobody has. */
        @Nullable private UUID owner;
        /** The plan cell to look at next: the ones before it are dug or passed. */
        private int cursor;
        /** Blocks this run dug, here (live {@code dug}). */
        private final Set<Long> dug = new HashSet<>();
        /** Blocks it may not take, passed by for the run (live {@code unreachable}). */
        private final Set<Long> setAside = new HashSet<>();
        /** Ore queued off the last block (live {@code vein}) and how much of the vein is taken. */
        private final Deque<BlockPos> vein = new ArrayDeque<>();
        private int veinTaken;
        /** Holes under the corridor floor, filled once the vein is done (live {@code pits}). */
        private final Set<BlockPos> pits = new HashSet<>();
        /** Plan cells whose water or lava was filled this run (live {@code fluidFills}, once is enough here). */
        private final Set<Long> filled = new HashSet<>();
        /** Torches set this run, for the light sum. */
        private final List<BlockPos> torches = new ArrayList<>();

        private Mine(MineBuilding building, MinerActivity.Rules rules) {
            this.building = building;
            this.rules = rules;
        }

        public MineBuilding building() { return building; }

        public MinerActivity.Rules rules() { return rules; }

        @Nullable public MineShaftPlan plan() { return rules.plan(); }

        @Nullable public UUID owner() { return owner; }

        public void own(UUID miner) { owner = miner; }

        public int cursor() { return cursor; }

        public void cursor(int index) { cursor = index; }

        public boolean dug(BlockPos pos) { return dug.contains(pos.asLong()); }

        public boolean setAside(BlockPos pos) { return setAside.contains(pos.asLong()); }

        public void putAside(BlockPos pos) { setAside.add(pos.asLong()); }

        public Deque<BlockPos> vein() { return vein; }

        public int veinTaken() { return veinTaken; }

        public void veinTaken(int count) { veinTaken = count; }

        public Set<BlockPos> pits() { return pits; }

        public boolean filled(BlockPos floor) { return filled.contains(floor.asLong()); }

        public void markFilled(BlockPos floor) { filled.add(floor.asLong()); }

        public List<BlockPos> torches() { return torches; }
    }

    private final ServerLevel level;
    private final VillageManager mgr;
    private final Map<BlockPos, Mine> mines = new LinkedHashMap<>();
    /** The block now wanted, by position. */
    private final Map<Long, BlockState> now = new HashMap<>();
    /** The block first seen there, in the order first touched. */
    private final Map<Long, BlockState> seen = new LinkedHashMap<>();
    private int listed;
    private int dugBlocks;
    private int ores;
    private int cobble;
    private int torchCount;
    // WRITE
    private int written;
    private int skipped;

    private AwayMines(ServerLevel level, VillageManager mgr) {
        this.level = level;
        this.mgr = mgr;
    }

    static AwayMines read(ServerLevel level, VillageManager mgr) {
        AwayMines result = new AwayMines(level, mgr);
        for (Building building : mgr.getBuildings()) {
            if (building instanceof MineBuilding mine && mine.isValid()) result.listed++;
        }
        return result;
    }

    /** Valid mines at READ. */
    public int listed() { return listed; }

    /** {@code building}'s mine this run, its plan made from {@code live} the first time. */
    public Mine mine(Activity.Context live, MineBuilding building) {
        return mines.computeIfAbsent(building.mouth().immutable(),
                mouth -> new Mine(building, new MinerActivity.Rules(live, building)));
    }

    /** The mine {@code miner} worked this run, or null. */
    @Nullable
    public Mine ownedBy(UUID miner) {
        for (Mine mine : mines.values()) {
            if (miner.equals(mine.owner)) return mine;
        }
        return null;
    }

    /** The block at {@code pos} as the miners left it so far; null in a chunk that is not loaded. */
    @Nullable
    public BlockState state(BlockPos pos) {
        BlockState changed = now.get(pos.asLong());
        if (changed != null) return changed;
        return level.isLoaded(pos) ? level.getBlockState(pos) : null;
    }

    /** The block at {@code pos} is now to be {@code state}. Only for a loaded position ({@link #state} not null). */
    public void set(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        if (!seen.containsKey(key)) {
            BlockState was = level.getBlockState(pos);
            if (was == state) return;
            seen.put(key, was);
        }
        now.put(key, state);
    }

    /** {@code mine}'s miner broke {@code pos}. */
    public void dig(Mine mine, BlockPos pos, boolean ore) {
        set(pos, Blocks.AIR.defaultBlockState());
        mine.dug.add(pos.asLong());
        dugBlocks++;
        if (ore) ores++;
    }

    /** Cobblestone set at {@code pos} (a seal, a plug or a filled hole): free, as live. */
    public void cobble(BlockPos pos) {
        set(pos, Blocks.COBBLESTONE.defaultBlockState());
        cobble++;
    }

    /** A torch set at {@code pos}. */
    public void torch(Mine mine, BlockPos pos) {
        set(pos, Blocks.TORCH.defaultBlockState());
        mine.torches.add(pos.immutable());
        torchCount++;
    }

    /** Block light at {@code pos} from the torches set this run in any mine: 14 less the steps to the nearest. */
    public int torchLight(BlockPos pos) {
        int light = 0;
        for (Mine mine : mines.values()) {
            for (BlockPos torch : mine.torches) {
                light = Math.max(light, 14 - torch.distManhattan(pos));
            }
        }
        return light;
    }

    /** WRITE: the changes, cobblestone first, then the dug blocks, then the torches. */
    void writeTo(AwayWorld world) {
        for (int pass = 0; pass < 3; pass++) {
            for (Map.Entry<Long, BlockState> entry : seen.entrySet()) {
                BlockState wanted = now.get(entry.getKey());
                if (wanted == null || wanted == entry.getValue() || pass(wanted) != pass) continue;
                world.add(new MineBlock(BlockPos.of(entry.getKey()), entry.getValue(), wanted));
            }
        }
    }

    private static int pass(BlockState wanted) {
        if (wanted.isAir()) return 1;
        return wanted.is(Blocks.TORCH) ? 2 : 0;
    }

    /** For the {@code [AwayRun]} lines and the preview. */
    public String describe() {
        StringBuilder text = new StringBuilder();
        text.append(listed).append(" mine(s), ").append(mines.size()).append(" looked at");
        int worked = 0;
        for (Mine mine : mines.values()) {
            if (mine.owner != null) worked++;
        }
        text.append(", ").append(worked).append(" worked: dug ").append(dugBlocks).append(" block(s) (")
                .append(ores).append(" ore), ").append(cobble).append(" cobblestone set, ")
                .append(torchCount).append(" torch(es)");
        if (written > 0 || skipped > 0) {
            text.append("; written ").append(written).append(", ").append(skipped).append(" no longer as seen");
        }
        return text.toString();
    }

    /** One changed block at WRITE: only if it still looks as first seen. */
    private final class MineBlock implements AwayWorld.Change {
        private final BlockPos pos;
        private final BlockState was;
        private final BlockState wanted;

        MineBlock(BlockPos pos, BlockState was, BlockState wanted) {
            this.pos = pos;
            this.was = was;
            this.wanted = wanted;
        }

        @Override
        public boolean stillHolds(ServerLevel level) {
            boolean holds = holds(level);
            if (!holds) skipped++;
            return holds;
        }

        private boolean holds(ServerLevel level) {
            if (!level.isLoaded(pos)) return false;
            BlockState current = level.getBlockState(pos);
            if (current == wanted) return false;
            // Water, lava and air move and change level: still open is enough. Anything else exactly as seen.
            boolean asSeen = MinerActivity.canPlugOver(was) ? MinerActivity.canPlugOver(current) : current == was;
            if (!asSeen) return false;
            if (wanted.isAir()) return true;
            if (wanted.is(Blocks.TORCH) && !wanted.canSurvive(level, pos)) return false;
            // Never set on top of anybody (live occupied).
            return level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).deflate(0.05),
                    entity -> entity.isAlive() && !entity.isSpectator()).isEmpty();
        }

        @Override
        public void apply(ServerLevel level) {
            level.setBlock(pos, wanted, Block.UPDATE_ALL);
            // As live: the village hears only of a player's breaks.
            mgr.noteBlockChanged(pos);
            written++;
        }

        @Override
        public String describe() {
            return "mine block at " + pos.toShortString() + " " + was + " -> " + wanted;
        }
    }
}
