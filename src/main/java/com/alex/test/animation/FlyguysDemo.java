package com.alex.test.animation;

import com.alex.test.DynamicVillages;
import com.alex.test.ModBlocks;
import com.alex.test.block.WoodenChairBlock;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /villager flyguys [walk|stand|seed|all|stop]}: the outside animator's clips
 * (flyguys_animations, see tools/flyguys) on brainless villagers, each with what its clip needs:
 * the stand-up on a wooden chair at a trapdoor table, the sowing at a strip of farmland, the walk
 * pacing a lane at the clip's own feet speed so the planted foot does not slide. Every scene is
 * set side on to the caller. Clips loop through the mocap controller ({@link NpcEntity#MOCAP_CLIPS}).
 *
 * <p>Every block put down is remembered with what was there before and put back by
 * {@code stop}, when a demo villager disappears (e.g. {@code /villager mocap stop}) and when the
 * server shuts down, so nothing is left behind after a reload.
 */
public final class FlyguysDemo {

    /** On every demo villager: {@code /villager mocap stop} still removes them, but leaves their blocks to this class. */
    public static final String TAG = "dynamicvillages.flyguys_demo";

    /** flyguys_walk as made: the planted foot moves back 1.14 blocks a second (tools/flyguys feetSpeed). */
    private static final double WALK_PER_TICK = 1.14 / 20.0;
    /** Half the walking lane, in blocks. */
    private static final double LANE_HALF = 4.0;
    /**
     * The stand-up clip seats the villager itself: its hips sit 7 px behind and 5 px below the
     * feet point. So the villager stands on the floor this far in front of the chair's middle.
     */
    private static final double SEAT_OFFSET = 7 / 16.0;
    /** Farmland dries back to dirt and wheat grows; both are put back this often. */
    private static final int PROP_REFRESH_TICKS = 100;

    private enum Scene { STAND, SEED, WALK }

    private static final class Demo {
        final ServerLevel level;
        final Scene scene;
        final NpcEntity npc;
        /** What each placed block replaced, in placing order (ground before what stands on it). */
        final Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        /** What was placed there. */
        final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        Vec3 spot;
        float yaw;
        Vec3 laneDir;
        double along;
        int ticks;

        Demo(ServerLevel level, Scene scene, NpcEntity npc) {
            this.level = level;
            this.scene = scene;
            this.npc = npc;
        }
    }

    private static final List<Demo> DEMOS = new ArrayList<>();

    private FlyguysDemo() {}

    /**
     * Puts out one scene ({@code walk}, {@code stand}, {@code seed}) or all three in front of the
     * caller, replacing any flyguys demo already running in this level.
     */
    public static Component start(ServerLevel level, Entity caller, String which) {
        stop(level);
        Direction ahead = Direction.fromYRot(caller.getYRot());
        Direction right = ahead.getClockWise();
        BlockPos feet = caller.blockPosition();
        int y = feet.getY();
        List<String> made = new ArrayList<>(), failed = new ArrayList<>();
        boolean all = which.equals("all");
        if (all || which.equals("stand")) {
            BlockPos at = feet.relative(ahead, all ? 4 : 3).relative(right, all ? -4 : 0);
            note(standScene(level, at, y, right), "stand up from a chair", made, failed);
        }
        if (all || which.equals("seed")) {
            BlockPos at = feet.relative(ahead, all ? 4 : 3).relative(right, all ? 2 : -1);
            note(seedScene(level, at, y, ahead, right), "sow seeds", made, failed);
        }
        if (all || which.equals("walk")) {
            BlockPos at = feet.relative(ahead, all ? 8 : 3);
            note(walkScene(level, at, y, right), "walk", made, failed);
        }
        StringBuilder msg = new StringBuilder();
        if (!made.isEmpty()) msg.append("Flyguys demo: ").append(String.join(", ", made))
                .append(". Stop it with /villager flyguys stop.");
        if (!failed.isEmpty()) msg.append(msg.isEmpty() ? "" : " ").append("No flat open ground for: ")
                .append(String.join(", ", failed)).append(". Face an open spot and try again.");
        return Component.literal(msg.toString());
    }

    private static void note(Demo demo, String what, List<String> made, List<String> failed) {
        if (demo == null) { failed.add(what); return; }
        DEMOS.add(demo);
        made.add(what);
    }

    /** Chair facing {@code right} with a closed top trapdoor as the table in front of it. */
    private static Demo standScene(ServerLevel level, BlockPos near, int y, Direction right) {
        BlockPos chair = standSpot(level, near.getX(), near.getZ(), y);
        if (chair == null || !open(level, chair.relative(right))) return null;
        float yaw = right.toYRot();
        Vec3 spot = new Vec3(chair.getX() + 0.5 + right.getStepX() * SEAT_OFFSET, chair.getY(),
                chair.getZ() + 0.5 + right.getStepZ() * SEAT_OFFSET);
        NpcEntity npc = puppet(level, "flyguys_stand_from_chair", spot, yaw, "flyguys: stand up", false);
        if (npc == null) return null;
        Demo demo = new Demo(level, Scene.STAND, npc);
        demo.spot = spot;
        demo.yaw = yaw;
        put(demo, chair, ModBlocks.WOODEN_CHAIR.get().defaultBlockState().setValue(WoodenChairBlock.FACING, right));
        put(demo, chair.relative(right), Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, false)
                .setValue(TrapDoorBlock.FACING, right));
        return demo;
    }

    /**
     * Farmland three wide and two deep in front of a kneeling farmer: the near row bare (the hand
     * lands there), the far row young wheat.
     */
    private static Demo seedScene(ServerLevel level, BlockPos near, int y, Direction ahead, Direction right) {
        BlockPos stand = standSpot(level, near.getX(), near.getZ(), y);
        if (stand == null) return null;
        float yaw = right.toYRot();
        Vec3 spot = Vec3.atBottomCenterOf(stand);
        NpcEntity npc = puppet(level, "flyguys_seed", spot, yaw, "flyguys: sow", true);
        if (npc == null) return null;
        Demo demo = new Demo(level, Scene.SEED, npc);
        demo.spot = spot;
        demo.yaw = yaw;
        for (int row = 1; row <= 2; row++)
            for (int side = -1; side <= 1; side++) {
                BlockPos crop = stand.relative(right, row).relative(ahead, side);
                BlockState soil = level.getBlockState(crop.below());
                // only soil becomes farmland: never a chest, a floor or anything else worth keeping
                if (!open(level, crop) || !(soil.is(BlockTags.DIRT) || soil.is(Blocks.FARMLAND))) continue;
                put(demo, crop.below(), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, FarmBlock.MAX_MOISTURE));
                put(demo, crop, row == 1 ? Blocks.AIR.defaultBlockState()
                        : Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 2));
            }
        return demo;
    }

    /** A lane 2 x LANE_HALF long across the caller's view; the walker paces it end to end. */
    private static Demo walkScene(ServerLevel level, BlockPos centre, int y, Direction right) {
        Vec3 dir = new Vec3(right.getStepX(), 0, right.getStepZ());
        Vec3 start = Vec3.atBottomCenterOf(centre).subtract(dir.scale(LANE_HALF));
        BlockPos ground = standSpot(level, centre.getX(), centre.getZ(), y);
        if (ground == null) return null;
        start = new Vec3(start.x, ground.getY(), start.z);
        NpcEntity npc = puppet(level, "flyguys_walk", start, right.toYRot(), "flyguys: walk", false);
        if (npc == null) return null;
        Demo demo = new Demo(level, Scene.WALK, npc);
        demo.spot = start;
        demo.yaw = right.toYRot();
        demo.laneDir = dir;
        return demo;
    }

    /** A brainless, unhurtable villager looping {@code clip} where it is put. */
    private static NpcEntity puppet(ServerLevel level, String clip, Vec3 at, float yaw, String label, boolean farmer) {
        int index = NpcEntity.mocapClipByName(clip);
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (index <= 0 || npc == null) return null;
        pose(npc, at, yaw, true);
        if (farmer) npc.setProfession(Profession.FARMER);
        npc.setMocapClip(index);
        npc.setMocapHoldInPlace(true);   // this class moves it, not the mocap root motion
        npc.setItemSlot(EquipmentSlot.MAINHAND, NpcEntity.mocapTool(index));
        npc.setCustomName(Component.literal(label));
        npc.setCustomNameVisible(true);
        npc.setNoAi(true);
        npc.setInvulnerable(true);
        npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);   // saved, so a reload still knows it is a puppet
        npc.addTag(TAG);
        return level.addFreshEntity(npc) ? npc : null;
    }

    /** Same as NpcPreviewCommands.face: with no AI nothing turns the body, so set it outright. */
    private static void pose(NpcEntity npc, Vec3 at, float yaw, boolean snap) {
        if (snap) npc.moveTo(at.x, at.y, at.z, yaw, 0f);
        else {
            npc.setPos(at.x, at.y, at.z);
            npc.setYRot(yaw);
        }
        npc.setYBodyRot(yaw);
        npc.setYHeadRot(yaw);
        if (snap) {
            npc.yBodyRotO = yaw;
            npc.yHeadRotO = yaw;
            npc.yRotO = yaw;
        }
    }

    /**
     * The open cell nearest {@code y} (2 up to 4 down) in this column with firm ground under it
     * and room for a villager, so it works indoors as well as out. Null when there is none.
     */
    private static BlockPos standSpot(ServerLevel level, int x, int z, int y) {
        for (int dy : new int[]{0, 1, -1, 2, -2, -3, -4}) {
            BlockPos p = new BlockPos(x, y + dy, z);
            if (open(level, p) && open(level, p.above())
                    && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) return p;
        }
        return null;
    }

    /** Air or a plant/snow layer that a placed block may replace; never water or lava. */
    private static boolean open(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.canBeReplaced() && s.getFluidState().isEmpty();
    }

    private static void put(Demo demo, BlockPos pos, BlockState state) {
        BlockPos p = pos.immutable();
        demo.before.putIfAbsent(p, demo.level.getBlockState(p));
        demo.placed.put(p, state);
        demo.level.setBlockAndUpdate(p, state);
    }

    /** Ends every flyguys demo in {@code level}. */
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

    /**
     * Removes the villager and puts back what the props replaced, top block first so wheat is
     * not knocked off its farmland. A block someone changed since (not ours any more) is left.
     */
    private static void end(Demo demo) {
        if (!demo.npc.isRemoved()) demo.npc.discard();
        List<BlockPos> order = new ArrayList<>(demo.placed.keySet());
        for (int i = order.size() - 1; i >= 0; i--) {
            BlockPos p = order.get(i);
            if (stillOurs(demo.level.getBlockState(p), demo.placed.get(p)))
                demo.level.setBlockAndUpdate(p, demo.before.get(p));
        }
    }

    private static boolean stillOurs(BlockState now, BlockState placed) {
        if (now.is(placed.getBlock())) return true;
        // farmland dries to dirt, and /villager mocap stop digs props up to air
        return placed.is(Blocks.FARMLAND) && (now.is(Blocks.DIRT) || now.isAir());
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

    /** Quitting the world tidies up, so a reload finds no chairs, farmland or puppets left behind. */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Demo demo : DEMOS) end(demo);
        DEMOS.clear();
    }

    /** One tick of one demo; false once it should end (its villager or its chair is gone). */
    private static boolean tick(Demo demo) {
        NpcEntity npc = demo.npc;
        if (npc.isRemoved() || !npc.isAlive()) return false;
        demo.ticks++;
        switch (demo.scene) {
            case STAND -> {
                BlockPos chair = demo.placed.keySet().iterator().next();
                if (!demo.level.getBlockState(chair).is(ModBlocks.WOODEN_CHAIR.get())) return false;
                pose(npc, demo.spot, demo.yaw, false);
            }
            case SEED -> {
                pose(npc, demo.spot, demo.yaw, false);
                if (demo.ticks % PROP_REFRESH_TICKS == 0) refreshProps(demo);
            }
            case WALK -> {
                demo.along += WALK_PER_TICK;
                if (demo.along >= 2 * LANE_HALF) {
                    // the far end: turn round and walk back
                    demo.spot = demo.spot.add(demo.laneDir.scale(2 * LANE_HALF));
                    demo.laneDir = demo.laneDir.scale(-1);
                    demo.yaw = Mth.wrapDegrees(demo.yaw + 180f);
                    demo.along -= 2 * LANE_HALF;
                }
                Vec3 at = demo.spot.add(demo.laneDir.scale(demo.along));
                BlockPos ground = standSpot(demo.level, (int) Math.floor(at.x), (int) Math.floor(at.z), (int) Math.floor(npc.getY() + 0.5));
                pose(npc, new Vec3(at.x, ground != null ? ground.getY() : npc.getY(), at.z), demo.yaw, false);
            }
        }
        return true;
    }

    /** Farmland dried to dirt or wheat grown taller goes back to how it was put down. */
    private static void refreshProps(Demo demo) {
        for (Map.Entry<BlockPos, BlockState> e : demo.placed.entrySet()) {
            BlockState now = demo.level.getBlockState(e.getKey());
            if (now == e.getValue()) continue;
            if (stillOurs(now, e.getValue()) || now.is(Blocks.WHEAT))
                demo.level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_ALL);
        }
    }
}
