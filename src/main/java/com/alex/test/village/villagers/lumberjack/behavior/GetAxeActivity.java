package com.alex.test.village.villagers.lumberjack.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Lumberjack-specific configuration for the shared tool acquisition activity. */
public final class GetAxeActivity extends GetToolActivity {

    public static final ProfessionToolRequirement AXE_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_axe"; }

        @Override
        public net.minecraft.tags.TagKey<Item> toolTag() { return ItemTags.AXES; }

        @Override
        public java.util.Set<Item> allowedTools(Context ctx) {
            return LumberjackSettings.of(ctx.npc()).getAllowedAxes();
        }

        @Override
        public java.util.function.Predicate<ItemStack> usablePredicate(Context ctx) {
            return AxeUtils::isUsableAxe;
        }

        @Override
        public boolean canCraft(Context ctx) {
            LumberjackSettings settings = LumberjackSettings.of(ctx.npc());
            return settings.allowAxeCrafting()
                    && settings.getAllowedAxes().contains(Items.WOODEN_AXE);
        }

        @Override
        public ItemStack craftedTool(Context ctx) { return new ItemStack(Items.WOODEN_AXE); }

        @Override
        public int woodCost() { return 4; }

        @Override
        public int craftTicks() { return 240; }

        /**
         * As the lumberjack picks its axe ({@link AxeUtils#quality}): one that fells
         * big 2x2 trees (iron or better) first, then chop speed with Efficiency, so a
         * fast stone axe beats a slow wooden one but never an iron one.
         */
        @Override
        public java.util.function.ToLongFunction<ItemStack> quality(Context ctx) {
            return AxeUtils::quality;
        }
    };

    public GetAxeActivity() {
        super(AXE_REQUIREMENT);
    }

    private GetAxeActivity(StorageBuilding storage) {
        super(AXE_REQUIREMENT, storage);
    }

    public static GetAxeActivity forUpgrade(StorageBuilding storage) {
        return new GetAxeActivity(storage);
    }
}
