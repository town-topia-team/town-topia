package com.alex.test.village.villagers.bard;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.bard.behavior.BardActivity;
import com.alex.test.village.villagers.core.Profession;

/** Wires the Bard profession to its behavior implementation. */
public final class BardProfession {

    public static final Profession PROFESSION = Profession.BARD;

    private BardProfession() {}

    public static WorkActivity createWorkActivity() {
        return new BardActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return BardActivity.hasWork(ctx);
    }
}
