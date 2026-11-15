package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.KitchenBuilding.HeatSource;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.cook.CookDemand;
import com.alex.test.village.villagers.cook.VillageKitchens;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The village's kitchens for one catch-up (round 4), worked as one
 * ({@link VillageKitchens}, as live) and shared by the cooks in their turns:
 * <ul>
 *   <li>the crafting tables' time each day, and each kind of heat's (smoker,
 *       furnace, campfire): one cook at a time works a station, as live
 *       ({@code CraftingStation}, every station claimed since 10-24), so the tables
 *       and each kind of heat have the day's work hours once for each such station
 *       the kitchens have;</li>
 *   <li>the heat left in the smoker and the furnace, kept for the next day
 *       ({@code CookActivity}'s warm stations keep it 24,000 ticks; they start cold,
 *       as after a reload, my call);</li>
 *   <li>the Stock tab's once orders cooked while away: the real order is told only
 *       at WRITE ({@link #writeTo}), so meanwhile they are counted here and the
 *       player's dishes are held in the book ({@link AwayBook#holdAway});</li>
 *   <li>what was cooked and what got in the way, for the preview and the log.</li>
 * </ul>
 * After each day's meals the working cooks learn what the village ate
 * ({@link CookDemand#recordAwayDay}).
 */
public final class AwayKitchen {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Heat left in a station lasts into the next away day ({@code CookActivity.WARM_TICKS}, a day). */
    private static final int WARM_DAYS = 1;

    @Nullable private final VillageKitchens building;
    private int dayIndex;
    private double tableTicksLeft;
    /** Today's time left on each kind of heat, all its stations together. */
    private final Map<HeatSource, Double> heatTicksLeft = new EnumMap<>(HeatSource.class);

    private record Warmth(double credit, int day) {}

    private final Map<HeatSource, Warmth> warm = new EnumMap<>(HeatSource.class);

    /** One once order's units cooked while away. */
    private static final class Made {
        final Item item;
        final UUID cook;
        int count;

        Made(Item item, UUID cook) {
            this.item = item;
            this.cook = cook;
        }
    }

    private final Map<UUID, Made> orders = new LinkedHashMap<>();

    // For the preview and the log.
    private final Map<Item, Integer> cooked = new LinkedHashMap<>();
    private final Map<String, Integer> snags = new TreeMap<>();
    private int batches;

    private AwayKitchen(@Nullable VillageKitchens building) {
        this.building = building;
    }

    /** Every standing kitchen of the village, as live ({@code CookActivity.findKitchen}). */
    static AwayKitchen read(VillageManager mgr) {
        return new AwayKitchen(VillageKitchens.of(mgr));
    }

    /** A new day: every table, smoker, furnace and campfire is free for the day's work hours. */
    void startDay(AwayDays.Day day) {
        dayIndex = day.index();
        double workTicks = day.workTicks() * AwayClock.AWAY_PACE;
        tableTicksLeft = workTicks;
        heatTicksLeft.clear();
        if (building == null) return;
        tableTicksLeft = workTicks * Math.max(1, building.getCraftingTablePositions().size());
        for (HeatSource heat : HeatSource.values()) {
            int stations = building.getHeatPositions(heat).size();
            if (stations > 0) heatTicksLeft.put(heat, workTicks * stations);
        }
    }

    /** The kitchens, worked as one, or null when the village has none. */
    @Nullable
    public VillageKitchens building() { return building; }

    // =========================================================================
    // The table and the heat
    // =========================================================================

    public double tableTicksLeft() { return tableTicksLeft; }

    public void useTable(double ticks) { tableTicksLeft = Math.max(0.0, tableTicksLeft - ticks); }

    public double heatTicksLeft(HeatSource heat) { return heatTicksLeft.getOrDefault(heat, 0.0); }

    public void useHeat(HeatSource heat, double ticks) {
        heatTicksLeft.computeIfPresent(heat, (kind, left) -> Math.max(0.0, left - ticks));
    }

    /** The heat left in {@code heat}'s station, taken out to cook on. */
    public double takeWarmth(HeatSource heat) {
        Warmth warmth = warm.remove(heat);
        return warmth == null || dayIndex - warmth.day() > WARM_DAYS ? 0.0 : warmth.credit();
    }

    /** Leaves {@code credit} items of heat in {@code heat}'s station for the next visit. */
    public void keepWarmth(HeatSource heat, double credit) {
        if (credit <= 0.0) return;
        Warmth left = warm.get(heat);
        double total = credit + (left == null || dayIndex - left.day() > WARM_DAYS ? 0.0 : left.credit());
        warm.put(heat, new Warmth(total, dayIndex));
    }

    /** Heat left in all the stations, for the planner ({@code Pantry.withWarmth}). */
    public double warmth() {
        double total = 0.0;
        for (Warmth warmth : warm.values()) {
            if (dayIndex - warmth.day() <= WARM_DAYS) total += warmth.credit();
        }
        return total;
    }

    // =========================================================================
    // Stock tab orders
    // =========================================================================

    /** Units of once order {@code row} already cooked while away. */
    public int madeFor(UUID row) {
        Made made = orders.get(row);
        return made == null ? 0 : made.count;
    }

    /** Units cooked away for once orders, by dish: their materials are spent, no longer held. */
    public Map<Item, Integer> madeByDish() {
        Map<Item, Integer> made = new LinkedHashMap<>();
        for (Made order : orders.values()) made.merge(order.item, order.count, Integer::sum);
        return made;
    }

    /** {@code count} of {@code item} cooked for once order {@code row} by {@code cook}. */
    public void onOrderMade(UUID row, Item item, UUID cook, int count) {
        if (count <= 0) return;
        orders.computeIfAbsent(row, key -> new Made(item, cook)).count += count;
    }

    // =========================================================================
    // For the preview and the log
    // =========================================================================

    public void cooked(Item item, int count) {
        if (count <= 0) return;
        cooked.merge(item, count, Integer::sum);
        batches++;
    }

    public void snag(String reason) {
        snags.merge(reason, 1, Integer::sum);
    }

    /** "smoker + table: 31 batch(es), 30 minecraft:cooked_beef ...; snags: no fuel x2", or why none. */
    String describe() {
        if (building == null) return "no kitchen";
        StringBuilder text = new StringBuilder();
        if (building.size() > 1) text.append(building.size()).append(" kitchens, ");
        int start = text.length();
        for (HeatSource heat : HeatSource.values()) {
            int stations = building.getHeatPositions(heat).size();
            if (stations == 0) continue;
            if (text.length() > start) text.append(" + ");
            text.append(heat.name().toLowerCase(Locale.ROOT)).append(stations > 1 ? " x" + stations : "");
        }
        int tables = building.getCraftingTablePositions().size();
        if (tables > 0) {
            text.append(text.length() > start ? " + " : "").append("table").append(tables > 1 ? " x" + tables : "");
        }
        text.append(": ").append(batches).append(" batch(es)");
        if (!cooked.isEmpty()) text.append(", ").append(AwayRun.counts(cooked));
        if (!orders.isEmpty()) {
            int units = 0;
            for (Made made : orders.values()) units += made.count;
            text.append("; ").append(units).append(" for Stock tab orders");
        }
        if (!snags.isEmpty()) text.append("; snags: ").append(snags);
        return text.toString();
    }

    // =========================================================================
    // After the meals, and WRITE
    // =========================================================================

    /**
     * After a day's meals, with the copies still swapped in: each working cook
     * learns what the village ate, so the shelf targets after a trip follow what
     * was really eaten.
     */
    void afterMeals(AwayDays.Day day, Map<Item, Integer> eaten, List<AwayVillager> swapped) {
        for (AwayVillager villager : swapped) {
            if (villager.profession() != Profession.COOK || !CookSettings.of(villager.body()).works()) continue;
            CookDemand.recordAwayDay(villager.body().getBrainData(), day.calendarDay(), eaten);
        }
    }

    /**
     * WRITE, after the chests: the once orders cooked while away are made and
     * delivered (their dishes are in storage now), as a live cook's batch and
     * deposit tell them. A player's finished order says so in chat.
     */
    void writeTo(ServerLevel level, VillageManager mgr) {
        if (orders.isEmpty()) return;
        StockReserves reserves = StockReserves.get(level.getServer());
        for (Map.Entry<UUID, Made> entry : orders.entrySet()) {
            Made made = entry.getValue();
            reserves.onMade(mgr.getVillageId(), entry.getKey(), made.cook, made.item, made.count);
            reserves.onDelivered(mgr, level, made.item, made.count);
            LOGGER.info("[AwayCook] Stock tab order {}: {} {} cooked while away", entry.getKey().toString()
                    .substring(0, 8), made.count, BuiltInRegistries.ITEM.getKey(made.item));
        }
    }
}
