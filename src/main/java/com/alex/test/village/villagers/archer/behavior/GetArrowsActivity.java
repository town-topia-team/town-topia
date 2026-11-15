package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.villagers.archer.ArcherBowUtils;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Archer-specific configuration for fetching arrows from storage, mirroring
 * {@link GetBowActivity}.
 *
 * <p>The shared {@link GetToolActivity} was built around single tools; arrows
 * ask it for a whole quiver per visit through {@code piecesPerVisit}.
 */
public final class GetArrowsActivity extends GetToolActivity {

    public static final ProfessionToolRequirement ARROW_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_arrows"; }

        @Override
        public TagKey<Item> toolTag() { return ItemTags.ARROWS; }

        @Override
        public Set<Item> allowedTools(Context ctx) {
            // Filtered by ArcherSettingsSchema.ALLOWED_ARROW_TYPES so disallowing
            // an arrow type in the settings screen genuinely stops this villager
            // from restocking it, rather than just hiding an unused toggle.
            ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), ArcherSettingsSchema.SCHEMA);
            Set<Item> allowed = new LinkedHashSet<>();
            if (settings.isAllowed(ArcherSettingsSchema.ALLOWED_ARROW_TYPES, "minecraft:arrow")) {
                allowed.add(Items.ARROW);
            }
            if (settings.isAllowed(ArcherSettingsSchema.ALLOWED_ARROW_TYPES, "minecraft:spectral_arrow")) {
                allowed.add(Items.SPECTRAL_ARROW);
            }
            if (settings.isAllowed(ArcherSettingsSchema.ALLOWED_ARROW_TYPES, "minecraft:tipped_arrow")) {
                allowed.add(Items.TIPPED_ARROW);
            }
            return allowed;
        }

        /**
         * A full quiver in one trip, not one arrow per walk to storage; never the
         * arrows the Stock tab keeps for the player (or holds for an order).
         */
        @Override
        public int piecesPerVisit(Context ctx) {
            int missing = ArcherBowUtils.quiverSize(ctx.npc())
                    - ArcherBowUtils.countArrows(ctx.npc().getInventory());
            int free = com.alex.test.village.economy.StockReserves.get(ctx.level().getServer())
                    .free(ctx.mgr(), ctx.level(), Items.ARROW,
                            com.alex.test.village.villagers.core.Profession.ARCHER);
            return Math.max(1, Math.min(missing, free));
        }

        @Override
        public boolean canCraft(Context ctx) { return false; }

        @Override
        public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }

        @Override
        public int woodCost() { return 0; }

        @Override
        public int craftTicks() { return 0; }
    };

    public GetArrowsActivity() {
        super(ARROW_REQUIREMENT);
    }
}
