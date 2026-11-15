package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Guard-specific configuration for the shared tool acquisition activity. */
public final class GetSwordActivity extends GetToolActivity {

    public static final ProfessionToolRequirement SWORD_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_sword"; }

        @Override
        public net.minecraft.tags.TagKey<Item> toolTag() { return ItemTags.SWORDS; }

        @Override
        public java.util.Set<Item> allowedTools(Context ctx) {
            return GuardSettings.of(ctx.npc()).getAllowedSwords();
        }

        @Override
        public boolean canCraft(Context ctx) {
            return GuardSettings.of(ctx.npc()).isSwordTierAllowed(GuardSettings.SwordTier.WOODEN);
        }

        @Override
        public ItemStack craftedTool(Context ctx) { return new ItemStack(Items.WOODEN_SWORD); }

        @Override
        public int woodCost() { return 2; }

        @Override
        public int craftTicks() { return 160; }

        @Override
        public float storageWalkSpeed() { return 0.6f; }

        @Override
        public void onAcquireSuccess(Context ctx) {
            GuardSettings.of(ctx.npc()).clearSwordAcquireCooldown();
        }

        /** Hardest hit first, Sharpness included (GuardSwordUtils.quality). */
        @Override
        public java.util.function.ToLongFunction<ItemStack> quality(Context ctx) {
            return com.alex.test.village.villagers.guard.GuardSwordUtils::quality;
        }
    };

    public GetSwordActivity() {
        super(SWORD_REQUIREMENT);
    }

    private GetSwordActivity(StorageBuilding storage) {
        super(SWORD_REQUIREMENT, storage);
    }

    public static GetSwordActivity forUpgrade(StorageBuilding storage) {
        return new GetSwordActivity(storage);
    }
}
