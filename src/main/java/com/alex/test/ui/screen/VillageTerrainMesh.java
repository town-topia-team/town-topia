package com.alex.test.ui.screen;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.BitSet;

/**
 * The village's land for the Builder's desk, as flat coloured columns: one height and
 * one map colour per cell, read from the chunks the client already has.
 *
 * <p>Work is spread over frames so opening the desk never stutters: about
 * {@link #COLUMNS_PER_FRAME} columns are read per frame, then the quads are written
 * {@link #CELLS_PER_FRAME} cells per frame into one native buffer, which goes to the
 * GPU once as a static {@link VertexBuffer}. After that a frame only issues one draw.
 * Nothing here is rebuilt while the desk stays open; the caller closes it. A new Layers
 * cut makes a new one ({@code cut}: the highest height shown; the top under it is found
 * through cave air), drawn around the first one's middle height so everything lines up.
 */
final class VillageTerrainMesh {
    /** Blocks of land shown around the border, so buildings at the edge have ground beside them. */
    static final int MARGIN = 4;
    private static final int MAX_CELLS = 65_536;
    private static final int WIDE_BORDER = 192;
    private static final int COLUMNS_PER_FRAME = 4_096;
    private static final int CELLS_PER_FRAME = 8_192;
    /** How far down to look past see-through blocks (glass, flowers) for a colour. */
    private static final int COLOR_DEPTH = 24;
    private static final int UNLOADED = Integer.MIN_VALUE;
    private static final int FLOOR_COLOR = 0xFF1A1A1A;
    private static final int UNKNOWN_COLOR = 0xFF5A5A5A;
    /** How far under a cut the top is looked for through air (a big cave). */
    private static final int CUT_SEARCH = 64;

    /** Area drawn, inclusive, and how it is cut into cells. */
    final int minX, minZ, maxX, maxZ, cell, cols, rows;
    private final int[] heights;
    private final int[] colors;
    private final BitSet path;

    private int sampled, meshed;
    private int minHeight = Integer.MAX_VALUE, maxHeight = Integer.MIN_VALUE;
    private double centerX, centerY, centerZ;
    @Nullable private ByteBufferBuilder bytes;
    @Nullable private BufferBuilder builder;
    @Nullable private VertexBuffer buffer;
    private boolean ready, closed;
    private final int cut;
    private final double fixedCenterY;

    /** {@code border} is minX, minZ, maxX, maxZ, inclusive. The whole height, middle from the land. */
    VillageTerrainMesh(int[] border) {
        this(border, VillageBlockMesh.NO_CUT, Double.NaN);
    }

    /** Everything up to {@code cut}; {@code centerY} the middle height to draw around (NaN: from the land). */
    VillageTerrainMesh(int[] border, int cut, double centerY) {
        this.cut = cut;
        this.fixedCenterY = centerY;
        minX = border[0] - MARGIN;
        minZ = border[1] - MARGIN;
        maxX = border[2] + MARGIN;
        maxZ = border[3] + MARGIN;
        int width = maxX - minX + 1, depth = maxZ - minZ + 1;
        int size = Math.max(width, depth) > WIDE_BORDER ? 2 : 1;
        while (ceilDiv(width, size) * (long) ceilDiv(depth, size) > MAX_CELLS) size++;
        cell = size;
        cols = ceilDiv(width, size);
        rows = ceilDiv(depth, size);
        heights = new int[cols * rows];
        colors = new int[cols * rows];
        path = new BitSet(cols * rows);
        centerX = minX + width / 2.0;
        centerZ = minZ + depth / 2.0;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    boolean ready() {
        return ready;
    }

    /** Share of the work done, 0..1, for the "Drawing the village..." line. */
    float progress() {
        int total = heights.length * 2;
        return (sampled + meshed) / (float) total;
    }

    double centerX() { return centerX; }
    double centerY() { return centerY; }
    double centerZ() { return centerZ; }
    int minHeight() { return minHeight; }
    int maxHeight() { return maxHeight; }

    // =========================================================================
    // Questions about the land
    // =========================================================================

    boolean inArea(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    private int cellIndex(int x, int z) {
        return ((z - minZ) / cell) * cols + (x - minX) / cell;
    }

    /** Y of the top of the ground (one above the top block); unloaded land counts as the floor. */
    int topAt(int x, int z) {
        if (!inArea(x, z)) return floorTop();
        int h = heights[cellIndex(x, z)];
        return h == UNLOADED ? floorTop() : h + 1;
    }

    boolean loadedAt(int x, int z) {
        return inArea(x, z) && heights[cellIndex(x, z)] != UNLOADED;
    }

    boolean pathAt(int x, int z) {
        return inArea(x, z) && path.get(cellIndex(x, z));
    }

    /** The dark floor where chunks are not loaded: level with the lowest loaded ground. */
    private int floorTop() {
        return minHeight == Integer.MAX_VALUE ? 64 : minHeight;
    }

    // =========================================================================
    // Building, a slice per frame
    // =========================================================================

    /** Does one frame's share of the work. Render thread. */
    void step(ClientLevel level) {
        if (ready || closed) return;
        if (sampled < heights.length) {
            sample(level);
            return;
        }
        if (builder == null) startMesh(level);
        if (meshed < heights.length) {
            mesh();
            return;
        }
        upload();
    }

    private void sample(ClientLevel level) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int budget = COLUMNS_PER_FRAME;
        int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
        LevelChunk chunk = null;
        int bottom = level.getMinBuildHeight();
        while (sampled < heights.length && budget > 0) {
            int index = sampled++;
            int x0 = minX + (index % cols) * cell, z0 = minZ + (index / cols) * cell;
            int best = UNLOADED, bestX = 0, bestZ = 0;
            LevelChunk bestChunk = null;
            for (int z = z0; z < z0 + cell && z <= maxZ; z++) {
                for (int x = x0; x < x0 + cell && x <= maxX; x++) {
                    budget--;
                    if ((x >> 4) != lastChunkX || (z >> 4) != lastChunkZ) {
                        lastChunkX = x >> 4;
                        lastChunkZ = z >> 4;
                        chunk = level.getChunkSource().getChunk(lastChunkX, lastChunkZ, ChunkStatus.FULL, false);
                    }
                    if (chunk == null) continue;
                    int h = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                    if (h > cut) {
                        // Under the cut: the first block below it, through cave air.
                        h = cut;
                        int end = Math.max(bottom, cut - CUT_SEARCH);
                        while (h > end && chunk.getBlockState(pos.set(x, h, z)).isAir()) h--;
                    }
                    if (h < bottom) continue;
                    if (chunk.getBlockState(pos.set(x, h, z)).is(Blocks.DIRT_PATH)) path.set(index);
                    if (h > best) {
                        best = h;
                        bestX = x;
                        bestZ = z;
                        bestChunk = chunk;
                    }
                }
            }
            heights[index] = best;
            if (best == UNLOADED) continue;
            minHeight = Math.min(minHeight, best);
            maxHeight = Math.max(maxHeight, best);
            colors[index] = colorAt(level, bestChunk, pos, bestX, best, bestZ, bottom);
        }
    }

    /** The map colour of the top block, looking down past blocks the map leaves out (glass, air). */
    private static int colorAt(ClientLevel level, LevelChunk chunk, BlockPos.MutableBlockPos pos, int x, int top,
                               int z, int bottom) {
        for (int y = top; y >= Math.max(bottom, top - COLOR_DEPTH); y--) {
            BlockState state = chunk.getBlockState(pos.set(x, y, z));
            MapColor color = state.getMapColor(level, pos);
            if (color != MapColor.NONE) return 0xFF000000 | color.col;
        }
        return UNKNOWN_COLOR;
    }

    private void startMesh(ClientLevel level) {
        if (minHeight == Integer.MAX_VALUE) {
            // Nothing loaded at all: a flat floor at the camera's height.
            minHeight = maxHeight = Math.max(level.getMinBuildHeight(), 64);
        }
        centerY = Double.isNaN(fixedCenterY) ? (minHeight + maxHeight + 1) / 2.0 : fixedCenterY;
        // Top quads always, sides roughly one per cell; the builder grows if a steep village needs more.
        bytes = new ByteBufferBuilder(heights.length * 8 * DefaultVertexFormat.POSITION_COLOR.getVertexSize());
        builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
    }

    private void mesh() {
        BufferBuilder out = builder;
        if (out == null) return;
        int end = Math.min(heights.length, meshed + CELLS_PER_FRAME);
        for (int index = meshed; index < end; index++) {
            int ci = index % cols, cj = index / cols;
            int h = heights[index];
            int top = h == UNLOADED ? floorTop() : h + 1;
            int color = h == UNLOADED ? FLOOR_COLOR : colors[index];
            float x0 = (float) (minX + ci * cell - centerX);
            float x1 = (float) (Math.min(minX + (ci + 1) * cell, maxX + 1) - centerX);
            float z0 = (float) (minZ + cj * cell - centerZ);
            float z1 = (float) (Math.min(minZ + (cj + 1) * cell, maxZ + 1) - centerZ);
            float y = (float) (top - centerY);
            out.addVertex(x0, y, z0).setColor(color);
            out.addVertex(x0, y, z1).setColor(color);
            out.addVertex(x1, y, z1).setColor(color);
            out.addVertex(x1, y, z0).setColor(color);
            // Sides down to each lower neighbour, darker the way the world shades them.
            int eastWest = shade(color, 0.72), northSouth = shade(color, 0.84);
            side(out, top, neighbourTop(ci - 1, cj), x0, z0, x0, z1, eastWest);
            side(out, top, neighbourTop(ci + 1, cj), x1, z0, x1, z1, eastWest);
            side(out, top, neighbourTop(ci, cj - 1), x0, z0, x1, z0, northSouth);
            side(out, top, neighbourTop(ci, cj + 1), x0, z1, x1, z1, northSouth);
        }
        meshed = end;
    }

    private int neighbourTop(int ci, int cj) {
        if (ci < 0 || cj < 0 || ci >= cols || cj >= rows) return floorTop() - 1;
        int h = heights[cj * cols + ci];
        return h == UNLOADED ? floorTop() : h + 1;
    }

    private void side(BufferBuilder out, int top, int below, float ax, float az, float bx, float bz, int color) {
        if (below >= top) return;
        float y1 = (float) (top - centerY), y0 = (float) (below - centerY);
        out.addVertex(ax, y0, az).setColor(color);
        out.addVertex(ax, y1, az).setColor(color);
        out.addVertex(bx, y1, bz).setColor(color);
        out.addVertex(bx, y0, bz).setColor(color);
    }

    static int shade(int color, double factor) {
        int r = (int) (((color >> 16) & 255) * factor);
        int g = (int) (((color >> 8) & 255) * factor);
        int b = (int) ((color & 255) * factor);
        return (color & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private void upload() {
        BufferBuilder out = builder;
        builder = null;
        try {
            if (out != null) buffer = VillageModelView.upload(null, out, VertexBuffer.Usage.STATIC);
        } finally {
            if (bytes != null) bytes.close();
            bytes = null;
            ready = true;
        }
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    void draw(Matrix4f modelView, Matrix4f projection) {
        VillageModelView.drawBuffer(buffer, modelView, projection);
    }

    /** Frees the GPU buffer and the half-written native buffer, if any. */
    void close() {
        if (buffer != null) buffer.close();
        buffer = null;
        builder = null;
        if (bytes != null) bytes.close();
        bytes = null;
        closed = true;
    }
}
