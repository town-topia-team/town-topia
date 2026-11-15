package com.alex.test.village.villagers.builder.project;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Blocks players gave the Builder by hand ({@code BuildOrders.giveFromHand}), saved with the world
 * per village, giver and item. They stay the player's: never put in storage, sold or used by
 * another villager. The Builder places them before fetching from storage, the orderer's first
 * ({@code BuildSiteWork.place}); the player takes the rest back on the desk's Projects tab.
 */
public final class GivenBlocks extends SavedData {
    private static final String DATA_NAME = "dynamicvillages_given_blocks";
    private static final SavedData.Factory<GivenBlocks> FACTORY =
            new SavedData.Factory<>(GivenBlocks::new, GivenBlocks::load, null);

    /** One giver's blocks of one item for one village. */
    public static final class Pile {
        public final UUID village, giver;
        public final Item item;
        public int count;

        Pile(UUID village, UUID giver, Item item, int count) {
            this.village = village;
            this.giver = giver;
            this.item = item;
            this.count = count;
        }
    }

    private final List<Pile> piles = new ArrayList<>();

    public static GivenBlocks get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    /** Everyone's given {@code item} for this village. */
    public int count(UUID village, Item item) {
        int total = 0;
        for (Pile pile : piles) {
            if (pile.village.equals(village) && pile.item == item) total += pile.count;
        }
        return total;
    }

    /** Everyone's given blocks for this village, by item. */
    public java.util.Map<Item, Integer> byVillage(UUID village) {
        java.util.Map<Item, Integer> out = new java.util.LinkedHashMap<>();
        for (Pile pile : piles) {
            if (pile.village.equals(village) && pile.count > 0) out.merge(pile.item, pile.count, Integer::sum);
        }
        return out;
    }

    /** This giver's {@code item} for this village. */
    public int count(UUID village, UUID giver, Item item) {
        Pile pile = pile(village, giver, item);
        return pile == null ? 0 : pile.count;
    }

    /** Everyone who has blocks given to this village, {@code first} first when it has any. */
    public List<UUID> givers(UUID village, @Nullable UUID first) {
        List<UUID> out = new ArrayList<>();
        for (Pile pile : piles) {
            if (pile.village.equals(village) && pile.count > 0 && !out.contains(pile.giver)) out.add(pile.giver);
        }
        if (first != null && out.remove(first)) out.add(0, first);
        return out;
    }

    /** What this giver has given this village, oldest pile first. */
    public List<Pile> byGiver(UUID village, UUID giver) {
        List<Pile> out = new ArrayList<>();
        for (Pile pile : piles) {
            if (pile.village.equals(village) && pile.giver.equals(giver)) out.add(pile);
        }
        return out;
    }

    public void add(UUID village, UUID giver, Item item, int amount) {
        if (amount <= 0) return;
        Pile pile = pile(village, giver, item);
        if (pile == null) piles.add(new Pile(village, giver, item, amount));
        else pile.count += amount;
        setDirty();
    }

    /** The Builder places some: up to {@code amount}, {@code first}'s pile first. How many it got. */
    public int take(UUID village, @Nullable UUID first, Item item, int amount) {
        int got = first == null ? 0 : takeBack(village, first, item, amount);
        for (int i = 0; i < piles.size() && got < amount; i++) {
            Pile pile = piles.get(i);
            if (!pile.village.equals(village) || pile.item != item) continue;
            int used = Math.min(amount - got, pile.count);
            pile.count -= used;
            got += used;
            if (pile.count <= 0) piles.remove(i--);
        }
        if (got > 0) setDirty();
        return got;
    }

    /** Takes up to {@code amount} from this giver's own pile only. How many it got. */
    public int takeBack(UUID village, UUID giver, Item item, int amount) {
        Pile pile = pile(village, giver, item);
        if (pile == null || amount <= 0) return 0;
        int got = Math.min(amount, pile.count);
        pile.count -= got;
        if (pile.count <= 0) piles.remove(pile);
        setDirty();
        return got;
    }

    @Nullable
    private Pile pile(UUID village, UUID giver, Item item) {
        for (Pile pile : piles) {
            if (pile.village.equals(village) && pile.giver.equals(giver) && pile.item == item) return pile;
        }
        return null;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Pile pile : piles) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("village", pile.village);
            entry.putUUID("giver", pile.giver);
            entry.putString("item", BuiltInRegistries.ITEM.getKey(pile.item).toString());
            entry.putInt("count", pile.count);
            list.add(entry);
        }
        tag.put("piles", list);
        return tag;
    }

    private static GivenBlocks load(CompoundTag tag, HolderLookup.Provider registries) {
        GivenBlocks data = new GivenBlocks();
        ListTag list = tag.getList("piles", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation key = ResourceLocation.tryParse(entry.getString("item"));
            // An item from a mod that is gone is left out (the save keeps nothing else of it either).
            Item item = key == null ? null : BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            if (item == null || !entry.hasUUID("village") || !entry.hasUUID("giver") || entry.getInt("count") <= 0) continue;
            data.piles.add(new Pile(entry.getUUID("village"), entry.getUUID("giver"), item, entry.getInt("count")));
        }
        return data;
    }
}
