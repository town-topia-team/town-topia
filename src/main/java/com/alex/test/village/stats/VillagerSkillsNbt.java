package com.alex.test.village.stats;

import com.alex.test.village.villagers.core.Profession;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/**
 * {@link VillagerSkills} persistence, kept in its own class so VillagerSkills
 * itself loads without Minecraft on the classpath (plain JUnit tests).
 */
final class VillagerSkillsNbt {

    private static final String TAG_SKILLS = "Skills";

    private VillagerSkillsNbt() {}

    static void save(CompoundTag tag, float intelligence, Map<Profession, Float> experience) {
        tag.putFloat(VillagerSkills.TAG_INTELLIGENCE, intelligence);
        CompoundTag skills = new CompoundTag();
        // Keyed by enum name, not ordinal: profession ordinals are a network
        // detail that new entries are appended to, and a saved world must not
        // start reading somebody else's skill track when that happens.
        experience.forEach((profession, xp) -> {
            if (xp > 0f) skills.putFloat(profession.name(), xp);
        });
        tag.put(TAG_SKILLS, skills);
    }

    static void loadExperience(CompoundTag tag, Map<Profession, Float> experience) {
        experience.clear();
        if (!tag.contains(TAG_SKILLS)) return;
        CompoundTag skills = tag.getCompound(TAG_SKILLS);
        for (String key : skills.getAllKeys()) {
            Profession profession = Profession.byName(key);
            // byName falls back to NONE for anything unrecognised, which would
            // silently pile a removed profession's history onto the jobless
            // track. Drop it instead.
            if (profession == Profession.NONE && !Profession.NONE.name().equals(key)) continue;
            experience.put(profession, skills.getFloat(key));
        }
    }
}
