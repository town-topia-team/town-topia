package com.alex.test.village.villagers.tradesman;

import com.alex.test.entity.NpcEntity;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;

/**
 * One tradesman's record of what it sold to the merchant, kept in its own
 * saved data so it travels with the villager.
 *
 * <p>Holds the latest {@value #MAX_ENTRIES} sales plus running totals, so the
 * record never grows without limit however long the village trades.
 */
public final class TradesmanLedger {
    private TradesmanLedger() {}

    private static final String TAG = "TradesmanLedger";
    private static final String ENTRIES = "Entries";
    private static final String TOTAL_EMERALDS = "TotalEmeralds";
    private static final String TOTAL_ITEMS = "TotalItems";
    /** Sales kept in the list; older ones only live on in the totals. */
    public static final int MAX_ENTRIES = 200;
    /** Sales sent to the screen when it opens: the newest ones. */
    public static final int MAX_SENT = 120;

    /** One sale: when (village day number and clock, see VillageDays), what, how many,
     * for how much, whether it came from the stall's sell box, and the price it went at
     * as a share of normal (100 = the base price, less after recent sales). */
    public record Entry(long dayTime, ResourceLocation item, int count, int emeralds, boolean sellBox,
                        int percent) {
        public long day() { return dayTime / 24_000L + 1; }

        void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(dayTime);
            buffer.writeResourceLocation(item);
            buffer.writeVarInt(count);
            buffer.writeVarInt(emeralds);
            buffer.writeBoolean(sellBox);
            buffer.writeVarInt(percent);
        }

        static Entry read(RegistryFriendlyByteBuf buffer) {
            return new Entry(buffer.readVarLong(), buffer.readResourceLocation(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean(), buffer.readVarInt());
        }
    }

    /** What the screen shows: the newest sales first, and the lifetime totals. */
    public record Snapshot(List<Entry> entries, int totalEmeralds, int totalItems) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), 0, 0);

        public void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(totalEmeralds);
            buffer.writeVarInt(totalItems);
            buffer.writeVarInt(entries.size());
            for (Entry entry : entries) entry.write(buffer);
        }

        public static Snapshot read(RegistryFriendlyByteBuf buffer) {
            int emeralds = buffer.readVarInt();
            int items = buffer.readVarInt();
            int size = buffer.readVarInt();
            List<Entry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) entries.add(Entry.read(buffer));
            return new Snapshot(entries, emeralds, items);
        }
    }

    public static void record(NpcEntity tradesman, long dayTime, Item item, int count, int emeralds,
                              boolean sellBox, int percent) {
        CompoundTag ledger = ledger(tradesman);
        ListTag entries = ledger.getList(ENTRIES, Tag.TAG_COMPOUND);
        CompoundTag entry = new CompoundTag();
        // On the village calendar, so the screen's "day N" keeps counting when
        // nights are skipped with /time set day.
        entry.putLong("t", com.alex.test.village.VillageDays.villageTime(dayTime));
        entry.putString("i", BuiltInRegistries.ITEM.getKey(item).toString());
        entry.putInt("c", count);
        entry.putInt("e", emeralds);
        if (sellBox) entry.putBoolean("b", true);
        if (percent != 100) entry.putInt("p", percent);
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        ledger.put(ENTRIES, entries);
        ledger.putInt(TOTAL_EMERALDS, ledger.getInt(TOTAL_EMERALDS) + emeralds);
        ledger.putInt(TOTAL_ITEMS, ledger.getInt(TOTAL_ITEMS) + count);
    }

    public static Snapshot snapshot(NpcEntity tradesman) {
        CompoundTag ledger = tradesman.getBrainData().getCompound(TAG);
        ListTag entries = ledger.getList(ENTRIES, Tag.TAG_COMPOUND);
        List<Entry> newestFirst = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && newestFirst.size() < MAX_SENT; i--) {
            CompoundTag entry = entries.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(entry.getString("i"));
            if (id == null) continue;
            newestFirst.add(new Entry(entry.getLong("t"), id, entry.getInt("c"), entry.getInt("e"),
                    entry.getBoolean("b"), entry.contains("p") ? entry.getInt("p") : 100));
        }
        return new Snapshot(newestFirst, ledger.getInt(TOTAL_EMERALDS), ledger.getInt(TOTAL_ITEMS));
    }

    private static CompoundTag ledger(NpcEntity tradesman) {
        CompoundTag brain = tradesman.getBrainData();
        if (!brain.contains(TAG, Tag.TAG_COMPOUND)) brain.put(TAG, new CompoundTag());
        return brain.getCompound(TAG);
    }
}
