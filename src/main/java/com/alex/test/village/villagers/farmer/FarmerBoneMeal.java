package com.alex.test.village.villagers.farmer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BeetrootBlock;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * Bone meal on crops. Vanilla decides what one use does (2-5 stages on
 * wheat, carrots and potatoes, one on a torchflower or pitcher plant); this
 * class picks what is worth it and how often.
 *
 * <p>An ordinary farmer bone meals in short bursts on a random timer. The
 * emptier the village's food stores, the sooner the next burst and the more
 * uses it gets: well fed, a burst of one every few minutes; starving, several
 * every half minute. A farmer with no field work (Planting, Harvesting and Tilling off) skips the timer.
 */
public final class FarmerBoneMeal {

    /** Bone meal a farmer carries for the job; the rest goes back to storage. */
    public static final int CARRY_TARGET = 16;
    private static final int BONE_MEAL_PER_BONE = 3;
    /** Food per villager in storage at or below which the village counts as starving. */
    private static final int FOOD_PER_VILLAGER_LOW = 4;
    /** Food per villager at or above which the village counts as well fed. */
    private static final int FOOD_PER_VILLAGER_HIGH = 24;
    private static final int MIN_INTERVAL_TICKS = 20 * 30;
    private static final int MAX_INTERVAL_TICKS = 20 * 60 * 6;
    private static final int MAX_EXTRA_USES = 5;

    private FarmerBoneMeal() {}

    /** 0 when the stores are full, 1 when they are about empty. */
    public static double foodUrgency(ServerLevel level, VillageManager village) {
        int food = 0;
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            food += storage.countMatching(level, stack -> VillageItemOwnership.isVillagerFood(stack)
                    && storage.mayUse(level, stack));
        }
        int villagers = Math.max(1, village.getVillagers().size());
        double perVillager = food / (double) villagers;
        return Mth.clamp((FOOD_PER_VILLAGER_HIGH - perVillager)
                / (FOOD_PER_VILLAGER_HIGH - FOOD_PER_VILLAGER_LOW), 0.0, 1.0);
    }

    /** Uses in the next burst: one when fed, up to six when starving. */
    public static int burstUses(double urgency) {
        return 1 + (int) Math.round(MAX_EXTRA_USES * urgency);
    }

    /** Ticks until the next burst: minutes when fed, half a minute when starving, jittered. */
    public static int ticksUntilNextBurst(double urgency, RandomSource random) {
        double base = Mth.lerp(urgency, MAX_INTERVAL_TICKS, MIN_INTERVAL_TICKS);
        return (int) (base * (0.6 + random.nextDouble() * 0.8));
    }

    /**
     * A planted, still-growing crop that one use of bone meal really moves on.
     * Beetroot is skipped: vanilla gives it a third of the growth, so most
     * uses do nothing at all.
     */
    public static boolean isTarget(ServerLevel level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof BonemealableBlock bonemealable)
                || state.getBlock() instanceof BeetrootBlock) return false;
        CropDefinition definition = CropDefinitionRegistry.byCropBlock(state.getBlock());
        if (definition == null || CropDefinitionRegistry.isMature(level, pos, state, definition)) return false;
        return bonemealable.isValidBonemealTarget(level, pos, state);
    }

    /** Growth stages still to go; the target with most left gains the most. */
    public static int stagesLeft(BlockState state) {
        if (state.getBlock() instanceof CropBlock crop) return crop.getMaxAge() - crop.getAge(state);
        // A glow berry vine's age is its length, not ripeness: one use makes it bear.
        if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES)) return 2;
        for (var property : state.getProperties()) {
            if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                int max = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(0);
                return max - state.getValue(age);
            }
        }
        return 1;
    }

    /**
     * Uses one carried bone meal on {@code pos} the vanilla way: the
     * bone meal event, the growth roll, the green particles.
     */
    public static boolean apply(ServerLevel level, NpcEntity farmer, BlockPos pos) {
        InventoryManager inventory = farmer.getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(Items.BONE_MEAL)) continue;
            if (!BoneMealItem.applyBonemeal(stack, level, pos, null)) return false;
            if (stack.isEmpty()) inventory.setSlot(slot, ItemStack.EMPTY);
            level.levelEvent(1505, pos, 15);
            level.gameEvent(farmer, GameEvent.ITEM_INTERACT_FINISH, pos);
            return true;
        }
        return false;
    }

    public static int carried(InventoryManager inventory) {
        return count(inventory, Items.BONE_MEAL);
    }

    /** Turns carried bones into bone meal, up to {@link #CARRY_TARGET}. Returns bone meal made. */
    public static int craftFromBones(NpcEntity farmer, VillageManager village) {
        InventoryManager inventory = farmer.getInventory();
        int missing = CARRY_TARGET - carried(inventory);
        int bones = Math.min(count(inventory, Items.BONE),
                (missing + BONE_MEAL_PER_BONE - 1) / BONE_MEAL_PER_BONE);
        if (bones <= 0) return 0;
        int made = bones * BONE_MEAL_PER_BONE;
        ItemStack meal = VillageItemOwnership.produce(new ItemStack(Items.BONE_MEAL, made), village);
        // Room is checked before the bones go: addItem is all-or-nothing.
        if (meal.isEmpty() || inventory.capacityFor(meal) < made) return 0;
        remove(inventory, Items.BONE, bones);
        inventory.addItem(meal);
        return made;
    }

    /** Bones to fetch to make one carry's worth of bone meal. */
    public static int bonesFor(int boneMealWanted) {
        return (boneMealWanted + BONE_MEAL_PER_BONE - 1) / BONE_MEAL_PER_BONE;
    }

    private static int count(InventoryManager inventory, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private static void remove(InventoryManager inventory, net.minecraft.world.item.Item item, int amount) {
        for (int i = 0; i < InventoryManager.SIZE && amount > 0; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (!inventory.mayUse(stack) || !stack.is(item)) continue;
            int taken = Math.min(amount, stack.getCount());
            inventory.reduceSlot(i, taken);
            amount -= taken;
        }
    }
}
