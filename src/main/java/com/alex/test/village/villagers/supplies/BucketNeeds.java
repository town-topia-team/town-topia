package com.alex.test.village.villagers.supplies;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Who in the village needs a bucket: shared by the blacksmith, who makes
 * them, and the rancher, who milks with them.
 *
 * <p>The two uses behave differently. Farmers carry water to the wash
 * cauldrons and bring the bucket back, so one or two serve every farmer for
 * good. Ranchers milk with them, and a milk bucket is used up in a cake (the
 * village's rule: buckets never come back). So milk is only made while the
 * cook wants a cake, the rancher milks only then, and the smith keeps one
 * cake's worth of buckets going round. Nobody makes buckets, or milk, that
 * would only sit in a chest.
 */
public final class BucketNeeds {

    /** Milk buckets in one cake. */
    public static final int MILK_PER_CAKE = 3;
    /** The most milk kept waiting, Stock tab cake orders included. */
    private static final int MAX_MILK = 2 * MILK_PER_CAKE;
    /** Most buckets kept for the wash cauldrons: farmers bring theirs back. */
    public static final int MAX_FARM_BUCKETS = 2;
    /** Counting touches every chest and pack; the answer is kept this long. */
    private static final long CACHE_TICKS = 100;

    /**
     * One count of the village.
     *
     * @param farmWanted   buckets the farmers' cauldrons want
     * @param milkTarget   milk buckets the kitchen wants waiting
     * @param milk         milk buckets the village holds
     * @param rancherEmpty empty buckets the ranchers carry
     * @param otherEmpty   empty and water buckets everywhere else
     * @param storedEmpty  empty buckets in storage, less the Stock tab's held ones
     * @param farmHeld     water buckets in storage and every bucket the farmers carry
     */
    private record Counts(long gameTime, int farmWanted, int milkTarget, int milk,
                          int rancherEmpty, int otherEmpty, int storedEmpty, int farmHeld) {}

    private static final Map<VillageManager, Counts> CACHE = new WeakHashMap<>();

    private BucketNeeds() {}

    /** Milk buckets the cook still wants: what the rancher may milk. */
    public static int milkWanted(VillageManager village, ServerLevel level) {
        Counts counts = counts(village, level);
        return Math.max(0, counts.milkTarget() - counts.milk());
    }

    /**
     * Buckets the blacksmith should make for the wash cauldrons. Not the
     * ranchers' empty buckets: a rancher keeps those for the cows.
     */
    public static int farmBucketsToMake(VillageManager village, ServerLevel level) {
        Counts counts = counts(village, level);
        return Math.max(0, counts.farmWanted() - counts.otherEmpty());
    }

    /**
     * Buckets the blacksmith should make for milk: the milk still wanted,
     * less the ranchers' empty buckets and any empty ones the cauldrons can
     * spare (a new bucket on its way to storage among them).
     */
    public static int milkBucketsToMake(VillageManager village, ServerLevel level) {
        Counts counts = counts(village, level);
        int spare = Math.max(0, counts.otherEmpty() - counts.farmWanted());
        return Math.max(0, counts.milkTarget() - counts.milk() - counts.rancherEmpty() - spare);
    }

    // =========================================================================
    // Sharing the buckets in storage
    // =========================================================================

    /**
     * Empty buckets in storage set aside for the wash cauldrons: what the
     * farmers still lack of their {@link #MAX_FARM_BUCKETS}, counting the
     * buckets they carry and the water buckets already filled.
     */
    private static int farmShare(Counts counts) {
        return Math.min(counts.storedEmpty(), Math.max(0, counts.farmWanted() - counts.farmHeld()));
    }

    /** Empty buckets the ranchers still need for the milk the kitchen wants. */
    private static int rancherShort(Counts counts) {
        return Math.max(0, counts.milkTarget() - counts.milk() - counts.rancherEmpty());
    }

    /**
     * How many empty buckets a rancher may take from storage now: the milk
     * still wanted, never the ones set aside for the farmers' cauldrons.
     */
    public static int rancherMayTake(VillageManager village, ServerLevel level) {
        Counts counts = counts(village, level);
        return Math.max(0, Math.min(rancherShort(counts), counts.storedEmpty() - farmShare(counts)));
    }

    /**
     * Whether a farmer may take an empty bucket from storage to refill a
     * cauldron. One set aside for the farms, or one the ranchers do not need,
     * always. Otherwise only when it is {@code urgent} (an evening wash waits
     * on the water), since milk can wait a day and a dirty farmer cannot.
     */
    public static boolean farmerMayTakeEmpty(VillageManager village, ServerLevel level, boolean urgent) {
        Counts counts = counts(village, level);
        if (counts.storedEmpty() <= 0) return false;
        if (urgent || farmShare(counts) > 0) return true;
        return counts.storedEmpty() - rancherShort(counts) > 0;
    }

    /**
     * How many empty buckets a rancher keeps in its pack: those still wanted
     * for milk. The rest go back to storage, for the cauldrons and for the
     * next cake.
     */
    public static int rancherBucketsToKeep(VillageManager village, ServerLevel level) {
        Counts counts = counts(village, level);
        return Math.max(0, counts.milkTarget() - counts.milk());
    }

    /** Counts again on the next question: a bucket was just made or milked. */
    public static void forget(VillageManager village) {
        CACHE.remove(village);
    }

    private static Counts counts(VillageManager village, ServerLevel level) {
        long now = level.getGameTime();
        Counts cached = CACHE.get(village);
        if (cached != null && now >= cached.gameTime() && now - cached.gameTime() < CACHE_TICKS) return cached;
        Counts counts = count(village, level, now);
        CACHE.put(village, counts);
        return counts;
    }

    private static Counts count(VillageManager village, ServerLevel level, long now) {
        int milk = 0;
        int rancherEmpty = 0;
        int otherEmpty = 0;
        int storedEmpty = 0;
        int farmHeld = 0;
        int storedCakes = 0;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            int empty = storage.countItem(level, Items.BUCKET);
            int water = storage.countItem(level, Items.WATER_BUCKET);
            otherEmpty += empty + water;
            storedEmpty += empty;
            farmHeld += water;
            milk += storage.countItem(level, Items.MILK_BUCKET);
            storedCakes += storage.countItem(level, Items.CAKE);
        }
        // Buckets the player keeps in the Stock tab are not the village's.
        StockReserves reserves = StockReserves.get(level.getServer());
        int locked = reserves.lockedAmount(village.getVillageId(), Items.BUCKET);
        otherEmpty = Math.max(0, otherEmpty - locked);
        storedEmpty = Math.max(0, storedEmpty - locked);

        int farmers = 0;
        boolean milker = false;
        boolean cakeWanted = false;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            Profession profession = npc.getProfession();
            if (profession == Profession.FARMER) farmers++;
            if (profession == Profession.RANCHER && milks(npc)) milker = true;
            if (profession == Profession.COOK && wantsCake(npc, storedCakes)) cakeWanted = true;
            InventoryManager inv = npc.getInventory();
            if (inv == null) continue;
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack stack = inv.getSlot(i);
                if (stack.isEmpty() || !inv.mayUse(stack)) continue;
                if (stack.is(Items.MILK_BUCKET)) milk += stack.getCount();
                else if (stack.is(Items.BUCKET) && profession == Profession.RANCHER) rancherEmpty += stack.getCount();
                else if (stack.is(Items.BUCKET) || stack.is(Items.WATER_BUCKET)) {
                    otherEmpty += stack.getCount();
                    if (profession == Profession.FARMER) farmHeld += stack.getCount();
                }
            }
        }

        int cauldrons = village.cauldrons().spots(village, level).size();
        int farmWanted = farmers > 0 && cauldrons > 0
                ? Math.min(MAX_FARM_BUCKETS, Math.min(cauldrons, (farmers + 1) / 2)) : 0;

        int milkTarget = 0;
        if (milker && hasMilkingCow(village, level)) {
            // Stock tab cake orders hold their milk too.
            int ordered = reserves.heldFor(village, level, Profession.COOK).getOrDefault(Items.MILK_BUCKET, 0);
            milkTarget = Math.min(MAX_MILK, (cakeWanted ? MILK_PER_CAKE : 0) + ordered);
        }
        return new Counts(now, farmWanted, milkTarget, milk, rancherEmpty, otherEmpty, storedEmpty, farmHeld);
    }

    /** A rancher at work that collects products and may tend cows. */
    private static boolean milks(NpcEntity npc) {
        ProfessionSettings settings = ProfessionSettings.of(npc, RancherSettingsSchema.SCHEMA);
        return settings.getBool(RancherSettingsSchema.WORK_ENABLED)
                && settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED)
                && settings.isAllowed(RancherSettingsSchema.ALLOWED_ANIMAL_TYPES,
                        EntityType.getKey(EntityType.COW).toString());
    }

    /** A cook at work whose shelf is short of cake. */
    private static boolean wantsCake(NpcEntity npc, int storedCakes) {
        CookSettings settings = CookSettings.of(npc);
        if (!settings.works() || !settings.allowsRecipe(Items.CAKE)) return false;
        int target = settings.stockTarget(Items.CAKE);
        return target > 0 && storedCakes < target;
    }

    /** An adult cow in one of the village's ranches. */
    private static boolean hasMilkingCow(VillageManager village, ServerLevel level) {
        for (Building building : village.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            for (Animal animal : ranch.animalsInside(level)) {
                if (animal instanceof Cow cow && !cow.isBaby()) return true;
            }
        }
        return false;
    }
}
