package com.alex.test.village.villagers.tradesman.settings;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.visitors.MerchantCatalog;
import com.alex.test.village.villagers.farmer.settings.FarmerPlantingResources;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.Container;
import net.neoforged.neoforge.common.Tags;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Sale choices (shared by every tradesman of a village, see
 * {@link TradesmanSaleChoices}) and the stock currently in village storage.
 */
public final class TradesmanSalePolicy {
    private static final String SALE_ITEMS = TradesmanSaleChoices.SALE_ITEMS;
    private static final String SALE_KEEP = TradesmanSaleChoices.SALE_KEEP;
    /** Set on a tradesman once its own old choices were folded into its village's. */
    private static final String MOVED_TO_VILLAGE = "sale_moved_to_village";
    /** No keep number of the item's own: the Behaviour tab's applies. */
    public static final int AUTO = -1;

    private TradesmanSalePolicy() {}

    public static boolean canSell(Item item) {
        if (item == Items.EMERALD) return false;
        ItemStack stack = new ItemStack(item);
        boolean seed = stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS)
                || stack.is(Tags.Items.SEEDS)
                || FarmerPlantingResources.isSupported(item);
        // Edible planting items such as carrots and potatoes remain sale goods.
        // Raw ore is not on any merchant's list, so it is never offered for sale here.
        return !ToolSelectionHelper.isProfessionTool(stack)
                && !com.alex.test.village.visitors.MerchantCatalog.isUnprocessedOre(stack)
                && (!seed || VillageItemOwnership.isVillagerFood(stack));
    }

    public static boolean isAllowed(NpcEntity tradesman, Item item) {
        CompoundTag choices = choices(tradesman, SALE_ITEMS);
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return !choices.contains(id) || choices.getBoolean(id);
    }

    public static void setAllowed(NpcEntity tradesman, Item item, boolean allowed) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        VillageManager village = villageOf(tradesman);
        if (village != null && tradesman.level() instanceof ServerLevel level) {
            movedToVillage(tradesman, village, level);
            TradesmanSaleChoices.get(level.getServer()).setAllowed(village.getVillageId(), id, allowed);
            return;
        }
        ownSection(tradesman, SALE_ITEMS).putBoolean(id, allowed);
    }

    /**
     * The player's own keep number for {@code item} from the Sale items tab, or
     * {@link #AUTO} when it follows the Behaviour tab. For food it is per villager.
     */
    public static int keepAmount(NpcEntity tradesman, Item item) {
        CompoundTag keeps = choices(tradesman, SALE_KEEP);
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return keeps.contains(id) ? keeps.getInt(id) : AUTO;
    }

    /** Sets the item's own keep number; {@link #AUTO} goes back to the Behaviour tab's. */
    public static void setKeepAmount(NpcEntity tradesman, Item item, int amount) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        VillageManager village = villageOf(tradesman);
        if (village != null && tradesman.level() instanceof ServerLevel level) {
            movedToVillage(tradesman, village, level);
            TradesmanSaleChoices store = TradesmanSaleChoices.get(level.getServer());
            if (amount == AUTO) store.clearKeep(village.getVillageId(), id);
            else store.setKeep(village.getVillageId(), id, clampKeep(item, amount));
            return;
        }
        if (amount == AUTO) ownSection(tradesman, SALE_KEEP).remove(id);
        else ownSection(tradesman, SALE_KEEP).putInt(id, clampKeep(item, amount));
    }

    /**
     * {@code section} of the choices this tradesman follows: its village's,
     * or, when it has no village, the ones it keeps itself.
     */
    private static CompoundTag choices(NpcEntity tradesman, String section) {
        VillageManager village = villageOf(tradesman);
        if (village != null && tradesman.level() instanceof ServerLevel level) {
            movedToVillage(tradesman, village, level);
            TradesmanSaleChoices store = TradesmanSaleChoices.get(level.getServer());
            return section.equals(SALE_ITEMS)
                    ? store.allowed(village.getVillageId()) : store.keeps(village.getVillageId());
        }
        return tradesman.getBrainData().getCompound(TradesmanSettingsSchema.SCHEMA.tagName()).getCompound(section);
    }

    /**
     * Choices used to live on each tradesman. The first time one is asked,
     * its own are folded into its village's (the safer choice wins on a clash)
     * and marked moved. They stay in its brain data untouched, as a fallback
     * for a tradesman that loses its village.
     */
    private static void movedToVillage(NpcEntity tradesman, VillageManager village, ServerLevel level) {
        CompoundTag own = tradesman.getBrainData().getCompound(TradesmanSettingsSchema.SCHEMA.tagName());
        if (own.getBoolean(MOVED_TO_VILLAGE)) return;
        TradesmanSaleChoices.get(level.getServer())
                .merge(village.getVillageId(), own.getCompound(SALE_ITEMS), own.getCompound(SALE_KEEP));
        ownSettings(tradesman).putBoolean(MOVED_TO_VILLAGE, true);
    }

    private static CompoundTag ownSettings(NpcEntity tradesman) {
        CompoundTag brain = tradesman.getBrainData();
        String key = TradesmanSettingsSchema.SCHEMA.tagName();
        if (!brain.contains(key, Tag.TAG_COMPOUND)) brain.put(key, new CompoundTag());
        return brain.getCompound(key);
    }

    private static CompoundTag ownSection(NpcEntity tradesman, String section) {
        CompoundTag settings = ownSettings(tradesman);
        if (!settings.contains(section, Tag.TAG_COMPOUND)) settings.put(section, new CompoundTag());
        return settings.getCompound(section);
    }

    /**
     * How many of {@code item} storage keeps back from sale. The item's own
     * number wins over the Behaviour tab's; food counts per villager and never
     * drops below 16 each. The storage room's Stock tab ({@code stockHeld})
     * still wins when it holds back more.
     */
    public static int keptBack(boolean food, int ownKeep, int itemKeepSetting,
                               int foodPerVillagerSetting, int villagers, int stockHeld) {
        int keep;
        if (food) {
            int perVillager = Math.max(TradesmanSettingsSchema.MIN_FOOD_PER_VILLAGER,
                    ownKeep != AUTO ? ownKeep : foodPerVillagerSetting);
            keep = perVillager * Math.max(1, villagers);
        } else {
            keep = ownKeep != AUTO ? ownKeep : itemKeepSetting;
        }
        return Math.max(stockHeld, keep);
    }

    /** Food: 16 to 64 per villager. Anything else: 0 to 2048 in storage. */
    public static int clampKeep(Item item, int amount) {
        return VillageItemOwnership.isVillagerFood(new ItemStack(item))
                ? Math.clamp(amount, TradesmanSettingsSchema.MIN_FOOD_PER_VILLAGER,
                        TradesmanSettingsSchema.MAX_FOOD_PER_VILLAGER)
                : Math.clamp(amount, 0, TradesmanSettingsSchema.MAX_ITEM_KEEP);
    }

    @Nullable
    public static VillageManager villageOf(NpcEntity tradesman) {
        if (!(tradesman.level() instanceof ServerLevel level) || tradesman.getVillageLink() == null) return null;
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .get(tradesman.getVillageLink().villageId());
        if (village != null) return village;
        return VillageManager.INSTANCE.getVillageId().equals(tradesman.getVillageLink().villageId())
                ? VillageManager.INSTANCE : null;
    }

    public static Map<Item, Integer> storedSaleItems(NpcEntity tradesman) {
        VillageManager village = villageOf(tradesman);
        if (village == null || !(tradesman.level() instanceof ServerLevel level)) return Map.of();
        return storedSaleItems(level, village);
    }

    public static Map<Item, Integer> storedSaleItems(ServerLevel level, VillageManager village) {
        Map<Item, Integer> totals = new HashMap<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(level)) {
                for (int slot = 0; slot < chest.size(); slot++) {
                    ItemStack stack = chest.peek(slot);
                    // Only goods a merchant could ask for: dirt the miner dug
                    // through is stock, but never something to sell.
                    if (VillageItemOwnership.mayTrade(stack, village) && canSell(stack.getItem())
                            && MerchantCatalog.mightAskFor(stack.getItem())) {
                        totals.merge(stack.getItem(), stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        return totals;
    }

    /** The player set SELL/KEEP or a keep number for {@code item} on the Sale items tab. */
    public static boolean hasChoice(NpcEntity tradesman, Item item) {
        String id = BuiltInRegistries.ITEM.getKey(item).toString();
        return choices(tradesman, SALE_ITEMS).contains(id) || choices(tradesman, SALE_KEEP).contains(id);
    }

    /**
     * Whether the Sale items tab may change {@code id}: any good a merchant
     * might ask for, the same rows the tab lists. Anything else is never sold,
     * so a switch for it would do nothing.
     */
    public static boolean isListable(NpcEntity tradesman, ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item != null && item != Items.AIR && canSell(item) && MerchantCatalog.mightAskFor(item);
    }
}
