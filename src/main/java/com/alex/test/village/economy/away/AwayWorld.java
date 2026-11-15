package com.alex.test.village.economy.away;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * The world changes the away jobs want, kept as a list while the days run:
 * nothing real changes before WRITE. Each is "if this is still X, make it Y";
 * WRITE applies those whose "if" still holds and skips the rest.
 *
 * <p>Jobs read real blocks and animals only where {@code level.isLoaded(pos)}:
 * a catch-up never loads a chunk. A change that throws at WRITE is logged and
 * skipped; the rest still go in.
 */
public final class AwayWorld {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One wanted change. */
    public interface Change {
        /** The world still looks the way the job saw it. */
        boolean stillHolds(ServerLevel level);

        void apply(ServerLevel level);

        /** For the log when it is skipped. */
        String describe();
    }

    private final List<Change> changes = new ArrayList<>();
    private int broke;

    public void add(Change change) {
        changes.add(change);
    }

    /** "If the block at {@code pos} is still {@code was}, make it {@code now}." */
    public void setBlock(BlockPos pos, BlockState was, BlockState now) {
        add(new BlockChange(pos.immutable(), was, now));
    }

    int size() {
        return changes.size();
    }

    /** WRITE: applies the changes that still hold; how many were applied. */
    int apply(ServerLevel level) {
        int applied = 0;
        for (Change change : changes) {
            // Each change on its own: one that throws (a bug of ours) is logged and the rest still go in.
            try {
                if (change.stillHolds(level)) {
                    change.apply(level);
                    applied++;
                } else {
                    LOGGER.info("[AwayWrite] world change skipped, no longer true: {}", change.describe());
                }
            } catch (RuntimeException e) {
                if (broke++ == 0) {
                    LOGGER.error("[AwayWrite] world change broke, the rest still go in: {}", describe(change), e);
                } else {
                    LOGGER.warn("[AwayWrite] world change broke too: {} ({})", describe(change), e.toString());
                }
            }
        }
        return applied;
    }

    /** World changes that threw at {@link #apply}. */
    int broke() { return broke; }

    private static String describe(Change change) {
        try {
            return change.describe();
        } catch (RuntimeException e) {
            return change.getClass().getSimpleName();
        }
    }

    private record BlockChange(BlockPos pos, BlockState was, BlockState now) implements Change {
        @Override
        public boolean stillHolds(ServerLevel level) {
            return level.isLoaded(pos) && level.getBlockState(pos) == was;
        }

        @Override
        public void apply(ServerLevel level) {
            level.setBlock(pos, now, Block.UPDATE_ALL);
        }

        @Override
        public String describe() {
            return "block at " + pos.toShortString() + " " + was + " -> " + now;
        }
    }
}
