package com.alex.test.village.brain;

/**
 * Best-effort hunger preparation before a long, non-interruptible task.
 *
 * <p>The activity reuses the normal food flow: eat carried food first, fetch
 * food from storage once if none is carried, then eat again. If no food exists
 * anywhere, it completes anyway so the caller can decide to proceed.
 */
public final class PreTaskHungerActivity extends Activity {

    private final float minimumHunger;
    private Activity subActivity;
    private boolean fetchedFromStorage;

    public PreTaskHungerActivity(float minimumHunger) {
        this.minimumHunger = minimumHunger;
    }

    @Override
    protected Status tick(Context ctx) {
        if (ctx.brain().needs().hunger >= minimumHunger) return Status.DONE;

        if (subActivity != null) {
            Status status = subActivity.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;

            subActivity.stop(ctx);
            subActivity = null;
            return Status.RUNNING;
        }

        if (EatActivity.findFoodSlot(ctx) >= 0) {
            subActivity = new EatActivity();
            return Status.RUNNING;
        }

        if (!fetchedFromStorage) {
            fetchedFromStorage = true;
            subActivity = new FetchFoodActivity();
            return Status.RUNNING;
        }

        ctx.brain().diary().log(String.format(
                "%s pre_task_hunger  no food available; continuing at hunger=%.1f",
                VillagerDiary.ts(ctx.level()), ctx.brain().needs().hunger));
        return Status.DONE;
    }

    @Override
    public void stop(Context ctx) {
        if (subActivity != null) {
            subActivity.stop(ctx);
            subActivity = null;
        }
    }

    @Override
    public boolean keepsChairSeat() {
        return subActivity != null && subActivity.keepsChairSeat();
    }

    @Override
    public String name() {
        return "pre_task_hunger";
    }
}
