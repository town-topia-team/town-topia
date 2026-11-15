package com.alex.test.village.economy.away;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageContainers;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The village book: a copy of every container of every storage room, read
 * once when the catch-up starts.
 *
 * <p>While an away step runs ({@link #open} to {@link #close}),
 * {@code StorageContainers.inventory} answers each copied position with its
 * copy, so the live storage helpers (deposit order, filters, withdraw, tool
 * picks, counts, ownership checks) work on the copies unchanged. Outside a
 * step the game sees the real chests. {@link #write} then gives each real
 * container the difference: what went down in the copy is taken out, what
 * went up is put in. A real chest a player changed meanwhile is made up from
 * or spills into the other rooms; anything left is logged as lost.
 *
 * <p>Only containers in loaded chunks are copied (a catch-up never loads a
 * chunk). Chests outside the storage rooms (houses, the sell box) are not in
 * the book: a step sees them real.
 */
final class AwayBook implements StorageContainers.ContainerOverride {

    private static final Logger LOGGER = LogUtils.getLogger();

    private final ServerLevel level;
    private final List<StorageBuilding> rooms = new ArrayList<>();
    /** Each copied position ({@link BlockPos#asLong}), to the copy of what it shows. */
    private final Map<Long, BookChest> byPos = new HashMap<>();
    /** One copy per distinct contents ({@link StorageInventory#key}). */
    private final Map<Object, BookChest> byKey = new LinkedHashMap<>();
    private boolean open;

    private AwayBook(ServerLevel level) {
        this.level = level;
    }

    /** Copies every container of the village's standing storage rooms. Call with no book open. */
    static AwayBook read(ServerLevel level, VillageManager mgr) {
        AwayBook book = new AwayBook(level);
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof StorageBuilding room) || !room.isValid()) continue;
            book.rooms.add(room);
            for (BlockPos pos : room.getFunctionalBlocks()) {
                if (!level.isLoaded(pos)) continue;
                StorageInventory real = StorageContainers.inventory(level, pos);
                if (real == null) continue;
                BookChest chest = book.byKey.computeIfAbsent(real.key(), key -> new BookChest(real, pos.immutable()));
                book.byPos.put(pos.asLong(), chest);
            }
        }
        return book;
    }

    // =========================================================================
    // The stand-in, open only while a step runs
    // =========================================================================

    void open() {
        if (open) return;
        StorageContainers.setOverride(this);
        open = true;
        reindex();
    }

    void close() {
        if (!open) return;
        StorageContainers.setOverride(null);
        open = false;
        reindex();
    }

    /**
     * The rooms' content index caches positions per item, and the Stock tab its counts
     * per tick: rebuilt from whatever is showing.
     */
    private void reindex() {
        for (StorageBuilding room : rooms) room.invalidateContentIndex();
        StockReserves.get(level.getServer()).forgetCounts();
    }

    // =========================================================================
    // Held back while away
    // =========================================================================

    /**
     * Goods made away for the player's Stock tab orders (round 4): in storage, but
     * the player's. The real order counts them only at WRITE, so until then this
     * keeps them from the village's meals and takes.
     */
    private final Map<Item, Integer> heldAway = new HashMap<>();

    void holdAway(Item item, int count) {
        if (count > 0) heldAway.merge(item, count, Integer::sum);
    }

    int heldAway(Item item) {
        return heldAway.getOrDefault(item, 0);
    }

    /**
     * How many of {@code item} a villager of {@code taker} may take from storage: the
     * Stock tab's {@code free} less what is held away. Large when nothing is held.
     */
    int free(ServerLevel askedLevel, VillageManager mgr, Item item, @Nullable Profession taker) {
        int free = StockReserves.get(askedLevel.getServer()).free(mgr, askedLevel, item, taker);
        int held = heldAway.getOrDefault(item, 0);
        if (held <= 0) return free;
        if (free == Integer.MAX_VALUE) free = count(item);
        return Math.max(0, free - held);
    }

    /** Every {@code item} in the copies, whoever's. */
    private int count(Item item) {
        int count = 0;
        for (BookChest chest : byKey.values()) {
            for (ItemStack stack : chest.slots) {
                if (stack.is(item)) count += stack.getCount();
            }
        }
        return count;
    }

    /** The copies changed (a take, a store, a meal): the Stock tab counts again. */
    void changed() {
        StockReserves.get(level.getServer()).forgetCounts();
    }

    @Override
    @Nullable
    public StorageInventory at(Level askedLevel, BlockPos pos) {
        return askedLevel == level ? byPos.get(pos.asLong()) : null;
    }

    // =========================================================================
    // For the preview and the log
    // =========================================================================

    int containers() { return byKey.size(); }

    int rooms() { return rooms.size(); }

    /** The standing storage rooms read. */
    List<StorageBuilding> storageRooms() { return rooms; }

    /** Every copy once (a double chest is one), for code that reads the copies themselves. */
    Collection<BookChest> chests() { return byKey.values(); }

    /** Villager food in the copies, in items. */
    int food() {
        int food = 0;
        for (BookChest chest : byKey.values()) {
            for (ItemStack stack : chest.slots) {
                if (!stack.isEmpty() && VillageItemOwnership.isVillagerFood(stack)) food += stack.getCount();
            }
        }
        return food;
    }

    /** Item counts in the copies, by item id. */
    Map<String, Integer> totals() {
        Map<String, Integer> totals = new TreeMap<>();
        for (BookChest chest : byKey.values()) {
            for (ItemStack stack : chest.slots) {
                if (!stack.isEmpty()) totals.merge(id(stack.getItem()), stack.getCount(), Integer::sum);
            }
        }
        return totals;
    }

    long items() {
        long items = 0L;
        for (int count : totals().values()) items += count;
        return items;
    }

    // =========================================================================
    // WRITE: the real containers get the differences
    // =========================================================================

    /** What {@link #write} moved, in item counts. */
    record Written(int takenOut, int putIn, int missing, int lost) {}

    /** Gives every real container its copy's difference. Call with the book closed. */
    Written write() {
        close();
        List<Change> out = new ArrayList<>();
        List<Change> in = new ArrayList<>();
        for (BookChest chest : byKey.values()) chest.differences(out, in);
        int takenOut = 0;
        int missing = 0;
        // Taking out first makes room for what goes in.
        for (Change change : out) {
            int wanted = change.stack().getCount();
            int got = takeOut(real(change.chest()), change.stack(), wanted);
            for (BookChest other : byKey.values()) {
                if (got >= wanted) break;
                if (other != change.chest()) got += takeOut(real(other), change.stack(), wanted - got);
            }
            takenOut += got;
            if (got < wanted) {
                missing += wanted - got;
                LOGGER.warn("[AwayWrite] {} {} used while away were no longer in storage",
                        wanted - got, id(change.stack().getItem()));
            }
        }
        int putIn = 0;
        int lost = 0;
        for (Change change : in) {
            ItemStack left = change.stack().copy();
            StorageInventory real = real(change.chest());
            if (real != null) left = real.insert(left, false);
            for (StorageBuilding room : rooms) {
                if (left.isEmpty()) break;
                room.depositItems(level, left);
            }
            putIn += change.stack().getCount() - left.getCount();
            if (!left.isEmpty()) {
                lost += left.getCount();
                LOGGER.warn("[AwayWrite] {} {} made while away found no room in storage and were lost",
                        left.getCount(), id(left.getItem()));
            }
        }
        reindex();
        return new Written(takenOut, putIn, missing, lost);
    }

    /** The real contents at a copy's position now, or null when that chest is gone. */
    @Nullable
    private StorageInventory real(BookChest chest) {
        return level.isLoaded(chest.pos) ? StorageContainers.inventory(level, chest.pos) : null;
    }

    /** Takes up to {@code amount} of {@code like} out of {@code inventory}; how many came out. */
    private static int takeOut(@Nullable StorageInventory inventory, ItemStack like, int amount) {
        if (inventory == null || amount <= 0) return 0;
        int got = 0;
        for (int i = 0; i < inventory.size() && got < amount; i++) {
            ItemStack stack = inventory.peek(i);
            if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, like)) continue;
            got += inventory.take(i, Math.min(amount - got, stack.getCount())).getCount();
        }
        return got;
    }

    /** {@code stack.getCount()} of this item went out of (or into) {@code chest}'s copy. */
    private record Change(BookChest chest, ItemStack stack) {}

    private static String id(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    // =========================================================================
    // One copied container
    // =========================================================================

    /**
     * The copy of one container's slots. It asks the real one only for its rules
     * (stack limits, which slot takes what), never for its contents.
     */
    static final class BookChest implements StorageInventory {
        final BlockPos pos;
        private final StorageInventory real;
        private final Object key;
        /** The slots as read, for the difference at the end. */
        private final ItemStack[] start;
        private final ItemStack[] slots;

        BookChest(StorageInventory real, BlockPos pos) {
            this.real = real;
            this.pos = pos;
            this.key = real.key();
            int size = real.size();
            start = new ItemStack[size];
            slots = new ItemStack[size];
            for (int i = 0; i < size; i++) {
                start[i] = real.peek(i).copy();
                slots[i] = start[i].copy();
            }
        }

        @Override public int size() { return slots.length; }

        @Override
        public ItemStack peek(int slot) {
            return slot >= 0 && slot < slots.length ? slots[slot] : ItemStack.EMPTY;
        }

        @Override
        public ItemStack take(int slot, int amount) {
            ItemStack stack = peek(slot);
            if (stack.isEmpty() || amount <= 0) return ItemStack.EMPTY;
            int taken = Math.min(amount, stack.getCount());
            ItemStack result = stack.copyWithCount(taken);
            stack.shrink(taken);
            if (stack.isEmpty()) slots[slot] = ItemStack.EMPTY;
            return result;
        }

        @Override
        public ItemStack insert(ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;
            int left = stack.getCount();
            // Top up matching stacks first, as the real containers do.
            for (int i = 0; i < slots.length && left > 0; i++) {
                ItemStack slot = slots[i];
                if (slot.isEmpty() || !ItemStack.isSameItemSameComponents(slot, stack)) continue;
                int add = Math.min(left, limit(i, stack) - slot.getCount());
                if (add <= 0) continue;
                if (!simulate) slot.grow(add);
                left -= add;
            }
            for (int i = 0; i < slots.length && left > 0; i++) {
                if (!slots[i].isEmpty() || !allowed(i, stack)) continue;
                int add = Math.min(left, limit(i, stack));
                if (add <= 0) continue;
                if (!simulate) slots[i] = stack.copyWithCount(add);
                left -= add;
            }
            return left <= 0 ? ItemStack.EMPTY : stack.copyWithCount(left);
        }

        @Override public Object key() { return key; }

        /** How many of {@code stack} slot {@code i} holds, by the real container's rules. */
        private int limit(int i, ItemStack stack) {
            if (real instanceof OfContainer container) return container.container().getMaxStackSize(stack);
            if (real instanceof OfHandler handler) {
                // A stack upgrade (Sophisticated) multiplies what a slot holds.
                int slotLimit = handler.handler().getSlotLimit(i);
                return slotLimit <= 64 ? Math.min(slotLimit, stack.getMaxStackSize())
                        : stack.getMaxStackSize() * (slotLimit / 64);
            }
            return stack.getMaxStackSize();
        }

        /** Whether an empty slot {@code i} takes {@code stack}, by the real container's rules. */
        private boolean allowed(int i, ItemStack stack) {
            if (real instanceof OfContainer container) return container.container().canPlaceItem(i, stack);
            if (real instanceof OfHandler handler) return handler.handler().isItemValid(i, stack);
            return true;
        }

        /** What went down ({@code out}) and up ({@code in}) since the copy was read. */
        private void differences(List<Change> out, List<Change> in) {
            List<ItemStack> before = tally(List.of(start));
            List<ItemStack> after = tally(List.of(slots));
            for (ItemStack now : after) {
                int was = count(before, now);
                if (now.getCount() > was) in.add(new Change(this, now.copyWithCount(now.getCount() - was)));
            }
            for (ItemStack was : before) {
                int now = count(after, was);
                if (was.getCount() > now) out.add(new Change(this, was.copyWithCount(was.getCount() - now)));
            }
        }

        /** One stack per item and components, with the whole count (may exceed a stack). */
        private static List<ItemStack> tally(Collection<ItemStack> stacks) {
            List<ItemStack> tally = new ArrayList<>();
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) continue;
                ItemStack same = find(tally, stack);
                if (same != null) same.grow(stack.getCount());
                else tally.add(stack.copy());
            }
            return tally;
        }

        private static int count(List<ItemStack> tally, ItemStack like) {
            ItemStack same = find(tally, like);
            return same == null ? 0 : same.getCount();
        }

        @Nullable
        private static ItemStack find(List<ItemStack> tally, ItemStack like) {
            for (ItemStack stack : tally) {
                if (ItemStack.isSameItemSameComponents(stack, like)) return stack;
            }
            return null;
        }
    }
}
