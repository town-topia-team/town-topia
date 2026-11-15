package com.alex.test.village.villagers.builder.copy;

import com.alex.test.DynamicVillages;
import com.alex.test.block.PianoLock;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.building.RoomScanner;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Copies a building of the village into a vanilla structure file (the bytes of a
 * {@code .nbt}), for the Builder's desk. Server only; the desk saves the file on the
 * player's computer.
 *
 * <p>The outline comes from {@link BuildingShapeScanner}. A registered building with a room
 * starts from the floor its room scan found, with the walls and roof around it
 * ({@link BuildingShapeScanner#frame}); a mine, or a building whose room is gone right now,
 * from every built block in the box around its rooms. Inside the outline's box:
 * <ul>
 *   <li>the building's blocks are saved as they are (chest contents and loot left out);</li>
 *   <li>air inside the building (its rooms, and any space the walls close in) is saved
 *       as air, so a copy that is built clears its rooms;</li>
 *   <li>everything else is left out, which the structure format reads as "leave the
 *       world block alone", so building a copy never flattens trees or neighbours.</li>
 * </ul>
 * With a detected floor, nothing outside the floor and its walls at or below the floor under the
 * door is part of the building, so paths and yard paving stay out. What the search was kept from
 * comes back as one line for the player ({@link Result#cut}).
 * The player can grow or shrink each side of the box ({@code grow}): a grown side takes
 * every block in the new rows (ground included), a shrunk side cuts the building off.
 *
 * <p>Entities are never copied: item frames would duplicate job tokens.
 */
public final class BuildingCopier {
    private BuildingCopier() {}

    /** Grow order: west (-X), east (+X), down (-Y), up (+Y), north (-Z), south (+Z). */
    public static final int SIDES = 6;
    /** Name of the extra tag in the file; other tools ignore tags they do not know. */
    public static final String INFO_TAG = BlueprintReader.COPY_TAG;
    /** How far past its floor tiles (sideways; its room box when it has none) a building's outline may reach: eaves, porches. */
    static final int OWN_REACH = 6;

    /**
     * The copy, or why there is none. {@code origin} is the box's low corner in the world;
     * {@code grow} is what was applied after clamping. {@code cut}: one short line on what kept
     * part of the building out of the copy, "" when nothing did.
     */
    public record Result(@Nullable String error, BlockPos origin, int sizeX, int sizeY, int sizeZ, int blocks,
                         String cut, boolean noFloor, String label, int[] grow, byte[] bytes) {
        static Result failed(String error) {
            return new Result(error, BlockPos.ZERO, 0, 0, 0, 0, "", false, "", new int[SIDES], new byte[0]);
        }
    }

    /**
     * Copies the building with this frame id, or the building under {@code pick} (a column
     * the player clicked on the village map, y = the top they saw). Every copy leaves one
     * debug line, a failed one too.
     */
    public static Result copy(ServerLevel level, VillageManager village, @Nullable UUID buildingId,
                              @Nullable BlockPos pick, int[] grow) {
        long started = System.nanoTime();
        Result result = copyBuilding(level, village, buildingId, pick, grow, started);
        if (result.error() != null) {
            DynamicVillages.LOGGER.debug("BUILDER copy of {} failed after {} ms: {}",
                    buildingId != null ? "building " + buildingId : "the click at " + (pick == null ? "nothing" : pick.toShortString()),
                    (System.nanoTime() - started) / 1_000_000, result.error());
        }
        return result;
    }

    /** The answer when the player picks one of the buildings that are never copied. */
    public static final String NOT_COPYABLE = "Mines, watchtowers and guard posts can't be copied.";

    /** Mines and watchtowers are never copied (user 10-23); guard posts are no buildings, see {@link #copyBuilding}. */
    public static boolean copyable(String buildingClassName) {
        return !buildingClassName.equals(MineBuilding.class.getSimpleName())
                && !buildingClassName.equals(WatchtowerBuilding.class.getSimpleName());
    }

    private static Result copyBuilding(ServerLevel level, VillageManager village, @Nullable UUID buildingId,
                                       @Nullable BlockPos pick, int[] grow, long started) {
        Building building = null;
        if (buildingId != null) {
            for (Building b : village.getBuildings()) {
                if (buildingId.equals(b.getItemFrameId())) building = b;
            }
            if (building == null) return Result.failed("That building is gone. Pick another one.");
            if (!copyable(building.getClass().getSimpleName())) return Result.failed(NOT_COPYABLE);
        }
        if (building == null && pick == null) return Result.failed("Pick a building first.");

        // A click inside a registered building copies that building, with its name and rooms.
        BlockPos seed = null;
        if (building == null) {
            // Finding the clicked block: every building counts, the click may be on any roof.
            BuildingShapeScanner finder = new BuildingShapeScanner(level, area(level, village, null, pick, false, null));
            seed = finder.findSeed(pick.getX(), pick.getY(), pick.getZ());
            if (seed == null) {
                return Result.failed("That looks like natural ground. Click a wall or a roof.");
            }
            for (Building b : village.getBuildings()) {
                if (BuildingShapeScanner.Area.inBox(roofBox(b), seed.getX(), seed.getY(), seed.getZ())) building = b;
            }
            if (building != null && !copyable(building.getClass().getSimpleName())) return Result.failed(NOT_COPYABLE);
            // A guard post is a token in a frame, not a building: a click on (or right by) one copies nothing.
            if (building == null && com.alex.test.village.villagers.guard.behavior.GuardPostRegistry.get(level)
                    .nearestPostDistanceSqr(level, village, seed) <= 4) {
                return Result.failed(NOT_COPYABLE);
            }
        }

        BlockPos centre = seed;
        if (building != null) {
            BlockPos lo = building.shellMin(), hi = building.shellMax();
            centre = new BlockPos((lo.getX() + hi.getX()) / 2, (lo.getY() + hi.getY()) / 2, (lo.getZ() + hi.getZ()) / 2);
        }
        // A registered building starts from the floor its room scan found, not from the box
        // around it. Not a mine: its floor runs on into the mountain.
        Set<BlockPos> tiles = building != null && !(building instanceof MineBuilding)
                ? building.getNavMeshBlocks() : Set.of();
        boolean byFloor = !tiles.isEmpty();
        boolean noFloor = building != null && !byFloor && !(building instanceof MineBuilding);
        BuildingShapeScanner.Area area = area(level, village, building, centre, true, byFloor ? tiles : null);
        BuildingShapeScanner scanner = new BuildingShapeScanner(level, area);
        // Stone, dirt or sand in a wall above the floor: placed or the world's? (10-23; never a mine)
        CopyLand.Setup land = byFloor ? CopyLand.prepare(level, village, building, tiles, scanner, area) : null;
        if (land != null) scanner.useLandTest(land.test());
        List<BlockPos> seeds = new ArrayList<>();
        LongOpenHashSet frame = null;
        LongOpenHashSet roomAir = new LongOpenHashSet();
        if (byFloor) {
            frame = scanner.frame(tiles);
        } else if (building != null) {
            BlockPos lo = building.shellMin(), hi = building.shellMax();
            for (BlockPos pos : BlockPos.betweenClosed(lo, hi)) {
                if (scanner.isBuilt(pos.getX(), pos.getY(), pos.getZ())) seeds.add(pos.immutable());
            }
        }
        if (building != null) {
            for (BlockPos feet : building.getNavMeshBlocks()) {
                for (int up = 0; up <= RoomScanner.MAX_CEILING_DIST; up++) {
                    BlockState state = scanner.state(feet.getX(), feet.getY() + up, feet.getZ());
                    if (state == null) break;
                    // A carpet or a shut floor trapdoor lies on the floor; the room's air starts above it.
                    if (up == 0 && (state.getBlock() instanceof CarpetBlock || state.getBlock() instanceof TrapDoorBlock)) {
                        continue;
                    }
                    if (!state.isAir()) break;
                    roomAir.add(BlockPos.asLong(feet.getX(), feet.getY() + up, feet.getZ()));
                }
            }
        }
        // The floor decides what the building is; a click on its box adds nothing of its own.
        if (seed != null && !byFloor) seeds.add(seed);
        BuildingShapeScanner.Shape shape = scanner.scan(seeds, frame);
        if (shape.isEmpty()) {
            return Result.failed("No building blocks were found there. Click a wall or a roof.");
        }

        int[] g = clampGrow(shape, grow);
        int x0 = shape.minX - g[0], x1 = shape.maxX + g[1];
        int y0 = shape.minY - g[2], y1 = shape.maxY + g[3];
        int z0 = shape.minZ - g[4], z1 = shape.maxZ + g[5];
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        BitSet outside = outsideAir(shape);

        Map<BlockState, Integer> palette = new HashMap<>();
        ListTag paletteTag = new ListTag();
        ListTag blocks = new ListTag();
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState state = scanner.state(x, y, z);
                    if (state == null) continue;
                    // ZalithYT's Piano is never copied: only its owners may have one (PianoLock).
                    if (PianoLock.isLocked(state)) state = Blocks.AIR.defaultBlockState();
                    boolean inShapeBox = x >= shape.minX && x <= shape.maxX && y >= shape.minY && y <= shape.maxY
                            && z >= shape.minZ && z <= shape.maxZ;
                    boolean take;
                    if (shape.contains(x, y, z)) {
                        take = true;
                    } else if (inShapeBox) {
                        // Closed in by the walls, or a room the building knows: part of the inside.
                        take = !outside.get(shapeIndex(shape, x, y, z)) || roomAir.contains(BlockPos.asLong(x, y, z));
                    } else {
                        // A row the player added: every block in it, but no air, water or other buildings.
                        take = !state.isAir() && !(state.getBlock() instanceof LiquidBlock) && area.allows(x, y, z);
                    }
                    if (!take) continue;
                    if (++count > BlueprintLimits.MAX_NON_VOID) {
                        return Result.failed("This copy has too many blocks. Make the box smaller.");
                    }
                    Integer index = palette.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.put(state, index);
                        paletteTag.add(NbtUtils.writeBlockState(state));
                    }
                    CompoundTag block = new CompoundTag();
                    block.put("pos", ints(x - x0, y - y0, z - z0));
                    block.putInt("state", index);
                    if (state.hasBlockEntity()) {
                        BlockEntity entity = level.getBlockEntity(pos.set(x, y, z));
                        if (entity != null) {
                            CompoundTag nbt = entity.saveWithId(level.registryAccess());
                            nbt.remove("x");
                            nbt.remove("y");
                            nbt.remove("z");
                            BlueprintReader.stripContents(nbt);
                            block.put("nbt", nbt);
                        }
                    }
                    blocks.add(block);
                }
            }
        }
        if (count == 0) return Result.failed("Nothing is left in the box. Make it bigger.");
        if (paletteTag.size() > BlueprintLimits.MAX_PALETTE) {
            return Result.failed("This copy uses too many different blocks. Make the box smaller.");
        }

        String label = building != null ? building.getDisplayName() : "Building";
        CompoundTag root = new CompoundTag();
        root.put("blocks", blocks);
        root.put("palette", paletteTag);
        root.put("entities", new ListTag());
        root.put("size", ints(sx, sy, sz));
        root.put(INFO_TAG, info(building, label, x0, y0, z0, sy));
        NbtUtils.addCurrentDataVersion(root);

        byte[] bytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            bytes = out.toByteArray();
        } catch (IOException e) {
            return Result.failed("The copy could not be packed.");
        }
        if (bytes.length > BlueprintLimits.MAX_FILE_BYTES) {
            return Result.failed("This copy is too big to save. Make the box smaller.");
        }
        String cut = withLine(cutLine(shape, area, building != null), CopyLand.untrustedLine(land));
        DynamicVillages.LOGGER.debug("BUILDER copy of {}: {} blocks in {}x{}x{}, {} KB, {} ms{}{}", label, count,
                sx, sy, sz, bytes.length / 1024, (System.nanoTime() - started) / 1_000_000, cutCounts(shape, area),
                CopyLand.summary(land));
        return new Result(null, new BlockPos(x0, y0, z0), sx, sy, sz, count, cut, noFloor, label, g, bytes);
    }

    /** Fewest blocks of one kind of cut worth telling the player about: a lone lantern past the reach is not. */
    private static final int MIN_CUT = 3;
    private static final String[] CUT_SIDES = {"West", "East", "North", "South"};

    /**
     * The blocks the search was kept from, counted per group: why ({@link BuildingShapeScanner.Area#why})
     * times 8, plus the side (west, east, north, south, seen from the middle of the copy) for cuts at
     * the sides of the area.
     */
    private static Int2IntOpenHashMap cutGroups(BuildingShapeScanner.Shape shape) {
        Int2IntOpenHashMap groups = new Int2IntOpenHashMap();
        double cx = (shape.minX + shape.maxX) / 2.0, cz = (shape.minZ + shape.maxZ) / 2.0;
        for (Long2IntMap.Entry cut : shape.cuts.long2IntEntrySet()) {
            int why = cut.getIntValue();
            int side = 0;
            if (why == BuildingShapeScanner.Area.FAR || why == BuildingShapeScanner.Area.BORDER) {
                double dx = BlockPos.getX(cut.getLongKey()) - cx, dz = BlockPos.getZ(cut.getLongKey()) - cz;
                side = Math.abs(dx) >= Math.abs(dz) ? (dx < 0 ? 0 : 1) : (dz < 0 ? 2 : 3);
            }
            groups.addTo(why * 8 + side, 1);
        }
        return groups;
    }

    /**
     * One short line for the player on what kept part of the building out, or "" when nothing worth
     * telling did: a cap first, else the biggest group of blocks kept out. {@code known}: a registered
     * building, whose limits are measured from its rooms (a clicked one's from the click).
     */
    private static String cutLine(BuildingShapeScanner.Shape shape, BuildingShapeScanner.Area area, boolean known) {
        if (shape.cap == BuildingShapeScanner.Shape.BLOCK_CAP) {
            return String.format(Locale.ROOT, "Hit the %,d block limit: part may be missing.",
                    BuildingShapeScanner.MAX_BLOCKS);
        }
        if (shape.cap == BuildingShapeScanner.Shape.SIDE_CAP) {
            return "Hit the " + BuildingShapeScanner.MAX_SIDE + " block side limit: part may be missing.";
        }
        int best = -1, most = MIN_CUT - 1;
        for (Int2IntMap.Entry group : cutGroups(shape).int2IntEntrySet()) {
            if (group.getIntValue() > most) {
                best = group.getIntKey();
                most = group.getIntValue();
            }
        }
        if (best < 0) return "";
        int why = best / 8, side = best % 8;
        String from = known ? "the rooms" : "where you clicked";
        return switch (why) {
            case BuildingShapeScanner.Area.FAR -> CUT_SIDES[side] + " part left out: too far from " + from + ".";
            case BuildingShapeScanner.Area.BORDER -> CUT_SIDES[side] + " part left out: past the village border.";
            case BuildingShapeScanner.Area.ABOVE -> "Top cut off: too high above " + from + ".";
            case BuildingShapeScanner.Area.BELOW -> "Bottom cut off: too deep under " + from + ".";
            default -> "Left out where the " + area.otherNames().get(why - BuildingShapeScanner.Area.OTHER) + " starts.";
        };
    }

    /** The player's line plus one more, kept within the 256 characters the desk sends; the first wins. */
    private static String withLine(String line, String more) {
        if (more.isEmpty()) return line;
        if (line.isEmpty()) return more;
        String both = line + " " + more;
        return both.length() <= 256 ? both : line;
    }

    /** Every cut for the log, like " (left out: far East 12, in Storage Room 4)"; "" when none. */
    private static String cutCounts(BuildingShapeScanner.Shape shape, BuildingShapeScanner.Area area) {
        List<String> parts = new ArrayList<>();
        if (shape.cap == BuildingShapeScanner.Shape.BLOCK_CAP) parts.add("block cap");
        if (shape.cap == BuildingShapeScanner.Shape.SIDE_CAP) parts.add("side cap");
        for (Int2IntMap.Entry group : cutGroups(shape).int2IntEntrySet()) {
            int why = group.getIntKey() / 8, side = group.getIntKey() % 8;
            String kind = switch (why) {
                case BuildingShapeScanner.Area.FAR -> "far " + CUT_SIDES[side];
                case BuildingShapeScanner.Area.BORDER -> "border " + CUT_SIDES[side];
                case BuildingShapeScanner.Area.ABOVE -> "above";
                case BuildingShapeScanner.Area.BELOW -> "below";
                default -> "in " + area.otherNames().get(why - BuildingShapeScanner.Area.OTHER);
            };
            parts.add(kind + " " + group.getIntValue());
        }
        return parts.isEmpty() ? "" : " (left out: " + String.join(", ", parts) + ")";
    }

    /** A box copy is at most this many blocks each way. */
    public static final int MAX_BOX_AXIS = 48;

    /**
     * Copies every block between two corners the player clicked, ground and air too,
     * sides grown or shrunk by {@code grow} (west, east, bottom, top, north, south).
     */
    public static Result copyBox(ServerLevel level, VillageManager village, BlockPos a, BlockPos b, int[] grow) {
        int[] g = java.util.Arrays.copyOf(grow, SIDES);
        int x0 = Math.min(a.getX(), b.getX()) - g[0], x1 = Math.max(a.getX(), b.getX()) + g[1];
        int y0 = Math.min(a.getY(), b.getY()) - g[2], y1 = Math.max(a.getY(), b.getY()) + g[3];
        int z0 = Math.min(a.getZ(), b.getZ()) - g[4], z1 = Math.max(a.getZ(), b.getZ()) + g[5];
        if (x1 < x0 || y1 < y0 || z1 < z0) return Result.failed("The box is too small.");
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        if (sx > MAX_BOX_AXIS || sy > MAX_BOX_AXIS || sz > MAX_BOX_AXIS) {
            return Result.failed("The box is too big: at most " + MAX_BOX_AXIS + " blocks each way.");
        }
        if (y0 < level.getMinBuildHeight() || y1 >= level.getMaxBuildHeight()) {
            return Result.failed("The box goes out of the world.");
        }
        AABB border = village.borderBounds();
        if (border == null || x0 < Math.floor(border.minX) || x1 > Math.floor(border.maxX)
                || z0 < Math.floor(border.minZ) || z1 > Math.floor(border.maxZ)) {
            return Result.failed("The box must stay inside the village border.");
        }
        for (int cx = x0 >> 4; cx <= x1 >> 4; cx++) {
            for (int cz = z0 >> 4; cz <= z1 >> 4; cz++) {
                if (!level.hasChunk(cx, cz)) return Result.failed("Part of the box is not loaded. Go closer.");
            }
        }
        Map<BlockState, Integer> palette = new HashMap<>();
        ListTag paletteTag = new ListTag();
        ListTag blocks = new ListTag();
        int count = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    BlockState state = level.getBlockState(pos.set(x, y, z));
                    if (state.getBlock() instanceof LiquidBlock) continue;
                    if (PianoLock.isLocked(state)) state = Blocks.AIR.defaultBlockState();
                    count++;
                    Integer index = palette.get(state);
                    if (index == null) {
                        index = palette.size();
                        palette.put(state, index);
                        paletteTag.add(NbtUtils.writeBlockState(state));
                    }
                    CompoundTag block = new CompoundTag();
                    block.put("pos", ints(x - x0, y - y0, z - z0));
                    block.putInt("state", index);
                    if (state.hasBlockEntity()) {
                        BlockEntity entity = level.getBlockEntity(pos);
                        if (entity != null) {
                            CompoundTag nbt = entity.saveWithId(level.registryAccess());
                            nbt.remove("x");
                            nbt.remove("y");
                            nbt.remove("z");
                            BlueprintReader.stripContents(nbt);
                            block.put("nbt", nbt);
                        }
                    }
                    blocks.add(block);
                }
            }
        }
        if (paletteTag.size() > BlueprintLimits.MAX_PALETTE) {
            return Result.failed("This box uses too many different blocks. Make it smaller.");
        }
        CompoundTag root = new CompoundTag();
        root.put("blocks", blocks);
        root.put("palette", paletteTag);
        root.put("entities", new ListTag());
        root.put("size", ints(sx, sy, sz));
        root.put(INFO_TAG, info(null, "Box", x0, y0, z0, sy));
        NbtUtils.addCurrentDataVersion(root);
        byte[] bytes;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            NbtIo.writeCompressed(root, out);
            bytes = out.toByteArray();
        } catch (IOException e) {
            return Result.failed("The copy could not be packed.");
        }
        if (bytes.length > BlueprintLimits.MAX_FILE_BYTES) {
            return Result.failed("This copy is too big to save. Make the box smaller.");
        }
        return new Result(null, new BlockPos(x0, y0, z0), sx, sy, sz, count, "", false, "Box", g, bytes);
    }

    /**
     * The search area: the village border, at most {@link BuildingShapeScanner#MAX_REACH} from {@code centre}.
     * With {@code tiles} (a building's detected floor) only columns within {@link #OWN_REACH} of a tile count,
     * and outside the tiles and the walls around them nothing at or below the floor under the door is taken.
     */
    static BuildingShapeScanner.Area area(ServerLevel level, VillageManager village,
                                          @Nullable Building building, BlockPos centre, boolean fenceOthers,
                                          @Nullable Set<BlockPos> tiles) {
        int reach = BuildingShapeScanner.MAX_REACH;
        int minX = centre.getX() - reach, maxX = centre.getX() + reach;
        int minZ = centre.getZ() - reach, maxZ = centre.getZ() + reach;
        AABB border = village.borderBounds();
        int[] borderBox = null;
        if (border != null) {
            borderBox = new int[] {(int) Math.floor(border.minX), (int) Math.floor(border.minZ),
                    (int) Math.floor(border.maxX), (int) Math.floor(border.maxZ)};
            minX = Math.max(minX, borderBox[0]);
            minZ = Math.max(minZ, borderBox[1]);
            maxX = Math.min(maxX, borderBox[2]);
            maxZ = Math.min(maxZ, borderBox[3]);
        }
        int minY = Math.max(level.getMinBuildHeight(), centre.getY() - reach);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centre.getY() + reach);
        if (building != null) {
            // A known building stays near its rooms, so nothing it touches can drag the search away.
            BlockPos lo = building.shellMin(), hi = building.shellMax();
            minX = Math.max(minX, lo.getX() - OWN_REACH);
            maxX = Math.min(maxX, hi.getX() + OWN_REACH);
            minZ = Math.max(minZ, lo.getZ() - OWN_REACH);
            maxZ = Math.min(maxZ, hi.getZ() + OWN_REACH);
            minY = Math.max(minY, lo.getY() - 3);
            maxY = Math.min(maxY, hi.getY() + ROOF_ABOVE_SHELL + 3);
        }
        List<int[]> others = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Building b : village.getBuildings()) {
            if (fenceOthers && b != building) {
                others.add(roofBox(b));
                names.add(b.getDisplayName());
            }
        }
        boolean byFloor = tiles != null && building != null;
        return new BuildingShapeScanner.Area(minX, minY, minZ, maxX, maxY, maxZ, others, names,
                building == null ? null : shell(building),
                tiles == null ? null : near(tiles, minX, minZ, maxX, maxZ, OWN_REACH), borderBox,
                // The tiles and the walls around them, corners too.
                byFloor ? near(tiles, minX, minZ, maxX, maxZ, 1) : null,
                byFloor ? building.getDoorPos().getY() - 1 : Integer.MIN_VALUE);
    }

    /**
     * Columns of the box (X fastest) within {@code reach} of a floor tile. With {@link #OWN_REACH}:
     * eaves, porches and chimneys fit, the far corner of an L-shaped house's box does not.
     */
    @Nullable
    private static boolean[] near(Set<BlockPos> tiles, int minX, int minZ, int maxX, int maxZ, int reach) {
        int w = maxX - minX + 1, d = maxZ - minZ + 1;
        if (w <= 0 || d <= 0) return null;
        boolean[] tileColumn = new boolean[w * d];
        for (BlockPos tile : tiles) {
            int x = tile.getX() - minX, z = tile.getZ() - minZ;
            if (x >= 0 && x < w && z >= 0 && z < d) tileColumn[z * w + x] = true;
        }
        boolean[] near = new boolean[w * d];
        for (int z = 0; z < d; z++) {
            for (int x = 0; x < w; x++) {
                if (!tileColumn[z * w + x]) continue;
                for (int nz = Math.max(0, z - reach); nz <= Math.min(d - 1, z + reach); nz++) {
                    Arrays.fill(near, nz * w + Math.max(0, x - reach), nz * w + Math.min(w - 1, x + reach) + 1, true);
                }
            }
        }
        return near;
    }

    /** The shell's top is 3 above the room; the room scan looks for a roof up to MAX_CEILING_DIST. */
    private static final int ROOF_ABOVE_SHELL = RoomScanner.MAX_CEILING_DIST - 3;

    /** Another building's walls and its whole roof (as high as its room scan looks), off limits. */
    static int[] roofBox(Building building) {
        int[] box = shell(building);
        box[4] += ROOF_ABOVE_SHELL;
        return box;
    }

    private static int[] shell(Building building) {
        BlockPos lo = building.shellMin(), hi = building.shellMax();
        return new int[] {lo.getX(), lo.getY(), lo.getZ(), hi.getX(), hi.getY(), hi.getZ()};
    }

    /** Keeps every side between 1 and {@link BuildingShapeScanner#MAX_SIDE} blocks long. */
    static int[] clampGrow(BuildingShapeScanner.Shape shape, int[] grow) {
        int[] g = new int[SIDES];
        for (int i = 0; i < SIDES && i < grow.length; i++) g[i] = grow[i];
        clampAxis(g, 0, 1, shape.maxX - shape.minX + 1);
        clampAxis(g, 2, 3, shape.maxY - shape.minY + 1);
        clampAxis(g, 4, 5, shape.maxZ - shape.minZ + 1);
        return g;
    }

    private static void clampAxis(int[] g, int low, int high, int size) {
        int max = BuildingShapeScanner.MAX_SIDE;
        g[low] = Math.max(-(size - 1), Math.min(max - size, g[low]));
        g[high] = Math.max(-(size - 1), Math.min(max - size, g[high]));
        // Both sides together: never longer than the cap, never shorter than one block.
        while (size + g[low] + g[high] > max) {
            if (g[high] > 0) g[high]--; else g[low]--;
        }
        while (size + g[low] + g[high] < 1) {
            if (g[high] < 0) g[high]++; else g[low]++;
        }
    }

    /**
     * Cells of the shape's box that open air from the box's edge reaches, walking through
     * anything that is not the building (6 ways, so a gap at a corner does not count as open).
     * The rest, closed in by the building, is its inside.
     */
    private static BitSet outsideAir(BuildingShapeScanner.Shape shape) {
        int sx = shape.maxX - shape.minX + 1, sy = shape.maxY - shape.minY + 1, sz = shape.maxZ - shape.minZ + 1;
        BitSet reached = new BitSet(sx * sy * sz);
        int[] stack = new int[sx * sy * sz];
        int top = 0;
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    boolean edge = x == 0 || y == 0 || z == 0 || x == sx - 1 || y == sy - 1 || z == sz - 1;
                    if (!edge) continue;
                    int i = (y * sz + z) * sx + x;
                    if (reached.get(i) || shape.contains(shape.minX + x, shape.minY + y, shape.minZ + z)) continue;
                    reached.set(i);
                    stack[top++] = i;
                }
            }
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (top > 0) {
            int i = stack[--top];
            int x = i % sx, z = (i / sx) % sz, y = i / (sx * sz);
            for (int[] step : steps) {
                int nx = x + step[0], ny = y + step[1], nz = z + step[2];
                if (nx < 0 || ny < 0 || nz < 0 || nx >= sx || ny >= sy || nz >= sz) continue;
                int n = (ny * sz + nz) * sx + nx;
                if (reached.get(n) || shape.contains(shape.minX + nx, shape.minY + ny, shape.minZ + nz)) continue;
                reached.set(n);
                stack[top++] = n;
            }
        }
        return reached;
    }

    private static int shapeIndex(BuildingShapeScanner.Shape shape, int x, int y, int z) {
        int sx = shape.maxX - shape.minX + 1, sz = shape.maxZ - shape.minZ + 1;
        return ((y - shape.minY) * sz + (z - shape.minZ)) * sx + (x - shape.minX);
    }

    /**
     * The extra tag: what the building was, and where its door and token frame were, in box coordinates.
     * {@code floor} is the row of the floor under the door, so a placed copy can stand that floor on the ground.
     */
    private static CompoundTag info(@Nullable Building building, String label, int x0, int y0, int z0, int sizeY) {
        CompoundTag info = new CompoundTag();
        info.putString("kind", "copy");
        info.putString("name", label);
        if (building != null) {
            info.putString("type", building.getClass().getSimpleName());
            BlockPos door = building.getDoorPos();
            info.put("door", ints(door.getX() - x0, door.getY() - y0, door.getZ() - z0));
            info.putString("inside", building.getInteriorDirection().getName());
            BlockPos frame = building.getItemFramePos();
            info.put("token", ints(frame.getX() - x0, frame.getY() - y0, frame.getZ() - z0));
            int floorRow = door.getY() - 1 - y0;
            if (floorRow >= 0 && floorRow < sizeY) info.putInt(BlueprintReader.FLOOR_KEY, floorRow);
        }
        return info;
    }

    private static ListTag ints(int a, int b, int c) {
        ListTag list = new ListTag();
        list.add(IntTag.valueOf(a));
        list.add(IntTag.valueOf(b));
        list.add(IntTag.valueOf(c));
        return list;
    }
}
