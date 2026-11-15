package com.alex.test.village.villagers.core;

/** A persistent profession aptitude shown in the villager information screen. */
public record ProfessionSkill(Profession profession, int level) {
    public ProfessionSkill {
        profession = profession == null ? Profession.NONE : profession;
        level = Math.max(1, Math.min(100, level));
    }
}
