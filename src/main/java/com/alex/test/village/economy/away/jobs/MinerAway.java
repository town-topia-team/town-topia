package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayMines;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.PlayerGear;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.MineShaftPlan;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.miner.PickaxeUtils;
import com.alex.test.village.villagers.miner.behavior.GetPickaxeActivity;
import com.alex.test.village.villagers.miner.behavior.MinerActivity;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A miner's away day (round 8 of the plan), in the village's real mines
 * ({@link AwayMines}): the shaft is dug on from where it stands, block by
 * block, so a dug-out mine gives nothing and the stone and ore are the ones
 * really there (old bugs M1, M2, M8).
 *
 * <p>Live rules it copies ({@code MinerActivity}), and where they live:
 * <ul>
 *   <li>Checks: work on ({@code WORK_ENABLED}); a usable pickaxe of an allowed tier first, from storage
 *       or made ({@code GetPickaxeActivity.PICKAXE_REQUIREMENT}; old bug M4); then a mine: not paused,
 *       not held by anybody else, one miner a mine, in live order ({@code awayMineOrder}: the nearest,
 *       or best for the wanted ores; old bug M9), its held mine first ({@code claimMine}).</li>
 *   <li>Order, as {@code startNextStint}: the doorway, then the vein already broken into, then its holes
 *       under the floor filled, then the plan's cells in dig order, each top down
 *       ({@code firstSolidOfCell}); a building's cell is passed ({@code isCellBlocked}), water or lava in
 *       a cell is filled and dug like rock ({@code fillFluidInCell}), an open cell with no floor gets one
 *       ({@code missingFloor}, {@code PLUG_TICKS}). It stops at the first cell in a chunk that is not
 *       loaded (old bug M3) or at the plan's end.</li>
 *   <li>Each block: {@code digRefusal}; the fastest allowed pickaxe read again for every block
 *       ({@code beginMining}; old bug M5), its {@code mineTicks}; drops from the block itself with that
 *       pickaxe (Fortune, never Silk Touch), scrap ore only from plain stone ({@code yieldSource},
 *       {@code yieldOf}); a point of wear, Mending from the ore's experience ({@code wearPickaxe}); the
 *       pay from those ticks ({@code onMinerBlockMined}; old bug M6); then {@code sealHazardsAround},
 *       {@code collectVein} (by skill) and {@code maybeLightCorridor}.</li>
 *   <li>Torches: a cell with light 8 or less gets one on its floor: carried, or made from coal or
 *       charcoal and a stick; at a stint's start, or when it is dark, fetched (torches 16, else sticks 4,
 *       else coal 2), two trips a stint ({@code fetchTorchMakings}; old bug M7).</li>
 *   <li>Stints: {@code stintBlocks} blocks or no empty slot, then the holes filled and a bank
 *       ({@code finishSession}); a block whose drops do not fit is banked for first.</li>
 * </ul>
 * Not done away: wall ore, ore sense, leaks into the dug part, the furnace trip for charcoal
 * ({@code MakeTorchesActivity}), pickaxe upgrades from storage, standing places (the plan's own stand
 * tile must be open), walls round a bridged floor.
 */
public final class MinerAway implements AwayJob {

    public static final MinerAway INSTANCE = new MinerAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    private MinerAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        if (!ProfessionSettings.of(ctx.npc(), MinerSettingsSchema.SCHEMA).getBool(MinerSettingsSchema.WORK_ENABLED)) {
            return "work off";
        }
        if (ctx.mines().listed() == 0) return "no mine";
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    /** The next thing to do in the mine: a block to dig or to set, or a cell's fluid to fill. */
    private enum Kind { DIG, PLUG, FILL }

    private record Target(Kind kind, BlockPos pos, @Nullable MineShaftPlan.Cell cell, boolean vein) {}

    /** A target, or why there is none. */
    private record Next(@Nullable Target target, @Nullable String stop) {}

    /** One miner's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final NpcEntity npc;
        private final UUID id;
        private final InventoryManager pack;
        private final ServerLevel level;
        private final RandomSource random;
        private final Activity.Context live;
        private final AwayMines mines;
        private final Set<Item> allowed;
        /** The miner's clock, in ticks from the start of its work hours. */
        private long t;
        private final long end;
        @Nullable private AwayMines.Mine mine;
        @Nullable private MineShaftPlan plan;
        /** The last block dug, for the steps between blocks. */
        @Nullable private BlockPos face;
        private int stintBlocks;
        private int supplyTrips;
        /** The miner's clock at the next look in storage for torch makings ({@code torchLookAfter}). */
        private long nextTorchLook = Long.MIN_VALUE;
        private boolean stintStart = true;
        private boolean storageFull;
        /** Why the day ended before its hours did; null when the hours ran out. */
        @Nullable private String stop;
        private final Map<Item, Integer> gross = new LinkedHashMap<>();
        private int blocks;
        private int ores;
        private long mined;
        private int plugs;
        private int fills;
        private int seals;
        private int holes;
        private int torches;
        private int crafted;
        private int refused;
        private int outOfReach;
        private int broken;
        private int pickaxes;
        private int stints;
        private int banks;
        private int trips;
        private int heldByOther;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.npc = ctx.npc();
            this.id = npc.getNpcId();
            this.pack = npc.getInventory();
            this.level = ctx.level;
            this.random = ctx.level.random;
            this.live = ctx.activity();
            this.mines = ctx.mines();
            this.allowed = MinerActivity.allowedPickaxes(live);
            this.end = Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
        }

        void run() {
            // A pickaxe before the mine, as live hasWork.
            if (pickaxeSlot() < 0 && !getPickaxe()) {
                finish();
                return;
            }
            mine = pickMine();
            if (mine == null) {
                if (stop == null) stop = "no free mine with rock it can reach";
                finish();
                return;
            }
            plan = mine.plan();
            t += AwayWalk.walk(Math.sqrt(npc.blockPosition().distSqr(plan.mouth())));
            face = plan.mouth();
            work();
            // The hours are over: holes filled and the load banked, as live finishSession and the evening deposit.
            closeStint();
            if (!storageFull && AwayPack.depositable(ctx) > 0 && AwayPack.bank(ctx) > 0) banks++;
            finish();
        }

        private void work() {
            while (t < end) {
                if (stintBlocks >= MinerBalance.stintBlocks(skill()) || !pack.hasEmptySlot()) {
                    if (!endStint()) return;
                    continue;
                }
                Next next = next();
                Target target = next.target();
                if (target == null) {
                    stop = next.stop();
                    return;
                }
                // After the look for work, so a dug-out mine costs no torch trip; the target stays as it was.
                supplyTorches();
                switch (target.kind()) {
                    case FILL -> fill(target.cell());
                    case PLUG -> plug(target.pos());
                    case DIG -> {
                        if (!dig(target)) return;
                    }
                }
            }
        }

        // ---------------------------------------------------------------------
        // Choosing the mine
        // ---------------------------------------------------------------------

        /** The mine live would take ({@code claimMine}), with work in it, or null. */
        @Nullable
        private AwayMines.Mine pickMine() {
            List<MineBuilding> order = new ArrayList<>(MinerActivity.awayMineOrder(live));
            // The one it held when the village fell asleep first, then the one it worked yesterday.
            order.sort(Comparator.comparing((MineBuilding building) -> !id.equals(ctx.mgr.getClaims().holder(building.mouth()))));
            AwayMines.Mine own = mines.ownedBy(id);
            if (own != null && order.remove(own.building())) order.add(0, own.building());
            for (MineBuilding building : order) {
                UUID holder = ctx.mgr.getClaims().holder(building.mouth());
                if (holder != null && !holder.equals(id)) {
                    heldByOther++;
                    continue;
                }
                AwayMines.Mine candidate = mines.mine(live, building);
                if (candidate.owner() != null && !candidate.owner().equals(id)) {
                    heldByOther++;
                    continue;
                }
                if (candidate.plan() == null) continue;
                mine = candidate;
                plan = candidate.plan();
                Next next = next();
                if (next.target() != null) {
                    candidate.own(id);
                    return candidate;
                }
                stop = next.stop();
            }
            mine = null;
            plan = null;
            return null;
        }

        // ---------------------------------------------------------------------
        // The next block
        // ---------------------------------------------------------------------

        /** Live's order in {@code startNextStint}, on the blocks as the miners have left them. */
        private Next next() {
            // The doorway, top down.
            BlockPos doorway = null;
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos at = plan.mouth().above(dy);
                if (mine.setAside(at)) continue;
                BlockState state = mines.state(at);
                if (state == null) return new Next(null, "doorway not loaded");
                if (MinerActivity.isDiggable(state)) doorway = at;
            }
            if (doorway != null) return dig(doorway, null, false);
            BlockPos vein = nextVeinBlock();
            if (vein != null) return dig(vein, null, true);
            backfill();
            for (int index = mine.cursor(); ; index++) {
                MineShaftPlan.Cell cell = plan.cell(index);
                if (cell == null) {
                    mine.cursor(index);
                    return new Next(null, outOfReach > 0 ? "dug out, or the rest out of reach" : "dug out");
                }
                BlockPos floor = cell.floor();
                BlockPos stand = plan.standFor(index);
                if (!level.isLoaded(floor.below()) || !level.isLoaded(floor.above())
                        || stand == null || !level.isLoaded(stand)) {
                    mine.cursor(index);
                    return new Next(null, "tunnel reaches ground not loaded");
                }
                mine.cursor(index);
                if (blocked(floor)) continue;
                BlockPos solid = firstSolid(floor);
                if (solid != null) {
                    if (open(stand)) return dig(solid, cell, false);
                    outOfReach++;
                    continue;
                }
                if (!mine.filled(floor) && needsFill(floor)) return new Next(new Target(Kind.FILL, floor, cell, false), null);
                BlockPos gap = missingFloor(floor);
                if (gap != null && !mine.setAside(gap)) return new Next(new Target(Kind.PLUG, gap, cell, false), null);
            }
        }

        private static Next dig(BlockPos pos, @Nullable MineShaftPlan.Cell cell, boolean vein) {
            return new Next(new Target(Kind.DIG, pos, cell, vein), null);
        }

        /** Another building's block in the cell ({@code isCellBlocked}). */
        private boolean blocked(BlockPos floor) {
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                if (mine.rules().isProtected(floor.above(dy))) return true;
            }
            return false;
        }

        /** The cell's next block to break, head first ({@code firstSolidOfCell}). */
        @Nullable
        private BlockPos firstSolid(BlockPos floor) {
            for (int dy = MineShaftPlan.TUNNEL_HEIGHT - 1; dy >= 0; dy--) {
                BlockPos at = floor.above(dy);
                if (mine.setAside(at)) continue;
                BlockState state = mines.state(at);
                if (state != null && MinerActivity.isDiggable(state)) return at;
            }
            return null;
        }

        /** Feet and head room open: the plan's stand tile for the next cell (my call for live's stand search). */
        private boolean open(BlockPos pos) {
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos at = pos.above(dy);
                BlockState state = mines.state(at);
                if (state == null || !state.getCollisionShape(level, at).isEmpty()) return false;
            }
            return true;
        }

        /** Water or lava standing in a cell to dig, where the miner may dig ({@code cellNeedsFluidWork}). */
        private boolean needsFill(BlockPos floor) {
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos at = floor.above(dy);
                if (mine.rules().isProtected(at) || !mine.rules().permitted(at)) continue;
                BlockState state = mines.state(at);
                if (state != null && MinerActivity.isLooseFluid(state)) return true;
            }
            return false;
        }

        /** The block under an open cell that should be its floor and is not ({@code missingFloor}). */
        @Nullable
        private BlockPos missingFloor(BlockPos floor) {
            BlockState feet = mines.state(floor);
            BlockState head = mines.state(floor.above());
            if (feet == null || head == null) return null;
            if (MinerActivity.isDiggable(feet) || MinerActivity.isDiggable(head)) return null;
            BlockPos below = floor.below();
            if (mine.pits().contains(below)) return null;
            BlockState under = mines.state(below);
            if (under == null || !MinerActivity.canPlugOver(under)) return null;
            if (mine.rules().isProtected(below) || !mine.rules().permitted(below)) return null;
            if (level.canSeeSky(floor)) return null;
            return below;
        }

        /** The next queued ore still there, with an open face ({@code nextVeinBlock}). */
        @Nullable
        private BlockPos nextVeinBlock() {
            Deque<BlockPos> vein = mine.vein();
            while (!vein.isEmpty()) {
                BlockPos candidate = vein.peek();
                BlockState state = mines.state(candidate);
                if (!mine.setAside(candidate) && state != null && mine.rules().permitted(candidate)
                        && MinerActivity.isOre(state) && hasOpenFace(candidate)) return candidate;
                vein.poll();
            }
            mine.veinTaken(0);
            return null;
        }

        private boolean hasOpenFace(BlockPos pos) {
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                BlockState state = mines.state(next);
                if (state != null && state.getCollisionShape(level, next).isEmpty()) return true;
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // Digging
        // ---------------------------------------------------------------------

        /** Breaks one block ({@code breakBlock}); false when the day ends here. */
        private boolean dig(Target target) {
            BlockPos at = target.pos();
            BlockState state = mines.state(at);
            if (state == null || mine.rules().isProtected(at) || !mine.rules().permitted(at)) {
                mine.putAside(at);
                refused++;
                return true;
            }
            // Never opened beside a chunk that is not loaded: what is behind it cannot be seen or sealed.
            if (!sidesLoaded(at)) {
                if (target.vein()) {
                    mine.putAside(at);
                    return true;
                }
                stop = "tunnel reaches ground not loaded";
                return false;
            }
            int slot = pickaxeSlot();
            if (slot < 0) {
                if (!getPickaxe()) return false;
                slot = pickaxeSlot();
                if (slot < 0) return false;
            }
            ItemStack tool = pack.getSlot(slot);
            int ticks = MinerBalance.mineTicks(tool.getItem(), PickaxeUtils.efficiencyLevel(level, tool));
            int skill = skill();
            BlockState source = MinerActivity.yieldSource(state, at.getY(), skill, random);
            boolean ore = MinerActivity.isOre(source);
            // A player's pickaxe digs with its enchantments at their share, as live.
            ItemStack working = PlayerGear.effective(tool, random);
            int experience = ore ? EnchantmentHelper.processBlockExperience(level, working,
                    source.getBlock().getExpDrop(source, level, at, null, npc, working)) : 0;
            List<ItemStack> stacks = new ArrayList<>();
            for (ItemStack drop : MinerActivity.yieldOf(level, at, source, npc, working)) {
                ItemStack yield = VillageItemOwnership.produce(drop, ctx.mgr);
                if (!yield.isEmpty()) stacks.add(yield);
            }
            if (!AwayPack.fits(pack, stacks)) {
                if (!endStint()) return false;
                if (!AwayPack.fits(pack, stacks)) {
                    stop = storageFull ? "storage full" : "pack full";
                    return false;
                }
                slot = pickaxeSlot();
                if (slot < 0) return false;
            }
            // The steps from the last block, then the swings; a block begun is finished.
            if (face != null) t += AwayWalk.walk(Math.max(0, face.distManhattan(at) - 1));
            t += ticks;
            for (ItemStack stack : stacks) {
                int count = stack.getCount();
                Item item = stack.getItem();
                if (pack.addItem(stack)) gross.merge(item, count, Integer::sum);
            }
            boolean survived = PickaxeUtils.damageForBlock(level, pack, slot);
            if (!survived) {
                broken++;
            } else {
                PickaxeUtils.repairFromExperience(level, pack.getSlot(slot), experience);
            }
            mines.dig(mine, at, ore);
            face = at.immutable();
            stintStart = false;
            stintBlocks++;
            blocks++;
            if (ore) ores++;
            mined += ticks;
            if (mine.vein().remove(at)) mine.veinTaken(mine.veinTaken() + 1);
            if (at.getY() < plan.mouth().getY()) mine.pits().add(at.immutable());
            AwayXp.minerBlock(ctx, ore, ticks);
            seal(at);
            collectVein(at);
            light();
            // Broken with no spare: the stint ends here, the next block fetches one (live endStint).
            if (!survived && pickaxeSlot() < 0) closeStint();
            return true;
        }

        private boolean sidesLoaded(BlockPos pos) {
            for (Direction dir : Direction.values()) {
                if (!level.isLoaded(pos.relative(dir))) return false;
            }
            return true;
        }

        /** Water or lava in the cell walled to be dug like rock ({@code fillFluidInCell}); a tick, as live. */
        private void fill(@Nullable MineShaftPlan.Cell cell) {
            if (cell == null) return;
            BlockPos floor = cell.floor();
            mine.markFilled(floor);
            for (int dy = 0; dy < MineShaftPlan.TUNNEL_HEIGHT; dy++) {
                BlockPos at = floor.above(dy);
                if (mine.rules().isProtected(at) || !mine.rules().permitted(at)) continue;
                BlockState state = mines.state(at);
                if (state == null || !MinerActivity.isLooseFluid(state)) continue;
                mines.cobble(at);
                fills++;
            }
            t++;
        }

        /** A floor under an open cell ({@code plug}): {@code PLUG_TICKS}, no cobblestone taken. */
        private void plug(BlockPos at) {
            mines.cobble(at);
            mine.putAside(at);
            plugs++;
            t += MinerActivity.PLUG_TICKS;
        }

        /** Walls off water, lava and cave openings round the block just taken ({@code sealHazardsAround}). */
        private void seal(BlockPos at) {
            for (Direction dir : Direction.values()) {
                BlockPos side = at.relative(dir);
                if (mine.rules().isProtected(side)) continue;
                BlockState state = mines.state(side);
                if (state == null) continue;
                boolean fluid = MinerActivity.isLooseFluid(state);
                // Open air in the neighbouring mine's rock is its tunnel: never walled.
                if (!mine.rules().ownedByMine(side) && !fluid) continue;
                if (mine.rules().isPartOfShaft(side)) {
                    // Fluid in the shaft's own path ahead: filled now, dug through like rock later.
                    if (fluid) {
                        mines.cobble(side);
                        seals++;
                    }
                    continue;
                }
                if (!fluid && !foreignOpening(side, dir)) continue;
                mines.cobble(side);
                seals++;
            }
        }

        /** Open air beside the shaft that leads somewhere else ({@code isForeignOpening}). */
        private boolean foreignOpening(BlockPos pos, Direction dir) {
            BlockState state = mines.state(pos);
            if (state == null || !state.isAir()) return false;
            if (mine.dug(pos)) return false;
            // Under the corridor floor: a hole to fall into, whatever it leads to.
            if (mine.rules().isPartOfShaft(pos.above())) return true;
            if (dir == Direction.UP && level.canSeeSky(pos)) return false;
            if (MinerActivity.isWalkway(live, pos)) return false;
            return leadsSomewhere(pos);
        }

        /** More than {@code CAVE_AIR_LIMIT} open blocks behind it, or the sky ({@code leadsSomewhere}). */
        private boolean leadsSomewhere(BlockPos start) {
            Set<Long> seen = new HashSet<>();
            Deque<BlockPos> open = new ArrayDeque<>();
            seen.add(start.asLong());
            open.add(start);
            while (!open.isEmpty()) {
                BlockPos pos = open.poll();
                if (level.canSeeSky(pos)) return true;
                for (Direction dir : Direction.values()) {
                    BlockPos next = pos.relative(dir);
                    if (seen.contains(next.asLong()) || mine.rules().isPartOfShaft(next) || mine.dug(next)) continue;
                    BlockState state = mines.state(next);
                    // A chunk not loaded may hold anything: counted as a way on, as live.
                    if (state == null) return true;
                    if (!state.getCollisionShape(level, next).isEmpty()) continue;
                    seen.add(next.asLong());
                    if (seen.size() > MinerActivity.CAVE_AIR_LIMIT) return true;
                    open.add(next);
                }
            }
            return false;
        }

        /** Ore touching the block just taken, up to the skill's vein length ({@code collectVein}). */
        private void collectVein(BlockPos at) {
            int limit = MinerBalance.veinBlocks(skill());
            Deque<BlockPos> vein = mine.vein();
            if (mine.veinTaken() + vein.size() >= limit) return;
            for (Direction dir : Direction.values()) {
                BlockPos side = at.relative(dir);
                if (mine.rules().isPartOfShaft(side) || mine.rules().isProtected(side)
                        || !mine.rules().permitted(side)) continue;
                BlockState state = mines.state(side);
                if (state == null || !MinerActivity.isOre(state)) continue;
                if (!mine.rules().insideMine(side) || openToSky(side)) continue;
                if (vein.contains(side) || mine.setAside(side)) continue;
                vein.add(side.immutable());
                if (mine.veinTaken() + vein.size() >= limit) return;
            }
        }

        private boolean openToSky(BlockPos pos) {
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                BlockState state = mines.state(next);
                if (state != null && state.isAir() && level.canSeeSky(next)) return true;
            }
            return false;
        }

        /** The holes under the floor filled from the bottom ({@code backfillPits}); free, as live. */
        private void backfill() {
            if (!mine.vein().isEmpty() || mine.pits().isEmpty()) return;
            List<BlockPos> order = new ArrayList<>(mine.pits());
            order.sort(Comparator.comparingInt(BlockPos::getY));
            for (BlockPos pos : order) {
                BlockState state = mines.state(pos);
                if (state == null || !MinerActivity.canPlugOver(state)) {
                    mine.pits().remove(pos);
                    continue;
                }
                if (mine.rules().isProtected(pos)) continue;
                mines.cobble(pos);
                mine.pits().remove(pos);
                holes++;
            }
        }

        // ---------------------------------------------------------------------
        // Torches
        // ---------------------------------------------------------------------

        /** A torch on the floor of the cell being dug, if it is dark ({@code maybeLightCorridor}). */
        private void light() {
            MineShaftPlan.Cell cell = plan.cell(mine.cursor());
            if (cell == null || !dark(cell)) return;
            if (count(Items.TORCH) <= 0 && !craftTorches()) return;
            BlockPos spot = cell.floor();
            BlockState feet = mines.state(spot);
            BlockState head = mines.state(spot.above());
            BlockState under = mines.state(spot.below());
            if (feet == null || head == null || under == null) return;
            if (!feet.isAir() || !head.isAir()) return;
            if (!under.isFaceSturdy(level, spot.below(), Direction.UP)) return;
            if (!consume(Items.TORCH, 1)) return;
            mines.torch(mine, spot);
            ctx.used(Items.TORCH, 1);
            torches++;
        }

        /** Light 8 or less round the cell's floor ({@code torchCell}, {@code lightAround}), this run's torches counted. */
        private boolean dark(MineShaftPlan.Cell cell) {
            BlockPos floor = cell.floor();
            int light = mines.torchLight(floor);
            for (Direction dir : Direction.values()) {
                BlockPos next = floor.relative(dir);
                if (level.isLoaded(next)) light = Math.max(light, level.getBrightness(LightLayer.BLOCK, next) - 1);
            }
            return light <= MinerActivity.TORCH_LIGHT_LEVEL;
        }

        /** One coal or charcoal and one stick make four torches ({@code craftTorches}). */
        private boolean craftTorches() {
            Item fuel = count(Items.COAL) > 0 ? Items.COAL : count(Items.CHARCOAL) > 0 ? Items.CHARCOAL : null;
            if (fuel == null || count(Items.STICK) <= 0) return false;
            ItemStack made = VillageItemOwnership.produce(new ItemStack(Items.TORCH, MinerActivity.TORCHES_PER_CRAFT), ctx.mgr);
            if (made.isEmpty() || !consume(fuel, 1) || !consume(Items.STICK, 1)) return false;
            int count = made.getCount();
            if (!pack.addItem(made)) return false;
            ctx.used(fuel, 1);
            ctx.used(Items.STICK, 1);
            ctx.made(Items.TORCH, count);
            crafted++;
            return true;
        }

        /** At a stint's start, or in a dark cell, with nothing to light it: a storage trip ({@code fetchTorchMakings}). */
        private void supplyTorches() {
            MineShaftPlan.Cell cell = plan.cell(mine.cursor());
            if (!stintStart && (cell == null || !dark(cell))) return;
            if (count(Items.TORCH) > 0) return;
            boolean fuel = count(Items.COAL) > 0 || count(Items.CHARCOAL) > 0;
            boolean sticks = count(Items.STICK) > 0;
            if (fuel && sticks) return;
            if (supplyTrips >= MinerActivity.MAX_TORCH_SUPPLY_TRIPS || t < nextTorchLook) return;
            Item item = null;
            int amount = 0;
            if (ctx.countInStorage(Items.TORCH, Profession.MINER) > 0) {
                item = Items.TORCH;
                amount = 16;
            } else if (fuel) {
                if (ctx.countInStorage(Items.STICK, Profession.MINER) > 0) {
                    item = Items.STICK;
                    amount = 4;
                }
            } else if (ctx.countInStorage(Items.COAL, Profession.MINER) > 0) {
                item = Items.COAL;
                amount = 2;
            }
            // As live: only a trip that starts counts, and a look that found nothing waits.
            if (item == null) {
                nextTorchLook = t + MinerActivity.TORCH_LOOK_WAIT_TICKS;
                return;
            }
            supplyTrips++;
            fetch(item, amount);
        }

        private void fetch(Item item, int amount) {
            for (ItemStack stack : ctx.takeFromStorage(item, amount, Profession.MINER)) {
                if (!pack.addItem(stack)) ctx.store(stack);
            }
            // From the tunnel and back in; at a stint's start it is on the way.
            t += AwayWalk.TRIP_TICKS + (stintStart ? 0L : 2L * AwayWalk.walk(depth()));
            trips++;
        }

        // ---------------------------------------------------------------------
        // Pickaxe, pack and storage
        // ---------------------------------------------------------------------

        private int pickaxeSlot() {
            return PickaxeUtils.findFastestUsablePickaxeSlot(pack, allowed, level);
        }

        /** A pickaxe from storage or made; with none, a bank and a second try. */
        private boolean getPickaxe() {
            if (fetchPickaxe()) return true;
            if (!storageFull && AwayPack.depositable(ctx) > 0) {
                bankTrip();
                if (fetchPickaxe()) return true;
            }
            stop = "no pickaxe";
            return false;
        }

        private boolean fetchPickaxe() {
            if (!ctx.restock(GetPickaxeActivity.PICKAXE_REQUIREMENT)) return false;
            t += AwayWalk.TRIP_TICKS + (plan != null ? 2L * AwayWalk.walk(depth()) : 0L);
            trips++;
            pickaxes++;
            return pickaxeSlot() >= 0;
        }

        /** The stint's end ({@code finishSession}): its holes filled, then a bank. False when the day ends here. */
        private boolean endStint() {
            closeStint();
            if (storageFull || AwayPack.depositable(ctx) == 0) {
                if (pack.hasEmptySlot()) return true;
                stop = storageFull ? "pack full, storage full" : "pack full of what it keeps";
                return false;
            }
            bankTrip();
            if (!pack.hasEmptySlot()) {
                stop = "pack full, storage takes nothing";
                return false;
            }
            return true;
        }

        /** The stint ends where it stands: holes filled, counted for the session's lesson. */
        private void closeStint() {
            if (mine != null) backfill();
            if (stintBlocks > 0) stints++;
            stintBlocks = 0;
            supplyTrips = 0;
            nextTorchLook = Long.MIN_VALUE;
            stintStart = true;
        }

        /** Out of the tunnel to storage and back in. */
        private void bankTrip() {
            t += AwayWalk.TRIP_TICKS + (plan != null ? 2L * AwayWalk.walk(depth()) : 0L);
            trips++;
            if (AwayPack.bank(ctx) == 0) {
                storageFull = true;
                return;
            }
            banks++;
        }

        /** Blocks from the mouth to where it digs. */
        private int depth() {
            return face != null && plan != null ? face.distManhattan(plan.mouth()) : 0;
        }

        private int count(Item item) {
            int total = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (pack.mayUse(stack) && stack.is(item)) total += stack.getCount();
            }
            return total;
        }

        private boolean consume(Item item, int amount) {
            if (count(item) < amount) return false;
            int left = amount;
            for (int slot = 0; slot < InventoryManager.SIZE && left > 0; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (!pack.mayUse(stack) || !stack.is(item)) continue;
                int take = Math.min(left, stack.getCount());
                pack.reduceSlot(slot, take);
                left -= take;
            }
            return left == 0;
        }

        private int skill() {
            return ctx.model().brain().minerSkillLevel();
        }

        private void finish() {
            gross.forEach(ctx::made);
            AwayXp.workSessions(ctx, stints);
            ctx.stoppedPartway(stop);
            LOGGER.info("[AwayMiner] {} day {}: mine {} dug {} block(s) ({} ore, {} tick(s) swinging), {} plug(s), "
                            + "{} fill(s), {} seal(s), {} hole(s) filled, {} torch(es) set ({} crafts), {} refused, "
                            + "{} cell(s) out of reach; {} tick(s) left of {}; {} pickaxe(s) broke, {} fetched, "
                            + "{} stint(s), banked {}, trips {}; {} mine(s) held by another; made {}{}",
                    id.toString().substring(0, 8), ctx.day.index() + 1,
                    mine != null ? mine.building().mouth().toShortString() : "none", blocks, ores, mined, plugs,
                    fills, seals, holes, torches, crafted, refused, outOfReach, Math.max(0L, end - t), end, broken,
                    pickaxes, stints, banks, trips, heldByOther, AwayRun.counts(gross),
                    stop == null ? "" : "; stopped: " + stop);
        }
    }
}
