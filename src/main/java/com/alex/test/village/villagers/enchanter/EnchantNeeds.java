package com.alex.test.village.villagers.enchanter;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.enchanter.EnchantBooks.Kind;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import com.alex.test.village.villagers.tools.ProfessionToolRequirement;
import com.alex.test.village.villagers.tools.ToolUpgradeHelper;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * What the village would gain from a book -- the enchanter's wish list.
 *
 * <p>The enchanter used to make whichever allowed book storage had fewest of,
 * which made the same book all session (its own pocket did not count), half of
 * them for no one, and level I forever. It now looks at the tools the workers
 * actually work with, works out which books would raise them, takes away the
 * books already waiting in storage or in someone's pack, and makes the most
 * useful of what is left. When every tool is covered it stops.
 *
 * <p>Each job wants its own enchantments, most valuable first (see
 * {@link #workTools}). A missing enchantment counts double a higher level of
 * one already there, and diamond and netherite tools get a little extra, since
 * they keep a book longest. Wooden, stone and golden tools, leather and golden
 * armour and tools in their last quarter are skipped: the book would be lost
 * with them.
 */
public final class EnchantNeeds {

    /** An enchantment that helps a job, and how much. */
    public record Want(Kind kind, int weight) {}

    /**
     * One thing a worker works with: a pack slot or a worn armour slot.
     *
     * @param inventorySlot pack slot, or -1 for worn armour
     * @param equipment     the armour slot, or null for a pack item
     */
    public record WorkTool(int inventorySlot, @Nullable EquipmentSlot equipment, String label, List<Want> wants) {
        public ItemStack stack(NpcEntity npc) {
            return equipment != null ? npc.getItemBySlot(equipment)
                    : inventorySlot >= 0 ? npc.getInventory().getSlot(inventorySlot) : ItemStack.EMPTY;
        }
    }

    /**
     * A book the village would gain from.
     *
     * @param bookLevel the level the enchanter would write it at
     * @param toolLevel the tool's level of it now
     * @param worker    who it is for; null for a spare
     */
    public record Wish(Kind kind, int bookLevel, int toolLevel, int priority,
                       @Nullable UUID worker, String workerName, String toolLabel, @Nullable UUID order) {
        public Wish(Kind kind, int bookLevel, int toolLevel, int priority,
                    @Nullable UUID worker, String workerName, String toolLabel) {
            this(kind, bookLevel, toolLevel, priority, worker, workerName, toolLabel, null);
        }

        /** A book the player ordered from the Stock tab. */
        public static Wish ordered(Kind kind, int level, String by, UUID order) {
            return new Wish(kind, level, 0, Integer.MAX_VALUE, null, by, ORDER_LABEL, order);
        }

        public String describe() {
            String book = EnchantBooks.name(kind, bookLevel);
            if (order != null) return book + (workerName.isEmpty() ? " (ordered)" : " ordered by " + workerName);
            if (worker == null) return book + (forSale() ? " (to sell)" : " (spare)");
            return book + " for " + workerName + "'s " + toolLabel;
        }

        /** A top book written for the merchant, not for a tool. */
        public boolean forSale() {
            return worker == null && SALE_LABEL.equals(toolLabel);
        }
    }

    /** The tool label of a wish written to sell. */
    public static final String SALE_LABEL = "sale";
    /** The tool label of a book the player ordered. */
    public static final String ORDER_LABEL = "order";

    /**
     * What a book is worth to one tool: the job's weight for each enchantment
     * it would raise, doubled for one the tool lacks. 0 when the book does
     * nothing for it, or only adds what the job has no use for (Sharpness does
     * fit an axe, but a lumberjack does not want it).
     */
    public static int score(WorkTool tool, ItemStack stack, ItemStack book) {
        if (!EnchantBooks.bookImproves(stack, book)) return 0;
        int score = 0;
        for (Want want : tool.wants()) {
            int level = EnchantBooks.storedLevel(book, want.kind());
            if (level <= 0 || !EnchantBooks.raises(stack, want.kind(), level)) continue;
            score += want.weight() * (EnchantBooks.toolLevel(stack, want.kind()) == 0 ? 2 : 1);
        }
        return score;
    }

    /** Whether some worker's tool would gain from {@code book} now. */
    public static boolean wantedByAnyTool(ServerLevel level, VillageManager mgr, ItemStack book) {
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity worker = model.puppet();
            if (worker == null || !worker.isAlive()) continue;
            for (WorkTool tool : workTools(worker, new Activity.Context(model.brain(), model, mgr, level, worker))) {
                ItemStack stack = tool.stack(worker);
                if (worthABook(stack) && score(tool, stack, book) > 0) return true;
            }
        }
        return false;
    }

    /** What the wish list came to, with why nothing is wanted when the list is empty. */
    public record Plan(List<Wish> wishes, int toolsSeen, int blockedByCap, int blockedBySettings) {
        @Nullable
        public Wish top() { return wishes.isEmpty() ? null : wishes.get(0); }
    }

    /** Weight added for a diamond or netherite tool, which keeps a book longest. */
    private static final int TOP_TIER_BONUS = 5;
    /** Priority of a spare book: below every real tool. */
    private static final int SPARE_PRIORITY = 1;

    private EnchantNeeds() {}

    // =========================================================================
    // What each job works with
    // =========================================================================

    private static final List<Want> PICKAXE = List.of(
            new Want(EnchantBooks.EFFICIENCY, 30), new Want(EnchantBooks.FORTUNE, 25),
            new Want(EnchantBooks.UNBREAKING, 20), new Want(EnchantBooks.MENDING, 15));
    private static final List<Want> AXE = List.of(
            new Want(EnchantBooks.EFFICIENCY, 25), new Want(EnchantBooks.UNBREAKING, 18));
    private static final List<Want> HOE = List.of(
            new Want(EnchantBooks.EFFICIENCY, 18), new Want(EnchantBooks.UNBREAKING, 14));
    // No Mending: a guard's hand holds a copy of its sword, and Mending only
    // mends what is held, so the real sword in the pack would never be mended.
    private static final List<Want> SWORD = List.of(
            new Want(EnchantBooks.SHARPNESS, 28), new Want(EnchantBooks.UNBREAKING, 14));
    private static final List<Want> SHIELD = List.of(new Want(EnchantBooks.UNBREAKING, 10));
    private static final List<Want> BOW = List.of(
            new Want(EnchantBooks.POWER, 28), new Want(EnchantBooks.INFINITY, 20),
            new Want(EnchantBooks.UNBREAKING, 14));
    private static final List<Want> CHEST = List.of(
            new Want(EnchantBooks.PROTECTION, 20), new Want(EnchantBooks.UNBREAKING, 8));
    private static final List<Want> OTHER_ARMOR = List.of(
            new Want(EnchantBooks.PROTECTION, 14), new Want(EnchantBooks.UNBREAKING, 6));

    /** The tools {@code npc} works with now and the enchantments that help them. */
    public static List<WorkTool> workTools(NpcEntity npc) {
        return workTools(npc, null);
    }

    /**
     * The same, with the worker's own context: then each tool is the one its job
     * actually works with (the job's allowed tiers and choice rule), not just the
     * best in its pack.
     */
    public static List<WorkTool> workTools(NpcEntity npc, @Nullable Activity.Context ctx) {
        List<WorkTool> tools = new ArrayList<>();
        switch (npc.getProfession()) {
            case MINER -> addWorking(tools, npc, ctx, ItemTags.PICKAXES, "pickaxe", PICKAXE);
            case LUMBERJACK -> addWorking(tools, npc, ctx, ItemTags.AXES, "axe", AXE);
            case FARMER -> addWorking(tools, npc, ctx, ItemTags.HOES, "hoe", HOE);
            case GUARD, CAPTAIN -> {
                addWorking(tools, npc, ctx, ItemTags.SWORDS, "sword", SWORD);
                addBest(tools, npc.getInventory(), stack -> stack.is(Items.SHIELD), "shield", SHIELD);
                addArmor(tools, npc);
            }
            case ARCHER -> {
                addWorking(tools, npc, ctx, ItemTags.BOW_ENCHANTABLE, "bow", BOW);
                addArmor(tools, npc);
            }
            default -> { }
        }
        // A player's tools and armor are used as they are: no book ever goes on one.
        tools.removeIf(tool -> com.alex.test.village.tokens.PlayerGear.isPenalised(tool.stack(npc)));
        return tools;
    }

    /** The job's working tool of {@code tag} when the context is known, else the best in the pack. */
    private static void addWorking(List<WorkTool> tools, NpcEntity npc, @Nullable Activity.Context ctx,
                                   TagKey<Item> tag, String label, List<Want> wants) {
        if (ctx != null) {
            for (ProfessionToolRequirement requirement : ProfessionSupplyRules.toolRequirements(npc.getProfession())) {
                if (!requirement.toolTag().equals(tag)) continue;
                int slot = ToolUpgradeHelper.workingToolSlot(ctx, requirement);
                if (slot >= 0) tools.add(new WorkTool(slot, null, label, wants));
                return;
            }
        }
        addBest(tools, npc.getInventory(), tag, label, wants);
    }

    private static void addBest(List<WorkTool> tools, InventoryManager inv, TagKey<Item> tag,
                                String label, List<Want> wants) {
        addBest(tools, inv, stack -> stack.is(tag), label, wants);
    }

    private static void addBest(List<WorkTool> tools, InventoryManager inv, Predicate<ItemStack> kind,
                                String label, List<Want> wants) {
        int slot = ToolSelectionHelper.findBestToolSlot(inv,
                stack -> kind.test(stack) && ToolSelectionHelper.isUsableByDefault(stack),
                ToolSelectionHelper::quality);
        if (slot >= 0) tools.add(new WorkTool(slot, null, label, wants));
    }

    private static void addArmor(List<WorkTool> tools, NpcEntity npc) {
        addWorn(tools, npc, EquipmentSlot.CHEST, "chestplate", CHEST);
        addWorn(tools, npc, EquipmentSlot.HEAD, "helmet", OTHER_ARMOR);
        addWorn(tools, npc, EquipmentSlot.LEGS, "leggings", OTHER_ARMOR);
        addWorn(tools, npc, EquipmentSlot.FEET, "boots", OTHER_ARMOR);
    }

    private static void addWorn(List<WorkTool> tools, NpcEntity npc, EquipmentSlot slot,
                                String label, List<Want> wants) {
        ItemStack worn = npc.getItemBySlot(slot);
        if (!worn.isEmpty() && !NpcEntity.isDisplayOnly(worn)) tools.add(new WorkTool(-1, slot, label, wants));
    }

    /**
     * Whether a tool should get a book at all: iron or better (or a bow,
     * shield or armour above leather) and not about to be replaced. Shared by
     * the enchanter's plan and the worker deciding to have a book put on.
     */
    public static boolean worthABook(ItemStack tool) {
        return EnchantBooks.worthEnchanting(tool) && !EnchantBooks.nearlyWornOut(tool);
    }

    // =========================================================================
    // The plan
    // =========================================================================

    /**
     * The books worth making now, most useful first.
     *
     * @param bookCap  strongest book this enchanter can write in its library
     * @param skill    the enchanter's level: enchantments it has not learned are left out
     * @param allowed  the enchantments the settings let it make
     * @param spares   spare books of each useful enchantment to keep on top of real needs
     * @param orders   books the player ordered, first of all (may be empty)
     */
    public static Plan plan(ServerLevel level, VillageManager mgr, int bookCap, int skill,
                            Predicate<Kind> allowed, int spares, List<Wish> orders) {
        List<Wish> wishes = new ArrayList<>();
        int toolsSeen = 0;
        int blocked = 0;
        int blockedSettings = 0;
        List<Kind> usedKinds = new ArrayList<>();

        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity worker = model.puppet();
            if (worker == null || !worker.isAlive()) continue;
            for (WorkTool tool : workTools(worker, new Activity.Context(model.brain(), model, mgr, level, worker))) {
                ItemStack stack = tool.stack(worker);
                if (!worthABook(stack)) continue;
                toolsSeen++;
                for (Want want : tool.wants()) {
                    Kind kind = want.kind();
                    if (!usedKinds.contains(kind)) usedKinds.add(kind);
                    if (!stack.supportsEnchantment(EnchantBooks.holder(level, kind))) continue;
                    int have = EnchantBooks.toolLevel(stack, kind);
                    if (have >= kind.maxLevel()) continue;
                    if (have == 0 && !compatible(level, stack, kind)) continue;
                    // Wanted but not to be had: switched off, or not learned yet.
                    if (!allowed.test(kind)) {
                        blockedSettings++;
                        continue;
                    }
                    if (skill < kind.unlockLevel()) {
                        blocked++;
                        continue;
                    }
                    int bookLevel = Math.min(kind.maxLevel(), bookCap);
                    if (!EnchantBooks.raises(stack, kind, bookLevel)) {
                        blocked++;
                        continue;
                    }
                    int priority = want.weight() * (have == 0 ? 2 : 1)
                            + (ToolSelectionHelper.rank(stack.getItem()) >= 5 ? TOP_TIER_BONUS : 0);
                    wishes.add(new Wish(kind, bookLevel, have, priority, model.npcId,
                            nameOf(worker), tool.label()));
                }
            }
        }

        List<ItemStack> books = existingBooks(level, mgr);
        wishes.sort(Comparator.comparingInt(Wish::priority).reversed());
        List<Wish> open = new ArrayList<>(orders);
        for (Wish wish : wishes) {
            if (!coverFromBooks(books, wish)) open.add(wish);
        }

        if (spares > 0) {
            for (Kind kind : usedKinds) {
                if (!allowed.test(kind) || skill < kind.unlockLevel()) continue;
                int bookLevel = Math.min(kind.maxLevel(), bookCap);
                int have = 0;
                for (ItemStack book : books) {
                    if (EnchantBooks.storedLevel(book, kind) >= bookLevel) have++;
                }
                for (int i = have; i < spares; i++) {
                    open.add(new Wish(kind, bookLevel, 0, SPARE_PRIORITY, null, "", "spare"));
                }
            }
        }
        return new Plan(open, toolsSeen, blocked, blockedSettings);
    }

    private static boolean compatible(ServerLevel level, ItemStack tool, Kind kind) {
        return net.minecraft.world.item.enchantment.EnchantmentHelper.isEnchantmentCompatible(
                tool.getEnchantments().keySet(), EnchantBooks.holder(level, kind));
    }

    /** Takes an unclaimed book out of {@code books} that would answer {@code wish}; false if none. */
    private static boolean coverFromBooks(List<ItemStack> books, Wish wish) {
        for (int i = 0; i < books.size(); i++) {
            int stored = EnchantBooks.storedLevel(books.get(i), wish.kind());
            if (stored <= 0) continue;
            boolean raises = stored > wish.toolLevel()
                    || (stored == wish.toolLevel() && stored < wish.kind().maxLevel());
            if (!raises) continue;
            books.remove(i);
            return true;
        }
        return false;
    }

    /** Every enchanted book in storage and in villagers' packs, one entry per book. */
    public static List<ItemStack> existingBooks(ServerLevel level, VillageManager mgr) {
        List<ItemStack> books = new ArrayList<>();
        for (Building building : mgr.getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            for (var chest : storage.inventories(level)) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (!stack.is(Items.ENCHANTED_BOOK) || !storage.mayUse(level, stack)
                            || BookOrders.isOrdered(stack)) continue;
                    for (int n = 0; n < stack.getCount(); n++) books.add(stack);
                }
            }
        }
        for (VillagerModel model : mgr.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            InventoryManager inv = npc.getInventory();
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack stack = inv.getSlot(i);
                if (stack.is(Items.ENCHANTED_BOOK) && !BookOrders.isOrdered(stack)) books.add(stack);
            }
        }
        return books;
    }

    /** The worker's own name, or its job when it has none. */
    public static String nameOf(NpcEntity npc) {
        String name = npc.givenName();
        return name != null ? name : npc.getProfession().displayName();
    }

    /** Professions whose tools take books. */
    public static boolean usesBooks(Profession profession) {
        return switch (profession) {
            case MINER, LUMBERJACK, FARMER, GUARD, CAPTAIN, ARCHER -> true;
            default -> false;
        };
    }
}
