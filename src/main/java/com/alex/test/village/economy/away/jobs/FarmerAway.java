package com.alex.test.village.economy.away.jobs;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.economy.AwayClock;
import com.alex.test.village.economy.away.AwayContext;
import com.alex.test.village.economy.away.AwayFarm;
import com.alex.test.village.economy.away.AwayJob;
import com.alex.test.village.economy.away.AwayXp;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.FarmerAxeUtils;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.FarmerHoeUtils;
import com.alex.test.village.villagers.farmer.FarmerInventory;
import com.alex.test.village.villagers.farmer.FarmerShearsUtils;
import com.alex.test.village.villagers.farmer.behavior.GetFarmerAxeActivity;
import com.alex.test.village.villagers.farmer.behavior.GetHoeActivity;
import com.alex.test.village.villagers.farmer.behavior.GetShearsActivity;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.CropMaturity;
import com.alex.test.village.villagers.farmer.crops.CropPlantingStrategy;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.ToolSpeed;
import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A farmer's away day (round 3 of the plan), on the village's shared field
 * ({@link AwayFarm}): the farmers take their turns in order, each with its
 * own work hours, so what one harvested is gone for the next.
 *
 * <p>Live rules it copies, and where they live:
 * <ul>
 *   <li>Time: the day's work hours at {@code AwayClock.AWAY_PACE} (80%). Each job the live time at the
 *       farmer's skill and tool ({@code FarmerBalance.plantHarvestTicks}, {@code tillTicks}): the hoe
 *       paces harvest and planting, the axe the fruit, the shears the picks, cane goes by hand
 *       ({@code FarmerActivity.paceTool}); a share of a deposit walk per item ({@code DEPOSIT_TRIP_TICKS}).</li>
 *   <li>Order: plots left empty first (live RESTORE), then ripe crops (each replanted right after),
 *       melons and pumpkins, cane, cocoa, berries, glow berries.</li>
 *   <li>Drops: the block's own loot ({@code FarmerActivity.harvestBlock}), averaged, plus the skill's
 *       extra food on the first food drop ({@code extraFoodChance}, {@code eligibleBonusProduct}).
 *       Berries: 1 + 0 or 1 + 1 when full, plus the extra food chance ({@code pickBerries}).</li>
 *   <li>Soil: back to dirt after a harvest unless it keeps ({@code soilRetentionChance}, as an
 *       average); the hoe tills it again ({@code FarmerActivity.till}, needs water near, one wear a
 *       till); with no hoe or Tilling off it stays dirt. A tiller (Planting off, Tilling on) re-tills
 *       dirt plots for the planters ({@code AwayFarm.plantersSow}) and plants nothing; a planter leaves
 *       them to it while it keeps up ({@code AwayFarm.leftToTillers}, the live 4 plots per tiller).</li>
 *   <li>Replant: Planting on and the crop on Replant ({@code FarmerSettings.shouldPlant}); the seed from
 *       the pack, else storage up to 16 ({@code FarmerInventory.PLANTING_RESERVE_PER_CROP}); a plot keeps
 *       its crop, a blank plot takes the default crop the farmer carries ({@code FarmerManager.desiredCrop}).
 *       Leave: never harvested. Harvest only: harvested, never replanted.</li>
 *   <li>Tools: the axe for fruit (a wear each), the shears for cocoa, berries and glow berries (a wear
 *       each, Allow Shears), a spare from storage or a crafted one as live ({@code AwayTools}).</li>
 *   <li>Items: into the pack; a full pack goes to storage as a deposit ({@code DepositActivity}'s keep
 *       rules); with no room anywhere that crop stops and the rest are kept as owed fractions.</li>
 *   <li>XP: each till, plant, harvest and pick ({@link AwayXp}).</li>
 * </ul>
 * Not done away: bone meal, compost, seeds crafted from melons and pumpkins,
 * new berry bushes, new tilling to grow the field.
 */
public final class FarmerAway implements AwayJob {

    public static final FarmerAway INSTANCE = new FarmerAway();

    private static final Logger LOGGER = LogUtils.getLogger();

    /** A walk to storage and back, shared by the items one load carries (as the old calculator's farmer). */
    static final double DEPOSIT_TRIP_TICKS = 200.0;
    /** From this level berries are picked low and quicker ({@code FarmerActivity.SKILLED_PICK_LEVEL}). */
    static final int SKILLED_PICK_LEVEL = 50;
    /** Fractions of items owed per item id: the old calculator's key, the same meaning. */
    static final String TAG_CARRY = "FarmerOfflineYieldRemainders";
    /** Toward the next tile whose soil goes back to dirt. */
    static final String TAG_SOIL = "FarmerAwaySoilCarry";
    /** The live list that gets the skill's extra food, first one found ({@code eligibleBonusProduct}). */
    private static final List<Item> BONUS_FOOD = List.of(Items.WHEAT, Items.CARROT, Items.POTATO, Items.BEETROOT,
            Items.PUMPKIN, Items.MELON_SLICE, Items.COCOA_BEANS);

    private FarmerAway() {}

    @Override
    @Nullable
    public String cannotWork(AwayContext ctx) {
        FarmerSettings settings = FarmerSettings.of(ctx.npc());
        if (!settings.works()) return "work off";
        if (ctx.farm().isEmpty()) return "no field loaded";
        if (settings.boneMealOnly()) return "only bone meal, not done away";
        if (settings.tillsForVillage() && settings.turnOffHarvesting() && !ctx.farm().hasPlanters()) {
            return "no planters to till for";
        }
        return null;
    }

    @Override
    public void workDay(AwayContext ctx) {
        new Shift(ctx).run();
    }

    /** One farmer's day. */
    private static final class Shift {
        private final AwayContext ctx;
        private final ServerLevel level;
        private final NpcEntity npc;
        private final InventoryManager pack;
        private final FarmerSettings settings;
        private final AwayFarm farm;
        private double ticksLeft;
        private boolean outOfTime;
        private int jobs;
        private final CompoundTag carry;
        private double soilCarry;
        /** Items with no room in the pack or storage: crops that drop them stop for the day. */
        private final Set<Item> noRoom = new HashSet<>();
        /** Seeds storage turned out to have none of today. */
        private final Set<Item> storageOut = new HashSet<>();
        private boolean hoeTried;
        private boolean axeTried;
        private boolean shearsTried;
        /** Whole items into the pack today, and seeds planted, for the net made / used. */
        private final Map<Item, Integer> gross = new LinkedHashMap<>();
        private final Map<Item, Integer> planted = new LinkedHashMap<>();
        private int harvests;
        private int plants;
        private int tills;
        private int toDirt;
        private int fruits;
        private int cuts;
        private int picks;
        private int noSeed;
        private int noHoe;
        private int leftToTiller;

        Shift(AwayContext ctx) {
            this.ctx = ctx;
            this.level = ctx.level;
            this.npc = ctx.npc();
            this.pack = npc.getInventory();
            this.settings = FarmerSettings.of(npc);
            this.farm = ctx.farm();
            this.ticksLeft = ctx.day.workTicks() * AwayClock.AWAY_PACE;
            CompoundTag data = npc.getBrainData();
            this.carry = data.getCompound(TAG_CARRY).copy();
            this.soilCarry = data.getDouble(TAG_SOIL);
        }

        void run() {
            boolean harvesting = !settings.turnOffHarvesting();
            for (AwayFarm.Plot plot : farm.plots()) {
                if (outOfTime) break;
                if (plot.empty()) restore(plot);
            }
            if (harvesting) {
                for (AwayFarm.Plot plot : farm.plots()) {
                    if (outOfTime) break;
                    if (plot.ripe()) harvest(plot);
                }
                for (AwayFarm.Plot plot : farm.plots()) {
                    if (outOfTime) break;
                    if (plot.hasFruit()) cutFruit(plot);
                }
                for (AwayFarm.Cane stack : farm.cane()) {
                    if (outOfTime) break;
                    if (stack.height() >= 2) cutCane(stack);
                }
            }
            for (AwayFarm.Pod pod : farm.pods()) {
                if (outOfTime) break;
                if (pod.ripe() && harvesting) pickPod(pod);
                else if (pod.empty()) plantPod(pod);
            }
            if (harvesting) {
                for (AwayFarm.Bush bush : farm.bushes()) {
                    if (outOfTime) break;
                    if (bush.age() >= 2) pickBush(bush);
                }
                for (AwayFarm.Glow glow : farm.glows()) {
                    if (outOfTime) break;
                    if (glow.ripe()) pickGlow(glow);
                }
            }
            finish();
        }

        // ---------------------------------------------------------------------
        // Farmland
        // ---------------------------------------------------------------------

        /** An empty plot: tilled again if it went back to dirt, then planted. */
        private void restore(AwayFarm.Plot plot) {
            if (settings.turnOffPlanting()) {
                if (settings.tillsForVillage()) tillForVillage(plot);
                return;
            }
            CropDefinition crop = plot.crop() != null ? plot.crop() : blankCrop();
            if (crop == null) return;
            Item seed = crop.representativePlantingItem().orElse(null);
            if (seed == null || !settings.shouldPlant(seed)) return;
            BlockState plantedState = plantedState(seed, crop);
            if (plantedState == null) return;
            // Soil gone back to dirt: water near, Tilling on and a hoe, or it stays dirt (checked before any walk for seed).
            if (!plot.tilled()) {
                if (!plot.wet() || !settings.tilling()) return;
                if (farm.leftToTillers(plot, ctx)) {
                    leftToTiller++;
                    return;
                }
                if (hoeSlot() < 0) {
                    noHoe++;
                    return;
                }
            }
            if (!seedAtHand(seed)) {
                noSeed++;
                return;
            }
            if (!plot.tilled()) {
                int hoe = FarmerHoeUtils.findBestUsableHoeSlot(pack, settings);
                if (hoe < 0 || !spend(FarmerBalance.tillTicks(ToolSpeed.of(pack.getSlot(hoe)), level()))) return;
                FarmerHoeUtils.damageHoeAfterUse(level, pack, hoe);
                plot.till();
                tills++;
                job();
            }
            if (!spend(FarmerBalance.plantHarvestTicks(hoeSpeed(), level())) || !takeSeed(seed)) return;
            plot.plant(plantedState, crop);
            plants++;
            job();
        }

        /**
         * A tiller's plot: soil gone back to dirt tilled again for the village's
         * planters, nothing planted (live {@code FarmerManager.tillCandidates} for
         * a tiller). Its crop must be one a planter sows and not on Leave on the
         * tiller's own tab; a blank plot waits for a planter. No new soil away.
         */
        private void tillForVillage(AwayFarm.Plot plot) {
            if (plot.tilled() || !plot.wet() || plot.crop() == null) return;
            Item seed = plot.crop().representativePlantingItem().orElse(null);
            if (seed == null || !settings.isPlantEnabled(seed) || !farm.plantersSow(seed, ctx)) return;
            int hoe = hoeSlot();
            if (hoe < 0) {
                noHoe++;
                return;
            }
            if (!spend(FarmerBalance.tillTicks(ToolSpeed.of(pack.getSlot(hoe)), level()))) return;
            FarmerHoeUtils.damageHoeAfterUse(level, pack, hoe);
            plot.till();
            tills++;
            job();
        }

        /** A ripe crop: broken, the soil may go back to dirt, replanted at once. */
        private void harvest(AwayFarm.Plot plot) {
            CropDefinition crop = plot.crop();
            Item seed = crop == null ? null : crop.representativePlantingItem().orElse(null);
            if (seed == null || !settings.isPlantEnabled(seed)) return;
            Map<Item, Double> drops = farm.drops(plot.state(), plot.pos, ItemStack.EMPTY, npc);
            if (blocked(drops)) return;
            if (!spend(FarmerBalance.plantHarvestTicks(hoeSpeed(), level()) + depositShare(drops))) return;
            give(drops, true);
            plot.harvest();
            harvests++;
            job();
            if (plot.tilled()) {
                soilCarry += 1.0 - FarmerBalance.soilRetentionChance(level());
                if (soilCarry >= 1.0) {
                    soilCarry -= 1.0;
                    plot.toDirt();
                    toDirt++;
                }
            }
            restore(plot);
        }

        /** A melon or pumpkin, cut with the axe; the stem stays. */
        private void cutFruit(AwayFarm.Plot plot) {
            CropDefinition crop = plot.crop();
            Item seed = crop == null ? null : crop.representativePlantingItem().orElse(null);
            if (seed == null || !settings.isPlantEnabled(seed) || plot.fruit() == null || plot.fruitPos() == null) return;
            int axe = axeSlot();
            if (axe < 0) return;
            ItemStack tool = pack.getSlot(axe);
            Map<Item, Double> drops = farm.drops(plot.fruit(), plot.fruitPos(), tool, npc);
            if (blocked(drops)) return;
            if (!spend(FarmerBalance.plantHarvestTicks(ToolSpeed.of(tool), level(), VillagerAnimationState.HARVEST_CHOP)
                    + depositShare(drops))) return;
            give(drops, true);
            FarmerAxeUtils.damageAfterUse(level, pack, axe);
            plot.cutFruit();
            fruits++;
            job();
        }

        // ---------------------------------------------------------------------
        // Cane and perennials
        // ---------------------------------------------------------------------

        /** Every block above the base, by hand; one job however tall. */
        private void cutCane(AwayFarm.Cane stack) {
            CropDefinition cane = CropDefinitionRegistry.byCropBlock(Blocks.SUGAR_CANE);
            Item item = cane == null ? null : cane.representativePlantingItem().orElse(null);
            if (item == null || !settings.isPlantEnabled(item) || !stack.onSand(level)) return;
            Map<Item, Double> drops = Map.of(Items.SUGAR_CANE, (double) (stack.height() - 1));
            if (blocked(drops)) return;
            if (!spend(FarmerBalance.plantHarvestTicks(1.0, level(), VillagerAnimationState.PICK) + depositShare(drops))) {
                return;
            }
            give(drops, false);
            stack.cut();
            cuts++;
            job();
        }

        private void pickPod(AwayFarm.Pod pod) {
            if (!settings.isPlantEnabled(Items.COCOA_BEANS)) return;
            int shears = shearsSlot();
            if (shears < 0) return;
            Map<Item, Double> drops = farm.drops(pod.state(), pod.pos, ItemStack.EMPTY, npc);
            if (blocked(drops)) return;
            if (!spend(FarmerBalance.plantHarvestTicks(ToolSpeed.of(pack.getSlot(shears)), level(),
                    VillagerAnimationState.PICK) + depositShare(drops))) return;
            give(drops, true);
            FarmerShearsUtils.damageAfterUse(level, pack, shears);
            pod.harvest();
            picks++;
            job();
            plantPod(pod);
        }

        /** A bean back on the log, Planting on and cocoa on Replant. */
        private void plantPod(AwayFarm.Pod pod) {
            if (!settings.shouldPlant(Items.COCOA_BEANS) || !seedAtHand(Items.COCOA_BEANS)) return;
            int shears = FarmerShearsUtils.findBestUsableShearsSlot(pack);
            double speed = shears >= 0 ? ToolSpeed.of(pack.getSlot(shears)) : 1.0;
            if (!spend(FarmerBalance.plantHarvestTicks(speed, level(), VillagerAnimationState.PICK))
                    || !takeSeed(Items.COCOA_BEANS)) return;
            pod.plant();
            plants++;
            job();
        }

        private void pickBush(AwayFarm.Bush bush) {
            if (!settings.isPlantEnabled(Items.SWEET_BERRIES)) return;
            int shears = shearsSlot();
            if (shears < 0) return;
            double count = 1.5 + (bush.age() >= 3 ? 1.0 : 0.0) + FarmerBalance.extraFoodChance(level());
            Map<Item, Double> drops = Map.of(Items.SWEET_BERRIES, count);
            if (blocked(drops)) return;
            VillagerAnimationState clip = level() >= SKILLED_PICK_LEVEL
                    ? VillagerAnimationState.PICK_LOW : VillagerAnimationState.PICK_BUSH;
            if (!spend(FarmerBalance.plantHarvestTicks(ToolSpeed.of(pack.getSlot(shears)), level(), clip)
                    + depositShare(drops))) return;
            give(drops, false);
            FarmerShearsUtils.damageAfterUse(level, pack, shears);
            bush.pick();
            picks++;
            job();
        }

        private void pickGlow(AwayFarm.Glow glow) {
            if (!settings.isPlantEnabled(Items.GLOW_BERRIES)) return;
            int shears = shearsSlot();
            if (shears < 0) return;
            Map<Item, Double> drops = Map.of(Items.GLOW_BERRIES, 1.0);
            if (blocked(drops)) return;
            if (!spend(FarmerBalance.plantHarvestTicks(ToolSpeed.of(pack.getSlot(shears)), level(),
                    VillagerAnimationState.PICK) + depositShare(drops))) return;
            give(drops, false);
            FarmerShearsUtils.damageAfterUse(level, pack, shears);
            glow.pick();
            picks++;
            job();
        }

        // ---------------------------------------------------------------------
        // Seeds, tools, items
        // ---------------------------------------------------------------------

        /** Live's blank-plot crop: a farmland crop the farmer carries and may plant, the default first. Not a stem (no fruit cell). */
        @Nullable
        private CropDefinition blankCrop() {
            CropDefinition chosen = null;
            CropDefinition fallback = CropDefinitionRegistry.defaultFarmlandCrop();
            for (CropDefinition definition : CropDefinitionRegistry.all()) {
                if (!(definition.planting() instanceof CropPlantingStrategy.Farmland)
                        || definition.maturity() instanceof CropMaturity.AttachedFruit) continue;
                Item seed = definition.representativePlantingItem().orElse(null);
                if (seed == null || !settings.shouldPlant(seed) || !pack.contains(seed)) continue;
                if (chosen == null || definition == fallback) chosen = definition;
            }
            return chosen;
        }

        /** The block the seed places, when it is this crop's. */
        @Nullable
        private static BlockState plantedState(Item seed, CropDefinition crop) {
            if (!(seed instanceof BlockItem blockItem)) return null;
            BlockState state = blockItem.getBlock().defaultBlockState();
            return crop.matchesCropBlock(state.getBlock()) ? state : null;
        }

        /** A seed in the pack, fetched from storage first (up to a reserve) when it has none. */
        private boolean seedAtHand(Item seed) {
            if (!pack.contains(seed)) fetch(seed);
            return pack.contains(seed);
        }

        /** Some of it in storage the farmer may take, and time for the walk. */
        private boolean canFetch(Item seed) {
            if (ticksLeft < DEPOSIT_TRIP_TICKS || storageOut.contains(seed)) return false;
            if (ctx.countInStorage(seed, Profession.FARMER) > 0) return true;
            storageOut.add(seed);
            return false;
        }

        /** One seed out of the pack. */
        private boolean takeSeed(Item seed) {
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.isEmpty() || !stack.is(seed) || !pack.mayUse(stack)) continue;
                pack.reduceSlot(slot, 1);
                planted.merge(seed, 1, Integer::sum);
                return true;
            }
            return false;
        }

        private void fetch(Item seed) {
            if (!canFetch(seed) || !spend(DEPOSIT_TRIP_TICKS)) return;
            int want = FarmerInventory.keepsPlantingReserve(seed) ? FarmerInventory.PLANTING_RESERVE_PER_CROP : 1;
            List<ItemStack> fetched = ctx.takeFromStorage(seed, want, Profession.FARMER);
            if (fetched.isEmpty()) storageOut.add(seed);
            for (ItemStack got : fetched) {
                if (!pack.addItem(got)) ctx.store(got);
            }
        }

        private double hoeSpeed() {
            int slot = FarmerHoeUtils.findBestUsableHoeSlot(pack, settings);
            return slot >= 0 ? ToolSpeed.of(pack.getSlot(slot)) : 1.0;
        }

        /** The hoe for tilling: the pack's best, else one spare or crafted (tried once a day). */
        private int hoeSlot() {
            int slot = FarmerHoeUtils.findBestUsableHoeSlot(pack, settings);
            if (slot >= 0 || hoeTried) return slot;
            hoeTried = true;
            return ctx.restock(GetHoeActivity.HOE_REQUIREMENT) ? FarmerHoeUtils.findBestUsableHoeSlot(pack, settings) : -1;
        }

        private int axeSlot() {
            int slot = FarmerAxeUtils.findBestUsableAxeSlot(pack, settings);
            if (slot >= 0 || axeTried) return slot;
            axeTried = true;
            return ctx.restock(GetFarmerAxeActivity.AXE_REQUIREMENT) ? FarmerAxeUtils.findBestUsableAxeSlot(pack, settings) : -1;
        }

        private int shearsSlot() {
            if (!settings.allowShears()) return -1;
            int slot = FarmerShearsUtils.findBestUsableShearsSlot(pack);
            if (slot >= 0 || shearsTried) return slot;
            shearsTried = true;
            return ctx.restock(GetShearsActivity.SHEARS_REQUIREMENT) ? FarmerShearsUtils.findBestUsableShearsSlot(pack) : -1;
        }

        /** A drop has had no room today: that crop waits. */
        private boolean blocked(Map<Item, Double> drops) {
            for (Item item : drops.keySet()) if (noRoom.contains(item)) return true;
            return false;
        }

        private double depositShare(Map<Item, Double> drops) {
            double items = 0.0;
            for (double count : drops.values()) items += count;
            return items * DEPOSIT_TRIP_TICKS / FarmerBalance.carryItems(level());
        }

        /** The drops into the pack, fractions owed; the extra food on the first food drop. */
        private void give(Map<Item, Double> drops, boolean bonus) {
            Item extra = null;
            if (bonus) {
                for (Item item : drops.keySet()) {
                    if (BONUS_FOOD.contains(item)) {
                        extra = item;
                        break;
                    }
                }
            }
            for (Map.Entry<Item, Double> drop : drops.entrySet()) {
                double amount = drop.getValue() + (drop.getKey() == extra ? FarmerBalance.extraFoodChance(level()) : 0.0);
                add(drop.getKey(), amount);
            }
        }

        private void add(Item item, double amount) {
            String key = BuiltInRegistries.ITEM.getKey(item).toString();
            double total = carry.getDouble(key) + amount;
            int whole = (int) Math.floor(total + 1e-9);
            if (whole <= 0) {
                carry.putDouble(key, total);
                return;
            }
            ItemStack stack = VillageItemOwnership.produce(new ItemStack(item, whole), ctx.mgr);
            if (stack.isEmpty()) {
                // Live loses an item that cannot be bound the same way.
                carry.putDouble(key, total - whole);
                return;
            }
            int placed = whole;
            if (!pack.addItem(stack)) {
                // A full pack: the deposit walk now, then storage straight if it still does not fit.
                depositSurplus();
                if (!pack.addItem(stack)) {
                    ctx.store(stack);
                    placed = whole - stack.getCount();
                    // No room anywhere: the rest is owed, and the crops that drop it wait.
                    if (!stack.isEmpty()) noRoom.add(item);
                }
            }
            carry.putDouble(key, total - placed);
            if (placed > 0) gross.merge(item, placed, Integer::sum);
        }

        /** As a live deposit: everything the farmer does not keep ({@code DepositActivity.keptPerSlot}) to storage. */
        private void depositSurplus() {
            int[] kept = DepositActivity.keptPerSlot(ctx.activity());
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = pack.getSlot(slot);
                if (stack.isEmpty() || kept[slot] >= stack.getCount()) continue;
                ItemStack surplus = stack.copyWithCount(stack.getCount() - kept[slot]);
                int moved = ctx.store(surplus);
                if (moved > 0) pack.reduceSlot(slot, moved);
            }
        }

        // ---------------------------------------------------------------------
        // The day's books
        // ---------------------------------------------------------------------

        private int level() {
            return ctx.model().brain().farmerSkillLevel();
        }

        private boolean spend(double ticks) {
            if (ticksLeft < ticks) {
                outOfTime = true;
                return false;
            }
            ticksLeft -= ticks;
            return true;
        }

        private void job() {
            jobs++;
            AwayXp.farmerJob(ctx);
        }

        private void finish() {
            depositSurplus();
            AwayXp.farmerSessions(ctx, jobs);
            CompoundTag data = npc.getBrainData();
            carry.getAllKeys().removeIf(key -> carry.getDouble(key) < 1e-6);
            data.put(TAG_CARRY, carry);
            data.putDouble(TAG_SOIL, soilCarry);
            Set<Item> items = new HashSet<>(gross.keySet());
            items.addAll(planted.keySet());
            for (Item item : items) {
                int net = gross.getOrDefault(item, 0) - planted.getOrDefault(item, 0);
                if (net > 0) ctx.made(item, net);
                else ctx.used(item, -net);
            }
            LOGGER.info("[AwayFarmer] {} day {}: {} job(s), {} tick(s) left of {}; harvested {}, planted {}, tilled {},"
                            + " soil to dirt {}, fruit {}, cane {}, picks {}; no seed {}, no hoe {}, left to tiller {}{}",
                    npc.getNpcId().toString().substring(0, 8), ctx.day.index() + 1, jobs, Math.round(ticksLeft),
                    Math.round(ctx.day.workTicks() * AwayClock.AWAY_PACE), harvests, plants, tills, toDirt, fruits,
                    cuts, picks, noSeed, noHoe, leftToTiller, noRoom.isEmpty() ? "" : "; no room for " + noRoom);
        }
    }
}
