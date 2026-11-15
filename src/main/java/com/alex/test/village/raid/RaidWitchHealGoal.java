package com.alex.test.village.raid;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.LevelEvent;

import java.util.List;

/**
 * A raid witch heals the raiders around it, as vanilla's witches do in a
 * vanilla raid. Ours are not in one, so vanilla's own healing never runs for
 * them. Vanilla's rule for the potion: healing for a raider nearly dead,
 * regeneration otherwise. The effect goes on that raider alone, with the
 * throw and the splash only shown: a real splash would heal the guards and
 * villagers fighting it too.
 *
 * <p>No flags: it runs alongside marching and fighting.
 */
final class RaidWitchHealGoal extends Goal {

    private static final double RANGE = 10.0;
    /** Between potions (vanilla's witch throws about every 3 s). */
    private static final int COOLDOWN_TICKS = 60;
    /** How long to wait before looking again when nobody needs healing. */
    private static final int LOOK_AGAIN_TICKS = 20;
    /** A raider below this share of its health is worth a potion. */
    private static final float HURT_SHARE = 0.8F;
    /** Vanilla's line between a healing and a regeneration potion. */
    private static final float NEARLY_DEAD = 4.0F;
    /** Regeneration I for 30 s (vanilla's splash on a direct hit gives 45 s). */
    private static final int REGEN_TICKS = 30 * 20;

    private final Witch witch;
    private final RaidManager raid;
    private long nextAt;

    RaidWitchHealGoal(Witch witch, RaidManager raid) {
        this.witch = witch;
        this.raid = raid;
    }

    @Override
    public boolean canUse() {
        return raid.isTracking(witch);
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        if (!(witch.level() instanceof ServerLevel level) || witch.isDrinkingPotion()) return;
        long now = level.getGameTime();
        if (now < nextAt) return;
        nextAt = now + LOOK_AGAIN_TICKS;
        Raider patient = mostHurt(level);
        if (patient == null) return;
        nextAt = now + COOLDOWN_TICKS + level.random.nextInt(20);
        heal(level, patient);
    }

    /** The most hurt raider of this raid in reach and in sight; never a witch (they drink their own). */
    private Raider mostHurt(ServerLevel level) {
        List<Raider> near = level.getEntitiesOfClass(Raider.class, witch.getBoundingBox().inflate(RANGE),
                r -> r != witch && r.isAlive() && !(r instanceof Witch)
                        && r.getHealth() < r.getMaxHealth() * HURT_SHARE && raid.isTracking(r));
        Raider best = null;
        for (Raider raider : near) {
            if (!witch.hasLineOfSight(raider)) continue;
            if (best == null || raider.getHealth() / raider.getMaxHealth() < best.getHealth() / best.getMaxHealth()) {
                best = raider;
            }
        }
        return best;
    }

    private void heal(ServerLevel level, Raider patient) {
        witch.getLookControl().setLookAt(patient, 30.0F, 30.0F);
        witch.swing(InteractionHand.MAIN_HAND);
        level.playSound(null, witch.getX(), witch.getY(), witch.getZ(), SoundEvents.WITCH_THROW,
                witch.getSoundSource(), 1.0F, 0.8F + level.random.nextFloat() * 0.4F);
        boolean nearlyDead = patient.getHealth() <= NEARLY_DEAD;
        Holder<Potion> potion = nearlyDead ? Potions.HEALING : Potions.REGENERATION;
        if (nearlyDead) {
            patient.heal(4.0F);
        } else {
            patient.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, 0), witch);
        }
        // The splash and its sound, on the raider only.
        level.levelEvent(nearlyDead ? LevelEvent.PARTICLES_INSTANT_POTION_SPLASH : LevelEvent.PARTICLES_SPELL_POTION_SPLASH,
                patient.blockPosition(), new PotionContents(potion).getColor());
    }
}
