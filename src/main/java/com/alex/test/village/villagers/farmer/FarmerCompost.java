package com.alex.test.village.villagers.farmer;

import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composting in a composter near the fields. Only surplus leaves storage:
 * a stack of each planting item and sixteen of everything else stay behind,
 * and nothing the cook cooks with or anyone eats is ever composted (bar the
 * poisonous potato). Cheap scraps go first: spare seeds, then saplings, then
 * flowers, leaves and the like. Every item takes the same fixed time.
 */
public final class FarmerCompost {

    /** Time to drop one item in. Fixed: skill does not change it. */
    public static final int TICKS_PER_ITEM = 12;
    /** A composter at level 7 turns ready after 20 ticks; wait a little longer. */
    public static final int READY_WAIT_TICKS = 24;
    /** Items fetched for one trip; a trip must finish inside the job's reservation. */
    public static final int MAX_ITEMS_PER_TRIP = 32;
    /** How far outside a field or patch a composter still counts as part of it. */
    private static final int NEAR_FARM = 8;
    private static final int PLANTING_ITEM_KEEP = 64;
    private static final int DEFAULT_KEEP = 16;

    /**
     * Compostable, but worth more as what they are: rare seeds, the cook's
     * cake and the blocks the farm itself grows or a player builds with.
     */
    private static final Set<Item> NEVER = Set.of(
            Items.TORCHFLOWER_SEEDS, Items.PITCHER_POD, Items.TORCHFLOWER, Items.PITCHER_PLANT,
            Items.CAKE, Items.PUMPKIN, Items.CARVED_PUMPKIN, Items.MELON, Items.HAY_BLOCK,
            Items.WHEAT, Items.SUGAR_CANE, Items.COCOA_BEANS, Items.NETHER_WART,
            Items.BROWN_MUSHROOM, Items.RED_MUSHROOM, Items.KELP, Items.DRIED_KELP,
            Items.DRIED_KELP_BLOCK);

    private FarmerCompost() {}

    public record Choice(Item item, int surplus, int priority) {}

    /** Whether a farmer may ever put {@code item} in a composter. */
    public static boolean mayCompost(Item item) {
        ItemStack stack = new ItemStack(item);
        if (ComposterBlock.getValue(stack) <= 0f) return false;
        if (NEVER.contains(item) || CookActivity.INGREDIENTS.contains(item)) return false;
        // raw food data: Minecraft food is never composted, whether or not villagers eat it.
        return item == Items.POISONOUS_POTATO || !stack.has(DataComponents.FOOD);
    }

    /** Lower goes first: spare seeds, then saplings, then everything else. */
    private static int priority(Item item) {
        if (CropDefinitionRegistry.byPlantingItem(item) != null
                || new ItemStack(item).is(ItemTags.VILLAGER_PLANTABLE_SEEDS)) return 0;
        if (new ItemStack(item).is(ItemTags.SAPLINGS)) return 1;
        return 2;
    }

    private static int keep(Item item) {
        return CropDefinitionRegistry.byPlantingItem(item) != null ? PLANTING_ITEM_KEEP : DEFAULT_KEEP;
    }

    /**
     * The cheapest compostable item storage holds more of than it keeps.
     * Renamed or otherwise customised stacks are never counted.
     */
    @Nullable
    public static Choice pickSurplus(ServerLevel level, VillageManager village, Map<Item, Integer> claimed) {
        Map<Item, Integer> totals = new HashMap<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (StorageInventory inventory : storage.inventories(level)) {
                for (int slot = 0; slot < inventory.size(); slot++) {
                    ItemStack stack = inventory.peek(slot);
                    if (stack.isEmpty() || isCustomised(stack)
                            || !storage.mayUseAsMaterial(level, stack) || !mayCompost(stack.getItem())) continue;
                    totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                }
            }
        }
        Choice best = null;
        for (Map.Entry<Item, Integer> entry : totals.entrySet()) {
            Item item = entry.getKey();
            int surplus = entry.getValue() - keep(item) - claimed.getOrDefault(item, 0);
            if (surplus <= 0) continue;
            int priority = priority(item);
            if (best == null || priority < best.priority()
                    || priority == best.priority() && surplus > best.surplus()) {
                best = new Choice(item, surplus, priority);
            }
        }
        return best;
    }

    /**
     * Renamed, enchanted or given lore by someone. The village binding is not
     * customisation: every item a villager made carries it (custom data and
     * the green item name), so checking for any component at all skipped
     * every seed in storage and nothing was ever composted. Lore counts only
     * with lines in it: every item carries an empty lore by default, so
     * asking whether it has lore at all skipped every stack the same way.
     */
    private static boolean isCustomised(ItemStack stack) {
        return stack.has(DataComponents.CUSTOM_NAME) || stack.isEnchanted()
                || !stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY).lines().isEmpty();
    }

    /**
     * Items it takes on average to fill a composter at {@code fill} to the
     * top, the vanilla way: the first item into an empty composter always
     * adds a layer, every other one only at the item's chance. Seeds into an
     * empty one: 1 + 6 / 0.3 = 21.
     */
    public static int itemsToFill(int fill, Item item) {
        float chance = ComposterBlock.getValue(new ItemStack(item));
        if (chance <= 0f) return 0;
        int layers = Math.max(0, ComposterBlock.MAX_LEVEL - fill);
        if (layers == 0) return 1;
        int sure = fill == 0 ? 1 : 0;
        int needed = sure + (int) Math.ceil((layers - sure) / chance);
        return Math.max(1, Math.min(MAX_ITEMS_PER_TRIP, needed));
    }

    public static int fill(BlockState state) {
        return state.is(Blocks.COMPOSTER) ? state.getValue(ComposterBlock.LEVEL) : -1;
    }

    /** A hopper under the composter empties it; the farmer leaves that to the hopper. */
    public static boolean hopperBelow(ServerLevel level, BlockPos composter) {
        return level.getBlockState(composter.below()).is(Blocks.HOPPER);
    }

    /**
     * Composters within reach of any of {@code areas} (each a set of farm
     * tiles). Uses vanilla's composter index, and only around areas whose
     * chunks are all loaded so the index never reads from disk.
     */
    public static Set<BlockPos> findNear(ServerLevel level, Collection<? extends Collection<BlockPos>> areas) {
        Set<BlockPos> found = new LinkedHashSet<>();
        PoiManager poi = level.getPoiManager();
        for (Collection<BlockPos> area : areas) {
            if (area.isEmpty()) continue;
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : area) {
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
                minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
                minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
            }
            BlockPos center = new BlockPos((minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2);
            // The search is a circle: reach the corners of the box around the
            // area, not just its sides, or composters by a corner were missed.
            int radius = (int) Math.ceil(Math.hypot((maxX - minX) / 2.0 + NEAR_FARM,
                    (maxZ - minZ) / 2.0 + NEAR_FARM)) + 1;
            // Vanilla's index reads one chunk further out than the radius.
            int reach = radius + 16;
            if (!level.hasChunkAt(center.offset(-reach, 0, -reach))
                    || !level.hasChunkAt(center.offset(reach, 0, -reach))
                    || !level.hasChunkAt(center.offset(-reach, 0, reach))
                    || !level.hasChunkAt(center.offset(reach, 0, reach))) continue;
            int lowY = minY - 4, highY = maxY + 4;
            int lowX = minX - NEAR_FARM, highX = maxX + NEAR_FARM;
            int lowZ = minZ - NEAR_FARM, highZ = maxZ + NEAR_FARM;
            poi.findAll(type -> type.is(PoiTypes.FARMER), pos -> true, center, radius,
                            PoiManager.Occupancy.ANY)
                    .filter(pos -> pos.getY() >= lowY && pos.getY() <= highY
                            && pos.getX() >= lowX && pos.getX() <= highX
                            && pos.getZ() >= lowZ && pos.getZ() <= highZ
                            && level.getBlockState(pos).is(Blocks.COMPOSTER))
                    .forEach(pos -> found.add(pos.immutable()));
        }
        return found;
    }

    /** Sorted nearest first. */
    public static List<BlockPos> nearestFirst(Collection<BlockPos> composters, BlockPos from) {
        return composters.stream()
                .sorted((a, b) -> Double.compare(a.distSqr(from), b.distSqr(from)))
                .toList();
    }
}
