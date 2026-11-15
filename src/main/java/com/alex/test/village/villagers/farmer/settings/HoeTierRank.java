package com.alex.test.village.villagers.farmer.settings;

/**
 * Pure (Minecraft-free) worst-to-best hoe tier ordering, kept separate from
 * {@link FarmerSettings.HoeTier} so the ordering itself is unit-testable
 * without any Minecraft classes on the test classpath. {@link FarmerSettings.HoeTier}
 * delegates its {@code rank()} and {@code DISPLAY_ORDER} to this table by name,
 * so the two can never independently drift.
 */
public enum HoeTierRank {
    WOODEN(1),
    GOLDEN(2),
    STONE(3),
    IRON(4),
    DIAMOND(5),
    NETHERITE(6);

    private final int rank;

    HoeTierRank(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
