package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.VillageDays;
import com.alex.test.village.VillageManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One authoritative, persisted merchant lifecycle for a village. A new village
 * gets a merchant on each of its first mornings; after that the gap between
 * visits depends on how many villagers live there (see {@link TravelerLifecycleRules}).
 * Each village has its own merchant; villages never wait on each other's.
 */
public final class VisitorSpawnManager {
    public static final int VISIT_DURATION_TICKS = 12_000; // legacy/test compatibility
    private static final double SEARCH_RADIUS = 384.0;
    private static final double ANNOUNCE_RADIUS = 128.0;
    /** Randomly picked; each player's own game translates them. Only ever passed into a new message, never changed. */
    private static final Component[] ARRIVAL_MESSAGES = {
            Tr.c("chat.dynamicvillages.merchant.arrival.1", "A traveling merchant has spotted your village and is making their way over."),
            Tr.c("chat.dynamicvillages.merchant.arrival.2", "A merchant is approaching the village with goods to trade."),
            Tr.c("chat.dynamicvillages.merchant.arrival.3", "A trader is coming down the road toward your village."),
            Tr.c("chat.dynamicvillages.merchant.arrival.4", "A merchant has arrived at the edge of the village."),
            Tr.c("chat.dynamicvillages.merchant.arrival.5", "Someone with a pack full of wares is heading toward the village."),
            Tr.c("chat.dynamicvillages.merchant.arrival.6", "A wandering merchant is on their way to set up shop."),
            Tr.c("chat.dynamicvillages.merchant.arrival.7", "Word of your village has reached a traveling merchant, and they're coming to trade."),
            Tr.c("chat.dynamicvillages.merchant.arrival.8", "A trader is making their way into the village, ready to do business."),
    };

    public record SavedState(boolean initialized, @Nullable UUID activeMerchantId,
                             long departureAt, long visitEndedAt,
                             int morningsSinceVisit, long lastMorningDay,
                             int foundingVisitsLeft, long pendingArrivalAt,
                             long pendingCancelAt) {
        public static SavedState empty() {
            return new SavedState(false, null, -1L, -1L, 0, -1L, 0, -1L, -1L);
        }
    }

    /** Retry spacing while thunder holds a due merchant back. */
    private static final int ARRIVAL_RETRY_TICKS = 40;
    /** Retry spacing when a due merchant found nowhere safe to appear (20 s). */
    private static final int NO_SITE_RETRY_TICKS = 400;
    /**
     * A merchant this long past its leave time that is not walking any more
     * (out of range) has its record ended: its own return timeout plus a minute.
     */
    private static final long STALE_RECORD_TICKS = TravelerLifecycleRules.RETURN_TIMEOUT_TICKS + 1_200L;
    private static final long STALE_CHECK_INTERVAL_TICKS = 100L;

    private final VillageManager village;
    private boolean arrivalsEnabled = true;
    private boolean initialized;
    @Nullable private UUID activeMerchantId;
    private long activeDepartureAt = -1L;
    private long visitEndedAt = -1L;
    private int morningsSinceVisit;
    /** Day index of the last morning counted, so each morning is rolled once. */
    private long lastMorningDay = -1L;
    private int foundingVisitsLeft;
    /** A rolled merchant shows up at this game time; -1 when none is on the way. */
    private long pendingArrivalAt = -1L;
    /** A merchant still held back by thunder (or with nowhere to stand) at this time is called off. */
    private long pendingCancelAt = -1L;
    /** Game time of the last night skip; not saved, it only matters for a few ticks. */
    private long wokeAtGameTime = -1L;
    /** Day clock and game time last seen, to notice the clock jumping ahead; not saved. */
    private long seenDayTime = -1L;
    private long seenGameTime = -1L;
    private long nextStaleCheckAt;

    public VisitorSpawnManager(VillageManager village) { this.village = village; }

    public void setArrivalsEnabled(boolean enabled) { arrivalsEnabled = enabled; }

    /** A new village gets a merchant on each of its next few mornings. */
    public void scheduleFoundingMerchants(long gameTime, long dayTime) {
        initialized = true;
        activeMerchantId = null;
        activeDepartureAt = -1L;
        visitEndedAt = gameTime;
        morningsSinceVisit = 0;
        lastMorningDay = TravelerLifecycleRules.dayIndex(dayTime);
        foundingVisitsLeft = TravelerLifecycleRules.FOUNDING_MERCHANT_VISITS;
        pendingArrivalAt = -1L;
        pendingCancelAt = -1L;
        village.markPersistentDataDirty();
    }

    public SavedState savedState() {
        return new SavedState(initialized, activeMerchantId, activeDepartureAt,
                visitEndedAt, morningsSinceVisit, lastMorningDay, foundingVisitsLeft,
                pendingArrivalAt, pendingCancelAt);
    }

    public long pendingArrivalAt() { return pendingArrivalAt; }

    /**
     * A night skip (slept through, or the clock turned back to morning): a
     * merchant on the way lands anywhere from waking up to four minutes later,
     * and a merchant that would have left during the skipped night sets off
     * now, walking out, instead of staying into the new morning.
     */
    public void onPlayerSleepFinished(ServerLevel level, long skippedTicks, long wakeDayTime) {
        if (skippedTicks <= 0L) return;
        long now = level.getGameTime();
        wokeAtGameTime = now;
        // Handled here: the jump must not be counted again (followClockJump).
        seenDayTime = wakeDayTime;
        seenGameTime = now;
        if (pendingArrivalAt >= 0L) {
            pendingArrivalAt = now + level.random.nextInt(
                    TravelerLifecycleRules.WAKE_ARRIVAL_SPREAD_TICKS + 1);
            pendingCancelAt = TravelerLifecycleRules.arrivalCancelAt(now, wakeDayTime);
            village.markPersistentDataDirty();
        }
        if (activeMerchantId != null) {
            // The entity takes the new time from this record (reconcileLifecycle).
            // A merchant who stayed the night keeps its morning leave time.
            long shifted = TravelerLifecycleRules.afterSkippedNight(activeDepartureAt, now, skippedTicks);
            if (shifted != activeDepartureAt) {
                activeDepartureAt = shifted;
                village.markPersistentDataDirty();
            }
        }
    }

    public void restore(SavedState state) {
        SavedState restored = state != null ? state : SavedState.empty();
        initialized = restored.initialized();
        activeMerchantId = restored.activeMerchantId();
        activeDepartureAt = restored.departureAt();
        visitEndedAt = restored.visitEndedAt();
        morningsSinceVisit = Math.max(0, restored.morningsSinceVisit());
        lastMorningDay = restored.lastMorningDay();
        foundingVisitsLeft = Math.max(0, restored.foundingVisitsLeft());
        pendingArrivalAt = restored.pendingArrivalAt();
        pendingCancelAt = restored.pendingCancelAt();
        if (activeMerchantId == null) activeDepartureAt = -1L;
        else if (activeDepartureAt < 0L) {
            DynamicVillages.LOGGER.warn("[VillageManager {}] Cleared corrupt active merchant {} "
                    + "with no valid departure timestamp.", shortId(), activeMerchantId);
            activeMerchantId = null;
            visitEndedAt = -1L;
            morningsSinceVisit = 0;
            village.markPersistentDataDirty();
        }
    }

    public boolean hasActiveMerchantRecord() { return activeMerchantId != null; }
    @Nullable public UUID activeMerchantId() { return activeMerchantId; }

    @Nullable
    public Long departureFor(UUID merchantId) {
        return merchantId.equals(activeMerchantId) ? activeDepartureAt : null;
    }

    public void finishMerchant(UUID merchantId, long now, long dayTime, String reason) {
        if (!merchantId.equals(activeMerchantId)) return;
        long departedAt = activeDepartureAt;
        activeMerchantId = null;
        activeDepartureAt = -1L;
        visitEndedAt = now;
        long today = TravelerLifecycleRules.dayIndex(dayTime);
        if (lastMorningDay == today && departedAt >= 0L && departedAt
                <= now - VillageDays.ticksIntoDay(now) + TravelerLifecycleRules.LEAVING_SOON_TICKS) {
            // It set off before this day began, or stayed the night and left
            // this morning, and this morning already rolled while it was on its
            // way out (see tick): that roll started the count.
        } else {
            morningsSinceVisit = 0;
            // A merchant still visiting into the morning must not free that same
            // morning for the next one; counting restarts with tomorrow.
            lastMorningDay = today;
        }
        initialized = true;
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] Merchant {} visit ended: {}; "
                        + "next arrival counted from the following morning.",
                shortId(), merchantId, reason);
    }

    /**
     * The clock jumped ahead with no night skip reported ({@code /time add}):
     * the leave time comes that much sooner, as after sleeping, so a merchant
     * who stayed the night still leaves in the morning on the village's record.
     */
    private void followClockJump(long now, long dayTime) {
        long ahead = TravelerLifecycleRules.clockJumpAhead(seenDayTime, dayTime, seenGameTime, now);
        seenDayTime = dayTime;
        seenGameTime = now;
        if (ahead <= 0L || activeMerchantId == null) return;
        long shifted = TravelerLifecycleRules.afterSkippedNight(activeDepartureAt, now, ahead);
        if (shifted == activeDepartureAt) return;
        activeDepartureAt = shifted;
        village.markPersistentDataDirty();
        if (ahead >= 100L) {
            DynamicVillages.LOGGER.info("[VillageManager {}] Clock jumped {} ticks ahead: merchant leave time "
                    + "moved up to match.", shortId(), ahead);
        }
    }

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()) return;
        long now = level.getGameTime();
        followClockJump(now, level.getDayTime());
        if (!arrivalsEnabled) return;
        if (!initialized) {
            // Old saves had no reliable merchant record. Starting a fresh daily
            // schedule is safer than adopting whichever duplicate loads first.
            initialized = true;
            visitEndedAt = now;
            morningsSinceVisit = 0;
            lastMorningDay = TravelerLifecycleRules.dayIndex(level.getDayTime());
            village.markPersistentDataDirty();
            DynamicVillages.LOGGER.info("[VillageManager {}] Initialized persistent merchant schedule.",
                    shortId());
            return;
        }
        if (activeMerchantId != null) {
            checkStaleMerchant(level, now);
            // Still visiting, including in unloaded chunks. One already walking
            // out does not hold back the next morning's roll; the next merchant
            // just waits for it to be gone (tickPending).
            if (activeMerchantId != null && !isLeaving(now)) return;
        }
        tickPending(level, now);
        // This morning's roll already sent one on the way.
        if (pendingArrivalAt >= 0L) return;
        long dayTime = level.getDayTime();
        long today = TravelerLifecycleRules.dayIndex(dayTime);
        if (today < lastMorningDay) {
            // Village days only move forward (VillageDays). A saved morning
            // ahead of today comes from before that, when /time set took the
            // world's own day number back: today counts as handled.
            lastMorningDay = today;
            village.markPersistentDataDirty();
            return;
        }
        if (!TravelerLifecycleRules.isMorningWindow(now, dayTime)) return;
        if (today == lastMorningDay) return;
        // A camp's fire was broken last night: no merchant today. The morning
        // is skipped, not counted, so a certain first visit comes a day later.
        if (village.travelerCamp().isSnubbed(today)) return;

        // Persist the check before rolling so reloads and time changes cannot reroll it.
        lastMorningDay = today;
        // The last merchant set off before this morning and is still walking
        // out: its visit is over, so the count starts again, as if it were gone.
        if (activeMerchantId != null) morningsSinceVisit = 0;
        morningsSinceVisit++;
        village.markPersistentDataDirty();
        int villagers = village.getVillagerIds().size();
        double chance = TravelerLifecycleRules.merchantChanceForMorning(
                morningsSinceVisit, villagers, foundingVisitsLeft);
        DynamicVillages.LOGGER.debug("[VillageManager {}] Merchant morning {} check "
                        + "({} villagers, {} founding visits left): chance {}.",
                shortId(), morningsSinceVisit, villagers, foundingVisitsLeft, chance);
        if (chance <= 0.0D || level.random.nextDouble() >= chance) return;
        // Show up at a random time this morning rather than right at sunrise.
        boolean justWoke = wokeAtGameTime >= 0L
                && now - wokeAtGameTime <= TravelerLifecycleRules.RECENT_WAKE_TICKS;
        pendingArrivalAt = now + TravelerLifecycleRules.arrivalDelay(dayTime, justWoke,
                level.random::nextInt);
        pendingCancelAt = TravelerLifecycleRules.arrivalCancelAt(now, dayTime);
        village.markPersistentDataDirty();
        DynamicVillages.LOGGER.info("[VillageManager {}] Merchant will arrive at {} (in {} ticks).",
                shortId(), pendingArrivalAt, pendingArrivalAt - now);
    }

    /**
     * The merchant here is on its way out: it has set off home, or stayed the
     * night and leaves within the morning leave spread. It holds back no
     * morning roll; the next merchant waits for it to be gone.
     */
    private boolean isLeaving(long now) {
        return activeMerchantId != null && activeDepartureAt >= 0L
                && activeDepartureAt <= now + TravelerLifecycleRules.LEAVING_SOON_TICKS;
    }

    /** Sends the merchant off now (its fire was broken, or it had nowhere to camp). */
    public void leaveNow(long now) {
        if (activeMerchantId == null) return;
        activeDepartureAt = now;
        village.markPersistentDataDirty();
    }

    /**
     * A storm at dusk: a merchant who would leave this evening stays the night
     * and leaves the next morning instead. Returns whether it did.
     */
    public boolean stayOvernight(ServerLevel level, long now, long dayTime) {
        long morning = TravelerLifecycleRules.morningAfter(now, dayTime);
        if (activeMerchantId == null || activeDepartureAt <= now || activeDepartureAt >= morning) return false;
        activeDepartureAt = morning + level.random.nextInt(TravelerLifecycleRules.MORNING_LEAVE_SPREAD + 1);
        village.markPersistentDataDirty();
        return true;
    }

    /**
     * Ends the record of a merchant that should long be gone but is not walking
     * any more: its chunks are unloaded, or loaded but out of simulation range.
     * A merchant that is still ticking leaves by itself (return timeout). An
     * unloaded one removes itself the moment its chunk loads, finding its
     * record gone (VisitorEntity.reconcileLifecycle).
     */
    private void checkStaleMerchant(ServerLevel level, long now) {
        if (activeDepartureAt < 0L || now - activeDepartureAt < STALE_RECORD_TICKS) return;
        if (now < nextStaleCheckAt) return;
        nextStaleCheckAt = now + STALE_CHECK_INTERVAL_TICKS;
        UUID merchantId = activeMerchantId;
        Entity entity = level.getEntity(merchantId);
        if (entity == null) {
            finishMerchant(merchantId, now, level.getDayTime(), "left while out of range");
            return;
        }
        if (entity instanceof VisitorEntity visitor && entity.isAlive()
                && !level.isPositionEntityTicking(entity.blockPosition())
                && !level.hasNearbyAlivePlayer(entity.getX(), entity.getY(), entity.getZ(),
                        TravelerLifecycleRules.SAFE_REMOVAL_PLAYER_RADIUS)) {
            visitor.finishVisit(level, "left while out of range");
        }
    }

    /**
     * Spawns the merchant on the way once it is due. Thunder, the last merchant
     * still walking out, or nowhere safe to stand hold it back; still held back
     * at the cancel time, today's visit is called off and the next morning
     * rolls again.
     */
    private void tickPending(ServerLevel level, long now) {
        if (pendingArrivalAt < 0L || now < pendingArrivalAt) return;
        if (TravelerLifecycleRules.isTooLateToArrive(level.getDayTime())) {
            // Past 2 PM nobody turns up. A first visit is not used up: the
            // next morning's merchant is still certain.
            pendingArrivalAt = -1L;
            pendingCancelAt = -1L;
            village.markPersistentDataDirty();
            DynamicVillages.LOGGER.info("[VillageManager {}] Merchant visit called off for today "
                    + "(too late in the day).", shortId());
            return;
        }
        boolean blocked = level.isThundering() || activeMerchantId != null;
        if (!blocked && arrive(level, VISIT_DURATION_TICKS) != null) {
            pendingArrivalAt = -1L;
            pendingCancelAt = -1L;
            if (foundingVisitsLeft > 0) foundingVisitsLeft--;
            village.markPersistentDataDirty();
            return;
        }
        if (pendingCancelAt >= 0L && now >= pendingCancelAt) {
            pendingArrivalAt = -1L;
            pendingCancelAt = -1L;
            village.markPersistentDataDirty();
            DynamicVillages.LOGGER.info("[VillageManager {}] Merchant visit called off for today ({}).",
                    shortId(), !blocked ? "no safe arrival site"
                            : activeMerchantId != null ? "the last merchant was still leaving" : "thunder");
            return;
        }
        // Thunder and the last merchant are cheap to re-check; a failed site
        // search waits longer before trying again.
        pendingArrivalAt = now + (blocked ? ARRIVAL_RETRY_TICKS : NO_SITE_RETRY_TICKS);
        village.markPersistentDataDirty();
    }

    @Nullable
    public VisitorEntity arrive(ServerLevel level, int ignoredLingerTicks) {
        if (activeMerchantId != null) return null;
        // Same far-out sites as nomads, near the edge of the nearest player's
        // simulation distance, so the merchant also walks back out there to leave.
        List<BlockPos> sites = new ArrayList<>(
                NomadArrivalSites.candidates(level, village, level.random, Set.of()));
        sites.addAll(VisitorArrivalSites.candidates(level, village, level.random));
        // How long it stays is picked as it arrives: later arrivals stay longer.
        TravelerLifecycleRules.StayPlan plan = village.travelerCamp().planFor(level);
        for (BlockPos site : sites) {
            VisitorEntity visitor = DynamicVillages.VISITOR.get().create(level);
            if (visitor == null) return null;
            TravelerLifecycleRules.Departure schedule =
                    TravelerLifecycleRules.departureFor(plan,
                    level.getGameTime(), level.getDayTime(), List.of(), level.random::nextInt);
            long departure = schedule.gameTime();
            visitor.setVillageId(village.getVillageId());
            visitor.prepareOffers(level, village);
            fillPurse(level, visitor);
            visitor.setDepartureSchedule(departure, schedule.dayClock(), level.getDayTime());
            visitor.setSpawnOrigin(level, site);
            visitor.moveTo(site.getX() + 0.5, site.getY(), site.getZ() + 0.5, 0, 0);
            visitor.setOnGround(true);

            // Install the record first: EntityJoinLevelEvent can run inside addFreshEntity.
            activeMerchantId = visitor.getUUID();
            activeDepartureAt = departure;
            initialized = true;
            village.markPersistentDataDirty();
            if (!level.addFreshEntity(visitor)) {
                clearFailedArrival(visitor.getUUID());
                continue;
            }
            // Every site already has a graph route to the gate, and MerchantLogic
            // walks it on the graph from the first tick. No vanilla path check here:
            // from the far sites it ran a 4096-node search per site that usually
            // failed, which froze the server for seconds on every retry.
            announceArrival(level, plan);
            DynamicVillages.LOGGER.info("[VillageManager {}] MERCHANT {} arriving at {}; "
                            + "departure scheduled for {}.",
                    shortId(), visitor.getUUID(), site, departure);
            return visitor;
        }
        return null;
    }

    private void clearFailedArrival(UUID merchantId) {
        if (!merchantId.equals(activeMerchantId)) return;
        activeMerchantId = null;
        activeDepartureAt = -1L;
        village.markPersistentDataDirty();
    }

    private void announceArrival(ServerLevel level, TravelerLifecycleRules.StayPlan plan) {
        Component hint = NomadSpawnManager.stayHint(plan);
        Component message = NomadSpawnManager.villageLine(ARRIVAL_MESSAGES[level.random.nextInt(ARRIVAL_MESSAGES.length)])
                .withStyle(ChatFormatting.AQUA);
        boolean tradesman = village.getVillagers().stream().anyMatch(model -> model.puppet() != null
                && model.puppet().getProfession() == com.alex.test.village.villagers.core.Profession.TRADESMAN);
        Component tradesmanLine = Tr.c("chat.dynamicvillages.merchant.tradesman_tip", "Your tradesman will sell the village's surplus to it, "
                + "along with any village goods in the stall's sell box.").withStyle(ChatFormatting.GRAY);
        double radiusSq = ANNOUNCE_RADIUS * ANNOUNCE_RADIUS;
        BlockPos bell = village.getBellPos();
        for (net.minecraft.server.level.ServerPlayer player : level.players()) {
            if (village.contains(player.blockPosition())
                    || player.distanceToSqr(bell.getX() + 0.5, bell.getY() + 0.5, bell.getZ() + 0.5)
                            <= radiusSq) {
                player.sendSystemMessage(message);
                if (hint != null) player.sendSystemMessage(hint);
                if (tradesman) {
                    int seen = player.getPersistentData().getInt(TRADESMAN_TIP_KEY);
                    if (seen < TRADESMAN_TIP_TIMES) {
                        player.sendSystemMessage(tradesmanLine);
                        player.getPersistentData().putInt(TRADESMAN_TIP_KEY, seen + 1);
                    }
                }
            }
        }
    }

    /** Player data key: how many arrivals have shown this player the tradesman tip. */
    public static final String TRADESMAN_TIP_KEY = "dynamicvillages_tradesman_tip";
    private static final int TRADESMAN_TIP_TIMES = 2;

    public List<VisitorEntity> currentVisitors(ServerLevel level) {
        AABB search = new AABB(village.getBellPos()).inflate(SEARCH_RADIUS, 128, SEARCH_RADIUS);
        return level.getEntitiesOfClass(VisitorEntity.class, search,
                v -> v.isAlive() && village.getVillageId().equals(v.getVillageId()));
    }

    @Nullable
    public VisitorEntity spawnVisitor(ServerLevel level, BlockPos pos) {
        return spawnVisitor(level, pos, TravelerLifecycleRules.StayPlan.DUSK);
    }

    /**
     * Spawns a merchant at {@code pos} right away (the {@code /villager merchant spawn}
     * command), staying as {@code plan} says, or as a real arrival would when null.
     */
    @Nullable
    public VisitorEntity spawnVisitor(ServerLevel level, BlockPos pos,
                                      @Nullable TravelerLifecycleRules.StayPlan plan) {
        if (activeMerchantId != null) return null;
        VisitorEntity visitor = DynamicVillages.VISITOR.get().create(level);
        if (visitor == null) return null;
        if (plan == null) plan = village.travelerCamp().planFor(level);
        // A camp settled earlier tonight without this merchant gets another look.
        if (plan == TravelerLifecycleRules.StayPlan.NIGHT) village.travelerCamp().allowNewCampTonight();
        TravelerLifecycleRules.Departure schedule = TravelerLifecycleRules.departureFor(plan,
                level.getGameTime(), level.getDayTime(), List.of(), level.random::nextInt);
        long departure = schedule.gameTime();
        visitor.setVillageId(village.getVillageId());
        visitor.prepareOffers(level, village);
        fillPurse(level, visitor);
        visitor.setDepartureSchedule(departure, schedule.dayClock(), level.getDayTime());
        visitor.setSpawnOrigin(level, pos);
        visitor.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        activeMerchantId = visitor.getUUID();
        activeDepartureAt = departure;
        initialized = true;
        village.markPersistentDataDirty();
        if (level.addFreshEntity(visitor)) return visitor;
        clearFailedArrival(visitor.getUUID());
        return null;
    }

    /**
     * The purse a merchant brings (MarketMemory.purseFor) and, on one of a new
     * village's founding visits, welcome prices: everything pays double, up to
     * the price table's ceiling, so the first stall and tradesman come sooner.
     */
    private void fillPurse(ServerLevel level, VisitorEntity visitor) {
        int villagers = village.getVillagerIds().size();
        int purse = com.alex.test.village.economy.MarketMemory.get(level.getServer())
                .newPurse(village.getVillageId(), villagers);
        visitor.setPurse(purse);
        visitor.setWelcomeVisit(foundingVisitsLeft > 0);
        DynamicVillages.LOGGER.info("[VillageManager {}] Merchant brings {} emeralds{}.", shortId(), purse,
                foundingVisitsLeft > 0 ? " and welcome prices" : "");
    }

    private String shortId() { return village.getVillageId().toString().substring(0, 8); }
}
