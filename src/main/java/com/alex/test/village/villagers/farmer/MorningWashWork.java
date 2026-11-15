package com.alex.test.village.villagers.farmer;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.farmer.behavior.WashActivity;

import javax.annotation.Nullable;

/**
 * The wash a farmer missed last night because the cauldrons had too few washes
 * for everyone: done first thing in the morning, before field work, until noon.
 * The same wash and the same line as in the evening ({@link WashActivity}).
 */
public final class MorningWashWork extends WorkActivity {

    private final WashActivity wash;

    private MorningWashWork(WashActivity wash) {
        this.wash = wash;
    }

    /** The morning wash, when one is owed, the farmer is still dirty and a cauldron has room. */
    @Nullable
    public static MorningWashWork create(Activity.Context ctx) {
        java.util.UUID me = ctx.model().npcId;
        if (!FarmerCauldrons.owesMorningWash(me, ctx.level().getGameTime())) return null;
        if (ctx.npc().getDirt() <= 0f) {
            FarmerCauldrons.clearMorningWash(me);
            return null;
        }
        FarmerCauldrons.WashChoice choice = FarmerCauldrons.pickWash(ctx);
        if (choice == null) return null;
        ctx.brain().diary().log(String.format("%s WASH morning pick %s %s lvl=%d%s",
                VillagerDiary.ts(ctx.level()), choice.where().label, choice.spot().pos().toShortString(),
                choice.level(), choice.busy() ? " (in use, will wait)" : ""));
        return new MorningWashWork(WashActivity.forChoice(ctx, choice));
    }

    /** A morning wash is owed, the farmer is still dirty, and some cauldron has room for it. */
    public static boolean isDue(Activity.Context ctx) {
        return FarmerCauldrons.owesMorningWash(ctx.model().npcId, ctx.level().getGameTime())
                && ctx.npc().getDirt() > 0f && FarmerCauldrons.pickWash(ctx) != null;
    }

    @Override
    protected Status doTick(Context ctx) {
        Status status = wash.tickOuter(ctx);
        // One try a morning: a failed wash waits for the evening one, rather
        // than coming back before every work pick until noon.
        if (status == Status.FAILED) FarmerCauldrons.clearMorningWash(ctx.model().npcId);
        return status;
    }

    @Override
    protected void doStop(Context ctx) {
        wash.stop(ctx);
    }

    @Override
    public String name() {
        return "morning_wash";
    }
}
