package com.alex.test.client;

import com.alex.test.network.StuckWatchPayloads;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Stuck catcher round 3: the ghost of a caught villager's last 15 s, drawn in
 * the world while the server holds it frozen. A trail of where it walked,
 * coloured by what steered it; its route with the waypoint it was heading for;
 * a see-through body at the chosen tick with where it faced, where it moved,
 * where the move control aimed and the block that stopped it; and a panel with
 * everything the black box knew at that tick. {@code [} and {@code ]} step a
 * tick (with Shift, a second), {@code \} hides the panel. Client side only;
 * the server sends it ({@link StuckWatchPayloads}) and takes it down on resume.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class StuckGhostView {

    private static final String CATEGORY = "key.categories.dynamicvillages";
    static final KeyMapping BACK = new KeyMapping("key.dynamicvillages.ghost_back", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);
    static final KeyMapping FORWARD = new KeyMapping("key.dynamicvillages.ghost_forward", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);
    static final KeyMapping PANEL = new KeyMapping("key.dynamicvillages.ghost_panel", KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY);

    private static final int PANEL_WIDTH = 330;
    private static final float BODY_HALF_WIDTH = 0.3F;
    private static final float BODY_HEIGHT = 1.95F;

    /** Trail colours by steering (BlackBox.STEER_*): idle, Minecraft path, straight, door, ladder, hop, feet off ground, waiting, route follower. */
    private static final int[][] STEER_RGB = {
            {150, 150, 150}, {80, 160, 255}, {255, 160, 40}, {90, 230, 90},
            {190, 110, 255}, {255, 70, 70}, {60, 230, 230}, {235, 235, 235}, {255, 130, 190}};

    @Nullable private static StuckWatchPayloads.Show show;
    private static int cursor;
    private static boolean panel = true;

    private StuckGhostView() {}

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(BACK);
        event.register(FORWARD);
        event.register(PANEL);
    }

    public static void show(StuckWatchPayloads.Show payload) {
        show = payload;
        cursor = Math.max(0, payload.frames().size() - 1);
        panel = true;
    }

    public static void cursor(StuckWatchPayloads.Cursor payload) {
        StuckWatchPayloads.Show shown = show;
        if (shown == null || shown.frames().isEmpty()) return;
        long want = shown.catchTick() + payload.offset();
        int best = 0;
        long bestGap = Long.MAX_VALUE;
        for (int i = 0; i < shown.frames().size(); i++) {
            long gap = Math.abs(shown.frames().get(i).tick() - want);
            if (gap < bestGap) {
                bestGap = gap;
                best = i;
            }
        }
        cursor = best;
    }

    public static void clear() {
        show = null;
    }

    /**
     * Rewind: the villager drawn at its rewound spot at once. A teleport normally
     * slides the mob over its next three ticks, which a frozen world never runs.
     */
    public static void snap(StuckWatchPayloads.Snap payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(payload.entityId()) instanceof LivingEntity living)) return;
        living.moveTo(payload.x(), payload.y(), payload.z(), payload.yRot(), living.getXRot());
        living.lerpTo(payload.x(), payload.y(), payload.z(), payload.yRot(), living.getXRot(), 0);
        living.yBodyRot = payload.bodyRot();
        living.yBodyRotO = payload.bodyRot();
        living.yHeadRot = payload.headRot();
        living.yHeadRotO = payload.headRot();
        living.lerpHeadTo(payload.headRot(), 0);
        living.setDeltaMovement(Vec3.ZERO);
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        StuckWatchPayloads.Show shown = show;
        boolean back = false, forward = false;
        while (BACK.consumeClick()) back = true;
        while (FORWARD.consumeClick()) forward = true;
        boolean toggle = false;
        while (PANEL.consumeClick()) toggle = true;
        if (shown == null || shown.frames().isEmpty()) return;
        if (Minecraft.getInstance().level == null) {
            show = null;
            return;
        }
        int step = Screen.hasShiftDown() ? 20 : 1;
        int last = shown.frames().size() - 1;
        if (back) cursor = Mth.clamp(cursor - step, 0, last);
        if (forward) cursor = Mth.clamp(cursor + step, 0, last);
        if (toggle) panel = !panel;
    }

    // -------------------------------------------------------------------------
    // In the world
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        StuckWatchPayloads.Show shown = show;
        if (shown == null || shown.frames().isEmpty() || IrisShadowPass.active()) return;
        List<StuckWatchPayloads.Frame> frames = shown.frames();
        int at = Mth.clamp(cursor, 0, frames.size() - 1);
        StuckWatchPayloads.Frame cur = frames.get(at);

        Vec3 camera = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f m = pose.last().pose();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.lineWidth(2.0F);
        BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);

        // The trail: bright up to the cursor, faint after it.
        for (int i = 1; i < frames.size(); i++) {
            StuckWatchPayloads.Frame a = frames.get(i - 1), b = frames.get(i);
            int[] rgb = STEER_RGB[Mth.clamp(b.steerCode(), 0, STEER_RGB.length - 1)];
            int alpha = i <= at ? 230 : 60;
            line(buf, m, a.x(), a.y() + 0.05, a.z(), b.x(), b.y() + 0.05, b.z(), rgb[0], rgb[1], rgb[2], alpha);
            if (i % 20 == 0) {
                // A tick mark each second, so distance per second can be read off the trail.
                line(buf, m, b.x(), b.y(), b.z(), b.x(), b.y() + 0.25, b.z(), rgb[0], rgb[1], rgb[2], alpha);
            }
        }

        // The route the cursor's tick was walking.
        StuckWatchPayloads.Route route = routeFor(shown, cur.routeSeq());
        if (route != null) {
            List<BlockPos> waypoints = route.waypoints();
            for (int j = 0; j < waypoints.size(); j++) {
                BlockPos wp = waypoints.get(j);
                int r, g, b;
                if (j < cur.wp()) { r = 120; g = 120; b = 120; }
                else if (j == cur.wp()) { r = 255; g = 230; b = 40; }
                else { r = 240; g = 240; b = 240; }
                flatSquare(buf, m, wp, j == cur.wp() ? 0.05 : 0.15, r, g, b, 220);
                if (j > 0) {
                    BlockPos prev = waypoints.get(j - 1);
                    line(buf, m, prev.getX() + 0.5, prev.getY() + 0.1, prev.getZ() + 0.5,
                            wp.getX() + 0.5, wp.getY() + 0.1, wp.getZ() + 0.5, r, g, b, 140);
                }
            }
        }

        // The body at the cursor.
        boolean pressed = (cur.flags() & 2) != 0;
        box(buf, m, cur.x() - BODY_HALF_WIDTH, cur.y(), cur.z() - BODY_HALF_WIDTH,
                cur.x() + BODY_HALF_WIDTH, cur.y() + BODY_HEIGHT, cur.z() + BODY_HALF_WIDTH,
                255, pressed ? 90 : 255, pressed ? 90 : 255, 255);
        double yawRad = Math.toRadians(cur.yaw());
        double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
        line(buf, m, cur.x(), cur.y() + 1.6, cur.z(), cur.x() + fx * 0.9, cur.y() + 1.6, cur.z() + fz * 0.9,
                255, 230, 40, 255);
        line(buf, m, cur.x(), cur.y() + 0.1, cur.z(),
                cur.x() + cur.vx() * 10.0, cur.y() + 0.1 + cur.vy() * 10.0, cur.z() + cur.vz() * 10.0, 90, 255, 90, 255);
        if (cur.mvWantX() != 0.0F || cur.mvWantZ() != 0.0F) {
            // Asked of the body (dim) against what it got (bright), ten times longer.
            line(buf, m, cur.x(), cur.y() + 0.3, cur.z(), cur.x() + cur.mvWantX() * 10.0, cur.y() + 0.3,
                    cur.z() + cur.mvWantZ() * 10.0, 255, 255, 255, 110);
            line(buf, m, cur.x(), cur.y() + 0.32, cur.z(), cur.x() + cur.mvGotX() * 10.0, cur.y() + 0.32,
                    cur.z() + cur.mvGotZ() * 10.0, 255, 255, 255, 255);
        }
        if (cur.hasWanted()) {
            line(buf, m, cur.x(), cur.y() + 0.9, cur.z(), cur.wantX(), cur.wantY() + 0.9, cur.wantZ(), 255, 80, 255, 220);
            box(buf, m, cur.wantX() - 0.1, cur.wantY(), cur.wantZ() - 0.1, cur.wantX() + 0.1, cur.wantY() + 0.2,
                    cur.wantZ() + 0.1, 255, 80, 255, 255);
        }
        if (cur.navNext() != Long.MIN_VALUE) {
            BlockPos next = BlockPos.of(cur.navNext());
            box(buf, m, next.getX() + 0.2, next.getY(), next.getZ() + 0.2, next.getX() + 0.8, next.getY() + 0.1,
                    next.getZ() + 0.8, 60, 230, 230, 255);
        }
        if (cur.blockedPos() != Long.MIN_VALUE) {
            BlockPos stop = BlockPos.of(cur.blockedPos());
            box(buf, m, stop.getX() - 0.01, stop.getY() - 0.01, stop.getZ() - 0.01,
                    stop.getX() + 1.01, stop.getY() + 1.01, stop.getZ() + 1.01, 255, 50, 50, 255);
        }
        // Where it was caught.
        StuckWatchPayloads.Frame caught = frames.get(frames.size() - 1);
        line(buf, m, caught.x() - 0.4, caught.y() + 0.02, caught.z() - 0.4, caught.x() + 0.4, caught.y() + 0.02,
                caught.z() + 0.4, 255, 50, 50, 255);
        line(buf, m, caught.x() - 0.4, caught.y() + 0.02, caught.z() + 0.4, caught.x() + 0.4, caught.y() + 0.02,
                caught.z() - 0.4, 255, 50, 50, 255);

        MeshData mesh = buf.build();
        if (mesh != null) BufferUploader.drawWithShader(mesh);
        RenderSystem.lineWidth(1.0F);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    @Nullable
    private static StuckWatchPayloads.Route routeFor(StuckWatchPayloads.Show shown, int seq) {
        for (StuckWatchPayloads.Route route : shown.routes()) {
            if (route.seq() == seq) return route;
        }
        return null;
    }

    private static void flatSquare(BufferBuilder buf, Matrix4f m, BlockPos pos, double inset, int r, int g, int b, int a) {
        double y = pos.getY() + 0.03;
        double x0 = pos.getX() + inset, x1 = pos.getX() + 1 - inset;
        double z0 = pos.getZ() + inset, z1 = pos.getZ() + 1 - inset;
        line(buf, m, x0, y, z0, x1, y, z0, r, g, b, a);
        line(buf, m, x1, y, z0, x1, y, z1, r, g, b, a);
        line(buf, m, x1, y, z1, x0, y, z1, r, g, b, a);
        line(buf, m, x0, y, z1, x0, y, z0, r, g, b, a);
    }

    private static void box(BufferBuilder buf, Matrix4f m, double x1, double y1, double z1,
                            double x2, double y2, double z2, int r, int g, int b, int a) {
        for (double y : new double[] {y1, y2}) {
            line(buf, m, x1, y, z1, x2, y, z1, r, g, b, a);
            line(buf, m, x2, y, z1, x2, y, z2, r, g, b, a);
            line(buf, m, x2, y, z2, x1, y, z2, r, g, b, a);
            line(buf, m, x1, y, z2, x1, y, z1, r, g, b, a);
        }
        line(buf, m, x1, y1, z1, x1, y2, z1, r, g, b, a);
        line(buf, m, x2, y1, z1, x2, y2, z1, r, g, b, a);
        line(buf, m, x2, y1, z2, x2, y2, z2, r, g, b, a);
        line(buf, m, x1, y1, z2, x1, y2, z2, r, g, b, a);
    }

    private static void line(BufferBuilder buf, Matrix4f m, double x1, double y1, double z1,
                             double x2, double y2, double z2, int r, int g, int b, int a) {
        buf.addVertex(m, (float) x1, (float) y1, (float) z1).setColor(r, g, b, a);
        buf.addVertex(m, (float) x2, (float) y2, (float) z2).setColor(r, g, b, a);
    }

    // -------------------------------------------------------------------------
    // The panel
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        StuckWatchPayloads.Show shown = show;
        Minecraft minecraft = Minecraft.getInstance();
        if (shown == null || shown.frames().isEmpty() || !panel || minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        List<StuckWatchPayloads.Frame> frames = shown.frames();
        int at = Mth.clamp(cursor, 0, frames.size() - 1);
        StuckWatchPayloads.Frame cur = frames.get(at);
        Font font = minecraft.font;
        int width = Math.min(PANEL_WIDTH, event.getGuiGraphics().guiWidth() - 8);

        List<Line> lines = new ArrayList<>();
        add(lines, font, "Stuck catch " + shown.title(), 0xFFFFC040, width);
        add(lines, font, shown.reason(), 0xFFFFFFFF, width);
        long rel = cur.tick() - shown.catchTick();
        add(lines, font, String.format(java.util.Locale.ROOT, "t%d  (%.2f s before the catch)   tick %d of %d%s",
                rel, -rel / 20.0, at + 1, frames.size(), at == frames.size() - 1 ? "   CAUGHT HERE" : ""),
                0xFF9FE0FF, width);
        for (String part : cur.text().split("\n")) add(lines, font, part, 0xFFE8E8E8, width);
        add(lines, font, "Trail: blue Minecraft path, orange straight, green door, purple ladder, red hop, cyan feet off ground, "
                + "pink route follower.", 0xFF9A9A9A, width);
        add(lines, font, "Yellow square: waypoint it heads for. Magenta: move control aim. Cyan: Minecraft's next point. "
                + "Red box: block that stopped it. White lines: move asked (dim) and got.", 0xFF9A9A9A, width);
        add(lines, font, "[ and ] step a tick, Shift for a second, \\ hides this.", 0xFF7FE08A, width);

        GuiGraphics graphics = event.getGuiGraphics();
        int x = 4, y = 4;
        int height = lines.size() * (font.lineHeight + 1) + 6;
        graphics.fill(x - 1, y - 1, x + width + 7, y + height + 1, 0xFF080808);
        graphics.fill(x, y, x + width + 6, y + height, 0xE0202020);
        int ty = y + 3;
        for (Line line : lines) {
            graphics.drawString(font, line.text(), x + 3, ty, line.color(), false);
            ty += font.lineHeight + 1;
        }
    }

    private record Line(FormattedCharSequence text, int color) {}

    private static void add(List<Line> lines, Font font, String text, int color, int width) {
        for (FormattedCharSequence part : font.split(FormattedText.of(text), width)) lines.add(new Line(part, color));
    }
}
