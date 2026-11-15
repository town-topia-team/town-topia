package com.alex.test.village.villagers.druid;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.druid.behavior.DruidActivity;

/** Wires the Druid profession to its behavior implementation. */
public final class DruidProfession {

    public static final Profession PROFESSION = Profession.DRUID;

    private DruidProfession() {}

    public static WorkActivity createWorkActivity() {
        return new DruidActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return DruidActivity.hasWork(ctx);
    }
}
