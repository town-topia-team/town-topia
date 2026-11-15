package com.alex.test.village.villagers.farmer;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.behavior.CauldronRefillWork;
import com.alex.test.village.villagers.farmer.behavior.FarmerActivity;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;

/** Wires the Farmer profession to its behavior implementation. */
public final class FarmerProfession {

    public static final Profession PROFESSION = Profession.FARMER;

    private FarmerProfession() {}

    public static WorkActivity createWorkActivity() {
        return new FarmerActivity();
    }

    /**
     * A wash missed last night comes first, before noon. Then field work; a
     * farmer with none refills a cauldron that needs water, or uses up a water
     * bucket it was left holding (its lowest-priority job).
     */
    public static WorkActivity createWorkActivity(Activity.Context ctx) {
        MorningWashWork wash = MorningWashWork.create(ctx);
        if (wash != null) return wash;
        if (!FarmerActivity.hasWork(ctx) && FarmerSettings.of(ctx.npc()).works()) {
            CauldronRefillWork refill = CauldronRefillWork.create(ctx);
            if (refill != null) return refill;
        }
        return new FarmerActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        if (FarmerActivity.hasWork(ctx)) return true;
        // A farmer with nothing ripe still goes for the wash it missed last night.
        if (MorningWashWork.isDue(ctx)) return true;
        return FarmerSettings.of(ctx.npc()).works() && FarmerCauldrons.hasRefillWork(ctx);
    }
}
