package com.alex.test.village.villagers.rancher;

import com.alex.test.Tr;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.rancher.behavior.RancherActivity;
import com.alex.test.village.villagers.rancher.settings.RancherSettingsSchema;
import com.alex.test.village.villagers.settings.ProfessionSettings;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Why each kind in one pen is bred or not, in the Rancher's own order (the breeding part of
 * {@code RancherActivity.chooseNext}): the Rancher's switches, the pen's room, two ready adults,
 * then food. For the Ranch window and {@code /villager ranch} (round 3 of
 * docs/plans/2026-10-24-ranch-window.md); both print {@link #text}. Every loaded Rancher of the
 * village is asked, as each has its own settings and pack; a kind shows the furthest any gets.
 * Reads only: a change to the Rancher's breeding rules needs the same change here.
 */
public final class RanchStatus {

    /** The kinds the Rancher breeds, in the order they are listed. */
    public static final List<EntityType<?>> KINDS = List.of(EntityType.COW, EntityType.SHEEP, EntityType.PIG,
            EntityType.CHICKEN);

    /**
     * The first thing that stops a kind being bred, or how far it got. Rancher side first, then the
     * pen, then food; within food, a later one is further along.
     */
    public enum Reason {
        NO_RANCHER, WORK_OFF, BREEDING_OFF, UNTICKED,
        FULL, TOO_FEW, IN_LOVE, RESTING, BUSY,
        NO_FOOD, FOOD_HELD, FETCH, READY, NEXT
    }

    /** How a reason reads: going ahead, waiting on the pen, or stuck until the player acts. */
    public enum Tone { GOOD, WAIT, BLOCKED }

    /** One food the kind eats and how many of it storage holds. */
    public record Stock(Item item, int stored) {}

    /**
     * One kind's line. {@code readyAt}: game time a second adult is ready (RESTING only), else 0.
     * {@code carried} and {@code perPair}: the furthest Rancher's breeding food in its pack and what a
     * pair eats (0 when no Rancher breeds this kind).
     */
    public record KindLine(EntityType<?> kind, Reason reason, long readyAt, int carried, int perPair,
                           List<Stock> stock) {}

    /** Collecting: everyone has it switched off; woolly sheep but no collecting Rancher has shears. */
    public record Products(boolean collectingOff, boolean noShears) {}

    public record Report(List<KindLine> kinds, Products products) {}

    private RanchStatus() {}

    // =========================================================================
    // Working it out
    // =========================================================================

    public static Report compute(ServerLevel level, VillageManager village, RanchBuilding ranch) {
        List<Activity.Context> ranchers = new ArrayList<>();
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession() != Profession.RANCHER || model.brain() == null) {
                continue;
            }
            ranchers.add(new Activity.Context(model.brain(), model, village, level, npc));
        }
        List<Animal> inside = ranch.animalsInside(level);
        long now = level.getGameTime();
        Map<Item, Integer> stored = new HashMap<>();
        Map<EntityType<?>, Integer> heads = new HashMap<>();
        List<KindLine> lines = new ArrayList<>();
        for (EntityType<?> kind : KINDS) lines.add(line(level, village, ranch, ranchers, inside, kind, now, stored, heads));

        // The Rancher tries the kinds in turn, the longest waiting first: the first that can go is next.
        List<EntityType<?>> going = new ArrayList<>();
        for (KindLine line : lines) {
            if (line.reason() == Reason.READY || line.reason() == Reason.FETCH) going.add(line.kind());
        }
        if (!going.isEmpty()) {
            EntityType<?> next = RanchBreedTurns.inTurn(ranch, going, heads).get(0);
            for (int i = 0; i < lines.size(); i++) {
                KindLine line = lines.get(i);
                if (line.kind() == next) {
                    lines.set(i, new KindLine(line.kind(), Reason.NEXT, line.readyAt(), line.carried(),
                            line.perPair(), line.stock()));
                }
            }
        }
        return new Report(List.copyOf(lines), products(ranchers, inside));
    }

    private static KindLine line(ServerLevel level, VillageManager village, RanchBuilding ranch,
                                 List<Activity.Context> ranchers, List<Animal> inside, EntityType<?> kind, long now,
                                 Map<Item, Integer> stored, Map<EntityType<?>, Integer> heads) {
        List<Stock> stock = new ArrayList<>();
        int storedTotal = 0;
        for (Item food : RanchAnimals.breedFoodsFor(kind)) {
            int count = stored.computeIfAbsent(food, item -> storedCount(level, village, item));
            stock.add(new Stock(food, count));
            storedTotal += count;
        }
        if (ranchers.isEmpty()) return new KindLine(kind, Reason.NO_RANCHER, 0, 0, 0, stock);

        // The Rancher's side: a kind goes no further than the best of them gets.
        List<Activity.Context> breeders = new ArrayList<>();
        Reason refused = Reason.WORK_OFF;
        for (Activity.Context ctx : ranchers) {
            ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
            Reason why = !settings.getBool(RancherSettingsSchema.WORK_ENABLED) ? Reason.WORK_OFF
                    : !settings.getBool(RancherSettingsSchema.BREEDING_ENABLED) ? Reason.BREEDING_OFF
                    : !RancherActivity.isAllowedSpecies(settings, kind) ? Reason.UNTICKED : null;
            if (why == null) breeders.add(ctx);
            else if (why.ordinal() > refused.ordinal()) refused = why;
        }
        if (breeders.isEmpty()) return new KindLine(kind, refused, 0, 0, 0, stock);

        // The pen's side, as the Rancher counts it.
        Activity.Context first = breeders.get(0);
        List<Animal> herd = new ArrayList<>();
        int halfHeads = 0;
        for (Animal animal : inside) {
            if (animal.getType() != kind) continue;
            herd.add(animal);
            halfHeads += RancherActivity.halfHeads(first, animal);
        }
        heads.put(kind, halfHeads);
        if (halfHeads >= 2 * ranch.capacityPerSpecies()) return new KindLine(kind, Reason.FULL, 0, 0, 0, stock);
        List<Animal> adults = herd.stream().filter(animal -> !animal.isBaby()).toList();
        if (adults.size() < 2) return new KindLine(kind, Reason.TOO_FEW, 0, 0, 0, stock);
        int ready = 0;
        for (Activity.Context ctx : breeders) {
            int count = 0;
            for (Animal animal : adults) {
                if (animal.getAge() == 0 && !animal.isInLove() && RancherActivity.free(ctx, animal)) count++;
            }
            ready = Math.max(ready, count);
        }
        if (ready < 2) {
            if (adults.stream().anyMatch(Animal::isInLove)) return new KindLine(kind, Reason.IN_LOVE, 0, 0, 0, stock);
            List<Long> free = new ArrayList<>();
            boolean resting = false;
            for (Animal animal : adults) {
                if (animal.getAge() > 0) {
                    resting = true;
                    free.add(now + animal.getAge());
                } else if (!animal.isInLove()) {
                    free.add(now);
                }
            }
            if (!resting) return new KindLine(kind, Reason.BUSY, 0, 0, 0, stock);
            free.sort(null);
            long readyAt = free.size() >= 2 ? free.get(1) : 0;
            return new KindLine(kind, Reason.RESTING, readyAt, 0, 0, stock);
        }

        // Food: the Rancher that is best off.
        Reason best = null;
        int carried = 0, perPair = 0;
        for (Activity.Context ctx : breeders) {
            int pair = RancherBalance.foodPerPair(ctx.brain().rancherSkillLevel());
            int inPack = RancherActivity.carriedFeedCount(ctx, kind);
            Reason food = inPack >= pair ? Reason.READY
                    : RancherActivity.feedToFetch(ctx, kind) != null ? Reason.FETCH
                    : inPack + storedTotal >= pair ? Reason.FOOD_HELD : Reason.NO_FOOD;
            if (best == null || food.ordinal() > best.ordinal()) {
                best = food;
                carried = inPack;
                perPair = pair;
            }
        }
        return new KindLine(kind, best, 0, carried, perPair, stock);
    }

    /** What every working storage room holds of {@code item}, whether the Rancher may take it or not. */
    private static int storedCount(ServerLevel level, VillageManager village, Item item) {
        int total = 0;
        for (Building building : village.getBuildings()) {
            if (building instanceof StorageBuilding storage && storage.isValid()) total += storage.countItem(level, item);
        }
        return total;
    }

    private static Products products(List<Activity.Context> ranchers, List<Animal> inside) {
        if (ranchers.isEmpty()) return new Products(false, false);
        boolean anyCollector = false, shearer = false, sheepCollector = false;
        for (Activity.Context ctx : ranchers) {
            ProfessionSettings settings = ProfessionSettings.of(ctx.npc(), RancherSettingsSchema.SCHEMA);
            if (!settings.getBool(RancherSettingsSchema.WORK_ENABLED)
                    || !settings.getBool(RancherSettingsSchema.PRODUCT_COLLECTION_ENABLED)) continue;
            anyCollector = true;
            if (!RancherActivity.isAllowedSpecies(settings, EntityType.SHEEP)) continue;
            sheepCollector = true;
            if (RancherActivity.hasShears(ctx.npc())) shearer = true;
        }
        boolean woolly = inside.stream().anyMatch(animal -> animal instanceof Sheep sheep && sheep.readyForShearing());
        return new Products(!anyCollector, woolly && sheepCollector && !shearer);
    }

    // =========================================================================
    // Words
    // =========================================================================

    public static Tone tone(Reason reason) {
        return switch (reason) {
            case FETCH, READY, NEXT -> Tone.GOOD;
            case FULL, TOO_FEW, IN_LOVE, RESTING, BUSY -> Tone.WAIT;
            default -> Tone.BLOCKED;
        };
    }

    /** The kind's line in the player's language: the same words in the window and in chat. */
    public static MutableComponent text(KindLine line, long now) {
        return switch (line.reason()) {
            case NO_RANCHER -> Tr.c("screen.dynamicvillages.ranch.why.no_rancher", "No Rancher here to breed them");
            case WORK_OFF -> Tr.c("screen.dynamicvillages.ranch.why.work_off", "The Rancher's Work switch is off");
            case BREEDING_OFF -> Tr.c("screen.dynamicvillages.ranch.why.breeding_off", "Breeding is switched off for the Rancher");
            case UNTICKED -> Tr.c("screen.dynamicvillages.ranch.why.unticked", "Unticked in the Rancher's Managed animals");
            case FULL -> Tr.c("screen.dynamicvillages.ranch.why.full", "Full: a bigger pen holds more");
            case TOO_FEW -> Tr.c("screen.dynamicvillages.ranch.why.too_few", "Needs 2 grown adults to breed");
            case IN_LOVE -> Tr.c("screen.dynamicvillages.ranch.why.in_love", "A pair is in love now");
            case RESTING -> line.readyAt() > now
                    ? Tr.c("screen.dynamicvillages.ranch.why.resting_in", "Resting after breeding, ready %s", in(line.readyAt() - now))
                    : Tr.c("screen.dynamicvillages.ranch.why.resting", "Resting after breeding");
            case BUSY -> Tr.c("screen.dynamicvillages.ranch.why.busy", "The adults are busy right now");
            case NO_FOOD -> noFood(line.kind());
            case FOOD_HELD -> Tr.c("screen.dynamicvillages.ranch.why.food_held", "Food is in storage, but the Rancher may not take it (Stock tab or a full pack)");
            case FETCH -> Tr.c("screen.dynamicvillages.ranch.why.fetch", "Ready: the Rancher fetches the food from storage");
            case READY -> Tr.c("screen.dynamicvillages.ranch.why.ready", "Ready: the Rancher has the food");
            case NEXT -> Tr.c("screen.dynamicvillages.ranch.why.next", "Next to breed");
        };
    }

    private static MutableComponent noFood(EntityType<?> kind) {
        if (kind == EntityType.PIG) return Tr.c("screen.dynamicvillages.ranch.why.no_food_pig", "Not enough carrots, potatoes or beetroot in storage");
        if (kind == EntityType.CHICKEN) return Tr.c("screen.dynamicvillages.ranch.why.no_food_chicken", "Not enough seeds in storage");
        return Tr.c("screen.dynamicvillages.ranch.why.no_food_wheat", "Not enough wheat in storage");
    }

    /** "in about 4 minutes": real minutes, rounded. */
    public static MutableComponent in(long ticks) {
        if (ticks < 1200) return Tr.c("screen.dynamicvillages.ranch.tip.under_minute", "in under a minute");
        long minutes = Math.round(ticks / 1200.0);
        return minutes == 1 ? Tr.c("screen.dynamicvillages.ranch.tip.one_minute", "in about 1 minute")
                : Tr.c("screen.dynamicvillages.ranch.tip.minutes", "in about %s minutes", minutes);
    }
}
