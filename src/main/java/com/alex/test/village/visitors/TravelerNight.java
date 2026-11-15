package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.VanillaNavCount;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.TavernBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A visitor's evening and night at its village's camp ({@link TravelerCamp}).
 *
 * <p>From dusk it walks to its tile beside the fire. Until bedtime (about
 * 8:30 PM) it passes the evening there: it stands warming itself, makes the
 * odd gesture, strolls a step or two around the fire and back, or sits down
 * for a while. At bedtime it goes back to its own tile, sits and sleeps, the
 * way villagers sleep on a Town Hall floor. In the morning, or as soon as it
 * sets off, it gets up.
 *
 * <p>Only visitors staying past the coming sunrise camp; the rest keep to
 * their stall or gathering spot until they leave. Transient: after a reload a
 * camper simply walks back to its tile and carries on.
 */
public final class TravelerNight {
    private enum Phase {
        WALKING, BY_THE_FIRE, STROLLING, BACKING_OFF, SEATED, STANDING_UP, SITTING_DOWN, ASLEEP,
        TAVERN_WALK, TAVERN_STAND, BED_WALK, IN_BED
    }

    private static final class State {
        BlockPos slot;
        Phase phase = Phase.WALKING;
        int timer;
        /** Bedtime ticks spent close to its tile without getting onto it. */
        int nearTimer;
        float yaw;
        /** Ticks until the next way of passing the evening. */
        int pastimeIn;
        /** Standing on its own tile (strolls take it off and back). */
        boolean atOwnSlot;
        @Nullable BlockPos strollTo;
        /** Where it shuffles back to before sitting, and what it does once there. */
        double sitX;
        double sitZ;
        Phase afterBackOff = Phase.SEATED;
        /** Spending the evening at {@link #tavernId}, standing at {@link #tavernTile}. */
        boolean inTavern;
        /** No room or no way in tonight: the evening goes on at camp or the stall. */
        boolean skipTavern;
        @Nullable UUID tavernId;
        @Nullable BlockPos tavernTile;
        /** The tile beside its guest bed it steps into bed from. */
        @Nullable BlockPos bedSide;
        /** No map route to its guest bed tonight: it sleeps at camp instead. */
        boolean skipBed;
    }

    /** Walking into the tavern this long (1 min) without getting there gives up on it for tonight. */
    private static final int TAVERN_WALK_GIVE_UP_TICKS = 1_200;
    /** Standing spots are picked this close to the stage first, to hear the bard. */
    private static final double NEAR_STAGE_SQ = 5.0 * 5.0;
    /** Something to do in the tavern every 5 to 12 s. */
    private static final int TAVERN_PAUSE = 100;
    private static final int TAVERN_PAUSE_JITTER = 140;
    /** How far a visitor in the tavern looks round for somebody to face. */
    private static final double LOOK_RANGE = 6.0;

    /** How far from the fire's middle a camper sits: its legs then stop short of the flames. */
    private static final double SIT_DISTANCE = 1.6;
    /** Tried in turn when the ground past the camp is lower or blocked. */
    private static final double[] SIT_DISTANCES = {SIT_DISTANCE, 1.45};
    /** Still on the camper's own tile beside the fire: always level with it. */
    private static final double SAFE_SIT_DISTANCE = 1.2;
    private static final float BACK_OFF_SPEED = 0.25F;
    /** Shuffling back gives up after this long (2 s) and sits where it got to. */
    private static final int BACK_OFF_TICKS = 40;

    private static final float WALK_SPEED = 0.45F;
    private static final float STROLL_SPEED = 0.3F;
    private static final int SITDOWN_TICKS = 25;
    private static final int SITUP_TICKS = 45;
    private static final int SLEEP_ANIM_REFRESH = 15;
    /** Walking this long without reaching the tile, the camper settles where it is. */
    private static final int MAX_WALK_TICKS = 2_400;
    /** At bedtime a camper close to its tile but not on it settles after this long (10 s) and sleeps. */
    private static final int BEDTIME_WALK_TICKS = 200;
    /** A stroll round the fire that has not arrived after this long (8 s) stops where it is. */
    private static final int STROLL_GIVE_UP_TICKS = 160;
    /** Pause between pastimes: 8 to 20 s. */
    private static final int PASTIME_PAUSE = 160;
    private static final int PASTIME_PAUSE_JITTER = 240;
    /** Sitting by the fire, awake: 20 to 50 s. */
    private static final int SEATED_TICKS = 400;
    private static final int SEATED_JITTER = 600;
    private static final double AT_SLOT_SQ = 0.45 * 0.45;
    private static final VillagerAnimationState[] GESTURES = {
            VillagerAnimationState.THINK, VillagerAnimationState.ARM_ON_HIP, VillagerAnimationState.SHRUG};

    private static final WeakHashMap<AbstractTravelerEntity, State> STATES = new WeakHashMap<>();
    private static final TravelerApproach ROUTES = new TravelerApproach("Camp");

    private TravelerNight() {}

    /** Runs the camp evening and night for {@code traveler}; true when it took this tick. */
    public static boolean tick(AbstractTravelerEntity traveler, ServerLevel level) {
        if (traveler.isReturningHome()) {
            clear(traveler);
            return false;
        }
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        long clock = Math.floorMod(dayTime, TravelerLifecycleRules.DAY_TICKS);
        long departure = traveler.getDepartureAtGameTime();
        if (clock < TravelerCamp.CAMP_FROM_CLOCK || departure < 0L) {
            clear(traveler);
            return false;
        }
        boolean camps = TravelerLifecycleRules.staysPastSunrise(departure, now, dayTime);
        if (!camps && !staysTheEvening(departure, now, dayTime)) {
            clear(traveler);
            return false;
        }
        VillageManager village = villageOf(traveler, level);
        if (village == null) {
            clear(traveler);
            return false;
        }
        State state = STATES.computeIfAbsent(traveler, ignored -> new State());
        RandomSource random = traveler.getRandom();

        // The evening at the tavern: until bedtime for campers, until they
        // leave for those staying only the evening.
        if (!state.skipTavern && (!camps || clock < TravelerCamp.SLEEP_FROM_CLOCK)) {
            TavernBuilding tavern = tavernFor(village);
            if (tavern != null && tickTavern(traveler, level, village, tavern, state, random)) return true;
        }
        if (state.inTavern) {
            // Bedtime: back to camp, out through the door the route opens.
            TravelerTalk.stop(traveler);
            state.inTavern = false;
            state.phase = Phase.WALKING;
            state.timer = 0;
            ROUTES.clear(traveler);
            traveler.setAnimation(VillagerAnimationState.IDLE);
        }
        if (!camps) {
            // Staying only the evening, with no tavern: its stall or gathering spot.
            ROUTES.clear(traveler);
            return false;
        }
        BlockPos bed = village.travelerCamp().bedFor(traveler.getUUID());
        if (bed != null && !state.skipBed && tickGuestBed(traveler, level, village, state, bed)) return true;
        if (bed == null && traveler.isSleeping()) {
            // Lying in a bed it no longer has (taken from it, or lost in a reload): up.
            traveler.stopSleeping();
            traveler.setPose(Pose.STANDING);
            traveler.setAnimation(VillagerAnimationState.IDLE);
        }

        BlockPos fire = village.travelerCamp().fire();
        BlockPos slot = village.travelerCamp().slotFor(traveler.getUUID());
        if (fire == null || slot == null) {
            // No camp: its usual spot. The state stays, so a full tavern is not
            // looked over again every tick.
            if (state.phase != Phase.WALKING) traveler.setAnimation(VillagerAnimationState.IDLE);
            state.phase = Phase.WALKING;
            state.slot = null;
            ROUTES.clear(traveler);
            return false;
        }
        if (!slot.equals(state.slot)) {
            state.slot = slot;
            state.phase = Phase.WALKING;
            state.timer = 0;
        }
        boolean bedtime = clock >= TravelerCamp.SLEEP_FROM_CLOCK;
        switch (state.phase) {
            // The quick bedtime give-up is for the last few steps, not the walk
            // over from the tavern: it counts only the time spent close by.
            case WALKING -> walk(traveler, village, state, fire,
                    bedtime && traveler.blockPosition().distSqr(slot) <= 9
                            && Math.abs(traveler.getY() - slot.getY()) < 0.6);
            case BY_THE_FIRE -> {
                hold(traveler, state);
                if (bedtime) {
                    // A chat still going at bedtime ends here, or the villager
                    // would count as busy with this visitor all night.
                    TravelerTalk.stop(traveler);
                    if (state.atOwnSlot) {
                        backOff(traveler, state, fire, Phase.SITTING_DOWN, random);
                    } else {
                        state.phase = Phase.WALKING; // back to its own tile to sleep
                        state.timer = 0;
                    }
                } else if (TravelerTalk.tick(traveler, level, village)) {
                    // chatting with a villager by the fire
                } else if (--state.pastimeIn <= 0) {
                    pickPastime(traveler, level, state, fire, random);
                }
            }
            case STROLLING -> stroll(traveler, village, state, fire, random);
            case BACKING_OFF -> {
                double dx = state.sitX - traveler.getX();
                double dz = state.sitZ - traveler.getZ();
                if (traveler.getY() < fire.getY() - 0.5) {
                    // Slipped off a ledge anyway: back up to its own tile first.
                    state.phase = Phase.WALKING;
                    state.timer = 0;
                } else if (dx * dx + dz * dz <= 0.01 || ++state.timer >= BACK_OFF_TICKS) {
                    traveler.getNavigation().stop();
                    state.yaw = yawToward(traveler, fire);
                    sitDown(traveler, state, state.afterBackOff, random);
                } else {
                    traveler.getMoveControl().setWantedPosition(state.sitX, traveler.getY(), state.sitZ,
                            BACK_OFF_SPEED);
                }
            }
            case SEATED -> {
                hold(traveler, state);
                if (bedtime && state.atOwnSlot) {
                    fallAsleep(traveler, state); // already sitting: nods off where it sits
                } else if (bedtime || --state.timer <= 0) {
                    state.phase = Phase.STANDING_UP;
                    state.timer = 0;
                    traveler.setAnimation(VillagerAnimationState.SITUP);
                }
            }
            case STANDING_UP -> {
                hold(traveler, state);
                if (++state.timer >= SITUP_TICKS) {
                    traveler.setAnimation(VillagerAnimationState.IDLE);
                    state.phase = Phase.BY_THE_FIRE;
                    state.pastimeIn = pause(random);
                }
            }
            case SITTING_DOWN -> {
                hold(traveler, state);
                if (++state.timer >= SITDOWN_TICKS) fallAsleep(traveler, state);
            }
            case ASLEEP -> {
                hold(traveler, state);
                if (++state.timer >= SLEEP_ANIM_REFRESH) {
                    state.timer = 0;
                    traveler.setAnimation(VillagerAnimationState.SLEEP_SITTING_DOWN);
                }
            }
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // The evening at the tavern
    // -------------------------------------------------------------------------

    /**
     * Still here when the evening guests leave (about 9 PM): staying the
     * evening or the night, not leaving at dusk.
     */
    static boolean staysTheEvening(long departure, long now, long dayTime) {
        long clock = Math.floorMod(dayTime, TravelerLifecycleRules.DAY_TICKS);
        return departure >= now + Math.max(0L, TravelerLifecycleRules.EVENING_LEAVE_START - clock);
    }

    /** Some visitor here tonight stays for the evening (or the night): a tavern night. */
    public static boolean hasEveningGuests(VillageManager village, ServerLevel level) {
        long now = level.getGameTime();
        long dayTime = level.getDayTime();
        UUID merchant = village.visitors().activeMerchantId();
        if (merchant != null) {
            Long departure = village.visitors().departureFor(merchant);
            if (departure != null && staysTheEvening(departure, now, dayTime)) return true;
        }
        for (long departure : village.nomads().savedActiveNomads().values()) {
            if (staysTheEvening(departure, now, dayTime)) return true;
        }
        return false;
    }

    @Nullable
    private static TavernBuilding tavernFor(VillageManager village) {
        for (com.alex.test.village.building.Building building : village.getBuildings()) {
            if (building instanceof TavernBuilding tavern && tavern.isValid()
                    && !tavern.getNavMeshBlocks().isEmpty()) return tavern;
        }
        return null;
    }

    /**
     * Walks in and stands somewhere free, near the stage when it can, facing
     * it, with the odd gesture. Visitors never take a chair, so no villager
     * loses a seat to them. False when there is no room or no way in tonight.
     */
    private static boolean tickTavern(AbstractTravelerEntity traveler, ServerLevel level, VillageManager village,
                                      TavernBuilding tavern, State state, RandomSource random) {
        if (!state.inTavern || !tavern.getItemFrameId().equals(state.tavernId)) {
            BlockPos tile = pickTavernTile(traveler, level, tavern, random);
            if (tile == null) {
                state.skipTavern = true;
                return false;
            }
            state.inTavern = true;
            state.tavernId = tavern.getItemFrameId();
            state.tavernTile = tile;
            state.phase = Phase.TAVERN_WALK;
            state.timer = 0;
        }
        BlockPos tile = state.tavernTile;
        if (state.phase == Phase.TAVERN_WALK) {
            double dx = traveler.getX() - (tile.getX() + 0.5);
            double dz = traveler.getZ() - (tile.getZ() + 0.5);
            boolean there = dx * dx + dz * dz <= AT_SLOT_SQ && Math.abs(traveler.getY() - tile.getY()) <= 1.5;
            if (there || ++state.timer >= TAVERN_WALK_GIVE_UP_TICKS) {
                ROUTES.clear(traveler);
                traveler.getNavigation().stop();
                if (!there && !tavern.getNavMeshBlocks().contains(traveler.blockPosition())) {
                    // Could not get in: the evening goes on outside.
                    state.inTavern = false;
                    state.skipTavern = true;
                    return false;
                }
                state.phase = Phase.TAVERN_STAND;
                state.pastimeIn = pause(random);
                BlockPos stage = tavern.getStagePos();
                state.yaw = stage != null ? yawToward(traveler, stage) : traveler.getYRot();
                traveler.setAnimation(VillagerAnimationState.IDLE);
                village.travelerCamp().welcomeAtTavern(level, tavern);
                return true;
            }
            TravelerApproach.Result route = ROUTES.tick(traveler, village, tile, WALK_SPEED);
            if (route == TravelerApproach.Result.WALKING) return true;
            if (dx * dx + dz * dz <= 2.25) {
                traveler.getMoveControl().setWantedPosition(tile.getX() + 0.5, tile.getY(), tile.getZ() + 0.5,
                        WALK_SPEED);
            } else if (endsWithoutRoute(route)) {
                // Inside the village and the map has no way to the tile: the walk ends
                // next tick as if its time ran out (stands where it got to, or the
                // evening goes on outside).
                noteNoRoute(traveler, "a tavern spot", tile);
                state.timer = TAVERN_WALK_GIVE_UP_TICKS;
            } else if (traveler.getNavigation().isDone()) {
                VanillaNavCount.note(VanillaNavCount.Site.TAVERN, traveler);
                traveler.getNavigation().moveTo(tile.getX() + 0.5, tile.getY(), tile.getZ() + 0.5, WALK_SPEED);
            }
            return true;
        }
        // In the tavern: listening, looking round, cheering the bard, moving
        // over beside somebody.
        hold(traveler, state);
        if (TravelerTalk.tick(traveler, level, village)) return true; // chatting with a villager
        if (--state.pastimeIn <= 0) tavernPastime(traveler, level, village, tavern, state, random);
        return true;
    }

    private static void tavernPastime(AbstractTravelerEntity traveler, ServerLevel level, VillageManager village,
                                      TavernBuilding tavern, State state, RandomSource random) {
        state.pastimeIn = TAVERN_PAUSE + random.nextInt(TAVERN_PAUSE_JITTER);
        int roll = random.nextInt(100);
        if (roll < 25) {
            traveler.setAnimation(GESTURES[random.nextInt(GESTURES.length)]);
            return;
        }
        if (roll < 45 && bardPlaysIn(village, tavern)) {
            traveler.setAnimation(VillagerAnimationState.WAVE); // the cheer
            return;
        }
        if (roll < 75) {
            net.minecraft.world.entity.Entity someone = someoneNear(traveler, level);
            if (someone != null) {
                state.yaw = yawToward(traveler, someone.blockPosition());
                return;
            }
        }
        BlockPos next = mingleTile(traveler, level, village, tavern, random);
        if (next != null && !next.equals(state.tavernTile)) {
            state.tavernTile = next;
            state.phase = Phase.TAVERN_WALK;
            state.timer = 0;
        }
    }

    /** A bard of this village is in the tavern (playing for it tonight). */
    private static boolean bardPlaysIn(VillageManager village, TavernBuilding tavern) {
        for (com.alex.test.village.VillagerModel model : village.getVillagers()) {
            var npc = model.puppet();
            if (npc != null && npc.isAlive()
                    && npc.getProfession() == com.alex.test.village.villagers.core.Profession.BARD
                    && tavern.getNavMeshBlocks().contains(npc.blockPosition())) return true;
        }
        return false;
    }

    /** The nearest villager or player within a few blocks, to turn and look at. */
    @Nullable
    private static net.minecraft.world.entity.Entity someoneNear(AbstractTravelerEntity traveler, ServerLevel level) {
        net.minecraft.world.entity.Entity best = null;
        double bestSq = LOOK_RANGE * LOOK_RANGE;
        for (net.minecraft.world.entity.Entity e : level.getEntities(traveler,
                traveler.getBoundingBox().inflate(LOOK_RANGE),
                e -> e instanceof com.alex.test.entity.NpcEntity
                        || e instanceof net.minecraft.world.entity.player.Player)) {
            double d = e.distanceToSqr(traveler);
            if (d < bestSq) {
                bestSq = d;
                best = e;
            }
        }
        return best;
    }

    /** Another free tile inside, beside a villager when there is one, else anywhere free. */
    @Nullable
    private static BlockPos mingleTile(AbstractTravelerEntity traveler, ServerLevel level, VillageManager village,
                                       TavernBuilding tavern, RandomSource random) {
        List<BlockPos> beside = new ArrayList<>();
        for (com.alex.test.village.VillagerModel model : village.getVillagers()) {
            var npc = model.puppet();
            if (npc == null || !tavern.getNavMeshBlocks().contains(npc.blockPosition())) continue;
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos tile = npc.blockPosition().relative(side);
                if (tavern.getNavMeshBlocks().contains(tile) && isFreeTavernTile(traveler, level, tavern, tile)) {
                    beside.add(tile);
                }
            }
        }
        if (!beside.isEmpty()) return beside.get(random.nextInt(beside.size()));
        return pickTavernTile(traveler, level, tavern, random);
    }

    private static boolean isFreeTavernTile(AbstractTravelerEntity traveler, ServerLevel level,
                                            TavernBuilding tavern, BlockPos tile) {
        BlockPos door = tavern.getDoorPos();
        if (door != null && Math.abs(tile.getX() - door.getX()) <= 1 && Math.abs(tile.getZ() - door.getZ()) <= 1) {
            return false;
        }
        return level.getBlockState(tile).getCollisionShape(level, tile).isEmpty()
                && level.getBlockState(tile.above()).getCollisionShape(level, tile.above()).isEmpty()
                && !isTakenByAnotherVisitor(traveler, tile)
                && level.getEntities(traveler, new AABB(tile)).isEmpty();
    }

    /**
     * A tile inside the tavern with nobody on it and no other visitor headed
     * for it, away from the door: near the stage first, else anywhere inside.
     */
    @Nullable
    private static BlockPos pickTavernTile(AbstractTravelerEntity traveler, ServerLevel level,
                                           TavernBuilding tavern, RandomSource random) {
        BlockPos door = tavern.getDoorPos();
        BlockPos stage = tavern.getStagePos();
        List<BlockPos> near = new ArrayList<>();
        List<BlockPos> any = new ArrayList<>();
        for (BlockPos tile : tavern.getNavMeshBlocks()) {
            if (door != null && Math.abs(tile.getX() - door.getX()) <= 1
                    && Math.abs(tile.getZ() - door.getZ()) <= 1) continue;
            if (!level.getBlockState(tile).getCollisionShape(level, tile).isEmpty()
                    || !level.getBlockState(tile.above()).getCollisionShape(level, tile.above()).isEmpty()) continue;
            if (isTakenByAnotherVisitor(traveler, tile)) continue;
            if (!level.getEntities(traveler, new AABB(tile)).isEmpty()) continue;
            any.add(tile);
            if (stage != null && tile.distSqr(stage) <= NEAR_STAGE_SQ) near.add(tile);
        }
        List<BlockPos> from = !near.isEmpty() ? near : any;
        return from.isEmpty() ? null : from.get(random.nextInt(from.size()));
    }

    private static boolean isTakenByAnotherVisitor(AbstractTravelerEntity traveler, BlockPos tile) {
        for (var entry : STATES.entrySet()) {
            if (entry.getKey() != traveler && entry.getValue().inTavern && tile.equals(entry.getValue().tavernTile)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // The evening by the fire
    // -------------------------------------------------------------------------

    /** Something to do for a while: a gesture, a few steps round the fire, or a seat. */
    private static void pickPastime(AbstractTravelerEntity traveler, ServerLevel level, State state,
                                    BlockPos fire, RandomSource random) {
        int roll = random.nextInt(100);
        if (!state.atOwnSlot || roll >= 35 && roll < 70) {
            BlockPos to = state.atOwnSlot ? strollSpot(traveler, level, fire, state.slot, random) : state.slot;
            if (to != null) {
                state.strollTo = to;
                state.phase = Phase.STROLLING;
                state.timer = 0;
                // The walk starts next tick, on the village map like the walk to camp (see stroll).
                return;
            }
        }
        if (roll < 35) {
            traveler.setAnimation(GESTURES[random.nextInt(GESTURES.length)]);
            state.pastimeIn = pause(random);
        } else if (roll >= 70) {
            backOff(traveler, state, fire, Phase.SEATED, random);
        } else {
            state.pastimeIn = pause(random);
        }
    }

    /**
     * Before sitting, shuffle back from the fire: seated legs stretch out about
     * a block in front, and on the tile beside the fire they ended up in the
     * flames. The camper moves out along the line from the fire to itself, to
     * {@link #SIT_DISTANCE} from the fire's middle, then sits facing it. Something
     * behind it (a wall, a tree) just stops it short.
     */
    private static void backOff(AbstractTravelerEntity traveler, State state, BlockPos fire, Phase next,
                                RandomSource random) {
        double fx = fire.getX() + 0.5;
        double fz = fire.getZ() + 0.5;
        double ox = traveler.getX() - fx;
        double oz = traveler.getZ() - fz;
        double distance = Math.sqrt(ox * ox + oz * oz);
        if (distance < 1.0E-3 || distance >= SIT_DISTANCE) {
            sitDown(traveler, state, next, random);
            return;
        }
        // Back only onto ground level with the fire: past the camp's own tiles
        // the land can drop away, and a camper shuffling over the edge ended
        // up asleep a block below. Closer in, it stays on its own tile.
        double sitAt = SAFE_SIT_DISTANCE;
        for (double d : SIT_DISTANCES) {
            double x = fx + ox / distance * d;
            double z = fz + oz / distance * d;
            if (isLevelGround((ServerLevel) traveler.level(), BlockPos.containing(x, fire.getY(), z))) {
                sitAt = d;
                break;
            }
        }
        if (distance >= sitAt) {
            sitDown(traveler, state, next, random);
            return;
        }
        state.sitX = fx + ox / distance * sitAt;
        state.sitZ = fz + oz / distance * sitAt;
        state.afterBackOff = next;
        state.phase = Phase.BACKING_OFF;
        state.timer = 0;
    }

    /** Feet room and solid ground underneath, on the fire's own level. */
    private static boolean isLevelGround(ServerLevel level, BlockPos feet) {
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                && !level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                && !NavGraph.isHazard(level.getBlockState(feet));
    }

    private static void sitDown(AbstractTravelerEntity traveler, State state, Phase next, RandomSource random) {
        traveler.setAnimation(VillagerAnimationState.SITDOWN);
        state.phase = next;
        state.timer = next == Phase.SEATED ? SEATED_TICKS + random.nextInt(SEATED_JITTER) : 0;
    }

    private static void stroll(AbstractTravelerEntity traveler, VillageManager village, State state, BlockPos fire,
                               RandomSource random) {
        BlockPos to = state.strollTo;
        double dx = traveler.getX() - (to.getX() + 0.5);
        double dz = traveler.getZ() - (to.getZ() + 0.5);
        boolean there = dx * dx + dz * dz <= AT_SLOT_SQ;
        if (there || ++state.timer >= STROLL_GIVE_UP_TICKS) {
            ROUTES.clear(traveler);
            traveler.getNavigation().stop();
            state.atOwnSlot = there && to.equals(state.slot);
            state.yaw = yawToward(traveler, fire);
            state.phase = Phase.BY_THE_FIRE;
            state.pastimeIn = pause(random);
            return;
        }
        // On the village map, the villagers' walker; Minecraft's path only off it.
        TravelerApproach.Result route = ROUTES.tick(traveler, village, to, STROLL_SPEED);
        if (route == TravelerApproach.Result.WALKING) return;
        if (dx * dx + dz * dz <= 2.25) {
            traveler.getMoveControl().setWantedPosition(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, STROLL_SPEED);
        } else if (endsWithoutRoute(route)) {
            // No map way to the spot: the stroll stops where it is.
            state.timer = STROLL_GIVE_UP_TICKS;
        } else if (traveler.getNavigation().isDone()) {
            VanillaNavCount.note(VanillaNavCount.Site.STROLL, traveler);
            traveler.getNavigation().moveTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5, STROLL_SPEED);
        }
    }

    /**
     * A free tile two steps from the fire to stand on for a while: open to walk
     * on, with ground under it, not a hazard, and nobody already there.
     */
    @Nullable
    private static BlockPos strollSpot(AbstractTravelerEntity traveler, ServerLevel level, BlockPos fire,
                                       BlockPos slot, RandomSource random) {
        List<BlockPos> spots = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
                BlockPos feet = fire.offset(dx, 0, dz);
                if (feet.distSqr(slot) > 5) continue; // a step or two from its own tile
                if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                        || !level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()
                        || level.getBlockState(feet.below()).getCollisionShape(level, feet.below()).isEmpty()
                        || NavGraph.isHazard(level.getBlockState(feet))
                        || !level.getFluidState(feet).isEmpty()) continue;
                if (!level.getEntities(traveler, new AABB(feet)).isEmpty()) continue;
                spots.add(feet);
            }
        }
        return spots.isEmpty() ? null : spots.get(random.nextInt(spots.size()));
    }

    private static int pause(RandomSource random) {
        return PASTIME_PAUSE + random.nextInt(PASTIME_PAUSE_JITTER);
    }

    // -------------------------------------------------------------------------
    // Walking to camp, sleeping
    // -------------------------------------------------------------------------

    /**
     * Leftovers step 4 ({@link WalkFeel#campMap}): a walk that is not on its map route
     * (the map has none, or the route ended more than 1.5 blocks from the tile) and
     * is still inside the village ends instead of trying Minecraft's path. Outside the
     * village ({@link TravelerApproach.Result#USE_VANILLA}) Minecraft's path stays.
     */
    private static boolean endsWithoutRoute(TravelerApproach.Result route) {
        return WalkFeel.campMap && route != TravelerApproach.Result.USE_VANILLA;
    }

    private static void noteNoRoute(AbstractTravelerEntity traveler, String what, BlockPos tile) {
        DynamicVillages.LOGGER.info("[Camp] {} {} has no map route to {} at {} from {}; that walk ends here.",
                traveler instanceof com.alex.test.entity.visitor.VisitorEntity ? "Merchant" : "Nomad",
                TravelerApproach.shortId(traveler), what, tile.toShortString(),
                traveler.blockPosition().toShortString());
    }

    private static void walk(AbstractTravelerEntity traveler, VillageManager village, State state,
                             BlockPos fire, boolean nearAtBedtime) {
        BlockPos slot = state.slot;
        double dx = traveler.getX() - (slot.getX() + 0.5);
        double dz = traveler.getZ() - (slot.getZ() + 0.5);
        boolean there = dx * dx + dz * dz <= AT_SLOT_SQ && Math.abs(traveler.getY() - slot.getY()) < 0.6;
        state.nearTimer = nearAtBedtime ? state.nearTimer + 1 : 0;
        if (there || ++state.timer >= MAX_WALK_TICKS || state.nearTimer >= BEDTIME_WALK_TICKS) {
            ROUTES.clear(traveler);
            traveler.getNavigation().stop();
            state.phase = Phase.BY_THE_FIRE;
            state.timer = 0;
            state.nearTimer = 0;
            // One that gave up counts as settled: it sleeps where it stands.
            state.atOwnSlot = true;
            state.pastimeIn = pause(traveler.getRandom());
            state.yaw = yawToward(traveler, fire);
            traveler.setAnimation(VillagerAnimationState.IDLE);
            return;
        }
        TravelerApproach.Result route = ROUTES.tick(traveler, village, slot, WALK_SPEED);
        if (route == TravelerApproach.Result.WALKING) return;
        if (dx * dx + dz * dz <= 2.25) {
            // The last step onto the exact tile, beside a fire the graph routes around.
            traveler.getMoveControl().setWantedPosition(slot.getX() + 0.5, slot.getY(), slot.getZ() + 0.5,
                    WALK_SPEED);
        } else if (endsWithoutRoute(route)) {
            // No map way to its tile: it settles where it stands next tick, as one
            // whose walk ran out of time does.
            noteNoRoute(traveler, "its camp tile", slot);
            state.timer = MAX_WALK_TICKS;
        } else if (traveler.getNavigation().isDone()) {
            VanillaNavCount.note(VanillaNavCount.Site.CAMP, traveler);
            traveler.getNavigation().moveTo(slot.getX() + 0.5, slot.getY(), slot.getZ() + 0.5, WALK_SPEED);
        }
    }

    private static void fallAsleep(AbstractTravelerEntity traveler, State state) {
        state.phase = Phase.ASLEEP;
        state.timer = 0;
        traveler.setAnimation(VillagerAnimationState.SLEEP_SITTING_DOWN);
        DynamicVillages.LOGGER.info("[Camp] {} {} fell asleep by the fire at {}.",
                traveler instanceof com.alex.test.entity.visitor.VisitorEntity ? "Merchant" : "Nomad",
                TravelerApproach.shortId(traveler), traveler.blockPosition().toShortString());
    }

    private static void hold(AbstractTravelerEntity traveler, State state) {
        traveler.holdStillForAnimation();
        traveler.setYRot(state.yaw);
        traveler.setYHeadRot(state.yaw);
        traveler.setYBodyRot(state.yaw);
    }

    private static float yawToward(AbstractTravelerEntity traveler, BlockPos fire) {
        double fx = fire.getX() + 0.5 - traveler.getX();
        double fz = fire.getZ() + 0.5 - traveler.getZ();
        return (float) Math.toDegrees(Math.atan2(-fx, fz));
    }

    /** Sitting asleep by the fire: clicking it says so instead of opening anything. */
    public static boolean isAsleep(AbstractTravelerEntity traveler) {
        State state = STATES.get(traveler);
        return state != null && (state.phase == Phase.SITTING_DOWN || state.phase == Phase.ASLEEP
                || state.phase == Phase.IN_BED);
    }

    // -------------------------------------------------------------------------
    // A guest bed at the tavern
    // -------------------------------------------------------------------------

    /**
     * From bedtime, a guest with a bed walks to it and lies down, as villagers
     * sleep in theirs, until morning. Before bedtime it spends the evening at
     * the tavern (above). False before bedtime, or when the bed has gone.
     */
    private static boolean tickGuestBed(AbstractTravelerEntity traveler, ServerLevel level, VillageManager village,
                                        State state, BlockPos bed) {
        long clock = Math.floorMod(level.getDayTime(), TravelerLifecycleRules.DAY_TICKS);
        if (clock < TravelerCamp.SLEEP_FROM_CLOCK) return false;
        BlockState here = level.getBlockState(bed);
        if (!(here.getBlock() instanceof BedBlock)) return false;
        if (traveler.isSleeping() && traveler.getSleepingPos().filter(bed::equals).isEmpty()) {
            // Lying in a bed it no longer has (a villager's own): up, and over to this one.
            traveler.stopSleeping();
            traveler.setPose(Pose.STANDING);
            traveler.setAnimation(VillagerAnimationState.IDLE);
            state.phase = Phase.WALKING;
        }
        if (state.phase != Phase.IN_BED && traveler.isSleeping()
                && traveler.getSleepingPos().filter(bed::equals).isPresent()) {
            // Already lying in it: back from a reload, or put there before.
            state.phase = Phase.IN_BED;
            state.timer = 0;
            village.travelerCamp().markSleptInBed(traveler.getUUID());
        }
        if (state.phase == Phase.IN_BED) {
            if (!traveler.isSleeping()) {
                state.phase = Phase.BED_WALK; // woken: back into bed
                state.timer = 0;
                return true;
            }
            traveler.holdStillWhileSleeping();
            if (++state.timer >= SLEEP_ANIM_REFRESH) {
                state.timer = 0;
                traveler.setAnimation(VillagerAnimationState.SLEEP_LYING_DOWN);
            }
            return true;
        }
        if (state.phase != Phase.BED_WALK) {
            state.phase = Phase.BED_WALK;
            state.timer = 0;
            state.bedSide = bedSide(level, bed, traveler);
        }
        BlockPos side = state.bedSide != null ? state.bedSide : bed;
        double dx = traveler.getX() - (side.getX() + 0.5);
        double dz = traveler.getZ() - (side.getZ() + 0.5);
        boolean there = dx * dx + dz * dz <= 0.8 * 0.8;
        if (there || ++state.timer >= MAX_WALK_TICKS) {
            ROUTES.clear(traveler);
            traveler.getNavigation().stop();
            if (here.getValue(BedBlock.OCCUPIED)) return false; // someone is in it
            traveler.startBedSleep(bed);
            traveler.setAnimation(VillagerAnimationState.SLEEP_LYING_DOWN);
            village.travelerCamp().markSleptInBed(traveler.getUUID());
            state.phase = Phase.IN_BED;
            state.timer = 0;
            DynamicVillages.LOGGER.info("[Camp] {} {} went to sleep in a guest bed at {}.",
                    traveler instanceof com.alex.test.entity.visitor.VisitorEntity ? "Merchant" : "Nomad",
                    TravelerApproach.shortId(traveler), bed.toShortString());
            return true;
        }
        TravelerApproach.Result route = ROUTES.tick(traveler, village, side, WALK_SPEED);
        if (route == TravelerApproach.Result.WALKING) return true;
        if (dx * dx + dz * dz <= 2.25) {
            traveler.getMoveControl().setWantedPosition(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, WALK_SPEED);
        } else if (endsWithoutRoute(route)) {
            // No map way to the bed. Not the timeout's way out (that puts it in the
            // bed from wherever it stands): it sleeps at camp tonight instead.
            noteNoRoute(traveler, "its guest bed", side);
            ROUTES.clear(traveler);
            traveler.getNavigation().stop();
            state.skipBed = true;
            state.phase = Phase.WALKING;
            state.timer = 0;
            return false;
        } else if (traveler.getNavigation().isDone()) {
            VanillaNavCount.note(VanillaNavCount.Site.GUEST_BED, traveler);
            traveler.getNavigation().moveTo(side.getX() + 0.5, side.getY(), side.getZ() + 0.5, WALK_SPEED);
        }
        return true;
    }

    /** A free tile beside the bed to step into it from, nearest the guest. */
    @Nullable
    private static BlockPos bedSide(ServerLevel level, BlockPos bed, AbstractTravelerEntity traveler) {
        BlockState state = level.getBlockState(bed);
        BlockPos foot = state.getBlock() instanceof BedBlock
                ? bed.relative(state.getValue(BedBlock.FACING).getOpposite()) : bed;
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos half : List.of(bed, foot)) {
            for (net.minecraft.core.Direction side : net.minecraft.core.Direction.Plane.HORIZONTAL) {
                BlockPos tile = half.relative(side);
                if (tile.equals(bed) || tile.equals(foot)) continue;
                if (!level.getBlockState(tile).getCollisionShape(level, tile).isEmpty()
                        || !level.getBlockState(tile.above()).getCollisionShape(level, tile.above()).isEmpty()
                        || level.getBlockState(tile.below()).getCollisionShape(level, tile.below()).isEmpty()) continue;
                double d = traveler.distanceToSqr(tile.getX() + 0.5, tile.getY(), tile.getZ() + 0.5);
                if (d < bestSq) {
                    bestSq = d;
                    best = tile;
                }
            }
        }
        return best;
    }

    /** Gets up, if sitting or asleep, and forgets the walk to camp. */
    public static void clear(AbstractTravelerEntity traveler) {
        State state = STATES.remove(traveler);
        if (state == null) {
            // A guest saved lying in its bed comes back without this state:
            // get it up all the same, or it stays lying down all day.
            if (traveler.isSleeping()) {
                traveler.stopSleeping();
                traveler.setPose(Pose.STANDING);
                traveler.setAnimation(VillagerAnimationState.IDLE);
            }
            return;
        }
        ROUTES.clear(traveler);
        TravelerTalk.stop(traveler);
        if (traveler.isSleeping()) traveler.stopSleeping();
        if (state.phase != Phase.WALKING && state.phase != Phase.STROLLING && state.phase != Phase.TAVERN_WALK
                && state.phase != Phase.BED_WALK) {
            traveler.setPose(Pose.STANDING);
            traveler.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    @Nullable
    private static VillageManager villageOf(AbstractTravelerEntity traveler, ServerLevel level) {
        UUID villageId = traveler.getVillageId();
        if (villageId == null || level.getServer() == null) return null;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        if (registry.isVillageDormantOrReconciling(villageId)) return null;
        return registry.get(villageId);
    }
}
