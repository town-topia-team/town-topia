package com.alex.test.town;

import com.alex.test.ModItems;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BarracksBuilding;
import com.alex.test.village.villagers.guard.CaptainAssignmentHandler;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.ForgeBuilding;
import com.alex.test.village.building.ButcheryBuilding;
import com.alex.test.village.building.KitchenBuilding;
import com.alex.test.village.building.LibraryBuilding;
import com.alex.test.village.building.MerchantStallBuilding;
import com.alex.test.village.building.MineBuilding;
import com.alex.test.village.building.RanchBuilding;
import com.alex.test.village.building.SchoolBuilding;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.building.TavernBuilding;
import com.alex.test.village.building.TownHallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The Mayor's emerald shop: what a village will sell the player, for how much,
 * and the act of selling it.
 *
 * <p>This is the mod's survival on-ramp. Before it, every profession and
 * structure token existed only in the creative tab, so a survival world could
 * place a Town Hall and then go no further. The Town Hall itself is crafted
 * (see {@code data/dynamicvillages/recipe/town_hall.json}); everything after it is
 * bought here.
 *
 * <p><b>Prices rise with the village.</b> A hamlet of two sells a 2-Bed House token
 * for a handful of emeralds; a town of twenty charges several times that, so
 * expansion has to be paid for by the economy the expansion creates. Size is
 * counted as villagers plus registered buildings, with the Town Hall itself
 * excluded so that a village founded one second ago is at size zero and its
 * first four purchases are exactly what the starter chest funds.
 *
 * <p>Everything here is server-side. The client gets {@link TradeOfferEntry}
 * rows and asks for a purchase by item id; it never computes a price.
 */
public final class MayorShop {

    /** Fraction of the base price added per point of village size (villagers plus buildings). */
    public static final double PRICE_GROWTH_PER_SIZE = 0.03;
    /**
     * Fraction of the base price added for every one of the same token the
     * village already has: placed buildings or villagers in that job, plus
     * this village's tokens the buyer still carries, so buying several at
     * once does not dodge it.
     */
    public static final double PRICE_GROWTH_PER_OWNED = 0.1;
    /** The size part never takes a token past this many times its base; owned growth comes on top. */
    public static final double MAX_PRICE_MULTIPLIER = 3.5;
    /** Owned growth stops after this many of the same token (+50%); more cost no extra. */
    public static final int MAX_OWNED_STEPS = 5;

    /** Currency. One item, everywhere, so "how much do I have" is a single count. */
    public static final Item CURRENCY = Items.EMERALD;

    private MayorShop() {}

    /**
     * A catalogue line before pricing: the item, what it costs in a brand-new
     * village, and the building that makes it useful.
     */
    public record CatalogueEntry(
            Supplier<Item> item,
            String displayName,
            int basePrice,
            boolean profession,
            @Nullable Class<? extends Building> worksIn,
            String worksInName,
            String withoutBuildingHint) {

        CatalogueEntry(Supplier<Item> item, String displayName, int basePrice, boolean profession) {
            this(item, displayName, basePrice, profession, null, "", "");
        }

        CatalogueEntry(Supplier<Item> item, String displayName, int basePrice, boolean profession,
                       @Nullable Class<? extends Building> worksIn, String worksInName) {
            this(item, displayName, basePrice, profession, worksIn, worksInName, "");
        }
    }

    /**
     * Structures first, then jobs — the order a village actually grows in, so
     * scrolling the shop reads like a build order rather than an inventory.
     */
    private static final List<CatalogueEntry> CATALOGUE = List.of(
            // --- Structures -------------------------------------------------
            // 10-10: Tektopia-style prices. Buildings carry the cost (a Tavern
            // 45, a 2 Bed House 6), workers are cheap (3 to 15), nothing below
            // 3. One early merchant visit no longer buys the late game.
            // Growth still raises them: 3% per villager and building (at most x3.5),
            // then 10% more per one of the same you own (at most +50%).
            new CatalogueEntry(ModItems.TWO_BED_HOUSE, "2 Bed House", 6, false),
            new CatalogueEntry(ModItems.FOUR_BED_HOUSE, "4 Bed House", 13, false),
            new CatalogueEntry(ModItems.SIX_BED_HOUSE, "6 Bed House", 20, false),
            new CatalogueEntry(ModItems.STORAGE_ROOM, "Storage", 6, false),
            new CatalogueEntry(ModItems.GUARD_POST, "Guard Post", 3, false),
            new CatalogueEntry(ModItems.KITCHEN, "Kitchen", 18, false),
            new CatalogueEntry(ModItems.BUTCHERY, "Butchery", 25, false),
            new CatalogueEntry(ModItems.RANCH, "Ranch", 20, false),
            new CatalogueEntry(ModItems.MINE, "Mine", 3, false),
            new CatalogueEntry(ModItems.ARCHERY_RANGE, "Archery Range", 20, false),
            new CatalogueEntry(ModItems.WATCHTOWER, "Watchtower", 10, false),
            new CatalogueEntry(ModItems.FORGE, "Forge", 30, false),
            new CatalogueEntry(ModItems.STALL, "Merchant Stall", 20, false),
            new CatalogueEntry(ModItems.BARRACKS, "Barracks", 35, false),
            new CatalogueEntry(ModItems.SCHOOL, "School", 28, false),
            new CatalogueEntry(ModItems.TAVERN, "Tavern", 36, false),
            new CatalogueEntry(ModItems.LIBRARY, "Library", 30, false),
            new CatalogueEntry(ModItems.MAGE_TOWER, "Mage Tower", 32, false),

            // --- Jobs -------------------------------------------------------
            new CatalogueEntry(ModItems.LUMBERJACK_TOKEN, "Lumberjack", 3, true),
            // Farmers discover irrigated fields inside the village boundary.
            new CatalogueEntry(ModItems.FARMER_TOKEN, "Farmer", 3, true),
            new CatalogueEntry(ModItems.GUARD_TOKEN, "Guard", 5, true),
            new CatalogueEntry(ModItems.RANCHER_TOKEN, "Rancher", 3, true,
                    RanchBuilding.class, "Ranch"),
            new CatalogueEntry(ModItems.COOK_TOKEN, "Cook", 7, true,
                    KitchenBuilding.class, "Kitchen"),
            new CatalogueEntry(ModItems.BUTCHER_TOKEN, "Butcher", 4, true,
                    ButcheryBuilding.class, "Butchery"),
            new CatalogueEntry(ModItems.MINER_TOKEN, "Miner", 4, true,
                    MineBuilding.class, "Mine"),
            // Archers defend without one; the range is where they practise and fletch.
            new CatalogueEntry(ModItems.ARCHER_TOKEN, "Archer", 5, true,
                    ArcheryRangeBuilding.class, "Archery Range",
                    "works without an Archery Range, and practises at one once you build it"),
            // Hired at a Mage Tower or a Barracks (the token only works in a village with one).
            new CatalogueEntry(ModItems.MAGE_TOKEN, "Mage", 9, true,
                    BarracksBuilding.class, "Barracks or a Mage Tower"),
            // Normally comes free with its Barracks; this row only shows while a
            // Barracks has no captain, to replace one that died.
            new CatalogueEntry(ModItems.CAPTAIN_TOKEN, "Captain", 3, true,
                    BarracksBuilding.class, "Barracks"),
            new CatalogueEntry(ModItems.TRADESMAN_TOKEN, "Tradesman", 5, true,
                    MerchantStallBuilding.class, "Merchant Stall"),
            new CatalogueEntry(ModItems.BARD_TOKEN, "Bard", 13, true,
                    TavernBuilding.class, "Tavern"),
            new CatalogueEntry(ModItems.BLACKSMITH_TOKEN, "Blacksmith", 5, true,
                    ForgeBuilding.class, "Forge"),
            new CatalogueEntry(ModItems.TEACHER_TOKEN, "Teacher", 8, true,
                    SchoolBuilding.class, "School"),
            new CatalogueEntry(ModItems.BUILDER_TOKEN, "Builder", 4, true),
            new CatalogueEntry(ModItems.CLERIC_TOKEN, "Cleric", 12, true),
            new CatalogueEntry(ModItems.DRUID_TOKEN, "Druid", 10, true),
            new CatalogueEntry(ModItems.ENCHANTER_TOKEN, "Enchanter", 15, true,
                    LibraryBuilding.class, "Library"));

    // -------------------------------------------------------------------------
    // Pricing
    // -------------------------------------------------------------------------

    /**
     * How big the village is for pricing purposes: villagers plus registered
     * buildings, minus the Town Hall that every village has by definition.
     */
    public static int villageSize(@Nullable VillageManager village) {
        if (village == null) return 0;
        int size = village.getVillagers().size();
        for (Building building : village.getBuildings()) {
            if (building.isValid() && !(building instanceof TownHallBuilding)) size++;
        }
        return size;
    }

    /** What {@code basePrice} costs in a village of {@code size} that has none of it yet. */
    public static int priceAt(int basePrice, int size) {
        return priceAt(basePrice, size, 0);
    }

    /**
     * What {@code basePrice} costs in a village of {@code size} that already has
     * {@code owned} of the same token: 3% per villager and building, at most
     * {@link #MAX_PRICE_MULTIPLIER} times the base, then 10% more per one owned.
     */
    public static int priceAt(int basePrice, int size, int owned) {
        double multiplier = Math.min(MAX_PRICE_MULTIPLIER, multiplierAt(size)) * ownedMultiplier(owned);
        return Math.max(1, (int) Math.ceil(basePrice * multiplier - 1e-9));
    }

    /** The size part of the multiplier, the same for every token, for display. */
    public static double multiplierAt(int size) {
        return 1.0 + PRICE_GROWTH_PER_SIZE * Math.max(0, size);
    }

    /** The owned part of the multiplier: 1, 1.1, 1.2... up to 1.5 at five owned. */
    public static double ownedMultiplier(int owned) {
        return 1.0 + PRICE_GROWTH_PER_OWNED * Math.clamp(owned, 0, MAX_OWNED_STEPS);
    }

    /** The whole shop, priced for this village. */
    public static List<TradeOfferEntry> offers(@Nullable VillageManager village) {
        return offers(village, null);
    }

    /** The whole shop, priced for this village and the tokens {@code buyer} carries. */
    public static List<TradeOfferEntry> offers(@Nullable VillageManager village, @Nullable Player buyer) {
        return offers(village, villageSize(village), buyer);
    }

    /**
     * The whole shop priced for a village of {@code size}, whatever size the
     * given village actually is. Only the hints come from the village itself.
     * Tests use this to ask what a just-founded village would charge without
     * having to found one.
     */
    public static List<TradeOfferEntry> offers(@Nullable VillageManager village, int size) {
        return offers(village, size, null);
    }

    public static List<TradeOfferEntry> offers(@Nullable VillageManager village, int size, @Nullable Player buyer) {
        List<TradeOfferEntry> offers = new ArrayList<>(CATALOGUE.size());
        for (CatalogueEntry entry : CATALOGUE) {
            if (isCaptainRow(entry) && !CaptainAssignmentHandler.hasOpenBarracks(village, null)) continue;
            Item item = entry.item().get();
            int owned = owned(entry, village, buyer);
            offers.add(new TradeOfferEntry(
                    BuiltInRegistries.ITEM.getKey(item).toString(),
                    entry.displayName(),
                    priceAt(entry.basePrice(), size, owned),
                    entry.profession(),
                    hintFor(entry, village, owned)));
        }
        return List.copyOf(offers);
    }

    // -------------------------------------------------------------------------
    // How many the village already has
    // -------------------------------------------------------------------------

    /**
     * How many of {@code entry}'s token the village already has: villagers in
     * that job, or registered buildings of that kind (houses by size), plus
     * this village's tokens of it that {@code buyer} carries unplaced. The
     * Captain row stays at its base: it only replaces a lost captain.
     */
    public static int owned(CatalogueEntry entry, @Nullable VillageManager village, @Nullable Player buyer) {
        if (village == null || isCaptainRow(entry)) return 0;
        Item item = entry.item().get();
        int count = 0;
        if (entry.profession()) {
            var profession = com.alex.test.village.villagers.registry.VillagerJobManager
                    .professionForToken(new ItemStack(item));
            if (profession != null) {
                for (var model : village.getVillagers()) {
                    var npc = model.puppet();
                    if (npc != null && npc.getProfession() == profession) count++;
                }
            }
        } else {
            var type = com.alex.test.village.tokens.ItemFrameTokenRegistry.tokenType(new ItemStack(item));
            if (type == com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType.GUARD_POST) {
                if (village.getLevel() instanceof net.minecraft.server.level.ServerLevel level) {
                    count += com.alex.test.village.villagers.guard.behavior.GuardPostRegistry.get(level)
                            .countValidPosts(level, village);
                }
            } else if (type != null) {
                for (Building building : village.getBuildings()) {
                    if (building.isValid() && isOfType(building, type)) count++;
                }
            }
        }
        if (buyer != null) {
            count += MayorShop.countMatching(buyer.getInventory(),
                    stack -> stack.is(item) && VillageTokenBinding.matches(stack, village));
        }
        return count;
    }

    private static boolean isOfType(Building building,
                                    com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType type) {
        return switch (type) {
            case TWO_BED_HOUSE -> building instanceof com.alex.test.village.building.HouseBuilding house
                    && house.getCapacity() == 2;
            case FOUR_BED_HOUSE -> building instanceof com.alex.test.village.building.HouseBuilding house
                    && house.getCapacity() == 4;
            case SIX_BED_HOUSE -> building instanceof com.alex.test.village.building.HouseBuilding house
                    && house.getCapacity() == 6;
            case STORAGE -> building instanceof StorageBuilding;
            case KITCHEN -> building instanceof KitchenBuilding;
            case BUTCHERY -> building instanceof ButcheryBuilding;
            case FORGE -> building instanceof ForgeBuilding;
            case MINE -> building instanceof MineBuilding;
            case RANCH -> building instanceof RanchBuilding;
            case TAVERN -> building instanceof TavernBuilding;
            case LIBRARY -> building instanceof LibraryBuilding;
            case SCHOOL -> building instanceof SchoolBuilding;
            case STALL -> building instanceof MerchantStallBuilding;
            case BARRACKS -> building instanceof BarracksBuilding;
            case ARCHERY_RANGE -> building instanceof ArcheryRangeBuilding;
            case WATCHTOWER -> building instanceof com.alex.test.village.building.WatchtowerBuilding;
            case MAGE_TOWER -> building instanceof com.alex.test.village.building.MageTowerBuilding;
            default -> false;
        };
    }

    /**
     * A job token is worth buying only once somewhere exists for that job to
     * happen. Say so on the row rather than refusing the sale — the player may
     * well be buying the token first on purpose. Otherwise, how many the
     * village has, since each one makes the next dearer.
     */
    private static String hintFor(CatalogueEntry entry, @Nullable VillageManager village, int owned) {
        String hint = hintFor(entry, village);
        if (!hint.isEmpty() || owned <= 0) return hint;
        if (owned >= MAX_OWNED_STEPS) return "you have " + owned;
        return "you have " + owned + ", each one adds 10% to the price";
    }

    private static String hintFor(CatalogueEntry entry, @Nullable VillageManager village) {
        if (entry.worksIn() == null) return "";
        if (hasBuilding(village, entry.worksIn())) return "";
        // A mage is hired at either; the row names both.
        if (entry.item().get() == ModItems.MAGE_TOKEN.get()
                && hasBuilding(village, com.alex.test.village.building.MageTowerBuilding.class)) return "";
        // A job that does not need the building says so instead of "needs".
        if (!entry.withoutBuildingHint().isEmpty()) return entry.withoutBuildingHint();
        String name = entry.worksInName();
        boolean vowel = !name.isEmpty() && "AEIOUaeiou".indexOf(name.charAt(0)) >= 0;
        return (vowel ? "needs an " : "needs a ") + name;
    }

    private static boolean hasBuilding(@Nullable VillageManager village,
                                       Class<? extends Building> type) {
        if (village == null) return false;
        for (Building building : village.getBuildings()) {
            if (building.isValid() && type.isInstance(building)) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Selling to the player
    // -------------------------------------------------------------------------

    /** What happened when the player asked to buy something. */
    public record Purchase(boolean success, String message, int price, int emeraldsLeft) {}

    /**
     * Sells one {@code itemId} to {@code player}, charging this village's
     * current price.
     *
     * <p>Prices are re-derived here rather than trusted from the client, so a
     * forged payload can only ask for an item in the catalogue and can only
     * ask at the price the village would have charged anyway.
     */
    public static Purchase buy(Player player, @Nullable VillageManager village, String itemId) {
        if (village == null || village.getLevel() != player.level()
                || village.getBuildings().stream().noneMatch(
                        building -> building instanceof TownHallBuilding && building.isValid())) {
            return new Purchase(false, "The Mayor has no active Town Hall.", 0,
                    villageEmeralds(player.getInventory(), village));
        }
        CatalogueEntry entry = find(itemId);
        if (entry == null) {
            return new Purchase(false, "The Mayor does not sell that.", 0,
                    villageEmeralds(player.getInventory(), village));
        }
        if (isCaptainRow(entry) && !CaptainAssignmentHandler.hasOpenBarracks(village, null)) {
            return new Purchase(false, CaptainAssignmentHandler.barracksCount(village) == 0
                    ? "A captain needs a Barracks. Buying a Barracks comes with its captain."
                    : "Every Barracks already has its captain.", 0,
                    villageEmeralds(player.getInventory(), village));
        }
        // No more tokens than there are captaincies open: the row used to sell
        // without limit while any one Barracks lacked its captain.
        if (isCaptainRow(entry) && carriedCaptainTokens(player, village)
                >= CaptainAssignmentHandler.openCaptaincies(village)) {
            return new Purchase(false, "You already carry a Captain token for every Barracks"
                    + " without a captain.", 0, villageEmeralds(player.getInventory(), village));
        }

        int price = priceAt(entry.basePrice(), villageSize(village), owned(entry, village, player));
        // Only emeralds this village earned (stamped with its ID) pay for its
        // tokens; plain or another village's emeralds are refused.
        int held = villageEmeralds(player.getInventory(), village);
        if (held < price) {
            boolean otherEmeralds = countCurrency(player.getInventory()) > held;
            return new Purchase(false,
                    "Not enough emeralds from this village: " + entry.displayName() + " costs " + price
                            + ", you have " + held + "."
                            + (otherEmeralds ? " The Mayor only takes emeralds with this village's name." : ""),
                    price, held);
        }

        ItemStack bought = new ItemStack(entry.item().get());
        VillageTokenBinding.bind(bought, village);
        UUID villageId = village.getVillageId();
        takeMatching(player.getInventory(), stack -> isVillageEmerald(stack, villageId), price);
        if (!player.getInventory().add(bought)) player.drop(bought, false);
        // Every Barracks comes with the captain who will lead it.
        boolean withCaptain = entry.item().get() == ModItems.BARRACKS.get();
        if (withCaptain) {
            ItemStack captain = new ItemStack(ModItems.CAPTAIN_TOKEN.get());
            VillageTokenBinding.bind(captain, village);
            if (!player.getInventory().add(captain)) player.drop(captain, false);
        }

        return new Purchase(true,
                "Bought " + entry.displayName() + " for " + price + " emeralds"
                        + (withCaptain ? ", with a Captain token to lead it." : "."),
                price, held - price);
    }

    /** A paused village's new Town Hall token: this many emeralds... */
    public static final int REPLACEMENT_TOWN_HALL_EMERALDS = 2;
    /** ...or what crafting one takes: this many logs and a crafting table. */
    public static final int REPLACEMENT_TOWN_HALL_LOGS = 8;

    /**
     * Sells a new Town Hall token for a village paused without its own. The new
     * token becomes the village's only working one; any older token stops working.
     */
    public static Purchase buyReplacementTownHall(Player player, @Nullable VillageManager village,
                                                  @Nullable BlockPos townHall, boolean withEmeralds) {
        Inventory inventory = player.getInventory();
        int held = countCurrency(inventory);
        if (village == null || player.getServer() == null) {
            return new Purchase(false, "Your village is running again, so it needs no new token.", 0, held);
        }
        VillageManagerRegistry registry = VillageManagerRegistry.get(player.getServer());
        if (!registry.canWakeUp(village)) {
            return new Purchase(false, "Another village now stands here, so this one cannot wake up.", 0, held);
        }
        // Each new token makes the one before it useless: a second one would only waste the first.
        if (countMatching(inventory, stack -> stack.is(ModItems.TOWN_HALL.get())
                && VillageTokenBinding.matches(stack, village)
                && !registry.isReplacedTownHallToken(stack)) > 0) {
            return new Purchase(false, "You already carry this village's Town Hall token.", 0, held);
        }
        UUID villageId = village.getVillageId();
        int payable = replacementEmeralds(inventory, villageId);
        if (withEmeralds && payable < REPLACEMENT_TOWN_HALL_EMERALDS) {
            return new Purchase(false, "Not enough emeralds: a new token costs "
                    + REPLACEMENT_TOWN_HALL_EMERALDS + ", you have " + payable + ".", 0, held);
        }
        if (!withEmeralds && (replacementLogs(inventory, villageId) < REPLACEMENT_TOWN_HALL_LOGS
                || replacementCraftingTables(inventory, villageId) < 1)) {
            return new Purchase(false, "You need " + REPLACEMENT_TOWN_HALL_LOGS
                    + " logs and 1 crafting table.", 0, held);
        }

        ItemStack token = new ItemStack(ModItems.TOWN_HALL.get());
        if (!registry.issueTownHallToken(token, village)) {
            return new Purchase(false, "The Mayor could not make a token right now.", 0, held);
        }
        if (withEmeralds) {
            takeMatching(inventory, stack -> stack.is(CURRENCY)
                    && payableForReplacement(stack, villageId), REPLACEMENT_TOWN_HALL_EMERALDS);
        } else {
            takeMatching(inventory, stack -> stack.is(ItemTags.LOGS)
                    && payableForReplacement(stack, villageId), REPLACEMENT_TOWN_HALL_LOGS);
            takeMatching(inventory, stack -> stack.is(Items.CRAFTING_TABLE)
                    && payableForReplacement(stack, villageId), 1);
        }
        if (!inventory.add(token)) player.drop(token, false);
        String where = townHall != null ? "at " + townHall.toShortString() : "at the Town Hall";
        return new Purchase(true, "Here is your new token. Hang it in a frame " + where + ".",
                withEmeralds ? REPLACEMENT_TOWN_HALL_EMERALDS : 0, countCurrency(inventory));
    }

    /**
     * What a replacement token may be paid with: the player's own plain items,
     * or the paused village's own (green, with its ID). Another village's
     * goods are not taken. Reads item data only, so the screen counts alike.
     */
    public static boolean payableForReplacement(ItemStack stack, @Nullable UUID villageId) {
        if (!VillageTokenBinding.isBound(stack)) return true;
        VillageTokenBinding binding = VillageTokenBinding.read(stack);
        return binding != null && binding.townHallId().equals(villageId);
    }

    public static int replacementEmeralds(Inventory inventory, @Nullable UUID villageId) {
        return countMatching(inventory, stack -> stack.is(CURRENCY) && payableForReplacement(stack, villageId));
    }

    public static int replacementLogs(Inventory inventory, @Nullable UUID villageId) {
        return countMatching(inventory, stack -> stack.is(ItemTags.LOGS)
                && payableForReplacement(stack, villageId));
    }

    public static int replacementCraftingTables(Inventory inventory, @Nullable UUID villageId) {
        return countMatching(inventory, stack -> stack.is(Items.CRAFTING_TABLE)
                && payableForReplacement(stack, villageId));
    }

    /** Items anywhere in the inventory that pass {@code test}, counted by stack size. */
    public static int countMatching(Inventory inventory, Predicate<ItemStack> test) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && test.test(stack)) total += stack.getCount();
        }
        return total;
    }

    /** Removes exactly {@code amount} matching items; the caller has already counted them. */
    private static void takeMatching(Inventory inventory, Predicate<ItemStack> test, int amount) {
        int remaining = amount;
        for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || !test.test(stack)) continue;
            int taken = Math.min(remaining, stack.getCount());
            stack.shrink(taken);
            if (stack.isEmpty()) inventory.setItem(slot, ItemStack.EMPTY);
            remaining -= taken;
        }
    }

    private static boolean isCaptainRow(CatalogueEntry entry) {
        return entry.item().get() == ModItems.CAPTAIN_TOKEN.get();
    }

    /** Captain tokens for {@code village} in the player's inventory. */
    private static int carriedCaptainTokens(Player player, VillageManager village) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(ModItems.CAPTAIN_TOKEN.get()) && VillageTokenBinding.matches(stack, village)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    @Nullable
    private static CatalogueEntry find(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null) return null;
        for (CatalogueEntry entry : CATALOGUE) {
            if (BuiltInRegistries.ITEM.getKey(entry.item().get()).equals(id)) return entry;
        }
        return null;
    }

    /** An emerald stamped with this village's ID: the only kind the Mayor's shop takes. */
    public static boolean isVillageEmerald(ItemStack stack, @Nullable UUID villageId) {
        if (villageId == null || !stack.is(CURRENCY)) return false;
        VillageTokenBinding binding = VillageTokenBinding.read(stack);
        return binding != null && binding.townHallId().equals(villageId);
    }

    /** This village's emeralds in the player's inventory. Reads item data only, so the screen counts alike. */
    public static int villageEmeralds(Inventory inventory, @Nullable UUID villageId) {
        return countMatching(inventory, stack -> isVillageEmerald(stack, villageId));
    }

    public static int villageEmeralds(Inventory inventory, @Nullable VillageManager village) {
        return village == null ? 0 : villageEmeralds(inventory, village.getVillageId());
    }

    /** Emeralds anywhere in the player's inventory, hotbar and offhand included. */
    public static int countCurrency(Inventory inventory) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(CURRENCY)) total += stack.getCount();
        }
        return total;
    }
}
