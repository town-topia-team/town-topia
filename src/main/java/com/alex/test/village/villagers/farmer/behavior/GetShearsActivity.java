package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.farmer.FarmerShearsUtils;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.ToLongFunction;

/** Optional shears acquisition, for picking berries and glow berries and cutting cocoa pods. */
public final class GetShearsActivity extends GetToolActivity {

    public static final ProfessionToolRequirement SHEARS_REQUIREMENT = new ProfessionToolRequirement() {
        @Override public String activityName() { return "get_farmer_shears"; }
        @Override public TagKey<Item> toolTag() { return FarmerShearsUtils.FARMER_SHEARS; }
        /**
         * None while nothing this farmer tends needs shears: storage visits
         * restock every allowed tool, so every farmer took a pair whether it
         * grew berries or not. Same rule as the farmer's axe.
         */
        @Override public Set<Item> allowedTools(Context ctx) {
            if (!FarmerSettings.of(ctx.npc()).allowShears()
                    || !ctx.mgr().farmers().wantsHarvestTool(ctx.npc(), HarvestTool.SHEARS)) return Set.of();
            return FarmerShearsUtils.ALLOWED_SHEARS;
        }
        @Override public boolean canCraft(Context ctx) { return false; }
        @Override public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }
        @Override public int woodCost() { return 0; }
        @Override public int craftTicks() { return 0; }
        @Override public boolean requiredForWork() { return false; }
        /** Faster is better (ToolSpeed): for shears, only Efficiency counts. */
        @Override public ToLongFunction<ItemStack> quality(Context ctx) { return ToolSpeed::quality; }
    };

    public GetShearsActivity() {
        super(SHEARS_REQUIREMENT);
    }

    private GetShearsActivity(StorageBuilding storage) {
        super(SHEARS_REQUIREMENT, storage);
    }

    /** Trades the farmer's shears for a faster pair in {@code storage}; the old pair stays there. */
    public static GetShearsActivity forUpgrade(StorageBuilding storage) {
        return new GetShearsActivity(storage);
    }
}
