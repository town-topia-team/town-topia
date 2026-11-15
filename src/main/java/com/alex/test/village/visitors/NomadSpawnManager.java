package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.visitor.NomadEntity;
import com.alex.test.village.VillageManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Founding, scripted and morning arrivals for recruitable nomads.
 *
 * <p>Every morning with no nomads around rolls once (see {@link NomadSpawnRules})
 * for whether a group comes and how big it is. The group then shows up at a
 * random time later that morning, all members from one spot on the edge,
 * a second or two apart. Day 1 (founding), day 3 and day 4 are scripted and
 * never roll; days 3 and 4 are the 2nd and 3rd morning the village sees after
 * its founding. Mornings are village days ({@code VillageDays}), so skipping
 * nights with {@code /time set day} brings them like sleeping does.
 */
public final class NomadSpawnManager {
    public static final int WELCOME_RETRY_TICKS = 40;
    public static final int WELCOME_MAX_ATTEMPTS = 40;
    /** A member after the first gets this many retries before the group arrives smaller. */
    private static final int MEMBER_MAX_ATTEMPTS = 5;
    private static final int FOUNDING_ARRIVAL_DELAY_TICKS = 200; // 10 seconds
    /** Members of one group appear 1 to 2 seconds apart. */
    private static final int MEMBER_SPACING_MIN_TICKS = 20;
    private static final int MEMBER_SPACING_JITTER_TICKS = 21;
    /** How far from the first member the rest of the group may appear. */
    private static final int GROUP_RADIUS = 3;
    private static final double SEARCH_RADIUS = 512.0;
    private static final long NO_DAY = Long.MIN_VALUE;
    /**
     * A nomad this long past its leave time that is not walking any more (out
     * of range) has its record ended: its own return timeout plus a minute.
     */
    private static final long STALE_RECORD_TICKS = TravelerLifecycleRules.RETURN_TIMEOUT_TICKS + 1_200L;
    private static final long STALE_CHECK_INTERVAL_TICKS = 100L;

    /**
     * Arrival lines by group size (index 0 = one nomad). Randomly picked; each player's own game translates
     * them. Only ever passed into a new message, never changed.
     */
    private static final Component[][] ARRIVAL_MESSAGES = {
            {
                    Tr.c("chat.dynamicvillages.nomad.arrival.one_1", "A lone nomad has spotted your village and is making their way over."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.one_2", "A weary traveler is approaching the village, hoping for a place to stay."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.one_3", "Footsteps on the horizon. A single nomad is heading this way."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.one_4", "A lone wanderer has caught sight of your village and is coming in."),
            },
            {
                    Tr.c("chat.dynamicvillages.nomad.arrival.two_1", "A pair of nomads is walking toward the village side by side."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.two_2", "Two travelers have caught sight of your village and are coming in together."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.two_3", "A couple of weary wanderers are making their way into the village."),
            },
            {
                    Tr.c("chat.dynamicvillages.nomad.arrival.three_1", "A small band of three nomads is approaching the village."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.three_2", "Three wanderers are making their way toward your village together."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.three_3", "A little traveling party of three has spotted your village."),
            },
            {
                    Tr.c("chat.dynamicvillages.nomad.arrival.four_1", "A group of four nomads is heading into the village."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.four_2", "Four travelers have been spotted on the road to your village."),
                    Tr.c("chat.dynamicvillages.nomad.arrival.four_3", "A band of four wanderers is coming your way, looking for a new home."),
            },
            {
                    Tr.c("chat.dynamicvillages.nomad.arrival.five_1", "A whole caravan of five nomads is arriving at the village!"),
                    Tr.c("chat.dynamicvillages.nomad.arrival.five_2", "Five nomads are traveling together toward your village. Quite the crowd!"),
                    Tr.c("chat.dynamicvillages.nomad.arrival.five_3", "A big traveling party of five has spotted your village and is heading in."),
            },
    };

    /** Pre-rework save format for queued founding arrivals; only read, never written. */
    public record SavedArrival(long atGameTime, int attempts) {}

    /** A group that has been rolled (or scripted) and has not finished arriving. */
    private static final class PendingGroup {
        /** First member's arrival time; after that, the next member's. */
        long atGameTime;
        /** Thunder still holding the group back at this time calls it off; -1 never. */
        long cancelAt = -1L;
        int size;
        int spawned;
        int attempts;
        boolean founding;
        boolean scripted;
        /** A full size group: the next morning is a quiet one. */
        boolean bigGroup;
        /** Rolled on a quiet morning, so calling it off counts as that quiet morning failing. */
        boolean quietRoll;
        /** The scripted day 4 group: it stays the night whenever it comes. */
        boolean stayNight;
        /** How long the group stays ({@link TravelerLifecycleRules.StayPlan} ordinal), picked as it arrives; -1 not yet. */
        int plan = -1;
        @Nullable BlockPos anchor;

        boolean started() { return spawned > 0; }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("at", atGameTime);
            tag.putLong("cancelAt", cancelAt);
            tag.putInt("size", size);
            tag.putInt("spawned", spawned);
            tag.putInt("attempts", attempts);
            tag.putBoolean("founding", founding);
            tag.putBoolean("scripted", scripted);
            tag.putBoolean("big", bigGroup);
            tag.putBoolean("quiet", quietRoll);
            tag.putBoolean("stayNight", stayNight);
            tag.putInt("plan", plan);
            if (anchor != null) tag.putLong("anchor", anchor.asLong());
            return tag;
        }

        static PendingGroup load(CompoundTag tag) {
            PendingGroup group = new PendingGroup();
            group.atGameTime = tag.getLong("at");
            group.cancelAt = tag.contains("cancelAt") ? tag.getLong("cancelAt") : -1L;
            group.size = Math.max(1, tag.getInt("size"));
            group.spawned = Math.max(0, tag.getInt("spawned"));
            group.attempts = Math.max(0, tag.getInt("attempts"));
            group.founding = tag.getBoolean("founding");
            group.scripted = tag.getBoolean("scripted");
            group.bigGroup = tag.getBoolean("big");
            group.quietRoll = tag.getBoolean("quiet");
            group.stayNight = tag.getBoolean("stayNight");
            group.plan = tag.contains("plan") ? tag.getInt("plan") : -1;
            group.anchor = tag.contains("anchor") ? BlockPos.of(tag.getLong("anchor")) : null;
            return group;
        }
    }

    private final VillageManager village;
    /** Oldest first; only the head group arrives at a time. */
    private final List<PendingGroup> pending = new ArrayList<>();
    /** Authoritative active group, including members whose chunks are unloaded. */
    private final Map<UUID, Long> activeDepartures = new LinkedHashMap<>();
    private boolean arrivalsEnabled = true;
    /** Day index of the founding day (day 1); NO_DAY for villages from older saves. */
    private long foundingDay = NO_DAY;
    /** Day index of the last morning handled, so each morning is looked at once. */
    private long lastMorningDay = NO_DAY;
    /**
     * Mornings handled since the founding day, which sets the scripted days
     * (the 2nd is day 3); {@link #NO_MORNINGS} for villages too old to tell.
     * Counted, not worked out from day numbers, so a morning the village
     * missed (nobody nearby) or a jump of several days moves them later
     * instead of losing them.
     */
    private int morningsSinceFounding = NO_MORNINGS;
    private static final int NO_MORNINGS = -1;
    /** Counting stops here; the scripted days are long past. */
    private static final int MORNINGS_COUNT_CAP = 100;
    private int failedMornings;
    private boolean quietMorning;
    private boolean quietBonus;
    /** Game time of the last night skip; not saved, it only matters for a few ticks. */
    private long wokeAtGameTime = -1L;
    /** Day clock and game time last seen, to notice the clock jumping ahead; not saved. */
    private long seenDayTime = -1L;
    private long seenGameTime = -1L;
    private long nextStaleCheckAt;
    /** Nomads upset by a broken camp fire: walking off, and refusing to be recruited. */
    private final Set<UUID> offended = new HashSet<>();
    @Nullable private BlockPos lastSpawnPosition;

    public NomadSpawnManager(VillageManager village) { this.village = village; }

    public void scheduleFoundingNomads(ServerLevel level) {
        pending.clear();
        foundingDay = TravelerLifecycleRules.dayIndex(level.getDayTime());
        // The founding day is scripted: no roll on it.
        lastMorningDay = foundingDay;
        morningsSinceFounding = 0;
        failedMornings = 0;
        quietMorning = false;
        quietBonus = false;
        PendingGroup group = new PendingGroup();
        group.atGameTime = level.getGameTime() + FOUNDING_ARRIVAL_DELAY_TICKS;
        group.size = NomadSpawnRules.FOUNDING_GROUP_SIZE;
        group.founding = true;
        group.scripted = true;
        pending.add(group);
        village.markPersistentDataDirty();
    }

    public void setArrivalsEnabled(boolean enabled) {
        arrivalsEnabled = enabled;
        if (!enabled) pending.clear();
        village.markPersistentDataDirty();
    }

    /** Nomads rolled or scripted but not yet spawned. */
    public int pendingWelcomeCount() {
        int count = 0;
        for (PendingGroup group : pending) count += group.size - group.spawned;
        return count;
    }

    public long nextPendingArrivalAt() { return pending.isEmpty() ? -1L : pending.get(0).atGameTime; }
    public int failedMornings() { return failedMornings; }
    public long foundingDay() { return foundingDay == NO_DAY ? -1L : foundingDay; }
    /** Village day of the last morning looked at; -1 for none yet. */
    public long lastMorningDay() { return lastMorningDay == NO_DAY ? -1L : lastMorningDay; }
    /** Mornings handled since the founding day; -1 for villages too old to tell. */
    public int morningsSinceFounding() { return morningsSinceFounding; }
    public boolean quietMorningNext() { return quietMorning; }
    public int populationForNextRoll() { return village.getVillagers().size(); }
    public double nextArrivalChance() {
        return NomadSpawnRules.arrivalChance(populationForNextRoll(), failedMornings,
                quietMorning, quietBonus);
    }
    public Map<UUID, Long> savedActiveNomads() { return Map.copyOf(activeDepartures); }

    public CompoundTag saveSchedule() {
        CompoundTag tag = new CompoundTag();
        if (foundingDay != NO_DAY) tag.putLong("foundingDay", foundingDay);
        if (lastMorningDay != NO_DAY) tag.putLong("lastMorningDay", lastMorningDay);
        tag.putInt("morningsSinceFounding", morningsSinceFounding);
        tag.putInt("failedMornings", failedMornings);
        tag.putBoolean("quietMorning", quietMorning);
        tag.putBoolean("quietBonus", quietBonus);
        ListTag groups = new ListTag();
        for (PendingGroup group : pending) groups.add(group.save());
        tag.put("pending", groups);
        ListTag upset = new ListTag();
        for (UUID id : offended) upset.add(net.minecraft.nbt.NbtUtils.createUUID(id));
        tag.put("offended", upset);
        // The village's camp rides along here so the village record keeps its format.
        tag.put("camp", village.travelerCamp().save());
        return tag;
    }

    /**
     * @param schedule      {@link #saveSchedule()} output; empty for older saves
     * @param legacyWelcome queued founding arrivals from before the rework
     */
    public void restore(@Nullable CompoundTag schedule, @Nullable List<SavedArrival> legacyWelcome,
                        @Nullable Map<UUID, Long> active) {
        pending.clear();
        CompoundTag tag = schedule != null ? schedule : new CompoundTag();
        foundingDay = tag.contains("foundingDay") ? tag.getLong("foundingDay") : NO_DAY;
        lastMorningDay = tag.contains("lastMorningDay") ? tag.getLong("lastMorningDay") : NO_DAY;
        if (tag.contains("morningsSinceFounding")) {
            morningsSinceFounding = Math.max(NO_MORNINGS, tag.getInt("morningsSinceFounding"));
        } else if (foundingDay == NO_DAY || lastMorningDay == NO_DAY) {
            morningsSinceFounding = NO_MORNINGS;
        } else {
            // Saved before mornings were counted: the day numbers tell. A last
            // morning before the founding day means the clock was set back after
            // mornings were handled; how many is unknown, so no scripted days
            // (rather than a second round of them).
            long handled = lastMorningDay - foundingDay;
            morningsSinceFounding = handled < 0L ? NO_MORNINGS
                    : (int) Math.min(MORNINGS_COUNT_CAP, handled);
        }
        failedMornings = Math.max(0, tag.getInt("failedMornings"));
        quietMorning = tag.getBoolean("quietMorning");
        quietBonus = tag.getBoolean("quietBonus");
        ListTag groups = tag.getList("pending", Tag.TAG_COMPOUND);
        for (int i = 0; i < groups.size(); i++) pending.add(PendingGroup.load(groups.getCompound(i)));
        offended.clear();
        for (Tag id : tag.getList("offended", Tag.TAG_INT_ARRAY)) {
            offended.add(net.minecraft.nbt.NbtUtils.loadUUID(id));
        }
        village.travelerCamp().load(tag.getCompound("camp"));
        if (pending.isEmpty() && legacyWelcome != null && !legacyWelcome.isEmpty()) {
            // Old saves queued founding nomads one by one; they now arrive as one group.
            PendingGroup group = new PendingGroup();
            group.atGameTime = Long.MAX_VALUE;
            for (SavedArrival arrival : legacyWelcome) {
                group.atGameTime = Math.min(group.atGameTime, arrival.atGameTime());
            }
            group.size = legacyWelcome.size();
            group.founding = true;
            group.scripted = true;
            pending.add(group);
        }

        activeDepartures.clear();
        if (active != null) {
            for (Map.Entry<UUID, Long> entry : active.entrySet()) {
                long departure = entry.getValue();
                if (departure < 0L && village.getLevel() != null) {
                    departure = allocateDepartureSchedule(village.getLevel()).gameTime();
                    DynamicVillages.LOGGER.warn("[VillageManager {}] Repaired invalid departure "
                            + "for active nomad {}.", shortId(), entry.getKey());
                    village.markPersistentDataDirty();
                }
                if (departure >= 0L) activeDepartures.put(entry.getKey(), departure);
            }
        }
    }

    @Nullable public Long departureFor(UUID nomadId) { return activeDepartures.get(nomadId); }

    /** One-time migration for pre-lifecycle nomad entities that had no manager record. */
    public long registerLegacyNomad(ServerLevel level, UUID nomadId, long departureAt) {
        if (departureAt < 0L) departureAt = allocateDepartureSchedule(level).gameTime();
        if (activeDepartures.putIfAbsent(nomadId, departureAt) == null) {
            village.markPersistentDataDirty();
            DynamicVillages.LOGGER.info("[VillageManager {}] Adopted legacy nomad {} into active group.",
                    shortId(), nomadId);
        }
        return activeDepartures.get(nomadId);
    }

    public void finishNomad(UUID nomadId, long now, String reason) {
        offended.remove(nomadId);
        if (activeDepartures.remove(nomadId) == null) return;
        DynamicVillages.LOGGER.info("[VillageManager {}] Nomad {} ended lifecycle: {} ({} remain).",
                shortId(), nomadId, reason, activeDepartures.size());
        village.markPersistentDataDirty();
    }

    /**
     * A night skip (slept through, or the clock turned back to morning): the
     * next morning's arrival (and any that was still waiting) lands anywhere
     * from waking up to four minutes later, and nomads that would have left
     * during the skipped night set off now, walking out, instead of staying
     * into the new morning.
     */
    public void onPlayerSleepFinished(ServerLevel level, long skippedTicks, long wakeDayTime) {
        if (skippedTicks <= 0L) return;
        long now = level.getGameTime();
        wokeAtGameTime = now;
        // Handled here: the jump must not be counted again (followClockJump).
        seenDayTime = wakeDayTime;
        seenGameTime = now;
        for (PendingGroup group : pending) {
            if (group.started() || group.founding) continue;
            group.atGameTime = now + level.random.nextInt(
                    TravelerLifecycleRules.WAKE_ARRIVAL_SPREAD_TICKS + 1);
            group.cancelAt = TravelerLifecycleRules.arrivalCancelAt(now, wakeDayTime);
        }
        // Each entity takes the new time from this record (reconcileLifecycle).
        // Guests who stayed the night keep their morning leave time, so they
        // neither hold back this morning's roll nor stay into the evening.
        activeDepartures.replaceAll((id, departure) ->
                TravelerLifecycleRules.afterSkippedNight(departure, now, skippedTicks));
        village.markPersistentDataDirty();
    }

    /**
     * Some nomad here is not on its way out: its leave time is further off than
     * the morning leave spread, so a guest leaving after its night here does not
     * hold back the morning's roll.
     */
    private boolean hasNomadsStaying(long now) {
        for (long departure : activeDepartures.values()) {
            if (departure > now + TravelerLifecycleRules.LEAVING_SOON_TICKS) return true;
        }
        return false;
    }

    /** Sends this nomad off now (its fire was broken, or it had nowhere to camp). */
    public void leaveNow(UUID nomadId, long now) {
        if (activeDepartures.containsKey(nomadId)) {
            activeDepartures.put(nomadId, now);
            village.markPersistentDataDirty();
        }
    }

    /** Upset with the village: it walks off and cannot be recruited. */
    public void markOffended(UUID nomadId) {
        if (offended.add(nomadId)) village.markPersistentDataDirty();
    }

    public boolean isOffended(UUID nomadId) { return offended.contains(nomadId); }

    /**
     * A storm at dusk: nomads who would leave this evening stay the night and
     * leave the next morning instead. Returns how many.
     */
    public int stayOvernight(ServerLevel level, long now, long dayTime) {
        long morning = TravelerLifecycleRules.morningAfter(now, dayTime);
        int kept = 0;
        for (Map.Entry<UUID, Long> entry : activeDepartures.entrySet()) {
            long departure = entry.getValue();
            if (departure > now && departure < morning) {
                entry.setValue(morning + level.random.nextInt(TravelerLifecycleRules.MORNING_LEAVE_SPREAD + 1));
                kept++;
            }
        }
        if (kept > 0) village.markPersistentDataDirty();
        return kept;
    }

    /**
     * Ends the records of nomads that should long be gone but are not walking
     * any more: their chunks are unloaded, or loaded but out of simulation
     * range. A nomad that is still ticking leaves by itself (return timeout);
     * an unloaded one removes itself the moment its chunk loads, finding its
     * record gone (NomadEntity.reconcileLifecycle).
     */
    private void checkStaleNomads(ServerLevel level, long now) {
        if (activeDepartures.isEmpty() || now < nextStaleCheckAt) return;
        nextStaleCheckAt = now + STALE_CHECK_INTERVAL_TICKS;
        List<UUID> stale = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : activeDepartures.entrySet()) {
            if (now - entry.getValue() >= STALE_RECORD_TICKS) stale.add(entry.getKey());
        }
        for (UUID nomadId : stale) {
            Entity entity = level.getEntity(nomadId);
            if (entity == null) {
                finishNomad(nomadId, now, "left while out of range");
            } else if (entity instanceof NomadEntity nomad && entity.isAlive()
                    && !level.isPositionEntityTicking(entity.blockPosition())
                    && !level.hasNearbyAlivePlayer(entity.getX(), entity.getY(), entity.getZ(),
                            TravelerLifecycleRules.SAFE_REMOVAL_PLAYER_RADIUS)) {
                nomad.finishVisit(level, "left while out of range");
            }
        }
    }

    /**
     * The clock jumped ahead with no night skip reported ({@code /time add}):
     * leave times come that much sooner, as after sleeping, so a guest who
     * stayed the night still leaves in the morning on the village's record.
     */
    private void followClockJump(long now, long dayTime) {
        long ahead = TravelerLifecycleRules.clockJumpAhead(seenDayTime, dayTime, seenGameTime, now);
        seenDayTime = dayTime;
        seenGameTime = now;
        if (ahead <= 0L) return;
        boolean moved = false;
        for (Map.Entry<UUID, Long> entry : activeDepartures.entrySet()) {
            long shifted = TravelerLifecycleRules.afterSkippedNight(entry.getValue(), now, ahead);
            if (shifted != entry.getValue()) {
                entry.setValue(shifted);
                moved = true;
            }
        }
        if (!moved) return;
        village.markPersistentDataDirty();
        if (ahead >= 100L) {
            DynamicVillages.LOGGER.info("[VillageManager {}] Clock jumped {} ticks ahead: nomad leave times "
                    + "moved up to match.", shortId(), ahead);
        }
    }

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()) return;
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        followClockJump(now, dayTime);
        if (!arrivalsEnabled) return;
        checkStaleNomads(level, now);
        tickPending(level, now, dayTime);

        // Village days only move forward (VillageDays). A saved morning ahead of
        // today comes from before that, when /time set took the world's own
        // day number back: today counts as handled, the next morning rolls.
        long today = TravelerLifecycleRules.dayIndex(dayTime);
        if (lastMorningDay != NO_DAY && lastMorningDay > today) {
            lastMorningDay = today;
            village.markPersistentDataDirty();
        }
        if (!TravelerLifecycleRules.isMorningWindow(now, dayTime)) return;
        if (lastMorningDay != NO_DAY && today <= lastMorningDay) return;
        // A camp's fire was broken last night: nobody comes today. The morning
        // is skipped, not counted, so the scripted days come a day later.
        if (village.travelerCamp().isSnubbed(today)) return;
        // Persist before rolling so reloads and time changes cannot reroll it.
        lastMorningDay = today;
        if (morningsSinceFounding >= 0 && morningsSinceFounding < MORNINGS_COUNT_CAP) {
            morningsSinceFounding++;
        }
        village.markPersistentDataDirty();

        boolean justWoke = wokeAtGameTime >= 0L
                && now - wokeAtGameTime <= TravelerLifecycleRules.RECENT_WAKE_TICKS;
        long dayNumber = morningsSinceFounding < 0 ? -1L : morningsSinceFounding + 1L;
        if (dayNumber == NomadSpawnRules.SCRIPTED_SINGLE_DAY) {
            scheduleGroup(level, 1, true, false, false, justWoke);
            return;
        }
        if (dayNumber == NomadSpawnRules.SCRIPTED_GROUP_DAY) {
            // Day 4: whoever comes, this group and the merchant, stays the night.
            village.travelerCamp().markScriptedStayDay(today);
            scheduleGroup(level, NomadSpawnRules.SCRIPTED_GROUP_SIZE, true, true, false, justWoke)
                    .stayNight = true;
            return;
        }
        // Mornings with nomads still staying (or on their way) neither roll nor
        // count. Nomads already walking out do not hold the morning back.
        if (!pending.isEmpty() || hasNomadsStaying(now)) return;
        rollMorning(level, justWoke);
    }

    private void rollMorning(ServerLevel level, boolean justWoke) {
        int population = village.getVillagers().size();
        boolean quietRoll = quietMorning;
        quietMorning = false;
        village.markPersistentDataDirty();
        if (level.isThundering()) {
            failedMorning(quietRoll);
            DynamicVillages.LOGGER.debug("[VillageManager {}] No nomads this morning: thunder "
                    + "({} failed mornings).", shortId(), failedMornings);
            return;
        }
        double chance = NomadSpawnRules.arrivalChance(population, failedMornings, quietRoll,
                quietBonus);
        // A village famous for beating raids draws more people (RaidManager fame).
        chance = Math.min(1.0, chance + village.raids().fameNomadBonus());
        if (level.random.nextDouble() >= chance) {
            failedMorning(quietRoll);
            DynamicVillages.LOGGER.debug("[VillageManager {}] No nomads this morning "
                    + "(chance {}, {} failed mornings).", shortId(), chance, failedMornings);
            return;
        }
        int max = NomadSpawnRules.maxGroup(population) + village.raids().fameGroupBonus();
        int size = NomadSpawnRules.rollGroupSize(
                NomadSpawnRules.groupChance(population, failedMornings), max,
                level.random::nextDouble);
        scheduleGroup(level, size, false, size >= max, quietRoll, justWoke);
    }

    private void failedMorning(boolean quietRoll) {
        if (quietRoll) quietBonus = true;
        else failedMornings++;
        village.markPersistentDataDirty();
    }

    private PendingGroup scheduleGroup(ServerLevel level, int size, boolean scripted, boolean bigGroup,
                                       boolean quietRoll, boolean justWoke) {
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        PendingGroup group = new PendingGroup();
        group.atGameTime = now + TravelerLifecycleRules.arrivalDelay(dayTime, justWoke,
                level.random::nextInt);
        group.cancelAt = TravelerLifecycleRules.arrivalCancelAt(now, dayTime);
        group.size = size;
        group.scripted = scripted;
        group.bigGroup = bigGroup;
        group.quietRoll = quietRoll;
        pending.add(group);
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] {} group of {} nomad(s) will arrive at {} "
                        + "(in {} ticks).", shortId(), scripted ? "Scripted" : "Rolled", size,
                group.atGameTime, group.atGameTime - now);
        return group;
    }

    /**
     * The head group will not come today. Scripted arrivals are only moved, to
     * tomorrow morning, never lost; a rolled group counts as a failed morning.
     */
    private void callOffForToday(ServerLevel level, PendingGroup group, long now, long dayTime,
                                 String why) {
        if (group.scripted) {
            long sunrise = TravelerLifecycleRules.nextSunrise(now, dayTime);
            group.atGameTime = sunrise + level.random.nextInt(
                    TravelerLifecycleRules.ARRIVAL_LATEST_CLOCK + 1);
            group.cancelAt = sunrise + TravelerLifecycleRules.ARRIVAL_CANCEL_CLOCK;
        } else {
            pending.remove(0);
            failedMorning(group.quietRoll);
        }
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] {} called off this morning's nomads{}.",
                shortId(), why, group.scripted ? " (moved to tomorrow)" : "");
    }

    private void tickPending(ServerLevel level, long now, long dayTime) {
        if (pending.isEmpty()) return;
        PendingGroup group = pending.get(0);
        if (now < group.atGameTime) return;

        // Past 2 PM nobody turns up (a /time set, a long hold-up, a world
        // closed and reopened). A group already arriving finishes; the
        // founding group comes at any hour.
        if (!group.started() && !group.founding
                && TravelerLifecycleRules.isTooLateToArrive(dayTime)) {
            callOffForToday(level, group, now, dayTime, "Too late in the day");
            return;
        }
        if (!group.started() && !group.founding && level.isThundering()) {
            if (group.cancelAt < 0L || now < group.cancelAt) return; // wait it out
            callOffForToday(level, group, now, dayTime, "Thunder");
            return;
        }
        if (!group.started() && !group.founding
                && village.travelerCamp().isSnubbed(TravelerLifecycleRules.dayIndex(dayTime))) {
            callOffForToday(level, group, now, dayTime, "Upset travelers");
            return;
        }
        if (!group.started() && group.plan < 0) {
            // How long the group stays is picked as it arrives: later arrivals stay longer.
            group.plan = (group.founding ? TravelerLifecycleRules.StayPlan.DUSK
                    : group.stayNight ? TravelerLifecycleRules.StayPlan.NIGHT
                    : village.travelerCamp().planFor(level, group.size)).ordinal();
        }

        NomadEntity nomad = group.started() ? spawnMember(level, group) : spawnLead(level, group);
        if (nomad != null) {
            group.spawned++;
            group.attempts = 0;
            if (group.spawned == 1) onGroupArrived(level, group);
            if (group.spawned >= group.size) pending.remove(0);
            else group.atGameTime = now + MEMBER_SPACING_MIN_TICKS
                    + level.random.nextInt(MEMBER_SPACING_JITTER_TICKS);
            village.markPersistentDataDirty();
            return;
        }

        group.attempts++;
        int maxAttempts = group.started() ? MEMBER_MAX_ATTEMPTS : WELCOME_MAX_ATTEMPTS;
        if (group.attempts >= maxAttempts) {
            pending.remove(0);
            // A group that never showed up is a failed morning; one that arrives
            // smaller already counted as an arrival.
            if (!group.started() && !group.scripted) failedMorning(group.quietRoll);
            DynamicVillages.LOGGER.warn("[VillageManager {}] Nomad group stopped at {} of {} "
                            + "after {} safe-perimeter attempts.", shortId(), group.spawned,
                    group.size, group.attempts);
        } else {
            group.atGameTime = now + WELCOME_RETRY_TICKS;
        }
        village.markPersistentDataDirty();
    }

    private void onGroupArrived(ServerLevel level, PendingGroup group) {
        if (!group.founding) {
            failedMornings = 0;
            quietBonus = false;
            quietMorning = group.bigGroup;
        }
        announceArrival(level, group.size, planOf(group));
    }

    @Nullable
    private NomadEntity spawnLead(ServerLevel level, PendingGroup group) {
        if (group.founding) {
            NomadEntity nomad = spawnFoundingLead(level);
            if (nomad != null) {
                group.anchor = nomad.blockPosition();
                return nomad;
            }
        }
        Set<BlockPos> excluded = new HashSet<>();
        if (lastSpawnPosition != null) excluded.add(lastSpawnPosition);
        for (BlockPos site : NomadArrivalSites.candidates(level, village, level.random, excluded)) {
            NomadEntity nomad = spawnAt(level, site, planOf(group));
            if (nomad != null) {
                group.anchor = site;
                return nomad;
            }
        }
        return null;
    }

    /** Next to the group's first member; failing that, a fresh edge spot the rest follow from. */
    @Nullable
    private NomadEntity spawnMember(ServerLevel level, PendingGroup group) {
        if (group.anchor != null) {
            BlockPos near = siteNearAnchor(level, group.anchor);
            if (near != null) {
                NomadEntity nomad = spawnAt(level, near, planOf(group));
                if (nomad != null) return nomad;
            }
        }
        for (BlockPos site : NomadArrivalSites.candidates(level, village, level.random, Set.of())) {
            NomadEntity nomad = spawnAt(level, site, planOf(group));
            if (nomad != null) {
                group.anchor = site;
                return nomad;
            }
        }
        return null;
    }

    @Nullable
    private BlockPos siteNearAnchor(ServerLevel level, BlockPos anchor) {
        var graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return null;
        BlockPos gate = village.getVisitorGatheringSpot();
        int span = GROUP_RADIUS * 2 + 1;
        for (int attempt = 0; attempt < 16; attempt++) {
            BlockPos probe = anchor.offset(level.random.nextInt(span) - GROUP_RADIUS, 0,
                    level.random.nextInt(span) - GROUP_RADIUS);
            var node = graph.nearestRoutable(probe, 1, 3);
            if (!NomadArrivalSites.usable(level, village, graph, node, gate)) continue;
            BlockPos feet = node.feetPos();
            if (Math.abs(feet.getX() - anchor.getX()) > GROUP_RADIUS + 1
                    || Math.abs(feet.getZ() - anchor.getZ()) > GROUP_RADIUS + 1) continue;
            return feet;
        }
        return null;
    }

    /**
     * Spawns one nomad at {@code site} walking to the gathering spot, or null.
     */
    @Nullable
    private NomadEntity spawnAt(ServerLevel level, BlockPos site, TravelerLifecycleRules.StayPlan plan) {
        NomadEntity nomad = DynamicVillages.NOMAD.get().create(level);
        if (nomad == null) return null;
        TravelerLifecycleRules.Departure schedule = TravelerLifecycleRules.departureFor(plan,
                level.getGameTime(), level.getDayTime(), activeDepartures.values(), level.random::nextInt);
        long departure = schedule.gameTime();
        nomad.setDepartureSchedule(departure, schedule.dayClock(), level.getDayTime());
        nomad.setSpawnOrigin(level, site);
        nomad.setVillageId(village.getVillageId());
        nomad.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0, 0);
        nomad.setOnGround(true);
        if (!level.noCollision(nomad) || !level.addFreshEntity(nomad)) return null;
        // Every site already has a graph route to the gate (NomadArrivalSites.usable),
        // and NomadLogic walks it on the graph from the first tick. No vanilla path
        // check here: from sites near the simulation edge it ran a 4096-node search
        // that usually failed, which froze the server on every retry.
        activeDepartures.put(nomad.getUUID(), departure);
        lastSpawnPosition = site;
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] Nomad {} arriving at {}; "
                + "departure scheduled for {}.", shortId(), nomad.getUUID(), site, departure);
        return nomad;
    }

    /** Founding visitors begin roughly 50 blocks from the Town Hall, then use
     * the nav graph to the same gathering point as later nomads. */
    @Nullable
    private NomadEntity spawnFoundingLead(ServerLevel level) {
        BlockPos hall = village.getBellPos();
        BlockPos gate = village.getVisitorGatheringSpot();
        var graph = village.getGraph();
        if (graph == null || graph.isEmpty()) return null;
        for (int attempt = 0; attempt < 96; attempt++) {
            double angle = level.random.nextDouble() * Math.PI * 2.0;
            int radius = 48 + level.random.nextInt(5);
            BlockPos probe = new BlockPos(hall.getX() + (int) Math.round(Math.cos(angle) * radius),
                    gate.getY(), hall.getZ() + (int) Math.round(Math.sin(angle) * radius));
            var node = graph.nearestRoutable(probe, 3, 8);
            if (node == null || !NomadArrivalSites.usable(level, village, graph, node, gate)) continue;
            BlockPos feet = node.feetPos();
            double distance = Math.hypot(feet.getX() - hall.getX(), feet.getZ() - hall.getZ());
            if (distance < 47.0 || distance > 53.0) continue;
            NomadEntity nomad = spawnAt(level, feet, TravelerLifecycleRules.StayPlan.DUSK);
            if (nomad != null) return nomad;
        }
        return null;
    }

    public List<NomadEntity> currentNomads(ServerLevel level) {
        AABB search = new AABB(village.getBellPos()).inflate(SEARCH_RADIUS, 128, SEARCH_RADIUS);
        return level.getEntitiesOfClass(NomadEntity.class, search,
                n -> n.isAlive() && (village.contains(n.blockPosition())
                        || village.contains(n.getSpawnOrigin())));
    }

    @Nullable
    public NomadEntity spawnNomad(ServerLevel level, BlockPos pos) {
        NomadEntity nomad = DynamicVillages.NOMAD.get().create(level);
        if (nomad == null) return null;
        TravelerLifecycleRules.Departure schedule = allocateDepartureSchedule(level);
        long departure = schedule.gameTime();
        nomad.setDepartureSchedule(departure, schedule.dayClock(), level.getDayTime());
        nomad.setSpawnOrigin(level, pos);
        nomad.setVillageId(village.getVillageId());
        nomad.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        if (!level.addFreshEntity(nomad)) return null;
        activeDepartures.put(nomad.getUUID(), departure);
        village.markPersistentDataDirty();
        return nomad;
    }

    /** Fallback radius from the bell for players just outside the village bounds. */
    private static final double ANNOUNCE_RADIUS = 128.0;

    private void announceArrival(ServerLevel level, int groupSize, TravelerLifecycleRules.StayPlan plan) {
        Component[] pool = ARRIVAL_MESSAGES[Math.max(1, Math.min(groupSize, ARRIVAL_MESSAGES.length)) - 1];
        Component message = villageLine(pool[level.random.nextInt(pool.length)]).withStyle(ChatFormatting.AQUA);
        Component hint = stayHint(plan);
        double radiusSq = ANNOUNCE_RADIUS * ANNOUNCE_RADIUS;
        BlockPos bell = village.getBellPos();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (village.contains(player.blockPosition())
                    || player.distanceToSqr(bell.getX() + 0.5, bell.getY() + 0.5, bell.getZ() + 0.5)
                            <= radiusSq) {
                player.sendSystemMessage(message);
                if (hint != null) player.sendSystemMessage(hint);
            }
        }
    }

    /** The arrival line's second line: whether they may stay late. Null for the usual dusk leave. */
    @Nullable
    static Component stayHint(TravelerLifecycleRules.StayPlan plan) {
        return switch (plan) {
            case NIGHT -> Tr.c("chat.dynamicvillages.travelers.stay_night", "They may stay the night if it gets late.")
                    .withStyle(ChatFormatting.GRAY);
            case EVENING -> Tr.c("chat.dynamicvillages.travelers.stay_evening", "They may stay into the evening.")
                    .withStyle(ChatFormatting.GRAY);
            case DUSK -> null;
        };
    }

    /** "[Village] " and the line, in each player's own language. A new component each time: safe to style. */
    static MutableComponent villageLine(Component line) {
        return Tr.c("chat.dynamicvillages.village.line", "[Village] %s", line);
    }

    private static TravelerLifecycleRules.StayPlan planOf(PendingGroup group) {
        TravelerLifecycleRules.StayPlan[] plans = TravelerLifecycleRules.StayPlan.values();
        return group.plan >= 0 && group.plan < plans.length ? plans[group.plan] : TravelerLifecycleRules.StayPlan.DUSK;
    }

    private TravelerLifecycleRules.Departure allocateDepartureSchedule(ServerLevel level) {
        return TravelerLifecycleRules.staggeredDepartureSchedule(
                level.getGameTime(), level.getDayTime(), activeDepartures.values(),
                level.random::nextInt);
    }

    private String shortId() { return village.getVillageId().toString().substring(0, 8); }
}
