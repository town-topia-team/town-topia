package com.alex.test.client;

import com.alex.test.mixin.LevelRendererAccessor;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;

/**
 * Whether a mob stands only in chunk sections the camera cannot see (plan step 0e,
 * docs/plans/2026-10-22-hundred-villagers-fps.md). Minecraft works out the visible
 * sections once a frame for drawing blocks (inside closed houses and behind hills are
 * not) but still draws every entity inside the view cone. For now this only counts
 * such villagers on the {@code [ClientCost]} budget line, to size step 3 (not drawing
 * them). The visible list is copied into a set once per frame, only while the cost
 * log is on. Never in the shader shadow pass (the list is the camera's, not the sun's).
 */
final class SectionVisibility {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final LongOpenHashSet VISIBLE = new LongOpenHashSet(4096);
    private static int frame = Integer.MIN_VALUE;
    private static int lastSections;
    /** False once the list could not be read (another Minecraft, a mod replacing the renderer). */
    private static boolean usable = true;

    private SectionVisibility() {}

    /** True when no section the mob's box touches is in the camera's visible list this frame. */
    static boolean unseen(Entity entity) {
        if (!usable || IrisShadowPass.active()) return false;
        try {
            refresh();
        } catch (RuntimeException | LinkageError e) {
            usable = false;
            LOGGER.warn("[SectionVisibility] cannot read the camera's visible sections; the unseen count stays 0", e);
            return false;
        }
        if (VISIBLE.isEmpty()) return false;             // nothing known this frame: count as seen
        AABB box = entity.getBoundingBox();
        int x0 = SectionPos.blockToSectionCoord(Mth.floor(box.minX)), x1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxX));
        int y0 = SectionPos.blockToSectionCoord(Mth.floor(box.minY)), y1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxY));
        int z0 = SectionPos.blockToSectionCoord(Mth.floor(box.minZ)), z1 = SectionPos.blockToSectionCoord(Mth.floor(box.maxZ));
        for (int x = x0; x <= x1; x++)
            for (int y = y0; y <= y1; y++)
                for (int z = z0; z <= z1; z++)
                    if (VISIBLE.contains(SectionPos.asLong(x, y, z))) return false;
        return true;
    }

    /** How many sections were visible when the set was last copied. */
    static int sectionsVisible() {
        return lastSections;
    }

    private static void refresh() {
        int now = VillagerFrameStats.frame();
        if (now == frame) return;
        frame = now;
        VISIBLE.clear();
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        for (SectionRenderDispatcher.RenderSection section : ((LevelRendererAccessor) renderer).getVisibleSections()) {
            BlockPos origin = section.getOrigin();
            VISIBLE.add(SectionPos.asLong(SectionPos.blockToSectionCoord(origin.getX()),
                    SectionPos.blockToSectionCoord(origin.getY()), SectionPos.blockToSectionCoord(origin.getZ())));
        }
        lastSections = VISIBLE.size();
    }
}
