package com.alex.test.village.building;

import com.alex.test.ui.StorageRoomMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.claims.ClaimLock;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Finds the registered room behind a storage token or one of its containers. */
public final class StorageRoomAccess {
    private StorageRoomAccess() {}

    private static List<StorageBuilding> rooms(ServerLevel level) {
        List<StorageBuilding> result = new ArrayList<>();
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() != level) continue;
            for (Building building : village.getBuildings()) {
                if (building instanceof StorageBuilding storage && storage.isValid()) result.add(storage);
            }
        }
        if (VillageManager.INSTANCE.getLevel() == level) {
            for (Building building : VillageManager.INSTANCE.getBuildings()) {
                if (building instanceof StorageBuilding storage && storage.isValid()) result.add(storage);
            }
        }
        return result;
    }

    /** The village a registered storage room belongs to. */
    @Nullable public static VillageManager villageOf(ServerLevel level, StorageBuilding storage) {
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() == level && village.getBuildings().contains(storage)) return village;
        }
        return VillageManager.INSTANCE.getLevel() == level
                && VillageManager.INSTANCE.getBuildings().contains(storage) ? VillageManager.INSTANCE : null;
    }

    @Nullable public static StorageBuilding forContainer(ServerLevel level, BlockPos pos) {
        if (!StorageContainers.isStorageBlock(level.getBlockState(pos))) return null;
        for (StorageBuilding storage : rooms(level)) {
            if (storage.getFunctionalBlocks().contains(pos)) return storage;
        }
        return null;
    }

    @Nullable public static StorageBuilding forFrame(ServerLevel level, ItemFrame frame) {
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                != ItemFrameTokenRegistry.TokenType.STORAGE) return null;
        for (StorageBuilding storage : rooms(level)) {
            if (storage.getItemFrameId().equals(frame.getUUID())) return storage;
        }
        return null;
    }

    public static boolean openFromFrame(ServerPlayer player, ItemFrame frame) {
        if (!(player.level() instanceof ServerLevel level) || player.distanceToSqr(frame) >= 64.0) return false;
        StorageBuilding storage = forFrame(level, frame);
        if (storage == null) return false;
        VillageManager village = villageOf(level, storage);
        if (!ClaimLock.mayIn(player, village, ClaimLock.Act.USE)) {
            ClaimLock.tellWindowRefused(player, village);
            return true;
        }
        // Only valid rooms get here; heal a glint an earlier bug stripped.
        ItemFrameTokenRegistry.setRegisteredGlint(frame, true);
        open(player, storage, null);
        return true;
    }

    public static boolean canOpenFromContainer(ServerPlayer player, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)
                || !isViewing(player, level, pos)
                || player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) >= 64.0) {
            return false;
        }
        StorageBuilding storage = forContainer(level, pos);
        // The chest-screen button: only for people who may open the village's chests (village claims round 6).
        return storage != null && ClaimLock.mayIn(player, villageOf(level, storage), ClaimLock.Act.USE);
    }

    /**
     * The player has this container's screen open. Vanilla chests and barrels use a
     * {@link ChestMenu}; modded chests (Iron Chests) have their own menu, whose slots
     * hold the chest's block entity directly.
     */
    private static boolean isViewing(ServerPlayer player, ServerLevel level, BlockPos pos) {
        if (player.containerMenu instanceof ChestMenu) return true;
        // Sophisticated Storage: its menu knows the block it was opened from (either half
        // of a double chest), and its slots are not vanilla container slots.
        BlockPos opened = SophisticatedCompat.menuPos(player.containerMenu);
        if (opened != null) return opened.equals(pos) || opened.equals(StorageContainers.pairedChestPos(level, pos));
        if (player.containerMenu == player.inventoryMenu
                || !(level.getBlockEntity(pos) instanceof Container container)) return false;
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container == container) return true;
        }
        return false;
    }

    public static boolean openFromContainer(ServerPlayer player, BlockPos pos) {
        if (!canOpenFromContainer(player, pos)) return false;
        StorageBuilding storage = forContainer((ServerLevel) player.level(), pos);
        if (storage == null) return false;
        open(player, storage, storage.primaryContainerPos((ServerLevel) player.level(), pos));
        return true;
    }

    private static void open(ServerPlayer player, StorageBuilding storage, @Nullable BlockPos selected) {
        ServerLevel level = (ServerLevel) player.level();
        player.openMenu(new SimpleMenuProvider(
                (id, inventory, ignored) -> new StorageRoomMenu(id, inventory, storage, level, selected),
                Component.literal("Manage Storage Room")),
                buffer -> new StorageRoomMenu(0, player.getInventory(), storage, level, selected)
                        .writeOpeningData(buffer));
    }
}
