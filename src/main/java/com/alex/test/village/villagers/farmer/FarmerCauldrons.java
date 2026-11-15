package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.CauldronClaims;
import com.alex.test.village.building.CauldronIndex;
import com.alex.test.village.building.MuddyCauldrons;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Farmers and the village's cauldrons: which cauldron a dirty farmer washes in
 * tonight, which cauldrons need water, where the buckets and the water are.
 *
 * <p>The rules, as the author set them:
 * <ul>
 *   <li>Only cauldrons inside a house or a storage room count
 *       ({@link CauldronIndex}). The farmer's own house comes first; otherwise
 *       the nearest one in a storage room or anybody else's house.</li>
 *   <li>A cauldron needs water when it is empty, or holds muddy water below
 *       full. Muddy water is tipped out before clean water goes in.</li>
 *   <li>A water bucket already filled, carried or in storage, saves the trip to
 *       the water.</li>
 * </ul>
 */
public final class FarmerCauldrons {

    private FarmerCauldrons() {}

    /** How long a farmer leaves a cauldron alone after a refill of it failed. */
    public static final int REFILL_COOLDOWN_TICKS = 2400;
    /** How far from a cauldron its water source may be, in blocks. */
    public static final int WATER_SEARCH_RADIUS = 48;
    /** How far the graph scan for water reaches, when no farm water will do. */
    private static final int WATER_NODE_SCAN_RADIUS = 32;
    private static final int WATER_NODE_SCAN_DY = 6;
    /** Water sources tested per search, nearest first. */
    private static final int MAX_WATER_TESTS = 64;
    /** After a search finds no water, no new search for this long. */
    private static final int NO_WATER_REST_TICKS = 1200;
    /** How often an idle farmer looks for a cauldron to refill. */
    private static final int REFILL_CHECK_TICKS = 30;

    // =========================================================================
    // Washing
    // =========================================================================

    public enum Where {
        OWN_HOUSE("own house"), STORAGE("storage"), OTHER_HOUSE("other house");

        public final String label;

        Where(String label) { this.label = label; }
    }

    /**
     * Tonight's cauldron: where, how full, and whether the farmer refills it
     * first ({@code refillFirst}) or waits for someone else to finish with it
     * ({@code busy}). {@code ahead} is how many farmers are in its wash line
     * ahead of this one.
     */
    public record WashChoice(CauldronIndex.Spot spot, BlockPos stand, Where where, int level,
                             boolean refillFirst, boolean busy, double distance, int ahead) {}

    /** Why home was passed over, per farmer, so the diary says it only when the reason changes. */
    private static final Map<UUID, String> HOME_SKIPPED = new HashMap<>();

    /**
     * Picks tonight's cauldron, in the order the author set: a cauldron with
     * water in the farmer's own house; the evening refill of an empty own-house
     * cauldron nobody refilled today; a cauldron with water in the own house
     * that someone is using, to wait for (unless its line is two or more and a
     * free cauldron elsewhere is closer); the nearest free cauldron with water
     * in a storage room or another house; a cauldron with water someone else
     * is using; and last the refill of any empty cauldron. Null when there is
     * none the farmer can reach. When home loses, the diary says why.
     */
    @Nullable
    public static WashChoice pickWash(Activity.Context ctx) {
        WashChoice choice = pickBest(ctx);
        noteHomeSkipped(ctx, choice);
        return choice;
    }

    @Nullable
    private static WashChoice pickBest(Activity.Context ctx) {
        ServerLevel level = ctx.level();
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        UUID me = ctx.model().npcId;
        BlockPos here = ctx.npc().blockPosition();
        Building home = ctx.mgr().getAssignedBedBuilding(me);
        UUID homeId = home != null ? home.getItemFrameId() : null;
        long day = EveningSchedule.dayIndex(level.getDayTime());

        WashChoice ownFree = null, ownBusy = null, ownEmpty = null, otherFree = null, otherBusy = null;
        WashChoice otherEmpty = null;
        for (CauldronIndex.Spot spot : ctx.mgr().cauldrons().spots(ctx.mgr(), level)) {
            int water = CauldronIndex.waterLevel(level, spot.pos());
            if (water < 0) continue;
            BlockPos stand = CauldronIndex.standTile(spot.building(), spot.pos(), graph, here);
            if (stand == null || !ctx.model().canReach(graph, stand)) continue;
            boolean own = homeId != null && homeId.equals(spot.building().getItemFrameId());
            Where where = own ? Where.OWN_HOUSE
                    : spot.kind() == CauldronIndex.Kind.STORAGE ? Where.STORAGE : Where.OTHER_HOUSE;
            double distance = Math.sqrt(stand.distSqr(here));
            boolean free = CauldronClaims.isFreeFor(level, spot.pos(), me);
            int ahead = CauldronClaims.ahead(level, spot.pos(), me);
            if (water == 0 && !refillUnderway(level, spot.pos())) {
                boolean cooling = ctx.mgr().cauldrons().onRefillCooldown(spot.pos(), me, level.getGameTime());
                if (free && ahead == 0 && !cooling) {
                    WashChoice refill = new WashChoice(spot, stand, where, 0, true, false, distance, ahead);
                    if (own && !ctx.mgr().cauldrons().refilledOn(spot.pos(), day)) ownEmpty = closer(ownEmpty, refill);
                    // Anyone's empty cauldron: refilled by the farmer that needs it,
                    // or a single shared cauldron is never filled again at night.
                    else otherEmpty = closer(otherEmpty, refill);
                }
                continue;
            }
            // Full up: the farmers already in its line use up the water left.
            if (ahead >= washesLeft(level, spot.pos())) continue;
            WashChoice choice = new WashChoice(spot, stand, where, Math.max(water, 0), false,
                    !free || ahead > 0, distance, ahead);
            if (own) {
                if (free) ownFree = closer(ownFree, choice);
                else ownBusy = closer(ownBusy, choice);
            } else if (free) {
                otherFree = closer(otherFree, choice);
            } else {
                otherBusy = closer(otherBusy, choice);
            }
        }
        if (ownFree != null) return ownFree;
        if (ownEmpty != null && canRefill(ctx, ownEmpty.spot())) return ownEmpty;
        if (ownBusy != null) {
            // Home first, unless a line of two or more waits there and a free cauldron is closer.
            if (otherFree != null && otherFree.distance() < ownBusy.distance()
                    && lineAt(level, me, ownBusy.spot().pos(), ownBusy.ahead()) >= 2) {
                return otherFree;
            }
            return ownBusy;
        }
        if (otherFree != null) return otherFree;
        if (otherBusy != null) return otherBusy;
        if (otherEmpty != null && canRefill(ctx, otherEmpty.spot())) return otherEmpty;
        return null;
    }

    /**
     * How long the line at a cauldron is for {@code me}: the farmers ahead in
     * its wash line, one more when a refiller outside the line holds it, and at
     * least one when someone else holds it.
     */
    private static int lineAt(ServerLevel level, UUID me, BlockPos cauldron, int ahead) {
        int line = ahead;
        CauldronClaims.Claim claim = CauldronClaims.holder(level, cauldron);
        if (claim != null && !claim.owner().equals(me) && claim.kind() == CauldronClaims.Kind.REFILL
                && !CauldronClaims.refillingInLine(level, cauldron)) {
            line++;
        }
        if (!CauldronClaims.isFreeFor(level, cauldron, me)) line = Math.max(line, 1);
        return line;
    }

    // Reasons the own house lost, as written in the diary.
    private static final String NO_CAULDRON = "no cauldron in house";
    private static final String NO_TILE = "no tile beside it";
    private static final String UNREACHABLE = "cannot reach it";
    private static final String REFILLED_TODAY = "empty, refilled today already";
    private static final String GAVE_UP = "empty, gave up on refilling it a while ago";
    private static final String NO_BUCKET_OR_WATER = "empty, no bucket or no water";

    /**
     * Writes one diary line when the farmer has a home and tonight's pick is
     * not in it (or there is none), but only when the reason differs from the
     * last one written. Cleared when home wins.
     */
    private static void noteHomeSkipped(Activity.Context ctx, @Nullable WashChoice choice) {
        UUID me = ctx.model().npcId;
        Building home = ctx.mgr().getAssignedBedBuilding(me);
        if (home == null || (choice != null && choice.where() == Where.OWN_HOUSE)) {
            HOME_SKIPPED.remove(me);
            return;
        }
        ServerLevel level = ctx.level();
        NavGraph graph = ctx.mgr().getGraph();
        BlockPos here = ctx.npc().blockPosition();
        UUID homeId = home.getItemFrameId();

        // The cauldron in the house nearest the farmer, and why it was passed over.
        CauldronIndex.Spot nearest = null;
        for (CauldronIndex.Spot spot : ctx.mgr().cauldrons().spots(ctx.mgr(), level)) {
            if (!homeId.equals(spot.building().getItemFrameId()) || CauldronIndex.waterLevel(level, spot.pos()) < 0) continue;
            if (nearest == null || spot.pos().distSqr(here) < nearest.pos().distSqr(here)) nearest = spot;
        }
        String reason;
        String where;
        if (nearest == null) {
            reason = NO_CAULDRON;
            where = "";
        } else {
            BlockPos pos = nearest.pos();
            where = " " + pos.toShortString();
            BlockPos stand = CauldronIndex.standTile(home, pos, graph, here);
            int water = CauldronIndex.waterLevel(level, pos);
            int ahead = CauldronClaims.ahead(level, pos, me);
            if (stand == null) {
                reason = NO_TILE;
            } else if (graph == null || !ctx.model().canReach(graph, stand)) {
                reason = UNREACHABLE;
            } else if (water == 0 && !refillUnderway(level, pos)) {
                long now = level.getGameTime();
                if (ctx.mgr().cauldrons().refilledOn(pos, EveningSchedule.dayIndex(level.getDayTime()))) {
                    reason = REFILLED_TODAY;
                } else if (ctx.mgr().cauldrons().onRefillCooldown(pos, me, now)) {
                    reason = GAVE_UP;
                } else {
                    reason = NO_BUCKET_OR_WATER;
                }
            } else if (ahead >= washesLeft(level, pos)) {
                reason = "line uses up the water (" + ahead + " ahead)";
            } else {
                reason = "line " + lineAt(level, me, pos, ahead) + " ahead, free cauldron closer";
            }
        }
        if (reason.equals(HOME_SKIPPED.get(me))) return;
        HOME_SKIPPED.put(me, reason);
        ctx.brain().diary().log(VillagerDiary.ts(level) + " WASH home skipped: " + reason + where);
    }

    /**
     * How many more washes the cauldron gives: one per water level, or a full
     * three when it is empty but being refilled.
     */
    public static int washesLeft(ServerLevel level, BlockPos cauldron) {
        int water = CauldronIndex.waterLevel(level, cauldron);
        if (water > 0) return water;
        return water == 0 && refillUnderway(level, cauldron) ? 3 : 0;
    }

    /**
     * Someone is refilling the empty cauldron: a farmer in its wash line, or
     * a refiller holding it (between tipping the muddy water and pouring).
     */
    public static boolean refillUnderway(ServerLevel level, BlockPos cauldron) {
        if (CauldronClaims.refillingInLine(level, cauldron)) return true;
        CauldronClaims.Claim claim = CauldronClaims.holder(level, cauldron);
        return claim != null && claim.kind() == CauldronClaims.Kind.REFILL;
    }

    private static WashChoice closer(@Nullable WashChoice best, WashChoice candidate) {
        return best == null || candidate.distance() < best.distance() ? candidate : best;
    }

    /** The evening refill of the farmer's own empty cauldron: a wash waits on it. */
    private static boolean canRefill(Activity.Context ctx, CauldronIndex.Spot spot) {
        return canRefill(ctx, spot.pos(), true);
    }

    /**
     * A bucket to hand (or in storage) and water within reach of this cauldron.
     * {@code urgent}: a wash waits on it, so a bucket the ranchers want may be taken.
     */
    public static boolean canRefill(Activity.Context ctx, BlockPos cauldron, boolean urgent) {
        return bucketSource(ctx, urgent) != BucketSource.NONE && findWater(ctx, cauldron) != null;
    }

    // =========================================================================
    // The morning wash
    // =========================================================================

    /** Farmers who went to bed dirty, and until when (game time) their morning wash stands. */
    private static final Map<UUID, Long> MORNING_WASH_UNTIL = new HashMap<>();
    /** Day time of noon: a morning wash not started by then is dropped for the evening's. */
    private static final long NOON = 6000;

    /**
     * Marks a farmer going to bed dirty for a wash first thing tomorrow, until
     * noon. Returns true when it was newly marked.
     */
    public static boolean oweMorningWash(UUID farmer, ServerLevel level) {
        long now = level.getGameTime();
        Long until = MORNING_WASH_UNTIL.get(farmer);
        if (until != null && now < until) return false;
        long timeOfDay = Math.floorMod(level.getDayTime(), 24000L);
        long toNoon = NOON - timeOfDay;
        if (toNoon <= 0) toNoon += 24000L;
        MORNING_WASH_UNTIL.put(farmer, now + toNoon);
        return true;
    }

    /** Whether the farmer still owes the wash it missed last night. */
    public static boolean owesMorningWash(UUID farmer, long now) {
        Long until = MORNING_WASH_UNTIL.get(farmer);
        if (until == null) return false;
        if (now >= until) {
            MORNING_WASH_UNTIL.remove(farmer);
            return false;
        }
        return true;
    }

    public static void clearMorningWash(UUID farmer) {
        MORNING_WASH_UNTIL.remove(farmer);
    }

    // =========================================================================
    // Refilling
    // =========================================================================

    /** Muddy water below full, or no water at all. */
    public static boolean needsWater(ServerLevel level, BlockPos cauldron) {
        int water = CauldronIndex.waterLevel(level, cauldron);
        if (water == 0) return true;
        return water > 0 && water < 3 && MuddyCauldrons.isDirtyWater(level, cauldron);
    }

    /**
     * The cauldron this farmer should refill next: empty ones first, then the
     * lowest, then the nearest. Skips cauldrons someone else holds, ones this
     * farmer gave up on a moment ago, and ones it cannot stand beside.
     */
    @Nullable
    public static CauldronIndex.Spot nextNeedy(Activity.Context ctx, @Nullable BlockPos exclude) {
        ServerLevel level = ctx.level();
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        UUID me = ctx.model().npcId;
        long now = level.getGameTime();
        BlockPos here = ctx.npc().blockPosition();
        CauldronIndex index = ctx.mgr().cauldrons();
        CauldronIndex.Spot best = null;
        int bestWater = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (CauldronIndex.Spot spot : index.spots(ctx.mgr(), level)) {
            if (spot.pos().equals(exclude)) continue;
            if (!needsWater(level, spot.pos())) continue;
            if (!CauldronClaims.isFreeFor(level, spot.pos(), me)) continue;
            if (index.onRefillCooldown(spot.pos(), me, now)) continue;
            int water = CauldronIndex.waterLevel(level, spot.pos());
            double dist = spot.pos().distSqr(here);
            if (water > bestWater || (water == bestWater && dist >= bestDist)) continue;
            BlockPos stand = CauldronIndex.standTile(spot.building(), spot.pos(), graph, here);
            if (stand == null || !ctx.model().canReach(graph, stand)) continue;
            best = spot;
            bestWater = water;
            bestDist = dist;
        }
        return best;
    }

    /** Last refill-work answer per farmer, so idle farmers look only every {@value #REFILL_CHECK_TICKS} ticks. */
    private static final Map<UUID, Long> REFILL_CHECKED_AT = new HashMap<>();
    private static final Map<UUID, BlockPos> REFILL_TARGET = new HashMap<>();

    /**
     * Whether an idle farmer has cauldron work: a water bucket to get rid of,
     * or a cauldron that needs water and a bucket to carry it in.
     */
    public static boolean hasRefillWork(Activity.Context ctx) {
        if (carriesWaterBucket(ctx.npc())) return true;
        return refillTarget(ctx) != null;
    }

    /** The cauldron an idle farmer would refill now, rechecked every {@value #REFILL_CHECK_TICKS} ticks. */
    @Nullable
    public static BlockPos refillTarget(Activity.Context ctx) {
        UUID me = ctx.model().npcId;
        long now = ctx.level().getGameTime();
        Long checked = REFILL_CHECKED_AT.get(me);
        if (checked != null && now >= checked && now - checked < REFILL_CHECK_TICKS) {
            BlockPos cached = REFILL_TARGET.get(me);
            if (cached == null || (needsWater(ctx.level(), cached)
                    && CauldronClaims.isFreeFor(ctx.level(), cached, me))) {
                return cached;
            }
        }
        REFILL_CHECKED_AT.put(me, now);
        CauldronIndex.Spot spot = bucketSource(ctx) == BucketSource.NONE ? null : nextNeedy(ctx, null);
        if (spot == null) {
            REFILL_TARGET.remove(me);
            return null;
        }
        REFILL_TARGET.put(me, spot.pos());
        return spot.pos();
    }

    /** Forget the cached answer, after a refill started, finished or failed. */
    public static void forgetRefillCheck(UUID farmer) {
        REFILL_CHECKED_AT.remove(farmer);
        REFILL_TARGET.remove(farmer);
    }

    // =========================================================================
    // Buckets
    // =========================================================================

    /** Where the farmer's bucket comes from, best first. */
    public enum BucketSource {
        HAND_WATER("hand water"), STORAGE_WATER("storage water"),
        HAND_EMPTY("hand empty"), STORAGE_EMPTY("storage empty"), NONE("none");

        public final String label;

        BucketSource(String label) { this.label = label; }
    }

    /** {@link #bucketSource(Activity.Context, boolean)} for a refill nobody is waiting on. */
    public static BucketSource bucketSource(Activity.Context ctx) {
        return bucketSource(ctx, false);
    }

    /**
     * Where this farmer's bucket would come from. Only buckets the Stock tab
     * does not hold count. An empty bucket in storage the ranchers need for
     * milk is left to them, unless the refill is {@code urgent}: an evening
     * wash is waiting on the water ({@link BucketNeeds#farmerMayTakeEmpty}).
     */
    public static BucketSource bucketSource(Activity.Context ctx, boolean urgent) {
        if (carriesWaterBucket(ctx.npc())) return BucketSource.HAND_WATER;
        if (storageHas(ctx, Items.WATER_BUCKET)) return BucketSource.STORAGE_WATER;
        if (slotOf(ctx.npc().getInventory(), Items.BUCKET) >= 0) return BucketSource.HAND_EMPTY;
        if (storageHas(ctx, Items.BUCKET)
                && BucketNeeds.farmerMayTakeEmpty(ctx.mgr(), ctx.level(), urgent)) return BucketSource.STORAGE_EMPTY;
        return BucketSource.NONE;
    }

    public static boolean carriesWaterBucket(NpcEntity npc) {
        return slotOf(npc.getInventory(), Items.WATER_BUCKET) >= 0;
    }

    /** First slot holding {@code item} that the villager may use, or -1. */
    public static int slotOf(InventoryManager inv, Item item) {
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.is(item) && inv.mayUse(stack)) return i;
        }
        return -1;
    }

    /** Storage holds one the farmer may take: the Stock tab does not keep them all. */
    private static boolean storageHas(Activity.Context ctx, Item item) {
        if (StockReserves.get(ctx.level().getServer())
                .free(ctx.mgr(), ctx.level(), item, ctx.npc().getProfession()) <= 0) return false;
        ItemStack wanted = new ItemStack(item);
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()
                    && storage.hasItem(ctx.level(), wanted)) return true;
        }
        return false;
    }

    /**
     * Turns the carried water bucket into an empty one, keeping its other
     * data (the village binding). Returns false when there was none.
     */
    public static boolean pourOutBucket(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        int slot = slotOf(inv, Items.WATER_BUCKET);
        if (slot < 0) return false;
        ItemStack water = inv.getSlot(slot);
        ItemStack empty = water.transmuteCopy(Items.BUCKET, 1);
        water.shrink(1);
        if (water.isEmpty()) inv.setSlot(slot, empty);
        else if (!inv.addItem(empty)) npc.spawnAtLocation(empty);
        return true;
    }

    /** Turns one carried empty bucket into a water bucket. Returns false when there was none. */
    public static boolean fillBucket(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        int slot = slotOf(inv, Items.BUCKET);
        if (slot < 0) return false;
        ItemStack empty = inv.getSlot(slot);
        ItemStack water = empty.transmuteCopy(Items.WATER_BUCKET, 1);
        empty.shrink(1);
        if (empty.isEmpty()) inv.setSlot(slot, water);
        else if (!inv.addItem(water)) npc.spawnAtLocation(water);
        return true;
    }

    /**
     * A water bucket nobody needs: poured out where the farmer stands (no water
     * block is placed), and the empty bucket kept for the deposit.
     */
    public static void emptyStrandedBucket(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (!pourOutBucket(npc)) return;
        ServerLevel level = ctx.level();
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 0.8f, 1.0f);
        level.sendParticles(ParticleTypes.SPLASH, npc.getX(), npc.getY() + 0.2, npc.getZ(),
                24, 0.4, 0.1, 0.4, 0.1);
        ctx.brain().diary().log(VillagerDiary.ts(level) + " REFILL emptied stranded water bucket");
    }

    // =========================================================================
    // Water
    // =========================================================================

    /** Plain still water, not a waterlogged block. */
    public static boolean isWaterSource(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.getBlockState(pos).is(Blocks.WATER)) return false;
        FluidState fluid = level.getFluidState(pos);
        return fluid.isSource() && fluid.getType() == Fluids.WATER;
    }

    /**
     * Where to fill a bucket for this cauldron: the nearest water source, by
     * distance from the cauldron, that has a routable floor tile beside it the
     * farmer can reach. Farm irrigation water is tried first; only when none of
     * it will do are the graph's floor tiles around the cauldron searched.
     * Cached per cauldron; a search that finds nothing rests for a while.
     */
    @Nullable
    public static CauldronIndex.WaterSpot findWater(Activity.Context ctx, BlockPos cauldron) {
        ServerLevel level = ctx.level();
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        CauldronIndex index = ctx.mgr().cauldrons();
        CauldronIndex.WaterSpot cached = index.waterSource(cauldron);
        if (cached != null) {
            NavNode node = graph.nodeAt(cached.stand().getX(), cached.stand().getY(), cached.stand().getZ());
            if (isWaterSource(level, cached.water()) && node != null && node.isRoutable()
                    && ctx.model().canReach(graph, cached.stand())) {
                return cached;
            }
            index.setWaterSource(cauldron, null);
        }
        long now = level.getGameTime();
        if (index.waterSearchResting(cauldron, now)) return null;

        long maxDistSq = (long) WATER_SEARCH_RADIUS * WATER_SEARCH_RADIUS;
        CauldronIndex.WaterSpot found = nearestUsable(ctx, graph, cauldron,
                ctx.mgr().farmers().waterPositions(), maxDistSq);
        if (found == null) found = nearestUsable(ctx, graph, cauldron, scanWater(level, graph, cauldron), maxDistSq);
        if (found == null) {
            index.restWaterSearch(cauldron, now + NO_WATER_REST_TICKS);
            return null;
        }
        index.setWaterSource(cauldron, found);
        return found;
    }

    private static CauldronIndex.WaterSpot nearestUsable(Activity.Context ctx, NavGraph graph, BlockPos cauldron,
                                                         List<BlockPos> candidates, long maxDistSq) {
        List<BlockPos> sorted = new ArrayList<>();
        for (BlockPos pos : candidates) {
            if (pos.distSqr(cauldron) <= maxDistSq) sorted.add(pos);
        }
        sorted.sort(Comparator.comparingDouble(p -> p.distSqr(cauldron)));
        int tests = 0;
        for (BlockPos water : sorted) {
            if (++tests > MAX_WATER_TESTS) break;
            if (!isWaterSource(ctx.level(), water)) continue;
            BlockPos stand = standBesideWater(graph, water, ctx.npc().blockPosition());
            if (stand != null && ctx.model().canReach(graph, stand)) {
                return new CauldronIndex.WaterSpot(water.immutable(), stand);
            }
        }
        return null;
    }

    /**
     * A routable floor tile beside the water: feet one above the water (a
     * farmer on the farmland beside an irrigation channel) or two above (the
     * water sits in a hole below the ground).
     */
    @Nullable
    private static BlockPos standBesideWater(NavGraph graph, BlockPos water, BlockPos from) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            for (int up = 1; up <= 2; up++) {
                NavNode node = graph.nodeAt(water.getX() + dir.getStepX(), water.getY() + up,
                        water.getZ() + dir.getStepZ());
                if (node == null || !node.isRoutable()) continue;
                double d = node.feetPos().distSqr(from);
                if (d < bestDist) {
                    bestDist = d;
                    best = node.feetPos();
                }
            }
        }
        return best;
    }

    /** Water sources beside the graph's floor tiles around the cauldron. */
    private static List<BlockPos> scanWater(ServerLevel level, NavGraph graph, BlockPos cauldron) {
        Set<BlockPos> found = new LinkedHashSet<>();
        for (NavNode node : graph.nodesInBox(cauldron, WATER_NODE_SCAN_RADIUS, WATER_NODE_SCAN_DY)) {
            if (!node.isRoutable()) continue;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                for (int down = 1; down <= 2; down++) {
                    BlockPos pos = new BlockPos(node.x + dir.getStepX(), node.feetY - down, node.z + dir.getStepZ());
                    if (!found.contains(pos) && isWaterSource(level, pos)) found.add(pos);
                }
            }
        }
        return new ArrayList<>(found);
    }
}
