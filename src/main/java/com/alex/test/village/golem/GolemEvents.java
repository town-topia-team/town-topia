package com.alex.test.village.golem;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.GolemRandomStrollInVillageGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.animal.IronGolem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Iron golems belong to our villages: our villagers claim no vanilla village
 * spots, so Minecraft never sees a village here and a golem drifts away. Each
 * golem gets a home (the village whose border it stands in), walks back when
 * it strays out, and wanders inside the border instead of anywhere.
 */
public final class GolemEvents {

    /** Above the vanilla stroll (4), below fighting (1); the vanilla walk-back (2) finds no village here. */
    private static final int RETURN_PRIORITY = 2;

    private GolemEvents() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof IronGolem golem) || !(event.getLevel() instanceof ServerLevel)) return;
        WrappedGoal stroll = null;
        for (WrappedGoal wrapped : golem.goalSelector.getAvailableGoals()) {
            // Already set up (the same golem joining again).
            if (wrapped.getGoal() instanceof GolemReturnHomeGoal) return;
            if (wrapped.getGoal() instanceof GolemRandomStrollInVillageGoal) stroll = wrapped;
        }
        GolemHome home = new GolemHome(golem);
        golem.goalSelector.addGoal(RETURN_PRIORITY, new GolemReturnHomeGoal(golem, home));
        if (stroll != null) {
            golem.goalSelector.removeGoal(stroll.getGoal());
            golem.goalSelector.addGoal(stroll.getPriority(), new GolemHomeStrollGoal(golem, home, stroll.getGoal()));
        }
    }
}
