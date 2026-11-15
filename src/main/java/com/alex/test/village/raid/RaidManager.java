package com.alex.test.village.raid;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.chat.TownAlertPrefs;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.log.VillageLog;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * One village's raids: when the next one is due, and the raid in progress.
 *
 * <p>A raid goes warning (a day ahead) → camp (noon of the attack day, outside
 * the village) → assault (the horn at dusk, waves from the camp) → retreat
 * (the raid broke or ran out of time, survivors run to camp) → over (result,
 * mood, fame; the camp packs up at the next sunrise). Nothing moves on while
 * no player is near the village: a due raid waits, and an assault nobody is
 * watching pulls back to its camp and attacks again two minutes after a
 * player returns.
 *
 * <p>Raiders are vanilla illagers tagged with this raid ({@link RaidSpawner});
 * guards fight them as monsters, and {@link RaidMarchGoal} gives them their
 * orders. Saved with the village rules ({@link #save}, {@link #load}).
 */
public final class RaidManager {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Stage { IDLE, WARNING, CAMP, ASSAULT, RETREAT }

    /** What a tagged raider joining the world is to this raid. */
    public enum Adoption { ADOPTED, STALE, UNKNOWN }

    /** Ticks between stage checks. */
    public static final int CHECK_INTERVAL = 20;
    /** Most raiders alive at once. */
    public static final int MAX_ALIVE = 20;
    /** Grown villagers a village needs before raids come. */
    public static final int MIN_VILLAGERS = 6;
    /** The very first raid on a village is a small scouting party. */
    public static final int FIRST_RAID_BUDGET = 6;
    /** The camp goes up at noon of the attack day. */
    public static final long CAMP_CLOCK = 6_000L;
    /** The warband attacks between dusk and midnight. */
    public static final long ATTACK_CLOCK_START = 12_000L;
    public static final long ATTACK_CLOCK_END = 18_000L;
    /** The "Night attack" twist: after dark instead. */
    public static final long NIGHT_ATTACK_CLOCK_START = 14_000L;
    public static final long NIGHT_ATTACK_CLOCK_END = 20_000L;
    /** The curfew: the alarm goes this long before the attack (30 s), so everyone is in when it comes. */
    public static final int CURFEW_TICKS = 30 * 20;
    /** Riders camp the evening before and strike in the early morning, when farmers head out. */
    public static final long RIDER_CAMP_CLOCK = 12_000L;
    public static final long RIDER_CLOCK_START = 500L;
    public static final long RIDER_CLOCK_END = 4_000L;
    /** Riders give up after 4 minutes, or when half of them are down. */
    public static final int RIDER_LIMIT_TICKS = 4 * 60 * 20;
    public static final double RIDER_BREAK_SHARE = 0.5;
    /** Never a horn sooner than this after the camp went up (3 minutes). */
    public static final int HORN_AFTER_CAMP_TICKS = 3 * 60 * 20;
    /** The next wave comes after this long even if the last one is still fighting (90 s). */
    public static final int WAVE_AFTER_TICKS = 90 * 20;
    /** Time limit of an assault (6 minutes). */
    public static final int ASSAULT_LIMIT_TICKS = 6 * 60 * 20;
    /** How long survivors get to run back to camp (60 s). */
    public static final int RETREAT_LIMIT_TICKS = 60 * 20;
    /** Nobody near this long during an assault: the raid pulls back to camp (30 s). */
    public static final int PULL_BACK_AFTER_TICKS = 30 * 20;
    /** After a pull back, the attack comes again this long after a player returns (2 minutes). */
    public static final int RESUME_AFTER_TICKS = 2 * 60 * 20;
    /** A raider not found in the world for this long is no longer counted (60 s). */
    private static final int MISSING_DROP_TICKS = 60 * 20;
    /** A raider the village hit this recently still pays if a fall or fire finishes it (5 s, like Minecraft's player loot). */
    private static final int KILL_CREDIT_TICKS = 5 * 20;
    /** Share of the raid down that breaks it, or with the banner carrier down. */
    public static final double BREAK_SHARE = 0.6;
    public static final double LEADER_BREAK_SHARE = 0.4;
    /** Village-wide wellbeing lost per check during an assault (0.2 a village minute's twentieth). */
    private static final float WELLBEING_DRAIN_PER_CHECK = 0.2f / 3f;
    /** Checks without a camp spot before trying closer in, and before giving up. */
    private static final int CAMP_TRIES_NEAR = 10;
    private static final int CAMP_TRIES_MAX = 30;
    /** Past raids kept for the Mayor. */
    private static final int RECORD_SIZE = 10;
    private static final int MARCH_TARGET_CHOICES = 6;
    /** Doors closer than this (8 blocks) are where a raider already is, not somewhere to march to. */
    private static final double MARCH_ARRIVED_SQ = 8.0 * 8.0;
    /** Round 2 (raidnear): a near target is over 7 blocks off, past the march goal's 6-block arrival. */
    private static final double NEAR_TARGET_SQ = 7.0 * 7.0;
    /** Round 2: a spot round a tiny village, 8 to 12 blocks out from the middle of its doors, 4 tries. */
    private static final int PROWL_MIN = 8;
    private static final int PROWL_SPREAD = 5;
    private static final int PROWL_TRIES = 4;
    /** Share of the storage's worth looters may carry off in one raid. */
    public static final double LOOT_SHARE = 0.15;
    /** The horde comes an hour after dark... */
    public static final long HORDE_CLOCK_START = 13_500L;
    /** ...and no new horde attack starts this late in the night (it would meet the sunrise at once). */
    public static final long HORDE_CLOCK_END = 22_000L;
    /** A new group of the dead after this long, if the last one is still about (40 s). */
    public static final int HORDE_GROUP_TICKS = 40 * 20;
    /** The horde's sides: two or three spots around the village. */
    private static final int HORDE_SIDES_MIN = 2;
    private static final int HORDE_SIDES_MAX = 3;
    /** Factions remembered so the same one never comes three times running. */
    private static final int RECENT_FACTIONS = 2;

    /** The Bad Omen raid: the countdown before the horn (30 s), and how long its Raid Omen effect shows. */
    private static final int OMEN_COUNTDOWN_TICKS = 600;
    private static final int OMEN_EFFECT_TICKS = OMEN_COUNTDOWN_TICKS + 40;
    /** Grown villagers a village needs before a Bad Omen raid comes. */
    private static final int OMEN_MIN_VILLAGERS = 3;
    /** Sides the Bad Omen raid comes from, and the checks it looks for them before giving up. */
    private static final int OMEN_SIDES = 3;
    private static final int OMEN_SIDE_TRIES = 30;
    /** Nobody near this long (5 minutes) and the raiders leave. */
    private static final int OMEN_AWAY_TICKS = 5 * 60 * 20;
    /** The next wave comes this long (15 s) after the last one is beaten. */
    private static final int OMEN_NEXT_WAVE_TICKS = 15 * 20;
    /** No side running this long (60 s) after that: new sides are looked for once. */
    private static final int OMEN_SIDE_WAIT_TICKS = 60 * 20;
    /** A wave down to 3 raiders or fewer for this long (3 minutes): they glow, so they are found. */
    private static final int OMEN_STRAGGLER_TICKS = 3 * 60 * 20;
    private static final int OMEN_GLOW_TICKS = 1200;
    /** A Bad Omen handed back lasts this long (5 minutes). */
    private static final int OMEN_GIVE_BACK_TICKS = 6000;

    /** {@code omenLevel} is the Bad Omen level of a Bad Omen raid, else 0. */
    public record Record(long day, RaidFaction faction, RaidResult result, int beaten, int deaths, int escaped,
                         int stacksLost, int omenLevel) {}

    private final VillageManager village;
    private boolean loaded;
    private int timer;
    private int checks;

    // Lasting state.
    private final RaidSchedule schedule = new RaidSchedule();
    private long lastSeenDay = Long.MIN_VALUE;
    private boolean mayorUsed;
    private int fame;
    private int raidsSeen;
    private int raidsWon;
    private int generation;
    private final List<Record> record = new ArrayList<>();
    private final List<RaidCamp.Placed> campBlocks = new ArrayList<>();
    private long packUpDay = Long.MIN_VALUE;
    /** The last factions to raid, most recent first. */
    private final List<RaidFaction> recentFactions = new ArrayList<>();

    // The raid in progress.
    private Stage stage = Stage.IDLE;
    private RaidFaction faction = RaidFaction.WARBAND;
    /** Where the horde's groups come from (the warband's camp is {@link #camp}). */
    private final List<BlockPos> sides = new ArrayList<>();
    @Nullable private UUID raidId;
    private long attackDay;
    private int budget;
    private int tier = 1;
    private double defense;
    private int villagersAtStart;
    /** Guards, archers and captains when the Bad Omen raid was planned (with the villagers: its loss limit). */
    private int defendersAtStart;
    private double campAngle;
    private String sizeWord = "";
    private List<List<RaiderKind>> waves = new ArrayList<>();
    private int wavesSent;
    private int totalPlanned;
    private int lastWaveSize;
    private int beaten;
    private int deaths;
    private int escaped;
    @Nullable private UUID leaderId;
    private boolean leaderDown;
    /** The Bad Omen raid's Warlord, once its last wave brought it (kept after it falls: never a second). */
    @Nullable private UUID warlordId;
    /** The Warlord fell: the raid is won, and the raiders who run are not "got away". */
    private boolean warlordDown;
    private boolean pulledBack;
    /** Only while {@link #horn} calls off a raid whose first horn placed nobody. Not saved. */
    private boolean firstHornFailed;
    private boolean retreatBeaten;
    @Nullable private BlockPos camp;
    private long campBuiltAt;
    private long hornAt;
    private long lastWaveAt;
    private long retreatSince;
    private final Map<UUID, RaiderKind> alive = new LinkedHashMap<>();
    /** Emeralds' worth looters may carry off in this raid, and what they have taken so far. */
    private double lootCap;
    private double stolenValue;
    /** Stacks carried off to the camp for good. */
    private int stacksLost;
    /** Raiders able to lead that did not: every third of them (the first, the fourth...) loots. */
    private int nonLeaders;
    private boolean lootAlarmSent;
    /** The warband's scout during the warning day, where it stands, and whether it was beaten in time. */
    @Nullable private UUID scoutId;
    @Nullable private BlockPos scoutPost;
    private boolean scoutDown;
    /** This raid's twists, whether players were told of them, and whether the curfew was called. */
    private final List<RaidTwist> twists = new ArrayList<>();
    private boolean twistsRevealed;
    private boolean curfewSent;
    /**
     * A Bad Omen raid is the player's secret: the village only knows of it
     * once a bell is rung (by a player, a lookout or a guard). Until then
     * villagers go about their day and only run from raiders they see.
     */
    private boolean omenBellRung;
    /** Raiders spawned so far this raid (for elite gear: every third). */
    private int totalSpawned;
    /** What skeleton riders did to the outskirts: farm animals killed, crops ridden down. */
    private int animalsKilled;
    private int cropsLost;
    /** Defenders (npc ids) who beat a raider or were hurt by one: they share the raid's experience. */
    private final Set<UUID> fighters = new HashSet<>();
    /** The day fame last faded (a point every {@link #FAME_FADE_DAYS} days). */
    private long fameDecayDay = Long.MIN_VALUE;
    public static final int FAME_FADE_DAYS = 5;
    /** Cakes on the house at the tavern after a clean or plain win. */
    private static final int FEAST_CAKES = 3;
    /** A raid still waiting for its hour this many days after its attack day gives up. */
    private static final int GIVE_UP_DAYS = 2;
    /** Smallest warband budget the siege beast twist may bring a ravager into. */
    private static final int SIEGE_BEAST_MIN_BUDGET = 12;

    // The Bad Omen raid (faction CHALLENGE in the stage machine above): its level, when the horn sounds,
    // the players whose omens started it, and when the wave in the field was beaten.
    private int omenLevel;
    private long omenHornAt;
    private final List<UUID> omenPlayers = new ArrayList<>();
    private long waveClearedAt = -1;
    /** The strongest Bad Omen raid beaten here (0 = none yet). */
    private int bestOmenWon;
    /** The village day the last Bad Omen spoils were delivered (MIN_VALUE = never). */
    private long lastOmenSpoilsDay = Long.MIN_VALUE;
    /** This Bad Omen raid started too soon after the last spoils: it leaves none. */
    private boolean omenSpoilsHeld;

    // Minecraft's own raid (Bad Omen) on this village, steered as a challenge.
    private boolean challengeActive;
    private int challengeVanillaId = -1;
    /** Stands for this challenge in the tags of its raiders. */
    @Nullable private UUID challengeTag;
    /** Where the first of its raiders turned up: looters run back there. */
    @Nullable private BlockPos challengeCamp;
    /** Its raiders this session (not saved: they join again when their chunks load). */
    private final Set<UUID> challengeRaiders = new HashSet<>();
    /** Wins against each kind of raid (a trophy for the first and every fifth), and trophies seen hung. */
    private final Map<RaidFaction, Integer> factionWins = new java.util.EnumMap<>(RaidFaction.class);
    private final Set<RaidFaction> trophiesHung = java.util.EnumSet.noneOf(RaidFaction.class);
    /** Checks between looks at the Town Hall's walls for trophies (once a minute). */
    private static final int TROPHY_CHECK_EVERY = 60;
    /** A challenge raid's march order sits above vanilla's own walk through the village. */
    private static final int CHALLENGE_GOAL_PRIORITY = 3;

    // Kidnappers: the snatcher, the villager it took (npc id), and how it ends.
    @Nullable private UUID snatcherId;
    @Nullable private UUID captiveId;
    private boolean captiveAtCamp;
    private long capturedAt;
    private int taken;
    private boolean rescued;
    /** The captive got away (rescued, or its captor fell): the kidnappers have failed. */
    private boolean captiveFreed;
    /** Not saved: who the snatcher is going for, re-picked every 5 seconds. */
    @Nullable private UUID victimId;
    private long victimPickedAt;
    private int captiveTick;
    /** Captive pace (10-19, {@code captivepace}): where its last Minecraft path aimed, and at which captive tick. */
    @Nullable private net.minecraft.world.phys.Vec3 captiveAim;
    private int captiveAimedAt;
    /** A villager's walk and flee run, and the fastest it is ever dragged (a hard run, to catch up). */
    private static final double CAPTIVE_WALK = VillagerModel.WALK_SPEED;
    private static final double CAPTIVE_MOST = 0.9;
    /** Further behind its captor than this (squared): a little faster, to close up. */
    private static final double CAPTIVE_BEHIND_SQ = 4.0 * 4.0;
    private static final double CAPTIVE_CATCH_UP = 1.2;
    /** A new path once the spot it follows moved this far (squared), or after a second. */
    private static final double CAPTIVE_AIM_MOVED_SQ = 1.5 * 1.5;
    private static final int CAPTIVE_ASK_TICKS = 20;
    /** The captor's run home in a retreat ({@code RaidMarchGoal.RUN_SPEED}). */
    private static final double CAPTOR_RETREAT_RUN = 1.25;
    /** A captive is never taken for good sooner than this after it was caught (5 minutes). */
    public static final int KIDNAP_HOLD_MIN_TICKS = 5 * 60 * 20;
    /** No kidnapper within this of the captive: a player may untie it (8 blocks). */
    private static final double RESCUE_CLEAR_SQ = 8.0 * 8.0;

    // Not saved.
    /** Players told why their Bad Omen was refused, until this raid ends (no chat spam every second). */
    private final Set<UUID> omenTold = new HashSet<>();
    /** When the stragglers last got the glow, and whether this wave's lost sides were looked for again. */
    private long omenGlowAt = -1;
    private boolean omenSidesRetried;
    /** The "one more loss" warning went out for this raid. */
    private boolean omenWarned;
    private final List<BlockPos> lootChests = new ArrayList<>();
    private int campTries;
    private long noPlayerSince = -1;
    private long resumeAt = -1;
    private final Map<UUID, Long> missingSince = new HashMap<>();
    @Nullable private ServerBossEvent bar;

    public RaidManager(VillageManager village) {
        this.village = village;
    }

    // =========================================================================
    // Queries
    // =========================================================================

    /** An assault (or the retreat after it) is in progress: raiders are about. */
    public boolean isActive() {
        return stage == Stage.ASSAULT || stage == Stage.RETREAT;
    }

    public boolean isLoaded() { return loaded; }

    public Stage stage() { return stage; }

    public int fame() { return fame; }

    /** Fame draws visitors: fame/4 percent more chance of nomads each morning. */
    public double fameNomadBonus() {
        ensureLoaded();
        return fame / 400.0;
    }

    /** From 50 fame, nomad groups can be one bigger. */
    public int fameGroupBonus() {
        ensureLoaded();
        return fame >= 50 ? 1 : 0;
    }

    /** The raid bar is up: the fight bar stays hidden meanwhile. */
    public boolean barShowing() { return isActive() || omenCountdown(); }

    boolean isRetreating() { return stage == Stage.RETREAT; }

    /** The village this raid is on (its map, its buildings): for {@link RaiderMapWalk}. */
    VillageManager village() { return village; }

    @Nullable BlockPos campCentre() {
        // In a challenge, never the last raid's camp still standing until sunrise.
        return challengeActive ? challengeCamp : camp;
    }

    /** Whether {@code entity} is one of this village's raiders right now. */
    public boolean isRaider(net.minecraft.world.entity.Entity entity) {
        return entity instanceof PathfinderMob raider && isTracking(raider);
    }

    boolean isTracking(PathfinderMob raider) {
        return (isActive() && alive.containsKey(raider.getUUID()))
                || (challengeActive && challengeRaiders.contains(raider.getUUID()));
    }

    /** A raid of ours or a challenge raid is being fought here now. */
    private boolean fighting() {
        return isActive() || challengeActive;
    }

    /** The Bad Omen raid's 30 seconds before the horn. */
    private boolean omenCountdown() {
        return stage == Stage.WARNING && faction == RaidFaction.CHALLENGE;
    }

    /** The horde and the Bad Omen raid come from sides around the village rather than from one camp. */
    private boolean usesSides() {
        return faction == RaidFaction.HORDE || faction == RaidFaction.CHALLENGE;
    }

    /**
     * Raiders are attacking now (our own raid's assault or retreat, or a vanilla
     * challenge raid), or a Bad Omen raid is about to: the village must not sleep.
     */
    public boolean inProgress() {
        return fighting() || omenCountdown();
    }

    /**
     * The village alarm holds through this raid's whole attack: a warband's,
     * the riders' or kidnappers' still hunting, each a few minutes at most.
     * Not the horde or a captive held at camp, which last until sunrise:
     * sheltering villagers never go to bed, so a night-long alarm would keep
     * the whole village up.
     */
    public boolean holdsAlarm() {
        // Not kidnappers: with everyone sheltering the whole attack they never found
        // anyone; the curfew bell before it is the warning.
        if (stage != Stage.ASSAULT) return false;
        // A Bad Omen raid holds the alarm only once someone has rung a bell for it.
        if (faction == RaidFaction.CHALLENGE) return omenBellRung;
        return faction == RaidFaction.WARBAND || faction == RaidFaction.RIDERS;
    }

    /**
     * A Bad Omen raid's raiders are here and nobody has rung a bell for them
     * yet: the village does not know. A lookout or a guard who sees them
     * should go and ring one. (Not in the countdown: nobody can see it coming.)
     */
    public boolean needsWarningBell() {
        return faction == RaidFaction.CHALLENGE && stage == Stage.ASSAULT && !omenBellRung;
    }

    /** A bell was rung in this village (see GuardAlertSystem.soundAlarm): a Bad Omen raid is now known. */
    public void onAlarmSounded() {
        if (faction != RaidFaction.CHALLENGE || !(stage == Stage.ASSAULT || omenCountdown())) return;
        if (!omenBellRung) LOGGER.info("[Raid {}] Bad Omen raid: the bell was rung, the village knows", shortId());
        omenBellRung = true;
    }

    /**
     * The village stopped (its Town Hall is gone) and will not tick: a raid of
     * ours is called off and its bar comes down, else it would stand frozen,
     * bar on screen, until the village is restored. A Bad Omen raid is
     * vanilla's and goes on.
     */
    public void onVillageInactive(ServerLevel level) {
        if (!loaded) return;
        if (stage != Stage.IDLE) {
            cancel(level, currentDay(level), Tr.c("chat.dynamicvillages.raid.no_town_hall",
                    "Without a Town Hall the village is no prize: the raiders left."));
        }
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
    }

    /**
     * Nobody may sleep at this village now: raiders are attacking, or an
     * evening or night attack is due today (sleeping would skip it, and
     * sleeping through the horde would win it). Riders come at dawn, so a
     * night's sleep only brings them sooner.
     */
    public boolean blocksSleep(ServerLevel level) {
        ensureLoaded();
        if (fighting() || omenCountdown()) return true;
        // A pulled-back raid comes again only when its camp ticks: no telling when.
        if ((stage != Stage.WARNING && stage != Stage.CAMP) || faction == RaidFaction.RIDERS || pulledBack) return false;
        // Still looking for a camp spot or horde side: the attack cannot come yet anyway.
        if (campTries > CAMP_TRIES_MAX) return false;
        long day = Math.floorDiv(VillageDays.villageTime(level.getDayTime()), VillageDays.DAY_TICKS);
        return day >= attackDay;
    }

    /** Minecraft's own raid (Bad Omen) is on in this village, steered by us. */
    public boolean isChallengeActive() {
        return challengeActive;
    }

    private RaidFrequency frequency() {
        return village.rules().raidFrequency();
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()) return;
        // A dormant village is skipped before this is called; this keeps it
        // safe if it is ever ticked from somewhere else.
        if (village.isDormantOrReconciling()) return;
        ensureLoaded();
        tickCaptive(level);
        RaiderMapWalk.tickHeldGates(level);
        if (++timer < CHECK_INTERVAL) return;
        timer = 0;
        checks++;

        long now = level.getGameTime();
        long villageTime = VillageDays.villageTime(level.getDayTime());
        long day = Math.floorDiv(villageTime, VillageDays.DAY_TICKS);
        long clock = Math.floorMod(villageTime, VillageDays.DAY_TICKS);
        boolean near = !RaidMessages.playersNear(village, level).isEmpty();

        if (day != lastSeenDay) {
            lastSeenDay = day;
            schedule.observe(day, com.alex.test.Config.firstRaidDays());
            fadeFame(day);
            dirty();
        }
        if (stage == Stage.IDLE && !campBlocks.isEmpty() && day >= packUpDay) {
            RaidCamp.takeDown(level, campBlocks);
            if (campBlocks.isEmpty()) camp = null;
            dirty();
        }

        if (challengeActive) tickChallenge(level, day);
        if (stage != Stage.IDLE && level.getDifficulty() == Difficulty.PEACEFUL) {
            // Peaceful takes the raiders away without a fight: the raid is off, not a free win.
            callOffOrLose(level, day, Tr.c("chat.dynamicvillages.raid.peaceful", "The world turned peaceful: the raid is off."));
            return;
        }
        if ((stage == Stage.WARNING || stage == Stage.CAMP) && day > attackDay + GIVE_UP_DAYS) {
            // Its hour never came (slept through every dusk, the sun stands still, or a
            // pulled-back raid's camp never ticks again): it leaves, so it does not block
            // every later raid.
            callOffOrLose(level, day, byFaction(
                    Tr.c("chat.dynamicvillages.raid.waited_too_long.warband", "The warband waited too long and left."),
                    Tr.c("chat.dynamicvillages.raid.waited_too_long.horde", "The horde waited too long and left."),
                    Tr.c("chat.dynamicvillages.raid.waited_too_long.riders", "The riders waited too long and left."),
                    Tr.c("chat.dynamicvillages.raid.waited_too_long.kidnappers", "The kidnappers waited too long and left."),
                    Tr.c("chat.dynamicvillages.raid.waited_too_long.raid", "The raid waited too long and left.")));
            return;
        }
        switch (stage) {
            case IDLE -> {
                if (near && schedule.isDue(day, frequency()) && canRaid(level)) beginWarning(level, day, -1, null);
            }
            case WARNING -> {
                // The Bad Omen raid is the player's choice: the Mayor's setting never calls it off.
                if (faction == RaidFaction.CHALLENGE) {
                    tickOmenCountdown(level, day, now);
                } else if (frequency() == RaidFrequency.OFF) {
                    cancel(level, day, byFaction(
                            Tr.c("chat.dynamicvillages.raid.turned_away.warband",
                                    "The warband has turned away: raids are off in this village."),
                            Tr.c("chat.dynamicvillages.raid.turned_away.horde",
                                    "The horde has turned away: raids are off in this village."),
                            Tr.c("chat.dynamicvillages.raid.turned_away.riders",
                                    "The riders have turned away: raids are off in this village."),
                            Tr.c("chat.dynamicvillages.raid.turned_away.kidnappers",
                                    "The kidnappers have turned away: raids are off in this village."),
                            Tr.c("chat.dynamicvillages.raid.turned_away.raid",
                                    "The raid has turned away: raids are off in this village.")));
                } else if (faction == RaidFaction.HORDE) {
                    // A horde that found no side last night comes again tonight: a new curfew for it.
                    if (curfewSent && clock < HORDE_CLOCK_START - CURFEW_TICKS) curfewSent = false;
                    if (near && !curfewSent && day >= attackDay
                            && clock >= HORDE_CLOCK_START - CURFEW_TICKS && clock < HORDE_CLOCK_START) {
                        callCurfew(level, Tr.c("chat.dynamicvillages.raid.curfew.horde",
                                "Night is falling and the dead are coming: everyone indoors!"));
                    }
                    if (near && day >= attackDay && isHordeHour(clock)) beginHorde(level, day, now);
                } else if (faction == RaidFaction.RIDERS) {
                    if (near && (day >= attackDay || (day == attackDay - 1 && clock >= RIDER_CAMP_CLOCK))) {
                        setUpCamp(level, day, clock, now);
                    }
                } else if (near && (day > attackDay || (day == attackDay && clock >= CAMP_CLOCK))) {
                    setUpCamp(level, day, clock, now);
                }
            }
            case CAMP -> {
                // Off calls off a raid that has not attacked yet; one pulled back after
                // attacking still ends with a result (else Off would erase a defeat).
                if (frequency() == RaidFrequency.OFF && !pulledBack) {
                    cancel(level, day, faction == RaidFaction.HORDE
                            ? Tr.c("chat.dynamicvillages.raid.off_horde",
                                    "The dead went back into the dark: raids are off in this village.")
                            : Tr.c("chat.dynamicvillages.raid.off_camp",
                                    "The warband broke camp and left: raids are off in this village."));
                } else {
                    tickCamp(level, day, clock, now, near);
                }
            }
            case ASSAULT, RETREAT -> tickFight(level, day, now, near);
        }
        if (checks % TROPHY_CHECK_EVERY == 0) checkTrophyWall(level);
        updateBar(level);
    }

    /**
     * After the away catch-up: raids stand still while nobody is there, the
     * days the catch-up lives through included, so the time away neither
     * brings the next raid closer, fades fame, takes a camp down nor runs out
     * a waiting raid's patience (a camp that pulled back is still there, and
     * attacks again once someone is near). Every day mark moves {@code days}
     * later. The Bad Omen spoils wait ({@code lastOmenSpoilsDay}) is the
     * player's own and still runs.
     */
    public void skipDaysAway(long days) {
        if (days <= 0L) return;
        ensureLoaded();
        schedule.shiftBy(days);
        if (fameDecayDay != Long.MIN_VALUE) fameDecayDay += days;
        if (packUpDay != Long.MIN_VALUE) packUpDay += days;
        if (stage == Stage.WARNING || stage == Stage.CAMP) attackDay += days;
        dirty();
    }

    /** Fame fades a point every {@link #FAME_FADE_DAYS} village days. */
    private void fadeFame(long day) {
        if (fameDecayDay == Long.MIN_VALUE || day < fameDecayDay) {
            fameDecayDay = day;
            return;
        }
        long steps = (day - fameDecayDay) / FAME_FADE_DAYS;
        if (steps <= 0) return;
        fame = (int) Math.max(0, fame - steps);
        fameDecayDay += steps * FAME_FADE_DAYS;
    }

    /** Whether a raid may start here now (the schedule already says one is due). */
    private boolean canRaid(ServerLevel level) {
        if (!com.alex.test.Config.raidsEnabled()) return false;
        if (challengeActive) return false;
        if (frequency() == RaidFrequency.OFF) return false;
        if (level.getDifficulty() == Difficulty.PEACEFUL) return false;
        if (!mayorUsed) return false;
        if (grownVillagers() < MIN_VILLAGERS) return false;
        BlockPos centre = RaidCamp.builtCentre(village);
        if (centre == null) return false;
        // Minecraft's own raid is already here (Bad Omen): one raid at a time.
        return level.getRaidAt(centre) == null;
    }

    private int grownVillagers() {
        int count = 0;
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() != null && model.brain().profession() != Profession.CHILD) count++;
        }
        return count;
    }

    /** Night, but not so late that sunrise would end the horde at once. */
    private static boolean isHordeHour(long clock) {
        return clock >= HORDE_CLOCK_START && clock < HORDE_CLOCK_END;
    }

    /** Desert (husks) and water nearby (some drowned), from the middle of the village. */
    private boolean[] hordeTerrain(ServerLevel level) {
        BlockPos centre = RaidCamp.builtCentre(village);
        if (centre == null) return new boolean[]{false, false};
        var biome = level.getBiome(centre);
        boolean desert = biome.is(net.minecraft.world.level.biome.Biomes.DESERT)
                || biome.is(net.minecraft.tags.BiomeTags.IS_BADLANDS);
        int wet = 0;
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            int x = centre.getX() + (int) Math.round(Math.cos(a) * 24);
            int z = centre.getZ() + (int) Math.round(Math.sin(a) * 24);
            if (!level.hasChunk(x >> 4, z >> 4)) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            if (!level.getFluidState(new BlockPos(x, y, z)).isEmpty()) wet++;
        }
        return new boolean[]{desert, wet >= 2};
    }

    private void beginWarning(ServerLevel level, long day, int forcedBudget, @Nullable RaidFaction forcedFaction) {
        if (!campBlocks.isEmpty()) RaidCamp.takeDown(level, campBlocks);
        RandomGenerator rng = ThreadLocalRandom.current();
        faction = forcedFaction != null ? forcedFaction : RaidFaction.pick(recentFactions, rng, this::factionAllowed);
        resetRaidState(level);
        recentFactions.add(0, faction);
        while (recentFactions.size() > RECENT_FACTIONS) recentFactions.remove(recentFactions.size() - 1);
        RaidCensus census = RaidCensus.of(village, level);
        int threat = RaidMath.threat(census.villagers(), census.wealth(), fame);
        budget = forcedBudget > 0 ? forcedBudget
                : RaidMath.budget(threat, census.defense(), RaidCensus.difficultyScale(level.getDifficulty()));
        if (forcedBudget <= 0 && raidsSeen == 0) budget = Math.min(budget, FIRST_RAID_BUDGET);
        tier = RaidMath.tier(threat);
        defense = census.defense();
        villagersAtStart = census.villagers();
        lootCap = Math.max(1.0, census.wealth() * LOOT_SHARE);
        sizeWord = RaidMath.sizeWord(budget, defense);
        if (faction == RaidFaction.HORDE) {
            boolean[] terrain = hordeTerrain(level);
            waves = new ArrayList<>(RaidPlanner.planHorde(budget, tier, com.alex.test.Config.maxRaidersAlive(), rng, terrain[0], terrain[1]));
            twists.clear();
        } else if (faction == RaidFaction.KIDNAPPERS) {
            waves = new ArrayList<>(RaidPlanner.planKidnappers(tier));
            twists.clear();
        } else if (faction == RaidFaction.RIDERS) {
            boolean[] climate = riderClimate(level);
            waves = new ArrayList<>(RaidPlanner.planRiders(budget, tier, com.alex.test.Config.maxRaidersAlive(), rng,
                    climate[0], climate[1]));
            twists.clear();
        } else {
            twists.clear();
            twists.addAll(RaidTwist.roll(tier, rng));
            waves = planWarband(rng);
        }
        totalPlanned = 0;
        for (List<RaiderKind> wave : waves) totalPlanned += wave.size();
        raidId = UUID.randomUUID();
        generation++;
        campAngle = rng.nextDouble() * Math.PI * 2;
        attackDay = day + 1;
        stage = Stage.WARNING;
        // Quiet scouts: no word of them until their camp goes up.
        if (!twists.contains(RaidTwist.QUIET_SCOUTS)) {
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                    switch (faction) {
                        case HORDE -> Tr.c("chat.dynamicvillages.raid.warning.horde",
                                "The dead are restless: a night horde will come tomorrow night, %s. "
                                        + "Keep the doors shut and the guards awake.", sizeText());
                        case RIDERS -> Tr.c("chat.dynamicvillages.raid.warning.riders",
                                "Skeleton riders were seen on the hills %1$s: %2$s. They will camp tonight and strike "
                                        + "the fields at dawn. Archers and towers stop them.", toward(), sizeText());
                        case KIDNAPPERS -> Tr.c("chat.dynamicvillages.raid.warning.kidnappers",
                                "Kidnappers were seen near the village %s. Tomorrow at dusk they will try to take someone. "
                                        + "Keep everyone indoors and the guards on the paths.", toward());
                        default -> Tr.c("chat.dynamicvillages.raid.warning.warband",
                                "An illager warband is gathering %1$s: %2$s. They will camp near the village tomorrow "
                                        + "and attack %3$s.", toward(), sizeText(), attackWhenText());
                    }, null);
        }
        LOGGER.info("[Raid {}] warning: {}, threat {}, defense {}, budget {}, tier {}, {} raiders in {} wave(s), attack day {}, twists {}",
                shortId(), faction.noun(), threat, String.format("%.1f", defense), budget, tier, totalPlanned,
                waves.size(), attackDay, twists);
        if (faction == RaidFaction.WARBAND && !twists.contains(RaidTwist.QUIET_SCOUTS)) sendScout(level);
        dirty();
    }

    /**
     * Wipes what the last raid left (counts, captive, stolen goods, raiders,
     * sides, camp) before a new one is planned. Not the plan itself, the camp's
     * blocks (taken down by the caller) or the stage.
     */
    private void resetRaidState(ServerLevel level) {
        freeCaptive(level, false, null);
        snatcherId = null;
        captiveId = null;
        captiveAtCamp = false;
        taken = 0;
        rescued = false;
        captiveFreed = false;
        victimId = null;
        animalsKilled = 0;
        cropsLost = 0;
        sides.clear();
        stolenValue = 0;
        stacksLost = 0;
        nonLeaders = 0;
        lootAlarmSent = false;
        twistsRevealed = false;
        curfewSent = false;
        omenBellRung = false;
        wavesSent = 0;
        totalSpawned = 0;
        fighters.clear();
        lastWaveSize = 0;
        beaten = 0;
        deaths = 0;
        escaped = 0;
        leaderId = null;
        leaderDown = false;
        warlordId = null;
        warlordDown = false;
        pulledBack = false;
        retreatBeaten = false;
        camp = null;
        campTries = 0;
        alive.clear();
        missingSince.clear();
        scoutId = null;
        scoutPost = null;
        scoutDown = false;
        waveClearedAt = -1;
        omenGlowAt = -1;
        omenSidesRetried = false;
        omenWarned = false;
    }

    /** The warband's waves for the current budget and tier, with the siege beast twist applied. */
    private List<List<RaiderKind>> planWarband(RandomGenerator rng) {
        List<List<RaiderKind>> planned = RaidPlanner.plan(budget, tier, com.alex.test.Config.maxRaidersAlive(), rng);
        // A small raid (a village with few or no defenders) never gets the ravager: it costs
        // more than the whole capped budget (spec 5.3).
        if (twists.contains(RaidTwist.SIEGE_BEAST)) {
            if (budget >= SIEGE_BEAST_MIN_BUDGET) planned = RaidTwist.withSiegeBeast(planned);
            else twists.remove(RaidTwist.SIEGE_BEAST);
        }
        return new ArrayList<>(planned);
    }

    /** The "Hungry" twist: looters take only food, from storage rooms and kitchens. */
    private boolean hungry() {
        return twists.contains(RaidTwist.HUNGRY);
    }

    /**
     * Which raids may come here: riders only where there is something on the
     * outskirts to ride down (a ranch, an animal pen, or a farmer's fields).
     */
    private boolean factionAllowed(RaidFaction kind) {
        if (kind == RaidFaction.KIDNAPPERS) return grownVillagers() >= 10;
        if (kind != RaidFaction.RIDERS) return true;
        for (Building building : village.getBuildings()) {
            if (building instanceof com.alex.test.village.building.RanchBuilding) return true;
        }
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() != null && model.brain().profession() == Profession.FARMER) return true;
        }
        return false;
    }

    /** Snow (strays) and swamp (bogged) riders, from the middle of the village. */
    private boolean[] riderClimate(ServerLevel level) {
        BlockPos centre = RaidCamp.builtCentre(village);
        if (centre == null) return new boolean[]{false, false};
        var biome = level.getBiome(centre);
        boolean snowy = biome.value().coldEnoughToSnow(centre);
        boolean swamp = biome.is(net.minecraft.world.level.biome.Biomes.SWAMP)
                || biome.is(net.minecraft.world.level.biome.Biomes.MANGROVE_SWAMP);
        return new boolean[]{snowy, swamp};
    }

    /** "a ravager leads the first wave, they sent no scout": the raid's twists, for chat. */
    private Component twistText() {
        List<Component> parts = new ArrayList<>();
        for (RaidTwist twist : twists) {
            parts.add(switch (twist) {
                case NIGHT_ATTACK -> Tr.c("chat.dynamicvillages.raid.twist.night_attack", "they will attack after dark");
                case SIEGE_BEAST -> Tr.c("chat.dynamicvillages.raid.twist.siege_beast", "a ravager leads the first wave");
                case ELITE_GEAR -> Tr.c("chat.dynamicvillages.raid.twist.elite_gear", "some of them carry enchanted weapons");
                case HUNGRY -> Tr.c("chat.dynamicvillages.raid.twist.hungry", "their looters are after your food");
                case QUIET_SCOUTS -> Tr.c("chat.dynamicvillages.raid.twist.quiet_scouts", "they sent no scout");
            });
        }
        return RaidMessages.joined(parts, ", ");
    }

    /** "at dusk", "after dark" for a night attack, or "at dawn" for riders, in each player's language. */
    private Component attackWhenText() {
        if (faction == RaidFaction.RIDERS) return Tr.c("chat.dynamicvillages.raid.when.dawn", "at dawn");
        return twists.contains(RaidTwist.NIGHT_ATTACK) ? Tr.c("chat.dynamicvillages.raid.when.dark", "after dark")
                : Tr.c("chat.dynamicvillages.raid.when.dusk", "at dusk");
    }

    /** {@link #sizeWord} (saved in English) in each player's language. */
    private Component sizeText() {
        return switch (sizeWord) {
            case "a small band, and no guards to stop it" -> Tr.c("chat.dynamicvillages.raid.size.unguarded",
                    "a small band, and no guards to stop it");
            case "a small band" -> Tr.c("chat.dynamicvillages.raid.size.small", "a small band");
            case "a band your guards can match" -> Tr.c("chat.dynamicvillages.raid.size.match", "a band your guards can match");
            case "a dangerous band" -> Tr.c("chat.dynamicvillages.raid.size.dangerous", "a dangerous band");
            default -> Component.literal(sizeWord);
        };
    }

    /** The attack window: dusk to midnight, after dark for a night attack, early morning for riders. */
    private long attackStart() {
        if (faction == RaidFaction.RIDERS) return RIDER_CLOCK_START;
        return twists.contains(RaidTwist.NIGHT_ATTACK) ? NIGHT_ATTACK_CLOCK_START : ATTACK_CLOCK_START;
    }

    private long attackEnd() {
        if (faction == RaidFaction.RIDERS) return RIDER_CLOCK_END;
        return twists.contains(RaidTwist.NIGHT_ATTACK) ? NIGHT_ATTACK_CLOCK_END : ATTACK_CLOCK_END;
    }

    /** The name of an archer up on a watchtower now, if any: it reports what it sees. */
    @Nullable
    private Component lookoutName() {
        for (VillagerModel model : village.getVillagers()) {
            com.alex.test.entity.NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || model.brain() == null) continue;
            if (model.brain().profession() != Profession.ARCHER) continue;
            if (com.alex.test.village.villagers.archer.behavior.WatchtowerDuty.isOnWatch(npc)) {
                return npc.getDisplayName();
            }
        }
        return null;
    }

    /**
     * The warband's scout: a lone pillager on a spot out towards where the
     * camp will be, watching the village. Beaten before the camp goes up, it
     * makes the raid smaller and tells how big it is.
     */
    private void sendScout(ServerLevel level) {
        RaidCamp.Spot spot = RaidCamp.findSpot(level, village, campAngle, RaidCamp.MIN_OUT, RaidCamp.MAX_OUT);
        if (spot == null) spot = RaidCamp.findSpot(level, village, campAngle, RaidCamp.NEAR_MIN_OUT, RaidCamp.NEAR_MAX_OUT);
        if (spot == null) return;
        PathfinderMob scout = RaidSpawner.create(level, RaiderKind.PILLAGER, spot.pos(), village.getVillageId(),
                raidId, generation, false, false);
        if (scout == null) return;
        scout.setCustomName(Tr.c("entity.towntopia.raid_scout", "Scout"));
        scoutId = scout.getUUID();
        scoutPost = scout.blockPosition();
        if (!RaidSpawner.addToWorld(level, scout)) {
            scoutId = null;
            scoutPost = null;
            return;
        }
        RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                Tr.c("chat.dynamicvillages.raid.scout", "A pillager scout is watching the village %s. "
                        + "Beat it before their camp goes up and the warband will be smaller.", from()), scoutPost);
    }

    /** The scout was beaten: before the camp is up, the warband loses a fifth of its strength. */
    private void onScoutDown(ServerLevel level) {
        scoutId = null;
        if (stage != Stage.WARNING || scoutDown) return;
        scoutDown = true;
        budget = Math.max(RaidMath.MIN_BUDGET, (int) Math.round(budget * 0.8));
        waves = planWarband(ThreadLocalRandom.current());
        totalPlanned = 0;
        for (List<RaiderKind> wave : waves) totalPlanned += wave.size();
        RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                Tr.c("chat.dynamicvillages.raid.scout_down", "The scout is down! The warband will be smaller: %1$s in %2$s.",
                        raiders(totalPlanned), wavesText(waves.size())), null);
        LOGGER.info("[Raid {}] scout beaten: budget {} -> {} raiders", shortId(), budget, totalPlanned);
        dirty();
    }

    /** The scout goes back to its band (camp up, raid over or called off). */
    private void recallScout(ServerLevel level) {
        if (scoutId == null) return;
        Entity scout = level.getEntity(scoutId);
        if (scout != null && scout.isAlive()) scout.discard();
        scoutId = null;
    }

    /**
     * The horde's night has come: it needs two or three sides to come from
     * (spots out from the village in the running part of the world), then
     * it attacks at once. No spots this time: tried again next check, and
     * given up after a while.
     */
    private void beginHorde(ServerLevel level, long day, long now) {
        if (sides.isEmpty()) {
            campTries++;
            // Past the usual tries (nowhere found, or not loaded yet): still looking, once
            // every 10 s, until the give-up deadline says so in chat. Calling it off here
            // made a warned raid vanish without a word.
            if (campTries > CAMP_TRIES_MAX && campTries % 10 != 0) return;
            findSides(level, HORDE_SIDES_MIN + level.random.nextInt(HORDE_SIDES_MAX - HORDE_SIDES_MIN + 1));
            if (sides.isEmpty()) {
                if (campTries == CAMP_TRIES_MAX) {
                    LOGGER.info("[Raid {}] no side for the horde after {} tries; still looking", shortId(), campTries);
                }
                return;
            }
        }
        camp = sides.get(0);
        attackDay = day;
        horn(level, now);
    }

    /**
     * Looks for up to {@code count} spots out from the village, spread around it
     * (running part of the world only), and adds the ones found to {@link #sides}.
     */
    private void findSides(ServerLevel level, int count) {
        for (int i = 0; i < count; i++) {
            double angle = campAngle + i * (Math.PI * 2 / count) + (level.random.nextDouble() - 0.5) * 0.6;
            RaidCamp.Spot spot = RaidCamp.findSpot(level, village, angle, RaidCamp.NEAR_MIN_OUT, RaidCamp.NEAR_MAX_OUT);
            if (spot != null) sides.add(spot.pos());
        }
    }

    /** Where the camp is, or will be, seen from the village: one way, or two for a raid from several sides. */
    private List<RaidMessages.Compass> bearings() {
        BlockPos centre = RaidCamp.builtCentre(village);
        if (centre == null) return List.of(RaidMessages.Compass.EDGE);
        if (usesSides() && sides.size() > 1) {
            RaidMessages.Compass first = RaidMessages.compass(centre, sides.get(0));
            RaidMessages.Compass second = RaidMessages.compass(centre, sides.get(1));
            return first == second ? List.of(first) : List.of(first, second);
        }
        if (camp != null) return List.of(RaidMessages.compass(centre, camp));
        BlockPos ahead = centre.offset((int) Math.round(Math.cos(campAngle) * 10), 0,
                (int) Math.round(Math.sin(campAngle) * 10));
        return List.of(RaidMessages.compass(centre, ahead));
    }

    /** "to the north", "to the north and to the east": where the camp is, for chat. */
    private Component toward() {
        List<RaidMessages.Compass> ways = bearings();
        if (ways.size() == 1) return RaidMessages.toward(ways.get(0));
        return Tr.c("chat.dynamicvillages.raid.two_ways", "%1$s and %2$s",
                RaidMessages.toward(ways.get(0)), RaidMessages.toward(ways.get(1)));
    }

    /** "from the north", "from the north and from the east": where the raiders come from, for chat. */
    private Component from() {
        List<RaidMessages.Compass> ways = bearings();
        if (ways.size() == 1) return RaidMessages.from(ways.get(0));
        return Tr.c("chat.dynamicvillages.raid.two_ways", "%1$s and %2$s",
                RaidMessages.from(ways.get(0)), RaidMessages.from(ways.get(1)));
    }

    /** True when the camp went up (or the raid was given up for want of a spot). */
    private boolean setUpCamp(ServerLevel level, long day, long clock, long now) {
        campTries++;
        // Past the usual tries: still looking, once every 10 s, until the give-up deadline.
        if (campTries > CAMP_TRIES_MAX && campTries % 10 != 0) return false;
        boolean close = campTries > CAMP_TRIES_NEAR;
        RaidCamp.Spot spot = RaidCamp.findSpot(level, village, campAngle,
                close ? RaidCamp.NEAR_MIN_OUT : RaidCamp.MIN_OUT, close ? RaidCamp.NEAR_MAX_OUT : RaidCamp.MAX_OUT,
                faction == RaidFaction.KIDNAPPERS);
        // Raider pathfinding round 3 (raidcampin): nothing far out joins the village (a big village's
        // map can end there): closer in, taken only when that one joins.
        if (com.alex.test.village.WalkFeel.raidCampIn && !close && (spot == null || !spot.joined())) {
            RaidCamp.Spot near = RaidCamp.findSpot(level, village, campAngle, RaidCamp.NEAR_MIN_OUT,
                    RaidCamp.NEAR_MAX_OUT, faction == RaidFaction.KIDNAPPERS);
            if (near != null && near.joined()) spot = near;
        }
        if (spot == null) {
            if (campTries == CAMP_TRIES_MAX) {
                LOGGER.info("[Raid {}] no camp spot found after {} tries; still looking", shortId(), campTries);
            }
            return false;
        }
        campBlocks.addAll(RaidCamp.build(level, spot, faction == RaidFaction.RIDERS));
        recallScout(level);
        camp = spot.pos();
        campBuiltAt = now;
        stage = Stage.CAMP;
        // "While you were away" only when the player was: not after a long search for a spot.
        boolean late = campTries <= CAMP_TRIES_MAX && (day > attackDay || (day == attackDay && clock >= attackStart()));
        RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                switch (faction) {
                    case RIDERS -> late
                            ? Tr.c("chat.dynamicvillages.raid.camp_late.riders",
                                    "While you were away, the riders made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText())
                            : Tr.c("chat.dynamicvillages.raid.camp.riders", "The riders made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText());
                    case KIDNAPPERS -> late
                            ? Tr.c("chat.dynamicvillages.raid.camp_late.kidnappers",
                                    "While you were away, the kidnappers made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText())
                            : Tr.c("chat.dynamicvillages.raid.camp.kidnappers",
                                    "The kidnappers made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText());
                    default -> late
                            ? Tr.c("chat.dynamicvillages.raid.camp_late.warband",
                                    "While you were away, the warband made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText())
                            : Tr.c("chat.dynamicvillages.raid.camp.warband", "The warband made camp %1$s: %2$s. They attack %3$s.",
                                    toward(), sizeText(), attackWhenText());
                }, camp);
        // A lookout on a watchtower sees the camp clearly: how many, and what is odd about them.
        Component lookout = lookoutName();
        if (lookout != null) {
            twistsRevealed = true;
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                    twists.isEmpty()
                            ? Tr.c("chat.dynamicvillages.raid.lookout", "Lookout %1$s counts %2$s in %3$s.",
                                    lookout, raiders(totalPlanned), wavesText(waves.size()))
                            : Tr.c("chat.dynamicvillages.raid.lookout_twists", "Lookout %1$s counts %2$s in %3$s; %4$s.",
                                    lookout, raiders(totalPlanned), wavesText(waves.size()), twistText()), camp);
        }
        LOGGER.info("[Raid {}] camp at {} ({} tents, {} blocks)", shortId(), camp.toShortString(),
                spot.tents() ? "with" : "no", campBlocks.size());
        dirty();
        return true;
    }

    private void tickCamp(ServerLevel level, long day, long clock, long now, boolean near) {
        if (!near) {
            resumeAt = -1;
            return;
        }
        if (pulledBack && faction == RaidFaction.HORDE) {
            // The dead only walk at night: back by day, the horde is over.
            if (!isHordeHour(clock) && (day > attackDay || clock < HORDE_CLOCK_START)) {
                // Nobody held out against it: no win for walking away, but the dead still count.
                callOffOrLose(level, day, Tr.c("chat.dynamicvillages.raid.horde_never_came_back",
                        "The sun is up and the night horde never came back."));
                return;
            }
            if (resumeAt < 0) {
                resumeAt = now + RESUME_AFTER_TICKS;
                RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                        Tr.c("chat.dynamicvillages.raid.horde_again", "The night horde is still out there. It comes again in 2 minutes."),
                        null);
            }
            if (now >= resumeAt && campTicking(level)) {
                attackDay = day;
                horn(level, now);
            }
            return;
        }
        if (pulledBack) {
            if (resumeAt < 0) {
                resumeAt = now + RESUME_AFTER_TICKS;
                RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                        faction == RaidFaction.RIDERS
                                ? Tr.c("chat.dynamicvillages.raid.still_camped.riders",
                                        "The riders are still camped %s. They attack again in 2 minutes.", toward())
                                : Tr.c("chat.dynamicvillages.raid.still_camped.warband",
                                        "The warband is still camped %s. They attack again in 2 minutes.", toward()), camp);
            }
            if (now >= resumeAt && campTicking(level)) horn(level, now);
            return;
        }
        // A window that passed without an attack (the camp was out of reach): call the curfew again next time.
        if (curfewSent && (clock < attackStart() - CURFEW_TICKS || clock >= attackEnd())) curfewSent = false;
        boolean attackDayCome = day > attackDay || (day == attackDay && clock >= attackStart() - CURFEW_TICKS);
        boolean attackHour = clock >= attackStart() && clock < attackEnd();
        boolean campOldEnough = now - campBuiltAt >= HORN_AFTER_CAMP_TICKS;
        // The curfew: half a minute before the attack, everyone goes in and the guards come out.
        if (!curfewSent && attackDayCome && clock >= attackStart() - CURFEW_TICKS && clock < attackEnd()
                && now - campBuiltAt >= HORN_AFTER_CAMP_TICKS - CURFEW_TICKS) {
            callCurfew(level, switch (faction) {
                case RIDERS -> Tr.c("chat.dynamicvillages.raid.curfew.riders", "The riders are about to strike: farmers indoors!");
                case KIDNAPPERS -> Tr.c("chat.dynamicvillages.raid.curfew.kidnappers",
                        "The kidnappers are about to come: everyone indoors!");
                default -> Tr.c("chat.dynamicvillages.raid.curfew.warband", "The warband is about to attack: everyone indoors!");
            });
        }
        if (attackDayCome && attackHour && campOldEnough && campTicking(level)) {
            horn(level, now);
        }
    }

    private void callCurfew(ServerLevel level, Component message) {
        curfewSent = true;
        GuardAlertSystem.soundAlarm(village, level);
        RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW, message, null);
        dirty();
    }

    /**
     * Whether the camp is in the part of the world that is running: raiders
     * put there now would move. A player at the far side of a big village may
     * be "near" it but too far from the camp; the attack then waits.
     */
    private boolean campTicking(ServerLevel level) {
        return spawnOrigin(level) != null;
    }

    /**
     * Where the next wave comes from, if that is in the running part of the
     * world: the warband's camp, or the next of the horde's sides that is.
     */
    @Nullable
    private BlockPos spawnOrigin(ServerLevel level) {
        if (!usesSides()) return camp != null && level.isPositionEntityTicking(camp) ? camp : null;
        for (int i = 0; i < sides.size(); i++) {
            BlockPos side = sides.get((wavesSent + i) % sides.size());
            if (level.isPositionEntityTicking(side)) return side;
        }
        return null;
    }

    private void horn(ServerLevel level, long now) {
        boolean again = pulledBack;
        attackDay = currentDay(level);
        stage = Stage.ASSAULT;
        hornAt = now;
        lastWaveAt = now;
        noPlayerSince = -1;
        resumeAt = -1;
        pulledBack = false;
        lootChests.clear();
        if (!spawnNextWave(level)) {
            LOGGER.warn("[Raid {}] no raider could be placed at the camp {}; the raid is called off",
                    shortId(), camp == null ? "?" : camp.toShortString());
            // A first horn that placed nobody wrote no "attacked" log row: no "left" row either.
            firstHornFailed = !again;
            if (faction == RaidFaction.CHALLENGE) callOffOmenNoWay(level, lastSeenDay);
            else cancel(level, lastSeenDay, null);
            firstHornFailed = false;
            return;
        }
        // The Bad Omen raid's horn is no warning to the village: someone has to see the
        // raiders and ring a bell (or the player rings it first).
        if (faction != RaidFaction.CHALLENGE) GuardAlertSystem.soundAlarm(village, level);
        if (faction == RaidFaction.CHALLENGE) {
            RaidMessages.horn(village, level, camp, net.minecraft.sounds.SoundEvents.RAID_HORN.value(), 1.0F);
            // The first wave only comes from the first side; the others follow.
            BlockPos centre = RaidCamp.builtCentre(village);
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    waves.size() == 1
                            ? Tr.c("chat.dynamicvillages.raid.omen_here_one", "The Bad Omen raid is here, coming %s!",
                                    RaidMessages.from(centre == null ? RaidMessages.Compass.EDGE : RaidMessages.compass(centre, camp)))
                            : Tr.c("chat.dynamicvillages.raid.omen_here", "The Bad Omen raid is here! %1$s waves, the first %2$s.",
                                    waves.size(),
                                    RaidMessages.from(centre == null ? RaidMessages.Compass.EDGE : RaidMessages.compass(centre, camp))),
                    camp);
        } else if (faction == RaidFaction.KIDNAPPERS) {
            RaidMessages.horn(village, level, camp, net.minecraft.sounds.SoundEvents.RAID_HORN.value(), 1.3F);
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    again ? Tr.c("chat.dynamicvillages.raid.horn_again.kidnappers",
                                    "The kidnappers try again, %s! Keep everyone indoors.", from())
                            : Tr.c("chat.dynamicvillages.raid.horn.kidnappers", "Kidnappers are coming %s! Keep everyone indoors.",
                                    from()), camp);
        } else if (faction == RaidFaction.RIDERS) {
            RaidMessages.horn(village, level, camp, net.minecraft.sounds.SoundEvents.SKELETON_HORSE_AMBIENT, 0.8F);
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    again ? Tr.c("chat.dynamicvillages.raid.horn_again.riders", "The riders are back, %s! Get the farmers indoors.",
                                    from())
                            : Tr.c("chat.dynamicvillages.raid.horn.riders", "Skeleton riders are riding in %s! Get the farmers indoors.",
                                    from()), camp);
        } else if (faction == RaidFaction.HORDE) {
            RaidMessages.horn(village, level, camp, net.minecraft.sounds.SoundEvents.ZOMBIE_AMBIENT, 0.6F);
            RaidMessages.important(village, level, RaidMessages.MARK_DEATH, ChatFormatting.RED,
                    again ? Tr.c("chat.dynamicvillages.raid.horn_again.horde", "The night horde is back, %s! Hold out until sunrise.",
                                    from())
                            : Tr.c("chat.dynamicvillages.raid.horn.horde", "The night horde is coming %s! Hold out until sunrise.",
                                    from()), camp);
        } else {
            RaidMessages.horn(village, level, camp, net.minecraft.sounds.SoundEvents.RAID_HORN.value(), 1.0F);
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    again ? Tr.c("chat.dynamicvillages.raid.horn_again.warband", "The warband attacks again %s!", from())
                            : Tr.c("chat.dynamicvillages.raid.horn.warband", "The warband is attacking %s!", from()), camp);
            if (!twistsRevealed && !twists.isEmpty()) {
                twistsRevealed = true;
                RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                        Tr.c("chat.dynamicvillages.raid.watch_out", "Watch out: %s.", twistText()), camp);
            }
        }
        // Only the first horn: one that sounds again after a pull back is the same raid.
        if (!again) {
            VillageLog.record(village, level, VillageLog.Kind.RAID, logBegan(), null,
                    camp != null ? camp : village.getBellPos());
        }
        LOGGER.info("[Raid {}] horn: {} raiders in wave 1 of {}", shortId(), lastWaveSize, waves.size());
        dirty();
    }

    /** Sends the next wave out of the camp; false when nobody could be placed. */
    private boolean spawnNextWave(ServerLevel level) {
        BlockPos origin = spawnOrigin(level);
        if (wavesSent >= waves.size() || origin == null) return false;
        List<RaiderKind> wave = waves.get(wavesSent++);
        int spawned = 0;
        boolean omen = faction == RaidFaction.CHALLENGE;
        // Every wave of the Bad Omen raid has its own banner carrier.
        if (omen) {
            leaderId = null;
            leaderDown = false;
        }
        com.alex.test.village.raid.warlord.RaidWarlord warlordCame = null;
        for (RaiderKind kind : wave) {
            // Never a second Warlord in one raid (a wave sent again, a test skip).
            if (kind.boss() && warlordId != null) {
                totalPlanned = Math.max(0, totalPlanned - 1);
                continue;
            }
            boolean warband = faction == RaidFaction.WARBAND || omen;
            boolean lead = warband && leaderId == null && !leaderDown && kind.canLead();
            boolean looter = warband && !lead && kind.canLead() && ++nonLeaders % 3 == 1;
            PathfinderMob raider = kind.boss() ? placeWarlord(level, origin)
                    : RaidSpawner.create(level, kind, origin, village.getVillageId(), raidId, generation, lead, looter);
            if (raider instanceof com.alex.test.village.raid.warlord.RaidWarlord warlord) warlord.setOmenLevel(omenLevel);
            // Elite gear: every third raider of the raid is better armed (not the Warlord: armour would not show on it).
            if (raider != null && !kind.boss() && twists.contains(RaidTwist.ELITE_GEAR) && (totalSpawned++ % 3 == 0)) {
                RaidSpawner.makeElite(level, raider);
            }
            if (raider != null) {
                alive.put(raider.getUUID(), kind);
                if (RaidSpawner.addToWorld(level, raider)) {
                    spawned++;
                    if (raider instanceof com.alex.test.village.raid.warlord.RaidWarlord warlord) {
                        warlordId = warlord.getUUID();
                        warlordCame = warlord;
                    }
                    if (lead) leaderId = raider.getUUID();
                    if (faction == RaidFaction.KIDNAPPERS && snatcherId == null && kind == RaiderKind.VINDICATOR) {
                        snatcherId = raider.getUUID();
                    }
                    continue;
                }
                alive.remove(raider.getUUID());
            }
            // Never placed: it no longer counts towards the raid's size.
            totalPlanned = Math.max(0, totalPlanned - 1);
        }
        lastWaveSize = spawned;
        if (spawned > 0 && wavesSent > 1) {
            BlockPos centre = RaidCamp.builtCentre(village);
            RaidMessages.detail(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    faction == RaidFaction.HORDE ? Tr.c("chat.dynamicvillages.raid.more_dead", "More of the dead are coming")
                            : omen ? Tr.c("chat.dynamicvillages.raid.omen_wave", "Wave %1$s of %2$s is coming %3$s.",
                                    wavesSent, waves.size(),
                                    RaidMessages.from(centre == null ? RaidMessages.Compass.EDGE : RaidMessages.compass(centre, origin)))
                            : Tr.c("chat.dynamicvillages.raid.camp_wave", "Wave %1$s of %2$s is coming from the camp",
                                    wavesSent, waves.size()), origin);
        }
        if (warlordCame != null) {
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    Tr.c("chat.dynamicvillages.raid.warlord_came", "The Warlord has come!"), warlordCame.blockPosition());
            level.playSound(null, warlordCame.getX(), warlordCame.getY(), warlordCame.getZ(),
                    com.alex.test.ModSounds.RAID_WARLORD_HORN.get(), net.minecraft.sounds.SoundSource.HOSTILE, 4.0F, 1.0F);
            warlordCame.roar();
        }
        dirty();
        return spawned > 0;
    }

    /**
     * The Warlord at the wave's side, else at the raid's other sides, else at
     * eight points 16 blocks out from each side: it needs room for its body.
     * Null (logged) when it fits nowhere: the wave then comes without it, so
     * the raid can still be won.
     */
    @Nullable
    private PathfinderMob placeWarlord(ServerLevel level, BlockPos origin) {
        List<BlockPos> tries = new ArrayList<>();
        tries.add(origin);
        tries.addAll(sides);
        for (BlockPos side : sides) {
            for (int i = 0; i < 8; i++) {
                double angle = i * Math.PI / 4;
                tries.add(side.offset((int) Math.round(Math.cos(angle) * 16), 0, (int) Math.round(Math.sin(angle) * 16)));
            }
        }
        for (BlockPos at : tries) {
            if (!level.isPositionEntityTicking(at)) continue;
            PathfinderMob warlord = RaidSpawner.create(level, RaiderKind.WARLORD, at, village.getVillageId(), raidId,
                    generation, false, false);
            if (warlord != null) return warlord;
        }
        LOGGER.warn("[Raid {}] no room anywhere for the Warlord: the last wave comes without it", shortId());
        return null;
    }

    private void tickFight(ServerLevel level, long day, long now, boolean near) {
        pruneAlive(level, now);
        if (!near) {
            if (noPlayerSince < 0) {
                noPlayerSince = now;
            } else if (faction == RaidFaction.CHALLENGE) {
                // No camp to pull back to: the raiders wait longer, then leave (a defeat if it went badly).
                // Fleeing a fallen Warlord with nobody about, it is still the win it was.
                if (stage == Stage.RETREAT) {
                    if (alive.isEmpty() || now - retreatSince >= RETREAT_LIMIT_TICKS) finish(level, day, retreatBeaten, null);
                } else if (now - noPlayerSince >= OMEN_AWAY_TICKS) {
                    callOffOrLose(level, day, Tr.c("chat.dynamicvillages.raid.omen_nobody_stayed",
                            "Nobody stayed to face the Bad Omen raid: the raiders left."));
                }
            } else if (now - noPlayerSince >= PULL_BACK_AFTER_TICKS) {
                if (stage == Stage.RETREAT) finish(level, day, retreatBeaten, null);
                else pullBack(level, day);
            }
            return;
        }
        noPlayerSince = -1;

        if (stage == Stage.RETREAT) {
            if (alive.isEmpty() || now - retreatSince >= RETREAT_LIMIT_TICKS) finish(level, day, retreatBeaten, null);
            return;
        }

        drainWellbeing();
        if (faction == RaidFaction.CHALLENGE) {
            tickOmenFight(level, day, now);
            return;
        }
        if (faction == RaidFaction.HORDE) {
            tickHorde(level, day, now);
            return;
        }
        // Kidnappers holding someone do not break or give up: they keep their prize until
        // sunrise (then it is gone), a rescue, or the last of them falls (then it walks home).
        if (faction == RaidFaction.KIDNAPPERS && captiveId != null) {
            if (alive.isEmpty()) {
                freeCaptive(level, true, Tr.c("chat.dynamicvillages.raid.captive.last_kidnapper",
                        "The last kidnapper is down: %s is free!", captiveName()));
                finish(level, day, true, null);
            } else if (captiveAtCamp && day > attackDay && now - capturedAt >= KIDNAP_HOLD_MIN_TICKS) {
                takeCaptive(level);
                finish(level, day, false, null);
            } else if (!captiveAtCamp && now - capturedAt >= 2L * KIDNAP_HOLD_MIN_TICKS) {
                // Ten minutes and still not at the camp (stuck on the way): the captive slips away.
                freeCaptive(level, false, Tr.c("chat.dynamicvillages.raid.captive.slipped_away",
                        "%s slipped away from the kidnappers and runs home!", captiveNameAtStart()));
            }
            return;
        }
        // A rescue or an escape on the way: the kidnappers have nothing left to wait for.
        if (faction == RaidFaction.KIDNAPPERS && captiveFreed) {
            beginRetreat(level, now, true);
            return;
        }
        int total = Math.max(1, totalPlanned);
        boolean riders = faction == RaidFaction.RIDERS;
        if (beaten >= Math.ceil((riders ? RIDER_BREAK_SHARE : BREAK_SHARE) * total)
                || (leaderDown && beaten >= Math.ceil(LEADER_BREAK_SHARE * total))) {
            beginRetreat(level, now, true);
        } else if (alive.isEmpty() && wavesSent >= waves.size()) {
            finish(level, day, true, null);
        } else if (now - hornAt >= (riders ? RIDER_LIMIT_TICKS : ASSAULT_LIMIT_TICKS)) {
            beginRetreat(level, now, false);
        } else if (wavesSent < waves.size() && campTicking(level) && roomForNextWave()
                && (alive.size() <= Math.max(1, lastWaveSize / 3) || now - lastWaveAt >= WAVE_AFTER_TICKS)) {
            spawnNextWave(level);
            lastWaveAt = now;
        }
    }

    /**
     * The next wave fits under the server's "most raiders at once" setting
     * (always true with nobody left, so an oversized wave still comes).
     */
    private boolean roomForNextWave() {
        if (alive.isEmpty() || wavesSent >= waves.size()) return true;
        // The Warlord comes on top of the cap, as the wave plan has it.
        long regulars = waves.get(wavesSent).stream().filter(kind -> !kind.boss()).count();
        return alive.size() + regulars <= com.alex.test.Config.maxRaidersAlive();
    }

    /**
     * The night horde never breaks and runs: it comes in groups until it is
     * spent and lasts until sunrise, when the dead still about burn or slip
     * away into the dark.
     */
    private void tickHorde(ServerLevel level, long day, long now) {
        if (day > attackDay) {
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                    Tr.c("chat.dynamicvillages.raid.horde_gone", "The sun is up: the night horde is gone."), null);
            finish(level, day, true, null);
        } else if (alive.isEmpty() && wavesSent >= waves.size()) {
            finish(level, day, true, null);
        } else if (wavesSent < waves.size() && campTicking(level) && roomForNextWave()
                && (alive.size() <= Math.max(1, lastWaveSize / 3) || now - lastWaveAt >= HORDE_GROUP_TICKS)) {
            spawnNextWave(level);
            lastWaveAt = now;
        }
    }

    // =========================================================================
    // The Bad Omen raid
    // =========================================================================

    /** The Bad Omen raid is on (counting down or fighting). */
    private boolean omenRaid() {
        return stage != Stage.IDLE && faction == RaidFaction.CHALLENGE;
    }

    /**
     * A player with Bad Omen stands in or near this village. Starts the Bad
     * Omen raid (a 30 second countdown, then waves from three sides), or adds
     * this omen to one that is counting down. True when the omen was used;
     * false when it was refused and the player keeps it (a line says why, once).
     *
     * <p>The Mayor's raid setting does not matter here: it is the player's
     * choice. Only the server's raid switch, Peaceful and the disableRaids rule do.
     *
     * @param omen the player's omen level, the Bad Omen amplifier plus one (1 to 5)
     */
    public boolean onBadOmen(ServerLevel level, ServerPlayer player, int omen) {
        ensureLoaded();
        int added = RaidOmenMath.clampLevel(omen);
        if (!com.alex.test.Config.raidsEnabled()) return refuseOmen(player, Tr.c("chat.dynamicvillages.raid.omen_refused.server_off",
                "Raids are turned off on this server."));
        if (level.getDifficulty() == Difficulty.PEACEFUL
                || level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_DISABLE_RAIDS)) {
            return refuseOmen(player, null);
        }
        if (RaidCamp.builtCentre(village) == null || grownVillagers() < OMEN_MIN_VILLAGERS) {
            return refuseOmen(player, Tr.c("chat.dynamicvillages.raid.omen_refused.too_small",
                    "There is nothing here worth raiding yet."));
        }
        // A vanilla raid is being run here: never two raids at once.
        if (challengeActive) return refuseOmen(player, null);
        boolean ours = omenRaid();
        if (ours && stage == Stage.ASSAULT) {
            return refuseOmen(player, Tr.c("chat.dynamicvillages.raid.omen_refused.already_here",
                    "The raid is already here. Your Bad Omen waits for the next one."));
        }
        if (!ours && (stage == Stage.ASSAULT || stage == Stage.RETREAT || pulledBack)) {
            return refuseOmen(player, Tr.c("chat.dynamicvillages.raid.omen_refused.fighting",
                    "The village is already fighting. Your Bad Omen waits."));
        }
        if (ours) return joinOmen(level, player, added);
        // One of the schedule's raids still waiting for its hour makes way, quietly, and comes later.
        if (stage == Stage.WARNING || stage == Stage.CAMP) cancel(level, currentDay(level), null);
        startOmen(level, player, added);
        return true;
    }

    /** Says why a Bad Omen was refused (once per player until the raid ends); always false. */
    private boolean refuseOmen(ServerPlayer player, @Nullable Component line) {
        if (line != null && omenTold.add(player.getUUID())) {
            RaidMessages.tell(player, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW, line);
        }
        return false;
    }

    private void startOmen(ServerLevel level, ServerPlayer player, int omen) {
        long day = currentDay(level);
        long now = level.getGameTime();
        RandomGenerator rng = ThreadLocalRandom.current();
        if (!campBlocks.isEmpty()) RaidCamp.takeDown(level, campBlocks);
        faction = RaidFaction.CHALLENGE;
        resetRaidState(level);
        omenLevel = omen;
        omenPlayers.clear();
        omenPlayers.add(player.getUUID());
        omenHornAt = now + OMEN_COUNTDOWN_TICKS;
        attackDay = day;
        planOmenWaves(level, rng);
        raidId = UUID.randomUUID();
        generation++;
        campAngle = rng.nextDouble() * Math.PI * 2;
        stage = Stage.WARNING;
        useOmen(player, omen, OMEN_EFFECT_TICKS);
        // No alarm: the omen is on the player, and the villagers cannot know of it.
        RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.omen_start", "A Bad Omen falls on the village! Raiders come in 30 seconds. "
                        + "The villagers don't know: ring the bell to warn them."),
                RaidCamp.builtCentre(village));
        omenSpoilsHeld = !RaidOmenMath.spoilsDue(lastOmenSpoilsDay, day);
        if (omenSpoilsHeld) {
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                    Tr.c("chat.dynamicvillages.raid.omen_no_spoils", "This raid leaves no spoils: the village had "
                            + "Bad Omen spoils less than %s days ago.", RaidOmenMath.SPOILS_WAIT_DAYS), null);
        }
        // The scheduled raids wait: no new one for two days.
        schedule.postponeTo(day + 2);
        LOGGER.info("[Raid {}] Bad Omen {}: strength {}, {} raiders in {} wave(s), horn in {} ticks", shortId(),
                RaidOmenMath.roman(omenLevel), budget, totalPlanned, waves.size(), OMEN_COUNTDOWN_TICKS);
        dirty();
    }

    /** A second omen joins the countdown: the level adds up (to V at most) and the raid grows with it. */
    private boolean joinOmen(ServerLevel level, ServerPlayer player, int added) {
        if (omenLevel >= RaidOmenMath.MAX_LEVEL) {
            return refuseOmen(player, Tr.c("chat.dynamicvillages.raid.omen_refused.max",
                    "The omen cannot grow any stronger. Your Bad Omen waits."));
        }
        omenLevel = Math.min(RaidOmenMath.MAX_LEVEL, omenLevel + added);
        int ticksLeft = (int) Math.max(0, omenHornAt - level.getGameTime()) + 40;
        useOmen(player, omenLevel, ticksLeft);
        if (!omenPlayers.contains(player.getUUID())) omenPlayers.add(player.getUUID());
        planOmenWaves(level, ThreadLocalRandom.current());
        RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.omen_grows", "Another omen joins: the raid grows to Bad Omen %s.",
                        RaidOmenMath.roman(omenLevel)), null);
        LOGGER.info("[Raid {}] Bad Omen grows to {}: {} raiders in {} wave(s)", shortId(),
                RaidOmenMath.roman(omenLevel), totalPlanned, waves.size());
        dirty();
        return true;
    }

    /** The Bad Omen is used up; the player shows the Raid Omen for the rest of the countdown instead. */
    private static void useOmen(ServerPlayer player, int level, int ticks) {
        player.removeEffect(MobEffects.BAD_OMEN);
        player.removeEffect(MobEffects.RAID_OMEN);
        // No raid omen position: Minecraft's own raid must not start from this effect.
        player.clearRaidOmenPosition();
        player.addEffect(new MobEffectInstance(MobEffects.RAID_OMEN, ticks, level - 1));
    }

    /** The waves for the current omen level, from the village as it is now. */
    private void planOmenWaves(ServerLevel level, RandomGenerator rng) {
        RaidCensus census = RaidCensus.of(village, level);
        int threat = RaidMath.threat(census.villagers(), census.wealth(), fame);
        Difficulty difficulty = level.getDifficulty();
        int difficultyIndex = difficulty == Difficulty.HARD ? 2 : difficulty == Difficulty.NORMAL ? 1 : 0;
        budget = RaidOmenMath.strength(threat, census.defense(), RaidCensus.difficultyScale(difficulty), omenLevel);
        tier = RaidMath.tier(threat);
        defense = census.defense();
        villagersAtStart = census.villagers();
        defendersAtStart = census.defenders();
        lootCap = Math.max(1.0, census.wealth() * LOOT_SHARE);
        sizeWord = RaidMath.sizeWord(budget, defense);
        waves = new ArrayList<>(RaidOmenMath.plan(budget, RaidOmenMath.waveCount(difficultyIndex, omenLevel),
                omenLevel, com.alex.test.Config.maxRaidersAlive(), rng));
        twists.clear();
        if (omenLevel >= RaidOmenMath.MAX_LEVEL) twists.add(RaidTwist.ELITE_GEAR);
        totalPlanned = 0;
        for (List<RaiderKind> wave : waves) totalPlanned += wave.size();
    }

    /** Players of the omen who are online in this level. */
    private List<ServerPlayer> omenPlayersHere(ServerLevel level) {
        List<ServerPlayer> here = new ArrayList<>();
        for (UUID id : omenPlayers) {
            if (level.getPlayerByUUID(id) instanceof ServerPlayer player) here.add(player);
        }
        return here;
    }

    /** Once a second before the horn: called off if the omen is gone, else the horn at its time. */
    private void tickOmenCountdown(ServerLevel level, long day, long now) {
        // Milk or death took every Raid Omen of the players who are here: no raid. Players
        // who are away do not call it off. After the horn's time the omen has done its work
        // (its effect runs out soon after, while the sides are still being looked for).
        if (now < omenHornAt) {
            boolean anyHere = false;
            boolean allLost = true;
            for (ServerPlayer player : omenPlayersHere(level)) {
                anyHere = true;
                if (player.isAlive() && player.hasEffect(MobEffects.RAID_OMEN)) allLost = false;
            }
            if (anyHere && allLost) {
                cancel(level, day, Tr.c("chat.dynamicvillages.raid.omen_faded", "The omen faded: no raid this time."));
                return;
            }
        }
        if (now >= omenHornAt) omenHorn(level, day, now);
    }

    /** The countdown is over: find the sides and sound the horn; no sides for 30 checks and the omen is handed back. */
    private void omenHorn(ServerLevel level, long day, long now) {
        if (sides.isEmpty()) {
            campTries++;
            findSides(level, OMEN_SIDES);
            if (sides.isEmpty()) {
                if (campTries >= OMEN_SIDE_TRIES) callOffOmenNoWay(level, day);
                return;
            }
        }
        for (ServerPlayer player : omenPlayersHere(level)) player.removeEffect(MobEffects.RAID_OMEN);
        camp = sides.get(0);
        horn(level, now);
    }

    /** The raid could not come: the Bad Omen goes back to the players who are here. */
    private void callOffOmenNoWay(ServerLevel level, long day) {
        for (ServerPlayer player : omenPlayersHere(level)) {
            player.removeEffect(MobEffects.RAID_OMEN);
            player.addEffect(new MobEffectInstance(MobEffects.BAD_OMEN, OMEN_GIVE_BACK_TICKS, omenLevel - 1));
        }
        cancel(level, day, Tr.c("chat.dynamicvillages.raid.omen_no_way", "The raiders found no way in. Your Bad Omen is back."));
    }

    /**
     * The Bad Omen raid's fight, once a second with a player near: it only ends
     * when every wave is beaten (a win) or the village could not hold (a defeat).
     * It never breaks, retreats or runs out of time.
     */
    private void tickOmenFight(ServerLevel level, long day, long now) {
        int defendersNow = RaidCensus.defendersAlive(village);
        if (RaidOmenMath.lost(deaths, villagersAtStart, defendersAtStart, defendersNow)) {
            RaidMessages.important(village, level, RaidMessages.MARK_DEATH, ChatFormatting.RED,
                    deaths == 1
                            ? Tr.c("chat.dynamicvillages.raid.omen_lost_one",
                                    "The village could not hold: %s villager died and the Bad Omen raid has won.", deaths)
                            : Tr.c("chat.dynamicvillages.raid.omen_lost",
                                    "The village could not hold: %s villagers died and the Bad Omen raid has won.", deaths),
                    null);
            finish(level, day, false, RaidResult.DEFEAT);
            return;
        }
        if (!omenWarned && RaidOmenMath.onTheBrink(deaths, villagersAtStart, defendersAtStart, defendersNow)) {
            omenWarned = true;
            RaidMessages.important(village, level, RaidMessages.MARK_DEATH, ChatFormatting.RED,
                    Tr.c("chat.dynamicvillages.raid.omen_brink", "One more loss and the village falls!"), null);
        }
        if (!alive.isEmpty()) {
            glowStragglers(level, now);
            return;
        }
        if (wavesSent >= waves.size()) {
            finish(level, day, true, null);
            return;
        }
        if (waveClearedAt < 0) {
            waveClearedAt = now;
            omenSidesRetried = false;
            RaidMessages.detail(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                    Tr.c("chat.dynamicvillages.raid.omen_wave_beaten", "Wave %s beaten! The next comes in 15 seconds.", wavesSent),
                    null);
            dirty();
            return;
        }
        long waited = now - waveClearedAt;
        if (waited < OMEN_NEXT_WAVE_TICKS) return;
        if (waited >= OMEN_NEXT_WAVE_TICKS + OMEN_SIDE_WAIT_TICKS && !campTicking(level)) {
            // No side has been running for a minute: look for new ones, once.
            if (!omenSidesRetried) {
                omenSidesRetried = true;
                sides.clear();
                findSides(level, OMEN_SIDES);
                if (!sides.isEmpty()) camp = sides.get(0);
            }
            if (!campTicking(level)) {
                RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                        Tr.c("chat.dynamicvillages.raid.omen_rest_never_came", "The rest of the raid never came."), null);
                finish(level, day, true, null);
                return;
            }
        }
        if (!campTicking(level)) return;
        if (spawnNextWave(level)) {
            waveClearedAt = -1;
            lastWaveAt = now;
        } else {
            // Nobody could be placed: that wave is lost; the next one comes after another wait.
            waveClearedAt = now;
        }
    }

    /** The village has fallen to the Bad Omen raid: its defenders are spent and 30% of its villagers dead. */
    private boolean omenLost() {
        return RaidOmenMath.lost(deaths, villagersAtStart, defendersAtStart, RaidCensus.defendersAlive(village));
    }

    /** A few raiders left for three minutes: they glow (as in Minecraft's raids) so they can be found. */
    private void glowStragglers(ServerLevel level, long now) {
        if (alive.size() > 3 || now - lastWaveAt < OMEN_STRAGGLER_TICKS) return;
        if (omenGlowAt >= 0 && now - omenGlowAt < OMEN_GLOW_TICKS) return;
        omenGlowAt = now;
        for (UUID id : alive.keySet()) {
            if (level.getEntity(id) instanceof LivingEntity raider && raider.isAlive()) {
                raider.addEffect(new MobEffectInstance(MobEffects.GLOWING, OMEN_GLOW_TICKS, 0, false, false));
            }
        }
    }

    /**
     * Drops raiders gone from the world without dying (despawned, removed by
     * a command, or out of the loaded world for a minute), and gives every
     * raider in the world its orders if it has none yet.
     */
    private void pruneAlive(ServerLevel level, long now) {
        List<UUID> gone = new ArrayList<>();
        for (UUID id : alive.keySet()) {
            Entity entity = level.getEntity(id);
            if (entity == null) {
                long since = missingSince.computeIfAbsent(id, k -> now);
                if (now - since >= MISSING_DROP_TICKS) gone.add(id);
                continue;
            }
            missingSince.remove(id);
            if (!entity.isAlive() || entity.isRemoved()) {
                gone.add(id);
            } else if (entity instanceof PathfinderMob raider) {
                ensureOrders(raider);
            }
        }
        for (UUID id : gone) {
            alive.remove(id);
            missingSince.remove(id);
        }
        if (!gone.isEmpty()) dirty();
    }

    private void ensureScoutOrders(PathfinderMob scout) {
        for (WrappedGoal goal : scout.goalSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof RaidScoutGoal) return;
        }
        BlockPos watch = RaidCamp.builtCentre(village);
        if (scoutPost == null || watch == null) return;
        scout.goalSelector.addGoal(RaidScoutGoal.PRIORITY, new RaidScoutGoal(scout, scoutPost, watch));
    }

    private void ensureOrders(PathfinderMob raider) {
        ensureOrders(raider, RaidMarchGoal.PRIORITY);
    }

    private void ensureOrders(PathfinderMob raider, int priority) {
        for (WrappedGoal goal : raider.goalSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof RaidMarchGoal) return;
        }
        // A witch's random stroll sits at 2, ahead of the march (it wandered off every
        // few seconds); the march still gives way to its attack when it has a target.
        int marchPriority = raider instanceof net.minecraft.world.entity.monster.Witch ? Math.min(priority, 1) : priority;
        RaidMarchGoal march = new RaidMarchGoal(raider, this);
        raider.goalSelector.addGoal(marchPriority, march);
        // Raider plan round 4 (switch raidchase): a far or hidden foe is walked to on the village map.
        raider.goalSelector.addGoal(RaidApproachGoal.PRIORITY,
                new RaidApproachGoal(raider, this, march.mapWalk(), march.wideWalk()));
        raider.targetSelector.addGoal(0, new RaidMarchGoal.HoldFireGoal(raider, this));
        // Our witches heal their raiders (vanilla's healing only runs in a vanilla raid;
        // a Bad Omen raid's witches already have it).
        if (raider instanceof net.minecraft.world.entity.monster.Witch witch && !witch.hasActiveRaid()) {
            raider.goalSelector.addGoal(2, new RaidWitchHealGoal(witch, this));
        }
        // Riders shoot the village's farm animals too.
        if (faction == RaidFaction.RIDERS && raider instanceof net.minecraft.world.entity.monster.AbstractSkeleton) {
            raider.targetSelector.addGoal(4, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                    raider, net.minecraft.world.entity.animal.Animal.class, 10, true, false, this::isFarmAnimal));
        }
        // Wooden doors open for them, as for vanilla raiders in a raid (they leave them open).
        // A ravager is too big for doorways and stays outside.
        // The dead never open doors (and do not break them: see RaidSpawner).
        // Nor does the Warlord: too big for doorways, and it breaks nothing.
        if (raider instanceof Raider && !(raider instanceof net.minecraft.world.entity.monster.Ravager)
                && !(raider instanceof com.alex.test.village.raid.warlord.RaidWarlord)
                && raider.getNavigation() instanceof net.minecraft.world.entity.ai.navigation.GroundPathNavigation ground) {
            ground.setCanOpenDoors(true);
            raider.goalSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.OpenDoorGoal(raider, false));
        }
    }

    private void drainWellbeing() {
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() != null) model.brain().needs().addWellbeing(-WELLBEING_DRAIN_PER_CHECK);
        }
    }

    private void beginRetreat(ServerLevel level, long now, boolean broke) {
        stage = Stage.RETREAT;
        retreatSince = now;
        retreatBeaten = broke;
        Component text;
        if (faction == RaidFaction.RIDERS) {
            text = broke ? Tr.c("chat.dynamicvillages.raid.retreat.riders_broke", "The riders are fleeing back to their camp!")
                    : Tr.c("chat.dynamicvillages.raid.retreat.riders", "The riders have had their fun and ride off.");
        } else if (faction == RaidFaction.KIDNAPPERS) {
            text = broke ? Tr.c("chat.dynamicvillages.raid.retreat.kidnappers_broke",
                            "The kidnappers have failed and run back to their camp.")
                    : Tr.c("chat.dynamicvillages.raid.retreat.kidnappers", "The kidnappers give up and slink back to their camp.");
        } else {
            text = broke ? Tr.c("chat.dynamicvillages.raid.retreat.warband_broke",
                            "The warband is breaking! The raiders are running back to their camp.")
                    : Tr.c("chat.dynamicvillages.raid.retreat.warband", "The warband gives up and pulls back to its camp.");
        }
        // A Bad Omen raid only retreats when its Warlord falls, which has its own line.
        if (faction != RaidFaction.CHALLENGE) {
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN, text, null);
        }
        LOGGER.info("[Raid {}] retreat ({}): {} beaten, {} left", shortId(), broke ? "broke" : "time up",
                beaten, alive.size());
        dirty();
    }

    /**
     * Nobody has been near for a while: the raiders still about go back into
     * the camp's roster with the waves not yet sent, and attack again when a
     * player returns.
     */
    private void pullBack(ServerLevel level, long day) {
        // Nobody here to see it: a villager being led away is let go and walks home.
        if (captiveId != null) freeCaptive(level, false, null);
        // Only let go for want of a watcher: the kidnappers try again when the attack resumes.
        captiveFreed = false;
        snatcherId = null;
        List<List<RaiderKind>> rest = new ArrayList<>();
        if (!alive.isEmpty()) rest.add(new ArrayList<>(alive.values()));
        for (int i = wavesSent; i < waves.size(); i++) rest.add(waves.get(i));
        discardTracked(level, true);
        generation++;
        if (rest.isEmpty()) {
            finish(level, day, true, null);
            return;
        }
        waves = rest;
        wavesSent = 0;
        leaderId = null;
        stage = Stage.CAMP;
        pulledBack = true;
        resumeAt = -1;
        noPlayerSince = -1;
        LOGGER.info("[Raid {}] nobody near: pulled back to camp with {} wave(s) left", shortId(), waves.size());
        dirty();
    }

    /** The raid is over: result, mood, fame, record, and the next one scheduled. */
    private void finish(ServerLevel level, long day, boolean broke, @Nullable RaidResult forced) {
        if (captiveId != null) freeCaptive(level, false, null);
        recallScout(level);
        discardTracked(level, true);
        // Horde and riders have their own win rules (spec 6.2, 6.3): surviving the night, or
        // the riders' time running out, is how those raids normally end, not giving up.
        RaidResult result = forced != null ? forced : switch (faction) {
            case HORDE -> deaths > 1 ? RaidResult.DEFEAT
                    : RaidResult.grade(true, deaths, stacksLost, taken, villagersAtStart);
            case RIDERS -> deaths > 1 || animalsKilled + cropsLost / 12 >= 10 ? RaidResult.DEFEAT
                    : RaidResult.grade(true, deaths, outskirtsDamage(), taken, villagersAtStart);
            case CHALLENGE -> RaidOmenMath.grade(broke, deaths, stacksLost, omenLost());
            default -> RaidResult.grade(broke, deaths, stacksLost + outskirtsDamage(), taken, villagersAtStart);
        };
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() != null) model.brain().needs().addWellbeing(result.moodChange());
        }
        boolean omen = faction == RaidFaction.CHALLENGE;
        int finishedOmen = omen ? omenLevel : 0;
        payFighters(result, finishedOmen);
        if (result == RaidResult.FLAWLESS || result == RaidResult.VICTORY) holdFeast(level);
        fame = Math.max(0, Math.min(RaidMath.MAX_FAME,
                fame + (omen ? RaidOmenMath.fameChange(result, finishedOmen) : result.fameChange())));
        raidsSeen++;
        if (result.isWin()) raidsWon++;
        if (omen && result.isWin()) bestOmenWon = Math.max(bestOmenWon, finishedOmen);
        record.add(0, new Record(day, faction, result, beaten, deaths, escaped, stacksLost, finishedOmen));
        while (record.size() > RECORD_SIZE) record.remove(record.size() - 1);

        List<Component> parts = new ArrayList<>();
        if (faction == RaidFaction.HORDE) {
            parts.add(beaten == 1 ? Tr.c("chat.dynamicvillages.raid.over.zombies_beaten_one", "%s zombie beaten", beaten)
                    : Tr.c("chat.dynamicvillages.raid.over.zombies_beaten", "%s zombies beaten", beaten));
        } else {
            parts.add(raidersBeaten(beaten));
        }
        if (stacksLost > 0) parts.add(stacksStolen(stacksLost));
        if (animalsKilled > 0) {
            parts.add(animalsKilled == 1 ? Tr.c("chat.dynamicvillages.raid.over.animals_killed_one", "%s animal killed", animalsKilled)
                    : Tr.c("chat.dynamicvillages.raid.over.animals_killed", "%s animals killed", animalsKilled));
        }
        if (cropsLost > 0) {
            parts.add(cropsLost == 1 ? Tr.c("chat.dynamicvillages.raid.over.crops_lost_one", "%s crop ridden down", cropsLost)
                    : Tr.c("chat.dynamicvillages.raid.over.crops_lost", "%s crops ridden down", cropsLost));
        }
        if (taken > 0) {
            parts.add(taken == 1 ? Tr.c("chat.dynamicvillages.raid.over.taken_one", "%s villager taken", taken)
                    : Tr.c("chat.dynamicvillages.raid.over.taken", "%s villagers taken", taken));
        } else if (rescued) {
            parts.add(Tr.c("chat.dynamicvillages.raid.over.rescued", "the captive rescued"));
        }
        if (escaped > 0) {
            parts.add(escaped == 1 ? Tr.c("chat.dynamicvillages.raid.over.escaped_one", "%s got away", escaped)
                    : Tr.c("chat.dynamicvillages.raid.over.escaped", "%s got away", escaped));
        }
        if (warlordDown) parts.add(Tr.c("chat.dynamicvillages.raid.over.warlord_slain", "Warlord slain"));
        parts.add(died(deaths));
        Component facts = RaidMessages.joined(parts, " · ");
        RaidMessages.result(village, level, result.isWin() ? RaidMessages.MARK_WIN : RaidMessages.MARK_DEATH,
                result.isWin() ? ChatFormatting.GREEN : ChatFormatting.RED,
                omen ? Tr.c("chat.dynamicvillages.raid.over.omen", "Bad Omen raid over: %1$s · %2$s", resultName(result), facts)
                        : faction == RaidFaction.HORDE
                        ? Tr.c("chat.dynamicvillages.raid.over.horde", "Night over: %1$s · %2$s", resultName(result), facts)
                        : Tr.c("chat.dynamicvillages.raid.over.raid", "Raid over: %1$s · %2$s", resultName(result), facts));
        logOver(level, faction, result, facts);
        LOGGER.info("[Raid {}] over: {} ({} beaten, {} got away, {} villager deaths), fame {}",
                shortId(), result.label(), beaten, escaped, deaths, fame);
        if (omen) {
            if (result.isWin() && omenSpoilsHeld) {
                RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.YELLOW,
                        Tr.c("chat.dynamicvillages.raid.spoils_held", "No spoils this time: the last Bad Omen spoils "
                                + "came less than %s days before this raid.", RaidOmenMath.SPOILS_WAIT_DAYS), null);
            } else if (result.isWin()) {
                deliverSpoils(level, result);
                lastOmenSpoilsDay = day;
            }
        } else {
            leaveWarChest(level, result);
        }
        if (result.isWin()) awardTrophy(level, faction);

        if (omen) {
            // The Bad Omen raid is the player's: the schedule keeps its gap, and only waits two days.
            schedule.postponeTo(day + 2);
        } else {
            schedule.scheduleAfter(day, frequency(), tier, !result.isWin(), ThreadLocalRandom.current());
        }
        endRaid(day + 1);
    }

    /**
     * What a won Bad Omen raid leaves: emeralds, and from level II iron and
     * gold, from III an enchanted book, from IV a totem of undying. It goes
     * into the village's storage (the Town Hall door when that is full).
     */
    private void deliverSpoils(ServerLevel level, RaidResult result) {
        int emeralds = RaidOmenMath.spoilsEmeralds(result, omenLevel);
        int iron = RaidOmenMath.ironIngots(omenLevel);
        int gold = RaidOmenMath.goldIngots(omenLevel);
        List<ItemStack> stacks = new ArrayList<>();
        stacks.addAll(RaidEmeralds.make(village, emeralds));
        addStacks(stacks, Items.IRON_INGOT, iron);
        addStacks(stacks, Items.GOLD_INGOT, gold);
        List<Component> names = new ArrayList<>();
        if (emeralds > 0) {
            names.add(emeralds == 1 ? Tr.c("chat.dynamicvillages.raid.spoils.emeralds_one", "%s emerald", emeralds)
                    : Tr.c("chat.dynamicvillages.raid.spoils.emeralds", "%s emeralds", emeralds));
        }
        if (iron > 0) names.add(Tr.c("chat.dynamicvillages.raid.spoils.iron", "%s iron", iron));
        if (gold > 0) names.add(Tr.c("chat.dynamicvillages.raid.spoils.gold", "%s gold", gold));
        if (RaidOmenMath.enchantedBook(omenLevel)) {
            // An enchanting table's choice of enchantments, at level 20 for III and 5 more for each level after.
            ItemStack book = net.minecraft.world.item.enchantment.EnchantmentHelper.enchantItem(level.random,
                    new ItemStack(Items.BOOK), 20 + 5 * (omenLevel - 3), level.registryAccess(),
                    level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                            .getTag(net.minecraft.tags.EnchantmentTags.IN_ENCHANTING_TABLE));
            stacks.add(book);
            names.add(Tr.c("chat.dynamicvillages.raid.spoils.book", "a book"));
        }
        if (RaidOmenMath.totem(omenLevel)) {
            stacks.add(new ItemStack(Items.TOTEM_OF_UNDYING));
            names.add(Tr.c("chat.dynamicvillages.raid.spoils.totem", "a totem"));
        }
        if (stacks.isEmpty()) return;
        BlockPos at = RaidTrophies.deliver(village, level, stacks);
        if (at == null) {
            LOGGER.warn("[Raid {}] no storage and no Town Hall for the spoils of the Bad Omen raid", shortId());
            return;
        }
        RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                Tr.c("chat.dynamicvillages.raid.spoils", "The spoils of the Bad Omen raid are in the village storage: %s",
                        RaidMessages.joined(names, ", ")), at);
    }

    /** {@code total} of an item as full stacks. */
    private static void addStacks(List<ItemStack> stacks, net.minecraft.world.item.Item item, int total) {
        int left = total;
        while (left > 0) {
            int size = Math.min(left, item.getDefaultMaxStackSize());
            stacks.add(new ItemStack(item, size));
            left -= size;
        }
    }

    /**
     * Every defender who fought gets practice for it: two working days for a
     * flawless win, one for a win, half for a defeat (they still learned).
     * A Bad Omen raid's level ({@code omen}, else 0) makes it worth more.
     */
    private void payFighters(RaidResult result, int omen) {
        float days = switch (result) {
            case FLAWLESS -> 2f;
            case VICTORY, COSTLY -> 1f;
            case DEFEAT -> 0.5f;
        };
        if (omen > 0) days = RaidOmenMath.practiceDays(days, omen);
        for (UUID id : fighters) {
            VillagerModel model = village.getModel(id);
            if (model == null || model.brain() == null || model.puppet() == null) continue;
            model.brain().onRaidVictoryExperience(model.puppet(), days);
        }
        if (!fighters.isEmpty()) LOGGER.info("[Raid {}] {} defender(s) got {} days' practice", shortId(), fighters.size(), days);
        fighters.clear();
    }

    /** A win: tonight the tavern feasts on the spoils (free cakes), if the village has a tavern. */
    private void holdFeast(ServerLevel level) {
        boolean tavern = false;
        for (Building building : village.getBuildings()) {
            if (building instanceof com.alex.test.village.building.TavernBuilding && building.isValid()) tavern = true;
        }
        if (!tavern) return;
        boolean tonight = com.alex.test.village.brain.FeastTable.addVictoryCakes(village, level, FEAST_CAKES);
        RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                tonight ? Tr.c("chat.dynamicvillages.raid.feast_tonight", "Tonight the tavern feasts on the spoils: cake for everyone!")
                        : Tr.c("chat.dynamicvillages.raid.feast_tomorrow",
                                "Tomorrow evening the tavern feasts on the spoils: cake for everyone!"), null);
    }

    /** A defender was hurt by a raider of this raid: it fought. */
    public void onDefenderHurt(com.alex.test.entity.NpcEntity defender) {
        if (!fighting()) return;
        if (fighters.add(defender.getNpcId())) dirty();
    }

    /**
     * The camp stands until sunrise with its war chest: after a win it holds
     * the rest of the spoils (and anything looters carried home); after a
     * defeat, the stolen goods are still there for whoever goes to get them.
     */
    private void leaveWarChest(ServerLevel level, RaidResult result) {
        BlockPos chest = RaidCamp.warChest(campBlocks);
        if (chest == null || !level.hasChunk(chest.getX() >> 4, chest.getZ() >> 4)) return;
        if (!level.getBlockState(chest).is(net.minecraft.world.level.block.Blocks.CHEST)) return;
        if (result.isWin()) {
            com.alex.test.village.building.StorageInventory inventory =
                    com.alex.test.village.building.StorageContainers.inventory(level, chest);
            if (inventory != null) {
                for (ItemStack emeralds : RaidEmeralds.make(village, RaidMath.warChestEmeralds(budget, result))) {
                    inventory.insert(emeralds, false);
                }
            }
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                    Tr.c("chat.dynamicvillages.raid.war_chest_spoils",
                            "The raiders left their war chest at the camp. Take the spoils before sunrise!"), chest);
        } else if (stacksLost > 0) {
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                    Tr.c("chat.dynamicvillages.raid.war_chest_stolen",
                            "The stolen goods are in the war chest at their camp until sunrise."), chest);
        }
    }

    /**
     * Calls the raid off without a result (raids switched off, no camp spot,
     * nobody could be placed). The camp comes down at once.
     */
    /**
     * The raid ends without a fight to the finish (Peaceful, waited too long,
     * a pulled-back horde meeting the sun). A defeat it already dealt still
     * counts, and its camp with any stolen goods stands until sunrise;
     * otherwise it is simply called off.
     */
    private void callOffOrLose(ServerLevel level, long day, Component message) {
        boolean attacked = stage == Stage.ASSAULT || stage == Stage.RETREAT || pulledBack;
        boolean lost = switch (faction) {
            case HORDE -> deaths > 1 || taken > 0;
            case RIDERS -> deaths > 1 || taken > 0 || animalsKilled + cropsLost / 12 >= 10;
            case CHALLENGE -> RaidOmenMath.grade(false, deaths, stacksLost, omenLost()) == RaidResult.DEFEAT;
            default -> RaidResult.grade(false, deaths, stacksLost + outskirtsDamage(), taken, villagersAtStart)
                    == RaidResult.DEFEAT;
        };
        if (attacked && lost) {
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW, message, null);
            finish(level, day, false, RaidResult.DEFEAT);
        } else {
            cancel(level, day, message);
        }
    }

    private void cancel(ServerLevel level, long day, @Nullable Component message) {
        // The same test as callOffOrLose: a raid that attacked has an "attacked" row in the village log.
        boolean attacked = (stage == Stage.ASSAULT || stage == Stage.RETREAT || pulledBack) && !firstHornFailed;
        freeCaptive(level, false, null);
        recallScout(level);
        discardTracked(level, false);
        if (message != null) {
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW, message, null);
        }
        if (attacked) logLeft(level, faction, message);
        LOGGER.info("[Raid {}] called off at stage {}", shortId(), stage);
        if (faction == RaidFaction.CHALLENGE) {
            // The Bad Omen raid was the player's: the schedule keeps its gap, and only waits two days.
            schedule.postponeTo(day + 2);
        } else {
            schedule.scheduleAfter(day, frequency(), tier, false, ThreadLocalRandom.current());
        }
        endRaid(day);
        RaidCamp.takeDown(level, campBlocks);
        if (campBlocks.isEmpty()) camp = null;
    }

    private void endRaid(long packUp) {
        stage = Stage.IDLE;
        generation++;
        alive.clear();
        missingSince.clear();
        waves = new ArrayList<>();
        wavesSent = 0;
        leaderId = null;
        pulledBack = false;
        raidId = null;
        sides.clear();
        omenLevel = 0;
        omenPlayers.clear();
        omenTold.clear();
        waveClearedAt = -1;
        // The horde's "camp" was only where it came from: nothing to pack up.
        if (campBlocks.isEmpty()) camp = null;
        packUpDay = packUp;
        dirty();
    }

    /**
     * Every raider of this raid still in the world leaves it. What looters
     * carry goes to the war chest ({@code keepLoot}: they slipped away with
     * it) or back where it came from (the raid was called off).
     */
    private void discardTracked(ServerLevel level, boolean keepLoot) {
        for (UUID id : alive.keySet()) {
            Entity entity = level.getEntity(id);
            if (entity == null || !entity.isAlive()) continue;
            if (entity instanceof PathfinderMob raider && RaidLoot.isCarrying(raider)) {
                if (keepLoot) stacksLost += RaidLoot.stash(level, raider, warChest());
                else RaidLoot.giveBack(level, raider);
            }
            RaidSpawner.discardWithMount(entity);
        }
        alive.clear();
        missingSince.clear();
    }

    // =========================================================================
    // Raider orders and events
    // =========================================================================

    /**
     * A door to march to: one of the building doors nearest the raider (not
     * the one it is already standing at), picked at random so a group
     * spreads out. Every door within 8 blocks: {@link #nearMarchTarget}
     * (switch {@code raidnear}). Null when there is none: the march goal then
     * holds the raider still until it sees someone to fight. Doors in
     * {@code skip} (the map found no way to them lately, {@code raidnovanilla}) are left out.
     */
    @Nullable
    BlockPos pickMarchTarget(PathfinderMob raider, Set<BlockPos> skip) {
        BlockPos from = raider.blockPosition();
        // Kidnappers with someone tied up guard their camp.
        if (faction == RaidFaction.KIDNAPPERS && captiveId != null && camp != null) {
            return from.distSqr(camp) <= MARCH_ARRIVED_SQ ? null : camp;
        }
        List<BlockPos> doors = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            BlockPos door = building.getDoorPos();
            if (door != null && !skip.contains(door) && door.distSqr(from) > MARCH_ARRIVED_SQ) doors.add(door);
        }
        // Riders keep to the outskirts: pens and ranches, and the outer half of the village.
        if (faction == RaidFaction.RIDERS && !doors.isEmpty()) {
            BlockPos centre = RaidCamp.builtCentre(village);
            List<BlockPos> outskirts = new ArrayList<>();
            for (Building building : village.getBuildings()) {
                if (building instanceof com.alex.test.village.building.RanchBuilding
                        && doors.contains(building.getDoorPos())) outskirts.add(building.getDoorPos());
            }
            if (centre != null) {
                List<BlockPos> byDistance = new ArrayList<>(doors);
                byDistance.sort(Comparator.comparingDouble((BlockPos door) -> door.distSqr(centre)).reversed());
                outskirts.addAll(byDistance.subList(0, Math.max(1, byDistance.size() / 2)));
            }
            doors = outskirts;
        }
        if (doors.isEmpty()) return com.alex.test.village.WalkFeel.raidNear ? nearMarchTarget(raider, from, skip) : null;
        doors.sort(Comparator.comparingDouble(door -> door.distSqr(from)));
        return doors.get(raider.getRandom().nextInt(Math.min(MARCH_TARGET_CHOICES, doors.size())));
    }

    /**
     * Raider pathfinding round 4 (switch {@code raidnovanilla}): the map found no way to the march
     * door, so another one, at any distance, picked among the 6 nearest as {@link #pickMarchTarget}
     * does, never one in {@code skip}. Null when none is left: the march then walks Minecraft's path to
     * the old door, as before.
     */
    @Nullable
    BlockPos otherMarchDoor(PathfinderMob raider, Set<BlockPos> skip) {
        BlockPos from = raider.blockPosition();
        List<BlockPos> doors = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            BlockPos door = building.getDoorPos();
            if (door != null && !skip.contains(door)) doors.add(door);
        }
        if (doors.isEmpty()) return null;
        doors.sort(Comparator.comparingDouble(door -> door.distSqr(from)));
        return doors.get(raider.getRandom().nextInt(Math.min(MARCH_TARGET_CHOICES, doors.size())));
    }

    /**
     * Round 2 of docs/plans/2026-10-23-raider-pathfinding.md (switch {@code raidnear}): every door
     * is within 8 blocks (a tiny village). Until 10-23 the raider got no target and stood still. Now:
     * the furthest door over 7 blocks away (the march goal counts 6 as reached), so a raider walks
     * between the houses; else the middle of the doors; else a spot 8 to 12 blocks out round it, on a
     * map tile when there is one. Null only for a village with no doors. Doors (and the middle) in
     * {@code skip} are left out (round 4).
     */
    @Nullable
    private BlockPos nearMarchTarget(PathfinderMob raider, BlockPos from, Set<BlockPos> skip) {
        BlockPos furthest = null;
        for (Building building : village.getBuildings()) {
            BlockPos door = building.getDoorPos();
            if (door == null || skip.contains(door) || door.distSqr(from) <= NEAR_TARGET_SQ) continue;
            if (furthest == null || door.distSqr(from) > furthest.distSqr(from)) furthest = door;
        }
        if (furthest != null) return furthest;
        BlockPos centre = RaidCamp.builtCentre(village);
        if (centre == null) return null;
        if (centre.distSqr(from) > NEAR_TARGET_SQ && !skip.contains(centre)) return centre;
        com.alex.test.village.NavGraph graph = village.getGraph();
        BlockPos spot = null;
        for (int i = 0; i < PROWL_TRIES; i++) {
            double angle = raider.getRandom().nextDouble() * Math.PI * 2.0;
            int out = PROWL_MIN + raider.getRandom().nextInt(PROWL_SPREAD);
            spot = centre.offset((int) Math.round(Math.cos(angle) * out), 0, (int) Math.round(Math.sin(angle) * out));
            com.alex.test.village.NavNode tile = graph == null ? null : graph.nearestRoutable(spot, 3, 4);
            if (tile != null) spot = tile.feetPos();
            if (spot.distSqr(from) > NEAR_TARGET_SQ) return spot;
        }
        return spot;
    }

    /**
     * Where looters bring what they took. The Bad Omen raid has no camp and no
     * chest: its looters drop the goods where they get away (null).
     */
    @Nullable
    private BlockPos warChest() {
        return omenRaid() ? null : RaidCamp.warChest(campBlocks);
    }

    /**
     * A raider made it back to the camp (in the retreat, or a looter with its
     * arms full): it is gone, and what it carried is in the war chest.
     */
    void onRaiderEscaped(PathfinderMob raider) {
        if (alive.remove(raider.getUUID()) == null && !challengeRaiders.remove(raider.getUUID())) return;
        // Running from a fallen Warlord is a rout, not a getaway.
        if (!warlordDown) escaped++;
        if (RaidLoot.isCarrying(raider) && raider.level() instanceof ServerLevel level) {
            Component what = RaidLoot.describeText(RaidLoot.carried(raider, level));
            stacksLost += RaidLoot.stash(level, raider, warChest());
            RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                    omenRaid() ? Tr.c("chat.dynamicvillages.raid.looter_escaped_omen", "A looter got away with %s", what)
                            : Tr.c("chat.dynamicvillages.raid.looter_escaped", "A looter got away to the camp with %s", what), camp);
        }
        RaidSpawner.discardWithMount(raider);
        dirty();
    }

    /**
     * A storage container a looter should go for: the nearest one that still
     * holds something worth taking, while this raid may take more. Null when
     * there is none (the looter then fights like the rest).
     */
    @Nullable
    BlockPos pickLootTarget(PathfinderMob raider) {
        if (stolenValue >= lootCap || !(raider.level() instanceof ServerLevel level)) return null;
        if (lootChests.isEmpty()) {
            for (Building building : village.getBuildings()) {
                boolean storage = building instanceof com.alex.test.village.building.StorageBuilding;
                boolean kitchen = hungry() && building instanceof com.alex.test.village.building.KitchenBuilding;
                if (!storage && !kitchen) continue;
                for (BlockPos pos : building.getFunctionalBlocks()) {
                    if (com.alex.test.village.building.StorageContainers.inventory(level, pos) != null) {
                        lootChests.add(pos.immutable());
                    }
                }
            }
        }
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockPos pos : lootChests) {
            double distance = pos.distToCenterSqr(raider.position());
            if (distance < bestDistance && RaidLoot.hasLoot(level, pos, hungry())) {
                best = pos;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** A looter has stood at a storage container long enough to empty some of it. */
    void onLooterAtChest(PathfinderMob raider, BlockPos chest) {
        if (!(raider.level() instanceof ServerLevel level)) return;
        double left = lootCap - stolenValue;
        if (left <= 0) {
            RaidSpawner.stopLooting(raider);
            return;
        }
        int maxStacks = tier >= 3 ? 4 : tier == 2 ? 3 : 2;
        double taken = RaidLoot.steal(level, raider, chest, maxStacks, left, hungry());
        if (taken <= 0) return;
        stolenValue += taken;
        if (!lootAlarmSent) {
            lootAlarmSent = true;
            RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                    Tr.c("chat.dynamicvillages.raid.looters_in_storage",
                            "Looters are in the storage room! Stop them before they reach their camp."), chest);
        }
        LOGGER.info("[Raid {}] a looter took {} worth from {}", shortId(), String.format("%.1f", taken),
                chest.toShortString());
        dirty();
    }

    /** A raider of this raid died; a looter's goods go back to storage. */
    public void onRaiderDeath(ServerLevel level, net.minecraft.world.entity.Mob raider, @Nullable Entity killer) {
        UUID raiderId = raider.getUUID();
        if (raiderId.equals(scoutId)) {
            // Beaten only by the village (a player or a defender), not by a fall or a river.
            if (killer instanceof net.minecraft.world.entity.player.Player
                    || killer instanceof com.alex.test.entity.NpcEntity) {
                onScoutDown(level);
            } else {
                scoutId = null;
            }
            return;
        }
        RaiderKind kind = alive.remove(raiderId);
        boolean challenge = kind == null && challengeRaiders.remove(raiderId);
        if (challenge) kind = RaidSpawner.kindOf(raider);
        if (kind == null) return;
        beaten++;
        if (kind == RaiderKind.WARLORD) onWarlordDown(level, raider);
        if (raiderId.equals(snatcherId)) {
            RaidKidnap.markCaptor(raider, false);
            if (captiveId != null && !captiveAtCamp) {
                freeCaptive(level, false, Tr.c("chat.dynamicvillages.raid.captive.kidnapper_fell",
                        "%s got free when the kidnapper fell, and runs home!", captiveNameAtStart()));
            } else if (captiveId == null && !captiveFreed) {
                // Not caught yet: another of the team tries.
                snatcherId = alive.isEmpty() ? null : alive.keySet().iterator().next();
            }
        }
        if (killer instanceof com.alex.test.entity.NpcEntity npc && npc.getProfession().isDefender()) {
            fighters.add(npc.getNpcId());
        }
        // Part of the raid's spoils come from the raiders themselves (the war chest holds the rest),
        // but only from those the village beat: none from a fall, lava, drowning or the sun alone.
        // A quarter of a raider's cost on average (a zombie 1 in 4, a ravager 2); the Warlord costs nothing and drops 1.
        if (beatenByVillage(raider, killer)) {
            double worth = kind == RaiderKind.WARLORD ? 1.0
                    : RaidMath.dropWorth(kind.cost(), twists.contains(RaidTwist.ELITE_GEAR), challenge);
            int dropped = RaidMath.rollDrop(worth, raider.getRandom().nextDouble());
            for (ItemStack emeralds : RaidEmeralds.make(village, dropped)) raider.spawnAtLocation(emeralds, 0.5F);
        }
        missingSince.remove(raiderId);
        if (RaidLoot.isCarrying(raider)) {
            int back = RaidLoot.giveBack(level, raider);
            RaidMessages.detail(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                    back == 1 ? Tr.c("chat.dynamicvillages.raid.looter_beaten_one", "A looter was beaten: %s stack went back to storage", back)
                            : Tr.c("chat.dynamicvillages.raid.looter_beaten", "A looter was beaten: %s stacks went back to storage", back),
                    raider.blockPosition());
        }
        if (raiderId.equals(leaderId)) {
            leaderDown = true;
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                    faction == RaidFaction.CHALLENGE ? Tr.c("chat.dynamicvillages.raid.banner_down_omen", "The banner carrier has fallen!")
                            : Tr.c("chat.dynamicvillages.raid.banner_down", "The warband's banner carrier has fallen!"), null);
        }
        dirty();
    }

    /**
     * The village beat this raider: someone of the village killed it, or hit it
     * in the last few seconds before a fall, fire or the sun finished it.
     */
    private static boolean beatenByVillage(net.minecraft.world.entity.Mob raider, @Nullable Entity killer) {
        if (fightsForVillage(killer)) return true;
        LivingEntity lastHit = raider.getLastHurtByMob();
        return lastHit != null && raider.tickCount - raider.getLastHurtByMobTimestamp() <= KILL_CREDIT_TICKS
                && fightsForVillage(lastHit);
    }

    /** A player (their arrows count as them), a player's tamed animal, one of our villagers or an iron golem. */
    private static boolean fightsForVillage(@Nullable Entity entity) {
        if (entity instanceof net.minecraft.world.entity.OwnableEntity pet
                && pet.getOwner() instanceof net.minecraft.world.entity.player.Player) {
            return true;
        }
        return entity instanceof net.minecraft.world.entity.player.Player
                || entity instanceof com.alex.test.entity.NpcEntity
                || entity instanceof net.minecraft.world.entity.animal.IronGolem;
    }

    /**
     * The Warlord fell: its Helm where it lies (or in storage, if it fell in lava
     * or out of the world), and the raiders still about lose heart and run. The
     * raid ends as a win once they are gone.
     */
    private void onWarlordDown(ServerLevel level, net.minecraft.world.entity.Mob warlord) {
        warlordDown = true;
        RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN,
                Tr.c("chat.dynamicvillages.raid.warlord_fallen", "The Warlord has fallen! The raiders flee."), warlord.blockPosition());
        RaidTrophies.dropOrDeliver(village, level, warlord, new ItemStack(com.alex.test.ModItems.WARLORD_HELM.get()));
        LOGGER.info("[Raid {}] Warlord down: {} raider(s) flee", shortId(), alive.size());
        if (stage == Stage.ASSAULT) beginRetreat(level, level.getGameTime(), true);
    }

    /**
     * For testing: a fighting Bad Omen raid skips straight to its last wave (the
     * Warlord's). The raiders about now are taken away. Null when done, else why not.
     */
    @Nullable
    public String skipToLastWave(ServerLevel level) {
        if (faction != RaidFaction.CHALLENGE) return "no Bad Omen raid here (start one with /vtest raid omen 1)";
        if (stage != Stage.ASSAULT) return "the Bad Omen raid is not fighting yet";
        if (wavesSent >= waves.size()) return "the last wave is already here";
        discardTracked(level, true);
        wavesSent = waves.size() - 1;
        if (!spawnNextWave(level)) return "the last wave could not be placed";
        waveClearedAt = -1;
        lastWaveAt = level.getGameTime();
        dirty();
        return null;
    }

    /** Where the Warlord heads when it is lost: the middle of the built-up village. */
    @Nullable
    public BlockPos warlordCentre() {
        return RaidCamp.builtCentre(village);
    }

    /** The Warlord fighting this village's raid fell below half its health. */
    public void onWarlordEnraged(ServerLevel level, Entity warlord) {
        if (!isActive()) return;
        RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.warlord_enraged", "The Warlord is enraged!"), warlord.blockPosition());
    }

    /** A villager died: counted when it belongs here and a raid is on. */
    public void onVillagerDeath(UUID npcId) {
        if (!fighting() || village.getModel(npcId) == null) return;
        deaths++;
        dirty();
    }

    /** A tagged raider is joining the world: give it its orders, or say it belongs to no raid any more. */
    public Adoption adopt(PathfinderMob raider, RaidSpawner.Tagged tag) {
        ensureLoaded();
        if (!tag.raidId().equals(raidId) || tag.generation() != generation) return Adoption.STALE;
        if (raider.getUUID().equals(scoutId) && stage == Stage.WARNING) {
            ensureScoutOrders(raider);
            return Adoption.ADOPTED;
        }
        if (!isActive() || !alive.containsKey(raider.getUUID())) return Adoption.STALE;
        ensureOrders(raider);
        return Adoption.ADOPTED;
    }

    // =========================================================================
    // Kidnappers
    // =========================================================================

    /** The kidnapper sent to grab a villager, until the captive is tied up at the camp. */
    boolean isSnatcher(PathfinderMob raider) {
        return faction == RaidFaction.KIDNAPPERS && isActive() && !captiveAtCamp && raider.getUUID().equals(snatcherId);
    }

    boolean hasCaptive() {
        return captiveId != null;
    }

    /** This villager is held by the kidnappers right now. */
    public boolean isCaptive(com.alex.test.entity.NpcEntity npc) {
        ensureLoaded();
        return captiveId != null && captiveId.equals(npc.getNpcId());
    }

    boolean isCaptiveAtCamp() {
        return captiveAtCamp;
    }

    /** The villager the snatcher goes for (kept 5 seconds, then chosen again). */
    @Nullable
    com.alex.test.entity.NpcEntity snatchTarget(PathfinderMob raider) {
        if (!(raider.level() instanceof ServerLevel level)) return null;
        long now = level.getGameTime();
        if (victimId != null && now - victimPickedAt < 100) {
            VillagerModel model = village.getModel(victimId);
            com.alex.test.entity.NpcEntity npc = model == null ? null : model.puppet();
            if (npc != null && npc.isAlive() && !model.isBrainSuspended()) return npc;
        }
        com.alex.test.entity.NpcEntity victim = RaidKidnap.pickVictim(village, raider);
        victimId = victim == null ? null : victim.getNpcId();
        victimPickedAt = now;
        return victim;
    }

    /** The snatcher reached its villager: it is led away (its own mind stops until it is free). */
    void capture(PathfinderMob raider, com.alex.test.entity.NpcEntity victim) {
        if (captiveId != null || !(raider.level() instanceof ServerLevel level)) return;
        VillagerModel model = village.getModel(victim.getNpcId());
        if (model == null) return;
        captiveId = victim.getNpcId();
        capturedAt = level.getGameTime();
        model.stopMoving();
        // Seated, it would stay in its chair (sitting stops its walking every tick).
        if (victim.isChairSeated()) victim.standUpFromChair();
        model.setBrainSuspended(true);
        victim.setActivityLabel("taken!");
        RaidKidnap.markCaptor(raider, true);
        RaidKidnap.markCaptive(captiveId, true);
        // A target picked before the grab would last: target goals do not check again.
        for (UUID id : alive.keySet()) {
            if (level.getEntity(id) instanceof net.minecraft.world.entity.Mob mob && mob.getTarget() == victim) {
                mob.setTarget(null);
            }
        }
        RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.captive.taken", "Kidnappers took %s! Stop them before they reach their camp.",
                        captiveName()), victim.blockPosition());
        LOGGER.info("[Raid {}] kidnappers took {}", shortId(), captiveName().getString());
        dirty();
    }

    /** The snatcher got its captive to the camp: tied up there until sunrise or a rescue. */
    void onCaptiveAtCamp(PathfinderMob raider) {
        if (captiveId == null || captiveAtCamp || !(raider.level() instanceof ServerLevel level)) return;
        captiveAtCamp = true;
        RaidKidnap.markCaptor(raider, false);
        RaidMessages.important(village, level, RaidMessages.MARK_WARNING, ChatFormatting.YELLOW,
                Tr.c("chat.dynamicvillages.raid.captive.at_camp", "%1$s is tied up at the kidnappers' camp. "
                        + "Beat the guards there and right click %1$s before sunrise!", captiveName()), camp);
        dirty();
    }

    /** {@link #captiveName} at the start of a sentence: "A villager", not "a villager", while its body is not loaded. */
    private Component captiveNameAtStart() {
        VillagerModel model = captiveId == null ? null : village.getModel(captiveId);
        com.alex.test.entity.NpcEntity npc = model == null ? null : model.puppet();
        return npc == null ? Tr.c("chat.dynamicvillages.raid.captive.someone_start", "A villager") : npc.getDisplayName();
    }

    private Component captiveName() {
        VillagerModel model = captiveId == null ? null : village.getModel(captiveId);
        com.alex.test.entity.NpcEntity npc = model == null ? null : model.puppet();
        return npc == null ? Tr.c("chat.dynamicvillages.raid.captive.someone", "a villager") : npc.getDisplayName();
    }

    /** The captive goes free and walks home. */
    private void freeCaptive(ServerLevel level, boolean byRescue, @Nullable Component message) {
        if (captiveId == null) return;
        VillagerModel model = village.getModel(captiveId);
        if (model != null) {
            model.setBrainSuspended(false);
            com.alex.test.entity.NpcEntity npc = model.puppet();
            if (npc != null) {
                npc.getNavigation().stop();
                npc.setActivityLabel(byRescue ? "rescued" : "free");
                if (byRescue && model.brain() != null) model.brain().needs().addWellbeing(20f);
            }
        }
        if (snatcherId != null) {
            Entity snatcher = level.getEntity(snatcherId);
            if (snatcher != null) RaidKidnap.markCaptor(snatcher, false);
        }
        if (byRescue) rescued = true;
        captiveFreed = true;
        RaidKidnap.markCaptive(captiveId, false);
        captiveId = null;
        captiveAtCamp = false;
        if (message != null) {
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GREEN, message, null);
        }
        dirty();
    }

    /** Sunrise came first: the captive leaves with the kidnappers and is gone from the village. */
    private void takeCaptive(ServerLevel level) {
        if (captiveId == null) return;
        Component name = captiveName();
        VillagerModel model = village.getModel(captiveId);
        com.alex.test.entity.NpcEntity npc = model == null ? null : model.puppet();
        if (model != null && npc == null) {
            // Not loaded: it could not be removed, and would walk back in when its
            // chunk loads. It got free in the dark instead.
            freeCaptive(level, false, Tr.c("chat.dynamicvillages.raid.captive.night",
                    "%s slipped away from the kidnappers in the night!", name));
            return;
        }
        UUID id = captiveId;
        BlockPos where = npc != null ? npc.blockPosition() : camp != null ? camp : village.getBellPos();
        if (where != null) {
            VillageLog.record(village, level, VillageLog.Kind.TAKEN,
                    Tr.c("log.dynamicvillages.taken", "%s was carried off by kidnappers",
                            npc != null ? VillageDamageAlerts.jobAndName(npc) : name),
                    null, where);
        }
        RaidKidnap.markCaptive(id, false);
        captiveId = null;
        captiveAtCamp = false;
        if (model != null) model.setBrainSuspended(false);
        com.alex.test.village.VillageManagerRegistry.get(level.getServer()).forgetVillager(village, id);
        if (npc != null) npc.discard();
        taken++;
        RaidMessages.result(village, level, RaidMessages.MARK_DEATH, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.captive.gone", "The kidnappers left at sunrise with %1$s. %1$s is gone.", name));
        LOGGER.info("[Raid {}] {} was taken for good", shortId(), name.getString());
        dirty();
    }

    /** Every tick: the captive walks behind the one leading it, or waits tied at the camp. */
    private void tickCaptive(ServerLevel level) {
        if (captiveId == null) return;
        if (!isActive()) {
            freeCaptive(level, false, null);
            return;
        }
        VillagerModel model = village.getModel(captiveId);
        if (model == null) {
            // The captive died: the kidnapping is over (the snatcher does not go for another).
            freeCaptive(level, false, null);
            return;
        }
        com.alex.test.entity.NpcEntity npc = model.puppet();
        if (npc == null) return;
        model.setBrainSuspended(true);
        // Neither is saved: after a reload both come back here, with the saved captive.
        RaidKidnap.markCaptive(captiveId, true);
        if (++captiveTick % 5 != 0) return;
        net.minecraft.world.phys.Vec3 target;
        Entity captor = null;
        if (captiveAtCamp) {
            if (camp == null) {
                freeCaptive(level, false, Tr.c("chat.dynamicvillages.raid.captive.got_free", "%s got free and runs home!",
                        captiveNameAtStart()));
                return;
            }
            target = net.minecraft.world.phys.Vec3.atBottomCenterOf(camp.offset(2, 0, 2));
        } else {
            captor = snatcherId == null ? null : level.getEntity(snatcherId);
            if (captor == null || !captor.isAlive()) {
                freeCaptive(level, false, Tr.c("chat.dynamicvillages.raid.captive.got_free", "%s got free and runs home!",
                        captiveNameAtStart()));
                return;
            }
            target = captor.position();
        }
        if (npc.distanceToSqr(target) <= 2.2 * 2.2) {
            npc.getNavigation().stop();
            captiveAim = null;
            return;
        }
        if (!com.alex.test.village.WalkFeel.captivePace) {
            // Until 10-19: a fresh path every quarter second at 1.3, over twice a villager's walk.
            com.alex.test.village.VanillaNavCount.note(com.alex.test.village.VanillaNavCount.Site.CAPTIVE, npc);
            npc.getNavigation().moveTo(target.x, target.y, target.z, 1.3);
            return;
        }
        double speed = captivePace(npc, captor, target);
        boolean due = captiveAim == null || npc.getNavigation().isDone()
                || captiveAim.distanceToSqr(target) > CAPTIVE_AIM_MOVED_SQ
                || captiveTick - captiveAimedAt >= CAPTIVE_ASK_TICKS;
        if (!due) {
            // The path under way keeps its spot; only the pace follows the captor.
            npc.getNavigation().setSpeedModifier(speed);
            return;
        }
        com.alex.test.village.VanillaNavCount.note(com.alex.test.village.VanillaNavCount.Site.CAPTIVE, npc);
        npc.getNavigation().moveTo(target.x, target.y, target.z, speed);
        captiveAim = target;
        captiveAimedAt = captiveTick;
    }

    /**
     * Captive pace (10-19): led along at its captor's walking pace (a pillager's walk is a
     * villager's flee run), a little faster when it has fallen 4 blocks behind; walked to its spot
     * at the camp. Never under a villager's walk nor over a hard run.
     */
    private double captivePace(com.alex.test.entity.NpcEntity npc, @Nullable Entity captor,
                               net.minecraft.world.phys.Vec3 target) {
        double own = npc.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
        if (!(captor instanceof LivingEntity leader) || own <= 0.0) return CAPTIVE_WALK;
        // Its captor walks at the march pace (1.0) while it leads, and runs (RaidMarchGoal's 1.25)
        // home in a retreat: the same speed in blocks.
        double lead = isRetreating() ? CAPTOR_RETREAT_RUN : 1.0;
        double pace = lead * leader.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED) / own;
        if (npc.distanceToSqr(target) > CAPTIVE_BEHIND_SQ) pace *= CAPTIVE_CATCH_UP;
        return net.minecraft.util.Mth.clamp(pace, CAPTIVE_WALK, CAPTIVE_MOST);
    }

    /**
     * A player right clicked a villager. When it is the captive and no
     * kidnapper is near it, it is untied: a rescue. True when the click was
     * about the captive (handled, even if the guards were still there).
     */
    public boolean tryRescue(ServerLevel level, net.minecraft.server.level.ServerPlayer player,
                             com.alex.test.entity.NpcEntity npc) {
        ensureLoaded();
        if (captiveId == null || !captiveId.equals(npc.getNpcId())) return false;
        for (UUID id : alive.keySet()) {
            Entity raider = level.getEntity(id);
            if (raider != null && raider.isAlive() && raider.distanceToSqr(npc) <= RESCUE_CLEAR_SQ) {
                player.displayClientMessage(Tr.c("chat.dynamicvillages.raid.captive.guarded",
                        "Beat the kidnappers guarding %s first.", npc.getDisplayName()), true);
                return true;
            }
        }
        freeCaptive(level, true, Tr.c("chat.dynamicvillages.raid.captive.rescued",
                "%1$s freed %2$s! The kidnappers have lost their prize.", player.getName(), npc.getDisplayName()));
        return true;
    }

    // =========================================================================
    // Skeleton riders on the outskirts
    // =========================================================================

    /** Riders' damage to the outskirts, counted like stolen stacks when the raid is judged. */
    private int outskirtsDamage() {
        return animalsKilled / 3 + cropsLost / 12;
    }

    /**
     * A rider rides its horse through a field: crops under and around the
     * horse are broken (no drops), unless mob griefing is off. Farmers replant.
     */
    void trampleUnder(PathfinderMob rider) {
        if (faction != RaidFaction.RIDERS || !(rider.level() instanceof ServerLevel level)) return;
        if (!level.getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_MOBGRIEFING)) return;
        Entity mount = rider.getVehicle() != null ? rider.getVehicle() : rider;
        BlockPos feet = mount.blockPosition();
        int broken = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos at = feet.offset(dx, 0, dz);
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(at);
                if (state.is(net.minecraft.tags.BlockTags.CROPS)
                        || state.getBlock() instanceof net.minecraft.world.level.block.StemBlock
                        || state.getBlock() instanceof net.minecraft.world.level.block.SweetBerryBushBlock) {
                    if (village.contains(at) && level.destroyBlock(at, false, rider)) broken++;
                }
            }
        }
        if (broken > 0) {
            cropsLost += broken;
            dirty();
        }
    }

    /** A rider killed a farm animal of the village. */
    public void onAnimalKilled() {
        if (!isActive()) return;
        animalsKilled++;
        dirty();
    }

    /** Farm animals a rider goes for, inside the village. */
    private boolean isFarmAnimal(net.minecraft.world.entity.LivingEntity entity) {
        return (entity instanceof net.minecraft.world.entity.animal.Cow
                || entity instanceof net.minecraft.world.entity.animal.Sheep
                || entity instanceof net.minecraft.world.entity.animal.Pig
                || entity instanceof net.minecraft.world.entity.animal.Chicken
                || entity instanceof net.minecraft.world.entity.animal.Rabbit
                || entity instanceof net.minecraft.world.entity.animal.goat.Goat)
                && village.contains(entity.blockPosition());
    }

    // =========================================================================
    // Trophies
    // =========================================================================

    /** A win: a trophy for the first against this kind of raid, and every fifth after. */
    private void awardTrophy(ServerLevel level, RaidFaction kind) {
        int wins = factionWins.merge(kind, 1, Integer::sum);
        dirty();
        if (wins != 1 && wins % 5 != 0) return;
        BlockPos at = RaidTrophies.give(village, level, kind);
        if (at == null) return;
        Component name = RaidTrophies.itemFor(kind).getDescription();
        RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GOLD,
                Tr.c("chat.dynamicvillages.raid.trophy", "A trophy for the Town Hall: the %s is waiting for you. "
                        + "Hang it in an item frame there.", name), at);
    }

    /** Each different trophy hung in the Town Hall adds fame, once. */
    private void checkTrophyWall(ServerLevel level) {
        for (RaidFaction kind : RaidTrophies.hungInTownHall(village, level)) {
            if (!trophiesHung.add(kind)) continue;
            fame = Math.min(RaidMath.MAX_FAME, fame + 2);
            RaidMessages.important(village, level, RaidMessages.MARK_WIN, ChatFormatting.GOLD,
                    Tr.c("chat.dynamicvillages.raid.trophy_hung", "The %s hangs in the Town Hall. The village is prouder: +2 fame.",
                            RaidTrophies.itemFor(kind).getDescription()), null);
            dirty();
        }
    }

    // =========================================================================
    // Challenge raids: Minecraft's own raid (Bad Omen) on this village
    // =========================================================================

    /**
     * A raider of Minecraft's own raid joined the world with this village as
     * its target. The vanilla raid keeps its waves, horn, bar and Hero of the
     * Village; the raid director gives its raiders our orders (march, loot,
     * doors) and the village's response and rewards are ours.
     */
    public void adoptChallenge(ServerLevel level, net.minecraft.world.entity.raid.Raider raider) {
        ensureLoaded();
        net.minecraft.world.entity.raid.Raid raid = raider.getCurrentRaid();
        // A raid already over (celebrating, or reloaded within its last 30 s) was scored already.
        if (raid == null || raid.isOver() || raid.isStopped()) return;
        if (!challengeActive || challengeVanillaId != raid.getId()) startChallenge(level, raid);
        UUID id = raider.getUUID();
        if (challengeRaiders.add(id)) {
            RaidSpawner.Tagged tag = RaidSpawner.tagOf(raider);
            boolean looter = tag != null && challengeTag != null && challengeTag.equals(tag.raidId())
                    ? tag.looter()
                    : !raider.isPatrolLeader() && raider.getType() != net.minecraft.world.entity.EntityType.RAVAGER
                            && ++nonLeaders % 3 == 1;
            RaidSpawner.tag(raider, village.getVillageId(), challengeTag, generation, RaidSpawner.kindOf(raider), looter);
            if (challengeCamp == null) challengeCamp = raider.blockPosition().immutable();
            dirty();
        }
        ensureOrders(raider, CHALLENGE_GOAL_PRIORITY);
    }

    private void startChallenge(ServerLevel level, net.minecraft.world.entity.raid.Raid raid) {
        long day = currentDay(level);
        // One raid at a time: ours, if any, makes way quietly and comes later.
        if (stage != Stage.IDLE) cancel(level, day, null);
        schedule.postponeTo(day + 2);
        challengeActive = true;
        challengeVanillaId = raid.getId();
        challengeTag = UUID.randomUUID();
        challengeCamp = null;
        challengeRaiders.clear();
        RaidCensus census = RaidCensus.of(village, level);
        villagersAtStart = census.villagers();
        lootCap = Math.max(1.0, census.wealth() * LOOT_SHARE);
        stolenValue = 0;
        stacksLost = 0;
        beaten = 0;
        deaths = 0;
        escaped = 0;
        nonLeaders = 0;
        lootAlarmSent = false;
        fighters.clear();
        twists.clear();
        lootChests.clear();
        GuardAlertSystem.soundAlarm(village, level);
        RaidMessages.important(village, level, RaidMessages.MARK_ATTACK, ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.challenge_start",
                        "The Bad Omen brought a raid to the village! Win it for double fame and spoils."), raid.getCenter());
        VillageLog.record(village, level, VillageLog.Kind.RAID, omenBegan(), null, raid.getCenter());
        LOGGER.info("[Raid {}] challenge: vanilla raid {} adopted", shortId(), challengeVanillaId);
        dirty();
    }

    /** Once a second while a challenge is on: orders for its raiders, and its end. */
    private void tickChallenge(ServerLevel level, long day) {
        net.minecraft.world.entity.raid.Raid raid = level.getRaids().get(challengeVanillaId);
        if (raid == null || raid.isStopped() && !raid.isOver()) {
            finishChallenge(level, day, false, true);
            return;
        }
        if (raid.isOver()) {
            finishChallenge(level, day, raid.isVictory(), false);
            return;
        }
        challengeRaiders.removeIf(id -> {
            Entity entity = level.getEntity(id);
            if (entity instanceof PathfinderMob raider && raider.isAlive()) ensureOrders(raider, CHALLENGE_GOAL_PRIORITY);
            return entity != null && !entity.isAlive();
        });
    }

    /**
     * The challenge is over. Won: graded like any raid, with double fame;
     * lost: a defeat; stopped without a result (the raid gave up): nothing.
     */
    private void finishChallenge(ServerLevel level, long day, boolean won, boolean noResult) {
        challengeActive = false;
        for (UUID id : challengeRaiders) {
            Entity entity = level.getEntity(id);
            if (entity instanceof PathfinderMob raider && RaidLoot.isCarrying(raider)) RaidLoot.giveBack(level, raider);
        }
        challengeRaiders.clear();
        challengeCamp = null;
        challengeTag = null;
        challengeVanillaId = -1;
        if (noResult) {
            logLeft(level, RaidFaction.CHALLENGE, Tr.c("log.dynamicvillages.raid.left.no_result",
                    "It stopped without a winner."));
            LOGGER.info("[Raid {}] challenge ended without a result", shortId());
            dirty();
            return;
        }
        RaidResult result = won ? RaidResult.grade(true, deaths, stacksLost, 0, villagersAtStart) : RaidResult.DEFEAT;
        for (VillagerModel model : village.getVillagers()) {
            if (model.brain() != null) model.brain().needs().addWellbeing(result.moodChange());
        }
        payFighters(result, 0);
        if (result == RaidResult.FLAWLESS || result == RaidResult.VICTORY) holdFeast(level);
        fame = Math.max(0, Math.min(RaidMath.MAX_FAME, fame + 2 * result.fameChange()));
        raidsSeen++;
        if (result.isWin()) raidsWon++;
        record.add(0, new Record(day, RaidFaction.CHALLENGE, result, beaten, deaths, escaped, stacksLost, 0));
        while (record.size() > RECORD_SIZE) record.remove(record.size() - 1);
        List<Component> parts = new ArrayList<>();
        parts.add(raidersBeaten(beaten));
        if (stacksLost > 0) parts.add(stacksStolen(stacksLost));
        parts.add(died(deaths));
        parts.add(Tr.c("chat.dynamicvillages.raid.over.fame", "fame %s", fame));
        Component facts = RaidMessages.joined(parts, " · ");
        RaidMessages.result(village, level, result.isWin() ? RaidMessages.MARK_WIN : RaidMessages.MARK_DEATH,
                result.isWin() ? ChatFormatting.GREEN : ChatFormatting.RED,
                Tr.c("chat.dynamicvillages.raid.over.challenge", "Challenge over: %1$s · %2$s",
                        resultName(result), facts));
        logOver(level, RaidFaction.CHALLENGE, result, facts);
        if (result.isWin()) awardTrophy(level, RaidFaction.CHALLENGE);
        LOGGER.info("[Raid {}] challenge over: {} ({} beaten, {} deaths), fame {}", shortId(), result.label(),
                beaten, deaths, fame);
        dirty();
    }

    /** Someone opened this village's Mayor: raids may come from now on. */
    public void markMayorUsed() {
        ensureLoaded();
        if (mayorUsed) return;
        mayorUsed = true;
        dirty();
    }

    /** The Mayor changed how often raids come: Off calls off a raid that has not attacked yet. */
    public void onFrequencyChanged(ServerLevel level) {
        ensureLoaded();
        // Checked on the next stage check (WARNING / CAMP see Off and call it off).
        timer = CHECK_INTERVAL;
        dirty();
    }

    // =========================================================================
    // The raid bar
    // =========================================================================

    private void updateBar(ServerLevel level) {
        if (!isActive() && !omenCountdown()) {
            if (bar != null) {
                bar.removeAllPlayers();
                bar = null;
            }
            return;
        }
        if (bar == null) {
            bar = new ServerBossEvent(Component.empty(), BossEvent.BossBarColor.RED,
                    BossEvent.BossBarOverlay.PROGRESS);
        }
        int unsent = 0;
        for (int i = wavesSent; i < waves.size(); i++) unsent += waves.get(i).size();
        int left = alive.size() + unsent;
        String mark;
        Component text;
        float progress = totalPlanned <= 0 ? 0.0F : Math.min(1.0F, left / (float) totalPlanned);
        int wave = Math.max(1, wavesSent);
        if (omenCountdown()) {
            long ticksLeft = Math.max(0, omenHornAt - level.getGameTime());
            mark = "☠ ";
            text = Tr.c("chat.dynamicvillages.raid.bar.omen_countdown", "Bad Omen %1$s · the raid comes in %2$s s",
                    RaidOmenMath.roman(omenLevel), (ticksLeft + 19) / 20);
            progress = Math.min(1.0F, ticksLeft / (float) OMEN_COUNTDOWN_TICKS);
        } else if (faction == RaidFaction.CHALLENGE) {
            mark = "⚔ ";
            text = Tr.c("chat.dynamicvillages.raid.bar.omen", "Bad Omen %1$s · wave %2$s of %3$s · %4$s left",
                    RaidOmenMath.roman(omenLevel), wave, waves.size(), left);
        } else if (faction == RaidFaction.HORDE) {
            mark = "☠ ";
            text = Tr.c("chat.dynamicvillages.raid.bar.horde", "Night horde · until sunrise · %1$s beaten · %2$s about",
                    beaten, alive.size());
        } else if (faction == RaidFaction.RIDERS) {
            mark = "⚔ ";
            text = stage == Stage.RETREAT
                    ? Tr.c("chat.dynamicvillages.raid.bar.riders_fleeing", "Skeleton riders · %s left · fleeing", left)
                    : Tr.c("chat.dynamicvillages.raid.bar.riders", "Skeleton riders · %s left", left);
        } else if (faction == RaidFaction.KIDNAPPERS) {
            if (captiveId != null) {
                mark = "⚠ ";
                text = captiveAtCamp
                        ? Tr.c("chat.dynamicvillages.raid.bar.captive_at_camp", "Kidnappers took %s · tied up at their camp",
                                captiveName())
                        : Tr.c("chat.dynamicvillages.raid.bar.captive", "Kidnappers took %s · stop them!", captiveName());
            } else {
                mark = "⚔ ";
                text = stage == Stage.RETREAT
                        ? Tr.c("chat.dynamicvillages.raid.bar.kidnappers_running", "Kidnappers · %s left · running off", left)
                        : Tr.c("chat.dynamicvillages.raid.bar.kidnappers", "Kidnappers · %s left", left);
            }
        } else if (stage == Stage.RETREAT) {
            mark = "⚔ ";
            text = Tr.c("chat.dynamicvillages.raid.bar.warband_retreat", "Illager warband · running back to camp · %s left",
                    alive.size());
        } else {
            mark = "⚔ ";
            text = Tr.c("chat.dynamicvillages.raid.bar.warband", "Illager warband · wave %1$s of %2$s · %3$s left",
                    wave, waves.size(), left);
        }
        bar.setName(Component.literal(mark).append(text));
        bar.setProgress(progress);
        Set<ServerPlayer> wanted = new HashSet<>();
        for (ServerPlayer player : RaidMessages.audience(village, level)) {
            if (TownAlertPrefs.of(player).seesFight()) wanted.add(player);
        }
        for (ServerPlayer player : new ArrayList<>(bar.getPlayers())) {
            if (!wanted.contains(player)) bar.removePlayer(player);
        }
        for (ServerPlayer player : wanted) bar.addPlayer(player);
    }

    /** Takes the raid bar off every screen (server stopping, village removed). */
    public void clearBar() {
        if (bar != null) {
            bar.removeAllPlayers();
            bar = null;
        }
    }

    // =========================================================================
    // Commands and the Mayor
    // =========================================================================

    /** Why {@link #startNow} / {@link #warnNow} refuse a village nobody (but a spectator) is inside. */
    static final String ASLEEP = "the village is asleep (no player inside its border)";

    /** Starts a raid at once: warning, camp and horn in one go. Null on success, else why not. */
    @Nullable
    public String startNow(ServerLevel level, @Nullable RaidFaction forced, int forcedBudget) {
        ensureLoaded();
        // Asleep, nobody fights back and the raid never moves on (tick skips it).
        if (village.isDormantOrReconciling()) return ASLEEP;
        if (stage != Stage.IDLE) return "a raid is already on (" + stage + ")";
        if (challengeActive) return "a Bad Omen raid is on";
        if (RaidCamp.builtCentre(village) == null) return "the village has no buildings to raid";
        long day = currentDay(level);
        beginWarning(level, day, forcedBudget, forced);
        attackDay = day;
        if (faction == RaidFaction.HORDE) {
            // Night or not: the command sends the dead now.
            for (int i = 0; i < CAMP_TRIES_MAX && stage == Stage.WARNING; i++) beginHorde(level, day, level.getGameTime());
            return stage == Stage.ASSAULT ? null : "no side for the horde was found";
        }
        for (int i = 0; i < CAMP_TRIES_MAX && stage == Stage.WARNING; i++) {
            setUpCamp(level, day, CAMP_CLOCK, level.getGameTime());
        }
        if (stage != Stage.CAMP) return "no camp spot was found";
        horn(level, level.getGameTime());
        return stage == Stage.ASSAULT ? null : "no raider could be placed at the camp";
    }

    /** Starts the warning now. Null on success, else why not. */
    @Nullable
    public String warnNow(ServerLevel level, @Nullable RaidFaction forced) {
        ensureLoaded();
        if (village.isDormantOrReconciling()) return ASLEEP;
        if (stage != Stage.IDLE) return "a raid is already on (" + stage + ")";
        if (challengeActive) return "a Bad Omen raid is on";
        if (RaidCamp.builtCentre(village) == null) return "the village has no buildings to raid";
        beginWarning(level, currentDay(level), -1, forced);
        return null;
    }

    /** Moves the raid on one stage. Null on success, else why not. */
    @Nullable
    public String nextStage(ServerLevel level) {
        ensureLoaded();
        long day = currentDay(level);
        long now = level.getGameTime();
        Stage before = stage;
        boolean horde = faction == RaidFaction.HORDE || faction == RaidFaction.CHALLENGE;
        String result = nextStageFrom(level, day, now);
        if (result != null) return result;
        // A retreat, or a horde's or Bad Omen raid's assault, ends the raid on purpose; anything
        // else that left it over was a call-off.
        if (stage == Stage.IDLE && before != Stage.RETREAT && !(before == Stage.ASSAULT && horde)) {
            return "the raid was called off (no camp spot, or nowhere to spawn)";
        }
        if (stage == before && before == Stage.WARNING) return "still looking for a camp spot";
        return null;
    }

    @Nullable
    private String nextStageFrom(ServerLevel level, long day, long now) {
        switch (stage) {
            case IDLE -> { return "no raid is on"; }
            case WARNING -> {
                attackDay = Math.min(attackDay, day);
                for (int i = 0; i < CAMP_TRIES_MAX && stage == Stage.WARNING; i++) {
                    if (faction == RaidFaction.CHALLENGE) omenHorn(level, day, now);
                    else if (faction == RaidFaction.HORDE) beginHorde(level, day, now);
                    else setUpCamp(level, day, CAMP_CLOCK, now);
                }
            }
            case CAMP -> horn(level, now);
            case ASSAULT -> {
                if (faction == RaidFaction.HORDE || faction == RaidFaction.CHALLENGE) finish(level, day, true, null);
                else beginRetreat(level, now, true);
            }
            case RETREAT -> finish(level, day, retreatBeaten, null);
        }
        return null;
    }

    /** Ends the raid with a win (flawless or graded) or a defeat. Null on success, else why not. */
    @Nullable
    public String endNow(ServerLevel level, boolean win) {
        ensureLoaded();
        if (stage == Stage.IDLE) return "no raid is on";
        long day = currentDay(level);
        // A pulled-back raid already attacked: it ends with a result like any other.
        if (!isActive() && !pulledBack) {
            cancel(level, day, null);
            return null;
        }
        // "win" is a win whatever the faction's own rules would say about the losses so far.
        RaidResult graded = faction == RaidFaction.CHALLENGE && stage != Stage.IDLE
                ? RaidOmenMath.grade(true, deaths, stacksLost, omenLost())
                : RaidResult.grade(true, deaths, stacksLost + outskirtsDamage(), taken, villagersAtStart);
        finish(level, day, true, win ? (graded == RaidResult.DEFEAT ? RaidResult.COSTLY : graded) : RaidResult.DEFEAT);
        return null;
    }

    public String status() {
        ensureLoaded();
        StringBuilder out = new StringBuilder();
        out.append("stage=").append(stage)
                .append(" frequency=").append(frequency())
                .append(" nextRaidDay=").append(schedule.nextRaidDay())
                .append(" firstDay=").append(schedule.firstDay())
                .append(" mayorUsed=").append(mayorUsed)
                .append(" fame=").append(fame)
                .append(" raids=").append(raidsSeen);
        if (stage != Stage.IDLE) {
            out.append(" budget=").append(budget).append(" tier=").append(tier)
                    .append(" attackDay=").append(attackDay)
                    .append(" waves=").append(wavesSent).append('/').append(waves.size())
                    .append(" alive=").append(alive.size())
                    .append(" beaten=").append(beaten)
                    .append(" escaped=").append(escaped)
                    .append(" deaths=").append(deaths)
                    .append(" faction=").append(faction)
                    .append(" twists=").append(twists)
                    .append(" scout=").append(scoutId == null ? (scoutDown ? "beaten" : "none") : "out")
                    .append(" stolen=").append(String.format("%.1f/%.1f", stolenValue, lootCap))
                    .append(" stacksLost=").append(stacksLost)
                    .append(" leaderDown=").append(leaderDown)
                    .append(" warlord=").append(warlordDown ? "down" : warlordId != null ? "up" : "none")
                    .append(" camp=").append(camp == null ? "none" : camp.toShortString());
            if (faction == RaidFaction.CHALLENGE) {
                out.append(" omen=").append(omenLevel).append(" omenPlayers=").append(omenPlayers.size())
                        .append(" deathLimit=").append(RaidOmenMath.deathLimit(villagersAtStart))
                        .append(" defenders=").append(RaidCensus.defendersAlive(village)).append("/").append(defendersAtStart);
            }
        }
        if (bestOmenWon > 0) out.append(" bestOmenWon=").append(bestOmenWon);
        if (!campBlocks.isEmpty()) out.append(" campBlocks=").append(campBlocks.size()).append(" packUpDay=").append(packUpDay);
        return out.toString();
    }

    /** Threat, defense and budget for the village as it is now. */
    public String threatReport(ServerLevel level) {
        RaidCensus census = RaidCensus.of(village, level);
        int threat = RaidMath.threat(census.villagers(), census.wealth(), fame);
        int raidBudget = RaidMath.budget(threat, census.defense(), RaidCensus.difficultyScale(level.getDifficulty()));
        return String.format("villagers=%d wealth=%.0f fame=%d threat=%d defense=%.1f defenders=%d budget=%d tier=%d size='%s'",
                census.villagers(), census.wealth(), fame, threat, census.defense(), census.defenders(),
                raidBudget, RaidMath.tier(threat), RaidMath.sizeWord(raidBudget, census.defense()));
    }

    /**
     * The Mayor's Raids page: one line per past raid (the last ten), newest first. Like {@link #totalsLine} and
     * {@link #statusLine}, sent as keys with their English, so each player reads it in their own language.
     */
    public List<Component> historyLines() {
        ensureLoaded();
        List<Component> lines = new ArrayList<>();
        for (Record past : record) {
            List<Component> parts = new ArrayList<>();
            parts.add(Tr.c("screen.dynamicvillages.mayor.raids.day", "Day %s", past.day()));
            parts.add(past.omenLevel() > 0
                    ? Tr.c("screen.dynamicvillages.mayor.raids.omen_raid", "Bad Omen raid %s", RaidOmenMath.roman(past.omenLevel()))
                    : factionTitle(past.faction()));
            parts.add(resultName(past.result()));
            parts.add(Tr.c("screen.dynamicvillages.mayor.raids.beaten", "%s beaten", past.beaten()));
            if (past.stacksLost() > 0) parts.add(stacksStolen(past.stacksLost()));
            parts.add(died(past.deaths()));
            lines.add(RaidMessages.joined(parts, " · "));
        }
        return lines;
    }

    /** "3 raids won, 1 lost" over the village's whole life. */
    public Component totalsLine() {
        ensureLoaded();
        if (raidsSeen == 0) return Tr.c("screen.dynamicvillages.mayor.raids.no_raids", "No raids yet.");
        int lost = raidsSeen - raidsWon;
        return raidsWon == 1
                ? Tr.c("screen.dynamicvillages.mayor.raids.totals_one", "%1$s raid won, %2$s lost", raidsWon, lost)
                : Tr.c("screen.dynamicvillages.mayor.raids.totals", "%1$s raids won, %2$s lost", raidsWon, lost);
    }

    /** "Illager warband": a raid's name at the start of a Raids page line ({@link RaidFaction#label} is the English). */
    private static Component factionTitle(RaidFaction kind) {
        return switch (kind) {
            case WARBAND -> Tr.c("screen.dynamicvillages.mayor.raids.faction.warband", "Illager warband");
            case HORDE -> Tr.c("screen.dynamicvillages.mayor.raids.faction.horde", "Night horde");
            case RIDERS -> Tr.c("screen.dynamicvillages.mayor.raids.faction.riders", "Skeleton riders");
            case KIDNAPPERS -> Tr.c("screen.dynamicvillages.mayor.raids.faction.kidnappers", "Kidnappers");
            case CHALLENGE -> Tr.c("screen.dynamicvillages.mayor.raids.faction.omen", "Bad Omen raid");
        };
    }

    /** The strongest Bad Omen raid won here (1 to 5), or 0. */
    public int bestOmenWon() {
        ensureLoaded();
        return bestOmenWon;
    }

    /**
     * One line for the Mayor's Village rules page and the top of the Raids page. Where the raiders are comes from
     * {@link #toward} ("to the north", "nearby"), so a camp next to the village no longer reads "seen to the nearby".
     */
    public Component statusLine() {
        ensureLoaded();
        if (challengeActive) {
            return Tr.c("screen.dynamicvillages.mayor.raids.status.omen_on", "A Bad Omen raid is on! Win it for extra fame and spoils.");
        }
        Component now = switch (stage) {
            case IDLE -> frequency() == RaidFrequency.OFF ? Tr.c("screen.dynamicvillages.mayor.raids.status.off", "Raids are off.")
                    : raidsSeen == 0 ? Tr.c("screen.dynamicvillages.mayor.raids.status.none_yet",
                            "No raiders yet. They come once the village has grown.")
                    : Tr.c("screen.dynamicvillages.mayor.raids.status.quiet", "Quiet for now.");
            case WARNING -> byFaction(
                    Tr.c("screen.dynamicvillages.mayor.raids.status.warning.warband",
                            "A warband is gathering %1$s. Expect it tomorrow %2$s.", toward(), attackWhenText()),
                    Tr.c("screen.dynamicvillages.mayor.raids.status.warning.horde",
                            "The dead are restless. A night horde comes tomorrow night."),
                    Tr.c("screen.dynamicvillages.mayor.raids.status.warning.riders",
                            "Skeleton riders were seen %s. They strike the fields at dawn.", toward()),
                    Tr.c("screen.dynamicvillages.mayor.raids.status.warning.kidnappers",
                            "Kidnappers were seen %s. They come tomorrow at dusk.", toward()),
                    Tr.c("screen.dynamicvillages.mayor.raids.status.warning.omen",
                            "A Bad Omen hangs over the village. Raiders come any moment!"));
            case CAMP -> faction == RaidFaction.HORDE
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.camp.horde", "The night horde is waiting in the dark.")
                    : faction == RaidFaction.RIDERS
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.camp.riders", "Skeleton riders are camped %s. They strike at dawn.", toward())
                    : faction == RaidFaction.KIDNAPPERS
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.camp.kidnappers", "Kidnappers are camped %s. Keep everyone in at dusk.", toward())
                    : Tr.c("screen.dynamicvillages.mayor.raids.status.camp.warband", "A warband is camped %1$s. It attacks %2$s.",
                            toward(), attackWhenText());
            case ASSAULT -> faction == RaidFaction.CHALLENGE
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.assault.omen", "Bad Omen raid! Wave %1$s of %2$s.",
                            Math.max(1, wavesSent), waves.size())
                    : faction == RaidFaction.HORDE
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.assault.horde", "The night horde is here! Hold out until sunrise.")
                    : faction == RaidFaction.RIDERS
                    ? Tr.c("screen.dynamicvillages.mayor.raids.status.assault.riders", "Skeleton riders are on the outskirts!")
                    : faction == RaidFaction.KIDNAPPERS
                    ? (captiveId != null
                            ? Tr.c("screen.dynamicvillages.mayor.raids.status.assault.taken", "%s has been taken to the kidnappers' camp!", captiveNameAtStart())
                            : Tr.c("screen.dynamicvillages.mayor.raids.status.assault.kidnappers", "Kidnappers are in the village!"))
                    : Tr.c("screen.dynamicvillages.mayor.raids.status.assault.warband", "Under attack!");
            case RETREAT -> Tr.c("screen.dynamicvillages.mayor.raids.status.retreat", "The raiders are running back to their camp.");
        };
        if (record.isEmpty()) return now;
        return Tr.c("screen.dynamicvillages.mayor.raids.status.last_raid", "%1$s Last raid: %2$s.", now,
                resultName(record.get(0).result()));
    }

    // =========================================================================
    // Saving
    // =========================================================================

    private void ensureLoaded() {
        if (!loaded) load(village.rules().raidState());
    }

    private void dirty() {
        village.markPersistentDataDirty();
    }

    private static long currentDay(ServerLevel level) {
        return Math.floorDiv(VillageDays.villageTime(level.getDayTime()), VillageDays.DAY_TICKS);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("firstDay", schedule.firstDay());
        tag.putLong("nextRaidDay", schedule.nextRaidDay());
        tag.putLong("lastSeenDay", lastSeenDay);
        tag.putBoolean("mayorUsed", mayorUsed);
        tag.putInt("fame", fame);
        tag.putLong("fameDecayDay", fameDecayDay);
        CompoundTag wins = new CompoundTag();
        for (Map.Entry<RaidFaction, Integer> entry : factionWins.entrySet()) wins.putInt(entry.getKey().name(), entry.getValue());
        tag.put("factionWins", wins);
        ListTag hung = new ListTag();
        for (RaidFaction kind : trophiesHung) hung.add(StringTag.valueOf(kind.name()));
        tag.put("trophiesHung", hung);
        tag.putBoolean("challengeActive", challengeActive);
        tag.putInt("challengeVanillaId", challengeVanillaId);
        if (challengeTag != null) tag.putUUID("challengeTag", challengeTag);
        if (challengeCamp != null) tag.putLong("challengeCamp", challengeCamp.asLong());
        if (challengeActive) {
            tag.putInt("villagersAtStart", villagersAtStart);
            tag.putDouble("lootCap", lootCap);
            tag.putDouble("stolenValue", stolenValue);
            tag.putInt("stacksLost", stacksLost);
            tag.putInt("beaten", beaten);
            tag.putInt("deaths", deaths);
            tag.putInt("escaped", escaped);
            tag.putInt("nonLeaders", nonLeaders);
        }
        tag.putInt("raidsSeen", raidsSeen);
        tag.putInt("raidsWon", raidsWon);
        tag.putInt("bestOmenWon", bestOmenWon);
        if (lastOmenSpoilsDay != Long.MIN_VALUE) tag.putLong("lastOmenSpoilsDay", lastOmenSpoilsDay);
        tag.putInt("generation", generation);
        tag.putString("stage", stage.name());
        tag.putString("faction", faction.name());
        ListTag recent = new ListTag();
        for (RaidFaction past : recentFactions) recent.add(StringTag.valueOf(past.name()));
        tag.put("recentFactions", recent);
        ListTag sideList = new ListTag();
        for (BlockPos side : sides) sideList.add(net.minecraft.nbt.LongTag.valueOf(side.asLong()));
        tag.put("sides", sideList);
        tag.put("campBlocks", RaidCamp.save(campBlocks));
        tag.putLong("packUpDay", packUpDay);
        if (camp != null) tag.putLong("camp", camp.asLong());
        ListTag records = new ListTag();
        for (Record past : record) {
            CompoundTag entry = new CompoundTag();
            entry.putLong("day", past.day());
            entry.putString("result", past.result().name());
            entry.putInt("beaten", past.beaten());
            entry.putInt("deaths", past.deaths());
            entry.putInt("escaped", past.escaped());
            entry.putString("faction", past.faction().name());
            entry.putInt("stacksLost", past.stacksLost());
            entry.putInt("omenLevel", past.omenLevel());
            records.add(entry);
        }
        tag.put("record", records);
        if (stage == Stage.IDLE || raidId == null) return tag;

        tag.putUUID("raidId", raidId);
        tag.putLong("attackDay", attackDay);
        tag.putInt("budget", budget);
        tag.putInt("tier", tier);
        tag.putDouble("defense", defense);
        tag.putInt("villagersAtStart", villagersAtStart);
        tag.putInt("defendersAtStart", defendersAtStart);
        tag.putDouble("campAngle", campAngle);
        tag.putString("sizeWord", sizeWord);
        ListTag waveList = new ListTag();
        for (List<RaiderKind> wave : waves) {
            List<String> names = new ArrayList<>();
            for (RaiderKind kind : wave) names.add(kind.name());
            waveList.add(StringTag.valueOf(String.join(",", names)));
        }
        tag.put("waves", waveList);
        tag.putInt("wavesSent", wavesSent);
        tag.putInt("totalPlanned", totalPlanned);
        tag.putInt("lastWaveSize", lastWaveSize);
        tag.putInt("beaten", beaten);
        tag.putInt("deaths", deaths);
        tag.putInt("escaped", escaped);
        if (leaderId != null) tag.putUUID("leader", leaderId);
        tag.putBoolean("leaderDown", leaderDown);
        if (warlordId != null) tag.putUUID("warlord", warlordId);
        tag.putBoolean("warlordDown", warlordDown);
        tag.putBoolean("pulledBack", pulledBack);
        tag.putBoolean("retreatBeaten", retreatBeaten);
        tag.putLong("campBuiltAt", campBuiltAt);
        tag.putLong("hornAt", hornAt);
        tag.putLong("lastWaveAt", lastWaveAt);
        tag.putLong("retreatSince", retreatSince);
        tag.putDouble("lootCap", lootCap);
        tag.putDouble("stolenValue", stolenValue);
        tag.putInt("stacksLost", stacksLost);
        tag.putInt("nonLeaders", nonLeaders);
        tag.putBoolean("lootAlarmSent", lootAlarmSent);
        if (scoutId != null) tag.putUUID("scout", scoutId);
        if (scoutPost != null) tag.putLong("scoutPost", scoutPost.asLong());
        tag.putBoolean("scoutDown", scoutDown);
        ListTag twistList = new ListTag();
        for (RaidTwist twist : twists) twistList.add(StringTag.valueOf(twist.name()));
        tag.put("twists", twistList);
        tag.putBoolean("twistsRevealed", twistsRevealed);
        tag.putBoolean("curfewSent", curfewSent);
        tag.putInt("totalSpawned", totalSpawned);
        tag.putInt("animalsKilled", animalsKilled);
        tag.putInt("cropsLost", cropsLost);
        if (snatcherId != null) tag.putUUID("snatcher", snatcherId);
        if (captiveId != null) tag.putUUID("captive", captiveId);
        tag.putBoolean("captiveAtCamp", captiveAtCamp);
        tag.putLong("capturedAt", capturedAt);
        tag.putInt("taken", taken);
        tag.putBoolean("rescued", rescued);
        tag.putBoolean("captiveFreed", captiveFreed);
        tag.putInt("omenLevel", omenLevel);
        tag.putBoolean("omenSpoilsHeld", omenSpoilsHeld);
        tag.putLong("omenHornAt", omenHornAt);
        tag.putLong("waveClearedAt", waveClearedAt);
        ListTag omenList = new ListTag();
        for (UUID id : omenPlayers) omenList.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        tag.put("omenPlayers", omenList);
        ListTag fighterList = new ListTag();
        for (UUID id : fighters) fighterList.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        tag.put("fighters", fighterList);
        ListTag raiders = new ListTag();
        for (Map.Entry<UUID, RaiderKind> entry : alive.entrySet()) {
            CompoundTag raider = new CompoundTag();
            raider.putUUID("id", entry.getKey());
            raider.putString("kind", entry.getValue().name());
            raiders.add(raider);
        }
        tag.put("alive", raiders);
        return tag;
    }

    public void load(@Nullable CompoundTag tag) {
        loaded = true;
        if (tag == null || tag.isEmpty()) return;
        schedule.restore(tag.contains("firstDay") ? tag.getLong("firstDay") : -1,
                tag.contains("nextRaidDay") ? tag.getLong("nextRaidDay") : -1);
        lastSeenDay = tag.contains("lastSeenDay") ? tag.getLong("lastSeenDay") : Long.MIN_VALUE;
        mayorUsed = tag.getBoolean("mayorUsed");
        fame = tag.getInt("fame");
        fameDecayDay = tag.contains("fameDecayDay") ? tag.getLong("fameDecayDay") : Long.MIN_VALUE;
        factionWins.clear();
        CompoundTag wins = tag.getCompound("factionWins");
        for (String key : wins.getAllKeys()) factionWins.put(RaidFaction.byName(key), wins.getInt(key));
        trophiesHung.clear();
        ListTag hung = tag.getList("trophiesHung", Tag.TAG_STRING);
        for (int i = 0; i < hung.size(); i++) trophiesHung.add(RaidFaction.byName(hung.getString(i)));
        challengeActive = tag.getBoolean("challengeActive");
        challengeVanillaId = tag.contains("challengeVanillaId") ? tag.getInt("challengeVanillaId") : -1;
        challengeTag = tag.hasUUID("challengeTag") ? tag.getUUID("challengeTag") : null;
        challengeCamp = tag.contains("challengeCamp") ? BlockPos.of(tag.getLong("challengeCamp")) : null;
        if (challengeActive) {
            villagersAtStart = tag.getInt("villagersAtStart");
            lootCap = tag.getDouble("lootCap");
            stolenValue = tag.getDouble("stolenValue");
            stacksLost = tag.getInt("stacksLost");
            beaten = tag.getInt("beaten");
            deaths = tag.getInt("deaths");
            escaped = tag.getInt("escaped");
            nonLeaders = tag.getInt("nonLeaders");
        }
        raidsSeen = tag.getInt("raidsSeen");
        raidsWon = tag.getInt("raidsWon");
        bestOmenWon = tag.getInt("bestOmenWon");
        lastOmenSpoilsDay = tag.contains("lastOmenSpoilsDay") ? tag.getLong("lastOmenSpoilsDay") : Long.MIN_VALUE;
        generation = tag.getInt("generation");
        campBlocks.clear();
        campBlocks.addAll(RaidCamp.load(tag, "campBlocks"));
        packUpDay = tag.contains("packUpDay") ? tag.getLong("packUpDay") : Long.MIN_VALUE;
        camp = tag.contains("camp") ? BlockPos.of(tag.getLong("camp")) : null;
        record.clear();
        ListTag records = tag.getList("record", Tag.TAG_COMPOUND);
        for (int i = 0; i < records.size(); i++) {
            CompoundTag entry = records.getCompound(i);
            RaidResult result = RaidResult.byName(entry.getString("result"));
            if (result == null) continue;
            record.add(new Record(entry.getLong("day"), RaidFaction.byName(entry.getString("faction")), result,
                    entry.getInt("beaten"), entry.getInt("deaths"), entry.getInt("escaped"), entry.getInt("stacksLost"),
                    entry.getInt("omenLevel")));
        }
        faction = RaidFaction.byName(tag.getString("faction"));
        omenLevel = 0;
        omenHornAt = 0;
        waveClearedAt = -1;
        omenPlayers.clear();
        recentFactions.clear();
        ListTag recent = tag.getList("recentFactions", Tag.TAG_STRING);
        for (int i = 0; i < recent.size() && i < RECENT_FACTIONS; i++) {
            recentFactions.add(RaidFaction.byName(recent.getString(i)));
        }
        sides.clear();
        ListTag sideList = tag.getList("sides", Tag.TAG_LONG);
        for (int i = 0; i < sideList.size(); i++) {
            if (sideList.get(i) instanceof net.minecraft.nbt.LongTag side) sides.add(BlockPos.of(side.getAsLong()));
        }

        Stage saved;
        try {
            saved = Stage.valueOf(tag.getString("stage"));
        } catch (IllegalArgumentException e) {
            saved = Stage.IDLE;
        }
        if (saved == Stage.IDLE || !tag.hasUUID("raidId")) {
            stage = Stage.IDLE;
            return;
        }
        stage = saved;
        raidId = tag.getUUID("raidId");
        attackDay = tag.getLong("attackDay");
        budget = tag.getInt("budget");
        tier = Math.max(1, tag.getInt("tier"));
        defense = tag.getDouble("defense");
        villagersAtStart = tag.getInt("villagersAtStart");
        defendersAtStart = tag.getInt("defendersAtStart");
        campAngle = tag.getDouble("campAngle");
        sizeWord = tag.getString("sizeWord");
        waves = new ArrayList<>();
        ListTag waveList = tag.getList("waves", Tag.TAG_STRING);
        for (int i = 0; i < waveList.size(); i++) {
            List<RaiderKind> wave = new ArrayList<>();
            for (String name : waveList.getString(i).split(",")) {
                RaiderKind kind = RaiderKind.byName(name);
                if (kind != null) wave.add(kind);
            }
            if (!wave.isEmpty()) waves.add(wave);
        }
        wavesSent = Math.min(tag.getInt("wavesSent"), waves.size());
        totalPlanned = tag.getInt("totalPlanned");
        lastWaveSize = tag.getInt("lastWaveSize");
        beaten = tag.getInt("beaten");
        deaths = tag.getInt("deaths");
        escaped = tag.getInt("escaped");
        leaderId = tag.hasUUID("leader") ? tag.getUUID("leader") : null;
        leaderDown = tag.getBoolean("leaderDown");
        warlordId = tag.hasUUID("warlord") ? tag.getUUID("warlord") : null;
        warlordDown = tag.getBoolean("warlordDown");
        pulledBack = tag.getBoolean("pulledBack");
        retreatBeaten = tag.getBoolean("retreatBeaten");
        campBuiltAt = tag.getLong("campBuiltAt");
        hornAt = tag.getLong("hornAt");
        lastWaveAt = tag.getLong("lastWaveAt");
        retreatSince = tag.getLong("retreatSince");
        lootCap = tag.getDouble("lootCap");
        stolenValue = tag.getDouble("stolenValue");
        stacksLost = tag.getInt("stacksLost");
        nonLeaders = tag.getInt("nonLeaders");
        lootAlarmSent = tag.getBoolean("lootAlarmSent");
        scoutId = tag.hasUUID("scout") ? tag.getUUID("scout") : null;
        scoutPost = tag.contains("scoutPost") ? BlockPos.of(tag.getLong("scoutPost")) : null;
        scoutDown = tag.getBoolean("scoutDown");
        twists.clear();
        ListTag twistList = tag.getList("twists", Tag.TAG_STRING);
        for (int i = 0; i < twistList.size(); i++) {
            RaidTwist twist = RaidTwist.byName(twistList.getString(i));
            if (twist != null && !twists.contains(twist)) twists.add(twist);
        }
        twistsRevealed = tag.getBoolean("twistsRevealed");
        curfewSent = tag.getBoolean("curfewSent");
        totalSpawned = tag.getInt("totalSpawned");
        animalsKilled = tag.getInt("animalsKilled");
        cropsLost = tag.getInt("cropsLost");
        snatcherId = tag.hasUUID("snatcher") ? tag.getUUID("snatcher") : null;
        captiveId = tag.hasUUID("captive") ? tag.getUUID("captive") : null;
        captiveAtCamp = tag.getBoolean("captiveAtCamp");
        capturedAt = tag.getLong("capturedAt");
        taken = tag.getInt("taken");
        rescued = tag.getBoolean("rescued");
        captiveFreed = tag.getBoolean("captiveFreed");
        omenLevel = tag.getInt("omenLevel");
        omenSpoilsHeld = tag.getBoolean("omenSpoilsHeld");
        omenHornAt = tag.getLong("omenHornAt");
        waveClearedAt = tag.contains("waveClearedAt") ? tag.getLong("waveClearedAt") : -1;
        ListTag omenList = tag.getList("omenPlayers", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < omenList.size(); i++) omenPlayers.add(net.minecraft.nbt.NbtUtils.loadUUID(omenList.get(i)));
        fighters.clear();
        ListTag fighterList = tag.getList("fighters", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < fighterList.size(); i++) fighters.add(net.minecraft.nbt.NbtUtils.loadUUID(fighterList.get(i)));
        alive.clear();
        ListTag raiders = tag.getList("alive", Tag.TAG_COMPOUND);
        for (int i = 0; i < raiders.size(); i++) {
            CompoundTag raider = raiders.getCompound(i);
            RaiderKind kind = RaiderKind.byName(raider.getString("kind"));
            if (raider.hasUUID("id") && kind != null) alive.put(raider.getUUID("id"), kind);
        }
        // A camp-stage raid without a camp (lost to an old save) goes back to its warning.
        if ((stage == Stage.CAMP || isActive()) && camp == null) stage = Stage.WARNING;
    }

    // =========================================================================

    private static Component raiders(int n) {
        return n == 1 ? Tr.c("chat.dynamicvillages.raid.raiders_one", "%s raider", n)
                : Tr.c("chat.dynamicvillages.raid.raiders", "%s raiders", n);
    }

    private static Component wavesText(int n) {
        return n == 1 ? Tr.c("chat.dynamicvillages.raid.waves_one", "%s wave", n)
                : Tr.c("chat.dynamicvillages.raid.waves", "%s waves", n);
    }

    private static Component raidersBeaten(int n) {
        return n == 1 ? Tr.c("chat.dynamicvillages.raid.over.raiders_beaten_one", "%s raider beaten", n)
                : Tr.c("chat.dynamicvillages.raid.over.raiders_beaten", "%s raiders beaten", n);
    }

    private static Component stacksStolen(int n) {
        return n == 1 ? Tr.c("chat.dynamicvillages.raid.over.stacks_stolen_one", "%s stack stolen", n)
                : Tr.c("chat.dynamicvillages.raid.over.stacks_stolen", "%s stacks stolen", n);
    }

    private static Component died(int deaths) {
        if (deaths == 0) return Tr.c("chat.dynamicvillages.raid.over.nobody_died", "nobody died");
        return deaths == 1 ? Tr.c("chat.dynamicvillages.raid.over.died_one", "%s villager died", deaths)
                : Tr.c("chat.dynamicvillages.raid.over.died", "%s villagers died", deaths);
    }

    /** The raid's grade in each player's language ({@link RaidResult#label} is the English). */
    private static Component resultName(RaidResult result) {
        return switch (result) {
            case FLAWLESS -> Tr.c("chat.dynamicvillages.raid.result.flawless", "Flawless");
            case VICTORY -> Tr.c("chat.dynamicvillages.raid.result.victory", "Victory");
            case COSTLY -> Tr.c("chat.dynamicvillages.raid.result.costly", "Costly win");
            case DEFEAT -> Tr.c("chat.dynamicvillages.raid.result.defeat", "Defeat");
        };
    }

    // =========================================================================
    // The village log (Mayor > Village Log)
    // =========================================================================

    /** "An illager warband attacked": the log line for a raid's first horn. */
    private Component logBegan() {
        return byFaction(
                Tr.c("log.dynamicvillages.raid.began.warband", "An illager warband attacked"),
                Tr.c("log.dynamicvillages.raid.began.horde", "A night horde attacked"),
                Tr.c("log.dynamicvillages.raid.began.riders", "Skeleton riders attacked the fields"),
                Tr.c("log.dynamicvillages.raid.began.kidnappers", "Kidnappers came for a villager"),
                omenBegan());
    }

    private static Component omenBegan() {
        return Tr.c("log.dynamicvillages.raid.began.omen", "The Bad Omen raid began");
    }

    /** "Victory against the night horde" or "Lost to the night horde", with the result line's facts on hover. */
    private void logOver(ServerLevel level, RaidFaction foe, RaidResult result, Component facts) {
        Component who = foeName(foe);
        Component line = switch (result) {
            case FLAWLESS -> Tr.c("log.dynamicvillages.raid.over", "%1$s against the %2$s",
                    Tr.c("log.dynamicvillages.raid.result.flawless", "Flawless win"), who);
            case VICTORY -> Tr.c("log.dynamicvillages.raid.over", "%1$s against the %2$s",
                    Tr.c("log.dynamicvillages.raid.result.victory", "Victory"), who);
            case COSTLY -> Tr.c("log.dynamicvillages.raid.over", "%1$s against the %2$s",
                    Tr.c("log.dynamicvillages.raid.result.costly", "Costly win"), who);
            case DEFEAT -> Tr.c("log.dynamicvillages.raid.lost", "Lost to the %s", who);
        };
        VillageLog.record(village, level, result.isWin() ? VillageLog.Kind.RAID_WON : VillageLog.Kind.RAID_LOST,
                line, facts, village.getBellPos());
    }

    /** "The night horde left": a raid that attacked, then ended with no win or loss (sunrise, Peaceful, called off). */
    private void logLeft(ServerLevel level, RaidFaction foe, @Nullable Component why) {
        VillageLog.record(village, level, VillageLog.Kind.RAID_LEFT,
                foe == RaidFaction.CHALLENGE
                        ? Tr.c("log.dynamicvillages.raid.left.omen", "The Bad Omen raid ended")
                        : Tr.c("log.dynamicvillages.raid.left", "The %s left", foeName(foe)),
                why, village.getBellPos());
    }

    private static Component foeName(RaidFaction foe) {
        return switch (foe) {
            case WARBAND -> Tr.c("log.dynamicvillages.raid.foe.warband", "illager warband");
            case HORDE -> Tr.c("log.dynamicvillages.raid.foe.horde", "night horde");
            case RIDERS -> Tr.c("log.dynamicvillages.raid.foe.riders", "skeleton riders");
            case KIDNAPPERS -> Tr.c("log.dynamicvillages.raid.foe.kidnappers", "kidnappers");
            case CHALLENGE -> Tr.c("log.dynamicvillages.raid.foe.omen", "Bad Omen raid");
        };
    }

    /** The same sentence for each kind of raid, each written whole for its own subject. */
    private Component byFaction(Component warband, Component horde, Component riders, Component kidnappers,
                                Component raid) {
        return switch (faction) {
            case WARBAND -> warband;
            case HORDE -> horde;
            case RIDERS -> riders;
            case KIDNAPPERS -> kidnappers;
            case CHALLENGE -> raid;
        };
    }

    private String shortId() {
        return village.getVillageId().toString().substring(0, 8);
    }
}
