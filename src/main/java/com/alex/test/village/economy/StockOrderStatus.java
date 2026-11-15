package com.alex.test.village.economy;

import com.alex.test.entity.NpcEntity;
import com.alex.test.network.StorageRoomPayloads;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.KitchenStations;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.blacksmith.settings.BlacksmithSettingsSchema;
import com.alex.test.village.villagers.cook.CookPlanner;
import com.alex.test.village.villagers.cook.VillageKitchens;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.cook.settings.CookSettings;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds what the Stock tab shows: each row's progress and a one-line status ("Waiting
 * for 3 Iron Ingot", "Needs a level 25 blacksmith", "Ready: take it from storage"), the
 * completed orders, and why an item cannot be ordered at all right now.
 */
public final class StockOrderStatus {
    public static final int FINE = 0, WAITING = 1, BLOCKED = 2, QUIET = 3;

    private StockOrderStatus() {}

    /**
     * The blacksmiths or cooks of a village, and the building they work in; for
     * cooks also every kitchen, worked as one ({@link VillageKitchens}).
     */
    private record Makers(int count, int onDuty, int bestLevel, @Nullable Building building,
                          @Nullable KitchenStations kitchens) {}

    public static StorageRoomPayloads.StockStatus build(ServerLevel level, @Nullable VillageManager village,
                                                        UUID frameId) {
        if (village == null) {
            return new StorageRoomPayloads.StockStatus(frameId, List.of(), List.of(), Map.of(), Map.of());
        }
        StockReserves reserves = StockReserves.get(level.getServer());
        UUID id = village.getVillageId();
        Makers smiths = makers(village, Profession.BLACKSMITH);
        Makers cooks = makers(village, Profession.COOK);
        Makers archers = makers(village, Profession.ARCHER);
        long now = level.getGameTime();

        // What the rows can draw on, used up row by row so a later order sees what an
        // earlier one leaves.
        Map<Item, Integer> available = new HashMap<>();
        List<StorageRoomPayloads.StockRowView> rows = new ArrayList<>();
        for (StockReserves.Row row : reserves.rows(id)) {
            Item item = row.item;
            Profession producer = StockReserves.producerOf(item);
            Makers makers = producer == Profession.BLACKSMITH ? smiths
                    : producer == Profession.ARCHER ? archers : cooks;
            int units = row.once() ? row.toMake() : reserves.shortfall(village, level, item);
            String status;
            int tone;
            if (row.once() && units == 0) {
                if (row.inTransit() > 0) {
                    boolean full = !storageAccepts(village, level, item);
                    status = full ? "Storage is full: there is no room to put it away" : "On the way to storage";
                    tone = full ? BLOCKED : WAITING;
                } else if (row.ready() > 0) {
                    status = "Ready: take it from storage";
                    tone = FINE;
                } else {
                    status = "Done";
                    tone = FINE;
                }
            } else if (!row.once() && units == 0) {
                status = "Stocked";
                tone = FINE;
            } else {
                String blocker = blocker(item, smiths, cooks, archers);
                String missing = missing(village, level, item, units, available);
                if (blocker != null) {
                    status = blocker;
                    tone = BLOCKED;
                } else if (missing != null) {
                    status = "Waiting for " + missing;
                    tone = WAITING;
                } else if (row.once() && row.claimedBy(null, now)) {
                    status = "Being made";
                    tone = FINE;
                } else {
                    status = row.once() ? "Next in line" : "Making more";
                    tone = row.once() ? QUIET : WAITING;
                }
                if (makers.count() == 0 && blocker == null) tone = BLOCKED;
            }
            rows.add(new StorageRoomPayloads.StockRowView(row.id, key(item), row.count(), row.once(),
                    row.forPlayer(), row.made(), row.delivered(), row.ready(),
                    reserves.villageTotal(village, level, item), clip(status, 200), tone,
                    clip(row.orderedByName(), 60)));
        }

        List<StorageRoomPayloads.StockDoneView> history = new ArrayList<>();
        for (StockReserves.Done done : reserves.history(id)) {
            history.add(new StorageRoomPayloads.StockDoneView(key(done.item()), done.count(), done.delivered(),
                    done.outcome().ordinal(), done.forPlayer(), clip(done.by(), 60),
                    (int) (done.finished() / 24000L) + 1));
        }

        Map<ResourceLocation, String> blocked = new LinkedHashMap<>();
        Map<ResourceLocation, Integer> totals = new LinkedHashMap<>();
        List<Item> catalog = new ArrayList<>();
        for (CookActivity.Recipe recipe : CookActivity.recipes()) catalog.add(recipe.result());
        catalog.addAll(StockReserves.forgeItems());
        catalog.addAll(StockReserves.archerItems());
        for (Item item : catalog) {
            String reason = blocker(item, smiths, cooks, archers);
            if (reason != null) blocked.put(key(item), clip(reason, 200));
            totals.put(key(item), reserves.villageTotal(village, level, item));
            for (Item material : unitCost(item).keySet()) {
                // Sticks and flint count what the archers would cut or sift them from.
                totals.computeIfAbsent(key(material), k -> material == BlacksmithActivity.WOOD_STAND_IN
                        ? storedWood(village, level)
                        : material == Items.STICK || material == Items.FLINT
                        ? stock(village, level, material) - makersCarry(village, material)
                        : reserves.villageTotal(village, level, material));
            }
        }
        totals.put(key(Items.RAW_IRON), reserves.villageTotal(village, level, Items.RAW_IRON));
        totals.put(key(Items.GRAVEL), reserves.villageTotal(village, level, Items.GRAVEL));
        return new StorageRoomPayloads.StockStatus(frameId, rows, history, blocked, totals);
    }

    // =========================================================================
    // Why it cannot be made
    // =========================================================================

    /** Why the village cannot make {@code item} at all right now, or null when it can. */
    @Nullable
    static String blocker(Item item, Makers smiths, Makers cooks, Makers archers) {
        Profession maker = StockReserves.producerOf(item);
        if (maker == Profession.ARCHER) {
            if (archers.count() == 0) return "No archer in the village";
            if (archers.building() == null) {
                return "Needs a fletching table at an Archery Range, or a crafting table in a storage room";
            }
            if (archers.onDuty() == 0) return "The archers are off duty";
            return null;
        }
        if (maker == Profession.BLACKSMITH) {
            BlacksmithActivity.OrderInfo info = BlacksmithActivity.orderInfo(item);
            if (info == null) return "The blacksmith does not make this";
            if (!(smiths.building() instanceof ForgeBuilding forge)) return "Needs a forge";
            if (smiths.count() == 0) return "No blacksmith in the village";
            if (smiths.bestLevel() < info.level()) return "Needs a level " + info.level() + " blacksmith";
            if (info.smithingTable() && forge.getSmithingTablePos() == null) {
                return "Needs a smithing table in the forge";
            }
            if (smiths.onDuty() == 0) return "The blacksmith is off duty";
            return null;
        }
        if (maker == Profession.COOK) {
            CookActivity.Recipe recipe = StockReserves.recipeFor(item);
            KitchenStations kitchen = cooks.kitchens();
            if (kitchen == null) return "Needs a kitchen";
            if (cooks.count() == 0) return "No cook in the village";
            if (recipe.needsFurnace()) {
                boolean heat = false;
                for (KitchenBuilding.HeatSource source : KitchenBuilding.HeatSource.values()) {
                    if (kitchen.getHeatPos(source) != null && CookPlanner.canCook(source, recipe)) heat = true;
                }
                if (!heat) return "The kitchen needs a smoker, furnace or campfire";
            } else if (kitchen.getCraftingTablePos() == null) {
                return "The kitchen needs a crafting table";
            }
            if (cooks.onDuty() == 0) return "The cook is off duty";
            return null;
        }
        return "Nobody makes this";
    }

    private static Makers makers(VillageManager village, Profession profession) {
        Building building = null;
        for (Building candidate : village.getBuildings()) {
            if (!candidate.isValid()) continue;
            if (profession == Profession.BLACKSMITH && candidate instanceof ForgeBuilding) building = candidate;
            if (profession == Profession.COOK && candidate instanceof KitchenBuilding) building = candidate;
            // Archers work at a range's fletching table, or a storage room's crafting table.
            if (profession == Profession.ARCHER
                    && (candidate instanceof com.alex.test.village.building.ArcheryRangeBuilding range
                            && range.hasFletchingTable()
                        || candidate instanceof StorageBuilding storage && storage.hasCraftingTable())) {
                building = candidate;
            }
            if (building != null) break;
        }
        int count = 0, onDuty = 0, best = 0;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || npc.getProfession() != profession) continue;
            count++;
            if (profession == Profession.ARCHER) {
                if (ProfessionSettings.of(npc, com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.SCHEMA)
                        .getBool(com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema.WORK_ENABLED)) {
                    onDuty++;
                }
            } else if (profession == Profession.BLACKSMITH) {
                best = Math.max(best, model.brain().blacksmithSkillLevel());
                if (ProfessionSettings.of(npc, BlacksmithSettingsSchema.SCHEMA)
                        .getBool(BlacksmithSettingsSchema.WORK_ENABLED)) onDuty++;
            } else {
                best = Math.max(best, model.brain().cookSkillLevel());
                if (CookSettings.of(npc).works()) onDuty++;
            }
        }
        return new Makers(count, onDuty, Math.max(1, best), building,
                profession == Profession.COOK ? VillageKitchens.of(village) : null);
    }

    // =========================================================================
    // What is missing
    // =========================================================================

    /**
     * One batch's cost of {@code item}: forge materials (wood as its stand-in), dish
     * ingredients, or an archer's recipe (four arrows at a crafting table, one bow).
     */
    public static Map<Item, Integer> unitCost(Item item) {
        if (item == Items.ARROW) {
            Map<Item, Integer> cost = new LinkedHashMap<>();
            cost.put(Items.FLINT, 1);
            cost.put(Items.FEATHER, 1);
            cost.put(Items.STICK, 1);
            return cost;
        }
        if (item == Items.BOW) {
            Map<Item, Integer> cost = new LinkedHashMap<>();
            cost.put(Items.STRING, 3);
            cost.put(Items.STICK, 3);
            return cost;
        }
        if (StockReserves.isForgeItem(item)) {
            BlacksmithActivity.OrderInfo info = BlacksmithActivity.orderInfo(item);
            return info == null ? Map.of() : info.materials();
        }
        CookActivity.Recipe recipe = StockReserves.recipeFor(item);
        return recipe == null ? Map.of() : recipe.inputsPerBatch();
    }

    /**
     * What one more batch lacks, as "3 Iron Ingot, 1 Leather", after the rows above took
     * theirs; null when storage holds enough. Then charges all {@code units} to {@code available}.
     */
    @Nullable
    private static String missing(VillageManager village, ServerLevel level, Item item, int units,
                                  Map<Item, Integer> available) {
        Map<Item, Integer> cost = unitCost(item);
        int perBatch = item == Items.ARROW ? StockReserves.ARROWS_PER_CRAFT
                : item == Items.BOW ? 1
                : StockReserves.isForgeItem(item) ? orderCount(item) : StockReserves.recipeFor(item).outputPerBatch();
        int batches = Math.max(1, (units + perBatch - 1) / perBatch);
        StringBuilder text = new StringBuilder();
        int listed = 0;
        for (Map.Entry<Item, Integer> entry : cost.entrySet()) {
            Item material = entry.getKey();
            int have = available.computeIfAbsent(material, m -> stock(village, level, m));
            int need = entry.getValue();
            if (have < need && listed < 2) {
                if (listed++ > 0) text.append(", ");
                text.append(need - have).append(' ').append(name(material));
            }
            available.put(material, Math.max(0, have - need * batches));
        }
        return listed == 0 ? null : text.toString();
    }

    private static int orderCount(Item item) {
        BlacksmithActivity.OrderInfo info = BlacksmithActivity.orderInfo(item);
        return info == null ? 1 : info.count();
    }

    /**
     * What storage and the makers' packs hold of a material. Iron counts raw iron too;
     * bowls and sticks count planks and logs; sugar counts spare sugar cane.
     */
    private static int stock(VillageManager village, ServerLevel level, Item material) {
        StockReserves reserves = StockReserves.get(level.getServer());
        if (material == BlacksmithActivity.WOOD_STAND_IN) return storedWood(village, level);
        int total = reserves.villageTotal(village, level, material) + makersCarry(village, material);
        if (material == Items.IRON_INGOT) {
            total += reserves.villageTotal(village, level, Items.RAW_IRON) + makersCarry(village, Items.RAW_IRON);
        }
        // Planks, and logs cut into four planks each: the cook's bowls, the archers' sticks.
        if (material == Items.BOWL || material == Items.STICK) {
            int planks = storedPlanks(village, level);
            int logs = Math.max(0, storedWood(village, level) - planks);
            planks += logs * com.alex.test.village.villagers.supplies.WoodCutting.PLANKS_PER_LOG;
            total += material == Items.BOWL ? planks / 3 * 4 : planks / 2 * 4;
        }
        // The archers sift gravel for flint.
        if (material == Items.FLINT) total += reserves.villageTotal(village, level, Items.GRAVEL) / 4;
        // The cook presses sugar from the cane the enchanters can spare.
        if (material == Items.SUGAR) {
            total += Math.max(0, reserves.villageTotal(village, level, Items.SUGAR_CANE)
                    - com.alex.test.village.villagers.cook.CookPlanner.caneKept(village));
        }
        return total;
    }

    private static int makersCarry(VillageManager village, Item item) {
        int total = 0;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || (npc.getProfession() != Profession.COOK
                    && npc.getProfession() != Profession.BLACKSMITH)) continue;
            for (ItemStack stack : npc.getInventory().getSlots()) {
                if (stack.getItem() == item) total += stack.getCount();
            }
        }
        return total;
    }

    private static int storedWood(VillageManager village, ServerLevel level) {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) total += storage.countWood(level);
        }
        return total;
    }

    private static int storedPlanks(VillageManager village, ServerLevel level) {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(level)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (stack.is(net.minecraft.tags.ItemTags.PLANKS)) total += stack.getCount();
                }
            }
        }
        return total;
    }

    /** Whether any storage room has a container that would take one more {@code item}. */
    private static boolean storageAccepts(VillageManager village, ServerLevel level, Item item) {
        ItemStack sample = new ItemStack(item);
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (BlockPos pos : storage.getFunctionalBlocks()) {
                if (storage.chestCanAccept(level, pos, sample)) return true;
            }
        }
        return false;
    }

    private static ResourceLocation key(Item item) {
        return BuiltInRegistries.ITEM.getKey(item);
    }

    private static String name(Item item) {
        return item == BlacksmithActivity.WOOD_STAND_IN ? "wood" : item.getDescription().getString();
    }

    private static String clip(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
