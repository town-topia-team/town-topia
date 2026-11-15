package com.alex.test.village.villagers.teacher;

import com.alex.test.Tr;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MageTowerBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a lesson is about. Each lesson is one job: the teacher holds up that
 * job's tool, and a child taught one job most grows up good at it.
 *
 * <p>With "Teaching What's Needed" on, jobs nobody in the village does
 * yet come up three times as often, and jobs only one villager does twice.
 */
public final class SchoolSubjects {

    /** Every job a lesson can be about. */
    public static final List<Profession> TEACHABLE = List.of(
            Profession.FARMER, Profession.LUMBERJACK, Profession.MINER, Profession.COOK,
            Profession.BLACKSMITH, Profession.GUARD, Profession.ARCHER, Profession.CLERIC,
            Profession.BARD, Profession.RANCHER, Profession.BUTCHER, Profession.DRUID,
            Profession.ENCHANTER, Profession.TRADESMAN, Profession.BUILDER);

    private static final Map<Profession, Item> PROPS = new EnumMap<>(Profession.class);

    static {
        PROPS.put(Profession.FARMER, Items.IRON_HOE);
        PROPS.put(Profession.LUMBERJACK, Items.IRON_AXE);
        PROPS.put(Profession.MINER, Items.IRON_PICKAXE);
        PROPS.put(Profession.COOK, Items.BREAD);
        PROPS.put(Profession.BLACKSMITH, Items.IRON_INGOT);
        PROPS.put(Profession.GUARD, Items.IRON_SWORD);
        PROPS.put(Profession.ARCHER, Items.BOW);
        PROPS.put(Profession.CLERIC, Items.GOLDEN_APPLE);
        PROPS.put(Profession.BARD, Items.NOTE_BLOCK);
        PROPS.put(Profession.RANCHER, Items.WHEAT);
        PROPS.put(Profession.BUTCHER, Items.BEEF);
        PROPS.put(Profession.DRUID, Items.OAK_SAPLING);
        PROPS.put(Profession.ENCHANTER, Items.BOOK);
        PROPS.put(Profession.TRADESMAN, Items.EMERALD);
        PROPS.put(Profession.BUILDER, Items.BRICK);
    }

    private SchoolSubjects() {}

    /** The thing the teacher holds up while teaching {@code subject}. */
    public static Item prop(Profession subject) {
        return PROPS.getOrDefault(subject, Items.BOOK);
    }

    /** A plain name for chat and the Mayor: "farming", "woodcutting"... */
    public static String lessonName(Profession subject) {
        return switch (subject) {
            case FARMER -> "farming";
            case LUMBERJACK -> "woodcutting";
            case MINER -> "mining";
            case COOK -> "cooking";
            case BLACKSMITH -> "smithing";
            case GUARD -> "guarding";
            case ARCHER -> "archery";
            case MAGE -> "magic";
            case CLERIC -> "healing";
            case BARD -> "music";
            case RANCHER -> "animal care";
            case BUTCHER -> "butchery";
            case DRUID -> "nature";
            case ENCHANTER -> "enchanting";
            case TRADESMAN -> "trading";
            case BUILDER -> "building";
            default -> "reading";
        };
    }

    /** {@link #lessonName} in each player's own language, for text the server sends (chat). */
    public static Component lessonText(Profession subject) {
        return switch (subject) {
            case FARMER -> Tr.c("lesson.dynamicvillages.farming", "farming");
            case LUMBERJACK -> Tr.c("lesson.dynamicvillages.woodcutting", "woodcutting");
            case MINER -> Tr.c("lesson.dynamicvillages.mining", "mining");
            case COOK -> Tr.c("lesson.dynamicvillages.cooking", "cooking");
            case BLACKSMITH -> Tr.c("lesson.dynamicvillages.smithing", "smithing");
            case GUARD -> Tr.c("lesson.dynamicvillages.guarding", "guarding");
            case ARCHER -> Tr.c("lesson.dynamicvillages.archery", "archery");
            case MAGE -> Tr.c("lesson.dynamicvillages.magic", "magic");
            case CLERIC -> Tr.c("lesson.dynamicvillages.healing", "healing");
            case BARD -> Tr.c("lesson.dynamicvillages.music", "music");
            case RANCHER -> Tr.c("lesson.dynamicvillages.animal_care", "animal care");
            case BUTCHER -> Tr.c("lesson.dynamicvillages.butchery", "butchery");
            case DRUID -> Tr.c("lesson.dynamicvillages.nature", "nature");
            case ENCHANTER -> Tr.c("lesson.dynamicvillages.enchanting", "enchanting");
            case TRADESMAN -> Tr.c("lesson.dynamicvillages.trading", "trading");
            case BUILDER -> Tr.c("lesson.dynamicvillages.building", "building");
            default -> Tr.c("lesson.dynamicvillages.reading", "reading");
        };
    }

    /** How many villagers do each job right now. */
    public static Map<Profession, Integer> workerCounts(VillageManager village) {
        Map<Profession, Integer> workers = new EnumMap<>(Profession.class);
        for (VillagerModel model : village.getVillagers()) {
            workers.merge(model.brain().profession(), 1, Integer::sum);
        }
        return workers;
    }

    /**
     * How often {@code subject} comes up: with "Teaching What's Needed"
     * on, three times as often when nobody does the job, twice with one
     * villager; otherwise every job the same.
     */
    public static int needWeight(Map<Profession, Integer> workers, Profession subject, boolean villageNeeds) {
        if (!villageNeeds) return 1;
        int have = workers.getOrDefault(subject, 0);
        return have == 0 ? 3 : have == 1 ? 2 : 1;
    }

    /** Picks the next lesson's subject. */
    public static Profession pick(VillageManager village, RandomSource random, boolean villageNeeds) {
        Map<Profession, Integer> workers = villageNeeds ? workerCounts(village) : Map.of();
        int total = 0;
        int[] weights = new int[TEACHABLE.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = needWeight(workers, TEACHABLE.get(i), villageNeeds);
            total += weights[i];
        }
        int roll = random.nextInt(total);
        for (int i = 0; i < weights.length; i++) {
            roll -= weights[i];
            if (roll < 0) return TEACHABLE.get(i);
        }
        return TEACHABLE.get(0);
    }

    /**
     * The workplace a field trip about {@code subject} visits: the nearest
     * such building within {@code maxDistance} of {@code from}, or null when
     * the village has none (farming and woodcutting have no building: the
     * class watches a worker out in the fields or at the trees instead).
     */
    @Nullable
    public static Building workplace(VillageManager village, Profession subject, BlockPos from, int maxDistance) {
        List<Class<? extends Building>> kinds = switch (subject) {
            case RANCHER -> List.of(RanchBuilding.class);
            case BUTCHER -> List.of(RanchBuilding.class, ButcheryBuilding.class);
            case COOK -> List.of(KitchenBuilding.class);
            case BLACKSMITH -> List.of(ForgeBuilding.class);
            case MINER -> List.of(MineBuilding.class);
            case ARCHER -> List.of(ArcheryRangeBuilding.class, WatchtowerBuilding.class);
            case GUARD -> List.of(BarracksBuilding.class, WatchtowerBuilding.class);
            case MAGE -> List.of(MageTowerBuilding.class, BarracksBuilding.class);
            case ENCHANTER -> List.of(LibraryBuilding.class);
            case TRADESMAN -> List.of(MerchantStallBuilding.class);
            case BARD -> List.of(TavernBuilding.class);
            default -> List.of();
        };
        if (kinds.isEmpty()) return null;
        Building best = null;
        double bestDist = (double) maxDistance * maxDistance;
        for (Building b : village.getBuildings()) {
            if (!b.isValid() || kinds.stream().noneMatch(kind -> kind.isInstance(b))) continue;
            double d = b.getDoorPos().distSqr(from);
            if (d < bestDist) {
                bestDist = d;
                best = b;
            }
        }
        return best;
    }
}
