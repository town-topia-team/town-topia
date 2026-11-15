package com.alex.test.village.brain;

import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;

/**
 * Backwards-compatible food fetch activity.
 *
 * <p>The actual item-aware chest selection and withdraw logic lives in
 * {@link FetchFromStorageActivity}. Keeping this class means existing brain
 * checks such as {@code current instanceof FetchFoodActivity} keep working.
 *
 * <p>When no storage room holds anything ready to eat but one holds wheat and
 * a crafting table, the trip becomes a {@link BakeBreadActivity} instead, so
 * every hunger path (overrides, scoring, pre-task and pre-sleep eating) can
 * bake without knowing about it.
 */
public final class FetchFoodActivity extends FetchFromStorageActivity {

    private BakeBreadActivity bake;

    /** Filling the pocket rather than eating now; a bake then never queues for the table. */
    private final boolean topUp;

    public FetchFoodActivity() {
        this(false);
    }

    private FetchFoodActivity(boolean topUp) {
        super(StorageBuilding.ResourceType.FOOD, ProfessionSupplyRules.FOOD_TARGET, "fetch_food");
        this.topUp = topUp;
    }

    /**
     * A pocket top-up (before bed, evening chores, before a bard set). If the
     * trip has to bake and another villager holds the table, it fails at once
     * with {@link Failure#TABLE_BUSY} instead of queuing in the storage room.
     */
    public static FetchFoodActivity topUp() {
        return new FetchFoodActivity(true);
    }

    /**
     * Pick food the way the reserve wants it (same type first, at most three
     * types) rather than whatever stack happens to sit first in this chest.
     * The plain one-chest withdraw stays as the fallback.
     */
    @Override
    protected boolean transact(Context ctx) {
        if (StorageSupplyRestocker.restockFood(ctx, storage) <= 0) return super.transact(ctx);
        StorageSupplyRestocker.restockAfterStorageVisit(ctx, storage);
        return true;
    }

    /** Fill the reserve up to the target rather than adding a fixed handful on top. */
    @Override
    protected int amountToTake(Context ctx) {
        return Math.max(1, ProfessionSupplyRules.FOOD_TARGET
                - StorageSupplyRestocker.countCarriedFood(ctx));
    }

    @Override
    protected void start(Context ctx) {
        super.start(ctx);
        // Ready food always wins; the cook needs that wheat more than a snacker does.
        if (super.failure() == Failure.NO_STORAGE && BakeBreadActivity.canBake(ctx)) {
            bake = new BakeBreadActivity(topUp);
        }
    }

    @Override
    protected Status tick(Context ctx) {
        return bake != null ? bake.tickOuter(ctx) : super.tick(ctx);
    }

    @Override
    public void stop(Context ctx) {
        if (bake != null) bake.stop(ctx);
        super.stop(ctx);
    }

    @Override
    public Failure failure() {
        return bake != null ? bake.failure() : super.failure();
    }
}
