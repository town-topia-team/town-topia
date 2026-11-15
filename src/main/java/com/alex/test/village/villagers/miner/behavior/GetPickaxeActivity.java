package com.alex.test.village.villagers.miner.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.miner.PickaxeUtils;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Miner-specific configuration for the shared tool acquisition activity.
 *
 * <p>The miner used to be handed a single wooden pickaxe by
 * {@code MinerAssignmentHandler} and had no way to replace it. A wooden
 * pickaxe lasts 59 blocks, so every miner in a long-lived village eventually
 * broke its only tool and stopped forever -- {@code hasWork} answered false
 * from then on, silently, taking the blacksmith and the enchanter down with
 * it for want of ore. Same deadlock the lumberjack had with its axe.
 */
public final class GetPickaxeActivity extends GetToolActivity {

    public static final ProfessionToolRequirement PICKAXE_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_pickaxe"; }

        @Override
        public net.minecraft.tags.TagKey<Item> toolTag() { return ItemTags.PICKAXES; }

        @Override
        public java.util.Set<Item> allowedTools(Context ctx) {
            return MinerActivity.allowedPickaxes(ctx);
        }

        @Override
        public java.util.function.Predicate<ItemStack> usablePredicate(Context ctx) {
            return ToolSelectionHelper::isUsableByDefault;
        }

        /**
         * Only worth walking to a table if the miner would actually equip what
         * it made: with the wooden tier switched off in the settings screen,
         * crafting a wooden pickaxe would hand it a tool it refuses to hold.
         */
        @Override
        public boolean canCraft(Context ctx) {
            return ProfessionSettings.of(ctx.npc(), MinerSettingsSchema.SCHEMA)
                    .isAllowed(MinerSettingsSchema.ALLOWED_PICKAXE_TIERS,
                            MinerSettingsSchema.TIER_WOODEN);
        }

        @Override
        public ItemStack craftedTool(Context ctx) { return new ItemStack(Items.WOODEN_PICKAXE); }

        /** Three planks and two sticks, as vanilla -- the same as an axe. */
        @Override
        public int woodCost() { return 4; }

        @Override
        public int craftTicks() { return 240; }

        /**
         * Fewest ticks per block, then the faster vanilla speed, as the miner
         * picks its pickaxe: swing count and swing speed together.
         */
        @Override
        public java.util.function.ToLongFunction<ItemStack> quality(Context ctx) {
            return PickaxeUtils::quality;
        }
    };

    public GetPickaxeActivity() {
        super(PICKAXE_REQUIREMENT);
    }

    private GetPickaxeActivity(StorageBuilding storage) {
        super(PICKAXE_REQUIREMENT, storage);
    }

    public static GetPickaxeActivity forUpgrade(StorageBuilding storage) {
        return new GetPickaxeActivity(storage);
    }
}
