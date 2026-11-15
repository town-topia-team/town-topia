package com.alex.test.testkit;

import com.alex.test.ModItems;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.blacksmith.BlacksmithAssignmentHandler;
import com.alex.test.village.villagers.butcher.ButcherAssignmentHandler;
import com.alex.test.village.villagers.cook.CookAssignmentHandler;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.FarmerAssignmentHandler;
import com.alex.test.village.villagers.guard.GuardAssignmentHandler;
import com.alex.test.village.villagers.lumberjack.LumberjackAssignmentHandler;
import com.alex.test.village.villagers.miner.MinerAssignmentHandler;
import com.alex.test.village.villagers.rancher.RancherAssignmentHandler;
import com.alex.test.village.villagers.builder.BuilderAssignmentHandler;
import com.alex.test.village.villagers.bard.BardAssignmentHandler;
import com.alex.test.village.villagers.cleric.ClericAssignmentHandler;
import com.alex.test.village.villagers.druid.DruidAssignmentHandler;
import com.alex.test.village.villagers.enchanter.EnchanterAssignmentHandler;
import com.alex.test.village.villagers.teacher.TeacherAssignmentHandler;
import com.alex.test.village.villagers.tradesman.TradesmanAssignmentHandler;
import com.alex.test.village.villagers.archer.ArcherAssignmentHandler;
import com.alex.test.village.villagers.child.ChildAssignmentHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Constructs complete, valid test villages directly from code.
 *
 * <p>Testing villager AI needs a village, and building one by hand in a client
 * is exactly the slow manual step this kit removes. Everything here is
 * deterministic: the same command always produces byte-identical geometry, so a
 * behaviour regression is a real regression and not a different world.
 *
 * <h3>How a building registers itself</h3>
 * We never call the building registry directly. We place the same blocks a
 * player would — floor, walls, ceiling, door, furniture — and hang an item frame
 * holding the marker token above the door. {@code VillageManager} then discovers
 * it through its normal paths:
 * <ul>
 *   <li>a Town Hall frame joining the level fires {@code EntityJoinLevelEvent},
 *       which creates the village outright;</li>
 *   <li>{@code rebuildNow} re-runs {@code scanExistingBuildings}, which pairs
 *       every door on the nav graph with a nearby token frame.</li>
 * </ul>
 * That means these scenarios exercise the real registration code, not a
 * test-only shortcut around it.
 *
 * <h3>Geometry</h3>
 * Every room is built to the constraints the scanners actually enforce:
 * a solid ceiling 3 blocks above the floor (so {@code RoomScanner.findCeilingY}
 * succeeds), an interior small enough to stay under {@code MAX_ROOM_NODES},
 * and open sky immediately outside each door (so the room BFS cannot leak into
 * the surrounding terrain).
 */
public final class ScenarioBuilder {

    /**
     * Interior side length of a generated room unless a caller asks for more.
     *
     * <p>Public because callers that size a room themselves still need to know
     * what "default" means — a storage room only gets its extra wall of chests
     * once it is bigger than this.
     */
    public static final int DEFAULT_INTERIOR = 5;

    /**
     * How far the rock body behind a generated outcrop runs.
     *
     * <p>A strip mine's main corridor may reach {@code MineShaftPlan
     * .MAX_MAIN_LENGTH}; this is deliberately shorter. The point of the
     * fixture is a miner with real rock to work for days, not a guarantee it
     * can never reach the far end — and a body that long would be most of a
     * chunk of stone to place every time a scenario wants a mine.
     */
    private static final int OUTCROP_DEPTH = 24;

    /** Half-width of the rock body, so branches have material either side. */
    private static final int OUTCROP_HALF_WIDTH = 6;

    /** How far the rock body rises above the tunnel floor. */
    private static final int OUTCROP_HEIGHT = 5;

    /**
     * How far the rock runs below the tunnel floor.
     */
    private static final int OUTCROP_ROOT_DEPTH = 3;

    /** Floor-to-ceiling clearance. Walls occupy feetY .. feetY+2, ceiling at feetY+3. */
    private static final int WALL_HEIGHT = 3;

    /** Blocks between wall torches along a wall. Four keeps every tile above light 8. */
    private static final int TORCH_SPACING = 4;

    /** Vertical clearance carved above the ground when levelling a build site. */
    private static final int CLEAR_HEIGHT = 12;

    private ScenarioBuilder() {}

    // =========================================================================
    // Site preparation
    // =========================================================================

    /**
     * Levels a square site: grass on top, dirt beneath, air above.
     *
     * <p>On a superflat test world this is nearly a no-op, but it makes the
     * builder safe to run in any world and guarantees the nav graph sees clean,
     * fully connected ground.
     *
     * @return number of blocks actually changed
     */
    public static int levelSite(ServerLevel level, BlockPos center, int radius, int feetY) {
        int changed = 0;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                changed += setIfDifferent(level, new BlockPos(x, feetY - 1, z), grass);
                for (int dy = 2; dy <= 4; dy++) {
                    changed += setIfDifferent(level, new BlockPos(x, feetY - dy, z), dirt);
                }
                for (int dy = 0; dy < CLEAR_HEIGHT; dy++) {
                    changed += setIfDifferent(level, new BlockPos(x, feetY + dy, z), air);
                }
            }
        }
        return changed;
    }

    // =========================================================================
    // Whole-village scenario
    // =========================================================================

    /** What {@link #buildVillage} produced, so callers can report it back. */
    public record VillageResult(
            BlockPos townHallDoor,
            List<BlockPos> treeBases,
            int blocksChanged,
            @Nullable VillageManager manager) {}

    /**
     * Builds the standard test village at {@code center}: a Town Hall, two
     * houses (2 beds each), a storage room (2 chests + crafting table), and
     * {@code treeCount} oaks placed clear of every building.
     *
     * <p>{@code feetY} is the Y a villager stands on; ground is {@code feetY-1}.
     */
    public static VillageResult buildVillage(ServerLevel level, BlockPos center,
                                             int feetY, int treeCount) {
        int ox = center.getX();
        int oz = center.getZ();
        int changed = levelSite(level, center, 30, feetY);

        // Buildings sit in a row with wide gaps. The gaps matter: the room BFS
        // rejects any tile without a ceiling, so open sky between buildings is
        // what stops two rooms merging into one.
        Room townHall = buildRoom(level, ox - 2, feetY, oz, RoomFurniture.NONE);
        Room houseWest = buildRoom(level, ox - 16, feetY, oz, RoomFurniture.BEDS);
        Room houseEast = buildRoom(level, ox + 12, feetY, oz, RoomFurniture.BEDS);
        Room storage = buildRoom(level, ox - 2, feetY, oz + 14, RoomFurniture.STORAGE);

        // Trees go well outside every room AABB — isProtectedTreePosition
        // rejects anything within one block of a building.
        List<BlockPos> trees = new ArrayList<>();
        int[][] treeSpots = {
                {ox - 24, oz + 20}, {ox - 16, oz + 22}, {ox - 8, oz + 24},
                {ox + 8, oz + 24}, {ox + 16, oz + 22}, {ox + 24, oz + 20},
                {ox - 24, oz - 12}, {ox + 24, oz - 12},
        };
        for (int i = 0; i < Math.min(treeCount, treeSpots.length); i++) {
            BlockPos base = new BlockPos(treeSpots[i][0], feetY, treeSpots[i][1]);
            changed += buildOak(level, base);
            trees.add(base);
        }

        // Only now do the frames receive their tokens. Everything above had to
        // exist first: registration inspects the finished world, and a token
        // dropped into a half-built room registers an invalid building.
        townHall.frame.setItem(new ItemStack(ModItems.TOWN_HALL.get()), false);
        houseWest.frame.setItem(new ItemStack(ModItems.TWO_BED_HOUSE.get()), false);
        houseEast.frame.setItem(new ItemStack(ModItems.TWO_BED_HOUSE.get()), false);
        storage.frame.setItem(new ItemStack(ModItems.STORAGE_ROOM.get()), false);

        // The Town Hall is what brings the village into existence; the rest are
        // picked up by the auto-scan that every nav rebuild runs.
        // A prefab village is scenery, not a founding: no supply chest, no
        // welcome party. Scenarios that want those hang the token by hand.
        VillageManager mgr;
        try (AutoCloseable ignored = com.alex.test.village.VillageFounding.suppress()) {
            mgr = VillageManagerRegistry.get(level.getServer())
                    .createFromTownHall(level, townHall.frame);
        } catch (Exception e) {
            throw new IllegalStateException("prefab village creation failed", e);
        }
        if (mgr != null) {
            // Scenery, not a settlement: no settlers turn up on their own, so a
            // scenario's visitor roster is exactly what the scenario put there.
            mgr.visitors().setArrivalsEnabled(false);
            mgr.nomads().setArrivalsEnabled(false);
            // Room tokens only register for the village they are bound to,
            // as a Mayor-issued token is; bind the prefab's now it exists.
            for (Room room : List.of(houseWest, houseEast, storage)) {
                ItemStack token = room.frame.getItem().copy();
                if (com.alex.test.village.tokens.VillageTokenBinding.bind(token, mgr)) {
                    room.frame.setItem(token, false);
                }
            }
            mgr.rebuildNow(level);
            // Announce each tree the way a grown sapling would, so the
            // KnowledgeBase learns about them through the production path.
            for (BlockPos base : trees) mgr.routeTreeGrow(base);
        }

        DynamicVillages.LOGGER.info(
                "[TestKit] Built village at {} (feetY={}, {} trees, {} blocks changed).",
                center.toShortString(), feetY, trees.size(), changed);
        return new VillageResult(townHall.doorPos, trees, changed, mgr);
    }

    // =========================================================================
    // Rooms
    // =========================================================================

    /** Furniture package a generated room gets, which decides what it validates as. */
    public enum RoomFurniture { NONE, BEDS, BEDS_4, BEDS_6, STORAGE, KITCHEN, BUTCHERY, FORGE,
        TAVERN, LIBRARY, SCHOOL, STALL, BARRACKS }

    /** A finished room and the (still empty) marker frame hanging above its door. */
    public record Room(BlockPos doorPos, ItemFrame frame) {}

    /**
     * Builds one walled room whose door faces north (−Z) and hangs an empty item
     * frame two blocks above the door on the outside wall.
     *
     * <p>The frame is left empty deliberately. Dropping a token in during
     * construction fires the join-time registration path, and at that moment the
     * entity is not yet queryable by {@code getEntitiesOfClass} — so the
     * building auto-scan cannot see the very frame that triggered it. Callers
     * fill the frames once the whole site is finished.
     *
     * <p>The frame position is not arbitrary either: {@code findDoorNear}
     * searches 1–3 blocks below the frame's support block, so mounting the frame
     * at {@code feetY+2} puts the door's lower half exactly 2 below it.
     *
     * @param interiorMinX west edge of the interior floor
     * @param interiorMinZ north edge of the interior floor
     */
    public static Room buildRoom(ServerLevel level, int interiorMinX, int feetY,
                                 int interiorMinZ, RoomFurniture furniture) {
        return buildRoom(level, interiorMinX, feetY, interiorMinZ, furniture, DEFAULT_INTERIOR);
    }

    /**
     * As above, with an explicit interior side length.
     *
     * <p>Rooms whose whole point is holding people or things — a tavern with a
     * crowd in it, a storage room with a wall of chests — outgrow the default
     * five very quickly. Everything scales off {@code interior}: the furniture
     * spreads along the back wall, and {@link #lightRoom} puts up more torches.
     * Keep it odd, so the door lands in the middle of the north wall.
     */
    public static Room buildRoom(ServerLevel level, int interiorMinX, int feetY,
                                 int interiorMinZ, RoomFurniture furniture, int interior) {
        int maxX = interiorMinX + interior - 1;
        int maxZ = interiorMinZ + interior - 1;
        int wallMinX = interiorMinX - 1, wallMaxX = maxX + 1;
        int wallMinZ = interiorMinZ - 1, wallMaxZ = maxZ + 1;

        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState floor = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState roof = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        for (int x = wallMinX; x <= wallMaxX; x++) {
            for (int z = wallMinZ; z <= wallMaxZ; z++) {
                boolean perimeter = x == wallMinX || x == wallMaxX
                        || z == wallMinZ || z == wallMaxZ;
                level.setBlock(new BlockPos(x, feetY - 1, z), floor, 3);
                for (int dy = 0; dy < WALL_HEIGHT; dy++) {
                    level.setBlock(new BlockPos(x, feetY + dy, z), perimeter ? wall : air, 3);
                }
                level.setBlock(new BlockPos(x, feetY + WALL_HEIGHT, z), roof, 3);
            }
        }

        // Door in the middle of the north wall, opening onto open sky outside.
        int doorX = interiorMinX + interior / 2;
        BlockPos doorPos = new BlockPos(doorX, feetY, wallMinZ);
        placeDoor(level, doorPos, Direction.NORTH);

        // Frame hangs in the air block just outside the wall above the door.
        BlockPos framePos = new BlockPos(doorX, feetY + 2, wallMinZ - 1);
        level.setBlock(framePos, air, 3);
        ItemFrame frame = placeEmptyFrame(level, framePos, Direction.NORTH);

        switch (furniture) {
            case BEDS -> furnishBeds(level, interiorMinX, feetY, interiorMinZ, interior, 2);
            case BEDS_4 -> furnishBeds(level, interiorMinX, feetY, interiorMinZ, interior, 4);
            case BEDS_6 -> furnishBeds(level, interiorMinX, feetY, interiorMinZ, interior, 6);
            case STORAGE -> furnishStorage(level, interiorMinX, feetY, interiorMinZ, interior);
            case KITCHEN -> furnishKitchen(level, interiorMinX, feetY, interiorMinZ, interior);
            case BUTCHERY -> furnishButchery(level, interiorMinX, feetY, interiorMinZ, interior);
            case FORGE -> furnishForge(level, interiorMinX, feetY, interiorMinZ, interior);
            case TAVERN -> furnishTavern(level, interiorMinX, feetY, interiorMinZ, interior);
            case LIBRARY -> furnishLibrary(level, interiorMinX, feetY, interiorMinZ, interior);
            case SCHOOL -> furnishSchool(level, interiorMinX, feetY, interiorMinZ, interior);
            case STALL -> furnishStall(level, interiorMinX, feetY, interiorMinZ, interior);
            case BARRACKS -> furnishBarracks(level, interiorMinX, feetY, interiorMinZ, interior);
            case NONE -> { }
        }
        lightRoom(level, interiorMinX, feetY, interiorMinZ, interior);
        return new Room(doorPos, frame);
    }

    /**
     * Hangs wall torches round the inside of a room.
     *
     * <p>Every room gets these, and not as decoration: a sealed, unlit room is
     * a mob spawner. Villagers would come back from a day's work to a zombie in
     * the kitchen, and the first sign of it in a headless run is a villager
     * quietly losing health with nothing in its diary to say why.
     *
     * <p>The torches sit on the top interior layer ({@code feetY+2}), clear of
     * both the feet and head positions the nav graph cares about, and a torch
     * has no collision shape — so {@code RoomScanner.findCeilingY} walks
     * straight past them to the real ceiling and the room scans exactly as it
     * did before.
     */
    private static void lightRoom(ServerLevel level, int interiorMinX, int feetY,
                                  int interiorMinZ, int interior) {
        int maxX = interiorMinX + interior - 1;
        int maxZ = interiorMinZ + interior - 1;
        int y = feetY + WALL_HEIGHT - 1;
        for (int offset = 1; offset <= interior - 2; offset += TORCH_SPACING) {
            placeWallTorch(level, new BlockPos(interiorMinX + offset, y, interiorMinZ),
                    Direction.SOUTH);
            placeWallTorch(level, new BlockPos(interiorMinX + offset, y, maxZ),
                    Direction.NORTH);
            placeWallTorch(level, new BlockPos(interiorMinX, y, interiorMinZ + offset),
                    Direction.EAST);
            placeWallTorch(level, new BlockPos(maxX, y, interiorMinZ + offset),
                    Direction.WEST);
        }
    }

    /**
     * Puts a torch on the wall behind {@code pos}, facing into the room.
     *
     * <p>{@code WallTorchBlock.FACING} points away from the wall it hangs on —
     * the block checked for support is the one at {@code FACING.getOpposite()}
     * — so a torch on the north wall faces south.
     */
    private static void placeWallTorch(ServerLevel level, BlockPos pos, Direction facing) {
        if (!level.getBlockState(pos).isAir()) return;
        level.setBlock(pos, Blocks.WALL_TORCH.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing), 3);
    }

    /** Furnace and crafting table against the back wall — the cook's stations. */
    private static void furnishKitchen(ServerLevel level, int interiorMinX, int feetY,
                                       int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX, feetY, backZ),
                Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(interiorMinX + interior - 1, feetY, backZ),
                Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
    }

    /** A stripped oak log chopping block and a grindstone at the back; the rest is open floor for the animal. */
    private static void furnishButchery(ServerLevel level, int interiorMinX, int feetY,
                                        int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX + interior / 2, feetY, backZ),
                Blocks.STRIPPED_OAK_LOG.defaultBlockState(), 3);
        level.setBlock(new BlockPos(interiorMinX, feetY, backZ),
                Blocks.GRINDSTONE.defaultBlockState(), 3);
    }

    /** Furnace and anvil against the back wall — the blacksmith's stations. */
    private static void furnishForge(ServerLevel level, int interiorMinX, int feetY,
                                     int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX, feetY, backZ),
                Blocks.FURNACE.defaultBlockState(), 3);
        level.setBlock(new BlockPos(interiorMinX + interior - 1, feetY, backZ),
                Blocks.ANVIL.defaultBlockState(), 3);
    }

    /**
     * A jukebox against the back wall — the bard's stage — with tables to drink at.
     *
     * <p>A jukebox rather than the note block it used to be: {@code
     * TavernBuilding} accepts either, and what the bard actually plays is a
     * record, so a turntable is the honest prop. The stage is the only
     * instrument in the room on purpose — {@code getStagePos} returns whichever
     * instrument the scan happened to find first, so a second one would make
     * the bard's pitch non-deterministic for no gain.
     *
     * <p>The tables are scenery and nothing reads them, but a tavern full of
     * villagers standing in an empty stone box does not look like the thing the
     * building is called.
     */
    private static void furnishTavern(ServerLevel level, int interiorMinX, int feetY,
                                      int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX + interior / 2, feetY, backZ),
                Blocks.JUKEBOX.defaultBlockState(), 3);

        // Tables on a 3-block grid, kept one clear of every wall so they never
        // pinch the nav graph into dead ends.
        for (int dx = 2; dx <= interior - 3; dx += 3) {
            for (int dz = 2; dz <= interior - 4; dz += 3) {
                level.setBlock(new BlockPos(interiorMinX + dx, feetY, interiorMinZ + dz),
                        Blocks.OAK_FENCE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(interiorMinX + dx, feetY + 1, interiorMinZ + dz),
                        Blocks.OAK_PRESSURE_PLATE.defaultBlockState(), 3);
            }
        }
    }

    /** Bookshelves along the back wall plus an enchanting table — the library. */
    private static void furnishLibrary(ServerLevel level, int interiorMinX, int feetY,
                                       int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        for (int dx = 0; dx < 3; dx++) {
            level.setBlock(new BlockPos(interiorMinX + dx, feetY, backZ),
                    Blocks.BOOKSHELF.defaultBlockState(), 3);
        }
        level.setBlock(new BlockPos(interiorMinX + interior - 1, feetY, backZ),
                Blocks.ENCHANTING_TABLE.defaultBlockState(), 3);
    }

    /** A lectern against the back wall — the teacher's classroom. */
    private static void furnishSchool(ServerLevel level, int interiorMinX, int feetY,
                                      int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX + interior / 2, feetY, backZ),
                Blocks.LECTERN.defaultBlockState(), 3);
    }

    /** A barrel counter — the tradesman's stall. */
    private static void furnishStall(ServerLevel level, int interiorMinX, int feetY,
                                     int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        level.setBlock(new BlockPos(interiorMinX + interior / 2, feetY, backZ),
                Blocks.BARREL.defaultBlockState(), 3);
    }

    /** An armor stand against the back wall — the barracks' equipment rack. */
    private static void furnishBarracks(ServerLevel level, int interiorMinX, int feetY,
                                        int interiorMinZ, int interior) {
        int backZ = interiorMinZ + interior - 1;
        Entity stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null) return;
        stand.moveTo(interiorMinX + interior / 2 + 0.5, feetY, backZ + 0.5, 0, 0);
        level.addFreshEntity(stand);
    }

    private static void placeDoor(ServerLevel level, BlockPos lowerPos, Direction facing) {
        BlockState lower = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER)
                .setValue(BlockStateProperties.DOOR_HINGE, DoorHingeSide.LEFT)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.POWERED, false);
        level.setBlock(lowerPos, lower, 3);
        level.setBlock(lowerPos.above(),
                lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER), 3);
    }

    /** Hangs an empty, indestructible item frame facing {@code facing}. */
    private static ItemFrame placeEmptyFrame(ServerLevel level, BlockPos pos,
                                             Direction facing) {
        ItemFrame frame = new ItemFrame(level, pos, facing);
        frame.setInvulnerable(true);
        level.addFreshEntity(frame);
        return frame;
    }

    /** Two, four, or six beds laid out in rows with a walkable aisle between them. */
    private static void furnishBeds(ServerLevel level, int interiorMinX, int feetY,
                                    int interiorMinZ, int interior, int count) {
        int backZ = interiorMinZ + interior - 1;
        int columns = count == 6 ? 3 : 2;
        for (int i = 0; i < count; i++) {
            int row = i / columns;
            int column = i % columns;
            int x = columns == 2
                    ? interiorMinX + column * (interior - 1)
                    : interiorMinX + column * ((interior - 1) / 2);
            placeBed(level, new BlockPos(x, feetY, backZ - row * 2), Direction.NORTH);
        }
    }

    /**
     * Beds are two blocks: the foot sits at {@code footPos} and the head one step
     * along {@code facing}. Building scanners key off the head half.
     */
    private static void placeBed(ServerLevel level, BlockPos footPos, Direction facing) {
        BlockState bed = Blocks.RED_BED.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OCCUPIED, false);
        level.setBlock(footPos, bed.setValue(BlockStateProperties.BED_PART, BedPart.FOOT), 3);
        level.setBlock(footPos.relative(facing),
                bed.setValue(BlockStateProperties.BED_PART, BedPart.HEAD), 3);
    }

    /**
     * Chests along the back wall plus a crafting table — the storage-room
     * requirement — and, once the room is bigger than the default, both side
     * walls lined as well.
     *
     * <p>The chests are deliberately not adjacent: neighbouring chests would
     * merge into one double chest and the room would only count one.
     *
     * <p>Capacity is the whole point of making one of these bigger. Two chests
     * is 54 slots, which a village of twenty with seven production chains
     * fills inside a day — after which every deposit silently fails and the
     * professions look like they have stopped working when what they have
     * actually done is run out of shelf.
     */
    private static void furnishStorage(ServerLevel level, int interiorMinX, int feetY,
                                       int interiorMinZ, int interior) {
        int maxX = interiorMinX + interior - 1;
        int backZ = interiorMinZ + interior - 1;
        BlockState chest = Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.CHEST_TYPE, ChestType.SINGLE);

        for (int dx = 0; dx <= interior - 3; dx += 2) {
            level.setBlock(new BlockPos(interiorMinX + dx, feetY, backZ), chest, 3);
        }
        level.setBlock(new BlockPos(maxX, feetY, backZ),
                Blocks.CRAFTING_TABLE.defaultBlockState(), 3);

        // The default 5x5 keeps exactly the two chests it always had: it is the
        // fixture every other scenario measures against, and quietly doubling
        // its capacity would move thresholds those tests were written against.
        if (interior <= DEFAULT_INTERIOR) return;

        BlockState westChest = chest.setValue(BlockStateProperties.HORIZONTAL_FACING,
                Direction.EAST);
        BlockState eastChest = chest.setValue(BlockStateProperties.HORIZONTAL_FACING,
                Direction.WEST);
        for (int dz = 1; dz <= interior - 3; dz += 2) {
            level.setBlock(new BlockPos(interiorMinX, feetY, interiorMinZ + dz), westChest, 3);
            level.setBlock(new BlockPos(maxX, feetY, interiorMinZ + dz), eastChest, 3);
        }
    }

    // =========================================================================
    // Rock outcrops (mines)
    // =========================================================================

    /**
     * Raises a rock outcrop and hangs a Mine token on its face.
     *
     * <p>A mine is not a room, so this is not {@link #buildRoom}: there are no
     * walls, no door and no ceiling, just a body of stone with a marked block
     * on the side of it. See {@code MineBuilding} for why.
     *
     * <p>The supplied position is the future tunnel floor at villager feet
     * level. The token is placed two blocks above it, on the roof block.
     *
     * @param floor the future tunnel floor, at villager feet level
     * @param facing the open side the token faces; the shaft runs the other way
     * @return the roof anchor position and its (still empty) token frame
     */
    public static Room buildOutcrop(ServerLevel level, BlockPos floor, Direction facing) {
        Direction shaft = facing.getOpposite();
        BlockPos anchor = floor.above(2);
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // The body, anchor slice included. Flag 2: a solid mass of stone has no
        // neighbour reactions worth tens of thousands of block updates.
        for (int depth = 0; depth < OUTCROP_DEPTH; depth++) {
            BlockPos slice = floor.relative(shaft, depth);
            for (int side = -OUTCROP_HALF_WIDTH; side <= OUTCROP_HALF_WIDTH; side++) {
                BlockPos column = slice.relative(shaft.getClockWise(), side);
                for (int dy = -OUTCROP_ROOT_DEPTH; dy <= OUTCROP_HEIGHT; dy++) {
                    level.setBlock(column.above(dy), stone, 2);
                }
            }
        }

        // Clear the approach at feet and head level below the frame.
        BlockPos framePos = anchor.relative(facing);
        for (int dy = -2; dy <= 0; dy++) level.setBlock(framePos.above(dy), air, 3);
        ItemFrame frame = placeEmptyFrame(level, framePos, facing);
        return new Room(anchor, frame);
    }

    // =========================================================================
    // Trees
    // =========================================================================

    /**
     * Builds a deterministic 5-log oak with a leaf canopy.
     *
     * <p>Hand-placed rather than generated from the vanilla tree feature so
     * every run gets an identical tree — a chopping test that sometimes gets a
     * 6-log tree and sometimes a 4-log one is not a test. The shape still
     * satisfies {@code TreeStructure.inspect}: at least two same-family logs
     * with matching leaves within two blocks.
     *
     * @param base ground-level position of the trunk's lowest log
     * @return number of blocks placed
     */
    public static int buildOak(ServerLevel level, BlockPos base) {
        int placed = 0;
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(BlockStateProperties.PERSISTENT, false);

        // Trees need dirt under them and clear air around them.
        level.setBlock(base.below(), Blocks.DIRT.defaultBlockState(), 3);

        int trunkHeight = 5;
        for (int dy = 0; dy < trunkHeight; dy++) {
            level.setBlock(base.above(dy), log, 3);
            placed++;
        }

        // Canopy: a 5x5 slab at the top two trunk levels, then a 3x3 cap.
        int canopyBase = base.getY() + trunkHeight - 2;
        for (int dy = 0; dy < 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) continue; // clip corners
                    placed += setIfAir(level,
                            new BlockPos(base.getX() + dx, canopyBase + dy, base.getZ() + dz),
                            leaves);
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                placed += setIfAir(level,
                        new BlockPos(base.getX() + dx, base.getY() + trunkHeight, base.getZ() + dz),
                        leaves);
            }
        }
        return placed;
    }

    // =========================================================================
    // Villagers
    // =========================================================================

    /**
     * Spawns one villager bound to {@code mgr} and assigns {@code profession}
     * through the same handler the token item uses, so it receives its starter
     * tool exactly as an in-game assignment would.
     *
     * @return the spawned entity, or {@code null} if creation failed
     */
    @Nullable
    public static NpcEntity spawnVillager(ServerLevel level, VillageManager mgr,
                                          BlockPos pos, Profession profession) {
        NpcEntity npc = DynamicVillages.NPC.get().create(level);
        if (npc == null) return null;
        npc.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        level.addFreshEntity(npc);
        mgr.bindVillager(npc);
        applyProfession(npc, profession);
        return npc;
    }

    /**
     * Runs the first-time assignment for a profession -- starter tool, settings
     * defaults, the lot -- on a villager that already exists.
     *
     * <p>This is the code path a player's token right-click takes. Splitting it
     * out of {@link #spawnVillager} is what lets a headless harness hire a
     * villager born in the village rather than only ever conjuring pre-employed
     * ones, which is the difference between testing a village and testing a
     * diorama.
     */
    public static void applyProfession(NpcEntity npc, Profession profession) {
        switch (profession) {
            case LUMBERJACK -> LumberjackAssignmentHandler.assignFirstTime(npc);
            case GUARD -> GuardAssignmentHandler.assignFirstTime(npc);
            case FARMER -> FarmerAssignmentHandler.assignFirstTime(npc);
            case RANCHER -> RancherAssignmentHandler.assignFirstTime(npc);
            case COOK -> CookAssignmentHandler.assignFirstTime(npc);
            case BLACKSMITH -> BlacksmithAssignmentHandler.assignFirstTime(npc);
            case BUTCHER -> ButcherAssignmentHandler.assignFirstTime(npc);
            case MINER -> MinerAssignmentHandler.assignFirstTime(npc);
            case BUILDER -> BuilderAssignmentHandler.assignFirstTime(npc);
            case BARD -> BardAssignmentHandler.assignFirstTime(npc);
            case CLERIC -> ClericAssignmentHandler.assignFirstTime(npc);
            case DRUID -> DruidAssignmentHandler.assignFirstTime(npc);
            case ENCHANTER -> EnchanterAssignmentHandler.assignFirstTime(npc);
            case TEACHER -> TeacherAssignmentHandler.assignFirstTime(npc);
            case TRADESMAN -> TradesmanAssignmentHandler.assignFirstTime(npc);
            case ARCHER -> ArcherAssignmentHandler.assignFirstTime(npc);
            case CHILD -> ChildAssignmentHandler.assignFirstTime(npc);
            case CAPTAIN -> com.alex.test.village.villagers.guard.CaptainAssignmentHandler.assignFirstTime(npc);
            case MAGE -> com.alex.test.village.villagers.mage.MageAssignmentHandler.assignFirstTime(npc);
            case NONE -> { }
        }
    }

    // =========================================================================
    // Fenced enclosures (farm / ranch)
    // =========================================================================

    /**
     * Builds a fenced enclosure with a gate in the middle of the south wall and
     * a token frame hung on a stone gatepost beside it.
     *
     * <p>The gatepost is deliberately stone, not fence: item frames sit
     * reliably on a full block, and {@code findGateNear} searches the support
     * block's horizontal neighbours, which is exactly where the gate is.
     *
     * @param interiorMinX west edge of the interior
     * @param interiorMinZ north edge of the interior
     * @param width        interior size along X
     * @param depth        interior size along Z
     * @param waterRow     if true, one row of irrigation water crosses the middle
     * @return the enclosure's still-empty token frame and its gate position
     */
    public static Room buildEnclosure(ServerLevel level, int interiorMinX, int feetY,
                                      int interiorMinZ, int width, int depth,
                                      boolean waterRow) {
        int maxX = interiorMinX + width - 1;
        int maxZ = interiorMinZ + depth - 1;
        int wallMinX = interiorMinX - 1, wallMaxX = maxX + 1;
        int wallMinZ = interiorMinZ - 1, wallMaxZ = maxZ + 1;

        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();

        // Ground everywhere (interior dirt so it reads as farmable, ring grass).
        for (int x = wallMinX; x <= wallMaxX; x++) {
            for (int z = wallMinZ; z <= wallMaxZ; z++) {
                boolean perimeter = x == wallMinX || x == wallMaxX
                        || z == wallMinZ || z == wallMaxZ;
                level.setBlock(new BlockPos(x, feetY - 1, z), perimeter ? grass : dirt, 3);
                level.setBlock(new BlockPos(x, feetY, z),
                        perimeter ? fence : Blocks.AIR.defaultBlockState(), 3);
                level.setBlock(new BlockPos(x, feetY + 1, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }

        if (waterRow) {
            // Two single-block irrigation pockets, not a channel: a full row of
            // water splits the pen in two for the nav graph (villagers have no
            // water traversal), stranding every tile on the far side. Two
            // pockets hydrate the whole 9x7 plot and leave it fully walkable.
            int waterZ = interiorMinZ + depth / 2;
            level.setBlock(new BlockPos(interiorMinX + 2, feetY - 1, waterZ),
                    Blocks.WATER.defaultBlockState(), 3);
            level.setBlock(new BlockPos(interiorMinX + width - 3, feetY - 1, waterZ),
                    Blocks.WATER.defaultBlockState(), 3);
        }

        // Gate mid-south-wall; stone gatepost east of it carrying the frame.
        // The post is TWO blocks tall on purpose: a single full block in the
        // fence line is climbable, and the enclosure scan (correctly) treats a
        // climbable gap as a hole in the pen.
        int gateX = interiorMinX + width / 2;
        BlockPos gatePos = new BlockPos(gateX, feetY, wallMaxZ);
        level.setBlock(gatePos, Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.SOUTH), 3);
        BlockPos gatePost = new BlockPos(gateX + 1, feetY, wallMaxZ);
        level.setBlock(gatePost, Blocks.COBBLESTONE.defaultBlockState(), 3);
        level.setBlock(gatePost.above(), Blocks.COBBLESTONE.defaultBlockState(), 3);

        BlockPos framePos = gatePost.above().relative(Direction.SOUTH);
        level.setBlock(framePos, Blocks.AIR.defaultBlockState(), 3);
        ItemFrame frame = placeEmptyFrame(level, framePos, Direction.SOUTH);
        return new Room(gatePos, frame);
    }

    /**
     * Builds an irrigated field near a village. Fields are discovered from
     * their soil and water and do not use a structure token. Water beside
     * plain dirt is not a field (every river bank would be one): it takes
     * three tilled tiles in a water block's reach
     * ({@code FarmerPlanning.isRecognizableCropField}), so three tiles beside
     * each pocket are tilled, as a player starting a field would. The village
     * finds it on its farm sweep, not at once.
     *
     * @return the farm's gate position, or {@code null} if no village owns the site
     */
    @Nullable
    public static BlockPos buildFarm(ServerLevel level, VillageManager mgr,
                                     int interiorMinX, int feetY, int interiorMinZ) {
        Room farm = buildEnclosure(level, interiorMinX, feetY, interiorMinZ, 9, 7, true);
        int waterZ = interiorMinZ + 7 / 2;
        BlockState farmland = Blocks.FARMLAND.defaultBlockState();
        for (int waterX : new int[] {interiorMinX + 2, interiorMinX + 9 - 3}) {
            level.setBlock(new BlockPos(waterX, feetY - 1, waterZ - 1), farmland, 3);
            level.setBlock(new BlockPos(waterX, feetY - 1, waterZ + 1), farmland, 3);
            level.setBlock(new BlockPos(waterX + 1, feetY - 1, waterZ), farmland, 3);
        }
        if (mgr != null) mgr.rebuildNow(level);
        return farm.doorPos();
    }

    /**
     * Builds a ranch enclosure with animals inside, drops the Ranch token into
     * its frame, and re-registers the village.
     */
    @Nullable
    public static BlockPos buildRanch(ServerLevel level, VillageManager mgr,
                                      int interiorMinX, int feetY, int interiorMinZ,
                                      EntityType<?> animalType, int animalCount) {
        Room ranch = buildEnclosure(level, interiorMinX, feetY, interiorMinZ, 9, 7, false);
        for (int i = 0; i < animalCount; i++) {
            Entity animal = animalType.create(level);
            if (animal == null) continue;
            animal.moveTo(interiorMinX + 1.5 + (i % 3) * 2,
                    feetY, interiorMinZ + 1.5 + (i / 3) * 2, 0, 0);
            level.addFreshEntity(animal);
        }
        ItemStack token = new ItemStack(ModItems.RANCH.get());
        // Tokens only register for the village they are bound to.
        if (mgr != null) com.alex.test.village.tokens.VillageTokenBinding.bind(token, mgr);
        ranch.frame().setItem(token, false);
        if (mgr != null) mgr.rebuildNow(level);
        return ranch.doorPos();
    }

    // =========================================================================
    // Storage stocking
    // =========================================================================

    /**
     * Deposits items straight into a village's storage chests — test setup for
     * "the village already has seeds/fuel/materials" preconditions.
     *
     * @return how many items were actually placed
     */
    public static int stockStorage(ServerLevel level, VillageManager mgr, ItemStack stack) {
        for (Building building : mgr.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) {
                int before = stack.getCount();
                if (storage.depositItems(level, stack)) return before;
            }
        }
        return 0;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Sets a block only when it differs, and reports whether it changed. */
    private static int setIfDifferent(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) == state) return 0;
        level.setBlock(pos, state, 3);
        return 1;
    }

    /** Sets a block only into air, so canopies never eat the trunk or terrain. */
    private static int setIfAir(ServerLevel level, BlockPos pos, BlockState state) {
        if (!level.getBlockState(pos).isAir()) return 0;
        level.setBlock(pos, state, 3);
        return 1;
    }
}
