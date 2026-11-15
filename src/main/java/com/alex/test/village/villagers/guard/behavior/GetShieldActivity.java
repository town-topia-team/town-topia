package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

import java.util.Set;

/**
 * Fetches a shield from storage once a guard has learned to block with one
 * ({@link GuardBalance#SHIELD_LEVEL}). The shield rides in the pack like the
 * sword, not the off-hand: eating holds food there and clears the slot after.
 *
 * <p>No crafting fallback, as for armor: a shield is picked up only when one
 * is stocked.
 */
public final class GetShieldActivity extends GetToolActivity {

    private static final Set<Item> SHIELDS = Set.of(Items.SHIELD);

    public static final ProfessionToolRequirement SHIELD_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_shield"; }

        @Override
        public TagKey<Item> toolTag() { return Tags.Items.TOOLS_SHIELD; }

        /** None until the guard can use one, so storage restocks skip it too. */
        @Override
        public Set<Item> allowedTools(Context ctx) {
            return ctx.npc().getProfession().isGuardLike()
                    && CombatRank.of(ctx.npc().getProfession())
                            .hasShieldBlock(ctx.brain().defenderSkillLevel()) ? SHIELDS : Set.of();
        }

        @Override
        public boolean canCraft(Context ctx) { return false; }

        @Override
        public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }

        @Override
        public int woodCost() { return 0; }

        @Override
        public int craftTicks() { return 0; }

        @Override
        public boolean requiredForWork() { return false; }

        @Override
        public float storageWalkSpeed() { return 0.6f; }
    };

    public GetShieldActivity() {
        super(SHIELD_REQUIREMENT);
    }
}
