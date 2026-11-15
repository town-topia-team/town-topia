package com.alex.test.village.villagers.guard.behavior;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.mayor.MayorEntity;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.chat.DamageCause;
import com.alex.test.village.chat.HazardDamageLog;
import com.alex.test.village.chat.VillageDamageAlerts;
import com.alex.test.village.villagers.guard.alert.GuardThreatRules;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

import javax.annotation.Nullable;

/** Event bridge for monster target changes that should wake/report to guards. */
public final class GuardThreatEvents {

    /** Marks a monster whose goals already include the NPC targeting added below. */
    private static final class NpcRetaliationGoal extends HurtByTargetGoal {
        NpcRetaliationGoal(Monster monster) { super(monster, MayorEntity.class); }
    }

    /**
     * How rarely a monster looks for a villager to go after: vanilla's default
     * is 10, a one-in-five roll every other tick, so a zombie walking past a
     * villager could take half a second or more to notice it. 3 is about
     * three times as quick and still not every tick (the search is a box scan).
     */
    private static final int NPC_TARGET_RANDOM_INTERVAL = 3;

    private GuardThreatEvents() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Monster monster)) return;
        // The Warlord picks its own targets (defenders first); these would sit on top of them.
        if (monster instanceof com.alex.test.village.raid.warlord.RaidWarlord) return;
        // Checked on the goal list itself, not by UUID: a monster reloaded from
        // its chunk is a fresh object with the same UUID and none of our goals.
        for (WrappedGoal goal : monster.targetSelector.getAvailableGoals()) {
            if (goal.getGoal() instanceof NpcRetaliationGoal) return;
        }

        monster.targetSelector.addGoal(1, new NpcRetaliationGoal(monster));
        if (GuardMobPolicy.canVanillaMonsterProactivelyTargetNpc(monster)) {
            monster.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                    monster,
                    NpcEntity.class,
                    NPC_TARGET_RANDOM_INTERVAL, true, false,
                    target -> !monster.isInWaterOrBubble()
                            && !(target instanceof MayorEntity)
                            && !com.alex.test.village.raid.RaidKidnap.isCaptive(target)
                            && !isSpiderInDaylight(monster)));
        } else if (monster instanceof Creeper) {
            // Creepers leave villagers alone -- unless a guard of that villager's
            // village has been set to fight creepers, which makes them enemies there.
            monster.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(
                    monster,
                    NpcEntity.class,
                    NPC_TARGET_RANDOM_INTERVAL, true, false,
                    target -> !monster.isInWaterOrBubble()
                            && !(target instanceof MayorEntity)
                            && creepersHostileFor(target)));
        }
    }

    /** Whether creepers count as enemies in {@code npc}'s village (a defender there fights them). */
    private static boolean creepersHostileFor(LivingEntity target) {
        if (!(target instanceof NpcEntity npc) || !(npc.level() instanceof ServerLevel level)
                || level.getServer() == null) {
            return false;
        }
        VillageManager manager = resolveManager(npc, level);
        return manager != null && GuardThreatRules.villageTurnedHostile(manager, level.getGameTime(),
                net.minecraft.world.entity.EntityType.getKey(net.minecraft.world.entity.EntityType.CREEPER));
    }

    /** Mirrors vanilla Spider.SpiderTargetGoal: spiders (and cave spiders) only pick targets in the dark. */
    private static boolean isSpiderInDaylight(Monster monster) {
        return monster instanceof Spider && monster.getLightLevelDependentMagicValue() >= 0.5F;
    }

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event) {
        LivingEntity newTarget = event.getNewAboutToBeSetTarget();
        if (!(newTarget instanceof NpcEntity npc)) return;
        if (!(event.getEntity() instanceof Monster monster)) return;

        // Creepers are village-friendly unless a guard there is set to fight them.
        // Clear the pending target instead of cancelling the event, because
        // cancellation would preserve the old target.
        if (monster instanceof Creeper && !creepersHostileFor(npc)) {
            event.setNewAboutToBeSetTarget(null);
            return;
        }
        if (monster.isInWaterOrBubble()) {
            event.setNewAboutToBeSetTarget(null);
            return;
        }

        if (npc instanceof MayorEntity) return;
        if (!(npc.level() instanceof ServerLevel level) || level.getServer() == null) return;

        VillageManager manager = resolveManager(npc, level);
        // A resting village (nobody inside its border) does not think: its people cannot run,
        // hide or fight, so a player standing just outside got them killed where they stood.
        if (manager != null && manager.isDormantOrReconciling() && !manager.raids().inProgress()) {
            event.setNewAboutToBeSetTarget(null);
            return;
        }
        if (manager != null && GuardThreatRules.isHostileMonster(manager, monster)) {
            GuardAlertSystem.reportThreat(manager, level, npc, monster);
        }
    }

    /**
     * Nothing but a player (or /kill) hurts the people of a resting village. Its
     * brains are off while nobody is inside the border, yet with a player in
     * sight of it the chunks still run: mobs spawned, walked in and killed
     * villagers that could neither flee nor fight. What happens to a village
     * nobody is in is the away calculator's to decide, not a zombie's.
     */
    @SubscribeEvent
    public static void onRestingVillagerHurt(net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || npc instanceof MayorEntity) return;
        if (!(npc.level() instanceof ServerLevel level) || level.getServer() == null) return;
        if (event.getSource().getEntity() instanceof net.minecraft.world.entity.player.Player) return;
        if (event.getSource().is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        VillageManager manager = resolveManager(npc, level);
        if (manager == null || !manager.isDormantOrReconciling() || manager.raids().inProgress()) return;
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onNpcDamaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) return;
        if (!(event.getEntity() instanceof NpcEntity npc)) return;
        if (!(npc.level() instanceof ServerLevel level) || level.getServer() == null) return;
        VillageManager manager = resolveManager(npc, level);
        DamageCause cause = DamageCause.of(npc, level, event.getSource());
        HazardDamageLog.record(level, npc, manager, cause);
        if (manager == null) return;

        // Only a hostile mob behind the hit (never starving, falling or a block) is a threat,
        // and only its hits are a fight for the fight bar; a wolf or a dispenser is not.
        Mob monster = sourceMonster(event);
        // The killing blow is told by the death message (onNpcDeath) instead.
        if (!npc.isDeadOrDying()) VillageDamageAlerts.announceDamage(manager, level, npc, cause, monster != null);

        if (monster == null || monster.isInWaterOrBubble()) return;

        if (GuardAlertSystem.reportDirectDamageThreat(manager, level, npc, monster)) {
            // Make the existing brain override react on the next tick: normal
            // villagers flee, guards counter-attack, without adding per-tick scans.
            npc.setLastHurtByMob(monster);
        }
    }

    /**
     * A villager died: players in its town are told, in the words vanilla uses
     * for a player's death. Lowest priority, so a death another handler
     * cancels is never announced.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onNpcDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof NpcEntity npc)) return;
        if (!(npc.level() instanceof ServerLevel level) || level.getServer() == null) return;
        VillageManager manager = resolveManager(npc, level);
        if (manager == null) return;
        VillageDamageAlerts.announceDeath(manager, level, npc, npc.getCombatTracker().getDeathMessage(),
                killedByMonster(npc, event.getSource()));
    }

    /**
     * A monster dealt the blow, or hit the villager in the last five seconds
     * before the fall, fire or drowning that killed it: a death in the fight.
     * Starving or walking into lava on its own is not.
     */
    private static boolean killedByMonster(NpcEntity npc, net.minecraft.world.damagesource.DamageSource source) {
        if (monsterBehind(source) != null) return true;
        return npc.tickCount - npc.getLastHurtByMobTimestamp() < 100 && hostileMob(npc.getLastHurtByMob()) != null;
    }

    /**
     * A player ringing a bell inside a village sounds the alarm: every defender
     * goes on alert, off-duty ones included, and everyone else goes indoors
     * until the danger has passed (see {@link GuardAlertSystem#soundAlarm}).
     */
    @SubscribeEvent
    public static void onBellRung(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        if (!(level.getBlockState(event.getPos()).getBlock() instanceof net.minecraft.world.level.block.BellBlock)) return;
        // Sneaking with an item in hand places it instead of ringing.
        if (event.getEntity().isSecondaryUseActive() && !event.getEntity().getMainHandItem().isEmpty()) return;

        VillageManager manager = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, event.getPos());
        if (manager == null) return;
        boolean wasAlarmed = GuardAlertSystem.isVillageAlarmed(manager, level);
        GuardAlertSystem.soundAlarm(manager, level);
        int revealed = revealHostiles(manager, level, event.getPos());
        if (revealed > 0) {
            event.getEntity().displayClientMessage(com.alex.test.Tr.c("chat.dynamicvillages.alert.alarm_revealed",
                    "Alarm! %s hostile mob(s) inside the village are glowing.", revealed), true);
        } else if (!wasAlarmed) {
            event.getEntity().displayClientMessage(com.alex.test.Tr.c("chat.dynamicvillages.alert.alarm",
                    "Alarm! The guards are on alert and villagers are heading indoors."), true);
        } else {
            event.getEntity().displayClientMessage(com.alex.test.Tr.c("chat.dynamicvillages.alert.alarm_clear",
                    "No hostile mobs found inside the village."), true);
        }
    }

    /** How long the bell keeps the village's hostile mobs glowing (10 s; a vanilla bell lights raiders for 3). */
    private static final int BELL_REVEAL_TICKS = 200;
    /** Half the side of the square searched around the bell: wider than any village. */
    private static final int BELL_REVEAL_REACH = 176;

    /**
     * The bell shows where the danger is: every mob the village counts as
     * hostile, anywhere inside its border and at any height, glows through
     * walls. The alarm music played while the one zombie that set it off sat
     * in a cave under a house or on an unlit roof, and nobody could find it.
     */
    private static int revealHostiles(VillageManager manager, ServerLevel level, net.minecraft.core.BlockPos bell) {
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                bell.getX() - BELL_REVEAL_REACH, level.getMinBuildHeight(), bell.getZ() - BELL_REVEAL_REACH,
                bell.getX() + BELL_REVEAL_REACH, level.getMaxBuildHeight(), bell.getZ() + BELL_REVEAL_REACH);
        int revealed = 0;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, box, candidate -> candidate.isAlive()
                && manager.contains(candidate.blockPosition())
                && GuardThreatRules.isHostileMonster(manager, candidate))) {
            mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.GLOWING, BELL_REVEAL_TICKS, 0, false, false));
            revealed++;
        }
        return revealed;
    }

    @SubscribeEvent
    public static void onPlayerDamaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0.0F) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(player.level() instanceof ServerLevel level) || level.getServer() == null) return;

        Mob monster = sourceMonster(event);
        if (monster == null || monster.isInWaterOrBubble()) return;

        VillageManager manager = resolveManager(player, level);
        if (manager != null && GuardThreatRules.isHostileMonster(manager, monster)) {
            GuardAlertSystem.reportPlayerThreat(manager, level, player, monster);
        }
    }

    @Nullable
    private static VillageManager resolveManager(NpcEntity npc, ServerLevel level) {
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        // Merchants and nomads have no resident link, but know the village they
        // are visiting -- which still counts when they stand just past its border.
        if (npc instanceof AbstractTravelerEntity traveler && traveler.getVillageId() != null) {
            VillageManager manager = registry.get(traveler.getVillageId());
            if (manager != null) return manager;
        }
        VillagerVillageLink link = npc.getVillageLink();
        if (link != null) {
            VillageManager manager = registry.get(link.villageId());
            if (manager != null) return manager;
            if (VillageManager.INSTANCE.getVillageId().equals(link.villageId())) {
                return VillageManager.INSTANCE;
            }
        }

        // Fallback for freshly loaded/relinked NPCs whose persistent village link
        // has not been restored yet. This keeps event-based threat reports working
        // without doing a broad mob scan.
        return registry.findVillageAt(level, npc.blockPosition());
    }

    @Nullable
    private static VillageManager resolveManager(ServerPlayer player, ServerLevel level) {
        return VillageManagerRegistry.get(level.getServer())
                .findVillageAt(level, player.blockPosition());
    }

    /** The hostile mob behind a hit -- a {@link Monster}, or any other {@link Enemy} such as a slime. */
    @Nullable
    private static Mob sourceMonster(LivingDamageEvent.Post event) {
        return monsterBehind(event.getSource());
    }

    @Nullable
    private static Mob monsterBehind(net.minecraft.world.damagesource.DamageSource source) {
        Mob mob = hostileMob(source.getEntity());
        return mob != null ? mob : hostileMob(source.getDirectEntity());
    }

    @Nullable
    private static Mob hostileMob(@Nullable Entity entity) {
        if (entity instanceof Projectile projectile) entity = projectile.getOwner();
        return entity instanceof Mob mob && mob instanceof Enemy ? mob : null;
    }
}
