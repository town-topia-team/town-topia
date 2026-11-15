package com.alex.test.village.chat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A few seconds of sparkles after a token is checked, so the player sees what
 * the check looked at: green where it found what it wanted (a tower's floor,
 * the spots range archers stand on), red where something is wrong (the token
 * with no floor beside it, the ground a too-low tower was measured from, a
 * blocked gate). One show per village at a time; a new one replaces it.
 */
public final class PlacementSparkles {

    private static final int SHOW_TICKS = 160;
    private static final int EVERY_TICKS = 10;
    private static final int MAX_SPOTS = 64;
    /** Players this close to the first spot see the show. */
    private static final double VIEW_RANGE = 48.0D;
    private static final DustParticleOptions RED = new DustParticleOptions(new Vector3f(1f, 0.1f, 0.1f), 1.5f);

    private record Spot(Vec3 at, boolean good) {}

    private final List<Spot> spots = new ArrayList<>();
    private long until;

    /** Starts a show: green at {@code good}, red at {@code bad}. Either may be empty. */
    public void show(long now, Collection<Vec3> good, Collection<Vec3> bad) {
        spots.clear();
        for (Vec3 at : bad) {
            if (spots.size() >= MAX_SPOTS) break;
            spots.add(new Spot(at, false));
        }
        for (Vec3 at : good) {
            if (spots.size() >= MAX_SPOTS) break;
            spots.add(new Spot(at, true));
        }
        until = now + SHOW_TICKS;
    }

    public void tick(ServerLevel level) {
        if (spots.isEmpty()) return;
        long now = level.getGameTime();
        if (now >= until || until - now > SHOW_TICKS) {
            spots.clear();
            return;
        }
        if (now % EVERY_TICKS != 0) return;
        Vec3 centre = spots.get(0).at();
        for (ServerPlayer player : level.players()) {
            if (player.position().distanceToSqr(centre) > VIEW_RANGE * VIEW_RANGE) continue;
            for (Spot spot : spots) {
                Vec3 at = spot.at();
                if (spot.good()) {
                    level.sendParticles(player, ParticleTypes.HAPPY_VILLAGER, true,
                            at.x, at.y, at.z, 2, 0.2D, 0.1D, 0.2D, 0.0D);
                } else {
                    level.sendParticles(player, RED, true, at.x, at.y, at.z, 3, 0.15D, 0.15D, 0.15D, 0.0D);
                }
            }
        }
    }

    /** Just above a floor tile (a feet position). */
    public static Vec3 onFloor(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5D, feet.getY() + 0.2D, feet.getZ() + 0.5D);
    }

    /** In the middle of a block (the token's frame, a gate). */
    public static Vec3 inBlock(BlockPos pos) {
        return Vec3.atCenterOf(pos);
    }

    public static List<Vec3> onFloors(Collection<BlockPos> feet) {
        List<Vec3> out = new ArrayList<>(feet.size());
        for (BlockPos pos : feet) out.add(onFloor(pos));
        return out;
    }
}
