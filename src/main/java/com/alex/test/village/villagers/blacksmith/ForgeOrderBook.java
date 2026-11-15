package com.alex.test.village.villagers.blacksmith;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.building.Building;
import com.alex.test.village.building.StorageBuilding;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.tokens.VillageTokenBinding;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * What the village owns and who needs what -- the facts the forge plans from.
 *
 * <p>The blacksmith used to work a fixed list and make the first line it could
 * afford, so what came off the anvil depended on what happened to be in the
 * chests that minute. {@code BlacksmithActivity} now builds a list of needs
 * from these numbers instead (no tool, wearing out, defenders' gear, upgrades,
 * one spare); this class only counts.
 *
 * <p>Everything is counted village-wide, chests and pockets and worn armour
 * together: a tool in a lumberjack's hand is a tool the village has.
 *
 * <p><b>Worn</b> tools (a quarter of their durability or less) still count as
 * owned at their tier -- the worker keeps using them until they break -- but
 * not as <i>fresh</i>. The smith uses the fresh count to have the replacement
 * waiting in storage before the old one snaps.
 */
public final class ForgeOrderBook {

    /** The four things a villager might work with. */
    public enum ToolKind {
        AXE(ItemTags.AXES),
        PICKAXE(ItemTags.PICKAXES),
        HOE(ItemTags.HOES),
        SWORD(ItemTags.SWORDS);

        private final TagKey<Item> tag;

        ToolKind(TagKey<Item> tag) {
            this.tag = tag;
        }

        public TagKey<Item> tag() {
            return tag;
        }
    }

    private static final ToolKind[] KINDS = ToolKind.values();

    /** Armour slots in the order the smith fills them. */
    public static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    /** A tool at or below this share of its durability is wearing out. */
    public static final float WORN_FRACTION = 0.25f;

    private ForgeOrderBook() {}

    /**
     * What this profession works with, or {@code null} if it needs no forged
     * tool at all.
     *
     * <p>Deliberately explicit rather than derived from
     * {@code ProfessionSupplyRules.toolRequirements}: that map describes which
     * professions fetch a tool from storage on their own, which is a different
     * question from which professions a smith should be making tools for.
     */
    @Nullable
    public static ToolKind kindFor(Profession profession) {
        return switch (profession) {
            case LUMBERJACK -> ToolKind.AXE;
            case MINER -> ToolKind.PICKAXE;
            case FARMER -> ToolKind.HOE;
            case GUARD, CAPTAIN, BUTCHER -> ToolKind.SWORD;
            default -> null;
        };
    }

    /**
     * Professions that shear: the rancher's flock, and the farmer's berries, cocoa
     * and glow berries ({@link #stock} counts a farmer only while it grows them).
     */
    public static boolean usesShears(Profession profession) {
        return profession == Profession.FARMER || profession == Profession.RANCHER;
    }

    /**
     * Villagers who put armour on: guards and captains (the guard loop's armour
     * trips). Archers and mages never wear any, and every other job takes it off
     * ({@code NpcEntity.stowArmorUnlessGuard}); counting them forged sets nobody wore.
     */
    public static boolean wearsArmor(Profession profession) {
        return profession.isGuardLike();
    }

    /** True when a damageable stack is down to {@link #WORN_FRACTION} or less. */
    public static boolean isWorn(ItemStack stack) {
        if (!stack.isDamageableItem()) return false;
        int max = stack.getMaxDamage();
        if (max <= 0) return false;
        return (max - stack.getDamageValue()) <= max * WORN_FRACTION;
    }

    // =========================================================================
    // Stock
    // =========================================================================

    /**
     * The roster and everything the village owns, counted in one pass.
     *
     * <p>The planner asks dozens of questions of the same chests; each used to
     * walk every chest slot and every pocket on its own. Take one of these per
     * planning pass instead.
     */
    public static final class Stock {
        private final int[] users = new int[KINDS.length];
        /** Owned tools per kind, by {@link #tierOf} rank, worn or not. */
        private final int[][] byTier = new int[KINDS.length][TIER_NETHERITE + 1];
        /** Owned tools per kind that are not worn, any tier. */
        private final int[] fresh = new int[KINDS.length];
        /** Owned armour per {@link #ARMOR_SLOTS} index, by tier (leather = wood rank). */
        private final int[][] armor = new int[ARMOR_SLOTS.length][TIER_NETHERITE + 1];
        private final Map<Item, Integer> exact = new HashMap<>();
        private int defenders;
        private int shieldUsers;
        private int freshShields;
        private int shearsUsers;
        private int freshShears;
        private final java.util.List<Recyclable> recyclables = new java.util.ArrayList<>();

        private Stock() {}

        /** Old tools in storage that have waited a day unclaimed, oldest scan order first. */
        public java.util.List<Recyclable> recyclables() {
            return recyclables;
        }

        /** How many villagers work with this kind of tool right now. */
        public int users(ToolKind kind) {
            return users[kind.ordinal()];
        }

        /** How many villagers want a set of armour: guards and captains ({@link #wearsArmor}). */
        public int defenders() {
            return defenders;
        }

        /** How many guards and captains carry a shield into a fight. */
        public int shieldUsers() {
            return shieldUsers;
        }

        /** Shields that are not about to break. */
        public int freshShields() {
            return freshShields;
        }

        /** Ranchers, and farmers growing something shears pick. */
        public int shearsUsers() {
            return shearsUsers;
        }

        /** Shears that are not about to break. */
        public int freshShears() {
            return freshShears;
        }

        /** Everything of a kind at or above a given tier — see {@link #tierOf}. */
        public int ownedAtLeast(ToolKind kind, int minTier) {
            int total = 0;
            for (int tier = Math.max(0, minTier); tier <= TIER_NETHERITE; tier++) {
                total += byTier[kind.ordinal()][tier];
            }
            return total;
        }

        /** Tools of a kind, any tier, that still have more than a quarter left. */
        public int fresh(ToolKind kind) {
            return fresh[kind.ordinal()];
        }

        /** Armour pieces for a slot at or above a tier (leather and chain rank as wood). */
        public int armorAtLeast(int slotIndex, int minTier) {
            int total = 0;
            for (int tier = Math.max(0, minTier); tier <= TIER_NETHERITE; tier++) {
                total += armor[slotIndex][tier];
            }
            return total;
        }

        /** Tools and armour of exactly this item. */
        public int ownedExactly(Item item) {
            return exact.getOrDefault(item, 0);
        }

        private void add(ItemStack stack) {
            int tier = tierOf(stack.getItem());
            boolean worn = isWorn(stack);
            for (ToolKind kind : KINDS) {
                if (stack.is(kind.tag())) {
                    byTier[kind.ordinal()][tier] += stack.getCount();
                    if (!worn) fresh[kind.ordinal()] += stack.getCount();
                }
            }
            if (stack.getItem() instanceof ArmorItem armorItem) {
                int slot = slotIndex(armorItem.getEquipmentSlot());
                if (slot >= 0) armor[slot][tier] += stack.getCount();
            }
            if (!worn && stack.is(Items.SHIELD)) freshShields += stack.getCount();
            if (!worn && stack.is(Items.SHEARS)) freshShears += stack.getCount();
            exact.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
    }

    /** A tool waiting to be melted down, and the storage room it sits in. */
    public record Recyclable(ItemStack stack, BlockPos storageDoor) {}

    /** At most this many old tools are queued per planning pass. */
    private static final int MAX_RECYCLABLES = 4;

    /** Index into {@link #ARMOR_SLOTS}, or -1. */
    public static int slotIndex(EquipmentSlot slot) {
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            if (ARMOR_SLOTS[i] == slot) return i;
        }
        return -1;
    }

    /**
     * Everything the village owns, at any tier: chests plus pockets, including
     * the villager inventory and worn armour.
     *
     * <p>Counting only storage would have the smith forge a second axe the
     * moment the lumberjack picked up the first.
     */
    public static Stock stock(Activity.Context ctx) {
        Stock stock = new Stock();
        long now = ctx.level().getGameTime();
        // What the player keeps in the Stock tab is theirs, not the village's: it is
        // neither counted as owned nor melted down.
        Map<Item, Integer> keptLeft = new HashMap<>();
        var reserves = com.alex.test.village.economy.StockReserves.get(ctx.level().getServer());
        for (Item item : com.alex.test.village.economy.StockReserves.forgeItems()) {
            int kept = reserves.lockedAmount(ctx.mgr().getVillageId(), item);
            if (kept > 0) keptLeft.put(item, kept);
        }
        for (Building building : ctx.mgr().getBuildings()) {
            if (!(building instanceof StorageBuilding storage) || !storage.isValid()) continue;
            // Nothing in a room with no owner is usable; see VillageItemOwnership.
            VillageTokenBinding owner = storage.owner(ctx.level());
            if (owner == null) continue;
            for (var chest : storage.inventories(ctx.level())) {
                for (int i = 0; i < chest.size(); i++) {
                    ItemStack stack = chest.peek(i);
                    if (isTracked(stack) && VillageItemOwnership.mayUse(stack, owner)) {
                        int keep = keptLeft.getOrDefault(stack.getItem(), 0);
                        if (keep > 0) {
                            keptLeft.put(stack.getItem(), keep - stack.getCount());
                            continue;
                        }
                        stock.add(stack);
                        // A player's tool or armor counts as stock, but is never melted down.
                        if (stock.recyclables.size() < MAX_RECYCLABLES
                                && VillageItemOwnership.mayUseAsMaterial(stack, owner)
                                && ForgeRecycling.isRecyclable(stack, now)) {
                            stock.recyclables.add(new Recyclable(stack.copyWithCount(1), storage.getDoorPos()));
                        }
                    }
                }
            }
        }
        for (VillagerModel model : ctx.mgr().getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive()) continue;
            ToolKind kind = kindFor(npc.getProfession());
            // A farmer set to only berries, cocoa or cane never uses a hoe.
            if (kind == ToolKind.HOE && !com.alex.test.village.villagers.farmer.FarmerManager.needsHoe(
                    com.alex.test.village.villagers.farmer.settings.FarmerSettings.of(npc))) kind = null;
            if (kind != null) stock.users[kind.ordinal()]++;
            // A farmer growing melons or pumpkins harvests them with an axe as well.
            if (npc.getProfession() == Profession.FARMER && kind != ToolKind.AXE
                    && ctx.mgr().farmers().wantsHarvestTool(npc,
                            com.alex.test.village.villagers.farmer.crops.HarvestTool.AXE)) {
                stock.users[ToolKind.AXE.ordinal()]++;
            }
            if (wearsArmor(npc.getProfession())) stock.defenders++;
            if (npc.getProfession().isGuardLike()) stock.shieldUsers++;
            // A farmer only counts while it grows berries, cocoa or glow berries, as it fetches them:
            // counting every farmer kept a pair in storage for each one that never shears.
            if (usesShears(npc.getProfession()) && (npc.getProfession() != Profession.FARMER
                    || ctx.mgr().farmers().wantsHarvestTool(npc,
                            com.alex.test.village.villagers.farmer.crops.HarvestTool.SHEARS))) {
                stock.shearsUsers++;
            }
            InventoryManager inv = npc.getInventory();
            if (inv == null) continue;
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack stack = inv.getSlot(i);
                if (isTracked(stack) && inv.mayUse(stack)) stock.add(stack);
            }
            for (ItemStack worn : npc.getArmorSlots()) {
                if (isTracked(worn) && inv.mayUse(worn)) stock.add(worn);
            }
        }
        return stock;
    }

    /**
     * Whether the book counts this stack at all. A tag lookup, so it runs
     * before the ownership check, which reads the stack's custom data.
     */
    private static boolean isTracked(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof ArmorItem || stack.is(Items.SHIELD)
                || stack.is(Items.SHEARS)) return true;
        for (ToolKind kind : KINDS) {
            if (stack.is(kind.tag())) return true;
        }
        return false;
    }

    // =========================================================================
    // Tiers
    // =========================================================================

    /** Material rank, used to decide whether a tool still wants upgrading. */
    public static final int TIER_WOOD = 0;
    public static final int TIER_STONE = 1;
    public static final int TIER_IRON = 2;
    public static final int TIER_DIAMOND = 3;
    public static final int TIER_NETHERITE = 4;

    private static final Set<Item> STONE = Set.of(
            Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_HOE, Items.STONE_SWORD,
            Items.STONE_SHOVEL);
    private static final Set<Item> IRON = Set.of(
            Items.IRON_AXE, Items.IRON_PICKAXE, Items.IRON_HOE, Items.IRON_SWORD,
            Items.IRON_SHOVEL, Items.IRON_HELMET, Items.IRON_CHESTPLATE,
            Items.IRON_LEGGINGS, Items.IRON_BOOTS);
    private static final Set<Item> DIAMOND = Set.of(
            Items.DIAMOND_AXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_HOE, Items.DIAMOND_SWORD,
            Items.DIAMOND_SHOVEL, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS);
    private static final Set<Item> NETHERITE = Set.of(
            Items.NETHERITE_AXE, Items.NETHERITE_PICKAXE, Items.NETHERITE_HOE,
            Items.NETHERITE_SWORD, Items.NETHERITE_SHOVEL, Items.NETHERITE_HELMET,
            Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS);

    /** Where an item sits on the material ladder. */
    public static int tierOf(Item item) {
        if (NETHERITE.contains(item)) return TIER_NETHERITE;
        if (DIAMOND.contains(item)) return TIER_DIAMOND;
        if (IRON.contains(item)) return TIER_IRON;
        if (STONE.contains(item)) return TIER_STONE;
        return TIER_WOOD;
    }
}
