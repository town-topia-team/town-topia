package com.alex.test.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * Renders a translucent, scrolling forcefield border around each known village area.
 * The effect is purely cosmetic and fully passable — it just marks the village boundary.
 *
 * <h3>Visual behaviour</h3>
 * <ul>
 *   <li>Four vertical quad-walls (N / S / E / W) around the NavGraph bounding box.</li>
 *   <li>Vanilla {@code forcefield.png} texture with scrolling diagonal UVs.</li>
 *   <li>Per-wall alpha: hidden beyond {@link #FADE_FAR_DIST} blocks; ramps up to
 *       {@link #MAX_ALPHA} as the player approaches within {@link #FADE_NEAR_DIST} blocks.</li>
 *   <li>Gold tint to distinguish village borders from the cyan world-border.</li>
 * </ul>
 *
 * <p>Registered on {@code NeoForge.EVENT_BUS} (game bus) from
 * {@link com.alex.test.DynamicVillagesClient#DynamicVillagesClient(net.neoforged.fml.ModContainer)}
 * so it only runs on the physical client.</p>
 */
public final class VillageBorderRenderer {

    // ── Texture ───────────────────────────────────────────────────────────────
    private static final ResourceLocation FORCEFIELD =
            ResourceLocation.withDefaultNamespace("textures/misc/forcefield.png");

    // ── Alpha / fade ──────────────────────────────────────────────────────────
    /** At this distance from a wall face the border is at full opacity. */
    private static final float FADE_NEAR_DIST = 3.0f;
    /** Beyond this distance the wall is not drawn at all; it fades in from here. */
    private static final float FADE_FAR_DIST  = 20.0f;
    private static final float MAX_ALPHA       = 0.55f;

    // ── UV scaling ────────────────────────────────────────────────────────────
    /** World blocks per one texture tile (16 = one repeat every 16 blocks). */
    private static final float UV_SCALE = 1.0f / 4.0f;

    // ── blue colour (R 255, G 215, B 80) ─────────────────────────────────────
    private static final int COL_R = 50, COL_G = 150, COL_B = 255;

    // ── Scroll animation: full UV-cycle every 800 game ticks ─────────────────
    private static final float SCROLL_PERIOD = 800.0f;

    // ─────────────────────────────────────────────────────────────────────────

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;

        List<AABB> bounds = VillageBorderData.get();
        if (bounds.isEmpty()) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        Camera camera = event.getCamera();
        double cx = camera.getPosition().x;
        double cy = camera.getPosition().y;
        double cz = camera.getPosition().z;

        // Diagonal scroll: uses raw game-tick counter to avoid partial-tick complexity.
        float scroll = (mc.level.getGameTime() % (long) SCROLL_PERIOD) / SCROLL_PERIOD;

        // ── GL state ─────────────────────────────────────────────────────────
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, FORCEFIELD);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ZERO,
                GlStateManager.DestFactor.ONE);
        RenderSystem.disableCull();
        // Sodium fires this stage with the depth test off (vanilla leaves it on),
        // which drew the border through terrain. Turn it on explicitly.
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.depthMask(false);

        // ── Translate pose to camera origin so we can use world coords ────────
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cx, -cy, -cz);
        Matrix4f m = pose.last().pose();

        for (AABB aabb : bounds) {
            drawVillageBorder(m, aabb, cx, cz, scroll);
        }

        pose.popPose();

        // ── Restore GL state ──────────────────────────────────────────────────
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
    }

    // ── Per-AABB border ───────────────────────────────────────────────────────

    private static void drawVillageBorder(Matrix4f m, AABB aabb,
                                           double playerX, double playerZ,
                                           float scroll) {
        float x1 = (float) aabb.minX, x2 = (float) aabb.maxX;
        float y1 = (float) aabb.minY, y2 = (float) aabb.maxY;
        float z1 = (float) aabb.minZ, z2 = (float) aabb.maxZ;

        float vLen = (y2 - y1) * UV_SCALE;

        // ── North wall  z = z1, spans x1→x2 ──────────────────────────────────
        {
            float a = wallAlpha(playerX, playerZ, aabb.minX, aabb.maxX, aabb.minZ, aabb.minZ);
            if (a > 0.005f) {
                float uLen = (x2 - x1) * UV_SCALE;
                drawQuad(m, x1, y1, z1, x2, y1, z1, x2, y2, z1, x1, y2, z1,
                         scroll, scroll + uLen, scroll, scroll + vLen, (int)(a * 255));
            }
        }

        // ── South wall  z = z2, spans x2→x1 ──────────────────────────────────
        {
            float a = wallAlpha(playerX, playerZ, aabb.minX, aabb.maxX, aabb.maxZ, aabb.maxZ);
            if (a > 0.005f) {
                float uLen = (x2 - x1) * UV_SCALE;
                drawQuad(m, x2, y1, z2, x1, y1, z2, x1, y2, z2, x2, y2, z2,
                         scroll, scroll + uLen, scroll, scroll + vLen, (int)(a * 255));
            }
        }

        // ── West wall  x = x1, spans z2→z1 ───────────────────────────────────
        {
            float a = wallAlpha(playerX, playerZ, aabb.minX, aabb.minX, aabb.minZ, aabb.maxZ);
            if (a > 0.005f) {
                float uLen = (z2 - z1) * UV_SCALE;
                drawQuad(m, x1, y1, z2, x1, y1, z1, x1, y2, z1, x1, y2, z2,
                         scroll, scroll + uLen, scroll, scroll + vLen, (int)(a * 255));
            }
        }

        // ── East wall  x = x2, spans z1→z2 ───────────────────────────────────
        {
            float a = wallAlpha(playerX, playerZ, aabb.maxX, aabb.maxX, aabb.minZ, aabb.maxZ);
            if (a > 0.005f) {
                float uLen = (z2 - z1) * UV_SCALE;
                drawQuad(m, x2, y1, z1, x2, y1, z2, x2, y2, z2, x2, y2, z1,
                         scroll, scroll + uLen, scroll, scroll + vLen, (int)(a * 255));
            }
        }
    }

    /**
     * Emit one quad (4 vertices in QUADS order) and immediately upload/draw it.
     *
     * <p>Corners supplied in BL → BR → TR → TL order.
     * UV ranges: U from {@code u0} to {@code u1}, V from {@code v0} to {@code v1}.
     * The diagonal scroll is baked into the supplied uv ranges.</p>
     */
    private static void drawQuad(Matrix4f m,
                                  float x0, float y0, float z0,  // BL
                                  float x1, float y1, float z1,  // BR
                                  float x2, float y2, float z2,  // TR
                                  float x3, float y3, float z3,  // TL
                                  float u0, float u1, float v0, float v1,
                                  int alpha) {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buf.addVertex(m, x0, y0, z0).setUv(u0, v1).setColor(COL_R, COL_G, COL_B, alpha);
        buf.addVertex(m, x1, y1, z1).setUv(u1, v1).setColor(COL_R, COL_G, COL_B, alpha);
        buf.addVertex(m, x2, y2, z2).setUv(u1, v0).setColor(COL_R, COL_G, COL_B, alpha);
        buf.addVertex(m, x3, y3, z3).setUv(u0, v0).setColor(COL_R, COL_G, COL_B, alpha);
        MeshData mesh = buf.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
            mesh.close();
        }
    }

    /**
     * Compute per-wall alpha based on the player's XZ distance to the wall segment.
     *
     * <p>The wall is described as an axis-aligned line segment in XZ space.
     * We clamp the player's position onto that segment to find the closest point,
     * then fade between {@link #MIN_ALPHA} (far) and {@link #MAX_ALPHA} (near).</p>
     */
    private static float wallAlpha(double px, double pz,
                                    double wallMinX, double wallMaxX,
                                    double wallMinZ, double wallMaxZ) {
        double clampX = Math.max(wallMinX, Math.min(wallMaxX, px));
        double clampZ = Math.max(wallMinZ, Math.min(wallMaxZ, pz));
        double dx = px - clampX, dz = pz - clampZ;
        float dist = (float) Math.sqrt(dx * dx + dz * dz);

        if (dist >= FADE_FAR_DIST) return 0.0f;
        if (dist < FADE_NEAR_DIST) return MAX_ALPHA;
        float t = 1.0f - (dist - FADE_NEAR_DIST) / (FADE_FAR_DIST - FADE_NEAR_DIST);
        return t * MAX_ALPHA;
    }
}
