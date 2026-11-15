package com.alex.test.village.villagers.archer.behavior.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Where to point the bow so the arrow lands on a mob: it leads a mob that is
 * walking (aims where it will be when the arrow gets there) and lifts the
 * shot for the arrow's real drop, from below, level or high up on a wall.
 *
 * <p>Arrow flight is vanilla's, step by step: each tick the arrow moves by its
 * velocity, then loses 1% of it to drag, then falls 0.05 faster. The old aim
 * was the skeleton's rule of thumb (aim a fifth of the distance high), which
 * only holds on level ground and never led a moving target.
 */
public final class ArcherAim {

    private static final double DRAG = 0.99D;
    private static final double GRAVITY = 0.05D;
    private static final int MAX_FLIGHT_TICKS = 100;
    private static final double MIN_PITCH = Math.toRadians(-80.0D);
    /** The flat shot; above 45 degrees an arrow only gets shorter. */
    private static final double MAX_PITCH = Math.toRadians(45.0D);
    private static final int SEARCH_STEPS = 14;
    private static final int LEAD_PASSES = 2;

    private ArcherAim() {}

    /** Direction to shoot from {@code from} at {@code speed} blocks per tick. */
    public static Vec3 direction(Vec3 from, LivingEntity target, float speed) {
        return direction(from, new Vec3(target.getX(), target.getY(0.5D), target.getZ()),
                target.getDeltaMovement(), speed);
    }

    /** As above, at a fixed point (a practice target) moving by {@code walk} per tick. */
    public static Vec3 direction(Vec3 from, Vec3 aimPoint, Vec3 walk, float speed) {
        Vec3 point = aimPoint;
        double pitch = 0.0D;
        for (int pass = 0; pass < LEAD_PASSES; pass++) {
            double horizontal = horizontal(from, point);
            pitch = solvePitch(horizontal, point.y - from.y, speed);
            double ticks = flightTicks(horizontal, pitch, speed);
            // Horizontal lead only: a mob's own falling and hopping is not worth chasing.
            point = aimPoint.add(walk.x * ticks, 0.0D, walk.z * ticks);
        }
        double dx = point.x - from.x;
        double dz = point.z - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 1.0E-6) return new Vec3(0.0D, point.y >= from.y ? 1.0D : -1.0D, 0.0D);
        double cos = Math.cos(pitch);
        return new Vec3(dx / flat * cos, Math.sin(pitch), dz / flat * cos);
    }

    private static double horizontal(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * The flattest pitch whose arrow is at height {@code rise} when it has
     * travelled {@code horizontal} blocks; 45 degrees if nothing reaches.
     */
    private static double solvePitch(double horizontal, double rise, float speed) {
        double low = MIN_PITCH;
        double high = MAX_PITCH;
        if (heightAt(horizontal, high, speed) < rise) return high;
        for (int i = 0; i < SEARCH_STEPS; i++) {
            double mid = (low + high) * 0.5D;
            if (heightAt(horizontal, mid, speed) < rise) low = mid;
            else high = mid;
        }
        return (low + high) * 0.5D;
    }

    /** Arrow height when it has flown {@code horizontal} blocks, or -infinity if it falls short. */
    private static double heightAt(double horizontal, double pitch, float speed) {
        double vx = Math.cos(pitch) * speed;
        double vy = Math.sin(pitch) * speed;
        double x = 0.0D;
        double y = 0.0D;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            double nx = x + vx;
            double ny = y + vy;
            if (nx >= horizontal) {
                double f = vx > 1.0E-9 ? (horizontal - x) / vx : 0.0D;
                return y + (ny - y) * f;
            }
            x = nx;
            y = ny;
            vx *= DRAG;
            vy = vy * DRAG - GRAVITY;
        }
        return Double.NEGATIVE_INFINITY;
    }

    /** Ticks to fly {@code horizontal} blocks at {@code pitch}. */
    private static double flightTicks(double horizontal, double pitch, float speed) {
        double vx = Math.cos(pitch) * speed;
        double x = 0.0D;
        for (int tick = 0; tick < MAX_FLIGHT_TICKS; tick++) {
            if (x + vx >= horizontal) return tick + (vx > 1.0E-9 ? (horizontal - x) / vx : 0.0D);
            x += vx;
            vx *= DRAG;
        }
        return MAX_FLIGHT_TICKS;
    }
}
