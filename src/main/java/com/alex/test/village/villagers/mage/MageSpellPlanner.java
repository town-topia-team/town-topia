package com.alex.test.village.villagers.mage;

/**
 * Which spell the mage casts next. Pure: the activity reads the fight into a
 * {@link Situation} and casts what comes back.
 *
 * <p>In order: a Ward for a guard about to go down (keeping the front line up
 * is worth more than any damage), Starfall once per fight on a big pack, Frost
 * Nova on a pack of three or more (pinning them comes first), Chain Spark when
 * the spark has somewhere to jump, Frost Nova on a pair, and otherwise the
 * free Arcane Bolt. A spell the mage has not learnt, that its settings switch
 * off, that it cannot pay mana or reagents for, or has just cast is skipped. Out of reagents the mage still
 * fights, with bolts and Ward (mana only).
 */
public final class MageSpellPlanner {

    public enum Spell {
        ARCANE_BOLT(MageCastTiming.Clip.BOLT, 0f, 0),
        FROST_NOVA(MageCastTiming.Clip.NOVA, MageBalance.NOVA_MANA, MageBalance.NOVA_REAGENTS),
        WARD(MageCastTiming.Clip.WARD, MageBalance.WARD_MANA, 0),
        CHAIN_SPARK(MageCastTiming.Clip.CHAIN, MageBalance.CHAIN_MANA, MageBalance.CHAIN_REAGENTS),
        STARFALL(MageCastTiming.Clip.STARFALL, MageBalance.STARFALL_MANA, MageBalance.STARFALL_REAGENTS);

        private final MageCastTiming.Clip clip;
        private final float mana;
        private final int reagents;

        Spell(MageCastTiming.Clip clip, float mana, int reagents) {
            this.clip = clip;
            this.mana = mana;
            this.reagents = reagents;
        }

        public MageCastTiming.Clip clip() { return clip; }

        public float mana() { return mana; }

        public int reagents() { return reagents; }

        public boolean learnt(int level) {
            return switch (this) {
                case ARCANE_BOLT -> true;
                case FROST_NOVA -> MageBalance.hasFrostNova(level);
                case WARD -> MageBalance.hasWard(level);
                case CHAIN_SPARK -> MageBalance.hasChainSpark(level);
                case STARFALL -> MageBalance.hasStarfall(level);
            };
        }

        /** Words for the diary. */
        public String label() {
            return switch (this) {
                case ARCANE_BOLT -> "Arcane Bolt";
                case FROST_NOVA -> "Frost Nova";
                case WARD -> "Ward";
                case CHAIN_SPARK -> "Chain Spark";
                case STARFALL -> "Starfall";
            };
        }
    }

    /**
     * The fight as the mage sees it this moment.
     *
     * @param packSize      mobs a Frost Nova on the densest spot in range would catch
     * @param chainSize     mobs a Chain Spark from the current target would reach
     * @param wardCandidate a guard or captain in range is fighting and low on health, and not warded
     * @param starfallUsed  Starfall already fell in this fight
     * @param novaReady     the nova's cooldown is over (likewise chain and ward)
     * @param switchedOff   spells the player has turned off in the mage's settings
     */
    public record Situation(int level, float mana, int reagents,
                            int packSize, int chainSize,
                            boolean wardCandidate, boolean starfallUsed,
                            boolean novaReady, boolean chainReady, boolean wardReady,
                            java.util.Set<Spell> switchedOff) {

        public Situation {
            switchedOff = switchedOff == null ? java.util.Set.of() : java.util.Set.copyOf(switchedOff);
        }
    }

    private MageSpellPlanner() {}

    public static Spell choose(Situation s) {
        if (s.wardCandidate() && s.wardReady() && affordable(Spell.WARD, s)) return Spell.WARD;
        if (!s.starfallUsed() && s.packSize() >= MageBalance.STARFALL_MIN_MOBS
                && affordable(Spell.STARFALL, s)) return Spell.STARFALL;
        if (s.novaReady() && s.packSize() >= MageBalance.NOVA_PACK_MOBS
                && affordable(Spell.FROST_NOVA, s)) return Spell.FROST_NOVA;
        if (s.chainReady() && s.chainSize() >= MageBalance.CHAIN_MIN_MOBS
                && affordable(Spell.CHAIN_SPARK, s)) return Spell.CHAIN_SPARK;
        if (s.novaReady() && s.packSize() >= MageBalance.NOVA_MIN_MOBS
                && affordable(Spell.FROST_NOVA, s)) return Spell.FROST_NOVA;
        return Spell.ARCANE_BOLT;
    }

    /** Learnt, switched on, and the mage has the mana and the reagents for it. */
    public static boolean affordable(Spell spell, Situation s) {
        return spell.learnt(s.level()) && !s.switchedOff().contains(spell)
                && s.mana() >= spell.mana() && s.reagents() >= spell.reagents();
    }
}
