package com.alex.test.entity.mayor;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcUiInteractionHandler;
import com.alex.test.testkit.TestKit;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.ConversationScripts;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight Mayor behavior controller.
 *
 * <p>Movement is delegated to the existing {@link VillagerModel}, so Mayor
 * walking uses the same cached NavGraph, async A*, and waypoint executor as all
 * other NPCs. Expensive decisions are deliberately throttled.
 */
public final class MayorAi {

    private static final double ACTIVE_PLAYER_RANGE = 48.0;
    private static final double LOOK_RANGE_SQ = 12.0 * 12.0;
    private static final int PLAYER_SCAN_INTERVAL = 20;
    private static final int ACTIVE_DECISION_INTERVAL = 40;
    private static final int INACTIVE_DECISION_INTERVAL = 200;
    private static final float SALUTE_CHANCE = 0.20F;
    private static final int SALUTE_COOLDOWN_MIN = 1200;
    private static final int SALUTE_COOLDOWN_EXTRA = 1200;

    private final MayorEntity mayor;
    private final VillagerModel movement;
    private final NpcUiInteractionHandler uiInteraction = new NpcUiInteractionHandler();
    private final Set<UUID> playersInside = new HashSet<>();

    @Nullable
    private VillageManager manager;
    @Nullable
    private ServerPlayer lookTarget;
    @Nullable
    private NpcEntity conversationTarget;
    @Nullable
    private ConverseActivity conversation;
    @Nullable
    private GoHomeActivity sleepActivity;

    private int playerScanTimer;
    private int decisionTimer;
    private int lookTicks;
    private int missingManagerTicks;
    private boolean lastPlayerGreetingWasSalute;
    private long nextSaluteAllowedGameTime;
    private int lastWanderFloorY = Integer.MIN_VALUE;
    private int sameFloorStopsRemaining;
    private long sleepSkipWakeAtGameTime;

    public MayorAi(MayorEntity mayor) {
        this.mayor = mayor;
        this.movement = new VillagerModel(mayor.getNpcId());
        this.movement.bind(mayor);
    }

    public void staggerWakeAfterPlayerSleep(ServerLevel level) {
        if (mayor.isSleeping() || sleepActivity != null && sleepActivity.isResting()) {
            sleepSkipWakeAtGameTime = level.getGameTime()
                    + VillagerBrain.playerSleepWakeDelay(movement.npcId);
        }
    }

    public void tick(ServerLevel level) {
        if (DynamicVillages.isServerStopping()
                || (level.getServer().getPlayerList().getPlayerCount() == 0
                    && !TestKit.headless())) {
            movement.stopMoving();
            return;
        }
        MayorTownHallLink link = mayor.getTownHallLink();
        if (link == null) return;

        TownHallBuilding townHall = resolveTownHall(level, link);
        if (townHall == null) {
            VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
            // Paused, or counting down to the pause while its Town Hall token is
            // out: the Mayor waits for the token, and sells a new one once paused.
            if (registry.isVillageInactive(link.villageId())
                    || registry.isAwaitingTownHallToken(link.villageId())) {
                movement.stopMoving();
                missingManagerTicks = 0;
                // It waits on its feet: asleep in the removed hall it used to
                // lie there, even through the day, until a new token was hung.
                getUp(level);
                return;
            }
            // Its village was asleep when the world loaded and is drawing its
            // map now that a player is near; the hall comes back with it.
            if (manager != null && manager.isAwaitingInitialize()) {
                movement.stopMoving();
                missingManagerTicks = 0;
                return;
            }
            // Registry initialization is deferred during world startup. Give it
            // ample time before treating the link as orphaned.
            if (++missingManagerTicks > 600 && !DynamicVillages.isServerStopping()) {
                mayor.discard();
            }
            return;
        }
        missingManagerTicks = 0;

        // Advance the same movement model residents run every server tick.
        // Its idle tick still checks water escape, village bounds, and physical
        // blockage. Once asleep, movement stays suspended.
        if (!mayor.isSleeping()) {
            movement.tick(manager.getGraph());
        }

        // Sleep overrides all daytime theater. GoHomeActivity supplies the
        // existing bed path, sleep pose, seated fallback, and morning wake-up.
        if (level.getGameTime() < sleepSkipWakeAtGameTime
                && (mayor.isSleeping() || sleepActivity != null)) return;
        if (VillagerBrain.isNight(level, movement.npcId)) {
            tickSleep(level, townHall);
            return;
        }

        // Only the daytime transition is allowed to wake a sleeping Mayor.
        if (mayor.isSleeping()) {
            mayor.stopSleeping();
            mayor.setPose(Pose.STANDING);
            mayor.setAnimation(VillagerAnimationState.IDLE);
        }

        // Its Town Hall bed stays its own by day, like a villager's house bed (10-24).
        if (sleepActivity != null) {
            Activity.Context context = context(level);
            sleepActivity.stop(context);
            sleepActivity = null;
            decisionTimer = 20;
        }

        if (conversation != null) {
            Activity.Context context = context(level);
            Activity.Status status = conversation.tickOuter(context);
            if (status != Activity.Status.RUNNING) {
                conversation.stop(context);
                conversation = null;
                conversationTarget = null;
                decisionTimer = 80 + mayor.getRandom().nextInt(120);
            } else if (conversationTarget != null) {
                faceEntity(conversationTarget, true);
            }
            uiInteraction.tick(mayor, movement, level, false);
            return;
        }

        // UI interaction pauses ordinary movement after sleep/conversation have
        // had their higher-priority turn. This checks only the interacting player.
        if (uiInteraction.tick(mayor, movement, level, !isInRestPose())) {
            return;
        }

        if (conversationTarget != null) {
            tickConversationApproach(level, townHall);
            return;
        }

        boolean active = level.hasNearbyAlivePlayer(
                townHall.getDoorPos().getX() + 0.5,
                townHall.getDoorPos().getY() + 0.5,
                townHall.getDoorPos().getZ() + 0.5,
                ACTIVE_PLAYER_RANGE);

        if (++playerScanTimer >= PLAYER_SCAN_INTERVAL) {
            playerScanTimer = 0;
            scanForEnteringPlayers(level, townHall, active);
        }

        if (lookTicks > 0) {
            tickLook();
            return;
        }

        if (--decisionTimer > 0) return;
        decisionTimer = active ? ACTIVE_DECISION_INTERVAL : INACTIVE_DECISION_INTERVAL;

        if (!townHall.isInRoom(mayor.blockPosition())) {
            moveToRandomTownHallTile(townHall);
            return;
        }

        // When nobody is near, the Mayor does no social scans and only makes an
        // occasional daytime stroll.
        if (!active) {
            if (mayor.getRandom().nextInt(3) == 0) {
                moveToRandomTownHallTile(townHall);
            }
            return;
        }

        if (mayor.getRandom().nextInt(6) == 0
                && tryApproachConversationPartner(townHall)) {
            return;
        }

        if (mayor.getRandom().nextBoolean()) {
            moveToRandomTownHallTile(townHall);
        }
    }

    private void tickSleep(ServerLevel level, TownHallBuilding townHall) {
        // A Mayor already sleeping in a bed (for example after an entity reload)
        // remains asleep. Do not run movement, player attention, conversations,
        // or constructor-style pose cleanup while it is still night.
        if (mayor.isSleeping() && sleepActivity == null) {
            lookTarget = null;
            lookTicks = 0;
            conversationTarget = null;
            playersInside.clear();
            if (mayor.getAnimation() != VillagerAnimationState.SLEEP_LYING_DOWN) {
                mayor.setAnimation(VillagerAnimationState.SLEEP_LYING_DOWN);
            }
            return;
        }

        if (sleepActivity == null) {
            movement.stopMoving();
            lookTarget = null;
            lookTicks = 0;
            conversationTarget = null;
            if (conversation != null) {
                conversation.stop(context(level));
                conversation = null;
            }
            // A hall bed still claimed by a Mayor that died or left would never come free.
            manager.releaseHallClaimsExcept(townHall.getItemFrameId(), movement.npcId);
            sleepActivity = new GoHomeActivity(true, townHall);
        }

        Activity.Context context = context(level);
        Activity.Status status = sleepActivity.tickOuter(context);
        if (status != Activity.Status.RUNNING) {
            sleepActivity.stop(context);
            sleepActivity = null;
        }
    }

    @Nullable
    private TownHallBuilding resolveTownHall(ServerLevel level, MayorTownHallLink link) {
        if (manager == null || !manager.getVillageId().equals(link.villageId())) {
            manager = VillageManagerRegistry.get(level.getServer()).get(link.villageId());
            if (manager == null && VillageManager.INSTANCE.getVillageId().equals(link.villageId())) {
                manager = VillageManager.INSTANCE;
            }
        }
        if (manager == null || manager.getLevel() != level) return null;
        Building building = manager.getBuilding(link.townHallId());
        return building instanceof TownHallBuilding hall && hall.isValid() ? hall : null;
    }

    private void scanForEnteringPlayers(ServerLevel level, TownHallBuilding hall, boolean active) {
        if (!active) {
            playersInside.clear();
            return;
        }

        Set<UUID> nowInside = new HashSet<>();
        ServerPlayer entered = null;
        double closest = Double.MAX_VALUE;

        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || !hall.isInRoom(player.blockPosition())) continue;
            double distance = mayor.distanceToSqr(player);
            if (distance <= LOOK_RANGE_SQ) nowInside.add(player.getUUID());
            if (!playersInside.contains(player.getUUID())
                    && distance <= LOOK_RANGE_SQ && distance < closest) {
                entered = player;
                closest = distance;
            }
        }

        playersInside.clear();
        playersInside.addAll(nowInside);
        if (entered != null) startLookingAt(entered);
    }

    private void startLookingAt(ServerPlayer player) {
        if (mayor.isSleeping() || conversation != null || conversationTarget != null) return;
        movement.stopMoving();
        lookTarget = player;
        lookTicks = 50;
        faceEntity(player, true);

        long gameTime = ((ServerLevel) mayor.level()).getGameTime();
        boolean salute = !lastPlayerGreetingWasSalute
                && gameTime >= nextSaluteAllowedGameTime
                && mayor.getRandom().nextFloat() < SALUTE_CHANCE;
        if (salute) {
            // Picked here rather than on the client so every player sees the same one.
            mayor.setAnimation(mayor.getRandom().nextBoolean()
                    ? VillagerAnimationState.SALUTE
                    : VillagerAnimationState.WAVE);
            lastPlayerGreetingWasSalute = true;
            nextSaluteAllowedGameTime = gameTime + SALUTE_COOLDOWN_MIN
                    + mayor.getRandom().nextInt(SALUTE_COOLDOWN_EXTRA + 1);
        } else {
            lastPlayerGreetingWasSalute = false;
        }
    }

    private void tickLook() {
        if (lookTarget == null || !lookTarget.isAlive()
                || mayor.distanceToSqr(lookTarget) > LOOK_RANGE_SQ * 2.0) {
            lookTicks = 0;
            lookTarget = null;
            decisionTimer = 20;
            return;
        }
        faceEntity(lookTarget, true);
        if (--lookTicks <= 0) {
            lookTarget = null;
            decisionTimer = 20;
        }
    }

    /** How far from a conversation partner the Mayor stops to talk. */
    private static final double CONVERSATION_STAND_DIST = 1.6;

    private boolean tryApproachConversationPartner(TownHallBuilding hall) {
        if (mayor.isSleeping()) return false;
        List<NpcEntity> candidates = new ArrayList<>();
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || !hall.isInRoom(npc.blockPosition())) continue;
            if (!model.brain().canAcceptMayorConversation()) continue;
            if (mayor.distanceToSqr(npc) <= 10.0 * 10.0) candidates.add(npc);
        }
        if (candidates.isEmpty()) return false;

        conversationTarget = candidates.get(mayor.getRandom().nextInt(candidates.size()));
        BlockPos stand = standBeside(conversationTarget);
        if (stand == null) { conversationTarget = null; return false; }
        movement.requestMoveTo(manager.getGraph(), stand, 0.42F);
        return true;
    }

    /**
     * A tile to talk to {@code partner} from: beside it, not on top of it.
     *
     * <p>The Mayor used to path to the villager's own block, so it arrived
     * inside its conversation partner and the two stood nose to nose -- and if
     * the villager happened to be up a step, the Mayor ended up talking to its
     * knees. One block back along the line between them is where a person would
     * stop, and snapping it to the nav graph keeps it somewhere walkable.
     */
    @Nullable
    private BlockPos standBeside(NpcEntity partner) {
        NavGraph graph = manager.getGraph();
        if (graph == null) return null;
        double dx = mayor.getX() - partner.getX();
        double dz = mayor.getZ() - partner.getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        BlockPos wanted = len < 1.0e-3
                ? partner.blockPosition().relative(Direction.NORTH, 2)
                : BlockPos.containing(
                        partner.getX() + dx / len * CONVERSATION_STAND_DIST,
                        partner.getY(),
                        partner.getZ() + dz / len * CONVERSATION_STAND_DIST);
        NavNode node = graph.nearest(wanted, 2, 1);
        return node != null ? node.feetPos() : null;
    }

    private void tickConversationApproach(ServerLevel level, TownHallBuilding hall) {
        if (conversationTarget == null || !conversationTarget.isAlive()
                || !hall.isInRoom(conversationTarget.blockPosition())) {
            cancelConversationApproach();
            return;
        }
        if (movement.isMoving()) return;

        if (ConverseActivity.wellPlaced(mayor, conversationTarget)) {
            int duration = 100 + mayor.getRandom().nextInt(80);
            VillageManager partnerManager = manager;
            VillagerModel partnerModel = partnerManager.getModel(conversationTarget.getNpcId());
            // The Mayor opens on village affairs and the villager answers from
            // its own memory -- which is how a grievance reaches the Mayor.
            // Trimmed to the (shorter) Mayor chat length so it ends on a line.
            ConversationScript script = partnerModel == null ? null
                    : ConversationScripts.within(
                            ConversationScripts.about(MemoryTopic.MAYOR_VISIT,
                                    partnerModel.brain().memory(),
                                    conversationTarget.getProfession(),
                                    level.getGameTime(), mayor.getRandom()),
                            duration);
            conversation = new ConverseActivity(
                    conversationTarget, true, duration, true, false, script);
            if (partnerModel != null) {
                partnerModel.brain().forceActivity(
                        new ConverseActivity(mayor, false, duration, true, true, script));
            }
        } else {
            cancelConversationApproach();
        }
    }

    private void cancelConversationApproach() {
        movement.stopMoving();
        conversationTarget = null;
        decisionTimer = 80;
    }

    private void moveToRandomTownHallTile(TownHallBuilding hall) {
        if (mayor.isSleeping() || movement.isMoving() || hall.getNavMeshBlocks().isEmpty()) return;
        BlockPos current = mayor.blockPosition();
        BlockPos nearestTile = null;
        long nearestDistance = Long.MAX_VALUE;
        for (BlockPos tile : hall.getNavMeshBlocks()) {
            long dx = tile.getX() - current.getX();
            long dy = tile.getY() - current.getY();
            long dz = tile.getZ() - current.getZ();
            long distance = dx * dx + dz * dz + 4 * dy * dy;
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestTile = tile;
            }
        }
        if (nearestTile == null) return;

        int floorY = nearestTile.getY();
        if (floorY != lastWanderFloorY) {
            lastWanderFloorY = floorY;
            sameFloorStopsRemaining = 3;
        }
        List<BlockPos> sameFloor = new ArrayList<>();
        List<BlockPos> otherFloors = new ArrayList<>();
        for (BlockPos tile : hall.getNavMeshBlocks()) {
            if (Math.abs(tile.getY() - floorY) <= 1) sameFloor.add(tile);
            else otherFloors.add(tile);
        }

        // A cross-floor trip should lead to a few walks around that storey,
        // instead of immediately choosing the ladder back down.
        boolean changeFloor = !otherFloors.isEmpty()
                && (sameFloor.isEmpty()
                || sameFloorStopsRemaining == 0 && mayor.getRandom().nextInt(5) == 0);
        List<BlockPos> tiles = changeFloor ? otherFloors : sameFloor;
        if (tiles.isEmpty()) tiles = new ArrayList<>(hall.getNavMeshBlocks());
        List<BlockPos> distant = new ArrayList<>();
        for (BlockPos tile : tiles) {
            long dx = tile.getX() - current.getX();
            long dz = tile.getZ() - current.getZ();
            if (dx * dx + dz * dz >= 9) distant.add(tile);
        }
        if (!distant.isEmpty()) tiles = distant;
        BlockPos target = tiles.get(mayor.getRandom().nextInt(tiles.size()));
        if (!changeFloor && sameFloorStopsRemaining > 0) sameFloorStopsRemaining--;
        movement.requestMoveTo(manager.getGraph(), target, 0.42F);
    }

    private Activity.Context context(ServerLevel level) {
        return new Activity.Context(movement.brain(), movement, manager, level, mayor);
    }

    /** In its night now: on the way to a bed or a hall spot, waiting for one, or asleep. */
    public boolean isInNight() {
        return sleepActivity != null || mayor.isSleeping();
    }

    /** The hall floor tile it sleeps on, or walks to, tonight; null in a bed, by day, or with no spot free. */
    @Nullable
    public BlockPos hallSleepSpot() {
        return sleepActivity != null ? movement.reservedSleepSpot() : null;
    }

    /** Ends its night, if any: out of bed or off its hall spot; its Town Hall bed stays its own. Cheap when awake. */
    private void getUp(ServerLevel level) {
        if (sleepActivity != null) {
            if (manager != null) sleepActivity.stop(context(level));
            sleepActivity = null;
        }
        if (mayor.isSleeping()) mayor.stopSleeping();
        if (isInRestPose()) {
            mayor.setPose(Pose.STANDING);
            mayor.setAnimation(VillagerAnimationState.IDLE);
        }
    }

    /** Start/refresh the UI movement lock; conversations keep their NPC facing. */
    public void onPlayerInteract(ServerPlayer player) {
        uiInteraction.markOpened(mayor, player);

        // Opening the menu is passive while the Mayor is sleeping, sitting,
        // or transitioning into/out of rest. In particular, stopMoving() sets
        // the puppet animation to IDLE and would cause a one-tick standing flash.
        if (isInRestPose() || conversation != null) return;

        movement.pauseMovingForUi();
        conversationTarget = null;
        NpcUiInteractionHandler.faceEntity(mayor, movement, player, true);
    }

    private boolean isInRestPose() {
        if (mayor.isSleeping()) return true;
        return switch (mayor.getAnimation()) {
            case SITDOWN, SLEEP_SITTING_DOWN, SITUP, SLEEP_LYING_DOWN,
                 WALL_SITDOWN, WALL_SLEEP, WALL_SITUP -> true;
            default -> false;
        };
    }

    /** Accurate eye-level facing with a smooth body turn while standing still. */
    private void faceEntity(Entity target, boolean turnBody) {
        NpcUiInteractionHandler.faceEntity(mayor, movement, target, turnBody);
    }
}
