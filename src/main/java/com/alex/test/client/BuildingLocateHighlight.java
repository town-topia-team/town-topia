package com.alex.test.client;

import com.alex.test.network.MayorBuildingPayloads;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

/**
 * The Mayor's "Locate" for a building: a green box around the room, seen
 * through walls, and a tall beam over the door so it can be found from afar.
 * One building at a time; a new locate replaces the old one.
 */
public final class BuildingLocateHighlight {

    private static final int BEAM_HEIGHT = 48;

    private static BlockPos min;
    private static BlockPos max;
    private static BlockPos door;
    private static long expiresAt;
    private static ClientLevel activeLevel;

    private BuildingLocateHighlight() {}

    public static void track(MayorBuildingPayloads.Highlight payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;
        activeLevel = level;
        min = payload.min();
        max = payload.max();
        door = payload.door();
        expiresAt = level.getGameTime() + Math.max(1, payload.ticks());
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || door == null) return;
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level != activeLevel || level.getGameTime() >= expiresAt) {
            door = null;
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f m = pose.last().pose();

        // Gentle pulse so the outline catches the eye.
        float pulse = 0.65f + 0.35f * (float) Math.sin(level.getGameTime() * 0.25);
        int alpha = (int) (pulse * 255);

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.lineWidth(2.0f);

        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES,
                DefaultVertexFormat.POSITION_COLOR);
        box(buf, m, min.getX() - 0.02f, min.getY() - 0.02f, min.getZ() - 0.02f,
                max.getX() + 1.02f, max.getY() + 1.02f, max.getZ() + 1.02f, 90, 255, 120, alpha);
        box(buf, m, door.getX(), door.getY(), door.getZ(),
                door.getX() + 1f, door.getY() + 2f, door.getZ() + 1f, 255, 230, 120, alpha);
        float cx = door.getX() + 0.5f, cz = door.getZ() + 0.5f;
        line(buf, m, cx, door.getY() + 2f, cz, cx, door.getY() + 2f + BEAM_HEIGHT, cz, 255, 230, 120, alpha);
        MeshData mesh = buf.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);

        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static void box(BufferBuilder buf, Matrix4f m, float x1, float y1, float z1,
                            float x2, float y2, float z2, int r, int g, int b, int a) {
        // Bottom and top rings.
        for (float y : new float[]{y1, y2}) {
            line(buf, m, x1, y, z1, x2, y, z1, r, g, b, a);
            line(buf, m, x2, y, z1, x2, y, z2, r, g, b, a);
            line(buf, m, x2, y, z2, x1, y, z2, r, g, b, a);
            line(buf, m, x1, y, z2, x1, y, z1, r, g, b, a);
        }
        // Uprights.
        line(buf, m, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, m, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, m, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, m, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void line(BufferBuilder buf, Matrix4f m, float x1, float y1, float z1,
                             float x2, float y2, float z2, int r, int g, int b, int a) {
        buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        buf.addVertex(m, x2, y2, z2).setColor(r, g, b, a);
    }
}
