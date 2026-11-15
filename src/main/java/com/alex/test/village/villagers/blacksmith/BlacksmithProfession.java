package com.alex.test.village.villagers.blacksmith;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.blacksmith.behavior.BlacksmithActivity;
import com.alex.test.village.villagers.core.Profession;

/** Wires the Blacksmith profession to its behavior implementation. */
public final class BlacksmithProfession {

    public static final Profession PROFESSION = Profession.BLACKSMITH;

    private BlacksmithProfession() {}

    public static WorkActivity createWorkActivity() {
        return new BlacksmithActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return BlacksmithActivity.hasWork(ctx)
                || com.alex.test.village.villagers.enchanter.BookFitting.hasCustomer(ctx);
    }
}
