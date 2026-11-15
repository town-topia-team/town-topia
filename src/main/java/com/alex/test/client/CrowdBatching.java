package com.alex.test.client;

import com.alex.test.DynamicVillages;
import com.alex.test.mixin.BufferSourceAccessor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One batch per texture for every villager drawn in the world this frame, instead
 * of one each time the texture changes.
 *
 * <p>Minecraft's entity buffer keeps one texture open at a time: asking for another
 * sends what it holds to the graphics card first. A guard asks for its skin, its
 * armor and its name, and the next guard for its skin again, so 400 guards made
 * 1,600 to 2,300 trips a frame, 11 to 33 ms (stress test 2026-10-19, without Iris).
 * Here each texture the villagers ask for gets a buffer of its own that fills all
 * through the entity pass and is sent once, at {@code AFTER_ENTITIES}: the spot where
 * Minecraft sends its own entity batches.
 *
 * <p>With {@link #allMobs} (plan P2, 10-19) every Minecraft mob drawn in the world pass
 * goes in too (zombie skins, helmets, shadows: ~1,000 trips a frame with 400 zombies),
 * and so do see-through types that need sorting (name tags, a slime's outer layer):
 * those are sent after the rest and sorted back to front first, as Minecraft does when
 * it sends one. Other mods' mobs are left out (one might send its own batch mid-pass).
 *
 * <p>Left to Minecraft as before: what its buffer already keeps open all pass (items,
 * shields, the enchantment shimmer: its "fixed" buffers, sent after ours, so the
 * shimmer still lands on armor that is already drawn), outlines, strips (a lead), and
 * anything drawn outside the world pass (inventory portraits, screens: {@link #worldEntity}
 * is only set around {@code LevelRenderer.renderEntity}, by {@code mixin/LevelRendererEntityMixin}).
 * Off while Iris is installed: it already batches entities its own way (about 50
 * trips a frame in the same test). {@code /villagerfps crowd on|off}, {@code crowd all on|off}.
 * Render thread only.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class CrowdBatching {

    /** {@code /villagerfps crowd on|off}. */
    public static volatile boolean enabled = true;
    /** {@code /villagerfps crowd all on|off}: Minecraft's mobs and sorted types (name tags) too; off = villagers only. */
    public static volatile boolean allMobs = true;

    /** Starting size of a texture's buffer; it grows as needed and keeps its size. */
    private static final int START_BYTES = 1 << 18;
    /** A texture no villager asked for in this many frames gives its memory back. */
    private static final int IDLE_FRAMES = 1200;
    /** At most this many textures kept; any more go to Minecraft as before (each holds a buffer). */
    private static final int MAX_SLOTS = 256;

    private static boolean inWorldEntity;
    private static int frame;
    private static Boolean iris;

    /** Per render type: its own buffer, and the builder open on it this frame. */
    private static final Map<RenderType, Slot> SLOTS = new IdentityHashMap<>();
    /** Whether a render type may be kept here, sorting aside (see {@link #kept}), decided once. */
    private static final Map<RenderType, Boolean> KEPT = new IdentityHashMap<>();
    /** Per mob type: Minecraft's or ours (true), or another mod's (false), decided once. */
    private static final Map<EntityType<?>, Boolean> OWN_TYPES = new IdentityHashMap<>();
    /** Slots opened this frame, in the order first asked for: sent in that order. */
    private static final List<Slot> started = new ArrayList<>();
    private static final Source SOURCE = new Source();

    // Sums for the [ClientCost] line since it was last written.
    private static long sentBatches, sendNanos, keptDraws, droppedFrames, sentBytes;
    private static int biggestBatch;
    private static int countedFrames;

    private CrowdBatching() {}

    private static final class Slot {
        final RenderType type;
        final ByteBufferBuilder bytes;
        BufferBuilder builder;
        int usedFrame;

        Slot(RenderType type) {
            this.type = type;
            this.bytes = new ByteBufferBuilder(Math.max(type.bufferSize(), START_BYTES));
        }
    }

    /** The buffer source a villager draws into while {@link #wrap} hands it out. */
    private static final class Source implements MultiBufferSource {
        MultiBufferSource.BufferSource main;

        @Override
        public VertexConsumer getBuffer(RenderType type) {
            if (!kept(type, main)) return main.getBuffer(type);
            Slot slot = SLOTS.get(type);
            if (slot == null) {
                if (SLOTS.size() >= MAX_SLOTS) return main.getBuffer(type);
                slot = new Slot(type);
                SLOTS.put(type, slot);
            }
            if (slot.builder == null) {
                slot.builder = new BufferBuilder(slot.bytes, type.mode(), type.format());
                started.add(slot);
            }
            slot.usedFrame = frame;
            return slot.builder;
        }
    }

    /** {@code LevelRenderer.renderEntity} starts ({@code true}) or ends; from the mixin. */
    public static void worldEntity(boolean drawing) {
        inWorldEntity = drawing;
    }

    /** The world's entity pass is drawing an entity now (the camera at the frame's origin); not a portrait or screen. */
    static boolean inWorldEntity() {
        return inWorldEntity;
    }

    /**
     * The source a villager should draw into: the crowd's, while the world's entity
     * pass is drawing it into Minecraft's main buffer, else {@code bufferSource} itself.
     * A villager already handed the crowd's source by {@link #wrapWorld} keeps it.
     */
    static MultiBufferSource wrap(MultiBufferSource bufferSource) {
        if (!enabled || !inWorldEntity || irisLoaded()) return bufferSource;
        // A glowing mob gets the outline source instead: left alone.
        if (!(bufferSource instanceof MultiBufferSource.BufferSource main)
                || main != Minecraft.getInstance().renderBuffers().bufferSource()) return bufferSource;
        SOURCE.main = main;
        keptDraws++;
        return SOURCE;
    }

    /**
     * Any mob the world pass draws (from {@code mixin/LevelRendererEntityMixin}, around
     * {@code EntityRenderDispatcher.render}, so its shadow and name come too): the crowd's
     * source for Minecraft's mobs and ours with {@link #allMobs} on, else as given.
     */
    public static MultiBufferSource wrapWorld(Entity entity, MultiBufferSource bufferSource) {
        if (!allMobs || !ownType(entity.getType())) return bufferSource;
        return wrap(bufferSource);
    }

    private static boolean ownType(EntityType<?> type) {
        Boolean own = OWN_TYPES.get(type);
        if (own == null) {
            String space = BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace();
            own = space.equals(ResourceLocation.DEFAULT_NAMESPACE) || space.equals(DynamicVillages.MODID);
            OWN_TYPES.put(type, own);
        }
        return own;
    }

    private static boolean kept(RenderType type, MultiBufferSource.BufferSource main) {
        Boolean known = KEPT.get(type);
        if (known == null) {
            if (KEPT.size() > 4096) KEPT.clear();   // a mod making new render types every frame
            known = !type.isOutline() && type.canConsolidateConsecutiveGeometry()
                    && !((BufferSourceAccessor) main).dv$fixedBuffers().containsKey(type);
            KEPT.put(type, known);
        }
        // Sorted types (text, see-through skins) only with allMobs: sent last, sorted (send()).
        return known && (allMobs || !type.sortOnUpload());
    }

    private static boolean irisLoaded() {
        if (iris == null) iris = ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus");
        return iris;
    }

    /** Whether the crowd's buffers are in use at all (for {@code /villagerfps}). */
    static String state() {
        if (!enabled) return "off";
        if (irisLoaded()) return "on, but Iris is installed and batches entities itself";
        return allMobs ? "on (all Minecraft mobs and name tags)" : "on (villagers only)";
    }

    /**
     * Sends every texture's batch, at the spot Minecraft sends its own entity batches.
     * Later stages only catch a pass that skipped this one (nothing should be left).
     */
    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                || stage == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            send();
        }
    }

    private static void send() {
        if (started.isEmpty()) return;
        long start = System.nanoTime();
        // Solid and cut-out batches first, then the see-through ones that need sorting, so
        // a name tag's half-clear backing never hides a mob drawn after it.
        for (Slot slot : started) {
            if (!slot.type.sortOnUpload()) send(slot);
        }
        for (Slot slot : started) {
            if (slot.type.sortOnUpload()) send(slot);
        }
        started.clear();
        sendNanos += System.nanoTime() - start;
    }

    private static void send(Slot slot) {
        BufferBuilder builder = slot.builder;
        slot.builder = null;
        MeshData mesh = builder.build();
        if (mesh == null) return;
        int bytes = mesh.vertexBuffer().remaining();
        sentBytes += bytes;
        biggestBatch = Math.max(biggestBatch, bytes);
        // Back to front, as Minecraft's own buffer sorts one when it sends it.
        if (slot.type.sortOnUpload()) mesh.sortQuads(slot.bytes, RenderSystem.getVertexSorting());
        slot.type.draw(mesh);              // closes the mesh, which frees the buffer for next frame
        sentBatches++;
    }

    /**
     * End of the frame: anything still unsent is thrown away (drawing it now would land
     * it in the wrong place) and crowd batching switches itself off, since this game's
     * world drawing skips the stage it waits for: villagers would vanish every frame.
     * Buffers of textures nobody used for a while are freed.
     */
    @SubscribeEvent
    public static void onFrameEnd(RenderFrameEvent.Post event) {
        inWorldEntity = false;
        if (!started.isEmpty()) {
            for (Slot slot : started) {
                MeshData mesh = slot.builder.build();
                if (mesh != null) mesh.close();
                slot.builder = null;
            }
            started.clear();
            droppedFrames++;
            enabled = false;
            DynamicVillages.LOGGER.warn("[CrowdBatching] Villagers were drawn but no entity stage came after; "
                    + "one frame of them was dropped and crowd batching switched itself off (/villagerfps crowd on retries).");
        }
        frame++;
        countedFrames++;
        if (frame % IDLE_FRAMES == 0) {
            for (Iterator<Slot> it = SLOTS.values().iterator(); it.hasNext(); ) {
                Slot slot = it.next();
                if (frame - slot.usedFrame > IDLE_FRAMES) {
                    slot.bytes.close();
                    it.remove();
                }
            }
        }
    }

    /** One line for the {@code [ClientCost]} log, then the sums start over. */
    static String costLine() {
        int frames = Math.max(1, countedFrames);
        String line = String.format(Locale.ROOT,
                "Crowd batching %s: draws through it %.0f/frame, batches sent at the end %.1f/frame, %.2f ms/frame,"
                        + " %.1f MB/frame (biggest batch %.1f MB), %d textures kept, frames dropped %d",
                state(), (double) keptDraws / frames, (double) sentBatches / frames, sendNanos / 1e6 / frames,
                sentBytes / 1048576.0 / frames, biggestBatch / 1048576.0, SLOTS.size(), droppedFrames);
        keptDraws = sentBatches = sendNanos = sentBytes = 0;
        biggestBatch = 0;
        countedFrames = 0;
        return line;
    }
}
