package com.alex.test.ui.screen.mayor;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.client.VillagerGeoModel;
import com.alex.test.client.VillagerTheme;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.GraphicsStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Villagers made on this computer for the Theme tab and never put in a world: one per job and skin
 * ({@link #ENTRIES}), drawn like the player in the inventory while a skin set is forced for that one drawing
 * ({@link VillagerTheme#drawAs}). Only the ones on screen are made; one spare villager answers which skins a set
 * has painted. Their own clock ({@link NpcEntity#setPreviewClock}) keeps the idle clip smooth.
 *
 * <p>The villager drawing code needed no "is a preview" guard: a drawing scaled up like this keeps every detail
 * (DetailCull reads no distance, so no crowd or far-away shortcut), these villagers have no name, thought bubble
 * or alert glow, and they are not drawn in the world's entity pass (no crowd batching). If a drawing still fails,
 * previews stop for this window and the tab shows an item instead.
 */
final class VillagerPreview {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** One job and one of its skins. */
    record Entry(Profession job, int variant, int variants) {
        /** "Guard 2 of 4", or just "Cook". */
        String label() {
            String name = job.shownName();
            return variants > 1
                    ? Tr.s("screen.dynamicvillages.mayor.theme.variant", "%s %s of %s", name, variant + 1, variants)
                    : name;
        }

        /** "Guard 2", or just "Cook": under a small villager in a set's grid. */
        String shortLabel() {
            String name = job.shownName();
            return variants > 1
                    ? Tr.s("screen.dynamicvillages.mayor.theme.variant_short", "%s %s", name, variant + 1)
                    : name;
        }

        /** Its name in the player's skin changes (guard_2). The captain's is Guard 1's: it wears that skin. */
        String skinKey() {
            return VillagerGeoModel.skinKey(job, variant);
        }
    }

    /** The small villagers' turn (cards and grid), and the big one's before the player turns it: a little to the side. */
    static final float SIDE_TURN = -20F;

    /** The jobs in the order the arrows step through them. Not children: they wear the jobless skin, only smaller. */
    private static final Profession[] ORDER = {
            Profession.FARMER, Profession.LUMBERJACK, Profession.GUARD, Profession.CAPTAIN, Profession.ARCHER,
            Profession.MAGE, Profession.RANCHER, Profession.COOK, Profession.BLACKSMITH, Profession.BUTCHER,
            Profession.MINER, Profession.BUILDER, Profession.BARD, Profession.CLERIC, Profession.DRUID,
            Profession.ENCHANTER, Profession.TEACHER, Profession.TRADESMAN, Profession.NONE};

    /** Every job and skin, in arrow order. */
    static final List<Entry> ENTRIES = entries();

    /** A card's strip: this many villagers, jobs the set has painted first, then these, one of each job. */
    static final int STRIP_SIZE = 6;
    private static final List<Profession> STRIP_ORDER = stripOrder();

    private static List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        for (Profession job : ORDER) {
            // A captain wears the guard's skins (and its cape): one is enough to show.
            int variants = job == Profession.CAPTAIN ? 1 : VillagerGeoModel.skinVariants(job);
            for (int i = 0; i < variants; i++) out.add(new Entry(job, i, variants));
        }
        return List.copyOf(out);
    }

    private static List<Profession> stripOrder() {
        Set<Profession> order = new LinkedHashSet<>(List.of(Profession.FARMER, Profession.GUARD, Profession.BARD,
                Profession.MINER, Profession.LUMBERJACK, Profession.COOK));
        order.addAll(List.of(ORDER));
        return List.copyOf(order);
    }

    /**
     * Model pixels from the feet to the top of the job's highest hat (geo/base_villager.json, measured 10-22). The
     * villager's box is only 30.4 high (1.9 blocks); sized by that, every hat was cut off at the top of the frame.
     */
    private static float drawnHeightPx(Profession job) {
        return switch (job) {
            case ENCHANTER -> 48F;        // the cone hat's tip
            case COOK -> 40.5F;           // the tall hat
            case GUARD, CAPTAIN -> 38.8F; // the Victorian custodian helmet's rose
            default -> 37.6F;             // the rancher's hat; other hats and heads are lower
        };
    }

    /** Ids for the villagers made here: negative, never a server entity's, so GeckoLib cannot mix one up with a real villager. */
    private static int nextId = -1_000_000;

    private final Map<Entry, NpcEntity> made = new HashMap<>();
    @Nullable private ClientLevel madeIn;
    /** One spare villager, dressed as each entry in turn only to ask which file it would wear; never drawn. */
    @Nullable private NpcEntity probe;
    /** Set when the spare villager failed: every skin then reads as not painted, with no more tries. */
    private boolean probeFailed;
    private static final byte UNKNOWN = 0;
    private static final byte PAINTED = 1;
    private static final byte NOT_PAINTED = 2;
    /** {@link #paintedIn}'s answers by set and entry; cleared when {@link VillagerTheme#filesVersion} changes. */
    private final byte[][] painted = new byte[VillagerTheme.values().length][ENTRIES.size()];
    private int paintedVersion = Integer.MIN_VALUE;
    private final Map<VillagerTheme, List<Entry>> strips = new EnumMap<>(VillagerTheme.class);
    private int stripsVersion = Integer.MIN_VALUE;
    @Nullable private List<Entry> lookStrip;
    private int lookStripKey;
    /** Client ticks while the tab showed: the preview villagers' clock. */
    private int ticks;
    /** Set at the first error: no more previews in this window. */
    private boolean broken;

    /** False once a preview failed in this window: the tab then shows no villagers. */
    boolean working() {
        return !broken;
    }

    /** Every client tick while the tab shows. */
    void tick() {
        ticks++;
        for (NpcEntity npc : made.values()) npc.tickCount++;
    }

    /** The world the villagers are made in; a new world drops the old ones. Null without a world. */
    @Nullable
    private ClientLevel level() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != madeIn) {
            made.clear();
            probe = null;
            probeFailed = false;
            madeIn = level;
        }
        return level;
    }

    /** This entry's villager, made the first time it is drawn; null without a world or after an error. */
    @Nullable
    private NpcEntity villager(Entry entry) {
        if (broken) return null;
        ClientLevel level = level();
        if (level == null) return null;
        NpcEntity npc = made.get(entry);
        if (npc != null) return npc;
        npc = make(level);
        if (npc == null) return null;
        dress(npc, entry);
        made.put(entry, npc);
        return npc;
    }

    @Nullable
    private NpcEntity make(ClientLevel level) {
        try {
            NpcEntity npc = new NpcEntity(DynamicVillages.NPC.get(), level);
            npc.setId(nextId--);
            return npc;
        } catch (RuntimeException e) {
            fail(e);
            return null;
        }
    }

    /** Its job and skin. Guards and archers pick their skin from the UUID's hash: (0, n) hashes to n. */
    private static void dress(NpcEntity npc, Entry entry) {
        npc.setUUID(new UUID(0L, entry.variant()));
        npc.setProfession(entry.job());
        npc.setPreviewSkin(entry.variant());
    }

    /**
     * Whether {@code set} has its own picture for this entry's skin; Classic has them all. Each answer is kept until
     * the next resource reload: a set's grid asks for every small villager in sight, every frame.
     */
    boolean paintedIn(Entry entry, VillagerTheme set) {
        if (set == VillagerTheme.CLASSIC) return true;
        if (paintedVersion != VillagerTheme.filesVersion()) {
            for (byte[] answers : painted) Arrays.fill(answers, UNKNOWN);
            paintedVersion = VillagerTheme.filesVersion();
        }
        int index = ENTRIES.indexOf(entry);
        byte known = index < 0 ? UNKNOWN : painted[set.ordinal()][index];
        if (known != UNKNOWN) return known == PAINTED;
        boolean answer = askPainted(entry, set);
        // Not kept without a world or after the spare villager failed: those answers are "no" only for now.
        if (index >= 0 && level() != null && !probeFailed) painted[set.ordinal()][index] = answer ? PAINTED : NOT_PAINTED;
        return answer;
    }

    private boolean askPainted(Entry entry, VillagerTheme set) {
        ClientLevel level = level();
        if (level == null || probeFailed) return false;
        try {
            if (probe == null) {
                // Made apart from the drawn ones, so the answers stay right after previews stopped.
                probe = new NpcEntity(DynamicVillages.NPC.get(), level);
                probe.setId(nextId--);
            }
            dress(probe, entry);
            return !texture(probe, set).equals(texture(probe, VillagerTheme.CLASSIC));
        } catch (RuntimeException e) {
            probeFailed = true;
            probe = null;
            LOGGER.warn("[Theme tab] Could not tell which skins a set has painted", e);
            return false;
        }
    }

    /** The set whose picture {@code theme} shows for this skin: its own, or Classic's while it has none. */
    VillagerTheme looksLike(Entry entry, VillagerTheme theme) {
        return paintedIn(entry, theme) ? theme : VillagerTheme.CLASSIC;
    }

    /** The set this skin is drawn from instead while the set in use is in use, by the player's change; null: none. */
    @Nullable
    static VillagerTheme changeOf(Entry entry) {
        return VillagerTheme.changeOf(VillagerTheme.current(), entry.skinKey());
    }

    /** The set whose picture villagers with this skin wear in the world now: the set in use and the player's changes. */
    VillagerTheme worn(Entry entry) {
        VillagerTheme from = changeOf(entry);
        return looksLike(entry, from != null ? from : VillagerTheme.current());
    }

    private static ResourceLocation texture(NpcEntity npc, VillagerTheme set) {
        VillagerTheme.drawAs(set);
        try {
            return Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(npc).getTextureLocation(npc);
        } finally {
            VillagerTheme.drawAs(null);
        }
    }

    /** The villagers on a set's card: jobs it has painted first, then the others in strip order, one of each job. */
    List<Entry> strip(VillagerTheme set) {
        if (stripsVersion != VillagerTheme.filesVersion()) {
            strips.clear();
            stripsVersion = VillagerTheme.filesVersion();
        }
        List<Entry> picked = strips.get(set);
        if (picked == null) {
            picked = pickStrip(set);
            strips.put(set, picked);
        }
        return picked;
    }

    /**
     * The villagers on the "Your look" card: skins the player changed first, then the set in use's painted ones, then
     * the others, one of each job. Picked again when the set in use, the changes or the files change.
     */
    List<Entry> lookStrip() {
        int key = VillagerTheme.current().ordinal() + 31 * (VillagerTheme.changesVersion() + 31 * VillagerTheme.filesVersion());
        if (lookStrip == null || lookStripKey != key) {
            VillagerTheme inUse = VillagerTheme.current();
            lookStrip = pickStrip(entry -> changeOf(entry) != null, entry -> paintedIn(entry, inUse), entry -> true);
            lookStripKey = key;
        }
        return lookStrip;
    }

    private List<Entry> pickStrip(VillagerTheme set) {
        return pickStrip(entry -> paintedIn(entry, set), entry -> true);
    }

    /** Up to {@link #STRIP_SIZE} entries, one of each job in strip order: those {@code passes[0]} takes first, and so on. */
    @SafeVarargs
    private static List<Entry> pickStrip(Predicate<Entry>... passes) {
        List<Entry> picked = new ArrayList<>(STRIP_SIZE);
        Set<Profession> jobs = EnumSet.noneOf(Profession.class);
        for (Predicate<Entry> pass : passes) {
            for (Profession job : STRIP_ORDER) {
                if (picked.size() == STRIP_SIZE) return List.copyOf(picked);
                if (jobs.contains(job)) continue;
                for (Entry entry : ENTRIES) {
                    if (entry.job() != job || !pass.test(entry)) continue;
                    picked.add(entry);
                    jobs.add(job);
                    break;
                }
            }
        }
        return List.copyOf(picked);
    }

    /**
     * Draws the entry's villager in {@code set}, feet to hat top as tall as the box allows, clipped to the box:
     * {@code turn} degrees round (0 faces you, more turns its face to your left), the whole villager tipped
     * {@code tilt} degrees round its middle (more shows it from above). Head and body face the same way, eyes ahead.
     * False when nothing was drawn (no world, or previews are off).
     */
    boolean drawTurned(GuiGraphics graphics, Entry entry, VillagerTheme set, int x1, int y1, int x2, int y2,
                       float turn, float tilt) {
        NpcEntity npc = villager(entry);
        if (npc == null) return false;
        Minecraft minecraft = Minecraft.getInstance();
        // A pixel of air over the highest hat, and a little room for the tilt.
        float drawn = (drawnHeightPx(entry.job()) + 1F) / 16F;
        int scale = Math.max(1, (int) ((y2 - y1) * 0.92F / drawn));
        // Vanilla puts the middle of the villager's box in the middle; this lifts it so feet to hat top is centred.
        float yOffset = (drawn - npc.getBbHeight()) / 2F;
        // Vanilla passes a whole tick: the frame's part of a tick goes in the villager's own clock instead
        // (true: still moving while /tick freeze holds the world).
        npc.setPreviewClock(ticks + minecraft.getTimer().getGameTimeDeltaPartialTick(true) - 1F);
        PoseStack.Pose before = graphics.pose().last();
        GraphicsStatus graphicsMode = minecraft.options.graphicsMode().get();
        VillagerTheme.drawAs(set);
        try {
            renderTurned(graphics, x1, y1, x2, y2, scale, yOffset, turn, tilt, npc);
            return true;
        } catch (RuntimeException e) {
            fail(e);
            // Put back what the half-done drawing left: its pose steps, its clip box, the graphics mode vanilla
            // sets to Fancy around it under Fabulous (it would be saved to options.txt), lighting and shadows.
            PoseStack pose = graphics.pose();
            while (pose.last() != before && !pose.clear()) pose.popPose();
            graphics.disableScissor();
            if (minecraft.options.graphicsMode().get() != graphicsMode) minecraft.options.graphicsMode().set(graphicsMode);
            Lighting.setupFor3DItems();
            minecraft.getEntityRenderDispatcher().setRenderShadow(true);
            return false;
        } finally {
            VillagerTheme.drawAs(null);
        }
    }

    /**
     * NeoForge's {@link InventoryScreen#renderEntityInInventoryFollowsAngle} with the player's turn in place of the
     * mouse's: body, head and eyes all face {@code turn}, and the tip goes round the villager's middle rather than
     * its feet, so it stays in the middle of the box and never sinks behind the window. Like vanilla, nothing is put
     * back after an error: {@link #drawTurned} does that.
     */
    private static void renderTurned(GuiGraphics graphics, int x1, int y1, int x2, int y2, int scale, float yOffset,
                                     float turn, float tilt, NpcEntity npc) {
        graphics.enableScissor(x1, y1, x2, y2);
        // Vanilla tips the villager back for a mouse above it, so "seen from above" is the other way round.
        float tip = -tilt * Mth.DEG_TO_RAD;
        Quaternionf tipped = new Quaternionf().rotateX(tip);
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI).mul(tipped);
        float bodyRot = npc.yBodyRot;
        float yRot = npc.getYRot();
        float xRot = npc.getXRot();
        float headRotO = npc.yHeadRotO;
        float headRot = npc.yHeadRot;
        float facing = 180F + turn;
        npc.yBodyRot = facing;
        npc.setYRot(facing);
        npc.setXRot(0F);
        npc.yHeadRot = facing;
        npc.yHeadRotO = facing;
        float entityScale = npc.getScale();
        // Vanilla puts the feet this far under the box's middle; turned with the tip, it keeps the villager's middle there.
        float lift = npc.getBbHeight() / 2F + yOffset * entityScale;
        // Toward the player by half its height: tipped back, a tall hat would otherwise go behind the window's
        // background (drawn at depth 0) and vanish. Still far under the hover text's 400.
        graphics.pose().pushPose();
        graphics.pose().translate(0F, 0F, lift * scale);
        InventoryScreen.renderEntityInInventory(graphics, (x1 + x2) / 2F, (y1 + y2) / 2F, scale / entityScale,
                new Vector3f(0F, lift * Mth.cos(tip), -lift * Mth.sin(tip)), pose, tipped, npc);
        graphics.pose().popPose();
        npc.yBodyRot = bodyRot;
        npc.setYRot(yRot);
        npc.setXRot(xRot);
        npc.yHeadRotO = headRotO;
        npc.yHeadRot = headRot;
        graphics.disableScissor();
    }

    private void fail(RuntimeException e) {
        broken = true;
        made.clear();
        LOGGER.warn("[Theme tab] A preview villager could not be made or drawn; previews are off in this window", e);
    }
}
