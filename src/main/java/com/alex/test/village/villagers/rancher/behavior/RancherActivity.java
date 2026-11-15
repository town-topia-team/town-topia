package com.alex.test.village.villagers.rancher.behavior;

import com.alex.test.LegacyModId;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.TickStagger;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.economy.StockReserves;
import com.alex.test.village.villagers.rancher.RanchAnimals;
import com.alex.test.village.villagers.rancher.RanchBreedTurns;
import com.alex.test.village.villagers.rancher.RanchClaims;
import com.alex.test.village.villagers.rancher.RanchHappenings;
import com.alex.test.village.villagers.rancher.RanchRoundUp;
import com.alex.test.village.villagers.rancher.RancherBalance;
import com.alex.test.village.villagers.butcher.lead.BringBackStrayActivity;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.butcher.lead.LedAnimals;
import com.alex.test.village.villagers.butcher.lead.RanchStrays;
import com.alex.test.village.villagers.tools.GetToolActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import com.alex.test.village.villagers.supplies.BucketNeeds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Rancher's working day: keep the pens stocked. Shear woolly sheep, milk
 * cows, gather laid eggs, comb loose hide off cows, and feed breeding pairs
 * whenever a species is under the pen's capacity, letting vanilla love-mode
 * do the rest.
 *
 * <p>Targets are entities, not blocks, so every job re-resolves its animal by
 * UUID each tick — animals wander, despawn-protect, or get culled by the
 * butcher between selection and arrival.
 */
public final class RancherActivity extends WorkActivity {

    /** How close (squared) the rancher must be to touch an animal or item. */
    private static final double REACH_SQ = 6.25;

    /** Give up on one animal after this many walks that found no way to it. */
    private static final int MAX_CHASE_REPATHS = 4;

    /**
     * Give up on one animal still out of reach after this long: 30 s. A fresh
     * walk after a wandering animal is not a failure; counting those gave up
     * on any animal that kept moving.
     */
    private static final long CHASE_LIMIT_TICKS = 600L;

    /** Breeding food carried per storage trip. */
    private static final int FOOD_PER_FETCH = 8;

    /** How far (squared) an animal in love looks for its mate: 8 blocks (vanilla {@code BreedGoal}). */
    private static final double MATE_RANGE_SQ = 8.0 * 8.0;
    /** Further apart than this (6 blocks) a fed pair is walked together: a margin inside the mate range. */
    private static final double MATE_MEET_SQ = 6.0 * 6.0;

    /** Empty buckets carried per storage trip — each is spent 1:1 on one milking. */
    private static final int BUCKETS_PER_FETCH = 4;
    /** The bucket trip's activity name, told apart from the breeding food trip in {@link #busy}. */
    private static final String FETCH_BUCKET = "fetch_bucket";

    /** How often (ticks) each rancher sweeps lapsed claims out of {@link RanchClaims}. */
    private static final int CLAIM_PRUNE_PERIOD = 1200;

    /**
     * NBT key on the cow's own {@link Entity#getPersistentData()} recording the
     * game time of its last hide shed. Stored on the animal (not in a map here)
     * so the cooldown survives chunk unload/reload exactly like the animal does.
     */
    private static final String SHED_NBT_KEY = "dynamicvillages_last_shed_tick";

    /**
     * Minimum ticks between hide sheds per cow (one Minecraft day). Leather
     * from a living cow isn't a vanilla mechanic — this models a cow shedding
     * loose hide that the rancher combs off, never killing the animal, but is
     * throttled hard so a single cow can't become a leather farm.
     */
    public static final long SHED_INTERVAL_TICKS = 24000L;
    private static final String EGG_NBT_KEY = "dynamicvillages_last_owned_egg_tick";
    /** Timers saved before the mod id rename. */
    private static final String LEGACY_SHED_NBT_KEY = LegacyModId.OLD + "_last_shed_tick";
    private static final String LEGACY_EGG_NBT_KEY = LegacyModId.OLD + "_last_owned_egg_tick";
    /** Base ticks between owned eggs per chicken; Egg Finder shortens it (RancherBalance). */
    public static final long EGG_INTERVAL_TICKS = RancherBalance.EGG_INTERVAL_TICKS;
    /**
     * NBT key on a baby's own persistent data: the game time a Good Breeder
     * last sped up its growing. Shared by every rancher, so two of them never
     * count the same time twice.
     */
    private static final String GROW_NBT_KEY = "dynamicvillages_last_grow_boost_tick";
    /** How often (ticks) a Good Breeder speeds up the babies in its pens. */
    private static final int GROW_BOOST_PERIOD = 1200;
    /** The most time one boost counts, so a pen left alone for days is not grown up in one go. */
    private static final long GROW_BOOST_MAX_SPAN = 24000L;

    private enum JobType { SHEAR, FEED, MILK, EGG, SHED }

    private static final class Job {
        final JobType type;
        final Entity target;
        /** The pen the animal was picked in, for its recent happenings. */
        final RanchBuilding ranch;
        int repaths;
        /** {@link Context#clock()} when the chase began, or -1 while the animal was in reach. */
        long chaseSince = -1L;
        /** The second animal of a breeding pair: a Master Rancher feeds it nothing. */
        boolean partner;
        /** For the second animal of a pair: the first, already fed. */
        @Nullable Animal mate;

        Job(JobType type, Entity target, RanchBuilding ranch) {
            this.type = type;
            this.target = target;
            this.ranch = ranch;
        }
    }

    @Nullable private Job job;
    @Nullable private MoveActivity move;
    @Nullable private BlockPos chaseDest;
    @Nullable private Activity sub;
    private boolean finishAfterSub;
    private int actTimer;
    private boolean workedThisSession;
    private final TickStagger.RetryGate chooseGate = new TickStagger.RetryGate();
    private int actionsThisSession;

    /** Second half of a breeding pair, fed immediately after the first. */
    private final Deque<Animal> feedQueue = new ArrayDeque<>();

    /** Animals this rancher holds a {@link RanchClaims} claim on: the job's target and a queued partner. */
    private final Set<UUID> claimed = new HashSet<>();

    // =========================================================================
    // WorkActivity contract
    // =========================================================================

    @Override
    protected Status doTick(Context ctx) {
        Status status = tickWork(ctx);
        syncClaims(ctx);
        if (RancherBalance.goodBreeder(level(ctx))
                && TickStagger.isSlot(ctx.level().getGameTime(), ctx.npc().getId(), GROW_BOOST_PERIOD)) {
            boostBabies(ctx);
        }
        return status;
    }

    private Status tickWork(Context ctx) {
        if (!ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA)
                .getBool(RancherSettingsSchema.WORK_ENABLED)) {
            return Status.DONE;
        }

        if (sub != null) {
            Status status = sub.tickOuter(ctx);
            if (status == Status.RUNNING) return Status.RUNNING;
            sub.stop(ctx);
            // An animal home in the pen, led or walked in by itself, as the butcher counts it.
            if (status == Status.DONE && sub instanceof BringBackStrayActivity) {
                ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.STRAY, RancherBalance.STRAY_JOBS);
            }
            sub = null;
            if (finishAfterSub) return Status.DONE;
        }

        if (move != null) {
            Status status = move.tickOuter(ctx);
            if (status == Status.RUNNING) {
                // Chase correction: the ANIMAL wandering away from where this
                // walk is headed means the leg is stale — re-path. (Comparing
                // the npc to the animal instead is always true at chase start
                // and cancels every walk instantly.) Also stop when another
                // rancher finished this animal meanwhile; tickJob then drops it.
                if (job != null && job.target.isAlive()
                        && (!stillWanted(ctx, job)
                            || chaseDest != null && job.target.blockPosition().distSqr(chaseDest) > 25.0)) {
                    move.stop(ctx);
                    move = null;
                }
                return Status.RUNNING;
            }
            move.stop(ctx);
            move = null;
            if (status == Status.FAILED && job != null) {
                diary(ctx, String.format("RANCH move FAILED npc=%s target=%s dist=%.1f repaths=%d",
                        ctx.npc().blockPosition().toShortString(),
                        job.target.blockPosition().toShortString(),
                        Math.sqrt(ctx.npc().distanceToSqr(job.target)), job.repaths));
                if (++job.repaths > MAX_CHASE_REPATHS) {
                    diary(ctx, "RANCH unreachable " + describe(job));
                    giveUpOn(ctx, job);
                    job = null;
                }
            }
            return Status.RUNNING;
        }

        if (job != null) {
            return tickJob(ctx);
        }

        return chooseNext(ctx);
    }

    @Override
    public void doStop(Context ctx) {
        if (sub != null) { sub.stop(ctx); sub = null; }
        if (move != null) { move.stop(ctx); move = null; }
        feedQueue.clear();
        releaseClaims(ctx);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null;
    }

    @Override
    public boolean allowsRoutineInterruption() {
        if (sub instanceof BringBackStrayActivity stray && stray.isLeading()) return false;
        return actTimer <= 0;
    }

    // =========================================================================
    // Job execution
    // =========================================================================

    private Status tickJob(Context ctx) {
        Entity target = job.target;
        if (!target.isAlive()) {
            if (actTimer > 0) endAction(ctx);
            job = null;
            actTimer = 0;
            return Status.RUNNING;
        }

        // Checked every tick, so a second rancher arriving at the same
        // animal, or still mid-action when the first finishes, gets no
        // second egg or hide and wastes no food on an animal already in love.
        if (!stillWanted(ctx, job)) {
            diary(ctx, "RANCH no longer needed " + describe(job));
            if (job.type == JobType.FEED) feedQueue.clear();
            if (actTimer > 0) {
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            }
            job = null;
            actTimer = 0;
            chooseGate.progressed();
            return Status.RUNNING;
        }

        double distSqr = ctx.npc().distanceToSqr(target);
        if (distSqr > REACH_SQ) {
            long now = ctx.clock(); // a pause for a player's screen is not chasing
            if (job.chaseSince < 0L) job.chaseSince = now;
            if (now - job.chaseSince > CHASE_LIMIT_TICKS) {
                diary(ctx, "RANCH gave up chasing " + describe(job));
                giveUpOn(ctx, job);
                job = null;
                return Status.RUNNING;
            }
            // Wandered off mid-action: out of the work pose before running after it.
            if (actTimer > 0) endAction(ctx);
            actTimer = 0;
            chaseDest = target.blockPosition();
            move = new MoveActivity(chaseDest, 0.6f);
            return Status.RUNNING;
        }

        if (actTimer == 0) {
            actTimer = RancherBalance.actTicks(level(ctx));
            if (job.type == JobType.FEED) equipFood(ctx, (Animal) target);
            else showJobItem(ctx, job.type);
            // From its first frame, paced so the whole swing lands before the job ends.
            ctx.npc().startWorkAnimation(VillagerAnimationState.CHOP, RancherBalance.clipSpeed(level(ctx),
                    VillagerAnimationState.cycleTicks(VillagerAnimationState.CHOP)));
        }
        ctx.npc().getLookControl().setLookAt(target, 30f, 30f);
        holdStill((Animal) target);

        if (--actTimer > 0) return Status.RUNNING;

        Job finished = job;
        switch (finished.type) {
            case SHEAR -> performShear(ctx, (Animal) target);
            case FEED -> performFeed(ctx, (Animal) target); // may queue the breeding partner as the next job
            case MILK -> performMilk(ctx, (Cow) target);
            case EGG -> performLayEgg(ctx, (Animal) target);
            case SHED -> performShed(ctx, (Animal) target);
        }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        // performFeed may have replaced `job` with the partner — feeding it
        // immediately is what makes both animals hit love mode together.
        if (job == finished) job = null;
        workedThisSession = true;
        chooseGate.progressed();
        actionsThisSession++;
        return Status.RUNNING;
    }

    /**
     * Ends a shear, feed or milk part-way: the work pose off and the hand
     * empty. The work clip holds the legs still, so a rancher whose animal
     * wandered off mid-action ran after it in that pose and glided, its legs
     * never walking, until it caught up.
     */
    private void endAction(Context ctx) {
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /**
     * The animal waits for the swing, this tick only: no stroll off mid-job,
     * which cut the swing and started it again. Nothing is saved, so the
     * animal is free the first tick the job is over or the rancher stops
     * (done, interrupted, paused for a player's screen). One that was hurt,
     * is burning or freezing, or is on a lead goes its own way at once.
     */
    private static void holdStill(Animal animal) {
        if (animal.hurtTime > 0 || animal.getLastHurtByMob() != null
                || animal.isOnFire() || animal.isFreezing()
                || animal.isLeashed() || LedAnimals.isLed(animal)) return;
        animal.getNavigation().stop();
        animal.setSpeed(0f);
        animal.xxa = 0f;
        animal.zza = 0f;
        Vec3 movement = animal.getDeltaMovement();
        animal.setDeltaMovement(0.0, movement.y, 0.0);
    }

    private void performShear(Context ctx, Animal target) {
        if (!(target instanceof Sheep sheep) || !sheep.readyForShearing()) return;
        // A full pack leaves the sheep woolly rather than shearing wool into nothing.
        int room = roomFor(ctx, RanchAnimals.woolFor(sheep));
        if (room <= 0) return;
        sheep.setSheared(true);
        // Skill adds a chance of one more wool to vanilla's 1-3.
        int sheared = 1 + ctx.level().random.nextInt(3)
                + (ctx.level().random.nextDouble() < RancherBalance.extraWoolChance(level(ctx)) ? 1 : 0);
        int wool = Math.min(room, sheared);
        ctx.npc().getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(RanchAnimals.woolFor(sheep), wool), ctx.mgr()));
        ctx.level().playSound(null, sheep.blockPosition(), SoundEvents.SHEEP_SHEAR,
                SoundSource.NEUTRAL, 1.0f, 1.0f);
        diary(ctx, "RANCH sheared sheep -> " + wool + " wool");
        ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.SHEAR, 1f);
        noteDone(ctx, RanchHappenings.Kind.WOOL, sheep, wool);
    }

    /** A line in the job's pen's recent happenings, for the Ranch window. */
    private void noteDone(Context ctx, RanchHappenings.Kind kind, Entity target, int amount) {
        if (job == null) return;
        RanchHappenings.note(job.ranch, kind, VillageManager.displayName(ctx.model()), target.getType(), amount,
                ctx.level().getGameTime());
    }

    private void performFeed(Context ctx, Animal target) {
        if (RanchAnimals.breedFoodFor(target.getType()) == null) return;
        // A Master Rancher sets the partner going without a second helping.
        boolean free = job != null && job.partner && RancherBalance.masterRancher(level(ctx));
        Item food = carriedFeed(ctx, target.getType());
        if (!free && (food == null || !consumeItem(ctx, food))) return;
        target.setInLove(null);
        ctx.level().playSound(null, target.blockPosition(), SoundEvents.GENERIC_EAT,
                SoundSource.NEUTRAL, 0.8f, 1.0f);
        diary(ctx, "RANCH fed " + target.getType().getDescription().getString()
                + (free ? " (no food: Master Rancher)" : " with " + food.getDescriptionId()));
        ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.FEED, RancherBalance.FEED_JOBS);
        // Both of the pair fed: one line, at the second.
        if (job != null && job.partner) {
            noteDone(ctx, RanchHappenings.Kind.BRED, target, 0);
            bringTogether(job.mate, target);
        }

        // Feed the partner right away so both hit love mode together.
        Animal partner = feedQueue.poll();
        if (partner != null && partner.isAlive() && job != null) {
            job = new Job(JobType.FEED, partner, job.ranch);
            job.partner = true;
            job.mate = target;
        }
    }

    /**
     * A fed pair too far apart to find each other walks together. An animal
     * in love looks only {@link #MATE_RANGE_SQ} far for its mate, and with no
     * player within 32 blocks an animal does not stroll at all, so a pair that
     * stood at two ends of a pen stayed there: fed again and again once the
     * love ran out, never a calf, the wheat gone. Within sight of each
     * other vanilla's own breeding takes over.
     */
    private static void bringTogether(@Nullable Animal first, Animal second) {
        if (first == null || !first.isAlive() || !first.isInLove() || first == second) return;
        if (first.distanceToSqr(second) <= MATE_MEET_SQ) return;
        first.getNavigation().moveTo(second, 1.0);
        second.getNavigation().moveTo(first, 1.0);
    }

    /**
     * Vanilla's own milking mechanic (see {@code Cow.mobInteract}): an empty
     * bucket becomes a milk bucket 1:1, consumed on every use, no cooldown.
     * We mirror that exactly instead of inventing a timer — the bucket supply
     * itself is what limits how much a rancher can milk per trip.
     */
    private void performMilk(Context ctx, Cow cow) {
        // Milk wanted is counted afresh: the next cow waits until the cook still wants more.
        if (BucketNeeds.milkWanted(ctx.mgr(), ctx.level()) <= 0) return;
        // Checked before the bucket is spent, or a full pack lost both.
        if (roomFor(ctx, Items.MILK_BUCKET) <= 0) return;
        if (!consumeItem(ctx, Items.BUCKET)) return;
        ctx.npc().getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.MILK_BUCKET), ctx.mgr()));
        BucketNeeds.forget(ctx.mgr());
        ctx.level().playSound(null, cow.blockPosition(), SoundEvents.COW_MILK,
                SoundSource.NEUTRAL, 1.0f, 1.0f);
        diary(ctx, "RANCH milked cow -> 1 milk bucket");
        ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.MILK, 1f);
        noteDone(ctx, RanchHappenings.Kind.MILK, cow, 1);
    }

    /** A direct ranch production event avoids claiming eggs thrown by players. */
    private void performLayEgg(Context ctx, Animal chicken) {
        ItemStack egg = com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.EGG), ctx.mgr());
        if (!ctx.npc().getInventory().addItem(egg)) return;
        markEggCollected(chicken, ctx.level().getGameTime());
        // The other four jobs each make a sound; a silent egg read as a failed try.
        ctx.level().playSound(null, chicken.blockPosition(), SoundEvents.CHICKEN_EGG,
                SoundSource.NEUTRAL, 1.0f, 1.0f);
        diary(ctx, "RANCH collected 1 owned egg from chicken");
        ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.EGG, 1f);
        noteDone(ctx, RanchHappenings.Kind.EGG, chicken, 1);
    }

    private void performShed(Context ctx, Animal cow) {
        // The cow's day-long timer only starts once the leather is really in the pack.
        if (!ctx.npc().getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.LEATHER), ctx.mgr()))) return;
        markShed(cow, ctx.level().getGameTime());
        // Fine Hides: sometimes a second leather, when it fits.
        int hides = 1;
        if (ctx.level().random.nextDouble() < RancherBalance.secondHideChance(level(ctx))
                && ctx.npc().getInventory().addItem(com.alex.test.village.tokens.VillageItemOwnership.produce(
                        new ItemStack(Items.LEATHER), ctx.mgr()))) {
            hides = 2;
        }
        ctx.level().playSound(null, cow.blockPosition(), SoundEvents.COW_AMBIENT,
                SoundSource.NEUTRAL, 1.0f, 1.0f);
        diary(ctx, "RANCH combed shed hide from cow -> " + hides + " leather");
        ctx.brain().onRancherJob(ctx, VillagerBrain.RancherJob.HIDE, 1f);
        noteDone(ctx, RanchHappenings.Kind.HIDE, cow, hides);
    }

    /** This rancher's skill level. */
    private static int level(Context ctx) {
        return ctx.brain().rancherSkillLevel();
    }

    /**
     * Good Breeder: every baby in this rancher's pens grows up a quarter of
     * the time since it was last sped up faster, so it is grown in about four
     * fifths of vanilla's time. The time is stamped on the baby, so a second
     * rancher never counts it again; the first sighting only starts the clock.
     */
    private static void boostBabies(Context ctx) {
        long now = ctx.level().getGameTime();
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            for (Animal animal : ranch.animalsInside(ctx.level())) {
                if (!animal.isBaby() || animal.getAge() >= 0) continue;
                CompoundTag data = animal.getPersistentData();
                if (data.contains(GROW_NBT_KEY)) {
                    long span = Math.min(GROW_BOOST_MAX_SPAN, Math.max(0L, now - data.getLong(GROW_NBT_KEY)));
                    int boost = (int) Math.round(span * RancherBalance.GROW_BOOST);
                    if (boost > 0) animal.setAge(Math.min(0, animal.getAge() + boost));
                }
                data.putLong(GROW_NBT_KEY, now);
            }
        }
    }

    /**
     * After the away catch-up: babies did not grow while nobody was near
     * (their chunks were unloaded), so the Good Breeder owes them nothing for
     * that time. Restarts every pen baby's clock at now.
     */
    public static void restartGrowClocks(net.minecraft.server.level.ServerLevel level,
                                         com.alex.test.village.VillageManager mgr) {
        long now = level.getGameTime();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            for (Animal animal : ranch.animalsInside(level)) {
                CompoundTag data = animal.getPersistentData();
                if (animal.isBaby() && data.contains(GROW_NBT_KEY)) data.putLong(GROW_NBT_KEY, now);
            }
        }
    }

    // =========================================================================
    // Job selection
    // =========================================================================

    /** Actions before a rancher calls it a shift and banks what it gathered. */
    private static final int ACTIONS_PER_SESSION = 16;
    /** Carried produce that is worth an early trip to storage on its own. */
    private static final int DEPOSIT_LOAD = 4;

    private Status chooseNext(Context ctx) {
        if (!chooseGate.tryPass(ctx.level().getGameTime(), ctx.npc().getId())) return Status.RUNNING;
        // Either a full shift, or simply having enough in hand to be worth the
        // walk. The action count alone is not enough: tending is slow and the
        // brain interrupts a rancher to eat and sleep long before 16 of them,
        // so produce accumulated across interrupted shifts and never reached
        // the chest at all.
        if (actionsThisSession >= ACTIONS_PER_SESSION
                || DepositActivity.countDepositable(ctx) >= DEPOSIT_LOAD) {
            return depositOrFinish(ctx);
        }

        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
        boolean productCollectionEnabled = settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED);
        boolean breedingEnabled = settings.getBool(RancherSettingsSchema.BREEDING_ENABLED);

        // A pen animal that got out comes before everything else, then a
        // loose one to round up while the pen has room.
        Activity leadJob = leadJob(ctx, settings);
        if (leadJob != null) {
            sub = leadJob;
            finishAfterSub = false;
            workedThisSession = true;
            return Status.RUNNING;
        }

        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            // Species the player has opted this rancher out of (settings ->
            // Animals) are excluded from every job below, breeding included.
            List<Animal> animals = allowedAnimals(ranch, ctx, settings);

            if (productCollectionEnabled) {
                // Shearing first: guaranteed yield, no consumables.
                if (hasShears(ctx)) {
                    for (Animal animal : animals) {
                        if (animal instanceof Sheep sheep && sheep.readyForShearing() && free(ctx, animal)
                                && roomFor(ctx, RanchAnimals.woolFor(sheep)) > 0) {
                            job = new Job(JobType.SHEAR, sheep, ranch);
                            return Status.RUNNING;
                        }
                    }
                }

                // Produce an owned egg at a persisted per-chicken interval.
                if (isAllowedSpecies(settings, EntityType.CHICKEN)) {
                    for (Animal animal : animals) {
                        if (readyToLayEgg(ctx, animal) && free(ctx, animal)
                                && roomFor(ctx, Items.EGG) > 0) {
                            job = new Job(JobType.EGG, animal, ranch);
                            return Status.RUNNING;
                        }
                    }
                }

                // Shed hide: free, but gated by the per-cow cooldown.
                long gameTime = ctx.level().getGameTime();
                for (Animal animal : animals) {
                    if (readyToShed(animal, gameTime) && free(ctx, animal)
                            && roomFor(ctx, Items.LEATHER) > 0) {
                        job = new Job(JobType.SHED, animal, ranch);
                        return Status.RUNNING;
                    }
                }

                // Milking: needs an empty bucket, so it follows the same
                // fetch-from-storage pattern as breeding food below. Only
                // while the cook wants milk for a cake: the bucket is used up
                // in it, and milk nobody bakes with only fills a chest.
                Cow milkTarget = null;
                for (Animal animal : animals) {
                    if (animal instanceof Cow cow && !cow.isBaby() && free(ctx, animal)) { milkTarget = cow; break; }
                }
                int milkWanted = milkTarget == null ? 0 : BucketNeeds.milkWanted(ctx.mgr(), ctx.level());
                if (milkWanted > 0 && roomFor(ctx, Items.MILK_BUCKET) > 0) {
                    if (countItem(ctx, Items.BUCKET) >= 1) {
                        job = new Job(JobType.MILK, milkTarget, ranch);
                        return Status.RUNNING;
                    }
                    int fetchable = bucketsFetchable(ctx);
                    if (fetchable > 0) {
                        sub = FetchFromStorageActivity.forItem(new ItemStack(Items.BUCKET),
                                Math.min(BUCKETS_PER_FETCH, Math.min(milkWanted, fetchable)), FETCH_BUCKET);
                        finishAfterSub = false;
                        return Status.RUNNING;
                    }
                }
            }

            if (breedingEnabled) {
                // Breeding: pick an under-capacity species with two ready adults.
                Map<EntityType<?>, List<Animal>> ready = new HashMap<>();
                Map<EntityType<?>, Integer> counts = new HashMap<>();
                for (Animal animal : animals) {
                    counts.merge(animal.getType(), halfHeads(ctx, animal), Integer::sum);
                    if (!animal.isBaby() && animal.getAge() == 0 && !animal.isInLove() && free(ctx, animal)) {
                        ready.computeIfAbsent(animal.getType(), t -> new ArrayList<>()).add(animal);
                    }
                }
                // The kinds take turns, the longest waiting first (RanchBreedTurns).
                for (EntityType<?> kind : RanchBreedTurns.inTurn(ranch, ready.keySet(), counts)) {
                    List<Animal> adults = ready.get(kind);
                    if (adults.size() < 2) continue;
                    if (counts.getOrDefault(kind, 0) >= 2 * ranch.capacityPerSpecies()) continue;
                    if (RanchAnimals.breedFoodFor(kind) == null) continue;

                    if (carriedFeedCount(ctx, kind) >= RancherBalance.foodPerPair(level(ctx))) {
                        Animal[] pair = closePair(ctx, adults);
                        feedQueue.clear();
                        feedQueue.add(pair[1]);
                        job = new Job(JobType.FEED, pair[0], ranch);
                        RanchBreedTurns.picked(ranch, kind, ctx.level().getGameTime());
                        return Status.RUNNING;
                    }
                    // A pair takes two: one in storage and none carried is a wasted trip.
                    Item food = feedToFetch(ctx, kind);
                    if (food != null) {
                        sub = FetchFromStorageActivity.forItem(
                                new ItemStack(food), FOOD_PER_FETCH, "fetch_breed_food");
                        finishAfterSub = false;
                        return Status.RUNNING;
                    }
                }
            }
        }

        return depositOrFinish(ctx);
    }

    /**
     * The pair to feed, the one to feed first at [0]: two close enough to find
     * each other once in love (vanilla looks 8 blocks for a mate, for 30 s),
     * the shortest walk among those, the nearer one first. With no two that
     * close, the two closest. The first two in the pen's list were often far
     * apart: the first fell in love, the second was chased until the love ran
     * out ("gave up chasing FEED"), and the food was wasted.
     */
    private static Animal[] closePair(Context ctx, List<Animal> adults) {
        double[] toRancher = new double[adults.size()];
        for (int i = 0; i < adults.size(); i++) toRancher[i] = Math.sqrt(ctx.npc().distanceToSqr(adults.get(i)));
        int bestFirst = 0;
        int bestSecond = 1;
        boolean bestClose = false;
        double bestScore = Double.MAX_VALUE;
        for (int i = 0; i < adults.size(); i++) {
            for (int j = i + 1; j < adults.size(); j++) {
                double apartSqr = adults.get(i).distanceToSqr(adults.get(j));
                boolean close = apartSqr <= MATE_RANGE_SQ;
                if (bestClose && !close) continue;
                double apart = Math.sqrt(apartSqr);
                double score = close ? Math.min(toRancher[i], toRancher[j]) + apart : apart;
                if (close == bestClose && score >= bestScore) continue;
                bestClose = close;
                bestScore = score;
                bestFirst = toRancher[i] <= toRancher[j] ? i : j;
                bestSecond = bestFirst == i ? j : i;
            }
        }
        return new Animal[] { adults.get(bestFirst), adults.get(bestSecond) };
    }

    /**
     * Bringing a stray home, else rounding up a loose animal (Rounding Up on),
     * or first fetching the lead for it; null when there is nothing to fetch
     * or no lead to be had. Only species the Managed animals list allows.
     */
    @Nullable
    private static Activity leadJob(Context ctx, ProfessionSettings settings) {
        LeadSupply.giveOnceToOldHire(ctx.npc());
        RanchStrays.Stray stray = RanchStrays.strayFor(ctx, animal -> isAllowedSpecies(settings, animal.getType()));
        boolean roundUp = false;
        if (stray == null) {
            stray = RanchRoundUp.candidateFor(ctx, settings);
            roundUp = stray != null;
        }
        if (stray == null) return null;
        if (LeadSupply.hasLead(ctx.npc())) {
            return roundUp
                    ? BringBackStrayActivity.roundUp(stray.ranch(), stray.animal(),
                            settings.getInt(RancherSettingsSchema.ROUNDUP_RANGE))
                    : new BringBackStrayActivity(stray.ranch(), stray.animal());
        }
        return GetToolActivity.canAcquire(ctx, LeadSupply.REQUIREMENT)
                ? new GetToolActivity(LeadSupply.REQUIREMENT) : null;
    }

    private Status depositOrFinish(Context ctx) {
        actionsThisSession = 0;
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            finishAfterSub = true;
            return Status.RUNNING;
        }
        return workedThisSession ? Status.DONE : Status.FAILED;
    }

    // =========================================================================
    // Inventory helpers
    // =========================================================================

    private static boolean hasShears(Context ctx) {
        return hasShears(ctx.npc());
    }

    /** Shears this villager may use: wool duty needs them, live and while nobody is near. */
    public static boolean hasShears(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        for (ItemStack stack : inv.getSlots())
            if (stack.is(Items.SHEARS) && inv.mayUse(stack)
                    && com.alex.test.village.villagers.tools.ToolSelectionHelper.isUsableByDefault(stack)) return true;
        return false;
    }

    private static int countItem(Context ctx, Item item) {
        int total = 0;
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUse(stack) && stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    private static boolean consumeItem(Context ctx, Item item) {
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (inv.mayUse(stack) && stack.getItem() == item) {
                return inv.reduceSlot(i, 1);
            }
        }
        return false;
    }

    /** The kind's breeding food to feed next: the first of {@code breedFoodsFor} the pack holds, or null. */
    @Nullable
    private static Item carriedFeed(Context ctx, EntityType<?> kind) {
        for (Item food : RanchAnimals.breedFoodsFor(kind)) {
            if (countItem(ctx, food) > 0) return food;
        }
        return null;
    }

    /** All the kind's breeding foods in the pack, together: one pair may eat two different ones. */
    public static int carriedFeedCount(Context ctx, EntityType<?> kind) {
        int count = 0;
        for (Item food : RanchAnimals.breedFoodsFor(kind)) count += countItem(ctx, food);
        return count;
    }

    /**
     * The kind's breeding food worth a storage trip: the first of
     * {@code breedFoodsFor} that, with what is carried, makes a pair's worth
     * (so wheat seeds before any other seed); null when none does.
     */
    @Nullable
    public static Item feedToFetch(Context ctx, EntityType<?> kind) {
        int carried = carriedFeedCount(ctx, kind);
        int perPair = RancherBalance.foodPerPair(level(ctx));
        for (Item food : RanchAnimals.breedFoodsFor(kind)) {
            if (carried + fetchableCount(ctx, food) >= perPair) return food;
        }
        return null;
    }

    private void equipFood(Context ctx, Animal target) {
        Item food = carriedFeed(ctx, target.getType());
        if (food == null) {
            ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        InventoryManager inv = ctx.npc().getInventory();
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.is(food) && inv.mayUse(stack)) {
                ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
                return;
            }
        }
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    /**
     * The shears or the bucket in hand while shearing or milking: a display
     * copy that can never drop, never the pack's own. With an empty hand the
     * work swing at a sheep or cow read as a swing at nothing. The end of the
     * job empties the hand; the time limit is only a safety net.
     */
    private void showJobItem(Context ctx, JobType type) {
        Item item = switch (type) {
            case SHEAR -> Items.SHEARS;
            case MILK -> Items.BUCKET;
            default -> null;
        };
        if (item == null) return;
        InventoryManager inv = ctx.npc().getInventory();
        for (ItemStack stack : inv.getSlots()) {
            if (stack.is(item) && inv.mayUse(stack)
                    && (item != Items.SHEARS
                        || com.alex.test.village.villagers.tools.ToolSelectionHelper.isUsableByDefault(stack))) {
                ctx.npc().holdDisplayItem(stack, ctx.level().getGameTime() + actTimer + 20L);
                return;
            }
        }
    }

    /** Animals in {@code ranch} whose species the settings allow-list still permits. */
    private static List<Animal> allowedAnimals(RanchBuilding ranch, Context ctx, ProfessionSettings settings) {
        List<Animal> all = ranch.animalsInside(ctx.level());
        List<Animal> allowed = new ArrayList<>(all.size());
        for (Animal animal : all) {
            if (isAllowedSpecies(settings, animal.getType())) allowed.add(animal);
        }
        return allowed;
    }

    public static boolean isAllowedSpecies(ProfessionSettings settings, EntityType<?> type) {
        return settings.isAllowed(
                RancherSettingsSchema.ALLOWED_ANIMAL_TYPES, EntityType.getKey(type).toString());
    }

    /**
     * Units of {@code item} a storage trip could actually bring back: what the
     * rooms hold, cut to what the Stock tab leaves free and what the pack has
     * room for. The fetch itself checks both, so counting the raw stock sent
     * ranchers on a trip that failed at once, over and over, standing still.
     */
    /**
     * Empty buckets this rancher may take for milking: what storage holds for
     * it, never the ones set aside for the farmers' wash cauldrons.
     */
    private static int bucketsFetchable(Context ctx) {
        return Math.min(fetchableCount(ctx, Items.BUCKET), BucketNeeds.rancherMayTake(ctx.mgr(), ctx.level()));
    }

    private static int fetchableCount(Context ctx, Item item) {
        int stored = 0;
        for (Building building : ctx.mgr().getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) {
                stored += storage.countItem(ctx.level(), item);
            }
        }
        if (stored <= 0) return 0;
        int free = StockReserves.get(ctx.level().getServer()).free(ctx.mgr(), ctx.level(), item,
                ctx.npc().getProfession());
        // Room for the village's own, which is what the fetch brings. A plain sample has room only
        // for what a villager may use unbound (a player's food): carrots and potatoes had room,
        // wheat and seeds never did, so only pigs were ever bred from storage.
        ItemStack sample = new ItemStack(item);
        com.alex.test.village.tokens.VillageTokenBinding.bind(sample, ctx.mgr());
        int room = ctx.npc().getInventory().capacityFor(sample);
        return Math.min(stored, Math.min(free, room));
    }

    // =========================================================================
    // Ranch scanning helpers
    // =========================================================================

    /** Whether a grown chicken in this rancher's care is due its next owned egg (Egg Finder shortens the wait). */
    private static boolean readyToLayEgg(Context ctx, Animal animal) {
        if (animal.getType() != EntityType.CHICKEN || animal.isBaby()) return false;
        CompoundTag data = animal.getPersistentData();
        LegacyModId.renameKey(data, LEGACY_EGG_NBT_KEY, EGG_NBT_KEY);
        return !data.contains(EGG_NBT_KEY)
                || ctx.level().getGameTime() - data.getLong(EGG_NBT_KEY)
                        >= RancherBalance.eggIntervalTicks(level(ctx));
    }

    /** Starts the chicken's egg interval over, as a collected egg does. */
    public static void markEggCollected(Animal chicken, long gameTime) {
        chicken.getPersistentData().putLong(EGG_NBT_KEY, gameTime);
    }

    /** Starts the cow's day-long hide interval over, as a combed hide does. */
    public static void markShed(Animal cow, long gameTime) {
        cow.getPersistentData().putLong(SHED_NBT_KEY, gameTime);
    }

    /** Game time of the chicken's last owned egg, or null when it never gave one (ready). For the away calculator. */
    @Nullable
    public static Long lastEggTick(Animal chicken) {
        CompoundTag data = chicken.getPersistentData();
        LegacyModId.renameKey(data, LEGACY_EGG_NBT_KEY, EGG_NBT_KEY);
        return data.contains(EGG_NBT_KEY) ? data.getLong(EGG_NBT_KEY) : null;
    }

    /** Game time of the cow's last combed hide, or null when it never shed one (ready). For the away calculator. */
    @Nullable
    public static Long lastShedTick(Animal cow) {
        CompoundTag data = cow.getPersistentData();
        LegacyModId.renameKey(data, LEGACY_SHED_NBT_KEY, SHED_NBT_KEY);
        return data.contains(SHED_NBT_KEY) ? data.getLong(SHED_NBT_KEY) : null;
    }

    private static boolean readyToShed(Animal animal, long gameTime) {
        if (!(animal instanceof Cow) || animal.isBaby()) return false;
        CompoundTag data = animal.getPersistentData();
        LegacyModId.renameKey(data, LEGACY_SHED_NBT_KEY, SHED_NBT_KEY);
        if (!data.contains(SHED_NBT_KEY)) return true; // never sheded yet
        return gameTime - data.getLong(SHED_NBT_KEY) >= SHED_INTERVAL_TICKS;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * How many of {@code item}, as the village-owned stack a ranch job makes,
     * still fit in the pack. Jobs that would produce into a full pack are not
     * picked: the wool, hide or milk used to vanish, and an egg that never fit
     * kept the rancher swinging at the same chicken forever.
     */
    private static int roomFor(Context ctx, Item item) {
        ItemStack owned = com.alex.test.village.tokens.VillageItemOwnership.produce(new ItemStack(item), ctx.mgr());
        return owned.isEmpty() ? 0 : ctx.npc().getInventory().capacityFor(owned);
    }

    /** Whether the job's animal still needs it: another rancher may have got there first. */
    private static boolean stillWanted(Context ctx, Job job) {
        long gameTime = ctx.level().getGameTime();
        if (!(job.target instanceof Animal animal)) return false;
        return switch (job.type) {
            case SHEAR -> animal instanceof Sheep sheep && sheep.readyForShearing();
            case EGG -> readyToLayEgg(ctx, animal);
            case SHED -> readyToShed(animal, gameTime);
            case FEED -> !animal.isBaby() && animal.getAge() == 0 && !animal.isInLove();
            case MILK -> true; // performMilk re-counts the milk still wanted
        };
    }

    /**
     * One animal's share of the pen's headcount, in halves: 2 for itself, plus
     * 1 when it is a parent-to-be (in love, or claimed by another rancher who
     * may be feeding it), since every two of those make one baby. Counting
     * only heads let two ranchers each feed a pair when one place was left.
     */
    public static int halfHeads(Context ctx, Animal animal) {
        if (animal.isBaby()) return 2;
        boolean parentToBe = animal.isInLove() || RanchClaims.isClaimedByOther(
                animal.getUUID(), ctx.npc().getUUID(), ctx.level().getGameTime());
        return parentToBe ? 3 : 2;
    }

    /** No other rancher is on this animal, and this one has not given up on it lately. */
    public static boolean free(Context ctx, Animal animal) {
        // An animal on a lead is on its way somewhere: never shear, milk or feed it.
        if (LedAnimals.isLed(animal)) return false;
        return RanchClaims.isFree(animal.getUUID(), ctx.npc().getUUID(), ctx.level().getGameTime());
    }

    /** Leaves an animal this rancher could not reach alone for a while, so it picks another. */
    private void giveUpOn(Context ctx, Job gaveUp) {
        long now = ctx.level().getGameTime();
        RanchClaims.markUnreachable(ctx.npc().getUUID(), gaveUp.target.getUUID(), now);
        if (gaveUp.type == JobType.FEED) {
            // The pair's other half is no use alone; let another rancher have it.
            feedQueue.clear();
        }
    }

    /** Holds claims on exactly the job's animal and the queued partner, refreshing them each tick. */
    private void syncClaims(Context ctx) {
        Set<UUID> wanted = new HashSet<>();
        if (job != null && job.target.isAlive()) wanted.add(job.target.getUUID());
        for (Animal partner : feedQueue) wanted.add(partner.getUUID());
        if (wanted.isEmpty() && claimed.isEmpty()) return;

        UUID me = ctx.npc().getUUID();
        long now = ctx.level().getGameTime();
        for (UUID animal : claimed) {
            if (!wanted.contains(animal)) RanchClaims.release(animal, me);
        }
        for (UUID animal : wanted) RanchClaims.claim(animal, me, now);
        claimed.clear();
        claimed.addAll(wanted);
        if (TickStagger.isSlot(now, ctx.npc().getId(), CLAIM_PRUNE_PERIOD)) {
            RanchClaims.prune(now);
        }
    }

    private void releaseClaims(Context ctx) {
        UUID me = ctx.npc().getUUID();
        for (UUID animal : claimed) RanchClaims.release(animal, me);
        claimed.clear();
    }

    private static String describe(Job job) {
        return job.type + ":" + job.target.getType().getDescription().getString();
    }

    /** What a Rancher at work is busy with, for the Ranch window. */
    public enum Busy {
        LOOKING, SHEAR, FEED, MILK, EGG, SHED, BRING_BACK, ROUND_UP, GET_LEAD, FETCH_FOOD, FETCH_BUCKETS, PUT_AWAY
    }

    /** The trip or job under way now; LOOKING between them. Reads only. */
    public Busy busy() {
        if (sub instanceof BringBackStrayActivity stray) {
            return "round_up".equals(stray.name()) ? Busy.ROUND_UP : Busy.BRING_BACK;
        }
        if (sub instanceof GetToolActivity) return Busy.GET_LEAD;
        if (sub instanceof FetchFromStorageActivity fetch) {
            return FETCH_BUCKET.equals(fetch.name()) ? Busy.FETCH_BUCKETS : Busy.FETCH_FOOD;
        }
        if (sub instanceof DepositActivity) return Busy.PUT_AWAY;
        if (job == null) return Busy.LOOKING;
        return switch (job.type) {
            case SHEAR -> Busy.SHEAR;
            case FEED -> Busy.FEED;
            case MILK -> Busy.MILK;
            case EGG -> Busy.EGG;
            case SHED -> Busy.SHED;
        };
    }

    /** The animal the job under way is for (the first of a breeding pair), else null. */
    @Nullable public Entity busyWith() {
        return job == null ? null : job.target;
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** Cheap pre-check for brain scoring. */
    public static boolean hasWork(Context ctx) {
        ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
        if (!settings.getBool(RancherSettingsSchema.WORK_ENABLED)) return false;

        boolean productCollectionEnabled = settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED);
        boolean breedingEnabled = settings.getBool(RancherSettingsSchema.BREEDING_ENABLED);
        if (leadJob(ctx, settings) != null) return true;
        if (!productCollectionEnabled && !breedingEnabled) return false;

        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof RanchBuilding ranch) || !ranch.isValid()) continue;
            List<Animal> animals = allowedAnimals(ranch, ctx, settings);

            if (productCollectionEnabled) {
                if (hasShears(ctx)) {
                    for (Animal animal : animals) {
                        if (animal instanceof Sheep sheep && sheep.readyForShearing() && free(ctx, animal)
                                && roomFor(ctx, RanchAnimals.woolFor(sheep)) > 0) return true;
                    }
                }

                if (isAllowedSpecies(settings, EntityType.CHICKEN))
                    for (Animal animal : animals)
                        if (readyToLayEgg(ctx, animal) && free(ctx, animal)
                                && roomFor(ctx, Items.EGG) > 0) return true;

                long gameTime = ctx.level().getGameTime();
                for (Animal animal : animals) {
                    if (readyToShed(animal, gameTime) && free(ctx, animal)
                            && roomFor(ctx, Items.LEATHER) > 0) return true;
                }

                boolean hasCow = false;
                for (Animal animal : animals) {
                    if (animal instanceof Cow cow && !cow.isBaby() && free(ctx, animal)) { hasCow = true; break; }
                }
                if (hasCow && (countItem(ctx, Items.BUCKET) >= 1 || bucketsFetchable(ctx) > 0)
                        && roomFor(ctx, Items.MILK_BUCKET) > 0
                        && BucketNeeds.milkWanted(ctx.mgr(), ctx.level()) > 0) {
                    return true;
                }
            }

            if (breedingEnabled) {
                Map<EntityType<?>, Integer> readyCount = new HashMap<>();
                Map<EntityType<?>, Integer> counts = new HashMap<>();
                for (Animal animal : animals) {
                    counts.merge(animal.getType(), halfHeads(ctx, animal), Integer::sum);
                    if (!animal.isBaby() && animal.getAge() == 0 && !animal.isInLove() && free(ctx, animal)) {
                        readyCount.merge(animal.getType(), 1, Integer::sum);
                    }
                }
                for (Map.Entry<EntityType<?>, Integer> entry : readyCount.entrySet()) {
                    if (entry.getValue() < 2) continue;
                    if (counts.getOrDefault(entry.getKey(), 0) >= 2 * ranch.capacityPerSpecies()) continue;
                    EntityType<?> kind = entry.getKey();
                    if (RanchAnimals.breedFoodFor(kind) == null) continue;
                    if (carriedFeedCount(ctx, kind) >= RancherBalance.foodPerPair(level(ctx))
                            || feedToFetch(ctx, kind) != null) return true;
                }
            }
        }
        return false;
    }
}
