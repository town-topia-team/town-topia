package com.alex.test.animation;

public enum VillagerAnimationState {
    IDLE(0),
    WAVE(1),
    EAT(2),
    ATTACK(3),
    WALK(4),
    CHOP(5),
    SITDOWN(6),
    SLEEP_SITTING_DOWN(7),
    SITUP(8),
    SLEEP_LYING_DOWN(9),
    THINK(10),
    ARM_ON_HIP(11),
    SHRUG(12),
    CRAFTING(13),
    /**
     * Working the soil with a hoe. Folded into CHOP before there was a clip for
     * it; the mocap set has one (act_till / mixamo_till), so the state exists and
     * is used by the farmer for its distinct tilling action.
     */
    TILL(14),
    /**
     * Pressing a seed into the soil (farmer_plant_sow, farmer_plant_press, baked in
     * tools/field): a low squat, the fist goes into the soil and the seed stays there.
     * Each job plays one of the two, picked on the client (NpcEntity.plantClip). Also
     * planting cane and berry bushes, and bone meal.
     */
    PLANT(15),
    /**
     * Pulling a farmland crop (farmer_harvest_yank: two hands, a handful a loop;
     * farmer_harvest_pluck: one hand, two plucks a loop). One of the two per job.
     */
    HARVEST(16),
    /**
     * One capture, two jobs. The take is a two-handed overhead hammer swing, which is
     * what mining a block and beating metal on an anvil both look like from outside;
     * they get separate states only so the call sites read as what the villager is
     * doing rather than as a borrowed animation.
     */
    MINE(17),
    FORGE(18),
    /** The cleric and druid dance. */
    DANCE(19),
    /** The hand-keyed salute; WAVE is the mocap cheer. */
    SALUTE(20),
    /** Held chair pose used by the stationary villager spawned with /villager sit. */
    CHAIR_SIT(21),
    /**
     * The violin on the move (bard_violin_walk): head, arms and bow only, over the
     * ordinary walk, so the walk's own torso carries the violin. The violin and bow hang
     * off the hips like the arms do, and the hands were solved onto them
     * (tools/violin/ViolinTool.java). Two bars of 150 BPM, the bow turning on every beat.
     */
    PLAY_VIOLIN(22),
    /** The violin at a stop with "dance at stops" on (bard_violin_dance): Mixamo's Charleston, six beats. */
    VIOLIN_DANCE(23),
    /**
     * The flute played on the move (bard_flute_walk): head and arms only, over the walk's
     * own legs and torso (tools/flute/FluteTool.java). Two bars of 150 BPM.
     */
    PLAY_FLUTE(24),
    /** The flute at a stop with "dance at stops" on (bard_flute_dance): Mixamo's Maraschino Step, eight beats. */
    FLUTE_DANCE(25),
    /** Holding an open book to read, standing or seated; the book prop shows only in this state. */
    READ(26),
    /**
     * Archer shooting: draws, holds at full draw, looses and follows through
     * (ki_bow_shot, base rig); the arrow leaves at {@link #AIM_RELEASE_TICKS}.
     */
    AIM(27),
    /**
     * Farmer picking fruit one block up (farmer_pick_mid, baked in tools/pick): the
     * left hand steadies the plant, the shears snip twice, the left hand catches and
     * pockets. 1 job in 3 plays farmer_pick_mid_look (holds a berry up to look at it).
     * Also cane one block up, and a cocoa bean set on a log. {@link #PICK_LOW},
     * {@link #PICK_BUSH} and {@link #PICK_HIGH} are the same pick at the feet and two up.
     */
    PICK(28),
    /**
     * Cleric throwing a healing orb (mixamo_magic_cast, Mixamo's Standing 1H
     * Magic Attack 01): the orb rests on the right palm, the arm winds back and
     * snaps forward, and the orb leaves at {@link #CAST_RELEASE_TICKS}.
     */
    CAST(29),
    /**
     * A defender psyching itself up at the barracks drill (mixamo_power_up,
     * Mixamo's Sword And Shield Power Up): crouches, flexes and roars.
     */
    POWER_UP(30),
    /**
     * A captain's front kick, at its armor stand or into a guard's shield
     * (mixamo_kick, Mixamo's Sword And Shield Kick); lands at {@link #KICK_CONTACT_TICKS}.
     */
    KICK(31),
    /**
     * The tavern kick line, as the dancer with its partner on its RIGHT
     * (mixamo_jazz_pair_r, Mixamo's Jazz Dancing): right arm across the
     * partner's upper back, left arm straight out, kicking in step.
     */
    JAZZ_PAIR_R(32),
    /** The same, as the dancer with its partner on its LEFT (mixamo_jazz_pair_l). */
    JAZZ_PAIR_L(33),
    /**
     * A guard at its post scanning the area (mixamo_look_around, Mixamo's
     * Looking): turns its head and shoulders about 40 degrees to its left, then
     * about 50 to its right, and back. The guard's real view direction follows
     * the head through {@code GuardLookAround}.
     */
    LOOK_AROUND(34),
    /**
     * Mage casting an Arcane Bolt (mixamo_mage_bolt, Mixamo's Standing 1H Magic
     * Attack 02): the casting hand winds back past the hip and flicks forward,
     * and the bolt leaves at {@link #BOLT_RELEASE_TICKS}. Bare-handed, like
     * every mage clip: the spell is light gathered in the hands.
     */
    MAGE_BOLT(35),
    /**
     * Frost Nova (mixamo_mage_nova, Standing 2H Magic Area Attack 01): both
     * arms rise and slam down; the nova bursts at {@link #NOVA_RELEASE_TICKS}.
     */
    MAGE_NOVA(36),
    /**
     * Chain Spark (mixamo_mage_chain, Standing 2H Magic Attack 01): both hands
     * draw back and thrust forward; the spark leaves at {@link #CHAIN_RELEASE_TICKS}.
     */
    MAGE_CHAIN(37),
    /**
     * Ward (mixamo_mage_ward, Standing 1H Cast Spell 01): one hand rises
     * calmly; the ward goes out at the top, {@link #WARD_RELEASE_TICKS}.
     */
    MAGE_WARD(38),
    /**
     * Starfall (mixamo_mage_starfall, Standing 2H Cast Spell 01): both arms
     * go up to the sky; the stars are called at {@link #STARFALL_RELEASE_TICKS}.
     */
    MAGE_STARFALL(39),
    /**
     * Enchanter writing a book (mixamo_enchant_weave, Mixamo's Two Hand Spell
     * Casting, the whole take looped): both hands gather and weave in front.
     */
    ENCHANT_WEAVE(40),
    /** A calmer stretch of writing (mixamo_enchant_ponder, Standing Idle 03's first 3.7 s, looped). */
    ENCHANT_PONDER(41),
    /**
     * Book done (mixamo_enchant_finish, Standing 2H Magic Attack 05 from 1.47 s):
     * rises, hands up, one clap at {@link #FINISH_CLAP_TICKS}.
     */
    ENCHANT_FINISH(42),
    /** Trance start (mixamo_enchant_seize, Zombie Agonizing 0.5-2.17 s): grabs the head, doubles over. */
    ENCHANT_SEIZE(43),
    /** Trance hold (mixamo_enchant_trance, a slowed copy of mixamo_mage_starfall): arms to the sky, head back. */
    ENCHANT_TRANCE(44),
    /** Trance end (mixamo_enchant_release, a copy of mixamo_mage_nova from 0.8 s): hands thrust to the ground. */
    ENCHANT_RELEASE(45),
    /**
     * The bard's upright bass (bard_bass_play), played standing: the body is
     * Mixamo's "Guitar Playing" at a calmer sway, the head "Lengthy Head Nod", the
     * hands solved onto the neck and the strings (tools/bass/BassTool.java). Two bars
     * of 150 BPM; the string lets go on every beat, {@link #BASS_BEAT_TICKS} apart.
     */
    BASS_PLAY(46),
    /** The same, livelier: the bass at stops with "dance at stops" on, and in the tavern set (bard_bass_jam). */
    BASS_JAM(47),
    /**
     * The violin played standing (bard_violin_play): the body is Mixamo's "Playing The
     * Violin", the head rests on the chinrest and holds still. At stops with "dance at
     * stops" off, and for the violin serenade.
     */
    VIOLIN_PLAY(48),
    /**
     * The flute played standing (bard_flute_play): the body is Mixamo's "Happy Idle", five
     * beats. At stops with "dance at stops" off.
     */
    FLUTE_PLAY(49),
    /**
     * The guitar strummed on the move (bard_guitar_walk): head and arms only, over the walk's
     * own legs and torso (tools/guitar/GuitarTool.java). Two bars of 150 BPM.
     */
    PLAY_GUITAR(50),
    /** The guitar at a stop with "dance at stops" on (bard_guitar_jam): Mixamo's bass guitar take, stepping, sixteen beats. */
    GUITAR_JAM(51),
    /** The guitar played standing (bard_guitar_play): Mixamo's calmer "Guitar Playing", twelve beats. */
    GUITAR_PLAY(52),
    /**
     * Working at a crafting table (craft_table, hand keyed in tools/crafting/CraftTool.java):
     * leaning in, a piece from the hip, three taps on the grid, the pieces worked together,
     * two knocks, a look at the result and setting it down; six seconds. Only for crafting
     * tables: everything else that works in place keeps {@link #CRAFTING}.
     */
    CRAFT_TABLE(53),

    /**
     * The farmer washing at a cauldron (farmer_wash, baked from the sketch in
     * tools/wash/farmer-wash-v1.html): sleeves up, a step in, two scoops, water to
     * the face and a head shake, each forearm and the hands scrubbed, a rinse, each
     * boot rubbed on the other calf, a step back, hands shaken dry and wiped on the
     * tunic; 11.2 s, played once and held. The body is drawn 2.5 px nearer the
     * cauldron while leaning in. WashActivity times its splashes to the clip.
     */
    WASH(54),

    /**
     * The lumberjack felling a tree: three sideways two-hand swings, side-on with his
     * left shoulder to the trunk ({@link LumberChopSwing}: bat swing, notch cutter, short
     * chops, baked in tools/chop/lumberjack-chop-sideways.html), one picked per tree and looped.
     * The axe follows the boxless axe_hold bone (HeldItemGeoLayer); the arms were solved
     * onto its handle. The body is drawn turned side-on and about 6 px left of the hitbox,
     * which still faces the trunk. The rancher's feeding keeps {@link #CHOP}.
     */
    LUMBER_CHOP(55),

    /**
     * A farmer of level 50 or more tilling (farmer_till_skilled, baked from the sketch in
     * tools/till/farmer-till-v3.html): down on one knee, one long smooth pull per stroke,
     * two rows side by side a loop. {@link #TILL} is the beginner's: bent double, the blade
     * catches and is wrenched free. The hoe sits in the fist as in the walking grip for both.
     */
    TILL_SKILLED(56),

    /** Picking fruit level with the feet, down on the right knee (farmer_pick_low, 1 job in 3 _low_look). */
    PICK_LOW(57),

    /**
     * A farmer below {@code FarmerActivity.SKILLED_PICK_LEVEL} picking a berry bush: as
     * {@link #PICK_LOW}, but 1 job in 3 the thorn pricks him (farmer_pick_low_thorn).
     */
    PICK_BUSH(58),

    /** Picking fruit two blocks up, on his toes (farmer_pick_high, 1 job in 3 _high_look). */
    PICK_HIGH(59),

    /** Cutting a melon or pumpkin off its stem with the axe: the overhead chop down (mixamo_attack_down). */
    HARVEST_CHOP(60),

    /*
     * The blacksmith's work clips, hand-keyed against the block (tools/forge/ForgeTool.java).
     * Each loops until the activity changes state; the server picks the variant per job.
     */
    /** Forging at the anvil, steady: three blows, turns the piece, two blows, checks the edge (smith_anvil_steady). */
    SMITH_ANVIL(61),
    /** Forging at the anvil, heavy: a high wind-up and two slams, then shakes out the hand (smith_anvil_heavy). */
    SMITH_ANVIL_HEAVY(62),
    /** Fitting a book at the anvil: reads it, presses it onto the tool, rubs it in, sees the shine (smith_enchant_press). */
    SMITH_ENCHANT(63),
    /** Fitting a book at the anvil: taps the tool while reading, holds it to the book, nods (smith_enchant_tap). */
    SMITH_ENCHANT_TAP(64),
    /** Netherite at the smithing table: template in, the piece in, bears down, looks it over (smith_smithing). */
    SMITH_SMITHING(65),
    /** Breaking an old tool down at the anvil: quick blows, prises it apart, tosses the scrap (smith_recycle). */
    SMITH_RECYCLE(66),
    /** Smelting: raw ore into the furnace mouth, shakes the hot hand, peeks in (smith_furnace). */
    SMITH_FURNACE(67),
    /**
     * The tavern band's bongos (bard_bongos_play), played seated on a low stool with the drums in
     * front of the shins (tools/bongos/BongosTool.java): the right hand on the big drum, the left on
     * the small one, two bars of the band song's 6/8. Clip time 0 is song time 0: BardActivity starts
     * it so it lines up with the song ({@link #BONGOS_PLAY_TICKS}).
     */
    BONGOS_PLAY(68),
    /** The same, joyful (bard_bongos_jam): sways, nods, taps a foot, a right-hand flourish every fourth bar. */
    BONGOS_JAM(69),
    /**
     * Sitting down with the back to a wall (wall_sitdown, baked in tools/wallsleep): slides back
     * onto the seat, knees up and apart, hands over the knees, eyes shut at the end. Held on its
     * last frame, which is wall_sleep's first. Stand on the tile middle, back to the wall.
     */
    WALL_SITDOWN(70),
    /** Asleep against the wall (wall_sleep): slow breathing, head dropped to one side, eyes shut. */
    WALL_SLEEP(71),
    /** Standing up from the wall (wall_situp): eyes open, arms swing forward, up onto the feet. Held. */
    WALL_SITUP(72),

    /*
     * The cook's work clips (docs/plans/2026-10-24-cook-animations.md). CookActivity owns them all;
     * CookMoves plans which play in a batch. Every "once" clip starts and ends on the shared ready
     * pose and holds its last frame, so any one can follow any other. The table ones loop.
     */
    /** Kneading bread: pushes the dough away, folds, slaps it down, dusts off, a proud pat (cook_knead, 4.5 s loop). */
    COOK_KNEAD(73),
    /** Cookies: presses four shapes, lifts one, sniffs, nods (cook_cookies, 4.5 s loop). */
    COOK_COOKIES(74),
    /** Cake: bowl on the left hip, beats with the spoon, pours, pipes one line, steps back (cook_whisk, 4.5 s loop). */
    COOK_WHISK(75),
    /** Pumpkin pie: crimps the crust with both thumbs, taps the top twice, turns it a hair (cook_pie, 4.5 s loop). */
    COOK_PIE(76),
    /** Stew and soup: four chops, swept into the bowl, stirs, tastes, a pinch of salt (cook_chop_stir, 4.5 s loop). */
    COOK_CHOP_STIR(77),
    /** Golden carrot or apple: rubs the gold on, turns it to the light, polishes it on the apron (cook_gild, 4.5 s loop). */
    COOK_GILD(78),
    /** Bowls and planks: knife strokes, blows the shavings off, looks it over (cook_whittle, 4.5 s loop). */
    COOK_WHITTLE(79),
    /** Sugar: wrings the cane like a cloth, taps the sugar out, brushes the hands (cook_press_cane, 4.5 s loop). */
    COOK_PRESS_CANE(80),
    /**
     * Lighting a cold furnace or smoker: crouches, feeds the fuel into the low mouth (in at
     * {@link #COOK_FIRE_LIGHT_IN_TICKS}), stands, dusts the hands (cook_fire_light, 3.0 s once).
     */
    COOK_FIRE_LIGHT(81),
    /** More fuel: a quick bow at the waist, tosses it in, back up (cook_fire_feed, 1.6 s once). */
    COOK_FIRE_FEED(82),
    /** Raw food slid into the top mouth, fingers wiped on the apron (cook_load, 2.2 s once). */
    COOK_LOAD(83),
    /** Waiting at the heat: bends to peek, squints, a small nod (cook_wait_peek, 2.5 s once). */
    COOK_WAIT_PEEK(84),
    /** Waiting at the heat: hands on hips, foot tap, looks round the kitchen (cook_wait_hips, 2.5 s once). */
    COOK_WAIT_HIPS(85),
    /** Waiting at the heat: taps the spoon in the left palm, glances at the fire (cook_wait_spoon, 2.5 s once). */
    COOK_WAIT_SPOON(86),
    /** Waiting at the heat: straightens the hat, brushes the apron (cook_wait_tidy, 2.5 s once). */
    COOK_WAIT_TIDY(87),
    /** Waiting at a smoker only: waves the smoke off, one dry cough (cook_wait_smoke, 2.5 s once). */
    COOK_WAIT_SMOKE(88),
    /** Taking the dish out, ends on the batch end: pulls it out, sniffs, tucks it at the hip (cook_take, 2.4 s once). */
    COOK_TAKE(89),
    /** Campfire: crouches, lays the food on the fire, settles it with a fingertip (cook_camp_lay, 3.0 s once). */
    COOK_CAMP_LAY(90),
    /** Campfire: crouches, turns the food with the spoon, wafts the smoke to the nose (cook_camp_turn, 3.0 s once). */
    COOK_CAMP_TURN(91),
    /** Campfire: arms folded, weight shifts, a look down at the fire, a tut (cook_camp_wait, 4.0 s loop). */
    COOK_CAMP_WAIT(92),
    /** Campfire, ends on the batch end: lifts it off, blows on it, holds it up (cook_camp_take, 2.5 s once). */
    COOK_CAMP_TAKE(93),
    /** Waiting for a busy station: arms folded, foot taps, a finger wag at it (cook_wait_station, 4.0 s loop). */
    COOK_WAIT_STATION(94),
    /** Last batch of a showpiece: holds the dish up, turns it a little, a small bow (cook_present, 2.6 s once). */
    COOK_PRESENT(95),
    /** The batch after a bonus dish: fingertips to the lips, the chef's kiss (cook_kiss, 1.8 s once). */
    COOK_KISS(96),
    /** First batch of the shift: tugs each sleeve up, straightens the hat, one clap (cook_sleeves, 2.6 s once). */
    COOK_SLEEVES(97),

    /**
     * Drawing out a bar at the anvil (tools/anvil, 10-24): three heavy blows, a quarter turn of the bar,
     * three more, then he lifts it and looks along it (smith_anvil_draw, 6.2 s loop). The hammer is held
     * like a player's tool (client SmithHammerHold), so its face lands edge first.
     */
    SMITH_ANVIL_DRAW(98);


    private final int id;

    /** GeckoLib transition used by the shared work controller. */
    public static final int WORK_BLEND_TICKS = 8;

    /**
     * Where mixamo_magic_cast lets the orb go, in clip ticks after the blend:
     * the forearm straightens fastest between 0.77 s and 0.87 s, and the hand
     * is at the front of that snap at 0.85 s.
     */
    public static final int CAST_RELEASE_TICKS = 17;

    /**
     * The cleric's throw blends in over this many clip ticks, half again the
     * work blend: a preview (10-04) of longer GeckoLib blends, tried on this
     * one clip first because switches between mocap clips read as a snap.
     */
    public static final int CAST_BLEND_TICKS = 12;

    /**
     * Where mixamo_mage_bolt lets the bolt go, in clip ticks after the blend:
     * the hand is back by the hip at 1.00 s and snaps forward fastest at
     * 1.30-1.35 s, coming level with the chest at 1.35 s.
     */
    public static final int BOLT_RELEASE_TICKS = 27;

    // The other mage casts' release frames, in clip ticks after the blend, read
    // off the shipped clips by forward kinematics (tools/mocap/render_anim.py
    // pose(), forearm ends): where the hands do the thing the spell is.

    /** mixamo_mage_nova: both hands come down fastest at 1.23-1.27 s and bottom out at 1.30 s. */
    public static final int NOVA_RELEASE_TICKS = 26;
    /** mixamo_mage_chain: both hands thrust forward fastest at 1.13 s, level with the chest at 1.17 s. */
    public static final int CHAIN_RELEASE_TICKS = 23;
    /** mixamo_mage_ward: the right hand rises fastest at 0.57 s and is highest at 0.87 s. */
    public static final int WARD_RELEASE_TICKS = 17;
    /** mixamo_mage_starfall: both arms rise fastest at 1.23-1.27 s and are highest at 1.50 s. */
    public static final int STARFALL_RELEASE_TICKS = 30;
    /** mixamo_enchant_finish: the hands meet at 0.73 s. */
    public static final int FINISH_CLAP_TICKS = 15;

    /** cook_fire_light: the fuel goes into the low mouth at 1.40 s. */
    public static final int COOK_FIRE_LIGHT_IN_TICKS = 28;

    // Blending back out of the throw is AnimationBlend.Group.CAST (10 ticks by default).

    /**
     * Gesture states (salute, power-up, kick) blend in over this many ticks
     * before their clip starts; matches NpcEntity's gesture controller.
     */
    public static final int GESTURE_BLEND_TICKS = 6;

    /** mixamo_power_up from start to end, its blend in included (2.37 s clip). */
    public static final int POWER_UP_TICKS = GESTURE_BLEND_TICKS + 47;

    /** The power-up's yell: 0.7 s in, as the arms finish throwing out into the roar. */
    public static final int POWER_UP_YELL_TICKS = GESTURE_BLEND_TICKS + 14;

    /** mixamo_look_around from start to end, its blend in included (8 s clip). */
    public static final int LOOK_AROUND_TICKS = GESTURE_BLEND_TICKS + 160;

    /** mixamo_attack_down's length (2.27 s), without its blend: the butcher's chop. */
    public static final int ATTACK_DOWN_TICKS = 45;

    /**
     * Where mixamo_attack_down's blade lands, as a share of the clip: the arm is
     * overhead at 0.67 s, drops fastest from there and is down by 0.80 s.
     */
    public static final float ATTACK_DOWN_CONTACT = 0.80f / 2.2667f;

    /** mixamo_kick from start to end, its blend in included (1.2 s clip). */
    public static final int KICK_TICKS = GESTURE_BLEND_TICKS + 24;

    /** When the kicking foot is out at full reach: 0.6 s into the clip. */
    public static final int KICK_CONTACT_TICKS = GESTURE_BLEND_TICKS + 12;

    /**
     * Where ki_bow_shot lets the string go: the drawing hand is still on frame 21
     * (0.84 s) and has sprung back by frame 22 (0.88 s).
     */
    public static final int AIM_RELEASE_TICKS = 17;

    /** ki_bow_shot's exact length (1.64 s), for following it through loops. */
    public static final float AIM_CLIP_TICKS = 32.8f;

    /**
     * How far ki_bow_shot has drawn the string, as clip ticks -> 0..1: the gap
     * between the two hands in the take, from the hand taking the string (39 cm)
     * to full draw (84 cm). Vanilla's bow shows its middle sprite from 0.65 and
     * the full one from 0.9.
     */
    private static final float[] AIM_DRAW_AT = {2.4f, 3.2f, 4.0f, 4.8f, 5.6f, 6.4f, 7.2f};
    private static final float[] AIM_DRAW = {0f, 0.04f, 0.14f, 0.44f, 0.83f, 0.96f, 1f};

    /** How far the string is drawn {@code clipTicks} into ki_bow_shot, 0..1. */
    public static float aimDraw(float clipTicks) {
        if (clipTicks <= AIM_DRAW_AT[0]) return AIM_DRAW[0];
        for (int i = 1; i < AIM_DRAW_AT.length; i++) {
            if (clipTicks < AIM_DRAW_AT[i]) {
                float u = (clipTicks - AIM_DRAW_AT[i - 1]) / (AIM_DRAW_AT[i] - AIM_DRAW_AT[i - 1]);
                return AIM_DRAW[i - 1] + (AIM_DRAW[i] - AIM_DRAW[i - 1]) * u;
            }
        }
        return 1f;
    }

    VillagerAnimationState(int id) {
        this.id = id;
    }

    public int getId() {
        return id;
    }

    /**
     * Ticks between plucks in the bass clips: 150 BPM, the bard's tempo. The clip
     * lets the string go on its beats at 0.0, 0.4, 0.8 ... s.
     */
    public static final int BASS_BEAT_TICKS = 8;

    /** The bard playing its bass: the bass is drawn and the hands are empty. */
    public boolean isBass() {
        return this == BASS_PLAY || this == BASS_JAM;
    }

    /** The bard playing its violin: violin and bow are drawn and the hands are empty. */
    public boolean isViolin() {
        return this == PLAY_VIOLIN || this == VIOLIN_DANCE || this == VIOLIN_PLAY;
    }

    /** The bard playing its flute: the flute is drawn at the mouth and the hands are empty. */
    public boolean isFlute() {
        return this == PLAY_FLUTE || this == FLUTE_DANCE || this == FLUTE_PLAY;
    }

    /** The bard playing its guitar: the guitar is drawn across the front and the hands are empty. */
    public boolean isGuitar() {
        return this == PLAY_GUITAR || this == GUITAR_JAM || this == GUITAR_PLAY;
    }

    /** The bard at the bongos: seated, the stool and the drums are drawn and the hands are empty. */
    public boolean isBongos() {
        return this == BONGOS_PLAY || this == BONGOS_JAM;
    }

    /**
     * Length of the bongo clips in ticks: 12 and 24 eighths of the band song (0.21469 s an eighth,
     * fitted to the recording). Not whole ticks: the band song is not a whole number of them either.
     */
    public static final float BONGOS_PLAY_TICKS = 51.526f, BONGOS_JAM_TICKS = 103.05f;

    /** A bard clip whose bow strokes, plucks, strums or steps land on the beat: it plays at its own tempo. */
    public boolean isOnTheBeat() {
        return isBass() || isViolin() || isFlute() || isGuitar() || isBongos();
    }

    /** One of the mage's casts: paced to its release frame, so sadness never slows it. */
    public boolean isMageCast() {
        return this == MAGE_BOLT || this == MAGE_NOVA || this == MAGE_CHAIN
                || this == MAGE_WARD || this == MAGE_STARFALL;
    }

    /** The farmer's picking clips (shears snip, left hand catches): one, two up, at the feet, at a bush. */
    public boolean isFarmerPick() {
        return this == PICK || this == PICK_LOW || this == PICK_BUSH || this == PICK_HIGH;
    }

    /** The blacksmith at the anvil with its hammer: forging or breaking a tool down. */
    public boolean isSmithHammering() {
        return this == SMITH_ANVIL || this == SMITH_ANVIL_HEAVY || this == SMITH_RECYCLE || this == SMITH_ANVIL_DRAW;
    }

    /** Any of the blacksmith's work clips. */
    public boolean isSmithWork() {
        return isSmithHammering() || this == SMITH_ENCHANT || this == SMITH_ENCHANT_TAP
                || this == SMITH_SMITHING || this == SMITH_FURNACE;
    }

    /** A farmer at the soil or a plant: tilling, planting, harvesting, picking, cutting a melon. */
    public boolean isFarmWork() {
        return this == TILL || this == TILL_SKILLED || this == PLANT || this == HARVEST
                || this == HARVEST_CHOP || isFarmerPick();
    }

    /** Sitting down against a wall, asleep there, or getting up from it (tools/wallsleep clips). */
    public boolean isWallSleep() {
        return this == WALL_SITDOWN || this == WALL_SLEEP || this == WALL_SITUP;
    }

    /** Any of the cook's own clips (ids 73-97). */
    public boolean isCookWork() {
        return id >= COOK_KNEAD.id && id <= COOK_SLEEVES.id;
    }

    /** A cook clip that loops: the table dishes, the campfire wait and the wait for a busy station. */
    public boolean isCookLoop() {
        return isCookTable() || this == COOK_CAMP_WAIT || this == COOK_WAIT_STATION;
    }

    /** The cook working a dish at the table (cook_knead .. cook_press_cane). */
    public boolean isCookTable() {
        return id >= COOK_KNEAD.id && id <= COOK_PRESS_CANE.id;
    }

    public static VillagerAnimationState byId(int id) {
        for (VillagerAnimationState state : values()) {
            if (state.id == id) return state;
        }
        return IDLE;
    }

    public static float getDuration(VillagerAnimationState state){
        switch (state){
            case IDLE -> {
                return Float.POSITIVE_INFINITY;
            }
            case EAT -> {
                return Float.POSITIVE_INFINITY; // EatActivity manages its own lifetime
            }
            case WAVE -> {
                // mixamo_cheer is 2.9 s (58 ticks) plus the 6-tick blend in. The clip
                // is held on its last frame, which has both arms up, so any longer
                // and the villager froze with its hands in the air.
                return 64;
            }
            case SALUTE -> {
                return 60; // 3 s: 1 s to raise the hand, then held
            }
            case ATTACK -> {
                return 30;
            }
            case WALK -> {
                return Float.POSITIVE_INFINITY;
            }
            case CHOP -> {
                return 20;
            }
            case LUMBER_CHOP -> {
                // ChopTreeActivity renews it every 19 ticks at axe speed 1 (CHOP_ANIM_PERIOD / speed).
                return 40;
            }
            case TILL, TILL_SKILLED -> {
                return Float.POSITIVE_INFINITY; // FarmerActivity owns the full work bout
            }
            case SITDOWN, SITUP, SLEEP_SITTING_DOWN, SLEEP_LYING_DOWN, CHAIR_SIT,
                 WALL_SITDOWN, WALL_SLEEP, WALL_SITUP -> {
                return Float.POSITIVE_INFINITY;
            }
            case MINE, FORGE -> { return 60; }   // 3 s
            case DANCE       -> { return 60; }   // 3 s
            case PLAY_VIOLIN, VIOLIN_DANCE, VIOLIN_PLAY, PLAY_FLUTE, FLUTE_DANCE, FLUTE_PLAY,
                 PLAY_GUITAR, GUITAR_JAM, GUITAR_PLAY -> {
                return Float.POSITIVE_INFINITY;
            }
            case PLANT, HARVEST, PICK, PICK_LOW, PICK_BUSH, PICK_HIGH, HARVEST_CHOP -> {
                return Float.POSITIVE_INFINITY; // FarmerActivity owns the full work bout
            }
            case THINK       -> { return 70; }   // 3.5 s
            case ARM_ON_HIP  -> { return 80; }   // 4 s
            case SHRUG       -> { return 40; }   // 2 s
            case CRAFTING, CRAFT_TABLE, WASH -> { return Float.POSITIVE_INFINITY; }
            case SMITH_ANVIL, SMITH_ANVIL_HEAVY, SMITH_ENCHANT, SMITH_ENCHANT_TAP, SMITH_SMITHING,
                 SMITH_RECYCLE, SMITH_FURNACE, SMITH_ANVIL_DRAW -> { return Float.POSITIVE_INFINITY; } // BlacksmithActivity / BookFitting own it
            case READ        -> { return Float.POSITIVE_INFINITY; } // LibraryActivity owns the session
            case AIM         -> { return 80; }   // the shot and its follow-through
            case CAST        -> { return Float.POSITIVE_INFINITY; } // ClericActivity paces each throw
            case POWER_UP    -> { return POWER_UP_TICKS + 2; }
            case KICK        -> { return KICK_TICKS + 2; }
            case LOOK_AROUND -> { return LOOK_AROUND_TICKS + 2; }
            case MAGE_BOLT   -> { return 60; }   // the cast and its follow-through
            // MageActivity ends each cast itself; these only bound a cast left running.
            case MAGE_NOVA   -> { return 70; }
            case MAGE_CHAIN  -> { return 64; }
            case MAGE_WARD   -> { return 54; }
            case MAGE_STARFALL -> { return 52; }
            // EnchanterActivity owns the writing loops; the trance ones are picked on the client.
            case ENCHANT_WEAVE, ENCHANT_PONDER, ENCHANT_TRANCE -> { return Float.POSITIVE_INFINITY; }
            case ENCHANT_FINISH  -> { return 40; }
            case ENCHANT_SEIZE   -> { return 36; }
            case ENCHANT_RELEASE -> { return 32; }
            case JAZZ_PAIR_R, JAZZ_PAIR_L -> { return Float.POSITIVE_INFINITY; } // PairDanceActivity ends it
            case BASS_PLAY, BASS_JAM -> { return Float.POSITIVE_INFINITY; } // BardActivity holds it
            case BONGOS_PLAY, BONGOS_JAM -> { return Float.POSITIVE_INFINITY; } // BardActivity holds it
            case COOK_KNEAD, COOK_COOKIES, COOK_WHISK, COOK_PIE, COOK_CHOP_STIR, COOK_GILD, COOK_WHITTLE,
                 COOK_PRESS_CANE, COOK_FIRE_LIGHT, COOK_FIRE_FEED, COOK_LOAD, COOK_WAIT_PEEK, COOK_WAIT_HIPS,
                 COOK_WAIT_SPOON, COOK_WAIT_TIDY, COOK_WAIT_SMOKE, COOK_TAKE, COOK_CAMP_LAY, COOK_CAMP_TURN,
                 COOK_CAMP_WAIT, COOK_CAMP_TAKE, COOK_WAIT_STATION, COOK_PRESENT, COOK_KISS,
                 COOK_SLEEVES -> { return Float.POSITIVE_INFINITY; } // CookActivity owns them
            default -> {
                return 60;
            }
        }

    }

    /**
     * {@code ticks} rounded to a whole number of loops of {@code state}'s clip,
     * never shorter than one loop.
     *
     * <p>A work bout that is not a whole number of loops cuts the clip off
     * part-way and then restarts it from frame zero for the next bout, which
     * reads as the villager snapping back mid-movement. It is worst where the
     * bout is shorter than the clip: the druid's 30-tick tend against a
     * 39-tick dance never once reached the end of the loop, and got further
     * from it the faster the druid worked, since {@code workTicks} divides the
     * bout by work speed.
     *
     * <p>Nearest rather than up, so rounding does not quietly change a
     * profession's work rate; one full loop is the floor.
     */
    public static int wholeCycles(VillagerAnimationState state, int ticks) {
        int cycle = cycleTicks(state);
        return Math.max(cycle, Math.round((float) ticks / cycle) * cycle);
    }

    /** Full clip length from the shipped villager animation JSON, rounded to ticks. */
    public static int cycleTicks(VillagerAnimationState state) {
        return switch (state) {
            case CHOP    -> 48;   // rok_chop     2.4000s
            case LUMBER_CHOP -> LumberChopSwing.BAT.loopTicks; // lumberjack_chop_bat 1.45s; _notch 2.8s, _short 1.6s (LumberChopSwing)
            case TILL, TILL_SKILLED -> 35;   // one pull of farmer_till_* (70 ticks = two pulls, 210 = six)
            case PLANT   -> 43;   // farmer_plant_sow / _press 2.1500s
            case HARVEST -> 43;   // farmer_harvest_yank / _pluck 2.1500s
            // farmer_pick_low / _mid / _high 2.2000s; the _look and _thorn ones are four picks (8.8 s)
            case PICK, PICK_LOW, PICK_BUSH, PICK_HIGH -> 44;
            case HARVEST_CHOP -> ATTACK_DOWN_TICKS; // mixamo_attack_down 2.2667s
            case MINE, FORGE -> 50;   // rok_mine     2.5000s
            case CRAFTING -> 45;      // mixamo_attack_down 2.2667s
            case CRAFT_TABLE -> 120;  // craft_table 6.0000s
            case WASH -> 224;         // farmer_wash 11.2000s, played once
            case SMITH_ANVIL -> 80;        // smith_anvil_steady 4.0000s
            case SMITH_ANVIL_HEAVY -> 88;  // smith_anvil_heavy 4.4000s
            case SMITH_RECYCLE -> 80;      // smith_recycle 4.0000s
            case SMITH_ANVIL_DRAW -> 124;  // smith_anvil_draw 6.2000s
            case SMITH_ENCHANT, SMITH_ENCHANT_TAP, SMITH_SMITHING, SMITH_FURNACE -> 100; // smith_enchant_press / _tap, smith_smithing, smith_furnace 5.0000s
            case DANCE   -> 37;   // rok_dance    1.8667s (nearest whole tick)
            case PLAY_FLUTE, FLUTE_DANCE -> 64; // bard_flute_walk / _dance 3.2000s, eight beats
            case FLUTE_PLAY -> 40;              // bard_flute_play 2.0000s, five beats
            case PLAY_GUITAR -> 64;             // bard_guitar_walk 3.2000s, eight beats
            case GUITAR_PLAY -> 96;             // bard_guitar_play 4.8000s, twelve beats
            case GUITAR_JAM -> 128;             // bard_guitar_jam 6.4000s, sixteen beats
            case PLAY_VIOLIN, VIOLIN_PLAY -> 64; // bard_violin_walk / _play 3.2000s, eight beats
            case VIOLIN_DANCE -> 48;            // bard_violin_dance 2.4000s, six beats
            case ATTACK  -> 33;   // mixamo_slash 1.6667s
            case AIM     -> 33;   // ki_bow_shot  1.6400s
            case CAST    -> 46;   // mixamo_magic_cast 2.3000s
            case POWER_UP -> 47;  // mixamo_power_up 2.3667s
            case KICK    -> 24;   // mixamo_kick 1.2000s
            case MAGE_BOLT -> 44; // mixamo_mage_bolt 2.2000s
            case MAGE_NOVA -> 59; // mixamo_mage_nova 2.9667s
            case MAGE_CHAIN -> 54; // mixamo_mage_chain 2.7000s
            case MAGE_WARD -> 45; // mixamo_mage_ward 2.2667s
            case MAGE_STARFALL -> 43; // mixamo_mage_starfall 2.1667s
            case ENCHANT_WEAVE -> 69;   // mixamo_enchant_weave 3.4333s
            case ENCHANT_PONDER -> 73;  // mixamo_enchant_ponder 3.6667s
            case ENCHANT_FINISH -> 37;  // mixamo_enchant_finish 1.8333s
            case ENCHANT_SEIZE -> 33;   // mixamo_enchant_seize 1.6667s
            case ENCHANT_TRANCE -> 64;  // mixamo_enchant_trance 3.2000s
            case ENCHANT_RELEASE -> 29; // mixamo_enchant_release 1.4667s
            case JAZZ_PAIR_R, JAZZ_PAIR_L -> 36; // mixamo_jazz_pair_* 1.8000s, one left and one right kick
            case BASS_PLAY, BASS_JAM -> 64;      // bard_bass_* 3.2000s, eight beats
            case BONGOS_PLAY -> 52;              // bard_bongos_play 2.5763s (BONGOS_PLAY_TICKS)
            case BONGOS_JAM -> 103;              // bard_bongos_jam 5.1526s (BONGOS_JAM_TICKS)
            case WALL_SITDOWN -> 44;             // wall_sitdown 2.2000s, held
            case WALL_SLEEP -> 160;              // wall_sleep 8.0000s
            case WALL_SITUP -> 48;               // wall_situp 2.4000s, held
            // cook_knead, _cookies, _whisk, _pie, _chop_stir, _gild, _whittle, _press_cane 4.5000s, looped
            case COOK_KNEAD, COOK_COOKIES, COOK_WHISK, COOK_PIE, COOK_CHOP_STIR, COOK_GILD, COOK_WHITTLE,
                 COOK_PRESS_CANE -> 90;
            case COOK_FIRE_LIGHT, COOK_CAMP_LAY, COOK_CAMP_TURN -> 60; // cook_fire_light, _camp_lay, _camp_turn 3.0000s, held
            case COOK_FIRE_FEED -> 32;           // cook_fire_feed 1.6000s, held
            case COOK_LOAD -> 44;                // cook_load 2.2000s, held
            case COOK_WAIT_PEEK, COOK_WAIT_HIPS, COOK_WAIT_SPOON, COOK_WAIT_TIDY, COOK_WAIT_SMOKE,
                 COOK_CAMP_TAKE -> 50;           // cook_wait_* 2.5000s, cook_camp_take 2.5000s, held
            case COOK_TAKE -> 48;                // cook_take 2.4000s, held
            case COOK_CAMP_WAIT, COOK_WAIT_STATION -> 80; // cook_camp_wait, _wait_station 4.0000s, looped
            case COOK_PRESENT, COOK_SLEEVES -> 52; // cook_present, cook_sleeves 2.6000s, held
            case COOK_KISS -> 36;                // cook_kiss 1.8000s, held
            default      -> 20;
        };
    }
}
