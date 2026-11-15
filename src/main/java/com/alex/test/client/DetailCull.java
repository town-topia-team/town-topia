package com.alex.test.client;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.Set;
import java.util.WeakHashMap;

/**
 * Small parts of a villager left out once it is small on screen: a villager
 * {@link #facePx} pixels tall has eyes under a pixel wide, so its eyes, nose and
 * other fine pieces are not drawn; below {@link #propsPx} its hats, sleeves and the
 * like go too (that is the blocky limb tier, a few boxes anyway); below
 * {@link #itemsPx} the held tool and shield, each a texture switch of its own, are
 * skipped. Measured like the limb tiers ({@link BentLimb#pixelsPerBlock}), so a
 * spyglass or a zoom mod keeps everything. {@code /villagerfps detail} sets the three.
 *
 * <p>Armor ({@link GuardArmorGeoLayer}) thins out the same way: its trims, rivets and
 * straps are one model pixel across, so once a model pixel is narrower on screen than
 * {@link #armorTrimPx} they are left out; and a villager shorter on screen than
 * {@link #armorStiffPx} wears each arm and leg piece as one stiff box riding the bent
 * limb ({@link BentLimb#renderWorn}) instead of bending it band by band.
 * {@code /villagerfps armor} sets the two.
 *
 * <p>A part comes back only once the villager is {@link #DEAD_BAND} times taller
 * than the threshold, remembered per villager, so one walking at the edge does not
 * flicker. Decided once per villager in {@link #begin}, read by the bones and layers
 * while it is drawn, cleared by {@link #end}. Render thread only.
 *
 * <p>Crowd budget ({@code /villagerfps budget <full> <mid>}): pixels alone made a
 * fullscreen window keep far more detail on the same crowd than a small one (7 against
 * 12 FPS with ~390 guards, 2026-10-19). So the villagers on screen are also ranked by
 * how tall they look, from the frame before: the {@link #budgetFull} tallest keep what
 * the pixel rules give them, those down to rank {@link #budgetMid} lose their armor
 * trims and bend their limbs in at most 6 strips a half, and the rest wear stiff armor and bend
 * in at most 3. The pixel rules still apply on top ("never more detail than this"). A
 * villager keeps its rank tier at least {@link #BUDGET_HOLD_MS} after it changed, so two
 * passing each other in the ranking do not swap looks every frame.
 */
final class DetailCull {

    /** Villager height on screen, in pixels, below which each group is left out; 0 = never. */
    static volatile float facePx = 24f;
    static volatile float propsPx = 12f;
    static volatile float itemsPx = 24f;

    /** Screen pixels a model pixel must span for armor trims to be drawn; 0 = always drawn. */
    static volatile float armorTrimPx = 1.5f;
    /** Villager height on screen, in pixels, below which limb armor is drawn stiff; 0 = never. */
    static volatile float armorStiffPx = 64f;
    static final float ARMOR_TRIM_PX = 1.5f, ARMOR_STIFF_PX = 64f;
    /** Stiff arm and leg armor drawn as one plain box per piece ({@link ArmorFarModel}). */
    static volatile boolean armorFarModel = true;
    /**
     * Villager height on screen, in pixels, below which arm and leg armor is one plain box
     * per piece that still bends with the limb (plan P3); 0 = only once stiff. The crowd
     * budget's middle ranks get it too.
     */
    static volatile float armorBoxPx = 96f;
    static final float ARMOR_BOX_PX = 96f;

    private static final float DEAD_BAND = 1.25f;
    /** An adult villager top to bottom, in blocks. */
    private static final float VILLAGER_HEIGHT = 1.9f;
    /** The same in model pixels: screen pixels per model pixel is the height on screen over this. */
    private static final float VILLAGER_MODEL_PX = VILLAGER_HEIGHT * 16f;

    /** Fine pieces: under a pixel once the villager is {@link #facePx} tall. */
    static final Set<String> FACE = Set.of(
            "noses", "nose_1", "nose_3", "eyes_bg", "eyes", "R_eye", "L_eye", "eyebrow",
            "miner_goggles", "miner_beard", "bard_feather", "bard_plume", "bard_cap_feather",
            "miner_rope", "miner_lantern");

    /** Silhouette pieces: hats, sleeves, puffs, the miner's sack. */
    static final Set<String> PROPS = Set.of(
            "farmer_hat", "bard_hat", "bard_cavalier", "bard_cap", "bard_cap_peak", "bard_wreath",
            "miner_cap", "miner_slouch",
            "farmer_left_sleeve", "farmer_right_sleeve", "bard_left_puff", "bard_right_puff",
            "bard_ruff", "bard_lute", "miner_sack");

    /** Villagers on screen that keep full detail; 0 = crowd budget off. */
    static volatile int budgetFull = 64;
    /** Last rank with trims off and 6-band limbs; past it stiff armor. 0 = no stiff rank. */
    static volatile int budgetMid = 160;
    static final int BUDGET_FULL = 64, BUDGET_MID = 160;
    /** A villager's rank tier changes at most this often, in milliseconds. */
    static final long BUDGET_HOLD_MS = 1000;
    private static final int CROWD_FULL = 0, CROWD_MID = 1, CROWD_FAR = 2;

    private static final int FACE_BIT = 1, PROPS_BIT = 2, ITEMS_BIT = 4, TRIMS_BIT = 8, STIFF_BIT = 16, BOX_BIT = 32;

    /** One villager as last decided: its pixel rule result and its crowd tier. */
    private static final class Seen {
        /** What the pixel rules left out, for their dead band (the budget is not in here). */
        byte pixelBits;
        byte tier = CROWD_FULL;
        /** When {@link #tier} last changed (ms); 0 = free to take the next rank at once. */
        long since;
        /** The frame it was last ranked in. */
        int frame = Integer.MIN_VALUE;
    }

    /** Each villager's last decision, so a part comes back only past the dead band. */
    private static final WeakHashMap<Object, Seen> LAST = new WeakHashMap<>();
    private static final Vector3f SCALE = new Vector3f();

    /** What is left out of the villager being drawn now. */
    private static int hidden;
    /** The crowd tier of the villager being drawn now ({@link #CROWD_FULL} when none). */
    private static int tierNow = CROWD_FULL;
    /** The coarsest limb detail the crowd budget allows the villager being drawn ({@link BentLimb#TIER_SMOOTH} = any). */
    private static int limbCap;

    // The ranking: heights on screen collected this frame, cut-offs from the frame before.
    private static float[] sizes = new float[512];
    private static float[] sorted = new float[512];
    private static int sized;
    /** Heights on screen of the {@link #budgetFull}-th and {@link #budgetMid}-th tallest last frame; 0 = everyone in. */
    private static float fullCutoffPx, midCutoffPx;
    private static int rankFrame = Integer.MIN_VALUE;
    private static long rankMillis;
    private static final int[] tierCount = new int[3];
    private static final int[] lastTierCount = new int[3];
    private static int lastRanked;

    private DetailCull() {}

    /**
     * Decides for {@code entity}, about to be drawn with {@code poseStack} standing at
     * its feet. An inventory portrait (scaled up, no distance) keeps everything.
     */
    static void begin(NpcEntity entity, PoseStack poseStack) {
        int frame = VillagerFrameStats.frame();
        if (frame != rankFrame) rank(frame);
        Matrix4f pose = poseStack.last().pose();
        float scale = pose.getScale(SCALE).y();
        float distance = BentLimb.distanceOf(pose, scale);
        if (distance <= 0f) {
            hidden = 0;
            limbCap = BentLimb.TIER_SMOOTH;
            tierNow = CROWD_FULL;
            return;
        }
        float height = VILLAGER_HEIGHT * scale
                * (entity.getProfession() == Profession.CHILD ? NpcGeoRenderer.CHILD_SCALE : 1f);
        float px = BentLimb.pixelsPerBlock(distance) * height;

        Seen seen = LAST.computeIfAbsent(entity, e -> new Seen());
        int was = seen.pixelBits, now = 0;
        if (below(px, facePx, (was & FACE_BIT) != 0)) now |= FACE_BIT;
        if (below(px, propsPx, (was & PROPS_BIT) != 0)) now |= PROPS_BIT;
        if (below(px, itemsPx, (was & ITEMS_BIT) != 0)) now |= ITEMS_BIT;
        if (below(px, armorTrimPx * VILLAGER_MODEL_PX, (was & TRIMS_BIT) != 0)) now |= TRIMS_BIT;
        if (below(px, armorStiffPx, (was & STIFF_BIT) != 0)) now |= STIFF_BIT;
        if (below(px, armorBoxPx, (was & BOX_BIT) != 0)) now |= BOX_BIT;
        seen.pixelBits = (byte) now;

        int tier = crowdTier(seen, px, frame);
        if (tier >= CROWD_MID) now |= TRIMS_BIT | BOX_BIT;
        if (tier >= CROWD_FAR) now |= STIFF_BIT;
        tierNow = tier;
        hidden = now;
        limbCap = tier == CROWD_FAR ? BentLimb.TIER_SIMPLE
                : tier == CROWD_MID ? BentLimb.TIER_MEDIUM : BentLimb.TIER_SMOOTH;
    }

    private static boolean below(float px, float threshold, boolean wasHidden) {
        return wasHidden ? px < threshold * DEAD_BAND : px < threshold;
    }

    /**
     * The villager's crowd tier this frame: where its height on screen {@code px} falls
     * against last frame's ranking, changed at most once per {@link #BUDGET_HOLD_MS}. One
     * that was not on screen last frame takes its tier at once and may take the next one
     * at once too (the cut-offs may come from another view, after a turn of the camera).
     */
    private static int crowdTier(Seen seen, float px, int frame) {
        if (budgetFull <= 0) {
            seen.tier = CROWD_FULL;
            return CROWD_FULL;
        }
        // The sun's pass (shader shadows): drawn as the camera's pass last decided.
        if (IrisShadowPass.active()) return seen.tier;
        if (seen.frame == frame) return seen.tier;       // drawn twice this frame: ranked once
        if (sized == sizes.length) sizes = java.util.Arrays.copyOf(sizes, sized * 2);
        sizes[sized++] = px;

        int target = px >= fullCutoffPx ? CROWD_FULL : px >= midCutoffPx ? CROWD_MID : CROWD_FAR;
        if (seen.frame != frame - 1) {
            seen.tier = (byte) target;
            seen.since = 0L;
        } else if (target != seen.tier && rankMillis - seen.since >= BUDGET_HOLD_MS) {
            seen.tier = (byte) target;
            seen.since = rankMillis;
        }
        seen.frame = frame;
        tierCount[seen.tier]++;
        return seen.tier;
    }

    /** A new frame: last frame's heights on screen become the cut-offs this frame ranks by. */
    private static void rank(int frame) {
        rankFrame = frame;
        rankMillis = net.minecraft.Util.getMillis();
        int n = sized;
        int full = budgetFull;
        int mid = budgetMid <= 0 ? Integer.MAX_VALUE : Math.max(full, budgetMid);
        if (sorted.length < n) sorted = new float[sizes.length];
        System.arraycopy(sizes, 0, sorted, 0, n);
        java.util.Arrays.sort(sorted, 0, n);            // ascending: the k-th tallest is sorted[n - k]
        fullCutoffPx = full > 0 && n > full ? sorted[n - full] : 0f;
        midCutoffPx = full > 0 && n > mid ? sorted[n - mid] : 0f;
        lastRanked = n;
        System.arraycopy(tierCount, 0, lastTierCount, 0, tierCount.length);
        java.util.Arrays.fill(tierCount, 0);
        sized = 0;
    }

    /** The villager is drawn; other renderers sharing the layers see nothing hidden. */
    static void end() {
        hidden = 0;
        tierNow = CROWD_FULL;
        limbCap = BentLimb.TIER_SMOOTH;
    }

    /**
     * The coarsest limb tier the crowd budget gives the villager being drawn:
     * {@link BentLimb#TIER_SMOOTH} (no limit), {@link BentLimb#TIER_MEDIUM} or {@link BentLimb#TIER_SIMPLE}.
     */
    static int limbTierCap() {
        return limbCap;
    }

    /** The crowd budget and how it split the villagers last frame, for {@code /villagerfps}. */
    static String budgetReport() {
        int full = budgetFull, mid = budgetMid;
        if (full <= 0)
            return "Crowd detail budget: off, every villager by its size on screen alone (/villagerfps budget on)";
        String ranks = mid <= 0
                ? "the rest lose armor trims and bend limbs in at most 6 strips a half (no stiff rank)"
                : mid <= full
                ? "the rest wear stiff armor and bend limbs in at most 3 strips a half"
                : "ranks " + (full + 1) + " to " + mid + " lose armor trims and bend limbs in at most 6 strips a half, "
                        + "the rest wear stiff armor and bend limbs in at most 3";
        return "Crowd detail budget: the " + full + " villagers that look biggest on screen keep full detail, "
                + ranks + ". Last frame: " + lastRanked + " ranked, " + lastTierCount[CROWD_FULL] + " full, "
                + lastTierCount[CROWD_MID] + " middle, " + lastTierCount[CROWD_FAR] + " far";
    }

    /** The same on one line, for the {@code [ClientCost]} log. */
    static String budgetCompact() {
        if (budgetFull <= 0) return "crowd budget off";
        return "crowd budget " + budgetFull + "/" + budgetMid + ": ranked " + lastRanked + ", full "
                + lastTierCount[CROWD_FULL] + ", middle " + lastTierCount[CROWD_MID] + ", far " + lastTierCount[CROWD_FAR];
    }

    /** Whether the bone {@code name} is left out of the villager being drawn. */
    static boolean hidesBone(String name) {
        return ((hidden & FACE_BIT) != 0 && FACE.contains(name))
                || ((hidden & PROPS_BIT) != 0 && PROPS.contains(name));
    }

    /** The villager being drawn is in the crowd budget's far ranks (a stand-in, {@link StandIn}). */
    static boolean crowdFar() {
        return tierNow == CROWD_FAR;
    }

    /** Held tool, shield and orb. */
    static boolean hidesItems() {
        return (hidden & ITEMS_BIT) != 0;
    }

    /** Cape and instruments: big enough to keep until the props go. */
    static boolean hidesProps() {
        return (hidden & PROPS_BIT) != 0;
    }

    /** Armor trims (one model pixel across) are left out of the villager being drawn. */
    static boolean dropsArmorTrims() {
        return (hidden & TRIMS_BIT) != 0;
    }

    /** The villager being drawn wears its arm and leg armor as stiff pieces. */
    static boolean stiffArmor() {
        return (hidden & STIFF_BIT) != 0;
    }

    /** The villager being drawn wears each arm and leg piece as one plain box (bent or stiff). */
    static boolean boxArmor() {
        return (hidden & BOX_BIT) != 0;
    }

    /** Villager height on screen, in pixels, below which armor trims go. */
    static float armorTrimHeightPx() {
        return armorTrimPx * VILLAGER_MODEL_PX;
    }
}
