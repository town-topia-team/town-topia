package com.alex.test.village.villagers.farmer.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.animation.WorkAnimationStagger;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ItemLossLog;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.FetchFromStorageActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.TickStagger;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.brain.WorkActivity;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.farmer.FarmerHoeUtils;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.farmer.FarmerAxeUtils;
import com.alex.test.village.villagers.farmer.FarmerBoneMeal;
import com.alex.test.village.villagers.farmer.FarmerCompost;
import com.alex.test.village.villagers.farmer.FarmerPerennials;
import com.alex.test.village.villagers.farmer.FarmerDirt;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.farmer.FarmerSeedCrafting;
import com.alex.test.village.villagers.farmer.FarmerShearsUtils;
import com.alex.test.village.villagers.farmer.crops.CropDefinition;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.alex.test.village.villagers.tools.ToolSpeed;
import com.alex.test.village.villagers.tools.GetToolActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight executor for jobs planned and reserved by {@link FarmerManager}.
 * Discovery, crop policy, farm selection and retry ownership deliberately live
 * in the village-owned manager rather than in each farmer activity.
 */
public final class FarmerActivity extends WorkActivity {

    private static final int SEED_CRAFT_RETRY_TICKS = 600;
    private static final int SEED_CRAFT_FAILURE_BACKOFF_TICKS = 6000;
    /** From this farmer level on, tilling plays the skilled clip (down on one knee, long pulls). */
    private static final int SKILLED_TILL_LEVEL = 50;
    /** From this farmer level on, sweet berries are picked with the quick reach; below it, carefully past the thorns. */
    private static final int SKILLED_PICK_LEVEL = 50;
    /** The last step onto the tilling tile is walked from at most this far (squared, blocks). */
    private static final double TILL_STEP_MAX_SQ = 1.6 * 1.6;
    /** Placed on the tile's middle from at most this far (squared, blocks). */
    private static final double TILL_SNAP_MAX_SQ = 0.6 * 0.6;
    /**
     * How far from a glow berry vine or cocoa pod the farmer stands to pick it,
     * across the ground from the middle of the fruit. The walk there may end up
     * to a block short, so a last straight step brings it in. Where 0.8 has no
     * room, a little further out is tried before keeping the walk's own spot.
     */
    private static final double PICK_DISTANCE = 0.8;
    /** Fruit two blocks up is picked from closer in, under it, so the high reach meets it. */
    private static final double PICK_DISTANCE_HIGH = 0.5;
    private static final double PICK_MAX_DISTANCE = 1.3;
    private static final double PICK_DISTANCE_STEP = 0.1;
    private static final int PICK_DIRECTIONS = 16;
    /** Already this close to the picking distance: no step needed. */
    private static final double PICK_CLOSE_ENOUGH = 0.1;
    private static final double PICK_STEP_DONE_SQ = 0.15 * 0.15;
    private static final int PICK_STEP_MAX_TICKS = 40;
    /** How often the claim on the job in hand is renewed; well inside its 30 s lifetime. */
    private static final int CLAIM_RENEW_TICKS = 100;

    @Nullable private FarmerManager.Job job;
    @Nullable private MoveActivity move;
    /** The last step up to a vine or pod, while it is being walked. */
    @Nullable private Vec3 pickPoint;
    private int pickStepTicks;
    @Nullable private Activity sub;
    private boolean supplySub;
    private boolean seedCraftSub;
    @Nullable private Item seedCraftItem;
    private boolean depositSub;
    private boolean finishAfterDeposit;
    private boolean hoeSub;
    private boolean shearsSub;
    private boolean shearsAttempted;
    private boolean axeAttempted;
    private long lastSeenToolStorageChangeId;
    private int workTimer;
    /** Items still to drop into the composter this job, waits spent on a full one, and whether anything happened. */
    private int compostLeft;
    private int compostWaits;
    private boolean compostDone;
    private int animationStartDelay;
    private int actions;
    private boolean worked;
    private long nextClaimRenewAt;
    private final TickStagger.RetryGate chooseGate = new TickStagger.RetryGate();

    @Override
    protected void start(Context ctx) {
        if (!FarmerSettings.of(ctx.npc()).works()) {
            ctx.mgr().farmers().release(ctx.model().npcId);
        }
    }

    @Override
    protected Status doTick(Context ctx) {
        if (!FarmerSettings.of(ctx.npc()).works()) {
            if (sub != null && depositSub) return tickSubActivity(ctx);
            cancelActiveWork(ctx);
            return depositOrFinish(ctx, true);
        }
        keepClaim(ctx);
        if (sub != null) return tickSubActivity(ctx);
        if (move != null) return tickMovement(ctx);
        if (pickPoint != null) {
            tickPickStep(ctx);
            return Status.RUNNING;
        }
        if (workTimer > 0) {
            tickWork(ctx);
            return Status.RUNNING;
        }
        return chooseNext(ctx);
    }

    private Status tickSubActivity(Context ctx) {
        Status status = sub.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        sub.stop(ctx);
        sub = null;

        if (hoeSub || shearsSub) {
            if (hoeSub) {
                // A trip that brought no hoe is not the end of the session: the
                // farmer works on without one and waits for something to change.
                FarmerSettings settings = FarmerSettings.of(ctx.npc());
                if (FarmerHoeUtils.findUsableHoeSlot(ctx.npc().getInventory(), settings) >= 0) {
                    ctx.mgr().farmers().hoeTrips().forget(ctx.model().npcId);
                } else if (FarmerManager.needsHoe(settings)) {
                    ctx.mgr().farmers().hoeTrips().failed(ctx);
                    diary(ctx, "FARM came back without a hoe, working on without one");
                }
            }
            hoeSub = false;
            shearsSub = false;
            return Status.RUNNING;
        }

        if (seedCraftSub) {
            // Whatever fruit the fetch managed to bring is converted now,
            // before a deposit pass can carry it back to the chest.
            if (seedCraftItem != null) craftSeeds(ctx, seedCraftItem);
            seedCraftSub = false;
            seedCraftItem = null;
            return Status.RUNNING;
        }

        if (supplySub && job != null) {
            FarmerManager.Job fetched = job;
            job = null;
            supplySub = false;
            if (status != Status.DONE) {
                ctx.mgr().farmers().reportFailure(ctx.model().npcId, fetched, ctx.level().getGameTime());
                return Status.RUNNING;
            }
            ctx.mgr().farmers().reportSuccess(ctx.level(), ctx.mgr(), ctx.model().npcId, fetched);
            worked = true;
            chooseGate.progressed();
            if (fetched.type() == FarmerManager.JobType.FETCH_BONE_MEAL
                    && fetched.plantingItem() == Items.BONE) {
                int made = FarmerBoneMeal.craftFromBones(ctx.npc(), ctx.mgr());
                if (made > 0) diary(ctx, "FARM made " + made + " bone meal from bones");
            } else if (fetched.type() == FarmerManager.JobType.FETCH_COMPOST) {
                // Straight to the composter: a deposit pass in between would
                // carry the scraps back to the chest they just came out of.
                FarmerManager.Job compost = ctx.mgr().farmers().compostAfterFetch(
                        ctx.level(), ctx.mgr(), ctx.npc(), fetched);
                writeManagerNotes(ctx);
                if (compost != null) {
                    startJob(ctx, compost);
                } else {
                    diary(ctx, "FARM compost trip stopped after the fetch: composter at "
                            + (fetched.fruitSpace() == null ? "?" : fetched.fruitSpace().toShortString())
                            + " is taken, gone or unreachable");
                }
            }
            return Status.RUNNING;
        }

        boolean deposited = depositSub;
        depositSub = false;
        if (deposited && finishAfterDeposit) return Status.DONE;
        return Status.RUNNING;
    }

    private Status tickMovement(Context ctx) {
        Status status = move.tickOuter(ctx);
        if (status == Status.RUNNING) return Status.RUNNING;
        move.stop(ctx);
        move = null;
        if (job == null) return Status.RUNNING;
        if (status == Status.FAILED) {
            if (canUseNearbyFieldFallback(ctx)) {
                settleOnTillStand(ctx);
                beginWork(ctx);
                return Status.RUNNING;
            }
            diary(ctx, "FARM move failed -> " + describe(job));
            ctx.mgr().farmers().reportFailure(ctx.model().npcId, job, ctx.level().getGameTime());
            job = null;
            return Status.RUNNING;
        }
        if (prefersExactFieldStand(ctx, job)
                && NavGraph.feetCell(ctx.npc()).distSqr(job.target()) > 4.0) {
            ctx.mgr().farmers().reportFailure(ctx.model().npcId, job, ctx.level().getGameTime());
            job = null;
            return Status.RUNNING;
        }
        arriveAtStand(ctx);
        return Status.RUNNING;
    }

    private Status chooseNext(Context ctx) {
        if (!chooseGate.tryPass(ctx.level().getGameTime(), ctx.npc().getId())) return Status.RUNNING;
        FarmerSettings settings = FarmerSettings.of(ctx.npc());
        if (!settings.works()) return depositOrFinish(ctx, true);
        if (DepositActivity.countPotentiallyDepositable(ctx)
                >= FarmerBalance.carryItems(ctx.brain().farmerSkillLevel())) {
            if (DepositActivity.countDepositable(ctx) > 0) return depositOrFinish(ctx, false);
            diary(ctx, "FARM stop: carrying " + DepositActivity.countPotentiallyDepositable(ctx)
                    + " items and no storage will take them");
            return worked ? Status.DONE : Status.FAILED;
        }
        if (actions >= FarmerBalance.ACTIONS_PER_WORK_SESSION) return depositOrFinish(ctx, true);

        // A farmer with Planting, Harvesting and Tilling all off tills, plants
        // and harvests nothing: no hoe, shears or seeds to look after.
        if (!settings.boneMealOnly()) {
            // A job still held here was restored from the save: resume it before
            // any optional tool errand, so a reload continues the same work.
            boolean resumingJob = ctx.mgr().farmers().hasAssignment(ctx.model().npcId);
            // The hoe only tills: without one the farmer harvests and replants
            // (requestJob hands out no tilling), and goes for one as soon as a
            // hoe or enough wood to make one is free (FarmerHoeTrips).
            if (!resumingJob && ctx.mgr().farmers().hoeTrips().worthTrying(ctx, settings)) {
                sub = new GetHoeActivity();
                hoeSub = true;
                diary(ctx, "FARM going for a hoe");
                return Status.RUNNING;
            }
            boolean needsHoe = FarmerManager.needsHoe(settings);
            if (!resumingJob && tryStartToolUpgrade(ctx, needsHoe, settings)) return Status.RUNNING;
            if (!resumingJob && tryStartHarvestToolTrip(ctx, settings)) return Status.RUNNING;

            if (!resumingJob && tryStartSeedCraft(ctx)) return Status.RUNNING;
        }

        // Spare items it can still pick up before the carry limit above sends
        // it to deposit; read now, after any seed crafting changed the pack.
        int supplyRoom = FarmerBalance.carryItems(ctx.brain().farmerSkillLevel()) - 1
                - DepositActivity.countPotentiallyDepositable(ctx);
        FarmerManager.Job next = ctx.mgr().farmers().requestJob(ctx.level(), ctx.mgr(), ctx.npc(), supplyRoom);
        writeManagerNotes(ctx);
        if (next == null) {
            diary(ctx, ctx.mgr().farmers().describeNoJob(ctx.level(), ctx.mgr(), ctx.npc()));
            return depositOrFinish(ctx, true);
        }
        startJob(ctx, next);
        return Status.RUNNING;
    }

    /**
     * Renews the claim on the job in hand every few seconds while the farmer
     * walks to it or works it. When another farmer holds the spot after all,
     * this one gives way at once instead of walking over to work it too.
     */
    private void keepClaim(Context ctx) {
        if (job == null || job.type().isFetch()) return;
        long now = ctx.level().getGameTime();
        if (now < nextClaimRenewAt) return;
        nextClaimRenewAt = now + CLAIM_RENEW_TICKS;
        if (ctx.mgr().farmers().renewClaim(ctx.model().npcId, job, now)) return;
        diary(ctx, "FARM another farmer has " + describe(job) + ", leaving it to them");
        if (move != null) move.stop(ctx);
        move = null;
        pickPoint = null;
        workTimer = 0;
        animationStartDelay = 0;
        ctx.mgr().farmers().release(ctx.model().npcId);
        job = null;
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ctx.model().stopMoving();
    }

    /** Sets off on a claimed job: a storage trip, or a walk to the stand and the work. */
    private void startJob(Context ctx, FarmerManager.Job next) {
        job = next;
        nextClaimRenewAt = ctx.level().getGameTime() + CLAIM_RENEW_TICKS;
        if (next.type().isFetch()) {
            if (next.plantingItem() == null) {
                ctx.mgr().farmers().reportFailure(ctx.model().npcId, next, ctx.level().getGameTime());
                job = null;
                return;
            }
            String name = switch (next.type()) {
                case FETCH_BONE_MEAL -> "fetch_bone_meal";
                case FETCH_COMPOST -> "fetch_compost";
                default -> "fetch_seeds";
            };
            sub = FetchFromStorageActivity.forItemAtStorage(new ItemStack(next.plantingItem()),
                    Math.max(1, next.supplyAmount()), name, next.target());
            supplySub = true;
            return;
        }

        if (next.interactionPos() == null) {
            ctx.mgr().farmers().reportFailure(ctx.model().npcId, next, ctx.level().getGameTime());
            job = null;
            return;
        }
        boolean exactFieldStand = prefersExactFieldStand(ctx, next);
        boolean alreadyPlaced = exactFieldStand
                ? NavGraph.feetCell(ctx.npc()).equals(next.interactionPos())
                : ctx.npc().blockPosition().distSqr(next.interactionPos()) <= 2.25;
        if (alreadyPlaced) arriveAtStand(ctx);
        else move = new MoveActivity(next.interactionPos(), VillagerModel.WALK_SPEED, true, exactFieldStand);
    }

    /** At the stand: picking walks the last step up to the fruit first, other work starts now. */
    private void arriveAtStand(Context ctx) {
        pickPoint = isPickJob(job) ? choosePickPoint(ctx, job)
                : isTillJob(job) ? chooseTillPoint(ctx, job) : null;
        if (pickPoint == null) {
            settleOnTillStand(ctx);
            beginWork(ctx);
            return;
        }
        pickStepTicks = 0;
        ctx.model().stopMoving();
    }

    private static boolean isPickJob(@Nullable FarmerManager.Job job) {
        return job != null && (job.type() == FarmerManager.JobType.PICK_GLOW_BERRIES
                || job.type() == FarmerManager.JobType.HARVEST_POD
                || job.type() == FarmerManager.JobType.PLANT_POD
                || job.type() == FarmerManager.JobType.HARVEST_CANE
                || job.type() == FarmerManager.JobType.PICK_BERRIES);
    }

    /** Walks straight onto {@link #pickPoint}, then starts the work; gives up the step after two seconds. */
    private void tickPickStep(Context ctx) {
        NpcEntity npc = ctx.npc();
        double dx = pickPoint.x - npc.getX();
        double dz = pickPoint.z - npc.getZ();
        if (job == null || dx * dx + dz * dz <= PICK_STEP_DONE_SQ || ++pickStepTicks > PICK_STEP_MAX_TICKS) {
            pickPoint = null;
            npc.getNavigation().stop();
            npc.getMoveControl().setWantedPosition(npc.getX(), npc.getY(), npc.getZ(), 0.0);
            settleOnTillStand(ctx);
            beginWork(ctx);
            return;
        }
        npc.getMoveControl().setWantedPosition(pickPoint.x, pickPoint.y, pickPoint.z, 0.5);
    }

    /**
     * Where to pick {@code job}'s fruit from: {@link #PICK_DISTANCE} from it,
     * on the side nearest the farmer, reachable in a straight line on the same
     * floor. Null when the farmer already stands about that far away, or when
     * no spot out to {@link #PICK_MAX_DISTANCE} has room.
     */
    @Nullable
    private static Vec3 choosePickPoint(Context ctx, FarmerManager.Job job) {
        NpcEntity npc = ctx.npc();
        NavGraph graph = ctx.mgr().getGraph();
        if (graph == null || !npc.onGround()) return null;
        Vec3 fruit = fruitCentre(ctx, job.target());
        Vec3 from = npc.position();
        double reach = job.target().getY() - npc.blockPosition().getY() >= 2
                ? PICK_DISTANCE_HIGH : PICK_DISTANCE;
        double distance = Math.hypot(from.x - fruit.x, from.z - fruit.z);
        if (Math.abs(distance - reach) <= PICK_CLOSE_ENOUGH) return null;
        for (double radius = reach; radius <= PICK_MAX_DISTANCE + 1e-6; radius += PICK_DISTANCE_STEP) {
            Vec3 best = null;
            double bestStep = Double.MAX_VALUE;
            for (int i = 0; i < PICK_DIRECTIONS; i++) {
                double angle = i * (Math.PI * 2 / PICK_DIRECTIONS);
                Vec3 point = new Vec3(fruit.x + Math.cos(angle) * radius, from.y,
                        fruit.z + Math.sin(angle) * radius);
                double step = point.distanceToSqr(from);
                if (step >= bestStep || !canStepTo(ctx, graph, from, point)) continue;
                best = point;
                bestStep = step;
            }
            if (best != null) return best;
        }
        return null;
    }

    private static boolean isTillJob(@Nullable FarmerManager.Job job) {
        return job != null && (job.type() == FarmerManager.JobType.TILL || job.type() == FarmerManager.JobType.RETILL);
    }

    /** The tile beside the target a tilling farmer stands on (null when the job stands on the target itself). */
    @Nullable
    private static BlockPos tillStandTile(@Nullable FarmerManager.Job job) {
        if (!isTillJob(job) || job.interactionPos() == null || job.interactionPos().equals(job.target())) return null;
        return job.interactionPos();
    }

    /**
     * Where to take the last step before tilling: the middle of the tile beside the target, level with
     * the farmer, so the clip's pull (the blade goes in at the far end of the next block and is dragged
     * back to his feet) lands on the tile. Null when he already stands there, is too far from it
     * to walk the step (he works from where he is) or the step is blocked.
     */
    @Nullable
    private static Vec3 chooseTillPoint(Context ctx, FarmerManager.Job job) {
        BlockPos stand = tillStandTile(job);
        NpcEntity npc = ctx.npc();
        NavGraph graph = ctx.mgr().getGraph();
        if (stand == null || graph == null || !npc.onGround()) return null;
        Vec3 from = npc.position();
        Vec3 centre = new Vec3(stand.getX() + 0.5, from.y, stand.getZ() + 0.5);
        double distSq = (centre.x - from.x) * (centre.x - from.x) + (centre.z - from.z) * (centre.z - from.z);
        if (distSq <= PICK_STEP_DONE_SQ || distSq > TILL_STEP_MAX_SQ) return null;
        return canStepTo(ctx, graph, from, centre) ? centre : null;
    }

    /**
     * Stands the farmer in the exact middle of the tile beside the target and turns him square to it,
     * so every tilling pull starts where the clip expects. A farmer too far off, or with no room on the
     * tile, works from where he is.
     */
    private void settleOnTillStand(Context ctx) {
        BlockPos stand = tillStandTile(job);
        NavGraph graph = ctx.mgr().getGraph();
        if (stand == null || graph == null) return;
        NpcEntity npc = ctx.npc();
        double dx = stand.getX() + 0.5 - npc.getX();
        double dz = stand.getZ() + 0.5 - npc.getZ();
        if (dx * dx + dz * dz > TILL_SNAP_MAX_SQ) return;
        Vec3 centre = new Vec3(stand.getX() + 0.5, npc.getY(), stand.getZ() + 0.5);
        if (dx * dx + dz * dz > 1.0E-6) {
            if (!fitsAt(ctx, graph, centre)) return;
            if (!com.alex.test.village.farawake.FarAwake.readyFor(ctx.level(), centre.x, centre.z)) return;
            ctx.model().stopMoving();
            npc.setPos(centre.x, centre.y, centre.z);
            npc.setDeltaMovement(0, npc.getDeltaMovement().y, 0);
        }
        npc.getNavigation().stop();
        com.alex.test.village.brain.CraftingStation.face(npc, job.target());
    }

    /** The middle of what can be seen of the fruit: a pod hangs against its log, off its block's centre. */
    private static Vec3 fruitCentre(Context ctx, BlockPos target) {
        VoxelShape shape = ctx.level().getBlockState(target).getShape(ctx.level(), target);
        Vec3 centre = shape.isEmpty() ? new Vec3(0.5, 0.5, 0.5) : shape.bounds().getCenter();
        return new Vec3(target.getX() + centre.x, target.getY() + centre.y, target.getZ() + centre.z);
    }

    /** A straight walk from {@code from} to {@code to}, checked every quarter block. */
    private static boolean canStepTo(Context ctx, NavGraph graph, Vec3 from, Vec3 to) {
        int samples = Math.max(1, (int) Math.ceil(Math.hypot(to.x - from.x, to.z - from.z) / 0.25));
        for (int i = 1; i <= samples; i++) {
            if (!fitsAt(ctx, graph, from.lerp(to, i / (double) samples))) return false;
        }
        return true;
    }

    /**
     * The farmer could stand at {@code point}: on a walkable tile at the same
     * floor height, with room for its body, and touching no water, lava or
     * hurting block.
     */
    private static boolean fitsAt(Context ctx, NavGraph graph, Vec3 point) {
        NpcEntity npc = ctx.npc();
        ServerLevel level = ctx.level();
        BlockPos feet = BlockPos.containing(point.x, Math.ceil(point.y - 0.01), point.z);
        NavNode node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        if (node == null) {
            feet = feet.below();
            node = graph.nodeAt(feet.getX(), feet.getY(), feet.getZ());
        }
        if (node == null || !node.isRoutable()) return false;
        if (Math.abs(NavGraph.standY(level, feet) - point.y) > 0.05) return false;
        AABB box = npc.getBoundingBox()
                .move(point.x - npc.getX(), point.y - npc.getY(), point.z - npc.getZ())
                .deflate(1.0E-3);
        if (!level.noCollision(npc, box) || level.containsAnyLiquid(box)) return false;
        for (BlockPos pos : BlockPos.betweenClosed(
                Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX), Mth.floor(box.maxY), Mth.floor(box.maxZ))) {
            if (NavGraph.isHazard(level.getBlockState(pos))) return false;
        }
        return true;
    }

    private static boolean prefersExactFieldStand(Context ctx, FarmerManager.Job job) {
        return job.ordinaryFieldJob() && job.interactionPos() != null
                && ctx.level().getBlockState(job.interactionPos().below()).is(Blocks.FARMLAND);
    }

    /** A blocked preferred plot does not prevent work from a nearby safe tile. */
    private boolean canUseNearbyFieldFallback(Context ctx) {
        return job != null && prefersExactFieldStand(ctx, job)
                && NavGraph.feetCell(ctx.npc()).distSqr(job.target()) <= 4.0
                && ctx.mgr().farmers().validateJob(ctx.level(), job);
    }

    /**
     * When storage's tools change: trades the hoe, then the axe, then the shears
     * for a faster one there (ToolSpeed), one trip per check. The swap itself
     * changes storage, so the next tool gets its turn on the next check.
     */
    private boolean tryStartToolUpgrade(Context ctx, boolean needsHoe, FarmerSettings settings) {
        long newestChange = ToolUpgradeHelper.newestToolStorageChangeId(ctx);
        if (newestChange <= lastSeenToolStorageChangeId) return false;
        lastSeenToolStorageChangeId = newestChange;
        // New tools in storage: a trip that found nothing earlier may succeed now.
        shearsAttempted = false;
        axeAttempted = false;

        StorageBuilding storage = !needsHoe ? null : ToolUpgradeHelper.nearestStorageWithBetterTool(
                ctx, GetHoeActivity.HOE_REQUIREMENT);
        if (storage != null) {
            sub = GetHoeActivity.forUpgrade(storage);
            hoeSub = true;
            diary(ctx, "FARM heading to storage for a better hoe");
            return true;
        }
        // With Harvesting off no axe or shears is used, so none is worth a trip.
        if (!settings.turnOffHarvesting()
                && FarmerManager.hasHarvestTool(ctx.npc().getInventory(), settings, HarvestTool.AXE)) {
            storage = ToolUpgradeHelper.nearestStorageWithBetterTool(ctx, GetFarmerAxeActivity.AXE_REQUIREMENT);
            if (storage != null) {
                sub = GetFarmerAxeActivity.forUpgrade(storage);
                shearsSub = true; // an optional tool trip, like the shears
                diary(ctx, "FARM heading to storage for a better axe");
                return true;
            }
        }
        if (settings.turnOffHarvesting()
                || !FarmerManager.hasHarvestTool(ctx.npc().getInventory(), settings, HarvestTool.SHEARS)) return false;
        storage = ToolUpgradeHelper.nearestStorageWithBetterTool(ctx, GetShearsActivity.SHEARS_REQUIREMENT);
        if (storage == null) return false;
        sub = GetShearsActivity.forUpgrade(storage);
        shearsSub = true;
        diary(ctx, "FARM heading to storage for better shears");
        return true;
    }

    /**
     * Shears for berries, cocoa and glow berries, an axe for melons and
     * pumpkins: fetched (or, for the axe, crafted) when something the farmer
     * grows needs them and it has none. Tried once per session, and again
     * whenever storage's tools change.
     */
    private boolean tryStartHarvestToolTrip(Context ctx, FarmerSettings settings) {
        FarmerManager farms = ctx.mgr().farmers();
        InventoryManager inventory = ctx.npc().getInventory();
        if (!shearsAttempted && !FarmerManager.hasHarvestTool(inventory, settings, HarvestTool.SHEARS)
                && farms.wantsHarvestTool(ctx.npc(), HarvestTool.SHEARS)) {
            shearsAttempted = true;
            if (GetToolActivity.canAcquire(ctx, GetShearsActivity.SHEARS_REQUIREMENT)) {
                sub = new GetShearsActivity();
                shearsSub = true;
                diary(ctx, "FARM heading to storage for shears");
                return true;
            }
        }
        if (!axeAttempted && !FarmerManager.hasHarvestTool(inventory, settings, HarvestTool.AXE)
                && farms.wantsHarvestTool(ctx.npc(), HarvestTool.AXE)) {
            axeAttempted = true;
            if (GetToolActivity.canAcquire(ctx, GetFarmerAxeActivity.AXE_REQUIREMENT)) {
                sub = new GetFarmerAxeActivity();
                shearsSub = true;
                diary(ctx, "FARM getting an axe for the melons and pumpkins");
                return true;
            }
        }
        return false;
    }

    /** Ripe crops wait for a tool the farmer lacks but could fetch or craft now. */
    private static boolean harvestToolTripWaiting(Context ctx, FarmerSettings settings) {
        FarmerManager farms = ctx.mgr().farmers();
        InventoryManager inventory = ctx.npc().getInventory();
        if (!FarmerManager.hasHarvestTool(inventory, settings, HarvestTool.SHEARS)
                && farms.hasRipeNeeding(HarvestTool.SHEARS)
                && farms.wantsHarvestTool(ctx.npc(), HarvestTool.SHEARS)
                && GetToolActivity.canAcquire(ctx, GetShearsActivity.SHEARS_REQUIREMENT)) return true;
        return !FarmerManager.hasHarvestTool(inventory, settings, HarvestTool.AXE)
                && farms.hasRipeNeeding(HarvestTool.AXE)
                && farms.wantsHarvestTool(ctx.npc(), HarvestTool.AXE)
                && GetToolActivity.canAcquire(ctx, GetFarmerAxeActivity.AXE_REQUIREMENT);
    }

    /**
     * Melon/pumpkin seeds made from fruit, only once the farmer and storage
     * are both out. Carried fruit is converted on the spot; otherwise the
     * farmer fetches just enough fruit for one reserve first.
     */
    private boolean tryStartSeedCraft(Context ctx) {
        long now = ctx.level().getGameTime();
        FarmerManager farms = ctx.mgr().farmers();
        if (!farms.seedCraftDue(ctx.model().npcId, now)) return false;
        farms.delaySeedCraft(ctx.model().npcId, now + SEED_CRAFT_RETRY_TICKS);
        FarmerSeedCrafting.Plan plan = FarmerSeedCrafting.plan(ctx.level(), ctx.mgr(), ctx.npc());
        if (plan == null) return false;
        if (plan.storage() == null) {
            craftSeeds(ctx, plan.seed());
            return false;
        }
        sub = FetchFromStorageActivity.forItemAtStorage(new ItemStack(plan.fruit()),
                plan.fruitNeeded(), "fetch_seed_fruit", plan.storage().getDoorPos());
        seedCraftSub = true;
        seedCraftItem = plan.seed();
        diary(ctx, "FARM out of " + plan.seed() + " -> fetching " + plan.fruit() + " to make seeds");
        return true;
    }

    private void craftSeeds(Context ctx, Item seed) {
        int made = FarmerSeedCrafting.craft(ctx.npc(), ctx.mgr(), seed);
        if (made > 0) {
            diary(ctx, "FARM made " + made + " " + seed + " from fruit");
            return;
        }
        // Whatever went wrong would go wrong again next session; back off
        // for a long while instead of fetching the same fruit every minute.
        ctx.mgr().farmers().delaySeedCraft(ctx.model().npcId,
                ctx.level().getGameTime() + SEED_CRAFT_FAILURE_BACKOFF_TICKS);
        diary(ctx, "FARM could not make " + seed + " from fruit, retry later");
    }

    private void cancelActiveWork(Context ctx) {
        if (sub != null) sub.stop(ctx);
        if (move != null) move.stop(ctx);
        sub = null;
        move = null;
        pickPoint = null;
        supplySub = false;
        seedCraftSub = false;
        seedCraftItem = null;
        depositSub = false;
        hoeSub = false;
        shearsSub = false;
        workTimer = 0;
        animationStartDelay = 0;
        if (job != null) ctx.mgr().farmers().release(ctx.model().npcId);
        job = null;
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ctx.model().stopMoving();
    }

    private Status depositOrFinish(Context ctx, boolean finish) {
        if (DepositActivity.countDepositable(ctx) > 0) {
            sub = new DepositActivity();
            depositSub = true;
            finishAfterDeposit = finish;
            if (finish) actions = 0;
            return Status.RUNNING;
        }
        if (!finish) return Status.RUNNING;
        actions = 0;
        return worked ? Status.DONE : Status.FAILED;
    }

    private void beginWork(Context ctx) {
        if (job == null) return;
        if (!jobAllowed(ctx, job) || !ctx.mgr().farmers().validateJob(ctx.level(), job)) {
            ctx.mgr().farmers().reportFailure(ctx.model().npcId, job, ctx.level().getGameTime());
            job = null;
            return;
        }
        VillagerAnimationState animation = animationFor(ctx, job);
        if (job.type() == FarmerManager.JobType.TILL || job.type() == FarmerManager.JobType.RETILL) {
            int hoeSlot = FarmerHoeUtils.findBestUsableHoeSlot(
                    ctx.npc().getInventory(), FarmerSettings.of(ctx.npc()));
            if (hoeSlot < 0) {
                ctx.mgr().farmers().reportFailure(ctx.model().npcId, job, ctx.level().getGameTime());
                job = null;
                return;
            }
            workTimer = FarmerBalance.tillTicks(
                    ToolSpeed.of(ctx.npc().getInventory().getSlot(hoeSlot)),
                    ctx.brain().farmerSkillLevel());
        } else if (job.type() == FarmerManager.JobType.COMPOST) {
            // Fixed pace per item, whatever the skill.
            compostLeft = job.plantingItem() == null ? 0
                    : Math.min(job.supplyAmount(), countCarried(ctx, job.plantingItem()));
            compostWaits = 0;
            compostDone = false;
            workTimer = FarmerCompost.TICKS_PER_ITEM;
        } else {
            workTimer = FarmerBalance.plantHarvestTicks(
                    ToolSpeed.of(paceTool(ctx, job)), ctx.brain().farmerSkillLevel(), animation);
        }
        animationStartDelay = WorkAnimationStagger.delayTicks(ctx.npc().getId());
        faceWorkTarget(ctx);
        if (animationStartDelay == 0) playWorkAnimation(ctx, animation);
        else ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        if (job.type() == FarmerManager.JobType.TILL || job.type() == FarmerManager.JobType.RETILL) {
            equipHoe(ctx);
        } else if (job.type() == FarmerManager.JobType.HARVEST_FRUIT) {
            equipAxe(ctx);
        } else if (job.type() == FarmerManager.JobType.HARVEST_POD
                || job.type() == FarmerManager.JobType.HARVEST_CANE
                || job.type() == FarmerManager.JobType.PICK_BERRIES
                || job.type() == FarmerManager.JobType.PICK_GLOW_BERRIES) {
            equipShears(ctx);
        } else if (job.type() == FarmerManager.JobType.PLANT
                || job.type() == FarmerManager.JobType.PLANT_CANE
                || job.type() == FarmerManager.JobType.PLANT_POD
                || job.type() == FarmerManager.JobType.PLANT_BUSH
                || job.type() == FarmerManager.JobType.BONEMEAL
                || job.type() == FarmerManager.JobType.COMPOST) {
            equipPlantingItem(ctx, job.plantingItem());
        }
    }

    /**
     * The tool that sets a plant or harvest job's pace: the crop's own tool.
     * The axe for melons and pumpkins; the shears for berries and cocoa,
     * planting as well as picking, so a farmer growing only those (who keeps
     * no hoe) still speeds up; the farmer's best hoe for farmland crops and
     * bone meal. Sugar cane takes no tool, as in vanilla, so it always goes
     * at bare-hand speed. Only tilling, picking and melons wear the tool.
     */
    private static ItemStack paceTool(Context ctx, FarmerManager.Job job) {
        InventoryManager inventory = ctx.npc().getInventory();
        FarmerSettings settings = FarmerSettings.of(ctx.npc());
        int slot = switch (job.type()) {
            case HARVEST_FRUIT -> FarmerAxeUtils.findBestUsableAxeSlot(inventory, settings);
            case HARVEST_POD, PICK_BERRIES, PICK_GLOW_BERRIES, PLANT_POD, PLANT_BUSH -> usableShearsSlot(ctx);
            case PLANT_CANE, HARVEST_CANE -> -1;
            default -> FarmerHoeUtils.findBestUsableHoeSlot(inventory, settings);
        };
        return slot < 0 ? ItemStack.EMPTY : inventory.getSlot(slot);
    }

    private static int countCarried(Context ctx, Item item) {
        InventoryManager inventory = ctx.npc().getInventory();
        int count = 0;
        for (ItemStack stack : inventory.getSlots()) {
            if (inventory.mayUse(stack) && stack.is(item)) count += stack.getCount();
        }
        return count;
    }

    private void playWorkAnimation(Context ctx, VillagerAnimationState animation) {
        faceWorkTarget(ctx);
        // Composting is many short steps on one job; its clip plays at plain
        // speed rather than squeezing all the strokes into the first item.
        float speed = job != null && job.type() == FarmerManager.JobType.COMPOST ? 1.0f
                : FarmerBalance.animationSpeed(animation, workTimer, ctx.brain().farmerSkillLevel());
        ctx.npc().startWorkAnimation(animation, speed);
    }

    private void faceWorkTarget(Context ctx) {
        if (job == null) return;
        BlockPos target = job.target();
        double x = target.getX() + 0.5;
        double z = target.getZ() + 0.5;
        double y = target.getY() + (job.type() == FarmerManager.JobType.TILL
                || job.type() == FarmerManager.JobType.RETILL ? 0.0 : 0.3);
        ctx.npc().getLookControl().setLookAt(x, y, z, 30f, 30f);
        double dx = x - ctx.npc().getX();
        double dz = z - ctx.npc().getZ();
        if (dx * dx + dz * dz < 0.0001) return;
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float yaw = Mth.rotLerp(0.35f, ctx.npc().getYRot(), targetYaw);
        ctx.npc().setYRot(yaw);
        ctx.npc().setYBodyRot(yaw);
        ctx.npc().setYHeadRot(Mth.rotLerp(0.35f, ctx.npc().getYHeadRot(), targetYaw));
    }

    private void tickWork(Context ctx) {
        if (job == null) {
            workTimer = 0;
            animationStartDelay = 0;
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            return;
        }
        if (!FarmerSettings.of(ctx.npc()).works() || !jobAllowed(ctx, job)) {
            workTimer = 0;
            animationStartDelay = 0;
            ctx.mgr().farmers().release(ctx.model().npcId);
            job = null;
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            return;
        }
        faceWorkTarget(ctx);
        if (animationStartDelay > 0) {
            animationStartDelay--;
            if (animationStartDelay == 0) {
                playWorkAnimation(ctx, animationFor(ctx, job));
            }
            return;
        }
        workTimer--;
        VillagerAnimationState animation = animationFor(ctx, job);
        ctx.npc().setAnimation(animation);
        if (workTimer > 0) return;

        FarmerManager.JobType completedType = job.type();
        boolean success;
        if (completedType == FarmerManager.JobType.COMPOST) {
            int next = compostStep(ctx, job);
            if (next > 0) {
                workTimer = next;
                return;
            }
            success = next == 0;
        } else {
            success = performJob(ctx, job);
        }
        if (success) {
            ctx.mgr().farmers().reportSuccess(ctx.level(), ctx.mgr(), ctx.model().npcId, job);
            actions++;
            worked = true;
            chooseGate.progressed();
            FarmerDirt.Work dirtyWork = FarmerDirt.forJob(completedType);
            if (dirtyWork != null) ctx.npc().farmerDirt().onWork(ctx.npc(), dirtyWork);
            // XP for every till, plant and harvest of any crop: full for the
            // day's first 24, a fifth after; bone meal and compost give none.
            if (paysExperience(completedType)) ctx.brain().onFarmerJob(ctx);
        } else {
            ctx.mgr().farmers().reportFailure(ctx.model().npcId, job, ctx.level().getGameTime());
        }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        job = null;
    }

    /** Tilling, planting and harvesting: the jobs a farmer learns from. */
    private static boolean paysExperience(FarmerManager.JobType type) {
        return switch (type) {
            case TILL, RETILL, PLANT, PLANT_CANE, PLANT_POD, PLANT_BUSH,
                 HARVEST, HARVEST_FRUIT, HARVEST_CANE, HARVEST_POD, PICK_BERRIES, PICK_GLOW_BERRIES -> true;
            case BONEMEAL, COMPOST, FETCH_SUPPLIES, FETCH_BONE_MEAL, FETCH_COMPOST -> false;
        };
    }

    private boolean performJob(Context ctx, FarmerManager.Job current) {
        if (!FarmerSettings.of(ctx.npc()).works() || !jobAllowed(ctx, current)
                || !ctx.mgr().farmers().validateJob(ctx.level(), current)) return false;
        return switch (current.type()) {
            case TILL, RETILL -> till(ctx, current);
            case PLANT, PLANT_CANE -> plant(ctx, current.target(), current.plantingItem());
            case HARVEST -> harvestCrop(ctx, current);
            case HARVEST_FRUIT -> harvestFruit(ctx, current.target());
            case HARVEST_CANE -> harvestCane(ctx, current.target());
            case BONEMEAL -> boneMeal(ctx, current.target());
            case HARVEST_POD -> harvestPod(ctx, current.target());
            case PICK_GLOW_BERRIES -> pickGlowBerries(ctx, current.target());
            case PLANT_POD -> plantPod(ctx, current.target(), current.plantingItem());
            case PICK_BERRIES -> pickBerries(ctx, current.target());
            case PLANT_BUSH -> plantBush(ctx, current.target(), current.plantingItem());
            case FETCH_SUPPLIES, FETCH_BONE_MEAL, FETCH_COMPOST, COMPOST -> false;
        };
    }

    private boolean boneMeal(Context ctx, BlockPos pos) {
        if (!FarmerBoneMeal.apply(ctx.level(), ctx.npc(), pos)) return false;
        diary(ctx, "FARM bone mealed " + ctx.level().getBlockState(pos).getBlock().getName().getString()
                + " at " + pos.toShortString());
        return true;
    }

    /**
     * Places a pod on its remembered log side directly: cocoa beans' own
     * placement needs a player to look along, which a villager is not.
     */
    private boolean plantPod(Context ctx, BlockPos pod, @Nullable Item item) {
        ServerLevel level = ctx.level();
        Direction facing = ctx.mgr().farmers().podFacing(pod);
        if (item == null || facing == null || !level.getBlockState(pod).isAir()
                || !FarmerSettings.of(ctx.npc()).shouldPlant(item)) return false;
        BlockState state = Blocks.COCOA.defaultBlockState().setValue(CocoaBlock.FACING, facing);
        if (!state.canSurvive(level, pod) || !takeOne(ctx, item)) return false;
        level.setBlock(pod, state, 3);
        level.gameEvent(GameEvent.BLOCK_PLACE, pod, GameEvent.Context.of(ctx.npc(), state));
        level.playSound(null, pod, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
        if (NavGraph.blockChangeAffectsNavigation(level, pod, Blocks.AIR.defaultBlockState(), state)) {
            ctx.mgr().markNavigationDirtyAt(pod);
        }
        diary(ctx, "FARM planted cocoa at " + pod.toShortString());
        return true;
    }

    /**
     * Picks a bush the vanilla way (one or two berries once it shows fruit,
     * one more when full) and leaves it at its first stage to grow again.
     */
    private boolean pickBerries(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        int shearsSlot = usableShearsSlot(ctx);
        // Stage 1 shows no fruit; stage 2 and up are what a player can pick.
        if (shearsSlot < 0 || !state.is(Blocks.SWEET_BERRY_BUSH)
                || state.getValue(SweetBerryBushBlock.AGE) <= 1) return false;
        boolean full = state.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
        int count = 1 + level.random.nextInt(2) + (full ? 1 : 0);
        if (level.random.nextDouble() < FarmerBalance.extraFoodChance(ctx.brain().farmerSkillLevel())) count++;
        BlockState picked = state.setValue(SweetBerryBushBlock.AGE, 1);
        level.setBlock(pos, picked, 2);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(ctx.npc(), picked));
        level.playSound(null, pos, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS,
                1f, 0.8f + level.random.nextFloat() * 0.4f);
        ItemStack berries = com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.SWEET_BERRIES, count), ctx.mgr());
        if (!berries.isEmpty() && !ctx.npc().getInventory().addItem(berries)) {
            ItemLossLog.pop(ctx, pos, berries, "berry pick, pack full");
        }
        FarmerShearsUtils.damageAfterUse(level, ctx.npc().getInventory(), shearsSlot);
        // Only berries it picked itself may go back into the ground.
        ctx.mgr().farmers().onBerriesPicked(ctx.model().npcId, count);
        diary(ctx, "FARM picked " + count + " sweet berries at " + pos.toShortString());
        return true;
    }

    /** Plants a bush from beside it; never onto anyone standing there. */
    private boolean plantBush(Context ctx, BlockPos pos, @Nullable Item item) {
        ServerLevel level = ctx.level();
        if (item == null || FarmerPerennials.isOccupied(level, pos)) return false;
        BlockState before = level.getBlockState(pos);
        // Short grass and the like give way to the bush, as for a player.
        if (!before.isAir() && before.canBeReplaced()) level.destroyBlock(pos, false, ctx.npc());
        if (!plant(ctx, pos, item)) return false;
        // A bush is a wall to every walker: the graph must route round it.
        ctx.mgr().markNavigationDirtyAt(pos);
        return true;
    }

    private static boolean takeOne(Context ctx, Item item) {
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(item)) continue;
            inventory.reduceSlot(slot, 1);
            return true;
        }
        return false;
    }

    /**
     * One step of composting: drop in an item, wait for a full composter to
     * turn ready, or empty a ready one into the pack. Returns the ticks until
     * the next step, 0 when finished, or -1 when it failed.
     */
    private int compostStep(Context ctx, FarmerManager.Job current) {
        ServerLevel level = ctx.level();
        BlockPos pos = current.target();
        if (!FarmerSettings.of(ctx.npc()).compost() || !ctx.mgr().farmers().validateJob(level, current)) {
            return compostDone ? 0 : -1;
        }
        BlockState state = level.getBlockState(pos);
        int fill = FarmerCompost.fill(state);
        if (fill == ComposterBlock.READY) {
            // A hopper underneath takes the bone meal; wait for it to empty.
            if (FarmerCompost.hopperBelow(level, pos)) {
                if (++compostWaits > 3) return compostDone ? 0 : -1;
                return FarmerCompost.READY_WAIT_TICKS;
            }
            ItemStack meal = com.alex.test.village.tokens.VillageItemOwnership.produce(
                    new ItemStack(Items.BONE_MEAL), ctx.mgr());
            if (meal.isEmpty() || ctx.npc().getInventory().capacityFor(meal) < 1) return compostDone ? 0 : -1;
            BlockState emptied = state.setValue(ComposterBlock.LEVEL, 0);
            level.setBlock(pos, emptied, 3);
            level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(ctx.npc(), emptied));
            level.playSound(null, pos, SoundEvents.COMPOSTER_EMPTY, SoundSource.BLOCKS, 1f, 1f);
            ctx.npc().getInventory().addItem(meal);
            compostDone = true;
            diary(ctx, "FARM emptied composter at " + pos.toShortString() + " -> 1 bone meal");
            return compostLeft > 0 ? FarmerCompost.TICKS_PER_ITEM : 0;
        }
        if (fill >= ComposterBlock.MAX_LEVEL) {
            // Full: vanilla turns it ready a second later.
            if (++compostWaits > 3) return compostDone ? 0 : -1;
            return FarmerCompost.READY_WAIT_TICKS;
        }
        if (compostLeft <= 0 || current.plantingItem() == null) return compostDone ? 0 : -1;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || !stack.is(current.plantingItem())) continue;
            BlockState after = ComposterBlock.insertItem(ctx.npc(), state, level, stack, pos);
            level.levelEvent(1500, pos, after != state ? 1 : 0);
            if (stack.isEmpty()) inventory.setSlot(slot, ItemStack.EMPTY);
            compostLeft--;
            compostDone = true;
            if (FarmerCompost.fill(after) >= ComposterBlock.MAX_LEVEL) return FarmerCompost.READY_WAIT_TICKS;
            return compostLeft > 0 ? FarmerCompost.TICKS_PER_ITEM : 0;
        }
        return compostDone ? 0 : -1;
    }

    /** Runs NeoForge's normal HOE_TILL state query, then emits the vanilla block event. */
    private boolean till(Context ctx, FarmerManager.Job current) {
        int hoeSlot = FarmerHoeUtils.findBestUsableHoeSlot(
                ctx.npc().getInventory(), FarmerSettings.of(ctx.npc()));
        if (hoeSlot < 0) return false;
        ItemStack hoe = ctx.npc().getInventory().getSlot(hoeSlot);
        BlockPos soil = current.target().below();
        // A hoe needs open air above the soil: short grass, ferns or snow go first.
        BlockState cover = ctx.level().getBlockState(current.target());
        if (FarmerManager.isClearableCover(cover)) {
            if (!FarmerManager.isActuallyHydrated(ctx.level(), soil)) return false;
            ctx.level().destroyBlock(current.target(), false, ctx.npc());
            if (NavGraph.blockChangeAffectsNavigation(ctx.level(), current.target(), cover,
                    Blocks.AIR.defaultBlockState())) {
                ctx.mgr().markNavigationDirtyAt(current.target());
            }
        }
        UseOnContext use = useContext(ctx, soil, hoe);
        BlockState modified = ctx.level().getBlockState(soil).getToolModifiedState(
                use, net.neoforged.neoforge.common.ItemAbilities.HOE_TILL, false);
        if (modified == null || !FarmerManager.isActuallyHydrated(ctx.level(), soil)) return false;
        ctx.level().playSound(null, soil, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 1f, 1f);
        ctx.level().setBlock(soil, modified, 11);
        ctx.level().gameEvent(GameEvent.BLOCK_CHANGE, soil, GameEvent.Context.of(ctx.npc(), modified));
        FarmerHoeUtils.damageHoeAfterUse(ctx.level(), ctx.npc().getInventory(), hoeSlot);
        diary(ctx, "FARM tilled " + current.target().toShortString());
        return true;
    }

    /** Uses the seed item's normal use-on-block path, including placement hooks and sounds. */
    private boolean plant(Context ctx, BlockPos cropPos, @Nullable Item item) {
        if (item == null || !FarmerSettings.of(ctx.npc()).shouldPlant(item)
                || !ctx.level().getBlockState(cropPos).isAir()) return false;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!inventory.mayUse(stack) || stack.getItem() != item) continue;
            if (!foodSeedWouldPlace(ctx.level(), cropPos, stack)) return false;
            InteractionResult result = stack.useOn(useContext(ctx, cropPos.below(), stack));
            if (!result.consumesAction()) return false;
            if (stack.isEmpty()) inventory.setSlot(slot, ItemStack.EMPTY);
            // Confirm the normal use-on path actually produced a crop this mod
            // recognizes before reporting success; a foreign or misbehaving
            // seed item that consumes the action without placing a matching
            // crop block must not be treated as a successful plant.
            CropDefinition expected = CropDefinitionRegistry.byPlantingItem(item);
            if (expected == null
                    || !expected.matchesCropBlock(ctx.level().getBlockState(cropPos).getBlock())) {
                return false;
            }
            diary(ctx, "FARM planted " + item.getDescriptionId() + " at " + cropPos.toShortString());
            return true;
        }
        return false;
    }

    /**
     * A food seed (carrot, potato, sweet berries) whose placement fails falls
     * back to being eaten, and vanilla's eating code needs a player: with
     * none it throws. So check what placement checks before using the item.
     * Other seeds have no such fallback and keep the plain path.
     */
    private static boolean foodSeedWouldPlace(ServerLevel level, BlockPos cropPos, ItemStack stack) {
        // raw food data: vanilla's eat fallback, whatever villagers make of the item.
        if (!stack.has(net.minecraft.core.component.DataComponents.FOOD)
                || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem)) return true;
        Block block = blockItem.getBlock();
        return block.isEnabled(level.enabledFeatures())
                && level.getBlockState(cropPos).canBeReplaced()
                && block.defaultBlockState().canSurvive(level, cropPos);
    }

    private boolean harvestCrop(Context ctx, FarmerManager.Job current) {
        BlockState before = ctx.level().getBlockState(current.target());
        if (!FarmerManager.isMatureCrop(before)
                || current.plantingItem() == null
                || !FarmerSettings.of(ctx.npc()).isPlantEnabled(current.plantingItem())) {
            return false;
        }
        if (!harvestBlock(ctx, current.target())) return false;

        // Only ordinary farmland crops disturb soil. Stems and cane retain
        // their own growing block and never use this roll.
        BlockPos soil = current.target().below();
        if (ctx.level().getBlockState(soil).is(Blocks.FARMLAND)
                && ctx.level().random.nextDouble()
                >= FarmerBalance.soilRetentionChance(ctx.brain().farmerSkillLevel())) {
            // turnToDirt lifts the farmer standing on this tile onto the taller
            // dirt; a plain setBlock left its feet inside it and MoveControl hopped.
            FarmBlock.turnToDirt(ctx.npc(), ctx.level().getBlockState(soil), ctx.level(), soil);
        }

        // Replanting is deliberately a later PLANT job. Keeping harvest and
        // planting separate gives each action its own movement and animation.
        return true;
    }

    /**
     * Pumpkin and melon fruit are cut with an axe, preserving their live
     * stem. No usable allowed axe means the fruit stays put.
     */
    private boolean harvestFruit(Context ctx, BlockPos pos) {
        int slot = FarmerAxeUtils.findBestUsableAxeSlot(ctx.npc().getInventory(), FarmerSettings.of(ctx.npc()));
        if (slot < 0) return false;
        ItemStack axe = ctx.npc().getInventory().getSlot(slot);
        if (!harvestBlock(ctx, pos, axe)) return false;
        FarmerAxeUtils.damageAfterUse(ctx.level(), ctx.npc().getInventory(), slot);
        return true;
    }

    /** Snips a ripe cocoa pod off with shears; its drops are the plain pod's. */
    private boolean harvestPod(Context ctx, BlockPos pos) {
        int slot = usableShearsSlot(ctx);
        if (slot < 0 || !harvestBlock(ctx, pos)) return false;
        FarmerShearsUtils.damageAfterUse(ctx.level(), ctx.npc().getInventory(), slot);
        return true;
    }

    /** The fastest pair: the one that sets the job's pace, is held and wears down. */
    private static int usableShearsSlot(Context ctx) {
        return FarmerSettings.of(ctx.npc()).allowShears()
                ? FarmerShearsUtils.findBestUsableShearsSlot(ctx.npc().getInventory()) : -1;
    }

    /**
     * Picks one bearing glow berry vine segment the vanilla way (one berry)
     * and leaves the vine to bear again.
     */
    private boolean pickGlowBerries(Context ctx, BlockPos pos) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        int slot = usableShearsSlot(ctx);
        if (slot < 0 || !state.hasProperty(BlockStateProperties.BERRIES)
                || !state.getValue(BlockStateProperties.BERRIES)) return false;
        BlockState picked = state.setValue(BlockStateProperties.BERRIES, false);
        level.setBlock(pos, picked, 2);
        level.gameEvent(GameEvent.BLOCK_CHANGE, pos, GameEvent.Context.of(ctx.npc(), picked));
        level.playSound(null, pos, SoundEvents.CAVE_VINES_PICK_BERRIES, SoundSource.BLOCKS,
                1f, 0.8f + level.random.nextFloat() * 0.4f);
        ItemStack berries = com.alex.test.village.tokens.VillageItemOwnership.produce(
                new ItemStack(Items.GLOW_BERRIES), ctx.mgr());
        if (!berries.isEmpty() && !ctx.npc().getInventory().addItem(berries)) {
            ItemLossLog.pop(ctx, pos, berries, "glow berry pick, pack full");
        }
        FarmerShearsUtils.damageAfterUse(level, ctx.npc().getInventory(), slot);
        diary(ctx, "FARM picked glow berries at " + pos.toShortString());
        return true;
    }

    private boolean harvestBlock(Context ctx, BlockPos pos) {
        return harvestBlock(ctx, pos, ItemStack.EMPTY);
    }

    private boolean harvestBlock(Context ctx, BlockPos pos, ItemStack tool) {
        ServerLevel level = ctx.level();
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
        // A player's tool harvests with its enchantments at their share (PlayerGear).
        List<ItemStack> drops = Block.getDrops(state, level, pos, blockEntity, ctx.npc(),
                com.alex.test.village.tokens.PlayerGear.effective(tool, level.random));
        if (!level.destroyBlock(pos, false, ctx.npc())) return false;
        // Crops have no collision, but a melon or pumpkin is a full block the
        // graph stands on; left alone it keeps a step onto air.
        if (NavGraph.blockChangeAffectsNavigation(level, pos, state, Blocks.AIR.defaultBlockState())) {
            ctx.mgr().markNavigationDirtyAt(pos);
        }
        Item bonus = eligibleBonusProduct(drops);
        if (bonus != null && level.random.nextDouble()
                < FarmerBalance.extraFoodChance(ctx.brain().farmerSkillLevel())) {
            drops = new ArrayList<>(drops);
            drops.add(new ItemStack(bonus));
        }
        for (ItemStack drop : drops) {
            drop = com.alex.test.village.tokens.VillageItemOwnership.produce(drop, ctx.mgr());
            if (!drop.isEmpty() && !ctx.npc().getInventory().addItem(drop)) ItemLossLog.pop(ctx, pos, drop, "harvest, pack full");
        }
        level.playSound(null, pos, SoundEvents.CROP_BREAK, SoundSource.BLOCKS, 0.6f, 1f);
        diary(ctx, "FARM harvested " + state.getBlock().getName().getString()
                + " at " + pos.toShortString());
        return true;
    }

    /** Bonus products only: seeds and non-food crops retain their normal drops. */
    @Nullable
    private static Item eligibleBonusProduct(List<ItemStack> drops) {
        for (ItemStack drop : drops) {
            Item item = drop.getItem();
            if (item == Items.WHEAT || item == Items.CARROT || item == Items.POTATO
                    || item == Items.BEETROOT || item == Items.PUMPKIN
                    || item == Items.MELON_SLICE || item == Items.COCOA_BEANS) return item;
        }
        return null;
    }

    /** Cuts only growth above the base, preserving the healthy cane plant. */
    private boolean harvestCane(Context ctx, BlockPos base) {
        boolean harvested = false;
        BlockPos cursor = base.above();
        while (ctx.level().getBlockState(cursor).is(net.minecraft.world.level.block.Blocks.SUGAR_CANE)) {
            BlockPos next = cursor.above();
            harvested |= harvestBlock(ctx, cursor);
            cursor = next;
        }
        return harvested;
    }

    private static UseOnContext useContext(Context ctx, BlockPos clicked, ItemStack stack) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clicked), Direction.UP, clicked, false);
        return new UseOnContext(ctx.level(), null, InteractionHand.MAIN_HAND, stack, hit);
    }

    /**
     * The clip for each job. Farmland seeds, cane, bushes and bone meal are put
     * into the ground (sow or press); farmland crops are pulled up (yank or
     * pluck); melons and pumpkins are chopped with the axe. Fruit that hangs
     * (glow berries, cocoa, cane tops, pods being planted) is picked at its own
     * height: at the feet or below, a block up, or two up. Sweet berries are
     * picked carefully past the thorns until the farmer is skilled.
     */
    private static VillagerAnimationState animationFor(Context ctx, FarmerManager.Job job) {
        int level = ctx.brain().farmerSkillLevel();
        return switch (job.type()) {
            case TILL, RETILL -> level >= SKILLED_TILL_LEVEL
                    ? VillagerAnimationState.TILL_SKILLED : VillagerAnimationState.TILL;
            case PLANT, PLANT_CANE, PLANT_BUSH, BONEMEAL -> VillagerAnimationState.PLANT;
            case COMPOST -> VillagerAnimationState.PICK;
            case PICK_GLOW_BERRIES, HARVEST_POD, PLANT_POD, HARVEST_CANE -> pickByHeight(ctx, job);
            case PICK_BERRIES -> level >= SKILLED_PICK_LEVEL
                    ? VillagerAnimationState.PICK_LOW : VillagerAnimationState.PICK_BUSH;
            case HARVEST -> VillagerAnimationState.HARVEST;
            case HARVEST_FRUIT -> VillagerAnimationState.HARVEST_CHOP;
            case FETCH_SUPPLIES, FETCH_BONE_MEAL, FETCH_COMPOST -> VillagerAnimationState.IDLE;
        };
    }

    /** Low, middle or high pick, from how far the fruit hangs above the farmer's feet block. */
    private static VillagerAnimationState pickByHeight(Context ctx, FarmerManager.Job job) {
        int up = job.target().getY() - ctx.npc().blockPosition().getY();
        if (up <= 0) return VillagerAnimationState.PICK_LOW;
        return up == 1 ? VillagerAnimationState.PICK : VillagerAnimationState.PICK_HIGH;
    }

    private static void equipHoe(Context ctx) {
        int slot = FarmerHoeUtils.findBestUsableHoeSlot(
                ctx.npc().getInventory(), FarmerSettings.of(ctx.npc()));
        if (slot >= 0) ctx.npc().setItemSlot(EquipmentSlot.MAINHAND,
                ctx.npc().getInventory().getSlot(slot).copy());
    }

    private static void equipAxe(Context ctx) {
        int slot = FarmerAxeUtils.findBestUsableAxeSlot(ctx.npc().getInventory(), FarmerSettings.of(ctx.npc()));
        if (slot >= 0) ctx.npc().setItemSlot(EquipmentSlot.MAINHAND,
                ctx.npc().getInventory().getSlot(slot).copy());
    }

    private static void equipShears(Context ctx) {
        int slot = FarmerShearsUtils.findBestUsableShearsSlot(ctx.npc().getInventory());
        if (slot >= 0) ctx.npc().setItemSlot(EquipmentSlot.MAINHAND,
                ctx.npc().getInventory().getSlot(slot).copy());
    }

    private static boolean jobAllowed(Context ctx, FarmerManager.Job current) {
        FarmerSettings settings = FarmerSettings.of(ctx.npc());
        return settings.works()
                && FarmerManager.jobAllowed(current, settings, ctx.npc().getInventory());
    }

    private static void equipPlantingItem(Context ctx, @Nullable Item item) {
        if (item == null) return;
        InventoryManager inventory = ctx.npc().getInventory();
        for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
            ItemStack stack = inventory.getSlot(slot);
            if (!stack.isEmpty() && stack.is(item)) {
                ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, stack.copyWithCount(1));
                return;
            }
        }
    }

    private static String describe(FarmerManager.Job job) {
        return job.type() + "@" + job.target().toShortString();
    }

    private static void diary(Context ctx, String message) {
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " " + message);
    }

    /** What the farm manager noted while handing out work: spots skipped, why composting found nothing. */
    private static void writeManagerNotes(Context ctx) {
        for (String note : ctx.mgr().farmers().takeNotes(ctx.model().npcId)) diary(ctx, note);
    }

    @Override
    public void doStop(Context ctx) {
        if (sub != null) sub.stop(ctx);
        if (move != null) move.stop(ctx);
        sub = null;
        move = null;
        pickPoint = null;
        if (job != null) ctx.mgr().farmers().release(ctx.model().npcId);
        job = null;
        workTimer = 0;
        animationStartDelay = 0;
        ctx.npc().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.model().stopMoving();
    }

    @Override
    public boolean isMovingPhase() {
        return move != null || pickPoint != null || supplySub;
    }

    /** Finish a tile action already in progress before attending to an open UI. */
    public boolean isPerformingTileAction() {
        if (workTimer <= 0 || job == null) return false;
        return switch (job.type()) {
            case TILL, RETILL, PLANT, PLANT_CANE,
                    HARVEST, HARVEST_FRUIT, HARVEST_CANE,
                    BONEMEAL, COMPOST, HARVEST_POD, PLANT_POD, PICK_BERRIES, PLANT_BUSH, PICK_GLOW_BERRIES -> true;
            case FETCH_SUPPLIES, FETCH_BONE_MEAL, FETCH_COMPOST -> false;
        };
    }

    @Override
    public boolean allowsRoutineInterruption() {
        return workTimer <= 0;
    }

    public static boolean hasWork(Context ctx) {
        FarmerSettings settings = FarmerSettings.of(ctx.npc());
        if (!settings.works()) return false;
        if (DepositActivity.countPotentiallyDepositable(ctx)
                >= FarmerBalance.carryItems(ctx.brain().farmerSkillLevel())) {
            return DepositActivity.countDepositable(ctx) > 0;
        }
        // Only tilling needs a hoe, and the manager counts bare soil as work only
        // for a farmer that has one, so a farmer without still harvests and replants.
        if (ctx.mgr().farmers().hasWork(ctx.level(), ctx.mgr(), ctx.npc())) return true;
        if (settings.boneMealOnly()) return false;
        // A hoe to be had is worth a session of its own (chooseNext goes for it
        // first). After a failed trip only once something changed: otherwise a
        // farmer with only tilling to do started, failed the trip and stopped,
        // over and over.
        if (ctx.mgr().farmers().hoeTrips().worthTrying(ctx, settings)) return true;
        // A field of ripe melons and nothing else to do still needs the farmer
        // to start a session, or it would never go for the axe.
        return harvestToolTripWaiting(ctx, settings);
    }
}
