package com.alex.test.village.villagers.mage.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.mage.MageReagents;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToLongFunction;

/**
 * A trip to storage for spell reagents -- amethyst shards and lapis lazuli --
 * the way an archer fetches arrows: a handful per visit, never the ones the
 * Stock tab keeps for the player or holds for an order. Amethyst comes first;
 * lapis only when no amethyst is left, since the enchanter needs it.
 */
public final class GetReagentsActivity extends GetToolActivity {

    /** Amethyst shards and lapis lazuli ({@code data/dynamicvillages/tags/item/mage_reagents.json}). */
    public static final TagKey<Item> MAGE_REAGENTS = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "mage_reagents"));

    public static final ProfessionToolRequirement REAGENT_REQUIREMENT = new ProfessionToolRequirement() {
        @Override
        public String activityName() { return "get_reagents"; }

        /**
         * The mod's own tag, holding exactly the two reagents: the common
         * {@code c:gems} tag is filled by whatever loader and mods are present,
         * and storage skips any item outside the tag before it looks further.
         */
        @Override
        public TagKey<Item> toolTag() { return MAGE_REAGENTS; }

        /** Either reagent its settings allow and the Stock tab does not keep back for the player. */
        @Override
        public Set<Item> allowedTools(Context ctx) {
            var settings = com.alex.test.village.villagers.mage.MageSettingsBridge.of(ctx.npc());
            String list = com.alex.test.village.villagers.mage.settings.MageSettingsSchema.ALLOWED_REAGENTS;
            Set<Item> allowed = new LinkedHashSet<>();
            if (settings.isAllowed(list, "minecraft:amethyst_shard") && free(ctx, Items.AMETHYST_SHARD) > 0) {
                allowed.add(Items.AMETHYST_SHARD);
            }
            if (settings.isAllowed(list, "minecraft:lapis_lazuli") && free(ctx, Items.LAPIS_LAZULI) > 0) {
                allowed.add(Items.LAPIS_LAZULI);
            }
            return allowed;
        }

        /** Amethyst before lapis: each piece taken is the best left, so lapis goes only once amethyst runs out. */
        @Override
        public ToLongFunction<ItemStack> quality(Context ctx) {
            return stack -> stack.is(Items.AMETHYST_SHARD) ? 2L : 1L;
        }

        /**
         * Up to a full pouch in one trip, never more than storage can spare: each
         * piece is drawn from what is left, so a short supply just ends the trip early.
         */
        @Override
        public int piecesPerVisit(Context ctx) {
            int missing = MageReagents.CARRY - MageReagents.count(ctx.npc().getInventory());
            long free = (long) free(ctx, Items.AMETHYST_SHARD) + free(ctx, Items.LAPIS_LAZULI);
            return (int) Math.max(1L, Math.min(missing, free));
        }

        /**
         * Each piece only while storage can still spare one of its kind: the
         * allowed set above is worked out once per trip, and amethyst always
         * comes first, so without this a trip would draw amethyst down into
         * what the Stock tab keeps before it turned to lapis.
         */
        @Override
        public boolean canPlaceAcquiredTool(Context ctx, ItemStack stack) {
            return ctx.npc().getInventory().capacityFor(stack) >= stack.getCount()
                    && free(ctx, stack.getItem()) > 0;
        }

        /** A mage fights without them (bolts and Ward), so it is never stranded for want of reagents. */
        @Override
        public boolean requiredForWork() { return false; }

        @Override
        public boolean canCraft(Context ctx) { return false; }

        @Override
        public ItemStack craftedTool(Context ctx) { return ItemStack.EMPTY; }

        @Override
        public int woodCost() { return 0; }

        @Override
        public int craftTicks() { return 0; }
    };

    private static int free(Context ctx, Item item) {
        return StockReserves.get(ctx.level().getServer()).free(ctx.mgr(), ctx.level(), item, Profession.MAGE);
    }

    public GetReagentsActivity() {
        super(REAGENT_REQUIREMENT);
    }
}
