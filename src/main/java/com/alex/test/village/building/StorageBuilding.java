package com.alex.test.village.building;

import com.alex.test.Tr;
import com.alex.test.DynamicVillages;
import com.alex.test.LegacyModId;
import com.mojang.logging.LogUtils;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ArmorItem;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Storage building: created by placing a Storage item in an item frame near a door.
 *
 * <p>Chests, barrels and crafting tables are optional features. When present, this class
 * provides methods to query, deposit, withdraw and craft with them.
 *
 * <h3>ResourceType categories</h3>
 * <ul>
 *   <li>{@link ResourceType#FOOD}  — items with a {@code FoodProperties} data component</li>
 *   <li>{@link ResourceType#WOOD}  — items tagged {@code minecraft:logs} or {@code minecraft:planks}</li>
 *   <li>{@link ResourceType#SEEDS} — items tagged {@code minecraft:seeds}</li>
 *   <li>{@link ResourceType#AXE}   — any axe item</li>
 *   <li>{@link ResourceType#ANY}   — any non-empty item stack</li>
 * </ul>
 */
public final class StorageBuilding extends Building {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Traditional recommended chest count, retained for feature reporting. */
    public static final int REQUIRED_CHESTS          = 2;
    /** Traditional recommended crafting-table count, retained for feature reporting. */
    public static final int REQUIRED_CRAFTING_TABLES = 1;

    /**
     * How long the chest-content index may be reused before a cheap refresh.
     *
     * <p>This prevents scanning every tick, while still recovering quickly if a
     * player manually changes a chest and no villager-side deposit/withdraw method
     * was called to invalidate the index.
     */
    private static final long CONTENT_INDEX_TTL_TICKS = 20L;
    private static long nextToolInventoryChangeId = 1L;

    /** Resource type -> chest positions currently known to contain that resource. */
    private final EnumMap<ResourceType, Set<BlockPos>> resourceIndex = new EnumMap<>(ResourceType.class);

    /** Item -> chest positions currently known to contain that item. Exact components are verified before use. */
    private final Map<Item, Set<BlockPos>> itemIndex = new HashMap<>();

    private long contentIndexGameTime = Long.MIN_VALUE;
    private boolean contentIndexDirty = true;
    private long toolInventoryChangeId;
    private int indexedToolContentsHash;

    // =========================================================================
    // Resource type enum
    // =========================================================================

    public enum ResourceType {
        /** Edible items (have a FoodProperties data component). */
        FOOD,
        /** Log or plank items. */
        WOOD,
        /** Seed items. */
        SEEDS,
        /** Any axe item. */
        AXE,
        /** Harvested crop produce a cook can process (wheat, carrots, ...). */
        CROP,
        /** Uncooked meat from butchered livestock. */
        RAW_MEAT,
        /** Furnace fuel — coal or wood. */
        FUEL,
        /** Cobblestone and stone the blacksmith crafts with. */
        STONE,
        /** Iron in any pre-tool form (raw ore or ingots). */
        IRON_MATERIAL,
        /** Any non-empty item. */
        ANY
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    /** Positions of crafting tables found inside the room. Cleared and repopulated each scan. */
    private final Set<BlockPos> craftingTableBlocks = new LinkedHashSet<>();
    /**
     * Optional furnaces: not needed for the room to register, but a miner
     * burns logs into charcoal here for its torches.
     */
    private final Set<BlockPos> furnaceBlocks = new LinkedHashSet<>();
    /** Optional stonecutters: not needed for the room to register; the Builder may cut blocks here. */
    private final Set<BlockPos> stonecutterBlocks = new LinkedHashSet<>();
    /**
     * Optional composters: farmers compost spare seeds and scraps here as
     * well as in composters beside their fields.
     */
    private final Set<BlockPos> composterBlocks = new LinkedHashSet<>();

    public StorageBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                           Direction interiorDirection,
                           Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
        for (ResourceType type : ResourceType.values()) {
            resourceIndex.put(type, new HashSet<>());
        }
    }

    // =========================================================================
    // Functional block scan
    // =========================================================================

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        invalidateContentIndex();
        frameFiltersStale = true;
        craftingTableBlocks.clear();
        furnaceBlocks.clear();
        stonecutterBlocks.clear();
        composterBlocks.clear();

        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 12; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    // Only count furniture that can be used from this room's own
                    // nav tiles. This prevents stacked rooms with overlapping X/Z
                    // from stealing each other's chests/crafting tables.
                    if (StorageContainers.isStorageContainer(level, pos)
                            && isStorageContainerInRoom(level, pos) && canUseChest(level, pos)) {
                        functionalBlocks.add(pos);
                        // A double chest is one container: when only one half faces the room,
                        // the other half is storage too.
                        BlockPos partner = pairedChestPos(level, pos);
                        if (partner != null && canUseChest(level, partner)) functionalBlocks.add(partner);
                    } else if (!isFunctionalBlockAccessibleFromRoom(pos)) {
                        continue;
                    } else if (state.is(Blocks.CRAFTING_TABLE)) {
                        craftingTableBlocks.add(pos);
                    } else if (state.is(Blocks.FURNACE)) {
                        // Only a plain furnace: smokers and blast furnaces
                        // cannot burn logs into charcoal.
                        furnaceBlocks.add(pos);
                    } else if (state.is(Blocks.STONECUTTER)) {
                        stonecutterBlocks.add(pos);
                    } else if (state.is(Blocks.COMPOSTER)) {
                        composterBlocks.add(pos);
                    }
                }
            }
        }
        // Record each container's settings now, so an in-place upgrade later can be spotted.
        pruneCarriedSettings(level);
        for (BlockPos pos : functionalBlocks) settingsEntity(level, pos);
        LOGGER.debug("[StorageBuilding] scanned {} container(s), {} crafting table(s) near door {}.",
                functionalBlocks.size(), craftingTableBlocks.size(), doorPos);
        return functionalBlocks.size() >= REQUIRED_CHESTS
                && craftingTableBlocks.size() >= REQUIRED_CRAFTING_TABLES;
    }

    private boolean canUseChest(ServerLevel level, BlockPos pos) {
        var chest = level.getBlockEntity(pos);
        if (chest == null) return false;
        var frame = ItemFrameTokenRegistry.findExactFrameAt(level, itemFramePos);
        ItemStack token = frame != null && frame.getUUID().equals(itemFrameId)
                ? frame.getItem() : ItemStack.EMPTY;
        return VillageTokenBinding.canUseChest(chest, token);
    }

    /** {@link #owner} as of {@link #ownerGameTime}. */
    private VillageTokenBinding cachedOwner;
    private long ownerGameTime = Long.MIN_VALUE;

    /**
     * Identity comes from the registered frame, never from chest contents.
     *
     * <p>Asked once per chest slot by every scan, and each answer is an entity
     * search around the frame plus a copy of its item's data. Kept for the
     * rest of the game tick: the frame is not touched mid-tick by anything
     * that would change the answer.
     */
    public VillageTokenBinding owner(ServerLevel level) {
        long now = level.getGameTime();
        if (now != ownerGameTime) {
            var frame = ItemFrameTokenRegistry.findExactFrameAt(level, itemFramePos);
            cachedOwner = frame != null && frame.getUUID().equals(itemFrameId)
                    ? VillageTokenBinding.read(frame.getItem()) : null;
            ownerGameTime = now;
        }
        return cachedOwner;
    }

    public boolean mayUse(ServerLevel level, ItemStack stack) {
        return VillageItemOwnership.mayUse(stack, owner(level));
    }

    /** Cooking, crafting and the like: a player's food does not count. */
    public boolean mayUseAsMaterial(ServerLevel level, ItemStack stack) {
        return VillageItemOwnership.mayUseAsMaterial(stack, owner(level));
    }

    // =========================================================================
    // Cached query/index
    // =========================================================================

    /** Mark the chest-content index as stale after any known inventory change. */
    public void invalidateContentIndex() {
        contentIndexDirty = true;
    }

    /** Rebuild the per-chest content index immediately. */
    public void refreshContentIndex(ServerLevel level) {
        for (Set<BlockPos> positions : resourceIndex.values()) positions.clear();
        itemIndex.clear();
        int toolContentsHash = 1;

        for (Held held : distinct(level, functionalBlocks)) {
            BlockPos pos = held.pos();
            StorageInventory chest = held.inventory();

            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (!mayUse(level, stack)) continue;
                if (ToolSelectionHelper.isProfessionTool(stack)) {
                    toolContentsHash = 31 * toolContentsHash
                            + stack.getItem().hashCode() + stack.getCount();
                }

                itemIndex.computeIfAbsent(stack.getItem(), ignored -> new HashSet<>()).add(pos);
                for (ResourceType type : ResourceType.values()) {
                    if (matchesType(stack, type)) {
                        resourceIndex.get(type).add(pos);
                    }
                }
            }
        }

        contentIndexDirty = false;
        contentIndexGameTime = level.getGameTime();
        if (toolContentsHash != indexedToolContentsHash) {
            indexedToolContentsHash = toolContentsHash;
            markToolInventoryChanged();
        }
    }

    /**
     * Whether the index was rebuilt this very tick. A miss right after a rebuild
     * would rebuild it again to the same answer: with no food in the village,
     * every hungry villager's check rescanned every chest twice.
     */
    private boolean indexedThisTick(ServerLevel level) {
        return !contentIndexDirty && contentIndexGameTime == level.getGameTime();
    }

    private void ensureContentIndex(ServerLevel level) {
        long now = level.getGameTime();
        if (contentIndexDirty || now - contentIndexGameTime >= CONTENT_INDEX_TTL_TICKS) {
            refreshContentIndex(level);
        }
    }

    public long getToolInventoryChangeId(ServerLevel level) {
        ensureContentIndex(level);
        return toolInventoryChangeId;
    }

    /**
     * Returns cached chest positions that contain {@code type}.
     *
     * <p>If the cache says there are no matches, it refreshes once immediately.
     * That avoids a long false-negative when a player recently added the item.
     */
    public List<BlockPos> findChestsWithResource(ServerLevel level, ResourceType type) {
        ensureContentIndex(level);
        Set<BlockPos> indexed = resourceIndex.getOrDefault(type, Collections.emptySet());
        if (indexed.isEmpty() && !indexedThisTick(level)) {
            refreshContentIndex(level);
            indexed = resourceIndex.getOrDefault(type, Collections.emptySet());
        }
        return new ArrayList<>(indexed);
    }

    /**
     * Returns cached candidate chest positions for an exact item stack.
     *
     * <p>The index is by {@link Item} for speed, then each candidate is verified
     * with {@link ItemStack#isSameItemSameComponents(ItemStack, ItemStack)} before
     * being returned. This keeps component-sensitive items safe without scanning
     * every chest every tick.
     */
    public List<BlockPos> findChestsWithItem(ServerLevel level, ItemStack wanted) {
        if (wanted.isEmpty()) return List.of();
        if (VillageTokenBinding.isBound(wanted)
                && !java.util.Objects.equals(owner(level), VillageTokenBinding.read(wanted)))
            return List.of();

        ensureContentIndex(level);
        List<BlockPos> result = exactItemCandidates(level, wanted);
        if (result.isEmpty() && !indexedThisTick(level)) {
            refreshContentIndex(level);
            result = exactItemCandidates(level, wanted);
        }
        return result;
    }

    private List<BlockPos> exactItemCandidates(ServerLevel level, ItemStack wanted) {
        Set<BlockPos> indexed = itemIndex.getOrDefault(wanted.getItem(), Collections.emptySet());
        if (indexed.isEmpty()) return List.of();

        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : indexed) {
            if (chestContainsItem(level, pos, wanted)) result.add(pos);
        }
        return result;
    }

    // =========================================================================
    // Query
    // =========================================================================

    /** True if this storage room contains at least one crafting table. */
    /** Composters inside this room, as of the last scan. */
    public Set<BlockPos> getComposterPositions() {
        return java.util.Collections.unmodifiableSet(composterBlocks);
    }

    public boolean hasFurnace() {
        return !furnaceBlocks.isEmpty();
    }

    @javax.annotation.Nullable
    public BlockPos getFurnacePos() {
        return furnaceBlocks.isEmpty() ? null : furnaceBlocks.iterator().next();
    }

    /** Every furnace in this room, in scan order (pick one with {@code CraftingStation.pick}). */
    public java.util.List<BlockPos> getFurnacePositions() {
        return java.util.List.copyOf(furnaceBlocks);
    }

    public boolean hasCraftingTable() {
        return !craftingTableBlocks.isEmpty();
    }

    public boolean hasStonecutter() {
        return !stonecutterBlocks.isEmpty();
    }

    @javax.annotation.Nullable
    public BlockPos getStonecutterPos() {
        return stonecutterBlocks.isEmpty() ? null : stonecutterBlocks.iterator().next();
    }

    /** Every stonecutter in this room, in scan order (pick one with {@code CraftingStation.pick}). */
    public java.util.List<BlockPos> getStonecutterPositions() {
        return java.util.List.copyOf(stonecutterBlocks);
    }

    /**
     * Returns the position of the first crafting table found in this storage
     * room, or {@code null} if none exists. A villager about to work one picks
     * from {@link #getCraftingTablePositions} instead.
     */
    public BlockPos getCraftingTablePos() {
        return craftingTableBlocks.isEmpty() ? null : craftingTableBlocks.iterator().next();
    }

    /** Every crafting table in this room, in scan order (pick one with {@code CraftingStation.pick}). */
    public java.util.List<BlockPos> getCraftingTablePositions() {
        return java.util.List.copyOf(craftingTableBlocks);
    }

    /**
     * Returns {@code true} when at least one item matching {@code type} is found
     * across all chests in this storage room.
     */
    public boolean hasResource(ServerLevel level, ResourceType type) {
        return !findChestsWithResource(level, type).isEmpty();
    }

    /** Returns {@code true} if this exact item stack exists in at least one chest. */
    /**
     * How many of {@code item} this storage holds, across all its chests.
     *
     * <p>{@link #hasItem} answers "any at all", which is enough to decide
     * whether a trip is worth making but not enough to decide whether the
     * village has too much of something already.
     */
    public int countItem(ServerLevel level, Item item) {
        int total = 0;
        for (StorageInventory chest : inventories(level)) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (stack.getItem() == item && mayUse(level, stack)) total += stack.getCount();
            }
        }
        return total;
    }

    /** One stop per distinct contents: the position first met showing it, and the contents. */
    private record Held(BlockPos pos, StorageInventory inventory) {}

    /**
     * The inventories behind {@code positions}, each once. Both halves of a Sophisticated
     * double chest show the whole chest, so reading each half would count it twice.
     */
    private static List<Held> distinct(ServerLevel level, Iterable<BlockPos> positions) {
        List<Held> result = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (BlockPos pos : positions) {
            StorageInventory inventory = StorageContainers.inventory(level, pos);
            if (inventory != null && seen.add(inventory.key())) result.add(new Held(pos, inventory));
        }
        return result;
    }

    /** Every container's contents in this room, each once. */
    public List<StorageInventory> inventories(ServerLevel level) {
        return inventories(level, functionalBlocks);
    }

    /** The contents behind {@code positions} (such as a find result), each once. */
    public static List<StorageInventory> inventories(ServerLevel level, Iterable<BlockPos> positions) {
        List<StorageInventory> result = new ArrayList<>();
        for (Held held : distinct(level, positions)) result.add(held.inventory());
        return result;
    }

    /** How many of each item of {@code type} this storage holds, leaving {@code skip} items out. */
    public Map<Item, Integer> countResourceByItem(ServerLevel level, ResourceType type,
                                                  java.util.function.Predicate<Item> skip) {
        Map<Item, Integer> counts = new HashMap<>();
        for (Held held : distinct(level, findChestsWithResource(level, type))) {
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (stack.isEmpty() || !mayUse(level, stack) || !matchesType(stack, type)
                        || skip.test(stack.getItem())) continue;
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** Usable logs and planks in this storage, every wood type counted together. */
    public int countWood(ServerLevel level) {
        int total = 0;
        for (int count : countResourceByItem(level, ResourceType.WOOD, item -> false).values()) {
            total += count;
        }
        return total;
    }

    /** Stacks taken by {@link #withdrawWood}, with the chest each came from. */
    public record WithdrawnStack(BlockPos chestPos, ItemStack stack) {}

    /**
     * Takes {@code amount} logs and planks in any mix of wood types, planks first.
     * {@link #withdrawItems} only ever takes one concrete item, so one stray plank
     * could fail a craft in a room full of logs. Takes nothing when the room holds
     * less than {@code amount}; the caller checks the total of what came back.
     */
    public List<WithdrawnStack> withdrawWood(ServerLevel level, int amount) {
        if (amount <= 0 || countWood(level) < amount) return List.of();
        List<WithdrawnStack> taken = new ArrayList<>();
        int needed = amount;
        List<Held> chests = distinct(level, findChestsWithResource(level, ResourceType.WOOD));
        for (TagKey<Item> kind : List.of(ItemTags.PLANKS, ItemTags.LOGS)) {
            for (Held held : chests) {
                StorageInventory chest = held.inventory();
                for (int i = 0; i < chest.size() && needed > 0; i++) {
                    ItemStack slot = chest.peek(i);
                    if (slot.isEmpty() || !slot.is(kind) || !mayUse(level, slot)) continue;
                    ItemStack got = chest.take(i, Math.min(slot.getCount(), needed));
                    if (got.isEmpty()) continue;
                    taken.add(new WithdrawnStack(held.pos(), got));
                    needed -= got.getCount();
                }
                if (needed <= 0) break;
            }
            if (needed <= 0) break;
        }
        if (!taken.isEmpty()) invalidateContentIndex();
        return taken;
    }

    /**
     * {@link #withdrawWood(ServerLevel, int)} for {@code forNpc}: leaves wood other
     * crafters have claimed, and ends this villager's own claim once it has its wood.
     */
    public List<WithdrawnStack> withdrawWood(ServerLevel level, int amount, UUID forNpc) {
        if (freeWood(level, forNpc) < amount) return List.of();
        List<WithdrawnStack> taken = withdrawWood(level, amount);
        if (!taken.isEmpty()) releaseWoodClaim(forNpc);
        return taken;
    }

    public boolean hasItem(ServerLevel level, ItemStack wanted) {
        return !findChestsWithItem(level, wanted).isEmpty();
    }

    /** Direct, non-cached verification for the chosen chest right before opening it. */
    public boolean chestContainsResource(ServerLevel level, BlockPos pos, ResourceType type) {
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return false;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.peek(i);
            if (mayUse(level, stack) && matchesType(stack, type)) return true;
        }
        return false;
    }

    /** Direct, non-cached verification for exact-item jobs right before opening the chest. */
    public boolean chestContainsItem(ServerLevel level, BlockPos pos, ItemStack wanted) {
        if (wanted.isEmpty()) return false;
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return false;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack stack = chest.peek(i);
            if (matchesWanted(level, stack, wanted)) return true;
        }
        return false;
    }

    /**
     * Returns {@code true} when the chests hold at least one axe whose item type is in
     * {@code allowed}.
     */
    public boolean hasAxeOfTypes(ServerLevel level, Set<Item> allowed) {
        return hasToolOfTypes(level, ItemTags.AXES, allowed);
    }

    /**
     * Removes one axe of an allowed tier from the chests and returns it,
     * or {@link ItemStack#EMPTY} if none found.
     */
    public ItemStack withdrawAxeOfTypes(ServerLevel level, Set<Item> allowed) {
        return withdrawToolOfTypes(level, ItemTags.AXES, allowed);
    }

    /** Returns true when storage holds at least one sword whose tier is allowed. */
    public boolean hasSwordOfTypes(ServerLevel level, Set<Item> allowed) {
        return hasToolOfTypes(level, ItemTags.SWORDS, allowed);
    }

    /** Withdraws one allowed sword from storage, or {@link ItemStack#EMPTY} if none exists. */
    public ItemStack withdrawSwordOfTypes(ServerLevel level, Set<Item> allowed) {
        return withdrawToolOfTypes(level, ItemTags.SWORDS, allowed);
    }

    /**
     * Withdraws one item matching {@code filter} from a chest of this room
     * within {@code maxDistSq} of {@code from}, or {@link ItemStack#EMPTY}.
     *
     * <p>For items told apart by their components rather than their id -- an
     * enchanted book is only worth taking for the enchantment it stores --
     * which {@link #withdrawItemFromChest} cannot distinguish.
     */
    public ItemStack withdrawMatchingNear(ServerLevel level, BlockPos from, double maxDistSq,
                                          java.util.function.Predicate<ItemStack> filter) {
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos pos : functionalBlocks) {
            if (pos.distSqr(from) <= maxDistSq) near.add(pos);
        }
        for (Held held : distinct(level, near)) {
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack slot = chest.peek(i);
                if (slot.isEmpty() || !mayUse(level, slot) || !filter.test(slot)) continue;
                ItemStack result = chest.take(i, 1);
                if (result.isEmpty()) continue;
                invalidateContentIndex();
                return result;
            }
        }
        return ItemStack.EMPTY;
    }

    /** Counts items matching {@code filter} across this room's chests, components included. */
    public int countMatching(ServerLevel level, java.util.function.Predicate<ItemStack> filter) {
        int total = 0;
        for (StorageInventory chest : inventories(level)) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack slot = chest.peek(i);
                if (!slot.isEmpty() && mayUse(level, slot) && filter.test(slot)) total += slot.getCount();
            }
        }
        return total;
    }

    public boolean hasToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed) {
        if (allowed.isEmpty()) return false;
        for (StorageInventory chest : inventories(level)) {
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (mayUse(level, stack) && stack.is(tag) && allowed.contains(stack.getItem())) return true;
            }
        }
        return false;
    }

    /** The best available tool by the default rule, without removing it, including its item components. */
    public ItemStack peekToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                     @javax.annotation.Nullable UUID forNpc) {
        return peekToolOfTypes(level, tag, allowed, stack -> true, ToolSelectionHelper::quality, forNpc);
    }

    /**
     * The tool that the receiver can currently accept that is best by the job's
     * {@code quality}, leaving out tools promised to other villagers (see
     * {@link #reserveToolOfTypes}).
     */
    public ItemStack peekToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                     java.util.function.Predicate<ItemStack> accepts,
                                     java.util.function.ToLongFunction<ItemStack> quality,
                                     @javax.annotation.Nullable UUID forNpc) {
        ToolSlot best = findBestTool(level, tag, allowed, accepts, quality, forNpc);
        StorageInventory chest = best == null ? null : StorageContainers.inventory(level, best.chestPos());
        if (chest == null || best.slot() >= chest.size()) return ItemStack.EMPTY;
        return chest.peek(best.slot()).copyWithCount(1);
    }

    /** Withdraws the best allowed tool in storage by the default {@link ToolSelectionHelper#quality}. */
    public ItemStack withdrawToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed) {
        return withdrawToolOfTypes(level, tag, allowed, stack -> true, ToolSelectionHelper::quality, null);
    }

    /**
     * Withdraws the allowed tool the receiver can currently accept that is best by
     * the job's {@code quality}, never one promised to another villager; ends
     * {@code forNpc}'s own promise here.
     */
    public ItemStack withdrawToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                         java.util.function.Predicate<ItemStack> accepts,
                                         java.util.function.ToLongFunction<ItemStack> quality,
                                         @javax.annotation.Nullable UUID forNpc) {
        ToolSlot best = findBestTool(level, tag, allowed, accepts, quality, forNpc);
        StorageInventory chest = best == null ? null : StorageContainers.inventory(level, best.chestPos());
        if (chest == null || best.slot() >= chest.size()) return ItemStack.EMPTY;
        ItemStack slot = chest.peek(best.slot());
        if (slot.isEmpty() || !accepts.test(slot.copyWithCount(1))) return ItemStack.EMPTY;
        ItemStack result = chest.take(best.slot(), 1);
        if (result.isEmpty()) return ItemStack.EMPTY;
        if (forNpc != null) releaseToolReservations(forNpc);
        invalidateContentIndex();
        markToolInventoryChanged();
        return result;
    }

    /**
     * Whether this room holds a usable allowed tool that beats {@code currentQuality}
     * and is not promised to another villager.
     */
    public boolean hasToolBetterThan(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                     java.util.function.ToLongFunction<ItemStack> quality,
                                     long currentQuality, @javax.annotation.Nullable UUID forNpc) {
        return findBetterTool(level, tag, allowed, quality, currentQuality, forNpc) != null;
    }

    /**
     * Trades {@code oldTool} for the best usable allowed tool here that beats it on
     * {@code quality}, never one promised to another villager. The old tool goes into
     * the chest the new one came out of (that slot was just freed), or anywhere in the
     * room; a villager never keeps it. If it cannot be stored, the new tool goes back
     * and nothing is traded. Ends {@code forNpc}'s own promise here either way.
     *
     * @return the new tool, with {@code oldTool} emptied once it is stored; EMPTY when
     *         nothing was traded, {@code oldTool} untouched. Only if the new tool could
     *         not be put back either is it returned with {@code oldTool} still full.
     */
    public ItemStack swapForBetterTool(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                       java.util.function.ToLongFunction<ItemStack> quality,
                                       ItemStack oldTool, @javax.annotation.Nullable UUID forNpc) {
        if (oldTool.isEmpty()) return ItemStack.EMPTY;
        ToolSlot best = findBetterTool(level, tag, allowed, quality,
                quality.applyAsLong(oldTool), forNpc);
        if (forNpc != null) releaseToolReservations(forNpc);
        StorageInventory chest = best == null ? null : StorageContainers.inventory(level, best.chestPos());
        if (chest == null || best.slot() >= chest.size()) return ItemStack.EMPTY;
        ItemStack better = chest.take(best.slot(), 1);
        if (better.isEmpty()) return ItemStack.EMPTY;
        invalidateContentIndex();
        markToolInventoryChanged();

        ItemStack old = oldTool.copy();
        // Unclaimed for a day, the blacksmith melts it down; see ForgeRecycling.
        com.alex.test.village.villagers.blacksmith.ForgeRecycling.stampReturned(old, level.getGameTime());
        if (!returnToChest(level, best.chestPos(), old)) depositItems(level, old);
        if (old.isEmpty()) {
            oldTool.setCount(0);
            return better;
        }
        ItemStack back = better.copy();
        if (!returnToChest(level, best.chestPos(), back)) depositItems(level, back);
        return back.isEmpty() ? ItemStack.EMPTY : better;
    }

    /**
     * Blacksmith items the player keeps in the Stock tab and storage holds no more
     * of than kept: they are the player's, so no villager is handed one.
     */
    private Set<Item> keptForPlayer(ServerLevel level) {
        com.alex.test.village.VillageManager village = StorageRoomAccess.villageOf(level, this);
        if (village == null) return Set.of();
        return com.alex.test.village.economy.StockReserves.get(level.getServer())
                .lockedForgeItems(village, level);
    }

    /**
     * The usable allowed tool with the highest {@code quality} above
     * {@code minQualityExclusive}; on a tie, the one with the most uses left.
     * Tools promised to a villager other than {@code forNpc} are skipped.
     */
    private ToolSlot findBetterTool(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                    java.util.function.ToLongFunction<ItemStack> quality,
                                    long minQualityExclusive, @javax.annotation.Nullable UUID forNpc) {
        if (allowed.isEmpty()) return null;
        Set<Item> kept = keptForPlayer(level);
        ToolSlot best = null;
        long bestQuality = Long.MIN_VALUE;
        int bestDurability = -1;
        for (Held held : distinct(level, functionalBlocks)) {
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (!mayUse(level, stack) || !stack.is(tag) || !allowed.contains(stack.getItem())
                        || !ToolSelectionHelper.isUsableByDefault(stack)
                        || kept.contains(stack.getItem())) continue;
                long q = quality.applyAsLong(stack);
                if (q <= minQualityExclusive) continue;
                ToolSlot here = new ToolSlot(held.pos(), i);
                if (reservedForOther(level, here, stack, forNpc)) continue;
                int durability = ToolSelectionHelper.durabilityLeft(stack);
                if (q > bestQuality || (q == bestQuality && durability > bestDurability)) {
                    bestQuality = q;
                    bestDurability = durability;
                    best = here;
                }
            }
        }
        return best;
    }

    /**
     * The usable allowed tool that is best by the job's {@code quality}, for a
     * villager with none; on a tie, the one with the most uses left. The same
     * rule as the villager's own pick ({@link ToolSelectionHelper#findBestToolSlot}),
     * so it never fetches one tool and then walks back to swap it for another.
     */
    private ToolSlot findBestTool(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                  java.util.function.Predicate<ItemStack> accepts,
                                  java.util.function.ToLongFunction<ItemStack> quality,
                                  @javax.annotation.Nullable UUID forNpc) {
        if (allowed.isEmpty()) return null;
        Set<Item> kept = keptForPlayer(level);
        ToolSlot best = null;
        long bestQuality = Long.MIN_VALUE;
        int bestDurability = -1;
        for (Held held : distinct(level, functionalBlocks)) {
            BlockPos pos = held.pos();
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack stack = chest.peek(i);
                if (!mayUse(level, stack) || !stack.is(tag) || !allowed.contains(stack.getItem())
                        || !ToolSelectionHelper.isUsableByDefault(stack)
                        || kept.contains(stack.getItem())
                        || !accepts.test(stack.copyWithCount(1))) {
                    continue;
                }
                ToolSlot here = new ToolSlot(pos, i);
                if (reservedForOther(level, here, stack, forNpc)) continue;
                long q = quality.applyAsLong(stack);
                int durability = ToolSelectionHelper.durabilityLeft(stack);
                if (q > bestQuality || (q == bestQuality && durability > bestDurability)) {
                    bestQuality = q;
                    bestDurability = durability;
                    best = here;
                }
            }
        }
        return best;
    }

    private record ToolSlot(BlockPos chestPos, int slot) {}

    // -------------------------------------------------------------------------
    // Tool reservations: a villager that sets off for a tool claims it first, so
    // the others pick another one, craft, or stay put instead of racing it there.
    // -------------------------------------------------------------------------

    /** Safety net only: the trip's stop() releases its promise long before this. */
    private static final long TOOL_RESERVATION_TICKS = 20L * 60 * 3;

    private record ToolReservation(UUID npcId, Item item, long expiresAt) {}

    /** Tools promised to a villager on its way here, by chest slot. Not saved: trips restart on load. */
    private final Map<ToolSlot, ToolReservation> toolReservations = new HashMap<>();

    /**
     * Promises the tool {@link #peekToolOfTypes} would hand {@code npcId} to it, replacing
     * any earlier promise it had here. False when no free tool is left.
     */
    public boolean reserveToolOfTypes(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                      java.util.function.Predicate<ItemStack> accepts,
                                      java.util.function.ToLongFunction<ItemStack> quality, UUID npcId) {
        releaseToolReservations(npcId);
        return reserve(level, findBestTool(level, tag, allowed, accepts, quality, npcId), npcId);
    }

    /** Promises the best tool beating {@code currentQuality} to {@code npcId}; false when there is none free. */
    public boolean reserveBetterTool(ServerLevel level, TagKey<Item> tag, Set<Item> allowed,
                                     java.util.function.ToLongFunction<ItemStack> quality,
                                     long currentQuality, UUID npcId) {
        releaseToolReservations(npcId);
        return reserve(level, findBetterTool(level, tag, allowed, quality, currentQuality, npcId), npcId);
    }

    private boolean reserve(ServerLevel level, @javax.annotation.Nullable ToolSlot slot, UUID npcId) {
        StorageInventory chest = slot == null ? null : StorageContainers.inventory(level, slot.chestPos());
        if (chest == null || slot.slot() >= chest.size()) return false;
        ItemStack stack = chest.peek(slot.slot());
        if (stack.isEmpty()) return false;
        toolReservations.put(slot, new ToolReservation(
                npcId, stack.getItem(), level.getGameTime() + TOOL_RESERVATION_TICKS));
        return true;
    }

    /** Ends every promise {@code npcId} holds in this room. */
    public void releaseToolReservations(UUID npcId) {
        toolReservations.values().removeIf(r -> r.npcId().equals(npcId));
    }

    /**
     * Whether the tool in {@code slot} is promised to a villager other than {@code forNpc}.
     * A promise lapses once it expires or that tool is no longer in the slot.
     */
    private boolean reservedForOther(ServerLevel level, ToolSlot slot, ItemStack stack,
                                     @javax.annotation.Nullable UUID forNpc) {
        ToolReservation reservation = toolReservations.get(slot);
        if (reservation == null) return false;
        if (level.getGameTime() >= reservation.expiresAt() || !stack.is(reservation.item())) {
            toolReservations.remove(slot);
            return false;
        }
        return !reservation.npcId().equals(forNpc);
    }

    // -------------------------------------------------------------------------
    // Wood claims: a villager setting off to craft a tool from this room's wood
    // claims the amount first, so a second crafter does not walk over for wood
    // that will be gone when it gets there. Only tool crafters honour them.
    // -------------------------------------------------------------------------

    private record WoodClaim(int amount, long expiresAt) {}

    /** Wood promised to crafters on their way here, by villager. Not saved: trips restart on load. */
    private final Map<UUID, WoodClaim> woodClaims = new HashMap<>();

    /** Wood {@code forNpc} can count on: what is here less what other villagers have claimed. */
    public int freeWood(ServerLevel level, @javax.annotation.Nullable UUID forNpc) {
        int total = countWood(level);
        if (woodClaims.isEmpty()) return total;
        long now = level.getGameTime();
        woodClaims.values().removeIf(claim -> now >= claim.expiresAt());
        for (Map.Entry<UUID, WoodClaim> claim : woodClaims.entrySet()) {
            if (!claim.getKey().equals(forNpc)) total -= claim.getValue().amount();
        }
        return total;
    }

    /** Claims {@code amount} wood for {@code npcId}, replacing its earlier claim here; false when not that much is free. */
    public boolean claimWood(ServerLevel level, int amount, UUID npcId) {
        woodClaims.remove(npcId);
        if (amount <= 0 || freeWood(level, npcId) < amount) return false;
        woodClaims.put(npcId, new WoodClaim(amount, level.getGameTime() + TOOL_RESERVATION_TICKS));
        return true;
    }

    public void releaseWoodClaim(UUID npcId) {
        woodClaims.remove(npcId);
    }

    // =========================================================================
    // Deposit
    // =========================================================================

    /**
     * Returns whether this room can currently accept any part of {@code stack}.
     *
     * <p>This is a direct, non-cached capacity check. Deposit planning uses it
     * to avoid sending villagers to a full storage room over and over.
     */
    public boolean canAccept(ServerLevel level, ItemStack stack) {
        if (!mayUse(level, stack)) return false;
        for (BlockPos pos : functionalBlocks) {
            if (chestCanAccept(level, pos, stack)) return true;
        }
        return false;
    }

    /** Capacity check for a complete merchant or production batch. */
    public boolean canAcceptAll(ServerLevel level, ItemStack stack) {
        if (!mayUse(level, stack)) return false;
        int capacity = 0;
        for (Held held : distinct(level, functionalBlocks)) {
            if (!depositable(level, held.pos(), stack)) continue;
            // Each container is asked only for what the others could not take.
            capacity += held.inventory().accepts(stack.copyWithCount(stack.getCount() - capacity));
            if (capacity >= stack.getCount()) return true;
        }
        return false;
    }

    /** Direct capacity check for one chest used by visual chest interactions. */
    public boolean chestCanAccept(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty() || !functionalBlocks.contains(pos) || !mayUse(level, stack)
                || !depositable(level, pos, stack)) {
            return false;
        }
        StorageInventory chest = StorageContainers.inventory(level, pos);
        return chest != null && chest.accepts(stack.copyWithCount(1)) > 0;
    }

    /**
     * Whether villagers may put {@code stack} here at all: the container's filters take it,
     * and a container whose upgrades turn or void items (Sophisticated void, compacting,
     * auto-smelting...) only gets what the player filtered it for.
     */
    private boolean depositable(ServerLevel level, BlockPos pos, ItemStack stack) {
        return containerAccepts(level, pos, stack)
                && (!transformsItems(level, pos) || isLabeled(level, pos));
    }

    /**
     * Attempts to deposit {@code stack} into any chest in this room.
     * First tries to merge into an existing matching slot; falls back to an empty slot.
     *
     * @return {@code true} when the entire stack was deposited
     */
    /**
     * Moves as much of {@code stack} into this room's chests as will fit,
     * shrinking {@code stack} by the amount actually stored.
     *
     * @return true when the whole stack fit. On false, {@code stack} holds
     *         exactly the remainder — callers must not assume it is untouched.
     */
    public boolean depositItems(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) return true;
        // A dish from before makers were marked: sorted out here so it stacks
        // with today's (VillageFood).
        com.alex.test.village.tokens.VillageFood.normalize(stack);
        if (!mayUse(level, stack)) return false;
        ItemStack remaining = stack.copy();
        boolean changed = false;

        // Labeled containers that take this item come first (item frame, picked items, category).
        for (BlockPos pos : functionalBlocks) {
            if (remaining.isEmpty()) break;
            if (isLabeled(level, pos) && containerAccepts(level, pos, stack)) {
                changed |= fillContainer(level, pos, remaining);
            }
        }
        // Then the "Anything" containers the village learned for this category.
        if (!remaining.isEmpty()) changed |= depositLearned(level, remaining);
        // Whatever is left is overflow.
        if (!remaining.isEmpty()) {
            for (BlockPos pos : overflowOrder(level, stack)) {
                changed |= fillContainer(level, pos, remaining);
                if (remaining.isEmpty()) break;
            }
        }
        if (changed) {
            invalidateContentIndex();
            if (ToolSelectionHelper.isProfessionTool(stack)) markToolInventoryChanged();
        }
        // Shrink the caller's stack by what actually went in. A partial deposit
        // has already changed the chest, so reporting only "it did not all fit"
        // and leaving the caller holding the original count duplicates whatever
        // did land — one felled tree turned 60 stored logs into 69 across the
        // chest and the villager's pack.
        stack.shrink(stack.getCount() - remaining.getCount());
        return remaining.isEmpty();
    }

    /**
     * Moves what fits of {@code remaining} into one container, topping up matching stacks
     * before taking empty slots. Shrinks {@code remaining}; true when anything went in.
     */
    private boolean fillContainer(ServerLevel level, BlockPos pos, ItemStack remaining) {
        if (remaining.isEmpty()) return false;
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return false;
        int stored = remaining.getCount() - chest.insert(remaining, false).getCount();
        remaining.shrink(stored);
        return stored > 0;
    }

    /**
     * Puts back food a villager carries but may not use (a player's, picked up
     * before villagers only took plain crops from players): into any container
     * with room, past filters and the usable rule. Shrinks {@code stack} by what
     * went in; true when all of it did.
     */
    public boolean returnUnusable(ServerLevel level, ItemStack stack) {
        if (stack.isEmpty()) return true;
        boolean changed = false;
        for (BlockPos pos : overflowOrder(level, stack)) {
            if (transformsItems(level, pos)) continue;
            changed |= fillContainer(level, pos, stack);
            if (stack.isEmpty()) break;
        }
        if (changed) invalidateContentIndex();
        return stack.isEmpty();
    }

    /**
     * Puts items a villager just withdrew from {@code pos} back into that same
     * container, ignoring its deposit filters. Shrinks {@code stack} by what went in.
     *
     * <p>A filter only says what may be deposited; it never stopped these items
     * coming out. Routing the put-back through {@link #depositItems} let a
     * "Nothing" chest, or one framed for another item, hand out food and then
     * refuse it back, and the rest ended up on the floor. Containers that
     * transform or delete what goes in (Sophisticated upgrades) are skipped.
     *
     * @return true when all of it went back
     */
    public boolean returnToChest(ServerLevel level, BlockPos pos, ItemStack stack) {
        if (stack.isEmpty()) return true;
        if (!mayUse(level, stack) || transformsItems(level, pos)) return false;
        boolean tool = ToolSelectionHelper.isProfessionTool(stack);
        boolean changed = false;
        for (BlockPos half : halves(level, pos)) {
            changed |= fillContainer(level, half, stack);
            if (stack.isEmpty()) break;
        }
        if (changed) {
            invalidateContentIndex();
            if (tool) markToolInventoryChanged();
        }
        return stack.isEmpty();
    }

    // =========================================================================
    // Withdraw
    // =========================================================================

    /**
     * Removes up to {@code maxAmount} items of {@code type} from indexed chests.
     * Only one concrete item type is withdrawn per call.
     */
    public ItemStack withdrawItems(
            ServerLevel level, ResourceType type, int maxAmount) {
        return withdrawItems(level, type, maxAmount, item -> false);
    }

    /** As {@link #withdrawItems(ServerLevel, ResourceType, int)}, leaving {@code skip} items alone. */
    public ItemStack withdrawItems(
            ServerLevel level, ResourceType type, int maxAmount, java.util.function.Predicate<Item> skip) {
        ItemStack result = ItemStack.EMPTY;
        int needed = maxAmount;

        for (BlockPos pos : findChestsWithResource(level, type)) {
            ItemStack taken =
                    withdrawItemsFromChest(level, pos, type, needed, result, skip);
            if (taken.isEmpty()) continue;

            if (result.isEmpty()) result = taken;
            else result.grow(taken.getCount());

            needed = maxAmount - result.getCount();
            if (needed <= 0) break;
        }
        return result;
    }

    /** Withdraws from one known chest only. Use this for visual chest interactions. */
    public ItemStack withdrawItemsFromChest(ServerLevel level, BlockPos pos, ResourceType type, int maxAmount) {
        return withdrawItemsFromChest(level, pos, type, maxAmount, ItemStack.EMPTY, item -> false);
    }

    /** One known chest, leaving {@code skip} items (such as reserved stock) alone. */
    public ItemStack withdrawItemsFromChest(ServerLevel level, BlockPos pos, ResourceType type, int maxAmount,
                                            java.util.function.Predicate<Item> skip) {
        return withdrawItemsFromChest(level, pos, type, maxAmount, ItemStack.EMPTY, skip);
    }

    /** A shelf container belongs to this room when its inside face is open to a scanned floor tile. */
    private boolean isStorageContainerInRoom(ServerLevel level, BlockPos pos) {
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            for (int feetY = Math.max(minY, pos.getY() - 12); feetY <= pos.getY(); feetY++) {
                BlockPos floor = new BlockPos(pos.getX() + direction.getStepX(), feetY,
                        pos.getZ() + direction.getStepZ());
                if (!navMeshBlocks.contains(floor)) continue;
                boolean open = true;
                for (int y = floor.getY() + 1; y <= pos.getY(); y++) {
                    BlockPos inside = new BlockPos(floor.getX(), y, floor.getZ());
                    if (!level.getBlockState(inside).getCollisionShape(level, inside).isEmpty()) {
                        open = false;
                        break;
                    }
                }
                if (open) return true;
            }
        }
        return false;
    }

    /** A room-side service position also works for a high or blocked container. */
    @javax.annotation.Nullable
    public BlockPos getWalkTargetForContainer(BlockPos container, NavGraph graph, BlockPos from) {
        BlockPos beside = getWalkTargetBeside(container, graph, from);
        if (beside != null) return beside;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos floor : navMeshBlocks) {
            NavNode node = graph.nodeAt(floor.getX(), floor.getY(), floor.getZ());
            if (node == null || !node.isRoutable()) continue;
            double distance = floor.distSqr(container);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = floor;
            }
        }
        return best;
    }

    /**
     * The categories a container takes from villager deposits; what is already inside stays
     * withdrawable. Apart from Anything the categories do not overlap, so every item has one
     * home. These only steer deposits -- villagers still fetch by {@link ResourceType}.
     * A container can take several categories; Anything and Nothing always stand alone.
     */
    public enum DepositFilter {
        ANY("Anything"), NONE("Nothing"), FOOD("Food"), CROP("Crops"), SEEDS("Seeds"),
        RAW_MEAT("Raw meat"), WOOD("Wood"), STONE("Stone"), ORES("Ores"), TOOLS("Tools"),
        ARMOR("Armor"), ENCHANTS("Enchants");

        private final String label;
        DepositFilter(String label) { this.label = label; }
        public String label() { return label; }

        /** {@link #label()} in the player's language, for screens. */
        public String shown() {
            return switch (this) {
                case ANY -> Tr.s("storage.dynamicvillages.filter.any", "Anything");
                case NONE -> Tr.s("storage.dynamicvillages.filter.none", "Nothing");
                case FOOD -> Tr.s("storage.dynamicvillages.filter.food", "Food");
                case CROP -> Tr.s("storage.dynamicvillages.filter.crop", "Crops");
                case SEEDS -> Tr.s("storage.dynamicvillages.filter.seeds", "Seeds");
                case RAW_MEAT -> Tr.s("storage.dynamicvillages.filter.raw_meat", "Raw meat");
                case WOOD -> Tr.s("storage.dynamicvillages.filter.wood", "Wood");
                case STONE -> Tr.s("storage.dynamicvillages.filter.stone", "Stone");
                case ORES -> Tr.s("storage.dynamicvillages.filter.ores", "Ores");
                case TOOLS -> Tr.s("storage.dynamicvillages.filter.tools", "Tools");
                case ARMOR -> Tr.s("storage.dynamicvillages.filter.armor", "Armor");
                case ENCHANTS -> Tr.s("storage.dynamicvillages.filter.enchants", "Enchants");
            };
        }

        /** Whether any category of {@code filters} takes {@code stack}. */
        public static boolean anyAccepts(Set<DepositFilter> filters, ItemStack stack) {
            for (DepositFilter filter : filters) {
                if (filter.accepts(stack)) return true;
            }
            return false;
        }

        /**
         * {@code current} with {@code filter} switched on or off. Anything and Nothing replace
         * every other category; any other category replaces them. Nothing left means Anything.
         */
        public static EnumSet<DepositFilter> toggled(Set<DepositFilter> current, DepositFilter filter, boolean on) {
            EnumSet<DepositFilter> result = current.isEmpty()
                    ? EnumSet.noneOf(DepositFilter.class) : EnumSet.copyOf(current);
            if (on && (filter == ANY || filter == NONE)) {
                result = EnumSet.of(filter);
            } else if (on) {
                result.remove(ANY);
                result.remove(NONE);
                result.add(filter);
            } else {
                result.remove(filter);
            }
            return normalized(result);
        }

        /** A valid category set: Anything or Nothing alone, or one or more categories. */
        public static EnumSet<DepositFilter> normalized(Set<DepositFilter> filters) {
            EnumSet<DepositFilter> result = filters.isEmpty()
                    ? EnumSet.noneOf(DepositFilter.class) : EnumSet.copyOf(filters);
            if (result.size() > 1) {
                result.remove(ANY);
                result.remove(NONE);
            }
            if (result.isEmpty()) result.add(ANY);
            return result;
        }

        public static boolean isAnything(Set<DepositFilter> filters) {
            return filters.isEmpty() || filters.equals(EnumSet.of(ANY));
        }

        public static int mask(Set<DepositFilter> filters) {
            int mask = 0;
            for (DepositFilter filter : filters) mask |= 1 << filter.ordinal();
            return mask;
        }

        public static EnumSet<DepositFilter> fromMask(int mask) {
            EnumSet<DepositFilter> result = EnumSet.noneOf(DepositFilter.class);
            for (DepositFilter filter : values()) {
                if ((mask & (1 << filter.ordinal())) != 0) result.add(filter);
            }
            return normalized(result);
        }

        /** "Food, Crops" for a set, in grid order. */
        public static String labels(Set<DepositFilter> filters) {
            List<String> names = new ArrayList<>();
            for (DepositFilter filter : normalized(filters)) names.add(filter.shown());
            return String.join(", ", names);
        }

        public boolean accepts(ItemStack stack) {
            if (stack.isEmpty()) return false;
            return switch (this) {
                case ANY -> true;
                // Ready to eat; produce and raw meat have their own chests.
                case FOOD -> VillageItemOwnership.isVillagerFood(stack) && !isCrop(stack) && !isRawMeat(stack);
                case CROP -> isCrop(stack);
                // Carrots and potatoes also plant, but they are crops first.
                case SEEDS -> !isCrop(stack) && (stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                        || stack.is(Items.MELON_SEEDS) || stack.is(Items.PUMPKIN_SEEDS));
                case RAW_MEAT -> isRawMeat(stack);
                // Every tree and stem species: logs, stripped logs, wood, planks and the
                // lumberjack's by-products.
                case WOOD -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS)
                        || stack.is(ItemTags.BAMBOO_BLOCKS) || stack.is(ItemTags.WOODEN_SLABS)
                        || stack.is(ItemTags.WOODEN_STAIRS) || stack.is(ItemTags.WOODEN_FENCES)
                        || stack.is(ItemTags.FENCE_GATES) || stack.is(ItemTags.WOODEN_DOORS)
                        || stack.is(ItemTags.WOODEN_TRAPDOORS) || stack.is(ItemTags.SAPLINGS)
                        || stack.is(Items.STICK) || stack.is(Items.BAMBOO);
                case STONE -> stack.is(Tags.Items.COBBLESTONES) || stack.is(Tags.Items.STONES);
                // What the miner digs and the smith smelts, with furnace coal. Lapis is an
                // enchanting supply.
                case ORES -> !stack.is(Items.LAPIS_LAZULI) && (stack.is(Tags.Items.ORES)
                        || stack.is(Tags.Items.RAW_MATERIALS) || stack.is(Tags.Items.INGOTS)
                        || stack.is(Tags.Items.NUGGETS) || stack.is(Tags.Items.GEMS)
                        || stack.is(ItemTags.COALS) || stack.is(Items.REDSTONE)
                        || stack.is(Items.NETHERITE_SCRAP));
                // Tools and weapons of every tier, enchanted or not; shields are armor.
                case TOOLS -> !isArmor(stack) && (stack.is(Tags.Items.TOOLS) || stack.is(ItemTags.AXES)
                        || stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.SHOVELS)
                        || stack.is(ItemTags.HOES) || stack.is(ItemTags.SWORDS));
                case ARMOR -> isArmor(stack);
                // The enchanter's books, what it makes them from, and bottles o' enchanting.
                case ENCHANTS -> stack.is(Items.ENCHANTED_BOOK) || stack.is(Items.BOOK) || stack.is(Items.PAPER)
                        || stack.is(Items.LAPIS_LAZULI) || stack.is(Items.EXPERIENCE_BOTTLE);
                // The player's own container: villagers only take from it.
                case NONE -> false;
            };
        }

        private static boolean isCrop(ItemStack stack) {
            return matchesType(stack, ResourceType.CROP);
        }

        private static boolean isRawMeat(ItemStack stack) {
            return matchesType(stack, ResourceType.RAW_MEAT);
        }

        private static boolean isArmor(ItemStack stack) {
            return stack.getItem() instanceof ArmorItem || stack.is(Tags.Items.ARMORS)
                    || stack.is(Tags.Items.TOOLS_SHIELD) || stack.is(Items.SHIELD);
        }
    }

    private static final String FILTER_KEY = "dynamicvillages:storage_deposit_filter";

    // Container settings (filter, picked items, lock, village binding) are saved on the
    // container's own block entity. Iron Chests' upgrade items and Sophisticated Storage's
    // tier upgrades swap that block entity in place, so the last settings seen at each
    // position are kept here and copied onto a replacement that arrives without any.
    private record CarriedSettings(BlockEntity entity, CompoundTag data) {}
    private final Map<BlockPos, CarriedSettings> carriedSettings = new HashMap<>();

    /** The container's block entity, with its settings restored if it was just replaced. */
    @javax.annotation.Nullable
    private BlockEntity settingsEntity(ServerLevel level, BlockPos pos) {
        BlockEntity entity = level.getBlockEntity(pos);
        // Any storage block entity: Sophisticated's are not vanilla containers but keep the
        // same persistent data.
        if (entity == null || !StorageContainers.isStorageBlock(entity.getBlockState())) return null;
        CarriedSettings carried = carriedSettings.get(pos);
        if (carried == null || carried.entity() == entity) {
            if (carried == null) rememberSettings(pos, entity);
            return entity;
        }
        if (!carried.data().isEmpty() && ownSettings(entity.getPersistentData()).isEmpty()) {
            entity.getPersistentData().merge(carried.data().copy());
            entity.setChanged();
            frameFiltersStale = true;
            LOGGER.debug("[StorageBuilding] carried container settings over to the new block at {}.", pos);
        }
        rememberSettings(pos, entity);
        return entity;
    }

    private void rememberSettings(BlockPos pos, BlockEntity entity) {
        carriedSettings.put(pos.immutable(), new CarriedSettings(entity, ownSettings(entity.getPersistentData())));
    }

    /** This mod's keys only; other mods' data on the block entity is not ours to copy. */
    private static CompoundTag ownSettings(CompoundTag data) {
        CompoundTag own = new CompoundTag();
        for (String key : data.getAllKeys()) {
            if (key.startsWith(LegacyModId.SAVE_PREFIX + ":") || key.startsWith(LegacyModId.OLD + ":")) {
                own.put(key, data.get(key).copy());
            }
        }
        return own;
    }

    /** Forget positions whose container is gone, so a chest placed there later starts clean. */
    private void pruneCarriedSettings(ServerLevel level) {
        carriedSettings.keySet().removeIf(pos -> !StorageContainers.isStorageBlock(level.getBlockState(pos)));
    }

    /**
     * The categories this container takes, never empty. Saved as comma-separated names, so a
     * container saved with one category reads back as exactly that one.
     */
    public EnumSet<DepositFilter> depositFilters(ServerLevel level, BlockPos pos) {
        var entity = settingsEntity(level, pos);
        if (entity == null) return EnumSet.of(DepositFilter.ANY);
        EnumSet<DepositFilter> filters = EnumSet.noneOf(DepositFilter.class);
        for (String value : entity.getPersistentData().getString(FILTER_KEY).split(",")) {
            switch (value) {
                case "" -> { }
                // Saved before the categories were reworked.
                case "AXE" -> filters.add(DepositFilter.TOOLS);
                case "FUEL", "IRON_MATERIAL" -> filters.add(DepositFilter.ORES);
                default -> {
                    try { filters.add(DepositFilter.valueOf(value)); }
                    catch (IllegalArgumentException ignored) { }
                }
            }
        }
        return DepositFilter.normalized(filters);
    }

    // Item frames hung on a container name the only items villagers put in it.
    // Looked up by entity scan, so the result is cached for FRAME_RESCAN_TICKS.
    private static final int FRAME_RESCAN_TICKS = 20;
    private final Map<BlockPos, Set<Item>> frameFilters = new HashMap<>();
    /** Containers with upgrades that turn or void items; refreshed with the frames. */
    private final Set<BlockPos> transforming = new HashSet<>();
    private long frameFiltersScannedAt;
    private boolean frameFiltersStale = true;

    /**
     * Items shown in item frames on this container (either half of a double chest),
     * or an empty set when it has none. A non-empty set replaces its category filter.
     */
    public Set<Item> frameFilter(ServerLevel level, BlockPos pos) {
        refreshFrameFilters(level);
        return frameFilters.getOrDefault(pos, Set.of());
    }

    private void refreshFrameFilters(ServerLevel level) {
        long now = level.getGameTime();
        if (!frameFiltersStale && now - frameFiltersScannedAt < FRAME_RESCAN_TICKS) return;
        frameFiltersStale = false;
        frameFiltersScannedAt = now;
        frameFilters.clear();
        chosenFilters.clear();
        autoHomes.clear();
        autoSpill.clear();
        transforming.clear();
        if (functionalBlocks.isEmpty()) return;
        for (BlockPos pos : functionalBlocks) {
            // A Sophisticated storage locked in its own screen takes only what its slots
            // remember: the same rule as an item frame, and shown the same way.
            BlockEntity entity = level.getBlockEntity(pos);
            Set<Item> locked = SophisticatedCompat.lockedItems(entity);
            if (!locked.isEmpty()) frameFilters.computeIfAbsent(pos, key -> new HashSet<>()).addAll(locked);
            if (SophisticatedCompat.transformsItems(entity)) transforming.add(pos);
            List<Item> chosen = chosenItems(level, pos);
            if (!chosen.isEmpty()) chosenFilters.put(pos, new HashSet<>(chosen));
            EnumSet<AutoCategory> homes = readAuto(level, pos, HOMES_KEY);
            if (!homes.isEmpty()) autoHomes.put(pos, homes);
            EnumSet<AutoCategory> spill = readAuto(level, pos, SPILL_KEY);
            if (!spill.isEmpty()) autoSpill.put(pos, spill);
        }
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (BlockPos pos : functionalBlocks) {
            x0 = Math.min(x0, pos.getX()); y0 = Math.min(y0, pos.getY()); z0 = Math.min(z0, pos.getZ());
            x1 = Math.max(x1, pos.getX()); y1 = Math.max(y1, pos.getY()); z1 = Math.max(z1, pos.getZ());
        }
        AABB area = new AABB(x0 - 1, y0 - 1, z0 - 1, x1 + 2, y1 + 2, z1 + 2);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, area)) {
            ItemStack shown = frame.getItem();
            // Building tokens are markers, never filters.
            if (shown.isEmpty() || ItemFrameTokenRegistry.isToken(shown)) continue;
            BlockPos support = frame.getPos().relative(frame.getDirection().getOpposite());
            if (!functionalBlocks.contains(support)) continue;
            frameFilters.computeIfAbsent(support, key -> new HashSet<>()).add(shown.getItem());
            BlockPos partner = pairedChestPos(level, support);
            if (partner != null && functionalBlocks.contains(partner)) {
                frameFilters.computeIfAbsent(partner, key -> new HashSet<>()).add(shown.getItem());
            }
        }
        refreshAutoSorting(level);
    }

    /**
     * Whether villagers may put {@code stack} into this container. Most specific wins: an item
     * frame, then the items picked in the storage screen, then the categories.
     */
    private boolean containerAccepts(ServerLevel level, BlockPos pos, ItemStack stack) {
        Set<Item> framed = frameFilter(level, pos);
        if (!framed.isEmpty()) return framed.contains(stack.getItem());
        Set<Item> chosen = chosenFilters.getOrDefault(pos, Set.of());
        if (!chosen.isEmpty()) return chosen.contains(stack.getItem());
        return DepositFilter.anyAccepts(depositFilters(level, pos), stack);
    }

    /** The container has an upgrade that changes or deletes what goes in (Sophisticated). */
    private boolean transformsItems(ServerLevel level, BlockPos pos) {
        refreshFrameFilters(level);
        return transforming.contains(pos);
    }

    /** Labeled containers are filled before unlabeled overflow storage. */
    private boolean isLabeled(ServerLevel level, BlockPos pos) {
        return !frameFilter(level, pos).isEmpty() || chosenFilters.containsKey(pos)
                || !DepositFilter.isAnything(depositFilters(level, pos));
    }

    /** Switches one category on or off for this container, keeping its other categories. */
    public boolean toggleDepositFilter(ServerLevel level, BlockPos pos, DepositFilter filter, boolean on) {
        if (!canEdit(level, pos)) return false;
        EnumSet<DepositFilter> filters = DepositFilter.toggled(depositFilters(level, pos), filter, on);
        for (BlockPos half : halves(level, pos)) saveDepositFilters(level, half, filters);
        // Any choice here, "Anything" included, replaces what the village learned.
        clearAuto(level, pos);
        return true;
    }

    /**
     * Room-wide override: every unlocked container gets exactly these categories and loses its
     * picked items. Locked containers keep their settings; item frames still decide for their
     * container.
     */
    public void setDepositFiltersForAll(ServerLevel level, Set<DepositFilter> filters) {
        EnumSet<DepositFilter> normalized = DepositFilter.normalized(filters);
        for (BlockPos pos : functionalBlocks) {
            var entity = settingsEntity(level, pos);
            if (entity == null || isLocked(level, pos)) continue;
            entity.getPersistentData().remove(ITEMS_KEY);
            saveDepositFilters(level, pos, normalized);
            clearAuto(level, pos);
        }
        frameFiltersStale = true;
    }

    // Items picked for a container in the storage screen, and the lock that protects a
    // container's settings from the room-wide override and from accidental edits.
    private static final String ITEMS_KEY = "dynamicvillages:storage_deposit_items";
    private static final String LOCK_KEY = "dynamicvillages:storage_locked";
    public static final int MAX_CHOSEN_ITEMS = 64;
    private final Map<BlockPos, Set<Item>> chosenFilters = new HashMap<>();

    /** Items picked for this container in the storage screen; empty when none. */
    public List<Item> chosenItems(ServerLevel level, BlockPos pos) {
        var entity = settingsEntity(level, pos);
        if (entity == null) return List.of();
        net.minecraft.nbt.ListTag list = entity.getPersistentData()
                .getList(ITEMS_KEY, net.minecraft.nbt.Tag.TAG_STRING);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(list.getString(i));
            Item item = id == null ? Items.AIR : net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
            if (item != Items.AIR && !items.contains(item)) items.add(item);
        }
        return items;
    }

    public boolean isLocked(ServerLevel level, BlockPos pos) {
        var entity = settingsEntity(level, pos);
        return entity != null && entity.getPersistentData().getBoolean(LOCK_KEY);
    }

    public boolean setChosenItems(ServerLevel level, BlockPos pos, List<Item> items) {
        if (!canEdit(level, pos)) return false;
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (Item item : items) {
            if (item == Items.AIR || list.size() >= MAX_CHOSEN_ITEMS) continue;
            list.add(net.minecraft.nbt.StringTag.valueOf(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).toString()));
        }
        for (BlockPos half : halves(level, pos)) {
            var entity = settingsEntity(level, half);
            if (entity == null) continue;
            if (list.isEmpty()) entity.getPersistentData().remove(ITEMS_KEY);
            else entity.getPersistentData().put(ITEMS_KEY, list.copy());
            entity.setChanged();
            rememberSettings(half, entity);
        }
        clearAuto(level, pos);
        frameFiltersStale = true;
        return true;
    }

    public boolean setLocked(ServerLevel level, BlockPos pos, boolean locked) {
        if (!functionalBlocks.contains(pos) || settingsEntity(level, pos) == null) return false;
        for (BlockPos half : halves(level, pos)) {
            var entity = settingsEntity(level, half);
            if (entity == null) continue;
            if (locked) entity.getPersistentData().putBoolean(LOCK_KEY, true);
            else entity.getPersistentData().remove(LOCK_KEY);
            entity.setChanged();
            rememberSettings(half, entity);
        }
        if (locked) clearAuto(level, pos);
        return true;
    }

    private boolean canEdit(ServerLevel level, BlockPos pos) {
        return functionalBlocks.contains(pos) && settingsEntity(level, pos) != null
                && !isLocked(level, pos);
    }

    /** The container and, for a double chest, its other half: settings always cover both. */
    private List<BlockPos> halves(ServerLevel level, BlockPos pos) {
        BlockPos partner = pairedChestPos(level, pos);
        return partner != null && functionalBlocks.contains(partner) ? List.of(pos, partner) : List.of(pos);
    }

    private void saveDepositFilters(ServerLevel level, BlockPos pos, Set<DepositFilter> filters) {
        var entity = settingsEntity(level, pos);
        if (entity == null) return;
        if (DepositFilter.isAnything(filters)) {
            entity.getPersistentData().remove(FILTER_KEY);
        } else {
            List<String> names = new ArrayList<>();
            for (DepositFilter filter : DepositFilter.normalized(filters)) names.add(filter.name());
            entity.getPersistentData().putString(FILTER_KEY, String.join(",", names));
        }
        entity.setChanged();
        rememberSettings(pos, entity);
    }

    // =========================================================================
    // Hidden auto-sorting
    // =========================================================================

    // "Anything" containers learn what the village keeps in them: the first container a
    // category goes into becomes its home, and later deposits of that category follow it.
    // Saved on the container like the other settings but never sent to the storage screen,
    // and wiped by any setting the player makes on that container. Only villager deposits
    // teach it; homes never move because of what is inside.

    /** The deposit categories, plus Misc for items none of them take. */
    public enum AutoCategory {
        FOOD(DepositFilter.FOOD, 0), CROP(DepositFilter.CROP, 0), SEEDS(DepositFilter.SEEDS, 0),
        RAW_MEAT(DepositFilter.RAW_MEAT, 0), WOOD(DepositFilter.WOOD, 1), STONE(DepositFilter.STONE, 1),
        ORES(DepositFilter.ORES, 2), TOOLS(DepositFilter.TOOLS, 2), ARMOR(DepositFilter.ARMOR, 2),
        ENCHANTS(DepositFilter.ENCHANTS, 2), MISC(null, -1);

        @javax.annotation.Nullable private final DepositFilter filter;
        /** Categories of one group share a container when the room runs short. */
        private final int group;

        AutoCategory(@javax.annotation.Nullable DepositFilter filter, int group) {
            this.filter = filter;
            this.group = group;
        }

        boolean relatedTo(AutoCategory other) {
            return group >= 0 && group == other.group;
        }
    }

    private static final Map<Item, AutoCategory> CATEGORY_BY_ITEM =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The category an item is learned under. Cached per item; a tag reload clears it. */
    public static AutoCategory categoryOf(ItemStack stack) {
        if (stack.isEmpty()) return AutoCategory.MISC;
        return CATEGORY_BY_ITEM.computeIfAbsent(stack.getItem(), item -> {
            for (AutoCategory category : AutoCategory.values()) {
                if (category.filter != null && category.filter.accepts(stack)) return category;
            }
            return AutoCategory.MISC;
        });
    }

    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        CATEGORY_BY_ITEM.clear();
    }

    private static final String HOMES_KEY = "dynamicvillages:storage_auto_homes";
    private static final String SPILL_KEY = "dynamicvillages:storage_auto_spill";
    /** A category gets an empty container to itself only while this many stay spare. */
    private static final int SPARE_EMPTY_CONTAINERS = 2;
    /** First load: the fullest container of a category becomes its home from this many items. */
    private static final int BOOTSTRAP_MIN_ITEMS = 16;

    /** Container -> categories it is home to. Both halves of a double chest are listed. */
    private final Map<BlockPos, EnumSet<AutoCategory>> autoHomes = new HashMap<>();
    /** Container -> categories that only use it because their home was full. */
    private final Map<BlockPos, EnumSet<AutoCategory>> autoSpill = new HashMap<>();
    /** Whether this load already checked for a room with nothing learned yet. */
    private boolean autoBootstrapped;

    /** An "Anything" container the player has not set up in any way. */
    private boolean autoEligible(ServerLevel level, BlockPos pos) {
        return frameFilter(level, pos).isEmpty() && !chosenFilters.containsKey(pos)
                && DepositFilter.isAnything(depositFilters(level, pos)) && !isLocked(level, pos)
                && !transforming.contains(pos);
    }

    /**
     * Deposits into the containers learned for this item's category: its homes, then its
     * spill, then a newly claimed one. Shrinks {@code remaining}; true when anything went in.
     */
    private boolean depositLearned(ServerLevel level, ItemStack remaining) {
        AutoCategory category = categoryOf(remaining);
        boolean changed = false;
        for (BlockPos pos : learnedFor(level, autoHomes, category)) {
            changed |= fillContainer(level, pos, remaining);
            if (remaining.isEmpty()) return changed;
        }
        for (BlockPos pos : learnedFor(level, autoSpill, category)) {
            changed |= fillContainer(level, pos, remaining);
            if (remaining.isEmpty()) return changed;
        }
        // One claim holds a whole stack; the second is only for oversized modded stacks.
        for (int claims = 0; claims < 2 && !remaining.isEmpty(); claims++) {
            BlockPos claimed = claimContainer(level, category, remaining);
            if (claimed == null) break;
            boolean filled = false;
            for (BlockPos half : halves(level, claimed)) filled |= fillContainer(level, half, remaining);
            if (!filled) break;
            changed = true;
        }
        return changed;
    }

    /** Containers {@code learned} lists for the category, in room order, both halves. */
    private List<BlockPos> learnedFor(ServerLevel level, Map<BlockPos, EnumSet<AutoCategory>> learned,
                                      AutoCategory category) {
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos pos : functionalBlocks) {
            EnumSet<AutoCategory> categories = learned.get(pos);
            if (categories != null && categories.contains(category) && autoEligible(level, pos)) {
                found.add(pos);
            }
        }
        return found;
    }

    /**
     * Picks a container for a category that has nowhere left to go and remembers it: as the
     * category's home, or as its spill when the category already has a (full) home.
     */
    @javax.annotation.Nullable
    private BlockPos claimContainer(ServerLevel level, AutoCategory category, ItemStack stack) {
        List<BlockPos> homes = learnedFor(level, autoHomes, category);
        List<BlockPos> empty = unclaimedEmptyContainers(level);
        BlockPos pick = null;
        // A full home spills into an empty container right beside it.
        if (!homes.isEmpty()) {
            for (BlockPos pos : empty) {
                if (touchesAny(level, pos, homes)) { pick = pos; break; }
            }
        }
        // Room to spare: the category gets a container to itself.
        if (pick == null && empty.size() > SPARE_EMPTY_CONTAINERS) pick = empty.get(0);
        // Short on room: share a home with related categories.
        if (pick == null) pick = sharedHome(level, category, stack);
        if (pick == null && !empty.isEmpty()) pick = empty.get(0);
        if (pick != null) learn(level, pick, category, !homes.isEmpty());
        return pick;
    }

    /** Empty "Anything" containers nothing is learned for, nearest the door first. */
    private List<BlockPos> unclaimedEmptyContainers(ServerLevel level) {
        List<BlockPos> empty = new ArrayList<>();
        for (BlockPos pos : functionalBlocks) {
            if (!primaryContainerPos(level, pos).equals(pos) || autoHomes.containsKey(pos)
                    || autoSpill.containsKey(pos) || !autoEligible(level, pos)
                    || !isEmptyContainer(level, pos)) continue;
            empty.add(pos);
        }
        empty.sort(Comparator.comparingDouble(pos -> pos.distSqr(doorPos)));
        return empty;
    }

    /** Whether either half of {@code pos} touches one of {@code others}, diagonals included. */
    private boolean touchesAny(ServerLevel level, BlockPos pos, List<BlockPos> others) {
        for (BlockPos half : halves(level, pos)) {
            for (BlockPos other : others) {
                int dx = Math.abs(half.getX() - other.getX());
                int dy = Math.abs(half.getY() - other.getY());
                int dz = Math.abs(half.getZ() - other.getZ());
                if (Math.max(dx, Math.max(dy, dz)) == 1) return true;
            }
        }
        return false;
    }

    /**
     * A home of other categories with room for {@code stack}: a related one nearest the door
     * (Misc: the one with the most free slots), else the one with the fewest categories.
     */
    @javax.annotation.Nullable
    private BlockPos sharedHome(ServerLevel level, AutoCategory category, ItemStack stack) {
        List<BlockPos> candidates = new ArrayList<>();
        for (BlockPos pos : functionalBlocks) {
            if (autoHomes.containsKey(pos) && primaryContainerPos(level, pos).equals(pos)
                    && autoEligible(level, pos) && hasRoomFor(level, pos, stack)) {
                candidates.add(pos);
            }
        }
        if (candidates.isEmpty()) return null;
        Comparator<BlockPos> byDoor = Comparator.comparingDouble(pos -> pos.distSqr(doorPos));
        Comparator<BlockPos> bySpace = Comparator.<BlockPos>comparingInt(pos -> -emptySlots(level, pos))
                .thenComparing(byDoor);
        if (category == AutoCategory.MISC) return candidates.stream().min(bySpace).orElseThrow();
        List<BlockPos> related = candidates.stream()
                .filter(pos -> autoHomes.get(pos).stream().anyMatch(category::relatedTo))
                .toList();
        if (!related.isEmpty()) return related.stream().min(byDoor).orElseThrow();
        return candidates.stream()
                .min(Comparator.<BlockPos>comparingInt(pos -> autoHomes.get(pos).size()).thenComparing(bySpace))
                .orElseThrow();
    }

    /**
     * Overflow: every unlabeled container, those holding only what was learned for them (an
     * empty one counts) before those holding anything else, emptiest first within each.
     */
    private List<BlockPos> overflowOrder(ServerLevel level, ItemStack stack) {
        List<BlockPos> order = new ArrayList<>();
        Map<BlockPos, Integer> unlearned = new HashMap<>();
        Map<BlockPos, Integer> space = new HashMap<>();
        for (BlockPos pos : functionalBlocks) {
            if (isLabeled(level, pos) || !depositable(level, pos, stack)) continue;
            order.add(pos);
            unlearned.put(pos, holdsOnlyLearned(level, pos) ? 0 : 1);
            space.put(pos, emptySlots(level, pos));
        }
        order.sort(Comparator.<BlockPos>comparingInt(unlearned::get)
                .thenComparing(Comparator.<BlockPos>comparingInt(space::get).reversed())
                .thenComparingDouble(pos -> pos.distSqr(doorPos)));
        return order;
    }

    private boolean holdsOnlyLearned(ServerLevel level, BlockPos pos) {
        EnumSet<AutoCategory> learned = EnumSet.noneOf(AutoCategory.class);
        learned.addAll(autoHomes.getOrDefault(pos, EnumSet.noneOf(AutoCategory.class)));
        learned.addAll(autoSpill.getOrDefault(pos, EnumSet.noneOf(AutoCategory.class)));
        for (Held held : distinct(level, halves(level, pos))) {
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack slot = chest.peek(i);
                if (!slot.isEmpty() && !learned.contains(categoryOf(slot))) return false;
            }
        }
        return true;
    }

    private boolean hasRoomFor(ServerLevel level, BlockPos pos, ItemStack stack) {
        for (BlockPos half : halves(level, pos)) {
            if (chestCanAccept(level, half, stack)) return true;
        }
        return false;
    }

    /** Empty slots over both halves of a double chest. */
    private int emptySlots(ServerLevel level, BlockPos pos) {
        int empty = 0;
        for (Held held : distinct(level, halves(level, pos))) empty += held.inventory().emptySlots();
        return empty;
    }

    private boolean isEmptyContainer(ServerLevel level, BlockPos pos) {
        List<Held> parts = distinct(level, halves(level, pos));
        if (parts.isEmpty()) return false;
        for (Held held : parts) {
            if (!held.inventory().isEmpty()) return false;
        }
        return true;
    }

    private boolean holdsCategory(ServerLevel level, BlockPos pos, AutoCategory category) {
        for (Held held : distinct(level, halves(level, pos))) {
            StorageInventory chest = held.inventory();
            for (int i = 0; i < chest.size(); i++) {
                ItemStack slot = chest.peek(i);
                if (!slot.isEmpty() && categoryOf(slot) == category) return true;
            }
        }
        return false;
    }

    /**
     * Run with every cache rebuild: first-load homes, spill whose home is gone taking its
     * place, and spill that is empty of its category once the home has room again.
     */
    private void refreshAutoSorting(ServerLevel level) {
        if (!autoBootstrapped) {
            autoBootstrapped = true;
            if (autoHomes.isEmpty() && autoSpill.isEmpty()) bootstrapAutoHomes(level);
        }
        for (BlockPos pos : List.copyOf(autoSpill.keySet())) {
            EnumSet<AutoCategory> spill = autoSpill.get(pos);
            if (spill == null || !primaryContainerPos(level, pos).equals(pos)
                    || !autoEligible(level, pos)) continue;
            EnumSet<AutoCategory> kept = EnumSet.copyOf(spill);
            for (AutoCategory category : spill) {
                List<BlockPos> homes = learnedFor(level, autoHomes, category);
                if (homes.isEmpty()) {
                    // The home was broken or set up by the player: this spill takes over.
                    kept.remove(category);
                    learn(level, pos, category, false);
                } else if (!holdsCategory(level, pos, category)
                        && homes.stream().anyMatch(home -> emptySlots(level, home) > 0)) {
                    kept.remove(category);
                    LOGGER.debug("[StorageBuilding] auto-spill {} released at {},{},{}.",
                            category, pos.getX(), pos.getY(), pos.getZ());
                }
            }
            if (!kept.equals(spill)) writeAuto(level, pos, SPILL_KEY, kept);
        }
    }

    /**
     * A room with nothing learned yet (an existing world, or a fresh room): each category's
     * home becomes the "Anything" container holding the most of it, if enough.
     */
    private void bootstrapAutoHomes(ServerLevel level) {
        Map<AutoCategory, BlockPos> best = new EnumMap<>(AutoCategory.class);
        Map<AutoCategory, Integer> bestCount = new EnumMap<>(AutoCategory.class);
        for (BlockPos pos : functionalBlocks) {
            if (!primaryContainerPos(level, pos).equals(pos) || !autoEligible(level, pos)) continue;
            Map<AutoCategory, Integer> counts = new EnumMap<>(AutoCategory.class);
            for (Held held : distinct(level, halves(level, pos))) {
                StorageInventory chest = held.inventory();
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack slot = chest.peek(i);
                    if (!slot.isEmpty() && mayUse(level, slot)) {
                        counts.merge(categoryOf(slot), slot.getCount(), Integer::sum);
                    }
                }
            }
            for (Map.Entry<AutoCategory, Integer> entry : counts.entrySet()) {
                int count = entry.getValue();
                if (count < BOOTSTRAP_MIN_ITEMS) continue;
                BlockPos current = best.get(entry.getKey());
                int currentCount = bestCount.getOrDefault(entry.getKey(), 0);
                if (current == null || count > currentCount
                        || (count == currentCount && pos.distSqr(doorPos) < current.distSqr(doorPos))) {
                    best.put(entry.getKey(), pos);
                    bestCount.put(entry.getKey(), count);
                }
            }
        }
        best.forEach((category, pos) -> learn(level, pos, category, false));
    }

    private void learn(ServerLevel level, BlockPos pos, AutoCategory category, boolean spill) {
        String key = spill ? SPILL_KEY : HOMES_KEY;
        EnumSet<AutoCategory> categories = readAuto(level, pos, key);
        if (!categories.add(category)) return;
        writeAuto(level, pos, key, categories);
        LOGGER.debug("[StorageBuilding] auto-{} {} -> {},{},{}", spill ? "spill" : "home",
                category, pos.getX(), pos.getY(), pos.getZ());
    }

    /** Forgets what was learned for this container, both halves. */
    private void clearAuto(ServerLevel level, BlockPos pos) {
        writeAuto(level, pos, HOMES_KEY, EnumSet.noneOf(AutoCategory.class));
        writeAuto(level, pos, SPILL_KEY, EnumSet.noneOf(AutoCategory.class));
    }

    /** Both halves' saved lists together. */
    private EnumSet<AutoCategory> readAuto(ServerLevel level, BlockPos pos, String key) {
        EnumSet<AutoCategory> categories = EnumSet.noneOf(AutoCategory.class);
        for (BlockPos half : halves(level, pos)) {
            var entity = settingsEntity(level, half);
            if (entity == null) continue;
            net.minecraft.nbt.ListTag list = entity.getPersistentData()
                    .getList(key, net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < list.size(); i++) {
                try { categories.add(AutoCategory.valueOf(list.getString(i))); }
                catch (IllegalArgumentException ignored) { }
            }
        }
        return categories;
    }

    private void writeAuto(ServerLevel level, BlockPos pos, String key, EnumSet<AutoCategory> categories) {
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (AutoCategory category : categories) list.add(net.minecraft.nbt.StringTag.valueOf(category.name()));
        Map<BlockPos, EnumSet<AutoCategory>> cache = key.equals(HOMES_KEY) ? autoHomes : autoSpill;
        for (BlockPos half : halves(level, pos)) {
            var entity = settingsEntity(level, half);
            if (entity == null) continue;
            CompoundTag data = entity.getPersistentData();
            if (list.isEmpty()) {
                if (!data.contains(key)) continue;
                data.remove(key);
                cache.remove(half);
            } else {
                data.put(key, list.copy());
                cache.put(half.immutable(), EnumSet.copyOf(categories));
            }
            entity.setChanged();
            rememberSettings(half, entity);
        }
    }

    /** Both halves of a double chest are shown as one selectable container. */
    public BlockPos primaryContainerPos(ServerLevel level, BlockPos pos) {
        BlockPos partner = pairedChestPos(level, pos);
        if (partner == null || !functionalBlocks.contains(partner)) return pos;
        return pos.asLong() <= partner.asLong() ? pos : partner;
    }

    @javax.annotation.Nullable
    public static BlockPos pairedChestPos(ServerLevel level, BlockPos pos) {
        return StorageContainers.pairedChestPos(level, pos);
    }

    /** The first resource stack a withdrawal from this chest would take. */
    public ItemStack peekResourceFromChest(ServerLevel level, BlockPos pos, ResourceType type) {
        return peekResourceFromChest(level, pos, type, item -> false);
    }

    /** The first resource stack a withdrawal would take, passing over {@code skip} items. */
    public ItemStack peekResourceFromChest(ServerLevel level, BlockPos pos, ResourceType type,
                                           java.util.function.Predicate<Item> skip) {
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return ItemStack.EMPTY;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack slot = chest.peek(i);
            if (mayUse(level, slot) && matchesType(slot, type) && !skip.test(slot.getItem())) {
                return slot.copyWithCount(1);
            }
        }
        return ItemStack.EMPTY;
    }

    private ItemStack withdrawItemsFromChest(ServerLevel level, BlockPos pos, ResourceType type,
                                             int maxAmount, ItemStack existingResultType,
                                             java.util.function.Predicate<Item> skip) {
        if (maxAmount <= 0) return ItemStack.EMPTY;
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return ItemStack.EMPTY;

        ItemStack result = ItemStack.EMPTY;
        int needed = maxAmount;
        boolean changed = false;

        for (int i = 0; i < chest.size() && needed > 0; i++) {
            ItemStack slot = chest.peek(i);
            if (!mayUse(level, slot) || !matchesType(slot, type) || skip.test(slot.getItem())) continue;

            // Only take items of the same concrete stack as what we started with.
            if (!existingResultType.isEmpty()
                    && !ItemStack.isSameItemSameComponents(existingResultType, slot)) continue;
            if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, slot)) continue;

            ItemStack taken = chest.take(i, Math.min(slot.getCount(), needed));
            if (taken.isEmpty()) continue;
            if (result.isEmpty()) result = taken;
            else result.grow(taken.getCount());
            changed = true;
            needed -= taken.getCount();
        }

        if (changed) {
            invalidateContentIndex();
            if (ToolSelectionHelper.isProfessionTool(result)) markToolInventoryChanged();
            LOGGER.debug("[StorageBuilding] withdrew {}x {} from chest {} for resource {}.",
                    result.getCount(), result.getItem().getDescriptionId(), pos, type);
        }
        return result;
    }

    /** The first exact-item stack a withdrawal from this chest would take. */
    public ItemStack peekItemFromChest(ServerLevel level, BlockPos pos, ItemStack wanted) {
        StorageInventory chest = wanted.isEmpty() ? null : StorageContainers.inventory(level, pos);
        if (chest == null) return ItemStack.EMPTY;
        for (int i = 0; i < chest.size(); i++) {
            ItemStack slot = chest.peek(i);
            if (matchesWanted(level, slot, wanted)) return slot.copyWithCount(1);
        }
        return ItemStack.EMPTY;
    }

    /** Withdraws an exact item stack from one known chest only. */
    public ItemStack withdrawItemFromChest(ServerLevel level, BlockPos pos, ItemStack wanted, int maxAmount) {
        if (wanted.isEmpty() || maxAmount <= 0) return ItemStack.EMPTY;
        StorageInventory chest = StorageContainers.inventory(level, pos);
        if (chest == null) return ItemStack.EMPTY;

        ItemStack result = ItemStack.EMPTY;
        int needed = maxAmount;
        boolean changed = false;

        for (int i = 0; i < chest.size() && needed > 0; i++) {
            ItemStack slot = chest.peek(i);
            if (!matchesWanted(level, slot, wanted)) continue;
            if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(slot, result)) continue;

            ItemStack taken = chest.take(i, Math.min(slot.getCount(), needed));
            if (taken.isEmpty()) continue;
            if (result.isEmpty()) result = taken;
            else result.grow(taken.getCount());
            changed = true;
            needed -= taken.getCount();
        }

        if (changed) {
            invalidateContentIndex();
            if (ToolSelectionHelper.isProfessionTool(result)) markToolInventoryChanged();
            LOGGER.debug("[StorageBuilding] withdrew {}x {} from chest {}.",
                    result.getCount(), result.getItem().getDescriptionId(), pos);
        }
        return result;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static boolean matchesType(ItemStack stack, ResourceType type) {
        return switch (type) {
            case FOOD  -> VillageItemOwnership.isVillagerFood(stack);
            case WOOD  -> stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS);
            case SEEDS -> stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS);
            case AXE   -> stack.is(ItemTags.AXES);
            case CROP  -> stack.is(Items.WHEAT) || stack.is(Items.CARROT)
                       || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
                       || stack.is(Items.PUMPKIN) || stack.is(Items.MELON_SLICE)
                       || stack.is(Items.SUGAR_CANE);
            case RAW_MEAT -> stack.is(Items.BEEF) || stack.is(Items.PORKCHOP)
                          || stack.is(Items.CHICKEN) || stack.is(Items.MUTTON)
                          || stack.is(Items.RABBIT);
            case FUEL  -> stack.is(Items.COAL) || stack.is(Items.CHARCOAL)
                       || stack.is(ItemTags.LOGS) || stack.is(ItemTags.PLANKS);
            case STONE -> stack.is(Items.COBBLESTONE) || stack.is(Items.COBBLED_DEEPSLATE);
            case IRON_MATERIAL -> stack.is(Items.RAW_IRON) || stack.is(Items.IRON_INGOT);
            case ANY   -> !stack.isEmpty();
        };
    }

    private boolean matchesWanted(ServerLevel level, ItemStack stack, ItemStack wanted) {
        return stack.is(wanted.getItem()) && mayUse(level, stack)
                && (!VillageTokenBinding.isBound(wanted)
                    || java.util.Objects.equals(VillageTokenBinding.read(stack),
                            VillageTokenBinding.read(wanted)));
    }

    private void markToolInventoryChanged() {
        toolInventoryChangeId = nextToolInventoryChangeId++;
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
