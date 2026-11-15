package com.alex.test.village.villagers.butcher.behavior;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * {@code /villager sharpen}: a butcher with no brain at a grindstone of its
 * own, sharpening on a loop, for looking at the visit without waiting a day
 * for a worn sword. Each round is the real one: same clip, same length
 * ({@link ButcherActivity#SHARPEN_TICKS}), same sound and sparks
 * ({@link ButcherActivity#grindstoneEffects}). The sword is never repaired.
 *
 * <p>The grindstone is put down only on an empty spot and taken up again by
 * {@code /villager sharpen stop}; one that was already there is left alone.
 */
public final class ButcherSharpenDemo {

    /** Standing between rounds, so each one shows the clip starting again. */
    private static final int PAUSE_TICKS = 30;
    /** How far ahead of you the grindstone goes. */
    private static final double AHEAD = 4.0;

    private static final class Demo {
        final ServerLevel level;
        final NpcEntity butcher;
        final BlockPos grindstone;
        final boolean placed;
        int ticks;
        int pause;

        Demo(ServerLevel level, NpcEntity butcher, BlockPos grindstone, boolean placed) {
            this.level = level;
            this.butcher = butcher;
            this.grindstone = grindstone;
            this.placed = placed;
        }
    }

    private static final List<Demo> DEMOS = new ArrayList<>();

    private ButcherSharpenDemo() {}

    /**
     * A grindstone {@link #AHEAD} blocks in front of {@code caller}, and the
     * butcher beside it on the caller's right, so the visit is seen side on.
     */
    public static Component start(ServerLevel level, Entity caller) {
        Vec3 forward = Vec3.directionFromRotation(0, caller.getYRot());
        Vec3 ahead = caller.position().add(forward.scale(AHEAD));
        int gx = Mth.floor(ahead.x), gz = Mth.floor(ahead.z);
        BlockPos grindstone = new BlockPos(gx, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, gx, gz), gz);

        // The butcher stands on the caller's right of the wheel, looking across it.
        Direction toRight = Direction.fromYRot(caller.getYRot()).getClockWise();
        BlockPos stand = grindstone.relative(toRight);
        Direction looking = toRight.getOpposite();

        boolean placed = false;
        BlockState here = level.getBlockState(grindstone);
        if (here.is(Blocks.GRINDSTONE)) {
            // Somebody's own grindstone: use it, leave it.
        } else if (here.canBeReplaced()) {
            level.setBlockAndUpdate(grindstone, Blocks.GRINDSTONE.defaultBlockState()
                    .setValue(GrindstoneBlock.FACE, AttachFace.FLOOR)
                    .setValue(GrindstoneBlock.FACING, looking));
            placed = true;
        } else {
            return Component.literal("No room for a grindstone at " + grindstone.toShortString()
                    + ". Face an open spot and try again.");
        }

        NpcEntity butcher = DynamicVillages.NPC.get().create(level);
        if (butcher == null) {
            if (placed) level.setBlockAndUpdate(grindstone, Blocks.AIR.defaultBlockState());
            return Component.literal("Could not create the demo butcher.");
        }
        butcher.moveTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, looking.toYRot(), 0f);
        butcher.setCustomName(Component.literal("sharpen demo"));
        butcher.setCustomNameVisible(true);
        butcher.setNoAi(true);
        butcher.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
        butcher.setProfession(Profession.BUTCHER);
        if (!level.addFreshEntity(butcher)) {
            if (placed) level.setBlockAndUpdate(grindstone, Blocks.AIR.defaultBlockState());
            return Component.literal("Could not create the demo butcher.");
        }
        DEMOS.add(new Demo(level, butcher, grindstone, placed));
        return Component.literal("A demo butcher is sharpening at a grindstone, "
                + String.format(java.util.Locale.ROOT, "%.0f", ButcherActivity.SHARPEN_TICKS / 20.0)
                + " s at a time like the real visit. Stop it with /villager sharpen stop.");
    }

    /** Removes every demo butcher in {@code level} and the grindstones put down for them. */
    public static int stop(ServerLevel level) {
        int stopped = 0;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (demo.level != level) continue;
            end(demo);
            it.remove();
            stopped++;
        }
        return stopped;
    }

    private static void end(Demo demo) {
        if (!demo.butcher.isRemoved()) demo.butcher.discard();
        if (demo.placed && demo.level.getBlockState(demo.grindstone).is(Blocks.GRINDSTONE)) {
            demo.level.setBlockAndUpdate(demo.grindstone, Blocks.AIR.defaultBlockState());
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (DEMOS.isEmpty()) return;
        for (Iterator<Demo> it = DEMOS.iterator(); it.hasNext(); ) {
            Demo demo = it.next();
            if (!tick(demo)) {
                end(demo);
                it.remove();
            }
        }
    }

    /** One tick of one demo; false once it should end (the butcher or the grindstone is gone). */
    private static boolean tick(Demo demo) {
        NpcEntity butcher = demo.butcher;
        if (butcher.isRemoved() || !butcher.isAlive()
                || !demo.level.getBlockState(demo.grindstone).is(Blocks.GRINDSTONE)) {
            return false;
        }
        face(butcher, Vec3.atCenterOf(demo.grindstone));
        if (demo.pause > 0) {
            demo.pause--;
            return true;
        }
        if (demo.ticks == 0) {
            // The sword comes out and the clip starts from its first frame, as at the real wheel.
            butcher.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            butcher.setAnimation(VillagerAnimationState.CRAFTING);
        }
        if (demo.ticks % 10 == 0) ButcherActivity.grindstoneEffects(demo.level, demo.grindstone);
        if (++demo.ticks >= ButcherActivity.SHARPEN_TICKS) {
            butcher.setAnimation(VillagerAnimationState.IDLE);
            butcher.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            demo.ticks = 0;
            demo.pause = PAUSE_TICKS;
        }
        return true;
    }

    /** Turns a brainless puppet to face {@code at}: body, head and all (no AI, so no look control). */
    private static void face(NpcEntity npc, Vec3 at) {
        double dx = at.x - npc.getX();
        double dz = at.z - npc.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90f;
        npc.setYRot(yaw);
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
    }
}
