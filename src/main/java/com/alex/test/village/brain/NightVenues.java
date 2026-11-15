package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One village's evening, decided once per night: who sleeps early, and who
 * gets into the tavern and the library.
 *
 * <p>Built by the first villager to finish work, from every villager's own
 * plan preview ({@link VillagerBrain#previewPlan}), which is the same roll it
 * would make itself. Tavern-goers are then admitted in rank order only while
 * the tavern never holds more than it seats (chairs, tonight's standing room,
 * the outdoor chairs) at any moment of the evening; see
 * {@link EveningPlanner#admit}. Guests whose sequence brings them late or
 * sends them on early make room for others, so the tavern sees more of the
 * village without being overfull. Those not admitted get another plan up
 * front: the library if it has room, else a visit. The library is filled the
 * same way against its own cap.
 *
 * <p>The rank is a nightly roll plus a bonus for regulars and last night's
 * habit, so the list changes from night to night.
 */
final class NightVenues {

    /** A villager's plan for the night, as the guest lists settled it. */
    record Assigned(EveningPlanner.Plan plan, EveningPlanner.Sequence sequence,
                    @Nullable EveningPlanner.Plan refusedFrom) {}

    /** One village's night. */
    static final class Night {
        final long day;
        final boolean hasTavern, hasLibrary;
        final EveningPlanner.NightType nightType;
        final Set<UUID> early = new HashSet<>();
        final Map<UUID, Assigned> plans = new HashMap<>();

        Night(long day, boolean hasTavern, boolean hasLibrary, EveningPlanner.NightType nightType) {
            this.day = day;
            this.hasTavern = hasTavern;
            this.hasLibrary = hasLibrary;
            this.nightType = nightType;
        }

        boolean hasTavern() { return hasTavern; }
        boolean hasLibrary() { return hasLibrary; }
        EveningPlanner.NightType nightType() { return nightType; }
        boolean isEarly(UUID id) { return early.contains(id); }
        @Nullable Assigned assigned(UUID id) { return plans.get(id); }
    }

    private static final Map<UUID, Night> CACHE = new HashMap<>();
    private static final long SALT_RANK = 0x6E57_0001L;
    /** A regular's rank bonus, out of a 0-999 nightly roll. */
    private static final int REGULAR_BONUS = 300;
    /** Rank per point of habit weight. */
    private static final int HABIT_RANK = 20;

    private NightVenues() {}

    /** The village's night {@code day}, built on first use. */
    static Night forNight(Activity.Context ctx, long day) {
        UUID village = ctx.mgr().getVillageId();
        Night cached = CACHE.get(village);
        if (cached != null && cached.day == day) return cached;
        Night night = build(ctx, day);
        CACHE.put(village, night);
        return night;
    }

    private record Candidate(VillagerModel model, VillagerBrain.PlanPreview preview, int rank, UUID venue,
                             long start, long end) {}

    private static Night build(Activity.Context ctx, long day) {
        List<TavernBuilding> taverns = new ArrayList<>();
        List<LibraryBuilding> libraries = new ArrayList<>();
        for (Building b : ctx.mgr().getBuildings()) {
            if (!b.isValid() || b.getNavMeshBlocks().isEmpty()) continue;
            if (b instanceof TavernBuilding t) taverns.add(t);
            if (b instanceof LibraryBuilding l) libraries.add(l);
        }
        UUID villageId = ctx.mgr().getVillageId();
        EveningPlanner.NightType nightType = EveningPlanner.nightType(villageId, day);
        // Visitors spending the evening here make an ordinary night a tavern night:
        // more of the village comes out to meet them.
        if (nightType == EveningPlanner.NightType.NORMAL && !taverns.isEmpty()
                && com.alex.test.village.visitors.TravelerNight.hasEveningGuests(ctx.mgr(), ctx.level())) {
            nightType = EveningPlanner.NightType.TAVERN_NIGHT;
        }
        Night night = new Night(day, !taverns.isEmpty(), !libraries.isEmpty(), nightType);

        // Who is in tonight: civilians; guards and archers keep their shift.
        List<VillagerModel> roster = new ArrayList<>();
        List<UUID> earlyRoster = new ArrayList<>();
        int villageSize = 0;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity puppet = m.puppet();
            if (puppet == null || !puppet.isAlive()) continue;
            villageSize++;
            Profession p = puppet.getProfession();
            if (p.isDefender()) continue;
            roster.add(m);
            if (p != Profession.BARD) earlyRoster.add(m.npcId);
        }
        for (UUID id : earlyRoster) {
            if (EveningSchedule.isEarlySleeper(id, earlyRoster, villageId, day)) night.early.add(id);
        }

        // Everyone's own roll, as a preview.
        Map<UUID, VillagerBrain.PlanPreview> previews = new HashMap<>();
        for (VillagerModel m : roster) {
            Activity.Context mc = new Activity.Context(m.brain(), m, ctx.mgr(), ctx.level(), m.puppet());
            VillagerBrain.PlanPreview p = m.brain().previewPlan(mc, day, night.hasTavern, night.hasLibrary,
                    night.early.contains(m.npcId), night.nightType);
            previews.put(m.npcId, p);
            night.plans.put(m.npcId, new Assigned(p.plan(), p.sequence(), null));
        }

        // The tavern(s): each guest goes to the one nearest its home.
        List<VillagerModel> refusedTavern = new ArrayList<>();
        for (TavernBuilding tavern : taverns) {
            List<Candidate> candidates = new ArrayList<>();
            for (VillagerModel m : roster) {
                VillagerBrain.PlanPreview p = previews.get(m.npcId);
                if (p.plan() != EveningPlanner.Plan.TAVERN) continue;
                if (nearest(ctx, m, taverns) != tavern) continue;
                candidates.add(candidate(ctx, m, p, tavern, day, true));
            }
            int capacity = EveningActivity.tavernCapacity(ctx, tavern, day);
            List<VillagerModel> refused = admit(candidates, capacity);
            refusedTavern.addAll(refused);
            ctx.brain().diary().log(String.format("%s EVENING tavern guest list: admitted=%d capacity=%d refused=%d",
                    VillagerDiary.ts(ctx.level()), candidates.size() - refused.size(), capacity, refused.size()));
        }
        if (taverns.isEmpty()) {
            for (VillagerModel m : roster) {
                if (previews.get(m.npcId).plan() == EveningPlanner.Plan.TAVERN) refusedTavern.add(m);
            }
        }

        // The library: its own rollers first, then those the tavern turned away.
        List<VillagerModel> refusedLibrary = new ArrayList<>();
        for (LibraryBuilding library : libraries) {
            List<Candidate> own = new ArrayList<>(), extra = new ArrayList<>();
            for (VillagerModel m : roster) {
                VillagerBrain.PlanPreview p = previews.get(m.npcId);
                if (nearest(ctx, m, libraries) != library) continue;
                if (p.plan() == EveningPlanner.Plan.LIBRARY) own.add(candidate(ctx, m, p, library, day, false));
                else if (refusedTavern.contains(m)) extra.add(candidate(ctx, m, p, library, day, false));
            }
            own.sort(Comparator.comparingInt(Candidate::rank).reversed());
            extra.sort(Comparator.comparingInt(Candidate::rank).reversed());
            List<Candidate> all = new ArrayList<>(own);
            all.addAll(extra);
            LibraryPlan.Limits limits = new LibraryPlan.Limits(villageSize,
                    library.getSpace().fits(), LibraryActivity.capacity(ctx, library, day));
            int capacity = LibraryPlan.cap(limits);
            List<VillagerModel> refused = admitInOrder(all, capacity);
            for (Candidate c : extra) {
                if (!refused.contains(c.model())) {
                    UUID id = c.model().npcId;
                    night.plans.put(id, new Assigned(EveningPlanner.Plan.LIBRARY,
                            c.model().brain().sequenceFor(EveningPlanner.Plan.LIBRARY, day), EveningPlanner.Plan.TAVERN));
                    refusedTavern.remove(c.model());
                }
            }
            for (Candidate c : own) if (refused.contains(c.model())) refusedLibrary.add(c.model());
            ctx.brain().diary().log(String.format("%s EVENING library guest list: admitted=%d capacity=%d refused=%d",
                    VillagerDiary.ts(ctx.level()), all.size() - refused.size(), capacity, refused.size()));
        }

        // Turned away with nowhere else to sit: one visit instead (home if no host).
        for (VillagerModel m : refusedTavern) {
            night.plans.put(m.npcId, new Assigned(EveningPlanner.Plan.VISIT, EveningPlanner.Sequence.SINGLE,
                    EveningPlanner.Plan.TAVERN));
        }
        for (VillagerModel m : refusedLibrary) {
            night.plans.put(m.npcId, new Assigned(EveningPlanner.Plan.VISIT, EveningPlanner.Sequence.SINGLE,
                    EveningPlanner.Plan.LIBRARY));
        }
        return night;
    }

    /** Admits in rank order; returns the villagers turned away. */
    private static List<VillagerModel> admit(List<Candidate> candidates, int capacity) {
        candidates.sort(Comparator.comparingInt(Candidate::rank).reversed());
        return admitInOrder(candidates, capacity);
    }

    private static List<VillagerModel> admitInOrder(List<Candidate> ordered, int capacity) {
        long[] starts = new long[ordered.size()], ends = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            starts[i] = ordered.get(i).start();
            ends[i] = ordered.get(i).end();
        }
        boolean[] in = EveningPlanner.admit(starts, ends, capacity);
        List<VillagerModel> refused = new ArrayList<>();
        for (int i = 0; i < in.length; i++) if (!in[i]) refused.add(ordered.get(i).model());
        return refused;
    }

    /**
     * A guest's estimated time at the venue, as times of day: when it gets
     * there after its head-out (and a visit first, for some tavern-goers),
     * and when it leaves (its stay, or its leaving time at the night's last
     * venue, whichever is later).
     */
    private static Candidate candidate(Activity.Context ctx, VillagerModel m, VillagerBrain.PlanPreview p,
                                       Building venue, long day, boolean tavern) {
        EveningSchedule.Night n = m.brain().eveningSchedule().night(day);
        BlockPos from = homeDoor(ctx, m);
        long walk = VisitRules.walkTicks(Math.sqrt(from.distSqr(venue.getDoorPos())));
        long setOff = Math.max(n.headOut(), n.endWork() + 100L);
        EveningPlanner.Sequence seq = tavern ? p.sequence() : EveningPlanner.Sequence.SINGLE;
        long start = setOff + walk;
        if (seq == EveningPlanner.Sequence.VISIT_THEN_TAVERN) start += 150 + n.visit1() + walk;
        int stay = tavern ? n.tavernStay() : n.libraryStay();
        boolean last = seq != EveningPlanner.Sequence.TAVERN_THEN_VISIT && seq != EveningPlanner.Sequence.TAVERN_THEN_CHORES;
        long end = last ? Math.max(start + stay, n.leave()) : start + stay;
        int rank = EveningSchedule.sharedRoll(m.npcId, day, SALT_RANK, 1000)
                + (p.favourite() == EveningPlanner.Favourite.TAVERN_REGULAR && tavern ? REGULAR_BONUS : 0)
                + (p.favourite() == EveningPlanner.Favourite.BOOKWORM && !tavern ? REGULAR_BONUS : 0)
                + HABIT_RANK * m.brain().habitFor(day, tavern ? EveningPlanner.Plan.TAVERN : EveningPlanner.Plan.LIBRARY);
        return new Candidate(m, p, rank, venue.getItemFrameId(), start, end);
    }

    private static BlockPos homeDoor(Activity.Context ctx, VillagerModel m) {
        Building home = ctx.mgr().getAssignedBedBuilding(m.npcId);
        if (home != null && home.isValid()) return home.getDoorPos();
        return m.puppet() != null ? m.puppet().blockPosition() : BlockPos.ZERO;
    }

    @Nullable
    private static <T extends Building> T nearest(Activity.Context ctx, VillagerModel m, List<T> venues) {
        BlockPos from = homeDoor(ctx, m);
        T best = null;
        double bestDist = Double.MAX_VALUE;
        for (T v : venues) {
            double d = v.getDoorPos().distSqr(from);
            if (d < bestDist) { bestDist = d; best = v; }
        }
        return best;
    }
}
