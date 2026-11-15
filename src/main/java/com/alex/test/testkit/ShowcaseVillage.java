package com.alex.test.testkit;

import com.alex.test.ModItems;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.building.Building;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One command's worth of late-game village: every profession, a workplace for
 * each, and the stock and terrain each of them needs to have real work waiting
 * on the first morning.
 *
 * <p>The per-profession scenarios under {@code tools/mctest/scenarios} each
 * stand up the one fixture their profession needs and nothing else, which is
 * right for a regression test and useless for watching the village <i>work</i>.
 * This builds the union of all of them at once — a settlement a player can walk
 * around and see sixteen jobs running side by side.
 *
 * <h3>What "late game" means here</h3>
 * Not a bigger starter village: a village that has already solved its bootstrap
 * problems. Veteran villagers (skill seeded to {@link #DEFAULT_SKILL_LEVEL}),
 * iron tools waiting in storage rather than wooden ones waiting to be crafted,
 * fields already under crop, herds already past their breeding stock, and an
 * ore body with enough in it that the miner never runs the blacksmith dry.
 * Everything downstream of that — the smith's order book, the tradesman's
 * surplus, the cook's menu — then has inputs on tick one instead of an in-game
 * week later.
 *
 * <h3>Geometry</h3>
 * Buildings sit on a 14-block lattice: a 7-block room footprint and a 7-block
 * gap, which is what {@link ScenarioBuilder#buildRoom} needs to keep two rooms
 * from merging during the room BFS. {@link #slotX}/{@link #slotZ} place
 * everything, and the four buildings {@link ScenarioBuilder#buildVillage} makes
 * on its own occupy four known slots that this class builds around rather than
 * duplicating.
 *
 * <p>The mountain is south of everything else so the two mine adits can face
 * the village across open ground: a mine is a Mine token on a block of natural
 * stone, and the miner walks up to that block from the front and drives its
 * shaft the other way. Put the massif north and the miners would work with
 * their backs to the village and their tunnels heading out of it.
 */
public final class ShowcaseVillage {

    // =========================================================================
    // Layout constants
    // =========================================================================

    /** Room footprint (7) plus the open-sky gap (7) the room BFS needs. */
    private static final int SLOT_PITCH = 14;

    /** Skill level every adult is seeded to. 10 is the cap; 8 leaves headroom. */
    public static final int DEFAULT_SKILL_LEVEL = 8;

    /** Intelligence adults start with — a village that has had a school a while. */
    private static final float ADULT_INTELLIGENCE = 60f;

    /** Interior size of a farm/ranch pen, matching {@code ScenarioBuilder.buildFarm}. */
    private static final int PEN_WIDTH = 9;
    private static final int PEN_DEPTH = 7;

    /** East edge of the building lattice; the pens start here. */
    private static final int PEN_X_OFFSET = 54;

    /** Forest rows sit this far north of the civic row, then every 8 blocks. */
    private static final int FOREST_NEAR_Z = -12;
    private static final int FOREST_ROWS = 3;
    private static final int FOREST_COLUMNS = 8;
    private static final int FOREST_SPACING = 8;

    /**
     * Lattice row the mine adits sit on, at the mountain's northern foot.
     *
     * <p>Not a room row any more — a mine is a marked block of rock rather than
     * a building — but the outcrops keep the lattice's spacing so the walk from
     * the village reads the same as every other workplace.
     */
    private static final int MINE_ROW = 4;

    /** Mountain dome: its north edge clears the adit mouths by a block. */
    private static final int MOUNTAIN_NORTH_Z = MINE_ROW * SLOT_PITCH + 6;
    private static final int MOUNTAIN_RADIUS_X = 34;
    private static final int MOUNTAIN_RADIUS_Z = 20;
    private static final int MOUNTAIN_CENTER_X = 7;
    private static final int MOUNTAIN_PEAK = 18;

    /**
     * Minimum rock thickness anywhere in the massif.
     *
     * <p>Four, not one: the miner tunnels at feet and head height, so a dome
     * tapering to a single block at its rim would leave a shaft breaking out
     * into daylight a few metres in — which reads, correctly, as a mine with
     * nothing left in it.
     */
    private static final int MOUNTAIN_FLOOR = 4;

    /** Ore veins are seeded on this lattice, through the depth a miner reaches. */
    private static final int VEIN_SPACING_XZ = 5;
    private static final int VEIN_SPACING_Y = 3;
    private static final int VEIN_TOP = 7;

    /** Vertical clearance the site is cleared to, matching ScenarioBuilder. */
    private static final int CLEAR_HEIGHT = 12;

    /** Stone one adit's body costs — fixed geometry, charged rather than counted. */
    private static final int ADIT_BLOCKS = 13 * 24 * 9;

    private ShowcaseVillage() {}

    // =========================================================================
    // Result
    // =========================================================================

    /** Everything the command needs to report back. */
    public record Result(
            @Nullable VillageManager manager,
            BlockPos townHallDoor,
            int buildings,
            int validBuildings,
            int villagers,
            int trees,
            int stockedItems,
            int blocksChanged,
            int forcedChunks,
            Map<String, BlockPos> landmarks,
            List<String> roster) {}

    // =========================================================================
    // Entry point
    // =========================================================================

    /**
     * Builds the whole set-piece at {@code origin}, whose Y is the level a
     * villager stands on (ground is one below, exactly as every other scenario
     * command reads it).
     */
    public static Result build(ServerLevel level, BlockPos origin, int skillLevel) {
        int ox = origin.getX();
        int oz = origin.getZ();
        int feetY = origin.getY();

        int forced = forceloadSite(level, ox, oz);

        // Level first, build second. levelSite clears twelve blocks of air
        // above the ground, so a forest planted before this pass would lose its
        // canopies to it.
        int changed = levelRect(level, ox - 42, ox + PEN_X_OFFSET + 14,
                oz + FOREST_NEAR_Z - (FOREST_ROWS * FOREST_SPACING) - 6,
                oz + MOUNTAIN_NORTH_Z, feetY);

        // The Town Hall is what brings a village into existence, and
        // buildVillage owns that path (founding suppressed, arrivals off, nav
        // rebuilt). Trees are zero here: this class plants its own forest well
        // outside the four slots buildVillage occupies.
        ScenarioBuilder.VillageResult core =
                ScenarioBuilder.buildVillage(level, origin, feetY, 0);
        VillageManager mgr = core.manager();
        changed += core.blocksChanged();
        if (mgr == null) {
            return new Result(null, core.townHallDoor(), 0, 0, 0, 0, 0, changed, forced,
                    Map.of(), List.of());
        }

        Map<String, BlockPos> landmarks = new LinkedHashMap<>();
        landmarks.put("town hall", core.townHallDoor());

        List<BlockPos> ripeCrops = new ArrayList<>();
        changed += buildDistricts(level, ox, oz, feetY, landmarks);
        changed += buildPens(level, ox, oz, feetY, landmarks, ripeCrops);
        changed += plantForest(level, ox, oz, feetY, landmarks);
        changed += raiseMountain(level, ox, oz, feetY, landmarks);
        changed += cutAdits(level, ox, oz, feetY, landmarks);

        // Room tokens only register for the village they are bound to (see
        // ScenarioBuilder.buildVillage); bind every token this site hung, or
        // only the four prefab buildings ever register.
        bindSiteTokens(level, mgr, ox, oz);

        // One rebuild for the whole site rather than one per building: every
        // token is hanging by now, so a single scan registers the lot, and a
        // nav graph this size is not something to rebuild twenty-eight times.
        mgr.rebuildNow(level);

        // Resources are announced the way growth would announce them, so the
        // KnowledgeBase learns them through the production path rather than
        // through a test-only back door. Both have to happen after the rebuild.
        for (int i = 0; i < FOREST_ROWS * FOREST_COLUMNS; i++) {
            BlockPos base = forestBase(ox, oz, feetY, i);
            if (base != null) mgr.routeTreeGrow(base);
        }
        announceRipe(mgr, level, ripeCrops);

        int stocked = stockLarder(level, mgr);
        List<String> roster = hireEveryone(level, mgr, ox, oz, feetY,
                skillLevel, landmarks);

        // Morning, so every profession starts the day in work rather than in
        // bed. Without it the command lands whenever the world happens to be
        // and the first thing an observer sees is everyone asleep.
        level.setDayTime(1000L);

        int buildings = mgr.getBuildings().size();
        int valid = (int) mgr.getBuildings().stream().filter(Building::isValid).count();

        DynamicVillages.LOGGER.info(
                "[TestKit] Showcase village at {} — {}/{} buildings valid, {} villagers, "
                        + "{} trees, {} items stocked, {} blocks changed.",
                origin.toShortString(), valid, buildings, roster.size(),
                FOREST_ROWS * FOREST_COLUMNS, stocked, changed);

        return new Result(mgr, core.townHallDoor(), buildings, valid, roster.size(),
                FOREST_ROWS * FOREST_COLUMNS, stocked, changed, forced, landmarks, roster);
    }

    // =========================================================================
    // Site preparation
    // =========================================================================

    /**
     * Force-loads every chunk the site touches.
     *
     * <p>The village is wider than a default simulation distance, so without
     * this the far end of it — the mine row and the pens — stops ticking the
     * moment the player walks to the other end, and the professions there look
     * broken rather than unloaded.
     */
    private static int forceloadSite(ServerLevel level, int ox, int oz) {
        int minCX = (ox - 48) >> 4;
        int maxCX = (ox + PEN_X_OFFSET + 20) >> 4;
        int minCZ = (oz + FOREST_NEAR_Z - (FOREST_ROWS * FOREST_SPACING) - 12) >> 4;
        int maxCZ = (oz + MOUNTAIN_NORTH_Z + (MOUNTAIN_RADIUS_Z * 2) + 8) >> 4;
        int count = 0;
        for (int cx = minCX; cx <= maxCX; cx++) {
            for (int cz = minCZ; cz <= maxCZ; cz++) {
                if (level.setChunkForced(cx, cz, true)) count++;
            }
        }
        return count;
    }

    /**
     * Binds every mod token hanging in an item frame on the site to the village,
     * as the Mayor does when it issues one. The Town Hall token is left as
     * ScenarioBuilder left it. Covers the same chunks {@link #forceloadSite} holds.
     */
    private static void bindSiteTokens(ServerLevel level, VillageManager mgr, int ox, int oz) {
        int minX = ((ox - 48) >> 4) << 4;
        int maxX = (((ox + PEN_X_OFFSET + 20) >> 4) << 4) + 16;
        int minZ = ((oz + FOREST_NEAR_Z - (FOREST_ROWS * FOREST_SPACING) - 12) >> 4) << 4;
        int maxZ = (((oz + MOUNTAIN_NORTH_Z + (MOUNTAIN_RADIUS_Z * 2) + 8) >> 4) << 4) + 16;
        net.minecraft.world.phys.AABB site = new net.minecraft.world.phys.AABB(
                minX, level.getMinBuildHeight(), minZ, maxX, level.getMaxBuildHeight(), maxZ);
        for (net.minecraft.world.entity.decoration.ItemFrame frame
                : level.getEntitiesOfClass(net.minecraft.world.entity.decoration.ItemFrame.class, site)) {
            ItemStack token = frame.getItem();
            if (token.isEmpty() || token.is(ModItems.TOWN_HALL.get())) continue;
            if (!DynamicVillages.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(token.getItem()).getNamespace())) continue;
            ItemStack bound = token.copy();
            if (com.alex.test.village.tokens.VillageTokenBinding.bind(bound, mgr)) frame.setItem(bound, false);
        }
    }

    /** {@code ScenarioBuilder.levelSite} over a rectangle rather than a square. */
    private static int levelRect(ServerLevel level, int minX, int maxX,
                                 int minZ, int maxZ, int feetY) {
        int changed = 0;
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
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
    // Districts
    // =========================================================================

    /** West edge of the room interior in lattice column {@code col}. */
    private static int slotX(int ox, int col) { return ox + col * SLOT_PITCH - 2; }

    /** North edge of the room interior in lattice row {@code row}. */
    private static int slotZ(int oz, int row) { return oz + row * SLOT_PITCH; }

    /** Where a villager should stand to be just outside a room's door. */
    private static BlockPos doorstep(int interiorMinX, int feetY, int interiorMinZ) {
        return new BlockPos(interiorMinX + 2, feetY, interiorMinZ - 3);
    }

    /**
     * One room to build: lattice position, furniture, the token it gets, and
     * how big its interior is.
     *
     * <p>Most rooms are the default five. The two kinds that are not are the
     * ones whose job is holding something: a tavern has to hold a crowd, or
     * the bard is playing to an empty floor, and a storage room has to hold a
     * village's output, which two chests do not.
     */
    private record Plot(int col, int row, ScenarioBuilder.RoomFurniture furniture,
                        Item token, @Nullable String landmark, int interior) {

        Plot(int col, int row, ScenarioBuilder.RoomFurniture furniture,
             Item token, @Nullable String landmark) {
            this(col, row, furniture, token, landmark, ScenarioBuilder.DEFAULT_INTERIOR);
        }
    }

    /** Interior side length for the tavern and the warehouses. */
    private static final int LARGE_INTERIOR = 9;

    /**
     * The rooms buildVillage does not already make.
     *
     * <p>Slots (0,0), (-1,0), (1,0) and (0,1) are deliberately absent: they are
     * the Town Hall, the two starter houses and the first storage room, and
     * building over them would leave two item frames fighting over one door.
     */
    private static List<Plot> plots() {
        List<Plot> plots = new ArrayList<>(List.of(
                // Civic row.
                new Plot(-2, 0, ScenarioBuilder.RoomFurniture.SCHOOL,
                        ModItems.SCHOOL.get(), "school"),
                new Plot(2, 0, ScenarioBuilder.RoomFurniture.TAVERN,
                        ModItems.TAVERN.get(), "tavern", LARGE_INTERIOR),
                new Plot(3, 0, ScenarioBuilder.RoomFurniture.LIBRARY,
                        ModItems.LIBRARY.get(), "library"),
                // Trades row.
                new Plot(-2, 1, ScenarioBuilder.RoomFurniture.BARRACKS,
                        ModItems.BARRACKS.get(), "barracks"),
                new Plot(-1, 1, ScenarioBuilder.RoomFurniture.STORAGE,
                        ModItems.STORAGE_ROOM.get(), "warehouse (west)", LARGE_INTERIOR),
                new Plot(1, 1, ScenarioBuilder.RoomFurniture.KITCHEN,
                        ModItems.KITCHEN.get(), "kitchen"),
                new Plot(2, 1, ScenarioBuilder.RoomFurniture.FORGE,
                        ModItems.FORGE.get(), "forge"),
                new Plot(3, 1, ScenarioBuilder.RoomFurniture.STALL,
                        ModItems.STALL.get(), "market stall"),
                // A second warehouse, south of the market row. Three storage
                // rooms between them hold ~600 slots: a village of twenty with
                // seven production chains fills two chests in a day, after
                // which every deposit fails silently and the professions look
                // like they have stopped rather than run out of shelf.
                new Plot(2, 3, ScenarioBuilder.RoomFurniture.STORAGE,
                        ModItems.STORAGE_ROOM.get(), "warehouse (south)",
                        LARGE_INTERIOR),
                // Beside the sheep ranch: the butcher leads animals here.
                new Plot(3, 3, ScenarioBuilder.RoomFurniture.BUTCHERY,
                        ModItems.BUTCHERY.get(), "butchery")));

        // Housing. Two beds per bedroom, and the roster below is 23 strong, so
        // ten of these plus the two starter houses is the smallest number that
        // still puts everyone in a bed — which matters, because a villager
        // without one loses motivation every night and eventually stops working.
        for (int col = -2; col <= 3; col++) {
            plots.add(new Plot(col, 2, ScenarioBuilder.RoomFurniture.BEDS,
                    ModItems.TWO_BED_HOUSE.get(), null));
        }
        for (int col = -2; col <= 1; col++) {
            plots.add(new Plot(col, 3, ScenarioBuilder.RoomFurniture.BEDS,
                    ModItems.TWO_BED_HOUSE.get(), null));
        }
        return plots;
    }

    private static int buildDistricts(ServerLevel level, int ox, int oz, int feetY,
                                      Map<String, BlockPos> landmarks) {
        List<Plot> plots = plots();
        for (Plot plot : plots) {
            int bx = slotX(ox, plot.col());
            int bz = slotZ(oz, plot.row());
            ScenarioBuilder.Room room = ScenarioBuilder.buildRoom(
                    level, bx, feetY, bz, plot.furniture(), plot.interior());
            room.frame().setItem(new ItemStack(plot.token()), false);
            if (plot.landmark() != null) landmarks.put(plot.landmark(), room.doorPos());
        }
        // buildRoom does not report a block count and this is a fixed-size
        // lattice, so charge it the footprint arithmetic rather than plumbing a
        // counter through ScenarioBuilder for a number nothing branches on.
        return plots.size() * 7 * 7 * 5;
    }

    // =========================================================================
    // Farms and ranches
    // =========================================================================

    private static int buildPens(ServerLevel level, int ox, int oz, int feetY,
                                 Map<String, BlockPos> landmarks,
                                 List<BlockPos> ripeCrops) {
        int px = ox + PEN_X_OFFSET;

        // Two fields. The farmer's is ripe and ready to cut; the druid's is
        // young, because the druid's first duty is pushing crops that already
        // exist further along, and a field of grown wheat gives it nothing to do.
        ScenarioBuilder.Room ripe = field(level, px, feetY, slotZ(oz, 0));
        ripeCrops.addAll(plantField(level, px, feetY, slotZ(oz, 0), 7));
        landmarks.put("farm (ripe)", ripe.doorPos());

        ScenarioBuilder.Room young = field(level, px, feetY, slotZ(oz, 1));
        plantField(level, px, feetY, slotZ(oz, 1), 0);
        landmarks.put("farm (young)", young.doorPos());

        // Two herds, both well past the butcher's two-adults-per-species
        // breeding floor, so there is a surplus to cull on day one and still
        // stock left for the rancher to breed back.
        ScenarioBuilder.Room cattle = pen(level, px, feetY, slotZ(oz, 2), false,
                ModItems.RANCH.get());
        stockPen(level, px, feetY, slotZ(oz, 2), EntityType.COW, 6, 0);
        stockPen(level, px, feetY, slotZ(oz, 2), EntityType.PIG, 4, 4);
        landmarks.put("ranch (cattle)", cattle.doorPos());

        ScenarioBuilder.Room flock = pen(level, px, feetY, slotZ(oz, 3), false,
                ModItems.RANCH.get());
        stockPen(level, px, feetY, slotZ(oz, 3), EntityType.SHEEP, 8, 0);
        landmarks.put("ranch (sheep)", flock.doorPos());

        return 4 * (PEN_WIDTH + 2) * (PEN_DEPTH + 2) * 3;
    }

    private static ScenarioBuilder.Room pen(ServerLevel level, int x, int feetY, int z,
                                            boolean irrigated, Item token) {
        ScenarioBuilder.Room room = ScenarioBuilder.buildEnclosure(
                level, x, feetY, z, PEN_WIDTH, PEN_DEPTH, irrigated);
        room.frame().setItem(new ItemStack(token), false);
        return room;
    }

    private static ScenarioBuilder.Room field(ServerLevel level, int x, int feetY, int z) {
        return ScenarioBuilder.buildEnclosure(
                level, x, feetY, z, PEN_WIDTH, PEN_DEPTH, true);
    }

    /**
     * Tills and sows a whole plot to {@code age}.
     *
     * <p>Skips the two irrigation pockets {@code buildEnclosure} sinks into the
     * middle row — they are below floor level, and farmland laid over water is
     * not farmland.
     *
     * @return the tiles that came out ripe, for {@link #announceRipe} to
     *         declare once the village has finished registering
     */
    private static List<BlockPos> plantField(ServerLevel level, int minX, int feetY,
                                             int minZ, int age) {
        BlockState farmland = Blocks.FARMLAND.defaultBlockState()
                .setValue(FarmBlock.MOISTURE, 7);
        Block[] rotation = { Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES };
        int waterZ = minZ + PEN_DEPTH / 2;
        List<BlockPos> ripe = new ArrayList<>();
        for (int dx = 0; dx < PEN_WIDTH; dx++) {
            for (int dz = 0; dz < PEN_DEPTH; dz++) {
                int x = minX + dx;
                int z = minZ + dz;
                if (z == waterZ && (dx == 2 || dx == PEN_WIDTH - 3)) continue;
                level.setBlock(new BlockPos(x, feetY - 1, z), farmland, 3);
                Block crop = rotation[(dx / 3) % rotation.length];
                BlockPos cropPos = new BlockPos(x, feetY, z);
                BlockState planted = crop.defaultBlockState().setValue(CropBlock.AGE, age);
                level.setBlock(cropPos, planted, 3);
                if (crop instanceof CropBlock cropBlock
                        && age >= cropBlock.getMaxAge()) {
                    ripe.add(cropPos);
                }
            }
        }
        return ripe;
    }

    /**
     * Tells the village about crops that were placed already grown.
     *
     * <p>Not optional, and the same trap the mod's own bonemeal path documents:
     * the KnowledgeBase learns ripe crops from {@code CropGrowEvent.Post} and
     * from nothing else, and a {@code setBlock} of an age-7 wheat fires no such
     * event. A field sown this way is therefore invisible to
     * {@code FarmerActivity.hasWork}, which — since every tile is already both
     * tilled and planted — has no other reason to return true either. The
     * symptom is a farmer standing in a field of ripe wheat scoring
     * {@code work=0.0} and going off to admire the scenery.
     */
    private static void announceRipe(VillageManager mgr, ServerLevel level,
                                     List<BlockPos> ripeCrops) {
        for (BlockPos pos : ripeCrops) {
            mgr.routeCropGrow(pos, level.getBlockState(pos));
        }
    }

    private static void stockPen(ServerLevel level, int minX, int feetY, int minZ,
                                 EntityType<?> type, int count, int rowOffset) {
        for (int i = 0; i < count; i++) {
            Entity animal = type.create(level);
            if (animal == null) continue;
            animal.moveTo(minX + 1.5 + (i % 4) * 2, feetY,
                    minZ + 1.5 + rowOffset + (i / 4) * 2, 0, 0);
            level.addFreshEntity(animal);
        }
    }

    // =========================================================================
    // Forest
    // =========================================================================

    /** Trunk base of forest tree {@code index}, or null if the index is past the grid. */
    @Nullable
    private static BlockPos forestBase(int ox, int oz, int feetY, int index) {
        if (index < 0 || index >= FOREST_ROWS * FOREST_COLUMNS) return null;
        int col = index % FOREST_COLUMNS;
        int row = index / FOREST_COLUMNS;
        // Offset alternate rows by half a spacing so the stand reads as a wood
        // rather than an orchard, while staying a fixed grid underneath.
        int jitter = (row % 2 == 0) ? 0 : FOREST_SPACING / 2;
        return new BlockPos(ox - 24 + col * FOREST_SPACING + jitter, feetY,
                oz + FOREST_NEAR_Z - row * FOREST_SPACING);
    }

    private static int plantForest(ServerLevel level, int ox, int oz, int feetY,
                                   Map<String, BlockPos> landmarks) {
        int placed = 0;
        for (int i = 0; i < FOREST_ROWS * FOREST_COLUMNS; i++) {
            BlockPos base = forestBase(ox, oz, feetY, i);
            if (base != null) placed += ScenarioBuilder.buildOak(level, base);
        }
        BlockPos centre = forestBase(ox, oz, feetY, FOREST_COLUMNS / 2);
        if (centre != null) landmarks.put("forest", centre);
        return placed;
    }

    // =========================================================================
    // Mountain
    // =========================================================================

    /**
     * Raises the ore body the miners work into.
     *
     * <p>A dome rather than a box, with a floor under it ({@link
     * #MOUNTAIN_FLOOR}) so the rim is still thick enough to tunnel through, and
     * ore veins stamped on a lattice through the depth a miner actually
     * reaches. Deterministic throughout — the veins come from a hash of the
     * position, not from {@code level.random}, so the same command twice gives
     * the same mountain and a miner's yield is a property of the fixture rather
     * than of the seed.
     */
    private static int raiseMountain(ServerLevel level, int ox, int oz, int feetY,
                                     Map<String, BlockPos> landmarks) {
        int cx = ox + MOUNTAIN_CENTER_X;
        int cz = oz + MOUNTAIN_NORTH_Z + MOUNTAIN_RADIUS_Z;
        BlockState stone = Blocks.STONE.defaultBlockState();
        int placed = 0;

        for (int x = cx - MOUNTAIN_RADIUS_X; x <= cx + MOUNTAIN_RADIUS_X; x++) {
            for (int z = cz - MOUNTAIN_RADIUS_Z; z <= cz + MOUNTAIN_RADIUS_Z; z++) {
                // Never north of the mine rooms' back wall: the room interiors
                // are up there, and stone poured into them would bury the very
                // buildings the mountain exists to serve.
                if (z < oz + MOUNTAIN_NORTH_Z) continue;

                double nx = (x - cx) / (double) MOUNTAIN_RADIUS_X;
                double nz = (z - cz) / (double) MOUNTAIN_RADIUS_Z;
                double d2 = nx * nx + nz * nz;
                if (d2 > 1.0) continue;

                int height = Math.max(MOUNTAIN_FLOOR,
                        (int) Math.round(MOUNTAIN_PEAK * (1.0 - d2)) + ridge(x, z));
                for (int dy = 0; dy < height; dy++) {
                    // Flag 2 (clients only): a solid mass of stone has no
                    // neighbour reactions worth the cost of firing tens of
                    // thousands of block updates for.
                    level.setBlock(new BlockPos(x, feetY + dy, z), stone, 2);
                    placed++;
                }
            }
        }
        placed += seedVeins(level, cx, cz, feetY);
        landmarks.put("mountain", new BlockPos(cx, feetY, cz));
        return placed;
    }

    /**
     * Drives two marked adits into the mountain's northern foot.
     *
     * <p>These are the mines. Each is a spur of rock running back into the
     * massif with a Mine token on its north face above villager head level, which
     * is the whole of what a mine is now — see {@code MineBuilding}. They are
     * built after the dome so their bodies merge into it rather than being
     * sliced off by its rim: the dome tapers to a knife edge at its north
     * extreme, and an anchor placed on that edge would have had a metre of rock
     * behind it and daylight beyond.
     */
    private static int cutAdits(ServerLevel level, int ox, int oz, int feetY,
                                Map<String, BlockPos> landmarks) {
        int z = slotZ(oz, MINE_ROW);
        int changed = 0;
        String[] names = { "mine (west)", "mine (east)" };
        int[] columns = { 0, 1 };
        for (int i = 0; i < columns.length; i++) {
            BlockPos anchor = new BlockPos(slotX(ox, columns[i]) + 2, feetY, z);
            ScenarioBuilder.Room adit =
                    ScenarioBuilder.buildOutcrop(level, anchor, Direction.NORTH);
            adit.frame().setItem(new ItemStack(ModItems.MINE.get()), false);
            landmarks.put(names[i], adit.doorPos());
            changed += ADIT_BLOCKS;
        }
        return changed;
    }

    /** A couple of blocks of deterministic relief, so the dome is not glassy. */
    private static int ridge(int x, int z) {
        return Math.floorMod(hash(x, 0, z), 3);
    }

    private static int seedVeins(ServerLevel level, int cx, int cz, int feetY) {
        int placed = 0;
        for (int x = cx - MOUNTAIN_RADIUS_X; x <= cx + MOUNTAIN_RADIUS_X; x += VEIN_SPACING_XZ) {
            for (int z = cz - MOUNTAIN_RADIUS_Z; z <= cz + MOUNTAIN_RADIUS_Z; z += VEIN_SPACING_XZ) {
                for (int dy = 0; dy <= VEIN_TOP; dy += VEIN_SPACING_Y) {
                    Block ore = oreFor(Math.floorMod(hash(x, dy, z), 100));
                    if (ore == null) continue;
                    placed += stampVein(level, new BlockPos(x, feetY + dy, z), ore);
                }
            }
        }
        return placed;
    }

    /**
     * The vein table.
     *
     * <p>Weighted the way an observer needs rather than the way the overworld
     * is: coal and iron dominate because they are what the blacksmith's chain
     * actually consumes, lapis is common enough to keep the enchanter working,
     * and diamond and emerald are present but rare enough to stay an event.
     */
    @Nullable
    private static Block oreFor(int roll) {
        if (roll < 22) return Blocks.COAL_ORE;
        if (roll < 40) return Blocks.IRON_ORE;
        if (roll < 48) return Blocks.COPPER_ORE;
        if (roll < 54) return Blocks.LAPIS_ORE;
        if (roll < 58) return Blocks.REDSTONE_ORE;
        if (roll < 61) return Blocks.GOLD_ORE;
        if (roll < 63) return Blocks.DIAMOND_ORE;
        if (roll < 64) return Blocks.EMERALD_ORE;
        return null;
    }

    /** Replaces a small blob of stone with {@code ore}, never anything else. */
    private static int stampVein(ServerLevel level, BlockPos centre, Block ore) {
        int placed = 0;
        BlockState state = ore.defaultBlockState();
        for (int dx = 0; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = 0; dz <= 1; dz++) {
                    BlockPos pos = centre.offset(dx, dy, dz);
                    if (!level.getBlockState(pos).is(Blocks.STONE)) continue;
                    level.setBlock(pos, state, 2);
                    placed++;
                }
            }
        }
        return placed;
    }

    /** Position hash. Deterministic, and independent of the world seed. */
    private static int hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        return h ^ (h >>> 16);
    }

    // =========================================================================
    // Stock
    // =========================================================================

    /** One line of the larder. */
    private record Line(Item item, int count) {}

    /**
     * Fills the two storage rooms with what a village this old would have on
     * the shelf.
     *
     * <p>Every line here is something a profession reads out of storage on its
     * first shift. Iron tools rather than wooden ones because the acquisition
     * activities upgrade to the best tool in the chest, and watching a
     * blacksmith work is more interesting than watching six villagers queue to
     * craft themselves a wooden axe.
     */
    private static int stockLarder(ServerLevel level, VillageManager mgr) {
        List<Line> larder = List.of(
                // Food: everyone eats, and the cook needs raw material.
                new Line(Items.BREAD, 64),
                new Line(Items.COOKED_BEEF, 32),
                new Line(Items.CARROT, 32),
                new Line(Items.WHEAT, 64),
                // Seeds, so the farmer can replant what it cuts.
                new Line(Items.WHEAT_SEEDS, 64),
                new Line(Items.POTATO, 32),
                new Line(Items.BEETROOT_SEEDS, 32),
                // Fuel and wood.
                new Line(Items.OAK_LOG, 64),
                new Line(Items.COAL, 64),
                new Line(Items.STICK, 64),
                new Line(Items.TORCH, 64),
                // The smith's inputs.
                new Line(Items.RAW_IRON, 64),
                new Line(Items.IRON_INGOT, 32),
                // The tradesman's surplus: well past its 32-unit reserve, so
                // there is something to actually sell on the first shift.
                new Line(Items.COBBLESTONE, 256),
                // The enchanter's line.
                new Line(Items.BOOK, 16),
                new Line(Items.LAPIS_LAZULI, 64),
                // Tools, one spare of each beyond the villager holding it.
                new Line(Items.IRON_AXE, 3),
                new Line(Items.IRON_PICKAXE, 3),
                new Line(Items.IRON_HOE, 2),
                new Line(Items.IRON_SHOVEL, 2),
                new Line(Items.IRON_SWORD, 3),
                new Line(Items.SHEARS, 2),
                new Line(Items.BUCKET, 2),
                // The guard's kit, and the archer's.
                new Line(Items.IRON_HELMET, 2),
                new Line(Items.IRON_CHESTPLATE, 2),
                new Line(Items.IRON_LEGGINGS, 2),
                new Line(Items.IRON_BOOTS, 2),
                new Line(Items.BOW, 2),
                new Line(Items.ARROW, 64),
                // A treasury, because a village this size has traded before.
                new Line(Items.EMERALD, 32));

        int stocked = 0;
        for (Line line : larder) {
            int remaining = line.count();
            while (remaining > 0) {
                int batch = Math.min(remaining, line.item().getDefaultMaxStackSize());
                ItemStack issued = new ItemStack(line.item(), batch);
                com.alex.test.village.tokens.VillageTokenBinding.bind(issued, mgr);
                int placed = ScenarioBuilder.stockStorage(level, mgr, issued);
                if (placed <= 0) break;
                stocked += placed;
                remaining -= placed;
            }
        }
        return stocked;
    }

    // =========================================================================
    // The roster
    // =========================================================================

    /** One villager to hire: what it does, and where it should be standing. */
    private record Hire(Profession profession, BlockPos pos, String where) {}

    private static List<Hire> roster(int ox, int oz, int feetY) {
        int px = ox + PEN_X_OFFSET;
        // Gate of a pen on lattice row r, then three blocks clear to the south.
        java.util.function.IntFunction<BlockPos> penGate = row ->
                new BlockPos(px + PEN_WIDTH / 2, feetY, slotZ(oz, row) + PEN_DEPTH + 3);

        BlockPos school = doorstep(slotX(ox, -2), feetY, slotZ(oz, 0));
        BlockPos tavern = doorstep(slotX(ox, 2), feetY, slotZ(oz, 0));
        BlockPos townHall = doorstep(slotX(ox, 0), feetY, slotZ(oz, 0));
        BlockPos barracks = doorstep(slotX(ox, -2), feetY, slotZ(oz, 1));

        List<Hire> hires = new ArrayList<>(List.of(
                new Hire(Profession.FARMER, penGate.apply(0), "ripe farm"),
                new Hire(Profession.DRUID, penGate.apply(1), "young farm"),
                new Hire(Profession.RANCHER, penGate.apply(2), "cattle ranch"),
                new Hire(Profession.BUTCHER, penGate.apply(3), "sheep ranch"),
                new Hire(Profession.COOK,
                        doorstep(slotX(ox, 1), feetY, slotZ(oz, 1)), "kitchen"),
                new Hire(Profession.BLACKSMITH,
                        doorstep(slotX(ox, 2), feetY, slotZ(oz, 1)), "forge"),
                new Hire(Profession.TRADESMAN,
                        doorstep(slotX(ox, 3), feetY, slotZ(oz, 1)), "market stall"),
                new Hire(Profession.ENCHANTER,
                        doorstep(slotX(ox, 3), feetY, slotZ(oz, 0)), "library"),
                new Hire(Profession.BARD, tavern, "tavern"),
                new Hire(Profession.TEACHER, school, "school"),
                // Two blocks north of each adit: the tile in front of the rock
                // is where the miner will stand to cut the mouth, and spawning
                // a villager into it just makes it shuffle aside first.
                new Hire(Profession.MINER,
                        new BlockPos(slotX(ox, 0) + 2, feetY, slotZ(oz, MINE_ROW) - 3),
                        "west mine"),
                new Hire(Profession.MINER,
                        new BlockPos(slotX(ox, 1) + 2, feetY, slotZ(oz, MINE_ROW) - 3),
                        "east mine"),
                new Hire(Profession.BUILDER, townHall, "town hall"),
                new Hire(Profession.CLERIC, tavern, "tavern"),
                new Hire(Profession.GUARD, barracks, "barracks"),
                new Hire(Profession.GUARD, barracks.offset(2, 0, 0), "barracks"),
                new Hire(Profession.ARCHER, barracks.offset(4, 0, 0), "barracks")));

        // Lumberjacks at the near edge of the wood.
        hires.add(new Hire(Profession.LUMBERJACK,
                new BlockPos(ox - 8, feetY, oz + FOREST_NEAR_Z + 4), "forest"));
        hires.add(new Hire(Profession.LUMBERJACK,
                new BlockPos(ox + 8, feetY, oz + FOREST_NEAR_Z + 4), "forest"));

        // Pupils, standing on the schoolroom floor from the start: the teacher
        // only teaches while somebody is physically in the room, and child
        // attendance is not something this fixture should be betting on.
        hires.add(new Hire(Profession.CHILD,
                new BlockPos(slotX(ox, -2) + 1, feetY, slotZ(oz, 0) + 1), "school (inside)"));
        hires.add(new Hire(Profession.CHILD,
                new BlockPos(slotX(ox, -2) + 3, feetY, slotZ(oz, 0) + 1), "school (inside)"));

        // Two jobless: an audience for the bard, and — once one of them is hurt
        // below — a patient for the cleric. Without a patient the cleric is the
        // one profession here with nothing whatsoever to do.
        hires.add(new Hire(Profession.NONE, tavern.offset(-2, 0, 0), "tavern (patient)"));
        hires.add(new Hire(Profession.NONE, tavern.offset(-4, 0, 0), "tavern"));
        return hires;
    }

    private static List<String> hireEveryone(ServerLevel level, VillageManager mgr,
                                             int ox, int oz, int feetY, int skillLevel,
                                             Map<String, BlockPos> landmarks) {
        List<String> lines = new ArrayList<>();
        NpcEntity patient = null;

        for (Hire hire : roster(ox, oz, feetY)) {
            NpcEntity npc = ScenarioBuilder.spawnVillager(
                    level, mgr, hire.pos(), hire.profession());
            if (npc == null) continue;
            settle(mgr, npc, hire.profession(), skillLevel);
            if (hire.profession() == Profession.NONE && patient == null) patient = npc;
            lines.add(String.format("%-11s %-18s %s",
                    hire.profession().name(), hire.where(), hire.pos().toShortString()));
        }

        // The cleric's work. Six hearts left is the margin prof_cleric.py uses:
        // comfortably past the half-heart threshold that counts as hurt, and low
        // enough to need several heal cycles rather than one lucky burst.
        if (patient != null) {
            patient.setHealth(6.0f);
            landmarks.put("cleric's patient", patient.blockPosition());
        }
        return lines;
    }

    /**
     * Turns a freshly spawned villager into a veteran one: fed, willing, and
     * experienced enough that its profession runs at something near full speed.
     */
    private static void settle(VillageManager mgr, NpcEntity npc,
                               Profession profession, int skillLevel) {
        VillagerModel model = mgr.getOrCreateModel(npc.getNpcId());
        if (model == null) return;
        VillagerBrain brain = model.brain();
        if (brain == null) return;

        brain.needs().hunger = 100f;
        brain.needs().wellbeing = 85f;
        brain.needs().clamp();

        VillagerSkills skills = brain.skills();
        if (profession != Profession.NONE) {
            // Set, not add: a freshly spawned villager may already carry a starting roll.
            if (profession != Profession.CHILD) {
                skills.addIntelligence(ADULT_INTELLIGENCE - skills.intelligence());
            }

            // VillagerSkills exposes level(), not the curve behind it, so seed
            // by measurement rather than re-deriving the formula here: award a
            // level's worth at a time until the track reads what was asked for.
            // The bound is a guard against a future curve, not an expected
            // iteration count.
            int target = Math.min(skillLevel, VillagerSkills.MAX_LEVEL);
            for (int i = 0; i < 400 && skills.level(profession) < target; i++) {
                skills.addWorkExperience(profession, 5f);
            }
        }
        persist(npc, brain);
    }

    /**
     * Writes seeded state through to the NBT the brain restores itself from.
     *
     * <p>Not optional, and the reason is worth stating because the failure is
     * silent. A brain runs {@code loadFrom(puppet)} once, on its first tick —
     * which is always *after* a command like this one has finished — and
     * {@code VillagerSkills.load} clears the experience map whether or not the
     * tag has anything to put back. Seeding the live object alone therefore
     * survives about a tick, and only the skills vanish: intelligence happens
     * to sit behind a {@code contains()} check and stays. The result is a
     * village of level-0 veterans with a suspiciously high learning rate.
     *
     * <p>Both {@code save} methods are the same ones the brain's own periodic
     * save calls, so this puts the puppet in exactly the state one tick of
     * normal running would have left it in.
     */
    private static void persist(NpcEntity npc, VillagerBrain brain) {
        brain.needs().save(npc.getBrainData());
        brain.skills().save(npc.getBrainData());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int setIfDifferent(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.getBlockState(pos) == state) return 0;
        level.setBlock(pos, state, 3);
        return 1;
    }
}
