package com.alex.test.village.tokens;

import com.alex.test.DynamicVillages;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.entity.NpcEntity;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import javax.annotation.Nullable;

/** Server-side rules for goods a villager may use. The visible name is never evidence. */
public final class VillageItemOwnership {
    private VillageItemOwnership() {}

    /**
     * Items with food data that are not food to a villager
     * ({@code data/towntopia/tags/item/not_villager_food.json}): the ominous
     * bottle, which 1.21 gives 1 hunger only so a player can drink it.
     */
    public static final TagKey<Item> NOT_VILLAGER_FOOD = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "not_villager_food"));

    /** Food to a villager: it has food data and is not on the not-villager-food list. */
    public static boolean isVillagerFood(ItemStack stack) {
        return isEdible(stack) && !stack.is(NOT_VILLAGER_FOOD);
    }

    /**
     * The food data {@code eater} would get from the stack, or null when it is
     * not food to a villager. Stands in for {@code stack.getFoodProperties(npc)}.
     */
    @Nullable
    public static FoodProperties villagerFood(ItemStack stack, @Nullable LivingEntity eater) {
        return stack.is(NOT_VILLAGER_FOOD) ? null : stack.getFoodProperties(eater);
    }

    public static boolean mayUse(ItemStack stack, VillageManager village) {
        return village != null && mayUse(stack, VillageTokenBinding.of(village));
    }

    public static boolean mayUse(ItemStack stack, VillageTokenBinding owner) {
        if (stack.isEmpty() || owner == null) return false;
        // A malformed binding is still a binding. It must not get the unbound exception.
        if (VillageTokenBinding.isBound(stack)) return owner.equals(VillageTokenBinding.read(stack));
        return PlayerGear.isPlayerGear(stack) || isFarmSupply(stack) || isPlayerFood(stack);
    }

    /**
     * Stricter than {@link #mayUse}: goods a villager may cook, compost,
     * melt down, enchant or craft from. A player's food and gear are used
     * (eaten, worn, swung) but never turned into the village's own goods:
     * only bone meal, bones and wooden tools come in from outside, as before.
     */
    public static boolean mayUseAsMaterial(ItemStack stack, VillageTokenBinding owner) {
        if (stack.isEmpty() || owner == null) return false;
        if (VillageTokenBinding.isBound(stack)) return owner.equals(VillageTokenBinding.read(stack));
        return isWoodenTool(stack) || isFarmSupply(stack);
    }

    public static boolean mayUseAsMaterial(ItemStack stack, VillageManager village) {
        return village != null && mayUseAsMaterial(stack, VillageTokenBinding.of(village));
    }

    /**
     * A sample for fetching {@code item} from storage as material: food is
     * bound to the village so only the village's own matches, anything else
     * stays plain.
     */
    public static ItemStack materialSample(Item item, VillageManager village) {
        ItemStack sample = new ItemStack(item);
        if (village != null && isEdible(sample)) VillageTokenBinding.bind(sample, village);
        return sample;
    }

    /**
     * Food a player brings: any of it but golden carrots and golden apples, and
     * nothing on the not-villager-food list (the ominous bottle).
     * Eaten at a penalty (FoodCatalog.fillShare and taste); plain crops may
     * also be planted by the farmer and fed to the rancher's animals.
     */
    public static boolean isPlayerFood(ItemStack stack) {
        return isVillagerFood(stack) && !stack.is(Items.GOLDEN_CARROT) && !stack.is(Items.GOLDEN_APPLE)
                && !stack.is(Items.ENCHANTED_GOLDEN_APPLE);
    }

    private static boolean isEdible(ItemStack stack) {
        return stack.has(DataComponents.FOOD);
    }

    /**
     * Bone meal and bones a player put in storage: farmers may use them like
     * the village's own. Nothing in the village makes them except a
     * composter, so without this a player could not supply any.
     */
    public static boolean isFarmSupply(ItemStack stack) {
        return stack.is(Items.BONE_MEAL) || stack.is(Items.BONE);
    }

    public static boolean mayTrade(ItemStack stack, VillageManager village) {
        return village != null && mayTrade(stack, VillageTokenBinding.of(village));
    }

    public static boolean mayTrade(ItemStack stack, VillageTokenBinding owner) {
        return !stack.isEmpty() && owner != null && owner.equals(VillageTokenBinding.read(stack));
    }

    /** Use only for new supplies issued by a profession assignment. */
    public static ItemStack issue(ItemStack stack, NpcEntity npc) {
        if (!(npc.level() instanceof ServerLevel level) || npc.getVillageLink() == null)
            return ItemStack.EMPTY;
        VillageManager village = VillageManagerRegistry.get(level.getServer())
                .get(npc.getVillageLink().villageId());
        return village != null && VillageTokenBinding.bind(stack, village) ? stack : ItemStack.EMPTY;
    }

    /** Use only for newly produced goods, never for inventory or chest input. */
    public static ItemStack produce(ItemStack stack, VillageManager village) {
        return VillageTokenBinding.bind(stack, village) ? stack : ItemStack.EMPTY;
    }

    public static boolean isWoodenTool(ItemStack stack) {
        return stack.is(Items.WOODEN_AXE) || stack.is(Items.WOODEN_PICKAXE)
                || stack.is(Items.WOODEN_HOE) || stack.is(Items.WOODEN_SHOVEL)
                || stack.is(Items.WOODEN_SWORD);
    }
}
