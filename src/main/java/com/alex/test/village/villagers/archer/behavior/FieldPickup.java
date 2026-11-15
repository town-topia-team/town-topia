package com.alex.test.village.villagers.archer.behavior;

import com.alex.test.village.VillagerModel;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.MoveActivity;
import com.alex.test.village.brain.VillagerDiary;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.villagers.guard.behavior.GuardAlertSystem;
import com.alex.test.village.villagers.guard.settings.GuardMobPolicy;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * After a fight, once the danger is cleared, an archer gathers what is worth
 * keeping from the ground and takes it to storage:
 * <ul>
 *   <li>what the defenders' kills dropped that the village can use (see
 *       {@code ArcherEvents}): arrows from skeletons, string from spiders (the
 *       only string the village gets, for new bows), bones for the farmers;</li>
 *   <li>a fallen villager's gear (see {@code NpcEntity#dropAllDeathLoot}),
 *       which would otherwise vanish after five minutes.</li>
 * </ul>
 *
 * <p>Only items marked for this archer's village are touched: never what a
 * player dropped, or what a player's own kills dropped. Mob loot becomes the
 * village's as it is picked up. Arrows stay in the quiver; the rest is banked
 * with one deposit trip once nothing is left to fetch.
 *
 * <p>"Danger cleared" means no alert, no alarm and no hostile mob about (a
 * neutral one that is not angry does not count). The archer used to walk out
 * for its arrows as soon as the mob it shot at was dead, with others still near.
 */
public final class FieldPickup {

    private static final String TAG_VILLAGE = "dynamicvillages_loot_village";
    private static final double SEARCH_XZ = 20.0D;
    private static final double SEARCH_Y = 6.0D;
    private static final double PICKUP_XZ_SQ = 1.6D * 1.6D;
    private static final double PICKUP_Y = 2.5D;
    private static final int SCAN_EVERY_TICKS = 20;
    private static final int WALK_GIVE_UP_TICKS = 200;
    /** An item given up on is not tried again for this long. */
    private static final int GIVE_UP_TICKS = 1200;
    private static final float WALK_SPEED = VillagerModel.WALK_SPEED;
    /** No hostile mob this close: the fight is over. */
    private static final double DANGER_RADIUS = 24.0D;
    private static final int DANGER_CHECK_TICKS = 20;

    @Nullable private UUID current;
    @Nullable private MoveActivity move;
    private int walkTicks;
    private long nextScanAt;
    /** Picked something up that belongs in storage since the last deposit. */
    private boolean carryingLoot;
    @Nullable private DepositActivity deposit;
    private final Map<UUID, Long> givenUp = new HashMap<>();
    private long dangerCheckedAt = Long.MIN_VALUE / 2;
    private boolean dangerClear;

    // =========================================================================
    // Marking
    // =========================================================================

    /** Marks a dropped item as this village's to gather. */
    public static void mark(ItemEntity item, UUID villageId) {
        item.getPersistentData().putUUID(TAG_VILLAGE, villageId);
    }

    private static boolean isFor(ItemEntity item, UUID villageId) {
        var data = item.getPersistentData();
        return data.hasUUID(TAG_VILLAGE) && villageId.equals(data.getUUID(TAG_VILLAGE));
    }

    // =========================================================================
    // Danger
    // =========================================================================

    /** No alert, no alarm, no hostile mob about. Looked at once a second. */
    boolean dangerCleared(Activity.Context ctx) {
        long now = ctx.level().getGameTime();
        if (now - dangerCheckedAt < DANGER_CHECK_TICKS && now >= dangerCheckedAt) return dangerClear;
        dangerCheckedAt = now;
        NpcEntity npc = ctx.npc();
        dangerClear = !GuardAlertSystem.isOnAlert(npc)
                && !GuardAlertSystem.isVillageAlarmed(ctx.mgr(), ctx.level())
                && ctx.level().getEntitiesOfClass(Mob.class, npc.getBoundingBox().inflate(DANGER_RADIUS),
                        FieldPickup::isDanger).isEmpty();
        return dangerClear;
    }

    private static boolean isDanger(Mob mob) {
        if (!GuardMobPolicy.isHostileCreature(mob)) return false;
        // An enderman or a zombified piglin minding its own business is no fight.
        return !(mob instanceof NeutralMob neutral) || neutral.isAngry();
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    /** RUNNING while fetching or banking loot; DONE when there is nothing to do. */
    Activity.Status tick(Activity.Context ctx) {
        if (deposit != null) {
            Activity.Status status = deposit.tickOuter(ctx);
            if (status == Activity.Status.RUNNING) return status;
            deposit.stop(ctx);
            deposit = null;
            carryingLoot = false;
            return Activity.Status.RUNNING;
        }
        if (current != null) return tickWalk(ctx);

        long now = ctx.level().getGameTime();
        if (now < nextScanAt && nextScanAt - now <= SCAN_EVERY_TICKS) return Activity.Status.DONE;
        nextScanAt = now + SCAN_EVERY_TICKS;
        givenUp.values().removeIf(until -> until < now || until - now > GIVE_UP_TICKS);

        ItemEntity next = nearestWanted(ctx);
        if (next != null) {
            current = next.getUUID();
            walkTicks = 0;
            return Activity.Status.RUNNING;
        }
        // Everything fetched (or nothing fits any more): bank it in one trip.
        if (carryingLoot && DepositActivity.countDepositable(ctx) > 0) {
            deposit = new DepositActivity();
            return Activity.Status.RUNNING;
        }
        carryingLoot = false;
        return Activity.Status.DONE;
    }

    private Activity.Status tickWalk(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        Entity entity = ctx.level().getEntity(current);
        if (!(entity instanceof ItemEntity item) || !item.isAlive() || ++walkTicks > WALK_GIVE_UP_TICKS) {
            giveUp(ctx);
            return Activity.Status.RUNNING;
        }
        double dx = item.getX() - npc.getX();
        double dz = item.getZ() - npc.getZ();
        if (dx * dx + dz * dz <= PICKUP_XZ_SQ && Math.abs(item.getY() - npc.getY()) <= PICKUP_Y) {
            take(ctx, item);
            stopWalking(ctx);
            current = null;
            nextScanAt = 0L;
            return Activity.Status.RUNNING;
        }
        if (move == null) move = new MoveActivity(item.blockPosition(), WALK_SPEED, true, true);
        Activity.Status status = move.tickOuter(ctx);
        if (status == Activity.Status.RUNNING) return status;
        stopWalking(ctx);
        // The walk ended short of it: close enough is checked next tick, else it is given up.
        if (status == Activity.Status.FAILED || npc.blockPosition().distSqr(item.blockPosition()) > 4.0D) {
            giveUp(ctx);
        }
        return Activity.Status.RUNNING;
    }

    /** Into the pack, the village's from now on; arrows join the quiver. */
    private void take(Activity.Context ctx, ItemEntity item) {
        NpcEntity npc = ctx.npc();
        ItemStack stack = item.getItem().copy();
        if (!VillageItemOwnership.mayUse(stack, ctx.mgr())) {
            stack = VillageItemOwnership.produce(stack, ctx.mgr());
        }
        if (stack.isEmpty() || !npc.getInventory().addItem(stack)) {
            givenUp.put(item.getUUID(), ctx.level().getGameTime() + GIVE_UP_TICKS);
            return;
        }
        item.discard();
        carryingLoot = true;
        npc.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        ctx.level().playSound(null, npc.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.NEUTRAL,
                0.2f, 1.4f + npc.getRandom().nextFloat() * 0.4f);
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " ARCHER picked up " + stack.getCount()
                + "x " + stack.getHoverName().getString());
    }

    /** The nearest marked item in reach of a walk, inside the village, that fits in the pack. */
    @Nullable
    private ItemEntity nearestWanted(Activity.Context ctx) {
        NpcEntity npc = ctx.npc();
        UUID villageId = ctx.mgr().getVillageId();
        AABB box = npc.getBoundingBox().inflate(SEARCH_XZ, SEARCH_Y, SEARCH_XZ);
        ItemEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (ItemEntity item : ctx.level().getEntitiesOfClass(ItemEntity.class, box,
                e -> e.isAlive() && isFor(e, villageId))) {
            if (givenUp.containsKey(item.getUUID())) continue;
            if (!ctx.mgr().contains(item.blockPosition())) continue;
            ItemStack stack = item.getItem();
            ItemStack owned = VillageItemOwnership.mayUse(stack, ctx.mgr()) ? stack
                    : VillageItemOwnership.produce(stack.copy(), ctx.mgr());
            if (owned.isEmpty() || npc.getInventory().capacityFor(owned) < owned.getCount()) continue;
            double d = item.distanceToSqr(npc);
            if (d < bestSq) {
                bestSq = d;
                best = item;
            }
        }
        return best;
    }

    private void giveUp(Activity.Context ctx) {
        if (current != null) givenUp.put(current, ctx.level().getGameTime() + GIVE_UP_TICKS);
        stopWalking(ctx);
        current = null;
    }

    private void stopWalking(Activity.Context ctx) {
        if (move != null) {
            move.stop(ctx);
            move = null;
        }
    }

    /** Stops walking or banking; marked items stay where they are for later. */
    void cancel(Activity.Context ctx) {
        stopWalking(ctx);
        current = null;
        if (deposit != null) {
            deposit.stop(ctx);
            deposit = null;
        }
    }
}
