package com.alex.test.village.villagers.archer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;

/**
 * The archer screen's night shift, guard posts and time at each post are the
 * guard's settings, which the archer and everything around it (drill and range
 * time, watchtowers, daytime rest) read from {@link GuardSettings}. Changing
 * them on the archer screen copies them over.
 */
public final class ArcherSettingsBridge {

    private ArcherSettingsBridge() {}

    /** Copies the archer screen's shared settings into the archer's {@link GuardSettings}. */
    public static void applyToGuardSettings(NpcEntity archer) {
        ProfessionSettings settings = ProfessionSettings.of(archer, ArcherSettingsSchema.SCHEMA);
        GuardSettings guard = GuardSettings.of(archer);
        guard.setPatrolAtNightOnly(settings.getBool(ArcherSettingsSchema.NIGHT_SHIFT));
        guard.setUseGuardPosts(settings.getBool(ArcherSettingsSchema.USE_GUARD_POSTS));
        guard.setGuardPostStaySeconds(settings.getInt(ArcherSettingsSchema.POST_SECONDS));
    }
}
