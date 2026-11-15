package com.alex.test.village.villagers.butcher;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.guard.GuardSwordUtils;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * The butcher's sword. The kill is real blows now (ButcherActivity), so a
 * butcher without one does not work: it takes the hardest-hitting sword from
 * storage, or whittles a wooden one from two planks as a guard does.
 */
public final class ButcherSword {

    private static final Set<Item> SWORDS = Set.of(Items.WOODEN_SWORD, Items.STONE_SWORD,
            Items.GOLDEN_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    /** Not "required" (as the lead): a missing sword never sends the butcher to bank its pack. */
    public static final ProfessionToolRequirement REQUIREMENT = new ProfessionToolRequirement() {
        @Override public String activityName() { return "get_butcher_sword"; }
        @Override public TagKey<Item> toolTag() { return ItemTags.SWORDS; }
        @Override public Set<Item> allowedTools(Activity.Context ctx) { return SWORDS; }
        @Override public boolean canCraft(Activity.Context ctx) { return true; }
        @Override public ItemStack craftedTool(Activity.Context ctx) { return new ItemStack(Items.WOODEN_SWORD); }
        @Override public int woodCost() { return 2; }
        @Override public int craftTicks() { return 160; }
        @Override public boolean requiredForWork() { return false; }
        /** Hardest hit first, Sharpness included. */
        @Override public ToLongFunction<ItemStack> quality(Activity.Context ctx) {
            return GuardSwordUtils::quality;
        }
    };

    private ButcherSword() {}

    /** The pack slot of the sword the butcher kills with, or -1. Banking keeps it (ProfessionDepositRules). */
    public static int slot(com.alex.test.entity.NpcEntity npc) {
        return ToolSelectionHelper.findBestToolSlot(npc.getInventory(),
                stack -> stack.is(ItemTags.SWORDS) && ToolSelectionHelper.isUsableByDefault(stack),
                ToolSelectionHelper::quality);
    }
}
