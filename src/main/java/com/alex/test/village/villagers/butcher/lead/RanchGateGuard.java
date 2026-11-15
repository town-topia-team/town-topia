package com.alex.test.village.villagers.butcher.lead;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * While a ranch gate stands open, no animal gets out but the one on a lead.
 *
 * <p>Every 2 ticks each pen checks its gate: closed, and that one block
 * lookup is all it costs. Open (a villager leading an animal through, or a
 * player who left it), every pen animal near the gate is pushed back toward
 * the middle of the pen, and one already standing in the gateway on its way
 * out is put back on the tile inside. The led animal, animals on a
 * player's lead and saddled ones (a player's ride) are left alone. A gate a
 * player opened is never closed here; it is only guarded.
 */
public final class RanchGateGuard {

    private static final int EVERY_TICKS = 2;
    /** Animals this close to the gate are checked. */
    private static final double AREA = 3.0;
    /** Pen animals this close to the gate are pushed back. */
    private static final double PUSH_RANGE = 2.0;
    private static final double PUSH = 0.12;
    private static final double MAX_SPEED = 0.3;
    /** An animal in the gateway counts as leaving if it was in the pen this recently. */
    private static final long LEAVING_TICKS = 100L;

    private RanchGateGuard() {}

    public static void tick(ServerLevel level) {
        if (level.getGameTime() % EVERY_TICKS != 0) return;
        for (VillageManager mgr : VillageManagerRegistry.get(level.getServer()).all()) {
            if (mgr.getLevel() != level) continue;
            for (Building building : mgr.getBuildings()) {
                if (building instanceof RanchBuilding ranch && ranch.isValid()) guard(level, ranch);
            }
        }
    }

    private static void guard(ServerLevel level, RanchBuilding ranch) {
        BlockPos gate = ranch.getGatePos();
        if (!level.isLoaded(gate)) return;
        BlockState state = level.getBlockState(gate);
        if (!(state.getBlock() instanceof FenceGateBlock) || !state.getValue(BlockStateProperties.OPEN)) return;

        ranch.animalsInside(level); // who is in the pen right now
        long now = level.getGameTime();
        Direction inward = ranch.getInteriorDirection();
        Vec3 gateCenter = gate.getBottomCenter();
        AABB gateBox = new AABB(gate);
        for (Animal animal : level.getEntitiesOfClass(Animal.class, new AABB(gate).inflate(AREA), Entity::isAlive)) {
            if (LedAnimals.isLed(animal) || animal.isLeashed() || RanchStrays.isSaddled(animal)) continue;
            boolean inGateway = animal.getBoundingBox().intersects(gateBox);
            if (inGateway) {
                if (!ranch.seenInsideWithin(animal.getUUID(), now, LEAVING_TICKS)) continue; // coming in, not out
                push(animal, inward);
                putBackInside(level, ranch, animal, gate.relative(inward));
                continue;
            }
            boolean onPen = ranch.isPenTile(level, animal.blockPosition());
            if (onPen && animal.position().distanceTo(gateCenter) <= PUSH_RANGE + 0.5) push(animal, inward);
        }
    }

    /** A nudge toward the middle of the pen, its own walking stopped. */
    private static void push(Animal animal, Direction inward) {
        animal.getNavigation().stop();
        Vec3 v = animal.getDeltaMovement().add(inward.getStepX() * PUSH, 0, inward.getStepZ() * PUSH);
        double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
        if (horizontal > MAX_SPEED) {
            double scale = MAX_SPEED / horizontal;
            v = new Vec3(v.x * scale, v.y, v.z * scale);
        }
        animal.setDeltaMovement(v);
        animal.hurtMarked = true; // sends the new motion to players at once
    }

    /** Back onto the tile inside the gate, only if it fits there. */
    private static void putBackInside(ServerLevel level, RanchBuilding ranch, Animal animal, BlockPos inside) {
        if (!ranch.getNavMeshBlocks().contains(inside)) return;
        Vec3 to = Vec3.atBottomCenterOf(inside);
        // A chunk still loading has no walls to stop the animal (FarAwake): not moved then.
        if (!com.alex.test.village.farawake.FarAwake.readyFor(level, to.x, to.z)) return;
        AABB box = animal.getBoundingBox().move(to.subtract(animal.position()));
        if (!level.noCollision(animal, box)) return;
        animal.moveTo(to.x, to.y, to.z, animal.getYRot(), animal.getXRot());
    }
}
