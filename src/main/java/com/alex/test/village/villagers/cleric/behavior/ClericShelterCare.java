package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.village.brain.Activity;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;

/**
 * A cleric sheltering from an alarm keeps healing: anyone hurt it can throw at
 * from where it stands, inside with it or seen through the door. It never
 * walks for it; the alarm says stay in.
 */
public final class ClericShelterCare {

    private static final int CHECK_TICKS = 20;

    @Nullable private HealingCast cast;
    private int check;

    /** Called every tick the cleric spends settled in its shelter. */
    public void tick(Activity.Context ctx) {
        if (cast != null) {
            if (cast.tick(ctx)) {
                cast.stop(ctx, false);
                cast = null;
                check = 0;
            }
            return;
        }
        if (--check > 0) return;
        check = CHECK_TICKS;
        ProfessionSettings settings = ClericCare.settings(ctx.npc());
        if (!settings.getBool(ClericSettingsSchema.WORK_ENABLED)) return;
        int level = ctx.brain().clericSkillLevel();
        // Guards sheltering or fighting at the door still get the battle blessing.
        BattleBlessing.tryBless(ctx, settings, level);
        LivingEntity patient = ClericCare.pickPatient(ctx, settings, level, true);
        if (patient != null) {
            ctx.npc().setActivityLabel("healing " + ClericCare.nameOf(patient));
            cast = new HealingCast(ctx, patient, level, settings);
        }
    }

    /** The shelter ended or was interrupted mid-throw. */
    public void stop(Activity.Context ctx) {
        if (cast != null) cast.stop(ctx, true);
        cast = null;
        // The throw pose is held between throws; leaving the shelter ends it.
        if (ctx.npc().getAnimation() == com.alex.test.animation.VillagerAnimationState.CAST) {
            ctx.npc().setAnimation(com.alex.test.animation.VillagerAnimationState.IDLE);
        }
    }
}
