package com.alex.test.village.villagers.cook;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.core.Profession;

/** Wires the Cook profession to its behavior implementation. */
public final class CookProfession {

    public static final Profession PROFESSION = Profession.COOK;

    private CookProfession() {}

    public static WorkActivity createWorkActivity() {
        return new CookActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return CookActivity.hasWork(ctx);
    }
}
