package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageContainers;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.building.StorageRoomAccess;
import com.alex.test.network.StorageRoomPayloads;
import com.alex.test.village.economy.StockOrderStatus;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-backed schematic of one registered storage room. No inventory slots are transferred. */
public final class StorageRoomMenu extends AbstractContainerMenu implements NpcUiMenu {
    /**
     * One selectable container. {@code framed}: items in item frames on it, which decide over
     * everything else; {@code chosen}: items picked in this screen, which decide over the
     * categories; {@code filters}: the categories, never empty.
     */
    public record ContainerMarker(BlockPos pos, @Nullable BlockPos partner, boolean barrel,
                                  Set<StorageBuilding.DepositFilter> filters, int items, int slots,
                                  List<Item> framed, List<Item> chosen, boolean locked) {
        public ContainerMarker {
            filters = java.util.Collections.unmodifiableSet(StorageBuilding.DepositFilter.normalized(filters));
        }

        ContainerMarker with(Set<StorageBuilding.DepositFilter> filters, List<Item> chosen, boolean locked) {
            return new ContainerMarker(pos, partner, barrel, filters, items, slots, framed, chosen, locked);
        }
    }

    /** A dish the stock limit can keep ingredients for, straight from the cook's recipes. */
    public record Dish(Item result, int outputPerBatch, Map<Item, Integer> inputsPerBatch) {}

    private static final int MAX_VILLAGE_ITEMS = 1024;

    @Nullable private final StorageBuilding storage;
    /** The Mayor that opened this screen from its building overview; null when opened in the room. */
    @Nullable private final MayorEntity mayor;
    /** Entity id of that Mayor on both sides, or -1. */
    private final int mayorId;
    private final UUID frameId;
    private final BlockPos doorPos;
    private final List<BlockPos> floor;
    private final List<ContainerMarker> containers;
    @Nullable private final BlockPos table;
    @Nullable private final BlockPos furnace;
    @Nullable private final BlockPos initialSelection;
    /** Every item kind in the village's storage rooms, for the item picker. */
    private final List<Item> villageItems;
    private final List<Dish> dishes;
    /** The Stock tab's rows, finished orders and stored totals; refreshed while the tab is open. */
    private StorageRoomPayloads.StockStatus stockStatus;
    /** The Stock tab's Enchanter page; empty until the first refresh brings it. */
    private com.alex.test.network.BookOrderPayloads.Status bookStatus;
    /** Village claims round 7b: below Officer the chests' filters, chosen items and locks are look only. */
    private final com.alex.test.village.claims.RoomRights rights;

    public StorageRoomMenu(int id, Inventory inventory, StorageBuilding storage,
                           ServerLevel level, @Nullable BlockPos selected) {
        this(id, inventory, storage, level, selected, null);
    }

    public StorageRoomMenu(int id, Inventory inventory, StorageBuilding storage,
                           ServerLevel level, @Nullable BlockPos selected, @Nullable MayorEntity mayor) {
        super(DynamicVillages.STORAGE_ROOM_MENU.get(), id);
        this.storage = storage;
        this.mayor = mayor;
        this.mayorId = mayor == null ? -1 : mayor.getId();
        this.frameId = storage.getItemFrameId();
        this.doorPos = storage.getDoorPos();
        this.table = storage.getCraftingTablePos();
        this.furnace = storage.getFurnacePos();
        // Spots standing on furniture are walkable but are not floor; the schematic drew
        // them as grey boxes sitting on the chest lids.
        List<BlockPos> tiles = new ArrayList<>();
        for (BlockPos tile : storage.getNavMeshBlocks()) {
            BlockPos below = tile.below();
            if (storage.getFunctionalBlocks().contains(below) || below.equals(table) || below.equals(furnace)
                    || level.getBlockEntity(below) instanceof Container
                    || StorageContainers.isStorageBlock(level.getBlockState(below))
                    || level.getBlockState(below).is(Blocks.CRAFTING_TABLE)) continue;
            tiles.add(tile);
        }
        this.floor = List.copyOf(tiles);
        this.containers = new ArrayList<>();
        Set<BlockPos> seen = new HashSet<>();
        for (BlockPos pos : storage.getFunctionalBlocks()) {
            BlockPos primary = storage.primaryContainerPos(level, pos);
            if (!seen.add(primary) || StorageContainers.inventory(level, primary) == null) continue;
            BlockPos partner = StorageBuilding.pairedChestPos(level, primary);
            if (partner != null && !storage.getFunctionalBlocks().contains(partner)) partner = null;
            // A Sophisticated double chest shows the whole chest from either half: count it once.
            int items = 0;
            int slots = 0;
            for (StorageInventory container : StorageBuilding.inventories(level,
                    partner == null ? List.of(primary) : List.of(primary, partner))) {
                slots += container.size();
                for (int slot = 0; slot < container.size(); slot++) {
                    items += container.peek(slot).getCount();
                }
            }
            containers.add(new ContainerMarker(primary, partner,
                    StorageContainers.isBarrel(level.getBlockState(primary)),
                    storage.depositFilters(level, primary), items, slots,
                    List.copyOf(storage.frameFilter(level, primary)),
                    storage.chosenItems(level, primary),
                    storage.isLocked(level, primary)));
        }
        this.initialSelection = selected == null ? null : storage.primaryContainerPos(level, selected);

        VillageManager village = StorageRoomAccess.villageOf(level, storage);
        List<StorageBuilding> rooms = new ArrayList<>();
        if (village != null) {
            for (Building building : village.getBuildings()) {
                if (building instanceof StorageBuilding room && room.isValid()) rooms.add(room);
            }
        } else {
            rooms.add(storage);
        }
        Set<Item> found = new LinkedHashSet<>();
        for (StorageBuilding room : rooms) {
            for (StorageInventory container : room.inventories(level)) {
                for (int slot = 0; slot < container.size() && found.size() < MAX_VILLAGE_ITEMS; slot++) {
                    ItemStack stack = container.peek(slot);
                    if (!stack.isEmpty()) found.add(stack.getItem());
                }
            }
        }
        // Picked items stay listed even once the last of them has left storage.
        for (ContainerMarker marker : containers) found.addAll(marker.chosen());
        this.villageItems = List.copyOf(found);

        this.dishes = new ArrayList<>();
        for (CookActivity.Recipe recipe : CookActivity.recipes()) {
            dishes.add(new Dish(recipe.result(), recipe.outputPerBatch(), recipe.inputsPerBatch()));
        }
        this.stockStatus = StockOrderStatus.build(level, village, frameId);
        this.rights = com.alex.test.village.claims.RoomRights.of(inventory.player, village,
                com.alex.test.village.claims.ClaimLock.Act.MANAGE);
    }

    public StorageRoomMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.STORAGE_ROOM_MENU.get(), id);
        this.storage = null;
        this.mayor = null;
        this.frameId = buffer.readUUID();
        this.doorPos = buffer.readBlockPos();
        this.floor = buffer.readList(buf -> buf.readBlockPos());
        this.containers = new ArrayList<>(buffer.readList(buf -> {
            BlockPos pos = buf.readBlockPos();
            BlockPos partner = buf.readBoolean() ? buf.readBlockPos() : null;
            boolean barrel = buf.readBoolean();
            Set<StorageBuilding.DepositFilter> filters = StorageBuilding.DepositFilter.fromMask(buf.readVarInt());
            int items = buf.readVarInt();
            int slots = buf.readVarInt();
            List<Item> framed = readItems(buf);
            List<Item> chosen = readItems(buf);
            boolean locked = buf.readBoolean();
            return new ContainerMarker(pos, partner, barrel, filters, items, slots, framed, chosen, locked);
        }));
        this.table = buffer.readBoolean() ? buffer.readBlockPos() : null;
        this.furnace = buffer.readBoolean() ? buffer.readBlockPos() : null;
        this.initialSelection = buffer.readBoolean() ? buffer.readBlockPos() : null;
        this.villageItems = readItems(buffer);
        this.dishes = buffer.readList(buf -> {
            Item result = readItem(buf);
            int output = buf.readVarInt();
            Map<Item, Integer> inputs = new LinkedHashMap<>();
            int count = buf.readVarInt();
            for (int i = 0; i < count; i++) inputs.put(readItem(buf), buf.readVarInt());
            return new Dish(result, output, inputs);
        });
        this.stockStatus = StorageRoomPayloads.StockStatus.CODEC.decode(buffer);
        this.mayorId = buffer.readVarInt();
        this.rights = com.alex.test.village.claims.RoomRights.read(buffer);
    }

    public void writeOpeningData(RegistryFriendlyByteBuf buffer) {
        buffer.writeUUID(frameId);
        buffer.writeBlockPos(doorPos);
        buffer.writeCollection(floor, (buf, pos) -> buf.writeBlockPos(pos));
        buffer.writeCollection(containers, (buf, marker) -> {
            buf.writeBlockPos(marker.pos());
            buf.writeBoolean(marker.partner() != null);
            if (marker.partner() != null) buf.writeBlockPos(marker.partner());
            buf.writeBoolean(marker.barrel());
            buf.writeVarInt(StorageBuilding.DepositFilter.mask(marker.filters()));
            buf.writeVarInt(marker.items());
            buf.writeVarInt(marker.slots());
            writeItems(buf, marker.framed());
            writeItems(buf, marker.chosen());
            buf.writeBoolean(marker.locked());
        });
        buffer.writeBoolean(table != null);
        if (table != null) buffer.writeBlockPos(table);
        buffer.writeBoolean(furnace != null);
        if (furnace != null) buffer.writeBlockPos(furnace);
        buffer.writeBoolean(initialSelection != null);
        if (initialSelection != null) buffer.writeBlockPos(initialSelection);
        writeItems(buffer, villageItems);
        buffer.writeCollection(dishes, (buf, dish) -> {
            writeItem(buf, dish.result());
            buf.writeVarInt(dish.outputPerBatch());
            buf.writeVarInt(dish.inputsPerBatch().size());
            dish.inputsPerBatch().forEach((item, count) -> {
                writeItem(buf, item);
                buf.writeVarInt(count);
            });
        });
        StorageRoomPayloads.StockStatus.CODEC.encode(buffer, stockStatus);
        buffer.writeVarInt(mayorId);
        rights.write(buffer);
    }

    private static List<Item> readItems(FriendlyByteBuf buf) {
        return buf.readList(StorageRoomMenu::readItem);
    }

    private static void writeItems(FriendlyByteBuf buf, List<Item> items) {
        buf.writeCollection(items, StorageRoomMenu::writeItem);
    }

    private static Item readItem(FriendlyByteBuf buf) {
        return BuiltInRegistries.ITEM.get(buf.readResourceLocation());
    }

    private static void writeItem(FriendlyByteBuf buf, Item item) {
        buf.writeResourceLocation(BuiltInRegistries.ITEM.getKey(item));
    }

    public UUID frameId() { return frameId; }
    public BlockPos doorPos() { return doorPos; }
    public List<BlockPos> floor() { return floor; }
    public List<ContainerMarker> containers() { return List.copyOf(containers); }
    @Nullable public BlockPos table() { return table; }
    @Nullable public BlockPos furnace() { return furnace; }
    @Nullable public BlockPos initialSelection() { return initialSelection; }
    @Nullable public StorageBuilding serverStorage() { return storage; }
    public List<Item> villageItems() { return villageItems; }
    public List<Dish> dishes() { return dishes; }
    public StorageRoomPayloads.StockStatus stockStatus() { return stockStatus; }

    /** Stored amount of {@code item} across the village, as of the last status. */
    public int total(Item item) {
        return stockStatus.totals().getOrDefault(BuiltInRegistries.ITEM.getKey(item), 0);
    }

    /** A fresh status from the server, for this screen only. */
    public void setStockStatus(StorageRoomPayloads.StockStatus status) {
        if (status.frameId().equals(frameId)) stockStatus = status;
    }

    public com.alex.test.network.BookOrderPayloads.Status bookStatus() {
        if (bookStatus == null) bookStatus = com.alex.test.network.BookOrderPayloads.Status.empty(frameId);
        return bookStatus;
    }

    /** A fresh Enchanter page from the server, for this screen only. */
    public void setBookStatus(com.alex.test.network.BookOrderPayloads.Status status) {
        if (status.frameId().equals(frameId)) bookStatus = status;
    }

    /** The client's copy after a local edit; the server checks the lock itself. */
    public void setFiltersLocally(BlockPos pos, Set<StorageBuilding.DepositFilter> filters) {
        update(pos, marker -> marker.locked() ? marker : marker.with(filters, marker.chosen(), false));
    }

    /** One category switched on or off, the same way the server does it. */
    public void toggleFilterLocally(BlockPos pos, StorageBuilding.DepositFilter filter, boolean on) {
        update(pos, marker -> marker.locked() ? marker : marker.with(
                StorageBuilding.DepositFilter.toggled(marker.filters(), filter, on), marker.chosen(), false));
    }

    public void setChosenLocally(BlockPos pos, List<Item> chosen) {
        update(pos, marker -> marker.locked() ? marker : marker.with(marker.filters(), List.copyOf(chosen), false));
    }

    public void setLockedLocally(BlockPos pos, boolean locked) {
        update(pos, marker -> marker.with(marker.filters(), marker.chosen(), locked));
    }

    /** The client's copy after a row edit; the server's next status replaces it. */
    public void editStockRowLocally(UUID row, int count, boolean once, boolean forPlayer) {
        List<StorageRoomPayloads.StockRowView> rows = new ArrayList<>(stockStatus.rows());
        rows.replaceAll(view -> view.id().equals(row) ? view.edited(count, once, forPlayer) : view);
        replaceRows(rows);
    }

    public void removeStockRowLocally(UUID row) {
        List<StorageRoomPayloads.StockRowView> rows = new ArrayList<>(stockStatus.rows());
        rows.removeIf(view -> view.id().equals(row));
        replaceRows(rows);
    }

    private void replaceRows(List<StorageRoomPayloads.StockRowView> rows) {
        stockStatus = new StorageRoomPayloads.StockStatus(frameId, rows, stockStatus.history(),
                stockStatus.blocked(), stockStatus.totals());
    }

    private void update(BlockPos pos, java.util.function.UnaryOperator<ContainerMarker> change) {
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).pos().equals(pos)) {
                containers.set(i, change.apply(containers.get(i)));
                return;
            }
        }
    }

    /** The Mayor to go back to, or -1 when the room itself opened this screen. */
    public int mayorId() { return mayorId; }
    /** Village claims round 7b: false = filters, chosen items and locks look only (the owner's name is in it). */
    public com.alex.test.village.claims.RoomRights rights() { return rights; }

    @Override public boolean stillValid(Player player) {
        if (storage == null) return true;
        if (!storage.isValid() || !storage.getItemFrameId().equals(frameId)) return false;
        // Village claims round 6: a player the owner no longer trusts loses the window (it closes).
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                && !com.alex.test.village.claims.ClaimLock.mayIn(serverPlayer,
                        StorageRoomAccess.villageOf(serverPlayer.serverLevel(), storage),
                        com.alex.test.village.claims.ClaimLock.Act.USE)) return false;
        // Opened from the Mayor: the player stands at the Mayor, not in the room.
        if (mayor != null) return mayor.isAlive() && player.distanceToSqr(mayor) < 64.0;
        return player.distanceToSqr(doorPos.getX() + 0.5, doorPos.getY() + 0.5,
                        doorPos.getZ() + 0.5) < 1024.0;
    }

    /** Keeps the Mayor attentive while the player manages storage through it. */
    @Override public boolean isForNpc(NpcEntity npc) {
        return mayor != null && mayor == npc;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
}
