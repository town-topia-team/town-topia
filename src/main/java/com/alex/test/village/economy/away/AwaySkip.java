package com.alex.test.village.economy.away;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.economy.CatchUp;
import com.alex.test.village.economy.MarketMemory;
import com.alex.test.village.villagers.child.ChildProfession;
import com.alex.test.village.villagers.cook.CookDemand;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.visitors.MerchantPricing;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The days a catch-up does not live through: time away past the 3-day cap,
 * or with nobody on the server ({@link CatchUp#uncountedTicks}). Moved here
 * from the old calculator in round 12, unchanged.
 */
public final class AwaySkip {

    private AwaySkip() {}

    /**
     * Children do not age or miss school in the uncounted days, the cook's
     * appetite does not fade over them, market prices do not recover through
     * them, and pen babies owe the Good Breeder nothing for them. Raids skip
     * the whole time away, counted days too: none comes closer while nobody is
     * there. Once per catch-up ({@link CatchUp#claimUncountedSkip}):
     * at WRITE, or when a catch-up failed ({@code VillageDormancy}). Members with
     * no body loaded take their part when they load ({@link CatchUp#notLoaded}).
     */
    public static void skipUncounted(ServerLevel level, VillageManager mgr, CatchUp away) {
        if (!away.claimUncountedSkip()) return;
        // Pen babies were not growing at all (chunks unloaded), counted days included.
        RancherActivity.restartGrowClocks(level, mgr);
        // Village time away, lived or not (counted + uncounted = the village calendar's run).
        long villageAway = away.countedTicks() + away.uncountedTicks();
        mgr.raids().skipDaysAway(Math.round(villageAway / (double) EveningSchedule.DAY_TICKS));
        long uncounted = away.uncountedTicks();
        if (uncounted <= 0L) return;
        long days = Math.round(uncounted / (double) EveningSchedule.DAY_TICKS);
        MarketMemory.get(level.getServer()).skipUncountedDays(mgr.getVillageId(), days, MerchantPricing.today(level));
        for (UUID villagerId : mgr.getVillagerIds()) {
            NpcEntity npc = livePuppet(mgr.getModel(villagerId));
            if (npc == null) {
                // No body to stamp: it takes the skip when it loads (VillageManager.bindVillager).
                away.notLoaded().add(villagerId);
            } else {
                skipUncountedAway(npc, uncounted);
            }
        }
    }

    /** One villager's part of {@link #skipUncounted}: a child's age and school days, a cook's appetite days. */
    public static void skipUncountedAway(NpcEntity npc, long ticks) {
        if (ticks <= 0L) return;
        ChildProfession.skipUncountedAway(npc, ticks);
        if (npc.getProfession() == Profession.COOK) {
            CookDemand.skipUncountedDays(npc.getBrainData(), Math.round(ticks / (double) EveningSchedule.DAY_TICKS),
                    EveningSchedule.dayIndex(npc.level().getDayTime()));
        }
    }

    @Nullable
    private static NpcEntity livePuppet(@Nullable VillagerModel model) {
        NpcEntity npc = model == null ? null : model.puppet();
        return npc == null || !npc.isAlive() || npc.isRemoved() ? null : npc;
    }
}
