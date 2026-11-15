package com.alex.test.village;

import com.alex.test.entity.NpcEntity;
import net.minecraft.world.entity.PathfinderMob;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/**
 * Opens nearby doors and fence gates that lie on an NPC's current route and
 * closes them once the NPC has moved away.
 *
 * <p>Fence gates matter because farm and ranch enclosures are fenced: the gate
 * is the only way in, and it has to close again behind the villager or the
 * animals simply walk out.
 *
 * <p>Any walking body may use it ({@link WalkBody}); a raider's doors stay open behind it.
 */
public final class RouteDoorHandler {

    /** Squared distance at which an on-route door should be opened. */
    private static final double DOOR_OPEN_DIST_SQ = 6.25; // 2.5 blocks

    /** Squared distance at which a passed door is considered behind and closed. */
    private static final double DOOR_CLOSE_DIST_SQ = 6.25; // 2.5 blocks

    /**
     * Anchor positions of doors/gates this handler used; closed once the NPC
     * moves away. For doors this is the lower half, for gates the gate itself.
     */
    private final Set<BlockPos> openedDoors = new HashSet<>();

    /**
     * Open or track door blocks that are near the NPC and actually lie on the
     * active route. This avoids touching doors the NPC merely walks past.
     */
    public void tick(PathfinderMob npc, Predicate<BlockPos> routeContainsDoor) {
        // The look for doors to open reads 50 blocks, and a walker covers well
        // under a block in 3 ticks while doors open from 2.5 blocks away, so
        // every 3rd tick still opens them in time. The first call of a new
        // handler (a new walk) looks at once. Closing is checked every tick.
        if (ticksToNextDoorLook <= 0) {
            openRouteDoors(npc, routeContainsDoor);
            ticksToNextDoorLook = DOOR_LOOK_EVERY_TICKS;
        }
        ticksToNextDoorLook--;
        closePassedDoors(npc);
    }

    private static final int DOOR_LOOK_EVERY_TICKS = 3;
    private int ticksToNextDoorLook;

    private void openRouteDoors(PathfinderMob npc, Predicate<BlockPos> routeContainsDoor) {
        if (!(npc.level() instanceof ServerLevel sl)) return;
        BlockPos base = npc.blockPosition();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                tryUseRouteDoor(sl, base.offset(dx, 0, dz), base, npc, routeContainsDoor);
                tryUseRouteDoor(sl, base.offset(dx, 1, dz), base, npc, routeContainsDoor);
            }
        }
    }

    /**
     * If {@code pos} is a door half or a fence gate on the active route, open it
     * and record its anchor position so it can be closed later. Already-open
     * player doors are recorded too, but only when the NPC is really going
     * through them.
     */
    private void tryUseRouteDoor(ServerLevel sl, BlockPos pos, BlockPos npcPos,
                                 PathfinderMob npc, Predicate<BlockPos> routeContainsDoor) {
        BlockState state = sl.getBlockState(pos);
        BlockPos anchor;
        if (state.getBlock() instanceof DoorBlock) {
            boolean isUpper = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    == DoubleBlockHalf.UPPER;
            anchor = isUpper ? pos.below() : pos;
        } else if (state.getBlock() instanceof FenceGateBlock) {
            anchor = gateAnchor(sl, pos);
        } else {
            return;
        }
        // Bug 5 (irondoors): an iron door is redstone's: walked through when open, never worked.
        if (!handOpens(state)) return;

        if (anchor.distSqr(npcPos) > DOOR_OPEN_DIST_SQ) return;
        if (!routeContainsDoor.test(anchor) && !isDoorOnVanillaPath(npc, anchor)) return;

        setOpen(sl, anchor, true, npc);
        // A raider leaves the doors it opens standing open, as Minecraft's raiders do; a gate it
        // still shuts behind it, or the animals in a pen walk out (raider plan round 2).
        if (WalkBody.shutsDoorsBehind(npc) || state.getBlock() instanceof FenceGateBlock) openedDoors.add(anchor);
    }

    /**
     * The other leaf of a double door: the door beside this one that faces the
     * same way with its hinge on the opposite side, as vanilla places a pair.
     */
    @Nullable
    public static BlockPos doubleDoorPartner(BlockGetter sl, BlockPos lowerHalf) {
        BlockState state = sl.getBlockState(lowerHalf);
        if (!(state.getBlock() instanceof DoorBlock)) return null;
        Direction facing = state.getValue(DoorBlock.FACING);
        DoorHingeSide hinge = state.getValue(DoorBlock.HINGE);
        for (Direction side : new Direction[] { facing.getClockWise(), facing.getCounterClockWise() }) {
            BlockPos pos = lowerHalf.relative(side);
            BlockState other = sl.getBlockState(pos);
            if (other.getBlock() instanceof DoorBlock
                    && other.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER
                    && other.getValue(DoorBlock.FACING) == facing
                    && other.getValue(DoorBlock.HINGE) != hinge) {
                return pos;
            }
        }
        return null;
    }

    /** The most fence gates one tall gateway is taken to stack. */
    private static final int MAX_STACKED_GATES = 3;

    /**
     * The bottom gate of fence gates stacked at {@code pos} (a tall gateway, one gate on top of
     * another); {@code pos} itself for a single gate. A stack is one gateway, like a door's two
     * halves: opened, held and shut by its bottom gate ({@link #setOpen} moves the rest with it).
     * Only the bottom gate used to open, and a villager walked into the shut one at head height.
     */
    public static BlockPos gateAnchor(BlockGetter level, BlockPos pos) {
        BlockPos at = pos.immutable();
        for (int i = 1; i < MAX_STACKED_GATES && level.getBlockState(at.below()).getBlock() instanceof FenceGateBlock; i++) {
            at = at.below();
        }
        return at;
    }

    private boolean isDoorOnVanillaPath(PathfinderMob npc, BlockPos anchorPos) {
        Path path = npc.getNavigation().getPath();
        if (path == null) return false;

        for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            if (node.x == anchorPos.getX()
                    && node.z == anchorPos.getZ()
                    && Math.abs(node.y - anchorPos.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    /**
     * Close any previously-used door whose anchor is now more than
     * {@link #DOOR_CLOSE_DIST_SQ} blocks away from the NPC.
     */
    private void closePassedDoors(PathfinderMob npc) {
        if (openedDoors.isEmpty()) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;
        BlockPos npcPos = npc.blockPosition();
        openedDoors.removeIf(anchor -> {
            // Held open for a led animal: leave it, and try again next tick.
            if (DoorHolds.isHeld(sl, anchor)) return false;
            // Someone else is still in the doorway: kept, and tried again next tick.
            if (isDoorwayOccupied(sl, anchor, npc)) return false;
            if (anchor.distSqr(npcPos) > DOOR_CLOSE_DIST_SQ) {
                closeDoor(sl, anchor, npc);
                return true;
            }
            return false;
        });
    }

    /**
     * Takes over the doors another handler opened, so they are still closed
     * behind the villager once it walks away from them.
     */
    public void adopt(RouteDoorHandler previous) {
        openedDoors.addAll(previous.openedDoors);
        previous.openedDoors.clear();
    }

    /** Close all doors used during this movement command (called on arrival or cancellation). */
    public void closeAllOpenedDoors(PathfinderMob npc) {
        if (openedDoors.isEmpty()) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;
        // Held open for a led animal: kept for the next tick (or the next
        // handler that adopts these doors); the holder closes it itself.
        openedDoors.removeIf(anchor -> {
            if (DoorHolds.isHeld(sl, anchor)) return false;
            closeDoor(sl, anchor, npc);
            return true;
        });
    }

    /**
     * Closes the doors used during this movement command, like
     * {@link #closeAllOpenedDoors}, except one a villager or player is still
     * standing in: that one is kept, to be closed once the doorway is clear.
     * A walk often ends with its villager still in the doorway, and the door
     * shut on it pinned it in the frame; a shut fence gate made it hop, as
     * vanilla jumps a mob out of any block it is inside except doors and fences.
     */
    public void closeVacatedDoors(PathfinderMob npc) {
        if (openedDoors.isEmpty()) return;
        if (!(npc.level() instanceof ServerLevel sl)) return;
        openedDoors.removeIf(anchor -> {
            if (DoorHolds.isHeld(sl, anchor)) return false;
            if (isDoorwayOccupied(sl, anchor, npc)) return false;
            closeDoor(sl, anchor, npc);
            return true;
        });
    }

    /** True while this handler still holds a door or gate to shut. */
    public boolean hasDoors() {
        return !openedDoors.isEmpty();
    }

    /**
     * True when a villager or a player stands in the doorway of the door or
     * gate whose anchor is {@code anchor}, or the walker that opened it does
     * (a raider: raider plan round 3; a villager already counts). Animals do
     * not count: a ranch gate still shuts behind a villager with a sheep in
     * the gateway, or the animals walk out.
     */
    private static boolean isDoorwayOccupied(ServerLevel sl, BlockPos anchor, PathfinderMob walker) {
        AABB box = new AABB(anchor);
        // Both halves of a door.
        if (sl.getBlockState(anchor).getBlock() instanceof DoorBlock) box = box.expandTowards(0, 1, 0);
        return !sl.getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && (e == walker || e instanceof NpcEntity
                        || (e instanceof Player p && !p.isSpectator()))).isEmpty();
    }

    /**
     * Shuts the door (and an open other leaf of a double door) whose lower
     * half is {@code anchor}, as a villager would: with its sound. For a
     * villager hiding indoors keeping its building's door shut; nothing here
     * changes how route doors are opened or closed.
     */
    public static void shutDoorAt(ServerLevel sl, BlockPos anchor, PathfinderMob npc) {
        closeDoor(sl, anchor, npc);
    }

    /**
     * Opens the door or gate whose anchor is {@code anchor}, with its sound.
     * For a villager leading an animal through a double door: one leaf is
     * 13/16 of a block wide when open, narrower than a cow, so it opens the
     * other leaf as well.
     */
    public static void openDoorAt(ServerLevel sl, BlockPos anchor, PathfinderMob npc) {
        setOpen(sl, anchor, true, npc);
    }

    /**
     * Raider plan bug 5 (10-19): false for a door no hand opens (iron: redstone only) while
     * {@link WalkFeel#ironDoors} is on; true for every other block. Until then villagers opened
     * and shut iron doors by code, so a locked one kept nobody in or out.
     */
    public static boolean handOpens(BlockState state) {
        return !WalkFeel.ironDoors || !(state.getBlock() instanceof DoorBlock door) || door.type().canOpenByHand();
    }

    /**
     * Closes a door this handler used. Only the leaf on the route is ever
     * opened, but when the other leaf of a double door is standing open as
     * well, it is shut too, so the pair is not left half open behind the
     * villager. A closed partner is never touched.
     */
    private static void closeDoor(ServerLevel sl, BlockPos anchor, PathfinderMob npc) {
        setOpen(sl, anchor, false, npc);
        BlockPos partner = doubleDoorPartner(sl, anchor);
        if (partner != null && sl.getBlockState(partner).getValue(BlockStateProperties.OPEN)) {
            setOpen(sl, partner, false, npc);
        }
    }

    /**
     * Sets the open state of the door (both halves) or fence gate at
     * {@code anchor}, playing the block's open/close sound and emitting the
     * matching game event just like a player interaction would. No-ops for
     * anything else, so a door replaced mid-walk cannot corrupt an unrelated
     * block.
     */
    private static void setOpen(ServerLevel sl, BlockPos anchor, boolean open, PathfinderMob npc) {
        BlockState state = sl.getBlockState(anchor);
        // Bug 5 (irondoors): no walker opens or shuts an iron door; only redstone does.
        if (!handOpens(state)) return;
        if (state.getBlock() instanceof FenceGateBlock gate) {
            if (state.getValue(BlockStateProperties.OPEN) != open) {
                sl.setBlock(anchor, state.setValue(BlockStateProperties.OPEN, open), 10);
                sl.playSound(null, anchor, open ? gate.openSound : gate.closeSound,
                        SoundSource.BLOCKS, 1.0F, sl.getRandom().nextFloat() * 0.1F + 0.9F);
                sl.gameEvent(npc, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, anchor);
            }
            // Gates stacked on it are the same tall gateway: they follow, silently, even when
            // the bottom one was already as wanted (a player opened only that one).
            for (int i = 1; i < MAX_STACKED_GATES; i++) {
                BlockPos up = anchor.above(i);
                BlockState upper = sl.getBlockState(up);
                if (!(upper.getBlock() instanceof FenceGateBlock)) break;
                if (upper.getValue(BlockStateProperties.OPEN) != open) {
                    sl.setBlock(up, upper.setValue(BlockStateProperties.OPEN, open), 10);
                }
            }
            return;
        }
        if (state.getBlock() instanceof DoorBlock door) {
            // Vanilla setOpen plays the door's own sound and fires the game event.
            door.setOpen(npc, sl, state, anchor, open);
        }
        // The upper half normally follows via shape updates; this only repairs
        // a mismatched half and stays silent so the sound is not doubled.
        BlockPos upperPos = anchor.above();
        BlockState upper = sl.getBlockState(upperPos);
        if (upper.getBlock() instanceof DoorBlock
                && upper.getValue(BlockStateProperties.OPEN) != open) {
            sl.setBlock(upperPos, upper.setValue(BlockStateProperties.OPEN, open), 10);
        }
    }
}
