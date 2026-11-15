package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.performance.ServerCostLog;
import com.alex.test.entity.NpcEntity;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where this computer's time goes, written to the game log as {@code [ClientCost]} lines
 * every few seconds ({@code /villagerfps log on [seconds]}), so a crowd test
 * ({@code /villager stresstest}) can be read back afterwards: frame time and the slowest
 * frames, how long the entity pass took, what one villager costs to draw and in which part
 * (posing, body and limbs, armor, held items, name tag ...), how many separate batches went
 * to the graphics card, what a vanilla mob costs next to a villager, client ticks, garbage
 * collection and the built-in server's tick.
 *
 * <p>Nothing is measured while it is off: the renderer's hooks are one flag check, and the
 * per-mob events are only listened to while it is on. Parts of a villager are timed on one
 * villager in {@link #SAMPLE_EVERY} each frame (a different one each frame), so the clock
 * reads stay cheap with hundreds drawn; the whole villager is timed on every one. The
 * shader shadow pass draws each villager a second time; it is counted apart and never
 * sampled, so the parts describe the pass the player sees.
 *
 * <p>Batches are counted by {@code mixin/RenderTypeDrawMixin} (every {@code RenderType.draw}:
 * one upload and draw call each). With a shader pack, Iris may group entity drawing its own
 * way, so those numbers can come out low there.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ClientCostLog {

    /** Logging on. Read by the renderer, the model and the draw counter. */
    public static volatile boolean on;

    /** Seconds per written block of lines. */
    private static volatile int seconds = 5;

    /** One villager in this many has its parts timed each frame. */
    static final int SAMPLE_EVERY = 4;

    /** Timed parts of one villager's drawing; see {@link #villagerLine}. */
    enum Part {
        /** The whole render call (sampled villagers only, to share the rest out by). */
        WHOLE,
        /** GeckoLib's actuallyRender: posing plus the walk over every bone. */
        POSED_BODY,
        /** handleAnimations: controllers, keyframes, bone visibility. */
        ANIMATION,
        /** Cubes of drawn bones other than arms and legs (head, torso, hats, props). */
        CUBES,
        /** Arms and legs, both halves, bent or straight (plan W0: apart from {@link #CUBES}). */
        LIMBS,
        /** Inside LIMBS: each limb's set-up (joint angle, measuring its boxes, the curve down it). */
        LIMB_SETUP,
        /** Render layers' per-bone work (armor bookkeeping, held items, shield). */
        BONE_EXTRAS,
        /** EntityRenderer.render after the body: the name tag (and a leash). */
        NAME_TAG,
        /** Thought and talk bubbles. */
        BUBBLE,
        /** Inside ANIMATION, fast posing only: the tracks (our clip choice + keyframes) and applying them. */
        ANIM_TRACKS,
        /** Inside ANIM_TRACKS: each track's "what should play now" check (its state handler, plan W0). */
        ANIM_CHECKS,
        /** Inside ANIMATION, fast posing only: bones not moved this frame eased back to rest. */
        ANIM_REST
    }

    // The villager being drawn (render thread).
    private static int depth;
    private static boolean sampling;
    /** Inside a whole-model redraw (the alert outline): its parts belong to that layer. */
    private static int quiet;
    private static int frameNo;

    // The window being collected.
    private static long windowStart;
    private static long lastFrameStart, renderStart, tickStart;
    private static int frames, slowFrames, ticks, frameCount;
    private static long frameSum, frameMax, renderSum, tickSum;
    private static final int[] frameMicros = new int[8192];

    private static long passStart, passSum, passDrawNanosAtStart, passDrawNanos;
    private static int passes, passDrawsAtStart, passDraws;
    /** The world view's projection, second diagonal entry (1 / tan of half the vertical FOV); 0 = not seen. */
    private static float worldM11;

    private static long drawStart, drawNanos, villagerDrawNanos;
    private static int draws, villagerDraws;

    private static int villagers, shadowVillagers, sampled;
    private static long villagerNanos, shadowNanos;
    private static final long[] parts = new long[Part.values().length];
    private static String[] layerNames = new String[0];
    private static long[] layerNanos = new long[0];

    private static final Map<EntityType<?>, long[]> mobs = new HashMap<>();
    private static long mobStart;
    private static long entityTickStart, npcTickNanos, otherTickNanos;
    private static int npcTicks, otherTicks;

    private static long gcCountStart, gcTimeStart;
    private static List<String> lastLines = List.of();

    private static final PerEntity PER_ENTITY = new PerEntity();

    private ClientCostLog() {}

    // -------------------------------------------------------------------------
    // Switch
    // -------------------------------------------------------------------------

    /** Starts (or restarts) logging, a block of lines every {@code everySeconds}. */
    static void start(int everySeconds) {
        seconds = Math.max(1, everySeconds);
        if (!on) NeoForge.EVENT_BUS.register(PER_ENTITY);
        reset(System.nanoTime());
        lastFrameStart = 0;
        on = true;
        // The built-in server writes its own [ServerCost] lines alongside.
        if (Minecraft.getInstance().getSingleplayerServer() != null) ServerCostLog.start(everySeconds);
        DynamicVillages.LOGGER.info("[ClientCost] Logging on, every {} s. Lines: frame, entity pass, "
                + "villager parts (1 in {} sampled), batches, vanilla mobs, ticks, memory.", seconds, SAMPLE_EVERY);
    }

    static void stop() {
        ServerCostLog.stop();
        if (!on) return;
        on = false;
        NeoForge.EVENT_BUS.unregister(PER_ENTITY);
        DynamicVillages.LOGGER.info("[ClientCost] Logging off.");
    }

    static int seconds() {
        return seconds;
    }

    /** The last block written, for the chat. */
    static List<String> lastLines() {
        return lastLines;
    }

    // -------------------------------------------------------------------------
    // Villager hooks (NpcGeoRenderer, VillagerGeoModel)
    // -------------------------------------------------------------------------

    /** A villager starts drawing; returns the clock to hand to {@link #villagerEnd}, 0 when off. */
    static long villagerBegin(Entity entity) {
        if (!on) return 0L;
        if (depth++ == 0) {
            sampling = !IrisShadowPass.active() && Math.floorMod(entity.getId() + frameNo, SAMPLE_EVERY) == 0;
        }
        return System.nanoTime();
    }

    static void villagerEnd(long start) {
        if (start == 0L) return;
        long spent = System.nanoTime() - start;
        if (--depth > 0) return;           // drawn inside another villager's drawing: counted there
        depth = 0;
        if (IrisShadowPass.active()) {
            shadowVillagers++;
            shadowNanos += spent;
        } else {
            villagers++;
            villagerNanos += spent;
        }
        if (sampling) {
            sampled++;
            parts[Part.WHOLE.ordinal()] += spent;
        }
        sampling = false;
    }

    /** Whether the villager being drawn has its parts timed now. */
    static boolean timing() {
        return sampling && quiet == 0;
    }

    /** The clock for a part, or 0 when this villager is not being sampled. */
    static long partStart() {
        return timing() ? System.nanoTime() : 0L;
    }

    static void partEnd(Part part, long start) {
        if (start != 0L) parts[part.ordinal()] += System.nanoTime() - start;
    }

    /** Time measured elsewhere for {@code part} of the sampled villager (only call while {@link #timing()}). */
    static void addPart(Part part, long nanos) {
        parts[part.ordinal()] += nanos;
    }

    /** A whole-model redraw starts ({@code true}) or ends; its parts are not timed on their own. */
    static void quiet(boolean begin) {
        if (begin) quiet++;
        else if (quiet > 0) quiet--;
    }

    /** Layer {@code index} of {@code layers} drew its part after the body in {@code nanos}. */
    static void layer(int index, List<? extends GeoRenderLayer<?>> layers, long nanos) {
        if (layerNames.length != layers.size()) {
            layerNames = new String[layers.size()];
            layerNanos = new long[layers.size()];
            for (int i = 0; i < layers.size(); i++) {
                layerNames[i] = layers.get(i).getClass().getSimpleName()
                        .replace("GeoLayer", "").replace("Layer", "");
            }
        }
        layerNanos[index] += nanos;
    }

    // -------------------------------------------------------------------------
    // Draw counter (mixin/RenderTypeDrawMixin)
    // -------------------------------------------------------------------------

    public static void drawStart() {
        if (on) drawStart = System.nanoTime();
    }

    public static void drawEnd() {
        if (!on || drawStart == 0L) return;
        long spent = System.nanoTime() - drawStart;
        drawStart = 0L;
        draws++;
        drawNanos += spent;
        if (depth > 0) {
            villagerDraws++;
            villagerDrawNanos += spent;
        }
    }

    // -------------------------------------------------------------------------
    // Frame, tick and pass events
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public static void frameStart(RenderFrameEvent.Pre event) {
        frameNo++;
        if (!on) return;
        long now = System.nanoTime();
        if (lastFrameStart != 0L) {
            long frame = now - lastFrameStart;
            frames++;
            frameSum += frame;
            frameMax = Math.max(frameMax, frame);
            if (frame > 50_000_000L) slowFrames++;
            if (frameCount < frameMicros.length) frameMicros[frameCount++] = (int) Math.min(Integer.MAX_VALUE, frame / 1000L);
        }
        if (now - windowStart >= seconds * 1_000_000_000L) {
            write(now);
            reset(now);
        }
        lastFrameStart = now;
        renderStart = now;
    }

    @SubscribeEvent
    public static void frameEnd(RenderFrameEvent.Post event) {
        if (!on || renderStart == 0L) return;
        renderSum += System.nanoTime() - renderStart;
        renderStart = 0L;
    }

    @SubscribeEvent
    public static void clientTickStart(ClientTickEvent.Pre event) {
        if (on) tickStart = System.nanoTime();
    }

    @SubscribeEvent
    public static void clientTickEnd(ClientTickEvent.Post event) {
        if (!on || tickStart == 0L) return;
        tickSum += System.nanoTime() - tickStart;
        ticks++;
        tickStart = 0L;
    }

    /** The entity pass: from the last block layer before entities to {@code AFTER_ENTITIES}. */
    @SubscribeEvent
    public static void stage(RenderLevelStageEvent event) {
        if (!on) return;
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS) {
            // The camera's view (not the shader sun's): what the detail cut-offs measure with.
            if (!IrisShadowPass.active()) worldM11 = event.getProjectionMatrix().m11();
            passStart = System.nanoTime();
            passDrawsAtStart = draws;
            passDrawNanosAtStart = drawNanos;
        } else if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES && passStart != 0L) {
            passSum += System.nanoTime() - passStart;
            passes++;
            passDraws += draws - passDrawsAtStart;
            passDrawNanos += drawNanos - passDrawNanosAtStart;
            passStart = 0L;
        }
    }

    /**
     * Events that fire once per mob every frame or tick: listened to only while logging,
     * so they cost nothing otherwise. Vanilla mobs (zombies, the player) fire
     * {@code RenderLivingEvent}; villagers are GeckoLib-drawn and timed by the renderer.
     */
    public static final class PerEntity {
        @SubscribeEvent
        public void mobDrawStart(RenderLivingEvent.Pre<?, ?> event) {
            mobStart = System.nanoTime();
        }

        @SubscribeEvent
        public void mobDrawEnd(RenderLivingEvent.Post<?, ?> event) {
            if (mobStart == 0L) return;
            long[] slot = mobs.computeIfAbsent(event.getEntity().getType(), type -> new long[2]);
            slot[0] += System.nanoTime() - mobStart;
            slot[1]++;
            mobStart = 0L;
        }

        @SubscribeEvent
        public void entityTickStart(EntityTickEvent.Pre event) {
            if (event.getEntity().level().isClientSide()) entityTickStart = System.nanoTime();
        }

        @SubscribeEvent
        public void entityTickEnd(EntityTickEvent.Post event) {
            Entity entity = event.getEntity();
            if (!entity.level().isClientSide() || entityTickStart == 0L) return;
            long spent = System.nanoTime() - entityTickStart;
            entityTickStart = 0L;
            if (entity instanceof NpcEntity) {
                npcTickNanos += spent;
                npcTicks++;
            } else {
                otherTickNanos += spent;
                otherTicks++;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Writing
    // -------------------------------------------------------------------------

    private static void reset(long now) {
        windowStart = now;
        frames = slowFrames = ticks = frameCount = 0;
        frameSum = frameMax = renderSum = tickSum = 0L;
        passSum = passDrawNanos = 0L;
        passes = passDraws = 0;
        drawNanos = villagerDrawNanos = 0L;
        draws = villagerDraws = 0;
        villagers = shadowVillagers = sampled = 0;
        villagerNanos = shadowNanos = 0L;
        Arrays.fill(parts, 0L);
        Arrays.fill(layerNanos, 0L);
        mobs.clear();
        npcTickNanos = otherTickNanos = 0L;
        npcTicks = otherTicks = 0;
        long[] gc = gc();
        gcCountStart = gc[0];
        gcTimeStart = gc[1];
    }

    private static void write(long now) {
        if (frames == 0) return;
        List<String> lines = new ArrayList<>();
        double secs = (now - windowStart) / 1e9;
        double frameMs = ms(frameSum) / frames;
        double renderMs = ms(renderSum) / frames;
        double tickMsPerFrame = ms(tickSum) / frames;
        lines.add(String.format(Locale.ROOT, "%.1f s: %.0f FPS, frame %.1f ms (1%% slowest %.1f ms, worst %.1f ms, %d over 50 ms)."
                        + " Per frame: drawing %.1f ms, client ticks %.1f ms, rest %.1f ms (graphics card wait, FPS cap)",
                secs, frames / secs, frameMs, slowestPercentMs(), ms(frameMax), slowFrames,
                renderMs, tickMsPerFrame, Math.max(0, frameMs - renderMs - tickMsPerFrame)));

        double perVillagerUs = villagers == 0 ? 0 : us(villagerNanos) / villagers;
        String shadow = shadowVillagers == 0 ? "no shadow pass"
                : String.format(Locale.ROOT, "shadow pass %.0f drawn/frame, %.1f ms/frame", (double) shadowVillagers / frames,
                ms(shadowNanos) / frames);
        lines.add(String.format(Locale.ROOT, "Entity pass %.1f ms/frame. Villagers %.0f drawn/frame, %.1f ms/frame, %.0f us each (%s)",
                passes == 0 ? 0 : ms(passSum) / passes, (double) villagers / frames, ms(villagerNanos) / frames,
                perVillagerUs, shadow));
        if (sampled > 0) lines.add(villagerLine());
        lines.add(VillagerFrameStats.compact() + "; " + DetailCull.budgetCompact() + "; " + StandIn.compact() + "; " + PoseRate.compact() + "; " + ReadyItem.compact() + "; " + CubeWriter.state()
                + ", " + BentLimb.state() + ", " + SkinFaces.state() + "; " + viewPart());

        lines.add(String.format(Locale.ROOT, "Batches to the graphics card: %.0f/frame, %.1f ms/frame; in the entity pass %.0f (%.1f ms);"
                        + " while drawing villagers %.0f (%.1f ms)",
                (double) draws / frames, ms(drawNanos) / frames,
                passes == 0 ? 0 : (double) passDraws / passes, passes == 0 ? 0 : ms(passDrawNanos) / passes,
                (double) villagerDraws / frames, ms(villagerDrawNanos) / frames));
        lines.add(CrowdBatching.costLine());

        lines.add(mobLine());

        lines.add(String.format(Locale.ROOT, "Client ticks %.1f/s, %.2f ms each: villagers %.2f ms (%.0f), other entities %.2f ms (%.0f)",
                ticks / secs, ticks == 0 ? 0 : ms(tickSum) / ticks,
                ticks == 0 ? 0 : ms(npcTickNanos) / ticks, ticks == 0 ? 0 : (double) npcTicks / ticks,
                ticks == 0 ? 0 : ms(otherTickNanos) / ticks, ticks == 0 ? 0 : (double) otherTicks / ticks));

        long[] gc = gc();
        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) >> 20;
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        String serverPart = server == null ? "server: not built in"
                : String.format(Locale.ROOT, "built-in server tick %.1f ms", server.getAverageTickTimeNanos() / 1e6);
        lines.add(String.format(Locale.ROOT, "Memory: %d collections, %d ms; heap %d of %d MB. %s",
                gc[0] - gcCountStart, gc[1] - gcTimeStart, usedMb, runtime.maxMemory() >> 20, serverPart));

        for (String line : lines) DynamicVillages.LOGGER.info("[ClientCost] {}", line);
        lastLines = List.copyOf(lines);
    }

    /**
     * Window size, field of view and GUI scale, so two runs compare: the detail cut-offs
     * are in screen pixels, so a taller window or a narrower view keeps more detail on
     * the same crowd. "Seen" is the field of view the world was drawn with (sprinting,
     * a spyglass or a zoom mod change it); the pixels a block 10 blocks away spans is
     * what the cut-offs measure ({@link BentLimb#pixelsPerBlock}).
     */
    private static String viewPart() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        float m11 = worldM11;
        String seen = m11 <= 0f ? "not seen"
                : String.format(Locale.ROOT, "seen %.0f, 1 block 10 away = %.0f px",
                        Math.toDegrees(2.0 * Math.atan(1.0 / m11)), window.getHeight() * m11 * 0.5 / 10.0);
        return String.format(Locale.ROOT, "window %dx%d px %s, FOV setting %d (%s), GUI scale %.0f%s",
                window.getWidth(), window.getHeight(), window.isFullscreen() ? "fullscreen" : "windowed",
                minecraft.options.fov().get(), seen, window.getGuiScale(),
                minecraft.options.guiScale().get() == 0 ? " (auto)" : "");
    }

    /** One sampled villager, part by part: share of its time and microseconds. */
    private static String villagerLine() {
        long whole = parts[Part.WHOLE.ordinal()];
        long posed = parts[Part.POSED_BODY.ordinal()];
        long animation = parts[Part.ANIMATION.ordinal()];
        long cubes = parts[Part.CUBES.ordinal()];
        long limbs = parts[Part.LIMBS.ordinal()];
        long limbSetUp = parts[Part.LIMB_SETUP.ordinal()];
        long boneExtras = parts[Part.BONE_EXTRAS.ordinal()];
        long nameTag = parts[Part.NAME_TAG.ordinal()];
        long bubble = parts[Part.BUBBLE.ordinal()];
        long layersAll = 0;
        for (long n : layerNanos) layersAll += n;
        long boneWalk = Math.max(0, posed - animation - cubes - limbs - boneExtras);
        long setUp = Math.max(0, whole - posed - layersAll - nameTag - bubble);

        StringBuilder line = new StringBuilder(String.format(Locale.ROOT, "One villager (%d sampled): %.0f us =", sampled, us(whole) / sampled));
        part(line, "animation", animation, whole);
        long tracks = parts[Part.ANIM_TRACKS.ordinal()];
        long checks = parts[Part.ANIM_CHECKS.ordinal()];
        long rest = parts[Part.ANIM_REST.ordinal()];
        // Full fast posing (FastPose) splits its share; safe and GeckoLib's own posing leave both at 0.
        // Tracks = the "what should play" checks + reading keyframes and setting the bones.
        line.append(tracks + rest > 0
                ? String.format(Locale.ROOT, " [fast posing: tracks %.1f us (what-to-play checks %.1f, keyframes %.1f),"
                                + " back to rest %.1f us],",
                        us(tracks) / sampled, us(checks) / sampled, us(Math.max(0, tracks - checks)) / sampled,
                        us(rest) / sampled)
                : com.alex.test.animation.FastPose.mode() == com.alex.test.animation.FastPose.Mode.SAFE
                        ? " [safe posing: GeckoLib's own, keyed bones only],"
                        : " [GeckoLib posing],");
        part(line, "body boxes", cubes, whole);
        part(line, "arms and legs", limbs, whole);
        line.append(String.format(Locale.ROOT, " [limb set-up %.1f us],", us(limbSetUp) / sampled));
        part(line, "bone walk", boneWalk, whole);
        part(line, "extras on bones", boneExtras, whole);
        long small = 0;
        for (int i = 0; i < layerNanos.length; i++) {
            if (layerNanos[i] * 100 >= whole) part(line, layerNames[i], layerNanos[i], whole);
            else small += layerNanos[i];
        }
        if (small > 0) part(line, "other layers", small, whole);
        part(line, "name tag", nameTag, whole);
        part(line, "bubbles", bubble, whole);
        part(line, "start (texture switch, set-up)", setUp, whole);
        return line.toString();
    }

    private static void part(StringBuilder line, String name, long nanos, long whole) {
        line.append(String.format(Locale.ROOT, " %s %.1f us (%.0f%%),", name, us(nanos) / sampled,
                whole == 0 ? 0 : 100.0 * nanos / whole));
    }

    /** The four vanilla mob kinds that took longest to draw. */
    private static String mobLine() {
        if (mobs.isEmpty()) return "Vanilla mobs drawn: none";
        List<Map.Entry<EntityType<?>, long[]>> sorted = new ArrayList<>(mobs.entrySet());
        sorted.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder line = new StringBuilder("Vanilla mobs drawn:");
        for (int i = 0; i < Math.min(4, sorted.size()); i++) {
            long[] slot = sorted.get(i).getValue();
            line.append(String.format(Locale.ROOT, " %s %.0f/frame %.1f ms/frame (%.0f us each),",
                    EntityType.getKey(sorted.get(i).getKey()).getPath(),
                    (double) slot[1] / frames, ms(slot[0]) / frames, us(slot[0]) / slot[1]));
        }
        return line.toString();
    }

    /** The frame time 1% of frames were slower than. */
    private static double slowestPercentMs() {
        if (frameCount == 0) return 0;
        int[] sorted = Arrays.copyOf(frameMicros, frameCount);
        Arrays.sort(sorted);
        int index = Math.min(frameCount - 1, (int) Math.ceil(frameCount * 0.99) - 1);
        return sorted[Math.max(0, index)] / 1000.0;
    }

    /** Collections so far and milliseconds spent in them, all collectors. */
    private static long[] gc() {
        long count = 0, time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0, bean.getCollectionCount());
            time += Math.max(0, bean.getCollectionTime());
        }
        return new long[] {count, time};
    }

    private static double ms(long nanos) {
        return nanos / 1e6;
    }

    private static double us(long nanos) {
        return nanos / 1e3;
    }
}
