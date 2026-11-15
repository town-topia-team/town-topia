package com.alex.test.village.villagers.mage;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.mage.settings.MageSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;

/**
 * The mage screen's night shift, guard posts and time at each post are the
 * guard's settings, which the mage's duty (the shift, the posts, sleeping by
 * day) reads from {@link GuardSettings}, as the archer's does. So is its
 * Monsters list: the guards' alert system offers each defender only the mobs
 * its {@link GuardSettings} allow. Changing them on the mage screen copies them
 * over, and so does every start of the mage's duty, so a villager that was a
 * guard before keeps none of its old guard settings behind the mage screen.
 */
public final class MageSettingsBridge {

    private MageSettingsBridge() {}

    /** Copies the mage screen's shared settings into the mage's {@link GuardSettings}. */
    public static void applyToGuardSettings(NpcEntity mage) {
        ProfessionSettings settings = ProfessionSettings.of(mage, MageSettingsSchema.SCHEMA);
        GuardSettings guard = GuardSettings.of(mage);
        boolean night = settings.getBool(MageSettingsSchema.NIGHT_SHIFT);
        if (guard.patrolAtNightOnly() != night) guard.setPatrolAtNightOnly(night);
        boolean posts = settings.getBool(MageSettingsSchema.USE_GUARD_POSTS);
        if (guard.useGuardPosts() != posts) guard.setUseGuardPosts(posts);
        int seconds = settings.getInt(MageSettingsSchema.POST_SECONDS);
        if (guard.guardPostStaySeconds() != seconds) guard.setGuardPostStaySeconds(seconds);
        boolean mobsChanged = false;
        for (GuardMobPolicy.MobOption option : GuardMobPolicy.options()) {
            boolean allowed = settings.isAllowed(MageSettingsSchema.ALLOWED_MOB_TARGETS, option.id().toString());
            if (guard.isMobAllowed(option.id()) != allowed) {
                guard.setMobAllowed(option.id(), allowed);
                mobsChanged = true;
            }
        }
        if (mobsChanged) GuardThreatRules.invalidateVillagePolicies();
    }

    public static ProfessionSettings of(NpcEntity mage) {
        return ProfessionSettings.of(mage, MageSettingsSchema.SCHEMA);
    }

    public static boolean setting(NpcEntity mage, String id) {
        return of(mage).getBool(id);
    }
}
