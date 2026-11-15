package com.alex.test.village.raid;

import com.alex.test.village.NavGraph;
import com.alex.test.village.NavReachability;
import com.alex.test.village.RouteRules;
import com.alex.test.village.VillageManager;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.building.Building;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * A raid's camp outside the village: where it goes, the few blocks it puts
 * up (a campfire whose smoke is the warning, two canopy tents, the ominous
 * banner on a post), and taking them down again.
 *
 * <p>Blocks only ever go where there was air or a plant, and come down only
 * while they are still what the camp put there: a player's block is never
 * replaced or removed.
 */
public final class RaidCamp {

    /** Blocks out from the edge of the built-up part of the village. */
    public static final int MIN_OUT = 40;
    public static final int MAX_OUT = 70;
    /** Closer in, for when nothing far enough out is loaded (a small view distance). */
    public static final int NEAR_MIN_OUT = 16;
    public static final int NEAR_MAX_OUT = 39;
    private static final int ATTEMPTS = 32;
    /**
     * Room kept between a camp and the nearest player build, in blocks (square
     * distance): the camp takes the spot with the most, 10 when it can, else
     * 9, 8, 7, and never a spot with a build 6 or closer.
     */
    private static final int CLEAR_BEST = 10;
    private static final int CLEAR_MIN = 6;

    private RaidCamp() {}

    /**
     * A camp spot; {@code tents} when the ground is flat, natural and clear enough to build on;
     * {@code joined} when it is on the village map and the map walks from it to a building door
     * (only looked at with switch {@code raidcampin}).
     */
    public record Spot(BlockPos pos, boolean tents, boolean joined) {}
    /** Doors a spot is tried against for {@code joined}: the ones nearest the middle of the village. */
    private static final int JOIN_DOORS = 4;
    /**
     * Bug B5 ({@code raidjoinrules}): a joined spot is also asked of an illager's own rules (no ladder, no
     * ranch gate), this many route searches a pick of at most this many tiles each; then the quick answer.
     */
    private static final int JOIN_RULE_CHECKS = 3;
    private static final int JOIN_RULE_TILES = 20000;

    /** One block the camp placed. */
    public record Placed(BlockPos pos, Block block) {}

    /** The middle of the built-up part of the village (its building doors), or null for a village with none. */
    @Nullable
    public static BlockPos builtCentre(VillageManager village) {
        int[] box = builtBox(village);
        if (box == null) return null;
        return new BlockPos((box[0] + box[2]) / 2, box[4], (box[1] + box[3]) / 2);
    }

    /** {minX, minZ, maxX, maxZ, y} over every building door, or null. */
    @Nullable
    private static int[] builtBox(VillageManager village) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        long ySum = 0;
        int count = 0;
        for (Building building : village.getBuildings()) {
            BlockPos door = building.getDoorPos();
            if (door == null) continue;
            minX = Math.min(minX, door.getX());
            minZ = Math.min(minZ, door.getZ());
            maxX = Math.max(maxX, door.getX());
            maxZ = Math.max(maxZ, door.getZ());
            ySum += door.getY();
            count++;
        }
        if (count == 0) return null;
        return new int[]{minX, minZ, maxX, maxZ, (int) (ySum / count)};
    }

    /**
     * The best spot towards {@code angle} (radians, 0 = east, π/2 = south):
     * flat clear ground inside the village border first, then flat clear
     * ground anywhere, then any dry standing spot. Only spots whose chunk is
     * ticking entities count, so the raiders there actually move. Null when
     * none was found this time.
     *
     * <p>Round 3 of docs/plans/2026-10-23-raider-pathfinding.md (switch {@code raidcampin}): a spot
     * whose ground joins the village (on its map, a map walk from it to a door) beats every other,
     * flat before uneven; none joined: the choice above. Before, a camp could go up across a river,
     * a ravine or a cliff from the village, and its raiders never found the way over.
     */
    @Nullable
    public static Spot findSpot(ServerLevel level, VillageManager village, double angle, int minOut, int maxOut) {
        return findSpot(level, village, angle, minOut, maxOut, false);
    }

    /** {@link #findSpot(ServerLevel, VillageManager, double, int, int)}, only inside the border when {@code insideOnly}. */
    @Nullable
    public static Spot findSpot(ServerLevel level, VillageManager village, double angle, int minOut, int maxOut,
                                boolean insideOnly) {
        int[] box = builtBox(village);
        if (box == null) return null;
        double cx = (box[0] + box[2]) / 2.0;
        double cz = (box[1] + box[3]) / 2.0;
        double halfDiagonal = 0.5 * Math.hypot(box[2] - box[0], box[3] - box[1]);
        boolean campIn = WalkFeel.raidCampIn;
        List<BlockPos> joinDoors = campIn ? joinDoors(village, cx, cz) : List.of();
        // Bug B5: illagers' rules (most raids are theirs; a door is only a start, so the dead are no different).
        NavGraph graph = village.getGraph();
        RouteRules joinRules = campIn && WalkFeel.raidJoinRules && graph != null
                ? RouteRules.raider(village, graph, true) : null;
        int[] ruleChecks = {JOIN_RULE_CHECKS};
        Spot fallback = null;
        Spot best = null;
        int bestRoom = 0;
        boolean bestInside = false;
        Spot joinedFallback = null;
        Spot joinedBest = null;
        int joinedRoom = 0;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double a = angle + (level.random.nextDouble() - 0.5) * (Math.PI / 2);
            double d = halfDiagonal + minOut + level.random.nextInt(Math.max(1, maxOut - minOut + 1));
            int x = (int) Math.floor(cx + Math.cos(a) * d);
            int z = (int) Math.floor(cz + Math.sin(a) * d);
            BlockPos probe = new BlockPos(x, box[4], z);
            if (!level.hasChunk(x >> 4, z >> 4) || !level.isPositionEntityTicking(probe)) continue;
            // Never past the world border (the war chest out of reach), nor in a neighbouring village.
            if (!level.getWorldBorder().isWithinBounds(probe)) continue;
            VillageManager other = com.alex.test.village.VillageManagerRegistry.get(level.getServer()).findVillageAt(level, probe);
            if (other != null && other != village) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            if (!level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()) continue;
            if (RaidSpawner.standingSpot(level, pos) == null) continue;
            if (insideOnly && !village.contains(pos)) continue;
            boolean flat = isFlat(level, pos) && isNatural(level.getBlockState(pos.below()));
            boolean joined = campIn && joinsVillage(village, pos, joinDoors);
            // An uneven spot is only ever a fallback, and one is enough (one of each with raidcampin).
            if (!flat && (joined ? joinedFallback : fallback) != null) continue;
            int room = buildDistance(level, pos);
            // Somebody's build within the least room kept: no camp there at all.
            if (room <= CLEAR_MIN) continue;
            // Bug B5: joined by villager rules; asked of the raiders' own too (a few searches a pick).
            if (joined && joinRules != null) joined = raiderJoins(graph, joinRules, pos, joinDoors, ruleChecks);
            if (!flat) {
                if (joined) joinedFallback = new Spot(pos, false, true);
                else fallback = new Spot(pos, false, false);
                continue;
            }
            if (joined) {
                // Joined is inside the border too: full room is none better, else the most room.
                if (room > CLEAR_BEST) return new Spot(pos, true, true);
                if (joinedBest == null || room > joinedRoom) {
                    joinedBest = new Spot(pos, true, true);
                    joinedRoom = room;
                }
                continue;
            }
            boolean inside = village.contains(pos);
            // Full room inside the border: none better (with raidcampin only a joined one is).
            if (!campIn && room > CLEAR_BEST && inside) return new Spot(pos, true, false);
            // Else the most room from builds (10 blocks, else 9, 8, 7), then inside the border.
            if (best == null || room > bestRoom || (room == bestRoom && inside && !bestInside)) {
                best = new Spot(pos, true, false);
                bestRoom = room;
                bestInside = inside;
            }
        }
        if (joinedBest != null) return joinedBest;
        if (joinedFallback != null) return joinedFallback;
        return best != null ? best : fallback;
    }

    /** Round 3: the building doors nearest the middle of the village ({@code cx}, {@code cz}), at most {@link #JOIN_DOORS}. */
    private static List<BlockPos> joinDoors(VillageManager village, double cx, double cz) {
        List<BlockPos> doors = new ArrayList<>();
        for (Building building : village.getBuildings()) {
            BlockPos door = building.getDoorPos();
            if (door != null) doors.add(door);
        }
        doors.sort(java.util.Comparator.comparingDouble((BlockPos door) -> {
            double dx = door.getX() - cx, dz = door.getZ() - cz;
            return dx * dx + dz * dz;
        }));
        return doors.size() > JOIN_DOORS ? new ArrayList<>(doors.subList(0, JOIN_DOORS)) : doors;
    }

    /**
     * Round 3: {@code pos} is on the village map and the map walks from it to one of {@code doors}
     * (the piece map's answer, {@link NavReachability}: no route search).
     */
    private static boolean joinsVillage(VillageManager village, BlockPos pos, List<BlockPos> doors) {
        NavGraph graph = village.getGraph();
        if (graph == null || doors.isEmpty() || !village.contains(pos)) return false;
        for (BlockPos door : doors) {
            if (NavReachability.canReach(graph, pos, door)) return true;
        }
        return false;
    }

    /**
     * Bug B5 (10-23): of the doors the map's quick answer joins {@code pos} to, one a raider's route
     * reaches too ({@link RaiderMapWalk#raiderReaches}). Searches are taken from {@code checks}; none
     * left: the quick answer stands.
     */
    private static boolean raiderJoins(NavGraph graph, RouteRules rules, BlockPos pos, List<BlockPos> doors, int[] checks) {
        for (BlockPos door : doors) {
            if (!NavReachability.canReach(graph, pos, door)) continue;
            if (checks[0] <= 0) return true;
            checks[0]--;
            if (RaiderMapWalk.raiderReaches(graph, rules, pos, door, JOIN_RULE_TILES)) return true;
        }
        return false;
    }

    /** Puts the camp up at {@code spot}; returns every block placed, in order. */
    public static List<Placed> build(ServerLevel level, Spot spot, boolean riders) {
        List<Placed> placed = new ArrayList<>();
        BlockPos c = spot.pos();
        place(level, c, Blocks.CAMPFIRE.defaultBlockState(), placed);
        // The war chest: where looters put what they carry home.
        if (!place(level, c.offset(-2, 0, 0), Blocks.CHEST.defaultBlockState(), placed)) {
            place(level, c.offset(0, 0, 2), Blocks.CHEST.defaultBlockState(), placed);
        }
        if (riders) {
            // Riders: no tents, a skull on a post.
            BlockPos post = c.offset(2, 0, 0);
            if (place(level, post, Blocks.SPRUCE_FENCE.defaultBlockState(), placed)) {
                place(level, post.above(), Blocks.SKELETON_SKULL.defaultBlockState(), placed);
            }
            return placed;
        }
        if (!spot.tents()) return placed;
        // The ominous banner on a post, two blocks from the fire.
        BlockPos post = c.offset(2, 0, 0);
        if (place(level, post, Blocks.DARK_OAK_FENCE.defaultBlockState(), placed)
                && place(level, post.above(), Blocks.WHITE_BANNER.defaultBlockState(), placed)
                && level.getBlockEntity(post.above()) instanceof BannerBlockEntity banner) {
            banner.fromItem(Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)),
                    DyeColor.WHITE);
            banner.setChanged();
            level.sendBlockUpdated(post.above(), banner.getBlockState(), banner.getBlockState(), 3);
        }
        tent(level, c.offset(-5, 0, 2), placed);
        tent(level, c.offset(2, 0, -5), placed);
        return placed;
    }

    /**
     * The camp's war chest, if it still stands in the list of placed blocks.
     * The newest one: an old camp's chest left in an unloaded chunk stays at
     * the front of the list until it can be taken down.
     */
    @Nullable
    public static BlockPos warChest(List<Placed> placed) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            if (placed.get(i).block() == Blocks.CHEST) return placed.get(i).pos();
        }
        return null;
    }

    /** Four posts two blocks high and a 3 by 3 wool roof on them. */
    private static void tent(ServerLevel level, BlockPos corner, List<Placed> placed) {
        BlockState post = Blocks.DARK_OAK_FENCE.defaultBlockState();
        BlockState cloth = Blocks.GRAY_WOOL.defaultBlockState();
        int[][] corners = {{0, 0}, {2, 0}, {0, 2}, {2, 2}};
        for (int[] at : corners) {
            BlockPos base = corner.offset(at[0], 0, at[1]);
            if (place(level, base, post, placed)) place(level, base.above(), post, placed);
        }
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                place(level, corner.offset(dx, 2, dz), cloth, placed);
            }
        }
    }

    private static boolean place(ServerLevel level, BlockPos pos, BlockState state, List<Placed> placed) {
        BlockState there = level.getBlockState(pos);
        if (!there.canBeReplaced() || !level.getFluidState(pos).isEmpty()) return false;
        if (!level.setBlock(pos, state, 3)) return false;
        placed.add(new Placed(pos.immutable(), state.getBlock()));
        return true;
    }

    /**
     * Takes down every placed block that is still what the camp put there,
     * last first. Blocks in chunks that are not loaded stay in the list for
     * later; the rest are dropped from it. True when nothing is left.
     */
    public static boolean takeDown(ServerLevel level, List<Placed> placed) {
        for (int i = placed.size() - 1; i >= 0; i--) {
            Placed block = placed.get(i);
            if (!level.hasChunk(block.pos().getX() >> 4, block.pos().getZ() >> 4)) continue;
            if (level.getBlockState(block.pos()).is(block.block())) {
                // What is left in the war chest leaves with the camp, rather than spilling on the ground.
                if (level.getBlockEntity(block.pos()) instanceof net.minecraft.world.Container container) {
                    container.clearContent();
                }
                level.setBlock(block.pos(), Blocks.AIR.defaultBlockState(), 3);
            }
            placed.remove(i);
        }
        return placed.isEmpty();
    }

    private static boolean isFlat(ServerLevel level, BlockPos centre) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre.getX() + dx, centre.getZ() + dz);
                if (Math.abs(y - centre.getY()) > 1) return false;
            }
        }
        return true;
    }

    private static boolean isNatural(BlockState ground) {
        return ground.is(BlockTags.DIRT) || ground.is(BlockTags.SAND) || ground.is(Blocks.GRAVEL)
                || ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.SNOW) || ground.is(BlockTags.BASE_STONE_OVERWORLD)
                || ground.is(BlockTags.TERRACOTTA) || ground.is(Blocks.CLAY);
    }

    /**
     * How far the nearest player build is from {@code centre} (square
     * distance, 0 to {@link #CLEAR_BEST}), or {@code CLEAR_BEST + 1} when
     * there is none that close. Looked at ring by ring from the middle, so a
     * spot next to a build is turned down after a few blocks.
     */
    private static int buildDistance(ServerLevel level, BlockPos centre) {
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int r = 0; r <= CLEAR_BEST; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -1; dy <= 3; dy++) {
                        at.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                        if (isBuild(level.getBlockState(at))) return r;
                    }
                }
            }
        }
        return CLEAR_BEST + 1;
    }

    /** Not nature (air, plants, leaves, logs, ground): somebody built it. */
    private static boolean isBuild(BlockState state) {
        if (state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()) return false;
        if (state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || isNatural(state)) return false;
        if (state.is(BlockTags.FLOWERS) || state.is(BlockTags.SAPLINGS) || state.is(Blocks.ICE)) return false;
        return !(state.is(Blocks.PUMPKIN) || state.is(Blocks.MELON) || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.BAMBOO) || state.is(Blocks.SWEET_BERRY_BUSH));
    }

    // -------------------------------------------------------------------------
    // Saving
    // -------------------------------------------------------------------------

    public static ListTag save(List<Placed> placed) {
        ListTag list = new ListTag();
        for (Placed block : placed) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("pos", block.pos().asLong());
            tag.putString("block", BuiltInRegistries.BLOCK.getKey(block.block()).toString());
            list.add(tag);
        }
        return list;
    }

    public static List<Placed> load(CompoundTag parent, String key) {
        List<Placed> placed = new ArrayList<>();
        if (!parent.contains(key, Tag.TAG_LIST)) return placed;
        ListTag list = parent.getList(key, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("block"));
            if (id == null) continue;
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block == Blocks.AIR) continue;
            placed.add(new Placed(BlockPos.of(tag.getLong("pos")), block));
        }
        return placed;
    }
}
