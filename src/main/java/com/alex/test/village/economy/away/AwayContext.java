package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.AwayTools;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one away job step gets: the village, the villager (its copy is in the
 * body for the length of the step), the open book, the world list, the day.
 * {@link #made} and {@link #used} feed the welcome-back lines, the summary and
 * the {@code [AwayDay]} log line.
 */
public final class AwayContext {

    public final ServerLevel level;
    public final VillageManager mgr;
    public final AwayWorld world;
    public final AwayDays.Day day;
    private final AwayVillager villager;
    private final AwayRun run;
    final Map<Item, Integer> madeToday = new LinkedHashMap<>();
    final Map<Item, Integer> usedToday = new LinkedHashMap<>();

    AwayContext(ServerLevel level, VillageManager mgr, AwayVillager villager, AwayWorld world, AwayDays.Day day,
                AwayRun run) {
        this.level = level;
        this.mgr = mgr;
        this.villager = villager;
        this.world = world;
        this.day = day;
        this.run = run;
    }

    /** The body, holding the villager's away copy for this step. */
    public NpcEntity npc() { return villager.body(); }

    public VillagerModel model() { return villager.model(); }

    /** The village's fields for this catch-up, shared by the farmers (round 3). */
    public AwayFarm farm() { return run.farm(); }

    /**
     * Whether this day is a whole village day away: neither the day the village
     * fell asleep nor the last counted one, which were (or will be) lived in
     * person. The children count their school days away this way
     * ({@code ChildProfession}: days between the one last seen and today).
     */
    public boolean wholeDayAway() {
        List<AwayDays.Day> days = run.days();
        return !days.isEmpty() && day.calendarDay() > days.get(0).calendarDay()
                && day.calendarDay() < days.get(days.size() - 1).calendarDay();
    }

    /**
     * The bodies of this run's villagers of {@code profession} still alive in it
     * (not starved on an earlier day). Outside their own step a body holds the
     * real villager.
     */
    public List<NpcEntity> bodiesAlive(Profession profession) {
        List<NpcEntity> bodies = new ArrayList<>();
        for (AwayVillager other : run.villagers()) {
            NpcEntity body = other.body();
            if (other.profession() == profession && !other.starved() && body != null && !body.isRemoved()) {
                bodies.add(body);
            }
        }
        return bodies;
    }

    /** The live helpers' context, on the body holding the copy. */
    public Activity.Context activity() {
        VillagerModel model = model();
        return new Activity.Context(model.brain(), model, mgr, level, npc());
    }

    public void made(Item item, int count) {
        if (count <= 0) return;
        madeToday.merge(item, count, Integer::sum);
        run.produced.merge(item, count, Integer::sum);
    }

    public void used(Item item, int count) {
        if (count <= 0) return;
        usedToday.merge(item, count, Integer::sum);
        run.consumed.merge(item, count, Integer::sum);
    }

    /** Why the job stopped before its work hours were up today; null when it did not. */
    @Nullable String partwayStop;

    /**
     * The job stopped before its work hours were up, and why (null: it did not):
     * the registry summary (StateDump) counts it per villager.
     */
    public void stoppedPartway(@Nullable String why) {
        partwayStop = why;
    }

    // =========================================================================
    // Storage (the book's copies)
    // =========================================================================

    /**
     * Takes up to {@code amount} of {@code item} out of storage: only stacks
     * the village may use, and no more than the Stock tab leaves free for
     * {@code taker}. The stacks as they lay (bound or not).
     */
    public List<ItemStack> takeFromStorage(Item item, int amount, @Nullable Profession taker) {
        return take(item, amount, taker, false);
    }

    /** How many of {@code item} {@link #takeFromStorage} would give {@code taker}. */
    public int countInStorage(Item item, @Nullable Profession taker) {
        return count(item, taker, false);
    }

    /**
     * {@link #takeFromStorage} for goods a villager turns into something
     * ({@code mayUseAsMaterial}): only the village's own, never a player's food
     * or gear. The cook's ingredients and fuel.
     */
    public List<ItemStack> takeMaterial(Item item, int amount, @Nullable Profession taker) {
        return take(item, amount, taker, true);
    }

    /** How many of {@code item} {@link #takeMaterial} would give {@code taker}. */
    public int countMaterial(Item item, @Nullable Profession taker) {
        return count(item, taker, true);
    }

    /**
     * Every {@code item} in storage the village may use, the Stock tab's holds not
     * taken off: what the village "has", as the live lumberjack counts its sticks
     * ({@code LeafRewards}: {@code StorageBuilding.countItem} in every valid room).
     */
    public int storageHolds(Item item) {
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        int count = 0;
        for (AwayBook.BookChest chest : run.book().chests()) {
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.peek(slot);
                if (!stack.isEmpty() && stack.is(item) && VillageItemOwnership.mayUse(stack, owner)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    private List<ItemStack> take(Item item, int amount, @Nullable Profession taker, boolean material) {
        List<ItemStack> taken = new ArrayList<>();
        if (amount <= 0) return taken;
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        int left = Math.min(amount, run.book().free(level, mgr, item, taker));
        for (AwayBook.BookChest chest : run.book().chests()) {
            for (int slot = 0; slot < chest.size() && left > 0; slot++) {
                ItemStack stack = chest.peek(slot);
                if (stack.isEmpty() || !stack.is(item) || !may(stack, owner, material)) continue;
                ItemStack got = chest.take(slot, left);
                if (got.isEmpty()) continue;
                left -= got.getCount();
                taken.add(got);
            }
            if (left <= 0) break;
        }
        if (!taken.isEmpty()) run.book().changed();
        return taken;
    }

    private int count(Item item, @Nullable Profession taker, boolean material) {
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        int count = 0;
        for (AwayBook.BookChest chest : run.book().chests()) {
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.peek(slot);
                if (!stack.isEmpty() && stack.is(item) && may(stack, owner, material)) count += stack.getCount();
            }
        }
        return count <= 0 ? 0 : Math.min(count, run.book().free(level, mgr, item, taker));
    }

    private static boolean may(ItemStack stack, VillageTokenBinding owner, boolean material) {
        return material ? VillageItemOwnership.mayUseAsMaterial(stack, owner) : VillageItemOwnership.mayUse(stack, owner);
    }

    /** Puts {@code stack} in any storage room; what did not fit stays in it. How many went in. */
    public int store(ItemStack stack) {
        int before = stack.getCount();
        for (StorageBuilding room : run.book().storageRooms()) {
            if (stack.isEmpty()) break;
            room.depositItems(level, stack);
        }
        if (stack.getCount() != before) run.book().changed();
        return before - stack.getCount();
    }

    /** Whether some storage room takes the whole of {@code stack} (the copies, by the rooms' rules). */
    public boolean storageTakes(ItemStack stack) {
        for (StorageBuilding room : run.book().storageRooms()) {
            if (room.canAcceptAll(level, stack)) return true;
        }
        return false;
    }

    /**
     * Everything in storage the village may turn into goods, by item, as the live
     * cook counts its pantry ({@code CookActivity.storageCounts}: {@code mayUseAsMaterial},
     * the Stock tab's holds not taken off).
     */
    public Map<Item, Integer> materialCounts() {
        VillageTokenBinding owner = VillageTokenBinding.of(mgr);
        Map<Item, Integer> counts = new HashMap<>();
        for (AwayBook.BookChest chest : run.book().chests()) {
            for (int slot = 0; slot < chest.size(); slot++) {
                ItemStack stack = chest.peek(slot);
                if (!stack.isEmpty() && VillageItemOwnership.mayUseAsMaterial(stack, owner)) {
                    counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        return counts;
    }

    /** Goods cooked for the player's Stock tab orders: held from the village until WRITE tells the order. */
    public void holdForPlayer(Item item, int count) {
        run.book().holdAway(item, count);
    }

    /** How many of {@code item} {@link #holdForPlayer} holds so far. */
    public int heldForPlayer(Item item) {
        return run.book().heldAway(item);
    }

    /** Whether the village has a standing storage room at all. */
    public boolean hasStorage() {
        return !run.book().storageRooms().isEmpty();
    }

    /** The village's kitchen for this catch-up, shared by the cooks (round 4). */
    public AwayKitchen kitchen() { return run.kitchen(); }

    /** The village's pens for this catch-up, shared by the ranchers (round 5). */
    public AwayPens pens() { return run.pens(); }

    /** The village's trees for this catch-up, shared by the lumberjacks (round 7). */
    public AwayTrees trees() { return run.trees(); }

    /** The village's mines for this catch-up, shared by the miners (round 8). */
    public AwayMines mines() { return run.mines(); }

    /** The Stock tab orders forged this catch-up, told at WRITE (round 9). */
    public AwayOrders orders() { return run.orders(); }

    /**
     * A tool for {@code requirement} into the pack, as live gets one: a spare
     * from storage, else a wooden one crafted from storage wood (counted as
     * used). False when there is none.
     */
    public boolean restock(ProfessionToolRequirement requirement) {
        Map<Item, Integer> wood = new LinkedHashMap<>();
        boolean got = AwayTools.restock(level, activity(), requirement, wood);
        run.book().changed();
        wood.forEach(this::used);
        return got;
    }
}
