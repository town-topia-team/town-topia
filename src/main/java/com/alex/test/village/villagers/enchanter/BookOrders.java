package com.alex.test.village.villagers.enchanter;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Enchanted books the player orders from the enchanter, the Enchanter page of
 * the storage room's Stock tab.
 *
 * <p>Each order is one enchantment at one level, a count, and Once or Always:
 * once makes that many and is done when the player has taken them all out of
 * storage; always keeps that many waiting in storage and writes another when
 * the player takes one. Books ordered are always the player's: each carries a
 * mark of its order and a "Made for" line, and no villager puts it on a tool,
 * counts it as covering a tool, or sells it. Books for the villagers need no
 * order: the enchanter plans those from their tools itself.
 *
 * <p>Orders come first for the enchanter, before the village's own needs, but
 * inside its day's books; a book the enchanter cannot write yet (not learned,
 * or the library too small) waits and says why.
 *
 * <p>Kept apart from {@code StockReserves}, whose rows are plain items: two
 * enchanted books are the same item and differ only in what they store.
 */
public final class BookOrders extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_book_orders";
    private static final SavedData.Factory<BookOrders> FACTORY =
            new SavedData.Factory<>(BookOrders::new, BookOrders::load, null);

    public static final int MAX_ROWS = 8;
    public static final int MAX_ONCE = 16;
    public static final int MAX_ALWAYS = 4;
    public static final int HISTORY = 12;
    private static final int TICK_EVERY = 40;
    /** Custom data key of a book made for an order: the order's id. */
    static final String TAG_ORDER = "dynamicvillages_book_order";

    public static final int COLLECTED = 0, CANCELLED = 1;

    /** One order. */
    public static final class Order {
        public final UUID id;
        public final String kind;
        public final int level;
        int count;
        boolean once;
        int made;
        int collected;
        /** Its books in storage and packs, as of the last count. */
        int held;
        /** Of those, the ones in storage. */
        int stored;
        @Nullable UUID orderedBy;
        String orderedByName = "";
        long placed;
        boolean announced;

        Order(UUID id, String kind, int level) {
            this.id = id;
            this.kind = kind;
            this.level = level;
        }

        public int count() { return count; }
        public boolean once() { return once; }
        public int made() { return made; }
        public int held() { return held; }
        public int stored() { return stored; }
        public String orderedByName() { return orderedByName; }

        /** Books still to write: the rest of a once order, or what an always order is short. */
        public int toMake() {
            return once ? Math.max(0, count - made) : Math.max(0, count - held);
        }

        @Nullable
        public EnchantBooks.Kind kindOf() { return EnchantBooks.byId(kind); }
    }

    public record Done(String kind, int level, int count, int outcome, String by, long finished) {}

    /** What an order asks of the enchanter now. */
    public record Want(UUID order, EnchantBooks.Kind kind, int level, int units, String by) {}

    private final Map<UUID, List<Order>> byVillage = new HashMap<>();
    private final Map<UUID, Deque<Done>> history = new HashMap<>();

    public static BookOrders get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    // =========================================================================
    // Orders
    // =========================================================================

    public List<Order> orders(UUID village) {
        return Collections.unmodifiableList(byVillage.getOrDefault(village, List.of()));
    }

    public List<Done> history(UUID village) {
        return List.copyOf(history.getOrDefault(village, new ArrayDeque<>()));
    }

    @Nullable
    private Order find(UUID village, UUID id) {
        for (Order order : byVillage.getOrDefault(village, List.of())) {
            if (order.id.equals(id)) return order;
        }
        return null;
    }

    /** A new order; false for an unknown book or when the village has its limit. */
    public boolean add(UUID village, String kindId, int level, boolean once, int count,
                       @Nullable ServerPlayer by, long now) {
        EnchantBooks.Kind kind = EnchantBooks.byId(kindId);
        if (kind == null || level < 1 || level > kind.maxLevel()) return false;
        List<Order> orders = byVillage.computeIfAbsent(village, key -> new ArrayList<>());
        if (orders.size() >= MAX_ROWS) return false;
        Order order = new Order(UUID.randomUUID(), kindId, level);
        order.once = once;
        order.count = Math.clamp(count, 1, once ? MAX_ONCE : MAX_ALWAYS);
        order.placed = now;
        if (by != null) {
            order.orderedBy = by.getUUID();
            order.orderedByName = by.getGameProfile().getName();
        }
        orders.add(order);
        setDirty();
        return true;
    }

    /** A new count, or Once and Always swapped (which starts the order over). */
    public void edit(UUID village, UUID id, int count, boolean once) {
        Order order = find(village, id);
        if (order == null) return;
        if (order.once != once) {
            order.once = once;
            order.made = order.held;
            order.collected = 0;
            order.announced = false;
        }
        order.count = Math.clamp(count, once ? Math.max(1, order.made) : 1,
                once ? MAX_ONCE : MAX_ALWAYS);
        setDirty();
    }

    /** Removes an order. Books already made for it stay marked as the player's. */
    public void remove(UUID village, UUID id, long dayTime) {
        List<Order> orders = byVillage.get(village);
        Order order = find(village, id);
        if (orders == null || order == null) return;
        orders.remove(order);
        if (orders.isEmpty()) byVillage.remove(village);
        if (order.once && order.made > 0) {
            remember(village, new Done(order.kind, order.level, order.count, CANCELLED, order.orderedByName, dayTime));
        }
        setDirty();
    }

    private void remember(UUID village, Done done) {
        Deque<Done> list = history.computeIfAbsent(village, key -> new ArrayDeque<>());
        list.addFirst(done);
        while (list.size() > HISTORY) list.removeLast();
    }

    // =========================================================================
    // The enchanter's side
    // =========================================================================

    /** What the orders ask for now, oldest first. */
    public List<Want> wants(UUID village) {
        List<Want> wants = new ArrayList<>();
        for (Order order : byVillage.getOrDefault(village, List.of())) {
            EnchantBooks.Kind kind = order.kindOf();
            int units = order.toMake();
            if (kind != null && units > 0) wants.add(new Want(order.id, kind, order.level, units, order.orderedByName));
        }
        return wants;
    }

    /** A book was written for {@code orderId}: marks it as the player's and counts it. */
    public ItemStack onMade(UUID village, UUID orderId, ItemStack book) {
        Order order = find(village, orderId);
        // A second enchanter finishing the same last book: the spare is the village's.
        if (order == null || (order.once && order.made >= order.count)) return book;
        order.made++;
        order.held++;
        mark(book, order);
        setDirty();
        return book;
    }

    /**
     * A book written while the village was away (the new away calculator): marked as
     * {@link #onMade} marks it, but not counted, so a preview changes no order; the
     * calculator counts it at its end ({@link #onMadeAway}). {@code madeAway}: books
     * already written for it in the same catch-up. Left the village's when the order
     * is gone or full.
     */
    public ItemStack markAway(UUID village, UUID orderId, ItemStack book, int madeAway) {
        Order order = find(village, orderId);
        if (order == null || (order.once && order.made + madeAway >= order.count)) return book;
        mark(book, order);
        return book;
    }

    /** The end of an away catch-up: {@code count} books {@link #markAway} marked for {@code orderId}, counted. */
    public void onMadeAway(UUID village, UUID orderId, int count) {
        Order order = find(village, orderId);
        if (order == null || count <= 0) return;
        int counted = order.once ? Math.min(count, Math.max(0, order.count - order.made)) : count;
        order.made += counted;
        order.held += counted;
        setDirty();
    }

    /** Marks a book as made for {@code order}, with a line saying who for. */
    private static void mark(ItemStack book, Order order) {
        CustomData.update(DataComponents.CUSTOM_DATA, book, tag -> tag.putUUID(TAG_ORDER, order.id));
        if (!order.orderedByName.isEmpty()) {
            book.set(DataComponents.LORE, new ItemLore(List.of(
                    Component.literal("Made for " + order.orderedByName).withStyle(
                            net.minecraft.ChatFormatting.GRAY))));
        }
    }

    /** Whether a book was made for a player's order: villagers leave it alone. */
    public static boolean isOrdered(ItemStack stack) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.getUnsafe().hasUUID(TAG_ORDER);
    }

    @Nullable
    private static UUID orderOf(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.getUnsafe().hasUUID(TAG_ORDER) ? data.getUnsafe().getUUID(TAG_ORDER) : null;
    }

    // =========================================================================
    // Counting what was made, stored and taken
    // =========================================================================

    /**
     * Every {@value #TICK_EVERY} ticks: counts each order's books in storage and
     * in packs. Books gone from both were taken by the player: a once order is
     * finished when all of its books are; an always order writes more.
     */
    public void tick(VillageManager village, ServerLevel level) {
        List<Order> orders = byVillage.get(village.getVillageId());
        if (orders == null || orders.isEmpty()) return;
        long now = level.getGameTime();
        if ((now + (village.getVillageId().hashCode() & 0x7fff)) % TICK_EVERY != 0) return;
        if (!makersLoaded(village)) return;

        Map<UUID, int[]> counted = count(village, level);
        boolean changed = false;
        for (Order order : new ArrayList<>(orders)) {
            int[] c = counted.getOrDefault(order.id, new int[2]);
            int held = c[0] + c[1];
            if (held != order.held || c[0] != order.stored) changed = true;
            order.held = held;
            order.stored = c[0];
            if (!order.once) continue;
            int taken = Math.max(0, order.made - held);
            if (taken > order.collected) {
                order.collected = taken;
                changed = true;
            }
            if (!order.announced && order.made >= order.count && c[1] == 0 && order.stored > 0) {
                order.announced = true;
                notifyReady(level, order);
                changed = true;
            }
            if (order.collected >= order.count) {
                orders.remove(order);
                remember(village.getVillageId(), new Done(order.kind, order.level, order.count, COLLECTED,
                        order.orderedByName, level.getDayTime()));
                changed = true;
            }
        }
        if (orders.isEmpty()) byVillage.remove(village.getVillageId());
        if (changed) setDirty();
    }

    /** Per order: {stored, carried} books with its mark. */
    private static Map<UUID, int[]> count(VillageManager village, ServerLevel level) {
        Map<UUID, int[]> counted = new HashMap<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(level)) {
                for (int i = 0; i < chest.size(); i++) {
                    UUID order = orderOf(chest.peek(i));
                    if (order != null) counted.computeIfAbsent(order, k -> new int[2])[0] += chest.peek(i).getCount();
                }
            }
        }
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null) continue;
            InventoryManager inv = npc.getInventory();
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                UUID order = orderOf(inv.getSlot(i));
                if (order != null) counted.computeIfAbsent(order, k -> new int[2])[1] += inv.getSlot(i).getCount();
            }
        }
        return counted;
    }

    private static boolean makersLoaded(VillageManager village) {
        for (VillagerModel model : village.getVillagers()) {
            if (model.puppet() == null) return false;
        }
        return true;
    }

    private static void notifyReady(ServerLevel level, Order order) {
        if (order.orderedBy == null) return;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(order.orderedBy);
        EnchantBooks.Kind kind = order.kindOf();
        if (player == null || kind == null) return;
        player.sendSystemMessage(Component.literal("Your enchanted book order is ready in the storage room: "
                + order.count + " " + EnchantBooks.name(kind, order.level) + "."));
    }

    /** The day an order finished, for the Completed list. */
    public static long day(long dayTime) {
        return VillageDays.dayIndex(dayTime);
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
            for (Order order : byVillage.getOrDefault(village, List.of())) {
                CompoundTag r = new CompoundTag();
                r.putUUID("id", order.id);
                r.putString("kind", order.kind);
                r.putInt("level", order.level);
                r.putInt("count", order.count);
                r.putBoolean("once", order.once);
                r.putInt("made", order.made);
                r.putInt("collected", order.collected);
                r.putInt("held", order.held);
                r.putInt("stored", order.stored);
                r.putBoolean("announced", order.announced);
                if (order.orderedBy != null) r.putUUID("by", order.orderedBy);
                r.putString("byName", order.orderedByName);
                r.putLong("placed", order.placed);
                rows.add(r);
            }
            entry.put("orders", rows);
            ListTag done = new ListTag();
            for (Done d : history.getOrDefault(village, new ArrayDeque<>())) {
                CompoundTag r = new CompoundTag();
                r.putString("kind", d.kind());
                r.putInt("level", d.level());
                r.putInt("count", d.count());
                r.putInt("outcome", d.outcome());
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

    private static BookOrders load(CompoundTag tag, HolderLookup.Provider registries) {
        BookOrders orders = new BookOrders();
        ListTag villages = tag.getList("villages", Tag.TAG_COMPOUND);
        for (int i = 0; i < villages.size(); i++) {
            CompoundTag entry = villages.getCompound(i);
            if (!entry.hasUUID("village")) continue;
            UUID village = entry.getUUID("village");
            List<Order> list = new ArrayList<>();
            ListTag saved = entry.getList("orders", Tag.TAG_COMPOUND);
            for (int j = 0; j < saved.size(); j++) {
                CompoundTag r = saved.getCompound(j);
                if (!r.hasUUID("id") || EnchantBooks.byId(r.getString("kind")) == null) continue;
                Order order = new Order(r.getUUID("id"), r.getString("kind"), Math.max(1, r.getInt("level")));
                order.count = Math.max(1, r.getInt("count"));
                order.once = r.getBoolean("once");
                order.made = r.getInt("made");
                order.collected = r.getInt("collected");
                order.held = r.getInt("held");
                order.stored = r.getInt("stored");
                order.announced = r.getBoolean("announced");
                if (r.hasUUID("by")) order.orderedBy = r.getUUID("by");
                order.orderedByName = r.getString("byName");
                order.placed = r.getLong("placed");
                list.add(order);
            }
            if (!list.isEmpty()) orders.byVillage.put(village, list);
            Deque<Done> done = new ArrayDeque<>();
            ListTag finished = entry.getList("history", Tag.TAG_COMPOUND);
            for (int j = 0; j < finished.size(); j++) {
                CompoundTag r = finished.getCompound(j);
                done.addLast(new Done(r.getString("kind"), r.getInt("level"), r.getInt("count"),
                        r.getInt("outcome"), r.getString("by"), r.getLong("finished")));
            }
            if (!done.isEmpty()) orders.history.put(village, done);
        }
        return orders;
    }
}
