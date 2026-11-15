package com.alex.test.village.villagers.farmer;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.farmer.behavior.GetHoeActivity;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * When a farmer with no hoe goes for one. The hoe only tills, so the farmer
 * harvests and replants without it, and goes the moment one can be had: a
 * hoe in storage nobody has claimed, or enough unclaimed wood by a crafting
 * table to make one (GetToolActivity claims either before walking).
 *
 * <p>After a failed trip it waits for something to change: storage's tools
 * change, or the hoe and wood run out (or get claimed) and come back. Or
 * {@link #RETRY_TICKS} pass, for a busy table or a blocked path. Without the
 * wait, a farmer with only tilling to do started a work session, failed the
 * same trip and ended the session, over and over.
 */
public final class FarmerHoeTrips {

    /** A failed trip is tried again after this long even when nothing seems to have changed. */
    static final long RETRY_TICKS = 20L * 60 * 2;

    private record Failure(long toolChangeId, long at) {}

    /** Not saved: after a reload the farmer simply tries again. */
    private final Map<UUID, Failure> failures = new HashMap<>();

    /** A farmer that grows a farmland crop, has no usable hoe, and could get one now. */
    public boolean worthTrying(Activity.Context ctx, FarmerSettings settings) {
        if (!FarmerManager.needsHoe(settings)
                || FarmerHoeUtils.findUsableHoeSlot(ctx.npc().getInventory(), settings) >= 0) return false;
        UUID id = ctx.model().npcId;
        if (!GetToolActivity.canAcquire(ctx, GetHoeActivity.HOE_REQUIREMENT)) {
            // Nothing free to fetch or craft: whatever turns up next is news.
            failures.remove(id);
            return false;
        }
        Failure failure = failures.get(id);
        if (failure == null) return true;
        if (ctx.level().getGameTime() - failure.at() < RETRY_TICKS
                && ToolUpgradeHelper.newestToolStorageChangeId(ctx) == failure.toolChangeId()) return false;
        failures.remove(id);
        return true;
    }

    /** The trip ended without a hoe: hold off until something changes. */
    public void failed(Activity.Context ctx) {
        failures.put(ctx.model().npcId, new Failure(
                ToolUpgradeHelper.newestToolStorageChangeId(ctx), ctx.level().getGameTime()));
    }

    public void forget(UUID npcId) {
        failures.remove(npcId);
    }

    void clear() {
        failures.clear();
    }
}
