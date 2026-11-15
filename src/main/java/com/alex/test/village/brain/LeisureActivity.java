package com.alex.test.village.brain;

import com.alex.test.village.stats.VillagerStats;

/**
 * Top-level leisure activity: scores between {@link WanderActivity},
 * {@link AdmireTownActivity}, and {@link SocializeActivity}, then runs the
 * winner to completion before re-scoring.
 *
 * <p>Leisure runs in short slices rather than until something interrupts it.
 * It used to return RUNNING forever and lean on the override system (night,
 * hunger, damage) to end it — which meant a villager that started the morning
 * with nothing to do kept admiring buildings until nightfall, even once the
 * work it was waiting for arrived. A cook scored work=75 for a full day while
 * standing in front of the town hall, because nobody ever asked it again.
 *
 * <p>So a slice ends when its sub-activity finishes, or when {@link
 * #SLICE_TICKS} elapses — whichever comes first — and the brain re-scores.
 * Re-scoring is cheap and lands on leisure again whenever leisure is still the
 * right answer, so a genuinely idle villager looks exactly as it did before.
 *
 * <p>Sub-activity selection mirrors the old brain scoring but operates only
 * within the leisure space. {@link VillagerBrain} now scores only the
 * top-level categories (eat, fetch, work, leisure).
 */
public final class LeisureActivity extends Activity {

    /**
     * Longest a villager will stay at leisure without being asked again.
     *
     * <p>Twenty seconds. It has to be short enough that work appearing mid-slice
     * is noticed promptly, and long enough that a villager with nothing to do
     * isn't re-deciding every other tick. Admiring the town alone can run for
     * 3000 ticks, so without this cap the longest sub-activity would set the
     * re-scoring interval.
     */
    private static final int SLICE_TICKS = 400;

    /** Time of day (9000 = mid afternoon) from which the campfire is the favourite place to idle. */
    private static final long CAMPFIRE_EVENING_FROM = 9_000L;

    private Activity sub;
    private int ticks;

    @Override
    protected Status tick(Context ctx) {
        if (sub == null) {
            sub = pickSub(ctx);
            if (sub == null) return Status.DONE;
        }

        Status s = sub.tickOuter(ctx);
        if (s != Status.RUNNING) {
            sub.stop(ctx);
            sub = null;
            return Status.DONE;
        }
        if (++ticks >= SLICE_TICKS) {
            sub.stop(ctx);
            sub = null;
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    @Override
    public void stop(Context ctx) {
        if (sub != null) {
            sub.stop(ctx);
            sub = null;
        }
        ticks = 0;
    }

    @Override
    public String name() {
        return sub != null ? "leisure:" + sub.name() : "leisure";
    }

    @Override
    public boolean keepsChairSeat() {
        return sub != null && sub.keepsChairSeat();
    }

    /** The currently running sub-activity, or {@code null} between sub-picks. */
    public Activity currentSub() { return sub; }

    // =========================================================================
    // Sub-activity scoring
    // =========================================================================

    private Activity pickSub(Context ctx) {
        // Children mostly play: chasing each other, running about, watching a favourite job.
        if (ctx.brain().profession() == com.alex.test.village.villagers.core.Profession.CHILD
                && com.alex.test.village.villagers.child.behavior.KidPlayActivity.worthPlaying(ctx)) {
            return new com.alex.test.village.villagers.child.behavior.KidPlayActivity();
        }
        // A lit campfire in the village draws people in: one free moment in three by day,
        // two in three from late afternoon on, when the day's work is winding down.
        long timeOfDay = ctx.level().getDayTime() % 24_000L;
        int campfireOdds = timeOfDay >= CAMPFIRE_EVENING_FROM ? 66 : 33;
        if (CampfireActivity.worthIt(ctx) && ctx.npc().getRandom().nextInt(100) < campfireOdds) {
            return new CampfireActivity();
        }
        VillagerStats n = ctx.brain().needs();
        Personality p = ctx.brain().personality();

        // Socialize: desire increases with low happiness and available social capacity
        float social = n.wellbeingRules().socialScore(
                n, p, ctx.brain().canStartSocialAction());

        // Admire: prefer when already happy (excess contentment → curiosity)
        float admire = n.wellbeingRules().admireScore(n);

        // Wander: always available as the baseline
        float wander = 5f;

        float best = Math.max(social, Math.max(admire, wander));

        if (best == social && social > 0) return new SocializeActivity();
        if (best == admire && admire > 0) return new AdmireTownActivity();
        return new WanderActivity();
    }
}
