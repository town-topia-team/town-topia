package com.alex.test.village;

import com.alex.test.village.log.VillageLog;
import com.alex.test.village.raid.RaidFrequency;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;

/**
 * Village-wide rules the player sets at the Mayor, under Settings > Village
 * rules. Saved with the village: whether births are allowed, the
 * watchtower bell's two rules, how often raids come (with the raid
 * manager's own saved state riding along), and how the defenders split up
 * to fight ({@link DefenderTeamRules}). The village's log of what happened
 * ({@link VillageLog}) rides along too.
 */
public final class VillageRules {

    /** When a player hurt by a monster inside the village makes a watchtower lookout ring its bell. */
    public enum PlayerHurtBell {
        /** Only villagers who are not defenders count. */
        NEVER("Never"),
        /** Also a player left at or below {@link #BADLY_HURT_HEALTH} of full health. */
        BADLY_HURT("Badly hurt"),
        /** Also every hit on a player. */
        ALWAYS("Always");

        private final String label;

        PlayerHurtBell(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * Badly hurt: a player the village's monsters have badly hurt gets the
         * village's help, so the village feels like it looks after them, but
         * one who trades a few blows on purpose does not set the bell off.
         */
        public static final PlayerHurtBell DEFAULT = BADLY_HURT;

        public PlayerHurtBell next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public static PlayerHurtBell byIndex(int index) {
            PlayerHurtBell[] values = values();
            return index >= 0 && index < values.length ? values[index] : DEFAULT;
        }
    }

    /** "Badly hurt": the hit left the player at or below this share of full health (8 of 20). */
    public static final float BADLY_HURT_HEALTH = 0.4f;

    private static final String TAG_PLAYER_HURT_BELL = "playerHurtBell";
    private static final String TAG_TOWER_ALARM_ENDS_WHEN_CLEAR = "towerAlarmEndsWhenClear";
    private static final String TAG_BIRTHS_ENABLED = "birthsEnabled";
    private static final String TAG_RAID_FREQUENCY = "raidFrequency";
    private static final String TAG_RAID_STATE = "raidState";
    private static final String TAG_DEFENDER_TEAMS = "defenderTeams";
    private static final String TAG_VILLAGE_LOG = "villageLog";

    private PlayerHurtBell playerHurtBell = PlayerHurtBell.DEFAULT;
    private boolean towerAlarmEndsWhenClear = true;
    private boolean birthsEnabled = true;
    private RaidFrequency raidFrequency = RaidFrequency.DEFAULT;
    private DefenderTeamRules defenderTeams = DefenderTeamRules.DEFAULT;
    /**
     * The raid in progress and the raid schedule, as {@link
     * com.alex.test.village.raid.RaidManager} saved them. Kept here so it
     * travels with the rules in the village save; only the raid manager reads
     * or writes it.
     */
    private CompoundTag raidState = new CompoundTag();
    /** What happened in the village (Mayor > Village Log). */
    private final VillageLog log = new VillageLog();

    public VillageLog log() {
        return log;
    }

    /** How often raids come by themselves; Off stops them. */
    public RaidFrequency raidFrequency() {
        return raidFrequency;
    }

    public void setRaidFrequency(RaidFrequency value) {
        raidFrequency = value != null ? value : RaidFrequency.DEFAULT;
    }

    public CompoundTag raidState() {
        return raidState;
    }

    public void setRaidState(@Nullable CompoundTag value) {
        raidState = value != null ? value : new CompoundTag();
    }

    /** How the defenders split up to fight monsters (Village rules and Professions at the Mayor). */
    public DefenderTeamRules defenderTeams() {
        return defenderTeams;
    }

    public void setDefenderTeams(DefenderTeamRules value) {
        defenderTeams = value != null ? value : DefenderTeamRules.DEFAULT;
    }

    /** Whether the player has left "Allow births" on at the Mayor. */
    public boolean birthsEnabled() {
        return birthsEnabled;
    }

    public void setBirthsEnabled(boolean value) {
        birthsEnabled = value;
    }

    public PlayerHurtBell playerHurtBell() {
        return playerHurtBell;
    }

    public void setPlayerHurtBell(PlayerHurtBell value) {
        playerHurtBell = value != null ? value : PlayerHurtBell.DEFAULT;
    }

    /**
     * Whether an alarm a lookout started ends 30 seconds after the last
     * monster inside the village is gone, rather than lasting while any
     * monster is near any villager (which can be all night).
     */
    public boolean towerAlarmEndsWhenClear() {
        return towerAlarmEndsWhenClear;
    }

    public void setTowerAlarmEndsWhenClear(boolean value) {
        towerAlarmEndsWhenClear = value;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(TAG_PLAYER_HURT_BELL, playerHurtBell.name());
        tag.putBoolean(TAG_TOWER_ALARM_ENDS_WHEN_CLEAR, towerAlarmEndsWhenClear);
        tag.putBoolean(TAG_BIRTHS_ENABLED, birthsEnabled);
        tag.putString(TAG_RAID_FREQUENCY, raidFrequency.name());
        if (!raidState.isEmpty()) tag.put(TAG_RAID_STATE, raidState.copy());
        tag.put(TAG_DEFENDER_TEAMS, defenderTeams.save());
        if (!log.isEmpty()) tag.put(TAG_VILLAGE_LOG, log.save());
        return tag;
    }

    /** Reads saved rules; missing ones (an older save) keep their defaults. */
    public void load(@Nullable CompoundTag tag) {
        if (tag == null) return;
        if (tag.contains(TAG_PLAYER_HURT_BELL)) {
            try {
                playerHurtBell = PlayerHurtBell.valueOf(tag.getString(TAG_PLAYER_HURT_BELL));
            } catch (IllegalArgumentException ignored) {
                playerHurtBell = PlayerHurtBell.DEFAULT;
            }
        }
        if (tag.contains(TAG_TOWER_ALARM_ENDS_WHEN_CLEAR)) {
            towerAlarmEndsWhenClear = tag.getBoolean(TAG_TOWER_ALARM_ENDS_WHEN_CLEAR);
        }
        if (tag.contains(TAG_BIRTHS_ENABLED)) {
            birthsEnabled = tag.getBoolean(TAG_BIRTHS_ENABLED);
        }
        if (tag.contains(TAG_RAID_FREQUENCY)) {
            raidFrequency = RaidFrequency.byName(tag.getString(TAG_RAID_FREQUENCY));
        }
        defenderTeams = tag.contains(TAG_DEFENDER_TEAMS, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? DefenderTeamRules.load(tag.getCompound(TAG_DEFENDER_TEAMS)) : DefenderTeamRules.DEFAULT;
        raidState = tag.contains(TAG_RAID_STATE, net.minecraft.nbt.Tag.TAG_COMPOUND)
                ? tag.getCompound(TAG_RAID_STATE).copy() : new CompoundTag();
        log.load(tag.contains(TAG_VILLAGE_LOG, net.minecraft.nbt.Tag.TAG_COMPOUND) ? tag.getCompound(TAG_VILLAGE_LOG) : null);
    }
}
