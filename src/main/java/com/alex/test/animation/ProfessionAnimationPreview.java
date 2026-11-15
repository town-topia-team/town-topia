package com.alex.test.animation;

import com.alex.test.village.villagers.core.Profession;
import software.bernie.geckolib.animation.RawAnimation;

import java.util.List;

/** Work clips for stationary command previews, with lengths from the shipped animation JSON. */
public final class ProfessionAnimationPreview {
    public static final int LOOPS_PER_CLIP = 3;
    public static final float MIN_SPEED = 0.25f;
    public static final float MAX_SPEED = 3f;

    public record Clip(VillagerAnimationState state, RawAnimation animation, double seconds) {
        Clip(VillagerAnimationState state, String name, double seconds) {
            this(state, RawAnimation.begin().thenLoop(name), seconds);
        }

        public int previewTicks(float speed) {
            return VillagerAnimationState.WORK_BLEND_TICKS + 2
                    + (int) Math.ceil(LOOPS_PER_CLIP * seconds * 20.0 / speed);
        }
    }

    private static final Clip IDLE = new Clip(VillagerAnimationState.IDLE, "mixamo_idle", 9.9333);
    private static final Clip CHOP = new Clip(VillagerAnimationState.CHOP, "rok_chop", 2.4);
    private static final Clip LUMBER_CHOP_BAT = new Clip(VillagerAnimationState.LUMBER_CHOP, "lumberjack_chop_bat", 1.45);
    private static final Clip LUMBER_CHOP_NOTCH = new Clip(VillagerAnimationState.LUMBER_CHOP, "lumberjack_chop_notch", 2.8);
    private static final Clip LUMBER_CHOP_SHORT = new Clip(VillagerAnimationState.LUMBER_CHOP, "lumberjack_chop_short", 1.6);
    private static final Clip HARVEST_YANK = new Clip(VillagerAnimationState.HARVEST, "farmer_harvest_yank", 2.15);
    private static final Clip HARVEST_PLUCK = new Clip(VillagerAnimationState.HARVEST, "farmer_harvest_pluck", 2.15);
    private static final Clip PLANT_SOW = new Clip(VillagerAnimationState.PLANT, "farmer_plant_sow", 2.15);
    private static final Clip PLANT_PRESS = new Clip(VillagerAnimationState.PLANT, "farmer_plant_press", 2.15);
    private static final Clip PICK_LOW = new Clip(VillagerAnimationState.PICK_LOW, "farmer_pick_low", 2.2);
    private static final Clip PICK_THORN = new Clip(VillagerAnimationState.PICK_BUSH, "farmer_pick_low_thorn", 8.8);
    private static final Clip PICK_LOOK = new Clip(VillagerAnimationState.PICK_LOW, "farmer_pick_low_look", 8.8);
    private static final Clip PICK = new Clip(VillagerAnimationState.PICK, "farmer_pick_mid", 2.2);
    private static final Clip PICK_HIGH = new Clip(VillagerAnimationState.PICK_HIGH, "farmer_pick_high", 2.2);
    private static final Clip HARVEST_CHOP = new Clip(VillagerAnimationState.HARVEST_CHOP, "mixamo_attack_down", 2.2667);
    private static final Clip TILL = new Clip(VillagerAnimationState.TILL, "farmer_till_beginner", 3.5);
    private static final Clip TILL_SKILLED = new Clip(VillagerAnimationState.TILL_SKILLED, "farmer_till_skilled", 3.5);
    private static final Clip MINE = new Clip(VillagerAnimationState.MINE, "mixamo_attack_down", 2.2667);
    private static final Clip CRAFT = new Clip(VillagerAnimationState.CRAFTING, "mixamo_attack_down", 2.2667);
    private static final Clip CRAFT_TABLE = new Clip(VillagerAnimationState.CRAFT_TABLE, "craft_table", 6.0);
    private static final Clip SMITH_ANVIL = new Clip(VillagerAnimationState.SMITH_ANVIL, "smith_anvil_steady", 4.0);
    private static final Clip SMITH_ANVIL_HEAVY = new Clip(VillagerAnimationState.SMITH_ANVIL_HEAVY, "smith_anvil_heavy", 4.4);
    private static final Clip SMITH_ANVIL_DRAW = new Clip(VillagerAnimationState.SMITH_ANVIL_DRAW, "smith_anvil_draw", 6.2);
    private static final Clip SMITH_ENCHANT = new Clip(VillagerAnimationState.SMITH_ENCHANT, "smith_enchant_press", 5.0);
    private static final Clip SMITH_ENCHANT_TAP = new Clip(VillagerAnimationState.SMITH_ENCHANT_TAP, "smith_enchant_tap", 5.0);
    private static final Clip SMITH_SMITHING = new Clip(VillagerAnimationState.SMITH_SMITHING, "smith_smithing", 5.0);
    private static final Clip SMITH_RECYCLE = new Clip(VillagerAnimationState.SMITH_RECYCLE, "smith_recycle", 4.0);
    private static final Clip SMITH_FURNACE = new Clip(VillagerAnimationState.SMITH_FURNACE, "smith_furnace", 5.0);
    private static final Clip WASH = new Clip(VillagerAnimationState.WASH, "farmer_wash", 11.2);
    private static final Clip ATTACK = new Clip(VillagerAnimationState.ATTACK, "mixamo_slash", 1.6667);
    private static final Clip SALUTE = new Clip(VillagerAnimationState.SALUTE, "salute", 1.0);
    private static final Clip DANCE = new Clip(VillagerAnimationState.DANCE, "rok_dance", 1.8667);
    private static final Clip BASS_PLAY = new Clip(VillagerAnimationState.BASS_PLAY, "bard_bass_play", 3.2);
    private static final Clip BASS_JAM = new Clip(VillagerAnimationState.BASS_JAM, "bard_bass_jam", 3.2);
    private static final Clip VIOLIN_PLAY = new Clip(VillagerAnimationState.VIOLIN_PLAY, "bard_violin_play", 3.2);
    private static final Clip VIOLIN = new Clip(VillagerAnimationState.PLAY_VIOLIN, "bard_violin_walk", 3.2);
    private static final Clip VIOLIN_DANCE = new Clip(VillagerAnimationState.VIOLIN_DANCE, "bard_violin_dance", 2.4);
    private static final Clip FLUTE_PLAY = new Clip(VillagerAnimationState.FLUTE_PLAY, "bard_flute_play", 2.0);
    private static final Clip FLUTE = new Clip(VillagerAnimationState.PLAY_FLUTE, "bard_flute_walk", 3.2);
    private static final Clip FLUTE_DANCE = new Clip(VillagerAnimationState.FLUTE_DANCE, "bard_flute_dance", 3.2);
    private static final Clip GUITAR_PLAY = new Clip(VillagerAnimationState.GUITAR_PLAY, "bard_guitar_play", 4.8);
    private static final Clip GUITAR = new Clip(VillagerAnimationState.PLAY_GUITAR, "bard_guitar_walk", 3.2);
    private static final Clip GUITAR_JAM = new Clip(VillagerAnimationState.GUITAR_JAM, "bard_guitar_jam", 6.4);
    private static final Clip BONGOS_PLAY = new Clip(VillagerAnimationState.BONGOS_PLAY, "bard_bongos_play", 2.5763);
    private static final Clip BONGOS_JAM = new Clip(VillagerAnimationState.BONGOS_JAM, "bard_bongos_jam", 5.1526);
    private static final Clip THINK = new Clip(VillagerAnimationState.THINK, "mixamo_think", 4.2333);
    private static final Clip TALK = new Clip(VillagerAnimationState.ARM_ON_HIP, "mixamo_talk", 3.9333);
    private static final Clip READ = new Clip(VillagerAnimationState.READ, "read", 4.0);
    private static final Clip AIM = new Clip(VillagerAnimationState.AIM, "ki_bow_shot", 1.64);
    private static final Clip CAST = new Clip(VillagerAnimationState.CAST, "mixamo_magic_cast", 2.3);
    private static final Clip MAGE_BOLT = new Clip(VillagerAnimationState.MAGE_BOLT, "mixamo_mage_bolt", 2.2);
    private static final Clip MAGE_NOVA = new Clip(VillagerAnimationState.MAGE_NOVA, "mixamo_mage_nova", 2.9667);
    private static final Clip MAGE_CHAIN = new Clip(VillagerAnimationState.MAGE_CHAIN, "mixamo_mage_chain", 2.7);
    private static final Clip MAGE_WARD = new Clip(VillagerAnimationState.MAGE_WARD, "mixamo_mage_ward", 2.2667);
    private static final Clip MAGE_STARFALL =
            new Clip(VillagerAnimationState.MAGE_STARFALL, "mixamo_mage_starfall", 2.1667);
    private static final Clip ENCHANT_WEAVE = new Clip(VillagerAnimationState.ENCHANT_WEAVE, "mixamo_enchant_weave", 3.4333);
    private static final Clip ENCHANT_PONDER = new Clip(VillagerAnimationState.ENCHANT_PONDER, "mixamo_enchant_ponder", 3.6667);
    private static final Clip ENCHANT_FINISH = new Clip(VillagerAnimationState.ENCHANT_FINISH, "mixamo_enchant_finish", 1.8333);
    private static final Clip ENCHANT_SEIZE = new Clip(VillagerAnimationState.ENCHANT_SEIZE, "mixamo_enchant_seize", 1.6667);
    private static final Clip ENCHANT_TRANCE = new Clip(VillagerAnimationState.ENCHANT_TRANCE, "mixamo_enchant_trance", 3.2);
    private static final Clip ENCHANT_RELEASE = new Clip(VillagerAnimationState.ENCHANT_RELEASE, "mixamo_enchant_release", 1.4667);
    /**
     * The cook's 25 clips in id order (tools/cook), each named as its state in lower case and as
     * long as {@link VillagerAnimationState#cycleTicks}. Looped here like every preview clip: the
     * once clips start and end on the same ready pose.
     */
    private static final List<Clip> COOK_CLIPS = java.util.Arrays.stream(VillagerAnimationState.values())
            .filter(VillagerAnimationState::isCookWork)
            .map(s -> new Clip(s, s.name().toLowerCase(java.util.Locale.ROOT), VillagerAnimationState.cycleTicks(s) / 20.0))
            .toList();

    private ProfessionAnimationPreview() {}

    /** Core job animations; professions without a dedicated work pose show their existing idle pose. */
    public static List<Clip> clips(Profession profession) {
        return switch (profession) {
            case FARMER -> List.of(HARVEST_YANK, HARVEST_PLUCK, PLANT_SOW, PLANT_PRESS, TILL, TILL_SKILLED,
                    PICK_LOW, PICK_THORN, PICK_LOOK, PICK, PICK_HIGH, HARVEST_CHOP, WASH);
            case LUMBERJACK -> List.of(LUMBER_CHOP_BAT, LUMBER_CHOP_NOTCH, LUMBER_CHOP_SHORT);
            case RANCHER -> List.of(CHOP);
            case MINER -> List.of(MINE, CRAFT_TABLE);
            // Steady and heavy (SMITH_ANVIL, SMITH_ANVIL_HEAVY) are no longer played at work since 10-24.
            case BLACKSMITH -> List.of(SMITH_ANVIL_DRAW, SMITH_ENCHANT, SMITH_ENCHANT_TAP,
                    SMITH_SMITHING, SMITH_RECYCLE, SMITH_FURNACE);
            case COOK -> COOK_CLIPS;
            case ENCHANTER -> List.of(ENCHANT_WEAVE, ENCHANT_PONDER, ENCHANT_FINISH,
                    ENCHANT_SEIZE, ENCHANT_TRANCE, ENCHANT_RELEASE);
            case BUTCHER -> List.of(ATTACK);
            case ARCHER -> List.of(AIM);
            case GUARD, CAPTAIN -> List.of(ATTACK, SALUTE);
            case CLERIC -> List.of(CAST);
            case MAGE -> List.of(MAGE_BOLT, MAGE_NOVA, MAGE_CHAIN, MAGE_WARD, MAGE_STARFALL);
            case DRUID -> List.of(DANCE);
            case BARD -> List.of(VIOLIN_PLAY, VIOLIN, VIOLIN_DANCE, FLUTE_PLAY, FLUTE, FLUTE_DANCE, BASS_PLAY, BASS_JAM,
                    GUITAR_PLAY, GUITAR, GUITAR_JAM, BONGOS_PLAY, BONGOS_JAM, DANCE);
            case TEACHER -> List.of(THINK);
            case TRADESMAN -> List.of(TALK);
            case CHILD -> List.of(READ);
            case NONE, BUILDER -> List.of(IDLE);
        };
    }
}
