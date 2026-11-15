package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.RouteDoorHandler;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.shelter.ShelterWatch;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Villagers hiding from monsters keep their building's door shut.
 *
 * <p>A door used to close only once a villager walked 2.5 blocks past it, so
 * with villagers running in one after another it stood open, and a monster
 * right behind them walked in. Now whoever is hiding inside shuts it, unless:
 * <ul>
 *   <li>a player is within {@link #PLAYER_NEAR} blocks of it (using it),</li>
 *   <li>someone is standing in the doorway, or</li>
 *   <li>another villager running here is within {@link #RUNNER_NEAR} blocks of
 *       it, and nearer to it than any monster. A monster nearer than the
 *       runner gets the door shut in its face; the runner then picks another
 *       building (DefenderRetreat gives up a door a monster reaches first).</li>
 * </ul>
 * Never with a monster inside: that would shut the villagers in with it.
 * Iron doors are left alone; villagers cannot work them.
 */
final class ShelterDoor {

    private static final double PLAYER_NEAR = 3.0;
    private static final double RUNNER_NEAR = 3.0;
    /** A monster farther than this from the door does not race a runner to it. */
    private static final double MONSTER_RACE = 6.0;

    private ShelterDoor() {}

    /** Called by a villager hiding in {@code building}, every half second or so. */
    static void keepShut(Activity.Context ctx, Building building) {
        BlockPos door = building.getDoorPos();
        if (door == null) return;
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(door);
        if (!(state.getBlock() instanceof DoorBlock doorBlock) || !state.getValue(DoorBlock.OPEN)
                || !doorBlock.type().canOpenByHand()) return;
        if (ShelterWatch.look(ctx.mgr(), level, building).inside() != null) return;
        Vec3 centre = Vec3.atCenterOf(door);
        if (level.hasNearbyAlivePlayer(centre.x, centre.y, centre.z, PLAYER_NEAR)) return;
        if (!level.getEntitiesOfClass(NpcEntity.class, new AABB(door).inflate(1.0), NpcEntity::isAlive).isEmpty()) {
            return;
        }
        double runner = nearestRunner(ctx, building, centre);
        if (runner <= RUNNER_NEAR && runner < nearestMonster(ctx, centre)) return;
        RouteDoorHandler.shutDoorAt(level, door, ctx.npc());
    }

    /** How far the nearest other villager running to {@code building} is from its door; MAX_VALUE for none. */
    private static double nearestRunner(Activity.Context ctx, Building building, Vec3 door) {
        double best = Double.MAX_VALUE;
        for (VillagerModel other : ctx.mgr().getVillagers()) {
            if (other == ctx.model()) continue;
            NpcEntity npc = other.puppet();
            if (npc == null || !npc.isAlive()) continue;
            Activity activity = other.brain().currentActivity();
            boolean coming = activity instanceof FleeActivity flee && flee.isRunningTo(building)
                    || activity instanceof ShelterActivity shelter && shelter.isHeadingInto(building, npc);
            if (coming) best = Math.min(best, npc.position().distanceTo(door));
        }
        return best;
    }

    /** How far the nearest monster is from the door, up to {@link #MONSTER_RACE}; MAX_VALUE for none. */
    private static double nearestMonster(Activity.Context ctx, Vec3 door) {
        double best = Double.MAX_VALUE;
        AABB box = new AABB(door, door).inflate(MONSTER_RACE);
        for (Mob mob : ctx.level().getEntitiesOfClass(Mob.class, box,
                mob -> mob.isAlive() && GuardThreatRules.isHostileMonster(ctx.mgr(), mob))) {
            best = Math.min(best, mob.position().distanceTo(door));
        }
        return best;
    }
}
