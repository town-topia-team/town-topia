package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.archer.settings.ArcherSettingsSchema;
import com.alex.test.village.villagers.guard.CaptainBalance;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardSkillEffects;
import com.alex.test.village.villagers.guard.CombatRank;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.behavior.combat.GuardCombatRules;
import com.alex.test.village.villagers.guard.behavior.combat.StrikeSchedule;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight, server-side warning and assignment hub for Guard villagers.
 *
 * <p>The system keeps a small watched-threat list per village. Villagers and
 * target-change events add watched monsters; guards only treat a watched monster
 * as active when it is still close to a protected NPC. This avoids the old
 * "monster anywhere inside the village border" behavior and keeps the expensive
 * monster scans throttled and local.
 */
public final class GuardAlertSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final double VILLAGER_SCAN_RADIUS = 10.0;
    private static final double ACTIVE_THREAT_RADIUS = 8.0;
    /**
     * An archer shoots from a band its combat rules will walk 18 blocks to
     * reach (ArcherCombatRules.APPROACH_XZ_RADIUS), so it has to notice a
     * hostile from at least that far out. Scanning only as far as a melee
     * guard left archers unable to use the range they exist for.
     */
    private static final double ARCHER_SCAN_RADIUS = 18.0;
    private static final double ARCHER_SCAN_RADIUS_SQ = ARCHER_SCAN_RADIUS * ARCHER_SCAN_RADIUS;
    private static final double ACTIVE_THREAT_RADIUS_SQ = ACTIVE_THREAT_RADIUS * ACTIVE_THREAT_RADIUS;
    private static final double CLOSE_NO_LOS_RADIUS_SQ = 4.0 * 4.0;
    /** cos(60°): villagers see 120° wide, centred on where their head points. */
    private static final double VILLAGER_VIEW_CONE_COS = 0.5D;
    // Damage events are confirmed threats, so every available guard in the same
    // village may respond even when the attacker is on the other side of town.
    // This fallback radius is only used for guards that are already outside the
    // village bounds, for example after being pushed or fleeing.
    private static final double VILLAGE_WIDE_ALERT_REACTION_RADIUS = 192.0;
    private static final double VILLAGE_WIDE_ALERT_REACTION_RADIUS_SQ =
            VILLAGE_WIDE_ALERT_REACTION_RADIUS * VILLAGE_WIDE_ALERT_REACTION_RADIUS;
    private static final double FAR_FROM_PROTECTED_NPC_SQ = 48.0 * 48.0;

    private static final int WATCH_TTL_TICKS = 200;
    private static final int ACTIVE_TTL_TICKS = 200;
    private static final int DAMAGE_CONFIRMED_ACTIVE_TICKS = 600;
    private static final int ACTIVE_RECHECK_INTERVAL_TICKS = 10;
    private static final int LOCAL_SCAN_INTERVAL_TICKS = 10;
    /** Guards look around twice as often as other villagers: spotting is their job. */
    private static final int GUARD_LOCAL_SCAN_INTERVAL_TICKS = 5;
    private static final int VILLAGER_ALERT_COOLDOWN_TICKS = 40;
    private static final int MONSTER_ALERT_COOLDOWN_TICKS = 20;
    // Rebalanced every half second (was 1.5 s) so a fresh mob gets its own guard quickly.
    private static final int ASSIGNMENT_REBALANCE_INTERVAL_TICKS = 10;
    private static final int GUARD_SWITCH_COOLDOWN_TICKS = 20;
    // Guards are spread over the mobs by load -- guards on a mob against what it
    // can take (StrikeSchedule.maxGuardsOn: two for an ordinary mob). The limit is
    // soft: when every mob is full the extra guards go to the least loaded one.
    private static final double RECENTLY_FAILED_ASSIGNMENT_PENALTY = 28_000.0D;
    /** One full step of load outweighs any distance or danger difference. */
    private static final double LOAD_WEIGHT = 1_000_000.0D;
    /** Danger only breaks ties between mobs carrying the same load. */
    private static final double THREAT_TIE_BREAK_WEIGHT = 20.0D;

    /** Village id -> its danger list, keyed by mob id (in the order first reported). */
    private static final Map<UUID, Map<UUID, Threat>> THREATS_BY_VILLAGE = new HashMap<>();
    private static final Map<UUID, Long> NEXT_VILLAGER_SCAN = new HashMap<>();
    private static final Map<UUID, Long> NEXT_GUARD_SCAN = new HashMap<>();
    private static final Map<UUID, Long> NEXT_VILLAGER_ALERT = new HashMap<>();
    private static final Map<UUID, Long> NEXT_MONSTER_ALERT = new HashMap<>();
    private static final Map<UUID, Long> NEXT_ASSIGNMENT_REBALANCE = new HashMap<>();
    /** Game tick the guard plan last ran per village: at most once a tick. */
    private static final Map<UUID, Long> REBALANCED_AT = new HashMap<>();
    private static final Map<UUID, Long> NEXT_GUARD_SWITCH_ALLOWED = new HashMap<>();
    // Game tick cleanup last ran for each village: every defender's three public
    // entries used to walk the whole threat list again in the same tick.
    private static final Map<UUID, Long> CLEANUP_DONE_AT = new HashMap<>();

    // Timing of the per-village rebalance; one INFO line when it took this long or more.
    private static final long SLOW_REBALANCE_LOG_NANOS = 5_000_000L;
    /** Guard plans run, and their time, since {@link #takePlanCost} last read them ({@code [ServerCost]}). */
    private static int planRuns;
    private static long planNanos;
    /** Nanos spent in GuardPostRegistry.nearestPostDistanceSqr during the current refreshAssignmentPlan. */
    private static long postScoreNanos;
    /** Threats scored by the current refreshAssignmentPlan. */
    private static int scoredThreats;

    /** Guard UUID -> monsters it has stepped aside from, to the game time it may come back. */
    private static final Map<UUID, Map<UUID, Long>> STEPPED_ASIDE = new HashMap<>();

    /** Guard UUID -> monster UUID. A monster may have multiple assigned guards. */
    private static final Map<UUID, UUID> GUARD_ASSIGNMENTS = new HashMap<>();

    /**
     * Defenders last seen taking a mob as archers. An archer shoots from range,
     * so it never fills one of the melee places a mob has room for; archers are
     * counted against their own, separate limit ({@link #ARCHERS_PER_MOB}).
     */
    private static final Set<UUID> RANGED_DEFENDERS = new HashSet<>();
    /** Archers one mob can take before more go to the next mob. */
    private static final int ARCHERS_PER_MOB = 3;

    private enum ProtectedKind { NPC, PLAYER }

    public record GuardTarget(
            @Nullable LivingEntity entity,
            BlockPos lastKnownMonsterPos,
            BlockPos threatenedVillagerPos,
            boolean fromAlert) {}

    private static final class Threat {
        private final UUID monsterId;
        private UUID reportedByNpcId;
        private BlockPos monsterPos;
        private BlockPos protectedNpcPos;
        private ProtectedKind protectedKind;
        private long expiresAt;
        private long lastActiveCheckAt;
        private boolean active;
        private long damageConfirmedUntil;
        /** Until when this mob counts as hurting people: a villager who is not a defender, or a player. */
        private long hurtPeopleUntil;
        /** The last player it hurt (kept when villagers take the mob over), and until when that hit counts. */
        @Nullable private UUID playerId;
        private long playerUntil = Long.MIN_VALUE;
        private double threatScore = 1.0D;
        /** Guards this mob can take before more only queue behind them; see StrikeSchedule. */
        private int maxGuards = StrikeSchedule.REGULAR_MAX_GUARDS;
        /** Last time this threat put the defenders around it on alert. */
        private long lastAlertSpreadAt = Long.MIN_VALUE / 2;
        private final Set<UUID> assignedGuardIds = new HashSet<>();
        /** Defenders its team wants, as the last plan worked it out (teams only). */
        private int teamSize = 2;
        /** When the first defender was sent to it; a long fight calls one more. */
        private long teamSince = Long.MIN_VALUE;
        /** Until when its team has one more: someone on it fell back or died. */
        private long backupUntil = Long.MIN_VALUE;
        private long approachCacheGraphVersion = Long.MIN_VALUE;
        @Nullable private BlockPos approachCacheMonsterPos;
        @Nullable private BlockPos approachCacheProtectedPos;
        @Nullable private BlockPos approachCacheResult;

        private Threat(UUID monsterId, UUID reportedByNpcId, BlockPos monsterPos,
                       BlockPos protectedNpcPos, ProtectedKind protectedKind, long expiresAt) {
            this.monsterId = monsterId;
            this.reportedByNpcId = reportedByNpcId;
            this.monsterPos = monsterPos;
            this.protectedNpcPos = protectedNpcPos;
            this.protectedKind = protectedKind;
            this.expiresAt = expiresAt;
        }
    }

    private GuardAlertSystem() {}

    /** Called by non-Guard villagers from brain overrides. Returns the seen threat. */
    @Nullable
    public static LivingEntity detectThreatForVillager(Activity.Context ctx) {
        return detectThreatForVillager(ctx.mgr(), ctx.level(), ctx.npc());
    }

    /** Brainless variant for travellers (merchants, nomads) standing in a village. */
    @Nullable
    public static LivingEntity detectThreatForVillager(
            VillageManager manager, ServerLevel level, NpcEntity villager) {
        if (villager.getProfession().isGuardLike()) return null;

        long now = level.getGameTime();
        UUID villagerId = villager.getNpcId();
        if (now < NEXT_VILLAGER_SCAN.getOrDefault(villagerId, 0L)) return null;
        NEXT_VILLAGER_SCAN.put(villagerId, now + LOCAL_SCAN_INTERVAL_TICKS);

        // Pruning the shared threat list looks up every threat's entity, so it
        // shares this villager's scan cooldown instead of running every tick.
        // Guards still prune it on each of their own ticks.
        cleanup(manager, level, now);

        LivingEntity threat = reportLocalThreats(manager, level, villager, VILLAGER_SCAN_RADIUS, true);
        if (threat != null) {
            VillageDamageAlerts.announceSighting(manager, level, villager, threat);
        }
        return threat;
    }

    /** Adds or refreshes a danger report with anti-spam cooldowns. */
    public static void reportThreat(Activity.Context ctx, NpcEntity threatened, LivingEntity threat) {
        boolean allowed = threatened.getProfession().isGuardLike()
                ? GuardThreatRules.isHostileMonsterForGuard(threatened, threat)
                : GuardThreatRules.isHostileMonster(ctx.mgr(), threat);
        if (!allowed) return;
        reportThreat(ctx.mgr(), ctx.level(), threatened, threat, true);
    }

    /** Adds or refreshes a danger report from event handlers that do not own a brain context. */
    public static void reportThreat(
            VillageManager manager, ServerLevel level, NpcEntity threatened, LivingEntity threat) {
        if (!GuardThreatRules.isHostileMonster(manager, threat)) return;
        reportThreat(manager, level, threatened, threat, true);
    }

    /**
     * Adds an urgent report for confirmed damage. This is intentionally stronger
     * than a normal proximity scan: ranged mobs such as skeletons can hurt a
     * villager from outside the small local flee/scan radius, and the hit itself
     * proves that the mob is an active threat.
     */
    public static boolean reportDirectDamageThreat(
            Activity.Context ctx, NpcEntity threatened, LivingEntity threat) {
        return reportDirectDamageThreat(ctx.mgr(), ctx.level(), threatened, threat);
    }

    public static boolean reportDirectDamageThreat(
            VillageManager manager, ServerLevel level, NpcEntity threatened, LivingEntity threat) {
        boolean allowed = threatened.getProfession().isGuardLike()
                ? GuardThreatRules.isHostileMonsterForGuard(threatened, threat)
                : GuardThreatRules.isHostileMonster(manager, threat);
        if (!allowed) return false;

        reportThreat(manager, level, threatened, threat, true, true);
        return true;
    }

    private static void reportThreat(
            VillageManager manager,
            ServerLevel level,
            NpcEntity threatened,
            LivingEntity threat,
            boolean prevalidated) {
        reportThreat(manager, level, threatened, threat, prevalidated, false);
    }

    private static void reportThreat(
            VillageManager manager,
            ServerLevel level,
            NpcEntity threatened,
            LivingEntity threat,
            boolean prevalidated,
            boolean damageConfirmed) {
        if (!prevalidated) return;
        long now = level.getGameTime();
        cleanup(manager, level, now);

        UUID npcId = threatened.getNpcId();
        UUID monsterId = threat.getUUID();
        Threat existing = threatById(manager, monsterId);

        boolean npcReady = now >= NEXT_VILLAGER_ALERT.getOrDefault(npcId, 0L);
        boolean monsterReady = now >= NEXT_MONSTER_ALERT.getOrDefault(monsterId, 0L);
        if (existing == null && !npcReady && !monsterReady) return;

        NEXT_VILLAGER_ALERT.put(npcId, now + VILLAGER_ALERT_COOLDOWN_TICKS);
        NEXT_MONSTER_ALERT.put(monsterId, now + MONSTER_ALERT_COOLDOWN_TICKS);

        // What the guard plan reads, before this report changes it (see the end).
        boolean wasActive = existing != null && existing.active;
        boolean wasHurting = existing != null && now <= existing.hurtPeopleUntil;
        ProtectedKind wasKind = existing != null ? existing.protectedKind : null;
        // A mob hurting the player stays the player's until it is after villagers too
        // (a villager hit here, or the check below). A villager merely seeing it used to
        // make it a villager's mob, judged by villagers only: guards dropped it until the
        // player's next hit, and the re-check flipped it back (a new guard plan each time).
        boolean keepPlayer = existing != null && existing.protectedKind == ProtectedKind.PLAYER && !damageConfirmed;

        Threat threatEntry = existing;
        if (threatEntry == null) {
            threatEntry = new Threat(
                    monsterId,
                    npcId,
                    threat.blockPosition(),
                    threatened.blockPosition(),
                    ProtectedKind.NPC,
                    now + (damageConfirmed ? DAMAGE_CONFIRMED_ACTIVE_TICKS : WATCH_TTL_TICKS));
            threatMap(manager).put(monsterId, threatEntry);
        } else {
            threatEntry.monsterPos = threat.blockPosition();
            if (!keepPlayer) {
                threatEntry.reportedByNpcId = npcId;
                threatEntry.protectedNpcPos = threatened.blockPosition();
                threatEntry.protectedKind = ProtectedKind.NPC;
            }
            threatEntry.expiresAt = Math.max(threatEntry.expiresAt,
                    now + (damageConfirmed ? DAMAGE_CONFIRMED_ACTIVE_TICKS : WATCH_TTL_TICKS));
        }

        if (damageConfirmed) {
            threatEntry.damageConfirmedUntil = Math.max(
                    threatEntry.damageConfirmedUntil,
                    now + DAMAGE_CONFIRMED_ACTIVE_TICKS);
            threatEntry.expiresAt = Math.max(
                    threatEntry.expiresAt,
                    now + DAMAGE_CONFIRMED_ACTIVE_TICKS);
            // A defender taking a hit is a fight; anyone else taking one is an attack.
            if (!threatened.getProfession().isDefender()) {
                threatEntry.hurtPeopleUntil = Math.max(threatEntry.hurtPeopleUntil, now + HURT_PEOPLE_TICKS);
            }
        }

        // A mob already known as dangerous and checked within the last re-check
        // interval stays so: in a crowd fight every guard reports every mob it sees,
        // and each report looked over every villager (with sight lines) again.
        // The regular 10-tick re-check (refreshThreatIfDue) still runs.
        if (!(wasActive && now - threatEntry.lastActiveCheckAt < ACTIVE_RECHECK_INTERVAL_TICKS)) {
            boolean forVillagers = damageConfirmed || isActiveThreat(manager, level, threat, threatened);
            if (!keepPlayer) {
                threatEntry.lastActiveCheckAt = now;
                threatEntry.active = forVillagers;
            } else if (forVillagers) {
                // After villagers as well: every guard's mob now, not only Protect Player guards'.
                threatEntry.lastActiveCheckAt = now;
                threatEntry.active = true;
                threatEntry.reportedByNpcId = npcId;
                threatEntry.protectedNpcPos = threatened.blockPosition();
                threatEntry.protectedKind = ProtectedKind.NPC;
            }
            // Otherwise the player side stays as the regular re-check judged it.
        }
        if (existing == null) {
            // First check done just above. A wave reported in one tick would re-check
            // in one tick too; phase this monster's next 10-tick check by 0..7 ticks.
            threatEntry.lastActiveCheckAt = now - Math.floorMod(monsterId.hashCode(), 8);
        }
        if (threatEntry.active) {
            threatEntry.expiresAt = Math.max(threatEntry.expiresAt, now + ACTIVE_TTL_TICKS);
            spreadAlert(manager, level, threatEntry, threat.blockPosition(), now);
        }
        // A new plan only when something it reads changed: a new mob, one that turned
        // dangerous (or calm), started hurting people, or changed whom it threatens.
        // A repeat sighting only moves the mob's spot; the plan's own 10-tick round
        // picks that up. Every sighting used to ask for a new plan, and the next
        // defender's tick ran it: 30-50 plans a tick in a crowd fight.
        if (existing == null || threatEntry.active != wasActive
                || (!wasHurting && now <= threatEntry.hurtPeopleUntil)
                || threatEntry.protectedKind != wasKind) {
            scheduleAssignmentRefresh(manager, now);
        }
    }

    /** Adds a player-protection report. The player must already be inside this village. */
    public static void reportPlayerThreat(
            VillageManager manager, ServerLevel level, ServerPlayer player, LivingEntity threat) {
        if (!GuardThreatRules.isHostileMonster(manager, threat)) return;

        long now = level.getGameTime();
        cleanup(manager, level, now);

        UUID playerId = player.getUUID();
        UUID monsterId = threat.getUUID();
        Threat existing = threatById(manager, monsterId);

        boolean playerReady = now >= NEXT_VILLAGER_ALERT.getOrDefault(playerId, 0L);
        boolean monsterReady = now >= NEXT_MONSTER_ALERT.getOrDefault(monsterId, 0L);
        if (existing == null && !playerReady && !monsterReady) return;

        NEXT_VILLAGER_ALERT.put(playerId, now + VILLAGER_ALERT_COOLDOWN_TICKS);
        NEXT_MONSTER_ALERT.put(monsterId, now + MONSTER_ALERT_COOLDOWN_TICKS);

        boolean wasActive = existing != null && existing.active;
        boolean wasHurting = existing != null && now <= existing.hurtPeopleUntil;
        ProtectedKind wasKind = existing != null ? existing.protectedKind : null;
        // A mob already after villagers stays every guard's mob; the player's hit is kept
        // beside it (playerId) and counts once the villagers are safe.
        boolean keepVillagers = wasActive && wasKind == ProtectedKind.NPC;

        Threat threatEntry = existing;
        if (threatEntry == null) {
            threatEntry = new Threat(
                    monsterId,
                    playerId,
                    threat.blockPosition(),
                    player.blockPosition(),
                    ProtectedKind.PLAYER,
                    now + DAMAGE_CONFIRMED_ACTIVE_TICKS);
            threatMap(manager).put(monsterId, threatEntry);
        } else {
            threatEntry.monsterPos = threat.blockPosition();
            if (!keepVillagers) {
                threatEntry.reportedByNpcId = playerId;
                threatEntry.protectedNpcPos = player.blockPosition();
                threatEntry.protectedKind = ProtectedKind.PLAYER;
            }
            threatEntry.expiresAt = Math.max(threatEntry.expiresAt, now + ACTIVE_TTL_TICKS);
        }
        threatEntry.playerId = playerId;
        threatEntry.playerUntil = Math.max(threatEntry.playerUntil, now + DAMAGE_CONFIRMED_ACTIVE_TICKS);

        threatEntry.damageConfirmedUntil = Math.max(
                threatEntry.damageConfirmedUntil,
                now + DAMAGE_CONFIRMED_ACTIVE_TICKS);
        threatEntry.expiresAt = Math.max(
                threatEntry.expiresAt,
                now + DAMAGE_CONFIRMED_ACTIVE_TICKS);
        // Whether the player's own hits make a lookout ring the tower bell is the
        // village's rule (Mayor > Settings > Village rules): never, only when
        // badly hurt, or always. A player who likes fighting monsters in town
        // used to set the bell off every two minutes.
        com.alex.test.village.VillageRules.PlayerHurtBell bellRule = manager.rules().playerHurtBell();
        boolean ringsBell = bellRule == com.alex.test.village.VillageRules.PlayerHurtBell.ALWAYS
                || bellRule == com.alex.test.village.VillageRules.PlayerHurtBell.BADLY_HURT
                        && player.getHealth() <= player.getMaxHealth()
                                * com.alex.test.village.VillageRules.BADLY_HURT_HEALTH;
        if (ringsBell) {
            threatEntry.hurtPeopleUntil = Math.max(threatEntry.hurtPeopleUntil, now + HURT_PEOPLE_TICKS);
        }
        threatEntry.lastActiveCheckAt = now;
        threatEntry.active = true;
        spreadAlert(manager, level, threatEntry, threat.blockPosition(), now);
        // Same rule as reportThreat: a new plan only when the mob is new or changed.
        if (existing == null || !wasActive || threatEntry.protectedKind != wasKind
                || (!wasHurting && now <= threatEntry.hurtPeopleUntil)) {
            scheduleAssignmentRefresh(manager, now);
        }
    }

    // =========================================================================
    // Alert and the village alarm
    // =========================================================================

    /** A defender stays on alert this long after the last danger near it (30 s). */
    public static final int ALERT_HOLD_TICKS = 600;
    /** Defenders this close to a live threat go on alert. */
    private static final double ALERT_SPREAD_RADIUS = 24.0;
    private static final double ALERT_SPREAD_RADIUS_SQ = ALERT_SPREAD_RADIUS * ALERT_SPREAD_RADIUS;
    /** One threat refreshes the alert around it at most this often. */
    private static final int ALERT_SPREAD_INTERVAL_TICKS = 20;
    // On alert a guard or captain notices what it can see GuardBalance.ALERT_SPOT_BONUS
    // farther off (10 -> 14)...
    /** ...an archer this far (calm: 18)... */
    private static final double ALERT_ARCHER_SEEN_RADIUS = 24.0;
    /** ...and anything at all, seen or not, this close (calm: 4). */
    private static final double ALERT_UNSEEN_RADIUS = 8.0;

    /** Defender NPC id -> game time its alert runs out. */
    private static final Map<UUID, Long> ALERT_UNTIL = new HashMap<>();
    /** Village id -> game time its bell alarm runs out. */
    private static final Map<UUID, Long> VILLAGE_ALARM_UNTIL = new HashMap<>();
    private static final Map<UUID, Long> NEXT_ALARM_DANGER_CHECK = new HashMap<>();

    /** Whether {@code npc} is a defender on alert right now. */
    public static boolean isOnAlert(@Nullable LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || !npc.getProfession().isDefender()) return false;
        Long until = ALERT_UNTIL.get(npc.getNpcId());
        return until != null && until >= npc.level().getGameTime();
    }

    /** Keeps a fighting defender on alert: called while it has a target. */
    public static void keepAlert(NpcEntity defender, long now) {
        ALERT_UNTIL.put(defender.getNpcId(), now + ALERT_HOLD_TICKS);
    }

    /**
     * Puts every defender within {@link #ALERT_SPREAD_RADIUS} of a live threat on
     * alert, or keeps it there: alert lasts until {@link #ALERT_HOLD_TICKS} after
     * the last report of danger near it. Whoever reports the threat -- a guard's
     * own scan, a villager who saw it, a mob going for someone, a hit -- primes
     * every defender around it, not just the one that saw it.
     */
    private static void spreadAlert(VillageManager manager, ServerLevel level, Threat threat,
                                     BlockPos at, long now) {
        if (now - threat.lastAlertSpreadAt < ALERT_SPREAD_INTERVAL_TICKS) return;
        threat.lastAlertSpreadAt = now;
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || !npc.getProfession().isDefender()) continue;
            if (npc.blockPosition().distSqr(at) > ALERT_SPREAD_RADIUS_SQ) continue;
            alert(model, npc, level, now + ALERT_HOLD_TICKS);
        }
    }

    private static void alert(VillagerModel model, NpcEntity npc, ServerLevel level, long until) {
        Long previous = ALERT_UNTIL.put(npc.getNpcId(), Math.max(until,
                ALERT_UNTIL.getOrDefault(npc.getNpcId(), Long.MIN_VALUE)));
        if (previous == null || previous < level.getGameTime()) {
            model.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(level) + " GUARD on alert");
            model.brain().rememberAndShow(com.alex.test.village.social.MemoryTopic.DANGER,
                    com.alex.test.village.social.ThoughtIcon.EMOTE_WARNING, 40, level);
        }
        if (ALERT_UNTIL.size() > 256) {
            long now = level.getGameTime();
            ALERT_UNTIL.values().removeIf(t -> t < now);
        }
    }

    /**
     * The bell has been rung: every defender of the village goes on alert for
     * {@link #ALERT_HOLD_TICKS}, off-duty ones included, and every other
     * villager goes indoors ({@code ShelterActivity}). The alarm lasts that long
     * after the ring, and for as long after the last live threat as there is one.
     */
    public static void soundAlarm(VillageManager manager, ServerLevel level) {
        long now = level.getGameTime();
        long until = now + ALERT_HOLD_TICKS;
        VILLAGE_ALARM_UNTIL.merge(manager.getVillageId(), until, Math::max);
        // A bell rung by hand is a player's alarm, whoever rang first.
        TOWER_ALARMS.remove(manager.getVillageId());
        manager.raids().onAlarmSounded();
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || !npc.getProfession().isDefender()) continue;
            alert(model, npc, level, until);
        }
    }

    /** Villages whose alarm a watchtower lookout started (not a player at a bell). */
    private static final Set<UUID> TOWER_ALARMS = new HashSet<>();

    /**
     * A lookout rang its tower bell: the same alarm as a player's, except that
     * with the village rule "Alarm ends when the village is clear" (Mayor >
     * Settings > Village rules, on by default) it lasts only while monsters
     * are inside the village, not while any monster is near any villager. The
     * old way could keep the off-duty defenders out of bed all night.
     */
    public static void soundTowerAlarm(VillageManager manager, ServerLevel level) {
        soundAlarm(manager, level);
        TOWER_ALARMS.add(manager.getVillageId());
    }

    /** How long a hit on a villager or player keeps its mob counted as hurting people (10 s). */
    private static final int HURT_PEOPLE_TICKS = 200;

    /**
     * Hostiles inside the village that have just hurt a villager who is not a
     * defender, or a player: what a lookout looks for before ringing its bell.
     * A guard trading blows with a zombie does not count. The bell used to ring
     * for any three monsters inside the village, which most nights there are,
     * and the alarm then kept the off-duty defenders out of bed until morning.
     */
    public static int threatsHurtingPeopleInside(VillageManager manager, ServerLevel level) {
        long now = level.getGameTime();
        int count = 0;
        for (Threat threat : threats(manager)) {
            if (now <= threat.hurtPeopleUntil && now <= threat.expiresAt && threat.monsterPos != null
                    && manager.contains(threat.monsterPos)) count++;
        }
        return count;
    }

    /**
     * The live monsters the village knows of right now: inside its border, or
     * anywhere if they hurt one of its people in the last half minute (a
     * skeleton shooting in from outside). What the fight bar counts. A mob a
     * lookout sees beyond the border, that has hurt nobody, is not a fight.
     */
    public static List<LivingEntity> monstersInTown(VillageManager manager, ServerLevel level) {
        Map<UUID, Threat> threats = THREATS_BY_VILLAGE.get(manager.getVillageId());
        if (threats == null || threats.isEmpty()) return List.of();
        long now = level.getGameTime();
        List<LivingEntity> found = new ArrayList<>();
        for (Threat threat : threats.values()) {
            if (now > threat.expiresAt) continue;
            if (!(level.getEntity(threat.monsterId) instanceof LivingEntity monster) || !monster.isAlive()) continue;
            if (now > threat.damageConfirmedUntil && !manager.contains(monster.blockPosition())) continue;
            found.add(monster);
        }
        return found;
    }

    /** A lookout calls out at most this often. */
    private static final int CALL_OUT_TICKS = 100;
    /** Lookout NPC id -> game time it may call out again. */
    private static final Map<UUID, Long> NEXT_CALL_OUT = new HashMap<>();

    /**
     * A lookout up its watchtower has spotted a hostile: every defender of the
     * village who is on duty goes on alert, not only those near the mob, and
     * the lookout shows the warning icon. Anyone else spotting one does nothing
     * here. At most every few seconds per lookout.
     */
    private static void callOutIfLookout(VillageManager manager, ServerLevel level, NpcEntity spotter) {
        if (!com.alex.test.village.villagers.archer.behavior.WatchtowerDuty.isOnWatch(spotter)) return;
        long now = level.getGameTime();
        Long next = NEXT_CALL_OUT.get(spotter.getNpcId());
        if (next != null && now < next && next - now <= CALL_OUT_TICKS) return;
        NEXT_CALL_OUT.put(spotter.getNpcId(), now + CALL_OUT_TICKS);
        if (NEXT_CALL_OUT.size() > 256) NEXT_CALL_OUT.values().removeIf(t -> t < now);
        spotter.showBubble(com.alex.test.village.social.ThoughtIcon.EMOTE_WARNING, true, 40);
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || !npc.getProfession().isDefender()) continue;
            if (!(model.brain().currentActivity() instanceof com.alex.test.village.brain.WorkActivity)) continue;
            alert(model, npc, level, now + ALERT_HOLD_TICKS);
        }
    }

    /** Whether the village's bell alarm is still on; extends it while any threat is live. */
    public static boolean isVillageAlarmed(VillageManager manager, ServerLevel level) {
        // A raid's attack keeps everyone in for its whole length: the 30 s alarm used to
        // run out between waves, with the raiders still out past the houses. Only the
        // short attacks: a night-long alarm would keep everyone out of bed (holdsAlarm).
        if (manager.raids().holdsAlarm()) return true;
        UUID villageId = manager.getVillageId();
        Long until = VILLAGE_ALARM_UNTIL.get(villageId);
        long now = level.getGameTime();
        if (until == null) return false;
        if (until < now) {
            VILLAGE_ALARM_UNTIL.remove(villageId);
            NEXT_ALARM_DANGER_CHECK.remove(villageId);
            TOWER_ALARMS.remove(villageId);
            return false;
        }
        if (now >= NEXT_ALARM_DANGER_CHECK.getOrDefault(villageId, 0L)) {
            NEXT_ALARM_DANGER_CHECK.put(villageId, now + 10);
            // A lookout's alarm, with the village rule on: only monsters inside
            // the village keep it going (see soundTowerAlarm).
            boolean insideOnly = TOWER_ALARMS.contains(villageId) && manager.rules().towerAlarmEndsWhenClear();
            for (Threat threat : threats(manager)) {
                if (insideOnly && (threat.monsterPos == null || !manager.contains(threat.monsterPos))) continue;
                if (threat.active && now <= threat.expiresAt) {
                    VILLAGE_ALARM_UNTIL.put(villageId, now + ALERT_HOLD_TICKS);
                    break;
                }
            }
        }
        return true;
    }

    /** Per defender: {game time, entity tick count, answer} of its latest {@link #hasEmergencyForGuard}. */
    private static final Map<UUID, long[]> EMERGENCY_THIS_TICK = new HashMap<>();

    public static boolean hasEmergencyForGuard(Activity.Context ctx) {
        // The bell calls every defender out, on duty or not. Never held back by the answer below.
        if (isVillageAlarmed(ctx.mgr(), ctx.level())) return true;
        // The brain asks this up to three times a tick for the same defender
        // (overrides, work check, UI yield). Once is enough: the answer
        // cannot change within a tick, and a new one comes next tick.
        NpcEntity npc = ctx.npc();
        long now = ctx.level().getGameTime();
        long[] memo = EMERGENCY_THIS_TICK.computeIfAbsent(npc.getNpcId(), id -> new long[]{-1L, -1L, 0L});
        if (memo[0] == now && memo[1] == npc.tickCount) return memo[2] != 0L;
        boolean answer = computeEmergencyForGuard(ctx);
        memo[0] = now;
        memo[1] = npc.tickCount;
        memo[2] = answer ? 1L : 0L;
        return answer;
    }

    private static boolean computeEmergencyForGuard(Activity.Context ctx) {
        if (!GuardSettings.of(ctx.npc()).attackHostileMobs()) return false;

        long now = ctx.level().getGameTime();
        cleanup(ctx, now);
        if (usesTeams(ctx.mgr(), ctx.npc())) {
            boolean onDuty = com.alex.test.village.villagers.guard.GuardShift.scheduled(ctx, GuardSettings.of(ctx.npc()));
            if (onDuty || ctx.mgr().rules().defenderTeams().resting()
                    != com.alex.test.village.DefenderTeamRules.RestingCallOut.EVERY_FIGHT) {
                return hasTeamEmergency(ctx, onDuty, now);
            }
        }
        refreshAssignmentPlanIfDue(ctx, now);

        // A mob this defender has given up on as out of reach does not keep it
        // out: it could only stand there looking at it.
        for (Threat threat : threats(ctx)) {
            if (plainlyNotFor(ctx, ctx.npc(), threat)) continue;
            LivingEntity entity = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (entity != null
                    && threat.active
                    && !isStandingDownFrom(ctx.npc(), entity, now)) {
                return true;
            }
        }

        if (shouldRunGuardLocalScan(ctx, now)) {
            List<Mob> seen = localThreats(ctx.mgr(), ctx.level(), ctx.npc(), scanRadiusFor(ctx.npc()), false);
            for (Mob monster : seen) {
                reportThreat(ctx.mgr(), ctx.level(), ctx.npc(), monster, true);
            }
            if (!seen.isEmpty()) callOutIfLookout(ctx.mgr(), ctx.level(), ctx.npc());
            for (Mob monster : seen) {
                if (!isStandingDownFrom(ctx.npc(), monster, now)) return true;
            }
        }
        return false;
    }

    /** Finds the best monster/alert this guard should respond to right now. */
    @Nullable
    public static GuardTarget findBestTargetForGuard(Activity.Context ctx) {
        if (!GuardSettings.of(ctx.npc()).attackHostileMobs()) return null;

        long now = ctx.level().getGameTime();
        cleanup(ctx, now);
        NpcEntity guard = ctx.npc();
        UUID guardId = guard.getNpcId();
        if (usesTeams(ctx.mgr(), guard)) {
            // Counted as out hunting before the plan runs, so it can be sent.
            LAST_HUNT_POLL.put(guardId, now);
            refreshAssignmentPlanIfDue(ctx, now);
            return teamTargetForDefender(ctx, now);
        }
        refreshAssignmentPlanIfDue(ctx, now);

        Threat assigned = assignedThreatForGuard(ctx, guardId, now);
        if (assigned != null) {
            Entity entity = ctx.level().getEntity(assigned.monsterId);
            LivingEntity monster = GuardThreatRules.isHostileMonsterForGuard(guard, entity)
                    ? (LivingEntity) entity
                    : null;
            if (monster != null && assigned.active) {
                return new GuardTarget(monster, assigned.monsterPos, assigned.protectedNpcPos, true);
            }
            releaseGuardAssignment(ctx, guardId, assigned.monsterId);
        }

        if (shouldRunGuardLocalScan(ctx, now)) {
            LivingEntity seen = reportLocalThreats(ctx.mgr(), ctx.level(), guard, scanRadiusFor(guard), false);
            if (seen != null) callOutIfLookout(ctx.mgr(), ctx.level(), guard);
        }

        Threat best = bestThreatForGuard(ctx, guard, now);
        if (best == null) return null;

        noteRole(guard);
        best.assignedGuardIds.add(guardId);
        GUARD_ASSIGNMENTS.put(guardId, best.monsterId);
        NEXT_GUARD_SWITCH_ALLOWED.put(guardId, now + GUARD_SWITCH_COOLDOWN_TICKS);

        Entity entity = ctx.level().getEntity(best.monsterId);
        LivingEntity monster = GuardThreatRules.isHostileMonsterForGuard(guard, entity)
                ? (LivingEntity) entity
                : null;
        return new GuardTarget(monster, best.monsterPos, best.protectedNpcPos, true);
    }

    /**
     * The nearest live mob on the village's danger list that this defender may
     * fight, or null. While the bell rings that includes a mob hurting a
     * player, which otherwise only defenders set to protect the player take.
     * For a defender called out with no mob given to it, to run towards:
     * the bell called every archer out, but one far from the fight got no mob
     * and stood at its guard post.
     */
    @Nullable
    public static LivingEntity nearestDanger(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        cleanup(ctx, now);
        NpcEntity defender = ctx.npc();
        boolean alarmed = isVillageAlarmed(ctx.mgr(), ctx.level());
        BlockPos at = defender.blockPosition();
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Threat threat : threats(ctx)) {
            // Field tests before the mob is looked up (see plainlyNotFor), and one
            // noted well past the nearest so far cannot be nearer.
            if (!threat.active
                    || !(alarmed || canGuardRespondToThreat(ctx, threat))
                    || guardTooFarFromThreat(ctx.mgr(), defender, threat)
                    || notedFartherThan(at, threat, bestSq)) continue;
            LivingEntity entity = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (entity == null
                    || !threat.active
                    || !GuardThreatRules.isHostileMonsterForGuard(defender, entity)
                    || isGivenUp(defender, entity, now)) continue;
            double d = defender.distanceToSqr(entity);
            if (d < bestSq) {
                bestSq = d;
                best = entity;
            }
        }
        return best;
    }

    public static void clearTarget(Activity.Context ctx, UUID guardId, @Nullable UUID monsterId) {
        if (monsterId == null) {
            UUID assignedMonster = GUARD_ASSIGNMENTS.remove(guardId);
            PLANNED.remove(guardId);
            if (assignedMonster == null) return;
            monsterId = assignedMonster;
        } else if (monsterId.equals(GUARD_ASSIGNMENTS.get(guardId))) {
            GUARD_ASSIGNMENTS.remove(guardId);
            PLANNED.remove(guardId);
        }

        for (Threat threat : threats(ctx)) {
            if (threat.monsterId.equals(monsterId)) {
                threat.assignedGuardIds.remove(guardId);
            }
        }
    }

    @Nullable
    private static Threat assignedThreatForGuard(Activity.Context ctx, UUID guardId, long now) {
        UUID monsterId = GUARD_ASSIGNMENTS.get(guardId);
        if (monsterId == null) return null;
        Threat threat = threatById(ctx.mgr(), monsterId);
        if (threat == null) {
            GUARD_ASSIGNMENTS.remove(guardId);
            return null;
        }
        if (!threat.assignedGuardIds.contains(guardId)) {
            threat.assignedGuardIds.add(guardId);
        }
        LivingEntity entity = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
        if (entity == null
                || !GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), entity)
                || !threat.active
                || !canGuardRespondToThreat(ctx, threat)
                || guardTooFarFromThreat(ctx.mgr(), ctx.npc(), threat)) {
            releaseGuardAssignment(ctx, guardId, monsterId);
            return null;
        }
        if (isSteppingAside(guardId, monsterId, now) || shouldSwitchFromAssigned(ctx, threat, now)) {
            releaseGuardAssignment(ctx, guardId, monsterId);
            return null;
        }
        return threat;
    }

    /**
     * Takes a guard off a mob that already has as many guards swinging at it as
     * it can take, and keeps it off for {@code ticks}, so it goes for another
     * mob instead of standing behind the two that are fighting this one.
     */
    public static void stepAside(Activity.Context ctx, UUID guardId, UUID monsterId, int ticks) {
        clearTarget(ctx, guardId, monsterId);
        long now = ctx.level().getGameTime();
        Map<UUID, Long> avoided = STEPPED_ASIDE.computeIfAbsent(guardId, id -> new HashMap<>());
        avoided.values().removeIf(until -> until < now);
        avoided.put(monsterId, now + ticks);
    }

    private static boolean isSteppingAside(UUID guardId, UUID monsterId, long now) {
        Map<UUID, Long> avoided = STEPPED_ASIDE.get(guardId);
        if (avoided == null) return false;
        Long until = avoided.get(monsterId);
        return until != null && until >= now;
    }

    /** A guard tries a mob it found no spot to strike from again after this long (20 s). */
    public static final int GUARD_UNREACHABLE_RETRY_TICKS = 400;
    /**
     * A mob a defender has given up on does not call it out off duty for this
     * long (2 min); see {@link #isStandingDownFrom}. Shorter, and a guard resting
     * at home is pulled out of bed that much more often to look at a skeleton on
     * a roof it still cannot reach; on duty it changes nothing.
     */
    private static final int GIVEN_UP_QUIET_TICKS = 2400;
    /** A given-up mob that moves this far from where it was given up may be reachable now. */
    private static final double UNREACHABLE_MOVED_SQ = 3.0D * 3.0D;

    private record GivenUp(BlockPos at, long retryAt, long quietUntil) {}

    /** Defender UUID -> mobs it gave up on as out of reach, with where each stood then. */
    private static final Map<UUID, Map<UUID, GivenUp>> GIVEN_UP = new HashMap<>();

    /**
     * {@code defender} could not get at {@code monster}: a guard found no spot
     * to strike it from, an archer went too long without a shot. Without this
     * the planner handed the same mob straight back, and the defender stood
     * still retrying it all night: a skeleton on a roof or in a pit froze every
     * guard in the village until it died or the game was set to Peaceful.
     */
    public static void giveUpUnreachable(Activity.Context ctx, UUID defenderId, LivingEntity monster,
                                         int retryTicks) {
        clearTarget(ctx, defenderId, monster.getUUID());
        long now = ctx.level().getGameTime();
        Map<UUID, GivenUp> mobs = GIVEN_UP.computeIfAbsent(defenderId, id -> new HashMap<>());
        mobs.values().removeIf(g -> g.quietUntil() < now);
        mobs.put(monster.getUUID(), new GivenUp(monster.blockPosition(), now + retryTicks,
                now + Math.max(retryTicks, GIVEN_UP_QUIET_TICKS)));
    }

    /**
     * Whether {@code defender} is still leaving {@code monster} alone as out of
     * reach. It takes the mob again once its retry time is up, once the mob has
     * moved off the spot it could not be reached on, or once it can hit it from
     * where it stands -- a guard within sword reach, an archer with a clear shot.
     * Being merely seen, or nearby, is not enough: the mob on the roof was in
     * sight and near all along.
     */
    public static boolean isGivenUp(NpcEntity defender, LivingEntity monster, long now) {
        GivenUp givenUp = givenUp(defender, monster, now);
        return givenUp != null && now < givenUp.retryAt();
    }

    /**
     * Whether {@code monster} no longer counts as an emergency for this
     * defender: it gave the mob up within {@link #GIVEN_UP_QUIET_TICKS}, and the
     * mob has neither moved nor come within its reach since. An off-duty
     * defender then goes back to its day instead of standing out by the mob.
     */
    private static boolean isStandingDownFrom(NpcEntity defender, LivingEntity monster, long now) {
        return givenUp(defender, monster, now) != null;
    }

    @Nullable
    private static GivenUp givenUp(NpcEntity defender, LivingEntity monster, long now) {
        Map<UUID, GivenUp> mobs = GIVEN_UP.get(defender.getNpcId());
        if (mobs == null) return null;
        GivenUp givenUp = mobs.get(monster.getUUID());
        if (givenUp == null) return null;
        if (givenUp.quietUntil() < now
                || monster.blockPosition().distSqr(givenUp.at()) >= UNREACHABLE_MOVED_SQ
                || canHitFromHere(defender, monster)) {
            mobs.remove(monster.getUUID());
            return null;
        }
        return givenUp;
    }

    private static boolean canHitFromHere(NpcEntity defender, LivingEntity monster) {
        if (defender.getProfession() == Profession.MAGE) {
            double range = com.alex.test.village.villagers.mage.MageBalance.CAST_RANGE;
            return com.alex.test.village.villagers.archer.behavior.combat.ArcherCombatRules.canShoot(
                    defender, monster, 0.0D, range * range);
        }
        if (isRanged(defender)) {
            double range = com.alex.test.village.villagers.archer.ArcherBalance.maxShootRange(
                    com.alex.test.village.villagers.archer.ArcherBowUtils.skillLevel(defender));
            return com.alex.test.village.villagers.archer.behavior.combat.ArcherCombatRules.canShoot(
                    defender, monster, 0.0D, range * range);
        }
        return GuardCombatRules.canMeleeAttack(defender, monster,
                CombatRank.of(defender.getProfession()).swingReach(GuardSkillEffects.activeLevel(defender)));
    }

    @Nullable
    private static Threat bestThreatForGuard(Activity.Context ctx, NpcEntity guard, long now) {
        Threat best = null;
        double bestScore = Double.MAX_VALUE;

        for (Threat threat : threats(ctx)) {
            if (plainlyNotFor(ctx, guard, threat)
                    || isSteppingAside(guard.getNpcId(), threat.monsterId, now)) continue;
            LivingEntity entity = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (entity == null
                    || !GuardThreatRules.isHostileMonsterForGuard(guard, entity)
                    || !threat.active
                    || isGivenUp(guard, entity, now)) continue;

            pruneAssignments(ctx, threat);
            int assigned = slotsTaken(threat, guard);
            double score = assignmentScore(ctx, ctx.model(), guard, threat, assigned);
            if (score < bestScore) {
                bestScore = score;
                best = threat;
            }
        }
        return best;
    }

    private static void refreshAssignmentPlanIfDue(Activity.Context ctx, long now) {
        UUID villageId = ctx.mgr().getVillageId();
        if (now < NEXT_ASSIGNMENT_REBALANCE.getOrDefault(villageId, 0L)) return;
        // At most once a tick: a plan asked for after it ran waits for the next tick.
        Long ranAt = REBALANCED_AT.put(villageId, now);
        if (ranAt != null && ranAt == now) return;
        NEXT_ASSIGNMENT_REBALANCE.put(villageId, now + ASSIGNMENT_REBALANCE_INTERVAL_TICKS);
        long startedAt = System.nanoTime();
        refreshAssignmentPlan(ctx.mgr(), ctx.level(), now);
        long scoredAt = System.nanoTime();
        planTeams(ctx.mgr(), ctx.level(), now);
        long doneAt = System.nanoTime();
        planRuns++;
        planNanos += doneAt - startedAt;
        if (doneAt - startedAt >= SLOW_REBALANCE_LOG_NANOS) {
            LOGGER.info("[GuardAlert {}] slow rebalance {} ms: threats={} score={} ms (posts={} ms) teams={} ms defenders={}",
                    villageId.toString().substring(0, 8), millis(doneAt - startedAt), scoredThreats,
                    millis(scoredAt - startedAt), millis(postScoreNanos), millis(doneAt - scoredAt),
                    countAliveDefenders(ctx.mgr()));
        }
    }

    /** {runs, nanos} of the guard plan since the last call, then both start over. Server thread. */
    public static long[] takePlanCost() {
        long[] cost = {planRuns, planNanos};
        planRuns = 0;
        planNanos = 0L;
        return cost;
    }

    /** Nanos as milliseconds with one decimal, for the timing lines. */
    private static double millis(long nanos) {
        return nanos / 100_000L / 10.0D;
    }

    private static int countAliveDefenders(VillageManager manager) {
        int count = 0;
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive() && npc.getProfession().isDefender()) count++;
        }
        return count;
    }

    private static void scheduleAssignmentRefresh(VillageManager manager, long now) {
        NEXT_ASSIGNMENT_REBALANCE.put(manager.getVillageId(), now);
    }

    /** Keeps each active threat's danger score and the guards it can take up to date. */
    private static void refreshAssignmentPlan(VillageManager manager, ServerLevel level, long now) {
        postScoreNanos = 0L;
        scoredThreats = 0;
        for (Threat threat : threats(manager)) {
            LivingEntity entity = refreshThreatIfDue(manager, level, threat, now);
            if (entity == null || !threat.active) continue;

            pruneAssignments(manager, threat, now);
            scoredThreats++;
            threat.threatScore = scoreThreat(manager, level, threat, entity);
            threat.maxGuards = StrikeSchedule.maxGuardsOn(entity);
        }
    }

    private static double scoreThreat(VillageManager manager, ServerLevel level, Threat threat, LivingEntity monster) {
        double score = 100.0D;
        score += Math.min(80.0D, monster.getMaxHealth() * 1.6D);

        double protectedDist = Math.sqrt(Math.max(0.0D, monster.blockPosition().distSqr(threat.protectedNpcPos)));
        score += proximityScore(protectedDist, 12.0D, 120.0D);

        double nearestNpcDist = Math.sqrt(nearestProtectedNpcDistanceSqr(manager, monster.blockPosition()));
        score += proximityScore(nearestNpcDist, 10.0D, 90.0D);
        if (nearestNpcDist <= 4.5D) score += 55.0D;

        double nearestPlayerDist = nearestProtectedPlayerDistance(manager, level, monster.blockPosition());
        if (nearestPlayerDist < Double.MAX_VALUE) {
            score += proximityScore(nearestPlayerDist, 14.0D, 90.0D);
        }

        double nearestBuildingDist = nearestBuildingDistance(manager, monster.blockPosition());
        if (nearestBuildingDist < Double.MAX_VALUE) {
            score += proximityScore(nearestBuildingDist, 18.0D, 70.0D);
        }

        long postLookupStart = System.nanoTime();
        double nearestPostDistSqr =
                GuardPostRegistry.get(level).nearestPostDistanceSqr(level, manager, monster.blockPosition());
        postScoreNanos += System.nanoTime() - postLookupStart;
        if (nearestPostDistSqr < Double.MAX_VALUE) {
            double nearestPostDist = Math.sqrt(nearestPostDistSqr);
            score += proximityScore(nearestPostDist, GuardPostRegistry.PATROL_RADIUS + 4.0D, 85.0D);
            if (nearestPostDist <= GuardPostRegistry.PATROL_RADIUS) score += 40.0D;
        }

        if (monster instanceof Mob mob && mob.getTarget() != null) {
            LivingEntity target = mob.getTarget();
            if (target instanceof NpcEntity || target instanceof ServerPlayer) score += 45.0D;
        }

        if (threat.assignedGuardIds.isEmpty()) score += 25.0D;

        // Raids: a looter comes first, most of all one running off with the village's goods.
        if (monster instanceof Mob raider && com.alex.test.village.raid.RaidSpawner.isLooter(raider)) {
            score += com.alex.test.village.raid.RaidLoot.isCarrying(raider) ? 150.0D : 60.0D;
        }
        // A kidnapper leading a villager away comes before everything else.
        if (com.alex.test.village.raid.RaidKidnap.isCaptor(monster)) score += 250.0D;
        return Math.max(1.0D, score);
    }

    private static double proximityScore(double distance, double radius, double maxScore) {
        if (distance >= radius) return 0.0D;
        double closeness = 1.0D - distance / radius;
        return maxScore * closeness * closeness;
    }

    private static double nearestProtectedPlayerDistance(VillageManager manager, ServerLevel level, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !manager.contains(player.blockPosition())) continue;
            best = Math.min(best, Math.sqrt(player.blockPosition().distSqr(pos)));
        }
        return best;
    }

    private static double nearestBuildingDistance(VillageManager manager, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (Building building : manager.getBuildings()) {
            if (!building.isValid()) continue;
            best = Math.min(best, Math.sqrt(building.getDoorPos().distSqr(pos)));
        }
        return best;
    }

    /** Guards on {@code threat} against what it can take; 1.0 is full, more means guards are queueing. */
    private static double load(Threat threat, int assigned) {
        return assigned / (double) Math.max(1, threat.maxGuards);
    }

    /** As {@link #load(Threat, int)}, against the archer limit when {@code defender} is an archer. */
    private static double load(Threat threat, int assigned, NpcEntity defender) {
        return isRanged(defender) ? assigned / (double) ARCHERS_PER_MOB : load(threat, assigned);
    }

    /** Archers and mages: they shoot, and count against the archer limit on a mob. */
    private static boolean isRanged(NpcEntity defender) {
        return defender.getProfession().isRangedDefender();
    }

    /** Keeps {@link #RANGED_DEFENDERS} in step with what this defender is now. */
    private static void noteRole(NpcEntity defender) {
        if (isRanged(defender)) RANGED_DEFENDERS.add(defender.getNpcId());
        else RANGED_DEFENDERS.remove(defender.getNpcId());
    }

    /**
     * Defenders of the same kind as {@code defender} on {@code threat}: melee
     * fighters for a guard or captain, archers for an archer. An archer on a mob
     * never takes a guard's place there, and the other way round.
     */
    private static int slotsTaken(Threat threat, NpcEntity defender) {
        boolean ranged = isRanged(defender);
        int count = 0;
        for (UUID id : threat.assignedGuardIds) {
            if (RANGED_DEFENDERS.contains(id) == ranged) count++;
        }
        return count;
    }

    /**
     * Lower is better. Load decides -- a guard goes where the fewest guards are
     * for what the mob can take -- and only then distance, then danger. The limit
     * is soft: when every mob is full the guard still goes to the least loaded.
     */
    private static double assignmentScore(
            Activity.Context ctx, VillagerModel model, NpcEntity guard, Threat threat, int assigned) {
        if (isRanged(guard)) return archerScore(ctx, guard, threat, assigned);
        double dist = guard.blockPosition().distSqr(threat.monsterPos);
        double protectedDist = guard.blockPosition().distSqr(threat.protectedNpcPos);
        double score = load(threat, assigned, guard) * LOAD_WEIGHT
                + Math.min(dist, protectedDist * 0.85D)
                - threat.threatScore * THREAT_TIE_BREAK_WEIGHT;

        if (recentlyFailedThreatApproach(ctx, model, threat)) score += RECENTLY_FAILED_ASSIGNMENT_PENALTY;
        // A mob chasing an archer: the guards peel it off before it reaches it.
        if (ctx.level().getEntity(threat.monsterId) instanceof Mob mob
                && mob.getTarget() instanceof NpcEntity chased && isRanged(chased)) {
            score -= PEEL_FOR_ARCHER_BONUS;
        }
        return score;
    }

    /** Ranked like squared blocks: how much nearer a mob an archer should rather shoot counts as. */
    private static final double PEEL_FOR_ARCHER_BONUS = 400.0D;
    private static final double ARCHER_SHOOTER_BONUS = 400.0D;
    private static final double ARCHER_FLYER_BONUS = 400.0D;
    private static final double ARCHER_OUT_OF_MELEE_BONUS = 600.0D;
    private static final double ARCHER_SUPPORT_BONUS = 150.0D;

    /**
     * Lower is better, for an archer. It spreads only once a mob has all the
     * archers it can take; before that it picks what it is best at: mobs that
     * shoot back (skeletons, pillagers), mobs that fly, and mobs no guard can
     * walk up to (on a roof, in a pit, across water). Then mobs already
     * fighting someone, then the nearest.
     */
    private static double archerScore(Activity.Context ctx, NpcEntity archer, Threat threat, int assigned) {
        double load = assigned / (double) ARCHERS_PER_MOB;
        double score = (load >= 1.0D ? load * LOAD_WEIGHT : 0.0D)
                + archer.blockPosition().distSqr(threat.monsterPos)
                - threat.threatScore * THREAT_TIE_BREAK_WEIGHT;
        Entity entity = ctx.level().getEntity(threat.monsterId);
        if (entity instanceof net.minecraft.world.entity.monster.RangedAttackMob) score -= ARCHER_SHOOTER_BONUS;
        if (entity instanceof net.minecraft.world.entity.FlyingMob
                || entity instanceof net.minecraft.world.entity.monster.Phantom
                || entity instanceof net.minecraft.world.entity.monster.Blaze) {
            score -= ARCHER_FLYER_BONUS;
        }
        if (meleeCannotReach(ctx, threat)) score -= ARCHER_OUT_OF_MELEE_BONUS;
        else if (entity instanceof Mob mob && mob.getTarget() != null) score -= ARCHER_SUPPORT_BONUS;
        return score;
    }

    /**
     * No guard can get at this mob: no walkable spot near it, or a guard or
     * captain of the village just failed to walk there.
     */
    private static boolean meleeCannotReach(Activity.Context ctx, Threat threat) {
        return meleeCannotReach(ctx.mgr(), threat);
    }

    private static boolean meleeCannotReach(VillageManager manager, Threat threat) {
        BlockPos approach = reachableThreatApproach(manager, threat);
        if (approach == null) return true;
        for (VillagerModel other : manager.getVillagers()) {
            NpcEntity npc = other.puppet();
            if (npc != null && npc.getProfession().isGuardLike() && other.hasRecentMoveFailure(approach)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every guard's target goes in the table the moment it is taken, however it
     * was chosen -- the alert plan, a mob that hit the guard -- so the planner
     * counts the guards actually on a mob (those still walking up included) and
     * not just the ones it assigned itself. Replaces the guard's earlier mob.
     */
    public static void claim(Activity.Context ctx, NpcEntity guard, LivingEntity monster) {
        UUID guardId = guard.getNpcId();
        UUID monsterId = monster.getUUID();
        noteRole(guard);
        UUID previous = GUARD_ASSIGNMENTS.get(guardId);
        if (monsterId.equals(previous)) {
            Threat same = threatById(ctx.mgr(), monsterId);
            if (same != null) same.assignedGuardIds.add(guardId);
            return;
        }
        // Its own choice (a mob that hit it, one close by): the team plan
        // counts it on that mob, but never takes it off again for being extra.
        PLANNED.remove(guardId);
        RELEASED.remove(guardId);
        Threat threat = threatById(ctx.mgr(), monsterId);
        if (threat == null) return;
        if (previous != null) {
            Threat old = threatById(ctx.mgr(), previous);
            if (old != null) old.assignedGuardIds.remove(guardId);
        }
        GUARD_ASSIGNMENTS.put(guardId, monsterId);
        threat.assignedGuardIds.add(guardId);
    }

    /**
     * Whether another threat carries a lighter load than {@code monster} would
     * with this guard gone from it -- the one reason to leave a mob that is
     * already as crowded as it can be.
     */
    public static boolean hasLessLoadedThreat(Activity.Context ctx, LivingEntity monster) {
        long now = ctx.level().getGameTime();
        // The loops below test fields before looking a mob up; those are current after this.
        cleanup(ctx, now);
        Threat current = threatById(ctx.mgr(), monster.getUUID());
        if (current == null) return false;
        // With teams: only for a mob whose team still has room, so the guard
        // goes where it is wanted rather than queueing behind two others.
        if (usesTeams(ctx.mgr(), ctx.npc())) return hasOpenTeamElsewhere(ctx, current, now);
        Threat alternative = bestAlternativeThreat(ctx, current, now);
        if (alternative == null) return false;
        NpcEntity self = ctx.npc();
        return load(alternative, slotsTaken(alternative, self) + 1, self)
                < load(current, slotsTaken(current, self), self);
    }

    private static boolean shouldSwitchFromAssigned(Activity.Context ctx, Threat current, long now) {
        UUID guardId = ctx.npc().getNpcId();
        if (now < NEXT_GUARD_SWITCH_ALLOWED.getOrDefault(guardId, 0L)) return false;

        Threat alternative = bestAlternativeThreat(ctx, current, now);
        if (alternative == null) return false;

        // An archer shoots from where it stands; a mob nobody can walk up to is
        // exactly the one it should stay on.
        if (!isRanged(ctx.npc())) {
            boolean currentRecentlyFailed = recentlyFailedThreatApproach(ctx, ctx.model(), current);
            boolean alternativeRecentlyFailed = recentlyFailedThreatApproach(ctx, ctx.model(), alternative);
            if (currentRecentlyFailed && !alternativeRecentlyFailed) return true;
            if (alternativeRecentlyFailed) return false;
        }

        // Move only if it evens things out: the alternative, with this guard on it,
        // would still carry less than the mob being left does now. Two guards on
        // one mob and none on another -> one moves; two against one -> nobody does.
        NpcEntity self = ctx.npc();
        return load(alternative, slotsTaken(alternative, self) + 1, self)
                < load(current, slotsTaken(current, self), self);
    }

    @Nullable
    private static Threat bestAlternativeThreat(Activity.Context ctx, Threat current, long now) {
        Threat best = null;
        double bestScore = Double.MAX_VALUE;
        for (Threat threat : threats(ctx)) {
            if (threat == current || plainlyNotFor(ctx, ctx.npc(), threat)) continue;
            LivingEntity entity = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (entity == null
                    || !GuardThreatRules.isHostileMonsterForGuard(ctx.npc(), entity)
                    || !threat.active
                    || isGivenUp(ctx.npc(), entity, now)) continue;

            pruneAssignments(ctx, threat);
            int assigned = slotsTaken(threat, ctx.npc());
            double score = assignmentScore(ctx, ctx.model(), ctx.npc(), threat, assigned);
            if (score < bestScore) {
                bestScore = score;
                best = threat;
            }
        }
        return best;
    }

    private static boolean recentlyFailedThreatApproach(Activity.Context ctx, VillagerModel model, Threat threat) {
        return recentlyFailedThreatApproach(ctx.mgr(), model, threat);
    }

    private static boolean recentlyFailedThreatApproach(VillageManager manager, VillagerModel model, Threat threat) {
        if (model == null || manager.getGraph() == null) return true;
        BlockPos approach = reachableThreatApproach(manager, threat);
        return approach == null || model.hasRecentMoveFailure(approach);
    }

    @Nullable
    private static BlockPos reachableThreatApproach(Activity.Context ctx, Threat threat) {
        return reachableThreatApproach(ctx.mgr(), threat);
    }

    @Nullable
    private static BlockPos reachableThreatApproach(VillageManager manager, Threat threat) {
        var graph = manager.getGraph();
        if (graph == null) return null;

        // Good while nothing changed near either end: an edit elsewhere in
        // the village used to throw this away through the village-wide version.
        if (threat.monsterPos.equals(threat.approachCacheMonsterPos)
                && threat.protectedNpcPos.equals(threat.approachCacheProtectedPos)
                && !graph.changedSince(threat.approachCacheGraphVersion, threat.monsterPos, 10, 4)
                && !graph.changedSince(threat.approachCacheGraphVersion, threat.protectedNpcPos, 8, 4)) {
            return threat.approachCacheResult;
        }

        NavNode node = graph.nearest(threat.monsterPos, 10, 4);
        BlockPos result = node != null ? node.feetPos() : null;
        if (result == null) {
            node = graph.nearest(threat.protectedNpcPos, 8, 4);
            result = node != null ? node.feetPos() : null;
        }

        threat.approachCacheGraphVersion = graph.version();
        threat.approachCacheMonsterPos = threat.monsterPos;
        threat.approachCacheProtectedPos = threat.protectedNpcPos;
        threat.approachCacheResult = result;
        return result;
    }

    @Nullable
    private static LivingEntity nearestLocalThreat(
            Activity.Context ctx, NpcEntity observer, double radius, boolean observerOnly) {
        return nearestLocalThreat(ctx.mgr(), ctx.level(), observer, radius, observerOnly);
    }

    @Nullable
    private static LivingEntity nearestLocalThreat(VillageManager manager, ServerLevel level,
            NpcEntity observer, double radius, boolean observerOnly) {
        return nearestOf(manager, observer, localThreats(manager, level, observer, radius, observerOnly));
    }

    /** Every hostile {@code observer} notices within {@code radius}. */
    private static List<Mob> localThreats(VillageManager manager, ServerLevel level,
            NpcEntity observer, double radius, boolean observerOnly) {
        AABB box = observer.getBoundingBox().inflate(radius);
        boolean guardObserver = observer.getProfession().isGuardLike();
        long now = level.getGameTime();
        return level.getEntitiesOfClass(Mob.class, box,
                monster -> (guardObserver
                                ? GuardThreatRules.isHostileMonsterForGuard(observer, monster)
                                : GuardThreatRules.isHostileMonster(manager, monster))
                        && (observerOnly
                                ? seesThreat(observer, monster)
                                : knownDanger(manager, monster, now)
                                        || isActiveThreat(manager, level, monster, observer)));
    }

    /**
     * A mob on the danger list, found dangerous within the last re-check interval:
     * counted as dangerous without looking over every villager again.
     */
    private static boolean knownDanger(VillageManager manager, Mob monster, long now) {
        Threat known = threatById(manager, monster.getUUID());
        return known != null && known.active && now - known.lastActiveCheckAt < ACTIVE_RECHECK_INTERVAL_TICKS;
    }

    /**
     * Reports every hostile in one scan, not just the nearest. Reporting only the
     * nearest left the rest of a pack unknown to the guards until that one was
     * dead or someone else happened to spot them, so a second and third guard had
     * nothing to be assigned to and piled onto the first mob.
     *
     * @return the nearest of them, or null
     */
    @Nullable
    private static LivingEntity reportLocalThreats(VillageManager manager, ServerLevel level,
            NpcEntity observer, double radius, boolean observerOnly) {
        List<Mob> seen = localThreats(manager, level, observer, radius, observerOnly);
        for (Mob monster : seen) {
            reportThreat(manager, level, observer, monster, true);
        }
        return nearestOf(manager, observer, seen);
    }

    @Nullable
    private static LivingEntity nearestOf(VillageManager manager, NpcEntity observer, List<Mob> monsters) {
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Mob monster : monsters) {
            double score = observer.distanceToSqr(monster);
            double protectedDist = nearestProtectedNpcDistanceSqr(manager, monster.blockPosition());
            if (protectedDist < ACTIVE_THREAT_RADIUS_SQ) score -= 80.0;
            if (score < bestScore) {
                bestScore = score;
                best = monster;
            }
        }
        return best;
    }

    private static boolean shouldRunGuardLocalScan(Activity.Context ctx, long now) {
        UUID guardId = ctx.npc().getNpcId();
        if (now < NEXT_GUARD_SCAN.getOrDefault(guardId, 0L)) return false;
        NEXT_GUARD_SCAN.put(guardId, now + GUARD_LOCAL_SCAN_INTERVAL_TICKS);
        return true;
    }

    private static boolean isActiveThreat(
            VillageManager manager, ServerLevel level, LivingEntity monster, @Nullable NpcEntity witness) {
        if (!GuardThreatRules.isHostileMonster(manager, monster)) return false;
        // Inside the village a monster counts whether or not anyone can see it.
        // Vanilla monsters do not target our NPCs, so one that stops behind a
        // wall never advances on its own -- and while line of sight was also
        // required to notice it, neither side ever moved and the monster simply
        // loitered inside the village until something else killed it. Every
        // distance bound below still applies, so this widens what guards react
        // to, not how far they can reach.
        boolean insideVillage = manager.contains(monster.blockPosition());
        if (witness != null && witness.isAlive()
                && isImmediateThreat(witness, monster, insideVillage)) return true;

        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            if (isImmediateThreat(npc, monster, insideVillage)) return true;
        }
        return false;
    }

    /**
     * A non-defender only notices a monster it can actually see: within range,
     * inside its field of view, and with nothing blocking its eyes. One sneaking
     * up from behind goes unnoticed until it attacks (damage still triggers the
     * flee and alerts). The cone test is a dot product and runs first, so the
     * line-of-sight raycast only happens for monsters already in front.
     */
    private static boolean seesThreat(NpcEntity observer, LivingEntity monster) {
        double dx = monster.getX() - observer.getX();
        double dz = monster.getZ() - observer.getZ();
        double distSq = dx * dx + dz * dz;
        if (distSq + Mth.square(monster.getY() - observer.getY()) > threatRadiusSqFor(observer)) {
            return false;
        }
        if (distSq > 1.0E-4D) {
            float yaw = observer.getYHeadRot() * Mth.DEG_TO_RAD;
            double facing = (-Mth.sin(yaw) * dx + Mth.cos(yaw) * dz) / Math.sqrt(distSq);
            if (facing < VILLAGER_VIEW_CONE_COS) return false;
        }
        return observer.hasLineOfSight(monster);
    }

    private static boolean isImmediateThreat(NpcEntity npc, LivingEntity monster,
                                             boolean insideVillage) {
        return isImmediateThreat((LivingEntity) npc, monster, insideVillage);
    }

    private static double threatRadiusSqFor(LivingEntity protectedEntity) {
        double r = seenRadius(protectedEntity);
        return r * r;
    }

    /**
     * How far this villager notices a hostile, and so how close one has to be
     * to count as a threat to it: 8 blocks for a civilian; a guard or captain
     * 10, 14 on alert, and half as far again before that for a captain with
     * Veteran's eye (15, 19 on alert); an archer 18, 24 on alert. The search
     * box and this filter used to differ -- a guard searched 10 blocks but
     * only counted what was within 8, and Veteran's eye only widened the box.
     */
    private static double seenRadius(LivingEntity entity) {
        if (!(entity instanceof NpcEntity npc) || !npc.getProfession().isDefender()) return ACTIVE_THREAT_RADIUS;
        boolean alert = isOnAlert(npc);
        // A mage watches as far as an archer does, without Eagle Eye or a tower.
        if (npc.getProfession() == Profession.MAGE) return alert ? ALERT_ARCHER_SEEN_RADIUS : ARCHER_SCAN_RADIUS;
        if (npc.getProfession() == Profession.ARCHER) {
            double radius = alert ? ALERT_ARCHER_SEEN_RADIUS : ARCHER_SCAN_RADIUS;
            // Eagle Eye: an archer at 75+ spots mobs half as far again.
            if (com.alex.test.village.villagers.archer.ArcherBalance.hasEagleEye(
                    com.alex.test.village.villagers.archer.ArcherBowUtils.skillLevel(npc))) {
                radius *= com.alex.test.village.villagers.archer.ArcherBalance.EAGLE_EYE_SPOT_MULTIPLIER;
            }
            // A lookout up its watchtower: a block farther for every two of height.
            return radius + com.alex.test.village.villagers.archer.behavior.WatchtowerDuty.spotBonus(npc);
        }
        double radius = GuardBalance.SPOT_RADIUS;
        if (npc.getProfession() == Profession.CAPTAIN
                && CaptainBalance.hasVeteranEye(GuardSkillEffects.activeLevel(npc))) {
            radius *= CaptainBalance.VETERAN_EYE_FACTOR;
        }
        return alert ? radius + GuardBalance.ALERT_SPOT_BONUS : radius;
    }

    /** How far this defender looks for hostiles on its own; see {@link #seenRadius}. */
    private static double scanRadiusFor(NpcEntity observer) {
        return seenRadius(observer);
    }

    /** Within this, a mob counts even unseen: 4 blocks, 8 for a defender on alert. */
    private static double unseenRadiusSqFor(LivingEntity protectedEntity) {
        return isOnAlert(protectedEntity) ? ALERT_UNSEEN_RADIUS * ALERT_UNSEEN_RADIUS : CLOSE_NO_LOS_RADIUS_SQ;
    }

    private static boolean isImmediateThreat(LivingEntity protectedEntity, LivingEntity monster) {
        return isImmediateThreat(protectedEntity, monster, false);
    }

    private static boolean isImmediateThreat(LivingEntity protectedEntity, LivingEntity monster,
                                             boolean insideVillage) {
        double dist = protectedEntity.distanceToSqr(monster);
        // How close a monster must be to count as menacing this villager. An
        // archer is menaced at its own shooting range rather than a melee
        // radius -- widening this for everyone instead made guards split
        // across too many simultaneous threats and finish none of them.
        if (dist > threatRadiusSqFor(protectedEntity)) return false;
        return insideVillage
                || dist <= unseenRadiusSqFor(protectedEntity)
                || protectedEntity.hasLineOfSight(monster)
                || monster.hasLineOfSight(protectedEntity);
    }

    @Nullable
    private static LivingEntity refreshThreatIfDue(
            VillageManager manager, ServerLevel level, Threat threat, long now) {
        Entity entity = level.getEntity(threat.monsterId);
        if (!GuardThreatRules.isHostileMonster(manager, entity)) return null;

        LivingEntity monster = (LivingEntity) entity;
        if (now - threat.lastActiveCheckAt >= ACTIVE_RECHECK_INTERVAL_TICKS) {
            threat.lastActiveCheckAt = now;
            threat.monsterPos = monster.blockPosition();
            // Villagers first: a mob after them is every guard's (NPC). One after only the
            // player it hurt is for Protect Player guards (PLAYER). Both sides are judged on
            // every check, whoever reported it last: a player's mob seen by a villager used
            // to be judged by villagers only and dropped until the player's next hit.
            boolean playerMob = threat.protectedKind == ProtectedKind.PLAYER;
            boolean forVillagers = (!playerMob && now <= threat.damageConfirmedUntil)
                    || isActiveThreat(manager, level, monster, null);
            ServerPlayer player = forVillagers ? null : playerInDanger(manager, level, threat, monster, now);
            threat.active = forVillagers || player != null;
            if (forVillagers) {
                threat.protectedKind = ProtectedKind.NPC;
                threat.protectedNpcPos =
                        nearestProtectedNpcPos(manager, monster.blockPosition(), threat.protectedNpcPos);
                threat.expiresAt = Math.max(threat.expiresAt, now + ACTIVE_TTL_TICKS);
            } else if (player != null) {
                threat.protectedKind = ProtectedKind.PLAYER;
                threat.reportedByNpcId = player.getUUID();
                threat.protectedNpcPos = player.blockPosition();
                threat.expiresAt = Math.max(threat.expiresAt, now + ACTIVE_TTL_TICKS);
            } else if (playerMob
                    ? monster.blockPosition().distSqr(threat.protectedNpcPos) > FAR_FROM_PROTECTED_NPC_SQ
                    : nearestProtectedNpcDistanceSqr(manager, monster.blockPosition()) > FAR_FROM_PROTECTED_NPC_SQ) {
                threat.expiresAt = now - 1;
            }
        }
        return now <= threat.expiresAt ? monster : null;
    }

    /** The player this mob last hurt, while still in danger from it: in the village, hit lately or about to be. */
    @Nullable
    private static ServerPlayer playerInDanger(
            VillageManager manager, ServerLevel level, Threat threat, LivingEntity monster, long now) {
        if (threat.playerId == null) return null;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(threat.playerId);
        if (player == null || player.isSpectator() || !manager.contains(player.blockPosition())) return null;
        return now <= threat.playerUntil || isImmediateThreat(player, monster) ? player : null;
    }

    private static boolean guardTooFarFromThreat(VillageManager manager, NpcEntity guard, Threat threat) {
        // Guards that belong to this village should be allowed to answer a
        // confirmed alarm from anywhere inside the village. The expensive part
        // remains event-based: we are not scanning the whole village every tick.
        if (manager.contains(guard.blockPosition())) return false;

        return guard.blockPosition().distSqr(threat.monsterPos) > VILLAGE_WIDE_ALERT_REACTION_RADIUS_SQ
                && guard.blockPosition().distSqr(threat.protectedNpcPos) > VILLAGE_WIDE_ALERT_REACTION_RADIUS_SQ;
    }

    private static boolean canGuardRespondToThreat(Activity.Context ctx, Threat threat) {
        return canGuardRespondToThreat(ctx.npc(), ctx.level(), threat);
    }

    private static boolean canGuardRespondToThreat(NpcEntity guard, ServerLevel level, Threat threat) {
        if (threat.protectedKind != ProtectedKind.PLAYER) return true;

        GuardSettings settings = GuardSettings.of(guard);
        // Protect Player is an emergency response toggle, not a patrol-shift
        // toggle. The night/day schedule should decide normal patrol/follow
        // behavior, but it must not block a confirmed player-damage alarm.
        return settings.protectPlayer();
    }

    private static void pruneAssignments(Activity.Context ctx, Threat threat) {
        pruneAssignments(ctx.mgr(), threat, ctx.level().getGameTime());
    }

    private static void pruneAssignments(VillageManager manager, Threat threat, long now) {
        threat.assignedGuardIds.removeIf(guardId -> {
            if (!threat.monsterId.equals(GUARD_ASSIGNMENTS.get(guardId))) {
                return true;
            }
            NpcEntity guard = findGuard(manager, guardId);
            boolean gone = guard == null || !guard.isAlive() || guard.isRemoved();
            boolean stale = gone
                    || !isGuardOrArcher(guard.getProfession())
                    || guardTooFarFromThreat(manager, guard, threat);
            if (stale) {
                GUARD_ASSIGNMENTS.remove(guardId);
                PLANNED.remove(guardId);
                // One of its team died: the team has one more for a while.
                if (gone) threat.backupUntil = Math.max(threat.backupUntil, now + BACKUP_HOLD_TICKS);
            }
            return stale;
        });
    }

    /**
     * Archers reuse this entire threat-detection/assignment hub rather than
     * duplicating it (see ArcherActivity) -- so anywhere this file gates
     * behaviour on "is this NPC a guard" must also accept an archer, or an
     * archer's assignment is treated as stale and stripped out from under it
     * every rebalance pass (~1.5s), causing constant target-reassignment churn.
     */
    private static boolean isGuardOrArcher(Profession profession) {
        return profession.isDefender();
    }

    @Nullable
    private static NpcEntity findGuard(VillageManager manager, UUID guardId) {
        // Villagers are kept by NPC id: one lookup, not a walk over all of them.
        VillagerModel model = manager.getModel(guardId);
        NpcEntity npc = model != null ? model.puppet() : null;
        return npc != null && guardId.equals(npc.getNpcId()) ? npc : null;
    }

    private static void releaseGuardAssignment(Activity.Context ctx, UUID guardId, UUID monsterId) {
        if (monsterId.equals(GUARD_ASSIGNMENTS.get(guardId))) GUARD_ASSIGNMENTS.remove(guardId);
        Threat threat = threatById(ctx.mgr(), monsterId);
        if (threat != null) threat.assignedGuardIds.remove(guardId);
    }

    private static double nearestProtectedNpcDistanceSqr(VillageManager manager, BlockPos pos) {
        double best = Double.MAX_VALUE;
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            best = Math.min(best, npc.blockPosition().distSqr(pos));
        }
        return best;
    }

    private static BlockPos nearestProtectedNpcPos(
            VillageManager manager, BlockPos monsterPos, BlockPos fallback) {
        BlockPos bestPos = fallback;
        double best = Double.MAX_VALUE;
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved()) continue;
            double dist = npc.blockPosition().distSqr(monsterPos);
            if (dist < best) {
                best = dist;
                bestPos = npc.blockPosition();
            }
        }
        return bestPos;
    }

    @Nullable
    private static Threat threatById(VillageManager manager, UUID monsterId) {
        Map<UUID, Threat> threats = THREATS_BY_VILLAGE.get(manager.getVillageId());
        return threats != null ? threats.get(monsterId) : null;
    }

    private static Collection<Threat> threats(Activity.Context ctx) {
        return threats(ctx.mgr());
    }

    /** The village's danger list, oldest report first; its iterator may remove. */
    private static Collection<Threat> threats(VillageManager manager) {
        return threatMap(manager).values();
    }

    private static Map<UUID, Threat> threatMap(VillageManager manager) {
        return THREATS_BY_VILLAGE.computeIfAbsent(manager.getVillageId(), ignored -> new LinkedHashMap<>());
    }

    private static void cleanup(Activity.Context ctx, long now) {
        cleanup(ctx.mgr(), ctx.level(), now);
    }

    /** Game time the timers below are next swept; see {@link #pruneTimers}. */
    private static long nextTimerPrune = Long.MIN_VALUE;
    private static final int TIMER_PRUNE_TICKS = 1200;
    /** A timer this far ahead is left from another world (single player keeps one game running). */
    private static final long TIMER_FUTURE_LIMIT = 72_000L;

    /**
     * Once a minute, drops timers that ran out: a run-out timer reads the same as a
     * missing one. Every mob ever reported kept its alert cooldown forever, and every
     * defender its scan, switch, poll and step-aside times, dead ones included.
     */
    private static void pruneTimers(long now) {
        if (now < nextTimerPrune && now >= nextTimerPrune - TIMER_PRUNE_TICKS) return;
        nextTimerPrune = now + TIMER_PRUNE_TICKS;
        java.util.function.LongPredicate gone = t -> t < now || t > now + TIMER_FUTURE_LIMIT;
        for (Map<UUID, Long> timers : List.of(NEXT_MONSTER_ALERT, NEXT_VILLAGER_ALERT, NEXT_VILLAGER_SCAN,
                NEXT_GUARD_SCAN, NEXT_GUARD_SWITCH_ALLOWED, NEXT_CALL_OUT, ALERT_UNTIL)) {
            timers.values().removeIf(gone::test);
        }
        STEPPED_ASIDE.values().removeIf(mobs -> {
            mobs.values().removeIf(gone::test);
            return mobs.isEmpty();
        });
        GIVEN_UP.values().removeIf(mobs -> {
            mobs.values().removeIf(g -> gone.test(g.quietUntil()));
            return mobs.isEmpty();
        });
        EMERGENCY_THIS_TICK.values().removeIf(memo -> memo[0] != now);
        LAST_HUNT_POLL.values().removeIf(t -> now - t > HUNTING_POLL_TICKS || t > now);
        LAST_STANDBY_POLL.entrySet().removeIf(entry -> {
            long t = entry.getValue();
            boolean old = now - t > STANDBY_POLL_TICKS || t > now;
            if (old) STANDBY_ON_DUTY.remove(entry.getKey());
            return old;
        });
    }

    private static void cleanup(VillageManager manager, ServerLevel level, long now) {
        // Once per village per tick: every defender's three entries used to walk the threat list each tick.
        UUID villageId = manager.getVillageId();
        Long doneAt = CLEANUP_DONE_AT.get(villageId);
        if (doneAt != null && doneAt == now) return;
        CLEANUP_DONE_AT.put(villageId, now);
        pruneTimers(now);
        boolean freedGuards = false;
        Iterator<Threat> it = threats(manager).iterator();
        while (it.hasNext()) {
            Threat threat = it.next();
            LivingEntity entity = refreshThreatIfDue(manager, level, threat, now);
            if (entity == null || now > threat.expiresAt) {
                for (UUID guardId : threat.assignedGuardIds) {
                    if (threat.monsterId.equals(GUARD_ASSIGNMENTS.get(guardId))) {
                        GUARD_ASSIGNMENTS.remove(guardId);
                        PLANNED.remove(guardId);
                        freedGuards = true;
                    }
                }
                threat.assignedGuardIds.clear();
                it.remove();
            }
        }
        // Its team is free for another mob: plan again (at most once a tick).
        if (freedGuards) scheduleAssignmentRefresh(manager, now);
    }

    // =========================================================================
    // Defender teams
    //
    // Each monster gets a team: as many defenders as it calls for (2 for a
    // zombie, 3 for a witch, 5 for a ravager; one more when it is hurting
    // someone, comes in a pack or is carrying a villager off; one more again
    // as backup), and the closest free defenders of any job fill it. The rest
    // keep patrolling. A defender always fights a monster that hits it or
    // comes within CLOSE_FIGHT_RANGE, team full or not. Every team gets a
    // guard or captain when one is free, unless no sword can reach the
    // monster. With more monsters than defenders, each monster gets one
    // before any gets a second. Off-duty defenders come out only when the
    // village rule allows and the ones on duty are too few. Mayor > Settings
    // > Village rules and > Professions (DefenderTeamRules) tune all this; the
    // rule "Everyone", or a job set to "Every fight", keeps the old way above.
    // =========================================================================

    /** A defender always fights a monster this close (or one that hits it), its team full or not. */
    public static final double CLOSE_FIGHT_RANGE = 8.0D;
    private static final double CLOSE_FIGHT_RANGE_SQ = CLOSE_FIGHT_RANGE * CLOSE_FIGHT_RANGE;
    /**
     * {@link #closeThreat} skips a mob whose noted spot is farther than this: the close
     * fight range plus 12 blocks, more than a fast mob covers between two spot updates.
     */
    private static final double CLOSE_PREFILTER_SQ = (CLOSE_FIGHT_RANGE + 12.0D) * (CLOSE_FIGHT_RANGE + 12.0D);
    /** Two more monsters within this of one make a pack: its team gets one more. */
    private static final double PACK_RADIUS_SQ = 6.0D * 6.0D;
    private static final int PACK_OTHERS = 2;
    /** A defender that asked for a mob this recently is out hunting, and can be sent. */
    private static final int HUNTING_POLL_TICKS = 40;
    /** A busy or resting defender that checked for danger this recently can be called. */
    private static final int STANDBY_POLL_TICKS = 100;
    /** A fight longer than this (20 s) calls one more defender. */
    private static final int LONG_FIGHT_TICKS = 400;
    /** After one of a team falls back or dies, the team has one more for this long (30 s). */
    private static final int BACKUP_HOLD_TICKS = 600;
    /** One of a team below this share of its health calls one more. */
    private static final float BACKUP_HEALTH = 0.5f;
    /** Below this share of its health a defender is not sent to a new fight (it still fights what comes close). */
    private static final float SEND_MIN_HEALTH = 0.4f;
    /** One moved to cover a monster nobody was on is not moved again for this long (5 s). */
    private static final int MOVED_HOLD_TICKS = 100;
    /** A hit this recent (ticks) means the monster is fighting this defender: it is never taken off it. */
    private static final int HIT_MEMORY_TICKS = 100;
    /** A sword that cannot get there counts as this much farther away (squared blocks). */
    private static final double USELESS_MELEE_PENALTY = 1_000_000.0D;

    /** Defender id -> game time it last asked for a mob (out hunting). */
    private static final Map<UUID, Long> LAST_HUNT_POLL = new HashMap<>();
    /** Defender id -> game time it last checked for danger while busy or resting. */
    private static final Map<UUID, Long> LAST_STANDBY_POLL = new HashMap<>();
    /** Defender id -> whether it was on duty at its last standby check. */
    private static final Map<UUID, Boolean> STANDBY_ON_DUTY = new HashMap<>();
    /** Defenders whose mob the team plan gave them (not one they took themselves). */
    private static final Set<UUID> PLANNED = new HashSet<>();
    /** Defender id -> the mob the plan took it off, for its activity to let go of. */
    private static final Map<UUID, UUID> RELEASED = new HashMap<>();

    private enum Tier { HUNTING, BUSY, RESTING }

    /** One defender, as the plan sees it. */
    private static final class Member {
        final VillagerModel model;
        final NpcEntity npc;
        /** Null: may not be sent (not polling, or its job answers every fight itself). */
        @Nullable final Tier tier;
        final com.alex.test.village.DefenderTeamRules.Answer answer;
        final boolean ranged;
        @Nullable TeamPlan on;

        Member(VillagerModel model, NpcEntity npc, @Nullable Tier tier,
               com.alex.test.village.DefenderTeamRules.Answer answer) {
            this.model = model;
            this.npc = npc;
            this.tier = tier;
            this.answer = answer;
            this.ranged = isRanged(npc);
        }

        UUID id() { return npc.getNpcId(); }

        boolean planned() { return PLANNED.contains(npc.getNpcId()); }
    }

    /** One monster's team, while the plan is worked out. */
    private static final class TeamPlan {
        final Threat threat;
        final LivingEntity monster;
        final List<Member> members = new ArrayList<>();
        int size;
        boolean meleeUseless;

        TeamPlan(Threat threat, LivingEntity monster) {
            this.threat = threat;
            this.monster = monster;
        }

        int melee() {
            int count = 0;
            for (Member member : members) if (!member.ranged) count++;
            return count;
        }

        int ofJob(Profession profession) {
            int count = 0;
            for (Member member : members) if (member.npc.getProfession() == profession) count++;
            return count;
        }

        boolean needsMelee() {
            return !meleeUseless && melee() == 0;
        }
    }

    private static com.alex.test.village.DefenderTeamRules teamRules(VillageManager manager) {
        return manager.rules().defenderTeams();
    }

    private static boolean teamsOn(VillageManager manager) {
        return teamRules(manager).teamSize() != com.alex.test.village.DefenderTeamRules.TeamSize.EVERYONE;
    }

    private static com.alex.test.village.DefenderTeamRules.Answer answerOf(VillageManager manager, NpcEntity npc) {
        return teamRules(manager).role(npc.getProfession()).answer();
    }

    /** Whether this defender's fights come from the team plan (teams on, its job not set to "Every fight"). */
    public static boolean usesTeams(Activity.Context ctx) {
        return usesTeams(ctx.mgr(), ctx.npc());
    }

    private static boolean usesTeams(VillageManager manager, NpcEntity npc) {
        return teamsOn(manager) && answerOf(manager, npc) != com.alex.test.village.DefenderTeamRules.Answer.EVERY_FIGHT;
    }

    /**
     * Whether a defender that is not out hunting would fight if called: the
     * same switches its activity checks before it looks for monsters.
     */
    private static boolean huntsOnCall(NpcEntity npc) {
        return switch (npc.getProfession()) {
            case GUARD, CAPTAIN -> GuardSettings.of(npc).attackHostileMobs();
            case ARCHER -> {
                ProfessionSettings settings = ProfessionSettings.of(npc, ArcherSettingsSchema.SCHEMA);
                yield settings.getBool(ArcherSettingsSchema.WORK_ENABLED)
                        && settings.getBool(ArcherSettingsSchema.PROACTIVE_TARGETING)
                        && com.alex.test.village.villagers.archer.ArcherBowUtils.hasBow(npc.getInventory())
                        && com.alex.test.village.villagers.archer.ArcherBowUtils.hasArrow(npc.getInventory());
            }
            case MAGE -> {
                ProfessionSettings settings = com.alex.test.village.villagers.mage.MageSettingsBridge.of(npc);
                yield settings.getBool(com.alex.test.village.villagers.mage.settings.MageSettingsSchema.WORK_ENABLED)
                        && settings.getBool(com.alex.test.village.villagers.mage.settings.MageSettingsSchema.PROACTIVE_TARGETING);
            }
            default -> false;
        };
    }

    @Nullable
    private static Tier tierOf(UUID id, long now) {
        Long hunt = LAST_HUNT_POLL.get(id);
        if (hunt != null && now - hunt <= HUNTING_POLL_TICKS && now >= hunt) return Tier.HUNTING;
        Long standby = LAST_STANDBY_POLL.get(id);
        if (standby != null && now - standby <= STANDBY_POLL_TICKS && now >= standby) {
            return Boolean.TRUE.equals(STANDBY_ON_DUTY.get(id)) ? Tier.BUSY : Tier.RESTING;
        }
        return null;
    }

    private static boolean isFlyer(LivingEntity monster) {
        return monster instanceof net.minecraft.world.entity.FlyingMob
                || monster instanceof net.minecraft.world.entity.monster.Phantom
                || monster instanceof net.minecraft.world.entity.monster.Blaze
                || monster instanceof net.minecraft.world.entity.monster.Vex;
    }

    /** What the monster itself calls for: 2 for an ordinary one, 3 for a tough one, 5 for a big one. */
    private static int baseTeamSize(LivingEntity monster) {
        float health = monster.getMaxHealth();
        if (health >= 60.0f) return 5;
        if (health >= 25.0f
                || monster instanceof net.minecraft.world.entity.monster.Witch
                || monster instanceof net.minecraft.world.entity.monster.SpellcasterIllager
                || monster instanceof net.minecraft.world.entity.monster.Vindicator) return 3;
        return 2;
    }

    /** Hurting a villager who is not a defender, or a player, or going for one right now. */
    private static boolean hurtingPeople(Threat threat, LivingEntity monster, long now) {
        if (now <= threat.hurtPeopleUntil) return true;
        // The player's hit counts whoever reported the mob last.
        if (now <= threat.playerUntil) return true;
        if (!(monster instanceof Mob mob)) return false;
        LivingEntity target = mob.getTarget();
        return target instanceof ServerPlayer
                || target instanceof NpcEntity npc && !npc.getProfession().isDefender();
    }

    private static boolean wasHitBy(NpcEntity npc, LivingEntity monster) {
        return npc.getLastHurtByMob() == monster
                && npc.tickCount - npc.getLastHurtByMobTimestamp() <= HIT_MEMORY_TICKS;
    }

    private static boolean needsBackup(TeamPlan plan, long now) {
        if (now < plan.threat.backupUntil) return true;
        if (plan.threat.teamSince != Long.MIN_VALUE && now - plan.threat.teamSince >= LONG_FIGHT_TICKS) return true;
        for (Member member : plan.members) {
            if (member.npc.getHealth() < member.npc.getMaxHealth() * BACKUP_HEALTH) return true;
        }
        return false;
    }

    private static int teamSize(VillageManager manager, TeamPlan plan, List<TeamPlan> plans, long now) {
        int size = baseTeamSize(plan.monster);
        if (hurtingPeople(plan.threat, plan.monster, now)) size++;
        int near = 0;
        for (TeamPlan other : plans) {
            if (other != plan && other.monster.distanceToSqr(plan.monster) <= PACK_RADIUS_SQ) near++;
        }
        if (near >= PACK_OTHERS) size++;
        if (com.alex.test.village.raid.RaidKidnap.isCaptor(plan.monster)) size++;
        if (needsBackup(plan, now)) size++;
        size += teamRules(manager).teamSize().extra();
        return Math.max(1, size);
    }

    /**
     * Works the teams out again: who is on which monster, who is sent where,
     * who is taken off. Runs with the assignment rebalance (twice a second,
     * and at once when a new monster is reported).
     */
    private static void planTeams(VillageManager manager, ServerLevel level, long now) {
        if (!teamsOn(manager)) return;
        com.alex.test.village.DefenderTeamRules rules = teamRules(manager);

        List<TeamPlan> plans = new ArrayList<>();
        Map<UUID, TeamPlan> byMonster = new HashMap<>();
        for (Threat threat : threats(manager)) {
            LivingEntity monster = refreshThreatIfDue(manager, level, threat, now);
            if (monster == null || !threat.active) continue;
            TeamPlan plan = new TeamPlan(threat, monster);
            plans.add(plan);
            byMonster.put(threat.monsterId, plan);
        }
        if (plans.isEmpty()) return;

        List<Member> members = new ArrayList<>();
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isRemoved() || !npc.getProfession().isDefender()) continue;
            com.alex.test.village.DefenderTeamRules.Answer answer = rules.role(npc.getProfession()).answer();
            Tier tier = answer == com.alex.test.village.DefenderTeamRules.Answer.EVERY_FIGHT
                    ? null : tierOf(npc.getNpcId(), now);
            Member member = new Member(model, npc, tier, answer);
            UUID onMob = GUARD_ASSIGNMENTS.get(npc.getNpcId());
            TeamPlan on = onMob != null ? byMonster.get(onMob) : null;
            if (on != null) {
                member.on = on;
                on.members.add(member);
            }
            members.add(member);
        }

        for (TeamPlan plan : plans) {
            plan.meleeUseless = isFlyer(plan.monster) || meleeCannotReach(manager, plan.threat);
            plan.size = teamSize(manager, plan, plans, now);
            plan.threat.teamSize = plan.size;
        }
        // Most dangerous first: kidnappers, looters, monsters hurting people, near houses.
        plans.sort((a, b) -> Double.compare(b.threat.threatScore, a.threat.threatScore));

        // 1. Close by always fights: a free defender out hunting joins the nearest monster in reach.
        for (Member member : members) {
            if (member.on != null || member.tier != Tier.HUNTING) continue;
            TeamPlan nearest = null;
            double best = CLOSE_FIGHT_RANGE_SQ;
            for (TeamPlan plan : plans) {
                double d = member.npc.distanceToSqr(plan.monster);
                if (d <= best && mayJoin(manager, level, member, plan, now, true)) {
                    best = d;
                    nearest = plan;
                }
            }
            if (nearest != null) assign(level, member, nearest, now, "close by");
        }

        // 2. Too many on a team (it shrank, or the plan just started): the farthest
        //    sent ones go back to patrol. Never one in a fight or close by, never the last sword.
        for (TeamPlan plan : plans) {
            while (plan.members.size() > plan.size) {
                Member drop = null;
                double far = CLOSE_FIGHT_RANGE_SQ;
                for (Member member : plan.members) {
                    if (member.tier == null || !member.planned()) continue;
                    if (!member.ranged && plan.melee() == 1 && !plan.meleeUseless) continue;
                    if (wasHitBy(member.npc, plan.monster)) continue;
                    double d = member.npc.distanceToSqr(plan.monster);
                    if (d > far) {
                        far = d;
                        drop = member;
                    }
                }
                if (drop == null) break;
                unassign(drop);
                RELEASED.put(drop.id(), plan.threat.monsterId);
                drop.model.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(level)
                        + " DEFENDER not needed at " + plan.monster.getName().getString()
                        + " (team " + plan.members.size() + "/" + plan.size + "), back to patrol");
            }
        }

        // 3. Spread first: every monster gets one before any gets a second.
        for (TeamPlan plan : plans) {
            if (!plan.members.isEmpty()) continue;
            Member pick = pick(manager, level, plan, members, Tier.HUNTING, now);
            if (pick == null) pick = steal(manager, level, plan, plans, now);
            if (pick == null) pick = pick(manager, level, plan, members, Tier.BUSY, now);
            if (pick == null && rules.resting() != com.alex.test.village.DefenderTeamRules.RestingCallOut.BELL_ONLY) {
                pick = pick(manager, level, plan, members, Tier.RESTING, now);
            }
            if (pick != null) assign(level, pick, plan, now, pick.tier == Tier.RESTING ? "called from rest" : "sent");
        }

        // 4. Fill each team up to its size, the most dangerous monster first.
        for (TeamPlan plan : plans) {
            while (plan.members.size() < plan.size) {
                Member pick = pick(manager, level, plan, members, Tier.HUNTING, now);
                // The last place would go to a bow or a staff with no sword on the team:
                // a guard busy on duty (at drill, fetching gear) comes before that.
                if (pick != null && pick.ranged && plan.needsMelee() && plan.size - plan.members.size() <= 1) {
                    Member melee = pickMelee(manager, level, plan, members, Tier.BUSY, now);
                    if (melee != null) pick = melee;
                }
                if (pick == null) pick = pick(manager, level, plan, members, Tier.BUSY, now);
                if (pick == null && rules.resting() == com.alex.test.village.DefenderTeamRules.RestingCallOut.WHEN_SHORT
                        && hurtingPeople(plan.threat, plan.monster, now)) {
                    pick = pick(manager, level, plan, members, Tier.RESTING, now);
                }
                if (pick == null) break;
                assign(level, pick, plan, now, pick.tier == Tier.RESTING ? "called from rest" : "sent");
            }
        }
    }

    /**
     * Whether {@code member} may go to {@code plan}'s monster: it fights that
     * kind, it is not leaving it alone (gave up on it, stepped aside), a
     * lookout can hit it from its tower, and its job has room on the team.
     */
    private static boolean mayJoin(VillageManager manager, ServerLevel level, Member member, TeamPlan plan,
                                   long now, boolean closeBy) {
        NpcEntity npc = member.npc;
        if (!closeBy && npc.getHealth() < npc.getMaxHealth() * SEND_MIN_HEALTH) return false;
        if (!GuardThreatRules.isHostileMonsterForGuard(npc, plan.monster)) return false;
        if (!canGuardRespondToThreat(npc, level, plan.threat)) return false;
        if (guardTooFarFromThreat(manager, npc, plan.threat)) return false;
        if (isSteppingAside(npc.getNpcId(), plan.threat.monsterId, now) || isGivenUp(npc, plan.monster, now)) return false;
        if (com.alex.test.village.villagers.archer.behavior.WatchtowerDuty.isOnWatch(npc)
                && !canHitFromHere(npc, plan.monster)) return false;
        if (member.tier != Tier.HUNTING && !huntsOnCall(npc)) return false;
        if (closeBy) return true;
        if (member.answer != com.alex.test.village.DefenderTeamRules.Answer.CLOSEST) return false;
        int most = teamRules(manager).role(npc.getProfession()).mostPerMonster();
        return most <= 0 || plan.ofJob(npc.getProfession()) < most;
    }

    /** Lower is better: how far, and a sword that has just failed to get there counts as farther. */
    private static double sendScore(VillageManager manager, Member member, TeamPlan plan) {
        double score = member.npc.distanceToSqr(plan.monster);
        if (!member.ranged) {
            if (plan.meleeUseless) score += USELESS_MELEE_PENALTY;
            else if (recentlyFailedThreatApproach(manager, member.model, plan.threat)) {
                score += RECENTLY_FAILED_ASSIGNMENT_PENALTY;
            }
        }
        return score;
    }

    /**
     * The best free defender of {@code tier} for {@code plan}: the closest,
     * except that the team's last open place goes to a guard or captain if it
     * has none yet and one is free (an archer and a mage alone are no match for
     * a zombie at the door).
     */
    @Nullable
    private static Member pick(VillageManager manager, ServerLevel level, TeamPlan plan, List<Member> members,
                               Tier tier, long now) {
        Member best = null;
        Member bestMelee = null;
        double bestScore = Double.MAX_VALUE;
        double bestMeleeScore = Double.MAX_VALUE;
        for (Member member : members) {
            if (member.on != null || member.tier != tier) continue;
            if (!mayJoin(manager, level, member, plan, now, false)) continue;
            double score = sendScore(manager, member, plan);
            if (score < bestScore) {
                bestScore = score;
                best = member;
            }
            if (!member.ranged && score < bestMeleeScore) {
                bestMeleeScore = score;
                bestMelee = member;
            }
        }
        boolean lastPlace = plan.size - plan.members.size() <= 1;
        if (lastPlace && plan.needsMelee() && bestMelee != null) return bestMelee;
        return best;
    }

    /** The closest free guard or captain of {@code tier} for {@code plan}, or null. */
    @Nullable
    private static Member pickMelee(VillageManager manager, ServerLevel level, TeamPlan plan, List<Member> members,
                                    Tier tier, long now) {
        Member best = null;
        double bestScore = Double.MAX_VALUE;
        for (Member member : members) {
            if (member.on != null || member.tier != tier || member.ranged) continue;
            if (!mayJoin(manager, level, member, plan, now, false)) continue;
            double score = sendScore(manager, member, plan);
            if (score < bestScore) {
                bestScore = score;
                best = member;
            }
        }
        return best;
    }

    /**
     * A monster nobody is on, and nobody free: one of a team of two or more
     * elsewhere moves over, the closest one not yet in its own fight. Never a
     * team's only guard, never one its monster has hit, never one moved a
     * moment ago.
     */
    @Nullable
    private static Member steal(VillageManager manager, ServerLevel level, TeamPlan plan, List<TeamPlan> plans,
                                long now) {
        Member best = null;
        double bestScore = Double.MAX_VALUE;
        for (TeamPlan from : plans) {
            if (from == plan || from.members.size() < 2) continue;
            for (Member member : from.members) {
                if (member.tier != Tier.HUNTING || !member.planned()) continue;
                if (now < NEXT_GUARD_SWITCH_ALLOWED.getOrDefault(member.id(), 0L)) continue;
                if (member.npc.distanceToSqr(from.monster) <= CLOSE_FIGHT_RANGE_SQ) continue;
                if (wasHitBy(member.npc, from.monster)) continue;
                if (!member.ranged && from.melee() == 1 && !from.meleeUseless) continue;
                if (com.alex.test.village.villagers.archer.behavior.WatchtowerDuty.isOnWatch(member.npc)) continue;
                if (!mayJoin(manager, level, member, plan, now, false)) continue;
                double score = sendScore(manager, member, plan);
                if (score < bestScore) {
                    bestScore = score;
                    best = member;
                }
            }
        }
        if (best != null) NEXT_GUARD_SWITCH_ALLOWED.put(best.id(), now + MOVED_HOLD_TICKS);
        return best;
    }

    private static void assign(ServerLevel level, Member member, TeamPlan plan, long now, String why) {
        if (member.on == plan) return;
        unassign(member);
        UUID id = member.id();
        GUARD_ASSIGNMENTS.put(id, plan.threat.monsterId);
        plan.threat.assignedGuardIds.add(id);
        plan.members.add(member);
        member.on = plan;
        PLANNED.add(id);
        RELEASED.remove(id);
        noteRole(member.npc);
        if (plan.threat.teamSince == Long.MIN_VALUE) plan.threat.teamSince = now;
        member.model.brain().diary().log(com.alex.test.village.brain.VillagerDiary.ts(level)
                + " DEFENDER " + why + " to " + plan.monster.getName().getString()
                + " (team " + plan.members.size() + "/" + plan.size
                + String.format(java.util.Locale.ROOT, ", %.0f blocks)", member.npc.distanceTo(plan.monster)));
    }

    private static void unassign(Member member) {
        TeamPlan from = member.on;
        if (from == null) return;
        UUID id = member.id();
        from.members.remove(member);
        from.threat.assignedGuardIds.remove(id);
        if (from.threat.monsterId.equals(GUARD_ASSIGNMENTS.get(id))) GUARD_ASSIGNMENTS.remove(id);
        PLANNED.remove(id);
        member.on = null;
    }

    /**
     * The mob a defender using teams should fight now: the one its team plan
     * gave it, else one within {@link #CLOSE_FIGHT_RANGE}, else none (it keeps
     * patrolling). It still reports what it sees, so the plan knows of it.
     */
    @Nullable
    private static GuardTarget teamTargetForDefender(Activity.Context ctx, long now) {
        NpcEntity defender = ctx.npc();
        UUID id = defender.getNpcId();
        UUID mobId = GUARD_ASSIGNMENTS.get(id);
        if (mobId != null) {
            Threat threat = threatById(ctx.mgr(), mobId);
            LivingEntity monster = threat != null ? refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now) : null;
            if (monster != null
                    && threat.active
                    && GuardThreatRules.isHostileMonsterForGuard(defender, monster)
                    && canGuardRespondToThreat(ctx, threat)
                    && !guardTooFarFromThreat(ctx.mgr(), defender, threat)
                    && !isSteppingAside(id, mobId, now)) {
                return new GuardTarget(monster, threat.monsterPos, threat.protectedNpcPos, true);
            }
            releaseGuardAssignment(ctx, id, mobId);
            PLANNED.remove(id);
        }

        if (shouldRunGuardLocalScan(ctx, now)) {
            LivingEntity seen = reportLocalThreats(ctx.mgr(), ctx.level(), defender, scanRadiusFor(defender), false);
            if (seen != null) callOutIfLookout(ctx.mgr(), ctx.level(), defender);
        }

        Threat close = closeThreat(ctx, defender, now);
        if (close == null) return null;
        LivingEntity monster = (LivingEntity) ctx.level().getEntity(close.monsterId);
        return new GuardTarget(monster, close.monsterPos, close.protectedNpcPos, true);
    }

    /**
     * The field-only tests a defender's mob loop makes before looking the mob up: one
     * lookup per mob per defender each tick was the cost in a crowd fight. Ask after
     * {@link #cleanup} this tick: nothing is then due a re-check, so these fields are
     * what a re-check would leave.
     */
    private static boolean plainlyNotFor(Activity.Context ctx, NpcEntity defender, Threat threat) {
        return !threat.active
                || !canGuardRespondToThreat(defender, ctx.level(), threat)
                || guardTooFarFromThreat(ctx.mgr(), defender, threat);
    }

    /** Slack on a noted mob spot (at most ~10 ticks old, see cleanup) for "can it be nearer than the best so far". */
    private static final double NOTED_SPOT_SLACK = 12.0D;

    /** Whether a mob noted this far off cannot be nearer than {@code bestSq} (squared blocks). */
    private static boolean notedFartherThan(BlockPos at, Threat threat, double bestSq) {
        if (bestSq == Double.MAX_VALUE || threat.monsterPos == null) return false;
        double reach = Math.sqrt(bestSq) + NOTED_SPOT_SLACK;
        return at.distSqr(threat.monsterPos) > reach * reach;
    }

    /** The nearest live monster within {@link #CLOSE_FIGHT_RANGE} this defender may fight, or null. */
    @Nullable
    private static Threat closeThreat(Activity.Context ctx, NpcEntity defender, long now) {
        Threat best = null;
        double bestSq = CLOSE_FIGHT_RANGE_SQ;
        BlockPos at = defender.blockPosition();
        for (Threat threat : threats(ctx)) {
            // Cheap test first: the mob's spot as last noted. Cleanup re-notes every
            // mob's spot at least every 10 ticks, so a mob far past this margin
            // cannot be in range; looking each one up cost an off-duty defender
            // a walk over every mob, every tick.
            if (threat.monsterPos != null && at.distSqr(threat.monsterPos) > CLOSE_PREFILTER_SQ) continue;
            LivingEntity monster = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (monster == null || !threat.active) continue;
            double d = defender.distanceToSqr(monster);
            if (d > bestSq
                    || !GuardThreatRules.isHostileMonsterForGuard(defender, monster)
                    || !canGuardRespondToThreat(ctx, threat)
                    || isSteppingAside(defender.getNpcId(), threat.monsterId, now)
                    || isGivenUp(defender, monster, now)) continue;
            bestSq = d;
            best = threat;
        }
        return best;
    }

    /**
     * Whether a busy or resting defender using teams should drop what it is
     * doing: it has been sent (on duty, or called from rest because those on
     * duty are too few), or a monster is within {@link #CLOSE_FIGHT_RANGE}.
     * One far across the village is left to the defenders on duty.
     */
    private static boolean hasTeamEmergency(Activity.Context ctx, boolean onDuty, long now) {
        NpcEntity npc = ctx.npc();
        UUID id = npc.getNpcId();
        if (answerOf(ctx.mgr(), npc) != com.alex.test.village.DefenderTeamRules.Answer.NEARBY_ONLY && huntsOnCall(npc)) {
            LAST_STANDBY_POLL.put(id, now);
            STANDBY_ON_DUTY.put(id, onDuty);
        }
        refreshAssignmentPlanIfDue(ctx, now);

        UUID mobId = GUARD_ASSIGNMENTS.get(id);
        if (mobId != null) {
            Threat threat = threatById(ctx.mgr(), mobId);
            if (threat != null && threat.active && refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now) != null) {
                return true;
            }
        }
        Threat close = closeThreat(ctx, npc, now);
        if (close != null && ctx.level().getEntity(close.monsterId) instanceof LivingEntity monster
                && !isStandingDownFrom(npc, monster, now)) {
            return true;
        }
        if (shouldRunGuardLocalScan(ctx, now)) {
            List<Mob> seen = localThreats(ctx.mgr(), ctx.level(), npc, scanRadiusFor(npc), false);
            for (Mob monster : seen) reportThreat(ctx.mgr(), ctx.level(), npc, monster, true);
            if (!seen.isEmpty()) callOutIfLookout(ctx.mgr(), ctx.level(), npc);
            for (Mob monster : seen) {
                if (npc.distanceToSqr(monster) <= CLOSE_FIGHT_RANGE_SQ && !isStandingDownFrom(npc, monster, now)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Whether a mob other than {@code current} has a team with room for this defender. */
    private static boolean hasOpenTeamElsewhere(Activity.Context ctx, Threat current, long now) {
        NpcEntity npc = ctx.npc();
        for (Threat threat : threats(ctx)) {
            if (threat == current || plainlyNotFor(ctx, npc, threat)) continue;
            LivingEntity monster = refreshThreatIfDue(ctx.mgr(), ctx.level(), threat, now);
            if (monster == null || !threat.active) continue;
            if (!GuardThreatRules.isHostileMonsterForGuard(npc, monster)
                    || isGivenUp(npc, monster, now)) continue;
            pruneAssignments(ctx, threat);
            if (threat.assignedGuardIds.size() < threat.teamSize) return true;
        }
        return false;
    }

    /**
     * Whether a defender may keep fighting {@code target}. False only when the
     * team plan has just taken it off that mob (its team has enough without
     * it) and the mob is neither close nor fighting it; the activity then lets
     * go and goes back to patrol. Asked once: the answer is used up.
     */
    public static boolean mayKeepTarget(Activity.Context ctx, LivingEntity target) {
        NpcEntity npc = ctx.npc();
        UUID released = RELEASED.remove(npc.getNpcId());
        if (released == null || !released.equals(target.getUUID())) return true;
        return npc.distanceToSqr(target) <= CLOSE_FIGHT_RANGE_SQ || wasHitBy(npc, target);
    }

    /**
     * A defender is falling back hurt from {@code from}: its team gets one
     * more for a while, and the plan looks again at once.
     */
    public static void noteRetreat(Activity.Context ctx, @Nullable LivingEntity from) {
        long now = ctx.level().getGameTime();
        UUID mobId = from != null ? from.getUUID() : GUARD_ASSIGNMENTS.get(ctx.npc().getNpcId());
        if (mobId == null) return;
        Threat threat = threatById(ctx.mgr(), mobId);
        if (threat == null) return;
        threat.backupUntil = Math.max(threat.backupUntil, now + BACKUP_HOLD_TICKS);
        scheduleAssignmentRefresh(ctx.mgr(), now);
    }
}
