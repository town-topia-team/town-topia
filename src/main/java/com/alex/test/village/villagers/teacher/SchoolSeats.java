package com.alex.test.village.villagers.teacher;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import com.alex.test.village.VillageChairs;
import com.alex.test.village.building.SchoolBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where a class sits: desks, rows, and the teacher's own spot.
 *
 * <p>The class faces its lectern. Chairs in the room that face the lectern
 * are desks and are filled first, nearest the front. Without enough chairs
 * the children stand in rows across the room: every other row is left empty
 * as a walkway, so a child can always reach its place without pushing past
 * the others. The tiles round the lectern are the teacher's, and the tiles
 * round the door are kept clear so nobody blocks the way in. Children past
 * the last seat stand at the back.
 *
 * <p>A room with two lecterns holds two classes; each floor tile belongs to
 * the class whose lectern is nearest, counting only lecterns in use, so one
 * teacher alone still gets the whole room. Layouts are worked out once and
 * kept for {@value #CACHE_TICKS} ticks.
 */
public final class SchoolSeats {

    /** A place in class: a chair to sit on, or a floor tile to stand on. */
    public record Seat(BlockPos tile, @Nullable VillageChairs.Chair chair, boolean overflow) {
        /** Where to walk to take this seat. */
        public BlockPos walkTo() { return chair != null ? chair.approach() : tile; }
        /** The seat's identity: the chair block, or the floor tile. */
        public BlockPos key()    { return chair != null ? chair.pos() : tile; }
    }

    /** One class's places, the teacher's spot beside the lectern, and the middle of the class. */
    public record Layout(List<Seat> seats, @Nullable BlockPos teacherStand, Vec3 classCentre) {}

    private static final int CACHE_TICKS = 600;

    private record Cached(Layout layout, long at, int tiles, Set<BlockPos> lecterns) {}
    private static final Map<SchoolClasses.ClassKey, Cached> CACHE = new HashMap<>();

    private SchoolSeats() {}

    /**
     * The layout for the class at {@code lectern}. {@code lecternsInUse} are
     * the school's other lecterns that have a class of their own right now.
     */
    public static Layout layout(ServerLevel level, NavGraph graph, SchoolBuilding school,
                                BlockPos lectern, Collection<BlockPos> lecternsInUse) {
        SchoolClasses.ClassKey key = new SchoolClasses.ClassKey(school.getItemFrameId(), lectern);
        Set<BlockPos> lecterns = new HashSet<>(lecternsInUse);
        lecterns.add(lectern);
        lecterns.retainAll(school.getFunctionalBlocks());
        lecterns.add(lectern);
        int tileCount = school.getNavMeshBlocks().size();
        long now = level.getGameTime();
        Cached cached = CACHE.get(key);
        if (cached != null && now - cached.at() < CACHE_TICKS && now >= cached.at()
                && cached.tiles() == tileCount && cached.lecterns().equals(lecterns)) {
            return cached.layout();
        }
        Layout layout = compute(level, graph, school, lectern, lecterns);
        if (CACHE.size() > 64) CACHE.clear();
        CACHE.put(key, new Cached(layout, now, tileCount, lecterns));
        return layout;
    }

    /** Lecterns of {@code school} with a class open right now, apart from {@code lectern}. */
    public static List<BlockPos> otherLecternsInUse(ServerLevel level, SchoolBuilding school, BlockPos lectern) {
        List<BlockPos> out = new ArrayList<>();
        for (SchoolClasses.ClassKey key : SchoolClasses.openClasses(level)) {
            if (key.school().equals(school.getItemFrameId()) && !key.lectern().equals(lectern)) {
                out.add(key.lectern());
            }
        }
        return out;
    }

    private static Layout compute(ServerLevel level, NavGraph graph, SchoolBuilding school,
                                  BlockPos lectern, Set<BlockPos> lecterns) {
        Set<BlockPos> roomTiles = school.getNavMeshBlocks();

        // This class's floor: usable tiles on the lectern's floor, nearer this lectern than any other in use.
        List<BlockPos> mine = new ArrayList<>();
        for (BlockPos tile : roomTiles) {
            if (Math.abs(tile.getY() - lectern.getY()) > 1) continue;
            NavNode node = graph != null ? graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) : null;
            if (node == null || !node.isRoutable() || node.climb) continue;
            if (!lectern.equals(nearestLectern(tile, lecterns, lectern))) continue;
            mine.add(tile);
        }

        // Which way the class faces: from the lectern towards the middle of its floor.
        double cx = 0, cz = 0;
        for (BlockPos tile : mine) {
            cx += tile.getX() + 0.5;
            cz += tile.getZ() + 0.5;
        }
        double dirX, dirZ;
        if (!mine.isEmpty()) {
            dirX = cx / mine.size() - (lectern.getX() + 0.5);
            dirZ = cz / mine.size() - (lectern.getZ() + 0.5);
        } else {
            dirX = dirZ = 0;
        }
        if (dirX * dirX + dirZ * dirZ < 0.25) {
            BlockPos entry = school.entryTile();
            dirX = entry.getX() - lectern.getX();
            dirZ = entry.getZ() - lectern.getZ();
        }
        Direction facing = Math.abs(dirX) >= Math.abs(dirZ)
                ? (dirX >= 0 ? Direction.EAST : Direction.WEST)
                : (dirZ >= 0 ? Direction.SOUTH : Direction.NORTH);

        BlockPos stand = teacherStand(graph, roomTiles, lectern, facing);

        // Kept clear: the teacher's corner round the lectern, and the way in.
        Set<BlockPos> kept = new HashSet<>();
        for (BlockPos tile : mine) {
            if (Math.abs(tile.getX() - lectern.getX()) <= 1 && Math.abs(tile.getZ() - lectern.getZ()) <= 1) {
                kept.add(tile);
            }
        }
        BlockPos entry = school.entryTile();
        kept.add(entry);
        for (Direction d : Direction.Plane.HORIZONTAL) kept.add(entry.relative(d));
        if (stand != null) kept.add(stand);

        // Desks: chairs whose sitter would face the lectern.
        List<Seat> chairs = new ArrayList<>();
        for (VillageChairs.Chair chair : chairsIn(level, graph, school)) {
            if (!lectern.equals(nearestLectern(chair.pos(), lecterns, lectern))) continue;
            if (Math.abs(chair.pos().getY() - lectern.getY()) > 1) continue;
            if (depth(chair.pos(), lectern, facing) < 1) continue;
            double tx = lectern.getX() - chair.pos().getX();
            double tz = lectern.getZ() - chair.pos().getZ();
            if (tx * chair.front().getStepX() + tz * chair.front().getStepZ() <= 0) continue;
            chairs.add(new Seat(chair.pos(), chair, false));
            kept.add(chair.approach());
        }
        chairs.sort(Comparator.comparingDouble((Seat s) -> s.key().distSqr(lectern))
                .thenComparingLong(s -> s.key().asLong()));

        // Rows: every other row across the room, nearest the front first, middle first.
        List<Seat> rows = new ArrayList<>();
        List<BlockPos> back = new ArrayList<>();
        for (BlockPos tile : mine) {
            if (kept.contains(tile)) continue;
            int depth = depth(tile, lectern, facing);
            if (depth < 1) continue;
            if (depth >= 2 && depth % 2 == 0) rows.add(new Seat(tile, null, false));
            else back.add(tile);
        }
        rows.sort(Comparator.comparingInt((Seat s) -> depth(s.tile(), lectern, facing))
                .thenComparingInt(s -> lateral(s.tile(), lectern, facing))
                .thenComparingLong(s -> s.tile().asLong()));
        // Past the last seat: stand at the back.
        back.sort(Comparator.comparingInt((BlockPos t) -> -depth(t, lectern, facing))
                .thenComparingInt(t -> lateral(t, lectern, facing))
                .thenComparingLong(BlockPos::asLong));

        List<Seat> seats = new ArrayList<>(chairs);
        seats.addAll(rows);
        for (BlockPos tile : back) seats.add(new Seat(tile, null, true));

        Vec3 centre = classCentre(seats, mine, lectern);
        return new Layout(List.copyOf(seats), stand, centre);
    }

    /**
     * The teacher's spot: beside the lectern, behind it if the room allows
     * (the class in front of both), else at its side, else in front of it.
     */
    @Nullable
    private static BlockPos teacherStand(NavGraph graph, Set<BlockPos> roomTiles, BlockPos lectern,
                                         Direction facing) {
        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos tile = lectern.relative(d);
            if (!roomTiles.contains(tile)) continue;
            NavNode node = graph != null ? graph.nodeAt(tile.getX(), tile.getY(), tile.getZ()) : null;
            if (node == null || !node.isRoutable()) continue;
            int score = d == facing.getOpposite() ? 0 : d == facing ? 2 : 1;
            if (score < bestScore) {
                bestScore = score;
                best = tile;
            }
        }
        return best;
    }

    private static List<VillageChairs.Chair> chairsIn(ServerLevel level, NavGraph graph, SchoolBuilding school) {
        Set<BlockPos> tiles = school.getNavMeshBlocks();
        if (tiles.isEmpty() || graph == null) return List.of();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos t : tiles) {
            minX = Math.min(minX, t.getX()); maxX = Math.max(maxX, t.getX());
            minY = Math.min(minY, t.getY()); maxY = Math.max(maxY, t.getY());
            minZ = Math.min(minZ, t.getZ()); maxZ = Math.max(maxZ, t.getZ());
        }
        List<VillageChairs.Chair> out = new ArrayList<>();
        for (VillageChairs.Chair c : VillageChairs.findInBox(level, graph,
                new BlockPos(minX - 1, minY, minZ - 1), new BlockPos(maxX + 1, maxY, maxZ + 1))) {
            if (tiles.contains(c.approach())) out.add(c);
        }
        return out;
    }

    private static BlockPos nearestLectern(BlockPos pos, Set<BlockPos> lecterns, BlockPos own) {
        BlockPos best = own;
        double bestDist = pos.distSqr(own);
        for (BlockPos l : lecterns) {
            double d = pos.distSqr(l);
            if (d < bestDist) {
                bestDist = d;
                best = l;
            }
        }
        return best;
    }

    /** Rows out from the lectern towards the class: 1 is the row just in front of it. */
    private static int depth(BlockPos pos, BlockPos lectern, Direction facing) {
        return (pos.getX() - lectern.getX()) * facing.getStepX()
                + (pos.getZ() - lectern.getZ()) * facing.getStepZ();
    }

    /** Sideways distance from the line straight out of the lectern. */
    private static int lateral(BlockPos pos, BlockPos lectern, Direction facing) {
        return facing.getAxis() == Direction.Axis.X
                ? Math.abs(pos.getZ() - lectern.getZ())
                : Math.abs(pos.getX() - lectern.getX());
    }

    /** Head height of a seated or standing child in the middle of the class, for the teacher to look at. */
    private static Vec3 classCentre(List<Seat> seats, List<BlockPos> floor, BlockPos lectern) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (Seat s : seats) {
            if (s.overflow()) continue;
            x += s.key().getX() + 0.5;
            y += s.key().getY();
            z += s.key().getZ() + 0.5;
            n++;
        }
        if (n == 0) {
            for (BlockPos t : floor) {
                x += t.getX() + 0.5;
                y += t.getY();
                z += t.getZ() + 0.5;
                n++;
            }
        }
        if (n == 0) return Vec3.atCenterOf(lectern);
        return new Vec3(x / n, y / n + 0.9, z / n);
    }
}
