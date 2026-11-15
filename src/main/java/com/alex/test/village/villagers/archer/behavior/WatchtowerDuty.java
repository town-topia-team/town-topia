package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.villagers.guard.GuardShift;
import com.alex.test.village.villagers.guard.settings.GuardSettings;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who keeps watch on which tower: one lookout per Watchtower, its holder.
 *
 * <p>At the evening shift change a night-shift archer may come as the
 * holder's reliever. The day lookout stays up until the reliever is on the
 * platform (its shift handover, see {@link GuardShift}), then leaves, and
 * the reliever takes the tower over. A claim lapses unless its archer renews
 * it, so a lookout that unloads or dies frees its tower by itself.
 *
 * <p>Also keeps who is standing on a platform right now, and how much
 * farther that lets it see ({@link #spotBonus}).
 */
public final class WatchtowerDuty {

    /** A claim not renewed for this long is free again. */
    private static final int HOLD_TICKS = 60;
    /** A lookout last seen on its platform longer ago than this no longer sees farther. */
    private static final int ON_PLATFORM_TICKS = 40;
    private static final int PRUNE_SIZE = 64;

    private record Key(ResourceKey<Level> dimension, UUID towerId) {}

    private static final class Watch {
        @Nullable UUID holder;
        long holderRenewed;
        @Nullable UUID reliever;
        long relieverRenewed;
    }

    private record OnPlatform(int bonus, long at) {}

    private static final Map<Key, Watch> WATCHES = new HashMap<>();
    private static final Map<UUID, OnPlatform> ON_PLATFORM = new HashMap<>();

    private WatchtowerDuty() {}

    /**
     * The nearest tower in this archer's village it may take now, claimed for
     * it: one nobody holds, or, for a night-shift archer on its shift, one a
     * day-shift lookout still holds and nobody is relieving yet.
     */
    @Nullable
    static WatchtowerBuilding claimNearest(Activity.Context ctx) {
        ServerLevel level = ctx.level();
        UUID self = ctx.model().npcId;
        boolean canRelieve = canRelieve(ctx);
        long now = level.getGameTime();
        WatchtowerBuilding best = null;
        double bestSq = Double.MAX_VALUE;
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof WatchtowerBuilding tower) || !tower.isValid()) continue;
            tower.remeasureIfIncomplete(level);
            if (!tower.isHighEnough() || isMarkedUnreachable(level, tower, now)) continue;
            Watch watch = WATCHES.get(new Key(level.dimension(), tower.getItemFrameId()));
            if (watch != null) {
                lapse(watch, now);
                boolean free = watch.holder == null;
                boolean relievable = canRelieve && watch.reliever == null
                        && watch.holder != null && isDayShift(ctx, watch.holder);
                if (!free && !relievable && !self.equals(watch.holder) && !self.equals(watch.reliever)) continue;
            }
            double d = tower.getItemFramePos().distSqr(ctx.npc().blockPosition());
            if (d < bestSq) {
                bestSq = d;
                best = tower;
            }
        }
        if (best == null) return null;
        Watch watch = WATCHES.computeIfAbsent(new Key(level.dimension(), best.getItemFrameId()), k -> new Watch());
        lapse(watch, now);
        if (watch.holder == null || self.equals(watch.holder)) {
            watch.holder = self;
            watch.holderRenewed = now;
        } else {
            watch.reliever = self;
            watch.relieverRenewed = now;
        }
        if (WATCHES.size() > PRUNE_SIZE) {
            WATCHES.values().removeIf(w -> {
                lapse(w, now);
                return w.holder == null && w.reliever == null;
            });
        }
        return best;
    }

    /**
     * Keeps this archer's claim alive. False once it has none: the claim
     * lapsed and was taken, or (a day lookout) its reliever took over.
     */
    static boolean renew(ServerLevel level, WatchtowerBuilding tower, UUID archer) {
        Watch watch = WATCHES.get(new Key(level.dimension(), tower.getItemFrameId()));
        if (watch == null) return false;
        long now = level.getGameTime();
        lapse(watch, now);
        if (archer.equals(watch.holder)) {
            watch.holderRenewed = now;
            return true;
        }
        if (archer.equals(watch.reliever)) {
            watch.relieverRenewed = now;
            return true;
        }
        return false;
    }

    /** Whether this archer holds the tower (rather than coming to relieve its lookout). */
    static boolean isHolder(ServerLevel level, WatchtowerBuilding tower, UUID archer) {
        Watch watch = WATCHES.get(new Key(level.dimension(), tower.getItemFrameId()));
        return watch != null && archer.equals(watch.holder);
    }

    /** Gives the tower up; a waiting reliever becomes its lookout. */
    static void release(ServerLevel level, WatchtowerBuilding tower, UUID archer) {
        Key key = new Key(level.dimension(), tower.getItemFrameId());
        Watch watch = WATCHES.get(key);
        clearOnPlatform(archer);
        if (watch == null) return;
        long now = level.getGameTime();
        if (archer.equals(watch.holder)) {
            watch.holder = watch.reliever;
            watch.holderRenewed = now;
            watch.reliever = null;
        } else if (archer.equals(watch.reliever)) {
            watch.reliever = null;
        }
        if (watch.holder == null && watch.reliever == null) WATCHES.remove(key);
    }

    /** A holder that lapsed hands over to a live reliever; a lapsed reliever is dropped. */
    private static void lapse(Watch watch, long now) {
        if (watch.reliever != null && (now - watch.relieverRenewed > HOLD_TICKS || now < watch.relieverRenewed)) {
            watch.reliever = null;
        }
        if (watch.holder != null && (now - watch.holderRenewed > HOLD_TICKS || now < watch.holderRenewed)) {
            watch.holder = watch.reliever;
            watch.holderRenewed = watch.relieverRenewed;
            watch.reliever = null;
        }
    }

    /** A night-shift archer, on its own shift now. */
    private static boolean canRelieve(Activity.Context ctx) {
        return GuardSettings.of(ctx.npc()).patrolAtNightOnly() && GuardShift.isShiftNight(ctx);
    }

    /** Whether this lookout works the day shift (so a night-shift archer may relieve it). */
    private static boolean isDayShift(Activity.Context ctx, UUID archer) {
        for (com.alex.test.village.VillagerModel model : ctx.mgr().getVillagers()) {
            if (!model.npcId.equals(archer)) continue;
            NpcEntity puppet = model.puppet();
            return puppet != null && !GuardSettings.of(puppet).patrolAtNightOnly();
        }
        return false;
    }

    // =========================================================================
    // A tower nobody can climb
    // =========================================================================

    /** After a failed climb, the tower is left alone this long by every archer. */
    private static final int UNREACHABLE_TICKS = 1200;
    private static final Map<Key, Long> UNREACHABLE_UNTIL = new HashMap<>();

    /**
     * An archer could not get up this tower (its ladder broken, say). Every
     * archer leaves it for a minute: each one used to find out for itself, by
     * walking over and failing, about once a minute each.
     */
    static void markUnreachable(ServerLevel level, WatchtowerBuilding tower) {
        long now = level.getGameTime();
        UNREACHABLE_UNTIL.put(new Key(level.dimension(), tower.getItemFrameId()), now + UNREACHABLE_TICKS);
        if (UNREACHABLE_UNTIL.size() > PRUNE_SIZE) UNREACHABLE_UNTIL.values().removeIf(until -> until <= now);
    }

    /** Within a minute of a failed climb; a time further off than that is another world's clock. */
    private static boolean isMarkedUnreachable(ServerLevel level, WatchtowerBuilding tower, long now) {
        Long until = UNREACHABLE_UNTIL.get(new Key(level.dimension(), tower.getItemFrameId()));
        return until != null && now < until && until - now <= UNREACHABLE_TICKS;
    }

    // =========================================================================
    // The bell
    // =========================================================================

    /** A tower's bell is rung at most this often. */
    private static final int BELL_COOLDOWN_TICKS = 2400;
    private static final Map<Key, Long> RUNG_AT = new HashMap<>();

    /** Whether this tower's bell may be rung again: not in the last two minutes. */
    static boolean mayRing(ServerLevel level, WatchtowerBuilding tower) {
        Long at = RUNG_AT.get(new Key(level.dimension(), tower.getItemFrameId()));
        long now = level.getGameTime();
        return at == null || now - at >= BELL_COOLDOWN_TICKS || now < at;
    }

    static void markRung(ServerLevel level, WatchtowerBuilding tower) {
        long now = level.getGameTime();
        RUNG_AT.put(new Key(level.dimension(), tower.getItemFrameId()), now);
        if (RUNG_AT.size() > PRUNE_SIZE) RUNG_AT.values().removeIf(at -> now - at >= BELL_COOLDOWN_TICKS);
    }

    // =========================================================================
    // Standing on the platform
    // =========================================================================

    static void markOnPlatform(NpcEntity npc, int bonus, long now) {
        ON_PLATFORM.put(npc.getNpcId(), new OnPlatform(bonus, now));
        if (ON_PLATFORM.size() > PRUNE_SIZE) ON_PLATFORM.values().removeIf(p -> now - p.at() > ON_PLATFORM_TICKS);
    }

    static void clearOnPlatform(UUID archer) {
        ON_PLATFORM.remove(archer);
    }

    /** Extra blocks of sight for a lookout standing on its platform now; 0 for anyone else. */
    public static int spotBonus(NpcEntity npc) {
        OnPlatform on = ON_PLATFORM.get(npc.getNpcId());
        if (on == null) return 0;
        long now = npc.level().getGameTime();
        return now - on.at() <= ON_PLATFORM_TICKS && now >= on.at() ? on.bonus() : 0;
    }

    /** Whether this archer is up on a tower keeping watch now. */
    public static boolean isOnWatch(NpcEntity npc) {
        OnPlatform on = ON_PLATFORM.get(npc.getNpcId());
        if (on == null) return false;
        long now = npc.level().getGameTime();
        return now - on.at() <= ON_PLATFORM_TICKS && now >= on.at();
    }
}
