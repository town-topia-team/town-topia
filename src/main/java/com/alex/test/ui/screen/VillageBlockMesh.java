package com.alex.test.ui.screen;

import com.alex.test.ui.BuilderDeskMenu;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The village with its real blocks for the Builder's desk: textures, grass and water
 * tints, plants and fences, shaded the way the world shades them, like the storage
 * room's view.
 *
 * <p>Built like the world's chunks: in pieces of {@link #PIECE} by {@link #PIECE} columns,
 * each run once through the world's own tesselator into its own static GPU buffers (one
 * per layer). Building is spread over frames with a time budget ({@link #FRAME_NANOS}),
 * from the middle outward, so if the memory cap ({@link #MAX_BYTES}) is reached the
 * outskirts are what is left out. A piece whose chunks the client does not hold (beyond
 * the view distance, or still arriving) waits and is built once they come.
 *
 * <p>Only what the camera sees from above is built, one skin: each column from just under
 * the lowest ground around it up to its top, skipping blocks buried on every side. Caves
 * and mines under the ground are not built (nothing shows them from above). The client gets
 * no "ground without leaves" height from the server, so ground is found here: down from the
 * top through leaves, plants and water to the first full block. Registered buildings are
 * built from their floor.
 *
 * <p>Layers: with a cut height ({@link #setCut}), everything above it is air and the skin
 * is taken from there down, so rock cut open at the cut shows its top and a cave or a
 * house floor under it shows. A new cut rebuilds the pieces nearest the view's middle
 * first; each keeps its old look until its new one is ready.
 *
 * <p>Textures are mipmapped: zoomed out, a block is a few pixels and full-size textures
 * sparkled as the view turned. Chests, beds and signs draw as block entities in the world
 * and are not here. Light is full bright, so a village at night still shows.
 */
final class VillageBlockMesh {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Areas with more columns than this keep the coloured map (memory and build time). */
    static final int MAX_COLUMNS = 512 * 512;
    /** Columns per side of one piece (two chunks). */
    private static final int PIECE = 32;
    /** Render-thread time spent building per frame. */
    private static final long FRAME_NANOS = 6_000_000L;
    /** How often pieces waiting for chunks look again. */
    private static final long RETRY_NANOS = 1_000_000_000L;
    /** No cut: the whole height, the plain surface. */
    static final int NO_CUT = Integer.MAX_VALUE;
    /** How far under a cut the top is looked for through air (a big cave). */
    private static final int CUT_SEARCH = 64;
    /** Native memory the buffers may take before the rest is left out. */
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    /** Blocks under the lowest ground around a column that are still built (cut edges, banks). */
    private static final int BELOW_GROUND = 2;
    /** How far down from the top the ground is looked for (deep water, tall glass). */
    private static final int GROUND_SEARCH = 48;
    private static final int UNSET = Integer.MIN_VALUE + 1;
    private static final int MISSING = Integer.MIN_VALUE;

    private static final RenderType SOLID = mapType("solid", RenderStateShard.RENDERTYPE_SOLID_SHADER,
            RenderStateShard.BLOCK_SHEET_MIPPED, RenderStateShard.NO_TRANSPARENCY);
    private static final RenderType CUTOUT_MIPPED = mapType("cutout_mipped",
            RenderStateShard.RENDERTYPE_CUTOUT_MIPPED_SHADER, RenderStateShard.BLOCK_SHEET_MIPPED,
            RenderStateShard.NO_TRANSPARENCY);
    private static final RenderType CUTOUT = mapType("cutout", RenderStateShard.RENDERTYPE_CUTOUT_SHADER,
            RenderStateShard.BLOCK_SHEET, RenderStateShard.NO_TRANSPARENCY);
    private static final RenderType TRANSLUCENT = mapType("translucent",
            RenderStateShard.RENDERTYPE_TRANSLUCENT_SHADER, RenderStateShard.BLOCK_SHEET_MIPPED,
            RenderStateShard.TRANSLUCENT_TRANSPARENCY);
    /** Drawn in this order; translucent last. */
    private static final RenderType[] LAYERS = {SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT};

    /** The chunk layers' look, without culling: the view's camera matrix mirrors, so winding flips. */
    private static RenderType mapType(String name, RenderStateShard.ShaderStateShard shader,
                                      RenderStateShard.TextureStateShard texture,
                                      RenderStateShard.TransparencyStateShard transparency) {
        return RenderType.create("dynamicvillages_village_map_" + name, DefaultVertexFormat.BLOCK,
                VertexFormat.Mode.QUADS, 262144, false, transparency != RenderStateShard.NO_TRANSPARENCY,
                RenderType.CompositeState.builder()
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setShaderState(shader)
                        .setTextureState(texture)
                        .setTransparencyState(transparency)
                        .setCullState(RenderStateShard.NO_CULL)
                        .createCompositeState(false));
    }

    /** One square of the map (inclusive world columns) and its GPU buffers once built. */
    private static final class Piece {
        final int x0, z0, x1, z1;
        final VertexBuffer[] buffers = new VertexBuffer[LAYERS.length];
        long bytes;

        Piece(int x0, int z0, int x1, int z1) {
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
        }
    }

    private final int minX, minZ, maxX, maxZ, width, columns;
    private final float centerX, centerY, centerZ;
    private final List<BuilderDeskMenu.BuildingRow> buildings;
    private int cut;

    // Per column, filled when first needed: ground (first air above the first full block
    // under the top) and top (first air above everything). UNSET until read.
    private final int[] ground, tops;
    private final Piece[] pieces;
    private final ArrayDeque<Piece> queue = new ArrayDeque<>();
    private final List<Piece> waiting = new ArrayList<>();
    private int built;
    private long nextRetry;

    // The piece being built: its next column and height, and the buffers being written.
    @Nullable private Piece current;
    private int pieceColumn, y = Integer.MIN_VALUE, columnTop;
    private final ByteBufferBuilder[] bytes = new ByteBufferBuilder[LAYERS.length];
    private final BufferBuilder[] builders = new BufferBuilder[LAYERS.length];
    private final MapConsumer[] consumers = new MapConsumer[LAYERS.length];

    private boolean ready, shown, closed, cutShort;
    private long totalBytes;
    private long startNanos;
    private int frames;
    @Nullable private AreaLevel area;
    private final PoseStack pose = new PoseStack();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final RandomSource random = RandomSource.create();

    /** The terrain's area and middle, so both meshes line up; it must be {@link #fits fit}. */
    VillageBlockMesh(VillageTerrainMesh terrain, List<BuilderDeskMenu.BuildingRow> buildings, int cut) {
        minX = terrain.minX;
        minZ = terrain.minZ;
        maxX = terrain.maxX;
        maxZ = terrain.maxZ;
        width = maxX - minX + 1;
        columns = width * (maxZ - minZ + 1);
        centerX = (float) terrain.centerX();
        centerY = (float) terrain.centerY();
        centerZ = (float) terrain.centerZ();
        this.buildings = buildings;
        this.cut = cut;
        ground = new int[columns];
        tops = new int[columns];
        Arrays.fill(ground, UNSET);
        Arrays.fill(tops, UNSET);
        List<Piece> list = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z += PIECE) {
            for (int x = minX; x <= maxX; x += PIECE) {
                list.add(new Piece(x, z, Math.min(maxX, x + PIECE - 1), Math.min(maxZ, z + PIECE - 1)));
            }
        }
        // From the middle outward: when memory runs out, the outskirts are what is missing.
        double midX = (minX + maxX) / 2.0, midZ = (minZ + maxZ) / 2.0;
        list.sort(Comparator.comparingDouble(p -> {
            double dx = (p.x0 + p.x1) / 2.0 - midX, dz = (p.z0 + p.z1) / 2.0 - midZ;
            return dx * dx + dz * dz;
        }));
        pieces = list.toArray(new Piece[0]);
        queue.addAll(list);
    }

    /**
     * Shows everything up to {@code cut} (or the whole height with {@link #NO_CUT}).
     * Every piece is built again, the ones nearest (nearX, nearZ) first; until then each
     * keeps its old look.
     */
    void setCut(int cut, double nearX, double nearZ) {
        if (cut == this.cut || closed) return;
        this.cut = cut;
        area = null;
        dropCurrent();
        Arrays.fill(ground, UNSET);
        Arrays.fill(tops, UNSET);
        queue.clear();
        waiting.clear();
        cutShort = false;
        built = 0;
        ready = false;
        frames = 0;
        Piece[] order = pieces.clone();
        Arrays.sort(order, Comparator.comparingDouble(p -> {
            double dx = (p.x0 + p.x1) / 2.0 - nearX, dz = (p.z0 + p.z1) / 2.0 - nearZ;
            return dx * dx + dz * dz;
        }));
        queue.addAll(Arrays.asList(order));
    }

    int cut() {
        return cut;
    }

    /** Throws away the half-built piece, if any. */
    private void dropCurrent() {
        current = null;
        for (int i = 0; i < LAYERS.length; i++) {
            if (bytes[i] != null) bytes[i].close();
            bytes[i] = null;
            builders[i] = null;
            consumers[i] = null;
        }
    }

    static boolean fits(VillageTerrainMesh terrain) {
        long area = (long) (terrain.maxX - terrain.minX + 1) * (terrain.maxZ - terrain.minZ + 1);
        if (area <= MAX_COLUMNS) return true;
        LOGGER.info("[VillageMap] no block detail: {}x{} columns is over the {} limit; coloured map only",
                terrain.maxX - terrain.minX + 1, terrain.maxZ - terrain.minZ + 1, MAX_COLUMNS);
        return false;
    }

    /** Every piece whose chunks are here is built (pieces may still wait for chunks). */
    boolean ready() {
        return ready;
    }

    /** Was ready once: worth drawing, even while a new cut is being built. */
    boolean shown() {
        return shown;
    }

    /** Share of the reachable pieces built, 0..1. */
    float progress() {
        int reachable = pieces.length - waiting.size();
        return reachable <= 0 ? 1 : Math.min(1, built / (float) reachable);
    }

    /** Pieces whose chunks the client does not hold (too far from the player, or still arriving). */
    int waitingPieces() {
        return cutShort ? 0 : waiting.size();
    }

    // =========================================================================
    // Building, a slice per frame
    // =========================================================================

    /** Does one frame's share of the work. Render thread. */
    void step(ClientLevel level) {
        if (closed) return;
        long now = System.nanoTime();
        if (area == null || area.world() != level) area = new AreaLevel(level, minX, minZ, maxX, maxZ, cut);
        if (frames++ == 0) startNanos = now;
        if (!waiting.isEmpty() && !cutShort && now >= nextRetry) {
            nextRetry = now + RETRY_NANOS;
            for (Iterator<Piece> it = waiting.iterator(); it.hasNext(); ) {
                Piece piece = it.next();
                if (chunksHere(level, piece)) {
                    it.remove();
                    queue.add(piece);
                }
            }
        }
        long deadline = now + FRAME_NANOS;
        while (System.nanoTime() < deadline) {
            if (current == null && !nextPiece(level)) break;
            if (buildSome(level, deadline)) finishPiece();
        }
        if (!ready && current == null && queue.isEmpty()) {
            ready = true;
            shown = true;
            LOGGER.info("[VillageMap] block detail{}: {}x{} columns, {} of {} pieces, {} MB, built in {} ms over {} frames{}{}",
                    cut == NO_CUT ? "" : " (cut at Y " + cut + ")",
                    width, columns / width, built, pieces.length, totalBytes >> 20,
                    (System.nanoTime() - startNanos) / 1_000_000, frames,
                    waiting.isEmpty() ? "" : ", " + waiting.size() + " waiting for chunks",
                    cutShort ? ", CUT SHORT at the memory cap" : "");
        }
    }

    /** Starts the next piece whose chunks are here; the others wait. False when none is left. */
    private boolean nextPiece(ClientLevel level) {
        while (!queue.isEmpty()) {
            Piece piece = queue.poll();
            if (cutShort) {
                // Left out: no old look from another cut may stay in its place.
                clearBuffers(piece);
                continue;
            }
            if (!chunksHere(level, piece)) {
                waiting.add(piece);
                continue;
            }
            current = piece;
            pieceColumn = 0;
            y = Integer.MIN_VALUE;
            for (int i = 0; i < LAYERS.length; i++) {
                bytes[i] = new ByteBufferBuilder(i == 0 ? 256 << 10 : 32 << 10);
                builders[i] = new BufferBuilder(bytes[i], VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
                consumers[i] = new MapConsumer(builders[i]);
            }
            return true;
        }
        return false;
    }

    private static boolean chunksHere(ClientLevel level, Piece piece) {
        for (int cz = piece.z0 >> 4; cz <= piece.z1 >> 4; cz++) {
            for (int cx = piece.x0 >> 4; cx <= piece.x1 >> 4; cx++) {
                if (level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false) == null) return false;
            }
        }
        return true;
    }

    /** Builds the current piece until the deadline; true when it is done. */
    private boolean buildSome(ClientLevel level, long deadline) {
        Piece piece = current;
        if (piece == null) return false;
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer tesselator = dispatcher.getModelRenderer();
        int pieceWidth = piece.x1 - piece.x0 + 1;
        int pieceColumns = pieceWidth * (piece.z1 - piece.z0 + 1);
        int checks = 0;
        while (pieceColumn < pieceColumns) {
            int x = piece.x0 + pieceColumn % pieceWidth, z = piece.z0 + pieceColumn / pieceWidth;
            if (y == Integer.MIN_VALUE) {
                columnTop = topAt(level, x, z);
                y = columnTop == MISSING ? Integer.MAX_VALUE : bottomOf(level, x, z);
            }
            for (; y <= columnTop; y++) {
                drawBlock(dispatcher, tesselator, x, y, z);
                if ((++checks & 255) == 0 && System.nanoTime() > deadline) {
                    y++;
                    if (y > columnTop) nextColumn();
                    return false;
                }
            }
            nextColumn();
        }
        return true;
    }

    private void nextColumn() {
        pieceColumn++;
        y = Integer.MIN_VALUE;
    }

    private void finishPiece() {
        Piece piece = current;
        current = null;
        if (piece == null) return;
        long vertices = 0;
        clearBuffers(piece); // the old look (another cut) goes now that the new one is here
        for (int i = 0; i < LAYERS.length; i++) {
            vertices += consumers[i].vertices;
            try {
                piece.buffers[i] = VillageModelView.upload(null, builders[i], VertexBuffer.Usage.STATIC);
            } finally {
                bytes[i].close();
                bytes[i] = null;
                builders[i] = null;
                consumers[i] = null;
            }
        }
        built++;
        piece.bytes = vertices * DefaultVertexFormat.BLOCK.getVertexSize();
        totalBytes += piece.bytes;
        if (totalBytes > MAX_BYTES) cutShort = true; // the pieces still to come stay out
    }

    private void clearBuffers(Piece piece) {
        for (int i = 0; i < LAYERS.length; i++) {
            if (piece.buffers[i] != null) piece.buffers[i].close();
            piece.buffers[i] = null;
        }
        totalBytes -= piece.bytes;
        piece.bytes = 0;
    }

    // =========================================================================
    // Heights
    // =========================================================================

    private int index(int x, int z) {
        return (z - minZ) * width + (x - minX);
    }

    private boolean inArea(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    /** First air above everything in the column, or MISSING when its chunk is not here. */
    private int topAt(ClientLevel level, int x, int z) {
        if (!inArea(x, z)) return MISSING;
        int i = index(x, z);
        if (tops[i] == UNSET) sample(level, x, z, i);
        return tops[i] == UNSET ? MISSING : tops[i];
    }

    /** First air above the ground (the first full block under the top), or MISSING. */
    private int groundAt(ClientLevel level, int x, int z) {
        if (!inArea(x, z)) return MISSING;
        int i = index(x, z);
        if (ground[i] == UNSET) sample(level, x, z, i);
        return ground[i] == UNSET ? MISSING : ground[i];
    }

    /** Reads one column; leaves it UNSET when its chunk is not here, so it is read again later. */
    private void sample(ClientLevel level, int x, int z, int i) {
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
        if (chunk == null) return;
        // The client holds only these two heights; "without leaves" is server only.
        int motion = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
        int minBuild = level.getMinBuildHeight();
        // The top block: the highest one, or under a cut the first one below it (through cave air).
        int top = Math.min(Math.max(motion, surface) - 1, cut);
        int searchEnd = Math.max(minBuild, top - CUT_SEARCH);
        while (top > searchEnd && chunk.getBlockState(probe.set(x, top, z)).isAir()) top--;
        tops[i] = top + 1;
        int bottom = Math.max(minBuild, top - GROUND_SEARCH);
        int g = top;
        while (g > bottom) {
            BlockState state = chunk.getBlockState(probe.set(x, g, z));
            if (!(state.getBlock() instanceof LeavesBlock) && state.getFluidState().isEmpty()
                    && state.isSolidRender(level, probe)) {
                break;
            }
            g--;
        }
        ground[i] = g + 1;
    }

    /**
     * The lowest block built in a column: just under the lowest ground around it, so banks
     * and cut edges show their sides; in a registered building, its floor.
     */
    private int bottomOf(ClientLevel level, int x, int z) {
        int low = Integer.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int g = groundAt(level, x + dx, z + dz);
                if (g != MISSING) low = Math.min(low, g);
            }
        }
        int bottom = low == Integer.MAX_VALUE ? Integer.MAX_VALUE : low - BELOW_GROUND;
        for (BuilderDeskMenu.BuildingRow row : buildings) {
            // Only a building that reaches up into what is shown (a cut can be under it).
            if (x >= row.shellMin().getX() && x <= row.shellMax().getX()
                    && z >= row.shellMin().getZ() && z <= row.shellMax().getZ() && row.shellMin().getY() <= cut) {
                bottom = Math.min(bottom, row.shellMin().getY());
            }
        }
        return bottom;
    }

    // =========================================================================
    // Blocks
    // =========================================================================

    private void drawBlock(BlockRenderDispatcher dispatcher, ModelBlockRenderer tesselator, int x, int y, int z) {
        AreaLevel level = area;
        if (level == null) return;
        pos.set(x, y, z);
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return;
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            MapConsumer out = consumers[layerFor(ItemBlockRenderTypes.getRenderLayer(fluid))];
            // The liquid renderer writes positions inside the chunk section.
            out.offset((x & ~15) - centerX, (y & ~15) - centerY, (z & ~15) - centerZ);
            dispatcher.renderLiquid(pos, level, out, state, fluid);
            out.offset(0, 0, 0);
        }
        if (state.getRenderShape() != RenderShape.MODEL || buried(level, state)) return;
        BakedModel model = dispatcher.getBlockModel(state);
        long seed = state.getSeed(pos);
        pose.pushPose();
        pose.translate(x - centerX, y - centerY, z - centerZ);
        for (RenderType type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            tesselator.tesselateBlock(level, model, state, pos, pose, consumers[layerFor(type)], true, random, seed,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, type);
        }
        pose.popPose();
    }

    /**
     * A full block with full blocks on every side shows nothing; most of the ground is this.
     * Leaves inside a tree are left out too: they draw every face, and a wood of them is
     * millions of hidden squares.
     */
    private boolean buried(AreaLevel level, BlockState state) {
        boolean leaves = state.getBlock() instanceof LeavesBlock;
        if (!leaves && !state.isSolidRender(level, pos)) return false;
        for (Direction direction : Direction.values()) {
            near.setWithOffset(pos, direction);
            BlockState next = level.getBlockState(near);
            if (!next.isSolidRender(level, near) && !(leaves && next.getBlock() instanceof LeavesBlock)) return false;
        }
        return true;
    }

    private static int layerFor(RenderType chunkLayer) {
        if (chunkLayer == RenderType.solid()) return 0;
        if (chunkLayer == RenderType.cutoutMipped()) return 1;
        if (chunkLayer == RenderType.translucent() || chunkLayer == RenderType.tripwire()) return 3;
        return 2;
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    /** Draws every built piece, layer by layer; leaves culling off and depth on, as the view expects. */
    void draw(Matrix4f modelView, Matrix4f projection) {
        for (int i = 0; i < LAYERS.length; i++) {
            LAYERS[i].setupRenderState();
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                // The chunk shaders add a section offset; ours are already in place.
                if (shader.CHUNK_OFFSET != null) shader.CHUNK_OFFSET.set(0f, 0f, 0f);
                for (Piece piece : pieces) {
                    VertexBuffer buffer = piece.buffers[i];
                    if (buffer == null || buffer.isInvalid() || buffer.getFormat() == null) continue;
                    buffer.bind();
                    buffer.drawWithShader(modelView, projection, shader);
                }
                VertexBuffer.unbind();
            }
            LAYERS[i].clearRenderState();
        }
        RenderSystem.disableCull();
        RenderSystem.enableDepthTest();
    }

    /** Frees the GPU buffers and any half-written native buffers. Safe to call twice. */
    void close() {
        closed = true;
        for (Piece piece : pieces) clearBuffers(piece);
        dropCurrent();
        area = null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Writes into a layer's buffer at full brightness (a night-time village still shows),
     * moving positions by an offset (the liquid renderer's chunk-section positions).
     */
    private static final class MapConsumer implements VertexConsumer {
        private final BufferBuilder inner;
        private float ox, oy, oz;
        long vertices;

        MapConsumer(BufferBuilder inner) {
            this.inner = inner;
        }

        void offset(float x, float y, float z) {
            ox = x;
            oy = y;
            oz = z;
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x + ox, y + oy, z + oz);
            vertices++;
            return this;
        }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) {
            inner.setColor(r, g, b, a);
            return this;
        }
        @Override public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }
        @Override public VertexConsumer setUv1(int u, int v) {
            return this; // the block format has no overlay
        }
        @Override public VertexConsumer setUv2(int u, int v) {
            inner.setLight(LightTexture.FULL_BRIGHT);
            return this;
        }
        @Override public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }

    /**
     * The world, cut off at the drawn area's sides and above the cut: past them there is only
     * air, so blocks on the edge show their sides like a model on a table, and rock cut open
     * by the Layers shows its top.
     */
    private record AreaLevel(ClientLevel world, int minX, int minZ, int maxX, int maxZ, int cut)
            implements BlockAndTintGetter {
        private boolean inside(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getZ() >= minZ && pos.getZ() <= maxZ
                    && pos.getY() <= cut;
        }
        @Override public BlockState getBlockState(BlockPos pos) {
            return inside(pos) ? world.getBlockState(pos) : Blocks.AIR.defaultBlockState();
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return inside(pos) ? world.getFluidState(pos) : Fluids.EMPTY.defaultFluidState();
        }
        @Nullable @Override public BlockEntity getBlockEntity(BlockPos pos) {
            return inside(pos) ? world.getBlockEntity(pos) : null;
        }
        @Override public float getShade(Direction direction, boolean shade) {
            return world.getShade(direction, shade);
        }
        @Override public LevelLightEngine getLightEngine() {
            return world.getLightEngine();
        }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
            return world.getBlockTint(pos, resolver);
        }
        @Override public int getHeight() {
            return world.getHeight();
        }
        @Override public int getMinBuildHeight() {
            return world.getMinBuildHeight();
        }
    }
}
