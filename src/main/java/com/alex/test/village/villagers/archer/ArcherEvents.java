package com.alex.test.village.villagers.archer;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.guard.GuardBalance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.projectile.AbstractArrow;
import com.alex.test.village.villagers.archer.behavior.combat.RailingSight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Arrow hits and kills by archers. An arrow lands long after the archer's
 * tick let it go, so the hits and kills wait here until the archer's own
 * activity collects them ({@link #takeCombat}) and pays them to its skill.
 */
public final class ArcherEvents {

    /** Archer npc id -> {hits, kills} by arrows that have not been paid yet. */
    private static final Map<UUID, int[]> PENDING_COMBAT = new HashMap<>();
    /** Kept on an arrow that struck a target block: its score (see {@link #targetScore(BlockHitResult)}). */
    private static final String TAG_TARGET_SCORE = "dynamicvillages_target_score";
    /** A score this high is a bullseye: the inner ring of the target face. */
    public static final int BULLSEYE_SCORE = 13;
    /** How far past a person, or short of a block, an arrow that flew through someone stops. */
    private static final double PASS_MARGIN = 0.05D;

    private ArcherEvents() {}

    /**
     * An archer's arrow flies straight through anything that is not a hostile
     * mob: villagers, players, pets and livestock, and also item frames, armor
     * stands and paintings. Those three never reach {@link #onIncomingDamage}:
     * vanilla pops the item out of a frame on any hit (a building token with
     * it), and an arrow breaks an armor stand outright.
     */
    @SubscribeEvent
    public static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;
        HitResult result = event.getRayTraceResult();
        if (result instanceof EntityHitResult hit) {
            if (hit.getEntity() instanceof Enemy) return;
            if (isArchers(arrow)) {
                event.setCanceled(true);
                stopJustPast(arrow, hit.getEntity());
            }
        } else if (result instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && isArchers(arrow)) {
            BlockState state = arrow.level().getBlockState(hit.getBlockPos());
            // The archer's own railing (a tower's fence or trapdoors) is shot
            // through, as the archer aims past it (RailingSight).
            Entity owner = arrow.getOwner();
            if (owner != null && RailingSight.isRailing(state)
                    && RailingSight.isNear(hit.getBlockPos(), owner.position())) {
                event.setCanceled(true);
                passRailing(arrow, hit.getBlockPos(), state, owner.position());
                return;
            }
            if (!arrow.level().isClientSide() && state.is(Blocks.TARGET)) {
                // Scored here, from where it struck: the block's own redstone output
                // is not updated again while a hit less than a second old still lights it.
                arrow.getPersistentData().putInt(TAG_TARGET_SCORE, targetScore(hit));
            }
        }
    }

    /**
     * Through the archer's own railing. Vanilla's move after the cancelled hit
     * would leave the arrow stuck in the fence (or fly it through whatever is
     * behind), so it is set down just past the railing block, or just short of
     * the next block in its way when that comes first. The next tick carries on
     * from there as usual; another post of the same railing is passed the same way.
     */
    private static void passRailing(AbstractArrow arrow, BlockPos pos, BlockState state, Vec3 archerFeet) {
        Vec3 start = arrow.position();
        Vec3 step = arrow.getDeltaMovement();
        double length = step.length();
        if (length < 1.0E-4D) return;
        Vec3 dir = step.scale(1.0D / length);
        VoxelShape shape = state.getCollisionShape(arrow.level(), pos);
        AABB box = shape.isEmpty() ? new AABB(pos) : shape.bounds().move(pos);
        // Where the path leaves the railing block, looked for back from well past it.
        Optional<Vec3> out = box.clip(start.add(dir.scale(length + 3.0D)), start);
        if (out.isEmpty()) return;
        double stop = start.distanceTo(out.get()) + PASS_MARGIN;
        Vec3 landing = start.add(dir.scale(stop));
        HitResult behind = RailingSight.clip(arrow.level(), start, landing, arrow, archerFeet);
        if (behind.getType() != HitResult.Type.MISS) {
            stop = Math.min(stop, start.distanceTo(behind.getLocation()) - PASS_MARGIN);
            landing = start.add(dir.scale(Math.max(0.0D, stop)));
        }
        // Vanilla adds the step to the arrow's position once this event returns.
        arrow.setPos(landing.subtract(step));
    }

    /**
     * After a pass-through, vanilla still moves the arrow its whole step this
     * tick, and never looks at what was behind the person: a wall, door or
     * glass pane right behind them was flown through (or the arrow ended up
     * inside it), and a mob or target right behind them was never hit. So the
     * step is cut short: to just past the person, or just short of the block
     * when that comes first. The next tick carries on from there as usual.
     */
    private static void stopJustPast(AbstractArrow arrow, Entity passed) {
        Vec3 start = arrow.position();
        Vec3 step = arrow.getDeltaMovement();
        double length = step.length();
        if (length < 1.0E-4D) return;
        Vec3 end = start.add(step);
        double stop = length;
        HitResult block = arrow.level().clip(new ClipContext(start, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, arrow));
        if (block.getType() != HitResult.Type.MISS) {
            stop = Math.min(stop, start.distanceTo(block.getLocation()) - PASS_MARGIN);
        }
        // Where the arrow leaves the person, with vanilla's 0.3 margin around arrow targets.
        Optional<Vec3> out = passed.getBoundingBox().inflate(0.3D).clip(end, start);
        if (out.isPresent()) stop = Math.min(stop, start.distanceTo(out.get()) + PASS_MARGIN);
        if (stop >= length) return;
        Vec3 landing = start.add(step.scale(Math.max(0.0D, stop) / length));
        // Vanilla adds the step to the arrow's position once this event returns.
        arrow.setPos(landing.subtract(step));
    }

    /**
     * How close to the middle of the face an arrow struck a target block:
     * vanilla's redstone strength, 1 at the rim to 15 dead centre.
     */
    static int targetScore(BlockHitResult hit) {
        Vec3 at = hit.getLocation();
        double dx = Math.abs(Mth.frac(at.x) - 0.5D);
        double dy = Math.abs(Mth.frac(at.y) - 0.5D);
        double dz = Math.abs(Mth.frac(at.z) - 0.5D);
        double off = switch (hit.getDirection().getAxis()) {
            case Y -> Math.max(dx, dz);
            case Z -> Math.max(dx, dy);
            case X -> Math.max(dy, dz);
        };
        return Math.max(1, Mth.ceil(15.0D * Mth.clamp((0.5D - off) / 0.5D, 0.0D, 1.0D)));
    }

    /** The score an archer's arrow made on a target block, or -1 if it hit none. */
    public static int targetScore(AbstractArrow arrow) {
        return arrow.getPersistentData().contains(TAG_TARGET_SCORE)
                ? arrow.getPersistentData().getInt(TAG_TARGET_SCORE) : -1;
    }

    private static boolean isArchers(AbstractArrow arrow) {
        return arrow.getOwner() instanceof NpcEntity npc && npc.getProfession() == Profession.ARCHER;
    }

    /**
     * Backstop for {@link #onProjectileImpact}: an archer's arrow only hurts
     * hostile mobs, whatever else it reaches.
     */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof Enemy) return;
        if (archerOf(event.getSource()) != null) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onDamaged(LivingDamageEvent.Post event) {
        if (event.getNewDamage() <= 0f) return;
        NpcEntity archer = archerOf(event.getSource());
        if (archer == null || !(event.getEntity() instanceof Enemy)) return;
        PENDING_COMBAT.computeIfAbsent(archer.getNpcId(), id -> new int[2])[0]++;
    }

    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        NpcEntity archer = archerOf(event.getSource());
        if (archer == null || !(event.getEntity() instanceof Enemy)) return;
        PENDING_COMBAT.computeIfAbsent(archer.getNpcId(), id -> new int[2])[1]++;
    }

    /**
     * What a hostile mob killed by the village's defenders drops that the
     * village can use is marked for the archers to gather once the danger is
     * cleared (see {@code FieldPickup}): arrows, string for bows, bones for
     * the farmers. A player's kills are left to the player.
     */
    @SubscribeEvent
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.level().isClientSide() || !(dead instanceof Enemy)) return;
        NpcEntity defender = defenderBehind(event.getSource(), dead);
        if (defender == null || defender.getVillageLink() == null) return;
        UUID villageId = defender.getVillageLink().villageId();
        for (ItemEntity drop : event.getDrops()) {
            if (isVillageLoot(drop.getItem())) {
                com.alex.test.village.villagers.archer.behavior.FieldPickup.mark(drop, villageId);
            }
        }
    }

    private static boolean isVillageLoot(ItemStack stack) {
        return stack.is(ItemTags.ARROWS) || stack.is(Items.STRING) || stack.is(Items.BONE);
    }

    /** The guard, captain or archer that killed {@code dead}, or null. */
    @Nullable
    private static NpcEntity defenderBehind(DamageSource source, LivingEntity dead) {
        Entity killer = source.getEntity() != null ? source.getEntity() : dead.getKillCredit();
        return killer instanceof NpcEntity npc && npc.getProfession().isDefender() ? npc : null;
    }

    /** Arrow hits and kills waiting for this archer as {hits, kills}, cleared as they are taken. */
    public static int[] takeCombat(UUID npcId) {
        int[] combat = PENDING_COMBAT.remove(npcId);
        return combat == null ? new int[2] : combat;
    }

    /** The archer that loosed the arrow behind {@code source}, if one did. */
    @Nullable
    static NpcEntity archerOf(DamageSource source) {
        if (!(source.getDirectEntity() instanceof AbstractArrow)) return null;
        Entity owner = source.getEntity();
        return owner instanceof NpcEntity npc && npc.getProfession() == Profession.ARCHER ? npc : null;
    }
}
