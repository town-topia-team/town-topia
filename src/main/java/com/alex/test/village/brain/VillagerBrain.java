package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.supplies.StorageSupplyRestocker;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.EnclosureBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtBubbles;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.social.VillagerMemory;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.bard.BardBalance;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardSkillEffects;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.cook.behavior.CookActivity;
import com.alex.test.village.villagers.registry.VillagerRegistry;
import com.alex.test.village.villagers.guard.GuardAssignmentHandler;
import com.alex.test.village.villagers.child.ChildProfession;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.behavior.BarracksDrills;
import com.alex.test.village.villagers.guard.behavior.DrillActivity;
import com.alex.test.village.villagers.archer.behavior.RangePracticeActivity;
import com.alex.test.village.villagers.guard.behavior.GuardActivity;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.farmer.behavior.FarmerActivity;
import com.alex.test.village.villagers.farmer.behavior.RefillCauldronActivity;
import com.alex.test.village.villagers.farmer.behavior.WashActivity;
import com.alex.test.village.villagers.farmer.FarmerCauldrons;
import com.alex.test.village.building.CauldronIndex;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.lumberjack.behavior.LumberjackActivity;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.druid.behavior.DruidActivity;
import com.alex.test.village.villagers.lumberjack.data.TreeStructure;
import com.alex.test.village.stats.HealthSystem;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.StatActivityStatus;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.stats.WellbeingSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The decision authority for one villager. Owned by {@link VillagerModel};
 * ticked by {@link VillageManager}.
 *
 * <h3>Top-level activity hierarchy</h3>
 * <pre>
 *   Overrides (highest priority, checked every tick):
 *     Flee      — recently attacked
 *     Evening   — civilians: end-of-shift deposit, home, gathering, bed
 *                 ({@link EveningSchedule}, rules in {@link EveningPlanner})
 *     GoHome    — bedtime (guards: off-shift rest)
 *     Eat       — critically hungry and food in inventory
 *     FetchFood — low hunger, no food
 *
 *   Scored (pick highest each decision cycle):
 *     Eat       — hungry with food
 *     FetchFood — hungry, no food
 *     Work      — daytime, motivated, profession
 *     Leisure   — default fallback (internally scores Wander/Admire/Socialize)
 *
 *   Converse — injected by {@link SocialCoordinator}; only fires when the
 *              villager is in WorkActivity (moving phase) or SocializeActivity.
 * </pre>
 */
public final class VillagerBrain {

    /** How long {@link #villageHasFoodCached} may reuse its answer. */
    private static final int VILLAGE_FOOD_CACHE_TICKS = 40;
    /** How often a villager checks its carried food reserve outside of scoring. */
    private static final int FOOD_RESERVE_CHECK_TICKS = 40;
    /** How long a low (not empty) food reserve is tolerated before a top-up trip. */
    private static final int LOW_FOOD_RESERVE_WAIT_TICKS = 2400;
    private static final int LOW_FOOD_RESERVE_WAIT_SPREAD_TICKS = 1200;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int   NBT_SAVE_INTERVAL       = 100;
    private static final int   SKILL_SYNC_INTERVAL     = 20;
    /**
     * Ticks over which the first activity ticks after load are spread. Every
     * brain in a village is built on the same tick, and the first tick of each
     * (restore, switchTo, the activity's start) cost ~2.6 ms: 98 of them made
     * the first two village ticks after a join 257 ms each.
     */
    private static final int   START_SPREAD_TICKS      = 40;
    /** The whole tick, in ms, from which a phase timing line is logged. */
    private static final long  SLOW_TICK_LOG_NANOS     = 20_000_000L;
    private static final int   THOUGHT_WORK_CHECK_TICKS = 200;
    /** Skill experience for finishing one work cycle. */
    private static final float WORK_EXPERIENCE_PER_CYCLE = 4f;
    private static final String TAG_LUMBERJACK_SKILL_VERSION = "LumberjackSkillVersion";
    /**
     * 2: the 10-03 per-tree curve (3,888 XP to level 100). The day count is
     * set by what a tree pays, so changing it needs no new version.
     */
    private static final int LUMBERJACK_SKILL_VERSION = 2;
    /** Lumberjack XP per tree with a full day (LumberjackBalance); shared with the away calculator. */
    public static final com.alex.test.village.stats.DailyJobExperience LUMBERJACK_TREES =
            new com.alex.test.village.stats.DailyJobExperience("LumberjackXpDay", "LumberjackXpEarnedToday",
                    LumberjackBalance.EXPERIENCE_PER_TREE, LumberjackBalance.FULL_DAY_TREES,
                    LumberjackBalance.OVER_FULL_DAY_SHARE);
    /** Miner XP per swinging tick with a full day (MinerBalance); shared with the away calculator. */
    public static final com.alex.test.village.stats.DailyJobExperience MINER_TICKS =
            new com.alex.test.village.stats.DailyJobExperience("MinerXpDay", "MinerXpEarnedToday",
                    MinerBalance.EXPERIENCE_PER_MINING_TICK, MinerBalance.FULL_DAY_MINING_TICKS,
                    MinerBalance.OVER_FULL_DAY_SHARE);
    /** Guard practice XP with a full day (GuardBalance), counted in raw experience. */
    public static final com.alex.test.village.stats.DailyJobExperience GUARD_PRACTICE =
            new com.alex.test.village.stats.DailyJobExperience("GuardXpDay", "GuardXpEarnedToday",
                    1f, GuardBalance.FULL_DAY_EXPERIENCE, GuardBalance.OVER_FULL_DAY_SHARE);
    /** Captain practice XP: the guard's full day, its own tally. */
    public static final com.alex.test.village.stats.DailyJobExperience CAPTAIN_PRACTICE =
            new com.alex.test.village.stats.DailyJobExperience("CaptainXpDay", "CaptainXpEarnedToday",
                    1f, GuardBalance.FULL_DAY_EXPERIENCE, GuardBalance.OVER_FULL_DAY_SHARE);
    /** Archer practice XP: the guard's full day, its own tally. */
    public static final com.alex.test.village.stats.DailyJobExperience ARCHER_PRACTICE =
            new com.alex.test.village.stats.DailyJobExperience("ArcherXpDay", "ArcherXpEarnedToday",
                    1f, GuardBalance.FULL_DAY_EXPERIENCE, GuardBalance.OVER_FULL_DAY_SHARE);
    /** Mage practice XP: the guard's full day, its own tally. */
    public static final com.alex.test.village.stats.DailyJobExperience MAGE_PRACTICE =
            new com.alex.test.village.stats.DailyJobExperience("MageXpDay", "MageXpEarnedToday",
                    1f, GuardBalance.FULL_DAY_EXPERIENCE, GuardBalance.OVER_FULL_DAY_SHARE);
    /** Cook XP per finished batch with a full day (CookBalance); shared with the away calculator. */
    public static final com.alex.test.village.stats.DailyJobExperience COOK_BATCHES =
            new com.alex.test.village.stats.DailyJobExperience("CookXpDay", "CookXpEarnedToday",
                    CookBalance.EXPERIENCE_PER_BATCH, CookBalance.FULL_DAY_BATCHES,
                    CookBalance.OVER_FULL_DAY_SHARE);
    /** Druid XP per dance with a full day (DruidBalance). */
    public static final com.alex.test.village.stats.DailyJobExperience DRUID_DANCES =
            new com.alex.test.village.stats.DailyJobExperience("DruidXpDay", "DruidXpEarnedToday",
                    DruidBalance.EXPERIENCE_PER_DANCE, DruidBalance.FULL_DAY_DANCES,
                    DruidBalance.OVER_FULL_DAY_SHARE);
    /** Cleric XP per care point with a full day (ClericBalance). */
    public static final com.alex.test.village.stats.DailyJobExperience CLERIC_POINTS =
            new com.alex.test.village.stats.DailyJobExperience("ClericXpDay", "ClericXpEarnedToday",
                    com.alex.test.village.villagers.cleric.ClericBalance.EXPERIENCE_PER_POINT,
                    com.alex.test.village.villagers.cleric.ClericBalance.FULL_DAY_POINTS,
                    com.alex.test.village.villagers.cleric.ClericBalance.OVER_FULL_DAY_SHARE);
    /** Teacher XP per lesson unit with children in class, with a full day (TeacherBalance). */
    public static final com.alex.test.village.stats.DailyJobExperience TEACHER_UNITS =
            new com.alex.test.village.stats.DailyJobExperience("TeacherXpDay", "TeacherXpEarnedToday",
                    com.alex.test.village.villagers.teacher.TeacherBalance.EXPERIENCE_PER_UNIT,
                    com.alex.test.village.villagers.teacher.TeacherBalance.FULL_DAY_UNITS,
                    com.alex.test.village.villagers.teacher.TeacherBalance.OVER_FULL_DAY_SHARE);
    /** Butcher XP per kill with a full day (ButcherBalance). */
    public static final com.alex.test.village.stats.DailyJobExperience BUTCHER_KILLS =
            new com.alex.test.village.stats.DailyJobExperience("ButcherXpDay", "ButcherXpEarnedToday",
                    com.alex.test.village.villagers.butcher.ButcherBalance.EXPERIENCE_PER_KILL,
                    com.alex.test.village.villagers.butcher.ButcherBalance.FULL_DAY_KILLS,
                    com.alex.test.village.villagers.butcher.ButcherBalance.OVER_FULL_DAY_SHARE);
    /** Blacksmith XP per forge point with a full day (BlacksmithBalance); shared with the away calculator. */
    public static final com.alex.test.village.stats.DailyJobExperience BLACKSMITH_POINTS =
            new com.alex.test.village.stats.DailyJobExperience("BlacksmithXpDay", "BlacksmithXpEarnedToday",
                    BlacksmithBalance.EXPERIENCE_PER_POINT, BlacksmithBalance.FULL_DAY_POINTS,
                    BlacksmithBalance.OVER_FULL_DAY_SHARE);
    /** Rancher XP per job with a full day (RancherBalance); shared with the away calculator. */
    public static final com.alex.test.village.stats.DailyJobExperience RANCHER_JOBS =
            new com.alex.test.village.stats.DailyJobExperience("RancherXpDay", "RancherXpEarnedToday",
                    com.alex.test.village.villagers.rancher.RancherBalance.EXPERIENCE_PER_JOB,
                    com.alex.test.village.villagers.rancher.RancherBalance.FULL_DAY_JOBS,
                    com.alex.test.village.villagers.rancher.RancherBalance.OVER_FULL_DAY_SHARE);
    private static final String TAG_RANCHER_SKILL_VERSION = "RancherSkillVersion";
    private static final String TAG_BUTCHER_SKILL_VERSION = "ButcherSkillVersion";
    private static final String TAG_TEACHER_SKILL_VERSION = "TeacherSkillVersion";
    private static final String TAG_CLERIC_SKILL_VERSION = "ClericSkillVersion";
    private static final String TAG_FARMER_SKILL_VERSION = "FarmerSkillVersion";
    /**
     * 2: the 10-03 per-job curve (3,888 XP to level 100). The day count is
     * set by what a job pays, so changing it needs no new version.
     */
    private static final int FARMER_SKILL_VERSION = 2;
    private static final String TAG_MINER_SKILL_VERSION = "MinerSkillVersion";
    private static final String TAG_DRUID_SKILL_VERSION = "DruidSkillVersion";
    private static final String TAG_COOK_SKILL_VERSION = "CookSkillVersion";
    private static final String TAG_BARD_SKILL_VERSION = "BardSkillVersion";
    private static final String TAG_GUARD_SKILL_VERSION = "GuardSkillVersion";
    private static final String TAG_ARCHER_SKILL_VERSION = "ArcherSkillVersion";
    private static final String TAG_BLACKSMITH_SKILL_VERSION = "BlacksmithSkillVersion";
    private static final String TAG_ENCHANTER_SKILL_VERSION = "EnchanterSkillVersion";
    /** Intelligence trickle from working, and the larger gain from talking. */
    public static final float INTELLIGENCE_PER_WORK_CYCLE  = 0.1f;
    private static final float INTELLIGENCE_PER_CONVERSATION = 0.5f;
    private static final int   MAX_SOCIAL_ACTIONS_PER_DAY = 3;
    /** Cooldown applied when a hang session in {@link SocializeActivity} ends without a partner. */
    private static final int   UNPAIRED_SOCIALIZE_BACKOFF_TICKS = 300;
    private static final String TAG_CURRENT_ACTIVITY = "CurrentActivity";
    private static final String TAG_EVENING_DAY = "EveningDay";
    private static final String TAG_EVENING_FLAGS = "EveningFlags";

    private final VillagerModel model;
    /** Rolled from the id until the entity's saved copy is read in {@link #loadFrom}. */
    private Personality         personality;
    private final VillagerStats needs = new VillagerStats();
    private final VillagerSkills skills = new VillagerSkills();
    private final HealthSystem  health = new HealthSystem();
    private final VillagerDiary diary;
    /** What this villager has been through lately; the source of conversation topics. */
    private final VillagerMemory memory = new VillagerMemory();
    /** Decides when a thought is floated over this villager's head. */
    private final ThoughtBubbles bubbles = new ThoughtBubbles();
    /** Whether the building this villager is in feels crowded; once per visit. */
    private final CrowdingSense crowding;

    private Activity current;
    private Activity forced;

    // A pause: a player has this villager's screen open, or a cleric is
    // tending it, and the current activity is not ticked. Everything the
    // activity holds is kept (see VillagerPause) and it carries on after.
    /** Game time the current pause began, or {@link Long#MIN_VALUE} when not paused. */
    private long pauseStartedAt = Long.MIN_VALUE;
    /** Ticks spent in pauses that are over; with the current one, what {@link Activity.Context#clock()} leaves out. */
    private long pausedTicksDone;
    /** The animation at the start of this tick, before a pause could set it to idle. */
    private VillagerAnimationState animationAtTickStart;
    /** The activity and its clip when the pause began, to put the clip back after. */
    private Activity pausedActivity;
    private VillagerAnimationState pausedAnimation;
    /** The pause went on for {@link VillagerPause#MAX_PAUSE_TICKS} and the activity was given up. */
    private boolean pauseGaveUp;

    /** Looping work clips put back after a pause, which leaves the villager idle. One-shots are not replayed. */
    private static final EnumSet<VillagerAnimationState> RESUMED_CLIPS = EnumSet.of(
            VillagerAnimationState.CHOP, VillagerAnimationState.LUMBER_CHOP,
            VillagerAnimationState.CRAFTING, VillagerAnimationState.CRAFT_TABLE,
            VillagerAnimationState.TILL, VillagerAnimationState.TILL_SKILLED,
            VillagerAnimationState.PLANT, VillagerAnimationState.HARVEST, VillagerAnimationState.MINE,
            VillagerAnimationState.FORGE, VillagerAnimationState.READ, VillagerAnimationState.PICK,
            VillagerAnimationState.PICK_LOW, VillagerAnimationState.PICK_BUSH, VillagerAnimationState.PICK_HIGH,
            VillagerAnimationState.HARVEST_CHOP,
            VillagerAnimationState.SMITH_ANVIL, VillagerAnimationState.SMITH_ANVIL_HEAVY, VillagerAnimationState.SMITH_ANVIL_DRAW,
            VillagerAnimationState.SMITH_ENCHANT, VillagerAnimationState.SMITH_ENCHANT_TAP,
            VillagerAnimationState.SMITH_SMITHING, VillagerAnimationState.SMITH_RECYCLE, VillagerAnimationState.SMITH_FURNACE,
            VillagerAnimationState.PLAY_VIOLIN, VillagerAnimationState.VIOLIN_DANCE, VillagerAnimationState.VIOLIN_PLAY,
            VillagerAnimationState.PLAY_FLUTE, VillagerAnimationState.FLUTE_DANCE, VillagerAnimationState.FLUTE_PLAY,
            VillagerAnimationState.PLAY_GUITAR, VillagerAnimationState.GUITAR_JAM, VillagerAnimationState.GUITAR_PLAY,
            // The cook's loops only: the table dishes and its two waits.
            VillagerAnimationState.COOK_KNEAD, VillagerAnimationState.COOK_COOKIES, VillagerAnimationState.COOK_WHISK,
            VillagerAnimationState.COOK_PIE, VillagerAnimationState.COOK_CHOP_STIR, VillagerAnimationState.COOK_GILD,
            VillagerAnimationState.COOK_WHITTLE, VillagerAnimationState.COOK_PRESS_CANE,
            VillagerAnimationState.COOK_CAMP_WAIT, VillagerAnimationState.COOK_WAIT_STATION);
    private int  decisionCooldown  = 0;
    private int  conversationCooldown = 1200;
    private int  socialCooldown    = 0;
    private int  socialActionsToday = 0;
    private int  workBackoff       = 0;
    private int  fetchFailCooldown = 0;
    /**
     * Game time of the last tick this villager was called out: a defender
     * with an emergency, or the cleric staying out at the fight (both read
     * at the top of checkOverrides). The per-tick answer flips with the
     * activity a mage or cleric is in (true while fetching, false back at
     * work), so a hungry one with empty pockets went fetch_food and back to
     * the fight every tick for the whole alarm, searching a chest each time.
     * The fetch override now waits CALL_OUT_GRACE_TICKS past the last call.
     */
    private static final int CALL_OUT_GRACE_TICKS = 60;
    private long lastCalledOutAt = Long.MIN_VALUE;
    /** Last tick's call-out answer, for the flip diagnostic in noteCallOutFlip. */
    private boolean calledOutLastTick = false;
    /** Game time the fetch override last started a food run, for the same diagnostic. */
    private long fetchFoodStartedAt = Long.MIN_VALUE;
    private long callOutFlipLoggedAt = Long.MIN_VALUE;
    private int  sleepHungerRetryCooldown = 0;
    private int  villageFoodRecheck = 0;
    private int  urgentWorkRecheck  = 0;
    /**
     * Ticks until the periodic profession-slot sync. XP awards sync their own
     * track immediately; this only catches slot changes made from elsewhere.
     */
    private int  skillSyncTimer     = 0;
    /** Ticks still to wait before the first restore / override / activity work (START_SPREAD_TICKS). */
    private int  startDelayTicks    = 0;
    /** The activity whose tickOuter ran last; a different one now means its start ran this tick. */
    private Activity lastTickedActivity;
    /**
     * Last {@code hasImmediateWork} answer from a decision, reused by the
     * thought bubble for {@link #THOUGHT_WORK_CHECK_TICKS}. The bubble only
     * picks an icon; decisions always ask fresh.
     */
    private long workCheckAt = Long.MIN_VALUE;
    private boolean workCheckResult;
    private Profession workCheckProfession = Profession.NONE;
    /** Guard or captain duty, drills, hits, kills and the skill they earned since dawn, for the daily line. */
    private int   defenderDutyMinutesToday = 0;
    private float defenderDrillMinutesToday = 0f;
    private int   defenderHitsToday = 0;
    private int   defenderKillsToday = 0;
    private float defenderXpToday = 0f;
    /** Cook batches and the skill they earned since dawn, for the daily diary line. */
    private int   cookBatchesToday = 0;
    private int   cookPraiseToday = 0;
    private float cookXpToday = 0f;
    /** Druid dances and the skill they earned since dawn, for the daily diary line. */
    private int   druidDancesToday = 0;
    private float druidXpToday     = 0f;
    /** Miner swinging time and the skill it earned since dawn, for the daily diary line. */
    private int   minerMiningTicksToday = 0;
    private float minerXpToday          = 0f;
    /** Farmer jobs (tills, plantings, harvests) and the skill they earned since dawn, for the daily line. */
    private int   farmerJobsToday = 0;
    private float farmerXpToday   = 0f;
    /** Lumberjack trees felled, their logs and the skill they earned since dawn, for the daily line. */
    private int   lumberjackTreesToday = 0;
    private int   lumberjackLogsToday  = 0;
    private float lumberjackXpToday    = 0f;
    /**
     * Chests this villager recently failed to walk to, by packed position, and
     * the game time each stays written off until. Kept on the brain rather
     * than the activity so the next trip does not walk into the same wall.
     */
    private final java.util.Map<Long, Long> unreachableChestUntil = new java.util.HashMap<>();
    /** Game time until which "food exists but I can't reach it" is shown. */
    private long foodUnreachableUntil = Long.MIN_VALUE;
    /** Set by {@link #onRescuedFromTrap}: the next tick ends a leisure filler and decides again. */
    private boolean endLeisureAfterRescue = false;
    /** Game time the carried food reserve first dropped to the low band; see {@link #lowFoodReserveOverdue}. */
    private long foodReserveLowSince = Long.MIN_VALUE;
    /** Game time of the last pre-bed food top-up; see {@link #bedFoodTopUp}. */
    private long bedTopUpTriedAt = Long.MIN_VALUE;
    private boolean villageFoodKnown   = false;
    private boolean villageFoodPresent = false;
    /** Cache for {@link #allFoodWrittenOff}; a new write-off resets the recheck time. */
    private long    foodWrittenOffRecheckAt = 0L;
    private boolean foodWrittenOff          = false;
    private int  saveTimer         = 0;
    /** Village day of the last tick, for the new-day reset; MIN_VALUE before the first tick. */
    private long rolloverDay       = Long.MIN_VALUE;
    /**
     * The away calculator lived this villager through a night, and whether in its
     * own bed ({@link #livedNightsAway}); taken by the next day check. Not saved.
     */
    private boolean nightsLivedAway     = false;
    private boolean nightsLivedAwayInBed = false;
    private boolean loaded              = false;
    /** The puppet whose loaded pose was last checked; a new entity after a reload gets checked again. */
    private NpcEntity poseCheckedPuppet;
    private String restoredActivityName = "";
    private boolean sleptInBedTonight   = false;
    /** Was in its sleep pose last tick; tonight's sleep gain is rolled on lying down. */
    private boolean restingLastTick     = false;
    /** Tonight's latest evening venue is a tavern: chats there lift the sleep refill. */
    private boolean eveningAtTavern     = false;
    /** Game tick when this resident may wake after a player skips the night. */
    private long sleepSkipWakeAtGameTime;


    private boolean stayedInTownHallTonight = false;

    /** This villager's end-of-work, gathering and leaving times; see {@link EveningSchedule}. */
    private final EveningSchedule eveningSchedule;
    /** What it has already done this evening; saved with the brain. */
    private final EveningState    evening = new EveningState();
    /** Tonight's home: the building holding its bed, or the Town Hall shelter. */
    private java.util.UUID eveningHomeId;
    private long   eveningHomeDay = Long.MIN_VALUE;
    private long   eveningHomeRetryAt = Long.MIN_VALUE;
    /** Last time the costlier evening facts were looked at; see {@link #eveningFacts}. */
    private long   eveningFactsAt = Long.MIN_VALUE;
    private boolean eveningDepositables;
    private boolean eveningTavernReachable;
    private java.util.UUID eveningTavernId;
    private java.util.UUID eveningVisitHostId;
    private int    eveningFactsCheckedPhase = -1;
    /** A dirty farmer's cauldron for tonight, refreshed with the other evening facts. Not saved. */
    @javax.annotation.Nullable
    private com.alex.test.village.villagers.farmer.FarmerCauldrons.WashChoice eveningWash;
    /** Tonight's wash-before-deposit roll, fixed once per evening. */
    private boolean eveningWashFirst;
    private long    eveningWashFirstDay = Long.MIN_VALUE;
    /** After a failed wash, no new one before this game time. */
    private long    washRetryAt = Long.MIN_VALUE;
    /** The evening "no cauldron" was last written to the diary, so it is written once a night. */
    private long    washNoneLoggedDay = Long.MIN_VALUE;

    public VillagerBrain(VillagerModel model) {
        this.model       = model;
        this.personality = new Personality(model.npcId);
        // The stress test crowd (hundreds of guards) keeps no diary files.
        this.diary       = new VillagerDiary(model.npcId, () -> {
            com.alex.test.entity.NpcEntity body = model.puppet();
            return body != null && body.getTags().contains(com.alex.test.command.StressTestCommands.TAG);
        });
        this.eveningSchedule = EveningSchedule.forVillager(model.npcId);
        // Every brain in a village is built on the same tick at world load, so
        // periodic timers that all start at zero fire on the same tick forever
        // after: every resident saving, flushing its diary and walking the
        // storage buildings for its thought bubble at once. In a big village
        // that stalled the server long enough to freeze everyone mid-stride.
        // Offset each villager's phase by its id to spread the work out.
        int phase = model.npcId.hashCode() & 0x7fffffff;
        this.saveTimer      = phase % NBT_SAVE_INTERVAL;
        this.skillSyncTimer = phase % SKILL_SYNC_INTERVAL;
        // Same for the first activity tick: a 0-2 s wait is harmless at load
        // and for a birth or an arrival, and it spreads the first tick's
        // restore and start work over 40 ticks instead of one.
        this.startDelayTicks = phase % START_SPREAD_TICKS;
        bubbles.staggerChecks(phase);
        this.crowding = new CrowdingSense(phase);
    }

    public VillagerStats needs()           { return needs; }
    public VillagerSkills skills()         { return skills; }

    /** Load saved tracks and migrations before offline work is credited, without ticking activities. */
    public void prepareForOfflineReconciliation(NpcEntity npc) {
        if (!loaded) {
            loadFrom(npc);
            loaded = true;
        }
    }
    public Personality   personality()     { return personality; }
    public Activity      currentActivity() { return current; }
    public VillagerDiary diary()           { return diary; }
    public VillagerMemory memory()         { return memory; }
    /** Wellbeing overcrowding has cost this villager today, out of {@link com.alex.test.village.stats.CrowdingBalance#DAILY_CAP}. */
    public float crowdingTakenToday(ServerLevel level) { return crowding.takenToday(level); }

    /**
     * Note that something worth talking about has happened to this villager.
     *
     * <p>Activities call this directly for the things a periodic state check
     * cannot see — being chased, finishing a meal, levelling up — while the
     * standing problems (hunger, no bed, no work) are recorded by
     * {@link ThoughtBubbles#onState} from the state it has already computed.
     */
    public void remember(MemoryTopic topic, ServerLevel level) {
        memory.record(topic, level.getGameTime());
    }

    /**
     * Record an event and, if a player is near enough to see it, show its icon
     * straight away. The memory is kept either way.
     */
    public void rememberAndShow(MemoryTopic topic, ThoughtIcon icon, int ticks, ServerLevel level) {
        remember(topic, level);
        NpcEntity npc = model.puppet();
        if (npc != null && npc.isAlive()) bubbles.showEvent(npc, level, icon, ticks);
    }

    /** Hiding from a monster: running for shelter, or indoors because the bell rang. */
    public boolean isTakingShelter() {
        return current instanceof ShelterActivity
                || current instanceof FleeActivity flee && flee.isToShelter();
    }

    /** Shows {@code icon} over this villager if a player is near enough to see it; nothing is remembered. */
    public void showBubble(ThoughtIcon icon, int ticks, ServerLevel level) {
        NpcEntity npc = model.puppet();
        if (npc != null && npc.isAlive()) bubbles.showEvent(npc, level, icon, ticks);
    }

    public boolean suppressesUiAttention() {
        // A lumberjack's work session includes travel to trees and storage.
        // Keep attention on the player during travel, but leave stationary
        // work such as chopping and crafting uninterrupted.
        return current instanceof ConverseActivity
                || forced instanceof ConverseActivity
                || current instanceof FleeActivity
                || current instanceof EatActivity
                || (profession() == Profession.LUMBERJACK
                    && current instanceof WorkActivity work
                    && !work.isMovingPhase() && !model.isMoving());
    }

    public Profession profession() {
        NpcEntity npc = model.puppet();
        return npc != null ? npc.getProfession() : Profession.NONE;
    }

    public void setConversationCooldown(int ticks) { conversationCooldown = ticks; }
    public int conversationCooldownLeft() { return Math.max(0, conversationCooldown); }

    public boolean canStartSocialAction() {
        return socialCooldown <= 0 && socialActionsToday < MAX_SOCIAL_ACTIONS_PER_DAY;
    }

    /** True when the Mayor may start a new conversation with this villager. */
    public boolean canAcceptMayorConversation() {
        return conversationCooldown <= 0
                && (!(current instanceof WorkActivity work)
                || work.allowsRoutineInterruption())
                && !(current instanceof ConverseActivity)
                && !(current instanceof GoHomeActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof LibraryActivity);
    }

    public void recordSocialSuccess() {
        if (socialActionsToday >= MAX_SOCIAL_ACTIONS_PER_DAY) return;
        socialActionsToday++;
        socialCooldown = 800;
    }

    /**
     * A {@link SocializeActivity} ran its whole hang duration without ever
     * being paired into a conversation. Doesn't count against the daily
     * social-action cap (that's for actual conversations, gated separately by
     * {@link ConverseActivity}'s {@code noLimits} flag) — just a short
     * cooldown so {@link LeisureActivity#pickSub} doesn't immediately
     * recompute the same winning social score and walk straight back into
     * another empty hang session. Lets wander/admire get a turn while the
     * villager's needs haven't changed enough to lower the social score on
     * their own.
     */
    public void recordUnpairedSocializeAttempt() {
        if (socialCooldown <= 0) {
            socialCooldown = UNPAIRED_SOCIALIZE_BACKOFF_TICKS;
        }
    }

    public void markSleptInBedTonight()       { sleptInBedTonight = true; }

    /**
     * The away calculator's WRITE: it lived this villager through at least one
     * night (its sleep and its dawn heals are in the copy). So the first day change
     * after the catch-up gives no second heal to full, and in its own bed no
     * sleepless memory (plan round 12). Taken by the next day check whether the
     * day changed or not; after a restart the first check never ends a night.
     */
    public void livedNightsAway(boolean inOwnBed) {
        nightsLivedAway = true;
        nightsLivedAwayInBed = inOwnBed;
    }

    /**
     * A player skipped the night: everyone gets a full night's sleep gain, in
     * bed or on the way to it, except a villager awake on a night shift. A
     * villager with an assigned bed gets the bed amount. Saved immediately,
     * including when a reloaded sleeper has not ticked yet.
     */
    public void onNightSkipped(NpcEntity npc, boolean resting, boolean hasBed) {
        if (!resting && (isDefender() || clericNightShift(npc))) return;
        if (!loaded) needs.load(npc.getBrainData());
        if (!needs.sleepRolledTonight()) {
            needs.startNightSleep(rollSleepGain(npc), hasBed || sleptInBedTonight);
        }
        needs.finishNightSleep();
        needs.save(npc.getBrainData());
    }

    private static float rollSleepGain(NpcEntity npc) {
        return WellbeingSystem.SLEEP_GAIN_MIN
                + npc.getRandom().nextFloat() * (WellbeingSystem.SLEEP_GAIN_MAX - WellbeingSystem.SLEEP_GAIN_MIN);
    }

    /** The night ended properly in its sleep spot: the rest of tonight's gain arrives at once. */
    public void onWokeAfterFullSleep() {
        needs.finishNightSleep();
    }

    public void staggerWakeAfterPlayerSleep(ServerLevel level) {
        sleepSkipWakeAtGameTime = level.getGameTime()
                + playerSleepWakeDelay(model.npcId);
    }
    public void markStayedInTownHallTonight() { stayedInTownHallTonight = true; }

    /**
     * Eligible for a coordinator-initiated conversation right now?
     *
     * <ul>
     *   <li>During {@link SocializeActivity} (within {@link LeisureActivity}):
     *       open with no daily action limit — the cooldown still applies.</li>
     *   <li>During {@link WorkActivity} movement phase: open with normal daily limits.</li>
     *   <li>All other activities: closed.</li>
     * </ul>
     */
    public boolean isOpenToConversation() {
        if (conversationCooldown > 0) return false;
        // Being tended by a cleric: it is already talking to someone.
        if (careHoldActive()) return false;

        // Dedicated socialising: bypass the daily action counter
        if (current instanceof LeisureActivity l
                && l.currentSub() instanceof SocializeActivity s
                && s.isHangingAround()) {
            return true;
        }

        // Walking to work: normal daily limits apply. Not on an errand that
        // cannot wait, such as a cleric on its way to someone hurt.
        if (current instanceof WorkActivity w && w.isMovingPhase()) {
            return w.allowsChatWhileWalking() && canStartSocialAction();
        }

        // Settled in at home, the tavern or a neighbour's for the evening:
        // open like socialising, but not so close to leaving time that the
        // conversation would still be running when it has to go.
        if (current instanceof EveningActivity e && e.isHangingAround()) {
            NpcEntity npc = model.puppet();
            long last = e.mode() == EveningActivity.Mode.VISIT
                    ? VisitRules.GUEST_LAST_CHAT_TICKS : EVENING_LAST_CONVERSATION_TICKS;
            return npc != null && ticksUntilEveningMove(npc, e) > last;
        }

        return false;
    }

    /** No new evening conversation starts this close to the villager's leaving time. */
    private static final int EVENING_LAST_CONVERSATION_TICKS = 300;

    /** Inject a conversation; takes effect on the next brain tick. */
    public void forceActivity(Activity activity) { forced = activity; }

    /**
     * Called immediately after a profession token assigns a job. This gives the
     * new job one chance to preempt leisure/social state without bypassing
     * survival and night-time overrides.
     */
    public boolean tryStartWorkImmediately(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        if (npc == null || !npc.isAlive() || profession() == Profession.NONE) return false;

        Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
        boolean shouldWorkNow = VillagerRegistry.shouldWorkNow(profession(), ctx);
        boolean guardWakingFromSleep = isDefender()
                && shouldWorkNow
                && current instanceof GoHomeActivity;
        if (!shouldWorkNow
                || current instanceof WorkActivity
                || (current instanceof GoHomeActivity && !guardWakingFromSleep)
                || current instanceof EatActivity
                || current instanceof FetchFromStorageActivity
                || current instanceof FleeActivity
                || (!guardWakingFromSleep && needs.hungerRules().isLow(needs))) {
            return false;
        }

        if (!VillagerRegistry.hasImmediateWork(profession(), ctx)) return false;

        WorkActivity work = WorkActivity.create(profession(), ctx);
        if (work == null) return false;

        workBackoff = 0;
        decisionCooldown = 0;
        forced = null;
        restoredActivityName = "";
        diary.log(VillagerDiary.ts(level) + " JOB_ASSIGNED -> immediate work check accepted");
        switchTo(work, ctx);
        return true;
    }

    /**
     * Apply a Lumberjack settings change to live runtime state immediately.
     * A disabled current job is cancelled owner-safely; when work remains
     * enabled, leisure may be preempted for another valid tree.
     */
    public void onLumberjackSettingsChanged(
            VillageManager mgr,
            ServerLevel level,
            NpcEntity npc,
            boolean workEnabled,
            ResourceLocation disabledTreeType) {
        if (npc == null || !npc.isAlive() || profession() != Profession.LUMBERJACK) return;

        TreeStructure claimedTree = mgr.getClaimedTreeJob(model.npcId);
        boolean disabledCurrentTree = disabledTreeType != null
                && claimedTree != null
                && disabledTreeType.equals(claimedTree.treeTypeId());
        boolean cancelCurrentWork = !workEnabled || disabledCurrentTree;

        if (cancelCurrentWork) {
            mgr.cancelLumberjackTreeJob(model.npcId);
            if (current instanceof LumberjackActivity) {
                Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
                current.stop(ctx);
                current = null;
                restoredActivityName = "";
                decisionCooldown = 0;
                model.stopMoving();
                saveActivityHint(npc);
            }
        }

        if (workEnabled) {
            tryStartWorkImmediately(mgr, level, npc);
        }
    }

    /** Release the old job's route and claims before starting a different profession. */
    public void onProfessionChanged(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        if (current != null) {
            current.stop(new Activity.Context(this, model, mgr, level, npc));
            current = null;
        }
        // A lumberjack's tree stays reserved across a stop (the walk there, a
        // paused chop); no longer a lumberjack, nothing would ever let it go.
        if (com.alex.test.village.WalkFeel.treeResume) mgr.cancelLumberjackTreeJob(model.npcId);
        model.stopMoving();
        forced = null;
        restoredActivityName = "";
        workBackoff = 0;
        decisionCooldown = 0;
        saveActivityHint(npc);
    }

    /** Revalidates Farmer work immediately after a Farmer-owned setting changes. */
    public void onFarmerSettingsChanged(
            VillageManager mgr, ServerLevel level, NpcEntity npc) {
        if (npc == null || !npc.isAlive() || profession() != Profession.FARMER) return;

        // FarmerActivity.stop is owner-safe: it closes any storage interaction,
        // keeps already-transferred items in the inventory, and releases the
        // manager reservation before another settings snapshot can be used.
        mgr.farmers().release(model.npcId);
        if (current instanceof FarmerActivity) {
            Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
            current.stop(ctx);
            current = null;
            forced = null;
            restoredActivityName = "";
            decisionCooldown = 0;
            model.stopMoving();
            saveActivityHint(npc);
        }

        if (FarmerSettings.of(npc).works()) {
            tryStartWorkImmediately(mgr, level, npc);
        }
    }

    /** Apply Guard behavior setting changes to the live activity immediately. */
    public void onGuardBehaviorSettingsChanged(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        if (npc == null || !npc.isAlive() || !isDefender()) return;

        Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
        boolean shouldWorkNow = VillagerRegistry.shouldWorkNow(profession(), ctx);

        if (current instanceof GoHomeActivity && shouldWorkNow) {
            current.stop(ctx);
            current = null;
            forced = null;
            restoredActivityName = "";
            decisionCooldown = 0;
            model.stopMoving();
            saveActivityHint(npc);
            tryStartWorkImmediately(mgr, level, npc);
        } else if (current instanceof WorkActivity && !shouldWorkNow) {
            current.stop(ctx);
            current = null;
            forced = null;
            restoredActivityName = "";
            decisionCooldown = 0;
            model.stopMoving();
            saveActivityHint(npc);
        }
    }

    /**
     * Apply any generic profession-settings change (see
     * {@code ProfessionSettingsChangeHandler}) to the live activity
     * immediately, instead of waiting for the next decision cycle. Unlike
     * {@link #onGuardBehaviorSettingsChanged}, this carries no
     * profession-specific logic: it just re-evaluates eligibility through
     * {@link VillagerRegistry#shouldWorkNow} and {@link
     * VillagerRegistry#hasImmediateWork} and restarts or stops accordingly,
     * so it is safe to call for any profession with a registered {@link
     * com.alex.test.village.villagers.settings.SettingsSchema}.
     *
     * <p>{@code shouldWorkNow} alone is only the day/night gate -- it stays
     * true all day regardless of a profession's own {@code WORK_ENABLED}
     * toggle (or any other reason {@code hasImmediateWork} says there is
     * nothing to do), so checking it alone here let a villager's WorkActivity
     * keep running (and, once it finished, get resumed right back via
     * {@code restoreActivity}'s identical gap) after the player had just
     * turned work off. Both checks together mirror the exact combination
     * {@code decideNext} scores work eligibility with.
     */
    public void onProfessionSettingsChanged(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        if (npc == null || !npc.isAlive()) return;

        // A cook finishes the current animation and batch before applying a
        // changed duty or recipe choice. The next tick reads the new settings.
        if (current instanceof CookActivity cook && cook.isProcessingBatch()) return;
        // Likewise work with something in hand, such as a butcher's animal on
        // the lead: it finishes that first, then reads the new settings itself.
        if (current instanceof WorkActivity work && work.holdsThroughSettingsChange()) return;

        Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
        boolean eligible = VillagerRegistry.shouldWorkNow(profession(), ctx)
                && VillagerRegistry.hasImmediateWork(profession(), ctx);

        if (current instanceof WorkActivity && !eligible) {
            current.stop(ctx);
            current = null;
            forced = null;
            restoredActivityName = "";
            decisionCooldown = 0;
            model.stopMoving();
            saveActivityHint(npc);
        } else if (!(current instanceof WorkActivity) && eligible) {
            tryStartWorkImmediately(mgr, level, npc);
        }
    }

    /**
     * Convert live activity state into a high-level resume hint before the
     * puppet entity is unloaded. Claims and movement are released normally.
     */
    public void onPuppetUnloaded(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        endPauseWithoutCover(npc);
        if (current == null) return;
        String activityName = current.name();
        current.stop(new Activity.Context(this, model, mgr, level, npc));
        current = null;
        forced = null;
        needs.save(npc.getBrainData());
        crowding.save(npc.getBrainData());
        skills.save(npc.getBrainData());
        memory.save(npc.getBrainData());
        saveEvening(npc);
        saveReadingLedger(npc);
        npc.getBrainData().putString(TAG_CURRENT_ACTIVITY, activityName);
        restoredActivityName = activityName;
    }

    /** Pause safely while the owning village has no Town Hall. */
    public void pauseForVillageInactive(VillageManager mgr, ServerLevel level, NpcEntity npc) {
        onPuppetUnloaded(mgr, level, npc);
    }

    public void tick(VillageManager mgr, ServerLevel level) {
        NpcEntity npc = model.puppet();
        if (npc == null || !npc.isAlive()) {
            if (npc != null) endPauseWithoutCover(npc);
            return;
        }

        // Phase laps for the slow-tick line in the finally block: the summary
        // line names the activity, not which part of the brain tick was slow,
        // and a profiler cannot separate one slow second from ten quiet
        // minutes. A few nanoTime calls per brain per tick; nothing else.
        long tickStart = System.nanoTime();
        long loadNs = 0, rolloverNs = 0, needsNs = 0, restoreNs = 0, overridesNs = 0, activityNs = 0, decideNs = 0;
        boolean activityStarted = false;
        String activityName = "none";
        try {
            long lap = tickStart;
            if (!loaded) {
                loadFrom(npc);
                loaded = true;
            }
            loadNs = System.nanoTime() - lap;
            lap += loadNs;

            // Spread the rollover's health, memory and diary work with waking.
            if (level.getGameTime() >= sleepSkipWakeAtGameTime) {
                handleDayRollover(level, mgr, npc);
            }
            rolloverNs = System.nanoTime() - lap;
            lap += rolloverNs;
            StatActivityStatus status = currentStatus();
            // Tonight's sleep gain is rolled on lying down, not on every return to
            // sleep: a villager woken early who lies down again picks up where it
            // left off, and one still asleep past the new day does not roll twice.
            boolean resting = status == StatActivityStatus.SLEEPING;
            if (resting && !restingLastTick && !needs.sleepRolledTonight()) {
                needs.startNightSleep(rollSleepGain(npc), sleptInBedTonight);
            }
            restingLastTick = resting;
            needs.tick(personality, status, sleptInBedTonight);
            tickExertion(level, npc);
            // Sadness slows a villager down, but never while running for its life,
            // sheltering from an alarm, or (a defender) squared up to an enemy.
            // A defender also when it has a mob to fight, is on alert, or the bell
            // has rung: the archer (never "combat ready") used to stroll sadly
            // towards an alarm and walk sadly away from a mob next to it.
            boolean emergency = current instanceof FleeActivity || current instanceof ShelterActivity
                    || (isDefender() && (npc.isCombatReady()
                            || GuardAlertSystem.isOnAlert(npc)
                            || GuardAlertSystem.isVillageAlarmed(mgr, level)
                            || current instanceof GuardActivity guard && guard.isThreatResponseActive()
                            || current instanceof com.alex.test.village.villagers.archer.behavior.ArcherActivity archer
                                    && archer.isThreatResponseActive()
                            || current instanceof com.alex.test.village.villagers.mage.behavior.MageActivity mage && mage.isThreatResponseActive()));
            npc.setSad(needs.wellbeingRules().isSad(needs) && !emergency);
            health.tick(needs, npc, resting);
            tickDefenderSleepHealing(npc);
            crowding.tick(this, mgr, level, npc);
            if (bubbles.tickTimers()) {
                boolean busyTalking = current instanceof ConverseActivity
                        || forced instanceof ConverseActivity;
                bubbles.onState(npc, level, memory,
                        ThoughtState.compute(this, npc, level),
                        needs.wellbeing,
                        busyTalking || npc.isSleeping());
            }
            if (conversationCooldown > 0) conversationCooldown--;
            if (socialCooldown > 0) socialCooldown--;
            if (++saveTimer >= NBT_SAVE_INTERVAL) { saveTimer = 0; saveTo(npc); diary.flush(); }
            needsNs = System.nanoTime() - lap;
            lap += needsNs;

            if (npc.hasPendingProfessionChange()) {
                npc.uiInteraction().tick(npc, model, level, true);
                return;
            }

            if (--skillSyncTimer <= 0) {
                skillSyncTimer = SKILL_SYNC_INTERVAL;
                syncLumberjackSkill(npc);
                syncFarmerSkill(npc);
                syncMinerSkill(npc);
                syncDruidSkill(npc);
                syncCookSkill(npc);
                syncBardSkill(npc);
                syncGuardSkill(npc);
                syncCaptainSkill(npc);
                syncArcherSkill(npc);
                syncMageSkill(npc);
                syncBlacksmithSkill(npc);
                syncEnchanterSkill(npc);
                syncClericSkill(npc);
                syncTeacherSkill(npc);
                syncButcherSkill(npc);
                syncRancherSkill(npc);
                // Only a working guard or captain gets the skill's extra health; it
                // goes when the job does.
                GuardSkillEffects.applyHealth(npc, CombatRank.of(profession()),
                        profession().isGuardLike() ? defenderSkillLevel() : 0);        }

            // Everything above is cheap bookkeeping; everything below (restore,
            // the pose check, overrides, the activity tick, deciding) waits out
            // the per-villager start delay, so brains built on one tick do not
            // all restore and start on it too. The pose check stays below the
            // gate: it stops a sleep pose when there is no GoHomeActivity yet,
            // and restore is what provides one.
            if (startDelayTicks > 0) {
                startDelayTicks--;
                return;
            }

            Activity.Context ctx = new Activity.Context(this, model, mgr, level, npc);
            if (profession().isGuardLike()) GuardAssignmentHandler.ensureStarterSword(npc);

            lap = System.nanoTime();
            if (!restoredActivityName.isEmpty()) {
                Activity restored = restoreActivity(restoredActivityName, ctx);
                restoredActivityName = "";
                if (restored != null) switchTo(restored, ctx);
            }
            restoreNs = System.nanoTime() - lap;

            // The brain outlives its puppet: every chunk reload binds a new entity,
            // saved before the unload woke it, so it can come back lying in bed.
            // Keep that pose only when resuming sleep (GoHomeActivity decides
            // whether to stay in that bed); anything else would carry on lying flat.
            if (npc != poseCheckedPuppet) {
                poseCheckedPuppet = npc;
                if (npc.isSleeping() && !(current instanceof GoHomeActivity)) npc.stopSleeping();
                if (!npc.isSleeping()) npc.setPose(net.minecraft.world.entity.Pose.STANDING);
            }

            if (endLeisureAfterRescue) {
                endLeisureAfterRescue = false;
                // Leisure is what a villager falls back on when every task failed;
                // those failures were the trap, so choose again now.
                if (current instanceof LeisureActivity) {
                    current.stop(ctx);
                    current = null;
                    saveActivityHint(npc);
                }
            }

            // /villager npc_anim: stand still and show the clip; the activity
            // carries on where it was once the preview ends.
            if (npc.isPreviewingAnimation()) {
                model.pauseMovingForUi();
                return;
            }

            if (forced != null) {
                if (current instanceof WorkActivity work
                        && !work.allowsRoutineInterruption()
                        && forced instanceof ConverseActivity) {
                    LOGGER.debug(
                            "[Brain {}] Ignored conversation while an active work job is locked.",
                            shortId());
                    forced = null;
                } else {
                    switchTo(forced, ctx);
                    forced = null;
                }
            }

            lap = System.nanoTime();
            Activity override = checkOverrides(ctx);
            if (override != null) switchTo(override, ctx);
            overridesNs = System.nanoTime() - lap;

            if (pauseStartedAt == Long.MIN_VALUE) animationAtTickStart = npc.getAnimation();
            if (uiInteractionShouldYield(ctx)) {
                npc.uiInteraction().tick(npc, model, level, false);
            } else if (npc.uiInteraction().tick(npc, model, level, canPauseAndFaceForUi(npc))) {
                pausedTick(ctx);
                return;
            }

            // A cleric is healing or talking to this villager: stand still and face it.
            if (tickCareHold(ctx)) {
                pausedTick(ctx);
                return;
            }
            resumeAfterPause(ctx);

            if (workBackoff > 0) workBackoff--;
            if (fetchFailCooldown > 0) fetchFailCooldown--;
            // Growth is time, not behaviour: it must advance whatever activity is
            // running, which is why it lives here and not in AttendSchoolActivity.
            if (profession() == Profession.CHILD) ChildProfession.tickGrowth(ctx);
            if (sleepHungerRetryCooldown > 0) sleepHungerRetryCooldown--;
            if (villageFoodRecheck > 0) villageFoodRecheck--;
            standUpUnlessSeatKept(npc);
            if (current != null) {
                // A sad villager works at WellbeingSystem.SAD_WORK_PACE (0.75): one
                // stationary work tick in four is skipped, so every action takes a
                // third longer, and NpcEntity slows the work clip to match. Walking
                // is slowed by the move control instead. Defenders are exempt here
                // so a sad guard still fights at full speed.
                if (current instanceof WorkActivity work && npc.isSad() && !isDefender()
                        && !work.isMovingPhase() && !model.isMoving()
                        && ++sadWorkTick % 4 == 0) {
                    return;
                }
                activityName = current.name();
                activityStarted = current != lastTickedActivity;
                lastTickedActivity = current;
                lap = System.nanoTime();
                Activity.Status result = current.tickOuter(ctx);
                activityNs = System.nanoTime() - lap;
                if (result == Activity.Status.RUNNING) return;
                if (result == Activity.Status.FAILED && current instanceof WorkActivity) workBackoff = 200;
                if (result == Activity.Status.DONE && current instanceof WorkActivity) {
                    awardWorkExperience(ctx);
                }
                onEveningStepEnded(ctx, current, result);
                if (current instanceof FetchFoodActivity fetch) {
                    if (result == Activity.Status.FAILED) onFoodFetchFailed(ctx, fetch.failure());
                    else if (result == Activity.Status.DONE) foodUnreachableUntil = Long.MIN_VALUE;
                    // An evening food run turned away by a busy table leaves
                    // the bedtime top-up its own try.
                    if (fetch == foodRunTrip && result == Activity.Status.FAILED
                            && fetch.failure() == ChestActivity.Failure.TABLE_BUSY) {
                        bedTopUpTriedAt = Long.MIN_VALUE;
                    }
                }
                if (current == foodRunTrip) foodRunTrip = null;
                if (current instanceof PreTaskHungerActivity
                        && needs.hungerRules().needsSleepPreparation(needs)) {
                    sleepHungerRetryCooldown = needs.hungerRules().sleepRetryCooldownTicks();
                }
                // A sitting in class ended with the child still on its chair: choose
                // the next thing at once. A gap with nothing running stands a seated
                // villager up, and the whole class got up every two minutes.
                if (result == Activity.Status.DONE && current instanceof WorkActivity
                        && current.keepsChairSeat() && npc.isChairSeated()) {
                    decisionCooldown = 0;
                }
                current.stop(ctx);
                current = null;
                saveActivityHint(npc);
                if (uiInteractionShouldYield(ctx)) {
                    npc.uiInteraction().tick(npc, model, level, false);
                } else if (npc.uiInteraction().tick(npc, model, level, canPauseAndFaceForUi(npc))) {
                    pausedTick(ctx);
                    return;
                }
            }

            if (current == null) {
                // An evening step (a chat, a meal) just ended: take the evening's
                // next step at once. Going through the day scoring first would
                // start leisure for a tick, and that stands a seated villager up.
                if (!isDefender() && !clericNightShift(npc) && eveningPhase(ctx) != EveningSchedule.Phase.DAY) {
                    Activity eveningStep = eveningOverride(ctx);
                    if (eveningStep != null) {
                        switchTo(eveningStep, ctx);
                        return;
                    }
                }
                if (decisionCooldown-- > 0) return;
                decisionCooldown = 10 + npc.getRandom().nextInt(20);
                lap = System.nanoTime();
                Activity next = decideNext(ctx); // also logs via decideAndLog
                if (next != null) switchTo(next, ctx);
                decideNs = System.nanoTime() - lap;
            }
        } finally {
            long totalNs = System.nanoTime() - tickStart;
            if (totalNs >= SLOW_TICK_LOG_NANOS) {
                LOGGER.info("[Brain {}] slow tick {} ms: load={} rollover={} needs={} restore={} overrides={} activity={} (started={}, {}) decide={}",
                        shortId(), totalNs / 1_000_000,
                        loadNs / 1_000_000, rolloverNs / 1_000_000, needsNs / 1_000_000,
                        restoreNs / 1_000_000, overridesNs / 1_000_000,
                        activityNs / 1_000_000, activityStarted, activityName,
                        decideNs / 1_000_000);
            }
        }
    }

    // =========================================================================
    // Override system
    // =========================================================================

    /**
     * Guards and archers share one night shift, one alert system and one
     * settings object, so every check below that asks "is this a guard?" is
     * really asking "does this villager defend the village?". Archers were
     * missing from those checks and so were sent home at nightfall -- exactly
     * the shift they exist to work.
     */
    private boolean isDefender() {
        return profession().isDefender();
    }

    /**
     * A defender the bell or an alert has called out. Its food runs wait for
     * the call to end: the call-out override sends it straight back to work,
     * and a mage with empty pockets flipped between the two every tick for
     * the whole alarm. The answer is memoised per tick, so asking is free.
     * The low-hunger fetch override reads the sticky form, calledOutRecently,
     * because this per-tick answer itself flips with the activity.
     */
    private boolean defenderCalledOut(Activity.Context ctx) {
        return isDefender() && GuardAlertSystem.hasEmergencyForGuard(ctx);
    }

    /** Called out (defender emergency or cleric out) within the last CALL_OUT_GRACE_TICKS. */
    private boolean calledOutRecently(long now) {
        return lastCalledOutAt != Long.MIN_VALUE && now - lastCalledOutAt <= CALL_OUT_GRACE_TICKS;
    }

    /**
     * The alert or cleric override is about to replace a food run that the
     * fetch override started only last tick: the two are flipping. Say so at
     * DEBUG, at most once per 100 ticks per villager, with this tick's and
     * last tick's call-out answers, so the cause can be read from the log.
     */
    private void noteCallOutFlip(Activity.Context ctx, boolean emergency, boolean lastTick) {
        if (!(current instanceof FetchFoodActivity)) return;
        long now = ctx.level().getGameTime();
        if (fetchFoodStartedAt != now - 1) return;
        if (callOutFlipLoggedAt != Long.MIN_VALUE && now - callOutFlipLoggedAt < 100) return;
        callOutFlipLoggedAt = now;
        LOGGER.debug("[Brain {}] call-out flip: emergency={} lastTick={}", shortId(), emergency, lastTick);
    }

    /**
     * A cleric with Night Shift on keeps the guards' night hours instead of
     * the evening timetable: works from the shift change to dawn, sleeps by day.
     */
    private boolean clericNightShift(NpcEntity npc) {
        return profession() == Profession.CLERIC
                && com.alex.test.village.villagers.cleric.ClericProfession.worksNightShift(npc);
    }

    private Activity checkOverrides(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        // The field medic: a cleric with Healing in Fights on stays out while
        // guards fight or the bell has rung, instead of running indoors, so
        // long as it has more than half its health (ClericCallOut). The bell or
        // a raid gets it out of bed too; so does a night call for a hurt guard,
        // during which it stays out the same way.
        boolean clericNightCall = current instanceof com.alex.test.village.villagers.cleric.behavior.ClericActivity c
                && c.isNightCall();
        boolean clericOut = profession() == Profession.CLERIC
                && (com.alex.test.village.villagers.cleric.behavior.ClericCallOut.staysOut(ctx,
                        current instanceof FleeActivity || current instanceof ShelterActivity)
                    || clericNightCall
                        && com.alex.test.village.villagers.cleric.behavior.ClericCallOut.braveOnNightCall(npc,
                                ctx.level().getGameTime()));
        // The sticky call-out the fetch override waits on (see lastCalledOutAt),
        // and last tick's answer for the flip diagnostic.
        long now = ctx.level().getGameTime();
        boolean calledOutNow = defenderCalledOut(ctx) || clericOut;
        boolean calledOutBefore = calledOutLastTick;
        calledOutLastTick = calledOutNow;
        if (calledOutNow) lastCalledOutAt = now;

        LivingEntity attacker = npc.getLastHurtByMob();
        if (attacker != null && attacker.isAlive()
                && npc.tickCount - npc.getLastHurtByMobTimestamp() < 60) {
            // Recorded here rather than in FleeActivity alone, because the
            // villagers who see the most of it are the ones who stand and
            // fight. Both paths are safe to hit: VillagerMemory folds repeats
            // inside its episode window into the same episode.
            remember(MemoryTopic.DANGER, ctx.level());
            if (npc.getHealth() < npc.getMaxHealth() * 0.5f) {
                remember(MemoryTopic.INJURY, ctx.level());
            }
            // Any hostile mob, not just a Monster (slimes are not), and whether or
            // not the defender's settings would have it go looking for that kind:
            // a guard that is hit hits back. It used to take the beating -- or,
            // hit by a slime, run -- when the mob was off its list.
            if (isDefender()
                    && com.alex.test.village.villagers.guard.settings.GuardMobPolicy
                            .isHostileCreature(attacker)) {
                if (!attacker.isInWaterOrBubble()) {
                    if (GuardThreatRules.isHostileMonsterForGuard(npc, attacker)) {
                        GuardAlertSystem.reportThreat(ctx, npc, attacker);
                    }
                    if (!(current instanceof WorkActivity)) {
                        WorkActivity guardWork = WorkActivity.create(profession());
                        if (guardWork != null) {
                            diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → guard counter-attack");
                            return guardWork;
                        }
                    }
                } else if (attacker.isInWaterOrBubble() && !(current instanceof FleeActivity)) {
                    if (attacker instanceof net.minecraft.world.entity.Mob mob
                            && mob.getTarget() == npc) {
                        mob.setTarget(null);
                    }
                    diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE -> guard keep distance from water threat");
                    return new FleeActivity(attacker);
                }
            } else if (current instanceof FleeActivity flee) {
                // Hit again on the way or while hiding: re-aim (shelter flee only).
                flee.onAttacked(ctx, attacker);
            } else if (isDefender()) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee (attacked)");
                return new FleeActivity(attacker);
            } else if (clericOut) {
                // Hit but still fit: its work backs it away from the monster.
                if (!(current instanceof com.alex.test.village.villagers.cleric.behavior.ClericActivity)) {
                    WorkActivity medic = WorkActivity.create(profession());
                    if (medic != null) {
                        diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → cleric stays out (attacked, still fit)");
                        return medic;
                    }
                }
            } else {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee to shelter (attacked)");
                return FleeActivity.toShelter(attacker);
            }
        }

        // On call off duty: a badly hurt guard near gets the cleric up from its
        // evening or its bed (ClericCallOut.nightCallReason). Every two seconds.
        if (profession() == Profession.CLERIC && !clericOut
                && !(current instanceof WorkActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof ShelterActivity)
                && !(current instanceof EatActivity)
                && !ctx.model().isEscapingWater()
                && Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(), 40) == 0
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)) {
            String why = com.alex.test.village.villagers.cleric.behavior.ClericCallOut.nightCallReason(ctx);
            if (why != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → cleric night call (" + why + ")");
                return com.alex.test.village.villagers.cleric.behavior.ClericActivity.nightCall();
            }
        }

        if (clericOut) {
            // Out to the fight: from leisure, the evening, a meal walk, or
            // hiding once it is fit again. Not mid-meal, and not out of water.
            if (!(current instanceof com.alex.test.village.villagers.cleric.behavior.ClericActivity)
                    && !(current instanceof EatActivity)
                    && !ctx.model().isEscapingWater()) {
                WorkActivity medic = WorkActivity.create(profession());
                if (medic != null) {
                    noteCallOutFlip(ctx, calledOutNow, calledOutBefore);
                    diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → cleric out to the fight");
                    return medic;
                }
            }
        } else if (!isDefender()) {
            LivingEntity threat = GuardAlertSystem.detectThreatForVillager(ctx);
            // Raiders no bell has been rung for: a shout, and the villagers around hear it.
            boolean unwarned = !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level());
            if (threat != null && unwarned && ctx.mgr().raids().isRaider(threat)) {
                com.alex.test.village.shelter.RaidShout.shout(ctx.mgr(), ctx.level(), npc, threat, 0);
            }
            if (threat != null && !(current instanceof FleeActivity)) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee to shelter + alert guards (monster spotted)");
                return FleeActivity.toShelter(threat);
            }
            // Village claims 11a: a player the owner listed as Hostile close by. Indoors already (asleep in a
            // bed or the hall, or sheltering from the bell): it stays put.
            if (!(current instanceof FleeActivity) && !(current instanceof ShelterActivity)
                    && !(current instanceof GoHomeActivity home && home.isResting() && !home.isRestingOutside())
                    && !ctx.model().isEscapingWater()) {
                net.minecraft.server.level.ServerPlayer hostile =
                        com.alex.test.village.claims.HostileWatch.nearestTo(ctx.mgr(), npc.position());
                if (hostile != null) {
                    diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee to shelter (hostile player "
                            + hostile.getGameProfile().getName() + " near)");
                    return FleeActivity.toShelter(hostile);
                }
            }
            com.alex.test.village.shelter.RaidShout.Heard heard =
                    com.alex.test.village.shelter.RaidShout.takeHeard(npc, ctx.level().getGameTime());
            // Already indoors (asleep in a bed or the hall, or hiding from the bell): it
            // stays put. Asleep outside against a wall it gets up and runs in.
            if (heard != null && unwarned && !(current instanceof FleeActivity)
                    && !(current instanceof ShelterActivity)
                    && !(current instanceof GoHomeActivity home && home.isResting() && !home.isRestingOutside())
                    && ctx.level().getEntity(heard.raiderId()) instanceof LivingEntity raider && raider.isAlive()) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee to shelter (heard a shout: raiders, hop "
                        + heard.hops() + ")");
                com.alex.test.village.shelter.RaidShout.shout(ctx.mgr(), ctx.level(), npc, raider, heard.hops());
                return FleeActivity.toShelter(raider);
            }
            // A monster got into the building this villager is asleep in, or
            // hiding in from the bell: up and out to another one. Once a
            // second, staggered; the look at each building is shared.
            if ((current instanceof ShelterActivity
                    || current instanceof GoHomeActivity resting && resting.isResting())
                    && Math.floorMod(ctx.level().getGameTime() + model.npcId.hashCode(), 20) == 0) {
                Building in = com.alex.test.village.shelter.ShelterWatch.shelterAt(
                        ctx.mgr(), npc.blockPosition(), true);
                LivingEntity inside = in != null
                        ? com.alex.test.village.shelter.ShelterWatch.look(ctx.mgr(), ctx.level(), in).inside()
                        : null;
                if (inside != null) {
                    diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → flee to shelter (monster inside the "
                            + in.getDisplayName() + " at " + in.getDoorPos().toShortString()
                            + ", avoided for 30 s)");
                    return FleeActivity.toShelterFrom(inside, in);
                }
            }
            // The bell has rung: indoors, and stay there until the alarm is over.
            // A villager asleep in a bed or the hall is already indoors; one asleep
            // outside against a wall gets up and goes in; one eating finishes
            // first; one fleeing runs first and shelters after.
            if (GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                    && !ctx.model().isEscapingWater()
                    && !(current instanceof FleeActivity)
                    && !(current instanceof EatActivity)
                    && !(current instanceof WorkActivity work && work.sheltersInPlace())
                    && !(current instanceof GoHomeActivity home && home.isResting() && !home.isRestingOutside())) {
                if (!(current instanceof ShelterActivity)) {
                    diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → shelter (alarm bell"
                            + (current instanceof GoHomeActivity home && home.isRestingOutside()
                                    ? ", woke outside)" : ")"));
                    return new ShelterActivity();
                }
                // Sheltering: only critical hunger gets it out of the routine below.
                if (needs.hungerRules().isCritical(needs) && !npc.isEating()
                        && EatActivity.findFoodSlot(ctx) >= 0) {
                    return new EatActivity();
                }
                return null;
            }
        } else if (GuardAlertSystem.hasEmergencyForGuard(ctx)
                && !(current instanceof WorkActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                // The one food run a call-out lets start (see defenderCalledOut):
                // a starving defender's, which it finishes before going back.
                && !(current instanceof FetchFoodActivity && needs.hungerRules().isCritical(needs))) {
            WorkActivity guardWork = WorkActivity.create(profession());
            if (guardWork != null) {
                noteCallOutFlip(ctx, calledOutNow, calledOutBefore);
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → guard respond to alert");
                return guardWork;
            }
        }

        // The bard's set holds off everything else, but not its meals.
        if (current instanceof com.alex.test.village.villagers.bard.behavior.BardActivity bard
                && bard.canStepOffToEat() && !npc.isEating()) {
            Activity meal = bardMealAtTavern(ctx);
            if (meal != null) return meal;
        }
        // The set also holds off the evening planner below, the only other place that
        // looks at whether guests remain: without this the answer from when the set
        // began held until dawn, and a bard played all night to an empty room.
        if (current instanceof com.alex.test.village.villagers.bard.behavior.BardActivity bard
                && bard.isEveningSet()) {
            refreshBardGuests(ctx);
        }

        if (current instanceof WorkActivity work && !work.allowsRoutineInterruption()) {
            return null;
        }

        // A pair dance is short and ends on its own (PairDance): routine trips
        // and evening steps wait for it. The alarm and fleeing above still win,
        // and so does starving with food to hand.
        if (current instanceof PairDanceActivity) {
            if (needs.hungerRules().isCritical(needs) && !npc.isEating()
                    && EatActivity.findFoodSlot(ctx) >= 0) {
                return new EatActivity();
            }
            return null;
        }

        // Both food overrides below need hunger first, so a fed villager skips
        // the inventory scan that runs here every tick.
        boolean hasFood = (needs.hungerRules().isCritical(needs) || needs.hungerRules().isLow(needs))
                && EatActivity.findFoodSlot(ctx) >= 0;
        if (needs.hungerRules().isCritical(needs)
                && hasFood
                && !npc.isEating()
                && !(current instanceof EatActivity)
                // Not in the middle of running for shelter: once inside it may eat.
                && !(current instanceof FleeActivity flee && flee.isToShelter() && !flee.isHiding())) {
            diary.log(String.format("%s OVERRIDE → eat (CRITICAL hunger=%.1f)",
                    VillagerDiary.ts(ctx.level()), needs.hunger));
            return new EatActivity();
        }

        // Only worth waking for food that actually exists. Without the
        // shouldPrepareHungerBeforeRest guard its two sibling rest overrides
        // below already use, an empty larder turns this into a one-tick
        // oscillation: this override replaces sleep with pre_task_hunger,
        // pre_task_hunger finds nothing and returns DONE the same tick, the
        // night override hands back GoHomeActivity, and round it goes. The
        // villager never reaches its bed, so it pays the full waking hunger
        // drain (4x the sleeping rate) all night and re-runs an A* path every
        // tick -- which is how an entire crew starves to death overnight.
        if (needs.hungerRules().isCritical(needs)
                && current instanceof GoHomeActivity
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof EatActivity)
                && shouldPrepareHungerBeforeRest(ctx)) {
            diary.log(String.format("%s OVERRIDE -> wake_for_food (CRITICAL hunger=%.1f)",
                    VillagerDiary.ts(ctx.level()), needs.hunger));
            return new PreTaskHungerActivity(needs.hungerRules().sleepPreparationFloor());
        }

        // Only send a villager to the larder when there is something in it.
        // The village's own food count is cached and two seconds fresh, and
        // when it says empty this override was still firing the instant its
        // 600-tick cooldown expired -- for days on end, preempting the work
        // that was the only way to refill it. A lumberjack that needs about
        // 1200 ticks to give up on an unreachable tree never got there.
        if (needs.hungerRules().isLow(needs)
                && !hasFood
                && villageHasFoodCached(ctx)
                && fetchFailCooldown == 0
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof GoHomeActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof PreTaskHungerActivity)
                // Called out to a fight, or was within the last 3 s: the
                // larder waits, unless it is starving.
                && (needs.hungerRules().isCritical(needs) || !calledOutRecently(now))) {
            diary.log(String.format("%s OVERRIDE -> fetch_food (hunger=%.1f, no food)",
                    VillagerDiary.ts(ctx.level()), needs.hunger));
            fetchFoodStartedAt = now;
            return new FetchFoodActivity();
        }

        // Civilians follow their own evening timetable rather than the world's
        // night boundary: work stops, deposit, home, the gathering, then bed.
        boolean nightShiftCleric = clericNightShift(npc);
        // Night Shift turned off while it slept by day: that rest only ends at
        // the evening shift change, so it lost the whole day. Up to work now.
        if (profession() == Profession.CLERIC && !nightShiftCleric
                && current instanceof GoHomeActivity dayRest && dayRest.isDaytimeRest()
                && VillagerRegistry.shouldWorkNow(profession(), ctx)) {
            WorkActivity work = WorkActivity.create(profession(), ctx);
            if (work != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → cleric up for the day (night shift off)");
                return work;
            }
        }
        if (nightShiftCleric && !clericOut && !clericNightCall
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !ctx.model().isEscapingWater()
                && !(current instanceof GoHomeActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof ShelterActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof PreTaskHungerActivity)) {
            if (shouldPrepareHungerBeforeRest(ctx)) {
                return sleepHungerPreparation(ctx, "before cleric day rest");
            }
            Activity topUp = bedFoodTopUp(ctx, "before cleric day rest");
            if (topUp != null) return topUp;
            diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → cleric daytime rest (night shift)");
            return GoHomeActivity.untilNight();
        }
        if (!isDefender() && !clericOut && !clericNightCall && !nightShiftCleric) {
            Activity eveningStep = eveningOverride(ctx);
            if (eveningStep != null) return eveningStep;
        }

        // Barracks drill: every guard and captain drills in its own off-duty
        // window -- before the night shift, or after the day shift -- captain
        // or no captain (see BarracksDrills). A sleeping defender is looked at
        // every two seconds, staggered so a village does not all get up at
        // once; one awake -- a day-shift guard just off its post -- at once,
        // so the daytime-rest override below does not start it towards bed first.
        if (profession().isGuardLike()
                && !(current instanceof DrillActivity)
                && (!(current instanceof GoHomeActivity)
                    || Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(), 40) == 0)
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !ctx.model().isEscapingWater()
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof PreTaskHungerActivity)
                && needs.hungerRules().canRoutineWork(needs)) {
            Activity drill = BarracksDrills.drillFor(ctx);
            if (drill != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → " + drill.name());
                return drill;
            }
        }

        // A captain whose own shift has no guards spars with one of the other
        // shift's during their drill instead, on duty and only while all is
        // quiet (see BarracksDrills.inspectionFor). Looked at every two seconds.
        if (profession() == Profession.CAPTAIN
                && current instanceof GuardActivity guard && !guard.isThreatResponseActive()
                && Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(), 40) == 0
                && VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && !GuardAlertSystem.isOnAlert(npc)
                && needs.hungerRules().canRoutineWork(needs)) {
            Activity inspection = BarracksDrills.inspectionFor(ctx);
            if (inspection != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → drill inspection");
                return inspection;
            }
        }

        // Range time: an off-duty archer practises at the Archery Range in the
        // window the guards drill in, by daylight only (see RangePracticeActivity).
        // Looked at like the drill: every two seconds while resting, at once otherwise.
        if (profession() == Profession.ARCHER
                && !(current instanceof RangePracticeActivity)
                && (!(current instanceof GoHomeActivity)
                    || Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(), 40) == 0)
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !ctx.model().isEscapingWater()
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof PreTaskHungerActivity)
                && needs.hungerRules().canRoutineWork(needs)) {
            Activity rangeTime = RangePracticeActivity.offDutyFor(ctx);
            if (rangeTime != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → " + rangeTime.name());
                return rangeTime;
            }
        }

        // A mage's off-duty day, in the same window and on the same terms:
        // study at a Mage Tower or Library, or practice at an Archery Range
        // (see MageOffDutyActivity).
        if (profession() == Profession.MAGE
                && !(current instanceof com.alex.test.village.villagers.mage.behavior.MageOffDutyActivity)
                && (!(current instanceof GoHomeActivity)
                    || Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(), 40) == 0)
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !ctx.model().isEscapingWater()
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof FetchFoodActivity)
                && !(current instanceof PreTaskHungerActivity)
                && needs.hungerRules().canRoutineWork(needs)) {
            Activity offDuty = com.alex.test.village.villagers.mage.behavior.MageOffDutyActivity.offDutyFor(ctx);
            if (offDuty != null) {
                diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → " + offDuty.name());
                return offDuty;
            }
        }

        if (isDefender()
                && !VillagerRegistry.shouldWorkNow(profession(), ctx)
                && !ctx.model().isEscapingWater()
                // During a bell alarm off-duty defenders stay out (hasEmergencyForGuard
                // called them); sending them back to bed here made them flip each tick.
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && !(current instanceof GoHomeActivity)
                && !(current instanceof DrillActivity)
                && !(current instanceof RangePracticeActivity)
                && !(current instanceof com.alex.test.village.villagers.mage.behavior.MageOffDutyActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof FetchFoodActivity)) {
            if (shouldPrepareHungerBeforeRest(ctx)) {
                return sleepHungerPreparation(ctx, "before guard rest");
            }
            Activity topUp = bedFoodTopUp(ctx, "before guard rest");
            if (topUp != null) return topUp;
            diary.log(VillagerDiary.ts(ctx.level()) + " OVERRIDE → guard daytime rest");
            boolean patrolAtNightOnly = GuardSettings.of(npc).patrolAtNightOnly();
            return patrolAtNightOnly ? GoHomeActivity.untilNight() : new GoHomeActivity(true);
        }

        // Food reserve: out of food entirely, drop any routine activity and go
        // restock; down to the last few, cut leisure short for it. Scanning
        // the inventory every tick is not needed, so each villager looks once
        // every FOOD_RESERVE_CHECK_TICKS, staggered by id so a village does
        // not look (and leave) all at once.
        if (Math.floorMod(ctx.level().getGameTime() + ctx.model().npcId.hashCode(),
                    FOOD_RESERVE_CHECK_TICKS) == 0
                && fetchFailCooldown == 0
                && !isNight(ctx)
                && (isDefender() || eveningPhase(ctx) == EveningSchedule.Phase.DAY)
                && !ctx.model().isEscapingWater()
                && !(current instanceof ChestActivity)
                && !(current instanceof GoHomeActivity)
                && !(current instanceof FleeActivity)
                && !(current instanceof EatActivity)
                && !(current instanceof PreTaskHungerActivity)
                && !defenderCalledOut(ctx)) {
            int carried = carriedFoodCount(ctx);
            boolean restock = carried == 0
                    || (lowFoodReserveOverdue(ctx, carried)
                        && current instanceof LeisureActivity);
            if (restock && villageHasFoodCached(ctx) && !allFoodWrittenOff(ctx)) {
                diary.log(String.format("%s OVERRIDE -> fetch_food (reserve %d, hunger=%.1f)",
                        VillagerDiary.ts(ctx.level()), carried, needs.hunger));
                return FetchFoodActivity.topUp();
            }
        }

        // Lowest-priority override: urgent work displaces leisure and nothing
        // else. Leisure runs for thousands of ticks and the scoring loop only
        // runs between activities, so without this a cleric idling in the
        // square never notices a villager bleeding out beside it — decideNext
        // ran exactly once in a whole scenario while a patient sat at 6 HP.
        if (current instanceof LeisureActivity && --urgentWorkRecheck <= 0) {
            urgentWorkRecheck = 40;
            if (VillagerRegistry.shouldWorkNow(profession(), ctx)
                    && VillagerRegistry.hasUrgentWork(profession(), ctx)) {
                WorkActivity urgent = WorkActivity.create(profession());
                if (urgent != null) {
                    diary.log(VillagerDiary.ts(ctx.level())
                            + " OVERRIDE -> urgent work (leisure preempted)");
                    return urgent;
                }
            }
        }
        return null;
    }

    // =========================================================================
    // Evening routine
    // =========================================================================

    private static final long SALT_STYLE      = 0x57A1E;
    private static final long SALT_MEAL_PLACE = 0x3EA1;
    private static final long SALT_MEAL_DELAY = 0x3EA2;
    private static final long SALT_PLAN            = 0x71A4;
    private static final long SALT_PLAN_REROLL     = 0x71A5;
    private static final long SALT_HOMEBODY        = 0x71A6;
    private static final long SALT_HOMEBODY_NIGHTS = 0x71A7;
    /** Eats in the evening below this hunger, once, if it carries food. */
    private static final float EVENING_MEAL_HUNGER = 75f;
    /** Tired enough to skip the rest of the evening. */
    private static final float LEAVE_EARLY_WELLBEING = 15f;
    /** Work is scored as if wellbeing were at least this: 30 x 0.8 drive x 0.7 = 16.8 still beats leisure (10). */
    private static final float SAD_WORK_DRIVE = 30f;
    /** Counts stationary work ticks while sad; every fourth is skipped. */
    private int sadWorkTick;
    private static final float LEAVE_EARLY_HEALTH_FRACTION = 0.4f;
    /** How often the costlier evening facts (inventory, reachability, hosts) are refreshed. */
    private static final int EVENING_FACTS_TICKS = 100;
    /** After a wash fails (cauldron gone, out of reach), how long before the next try. */
    private static final int WASH_RETRY_TICKS = 600;
    private static final long SALT_WASH = 0xA5A1_0001L;
    /** How long a missing home waits before it is looked for again. */
    private static final int HOME_RETRY_TICKS = 400;
    /** Guests a house seats at most; a house without chairs takes this many standing. */
    private static final int HOUSE_MAX_SEATED_GUESTS = 3;
    private static final int HOUSE_MAX_STANDING_GUESTS = 2;
    /** Reachability checks tried per host search, nearest houses first. */
    private static final int HOST_REACH_CHECKS = 3;

    private static final String TAG_EVENING_PLAN     = "EveningPlan";
    private static final String TAG_EVENING_VENUE    = "EveningVenue";
    private static final String TAG_EVENING_ARRIVAL  = "EveningArrival";
    private static final String TAG_EVENING_LEAVE_AT = "EveningLeaveAt";
    private static final String TAG_EVENING_HOSTS    = "EveningHosts";
    private static final String TAG_EVENING_HOST_NOW = "EveningVisitHost";
    private static final String TAG_EVENING_HOMEBODY = "EveningHomebody";
    private static final String TAG_EVENING_HABIT    = "EveningHabit";
    private static final String TAG_EVENING_HABIT_DAY = "EveningHabitDay";

    public EveningSchedule eveningSchedule() { return eveningSchedule; }

    /** This villager's rolls for the night {@code dayTime} falls in. */
    public EveningSchedule.Night tonight(long dayTime) { return eveningSchedule.nightAt(dayTime); }

    /** Tonight's plan, or null before it is rolled. */
    @javax.annotation.Nullable
    public EveningPlanner.Plan eveningPlan() { return evening.plan(); }

    /** One of tonight's early sleepers (only once tonight's plan is known). */
    /** Ticks until this villager leaves tonight's outing for bed; 0 outside the evening. */
    public long eveningTicksUntilLeave(long dayTime) {
        return tonight(dayTime).ticksUntilLeave(dayTime, earlySleeper(dayTime));
    }

    private boolean earlySleeper(long dayTime) {
        return evening.plan() == EveningPlanner.Plan.EARLY
                && evening.day() == EveningSchedule.dayIndex(dayTime);
    }

    /** Where this villager is in its own evening right now. */
    public EveningSchedule.Phase eveningPhase(Activity.Context ctx) {
        long dayTime = ctx.level().getDayTime();
        EveningSchedule.Night night = tonight(dayTime);
        EveningSchedule.Phase phase = night.phaseAt(dayTime, earlySleeper(dayTime));
        if (!bardTavernTonight || bardNightDay != EveningSchedule.dayIndex(dayTime)) return phase;
        // A bard playing the tavern tonight keeps the tavern's hours, not its
        // own: it stops work in time to be there by 12,000, whatever its own
        // end of work, and stays until the last guest has gone.
        long tod = EveningSchedule.timeOfDay(dayTime);
        if ((phase == EveningSchedule.Phase.DAY || phase == EveningSchedule.Phase.WIND_DOWN)
                && tod >= bardDepartAt && tod <= EveningSchedule.WAKE_TIME) {
            return EveningSchedule.Phase.GATHER;
        }
        if (phase == EveningSchedule.Phase.NIGHT && night.bardStillPlaying(dayTime, bardGuestsPresent)) {
            return EveningSchedule.Phase.GATHER;
        }
        return phase;
    }

    /** Tonight's bard plan: decided in the afternoon, so the bard can leave work in time. */
    private boolean bardTavernTonight;
    private long    bardNightDay = Long.MIN_VALUE;
    private long    bardCheckedAt = Long.MIN_VALUE;
    private int     bardDepartAt = EveningSchedule.BARD_ARRIVE_BY;
    private boolean bardGuestsPresent;
    private long    bardGuestsCheckedAt = Long.MIN_VALUE;
    /** From this time of day a bard looks at whether it plays the tavern tonight. */
    private static final int BARD_PLAN_FROM = 10_000;
    /** Houses whose host turned out not to be home tonight; not tried again. Not saved. */
    private final java.util.Set<java.util.UUID> skippedHosts = new java.util.HashSet<>();
    /** The chores step has a food top-up to do; refreshed with the other evening facts. */
    private boolean eveningChoresDue;

    /**
     * A bard decides during the afternoon whether it plays the tavern
     * tonight, and when it must stop work to be there by
     * {@link EveningSchedule#BARD_ARRIVE_BY}. Rechecked every few seconds
     * until it has set off; cheap for everyone else.
     */
    private void prepareBardNight(Activity.Context ctx, long dayTime) {
        if (profession() != Profession.BARD) {
            bardTavernTonight = false;
            return;
        }
        long tod = EveningSchedule.timeOfDay(dayTime);
        long day = EveningSchedule.dayIndex(dayTime);
        if (tod < BARD_PLAN_FROM || tod > EveningSchedule.WAKE_TIME) return;
        if (bardTavernTonight && bardNightDay == day && tod >= bardDepartAt) return;
        long now = ctx.level().getGameTime();
        if (bardNightDay == day && now >= bardCheckedAt && now - bardCheckedAt < EVENING_FACTS_TICKS) return;
        bardCheckedAt = now;
        bardNightDay = day;
        TavernBuilding tavern = bardCanPlayEveningSet(ctx) ? nearestReachableTavern(ctx) : null;
        bardTavernTonight = tavern != null;
        if (tavern != null) {
            eveningTavernId = tavern.getItemFrameId();
            eveningTavernReachable = true;
            bardDepartAt = tonight(dayTime).bardDeparture(
                    Math.sqrt(tavern.getDoorPos().distSqr(ctx.npc().blockPosition())));
        }
    }

    /** A choice fixed for this villager for today; see {@link EveningSchedule#dailyRoll}. */
    int eveningRoll(long salt, int bound) {
        NpcEntity npc = model.puppet();
        long day = npc != null ? EveningSchedule.dayIndex(npc.level().getDayTime()) : 0L;
        return eveningSchedule.dailyRoll(day, salt, bound);
    }

    /** How this villager settles in tonight. More sociable villagers seek company more often. */
    EveningActivity.Style eveningStyle() {
        int roll = eveningRoll(SALT_STYLE, 100);
        int seek = 25 + Math.round((personality.sociability - 0.7f) * 100f); // 25..55
        if (roll < seek) return EveningActivity.Style.SEEK_COMPANY;
        if (roll < seek + 30) return EveningActivity.Style.MINGLE_THEN_SIT;
        return EveningActivity.Style.STAND;
    }

    /**
     * The evening routine's next step for a civilian, or null to leave the
     * current activity alone. Defers the rules to {@link EveningPlanner}.
     */
    private Activity eveningOverride(Activity.Context ctx) {
        long dayTime = ctx.level().getDayTime();
        prepareBardNight(ctx, dayTime);
        EveningSchedule.Phase raw = eveningPhase(ctx);
        // Daytime again: last night's outcome becomes tonight's habit.
        if (raw == EveningSchedule.Phase.DAY && evening.plan() != null) recordHabit();
        if (evening.sync(EveningSchedule.dayIndex(dayTime), raw)) {
            eveningFactsAt = Long.MIN_VALUE;
            eveningVisitHostId = null;
            eveningLibraryId = null;
            skippedHosts.clear();
            bardGuestsPresent = false;
            eveningWash = null;
            washRetryAt = Long.MIN_VALUE;
        }
        if (raw == EveningSchedule.Phase.DAY) {
            // Before this villager's own dawn, or held asleep after a player
            // skipped the night: the old bedtime rule still applies.
            return isNight(ctx) && !(current instanceof GoHomeActivity) ? bedtimeIfFree(ctx) : null;
        }
        if (evening.onStarted()) {
            diary.log(String.format("%s EVENING work over endWork=%d (was %s)",
                    VillagerDiary.ts(ctx.level()), tonight(dayTime).endWork(),
                    current != null ? current.name() : "idle"));
            // The day's dirt by source: what the dirt numbers get tuned from.
            if (ctx.npc().getProfession() == Profession.FARMER) {
                diary.log(VillagerDiary.ts(ctx.level()) + " "
                        + ctx.npc().farmerDirt().endOfShiftSummary(ctx.npc()));
            }
        }

        EveningPlanner.Facts facts = eveningFacts(ctx, raw, dayTime);
        // Rolling the plan can make this villager an early sleeper, which moves its phase.
        if (facts.phase() != eveningPhase(ctx)) facts = eveningFacts(ctx, eveningPhase(ctx), dayTime);
        EveningPlanner.Decision decision = EveningPlanner.decide(facts);
        if (decision == EveningPlanner.Decision.KEEP) {
            Activity foodRun = eveningFoodRun(ctx, facts, dayTime);
            if (foodRun != null) return foodRun;
        }
        return switch (decision) {
            case KEEP -> null;
            case BEDTIME -> {
                if (facts.leaveEarly() && facts.phase() != EveningSchedule.Phase.NIGHT && !evening.leftEarly()) {
                    evening.onLeftEarly();
                    diary.log(String.format("%s EVENING leaving early (%s)",
                            VillagerDiary.ts(ctx.level()), leaveEarlyReason(ctx)));
                }
                // Not enough washes tonight: bed dirty, and a wash first thing tomorrow.
                if (profession() == Profession.FARMER && !evening.washDone() && ctx.npc().getDirt() > 0f
                        && FarmerCauldrons.oweMorningWash(ctx.model().npcId, ctx.level())) {
                    diary.log(String.format("%s WASH none tonight, morning wash owed (dirt %.1f)",
                            VillagerDiary.ts(ctx.level()), ctx.npc().getDirt()));
                }
                yield bedtimeIfFree(ctx);
            }
            case DEPOSIT -> {
                // A water bucket left over from a refill goes into a cauldron
                // that needs it, or is emptied, before anything is deposited.
                Activity stranded = strandedWaterBucket(ctx);
                if (stranded != null) yield stranded;
                evening.onDepositStarted();
                diary.log(VillagerDiary.ts(ctx.level()) + " EVENING end-of-shift deposit");
                yield new DepositActivity();
            }
            case WASH -> washStart(ctx);
            case CHORES -> {
                Activity topUp = bedFoodTopUp(ctx, "evening chores");
                if (topUp == null) advanceStep(ctx, "no chores left");
                yield topUp;
            }
            case EAT -> {
                evening.onMealStarted();
                diary.log(String.format("%s EVENING meal (hunger=%.1f)",
                        VillagerDiary.ts(ctx.level()), needs.hunger));
                yield new EatActivity(true);
            }
            case HOME -> {
                Building home = eveningHome(ctx);
                if (home != null && profession() == Profession.CHILD
                        && com.alex.test.village.villagers.child.behavior.HomeworkActivity.due(ctx)) {
                    yield new com.alex.test.village.villagers.child.behavior.HomeworkActivity(home);
                }
                yield home != null ? eveningStart(ctx, new EveningActivity(EveningActivity.Mode.HOME, home)) : null;
            }
            case TAVERN -> {
                Building tavern = eveningTavernId != null ? ctx.mgr().getBuilding(eveningTavernId) : null;
                yield tavern != null ? eveningStart(ctx, new EveningActivity(EveningActivity.Mode.TAVERN, tavern)) : null;
            }
            case BARD_SET -> {
                Building tavern = eveningTavernId != null ? ctx.mgr().getBuilding(eveningTavernId) : null;
                if (tavern == null) yield null;
                if (!evening.bardSetStarted()) {
                    Activity packFood = bardSetFoodTopUp(ctx);
                    if (packFood != null) yield packFood;
                }
                if (!evening.bardSetStarted()) {
                    evening.onBardSetStarted();
                    saveEvening(ctx.npc());
                }
                diary.log(VillagerDiary.ts(ctx.level()) + " EVENING bard set at the tavern");
                yield com.alex.test.village.villagers.bard.behavior.BardActivity.eveningSet(tavern);
            }
            case VISIT -> {
                Building host = eveningVisitHostId != null ? ctx.mgr().getBuilding(eveningVisitHostId) : null;
                if (host == null) {
                    eveningVisitHostId = null;
                    yield null;
                }
                yield eveningStart(ctx, new EveningActivity(EveningActivity.Mode.VISIT, host));
            }
            case LIBRARY -> {
                com.alex.test.village.building.LibraryBuilding library = plannedLibrary(ctx);
                if (library == null) {
                    evening.onLibraryFailed();
                    yield null;
                }
                if (ctx.npc().isSleeping()) ctx.npc().stopSleeping();
                yield new LibraryActivity(library, LibraryPlan.action(model.npcId, evening.day()));
            }
        };
    }

    private Activity eveningStart(Activity.Context ctx, EveningActivity next) {
        // Leaving a bed for the evening (a backwards time jump, or a reload
        // that restored sleep): stand up before walking anywhere.
        if (ctx.npc().isSleeping()) ctx.npc().stopSleeping();
        return next;
    }

    // =========================================================================
    // A dirty farmer's wash
    // =========================================================================

    /**
     * Tonight's cauldron for a dirty farmer (see {@link FarmerCauldrons#pickWash}),
     * and once per evening the roll for washing before or after the deposit:
     * 50/50, tilted towards the shorter trip, never certain. A farmer with no
     * dirt has nothing to wash off, which settles the wash for the night.
     */
    private void refreshWash(Activity.Context ctx, long dayTime) {
        eveningWash = null;
        if (profession() != Profession.FARMER || evening.washDone()) return;
        if (ctx.npc().getDirt() <= 0f) {
            evening.onWashFinished();
            return;
        }
        if (ctx.level().getGameTime() < washRetryAt || current instanceof WashActivity) return;
        long day = EveningSchedule.dayIndex(dayTime);
        eveningWash = FarmerCauldrons.pickWash(ctx);
        if (eveningWash == null) {
            if (washNoneLoggedDay != day) {
                washNoneLoggedDay = day;
                diary.log(String.format("%s WASH none usable (dirt %.1f)",
                        VillagerDiary.ts(ctx.level()), ctx.npc().getDirt()));
            }
            return;
        }
        if (eveningWashFirstDay != day) {
            eveningWashFirstDay = day;
            double washDist = eveningWash.distance();
            double depositDist = nearestStorageDistance(ctx, washDist);
            double p = 0.5 + WASH_ORDER_TILT * (depositDist - washDist) / Math.max(1.0, depositDist + washDist);
            p = Math.max(WASH_FIRST_MIN, Math.min(WASH_FIRST_MAX, p));
            eveningWashFirst = eveningRoll(SALT_WASH, 100) < Math.round(p * 100);
            diary.log(String.format("%s WASH order %s (wash %.0f, storage %.0f blocks, wash-first %.0f%%)",
                    VillagerDiary.ts(ctx.level()), eveningWashFirst ? "wash then deposit" : "deposit then wash",
                    washDist, depositDist, p * 100));
        }
    }

    /** How far the distance difference tilts the 50/50 wash-or-deposit roll. */
    private static final double WASH_ORDER_TILT = 0.3;
    private static final double WASH_FIRST_MIN = 0.2;
    private static final double WASH_FIRST_MAX = 0.8;

    /** Straight-line distance to the nearest valid storage room's door, or {@code fallback} when there is none. */
    private static double nearestStorageDistance(Activity.Context ctx, double fallback) {
        BlockPos here = ctx.npc().blockPosition();
        double best = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding) || !b.isValid()) continue;
            best = Math.min(best, Math.sqrt(b.getDoorPos().distSqr(here)));
        }
        return best == Double.MAX_VALUE ? fallback : best;
    }

    /** Starts tonight's wash at the cauldron picked with the last evening facts. */
    @javax.annotation.Nullable
    private Activity washStart(Activity.Context ctx) {
        FarmerCauldrons.WashChoice choice = eveningWash;
        if (choice == null) return null;
        if (ctx.npc().isSleeping()) ctx.npc().stopSleeping();
        diary.log(String.format("%s WASH pick %s %s lvl=%d dist=%.0f%s%s",
                VillagerDiary.ts(ctx.level()), choice.where().label, choice.spot().pos().toShortString(),
                choice.level(), choice.distance(),
                choice.refillFirst() ? " (refill first)" : "",
                choice.busy() ? " (in use, will wait)" : ""));
        return WashActivity.forChoice(ctx, choice);
    }

    /**
     * A farmer still holding a water bucket at the end of its shift pours it
     * into a cauldron that needs water, or, when none does, empties it where
     * it stands. Null when there is no such bucket or it was just emptied.
     */
    @javax.annotation.Nullable
    private Activity strandedWaterBucket(Activity.Context ctx) {
        if (profession() != Profession.FARMER || !FarmerCauldrons.carriesWaterBucket(ctx.npc())) return null;
        CauldronIndex.Spot needy = FarmerCauldrons.nextNeedy(ctx, null);
        if (needy != null) {
            return new RefillCauldronActivity(needy.pos(), needy.building().getItemFrameId(),
                    RefillCauldronActivity.Purpose.STRANDED);
        }
        FarmerCauldrons.emptyStrandedBucket(ctx);
        return null;
    }

    /** Whether tonight's end-of-shift deposit is still to come (it takes a borrowed bucket back too). */
    public boolean eveningDepositDue() {
        return evening.depositDue();
    }

    // =========================================================================
    // Tonight's sequence of steps
    // =========================================================================

    /** The step tonight's sequence is on, whatever the time. */
    private EveningPlanner.Step currentStep() {
        EveningPlanner.Plan plan = evening.plan() != null ? evening.plan() : EveningPlanner.Plan.HOME;
        return EveningPlanner.stepAt(plan, evening.sequence(), evening.stage());
    }

    private boolean onLastStep() {
        EveningPlanner.Plan plan = evening.plan() != null ? evening.plan() : EveningPlanner.Plan.HOME;
        return EveningPlanner.isLastStep(plan, evening.sequence(), evening.stage());
    }

    /** On to the next step of tonight's sequence. */
    private void advanceStep(Activity.Context ctx, String why) {
        EveningPlanner.Step was = currentStep();
        evening.advanceStage();
        eveningFactsAt = Long.MIN_VALUE;
        diary.log(String.format("%s EVENING step %s -> %s (%s)",
                VillagerDiary.ts(ctx.level()), was, currentStep(), why));
        saveEvening(ctx.npc());
    }

    public boolean bardPianoPlayed() { return evening.bardPianoPlayed(); }

    public boolean tryStartBardPiano(Activity.Context ctx) {
        if (!evening.tryStartBardPiano()) return false;
        saveEvening(ctx.npc());
        return true;
    }

    /** Whether the stay at this step's tavern or library is used up. */
    private boolean stayUsedUp(long now, EveningSchedule.Night night, EveningPlanner.Step step) {
        if (step == EveningPlanner.Step.TAVERN && eveningTavernId != null) {
            return EveningSchedule.stayUsedUp(now, evening.arrivalAt(eveningTavernId), night.tavernStay());
        }
        if (step == EveningPlanner.Step.LIBRARY && eveningLibraryId != null) {
            return EveningSchedule.stayUsedUp(now, evening.arrivalAt(eveningLibraryId), night.libraryStay());
        }
        return false;
    }

    /** A side-effect-free look at whether the chores step has a food top-up to do. */
    private boolean choresNeeded(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (bedTopUpTriedAt != Long.MIN_VALUE && now - bedTopUpTriedAt < BED_TOP_UP_INTERVAL_TICKS) return false;
        if (fetchFailCooldown > 0) return false;
        if (!needs.hungerRules().isFoodReserveLow(carriedFoodCount(ctx))) return false;
        return villageHasFoodCached(ctx) && !allFoodWrittenOff(ctx);
    }

    /**
     * The facts {@link EveningPlanner} decides on. The cheap ones are read
     * every tick; inventory, reachability, capacity and host searches are
     * refreshed every {@link #EVENING_FACTS_TICKS}, spread over villagers by
     * id, and whenever the phase changes or tonight's plan is not rolled yet.
     */
    private EveningPlanner.Facts eveningFacts(Activity.Context ctx, EveningSchedule.Phase phase, long dayTime) {
        long now = ctx.level().getGameTime();
        boolean stale = eveningFactsAt == Long.MIN_VALUE
                || evening.plan() == null
                || eveningFactsCheckedPhase != phase.ordinal()
                || now - eveningFactsAt >= EVENING_FACTS_TICKS
                || now < eveningFactsAt;
        if (stale) {
            eveningFactsAt = now;
            eveningFactsCheckedPhase = phase.ordinal();
            refreshEveningFacts(ctx, phase, dayTime);
        }

        EveningSchedule.Night night = tonight(dayTime);
        // A tavern or library that is not the night's last venue ends with its stay.
        EveningPlanner.Step raw = currentStep();
        if ((raw == EveningPlanner.Step.TAVERN || raw == EveningPlanner.Step.LIBRARY)
                && !onLastStep() && stayUsedUp(now, night, raw)) {
            advanceStep(ctx, raw + " stay over");
            raw = currentStep();
        }
        // Outings wait for the head-out time; until then the villager is home.
        EveningPlanner.Step step = raw;
        if (EveningPlanner.isOuting(raw) && phase != EveningSchedule.Phase.NIGHT
                && EveningSchedule.timeOfDay(dayTime) < night.headOut()) {
            step = EveningPlanner.Step.HOME;
        }

        // A visit is under way once the villager is on its way to, or at, the
        // chosen host; until then it is only wanted.
        boolean visitActive = false;
        if (eveningVisitHostId != null) {
            boolean heading = current instanceof EveningActivity e && e.mode() == EveningActivity.Mode.VISIT
                    && e.venue().getItemFrameId().equals(eveningVisitHostId);
            boolean there = evening.arrivalAt(eveningVisitHostId) != Long.MIN_VALUE;
            visitActive = (heading || there) && !EveningSchedule.stayUsedUp(now,
                    evening.arrivalAt(eveningVisitHostId), night.visitLength(evening.visitsDone()));
        }

        return new EveningPlanner.Facts(
                phase,
                classifyForEvening(),
                isDefender(),
                ctx.model().isEscapingWater(),
                evening.depositDue() && eveningDepositables,
                step,
                onLastStep(),
                eveningTavernReachable && !evening.tavernFailed() && !evening.tavernFull(),
                eveningLibraryReachable && !evening.libraryFailed() && !evening.libraryFull(),
                bardCanPlayEveningSet(ctx),
                stayUsedUp(now, night, step),
                visitActive,
                eveningVisitHostId != null && !visitActive && evening.visitsLeft(),
                step == EveningPlanner.Step.CHORES && eveningChoresDue,
                shouldLeaveEarly(ctx, phase),
                eveningMealNow(ctx),
                current instanceof ConverseActivity chat && chat.isVisitChat(),
                eveningWash != null && !evening.washDone() && ctx.npc().getDirt() > 0f && now >= washRetryAt,
                eveningWashFirst,
                needs.hungerRules().isCritical(needs));
    }

    private void refreshEveningFacts(Activity.Context ctx, EveningSchedule.Phase phase, long dayTime) {
        // End-of-shift deposit: only for villagers with a job, and only while
        // one is still due. An empty pocket settles it for the evening.
        if (evening.depositDue() && phase != EveningSchedule.Phase.NIGHT) {
            eveningDepositables = profession() != Profession.NONE
                    && DepositActivity.countPotentiallyDepositable(ctx) > 0;
            if (!eveningDepositables) evening.onDepositFinished();
        } else {
            eveningDepositables = false;
        }
        refreshWash(ctx, dayTime);

        if (evening.plan() == null) rollEveningPlan(ctx, dayTime);
        EveningPlanner.Plan plan = evening.plan();

        // The bard on a set night: straight to the tavern, no deposit trip.
        if (plan == EveningPlanner.Plan.BARD) {
            if (evening.depositDue()) evening.onDepositFinished();
            eveningDepositables = false;
            // Once the set has begun, the bard stays with tonight's tavern
            // rather than searching again, and whether guests remain does not
            // depend on how worn out the bard is.
            Building playing = evening.bardSetStarted() && eveningTavernId != null
                    ? ctx.mgr().getBuilding(eveningTavernId) : null;
            TavernBuilding tavern = playing instanceof TavernBuilding t && t.isValid() && !t.getNavMeshBlocks().isEmpty()
                    ? t
                    : bardCanPlayEveningSet(ctx) ? nearestReachableTavern(ctx) : null;
            eveningTavernId = tavern != null ? tavern.getItemFrameId() : eveningTavernId;
            eveningTavernReachable = tavern != null;
            EveningSchedule.Night night = tonight(dayTime);
            bardGuestsPresent = tavern != null
                    && night.phaseAt(dayTime, false) == EveningSchedule.Phase.NIGHT
                    && hasTavernGuests(ctx, tavern);
            return;
        }

        // Chores can be done before the head-out time.
        EveningPlanner.Step step = currentStep();
        eveningChoresDue = step == EveningPlanner.Step.CHORES && choresNeeded(ctx);
        if (step == EveningPlanner.Step.CHORES && !eveningChoresDue && !(current instanceof FetchFoodActivity)) {
            advanceStep(ctx, "no chores needed");
            step = currentStep();
        }
        if (phase == EveningSchedule.Phase.WIND_DOWN) return;

        if (plan == EveningPlanner.Plan.TAVERN && !evening.tavernFailed() && !evening.tavernFull()) {
            boolean there = current instanceof EveningActivity e && e.mode() == EveningActivity.Mode.TAVERN;
            Building known = eveningTavernId != null ? ctx.mgr().getBuilding(eveningTavernId) : null;
            if (!there || known == null || !known.isValid()) {
                TavernBuilding tavern = nearestReachableTavern(ctx);
                eveningTavernId = tavern != null ? tavern.getItemFrameId() : null;
                eveningTavernReachable = tavern != null;
                if (tavern == null) {
                    evening.onTavernFailed();
                    diary.log(VillagerDiary.ts(ctx.level()) + " EVENING tavern unreachable");
                }
            }
        }
        if (plan == EveningPlanner.Plan.LIBRARY && !evening.libraryFailed() && !evening.libraryFull()) {
            refreshLibrary(ctx);
        }
        // A full or unreachable venue becomes one neighbour visit, or home.
        boolean overflow = (step == EveningPlanner.Step.TAVERN && (evening.tavernFailed() || evening.tavernFull()))
                || (step == EveningPlanner.Step.LIBRARY && (evening.libraryFailed() || evening.libraryFull()));
        if (overflow) evening.allowOverflowVisit();

        refreshVisitHost(ctx, phase, step, overflow);
    }

    // =========================================================================
    // Tonight's plan
    // =========================================================================

    private static final long SALT_FAVOURITE = 0xFA70_0001L;
    private static final long SALT_SEQUENCE  = 0x5E90_0001L;
    private static final long SALT_SEQ_AFTER = 0x5E90_0002L;

    /** What a plan roll would give, without side effects: shared by the village's guest lists. */
    record PlanPreview(EveningPlanner.Plan plan, EveningPlanner.Sequence sequence,
                       EveningPlanner.Favourite favourite, boolean rolledHome, String note) {}

    /** This villager's lasting evening favourite: the same every day. */
    EveningPlanner.Favourite eveningFavourite() {
        EveningPlanner.JobLean lean = switch (profession()) {
            case TEACHER, ENCHANTER, CLERIC -> EveningPlanner.JobLean.LIBRARY;
            case FARMER, MINER, LUMBERJACK, BUTCHER -> EveningPlanner.JobLean.TAVERN;
            default -> EveningPlanner.JobLean.NONE;
        };
        return EveningPlanner.favourite(eveningSchedule.lastingRoll(SALT_FAVOURITE, 1000),
                skills.intelligence() / 30.0, (personality.sociability - 0.7) / 0.3, lean);
    }

    /** Last night's habit weight for {@code plan} on {@code day}. */
    int habitFor(long day, EveningPlanner.Plan plan) {
        return evening.habitFor(day, plan);
    }

    /**
     * Tonight's plan as this villager would roll it: the bard's set, an early
     * night, a homebody streak, or a weighted pick among home, tavern,
     * library and visits by what the village has, nudged by sociability, its
     * favourite, last night's habit and the night of the week; then the order
     * of the evening. No side effects, so the village's guest lists can ask
     * every villager ahead of time.
     */
    PlanPreview previewPlan(Activity.Context ctx, long day, boolean hasTavern, boolean hasLibrary,
                            boolean early, EveningPlanner.NightType night) {
        EveningPlanner.Favourite favourite = eveningFavourite();
        if (profession() == Profession.BARD && hasTavern && bardCanPlayEveningSet(ctx)) {
            return new PlanPreview(EveningPlanner.Plan.BARD, EveningPlanner.Sequence.SINGLE, favourite, false, "");
        }
        if (profession() == Profession.CHILD) {
            // Children never go out at night: home, homework, and an early bed.
            return new PlanPreview(EveningPlanner.Plan.EARLY, EveningPlanner.Sequence.SINGLE, favourite, false, " child");
        }
        if (early) {
            return new PlanPreview(EveningPlanner.Plan.EARLY, EveningPlanner.Sequence.SINGLE, favourite, false, "");
        }
        if (evening.homebodyOn(day)) {
            return new PlanPreview(EveningPlanner.Plan.HOME, EveningPlanner.Sequence.SINGLE, favourite, false, " homebody");
        }
        double[] w = planWeights(day, hasTavern, hasLibrary, favourite, night);
        EveningPlanner.Plan plan = EveningPlanner.rollPlan(w, true, true,
                EveningSchedule.sharedFraction(model.npcId, day, SALT_PLAN),
                EveningSchedule.sharedFraction(model.npcId, day, SALT_PLAN_REROLL));
        return new PlanPreview(plan, sequenceFor(plan, day), favourite, plan == EveningPlanner.Plan.HOME, "");
    }

    private double[] planWeights(long day, boolean hasTavern, boolean hasLibrary,
                                 EveningPlanner.Favourite favourite, EveningPlanner.NightType night) {
        double[] w = EveningPlanner.socialWeights(EveningPlanner.weights(hasTavern, hasLibrary),
                (personality.sociability - 0.7f) / 0.3f);
        w = EveningPlanner.favouriteWeights(w, favourite);
        if (evening.habitPlan() != null) {
            w = EveningPlanner.habitWeights(w, evening.habitPlan(), evening.habitFor(day, evening.habitPlan()));
        }
        return EveningPlanner.nightWeights(w, night);
    }

    EveningPlanner.Sequence sequenceFor(EveningPlanner.Plan plan, long day) {
        return EveningPlanner.rollSequence(plan,
                eveningSchedule.dailyRoll(day, SALT_SEQUENCE, 100),
                eveningSchedule.dailyRoll(day, SALT_SEQ_AFTER, 100));
    }

    /**
     * Tonight's plan: the village's guest lists decide who is in the tavern
     * and the library (see {@link NightVenues}); the rest is this villager's
     * own roll. A venue this villager cannot reach from here is rolled
     * again. Logged once in the diary.
     */
    private void rollEveningPlan(Activity.Context ctx, long dayTime) {
        long day = evening.day();
        EveningSchedule.Night night = tonight(dayTime);
        NightVenues.Night venues = NightVenues.forNight(ctx, day);
        PlanPreview mine = previewPlan(ctx, day, venues.hasTavern(), venues.hasLibrary(),
                venues.isEarly(model.npcId), venues.nightType());
        NightVenues.Assigned assigned = venues.assigned(model.npcId);
        EveningPlanner.Plan plan = assigned != null ? assigned.plan() : mine.plan();
        EveningPlanner.Sequence sequence = assigned != null ? assigned.sequence() : mine.sequence();
        String note = mine.note();

        boolean needsTavern = plan == EveningPlanner.Plan.TAVERN;
        boolean needsLibrary = plan == EveningPlanner.Plan.LIBRARY;
        if ((needsTavern && nearestReachableTavern(ctx) == null) || (needsLibrary && nearestReachableLibrary(ctx) == null)) {
            boolean tavernReach = venues.hasTavern() && nearestReachableTavern(ctx) != null;
            boolean libraryReach = venues.hasLibrary() && nearestReachableLibrary(ctx) != null;
            plan = EveningPlanner.rollPlan(planWeights(day, venues.hasTavern(), venues.hasLibrary(),
                            mine.favourite(), venues.nightType()), tavernReach, libraryReach,
                    EveningSchedule.sharedFraction(model.npcId, day, SALT_PLAN),
                    EveningSchedule.sharedFraction(model.npcId, day, SALT_PLAN_REROLL));
            sequence = sequenceFor(plan, day);
            note += " rerolled(unreachable)";
        }
        if (mine.rolledHome() && plan == EveningPlanner.Plan.HOME) {
            int nights = EveningPlanner.homebodyNights(eveningRoll(SALT_HOMEBODY, 100),
                    eveningRoll(SALT_HOMEBODY_NIGHTS, EveningPlanner.HOMEBODY_MAX_NIGHTS));
            if (nights > 0) {
                evening.startHomebodyStreak(day + nights);
                note += " homebody+" + nights;
            }
        }
        boolean refused = assigned != null && assigned.refusedFrom() != null;
        int visits = plan == EveningPlanner.Plan.VISIT ? (refused ? 1 : night.visits())
                : java.util.Arrays.asList(EveningPlanner.steps(plan, sequence)).contains(EveningPlanner.Step.VISIT) ? 1 : 0;
        evening.setPlan(plan, visits);
        evening.setSequence(sequence);

        String stay = switch (plan) {
            case TAVERN  -> Integer.toString(night.tavernStay());
            case LIBRARY -> Integer.toString(night.libraryStay());
            case VISIT   -> visits > 1 ? night.visit1() + "+" + night.visit2() : Integer.toString(night.visit1());
            default -> "0";
        };
        diary.log(String.format("%s EVENING plan=%s seq=%s favourite=%s night=%s headOut=%d stay=%s leave=%d visits=%d%s",
                VillagerDiary.ts(ctx.level()), plan, sequence, mine.favourite(), venues.nightType(),
                night.headOut(), stay, night.leaveFor(plan == EveningPlanner.Plan.EARLY), visits, note));
        if (assigned != null && assigned.refusedFrom() != null) {
            diary.log(String.format("%s EVENING refused from %s -> %s",
                    VillagerDiary.ts(ctx.level()), assigned.refusedFrom(), plan));
        }
        saveEvening(ctx.npc());
    }

    /** Last night's outcome becomes the next night's habit. */
    private void recordHabit() {
        EveningPlanner.Plan plan = evening.plan();
        long next = evening.day() + 1;
        if (plan == EveningPlanner.Plan.TAVERN && evening.tavernFull()) {
            evening.setHabit(EveningPlanner.Plan.TAVERN, -EveningPlanner.HABIT_WEIGHT, next);
        } else if (evening.goodNight() && plan != null
                && plan != EveningPlanner.Plan.EARLY && plan != EveningPlanner.Plan.BARD) {
            evening.setHabit(plan, EveningPlanner.HABIT_WEIGHT, next);
        } else if (evening.habitDay() < next) {
            evening.setHabit(null, 0, next);
        }
    }

    /** Villagers this one has talked with tonight, so a second chat needs no hello. Not saved. */
    private final java.util.Set<java.util.UUID> chatPartnersTonight = new java.util.HashSet<>();
    private long chatPartnersDay = Long.MIN_VALUE;

    /** A chat began with {@code partner}. */
    void onChatWith(java.util.UUID partner) {
        NpcEntity npc = model.puppet();
        if (npc == null) return;
        long day = EveningSchedule.dayIndex(npc.level().getDayTime());
        if (day != chatPartnersDay) {
            chatPartnersTonight.clear();
            chatPartnersDay = day;
        }
        chatPartnersTonight.add(partner);
    }

    /** Whether this villager already talked with {@code partner} today. */
    boolean chattedTodayWith(java.util.UUID partner, long day) {
        return chatPartnersDay == day && chatPartnersTonight.contains(partner);
    }

    /** A chat during the evening: a good night, so tomorrow leans the same way. */
    void onEveningChat() {
        if (evening.plan() != null) evening.onGoodNight();
        if (eveningAtTavern) needs.addEveningBonus(WellbeingSystem.EVENING_BONUS_TAVERN_CHAT);
    }

    /** Heard the bard during the evening: also lifts tonight's sleep refill, once. */
    public void onHeardBard() {
        if (evening.plan() != null && current instanceof EveningActivity) {
            evening.onGoodNight();
            needs.onEveningBard();
        }
    }

    // =========================================================================
    // Arrival, stay and leaving
    // =========================================================================

    /**
     * Settled at a tavern, library or host's house. The first arrival at a
     * venue starts the stay; coming back after an interruption keeps it, so
     * only the remaining time counts.
     */
    void onEveningArrived(Activity.Context ctx, Building venue) {
        long now = ctx.level().getGameTime();
        eveningAtTavern = venue instanceof TavernBuilding;
        if (!evening.onArrived(venue.getItemFrameId(), now)) return;
        if (eveningAtTavern) needs.addEveningBonus(WellbeingSystem.EVENING_BONUS_TAVERN);
        long dayTime = ctx.level().getDayTime();
        EveningSchedule.Night night = tonight(dayTime);
        long tod = EveningSchedule.timeOfDay(dayTime);
        long personalLeave = now + Math.max(0L, night.leave() - tod);
        boolean last = onLastStep();
        String kind;
        long leaveAt;
        if (venue instanceof TavernBuilding) {
            kind = "tavern";
            leaveAt = last ? EveningSchedule.guestLeavesAt(personalLeave, now, night.tavernStay())
                    : now + night.tavernStay();
        } else if (venue instanceof com.alex.test.village.building.LibraryBuilding) {
            kind = "library";
            leaveAt = EveningSchedule.guestLeavesAt(personalLeave, now, night.libraryStay());
        } else {
            kind = "visit " + (evening.visitsDone() + 1) + "/" + Math.max(1, evening.visitsPlanned());
            leaveAt = now + night.visitLength(evening.visitsDone());
        }
        evening.setLeaveAt(leaveAt);
        diary.log(String.format("%s EVENING arrived (%s) leaves=%d",
                VillagerDiary.ts(ctx.level()), kind, tod + (leaveAt - now)));
        saveEvening(ctx.npc());
    }

    /**
     * Round at this house as an evening guest: walking in, settled, or away on
     * a chat or a meal in the middle of the stay. Guests are not counted for
     * overcrowding and do not feel it -- a visit is meant to be busy.
     */
    public boolean isVisitingHouse(java.util.UUID houseId, long gameTime) {
        for (Activity activity : new Activity[] {current, forced}) {
            if (activity instanceof EveningActivity e && e.mode() == EveningActivity.Mode.VISIT
                    && e.venue().getItemFrameId().equals(houseId)) return true;
        }
        return houseId.equals(evening.arrivalVenue()) && evening.leaveAt() != Long.MIN_VALUE
                && gameTime <= evening.leaveAt();
    }

    /** Whether it is time to leave the evening venue this activity is at. */
    boolean eveningVenueOver(Activity.Context ctx, EveningActivity.Mode mode, Building venue) {
        EveningSchedule.Phase phase = eveningPhase(ctx);
        if (phase == EveningSchedule.Phase.DAY) return true;
        long now = ctx.level().getGameTime();
        EveningSchedule.Night night = tonight(ctx.level().getDayTime());
        long arrival = evening.arrivalAt(venue.getItemFrameId());
        return switch (mode) {
            case HOME   -> phase == EveningSchedule.Phase.NIGHT;
            case TAVERN -> {
                if (arrival == Long.MIN_VALUE) {
                    yield phase == EveningSchedule.Phase.NIGHT && (evening.tavernFailed() || evening.tavernFull());
                }
                boolean used = EveningSchedule.stayUsedUp(now, arrival, night.tavernStay());
                yield onLastStep() ? used && phase == EveningSchedule.Phase.NIGHT : used;
            }
            case VISIT  -> EveningSchedule.stayUsedUp(now, arrival, night.visitLength(evening.visitsDone()));
        };
    }

    /** Whether a library visit is over: its stay used up and the personal leaving time passed. */
    boolean libraryVisitOver(Activity.Context ctx, com.alex.test.village.building.LibraryBuilding library) {
        EveningSchedule.Phase phase = eveningPhase(ctx);
        if (phase == EveningSchedule.Phase.DAY) return true;
        if (phase != EveningSchedule.Phase.NIGHT) return false;
        return EveningSchedule.stayUsedUp(ctx.level().getGameTime(),
                evening.arrivalAt(library.getItemFrameId()),
                tonight(ctx.level().getDayTime()).libraryStay());
    }

    /**
     * Ticks before this villager moves on from where it is settled for the
     * evening: head-out time at home for those going out, the actual leaving
     * moment (stay included) at a venue, the end of a visit.
     */
    private long ticksUntilEveningMove(NpcEntity npc, EveningActivity e) {
        long dayTime = npc.level().getDayTime();
        long now = npc.level().getGameTime();
        long tod = EveningSchedule.timeOfDay(dayTime);
        EveningSchedule.Night night = tonight(dayTime);
        long untilLeave = night.leaveFor(earlySleeper(dayTime)) - tod;
        long arrival = evening.arrivalAt(e.venue().getItemFrameId());
        return switch (e.mode()) {
            case HOME -> tod < night.headOut() && goesOutTonight() ? night.headOut() - tod : untilLeave;
            case TAVERN -> {
                if (arrival == Long.MIN_VALUE) yield untilLeave;
                long stayLeft = arrival + night.tavernStay() - now;
                yield onLastStep() ? Math.max(untilLeave, stayLeft) : stayLeft;
            }
            case VISIT -> arrival == Long.MIN_VALUE ? Long.MAX_VALUE
                    : arrival + night.visitLength(evening.visitsDone()) - now;
        };
    }

    /** Whether an outing is still ahead tonight. */
    private boolean goesOutTonight() {
        EveningPlanner.Plan plan = evening.plan();
        if (plan == null || plan == EveningPlanner.Plan.HOME || plan == EveningPlanner.Plan.EARLY) return false;
        if (plan == EveningPlanner.Plan.VISIT) return evening.visitsLeft();
        EveningPlanner.Step[] steps = EveningPlanner.steps(plan, evening.sequence());
        for (int i = evening.stage(); i < steps.length; i++) {
            if (EveningPlanner.isOuting(steps[i]) || steps[i] == EveningPlanner.Step.BARD) return true;
        }
        return false;
    }

    // =========================================================================
    // Library
    // =========================================================================

    /** Tonight's library, when the plan says library. */
    private java.util.UUID eveningLibraryId;
    private boolean eveningLibraryReachable;
    /** Intelligence already paid for reading today; saved, so it cannot pay twice. */
    private final ReadingLedger readingLedger = new ReadingLedger();
    private static final String TAG_READING_DAY = "LibraryReadingDay";
    private static final String TAG_READING_AWARDS = "LibraryReadingAwards";

    /**
     * The library for tonight's plan: the nearest reachable one, and whether
     * it has room. Full means at its cap ({@link LibraryPlan#cap}): a fifth of
     * the village, no more than the room holds before it feels crowded (less
     * anyone inside for another reason), and never more than its usable spots.
     * A full library is written off for the night.
     */
    private void refreshLibrary(Activity.Context ctx) {
        boolean there = current instanceof LibraryActivity
                || (eveningLibraryId != null && evening.arrivalAt(eveningLibraryId) != Long.MIN_VALUE);
        com.alex.test.village.building.LibraryBuilding library = plannedLibrary(ctx);
        if (!there || library == null) {
            library = nearestReachableLibrary(ctx);
            eveningLibraryId = library != null ? library.getItemFrameId() : null;
        }
        eveningLibraryReachable = library != null;
        if (library == null) {
            evening.onLibraryFailed();
            diary.log(VillagerDiary.ts(ctx.level()) + " EVENING library unreachable");
            return;
        }
        if (there) return;

        long day = evening.day();
        int villageSize = 0;
        int visitors = 0;
        // Anyone inside for another reason (the enchanter at work) takes room a reader would have.
        int othersInside = 0;
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            NpcEntity puppet = other.puppet();
            if (puppet == null || !puppet.isAlive()) continue;
            villageSize++;
            if (other == model) continue;
            if (other.brain().currentActivity() instanceof LibraryActivity visit
                    && visit.library().getItemFrameId().equals(library.getItemFrameId())) {
                visitors++;
            } else if (ctx.mgr().crowding().buildingOf(other.npcId) == library) {
                othersInside++;
            }
        }
        LibraryPlan.Limits limits = new LibraryPlan.Limits(villageSize,
                library.getSpace().fits() - othersInside, LibraryActivity.capacity(ctx, library, day));
        int cap = LibraryPlan.cap(limits);
        if (visitors >= cap) {
            evening.onLibraryFull();
            diary.log(String.format("%s EVENING library full (%d/%d)",
                    VillagerDiary.ts(ctx.level()), visitors, cap));
        }
    }

    @javax.annotation.Nullable
    private com.alex.test.village.building.LibraryBuilding nearestReachableLibrary(Activity.Context ctx) {
        BlockPos here = ctx.npc().blockPosition();
        com.alex.test.village.building.LibraryBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof com.alex.test.village.building.LibraryBuilding library)
                    || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            double d = b.getDoorPos().distSqr(here);
            if (d >= bestDist) continue;
            if (!model.canReach(ctx.mgr().getGraph(), b.getDoorPos())) continue;
            best = library;
            bestDist = d;
        }
        return best;
    }

    @javax.annotation.Nullable
    private com.alex.test.village.building.LibraryBuilding plannedLibrary(Activity.Context ctx) {
        if (eveningLibraryId == null) return null;
        return ctx.mgr().getBuilding(eveningLibraryId) instanceof com.alex.test.village.building.LibraryBuilding l
                && l.isValid() ? l : null;
    }

    /** One settled reading tick: pays intelligence through the daily ledger. */
    void onLibraryReadingTick(Activity.Context ctx) {
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        float gain = readingLedger.onReadingTicks(day, 1);
        if (gain <= 0f) return;
        skills.addIntelligence(gain);
        skills.save(ctx.npc().getBrainData());
        saveReadingLedger(ctx.npc());
        if (readingLedger.awards() == ReadingLedger.DAILY_AWARDS) {
            diary.log(String.format("%s LIBRARY daily reading cap reached (+%.2f intelligence)",
                    VillagerDiary.ts(ctx.level()), readingLedger.awardedToday()));
        }
    }

    private void saveReadingLedger(NpcEntity npc) {
        if (readingLedger.day() == Long.MIN_VALUE) return;
        npc.getBrainData().putLong(TAG_READING_DAY, readingLedger.day());
        npc.getBrainData().putInt(TAG_READING_AWARDS, readingLedger.awards());
    }

    // =========================================================================
    // House visits
    // =========================================================================

    /** Salts for the per-visit rolls; the visit number is added so each visit rolls afresh. */
    private static final long SALT_VISIT_PICK    = 0x7151_0100L;
    private static final long SALT_VISIT_WELCOME = 0x7151_0200L;
    private static final long SALT_VISIT_SEE_OUT = 0x7151_0300L;
    private static final long SALT_VISIT_SNACK   = 0x7151_0400L;
    /** Chats had during the current visit, for the diary. */
    private int visitChats;
    /** Last "no host" summary written, so an unchanged one is not repeated. */
    private String lastNoHostSummary = "";

    /**
     * Keeps tonight's visit host current: drops a chosen host that is no
     * longer home before the guest got there, and picks the next house with
     * a host while visits are left and the leaving time has not come. A visit
     * that is one step of a longer evening is skipped when no host is home.
     */
    private void refreshVisitHost(Activity.Context ctx, EveningSchedule.Phase phase,
                                  EveningPlanner.Step step, boolean overflow) {
        boolean visiting = step == EveningPlanner.Step.VISIT || overflow;
        if (!visiting || !evening.visitsLeft()) {
            // Not visiting now: forget a host not yet reached (a visit under way carries on).
            if (eveningVisitHostId != null && evening.arrivalAt(eveningVisitHostId) == Long.MIN_VALUE) {
                eveningVisitHostId = null;
            }
            return;
        }
        if (eveningVisitHostId != null) {
            if (evening.arrivalAt(eveningVisitHostId) != Long.MIN_VALUE) return;
            Building host = ctx.mgr().getBuilding(eveningVisitHostId);
            if (host != null && host.isValid() && hostHome(ctx, host, VisitRules.HOST_MIN_ON_ARRIVAL)) return;
            if (host != null && residentAsleep(ctx, host)) skippedHosts.add(eveningVisitHostId);
            diary.log(VillagerDiary.ts(ctx.level()) + " EVENING visit host no longer home (asleep or leaving)");
            eveningVisitHostId = null;
        }
        if (phase != EveningSchedule.Phase.GATHER) return;
        if (EveningSchedule.timeOfDay(ctx.level().getDayTime()) < tonight(ctx.level().getDayTime()).headOut()) return;
        Building host = findVisitHost(ctx, eveningHome(ctx));
        if (host == null) {
            // One visit on the way to (or after) the tavern: no host, no waiting.
            if (step == EveningPlanner.Step.VISIT && evening.plan() != EveningPlanner.Plan.VISIT) {
                advanceStep(ctx, "no host home, visit skipped");
            }
            return;
        }
        eveningVisitHostId = host.getItemFrameId();
        visitChats = 0;
    }

    /** Some resident of {@code house} is in bed. */
    private static boolean residentAsleep(Activity.Context ctx, Building house) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || !other.isSleeping()) continue;
            Building theirs = ctx.mgr().getAssignedBedBuilding(m.npcId);
            if (theirs != null && theirs.getItemFrameId().equals(house.getItemFrameId())) return true;
        }
        return false;
    }

    /** Whether this villager spends the rest of tonight at home, so it can host. */
    private boolean staysHomeTonight() {
        EveningPlanner.Plan plan = evening.plan();
        if (plan == null || plan == EveningPlanner.Plan.EARLY || plan == EveningPlanner.Plan.BARD) return false;
        if (plan == EveningPlanner.Plan.HOME) return true;
        if (eveningVisitHostId != null) return false;
        EveningPlanner.Step step = currentStep();
        boolean venueGone = (step == EveningPlanner.Step.TAVERN && (evening.tavernFailed() || evening.tavernFull()))
                || (step == EveningPlanner.Step.LIBRARY && (evening.libraryFailed() || evening.libraryFull()));
        if (venueGone || plan == EveningPlanner.Plan.VISIT) return !evening.visitsLeft();
        return !goesOutTonight();
    }

    /** Ticks this villager has before its own bedtime if it is hosting tonight, else 0. */
    long hostTicksLeft(long dayTime) {
        if (!staysHomeTonight() || evening.day() != EveningSchedule.dayIndex(dayTime)) return 0L;
        long left = tonight(dayTime).leaveFor(false) - EveningSchedule.timeOfDay(dayTime);
        return Math.max(0L, left);
    }

    /**
     * Ticks before bed of {@code other} as host of {@code house}, or -1 when
     * it is not hosting there right now: not a resident, asleep, going out
     * tonight, or not in the house. Seated, walking between chairs, chatting
     * or eating in the house all count.
     */
    private static long hostTicks(Activity.Context ctx, VillagerModel other, Building house) {
        NpcEntity puppet = other.puppet();
        if (puppet == null || !puppet.isAlive() || puppet.isSleeping()) return -1L;
        Activity activity = other.brain().currentActivity();
        boolean inHouse = activity instanceof EveningActivity e
                ? e.mode() == EveningActivity.Mode.HOME && e.venue().getItemFrameId().equals(house.getItemFrameId())
                : (activity instanceof ConverseActivity || activity instanceof EatActivity)
                    && house.getNavMeshBlocks().contains(puppet.blockPosition());
        if (!inHouse) return -1L;
        Building theirs = ctx.mgr().getAssignedBedBuilding(other.npcId);
        if (theirs == null || !theirs.getItemFrameId().equals(house.getItemFrameId())) return -1L;
        return other.brain().hostTicksLeft(ctx.level().getDayTime());
    }

    /** Whether {@code house} has an awake resident hosting with at least {@code minTicks} before bed. */
    boolean hostHome(Activity.Context ctx, Building house, long minTicks) {
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == model) continue;
            long left = hostTicks(ctx, other, house);
            if (left > 0 && left >= minTicks) return true;
        }
        return false;
    }

    /** Whether {@code house} still has anyone awake hosting it. */
    boolean hostHome(Activity.Context ctx, Building house) {
        return hostHome(ctx, house, 1L);
    }

    /**
     * A neighbour's house to visit. It needs a host with time for this visit
     * (see {@link VisitRules#hostHasTime}), it is not this villager's own
     * house or one visited (or found empty) tonight, and it has room:
     * {@value #HOUSE_MAX_SEATED_GUESTS} guests at most and no more than its
     * chairs free of residents, or {@value #HOUSE_MAX_STANDING_GUESTS}
     * standing where there are no chairs. Of the nearest reachable few, one
     * with the fewest guests is picked by a per-visit roll, so guests spread
     * out. One pass over the village; chair counts are cached per house.
     */
    @javax.annotation.Nullable
    private Building findVisitHost(Activity.Context ctx, @javax.annotation.Nullable Building home) {
        java.util.Map<java.util.UUID, Building> houses = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, Integer> hosts = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, List<NpcEntity>> hostNpcs = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, Long> bestLeft = new java.util.HashMap<>();
        java.util.Map<java.util.UUID, Integer> guests = new java.util.HashMap<>();
        java.util.Set<java.util.UUID> asleep = new java.util.HashSet<>();
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == model) continue;
            NpcEntity puppet = other.puppet();
            if (puppet == null || !puppet.isAlive()) continue;
            Activity activity = other.brain().currentActivity();
            java.util.UUID heading = other.brain().eveningVisitHostId;
            if (activity instanceof EveningActivity e && e.mode() == EveningActivity.Mode.VISIT) {
                guests.merge(e.venue().getItemFrameId(), 1, Integer::sum);
                continue;
            }
            if (heading != null) guests.merge(heading, 1, Integer::sum);
            Building theirs = ctx.mgr().getAssignedBedBuilding(other.npcId);
            if (!(theirs instanceof com.alex.test.village.building.HouseBuilding) || !theirs.isValid()) continue;
            java.util.UUID id = theirs.getItemFrameId();
            houses.putIfAbsent(id, theirs);
            long left = hostTicks(ctx, other, theirs);
            if (left > 0) {
                hosts.merge(id, 1, Integer::sum);
                hostNpcs.computeIfAbsent(id, k -> new ArrayList<>()).add(puppet);
                bestLeft.merge(id, left, Math::max);
            } else if (puppet.isSleeping()) {
                asleep.add(id);
            }
        }

        BlockPos here = ctx.npc().blockPosition();
        int visitLength = tonight(ctx.level().getDayTime()).visitLength(evening.visitsDone());
        int nHouses = 0, nAsleep = 0, nShort = 0, nOut = 0, nFull = 0, nVisited = 0;
        List<Building> candidates = new ArrayList<>();
        for (Building house : houses.values()) {
            java.util.UUID id = house.getItemFrameId();
            if (home != null && id.equals(home.getItemFrameId())) continue;
            nHouses++;
            if (evening.hostsVisited().contains(id) || skippedHosts.contains(id)) { nVisited++; continue; }
            Long left = bestLeft.get(id);
            if (left == null) {
                if (asleep.contains(id)) nAsleep++; else nOut++;
                continue;
            }
            double blocks = Math.sqrt(house.getDoorPos().distSqr(here));
            if (!VisitRules.hostHasTime(left, blocks, visitLength)) { nShort++; continue; }
            // Guest seats: chairs near a host on its floor, not the whole house.
            // Guests already there or on the way hold their seats, so what is
            // left is the free ones; at most three guests in all, or two
            // standing in a house with no such chairs.
            int[] seats = EveningActivity.guestSeats(ctx, house, hostNpcs.getOrDefault(id, List.of()));
            int guestsHere = guests.getOrDefault(id, 0);
            boolean full = guestsHere >= HOUSE_MAX_SEATED_GUESTS
                    || (seats[0] == 0 ? guestsHere >= HOUSE_MAX_STANDING_GUESTS : seats[1] == 0);
            if (full) { nFull++; continue; }
            candidates.add(house);
        }
        candidates.sort(java.util.Comparator.comparingDouble(b -> b.getDoorPos().distSqr(here)));
        List<Building> reachable = new ArrayList<>();
        int unreachable = 0;
        for (int i = 0; i < candidates.size() && i < HOST_REACH_CHECKS; i++) {
            Building house = candidates.get(i);
            if (model.canReach(ctx.mgr().getGraph(), house.getDoorPos())) reachable.add(house);
            else unreachable++;
        }
        if (reachable.isEmpty()) {
            String summary = String.format(
                    "houses=%d asleep=%d shortTime=%d goingOut=%d full=%d visited=%d unreachable=%d",
                    nHouses, nAsleep, nShort, nOut, nFull, nVisited, unreachable);
            if (!summary.equals(lastNoHostSummary)) {
                lastNoHostSummary = summary;
                diary.log(VillagerDiary.ts(ctx.level()) + " EVENING no visit host: " + summary);
            }
            return null;
        }
        List<Integer> load = new ArrayList<>();
        for (Building b : reachable) load.add(guests.getOrDefault(b.getItemFrameId(), 0));
        int roll = EveningSchedule.sharedRoll(model.npcId, evening.day(),
                SALT_VISIT_PICK + evening.visitsDone(), 1 << 16);
        Building pick = reachable.get(VisitRules.pickHost(load, roll));
        java.util.UUID id = pick.getItemFrameId();
        lastNoHostSummary = "";
        diary.log(String.format("%s EVENING visit %d/%d -> house at %s hostLeft=%d guests=%d chairs=%d from=%d candidates",
                VillagerDiary.ts(ctx.level()), evening.visitsDone() + 1, evening.visitsPlanned(),
                pick.getDoorPos().toShortString(), bestLeft.getOrDefault(id, 0L),
                guests.getOrDefault(id, 0), EveningActivity.guestSeats(ctx, pick, hostNpcs.getOrDefault(id, List.of()))[1], reachable.size()));
        return pick;
    }

    /** How this visit begins; rolled per visit. */
    VisitRules.Welcome visitWelcome() {
        return VisitRules.welcome(EveningSchedule.sharedRoll(model.npcId, evening.day(),
                SALT_VISIT_WELCOME + evening.visitsDone(), 100));
    }

    /** Whether the host sees this visit's guest out; rolled per visit. */
    boolean visitSeeOut() {
        return VisitRules.seeOut(EveningSchedule.sharedRoll(model.npcId, evening.day(),
                SALT_VISIT_SEE_OUT + evening.visitsDone(), 100));
    }

    /** This visit's snack roll, 0-99. */
    int visitSnackRoll() {
        return EveningSchedule.sharedRoll(model.npcId, evening.day(), SALT_VISIT_SNACK + evening.visitsDone(), 100);
    }

    /** A chat of this visit began; counted for the diary. */
    void onVisitChat() { visitChats++; }

    /** Chats so far this visit. */
    int visitChats() { return visitChats; }

    /** The house this villager is visiting (on its way or there), or null. */
    @javax.annotation.Nullable
    java.util.UUID visitHostId() { return eveningVisitHostId; }

    // =========================================================================
    // Evening helpers
    // =========================================================================

    private EveningPlanner.Current classifyForEvening() {
        if (current == null) return EveningPlanner.Current.NONE;
        if (current instanceof EveningActivity e) {
            return switch (e.mode()) {
                case HOME   -> EveningPlanner.Current.HOME;
                case TAVERN -> EveningPlanner.Current.TAVERN;
                case VISIT  -> EveningPlanner.Current.VISIT;
            };
        }
        if (current instanceof com.alex.test.village.villagers.bard.behavior.BardActivity bard
                && bard.isEveningSet()) {
            return EveningPlanner.Current.BARD_SET;
        }
        if (current instanceof WorkActivity work) {
            return work.allowsRoutineInterruption()
                    ? EveningPlanner.Current.WORK : EveningPlanner.Current.WORK_LOCKED;
        }
        if (current instanceof LibraryActivity)        return EveningPlanner.Current.LIBRARY;
        if (current instanceof WashActivity)           return EveningPlanner.Current.WASH;
        if (current instanceof PairDanceActivity)      return EveningPlanner.Current.DANCE;
        if (current instanceof RefillCauldronActivity) return EveningPlanner.Current.WASH;
        if (current instanceof ConverseActivity)       return EveningPlanner.Current.CONVERSE;
        if (current instanceof FleeActivity)           return EveningPlanner.Current.FLEE;
        if (current instanceof EatActivity)            return EveningPlanner.Current.EAT;
        if (current instanceof PreTaskHungerActivity)  return EveningPlanner.Current.EAT;
        if (current instanceof FetchFoodActivity)      return EveningPlanner.Current.FETCH_FOOD;
        if (current instanceof ChestActivity)          return EveningPlanner.Current.CHEST;
        if (current instanceof GoHomeActivity)         return EveningPlanner.Current.SLEEP;
        return EveningPlanner.Current.OTHER;
    }

    /**
     * The bard plays the evening when duty is on. Unhappiness never cancels a
     * set; a sad bard just plays it (see {@code WellbeingSystem.isSad}).
     */
    private boolean bardCanPlayEveningSet(Activity.Context ctx) {
        if (profession() != Profession.BARD || evening.bardSetFailed()
                || (evening.plan() == EveningPlanner.Plan.BARD && currentStep() != EveningPlanner.Step.BARD)) return false;
        return com.alex.test.village.villagers.settings.ProfessionSettings.of(ctx.npc(),
                        com.alex.test.village.villagers.bard.settings.BardSettingsSchema.SCHEMA)
                .getBool(com.alex.test.village.villagers.bard.settings.BardSettingsSchema.WORK_ENABLED);
    }

    /**
     * Tired, hurt, or starving with nothing to eat: a real reason to skip the
     * rest of the evening. Never a random one. Decided once; it sticks.
     */
    private boolean shouldLeaveEarly(Activity.Context ctx, EveningSchedule.Phase phase) {
        if (evening.leftEarly()) return true;
        if (phase == EveningSchedule.Phase.WIND_DOWN) return false;
        return leaveEarlyReason(ctx) != null;
    }

    @javax.annotation.Nullable
    private String leaveEarlyReason(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        // A bard whose set has begun plays on however tired it gets.
        if (needs.wellbeing < LEAVE_EARLY_WELLBEING
                && !(evening.bardSetStarted() && currentStep() == EveningPlanner.Step.BARD)) return "tired";
        if (npc.getHealth() < npc.getMaxHealth() * LEAVE_EARLY_HEALTH_FRACTION) return "injured";
        if (needs.hungerRules().isCritical(needs) && carriedFoodCount(ctx) == 0) return "very hungry";
        return evening.leftEarly() ? "earlier" : null;
    }

    /**
     * Time for this evening's meal: hungry enough, carrying food, settled in
     * for a while (so a group that arrived together does not eat in unison),
     * and at tonight's chosen place -- right after getting home for some,
     * out for others. Anyone at home from head-out on (not going out, or its
     * outing fell through) may eat there.
     */
    private boolean eveningMealNow(Activity.Context ctx) {
        if (evening.mealDone() || needs.hunger >= EVENING_MEAL_HUNGER) return false;
        if (!(current instanceof EveningActivity e) || !e.isHangingAround()) return false;
        int settleDelay = 60 + eveningRoll(SALT_MEAL_DELAY, 400);
        if (e.settledTicks() < settleDelay) return false;
        boolean eatsAtHome = eveningRoll(SALT_MEAL_PLACE, 100) < 50;
        EveningSchedule.Phase phase = eveningPhase(ctx);
        boolean rightPlace = eatsAtHome
                ? e.mode() == EveningActivity.Mode.HOME
                : e.mode() != EveningActivity.Mode.HOME || phase == EveningSchedule.Phase.GATHER;
        if (!rightPlace) return false;
        // The inventory is only looked through once a second, staggered by id.
        if (Math.floorMod(ctx.level().getGameTime() + model.npcId.hashCode(), 20) != 0) return false;
        return EatActivity.findFoodSlot(ctx) >= 0;
    }

    /**
     * Where this villager takes shelter when the alarm bell rings: the nearest
     * roofed building, whatever it is. Open-air pens, mines and merchant
     * stalls do not count, nor a building a monster got into lately
     * (ShelterWatch), and a door a known monster is nearer to than the
     * villager counts as farther off, as the flee to shelter scores it: the
     * villager would meet the monster there. Villagers spread out: a building
 * past what it holds counts as farther off (ShelterCrowd).
     */
    @javax.annotation.Nullable
    Building shelterHome(Activity.Context ctx) {
        net.minecraft.world.phys.Vec3 here = ctx.npc().position();
        long now = ctx.level().getGameTime();
        List<LivingEntity> monsters = GuardAlertSystem.monstersInTown(ctx.mgr(), ctx.level());
        List<Building> ranked = new ArrayList<>();
        Map<Building, Double> scores = new HashMap<>();
        // Spread out: a building already full of villagers hiding counts as farther off.
        Map<Building, Integer> sheltering = ShelterCrowd.sheltering(ctx.mgr(), model);
        for (Building b : ctx.mgr().getBuildings()) {
            if (b instanceof EnclosureBuilding || b instanceof MineBuilding || b instanceof MerchantStallBuilding
                    || b instanceof com.alex.test.village.building.WatchtowerBuilding) continue;
            if (!b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            if (com.alex.test.village.shelter.ShelterWatch.isOverrun(ctx.mgr(), b, now)) continue;
            net.minecraft.world.phys.Vec3 door = net.minecraft.world.phys.Vec3.atCenterOf(b.getDoorPos());
            double dist = here.distanceTo(door);
            double score = dist;
            for (LivingEntity monster : monsters) {
                double mobToDoor = monster.position().distanceTo(door);
                if (mobToDoor < dist) score += (dist - mobToDoor) * SHELTER_BEATEN_PER_BLOCK + SHELTER_BEATEN_FLAT;
            }
            score += ShelterCrowd.penalty(ctx.mgr(), b, sheltering);
            scores.put(b, score);
            ranked.add(b);
        }
        ranked.sort(java.util.Comparator.comparingDouble(scores::get));
        // A monster inside one right now: the look marks it overrun, and the next is taken.
        int looked = 0;
        for (Building b : ranked) {
            if (looked++ >= SHELTER_LOOKS) return b;
            if (com.alex.test.village.shelter.ShelterWatch.look(ctx.mgr(), ctx.level(), b).inside() == null) return b;
        }
        return null;
    }

    /** Score added per block a monster is nearer to a shelter's door than the villager (as DefenderRetreat). */
    private static final double SHELTER_BEATEN_PER_BLOCK = 1.5;
    /** Flat score added to a shelter door a monster would reach first. */
    private static final double SHELTER_BEATEN_FLAT = 6.0;
    /** Buildings looked into for a monster before the next is taken unseen. */
    private static final int SHELTER_LOOKS = 4;

    /**
     * Tonight's home: the house that holds this villager's bed, claiming a
     * free bed now if it has none, else the Town Hall shelter. Resolved once
     * an evening, and looked for again only after a pause when missing.
     */

    @javax.annotation.Nullable
    private Building eveningHome(Activity.Context ctx) {
        long day = evening.day();
        Building cached = eveningHomeId != null ? ctx.mgr().getBuilding(eveningHomeId) : null;
        if (cached != null && cached.isValid() && eveningHomeDay == day) return cached;
        long now = ctx.level().getGameTime();
        if (eveningHomeDay == day && now < eveningHomeRetryAt) return cached != null && cached.isValid() ? cached : null;

        eveningHomeDay = day;
        eveningHomeRetryAt = now + HOME_RETRY_TICKS;
        if (ctx.mgr().getAssignedBed(model.npcId) == null) ctx.mgr().requestBedFor(model);
        Building home = ctx.mgr().getAssignedBedBuilding(model.npcId);
        if (home == null || !home.isValid() || home.getNavMeshBlocks().isEmpty()) {
            home = nearestValid(ctx, TownHallBuilding.class);
        }
        eveningHomeId = home != null ? home.getItemFrameId() : null;
        return home;
    }

    /** Whether anyone besides this villager is spending the evening at {@code tavern}. */
    /**
     * During the bard's set: whether guests remain at its tavern, looked at again
     * every {@link #EVENING_FACTS_TICKS} as the evening facts do. After the bard's own
     * leaving time this alone keeps it playing ({@link #eveningPhase}); once nobody is
     * left, the set ends with the song in progress.
     */
    private void refreshBardGuests(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (bardGuestsCheckedAt != Long.MIN_VALUE && now >= bardGuestsCheckedAt
                && now - bardGuestsCheckedAt < EVENING_FACTS_TICKS) return;
        bardGuestsCheckedAt = now;
        long dayTime = ctx.level().getDayTime();
        Building playing = eveningTavernId != null ? ctx.mgr().getBuilding(eveningTavernId) : null;
        bardGuestsPresent = playing instanceof TavernBuilding tavern
                && tonight(dayTime).phaseAt(dayTime, false) == EveningSchedule.Phase.NIGHT
                && hasTavernGuests(ctx, tavern);
    }

    private boolean hasTavernGuests(Activity.Context ctx, TavernBuilding tavern) {
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == model) continue;
            Activity activity = other.brain().currentActivity();
            if (activity instanceof EveningActivity e && e.mode() == EveningActivity.Mode.TAVERN
                    && e.venue().getItemFrameId().equals(tavern.getItemFrameId())) return true;
            // Anyone in the tavern who is out for the evening counts too: mid-chat,
            // eating at a table, or tavern-goers between steps. Seats are not
            // nav tiles, so the room's box, not its mesh.
            NpcEntity puppet = other.puppet();
            if (puppet == null || puppet.isSleeping() || !tavern.isInRoom(puppet.blockPosition())) continue;
            if (activity instanceof ConverseActivity || activity instanceof EatActivity
                    || other.brain().eveningPlan() == EveningPlanner.Plan.TAVERN) return true;
        }
        return false;
    }

    @javax.annotation.Nullable
    private TavernBuilding nearestReachableTavern(Activity.Context ctx) {
        BlockPos here = ctx.npc().blockPosition();
        TavernBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof TavernBuilding tavern) || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            double d = b.getDoorPos().distSqr(here);
            if (d >= bestDist) continue;
            if (!model.canReach(ctx.mgr().getGraph(), b.getDoorPos())) continue;
            best = tavern;
            bestDist = d;
        }
        return best;
    }

    @javax.annotation.Nullable
    private static Building nearestValid(Activity.Context ctx, Class<? extends Building> type) {
        BlockPos here = ctx.npc().blockPosition();
        Building best = null;
        double bestDist = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!type.isInstance(b) || !b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            double d = b.getDoorPos().distSqr(here);
            if (d < bestDist) { bestDist = d; best = b; }
        }
        return best;
    }

    /** Book-keeping when an evening step finishes on its own (not when replaced). */
    private void onEveningStepEnded(Activity.Context ctx, Activity finished, Activity.Status result) {
        if (isDefender()) return;
        EveningSchedule.Phase phase = eveningPhase(ctx);
        if (phase == EveningSchedule.Phase.DAY) return;
        if (finished instanceof DepositActivity) {
            // Done or not -- storage full, missing or out of reach -- the
            // shift is over; whatever did not fit stays in the pocket.
            evening.onDepositFinished();
        } else if (finished instanceof WashActivity) {
            if (result == Activity.Status.DONE) {
                evening.onWashFinished();
                saveEvening(ctx.npc());
            } else {
                // Cauldron gone, dry or out of reach: look again in a while.
                washRetryAt = ctx.level().getGameTime() + WASH_RETRY_TICKS;
            }
            eveningWash = null;
            eveningFactsAt = Long.MIN_VALUE;
        } else if (finished instanceof FetchFoodActivity && currentStep() == EveningPlanner.Step.CHORES) {
            advanceStep(ctx, "food topped up");
        } else if (finished instanceof EveningActivity e) {
            switch (e.mode()) {
                case TAVERN -> {
                    if (result != Activity.Status.FAILED) break;
                    if (e.wasFull()) {
                        evening.onTavernFull();
                        diary.log(VillagerDiary.ts(ctx.level()) + " EVENING tavern full (no chair, no standing room)");
                    } else {
                        evening.onTavernFailed();
                        diary.log(VillagerDiary.ts(ctx.level()) + " EVENING tavern unreachable");
                    }
                    evening.allowOverflowVisit();
                    eveningFactsAt = Long.MIN_VALUE;
                }
                case VISIT -> {
                    java.util.UUID host = e.venue().getItemFrameId();
                    long arrived = evening.arrivalAt(host);
                    if (result == Activity.Status.DONE && arrived != Long.MIN_VALUE) {
                        evening.onVisitFinished(host);
                        if (e.hostLeft()) {
                            diary.log(String.format("%s EVENING visit %d/%d ended early: host %s after %d ticks (chats=%d)",
                                    VillagerDiary.ts(ctx.level()), evening.visitsDone(), evening.visitsPlanned(),
                                    e.hostLeftReason(), ctx.level().getGameTime() - arrived, visitChats));
                        } else {
                            diary.log(String.format("%s EVENING visit %d/%d over (chats=%d)",
                                    VillagerDiary.ts(ctx.level()), evening.visitsDone(), evening.visitsPlanned(),
                                    visitChats));
                        }
                        visitChats = 0;
                        // The visit was one step of a longer evening: on to the next.
                        if (evening.plan() != EveningPlanner.Plan.VISIT
                                && currentStep() == EveningPlanner.Step.VISIT) {
                            advanceStep(ctx, "visit over");
                        }
                    } else if (result == Activity.Status.FAILED) {
                        // Only a host gone to bed writes the house off for the night.
                        boolean asleep = residentAsleep(ctx, e.venue());
                        if (asleep || !e.noAnswer()) skippedHosts.add(host);
                        diary.log(VillagerDiary.ts(ctx.level()) + (e.noAnswer()
                                ? " EVENING no answer at the door" + (asleep ? " (host asleep)" : "")
                                : e.hostMissing()
                                ? " EVENING visit host not home on arrival"
                                : " EVENING visit host unreachable"));
                    }
                    if (eveningVisitHostId != null && eveningVisitHostId.equals(host)) eveningVisitHostId = null;
                    eveningFactsAt = Long.MIN_VALUE;
                    saveEvening(ctx.npc());
                }
                case HOME -> {
                    if (result == Activity.Status.FAILED) {
                        // Try the home again only after a pause, not every tick.
                        eveningHomeRetryAt = ctx.level().getGameTime() + HOME_RETRY_TICKS;
                        eveningHomeId = null;
                    }
                }
            }
        } else if (finished instanceof LibraryActivity visit) {
            if (result == Activity.Status.FAILED) {
                if (visit.wasFull()) {
                    evening.onLibraryFull();
                    diary.log(VillagerDiary.ts(ctx.level()) + " EVENING library full (no chair, no spot)");
                } else {
                    evening.onLibraryFailed();
                    diary.log(VillagerDiary.ts(ctx.level()) + " EVENING library visit failed");
                }
                evening.allowOverflowVisit();
                eveningFactsAt = Long.MIN_VALUE;
            }
        } else if (finished instanceof com.alex.test.village.villagers.bard.behavior.BardActivity bard
                && bard.isEveningSet() && phase == EveningSchedule.Phase.GATHER
                && currentStep() == EveningPlanner.Step.BARD) {
            evening.onBardSetFailed();
        }
    }

    // =========================================================================
    // Saving the evening
    // =========================================================================

    private void saveEvening(NpcEntity npc) {
        net.minecraft.nbt.CompoundTag data = npc.getBrainData();
        data.putLong(TAG_EVENING_DAY, evening.day());
        data.putInt(TAG_EVENING_FLAGS, evening.packFlags());
        data.putInt(TAG_EVENING_PLAN, evening.packPlan());
        if (evening.arrivalVenue() != null) {
            data.putUUID(TAG_EVENING_VENUE, evening.arrivalVenue());
            data.putLong(TAG_EVENING_ARRIVAL, evening.arrivalTick());
        } else {
            data.remove(TAG_EVENING_VENUE);
            data.remove(TAG_EVENING_ARRIVAL);
        }
        data.putLong(TAG_EVENING_LEAVE_AT, evening.leaveAt());
        StringBuilder hosts = new StringBuilder();
        for (java.util.UUID h : evening.hostsVisited()) {
            if (!hosts.isEmpty()) hosts.append(',');
            hosts.append(h);
        }
        data.putString(TAG_EVENING_HOSTS, hosts.toString());
        if (eveningVisitHostId != null) data.putUUID(TAG_EVENING_HOST_NOW, eveningVisitHostId);
        else data.remove(TAG_EVENING_HOST_NOW);
        data.putLong(TAG_EVENING_HOMEBODY, evening.homebodyUntil());
        data.putInt(TAG_EVENING_HABIT, (evening.habitPlan() == null ? 0 : evening.habitPlan().ordinal() + 1)
                | ((evening.habitDelta() + 128) & 0xFF) << 8);
        data.putLong(TAG_EVENING_HABIT_DAY, evening.habitDay());
    }

    /** Reads what {@link #saveEvening} wrote; anything missing (an older save) keeps its safe default. */
    private void loadEvening(NpcEntity npc) {
        net.minecraft.nbt.CompoundTag data = npc.getBrainData();
        if (!data.contains(TAG_EVENING_DAY)) return;
        evening.unpack(data.getLong(TAG_EVENING_DAY), data.getInt(TAG_EVENING_FLAGS));
        if (data.contains(TAG_EVENING_PLAN)) evening.unpackPlan(data.getInt(TAG_EVENING_PLAN));
        java.util.UUID venue = data.hasUUID(TAG_EVENING_VENUE) ? data.getUUID(TAG_EVENING_VENUE) : null;
        evening.restoreArrival(venue,
                data.contains(TAG_EVENING_ARRIVAL) ? data.getLong(TAG_EVENING_ARRIVAL) : Long.MIN_VALUE,
                data.contains(TAG_EVENING_LEAVE_AT) ? data.getLong(TAG_EVENING_LEAVE_AT) : Long.MIN_VALUE);
        List<java.util.UUID> hosts = new ArrayList<>();
        for (String s : data.getString(TAG_EVENING_HOSTS).split(",")) {
            if (s.isBlank()) continue;
            try { hosts.add(java.util.UUID.fromString(s.trim())); } catch (IllegalArgumentException ignored) { }
        }
        evening.restoreHosts(hosts);
        if (data.hasUUID(TAG_EVENING_HOST_NOW)) eveningVisitHostId = data.getUUID(TAG_EVENING_HOST_NOW);
        if (data.contains(TAG_EVENING_HOMEBODY)) evening.startHomebodyStreak(data.getLong(TAG_EVENING_HOMEBODY));
        if (data.contains(TAG_EVENING_HABIT) && data.contains(TAG_EVENING_HABIT_DAY)) {
            int packed = data.getInt(TAG_EVENING_HABIT);
            int p = packed & 0xFF;
            EveningPlanner.Plan[] plans = EveningPlanner.Plan.values();
            evening.setHabit(p >= 1 && p <= plans.length ? plans[p - 1] : null,
                    ((packed >> 8) & 0xFF) - 128, data.getLong(TAG_EVENING_HABIT_DAY));
        }
        // Which library a guest was at: its arrival venue, if that is one.
        if (evening.plan() == EveningPlanner.Plan.LIBRARY && venue != null) eveningLibraryId = venue;
        if (evening.plan() == EveningPlanner.Plan.TAVERN && venue != null) eveningTavernId = venue;
    }

    /** The old night rule, unchanged: pre-bed meal, food top-up, then bed. */
    @javax.annotation.Nullable
    private Activity bedtimeIfFree(Activity.Context ctx) {
        if (ctx.model().isEscapingWater()
                || current instanceof GoHomeActivity
                || current instanceof FleeActivity
                || current instanceof EatActivity
                || current instanceof FetchFoodActivity) return null;
        if (shouldPrepareHungerBeforeRest(ctx)) {
            return sleepHungerPreparation(ctx, "before sleep");
        }
        Activity topUp = bedFoodTopUp(ctx, "before sleep");
        if (topUp != null) return topUp;
        diary.log(String.format("%s OVERRIDE → go_home (bedtime) | hunger=%.1f wellbeing=%.1f",
                VillagerDiary.ts(ctx.level()), needs.hunger, needs.wellbeing));
        return new GoHomeActivity(true);
    }

    /** Civilian bedtime: past leaving time, left early, or before its own dawn. */
    private boolean civilianBedtime(Activity.Context ctx) {
        EveningSchedule.Phase phase = eveningPhase(ctx);
        return phase == EveningSchedule.Phase.NIGHT
                || (phase != EveningSchedule.Phase.DAY && evening.leftEarly())
                || (phase == EveningSchedule.Phase.DAY && isNight(ctx));
    }

    /**
     * Civilian work hours: day on the villager's own clock and before its
     * end-of-work time. Professions ask this through {@link VillagerRegistry}.
     */
    public static boolean isWorkHours(Activity.Context ctx) {
        return !isNight(ctx) && ctx.brain().eveningPhase(ctx) == EveningSchedule.Phase.DAY;
    }

    // =========================================================================
    // Decision scoring
    // =========================================================================

    private Activity decideNext(Activity.Context ctx) {
        boolean day         = !isNight(ctx);
        int     foodCount   = carriedFoodCount(ctx);
        boolean hasFood     = foodCount > 0;
        double  storageDist = nearestStorageDistanceSqr(ctx);

        float eat = needs.hungerRules().eatScore(needs, hasFood, personality,
                HungerSystem.isMealTime(ctx.level().getDayTime()));

        // Nothing in any storage room (no food, no wheat to bake): picking the
        // trip anyway failed the same tick every 850 ticks while hunger hit 0.
        float fetch = needs.hungerRules().fetchScore(
                needs, hasFood, personality, fetchFailCooldown == 0 && villageHasFoodCached(ctx));
        // The top-up bonus obeys the same cooldown as the base score: a fetch
        // that just failed for lack of food does not become worth retrying by
        // standing next to the empty chest — that exact combination looped a
        // villager on fetch_food dozens of times in a row.
        boolean reserveDue = foodCount == 0 || lowFoodReserveOverdue(ctx, foodCount);
        if (fetchFailCooldown == 0) {
            fetch += needs.hungerRules().nearbyStorageReserveBonus(foodCount, storageDist);
            // A reserve is kept whatever the hunger: waiting until hungry with
            // empty pockets is how villagers ended up starving on the way.
            if (day && reserveDue
                    && villageHasFoodCached(ctx) && !allFoodWrittenOff(ctx)) {
                fetch = Math.max(fetch, needs.hungerRules().foodReserveScore(foodCount));
            }
        }

        // Low wellbeing softens work drive but never stops work: a sad villager
        // (WellbeingSystem.isSad) keeps working, only slower. The drive is
        // scored from at least SAD_WORK_DRIVE so leisure (at most 10) cannot
        // outscore work just because the villager is unhappy.
        float wellbeingFactor = needs.wellbeingRules().workDriveFactor(needs);
        float drive = Math.max(needs.wellbeing, SAD_WORK_DRIVE);
        boolean guard = isDefender();

        // The hunger floor tells a hungry villager to go eat rather than start a
        // shift, which is right only while eating is an option. With nothing
        // carried and nothing in the village's storage, the floor becomes a trap:
        // eat and fetch both score 0 too, so leisure wins by default and the
        // villager idles until it starves. Since the only way food ever comes
        // back is somebody working, a starving villager with nothing to eat is
        // allowed to work anyway.
        boolean noFoodToBeHad = !hasFood && !villageHasFoodCached(ctx);
        boolean emergencyFoodWork = noFoodToBeHad && switch (profession()) {
            case FARMER, RANCHER, BUTCHER, COOK, DRUID -> true;
            default -> false;
        };
        float work = (profession() != Profession.NONE
                && VillagerRegistry.shouldWorkNow(profession(), ctx)
                && workBackoff == 0
                && (needs.hungerRules().canRoutineWork(needs) || noFoodToBeHad)
                && checkImmediateWork(ctx))
                ? (guard ? 120f : emergencyFoodWork
                    ? Math.max(20f, drive * personality.workDrive * wellbeingFactor)
                    : drive * personality.workDrive * wellbeingFactor) : 0f;

        // Leisure stays low while there is work, so a rested villager can use
        // the whole daylight shift rather than stopping halfway through it.
        float leisure = (day && !guard)
                ? 10f * needs.wellbeingRules().leisureNeed(needs) : 0f;

        // With an empty larder, essential producers may work even below the
        // wellbeing floor. Leisure cannot restore the village's food supply.
        if (noFoodToBeHad) leisure = 0f;

        return decideAndLog(ctx, eat, fetch, work, leisure);
    }

    private Activity decideNext(Activity.Context ctx,
                                float eat, float fetch, float work, float leisure) {
        float best = Math.max(leisure, Math.max(Math.max(eat, fetch), work));

        if (best == eat   && eat   > 0) return new EatActivity();
        if (best == fetch && fetch > 0) return new FetchFoodActivity();
        if (best == work  && work  > 0) {
            WorkActivity w = WorkActivity.create(profession(), ctx);
            if (w != null) return w;
        }
        return new LeisureActivity();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * The one place a seated villager gets up. Activities sit down on purpose
     * but never have to remember to stand: whatever ends or interrupts them
     * (a flee, nightfall, a meal finishing) lands here before the next
     * activity can ask the villager to walk.
     */
    private void standUpUnlessSeatKept(NpcEntity npc) {
        if (npc == null || !npc.isChairSeated()) return;
        if (current != null && current.keepsChairSeat()) return;
        npc.standUpFromChair();
    }

    private void switchTo(Activity next, Activity.Context ctx) {
        if (current != null) current.stop(ctx);
        current = next;
        LOGGER.debug("[Brain {}] -> {}", shortId(), next.name());
        NpcEntity npc = ctx.npc();
        standUpUnlessSeatKept(npc);
        if (npc != null) {
            if (next instanceof ConverseActivity) npc.clearUiInteractionLock();
            ThoughtState thoughtNow = ThoughtState.compute(this, npc, ctx.level());
            // What it is doing now, seen only with /villagerstatus on. The name itself
            // shows only to a player looking at the villager, so it is never forced on.
            npc.setActivityLabel(thoughtNow != ThoughtState.OK
                    ? next.name() + " " + thoughtNow.glyph() : next.name());
            npc.setCustomNameVisible(false);
            saveActivityHint(npc);
        }
    }

    /**
     * Restore only safe, high-level intent. Activities rebuild their own targets,
     * claims, and paths; no runtime path or Java object is serialized.
     */
    private Activity restoreActivity(String name, Activity.Context ctx) {
        if ("sleep".equals(name)) {
            if (needs.hungerRules().needsSleepPreparation(needs)) {
                return new PreTaskHungerActivity(needs.hungerRules().sleepPreparationFloor());
            }
            if (isDefender()) {
                return VillagerRegistry.shouldWorkNow(profession(), ctx)
                        ? WorkActivity.create(profession())
                        : (GuardSettings.of(ctx.npc()).patrolAtNightOnly()
                           ? GoHomeActivity.untilNight()
                           : new GoHomeActivity(true));
            }
            return civilianBedtime(ctx) ? new GoHomeActivity(true) : null;
        }
        if ("go_home".equals(name)) return new GoHomeActivity(false);
        if ("pre_task_hunger".equals(name)) {
            return new PreTaskHungerActivity(needs.hungerRules().sleepPreparationFloor());
        }
        if ("eat".equals(name)) return EatActivity.findFoodSlot(ctx) >= 0 ? new EatActivity() : null;
        if ("fetch_food".equals(name)) return new FetchFoodActivity();
        if (("work".equals(name) || "guard".equals(name))
                && VillagerRegistry.shouldWorkNow(profession(), ctx)
                && VillagerRegistry.hasImmediateWork(profession(), ctx)) {
            return WorkActivity.create(profession(), ctx);
        }
        if ("wander".equals(name)) return new WanderActivity();
        if (name.startsWith("leisure")) return new LeisureActivity();
        return null;
    }

    /** Name of the last choice written to the diary; see {@link #decideAndLog}. */
    private String lastLoggedPick = "";

    /** Called from {@link #decideNext} with the computed scores so they go to the diary. */
    private Activity decideAndLog(Activity.Context ctx,
                                  float eat, float fetch, float work, float leisure) {
        Activity chosen = decideNext(ctx, eat, fetch, work, leisure);
        // Leisure is re-decided every few hundred ticks now, and an idle
        // villager would otherwise fill its diary with identical lines. Log the
        // moment it settles into leisure, then stay quiet until it leaves.
        boolean repeatedLeisure = chosen instanceof LeisureActivity
                && lastLoggedPick.startsWith("leisure");
        if (chosen != null) lastLoggedPick = chosen.name();
        if (chosen != null && !repeatedLeisure) {
            diary.log(String.format("%s PICK  %-14s eat=%.1f fetch=%.1f work=%.1f leisure=%.1f | hunger=%.1f wellbeing=%.1f",
                    VillagerDiary.ts(ctx.level()), chosen.name(),
                    eat, fetch, work, leisure,
                    needs.hunger, needs.wellbeing));
        }
        return chosen;
    }

    /**
     * A guard, captain or archer asleep heals at its out-of-fight rate
     * ({@link CombatRank#regenTicks}). The dawn heal only reaches defenders
     * asleep at dawn, and the night shift sleeps by day, so without this a
     * night-shift guard hurt on duty would stay hurt until its next quiet patrol.
     * Not while starving: this heal (every 80 ticks or less) outran starving
     * (every 100), so a hungry defender asleep never lost any health.
     */
    private void tickDefenderSleepHealing(NpcEntity npc) {
        if (!isDefender() || !npc.isSleeping() || npc.getHealth() >= npc.getMaxHealth()) return;
        if (isStarving()) return;
        int every = CombatRank.of(profession()).regenTicks(defenderSkillLevel());
        if (Math.floorMod(npc.tickCount + npc.getId(), every) == 0) npc.heal(1.0F);
    }

    /**
     * The night is over when the village day moves on: at sunrise, on waking,
     * or when the clock is turned back to morning. Not on every turn back of the
     * clock (a command block holding the time would end a night every tick),
     * and still once a day with the clock stopped (see VillageDays).
     */
    private void handleDayRollover(ServerLevel level, VillageManager mgr, NpcEntity npc) {
        long day = EveningSchedule.dayIndex(level.getDayTime());
        boolean livedAway = nightsLivedAway;
        boolean livedAwayInBed = nightsLivedAwayInBed;
        nightsLivedAway = false;
        nightsLivedAwayInBed = false;
        if (rolloverDay != Long.MIN_VALUE && day != rolloverDay) {
            diary.log(String.format("%s NIGHT_END  slept_in_bed=%b nights_away=%b | hunger=%.1f wellbeing=%.1f",
                    VillagerDiary.ts(level), sleptInBedTonight, livedAway,
                    needs.hunger, needs.wellbeing));
            // A defender is healed by dawn only if it slept through to it: a
            // night-shift guard still fighting at dawn used to be made whole
            // mid-fight. Defenders heal in their sleep instead (tickDefenderSleepHealing).
            // Nights lived away healed at their own dawns (AwayHealth): no second heal.
            if (!livedAway && (!isDefender() || npc.isSleeping())) health.onNightEnded(needs, npc);
            if (!sleptInBedTonight && !livedAwayInBed) remember(MemoryTopic.SLEEPLESS, level);
            // Still asleep at the new day: the night counts as full. Woken
            // earlier and never back to sleep: the rest of the gain is lost.
            if (current instanceof GoHomeActivity home && home.isResting()) needs.finishNightSleep();
            needs.clearNightSleep();
            needs.clearEveningBonus();
            eveningAtTavern = false;
            socialActionsToday = 0;
            socialCooldown     = 0;
            workBackoff        = 0;
            fetchFailCooldown  = 0;
            sleepHungerRetryCooldown = 0;
            conversationCooldown = 600 + npc.getRandom().nextInt(1800);
            if (profession().isDefender()) {
                // A full day of practice sets a guard's, captain's, archer's or
                // mage's pace (GuardBalance); this line checks the duty and drill estimates.
                Profession track = profession();
                String drillName = track == Profession.ARCHER || track == Profession.MAGE ? "practice" : "drill";
                int defenderLevel = skills.level(track);
                String pace = "";
                if (defenderLevel < GuardBalance.MAX_LEVEL && defenderXpToday > 0f) {
                    double left = GuardBalance.experienceForLevel(GuardBalance.MAX_LEVEL)
                            - skills.experience(track);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / defenderXpToday);
                }
                diary.log(String.format(
                        "%s %s_DAY  duty=%d min %s=%.1f min hits=%d kills=%d xp=%.1f (full day %d) level=%d%s | %s",
                        VillagerDiary.ts(level), track.name(), defenderDutyMinutesToday,
                        drillName, defenderDrillMinutesToday, defenderHitsToday, defenderKillsToday, defenderXpToday,
                        GuardBalance.FULL_DAY_EXPERIENCE, defenderLevel, pace, skills.describe(track)));
            }
            if (profession() == Profession.COOK) {
                // A full day of batches sets the cook's pace (CookBalance);
                // this line shows whether the day had one and where that leads.
                int cookLevel = cookSkillLevel();
                String pace = "";
                if (cookLevel < CookBalance.MAX_LEVEL && cookXpToday > 0f) {
                    double left = CookBalance.experienceForLevel(CookBalance.MAX_LEVEL)
                            - skills.experience(Profession.COOK);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / cookXpToday);
                }
                diary.log(String.format("%s COOK_DAY  batches=%d praised=%d (full day %d lessons) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), cookBatchesToday, cookPraiseToday, CookBalance.FULL_DAY_BATCHES,
                        cookXpToday, cookLevel, pace, skills.describe(Profession.COOK)));
            }
            if (profession() == Profession.DRUID) {
                // A full day of dances sets the druid's pace (DruidBalance);
                // this line shows whether the day had one and where that leads.
                int druidLevel = druidSkillLevel();
                String pace = "";
                if (druidLevel < DruidBalance.MAX_LEVEL && druidXpToday > 0f) {
                    double left = DruidBalance.experienceForLevel(DruidBalance.MAX_LEVEL)
                            - skills.experience(Profession.DRUID);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / druidXpToday);
                }
                diary.log(String.format("%s DRUID_DAY  dances=%d (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), druidDancesToday, DruidBalance.FULL_DAY_DANCES,
                        druidXpToday, druidLevel, pace, skills.describe(Profession.DRUID)));
            }
            if (profession() == Profession.TEACHER) {
                // A full day of teaching a full class sets the teacher's pace (TeacherBalance).
                diary.log(String.format("%s TEACHER_DAY  units=%.1f (full day %d) xp=%.1f level=%d | %s",
                        VillagerDiary.ts(level), teacherUnitsToday,
                        com.alex.test.village.villagers.teacher.TeacherBalance.FULL_DAY_UNITS,
                        teacherXpToday, teacherSkillLevel(), skills.describe(Profession.TEACHER)));
                teacherUnitsToday = 0f;
                teacherXpToday = 0f;
            }
            if (profession() == Profession.BUTCHER) {
                // A full day of kills sets the butcher's pace (ButcherBalance);
                // a smaller ranch shows here as fewer kills and a longer road.
                int butcherLevel = butcherSkillLevel();
                String pace = "";
                if (butcherLevel < com.alex.test.village.villagers.butcher.ButcherBalance.MAX_LEVEL
                        && butcherXpToday > 0f) {
                    double left = com.alex.test.village.villagers.butcher.ButcherBalance.experienceForLevel(
                            com.alex.test.village.villagers.butcher.ButcherBalance.MAX_LEVEL)
                            - skills.experience(Profession.BUTCHER);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / butcherXpToday);
                }
                diary.log(String.format("%s BUTCHER_DAY  kills=%d strays=%d (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), butcherKillsToday, butcherStraysToday,
                        com.alex.test.village.villagers.butcher.ButcherBalance.FULL_DAY_KILLS,
                        butcherXpToday, butcherLevel, pace, skills.describe(Profession.BUTCHER)));
                butcherKillsToday = 0;
                butcherStraysToday = 0;
                butcherXpToday = 0f;
            }
            if (profession() == Profession.RANCHER) {
                // A full day of jobs sets the rancher's pace (RancherBalance);
                // a smaller ranch shows here as fewer jobs and a longer road.
                int rancherLevel = rancherSkillLevel();
                String pace = "";
                if (rancherLevel < com.alex.test.village.villagers.rancher.RancherBalance.MAX_LEVEL
                        && rancherXpToday > 0f) {
                    double left = com.alex.test.village.villagers.rancher.RancherBalance.experienceForLevel(
                            com.alex.test.village.villagers.rancher.RancherBalance.MAX_LEVEL)
                            - skills.experience(Profession.RANCHER);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / rancherXpToday);
                }
                int[] jobs = rancherJobsToday;
                diary.log(String.format("%s RANCHER_DAY  shear=%d milk=%d egg=%d hide=%d feed=%d stray=%d"
                                + " (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), jobs[RancherJob.SHEAR.ordinal()], jobs[RancherJob.MILK.ordinal()],
                        jobs[RancherJob.EGG.ordinal()], jobs[RancherJob.HIDE.ordinal()],
                        jobs[RancherJob.FEED.ordinal()], jobs[RancherJob.STRAY.ordinal()],
                        com.alex.test.village.villagers.rancher.RancherBalance.FULL_DAY_JOBS,
                        rancherXpToday, rancherLevel, pace, skills.describe(Profession.RANCHER)));
                java.util.Arrays.fill(rancherJobsToday, 0);
                rancherXpToday = 0f;
            }
            if (profession() == Profession.CLERIC) {
                // A full day of care sets the cleric's pace (ClericBalance).
                int clericLevel = clericSkillLevel();
                diary.log(String.format("%s CLERIC_DAY  points=%.1f (full day %d) xp=%.1f level=%d | %s",
                        VillagerDiary.ts(level), clericPointsToday,
                        com.alex.test.village.villagers.cleric.ClericBalance.FULL_DAY_POINTS,
                        clericXpToday, clericLevel, skills.describe(Profession.CLERIC)));
                clericPointsToday = 0f;
                clericXpToday = 0f;
            }
            if (profession() == Profession.BLACKSMITH) {
                // A busy day at the forge sets the blacksmith's pace (BlacksmithBalance);
                // a village that needs less shows here as fewer points and a longer road.
                int smithLevel = blacksmithSkillLevel();
                String pace = "";
                if (smithLevel < BlacksmithBalance.MAX_LEVEL && blacksmithXpToday > 0f) {
                    double left = BlacksmithBalance.experienceForLevel(BlacksmithBalance.MAX_LEVEL)
                            - skills.experience(Profession.BLACKSMITH);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / blacksmithXpToday);
                }
                diary.log(String.format("%s BLACKSMITH_DAY  items=%d points=%.1f (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), blacksmithItemsToday, blacksmithPointsToday,
                        BlacksmithBalance.FULL_DAY_POINTS, blacksmithXpToday, smithLevel, pace,
                        skills.describe(Profession.BLACKSMITH)));
                blacksmithItemsToday = 0;
                blacksmithPointsToday = 0f;
                blacksmithXpToday = 0f;
            }
            if (profession() == Profession.MINER) {
                // A full day of swinging sets the miner's pace (MinerBalance);
                // this line shows whether the day had one and where that leads.
                int minerLevel = minerSkillLevel();
                String pace = "";
                if (minerLevel < MinerBalance.MAX_LEVEL && minerXpToday > 0f) {
                    double left = MinerBalance.experienceForLevel(MinerBalance.MAX_LEVEL)
                            - skills.experience(Profession.MINER);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / minerXpToday);
                }
                diary.log(String.format("%s MINER_DAY  mining_ticks=%d (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), minerMiningTicksToday, MinerBalance.FULL_DAY_MINING_TICKS,
                        minerXpToday, minerLevel, pace, skills.describe(Profession.MINER)));
            }
            if (profession() == Profession.FARMER) {
                // A full day of jobs sets the farmer's pace (FarmerBalance);
                // this line shows whether the day had one and where that leads.
                int farmerLevel = farmerSkillLevel();
                String pace = "";
                if (farmerLevel < FarmerBalance.MAX_LEVEL && farmerXpToday > 0f) {
                    double left = FarmerBalance.experienceForLevel(FarmerBalance.MAX_LEVEL)
                            - skills.experience(Profession.FARMER);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / farmerXpToday);
                }
                diary.log(String.format("%s FARMER_DAY  jobs=%d (full day %d) xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), farmerJobsToday, FarmerBalance.FULL_DAY_JOBS,
                        farmerXpToday, farmerLevel, pace, skills.describe(Profession.FARMER)));
            }
            if (profession() == Profession.LUMBERJACK) {
                // A full day of trees sets the lumberjack's pace (LumberjackBalance);
                // this line shows whether the day had one and where that leads.
                int lumberjackLevel = lumberjackSkillLevel();
                String pace = "";
                if (lumberjackLevel < LumberjackBalance.MAX_LEVEL && lumberjackXpToday > 0f) {
                    double left = LumberjackBalance.experienceForLevel(LumberjackBalance.MAX_LEVEL)
                            - skills.experience(Profession.LUMBERJACK);
                    pace = String.format(" | level 100 in ~%.0f days at this pace", left / lumberjackXpToday);
                }
                diary.log(String.format("%s LUMBERJACK_DAY  trees=%d (full day %d) logs=%d xp=%.1f level=%d%s | %s",
                        VillagerDiary.ts(level), lumberjackTreesToday, LumberjackBalance.FULL_DAY_TREES,
                        lumberjackLogsToday, lumberjackXpToday, lumberjackLevel, pace,
                        skills.describe(Profession.LUMBERJACK)));
            }
            lumberjackTreesToday = 0;
            lumberjackLogsToday  = 0;
            lumberjackXpToday    = 0f;
            farmerJobsToday = 0;
            farmerXpToday   = 0f;
            minerMiningTicksToday = 0;
            minerXpToday          = 0f;
            druidDancesToday = 0;
            druidXpToday     = 0f;
            cookBatchesToday = 0;
            cookPraiseToday = 0;
            cookXpToday = 0f;
            defenderDutyMinutesToday = 0;
            defenderDrillMinutesToday = 0f;
            defenderHitsToday = 0;
            defenderKillsToday = 0;
            defenderXpToday = 0f;
            diary.log(String.format("%s DAY_START             hunger=%.1f wellbeing=%.1f endWork=%d",
                    VillagerDiary.ts(level),
                    needs.hunger, needs.wellbeing, tonight(level.getDayTime()).endWork()));
            // No flush here: every villager rolls over on the same tick, and
            // one OS write each made that tick slow. The phased periodic save
            // in tick() flushes the diary within 5 s anyway.
            sleptInBedTonight        = false;
            stayedInTownHallTonight  = false;
            LOGGER.debug("[Brain {}] New day (sleptInBed={}).", shortId(), sleptInBedTonight);
        }
        rolloverDay = day;
    }

    private StatActivityStatus currentStatus() {
        if (current instanceof GoHomeActivity home && home.isResting())
            return StatActivityStatus.SLEEPING;
        if (current instanceof LibraryActivity visit)
            return visit.isSettled() ? StatActivityStatus.ADMIRING : StatActivityStatus.IDLE;
        if (current instanceof EveningActivity e) {
            if (!e.isHangingAround()) return StatActivityStatus.IDLE;
            return e.mode() == EveningActivity.Mode.TAVERN
                    ? StatActivityStatus.AT_TAVERN : StatActivityStatus.SOCIALIZING;
        }
        if (current instanceof WorkActivity)      return StatActivityStatus.WORKING;
        if (current instanceof LeisureActivity l) {
            Activity sub = l.currentSub();
            if (sub instanceof WanderActivity)     return StatActivityStatus.WANDERING;
            if (sub instanceof SocializeActivity)  return StatActivityStatus.SOCIALIZING;
            if (sub instanceof AdmireTownActivity) return StatActivityStatus.ADMIRING;
        }
        return StatActivityStatus.IDLE;
    }

    private boolean canPauseAndFaceForUi(NpcEntity npc) {
        return !isInRestPose(npc) && !suppressesUiAttention();
    }

    private boolean uiInteractionShouldYield(Activity.Context ctx) {
        if (suppressesUiAttention()) {
            return true;
        }
        // A tile action finishes before UI attention. Travel and supply runs
        // still pause immediately so the farmer faces the player while walking.
        if (current instanceof FarmerActivity farmer && farmer.isPerformingTileAction()) {
            return true;
        }
        if (current instanceof CookActivity cook && cook.isProcessingBatch()) {
            return true;
        }
        // A miner finishes the block it is breaking; then it turns to the player.
        if (current instanceof com.alex.test.village.villagers.miner.behavior.MinerActivity miner
                && miner.isMiningBlock()) {
            return true;
        }
        // A druid finishes the dance it is in; then it stops and turns to the player.
        // A mine job runs to its end before it does.
        if (current instanceof DruidActivity druid
                && (druid.isPerformingMagic() || druid.isRegrowingMine())) {
            return true;
        }
        // The tavern set plays on while a player has the bard's menu open, and so does
        // a serenade piece already playing: the music cannot be paused, so the pose is not.
        if (current instanceof com.alex.test.village.villagers.bard.behavior.BardActivity bard
                && (bard.isEveningSet() || bard.isPlayingPiece())) {
            return true;
        }
        // A class goes on while a player has the teacher's menu open: a teacher
        // that stopped to face the player let the class time out, and the
        // children left.
        if (current instanceof com.alex.test.village.villagers.teacher.behavior.TeacherActivity teacher
                && teacher.isHoldingClass()) {
            return true;
        }
        if (!isDefender()) return false;
        if (current instanceof GuardActivity guard && guard.isThreatResponseActive()) return true;
        // An archer stops and faces the player like anyone else, unless it is
        // actually fighting: it used to walk off while its screen was open
        // whenever the village had an emergency anywhere.
        if (current instanceof com.alex.test.village.villagers.archer.behavior.ArcherActivity archer) {
            return archer.isThreatResponseActive();
        }
        if (current instanceof com.alex.test.village.villagers.mage.behavior.MageActivity mage) return mage.isThreatResponseActive();
        return GuardAlertSystem.hasEmergencyForGuard(ctx);
    }

    private Activity sleepHungerPreparation(Activity.Context ctx, String reason) {
        if (current instanceof PreTaskHungerActivity
                || current instanceof FetchFoodActivity
                || current instanceof EatActivity) {
            return null;
        }

        if (sleepHungerRetryCooldown > 0 && !needs.hungerRules().isCritical(needs)) {
            return null;
        }

        diary.log(String.format("%s OVERRIDE -> eat %s (hunger=%.1f, target=%.1f)",
                VillagerDiary.ts(ctx.level()), reason, needs.hunger,
                needs.hungerRules().sleepPreparationFloor()));
        return new PreTaskHungerActivity(needs.hungerRules().sleepPreparationFloor());
    }

    /** How long one pre-bed top-up covers: half a day, so each bedtime gets one. */
    private static final long BED_TOP_UP_INTERVAL_TICKS = 12_000L;

    /**
     * One trip to the larder before bed when the pocket is down to the low
     * band (zero to four), so the villager wakes with food rather than
     * starting the day on an empty-pocket fetch. Runs after the pre-bed meal,
     * so the meal does not eat into the fresh reserve. Tried once per
     * bedtime whatever the outcome: a fetch that brings back nothing (stock
     * reserves, an emptied chest) would otherwise restart every tick.
     */
    private Activity bedFoodTopUp(Activity.Context ctx, String reason) {
        long now = ctx.level().getGameTime();
        if (bedTopUpTriedAt != Long.MIN_VALUE
                && now - bedTopUpTriedAt < BED_TOP_UP_INTERVAL_TICKS) return null;
        if (fetchFailCooldown > 0) return null;
        int carried = carriedFoodCount(ctx);
        if (!needs.hungerRules().isFoodReserveLow(carried)) return null;
        if (!villageHasFoodCached(ctx) || allFoodWrittenOff(ctx)) return null;
        bedTopUpTriedAt = now;
        diary.log(String.format("%s OVERRIDE -> fetch_food %s (reserve %d, hunger=%.1f)",
                VillagerDiary.ts(ctx.level()), reason, carried, needs.hunger));
        return FetchFoodActivity.topUp();
    }

    /** The evening day the bard last packed food for its set; once a night. */
    private long bardTopUpDay = Long.MIN_VALUE;

    /**
     * Before tonight's set: a bard down to the low band (zero to four) fills
     * its pocket first, so it has something to eat at the tavern. Tried once
     * a night whatever the outcome, and kept apart from the bedtime top-up so
     * the bard can still top up again before bed.
     */
    private Activity bardSetFoodTopUp(Activity.Context ctx) {
        if (bardTopUpDay == evening.day() || fetchFailCooldown > 0) return null;
        int carried = carriedFoodCount(ctx);
        if (!needs.hungerRules().isFoodReserveLow(carried)) return null;
        if (!villageHasFoodCached(ctx) || allFoodWrittenOff(ctx)) return null;
        bardTopUpDay = evening.day();
        diary.log(String.format("%s OVERRIDE -> fetch_food before the bard set (reserve %d, hunger=%.1f)",
                VillagerDiary.ts(ctx.level()), carried, needs.hunger));
        return FetchFoodActivity.topUp();
    }

    private static final long SALT_FOOD_RUN = 0x71A8;
    /**
     * The food run's window closes this long before the leaving time: a run
     * that found the table busy has its 600-tick fetch cooldown over by
     * bedtime, so the bedtime top-up still gets its try.
     */
    private static final int FOOD_RUN_END_MARGIN = 700;
    /** The evening day of the last food run check; once a night. Not saved. */
    private long foodRunDay = Long.MIN_VALUE;
    /** Tonight's food run while it is under way, to tell it apart when it fails. */
    @javax.annotation.Nullable private Activity foodRunTrip;

    /**
     * A villager relaxing at home with a low pocket fetches its food at its
     * own moment of the evening, rolled per villager and night between its
     * head-out time and shortly before its leaving time. The bedtime top-up
     * alone sent every low pocket at its leaving time, a 1,000-tick window,
     * and twenty of them queued for one crafting table. Not while a guest is
     * on the way to this villager's house.
     */
    @javax.annotation.Nullable
    private Activity eveningFoodRun(Activity.Context ctx, EveningPlanner.Facts facts, long dayTime) {
        if (facts.current() != EveningPlanner.Current.HOME
                || facts.phase() != EveningSchedule.Phase.GATHER
                || foodRunDay == evening.day()) return null;
        EveningSchedule.Night night = tonight(dayTime);
        int end = night.leaveFor(earlySleeper(dayTime)) - FOOD_RUN_END_MARGIN;
        int span = end - night.headOut();
        if (span <= 0) return null;
        long t = EveningSchedule.timeOfDay(dayTime);
        if (t >= end || t < night.headOut() + eveningRoll(SALT_FOOD_RUN, span)) return null;
        // Its moment has come: looked at once, whatever the answer.
        foodRunDay = evening.day();
        if (!choresNeeded(ctx) || guestOnTheWay(ctx)) return null;
        Activity run = bedFoodTopUp(ctx, "evening food run");
        foodRunTrip = run;
        return run;
    }

    /** Whether another villager is visiting (or walking to) this villager's house right now. */
    private boolean guestOnTheWay(Activity.Context ctx) {
        Building house = ctx.mgr().getAssignedBedBuilding(model.npcId);
        if (house == null) return false;
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == model) continue;
            if (house.getItemFrameId().equals(other.brain().visitHostId())) return true;
        }
        return false;
    }

    /**
     * A meal at the tavern stage, stepping off the bard's set: dinner on
     * arrival before the set opens (as hungry as the guests eat theirs), or a
     * break between songs when starving. The set starts again afterwards and
     * does not replay the recording.
     */
    private Activity bardMealAtTavern(Activity.Context ctx) {
        boolean critical = needs.hungerRules().isCritical(needs);
        boolean dinner = !evening.mealDone() && !evening.bardPianoPlayed()
                && needs.hunger < EVENING_MEAL_HUNGER;
        if (!critical && !dinner) return null;
        // The inventory is only looked through once a second, staggered by id.
        if (Math.floorMod(ctx.level().getGameTime() + model.npcId.hashCode(), 20) != 0) return null;
        if (EatActivity.findFoodSlot(ctx) < 0) return null;
        boolean proper = !evening.mealDone();
        if (proper) {
            evening.onMealStarted();
            saveEvening(ctx.npc());
        }
        diary.log(String.format("%s EVENING bard %s at the tavern (hunger=%.1f)",
                VillagerDiary.ts(ctx.level()), dinner ? "dinner before the set" : "meal break", needs.hunger));
        return new EatActivity(proper);
    }

    private boolean isInRestPose(NpcEntity npc) {
        if (npc.isSleeping()) return true;
        VillagerAnimationState animation = npc.getAnimation();
        return animation == VillagerAnimationState.SITDOWN
                || animation == VillagerAnimationState.SLEEP_SITTING_DOWN
                || animation == VillagerAnimationState.SITUP
                || animation == VillagerAnimationState.SLEEP_LYING_DOWN
                || animation.isWallSleep();
    }

    /**
     * How far apart villagers' schedules are spread: 100 ticks = 5 seconds.
     * Each villager's clock runs a fixed 0-99 ticks behind the world's.
     */
    private static final int SCHEDULE_SPREAD_TICKS = 100;

    public static int playerSleepWakeDelay(UUID villagerId) {
        return 1 + Math.floorMod(villagerId.hashCode(), SCHEDULE_SPREAD_TICKS);
    }

    /** World night, the same for everyone. Use {@link #isNight(Activity.Context)} for schedule decisions. */
    public static boolean isNight(ServerLevel level) {
        return isNightAt(level.getDayTime());
    }

    /**
     * Night on this villager's own clock.
     *
     * <p>Every schedule decision -- going to bed, waking, starting and ending a
     * guard shift -- goes through here rather than {@link #isNight(ServerLevel)}.
     * On the world clock, dusk and dawn flip for the whole village in one
     * tick, so every villager searched for a bed (dozens of synchronous A*
     * reachability checks each) or re-planned its day at once, and
     * {@code /time set night} or {@code /time set day} froze the server long
     * enough to see villagers jump. A per-villager delay spreads that work
     * over five seconds. All schedule checks must use the same clock, or the
     * night override and GoHomeActivity's wake check disagree and flip-flop.
     */
    public static boolean isNight(Activity.Context ctx) {
        return ctx.level().getGameTime() < ctx.brain().sleepSkipWakeAtGameTime
                || isNight(ctx.level(), ctx.model().npcId);
    }

    /** Still held asleep after a player skipped the night; see {@link #staggerWakeAfterPlayerSleep}. */
    public static boolean heldAsleepAfterSkip(Activity.Context ctx) {
        return ctx.level().getGameTime() < ctx.brain().sleepSkipWakeAtGameTime;
    }

    /** This villager's spread on the world night boundary, 0-99 ticks. */
    public static int scheduleDelay(UUID villagerId) {
        return Math.floorMod(villagerId.hashCode(), SCHEDULE_SPREAD_TICKS);
    }

    public static boolean isNight(ServerLevel level, UUID villagerId) {
        int delay = Math.floorMod(villagerId.hashCode(), SCHEDULE_SPREAD_TICKS);
        return isNightAt(level.getDayTime() - delay);
    }

    private static boolean isNightAt(long dayTime) {
        long t = Math.floorMod(dayTime, 24000L);
        return t >= 12700L && t <= 23200L;
    }



    /**
     * Whether a low reserve (one to four items) has been low long enough to go
     * and top it up. Running low is not an emergency while there is still food
     * in the pocket, so the villager carries on for a while first -- two
     * minutes plus up to one more, spread by id so a crew that ate together
     * does not leave for the larder together. Walking right past the storage
     * still tops up at once through the nearby-storage bonus; an empty pocket
     * is handled without any wait.
     */
    private boolean lowFoodReserveOverdue(Activity.Context ctx, int carried) {
        if (carried <= 0 || !needs.hungerRules().isFoodReserveLow(carried)) {
            foodReserveLowSince = Long.MIN_VALUE;
            return false;
        }
        long now = ctx.level().getGameTime();
        if (foodReserveLowSince == Long.MIN_VALUE) foodReserveLowSince = now;
        long wait = LOW_FOOD_RESERVE_WAIT_TICKS
                + Math.floorMod(ctx.model().npcId.hashCode(), LOW_FOOD_RESERVE_WAIT_SPREAD_TICKS);
        return now - foodReserveLowSince >= wait;
    }

    private static int carriedFoodCount(Activity.Context ctx) {
        return StorageSupplyRestocker.countCarriedFood(ctx);
    }

    /**
     * Whether it is worth eating before lying down.
     *
     * <p>Hunger preparation only makes sense while there is something to eat.
     * With the larder empty it is actively harmful: {@link #sleepHungerPreparation}
     * ignores its own retry cooldown once hunger is critical, so the hungrier the
     * villager gets the harder it retries a fetch that cannot succeed, and it
     * never reaches its bed. Sleeping quarters the hunger drain and restores
     * health at dawn, so going to bed hungry beats standing outside all night —
     * which is how a whole seven-villager crew starved to death in
     * {@code village_survival} while their beds sat empty.
     *
     * <p>Food this villager cannot get to is no better. Once it has written off
     * every chest holding food as unreachable, the fetch fails before taking a
     * step, the pre-sleep eat ends, and the night override starts it again the
     * next tick. The villager never walks anywhere: three druids stood frozen
     * in a field all night like that. A write-off lasts
     * {@link #UNREACHABLE_CHEST_TICKS}; when it runs out this says yes again,
     * and a villager that has since come within reach goes and eats.
     */
    private boolean shouldPrepareHungerBeforeRest(Activity.Context ctx) {
        if (!needs.hungerRules().needsSleepPreparation(needs)) return false;
        if (EatActivity.findFoodSlot(ctx) >= 0) return true;
        return villageHasFoodCached(ctx) && !allFoodWrittenOff(ctx);
    }

    /**
     * True when every chest holding food, or wheat enough to bake, is one this
     * villager has written off as unreachable. False when there are no such
     * chests at all, so baking with carried wheat still counts as food.
     *
     * <p>The rest overrides ask this every tick, so the answer is cached as
     * long as {@link #villageHasFoodCached}'s; a new write-off clears the cache.
     */
    private boolean allFoodWrittenOff(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (now < foodWrittenOffRecheckAt) return foodWrittenOff;
        foodWrittenOffRecheckAt = now + VILLAGE_FOOD_CACHE_TICKS;
        foodWrittenOff = false;
        unreachableChestUntil.values().removeIf(until -> until <= now);
        if (unreachableChestUntil.isEmpty()) return false;

        boolean sawWrittenOff = false;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            List<BlockPos> chests = new ArrayList<>(
                    s.findChestsWithResource(ctx.level(), StorageBuilding.ResourceType.FOOD));
            if (BakeBreadActivity.canBakeIn(s, ctx.level())) {
                chests.addAll(s.findChestsWithItem(ctx.level(), new ItemStack(Items.WHEAT)));
            }
            for (BlockPos pos : chests) {
                if (!s.getFunctionalBlocks().contains(pos)) continue;
                if (!isChestUnreachable(pos, now)) return false;
                sawWrittenOff = true;
            }
        }
        foodWrittenOff = sawWrittenOff;
        return foodWrittenOff;
    }

    /**
     * {@link #villageHasFood} behind a short cache.
     *
     * <p>The uncached form walks every chest in every storage room, and the rest
     * overrides above consult it from {@code checkOverrides}, which runs every
     * tick for every villager. Re-reading the answer twice a second is plenty:
     * the only cost is a two-second delay before a villager notices food that
     * has just been deposited.
     */
    /** The same cached answer for {@link ThoughtState}, which has no context of its own. */
    boolean villageHasFoodCached(VillageManager mgr, ServerLevel level) {
        return villageHasFoodCached(new Activity.Context(this, model, mgr, level, model.puppet()));
    }

    /**
     * Whether this villager has work, for {@link ThoughtState} only. Reuses an
     * answer up to {@link #THOUGHT_WORK_CHECK_TICKS} old: asking fresh every
     * bubble check made an idle lumberjack inspect every known tree each time.
     */
    boolean hasImmediateWorkForThought(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (workCheckProfession == profession() && now - workCheckAt < THOUGHT_WORK_CHECK_TICKS) {
            return workCheckResult;
        }
        return checkImmediateWork(ctx);
    }

    /** Asks the profession fresh and remembers the answer for the thought bubble. */
    private boolean checkImmediateWork(Activity.Context ctx) {
        boolean result = VillagerRegistry.hasImmediateWork(profession(), ctx);
        workCheckAt = ctx.level().getGameTime();
        workCheckResult = result;
        workCheckProfession = profession();
        return result;
    }

    private boolean villageHasFoodCached(Activity.Context ctx) {
        if (villageFoodKnown && villageFoodRecheck > 0) return villageFoodPresent;
        villageFoodPresent = villageHasFood(ctx);
        villageFoodKnown   = true;
        villageFoodRecheck = VILLAGE_FOOD_CACHE_TICKS;
        return villageFoodPresent;
    }

    /**
     * Whether any valid storage room in the village still holds food.
     *
     * <p>Asked outright rather than inferred from {@code fetchFailCooldown}: a
     * fetch that ends for any reason other than FAILED leaves that cooldown at
     * zero, so it answers "did the last fetch fail", which is not the same
     * question and disagrees often enough to matter. Only reached from
     * {@link #decideNext}, which runs between activities rather than per tick.
     */
    /**
     * Ticks a work action should take for this villager, given a base duration.
     *
     * <p>General work activities route their timing through here. Lumberjack
     * chopping uses its own axe, skill, Efficiency and tree formula. Larger is
     * always faster here.
     */
    public int workTicks(int baseTicks) {
        float speed = Math.max(0.5f, personality.workSpeed)
                * skills.speedMultiplier(profession());
        return Math.max(1, Math.round(baseTicks / speed));
    }

    // ---- Effort: hunger spent on work and walking -------------------------

    private double exertionLastX = Double.NaN;
    private double exertionLastZ;
    private float workExertionToday;
    private float walkExertionToday;
    private long exertionDay = -1;

    /**
     * Charges hunger for effort: every working tick by the job's intensity and
     * the villager's skill, every block walked. Logs yesterday's totals to the
     * diary when the day changes, so the numbers can be tuned by playing.
     */
    private void tickExertion(ServerLevel level, NpcEntity npc) {
        long day = EveningSchedule.dayIndex(level.getDayTime());
        if (day != exertionDay) {
            if (exertionDay >= 0 && workExertionToday + walkExertionToday > 0f) {
                diary.log(String.format("%s EXERTION day %d: work %.1f + walking %.1f = %.1f hunger (%s)",
                        VillagerDiary.ts(level), exertionDay, workExertionToday, walkExertionToday,
                        workExertionToday + walkExertionToday, profession().displayName()));
            }
            exertionDay = day;
            workExertionToday = 0f;
            walkExertionToday = 0f;
        }

        float spent = 0f;
        if (currentStatus() == StatActivityStatus.WORKING) {
            Profession profession = profession();
            float work = HungerSystem.workExertionPerTick(profession,
                    profession == Profession.NONE ? 1 : skills.level(profession));
            workExertionToday += work;
            spent += work;
        }
        double x = npc.getX();
        double z = npc.getZ();
        if (!Double.isNaN(exertionLastX) && !npc.isPassenger()) {
            double dx = x - exertionLastX;
            double dz = z - exertionLastZ;
            double moved = Math.sqrt(dx * dx + dz * dz);
            // A jump this large in one tick is a teleport or a rescue, not a walk.
            if (moved > 0.01 && moved < 2.0) {
                float walk = (float) moved * HungerSystem.WALK_HUNGER_PER_BLOCK;
                walkExertionToday += walk;
                spent += walk;
            }
        }
        exertionLastX = x;
        exertionLastZ = z;
        if (spent > 0f) needs.onExertion(spent);
    }

    public int lumberjackSkillLevel() {
        return skills.level(Profession.LUMBERJACK);
    }

    public int farmerSkillLevel() {
        return skills.level(Profession.FARMER);
    }

    public int minerSkillLevel() {
        return skills.level(Profession.MINER);
    }

    /**
     * Miner XP follows the time spent swinging, ore counting half again: in
     * full for the day's first 7 minutes, a fifth after (MinerBalance,
     * MINER_TICKS). 80 days to level 100 learning at 1x with full days.
     */
    public void onMinerBlockMined(Activity.Context ctx, boolean ore, int blockTicks) {
        float amount = MINER_TICKS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()),
                MinerBalance.payTicksForBlock(blockTicks, ore));
        float before = skills.experience(Profession.MINER);
        boolean levelled = skills.addWorkExperience(Profession.MINER, amount);
        minerMiningTicksToday += blockTicks;
        minerXpToday += skills.experience(Profession.MINER) - before;
        syncMinerSkill(ctx.npc());
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Miner reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), minerSkillLevel(),
                    skills.describe(Profession.MINER)));
        }
    }

    public int druidSkillLevel() {
        return skills.level(Profession.DRUID);
    }

    public int cookSkillLevel() {
        return skills.level(Profession.COOK);
    }

    public int bardSkillLevel() {
        return skills.level(Profession.BARD);
    }

    public int guardSkillLevel() {
        return skills.level(Profession.GUARD);
    }

    public int archerSkillLevel() {
        return skills.level(Profession.ARCHER);
    }

    public int captainSkillLevel() {
        return skills.level(Profession.CAPTAIN);
    }

    public int blacksmithSkillLevel() {
        return skills.level(Profession.BLACKSMITH);
    }

    public int enchanterSkillLevel() {
        return skills.level(Profession.ENCHANTER);
    }

    /**
     * Enchanter practice: every finished book, and a little for paper and
     * books it makes (see {@link com.alex.test.village.villagers.enchanter.EnchanterBalance}).
     * The day's book cap paces it, so no full-day rule is needed.
     */
    public void onEnchanterExperience(Activity.Context ctx, float amount) {
        if (profession() != Profession.ENCHANTER) return;
        boolean levelled = skills.addWorkExperience(Profession.ENCHANTER, amount);
        syncEnchanterSkill(ctx.npc());
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Enchanter reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), enchanterSkillLevel(),
                    skills.describe(Profession.ENCHANTER)));
        }
    }

    public int teacherSkillLevel() {
        return skills.level(Profession.TEACHER);
    }

    /** Lesson units the teacher taught today, for the TEACHER_DAY diary line. */
    private float teacherUnitsToday;
    private float teacherXpToday;

    /**
     * Teacher practice: one lesson unit with children in class, weighted by
     * how many ({@link com.alex.test.village.villagers.teacher.TeacherBalance#unitWeight}).
     * In full for the day's first FULL_DAY_UNITS, a fifth after (TEACHER_UNITS):
     * 80 days to level 100 learning at 1x with a full class.
     */
    public void onTeacherUnit(Activity.Context ctx, float weight) {
        if (profession() != Profession.TEACHER || weight <= 0f) return;
        float raw = TEACHER_UNITS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), weight);
        float before = skills.experience(Profession.TEACHER);
        boolean levelled = skills.addWorkExperience(Profession.TEACHER, raw);
        teacherUnitsToday += weight;
        teacherXpToday += skills.experience(Profession.TEACHER) - before;
        syncTeacherSkill(ctx.npc());
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Teacher reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), teacherSkillLevel(),
                    skills.describe(Profession.TEACHER)));
        }
    }

    /** Shows {@code icon} over this villager for {@code ticks}, with no memory behind it. */
    public void showIcon(ThoughtIcon icon, int ticks, ServerLevel level) {
        NpcEntity npc = model.puppet();
        if (npc != null && npc.isAlive()) bubbles.showEvent(npc, level, icon, ticks);
    }

    public int clericSkillLevel() {
        return skills.level(Profession.CLERIC);
    }

    public int butcherSkillLevel() {
        return skills.level(Profession.BUTCHER);
    }

    /** Kills, strays and XP the butcher earned today, for the BUTCHER_DAY diary line. */
    private int butcherKillsToday;
    private int butcherStraysToday;
    private float butcherXpToday;

    /** Animals culled since the village morning, for the Butchery window; kept in memory only, so a reload starts at 0. */
    public int butcherKillsToday() { return butcherKillsToday; }

    /** Strays brought home since the village morning, as {@link #butcherKillsToday()}. */
    public int butcherStraysToday() { return butcherStraysToday; }

    /**
     * Butcher practice: one kill at the chopping block. In full for the day's
     * first {@link com.alex.test.village.villagers.butcher.ButcherBalance#FULL_DAY_KILLS},
     * a fifth after (BUTCHER_KILLS): 80 days to level 100 learning at 1x.
     */
    public void onButcherKill(Activity.Context ctx) {
        butcherKillsToday++;
        payButcher(ctx, 1f);
    }

    /** A pen animal brought home: half a kill's practice. */
    public void onButcherStray(Activity.Context ctx) {
        butcherStraysToday++;
        payButcher(ctx, com.alex.test.village.villagers.butcher.ButcherBalance.STRAY_KILLS);
    }

    private void payButcher(Activity.Context ctx, float kills) {
        if (profession() != Profession.BUTCHER) return;
        float raw = BUTCHER_KILLS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), kills);
        float before = skills.experience(Profession.BUTCHER);
        boolean levelled = skills.addWorkExperience(Profession.BUTCHER, raw);
        butcherXpToday += skills.experience(Profession.BUTCHER) - before;
        syncButcherSkill(ctx.npc());
        skills.save(ctx.npc().getBrainData());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Butcher reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), butcherSkillLevel(),
                    skills.describe(Profession.BUTCHER)));
        }
    }

    public int rancherSkillLevel() {
        return skills.level(Profession.RANCHER);
    }

    /** What the rancher did today, for the RANCHER_DAY diary line. */
    public enum RancherJob { SHEAR, MILK, EGG, HIDE, FEED, STRAY }
    private final int[] rancherJobsToday = new int[RancherJob.values().length];
    private float rancherXpToday;

    /**
     * Rancher practice: a finished job, worth {@code jobs} (one for most, half
     * per animal fed, two for an animal brought home). In full for the day's
     * first {@link com.alex.test.village.villagers.rancher.RancherBalance#FULL_DAY_JOBS},
     * a fifth after (RANCHER_JOBS): 80 days to level 100 learning at 1x.
     */
    public void onRancherJob(Activity.Context ctx, RancherJob kind, float jobs) {
        if (profession() != Profession.RANCHER) return;
        rancherJobsToday[kind.ordinal()]++;
        float raw = RANCHER_JOBS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), jobs);
        float before = skills.experience(Profession.RANCHER);
        boolean levelled = skills.addWorkExperience(Profession.RANCHER, raw);
        rancherXpToday += skills.experience(Profession.RANCHER) - before;
        syncRancherSkill(ctx.npc());
        skills.save(ctx.npc().getBrainData());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Rancher reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), rancherSkillLevel(),
                    skills.describe(Profession.RANCHER)));
        }
    }

    /** Care points the cleric earned today, for the CLERIC_DAY diary line. */
    private float clericPointsToday;
    private float clericXpToday;

    /**
     * Cleric practice: care points for a heart healed, food brought, a sad
     * villager cheered up, the morning prayer or a stop on its rounds. In full
     * for the day's first {@link com.alex.test.village.villagers.cleric.ClericBalance#FULL_DAY_POINTS},
     * a fifth after (CLERIC_POINTS): 80 days to level 100 learning at 1x.
     */
    public void onClericCare(Activity.Context ctx, float points, String what) {
        if (profession() != Profession.CLERIC || points <= 0f) return;
        float raw = CLERIC_POINTS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), points);
        float before = skills.experience(Profession.CLERIC);
        boolean levelled = skills.addWorkExperience(Profession.CLERIC, raw);
        clericPointsToday += points;
        clericXpToday += skills.experience(Profession.CLERIC) - before;
        syncClericSkill(ctx.npc());
        diary.log(String.format("%s CLERIC care +%.1f pts (%s) | today %.1f pts, %.1f xp",
                VillagerDiary.ts(ctx.level()), points, what, clericPointsToday, clericXpToday));
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Cleric reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), clericSkillLevel(),
                    skills.describe(Profession.CLERIC)));
        }
    }

    // ---- Being tended by a cleric -----------------------------------------

    /** The cleric this villager is standing still for, and until when. */
    @javax.annotation.Nullable private NpcEntity careGiver;
    private long careHoldUntil = Long.MIN_VALUE;

    /** Whether a cleric is healing or talking to this villager right now. */
    public boolean careHoldActive() {
        NpcEntity npc = model.puppet();
        return careGiver != null && npc != null && npc.level().getGameTime() < careHoldUntil;
    }

    /**
     * A cleric asks this villager to stand still and face it for
     * {@code ticks}: while its healing orb flies, or for a chat. Its current
     * activity is paused, not dropped, and carries on afterwards. Refused
     * while it is busy with something that cannot wait.
     *
     * @return true when the villager will wait
     */
    public boolean requestCareHold(NpcEntity caregiver, int ticks) {
        NpcEntity npc = model.puppet();
        if (npc == null || caregiver == null || ticks <= 0 || !mayHoldForCare(npc)) return false;
        careGiver = caregiver;
        careHoldUntil = npc.level().getGameTime() + ticks;
        return true;
    }

    /** Whether a cleric may stop this villager for a chat or a heal right now. */
    public boolean isFreeToChat() {
        NpcEntity npc = model.puppet();
        return npc != null && !careHoldActive() && mayHoldForCare(npc);
    }

    /** The cleric is done with this villager; it may go back to what it was doing. */
    public void releaseCareHold(NpcEntity caregiver) {
        if (careGiver == caregiver) {
            careGiver = null;
            careHoldUntil = Long.MIN_VALUE;
        }
    }

    /**
     * Never a villager running for its life, sheltering, asleep, eating, already
     * talking, swimming out of water, or a guard or archer in a fight: the
     * cleric's orb finds it wherever it is, and stopping it there could get it
     * killed.
     */
    private boolean mayHoldForCare(NpcEntity npc) {
        if (current instanceof FleeActivity || current instanceof ShelterActivity
                || current instanceof ConverseActivity || current instanceof EatActivity
                || current instanceof GoHomeActivity) {
            return false;
        }
        if (npc.isSleeping() || npc.isEating() || npc.isPreviewingAnimation()
                || model.isEscapingWater() || npc.hasPendingProfessionChange()) {
            return false;
        }
        if (isDefender()) {
            // A guard or archer walking its patrol may stop for a moment; one
            // with a target, falling back, on alert, with its shield up or hit
            // in the last few seconds is fighting, and keeps fighting. It used
            // to count only a guard right beside its enemy: one walking up to
            // a mob, an archer between shots or a hurt guard falling back
            // stood still for the orb, its fight paused, and was killed.
            boolean recentlyHit = npc.getLastHurtByMob() != null
                    && npc.tickCount - npc.getLastHurtByMobTimestamp() < 200;
            boolean fighting = current instanceof GuardActivity guard && guard.isThreatResponseActive()
                    || current instanceof com.alex.test.village.villagers.archer.behavior.ArcherActivity archer
                            && archer.isThreatResponseActive()
                    || current instanceof com.alex.test.village.villagers.mage.behavior.MageActivity mage && mage.isThreatResponseActive()
                    || GuardAlertSystem.isOnAlert(npc);
            if (fighting || recentlyHit || npc.isCombatReady() || npc.isShieldRaised()
                    || npc.getAnimation() == VillagerAnimationState.ATTACK
                    || npc.getAnimation() == VillagerAnimationState.AIM
                    || npc.getAnimation().isMageCast()) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // Pauses: a screen open or a cleric tending
    // =========================================================================

    /** Ticks this villager has spent paused, up to {@code now}; see {@link Activity.Context#clock()}. */
    public long pausedTicks(long now) {
        long running = pauseStartedAt == Long.MIN_VALUE ? 0 : Math.max(0, now - pauseStartedAt);
        return pausedTicksDone + running;
    }

    /**
     * The current activity was not ticked this tick. The first such tick
     * starts the pause, which keeps everything the activity holds (see
     * {@link VillagerPause}). A pause that reaches
     * {@link VillagerPause#MAX_PAUSE_TICKS} gives the activity up, cleanly:
     * the villager lets go of what it held and picks something new once the
     * screen closes. Sleep is never given up.
     */
    private void pausedTick(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        if (pauseStartedAt == Long.MIN_VALUE) {
            pauseStartedAt = now;
            pausedActivity = current;
            pausedAnimation = animationAtTickStart;
            pauseGaveUp = false;
            // Claims are keyed by the entity's UUID in some registries and by
            // the villager's own id in others: both are covered.
            VillagerPause.start(npc.getUUID());
            VillagerPause.start(model.npcId);
        }
        if (pauseGaveUp || now - pauseStartedAt < VillagerPause.MAX_PAUSE_TICKS) return;
        // Asleep or sitting down to rest: nothing to give up, and stopping
        // it would get the villager out of bed.
        if (current instanceof GoHomeActivity || isInRestPose(npc)) return;
        pauseGaveUp = true;
        VillagerPause.forget(npc.getUUID());
        VillagerPause.forget(model.npcId);
        pausedActivity = null;
        if (current == null) return;
        diary.log(VillagerDiary.ts(ctx.level()) + " PAUSE gave up " + current.name()
                + ": held still for " + VillagerPause.MAX_PAUSE_TICKS / 1200 + " minutes");
        current.stop(ctx);
        current = null;
        saveActivityHint(npc);
    }

    /** The current activity runs again: the pause is over, and its clip is put back. */
    private void resumeAfterPause(Activity.Context ctx) {
        if (pauseStartedAt == Long.MIN_VALUE) return;
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        pausedTicksDone += Math.max(0, now - pauseStartedAt);
        pauseStartedAt = Long.MIN_VALUE;
        if (!pauseGaveUp) {
            VillagerPause.end(npc.getUUID(), now);
            VillagerPause.end(model.npcId, now);
        }
        if (current != null && current == pausedActivity && pausedAnimation != null
                && RESUMED_CLIPS.contains(pausedAnimation)
                && npc.getAnimation() == VillagerAnimationState.IDLE) {
            npc.setAnimation(pausedAnimation);
        }
        pausedActivity = null;
        pausedAnimation = null;
        pauseGaveUp = false;
    }

    /** Unloaded or dead: the pause ends and nothing it held stays covered. */
    private void endPauseWithoutCover(NpcEntity npc) {
        VillagerPause.forget(npc.getUUID());
        VillagerPause.forget(model.npcId);
        if (pauseStartedAt == Long.MIN_VALUE) return;
        pausedTicksDone += Math.max(0, npc.level().getGameTime() - pauseStartedAt);
        pauseStartedAt = Long.MIN_VALUE;
        pausedActivity = null;
        pausedAnimation = null;
        pauseGaveUp = false;
    }

    /** Holds the villager for its cleric; false (and the hold dropped) once it should move again. */
    private boolean tickCareHold(Activity.Context ctx) {
        if (careGiver == null) return false;
        NpcEntity npc = ctx.npc();
        if (!careHoldActive() || !careGiver.isAlive() || careGiver.isRemoved()
                || npc.distanceToSqr(careGiver) > 16.0 * 16.0 || !mayHoldForCare(npc)
                // The bell or a raid: a held guard or archer goes at once.
                || isDefender() && GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())) {
            careGiver = null;
            careHoldUntil = Long.MIN_VALUE;
            return false;
        }
        model.holdStillForCare();
        com.alex.test.entity.NpcUiInteractionHandler.faceEntity(npc, careGiver, true);
        return true;
    }

    /**
     * Whether this villager could use a cleric bringing it food: it carries
     * none, it is not already on its way to get some, and either it cannot get
     * any itself (the larder was empty or out of reach on its last try) or it
     * is hungry.
     */
    public boolean wantsFoodBrought(NpcEntity npc) {
        if (npc == null || current instanceof FetchFoodActivity || current instanceof EatActivity
                || npc.isSleeping()) {
            return false;
        }
        int carried = 0;
        for (ItemStack stack : npc.getInventory().getSlots()) {
            if (npc.getInventory().mayUse(stack) && VillageItemOwnership.villagerFood(stack, npc) != null) {
                carried += stack.getCount();
            }
        }
        if (carried > 0) return false;
        long now = npc.level().getGameTime();
        boolean cannotFetch = fetchFailCooldown > 0 || foodUnreachableUntil > now;
        return cannotFetch || needs.hungerRules().isLow(needs) || needs.hunger <= 0f;
    }

    /** Whether this villager has nothing left in its hunger bar. */
    public boolean isStarving() {
        return needs.hunger <= 0f;
    }

    /**
     * Whether a cleric may heal this villager (orb, splash, battle blessing or
     * Guardian Angel): always while it has hunger left; while starving only
     * {@link VillagerStats#STARVING_HEALS} times before it starves to death (user 10-19).
     */
    public boolean mayBeHealedByCleric() {
        return !isStarving() || needs.starvingHealsLeft > 0;
    }

    /** A cleric's heal reached this villager: while starving it uses one of its heals. */
    public void useStarvingHeal(String what, ServerLevel level) {
        if (!isStarving() || needs.starvingHealsLeft <= 0) return;
        needs.starvingHealsLeft--;
        diary.log(String.format("%s STARVING healed (%s); %d cleric heals left",
                VillagerDiary.ts(level), what, needs.starvingHealsLeft));
    }

    /** Items made, forge points and XP the blacksmith earned today, for the BLACKSMITH_DAY diary line. */
    private int blacksmithItemsToday;
    private float blacksmithPointsToday;
    private float blacksmithXpToday;

    /** A finished item at the anvil or smithing table: its points, counted as made. */
    public void onBlacksmithCraft(Activity.Context ctx, float points) {
        if (profession() != Profession.BLACKSMITH) return;
        blacksmithItemsToday++;
        onBlacksmithExperience(ctx, points);
    }

    /**
     * Blacksmith practice: forge points for every finished item by material,
     * every tool melted down, smelting and time at the forge. In full for the
     * day's first {@link BlacksmithBalance#FULL_DAY_POINTS}, a fifth after
     * (BLACKSMITH_POINTS): 80 days to level 100 learning at 1x.
     */
    public void onBlacksmithExperience(Activity.Context ctx, float points) {
        if (profession() != Profession.BLACKSMITH) return;
        blacksmithPointsToday += points;
        float raw = BLACKSMITH_POINTS.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), points);
        float before = skills.experience(Profession.BLACKSMITH);
        boolean levelled = skills.addWorkExperience(Profession.BLACKSMITH, raw);
        blacksmithXpToday += skills.experience(Profession.BLACKSMITH) - before;
        syncBlacksmithSkill(ctx.npc());
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Blacksmith reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), blacksmithSkillLevel(),
                    skills.describe(Profession.BLACKSMITH)));
        }
    }

    /** The skill level of the defender job this villager works now: guard, captain, archer or mage. */
    public int defenderSkillLevel() {
        return switch (profession()) {
            case CAPTAIN -> captainSkillLevel();
            case ARCHER -> archerSkillLevel();
            case MAGE -> mageSkillLevel();
            default -> guardSkillLevel();
        };
    }

    /** A mage's level on its own 1..100 track (MageBalance), grown by practice like a guard's. */
    public int mageSkillLevel() {
        return skills.level(Profession.MAGE);
    }

    /** A defender's minute on duty, counted for the daily line (GUARD_DAY and the like). */
    public void onDefenderDuty(Activity.Context ctx) {
        defenderDutyMinutesToday++;
        onDefenderExperience(ctx, GuardBalance.EXPERIENCE_PER_DUTY_MINUTE);
    }

    /** A defender's landed hits or arrows and kills (sweep kills included), counted for the daily line. */
    public void onDefenderCombat(Activity.Context ctx, int hits, int kills) {
        if (hits <= 0 && kills <= 0) return;
        defenderHitsToday += Math.max(0, hits);
        defenderKillsToday += Math.max(0, kills);
        onDefenderExperience(ctx, Math.max(0, hits) * GuardBalance.EXPERIENCE_PER_HIT
                + Math.max(0, kills) * GuardBalance.EXPERIENCE_PER_KILL);
    }

    /** Barracks drill or archery practice time, counted for the daily line, and what it pays. */
    public void onDefenderDrill(Activity.Context ctx, float minutes, float amount) {
        defenderDrillMinutesToday += Math.max(0f, minutes);
        onDefenderExperience(ctx, amount);
    }

    /**
     * Guard, captain and archer practice: time on duty, every landed hit or
     * arrow, every kill and drills (see {@link GuardBalance}). Paid to the
     * track of the job worked now. A level-up also raises a guard's or
     * captain's health at once rather than at the next skill sync.
     */
    public void onDefenderExperience(Activity.Context ctx, float amount) {
        Profession track = switch (profession()) {
            case CAPTAIN -> Profession.CAPTAIN;
            case ARCHER -> Profession.ARCHER;
            case MAGE -> Profession.MAGE;
            default -> Profession.GUARD;
        };
        // A day's first FULL_DAY_EXPERIENCE pays in full, the rest a fifth
        // (GuardBalance); each track keeps its own tally.
        com.alex.test.village.stats.DailyJobExperience rule = switch (track) {
            case CAPTAIN -> CAPTAIN_PRACTICE;
            case ARCHER -> ARCHER_PRACTICE;
            case MAGE -> MAGE_PRACTICE;
            default -> GUARD_PRACTICE;
        };
        float raw = rule.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), amount);
        float before = skills.experience(track);
        boolean levelled = skills.addWorkExperience(track, raw);
        defenderXpToday += skills.experience(track) - before;
        syncGuardSkill(ctx.npc());
        syncCaptainSkill(ctx.npc());
        syncArcherSkill(ctx.npc());
        syncMageSkill(ctx.npc());
        // Paid on every hit and drill slice: progress is written by the brain's
        // periodic save, a level-up at once.
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            int level = skills.level(track);
            if (profession() == track && track.isGuardLike()) {
                GuardSkillEffects.applyHealth(ctx.npc(), CombatRank.of(track), level);
            }
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  %s reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), track.displayName(), level,
                    skills.describe(track)));
        }
    }

    /**
     * A raid this defender fought in was won: {@code days} full working days'
     * practice ({@link GuardBalance#FULL_DAY_EXPERIENCE} each), paid in full,
     * outside the day's tally (a raid is not a day's drill). For guards,
     * captains and archers only; others are left alone.
     */
    public void onRaidVictoryExperience(com.alex.test.entity.NpcEntity npc, float days) {
        Profession track = switch (profession()) {
            case GUARD -> Profession.GUARD;
            case CAPTAIN -> Profession.CAPTAIN;
            case ARCHER -> Profession.ARCHER;
            case MAGE -> Profession.MAGE;
            default -> null;
        };
        if (track == null || days <= 0f) return;
        float before = skills.experience(track);
        boolean levelled = skills.addWorkExperience(track, days * GuardBalance.FULL_DAY_EXPERIENCE);
        defenderXpToday += skills.experience(track) - before;
        syncGuardSkill(npc);
        syncCaptainSkill(npc);
        syncArcherSkill(npc);
        syncMageSkill(npc);
        skills.save(npc.getBrainData());
        if (levelled && track.isGuardLike()) {
            GuardSkillEffects.applyHealth(npc, CombatRank.of(track), skills.level(track));
        }
        diary.log(String.format("%s RAID   won a raid: %.1f days' practice | %s",
                npc.level() instanceof net.minecraft.server.level.ServerLevel sl ? VillagerDiary.ts(sl) : "", days,
                skills.describe(track)));
    }

    /** A finished set teaches violin playing even when nobody was close enough to listen. */
    public void onBardBurstCompleted(Activity.Context ctx) {
        boolean levelled = skills.addWorkExperience(
                Profession.BARD, BardBalance.EXPERIENCE_PER_BURST);
        syncBardSkill(ctx.npc());
        skills.save(ctx.npc().getBrainData());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Bard reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), bardSkillLevel(),
                    skills.describe(Profession.BARD)));
        }
    }

    // Public for the away cook (economy/away/AwayXp), which keeps the same day counts.
    public static final String TAG_COOK_DISH_DAY = "CookDishDay";
    public static final String TAG_COOK_DISH_COUNTS = "CookDishCounts";
    public static final String TAG_COOK_DISHES_LEARNED = "CookDishesLearned";

    /**
     * Cooking practice for each batch actually completed, in lessons
     * (CookPlanner.lessons: harder dishes teach more). One dish's first
     * batches of the day teach in full, the rest half; the day's first 24
     * lessons pay in full, the rest a fifth (COOK_BATCHES). The first batch
     * of a dish this cook has never made is a lesson of its own.
     */
    public void onCookBatchCompleted(Activity.Context ctx, net.minecraft.world.item.Item result) {
        net.minecraft.nbt.CompoundTag data = ctx.npc().getBrainData();
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        if (data.getLong(TAG_COOK_DISH_DAY) != day || !data.contains(TAG_COOK_DISH_DAY)) {
            data.putLong(TAG_COOK_DISH_DAY, day);
            data.put(TAG_COOK_DISH_COUNTS, new net.minecraft.nbt.CompoundTag());
        }
        String key = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(result).toString();
        net.minecraft.nbt.CompoundTag counts = data.getCompound(TAG_COOK_DISH_COUNTS);
        int madeToday = counts.getInt(key);
        counts.putInt(key, madeToday + 1);
        data.put(TAG_COOK_DISH_COUNTS, counts);
        double lessons = com.alex.test.village.villagers.cook.CookPlanner.lessons(result)
                * (madeToday < CookBalance.REPEAT_FULL_BATCHES ? 1.0 : CookBalance.REPEAT_SHARE);

        boolean dish = com.alex.test.village.tokens.VillageFood.isDish(result);
        net.minecraft.nbt.CompoundTag learned = data.getCompound(TAG_COOK_DISHES_LEARNED);
        boolean newDish = dish && !learned.contains(key);
        if (newDish) {
            learned.putBoolean(key, true);
            data.put(TAG_COOK_DISHES_LEARNED, learned);
            // Inside the day's full pay like any lesson: a cook from before
            // dishes were remembered "learns" each dish once more, and the
            // full day keeps that from becoming days of free experience.
            lessons += CookBalance.NEW_DISH_LESSONS;
        }
        float raw = COOK_BATCHES.claim(data, day, lessons);
        if (dish) com.alex.test.village.villagers.cook.CookBonuses.recordDish(data, result);

        float before = skills.experience(Profession.COOK);
        boolean levelled = skills.addWorkExperience(Profession.COOK, raw);
        cookBatchesToday++;
        cookXpToday += skills.experience(Profession.COOK) - before;
        syncCookSkill(ctx.npc());
        skills.save(data);
        if (newDish) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Cook made %s for the first time | %s",
                    VillagerDiary.ts(ctx.level()), key, skills.describe(Profession.COOK)));
        }
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Cook reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), cookSkillLevel(),
                    skills.describe(Profession.COOK)));
        }
    }

    /**
     * Someone enjoyed a dish this cook's kitchen made: a small lesson
     * (CookBalance.PRAISE_LESSONS), inside the day's full pay like a batch.
     */
    public void onCookPraised(NpcEntity npc) {
        if (!(npc.level() instanceof net.minecraft.server.level.ServerLevel level)) return;
        net.minecraft.nbt.CompoundTag data = npc.getBrainData();
        float raw = COOK_BATCHES.claim(data, EveningSchedule.dayIndex(level.getDayTime()),
                CookBalance.PRAISE_LESSONS);
        float before = skills.experience(Profession.COOK);
        boolean levelled = skills.addWorkExperience(Profession.COOK, raw);
        cookPraiseToday++;
        cookXpToday += skills.experience(Profession.COOK) - before;
        syncCookSkill(npc);
        if (levelled) {
            skills.save(data);
            diary.log(String.format("%s SKILL  Cook reached level %d | %s",
                    VillagerDiary.ts(level), cookSkillLevel(), skills.describe(Profession.COOK)));
        }
    }

    /**
     * Druid XP follows time spent working, one payment per dance, whatever the
     * dance was for. Paying per crop stage instead would let a master, who
     * needs a third of the dances per stage, level three times as fast. In
     * full for the day's first 190 dances, a fifth after (DRUID_DANCES): 80
     * days to level 100 learning at 1x with full days.
     */
    public void onDruidDances(Activity.Context ctx, int dances) {
        if (dances <= 0) return;
        float raw = DRUID_DANCES.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), dances);
        float before = skills.experience(Profession.DRUID);
        boolean levelled = skills.addWorkExperience(Profession.DRUID, raw);
        druidDancesToday += dances;
        druidXpToday += skills.experience(Profession.DRUID) - before;
        syncDruidSkill(ctx.npc());
        if (levelled) {
            skills.save(ctx.npc().getBrainData());
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Druid reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), druidSkillLevel(),
                    skills.describe(Profession.DRUID)));
        }
    }

    /**
     * Farmer XP: every till, plant and harvest, from any crop: in full for
     * the day's first 24 jobs, a fifth after (FarmerBalance, FarmerExperience).
     * The full day, not the size of the farm, sets the pace: 80 days to
     * level 100 learning at 1x.
     */
    public void onFarmerJob(Activity.Context ctx) {
        farmerJobsToday++;
        float raw = com.alex.test.village.villagers.farmer.FarmerExperience.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), 1);
        if (raw <= 0f) return;
        float before = skills.experience(Profession.FARMER);
        boolean levelled = skills.addWorkExperience(Profession.FARMER, raw);
        farmerXpToday += skills.experience(Profession.FARMER) - before;
        syncFarmerSkill(ctx.npc());
        skills.save(ctx.npc().getBrainData());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Farmer reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), farmerSkillLevel(),
                    skills.describe(Profession.FARMER)));
        }
    }

    /**
     * Lumberjack XP per felled tree, whatever its size, including trees kept
     * across interrupted sessions: in full for the day's first 11, a fifth
     * after (LumberjackBalance, LUMBERJACK_TREES). 80 days to level 100
     * learning at 1x, felling a full day's trees.
     */
    public void onLumberjackTreeFelled(Activity.Context ctx, int logs) {
        lumberjackTreesToday++;
        lumberjackLogsToday += Math.max(0, logs);
        float raw = LUMBERJACK_TREES.claim(ctx.npc().getBrainData(),
                EveningSchedule.dayIndex(ctx.level().getDayTime()), 1);
        float before = skills.experience(Profession.LUMBERJACK);
        boolean levelled = skills.addWorkExperience(Profession.LUMBERJACK, raw);
        lumberjackXpToday += skills.experience(Profession.LUMBERJACK) - before;
        skills.addWorkIntelligence(INTELLIGENCE_PER_WORK_CYCLE);
        remember(MemoryTopic.WORK_DONE, ctx.level());
        syncLumberjackSkill(ctx.npc());
        skills.save(ctx.npc().getBrainData());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  Lumberjack reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), lumberjackSkillLevel(),
                    skills.describe(Profession.LUMBERJACK)));
        }
    }

    /**
     * Credits a finished work cycle to the villager's skill track.
     *
     * <p>Hooked to {@link WorkActivity} completing rather than to individual
     * profession events, so every profession earns skill on the same terms and a
     * new one gets it for free.
     */
    private void awardWorkExperience(Activity.Context ctx) {
        Profession profession = profession();
        if (profession == Profession.NONE || profession == Profession.LUMBERJACK) return;
        // A child learns at school only (the teacher pays it), not from its sittings.
        if (profession == Profession.CHILD) return;
        if (profession == Profession.FARMER || profession == Profession.MINER
                || profession == Profession.DRUID || profession == Profession.COOK
                || profession == Profession.BARD || profession == Profession.BLACKSMITH
                || profession == Profession.ARCHER || profession == Profession.CLERIC
                || profession == Profession.ENCHANTER
                || profession == Profession.TEACHER || profession == Profession.MAGE
                || profession == Profession.BUTCHER || profession == Profession.RANCHER) {
            // Intelligence follows completed work sessions, as it did before;
            // farmer XP is awarded per harvest, miner XP per block broken,
            // druid XP per dance, cleric XP per care point, butcher XP per kill
            // and rancher XP per job.
            skills.addWorkIntelligence(profession == Profession.FARMER
                    ? FarmerBalance.INTELLIGENCE_PER_WORK_SESSION : INTELLIGENCE_PER_WORK_CYCLE);
            remember(MemoryTopic.WORK_DONE, ctx.level());
            skills.save(ctx.npc().getBrainData());
            return;
        }
        boolean levelled = skills.addWorkExperience(profession, WORK_EXPERIENCE_PER_CYCLE);
        skills.addWorkIntelligence(INTELLIGENCE_PER_WORK_CYCLE);
        remember(MemoryTopic.WORK_DONE, ctx.level());
        if (levelled) {
            rememberAndShow(MemoryTopic.SKILL_UP, ThoughtIcon.EMOTE_PRIDE, 60, ctx.level());
            diary.log(String.format("%s SKILL  %s reached level %d | %s",
                    VillagerDiary.ts(ctx.level()), profession.displayName(),
                    skills.level(profession), skills.describe(profession)));
        }
    }

    /** Talking to people makes a villager quicker to learn, not better at their job. */
    public void onConversationLearned() {
        // Children start at nothing and learn at school; chatter does not count.
        if (profession() == Profession.CHILD) return;
        skills.addWorkIntelligence(INTELLIGENCE_PER_CONVERSATION);
    }

    /** How long a failed food fetch keeps this villager from trying again. */
    private static final int FETCH_FAIL_COOLDOWN_TICKS = 600;
    /** How long a chest this villager could not walk to stays written off. */
    private static final long UNREACHABLE_CHEST_TICKS = 20L * 180L;

    /**
     * Reacts to why a food fetch failed instead of treating every failure as
     * an empty larder.
     *
     * <p>An unreachable chest is not missing food. If another chest still has
     * food and has not been written off, the villager goes for it straight
     * away. Only when every food chest is out of reach does it wait out the
     * cooldown, and then it shows that it can't reach the food rather than
     * looking merely hungry next to a full storage room.
     */
    private void onFoodFetchFailed(Activity.Context ctx, ChestActivity.Failure why) {
        long now = ctx.level().getGameTime();
        if (why == ChestActivity.Failure.UNREACHABLE) {
            if (hasReachableFoodChest(ctx, now)) {
                fetchFailCooldown = 0;
                diary.log(String.format("%s fetch_food  chest unreachable; trying another",
                        VillagerDiary.ts(ctx.level())));
                return;
            }
            foodUnreachableUntil = now + UNREACHABLE_CHEST_TICKS;
        }
        fetchFailCooldown = FETCH_FAIL_COOLDOWN_TICKS;
        diary.log(String.format("%s fetch_food  failed (%s); retrying in %d ticks",
                VillagerDiary.ts(ctx.level()), why, FETCH_FAIL_COOLDOWN_TICKS));
    }

    /** Whether any storage chest with food is one this villager has not written off. */
    private boolean hasReachableFoodChest(Activity.Context ctx, long now) {
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            for (BlockPos pos : s.findChestsWithResource(ctx.level(), StorageBuilding.ResourceType.FOOD)) {
                if (s.getFunctionalBlocks().contains(pos) && !isChestUnreachable(pos, now)) return true;
            }
        }
        return false;
    }

    /**
     * The villager was trapped on a tile with no way off it since
     * {@code trappedSince}, and has just been moved somewhere it can walk from.
     *
     * <p>Every walk it tried while trapped failed, so it wrote off food chests
     * for three minutes, backed off from work, and gave up on fetching food.
     * None of that was true of the chests or the job. Forgets exactly what was
     * written off since then and ends a leisure filler, so the next decision is
     * made at once and it goes straight back to its task.
     */
    public void onRescuedFromTrap(long trappedSince) {
        unreachableChestUntil.values().removeIf(until -> until - UNREACHABLE_CHEST_TICKS >= trappedSince);
        if (foodUnreachableUntil != Long.MIN_VALUE
                && foodUnreachableUntil - UNREACHABLE_CHEST_TICKS >= trappedSince) {
            foodUnreachableUntil = Long.MIN_VALUE;
        }
        foodWrittenOffRecheckAt = 0L;
        fetchFailCooldown = 0;
        workBackoff = 0;
        decisionCooldown = 0;
        endLeisureAfterRescue = true;
    }

    /** Writes {@code chest} off for this villager for {@link #UNREACHABLE_CHEST_TICKS}. */
    public void markChestUnreachable(BlockPos chest, long now) {
        if (chest == null) return;
        unreachableChestUntil.put(chest.asLong(), now + UNREACHABLE_CHEST_TICKS);
        foodWrittenOffRecheckAt = 0L;
    }

    /** Whether this villager recently failed to reach {@code chest}. */
    public boolean isChestUnreachable(BlockPos chest, long now) {
        if (chest == null || unreachableChestUntil.isEmpty()) return false;
        unreachableChestUntil.values().removeIf(until -> until <= now);
        return unreachableChestUntil.containsKey(chest.asLong());
    }

    /** True while the village has food but none of it is reachable for this villager. */
    public boolean isFoodUnreachable(long now) {
        return now < foodUnreachableUntil;
    }

    private static boolean villageHasFood(Activity.Context ctx) {
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            // Food the Stock tab keeps for the player does not count: the fetch would refuse it.
            if (FetchFromStorageActivity.hasFoodItMayTake(ctx, s)) return true;
        }
        // Wheat and a crafting table are food too: fetch_food bakes it.
        return BakeBreadActivity.canBake(ctx);
    }

    private static double nearestStorageDistanceSqr(Activity.Context ctx) {
        BlockPos npcPos = ctx.npc().blockPosition();
        double best = Double.MAX_VALUE;
        for (Building b : ctx.mgr().getBuildings()) {
            if (!(b instanceof StorageBuilding s) || !s.isValid()) continue;
            if (s.getFunctionalBlocks().isEmpty()) continue;
            best = Math.min(best, s.getDoorPos().distSqr(npcPos));
        }
        return best;
    }

    /**
     * Writes the brain into this body's save data now, not at the next 5 s save. Minecraft calls
     * it (through {@code NpcEntity.addAdditionalSaveData}) when it saves the body: a far villager
     * kept awake is saved with the needs it has this tick. Nothing before the brain has loaded
     * from the body, nor for a body that is not the model's own.
     */
    public void saveNow(NpcEntity npc) {
        if (loaded && model.puppet() == npc) saveTo(npc);
    }

    private void saveTo(NpcEntity npc) {
        needs.save(npc.getBrainData());
        crowding.save(npc.getBrainData());
        skills.save(npc.getBrainData());
        memory.save(npc.getBrainData());
        saveEvening(npc);
        saveReadingLedger(npc);
        saveActivityHint(npc);
    }

    private void saveActivityHint(NpcEntity npc) {
        if (current == null) npc.getBrainData().remove(TAG_CURRENT_ACTIVITY);
        else npc.getBrainData().putString(TAG_CURRENT_ACTIVITY, current.name());
    }

    private void loadFrom(NpcEntity npc) {
        // A brand-new villager rolls starting intelligence into brain data when its
        // skills are first initialised; do that before reading, not after.
        npc.ensureProfessionSkillsInitialized();
        personality = npc.personality();
        needs.load(npc.getBrainData());
        skills.load(npc.getBrainData());
        crowding.load(npc.getBrainData());
        if (!npc.getBrainData().contains(TAG_LUMBERJACK_SKILL_VERSION)) {
            // The old hidden skill reached 10; the visible profession slot reached
            // 40. Preserve the visible level and scale hidden XP to the new cap.
            float oldXp = skills.experience(Profession.LUMBERJACK);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.LUMBERJACK)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.LUMBERJACK);
            skills.setLumberjackLevelAtLeast(
                    Math.max(shownLevel, oldLevel * LumberjackBalance.MAX_LEVEL
                            / VillagerSkills.MAX_LEVEL));
            // Set straight onto the current curve: nothing left to convert.
            npc.getBrainData().putInt(TAG_LUMBERJACK_SKILL_VERSION, LUMBERJACK_SKILL_VERSION);
        } else if (npc.getBrainData().getInt(TAG_LUMBERJACK_SKILL_VERSION) < LUMBERJACK_SKILL_VERSION) {
            // The 10-03 curve: same level and progress, numbers on the new scale.
            skills.setLumberjackExperience(LumberjackBalance.convertLegacyExperience(
                    skills.experience(Profession.LUMBERJACK)));
            // Written with the version mark: a save in between must not pair
            // the new mark with the old numbers.
            skills.save(npc.getBrainData());
            npc.getBrainData().putInt(TAG_LUMBERJACK_SKILL_VERSION, LUMBERJACK_SKILL_VERSION);
        }
        syncLumberjackSkill(npc);
        if (!npc.getBrainData().contains(TAG_FARMER_SKILL_VERSION)) {
            float oldXp = skills.experience(Profession.FARMER);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.FARMER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.FARMER);
            skills.setFarmerLevelAtLeast(Math.max(shownLevel,
                    oldLevel * FarmerBalance.MAX_LEVEL / VillagerSkills.MAX_LEVEL));
            // Set straight onto the current curve: nothing left to convert.
            npc.getBrainData().putInt(TAG_FARMER_SKILL_VERSION, FARMER_SKILL_VERSION);
        } else if (npc.getBrainData().getInt(TAG_FARMER_SKILL_VERSION) < FARMER_SKILL_VERSION) {
            // The 10-03 curve: same level and progress, numbers on the new scale.
            skills.setFarmerExperience(FarmerBalance.convertLegacyExperience(
                    skills.experience(Profession.FARMER)));
            // Written with the version mark: a save in between must not pair
            // the new mark with the old numbers.
            skills.save(npc.getBrainData());
            npc.getBrainData().putInt(TAG_FARMER_SKILL_VERSION, FARMER_SKILL_VERSION);
        }
        syncFarmerSkill(npc);
        if (!npc.getBrainData().contains(TAG_MINER_SKILL_VERSION)) {
            float oldXp = skills.experience(Profession.MINER);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.MINER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.MINER);
            skills.setMinerLevelAtLeast(Math.max(shownLevel,
                    oldLevel * MinerBalance.MAX_LEVEL / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_MINER_SKILL_VERSION, 1);
        }
        syncMinerSkill(npc);
        if (!npc.getBrainData().contains(TAG_DRUID_SKILL_VERSION)) {
            // The old druid track was a hidden 0..10 speed bonus nobody could
            // see. The slot on the info screen is the level players know, so
            // that is the one carried over.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.DRUID)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.DRUID);
            skills.setDruidLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_DRUID_SKILL_VERSION, 1);
        }
        syncDruidSkill(npc);
        if (!npc.getBrainData().contains(TAG_COOK_SKILL_VERSION)) {
            float oldXp = skills.experience(Profession.COOK);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.COOK)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.COOK);
            skills.setCookLevelAtLeast(Math.max(shownLevel,
                    oldLevel * CookBalance.MAX_LEVEL / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_COOK_SKILL_VERSION, 1);
        }
        syncCookSkill(npc);
        if (!npc.getBrainData().contains(TAG_BARD_SKILL_VERSION)) {
            float oldXp = skills.experience(Profession.BARD);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.BARD)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.BARD);
            skills.setBardLevelAtLeast(Math.max(shownLevel,
                    oldLevel * BardBalance.MAX_LEVEL / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_BARD_SKILL_VERSION, 1);
        }
        syncBardSkill(npc);
        if (!npc.getBrainData().contains(TAG_GUARD_SKILL_VERSION)) {
            // Guards never earned experience before their 1..100 track; the
            // level on the info screen is the rolled one players know, so
            // that is the one kept.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.GUARD)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.GUARD);
            skills.setGuardLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_GUARD_SKILL_VERSION, 1);
        }
        syncGuardSkill(npc);
        if (!npc.getBrainData().contains(TAG_ARCHER_SKILL_VERSION)) {
            // Archers had no 1..100 track (and their practice went to a guard
            // track most of them lack); keep the level the info screen shows.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.ARCHER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.ARCHER);
            skills.setArcherLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_ARCHER_SKILL_VERSION, 1);
        }
        syncArcherSkill(npc);
        // Mages had no track before (their level was their slot's): it starts
        // from that level, so nothing is lost and nothing needs migrating.
        syncMageSkill(npc);
        if (!npc.getBrainData().contains(TAG_BLACKSMITH_SKILL_VERSION)) {
            // Smiths earned only the legacy per-session trickle before their
            // 1..100 track; keep the level players see on the info screen.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.BLACKSMITH)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.BLACKSMITH);
            skills.setBlacksmithLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_BLACKSMITH_SKILL_VERSION, 1);
        }
        syncBlacksmithSkill(npc);
        if (!npc.getBrainData().contains(TAG_ENCHANTER_SKILL_VERSION)) {
            // Enchanters earned only the legacy 0..10 per-session trickle before
            // their 1..100 track; keep the level players see on the info screen.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.ENCHANTER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.ENCHANTER);
            skills.setEnchanterLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_ENCHANTER_SKILL_VERSION, 1);
        }
        syncEnchanterSkill(npc);
        if (!npc.getBrainData().contains(TAG_CLERIC_SKILL_VERSION)) {
            // Clerics earned only the legacy 0..10 per-session trickle before
            // their 1..100 track; keep whichever is higher, that level scaled
            // up or the one players see on the info screen.
            float oldXp = skills.experience(Profession.CLERIC);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.CLERIC)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.CLERIC);
            skills.setClericLevelAtLeast(Math.max(shownLevel,
                    oldLevel * com.alex.test.village.villagers.cleric.ClericBalance.MAX_LEVEL
                            / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_CLERIC_SKILL_VERSION, 1);
        }
        if (!npc.getBrainData().contains(TAG_BUTCHER_SKILL_VERSION)) {
            // Butchers earned only the legacy 0..10 per-shift trickle before
            // their 1..100 track; keep the higher of that scaled up and the shown level.
            float oldXp = skills.experience(Profession.BUTCHER);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.BUTCHER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.BUTCHER);
            skills.setButcherLevelAtLeast(Math.max(shownLevel,
                    oldLevel * com.alex.test.village.villagers.butcher.ButcherBalance.MAX_LEVEL
                            / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_BUTCHER_SKILL_VERSION, 1);
        }
        syncButcherSkill(npc);
        if (!npc.getBrainData().contains(TAG_RANCHER_SKILL_VERSION)) {
            // Ranchers earned only the legacy 0..10 per-shift trickle, which
            // changed nothing; they keep the level players see and drop the rest.
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.RANCHER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.RANCHER);
            skills.setRancherLevelAtLeast(shownLevel);
            npc.getBrainData().putInt(TAG_RANCHER_SKILL_VERSION, 1);
        }
        syncRancherSkill(npc);
        if (!npc.getBrainData().contains(TAG_TEACHER_SKILL_VERSION)) {
            // Teachers had only the legacy 0..10 trickle before their 1..100
            // track; keep the higher of that scaled up and the shown level.
            float oldXp = skills.experience(Profession.TEACHER);
            int oldLevel = Math.min(VillagerSkills.MAX_LEVEL,
                    (int) Math.sqrt(Math.max(0f, oldXp) / 5f));
            int shownLevel = npc.getProfessionSkills().stream()
                    .filter(slot -> slot.profession() == Profession.TEACHER)
                    .mapToInt(ProfessionSkill::level).findFirst().orElse(1);
            skills.clearExperience(Profession.TEACHER);
            skills.setTeacherLevelAtLeast(Math.max(shownLevel,
                    oldLevel * com.alex.test.village.villagers.teacher.TeacherBalance.MAX_LEVEL
                            / VillagerSkills.MAX_LEVEL));
            npc.getBrainData().putInt(TAG_TEACHER_SKILL_VERSION, 1);
        }
        syncTeacherSkill(npc);
        syncClericSkill(npc);
        memory.load(npc.getBrainData());
        restoredActivityName = npc.getBrainData().getString(TAG_CURRENT_ACTIVITY);
        loadEvening(npc);
        if (npc.getBrainData().contains(TAG_READING_DAY)) {
            readingLedger.load(npc.getBrainData().getLong(TAG_READING_DAY),
                    npc.getBrainData().getInt(TAG_READING_AWARDS));
        }
    }

    private static ProfessionSkill professionSlot(NpcEntity npc, Profession profession) {
        for (ProfessionSkill slot : npc.getProfessionSkills()) {
            if (slot.profession() == profession) return slot;
        }
        return null;
    }

    private void syncLumberjackSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.LUMBERJACK);
        if (slot == null) {
            skills.clearExperience(Profession.LUMBERJACK);
            return;
        }
        skills.setLumberjackLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.LUMBERJACK, lumberjackSkillLevel());
    }

    private void syncFarmerSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.FARMER);
        if (slot == null) {
            skills.clearExperience(Profession.FARMER);
            return;
        }
        skills.setFarmerLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.FARMER, farmerSkillLevel());
    }

    private void syncMinerSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.MINER);
        if (slot == null) {
            skills.clearExperience(Profession.MINER);
            return;
        }
        skills.setMinerLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.MINER, minerSkillLevel());
    }

    private void syncDruidSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.DRUID);
        if (slot == null) {
            skills.clearExperience(Profession.DRUID);
            return;
        }
        skills.setDruidLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.DRUID, druidSkillLevel());
    }

    private void syncCookSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.COOK);
        if (slot == null) {
            skills.clearExperience(Profession.COOK);
            return;
        }
        skills.setCookLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.COOK, cookSkillLevel());
    }

    private void syncBardSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.BARD);
        if (slot == null) {
            skills.clearExperience(Profession.BARD);
            return;
        }
        skills.setBardLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.BARD, bardSkillLevel());
    }

    private void syncGuardSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.GUARD);
        if (slot == null) {
            skills.clearExperience(Profession.GUARD);
            return;
        }
        skills.setGuardLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.GUARD, guardSkillLevel());
    }

    private void syncArcherSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.ARCHER);
        if (slot == null) {
            skills.clearExperience(Profession.ARCHER);
            return;
        }
        skills.setArcherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.ARCHER, archerSkillLevel());
    }

    private void syncMageSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.MAGE);
        if (slot == null) {
            skills.clearExperience(Profession.MAGE);
            return;
        }
        skills.setMageLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.MAGE, mageSkillLevel());
    }

    private void syncBlacksmithSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.BLACKSMITH);
        if (slot == null) {
            skills.clearExperience(Profession.BLACKSMITH);
            return;
        }
        skills.setBlacksmithLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.BLACKSMITH, blacksmithSkillLevel());
    }

    private void syncEnchanterSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.ENCHANTER);
        if (slot == null) {
            skills.clearExperience(Profession.ENCHANTER);
            return;
        }
        skills.setEnchanterLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.ENCHANTER, enchanterSkillLevel());
    }

    private void syncTeacherSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.TEACHER);
        if (slot == null) {
            skills.clearExperience(Profession.TEACHER);
            return;
        }
        skills.setTeacherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.TEACHER, teacherSkillLevel());
    }

    private void syncRancherSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.RANCHER);
        if (slot == null) {
            skills.clearExperience(Profession.RANCHER);
            return;
        }
        skills.setRancherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.RANCHER, rancherSkillLevel());
    }

    private void syncButcherSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.BUTCHER);
        if (slot == null) {
            skills.clearExperience(Profession.BUTCHER);
            return;
        }
        skills.setButcherLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.BUTCHER, butcherSkillLevel());
    }

    private void syncClericSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.CLERIC);
        if (slot == null) {
            skills.clearExperience(Profession.CLERIC);
            return;
        }
        skills.setClericLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.CLERIC, clericSkillLevel());
    }

    private void syncCaptainSkill(NpcEntity npc) {
        ProfessionSkill slot = professionSlot(npc, Profession.CAPTAIN);
        if (slot == null) {
            skills.clearExperience(Profession.CAPTAIN);
            return;
        }
        skills.setCaptainLevelAtLeast(slot.level());
        npc.setProfessionSkillLevel(Profession.CAPTAIN, captainSkillLevel());
    }

    /** A replaced profession slot loses its saved XP as well as its displayed level. */
    public void forgetProfessionExperience(Profession profession, NpcEntity npc) {
        // A brain that has not loaded yet must not save its empty in-memory map
        // over the other profession tracks already stored on the entity.
        if (!loaded) return;
        skills.clearExperience(profession);
        skills.save(npc.getBrainData());
    }

    private String shortId() { return model.npcId.toString().substring(0, 8); }
}
