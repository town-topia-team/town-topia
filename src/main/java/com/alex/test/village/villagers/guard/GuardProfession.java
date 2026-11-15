package com.alex.test.village.villagers.guard;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardActivity;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.settings.GuardSettings;

/** Wires the Guard profession to its night patrol and threat-response behavior. */
public final class GuardProfession {

    public static final Profession PROFESSION = Profession.GUARD;

    private GuardProfession() {}

    public static WorkActivity createWorkActivity() {
        return new GuardActivity();
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return shouldWorkNow(ctx);
    }

    public static boolean shouldWorkNow(Activity.Context ctx) {
        GuardSettings settings = GuardSettings.of(ctx.npc());
        // The defenders' own shift clock, not the world night: see GuardShift.
        boolean scheduled = GuardShift.scheduled(ctx, settings);
        return scheduled || GuardAlertSystem.hasEmergencyForGuard(ctx);
    }
}
