package com.alex.test.village.knowledge;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.function.Predicate;

/**
 * Tells a column scan which 16-block chunk sections can't hold what it is
 * looking for, so it can jump past them without reading a block.
 *
 * <p>The world-load scans for trees and sugar cane used to read every block
 * of every column in the claim, bedrock to sky: about thirty million reads and
 * 1.3 s on a 300x300 claim, nearly all of it stone. A section's palette can
 * list a block the section no longer holds, but never leaves out one it does,
 * so a section it rules out really has none, and skipping it gives the same
 * answer the full read did.
 *
 * <p>Remembers the answers for the last chunk asked about. A scan reads the
 * sixteen columns of a chunk row one after another, so that is all it needs.
 * Server thread only.
 */
public final class SectionPalettes {

    private final Predicate<BlockState> wanted;
    private LevelChunk chunk;
    private boolean[] mayHold = new boolean[0];

    public SectionPalettes(Predicate<BlockState> wanted) {
        this.wanted = wanted;
    }

    /**
     * @return {@code false} only if the section of {@code chunk} holding
     *         {@code y} certainly has no wanted block. A height outside the
     *         chunk's sections answers {@code true}, leaving it to the caller's
     *         own read.
     */
    public boolean mayHold(LevelChunk chunk, int y) {
        if (chunk != this.chunk) {
            this.chunk = chunk;
            LevelChunkSection[] sections = chunk.getSections();
            if (mayHold.length != sections.length) mayHold = new boolean[sections.length];
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection section = sections[i];
                mayHold[i] = section != null && !section.hasOnlyAir() && section.maybeHas(wanted);
            }
        }
        int index = chunk.getSectionIndex(y);
        return index < 0 || index >= mayHold.length || mayHold[index];
    }
}
