package com.alex.test.village.economy.away.jobs;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayPens;
import com.alex.test.village.economy.away.AwayRun;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.rancher.RancherBalance;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A rancher's away day (round 5 of the plan), on the village's pens
 * ({@link AwayPens}): the ranchers take their turns in order, each over its own
 * work hours, so what one collected is gone for the next (and one without shears
 * leaves the wool to the others: old bug R6).
 *
 * <p>Live rules it copies ({@code RancherActivity}), and where they live:
 * <ul>
 *   <li>Order, pen by pen: woolly sheep (shears in the pack), due eggs, due hides, milk while the
 *       cook wants a cake, then a breeding pair ({@code chooseNext}). Only the kinds the settings
 *       allow; never an animal on a lead ({@code free}).</li>
 *   <li>Time: one clock over the day's work hours at {@code AwayClock.AWAY_PACE}; a job is a walk to
 *       the animal ({@link #WALK_TICKS}) plus {@code RancherBalance.actTicks}, a storage trip (bank,
 *       buckets, feed) {@link #TRIP_TICKS}. With nothing due the clock waits for the next egg, hide,
 *       wool or rested pair.</li>
 *   <li>Eggs: one per chicken per {@code eggIntervalTicks} (Egg Finder), the timer starting again at
 *       the egg ({@code readyToLayEgg}). Hides: a comb per cow per {@code SHED_INTERVAL_TICKS},
 *       {@code averageHides} leather (Fine Hides). Wool: {@code averageWool} a shear, fractions owed;
 *       the wool grows back only when the sheep eats grass ({@link AwayPens}).</li>
 *   <li>Milk: {@code BucketNeeds.milkWanted}, counted once a shift; buckets from the pack, else from
 *       storage ({@code rancherMayTake}: never the cauldrons' ones), 4 a trip; the bucket is spent
 *       only after the room check ({@code performMilk}).</li>
 *   <li>Breeding (Breeding on), the kinds taking turns ({@code RanchBreedTurns}): a kind below the
 *       pen's room for it, two rested adults, the kind's
 *       foods ({@code RanchAnimals.breedFoodsFor}, in order; {@code foodPerPair}, one at Master Rancher) from the
 *       pack, else 8 from storage when pack and storage hold a pair's worth.</li>
 *   <li>Room: a job only when its goods fit (pack, a bank trip, or storage); with no room anywhere that
 *       product stops for the day and the animal stays undone (old bug R4).</li>
 *   <li>Banking at {@code DEPOSIT_LOAD} goods carried and at the end ({@code DepositActivity}'s keep rules).</li>
 *   <li>XP: each job ({@link AwayXp#rancherJob}), half per animal fed; a session's intelligence per
 *       {@link #JOBS_PER_SESSION} jobs ({@code ACTIONS_PER_SESSION}).</li>
 * </ul>
 * Not done away: strays and the round-up (nothing wanders while unloaded), fetching a lead.
 */
public final class RancherAway implements AwayJob {

    public static final RancherAway INSTANCE = new RancherAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A walk to the next animal in the pen (my call). */
    static final long WALK_TICKS = 40L;
    /** A walk to storage and back, as the cook's and the farmer's. */
    static final long TRIP_TICKS = 200L;
    /** Goods carried that make a bank trip (live {@code RancherActivity.DEPOSIT_LOAD}). */
    static final int DEPOSIT_LOAD = 4;
    /** Breeding food and empty buckets a trip brings (live {@code FOOD_PER_FETCH}, {@code BUCKETS_PER_FETCH}). */
    static final int FOOD_PER_FETCH = 8;
    static final int BUCKETS_PER_FETCH = 4;
    /** Jobs in a live work session ({@code ACTIONS_PER_SESSION}). */
    static final int JOBS_PER_SESSION = 16;
    /** Fractions of wool and leather owed, per item id. */
    static final String TAG_CARRY = "RancherAwayCarry";

    private RancherAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
        if (!settings.getBool(RancherSettingsSchema.WORK_ENABLED)) return "work off";
        if (!settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED)
                && !settings.getBool(RancherSettingsSchema.BREEDING_ENABLED)) {
            return "collecting and breeding off";
        }
        if (ctx.pens().isEmpty()) return ctx.pens().notLoaded() > 0 ? "pen not loaded" : "no pen";
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    /** One rancher's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final NpcEntity npc;
        private final InventoryManager pack;
        private final ProfessionSettings settings;
        private final AwayPens pens;
        private final boolean collect;
        private final boolean breed;
        private final boolean shears;
        private final CompoundTag carry;
        /** The rancher's clock, in run ticks. */
        private long t;
        private final long end;
        private boolean storageFull;
        private boolean bucketsOut;
        private int milkLeft;
        /** Breeding food neither carried nor in storage today. */
        private final Set<Item> foodOut = new HashSet<>();
        /** Goods with no room in the pack or storage: their jobs stop for the day. */
        private final Set<Item> noRoom = new HashSet<>();
        /** Whole goods into the pack today, and what was used up, for the net made / used. */
        private final Map<Item, Integer> gross = new LinkedHashMap<>();
        private final Map<Item, Integer> spent = new LinkedHashMap<>();
        private int jobs;
        private int shorn;
        private int eggs;
        private int hides;
        private int milked;
        private int pairs;
        private int trips;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.npc = ctx.npc();
            this.pack = npc.getInventory();
            this.settings = ProfessionSettings.of(npc, RancherSettingsSchema.SCHEMA);
            this.pens = ctx.pens();
            this.collect = settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED);
            this.breed = settings.getBool(RancherSettingsSchema.BREEDING_ENABLED);
            this.shears = RancherActivity.hasShears(npc);
            this.carry = npc.getBrainData().getCompound(TAG_CARRY).copy();
            this.t = pens.dayStart(ctx.day);
            this.end = t + Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE);
        }

        void run() {
            if (RancherBalance.goodBreeder(level())) pens.goodBreederAtWork();
            if (collect && allowed(EntityType.COW)) {
                // Counted afresh: the count is kept 100 ticks, and every step of a catch-up runs in one tick.
                BucketNeeds.forget(ctx.mgr);
                milkLeft = BucketNeeds.milkWanted(ctx.mgr, ctx.level);
            }
            while (t < end) {
                if (!storageFull && depositable() >= DEPOSIT_LOAD) {
                    bank();
                    continue;
                }
                if (next()) continue;
                long due = nextDue();
                if (due >= end) break;
                t = Math.max(t + 1, due);
            }
            finish();
        }

        /** The first job live would pick at {@code t}; false when none is due. */
        private boolean next() {
            for (AwayPens.Pen pen : pens.pens()) {
                if (collect) {
                    if (shears && allowed(EntityType.SHEEP)) {
                        for (AwayPens.Beast sheep : pen.beasts()) {
                            if (sheep.type() == EntityType.SHEEP && works(sheep) && !noRoom.contains(sheep.wool())
                                    && pens.woolly(pen, sheep, t) && shear(pen, sheep)) return true;
                        }
                    }
                    if (allowed(EntityType.CHICKEN) && !noRoom.contains(Items.EGG)) {
                        for (AwayPens.Beast chicken : pen.beasts()) {
                            if (chicken.type() == EntityType.CHICKEN && works(chicken)
                                    && due(chicken.eggStamp(), eggInterval()) && egg(chicken)) return true;
                        }
                    }
                    if (allowed(EntityType.COW) && !noRoom.contains(Items.LEATHER)) {
                        for (AwayPens.Beast cow : pen.beasts()) {
                            if (cow.type() == EntityType.COW && works(cow)
                                    && due(cow.shedStamp(), RancherActivity.SHED_INTERVAL_TICKS) && hide(cow)) return true;
                        }
                    }
                    if (allowed(EntityType.COW) && milk(pen)) return true;
                }
                if (breed && breedPair(pen)) return true;
            }
            return false;
        }

        // ---------------------------------------------------------------------
        // Jobs
        // ---------------------------------------------------------------------

        private boolean shear(AwayPens.Pen pen, AwayPens.Beast sheep) {
            Item wool = sheep.wool();
            double amount = RancherBalance.averageWool(level());
            if (!room(wool, amount)) return false;
            if (!spend(WALK_TICKS + act())) return true;
            pens.shear(pen, sheep, t);
            give(wool, amount);
            shorn++;
            job(1f);
            return true;
        }

        private boolean egg(AwayPens.Beast chicken) {
            if (!room(Items.EGG, 1.0)) return false;
            if (!spend(WALK_TICKS + act())) return true;
            pens.collectEgg(chicken, t);
            give(Items.EGG, 1.0);
            eggs++;
            job(1f);
            return true;
        }

        private boolean hide(AwayPens.Beast cow) {
            double amount = RancherBalance.averageHides(level());
            if (!room(Items.LEATHER, amount)) return false;
            if (!spend(WALK_TICKS + act())) return true;
            pens.combHide(cow, t);
            give(Items.LEATHER, amount);
            hides++;
            job(1f);
            return true;
        }

        /** A milking, or the trip for buckets; false when no milk is wanted or it cannot be had. */
        private boolean milk(AwayPens.Pen pen) {
            if (milkLeft <= 0 || noRoom.contains(Items.MILK_BUCKET)) return false;
            boolean cow = false;
            for (AwayPens.Beast beast : pen.beasts()) {
                if (beast.type() == EntityType.COW && works(beast)) {
                    cow = true;
                    break;
                }
            }
            if (!cow || !room(Items.MILK_BUCKET, 1.0)) return false;
            int slot = slotOf(Items.BUCKET);
            if (slot < 0) return fetchBuckets();
            if (!spend(WALK_TICKS + act())) return true;
            pack.reduceSlot(slot, 1);
            spent.merge(Items.BUCKET, 1, Integer::sum);
            give(Items.MILK_BUCKET, 1.0);
            BucketNeeds.forget(ctx.mgr);
            milkLeft--;
            milked++;
            job(1f);
            return true;
        }

        private boolean fetchBuckets() {
            if (bucketsOut) return false;
            BucketNeeds.forget(ctx.mgr);
            int want = Math.min(BUCKETS_PER_FETCH, Math.min(milkLeft, BucketNeeds.rancherMayTake(ctx.mgr, ctx.level)));
            want = Math.min(want, ctx.countInStorage(Items.BUCKET, Profession.RANCHER));
            if (want <= 0) {
                bucketsOut = true;
                return false;
            }
            if (!spend(TRIP_TICKS)) return true;
            trips++;
            if (takeIntoPack(Items.BUCKET, want) <= 0) bucketsOut = true;
            return true;
        }

        /**
         * Feeds a pair of the kind whose turn it is (the longest since bred, then fewer heads,
         * then {@code KINDS} order: live {@code RanchBreedTurns}), or goes for its food; false
         * when none can be bred.
         */
        private boolean breedPair(AwayPens.Pen pen) {
            List<EntityType<?>> inTurn = new ArrayList<>(AwayPens.KINDS);
            inTurn.sort(Comparator.<EntityType<?>>comparingLong(kind -> pens.lastBred(pen, kind))
                    .thenComparingInt(pen::heads));
            for (EntityType<?> kind : inTurn) {
                if (!allowed(kind)) continue;
                List<Item> foods = RanchAnimals.breedFoodsFor(kind);
                if (foods.isEmpty() || foodOut.containsAll(foods) || pen.heads(kind) >= pen.capacity()) continue;
                AwayPens.Beast a = null;
                AwayPens.Beast b = null;
                for (AwayPens.Beast beast : pen.beasts()) {
                    if (beast.type() != kind || !pens.readyToBreed(beast, t)) continue;
                    if (a == null) {
                        a = beast;
                    } else {
                        b = beast;
                        break;
                    }
                }
                if (b == null) continue;
                int perPair = RancherBalance.foodPerPair(level());
                int carried = 0;
                for (Item food : foods) carried += countOf(food);
                if (carried < perPair) {
                    // A pair takes its whole helping: one in storage and none carried is a wasted trip.
                    // The first food that makes a pair's worth (live feedToFetch: wheat seeds first).
                    Item fetch = null;
                    for (Item food : foods) {
                        if (foodOut.contains(food)) continue;
                        if (carried + ctx.countInStorage(food, Profession.RANCHER) >= perPair) {
                            fetch = food;
                            break;
                        }
                        foodOut.add(food);
                    }
                    if (fetch == null) continue;
                    if (!spend(TRIP_TICKS)) return true;
                    trips++;
                    if (takeIntoPack(fetch, FOOD_PER_FETCH) <= 0) foodOut.add(fetch);
                    return true;
                }
                if (!spend(2 * (WALK_TICKS + act()))) return true;
                // In the kind's order, as live performFeed: one pair may eat two different foods.
                int left = perPair;
                for (Item food : foods) {
                    int used = Math.min(left, countOf(food));
                    if (used <= 0) continue;
                    useFromPack(food, used);
                    spent.merge(food, used, Integer::sum);
                    left -= used;
                }
                pens.breed(pen, a, b, t);
                pairs++;
                job(RancherBalance.FEED_JOBS);
                job(RancherBalance.FEED_JOBS);
                return true;
            }
            return false;
        }

        /** The soonest run tick something comes due (wool, egg, hide, a rested pair); {@code Long.MAX_VALUE} for none. */
        private long nextDue() {
            long next = Long.MAX_VALUE;
            long eggInterval = eggInterval();
            for (AwayPens.Pen pen : pens.pens()) {
                for (AwayPens.Beast beast : pen.beasts()) {
                    if (!works(beast) || !allowed(beast.type())) continue;
                    if (collect) {
                        if (beast.type() == EntityType.SHEEP && shears && beast.sheared() && !noRoom.contains(beast.wool())) {
                            next = Math.min(next, beast.woollyAt());
                        }
                        if (beast.type() == EntityType.CHICKEN && beast.eggStamp() != AwayPens.NONE
                                && !noRoom.contains(Items.EGG)) {
                            next = Math.min(next, beast.eggStamp() + eggInterval);
                        }
                        if (beast.type() == EntityType.COW && beast.shedStamp() != AwayPens.NONE
                                && !noRoom.contains(Items.LEATHER)) {
                            next = Math.min(next, beast.shedStamp() + RancherActivity.SHED_INTERVAL_TICKS);
                        }
                    }
                    if (breed && beast.restUntil() > t && !foodOut.containsAll(RanchAnimals.breedFoodsFor(beast.type()))
                            && pen.heads(beast.type()) < pen.capacity()) {
                        next = Math.min(next, beast.restUntil());
                    }
                }
            }
            return next;
        }

        // ---------------------------------------------------------------------
        // Pack and storage
        // ---------------------------------------------------------------------

        /** Whether the whole goods of a job fit somewhere: the pack, else storage (a bank trip). */
        private boolean room(Item item, double amount) {
            int whole = (int) Math.floor(carry.getDouble(key(item)) + amount + 1e-9);
            if (whole <= 0) return true;
            ItemStack stack = VillageItemOwnership.produce(new ItemStack(item, whole), ctx.mgr);
            if (stack.isEmpty() || pack.capacityFor(stack) >= whole) return true;
            if (!storageFull && ctx.storageTakes(stack)) return true;
            noRoom.add(item);
            return false;
        }

        /** The goods into the pack, fractions owed; a full pack banks first, then storage straight. */
        private void give(Item item, double amount) {
            String key = key(item);
            double total = carry.getDouble(key) + amount;
            int whole = (int) Math.floor(total + 1e-9);
            if (whole <= 0) {
                carry.putDouble(key, total);
                return;
            }
            ItemStack stack = VillageItemOwnership.produce(new ItemStack(item, whole), ctx.mgr);
            if (stack.isEmpty()) {
                // Live loses goods that cannot be bound the same way.
                carry.putDouble(key, total - whole);
                return;
            }
            int placed = whole;
            if (!pack.addItem(stack)) {
                if (!storageFull && depositable() > 0) bank();
                if (!pack.addItem(stack)) {
                    ctx.store(stack);
                    placed = whole - stack.getCount();
                    // No room anywhere: the rest is owed, and this product stops.
                    if (!stack.isEmpty()) noRoom.add(item);
                }
            }
            carry.putDouble(key, total - placed);
            if (placed > 0) gross.merge(item, placed, Integer::sum);
        }

        /** Takes up to {@code amount} from storage into the pack; what does not fit goes back. How many came. */
        private int takeIntoPack(Item item, int amount) {
            int got = 0;
            List<ItemStack> taken = ctx.takeFromStorage(item, amount, Profession.RANCHER);
            for (ItemStack stack : taken) {
                if (pack.addItem(stack)) got += stack.getCount();
                else ctx.store(stack);
            }
            return got;
        }

        /** A storage trip with everything the rancher does not keep ({@code DepositActivity.keptPerSlot}). */
        private void bank() {
            t += TRIP_TICKS;
            trips++;
            int[] kept = DepositActivity.keptPerSlot(ctx.activity());
            int moved = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.isEmpty() || kept[slot] >= stack.getCount()) continue;
                int stored = ctx.store(stack.copyWithCount(stack.getCount() - kept[slot]));
                if (stored > 0) {
                    pack.reduceSlot(slot, stored);
                    moved += stored;
                }
            }
            if (moved == 0) storageFull = true;
        }

        /** Goods the next bank would take. */
        private int depositable() {
            int[] kept = DepositActivity.keptPerSlot(ctx.activity());
            int count = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (!stack.isEmpty()) count += Math.max(0, stack.getCount() - kept[slot]);
            }
            return count;
        }

        private int slotOf(Item item) {
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.is(item) && pack.mayUse(stack)) return slot;
            }
            return -1;
        }

        private int countOf(Item item) {
            int count = 0;
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.is(item) && pack.mayUse(stack)) count += stack.getCount();
            }
            return count;
        }

        private void useFromPack(Item item, int amount) {
            for (int slot = 0; slot < InventoryManager.SIZE && amount > 0; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (!stack.is(item) || !pack.mayUse(stack)) continue;
                int used = Math.min(amount, stack.getCount());
                pack.reduceSlot(slot, used);
                amount -= used;
            }
        }

        // ---------------------------------------------------------------------
        // The day's books
        // ---------------------------------------------------------------------

        private boolean allowed(EntityType<?> kind) {
            return RancherActivity.isAllowedSpecies(settings, kind);
        }

        /** A grown animal on no lead: one the rancher works. */
        private static boolean works(AwayPens.Beast beast) {
            return !beast.baby() && !beast.led();
        }

        private boolean due(long stamp, long interval) {
            return stamp == AwayPens.NONE || t - stamp >= interval;
        }

        private int level() {
            return ctx.model().brain().rancherSkillLevel();
        }

        private long eggInterval() {
            return RancherBalance.eggIntervalTicks(level());
        }

        private long act() {
            return RancherBalance.actTicks(level());
        }

        /** Spends {@code ticks} of the day; false (the day over) when they do not fit. */
        private boolean spend(long ticks) {
            if (t + ticks > end) {
                t = end;
                return false;
            }
            t += ticks;
            return true;
        }

        private void job(float worth) {
            jobs++;
            AwayXp.rancherJob(ctx, worth);
        }

        private static String key(Item item) {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }

        private void finish() {
            if (!storageFull && depositable() > 0) bank();
            if (jobs > 0) AwayXp.workSessions(ctx, (jobs + JOBS_PER_SESSION - 1) / JOBS_PER_SESSION);
            carry.getAllKeys().removeIf(key -> carry.getDouble(key) < 1e-6);
            npc.getBrainData().put(TAG_CARRY, carry);
            gross.forEach(ctx::made);
            spent.forEach(ctx::used);
            BucketNeeds.forget(ctx.mgr);
            LOGGER.info("[AwayRancher] {} day {}: {} job(s), {} tick(s) left of {}; shorn {}, eggs {}, hides {}, "
                            + "milked {}, pairs fed {}, trips {}; made {}{}{}",
                    npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, jobs, Math.max(0L, end - t),
                    Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE), shorn, eggs, hides, milked, pairs, trips,
                    AwayRun.counts(gross), shears ? "" : "; no shears",
                    noRoom.isEmpty() ? "" : "; no room for " + noRoom);
        }
    }
}
