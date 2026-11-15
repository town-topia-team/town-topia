package com.alex.test.village.villagers.guard.behavior.patrol;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import net.minecraft.world.phys.Vec3;

/**
 * A guard at its post now and then stops and looks around (Mixamo's
 * "Looking", mixamo_look_around). The clip turns the head on screen; this keeps
 * the guard's real view direction ({@code yHeadRot} and pitch, what
 * {@code getViewVector} and anything with a view cone reads) on that head the
 * whole way through, while the body stays facing where it stood.
 *
 * <p>The tables are the head's direction in the shipped base_villager clip,
 * one value per tick from the clip's first frame, measured by forward
 * kinematics on the finished clip (tools/mocap, 10-08). Yaw is degrees to the
 * guard's right (negative = left), pitch degrees up (negative = down).
 */
final class GuardLookAround {

    /** Chance, on reaching each patrol spot by the post, that the guard looks around there. */
    static final float CHANCE = 0.3f;

    /** How far ahead the look target is put; only its direction matters. */
    private static final double LOOK_DISTANCE = 8.0D;

    private boolean active;
    private int tick;
    private float baseYaw;

    boolean isActive() {
        return active;
    }

    /** Starts the clip, facing the way the guard faces now. */
    void start(NpcEntity npc) {
        active = true;
        tick = 0;
        baseYaw = npc.getYRot();
        npc.setAnimation(VillagerAnimationState.LOOK_AROUND);
        apply(npc);
    }

    /** Advances one tick; false once the clip has finished or something else took the guard's animation. */
    boolean tick(NpcEntity npc) {
        if (!active) return false;
        tick++;
        if (tick >= VillagerAnimationState.LOOK_AROUND_TICKS || !npc.isLookingAround()) {
            stop(npc);
            return false;
        }
        apply(npc);
        return true;
    }

    /** Ends the look early (leaving the post, a fight); the head turns back to the front. */
    void stop(NpcEntity npc) {
        if (!active) return;
        if (npc.isLookingAround()) npc.setAnimation(VillagerAnimationState.IDLE);
        npc.setYHeadRot(baseYaw);
        npc.setXRot(0f);
        active = false;
    }

    private void apply(NpcEntity npc) {
        float[] yaws = LONG_YAW;
        float[] pitches = LONG_PITCH;
        // The gesture controller blends in over GESTURE_BLEND_TICKS before frame 0.
        int frame = tick - VillagerAnimationState.GESTURE_BLEND_TICKS;
        float yaw;
        float pitch;
        if (frame < 0) {
            float in = (float) tick / VillagerAnimationState.GESTURE_BLEND_TICKS;
            yaw = yaws[0] * in;
            pitch = pitches[0] * in;
        } else {
            int i = Math.min(frame, yaws.length - 1);
            yaw = yaws[i];
            pitch = pitches[i];
        }

        float headYaw = baseYaw + yaw;   // Minecraft yaw grows turning right
        float xRot = -pitch;             // and pitch grows looking down
        npc.setYRot(baseYaw);
        npc.setYBodyRot(baseYaw);
        npc.setYHeadRot(headYaw);
        npc.setXRot(xRot);
        Vec3 eye = npc.getEyePosition();
        Vec3 look = eye.add(Vec3.directionFromRotation(xRot, headYaw).scale(LOOK_DISTANCE));
        npc.getLookControl().setLookAt(look.x, look.y, look.z, 90f, 90f);
    }

    private static final float[] LONG_YAW = {
            1.6f, 1.4f, 1.2f, 0.8f, 0.0f, -0.9f, -2.4f, -4.4f, -6.4f, -8.4f, -10.2f, -11.7f,
            -12.9f, -14.0f, -15.0f, -16.1f, -17.1f, -18.1f, -19.7f, -22.0f, -25.3f, -29.0f, -32.7f, -35.5f,
            -37.4f, -38.6f, -39.1f, -39.7f, -40.0f, -40.1f, -40.3f, -40.4f, -40.6f, -40.7f, -40.9f, -41.0f,
            -41.0f, -41.1f, -41.2f, -41.3f, -41.4f, -41.4f, -41.2f, -41.1f, -41.0f, -40.6f, -39.9f, -39.2f,
            -38.5f, -37.2f, -36.0f, -34.1f, -31.9f, -29.7f, -27.4f, -25.2f, -23.4f, -21.8f, -20.1f, -18.8f,
            -17.9f, -16.9f, -16.2f, -15.6f, -15.0f, -14.4f, -13.8f, -12.6f, -11.4f, -9.7f, -7.7f, -5.6f,
            -3.5f, -1.5f, 0.4f, 2.4f, 4.3f, 6.2f, 8.2f, 9.8f, 11.5f, 12.9f, 14.0f, 15.0f,
            15.8f, 16.6f, 17.3f, 18.1f, 18.8f, 19.6f, 20.6f, 21.8f, 23.5f, 25.6f, 28.0f, 30.6f,
            33.1f, 35.7f, 38.2f, 40.2f, 42.1f, 43.6f, 45.1f, 46.3f, 47.4f, 48.1f, 48.6f, 48.8f,
            49.1f, 49.2f, 49.0f, 48.9f, 48.6f, 48.3f, 47.8f, 47.3f, 46.6f, 45.8f, 45.0f, 44.1f,
            43.1f, 42.2f, 41.3f, 40.3f, 39.5f, 38.6f, 37.8f, 36.5f, 34.8f, 32.5f, 29.6f, 25.8f,
            21.9f, 18.2f, 14.6f, 11.7f, 9.4f, 7.3f, 5.8f, 4.6f, 3.4f, 2.9f, 2.4f, 1.9f,
            1.7f, 1.6f, 1.6f, 1.5f, 1.5f, 1.4f, 1.5f, 1.5f, 1.5f, 1.6f, 1.6f, 1.6f,
            1.6f, 1.6f, 1.6f, 1.6f, 1.6f,
    };
    private static final float[] LONG_PITCH = {
            1.1f, 1.0f, 0.9f, 0.8f, 0.5f, 0.2f, -0.3f, -1.1f, -1.8f, -2.5f, -3.1f, -3.7f,
            -4.2f, -4.6f, -4.8f, -5.0f, -5.1f, -5.3f, -5.3f, -5.3f, -5.5f, -5.7f, -5.8f, -6.1f,
            -6.5f, -6.8f, -7.1f, -7.3f, -7.6f, -7.8f, -8.1f, -8.4f, -8.7f, -8.9f, -9.2f, -9.6f,
            -10.1f, -10.5f, -11.0f, -11.4f, -11.9f, -12.3f, -12.6f, -13.0f, -13.3f, -13.6f, -13.9f, -14.2f,
            -14.4f, -14.8f, -15.1f, -15.5f, -15.8f, -16.0f, -16.3f, -16.4f, -16.6f, -16.8f, -16.9f, -17.0f,
            -17.1f, -17.2f, -17.2f, -17.3f, -17.4f, -17.4f, -17.5f, -17.6f, -17.7f, -17.8f, -18.0f, -18.2f,
            -18.3f, -18.4f, -18.6f, -18.7f, -18.9f, -19.0f, -19.1f, -19.3f, -19.4f, -19.5f, -19.6f, -19.6f,
            -19.7f, -19.7f, -19.8f, -19.8f, -19.9f, -19.9f, -20.0f, -20.0f, -20.1f, -20.2f, -20.3f, -20.4f,
            -20.4f, -20.4f, -20.4f, -20.3f, -20.2f, -20.1f, -20.1f, -20.1f, -20.1f, -20.1f, -20.1f, -20.1f,
            -20.1f, -20.0f, -20.0f, -19.9f, -19.8f, -19.6f, -19.4f, -19.1f, -18.8f, -18.5f, -18.1f, -17.6f,
            -17.1f, -16.5f, -16.0f, -15.3f, -14.3f, -13.2f, -12.1f, -11.0f, -9.9f, -8.8f, -7.7f, -6.8f,
            -5.8f, -4.8f, -4.1f, -3.5f, -3.0f, -2.5f, -2.1f, -1.8f, -1.4f, -1.1f, -0.7f, -0.3f,
            0.0f, 0.3f, 0.6f, 0.9f, 1.2f, 1.4f, 1.5f, 1.5f, 1.6f, 1.6f, 1.7f, 1.7f,
            1.6f, 1.4f, 1.3f, 1.2f, 1.1f,
    };
}
