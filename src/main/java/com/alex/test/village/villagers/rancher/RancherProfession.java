package com.alex.test.village.villagers.rancher;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;

/** Wires the Rancher profession to its behavior implementation. */
public final class RancherProfession {

    public static final Profession PROFESSION = Profession.RANCHER;

    private RancherProfession() {}

    public static WorkActivity createWorkActivity() {
        return new RancherActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return RancherActivity.hasWork(ctx);
    }
}
