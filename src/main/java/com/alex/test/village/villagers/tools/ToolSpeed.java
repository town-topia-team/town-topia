package com.alex.test.village.villagers.tools;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * How fast a work tool makes each stroke, the same for every job that times
 * its strokes by the tool (farmer hoe, axe and shears; lumberjack axe): 1×
 * bare-handed or with a plain wooden tool, up to {@link #MAX_SPEED}× for
 * netherite with Efficiency V. The material and Efficiency give half each:
 *
 * <pre>
 *   material   bonus       Efficiency   bonus
 *   wood       +0          I            +0.04
 *   gold       +0.03       II           +0.08
 *   stone      +0.05       III          +0.12
 *   iron       +0.10       IV           +0.16
 *   diamond    +0.15       V            +0.20
 *   netherite  +0.20
 * </pre>
 *
 * Shears come in one material, so only their Efficiency counts. Skill never
 * changes stroke speed. The miner keeps its own swing-count rule
 * (MinerBalance) but tops out at the same 1.4×.
 */
public final class ToolSpeed {

    /** Stroke speed of a netherite tool with Efficiency V; nothing works faster. */
    public static final double MAX_SPEED = 1.4;
    private static final double EFFICIENCY_PER_LEVEL = 0.04;

    private ToolSpeed() {}

    private static double materialBonus(Item tool) {
        return switch (ToolSelectionHelper.rank(tool)) {
            case 6 -> 0.20;
            case 5 -> 0.15;
            case 4 -> 0.10;
            case 3 -> 0.05;
            case 2 -> 0.03;
            default -> 0.0;
        };
    }

    public static double of(Item tool, double efficiencyLevel) {
        return Math.min(MAX_SPEED, 1.0 + materialBonus(tool)
                + EFFICIENCY_PER_LEVEL * Math.clamp(efficiencyLevel, 0.0, 5.0));
    }

    /**
     * {@link #of(Item, double)} for a stack, reading the Efficiency that works
     * (a player's at its share); empty is bare hands.
     */
    public static double of(ItemStack tool) {
        if (tool.isEmpty()) return 1.0;
        return of(tool.getItem(), ToolSelectionHelper.effectiveEnchantmentLevel(tool, Enchantments.EFFICIENCY));
    }

    /** The tool-choice rule for these jobs: fastest first, then enchantments, then uses left. */
    public static long quality(ItemStack tool) {
        return ToolSelectionHelper.byMeasure(of(tool), tool);
    }
}
