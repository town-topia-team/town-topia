package com.alex.test.village.villagers.archer;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.archer.behavior.ArcherActivity;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardProfession;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;

/** Wires the Archer profession to its behavior implementation. */
public final class ArcherProfession {

    public static final Profession PROFESSION = Profession.ARCHER;

    private ArcherProfession() {}

    public static WorkActivity createWorkActivity() {
        return new ArcherActivity();
    }

    // Reuses GuardProfession's schedule/emergency check rather than
    // duplicating it: an archer works the same night-patrol-or-emergency
    // schedule a guard does, driven by the same GuardSettings/GuardAlertSystem
    // state (see GuardAlertSystem.isGuardOrArcher for the assignment-side half
    // of this reuse). On top of that shared schedule, an archer also has its
    // own WORK_ENABLED master toggle (ArcherSettingsSchema) -- unlike Guard,
    // which has no equivalent -- so it must be checked here rather than
    // relying solely on GuardProfession.shouldWorkNow, otherwise disabling
    // "Archer duty enabled" would never register as "no immediate work" and
    // VillagerBrain would keep recreating ArcherActivity every cycle only for
    // it to self-terminate on its own WORK_ENABLED check.
    public static boolean hasImmediateWork(Activity.Context ctx) {
        if (!ProfessionSettings.of(ctx.npc(), ArcherSettingsSchema.SCHEMA)
                .getBool(ArcherSettingsSchema.WORK_ENABLED)) {
            return false;
        }
        return GuardProfession.shouldWorkNow(ctx);
    }
}
