package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.Set;

/**
 * Archer-specific configuration for the shared tool acquisition activity,
 * mirroring GuardActivity's GetSwordActivity.
 *
 * <p>No vanilla item tag exists for "bow" alone (crossbows are separate
 * items), so this borrows {@code minecraft:enchantable/bow} -- a real
 * vanilla tag containing exactly {@code minecraft:bow} -- and restricts the
 * allowed set to just that item, which is all {@link GetToolActivity}'s
 * generic tag+allowed-set filtering needs.
 */
public final class GetBowActivity extends GetToolActivity {

    public static final ProfessionToolRequirement BOW_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_bow"; }

        @Override
        public TagKey<Item> toolTag() { return ItemTags.BOW_ENCHANTABLE; }

        @Override
        public Set<Item> allowedTools(Context ctx) { return Set.of(Items.BOW); }

        @Override
        public boolean canCraft(Context ctx) { return false; }

        @Override
        public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }

        @Override
        public int woodCost() { return 0; }

        @Override
        public int craftTicks() { return 0; }
    };

    public GetBowActivity() {
        super(BOW_REQUIREMENT);
    }

    private GetBowActivity(StorageBuilding storage) {
        super(BOW_REQUIREMENT, storage);
    }

    /**
     * Trades the bow for a better one in {@code storage} (more enchantment):
     * the old bow goes back to storage, where it waits as a spare.
     */
    public static GetBowActivity forUpgrade(StorageBuilding storage) {
        return new GetBowActivity(storage);
    }
}
