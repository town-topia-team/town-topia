package com.alex.test.village.villagers.mage;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardProfession;
import com.alex.test.village.villagers.mage.behavior.MageActivity;

/**
 * Wires the Mage profession to its behaviour. A mage works the guard's
 * schedule (night patrol, or any shift when there is an emergency), driven by
 * the same GuardSettings and GuardAlertSystem state archers use.
 */
public final class MageProfession {

    public static final Profession PROFESSION = Profession.MAGE;

    private MageProfession() {}

    public static WorkActivity createWorkActivity() {
        return new MageActivity();
    }

    /**
     * The defenders' schedule, unless "Working" is off in its settings: then
     * the brain does not start its work only to have it stop at once.
     */
    public static boolean hasImmediateWork(Activity.Context ctx) {
        if (!MageSettingsBridge.setting(ctx.npc(), com.alex.test.village.villagers.mage.settings.MageSettingsSchema.WORK_ENABLED)) {
            return false;
        }
        return GuardProfession.shouldWorkNow(ctx);
    }
}
