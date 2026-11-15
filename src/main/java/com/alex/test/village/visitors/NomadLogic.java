package com.alex.test.village.visitors;

import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.entity.visitor.NomadEntity;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.WalkFeel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Non-member nomad behaviour: walk toward the active village whose bounds the
 * nomad currently occupies, then wait at its gathering point. Proximity is
 * used only as a navigation target; it never creates or persists membership.
 */
public final class NomadLogic {

    /** Vertical tolerance for the individually reserved gathering tile. */
    private static final double ARRIVAL_MAX_DY = 5.0;
    private static final double WALK_SPEED = 0.4;
    /** Within this (1.5 blocks, squared) of the tile after the map walk, the last step is steered straight. */
    private static final double LAST_STEP_SQ = 2.25;
    private static final TravelerApproach APPROACH = new TravelerApproach("Nomad");

    private NomadLogic() {}

    public static void tick(NomadEntity nomad, ServerLevel level) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        VillageManager mgr = nomad.getVillageId() == null ? null : registry.get(nomad.getVillageId());
        if (mgr == null) mgr = registry.findVillageAt(level, nomad.blockPosition());
        // A nomad is never attached to a village. If it is outside all active
        // bounds, or the nearby village is paused, it simply remains a nomad
        // until its ordinary despawn deadline.
        if (mgr == null || registry.isVillageDormantOrReconciling(mgr.getVillageId())) {
            APPROACH.clear(nomad);
            return;
        }

        // Outside the Town Hall door and off to one side, not the door itself:
        // vanilla navigation will not path onto a door block, so a nomad aimed
        // at one stops short and never counts itself arrived. Not the doorstep
        // either, where nomads stood blocking the way in.
        BlockPos bell = TravelerGatheringSpots.claimAtTownHall(nomad, level, mgr);
        BlockPos pos = nomad.blockPosition();
        boolean atBell = pos.getX() == bell.getX() && pos.getZ() == bell.getZ()
                && Math.abs(pos.getY() - bell.getY()) <= ARRIVAL_MAX_DY;

        if (!atBell) {
            // Inside the village the nomad follows the nav graph, as villagers
            // do. Founding nomads begin outside the graph's bounds, so vanilla
            // navigation covers that approach, and the last step onto the
            // exact tile once the graph route has finished.
            TravelerApproach.Result result = APPROACH.tick(nomad, mgr, bell, (float) WALK_SPEED);
            if (result == TravelerApproach.Result.WALKING) return;
            double dx = nomad.getX() - (bell.getX() + 0.5);
            double dz = nomad.getZ() - (bell.getZ() + 0.5);
            if (result == TravelerApproach.Result.FINISHED && dx * dx + dz * dz <= LAST_STEP_SQ) {
                // The map walk ended beside the tile (someone stood on it): the
                // last step straight, as the camp walks do, not a Minecraft path.
                nomad.getMoveControl().setWantedPosition(bell.getX() + 0.5, bell.getY(), bell.getZ() + 0.5,
                        WALK_SPEED);
            } else if (WalkFeel.nomadMap && result != TravelerApproach.Result.USE_VANILLA) {
                waitWithoutRoute(nomad, level, bell, result);
            } else if (nomad.getNavigation().isDone()) {
                // Off the map (a founding nomad's walk in), or with nomadmap off no
                // map route or a tile the map does not have: Minecraft's own path.
                VanillaNavCount.note(result == TravelerApproach.Result.USE_VANILLA
                        ? VanillaNavCount.Site.NOMAD_OFF_MAP : VanillaNavCount.Site.NOMAD, nomad);
                var path = nomad.getNavigation().createPath(bell, 1);
                if (path != null) nomad.getNavigation().moveTo(path, WALK_SPEED);
            }
            return;
        }

        APPROACH.clear(nomad);
        nomad.getNavigation().stop();
    }

    /**
     * Leftovers step 5 ({@link WalkFeel#nomadMap}): inside the village with no map
     * route to its tile ({@code NO_ROUTE}: asked again every 2 s, or the walk gave up
     * after 3 stucks), or with the route
     * ended away from it ({@code FINISHED}: as near as the map gets), the nomad waits
     * where it is instead of trying Minecraft's path. With no route, a minute without
     * headway hops it once onto an open map tile nearby (off whatever ledge or roof
     * holds it), and the map is asked again from there.
     */
    private static void waitWithoutRoute(NomadEntity nomad, ServerLevel level, BlockPos bell,
                                         TravelerApproach.Result result) {
        nomad.getNavigation().stop();
        if (result != TravelerApproach.Result.NO_ROUTE) return;
        long stuck = nomad.walkStuckTicks(level.getGameTime(), Vec3.atBottomCenterOf(bell));
        if (stuck >= AbstractTravelerEntity.STUCK_HOP_TICKS && nomad.hopIfStuck(level, stuck)) {
            APPROACH.clear(nomad);
        }
    }
}
