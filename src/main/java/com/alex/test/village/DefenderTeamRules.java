package com.alex.test.village;

import com.alex.test.village.villagers.core.Profession;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * How the village's defenders split up to fight monsters, set at the Mayor:
 * Settings > Village rules (how many go to one monster, when resting
 * defenders come out) and Settings > Professions (how each defender job
 * answers fights, and how many of that job one monster may have). Saved with
 * the village in {@link VillageRules}. Read by {@code GuardAlertSystem}.
 */
public record DefenderTeamRules(TeamSize teamSize, RestingCallOut resting, List<RoleRule> roles) {

    /** How many defenders go to one monster, on top of what the monster itself calls for. */
    public enum TeamSize {
        /** One fewer than normal, never fewer than one. */
        FEW("Few", -1),
        /** 2 for a zombie, 3 for a witch, 5 for a ravager. */
        NORMAL("Normal", 0),
        /** One more than normal. */
        MANY("Many", 1),
        /** No teams: every defender runs to every fight, the old way. */
        EVERYONE("Everyone", 0);

        private final String label;
        private final int extra;

        TeamSize(String label, int extra) {
            this.label = label;
            this.extra = extra;
        }

        public String label() { return label; }

        public int extra() { return extra; }

        public TeamSize next() { return values()[(ordinal() + 1) % values().length]; }

        public TeamSize previous() { return values()[(ordinal() + values().length - 1) % values().length]; }

        public static TeamSize byIndex(int index) {
            TeamSize[] values = values();
            return index >= 0 && index < values.length ? values[index] : NORMAL;
        }
    }

    /** When defenders who are off duty (resting, asleep) come out for a fight. */
    public enum RestingCallOut {
        /** Only when the defenders on duty are too few for the monsters. */
        WHEN_SHORT("When short"),
        /** For every monster in the village, the old way. */
        EVERY_FIGHT("Every fight"),
        /** Only for the bell, or a monster right next to them. */
        BELL_ONLY("Bell only");

        private final String label;

        RestingCallOut(String label) {
            this.label = label;
        }

        public String label() { return label; }

        public RestingCallOut next() { return values()[(ordinal() + 1) % values().length]; }

        public RestingCallOut previous() { return values()[(ordinal() + values().length - 1) % values().length]; }

        public static RestingCallOut byIndex(int index) {
            RestingCallOut[] values = values();
            return index >= 0 && index < values.length ? values[index] : WHEN_SHORT;
        }
    }

    /** How one defender job answers fights across the village. */
    public enum Answer {
        /** Goes when it is among the closest the monster's team needs. */
        CLOSEST("Closest"),
        /** Goes to every fight, whether the team is full or not (the old way). */
        EVERY_FIGHT("Every fight"),
        /** Never sent across the village: only fights what comes close or hits it. */
        NEARBY_ONLY("Nearby only");

        private final String label;

        Answer(String label) {
            this.label = label;
        }

        public String label() { return label; }

        public Answer next() { return values()[(ordinal() + 1) % values().length]; }

        public Answer previous() { return values()[(ordinal() + values().length - 1) % values().length]; }

        public static Answer byIndex(int index) {
            Answer[] values = values();
            return index >= 0 && index < values.length ? values[index] : CLOSEST;
        }
    }

    /** The defender jobs, in the order their rules are kept. */
    public enum Role {
        GUARD(Profession.GUARD, "Guard"),
        CAPTAIN(Profession.CAPTAIN, "Captain"),
        ARCHER(Profession.ARCHER, "Archer"),
        MAGE(Profession.MAGE, "Mage");

        private final Profession profession;
        private final String label;

        Role(Profession profession, String label) {
            this.profession = profession;
            this.label = label;
        }

        public Profession profession() { return profession; }

        public String label() { return label; }

        @Nullable
        public static Role of(Profession profession) {
            for (Role role : values()) {
                if (role.profession == profession) return role;
            }
            return null;
        }

        @Nullable
        public static Role byLabel(String label) {
            for (Role role : values()) {
                if (role.label.equalsIgnoreCase(label)) return role;
            }
            return null;
        }
    }

    /** The most of one job on a monster's team: 0 means no limit. */
    public static final int MAX_PER_MONSTER_LIMIT = 3;

    /** One job's rule: how it answers fights, and the most of it on one monster (0 = no limit). */
    public record RoleRule(Answer answer, int mostPerMonster) {
        public static final RoleRule DEFAULT = new RoleRule(Answer.CLOSEST, 0);

        public RoleRule {
            if (answer == null) answer = Answer.CLOSEST;
            mostPerMonster = Math.max(0, Math.min(MAX_PER_MONSTER_LIMIT, mostPerMonster));
        }

        public RoleRule withAnswer(Answer value) { return new RoleRule(value, mostPerMonster); }

        public RoleRule withMostPerMonster(int value) { return new RoleRule(answer, value); }

        /** "No limit", "1", "2" or "3". */
        public String mostLabel() {
            return mostPerMonster <= 0 ? "No limit" : Integer.toString(mostPerMonster);
        }
    }

    public static final DefenderTeamRules DEFAULT = new DefenderTeamRules(
            TeamSize.NORMAL, RestingCallOut.WHEN_SHORT, defaultRoles());

    public DefenderTeamRules {
        if (teamSize == null) teamSize = TeamSize.NORMAL;
        if (resting == null) resting = RestingCallOut.WHEN_SHORT;
        List<RoleRule> fixed = new ArrayList<>(Role.values().length);
        for (int i = 0; i < Role.values().length; i++) {
            RoleRule rule = roles != null && i < roles.size() ? roles.get(i) : null;
            fixed.add(rule != null ? rule : RoleRule.DEFAULT);
        }
        roles = List.copyOf(fixed);
    }

    private static List<RoleRule> defaultRoles() {
        List<RoleRule> roles = new ArrayList<>();
        for (int i = 0; i < Role.values().length; i++) roles.add(RoleRule.DEFAULT);
        return roles;
    }

    public RoleRule role(Role role) {
        return roles.get(role.ordinal());
    }

    /** The rule for a defender's job; any other job gets the default. */
    public RoleRule role(Profession profession) {
        Role role = Role.of(profession);
        return role != null ? role(role) : RoleRule.DEFAULT;
    }

    public DefenderTeamRules withTeamSize(TeamSize value) {
        return new DefenderTeamRules(value, resting, roles);
    }

    public DefenderTeamRules withResting(RestingCallOut value) {
        return new DefenderTeamRules(teamSize, value, roles);
    }

    public DefenderTeamRules withRole(Role role, RoleRule rule) {
        List<RoleRule> changed = new ArrayList<>(roles);
        changed.set(role.ordinal(), rule);
        return new DefenderTeamRules(teamSize, resting, changed);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeVarInt(teamSize.ordinal());
        buffer.writeVarInt(resting.ordinal());
        for (RoleRule rule : roles) {
            buffer.writeVarInt(rule.answer().ordinal());
            buffer.writeVarInt(rule.mostPerMonster());
        }
    }

    public static DefenderTeamRules read(FriendlyByteBuf buffer) {
        TeamSize size = TeamSize.byIndex(buffer.readVarInt());
        RestingCallOut resting = RestingCallOut.byIndex(buffer.readVarInt());
        List<RoleRule> roles = new ArrayList<>();
        for (int i = 0; i < Role.values().length; i++) {
            roles.add(new RoleRule(Answer.byIndex(buffer.readVarInt()), buffer.readVarInt()));
        }
        return new DefenderTeamRules(size, resting, roles);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("teamSize", teamSize.name());
        tag.putString("resting", resting.name());
        for (Role role : Role.values()) {
            CompoundTag roleTag = new CompoundTag();
            roleTag.putString("answer", role(role).answer().name());
            roleTag.putInt("most", role(role).mostPerMonster());
            tag.put(role.name().toLowerCase(java.util.Locale.ROOT), roleTag);
        }
        return tag;
    }

    /** Reads saved rules; anything missing (an older save) keeps its default. */
    public static DefenderTeamRules load(@Nullable CompoundTag tag) {
        if (tag == null) return DEFAULT;
        TeamSize size = enumOr(TeamSize.class, tag.getString("teamSize"), TeamSize.NORMAL);
        RestingCallOut resting = enumOr(RestingCallOut.class, tag.getString("resting"), RestingCallOut.WHEN_SHORT);
        List<RoleRule> roles = new ArrayList<>();
        for (Role role : Role.values()) {
            String key = role.name().toLowerCase(java.util.Locale.ROOT);
            if (!tag.contains(key, net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                roles.add(RoleRule.DEFAULT);
                continue;
            }
            CompoundTag roleTag = tag.getCompound(key);
            roles.add(new RoleRule(enumOr(Answer.class, roleTag.getString("answer"), Answer.CLOSEST),
                    roleTag.getInt("most")));
        }
        return new DefenderTeamRules(size, resting, roles);
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) return fallback;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
