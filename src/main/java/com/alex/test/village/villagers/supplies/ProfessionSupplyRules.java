package com.alex.test.village.villagers.supplies;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.archer.behavior.GetArrowsActivity;
import com.alex.test.village.villagers.archer.behavior.GetBowActivity;
import com.alex.test.village.villagers.farmer.behavior.GetHoeActivity;
import com.alex.test.village.villagers.farmer.behavior.GetShearsActivity;
import com.alex.test.village.villagers.guard.behavior.GetSwordActivity;
import com.alex.test.village.villagers.farmer.FarmerInventory;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.lumberjack.behavior.GetAxeActivity;
import com.alex.test.village.villagers.miner.behavior.GetPickaxeActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Central profession-specific supply configuration used by shared storage workflows. */
public final class ProfessionSupplyRules {

    private ProfessionSupplyRules() {}

    /**
     * Food a villager fills its pockets to on a storage visit. Eating only when
     * hungry with whatever happened to be carried left villagers walking around
     * empty; sixteen items is two to three days of meals.
     */
    public static final int FOOD_TARGET = 16;

    /** A reserve of this many food items or fewer is worth topping up when convenient. */
    public static final int FOOD_RESERVE_LOW = 4;

    /**
     * Arrows an archer keeps ready; excess ammunition can return to storage.
     * The same as the starter quiver, so a new archer does not bank most of it.
     */
    public static final int ARCHER_ARROW_TARGET = 32;

    /** Below this many arrows an archer goes to storage for a full quiver. */
    public static final int ARCHER_ARROW_REFILL = 16;

    public static boolean shouldKeepFromGenericDeposit(NpcEntity npc, ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (npc.getProfession() == Profession.FARMER
                && (FarmerInventory.isSupportedSeed(stack) || stack.is(Items.BONE_MEAL))) {
            return FarmerInventory.isKeptSupply(npc, stack);
        }
        for (ProfessionToolRequirement requirement : toolRequirements(npc.getProfession())) {
            if (stack.is(requirement.toolTag())) return true;
        }
        // Working tools without an acquire-from-storage requirement.
        return switch (npc.getProfession()) {
            // Breeding food belongs here too, not just the tools. Without it
            // the rancher fetched 8 wheat, and chooseNext() -- which runs the
            // moment the fetch sub-activity ends -- saw 8 depositable items
            // against a DEPOSIT_LOAD of 4 and marched it straight back to the
            // chest before feeding a single animal. Fetch, deposit, repeat,
            // forever: no animal ever entered love mode and nothing was ever
            // bred, while milk and wool worked fine because BUCKET and SHEARS
            // were already exempt. Covers every food RanchAnimals.breedFoodsFor
            // lists.
            case RANCHER -> stack.is(Items.SHEARS) || stack.is(Items.BUCKET) || stack.is(Items.LEAD)
                    || com.alex.test.village.villagers.rancher.RanchAnimals.isBreedFood(stack.getItem());
            // A cook fetches its ingredients one trip at a time, so anything it
            // is carrying toward a dish has to survive the next deposit
            // decision -- otherwise it takes one wheat from the chest and puts
            // it straight back, over and over, and no bread is ever baked.
            // Ingredients, and logs: a cook fetches logs to burn into charcoal,
            // and the generic deposit pass used to walk them straight back to
            // the chest between the fetch and the furnace. DepositActivity
            // narrows the ingredients to dishes the cook can still make, which
            // needs the village this rule has no access to.
            case COOK -> com.alex.test.village.villagers.cook.behavior.CookActivity
                    .INGREDIENTS.contains(stack.getItem())
                    || stack.is(net.minecraft.tags.ItemTags.LOGS);
            // The lead too; DepositActivity keeps only one of them.
            case BUTCHER -> stack.is(net.minecraft.tags.ItemTags.SWORDS) || stack.is(Items.LEAD);
            // The mage's reagents (amethyst, lapis): its own spell supply.
            case MAGE -> com.alex.test.village.villagers.mage.MageReagents.isReagent(stack);
            // Torches as well as the pickaxe: they are the miner's own
            // consumable, no use to anyone else in the village, and a miner
            // that banked them at the end of every shift arrived at the dark
            // end of its tunnel every morning with nothing to light it.
            case MINER -> stack.is(net.minecraft.tags.ItemTags.PICKAXES)
                    || stack.is(Items.TORCH);
            default -> false;
        };
    }

    /**
     * True when {@code stack} is what this villager's profession exists to
     * produce. Product is always banked, never kept as the villager's personal
     * food reserve — a butcher eating into the village's raw-meat supply chain
     * defeats the point of having one.
     */
    public static boolean isProfessionProduct(NpcEntity npc, ItemStack stack) {
        return isProductOf(npc.getProfession(), stack);
    }

    /** {@link #isProfessionProduct} for a profession rather than a villager. */
    public static boolean isProductOf(Profession profession, ItemStack stack) {
        return switch (profession) {
            case BUTCHER -> stack.is(Items.BEEF) || stack.is(Items.PORKCHOP)
                    || stack.is(Items.CHICKEN) || stack.is(Items.MUTTON)
                    || stack.is(Items.RABBIT);
            case FARMER -> stack.is(Items.WHEAT) || stack.is(Items.CARROT)
                    || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
                    || stack.is(Items.SWEET_BERRIES) || stack.is(Items.GLOW_BERRIES);
            // The cook is the profession this rule matters most for, and it was
            // the one missing. Its product is food, so without a case here every
            // loaf counted against the personal-food reserve DepositActivity
            // keeps back (KEEP_FOOD = 8) — and the cook ate out of that same
            // pile, so the count never rose above the reserve and not one loaf
            // was ever banked. The village starved around a working kitchen.
            case COOK -> com.alex.test.village.villagers.cook.behavior.CookActivity.isDish(stack);
            // What these professions exist to bank: the tradesman sells surplus
            // for emeralds, the enchanter binds enchantments into books.
            case TRADESMAN -> stack.is(Items.EMERALD);
            case ENCHANTER -> stack.is(Items.ENCHANTED_BOOK);
            case RANCHER -> stack.is(Items.MILK_BUCKET) || stack.is(Items.EGG)
                    || stack.is(net.minecraft.tags.ItemTags.WOOL) || stack.is(Items.LEATHER);
            case BLACKSMITH -> stack.is(Items.LEATHER_HELMET) || stack.is(Items.LEATHER_CHESTPLATE)
                    || stack.is(Items.LEATHER_LEGGINGS) || stack.is(Items.LEATHER_BOOTS)
                    || stack.is(Items.IRON_HELMET) || stack.is(Items.IRON_CHESTPLATE)
                    || stack.is(Items.IRON_LEGGINGS) || stack.is(Items.IRON_BOOTS);
            default -> false;
        };
    }

    public static List<ProfessionToolRequirement> toolRequirements(Profession profession) {
        return switch (profession) {
            case LUMBERJACK -> List.of(GetAxeActivity.AXE_REQUIREMENT);
            case GUARD, CAPTAIN -> List.of(GetSwordActivity.SWORD_REQUIREMENT,
                    com.alex.test.village.villagers.guard.behavior.GetShieldActivity.SHIELD_REQUIREMENT);
            case FARMER -> List.of(
                    GetHoeActivity.HOE_REQUIREMENT,
                    GetShearsActivity.SHEARS_REQUIREMENT,
                    com.alex.test.village.villagers.farmer.behavior.GetFarmerAxeActivity.AXE_REQUIREMENT);
            case MINER -> List.of(GetPickaxeActivity.PICKAXE_REQUIREMENT);
            // The butcher kills with real blows, so its sword is fetched (or
            // whittled) like the lead. The rancher's lead comes from storage;
            // its shears are handled below, with no acquire flow (yet).
            case BUTCHER -> List.of(com.alex.test.village.villagers.butcher.lead.LeadSupply.REQUIREMENT,
                    com.alex.test.village.villagers.butcher.ButcherSword.REQUIREMENT);
            case RANCHER -> List.of(com.alex.test.village.villagers.butcher.lead.LeadSupply.REQUIREMENT);
            case COOK, BLACKSMITH,
                 BUILDER, BARD, CLERIC, DRUID, ENCHANTER, TEACHER, TRADESMAN,
                 CHILD -> List.of();
            case ARCHER -> List.of(
                    GetBowActivity.BOW_REQUIREMENT, GetArrowsActivity.ARROW_REQUIREMENT);
            // Reagents are fetched by the mage's own activity between fights; it
            // casts its bolt without them, so nothing here can strand it.
            case MAGE -> List.of();
            case NONE -> List.of();
        };
    }
}
