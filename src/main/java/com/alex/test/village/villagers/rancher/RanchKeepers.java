package com.alex.test.village.villagers.rancher;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.AdmireTownActivity;
import com.alex.test.village.brain.BakeBreadActivity;
import com.alex.test.village.brain.ConverseActivity;
import com.alex.test.village.brain.DepositActivity;
import com.alex.test.village.brain.EatActivity;
import com.alex.test.village.brain.EveningActivity;
import com.alex.test.village.brain.FetchFoodActivity;
import com.alex.test.village.brain.FleeActivity;
import com.alex.test.village.brain.GoHomeActivity;
import com.alex.test.village.brain.LeisureActivity;
import com.alex.test.village.brain.LibraryActivity;
import com.alex.test.village.brain.PairDanceActivity;
import com.alex.test.village.brain.PreTaskHungerActivity;
import com.alex.test.village.brain.ShelterActivity;
import com.alex.test.village.brain.SocializeActivity;
import com.alex.test.village.brain.WanderActivity;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.villagers.butcher.lead.LeadSupply;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * The village's Ranchers as the Ranch window lists them (round 4 of
 * docs/plans/2026-10-24-ranch-window.md): name, what each is doing now, its switches, which kinds it
 * looks after and the shears, lead and buckets in its pack. Only Ranchers near a player are known:
 * a villager far away has no body to ask. Reads only.
 */
public final class RanchKeepers {

    /** What a Rancher is doing: its life outside work first, then (from LOOKING on) its work. */
    public enum Doing {
        ASLEEP, GOING_HOME, EATING, FREE_TIME, SHELTER, IDLE, OTHER,
        LOOKING, SHEAR, FEED, MILK, EGG, SHED, BRING_BACK, ROUND_UP, GET_LEAD, FETCH_FOOD, FETCH_BUCKETS, PUT_AWAY
    }

    /** Its lead: in the pack, none but storage has one it may take, or none to be had. */
    public enum Lead { CARRIED, IN_STORAGE, NONE }

    /**
     * One Rancher. {@code kind}: the animal its job is for (null when none); {@code elsewhere}: that
     * animal is at another pen of the village. {@code managed}: bit i set when kind i of
     * {@link RanchStatus#KINDS} is ticked. {@code buckets}: empty buckets it may use.
     */
    public record Keeper(String name, Doing doing, @Nullable EntityType<?> kind, boolean elsewhere,
                         boolean work, boolean collecting, boolean breeding, boolean roundUp, int range,
                         int managed, boolean shears, Lead lead, int buckets) {
        public boolean manages(int kindIndex) {
            return (managed & (1 << kindIndex)) != 0;
        }
    }

    private RanchKeepers() {}

    /**
     * Every loaded Rancher of the village, by name. {@code leadInStorage} answers whether storage has a
     * lead this Rancher may take; it is asked only for one that carries none (it looks through the chests).
     */
    public static List<Keeper> compute(ServerLevel level, VillageManager village, RanchBuilding ranch,
                                       Predicate<Activity.Context> leadInStorage) {
        boolean manyPens = village.getBuildings().stream()
                .filter(building -> building instanceof RanchBuilding other && other.isValid()).count() > 1;
        List<Keeper> keepers = new ArrayList<>();
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.RANCHER || model.brain() == null) {
                continue;
            }
            Activity.Context ctx = new Activity.Context(model.brain(), model, village, level, npc);
            ProfessionSettings settings = ProfessionSettings.of(npc, RancherSettingsSchema.SCHEMA);
            Activity current = model.brain().currentActivity();
            Doing doing = doing(npc, current);
            Entity target = current instanceof RancherActivity work ? work.busyWith() : null;
            boolean elsewhere = target != null && manyPens && !ranch.isPenTile(level, target.blockPosition());
            int managed = 0;
            for (int i = 0; i < RanchStatus.KINDS.size(); i++) {
                if (RancherActivity.isAllowedSpecies(settings, RanchStatus.KINDS.get(i))) managed |= 1 << i;
            }
            Lead lead = LeadSupply.hasLead(npc) ? Lead.CARRIED
                    : leadInStorage.test(ctx) ? Lead.IN_STORAGE : Lead.NONE;
            String name = VillageManager.displayName(model);
            keepers.add(new Keeper(name, doing, target == null ? null : target.getType(), elsewhere,
                    settings.getBool(RancherSettingsSchema.WORK_ENABLED),
                    settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED),
                    settings.getBool(RancherSettingsSchema.BREEDING_ENABLED),
                    settings.getBool(RancherSettingsSchema.ROUNDUP_ENABLED),
                    settings.getInt(RancherSettingsSchema.ROUNDUP_RANGE),
                    managed, RancherActivity.hasShears(npc), lead, emptyBuckets(npc)));
        }
        keepers.sort(Comparator.comparing(Keeper::name, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(keepers);
    }

    private static Doing doing(NpcEntity npc, @Nullable Activity current) {
        if (npc.isSleeping()) return Doing.ASLEEP;
        if (current == null) return Doing.IDLE;
        if (current instanceof RancherActivity work) return Doing.valueOf(work.busy().name());
        if (current instanceof GoHomeActivity) return Doing.GOING_HOME;
        if (current instanceof EatActivity || current instanceof FetchFoodActivity
                || current instanceof PreTaskHungerActivity || current instanceof BakeBreadActivity) return Doing.EATING;
        if (current instanceof FleeActivity || current instanceof ShelterActivity) return Doing.SHELTER;
        if (current instanceof DepositActivity) return Doing.PUT_AWAY;
        if (current instanceof EveningActivity || current instanceof LeisureActivity || current instanceof LibraryActivity
                || current instanceof SocializeActivity || current instanceof ConverseActivity
                || current instanceof PairDanceActivity || current instanceof AdmireTownActivity
                || current instanceof WanderActivity) return Doing.FREE_TIME;
        return Doing.OTHER;
    }

    private static int emptyBuckets(NpcEntity npc) {
        InventoryManager inv = npc.getInventory();
        int total = 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inv.getSlot(i);
            if (stack.is(Items.BUCKET) && inv.mayUse(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Whether the line reads as work going on (green) rather than time off (grey). */
    public static boolean working(Doing doing) {
        return doing.ordinal() > Doing.LOOKING.ordinal();
    }

    /** "Shearing a sheep", "Asleep": the same words wherever they are shown. */
    public static MutableComponent text(Keeper keeper) {
        MutableComponent doing = switch (keeper.doing()) {
            case ASLEEP -> Tr.c("screen.dynamicvillages.ranch.doing.asleep", "Asleep");
            case GOING_HOME -> Tr.c("screen.dynamicvillages.ranch.doing.going_home", "Going home");
            case EATING -> Tr.c("screen.dynamicvillages.ranch.doing.eating", "Getting something to eat");
            case FREE_TIME -> Tr.c("screen.dynamicvillages.ranch.doing.free_time", "Free time, not working");
            case SHELTER -> Tr.c("screen.dynamicvillages.ranch.doing.shelter", "Taking shelter");
            case IDLE -> Tr.c("screen.dynamicvillages.ranch.doing.idle", "Resting between jobs");
            case OTHER -> Tr.c("screen.dynamicvillages.ranch.doing.other", "Busy with something else");
            case LOOKING -> Tr.c("screen.dynamicvillages.ranch.doing.looking", "At work, looking for a job");
            case SHEAR -> Tr.c("screen.dynamicvillages.ranch.doing.shear", "Shearing a sheep");
            case FEED -> feed(keeper.kind());
            case MILK -> Tr.c("screen.dynamicvillages.ranch.doing.milk", "Milking a cow");
            case EGG -> Tr.c("screen.dynamicvillages.ranch.doing.egg", "Collecting an egg");
            case SHED -> Tr.c("screen.dynamicvillages.ranch.doing.shed", "Combing leather off a cow");
            case BRING_BACK -> Tr.c("screen.dynamicvillages.ranch.doing.bring_back", "Bringing an animal back into its pen");
            case ROUND_UP -> Tr.c("screen.dynamicvillages.ranch.doing.round_up", "Rounding up a loose animal");
            case GET_LEAD -> Tr.c("screen.dynamicvillages.ranch.doing.get_lead", "Fetching a lead from storage");
            case FETCH_FOOD -> Tr.c("screen.dynamicvillages.ranch.doing.fetch_food", "Fetching breeding food from storage");
            case FETCH_BUCKETS -> Tr.c("screen.dynamicvillages.ranch.doing.fetch_buckets", "Fetching buckets for milking");
            case PUT_AWAY -> Tr.c("screen.dynamicvillages.ranch.doing.put_away", "Taking goods to storage");
        };
        return keeper.elsewhere()
                ? Tr.c("screen.dynamicvillages.ranch.doing.elsewhere", "%s, at another pen", doing)
                : doing;
    }

    private static MutableComponent feed(@Nullable EntityType<?> kind) {
        if (kind == EntityType.COW) return Tr.c("screen.dynamicvillages.ranch.doing.feed_cows", "Feeding a pair of cows");
        if (kind == EntityType.SHEEP) return Tr.c("screen.dynamicvillages.ranch.doing.feed_sheep", "Feeding a pair of sheep");
        if (kind == EntityType.PIG) return Tr.c("screen.dynamicvillages.ranch.doing.feed_pigs", "Feeding a pair of pigs");
        if (kind == EntityType.CHICKEN) return Tr.c("screen.dynamicvillages.ranch.doing.feed_chickens", "Feeding a pair of chickens");
        return Tr.c("screen.dynamicvillages.ranch.doing.feed", "Feeding a pair to breed");
    }
}
