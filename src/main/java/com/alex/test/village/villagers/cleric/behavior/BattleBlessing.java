package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The battle blessing (10-08 rework): when the bell has rung or raiders are
 * attacking, a cleric blesses the guards and archers near it once, with
 * Resistance I and Regeneration I for {@link ClericBalance#blessingTicks}
 * (15 s at level 1, 40 s at 100). At most once in
 * {@link ClericBalance#BLESSING_COOLDOWN_TICKS} per cleric, kept in its saved
 * brain data, so a bell rung over and over does not keep the guards blessed.
 *
 * <p>It waits for at least one guard to be close: the bell often rings with
 * the cleric across the village, and a blessing on nobody would use it up.
 */
public final class BattleBlessing {

    private static final String TAG_LAST = "ClericBlessingAt";

    private BattleBlessing() {}

    /** Blesses the guards near the cleric if the moment is right; returns whether it did. */
    static boolean tryBless(Activity.Context ctx, ProfessionSettings settings, int level) {
        if (!settings.getBool(ClericSettingsSchema.BATTLE_BLESSING)) return false;
        NpcEntity cleric = ctx.npc();
        ServerLevel world = ctx.level();
        long now = world.getGameTime();
        CompoundTag data = cleric.getBrainData();
        if (data.contains(TAG_LAST)) {
            long last = data.getLong(TAG_LAST);
            if (last <= now && now - last < ClericBalance.BLESSING_COOLDOWN_TICKS) return false;
        }
        if (!GuardAlertSystem.isVillageAlarmed(ctx.mgr(), world) && !ctx.mgr().raids().inProgress()) return false;

        double r = ClericBalance.BLESSING_RADIUS;
        AABB box = cleric.getBoundingBox().inflate(r, 6.0, r);
        List<NpcEntity> guards = world.getEntitiesOfClass(NpcEntity.class, box,
                e -> e != cleric && e.isAlive() && !e.isSleeping() && !ClericCare.isPreview(e)
                        && e.getProfession().isDefender() && e.distanceToSqr(cleric) <= r * r
                        // A starving guard with its three heals used gets no blessing (10-19).
                        && ClericCare.mayHeal(ctx.mgr(), e));
        if (guards.isEmpty()) return false;

        data.putLong(TAG_LAST, now);
        int ticks = ClericBalance.blessingTicks(level);
        for (NpcEntity guard : guards) {
            guard.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, ticks, 0));
            guard.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ticks, 0));
            ClericCare.countHeal(ctx.mgr(), guard, "battle blessing from " + ClericCare.nameOf(cleric), world);
            world.sendParticles(ParticleTypes.ENCHANT, guard.getX(), guard.getY(1.0), guard.getZ(),
                    30, 0.4, 0.7, 0.4, 0.6);
            world.sendParticles(ParticleTypes.HAPPY_VILLAGER, guard.getX(), guard.getY(1.1), guard.getZ(),
                    6, 0.3, 0.4, 0.3, 0.0);
        }
        world.sendParticles(ParticleTypes.END_ROD, cleric.getX(), cleric.getY(1.2), cleric.getZ(),
                24, 0.6, 0.8, 0.6, 0.05);
        world.playSound(null, cleric.getX(), cleric.getY(), cleric.getZ(),
                SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1.0f, 1.4f);
        cleric.showBubble(ThoughtIcon.TOPIC_HEALING, false, 60);
        ctx.brain().onClericCare(ctx, ClericBalance.POINTS_BATTLE_BLESSING,
                "battle blessing (" + guards.size() + " guards, " + ticks / 20 + " s)");
        return true;
    }
}
