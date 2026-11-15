package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSpeed;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;
import java.util.function.ToLongFunction;

/** Farmer-specific configuration for the shared tool acquisition activity. */
public final class GetHoeActivity extends GetToolActivity {

    public static final ProfessionToolRequirement HOE_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_hoe"; }

        @Override
        public net.minecraft.tags.TagKey<Item> toolTag() { return ItemTags.HOES; }

        /**
         * None for a farmer with no farmland crop (only berries, cocoa or
         * cane): storage visits restock every allowed tool, and such a farmer
         * would keep a hoe another farmer needs.
         */
        @Override
        public Set<Item> allowedTools(Context ctx) {
            FarmerSettings settings = FarmerSettings.of(ctx.npc());
            if (!FarmerManager.needsHoe(settings)) return Set.of();
            return settings.getAllowedHoes();
        }

        /**
         * The hoe only tills: without one the farmer still harvests and
         * replants, and goes for a hoe when one can be had (FarmerHoeTrips),
         * so it is never stranded for want of one.
         */
        @Override
        public boolean requiredForWork() {
            return false;
        }

        @Override
        public boolean canCraft(Context ctx) {
            FarmerSettings settings = FarmerSettings.of(ctx.npc());
            return settings.allowHoeCrafting()
                    && settings.isHoeTierAllowed(FarmerSettings.HoeTier.WOODEN);
        }

        @Override
        public ItemStack craftedTool(Context ctx) { return new ItemStack(Items.WOODEN_HOE); }

        @Override
        public int woodCost() { return 3; }

        @Override
        public int craftTicks() { return 200; }

        /** Faster is better (ToolSpeed): a diamond Efficiency V hoe beats a plain netherite one. */
        @Override
        public ToLongFunction<ItemStack> quality(Context ctx) {
            return ToolSpeed::quality;
        }
    };

    public GetHoeActivity() {
        super(HOE_REQUIREMENT);
    }

    private GetHoeActivity(StorageBuilding storage) {
        super(HOE_REQUIREMENT, storage);
    }

    public static GetHoeActivity forUpgrade(StorageBuilding storage) {
        return new GetHoeActivity(storage);
    }
}
