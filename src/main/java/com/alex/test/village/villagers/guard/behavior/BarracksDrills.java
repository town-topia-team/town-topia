package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.stats.VillagerStats;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.guard.GuardShift;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Barracks drills. Every guard and captain drills at its barracks in its own
 * off-duty window ({@link #inWindow}), with or without a captain: one defender
 * to each armor stand, standing right in front of it, and anyone left over --
 * or anyone who feels like it, more on some days than others
 * ({@link #shadowChance}) -- shadow fighting on open floor, alone in a clear
 * 2x2 or against another guard ({@link Pair}). A captain keeps a stand of its
 * own and, once a day each, leaves it to spar with one guard and to test
 * another with a kick ({@link Visit}).
 *
 * <p>Everything here is held by heartbeat: a defender renews its spot every
 * tick it drills, and a spot not renewed for {@value #CLAIM_TTL_TICKS} ticks is
 * free, so a defender that died, unloaded or changed job never keeps one. A
 * defender paused for a player's screen ({@link VillagerPause}) keeps its
 * spot, but a {@link Pair} or {@link Visit} it is part of still ends: its
 * partner does not fight on against someone standing still. Not
 * saved; the captain's own stand ({@value #TAG_CAPTAIN_STAND}) and its visit
 * day are kept in its brain data.
 */
public final class BarracksDrills {

    /**
     * Night shift: up from the day's sleep at 9,000 and drilling until its own
     * shift change (rolled 12,500-13,200), straight from the drill to work.
     */
    public static final int NIGHT_SHIFT_WINDOW_START = 9_000;
    /**
     * Day shift: from its own shift change, straight from work, until 15,600,
     * then bed. A fixed 12,000 / 13,200 edge left a gap either side of the
     * shift change, and the defender walked to bed, lay down for half a
     * minute and got up again.
     */
    public static final int DAY_SHIFT_WINDOW_END = 15_600;
    /** A night-shift defender starts up to this late in its window, so they do not all walk in together. */
    private static final int WINDOW_STAGGER_TICKS = 600;
    private static final long SALT_STAGGER = 0x0D71_11A5L;

    /** A hall nobody has drilled in for this long is forgotten (see {@link #pruneHalls}). */
    private static final long HALL_IDLE_TICKS = 24_000L;
    /** A spot not renewed for this long is free. */
    static final int CLAIM_TTL_TICKS = 40;
    /** Each day a barracks rolls how keen its guards are on shadow fighting (see {@link #shadowChance}). */
    private static final long SALT_SHADOW_MOOD = 0x5AD0_F1E7L;
    /** A guard that chose to shadow-fight looks for a partner this often; the rest fight alone. */
    static final float PAIR_CHANCE = 0.7f;
    /** A guard pairs up only with at least this long (30 s) of its drill left, and its partner's. */
    static final int PAIR_MIN_TICKS_LEFT = 600;
    /** Shadow drill spots keep this far (squared) from every other drill spot and stand. */
    private static final double SHADOW_SPACING_SQ = 2.0 * 2.0;

    static final String TAG_CAPTAIN_STAND = "DrillStandId";

    // --- Shared set schedule (see Hall#phase).
    /** Sets start on this beat of the game clock. */
    private static final int BEAT_TICKS = 10;
    /**
     * A sword set lasts 8 to 12 s. Each defender fits in as many swings as its
     * own pace allows: about three for a recruit, up to six for a master.
     */
    private static final int SET_MIN_TICKS = 160;
    private static final int SET_MAX_TICKS = 240;
    /**
     * A shield set lasts 2.25 to 2.75 s: room for a defender starting up to
     * half a second late to raise its shield the whole way through the clip.
     */
    static final int SHIELD_SET_MIN_TICKS = 45;
    static final int SHIELD_SET_MAX_TICKS = 55;
    /**
     * A practice block: the shield clip once through, at its own speed, its
     * blend in included -- 1.2 s of clip. Lowered any sooner, the arm dropped
     * half-way through the clip.
     */
    static final int PRACTICE_SHIELD_TICKS = VillagerAnimationState.WORK_BLEND_TICKS + 24;
    /** A practice blow lands 0.45 s into the shield clip, the arm up in front. */
    static final int PRACTICE_BLOCK_AT = VillagerAnimationState.WORK_BLEND_TICKS + 9;
    private static final int REST_MIN_TICKS = 60;
    private static final int REST_MAX_TICKS = 120;

    /**
     * A defender's spot: where it stands, the stand it drills at (null for a
     * shadow drill), what it faces, and the pair it shadow-fights in (null
     * when it fights alone or at a stand).
     */
    record Spot(BlockPos cell, @Nullable BarracksBuilding.Post post, Vec3 faceAt, @Nullable Pair pair,
                Set<BlockPos> area) {
        boolean shadow() {
            return post == null;
        }
    }

    /**
     * One defender's hold on a spot: the cell it stands on, the armor stand it
     * drills at (one defender to a stand) and, shadow fighting, the floor it
     * keeps clear around it.
     */
    private static final class Claim {
        final UUID holder;
        long renewedAt;
        @Nullable final UUID standId;
        final Set<BlockPos> area;

        Claim(UUID holder, long renewedAt, @Nullable UUID standId, Set<BlockPos> area) {
            this.holder = holder;
            this.renewedAt = renewedAt;
            this.standId = standId;
            this.area = area;
        }
    }

    /**
     * Two guards shadow fighting each other in a clear 3x3 of floor, on the
     * middles of two opposite sides: two blocks apart, facing each other
     * across the centre. The guard that found the space leads and runs the
     * bouts -- runs of exchanges with a rest between -- and its partner follows
     * them. They salute when they first meet and when the leader's drill is
     * over. Until a partner comes the leader shadow-drills alone, facing the
     * empty side. Like a {@link Visit}, each side renews itself every tick and
     * a side not seen for {@value #CLAIM_TTL_TICKS} ticks has gone.
     */
    static final class Pair {
        enum Stage { WAITING, SALUTE_IN, SPARRING, SALUTE_OUT, RESTING, PARTED }

        /** Blows in one exchange: the attacker's slash, played twice. */
        static final int BLOWS = 2;

        /** The two have saluted on meeting; the next partner salutes anew. */
        boolean met;
        /** The leader has saluted goodbye at the end of its drill. */
        boolean parted;
        /** Both on the night shift, or both on the day shift: only those pair up. */
        final boolean nightShift;
        /** When each side's drill window closes (game time): the pair parts before the first. */
        long leaderEndsAt;
        long partnerEndsAt = Long.MAX_VALUE;

        final UUID leader;
        final BlockPos leaderCell;
        final BlockPos partnerCell;
        /** The partner's square, which it keeps clear once it has come. */
        final Set<BlockPos> partnerArea;
        @Nullable UUID partner;
        /** The partner has walked over and is in place. */
        boolean partnerArrived;
        long leaderSeen;
        long partnerSeen;
        int leaderLevel;
        int partnerLevel;
        int leaderSlashTicks;
        int partnerSlashTicks;

        Stage stage = Stage.WAITING;
        long stageAt;
        long stageEnd;
        /** Counts bouts, so each side salutes and rests once per bout. */
        int bout;

        // The current exchange: two blows, each met by a block.
        int exchange;
        boolean leaderAttacks;
        final long[] swingAt = new long[BLOWS];
        final long[] strikeAt = new long[BLOWS];
        final long[] raiseAt = new long[BLOWS];
        final long[] lowerAt = new long[BLOWS];
        long exchangeEnd = Long.MAX_VALUE;
        long nextExchangeAt = Long.MAX_VALUE;

        Pair(UUID leader, BlockPos leaderCell, BlockPos partnerCell, Set<BlockPos> partnerArea,
             boolean nightShift, long leaderEndsAt, long now) {
            this.leader = leader;
            this.leaderCell = leaderCell;
            this.partnerCell = partnerCell;
            this.partnerArea = partnerArea;
            this.nightShift = nightShift;
            this.leaderEndsAt = leaderEndsAt;
            this.leaderSeen = now;
        }

        /** When the first of the two must stop: the time they part by. */
        long endsAt() {
            return partner == null ? leaderEndsAt : Math.min(leaderEndsAt, partnerEndsAt);
        }

        boolean leaderGone(long now) {
            return now - leaderSeen > CLAIM_TTL_TICKS;
        }

        boolean partnerGone(long now) {
            return partner == null || now - partnerSeen > CLAIM_TTL_TICKS;
        }

        /**
         * Schedules the next exchange from {@code start}. Close in level, they
         * take turns at random; five or more levels apart, the better one
         * attacks two times in three. The attacker plays its whole slash twice;
         * the defender meets each blow with one whole practice block, raised
         * so the blade lands {@link #PRACTICE_BLOCK_AT} into it. A fast
         * attacker waits between its blows for the first block to finish.
         */
        void nextExchange(long start, RandomSource random) {
            exchange++;
            int gap = leaderLevel - partnerLevel;
            float leaderOdds = Math.abs(gap) < 5 ? 0.5f : gap > 0 ? 0.65f : 0.35f;
            leaderAttacks = random.nextFloat() < leaderOdds;
            int slash = Math.max(1, leaderAttacks ? leaderSlashTicks : partnerSlashTicks);
            int strike = Math.max(1, Math.round(slash * GuardBalance.STRIKE_POINT));
            long firstSwing = Math.max(start, start + PRACTICE_BLOCK_AT - strike);
            int between = Math.max(slash + 2, PRACTICE_SHIELD_TICKS + 2);
            for (int blow = 0; blow < BLOWS; blow++) {
                swingAt[blow] = firstSwing + (long) blow * between;
                strikeAt[blow] = swingAt[blow] + strike;
                raiseAt[blow] = strikeAt[blow] - PRACTICE_BLOCK_AT;
                lowerAt[blow] = raiseAt[blow] + PRACTICE_SHIELD_TICKS;
            }
            exchangeEnd = Math.max(swingAt[BLOWS - 1] + slash + 2, lowerAt[BLOWS - 1]);
            nextExchangeAt = Long.MAX_VALUE;
        }

        /**
         * Calls off the exchange under way -- the captain has come to test one
         * of the two: no more blows from it, no more blocks, and the next one
         * is scheduled afresh once the captain has gone.
         */
        void callOff(long now) {
            for (int blow = 0; blow < BLOWS; blow++) {
                swingAt[blow] = strikeAt[blow] = raiseAt[blow] = lowerAt[blow] = Long.MAX_VALUE;
            }
            exchangeEnd = Math.min(exchangeEnd, now);
            nextExchangeAt = Long.MAX_VALUE;
        }

        /** Whether the defender holds its shield up at {@code now}: through each block, whole. */
        boolean blocking(long now) {
            for (int blow = 0; blow < BLOWS; blow++) {
                if (now >= raiseAt[blow] && now < lowerAt[blow]) return true;
            }
            return false;
        }
    }

    /** Where in the shared drill rhythm a barracks is: a set, or the rest after it. */
    record Phase(boolean set, long setStart, long setEnd, boolean shield, int index) {}

    /** One barracks' drills. */
    static final class Hall {
        final Map<BlockPos, Claim> cells = new HashMap<>();
        long setStart = Long.MIN_VALUE;
        long setEnd;
        long nextSetStart;
        boolean shield;
        int index;
        int setsUntilShield = 3;
        /** Game time a spot here was last claimed or renewed. */
        long lastUsed;

        /** The captain drilling here now, and when it came and went (for the guards' salutes). */
        @Nullable UUID captain;
        long captainArrivedAt = Long.MIN_VALUE / 2;
        long captainLeftAt = Long.MIN_VALUE / 2;

        @Nullable Visit visit;
        /** Guards shadow fighting each other here, and leaders still waiting for a partner. */
        final List<Pair> pairs = new ArrayList<>();
        /** When a defender here last began a power-up, so two never start together. */
        long lastPowerUpAt = Long.MIN_VALUE / 2;

        /** Where the room is in the drill rhythm at {@code now}; starts and advances it lazily. */
        Phase phase(long now, RandomSource random) {
            if (setStart == Long.MIN_VALUE || now > nextSetStart + REST_MAX_TICKS) {
                // First set, or the room stood empty a while: start fresh on the next beat.
                startSet(Math.floorDiv(now, BEAT_TICKS) * BEAT_TICKS + 2L * BEAT_TICKS, random);
            }
            while (now >= nextSetStart) startSet(nextSetStart, random);
            return new Phase(now >= setStart && now < setEnd, setStart, setEnd, shield, index);
        }

        private void startSet(long start, RandomSource random) {
            index++;
            setStart = start;
            shield = --setsUntilShield <= 0;
            if (shield) setsUntilShield = 3 + random.nextInt(2);
            setEnd = start + (shield
                    ? SHIELD_SET_MIN_TICKS + random.nextInt(SHIELD_SET_MAX_TICKS - SHIELD_SET_MIN_TICKS + 1)
                    : SET_MIN_TICKS + random.nextInt(SET_MAX_TICKS - SET_MIN_TICKS + 1));
            long rest = REST_MIN_TICKS + random.nextInt(REST_MAX_TICKS - REST_MIN_TICKS + 1);
            nextSetStart = Math.floorDiv(setEnd + rest + BEAT_TICKS - 1, BEAT_TICKS) * BEAT_TICKS;
        }

        /**
         * Whether {@code cell} is someone else's: the spot it stands on, or
         * floor its shadow fighting keeps clear.
         */
        boolean cellTaken(BlockPos cell, UUID self, long now) {
            return cellTaken(cell, self, now, null);
        }

        /** As {@link #cellTaken(BlockPos, UUID, long)}, not counting {@code except}'s hold (a pair's leader). */
        boolean cellTaken(BlockPos cell, UUID self, long now, @Nullable UUID except) {
            for (Map.Entry<BlockPos, Claim> e : cells.entrySet()) {
                Claim claim = e.getValue();
                if (claim.holder.equals(self) || claim.holder.equals(except)
                        || VillagerPause.stale(claim.holder, claim.renewedAt, CLAIM_TTL_TICKS, now)) continue;
                if (e.getKey().equals(cell) || claim.area.contains(cell)) return true;
            }
            return false;
        }

        /** Whether someone else drills at this armor stand: one defender to a stand. */
        boolean standTaken(UUID standId, UUID self, long now) {
            for (Claim claim : cells.values()) {
                if (standId.equals(claim.standId) && !claim.holder.equals(self)
                        && !VillagerPause.stale(claim.holder, claim.renewedAt, CLAIM_TTL_TICKS, now)) return true;
            }
            return false;
        }

        /** Renews this defender's hold on {@code cell}, or takes it with nothing kept clear. */
        void claim(BlockPos cell, UUID self, long now) {
            lastUsed = now;
            Claim claim = cells.get(cell);
            if (claim != null && claim.holder.equals(self)) {
                claim.renewedAt = now;
            } else {
                cells.put(cell, new Claim(self, now, null, Set.of()));
            }
        }

        /** Takes {@code cell}, at {@code standId} if any, keeping {@code area} clear around it. */
        void claim(BlockPos cell, UUID self, long now, @Nullable UUID standId, Set<BlockPos> area) {
            lastUsed = now;
            cells.put(cell, new Claim(self, now, standId, Set.copyOf(area)));
        }

        /** A pair whose leader is waiting for a partner, or null. Forgets pairs both sides have left. */
        @Nullable
        Pair openPair(UUID self, boolean nightShift, long now) {
            pairs.removeIf(pair -> pair.leaderGone(now) && pair.partnerGone(now));
            for (Pair pair : pairs) {
                if (pair.leader.equals(self) || pair.leaderGone(now) || pair.stage == Pair.Stage.PARTED) continue;
                // Partners go off together: same shift, and long enough left to be worth starting.
                if (pair.nightShift != nightShift || pair.leaderEndsAt - now < PAIR_MIN_TICKS_LEFT) continue;
                if (pair.partnerGone(now)) return pair;
            }
            return null;
        }

        void release(BlockPos cell, UUID self) {
            Claim claim = cells.get(cell);
            if (claim != null && claim.holder.equals(self)) cells.remove(cell);
        }

        /** Live holders and the cells they hold. */
        Map<UUID, BlockPos> holders(long now) {
            Map<UUID, BlockPos> live = new HashMap<>();
            Iterator<Map.Entry<BlockPos, Claim>> it = cells.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<BlockPos, Claim> e = it.next();
                if (VillagerPause.stale(e.getValue().holder, e.getValue().renewedAt, CLAIM_TTL_TICKS, now)) {
                    it.remove();
                } else {
                    live.put(e.getValue().holder, e.getKey());
                }
            }
            return live;
        }
    }

    /**
     * A captain's daily sparring visit to one guard. The captain drives the
     * timeline (it advances the exchanges); both sides read it every tick and
     * renew their side, and a side not seen for {@value #CLAIM_TTL_TICKS} ticks
     * ends it.
     */
    static final class Visit {
        enum Stage { WALKING, SPARRING, ENDING }

        final UUID captain;
        final UUID guard;
        final BlockPos guardCell;
        final BlockPos captainCell;
        /** Where exactly the captain stands: {@link #SPAR_DISTANCE} from the guard when there is room. */
        final Vec3 captainStandAt;
        /**
         * The captain's daily test instead of a spar: it walks up, puts one
         * kick into the guard's shield, they salute and it goes back.
         */
        final boolean kickTest;
        Stage stage = Stage.WALKING;
        long captainSeen;
        long guardSeen;
        long sparEnd;
        long endingAt;

        // The current exchange: one strike and one block.
        int exchange;
        boolean captainAttacks;
        /** The captain's strike this exchange is a kick. */
        boolean kick;
        long exchangeStart;
        long swingAt;
        long strikeAt;
        long raiseUntil;
        long exchangeEnd = Long.MAX_VALUE;
        long nextExchangeAt = Long.MAX_VALUE;

        // A reply bubble the guard shows a moment after the captain speaks.
        long replyAt = Long.MAX_VALUE;
        @Nullable ThoughtIcon reply;

        /**
         * Schedules the next exchange from {@code start}, captain striking first
         * and then taking turns. The defender meets the blow with one whole
         * practice block ({@link #PRACTICE_SHIELD_TICKS}), raised so the blade
         * or the boot lands {@link #PRACTICE_BLOCK_AT} into it, the arm up in
         * front; the swing starts that much before.
         */
        void nextExchange(long start, int captainSwingTicks, int guardSwingTicks, boolean captainKicks) {
            exchange++;
            captainAttacks = exchange % 2 == 1;
            kick = captainAttacks && captainKicks;
            int swing = kick ? VillagerAnimationState.KICK_TICKS
                    : captainAttacks ? captainSwingTicks : guardSwingTicks;
            int strike = kick ? VillagerAnimationState.KICK_CONTACT_TICKS
                    : Math.max(1, Math.round(swing * GuardBalance.STRIKE_POINT));
            swingAt = Math.max(start, start + PRACTICE_BLOCK_AT - strike);
            strikeAt = swingAt + strike;
            exchangeStart = strikeAt - PRACTICE_BLOCK_AT;
            raiseUntil = exchangeStart + PRACTICE_SHIELD_TICKS;
            exchangeEnd = Math.max(swingAt + swing + 2, raiseUntil);
            nextExchangeAt = Long.MAX_VALUE;
        }

        Visit(UUID captain, UUID guard, BlockPos guardCell, BlockPos captainCell, Vec3 captainStandAt,
              boolean kickTest, long now) {
            this.captain = captain;
            this.guard = guard;
            this.guardCell = guardCell;
            this.captainCell = captainCell;
            this.captainStandAt = captainStandAt;
            this.kickTest = kickTest;
            this.captainSeen = now;
            this.guardSeen = now;
        }

        boolean stale(long now) {
            return now - captainSeen > CLAIM_TTL_TICKS || now - guardSeen > CLAIM_TTL_TICKS;
        }
    }

    private static final Map<UUID, Hall> HALLS = new HashMap<>();
    /**
     * Defenders that found no spot, or no way to it, and the game time until
     * which they stop trying: the end of that window. Without it a guard was
     * sent back to bed and woken for the drill again every two seconds.
     */
    private static final Map<UUID, Long> GAVE_UP_UNTIL = new HashMap<>();

    private BarracksDrills() {}

    static Hall hall(BarracksBuilding barracks) {
        return HALLS.computeIfAbsent(barracks.getItemFrameId(), id -> new Hall());
    }

    /**
     * Forgets halls nobody has drilled in for a day -- a barracks taken down
     * kept its hall forever -- and any from another world's clock (used "in the
     * future"). Run when a drill starts, which is rare enough.
     */
    private static void pruneHalls(Hall keep, long now) {
        HALLS.values().removeIf(hall -> hall != keep && hall.visit == null
                && (hall.lastUsed > now || now - hall.lastUsed > HALL_IDLE_TICKS));
    }

    // =========================================================================
    // When and where
    // =========================================================================

    /** A defender's drill window today, as times of day. */
    private record Window(int start, int end) {}

    /**
     * This defender's window: on the night shift from 9,000 (up to 600 ticks
     * late, fixed per defender) until its own shift change; on the day shift
     * from its own shift change until 15,600. A day-shift defender held on
     * at its post for the handover starts when it is let go.
     */
    private static Window window(Activity.Context ctx) {
        long dayTime = ctx.level().getDayTime();
        int change = GuardShift.shiftChange(ctx.brain().eveningSchedule(), EveningSchedule.dayIndex(dayTime));
        if (GuardSettings.of(ctx.npc()).patrolAtNightOnly()) {
            int stagger = EveningSchedule.sharedRoll(ctx.model().npcId, 0L, SALT_STAGGER, WINDOW_STAGGER_TICKS + 1);
            return new Window(NIGHT_SHIFT_WINDOW_START + stagger, change);
        }
        return new Window(change, DAY_SHIFT_WINDOW_END);
    }

    /** Whether it is this defender's drill window (see {@link #window}). */
    public static boolean inWindow(Activity.Context ctx) {
        Window window = window(ctx);
        long time = EveningSchedule.timeOfDay(ctx.level().getDayTime());
        return time >= window.start() && time < window.end();
    }

    /** Time of day this defender's drill window closes. */
    static int windowEnd(Activity.Context ctx) {
        return window(ctx).end();
    }

    /** Game time this defender's drill window closes: now, if it already has. */
    static long drillEndsAt(Activity.Context ctx) {
        long left = windowEnd(ctx) - EveningSchedule.timeOfDay(ctx.level().getDayTime());
        return ctx.level().getGameTime() + Math.max(0L, left);
    }

    /** How far through its window this defender is, 0 to 1. */
    static double windowProgress(Activity.Context ctx) {
        Window window = window(ctx);
        long time = EveningSchedule.timeOfDay(ctx.level().getDayTime());
        return Math.clamp((time - window.start()) / (double) Math.max(1, window.end() - window.start()), 0.0, 1.0);
    }

    /** Fed enough to drill. Unhappiness never stops a drill; a sad guard drills slower. */
    static boolean fitToDrill(Activity.Context ctx) {
        VillagerStats needs = ctx.brain().needs();
        return needs.hungerRules().canRoutineWork(needs);
    }

    /**
     * What an off-duty guard or captain should do now: drill at its barracks
     * when it is its window and it is fit for it. Null otherwise.
     */
    @Nullable
    public static Activity drillFor(Activity.Context ctx) {
        Profession profession = ctx.npc().getProfession();
        if (!profession.isGuardLike()) return null;
        if (!inWindow(ctx) || !fitToDrill(ctx) || gaveUp(ctx)) return null;
        if (barracksFor(ctx) == null) return null;
        return new DrillActivity(profession == Profession.CAPTAIN);
    }

    /**
     * A captain on duty whose own shift has no guards: today's sparring visit
     * becomes an inspection of the other shift's drill, if guards are drilling
     * at its barracks now and it has not had its visit today. Null otherwise.
     * A captain with guards on its own shift visits them at its own drill.
     */
    @Nullable
    public static Activity inspectionFor(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        if (npc.getProfession() != Profession.CAPTAIN) return null;
        if (DrillActivity.visitedOn(npc, EveningSchedule.dayIndex(ctx.level().getDayTime()))) return null;
        boolean nightShift = GuardSettings.of(npc).patrolAtNightOnly();
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity puppet = model.puppet();
            if (puppet != null && puppet.isAlive() && puppet.getProfession() == Profession.GUARD
                    && GuardSettings.of(puppet).patrolAtNightOnly() == nightShift) {
                return null;
            }
        }
        BarracksBuilding barracks = barracksFor(ctx);
        if (barracks == null) return null;
        Hall hall = HALLS.get(barracks.getItemFrameId());
        if (hall == null || hall.visit != null || hall.holders(ctx.level().getGameTime()).isEmpty()) return null;
        return new DrillActivity(true, true);
    }

    /** The room tile nearest the barracks door: where an inspecting captain comes in. */
    @Nullable
    static BlockPos entryCell(BarracksBuilding barracks) {
        BlockPos door = barracks.getDoorPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos cell : barracks.getNavMeshBlocks()) {
            double dist = cell.distSqr(door);
            if (dist < bestDist) {
                bestDist = dist;
                best = cell;
            }
        }
        return best;
    }

    /** Whether this defender gave up on today's drill (see {@link #giveUpForWindow}). */
    private static boolean gaveUp(Activity.Context ctx) {
        Long until = GAVE_UP_UNTIL.get(ctx.model().npcId);
        long now = ctx.level().getGameTime();
        // Never more than a window ahead; anything farther is another world's clock.
        return until != null && now < until && until - now <= HALL_IDLE_TICKS;
    }

    /** No free spot, or no way to it: this defender stops trying until its window has closed. */
    static void giveUpForWindow(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        long left = Math.max(0L, windowEnd(ctx) - EveningSchedule.timeOfDay(ctx.level().getDayTime()));
        GAVE_UP_UNTIL.put(ctx.model().npcId, now + left);
        if (GAVE_UP_UNTIL.size() > 256) GAVE_UP_UNTIL.values().removeIf(until -> until < now);
    }

    /** The barracks this defender sleeps in, else the nearest valid one in its village. */
    @Nullable
    static BarracksBuilding barracksFor(Activity.Context ctx) {
        VillageManager mgr = ctx.mgr();
        if (mgr.getAssignedBedBuilding(ctx.model().npcId) instanceof BarracksBuilding home && home.isValid()) {
            return home;
        }
        BarracksBuilding best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos from = ctx.npc().blockPosition();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof BarracksBuilding barracks) || !barracks.isValid()) continue;
            double dist = barracks.getDoorPos().distSqr(from);
            if (dist < bestDist) {
                bestDist = dist;
                best = barracks;
            }
        }
        return best;
    }

    // =========================================================================
    // Spots
    // =========================================================================

    /**
     * How often today a guard with a stand free shadow-fights instead: the
     * room's mood for the day, the same for every guard in it. One day in four
     * nobody does; most days a few do; now and then half or most of the room.
     */
    static float shadowChance(BarracksBuilding barracks, long day) {
        int roll = EveningSchedule.sharedRoll(barracks.getItemFrameId(), day, SALT_SHADOW_MOOD, 100);
        if (roll < 25) return 0f;
        if (roll < 70) return 0.25f;
        if (roll < 90) return 0.5f;
        return 0.8f;
    }

    /**
     * Claims a spot for this defender: a stand when it wants one and one is
     * free, else shadow fighting. A captain takes its own stand back first;
     * a guard leaves every captain's stand alone. A shadow-fighting guard
     * joins a guard waiting for a partner, else finds a 3x3 for two (always
     * when it had no stand, {@link #PAIR_CHANCE} when it chose to), else
     * fights alone in a clear 2x2. Null when the room has no room left at all.
     */
    @Nullable
    static Spot claimSpot(Activity.Context ctx, BarracksBuilding barracks, boolean captain, boolean wantShadow) {
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        Hall hall = hall(barracks);
        long now = ctx.level().getGameTime();
        pruneHalls(hall, now);
        UUID self = ctx.model().npcId;
        RandomSource random = ctx.level().random;
        List<BarracksBuilding.Post> posts = barracks.getPosts();

        if (!wantShadow && !posts.isEmpty()) {
            List<BarracksBuilding.Post> order = new ArrayList<>(posts);
            if (captain) {
                UUID own = ownStand(ctx.npc());
                if (own != null) order.sort((a, b) -> Boolean.compare(!a.standId().equals(own), !b.standId().equals(own)));
            } else {
                List<UUID> reserved = captainStands(ctx.mgr(), ctx.npc());
                order.removeIf(post -> reserved.contains(post.standId()));
            }
            for (BarracksBuilding.Post post : order) {
                if (hall.standTaken(post.standId(), self, now)) continue;
                BlockPos cell = standingCell(barracks, graph, hall, post, self, now);
                if (cell == null) continue;
                hall.claim(cell, self, now, post.standId(), Set.of());
                if (captain) ctx.npc().getBrainData().putUUID(TAG_CAPTAIN_STAND, post.standId());
                return new Spot(cell, post, Vec3.atCenterOf(post.standPos()).add(0.0, 0.5, 0.0), null, Set.of());
            }
        }

        if (!captain) {
            boolean nightShift = GuardSettings.of(ctx.npc()).patrolAtNightOnly();
            long endsAt = drillEndsAt(ctx);
            Pair open = endsAt - now < PAIR_MIN_TICKS_LEFT ? null : hall.openPair(self, nightShift, now);
            if (open != null && !hall.cellTaken(open.partnerCell, self, now, open.leader)) {
                open.partner = self;
                open.partnerArrived = false;
                open.partnerSeen = now;
                open.partnerEndsAt = endsAt;
                hall.claim(open.partnerCell, self, now, null, open.partnerArea);
                return new Spot(open.partnerCell, null, eyeLevel(open.leaderCell), open, open.partnerArea);
            }
            // A guard with little of its drill left fights alone rather than start a pair.
            if ((!wantShadow || random.nextFloat() < PAIR_CHANCE) && endsAt - now >= PAIR_MIN_TICKS_LEFT) {
                PairSite site = pairSite(barracks, graph, hall, self, now, random);
                if (site != null) {
                    Pair pair = new Pair(self, site.leaderCell(), site.partnerCell(), site.partnerArea(),
                            nightShift, endsAt, now);
                    hall.pairs.add(pair);
                    hall.claim(site.leaderCell(), self, now, null, site.square());
                    return new Spot(site.leaderCell(), null, eyeLevel(site.partnerCell()), pair, site.square());
                }
            }
        }

        SoloSite solo = soloSite(barracks, graph, hall, self, now, random);
        if (solo == null) return null;
        hall.claim(solo.cell(), self, now, null, solo.square());
        return new Spot(solo.cell(), null, shadowFacing(barracks, solo.cell()), null, solo.square());
    }

    /** Where a defender standing on {@code cell} looks: a partner's eyes, or the room at eye height. */
    private static Vec3 eyeLevel(BlockPos cell) {
        return new Vec3(cell.getX() + 0.5, cell.getY() + 1.5, cell.getZ() + 0.5);
    }

    @Nullable
    private static UUID ownStand(NpcEntity captain) {
        CompoundTag data = captain.getBrainData();
        return data.hasUUID(TAG_CAPTAIN_STAND) ? data.getUUID(TAG_CAPTAIN_STAND) : null;
    }

    /**
     * The own stands of the village's captains on {@code guard}'s shift, which it
     * leaves free. A captain on the other shift never drills in this window,
     * so its stand is anyone's meanwhile.
     */
    private static List<UUID> captainStands(VillageManager mgr, NpcEntity guard) {
        boolean nightShift = GuardSettings.of(guard).patrolAtNightOnly();
        List<UUID> stands = new ArrayList<>();
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity puppet = model.puppet();
            if (puppet == null || !puppet.isAlive() || puppet == guard) continue;
            if (puppet.getProfession() != Profession.CAPTAIN) continue;
            if (GuardSettings.of(puppet).patrolAtNightOnly() != nightShift) continue;
            UUID stand = ownStand(puppet);
            if (stand != null) stands.add(stand);
        }
        return stands;
    }

    /**
     * Where a defender stands to drill at {@code post}: right in front of the
     * stand, else at its left, else at its right -- never on the stand's own
     * cell or another defender's spot. Null when none of the three will do.
     */
    @Nullable
    private static BlockPos standingCell(BarracksBuilding barracks, NavGraph graph, Hall hall,
                                         BarracksBuilding.Post post, UUID self, long now) {
        BlockPos stand = post.standPos();
        BlockPos[] candidates = {
                stand.relative(post.facing()),
                stand.relative(post.facing().getCounterClockWise()),
                stand.relative(post.facing().getClockWise())};
        for (BlockPos cell : candidates) {
            if (usableCell(barracks, graph, cell) && !hall.cellTaken(cell, self, now)) return cell;
        }
        return null;
    }

    /** A routable room tile that is no stand's cell and not on a bed. */
    private static boolean usableCell(BarracksBuilding barracks, NavGraph graph, BlockPos cell) {
        if (!barracks.getNavMeshBlocks().contains(cell)) return false;
        NavNode node = graph.nodeAt(cell.getX(), cell.getY(), cell.getZ());
        if (node == null || !node.isRoutable()) return false;
        if (barracks.isBedBlock(cell) || barracks.isBedBlock(cell.below())) return false;
        for (BarracksBuilding.Post post : barracks.getPosts()) {
            if (post.standPos().equals(cell)) return false;
        }
        return true;
    }

    /** A 3x3 for two shadow fighters: who stands where, the whole square (the leader keeps it clear), the partner's part. */
    private record PairSite(BlockPos leaderCell, BlockPos partnerCell, Set<BlockPos> square, Set<BlockPos> partnerArea) {}

    /** Open floor for one shadow fighter: where it stands and the 2x2 it keeps clear. */
    private record SoloSite(BlockPos cell, Set<BlockPos> square) {}

    /**
     * Floor a shadow fighter can use: a routable room tile off the doorway,
     * nobody's spot or kept-clear floor, and not right beside an armor stand,
     * where a stand's defender stands.
     */
    private static boolean openFloor(BarracksBuilding barracks, NavGraph graph, Hall hall,
                                     BlockPos cell, UUID self, long now) {
        BlockPos door = barracks.getDoorPos();
        if (cell.equals(door) || cell.equals(door.relative(barracks.getInteriorDirection()))) return false;
        if (!usableCell(barracks, graph, cell) || hall.cellTaken(cell, self, now)) return false;
        for (BarracksBuilding.Post post : barracks.getPosts()) {
            BlockPos stand = post.standPos();
            if (Math.abs(stand.getX() - cell.getX()) <= 1 && Math.abs(stand.getZ() - cell.getZ()) <= 1
                    && Math.abs(stand.getY() - cell.getY()) <= 1) return false;
        }
        return true;
    }

    /**
     * A 3x3 of open floor, all on one level, for two guards to shadow-fight
     * each other: the leader on the middle of one side and its partner on the
     * middle of the opposite side, two blocks apart and facing each other
     * across the centre. The partner keeps the centre row and its own side
     * clear, should the leader go. Picked at random; null when the room has
     * no such space left.
     */
    @Nullable
    private static PairSite pairSite(BarracksBuilding barracks, NavGraph graph, Hall hall,
                                     UUID self, long now, RandomSource random) {
        Map<UUID, BlockPos> taken = hall.holders(now);
        List<PairSite> options = new ArrayList<>();
        for (BlockPos centre : barracks.getNavMeshBlocks()) {
            Set<BlockPos> square = new HashSet<>();
            boolean clear = true;
            for (int dx = -1; dx <= 1 && clear; dx++) {
                for (int dz = -1; dz <= 1 && clear; dz++) {
                    BlockPos cell = centre.offset(dx, 0, dz);
                    clear = openFloor(barracks, graph, hall, cell, self, now);
                    square.add(cell);
                }
            }
            if (!clear) continue;
            // Both ways across: west-east and north-south.
            for (Direction toward : new Direction[]{Direction.EAST, Direction.SOUTH}) {
                BlockPos leader = centre.relative(toward.getOpposite());
                BlockPos partner = centre.relative(toward);
                if (tooClose(leader, taken, self, barracks) || tooClose(partner, taken, self, barracks)) continue;
                Set<BlockPos> partnerArea = new HashSet<>();
                for (BlockPos cell : square) {
                    int along = (cell.getX() - centre.getX()) * toward.getStepX()
                            + (cell.getZ() - centre.getZ()) * toward.getStepZ();
                    if (along >= 0) partnerArea.add(cell);
                }
                // Either guard may lead from either side.
                if (random.nextBoolean()) {
                    options.add(new PairSite(leader, partner, square, partnerArea));
                } else {
                    Set<BlockPos> otherArea = new HashSet<>();
                    for (BlockPos cell : square) {
                        int along = (cell.getX() - centre.getX()) * toward.getStepX()
                                + (cell.getZ() - centre.getZ()) * toward.getStepZ();
                        if (along <= 0) otherArea.add(cell);
                    }
                    options.add(new PairSite(partner, leader, square, otherArea));
                }
            }
        }
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    /**
     * Open floor for one shadow fighter: a tile with a clear 2x2 square of
     * open floor it is a corner of, at least two blocks from every stand and
     * every spot already taken. Picked at random.
     */
    @Nullable
    private static SoloSite soloSite(BarracksBuilding barracks, NavGraph graph, Hall hall,
                                     UUID self, long now, RandomSource random) {
        Map<UUID, BlockPos> taken = hall.holders(now);
        List<SoloSite> options = new ArrayList<>();
        for (BlockPos cell : barracks.getNavMeshBlocks()) {
            if (!openFloor(barracks, graph, hall, cell, self, now) || tooClose(cell, taken, self, barracks)) continue;
            for (Direction xSide : new Direction[]{Direction.EAST, Direction.WEST}) {
                for (Direction zSide : new Direction[]{Direction.SOUTH, Direction.NORTH}) {
                    Set<BlockPos> square = Set.of(cell, cell.relative(xSide), cell.relative(zSide),
                            cell.relative(xSide).relative(zSide));
                    boolean clear = true;
                    for (BlockPos c : square) {
                        if (!openFloor(barracks, graph, hall, c, self, now)) {
                            clear = false;
                            break;
                        }
                    }
                    if (clear) options.add(new SoloSite(cell, square));
                }
            }
        }
        return options.isEmpty() ? null : options.get(random.nextInt(options.size()));
    }

    private static boolean tooClose(BlockPos cell, Map<UUID, BlockPos> taken, UUID self, BarracksBuilding barracks) {
        for (Map.Entry<UUID, BlockPos> e : taken.entrySet()) {
            if (!e.getKey().equals(self) && e.getValue().distSqr(cell) < SHADOW_SPACING_SQ) return true;
        }
        for (BarracksBuilding.Post post : barracks.getPosts()) {
            if (post.standPos().distSqr(cell) < SHADOW_SPACING_SQ) return true;
        }
        return false;
    }

    /** A shadow drill faces the middle of the room, or the far wall when it stands in the middle. */
    private static Vec3 shadowFacing(BarracksBuilding barracks, BlockPos cell) {
        Vec3 centre = barracks.roomBox().getCenter();
        Vec3 at = Vec3.atBottomCenterOf(cell);
        double dx = centre.x - at.x;
        double dz = centre.z - at.z;
        if (dx * dx + dz * dz < 2.25) {
            BlockPos ahead = cell.relative(barracks.getInteriorDirection(), 4);
            return new Vec3(ahead.getX() + 0.5, at.y + 1.5, ahead.getZ() + 0.5);
        }
        return new Vec3(centre.x, at.y + 1.5, centre.z);
    }

    /** Renews this defender's hold on a cell it only stands on (a captain's sparring cell). */
    static void renew(BarracksBuilding barracks, BlockPos cell, UUID self, long now) {
        hall(barracks).claim(cell, self, now);
    }

    /** Renews this defender's hold on its spot, retaking its stand and its clear floor if the hold lapsed. */
    static void renew(BarracksBuilding barracks, Spot spot, UUID self, long now) {
        Hall hall = hall(barracks);
        Claim claim = hall.cells.get(spot.cell());
        if (claim != null && claim.holder.equals(self)) {
            hall.claim(spot.cell(), self, now);
        } else {
            hall.claim(spot.cell(), self, now, spot.post() != null ? spot.post().standId() : null, spot.area());
        }
    }

    static void release(BarracksBuilding barracks, BlockPos cell, UUID self) {
        hall(barracks).release(cell, self);
    }

    /**
     * This defender stops shadow fighting in {@code pair}. A leader that goes
     * leaves its partner to fight alone; a partner that goes leaves the leader
     * waiting for another.
     */
    static void leavePair(Pair pair, UUID self) {
        if (pair.leader.equals(self)) {
            pair.leaderSeen = Long.MIN_VALUE / 2;
        } else if (self.equals(pair.partner)) {
            pair.partner = null;
            pair.partnerArrived = false;
            pair.partnerEndsAt = Long.MAX_VALUE;
        }
    }

    /**
     * Where the captain spars from: the cell it holds, the floor it keeps
     * clear, and the exact spot it stands on, which need not be a cell's middle.
     */
    record SparSpot(BlockPos cell, Set<BlockPos> area, Vec3 standAt) {}

    /** Captain and guard stand this far apart, middle to middle: half a block of air between them. */
    static final double SPAR_DISTANCE = 1.5;

    /**
     * Where the captain spars with the guard on {@code partner}, best first:
     * two blocks out in a straight line and half a block back in, so they
     * stand {@value #SPAR_DISTANCE} apart with the tile between them kept
     * clear; else a diagonal neighbour (1.4 apart); else two out (2 apart);
     * else, as before, the tile right beside it. Random among equals.
     */
    @Nullable
    static SparSpot sparringSpot(BarracksBuilding barracks, NavGraph graph, BlockPos partner,
                                 UUID self, long now, RandomSource random) {
        Hall hall = hall(barracks);
        List<SparSpot> best = new ArrayList<>();
        int bestRank = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos near = partner.relative(d);
            BlockPos far = partner.relative(d, 2);
            BlockPos diagonal = near.relative(d.getClockWise());
            boolean nearOpen = sparFloor(barracks, graph, hall, near, self, now);
            boolean farOpen = sparFloor(barracks, graph, hall, far, self, now);
            Vec3 centre = Vec3.atBottomCenterOf(partner);
            List<SparSpot> found = new ArrayList<>(1);
            int rank;
            if (nearOpen && farOpen) {
                rank = 0;
                found.add(new SparSpot(far, Set.of(near, far), centre.add(
                        d.getStepX() * SPAR_DISTANCE, 0.0, d.getStepZ() * SPAR_DISTANCE)));
            } else if (sparFloor(barracks, graph, hall, diagonal, self, now)) {
                rank = 1;
                found.add(new SparSpot(diagonal, Set.of(diagonal), Vec3.atBottomCenterOf(diagonal)));
            } else if (farOpen) {
                rank = 2;
                found.add(new SparSpot(far, Set.of(far), Vec3.atBottomCenterOf(far)));
            } else if (nearOpen) {
                rank = 3;
                found.add(new SparSpot(near, Set.of(near), Vec3.atBottomCenterOf(near)));
            } else {
                continue;
            }
            if (rank < bestRank) {
                bestRank = rank;
                best.clear();
            }
            if (rank == bestRank) best.addAll(found);
        }
        return best.isEmpty() ? null : best.get(random.nextInt(best.size()));
    }

    /** A routable room tile nobody else holds, for the captain to stand on or keep clear. */
    private static boolean sparFloor(BarracksBuilding barracks, NavGraph graph, Hall hall,
                                     BlockPos cell, UUID self, long now) {
        return usableCell(barracks, graph, cell) && !hall.cellTaken(cell, self, now);
    }

    /** The live villager with this id in the village, or null. */
    @Nullable
    static NpcEntity puppet(VillageManager mgr, UUID id) {
        for (VillagerModel model : mgr.getVillagers()) {
            if (!model.npcId.equals(id)) continue;
            NpcEntity puppet = model.puppet();
            return puppet != null && puppet.isAlive() ? puppet : null;
        }
        return null;
    }

    @Nullable
    static VillagerModel model(VillageManager mgr, UUID id) {
        for (VillagerModel model : mgr.getVillagers()) {
            if (model.npcId.equals(id)) return model;
        }
        return null;
    }
}
