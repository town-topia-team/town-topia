package com.alex.test.village.villagers.butcher;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.butcher.behavior.ButcherActivity;
import com.alex.test.village.villagers.core.Profession;

/** Wires the Butcher profession to its behavior implementation. */
public final class ButcherProfession {

    public static final Profession PROFESSION = Profession.BUTCHER;

    private ButcherProfession() {}

    public static WorkActivity createWorkActivity() {
        return new ButcherActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return ButcherActivity.hasWork(ctx);
    }
}
