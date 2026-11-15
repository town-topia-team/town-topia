package com.alex.test.village.raid.warlord;

import com.alex.test.DynamicVillages;
import com.alex.test.ModSounds;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /vtest raid warlord showcase}: one 48-second take of everything the
 * Illager Warlord has, played in front of you to watch or record. It arrives
 * with the war horn and a roar, idles, roars at a line of three dummy defenders
 * and charges through them (they are thrown aside), turns and swings, takes
 * their hits, slams them away, spins with the axe out when they close in round
 * it, charges at a guard who jumps aside so it runs into a stone wall and
 * staggers dazed, walks to a door with a villager hiding behind it and pounds on
 * it (the door holds), leaps, lands, becomes enraged, swings faster, and falls.
 * A caption over the hotbar names each part.
 *
 * <p>It is a scripted scene, not a fight: the Warlord and the dummies have no
 * AI and every move is placed tick by tick, with the real clips, sounds and
 * effects. The wall and the door frame are only put into empty space and are
 * put back when the scene ends, is stopped ({@code showcase stop}) or the
 * world closes. Best in an open, flat spot outside any village.
 */
public final class WarlordShowcase {

    public static final String TAG = "dynamicvillages.warlord_showcase";

    /** The stage, in blocks: "ahead" is away from you, "right" is to your right. */
    private static final double STAGE_AHEAD = 10;
    private static final double START_RIGHT = 6;
    /** Where the guards line up for the first charge, and where it runs to (3 blocks past them, as in a fight). */
    private static final double LINE_RIGHT = -3;
    private static final double RUN_TO_RIGHT = -6;
    private static final double WALL_RIGHT = 9;
    private static final double DOOR_AHEAD = 5;
    private static final double DOOR_RIGHT = 5;
    private static final double WALK_SPEED = 0.12;
    private static final double DUMMY_WALK_SPEED = 0.12;
    private static final double DUMMY_RUN_SPEED = 0.2;
    /** Its real charging pace at its walking speed. */
    private static final double CHARGE_SPEED = WarlordMath.chargeBlocksPerTick(WarlordMath.walkSpeed(false));
    private static final int LEAP_TICKS = 16;
    /** Ticks the scripted moves start; where their blows land follows from the clips. */
    private static final int CHARGE_ROAR = 90;
    private static final int SWING = 200;
    private static final int SLAM = 280;
    private static final int SPIN = 385;
    private static final int WALL_ROAR = 470;
    private static final int LEAP = 720;
    private static final int ENRAGED_SWING = 805;
    private static final int KILL = 865;
    private static final int END_TICK = 965;
    private static final int QUIET = WarlordShowcaseStage.QUIET;

    private static final List<Show> SHOWS = new ArrayList<>();
    /** Leftover actors found loading from disk, taken away on the next level tick. */
    private static final List<Entity> LEFTOVERS = new ArrayList<>();

    private record Arc(Vec3 from, Vec3 to, int start, int length, double height) {}

    private record Walk(Vec3 to, double speed) {}

    private static final class Show {
        final ServerLevel level;
        final UUID viewerId;
        final Vec3 origin;
        final Direction ahead;
        final Direction right;
        final RaidWarlord warlord;
        final NpcEntity guardA;
        final NpcEntity guardB;
        final NpcEntity archer;
        final NpcEntity hider;
        /** Stage blocks: what stood there before and what the stage put there, in placing order. */
        final List<WarlordShowcaseStage.Placed> placed = new ArrayList<>();
        final BlockPos wallHit;
        final BlockPos door;
        int tick;
        /** When the daze after the wall ends; -1 while not dazed. */
        int dazedUntil = -1;
        @Nullable Vec3 walkTo;
        double walkSpeed;
        @Nullable Arc leap;
        /** The charge that runs into the wall (the other one stops past the guards). */
        boolean intoWall;
        boolean dodged;
        /** When the spin was started; -1 while not spinning. */
        int spinStart = -1;
        /** Dummies the running charge or the spinning blade already hit. */
        final Set<LivingEntity> struck = new HashSet<>();
        final Map<LivingEntity, Arc> thrown = new HashMap<>();
        final Map<LivingEntity, Walk> dummyWalks = new HashMap<>();

        Show(ServerLevel level, UUID viewerId, Vec3 origin, Direction ahead, RaidWarlord warlord,
             NpcEntity guardA, NpcEntity guardB, NpcEntity archer, NpcEntity hider, BlockPos wallHit, BlockPos door) {
            this.level = level;
            this.viewerId = viewerId;
            this.origin = origin;
            this.ahead = ahead;
            this.right = ahead.getClockWise();
            this.warlord = warlord;
            this.guardA = guardA;
            this.guardB = guardB;
            this.archer = archer;
            this.hider = hider;
            this.wallHit = wallHit;
            this.door = door;
        }

        Vec3 at(double aheadBy, double rightBy) {
            return stageAt(level, origin, ahead, aheadBy, rightBy);
        }

        List<NpcEntity> dummies() {
            return List.of(guardA, guardB, archer, hider);
        }

        List<NpcEntity> fighters() {
            return List.of(guardA, guardB, archer);
        }
    }

    private WarlordShowcase() {}

    // =========================================================================
    // Start and stop
    // =========================================================================

    public static Component start(ServerLevel level, ServerPlayer viewer) {
        stop(level);
        Direction ahead = viewer.getDirection();
        Direction right = ahead.getClockWise();
        Vec3 centre = Vec3.atBottomCenterOf(viewer.blockPosition());
        Vec3 origin = centre.add(Vec3.atLowerCornerOf(ahead.getNormal()).scale(STAGE_AHEAD));

        RaidWarlord warlord = DynamicVillages.RAID_WARLORD.get().create(level);
        if (warlord == null) return Component.literal("Could not create the Warlord.");
        Vec3 start = stageAt(level, origin, ahead, 0, START_RIGHT);
        warlord.moveTo(start.x, start.y, start.z, yawToward(start, centre), 0.0F);
        warlord.setYHeadRot(warlord.getYRot());
        warlord.setNoAi(true);
        warlord.setOmenLevel(3);
        warlord.setPersistenceRequired();
        warlord.addTag(TAG);

        // In a line across its path, close enough to its middle line to be trampled.
        NpcEntity guardA = dummy(level, stageAt(level, origin, ahead, 0, LINE_RIGHT), "guard dummy", Profession.GUARD, start);
        NpcEntity guardB = dummy(level, stageAt(level, origin, ahead, 1.1, LINE_RIGHT - 0.3), "guard dummy", Profession.GUARD, start);
        NpcEntity archer = dummy(level, stageAt(level, origin, ahead, -1.1, LINE_RIGHT - 0.3), "archer dummy", Profession.ARCHER, start);
        // Behind the door, away from the wall side the Warlord comes from after its daze.
        NpcEntity hider = dummy(level, stageAt(level, origin, ahead, DOOR_AHEAD, DOOR_RIGHT - 1.6), "hiding villager",
                Profession.FARMER, stageAt(level, origin, ahead, DOOR_AHEAD, DOOR_RIGHT + 3));
        if (guardA == null || guardB == null || archer == null || hider == null || !level.addFreshEntity(warlord)) {
            for (Entity e : new Entity[]{guardA, guardB, archer, hider, warlord}) if (e != null) e.discard();
            return Component.literal("Could not set up the showcase.");
        }
        // Found before the stage is built, while the ground there is still bare.
        BlockPos wallHit = BlockPos.containing(stageAt(level, origin, ahead, 0, WALL_RIGHT)).above();
        BlockPos door = BlockPos.containing(stageAt(level, origin, ahead, DOOR_AHEAD, DOOR_RIGHT));
        Show show = new Show(level, viewer.getUUID(), origin, ahead, warlord, guardA, guardB, archer, hider, wallHit, door);
        buildStage(show);
        // Written down at once, so a crash mid-show still gets the stage taken down at the next start.
        WarlordShowcaseStage.get(level.getServer()).remember(show.placed);
        SHOWS.add(show);
        return Component.literal("The Illager Warlord showcase starts in front of you (about 48 seconds)."
                + " Best in an open spot outside a village. Stop it early with /vtest raid warlord showcase stop.");
    }

    /** Ends every showcase in {@code level}: the actors go, the stage blocks are put back. Returns how many. */
    public static int stop(ServerLevel level) {
        int stopped = 0;
        for (Iterator<Show> it = SHOWS.iterator(); it.hasNext(); ) {
            Show show = it.next();
            if (show.level != level) continue;
            tearDown(show);
            it.remove();
            stopped++;
        }
        return stopped;
    }

    /**
     * The actors go (any saved into an unloaded chunk are removed when it loads
     * again, see {@link #onEntityJoinLevel}), and each stage block goes back to
     * what stood there, unless somebody has changed it since.
     */
    private static void tearDown(Show show) {
        if (!show.warlord.isRemoved()) show.warlord.discard();
        for (NpcEntity dummy : show.dummies()) if (!dummy.isRemoved()) dummy.discard();
        for (int i = show.placed.size() - 1; i >= 0; i--) WarlordShowcaseStage.putBack(show.level, show.placed.get(i));
        show.placed.clear();
        WarlordShowcaseStage.get(show.level.getServer()).forget(show.level.dimension());
    }

    /** A stage a crash left behind goes back before anyone sees it. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        WarlordShowcaseStage.get(event.getServer()).restoreLeftovers(event.getServer());
    }

    /**
     * A showcase actor loaded from disk that no running showcase owns (its chunk
     * was unloaded or saved mid-show): taken away, a tick later, like stale raiders.
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (!event.loadedFromDisk() || event.getLevel().isClientSide()) return;
        Entity entity = event.getEntity();
        if (!entity.getTags().contains(TAG) || ownedByAShow(entity)) return;
        LEFTOVERS.add(entity);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (LEFTOVERS.isEmpty() || event.getLevel().isClientSide()) return;
        LEFTOVERS.removeIf(entity -> {
            if (entity.level() != event.getLevel()) return false;
            if (!entity.isRemoved()) entity.discard();
            return true;
        });
    }

    private static boolean ownedByAShow(Entity entity) {
        for (Show show : SHOWS) {
            if (show.warlord.getUUID().equals(entity.getUUID())) return true;
            for (NpcEntity dummy : show.dummies()) if (dummy.getUUID().equals(entity.getUUID())) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        for (Show show : SHOWS) tearDown(show);
        SHOWS.clear();
    }

    // =========================================================================
    // The stage
    // =========================================================================

    @Nullable
    private static NpcEntity dummy(ServerLevel level, Vec3 at, String name, Profession profession, Vec3 facing) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(at.x, at.y, at.z, yawToward(at, facing), 0.0F);
        npc.setYHeadRot(npc.getYRot());
        npc.setCustomName(Component.literal(name));
        npc.setNoAi(true);
        npc.addTag(NpcEntity.MOCAP_PREVIEW_TAG);
        npc.addTag(TAG);
        if (!level.addFreshEntity(npc)) return null;
        npc.setProfession(profession);
        return npc;
    }

    /** The stone wall it charges into, and a door frame with a closed oak door. */
    private static void buildStage(Show show) {
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        for (int a = -2; a <= 2; a++) {
            BlockPos base = BlockPos.containing(show.at(a, WALL_RIGHT));
            for (int y = 0; y < 4; y++) place(show, base.above(y), wall);
        }
        for (int a : new int[]{-1, 1}) {
            BlockPos base = BlockPos.containing(show.at(DOOR_AHEAD + a, DOOR_RIGHT));
            for (int y = 0; y < 3; y++) place(show, base.above(y), wall);
        }
        Direction doorFacing = show.right.getOpposite();
        BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, doorFacing);
        if (place(show, show.door, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER))) {
            place(show, show.door.above(), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        }
        place(show, show.door.above(2), wall);
    }

    /** Puts {@code state} at {@code pos} only where nothing solid stands; remembers what was there. */
    private static boolean place(Show show, BlockPos pos, BlockState state) {
        BlockState before = show.level.getBlockState(pos);
        if (!before.isAir() && !before.canBeReplaced()) return false;
        show.placed.add(new WarlordShowcaseStage.Placed(show.level.dimension(), pos.immutable(), before, state));
        show.level.setBlock(pos, state, QUIET);
        return true;
    }

    // =========================================================================
    // The scene
    // =========================================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SHOWS.isEmpty()) return;
        for (Iterator<Show> it = SHOWS.iterator(); it.hasNext(); ) {
            Show show = it.next();
            if (!tick(show)) {
                tearDown(show);
                it.remove();
            }
        }
    }

    /** One tick of the scene; false once it is over (or its actors are gone). */
    private static boolean tick(Show show) {
        int t = show.tick++;
        if (t >= END_TICK) return false;
        for (NpcEntity dummy : show.dummies()) if (dummy.isRemoved()) return false;
        RaidWarlord w = show.warlord;
        boolean alive = !w.isRemoved() && w.isAlive();
        if (!alive && t < KILL) return false;
        ServerLevel level = show.level;
        Vec3 viewer = show.at(-STAGE_AHEAD, 0);

        switch (t) {
            case 0 -> caption(show, "The Illager Warlord arrives");
            case 10 -> {
                level.playSound(null, w.getX(), w.getY(), w.getZ(), ModSounds.RAID_WARLORD_HORN.get(), SoundSource.HOSTILE, 4.0F, 1.0F);
                w.roar();
            }
            case 50 -> caption(show, "It waits, breathing");
            case 60 -> w.playSound(ModSounds.RAID_WARLORD_IDLE.get(), 2.0F, 1.0F);
            case CHARGE_ROAR -> {
                caption(show, "It roars at the guards, then charges");
                face(w, show.guardA.position());
                w.roar();
            }
            case 170 -> {
                face(w, show.at(0, START_RIGHT));
                walkDummy(show, show.guardA, show.at(0, RUN_TO_RIGHT + 2.6), DUMMY_WALK_SPEED);
            }
            case 190 -> caption(show, "Heavy swing: everyone under the blade is hit");
            case SWING -> {
                face(w, show.guardA.position());
                w.playClip("swing");
                w.playSound(ModSounds.RAID_WARLORD_SWING.get(), 1.5F, 1.0F);
            }
            case 230 -> {
                caption(show, "The guards hit back");
                walkDummy(show, show.guardA, show.at(0, RUN_TO_RIGHT + 2.5), DUMMY_WALK_SPEED);
                walkDummy(show, show.guardB, show.at(1.8, RUN_TO_RIGHT + 1.7), DUMMY_WALK_SPEED);
                walkDummy(show, show.archer, show.at(-1.8, RUN_TO_RIGHT + 1.7), DUMMY_WALK_SPEED);
            }
            case 255 -> strike(show, show.guardB, 30.0F);
            case 267 -> strike(show, show.archer, 30.0F);
            case SLAM -> {
                caption(show, "Ground slam: everyone close is thrown back");
                show.dummyWalks.clear();
                w.playClip("slam");
            }
            case 325 -> {
                caption(show, "Surrounded, it spins with the axe held out");
                // In front of it, to its left and behind its right shoulder: the blade meets them in turn.
                walkDummy(show, show.guardA, show.at(0, RUN_TO_RIGHT + 2.8), DUMMY_WALK_SPEED);
                walkDummy(show, show.guardB, show.at(2.8, RUN_TO_RIGHT), DUMMY_WALK_SPEED);
                walkDummy(show, show.archer, show.at(-2.0, RUN_TO_RIGHT - 2.0), DUMMY_WALK_SPEED);
            }
            case SPIN -> {
                face(w, show.at(0, START_RIGHT));
                show.dummyWalks.clear();
                show.struck.clear();
                show.spinStart = t;
                w.playClip("spin");
                w.playSound(ModSounds.RAID_WARLORD_ROAR.get(), 1.5F, 1.25F);
            }
            case 430 -> {
                caption(show, "Jump aside from the charge and it runs into a wall");
                walkDummy(show, show.guardA, show.at(0, WALL_RIGHT - 3.5), DUMMY_RUN_SPEED);
            }
            case WALL_ROAR -> {
                face(w, show.guardA.position());
                w.roar();
            }
            case 590 -> {
                caption(show, "A villager hides behind a door");
                // Square in front of the door, on the open side: the frame is not in the way.
                walk(show, show.at(DOOR_AHEAD, DOOR_RIGHT + 1.9), WALK_SPEED);
            }
            case 655, 685 -> {
                if (t == 655) caption(show, "It pounds on the door. The door holds");
                face(w, Vec3.atBottomCenterOf(show.door));
                w.playClip("pound");
                WarlordEffects.poundDoor(level, show.door);
            }
            case 665, 695 -> WarlordEffects.doorDust(level, show.door);
            case LEAP -> {
                caption(show, "Stuck? It leaps clear");
                face(w, show.at(0, 0));
                w.playClip("leap");
            }
            case 740 -> stepIn(show.guardA, show.at(0, 6), show.at(0, 0));
            case 745 -> stepIn(show.archer, show.at(-1.8, 2.2), show.at(0, 0));
            case 750 -> face(w, viewer);
            case 755 -> {
                // Just under half its health: it becomes enraged by itself on the next tick.
                strike(show, show.archer, Math.max(1.0F, w.getHealth() - w.getMaxHealth() * 0.49F));
            }
            case 760 -> caption(show, "Below half health it is enraged: brighter eyes, quicker blows");
            case 765 -> {
                if (!w.isEnraged()) w.enrage();
                walkDummy(show, show.guardA, show.at(0, 2.6), DUMMY_WALK_SPEED);
            }
            case ENRAGED_SWING -> {
                caption(show, "Enraged swing");
                face(w, show.guardA.position());
                w.playClip("swing");
                w.playSound(ModSounds.RAID_WARLORD_SWING.get(), 1.5F, 1.1F);
            }
            case 840 -> stepIn(show.guardB, show.at(1.6, 2.2), w.position());
            case 855 -> {
                caption(show, "The Warlord falls");
                strike(show, show.guardB, 20.0F);
            }
            case KILL -> {
                w.skipDropExperience();
                w.kill();
            }
            case 925 -> caption(show, "That was the Illager Warlord");
            default -> {}
        }
        // The moments the clips land their blows, counted from when each began.
        if (t == CHARGE_ROAR + WarlordMath.clipTicks(WarlordMath.CHARGE_WINDUP_TICKS, false)) {
            charge(show, show.at(0, RUN_TO_RIGHT), false);
            caption(show, "Anyone in its path is trampled aside");
        }
        if (t == SWING + WarlordMath.clipTicks(WarlordMath.SWING_WINDUP_TICKS, false)) {
            hit(show, show.guardA, show.at(0, RUN_TO_RIGHT + 5.6), 10, 1.2);
        }
        if (t == SLAM + WarlordMath.clipTicks(WarlordMath.SLAM_WINDUP_TICKS, false)) {
            WarlordEffects.slam(level, w);
            for (NpcEntity dummy : show.fighters()) {
                Vec3 out = flat(dummy.position().subtract(w.position()));
                hit(show, dummy, ground(level, w.position().add(out.normalize().scale(6.0))), 14, 1.6);
            }
        }
        if (t == WALL_ROAR + WarlordMath.clipTicks(WarlordMath.CHARGE_WINDUP_TICKS, false)) {
            charge(show, show.at(0, WALL_RIGHT - 1.2), true);
        }
        if (t == LEAP + WarlordMath.clipTicks(6, false)) show.leap = new Arc(w.position(), show.at(0, 0), t, LEAP_TICKS, 3.5);
        if (t == ENRAGED_SWING + WarlordMath.clipTicks(WarlordMath.SWING_WINDUP_TICKS, true)) {
            hit(show, show.guardA, show.at(0, 6.5), 10, 1.2);
        }

        if (alive) {
            moveWarlord(show, t);
            if (show.spinStart >= 0) spin(show, t);
        }
        moveDummies(show, t);
        return true;
    }

    /** The Warlord's walk, charge or leap for this tick; a charge tramples the dummies in its way. */
    private static void moveWarlord(Show show, int t) {
        RaidWarlord w = show.warlord;
        if (show.dazedUntil >= 0 && t >= show.dazedUntil) {
            show.dazedUntil = -1;
            w.setAction(WarlordAction.NONE);
        }
        if (show.leap != null) {
            Arc arc = show.leap;
            float f = Math.min(1.0F, (t - arc.start()) / (float) arc.length());
            Vec3 p = arc.from().lerp(arc.to(), f).add(0, arc.height() * 4 * f * (1 - f), 0);
            w.moveTo(p.x, p.y, p.z, w.getYRot(), 0.0F);
            if (f >= 1.0F) {
                show.leap = null;
                w.playClip("land");
                WarlordEffects.landing(show.level, w);
            }
            return;
        }
        if (show.walkTo == null) return;
        Vec3 to = show.walkTo;
        Vec3 flat = new Vec3(to.x - w.getX(), 0, to.z - w.getZ());
        double left = flat.length();
        if (left < 0.05) {
            arrive(show, t);
            return;
        }
        face(w, to);
        Vec3 step = flat.scale(Math.min(show.walkSpeed, left) / left);
        // GeckoLib plays the walk only for a mob with a velocity; with no AI that
        // velocity moves nothing, so it is set to the step purely for the clip.
        setVelocity(w, step);
        // A little downward too, so it stays on the ground and its footsteps sound.
        w.move(MoverType.SELF, new Vec3(step.x, -0.1, step.z));
        if (w.action() == WarlordAction.CHARGE) {
            trample(show, flat.normalize());
            dodge(show);
        }
        // Something in the way (a tree, a bump): that is as far as it goes, so the scene never stalls.
        if (w.horizontalCollision) arrive(show, t);
    }

    /** The end of a walk or a charge; the charge into the wall ends in a daze. */
    private static void arrive(Show show, int t) {
        show.walkTo = null;
        RaidWarlord w = show.warlord;
        setVelocity(w, Vec3.ZERO);
        if (w.action() != WarlordAction.CHARGE) return;
        if (show.intoWall) {
            w.setAction(WarlordAction.DAZED);
            WarlordEffects.wallBump(show.level, w, show.wallHit);
            caption(show, "Into the wall: dazed for 2 seconds");
            show.dazedUntil = t + WarlordMath.DAZE_TICKS;
        } else {
            w.setAction(WarlordAction.NONE);
        }
    }

    /** The dummies its shoulder reaches are thrown to the side they stood on, once each. */
    private static void trample(Show show, Vec3 direction) {
        if (show.intoWall) return;
        RaidWarlord w = show.warlord;
        Vec3 across = new Vec3(-direction.z, 0, direction.x);
        for (NpcEntity dummy : show.fighters()) {
            if (show.struck.contains(dummy)) continue;
            Vec3 at = flat(dummy.position().subtract(w.position()));
            double along = at.dot(direction);
            double side = at.dot(across);
            if (along > 1.4 || along < -0.6 || Math.abs(side) > 1.4) continue;
            show.struck.add(dummy);
            Vec3 to = dummy.position().add(across.scale(side >= 0 ? 2.6 : -2.6)).add(direction.scale(1.0));
            hit(show, dummy, ground(show.level, to), 10, 1.0);
        }
    }

    /** The guard in front of the wall jumps aside at the last moment. */
    private static void dodge(Show show) {
        if (!show.intoWall || show.dodged) return;
        if (show.warlord.distanceTo(show.guardA) > 3.2) return;
        show.dodged = true;
        show.thrown.put(show.guardA, new Arc(show.guardA.position(), show.at(-2.6, WALL_RIGHT - 3.8), show.tick, 7, 0.8));
    }

    /** One tick of the spin: each dummy is hit as the blade passes it, as in a fight. */
    private static void spin(Show show, int t) {
        RaidWarlord w = show.warlord;
        int n = t - show.spinStart;
        boolean enraged = w.isEnraged();
        if (n >= WarlordMath.clipTicks(WarlordMath.SPIN_CLIP_TICKS, enraged)) {
            show.spinStart = -1;
            return;
        }
        if (n == WarlordMath.clipTicks((int) Math.round(WarlordMath.SPIN_TURN_FROM * 20), enraged)) {
            w.playSound(ModSounds.RAID_WARLORD_SWING.get(), 2.0F, 0.75F);
        }
        double before = WarlordMath.spinBladeBearing(WarlordMath.clipSeconds(n - 1, enraged));
        double after = WarlordMath.spinBladeBearing(WarlordMath.clipSeconds(n, enraged));
        if (after >= before) return;
        float yaw = w.yBodyRot;
        WarlordEffects.spinSweep(show.level, w, yaw, after);
        Vec3 forward = RaidWarlord.forward(yaw);
        Vec3 right = RaidWarlord.right(yaw);
        for (NpcEntity dummy : show.fighters()) {
            if (show.struck.contains(dummy)) continue;
            Vec3 at = flat(dummy.position().subtract(w.position()));
            if (!WarlordMath.inSpinZone(at.length(), dummy.getBbWidth() / 2.0)) continue;
            double bearing = WarlordSpinGoal.bearing(forward, right, at);
            if (!WarlordMath.sweptPast(before, after, bearing)) continue;
            show.struck.add(dummy);
            Vec3 way = flat(WarlordSpinGoal.flingWay(forward, right, at, bearing)).normalize();
            hit(show, dummy, ground(show.level, dummy.position().add(way.scale(3.0))), 12, 1.2);
        }
    }

    private static void moveDummies(Show show, int t) {
        for (Iterator<Map.Entry<LivingEntity, Arc>> it = show.thrown.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<LivingEntity, Arc> entry = it.next();
            Arc arc = entry.getValue();
            float f = Math.max(0.0F, Math.min(1.0F, (t - arc.start()) / (float) arc.length()));
            Vec3 p = arc.from().lerp(arc.to(), f).add(0, arc.height() * 4 * f * (1 - f), 0);
            LivingEntity dummy = entry.getKey();
            dummy.moveTo(p.x, p.y, p.z, dummy.getYRot(), 0.0F);
            if (f >= 1.0F) it.remove();
        }
        for (Iterator<Map.Entry<LivingEntity, Walk>> it = show.dummyWalks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<LivingEntity, Walk> entry = it.next();
            LivingEntity dummy = entry.getKey();
            if (show.thrown.containsKey(dummy)) continue;
            Walk walk = entry.getValue();
            Vec3 flat = new Vec3(walk.to().x - dummy.getX(), 0, walk.to().z - dummy.getZ());
            double left = flat.length();
            float yaw = yawToward(dummy.position(), show.warlord.position());
            if (left < 0.05) {
                dummy.setYRot(yaw);
                dummy.setYHeadRot(yaw);
                setVelocity(dummy, Vec3.ZERO);
                it.remove();
                continue;
            }
            Vec3 step = flat.scale(Math.min(walk.speed(), left) / left);
            Vec3 p = ground(show.level, dummy.position().add(step));
            dummy.moveTo(p.x, p.y, p.z, yaw, 0.0F);
            dummy.setYHeadRot(yaw);
            setVelocity(dummy, step);
        }
    }

    // =========================================================================
    // Pieces of the scene
    // =========================================================================

    private static void walk(Show show, Vec3 to, double speed) {
        show.walkTo = to;
        show.walkSpeed = speed;
    }

    /** It sets off running at its charging pace: into the wall, or past the guards it tramples. */
    private static void charge(Show show, Vec3 to, boolean intoWall) {
        show.intoWall = intoWall;
        show.dodged = false;
        show.struck.clear();
        show.warlord.setAction(WarlordAction.CHARGE);
        walk(show, to, CHARGE_SPEED);
    }

    private static void walkDummy(Show show, LivingEntity dummy, Vec3 to, double speed) {
        show.dummyWalks.put(dummy, new Walk(to, speed));
    }

    /** A dummy steps in out of shot, facing {@code facing}. */
    private static void stepIn(LivingEntity dummy, Vec3 at, Vec3 facing) {
        dummy.moveTo(at.x, at.y, at.z, yawToward(at, facing), 0.0F);
        dummy.setYHeadRot(dummy.getYRot());
    }

    private static Vec3 flat(Vec3 v) {
        return new Vec3(v.x, 0, v.z);
    }

    /** The blade lands on a dummy: it flinches and is thrown to {@code to}. It never dies. */
    private static void hit(Show show, LivingEntity dummy, Vec3 to, int ticks, double height) {
        flinch(show, dummy, show.warlord);
        show.thrown.put(dummy, new Arc(dummy.position(), to, show.tick, ticks, height));
    }

    /** A dummy strikes the Warlord: its hurt flash and grunt, and a bite out of its health bar. */
    private static void strike(Show show, NpcEntity dummy, float damage) {
        face(dummy, show.warlord.position());
        dummy.swing(net.minecraft.world.InteractionHand.MAIN_HAND, true);
        show.warlord.hurt(show.level.damageSources().mobAttack(dummy), damage);
    }

    private static void flinch(Show show, LivingEntity dummy, Entity by) {
        dummy.hurt(show.level.damageSources().mobAttack((LivingEntity) by), 1.0F);
        dummy.setHealth(dummy.getMaxHealth());
    }

    /** The velocity clients see (walk clips key off it); a mob with no AI is not moved by it. */
    private static void setVelocity(Entity entity, Vec3 velocity) {
        entity.setDeltaMovement(velocity.x, 0.0, velocity.z);
        entity.hasImpulse = true;
    }

    private static void caption(Show show, String text) {
        if (show.level.getServer().getPlayerList().getPlayer(show.viewerId) instanceof ServerPlayer viewer) {
            viewer.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GOLD), true);
        }
    }

    private static void face(LivingEntity entity, Vec3 target) {
        float yaw = yawToward(entity.position(), target);
        entity.setYRot(yaw);
        entity.setYBodyRot(yaw);
        entity.setYHeadRot(yaw);
    }

    private static float yawToward(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** A point on the stage at ground height: {@code aheadBy} away from the viewer, {@code rightBy} to their right. */
    private static Vec3 stageAt(ServerLevel level, Vec3 origin, Direction ahead, double aheadBy, double rightBy) {
        return ground(level, origin.add(Vec3.atLowerCornerOf(ahead.getNormal()).scale(aheadBy))
                .add(Vec3.atLowerCornerOf(ahead.getClockWise().getNormal()).scale(rightBy)));
    }

    private static Vec3 ground(ServerLevel level, Vec3 at) {
        return new Vec3(at.x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(at.x), Mth.floor(at.z)), at.z);
    }
}
