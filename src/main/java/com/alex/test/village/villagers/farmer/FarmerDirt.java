package com.alex.test.village.villagers.farmer;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.DirtLevel;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.TickStagger;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.Locale;

/**
 * How a farmer gets dirty, and the one thing short of a wash that helps: rain.
 *
 * <p>Work adds dirt per finished job, doubled when it is raining on the
 * farmer: wet fields are mud. One job in five ({@link #SKIP_CHANCE}) leaves
 * none, so two farmers on the same field do not dirty in lockstep. At the first
 * numbers (2.5 per till) a busy farmer was fully dirty in under two real
 * minutes; these are a quarter of those, which with the skips puts non-stop
 * work at stage 4 after about 8 to 10 real minutes, a rainy day in half that. Walking over soil in the rain muddies the boots,
 * but only up to {@link #RAIN_WALK_CAP}, inside the muddy-boots stage. Standing
 * in the rain without working rinses the rest off, slowly and never below
 * {@link DirtLevel#BOOTS}: rain does not clean boots that stand in mud.
 *
 * <p>One instance per villager, held by {@link NpcEntity}. It also counts the
 * day's dirt by source for the end-of-shift diary line, which is how the
 * numbers here get tuned. The count lives in memory only; a reload mid-day
 * starts it again.
 */
public final class FarmerDirt {

    /** One kind of finished farm job and the dirt it leaves. */
    public enum Work {
        TILL("till", 0.625f),
        HARVEST("harvest", 0.375f),
        PLANT("plant", 0.25f),
        /** Pruning fruit or cutting cane: a blade, not hands in the soil. */
        CUT("cut", 0.125f);

        final String label;
        final float dirt;

        Work(String label, float dirt) {
            this.label = label;
            this.dirt = dirt;
        }
    }

    /** Work in the rain leaves this many times the dirt. */
    public static final float RAIN_MULTIPLIER = 2f;
    /** Chance that a finished job leaves no dirt at all. */
    public static final float SKIP_CHANCE = 0.2f;
    /** Dirt per block walked over soil in the rain. */
    public static final float RAIN_WALK_PER_BLOCK = 0.1f;
    /** Rain walking never takes dirt past this: muddy boots, nothing more. */
    public static final float RAIN_WALK_CAP = 12f;
    /** Rain rinses one point of dirt this often. */
    public static final int RINSE_TICKS = 300;
    /** How often the rain checks run, on the villager's own staggered tick. */
    public static final int CHECK_TICKS = 20;
    /** Longer moves between checks are teleports or rescues, not walking. */
    private static final double MAX_WALK_PER_CHECK = 16.0;

    private final int[] counts = new int[Work.values().length];
    /** Jobs that rolled no dirt today. */
    private int skipped;
    private float rainBonus;
    private float rainWalk;
    private float rinsed;

    private double lastX;
    private double lastZ;
    private boolean hasLast;
    private int rinseTicks;

    /**
     * A finished job: adds its dirt, doubled in the rain, and counts it for the
     * day -- unless it rolls the {@link #SKIP_CHANCE} of leaving none.
     */
    public void onWork(NpcEntity npc, Work work) {
        if (npc.getRandom().nextFloat() < SKIP_CHANCE) {
            skipped++;
            return;
        }
        float amount = work.dirt;
        if (rainingOn(npc)) {
            amount *= RAIN_MULTIPLIER;
            rainBonus += amount - work.dirt;
        }
        counts[work.ordinal()]++;
        npc.addDirt(amount);
    }

    /**
     * Rain walking and rinsing. Called every server tick for farmers; does its
     * work only on the villager's own slot every {@link #CHECK_TICKS} ticks.
     */
    public void tick(NpcEntity npc) {
        if (!TickStagger.isSlot(npc.tickCount, npc.getId(), CHECK_TICKS)) return;
        double moved = hasLast ? Math.hypot(npc.getX() - lastX, npc.getZ() - lastZ) : 0.0;
        lastX = npc.getX();
        lastZ = npc.getZ();
        hasLast = true;

        if (!rainingOn(npc) || isSoilWork(npc)) {
            rinseTicks = 0;
            return;
        }
        if (moved > 0.05 && moved <= MAX_WALK_PER_CHECK && npc.onGround()
                && npc.getDirt() < RAIN_WALK_CAP && isSoil(npc.getBlockStateOn())) {
            float add = Math.min((float) moved * RAIN_WALK_PER_BLOCK, RAIN_WALK_CAP - npc.getDirt());
            npc.addDirt(add);
            rainWalk += add;
        }
        rinseTicks += CHECK_TICKS;
        if (rinseTicks >= RINSE_TICKS) {
            rinseTicks -= RINSE_TICKS;
            float drop = Math.min(1f, npc.getDirt() - DirtLevel.BOOTS);
            if (drop > 0f) {
                npc.addDirt(-drop);
                rinsed += drop;
            }
        }
    }

    /**
     * The day's dirt by source, for the end-of-shift diary line; starts the
     * next day's count.
     */
    public String endOfShiftSummary(NpcEntity npc) {
        StringBuilder out = new StringBuilder("DIRT today");
        float total = rainBonus + rainWalk - rinsed;
        StringBuilder parts = new StringBuilder();
        for (Work work : Work.values()) {
            int n = counts[work.ordinal()];
            total += n * work.dirt;
            if (parts.length() > 0) parts.append(", ");
            parts.append(String.format(Locale.ROOT, "%s %d×%s", work.label, n, trim(work.dirt)));
        }
        out.append(String.format(Locale.ROOT,
                " %+.1f (%s, no dirt %d, rain bonus %+.1f, rain walk %+.1f, rinsed %.1f)",
                total, parts, skipped, rainBonus, rainWalk, -rinsed));
        out.append(String.format(Locale.ROOT, " → dirt %.1f stage %d", npc.getDirt(), npc.getDirtStage()));
        Arrays.fill(counts, 0);
        skipped = 0;
        rainBonus = 0f;
        rainWalk = 0f;
        rinsed = 0f;
        return out.toString();
    }

    /**
     * Rain actually falling on the farmer: open sky above the head, in a biome
     * where it rains. False under a roof, in snow and in deserts.
     */
    private static boolean rainingOn(NpcEntity npc) {
        return npc.level().isRainingAt(npc.blockPosition().above());
    }

    /** Tilling, planting or harvesting right now. */
    private static boolean isSoilWork(NpcEntity npc) {
        VillagerAnimationState animation = npc.getAnimation();
        return animation != null && animation.isFarmWork();
    }

    /** Ground that turns to mud in the rain: farmland, and anything tagged dirt (grass, podzol, mud...). */
    private static boolean isSoil(BlockState state) {
        return state.is(Blocks.FARMLAND) || state.is(BlockTags.DIRT);
    }

    private static String trim(float value) {
        return value == Math.rint(value) ? Integer.toString((int) value) : Float.toString(value);
    }

    /** The job kind a finished farm job counts as, or null for jobs that touch no soil. */
    public static Work forJob(FarmerManager.JobType type) {
        return switch (type) {
            case TILL, RETILL -> Work.TILL;
            case HARVEST, PICK_BERRIES, PICK_GLOW_BERRIES -> Work.HARVEST;
            case PLANT, PLANT_CANE, PLANT_POD, PLANT_BUSH, BONEMEAL, COMPOST -> Work.PLANT;
            case HARVEST_FRUIT, HARVEST_CANE, HARVEST_POD -> Work.CUT;
            case FETCH_SUPPLIES, FETCH_BONE_MEAL, FETCH_COMPOST -> null;
        };
    }
}
