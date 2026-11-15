package com.alex.test.village.villagers.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.random.RandomGenerator;

/** Initial profession-skill rolls plus their compact save format. */
public final class ProfessionSkills {
    public static final int MAX_INITIAL_SKILLS = 3;
    public static final int MAX_SKILLS = 4;
    public static final int MIN_LEVEL = 1;
    public static final int MAX_LEVEL = 100;
    public static final int MAX_INITIAL_LEVEL = 34;
    public static final double FIRST_SKILL_CHANCE = 0.90;
    public static final double SECOND_SKILL_CHANCE = 0.50;
    public static final double THIRD_SKILL_CHANCE = 0.20;

    /**
     * Starting levels climb one at a time from level one; the chance to climb from level L
     * is {@code 1 - L * LEVEL_CLIMB_DROP}, so every level is rarer than the one below it.
     */
    private static final double LEVEL_CLIMB_DROP = 0.011;

    /**
     * Starting intelligence uses the same climbing roll with a steeper drop: average 9,
     * median 9, 15+ about 12%, 20+ about 2%. The cap only bounds the spawn roll;
     * intelligence still grows to 100 in play.
     */
    public static final int MAX_INITIAL_INTELLIGENCE = 30;
    private static final double INTELLIGENCE_CLIMB_DROP = 0.0181;

    private static final String TAG_PROFESSION = "Profession";
    private static final String TAG_LEVEL = "Level";

    private ProfessionSkills() {}

    public static List<ProfessionSkill> rollInitial(
            RandomGenerator random,
            List<Profession> eligible,
            Profession preferredFirst) {
        return rollInitial(new RandomValues() {
            @Override public double nextDouble() { return random.nextDouble(); }
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
        }, eligible, preferredFirst);
    }

    public static List<ProfessionSkill> rollInitial(
            RandomValues random,
            List<Profession> eligible,
            Profession preferredFirst) {
        List<Profession> pool = new ArrayList<>(new LinkedHashSet<>(eligible));
        pool.removeIf(p -> p == null || p == Profession.NONE || p == Profession.CHILD);
        if (pool.isEmpty()) return List.of();

        for (int i = pool.size() - 1; i > 0; i--) {
            java.util.Collections.swap(pool, i, random.nextInt(i + 1));
        }
        if (preferredFirst != null && pool.remove(preferredFirst)) pool.addFirst(preferredFirst);

        int count = 0;
        if (!pool.isEmpty() && random.nextDouble() < FIRST_SKILL_CHANCE) {
            count++;
            if (pool.size() > 1 && random.nextDouble() < SECOND_SKILL_CHANCE) {
                count++;
                if (pool.size() > 2 && random.nextDouble() < THIRD_SKILL_CHANCE) count++;
            }
        }
        count = Math.min(count, Math.min(MAX_INITIAL_SKILLS, pool.size()));

        List<ProfessionSkill> skills = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            skills.add(new ProfessionSkill(pool.get(i), rollLevel(random.nextDouble())));
        }
        return List.copyOf(skills);
    }

    /** Adds a newly assigned profession to the first free fallback slot without disturbing existing skills. */
    public static List<ProfessionSkill> appendAssignedProfession(
            List<ProfessionSkill> existing,
            Profession profession,
            RandomGenerator random) {
        return appendAssignedProfession(existing, profession, new RandomValues() {
            @Override public double nextDouble() { return random.nextDouble(); }
            @Override public int nextInt(int bound) { return random.nextInt(bound); }
        });
    }

    public static List<ProfessionSkill> appendAssignedProfession(
            List<ProfessionSkill> existing,
            Profession profession,
            RandomValues random) {
        List<ProfessionSkill> skills = new ArrayList<>(existing.stream()
                .limit(MAX_SKILLS)
                .toList());
        if (cannotAppend(skills, profession)) return List.copyOf(skills);
        skills.add(new ProfessionSkill(profession, rollLevel(random.nextDouble())));
        return List.copyOf(skills);
    }

    /**
     * Adds a newly assigned profession at an explicit starting level. This preserves a
     * matching pre-rolled skill, which lets recruitment retain a nomad's aptitude.
     */
    public static List<ProfessionSkill> appendAssignedProfession(
            List<ProfessionSkill> existing,
            Profession profession,
            int startingLevel) {
        List<ProfessionSkill> skills = new ArrayList<>(existing.stream()
                .limit(MAX_SKILLS)
                .toList());
        if (cannotAppend(skills, profession)) return List.copyOf(skills);
        skills.add(new ProfessionSkill(profession,
                Math.clamp(startingLevel, MIN_LEVEL, MAX_LEVEL)));
        return List.copyOf(skills);
    }

    private static boolean cannotAppend(List<ProfessionSkill> skills, Profession profession) {
        return profession == null || profession == Profession.NONE || profession == Profession.CHILD
                || skills.size() >= MAX_SKILLS
                || skills.stream().anyMatch(skill -> skill.profession() == profession);
    }

    /** Returns the saved slot unchanged, fills a free slot at level one, or replaces a chosen slot. */
    public static List<ProfessionSkill> forSwitch(
            List<ProfessionSkill> existing, Profession profession, int replacementSlot) {
        if (profession == null || profession == Profession.NONE || profession == Profession.CHILD) {
            return null;
        }
        List<ProfessionSkill> skills = new ArrayList<>(existing.stream()
                .limit(MAX_SKILLS).toList());
        if (skills.stream().anyMatch(skill -> skill.profession() == profession)) {
            return List.copyOf(skills);
        }
        if (skills.size() < MAX_SKILLS) {
            return appendAssignedProfession(skills, profession, MIN_LEVEL);
        }
        if (replacementSlot < 0 || replacementSlot >= MAX_SKILLS) return null;
        skills.set(replacementSlot, new ProfessionSkill(profession, MIN_LEVEL));
        return List.copyOf(skills);
    }

    /** Rolls an initial level from the climbing curve. */
    public static int rollLevel(RandomGenerator random) {
        return rollLevel(random.nextDouble());
    }

    /**
     * One sample walks the climbing curve: {@code reach} is the chance of getting past the
     * current level, and the roll stops at the first level whose climb the sample misses.
     */
    private static int rollLevel(double sample) {
        return climb(sample, LEVEL_CLIMB_DROP, MAX_INITIAL_LEVEL);
    }

    /** Rolls a new villager's or nomad's starting intelligence, independent of their skills. */
    public static int rollIntelligence(RandomValues random) {
        return climb(random.nextDouble(), INTELLIGENCE_CLIMB_DROP, MAX_INITIAL_INTELLIGENCE);
    }

    private static int climb(double sample, double drop, int cap) {
        double u = Math.max(0.0, Math.min(Math.nextDown(1.0), sample));
        double reach = 1.0;
        int level = MIN_LEVEL;
        while (level < cap) {
            reach *= Math.max(0.0, 1.0 - level * drop);
            if (u >= reach) break;
            level++;
        }
        return level;
    }

    public static ListTag save(List<ProfessionSkill> skills) {
        ListTag result = new ListTag();
        for (ProfessionSkill skill : skills) {
            CompoundTag entry = new CompoundTag();
            entry.putString(TAG_PROFESSION, skill.profession().name());
            entry.putInt(TAG_LEVEL, skill.level());
            result.add(entry);
        }
        return result;
    }

    public static List<ProfessionSkill> load(CompoundTag owner, String key) {
        if (!owner.contains(key, Tag.TAG_LIST)) return List.of();
        ListTag saved = owner.getList(key, Tag.TAG_COMPOUND);
        List<ProfessionSkill> result = new ArrayList<>(Math.min(saved.size(), MAX_SKILLS));
        LinkedHashSet<Profession> seen = new LinkedHashSet<>();
        for (int i = 0; i < saved.size() && result.size() < MAX_SKILLS; i++) {
            CompoundTag entry = saved.getCompound(i);
            Profession profession = Profession.byName(entry.getString(TAG_PROFESSION));
            if (profession == Profession.NONE || profession == Profession.CHILD || !seen.add(profession)) continue;
            result.add(new ProfessionSkill(profession, entry.getInt(TAG_LEVEL)));
        }
        return List.copyOf(result);
    }

    public interface RandomValues {
        double nextDouble();
        int nextInt(int bound);
    }
}
