package com.alex.test.village.villagers.enchanter;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity;

/** Wires the Enchanter profession to its behavior implementation. */
public final class EnchanterProfession {

    public static final Profession PROFESSION = Profession.ENCHANTER;

    private EnchanterProfession() {}

    public static WorkActivity createWorkActivity() {
        return new EnchanterActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return BookFitting.hasCustomer(ctx) || EnchanterActivity.hasWork(ctx);
    }
}
