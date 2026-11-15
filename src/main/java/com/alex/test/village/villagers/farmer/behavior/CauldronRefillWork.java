package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.CauldronIndex;
import com.alex.test.village.villagers.farmer.FarmerCauldrons;

import javax.annotation.Nullable;

/**
 * An idle farmer's cauldron work, as its routine work session: refilling a
 * home or storage cauldron that needs water, or using up a water bucket it
 * was left holding. The farmer's lowest-priority job: it only comes up when
 * {@link FarmerActivity#hasWork} has nothing (see {@code VillagerRegistry}).
 */
public final class CauldronRefillWork extends WorkActivity {

    private final RefillCauldronActivity refill;

    private CauldronRefillWork(RefillCauldronActivity refill) {
        this.refill = refill;
    }

    /**
     * The cauldron work for an idle farmer now, or null. A water bucket with no
     * cauldron that needs it is emptied on the spot, and then the ordinary
     * refill search runs.
     */
    @Nullable
    public static CauldronRefillWork create(Activity.Context ctx) {
        if (FarmerCauldrons.carriesWaterBucket(ctx.npc())) {
            CauldronIndex.Spot needy = FarmerCauldrons.nextNeedy(ctx, null);
            if (needy != null) {
                return new CauldronRefillWork(new RefillCauldronActivity(needy.pos(),
                        needy.building().getItemFrameId(), RefillCauldronActivity.Purpose.STRANDED));
            }
            FarmerCauldrons.emptyStrandedBucket(ctx);
        }
        java.util.UUID me = ctx.model().npcId;
        net.minecraft.core.BlockPos target = FarmerCauldrons.refillTarget(ctx);
        if (target == null) return null;
        CauldronIndex.Spot spot = ctx.mgr().cauldrons().spotAt(ctx.mgr(), ctx.level(), target);
        FarmerCauldrons.forgetRefillCheck(me);
        if (spot == null) return null;
        return new CauldronRefillWork(new RefillCauldronActivity(spot.pos(),
                spot.building().getItemFrameId(), RefillCauldronActivity.Purpose.DAY));
    }

    @Override
    protected Status doTick(Context ctx) {
        return refill.tickOuter(ctx);
    }

    @Override
    protected void doStop(Context ctx) {
        refill.stop(ctx);
    }

    @Override
    public boolean allowsRoutineInterruption() {
        return !refill.inAction();
    }
}
