package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.lumberjack.LumberjackBigTrees;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * The farmer's axe, for melons and pumpkins only: the best allowed one from
 * storage (blacksmith made ones included), else a wooden one crafted from
 * planks when wooden tool crafting is on.
 */
public final class GetFarmerAxeActivity extends GetToolActivity {

    public static final ProfessionToolRequirement AXE_REQUIREMENT = new ProfessionToolRequirement() {
        @Override public String activityName() { return "get_farmer_axe"; }
        @Override public TagKey<Item> toolTag() { return ItemTags.AXES; }
        /**
         * None while the farmer grows no melons or pumpkins: storage visits
         * restock every allowed tool, and a farmer holding an axe it never
         * uses would keep it from the lumberjacks. Iron and better axes are left
         * for a lumberjack waiting for one to fell big trees; one the farmer
         * carries is then no longer its working axe, so the next storage visit
         * banks it (DepositActivity keeps only working tools).
         */
        @Override public Set<Item> allowedTools(Context ctx) {
            if (!ctx.mgr().farmers().wantsHarvestTool(ctx.npc(), HarvestTool.AXE)) return Set.of();
            Set<Item> allowed = FarmerSettings.of(ctx.npc()).getAllowedAxes();
            allowed.removeAll(LumberjackBigTrees.axesWaitedFor(ctx.mgr(), ctx.level().getGameTime()));
            return allowed;
        }
        @Override public boolean canCraft(Context ctx) {
            FarmerSettings settings = FarmerSettings.of(ctx.npc());
            return settings.allowHoeCrafting() && settings.isAxeTierAllowed(FarmerSettings.AxeTier.WOODEN);
        }
        @Override public ItemStack craftedTool(Context ctx) { return new ItemStack(Items.WOODEN_AXE); }
        @Override public int woodCost() { return 3; }
        @Override public int craftTicks() { return 200; }
        /** Fields without melons or pumpkins still get worked. */
        @Override public boolean requiredForWork() { return false; }
        /** Faster is better (ToolSpeed), Efficiency included. */
        @Override public ToLongFunction<ItemStack> quality(Context ctx) { return ToolSpeed::quality; }
    };

    public GetFarmerAxeActivity() {
        super(AXE_REQUIREMENT);
    }

    private GetFarmerAxeActivity(StorageBuilding storage) {
        super(AXE_REQUIREMENT, storage);
    }

    public static GetFarmerAxeActivity forUpgrade(StorageBuilding storage) {
        return new GetFarmerAxeActivity(storage);
    }
}
