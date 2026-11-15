package com.alex.test.village.building;

import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * One give-up at a Butchery, for its window (round 2 of docs/plans/2026-10-24-butchery-window.md):
 * what went wrong, the kind of animal (its entity type id), where it stood and when (game time).
 * A Butchery keeps its last {@link ButcheryBuilding#TROUBLES_KEPT} in memory only: a restart
 * forgets them.
 */
public record ButcheryTrouble(Kind kind, String animal, BlockPos where, Place place, long gameTime) {

    /** Outside the room, this close to the door (blocks across, either way) counts as by the door. */
    private static final int DOOR_REACH = 3;

    /**
     * What went wrong. {@code roomTrouble}: the room or the way to it can help; the others
     * (danger came first, a player's pet, a tough animal) are nothing the room could change.
     */
    public enum Kind {
        STUCK(true), DOOR_HELD(true), WOULD_NOT_COME_IN(true), NO_WAY(true), FELL_BEHIND(true),
        ROOM_BROKE(true), WOULD_NOT_GO_DOWN(false), CALLED_AWAY(false), PLAYERS_ANIMAL(false);

        private final boolean roomTrouble;

        Kind(boolean roomTrouble) {
            this.roomTrouble = roomTrouble;
        }

        public boolean roomTrouble() {
            return roomTrouble;
        }
    }

    /** On a room tile, outside within {@link #DOOR_REACH} of the door, or further off on the way. */
    public enum Place { ROOM, DOOR, WAY }

    /**
     * Where the animal stood, from both its cells: {@code raw} its block position, {@code feet}
     * the walker's feet cell (a stair's lower step or a bottom slab is one under the tile).
     */
    public static ButcheryTrouble at(Kind kind, String animal, BlockPos raw, BlockPos feet,
                                     Set<BlockPos> room, BlockPos door, long gameTime) {
        if (room.contains(raw)) return new ButcheryTrouble(kind, animal, raw, Place.ROOM, gameTime);
        if (room.contains(feet)) return new ButcheryTrouble(kind, animal, feet, Place.ROOM, gameTime);
        boolean byDoor = Math.max(Math.abs(feet.getX() - door.getX()), Math.abs(feet.getZ() - door.getZ())) <= DOOR_REACH
                && Math.abs(feet.getY() - door.getY()) <= 2;
        return new ButcheryTrouble(kind, animal, feet, byDoor ? Place.DOOR : Place.WAY, gameTime);
    }
}
