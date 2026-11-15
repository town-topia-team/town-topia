package com.alex.test.village.raid;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Raid bookkeeping from world events: a raider loaded back from its chunk
 * gets its orders again (or leaves, when its raid is over), and deaths of
 * raiders and villagers are counted by the raid they belong to.
 */
public final class RaidEvents {

    /** Raiders whose raid is over, sent away on the next level tick (not inside the join event). */
    private static final List<Entity> PENDING_DISCARDS = new ArrayList<>();

    private RaidEvents() {}

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.getServer() == null) return;
        // Minecraft's own raid (Bad Omen) aimed at one of our villages: a challenge raid there.
        if (event.getEntity() instanceof Raider illager && illager.getCurrentRaid() != null) {
            VillageManager target = VillageManagerRegistry.get(level.getServer())
                    .findVillageAt(level, illager.getCurrentRaid().getCenter());
            if (target != null) target.raids().adoptChallenge(level, illager);
            return;
        }
        // Only hostile mobs: a cured zombie villager keeps its old raid tag, and must never be sent away.
        if (!(event.getEntity() instanceof PathfinderMob raider)
                || !(raider instanceof net.minecraft.world.entity.monster.Enemy)) return;
        RaidSpawner.Tagged tag = RaidSpawner.tagOf(raider);
        if (tag == null) return;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).get(tag.villageId());
        // Its village is not here (yet): leave it be, the raid picks it up when it ticks.
        if (village == null) return;
        if (village.raids().adopt(raider, tag) == RaidManager.Adoption.STALE) PENDING_DISCARDS.add(raider);
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        Entity entity = event.getEntity();
        if (!(entity.level() instanceof ServerLevel level) || level.getServer() == null) return;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        RaidSpawner.Tagged tag = RaidSpawner.tagOf(entity);
        if (tag != null) {
            VillageManager village = registry.get(tag.villageId());
            if (village != null && entity instanceof net.minecraft.world.entity.Mob raider) {
                village.raids().onRaiderDeath(level, raider, event.getSource().getEntity());
            }
            return;
        }
        // A farm animal shot by a rider: the riders' damage to the outskirts.
        // (Not the riders' own horses, hit by a stray arrow.)
        if (entity instanceof net.minecraft.world.entity.animal.Animal && event.getSource().getEntity() != null
                && !RaidSpawner.isRaidHorse(entity)) {
            RaidSpawner.Tagged killer = RaidSpawner.tagOf(event.getSource().getEntity());
            VillageManager village = killer == null ? null : registry.get(killer.villageId());
            if (village != null) village.raids().onAnimalKilled();
            return;
        }
        if (entity instanceof NpcEntity npc) {
            for (VillageManager village : registry.all()) {
                if (village.getLevel() == level && village.raids().inProgress()) {
                    village.raids().onVillagerDeath(npc.getNpcId());
                }
            }
        }
    }

    /**
     * Emeralds in a raider's own loot (an evoker's, say) belong to the
     * village it raided, like the emeralds the raid itself drops.
     */
    @SubscribeEvent
    public static void onRaiderDrops(net.neoforged.neoforge.event.entity.living.LivingDropsEvent event) {
        Entity dead = event.getEntity();
        if (!(dead.level() instanceof ServerLevel level) || level.getServer() == null) return;
        RaidSpawner.Tagged tag = RaidSpawner.tagOf(dead);
        if (tag == null) return;
        // Our banner leaders are patrol leaders, so Minecraft counts them as captains and its
        // loot gives each an ominous bottle; its own raid leaders drop none, and ours neither.
        if (RaidSpawner.isOwnRaider(dead)) {
            event.getDrops().removeIf(drop -> drop.getItem().is(net.minecraft.world.item.Items.OMINOUS_BOTTLE));
        }
        VillageManager village = VillageManagerRegistry.get(level.getServer()).get(tag.villageId());
        if (village == null) return;
        for (net.minecraft.world.entity.item.ItemEntity drop : event.getDrops()) {
            if (drop.getItem().is(net.minecraft.world.item.Items.EMERALD)) {
                drop.setItem(RaidEmeralds.stamp(drop.getItem(), village));
            }
        }
    }

    /**
     * Right clicking a captive the kidnappers hold: untie it (when no
     * kidnapper is near). Its villager screen does not open meanwhile.
     */
    @SubscribeEvent
    public static void onEntityInteract(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof NpcEntity npc)
                || !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
                || !(player.level() instanceof ServerLevel level) || level.getServer() == null) return;
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() != level || !village.raids().isCaptive(npc)) continue;
            if (event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
                village.raids().tryRescue(level, player, npc);
            }
            event.setCanceled(true);
            event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
            return;
        }
    }

    /** A guard, captain or archer hurt by a raider fought in that raid. */
    @SubscribeEvent
    public static void onDefenderHurt(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post event) {
        if (!(event.getEntity() instanceof NpcEntity npc) || !npc.getProfession().isDefender()) return;
        if (!(npc.level() instanceof ServerLevel level) || level.getServer() == null) return;
        Entity attacker = event.getSource().getEntity();
        RaidSpawner.Tagged tag = attacker == null ? null : RaidSpawner.tagOf(attacker);
        if (tag == null) return;
        VillageManager village = VillageManagerRegistry.get(level.getServer()).get(tag.villageId());
        if (village != null) village.raids().onDefenderHurt(npc);
    }

    /**
     * A witch's potion never does the village's people good: a Bad Omen raid's
     * witches heal their raiders with real splash potions, which caught the
     * guards and villagers fighting beside them too. (Instant healing is not
     * an applied effect and still splashes; regeneration, the usual one, is
     * stopped.)
     */
    @SubscribeEvent
    public static void onEffectApplicable(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable event) {
        if (event.getEntity() instanceof NpcEntity
                && event.getEffectSource() instanceof net.minecraft.world.entity.monster.Witch
                && event.getEffectInstance().getEffect().value().isBeneficial()) {
            event.setResult(net.neoforged.neoforge.event.entity.living.MobEffectEvent.Applicable.Result.DO_NOT_APPLY);
        }
    }

    /** Ticks between looks at a player's effects (5 seconds). */
    private static final int OMEN_CHECK_TICKS = 100;

    /**
     * A player with Bad Omen inside one of our villages' borders calls the Bad
     * Omen raid (checked every 5 seconds). Our villagers claim no village POIs, so Minecraft
     * rarely turns the omen into a Raid Omen here; when it does (a vanilla
     * villager's POI stands nearby), its raid position is cleared so Minecraft's
     * own raid never starts, and the omen goes to ours.
     */
    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.tickCount % OMEN_CHECK_TICKS != 0
                || player.isSpectator()) return;
        MobEffectInstance badOmen = player.getEffect(MobEffects.BAD_OMEN);
        MobEffectInstance raidOmen = player.getRaidOmenPosition() != null ? player.getEffect(MobEffects.RAID_OMEN) : null;
        if (badOmen == null && raidOmen == null) return;
        ServerLevel level = player.serverLevel();
        if (level.getServer() == null) return;
        VillageManager village = omenVillage(level, player);
        if (village == null) return;
        boolean converted = badOmen == null;
        // A vanilla raid already run here by the takeover keeps its own Raid Omen.
        if (converted && village.raids().isChallengeActive()) return;
        MobEffectInstance omen = converted ? raidOmen : badOmen;
        int omenLevel = RaidOmenMath.clampLevel(omen.getAmplifier() + 1);
        if (converted) player.clearRaidOmenPosition();
        boolean used = village.raids().onBadOmen(level, player, omenLevel);
        // Refused after Minecraft had already turned it into a Raid Omen: the Bad Omen comes back.
        if (!used && converted) {
            player.removeEffect(MobEffects.RAID_OMEN);
            player.addEffect(new MobEffectInstance(MobEffects.BAD_OMEN, 6000, omenLevel - 1));
        }
    }

    /** The village a Bad Omen counts for: the first one whose border holds the player. */
    @Nullable
    private static VillageManager omenVillage(ServerLevel level, ServerPlayer player) {
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() != level) continue;
            AABB border = village.borderBounds();
            if (border == null ? village.contains(player.blockPosition())
                    : border.contains(player.getX(), border.minY + 1, player.getZ())) {
                return village;
            }
        }
        return null;
    }

    /** No sleeping in a village under attack, or on the evening its attack is due (it would skip the raid). */
    @SubscribeEvent
    public static void onCanSleep(net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent event) {
        if (event.getProblem() != null || !(event.getLevel() instanceof ServerLevel level)) return;
        // Any village whose raid this bed is near enough to keep going (its border plus
        // RaidMessages.NEAR_MARGIN), not only the one it stands in: a house just outside
        // the border slept through the horde.
        net.minecraft.core.BlockPos pos = event.getPos();
        for (VillageManager village : VillageManagerRegistry.get(level.getServer()).all()) {
            if (village.getLevel() != level) continue;
            net.minecraft.world.phys.AABB border = village.borderBounds();
            boolean near = border != null
                    ? border.inflate(RaidMessages.NEAR_MARGIN, 0, RaidMessages.NEAR_MARGIN)
                            .contains(pos.getX() + 0.5, border.minY + 1, pos.getZ() + 0.5)
                    : village.contains(pos);
            if (near && village.raids().blocksSleep(level)) {
                event.setProblem(net.minecraft.world.entity.player.Player.BedSleepingProblem.NOT_SAFE);
                return;
            }
        }
    }

    /**
     * A raider never turns into another mob (zombie into drowned, husk into
     * zombie, skeleton into stray): the new mob would carry no raid tag, never
     * be cleaned up, and the raid would count the old one as vanished.
     */
    @SubscribeEvent
    public static void onConversion(net.neoforged.neoforge.event.entity.living.LivingConversionEvent.Pre event) {
        if (event.getEntity() instanceof PathfinderMob raider && RaidSpawner.tagOf(raider) != null) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (PENDING_DISCARDS.isEmpty() || !(event.getLevel() instanceof ServerLevel level)) return;
        if (com.alex.test.village.stuckwatch.StuckWatch.worldHeld(level)) return;
        PENDING_DISCARDS.removeIf(entity -> {
            if (entity.level() != level) return false;
            if (!entity.isRemoved()) {
                // A leftover looter brings its goods back first; a rider takes its raid horse along.
                if (entity instanceof net.minecraft.world.entity.Mob mob && RaidLoot.isCarrying(mob)) {
                    RaidLoot.giveBack(level, mob);
                }
                RaidSpawner.discardWithMount(entity);
            }
            return true;
        });
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PENDING_DISCARDS.clear();
        RaidKidnap.clearCaptives();
        for (VillageManager village : VillageManagerRegistry.get(event.getServer()).all()) {
            village.raids().clearBar();
        }
    }
}
