package com.alex.test.village.raid.warlord;

import com.alex.test.ModSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What the Warlord's heavy moves look and sound like: dust, thuds, a shaking
 * door. Shared by its fighting goals and the showcase. Never changes a block.
 */
public final class WarlordEffects {

    /** Where the spin clip holds the blade: this far out from its middle and this high (blocks). */
    private static final double SPIN_MARK_OUT = 3.3;
    private static final double SPIN_MARK_UP = 1.4;

    private WarlordEffects() {}

    /** The axe hits the ground: a ring of dust from the block under it, out to the slam's reach, and the boom. */
    public static void slam(ServerLevel level, Entity warlord) {
        BlockState ground = groundUnder(level, warlord);
        for (int i = 0; i < 48; i++) {
            double angle = i * Math.PI * 2 / 48;
            double reach = 1.0 + level.random.nextDouble() * (WarlordMath.SLAM_RADIUS - 1.0);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    warlord.getX() + Math.cos(angle) * reach, warlord.getY() + 0.1, warlord.getZ() + Math.sin(angle) * reach,
                    3, 0.2, 0.1, 0.2, 0.15);
        }
        level.sendParticles(ParticleTypes.EXPLOSION, warlord.getX(), warlord.getY() + 0.2, warlord.getZ(), 1, 0, 0, 0, 0);
        level.playSound(null, warlord.getX(), warlord.getY(), warlord.getZ(), ModSounds.RAID_WARLORD_SLAM.get(),
                SoundSource.HOSTILE, 3.0F, 1.0F);
    }

    /** The spinning axe passes {@code bearing} degrees right of {@code yaw}'s front: a sweep mark where the blade is. */
    public static void spinSweep(ServerLevel level, Entity warlord, float yaw, double bearing) {
        double b = Math.toRadians(bearing);
        Vec3 out = RaidWarlord.forward(yaw).scale(Math.cos(b)).add(RaidWarlord.right(yaw).scale(Math.sin(b)));
        Vec3 at = warlord.position().add(out.scale(SPIN_MARK_OUT)).add(0, SPIN_MARK_UP, 0);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, at.y, at.z, 1, 0, 0, 0, 0);
    }

    /** It runs into a wall: grit off the wall, a heavy thud. */
    public static void wallBump(ServerLevel level, Entity warlord, BlockPos wall) {
        BlockState state = level.getBlockState(wall);
        if (!state.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    wall.getX() + 0.5, wall.getY() + 1.0, wall.getZ() + 0.5, 30, 0.6, 0.8, 0.6, 0.2);
        }
        level.playSound(null, warlord.getX(), warlord.getY(), warlord.getZ(), ModSounds.RAID_WARLORD_SLAM.get(),
                SoundSource.HOSTILE, 1.6F, 0.8F);
    }

    /** It comes down from a leap: dust and a lighter thud. */
    public static void landing(ServerLevel level, Entity warlord) {
        level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, groundUnder(level, warlord)),
                warlord.getX(), warlord.getY() + 0.1, warlord.getZ(), 40, 1.0, 0.1, 1.0, 0.15);
        level.playSound(null, warlord.getX(), warlord.getY(), warlord.getZ(), ModSounds.RAID_WARLORD_SLAM.get(),
                SoundSource.HOSTILE, 1.2F, 1.25F);
    }

    /**
     * The first of the two fist blows on a door: the knocking (one sound holds both
     * blows) and splinters. The door is not touched.
     */
    public static void poundDoor(ServerLevel level, BlockPos door) {
        doorDust(level, door);
        level.playSound(null, door.getX() + 0.5, door.getY() + 1.0, door.getZ() + 0.5, ModSounds.RAID_WARLORD_POUND.get(),
                SoundSource.HOSTILE, 2.0F, 1.0F);
    }

    /** Splinters shaken off a door (the second blow of a pound). */
    public static void doorDust(ServerLevel level, BlockPos door) {
        BlockState state = level.getBlockState(door);
        if (!state.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state),
                    door.getX() + 0.5, door.getY() + 1.0, door.getZ() + 0.5, 18, 0.3, 0.6, 0.3, 0.1);
        }
    }

    private static BlockState groundUnder(ServerLevel level, Entity entity) {
        BlockState state = level.getBlockState(entity.blockPosition().below());
        return state.isAir() ? level.getBlockState(entity.blockPosition().below(2)) : state;
    }
}
