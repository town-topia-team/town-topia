package com.alex.test.village.building;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Butchery: a walled room with a chopping block, where the butcher kills the
 * animals they lead in from the ranch. Created like every room building:
 * Butchery token in a frame beside a door.
 *
 * <p>The chopping block is any stripped log or stripped wood (stems, hyphae
 * and bamboo blocks too). Like every room, an empty one still registers; the
 * butcher just cannot work there until it has a chopping block with open
 * floor beside it. {@code functionalBlocks} holds the chopping blocks.
 *
 * <p>Each scan also picks where the work happens: a kill spot, the floor tile
 * beside a chopping block where the butcher stands, and an animal spot, a
 * second floor tile beside that one where the led animal is stopped. All room
 * tiles come from the door's room scan, so both are reachable from the door.
 * The player can pick the butcher's spot in the window ({@link #chooseKillSpot}).
 */
public final class ButcheryBuilding extends Building {

    public static final String SAVED_TYPE = "butchery";

    /** Shown when the room has no chopping block with floor beside it. */
    public static final String NEEDS_CHOPPING_BLOCK = "Needs a chopping block with floor beside it";

    /** Where the butcher stands, and where the led animal is stopped. */
    public record WorkSpots(BlockPos chop, BlockPos killSpot, BlockPos animalSpot) {}

    /** Give-ups the window lists. */
    public static final int TROUBLES_KEPT = 5;

    /** Why the player's chosen butcher's spot is not in use now (round 8 of the Butchery window plan). */
    public enum ChoiceTrouble { NONE, NOT_FLOOR, NO_BLOCK, NO_ROOM_FOR_ANIMAL, DOORWAY }

    private final Set<BlockPos> grindstones = new LinkedHashSet<>();
    private List<WorkSpots> workSpots = List.of();
    /** The pairs in the automatic order, before a chosen spot is put first. */
    private List<WorkSpots> automaticSpots = List.of();
    /**
     * The butcher's spot the player picked in the window, saved with the building; null = automatic.
     * Kept while it cannot be used (a scan of a broken room finds no floor at all), so the butcher goes
     * back to it once it works again; until then the automatic order is used.
     */
    @Nullable private BlockPos chosenKillSpot;
    private ChoiceTrouble choiceTrouble = ChoiceTrouble.NONE;
    /** Give-ups the room could help with that pause culls here until the next morning... */
    public static final int PAUSE_GIVE_UPS = 3;
    /** ...when they come within this many ticks (10 minutes). */
    public static final long PAUSE_WINDOW_TICKS = 12_000L;
    private static final long NOT_PAUSED = Long.MIN_VALUE;

    /** The last give-ups here, newest first; in memory only (not saved). */
    private final ArrayDeque<ButcheryTrouble> troubles = new ArrayDeque<>();
    /** The village day culls are paused on; in memory only, so a restart ends a pause. */
    private long pausedDay = NOT_PAUSED;
    /** Give-ups at or before this game time no longer count towards a pause. */
    private long countAfter = Long.MIN_VALUE;

    public ButcheryBuilding(UUID itemFrameId, BlockPos doorPos, BlockPos itemFramePos,
                            Direction interiorDirection, Set<BlockPos> initialNavMeshBlocks) {
        super(itemFrameId, doorPos, itemFramePos, interiorDirection, initialNavMeshBlocks);
    }

    @Override
    protected boolean scanFunctionalBlocks(ServerLevel level) {
        grindstones.clear();
        for (int x = minX - 1; x <= maxX + 1; x++) {
            for (int y = minY - 1; y <= maxY + 2; y++) {
                for (int z = minZ - 1; z <= maxZ + 1; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!isFunctionalBlockAccessibleFromRoom(pos)) continue;
                    BlockState state = level.getBlockState(pos);
                    if (isChoppingBlock(state)) {
                        functionalBlocks.add(pos);
                    } else if (state.is(Blocks.GRINDSTONE)) {
                        grindstones.add(pos);
                    }
                }
            }
        }
        automaticSpots = findWorkSpots();
        workSpots = withChoice(automaticSpots);
        return !workSpots.isEmpty();
    }

    /** The automatic pairs with the chosen spot's pairs first, or as they are when it is not one of them. */
    private List<WorkSpots> withChoice(List<WorkSpots> automatic) {
        choiceTrouble = ChoiceTrouble.NONE;
        if (chosenKillSpot == null) return automatic;
        List<WorkSpots> chosen = new ArrayList<>();
        List<WorkSpots> rest = new ArrayList<>();
        for (WorkSpots pair : automatic) (pair.killSpot().equals(chosenKillSpot) ? chosen : rest).add(pair);
        if (chosen.isEmpty()) {
            choiceTrouble = whyNotUsable(chosenKillSpot);
            return automatic;
        }
        chosen.addAll(rest);
        return Collections.unmodifiableList(chosen);
    }

    private ChoiceTrouble whyNotUsable(BlockPos tile) {
        if (!navMeshBlocks.contains(tile)) return ChoiceTrouble.NOT_FLOOR;
        boolean besideBlock = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (functionalBlocks.contains(tile.relative(dir))) besideBlock = true;
        }
        if (!besideBlock) return ChoiceTrouble.NO_BLOCK;
        // findWorkSpots drops the doorway tile as the butcher's spot once there is any other.
        if (tile.equals(entryTile())) return ChoiceTrouble.DOORWAY;
        return ChoiceTrouble.NO_ROOM_FOR_ANIMAL;
    }

    /**
     * The player picks the butcher's spot ({@code tile}, one of the pairs' kill spots), or null for
     * automatic. Takes effect at once for the next animal; the caller marks the village to be saved.
     */
    public void chooseKillSpot(@Nullable BlockPos tile) {
        chosenKillSpot = tile == null ? null : tile.immutable();
        workSpots = withChoice(automaticSpots);
    }

    /** The spot the player picked, saved with the building; null when automatic. */
    @Nullable
    public BlockPos chosenKillSpot() {
        return chosenKillSpot;
    }

    /** Why {@link #chosenKillSpot()} is not in use now; NONE when it is, or when nothing is chosen. */
    public ChoiceTrouble choiceTrouble() {
        return choiceTrouble;
    }

    /** Any stripped log or wood, stripped stems and hyphae, and bamboo blocks. */
    public static boolean isChoppingBlock(BlockState state) {
        if (state.is(Blocks.BAMBOO_BLOCK) || state.is(Blocks.STRIPPED_BAMBOO_BLOCK)) return true;
        if (!state.is(BlockTags.LOGS)) return false;
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().startsWith("stripped_");
    }

    /**
     * Every chopping block with a room tile beside it at its own height, and a
     * second room tile beside that one for the animal. Tiles closest to the
     * door come first; the doorway tile is used for the animal only as a last
     * resort, since an animal standing there keeps the door from closing. The
     * butcher never stands on the doorway tile unless every pair has them
     * there: the led animal waits behind the butcher, so it would block the
     * door and never come in.
     */
    private List<WorkSpots> findWorkSpots() {
        BlockPos entry = entryTile();
        List<WorkSpots> found = new ArrayList<>();
        for (BlockPos chop : functionalBlocks) {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos kill = chop.relative(dir);
                if (!navMeshBlocks.contains(kill)) continue;
                BlockPos animal = animalSpotBeside(kill, chop, entry);
                if (animal != null) found.add(new WorkSpots(chop, kill, animal));
            }
        }
        // A tiny room where every pair uses the doorway tile keeps working as before.
        if (found.stream().anyMatch(pair -> !pair.killSpot().equals(entry))) {
            found.removeIf(pair -> pair.killSpot().equals(entry));
        }
        // Pairs that park the animal in the doorway rank after the ones that do not.
        found.sort(Comparator.<WorkSpots, Boolean>comparing(pair -> pair.animalSpot().equals(entry))
                .thenComparingDouble(pair -> pair.killSpot().distSqr(doorPos)));
        return Collections.unmodifiableList(found);
    }

    @Nullable
    private BlockPos animalSpotBeside(BlockPos kill, BlockPos chop, BlockPos entry) {
        BlockPos fallback = null;
        BlockPos plain = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos tile = kill.relative(dir);
            if (tile.equals(chop) || !navMeshBlocks.contains(tile)) continue;
            if (tile.equals(entry)) {
                fallback = tile;
                continue;
            }
            // Beside a chopping block too reads best: the animal stands at the block.
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (functionalBlocks.contains(tile.relative(side))) return tile;
            }
            if (plain == null) plain = tile;
        }
        return plain != null ? plain : fallback;
    }

    public Set<BlockPos> getChoppingBlocks() {
        return functionalBlocks;
    }

    @Nullable
    public BlockPos firstChoppingBlock() {
        return functionalBlocks.isEmpty() ? null : functionalBlocks.iterator().next();
    }

    @Nullable
    public BlockPos getGrindstone() {
        return grindstones.isEmpty() ? null : grindstones.iterator().next();
    }

    /** Where the butcher stands to kill, or null when no chopping block has floor beside it. */
    @Nullable
    public BlockPos killSpot() {
        return workSpots.isEmpty() ? null : workSpots.get(0).killSpot();
    }

    /** Where the led animal is stopped, beside {@link #killSpot()}. */
    @Nullable
    public BlockPos animalSpot() {
        return workSpots.isEmpty() ? null : workSpots.get(0).animalSpot();
    }

    /** Every usable pair, best first, for when the first one is blocked. */
    public List<WorkSpots> getWorkSpots() {
        return workSpots;
    }

    /**
     * A butcher gave an animal up on its way here or in the room: kept for the window.
     * The {@link #PAUSE_GIVE_UPS}th give-up the room could help with inside
     * {@link #PAUSE_WINDOW_TICKS} pauses culls here for the rest of village day {@code day}
     * (round 3), so animals stop going back and forth between the butcher and the rancher.
     * True when this one paused it.
     */
    public boolean noteTrouble(ButcheryTrouble trouble, long day) {
        troubles.addFirst(trouble);
        while (troubles.size() > TROUBLES_KEPT) troubles.removeLast();
        if (!trouble.kind().roomTrouble() || isPaused(day)) return false;
        long since = Math.max(countAfter, trouble.gameTime() - PAUSE_WINDOW_TICKS);
        long count = troubles.stream().filter(t -> t.kind().roomTrouble() && t.gameTime() > since).count();
        if (count < PAUSE_GIVE_UPS) return false;
        pausedDay = day;
        countAfter = trouble.gameTime(); // a fresh count once the pause is over
        return true;
    }

    /** Whether culls here are paused on village day {@code day}. */
    public boolean isPaused(long day) {
        return pausedDay == day;
    }

    /** "Try again now" in the window: culls here again, and the give-ups so far no longer count. */
    public void clearPause(long gameTime) {
        pausedDay = NOT_PAUSED;
        countAfter = gameTime;
    }

    /** The last {@link #TROUBLES_KEPT} give-ups, newest first. */
    public List<ButcheryTrouble> recentTroubles() {
        return List.copyOf(troubles);
    }

    /** Whether a butcher can work here: a valid room with a chopping block and floor beside it. */
    public boolean canButcher() {
        return isValid() && !workSpots.isEmpty();
    }

    /** Villagers inside can feel crowded; see {@link BuildingSpace}. */
    @Override
    public boolean countsCrowding() {
        return true;
    }
}
