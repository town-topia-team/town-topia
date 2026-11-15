package com.alex.test.client;

import com.alex.test.network.BuildOrderPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Dashed gold boxes in the world where a build was ordered, for every player within
 * {@link #RANGE} blocks. The server sends the list ({@link BuildOrderPayloads.Sites}) on
 * join, on a world change and whenever an order is added or cancelled.
 */
public final class ConstructionSiteOutline {
    private ConstructionSiteOutline() {}

    private static final double RANGE = 96, NAME_RANGE = 64;
    private static final int COLOR = 0xE0E8B040;
    /** Dash and gap length in blocks. */
    private static final float DASH = 0.5F, STEP = 1.0F;

    private static List<BuildOrderPayloads.SiteRow> sites = List.of();

    public static void set(List<BuildOrderPayloads.SiteRow> rows) {
        sites = List.copyOf(rows);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        sites = List.of();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || sites.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.options.hideGui) return;
        Vec3 camera = event.getCamera().getPosition();
        BufferBuilder out = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (BuildOrderPayloads.SiteRow site : sites) {
            float x0 = (float) (site.origin().getX() - camera.x), y0 = (float) (site.origin().getY() - camera.y);
            float z0 = (float) (site.origin().getZ() - camera.z);
            float x1 = x0 + site.sizeX(), y1 = y0 + site.sizeY(), z1 = z0 + site.sizeZ();
            // Flat distance from the camera to the nearest point of the box.
            double dx = Math.max(Math.max(x0, -x1), 0), dz = Math.max(Math.max(z0, -z1), 0);
            if (dx * dx + dz * dz > RANGE * RANGE) continue;
            // Four upright edges, then the bottom and top rings.
            dashed(out, x0, y0, z0, x0, y1, z0);
            dashed(out, x1, y0, z0, x1, y1, z0);
            dashed(out, x0, y0, z1, x0, y1, z1);
            dashed(out, x1, y0, z1, x1, y1, z1);
            for (float y : new float[] {y0, y1}) {
                dashed(out, x0, y, z0, x1, y, z0);
                dashed(out, x0, y, z1, x1, y, z1);
                dashed(out, x0, y, z0, x0, y, z1);
                dashed(out, x1, y, z0, x1, y, z1);
            }
        }
        MeshData mesh = out.build();
        if (mesh != null) drawLines(mesh);
        drawNames(minecraft, event.getCamera(), camera);
    }

    private static void drawLines(MeshData mesh) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** Each order's name over its outline, facing the camera, readable from afar; within {@link #NAME_RANGE}. */
    private static void drawNames(Minecraft minecraft, net.minecraft.client.Camera camera, Vec3 at) {
        net.minecraft.client.gui.Font font = minecraft.font;
        net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        com.mojang.blaze3d.vertex.PoseStack pose = new com.mojang.blaze3d.vertex.PoseStack();
        for (BuildOrderPayloads.SiteRow site : sites) {
            double x = site.origin().getX() + site.sizeX() / 2.0 - at.x;
            double y = site.origin().getY() + site.sizeY() + 0.6 - at.y;
            double z = site.origin().getZ() + site.sizeZ() / 2.0 - at.z;
            double distance = Math.sqrt(x * x + y * y + z * z);
            if (distance > NAME_RANGE) continue;
            float scale = 0.025F * (float) Math.max(1.0, distance / 12.0);
            pose.pushPose();
            pose.translate(x, y, z);
            pose.mulPose(camera.rotation());
            pose.scale(scale, -scale, scale);
            String text = site.name();
            font.drawInBatch(text, -font.width(text) / 2.0F, 0, 0xFFE8B040, false, pose.last().pose(), buffers,
                    net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0x50000000,
                    net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            pose.popPose();
        }
        buffers.endBatch();
    }

    private static void dashed(BufferBuilder out, float ax, float ay, float az, float bx, float by, float bz) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length <= 0) return;
        for (float at = 0; at < length; at += STEP) {
            float t0 = at / length, t1 = Math.min(at + DASH, length) / length;
            out.addVertex(ax + dx * t0, ay + dy * t0, az + dz * t0).setColor(COLOR);
            out.addVertex(ax + dx * t1, ay + dy * t1, az + dz * t1).setColor(COLOR);
        }
    }
}
