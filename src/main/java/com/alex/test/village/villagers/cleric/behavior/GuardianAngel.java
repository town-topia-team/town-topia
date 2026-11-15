package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Guardian Angel, the level 100 cleric unlock: once a day, a villager about to
 * die within {@link ClericBalance#GUARDIAN_RADIUS} blocks of a master cleric
 * is kept alive on three hearts with a burst of Regeneration, the way a totem
 * saves a player. The cleric itself counts. Players in town are told.
 *
 * <p>High priority, so the death announcement (lowest) never fires for a
 * villager that was saved. Never against {@code /kill} or the void.
 */
public final class GuardianAngel {

    private static final String TAG_DAY = "ClericGuardianDay";
    private static final String TAG_COUNT = "ClericGuardianCount";

    private GuardianAngel() {}

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onVillagerDeath(LivingDeathEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof NpcEntity dying)) return;
        if (!(dying.level() instanceof ServerLevel level) || level.getServer() == null) return;
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) || ClericCare.isPreview(dying)) return;
        VillageManager manager = residentManager(dying, level);
        if (manager == null || manager.getModel(dying.getNpcId()) == null) return;
        // A starving villager whose three cleric heals are used is not saved (10-19).
        VillagerBrain savedBrain = ClericCare.brainOf(manager, dying);
        if (savedBrain != null && !savedBrain.mayBeHealedByCleric()) return;

        NpcEntity angel = null;
        VillagerBrain angelBrain = null;
        long day = EveningSchedule.dayIndex(level.getDayTime());
        double best = Double.MAX_VALUE;
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity cleric = model.puppet();
            if (cleric == null || cleric.getProfession() != Profession.CLERIC
                    || (cleric != dying && !cleric.isAlive())) {
                continue;
            }
            VillagerBrain brain = model.brain();
            if (!ClericBalance.guardianAngel(brain.clericSkillLevel())) continue;
            if (!ClericCare.settings(cleric).getBool(ClericSettingsSchema.WORK_ENABLED)) continue;
            if (savesUsed(cleric.getBrainData(), day) >= ClericBalance.GUARDIAN_SAVES_PER_DAY) continue;
            double dist = cleric.distanceToSqr(dying);
            if (dist > ClericBalance.GUARDIAN_RADIUS * ClericBalance.GUARDIAN_RADIUS || dist >= best) continue;
            best = dist;
            angel = cleric;
            angelBrain = brain;
        }
        if (angel == null) return;

        event.setCanceled(true);
        CompoundTag data = angel.getBrainData();
        data.putLong(TAG_DAY, day);
        data.putInt(TAG_COUNT, savesUsed(data, day) + 1);
        dying.setHealth(ClericBalance.GUARDIAN_HEALTH);
        dying.clearFire();
        // Only what was hurting it: a potion given it by a player stays.
        List<MobEffectInstance> harmful = new ArrayList<>();
        for (MobEffectInstance effect : dying.getActiveEffects()) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) harmful.add(effect);
        }
        for (MobEffectInstance effect : harmful) dying.removeEffect(effect.getEffect());
        dying.addEffect(new MobEffectInstance(MobEffects.REGENERATION, ClericBalance.GUARDIAN_REGEN_TICKS, 1));
        dying.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 60, 3));
        level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING, dying.getX(), dying.getY(1.0), dying.getZ(),
                40, 0.4, 0.6, 0.4, 0.4);
        level.playSound(null, dying.getX(), dying.getY(), dying.getZ(), SoundEvents.TOTEM_USE,
                dying.getSoundSource(), 0.8f, 1.2f);
        angel.showBubble(ThoughtIcon.TOPIC_HEALING, false, 60);
        if (savedBrain != null) {
            savedBrain.rememberAndShow(MemoryTopic.CARED_FOR, ThoughtIcon.EMOTE_RELIEF, 60, level);
            savedBrain.useStarvingHeal("guardian angel from " + ClericCare.nameOf(angel), level);
        }
        angelBrain.diary().log(VillagerDiary.ts(level) + " CLERIC guardian angel saved "
                + ClericCare.nameOf(dying) + " from " + event.getSource().getMsgId());
        VillageDamageAlerts.announceGuardianAngel(manager, level, angel, dying);
    }

    private static int savesUsed(CompoundTag data, long day) {
        return data.contains(TAG_DAY) && data.getLong(TAG_DAY) == day ? data.getInt(TAG_COUNT) : 0;
    }

    @Nullable
    private static VillageManager residentManager(NpcEntity npc, ServerLevel level) {
        VillagerVillageLink link = npc.getVillageLink();
        if (link == null) return null;
        VillageManager manager = VillageManagerRegistry.get(level.getServer()).get(link.villageId());
        if (manager != null) return manager;
        return VillageManager.INSTANCE.getVillageId().equals(link.villageId()) ? VillageManager.INSTANCE : null;
    }
}
