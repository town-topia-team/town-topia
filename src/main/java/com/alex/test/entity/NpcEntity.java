package com.alex.test.entity;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.AnimationBlend;
import com.alex.test.animation.FastController;
import com.alex.test.animation.HurryGait;
import com.alex.test.animation.LumberChopSwing;
import com.alex.test.animation.OverlayBlend;
import com.alex.test.animation.PhasedLoopController;
import com.alex.test.animation.ProfessionAnimationPreview;
import com.alex.test.animation.RunPace;
import com.alex.test.block.WoodenChairBlock;
import com.alex.test.village.VillageChairs;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.ui.VillagerMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.names.VillagerNameBook;
import com.alex.test.village.names.VillagerNames;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.VillagerVillageLink;
import com.alex.test.village.LegShape;
import com.alex.test.village.WalkFeel;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.core.ProfessionSkills;
import com.alex.test.village.villagers.guard.ShieldStance;
import com.alex.test.village.villagers.farmer.FarmerDirt;
import com.alex.test.village.villagers.farmer.FarmerSkinSequence;
import com.alex.test.village.villagers.bard.BardSkinSequence;
import com.alex.test.village.villagers.miner.MinerSkinSequence;
import com.alex.test.village.villagers.lumberjack.LumberjackSkinSequence;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import com.alex.test.village.villagers.settings.SettingsSchemaRegistry;
import com.alex.test.entity.mayor.MayorEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import java.util.function.IntUnaryOperator;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.entity.ai.control.JumpControl;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Puppet NPC — the visual shell driven by the server-side {@link com.alex.test.village.VillagerModel}.
 *
 * <h3>Architectural rules</h3>
 * <ul>
 *   <li>Zero autonomous behaviour: {@link #registerGoals()} is intentionally empty.</li>
 *   <li>Owns only position/interpolation, animation state, hitbox/collision, rendering,
 *       and an item inventory.</li>
 *   <li>All movement decisions are made by {@link com.alex.test.village.VillagerModel}
 *       and pushed via {@link com.alex.test.village.MoveToCommand}.</li>
 *   <li>A stable {@link #npcId} links this puppet to its {@code VillagerModel} so it
 *       can be re-bound after chunk reload.</li>
 * </ul>
 *
 * <h3>Animations</h3>
 * GeckoLib drives all animations.  The controllers of note:
 * <ul>
 *   <li>{@code chop_controller} — the working clip for {@link VillagerAnimationState#CHOP},
 *       {@code LUMBER_CHOP}, {@code ATTACK} and {@code TILL} (set by e.g.
 *       {@link com.alex.test.village.villagers.lumberjack.behavior.ChopTreeActivity}).</li>
 *   <li>{@code idle_controller} — plays {@code nose wiggle} once, triggered server-side
 *       at randomly staggered 45–90 second intervals.</li>
 *   <li>{@code mocap_controller} — registered last, so it overrides every other
 *       controller on the bones it touches (which is all of them) while
 *       {@code /villager mocap} has a clip selected. Off for normal villagers.</li>
 * </ul>
 * The mocap clips also carry root motion, which is not in the clip: see
 * {@link MocapRootMotion} and {@link #tickMocapRootMotion}.
 */
public class NpcEntity extends PathfinderMob implements MenuProvider, GeoEntity {

    public static final String CHAIR_PREVIEW_TAG = "dynamicvillages.chair_preview";
    /**
     * Marks a villager spawned by {@code /villager mocap}. Its clip is synced
     * data that is not saved, so after a reload this tag is all that still
     * says it is a preview.
     */
    public static final String MOCAP_PREVIEW_TAG = "dynamicvillages.mocap_preview";
    /** Marks the throwaway villagers spawned by {@code thoughts lineup}. */
    public static final String THOUGHT_PREVIEW_TAG = "dynamicvillages_thought_preview";

    // -------------------------------------------------------------------------
    // GeckoLib
    // -------------------------------------------------------------------------

    // Every state a villager can be in plays a Mixamo clip, retargeted through
    // tools/mocap, except where the set has no take for it: eat, situp, crafting
    // and the nose wiggle keep the hand-keyed clip, each marked below. Forcing a
    // capture into a slot it does not fit reads worse than the clip it replaced,
    // which is what mixamo_gather did as a crafting loop.
    private static final RawAnimation ANIM_IDLE =
            RawAnimation.begin().thenLoop("mixamo_idle");
    private static final RawAnimation ANIM_WALK =
            RawAnimation.begin().thenLoop("mixamo_walk");
    /** Mixamo "Sad Walk": played instead of the walk while the villager is sad. */
    private static final RawAnimation ANIM_SAD_WALK =
            RawAnimation.begin().thenLoop("mixamo_sad_walk");
    /** Mixamo "Sad Idle": played instead of the idle while the villager is sad, standing still. */
    private static final RawAnimation ANIM_SAD_IDLE =
            RawAnimation.begin().thenLoop("mixamo_sad_idle");
    /**
     * The author's own Rokoko capture of chopping wood (0911), mirrored, one-armed and
     * smoothed. Three things were done to it and each is measured in
     * tools/mocap/batch_takes.py:
     *
     * <p>Mirrored, because the performer led with the left hand -- 1.35 torso-heights
     * of travel against the right's 1.03 -- and the villager's axe hangs off
     * {@code bone2}, the right forearm. Unmirrored, the stroke landed on the empty
     * hand.
     *
     * <p>One-armed, because the capture is two-handed and this rig has no wrist or
     * fingers to absorb the off hand; it read as flailing. Held at rest it hangs
     * straight and the chop is one-handed, which is how a villager carries a tool.
     *
     * <p>A 3s cycle holding 92% of the take's motion, cut where its ends nearly meet
     * so the seam needs almost no cross-fade -- the jerk left in it is all in the last
     * seven frames, and a tighter cut took the legs there from 1.93 to 0.74.
     */
    private static final RawAnimation ANIM_CHOP =
            RawAnimation.begin().thenLoop("rok_chop");
    /**
     * The tilling clips, baked from tools/till/farmer-till-v3.html. Each stroke is 35 ticks, a
     * real pull of the blade along the ground; a loop is two strokes. Never chained with
     * thenPlay: GeckoLib puts a full transition between queued clips. The back-rub loops
     * (farmer_till_beginner_rub2/4/6) are still in the animation file but no longer played:
     * mid-tile the rest read as the till animation failing (10-17).
     */
    private static final RawAnimation ANIM_TILL =
            RawAnimation.begin().thenLoop("farmer_till_beginner");
    private static final RawAnimation ANIM_TILL_SKILLED =
            RawAnimation.begin().thenLoop("farmer_till_skilled");
    /**
     * Planting, baked in tools/field (10-17): a low squat, the fist goes into the soil and
     * the seed stays there (the pick_item_r bone hides it). Sow: one hand pokes and pinches,
     * the other braced on the soil. Press: pushes it in, both hands sweep and pat. Each is
     * one 43-tick loop that repeats cleanly; one of the two per job ({@link #plantClip}).
     */
    private static final RawAnimation[] ANIM_PLANT = {
            RawAnimation.begin().thenLoop("farmer_plant_sow"),
            RawAnimation.begin().thenLoop("farmer_plant_press")};
    /**
     * Harvesting a farmland crop (tools/field): yank, squatting, both hands pull a handful
     * and pile it on his left (crop in the left fist, pick_item); pluck, bent over, the right
     * hand plucks twice a loop into the hip pocket (crop in the right fist, pick_item_r).
     */
    private static final RawAnimation[] ANIM_HARVEST = {
            RawAnimation.begin().thenLoop("farmer_harvest_yank"),
            RawAnimation.begin().thenLoop("farmer_harvest_pluck")};
    /**
     * Picking with the shears (tools/pick): the left hand steadies, the shears snip twice,
     * the left hand catches the fruit (pick_item) and pockets it. [plain, flourish] for one
     * block up, at the feet, a beginner at a bush, two up; the flourish (a look at the berry,
     * or the thorn) is four picks long and plays on 1 job in 3 ({@link #pickClip}).
     */
    private static final RawAnimation[] ANIM_PICK = {
            RawAnimation.begin().thenLoop("farmer_pick_mid"),
            RawAnimation.begin().thenLoop("farmer_pick_mid_look")};
    private static final RawAnimation[] ANIM_PICK_LOW = {
            RawAnimation.begin().thenLoop("farmer_pick_low"),
            RawAnimation.begin().thenLoop("farmer_pick_low_look")};
    private static final RawAnimation[] ANIM_PICK_BUSH = {
            RawAnimation.begin().thenLoop("farmer_pick_low"),
            RawAnimation.begin().thenLoop("farmer_pick_low_thorn")};
    private static final RawAnimation[] ANIM_PICK_HIGH = {
            RawAnimation.begin().thenLoop("farmer_pick_high"),
            RawAnimation.begin().thenLoop("farmer_pick_high_look")};
    /** A melon or pumpkin cut off its stem with the axe: the butcher's chop down onto the block. */
    private static final RawAnimation ANIM_HARVEST_CHOP =
            RawAnimation.begin().thenLoop("mixamo_attack_down");
    /**
     * One capture behind two states: an overhead hammer swing reads as mining a block
     * and as beating metal on an anvil alike, so the miner and the blacksmith share it.
     */
    private static final RawAnimation ANIM_MINE =
            RawAnimation.begin().thenLoop("mixamo_attack_down");
    private static final RawAnimation ANIM_FORGE =
            RawAnimation.begin().thenLoop("mixamo_attack_down");
    /** The druid's dance (and the bard's, for now). */
    private static final RawAnimation ANIM_DANCE =
            RawAnimation.begin().thenLoop("rok_dance");
    /**
     * The tavern kick line (Mixamo "Jazz Dancing"), one clip per side of the
     * pair: the inner arm lies across the partner's upper back. Both dancers
     * start it on the same tick (PairDance), so the kicks land together.
     */
    private static final RawAnimation ANIM_JAZZ_PAIR_R =
            RawAnimation.begin().thenLoop("mixamo_jazz_pair_r");
    private static final RawAnimation ANIM_JAZZ_PAIR_L =
            RawAnimation.begin().thenLoop("mixamo_jazz_pair_l");
    /**
     * The bard's upright bass (tools/bass/BassTool.java): the hands are solved onto
     * the neck and strings, and the bass itself rides the {@code bard_bass} locator
     * the clip keys ({@code BardBassGeoLayer}). Base rig only.
     */
    private static final RawAnimation ANIM_BASS_PLAY =
            RawAnimation.begin().thenLoop("bard_bass_play");
    private static final RawAnimation ANIM_BASS_JAM =
            RawAnimation.begin().thenLoop("bard_bass_jam");
    /**
     * The seated bongo bard (tools/bongos/BongosTool.java), on the 6/8 beat of the band
     * song's drum part: clip time 0 is song time 0. The stool and drums ride the
     * {@code bard_stool} / {@code bard_bongos} locators ({@code BardBongosGeoLayer}).
     */
    private static final RawAnimation ANIM_BONGOS_PLAY =
            RawAnimation.begin().thenLoop("bard_bongos_play");
    private static final RawAnimation ANIM_BONGOS_JAM =
            RawAnimation.begin().thenLoop("bard_bongos_jam");
    /**
     * Mixamo "Standing 1H Magic Attack 01": the cleric's healing throw. Played
     * once and held, restarted for every throw; its last frame is its first, the
     * orb hand raised in front of the chest.
     */
    private static final RawAnimation ANIM_CAST =
            RawAnimation.begin().thenPlayAndHold("mixamo_magic_cast");
    /**
     * The hurry loops ({@link HurryGait}), played by the walk controller in place of
     * the walk at a speed that follows the villager's pace ({@link RunPace}).
     */
    private static final RawAnimation ANIM_RUN =
            RawAnimation.begin().thenLoop("mixamo_run");
    private static final RawAnimation ANIM_SWORD_RUN =
            RawAnimation.begin().thenLoop("mixamo_run_sword");
    private static final RawAnimation ANIM_INJURED_RUN =
            RawAnimation.begin().thenLoop("mixamo_injured_run");
    private static final RawAnimation ANIM_JOG =
            RawAnimation.begin().thenLoop("mixamo_jog");
    /**
     * The bard's violin (tools/violin/ViolinTool.java): the hands are solved onto the
     * neck and the bow, and violin and bow ride the {@code bard_violin} and
     * {@code bard_bow} locators under the hips ({@code BardViolinGeoLayer}). The walk
     * clip keys only head, arms and bow, so the walk's legs and torso carry on under it.
     */
    private static final RawAnimation ANIM_PLAY_VIOLIN =
            RawAnimation.begin().thenLoop("bard_violin_walk");
    private static final RawAnimation ANIM_VIOLIN_DANCE =
            RawAnimation.begin().thenLoop("bard_violin_dance");
    private static final RawAnimation ANIM_VIOLIN_PLAY =
            RawAnimation.begin().thenLoop("bard_violin_play");
    /**
     * The bard's flute (tools/flute/FluteTool.java): the flute rides the {@code bard_flute}
     * locator under the head ({@code BardFluteGeoLayer}) and both hands are solved onto it.
     * The walk clip keys only head and arms, so the walk's legs and torso carry on under it.
     */
    private static final RawAnimation ANIM_PLAY_FLUTE =
            RawAnimation.begin().thenLoop("bard_flute_walk");
    private static final RawAnimation ANIM_FLUTE_DANCE =
            RawAnimation.begin().thenLoop("bard_flute_dance");
    private static final RawAnimation ANIM_FLUTE_PLAY =
            RawAnimation.begin().thenLoop("bard_flute_play");
    /**
     * The bard's guitar (tools/guitar/GuitarTool.java): the guitar rides the {@code bard_guitar}
     * locator under the hips ({@code BardGuitarGeoLayer}); the chord hand is solved onto the
     * neck and the strumming hand over the strings. The walk clip keys only head and arms.
     */
    private static final RawAnimation ANIM_PLAY_GUITAR =
            RawAnimation.begin().thenLoop("bard_guitar_walk");
    private static final RawAnimation ANIM_GUITAR_JAM =
            RawAnimation.begin().thenLoop("bard_guitar_jam");
    private static final RawAnimation ANIM_GUITAR_PLAY =
            RawAnimation.begin().thenLoop("bard_guitar_play");
    private static final RawAnimation ANIM_ATTACK =
            RawAnimation.begin().thenLoop("mixamo_slash");
    /** The mocap sword-block stance: the guard's ready pose between swings. */
    private static final RawAnimation ANIM_BLOCK =
            RawAnimation.begin().thenLoop("mixamo_block");
    /**
     * Shield raised (tools/shield/ShieldClips.java bakes these): a 0.2 s raise
     * played once, then a held 2 s breathing loop, so the shield stays up for as
     * long as it is raised. Two stances: the close guard (melee) and the high
     * cover (arrows, a creeper about to blow). The _walk twins key no legs and no
     * hip height, so the walk shows below. The shield takes hits once the arm is
     * up (GuardBalance.SHIELD_UP_TICKS).
     *
     * <p>The hold-only animations are for a stance change or a walk/stand change
     * after the raise has played: starting the raise again would dip the shield.
     * (The old mocap mixamo_block_shield looped down and up every 1.2 s.)
     */
    private static final RawAnimation ANIM_SHIELD_GUARD =
            RawAnimation.begin().thenPlay("shield_guard_raise").thenLoop("shield_guard_hold");
    private static final RawAnimation ANIM_SHIELD_GUARD_WALK =
            RawAnimation.begin().thenPlay("shield_guard_raise_walk").thenLoop("shield_guard_hold_walk");
    private static final RawAnimation ANIM_SHIELD_GUARD_HOLD =
            RawAnimation.begin().thenLoop("shield_guard_hold");
    private static final RawAnimation ANIM_SHIELD_GUARD_HOLD_WALK =
            RawAnimation.begin().thenLoop("shield_guard_hold_walk");
    private static final RawAnimation ANIM_SHIELD_COVER =
            RawAnimation.begin().thenPlay("shield_cover_raise").thenLoop("shield_cover_hold");
    private static final RawAnimation ANIM_SHIELD_COVER_WALK =
            RawAnimation.begin().thenPlay("shield_cover_raise_walk").thenLoop("shield_cover_hold_walk");
    private static final RawAnimation ANIM_SHIELD_COVER_HOLD =
            RawAnimation.begin().thenLoop("shield_cover_hold");
    private static final RawAnimation ANIM_SHIELD_COVER_HOLD_WALK =
            RawAnimation.begin().thenLoop("shield_cover_hold_walk");
    /** Hand-keyed: a face animation; no body capture has one. */
    private static final RawAnimation ANIM_NOSE_WIGGLE =
            RawAnimation.begin().thenPlay("nose wiggle");
    /** Hand-keyed: two small dips of the head, a "yes" in a conversation. */
    private static final RawAnimation ANIM_NOD =
            RawAnimation.begin().thenPlay("nod");
    private static final RawAnimation ANIM_NOSE_VARIANTS =
            RawAnimation.begin().thenLoop("nose_variants");
    private static final RawAnimation ANIM_CHEER =
            RawAnimation.begin().thenPlayAndHold("mixamo_cheer");
    /** The same wave with only the head and arms, so a seated villager waves without standing. */
    private static final RawAnimation ANIM_CHAIR_CHEER =
            RawAnimation.begin().thenPlayAndHold("mixamo_cheer_chair");
    /** Hand-keyed; held on its last frame so the hand stays up until the state ends. */
    private static final RawAnimation ANIM_SALUTE =
            RawAnimation.begin().thenPlayAndHold("salute");
    /** Barracks drill flourishes: a defender's power-up, a captain's kick. Played once. */
    private static final RawAnimation ANIM_POWER_UP =
            RawAnimation.begin().thenPlayAndHold("mixamo_power_up");
    private static final RawAnimation ANIM_KICK =
            RawAnimation.begin().thenPlayAndHold("mixamo_kick");
    /** A guard at its post looking around; the clip ends on the pose it starts from. */
    private static final RawAnimation ANIM_LOOK_AROUND =
            RawAnimation.begin().thenPlayAndHold("mixamo_look_around");
    /**
     * Hand-keyed: sitting down on the floor (a Town Hall sleeper, a visitor at
     * camp). Not mixamo_sit, which is a chair pose: its hips stop at seat
     * height, so on bare ground the villager hovered. This one ends on the
     * first frame of sleep_sitting_down and situp, so the chain runs on.
     */
    private static final RawAnimation ANIM_SITDOWN =
            RawAnimation.begin().thenPlayAndHold("sitdown");
    /**
     * The same take, looped, for sitting on a chair. mixamo_sit is authored as
     * a loop; played and held it froze on its last frame, which a villager
     * sitting through a whole meal or conversation made obvious.
     */
    private static final RawAnimation ANIM_CHAIR_SIT =
            RawAnimation.begin().thenLoop("mixamo_sit");
    private static final RawAnimation ANIM_SLEEP_SITTING_DOWN =
            RawAnimation.begin().thenLoop("sleep_sitting_down");
    /** Hand-keyed: no stand-up-from-sitting take in the set. */
    private static final RawAnimation ANIM_SITUP =
            RawAnimation.begin().thenPlayAndHold("situp");
    private static final RawAnimation ANIM_SLEEP_LYING_DOWN =
            RawAnimation.begin().thenLoop("sleep_lying_down");
    /**
     * Baked from tools/wallsleep: the same chain with the back to a wall, knees up.
     * The sit-down ends on wall_sleep's first frame and the stand-up starts on it.
     * The clips squash the eye sticker (eyes_bg) to nothing while asleep.
     */
    private static final RawAnimation ANIM_WALL_SITDOWN =
            RawAnimation.begin().thenPlayAndHold("wall_sitdown");
    private static final RawAnimation ANIM_WALL_SLEEP =
            RawAnimation.begin().thenLoop("wall_sleep");
    private static final RawAnimation ANIM_WALL_SITUP =
            RawAnimation.begin().thenPlayAndHold("wall_situp");
    /**
     * Baked from tools/eat/villager-eat-v1.html: reach into the hip pocket, look
     * at the food, four bites to the mouth with chewing, the other hand cupped
     * underneath, then a swallow and two pats on the belly. Keys hips, head,
     * nose and both arms only, so a seated villager's legs stay on the chair.
     */
    private static final RawAnimation ANIM_EAT =
            RawAnimation.begin().thenPlayAndHold("eat");
    /**
     * The archer's shot (Kevin Iglesias BowShot01 Load + Release, mirrored so the
     * bow is in the right hand, side-on to the target): restarted for every shot.
     */
    private static final RawAnimation ANIM_AIM =
            RawAnimation.begin().thenPlayAndHold("ki_bow_shot");
    /**
     * The mage's Arcane Bolt (Mixamo's Standing 1H Magic Attack 02): the casting
     * hand winds back and flicks forward, empty -- the bolt is light, not a
     * staff's; restarted for every cast.
     */
    private static final RawAnimation ANIM_MAGE_BOLT =
            RawAnimation.begin().thenPlayAndHold("mixamo_mage_bolt");
    /** The mage's other casts (see the MAGE_* states); each restarted for every cast. */
    private static final RawAnimation ANIM_MAGE_NOVA =
            RawAnimation.begin().thenPlayAndHold("mixamo_mage_nova");
    private static final RawAnimation ANIM_MAGE_CHAIN =
            RawAnimation.begin().thenPlayAndHold("mixamo_mage_chain");
    private static final RawAnimation ANIM_MAGE_WARD =
            RawAnimation.begin().thenPlayAndHold("mixamo_mage_ward");
    private static final RawAnimation ANIM_MAGE_STARFALL =
            RawAnimation.begin().thenPlayAndHold("mixamo_mage_starfall");
    private static final RawAnimation ANIM_ENCHANT_WEAVE =
            RawAnimation.begin().thenLoop("mixamo_enchant_weave");
    private static final RawAnimation ANIM_ENCHANT_PONDER =
            RawAnimation.begin().thenLoop("mixamo_enchant_ponder");
    private static final RawAnimation ANIM_ENCHANT_FINISH =
            RawAnimation.begin().thenPlayAndHold("mixamo_enchant_finish");
    private static final RawAnimation ANIM_ENCHANT_SEIZE =
            RawAnimation.begin().thenPlayAndHold("mixamo_enchant_seize");
    private static final RawAnimation ANIM_ENCHANT_TRANCE =
            RawAnimation.begin().thenLoop("mixamo_enchant_trance");
    private static final RawAnimation ANIM_ENCHANT_RELEASE =
            RawAnimation.begin().thenPlayAndHold("mixamo_enchant_release");
    /** Hand-keyed on the base rig: book held at chest height, head tipped down, a page turn each loop. */
    private static final RawAnimation ANIM_READ =
            RawAnimation.begin().thenLoop("read");
    private static final RawAnimation ANIM_THINK =
            RawAnimation.begin().thenPlayAndHold("mixamo_think");
    /** Chair variants leave hips and legs to the sitting controller. */
    private static final RawAnimation ANIM_CHAIR_THINK =
            RawAnimation.begin().thenPlayAndHold("mixamo_think_chair");
    /**
     * Mixamo's "Asking A Question With Two Hands". This played `mixamo_pray` until
     * the sweep in analyse_all.py caught it: Praying.fbx is 36 frames holding one
     * pose, and it retargeted to 0.4 degrees of joint motion -- a still, wired to a
     * conversation gesture.
     */
    private static final RawAnimation ANIM_ARM_ON_HIP =
            RawAnimation.begin().thenPlayAndHold("mixamo_talk");
    private static final RawAnimation ANIM_CHAIR_ARM_ON_HIP =
            RawAnimation.begin().thenPlayAndHold("mixamo_talk_chair");
    private static final RawAnimation ANIM_SHRUG =
            RawAnimation.begin().thenPlayAndHold("mixamo_shrug");
    private static final RawAnimation ANIM_CHAIR_SHRUG =
            RawAnimation.begin().thenPlayAndHold("mixamo_shrug_chair");
    /**
     * The hand-keyed clip, not a capture. The set has no crafting take, and the
     * nearest thing in it -- {@code mixamo_gather} -- is a pick-it-off-the-floor
     * motion: looped at a workbench the villager squats to the ground over and
     * over. The authored clip works at chest height with its feet planted, which
     * is what crafting looks like.
     */
    /**
     * The same clip as FORGE and MINE. The authored crafting clip is gone and there is
     * no crafting take; an overhand strike is the motion it had anyway.
     */
    private static final RawAnimation ANIM_CRAFTING =
            RawAnimation.begin().thenLoop("mixamo_attack_down");
    /**
     * Work at a crafting table, keyed by hand against the table's top
     * (tools/crafting/CraftTool.java): the hands land on the front row of the grid,
     * the only part a villager's arms reach, and the body is drawn 3 px nearer the
     * table than the hitbox stands.
     */
    private static final RawAnimation ANIM_CRAFT_TABLE =
            RawAnimation.begin().thenLoop("craft_table");
    /**
     * The blacksmith at work, keyed by hand against the anvil, smithing table and furnace
     * (tools/forge/ForgeTool.java). The anvil clips hold the hammer in the right fist;
     * the book clips the tool in the right and the book in the left.
     */
    private static final RawAnimation ANIM_SMITH_ANVIL = RawAnimation.begin().thenLoop("smith_anvil_steady");
    private static final RawAnimation ANIM_SMITH_ANVIL_HEAVY = RawAnimation.begin().thenLoop("smith_anvil_heavy");
    private static final RawAnimation ANIM_SMITH_ENCHANT = RawAnimation.begin().thenLoop("smith_enchant_press");
    private static final RawAnimation ANIM_SMITH_ENCHANT_TAP = RawAnimation.begin().thenLoop("smith_enchant_tap");
    private static final RawAnimation ANIM_SMITH_SMITHING = RawAnimation.begin().thenLoop("smith_smithing");
    private static final RawAnimation ANIM_SMITH_RECYCLE = RawAnimation.begin().thenLoop("smith_recycle");
    private static final RawAnimation ANIM_SMITH_FURNACE = RawAnimation.begin().thenLoop("smith_furnace");
    /** Drawing out a bar at the anvil, baked from the sketch in tools/anvil (10-24): the hammer held like a player's. */
    private static final RawAnimation ANIM_SMITH_ANVIL_DRAW = RawAnimation.begin().thenLoop("smith_anvil_draw");
    /**
     * The farmer's wash at a cauldron, baked from the sketch in tools/wash: hands
     * reach the water only in a full cauldron (fists go no lower than 13.7 px).
     * Played once and held; WashActivity ends it after the clip.
     */
    private static final RawAnimation ANIM_WASH =
            RawAnimation.begin().thenPlayAndHold("farmer_wash");
    /**
     * The cook's 25 work clips (tools/cook, docs/plans/2026-10-24-cook-animations.md), one per
     * state, named as the state in lower case. The table dishes and the two waits loop; the rest
     * play once and hold their last frame (each starts and ends on the same ready pose).
     */
    private static final java.util.Map<VillagerAnimationState, RawAnimation> ANIM_COOK = cookClips();

    private static java.util.Map<VillagerAnimationState, RawAnimation> cookClips() {
        var clips = new java.util.EnumMap<VillagerAnimationState, RawAnimation>(VillagerAnimationState.class);
        for (VillagerAnimationState state : VillagerAnimationState.values()) {
            if (!state.isCookWork()) continue;
            String name = state.name().toLowerCase(java.util.Locale.ROOT);
            clips.put(state, state.isCookLoop() ? RawAnimation.begin().thenLoop(name)
                    : RawAnimation.begin().thenPlayAndHold(name));
        }
        return clips;
    }
    /**
     * The lumberjack's three sideways two-handed chops, baked in
     * tools/chop/lumberjack-chop-sideways.html, in {@link LumberChopSwing}
     * order; the axe placed by the axe_hold bone (HeldItemGeoLayer).
     */
    private static final RawAnimation[] ANIM_LUMBER_CHOP =
            Arrays.stream(LumberChopSwing.values())
                    .map(s -> RawAnimation.begin().thenLoop(s.clip)).toArray(RawAnimation[]::new);

    private final AnimatableInstanceCache animatableCache = GeckoLibUtil.createInstanceCache(this);

    /**
     * Client only: the fast posing step's note of this villager's saved bone states in bone
     * order (client {@code FastAnimationProcessor}). Never saved or sent.
     */
    public Object fastPoseCache;

    /** Server-side countdown until the next nose-wiggle trigger (ticks). */
    private int noseWiggleCooldown = -1; // stagger the first wiggle too

    private int nextNoseWiggleDelay() {
        return 20 * 45 + getRandom().nextInt(20 * 45 + 1);
    }

    /**
     * The clip for a working state, or null if this state is not work.
     *
     * <p>Mocap clips are solved on a flat copy of {@code base_villager} and written
     * onto its bone tree by {@code tools/mocap/retarget.py} ({@code flat_to_tree.py}),
     * so they play like any hand-keyed clip.
     */
    private RawAnimation workAnimation(VillagerAnimationState animation) {
        return switch (animation) {
            case CHOP -> ANIM_CHOP;
            case LUMBER_CHOP -> ANIM_LUMBER_CHOP[lumberChopSwing().ordinal()];
            case TILL -> ANIM_TILL;
            case TILL_SKILLED -> ANIM_TILL_SKILLED;
            case PLANT -> ANIM_PLANT[farmVariant(0x51A7, 2)];
            case HARVEST -> ANIM_HARVEST[farmVariant(0x4A2B, 2)];
            case PICK -> pickClip(ANIM_PICK);
            case PICK_LOW -> pickClip(ANIM_PICK_LOW);
            case PICK_BUSH -> pickClip(ANIM_PICK_BUSH);
            case PICK_HIGH -> pickClip(ANIM_PICK_HIGH);
            case HARVEST_CHOP -> ANIM_HARVEST_CHOP;
            case MINE -> ANIM_MINE;
            case FORGE -> ANIM_FORGE;
            case DANCE -> ANIM_DANCE;
            case JAZZ_PAIR_R -> ANIM_JAZZ_PAIR_R;
            case JAZZ_PAIR_L -> ANIM_JAZZ_PAIR_L;
            case BASS_PLAY -> ANIM_BASS_PLAY;
            case BASS_JAM -> ANIM_BASS_JAM;
            case BONGOS_PLAY -> ANIM_BONGOS_PLAY;
            case BONGOS_JAM -> ANIM_BONGOS_JAM;
            case PLAY_VIOLIN -> ANIM_PLAY_VIOLIN;
            case VIOLIN_DANCE -> ANIM_VIOLIN_DANCE;
            case VIOLIN_PLAY -> ANIM_VIOLIN_PLAY;
            case PLAY_FLUTE -> ANIM_PLAY_FLUTE;
            case FLUTE_DANCE -> ANIM_FLUTE_DANCE;
            case FLUTE_PLAY -> ANIM_FLUTE_PLAY;
            case PLAY_GUITAR -> ANIM_PLAY_GUITAR;
            case GUITAR_JAM -> ANIM_GUITAR_JAM;
            case GUITAR_PLAY -> ANIM_GUITAR_PLAY;
            case ATTACK -> ANIM_ATTACK;
            case AIM -> ANIM_AIM;
            case MAGE_BOLT -> ANIM_MAGE_BOLT;
            case MAGE_NOVA -> ANIM_MAGE_NOVA;
            case MAGE_CHAIN -> ANIM_MAGE_CHAIN;
            case MAGE_WARD -> ANIM_MAGE_WARD;
            case MAGE_STARFALL -> ANIM_MAGE_STARFALL;
            case ENCHANT_WEAVE -> ANIM_ENCHANT_WEAVE;
            case ENCHANT_PONDER -> ANIM_ENCHANT_PONDER;
            case ENCHANT_FINISH -> ANIM_ENCHANT_FINISH;
            case ENCHANT_SEIZE -> ANIM_ENCHANT_SEIZE;
            case ENCHANT_TRANCE -> ANIM_ENCHANT_TRANCE;
            case ENCHANT_RELEASE -> ANIM_ENCHANT_RELEASE;
            // CAST has a controller of its own (cast_controller), for its longer blends.
            default -> null;
        };
    }

    /**
     * How long a controller blends from whatever it was showing into the clip it is
     * switching to, in ticks.
     *
     * <p>GeckoLib blends before the clip's loop begins. The farmer includes this
     * transition in its paced action duration. The old values were 2 to 5 ticks:
     * a tenth of a second to get from
     * a two-handed harvest pose to standing idle reads as a snap, and the farmer
     * does it after every tile.
     *
     * <p>{@code SETTLE} is longer because sitting down and lying down move the whole
     * body, and {@code FLOURISH} is shorter than the rest because a one-shot gesture
     * spends its transition inside its own runtime -- blend too long and the wave
     * has started before the arm is up.
     */
    private static final int BLEND = VillagerAnimationState.WORK_BLEND_TICKS;
    private static final int SETTLE = 12;
    private static final int FLOURISH = 6;

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        // Walk: loops while moving. Suppressed during sleep and eat so it cannot
        // bleed through onto shared bones. Phased: the first loop after loading in
        // starts at a per-villager offset so a freshly loaded village is not in lockstep.
        walkController = new PhasedLoopController<>(this, "walk_controller", BLEND, state -> {
            if (isInWaterOrBubble() || isChairSeated()) {
                return stopWalk(false);
            }
            // An animation over this one is easing out (OverlayBlend): sit it out,
            // or that blend would start from the standing pose written here this
            // frame rather than from the pose on screen.
            if (overlaysOwnBase(state)) {
                walkReloadForOverlay = true;
                return stopWalk(false);
            }
            VillagerAnimationState anim = getAnimation();
            if (anim == VillagerAnimationState.SITDOWN
                    || anim == VillagerAnimationState.CHAIR_SIT
                    || anim == VillagerAnimationState.SLEEP_SITTING_DOWN
                    || anim == VillagerAnimationState.SITUP
                    || anim == VillagerAnimationState.SLEEP_LYING_DOWN
                    || anim.isWallSleep()) {
                return stopWalk(anim == VillagerAnimationState.SLEEP_LYING_DOWN || isSleeping());
            }
            if (anim == VillagerAnimationState.EAT && (!isSad() || !hasSadIdleClip())) {
                return stopWalk(false);
            }
            // Coming back from any of those: blend in from the pose on screen.
            resumeWalk(state.getController());
            reloadWalkWhenLegsHandedBack(state);
            if (anim == VillagerAnimationState.EAT) {
                // The eat clip keys the upper body, head and both arms. A sad villager
                // keeps its sad idle underneath for the legs; the eat controller is
                // registered later and wins on everything it keys.
                playingSadWalk = false;
                state.getController().setAnimation(ANIM_SAD_IDLE);
                return PlayState.CONTINUE;
            }
            if (isWalkingForAnimation(state) || isHurryHop()
                    || (anim == VillagerAnimationState.WALK && this.entityData.get(ANIM_PREVIEW))) {
                // A villager in a hurry runs, sad or not: someone needs it.
                RawAnimation hurry = hurryClip();
                if (hurry != null) {
                    playingSadWalk = false;
                    playingPacedWalk = false;
                    playingHurry = shownHurryGait();
                    state.getController().setAnimation(hurry);
                    return PlayState.CONTINUE;
                }
                playingHurry = HurryGait.NONE;
                playingSadWalk = isSad() && hasSadWalkClip();
                // Behind a raised shield a guard goes at 0.6 of its pace, and a bard
                // playing on the move can slow to a quarter: the legs follow the ground.
                playingPacedWalk = !playingSadWalk && (showsShieldUp()
                        || anim == VillagerAnimationState.PLAY_VIOLIN
                        || anim == VillagerAnimationState.PLAY_FLUTE
                        || anim == VillagerAnimationState.PLAY_GUITAR);
                state.getController().setAnimation(playingSadWalk ? ANIM_SAD_WALK : ANIM_WALK);
                return PlayState.CONTINUE;
            }
            // Standing still is a pose too. Without this the villager snapped to its
            // rest position the moment it stopped, which is the one frame no mocap
            // take contains.
            playingSadWalk = false;
            playingPacedWalk = false;
            playingHurry = HurryGait.NONE;
            state.getController().setAnimation(isSad() && hasSadIdleClip() ? ANIM_SAD_IDLE : ANIM_IDLE);
            return PlayState.CONTINUE;
        });
        walkController.setAnimationSpeedHandler(NpcEntity::walkControllerSpeed);
        controllers.add(walkController);

        // Work clips win over walk on shared bones. Farmer actions sync their
        // playback speed and a sequence so each tile starts at frame zero.
        // PhaseKeepingController: looping clips keep their beat, so the effects timed from a
        // clip's start (chop thunks, mine dust, till dirt) stay on the blows.
        controllers.add(new com.alex.test.animation.PhaseKeepingController<NpcEntity>(this, "chop_controller", BLEND, state -> {
            RawAnimation clip = workClip(state);
            if (clip != null) {
                int sequence = this.entityData.get(WORK_ANIMATION_SEQUENCE);
                if (sequence != renderedWorkAnimationSequence) {
                    state.getController().forceAnimationReset();
                    renderedWorkAnimationSequence = sequence;
                    workClipStartTick = tickCount;
                    if (getAnimation().isBongos()
                            && state.getController() instanceof com.alex.test.animation.PhaseKeepingController<?> keep) {
                        keep.startLate(bongoClipLate(sequence, state.getPartialTick()));
                    }
                }
            }
            // The shield snaps up: a short blend into its raise, not the work clips' 8 ticks.
            int in = clip != null && showsShieldUp() && workAnimation(getAnimation()) == null
                    ? com.alex.test.village.villagers.guard.GuardBalance.SHIELD_BLEND_TICKS : BLEND;
            return workBlend.handle(state.getController(), clip, in, tickCount,
                    canBlendOut(), () -> baseClip(state));
        }).setAnimationSpeedHandler(npc -> {
            if (npc.workBlend.blendingOut()) return 1.0;
            if (npc.workAnimation(npc.getAnimation()) != null) {
                return npc.workClipSpeed();
            }
            if (npc.showsShieldUp()) return 1.0;
            return npc.isCombatReady() ? 1.0 : (double) npc.entityData.get(WORK_ANIMATION_SPEED);
        }));

        // The cleric's throw, on a controller of its own so it can try longer
        // GeckoLib blends without changing any other work clip (a preview, 10-04).
        // In: CAST_BLEND_TICKS from the pose on screen. Out: eases into standing,
        // walking or running (OverlayBlend, AnimationBlend.Group.CAST).
        controllers.add(new FastController<NpcEntity>(this, "cast_controller",
                VillagerAnimationState.CAST_BLEND_TICKS, state -> {
            RawAnimation clip = getAnimation() == VillagerAnimationState.CAST ? ANIM_CAST : null;
            if (clip != null) {
                int sequence = this.entityData.get(WORK_ANIMATION_SEQUENCE);
                if (sequence != renderedCastSequence) {
                    state.getController().forceAnimationReset();
                    renderedCastSequence = sequence;
                }
            }
            return castBlend.handle(state.getController(), clip, VillagerAnimationState.CAST_BLEND_TICKS,
                    tickCount, canBlendOut(), () -> baseClip(state));
        }).setAnimationSpeedHandler(npc -> npc.getAnimation() == VillagerAnimationState.CAST
                ? npc.workClipSpeed() : 1.0));

        // Sleep sequences: town-hall sit-sleep and bed lie-down.
        // On a chair the sitting pose holds whatever else is playing: eating and
        // conversation gestures are registered later, so they still win on the
        // arms while the legs stay bent over the seat.
        controllers.add(new PhasedLoopController<>(this, "sleep_controller", SETTLE, state -> {
            if (isChairSeated() && !isSleeping()) {
                // A seated gesture, meal or book easing out plays the chair sit
                // itself (OverlayBlend): sit that out, as the walk controller does.
                if (seatedOverlaysOwnBase(state)) {
                    sleepReloadForOverlay = true;
                    sleepStopped = true;
                    return PlayState.STOP;
                }
                resumeSleep(state.getController(), true);
                state.getController().setAnimation(ANIM_CHAIR_SIT);
                return PlayState.CONTINUE;
            }
            VillagerAnimationState sitState = getAnimation();
            if (sitState == VillagerAnimationState.SITDOWN
                    || sitState == VillagerAnimationState.CHAIR_SIT
                    || sitState == VillagerAnimationState.SLEEP_SITTING_DOWN
                    || sitState == VillagerAnimationState.SITUP
                    || sitState == VillagerAnimationState.SLEEP_LYING_DOWN
                    || sitState.isWallSleep()) {
                // Getting into bed stays as it was: the body is laid flat at once,
                // and limbs easing in on a body that has already turned look wrong.
                resumeSleep(state.getController(), sitState != VillagerAnimationState.SLEEP_LYING_DOWN);
            } else {
                sleepStopped = true;
            }
            switch (getAnimation()) {
                case SITDOWN -> {
                    state.getController().setAnimation(ANIM_SITDOWN);
                    return PlayState.CONTINUE;
                }
                case CHAIR_SIT -> {
                    state.getController().setAnimation(ANIM_CHAIR_SIT);
                    return PlayState.CONTINUE;
                }
                case SLEEP_SITTING_DOWN -> {
                    state.getController().setAnimation(ANIM_SLEEP_SITTING_DOWN);
                    return PlayState.CONTINUE;
                }
                case SITUP -> {
                    state.getController().setAnimation(ANIM_SITUP);
                    return PlayState.CONTINUE;
                }
                case SLEEP_LYING_DOWN -> {
                    state.getController().setAnimation(ANIM_SLEEP_LYING_DOWN);
                    return PlayState.CONTINUE;
                }
                case WALL_SITDOWN -> {
                    state.getController().setAnimation(ANIM_WALL_SITDOWN);
                    return PlayState.CONTINUE;
                }
                case WALL_SLEEP -> {
                    state.getController().setAnimation(ANIM_WALL_SLEEP);
                    return PlayState.CONTINUE;
                }
                case WALL_SITUP -> {
                    state.getController().setAnimation(ANIM_WALL_SITUP);
                    return PlayState.CONTINUE;
                }
                default -> { return PlayState.STOP; }
            }
        }));

        // Eat: plays once while EatActivity is running.
        controllers.add(new FastController<NpcEntity>(this, "eat_controller", FLOURISH, state -> {
            RawAnimation clip = eatClip();
            if (clip != null) {
                int sequence = getEatAnimationSequence();
                if (sequence != renderedEatAnimationSequence) {
                    renderedEatAnimationSequence = sequence;
                    state.getController().forceAnimationReset();
                    eatClipStartTick = tickCount;
                } else if (!eatClipRunning) {
                    eatClipStartTick = tickCount;
                }
            }
            eatClipRunning = clip != null;
            return eatBlend.handle(state.getController(), clip, FLOURISH, tickCount,
                    canBlendOut(), () -> baseClip(state));
        }));

        // Reading: an open book held in front, looping with a page turn. Like
        // eating it is registered after the sleep controller, so a seated
        // reader keeps its legs over the chair and only the arms and head change.
        controllers.add(new FastController<NpcEntity>(this, "read_controller", BLEND, state ->
                readBlend.handle(state.getController(), readClip(), BLEND, tickCount,
                        canBlendOut(), () -> baseClip(state))));

        // Conversation gestures: seated variants only key the head and arms, so
        // the chair controller keeps ownership of the hips, thighs, and shins.
        controllers.add(new FastController<NpcEntity>(this, "gesture_controller", FLOURISH, state ->
                gestureBlend.handle(state.getController(), gestureClip(), FLOURISH, tickCount,
                        canBlendOut(), () -> baseClip(state))));

        // Crafting: CRAFT_TABLE at a crafting table, CRAFTING for other work in place,
        // WASH at a cauldron (played once; each wash restarts it on a new sequence).
        // The cook's clips restart on every new sequence too, and only they are paced:
        // a once clip at the synced speed (fitted to the batch), a loop at the work pace.
        controllers.add(new FastController<NpcEntity>(this, "crafting_controller", BLEND, state -> {
            RawAnimation clip = craftClip();
            if (clip == ANIM_WASH) {
                int sequence = this.entityData.get(WORK_ANIMATION_SEQUENCE);
                if (sequence != renderedWashSequence) {
                    state.getController().forceAnimationReset();
                    renderedWashSequence = sequence;
                }
            } else if (clip != null && getAnimation().isCookWork()) {
                int sequence = this.entityData.get(WORK_ANIMATION_SEQUENCE);
                if (sequence != renderedCookSequence) {
                    state.getController().forceAnimationReset();
                    renderedCookSequence = sequence;
                }
            }
            return craftBlend.handle(state.getController(), clip, BLEND, tickCount,
                    canBlendOut(), () -> baseClip(state));
        }).setAnimationSpeedHandler(npc -> {
            VillagerAnimationState anim = npc.getAnimation();
            if (!anim.isCookWork()) return 1.0;
            return anim.isCookLoop() ? npc.workClipSpeed() : (double) npc.entityData.get(WORK_ANIMATION_SPEED);
        }));

        // Nose-wiggle: triggered from tick() every 45–90 seconds.
        controllers.add(new FastController<>(this, "idle_controller", FLOURISH,
                state -> PlayState.STOP)
                .triggerableAnim("nose wiggle", ANIM_NOSE_WIGGLE));

        // A nod: triggered by nod(). Its own controller, after the gestures,
        // so the head dip shows over whatever the body is doing.
        controllers.add(new FastController<>(this, "nod_controller", 0,
                state -> PlayState.STOP)
                .triggerableAnim("nod", ANIM_NOD));

        // Mocap preview (/villager mocap ...). Registered last so it wins on every
        // bone it touches -- it drives all ten, so nothing else shows through.
        controllers.add(new FastController<>(this, "mocap_controller", BLEND, state -> {
            int clip = getMocapClip();
            if (clip <= 0 || clip >= MOCAP_CLIPS.length)
                return PlayState.STOP;

            if (clip != renderedMocapClip) {
                renderedMocapClip = clip;
                state.getController().forceAnimationReset();
            }
            state.getController().setAnimation(MOCAP_ANIMATIONS[clip]);
            return PlayState.CONTINUE;
        }));

        // Only the scale channel is keyed here, so gestures and the nose wiggle
        // can still rotate the selected nose. Skins without extra nose artwork
        // keep nose_1 through the model's texture-support query.
        controllers.add(new FastController<>(this, "nose_variant_controller", 0, state -> {
            state.getController().setAnimation(ANIM_NOSE_VARIANTS);
            return PlayState.CONTINUE;
        }));

        // A command preview also paces crafting, gestures and reading, whose normal
        // controllers do not use the work-speed multiplier. Last so it owns the pose.
        controllers.add(new FastController<NpcEntity>(this, "profession_preview_controller", BLEND, state -> {
            if (!isProfessionAnimationPreview()) return PlayState.STOP;
            var clips = ProfessionAnimationPreview.clips(getProfession());
            var clip = clips.stream().filter(c -> c.state() == getAnimation()).findFirst().orElse(clips.getFirst());
            int sequence = entityData.get(WORK_ANIMATION_SEQUENCE);
            if (sequence != renderedProfessionPreviewSequence) {
                state.getController().forceAnimationReset();
                renderedProfessionPreviewSequence = sequence;
            }
            state.getController().setAnimation(clip.animation());
            return PlayState.CONTINUE;
        }).setAnimationSpeedHandler(npc -> (double) npc.entityData.get(WORK_ANIMATION_SPEED)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() { return animatableCache; }

    /**
     * Whether this villager renders on the {@code base_villager} rig rather than the
     * original {@code dynamic_villagers} mesh.
     *
     * <p>The two rigs have different bone hierarchies and their own animation files, so
     * mesh, texture, animations and the animation names requested above all have to agree.
     *
     * <p>Now true for everyone. It was LUMBERJACK-only while the lumberjack was the
     * only skin painted for the new UV layout; the rest were relaid onto it by
     * a skin remap script. The old mesh and its clips were deleted 10-22 and
     * {@code VillagerGeoModel} always loads the base rig; the held-item and shield code
     * still asks this method, and its old-rig branches are dead.
     */
    public boolean usesBaseVillagerRig() {
        return true;
    }

    // -------------------------------------------------------------------------
    // Synced animation state (used by chop_controller)
    // -------------------------------------------------------------------------

    /**
     * Mocap preview clips, driven by {@code /villager mocap <clip>}. Index 0 means
     * "off"; the names are keys in both rigs' animation files. These are retargeted
     * Rokoko and Mixamo takes, and they animate the whole rig.
     */
    public static final String[] MOCAP_CLIPS = {
            "off",
            "mixamo_idle", "mixamo_walk", "mixamo_run", "mixamo_drunk_walk",
            "mixamo_sit", "mixamo_think", "mixamo_shrug", "mixamo_talk",
            "mixamo_cheer", "mixamo_slash", "mixamo_block", "mixamo_attack_down",
            "rok_chop", "rok_till",
            "rok_dance", "eat", "situp", "nose wiggle",
            "sitdown", "salute",
            "sleep_sitting_down", "sleep_lying_down",
            "mixamo_sad_walk", "mixamo_sad_idle",
            "mixamo_magic_cast",
            "mixamo_run_sword", "mixamo_injured_run", "mixamo_jog",
            "mixamo_mage_bolt", "mixamo_mage_nova", "mixamo_mage_chain",
            "mixamo_mage_ward", "mixamo_mage_starfall",
            "mixamo_enchant_weave", "mixamo_enchant_ponder", "mixamo_enchant_finish",
            "mixamo_enchant_seize", "mixamo_enchant_trance", "mixamo_enchant_release",
            // outside animator's draft (tools/standing-preview), preview only
            "stand_from_chair",
            // outside animator's clips (tools/flyguys, flyguys_animations file), preview only;
            // /villager flyguys puts them out with a chair, farmland and a lane
            "flyguys_walk", "flyguys_stand_from_chair", "flyguys_seed", "flyguys_walk_legs",
    };

    /**
     * One looping {@link RawAnimation} per preview clip, derived from the names above
     * rather than written out beside them. Index-aligned arrays drift: the hand-written
     * tool table once stopped at {@code mixamo_box_walk}, and every clip added after it
     * previewed with an empty hand and nothing said so.
     */
    private static final RawAnimation[] MOCAP_ANIMATIONS = previewAnimations();

    private static RawAnimation[] previewAnimations() {
        RawAnimation[] out = new RawAnimation[MOCAP_CLIPS.length];
        for (int i = 1; i < MOCAP_CLIPS.length; i++)
            out[i] = RawAnimation.begin().thenLoop(MOCAP_CLIPS[i]);
        return out;
    }

    /**
     * What a preview clip should be holding, by clip name. Absent means
     * empty-handed, as the mage's clips are: it casts bare-handed.
     */
    private static final java.util.Map<String, net.minecraft.world.item.Item> MOCAP_TOOLS =
            java.util.Map.ofEntries(
                    java.util.Map.entry("mixamo_slash", net.minecraft.world.item.Items.IRON_SWORD),
                    java.util.Map.entry("mixamo_block", net.minecraft.world.item.Items.IRON_SWORD),
                    java.util.Map.entry("mixamo_attack_down", net.minecraft.world.item.Items.IRON_SWORD),
                    java.util.Map.entry("mixamo_run_sword", net.minecraft.world.item.Items.IRON_SWORD),
                    java.util.Map.entry("rok_chop", net.minecraft.world.item.Items.IRON_AXE),
                    java.util.Map.entry("rok_till", net.minecraft.world.item.Items.IRON_HOE),
                    java.util.Map.entry("eat", net.minecraft.world.item.Items.BREAD),
                    java.util.Map.entry("flyguys_seed", net.minecraft.world.item.Items.WHEAT_SEEDS));

    /** The tool a preview clip should be holding, or {@link ItemStack#EMPTY}. */
    public static ItemStack mocapTool(int clip) {
        if (clip <= 0 || clip >= MOCAP_CLIPS.length)
            return ItemStack.EMPTY;
        net.minecraft.world.item.Item item = MOCAP_TOOLS.get(MOCAP_CLIPS[clip]);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /** Whether this preview villager stays put instead of following its root motion. */
    private boolean mocapHoldInPlace;

    public void setMocapHoldInPlace(boolean hold) { this.mocapHoldInPlace = hold; }

    /** Index into {@link #MOCAP_CLIPS} of the clip this NPC is previewing, 0 for none. */
    private static final EntityDataAccessor<Integer> MOCAP_CLIP =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

    /** Client-side: which clip the controller last started, so a change restarts it. */
    private int renderedMocapClip = 0;
    /** Ticks since the current mocap clip started, for its root motion. */
    private int mocapTick = 0;

    private static final EntityDataAccessor<Integer> ANIM_STATE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> WORK_ANIMATION_SPEED =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> WORK_ANIMATION_SEQUENCE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> EAT_ANIMATION_SEQUENCE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** A {@code /villager npc_anim} preview is on: WALK loops in place instead of needing movement. */
    private static final EntityDataAccessor<Boolean> ANIM_PREVIEW =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** The cleric has a healing orb on its palm, from the start of a throw until the orb leaves. */
    private static final EntityDataAccessor<Boolean> HOLDING_ORB =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * An enchanter is writing a book at its table. Clients hear the enchanting
     * loop from it, and the trance up close keys off it too.
     */
    private static final EntityDataAccessor<Boolean> ENCHANTING =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * The corners of the library room the enchanter writes in (both ZERO when
     * unknown). Clients play its sounds louder for a player inside that room.
     */
    private static final EntityDataAccessor<BlockPos> ENCHANTING_ROOM_MIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<BlockPos> ENCHANTING_ROOM_MAX =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BLOCK_POS);
    /**
     * What a druid is dancing on ({@link DruidCast} ordinal). Clients hear
     * that job's hum from it; a tree's and a crop's go off a moment before
     * the tree or ripe crop, when the finish sound takes over. Not saved.
     */
    private static final EntityDataAccessor<Byte> DRUID_CAST =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);
    /** Hurrying ({@link HurryGait} ordinal): the walk controller plays that loop while this villager moves. */
    private static final EntityDataAccessor<Byte> HURRY_GAIT =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);
    /**
     * Debug view ({@code /villager clericcast_raw}): drawn as plain rigid boxes,
     * without BentLimb welding and bending the limbs, so a clip's raw pose shows.
     */
    private static final EntityDataAccessor<Boolean> RAW_LIMBS =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /**
     * What the villager is doing ("work", "sleep"), kept apart from its name. Only
     * drawn for a player who has turned on {@code /villagerstatus} (VillagerLabels).
     */
    private static final EntityDataAccessor<String> WORK_STATUS =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.STRING);
    /** Wellbeing at or below WellbeingSystem.SAD_THRESHOLD: sad walk, and slower walking (SAD_WALK_PACE) and work (SAD_WORK_PACE). */
    private static final EntityDataAccessor<Boolean> SAD =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> PROFESSION_ANIMATION_PREVIEW =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** A guard or captain has its shield raised: the block pose plays and the shield is drawn. */
    private static final EntityDataAccessor<Boolean> SHIELD_RAISED =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** The raised shield is drill practice: the block clip plays at its own speed, not a fight's. */
    private static final EntityDataAccessor<Boolean> SHIELD_PRACTICE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** The raised shield is the high cover (arrows, a creeper about to blow), not the close guard. */
    private static final EntityDataAccessor<Boolean> SHIELD_HIGH =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    /** A guard has a live enemy within a few blocks: keep the ready pose between swings while it stands still. */
    private static final EntityDataAccessor<Boolean> COMBAT_READY =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> PROFESSION_DATA =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> LUMBERJACK_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** Farmer skin index, 0 to {@link FarmerSkinSequence#SKIN_COUNT} - 1; -1 for non-farmers. */
    private static final EntityDataAccessor<Integer> FARMER_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** Bard skin index, 0 to {@link BardSkinSequence#SKIN_COUNT} - 1; -1 for non-bards. */
    private static final EntityDataAccessor<Integer> BARD_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /** Miner skin index, 0 to {@link MinerSkinSequence#SKIN_COUNT} - 1; -1 for non-miners. */
    private static final EntityDataAccessor<Integer> MINER_SKIN =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);
    /**
     * The {@link DirtLevel} stage, 0 to 4. The dirt number itself stays on the
     * server; only a stage change reaches clients.
     */
    private static final EntityDataAccessor<Byte> DIRT_STAGE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.BYTE);

    /** Server-side dirt, 0 to {@link DirtLevel#MAX}; saved as "Dirt". */
    private float dirt;
    /** How farming dirties this villager, and the day's count by source. */
    private final FarmerDirt farmerDirt = new FarmerDirt();

    /**
     * The thought/speech icon floating over this villager's head, packed into
     * one synced int: bits 0-6 hold {@link ThoughtIcon#networkId()} (0 = none),
     * bit 7 is the bubble style, and bits 8-14 are a sequence counter.
     *
     * <p>The counter exists so that showing the same icon twice in a row is two
     * distinct events on the client: a conversation can legitimately answer
     * "laughter" with "laughter", and without it the second one would be an
     * unchanged field and never re-trigger the pop-in.
     */
    private static final EntityDataAccessor<Integer> BUBBLE =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.INT);

    /**
     * An item drawn in the bubble instead of its icon (a villager showing off
     * what it made, a merchant pricing it); empty for an ordinary bubble.
     */
    private static final EntityDataAccessor<ItemStack> BUBBLE_ITEM =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.ITEM_STACK);

    /**
     * What the cook's clips show in a fist (the fuel, the raw food, the dish): drawn only
     * (HeldItemGeoLayer), never put in its hands or pack. Empty for none; not saved.
     */
    private static final EntityDataAccessor<ItemStack> COOK_PROP =
            SynchedEntityData.defineId(NpcEntity.class, EntityDataSerializers.ITEM_STACK);

    /** Stable identity linking this puppet to its logical {@link com.alex.test.village.VillagerModel}. */
    private UUID npcId = UUID.randomUUID();

    /** Server-side countdown until a timed animation returns to IDLE. */
    private int animationTimer = 0;

    /** Guard and captain shield state; server-side only, see {@link ShieldStance}. */
    private final ShieldStance shieldStance = new ShieldStance();

    /**
     * {@code /villager npc_anim}: a state held against every other
     * {@link #setAnimation} call (the brain's walk, idle and chat states would
     * replace it the next tick) until {@link #previewUntil}. Not saved.
     */
    @Nullable private VillagerAnimationState previewAnimation;
    /** Game time the preview clip starts; IDLE until then, so a repeat of the same clip restarts. */
    private long previewStartAt;
    private long previewUntil;
    /** A short IDLE before the preview clip: clients see the state change and start it from frame 0. */
    private static final int PREVIEW_RESTART_TICKS = 3;
    /** showoff_bass.ogg is 10.0 s: started again on the tick it ends, so it loops without a gap. */
    private static final int BASS_PREVIEW_MUSIC_TICKS = 200;
    /** Every showoff_violin*.ogg is 6.875 s. */
    private static final int VIOLIN_PREVIEW_MUSIC_TICKS = 138;
    /** The band song's drum part is exactly 48.0 s (TavernBand.SONG_TICKS). */
    private static final int BONGOS_PREVIEW_MUSIC_TICKS = 960;
    /** The violin preview's play-throughs, in order: the first piece once, the rest twice each. */
    private static final List<net.neoforged.neoforge.registries.DeferredHolder<net.minecraft.sounds.SoundEvent,
            net.minecraft.sounds.SoundEvent>> VIOLIN_PREVIEW_ORDER = List.of(
            com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN,
            com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_2, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_2,
            com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_1, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_1,
            com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_3, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_3,
            com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_4, com.alex.test.ModSounds.BARD_SHOWOFF_VIOLIN_4);
    /** Which play-through of the violin preview's pieces is next. */
    private int previewMusicStep;

    /** Last eat sequence observed by this client-side entity instance. */
    private int renderedEatAnimationSequence = -1;
    /** Client: the tick the eat controller last started its clip, and whether it is still playing. */
    private int eatClipStartTick;
    private boolean eatClipRunning;
    /** Restarts a farmer's next work bout even when it uses the same clip. */
    private int renderedWorkAnimationSequence = -1;
    /** Client: the throw sequence cast_controller last started, so each throw restarts the clip. */
    private int renderedCastSequence = -1;
    /** Client: the wash sequence crafting_controller last started, so each wash plays from frame zero. */
    private int renderedWashSequence = -1;
    /** Client: the same for the cook's clips, so each piece of a batch plays from frame zero. */
    private int renderedCookSequence = -1;
    // ---- Blending between clips (AnimationBlend, OverlayBlend), client side ----

    /** One per controller that plays over the standing and walking loop; see OverlayBlend. */
    private final OverlayBlend workBlend = new OverlayBlend(AnimationBlend.Group.WORK);
    private final OverlayBlend castBlend = new OverlayBlend(AnimationBlend.Group.CAST);
    private final OverlayBlend eatBlend = new OverlayBlend(AnimationBlend.Group.HANDS);
    private final OverlayBlend readBlend = new OverlayBlend(AnimationBlend.Group.HANDS);
    private final OverlayBlend gestureBlend = new OverlayBlend(AnimationBlend.Group.GESTURES);
    private final OverlayBlend craftBlend = new OverlayBlend(AnimationBlend.Group.HANDS);

    /** The walk controller is sitting something out; why, for how it comes back. */
    private boolean walkStopped;
    private boolean walkStoppedForBed;
    private boolean walkReloadForOverlay;
    /** The same for the sleep controller's chair sit and sitting states. */
    private boolean sleepStopped;
    private boolean sleepReloadForOverlay;

    private PlayState stopWalk(boolean forBed) {
        walkStopped = true;
        walkStoppedForBed = forBed;
        playingHurry = HurryGait.NONE;
        playingPacedWalk = false;
        return PlayState.STOP;
    }

    /**
     * The walk controller coming back after sitting something out reloads its
     * clip, so GeckoLib blends it in from the pose on screen instead of picking
     * the loop up where it left off. Always after an overlay's blend out (that
     * handoff needs it); otherwise when {@link AnimationBlend.Group#RESUME} is
     * on. Never out of bed: the body stands up at once, as before.
     */
    private void resumeWalk(AnimationController<NpcEntity> controller) {
        if (!walkStopped) return;
        boolean reload = walkReloadForOverlay
                || (AnimationBlend.enabled(AnimationBlend.Group.RESUME) && !walkStoppedForBed);
        if (reload) controller.forceAnimationReset();
        walkStopped = false;
        walkStoppedForBed = false;
        walkReloadForOverlay = false;
    }

    /** Whether last frame's work clip keyed the legs (the standing or dancing violin, the bass). */
    private boolean workClipHeldLegs;

    /**
     * A work clip that keys the legs swapped for one that does not (a bard setting
     * off mid-tune: violin standing or dancing to the violin walk, which keys only
     * arms, head and bow). GeckoLib blends only the bones the new clip keys, so the
     * legs dropped onto the walk underneath in one frame. Reloading the walk makes
     * it blend in from the pose on screen instead. A clip that simply ends is
     * {@link OverlayBlend}'s, as before.
     */
    private void reloadWalkWhenLegsHandedBack(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        RawAnimation clip = workClip(state);
        boolean holdsLegs = clip != null && clipKeysLegs(clip);
        if (workClipHeldLegs && !holdsLegs && clip != null) state.getController().forceAnimationReset();
        workClipHeldLegs = holdsLegs;
    }

    private static final java.util.Map<String, Boolean> CLIP_KEYS_LEGS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Whether {@code clip}'s first stage keys a leg bone; read once per clip from the baked file. */
    private boolean clipKeysLegs(RawAnimation clip) {
        if (clip.getAnimationStages().isEmpty()) return false;
        String name = clip.getAnimationStages().get(0).animationName();
        String file = "animations/base_villager.animation.json";
        String key = file + "#" + name;
        Boolean known = CLIP_KEYS_LEGS.get(key);
        if (known != null) return known;
        var cache = software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations();
        var anims = cache == null ? null
                : cache.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, file));
        var anim = anims == null ? null : anims.getAnimation(name);
        // Not loaded yet: ask again next frame rather than remember a wrong answer.
        if (anim == null) return false;
        boolean legs = false;
        for (var bone : anim.boneAnimations()) {
            if (bone.boneName().endsWith("_leg")) { legs = true; break; }
        }
        CLIP_KEYS_LEGS.put(key, legs);
        return legs;
    }

    /** The sleep controller's version of {@link #resumeWalk}. */
    private void resumeSleep(AnimationController<NpcEntity> controller, boolean mayReload) {
        if (!sleepStopped) return;
        boolean reload = sleepReloadForOverlay
                || (mayReload && AnimationBlend.enabled(AnimationBlend.Group.RESUME));
        if (reload) controller.forceAnimationReset();
        sleepStopped = false;
        sleepReloadForOverlay = false;
    }

    /** Whether any overlay is ending or easing out, so the walk controller sits this frame out. */
    private boolean overlaysOwnBase(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        int t = tickCount;
        return workBlend.ownsBase(t, workClip(state) != null)
                || castBlend.ownsBase(t, getAnimation() == VillagerAnimationState.CAST)
                || seatedOverlaysOwnBase(state);
    }

    /** The overlays registered after the sleep controller, which a chair sit has to sit out for. */
    private boolean seatedOverlaysOwnBase(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        int t = tickCount;
        return eatBlend.ownsBase(t, eatClip() != null)
                || readBlend.ownsBase(t, readClip() != null)
                || gestureBlend.ownsBase(t, gestureClip() != null)
                || craftBlend.ownsBase(t, craftClip() != null);
    }

    /** Easing out makes no sense asleep in bed or in water, where the base pose is not shown. */
    private boolean canBlendOut() {
        return !isSleeping() && !isInWaterOrBubble();
    }

    /** What an overlay eases out into: the chair sit when seated, otherwise the standing and walking loop. */
    private RawAnimation baseClip(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        return isChairSeated() && !isSleeping() ? ANIM_CHAIR_SIT : baseLoopClip(state);
    }

    /** chop_controller's clip: the work clip for the state, or the guard's stance standing still. */
    @Nullable
    private RawAnimation workClip(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        RawAnimation trance = tranceClip();
        if (trance != null) return trance;
        RawAnimation clip = workAnimation(getAnimation());
        // The block stance is also the guard's ready pose, but only standing still:
        // it keys the legs, so while the guard walks to its enemy the walk clip must win.
        if (clip == null && !isSleeping() && !isEating()
                && (showsShieldUp() || (isCombatReady() && !isWalkingForAnimation(state)))) {
            // The shield clips live in base_villager's animation file only. Walking
            // behind the shield, its upper half only, so the walk below shows.
            if (showsShieldUp() && usesBaseVillagerRig()) {
                clip = shieldClip(isWalkingForAnimation(state));
            } else {
                clip = ANIM_BLOCK;
            }
        }
        noteShieldShown();
        return clip;
    }

    /** Client: tickCount when the shield was last shown coming up, and in which stance. */
    private int shieldShownAt = Integer.MIN_VALUE / 2;
    private boolean shieldWasShown;
    private boolean shieldShownHigh;

    private void noteShieldShown() {
        boolean shown = showsShieldUp();
        if (shown && !shieldWasShown) {
            shieldShownAt = tickCount;
            shieldShownHigh = isShieldHigh();
        }
        shieldWasShown = shown;
    }

    /**
     * The shield stance's clip: the raise and hold while the raise is still
     * playing, the hold alone after that (or when the stance changed meanwhile).
     */
    private RawAnimation shieldClip(boolean walking) {
        boolean high = isShieldHigh();
        boolean raising = shieldWasShown && high == shieldShownHigh
                && tickCount - shieldShownAt <= com.alex.test.village.villagers.guard.GuardBalance.SHIELD_RAISE_SHOW_TICKS;
        if (!shieldWasShown) raising = true;   // first frame up: noteShieldShown runs after this
        if (high) {
            if (raising) return walking ? ANIM_SHIELD_COVER_WALK : ANIM_SHIELD_COVER;
            return walking ? ANIM_SHIELD_COVER_HOLD_WALK : ANIM_SHIELD_COVER_HOLD;
        }
        if (raising) return walking ? ANIM_SHIELD_GUARD_WALK : ANIM_SHIELD_GUARD;
        return walking ? ANIM_SHIELD_GUARD_HOLD_WALK : ANIM_SHIELD_GUARD_HOLD;
    }

    @Nullable
    private RawAnimation eatClip() {
        return getAnimation() == VillagerAnimationState.EAT ? ANIM_EAT : null;
    }

    @Nullable
    private RawAnimation readClip() {
        return getAnimation() == VillagerAnimationState.READ ? ANIM_READ : null;
    }

    @Nullable
    private RawAnimation craftClip() {
        VillagerAnimationState anim = getAnimation();
        if (anim.isCookWork()) return ANIM_COOK.get(anim);
        return switch (anim) {
            case CRAFTING -> ANIM_CRAFTING;
            case CRAFT_TABLE -> ANIM_CRAFT_TABLE;
            case SMITH_ANVIL -> ANIM_SMITH_ANVIL;
            case SMITH_ANVIL_HEAVY -> ANIM_SMITH_ANVIL_HEAVY;
            case SMITH_ENCHANT -> ANIM_SMITH_ENCHANT;
            case SMITH_ENCHANT_TAP -> ANIM_SMITH_ENCHANT_TAP;
            case SMITH_SMITHING -> ANIM_SMITH_SMITHING;
            case SMITH_RECYCLE -> ANIM_SMITH_RECYCLE;
            case SMITH_FURNACE -> ANIM_SMITH_FURNACE;
            case SMITH_ANVIL_DRAW -> ANIM_SMITH_ANVIL_DRAW;
            case WASH -> ANIM_WASH;
            default -> null;
        };
    }

    /** Conversation and drill gestures; the seated variants key only the head and arms. */
    @Nullable
    private RawAnimation gestureClip() {
        boolean seated = isChairSeated();
        return switch (getAnimation()) {
            case WAVE -> seated ? ANIM_CHAIR_CHEER : ANIM_CHEER;
            case SALUTE -> ANIM_SALUTE;
            case POWER_UP -> ANIM_POWER_UP;
            case KICK -> ANIM_KICK;
            case LOOK_AROUND -> ANIM_LOOK_AROUND;
            case THINK -> seated ? ANIM_CHAIR_THINK : ANIM_THINK;
            case ARM_ON_HIP -> seated ? ANIM_CHAIR_ARM_ON_HIP : ANIM_ARM_ON_HIP;
            case SHRUG -> seated ? ANIM_CHAIR_SHRUG : ANIM_SHRUG;
            default -> null;
        };
    }

    /** The loop the walk controller shows right now, chosen the way it chooses. */
    private RawAnimation baseLoopClip(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        if (isWalkingForAnimation(state) || isHurryHop() || (getAnimation() == VillagerAnimationState.WALK
                && this.entityData.get(ANIM_PREVIEW))) {
            RawAnimation hurry = hurryClip();
            if (hurry != null) return hurry;
            return isSad() && hasSadWalkClip() ? ANIM_SAD_WALK : ANIM_WALK;
        }
        return isSad() && hasSadIdleClip() ? ANIM_SAD_IDLE : ANIM_IDLE;
    }
    /** Client: tickCount when chop_controller last restarted its clip from frame zero. */
    private int workClipStartTick;
    private int renderedProfessionPreviewSequence = -1;
    private int professionPreviewIndex;
    private int professionPreviewTicks;
    @Nullable private Vec3 professionPreviewPosition;
    private float professionPreviewYaw;
    /** A preview pinned to one clip (the bass preview), or null to cycle the profession's clips. */
    @Nullable private VillagerAnimationState professionPreviewOnly;
    /** The bass preview's recording: ticks until it starts again, or -1 for no music. */
    private int previewMusicTicks = -1;

    /** Items carried by this NPC (logs, loot, etc.). */
    private InventoryManager inventory = new InventoryManager();
    private final NpcUiInteractionHandler uiInteraction = new NpcUiInteractionHandler();
    private List<ProfessionSkill> professionSkills = List.of();
    private boolean professionSkillsInitialized;
    /** One-shot level used when a nomad is assigned a profession it did not roll. */
    @Nullable private Integer pendingAssignedProfessionLevel;
    @Nullable private PendingProfessionChange pendingProfessionChange;
    private boolean loadedFromSave;

    /**
     * Persistent scratch space for the {@link com.alex.test.village.brain.VillagerBrain}
     * (needs, profession). The brain reads it on bind and writes it periodically;
     * this entity just stores it through save/load.
     */
    private CompoundTag brainData = new CompoundTag();

    /** This villager's job. Assigned by profession-token items; NONE by default. Synced to client. */

    /** Persistent owning-village identity; null only for legacy/unassigned NPCs. */
    @Nullable
    private VillagerVillageLink villageLink;
    /** Game time before which a failed brain write on save is not logged again (once in 5 minutes). */
    private long saveFlushQuietUntil;
    /**
     * Game time of this body's last server tick, by Minecraft or by FarAwake. The only proof the
     * body moved this tick: FarAwake ticks a far body only when this is not this tick, and the
     * walk goes on only when it is.
     */
    private long bodyTickTime = Long.MIN_VALUE;

    /**
     * Temporary movement assist for stairs.
     *
     * <p>The custom village graph stores every surface as an integer feet-Y node.
     * On stair blocks that can make vanilla navigation decide to jump toward the
     * next node even though the collision shape is already a smooth half-step.
     * MoveToCommand enables this assist only while the current/next waypoint is a
     * stair, so normal block climbing still behaves like vanilla.
     */
    private int stairStepAssistTicks = 0;

    /**
     * Round 6 of the walking-feel plan: the pace MoveToCommand asks for over
     * the last blocks of a walk (1 = full), and the tick it last asked. Unasked
     * for two ticks, it no longer applies.
     */
    private float walkEndEase = 1.0F;
    private int walkEndEaseTick = Integer.MIN_VALUE / 2;

    /** The ladder column currently selected by MoveToCommand, if any. */
    @Nullable private BlockPos ladderClimbTarget;
    /** Vanilla's cap on sliding down a ladder. */
    private static final double LADDER_SLIDE_SPEED = -0.15;
    private final CancellableJumpControl cancellableJumpControl;

    // -------------------------------------------------------------------------

    /**
     * Whether this NPC is a resident of the village that owns its chunk.
     *
     * <p>Not everything wearing the villager rig belongs to the village.
     * Mayors are attached to a Town Hall rather than the population, and
     * visitors are passing through; both would otherwise be adopted by
     * {@code VillageManager}'s discovery scan, which collects every
     * {@code NpcEntity} in the graph bounds. Subclasses that are not residents
     * override this, so a new one only has to say so in a single place instead
     * of being remembered at each scan site.
     */
    public boolean isVillageMember() {
        return !isPreviewPuppet() && !isWaitingSettler();
    }

    /**
     * A villager a command spawned to show an animation or an icon. They are
     * frozen with vanilla's NoAI, so one taken in as a resident never walked:
     * a mocap villager from before a reload, its clip unsaved, was adopted,
     * named and sent for food it could not take a step towards.
     */
    public boolean isPreviewPuppet() {
        if (isProfessionAnimationPreview() || getMocapClip() > 0) return true;
        Set<String> tags = getTags();
        return tags.contains(MOCAP_PREVIEW_TAG)
                || tags.contains(THOUGHT_PREVIEW_TAG)
                || tags.contains(CHAIR_PREVIEW_TAG);
    }

    /** Entity tag on a villager waiting for its generated village to wake up. */
    public static final String WAITING_SETTLER_TAG = "dynamicvillages.waiting_settler";

    /**
     * Spawned in place of a vanilla villager in a generated village that is not
     * a Dynamic Village yet. It belongs to nothing until that village wakes up
     * and takes it in; until then no other village may adopt it, which the
     * relink fallback would otherwise do for any villager with no village.
     */
    public boolean isWaitingSettler() {
        return getTags().contains(WAITING_SETTLER_TAG);
    }

    public NpcEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new LadderAwareMoveControl();
        this.cancellableJumpControl = new CancellableJumpControl(this);
        this.jumpControl = cancellableJumpControl;
        setPathfindingMalus(PathType.WATER, 8.0F);
        setPathfindingMalus(PathType.WATER_BORDER, 4.0F);
    }

    /** Round 6: a walk builds up to full pace over this many ticks... */
    private static final int   EASE_START_TICKS = 8;
    /** ...from this share of it. */
    private static final float EASE_START_SLOWEST = 0.5F;
    /** Round 6: stopped this long, the next walk starts gently again. */
    private static final int   EASE_RESTART_TICKS = 5;
    /** Round 6: turning more than this in a tick slows the villager... */
    private static final float EASE_TURN_FROM = 30.0F;
    /** ...down to its slowest at this. */
    private static final float EASE_TURN_FULL = 90.0F;
    private static final float EASE_TURN_SLOWEST = 0.5F;
    /** Round 6: walking pace (VillagerModel.WALK_SPEED 0.55) and below eases; runs (0.6 and up) do not. */
    private static final double EASE_MAX_SPEED_MODIFIER = 0.575;

    /** Round 6: see {@link #walkEndEase}. Asked for every tick of a walk's last blocks. */
    public void setWalkEndEase(float pace) {
        walkEndEase = pace;
        walkEndEaseTick = tickCount;
    }

    private final class LadderAwareMoveControl extends MoveControl {
        /** Ticks before another early running hop may start. */
        private int earlyHopCooldown;
        /** Round 6: ticks into the current walk, up to EASE_START_TICKS, and the last tick it moved. */
        private int easeMovingTicks;
        private int easeLastMoveTick = Integer.MIN_VALUE / 2;

        private LadderAwareMoveControl() {
            super(NpcEntity.this);
        }

        @Override
        public void setWantedPosition(double x, double y, double z, double speed) {
            // Every walk passes through here -- path following, ladders, the
            // short steering moves -- so a sad villager's SAD_WALK_PACE is
            // applied once, here. (Applied in tick() it only reached ladders:
            // off a ladder tick() hands over to vanilla, which sets the speed
            // itself from speedModifier.)
            super.setWantedPosition(x, y, z, speed * NpcEntity.this.sadWalkPace());
            // Vanilla leaves MOVE_TO in JUMPING until the mob lands. A planned
            // ladder segment can be airborne for many rungs, so keep steering.
            if (ladderClimbTarget != null
                    || level().getBlockState(blockPosition()).is(Blocks.LADDER)) {
                operation = Operation.MOVE_TO;
            }
        }

        @Override
        public void tick() {
            if (ladderClimbTarget == null
                    && !level().getBlockState(blockPosition()).is(Blocks.LADDER)) {
                if (operation == Operation.STRAFE) {
                    strafeAsAsked();
                    return;
                }
                boolean wasMoveTo = operation == Operation.MOVE_TO;
                float yawBefore = NpcEntity.this.getYRot();
                if (earlyHopCooldown > 0) earlyHopCooldown--;
                super.tick();
                if (wasMoveTo && operation == Operation.JUMPING
                        && (isOpenTrapdoorOnlyJump() || isShutGateOnlyJump())) {
                    operation = Operation.WAIT;
                    cancellableJumpControl.cancel();
                } else if (wasMoveTo && operation != Operation.JUMPING && earlyHopCooldown == 0
                        && RunningHop.shouldJumpEarly(NpcEntity.this, wantedX, wantedY, wantedZ)) {
                    // Running at a one-block step: up and over it from a few strides
                    // out instead of into its face (RunningHop).
                    cancellableJumpControl.jump();
                    operation = Operation.JUMPING;
                    earlyHopCooldown = RunningHop.COOLDOWN_TICKS;
                }
                if (wasMoveTo) easeWalk(yawBefore);
                return;
            }
            if (operation != Operation.MOVE_TO) {
                NpcEntity.this.setSpeed(0.0F);
                return;
            }
            operation = Operation.WAIT;
            double dx = wantedX - NpcEntity.this.getX();
            double dz = wantedZ - NpcEntity.this.getZ();
            if (WalkFeel.ladderFace && faceLadderWhileCentring(dx, dz)) return;
            if (dx * dx + dz * dz < 0.01) {
                NpcEntity.this.setSpeed(0.0F);
                return;
            }
            float yaw = (float) (Mth.atan2(dz, dx) * 180.0 / Math.PI) - 90.0F;
            NpcEntity.this.setYRot(rotlerp(NpcEntity.this.getYRot(), yaw, 90.0F));
            NpcEntity.this.setSpeed((float) (speedModifier
                    * NpcEntity.this.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            // The normal MOVE_TO controller treats a ladder's collision shape
            // as a block to jump over, which interrupts steering at entrances.
        }

        /** Walk spins round 1: nearer the rung's middle than this, no sideways nudge. */
        private static final double LADDER_FACE_DEAD_BAND = 0.03;
        /** The most the nudge toward the rung's middle moves the villager per tick, in blocks. */
        private static final double LADDER_FACE_NUDGE = 0.05;
        /** How fast the villager turns to face the ladder when it gets on, degrees per tick. */
        private static final float LADDER_FACE_TURN = 45.0F;

        /**
         * Walk spins round 1 (2026-10-17): aimed at the rung's own column, the
         * villager faces the ladder and is nudged toward the column's middle
         * instead of being turned toward it. The old steering set the yaw to
         * point at the middle every tick, up to 90 degrees a tick, so a
         * villager a hair past the middle spun round and back while climbing:
         * about a quarter of all the spins the {@code [WalkBack]} log counted.
         * The nudge sets the sideways velocity outright, a fraction of the
         * distance, so it closes in without overshooting. Returns false when
         * the aim is off the rung's column (stepping onto or off the ladder,
         * the exit landing), which keeps the old steering for that.
         */
        private boolean faceLadderWhileCentring(double dx, double dz) {
            BlockPos rung = ladderClimbTarget;
            if (rung == null) return false;
            if (Mth.floor(wantedX) != rung.getX() || Mth.floor(wantedZ) != rung.getZ()) return false;
            BlockState state = level().getBlockState(rung);
            if (!state.is(Blocks.LADDER)) return false;
            Direction wall = state.getValue(LadderBlock.FACING).getOpposite();
            NpcEntity.this.setYRot(rotlerp(NpcEntity.this.getYRot(), wall.toYRot(), LADDER_FACE_TURN));
            NpcEntity.this.setSpeed(0.0F);
            Vec3 motion = getDeltaMovement();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < LADDER_FACE_DEAD_BAND) {
                setDeltaMovement(motion.x * 0.5, motion.y, motion.z * 0.5);
            } else {
                double push = Math.min(LADDER_FACE_NUDGE, dist * 0.3);
                setDeltaMovement(dx / dist * push, motion.y, dz / dist * push);
            }
            return true;
        }

        /**
         * Round 6 of the walking-feel plan: walking pace eases in and out like
         * a person's. Vanilla starts at full pace, takes a corner at full pace
         * however sharp, and stops dead. Here the villager builds up to pace
         * over its first EASE_START_TICKS of a walk, slows while it turns
         * sharply, and slows over the last blocks of a walk (MoveToCommand
         * asks for that, through {@link #setWalkEndEase}).
         *
         * <p>Only the forward push is scaled, after vanilla has set it; the
         * steering and every jump are vanilla's. Runs, chases and flights keep
         * their pace, and so do a jump, a stair climb, water and the air,
         * where pace decides whether a step is cleared.
         */
        private void easeWalk(float yawBefore) {
            int now = NpcEntity.this.tickCount;
            if (now - easeLastMoveTick > EASE_RESTART_TICKS) easeMovingTicks = 0;
            easeLastMoveTick = now;
            if (easeMovingTicks < EASE_START_TICKS) easeMovingTicks++;
            if (!WalkFeel.ease || operation == Operation.JUMPING) return;
            if (speedModifier > EASE_MAX_SPEED_MODIFIER) return;
            if (!onGround() || isInWaterOrBubble() || stairStepAssistTicks > 0) return;

            float start = EASE_START_SLOWEST
                    + (1.0F - EASE_START_SLOWEST) * easeMovingTicks / (float) EASE_START_TICKS;
            float turned = Math.abs(Mth.wrapDegrees(getYRot() - yawBefore));
            float turn = turned <= EASE_TURN_FROM ? 1.0F
                    : 1.0F - (1.0F - EASE_TURN_SLOWEST)
                    * Math.min(1.0F, (turned - EASE_TURN_FROM) / (EASE_TURN_FULL - EASE_TURN_FROM));
            float end = now - walkEndEaseTick <= 2 ? walkEndEase : 1.0F;
            float pace = Math.min(start, Math.min(turn, end));
            if (pace < 1.0F) setZza(zza * pace);
        }

        /**
         * Vanilla's STRAFE without its walkable check. That check probes a spot
         * level with the mob's feet and turned 90 degrees from the step -- beside
         * the mob, not behind it -- and anything there short of plain floor (the
         * slab or stair it stands on, a fence beside it) turns the step into a walk
         * forward. The archer backing off a mob mid-draw, the only strafe here,
         * walked into the mob instead; it checks the ground behind it itself.
         */
        private void strafeAsAsked() {
            NpcEntity.this.setSpeed((float) (speedModifier
                    * NpcEntity.this.getAttributeValue(Attributes.MOVEMENT_SPEED)));
            NpcEntity.this.setZza(strafeForwards);
            NpcEntity.this.setXxa(strafeRight);
            operation = Operation.WAIT;
        }

        /**
         * Vanilla MOVE_TO also jumps whenever the cell the mob's centre is in
         * has a collision shape reaching above its feet. An open trapdoor's
         * shape reaches the top of the cell, but it is only a thin plate on
         * one edge, so villagers walking beside a wall of open trapdoors hop
         * for nothing. True when that was the only reason for the jump, not a
         * real step up to the wanted position.
         */
        private boolean isOpenTrapdoorOnlyJump() {
            BlockState state = level().getBlockState(blockPosition());
            if (!(state.getBlock() instanceof TrapDoorBlock) || !state.getValue(TrapDoorBlock.OPEN)) {
                return false;
            }
            double dx = wantedX - NpcEntity.this.getX();
            double dz = wantedZ - NpcEntity.this.getZ();
            double dy = wantedY - NpcEntity.this.getY();
            boolean stepUp = dy > NpcEntity.this.maxUpStep()
                    && dx * dx + dz * dz < Math.max(1.0F, NpcEntity.this.getBbWidth());
            return !stepUp;
        }

        /**
         * Vanilla MOVE_TO also jumps out of any block whose collision reaches
         * above the feet, sparing doors and fences but not fence gates. A gate
         * shut on a villager standing in it, or one it pressed against, made it
         * hop, and the hop cannot clear the gate's bar. True when that was the
         * only reason, not a real step up to the wanted position.
         */
        private boolean isShutGateOnlyJump() {
            BlockState state = level().getBlockState(blockPosition());
            if (!(state.getBlock() instanceof FenceGateBlock) || state.getValue(FenceGateBlock.OPEN)) {
                return false;
            }
            double dx = wantedX - NpcEntity.this.getX();
            double dz = wantedZ - NpcEntity.this.getZ();
            double dy = wantedY - NpcEntity.this.getY();
            boolean stepUp = dy > NpcEntity.this.maxUpStep()
                    && dx * dx + dz * dz < Math.max(1.0F, NpcEntity.this.getBbWidth());
            if (!stepUp) logGateHop("hop cancelled inside a shut gate", blockPosition());
            return !stepUp;
        }
    }

    /** Lets the move control take back a jump it just asked for. */
    private static final class CancellableJumpControl extends JumpControl {
        private CancellableJumpControl(NpcEntity npc) {
            super(npc);
        }

        void cancel() {
            jump = false;
        }
    }

    /**
     * Called by the custom movement command when the current route segment touches
     * a stair block. This lets the NPC step along stairs instead of doing a small
     * jump on every Y-changing stair waypoint.
     */
    public void enableStairStepAssist(int ticks) {
        stairStepAssistTicks = Math.max(stairStepAssistTicks, ticks);
    }

    /** Ends the stair assist at once, for a step off the stairs that needs a real jump. */
    public void cancelStairStepAssist() {
        stairStepAssistTicks = 0;
    }

    public void setLadderClimbTarget(@Nullable BlockPos rung) {
        ladderClimbTarget = rung;
    }

    /**
     * True while the walker is driving a ladder leg: climbing, coming down,
     * or stepping off the top or bottom rung. Set only by MoveToCommand.
     */
    public boolean isOnLadderLeg() {
        return ladderClimbTarget != null;
    }

    @Override
    public boolean onClimbable() {
        // Vanilla adds an upward push on ladder collision even after a planned
        // climb has reached its rung. MoveToCommand owns ladder velocity so it
        // can stop at the selected exit; walking past a ladder stays level.
        BlockPos feet = blockPosition();
        if (!level().isClientSide && level().getBlockState(feet).is(Blocks.LADDER)) {
            // Keep vanilla's fall protection, though. A route cancelled mid-climb
            // (eating, a UI, a stuck re-path) clears the climb target, and the
            // villager used to free-fall the whole shaft and take damage below.
            resetFallDistance();
            Vec3 motion = getDeltaMovement();
            if (motion.y < LADDER_SLIDE_SPEED) {
                setDeltaMovement(motion.x, LADDER_SLIDE_SPEED, motion.z);
            }
            return false;
        }
        return super.onClimbable();
    }

    /**
     * Client glide toward each server position update, in ticks. Vanilla uses 3,
     * the same as the 3-tick update interval, so the glide ends the moment the
     * next update is due. When the server and client tick clocks drift into step,
     * an update gets handled a tick late: every walking villager stood still for
     * that tick, then hurried to catch up, all at once. One spare tick lets the
     * late update land mid-glide instead. Walking speed stays even: with updates
     * every 3 ticks the glide settles at exactly one tick of travel per tick.
     */
    private static final int CLIENT_LERP_STEPS = 4;

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps) {
        super.lerpTo(x, y, z, yRot, xRot, Math.max(steps, CLIENT_LERP_STEPS));
    }

    // -------------------------------------------------------------------------
    // Navigation — enable door opening so NPCs can pass through doors
    // -------------------------------------------------------------------------

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation nav = new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                nodeEvaluator = new TrapdoorPlateAwareEvaluator();
                nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(nodeEvaluator, maxVisitedNodes);
            }

            /**
             * Round 2 of the walking-feel plan. Every new path starts at the
             * block the villager stands in, and Minecraft only counts that
             * first point as reached within 0.45 of the block's middle (or when
             * the second point is straight on). Walking along the edge of its
             * block into a stretch that bends sideways, the villager turned
             * back to its own block's middle first, then round again: a quick
             * spin at a corner, after a door, whenever a stretch was planned
             * again. Already in that block with a level step on, it walks for
             * the second point straight away. See {@link LegShape#startSkippable}.
             */
            @Override
            protected void followThePath() {
                if (WalkFeel.legStart && path != null && LegShape.startSkippable(mob, path)) {
                    path.advance();
                }
                super.followThePath();
            }
        };
        nav.setCanOpenDoors(true);
        nav.setCanPassDoors(true);
        nav.setCanFloat(true);
        return nav;
    }

    /**
     * Vanilla lets a mob with a target plan drops as deep as its spare health
     * allows: about 13 blocks for a guard at 20 health, more with more. When
     * the old walker handed every leg to vanilla navigation, a defender running
     * to help (it has a target) was routed straight off a tower instead of down
     * its ladder; the walks still on vanilla (a guard's close chase, travellers
     * off the village map) keep the cap. A villager never plans a drop deeper
     * than one without a target would: 3 blocks.
     */
    @Override
    public int getMaxFallDistance() {
        return getComfortableFallDistance(0.0F);
    }

    /**
     * Vanilla counts an open trapdoor's cell as walkable from every side, so
     * it cut diagonally through the plate's end and the villager stood pressed
     * against it. Such cells are closed to vanilla here. Village routes no
     * longer use vanilla (the route follower knows which side the plate is on);
     * this is for the walks still on it.
     *
     * <p>Vanilla also reads the open air above any trapdoor as a floor, at the
     * top of its collision box, and an open trapdoor's box is full height. With
     * the trapdoor's own cell blocked, vanilla planned a jump up onto it as onto
     * a solid block: trapdoors set as railings beside a stair became a second
     * staircase, and villagers hopped at plates they could never land on. So the
     * cell above an open trapdoor is no place to stand either.
     */
    private static final class TrapdoorPlateAwareEvaluator extends WalkNodeEvaluator {
        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            if (isOpenTrapdoor(context.getBlockState(new BlockPos(x, y, z)))) {
                return PathType.BLOCKED;
            }
            PathType type = super.getPathType(context, x, y, z);
            if (type == PathType.DANGER_TRAPDOOR
                    && isOpenTrapdoor(context.getBlockState(new BlockPos(x, y - 1, z)))) {
                return PathType.BLOCKED;
            }
            return type;
        }

        private static boolean isOpenTrapdoor(BlockState state) {
            return state.getBlock() instanceof TrapDoorBlock && state.getValue(TrapDoorBlock.OPEN);
        }
    }

    // -------------------------------------------------------------------------
    // No AI — puppet has zero autonomous behaviour.
    // -------------------------------------------------------------------------

    @Override
    protected void registerGoals() {
        // Intentionally empty.
    }

    /**
     * Vanilla turns a standing mob's body towards where its head points once the
     * head has been still for half a second. A guard looking around turns only its
     * head (the clip draws it; {@code yHeadRot} is its real view direction), so the
     * body stays put for those clips instead of swinging round after the head.
     */
    @Override
    protected BodyRotationControl createBodyControl() {
        return new BodyRotationControl(this) {
            @Override
            public void clientTick() {
                if (isLookingAround()) {
                    setYBodyRot(getYRot());
                    return;
                }
                float before = yBodyRot;
                super.clientTick();
                if (level().isClientSide) easeDrawnBodyTurn(before);
            }
        };
    }

    /**
     * Round 4 of the walking-feel plan, drawing only. While walking, vanilla
     * draws the body facing straight down the way the villager moves, so every
     * turn the server makes (up to 90 degrees a tick) is drawn as a snap, and a
     * quick turn and back as a spin. Here the drawn body turns toward that way
     * at no more than {@link WalkFeel#drawnTurnDegrees} a tick. Movement goes
     * by the server's facing and is untouched; standing villagers, clips other
     * than the walk, seats and rides keep vanilla's rule. {@code /walkfeel turn}.
     */
    private void easeDrawnBodyTurn(float before) {
        float most = WalkFeel.drawnTurnDegrees;
        if (most <= 0.0F || most >= 180.0F) return;
        // The walk loop plays from movement, under IDLE as often as WALK: the
        // server sets WALK only for a bard playing on the move. Any other state
        // is a clip of its own (an aim, a swing, a gesture) that keeps its facing,
        // and so does a raised shield.
        VillagerAnimationState anim = getAnimation();
        if ((anim != VillagerAnimationState.IDLE && anim != VillagerAnimationState.WALK)
                || showsShieldUp() || isPassenger() || isChairSeated()
                || getMocapClip() > 0 || isProfessionAnimationPreview()) {
            return;
        }
        double dx = getX() - xo;
        double dz = getZ() - zo;
        if (dx * dx + dz * dz <= 2.5E-7) return;
        yBodyRot = Mth.approachDegrees(before, yBodyRot, most);
        yHeadRot = Mth.rotateIfNecessary(yHeadRot, yBodyRot, getMaxHeadYRot());
    }

    /** Playing the guard-post look-around clip. */
    public boolean isLookingAround() {
        return getAnimation() == VillagerAnimationState.LOOK_AROUND;
    }

    /** Prevent natural despawn — NPCs are managed by VillagerModel, never auto-removed. */
    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    /** NPCs don't push each other — prevents mutual nav-blocking in narrow corridors. */
    @Override
    public boolean isPushable() {
        return false;
    }

    // -------------------------------------------------------------------------
    // Inventory
    // -------------------------------------------------------------------------

    public InventoryManager getInventory() {
        inventory.setOwner(villageLink == null ? null
                : new com.alex.test.village.tokens.VillageTokenBinding(
                        villageLink.villageId(), level().dimension().location().toString()));
        return inventory;
    }

    public CompoundTag getBrainData() { return brainData; }

    public NpcUiInteractionHandler uiInteraction() { return uiInteraction; }

    public void markUiOpened(ServerPlayer player) {
        uiInteraction.markOpened(this, player);
    }

    public void clearUiInteractionLock() {
        uiInteraction.clear();
    }

    public record PendingProfessionChange(UUID playerId, InteractionHand hand, Profession target) {}

    public void beginProfessionChange(ServerPlayer player, InteractionHand hand, Profession target) {
        pendingProfessionChange = new PendingProfessionChange(player.getUUID(), hand, target);
    }

    @Nullable
    public PendingProfessionChange pendingProfessionChange(ServerPlayer player) {
        return pendingProfessionChange != null
                && pendingProfessionChange.playerId().equals(player.getUUID())
                ? pendingProfessionChange : null;
    }

    public boolean hasPendingProfessionChange() {
        if (pendingProfessionChange == null) return false;
        if (level() instanceof ServerLevel serverLevel) {
            ServerPlayer player = serverLevel.getServer().getPlayerList()
                    .getPlayer(pendingProfessionChange.playerId());
            if (player == null || !(player.containerMenu instanceof VillagerMenu menu)
                    || !menu.isForNpc(this)) {
                pendingProfessionChange = null;
            }
        }
        return pendingProfessionChange != null;
    }

    public void cancelProfessionChange(ServerPlayer player) {
        if (pendingProfessionChange(player) != null) pendingProfessionChange = null;
    }

    public Profession getProfession() { return Profession.fromNetworkId(this.entityData.get(PROFESSION_DATA)); }
    public void setProfession(Profession p) {
        Profession previous = getProfession();
        this.entityData.set(PROFESSION_DATA, p.ordinal());
        if (!level().isClientSide) getInventory();
        if (level() instanceof ServerLevel serverLevel) {
            if (p == Profession.LUMBERJACK && this.entityData.get(LUMBERJACK_SKIN) < 0) {
                this.entityData.set(LUMBERJACK_SKIN, LumberjackSkinSequence.nextSkin(serverLevel));
            } else if (previous == Profession.LUMBERJACK && p != Profession.LUMBERJACK) {
                this.entityData.set(LUMBERJACK_SKIN, -1);
            }
            if (p == Profession.FARMER && this.entityData.get(FARMER_SKIN) < 0) {
                this.entityData.set(FARMER_SKIN, FarmerSkinSequence.nextSkin(serverLevel));
            } else if (previous == Profession.FARMER && p != Profession.FARMER) {
                this.entityData.set(FARMER_SKIN, -1);
            }
            if (p == Profession.BARD && this.entityData.get(BARD_SKIN) < 0) {
                this.entityData.set(BARD_SKIN, BardSkinSequence.nextSkin(serverLevel));
            } else if (previous == Profession.BARD && p != Profession.BARD) {
                this.entityData.set(BARD_SKIN, -1);
            }
            if (p == Profession.MINER && this.entityData.get(MINER_SKIN) < 0) {
                this.entityData.set(MINER_SKIN, MinerSkinSequence.nextSkin(serverLevel));
            } else if (previous == Profession.MINER && p != Profession.MINER) {
                this.entityData.set(MINER_SKIN, -1);
            }
            // A new job starts clean: dirt only shows on skins that have a dirty
            // twin, and an ex-farmer would otherwise carry it unseen forever.
            if (previous != p) setDirt(0f);
        }
        if (!level().isClientSide && professionSkillsInitialized) {
            ensureAssignedProfessionSkill();
        }
    }

    public int getLumberjackSkin() { return this.entityData.get(LUMBERJACK_SKIN); }

    public int getFarmerSkin() { return this.entityData.get(FARMER_SKIN); }

    public int getBardSkin() { return this.entityData.get(BARD_SKIN); }

    public int getMinerSkin() { return this.entityData.get(MINER_SKIN); }

    /**
     * A villager made on this computer and never put in a world (the Mayor's Theme tab preview): wears its job's
     * stored skin {@code skin} (lumberjack, farmer, bard, miner), as the server would sync it. Not on the server.
     */
    public void setPreviewSkin(int skin) {
        if (!level().isClientSide) return;
        switch (getProfession()) {
            case LUMBERJACK -> this.entityData.set(LUMBERJACK_SKIN, skin);
            case FARMER -> this.entityData.set(FARMER_SKIN, skin);
            case BARD -> this.entityData.set(BARD_SKIN, skin);
            case MINER -> this.entityData.set(MINER_SKIN, skin);
            default -> { }
        }
    }

    /** A preview villager's animation clock (see {@link #getTick}); NaN for every villager in a world. */
    private double previewClock = Double.NaN;

    /**
     * A preview villager drawn in a window (the Theme tab): vanilla's window drawing always passes a whole tick, so
     * GeckoLib's clip would move only 20 times a second. The preview sets its clock with the frame's part of a tick.
     */
    public void setPreviewClock(double ticks) {
        previewClock = ticks;
    }

    /** GeckoLib's clock: the age in ticks, as its own default, or a preview villager's own clock. */
    @Override
    public double getTick(Object entity) {
        if (entity == this && !Double.isNaN(previewClock)) return previewClock;
        return ((net.minecraft.world.entity.Entity) entity).tickCount;
    }

    /** Server-side dirt, 0 to {@link DirtLevel#MAX}. Always 0 on the client; use {@link #getDirtStage}. */
    public float getDirt() { return dirt; }

    /** The synced {@link DirtLevel} stage, 0 (clean) to 4 (fully dirty). */
    public int getDirtStage() { return this.entityData.get(DIRT_STAGE); }

    /** Sets dirt, clamped to 0-100, and syncs the stage if it changed. Server only. */
    public void setDirt(float value) {
        if (level().isClientSide) return;
        dirt = DirtLevel.clamp(value);
        byte stage = (byte) DirtLevel.stageFor(dirt);
        if (stage != this.entityData.get(DIRT_STAGE)) this.entityData.set(DIRT_STAGE, stage);
    }

    public void addDirt(float amount) { setDirt(dirt + amount); }

    public FarmerDirt farmerDirt() { return farmerDirt; }

    /**
     * The face a farmer saved before skins were stored was already wearing:
     * the old renderer picked between the two female skins from the UUID hash.
     */
    private int legacyFarmerSkin() {
        return Math.floorMod(getUUID().hashCode(), 2);
    }

    public List<ProfessionSkill> getProfessionSkills() {
        ensureProfessionSkillsInitialized();
        return professionSkills;
    }

    /** Keep the level displayed in the profession slot in step with work XP. */
    public void setProfessionSkillLevel(Profession profession, int level) {
        if (level().isClientSide || this instanceof MayorEntity) return;
        ensureProfessionSkillsInitialized();
        // Called every sync for every villager; copy only when a level changes.
        for (int i = 0; i < professionSkills.size(); i++) {
            ProfessionSkill slot = professionSkills.get(i);
            if (slot.profession() != profession) continue;
            if (slot.level() != level) {
                List<ProfessionSkill> updated = new ArrayList<>(professionSkills);
                updated.set(i, new ProfessionSkill(profession, level));
                professionSkills = List.copyOf(updated);
            }
            return;
        }
    }

    /** Preserve the exact saved levels when a first-time assignment adds a starter kit. */
    public void restoreProfessionSkills(List<ProfessionSkill> skills) {
        professionSkills = List.copyOf(skills);
        professionSkillsInitialized = true;
    }

    /**
     * Carries a nomad's rolled skills into its newly created resident entity. A matching
     * assigned profession keeps that roll; an unrolled one is deliberately started at one.
     */
    public void inheritNomadProfessionSkills(List<ProfessionSkill> skills) {
        if (level().isClientSide || this instanceof MayorEntity) return;
        professionSkills = List.copyOf(skills);
        professionSkillsInitialized = true;
        pendingAssignedProfessionLevel = ProfessionSkills.MIN_LEVEL;
    }

    /** Safe migration point for legacy entities and the one-time initializer for new entities. */
    public void ensureProfessionSkillsInitialized() {
        if (level().isClientSide || this instanceof MayorEntity) return;
        if (!professionSkillsInitialized) {
            professionSkills = ProfessionSkills.rollInitial(
                    skillRandom(), VillagerJobManager.eligibleProfessions(),
                    loadedFromSave ? getProfession() : Profession.NONE);
            professionSkillsInitialized = true;
            if (!loadedFromSave) rollStartingIntelligence();
        }
        ensureAssignedProfessionSkill();
        ensureGivenName();
    }

    // -------------------------------------------------------------------------
    // Names
    // -------------------------------------------------------------------------

    /** Brain-data key of the villager's own name. */
    private static final String TAG_GIVEN_NAME = "GivenName";
    /** Set when a player chose the name (a name tag): it is kept as typed, one word or not. */
    private static final String TAG_NAME_CHOSEN = "GivenNameChosen";
    /** A name tag's name the villager arrived with (a renamed vanilla villager), used if free. */
    private static final String TAG_SUGGESTED_NAME = "SuggestedName";

    /** Whether the name has been checked against the world's name book since this entity loaded. */
    private boolean nameChecked;
    private static final org.slf4j.Logger NAMES_LOGGER = com.mojang.logging.LogUtils.getLogger();

    /**
     * Gives the villager (or Mayor) a first and last name of its own the first time it
     * is set up, and checks it once each time it loads (VillagerNameBook):
     * <ul>
     * <li>a one-word name from an older save keeps its first name and gets a last name;</li>
     * <li>a name another villager already holds (two villagers saved with one name before
     *     names were unique) is swapped for a new one, so the one loaded later is renamed.</li>
     * </ul>
     * Residents, villagers waiting to be taken in, and Mayors get names; travellers and
     * previews do not.
     */
    protected void ensureGivenName() {
        if (nameChecked || !(level() instanceof ServerLevel sl)) return;
        String name = givenName();
        if (name == null && !isVillageMember() && !isWaitingSettler() && !(this instanceof MayorEntity)) return;
        nameChecked = true;
        VillagerNameBook book = VillagerNameBook.get(sl.getServer());
        IntUnaryOperator random = getRandom()::nextInt;
        String old = name;
        if (name == null) {
            String suggested = brainData.getString(TAG_SUGGESTED_NAME).trim();
            if (!suggested.isEmpty() && book.isFreeFor(suggested, npcId)) {
                name = suggested;
                brainData.putBoolean(TAG_NAME_CHOSEN, true);
            }
        } else if (!brainData.getBoolean(TAG_NAME_CHOSEN) && !VillagerNames.hasLastName(name)) {
            name = book.withLastName(name, random);
        }
        if (name != null && !book.claim(name, npcId)) {
            NAMES_LOGGER.info("[Names] {} shares its name with another villager; renaming it", name);
            name = null;
            brainData.remove(TAG_NAME_CHOSEN);
        }
        if (name == null) {
            name = book.newName(random);
            book.claim(name, npcId);
        }
        brainData.remove(TAG_SUGGESTED_NAME);
        if (!name.equals(old)) {
            if (old != null) NAMES_LOGGER.info("[Names] {} is now called {}", old, name);
            brainData.putString(TAG_GIVEN_NAME, name);
        }
        showGivenName();
    }

    /**
     * A name for a villager about to be set up, from a vanilla name tag it already had.
     * Used only if no other villager holds it; otherwise it gets a name of its own.
     */
    public void suggestName(@Nullable Component name) {
        if (name == null || givenName() != null) return;
        String text = name.getString().trim();
        if (!text.isEmpty()) brainData.putString(TAG_SUGGESTED_NAME, text);
    }

    /**
     * A name tag used on a villager or the Mayor renames it, if no other villager has the
     * name. True when the click was handled (renamed or refused), false to carry on as
     * usual (not a named name tag, or a traveller, which vanilla name tags as any mob).
     */
    public boolean tryNameTag(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        Component tagName = held.get(DataComponents.CUSTOM_NAME);
        if (!held.is(Items.NAME_TAG) || tagName == null || !(level() instanceof ServerLevel sl)) return false;
        // Village claims round 7c: renaming a resident takes Friend or better in its village (Visitors only look).
        VillageManager claimed = player instanceof ServerPlayer ? resolveVillageManager(sl) : null;
        if (claimed != null && !com.alex.test.village.claims.ClaimLock.mayIn((ServerPlayer) player, claimed,
                com.alex.test.village.claims.ClaimLock.Act.USE)) {
            player.displayClientMessage(com.alex.test.Tr.c("chat.dynamicvillages.claim.rename_locked",
                    "Only people %s trusts may rename villagers here.", claimed.claim().ownerName()), true);
            return true;
        }
        ensureGivenName();
        String old = givenName();
        if (old == null) return false;
        String wanted = tagName.getString().trim().replaceAll("\\s+", " ");
        if (wanted.isEmpty()) return false;
        VillagerNameBook book = VillagerNameBook.get(sl.getServer());
        if (wanted.equals(old)) {
            player.displayClientMessage(Component.literal("Already called " + wanted), true);
            return true;
        }
        if (!book.isFreeFor(wanted, npcId)) {
            player.displayClientMessage(Component.literal("Another villager is already called " + wanted), true);
            return true;
        }
        book.release(old, npcId);
        book.claim(wanted, npcId);
        brainData.putString(TAG_GIVEN_NAME, wanted);
        brainData.putBoolean(TAG_NAME_CHOSEN, true);
        showGivenName();
        if (!player.getAbilities().instabuild) held.shrink(1);
        player.displayClientMessage(Component.literal(old + " is now called " + wanted), true);
        return true;
    }

    /** A villager killed or removed for good gives its name back to the world. */
    @Override
    public void remove(RemovalReason reason) {
        if (reason.shouldDestroy() && level() instanceof ServerLevel sl && givenName() != null) {
            VillagerNameBook.get(sl.getServer()).release(givenName(), npcId);
        }
        super.remove(reason);
    }

    /** The villager's own name, or null for one without (travellers, previews, the client side). */
    @Nullable
    public String givenName() {
        String name = brainData.getString(TAG_GIVEN_NAME);
        return name.isEmpty() ? null : name;
    }

    /** What is written over the head and in chat for a villager called {@code name}; the Mayor adds its title. */
    protected String nameWithTitle(String name) {
        return name;
    }

    /**
     * Notes what the villager is doing ("work"). Players never see it unless they
     * turn on {@code /villagerstatus}, which draws "Bram · work" like the old
     * overhead labels. The custom name holds the name alone, shown only to a
     * player looking at the villager (NpcGeoRenderer.shouldShowName).
     */
    public void setActivityLabel(String label) {
        String status = label == null ? "" : label;
        if (!status.equals(entityData.get(WORK_STATUS))) entityData.set(WORK_STATUS, status);
        showGivenName();
    }

    /** What the villager is doing, as last set by {@link #setActivityLabel}; "" when nothing yet. */
    public String workStatus() {
        return entityData.get(WORK_STATUS);
    }

    /** Puts the villager's own name (with the Mayor's title), and nothing else, in its custom name. */
    protected void showGivenName() {
        String given = givenName();
        if (given == null) return;
        String name = nameWithTitle(given);
        Component current = getCustomName();
        if (current == null || !name.equals(current.getString())) setCustomName(Component.literal(name));
    }

    /**
     * Brand-new villagers and nomads roll intelligence alongside their first skills.
     * Children stay at 0 and earn it at school; entities from a save are never rolled.
     * Written to brain data, which {@code VillagerBrain.loadFrom} reads only after
     * ensuring skills, so the brain never saves over it.
     */
    private void rollStartingIntelligence() {
        if (!rollsStartingIntelligence() || getProfession() == Profession.CHILD
                || brainData.contains(VillagerSkills.TAG_INTELLIGENCE)) return;
        brainData.putFloat(VillagerSkills.TAG_INTELLIGENCE,
                ProfessionSkills.rollIntelligence(skillRandom()));
    }

    /** Whether a brand-new entity of this kind rolls starting intelligence. */
    protected boolean rollsStartingIntelligence() {
        return true;
    }

    /** Starting intelligence stored on this entity, for carrying a nomad's roll to its resident. */
    public float storedIntelligence() {
        return brainData.getFloat(VillagerSkills.TAG_INTELLIGENCE);
    }

    /** Carries a recruited nomad's intelligence onto the resident created for it. */
    public void inheritNomadIntelligence(float intelligence) {
        if (level().isClientSide || this instanceof MayorEntity) return;
        brainData.putFloat(VillagerSkills.TAG_INTELLIGENCE, intelligence);
    }

    private static final String TAG_PERSONALITY = "Personality";

    /**
     * This villager's personality. Rolled from its id the first time and saved in
     * brain data, so a later change to the roll never changes a villager players
     * have already met, and the badges on its screen stay the same.
     */
    public com.alex.test.village.brain.Personality personality() {
        if (brainData.contains(TAG_PERSONALITY, Tag.TAG_COMPOUND)) {
            CompoundTag saved = brainData.getCompound(TAG_PERSONALITY);
            return new com.alex.test.village.brain.Personality(
                    saved.getFloat("WorkDrive"), saved.getFloat("Sociability"),
                    saved.getFloat("Appetite"), saved.getFloat("WorkSpeed"),
                    saved.getFloat("WorkFatigue"), saved.getFloat("WorkFraction"));
        }
        com.alex.test.village.brain.Personality rolled = new com.alex.test.village.brain.Personality(npcId);
        if (!level().isClientSide) savePersonality(rolled);
        return rolled;
    }

    private void savePersonality(com.alex.test.village.brain.Personality p) {
        CompoundTag saved = new CompoundTag();
        saved.putFloat("WorkDrive", p.workDrive);
        saved.putFloat("Sociability", p.sociability);
        saved.putFloat("Appetite", p.appetite);
        saved.putFloat("WorkSpeed", p.workSpeed);
        saved.putFloat("WorkFatigue", p.workFatigue);
        saved.putFloat("WorkFraction", p.workFraction);
        brainData.put(TAG_PERSONALITY, saved);
    }

    /** Carries a recruited nomad's personality onto the resident created for it. */
    public void inheritNomadPersonality(NpcEntity nomad) {
        if (level().isClientSide || this instanceof MayorEntity) return;
        savePersonality(nomad.personality());
    }

    private void ensureAssignedProfessionSkill() {
        if (pendingAssignedProfessionLevel != null) {
            professionSkills = ProfessionSkills.appendAssignedProfession(
                    professionSkills, getProfession(), pendingAssignedProfessionLevel);
            pendingAssignedProfessionLevel = null;
        } else {
            professionSkills = ProfessionSkills.appendAssignedProfession(
                    professionSkills, getProfession(), skillRandom());
        }
        applyFavouriteJobHeadStart();
    }

    /**
     * A grown child's favourite job (the one it was taught most at school)
     * starts a few levels ahead, 5 or more by its best teacher. The levels go
     * onto that job's skill as soon as it grows up, so its screen shows them
     * before anybody picks it a job; a villager without the skill yet gets it.
     * Once only. The favourite itself is kept for good: the screen marks it.
     * With all four skill slots taken by other jobs it waits until the
     * villager is given its favourite job.
     */
    private void applyFavouriteJobHeadStart() {
        if (getProfession() == Profession.CHILD) return;
        Profession job = com.alex.test.village.villagers.teacher.PupilRecord.favouriteJob(brainData);
        if (job == null || brainData.getBoolean(
                com.alex.test.village.villagers.teacher.PupilRecord.TAG_HEAD_START_APPLIED)) return;
        int bonus = Math.max(0, brainData.getInt(com.alex.test.village.villagers.teacher.PupilRecord.TAG_HEAD_START));
        List<ProfessionSkill> skills = ProfessionSkills.appendAssignedProfession(professionSkills, job, skillRandom());
        for (int i = 0; i < skills.size(); i++) {
            ProfessionSkill slot = skills.get(i);
            if (slot.profession() != job) continue;
            List<ProfessionSkill> updated = new ArrayList<>(skills);
            updated.set(i, new ProfessionSkill(job, Math.min(ProfessionSkills.MAX_LEVEL, slot.level() + bonus)));
            professionSkills = List.copyOf(updated);
            brainData.putBoolean(com.alex.test.village.villagers.teacher.PupilRecord.TAG_HEAD_START_APPLIED, true);
            return;
        }
    }

    /** This grown-up's favourite job from school, or null; for the heart in its screen. */
    @Nullable
    public Profession schoolFavouriteJob() {
        return com.alex.test.village.villagers.teacher.PupilRecord.favouriteJob(brainData);
    }

    /** Levels school put this grown-up ahead in its favourite job. */
    public int schoolHeadStart() {
        return brainData.getInt(com.alex.test.village.villagers.teacher.PupilRecord.TAG_HEAD_START);
    }

    /** The head start has not reached the favourite job's skill yet (all four slots were taken). */
    public boolean schoolHeadStartPending() {
        return schoolFavouriteJob() != null && !brainData.getBoolean(
                com.alex.test.village.villagers.teacher.PupilRecord.TAG_HEAD_START_APPLIED);
    }

    private ProfessionSkills.RandomValues skillRandom() {
        return new ProfessionSkills.RandomValues() {
            @Override public double nextDouble() { return getRandom().nextDouble(); }
            @Override public int nextInt(int bound) { return getRandom().nextInt(bound); }
        };
    }

    /** Village members (not the Mayor) show their inventory read-only and may have settings. */
    public boolean canPlayerManage(Player player) {
        return isVillageMember() && !(this instanceof MayorEntity);
    }

    /**
     * May this player change this villager's settings (its settings window, the Builder's desk, plans and orders)?
     * Village claims round 7c: in an owned village only the owner and their Officers may.
     */
    public boolean canPlayerAccessSettings(Player player) {
        return hasSettings(player) && settingsClaimOwner(player).isEmpty();
    }

    private boolean hasSettings(Player player) {
        if (!canPlayerManage(player)) return false;
        Profession profession = getProfession();
        return profession == Profession.LUMBERJACK
                || profession.isGuardLike()
                || profession == Profession.FARMER
                || SettingsSchemaRegistry.forProfession(profession).isPresent();
    }

    /**
     * Village claims round 7c, server: the owner's name when this villager has settings but the village's ranks keep
     * this player from changing them (the villager window greys its button); "" otherwise.
     */
    public String settingsClaimOwner(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(level() instanceof ServerLevel level)
                || !hasSettings(player)) return "";
        VillageManager village = resolveVillageManager(level);
        return village == null || com.alex.test.village.claims.ClaimLock.mayIn(serverPlayer, village,
                com.alex.test.village.claims.ClaimLock.Act.MANAGE) ? "" : village.claim().ownerName();
    }

    @Nullable
    public VillagerVillageLink getVillageLink() { return villageLink; }
    public void setVillageLink(@Nullable VillagerVillageLink link) { villageLink = link; }

    // -------------------------------------------------------------------------
    // Synced animation state
    // -------------------------------------------------------------------------

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ANIM_STATE, VillagerAnimationState.IDLE.getId());
        builder.define(WORK_ANIMATION_SPEED, 1f);
        builder.define(WORK_ANIMATION_SEQUENCE, 0);
        builder.define(EAT_ANIMATION_SEQUENCE, 0);
        builder.define(ANIM_PREVIEW, false);
        builder.define(SAD, false);
        builder.define(PROFESSION_ANIMATION_PREVIEW, false);
        builder.define(SHIELD_RAISED, false);
        builder.define(SHIELD_PRACTICE, false);
        builder.define(SHIELD_HIGH, false);
        builder.define(COMBAT_READY, false);
        builder.define(PROFESSION_DATA, Profession.NONE.ordinal());
        builder.define(LUMBERJACK_SKIN, -1);
        builder.define(FARMER_SKIN, -1);
        builder.define(BARD_SKIN, -1);
        builder.define(MINER_SKIN, -1);
        builder.define(DIRT_STAGE, (byte) 0);
        builder.define(MOCAP_CLIP, 0);
        builder.define(BUBBLE, 0);
        builder.define(BUBBLE_ITEM, ItemStack.EMPTY);
        builder.define(COOK_PROP, ItemStack.EMPTY);
        builder.define(HOLDING_ORB, false);
        builder.define(ENCHANTING, false);
        builder.define(ENCHANTING_ROOM_MIN, BlockPos.ZERO);
        builder.define(ENCHANTING_ROOM_MAX, BlockPos.ZERO);
        builder.define(DRUID_CAST, (byte) 0);
        builder.define(HURRY_GAIT, (byte) 0);
        builder.define(RAW_LIMBS, false);
        builder.define(WORK_STATUS, "");
    }

    /** Whether this villager is drawn with rigid, unbent limbs (a debug view; see RAW_LIMBS). */
    public boolean hasRawLimbs() {
        return this.entityData.get(RAW_LIMBS);
    }

    public void setRawLimbs(boolean raw) {
        if (hasRawLimbs() != raw) this.entityData.set(RAW_LIMBS, raw);
    }

    /** Whether a healing orb sits on this cleric's palm (drawn by HeldItemGeoLayer). */
    public boolean isHoldingOrb() {
        return this.entityData.get(HOLDING_ORB);
    }

    public void setHoldingOrb(boolean holding) {
        if (isHoldingOrb() != holding) this.entityData.set(HOLDING_ORB, holding);
    }

    /**
     * This client's trance phase for this enchanter ({@code EnchanterTrance}
     * NORMAL..RELEASE). Set by the client's trance tracker for its own player
     * only, never synced: a far player's game keeps it at NORMAL.
     */
    private int trancePhase;

    public int getTrancePhase() {
        return trancePhase;
    }

    public void setTrancePhase(int phase) {
        trancePhase = phase;
    }

    /** The trance clip over the enchanter's writing clips, while this client's player is in it. */
    @Nullable
    private RawAnimation tranceClip() {
        if (trancePhase == com.alex.test.village.villagers.enchanter.EnchanterTrance.NORMAL) return null;
        VillagerAnimationState writing = getAnimation();
        if (writing != VillagerAnimationState.ENCHANT_WEAVE && writing != VillagerAnimationState.ENCHANT_PONDER
                && writing != VillagerAnimationState.ENCHANT_FINISH) return null;
        return switch (trancePhase) {
            case com.alex.test.village.villagers.enchanter.EnchanterTrance.SEIZE -> ANIM_ENCHANT_SEIZE;
            case com.alex.test.village.villagers.enchanter.EnchanterTrance.TRANCE -> ANIM_ENCHANT_TRANCE;
            default -> ANIM_ENCHANT_RELEASE;
        };
    }

    /** Whether this enchanter is writing a book at its table right now. */
    public boolean isEnchanting() {
        return this.entityData.get(ENCHANTING);
    }

    public void setEnchanting(boolean enchanting) {
        if (isEnchanting() != enchanting) this.entityData.set(ENCHANTING, enchanting);
    }

    /** What a druid's magic is working on, for its hum. */
    public enum DruidCast { NONE, TREE, CROPS, MINE }

    /** What this druid is dancing on; NONE once the tree's or ripe crop's finish sound has started. */
    public DruidCast getDruidCast() {
        DruidCast[] casts = DruidCast.values();
        int index = this.entityData.get(DRUID_CAST);
        return index >= 0 && index < casts.length ? casts[index] : DruidCast.NONE;
    }

    public void setDruidCast(DruidCast cast) {
        if (getDruidCast() != cast) this.entityData.set(DRUID_CAST, (byte) cast.ordinal());
    }

    /** The room the enchanter writes in, as a block box; null to forget it. */
    public void setEnchantingRoom(@Nullable AABB room) {
        BlockPos min = room == null ? BlockPos.ZERO : BlockPos.containing(room.minX, room.minY, room.minZ);
        BlockPos max = room == null ? BlockPos.ZERO : BlockPos.containing(room.maxX, room.maxY, room.maxZ);
        if (!min.equals(this.entityData.get(ENCHANTING_ROOM_MIN))) this.entityData.set(ENCHANTING_ROOM_MIN, min);
        if (!max.equals(this.entityData.get(ENCHANTING_ROOM_MAX))) this.entityData.set(ENCHANTING_ROOM_MAX, max);
    }

    /**
     * Whether {@code pos} stands in the room this enchanter writes in. Null
     * when the room is not known, so callers can treat that as "no walls".
     */
    @Nullable
    public Boolean isInEnchantingRoom(Vec3 pos) {
        BlockPos min = this.entityData.get(ENCHANTING_ROOM_MIN);
        BlockPos max = this.entityData.get(ENCHANTING_ROOM_MAX);
        if (min.equals(max)) return null;
        return pos.x >= min.getX() && pos.x <= max.getX()
                && pos.y >= min.getY() && pos.y <= max.getY()
                && pos.z >= min.getZ() && pos.z <= max.getZ();
    }

    /**
     * Client: where the palm orb was last drawn, in world space, and on which
     * tick. Written by HeldItemGeoLayer, which is the only place that knows
     * where the hand really is; read by {@link #tickHandOrbSparkles}.
     */
    @Nullable private Vec3 handOrbAt;
    private int handOrbAtTick;

    /** Client: the palm orb was just drawn at {@code at}. */
    public void noteHandOrbDrawn(Vec3 at) {
        handOrbAt = at;
        handOrbAtTick = tickCount;
    }

    private static final org.joml.Vector3f HAND_ORB_GREEN = new org.joml.Vector3f(0.45f, 1.0f, 0.4f);

    /** Client: green sparkles around the orb on the cleric's palm while it is held. */
    private void tickHandOrbSparkles() {
        if (!isHoldingOrb() || handOrbAt == null || tickCount - handOrbAtTick > 2) return;
        net.minecraft.util.RandomSource r = getRandom();
        level().addParticle(new net.minecraft.core.particles.DustParticleOptions(HAND_ORB_GREEN, 0.7f),
                handOrbAt.x + (r.nextDouble() - 0.5) * 0.3, handOrbAt.y + (r.nextDouble() - 0.5) * 0.3,
                handOrbAt.z + (r.nextDouble() - 0.5) * 0.3, 0, 0.02, 0);
        if (r.nextInt(3) == 0) {
            level().addParticle(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    handOrbAt.x + (r.nextDouble() - 0.5) * 0.25, handOrbAt.y + 0.1,
                    handOrbAt.z + (r.nextDouble() - 0.5) * 0.25, 0, 0, 0);
        }
    }

    // -------------------------------------------------------------------------
    // Hurrying: run, sword run, injured run, jog
    // -------------------------------------------------------------------------

    /**
     * Ticks a hurry lasts once the job stops asking for it. Jobs ask every tick
     * they want it, so a job that ends, is interrupted or forgets cannot leave a
     * villager running on its next stroll.
     */
    private static final int HURRY_LEASE_TICKS = 10;
    /** Server: ticks left of the current hurry. */
    private int hurryLease;
    /** Client: blocks a tick this villager has been covering, smoothed; see {@link #clientGroundSpeed}. */
    private double clientGroundSpeed;
    /**
     * Each tick the smoothed pace moves this share of the way to the new one:
     * quickly down, so the legs stop with a body that has just run into a block
     * face, more gently up, so the first steps do not lurch.
     */
    private static final double GROUND_SPEED_RISE = 0.3;
    private static final double GROUND_SPEED_FALL = 0.6;
    /** Client: the hurry loop the walk controller is playing, NONE when it is not. */
    private HurryGait playingHurry = HurryGait.NONE;
    /** Client: the walk is playing at the pace the villager goes (behind a raised shield), not at 1. */
    private boolean playingPacedWalk;
    /** Client: walk_controller, for where its loop is during a hop. */
    @Nullable private PhasedLoopController<NpcEntity> walkController;
    /** Client: the hurry loop's playback speed, set each tick (eased on the ground). */
    private double hurryRate = 1.0;
    private static final double HURRY_RATE_RISE = 0.35;
    private static final double HURRY_RATE_FALL = 0.6;
    /** Client: a hop is on -- off the ground on a jump, or long enough to be more than a step down. */
    private boolean hopping;
    /** Client: ticks into the hop. */
    private int hopTicks;
    /** Client: height the feet left the ground at; NaN while on it. */
    private double takeoffY = Double.NaN;
    /** Client: this hop has reached its widest stride and is holding it. */
    private boolean leapHeld;
    /**
     * Client: this hop is past its top, and the legs run again for the landing.
     * The leap used to be held to the ground and three ticks past it, and with
     * the pace now kept through the air (RunningHop, getFlyingSpeed) the body
     * came down and ran on with its legs still split: a glide.
     */
    private boolean leapReleased;
    /** How fast the legs pick the run back up after the leap: most of the way in two ticks. */
    private static final double LEAP_RELEASE_EASE = 0.6;
    /**
     * Rising faster than this (blocks a tick, as drawn) is a jump. Climbing
     * stairs never leaves the ground, so it cannot pass for one.
     */
    private static final double HOP_RISE = 0.04;
    /**
     * Fallen this far below the take-off is a drop, not a step down. Running down
     * stairs is nearly all falling -- three or four ticks off the ground for each
     * half-block step -- and must not freeze the stride on every one.
     */
    private static final double HOP_DROP = 0.8;

    /** How this villager is hurrying, NONE when it is not. Both sides. */
    public HurryGait hurryGait() {
        return HurryGait.byId(this.entityData.get(HURRY_GAIT));
    }

    /** Whether this villager is hurrying: it plays a run instead of the walk while it moves. */
    public boolean isRunning() {
        return hurryGait() != HurryGait.NONE;
    }

    /**
     * Server: hurry this way for the next half second. Called every tick the job
     * wants it; it lapses by itself after {@link #HURRY_LEASE_TICKS} without a call.
     */
    public void hurry(HurryGait gait) {
        hurryLease = gait == HurryGait.NONE ? 0 : HURRY_LEASE_TICKS;
        byte id = (byte) gait.ordinal();
        if (this.entityData.get(HURRY_GAIT) != id) this.entityData.set(HURRY_GAIT, id);
    }

    /** Server: back to walking at once. */
    public void stopHurry() {
        hurry(HurryGait.NONE);
    }

    /** The cleric's switch: the plain run, or back to walking. */
    public void setRunning(boolean running) {
        if (running) hurry(HurryGait.RUN);
        else stopHurry();
    }

    private void tickHurryLease() {
        if (hurryLease > 0 && --hurryLease == 0) {
            this.entityData.set(HURRY_GAIT, (byte) 0);
        }
    }

    /** Client: how fast this villager is visibly going over the ground, in blocks a second. */
    public double clientGroundSpeed() {
        return clientGroundSpeed * 20.0;
    }

    /**
     * Client, every tick: the smoothed pace, the hop, and the hurry loop's
     * playback speed.
     *
     * <p>A running villager used to run into a block rather than jump it: its
     * route jumped only on contact, the bump stopped it dead, and a mob gets
     * almost no push in the air, so it rose nearly straight up and crept over the
     * edge for about half a second while the legs went on running (at the old 0.7
     * floor), sometimes dropping to the standing pose mid-hop. RunningHop and
     * getFlyingSpeed now carry it over at speed; this is the look of it. The legs
     * slow with the body; on the way up the stride is carried to its widest
     * split and held there, a leap ({@link HurryGait#leapDistance}); over the
     * top the legs pick the run back up, so they are running when the feet land.
     */
    private void tickClientHurryPace() {
        double dx = getX() - xo;
        double dz = getZ() - zo;
        double now = Math.sqrt(dx * dx + dz * dz);
        clientGroundSpeed += (now - clientGroundSpeed)
                * (now < clientGroundSpeed ? GROUND_SPEED_FALL : GROUND_SPEED_RISE);

        boolean off = !onGround() && !isInWaterOrBubble() && !isPassenger() && !onClimbable();
        if (off) {
            if (Double.isNaN(takeoffY)) takeoffY = yo;
            boolean rising = getY() - yo > HOP_RISE;
            if (!hopping && (rising || takeoffY - getY() > HOP_DROP)) hopping = true;
            if (hopping) {
                hopTicks++;
                // Over the top (or a drop, never rising): legs back to running for the landing.
                if (!rising) leapReleased = true;
            }
        } else {
            takeoffY = Double.NaN;
            hopping = false;
            hopTicks = 0;
            leapHeld = false;
            leapReleased = false;
        }

        HurryGait gait = playingHurry;
        if (gait == HurryGait.NONE) {
            // Not on screen yet: start from the pace it will need, not from 1.
            HurryGait next = shownHurryGait();
            hurryRate = next == HurryGait.NONE ? 1.0 : RunPace.rate(next, clientGroundSpeed());
            return;
        }
        if (hopping && !leapReleased) {
            // On the way up the stride is steered, not eased: a lag here is a lag in reaching the leap.
            hurryRate = airRate(gait);
            return;
        }
        double target = RunPace.rate(gait, clientGroundSpeed());
        double ease = target < hurryRate ? HURRY_RATE_FALL : (leapReleased ? LEAP_RELEASE_EASE : HURRY_RATE_RISE);
        hurryRate += (target - hurryRate) * ease;
    }

    /**
     * Mid-hop playback: carry the loop to its next widest stride, arriving by the
     * {@link RunPace#AIR_CATCH_TICKS}th tick in the air, then creep there.
     */
    private double airRate(HurryGait gait) {
        if (leapHeld || walkController == null) return RunPace.air();
        double loopTicks = walkController.loopTicks(gait.clip());
        double phase = walkController.loopPhase(gait.clip());
        if (Double.isNaN(phase)) return RunPace.air();
        double[] distance = gait.leapDistance(phase);
        if (distance[0] <= RunPace.LEAP_WINDOW || distance[1] <= RunPace.LEAP_WINDOW) {
            leapHeld = true;
            return RunPace.air();
        }
        double ticksLeft = Math.max(1.0, RunPace.AIR_CATCH_TICKS - (hopTicks - 1));
        return RunPace.catchUpRate(distance[0] * RunPace.AIR_CATCH_TICKS / ticksLeft, loopTicks);
    }

    /** Hopping with a hurry loop on: it plays on through the hop instead of dropping to the standing pose. */
    private boolean isHurryHop() {
        return hopping && playingHurry != HurryGait.NONE;
    }

    /** Client: in a hop, for {@code /runpace}. */
    public boolean isClientHopping() {
        return hopping;
    }

    /**
     * The hurry gait the walk controller shows: the synced one, or the plain run
     * on a rig that lacks its clip (so it never asks GeckoLib for a missing one).
     */
    private HurryGait shownHurryGait() {
        HurryGait gait = hurryGait();
        if (gait == HurryGait.NONE || gait == HurryGait.RUN) return gait;
        return hasClip(gait.clip()) ? gait : HurryGait.RUN;
    }

    /** The loop for {@link #shownHurryGait}, or null when not hurrying. */
    @Nullable
    private RawAnimation hurryClip() {
        return switch (shownHurryGait()) {
            case NONE -> null;
            case RUN -> ANIM_RUN;
            case SWORD_RUN -> ANIM_SWORD_RUN;
            case INJURED_RUN -> ANIM_INJURED_RUN;
            case JOG -> ANIM_JOG;
        };
    }

    /**
     * walk_controller's playback speed: a hurry loop keeps pace with the ground
     * ({@link RunPace}), the sad walk plays at its own fixed speed, the rest at 1.
     */
    private double walkControllerSpeed() {
        if (playingHurry != HurryGait.NONE) return hurryRate;
        if (playingPacedWalk) return RunPace.walkRate(clientGroundSpeed());
        return playingSadWalk ? (double) com.alex.test.village.stats.WellbeingSystem.SAD_WALK_CLIP_SPEED : 1.0;
    }

    /** Client: the hurry loop on screen and its playback speed, for {@code /runpace}. */
    public HurryGait playingHurryGait() {
        return playingHurry;
    }

    public double playingHurryRate() {
        return walkControllerSpeed();
    }

    // -------------------------------------------------------------------------
    // Thought / conversation bubble
    // -------------------------------------------------------------------------

    private static final int BUBBLE_ICON_MASK   = 0x7F;
    private static final int BUBBLE_SPEECH_BIT  = 0x80;
    private static final int BUBBLE_SEQ_SHIFT   = 8;
    private static final int BUBBLE_SEQ_MASK    = 0x7F;

    /** Server-side countdown until the current bubble is cleared. */
    private int bubbleTicks = 0;
    /** Server-side rolling counter; see {@link #BUBBLE}. */
    private int bubbleSequence = 0;

    /** Client-side: the icon currently drawn, kept through the fade-out. */
    @Nullable
    private ThoughtIcon renderedBubbleIcon;
    private boolean renderedBubbleSpeech;
    /** Client-side: the packed value the render state was last built from. */
    private int renderedBubblePacked = 0;
    /** Client-side: {@link #tickCount} when the bubble last appeared or vanished. */
    private int bubbleChangedAtTick = -1000;
    /** Client-side: whether the bubble is currently coming in or fading out. */
    private boolean bubbleShowing = false;

    /**
     * Server-side: float {@code icon} over this villager's head for
     * {@code ticks}, replacing whatever was there.
     */
    public void showBubble(ThoughtIcon icon, boolean speech, int ticks) {
        if (level().isClientSide || icon == null) return;
        if (!this.entityData.get(BUBBLE_ITEM).isEmpty()) this.entityData.set(BUBBLE_ITEM, ItemStack.EMPTY);
        bubbleSequence = (bubbleSequence + 1) & BUBBLE_SEQ_MASK;
        int packed = (icon.networkId() & BUBBLE_ICON_MASK)
                | (speech ? BUBBLE_SPEECH_BIT : 0)
                | (bubbleSequence << BUBBLE_SEQ_SHIFT);
        this.entityData.set(BUBBLE, packed);
        bubbleTicks = Math.max(1, ticks);
    }

    /**
     * Server-side: a speech bubble with {@code item} drawn in it, for
     * {@code ticks}: something being shown off, asked about or priced.
     */
    public void showItemBubble(ItemStack item, int ticks) {
        if (level().isClientSide || item.isEmpty()) return;
        showBubble(ThoughtIcon.EMOTE_TALKING, true, ticks);
        this.entityData.set(BUBBLE_ITEM, item.copyWithCount(1));
    }

    /** The item drawn in the bubble instead of its icon; empty for an ordinary bubble. */
    public ItemStack bubbleItem() { return this.entityData.get(BUBBLE_ITEM); }

    /** Server-side: take the bubble down now. */
    public void clearBubble() {
        if (level().isClientSide) return;
        if (!this.entityData.get(BUBBLE_ITEM).isEmpty()) this.entityData.set(BUBBLE_ITEM, ItemStack.EMPTY);
        bubbleTicks = 0;
        if ((this.entityData.get(BUBBLE) & BUBBLE_ICON_MASK) != 0) {
            this.entityData.set(BUBBLE, 0);
        }
    }

    /** True while a bubble is up on the server. */
    public boolean hasBubble() {
        return (this.entityData.get(BUBBLE) & BUBBLE_ICON_MASK) != 0;
    }

    /** Client-side: the icon to draw, which outlives the clear for the fade-out. */
    @Nullable
    public ThoughtIcon renderedBubbleIcon() { return renderedBubbleIcon; }

    public boolean renderedBubbleIsSpeech() { return renderedBubbleSpeech; }

    /** Client-side: whether the bubble is fading in ({@code true}) or out. */
    public boolean bubbleShowing() { return bubbleShowing; }

    /** Client-side: {@link #tickCount} at which the current fade started. */
    public int bubbleChangedAtTick() { return bubbleChangedAtTick; }

    /**
     * Client-side: notice a new packed value and start the matching fade. The
     * icon itself is deliberately kept when the field goes back to 0 so the
     * renderer has something to fade out.
     */
    private void tickBubbleRenderState() {
        int packed = this.entityData.get(BUBBLE);
        if (packed == renderedBubblePacked) return;

        renderedBubblePacked = packed;
        bubbleChangedAtTick  = tickCount;

        ThoughtIcon icon = ThoughtIcon.byNetworkId(packed & BUBBLE_ICON_MASK);
        if (icon != null) {
            renderedBubbleIcon   = icon;
            renderedBubbleSpeech = (packed & BUBBLE_SPEECH_BIT) != 0;
            bubbleShowing        = true;
        } else {
            bubbleShowing = false;
        }
    }

    /** Index into {@link #MOCAP_CLIPS}, or 0 when this NPC animates normally. */
    public int getMocapClip() {
        return this.entityData.get(MOCAP_CLIP);
    }

    /**
     * Server-side: put this NPC into mocap preview mode, looping one clip until set
     * back to 0. The preview controller runs last, so it overrides the task
     * animations for as long as it is on.
     */
    public void setMocapClip(int clip) {
        this.entityData.set(MOCAP_CLIP, clip < 0 || clip >= MOCAP_CLIPS.length ? 0 : clip);
        this.mocapTick = 0;
    }

    /**
     * Walks the NPC along the clip's root motion, so a step lands on the ground
     * instead of skating over it. See {@link MocapRootMotion} for why the travel
     * lives here rather than in the animation.
     */
    private void tickMocapRootMotion() {
        int clip = getMocapClip();
        if (clip <= 0 || clip >= MOCAP_CLIPS.length) {
            mocapTick = 0;
            return;
        }
        // A lineup is for comparing clips side by side, so its villagers stay on their
        // marks: root motion is right for a single preview, and would walk a row of
        // forty of them into each other.
        if (mocapHoldInPlace) return;

        Vec3 step = MocapRootMotion.step(MOCAP_CLIPS[clip], mocapTick++);
        if (step.lengthSqr() == 0)
            return;

        float yaw = getYRot() * ((float) Math.PI / 180f);
        Vec3 forward = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
        Vec3 right = new Vec3(-forward.z, 0, forward.x);

        move(net.minecraft.world.entity.MoverType.SELF,
             forward.scale(-step.z).add(right.scale(step.x)));
    }

    /** @return the clip index for a name from {@link #MOCAP_CLIPS}, or -1 if unknown. */
    public static int mocapClipByName(String name) {
        for (int i = 0; i < MOCAP_CLIPS.length; i++) {
            if (MOCAP_CLIPS[i].equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    /**
     * Server-side: change animation and sync to clients.
     * Timed animations (finite duration) automatically return to IDLE.
     */
    public void setAnimation(VillagerAnimationState state) {
        if (previewAnimation != null || isProfessionAnimationPreview()) return;
        applyAnimation(state);
    }

    /**
     * One sword swing, with the clip paced so its whole slash fits inside
     * {@code swingTicks}: a higher-level defender swings faster, and the
     * animation has to keep up. Restarted from frame zero for every swing, and
     * held just past the interval so a fight does not dip into idle between
     * swings.
     *
     * <p>The pace covers the blend-in as well as the clip: GeckoLib plays the
     * {@link VillagerAnimationState#WORK_BLEND_TICKS} transition at the clip's
     * speed, so pacing the clip alone ran the swing past its interval and put
     * the blade behind the blow (see {@code GuardBalance.STRIKE_POINT}).
     */
    public void startAttackAnimation(int swingTicks) {
        if (level().isClientSide) return;
        int ticks = Math.max(1, swingTicks);
        float animationTicks = VillagerAnimationState.cycleTicks(VillagerAnimationState.ATTACK)
                + VillagerAnimationState.WORK_BLEND_TICKS;
        this.entityData.set(WORK_ANIMATION_SPEED, Math.clamp(animationTicks / ticks, 0.25f, 4f));
        this.entityData.set(WORK_ANIMATION_SEQUENCE,
                this.entityData.get(WORK_ANIMATION_SEQUENCE) + 1);
        setAnimation(VillagerAnimationState.ATTACK);
        // A sad villager's clip plays slower (workClipSpeed); the state lasts the
        // whole of it, or the swing was cut off before the blade came through.
        animationTimer = attackAnimationTicks(ticks) + 2;
    }

    /**
     * The butcher's blow: the overhead chop down onto the block (mixamo_attack_down,
     * played as FORGE), paced like {@link #startAttackAnimation} so blend and clip
     * fit inside {@code swingTicks} and restart from frame zero every blow.
     */
    public void startChopAnimation(int swingTicks) {
        if (level().isClientSide) return;
        int ticks = Math.max(1, swingTicks);
        float animationTicks = VillagerAnimationState.ATTACK_DOWN_TICKS + VillagerAnimationState.WORK_BLEND_TICKS;
        this.entityData.set(WORK_ANIMATION_SPEED, Math.clamp(animationTicks / ticks, 0.25f, 4f));
        this.entityData.set(WORK_ANIMATION_SEQUENCE,
                this.entityData.get(WORK_ANIMATION_SEQUENCE) + 1);
        setAnimation(VillagerAnimationState.FORGE);
        animationTimer = attackAnimationTicks(ticks) + 2;
    }

    /** How long a swing paced to {@code swingTicks} takes on screen: longer while sad. */
    public int attackAnimationTicks(int swingTicks) {
        return (int) Math.ceil(Math.max(1, swingTicks) / sadWorkPace());
    }

    /** Whether the shield is raised, for the block pose and the drawn shield. */
    public boolean isShieldRaised() {
        return this.entityData.get(SHIELD_RAISED);
    }

    /**
     * Client-only tuning aid, set by {@code /helditem shieldpose on}: every guard
     * and captain is drawn holding its shield up, so the shield's placement can
     * be adjusted without waiting for a fight. Never read on the server.
     */
    public static volatile boolean clientShieldPosePreview;

    /** Whether to draw this villager with its shield up: raised for real, or the client preview. */
    public boolean showsShieldUp() {
        return isShieldRaised()
                || (clientShieldPosePreview && level().isClientSide && getProfession().isGuardLike());
    }

    /**
     * Raises or lowers the shield, here and on every client. Raising also ends
     * a lingering swing so the block pose is not hidden behind it.
     */
    /**
     * Raises or lowers the shield for drill practice: the block clip plays at
     * its authored speed, so a raise held for the whole clip shows all of it.
     */
    public void raiseShieldForPractice(boolean up) {
        if (up) this.entityData.set(SHIELD_PRACTICE, true);
        setShieldRaised(up);
    }

    /** Whether the raised shield is drill practice (see {@link #raiseShieldForPractice}). */
    public boolean isShieldPractice() {
        return this.entityData.get(SHIELD_PRACTICE);
    }

    /** Whether the raised shield is the high cover rather than the close guard. */
    public boolean isShieldHigh() {
        return this.entityData.get(SHIELD_HIGH);
    }

    /** Picks the stance the shield is (or will be) raised in: high cover, or close guard. */
    public void setShieldHigh(boolean high) {
        if (isShieldHigh() != high) this.entityData.set(SHIELD_HIGH, high);
    }

    public void setShieldRaised(boolean raised) {
        if (!raised) {
            this.entityData.set(SHIELD_PRACTICE, false);
            setShieldHigh(false);
        }
        if (isShieldRaised() == raised) return;
        this.entityData.set(SHIELD_RAISED, raised);
        if (raised && getAnimation() == VillagerAnimationState.ATTACK) {
            setAnimation(VillagerAnimationState.IDLE);
        }
        if (raised) {
            this.entityData.set(WORK_ANIMATION_SPEED, 1f);
        } else {
            shieldStance.lower();
        }
    }

    /** Whether an enemy is close enough that the guard holds its ready pose when it is not swinging. */
    public boolean isCombatReady() {
        return this.entityData.get(COMBAT_READY);
    }

    public void setCombatReady(boolean ready) {
        if (isCombatReady() != ready) this.entityData.set(COMBAT_READY, ready);
    }

    /** This defender's shield stance; only meaningful on the server. */
    public ShieldStance shieldStance() {
        return shieldStance;
    }

    private void applyAnimation(VillagerAnimationState state) {
        float dur = VillagerAnimationState.getDuration(state);
        animationTimer = Float.isInfinite(dur) ? 0 : (int) dur;
        if (state == VillagerAnimationState.IDLE) this.entityData.set(WORK_ANIMATION_SPEED, 1f);
        this.entityData.set(ANIM_STATE, state.getId());
    }

    /** Start a complete paced work bout as a distinct client animation event. */
    public void startWorkAnimation(VillagerAnimationState state, float speed) {
        if (level().isClientSide) return;
        this.entityData.set(WORK_ANIMATION_SPEED, Math.clamp(speed, 0.25f, 3f));
        this.entityData.set(WORK_ANIMATION_SEQUENCE,
                this.entityData.get(WORK_ANIMATION_SEQUENCE) + 1);
        setAnimation(state);
    }

    /**
     * Server-side: the stack the cook's clips show in a fist (the fuel, the raw food, the
     * dish); {@link ItemStack#EMPTY} for none. A one-item copy is sent, only when it changes.
     * Drawn only: nothing is taken from or put into the cook's hands or pack.
     */
    public void setCookProp(ItemStack stack) {
        if (level().isClientSide) return;
        ItemStack shown = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (!ItemStack.matches(shown, this.entityData.get(COOK_PROP))) this.entityData.set(COOK_PROP, shown);
    }

    /** What the cook's clips show in a fist ({@link #setCookProp}); empty for none. */
    public ItemStack getCookProp() { return this.entityData.get(COOK_PROP); }

    /**
     * A bongo clip started now, in time with the band song. Its sequence is the game time
     * (not the next number), so a client that only draws the bard later (looking away at
     * the time, or just come within range) starts the clip that much further on and the
     * slaps stay on the beat ({@link #bongoClipLate}).
     */
    public void startBongoClip(VillagerAnimationState state) {
        if (level().isClientSide) return;
        this.entityData.set(WORK_ANIMATION_SPEED, 1f);
        setBongoSequence();
        setAnimation(state);
    }

    private void setBongoSequence() {
        int stamp = (int) level().getGameTime();
        if (stamp == this.entityData.get(WORK_ANIMATION_SEQUENCE)) stamp++;
        this.entityData.set(WORK_ANIMATION_SEQUENCE, stamp);
    }

    /**
     * Client: how many ticks ago a bongo clip with this sequence (its start's game time)
     * started, kept under the blend in + one clip length so the loop shows the same point;
     * 0 when it is no time stamp (an old counter) or just now.
     */
    private double bongoClipLate(int startedAt, float partialTick) {
        double late = ((int) level().getGameTime() - startedAt) + partialTick;
        if (late <= 1 || late > 1200) return 0;
        double length = getAnimation() == VillagerAnimationState.BONGOS_JAM
                ? VillagerAnimationState.BONGOS_JAM_TICKS : VillagerAnimationState.BONGOS_PLAY_TICKS;
        int blend = VillagerAnimationState.WORK_BLEND_TICKS;
        return late < blend ? late : blend + (late - blend) % length;
    }

    public VillagerAnimationState getAnimation() {
        return VillagerAnimationState.byId(this.entityData.get(ANIM_STATE));
    }

    /**
     * chop_controller's clip speed: the synced pace, and a sad villager works at
     * SAD_WORK_PACE with its clips to match. Not the archer's shot: its release
     * frame is paced to the moment the arrow leaves, which sadness does not delay.
     * Nor a mage's cast, paced the same way to the moment its spell leaves.
     * Nor the pair dance: a sad partner slowed down would kick out of step.
     */
    private double workClipSpeed() {
        double pace = entityData.get(WORK_ANIMATION_SPEED);
        VillagerAnimationState state = getAnimation();
        // The bass and violin always play at their own tempo: plucks and bow turns land on the notes.
        if (state.isOnTheBeat()) return 1.0;
        return state == VillagerAnimationState.AIM || state.isMageCast()
                || state == VillagerAnimationState.JAZZ_PAIR_R
                || state == VillagerAnimationState.JAZZ_PAIR_L ? pace : pace * sadWorkPace();
    }

    /**
     * Which of the lumberjack's three chops is playing, from the synced work sequence (server
     * and clients agree, nothing else sent); set by {@link #startLumberChop}.
     */
    public LumberChopSwing lumberChopSwing() {
        return LumberChopSwing.forSequence(entityData.get(WORK_ANIMATION_SEQUENCE));
    }

    /**
     * Starts the lumberjack's chop clip as {@code swing}, at the axe's speed. Like
     * {@link #startWorkAnimation}, but the sequence moves on to the next number that reads as
     * this swing (one to three steps), so every restart on the same tree plays the same swing.
     */
    public void startLumberChop(LumberChopSwing swing, float speed) {
        if (level().isClientSide) return;
        int sequence = this.entityData.get(WORK_ANIMATION_SEQUENCE) + 1;
        while (LumberChopSwing.forSequence(sequence) != swing) sequence++;
        this.entityData.set(WORK_ANIMATION_SPEED, Math.clamp(speed, 0.25f, 3f));
        this.entityData.set(WORK_ANIMATION_SEQUENCE, sequence);
        setAnimation(VillagerAnimationState.LUMBER_CHOP);
    }


    /** Client: the work sequence the chop bites below count from, when this client first saw it, and bites heard. */
    private int chopBiteSequence = -1, chopBiteSeenTick, chopBitesHeard;

    /**
     * Client: the thunk and the wood chips on the frame the lumberjack's blade lands, timed
     * from this client's own clip (the chop_controller keeps its loop on the beat,
     * PhaseKeepingController), so they stay on the blows however long the tree takes. The
     * server used to send them on its own clock, which ran a few ticks ahead and drifted.
     * Until the clip has started on screen (off screen when it began), it counts from when the
     * new swing arrived.
     */
    private void tickChopBites() {
        if (getAnimation() != VillagerAnimationState.LUMBER_CHOP || isProfessionAnimationPreview() || !isAlive()) {
            chopBiteSequence = -1;
            return;
        }
        int sequence = entityData.get(WORK_ANIMATION_SEQUENCE);
        LumberChopSwing swing = lumberChopSwing();
        int start = sequence == renderedWorkAnimationSequence ? workClipStartTick : chopBiteSeenTick;
        float clip = (float) ((tickCount - start) * workClipSpeed() - VillagerAnimationState.WORK_BLEND_TICKS);
        int landed = swing.bitesBefore(clip);
        if (sequence != chopBiteSequence) {
            chopBiteSequence = sequence;
            chopBiteSeenTick = tickCount;
            chopBitesHeard = 0;
            return;
        }
        // only the bite that has just landed: after a jump (back on screen, a pause) catch up quietly
        boolean play = landed == chopBitesHeard + 1;
        chopBitesHeard = landed;
        if (!play) return;
        int n = landed - 1;
        double yaw = Math.toRadians(yBodyRot);
        double ahead = swing.biteAhead(n), right = swing.biteRight(n);
        double wx = getX() - Math.sin(yaw) * ahead - Math.cos(yaw) * right;
        double wz = getZ() + Math.cos(yaw) * ahead - Math.sin(yaw) * right;
        double wy = getY() + swing.biteUp(n);
        level().playLocalSound(wx, wy, wz, SoundEvents.WOOD_HIT, SoundSource.BLOCKS,
                0.75f, 0.85f + random.nextFloat() * 0.2f, false);
        // the blade bites just into the bark: look a hair further along for the log, then half a block
        BlockState wood = level().getBlockState(BlockPos.containing(wx - Math.sin(yaw) * 0.1, wy, wz + Math.cos(yaw) * 0.1));
        if (!wood.is(net.minecraft.tags.BlockTags.LOGS)) {
            wood = level().getBlockState(BlockPos.containing(wx - Math.sin(yaw) * 0.5, wy, wz + Math.cos(yaw) * 0.5));
        }
        if (!wood.is(net.minecraft.tags.BlockTags.LOGS)) return;
        var chips = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, wood);
        for (int i = 0; i < 8; i++) {
            level().addParticle(chips, wx + (random.nextDouble() - 0.5) * 0.1, wy + (random.nextDouble() - 0.5) * 0.16,
                    wz + (random.nextDouble() - 0.5) * 0.1,
                    (random.nextDouble() - 0.5) * 0.24, random.nextDouble() * 0.12, (random.nextDouble() - 0.5) * 0.24);
        }
    }

    /**
     * 0..count-1 for this farm job, from the synced bout counter and the entity id: every
     * client agrees, nothing is sent, and it stays put for the
     * whole job (the two planting or harvesting clips start from different poses, so a
     * switch mid job would jump). {@code salt} keeps planting and harvesting apart.
     */
    private int farmVariant(int salt, int count) {
        int h = (entityData.get(WORK_ANIMATION_SEQUENCE) + salt) * 0x9E3779B1 + getId() * 0x85EBCA6B;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 13;
        return Math.floorMod(h >>> 3, count);
    }

    /** The plain pick, or on 1 job in 3 its four-pick flourish (the look, or the thorn at a bush). */
    private RawAnimation pickClip(RawAnimation[] clips) {
        return clips[farmVariant(0x7C3F, 3) == 0 ? 1 : 0];
    }

    /**
     * While the blade is dragged through the soil in farmer_till_*: [clip tick the blade bites,
     * then points of (ticks after the bite, px right of him, px ahead of him)], read off the
     * two-hand bake's stroke paths (tools/till/twohand.js, hitDt and path). Strokes alternate
     * A, B in every loop, so the stroke number mod 2 picks the table.
     */
    private static final float[][] TILL_PULL_SKILLED = {
            {12.5f, 0, 2.4f, 16f, 13, 2.4f, 9.4f}, {12.5f, 0, -0.6f, 16f, 13, -0.4f, 9.4f}};
    private static final float[][] TILL_PULL_BEGINNER = {
            {15, 0, 2.6f, 15.2f, 3, 2.8f, 13.5f, 7, 2.9f, 13.1f, 10, 3.3f, 9.6f},
            {14, 0, 1.5f, 15.0f, 4, 2.9f, 12.5f, 6, 3.7f, 11.3f}};

    /** Client: dirt thrown up at the blade, and a quiet scrape, while a tilling pull is under way. */
    private void tickTillDirt() {
        VillagerAnimationState state = getAnimation();
        if (state != VillagerAnimationState.TILL && state != VillagerAnimationState.TILL_SKILLED) return;
        if (isProfessionAnimationPreview() || !isAlive()) return;
        double clip = (tickCount - workClipStartTick) * workClipSpeed() - VillagerAnimationState.WORK_BLEND_TICKS;
        if (clip < 0) return;
        int stroke = (int) (clip / 35) & 1;
        float local = (float) (clip % 35);
        float[] p = (state == VillagerAnimationState.TILL_SKILLED ? TILL_PULL_SKILLED : TILL_PULL_BEGINNER)[stroke];
        float d = local - p[0];
        int n = (p.length - 1) / 3;
        if (d < 0 || d > p[1 + 3 * (n - 1)]) return;
        float x = p[2], z = p[3];
        for (int i = 1; i < n; i++) {
            if (d <= p[1 + 3 * i]) {
                float u = (d - p[1 + 3 * (i - 1)]) / Math.max(0.01f, p[1 + 3 * i] - p[1 + 3 * (i - 1)]);
                float e = u * u * (3 - 2 * u);
                x = Mth.lerp(e, p[2 + 3 * (i - 1)], p[2 + 3 * i]);
                z = Mth.lerp(e, p[3 + 3 * (i - 1)], p[3 + 3 * i]);
                break;
            }
            x = p[2 + 3 * i];
            z = p[3 + 3 * i];
        }
        double yaw = Math.toRadians(yBodyRot);
        double ahead = z / 16.0, right = x / 16.0;
        double wx = getX() - Math.sin(yaw) * ahead - Math.cos(yaw) * right;
        double wz = getZ() + Math.cos(yaw) * ahead - Math.sin(yaw) * right;
        BlockPos ground = BlockPos.containing(wx, getY() - 0.5, wz);
        BlockState soil = level().getBlockState(ground);
        if (soil.isAir()) return;
        var dust = new net.minecraft.core.particles.BlockParticleOption(ParticleTypes.BLOCK, soil);
        for (int i = 0; i < 2; i++) {
            level().addParticle(dust, wx + (random.nextDouble() - 0.5) * 0.1, getY() + 0.04, wz + (random.nextDouble() - 0.5) * 0.1,
                    (random.nextDouble() - 0.5) * 0.05, 0.04 + random.nextDouble() * 0.05, (random.nextDouble() - 0.5) * 0.05);
        }
        if (tickCount % 4 == 0) {
            level().playLocalSound(wx, getY(), wz, soil.getSoundType().getStepSound(), SoundSource.NEUTRAL, 0.2f, 0.7f, false);
        }
    }

    /** Client: the tick the running work clip started on (its blend in comes first). */
    public int workClipStartTick() {
        return workClipStartTick;
    }

    /** Client: clip ticks since the running work clip started, at its speed, blend in included. */
    public float workClipTicks(float partialTick) {
        return (float) ((tickCount + partialTick - workClipStartTick) * workClipSpeed());
    }

    /**
     * Client: how far the bow in this archer's hand is drawn, 0..1, read off where
     * the shot clip is; -1 when no string is drawn (not shooting, or the arrow has
     * left). The clip runs the work blend first, both at the clip's speed.
     */
    public float bowDraw() {
        if (getAnimation() != VillagerAnimationState.AIM) return -1f;
        float clipTicks = (float) ((tickCount - workClipStartTick) * workClipSpeed())
                - VillagerAnimationState.WORK_BLEND_TICKS;
        // /villager preview loops the clip in place rather than restarting it: every loop draws again.
        if (isProfessionAnimationPreview() && clipTicks > 0) clipTicks %= VillagerAnimationState.AIM_CLIP_TICKS;
        if (clipTicks >= VillagerAnimationState.AIM_RELEASE_TICKS) return -1f;
        return VillagerAnimationState.aimDraw(clipTicks);
    }

    /**
     * Server-side preview for {@code /villager npc_anim}: plays {@code state}
     * from its first frame and holds it for {@code ticks}, whatever the brain
     * sets meanwhile; the brain keeps the villager standing still until it ends.
     * {@code null} ends a preview now.
     */
    public void previewAnimation(@Nullable VillagerAnimationState state, int ticks) {
        if (isProfessionAnimationPreview()) return;
        previewAnimation = null;
        this.entityData.set(ANIM_PREVIEW, state != null);
        if (state == null) {
            applyAnimation(VillagerAnimationState.IDLE);
            return;
        }
        long now = level().getGameTime();
        applyAnimation(VillagerAnimationState.IDLE);
        previewAnimation = state;
        previewStartAt = now + PREVIEW_RESTART_TICKS;
        previewUntil = previewStartAt + Math.max(1, ticks);
    }

    public boolean isPreviewingAnimation() { return previewAnimation != null || isProfessionAnimationPreview(); }

    public boolean isProfessionAnimationPreview() {
        return entityData.get(PROFESSION_ANIMATION_PREVIEW);
    }

    /** A detached, persistent command puppet; its job animations never perform work. */
    public void startProfessionAnimationPreview(float speed) {
        if (level().isClientSide) return;
        previewAnimation = null;
        entityData.set(ANIM_PREVIEW, false);
        entityData.set(PROFESSION_ANIMATION_PREVIEW, true);
        entityData.set(WORK_ANIMATION_SPEED, Float.isFinite(speed)
                ? Math.clamp(speed, ProfessionAnimationPreview.MIN_SPEED, ProfessionAnimationPreview.MAX_SPEED) : 1f);
        professionPreviewIndex = 0;
        if (professionPreviewOnly != null) {
            var clips = ProfessionAnimationPreview.clips(getProfession());
            for (int i = 0; i < clips.size(); i++) if (clips.get(i).state() == professionPreviewOnly) professionPreviewIndex = i;
        }
        // A blacksmith gets its anvil, smithing table or furnace on the next tile (a cook its
        // table, furnace, smoker or campfire), so it stands in the middle of its own tile
        // facing square on, as at its work.
        if (getProfession() == com.alex.test.village.villagers.core.Profession.BLACKSMITH
                || getProfession() == com.alex.test.village.villagers.core.Profession.COOK) {
            BlockPos tile = blockPosition();
            setPos(tile.getX() + 0.5, getY(), tile.getZ() + 0.5);
            setYRot(net.minecraft.core.Direction.fromYRot(getYRot()).toYRot());
        }
        professionPreviewPosition = position();
        professionPreviewYaw = getYRot();
        setNoAi(true);
        setNoGravity(true);
        beginProfessionPreviewClip();
        holdProfessionPreviewInPlace();
    }

    private void beginProfessionPreviewClip() {
        var clips = ProfessionAnimationPreview.clips(getProfession());
        professionPreviewIndex = Math.floorMod(professionPreviewIndex, clips.size());
        var clip = clips.get(professionPreviewIndex);
        // applyAnimation(IDLE) resets work speed for ordinary villagers; keep
        // the selected multiplier even for professions whose work pose is idle.
        float speed = entityData.get(WORK_ANIMATION_SPEED);
        applyAnimation(clip.state());
        entityData.set(WORK_ANIMATION_SPEED, speed);
        animationTimer = 0;
        if (clip.state().isBongos()) setBongoSequence();
        else entityData.set(WORK_ANIMATION_SEQUENCE, entityData.get(WORK_ANIMATION_SEQUENCE) + 1);
        professionPreviewTicks = clip.previewTicks(speed);
        professionPreviewElapsed = 0;
        placePreviewStation(clip.state());
        setCookProp(previewCookProp(clip.state()));
        setCustomName(Component.literal(getProfession().displayName() + " preview: "
                + clip.state().name().toLowerCase(java.util.Locale.ROOT) + " (" + speed + "x)"));
        setCustomNameVisible(true);
    }

    /** The block a blacksmith preview works at, put on the tile in front; null when none is placed. */
    @Nullable private BlockPos previewStationPos;
    /** Ticks since the preview's current clip started. */
    private int professionPreviewElapsed;

    /**
     * Puts the block this clip works at in front of a blacksmith or cook preview (the clips are
     * made against the next tile, tools/forge and tools/cook): the anvil end on, the smithing
     * table, a lit furnace facing the smith; for the cook a crafting table, a lit furnace (a lit
     * smoker for the smoke wave) or a lit campfire, and nothing for its moments and the wait
     * for a busy station. The last one goes first; a tile that is not empty is left alone.
     */
    private void placePreviewStation(VillagerAnimationState clip) {
        removePreviewStation();
        if (getProfession() != com.alex.test.village.villagers.core.Profession.BLACKSMITH
                && getProfession() != com.alex.test.village.villagers.core.Profession.COOK) return;
        net.minecraft.core.Direction facing = net.minecraft.core.Direction.fromYRot(getYRot());
        net.minecraft.world.level.block.state.BlockState block = switch (clip) {
            case SMITH_ANVIL, SMITH_ANVIL_HEAVY, SMITH_ENCHANT, SMITH_ENCHANT_TAP, SMITH_RECYCLE ->
                    net.minecraft.world.level.block.Blocks.ANVIL.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.AnvilBlock.FACING, facing);
            // The bar lies along the anvil's long top (tools/anvil): its long side toward him. A vanilla
            // anvil's top runs across its FACING, so the anvil faces his right.
            case SMITH_ANVIL_DRAW -> net.minecraft.world.level.block.Blocks.ANVIL.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.AnvilBlock.FACING, facing.getClockWise());
            case SMITH_SMITHING -> net.minecraft.world.level.block.Blocks.SMITHING_TABLE.defaultBlockState();
            case SMITH_FURNACE, COOK_FIRE_LIGHT, COOK_FIRE_FEED, COOK_LOAD, COOK_WAIT_PEEK, COOK_WAIT_HIPS,
                 COOK_WAIT_SPOON, COOK_WAIT_TIDY, COOK_TAKE ->
                    net.minecraft.world.level.block.Blocks.FURNACE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, facing.getOpposite())
                    .setValue(net.minecraft.world.level.block.FurnaceBlock.LIT, true);
            case COOK_KNEAD, COOK_COOKIES, COOK_WHISK, COOK_PIE, COOK_CHOP_STIR, COOK_GILD, COOK_WHITTLE,
                 COOK_PRESS_CANE -> net.minecraft.world.level.block.Blocks.CRAFTING_TABLE.defaultBlockState();
            case COOK_WAIT_SMOKE -> net.minecraft.world.level.block.Blocks.SMOKER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.SmokerBlock.FACING, facing.getOpposite())
                    .setValue(net.minecraft.world.level.block.SmokerBlock.LIT, true);
            case COOK_CAMP_LAY, COOK_CAMP_TURN, COOK_CAMP_WAIT, COOK_CAMP_TAKE ->
                    net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.CampfireBlock.FACING, facing.getOpposite())
                    .setValue(net.minecraft.world.level.block.CampfireBlock.LIT, true);
            default -> null;
        };
        if (block == null) return;
        BlockPos pos = blockPosition().relative(facing);
        if (!level().getBlockState(pos).isAir()) return;
        level().setBlock(pos, block, net.minecraft.world.level.block.Block.UPDATE_ALL);
        previewStationPos = pos;
    }

    /** Takes away the block {@link #placePreviewStation} put down, if it is still one of ours. */
    public void removePreviewStation() {
        if (previewStationPos == null) return;
        var state = level().getBlockState(previewStationPos);
        if (state.getBlock() instanceof net.minecraft.world.level.block.AnvilBlock
                || state.is(net.minecraft.world.level.block.Blocks.SMITHING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.FURNACE)
                || state.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                || state.is(net.minecraft.world.level.block.Blocks.SMOKER)
                || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)) {
            level().setBlock(previewStationPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
                    net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        previewStationPos = null;
    }

    /**
     * A stand-in for what a cook preview's clip shows in a fist (the real cook shows its own
     * fuel, food and dish): coal for the fire, raw beef going in, cooked beef coming out and
     * presented, a cookie, a golden apple, the sugar cane. Empty for every other clip.
     */
    private static ItemStack previewCookProp(VillagerAnimationState clip) {
        return switch (clip) {
            case COOK_FIRE_LIGHT, COOK_FIRE_FEED -> new ItemStack(net.minecraft.world.item.Items.COAL);
            case COOK_LOAD, COOK_CAMP_LAY -> new ItemStack(net.minecraft.world.item.Items.BEEF);
            case COOK_TAKE, COOK_CAMP_TAKE, COOK_PRESENT -> new ItemStack(net.minecraft.world.item.Items.COOKED_BEEF);
            case COOK_COOKIES -> new ItemStack(net.minecraft.world.item.Items.COOKIE);
            case COOK_GILD -> new ItemStack(net.minecraft.world.item.Items.GOLDEN_APPLE);
            case COOK_PRESS_CANE -> new ItemStack(net.minecraft.world.item.Items.SUGAR_CANE);
            default -> ItemStack.EMPTY;
        };
    }

    private void holdProfessionPreviewInPlace() {
        holdStillForAnimation();
        setDeltaMovement(Vec3.ZERO);
        if (professionPreviewPosition != null && !position().equals(professionPreviewPosition)) {
            setPos(professionPreviewPosition.x, professionPreviewPosition.y, professionPreviewPosition.z);
        }
        setYRot(professionPreviewYaw);
        setYBodyRot(professionPreviewYaw);
        setYHeadRot(professionPreviewYaw);
        setXRot(0f);
    }

    /**
     * {@code /villager preview bass|violin|flute|guitar|bongos}: a bard pinned to one instrument
     * clip, with that instrument's serenade recording started again from the bard itself each
     * time it ends. The flute and guitar have no recording, so their previews are silent. The
     * bongos play the band song's drum part, and their clip starts again with it.
     */
    public void startInstrumentPreview(VillagerAnimationState clip) {
        if (level().isClientSide) return;
        professionPreviewOnly = clip;
        previewMusicTicks = clip.isFlute() || clip.isGuitar() ? -1 : 0;
        startProfessionAnimationPreview(1f);
    }

    /** Whether this is an instrument preview with its recording looping. */
    public boolean hasPreviewMusic() { return previewMusicTicks >= 0; }

    private void tickProfessionAnimationPreview() {
        holdProfessionPreviewInPlace();
        // The hammer's clink on the anvil at each blow, at normal speed only (the blows are ticks).
        int clipTicks = professionPreviewElapsed++;
        if (previewStationPos != null && entityData.get(WORK_ANIMATION_SPEED) == 1f
                && level() instanceof net.minecraft.server.level.ServerLevel server) {
            com.alex.test.village.villagers.blacksmith.SmithStrikes.tick(server, previewStationPos, getAnimation(), clipTicks);
        }
        if (previewMusicTicks >= 0 && professionPreviewOnly != null && professionPreviewOnly.isBongos()) {
            // Clip time 0 is song time 0. The client starts the clip on the tick it sees the
            // sequence bump, so clip time = ticks since that tick - blend; clipTicks counts
            // from the tick after it. 48 s is not a whole number of bars, so the clip starts
            // again (its blend in) right before each play-through, in the drums' closing quiet.
            if (clipTicks == VillagerAnimationState.WORK_BLEND_TICKS - 1) {
                level().playSound(null, this, com.alex.test.ModSounds.BARD_BAND_BONGOS.get(), SoundSource.RECORDS, 1f, 1f);
            }
            if (clipTicks == BONGOS_PREVIEW_MUSIC_TICKS - 1) {
                setBongoSequence();
                professionPreviewElapsed = 0;
            }
        } else if (previewMusicTicks >= 0 && --previewMusicTicks <= 0) {
            boolean violin = professionPreviewOnly != null && professionPreviewOnly.isViolin();
            previewMusicTicks = violin ? VIOLIN_PREVIEW_MUSIC_TICKS : BASS_PREVIEW_MUSIC_TICKS;
            // The violin takes turns with its pieces as the serenade plays them: the
            // first once, each of the others through twice.
            var sound = !violin ? com.alex.test.ModSounds.BARD_SHOWOFF_BASS
                    : VIOLIN_PREVIEW_ORDER.get(previewMusicStep++ % VIOLIN_PREVIEW_ORDER.size());
            level().playSound(null, this, sound.get(), SoundSource.RECORDS, 1f, 1f);
        }
        if (--professionPreviewTicks <= 0) {
            // A single clip can loop uninterrupted. Multiple clips restart at
            // each boundary, including harvest -> plant which share a raw clip.
            if (professionPreviewOnly == null && ProfessionAnimationPreview.clips(getProfession()).size() > 1) {
                professionPreviewIndex++;
                beginProfessionPreviewClip();
            } else {
                professionPreviewTicks = Integer.MAX_VALUE;
            }
        }
    }

    /** Keeps a preview's state on the entity, and ends the preview on time. */
    private void tickAnimationPreview() {
        if (previewAnimation == null) return;
        long now = level().getGameTime();
        if (now >= previewUntil || now < previewStartAt - PREVIEW_RESTART_TICKS) {
            // Over, or the clock jumped back past its start.
            previewAnimation(null, 0);
            return;
        }
        holdStillForAnimation();
        if (now < previewStartAt) {
            if (getAnimation() != VillagerAnimationState.IDLE) applyAnimation(VillagerAnimationState.IDLE);
            return;
        }
        if (now == previewStartAt) {
            // Work and eat clips restart on a new sequence, not on the state alone.
            this.entityData.set(WORK_ANIMATION_SPEED, 1f);
            this.entityData.set(WORK_ANIMATION_SEQUENCE, this.entityData.get(WORK_ANIMATION_SEQUENCE) + 1);
            this.entityData.set(EAT_ANIMATION_SEQUENCE, this.entityData.get(EAT_ANIMATION_SEQUENCE) + 1);
        }
        if (getAnimation() != previewAnimation) applyAnimation(previewAnimation);
        animationTimer = 0; // held by the preview, not the clip's own timer
    }

    /**
     * Starts one distinct eat animation on the authoritative server.
     *
     * <p>The incrementing sequence is synced separately from {@link #ANIM_STATE},
     * so consecutive EAT states remain separate client animation events.
     */
    public boolean startEatAnimation() {
        if (level().isClientSide || !isAlive()) return false;

        int nextSequence = this.entityData.get(EAT_ANIMATION_SEQUENCE) + 1;
        this.entityData.set(EAT_ANIMATION_SEQUENCE, nextSequence);
        setAnimation(VillagerAnimationState.EAT);
        return true;
    }

    public int getEatAnimationSequence() {
        return this.entityData.get(EAT_ANIMATION_SEQUENCE);
    }

    /**
     * The eat clip (designed in tools/eat): the food comes out of the pocket at
     * clip tick 17 and the last piece goes in at 114. Before and after, the
     * eating hand is drawn empty.
     */
    private static final int EAT_FOOD_SHOWN_TICK = 17, EAT_FOOD_GONE_TICK = 114;
    /** Clip ticks at which the food meets the mouth: three bites, then the last piece. */
    private static final int[] EAT_BITE_TICKS = {43, 68, 91, 112};

    /** Client: whether the food is in the eating hand now, out of the pocket and not yet eaten. */
    public boolean showsFoodInHand() {
        if (!eatClipRunning) return false;
        // The clip starts once its blend in is over (AnimationController.process).
        int clipTick = tickCount - eatClipStartTick - FLOURISH;
        return clipTick >= EAT_FOOD_SHOWN_TICK && clipTick < EAT_FOOD_GONE_TICK;
    }

    /**
     * Server: whether a bite lands this many ticks into one play of the eat
     * clip, counted from {@link #startEatAnimation}. The client starts the clip
     * after its blend in, so the bite is shifted by that.
     */
    public static boolean isEatBiteTick(int ticksIntoClip) {
        for (int bite : EAT_BITE_TICKS) {
            if (ticksIntoClip == FLOURISH + bite) return true;
        }
        return false;
    }

    /**
     * Server: one bite of {@code food}, as a player's: the vanilla eating sound
     * and crumbs of the food itself at the mouth, which is just under the eyes,
     * in front of the face and to the right of the nose.
     */
    public void eatBite(ItemStack food, float volume) {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel server)) return;
        server.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT, SoundSource.NEUTRAL,
                volume, 0.9f + getRandom().nextFloat() * 0.2f);
        if (food.isEmpty()) return;
        double yaw = Math.toRadians(yBodyRot);
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = -Math.cos(yaw), rightZ = -Math.sin(yaw);
        double x = getX() + forwardX * 0.32 + rightX * 0.12;
        double z = getZ() + forwardZ * 0.32 + rightZ * 0.12;
        server.sendParticles(new net.minecraft.core.particles.ItemParticleOption(
                        net.minecraft.core.particles.ParticleTypes.ITEM, food.copyWithCount(1)),
                x, getEyeY() - 0.15, z, 6, 0.05, 0.03, 0.05, 0.05);
    }

    public boolean isEating() {
        return getAnimation() == VillagerAnimationState.EAT;
    }

    /**
     * Reading in the library. The open book is drawn by the renderer from this
     * state alone -- it is a prop, never an inventory item -- so it disappears
     * the moment the animation changes for any reason.
     */
    public boolean isReadingBook() {
        return getAnimation() == VillagerAnimationState.READ;
    }

    /**
     * Starts vanilla bed sleep for this puppet and clears any leftover pathing
     * velocity so the sleeping pose cannot drift off the bed.
     */
    public void startBedSleep(BlockPos bedPos) {
        getNavigation().stop();
        setSpeed(0f);
        xxa = 0f;
        zza = 0f;
        setDeltaMovement(Vec3.ZERO);
        lieDownFrom = blockPosition();
        startSleeping(bedPos);
        holdStillWhileSleeping();
    }

    /**
     * Wake spot (10-18): the tile this villager stepped into bed from, a tile it walked to, so one
     * the village map knows a way out of. Saved while asleep; see {@link #stopSleeping}.
     */
    @Nullable
    private BlockPos lieDownFrom;

    /**
     * Wake spot (10-18): Minecraft stands a waking sleeper on a free tile beside the bed. That tile
     * can be a pocket the village map has no way out of (the bed on one side, a chair and walls on
     * the others): a farmer stood in one until the trapped rescue moved it, through a wall. Every
     * way out of bed (morning, a hit, an alarm) comes through here, so the village checks the tile.
     * K1b (10-18): with no lie-down tile saved (asleep since before the wake spot), the bed goes
     * along instead, read before Minecraft clears it.
     */
    @Override
    public void stopSleeping() {
        boolean inBed = isSleeping();
        BlockPos from = lieDownFrom;
        BlockPos bed = getSleepingPos().orElse(null);
        lieDownFrom = null;
        super.stopSleeping();
        if (!inBed || !WalkFeel.wakeSpot || !(level() instanceof ServerLevel serverLevel)) return;
        if (from == null && (bed == null || !WalkFeel.wakeBedside)) return;
        VillageManager manager = resolveVillageManager(serverLevel);
        VillagerModel model = manager != null ? manager.getModel(getNpcId()) : null;
        if (model != null) model.standClearOfBed(manager.getGraph(), from, bed);
    }

    /** True while riding a chair's seat. Works on both sides; the vehicle link is synced. */
    /** Client: the walk controller is playing the sad walk (it then plays at SAD_WALK_CLIP_SPEED). */
    private boolean playingSadWalk;
    /** Client tick this villager last visibly moved; see {@link #isWalkingForAnimation}. */
    private int lastWalkMotionTick = Integer.MIN_VALUE / 2;
    /** Vanilla's smoothed leg swing above this counts as walking. */
    private static final float WALK_SWING_THRESHOLD = 0.03f;
    /** Ticks the walk keeps playing after the villager stops moving. */
    private static final int WALK_GRACE_TICKS = 3;

    /**
     * Whether the walk clip should play, on the client.
     *
     * <p>GeckoLib's own {@code isMoving()} reads the velocity the server last
     * sent, which is coarse and drops to zero for a tick or more whenever a
     * villager bumps into a block face before jumping onto it, lands, or steps
     * down a stair. The walk then flipped to the idle pose and, coming back,
     * restarted from frame zero: villagers "reset to standing" at every step.
     * This uses what the client actually sees instead -- vanilla's leg swing,
     * which is computed from position change and eases out over a few ticks --
     * plus the position change this tick, and holds the walk for
     * {@value #WALK_GRACE_TICKS} ticks after the last movement.
     */
    private boolean isWalkingForAnimation(software.bernie.geckolib.animation.AnimationState<NpcEntity> state) {
        double dx = getX() - xo;
        double dz = getZ() - zo;
        if (state.isMoving() || walkAnimation.speed() > WALK_SWING_THRESHOLD
                || dx * dx + dz * dz > 1.0E-4) {
            lastWalkMotionTick = tickCount;
        }
        return tickCount - lastWalkMotionTick <= WALK_GRACE_TICKS;
    }

    /**
     * Whether this villager's rig has the sad walk. Until the Mixamo take is
     * converted for a rig, a sad villager keeps the ordinary walk instead of
     * asking GeckoLib for a clip that is not there.
     */
    private boolean hasSadWalkClip() {
        return hasClip("mixamo_sad_walk");
    }

    /** Whether this villager's rig has the sad idle; see {@link #hasSadWalkClip}. */
    private boolean hasSadIdleClip() {
        return hasClip("mixamo_sad_idle");
    }

    private boolean hasClip(String name) {
        var cache = software.bernie.geckolib.cache.GeckoLibCache.getBakedAnimations();
        if (cache == null) return false;
        var anims = cache.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID,
                "animations/base_villager.animation.json"));
        return anims != null && anims.getAnimation(name) != null;
    }

    /** Set by the brain each tick from wellbeing; drives the sad walk and the slower pace. */
    public void setSad(boolean sad) {
        if (entityData.get(SAD) != sad) entityData.set(SAD, sad);
    }

    public boolean isSad() {
        return entityData.get(SAD);
    }

    /** 1, or WellbeingSystem.SAD_WORK_PACE while sad. */
    public float sadWorkPace() {
        return isSad() ? com.alex.test.village.stats.WellbeingSystem.SAD_WORK_PACE : 1f;
    }

    /** 1, or WellbeingSystem.SAD_WALK_PACE while sad. */
    public float sadWalkPace() {
        return isSad() ? com.alex.test.village.stats.WellbeingSystem.SAD_WALK_PACE : 1f;
    }

    public boolean isChairSeated() {
        return getVehicle() instanceof ChairSeatEntity;
    }

    /** The chair this villager is sitting on, or null. */
    @Nullable
    public BlockPos seatedChairPos() {
        return getVehicle() instanceof ChairSeatEntity seat ? seat.chairPos() : null;
    }

    /**
     * Sits on the chair at {@code pos}, the same way a player does: an
     * invisible seat entity is spawned and ridden. Fails if it is not a chair
     * or somebody else is already on it.
     */
    public boolean sitOnChair(BlockPos pos) {
        if (!(level() instanceof ServerLevel serverLevel) || !isAlive()) return false;
        if (pos.equals(seatedChairPos())) return true;
        BlockState state = serverLevel.getBlockState(pos);
        if (!(state.getBlock() instanceof WoodenChairBlock chair)) return false;
        if (isPassenger()) stopRiding();

        for (ChairSeatEntity existing : serverLevel.getEntitiesOfClass(
                ChairSeatEntity.class, new AABB(pos).inflate(0.15))) {
            if (existing.isVehicle()) return false;
            existing.discard();
        }
        ChairSeatEntity seat = DynamicVillages.CHAIR_SEAT.get().create(serverLevel);
        if (seat == null) return false;
        float yaw = state.getValue(WoodenChairBlock.FACING).toYRot();
        seat.moveTo(pos.getX() + 0.5, pos.getY() + chair.seatMountHeight(), pos.getZ() + 0.5, yaw, 0.0F);
        if (!serverLevel.addFreshEntity(seat)) return false;

        getNavigation().stop();
        setSpeed(0f);
        xxa = 0f;
        zza = 0f;
        setDeltaMovement(Vec3.ZERO);
        // Forced: dismounting starts a boarding cooldown that would otherwise
        // stop a villager sitting back down within three seconds.
        if (!startRiding(seat, true)) {
            seat.discard();
            return false;
        }
        setYRot(yaw);
        setYBodyRot(yaw);
        setYHeadRot(yaw);
        setAnimation(VillagerAnimationState.CHAIR_SIT);
        return true;
    }

    /** Gets off the chair, stepping out to the front if there is room. */
    public void standUpFromChair() {
        if (!(getVehicle() instanceof ChairSeatEntity seat)) return;
        BlockPos chair = seat.chairPos();
        stopRiding();
        // The empty seat removes itself on its next tick, but past the simulation distance it never
        // ticks (FarAwake): removed here.
        if (!level().isClientSide && !seat.isVehicle()) seat.discard();
        setDeltaMovement(Vec3.ZERO);
        if (getAnimation() == VillagerAnimationState.CHAIR_SIT) setAnimation(VillagerAnimationState.IDLE);
        if (level() instanceof ServerLevel serverLevel) VillageChairs.release(serverLevel, chair, getUUID());
    }

    /**
     * Outlined (located/alerting) villagers render out to 80 blocks (the default tracking range, so the client
     * has them that far) even with the tiny sleeping hitbox.
     */
    private static final double OUTLINED_RENDER_DISTANCE_SQR = 80.0 * 80.0;

    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSqr) {
        if (super.shouldRenderAtSqrDistance(distanceSqr)) return true;
        return level().isClientSide()
                && distanceSqr < OUTLINED_RENDER_DISTANCE_SQR
                && com.alex.test.client.VillageAlertOutline.isActive(this);
    }

    /**
     * Push in the air, as a share of the speed the move control asks for, that
     * holds a running villager's pace through a hop. Air drag takes 9% a tick and
     * ground friction 45.4%, and the push is speed x forward input either way, so
     * the steady air pace equals the ground's when this is 0.09 / 0.454.
     */
    private static final float AIR_RUN_PUSH = 0.09f / 0.454f;

    /**
     * Round 3 of the running hop. A mob gets a flat 0.02 of push in the air
     * whatever its speed, so a running villager's pace drained through a hop
     * (and from a standstill against a block face, barely came back). In a hurry
     * it keeps the pace it runs at on the ground -- and only that pace: the push
     * levels off exactly there, so a run off a ledge carries no further than a
     * run along the ground would. Server side, where mobs move.
     */
    @Override
    protected float getFlyingSpeed() {
        if (!level().isClientSide && isRunning() && !isInWaterOrBubble() && !onClimbable()) {
            return getSpeed() * AIR_RUN_PUSH;
        }
        return super.getFlyingSpeed();
    }

    /**
     * A step's travel at speed s on plain ground, as a multiple of s squared: the
     * push (0.98 s^2) over what friction lets through (1 - 0.546).
     */
    private static final double GROUND_PACE = 0.98 / (1.0 - 0.546);

    @Override
    public void jumpFromGround() {
        if (stairStepAssistTicks > 0 && isOnOrNearStair()) {
            if (!level().isClientSide) moveProbe.jumpRefused(level().getGameTime());
            return;
        }
        if (!level().isClientSide) {
            BlockPos gate = fenceGateBeside();
            if (gate != null) logGateHop("jumped", gate);
        }
        super.jumpFromGround();
        if (!level().isClientSide) moveProbe.jumped(level().getGameTime());
        if (!level().isClientSide && isRunning()) carryRunIntoJump();
    }

    /** Stuck catcher: the last move asked of the body, what it got and what stopped it. */
    private final com.alex.test.village.stuckwatch.MoveProbe moveProbe =
            new com.alex.test.village.stuckwatch.MoveProbe();

    public com.alex.test.village.stuckwatch.MoveProbe moveProbe() { return moveProbe; }

    @Override
    public void move(net.minecraft.world.entity.MoverType type, Vec3 movement) {
        if (level().isClientSide || type != net.minecraft.world.entity.MoverType.SELF
                || !com.alex.test.village.stuckwatch.StuckWatch.recording()) {
            super.move(type, movement);
            return;
        }
        double x0 = getX(), y0 = getY(), z0 = getZ();
        super.move(type, movement);
        moveProbe.record(this, movement, getX() - x0, getY() - y0, getZ() - z0);
    }

    private static final org.slf4j.Logger GATE_HOP_LOGGER = com.mojang.logging.LogUtils.getLogger();
    private long lastGateHopLogTick = Long.MIN_VALUE;

    /** A fence gate in the villager's own cell or the one above, or beside either; null if none. */
    @Nullable
    private BlockPos fenceGateBeside() {
        BlockPos feet = blockPosition();
        for (BlockPos base : new BlockPos[] { feet, feet.above() }) {
            if (level().getBlockState(base).getBlock() instanceof FenceGateBlock) return base;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                BlockPos pos = base.relative(side);
                if (level().getBlockState(pos).getBlock() instanceof FenceGateBlock) return pos;
            }
        }
        return null;
    }

    /**
     * One line, at most every 2 s per villager, when it jumps beside a fence
     * gate: where, the gate's open state, and where it was steering. Hops at
     * gates on flat ground had no known cause apart from a gate shut on the
     * villager; this names any other.
     */
    void logGateHop(String what, BlockPos gate) {
        if (level().isClientSide || tickCount - lastGateHopLogTick < 40) return;
        lastGateHopLogTick = tickCount;
        BlockState state = level().getBlockState(gate);
        boolean open = state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN);
        MoveControl move = getMoveControl();
        GATE_HOP_LOGGER.info("[GateHop] {} {} at ({}, {}, {}) gate {} open={} wanted=({}, {}, {}) rise={} onGround={} running={}",
                getName().getString(), what,
                String.format("%.2f", getX()), String.format("%.2f", getY()), String.format("%.2f", getZ()),
                gate.toShortString(), open,
                String.format("%.2f", move.getWantedX()), String.format("%.2f", move.getWantedY()),
                String.format("%.2f", move.getWantedZ()),
                String.format("%.2f", move.getWantedY() - getY()), onGround(), isRunning());
    }

    /**
     * The other half of round 3. On the ground almost half of each tick's travel
     * is that tick's push and friction takes the rest back, so a runner leaves the
     * ground carrying 55% of its pace, and the small air push takes over ten ticks
     * to build it back: the hop sagged in the middle. A sprinting player gets a
     * flat boost on jumping for the same reason. Here the carried motion is topped
     * up to what the air push holds steady -- 91% of the ground pace, which with
     * the push is exactly the pace -- along the way it was going (facing, if it
     * had stopped against a face), never above it.
     */
    private void carryRunIntoJump() {
        Vec3 motion = getDeltaMovement();
        double carried = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
        double speed = getSpeed();
        double wanted = (1.0 - 0.09) * GROUND_PACE * speed * speed;
        if (carried >= wanted) return;
        double dx, dz;
        if (carried > 1.0E-4) {
            dx = motion.x / carried;
            dz = motion.z / carried;
        } else {
            float yaw = getYRot() * ((float) Math.PI / 180f);
            dx = -Mth.sin(yaw);
            dz = Mth.cos(yaw);
        }
        setDeltaMovement(dx * wanted, motion.y, dz * wanted);
    }

    /** Server: on the ground at the end of last tick, for {@link #settleRunLanding}. */
    private boolean wasOnGroundLastTick = true;

    /**
     * Round 3's landing. A hop carries 91% of the pace through the air, and the
     * first step on the ground adds a whole ground push on top: a lunge a third
     * faster than the run. On touching down the carried motion goes back to what
     * a step on the ground carries (55%), so the run goes on level.
     */
    private void settleRunLanding() {
        boolean grounded = onGround();
        if (grounded && !wasOnGroundLastTick && isRunning()) {
            Vec3 motion = getDeltaMovement();
            double carried = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            double speed = getSpeed();
            double groundCarry = 0.546 * GROUND_PACE * speed * speed;
            if (carried > groundCarry && carried > 1.0E-4) {
                double scale = groundCarry / carried;
                setDeltaMovement(motion.x * scale, motion.y, motion.z * scale);
            }
        }
        wasOnGroundLastTick = grounded;
    }

    private boolean isOnOrNearStair() {
        BlockPos feet = BlockPos.containing(getX(), getY() - 0.05D, getZ());
        if (isStairAt(feet) || isStairAt(feet.below())) return true;
        if (isStairAt(feet.north()) || isStairAt(feet.south())
                || isStairAt(feet.east()) || isStairAt(feet.west())) return true;
        return false;
    }

    private boolean isStairAt(BlockPos pos) {
        return level().getBlockState(pos).getBlock() instanceof StairBlock;
    }


    private void tickStairStepAssist() {
        if (stairStepAssistTicks > 0) stairStepAssistTicks--;
    }

    /**
     * Cancels navigation input and horizontal momentum for the current tick.
     * Vertical velocity is retained so gravity and falling continue normally.
     */
    public void holdStillWhileEating() {
        if (!isEating()) return;

        getNavigation().stop();
        setSpeed(0f);
        xxa = 0f;
        zza = 0f;
        Vec3 movement = getDeltaMovement();
        setDeltaMovement(0.0, movement.y, 0.0);
    }

    /**
     * Cancels horizontal movement without requiring a specific animation state.
     * Used by activities that need a brief committed pose, such as a guard swing.
     */
    public void holdStillForAnimation() {
        getNavigation().stop();
        setSpeed(0f);
        xxa = 0f;
        zza = 0f;
        Vec3 movement = getDeltaMovement();
        setDeltaMovement(0.0, movement.y, 0.0);
    }

    /**
     * Sleeping puppets are driven entirely by the bed pose and GeckoLib
     * animation. Vanilla can leave a little movement from the final pathing tick;
     * zero it so they stay anchored to the bed until wake-up.
     */
    public void holdStillWhileSleeping() {
        if (!isSleeping()) return;

        getNavigation().stop();
        setSpeed(0f);
        xxa = 0f;
        zza = 0f;
        setDeltaMovement(Vec3.ZERO);
    }

    // -------------------------------------------------------------------------
    // Tick
    // -------------------------------------------------------------------------

    // A captain's cape trails behind it the way a player's does: a point chases
    // the villager, and the cape swings by how far it lags (CapeGeoLayer).
    // Client only, after vanilla's Player.moveCloak and CapeLayer.
    private double xCloak, yCloak, zCloak;
    /** Walk distance and view bob, kept the player's way; a mob has neither. */
    private float capeWalkDist, capeBob;
    /** The cape's swing in degrees, this tick and last, for CapeGeoLayer to blend. */
    public float capeBack, capeBackO, capeSide, capeSideO, capeLift, capeLiftO;

    /**
     * Works the vanilla cape angles out once a tick and eases toward them.
     * Villager positions arrive a little unevenly, and vanilla's per-frame
     * formula turned every hitch into a twitch of the cape.
     */
    private void moveCloak() {
        if (Math.abs(getX() - xCloak) > 10.0) xCloak = getX();
        if (Math.abs(getY() - yCloak) > 10.0) yCloak = getY();
        if (Math.abs(getZ() - zCloak) > 10.0) zCloak = getZ();
        xCloak += (getX() - xCloak) * 0.25;
        yCloak += (getY() - yCloak) * 0.25;
        zCloak += (getZ() - zCloak) * 0.25;

        double dx = xCloak - getX();
        double dy = yCloak - getY();
        double dz = zCloak - getZ();
        float yaw = yBodyRot * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw);
        double cos = -Mth.cos(yaw);
        float lift = Mth.clamp((float) dy * 10f, -6f, 32f);
        float back = Mth.clamp((float) (dx * sin + dz * cos) * 100f, 0f, 150f);
        float side = Mth.clamp((float) (dx * cos - dz * sin) * 100f, -20f, 20f);

        double moved = Math.sqrt((getX() - xo) * (getX() - xo) + (getZ() - zo) * (getZ() - zo));
        capeWalkDist += (float) moved * 0.6f;
        capeBob += ((onGround() ? Math.min(0.1f, (float) moved) : 0f) - capeBob) * 0.4f;
        lift += Mth.sin(capeWalkDist * 6f) * 32f * capeBob;

        capeBackO = capeBack;
        capeSideO = capeSide;
        capeLiftO = capeLift;
        capeBack += (back - capeBack) * 0.5f;
        capeSide += (side - capeSide) * 0.5f;
        capeLift += (lift - capeLift) * 0.5f;
    }

    /** Game time of this body's last server tick ({@link #bodyTickTime}). */
    public long lastBodyTickTime() {
        return bodyTickTime;
    }

    @Override
    public void tick() {
        if (!level().isClientSide) bodyTickTime = level().getGameTime();
        if (!level().isClientSide && isProfessionAnimationPreview()) holdProfessionPreviewInPlace();
        boolean chairPreview = !level().isClientSide && getTags().contains(CHAIR_PREVIEW_TAG);
        if (chairPreview) {
            if (!(getVehicle() instanceof ChairSeatEntity)) {
                discard();
                return;
            }
            if (!isNoAi()) setNoAi(true);
            if (getAnimation() != VillagerAnimationState.CHAIR_SIT)
                setAnimation(VillagerAnimationState.CHAIR_SIT);
        } else if (!level().isClientSide && !isChairSeated()
                && getAnimation() == VillagerAnimationState.CHAIR_SIT) {
            // Thrown off the seat without standing up (the chair was broken):
            // the sit pose would otherwise walk around with it.
            setAnimation(VillagerAnimationState.IDLE);
        }
        if (!level().isClientSide) expireDisplayItem();
        if (!level().isClientSide && villageLink != null) rejectDisallowedEquipment();
        if (!level().isClientSide && villageLink != null
                && (tickCount + getId()) % ARMOR_STOW_CHECK_TICKS == 0) stowArmorUnlessGuard();
        if (!level().isClientSide && isSleeping()) holdStillWhileSleeping();
        if (!level().isClientSide && isEating()) holdStillWhileEating();
        super.tick();
        if (level().isClientSide) {
            tickClientHurryPace();
            tickBubbleRenderState();
            if (getProfession() == Profession.CAPTAIN) moveCloak();
            tickHandOrbSparkles();
            tickTillDirt();
            tickChopBites();
            com.alex.test.client.SmithBlows.tick(this);
        }
        if (!level().isClientSide) {
            if (isProfessionAnimationPreview()) {
                tickProfessionAnimationPreview();
                return;
            }
            if (chairPreview) return;
            if (level() instanceof ServerLevel serverLevel && getProfession().isGuardLike()) {
                repairEquipmentFromExperience(serverLevel);
            }
            if (getProfession() == Profession.FARMER) farmerDirt.tick(this);
            // Everything that reads skills ensures them itself; this is only the
            // catch-up for villagers nobody looks at, so it runs every 1.5 s.
            if (com.alex.test.village.brain.TickStagger.isSlot(tickCount, getId(),
                    com.alex.test.village.brain.TickStagger.CHECK_PERIOD_TICKS)) {
                ensureProfessionSkillsInitialized();
            }
            if (bubbleTicks > 0 && --bubbleTicks == 0) clearBubble();
            tickHurryLease();
            settleRunLanding();
            tickMocapRootMotion();
            tickStairStepAssist();
            if (isSleeping()) holdStillWhileSleeping();
            if (isEating()) holdStillWhileEating();

            tickAnimationPreview();

            // Auto-return timed animations to IDLE
            if (animationTimer > 0 && --animationTimer == 0)
                setAnimation(VillagerAnimationState.IDLE);

            // Stagger the first wiggle so villagers spawned together do not all twitch at once.
            if (noseWiggleCooldown < 0) {
                noseWiggleCooldown = nextNoseWiggleDelay();
            } else if (--noseWiggleCooldown <= 0) {
                triggerAnim("idle_controller", "nose wiggle");
                noseWiggleCooldown = nextNoseWiggleDelay();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Display-only hand items
    // -------------------------------------------------------------------------

    private static final String DISPLAY_ONLY_KEY = "dynamicvillages_display_only";
    /** Game time a display item may stay in hand. Transient: after a reload it is gone at once. */
    private long displayHeldUntil;

    /**
     * Shows {@code stack} in the main hand until {@code untilGameTime}: a copy
     * for the eye only, such as the goods a tradesman holds up while selling.
     * It is tagged so it can never be dropped, and it clears itself when the
     * time is up, so a save in the middle of a scene cannot leave a real item
     * behind.
     */
    public void holdDisplayItem(ItemStack stack, long untilGameTime) {
        if (stack.isEmpty()) return;
        ItemStack display = stack.copyWithCount(1);
        net.minecraft.world.item.component.CustomData.update(
                net.minecraft.core.component.DataComponents.CUSTOM_DATA, display,
                tag -> tag.putBoolean(DISPLAY_ONLY_KEY, true));
        setDropChance(EquipmentSlot.MAINHAND, 0f);
        setItemSlot(EquipmentSlot.MAINHAND, display);
        displayHeldUntil = untilGameTime;
    }

    /** Keeps a display item already in hand up until {@code untilGameTime}. */
    public void extendDisplayItem(long untilGameTime) {
        if (isDisplayOnly(getMainHandItem())) displayHeldUntil = Math.max(displayHeldUntil, untilGameTime);
    }

    /** Nods once: a "yes" played over whatever else the villager is doing. Server side. */
    public void nod() {
        if (!level().isClientSide) triggerAnim("nod_controller", "nod");
    }

    /** Puts a display item away now; a real item in hand is left alone. */
    public void clearDisplayItem() {
        if (isDisplayOnly(getMainHandItem())) setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        displayHeldUntil = 0L;
    }

    public static boolean isDisplayOnly(ItemStack stack) {
        if (stack.isEmpty()) return false;
        var data = stack.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        // Read in place: this runs every tick, and copyTag() copied the whole tag for one flag.
        return data != null && data.getUnsafe().getBoolean(DISPLAY_ONLY_KEY);
    }

    private void expireDisplayItem() {
        if (displayHeldUntil > level().getGameTime()) return;
        if (isDisplayOnly(getMainHandItem())) setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
    }

    private static final EquipmentSlot[] CHECKED_EQUIPMENT_SLOTS = {EquipmentSlot.MAINHAND,
            EquipmentSlot.OFFHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** Legacy or command-inserted equipment the village does not own is dropped, never used. */
    private void rejectDisallowedEquipment() {
        for (EquipmentSlot slot : CHECKED_EQUIPMENT_SLOTS) {
            ItemStack worn = getItemBySlot(slot);
            if (worn.isEmpty() || getInventory().mayUse(worn)) continue;
            if (isDisplayOnly(worn)) {
                // A prop, not property: never dropped as a real item. One held
                // up right now stays until its time is up (expireDisplayItem);
                // put away at once, the next tick, nobody ever saw it.
                if (slot != EquipmentSlot.MAINHAND || displayHeldUntil <= level().getGameTime()) {
                    setItemSlot(slot, ItemStack.EMPTY);
                }
                continue;
            }
            com.alex.test.village.brain.ItemLossLog.drop(this, worn.copy(), "disallowed equipment in " + slot);
            setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /** How often a villager that is not a guard checks for armour to take off. */
    private static final int ARMOR_STOW_CHECK_TICKS = 40;

    /**
     * Only guards and captains wear armour. Anyone else (a guard given another job,
     * or one from a world saved before this) puts it in the pack, and the next
     * storage trip banks it for the next guard; a job change used to leave it on
     * for good, and the smith counted it as owned. A full pack keeps it on until
     * the next check. Called on the job change and every {@link #ARMOR_STOW_CHECK_TICKS}.
     */
    public void stowArmorUnlessGuard() {
        if (level().isClientSide || getProfession().isGuardLike()) return;
        for (EquipmentSlot slot : WORN_ARMOR_SLOTS) {
            ItemStack worn = getItemBySlot(slot);
            if (worn.isEmpty() || isDisplayOnly(worn)) continue;
            if (getInventory().addItem(worn.copy())) setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    /**
     * Villagers brush past berry bushes unharmed, as foxes and bees do. The
     * graph routes round bushes, but a smoothed corner or a shove can still
     * clip one, and farmers now tend whole rows of them.
     */
    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return source.is(net.minecraft.world.damagesource.DamageTypes.SWEET_BERRY_BUSH)
                || super.isInvulnerableTo(source);
    }

    /**
     * Villagers and visitors never go through a portal. A village lives in one
     * dimension: one pushed (or wandering) through a Nether portal had no map,
     * no brain and no way back, and stayed in the Mayor's list as "Unknown".
     */
    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return false;
    }

    @Override
    public boolean canChangeDimensions(net.minecraft.world.level.Level from, net.minecraft.world.level.Level to) {
        return false;
    }

    @Override
    public void makeStuckInBlock(BlockState state, net.minecraft.world.phys.Vec3 motionMultiplier) {
        if (state.is(Blocks.SWEET_BERRY_BUSH)) return;
        super.makeStuckInBlock(state, motionMultiplier);
    }

    /** Guard armor wears and breaks on hits just like a player's equipped armor. */
    @Override
    protected void hurtArmor(DamageSource source, float amount) {
        if (getProfession().isGuardLike()) {
            wearArmor(source, amount, EquipmentSlot.FEET, EquipmentSlot.LEGS,
                    EquipmentSlot.CHEST, EquipmentSlot.HEAD);
        }
    }

    @Override
    protected void hurtHelmet(DamageSource source, float amount) {
        if (getProfession().isGuardLike()) {
            wearArmor(source, amount, EquipmentSlot.HEAD);
        }
    }

    /** The stress test crowd (/villager stresstest): its armor, swords and shields never wear, in every mode. */
    public boolean keepsGearWhole() {
        return getTags().contains(com.alex.test.command.StressTestCommands.TAG);
    }

    /**
     * The village's own armor wears the vanilla way; a player's piece wears
     * faster, with its Unbreaking at its share (PlayerGear).
     */
    private void wearArmor(DamageSource source, float amount, EquipmentSlot... slots) {
        if (keepsGearWhole()) return;
        java.util.List<EquipmentSlot> plain = new java.util.ArrayList<>();
        for (EquipmentSlot slot : slots) {
            ItemStack worn = getItemBySlot(slot);
            if (level() instanceof ServerLevel server
                    && com.alex.test.village.tokens.PlayerGear.isPenalised(worn)) {
                wearPlayerArmor(server, source, amount, slot, worn);
            } else {
                plain.add(slot);
            }
        }
        if (!plain.isEmpty()) doHurtEquipment(source, amount, plain.toArray(EquipmentSlot[]::new));
    }

    /** Vanilla's armor wear for one hit, through PlayerGear.wear, breaking it the vanilla way. */
    private void wearPlayerArmor(ServerLevel server, DamageSource source, float amount,
                                 EquipmentSlot slot, ItemStack worn) {
        if (amount <= 0f || !(worn.getItem() instanceof net.minecraft.world.item.ArmorItem)
                || !worn.canBeHurtBy(source) || !worn.isDamageableItem()) return;
        int points = (int) Math.max(1f, amount / 4f);
        int damage = com.alex.test.village.tokens.PlayerGear.wear(server, worn, points);
        if (damage <= 0) return;
        int next = worn.getDamageValue() + damage;
        if (next < worn.getMaxDamage()) {
            worn.setDamageValue(next);
            return;
        }
        net.minecraft.world.item.Item item = worn.getItem();
        worn.shrink(1);
        onEquippedItemBroken(item, slot);
    }

    /**
     * Vanilla protection enchantments, with a player's armor counting only
     * its share (Protection IV on a player's iron chestplate works as II).
     * NeoForge takes the damage from the hit's damage container, not from this
     * method's result, so the lower total goes in as a modifier on the
     * container's enchantment reduction before vanilla records it.
     */
    @Override
    protected float getDamageAfterMagicAbsorb(DamageSource source, float damage) {
        if (level() instanceof ServerLevel server && !damageContainers.isEmpty()
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_EFFECTS)
                && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_ENCHANTMENTS)) {
            float lost = playerArmorProtectionLost(server, source);
            if (lost > 0f) {
                float total = net.minecraft.world.item.enchantment.EnchantmentHelper
                        .getDamageProtection(server, this, source);
                // Vanilla caps protection at 20; the reduction scales with the capped value.
                float full = Math.clamp(total, 0f, 20f);
                float kept = Math.clamp(total - lost, 0f, 20f);
                if (full > 0f && kept < full) {
                    float scale = kept / full;
                    damageContainers.peek().addModifier(
                            net.neoforged.neoforge.common.damagesource.DamageContainer.Reduction.ENCHANTMENTS,
                            (container, reduction) -> reduction * scale);
                }
            }
        }
        return super.getDamageAfterMagicAbsorb(source, damage);
    }

    /** Protection the player's armor pieces would give beyond their share. */
    private float playerArmorProtectionLost(ServerLevel server, DamageSource source) {
        float lost = 0f;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack worn = getItemBySlot(slot);
            var penalty = com.alex.test.village.tokens.PlayerGear.penalty(worn);
            if (penalty == com.alex.test.village.tokens.PlayerGear.Penalty.NONE || !worn.isEnchanted()) continue;
            var protection = new org.apache.commons.lang3.mutable.MutableFloat(0f);
            for (var entry : worn.getEnchantments().entrySet()) {
                // As vanilla: an enchantment only protects from the slot it belongs in.
                if (!entry.getKey().value().matchingSlot(slot)) continue;
                entry.getKey().value().modifyDamageProtection(server, entry.getIntValue(), worn, this,
                        source, protection);
            }
            lost += protection.floatValue() * (float) (1.0 - penalty.enchantShare);
        }
        return lost;
    }

    /** Let a guard's equipped Mending items use XP orbs it physically reaches. */
    private void repairEquipmentFromExperience(ServerLevel level) {
        if (tickCount % 2 != 0 || EnchantmentHelper.getRandomItemWith(
                EnchantmentEffectComponents.REPAIR_WITH_XP, this, ItemStack::isDamaged).isEmpty()) return;

        for (ExperienceOrb orb : level.getEntitiesOfClass(
                ExperienceOrb.class, getBoundingBox().inflate(0.25))) {
            if (orb.isRemoved()) continue;
            int remaining = orb.getValue()
                    * Math.max(1, orb.saveWithoutId(new CompoundTag()).getInt("Count"));
            int original = remaining;
            while (remaining > 0) {
                var candidate = EnchantmentHelper.getRandomItemWith(
                        EnchantmentEffectComponents.REPAIR_WITH_XP, this, ItemStack::isDamaged);
                if (candidate.isEmpty()) break;
                ItemStack stack = candidate.get().itemStack();
                int possible = EnchantmentHelper.modifyDurabilityToRepairFromXp(level, stack,
                        (int)(remaining * stack.getXpRepairRatio()));
                if (possible <= 0) break;
                int used = Math.min(possible, stack.getDamageValue());
                // A player's item mends only its share of what the orb pays for.
                int repaired = com.alex.test.village.tokens.PlayerGear.repair(stack, used, getRandom());
                stack.setDamageValue(stack.getDamageValue() - repaired);
                int spent = (int)((long)used * remaining / possible);
                // Still damaged after a short repair: always spend something, or this never ends.
                if (spent <= 0 && repaired < used) spent = 1;
                remaining -= spent;
            }
            if (remaining < original) orb.discard();
        }
    }

    // -------------------------------------------------------------------------
    // Death — drop carried items
    // -------------------------------------------------------------------------

    private static final EquipmentSlot[] WORN_ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /**
     * Nothing is left to vanilla's equipment roll (8.5%, only after a player's hit).
     * The hands only ever hold a copy of a pack item (a tool, a bow, a meal) or a
     * display item, so that roll dropped a free second one; worn armour is real and
     * is dropped whole below, with the pack. An override, because the saved
     * {@code HandDropChances} / {@code ArmorDropChances} would undo a chance set in
     * the constructor.
     */
    @Override
    protected float getEquipmentDropChance(EquipmentSlot slot) {
        EquipmentSlot.Type type = slot.getType();
        if (type == EquipmentSlot.Type.HAND || type == EquipmentSlot.Type.HUMANOID_ARMOR) return 0f;
        return super.getEquipmentDropChance(slot);
    }

    @Override
    protected void dropAllDeathLoot(ServerLevel level, DamageSource source) {
        super.dropAllDeathLoot(level, source);
        for (int i = 0; i < inventory.getSlots().length; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (stack.isEmpty()) continue;
            dropForPickup(stack);
        }
        // Armour is never in the pack: a guard killed by raiders used to lose its whole set.
        // Curse of Vanishing still vanishes, as vanilla's own roll would have it.
        for (EquipmentSlot slot : WORN_ARMOR_SLOTS) {
            ItemStack worn = getItemBySlot(slot);
            if (worn.isEmpty() || isDisplayOnly(worn)
                    || EnchantmentHelper.has(worn, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP)) continue;
            ItemStack piece = worn.copy();
            setItemSlot(slot, ItemStack.EMPTY);
            dropForPickup(piece);
        }
    }

    /**
     * The village's archers carry a fallen villager's gear back to storage once it
     * is safe; it used to vanish after five minutes.
     */
    private void dropForPickup(ItemStack stack) {
        net.minecraft.world.entity.item.ItemEntity dropped = spawnAtLocation(stack);
        if (dropped != null && villageLink != null) {
            com.alex.test.village.villagers.archer.behavior.FieldPickup.mark(dropped, villageLink.villageId());
        }
    }

    // -------------------------------------------------------------------------
    // MenuProvider — right-click opens inventory screen
    // -------------------------------------------------------------------------

    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            // A water bucket rinses a dirty villager instead of opening the menu;
            // with no dirt the click falls through to the menu as usual.
            if (tryNameTag(player, hand)) return InteractionResult.SUCCESS;
            ItemStack held = player.getItemInHand(hand);
            if (held.is(Items.WATER_BUCKET) && getDirt() > 0f) {
                rinseWithBucket(player, hand, held);
                return InteractionResult.SUCCESS;
            }

            InteractionResult jobAssignment =
                    VillagerJobManager.handleProfessionTokenUse(this, player, hand);
            if (jobAssignment.consumesAction()) return jobAssignment;

            // Blocks an order still needs go to the Builder instead of opening the screen (sneak to open it).
            if (player instanceof ServerPlayer giver && getProfession() == Profession.BUILDER && !giver.isShiftKeyDown()
                    && com.alex.test.village.villagers.builder.project.BuildOrders.giveFromHand(this, giver, hand)) {
                return InteractionResult.SUCCESS;
            }

            if (player instanceof ServerPlayer serverPlayer) {
                VillagerModel model = resolveVillageModel(serverPlayer.serverLevel());
                if (model != null && model.brain().suppressesUiAttention()) {
                    clearUiInteractionLock();
                } else {
                    markUiOpened(serverPlayer);
                }
                serverPlayer.openMenu(this, buffer -> {
                    VillagerMenu.writeOpeningData(buffer, this, serverPlayer);
                });
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.CONSUME;
    }

    /**
     * A player pours a water bucket over this villager: all dirt off, a splash,
     * and the bucket comes back empty (kept full in creative, as vanilla does).
     */
    private void rinseWithBucket(Player player, InteractionHand hand, ItemStack bucket) {
        setDirt(0f);
        player.setItemInHand(hand, BucketItem.getEmptySuccessItem(bucket, player));
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.BUCKET_EMPTY, SoundSource.PLAYERS, 1f, 1f);
        level().playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_SPLASH, SoundSource.NEUTRAL, 0.6f, 1.1f);
        if (level() instanceof ServerLevel serverLevel) {
            serverLevel.sendParticles(ParticleTypes.SPLASH, getX(), getY() + getBbHeight() * 0.8, getZ(),
                    40, getBbWidth() * 0.5, getBbHeight() * 0.35, getBbWidth() * 0.5, 0.2);
            serverLevel.sendParticles(ParticleTypes.FALLING_WATER, getX(), getY() + getBbHeight(), getZ(),
                    16, getBbWidth() * 0.4, 0.1, getBbWidth() * 0.4, 0.0);
        }
    }

    /**
     * The logical villager behind this puppet, found through its village link
     * and falling back to whichever village contains it. Public so that the
     * debug commands can reach a villager's brain from nothing but the entity
     * the player is looking at.
     */
    @Nullable
    public VillagerModel resolveVillageModel(ServerLevel level) {
        VillageManager manager = resolveVillageManager(level);
        return manager != null ? manager.getModel(getNpcId()) : null;
    }

    /** The village this NPC belongs to, by link or else by position; see {@link #resolveVillageModel}. */
    @Nullable
    public VillageManager resolveVillageManager(ServerLevel level) {
        VillageManager manager = null;
        if (villageLink != null) {
            manager = VillageManagerRegistry.get(level.getServer()).get(villageLink.villageId());
        }
        if (manager == null && villageLink != null
                && VillageManager.INSTANCE.getVillageId().equals(villageLink.villageId())) {
            manager = VillageManager.INSTANCE;
        }
        if (manager == null) {
            manager = VillageManagerRegistry.get(level.getServer()).findVillageAt(level, blockPosition());
        }
        return manager;
    }

    /**
     * Where a lead's rope meets this villager: its right hand, not the middle
     * of its chest. Only changes how the rope is drawn.
     */
    @Override
    public Vec3 getRopeHoldPosition(float partialTicks) {
        float yaw = Mth.lerp(partialTicks, yBodyRotO, yBodyRot) * Mth.DEG_TO_RAD;
        double sin = Mth.sin(yaw), cos = Mth.cos(yaw);
        double right = 0.35, forward = 0.25;
        Vec3 pos = getPosition(partialTicks);
        return new Vec3(
                pos.x - sin * forward - cos * right,
                pos.y + getBbHeight() * 0.6 * 0.95,
                pos.z + cos * forward - sin * right);
    }

    @Override
    public Component getDisplayName() {
        // On the server (chat, screen titles) from the name itself; on the client
        // the custom name, which holds the same name (showGivenName).
        String name = level().isClientSide ? null : givenName();
        if (name != null) return Component.literal(nameWithTitle(name));
        Component custom = getCustomName();
        return custom != null ? custom : Component.literal("Villager");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        ensureProfessionSkillsInitialized();
        return new VillagerMenu(id, this, player);
    }

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        ensureProfessionSkillsInitialized();
        tag.putUUID("NpcId", npcId);
        if (!level().isClientSide && villageLink != null) {
            try {
                VillageManagerRegistry.flushBrainForSave(this);
            } catch (Exception | LinkageError e) {
                // A throw here would make Minecraft leave this villager out of the save.
                long now = level().getGameTime();
                if (now >= saveFlushQuietUntil) {
                    saveFlushQuietUntil = now + 6000L;
                    DynamicVillages.LOGGER.warn("[FarAwake] Villager {}: writing its brain into the save failed;"
                            + " saved with the last 5 s copy.", npcId, e);
                }
            }
        }
        tag.put("BrainData", brainData.copy());
        tag.putString("Profession", getProfession().name());
        if (isProfessionAnimationPreview()) {
            tag.putFloat("ProfessionAnimationPreviewSpeed", entityData.get(WORK_ANIMATION_SPEED));
            if (professionPreviewOnly != null) tag.putInt("ProfessionAnimationPreviewOnly", professionPreviewOnly.getId());
            if (previewMusicTicks >= 0) tag.putBoolean("ProfessionAnimationPreviewMusic", true);
            if (previewStationPos != null) tag.putLong("ProfessionAnimationPreviewStation", previewStationPos.asLong());
        }
        if (getLumberjackSkin() >= 0) tag.putInt("LumberjackSkin", getLumberjackSkin());
        if (getFarmerSkin() >= 0) tag.putInt("FarmerSkin", getFarmerSkin());
        if (getBardSkin() >= 0) tag.putInt("BardSkin", getBardSkin());
        if (getMinerSkin() >= 0) tag.putInt("MinerSkin3", getMinerSkin());
        if (dirt > 0f) tag.putFloat("Dirt", dirt);
        if (lieDownFrom != null && isSleeping()) tag.putLong("LieDownFrom", lieDownFrom.asLong());
        if (!(this instanceof MayorEntity)) {
            tag.putBoolean("ProfessionSkillsInitialized", professionSkillsInitialized);
            tag.put("ProfessionSkills", ProfessionSkills.save(professionSkills));
        }
        if (villageLink != null) villageLink.save(tag);
        List<Tag> invTags = inventory.save(level().registryAccess());
        for (int i = 0; i < invTags.size(); i++) {
            tag.put("npc_inv" + i, invTags.get(i));
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        loadedFromSave = true;
        if (tag.hasUUID("NpcId")) npcId = tag.getUUID("NpcId");
        if (tag.contains("BrainData")) brainData = tag.getCompound("BrainData");
        lieDownFrom = tag.contains("LieDownFrom", Tag.TAG_LONG) ? BlockPos.of(tag.getLong("LieDownFrom")) : null;
        if (tag.contains("LumberjackSkin", Tag.TAG_INT)) {
            int skin = tag.getInt("LumberjackSkin");
            if (skin >= 0 && skin < 3) this.entityData.set(LUMBERJACK_SKIN, skin);
        }
        // Set before the profession below, so setProfession sees a skin and
        // does not roll a new one. A farmer saved before skins were stored
        // keeps the face it was already wearing.
        if (tag.contains("FarmerSkin", Tag.TAG_INT)) {
            int skin = tag.getInt("FarmerSkin");
            if (skin >= 0 && skin < FarmerSkinSequence.SKIN_COUNT) this.entityData.set(FARMER_SKIN, skin);
        } else if (tag.contains("Profession")
                && Profession.byName(tag.getString("Profession")) == Profession.FARMER) {
            this.entityData.set(FARMER_SKIN, legacyFarmerSkin());
        }
        // Bards saved before skins were stored all wore the one old skin, so
        // there is no face to keep: setProfession below deals them the next one.
        if (tag.contains("BardSkin", Tag.TAG_INT)) {
            int skin = tag.getInt("BardSkin");
            if (skin >= 0 && skin < BardSkinSequence.SKIN_COUNT) this.entityData.set(BARD_SKIN, skin);
        }
        // Likewise miners: they all wore the one tabard before.
        // "MinerSkin3" counts the three skins left after the modern hard-hat miner was
        // removed (10-17). The old "MinerSkin" counted four: 0 coal, 1 hard hat, 2 goggles,
        // 3 prospector. A hard-hat miner gets the next skin in the rotation instead.
        if (tag.contains("MinerSkin3", Tag.TAG_INT)) {
            int skin = tag.getInt("MinerSkin3");
            if (skin >= 0 && skin < MinerSkinSequence.SKIN_COUNT) this.entityData.set(MINER_SKIN, skin);
        } else if (tag.contains("MinerSkin", Tag.TAG_INT)) {
            int old = tag.getInt("MinerSkin");
            if (old == 0 || old == 2 || old == 3) {
                this.entityData.set(MINER_SKIN, old == 0 ? 0 : old - 1);
            } else if (old == 1 && level() instanceof ServerLevel serverLevel) {
                this.entityData.set(MINER_SKIN, MinerSkinSequence.nextSkin(serverLevel));
            }
        }
        if (tag.contains("Profession"))
            setProfession(Profession.byName(tag.getString("Profession")));
        // After the profession: setting it counts as a job change and wipes dirt.
        setDirt(tag.getFloat("Dirt"));
        villageLink = VillagerVillageLink.load(tag);

        List<Tag> invTags = new ArrayList<>();
        professionSkills = ProfessionSkills.load(tag, "ProfessionSkills");
        professionSkillsInitialized = tag.getBoolean("ProfessionSkillsInitialized")
                || tag.contains("ProfessionSkills", Tag.TAG_LIST);

        for (int i = 0; i < InventoryManager.SIZE; i++) {
            if (tag.contains("npc_inv" + i)) invTags.add(tag.get("npc_inv" + i));
        }
        if (!invTags.isEmpty()) {
            inventory = new InventoryManager();
            inventory.load(invTags, level().registryAccess());
        }
        // Saves from before 2026-10-16 hold "Bram · work", always shown; keep the name, hidden.
        if (givenName() != null) {
            showGivenName();
            setCustomNameVisible(false);
        }
        if (tag.contains("ProfessionAnimationPreviewSpeed", Tag.TAG_FLOAT)) {
            if (tag.contains("ProfessionAnimationPreviewOnly", Tag.TAG_INT))
                professionPreviewOnly = VillagerAnimationState.byId(tag.getInt("ProfessionAnimationPreviewOnly"));
            if (tag.getBoolean("ProfessionAnimationPreviewMusic")) previewMusicTicks = 0;
            if (tag.contains("ProfessionAnimationPreviewStation", Tag.TAG_LONG))
                previewStationPos = BlockPos.of(tag.getLong("ProfessionAnimationPreviewStation"));
            startProfessionAnimationPreview(tag.getFloat("ProfessionAnimationPreviewSpeed"));
        }
    }

    public UUID getNpcId() { return npcId; }
}
