package com.alex.test.ui.screen;

import com.alex.test.village.villagers.archer.ArcherBalance;
import com.alex.test.village.villagers.bard.BardBalance;
import com.alex.test.village.villagers.butcher.ButcherBalance;
import com.alex.test.village.villagers.cleric.ClericBalance;
import com.alex.test.village.villagers.cook.CookBalance;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.druid.DruidBalance;
import com.alex.test.village.villagers.enchanter.EnchanterBalance;
import com.alex.test.village.villagers.farmer.FarmerBalance;
import com.alex.test.village.villagers.guard.CaptainBalance;
import com.alex.test.village.villagers.guard.GuardBalance;
import com.alex.test.village.villagers.blacksmith.BlacksmithBalance;
import com.alex.test.village.villagers.lumberjack.LumberjackBalance;
import com.alex.test.village.villagers.mage.MageBalance;
import com.alex.test.village.villagers.miner.MinerBalance;
import com.alex.test.village.villagers.rancher.RancherBalance;
import com.alex.test.village.villagers.teacher.TeacherBalance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * What a profession's 1..100 skill level does, for the Skill Progression panel.
 * Every number is read from the profession's Balance class, so the panel always
 * says what the game actually does.
 *
 * <p>Milestones are the levels where something steps up or appears; stats are
 * the values at any one level, compared now → then in the panel.
 */
record SkillProgression(List<Milestone> milestones, IntFunction<List<Stat>> stats) {

    /** One node on the track. {@code icon} null means the profession's token. */
    record Milestone(int level, String title, @Nullable Item icon, List<Unlock> unlocks) {}

    /** Something new rather than bigger, listed with its own item from its level on. */
    record Unlock(Item icon, String title, String description) {}

    record Stat(String label, String value, String description) {}

    /** Matches the default of the bard's "Max listening range" setting. */
    private static final int DEFAULT_LISTEN_RADIUS = 32;

    @Nullable
    static SkillProgression of(Profession profession) {
        return switch (profession) {
            case DRUID -> druid();
            case BARD -> bard();
            case LUMBERJACK -> lumberjack();
            case FARMER -> farmer();
            case MINER -> miner();
            case COOK -> cook();
            case BUTCHER -> butcher();
            case GUARD -> guard();
            case CAPTAIN -> captain();
            case BLACKSMITH -> blacksmith();
            case ARCHER -> archer();
            case MAGE -> mage();
            case ENCHANTER -> enchanter();
            case CLERIC -> cleric();
            case TEACHER -> teacher();
            case RANCHER -> rancher();
            default -> null;
        };
    }

    @Nullable
    Milestone milestoneExactly(int level) {
        for (Milestone milestone : milestones) {
            if (milestone.level() == level) return milestone;
        }
        return null;
    }

    @Nullable
    Milestone nextMilestone(int level) {
        for (Milestone milestone : milestones) {
            if (milestone.level() > level) return milestone;
        }
        return null;
    }

    private static SkillProgression druid() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of(
                        new Unlock(Items.IRON_ORE, "Common ores",
                                "Regrown rock holds the ores of its height: coal high up, redstone deep down"))),
                new Milestone(DruidBalance.ADEPT_LEVEL, "Adept", Items.TORCH, List.of(
                        new Unlock(Items.TORCH, "Kept torches",
                                "Torches taken out of a mine go to storage, not to waste"))),
                new Milestone(DruidBalance.EXPERT_LEVEL, "Expert", Items.GOLD_ORE, List.of(
                        new Unlock(Items.GOLD_ORE, "Gold ore",
                                "Regrown rock can now hold gold, most around Y -16"))),
                new Milestone(DruidBalance.MASTER_LEVEL, "Master", Items.DIAMOND_ORE, List.of(
                        new Unlock(Items.DIAMOND_ORE, "Diamond ore",
                                "Regrown rock can now hold diamonds, below Y 16"),
                        new Unlock(Items.EMERALD_ORE, "Emerald ore",
                                "Regrown rock can now hold emeralds, at any height"))),
                new Milestone(DruidBalance.GRANDMASTER_LEVEL, "Grandmaster", Items.ANCIENT_DEBRIS, List.of(
                        new Unlock(Items.ANCIENT_DEBRIS, "Ancient debris",
                                "Regrown rock at Y -48 and below can now hold ancient debris"),
                        new Unlock(Items.BONE_MEAL, "Double growth", percent(
                                DruidBalance.DOUBLE_STEP_CHANCE)
                                + " of crop and mine steps grow twice at once")))),
                level -> {
                    double treeSeconds = DruidBalance.saplingStageTicks(level, false) * 2 / 20.0;
                    return List.of(
                            new Stat("Dances per step", whole(DruidBalance.dancesPerStep(level)),
                                    "Dances for one crop stage or one regrown mine cell"),
                            new Stat("Sapling to tree", whole(treeSeconds) + " s",
                                    "Time to grow a sapling into a tree; 2x2 takes twice as long"),
                            new Stat("Ore in regrown rock",
                                    oneDecimal(DruidBalance.oreChance(level) * 100) + "%",
                                    "Chance a regrown mine block comes back as ore"));
                });
    }

    private static SkillProgression teacher() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of(
                        new Unlock(Items.LECTERN, "Lessons", "Teaches the village children at the school,"
                                + " and they grow up cleverer"),
                        new Unlock(Items.IRON_HOE, "Favourite job", "A child taught one job most starts it "
                                + TeacherBalance.headStartLevels(1) + " levels ahead"))),
                new Milestone(TeacherBalance.PATIENT_LEVEL, "Patient", Items.CLOCK, List.of(
                        new Unlock(Items.CLOCK, "Patient", "Tired or sad children daydream half as often"))),
                new Milestone(TeacherBalance.STORYTELLER_LEVEL, "Storyteller", Items.BOOK, List.of(
                        new Unlock(Items.BOOK, "Storyteller", "Reading hour teaches half as much again"))),
                new Milestone(TeacherBalance.INSPIRING_LEVEL, "Inspiring", Items.EXPERIENCE_BOTTLE, List.of(
                        new Unlock(Items.EXPERIENCE_BOTTLE, "Inspiring", "Children start their favourite job "
                                + TeacherBalance.headStartLevels(TeacherBalance.INSPIRING_LEVEL) + " levels ahead"))),
                new Milestone(TeacherBalance.HEAD_TEACHER_LEVEL, "Head Teacher", Items.BELL, List.of(
                        new Unlock(Items.BELL, "Head Teacher", "Teaches "
                                + TeacherBalance.HEAD_TEACHER_CLASS_SIZE + " children at full pace, not "
                                + TeacherBalance.CLASS_SIZE))),
                new Milestone(TeacherBalance.MENTOR_LEVEL, "Mentor", Items.WRITABLE_BOOK, List.of(
                        new Unlock(Items.WRITABLE_BOOK, "Mentor", "Children start their favourite job "
                                + TeacherBalance.headStartLevels(TeacherBalance.MENTOR_LEVEL) + " levels ahead")))),
                level -> List.of(
                        new Stat("A day of lessons", "+" + oneDecimal(TeacherBalance.dayOfLessons(level)),
                                "Intelligence a child gains from a whole school day"),
                        new Stat("Class size", whole(TeacherBalance.classSize(level)) + " children",
                                "Children taught at full pace; more than this and each learns less"),
                        new Stat("Head start", whole(TeacherBalance.headStartLevels(level)) + " levels",
                                "How far ahead a grown child starts the job it was taught most")));
    }

    private static SkillProgression cleric() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of(
                        new Unlock(Items.SLIME_BALL, "Healing orb",
                                "Throws a healing orb at anyone hurt within "
                                        + whole(ClericBalance.CAST_RANGE) + " blocks"))),
                new Milestone(ClericBalance.CLEANSE_LEVEL, "Cleanse", Items.MILK_BUCKET, List.of(
                        new Unlock(Items.MILK_BUCKET, "Cleanse",
                                "The orb also cures poison and other bad effects, and puts out fire"))),
                new Milestone(ClericBalance.SPLASH_LEVEL, "Splash", Items.SPLASH_POTION, List.of(
                        new Unlock(Items.SPLASH_POTION, "Splash", "Everyone within "
                                + whole(ClericBalance.SPLASH_RADIUS) + " blocks of the patient gets "
                                + percent(ClericBalance.SPLASH_SHARE) + " of the heal"))),
                new Milestone(ClericBalance.RENEWAL_LEVEL, "Renewal", Items.GLISTERING_MELON_SLICE, List.of(
                        new Unlock(Items.GLISTERING_MELON_SLICE, "Renewal", whole(ClericBalance.RENEWAL_TICKS / 20.0)
                                + " seconds of Regeneration after every heal"))),
                new Milestone(ClericBalance.GUARDIAN_LEVEL, "Guardian Angel", Items.TOTEM_OF_UNDYING, List.of(
                        new Unlock(Items.TOTEM_OF_UNDYING, "Guardian angel", "Once a day, saves a villager within "
                                + whole(ClericBalance.GUARDIAN_RADIUS) + " blocks from dying")))),
                level -> List.of(
                        new Stat("Heal per orb", oneDecimal(ClericBalance.healAmount(level) / 2.0) + " hearts",
                                "Health one healing orb gives back"),
                        new Stat("Time per throw",
                                String.format(Locale.ROOT, "%.2f s", ClericBalance.castTicks(level) / 20.0),
                                "How long one throw takes, wind up to follow through"),
                        new Stat("Cheering up", "+" + whole(ClericBalance.cheerUpAmount(level)),
                                "Happiness a sad villager gains from a kind word"),
                        new Stat("Battle blessing", whole(ClericBalance.blessingTicks(level) / 20.0) + " s",
                                "How long guards it blesses when the bell rings take less harm and heal")));
    }

    private static SkillProgression bard() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of(
                        new Unlock(Items.NOTE_BLOCK, "Melody",
                                "Plays the tune over a soft drum pulse"))),
                new Milestone(BardBalance.HARMONY_LEVEL, "Harmony", Items.NOTE_BLOCK, List.of(
                        new Unlock(Items.NOTE_BLOCK, "Harmony",
                                "A second voice plays under the melody"))),
                new Milestone(BardBalance.RHYTHM_LEVEL, "Rhythm", Items.JUKEBOX, List.of(
                        new Unlock(Items.JUKEBOX, "Rhythm section",
                                "Adds a bass line, hi-hats and stronger drums"))),
                new Milestone(BardBalance.VIRTUOSO_LEVEL, "Virtuoso", Items.BELL, List.of(
                        new Unlock(Items.BELL, "Bells",
                                "Bells join in for the full four-part arrangement")))),
                level -> List.of(
                        new Stat("Audience range", whole(BardBalance.audienceRadius(
                                level, DEFAULT_LISTEN_RADIUS)) + " blocks",
                                "How far listeners can be, with the default range setting"),
                        new Stat("Happiness per burst",
                                "+" + oneDecimal(BardBalance.happinessPerBurst(level)),
                                "Happiness each listener gains per burst of music"),
                        new Stat("Happiness per set", whole(BardBalance.sessionCap(level)),
                                "Most happiness one listener can gain from a set")));
    }

    private static SkillProgression lumberjack() {
        int tier = LumberjackBalance.LEVELS_PER_CARRY_TIER;
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of()),
                new Milestone(tier + 1, "Adept", Items.OAK_LOG, List.of()),
                new Milestone(tier * 2 + 1, "Expert", Items.SPRUCE_LOG, List.of()),
                new Milestone(tier * 3 + 1, "Master", Items.DARK_OAK_LOG, List.of())),
                level -> List.of(
                        new Stat("Logs carried", LumberjackBalance.carryLogs(level) + " logs",
                                "Logs gathered before taking them to storage"),
                        new Stat("Log yield", percent(LumberjackBalance.logChance(level)),
                                "Chance each log after the first drops from a felled tree")));
    }

    private static SkillProgression farmer() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of()),
                new Milestone(FarmerBalance.FIVE_STROKE_LEVEL, "5 strokes", Items.WHEAT, List.of()),
                new Milestone(FarmerBalance.FOUR_STROKE_LEVEL, "4 strokes", Items.WHEAT, List.of()),
                new Milestone(FarmerBalance.THREE_STROKE_LEVEL, "3 strokes", Items.WHEAT, List.of()),
                new Milestone(FarmerBalance.TWO_STROKE_LEVEL, "2 strokes", Items.HAY_BLOCK, List.of())),
                level -> List.of(
                        new Stat("Strokes per action",
                                Integer.toString(FarmerBalance.animationStrokes(level)),
                                "Swings to till, plant or harvest one block"),
                        new Stat("Bonus food chance", percent(FarmerBalance.extraFoodChance(level)),
                                "Chance a harvest gives one extra piece of food"),
                        new Stat("Soil stays farmland",
                                percent(FarmerBalance.soilRetentionChance(level)),
                                "Chance harvested soil stays farmland instead of dirt"),
                        new Stat("Harvest carried", FarmerBalance.carryItems(level) + " items",
                                "Harvest gathered before taking it to storage")));
    }

    private static SkillProgression miner() {
        return new SkillProgression(List.of(
                new Milestone(1, "Novice", null, List.of()),
                new Milestone(MinerBalance.ORE_SENSE_LEVEL, "Ore Sense", Items.SPYGLASS, List.of()),
                new Milestone(MinerBalance.ORE_SENSE_WIDE_LEVEL, "Wide Ore Sense",
                        Items.ENDER_EYE, List.of())),
                level -> {
                    int radius = MinerBalance.oreSenseRadius(level);
                    return List.of(
                            new Stat("Ore sense reach", radius == 0 ? "None"
                                    : radius + (radius == 1 ? " block" : " blocks"),
                                    "How far past the tunnel wall it can spot ore"),
                            new Stat("Ore sense chance", radius == 0 ? "-"
                                    : percent(MinerBalance.oreSenseChance(level)),
                                    "Chance it looks around for ore after breaking a block"),
                            new Stat("Scrap ore chance", percent(MinerBalance.scrapOreChance(level)),
                                    "Chance plain stone drops a scrap of ore"),
                            new Stat("Vein followed", MinerBalance.veinBlocks(level) + " blocks",
                                    "Ore followed off a vein before going back to the tunnel"),
                            new Stat("Blocks per trip", MinerBalance.stintBlocks(level) + " blocks",
                                    "Blocks broken before walking back to bank them"));
                });
    }

    private static SkillProgression cook() {
        return new SkillProgression(List.of(
                new Milestone(1, "Kitchen Hand", Items.BREAD, List.of(
                        new Unlock(Items.BREAD, "Every recipe", "Knows every dish from the start"))),
                new Milestone(CookBalance.SEASONED_LEVEL, "Seasoned", Items.SUGAR, List.of(
                        new Unlock(Items.SUGAR, "Seasoned", "The cook's dishes taste better,"
                                + " so meals make villagers happier"))),
                new Milestone(CookBalance.BAKERS_HANDS_LEVEL, "Baker's Hands", Items.CAKE, List.of(
                        new Unlock(Items.CAKE, "Baker's Hands", "Any dish may come out one extra,"
                                + " " + percent(CookBalance.BAKERS_HANDS_CHANCE) + " of batches"))),
                new Milestone(CookBalance.HEARTY_LEVEL, "Hearty Portions", Items.COOKED_BEEF, List.of(
                        new Unlock(Items.COOKED_BEEF, "Hearty Portions", "Every dish fills a villager"
                                + " a little more"))),
                new Milestone(CookBalance.SIGNATURE_LEVEL, "Signature Dish", Items.GOLDEN_APPLE, List.of(
                        new Unlock(Items.GOLDEN_APPLE, "Signature Dish", "The dish it has made most"
                                + " tastes much better to everyone")))),
                level -> List.of(
                        new Stat("Cooking speed", times(CookBalance.speed(level)),
                                "How fast a batch cooks, on top of the station's speed"),
                        new Stat("Batches per visit",
                                Integer.toString(CookBalance.batchesPerVisit(level)),
                                "Batches cooked each time it works a station"),
                        new Stat("Food carried", CookBalance.carryItems(level) + " items",
                                "Food carried before taking it to storage"),
                        new Stat("Extra staple chance",
                                percent(CookBalance.extraStapleChance(level)),
                                "Chance a staple batch makes one extra")));
    }

    private static SkillProgression butcher() {
        return new SkillProgression(List.of(
                new Milestone(1, "Apprentice", Items.WOODEN_SWORD, List.of(
                        new Unlock(Items.WOODEN_SWORD, "Vanilla kill", "Kills with sword blows; the animal"
                                + " drops its own loot, Looting and Fire Aspect included"))),
                new Milestone(ButcherBalance.SKINNER_LEVEL, "Skinner", Items.LEATHER, List.of(
                        new Unlock(Items.LEATHER, "Skinner", "A cow always gives leather and a chicken"
                                + " always gives feathers"))),
                new Milestone(ButcherBalance.CLEAN_KILL_LEVEL, "Clean Kill", Items.IRON_SWORD, List.of(
                        new Unlock(Items.IRON_SWORD, "Clean Kill", "Every blow is a critical hit, "
                                + times(ButcherBalance.CRIT_MULTIPLIER) + " damage"))),
                new Milestone(ButcherBalance.PRIME_CUTS_LEVEL, "Prime Cuts", Items.BEEF, List.of(
                        new Unlock(Items.BEEF, "Prime Cuts", "The extra meat comes as one or two"))),
                new Milestone(ButcherBalance.MASTER_LEVEL, "Master Butcher", Items.DIAMOND_SWORD, List.of(
                        new Unlock(Items.DIAMOND_SWORD, "Master Butcher", "Every kill counts one Looting"
                                + " level more than the sword has")))),
                level -> List.of(
                        new Stat("Extra meat chance", percent(ButcherBalance.extraMeatChance(level)),
                                "Chance a kill gives meat on top of the animal's own"),
                        new Stat("Extra hide chance", percent(ButcherBalance.extraHideChance(level)),
                                "Chance a cow gives one more leather or a chicken one more feather"),
                        new Stat("Animals per shift", Integer.toString(ButcherBalance.cullsPerShift(level)),
                                "Animals culled before taking the haul to storage")));
    }

    private static SkillProgression rancher() {
        return new SkillProgression(List.of(
                new Milestone(1, "Apprentice", Items.SHEARS, List.of(
                        new Unlock(Items.SHEARS, "Ranch work", "Shears sheep, milks cows, gathers eggs,"
                                + " combs hide off cows and feeds pairs so they have babies"))),
                new Milestone(RancherBalance.EGG_FINDER_LEVEL, "Egg Finder", Items.EGG, List.of(
                        new Unlock(Items.EGG, "Egg Finder", "Chickens lay an egg every "
                                + minutes(RancherBalance.eggIntervalTicks(RancherBalance.EGG_FINDER_LEVEL))
                                + " minutes instead of "
                                + minutes(RancherBalance.EGG_INTERVAL_TICKS)))),
                new Milestone(RancherBalance.GOOD_BREEDER_LEVEL, "Good Breeder", Items.WHEAT, List.of(
                        new Unlock(Items.WHEAT, "Good Breeder", "Babies in the pen grow up "
                                + percent(RancherBalance.GROW_BOOST) + " faster"))),
                new Milestone(RancherBalance.FINE_HIDES_LEVEL, "Fine Hides", Items.LEATHER, List.of(
                        new Unlock(Items.LEATHER, "Fine Hides", "A combed cow has a "
                                + percent(RancherBalance.SECOND_HIDE_CHANCE) + " chance of giving 2 leather"))),
                new Milestone(RancherBalance.MASTER_LEVEL, "Master Rancher", Items.GOLDEN_CARROT, List.of(
                        new Unlock(Items.GOLDEN_CARROT, "Master Rancher", "One feeding sets a pair"
                                + " breeding, so it uses half the food")))),
                level -> List.of(
                        new Stat("Time per animal",
                                String.format(Locale.ROOT, "%.1f s", RancherBalance.actTicks(level) / 20.0),
                                "How long each shear, milking, egg or feed takes"),
                        new Stat("Extra wool chance", percent(RancherBalance.extraWoolChance(level)),
                                "Chance a sheep gives one more wool"),
                        new Stat("Wool per sheep", oneDecimal(RancherBalance.averageWool(level)),
                                "Wool a shorn sheep gives on average")));
    }

    private static SkillProgression guard() {
        return new SkillProgression(List.of(
                new Milestone(GuardBalance.SHIELD_UNLOCK_LEVEL, "Recruit", Items.SHIELD, List.of(
                        new Unlock(Items.SHIELD, "Shield block", "Fetches a shield from storage and raises it"
                                + " against arrows, timed blows and crowds; once up it blocks every"
                                + " hit from the front until the guard meter runs dry"))),
                new Milestone(GuardBalance.SWEEP_LEVEL, "Sweeping Strike", Items.IRON_SWORD, List.of(
                        new Unlock(Items.IRON_SWORD, "Sweeping strike", "Each hit also deals "
                                + percent(GuardBalance.SWEEP_DAMAGE_FRACTION)
                                + " damage to hostiles next to the target"))),
                new Milestone(GuardBalance.BASH_LEVEL, "Shield Bash", Items.ANVIL, List.of(
                        new Unlock(Items.ANVIL, "Shield bash", "A block just as the shield comes up"
                                + " is a parry that knocks the attacker back and stuns it; so is every "
                                + GuardBalance.BASH_EVERY_HITS + "th hit while it carries a shield"))),
                new Milestone(GuardBalance.SECOND_WIND_LEVEL, "Second Wind",
                        Items.TOTEM_OF_UNDYING, List.of(
                        new Unlock(Items.TOTEM_OF_UNDYING, "Second wind", "Once a day, heals "
                                + percent(GuardBalance.SECOND_WIND_HEAL_FRACTION)
                                + " instead of falling back and keeps fighting")))),
                level -> List.of(
                        new Stat("Max health", oneDecimal(GuardBalance.BASE_HEALTH + GuardBalance.bonusHealth(level)) + " HP",
                                "Health, a little more every level"),
                        new Stat("Time between swings",
                                String.format(Locale.ROOT, "%.2f s", GuardBalance.attackTicks(level) / 20.0),
                                "How long from one sword swing to the next"),
                        new Stat("Guard meter", oneDecimal(GuardBalance.guardMeter(level)),
                                "Damage the shield soaks before the guard is broken open (grows from level 25)"),
                        new Stat("Threat response", times(GuardBalance.responseSpeed(level)),
                                "Running speed when answering a threat, against walking"),
                        new Stat("Healing after fights", String.format(Locale.ROOT, "%.2f HP/s",
                                        20.0 / GuardBalance.regenTicks(level)),
                                "Health regained once nothing has hurt it for 10 seconds")));
    }

    private static SkillProgression archer() {
        return new SkillProgression(List.of(
                new Milestone(1, "Recruit", null, List.of(
                        new Unlock(Items.BOW, "Bow and arrows", "Shoots hostile mobs from range, picks"
                                + " its arrows back up after a fight and makes new ones from flint,"
                                + " sticks and feathers"))),
                new Milestone(ArcherBalance.QUICK_NOCK_LEVEL, "Quick Nock", Items.ARROW, List.of(
                        new Unlock(Items.ARROW, "Quick nock", "Half the pause after a shot before"
                                + " it draws again"))),
                new Milestone(ArcherBalance.PIERCING_LEVEL, "Piercing Shot", Items.SPECTRAL_ARROW, List.of(
                        new Unlock(Items.SPECTRAL_ARROW, "Piercing shot", "Every arrow goes through the"
                                + " first mob and on into the one behind it"))),
                new Milestone(ArcherBalance.EAGLE_EYE_LEVEL, "Eagle Eye", Items.SPYGLASS, List.of(
                        new Unlock(Items.SPYGLASS, "Eagle eye", "Spots mobs "
                                + times(ArcherBalance.EAGLE_EYE_SPOT_MULTIPLIER)
                                + " as far away and shoots 4 blocks further"))),
                new Milestone(ArcherBalance.VOLLEY_LEVEL, "Volley", Items.CROSSBOW, List.of(
                        new Unlock(Items.CROSSBOW, "Volley", "Every " + ArcherBalance.VOLLEY_EVERY_SHOTS
                                + "th shot sends a second arrow, at another mob if one is in range,"
                                + " without using up an arrow")))),
                level -> List.of(
                        new Stat("Bow draw",
                                String.format(Locale.ROOT, "%.2f s", ArcherBalance.drawTicks(level) / 20.0),
                                "How long it takes to pull the bow to full draw"),
                        new Stat("Time between shots",
                                String.format(Locale.ROOT, "%.2f s", ArcherBalance.secondsPerShot(level)),
                                "Draw plus the pause after the shot"),
                        new Stat("Arrow spread", oneDecimal(ArcherBalance.inaccuracy(level)),
                                "How far arrows stray; lower is truer (a player is 1)"),
                        new Stat("Range", oneDecimal(ArcherBalance.maxShootRange(level)) + " blocks",
                                "Furthest it shoots from"),
                        new Stat("Healing after fights", String.format(Locale.ROOT, "%.2f HP/s",
                                        20.0 / GuardBalance.regenTicks(level)),
                                "Health regained once nothing has hurt it for 10 seconds")));
    }

    private static SkillProgression mage() {
        return new SkillProgression(List.of(
                new Milestone(1, "Apprentice", null, List.of(
                        new Unlock(Items.AMETHYST_SHARD, "Arcane Bolt", "A free purple bolt from its bare hands"
                                + " that curves after its mob; it hits flyers as surely as walkers"))),
                new Milestone(MageBalance.FROST_NOVA_LEVEL, "Frost Nova", Items.SNOWBALL, List.of(
                        new Unlock(Items.SNOWBALL, "Frost Nova", "Every mob within "
                                + whole(MageBalance.NOVA_RADIUS) + " blocks of a packed spot takes a little"
                                + " damage and barely walks; leaves no ice. Costs a reagent"))),
                new Milestone(MageBalance.BLINK_LEVEL, "Blink", Items.ENDER_PEARL, List.of(
                        new Unlock(Items.ENDER_PEARL, "Blink", "Cornered with no guard near, it vanishes"
                                + " and reappears " + whole(MageBalance.BLINK_MIN_DISTANCE) + "-"
                                + whole(MageBalance.BLINK_MAX_DISTANCE) + " blocks away, once every "
                                + MageBalance.BLINK_COOLDOWN_TICKS / 20 + " s"))),
                new Milestone(MageBalance.WARD_LEVEL, "Ward", Items.SHIELD, List.of(
                        new Unlock(Items.SHIELD, "Ward", "A shield bubble on the guard in the most trouble;"
                                + " it soaks up the next few hits"))),
                new Milestone(MageBalance.CHAIN_SPARK_LEVEL, "Chain Spark", Items.LIGHTNING_ROD, List.of(
                        new Unlock(Items.LIGHTNING_ROD, "Chain Spark", "Lightning that jumps from mob to mob, "
                                + MageBalance.CHAIN_FIRST_JUMPS + " of them (" + MageBalance.CHAIN_FULL_JUMPS
                                + " from level " + MageBalance.CHAIN_FULL_LEVEL + "); never real lightning,"
                                + " never fire. Costs a reagent"))),
                new Milestone(MageBalance.STARFALL_LEVEL, "Starfall", Items.NETHER_STAR, List.of(
                        new Unlock(Items.NETHER_STAR, "Starfall", "Once a fight, " + MageBalance.STARFALL_MIN_STARS
                                + " to " + MageBalance.STARFALL_MAX_STARS + " stars fall on the biggest pack."
                                + " Costs " + MageBalance.STARFALL_REAGENTS + " reagents")))),
                level -> List.of(
                        new Stat("Time between bolts",
                                String.format(Locale.ROOT, "%.2f s", MageBalance.secondsPerBolt(level)),
                                "Cast plus the pause after it"),
                        new Stat("Bolt damage", oneDecimal(MageBalance.boltDamage(level)),
                                "What one Arcane Bolt does to a mob"),
                        new Stat("Mana", whole(MageBalance.maxMana(level)),
                                "The most it holds; the bolt costs none"),
                        new Stat("Mana back", String.format(Locale.ROOT, "%.1f /s",
                                        MageBalance.manaPerTick(level) * 20.0),
                                "Regained all the time, fighting or not"),
                        new Stat("Range", oneDecimal(MageBalance.CAST_RANGE) + " blocks",
                                "Furthest it casts from"),
                        new Stat("Frost Nova slow", String.format(Locale.ROOT, "%.1f s",
                                        MageBalance.novaSlowTicks(level) / 20.0),
                                "How long mobs caught in a nova are slowed")));
    }

    private static SkillProgression blacksmith() {
        return new SkillProgression(List.of(
                new Milestone(1, "Apprentice", null, List.of(
                        new Unlock(Items.IRON_PICKAXE, "Stone, leather and iron", "Forges stone and iron"
                                + " tools, leather and iron armour, shields and shears, and melts"
                                + " unclaimed old tools back into material"))),
                new Milestone(BlacksmithBalance.DIAMOND_LEVEL, "Diamond Smith", Items.DIAMOND, List.of(
                        new Unlock(Items.DIAMOND_PICKAXE, "Diamond smithing",
                                "Forges diamond tools and armour"))),
                new Milestone(BlacksmithBalance.TEMPERED_LEVEL, "Tempered Steel", Items.ANVIL, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Tempered steel", "A "
                                + percent(BlacksmithBalance.temperedChance(BlacksmithBalance.TEMPERED_LEVEL))
                                + " chance (up to "
                                + percent(BlacksmithBalance.temperedChance(BlacksmithBalance.MAX_LEVEL))
                                + " at level 100) that a finished piece comes out with Unbreaking I"))),
                new Milestone(BlacksmithBalance.NETHERITE_LEVEL, "Netherite Smith", Items.SMITHING_TABLE, List.of(
                        new Unlock(Items.NETHERITE_INGOT, "Netherite smithing", "Upgrades to netherite at"
                                + " a smithing table in the forge, using a template and an ingot from storage"))),
                new Milestone(BlacksmithBalance.TEMPLATE_LEVEL, "Master Smith",
                        Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, List.of(
                        new Unlock(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "Template copying",
                                "Copies the netherite upgrade template when the village is down to one"
                                + " (7 diamonds and a netherrack)")))),
                level -> List.of(
                        new Stat("Time per item", String.format(Locale.ROOT, "%.1f s",
                                        BlacksmithBalance.craftTicks(level) / 20.0),
                                "How long one tool or armour piece takes at the anvil"),
                        new Stat("Smelting", String.format(Locale.ROOT, "%.1f s per ingot",
                                        BlacksmithBalance.smeltTicksPerItem(level) / 20.0),
                                "At a plain furnace; a blast furnace in the forge halves it"),
                        new Stat("Smelts per coal", oneDecimal(BlacksmithBalance.smeltsPerCoal(level)),
                                "Raw iron one coal or charcoal smelts"),
                        new Stat("Recycling return", percent(BlacksmithBalance.recoveryRate(level)),
                                "Share of an old tool's remaining material it gets back"),
                        new Stat("Tempered chance", percent(BlacksmithBalance.temperedChance(level)),
                                "Chance of Unbreaking I on a finished piece (from level 50)")));
    }

    private static SkillProgression enchanter() {
        return new SkillProgression(List.of(
                new Milestone(1, "Apprentice", null, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Level I books", "Writes Unbreaking, Efficiency,"
                                + " Sharpness, Protection and Power books for the village's iron or better"
                                + " tools, armour and bows, whichever helps most"),
                        new Unlock(Items.PAPER, "Paper and books", "Presses sugar cane into paper and binds"
                                + " books with leather when storage has none"))),
                new Milestone(EnchanterBalance.SCHOLAR_LEVEL, "Scholar", Items.BOOKSHELF, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Level II books", "Needs "
                                + EnchanterBalance.shelvesFor(2) + " bookshelves in the library"),
                        new Unlock(Items.RAW_GOLD, "Fortune", "Miners get more ore from each block"),
                        new Unlock(Items.BOW, "Infinity", "Archers shoot without using up arrows"))),
                new Milestone(EnchanterBalance.ADEPT_LEVEL, "Adept", Items.LAPIS_LAZULI, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Level III books", "Needs "
                                + EnchanterBalance.shelvesFor(3) + " bookshelves in the library"),
                        new Unlock(Items.LAPIS_LAZULI, "Steady hand", "A "
                                + percent(EnchanterBalance.lapisSaveChance(EnchanterBalance.ADEPT_LEVEL))
                                + " chance (up to "
                                + percent(EnchanterBalance.lapisSaveChance(EnchanterBalance.MAX_LEVEL))
                                + " at level 100) that a book uses no lapis"))),
                new Milestone(EnchanterBalance.ARCANIST_LEVEL, "Arcanist", Items.EXPERIENCE_BOTTLE, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Level IV books", "Needs "
                                + EnchanterBalance.shelvesFor(4) + " bookshelves in the library"),
                        new Unlock(Items.EXPERIENCE_BOTTLE, "Mending", "Miners' pickaxes mend"
                                + " themselves with the experience from the ore they dig"))),
                new Milestone(EnchanterBalance.MASTER_LEVEL, "Master Enchanter", Items.ENCHANTING_TABLE, List.of(
                        new Unlock(Items.ENCHANTED_BOOK, "Level V books", "Efficiency V, Sharpness V and"
                                + " Power V, the strongest there are")))),
                level -> List.of(
                        new Stat("Books a day", Integer.toString(EnchanterBalance.booksPerDay(level)),
                                "The most enchanted books it writes in one day"),
                        new Stat("Strongest book",
                                com.alex.test.village.villagers.enchanter.EnchantBooks.roman(
                                        EnchanterBalance.skillCap(level)),
                                "Also limited by the library: " + EnchanterBalance.shelvesFor(1)
                                        + " bookshelves for I, up to " + EnchanterBalance.shelvesFor(5) + " for V"),
                        new Stat("Reading time", String.format(Locale.ROOT, "%.0f s",
                                        EnchanterBalance.studyTicks(level) / 20.0),
                                "Time at a bookshelf before each book"),
                        new Stat("Time at the table", String.format(Locale.ROOT, "%.0f s",
                                        EnchanterBalance.tableTicks(level, 1) / 20.0),
                                "For a level I to III book; IV and V take a quarter longer"),
                        new Stat("Lapis saved", percent(EnchanterBalance.lapisSaveChance(level)),
                                "Chance a book uses no lapis (from level 50)")));
    }

    private static SkillProgression captain() {
        return new SkillProgression(List.of(
                new Milestone(1, "Captain", null, List.of(
                        new Unlock(Items.SHIELD, "Shield block", "Carries a shield and raises it in a"
                                + " fight, blocking every hit from the front until its guard meter runs dry;"
                                + " defenders right behind have "
                                + percent(CaptainBalance.SHIELD_WALL_SHARE) + " of each hit blocked"),
                        new Unlock(Items.IRON_SWORD, "Sweeping strike", "Each hit also deals "
                                + percent(CaptainBalance.SWEEP_DAMAGE_FRACTION)
                                + " damage to hostiles next to the target"))),
                new Milestone(CaptainBalance.RALLY_LEVEL, "Rallying Cry", Items.GOAT_HORN, List.of(
                        new Unlock(Items.GOAT_HORN, "Rallying cry", "While it fights, defenders within "
                                + whole(CaptainBalance.RALLY_RADIUS) + " blocks deal +"
                                + percent(CaptainBalance.RALLY_DAMAGE_BONUS)
                                + " damage and fall back later"))),
                new Milestone(CaptainBalance.SHOCKWAVE_LEVEL, "Shockwave Bash", Items.MACE, List.of(
                        new Unlock(Items.MACE, "Shockwave bash", "A block just as the shield comes up is a"
                                + " parry, and every " + CaptainBalance.BASH_EVERY_HITS
                                + "rd hit while it carries a shield a bash: either one knocks back and"
                                + " stuns every hostile around the target"))),
                new Milestone(CaptainBalance.VETERAN_EYE_LEVEL, "Veteran's Eye", Items.SPYGLASS, List.of(
                        new Unlock(Items.SPYGLASS, "Veteran's eye", "Spots hostiles from "
                                + whole(GuardBalance.SPOT_RADIUS * CaptainBalance.VETERAN_EYE_FACTOR)
                                + " blocks instead of " + whole(GuardBalance.SPOT_RADIUS) + " ("
                                + whole(GuardBalance.SPOT_RADIUS * CaptainBalance.VETERAN_EYE_FACTOR
                                        + GuardBalance.ALERT_SPOT_BONUS)
                                + " instead of " + whole(GuardBalance.SPOT_RADIUS + GuardBalance.ALERT_SPOT_BONUS)
                                + " on alert)"))),
                new Milestone(CaptainBalance.UNBREAKABLE_LEVEL, "Unbreakable",
                        Items.TOTEM_OF_UNDYING, List.of(
                        new Unlock(Items.TOTEM_OF_UNDYING, "Unbreakable", "Second wind "
                                + CaptainBalance.SECOND_WINDS_PER_DAY + " times a day; nearby defenders heal "
                                + percent(CaptainBalance.UNBREAKABLE_ALLY_HEAL_FRACTION) + " with it")))),
                level -> List.of(
                        new Stat("Max health",
                                oneDecimal(GuardBalance.BASE_HEALTH + CaptainBalance.bonusHealth(level)) + " HP",
                                "Health, a little more every level"),
                        new Stat("Time between swings",
                                String.format(Locale.ROOT, "%.2f s", CaptainBalance.attackTicks(level) / 20.0),
                                "How long from one sword swing to the next"),
                        new Stat("Guard meter", oneDecimal(CaptainBalance.guardMeter(level)),
                                "Damage the shield soaks before the captain is broken open"),
                        new Stat("Threat response", times(CaptainBalance.responseSpeed(level)),
                                "Running speed when answering a threat, against walking"),
                        new Stat("Healing after fights", String.format(Locale.ROOT, "%.2f HP/s",
                                        20.0 / CaptainBalance.regenTicks(level)),
                                "Health regained once nothing has hurt it for 10 seconds")));
    }

    private static String percent(double chance) {
        return Math.round(chance * 100) + "%";
    }

    private static String times(double multiplier) {
        return String.format(Locale.ROOT, "×%.2f", multiplier);
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** Game ticks as real minutes, one decimal: 7500 → "6.3". */
    private static String minutes(long ticks) {
        return oneDecimal(ticks / 1200.0);
    }

    private static String whole(double value) {
        return Long.toString(Math.round(value));
    }
}
