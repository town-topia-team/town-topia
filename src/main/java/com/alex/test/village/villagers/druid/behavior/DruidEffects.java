package com.alex.test.village.villagers.druid.behavior;

import com.alex.test.village.villagers.druid.DruidBalance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * What the druid's magic looks like. Purely visual: nothing here changes what
 * the druid grows or how long it takes.
 *
 * <p>While it dances, motes travel from its hands into the crop, sapling or
 * mine it is working on, and the stream gets fuller with its band: a novice
 * makes a thin thread, from the third band a glyph now and then flies in as
 * well, and from the fourth its hands glow. Each finished step ends in a
 * burst that fits the job. All of it is sent as ordinary particles, so a
 * player on Decreased or Minimal particles sees fewer, as with any other.
 */
final class DruidEffects {

    /** Ticks between two motes of the stream. */
    private static final int BEAT_TICKS = 5;
    /** Beats a mote takes to travel from the hands to the target. */
    private static final int BEATS_PER_TRIP = 4;
    /** Ticks between the slower extras: glyph, hand glow, petals and dust. */
    private static final int ACCENT_TICKS = 20;

    private static final DustParticleOptions LEAF_GREEN = dust(0.35f, 0.85f, 0.3f, 0.7f);
    private static final DustParticleOptions BLOSSOM_PINK = dust(1.0f, 0.7f, 0.85f, 0.7f);
    private static final DustParticleOptions STONE_GREY = dust(0.62f, 0.62f, 0.6f, 0.7f);
    private static final DustParticleOptions GOLD = dust(1.0f, 0.82f, 0.2f, 1.0f);
    private static final DustParticleOptions DIAMOND_WHITE = dust(0.8f, 1.0f, 1.0f, 1.0f);
    private static final DustParticleOptions EMERALD_GREEN = dust(0.2f, 1.0f, 0.4f, 1.0f);
    private static final DustParticleOptions DEBRIS_RED = dust(0.55f, 0.12f, 0.1f, 1.0f);
    private static final BlockParticleOption STONE_DUST =
            new BlockParticleOption(ParticleTypes.FALLING_DUST, Blocks.STONE.defaultBlockState());

    enum Kind { CROP, SAPLING, MINE }

    private DruidEffects() {}

    private static DustParticleOptions dust(float r, float g, float b, float size) {
        return new DustParticleOptions(new Vector3f(r, g, b), size);
    }

    /** A short step the way the druid faces, level with the ground. */
    private static Vec3 forward(LivingEntity druid) {
        Vec3 look = druid.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0, look.z);
        return flat.lengthSqr() > 1.0e-4 ? flat.normalize().scale(0.3) : Vec3.ZERO;
    }

    // =========================================================================
    // While dancing
    // =========================================================================

    /**
     * One tick of a dance. Most ticks send nothing; the beat is offset by the
     * druid's id so two druids side by side do not pulse together.
     */
    static void danceTick(ServerLevel level, LivingEntity druid, Kind kind, Vec3 target, int skillLevel) {
        long tick = level.getGameTime() + druid.getId();
        if (tick % BEAT_TICKS != 0) return;
        int band = DruidBalance.band(skillLevel);
        Vec3 ahead = forward(druid);
        // Roughly where the hands are: chest height, a little in front.
        Vec3 from = druid.position().add(ahead.x, 1.15, ahead.z);
        // The druid stands on the crop it tends; aimed at the crop's middle
        // the stream would end inside its legs, so aim at the part in front.
        if (kind == Kind.CROP) target = target.add(ahead);
        Vec3 path = target.subtract(from);

        // The stream: each beat a mote a quarter further along, so over four
        // beats one travels the whole way in. More motes per beat with skill.
        long beat = tick / BEAT_TICKS;
        double along = (beat % BEATS_PER_TRIP + 0.5) / BEATS_PER_TRIP;
        Vec3 at = from.add(path.scale(along));
        int motes = band <= 2 ? 1 : band <= 4 ? 2 : 3;
        DustParticleOptions colour = switch (kind) {
            case CROP -> LEAF_GREEN;
            case SAPLING -> beat % 2 == 0 ? LEAF_GREEN : BLOSSOM_PINK;
            case MINE -> STONE_GREY;
        };
        level.sendParticles(colour, at.x, at.y, at.z, motes, 0.06, 0.06, 0.06, 0.0);

        if (tick % ACCENT_TICKS != 0) return;
        accent(level, kind, target);
        if (band >= 3) {
            // A glyph flying the whole way in. With a count of zero the
            // offset is the glyph's start relative to where it ends; it dips
            // 1.2 blocks on the way, so it is aimed that much higher.
            double endY = target.y + 1.2;
            level.sendParticles(ParticleTypes.ENCHANT, target.x, endY, target.z, 0,
                    from.x - target.x, from.y - endY, from.z - target.z, 1.0);
        }
        if (band >= 4) {
            // A soft light at the hands, drifting up.
            level.sendParticles(ParticleTypes.END_ROD, from.x, from.y, from.z, 0,
                    0.0, 1.0, 0.0, 0.01);
        }
    }

    /** The slow extra at the target itself, once a second. */
    private static void accent(ServerLevel level, Kind kind, Vec3 target) {
        switch (kind) {
            case CROP -> level.sendParticles(ParticleTypes.COMPOSTER,
                    target.x, target.y + 0.2, target.z, 1, 0.2, 0.1, 0.2, 0.0);
            case SAPLING -> level.sendParticles(ParticleTypes.CHERRY_LEAVES,
                    target.x, target.y + 1.2, target.z, 1, 0.6, 0.3, 0.6, 0.0);
            case MINE -> level.sendParticles(STONE_DUST,
                    target.x, target.y + 0.8, target.z, 2, 0.3, 0.1, 0.3, 0.0);
        }
    }

    // =========================================================================
    // When a step finishes
    // =========================================================================

    /** A crop grew a stage: the bone meal sparkle and a few compost bits. */
    static void cropGrown(ServerLevel level, BlockPos tile, boolean twice) {
        double x = tile.getX() + 0.5, y = tile.getY() + 0.5, z = tile.getZ() + 0.5;
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, x, y, z, 6, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.COMPOSTER, x, y, z, 4, 0.3, 0.2, 0.3, 0.0);
        if (twice) doubleStep(level, new Vec3(x, tile.getY() + 0.2, z));
    }

    /** A sapling grew a stage but is not a tree yet. */
    static void saplingStaged(ServerLevel level, Vec3 centre) {
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                centre.x, centre.y + 0.2, centre.z, 8, 0.4, 0.3, 0.4, 0.0);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES,
                centre.x, centre.y + 0.8, centre.z, 3, 0.4, 0.3, 0.4, 0.0);
    }

    /**
     * The sapling became a tree: sparkles up the trunk, petals from the crown.
     * Its sound started a moment before ({@link DruidSounds#treeFinishCue}).
     */
    static void treeGrown(ServerLevel level, Vec3 centre, boolean twoByTwo) {
        double spread = twoByTwo ? 0.8 : 0.4;
        for (int up = 0; up <= 5; up++) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    centre.x, centre.y + up, centre.z, 3, spread, 0.3, spread, 0.0);
        }
        level.sendParticles(ParticleTypes.COMPOSTER,
                centre.x, centre.y + 0.3, centre.z, 6, spread, 0.2, spread, 0.0);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES,
                centre.x, centre.y + 5.0, centre.z, 12, spread + 1.0, 0.8, spread + 1.0, 0.0);
    }

    /** A mine block grew back: it crumbles in, and good ore gets a sparkle of its colour. */
    static void mineBlockGrown(ServerLevel level, BlockPos pos, BlockState grown) {
        double x = pos.getX() + 0.5, y = pos.getY() + 0.5, z = pos.getZ() + 0.5;
        if (!grown.isAir()) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, grown),
                    x, y, z, 10, 0.3, 0.3, 0.3, 0.0);
        }
        DustParticleOptions sparkle = oreSparkle(grown);
        if (sparkle != null) {
            level.sendParticles(sparkle, x, y, z, 8, 0.4, 0.4, 0.4, 0.0);
            level.sendParticles(ParticleTypes.END_ROD, x, y, z, 2, 0.3, 0.3, 0.3, 0.02);
        }
    }

    @Nullable
    private static DustParticleOptions oreSparkle(BlockState state) {
        if (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE)) return DIAMOND_WHITE;
        if (state.is(Blocks.EMERALD_ORE) || state.is(Blocks.DEEPSLATE_EMERALD_ORE)) return EMERALD_GREEN;
        if (state.is(Blocks.ANCIENT_DEBRIS)) return DEBRIS_RED;
        return null;
    }

    /** A master's rare two-at-once step: a ring of gold and a few bright flecks. */
    static void doubleStep(ServerLevel level, Vec3 at) {
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4.0;
            level.sendParticles(GOLD, at.x + Math.cos(angle) * 0.7, at.y, at.z + Math.sin(angle) * 0.7,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, at.x, at.y + 0.5, at.z, 6, 0.2, 0.2, 0.2, 0.15);
    }
}
