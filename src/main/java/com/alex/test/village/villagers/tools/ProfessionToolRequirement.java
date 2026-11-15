package com.alex.test.village.villagers.tools;

import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Profession-specific configuration for the shared tool acquisition activity. */
public interface ProfessionToolRequirement {

    String activityName();

    TagKey<Item> toolTag();

    Set<Item> allowedTools(Activity.Context ctx);

    default Predicate<ItemStack> usablePredicate(Activity.Context ctx) {
        return ToolSelectionHelper::isUsableByDefault;
    }

    /**
     * How good a tool is for this job, higher is better; a storage tool is an
     * upgrade only when it beats the working tool on this. Leaves durability out:
     * a nearly broken tool is used until it breaks, never swapped for a fresher
     * copy. Lumberjacks and miners override it with their real work speed.
     */
    default ToLongFunction<ItemStack> quality(Activity.Context ctx) {
        return ToolSelectionHelper::quality;
    }

    boolean canCraft(Activity.Context ctx);

    ItemStack craftedTool(Activity.Context ctx);

    int woodCost();

    int craftTicks();

    default int chestBusyTicks() {
        return 25;
    }

    default float storageWalkSpeed() {
        return VillagerModel.WALK_SPEED;
    }

    default void onAcquireSuccess(Activity.Context ctx) {}

    /**
     * How many pieces one storage visit takes. One for a real tool; ammunition
     * such as arrows comes by the handful, or an archer walks to storage once
     * per arrow.
     */
    default int piecesPerVisit(Activity.Context ctx) {
        return 1;
    }

    /** Place a piece withdrawn from storage; most tools use the backpack. */
    default boolean canPlaceAcquiredTool(Activity.Context ctx, ItemStack stack) {
        return ctx.npc().getInventory().capacityFor(stack) >= stack.getCount();
    }

    /** Place a piece withdrawn from storage; most tools use the backpack. */
    default boolean placeAcquiredTool(Activity.Context ctx, ItemStack stack) {
        return ctx.npc().getInventory().addItem(stack);
    }

    /**
     * Whether the profession cannot work at all without this item. False for
     * extras such as armor or optional shears, whose absence must not send a
     * villager off to bank its pack (see {@link StrandedToolDeposit}).
     */
    default boolean requiredForWork() {
        return true;
    }

    /**
     * {@link #requiredForWork()} for this villager; a farmer set to crops
     * that need no hoe is not stranded without one.
     */
    default boolean requiredForWork(Activity.Context ctx) {
        return requiredForWork();
    }
}
