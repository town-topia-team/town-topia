package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.CraftingStation;
import com.alex.test.village.brain.EveningSchedule;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.building.CauldronClaims;
import com.alex.test.village.building.CauldronIndex;
import com.alex.test.village.building.MuddyCauldrons;
import com.alex.test.village.villagers.farmer.FarmerCauldrons;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.gameevent.GameEvent;

import javax.annotation.Nullable;

/**
 * One farmer fills one cauldron with one bucket, in the order the author set:
 * <ol>
 *   <li>A bucket: a water bucket carried, a water bucket in storage, an empty
 *       bucket carried, an empty bucket in storage, in that order.</li>
 *   <li>At the cauldron, muddy water is tipped out first. Clean water is never
 *       poured on top of muddy water.</li>
 *   <li>Holding water, the farmer pours it straight in. Holding an empty
 *       bucket, it walks to the water, fills it (the source stays) and comes
 *       back to pour.</li>
 *   <li>The empty bucket goes back to storage, unless the farmer is about to
 *       refill another cauldron, or the end-of-shift deposit will take it.</li>
 * </ol>
 *
 * <p>Only one farmer refills a cauldron at a time, and never while someone
 * washes in it: the cauldron is claimed from the moment the farmer reaches it
 * until the water is in. Used for idle-time refills ({@link CauldronRefillWork}),
 * the evening refill before a wash ({@link WashActivity}) and a stranded water
 * bucket.
 */
public final class RefillCauldronActivity extends Activity {

    public enum Purpose { DAY, EVENING, STRANDED }

    private enum Phase { BUCKET, FETCH, TO_CAULDRON, AT_CAULDRON, ACTION, TO_WATER, RETURN }

    private enum Action { TIP, POUR, FILL }

    /** How long tipping, pouring or filling takes. */
    public static final int ACTION_TICKS = 20;
    /** Backstop for the whole job, fetching and returning the bucket included. */
    private static final int TRIP_MAX_TICKS = 4800;
    /** Water sources given up on (dried up on arrival) before the job is. */
    private static final int MAX_WATER_RETRIES = 3;

    private final BlockPos cauldron;
    private final java.util.UUID buildingId;
    private final Purpose purpose;

    private Phase phase = Phase.BUCKET;
    private Action action;
    private FarmerCauldrons.BucketSource source = FarmerCauldrons.BucketSource.NONE;
    @Nullable private FetchFromStorageActivity fetch;
    @Nullable private ReturnBucketActivity giveBack;
    /** The exact tile beside the cauldron the farmer works from. */
    private final CauldronStand tile = new CauldronStand();
    @Nullable private CauldronIndex.WaterSpot water;
    private int waterRetries;
    private int actionTimer;
    private int elapsed;
    private boolean claimed;
    private boolean waitLogged;
    private boolean poured;
    /** Set when the job failed and is only returning the bucket before it ends. */
    @Nullable private String failure;

    public RefillCauldronActivity(BlockPos cauldron, java.util.UUID buildingId, Purpose purpose) {
        this.cauldron = cauldron.immutable();
        this.buildingId = buildingId;
        this.purpose = purpose;
    }

    public BlockPos cauldron() { return cauldron; }

    /** Whether clean water went in. */
    public boolean poured() { return poured; }

    /** Tipping, pouring or filling right now: a few ticks nothing should cut short. */
    public boolean inAction() { return phase == Phase.ACTION; }

    @Override
    protected void start(Context ctx) {
        FarmerCauldrons.forgetRefillCheck(ctx.model().npcId);
        source = FarmerCauldrons.bucketSource(ctx, purpose == Purpose.EVENING);
        diary(ctx, String.format("REFILL start %s lvl=%d muddy=%b bucket=%s (%s)",
                cauldron.toShortString(), CauldronIndex.waterLevel(ctx.level(), cauldron),
                MuddyCauldrons.isMarked(ctx.level(), cauldron), source.label,
                purpose.name().toLowerCase(java.util.Locale.ROOT)));
    }

    @Override
    protected Status tick(Context ctx) {
        if (++elapsed > TRIP_MAX_TICKS) return cancel(ctx, "took too long");
        if (phase != Phase.RETURN && CauldronIndex.waterLevel(ctx.level(), cauldron) < 0) {
            return cancel(ctx, "cauldron gone");
        }
        if (claimed && !CauldronClaims.tryClaim(ctx.level(), cauldron, ctx.model().npcId, CauldronClaims.Kind.REFILL)) {
            // Someone took it over after a lapse: wait at the cauldron again.
            claimed = false;
            if (phase == Phase.ACTION) {
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                phase = Phase.AT_CAULDRON;
            }
        }

        switch (phase) {
            case BUCKET -> {
                switch (source) {
                    case HAND_WATER, HAND_EMPTY -> toCauldron();
                    case STORAGE_WATER -> startFetch(ctx, Items.WATER_BUCKET);
                    case STORAGE_EMPTY -> startFetch(ctx, Items.BUCKET);
                    case NONE -> { return cancel(ctx, "no bucket"); }
                }
            }
            case FETCH -> {
                Status fetched = fetch.tickOuter(ctx);
                if (fetched == Status.RUNNING) return Status.RUNNING;
                fetch.stop(ctx);
                fetch = null;
                if (fetched == Status.FAILED || !carriesAnyBucket(ctx)) return cancel(ctx, "bucket fetch failed");
                toCauldron();
            }
            case TO_CAULDRON -> {
                CauldronStand.Step step = tile.step(ctx, cauldron, buildingId,
                        goal -> moveTo(ctx, goal, VillagerModel.WALK_SPEED), this::resetMove);
                if (step == CauldronStand.Step.WALKING) return Status.RUNNING;
                if (step == CauldronStand.Step.FAILED) return cancel(ctx, tile.failure());
                phase = Phase.AT_CAULDRON;
            }
            case AT_CAULDRON -> {
                tile.keep(ctx);
                CraftingStation.face(ctx.npc(), cauldron);
                if (!claimed) {
                    if (!CauldronClaims.tryClaim(ctx.level(), cauldron, ctx.model().npcId, CauldronClaims.Kind.REFILL)) {
                        if (!waitLogged) {
                            waitLogged = true;
                            diary(ctx, "REFILL wait busy (" + holderName(ctx) + ")");
                        }
                        return Status.RUNNING;
                    }
                    claimed = true;
                }
                return decideAtCauldron(ctx);
            }
            case ACTION -> {
                tile.keep(ctx);
                if (action == Action.FILL) CraftingStation.face(ctx.npc(), water.water());
                else CraftingStation.face(ctx.npc(), cauldron);
                if (--actionTimer > 0) return Status.RUNNING;
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                return finishAction(ctx);
            }
            case TO_WATER -> {
                if (water == null) {
                    water = FarmerCauldrons.findWater(ctx, cauldron);
                    if (water == null) return cancel(ctx, "no water within reach");
                    resetMove();
                }
                if (!moveTo(ctx, water.stand(), VillagerModel.WALK_SPEED)) return Status.RUNNING;
                if (!FarmerCauldrons.isWaterSource(ctx.level(), water.water())) {
                    ctx.mgr().cauldrons().setWaterSource(cauldron, null);
                    water = null;
                    if (++waterRetries > MAX_WATER_RETRIES) return cancel(ctx, "water kept drying up");
                    return Status.RUNNING;
                }
                if (!isClose(ctx.npc().blockPosition(), water.stand())) return cancel(ctx, "could not reach the water");
                startAction(ctx, Action.FILL);
            }
            case RETURN -> {
                if (giveBack == null) {
                    if (!shouldReturnBucket(ctx)) return end();
                    giveBack = new ReturnBucketActivity();
                }
                Status returned = giveBack.tickOuter(ctx);
                if (returned == Status.RUNNING) return Status.RUNNING;
                giveBack.stop(ctx);
                giveBack = null;
                return end();
            }
        }
        return Status.RUNNING;
    }

    /** At the cauldron with the claim: tip muddy water out, pour, or go for water. */
    private Status decideAtCauldron(Context ctx) {
        ServerLevel level = ctx.level();
        int lvl = CauldronIndex.waterLevel(level, cauldron);
        if (lvl > 0 && MuddyCauldrons.isDirtyWater(level, cauldron)) {
            startAction(ctx, Action.TIP);
            return Status.RUNNING;
        }
        if (lvl >= 3) {
            diary(ctx, "REFILL already full " + cauldron.toShortString());
            return toReturn(ctx);
        }
        if (FarmerCauldrons.carriesWaterBucket(ctx.npc())) {
            startAction(ctx, Action.POUR);
            return Status.RUNNING;
        }
        if (FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET) >= 0) {
            // Off to the water: the tile is free for others, and picked again on the way back.
            tile.release(ctx);
            phase = Phase.TO_WATER;
            resetMove();
            return Status.RUNNING;
        }
        return cancel(ctx, "lost the bucket");
    }

    private void startAction(Context ctx, Action next) {
        action = next;
        actionTimer = ACTION_TICKS;
        phase = Phase.ACTION;
        ctx.npc().setAnimation(VillagerAnimationState.CRAFTING);
    }

    private Status finishAction(Context ctx) {
        ServerLevel level = ctx.level();
        switch (action) {
            case TIP -> {
                int was = CauldronIndex.waterLevel(level, cauldron);
                level.setBlockAndUpdate(cauldron, Blocks.CAULDRON.defaultBlockState());
                MuddyCauldrons.clear(level, cauldron);
                level.playSound(null, cauldron, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 0.8f, 0.8f);
                splash(level, cauldron, 20);
                level.gameEvent(ctx.npc(), GameEvent.FLUID_PICKUP, cauldron);
                diary(ctx, "REFILL tipped dirty water lvl=" + was);
                phase = Phase.AT_CAULDRON;
            }
            case POUR -> {
                if (!FarmerCauldrons.pourOutBucket(ctx.npc())) return cancel(ctx, "lost the water bucket");
                MuddyCauldrons.clear(level, cauldron);
                level.setBlockAndUpdate(cauldron, Blocks.WATER_CAULDRON.defaultBlockState()
                        .setValue(LayeredCauldronBlock.LEVEL, 3));
                level.playSound(null, cauldron, SoundEvents.BUCKET_EMPTY, SoundSource.NEUTRAL, 1f, 1f);
                splash(level, cauldron, 12);
                level.gameEvent(ctx.npc(), GameEvent.FLUID_PLACE, cauldron);
                ctx.mgr().cauldrons().markRefilled(cauldron, EveningSchedule.dayIndex(level.getDayTime()));
                poured = true;
                diary(ctx, "REFILL poured → lvl 3 " + cauldron.toShortString());
                return toReturn(ctx);
            }
            case FILL -> {
                if (!FarmerCauldrons.fillBucket(ctx.npc())) return cancel(ctx, "lost the bucket");
                level.playSound(null, water.water(), SoundEvents.BUCKET_FILL, SoundSource.NEUTRAL, 1f, 1f);
                level.gameEvent(ctx.npc(), GameEvent.FLUID_PICKUP, water.water());
                diary(ctx, "REFILL filled at " + water.water().toShortString());
                toCauldron();
                resetMove();
            }
        }
        return Status.RUNNING;
    }

    /** The water is in (or was never needed): free the cauldron, then see to the bucket. */
    private Status toReturn(Context ctx) {
        releaseClaim(ctx);
        tile.release(ctx);
        phase = Phase.RETURN;
        resetMove();
        return Status.RUNNING;
    }

    /**
     * The borrowed empty bucket goes back to storage, except when this farmer
     * is about to refill another cauldron with it (an idle-time chain) or the
     * end-of-shift deposit is still to come and will take it anyway.
     */
    private boolean shouldReturnBucket(Context ctx) {
        if (FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET) < 0) return false;
        if (ctx.brain().eveningPhase(ctx) != EveningSchedule.Phase.DAY) {
            return !ctx.brain().eveningDepositDue();
        }
        if (failure == null && purpose != Purpose.EVENING && FarmerCauldrons.nextNeedy(ctx, cauldron) != null) {
            diary(ctx, "REFILL keeping the bucket for the next cauldron");
            return false;
        }
        return true;
    }

    private Status end() {
        return failure == null ? Status.DONE : Status.FAILED;
    }

    /**
     * Gives up on this cauldron for a while. A borrowed empty bucket still goes
     * back to storage first; anything else ends the job now.
     */
    private Status cancel(Context ctx, String why) {
        diary(ctx, "REFILL cancel " + why + " " + cauldron.toShortString());
        long now = ctx.level().getGameTime();
        ctx.mgr().cauldrons().setRefillCooldown(cauldron, ctx.model().npcId,
                now + FarmerCauldrons.REFILL_COOLDOWN_TICKS);
        releaseClaim(ctx);
        tile.release(ctx);
        if (phase == Phase.ACTION) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (fetch != null) {
            fetch.stop(ctx);
            fetch = null;
        }
        if (failure == null && phase != Phase.RETURN
                && FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET) >= 0) {
            failure = why;
            phase = Phase.RETURN;
            ctx.model().stopMoving();
            resetMove();
            return Status.RUNNING;
        }
        failure = why;
        return Status.FAILED;
    }

    private void toCauldron() {
        phase = Phase.TO_CAULDRON;
        resetMove();
    }

    private void startFetch(Context ctx, net.minecraft.world.item.Item item) {
        fetch = FetchFromStorageActivity.forItem(new ItemStack(item), 1, "fetch_bucket");
        phase = Phase.FETCH;
    }

    private static boolean carriesAnyBucket(Context ctx) {
        return FarmerCauldrons.slotOf(ctx.npc().getInventory(), Items.BUCKET) >= 0
                || FarmerCauldrons.carriesWaterBucket(ctx.npc());
    }

    private void releaseClaim(Context ctx) {
        if (!claimed) return;
        CauldronClaims.release(ctx.level(), cauldron, ctx.model().npcId);
        claimed = false;
    }

    private String holderName(Context ctx) {
        CauldronClaims.Claim claim = CauldronClaims.holder(ctx.level(), cauldron);
        return claim == null ? "nobody" : WashActivity.nameOf(ctx, claim.owner()) + " " + claim.kind().name().toLowerCase(java.util.Locale.ROOT);
    }

    static void splash(ServerLevel level, BlockPos cauldron, int count) {
        level.sendParticles(ParticleTypes.SPLASH, cauldron.getX() + 0.5, cauldron.getY() + 0.95,
                cauldron.getZ() + 0.5, count, 0.25, 0.05, 0.25, 0.1);
    }

    /** Same leeway as the chest walks: within two blocks across, one up or down. */
    static boolean isClose(BlockPos npcPos, BlockPos target) {
        if (Math.abs(npcPos.getY() - target.getY()) > 1) return false;
        double dx = npcPos.getX() - target.getX();
        double dz = npcPos.getZ() - target.getZ();
        return dx * dx + dz * dz <= 4.0;
    }

    private static void diary(Context ctx, String line) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + line);
    }

    @Override
    public void stop(Context ctx) {
        releaseClaim(ctx);
        tile.release(ctx);
        if (fetch != null) {
            fetch.stop(ctx);
            fetch = null;
        }
        if (giveBack != null) {
            giveBack.stop(ctx);
            giveBack = null;
        }
        if (phase == Phase.ACTION) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
        FarmerCauldrons.forgetRefillCheck(ctx.model().npcId);
    }

    @Override
    public String name() {
        return "refill_cauldron";
    }
}
