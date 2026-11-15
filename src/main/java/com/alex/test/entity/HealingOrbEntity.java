package com.alex.test.entity;

import com.alex.test.DynamicVillages;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The cleric's healing orb: a small green light thrown from its palm that
 * curves after its patient, heals it on arrival and bursts into hearts.
 *
 * <p>It flies through blocks on purpose. The cleric only throws with a clear
 * line of sight, and a patient that steps behind a post mid-flight should
 * still be healed rather than the orb splashing on the post. It cannot hit
 * anything but its patient, and it never saves: an orb in flight when its
 * chunk unloads is simply gone, along with the callback that pays the cleric.
 */
public final class HealingOrbEntity extends Projectile {

    /** Blocks per tick: 8 blocks in about 15 ticks. */
    private static final double SPEED = 0.55;
    /** How strongly it turns after a moving patient each tick (0..1). */
    private static final double HOMING = 0.35;
    /** Lands this close to the middle of its patient. */
    private static final double HIT_DIST = 0.7;
    /** An orb that has not landed by now fizzles out. */
    private static final int MAX_LIFE_TICKS = 80;

    private static final Vector3f TRAIL_GREEN = new Vector3f(0.45f, 1.0f, 0.55f);

    /** What happened when an orb landed, for the cleric that threw it. */
    public record Landing(LivingEntity patient, float healed, List<LivingEntity> splashed, float splashHealed) {}

    /** How an orb heals, decided by the cleric's skill when it is thrown. */
    public record Blessing(float heal, boolean cleanse, boolean renew, boolean splash,
                           double splashRadius, float splashShare, int renewTicks) {}

    @Nullable private UUID patientId;
    @Nullable private Blessing blessing;
    @Nullable private Predicate<LivingEntity> splashWho;
    @Nullable private Consumer<Landing> onLanded;
    private int life;

    public HealingOrbEntity(EntityType<? extends HealingOrbEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    /**
     * Throws an orb from {@code from} at {@code patient}.
     *
     * @param splashWho who else a splash may heal
     * @param onLanded  told what the orb did when it lands; never called if it fizzles
     */
    public static HealingOrbEntity throwAt(ServerLevel level, LivingEntity caster, Vec3 from,
                                           LivingEntity patient, Blessing blessing,
                                           Predicate<LivingEntity> splashWho,
                                           Consumer<Landing> onLanded) {
        HealingOrbEntity orb = new HealingOrbEntity(DynamicVillages.HEALING_ORB.get(), level);
        orb.setOwner(caster);
        orb.setPos(from.x, from.y, from.z);
        orb.patientId = patient.getUUID();
        orb.blessing = blessing;
        orb.splashWho = splashWho;
        orb.onLanded = onLanded;
        // Leaves the hand toward the patient with a slight lift, so it reads as
        // tossed rather than fired; the homing brings it down onto them. A
        // stronger lift (0.25) made it jump up out of the hand.
        Vec3 to = aimPoint(patient).subtract(from);
        Vec3 start = to.lengthSqr() < 1e-6 ? new Vec3(0, SPEED, 0)
                : to.normalize().add(0, 0.08, 0).normalize().scale(SPEED);
        orb.setDeltaMovement(start);
        level.addFreshEntity(orb);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.ILLUSIONER_CAST_SPELL,
                SoundSource.NEUTRAL, 0.5f, 1.6f);
        return orb;
    }

    private static Vec3 aimPoint(LivingEntity patient) {
        return patient.position().add(0, patient.getBbHeight() * 0.55, 0);
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
        if (++life > MAX_LIFE_TICKS || blessing == null || patientId == null) {
            fizzle();
            return;
        }
        Entity found = ((ServerLevel) level()).getEntity(patientId);
        if (!(found instanceof LivingEntity patient) || !patient.isAlive() || patient.isRemoved()) {
            fizzle();
            return;
        }
        Vec3 to = aimPoint(patient).subtract(position());
        double dist = to.length();
        if (dist <= Math.max(HIT_DIST, SPEED)) {
            land((ServerLevel) level(), patient);
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
     * A green trail along the stretch flown this tick: green dust spread evenly
     * along it (the orb covers half a block a tick, so one puff per tick left
     * gaps), and a green sparkle at the orb itself. The orb is drawn at its own
     * position, so the trail starts right at it.
     */
    private void spawnTrail(Vec3 from, Vec3 to) {
        Level level = level();
        for (int i = 0; i < TRAIL_PUFFS; i++) {
            Vec3 at = from.lerp(to, (i + random.nextDouble()) / TRAIL_PUFFS);
            level.addParticle(new DustParticleOptions(TRAIL_GREEN, 0.9f),
                    at.x + (random.nextDouble() - 0.5) * 0.12,
                    at.y + (random.nextDouble() - 0.5) * 0.12,
                    at.z + (random.nextDouble() - 0.5) * 0.12, 0, 0, 0);
        }
        level.addParticle(ParticleTypes.HAPPY_VILLAGER,
                to.x + (random.nextDouble() - 0.5) * 0.2, to.y + (random.nextDouble() - 0.5) * 0.2,
                to.z + (random.nextDouble() - 0.5) * 0.2, 0, 0, 0);
    }

    /** Green dust puffs laid along each tick of flight. */
    private static final int TRAIL_PUFFS = 4;

    private void land(ServerLevel level, LivingEntity patient) {
        Blessing b = blessing;
        float healed = blessOne(patient, b.heal(), b);
        List<LivingEntity> splashed = new ArrayList<>();
        float splashHealed = 0f;
        if (b.splash() && splashWho != null) {
            AABB box = patient.getBoundingBox().inflate(b.splashRadius());
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e != patient && e.isAlive() && splashWho.test(e))) {
                float h = blessOne(other, b.heal() * b.splashShare(), b);
                if (h > 0f || b.cleanse()) splashed.add(other);
                splashHealed += h;
                level.sendParticles(ParticleTypes.HEART, other.getX(), other.getY(1.0), other.getZ(),
                        2, 0.25, 0.2, 0.25, 0.0);
            }
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY(0.5), patient.getZ(),
                    24, b.splashRadius() * 0.5, 0.4, b.splashRadius() * 0.5, 0.0);
        }
        level.sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY(1.0), patient.getZ(),
                6, 0.3, 0.3, 0.3, 0.0);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, patient.getX(), patient.getY(0.5), patient.getZ(),
                12, 0.35, 0.5, 0.35, 0.0);
        level.playSound(null, patient.getX(), patient.getY(), patient.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2f, 1.3f);
        if (onLanded != null) onLanded.accept(new Landing(patient, healed, splashed, splashHealed));
        discard();
    }

    /** Heals, cleanses and renews one creature; returns the health it actually gained. */
    private static float blessOne(LivingEntity who, float amount, Blessing b) {
        float before = who.getHealth();
        who.heal(amount);
        if (b.cleanse()) {
            List<MobEffectInstance> harmful = new ArrayList<>();
            for (MobEffectInstance effect : who.getActiveEffects()) {
                if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) harmful.add(effect);
            }
            for (MobEffectInstance effect : harmful) who.removeEffect(effect.getEffect());
            who.clearFire();
        }
        if (b.renew()) who.addEffect(new MobEffectInstance(MobEffects.REGENERATION, b.renewTicks(), 0));
        return Math.max(0f, who.getHealth() - before);
    }

    private void fizzle() {
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY(), getZ(), 5, 0.15, 0.15, 0.15, 0.0);
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
