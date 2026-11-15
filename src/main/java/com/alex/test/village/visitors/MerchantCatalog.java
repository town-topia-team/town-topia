package com.alex.test.village.visitors;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry;
import com.alex.test.village.villagers.farmer.settings.FarmerPlantingResources;
import com.alex.test.village.villagers.lumberjack.data.TreeTypeRegistry;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import com.alex.test.village.building.StorageInventory;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.common.Tags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Creates the visit catalog from one snapshot of storage and the workers'
 * packs, and queries live stock. Each profession present is asked for goods
 * the village actually has, plus one random good from its line of work.
 */
public final class MerchantCatalog {
    private MerchantCatalog() {}

    /** Most goods on hand offered per profession, before the random extras. */
    private static final int MAX_STOCKED_OFFERS = 3;

    /**
     * Never bought, even if a job's list below names one again: what a mine
     * or gravel pit is mostly made of, the archers' arrow flint, and charcoal
     * (the cook burns logs for it, for the village's own torches and furnaces).
     * Checked by {@link #sellable}, so neither the merchant nor the tradesman
     * ever deals in them.
     */
    private static final Set<Item> NEVER_BOUGHT = Set.of(Items.COBBLESTONE, Items.COBBLED_DEEPSLATE,
            Items.FLINT, Items.DIRT, Items.SAND, Items.GRAVEL, Items.CHARCOAL);

    public static List<StorageInventory> containers(ServerLevel level, VillageManager village) {
        List<StorageInventory> result = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Building building : village.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()
                    || !VillageTokenBinding.of(village).equals(storage.owner(level))) continue;
            for (StorageInventory chest : storage.inventories(level)) {
                if (seen.add(chest.key())) result.add(chest);
            }
        }
        return result;
    }

    public static int count(List<StorageInventory> containers, Item item, VillageManager village) {
        int result = 0;
        for (StorageInventory container : containers)
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.peek(slot);
                if (stack.is(item) && VillageItemOwnership.mayTrade(stack, village)) result += stack.getCount();
            }
        return result;
    }

    public static List<Integer> counts(List<MerchantOffer> offers, List<StorageInventory> containers,
                                       VillageManager village) {
        List<Integer> result = new ArrayList<>(offers.size());
        for (MerchantOffer offer : offers) result.add(count(containers, item(offer), village));
        return List.copyOf(result);
    }

    public static Item item(MerchantOffer offer) {
        var id = net.minecraft.resources.ResourceLocation.tryParse(offer.itemId());
        return id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
    }

    public static List<MerchantOffer> create(ServerLevel level, VillageManager village) {
        // What the village holds right now: every storage chest plus every
        // worker's pack. A worker's own produce also marks that item as its
        // profession's, so a lumberjack carrying oak gets an oak offer before
        // the logs have even reached storage.
        Map<Item, Integer> held = new HashMap<>();
        for (StorageInventory container : containers(level, village))
            for (int slot = 0; slot < container.size(); slot++) {
                ItemStack stack = container.peek(slot);
                if (VillageItemOwnership.mayTrade(stack, village))
                    held.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        Map<Profession, Integer> professionCounts = new EnumMap<>(Profession.class);
        Map<Profession, Set<Item>> carriedProduce = new EnumMap<>(Profession.class);
        // Built once per catalog: the farmer's list walks the whole item registry.
        Map<Profession, List<Item>> candidates = new EnumMap<>(Profession.class);
        village.getVillagers().forEach(model -> {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) return;
            Profession profession = npc.getProfession();
            professionCounts.merge(profession, 1, Integer::sum);
            InventoryManager inventory = npc.getInventory();
            for (int slot = 0; slot < InventoryManager.SIZE; slot++) {
                ItemStack stack = inventory.getSlot(slot);
                if (!VillageItemOwnership.mayTrade(stack, village)) continue;
                // Working supplies (torches, ingredients, breeding feed) never
                // reach storage, so they are not goods the village can part
                // with. A farmer's carrots and potatoes are kept as seed but
                // are still its harvest, so its own produce always counts.
                boolean product = ProfessionSupplyRules.isProfessionProduct(npc, stack);
                if (!product && ProfessionSupplyRules.shouldKeepFromGenericDeposit(npc, stack)) continue;
                held.merge(stack.getItem(), stack.getCount(), Integer::sum);
                if (product || candidates.computeIfAbsent(profession, MerchantCatalog::candidates)
                        .contains(stack.getItem()))
                    carriedProduce.computeIfAbsent(profession, ignored -> new HashSet<>())
                            .add(stack.getItem());
            }
        });

        Random random = new Random(level.random.nextLong());
        Map<Profession, List<Item>> stockedByProfession = new EnumMap<>(Profession.class);
        Map<Profession, List<Item>> extrasByProfession = new EnumMap<>(Profession.class);
        for (Profession profession : professionCounts.keySet()) {
            Set<Item> own = carriedProduce.getOrDefault(profession, Set.of());
            LinkedHashSet<Item> pool = new LinkedHashSet<>(own);
            // Its products in storage count too, not only those on its list or
            // in a worker's pack: pink wool in a chest is still the rancher's.
            // Always on hand, so they are never one of the random extras.
            for (Item item : held.keySet())
                if (ProfessionSupplyRules.isProductOf(profession, new ItemStack(item))) pool.add(item);
            pool.addAll(candidates.computeIfAbsent(profession, MerchantCatalog::candidates));
            pool.removeIf(item -> !sellable(item));
            if (pool.isEmpty()) continue;
            // Whatever the village holds most of first, whoever carries it:
            // nine stacks of oak in storage must not lose its place to the
            // few logs a lumberjack has in its pack. The profession's own
            // carried produce only breaks a tie.
            List<Item> stocked = new ArrayList<>(pool.stream()
                    .filter(item -> held.getOrDefault(item, 0) > 0)
                    .sorted(Comparator.comparingInt((Item item) -> -held.get(item))
                            .thenComparing(item -> !own.contains(item)))
                    .toList());
            List<Item> extras = new ArrayList<>(pool);
            extras.removeAll(stocked);
            Collections.shuffle(extras, random);
            stockedByProfession.put(profession, stocked);
            extrasByProfession.put(profession, extras);
        }

        // Pick in rounds over a shuffled profession order so goods two
        // professions share (coal, leather, paper) are split fairly instead of
        // always going to the first enum category. Goods on hand come first:
        // up to two for a lone worker, three when several share the job. Then
        // one random request per profession for a good it could bring.
        List<Profession> order = new ArrayList<>(stockedByProfession.keySet());
        Collections.shuffle(order, random);
        Map<Profession, List<Item>> selected = new EnumMap<>(Profession.class);
        Set<Item> used = new HashSet<>();
        // The biggest sellable pile the village holds, found before the rounds
        // below take goods off the lists, and the profession that could bring it.
        Item biggest = null;
        Profession biggestOwner = null;
        for (Profession profession : order) {
            for (Item item : stockedByProfession.get(profession)) {
                if (biggest == null || held.get(item) > held.get(biggest)) {
                    biggest = item;
                    biggestOwner = profession;
                }
            }
        }
        for (int round = 0; round < MAX_STOCKED_OFFERS; round++) {
            for (Profession profession : order) {
                if (round >= Math.min(MAX_STOCKED_OFFERS, professionCounts.get(profession) + 1)) continue;
                Item choice = takeFirstUnused(stockedByProfession.get(profession), used);
                if (choice == null) continue;
                used.add(choice);
                selected.computeIfAbsent(profession, ignored -> new ArrayList<>()).add(choice);
            }
        }
        // That pile is always asked for, even when the rounds ran out of slots
        // before reaching it: it goes to the profession on top of its normal
        // picks. Placed here, it is exempt from the lumberjack's one-log-per-tree
        // rule in choose(), which only shapes the random extras after it.
        if (biggest != null && !used.contains(biggest)) {
            used.add(biggest);
            selected.computeIfAbsent(biggestOwner, ignored -> new ArrayList<>()).add(biggest);
        }
        for (Profession profession : order) {
            Item choice = choose(extrasByProfession.get(profession), used, profession);
            if (choice == null) continue;
            used.add(choice);
            selected.computeIfAbsent(profession, ignored -> new ArrayList<>()).add(choice);
        }

        List<MerchantOffer> offers = new ArrayList<>();
        for (Profession profession : Profession.values()) {
            for (Item item : selected.getOrDefault(profession, List.of()))
                offers.add(new MerchantOffer(profession.displayName(),
                        BuiltInRegistries.ITEM.getKey(item).toString(), MerchantPricing.fullStackPrice(item)));
        }
        return List.copyOf(offers);
    }

    private static Item takeFirstUnused(List<Item> choices, Set<Item> used) {
        while (!choices.isEmpty()) {
            Item candidate = choices.remove(0);
            if (!used.contains(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Stackable goods that split into exact half stacks. Never currency, tools,
     * armour, sticks, seeds, rubble (cobblestone, cobbled deepslate), flint,
     * charcoal or loose ground (dirt, sand, gravel); a crop that is both seed and food
     * (carrot, potato) is food first and stays wanted, and so does sugar cane, the
     * one crop planted from its own harvest that is wanted anyway (not cocoa beans).
     */
    public static boolean sellable(Item item) {
        if (item == Items.AIR || item == Items.EMERALD || item == Items.STICK
                || item instanceof ArmorItem || NEVER_BOUGHT.contains(item)) return false;
        ItemStack sample = new ItemStack(item);
        if (isUnprocessedOre(sample)) return false;
        int max = sample.getMaxStackSize();
        return max >= 2 && max % 2 == 0 && !ToolSelectionHelper.isProfessionTool(sample)
                && !(isSeed(sample) && !VillageItemOwnership.isVillagerFood(sample) && item != Items.SUGAR_CANE);
    }

    /**
     * Ore that still needs smelting: raw iron, copper and gold, their raw
     * blocks, and ore blocks. The merchant buys the finished goods, not the
     * rock. Ores that drop a finished item (coal, diamond, redstone, lapis,
     * quartz, amethyst) are not raw and stay sellable; emeralds never are,
     * they are the money.
     */
    public static boolean isUnprocessedOre(ItemStack stack) {
        return stack.is(Tags.Items.RAW_MATERIALS) || stack.is(Tags.Items.ORES)
                || stack.is(Items.RAW_IRON_BLOCK) || stack.is(Items.RAW_COPPER_BLOCK)
                || stack.is(Items.RAW_GOLD_BLOCK);
    }

    private static boolean isSeed(ItemStack stack) {
        return stack.is(ItemTags.VILLAGER_PLANTABLE_SEEDS) || stack.is(Tags.Items.SEEDS)
                || FarmerPlantingResources.isSupported(stack.getItem());
    }

    private static Item choose(List<Item> choices, Set<Item> used, Profession profession) {
        int fallback = -1;
        for (int i = 0; i < choices.size(); i++) {
            Item candidate = choices.get(i);
            if (used.contains(candidate)) continue;
            if (fallback < 0) fallback = i;
            if (profession != Profession.LUMBERJACK || used.stream()
                    .noneMatch(existing -> sameTreeFamily(existing, candidate))) {
                return choices.remove(i);
            }
        }
        return fallback < 0 ? null : choices.remove(fallback);
    }

    private static boolean sameTreeFamily(Item first, Item second) {
        return first instanceof BlockItem a && second instanceof BlockItem b
                && TreeTypeRegistry.sameLogFamily(a.getBlock(), b.getBlock());
    }

    private static List<Item> lumberjackCandidates() {
        LinkedHashSet<Item> items = new LinkedHashSet<>();
        // One log per tree kind: lumberjacks bring home logs, never the
        // bark-on-every-side "Wood" blocks, which sat in the random extras forever.
        for (var type : TreeTypeRegistry.all()) {
            if (type.id().getPath().equals("azalea")) continue;
            var log = TreeTypeRegistry.plainLog(type);
            if (log != null) items.add(log.asItem());
        }
        // Chopping also yields apples (ChopTreeActivity rewards).
        items.add(Items.APPLE);
        return List.copyOf(items);
    }

    /**
     * Every good some profession's merchant visit could ask for, in a stable
     * order: its list below plus anything it exists to produce (a rancher's red
     * wool, a cook's pumpkin pie), which is all {@link #create} ever offers.
     * The Sale items tab lists these so a player can set one up before the
     * village has any.
     */
    public static List<Item> everyCandidate() {
        return goods().ordered();
    }

    /**
     * Whether any merchant visit could ask for {@code item}. Dirt, sand and
     * the rest of what piles up in storage never can.
     */
    public static boolean mightAskFor(Item item) {
        return goods().all().contains(item);
    }

    /** Whether a visit to a village with these professions could ask for {@code item}. */
    public static boolean mightAskFor(Item item, Set<Profession> professions) {
        Map<Profession, Set<Item>> byProfession = goods().byProfession();
        for (Profession profession : professions) {
            if (byProfession.getOrDefault(profession, Set.of()).contains(item)) return true;
        }
        return false;
    }

    /** The professions living in {@code village}, as {@link #create} counts them. */
    public static Set<Profession> professionsIn(VillageManager village) {
        Set<Profession> professions = EnumSet.noneOf(Profession.class);
        village.getVillagers().forEach(model -> {
            NpcEntity npc = model.puppet();
            if (npc != null && npc.isAlive()) professions.add(npc.getProfession());
        });
        return professions;
    }

    /** A tag reload can change what counts as wool, seed or ore. */
    public static void onTagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
        goods = null;
    }

    private record Goods(List<?> crops, List<Item> ordered, Set<Item> all,
                         Map<Profession, Set<Item>> byProfession) {}

    /** Built once: the farmer's list and the product check walk the whole item registry. */
    private static volatile Goods goods;

    private static Goods goods() {
        // The crop table is replaced on every datapack reload; a new one means
        // the farmer's list may have changed.
        List<?> crops = CropDefinitionRegistry.all();
        Goods cached = goods;
        if (cached != null && cached.crops() == crops) return cached;
        Map<Profession, LinkedHashSet<Item>> found = new EnumMap<>(Profession.class);
        for (Profession profession : Profession.values())
            found.put(profession, new LinkedHashSet<>(candidates(profession)));
        for (Item item : BuiltInRegistries.ITEM) {
            ItemStack sample = new ItemStack(item);
            for (Profession profession : Profession.values()) {
                if (ProfessionSupplyRules.isProductOf(profession, sample)) found.get(profession).add(item);
            }
        }
        Map<Profession, Set<Item>> byProfession = new EnumMap<>(Profession.class);
        LinkedHashSet<Item> ordered = new LinkedHashSet<>();
        for (var entry : found.entrySet()) {
            entry.getValue().removeIf(item -> !sellable(item));
            byProfession.put(entry.getKey(), Set.copyOf(entry.getValue()));
            ordered.addAll(entry.getValue());
        }
        cached = new Goods(crops, List.copyOf(ordered), Set.copyOf(ordered), byProfession);
        goods = cached;
        return cached;
    }

    private static List<Item> candidates(Profession profession) {
        return switch (profession) {
            case LUMBERJACK -> lumberjackCandidates();
            case FARMER -> {
                LinkedHashSet<Item> items = new LinkedHashSet<>(List.of(Items.WHEAT, Items.CARROT,
                        Items.POTATO, Items.BEETROOT, Items.PUMPKIN, Items.MELON_SLICE));
                items.addAll(FarmerPlantingResources.allSupported());
                yield List.copyOf(items);
            }
            // No rubble: cobblestone and cobbled deepslate are what a mine is
            // mostly made of, so they took every visit and paid next to nothing.
            // Every good below must have a villager who makes or brings it home:
            // one nobody makes is never on hand, so it is the random extra on
            // visit after visit (rabbits, spectral arrows, maps were).
            case MINER -> List.of(Items.COAL, Items.REDSTONE, Items.DIAMOND);
            // The colours sheep come in; any other colour in storage is still
            // asked for, as the rancher's product (see create).
            case RANCHER -> List.of(Items.EGG, Items.WHITE_WOOL, Items.BLACK_WOOL,
                    Items.BROWN_WOOL, Items.GRAY_WOOL, Items.LIGHT_GRAY_WOOL, Items.LEATHER);
            // Feathers come only from its chicken kills (ButcherDrops.hideFor).
            case BUTCHER -> List.of(Items.BEEF, Items.PORKCHOP, Items.CHICKEN,
                    Items.MUTTON, Items.LEATHER, Items.FEATHER);
            case COOK -> List.of(Items.BREAD, Items.COOKED_BEEF, Items.COOKED_PORKCHOP,
                    Items.COOKED_CHICKEN, Items.BAKED_POTATO, Items.COOKIE);
            // Coal is the miner's; charcoal is never bought (NEVER_BOUGHT).
            case BLACKSMITH -> List.of(Items.IRON_INGOT, Items.COPPER_INGOT,
                    Items.GOLD_INGOT, Items.COAL);
            // Not flint or feathers: the archers make their arrows from them.
            case ARCHER -> List.of(Items.ARROW, Items.STRING);
            case ENCHANTER -> List.of(Items.BOOK, Items.LAPIS_LAZULI, Items.PAPER);
            // Guard, bard, druid, builder, cleric, teacher and tradesman produce
            // nothing a merchant buys: guards do not collect mob drops, the druid's
            // torches are its own, the builder, cleric and teacher make nothing
            // (the school's paper and books are the enchanter's), and the tradesman
            // only sells what the others make. No offers in their name.
            default -> List.of();
        };
    }
}
