package com.alex.test.village;

import com.alex.test.village.chat.TokenFeedback;
import com.alex.test.village.tokens.ItemFrameTokenRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Town Halls hung by players, set up a slice per tick before their village is
 * founded.
 *
 * <p>Founding in one go drew a small map to check the room, then the whole
 * village's map, then learned every tree and crop, all inside one tick: a
 * freeze of 6.6 s on a player's phone. Here the room is checked first, on a
 * small map drawn in slices, so a room that does not work is still reported
 * within a moment. The land then loads in the background, the whole map is
 * drawn in slices, and the village is founded on that map, learning its trees
 * and crops in slices afterwards (see {@link VillageManager#wakeUpWith}).
 *
 * <p>Server thread only. Nothing here is saved: a world closed during a setup
 * still has the token in its frame, and the frame loading in again starts a
 * new one.
 */
final class TownHallSetups {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Longest the chunks of a stage are waited for before it goes ahead anyway. */
    private static final long LOAD_TIMEOUT_TICKS = 1200L;
    /** Blocks changed in the land while its map was drawn, remembered at most. */
    private static final int MAX_CHANGES = 512;
    /** Chunks let go per tick after a setup that founded nothing. */
    private static final int RELEASE_PER_TICK = 8;
    /** Times a setup starts over because its door moved, before it founds in one go. */
    private static final int MAX_RESTARTS = 3;

    private enum Stage { LOAD_ROOM, CHECK_ROOM, LOAD_LAND, MAP }

    private static final class Setup {
        final ResourceKey<Level> dimension;
        final UUID frameId;
        final BlockPos framePos;
        final int radius;
        final long startedAt;
        /** Every chunk this setup holds loaded; handed to the village, or let go. */
        final ChunkHold hold = new ChunkHold();
        /** Blocks broken or placed in the land while its map was drawn. */
        final Set<BlockPos> changed = new LinkedHashSet<>();
        BlockPos door;
        Stage stage = Stage.LOAD_ROOM;
        @Nullable SlicedMapBuild build;
        long roomCheckedAt;
        long landLoadedAt;
        long checkMillis;
        int checkTicks;
        int restarts;

        Setup(ResourceKey<Level> dimension, UUID frameId, BlockPos framePos, BlockPos door,
              int radius, long startedAt) {
            this.dimension = dimension;
            this.frameId = frameId;
            this.framePos = framePos;
            this.door = door;
            this.radius = radius;
            this.startedAt = startedAt;
        }
    }

    private record Release(ResourceKey<Level> dimension, ChunkHold hold) {}

    private final VillageManagerRegistry registry;
    private final Map<UUID, Setup> setups = new LinkedHashMap<>();
    /** Chunks of setups that founded nothing, let go a few per tick. */
    private final List<Release> releasing = new ArrayList<>();

    TownHallSetups(VillageManagerRegistry registry) {
        this.registry = registry;
    }

    /** True while the frame's village is being set up. */
    boolean isSettingUp(UUID frameId) {
        return setups.containsKey(frameId);
    }

    /** Starts setting up the village of a fresh Town Hall token hung by {@code door}. */
    void begin(ServerLevel level, ItemFrame frame, BlockPos door, int radius) {
        Setup setup = new Setup(level.dimension(), frame.getUUID(), frame.blockPosition().immutable(),
                door.immutable(), radius, level.getGameTime());
        holdRoom(level, setup);
        setups.put(setup.frameId, setup);
        TokenFeedback.announceTownHallSettingUp(level, setup.framePos);
        LOGGER.info("[TownHallSetup] Town Hall at {} (door {}): setting up its village a slice per tick.",
                setup.framePos, setup.door);
    }

    /**
     * A block broken or placed. One inside the land of a village whose map is
     * being drawn may or may not be on that map, so the village looks again
     * once it is founded.
     */
    void noteChange(ServerLevel level, BlockPos pos) {
        if (setups.isEmpty()) return;
        for (Setup setup : setups.values()) {
            if (setup.stage != Stage.MAP || !setup.dimension.equals(level.dimension())
                    || setup.changed.size() >= MAX_CHANGES) continue;
            int cx = setup.door.getX() >> 4;
            int cz = setup.door.getZ() >> 4;
            if (Math.abs((pos.getX() >> 4) - cx) > setup.radius
                    || Math.abs((pos.getZ() >> 4) - cz) > setup.radius) continue;
            setup.changed.add(pos.immutable());
        }
    }

    /** One step of every setup in {@code level}. */
    void tick(ServerLevel level) {
        tickReleases(level);
        if (setups.isEmpty()) return;
        for (Setup setup : List.copyOf(setups.values())) {
            if (!setup.dimension.equals(level.dimension())) continue;
            try {
                step(level, setup);
            } catch (RuntimeException failure) {
                // Never leave a Town Hall stuck half set up: found it the old way, now.
                LOGGER.error("[TownHallSetup] Town Hall at {}: setting it up a slice at a time failed; "
                        + "founding it in one go.", setup.framePos, failure);
                setups.remove(setup.frameId);
                releaseLater(setup);
                ItemFrame frame = currentFrame(level, setup);
                if (frame != null) registry.createFromTownHall(level, frame);
            }
        }
    }

    private void step(ServerLevel level, Setup setup) {
        ItemFrame frame = currentFrame(level, setup);
        if (frame == null) {
            stop(level, setup);
            return;
        }
        switch (setup.stage) {
            case LOAD_ROOM -> {
                if (!setup.hold.loaded(level, LOAD_TIMEOUT_TICKS)) return;
                setup.build = new SlicedMapBuild(level, setup.door, VillageBuildings.TOWN_HALL_CHECK_RADIUS, 0L);
                setup.stage = Stage.CHECK_ROOM;
            }
            case CHECK_ROOM -> {
                NavGraph quick = setup.build.advance(level);
                if (quick == null) return;
                setup.checkMillis += setup.build.workMillis();
                setup.checkTicks += setup.build.ticks();
                if (doorMoved(level, frame, setup)) return;
                VillageBuildings.TownHallScanResult result =
                        VillageBuildings.scanTownHallMarkerDetailed(level, frame, setup.radius, quick);
                if (result.scan() == null) {
                    setups.remove(setup.frameId);
                    releaseLater(setup);
                    registry.reportTownHallScanFailure(level, frame, result);
                    return;
                }
                setup.roomCheckedAt = level.getGameTime();
                int cx = setup.door.getX() >> 4;
                int cz = setup.door.getZ() >> 4;
                // The square the village will claim, and the ring its map looks into.
                setup.hold.hold(level, ChunkHold.square(cx, cz, cx, cz, setup.radius + 1));
                setup.build = null;
                setup.stage = Stage.LOAD_LAND;
            }
            case LOAD_LAND -> {
                if (!setup.hold.loaded(level, LOAD_TIMEOUT_TICKS)) return;
                int missing = setup.hold.missing(level);
                if (missing > 0) {
                    LOGGER.warn("[TownHallSetup] Town Hall at {}: {} chunk(s) of its land still not loaded "
                            + "after {} ticks; mapping it anyway.", setup.framePos, missing, setup.hold.waited(level));
                }
                setup.landLoadedAt = level.getGameTime();
                setup.build = new SlicedMapBuild(level, setup.door, setup.radius, 1L);
                setup.stage = Stage.MAP;
            }
            case MAP -> {
                NavGraph graph = setup.build.advance(level);
                if (graph == null) return;
                if (doorMoved(level, frame, setup)) return;
                found(level, setup, frame, graph);
            }
        }
    }

    /** Founds the village on its finished map, then hands it the land to let go of later. */
    private void found(ServerLevel level, Setup setup, ItemFrame frame, NavGraph graph) {
        setups.remove(setup.frameId);
        long began = System.nanoTime();
        VillageManager founded = registry.foundOnDrawnMap(level, frame, graph, setup.radius);
        boolean ok = founded != null && founded.hasValidTownHall(setup.frameId);
        if (ok) {
            // Kept loaded until the village has learned its trees, then let go a few per tick.
            founded.takeWakeHold(setup.hold);
            // Blocks changed while the map was drawn may or may not be on it: look again.
            for (BlockPos pos : setup.changed) founded.noteBlockChanged(pos);
        } else {
            releaseLater(setup);
        }
        LOGGER.info("[TownHallSetup] Town Hall at {}: {}. Room checked in {} ms over {} ticks, "
                        + "land loaded in {} more ticks, map drawn in {} ms over {} ticks, founded in {} ms; "
                        + "{} ticks in all.",
                setup.framePos, ok ? "village founded" : "not founded",
                setup.checkMillis, setup.checkTicks, setup.landLoadedAt - setup.roomCheckedAt,
                setup.build.workMillis(), setup.build.ticks(), (System.nanoTime() - began) / 1_000_000L,
                level.getGameTime() - setup.startedAt);
    }

    /**
     * The door was changed while a map was drawn: start over from the door
     * there now. With no door left, or one that keeps moving, the Town Hall is
     * handed to the one-go founding, which reports a missing door at once
     * without drawing any map.
     *
     * @return true when the setup did not carry on from here
     */
    private boolean doorMoved(ServerLevel level, ItemFrame frame, Setup setup) {
        BlockPos door = VillageBuildings.townHallDoor(level, frame);
        if (setup.door.equals(door)) return false;
        if (door == null || setup.restarts >= MAX_RESTARTS) {
            setups.remove(setup.frameId);
            releaseLater(setup);
            registry.createFromTownHall(level, frame);
            return true;
        }
        setup.restarts++;
        LOGGER.info("[TownHallSetup] Town Hall at {}: its door moved from {} to {}; starting over.",
                setup.framePos, setup.door, door);
        setup.door = door.immutable();
        setup.changed.clear();
        holdRoom(level, setup);
        return true;
    }

    /** The setup's token left its frame, or the frame unloaded with its chunk. */
    private void stop(ServerLevel level, Setup setup) {
        setups.remove(setup.frameId);
        releaseLater(setup);
        // A frame unloaded with its chunk starts a new setup as it loads again.
        boolean unloaded = !level.areEntitiesLoaded(ChunkPos.asLong(setup.framePos));
        if (!unloaded) TokenFeedback.announceTownHallSetupStopped(level, setup.framePos);
        LOGGER.info("[TownHallSetup] Town Hall at {}: setup stopped, {}.", setup.framePos,
                unloaded ? "its frame unloaded" : "its token left the frame");
    }

    /** The frame in place with a fresh Town Hall token, or null once it is not. */
    @Nullable
    private static ItemFrame currentFrame(ServerLevel level, Setup setup) {
        if (!(level.getEntity(setup.frameId) instanceof ItemFrame frame) || !frame.isAlive()) return null;
        if (!frame.blockPosition().equals(setup.framePos)) return null;
        if (ItemFrameTokenRegistry.tokenType(frame.getItem())
                != ItemFrameTokenRegistry.TokenType.TOWN_HALL) return null;
        return VillageTokenBinding.isBound(frame.getItem()) ? null : frame;
    }

    /** Holds the chunks of the quick room check: its square and the ring its edge checks look into. */
    private static void holdRoom(ServerLevel level, Setup setup) {
        int cx = setup.door.getX() >> 4;
        int cz = setup.door.getZ() >> 4;
        setup.hold.hold(level, ChunkHold.square(cx, cz, cx, cz, VillageBuildings.TOWN_HALL_CHECK_RADIUS + 1));
        setup.build = null;
        setup.stage = Stage.LOAD_ROOM;
    }

    private void releaseLater(Setup setup) {
        if (!setup.hold.isEmpty()) releasing.add(new Release(setup.dimension, setup.hold));
    }

    private void tickReleases(ServerLevel level) {
        Iterator<Release> it = releasing.iterator();
        while (it.hasNext()) {
            Release release = it.next();
            if (!release.dimension().equals(level.dimension())) continue;
            if (release.hold().releaseSome(level, RELEASE_PER_TICK)) it.remove();
            return;
        }
    }
}
