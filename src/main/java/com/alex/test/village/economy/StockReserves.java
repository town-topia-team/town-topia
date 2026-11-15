package com.alex.test.village.economy;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The storage room's Stock tab: what the player asks the blacksmith, the cook and the
 * archers (arrows and bows) to make.
 *
 * <p>Each row is one dish or blacksmith product, a count, and two switches:
 * <ul>
 *   <li><b>Once / Always.</b> A once row is an order: that many are made one time, then
 *       it moves to the completed list. An always row keeps that many in storage and has
 *       more made whenever storage runs short.</li>
 *   <li><b>For me / Villagers.</b> What is made for the player is locked in storage: no
 *       villager takes it, the tradesman does not sell it, nobody eats it. A once order
 *       for the player is done when the player has taken it out. What is made for the
 *       villagers is free for them to use the moment it is stored.</li>
 * </ul>
 *
 * <p>Rows come first for their maker, ahead of the village's own needs. The materials a
 * row still needs are held back in storage for its maker: the tradesman will not sell
 * them and other villagers will not take them.
 *
 * <p>Progress of a once order is counted three times: {@code made} when the maker
 * finishes one, {@code delivered} when the maker puts it into storage, and {@code collected}
 * when it has left storage again (for the player's orders only).
 */
public final class StockReserves extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_stock_reserves";
    private static final SavedData.Factory<StockReserves> FACTORY =
            new SavedData.Factory<>(StockReserves::new, StockReserves::load, null);

    /** Most an always row keeps, and most a once order asks for. */
    public static final int MAX_ALWAYS = 512;
    public static final int MAX_ONCE = 64;
    /** Rows per village, and once orders open at the same time. */
    public static final int MAX_ROWS = 24;
    public static final int MAX_OPEN_ORDERS = 8;
    /** Completed orders remembered per village. */
    public static final int HISTORY = 20;

    /** How often {@link #tick} looks for collected or lost items. */
    private static final int TICK_EVERY = 40;
    /** How long a maker's claim on an order lasts without being renewed. */
    private static final int CLAIM_TICKS = 6000;

    public enum Outcome { COLLECTED, DELIVERED, CANCELLED }

    /** One line of the Stock tab. */
    public static final class Row {
        public final UUID id;
        public final Item item;
        int count;
        boolean once;
        boolean forPlayer;
        int made;
        int delivered;
        int collected;
        @Nullable UUID orderedBy;
        String orderedByName = "";
        long placed;
        /** The player was told the order is ready to collect. */
        boolean announced;
        /** Makers working on this order right now: units each, until when. Not saved. */
        final Map<UUID, Claim> claims = new HashMap<>();

        Row(UUID id, Item item) {
            this.id = id;
            this.item = item;
        }

        public int count() { return count; }
        public boolean once() { return once; }
        public boolean forPlayer() { return forPlayer; }
        public int made() { return made; }
        public int delivered() { return delivered; }
        public String orderedByName() { return orderedByName; }

        /** Units still to make for a once order. */
        public int toMake() { return once ? Math.max(0, count - made) : 0; }

        /** Made but still in the maker's pack. */
        public int inTransit() { return once ? Math.max(0, made - delivered) : 0; }

        /** The player's finished order sitting in storage, waiting to be taken. */
        public int ready() { return once && forPlayer ? Math.max(0, delivered - collected) : 0; }

        boolean complete() {
            return once && (forPlayer ? collected >= count : delivered >= count);
        }

        boolean claimedBy(@Nullable UUID npc, long now) {
            for (Map.Entry<UUID, Claim> claim : claims.entrySet()) {
                if (!VillagerPause.lapsed(claim.getKey(), claim.getValue().until(), now)
                        && (npc == null || claim.getKey().equals(npc))) return true;
            }
            return false;
        }

        int claimedByOthers(@Nullable UUID self, long now) {
            int units = 0;
            for (Map.Entry<UUID, Claim> claim : claims.entrySet()) {
                if (!VillagerPause.lapsed(claim.getKey(), claim.getValue().until(), now)
                        && !claim.getKey().equals(self)) units += claim.getValue().units();
            }
            return units;
        }
    }

    record Claim(int units, long until) {}

    /** A finished, collected or cancelled order. */
    public record Done(Item item, int count, int delivered, Outcome outcome, boolean forPlayer,
                       String by, long finished) {}

    /**
     * Work a row asks of its maker: a once order ({@code row} set) or an always row's
     * shortfall ({@code row} null, summed over every always row of that item).
     */
    public record Want(@Nullable UUID row, Item item, int units, boolean once) {}

    private final Map<UUID, List<Row>> byVillage = new HashMap<>();
    private final Map<UUID, Deque<Done>> history = new HashMap<>();

    // Per-tick caches: stored totals, what the makers carry, what is held back.
    private final Map<UUID, Map<Item, Integer>> totals = new HashMap<>();
    private final Map<UUID, Map<Item, Integer>> carried = new HashMap<>();
    private final Map<UUID, Map<Profession, Map<Item, Integer>>> held = new HashMap<>();
    private long cacheTick = Long.MIN_VALUE;

    public static StockReserves get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // =========================================================================
    // What can be ordered
    // =========================================================================

    /** True for the blacksmith's products (its Products tab). */
    public static boolean isForgeItem(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return "minecraft".equals(id.getNamespace())
                && BlacksmithSettingsSchema.PRODUCT_IDS.contains(id.getPath());
    }

    /** The blacksmith's products in Products-tab order, for the Stock tab's list. */
    public static List<Item> forgeItems() {
        List<Item> items = new ArrayList<>();
        for (String path : BlacksmithSettingsSchema.PRODUCT_IDS) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.withDefaultNamespace(path));
            if (item != Items.AIR) items.add(item);
        }
        return items;
    }

    /** True for what the archers make: plain arrows and bows. */
    public static boolean isArcherItem(Item item) {
        return item == Items.ARROW || item == Items.BOW;
    }

    /** The archers' products, for the Stock tab's list. */
    public static List<Item> archerItems() {
        return List.of(Items.ARROW, Items.BOW);
    }

    /** Arrows one craft makes at a crafting table (a fletching table makes more). */
    public static final int ARROWS_PER_CRAFT = 4;

    /** Who makes {@code item}: the blacksmith, the cook, the archers, or nobody (null). */
    @Nullable
    public static Profession producerOf(Item item) {
        if (isArcherItem(item)) return Profession.ARCHER;
        if (isForgeItem(item)) return Profession.BLACKSMITH;
        if (recipeFor(item) != null) return Profession.COOK;
        return null;
    }

    public static CookActivity.Recipe recipeFor(Item dish) {
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            if (recipe.result() == dish) return recipe;
        }
        return null;
    }

    /** Batches needed for {@code dishes} of the recipe's output, rounded up. */
    public static int batches(CookActivity.Recipe recipe, int dishes) {
        return (dishes + recipe.outputPerBatch() - 1) / recipe.outputPerBatch();
    }

    /**
     * Materials {@code units} of {@code item} take from storage, by item. Wood (any log or
     * planks) is left out: it is cheap, and no single item stands for it.
     */
    public static Map<Item, Integer> materialsFor(Item item, int units) {
        Map<Item, Integer> materials = new LinkedHashMap<>();
        if (units <= 0) return materials;
        if (item == Items.ARROW) {
            // Flint (or gravel sifted for it) and a feather per craft; sticks are wood.
            int crafts = (units + ARROWS_PER_CRAFT - 1) / ARROWS_PER_CRAFT;
            materials.put(Items.FLINT, crafts);
            materials.put(Items.FEATHER, crafts);
            return materials;
        }
        if (item == Items.BOW) {
            materials.put(Items.STRING, 3 * units);
            return materials;
        }
        if (isForgeItem(item)) {
            BlacksmithActivity.OrderInfo info = BlacksmithActivity.orderInfo(item);
            if (info == null) return materials;
            int batches = (units + info.count() - 1) / info.count();
            info.materials().forEach((material, n) -> {
                if (material != BlacksmithActivity.WOOD_STAND_IN) materials.put(material, n * batches);
            });
            return materials;
        }
        CookActivity.Recipe recipe = recipeFor(item);
        if (recipe == null) return materials;
        int batches = batches(recipe, units);
        recipe.inputsPerBatch().forEach((input, n) -> materials.put(input, n * batches));
        return materials;
    }

    // =========================================================================
    // Rows
    // =========================================================================

    public List<Row> rows(UUID village) {
        return Collections.unmodifiableList(byVillage.getOrDefault(village, List.of()));
    }

    public List<Done> history(UUID village) {
        return List.copyOf(history.getOrDefault(village, new ArrayDeque<>()));
    }

    @Nullable
    private Row find(UUID village, UUID id) {
        for (Row row : byVillage.getOrDefault(village, List.of())) {
            if (row.id.equals(id)) return row;
        }
        return null;
    }

    private int openOrders(UUID village) {
        int open = 0;
        for (Row row : byVillage.getOrDefault(village, List.of())) if (row.once) open++;
        return open;
    }

    /** Adds a row; false when the item cannot be ordered or the village is at its limit. */
    public boolean addRow(UUID village, Item item, boolean once, boolean forPlayer, int count,
                          @Nullable ServerPlayer by, long now) {
        if (producerOf(item) == null) return false;
        List<Row> rows = byVillage.computeIfAbsent(village, key -> new ArrayList<>());
        if (rows.size() >= MAX_ROWS || (once && openOrders(village) >= MAX_OPEN_ORDERS)) return false;
        Row row = new Row(UUID.randomUUID(), item);
        row.once = once;
        row.forPlayer = forPlayer;
        row.count = Math.clamp(count, 1, once ? MAX_ONCE : MAX_ALWAYS);
        row.placed = now;
        if (by != null) {
            row.orderedBy = by.getUUID();
            row.orderedByName = by.getGameProfile().getName();
        }
        rows.add(row);
        invalidate();
        setDirty();
        return true;
    }

    /**
     * Changes a row's count and switches. Turning Once into Always (or back) starts it
     * over; turning a once order from the player to the villagers lets go of what is
     * already in storage, and the other way round locks only what is still to come.
     */
    public void editRow(UUID village, UUID id, int count, boolean once, boolean forPlayer) {
        Row row = find(village, id);
        if (row == null) return;
        if (row.once != once) {
            if (once && openOrders(village) >= MAX_OPEN_ORDERS) return;
            row.once = once;
            row.made = 0;
            row.delivered = 0;
            row.collected = 0;
            row.claims.clear();
        }
        if (row.forPlayer != forPlayer) {
            row.forPlayer = forPlayer;
            row.collected = row.delivered;
        }
        row.count = Math.clamp(count, once ? Math.max(1, row.made) : 1, once ? MAX_ONCE : MAX_ALWAYS);
        invalidate();
        setDirty();
    }

    /** Removes a row. A once order already under way goes to the completed list as cancelled. */
    public void removeRow(UUID village, UUID id, long now) {
        List<Row> rows = byVillage.get(village);
        Row row = find(village, id);
        if (rows == null || row == null) return;
        rows.remove(row);
        if (rows.isEmpty()) byVillage.remove(village);
        if (row.once && row.made > 0) {
            remember(village, new Done(row.item, row.count, row.delivered, Outcome.CANCELLED,
                    row.forPlayer, row.orderedByName, now));
        }
        invalidate();
        setDirty();
    }

    private void remember(UUID village, Done done) {
        Deque<Done> list = history.computeIfAbsent(village, key -> new ArrayDeque<>());
        list.addFirst(done);
        while (list.size() > HISTORY) list.removeLast();
    }

    // =========================================================================
    // Makers: what to make, and what was made and stored
    // =========================================================================

    /**
     * What the rows ask of {@code producer}, once orders first (oldest first), then the
     * always rows' shortfalls. Units another maker has claimed are left out; {@code self}'s
     * own claims are not.
     */
    public List<Want> wants(VillageManager village, ServerLevel level, Profession producer, @Nullable UUID self) {
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null || rows.isEmpty()) return List.of();
        long now = level.getGameTime();
        List<Want> wants = new ArrayList<>();
        Set<Item> always = new LinkedHashSet<>();
        for (Row row : rows) {
            if (producerOf(row.item) != producer) continue;
            if (!row.once) {
                always.add(row.item);
                continue;
            }
            int units = row.toMake() - row.claimedByOthers(self, now);
            if (units > 0) wants.add(new Want(row.id, row.item, units, true));
        }
        for (Item item : always) {
            int units = shortfall(village, level, item);
            if (units > 0) wants.add(new Want(null, item, units, false));
        }
        return wants;
    }

    /**
     * How many more of {@code item} storage needs for its always rows: what they keep,
     * on top of the player's orders waiting there or on their way, minus what storage and
     * the makers' packs hold.
     */
    public int shortfall(VillageManager village, ServerLevel level, Item item) {
        int keep = 0, waiting = 0;
        for (Row row : byVillage.getOrDefault(village.getVillageId(), List.of())) {
            if (row.item != item) continue;
            if (row.once) waiting += row.ready() + row.inTransit();
            else keep += row.count;
        }
        if (keep == 0) return 0;
        return Math.max(0, keep + waiting - villageTotal(village, level, item) - carried(village, level, item));
    }

    /** A maker has started on an order: keeps other makers from making the same units. */
    public void claim(UUID village, UUID rowId, UUID npc, int units, long now) {
        Row row = find(village, rowId);
        if (row != null && row.once) row.claims.put(npc, new Claim(units, now + CLAIM_TICKS));
    }

    public void release(UUID village, @Nullable UUID rowId, UUID npc) {
        if (rowId == null) return;
        Row row = find(village, rowId);
        if (row != null) row.claims.remove(npc);
    }

    /**
     * {@code amount} of {@code item} were made for once orders: the claimed order first,
     * then the oldest still short. Anything beyond the orders is the village's.
     */
    public void onMade(UUID village, @Nullable UUID rowId, UUID npc, Item item, int amount) {
        List<Row> rows = byVillage.get(village);
        if (rows == null) return;
        List<Row> order = new ArrayList<>();
        Row claimed = rowId == null ? null : find(village, rowId);
        if (claimed != null) order.add(claimed);
        for (Row row : rows) if (row != claimed) order.add(row);
        for (Row row : order) {
            if (amount <= 0) break;
            if (!row.once || row.item != item) continue;
            row.claims.remove(npc);
            int add = Math.min(amount, row.toMake());
            row.made += add;
            amount -= add;
        }
        invalidate();
        setDirty();
    }

    /** The maker put {@code amount} of {@code item} into storage: orders on their way first. */
    public void onDelivered(VillageManager village, ServerLevel level, Item item, int amount) {
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null) return;
        boolean changed = false;
        for (Row row : rows) {
            if (amount <= 0) break;
            if (!row.once || row.item != item) continue;
            int add = Math.min(amount, row.inTransit());
            if (add <= 0) continue;
            row.delivered += add;
            amount -= add;
            changed = true;
            if (row.forPlayer && row.delivered >= row.count && !row.announced) {
                row.announced = true;
                notifyReady(level, row);
            }
        }
        if (!changed) return;
        finishCompleted(village, level);
        invalidate();
        setDirty();
    }

    /**
     * Every {@value #TICK_EVERY} ticks: the player's orders taken out of storage count as
     * collected, orders lost from a maker's pack (eaten, or the maker gone) are made again,
     * and finished orders move to the completed list.
     */
    public void tick(VillageManager village, ServerLevel level) {
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null || rows.isEmpty()) return;
        long now = level.getGameTime();
        if ((now + (village.getVillageId().hashCode() & 0x7fff)) % TICK_EVERY != 0) return;
        invalidate();
        boolean changed = false;

        Set<Item> items = new LinkedHashSet<>();
        for (Row row : rows) {
            items.add(row.item);
            row.claims.entrySet().removeIf(claim -> VillagerPause.lapsed(claim.getKey(), claim.getValue().until(), now));
        }
        boolean makersLoaded = makersLoaded(village);
        for (Item item : items) {
            // Locked items only leave storage by the player's hand (or a hopper). Waiting
            // orders count as the first in storage: an always row may still be short.
            int ready = 0;
            for (Row row : rows) if (row.item == item) ready += row.ready();
            int taken = ready - villageTotal(village, level, item);
            for (Row row : rows) {
                if (taken <= 0) break;
                if (row.item != item || row.ready() <= 0) continue;
                int c = Math.min(taken, row.ready());
                row.collected += c;
                taken -= c;
                changed = true;
            }
            if (!makersLoaded) continue;
            int transit = 0;
            for (Row row : rows) if (row.item == item) transit += row.inTransit();
            int lost = transit - carried(village, level, item);
            for (int i = rows.size() - 1; i >= 0 && lost > 0; i--) {
                Row row = rows.get(i);
                if (row.item != item || row.inTransit() <= 0) continue;
                int back = Math.min(lost, row.inTransit());
                row.made -= back;
                lost -= back;
                changed = true;
            }
        }
        if (finishCompleted(village, level)) changed = true;
        if (changed) {
            invalidate();
            setDirty();
        }
    }

    private boolean finishCompleted(VillageManager village, ServerLevel level) {
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null) return false;
        boolean any = false;
        for (Iterator<Row> it = rows.iterator(); it.hasNext(); ) {
            Row row = it.next();
            if (!row.complete()) continue;
            it.remove();
            any = true;
            Outcome outcome = row.forPlayer ? Outcome.COLLECTED : Outcome.DELIVERED;
            remember(village.getVillageId(), new Done(row.item, row.count, row.delivered, outcome,
                    row.forPlayer, row.orderedByName, level.getGameTime()));
            notifyDone(level, row);
        }
        if (rows.isEmpty()) byVillage.remove(village.getVillageId());
        return any;
    }

    /**
     * Tells whoever ordered it. The player's own orders are announced when they are all in
     * storage, which is when the player can come and take them; collecting them is no news.
     */
    private static void notifyDone(ServerLevel level, Row row) {
        if (row.orderedBy == null || row.forPlayer) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(row.orderedBy);
        if (player == null) return;
        player.sendSystemMessage(Component.literal("Your order is in storage for the villagers: "
                + row.count + " " + row.item.getDescription().getString() + "."));
    }

    /** Called when the player's order has all been delivered; see {@link #notifyDone}. */
    private static void notifyReady(ServerLevel level, Row row) {
        if (row.orderedBy == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(row.orderedBy);
        if (player == null) return;
        player.sendSystemMessage(Component.literal("Your order is ready in the storage room: "
                + row.count + " " + row.item.getDescription().getString() + "."));
    }

    private static boolean makersLoaded(VillageManager village) {
        for (VillagerModel model : village.getVillagers()) {
            if (model.puppet() == null) return false;
        }
        return true;
    }

    // =========================================================================
    // Reserves: what villagers may not take
    // =========================================================================

    /** What of {@code item} is the player's: always rows for them, plus their orders waiting in storage. */
    public int lockedAmount(UUID village, Item item) {
        int locked = 0;
        for (Row row : byVillage.getOrDefault(village, List.of())) {
            if (row.item != item || !row.forPlayer) continue;
            locked += row.once ? row.ready() : row.count;
        }
        return locked;
    }

    /**
     * Materials held back for what the rows still need made, by the maker that spends
     * them. Iron counts ingots first, then raw iron.
     */
    public Map<Item, Integer> heldFor(VillageManager village, ServerLevel level, Profession maker) {
        refreshCache(level);
        return held.computeIfAbsent(village.getVillageId(), key -> new HashMap<>())
                .computeIfAbsent(maker, key -> computeHeld(village, level, maker));
    }

    private Map<Item, Integer> computeHeld(VillageManager village, ServerLevel level, Profession maker) {
        Map<Item, Integer> materials = new HashMap<>();
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null) return materials;
        Set<Item> always = new LinkedHashSet<>();
        for (Row row : rows) {
            if (producerOf(row.item) != maker) continue;
            if (row.once) materialsFor(row.item, row.toMake()).forEach((m, n) -> materials.merge(m, n, Integer::sum));
            else always.add(row.item);
        }
        for (Item item : always) {
            materialsFor(item, shortfall(village, level, item))
                    .forEach((m, n) -> materials.merge(m, n, Integer::sum));
        }
        Integer iron = materials.get(Items.IRON_INGOT);
        if (iron != null) {
            int ingots = villageTotal(village, level, Items.IRON_INGOT);
            if (iron > ingots) materials.put(Items.RAW_IRON, iron - ingots);
            materials.put(Items.IRON_INGOT, Math.min(iron, ingots));
        }
        return materials;
    }

    /** Everything held back of {@code item}: the player's, plus every maker's materials. */
    public int reservedAmount(VillageManager village, ServerLevel level, Item item) {
        return reservedAmount(village, level, item, null);
    }

    private int reservedAmount(VillageManager village, ServerLevel level, Item item, @Nullable Profession taker) {
        int reserved = lockedAmount(village.getVillageId(), item);
        if (!byVillage.containsKey(village.getVillageId())) return reserved;
        // A maker may spend what is held for its own work, never the player's things.
        if (taker != Profession.COOK) reserved += heldFor(village, level, Profession.COOK).getOrDefault(item, 0);
        if (taker != Profession.BLACKSMITH) {
            reserved += heldFor(village, level, Profession.BLACKSMITH).getOrDefault(item, 0);
        }
        if (taker != Profession.ARCHER) {
            reserved += heldFor(village, level, Profession.ARCHER).getOrDefault(item, 0);
        }
        return reserved;
    }

    /**
     * How many of {@code item} a villager may take from the village's storage: everything
     * when nothing is held back, otherwise only what sits above it.
     */
    public int free(VillageManager village, ServerLevel level, Item item) {
        return free(village, level, item, null);
    }

    /** As {@link #free(VillageManager, ServerLevel, Item)}, for a villager of {@code taker}'s profession. */
    public int free(VillageManager village, ServerLevel level, Item item, @Nullable Profession taker) {
        int reserved = reservedAmount(village, level, item, taker);
        if (reserved <= 0) return Integer.MAX_VALUE;
        return Math.max(0, villageTotal(village, level, item) - reserved);
    }

    /**
     * Blacksmith and archer items whose whole stored amount is the player's: no villager
     * is handed one. Empty when the village keeps none.
     */
    public Set<Item> lockedForgeItems(VillageManager village, ServerLevel level) {
        List<Row> rows = byVillage.get(village.getVillageId());
        if (rows == null) return Set.of();
        Set<Item> locked = new HashSet<>();
        for (Row row : rows) {
            if (!(isForgeItem(row.item) || isArcherItem(row.item)) || locked.contains(row.item)) continue;
            int amount = lockedAmount(village.getVillageId(), row.item);
            if (amount > 0 && villageTotal(village, level, row.item) <= amount) locked.add(row.item);
        }
        return locked;
    }

    // =========================================================================
    // Counting
    // =========================================================================

    private void refreshCache(ServerLevel level) {
        long now = level.getGameTime();
        if (now == cacheTick) return;
        totals.clear();
        carried.clear();
        held.clear();
        cacheTick = now;
    }

    /**
     * Drops the counts kept for this tick. The away calculator calls it when its chest
     * copies come and go, and after it takes or stores: several of its steps run in one
     * tick, each changing the copies, and the counts would otherwise be a step behind.
     */
    public void forgetCounts() {
        invalidate();
    }

    private void invalidate() {
        cacheTick = Long.MIN_VALUE;
        totals.clear();
        carried.clear();
        held.clear();
    }

    public int villageTotal(VillageManager village, ServerLevel level, Item item) {
        refreshCache(level);
        return totals.computeIfAbsent(village.getVillageId(), key -> new HashMap<>())
                .computeIfAbsent(item, key -> {
                    int total = 0;
                    for (Building building : village.getBuildings()) {
                        if (building instanceof StorageBuilding storage && storage.isValid()) {
                            total += storage.countItem(level, item);
                        }
                    }
                    return total;
                });
    }

    /**
     * What the item's makers (cooks or blacksmiths) carry of it. Not the archers: the
     * arrows and bow an archer carries are its own quiver, not an order on its way, and
     * an archer counts its orders made and delivered together, as it puts them away.
     */
    public int carried(VillageManager village, ServerLevel level, Item item) {
        Profession maker = producerOf(item);
        if (maker == null || maker == Profession.ARCHER) return 0;
        refreshCache(level);
        return carried.computeIfAbsent(village.getVillageId(), key -> new HashMap<>())
                .computeIfAbsent(item, key -> {
                    int total = 0;
                    for (VillagerModel model : village.getVillagers()) {
                        NpcEntity npc = model.puppet();
                        if (npc == null || npc.getProfession() != maker) continue;
                        for (ItemStack stack : npc.getInventory().getSlots()) {
                            if (stack.getItem() == item) total += stack.getCount();
                        }
                    }
                    return total;
                });
    }

    // =========================================================================
    // Saving
    // =========================================================================

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag villages = new ListTag();
        Set<UUID> ids = new LinkedHashSet<>(byVillage.keySet());
        ids.addAll(history.keySet());
        for (UUID village : ids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("village", village);
            ListTag rows = new ListTag();
            for (Row row : byVillage.getOrDefault(village, List.of())) {
                CompoundTag r = new CompoundTag();
                r.putUUID("id", row.id);
                r.putString("item", BuiltInRegistries.ITEM.getKey(row.item).toString());
                r.putInt("count", row.count);
                r.putBoolean("once", row.once);
                r.putBoolean("forPlayer", row.forPlayer);
                r.putInt("made", row.made);
                r.putInt("delivered", row.delivered);
                r.putInt("collected", row.collected);
                r.putBoolean("announced", row.announced);
                if (row.orderedBy != null) r.putUUID("by", row.orderedBy);
                r.putString("byName", row.orderedByName);
                r.putLong("placed", row.placed);
                rows.add(r);
            }
            entry.put("rows", rows);
            ListTag done = new ListTag();
            for (Done d : history.getOrDefault(village, new ArrayDeque<>())) {
                CompoundTag r = new CompoundTag();
                r.putString("item", BuiltInRegistries.ITEM.getKey(d.item()).toString());
                r.putInt("count", d.count());
                r.putInt("delivered", d.delivered());
                r.putString("outcome", d.outcome().name());
                r.putBoolean("forPlayer", d.forPlayer());
                r.putString("by", d.by());
                r.putLong("finished", d.finished());
                done.add(r);
            }
            entry.put("history", done);
            villages.add(entry);
        }
        tag.put("villages", villages);
        return tag;
    }

    @Nullable
    private static Item readItem(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("item"));
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? null : item;
    }

    private static StockReserves load(CompoundTag tag, HolderLookup.Provider registries) {
        StockReserves reserves = new StockReserves();
        ListTag villages = tag.getList("villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < villages.size(); i++) {
            CompoundTag entry = villages.getCompound(i);
            if (!entry.hasUUID("village")) continue;
            UUID village = entry.getUUID("village");
            List<Row> rows = new ArrayList<>();
            ListTag saved = entry.getList("rows", Tag.TAG_COMPOUND);
            for (int j = 0; j < saved.size(); j++) {
                CompoundTag r = saved.getCompound(j);
                Item item = readItem(r);
                if (item == null || producerOf(item) == null || !r.hasUUID("id")) continue;
                Row row = new Row(r.getUUID("id"), item);
                row.once = r.getBoolean("once");
                row.forPlayer = r.getBoolean("forPlayer");
                row.count = Math.max(1, r.getInt("count"));
                row.made = r.getInt("made");
                row.delivered = r.getInt("delivered");
                row.collected = r.getInt("collected");
                row.announced = r.getBoolean("announced");
                if (r.hasUUID("by")) row.orderedBy = r.getUUID("by");
                row.orderedByName = r.getString("byName");
                row.placed = r.getLong("placed");
                rows.add(row);
            }
            // Before orders: "dishes" was a plain keep list. Blacksmith items were kept for
            // the player; dishes held their ingredients for the cook, now "always, for villagers".
            ListTag old = entry.getList("dishes", Tag.TAG_COMPOUND);
            for (int j = 0; j < old.size(); j++) {
                CompoundTag r = old.getCompound(j);
                Item item = readItem(r);
                if (item == null || producerOf(item) == null || r.getInt("count") <= 0) continue;
                Row row = new Row(UUID.randomUUID(), item);
                row.forPlayer = isForgeItem(item);
                row.count = Math.min(MAX_ALWAYS, r.getInt("count"));
                rows.add(row);
            }
            if (!rows.isEmpty()) reserves.byVillage.put(village, rows);
            ListTag done = entry.getList("history", Tag.TAG_COMPOUND);
            Deque<Done> list = new ArrayDeque<>();
            for (int j = 0; j < done.size(); j++) {
                CompoundTag r = done.getCompound(j);
                Item item = readItem(r);
                if (item == null) continue;
                Outcome outcome;
                try {
                    outcome = Outcome.valueOf(r.getString("outcome"));
                } catch (IllegalArgumentException e) {
                    outcome = Outcome.DELIVERED;
                }
                list.addLast(new Done(item, r.getInt("count"), r.getInt("delivered"), outcome,
                        r.getBoolean("forPlayer"), r.getString("by"), r.getLong("finished")));
            }
            if (!list.isEmpty()) reserves.history.put(village, list);
        }
        return reserves;
    }
}
