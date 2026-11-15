package com.alex.test.village.raid;

import com.alex.test.ModItems;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Raid trophies: which one a raid earns, where it is put, and which ones hang
 * in the Town Hall.
 */
final class RaidTrophies {

    private RaidTrophies() {}

    /** The trophy for beating this kind of raid. */
    static Item itemFor(RaidFaction faction) {
        return switch (faction) {
            case WARBAND -> ModItems.WARBAND_TROPHY.get();
            case HORDE -> ModItems.HORDE_TROPHY.get();
            case RIDERS -> ModItems.RIDER_TROPHY.get();
            case KIDNAPPERS -> ModItems.RESCUE_TROPHY.get();
            case CHALLENGE -> ModItems.OMEN_TROPHY.get();
        };
    }

    /**
     * Puts a new trophy in the village's storage (the first container with
     * room), or, with no room, at the Town Hall door where it never vanishes.
     * Returns where it went, or null when the village has neither.
     */
    @Nullable
    static BlockPos give(VillageManager village, ServerLevel level, RaidFaction faction) {
        return deliver(village, level, List.of(new ItemStack(itemFor(faction))));
    }

    /**
     * Puts each stack in the first storage container with room for all of it;
     * whatever fits nowhere drops at the Town Hall door, where it never
     * vanishes. Returns where the first stack went, or null when the village
     * has no room and no Town Hall.
     */
    @Nullable
    static BlockPos deliver(VillageManager village, ServerLevel level, List<ItemStack> stacks) {
        BlockPos first = null;
        for (ItemStack stack : stacks) {
            BlockPos at = placeInStorage(village, level, stack);
            if (at == null) at = dropAtTownHall(village, level, stack);
            if (first == null) first = at;
        }
        return first;
    }

    /**
     * Drops {@code stack} where {@code at} fell, never despawning; or, when it
     * fell into lava or out of the world (where the item would be lost), into
     * the village's storage instead.
     */
    static void dropOrDeliver(VillageManager village, ServerLevel level, net.minecraft.world.entity.Entity at, ItemStack stack) {
        if (at.isInLava() || at.getY() < level.getMinBuildHeight() + 1) {
            deliver(village, level, List.of(stack));
            return;
        }
        ItemEntity item = at.spawnAtLocation(stack, 0.5F);
        if (item != null) item.setUnlimitedLifetime();
    }

    @Nullable
    private static BlockPos placeInStorage(VillageManager village, ServerLevel level, ItemStack stack) {
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos pos : storage.getFunctionalBlocks()) {
                StorageInventory inventory = com.alex.test.village.building.StorageContainers.inventory(level, pos);
                if (inventory == null) continue;
                if (inventory.insert(stack.copy(), true).isEmpty()) {
                    inventory.insert(stack.copy(), false);
                    return pos.immutable();
                }
            }
        }
        return null;
    }

    @Nullable
    private static BlockPos dropAtTownHall(VillageManager village, ServerLevel level, ItemStack stack) {
        TownHallBuilding hall = townHall(village);
        if (hall == null) return null;
        BlockPos door = hall.getDoorPos();
        ItemEntity item = new ItemEntity(level, door.getX() + 0.5, door.getY() + 0.5, door.getZ() + 0.5, stack.copy());
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
        return door;
    }

    /** Which kinds of trophy hang in item frames inside the Town Hall right now. */
    static Set<RaidFaction> hungInTownHall(VillageManager village, ServerLevel level) {
        Set<RaidFaction> hung = EnumSet.noneOf(RaidFaction.class);
        TownHallBuilding hall = townHall(village);
        if (hall == null) return hung;
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, hall.roomBox().inflate(1.5, 1.0, 1.5))) {
            if (frame.getItem().getItem() instanceof RaidTrophyItem trophy) hung.add(trophy.faction());
        }
        return hung;
    }

    @Nullable
    private static TownHallBuilding townHall(VillageManager village) {
        for (Building building : village.getBuildings()) {
            if (building instanceof TownHallBuilding hall && hall.isValid()) return hall;
        }
        return null;
    }
}
