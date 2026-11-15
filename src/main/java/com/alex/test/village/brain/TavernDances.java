package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * When a pair of tavern guests gets up to dance (see {@link PairDance}).
 *
 * <p>Only while the bard is playing: the bard's evening set calls
 * {@link #duringSet} every tick it is playing (the piano or the instruments),
 * which is also how a dance knows the music is still on. One pair at a time
 * per tavern, and a pause of one to two minutes after each dance before the
 * next pair may start.
 *
 * <p>Who dances: two guests settled at this tavern (seated or standing),
 * not eating, not about to leave, not the bard, children or defenders. More
 * sociable guests are picked more often.
 */
public final class TavernDances {

    /** How often a playing set looks for a pair. */
    private static final int CHECK_TICKS = 100;
    /** One look in this many starts a dance. */
    private static final int CHANCE = 3;
    /** After the music starts, before the first dance. */
    private static final int FIRST_DANCE_AFTER_TICKS = 400;
    /** Pause after a dance: this, plus up to the same again. */
    private static final int PAUSE_TICKS = 1200;
    /** A guest must have been settled this long. */
    private static final int SETTLED_TICKS = 200;
    /** And be staying at least this much longer. */
    private static final int STAYING_TICKS = 900;
    /** Whole loops of the 1.8 s clip: 6-9, about 11-16 seconds. */
    private static final int MIN_CYCLES = 6;
    private static final int EXTRA_CYCLES = 4;
    /** Set calls stop for longer than this: the music is off. */
    private static final int MUSIC_GAP_TICKS = 40;

    private static final class Tavern {
        long playedAt = Long.MIN_VALUE;
        long nextAllowedAt = Long.MIN_VALUE;
    }

    private static final Map<ServerLevel, Map<UUID, Tavern>> TAVERNS = new WeakHashMap<>();

    private TavernDances() {}

    private static Tavern state(ServerLevel level, UUID tavernId) {
        return TAVERNS.computeIfAbsent(level, l -> new HashMap<>()).computeIfAbsent(tavernId, id -> new Tavern());
    }

    /** Whether a bard is playing at this tavern right now. */
    public static boolean musicPlaying(ServerLevel level, UUID tavernId) {
        Map<UUID, Tavern> map = TAVERNS.get(level);
        Tavern t = map != null ? map.get(tavernId) : null;
        return t != null && level.getGameTime() - t.playedAt <= MUSIC_GAP_TICKS;
    }

    /** A dance ended: the next one waits a while. */
    static void onDanceEnded(ServerLevel level, PairDance dance) {
        if (dance.tavernId() == null) return;
        state(level, dance.tavernId()).nextAllowedAt =
                level.getGameTime() + PAUSE_TICKS + level.getRandom().nextInt(PAUSE_TICKS + 1);
    }

    /** Called by the bard every tick it plays its evening set at {@code tavern}. */
    public static void duringSet(Activity.Context ctx, Building tavern) {
        ServerLevel level = ctx.level();
        long now = level.getGameTime();
        UUID tavernId = tavern.getItemFrameId();
        Tavern state = state(level, tavernId);
        // Music starting again after a gap: the first dance waits a little.
        if (now - state.playedAt > MUSIC_GAP_TICKS && state.nextAllowedAt < now + FIRST_DANCE_AFTER_TICKS) {
            state.nextAllowedAt = Math.max(state.nextAllowedAt, now + FIRST_DANCE_AFTER_TICKS);
        }
        state.playedAt = now;

        if (Math.floorMod(now + tavernId.hashCode(), CHECK_TICKS) != 0) return;
        if (now < state.nextAllowedAt || PairDance.atTavern(level, tavernId) != null) return;
        if (level.getRandom().nextInt(CHANCE) != 0) return;

        List<VillagerModel> guests = candidates(ctx, tavern);
        if (guests.size() < 2) return;
        VillagerModel a = pick(guests, level);
        guests.remove(a);
        VillagerModel b = pick(guests, level);
        NpcEntity na = a.puppet(), nb = b.puppet();
        if (na == null || nb == null) return;

        PairDance.Spot spot = PairDance.findSpot(level, tavern.getNavMeshBlocks(), tavern.getDoorPos(),
                ctx.npc().blockPosition(), List.of(na.getUUID(), nb.getUUID()),
                new java.util.Random(level.getRandom().nextLong()));
        if (spot == null) {
            state.nextAllowedAt = now + PAUSE_TICKS / 2;
            ctx.brain().diary().log(VillagerDiary.ts(level) + " DANCE no free 2x2 at the tavern");
            return;
        }
        start(level, spot, a, b, MIN_CYCLES + level.getRandom().nextInt(EXTRA_CYCLES), tavernId);
        // No second pair while this one is walking over.
        state.nextAllowedAt = now + PairDance.GATHER_TIMEOUT_TICKS;
    }

    /** Starts the dance and hands both their part. Shared with /vtest dance. */
    public static PairDance start(ServerLevel level, PairDance.Spot spot, VillagerModel a, VillagerModel b,
                                  int cycles, @javax.annotation.Nullable UUID tavernId) {
        PairDance dance = PairDance.begin(level, spot, a.puppet(), b.puppet(), cycles, tavernId);
        String where = spot.tiles().get(0).toShortString() + " facing " + spot.facing().getName();
        for (VillagerModel m : List.of(a, b)) {
            VillagerModel other = m == a ? b : a;
            m.brain().diary().log(String.format("%s DANCE with %s at %s (%d loops)",
                    VillagerDiary.ts(level), other.npcId.toString().substring(0, 8), where, cycles));
            m.brain().forceActivity(new PairDanceActivity(dance));
        }
        return dance;
    }

    private static List<VillagerModel> candidates(Activity.Context ctx, Building tavern) {
        long dayTime = ctx.level().getDayTime();
        List<VillagerModel> out = new ArrayList<>();
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity npc = m.puppet();
            if (npc == null || !npc.isAlive() || npc == ctx.npc()) continue;
            if (!(m.brain().currentActivity() instanceof EveningActivity e)
                    || e.mode() != EveningActivity.Mode.TAVERN || e.venue() != tavern
                    || !e.isHangingAround() || e.settledTicks() < SETTLED_TICKS) continue;
            Profession p = npc.getProfession();
            if (p == Profession.BARD || p == Profession.CHILD || p.isDefender()) continue;
            if (npc.isEating() || npc.isSleeping()) continue;
            if (m.brain().needs().hungerRules().isCritical(m.brain().needs())) continue;
            if (m.brain().eveningTicksUntilLeave(dayTime) < STAYING_TICKS) continue;
            if (PairDance.of(ctx.level(), npc.getUUID()) != null) continue;
            out.add(m);
        }
        return out;
    }

    /** Weighted by sociability. */
    private static VillagerModel pick(List<VillagerModel> from, ServerLevel level) {
        double total = 0;
        for (VillagerModel m : from) total += m.brain().personality().sociability;
        double roll = level.getRandom().nextDouble() * total;
        for (VillagerModel m : from) {
            roll -= m.brain().personality().sociability;
            if (roll <= 0) return m;
        }
        return from.get(from.size() - 1);
    }
}
