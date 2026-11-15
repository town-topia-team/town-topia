package com.alex.test.village.villagers.guard;

import com.alex.test.LegacyModId;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingKnockBackEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a guard's or captain's skill level does to the defender itself: its
 * extra health, the shield it blocks with, the techniques {@code GuardActivity}
 * calls on a hit, and the captain's rallying cry. Numbers live in
 * {@link GuardBalance} and {@link CaptainBalance}, picked through
 * {@link CombatRank}.
 */
public final class GuardSkillEffects {

    // Saved on the guard: keeps the old prefix so a renamed id never stacks a second bonus.
    private static final ResourceLocation HEALTH_MODIFIER_ID =
            ResourceLocation.fromNamespaceAndPath(LegacyModId.SAVE_PREFIX, "guard_skill_health");
    private static final String TAG_SECOND_WIND_DAY = "GuardSecondWindDay";
    private static final String TAG_SECOND_WIND_COUNT = "GuardSecondWindCount";
    private static final Set<net.minecraft.world.item.Item> SHIELDS = Set.of(Items.SHIELD);

    /** Rallied defenders, by NPC id, to the game time their rally runs out. */
    private static final Map<UUID, Long> RALLIED = new HashMap<>();

    private GuardSkillEffects() {}

    /**
     * The skill level of the guard or captain job this villager works now, as
     * shown on its skill slot, for code that has the entity but not its brain
     * (damage events). 0 when it is neither.
     */
    public static int activeLevel(NpcEntity npc) {
        Profession profession = npc.getProfession();
        if (!profession.isGuardLike()) return 0;
        for (ProfessionSkill skill : npc.getProfessionSkills()) {
            if (skill.profession() == profession) return skill.level();
        }
        return 1;
    }

    /**
     * Sets the skill's extra health, or removes it when {@code level} is 0 (not
     * a guard or captain). Permanent so it is saved with the entity: attributes
     * load before health, so a wounded defender keeps its health over a reload.
     */
    public static void applyHealth(NpcEntity npc, CombatRank rank, int level) {
        AttributeInstance health = npc.getAttribute(Attributes.MAX_HEALTH);
        if (health == null) return;
        double amount = level > 0 ? rank.bonusHealth(level) : 0.0;
        AttributeModifier current = health.getModifier(HEALTH_MODIFIER_ID);
        if (current != null && Math.abs(current.amount() - amount) < 1.0e-4) return;
        if (current != null) health.removeModifier(HEALTH_MODIFIER_ID);
        if (amount > 0.0) {
            health.addPermanentModifier(new AttributeModifier(
                    HEALTH_MODIFIER_ID, amount, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    /**
     * Guards, captains and archers are shoved only half as far as anyone else, so a
     * mob that hits one cannot throw it out of reach every time and keep it from
     * ever swinging back.
     */
    @SubscribeEvent
    public static void onKnockBack(LivingKnockBackEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !npc.getProfession().isDefender()) return;
        event.setStrength(event.getStrength() * GuardBalance.KNOCKBACK_TAKEN_FACTOR);
    }

    /** The best usable shield in the pack, or -1. */
    public static int findShieldSlot(InventoryManager inventory) {
        return ToolSelectionHelper.findBestUsableToolSlot(inventory, Tags.Items.TOOLS_SHIELD,
                SHIELDS, ToolSelectionHelper::isUsableByDefault);
    }

    // =========================================================================
    // Shield block
    // =========================================================================

    /**
     * Shield block: a defender whose shield is up -- see {@link ShieldStance}
     * -- takes no damage from anything that hits it from the front.
     * Damage that ignores shields in vanilla (falls, fire, magic) ignores this
     * too. What a hit takes off is drawn from the guard meter; an empty meter
     * breaks the defender open, an axe disables the shield, and a block just as
     * the shield comes up is a parry that costs nothing and bashes the attacker.
     * A captain's raised shield also covers a defender right behind it, in part.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc)) return;
        if (!(npc.level() instanceof ServerLevel level)) return;
        DamageSource source = event.getSource();
        if (source.is(DamageTypeTags.BYPASSES_SHIELD)) return;
        Vec3 from = source.getSourcePosition();
        if (from == null) return;
        if (!npc.getProfession().isDefender()) return;

        CombatRank rank = CombatRank.of(npc.getProfession());
        int skill = activeLevel(npc);
        long now = level.getGameTime();
        ShieldStance stance = npc.shieldStance();

        // A shot it raised the shield for is stopped outright, like a vanilla
        // shield stops an arrow: whichever way the defender has turned since,
        // nothing gets through and the meter is not touched -- the shield only
        // wears. The arrow bounces off.
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile
                && skill > 0 && rank.hasShieldBlock(skill)
                && (stance.isUpAgainstShots(now) || (stance.isUp(now) && isInFront(npc, from)))) {
            int slot = findShieldSlot(npc.getInventory());
            if (slot >= 0) {
                event.setCanceled(true);
                stance.markBlocked(now);
                // The shooter is remembered, as for a blocked blow, so self-defence answers it.
                if (source.getEntity() instanceof LivingEntity shooter) npc.setLastHurtByMob(shooter);
                if (!npc.keepsGearWhole()) ToolSelectionHelper.wearTool(level, npc.getInventory(), slot);
                level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.SHIELD_BLOCK,
                        npc.getSoundSource(), 0.8F, 0.9F + level.random.nextFloat() * 0.2F);
                return;
            }
        }

        if (skill > 0 && rank.hasShieldBlock(skill) && stance.isUp(now) && isInFront(npc, from)) {
            int slot = findShieldSlot(npc.getInventory());
            if (slot < 0) {
                npc.setShieldRaised(false);
            } else {
                blockWithOwnShield(level, npc, rank, skill, stance, slot, event, source, now);
                return;
            }
        }
        shieldWall(level, npc, from, event, now);
    }

    private static void blockWithOwnShield(ServerLevel level, NpcEntity npc, CombatRank rank, int skill,
                                           ShieldStance stance, int slot, LivingIncomingDamageEvent event,
                                           DamageSource source, long now) {
        // Up and facing the blow: the whole hit is stopped, and the whole hit is
        // what the guard meter pays -- all but a share of any part the meter
        // could not hold (GuardBalance.METER_OVERFLOW_SHARE).
        float blocked = event.getAmount();
        event.setCanceled(true);
        stance.markBlocked(now);
        LivingEntity attacker = source.getDirectEntity() instanceof LivingEntity direct ? direct : null;
        // As a vanilla shield block does: the attacker is still remembered, so
        // self-defence answers it.
        if (attacker != null) npc.setLastHurtByMob(attacker);

        if (rank.canParry(skill) && stance.inParryWindow(now) && attacker != null) {
            // Timed just right: nothing comes off the meter and the attacker is thrown back.
            // The bash wears the shield once.
            bash(level, npc, attacker, slot, rank == CombatRank.CAPTAIN);
            return;
        }
        if (!npc.keepsGearWhole()) ToolSelectionHelper.wearTool(level, npc.getInventory(), slot);

        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.SHIELD_BLOCK,
                npc.getSoundSource(), 0.8F, 0.9F + level.random.nextFloat() * 0.2F);
        if (attacker != null && attacker.getMainHandItem().is(ItemTags.AXES)) {
            // As in vanilla: an axe knocks the shield out of use for a few seconds.
            stance.breakUntil(now + GuardBalance.SHIELD_DISABLED_TICKS);
            npc.setShieldRaised(false);
            level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.SHIELD_BREAK,
                    npc.getSoundSource(), 0.9F, 1.0F);
            return;
        }
        float max = rank.guardMeter(skill);
        float overflow = Math.max(0f, blocked - stance.meter(max));
        if (stance.drain(blocked, max)) breakOpen(level, npc, stance, now);
        if (overflow > 0f) {
            // The meter ran dry part-way through the blow: a share of what it
            // could not hold gets through. It used to stop the rest entirely, so
            // a meter with one point left still held off a ravager.
            event.setCanceled(false);
            event.setAmount(overflow * GuardBalance.METER_OVERFLOW_SHARE);
        }
    }

    /** Guard broken open: down, dazed, and takes full damage for a moment. */
    private static void breakOpen(ServerLevel level, NpcEntity npc, ShieldStance stance, long now) {
        stance.breakUntil(now + GuardBalance.GUARD_BROKEN_TICKS);
        npc.setShieldRaised(false);
        npc.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                GuardBalance.GUARD_BROKEN_TICKS, 2));
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.SHIELD_BREAK,
                npc.getSoundSource(), 1.0F, 0.8F);
        level.sendParticles(ParticleTypes.CRIT, npc.getX(), npc.getY(1.0), npc.getZ(),
                10, 0.3, 0.3, 0.3, 0.1);
    }

    /**
     * A raised captain's shield covers a defender standing just behind it,
     * between the captain and no one: a share of the captain's block, paid from
     * the captain's meter.
     */
    private static void shieldWall(ServerLevel level, NpcEntity ally, Vec3 from,
                                   LivingIncomingDamageEvent event, long now) {
        Vec3 toSource = new Vec3(from.x - ally.getX(), 0.0, from.z - ally.getZ());
        if (toSource.lengthSqr() < 1.0e-6) return;
        Vec3 dir = toSource.normalize();
        for (NpcEntity captain : level.getEntitiesOfClass(NpcEntity.class,
                ally.getBoundingBox().inflate(CaptainBalance.SHIELD_WALL_DEPTH),
                other -> other != ally && other.isAlive() && other.getProfession() == Profession.CAPTAIN)) {
            ShieldStance stance = captain.shieldStance();
            if (!stance.isUp(now)) continue;
            Vec3 toCaptain = new Vec3(captain.getX() - ally.getX(), 0.0, captain.getZ() - ally.getZ());
            if (toCaptain.lengthSqr() < 1.0e-6 || toCaptain.normalize().dot(dir) < 0.7) continue;
            if (!isInFront(captain, from)) continue;
            int skill = activeLevel(captain);
            float blocked = event.getAmount() * CaptainBalance.SHIELD_WALL_SHARE;
            event.setAmount(event.getAmount() - blocked);
            level.playSound(null, ally.getX(), ally.getY(), ally.getZ(), SoundEvents.SHIELD_BLOCK,
                    ally.getSoundSource(), 0.5F, 1.1F);
            // Covering others drains the same meter, and can break the captain open too.
            if (stance.drain(blocked, CaptainBalance.guardMeter(skill))) breakOpen(level, captain, stance, now);
            return;
        }
    }

    /** Within 90° either side of where the defender faces, the way vanilla shields judge it. */
    private static boolean isInFront(NpcEntity npc, Vec3 from) {
        Vec3 toSource = new Vec3(from.x - npc.getX(), 0.0, from.z - npc.getZ());
        Vec3 view = npc.getViewVector(1.0F);
        Vec3 facing = new Vec3(view.x, 0.0, view.z);
        if (toSource.lengthSqr() < 1.0e-6 || facing.lengthSqr() < 1.0e-6) return false;
        return toSource.normalize().dot(facing.normalize()) > 0.0;
    }

    // =========================================================================
    // Techniques used on a hit
    // =========================================================================

    /**
     * Sweeping strike: hostiles right next to the target take a share of the
     * hit as well.
     *
     * @return how many of them it killed
     */
    public static int sweep(ServerLevel level, NpcEntity npc, LivingEntity target, float damage,
                            float fraction) {
        AABB box = target.getBoundingBox().inflate(GuardBalance.SWEEP_RADIUS, 0.25, GuardBalance.SWEEP_RADIUS);
        int kills = 0;
        for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, box)) {
            if (other == target || other == npc || !other.isAlive()) continue;
            if (!GuardThreatRules.isHostileMonsterForGuard(npc, other)) continue;
            boolean hit = com.alex.test.village.tokens.PlayerGear.withWorkingMainHand(npc,
                    () -> other.hurt(npc.damageSources().mobAttack(npc), damage * fraction));
            if (hit && !other.isAlive()) {
                kills++;
            }
        }
        Vec3 look = npc.getViewVector(1.0F);
        level.sendParticles(ParticleTypes.SWEEP_ATTACK,
                npc.getX() + look.x, npc.getY(0.5), npc.getZ() + look.z, 1, 0.0, 0.0, 0.0, 0.0);
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP,
                npc.getSoundSource(), 0.7F, 1.0F);
        return kills;
    }

    /**
     * Shield bash: knocks the target well back and leaves it slowed and weak
     * for a moment, so its next swing comes late and soft. A captain's
     * shockwave does the same to every hostile around the target. Wears the
     * shield once.
     */
    public static void bash(ServerLevel level, NpcEntity npc, LivingEntity target, int shieldSlot,
                            boolean shockwave) {
        stagger(npc, target);
        if (shockwave) {
            AABB box = target.getBoundingBox().inflate(CaptainBalance.SHOCKWAVE_RADIUS, 0.5,
                    CaptainBalance.SHOCKWAVE_RADIUS);
            for (LivingEntity other : level.getEntitiesOfClass(LivingEntity.class, box)) {
                if (other == target || other == npc || !other.isAlive()) continue;
                if (GuardThreatRules.isHostileMonsterForGuard(npc, other)) stagger(npc, other);
            }
            level.sendParticles(ParticleTypes.EXPLOSION, target.getX(), target.getY(0.3), target.getZ(),
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        if (!npc.keepsGearWhole()) ToolSelectionHelper.wearTool(level, npc.getInventory(), shieldSlot);
        level.sendParticles(ParticleTypes.CRIT, target.getX(), target.getY(0.6), target.getZ(),
                8, 0.3, 0.3, 0.3, 0.1);
        level.playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.SHIELD_BLOCK,
                npc.getSoundSource(), 1.0F, shockwave ? 0.5F : 0.6F);
    }

    private static void stagger(NpcEntity npc, LivingEntity target) {
        target.knockback(GuardBalance.BASH_KNOCKBACK,
                npc.getX() - target.getX(), npc.getZ() - target.getZ());
        target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                GuardBalance.BASH_STUN_TICKS, 3), npc);
        target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS,
                GuardBalance.BASH_STUN_TICKS, 3), npc);
    }

    /**
     * Second wind: a defender about to fall back heals instead and keeps
     * fighting -- once a day for a guard at 100, twice for a captain at 100,
     * whose heal also reaches the defenders around it.
     *
     * @return true when it fired
     */
    public static boolean trySecondWind(ServerLevel level, NpcEntity npc, CombatRank rank, int skillLevel) {
        int perDay = rank.secondWindsPerDay(skillLevel);
        if (perDay <= 0) return false;
        long day = EveningSchedule.dayIndex(level.getDayTime());
        CompoundTag data = npc.getBrainData();
        int used = data.contains(TAG_SECOND_WIND_DAY) && data.getLong(TAG_SECOND_WIND_DAY) == day
                ? data.getInt(TAG_SECOND_WIND_COUNT) : 0;
        if (used >= perDay) return false;
        data.putLong(TAG_SECOND_WIND_DAY, day);
        data.putInt(TAG_SECOND_WIND_COUNT, used + 1);
        npc.heal(npc.getMaxHealth() * GuardBalance.SECOND_WIND_HEAL_FRACTION);
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, npc.getX(), npc.getY(1.0), npc.getZ(),
                30, 0.4, 0.5, 0.4, 0.3);
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.PLAYER_LEVELUP,
                npc.getSoundSource(), 0.6F, 1.4F);

        if (rank == CombatRank.CAPTAIN) {
            for (NpcEntity ally : defendersAround(level, npc, CaptainBalance.UNBREAKABLE_RADIUS)) {
                ally.heal(ally.getMaxHealth() * CaptainBalance.UNBREAKABLE_ALLY_HEAL_FRACTION);
                level.sendParticles(ParticleTypes.HEART, ally.getX(), ally.getY(1.1), ally.getZ(),
                        3, 0.3, 0.2, 0.3, 0.0);
            }
        }
        return true;
    }

    // =========================================================================
    // Rallying cry
    // =========================================================================

    /**
     * A fighting captain rallies every guard, captain and archer within
     * {@link CaptainBalance#RALLY_RADIUS}: for the next
     * {@link CaptainBalance#RALLY_TICKS} they hit harder and fall back later.
     * Called every second or so while the captain fights; a defender newly
     * rallied shows it.
     */
    public static void rally(ServerLevel level, NpcEntity captain) {
        long now = level.getGameTime();
        boolean anyNew = false;
        for (NpcEntity ally : defendersAround(level, captain, CaptainBalance.RALLY_RADIUS)) {
            Long previous = RALLIED.put(ally.getNpcId(), now + CaptainBalance.RALLY_TICKS);
            if (previous == null || previous < now) {
                anyNew = true;
                level.sendParticles(ParticleTypes.ANGRY_VILLAGER, ally.getX(), ally.getY(1.2), ally.getZ(),
                        2, 0.3, 0.2, 0.3, 0.0);
            }
        }
        if (anyNew) {
            level.playSound(null, captain.getX(), captain.getY(), captain.getZ(),
                    SoundEvents.VILLAGER_CELEBRATE, captain.getSoundSource(), 1.0F, 0.8F);
        }
        if (RALLIED.size() > 256) RALLIED.values().removeIf(until -> until < now);
    }

    /** Whether a captain's rallying cry is on this defender right now. */
    public static boolean isRallied(NpcEntity npc, long gameTime) {
        Long until = RALLIED.get(npc.getNpcId());
        return until != null && until >= gameTime;
    }

    /** Damage multiplier from a rallying cry: 1 when not rallied. */
    public static double rallyDamageMultiplier(NpcEntity npc, long gameTime) {
        return isRallied(npc, gameTime) ? 1.0 + CaptainBalance.RALLY_DAMAGE_BONUS : 1.0;
    }

    private static Iterable<NpcEntity> defendersAround(ServerLevel level, NpcEntity center, double radius) {
        return level.getEntitiesOfClass(NpcEntity.class, center.getBoundingBox().inflate(radius),
                other -> other != center && other.isAlive() && other.getProfession().isDefender());
    }
}
