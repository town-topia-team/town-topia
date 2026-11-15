package com.alex.test.village.villagers.tradesman;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.behavior.TradesmanActivity;

/** Wires the Tradesman profession to its behavior implementation. */
public final class TradesmanProfession {

    public static final Profession PROFESSION = Profession.TRADESMAN;

    private TradesmanProfession() {}

    public static WorkActivity createWorkActivity() {
        return new TradesmanActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return TradesmanActivity.hasWork(ctx);
    }
}
