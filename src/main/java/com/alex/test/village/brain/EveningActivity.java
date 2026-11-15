package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.bard.behavior.BardActivity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import com.alex.test.animation.VillagerAnimationState;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Spending the evening somewhere: relaxing at home, at the tavern, or on a
 * visit to a neighbour's house.
 *
 * <p>The villager walks to a spot in the venue, settles there, and every so
 * often maybe moves. Chairs come first everywhere: at home most of the time,
 * and for tavern and visit guests whenever one is free. Once seated it stays
 * a good while. While settled it is open to conversation (see
 * {@link VillagerBrain#isOpenToConversation}), and the
 * {@link SocialCoordinator} pairs it with whoever is beside it.
 *
 * <p>A tavern seats its guests on its own chairs first. Each night it has room
 * for 0-3 standing guests (rolled per tavern per night); after that a guest
 * takes a free chair outside within {@value #TAVERN_OUTDOOR_CHAIRS} blocks of
 * the door, and a guest who finds none of those gives up
 * ({@link #wasFull}) and the brain sends it visiting or home.
 *
 * <p>The activity decides only where to be. When to go, when to eat and when
 * to leave belong to {@link EveningPlanner} and the brain. A conversation or a
 * meal replaces this activity, and the brain starts a fresh one afterwards; a
 * fresh one keeps the villager on the chair it is already sitting on when that
 * chair belongs here, so a chat or a meal no longer gets everyone up.
 */
public final class EveningActivity extends Activity {

    public enum Mode { HOME, TAVERN, VISIT }

    /** How a villager tends to settle in tonight; see {@link VillagerBrain#eveningStyle}. */
    public enum Style {
        /** Heads for a chair beside somebody if it can. */
        SEEK_COMPANY,
        /** Likes a chair near others, but any will do. */
        MINGLE_THEN_SIT,
        /** Keeps to its own corner and chair. */
        STAND
    }

    /** Chairs outside the tavern still count as its seats up to this far from its door. */
    private static final int TAVERN_OUTDOOR_CHAIRS = 8;
    /** Standing guests a tavern allows: 0 to this many, rolled per tavern per night. */
    public static final int MAX_TAVERN_STANDERS = 3;
    private static final long SALT_STANDERS = 0x57A4D;
    /** Share of picks at home that take a free chair rather than standing. */
    private static final float HOME_SIT_CHANCE = 0.85f;
    /** Random tiles tried when looking for a free one. */
    private static final int SPOT_TRIES = 8;
    /** Failed walks before the venue is given up for this evening. */
    private static final int MAX_WALK_FAILURES = 3;
    /** A villager that finished walking further than this from its chair did not really reach it. */
    private static final double SIT_REACH_SQ = 4.0;
    /** Standing this far from somebody (flat, squared) reads as standing with them: the pairing range. */
    private static final double MIN_MINGLE_SQ = 1.0;
    private static final double MAX_MINGLE_SQ = 5.0;
    /** How close to the bard counts as sitting where the music is. */
    private static final int BARD_LISTEN_RADIUS = 6;
    /** How long a house's chair count is trusted. */
    private static final int CHAIR_COUNT_TICKS = 1_200;

    private final Mode     mode;
    private final Building venue;

    private Style    style = Style.SEEK_COMPANY;
    private List<VillageChairs.Chair> chairs = List.of();
    /** The tavern's chairs outside, near its door; only used once there is no room inside. */
    private List<VillageChairs.Chair> outdoorChairs = List.of();
    @Nullable private VillageChairs.Chair chair;
    @Nullable private BlockPos favourite;
    private boolean  favouriteNight;
    private boolean  listensToBard;
    private BlockPos target;
    private float    speed = VillagerModel.WALK_SPEED;
    private boolean  begun;
    private boolean  arrived;
    /** A tavern guest on its feet by the tavern's leave, not for want of trying a chair. */
    private boolean  standing;
    /** No chair and no standing room at the tavern: the brain sends this guest elsewhere. */
    private boolean  full;
    /** The visit's host was not home when the guest arrived. */
    private boolean  hostMissing;
    private int      picks;
    private int      walkFailures;
    private int      settledTicks;
    private int      driftIn;
    private int      noiseIn;

    // --- A guest's visit (VISIT mode) ----------------------------------------
    /** How the guest gets in: at the door, or straight in. */
    private enum Entry { NONE, TO_DOOR, KNOCKING, WAITING, TO_VIEW, WAVING, INSIDE }
    private VisitRules.Welcome welcome = VisitRules.Welcome.WALK_IN;
    private Entry    entry = Entry.NONE;
    private int      entryTimer;
    private int      knocksLeft;
    private boolean  wavedIn;
    /** The host's activity answering the door for this guest, once asked. */
    @Nullable private EveningActivity greeter;
    /** The host went to bed or away mid-visit; the visit ended early. */
    private boolean  hostLeft;
    private String   hostLeftReason = "left";
    private int      hostMisses;
    /** Leaving at the end of the visit, walked to the door by the host. */
    private boolean  leaving;
    private int      leaveWave;
    private int      clearWait;
    private boolean  wavedOut;
    private boolean  snackTried;

    // --- A host (HOME mode) answering the door or seeing a guest out --------
    @Nullable private NpcEntity greetGuest;
    private boolean  greetSeeOut;
    /** 0 walking to the door, 1 at the door, 2 door open and waving. */
    private int      greetState;
    private int      greetTimer;
    private boolean  openedDoor;
    private int      waveTimer;
    @Nullable private NpcEntity waveAt;

    private static final int KNOCK_GAP_TICKS = 7;
    private static final int GREET_TIMEOUT_TICKS = 400;
    private static final int WAIT_AT_DOOR_TICKS = 400;
    private static final int HOST_CHECK_TICKS = 40;
    private static final double DOOR_NEAR_SQ = 9.0;
    /** Sleeping villagers this close make evening noise quieter. */
    public static final double SLEEPER_QUIET_RADIUS = 8.0;

    public EveningActivity(Mode mode, Building venue) {
        this.mode  = mode;
        this.venue = venue;
    }

    /** The host went to bed or away before the visit's time was up. */
    public boolean hostLeft() { return hostLeft; }
    public String hostLeftReason() { return hostLeftReason; }
    /** This guest was welcomed at the door, so a chat need not open with greetings. */
    public boolean greetedAtDoor() { return wavedIn; }

    public Mode mode()       { return mode; }
    public Building venue()  { return venue; }
    public boolean isHangingAround() { return arrived; }
    /** Ticks since the villager settled in; zero while still on the way. */
    public int settledTicks() { return arrived ? settledTicks : 0; }
    /** Tonight's tavern had no chair and no standing room for this guest. */
    public boolean wasFull() { return full; }
    /** The visit failed because the host was out. */
    public boolean hostMissing() { return hostMissing; }
    /** Nobody answered at the door and no awake host was home: the guest did not go in. */
    public boolean noAnswer() { return noAnswer; }
    private boolean  noAnswer;
    /** One of the tavern's standing guests tonight (on its way or there). */
    public boolean isTavernStander() { return mode == Mode.TAVERN && begun && standing; }

    @Override
    protected void start(Context ctx) {
        begun = true;
        VillagerBrain brain = ctx.brain();
        style          = brain.eveningStyle();
        favouriteNight = brain.eveningRoll(0x7AB1E, 100) < 70;
        listensToBard  = mode == Mode.TAVERN && brain.eveningRoll(0xBA4D, 100) < 35;
        speed          = VillagerModel.WALK_SPEED;
        if (mode == Mode.TAVERN) {
            // One scan: chairs sat down to from inside are the tavern's own.
            List<VillageChairs.Chair> inside = new ArrayList<>();
            List<VillageChairs.Chair> outside = new ArrayList<>();
            for (VillageChairs.Chair c : SocializeActivity.venueChairs(ctx, venue, TAVERN_OUTDOOR_CHAIRS)) {
                (venue.getNavMeshBlocks().contains(c.approach()) ? inside : outside).add(c);
            }
            chairs = inside;
            outdoorChairs = outside;
        } else {
            chairs = SocializeActivity.venueChairs(ctx, venue, 0);
        }
        favourite = favouriteSpot(ctx);
        noiseIn = 200 + ctx.npc().getRandom().nextInt(400);

        // Back from a chat or a meal, still on one of this venue's chairs:
        // stay seated rather than getting up to choose a place from scratch.
        BlockPos seat = ctx.npc().isChairSeated() ? ctx.npc().seatedChairPos() : null;
        if (seat != null) {
            VillageChairs.Chair mine = chairAt(chairs, seat);
            if (mine == null) mine = chairAt(outdoorChairs, seat);
            if (mine != null) {
                chair = mine;
                VillageChairs.claim(ctx.level(), mine.pos(), ctx.npc().getUUID());
                target = mine.approach();
                arrived = true;
                picks = 1;
                settledTicks = 0;
                driftIn = driftDelay(ctx);
                if (mode != Mode.HOME) brain.onEveningArrived(ctx, venue);
                return;
            }
            ctx.npc().standUpFromChair();
        }
        pickTarget(ctx);
        // A guest from outside comes to the door on some visits: rolled per visit.
        if (mode == Mode.VISIT && !venue.getNavMeshBlocks().contains(ctx.npc().blockPosition())) {
            welcome = brain.visitWelcome();
            if ((welcome == VisitRules.Welcome.KNOCK || welcome == VisitRules.Welcome.AT_DOOR)
                    && outsideTile(ctx) != null && isDoor(ctx, doorBlock())) {
                entry = Entry.TO_DOOR;
            } else if (welcome == VisitRules.Welcome.WAVE_FROM_INSIDE) {
                // In through the door, then into the host's view to wave before sitting.
                entry = Entry.TO_VIEW;
            }
        }
    }

    @Override
    protected Status tick(Context ctx) {
        if (!venue.isValid() || venue.getNavMeshBlocks().isEmpty()) return Status.FAILED;
        hostNpc = ctx.npc();
        if (leaving) return tickLeaving(ctx);
        if (ctx.brain().eveningVenueOver(ctx, mode, venue)) {
            // Some visits end with the host walking the guest to the door.
            if (mode == Mode.VISIT && arrived && ctx.brain().eveningPhase(ctx) != EveningSchedule.Phase.DAY
                    && ctx.brain().visitSeeOut() && startLeaving(ctx)) {
                return Status.RUNNING;
            }
            endGreeting(ctx);
            return Status.DONE;
        }
        if (full || target == null) return Status.FAILED;
        if (chair != null) VillageChairs.claim(ctx.level(), chair.pos(), ctx.npc().getUUID());
        if (standSpot != null) StandClaims.claim(ctx.level(), standSpot, ctx.npc().getUUID());
        if (greetGuest != null) return tickGreeting(ctx);
        if (entry != Entry.NONE && entry != Entry.INSIDE) {
            Status s = tickEntry(ctx);
            if (s != null) return s;
        }

        if (!arrived) {
            if (!moveTo(ctx, target, speed)) return Status.RUNNING;
            if (moveFailed(ctx, target)) {
                releaseChair(ctx);
                if (++walkFailures >= MAX_WALK_FAILURES) return Status.FAILED;
                pickTarget(ctx);
                resetMove();
                return full || target == null ? Status.FAILED : Status.RUNNING;
            }
            // A host must actually be home when the guest gets there.
            if (mode == Mode.VISIT && !ctx.brain().hostHome(ctx, venue)) {
                hostMissing = true;
                return Status.FAILED;
            }
            if (chair != null && !sitDown(ctx)) {
                // Somebody got there first: choose again rather than stand
                // where standing is not allowed.
                releaseChair(ctx);
                if (++walkFailures >= MAX_WALK_FAILURES) return Status.FAILED;
                pickTarget(ctx);
                resetMove();
                return full || target == null ? Status.FAILED : Status.RUNNING;
            }
            arrived = true;
            settledTicks = 0;
            driftIn = driftDelay(ctx);
            if (mode != Mode.HOME) ctx.brain().onEveningArrived(ctx, venue);
            if (mode == Mode.VISIT) onGuestSettled(ctx);
            return Status.RUNNING;
        }

        // Knocked off the chair (it was broken): stay on foot.
        if (chair != null && !ctx.npc().isChairSeated()) releaseChair(ctx);
        settledTicks++;

        if (snackTimer > 0) tickSnack(ctx);
        if (waveTimer > 0) {
            // Watching a guest come in: the head follows it; the pose is left alone.
            if (waveAt != null) ctx.npc().getLookControl().setLookAt(waveAt, 30f, 30f);
            if (--waveTimer == 0) {
                if (!watching) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                watching = false;
            }
        }

        // A guest keeps checking its host is still up; a host gone to bed ends the visit.
        if (mode == Mode.VISIT
                && Math.floorMod(ctx.level().getGameTime() + ctx.npc().getUUID().hashCode(), HOST_CHECK_TICKS) == 0) {
            if (ctx.brain().hostHome(ctx, venue)) {
                hostMisses = 0;
            } else if (++hostMisses >= 2) {
                hostLeft = true;
                hostLeftReason = residentAsleep(ctx) ? "asleep" : "left";
                return Status.DONE;
            }
        }
        if (mode == Mode.VISIT
                && Math.floorMod(ctx.level().getGameTime() + ctx.npc().getUUID().hashCode(), 10) == 0) {
            visitChatCheck(ctx);
        }
        if (mode == Mode.VISIT && !snackTried && settledTicks >= 150) {
            snackTried = true;
            maybeShareSnack(ctx);
        }

        if (--noiseIn <= 0) {
            boolean quiet = sleeperNear(ctx, ctx.npc().blockPosition(), SLEEPER_QUIET_RADIUS);
            noiseIn = (mode == Mode.HOME ? 400 : 200) * (quiet ? 2 : 1) + ctx.npc().getRandom().nextInt(400);
            ctx.level().playSound(null, ctx.npc().getX(), ctx.npc().getY(), ctx.npc().getZ(),
                    SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                    quiet ? 0.12f : 0.35f, 0.9f + ctx.npc().getRandom().nextFloat() * 0.2f);
        }

        if (--driftIn <= 0) drift(ctx);
        return Status.RUNNING;
    }

    // =========================================================================
    // Visits: coming in at the door, the host answering, seeing out
    // =========================================================================

    /** The door block: the building's door, lower half. */
    private BlockPos doorBlock() {
        return venue.getDoorPos();
    }

    /** The walkable tile just outside the door, or null. */
    @Nullable
    private BlockPos outsideTile(Context ctx) {
        return routable(ctx, doorBlock().relative(venue.getInteriorDirection().getOpposite()));
    }

    /** The walkable tile just inside the door, or null. */
    @Nullable
    private BlockPos insideTile(Context ctx) {
        return routable(ctx, doorBlock().relative(venue.getInteriorDirection()));
    }

    /**
     * Where a host waits to open the door: beside the tile inside the door,
     * so it is not standing in the doorway the guest walks through.
     */
    @Nullable
    private BlockPos greetTile(Context ctx) {
        BlockPos inside = insideTile(ctx);
        if (inside == null) return null;
        Direction in = venue.getInteriorDirection();
        for (BlockPos p : new BlockPos[] {inside.relative(in.getClockWise()),
                inside.relative(in.getCounterClockWise()), inside.relative(in)}) {
            BlockPos tile = routable(ctx, p);
            if (tile != null && venue.getNavMeshBlocks().contains(tile)) return tile;
        }
        return inside;
    }

    @Nullable
    private static BlockPos routable(Context ctx, BlockPos pos) {
        com.alex.test.village.NavGraph graph = ctx.mgr().getGraph();
        if (graph == null) return null;
        for (int dy : new int[] {0, -1, 1}) {
            com.alex.test.village.NavNode node = graph.nodeAt(pos.getX(), pos.getY() + dy, pos.getZ());
            if (node != null && node.isRoutable()) return node.feetPos();
        }
        return null;
    }

    /** A door the host can open for a guest (an iron door is redstone's, bug 5 of the raider plan). */
    private static boolean isDoor(Context ctx, BlockPos pos) {
        BlockState state = ctx.level().getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && com.alex.test.village.RouteDoorHandler.handOpens(state);
    }

    /** Opens or closes the door; returns true if it changed. */
    private static boolean setDoor(Context ctx, BlockPos pos, boolean open) {
        BlockState state = ctx.level().getBlockState(pos);
        if (!(state.getBlock() instanceof DoorBlock door)) return false;
        if (state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            pos = pos.below();
            state = ctx.level().getBlockState(pos);
            if (!(state.getBlock() instanceof DoorBlock)) return false;
        }
        if (state.getValue(DoorBlock.OPEN) == open) return false;
        if (!com.alex.test.village.RouteDoorHandler.handOpens(state)) return false;
        door.setOpen(ctx.npc(), ctx.level(), state, pos, open);
        return true;
    }

    /**
     * The guest's way in when the visit starts at the door: walk up, knock
     * (or find the host already waiting), wait for the host to open, wave,
     * then go in and sit. None of this counts towards the visit: its clock
     * starts once the guest is settled inside. Returns null once inside.
     */
    @Nullable
    private Status tickEntry(Context ctx) {
        NpcEntity npc = ctx.npc();
        switch (entry) {
            case TO_DOOR -> {
                BlockPos outside = outsideTile(ctx);
                if (outside == null) { entry = Entry.INSIDE; resetMove(); return null; }
                // A host who sees the guest coming goes to the door ahead of it.
                if (welcome == VisitRules.Welcome.AT_DOOR && greeter == null
                        && npc.blockPosition().distSqr(outside) < 144) {
                    greeter = summonHost(ctx, false);
                }
                if (!moveTo(ctx, outside, speed)) return Status.RUNNING;
                if (moveFailed(ctx, outside)) { entry = Entry.INSIDE; resetMove(); return null; }
                if (!ctx.brain().hostHome(ctx, venue)) {
                    hostMissing = true;
                    return Status.FAILED;
                }
                ctx.model().stopMoving();
                claimStand(ctx, outside);
                // The walk stops short: step onto the tile right in front of the door.
                if (!settleOnto(ctx, outside)) return Status.RUNNING;
                if (welcome == VisitRules.Welcome.KNOCK) {
                    entry = Entry.KNOCKING;
                    knocksLeft = 3;
                    entryTimer = 0;
                } else {
                    if (greeter == null) greeter = summonHost(ctx, false);
                    entry = Entry.WAITING;
                    entryTimer = WAIT_AT_DOOR_TICKS;
                }
                return Status.RUNNING;
            }
            case KNOCKING -> {
                lookAt(ctx, doorBlock());
                if (--entryTimer > 0) return Status.RUNNING;
                if (knocksLeft-- > 0) {
                    knock(ctx);
                    entryTimer = KNOCK_GAP_TICKS;
                    return Status.RUNNING;
                }
                if (greeter == null) greeter = summonHost(ctx, false);
                entry = Entry.WAITING;
                entryTimer = WAIT_AT_DOOR_TICKS;
                return Status.RUNNING;
            }
            case WAITING -> {
                entryTimer--;
                // Everyone went to bed or out while it waited: it does not go in.
                if (!wavedIn && entryTimer % 20 == 0 && !ctx.brain().hostHome(ctx, venue)) {
                    noAnswer = true;
                    return Status.FAILED;
                }
                NpcEntity host = greeter != null && greeter.doorOpenedFor(npc) ? greeter.hostNpc : null;
                if (host != null) {
                    // Door open: turn to the host, and wave once both face each other.
                    face(npc, host);
                    if (!wavedIn && (facing(npc, host) && facing(host, npc) || ++faceWait > FACE_WAIT_TICKS)) {
                        wavedIn = true;
                        npc.setAnimation(VillagerAnimationState.WAVE);
                        entryTimer = Math.min(entryTimer, 30);
                    }
                } else {
                    lookAt(ctx, doorBlock());
                    // Host busy (a chat, a meal) or not come yet: ask again now and
                    // then, and knock once more halfway.
                    if ((greeter == null || !greeter.isGreeting(npc)) && entryTimer % 40 == 0) {
                        greeter = summonHost(ctx, false);
                    }
                    if (welcome == VisitRules.Welcome.KNOCK && entryTimer == WAIT_AT_DOOR_TICKS / 2) {
                        knock(ctx);
                    }
                }
                if (entryTimer > 0) return Status.RUNNING;
                // Waved in: wait (a few seconds at most) for the host to step out of the doorway.
                if (wavedIn && greeter != null && !greeter.doorwayClearFor(npc) && ++clearWait < 80) {
                    return Status.RUNNING;
                }
                // Never opened: in it goes only if somebody is still up.
                if (!wavedIn && !ctx.brain().hostHome(ctx, venue)) {
                    noAnswer = true;
                    return Status.FAILED;
                }
                npc.setAnimation(VillagerAnimationState.IDLE);
                entry = Entry.INSIDE;
                resetMove();
                return null;
            }
            case TO_VIEW -> {
                // Only once inside: the door itself is handled by the route.
                if (viewHost == null) {
                    viewHost = nearestHost(ctx);
                    viewTile = viewHost != null ? viewTileFor(ctx, viewHost) : null;
                    if (viewTile == null) { entry = Entry.INSIDE; resetMove(); return null; }
                    resetMove();
                }
                if (!viewHost.isAlive() || viewHost.isSleeping()) { entry = Entry.INSIDE; resetMove(); return null; }
                if (!moveTo(ctx, viewTile, speed)) return Status.RUNNING;
                if (moveFailed(ctx, viewTile)) { entry = Entry.INSIDE; resetMove(); return null; }
                if (!ctx.brain().hostHome(ctx, venue)) {
                    hostMissing = true;
                    return Status.FAILED;
                }
                ctx.model().stopMoving();
                // The host looks up from where it is; it does not wave or get up.
                for (VillagerModel m : ctx.mgr().getVillagers()) {
                    if (m.puppet() == viewHost && m.brain().currentActivity() instanceof EveningActivity e) {
                        e.watch(npc, 60);
                    }
                }
                entry = Entry.WAVING;
                entryTimer = 30;
                faceWait = 0;
                return Status.RUNNING;
            }
            case WAVING -> {
                face(npc, viewHost);
                if (!wavedIn) {
                    if (facing(npc, viewHost) || ++faceWait > FACE_WAIT_TICKS) {
                        wavedIn = true;
                        npc.setAnimation(VillagerAnimationState.WAVE);
                    }
                    return Status.RUNNING;
                }
                if (--entryTimer > 0) return Status.RUNNING;
                npc.setAnimation(VillagerAnimationState.IDLE);
                entry = Entry.INSIDE;
                resetMove();
                return null;
            }
            default -> { return null; }
        }
    }

    /** Three raps on the door; softer with somebody asleep nearby. */
    private void knock(Context ctx) {
        boolean quiet = sleeperNear(ctx, doorBlock(), SLEEPER_QUIET_RADIUS);
        ctx.level().playSound(null, doorBlock(), SoundEvents.WOOD_HIT, SoundSource.NEUTRAL,
                quiet ? 0.35f : 0.9f, 0.9f + ctx.npc().getRandom().nextFloat() * 0.2f);
        ctx.npc().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
    }

    /** The house's resident nearest the door, free to come to it; asked to answer. */
    @Nullable
    private EveningActivity summonHost(Context ctx, boolean seeOut) {
        EveningActivity best = null;
        double bestDist = Double.MAX_VALUE;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive() || other.isSleeping()) continue;
            if (!(m.brain().currentActivity() instanceof EveningActivity e)
                    || e.mode != Mode.HOME || e.venue != venue || !e.begun) continue;
            if (e.greetGuest != null && e.greetGuest != ctx.npc()) continue;
            double d = other.blockPosition().distSqr(doorBlock());
            if (d < bestDist) { bestDist = d; best = e; }
        }
        if (best != null && best.requestGreeting(ctx.npc(), this, seeOut)) return best;
        return null;
    }

    /** Settled inside: a host waves from where it sits when there was no welcome at the door. */
    private void onGuestSettled(Context ctx) {
        hostMisses = 0;
        String guestId = ctx.npc().getUUID().toString().substring(0, 8);
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            if (m.puppet() != ctx.npc() && m.brain().currentActivity() instanceof EveningActivity e
                    && e.mode == Mode.HOME && e.venue == venue) {
                m.brain().diary().log(VillagerDiary.ts(ctx.level()) + " EVENING hosting " + guestId
                        + " (welcome=" + welcome + ")");
            }
        }
    }

    // --- Facing each other -------------------------------------------------------

    /** A wave waits this long at most for both to face each other. */
    private static final int FACE_WAIT_TICKS = 20;
    /** Facing within this many degrees counts as facing. */
    private static final float FACING_DEGREES = 30f;

    /** Yaw from {@code self} towards {@code target}, in Minecraft's convention. */
    private static float yawTo(Entity self, Entity target) {
        return (float) Math.toDegrees(Math.atan2(target.getZ() - self.getZ(), target.getX() - self.getX())) - 90f;
    }

    /**
     * Turns {@code self} to face {@code target}: the whole body when
     * standing, the head only when seated (the chair holds the body). Called
     * every tick of a wave or a greeting so the look does not drift.
     */
    static void face(NpcEntity self, Entity target) {
        if (!self.isChairSeated()) {
            float yaw = yawTo(self, target);
            self.setYRot(yaw);
            self.setYBodyRot(yaw);
            self.setYHeadRot(yaw);
        }
        self.getLookControl().setLookAt(target, 30f, 30f);
    }

    /** Whether {@code self}'s head points at {@code target} within {@link #FACING_DEGREES}. */
    static boolean facing(NpcEntity self, Entity target) {
        return Math.abs(Mth.wrapDegrees(yawTo(self, target) - self.getYHeadRot())) <= FACING_DEGREES;
    }

    // --- Wave from inside ----------------------------------------------------------

    @Nullable private NpcEntity viewHost;
    @Nullable private BlockPos viewTile;
    private int faceWait;

    /** The nearest awake resident relaxing in the house. */
    @Nullable
    private NpcEntity nearestHost(Context ctx) {
        NpcEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (NpcEntity host : hosts(ctx)) {
            double d = host.distanceToSqr(ctx.npc());
            if (d < bestDist) { bestDist = d; best = host; }
        }
        return best;
    }

    /**
     * A free tile 1.5-3 blocks from the host where it can see the guest: for
     * a seated host, within its head's 75-degree turn from the chair's
     * facing. Nearest to the guest.
     */
    @Nullable
    private BlockPos viewTileFor(Context ctx, NpcEntity host) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos t : venue.getNavMeshBlocks()) {
            if (Math.abs(t.getY() - host.getBlockY()) > 1) continue;
            double dx = t.getX() + 0.5 - host.getX(), dz = t.getZ() + 0.5 - host.getZ();
            double flat = dx * dx + dz * dz;
            if (flat < 2.25 || flat > 9.0) continue;
            if (host.isChairSeated()) {
                float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90f;
                if (Math.abs(Mth.wrapDegrees(yaw - host.yBodyRot)) > 75f) continue;
            }
            if (taken(ctx, t)) continue;
            double d = t.distSqr(ctx.npc().blockPosition());
            if (d < bestDist) { bestDist = d; best = t; }
        }
        return best;
    }

    /** A host watches a guest come in: head only, no wave, whether sitting or standing. */
    void watch(NpcEntity guest, int ticks) {
        waveAt = guest;
        waveTimer = ticks;
        watching = true;
    }
    private boolean watching;

    /** The visit is over: head for the door, the host coming along to see the guest out. */
    private boolean startLeaving(Context ctx) {
        BlockPos outside = outsideTile(ctx);
        if (outside == null) return false;
        greeter = summonHost(ctx, true);
        if (greeter == null) return false;
        leaving = true;
        ctx.npc().standUpFromChair();
        releaseChair(ctx);
        arrived = false;
        standOn(ctx, outside);
        resetMove();
        return true;
    }

    private Status tickLeaving(Context ctx) {
        if (ctx.brain().eveningPhase(ctx) == EveningSchedule.Phase.DAY) return Status.DONE;
        NpcEntity host = greeter != null ? greeter.hostNpc : null;
        if (leaveWave > 0) {
            if (host != null) face(ctx.npc(), host);
            // The goodbye wave starts once both face each other.
            if (!wavedOut) {
                // Give the host time to step in front of the door first.
                if (host == null || facing(ctx.npc(), host) && facing(host, ctx.npc()) || ++faceWait > 3 * FACE_WAIT_TICKS) {
                    wavedOut = true;
                    ctx.npc().setAnimation(VillagerAnimationState.WAVE);
                }
                return Status.RUNNING;
            }
            if (--leaveWave == 0) {
                ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                return Status.DONE;
            }
            return Status.RUNNING;
        }
        if (!moveTo(ctx, target, speed)) return Status.RUNNING;
        // Outside, or as near as it got: turn round for a wave goodbye.
        ctx.model().stopMoving();
        if (!settleOnto(ctx, target)) return Status.RUNNING;
        leaveWave = 30;
        faceWait = 0;
        return Status.RUNNING;
    }

    // --- Host side -------------------------------------------------------------

    /** The host's own entity, set while it answers the door. */
    @Nullable private NpcEntity hostNpc;

    /** Asked by a guest to answer the door (or see it out); false if busy. */
    boolean requestGreeting(NpcEntity guest, @Nullable EveningActivity guestActivity, boolean seeOut) {
        if (mode != Mode.HOME || !begun) return false;
        if (greetGuest != null && greetGuest != guest) return false;
        if (greetGuest == guest) return true;
        greetGuest = guest;
        greetSeeOut = seeOut;
        greetGuestActivity = guestActivity;
        greetState = 0;
        greetTimer = GREET_TIMEOUT_TICKS;
        openedDoor = false;
        return true;
    }

    boolean isGreeting(NpcEntity guest) { return greetGuest == guest; }

    /** The door is open for this guest and the host is at it. */
    boolean doorOpenedFor(NpcEntity guest) {
        return greetGuest == guest && !greetSeeOut && greetState >= GREET_OPEN;
    }

    /** The host has stepped out of the doorway: the guest can come in. */
    boolean doorwayClearFor(NpcEntity guest) {
        return greetGuest != guest || greetState >= GREET_ASIDE;
    }

    private boolean hostWaved;
    private int greetFaceWait;
    private int greetWaveTicks;

    /** Host door steps. */
    private static final int GREET_WALK = 0, GREET_WAIT = 1, GREET_OPEN = 2, GREET_ASIDE = 3,
            GREET_OUT_WAIT = 4, GREET_OUT_FRONT = 5;
    /** How long a wave lasts before the host steps aside. */
    private static final int WAVE_TICKS = 30;

    /**
     * Answering the door. Letting a guest in: stand right in front of the
     * door on the inside (not in the doorway), open it when the guest is
     * there, face each other and wave, then step aside so the guest can come
     * in. Seeing a guest out: wait beside the doorway while the guest walks
     * out, then step in front of the door and wave it off.
     */
    private Status tickGreeting(Context ctx) {
        hostNpc = ctx.npc();
        NpcEntity guest = greetGuest;
        if (guest == null || !guest.isAlive() || --greetTimer <= 0) {
            endGreeting(ctx);
            return Status.RUNNING;
        }
        switch (greetState) {
            case GREET_WALK -> {
                BlockPos spot = greetSeeOut ? greetTile(ctx) : insideTile(ctx);
                if (spot == null) { endGreeting(ctx); return Status.RUNNING; }
                if (arrived || ctx.npc().isChairSeated()) {
                    ctx.npc().standUpFromChair();
                    releaseChair(ctx);
                    arrived = false;
                    resetMove();
                }
                claimStand(ctx, spot);
                if (!moveTo(ctx, spot, speed)) return Status.RUNNING;
                if (moveFailed(ctx, spot)) { endGreeting(ctx); return Status.RUNNING; }
                ctx.model().stopMoving();
                // The walk stops up to a block and a half short: step onto the spot.
                if (!settleOnto(ctx, spot)) return Status.RUNNING;
                greetState = GREET_WAIT;
            }
            case GREET_WAIT -> {
                // Behind the closed door: looking at the door, not through it.
                lookAt(ctx, doorBlock());
                if (guest.blockPosition().distSqr(doorBlock()) <= DOOR_NEAR_SQ) {
                    openedDoor = setDoor(ctx, doorBlock(), true);
                    greetFaceWait = 0;
                    hostWaved = false;
                    greetWaveTicks = 0;
                    greetState = greetSeeOut ? GREET_OUT_WAIT : GREET_OPEN;
                    greetTimer = Math.min(greetTimer, 200);
                }
            }
            case GREET_OPEN -> {
                // Door open: face the guest, wave once both face each other,
                // then make way.
                face(ctx.npc(), guest);
                if (!hostWaved && (facing(ctx.npc(), guest) && facing(guest, ctx.npc())
                        || ++greetFaceWait > FACE_WAIT_TICKS)) {
                    hostWaved = true;
                    ctx.npc().setAnimation(VillagerAnimationState.WAVE);
                }
                if (hostWaved && ++greetWaveTicks >= WAVE_TICKS) {
                    ctx.npc().setAnimation(VillagerAnimationState.IDLE);
                    greetState = GREET_ASIDE;
                    resetMove();
                }
            }
            case GREET_ASIDE -> {
                BlockPos aside = greetTile(ctx);
                if (aside != null && !aside.equals(insideTile(ctx))) {
                    claimStand(ctx, aside);
                    if (moveTo(ctx, aside, speed)) settleOnto(ctx, aside);
                }
                ctx.npc().getLookControl().setLookAt(guest, 30f, 30f);
                double fromDoor = guest.blockPosition().distSqr(doorBlock());
                if (venue.getNavMeshBlocks().contains(guest.blockPosition()) && fromDoor > 4.0) endGreeting(ctx);
            }
            case GREET_OUT_WAIT -> {
                // The guest walks out past; once it is outside, step into the doorway front.
                ctx.npc().getLookControl().setLookAt(guest, 30f, 30f);
                boolean out = !venue.getNavMeshBlocks().contains(guest.blockPosition())
                        && guest.blockPosition().distSqr(doorBlock()) >= 1.0;
                if (out) {
                    greetState = GREET_OUT_FRONT;
                    resetMove();
                }
            }
            default -> {
                BlockPos front = insideTile(ctx);
                if (front != null && !hostWaved) {
                    claimStand(ctx, front);
                    if (!moveTo(ctx, front, speed)) return Status.RUNNING;
                    if (!settleOnto(ctx, front)) return Status.RUNNING;
                }
                face(ctx.npc(), guest);
                if (!hostWaved && (facing(ctx.npc(), guest) && facing(guest, ctx.npc())
                        || ++greetFaceWait > FACE_WAIT_TICKS)) {
                    hostWaved = true;
                    ctx.npc().setAnimation(VillagerAnimationState.WAVE);
                }
                // Done once the guest has waved and gone on.
                if (hostWaved && ++greetWaveTicks >= WAVE_TICKS
                        && guest.blockPosition().distSqr(doorBlock()) > 9.0) endGreeting(ctx);
            }
        }
        return Status.RUNNING;
    }

    private int settleTicks;
    private static final int SETTLE_MAX_TICKS = 40;

    /**
     * The walk counts as done up to a block and a half from the goal; this
     * steps the rest of the way onto the tile's middle. True once there (or
     * after two seconds of trying).
     */
    private boolean settleOnto(Context ctx, BlockPos tile) {
        NpcEntity npc = ctx.npc();
        double dx = tile.getX() + 0.5 - npc.getX(), dz = tile.getZ() + 0.5 - npc.getZ();
        if (dx * dx + dz * dz <= 0.09 || ++settleTicks > SETTLE_MAX_TICKS) {
            settleTicks = 0;
            npc.getNavigation().stop();
            return true;
        }
        npc.getNavigation().stop();
        npc.getMoveControl().setWantedPosition(tile.getX() + 0.5, npc.getY(), tile.getZ() + 0.5, 0.45);
        return false;
    }

    /** Done at the door: close it if this host opened it and nobody is in it, then back to a seat. */
    private void endGreeting(Context ctx) {
        if (greetGuest == null) return;
        greetGuest = null;
        if (openedDoor) {
            BlockPos door = doorBlock();
            boolean blocked = !ctx.level().getEntitiesOfClass(NpcEntity.class,
                    new net.minecraft.world.phys.AABB(door).inflate(0.1)).isEmpty();
            if (!blocked) setDoor(ctx, door, false);
            openedDoor = false;
        }
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        // After letting a guest in, sit down near where it is going to sit.
        if (!greetSeeOut && greetGuestActivity != null) sitNear = greetGuestActivity.plannedSeat();
        greetGuestActivity = null;
        if (mode == Mode.HOME) {
            arrived = false;
            pickTarget(ctx);
            resetMove();
        }
    }

    @Nullable private EveningActivity greetGuestActivity;
    /** Where the host wants to sit next: near a guest it has just let in. */
    @Nullable private BlockPos sitNear;

    /** The chair (or tile) this villager is heading for or sitting on. */
    @Nullable
    BlockPos plannedSeat() {
        return chair != null ? chair.pos() : target;
    }

    /** A free chair within talking range of {@code pos}, facing it if possible. */
    @Nullable
    private VillageChairs.Chair chairNear(Context ctx, BlockPos pos) {
        VillageChairs.Chair best = null;
        boolean bestFacing = false;
        for (VillageChairs.Chair c : chairs) {
            if (c.pos().equals(pos) || !VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            double dx = pos.getX() - c.pos().getX(), dz = pos.getZ() - c.pos().getZ();
            double flat = dx * dx + dz * dz;
            if (flat < TALK_MIN_SQ || flat > TABLE_MAX_SQ || Math.abs(pos.getY() - c.pos().getY()) > 1) continue;
            boolean facing = (dx * c.front().getStepX() + dz * c.front().getStepZ()) / Math.sqrt(flat) >= FACING_MIN_DOT;
            if (flat > TALK_MAX_SQ && !facing) continue;
            if (best == null || (facing && !bestFacing)) {
                best = c;
                bestFacing = facing;
            }
        }
        return best;
    }

    /** A free tile beside the nearest host, within talking range of it. */
    @Nullable
    private BlockPos tileBesideHost(Context ctx) {
        NpcEntity host = nearestHost(ctx);
        if (host == null) return null;
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos t : venue.getNavMeshBlocks()) {
            if (Math.abs(t.getY() - host.getBlockY()) > 1) continue;
            double dx = t.getX() + 0.5 - host.getX(), dz = t.getZ() + 0.5 - host.getZ();
            double flat = dx * dx + dz * dz;
            if (flat < 1.0 || flat > 5.0 || taken(ctx, t)) continue;
            double d = t.distSqr(ctx.npc().blockPosition());
            if (d < bestDist) { bestDist = d; best = t; }
        }
        return best;
    }

    // --- Shared snack ----------------------------------------------------------

    /**
     * Now and then a host with food shares one item with its guest: the host
     * hands it over and both have a bite in their seats. Only a quarter of
     * visits, and only when the host carries food.
     */
    private void maybeShareSnack(Context ctx) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity host = m.puppet();
            if (host == null || host == ctx.npc() || host.isSleeping()) continue;
            if (!(m.brain().currentActivity() instanceof EveningActivity e)
                    || e.mode != Mode.HOME || e.venue != venue || !e.arrived) continue;
            if (host.distanceToSqr(ctx.npc()) > 16.0) continue;
            Context hostCtx = new Context(m.brain(), m, ctx.mgr(), ctx.level(), host);
            int slot = EatActivity.findFoodSlot(hostCtx);
            if (!VisitRules.snack(ctx.brain().visitSnackRoll(), slot >= 0)) return;
            ItemStack food = host.getInventory().getSlot(slot).copyWithCount(1);
            if (!host.getInventory().reduceSlot(slot, 1)) return;
            ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " EVENING snack shared by the host");
            e.startSnack(host, food);
            startSnack(ctx.npc(), food);
            ctx.npc().showBubble(com.alex.test.village.social.ThoughtIcon.TOPIC_FOOD, true, 50);
            return;
        }
    }

    /** The shared snack is eaten with the whole eat clip, like a serving of a meal (EatActivity.EAT_TICKS). */
    private static final int SNACK_TICKS = 141;
    private int snackTimer;
    /** What is being eaten, shown in the eating hand from the offhand as EatActivity does. */
    private ItemStack snack = ItemStack.EMPTY;

    /** One serving of the shared food, seated where it is. */
    void startSnack(NpcEntity self, ItemStack food) {
        snack = food.copyWithCount(1);
        self.setItemSlot(EquipmentSlot.OFFHAND, snack.copy());
        snackTimer = SNACK_TICKS;
        self.startEatAnimation();
    }

    private void tickSnack(Context ctx) {
        // Something else took the pose over (a wave, a fright): the snack is left uneaten.
        if (!ctx.npc().isEating()) {
            endSnack(ctx);
            return;
        }
        ctx.npc().holdStillWhileEating();
        if (NpcEntity.isEatBiteTick(SNACK_TICKS - --snackTimer)) {
            boolean quiet = sleeperNear(ctx, ctx.npc().blockPosition(), SLEEPER_QUIET_RADIUS);
            ctx.npc().eatBite(snack, quiet ? 0.2f : 0.5f);
        }
        if (snackTimer == 0) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            ctx.brain().needs().hunger = Math.min(100f, ctx.brain().needs().hunger + 5f);
            endSnack(ctx);
        }
    }

    private void endSnack(Context ctx) {
        snackTimer = 0;
        snack = ItemStack.EMPTY;
        ctx.npc().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }

    // --- Sitting within talking range of the host -----------------------------

    /** Pairing range (see {@link ConverseActivity#wellPlaced}), flat and squared. */
    private static final double TALK_MIN_SQ = 0.81, TALK_MAX_SQ = 6.25;
    /** Two seated villagers facing each other across a table talk from up to 3.6 blocks. */
    private static final double TABLE_MAX_SQ = 12.96;
    /** A seated head turns about 75 degrees; a chair facing the host within that is preferred. */
    private static final double FACING_MIN_DOT = Math.cos(Math.toRadians(75));

    /** The awake residents relaxing in this house right now. */
    private List<NpcEntity> hosts(Context ctx) {
        List<NpcEntity> out = new ArrayList<>();
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive() || other.isSleeping()) continue;
            if (m.brain().currentActivity() instanceof EveningActivity e
                    && e.mode == Mode.HOME && e.venue == venue) out.add(other);
        }
        return out;
    }

    /**
     * A free chair within talking range of a host, facing it if possible, so
     * guest and host can actually chat; null if there is none.
     */
    @Nullable
    private VillageChairs.Chair chairNearHost(Context ctx) {
        List<NpcEntity> hosts = hosts(ctx);
        if (hosts.isEmpty()) return null;
        VillageChairs.Chair best = null;
        boolean bestFacing = false;
        for (VillageChairs.Chair c : chairs) {
            if (!VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())
                    && !c.pos().equals(ctx.npc().seatedChairPos())) continue;
            for (NpcEntity host : hosts) {
                double dx = host.getX() - (c.pos().getX() + 0.5), dz = host.getZ() - (c.pos().getZ() + 0.5);
                double flat = dx * dx + dz * dz;
                // A seated host can be talked to from across a table too.
                boolean table = host.isChairSeated() && flat > TALK_MAX_SQ && flat <= TABLE_MAX_SQ;
                if (flat < TALK_MIN_SQ || (flat > TALK_MAX_SQ && !table)
                        || Math.abs(host.getY() - c.pos().getY()) > 1.0) continue;
                double len = Math.sqrt(flat);
                boolean facing = (dx * c.front().getStepX() + dz * c.front().getStepZ()) / len >= FACING_MIN_DOT;
                if (table) {
                    // Across a table both must face each other: the chair
                    // towards the host, and the host's chair towards it.
                    float yawToChair = (float) Math.toDegrees(Math.atan2(-dz, -dx)) - 90f;
                    if (!facing || Math.abs(Mth.wrapDegrees(yawToChair - host.yBodyRot)) > 75f) continue;
                }
                if (best == null || (facing && !bestFacing)) {
                    best = c;
                    bestFacing = facing;
                }
            }
        }
        return best;
    }

    /** Whether {@code pos} is within talking range of any host. */
    private boolean nearHost(Context ctx, BlockPos pos) {
        for (NpcEntity host : hosts(ctx)) {
            double dx = host.getX() - (pos.getX() + 0.5), dz = host.getZ() - (pos.getZ() + 0.5);
            double flat = dx * dx + dz * dz;
            if (flat >= TALK_MIN_SQ && flat <= TALK_MAX_SQ) return true;
        }
        return false;
    }

    // --- The visit chat ------------------------------------------------------------

    /** Settled ticks at which the next "no chat" line may be written. */
    private int nextNoChatLog;
    private static final int NO_CHAT_LOG_TICKS = 200;

    /**
     * The first chat of a visit starts as soon as guest and host are placed
     * for it, whatever either one's conversation cooldown, without waiting
     * for the village-wide pairing scan. Later chats go through the scan.
     * While there is no chat, the reason is written down every ten seconds.
     */
    private void visitChatCheck(Context ctx) {
        NpcEntity guest = ctx.npc();
        VillagerModel hostModel = null;
        NpcEntity host = null;
        double nearest = Double.MAX_VALUE;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == guest || !other.isAlive() || other.isSleeping()) continue;
            if (!(m.brain().currentActivity() instanceof EveningActivity e)
                    || e.mode != Mode.HOME || e.venue != venue) continue;
            double d = other.distanceToSqr(guest);
            if (d < nearest) { nearest = d; host = other; hostModel = m; }
        }
        EveningActivity hostActivity = hostModel != null
                ? (EveningActivity) hostModel.brain().currentActivity() : null;
        boolean hostFree = hostActivity != null && hostActivity.arrived && hostActivity.greetGuest == null;
        if (hostFree && ctx.brain().visitChats() == 0 && ConverseActivity.wellPlaced(guest, host)) {
            SocialCoordinator.startVisitChat(ctx.model(), this, hostModel, ctx.level());
            return;
        }
        if (settledTicks < nextNoChatLog) return;
        nextNoChatLog = settledTicks + NO_CHAT_LOG_TICKS;
        String line = host == null
                ? "no host free (chatting, eating or away)"
                : String.format("host %.1f blocks / host cooldown %d / guest cooldown %d / host %s",
                        Math.sqrt(nearest), hostModel.brain().conversationCooldownLeft(),
                        ctx.brain().conversationCooldownLeft(),
                        hostFree ? "free" : hostActivity.greetGuest != null ? "at the door" : "moving");
        ctx.brain().diary().log(VillagerDiary.ts(ctx.level()) + " VISIT no chat: " + line);
    }

    // --- Helpers ---------------------------------------------------------------

    private static void lookAt(Context ctx, BlockPos pos) {
        ctx.npc().getLookControl().setLookAt(pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, 30f, 30f);
    }

    /** A resident of this house is in bed. */
    private boolean residentAsleep(Context ctx) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || !other.isSleeping()) continue;
            Building theirs = ctx.mgr().getAssignedBedBuilding(m.npcId);
            if (theirs != null && theirs.getItemFrameId().equals(venue.getItemFrameId())) return true;
        }
        return false;
    }

    /** Somebody is asleep within {@code radius} blocks of {@code pos}. */
    public static boolean sleeperNear(Context ctx, BlockPos pos, double radius) {
        double r2 = radius * radius;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other != null && other.isAlive() && other.isSleeping()
                    && other.blockPosition().distSqr(pos) <= r2) return true;
        }
        return false;
    }

    /** Until {@link #start} has run a fresh activity keeps the seat, so it can adopt it. */
    @Override
    public boolean keepsChairSeat() { return !begun || chair != null; }

    @Override
    public void stop(Context ctx) {
        if (snackTimer > 0) {
            if (ctx.npc().isEating()) ctx.npc().setAnimation(VillagerAnimationState.IDLE);
            endSnack(ctx);
        }
        ctx.model().stopMoving();
        releaseChair(ctx);
        releaseStand(ctx);
        // Interrupted at the door: do not leave it standing open.
        if (openedDoor) {
            setDoor(ctx, doorBlock(), false);
            openedDoor = false;
        }
        greetGuest = null;
    }

    @Override
    public String name() {
        return switch (mode) {
            case HOME   -> "evening_home";
            case TAVERN -> "evening_tavern";
            case VISIT  -> "evening_visit";
        };
    }

    // =========================================================================
    // Choosing where to be
    // =========================================================================

    /** The first place in the venue: a free chair, else standing where that is allowed. */
    private void pickTarget(Context ctx) {
        releaseChair(ctx);
        releaseStand(ctx);
        standing = false;
        picks++;
        if (mode == Mode.VISIT) {
            VillageChairs.Chair near = chairNearHost(ctx);
            if (near != null) {
                takeChair(ctx, near);
                return;
            }
            // No chair within talking range: stand beside the host rather
            // than sit across the room where the two could never chat.
            BlockPos beside = tileBesideHost(ctx);
            if (beside != null) {
                standOn(ctx, beside);
                return;
            }
        }
        // A host just back from the door sits down near its guest.
        if (mode == Mode.HOME && sitNear != null) {
            VillageChairs.Chair near = chairNear(ctx, sitNear);
            sitNear = null;
            if (near != null) {
                takeChair(ctx, near);
                return;
            }
        }
        boolean sit = mode != Mode.HOME || ctx.npc().getRandom().nextFloat() < HOME_SIT_CHANCE;
        VillageChairs.Chair next = sit ? pickChair(ctx, chairs) : null;
        if (next != null) {
            takeChair(ctx, next);
            return;
        }
        if (mode == Mode.TAVERN) {
            if (standingRoomLeft(ctx)) {
                standing = true;
                standOn(ctx, standingTile(ctx));
                return;
            }
            VillageChairs.Chair outside = pickChair(ctx, outdoorChairs);
            if (outside != null) {
                takeChair(ctx, outside);
                return;
            }
            full = true;
            target = null;
            return;
        }
        standOn(ctx, standingTile(ctx));
    }

    /**
     * Every so often: a seated villager may move to another free chair (more
     * often towards company), a standing one takes a chair that has come
     * free, or else shifts to stand beside someone else.
     */
    private void drift(Context ctx) {
        driftIn = driftDelay(ctx);
        if (chair != null) {
            // A guest sat out of talking range of its host moves closer when it can.
            if (mode == Mode.VISIT && !nearHost(ctx, chair.pos())) {
                VillageChairs.Chair near = chairNearHost(ctx);
                if (near != null && !near.pos().equals(chair.pos())) {
                    ctx.npc().standUpFromChair();
                    releaseChair(ctx);
                    takeChair(ctx, near);
                    arrived = false;
                    resetMove();
                    return;
                }
            }
            float moveChance = mode == Mode.HOME ? 0.3f : 0.45f;
            if (ctx.npc().getRandom().nextFloat() >= moveChance) return;
            // A guest sitting outside moves in when a chair inside frees up.
            boolean outside = outdoorChairs.contains(chair);
            VillageChairs.Chair next = pickChair(ctx, chairs);
            if (next == null && outside) next = pickChair(ctx, outdoorChairs);
            if (next == null || next.pos().equals(chair.pos())) return;
            ctx.npc().standUpFromChair();
            releaseChair(ctx);
            takeChair(ctx, next);
            standing = false;
            arrived = false;
            resetMove();
            return;
        }
        // On its feet: a chair that has come free first.
        VillageChairs.Chair free = pickChair(ctx, chairs);
        if (free != null) {
            takeChair(ctx, free);
            standing = false;
            arrived = false;
            resetMove();
            return;
        }
        if (ctx.npc().getRandom().nextBoolean()) return;
        BlockPos next = standingTile(ctx);
        if (next != null && !next.equals(target)) {
            standOn(ctx, next);
            arrived = false;
            resetMove();
        }
    }

    private void takeChair(Context ctx, VillageChairs.Chair next) {
        chair = next;
        VillageChairs.claim(ctx.level(), next.pos(), ctx.npc().getUUID());
        target = next.approach();
        releaseStand(ctx);
    }

    /** Whether the tavern still has standing room tonight for one more guest. */
    /** Standing guests the tavern allows tonight: 0-3, rolled per tavern per night. */
    static int standingAllowance(UUID tavernId, long day) {
        return EveningSchedule.sharedRoll(tavernId, day, SALT_STANDERS, MAX_TAVERN_STANDERS + 1);
    }

    /**
     * How many guests the tavern holds at once tonight: its own chairs,
     * tonight's standing room and the chairs outside within
     * {@value #TAVERN_OUTDOOR_CHAIRS} blocks of the door. One chair scan,
     * done once a night by the guest list.
     */
    static int tavernCapacity(Context ctx, Building tavern, long day) {
        return SocializeActivity.venueChairs(ctx, tavern, TAVERN_OUTDOOR_CHAIRS).size()
                + standingAllowance(tavern.getItemFrameId(), day);
    }

    private boolean standingRoomLeft(Context ctx) {
        long day = EveningSchedule.dayIndex(ctx.level().getDayTime());
        int allowed = standingAllowance(venue.getItemFrameId(), day);
        if (allowed <= 0) return false;
        int standers = 0;
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            if (m.puppet() == ctx.npc()) continue;
            if (m.brain().currentActivity() instanceof EveningActivity e
                    && e.venue == venue && e.isTavernStander()) standers++;
        }
        return standers < allowed;
    }

    /** A tile to stand on: beside somebody when possible, else near the usual corner. */
    private BlockPos standingTile(Context ctx) {
        BlockPos mingle = style != Style.STAND || ctx.npc().getRandom().nextBoolean()
                ? tileBesideSomeone(ctx) : null;
        return mingle != null ? mingle : pickTile(ctx);
    }

    /**
     * A free chair from {@code from}: near the bard for a listener, beside
     * somebody for a sociable villager, else nearest the usual corner on most
     * nights.
     */
    @Nullable
    private VillageChairs.Chair pickChair(Context ctx, List<VillageChairs.Chair> from) {
        if (from.isEmpty()) return null;
        BlockPos mine = ctx.npc().seatedChairPos();
        List<VillageChairs.Chair> free = new ArrayList<>();
        List<VillageChairs.Chair> social = new ArrayList<>();
        for (VillageChairs.Chair c : from) {
            if (!c.pos().equals(mine)
                    && !VillageChairs.isFreeFor(ctx.level(), c.pos(), ctx.npc().getUUID())) continue;
            free.add(c);
            if (style != Style.STAND && hasCompanyNear(ctx, c.pos(), 6.25)) social.add(c);
        }
        if (free.isEmpty()) return null;
        if (listensToBard) {
            BlockPos bard = bardPos(ctx);
            if (bard != null) {
                List<VillageChairs.Chair> near = new ArrayList<>();
                for (VillageChairs.Chair c : free) {
                    if (c.pos().distManhattan(bard) <= BARD_LISTEN_RADIUS) near.add(c);
                }
                if (!near.isEmpty()) return near.get(ctx.npc().getRandom().nextInt(near.size()));
            }
        }
        List<VillageChairs.Chair> pool = social.isEmpty() ? free : social;
        if (favouriteNight && favourite != null && ctx.npc().getRandom().nextFloat() < 0.7f) {
            return pool.stream().min(Comparator.comparingDouble(c -> c.pos().distSqr(favourite))).orElse(null);
        }
        return pool.get(ctx.npc().getRandom().nextInt(pool.size()));
    }

    @Nullable
    private static VillageChairs.Chair chairAt(List<VillageChairs.Chair> list, BlockPos pos) {
        for (VillageChairs.Chair c : list) if (c.pos().equals(pos)) return c;
        return null;
    }

    /** A free floor tile, drawn near the usual corner on most nights. */
    @Nullable
    private BlockPos pickTile(Context ctx) {
        List<BlockPos> tiles = new ArrayList<>(venue.getNavMeshBlocks());
        if (tiles.isEmpty()) return null;
        if (favouriteNight && favourite != null && ctx.npc().getRandom().nextFloat() < 0.6f) {
            tiles.sort(Comparator.comparingDouble(t -> t.distSqr(favourite)));
            int limit = Math.min(tiles.size(), 6);
            for (int i = 0; i < limit; i++) {
                BlockPos t = tiles.get(ctx.npc().getRandom().nextInt(limit));
                if (!taken(ctx, t)) return t;
            }
        }
        BlockPos fallback = null;
        for (int attempt = 0; attempt < SPOT_TRIES; attempt++) {
            BlockPos t = tiles.get(ctx.npc().getRandom().nextInt(tiles.size()));
            if (fallback == null) fallback = t;
            if (!taken(ctx, t)) return t;
        }
        return fallback;
    }

    /** A free venue tile a conversation's distance from somebody already settled here. */
    @Nullable
    private BlockPos tileBesideSomeone(Context ctx) {
        List<NpcEntity> company = new ArrayList<>();
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (!(m.brain().currentActivity() instanceof EveningActivity e)
                    || e.venue != venue || !e.arrived) continue;
            company.add(other);
        }
        if (company.isEmpty()) return null;
        NpcEntity pick = company.get(ctx.npc().getRandom().nextInt(company.size()));
        List<BlockPos> near = new ArrayList<>();
        for (BlockPos t : venue.getNavMeshBlocks()) {
            if (Math.abs(t.getY() - pick.getBlockY()) > 1) continue;
            double dx = t.getX() + 0.5 - pick.getX(), dz = t.getZ() + 0.5 - pick.getZ();
            double flat = dx * dx + dz * dz;
            if (flat >= MIN_MINGLE_SQ && flat <= MAX_MINGLE_SQ && !taken(ctx, t)) near.add(t);
        }
        return near.isEmpty() ? null : near.get(ctx.npc().getRandom().nextInt(near.size()));
    }

    /** Where a bard playing its evening set stands, if one is. */
    @Nullable
    private static BlockPos bardPos(Context ctx) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity bard = m.puppet();
            if (bard != null && bard.isAlive()
                    && m.brain().currentActivity() instanceof BardActivity set && set.isEveningSet()) {
                return bard.blockPosition();
            }
        }
        return null;
    }

    /**
     * This villager's usual part of the venue: a tile picked from its id, the
     * same on every evening. Tiles are sorted first so the pick does not
     * depend on hash-set order.
     */
    @Nullable
    private BlockPos favouriteSpot(Context ctx) {
        List<BlockPos> tiles = new ArrayList<>(venue.getNavMeshBlocks());
        if (tiles.isEmpty()) return null;
        tiles.sort(Comparator.comparingInt((BlockPos p) -> p.getX())
                .thenComparingInt(p -> p.getZ()).thenComparingInt(p -> p.getY()));
        long salt = venue.getItemFrameId().getLeastSignificantBits();
        return tiles.get(ctx.brain().eveningSchedule().lastingRoll(salt, tiles.size()));
    }

    private boolean hasCompanyNear(Context ctx, BlockPos pos, double flatSq) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (Math.abs(other.getY() - pos.getY()) > 1.5) continue;
            double dx = other.getX() - (pos.getX() + 0.5), dz = other.getZ() - (pos.getZ() + 0.5);
            if (dx * dx + dz * dz <= flatSq) return true;
        }
        return false;
    }

    /** The standing spot this villager holds, if it is not on a chair. */
    @Nullable private BlockPos standSpot;

    /** Stand on {@code tile}: it becomes the target and this villager's claimed spot. */
    private void standOn(Context ctx, @Nullable BlockPos tile) {
        target = tile;
        claimStand(ctx, tile);
    }

    /** Claim a spot to stand on without changing the target (the door tiles). */
    private void claimStand(Context ctx, @Nullable BlockPos tile) {
        releaseStand(ctx);
        if (tile == null) return;
        standSpot = tile.immutable();
        StandClaims.claim(ctx.level(), standSpot, ctx.npc().getUUID());
    }

    private void releaseStand(Context ctx) {
        if (standSpot == null) return;
        StandClaims.release(ctx.level(), standSpot, ctx.npc().getUUID());
        standSpot = null;
    }

    /** Somebody stands there, or has claimed it on the way. */
    private static boolean taken(Context ctx, BlockPos tile) {
        return occupied(ctx, tile) || !StandClaims.isFreeFor(ctx.level(), tile, ctx.npc().getUUID());
    }

    private static boolean occupied(Context ctx, BlockPos tile) {
        for (VillagerModel m : ctx.mgr().getVillagers()) {
            NpcEntity other = m.puppet();
            if (other == null || other == ctx.npc() || !other.isAlive()) continue;
            if (other.blockPosition().equals(tile)) return true;
        }
        return false;
    }

    /** Seated villagers stay a good while (1,000-2,000 ticks at home); standing ones move sooner. */
    private int driftDelay(Context ctx) {
        boolean seated = chair != null || ctx.npc().isChairSeated();
        if (seated) {
            return mode == Mode.HOME
                    ? 1_000 + ctx.npc().getRandom().nextInt(1_001)
                    : 600 + ctx.npc().getRandom().nextInt(601);
        }
        return (mode == Mode.HOME ? 300 : 200) + ctx.npc().getRandom().nextInt(300);
    }

    private boolean sitDown(Context ctx) {
        if (ctx.npc().distanceToSqr(chair.pos().getCenter()) > SIT_REACH_SQ) return false;
        if (!chair.pos().equals(ctx.npc().seatedChairPos())
                && !VillageChairs.isFreeFor(ctx.level(), chair.pos(), ctx.npc().getUUID())) return false;
        return ctx.npc().sitOnChair(chair.pos());
    }

    private void releaseChair(Context ctx) {
        if (chair == null) return;
        VillageChairs.release(ctx.level(), chair.pos(), ctx.npc().getUUID());
        chair = null;
    }

    // =========================================================================
    // House chair counts, for the brain's guest limit
    // =========================================================================

    private record HouseChairs(long at, List<VillageChairs.Chair> chairs) {}
    private static final Map<UUID, HouseChairs> HOUSE_CHAIRS = new HashMap<>();
    /** Chairs this far (flat) from a host, on its floor, are its guests' seats. */
    private static final double GUEST_SEAT_RADIUS_SQ = 36.0;
    private static final UUID NOBODY = new UUID(0L, 0L);

    /** The chairs in {@code venue}; scanned at most once a minute per building. */
    private static List<VillageChairs.Chair> houseChairs(Context ctx, Building venue) {
        long now = ctx.level().getGameTime();
        HouseChairs cached = HOUSE_CHAIRS.get(venue.getItemFrameId());
        if (cached != null && now >= cached.at() && now - cached.at() < CHAIR_COUNT_TICKS) return cached.chairs();
        List<VillageChairs.Chair> chairs = SocializeActivity.venueChairs(ctx, venue, 0);
        HOUSE_CHAIRS.put(venue.getItemFrameId(), new HouseChairs(now, chairs));
        return chairs;
    }

    /** Chairs in {@code venue}; scanned at most once a minute per building. */
    static int chairCount(Context ctx, Building venue) {
        return houseChairs(ctx, venue).size();
    }

    /**
     * A house's guest seats: its chairs within 6 blocks of an awake host
     * and on the host's floor (not the bedroom upstairs), as {total, free}.
     * Free means nobody sits there or is on the way to it.
     */
    static int[] guestSeats(Context ctx, Building house, List<NpcEntity> hosts) {
        int total = 0, free = 0;
        for (VillageChairs.Chair c : houseChairs(ctx, house)) {
            boolean near = false;
            for (NpcEntity host : hosts) {
                double dx = host.getX() - (c.pos().getX() + 0.5), dz = host.getZ() - (c.pos().getZ() + 0.5);
                if (dx * dx + dz * dz <= GUEST_SEAT_RADIUS_SQ && Math.abs(host.getBlockY() - c.pos().getY()) <= 1) {
                    near = true;
                    break;
                }
            }
            if (!near) continue;
            total++;
            if (VillageChairs.isFreeFor(ctx.level(), c.pos(), NOBODY)) free++;
        }
        return new int[] {total, free};
    }
}
