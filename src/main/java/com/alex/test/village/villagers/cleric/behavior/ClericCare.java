package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.entity.HealingOrbEntity;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerPause;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.cleric.settings.ClericSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Who a cleric looks after, shared by every cleric in the world.
 *
 * <p><b>Choosing a patient.</b> Whoever is closest to dying comes first,
 * measured as the share of health missing, with a push for anyone down to
 * three hearts or less; distance breaks near ties, so a cleric does not cross
 * the village past someone almost as hurt. A guard with extra hearts is
 * weighed by the share it is missing, not the raw amount.
 *
 * <p><b>Skipped for now.</b> A patient this cleric could not walk to is left
 * for a minute, remembered here rather than in the work session so a new
 * session does not walk straight back into the same wall. A patient another
 * cleric is already throwing at is left to that cleric. A patient with a
 * monster close by is not walked to (the cleric would only flee and come
 * back), though it is still healed from where the cleric stands if it is in
 * reach. And a villager with an empty hunger bar is not healed at all: it
 * loses the health again within seconds, so it is brought food instead.
 */
public final class ClericCare {

    /** How long a patient this cleric could not reach is left alone. */
    static final long UNREACHABLE_TICKS = 20L * 60L;
    /** A claim on a patient lapses this long after its cleric last renewed it. */
    private static final long CLAIM_TICKS = 60L;
    /** Visitors, players, pets and golems are looked for this far from the cleric. */
    private static final double SEARCH_RADIUS = 48.0;
    /** A patient with a monster this close is not walked to. */
    static final double DANGER_RADIUS = 10.0;
    /** How much higher or lower than the cleric a patient may be for a throw. */
    private static final double THROW_MAX_DY = 4.0;
    /** Candidates looked at closely (sight, danger, claims) per search. */
    private static final int CLOSE_LOOKS = 6;
    /** Health at or below which a patient counts as close to dying (three hearts). */
    private static final float DYING_HEALTH = 6f;
    /** A patient at or below this share of its health is hurried to. */
    private static final float HURRY_SHARE = 0.6f;
    /** A villager fed by a cleric is not fed again for this long. */
    private static final long FED_AGAIN_TICKS = 20L * 120L;
    /** How much higher another patient must score for a cleric to leave its own for it. */
    private static final double SWITCH_MARGIN = 0.3;
    /** Score added to a guard or archer with Healing Guards First on. */
    private static final double GUARD_FIRST_BONUS = 0.25;

    private record Claim(UUID cleric, long until) {}
    private record PrayerClaim(UUID cleric, long day) {}

    private static final Map<UUID, Claim> CLAIMS = new HashMap<>();
    /** Villagers a cleric is bringing food to or cheering up, so the next cleric picks someone else. */
    private static final Map<UUID, Claim> VISIT_CLAIMS = new HashMap<>();
    /** Which cleric leads each village's morning prayer, by village. */
    private static final Map<UUID, PrayerClaim> PRAYER_CLAIMS = new HashMap<>();
    private static final Map<UUID, Map<UUID, Long>> UNREACHABLE = new HashMap<>();
    private static final Map<UUID, Long> CHEERED_DAY = new HashMap<>();
    private static final Map<UUID, Long> BLESSED_DAY = new HashMap<>();
    private static final Map<UUID, Long> FED_UNTIL = new HashMap<>();

    private ClericCare() {}

    static ProfessionSettings settings(NpcEntity cleric) {
        return ProfessionSettings.of(cleric, ClericSettingsSchema.SCHEMA);
    }

    // =========================================================================
    // Who needs healing
    // =========================================================================

    /**
     * Whether {@code who} is hurt enough, by this cleric's Start Healing
     * choice ({@link #startHealing}), to be healed now.
     */
    static boolean needsHealing(Activity.Context ctx, LivingEntity who, int startHealing, int level) {
        if (who == null || !who.isAlive() || who.isRemoved()) return false;
        if (!mayHeal(ctx.mgr(), who)) return false;
        if (hurtEnough(who, startHealing)) return true;
        return ClericBalance.cleanses(level) && (who.isOnFire() || hasHarmfulEffect(who));
    }

    /**
     * Whether a cleric's heal may reach {@code who}: anyone but a starving
     * villager that has had its {@link com.alex.test.village.stats.VillagerStats#STARVING_HEALS}
     * heals (user 10-19). The orb, the splash, the battle blessing and Guardian
     * Angel all ask this, and all count ({@link #countHeal}).
     */
    static boolean mayHeal(VillageManager mgr, LivingEntity who) {
        if (!(who instanceof NpcEntity npc)) return true;
        VillagerBrain brain = brainOf(mgr, npc);
        return brain == null || brain.mayBeHealedByCleric();
    }

    /** A cleric's heal reached {@code who}: a starving villager uses one of its heals. */
    static void countHeal(VillageManager mgr, LivingEntity who, String what, ServerLevel level) {
        if (!(who instanceof NpcEntity npc)) return;
        VillagerBrain brain = brainOf(mgr, npc);
        if (brain != null) brain.useStarvingHeal(what, level);
    }

    /** Three hearts or less: the "Nearly Dead" line. */
    static boolean nearlyDead(LivingEntity who) {
        return who.getHealth() <= NEARLY_DEAD_HEALTH;
    }

    /** "A Little Hurt": a fifth of its health gone, two hearts for a villager. */
    private static final float A_LITTLE_HURT_SHARE = 0.8f;
    /** "Badly Hurt": half its health or less. */
    private static final float BADLY_HURT_SHARE = 0.5f;
    /** "Nearly Dead": three hearts or less. */
    private static final float NEARLY_DEAD_HEALTH = 6f;

    /**
     * The Start Healing choices, each against the patient's own full health
     * so a guard with extra hearts is judged like anyone else (the old
     * "hurt by 6" was very hurt for a villager and a scratch for such a guard).
     */
    static boolean hurtEnough(LivingEntity who, int startHealing) {
        float health = who.getHealth();
        float max = who.getMaxHealth();
        if (health >= max) return false;
        return switch (startHealing) {
            case ClericSettingsSchema.START_A_LITTLE_HURT -> health <= max * A_LITTLE_HURT_SHARE;
            case ClericSettingsSchema.START_BADLY_HURT -> health <= max * BADLY_HURT_SHARE;
            case ClericSettingsSchema.START_NEARLY_DEAD -> health <= NEARLY_DEAD_HEALTH;
            default -> true;
        };
    }

    /**
     * This cleric's Start Healing choice. A cleric saved with the old "Heal
     * when hurt by" number (half hearts lost, 1 to 12) gets the nearest
     * choice, written once: 1 Any Scratch, 2 to 5 A Little Hurt, 6 to 9
     * Badly Hurt, 10 to 12 Nearly Dead.
     */
    static int startHealing(NpcEntity cleric, ProfessionSettings settings) {
        CompoundTag saved = cleric.getBrainData().getCompound(ClericSettingsSchema.SCHEMA.tagName());
        if (!saved.contains(ClericSettingsSchema.START_HEALING)
                && saved.contains(ClericSettingsSchema.LEGACY_HEAL_THRESHOLD)) {
            int old = saved.getInt(ClericSettingsSchema.LEGACY_HEAL_THRESHOLD);
            int choice = old <= 1 ? ClericSettingsSchema.START_ANY_SCRATCH
                    : old <= 5 ? ClericSettingsSchema.START_A_LITTLE_HURT
                    : old <= 9 ? ClericSettingsSchema.START_BADLY_HURT
                    : ClericSettingsSchema.START_NEARLY_DEAD;
            settings.setInt(ClericSettingsSchema.START_HEALING, choice);
            return choice;
        }
        return settings.getInt(ClericSettingsSchema.START_HEALING);
    }

    private static boolean hasHarmfulEffect(LivingEntity who) {
        for (MobEffectInstance effect : who.getActiveEffects()) {
            if (effect.getEffect().value().getCategory() == MobEffectCategory.HARMFUL) return true;
        }
        return false;
    }

    /** Down to {@link #HURRY_SHARE} of its health or three hearts: worth running for. */
    static boolean badlyHurt(LivingEntity who) {
        return who.getHealth() <= DYING_HEALTH || who.getHealth() <= who.getMaxHealth() * HURRY_SHARE;
    }

    /**
     * The patient this cleric should heal now, or null.
     *
     * @param inReachOnly only patients it can throw at from where it stands
     *                    (sheltering during an alarm)
     */
    @Nullable
    static LivingEntity pickPatient(Activity.Context ctx, ProfessionSettings settings, int level,
                                    boolean inReachOnly) {
        return pickPatient(ctx, settings, level, inReachOnly, false);
    }

    /**
     * @param intoFights a field medic on call ({@link ClericCallOut}): a guard
     *                   or archer with a monster beside it is still walked to
     *                   (to a spot behind it), not left until the fight is over
     */
    @Nullable
    static LivingEntity pickPatient(Activity.Context ctx, ProfessionSettings settings, int level,
                                    boolean inReachOnly, boolean intoFights) {
        int startAt = startHealing(ctx.npc(), settings);
        NpcEntity cleric = ctx.npc();
        long now = ctx.level().getGameTime();
        List<LivingEntity> hurt = new ArrayList<>();
        for (LivingEntity who : candidates(ctx, settings)) {
            if (needsHealing(ctx, who, startAt, level)) hurt.add(who);
        }
        if (hurt.isEmpty()) return null;
        boolean guardsFirst = settings.getBool(ClericSettingsSchema.GUARDS_FIRST);
        hurt.sort(Comparator.comparingDouble((LivingEntity who) -> score(cleric, who, guardsFirst)).reversed());
        int looks = 0;
        for (LivingEntity who : hurt) {
            // Taken and unreachable patients are skipped before the count: in a
            // raid with several clerics they filled the close looks, and the
            // next hurt villager down was never looked at.
            if (isUnreachable(cleric, who, now) || claimedByOther(cleric, who, now)) continue;
            if (looks++ >= CLOSE_LOOKS) break;
            boolean inReach = inThrowRange(cleric, who);
            if (inReachOnly && !inReach) continue;
            if (!inReach && !(intoFights && isDefender(who)) && dangerNear(ctx.level(), who)) continue;
            return who;
        }
        return null;
    }

    /** Everyone the cleric may heal: villagers, other people in town, and players and animals by setting. */
    private static List<LivingEntity> candidates(Activity.Context ctx, ProfessionSettings settings) {
        NpcEntity cleric = ctx.npc();
        List<LivingEntity> out = new ArrayList<>();
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc != null && npc != cleric && npc.isAlive() && !npc.isRemoved() && !isPreview(npc)) out.add(npc);
        }
        ServerLevel level = ctx.level();
        AABB box = cleric.getBoundingBox().inflate(SEARCH_RADIUS, 12.0, SEARCH_RADIUS);
        // The merchant, nomads and the Mayor: people in town who are not on its roll.
        for (NpcEntity other : level.getEntitiesOfClass(NpcEntity.class, box,
                e -> e != cleric && e.isAlive() && !isPreview(e)
                        && (e.getNpcId() == null || ctx.mgr().getModel(e.getNpcId()) == null)
                        && ctx.mgr().contains(e.blockPosition()))) {
            out.add(other);
        }
        if (settings.getBool(ClericSettingsSchema.HEAL_PLAYERS)) {
            for (ServerPlayer player : level.players()) {
                if (player.isAlive() && !player.isSpectator() && !player.isCreative()
                        && player.distanceToSqr(cleric) <= SEARCH_RADIUS * SEARCH_RADIUS
                        && ctx.mgr().contains(player.blockPosition())) {
                    out.add(player);
                }
            }
        }
        if (settings.getBool(ClericSettingsSchema.HEAL_ANIMALS)) {
            out.addAll(level.getEntitiesOfClass(LivingEntity.class, box,
                    e -> e.isAlive() && isPetOrGolem(e) && ctx.mgr().contains(e.blockPosition())));
        }
        return out;
    }

    /** Command and screen puppets: never real people. */
    static boolean isPreview(NpcEntity npc) {
        return npc.isNoAi() || npc.isProfessionAnimationPreview()
                || npc.getTags().contains(NpcEntity.MOCAP_PREVIEW_TAG)
                || npc.getTags().contains(NpcEntity.THOUGHT_PREVIEW_TAG);
    }

    static boolean isPetOrGolem(Entity e) {
        return (e instanceof TamableAnimal tame && tame.isTame())
                || (e instanceof AbstractHorse horse && horse.isTamed())
                || e instanceof IronGolem || e instanceof SnowGolem;
    }

    /**
     * Who a splash may heal besides the patient, by the same rules as choosing one.
     * A starving villager only with heals left and health to gain: at full health
     * the splash would use one of its three for nothing.
     */
    static Predicate<LivingEntity> splashWho(VillageManager mgr, ProfessionSettings settings) {
        boolean players = settings.getBool(ClericSettingsSchema.HEAL_PLAYERS);
        boolean animals = settings.getBool(ClericSettingsSchema.HEAL_ANIMALS);
        return e -> (e instanceof NpcEntity npc && !isPreview(npc) && splashMayHeal(mgr, npc))
                || (players && e instanceof Player player && !player.isSpectator())
                || (animals && isPetOrGolem(e));
    }

    private static boolean splashMayHeal(VillageManager mgr, NpcEntity npc) {
        VillagerBrain brain = brainOf(mgr, npc);
        if (brain == null || !brain.isStarving()) return true;
        return brain.mayBeHealedByCleric() && npc.getHealth() < npc.getMaxHealth();
    }

    /**
     * Whether {@code other} is so much worse off than the patient the cleric
     * is on now that it should leave that one for it: someone down to three
     * hearts against a scratch, not one a little more hurt. The margin keeps
     * it from turning back and forth between two.
     */
    static boolean clearlyWorse(NpcEntity cleric, LivingEntity other, LivingEntity patient, boolean guardsFirst) {
        return score(cleric, other, guardsFirst) >= score(cleric, patient, guardsFirst) + SWITCH_MARGIN;
    }

    /** A guard, captain or archer: someone who fights for the village. */
    static boolean isDefender(LivingEntity who) {
        return who instanceof NpcEntity npc && npc.getProfession().isDefender();
    }

    private static double score(NpcEntity cleric, LivingEntity who, boolean guardsFirst) {
        double missing = 1.0 - who.getHealth() / Math.max(1f, who.getMaxHealth());
        double dying = who.getHealth() <= DYING_HEALTH ? 0.35 : 0.0;
        // Villagers and people before pets, on an otherwise equal footing.
        double people = who instanceof NpcEntity || who instanceof Player ? 0.05 : 0.0;
        double cleanseOnly = missing <= 0.0 ? 0.1 : 0.0;
        // Healing Guards First: a defender jumps ahead of a villager about as
        // hurt, though not ahead of one much closer to dying.
        double defender = guardsFirst && isDefender(who) ? GUARD_FIRST_BONUS : 0.0;
        return missing + dying + people + cleanseOnly + defender - Math.sqrt(cleric.distanceToSqr(who)) * 0.004;
    }

    /** Close enough, level enough and in plain sight: the orb can be thrown from here. */
    static boolean inThrowRange(NpcEntity cleric, LivingEntity who) {
        double range = ClericBalance.CAST_RANGE;
        return cleric.distanceToSqr(who) <= range * range
                && Math.abs(who.getY() - cleric.getY()) <= THROW_MAX_DY
                && cleric.hasLineOfSight(who);
    }

    /** Whether a monster is close to {@code who}. */
    static boolean dangerNear(ServerLevel level, LivingEntity who) {
        AABB box = who.getBoundingBox().inflate(DANGER_RADIUS, 4.0, DANGER_RADIUS);
        return !level.getEntitiesOfClass(Mob.class, box, m -> m instanceof Enemy && m.isAlive()).isEmpty();
    }

    @Nullable
    static LivingEntity resolve(Activity.Context ctx, @Nullable UUID id) {
        if (id == null) return null;
        Entity entity = ctx.level().getEntity(id);
        return entity instanceof LivingEntity living && living.isAlive() && !living.isRemoved() ? living : null;
    }

    @Nullable
    static VillagerBrain brainOf(VillageManager mgr, NpcEntity npc) {
        if (npc == null || npc.getNpcId() == null) return null;
        VillagerModel model = mgr.getModel(npc.getNpcId());
        return model != null && model.puppet() == npc ? model.brain() : null;
    }

    /** The name shown over its head, or what it is. */
    static String nameOf(LivingEntity who) {
        if (who instanceof NpcEntity npc && npc.givenName() != null) return npc.givenName();
        return who.getName().getString();
    }

    // =========================================================================
    // Claims and the unreachable list
    // =========================================================================

    static void claim(NpcEntity cleric, LivingEntity patient, long now) {
        CLAIMS.put(patient.getUUID(), new Claim(cleric.getUUID(), now + CLAIM_TICKS));
        if (CLAIMS.size() > 256) CLAIMS.values().removeIf(c -> VillagerPause.lapsed(c.cleric(), c.until(), now));
    }

    static void release(NpcEntity cleric, @Nullable UUID patient) {
        if (patient == null) return;
        Claim claim = CLAIMS.get(patient);
        if (claim != null && claim.cleric().equals(cleric.getUUID())) CLAIMS.remove(patient);
    }

    static boolean claimedByOther(NpcEntity cleric, LivingEntity patient, long now) {
        Claim claim = CLAIMS.get(patient.getUUID());
        return claim != null && !VillagerPause.lapsed(claim.cleric(), claim.until(), now)
                && !claim.cleric().equals(cleric.getUUID());
    }

    /**
     * Books a villager this cleric is bringing food to or cheering up: the
     * saddest villager was every cleric's pick, so they all walked to it and
     * all but one stood about and gave up. Renewed every tick of the visit.
     */
    static void claimVisit(NpcEntity cleric, NpcEntity who, long now) {
        VISIT_CLAIMS.put(who.getUUID(), new Claim(cleric.getUUID(), now + CLAIM_TICKS));
        if (VISIT_CLAIMS.size() > 256) VISIT_CLAIMS.values().removeIf(c -> VillagerPause.lapsed(c.cleric(), c.until(), now));
    }

    static void releaseVisit(NpcEntity cleric, UUID who) {
        Claim claim = VISIT_CLAIMS.get(who);
        if (claim != null && claim.cleric().equals(cleric.getUUID())) VISIT_CLAIMS.remove(who);
    }

    private static boolean visitedByOther(NpcEntity cleric, NpcEntity who, long now) {
        Claim claim = VISIT_CLAIMS.get(who.getUUID());
        return claim != null && !VillagerPause.lapsed(claim.cleric(), claim.until(), now)
                && claim.until() - now <= CLAIM_TICKS
                && !claim.cleric().equals(cleric.getUUID());
    }

    /**
     * Whether another cleric of this village leads today's morning prayer.
     * One prayer a village is enough: everyone is blessed once a day, so a
     * second cleric praying beside the first blessed nobody.
     */
    static boolean prayerTakenByOther(UUID village, NpcEntity cleric, long day) {
        PrayerClaim claim = PRAYER_CLAIMS.get(village);
        return claim != null && claim.day() == day && !claim.cleric().equals(cleric.getUUID());
    }

    /** This cleric sets out to lead the village's prayer today. */
    static void claimPrayer(UUID village, NpcEntity cleric, long day) {
        PRAYER_CLAIMS.put(village, new PrayerClaim(cleric.getUUID(), day));
    }

    /** Called away before it began to read: another cleric may still lead it. */
    static void releasePrayer(UUID village, NpcEntity cleric) {
        PrayerClaim claim = PRAYER_CLAIMS.get(village);
        if (claim != null && claim.cleric().equals(cleric.getUUID())) PRAYER_CLAIMS.remove(village);
    }

    static void markUnreachable(NpcEntity cleric, UUID patient, long now, long ticks) {
        Map<UUID, Long> mine = UNREACHABLE.computeIfAbsent(cleric.getUUID(), k -> new HashMap<>());
        mine.put(patient, now + ticks);
        if (mine.size() > 64) mine.values().removeIf(until -> until < now);
    }

    static boolean isUnreachable(NpcEntity cleric, LivingEntity patient, long now) {
        return isUnreachable(cleric, patient.getUUID(), now);
    }

    static boolean isUnreachable(NpcEntity cleric, UUID patient, long now) {
        Map<UUID, Long> mine = UNREACHABLE.get(cleric.getUUID());
        if (mine == null) return false;
        Long until = mine.get(patient);
        if (until == null) return false;
        if (until <= now || until - now > UNREACHABLE_TICKS * 10) {
            mine.remove(patient);
            return false;
        }
        return true;
    }

    // =========================================================================
    // Food and kind words
    // =========================================================================

    /** The villager most in need of food this cleric could bring it, or null. */
    @Nullable
    static NpcEntity pickFoodRecipient(Activity.Context ctx, boolean starvingOnly) {
        NpcEntity cleric = ctx.npc();
        long now = ctx.level().getGameTime();
        NpcEntity best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            // Not one asleep: a night-shift cleric walked up to a bed and waited.
            if (npc == null || npc == cleric || !npc.isAlive() || isPreview(npc) || npc.isSleeping()) continue;
            if (isUnreachable(cleric, npc, now) || recentlyFed(npc, now) || visitedByOther(cleric, npc, now)) {
                continue;
            }
            VillagerBrain brain = model.brain();
            if (!brain.wantsFoodBrought(npc)) continue;
            boolean starving = brain.isStarving();
            if (starvingOnly && !starving) continue;
            double score = (starving ? 100.0 : 0.0) - Math.sqrt(cleric.distanceToSqr(npc));
            if (score > bestScore) {
                bestScore = score;
                best = npc;
            }
        }
        return best;
    }

    static boolean recentlyFed(NpcEntity npc, long now) {
        Long until = FED_UNTIL.get(npc.getUUID());
        return until != null && until > now && until - now <= FED_AGAIN_TICKS;
    }

    static void markFed(NpcEntity npc, long now) {
        FED_UNTIL.put(npc.getUUID(), now + FED_AGAIN_TICKS);
        if (FED_UNTIL.size() > 256) FED_UNTIL.values().removeIf(until -> until < now);
    }

    /**
     * The saddest villager nobody has cheered up today, or null. Never one
     * asleep, eating, talking, running away or sheltering: those are not the
     * moment for a chat.
     */
    @Nullable
    static NpcEntity pickSadVillager(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        long now = ctx.level().getGameTime();
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        NpcEntity best = null;
        float lowest = Float.MAX_VALUE;
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || npc == cleric || !npc.isAlive() || isPreview(npc) || npc.isSleeping()) continue;
            if (isUnreachable(cleric, npc, now) || cheeredToday(npc, day) || visitedByOther(cleric, npc, now)) {
                continue;
            }
            VillagerBrain brain = model.brain();
            float wellbeing = brain.needs().wellbeing;
            if (wellbeing > WellbeingSystem.SAD_THRESHOLD || !brain.isFreeToChat()) continue;
            if (wellbeing < lowest) {
                lowest = wellbeing;
                best = npc;
            }
        }
        return best;
    }

    static boolean cheeredToday(NpcEntity npc, long day) {
        Long cheered = CHEERED_DAY.get(npc.getUUID());
        return cheered != null && cheered == day;
    }

    static void markCheered(NpcEntity npc, long day) {
        CHEERED_DAY.put(npc.getUUID(), day);
        if (CHEERED_DAY.size() > 256) CHEERED_DAY.values().removeIf(d -> d < day);
    }

    /** Whether {@code npc} has had the morning blessing today; marks it if not. */
    static boolean blessOncePerDay(NpcEntity npc, long day) {
        Long blessed = BLESSED_DAY.get(npc.getUUID());
        if (blessed != null && blessed == day) return false;
        BLESSED_DAY.put(npc.getUUID(), day);
        if (BLESSED_DAY.size() > 256) {
            for (Iterator<Long> it = BLESSED_DAY.values().iterator(); it.hasNext(); ) {
                if (it.next() < day) it.remove();
            }
        }
        return true;
    }

    // =========================================================================
    // The orb
    // =========================================================================

    static HealingOrbEntity.Blessing blessing(int level) {
        return new HealingOrbEntity.Blessing(ClericBalance.healAmount(level),
                ClericBalance.cleanses(level), ClericBalance.renews(level), ClericBalance.splashes(level),
                ClericBalance.SPLASH_RADIUS, ClericBalance.SPLASH_SHARE, ClericBalance.RENEWAL_TICKS);
    }
}
