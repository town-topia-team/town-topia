package com.alex.test.ui.screen;

import com.alex.test.network.BuilderDeskPayloads;
import com.alex.test.network.BuilderPlanPayloads;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.village.villagers.builder.blueprint.Blueprint;
import com.alex.test.village.villagers.builder.blueprint.BlueprintReader;
import com.alex.test.village.villagers.builder.copy.BuildingCopier;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The Builder's plan for copying: right click a building in the world, the server finds
 * its shape (the desk's own copy), and the box shows in the world with green columns for
 * what the copy holds. Looking at a side of the box and sneak + scroll grows or shrinks
 * that side; sneak + right click names and saves it ({@link BuilderPlanSaveScreen}).
 */
final class PlanCopyTool {
    /** Ticks between two copy requests, a little over the server's own wait. */
    private static final int SEND_GAP = 4;
    private static final int LINE = 0xFF7FE08A, SIDE = 0x506BAF72;
    private static final String[] SIDE_NAMES = {"west", "east", "bottom", "top", "north", "south"};

    final int villagerId;
    @Nullable private BlockPos pick;
    /** Box copy: the second corner of the copy sent (null for a building copy). */
    @Nullable private BlockPos boxEnd;
    /** Box copy: the first corner clicked, waiting for the second. */
    @Nullable private BlockPos corner;
    private final int[] grow = new int[BuildingCopier.SIDES];
    private int requestCounter, pendingRequest = -1, sendWait;
    private boolean resend;
    @Nullable private BuilderDeskPayloads.CopyResult header;
    @Nullable private byte[] bytes;
    @Nullable private String message;
    private boolean messageBad;
    /** The side of the box the player looks at, in {@link BuildingCopier#SIDES} order; -1: none. */
    private int lookSide = -1;

    // What the copy holds, as green column boxes.
    private final BlueprintGhost ghost = new BlueprintGhost();
    @Nullable private CompletableFuture<Blueprint> parsing;
    private boolean boxesBuilt;
    @Nullable private VertexBuffer boxes, frame;
    private boolean frameDirty;
    private int frameSide = -2;

    PlanCopyTool(int villagerId) {
        this.villagerId = villagerId;
    }

    @Nullable byte[] bytes() {
        return canSave() ? bytes : null;
    }

    @Nullable String label() {
        return header == null ? null : "Building".equals(header.label()) ? "Copied building" : header.label();
    }

    boolean canSave() {
        return bytes != null && header != null && pendingRequest < 0 && !resend;
    }

    // =========================================================================
    // Controls
    // =========================================================================

    /** Left click: the first corner of a box copy. */
    void cornerLooked(Minecraft minecraft) {
        BlockHitResult hit = look(minecraft);
        if (hit == null) return;
        corner = hit.getBlockPos();
        message = null;
    }

    /** Box copy: the first corner, while the second is not clicked yet. */
    @Nullable
    BlockPos corner() {
        return corner;
    }

    /** Right click: copy what is at the block looked at, or the box from the first corner to it. */
    void pickLooked(Minecraft minecraft) {
        BlockHitResult hit = look(minecraft);
        if (hit == null) return;
        if (corner != null) {
            pick = corner;
            boxEnd = hit.getBlockPos();
            corner = null;
        } else {
            pick = hit.getBlockPos();
            boxEnd = null;
        }
        Arrays.fill(grow, 0);
        header = null;
        bytes = null;
        dropShape();
        message = boxEnd != null ? "Copying the box..." : "Finding the building...";
        messageBad = false;
        pendingRequest = -1;
        resend = false;
        request();
    }

    /** + / -: the side looked at moves out one block, or in. */
    void resize(int direction) {
        if (header == null) return;
        if (lookSide < 0) {
            message = "Look at a side of the box first.";
            messageBad = true;
            return;
        }
        grow[lookSide] += direction;
        request();
    }

    /** Arrows and Page Up / Down: the whole box one block that way, same size. */
    void move(int dx, int dy, int dz) {
        if (header == null) return;
        int[] by = {dx, dy, dz};
        for (int axis = 0; axis < 3; axis++) {
            // Sides per axis: low (west, bottom, north) then high.
            grow[axis * 2] -= by[axis];
            grow[axis * 2 + 1] += by[axis];
        }
        request();
    }

    private void request() {
        if (pick == null) return;
        if (pendingRequest >= 0 || sendWait > 0) {
            resend = true;
            return;
        }
        pendingRequest = ++requestCounter;
        sendWait = SEND_GAP;
        PacketDistributor.sendToServer(new BuilderPlanPayloads.PlanCopyScan(pendingRequest, pick, grow.clone(), boxEnd));
    }

    @Nullable
    private static BlockHitResult look(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) return null;
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 end = eye.add(minecraft.player.getViewVector(1.0F).scale(PlanWorldGhost.REACH));
        BlockHitResult hit = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, minecraft.player));
        return hit.getType() == HitResult.Type.MISS ? null : hit;
    }

    // =========================================================================
    // Ticking
    // =========================================================================

    void tick(Minecraft minecraft) {
        if (sendWait > 0) sendWait--;
        BuilderDeskMenu.CopyInbox.Finished done = BuilderPlanClient.copyInbox().take();
        if (done != null && done.header().requestId() == pendingRequest) {
            pendingRequest = -1;
            receive(done);
        }
        if (resend && pendingRequest < 0 && sendWait == 0) {
            resend = false;
            request();
        }
        if (parsing != null && parsing.isDone()) {
            Blueprint blueprint = parsing.isCompletedExceptionally() || parsing.isCancelled() ? null : parsing.join();
            parsing = null;
            if (blueprint != null) ghost.setSource(blueprint, null);
        }
        if (ghost.poll()) dropBoxes();
        int side = lookedSide(minecraft);
        if (side != lookSide) {
            lookSide = side;
            frameDirty = true;
        }
    }

    private void receive(BuilderDeskMenu.CopyInbox.Finished done) {
        BuilderDeskPayloads.CopyResult result = done.header();
        if (result.error() != null) {
            message = result.error();
            messageBad = true;
            // A side moved too far: back to the copy still shown.
            if (header != null) System.arraycopy(header.grow(), 0, grow, 0, grow.length);
            return;
        }
        header = result;
        bytes = done.bytes();
        System.arraycopy(result.grow(), 0, grow, 0, grow.length);
        message = !result.cut().isEmpty() ? result.cut() : result.noFloor() ? BuilderDeskBuildTab.NO_FLOOR_MESSAGE : null;
        messageBad = false;
        frameDirty = true;
        dropShape();
        byte[] data = bytes;
        String name = result.label();
        parsing = CompletableFuture.supplyAsync(() -> {
            try {
                return BlueprintReader.read(data, name);
            } catch (Exception e) {
                return null;
            }
        }, Util.backgroundExecutor());
    }

    /** The box side the look ray meets first (or leaves through, from inside); -1 when it misses. */
    private int lookedSide(Minecraft minecraft) {
        if (header == null || minecraft.player == null) return -1;
        Vec3 eye = minecraft.player.getEyePosition();
        Vec3 dir = minecraft.player.getViewVector(1.0F);
        double[] min = {header.origin().getX(), header.origin().getY(), header.origin().getZ()};
        double[] max = {min[0] + header.sizeX(), min[1] + header.sizeY(), min[2] + header.sizeZ()};
        double[] o = {eye.x, eye.y, eye.z}, d = {dir.x, dir.y, dir.z};
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        int nearSide = -1, farSide = -1;
        for (int axis = 0; axis < 3; axis++) {
            // Sides per axis: x -> west/east, y -> bottom/top, z -> north/south.
            int lowSide = axis * 2, highSide = axis * 2 + 1;
            if (Math.abs(d[axis]) < 1e-9) {
                if (o[axis] < min[axis] || o[axis] > max[axis]) return -1;
                continue;
            }
            double t1 = (min[axis] - o[axis]) / d[axis], t2 = (max[axis] - o[axis]) / d[axis];
            int s1 = lowSide, s2 = highSide;
            if (t1 > t2) {
                double t = t1; t1 = t2; t2 = t;
                int s = s1; s1 = s2; s2 = s;
            }
            if (t1 > near) { near = t1; nearSide = s1; }
            if (t2 < far) { far = t2; farSide = s2; }
        }
        if (near > far || far < 0) return -1;
        return near > 0 ? nearSide : farSide;
    }

    // =========================================================================
    // The line over the hotbar
    // =========================================================================

    PlanHud.Content hud(boolean more) {
        PlanHud.Content out = new PlanHud.Content("Copy a building");
        String sneak = PlanHud.sneak(), help = PlanHud.cap(BuilderPlanClient.HELP);
        if (pick == null) {
            if (corner != null) {
                out.status(Component.literal("First corner set"));
                out.row(0xFF7FE08A, "Second corner: copy", PlanHud.RIGHT);
                out.row("Redo first corner", PlanHud.LEFT);
            } else {
                out.status(Component.literal("Look at a building"));
                out.row("Copy it", PlanHud.RIGHT);
                out.row("Copy a box: first corner", PlanHud.LEFT);
            }
        } else if (header == null) {
            out.status(Component.literal(message == null ? "Finding the building..." : message)
                    .withStyle(messageBad ? ChatFormatting.RED : ChatFormatting.GRAY));
            out.row("Pick another", PlanHud.RIGHT);
        } else {
            if (message != null) {
                out.status(Component.literal(message).withStyle(messageBad ? ChatFormatting.RED : ChatFormatting.GOLD));
            } else {
                out.status(Component.literal(canSave() ? String.format(Locale.ROOT, "%d×%d×%d, %,d blocks",
                        header.sizeX(), header.sizeY(), header.sizeZ(), header.blocks()) : "Updating...")
                        .withStyle(canSave() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            }
            if (canSave()) out.row(0xFF7FE08A, "Save", sneak, PlanHud.RIGHT);
            out.anyOf("Move the box", PlanHud.cap(BuilderPlanClient.FORWARD), PlanHud.cap(BuilderPlanClient.LEFT_KEY),
                    PlanHud.cap(BuilderPlanClient.BACK), PlanHud.cap(BuilderPlanClient.RIGHT_KEY));
            out.anyOf(lookSide >= 0 ? "Bigger / smaller: " + SIDE_NAMES[lookSide] + " side" : "Bigger / smaller",
                    PlanHud.cap(BuilderPlanClient.BIGGER), PlanHud.cap(BuilderPlanClient.SMALLER));
            out.row("Pick another", PlanHud.RIGHT);
        }
        if (!more) return out.row("More", help);
        if (header != null) {
            out.anyOf("Box up / down", PlanHud.cap(BuilderPlanClient.UP), PlanHud.cap(BuilderPlanClient.DOWN));
        }
        out.row("Put away", sneak, PlanHud.LEFT);
        out.row("Less", help);
        out.note("Look at a side of the box: it lights up.");
        out.note("Bigger and smaller change that side.");
        out.note("Green: what gets copied.");
        out.note("A box copies everything in it, ground too. Up to 48 blocks each way.");
        return out;
    }

    private static Component gray(String text) {
        return Component.literal(text).withStyle(ChatFormatting.GRAY);
    }

    // =========================================================================
    // Drawing
    // =========================================================================

    void render(Minecraft minecraft, Vec3 cameraPos) {
        if (corner != null) drawCornerBox(minecraft, cameraPos);
        BuilderDeskPayloads.CopyResult shown = header;
        if (shown == null) return;
        BlueprintGhost.Shape shape = ghost.shape();
        if (shape != null && !boxesBuilt) {
            boxesBuilt = true;
            boxes = BlueprintGhost.buildBoxes(shape);
        }
        if (frameDirty || frameSide != lookSide) buildFrame(shown);
        Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix()).translate(
                (float) (shown.origin().getX() - cameraPos.x), (float) (shown.origin().getY() - cameraPos.y),
                (float) (shown.origin().getZ() - cameraPos.z));
        Matrix4f projection = RenderSystem.getProjectionMatrix();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        VillageModelView.drawBuffer(boxes, modelView, projection);
        // The box and its picked side show through walls.
        RenderSystem.disableDepthTest();
        VillageModelView.drawBuffer(sideFace, modelView, projection);
        VillageModelView.drawBuffer(frame, modelView, projection);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** The box from the first corner to the block looked at, through walls. */
    private void drawCornerBox(Minecraft minecraft, Vec3 cameraPos) {
        BlockHitResult hit = look(minecraft);
        BlockPos other = hit != null ? hit.getBlockPos() : corner;
        BlockPos lo = new BlockPos(Math.min(corner.getX(), other.getX()), Math.min(corner.getY(), other.getY()),
                Math.min(corner.getZ(), other.getZ()));
        BlockPos hi = new BlockPos(Math.max(corner.getX(), other.getX()), Math.max(corner.getY(), other.getY()),
                Math.max(corner.getZ(), other.getZ()));
        int max = com.alex.test.village.villagers.builder.copy.BuildingCopier.MAX_BOX_AXIS;
        boolean fits = hi.getX() - lo.getX() < max && hi.getY() - lo.getY() < max && hi.getZ() - lo.getZ() < max;
        com.mojang.blaze3d.vertex.BufferBuilder out = com.mojang.blaze3d.vertex.Tesselator.getInstance().begin(
                com.mojang.blaze3d.vertex.VertexFormat.Mode.DEBUG_LINES,
                com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
        float ox = (float) (lo.getX() - cameraPos.x), oy = (float) (lo.getY() - cameraPos.y), oz = (float) (lo.getZ() - cameraPos.z);
        VillageModelView.outline(out, ox, oy, oz, ox + hi.getX() - lo.getX() + 1, oy + hi.getY() - lo.getY() + 1,
                oz + hi.getZ() - lo.getZ() + 1, fits ? LINE : 0xFFE05A4E);
        com.mojang.blaze3d.vertex.MeshData mesh = out.build();
        if (mesh == null) return;
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(mesh);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private void buildFrame(BuilderDeskPayloads.CopyResult shown) {
        frameDirty = false;
        frameSide = lookSide;
        float x1 = shown.sizeX(), y1 = shown.sizeY(), z1 = shown.sizeZ();
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1024)) {
            BufferBuilder out = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            switch (lookSide) {
                case 0 -> face(out, 0, 0, 0, 0, y1, z1);
                case 1 -> face(out, x1, 0, 0, x1, y1, z1);
                case 2 -> face(out, 0, 0, 0, x1, 0, z1);
                case 3 -> face(out, 0, y1, 0, x1, y1, z1);
                case 4 -> face(out, 0, 0, 0, x1, y1, 0);
                case 5 -> face(out, 0, 0, z1, x1, y1, z1);
                default -> { }
            }
            VertexBuffer faces = VillageModelView.upload(null, out, VertexBuffer.Usage.DYNAMIC);
            try (ByteBufferBuilder lineBytes = new ByteBufferBuilder(1024)) {
                BufferBuilder lines = new BufferBuilder(lineBytes, VertexFormat.Mode.DEBUG_LINES,
                        DefaultVertexFormat.POSITION_COLOR);
                VillageModelView.outline(lines, 0, 0, 0, x1, y1, z1, LINE);
                if (frame != null) frame.close();
                frame = VillageModelView.upload(null, lines, VertexBuffer.Usage.DYNAMIC);
            }
            // The tinted side goes with the frame: one buffer of lines, one of the side.
            if (sideFace != null) sideFace.close();
            sideFace = faces;
        }
    }

    @Nullable private VertexBuffer sideFace;

    /** A flat quad between two corners that share one coordinate. */
    private static void face(BufferBuilder out, float x0, float y0, float z0, float x1, float y1, float z1) {
        if (x0 == x1) {
            out.addVertex(x0, y0, z0).setColor(SIDE);
            out.addVertex(x0, y1, z0).setColor(SIDE);
            out.addVertex(x0, y1, z1).setColor(SIDE);
            out.addVertex(x0, y0, z1).setColor(SIDE);
        } else if (y0 == y1) {
            out.addVertex(x0, y0, z0).setColor(SIDE);
            out.addVertex(x1, y0, z0).setColor(SIDE);
            out.addVertex(x1, y0, z1).setColor(SIDE);
            out.addVertex(x0, y0, z1).setColor(SIDE);
        } else {
            out.addVertex(x0, y0, z0).setColor(SIDE);
            out.addVertex(x1, y0, z0).setColor(SIDE);
            out.addVertex(x1, y1, z0).setColor(SIDE);
            out.addVertex(x0, y1, z0).setColor(SIDE);
        }
    }

    private void dropShape() {
        if (parsing != null) parsing.cancel(false);
        parsing = null;
        ghost.setSource(null, null);
        dropBoxes();
    }

    private void dropBoxes() {
        boxesBuilt = false;
        if (boxes != null) boxes.close();
        boxes = null;
    }

    void close() {
        dropShape();
        ghost.close();
        if (frame != null) frame.close();
        if (sideFace != null) sideFace.close();
        frame = sideFace = null;
    }
}
