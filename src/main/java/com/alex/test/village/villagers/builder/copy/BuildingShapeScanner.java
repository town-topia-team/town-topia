package com.alex.test.village.villagers.builder.copy;

import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.RoomScanner;
import com.alex.test.village.villagers.lumberjack.data.LevelTreeView;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/**
 * Finds the real outline of a building: everything a player placed that hangs together
 * with it, walls, roof overhangs, chimneys, porches and towers included, and none of the
 * ground, water, plants or trees around it.
 *
 * <p>It spreads from seed blocks through every touching built block (edges and corners
 * count, so stepped stair roofs hold together) until it meets only natural blocks
 * ({@link NaturalBlocks}), another building, the edge of the {@link Area}, or a cap
 * ({@link #MAX_SIDE} per side, {@link #MAX_BLOCKS} blocks). Then it adds one layer of
 * solid ground under the lowest block of each column, as a foundation.
 *
 * <p>A registered building starts from its {@link #frame}: the floor its room scan found,
 * with the walls and roof around it, whatever they are made of.
 *
 * <p>Paths, fences and garden walls join the buildings of a village to each other, so
 * the spread must not follow them: {@link #isGroundCover ground cover} (at most two
 * blocks tall, on natural ground, open sky above) is taken where it touches the
 * building, but never spread from. With a detected floor, outside its footprint nothing
 * at or below the floor and nothing low on the ground is taken at all ({@link #isGroundOutside}),
 * except the floor-row block a kept wall stands on ({@link #addFoundation}).
 *
 * <p>Built blocks the area keeps out are remembered in {@link Shape#cuts}, so the copy
 * can tell the player what cut it.
 *
 * <p>Server thread. Unloaded chunks count as natural; nothing here loads a chunk.
 */
final class BuildingShapeScanner {
    /** Longest side of a copy, in blocks. */
    static final int MAX_SIDE = 64;
    /** Most blocks one copy finds. */
    static final int MAX_BLOCKS = 40_000;
    /** How far the search goes from where it started, each way. */
    static final int MAX_REACH = 48;
    /** How far up a log column is followed looking for tree leaves. */
    private static final int LOG_CLIMB = 40;
    /** How deep plain sandstone is followed looking for the desert sand above it. */
    private static final int SANDSTONE_CLIMB = 8;
    /** Ground cover is at most this many blocks tall: a path, a slab step, a fence, a low wall. */
    private static final int COVER_HEIGHT = 2;
    /** How far above ground cover a roof is looked for; under a roof it is a floor, not a path. */
    private static final int COVER_SKY = 30;

    private static final byte BUILT = 1, NATURAL = 2;

    /**
     * Where the search may go: a box (inclusive), minus other buildings' shells. A block in
     * the copied building's own shell is always allowed, even where a neighbour's shell
     * overlaps it, so a shared wall goes with the building being copied.
     *
     * <p>{@code reach}, when set, marks the columns of the box (X fastest) near the building's
     * detected floor; the rest of the box is off limits, own shell included, so the empty
     * corner of an L-shaped house is not searched.
     *
     * <p>{@code footprint}, when set, marks the columns (same order) of the detected floor and
     * the walls around it. Outside them nothing at or below {@code floorRow} (the floor under
     * the door) is part of the building, and no ground cover is: paths, yard paving and garden
     * fences stay out. {@code otherNames} names each of {@code otherShells}; {@code border} is
     * the village border (minX, minZ, maxX, maxZ), so a cut there can be told apart.
     */
    record Area(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, List<int[]> otherShells,
                List<String> otherNames, @Nullable int[] ownShell, @Nullable boolean[] reach,
                @Nullable int[] border, @Nullable boolean[] footprint, int floorRow) {
        /** Why a block may not be searched ({@link #why}); another building's is OTHER plus its index. */
        static final int OK = 0, FAR = 1, BORDER = 2, ABOVE = 3, BELOW = 4, OTHER = 5;

        boolean allows(int x, int y, int z) {
            return why(x, y, z) == OK;
        }

        /**
         * {@link #OK} when the search may take this block, else why not: {@link #FAR} (past the
         * reach or the side of the box), {@link #BORDER}, {@link #ABOVE}, {@link #BELOW}, or
         * {@link #OTHER} plus the index of the other building's shell it is in.
         */
        int why(int x, int y, int z) {
            if (y > maxY) return ABOVE;
            if (y < minY) return BELOW;
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                boolean pastBorder = border != null
                        && (x < border[0] || z < border[1] || x > border[2] || z > border[3]);
                return pastBorder ? BORDER : FAR;
            }
            if (reach != null && !reach[column(x, z)]) return FAR;
            if (ownShell != null && inBox(ownShell, x, y, z)) return OK;
            for (int i = 0; i < otherShells.size(); i++) {
                if (inBox(otherShells.get(i), x, y, z)) return OTHER + i;
            }
            return OK;
        }

        /** Outside the columns of the detected floor and its walls; false when there is no detected floor. */
        boolean outsideFootprint(int x, int z) {
            if (footprint == null) return false;
            if (x < minX || x > maxX || z < minZ || z > maxZ) return true;
            return !footprint[column(x, z)];
        }

        /** At or below the floor under the door, outside the footprint: ground, a path, a porch floor. */
        boolean belowFloorOutside(int x, int y, int z) {
            return y <= floorRow && outsideFootprint(x, z);
        }

        private int column(int x, int z) {
            return (z - minZ) * (maxX - minX + 1) + (x - minX);
        }

        /** Box as minX, minY, minZ, maxX, maxY, maxZ, inclusive. */
        static boolean inBox(int[] box, int x, int y, int z) {
            return x >= box[0] && x <= box[3] && y >= box[1] && y <= box[4] && z >= box[2] && z <= box[5];
        }
    }

    /** What was found: the building's blocks (foundation included) and its box. */
    static final class Shape {
        static final int SIDE_CAP = 1, BLOCK_CAP = 2;

        final LongOpenHashSet blocks = new LongOpenHashSet();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        /** The cap that stopped the search ({@link #SIDE_CAP}, {@link #BLOCK_CAP}), so part may be missing; 0: none. */
        int cap;
        /**
         * Built blocks touching the building that the {@link Area} kept out, and why ({@link Area#why}).
         * Only the first block of each cut part, so the copy can say what cut it.
         */
        final Long2IntOpenHashMap cuts = new Long2IntOpenHashMap();

        boolean hitLimit() {
            return cap != 0;
        }

        void capped(int which) {
            if (cap == 0) cap = which;
        }

        boolean isEmpty() {
            return blocks.isEmpty();
        }

        boolean contains(int x, int y, int z) {
            return blocks.contains(BlockPos.asLong(x, y, z));
        }

        /** Whether taking this block keeps every side within {@link #MAX_SIDE}. */
        boolean fits(int x, int y, int z) {
            if (blocks.isEmpty()) return true;
            return Math.max(maxX, x) - Math.min(minX, x) < MAX_SIDE
                    && Math.max(maxY, y) - Math.min(minY, y) < MAX_SIDE
                    && Math.max(maxZ, z) - Math.min(minZ, z) < MAX_SIDE;
        }

        void add(int x, int y, int z) {
            blocks.add(BlockPos.asLong(x, y, z));
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
    }

    private final ServerLevel level;
    private final Area area;
    private final Long2ByteOpenHashMap kinds = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap todayKinds = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap covers = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    @Nullable private LevelChunk chunk;
    private int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;

    BuildingShapeScanner(ServerLevel level, Area area) {
        this.level = level;
        this.area = area;
    }

    // =========================================================================
    // Reading the world
    // =========================================================================

    /** The block, or null where the chunk is not loaded (never loads one). */
    @Nullable
    BlockState state(int x, int y, int z) {
        if (y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) return null;
        int cx = x >> 4, cz = z >> 4;
        if (cx != chunkX || cz != chunkZ) {
            chunk = level.getChunkSource().getChunkNow(cx, cz);
            chunkX = cx;
            chunkZ = cz;
        }
        return chunk == null ? null : chunk.getBlockState(cursor.set(x, y, z));
    }

    /**
     * The placed-or-world test a copy uses on top of today's rule (rounds 2, 4, 5, 7 of
     * docs/plans/2026-10-23-builder-copy-placed-blocks-design.md), with counts for the debug line.
     *
     * @param logs a log today's rule calls a tree's is building when stripped or standing on a building block
     */
    static final class LandTest {
        final LandView view;
        final int lowestFloor;
        final boolean trusted;
        final PlacedJudge.Rules rules;
        final boolean logs;
        /** Land blocks taken that today's rule leaves out, and lookalikes left out that it takes. */
        int placed, world;
        /** Of {@link #placed}, the ones remembered as placed. */
        int remembered;
        /** Land blocks left out as today because the land around was not trusted, that the test would have taken. */
        int untrusted;
        /** Logs today's rule calls a tree's, taken as building. */
        int buildLogs;

        LandTest(LandView view, int lowestFloor, boolean trusted, PlacedJudge.Rules rules, boolean logs) {
            this.view = view;
            this.lowestFloor = lowestFloor;
            this.trusted = trusted;
            this.rules = rules;
            this.logs = logs;
        }
    }

    @Nullable private LandTest land;

    /** From now on {@link #isBuilt} uses this test. Set it before anything asks. */
    void useLandTest(LandTest land) {
        this.land = land;
        kinds.clear();
        covers.clear();
    }

    @Nullable
    LandTest landTest() {
        return land;
    }

    /**
     * A block a player placed: today's rule ({@link #isBuiltToday}), with the copy's land test
     * over it when it has one. Unloaded counts as natural.
     */
    boolean isBuilt(int x, int y, int z) {
        if (land == null) return isBuiltToday(x, y, z);
        long key = BlockPos.asLong(x, y, z);
        byte known = kinds.get(key);
        if (known != 0) return known == BUILT;
        boolean today = isBuiltToday(x, y, z);
        boolean built = today;
        BlockState state = state(x, y, z);
        if (state != null) {
            LandView.Group group = LevelLandView.group(state);
            if (group.isLand() || group.isLookalike()) {
                PlacedJudge.Judgement judgement =
                        PlacedJudge.judge(land.view, x, y, z, land.lowestFloor, land.trusted, land.rules);
                built = switch (judgement.verdict()) {
                    case PLACED -> true;
                    case WORLD -> false;
                    case TODAY -> today;
                };
                if (built && !today) {
                    land.placed++;
                    if (judgement.reason() == PlacedJudge.Reason.REMEMBERED) land.remembered++;
                } else if (!built && today) {
                    land.world++;
                } else if (judgement.reason() == PlacedJudge.Reason.NOT_TRUSTED && !today
                        && PlacedJudge.judge(land.view, x, y, z, land.lowestFloor, true, land.rules).placed()) {
                    // Would have come had the land around been trusted: what the player's line warns about.
                    land.untrusted++;
                }
            } else if (group == LandView.Group.LOG && land.logs && !today && isBuildLog(state, x, y, z)) {
                built = true;
                land.buildLogs++;
            }
        }
        kinds.put(key, built ? BUILT : NATURAL);
        return built;
    }

    /** Today's rule (see {@link NaturalBlocks}): the copy before 10-23, and what the land test falls back on. */
    boolean isBuiltToday(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = todayKinds.get(key);
        if (known != 0) return known == BUILT;
        BlockState state = state(x, y, z);
        boolean built = state != null && switch (NaturalBlocks.kind(state)) {
            case BUILT -> true;
            case NATURAL -> false;
            case LOG -> !isTreeLog(x, y, z);
            case SANDSTONE -> !isBuriedSandstone(state.getBlock(), x, y, z);
        };
        todayKinds.put(key, built ? BUILT : NATURAL);
        return built;
    }

    /**
     * {@code copylogs}: a log is a build's, whatever leaves touch it, when no tree grows it (stripped,
     * all bark) or its column of logs stands on a building block (cobblestone, planks...: no sapling
     * grows there). The lumberjack's plain-log rule, {@code LevelTreeView.isBarkOrStripped}.
     */
    private boolean isBuildLog(BlockState state, int x, int y, int z) {
        if (LevelTreeView.isBarkOrStripped(state.getBlock())) return true;
        int bottom = y;
        for (int down = 0; down < LOG_CLIMB; down++) {
            BlockState below = state(x, bottom - 1, z);
            if (below == null) return false;
            if (!below.is(BlockTags.LOGS)) {
                return NaturalBlocks.kind(below) == NaturalBlocks.Kind.BUILT;
            }
            bottom--;
        }
        return false;
    }

    /**
     * A log is part of a tree when tree leaves touch it or any log above it in its column.
     * A log wall or pillar is capped by a roof instead, so it counts as built.
     */
    private boolean isTreeLog(int x, int y, int z) {
        for (int up = 0; up < LOG_CLIMB; up++) {
            BlockState here = state(x, y + up, z);
            if (here == null || !here.is(BlockTags.LOGS)) {
                return here != null && NaturalBlocks.isTreeLeaves(here);
            }
            if (leavesBeside(x, y + up, z)) return true;
        }
        return false;
    }

    private boolean leavesBeside(int x, int y, int z) {
        BlockState s;
        return ((s = state(x + 1, y, z)) != null && NaturalBlocks.isTreeLeaves(s))
                || ((s = state(x - 1, y, z)) != null && NaturalBlocks.isTreeLeaves(s))
                || ((s = state(x, y, z + 1)) != null && NaturalBlocks.isTreeLeaves(s))
                || ((s = state(x, y, z - 1)) != null && NaturalBlocks.isTreeLeaves(s));
    }

    /** Desert rock lies under sand; a sandstone wall has air or a roof above it. */
    private boolean isBuriedSandstone(Block sandstone, int x, int y, int z) {
        for (int up = 1; up <= SANDSTONE_CLIMB; up++) {
            BlockState above = state(x, y + up, z);
            if (above == null) return false;
            if (above.is(BlockTags.SAND)) return true;
            if (above.getBlock() != sandstone) return false;
        }
        return true;
    }

    /**
     * A built block that is part of the ground rather than a building: its column of built
     * blocks is at most {@link #COVER_HEIGHT} tall, stands on natural solid ground and has
     * open sky above (no built block for {@link #COVER_SKY}). Paths, fences, low walls and
     * open decks; a room's floor is under a roof, so it never is.
     */
    boolean isGroundCover(int x, int y, int z) {
        long key = BlockPos.asLong(x, y, z);
        byte known = covers.get(key);
        if (known != 0) return known == BUILT;
        boolean cover = groundCover(x, y, z, true);
        covers.put(key, cover ? BUILT : NATURAL);
        return cover;
    }

    /** {@code needSky}: only with open sky above (ground cover); else under a roof too (a path under the eaves). */
    private boolean groundCover(int x, int y, int z, boolean needSky) {
        int bottom = y;
        while (bottom > y - (COVER_HEIGHT - 1) && isBuilt(x, bottom - 1, z)) bottom--;
        if (isBuilt(x, bottom - 1, z)) return false;
        BlockState ground = state(x, bottom - 1, z);
        if (ground == null || !NaturalBlocks.isSolidGround(ground)) return false;
        int height = 0;
        while (height <= COVER_HEIGHT && isBuilt(x, bottom + height, z)) height++;
        if (height > COVER_HEIGHT) return false;
        if (!needSky) return true;
        for (int up = bottom + height; up < bottom + height + COVER_SKY; up++) {
            if (isBuilt(x, up, z)) return false;
        }
        return true;
    }

    // =========================================================================
    // The search
    // =========================================================================

    /**
     * The first block a player placed in this column, looking down from a little above
     * {@code hintY}; null when the first block there is natural ground or nothing.
     */
    @Nullable
    BlockPos findSeed(int x, int hintY, int z) {
        for (int y = hintY + 4; y >= hintY - 8; y--) {
            BlockState state = state(x, y, z);
            if (state == null || state.isAir()) continue;
            if (isBuilt(x, y, z) && area.allows(x, y, z)) return new BlockPos(x, y, z);
            // Plants and snow on a roof are skipped; real ground ends the look.
            if (NaturalBlocks.isSolidGround(state)) return null;
        }
        return null;
    }

    // =========================================================================
    // The detected floor
    // =========================================================================

    /**
     * The building as its detected floor shows it ({@code tiles}: the feet cells of a
     * registered building's room, every storey): the floor block under every tile, and,
     * where a roof is over the tile, the blocks where the room ends beside it from the floor
     * up to that roof, and the roof block itself. Taken whatever they are made of, so a dirt
     * floor, a stone or mud wall and a turf roof come along. Air, water, grass, flowers and
     * tree leaves never do. An open-air tile (a ranch) gives its floor only.
     */
    LongOpenHashSet frame(Set<BlockPos> tiles) {
        LongOpenHashSet frame = new LongOpenHashSet();
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (BlockPos tile : tiles) {
            int x = tile.getX(), y = tile.getY(), z = tile.getZ();
            takeFrame(frame, x, y - 1, z);
            int ceiling = ceilingAbove(x, y, z);
            if (ceiling == NO_CEILING) continue;
            takeFrame(frame, x, ceiling, z);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                int bx = x + direction.getStepX(), bz = z + direction.getStepZ();
                if (tiles.contains(beside.set(bx, y, bz))) continue;
                for (int wy = y - 1; wy <= ceiling; wy++) takeFrame(frame, bx, wy, bz);
            }
        }
        return frame;
    }

    private void takeFrame(LongOpenHashSet frame, int x, int y, int z) {
        BlockState state = state(x, y, z);
        if (state != null && isFrameBlock(state)) frame.add(BlockPos.asLong(x, y, z));
    }

    /** Solid enough to be a floor, wall or roof: built blocks, and natural ones you can't walk through. */
    private static boolean isFrameBlock(BlockState state) {
        if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.is(BlockTags.REPLACEABLE)
                || NaturalBlocks.isTreeLeaves(state)) {
            return false;
        }
        return NaturalBlocks.kind(state) != NaturalBlocks.Kind.NATURAL || state.blocksMotion();
    }

    private static final int NO_CEILING = Integer.MIN_VALUE;

    /**
     * The room scan's own roof rule ({@code RoomScanner.findCeilingY}): the first block above
     * the tile with something to bump into, ladders and vines looked through. Read without
     * loading a chunk; {@link #NO_CEILING} when there is none.
     */
    private int ceilingAbove(int x, int y, int z) {
        for (int dy = 1; dy <= RoomScanner.MAX_CEILING_DIST; dy++) {
            BlockState state = state(x, y + dy, z);
            if (state == null) return NO_CEILING;
            if (state.is(BlockTags.CLIMBABLE)) continue;
            if (state.blocksMotion()
                    || (!state.isAir() && !state.getCollisionShape(level, cursor.set(x, y + dy, z)).isEmpty())) {
                return y + dy;
            }
        }
        return NO_CEILING;
    }

    /** Spreads from the seeds and adds the foundation layer. */
    Shape scan(List<BlockPos> seeds) {
        return scan(seeds, null);
    }

    /**
     * As {@link #scan(List)}, starting from {@code frame} too: blocks taken whatever they are
     * made of (see {@link #frame}). The spread goes on from them, but only through built blocks,
     * so a dirt floor brings the bed standing on it and never the ground under it.
     */
    Shape scan(List<BlockPos> seeds, @Nullable LongOpenHashSet frame) {
        Shape shape = new Shape();
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        if (frame != null) {
            for (long key : frame) {
                if (!seen.add(key)) continue;
                int x = BlockPos.getX(key), y = BlockPos.getY(key), z = BlockPos.getZ(key);
                int why = area.why(x, y, z);
                if (why != Area.OK) {
                    shape.cuts.put(key, why);
                    continue;
                }
                if (!shape.fits(x, y, z) || shape.blocks.size() >= MAX_BLOCKS) {
                    shape.capped(shape.fits(x, y, z) ? Shape.BLOCK_CAP : Shape.SIDE_CAP);
                    continue;
                }
                shape.add(x, y, z);
                // An open-air floor (a ranch's grass) is ground cover: its fence comes, the meadow does not.
                if (!isGroundCover(x, y, z)) queue.enqueue(key);
            }
        }
        for (BlockPos seed : seeds) {
            long key = seed.asLong();
            if (!seen.add(key)) continue;
            int x = seed.getX(), y = seed.getY(), z = seed.getZ();
            if (!area.allows(x, y, z) || !isBuilt(x, y, z)) continue;
            if (!shape.fits(x, y, z)) {
                shape.capped(Shape.SIDE_CAP);
                continue;
            }
            shape.add(x, y, z);
            if (!isGroundCover(x, y, z)) queue.enqueue(key);
        }
        search:
        while (!queue.isEmpty()) {
            long key = queue.dequeueLong();
            int bx = BlockPos.getX(key), by = BlockPos.getY(key), bz = BlockPos.getZ(key);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        int x = bx + dx, y = by + dy, z = bz + dz;
                        long next = BlockPos.asLong(x, y, z);
                        if (!seen.add(next)) continue;
                        if (!isBuilt(x, y, z) || isGroundOutside(x, y, z)) continue;
                        int why = area.why(x, y, z);
                        if (why != Area.OK) {
                            // A path or fence running past a limit was never going to come: not a cut.
                            if (!isGroundCover(x, y, z)) shape.cuts.put(next, why);
                            continue;
                        }
                        if (!shape.fits(x, y, z)) {
                            shape.capped(Shape.SIDE_CAP);
                            continue;
                        }
                        if (shape.blocks.size() >= MAX_BLOCKS) {
                            shape.capped(Shape.BLOCK_CAP);
                            break search;
                        }
                        shape.add(x, y, z);
                        // A path tile at the door comes along; the path beyond it does not.
                        if (!isGroundCover(x, y, z)) queue.enqueue(next);
                    }
                }
            }
        }
        addFoundation(shape);
        return shape;
    }

    /**
     * Never part of a building with a detected floor, outside its footprint: anything at or
     * below the floor under the door (paths, yard paving, porch floors), and anything at most
     * {@link #COVER_HEIGHT} tall standing on natural ground, roof above or not (a path up a
     * slope, slabs laid under the eaves, a garden fence). Walls, roofs, eaves and taller posts
     * still come.
     */
    private boolean isGroundOutside(int x, int y, int z) {
        if (!area.outsideFootprint(x, z)) return false;
        return y <= area.floorRow() || groundCover(x, y, z, false);
    }

    /**
     * One layer of solid ground under the lowest block of every column, so the copy has a footing.
     * Outside the footprint only a column standing right on the floor row gets one ({@code copyfooting}):
     * a wall no room tile reaches, like the one beside a campfire, which no villager can stand on.
     * Only walls and posts taller than ground cover are kept there ({@link #isGroundOutside}).
     */
    private void addFoundation(Shape shape) {
        if (shape.isEmpty()) return;
        Long2IntOpenHashMap lowest = new Long2IntOpenHashMap();
        lowest.defaultReturnValue(Integer.MAX_VALUE);
        for (long key : shape.blocks) {
            long column = BlockPos.asLong(BlockPos.getX(key), 0, BlockPos.getZ(key));
            lowest.put(column, Math.min(lowest.get(column), BlockPos.getY(key)));
        }
        for (Long2IntOpenHashMap.Entry entry : lowest.long2IntEntrySet()) {
            int x = BlockPos.getX(entry.getLongKey()), z = BlockPos.getZ(entry.getLongKey());
            int y = entry.getIntValue() - 1;
            boolean wallFooting = WalkFeel.copyFooting && y == area.floorRow();
            if (!area.allows(x, y, z) || (area.belowFloorOutside(x, y, z) && !wallFooting) || !shape.fits(x, y, z)) {
                continue;
            }
            BlockState below = state(x, y, z);
            if (below != null && NaturalBlocks.isSolidGround(below)) shape.add(x, y, z);
        }
    }
}
