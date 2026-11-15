package com.alex.test.village.raid;

/**
 * A raider and what it costs out of the raid budget. The entity type behind
 * each is in {@link RaidSpawner}, so this enum loads without Minecraft.
 */
public enum RaiderKind {
    PILLAGER(2, "pillager"),
    VINDICATOR(3, "vindicator"),
    WITCH(3, "witch"),
    EVOKER(6, "evoker"),
    RAVAGER(8, "ravager"),
    // The night horde.
    ZOMBIE(1, "zombie"),
    HUSK(1, "husk"),
    DROWNED(1, "drowned"),
    BABY_ZOMBIE(1, "baby zombie"),
    ZOMBIE_VILLAGER(1, "zombie villager"),
    /** A zombie in iron armour with more health; tier 3 only. */
    BRUTE(3, "brute"),
    // Skeleton riders: a skeleton on a skeleton horse (strays in the snow, bogged in swamps), and on foot.
    SKELETON_RIDER(4, "skeleton rider"),
    STRAY_RIDER(4, "stray rider"),
    BOGGED_RIDER(4, "bogged rider"),
    SKELETON(2, "skeleton"),
    /**
     * The Illager Warlord: one leads the Bad Omen raid's last wave. It costs
     * nothing, as it comes on top of that wave's budget.
     */
    WARLORD(0, "warlord");

    private final int cost;
    private final String label;

    RaiderKind(int cost, String label) {
        this.cost = cost;
        this.label = label;
    }

    public int cost() { return cost; }

    public String label() { return label; }

    /** Comes riding a skeleton horse. */
    public boolean mounted() {
        return this == SKELETON_RIDER || this == STRAY_RIDER || this == BOGGED_RIDER;
    }

    /** The Warlord: it never counts against the cap on raiders alive. */
    public boolean boss() {
        return this == WARLORD;
    }

    /** Pillagers and vindicators may carry the banner and lead. */
    public boolean canLead() {
        return this == PILLAGER || this == VINDICATOR;
    }

    /** Reads a saved name; null when unknown. */
    public static RaiderKind byName(String name) {
        for (RaiderKind kind : values()) {
            if (kind.name().equals(name)) return kind;
        }
        return null;
    }
}
