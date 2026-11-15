package com.alex.test.village.villagers.cleric;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.villagers.cleric.behavior.ClericActivity;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardShift;
import com.alex.test.village.villagers.settings.ProfessionSettings;

/** Wires the Cleric profession to its behavior implementation. */
public final class ClericProfession {

    public static final Profession PROFESSION = Profession.CLERIC;

    private ClericProfession() {}

    public static WorkActivity createWorkActivity() {
        return new ClericActivity();
    }

    /**
     * Work hours: the day like any villager, or with Night Shift on the
     * guards' night shift (from the evening shift change until dawn).
     */
    public static boolean shouldWorkNow(Activity.Context ctx) {
        if (worksNightShift(ctx.npc())) return GuardShift.isShiftNight(ctx);
        return VillagerBrain.isWorkHours(ctx);
    }

    /** A working cleric with Night Shift on: sleeps by day, works at night. */
    public static boolean worksNightShift(NpcEntity cleric) {
        ProfessionSettings settings = ProfessionSettings.of(cleric, ClericSettingsSchema.SCHEMA);
        return settings.getBool(ClericSettingsSchema.WORK_ENABLED)
                && settings.getBool(ClericSettingsSchema.NIGHT_SHIFT);
    }

    public static boolean hasImmediateWork(Activity.Context ctx) {
        return ClericActivity.hasWork(ctx);
    }

    /** Someone hurt, or starving with nothing to eat: worth cutting leisure short for. */
    public static boolean hasUrgentWork(Activity.Context ctx) {
        return ClericActivity.hasUrgentWork(ctx);
    }
}
