package com.alex.test.village.villagers.tools;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashSet;
import java.util.Set;

/** Central rules for items a profession should keep out of generic storage deposits. */
public final class ProfessionDepositRules {

    private ProfessionDepositRules() {}

    public static boolean shouldKeep(NpcEntity npc, ItemStack stack) {
        return ProfessionSupplyRules.shouldKeepFromGenericDeposit(npc, stack);
    }

    /**
     * One usable working tool per requirement; spare and broken tools may be
     * banked. The kept tool is the one the job works with: the same pick
     * ({@link ToolUpgradeHelper#workingToolSlot}) that puts it in the hand.
     */
    public static Set<Integer> preferredToolSlots(Activity.Context ctx) {
        InventoryManager inventory = ctx.npc().getInventory();
        Profession profession = ctx.npc().getProfession();
        Set<Integer> keep = new HashSet<>();
        for (ProfessionToolRequirement requirement : ProfessionSupplyRules.toolRequirements(profession)) {
            int slot = ToolUpgradeHelper.workingToolSlot(ctx, requirement);
            if (slot >= 0) keep.add(slot);
        }
        // Tools without an acquire-from-storage requirement; neither wears out.
        int slot = switch (profession) {
            case BUTCHER -> ToolSelectionHelper.findBestToolSlot(inventory,
                    stack -> stack.is(ItemTags.SWORDS) && ToolSelectionHelper.isUsableByDefault(stack),
                    ToolSelectionHelper::quality);
            case RANCHER -> ToolSelectionHelper.findBestToolSlot(inventory,
                    stack -> stack.is(Items.SHEARS) && ToolSelectionHelper.isUsableByDefault(stack),
                    ToolSelectionHelper::quality);
            default -> -1;
        };
        if (slot >= 0) keep.add(slot);
        return keep;
    }
}
