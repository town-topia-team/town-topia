package com.alex.test.entity;

import com.alex.test.DynamicVillages;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The mage's Arcane Bolt: a small purple light flicked from the mage's bare
 * hand that curves after one mob and bursts on it. It is drawn with
 * particles only -- nothing renders the entity itself (a NoopRenderer) --
 * so it reads as magic, not a thrown thing.
 *
 * <p>Like the cleric's healing orb it is locked onto its mob: it flies
 * through blocks and through everyone else, and can only ever hit the mob it
 * was cast at. The mage casts only at hostile mobs it has a clear view of, so
 * a villager, player, pet or golem stepping into the line is never hurt. It
 * never saves: a bolt in flight when its chunk unloads is gone.
 */
public final class ArcaneBoltEntity extends Projectile {

    /** Blocks per tick: 16 blocks in under a second. */
    private static final double SPEED = 0.85;
    /** How strongly it turns after a moving mob each tick (0..1). */
    private static final double HOMING = 0.3;
    /** Lands this close to the middle of its mob. */
    private static final double HIT_DIST = 0.6;
    /** A bolt that has not landed by now fizzles out. */
    private static final int MAX_LIFE_TICKS = 60;

    private static final Vector3f TRAIL_PURPLE = new Vector3f(0.72f, 0.38f, 1.0f);
    /** The bolt's bright heart, where a sprite used to be drawn. */
    private static final Vector3f CORE_PALE = new Vector3f(0.93f, 0.85f, 1.0f);

    /** What happened when a bolt landed, for the mage that cast it. */
    public record Landing(LivingEntity target, boolean killed) {}

    @Nullable private UUID targetId;
    private float damage;
    @Nullable private Consumer<Landing> onLanded;
    private int life;

    public ArcaneBoltEntity(EntityType<? extends ArcaneBoltEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Casts a bolt from {@code from} at {@code target}.
     *
     * @param onLanded told what the bolt did when it lands; never called if it fizzles
     */
    public static ArcaneBoltEntity castAt(ServerLevel level, LivingEntity caster, Vec3 from,
                                          LivingEntity target, float damage,
                                          @Nullable Consumer<Landing> onLanded) {
        ArcaneBoltEntity bolt = new ArcaneBoltEntity(DynamicVillages.ARCANE_BOLT.get(), level);
        bolt.setOwner(caster);
        bolt.setPos(from.x, from.y, from.z);
        bolt.targetId = target.getUUID();
        bolt.damage = damage;
        bolt.onLanded = onLanded;
        Vec3 to = aimPoint(target).subtract(from);
        Vec3 start = to.lengthSqr() < 1e-6 ? new Vec3(0, SPEED, 0) : to.normalize().scale(SPEED);
        bolt.setDeltaMovement(start);
        level.addFreshEntity(bolt);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.EVOKER_CAST_SPELL,
                SoundSource.NEUTRAL, 0.6f, 1.5f);
        return bolt;
    }

    private static Vec3 aimPoint(LivingEntity target) {
        return target.position().add(0, target.getBbHeight() * 0.55, 0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            // Carry on along the last velocity the server sent until its next
            // position arrives, so the flight draws smoothly between updates.
            Vec3 from = position();
            Vec3 v = getDeltaMovement();
            setPos(getX() + v.x, getY() + v.y, getZ() + v.z);
            spawnTrail(from, position());
            return;
        }
        if (++life > MAX_LIFE_TICKS || targetId == null) {
            fizzle();
            return;
        }
        Entity found = ((ServerLevel) level()).getEntity(targetId);
        if (!(found instanceof LivingEntity target) || !target.isAlive() || target.isRemoved()) {
            fizzle();
            return;
        }
        Vec3 to = aimPoint(target).subtract(position());
        double dist = to.length();
        if (dist <= Math.max(HIT_DIST, SPEED)) {
            land((ServerLevel) level(), target);
            return;
        }
        Vec3 want = to.scale(SPEED / dist);
        Vec3 velocity = getDeltaMovement().lerp(want, HOMING);
        double speed = velocity.length();
        velocity = speed < 1e-6 ? want : velocity.scale(SPEED / speed);
        setDeltaMovement(velocity);
        setPos(getX() + velocity.x, getY() + velocity.y, getZ() + velocity.z);
    }

    /**
     * Purple dust along the stretch flown this tick, a pale bright core where
     * the bolt is, and a glint now and then: the bolt is nothing but these.
     */
    private void spawnTrail(Vec3 from, Vec3 to) {
        Level level = level();
        level.addParticle(new DustParticleOptions(CORE_PALE, 1.3f), to.x, to.y, to.z, 0, 0, 0);
        level.addParticle(new DustParticleOptions(TRAIL_PURPLE, 1.1f), to.x, to.y, to.z, 0, 0, 0);
        for (int i = 0; i < TRAIL_PUFFS; i++) {
            Vec3 at = from.lerp(to, (i + random.nextDouble()) / TRAIL_PUFFS);
            level.addParticle(new DustParticleOptions(TRAIL_PURPLE, 0.8f),
                    at.x + (random.nextDouble() - 0.5) * 0.1,
                    at.y + (random.nextDouble() - 0.5) * 0.1,
                    at.z + (random.nextDouble() - 0.5) * 0.1, 0, 0, 0);
        }
        if (random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.WITCH, to.x, to.y, to.z, 0, 0, 0);
        }
        if (random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.ENCHANT, to.x, to.y, to.z,
                    (random.nextDouble() - 0.5) * 0.4, (random.nextDouble() - 0.5) * 0.4,
                    (random.nextDouble() - 0.5) * 0.4);
        }
    }

    /** Purple dust puffs laid along each tick of flight. */
    private static final int TRAIL_PUFFS = 5;

    private void land(ServerLevel level, LivingEntity target) {
        Entity owner = getOwner();
        // Inside the mob's hurt cooldown (a guard's swing just landed) the bolt does nothing:
        // then it counts for nothing on the mage's skill either.
        boolean hit = target.hurt(level.damageSources().indirectMagic(this, owner), damage);
        boolean killed = !target.isAlive();
        level.sendParticles(ParticleTypes.WITCH, target.getX(), target.getY(0.55), target.getZ(),
                14, 0.3, 0.4, 0.3, 0.02);
        level.sendParticles(ParticleTypes.ENCHANTED_HIT, target.getX(), target.getY(0.55), target.getZ(),
                8, 0.25, 0.3, 0.25, 0.2);
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, 0.8f, 1.4f);
        if (hit && onLanded != null) onLanded.accept(new Landing(target, killed));
        discard();
    }

    private void fizzle() {
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.WITCH, getX(), getY(), getZ(), 4, 0.12, 0.12, 0.12, 0.0);
        }
        discard();
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeHitByProjectile() {
        return false;
    }
}
