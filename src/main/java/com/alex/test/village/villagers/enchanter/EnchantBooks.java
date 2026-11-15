package com.alex.test.village.villagers.enchanter;

import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Enchanted books: which ones the enchanter knows, how to write one, and how a
 * book goes onto a tool.
 *
 * <p>A book goes on with vanilla anvil rules, the same for every tool: an
 * enchantment the tool cannot take, or that clashes with one it has, is
 * skipped; a book of the same level as the tool's raises it one (up to the
 * maximum); a stronger book replaces it. So a level N book takes a tool from N
 * to N+1, and an enchanter able to write level II books can bring tools to
 * level III. No experience cost: villagers have none to spend.
 */
public final class EnchantBooks {

    /**
     * One enchantment the enchanter can write.
     *
     * @param id          settings id, the enchantment's registry name
     * @param unlockLevel enchanter skill level it is learned at
     */
    public record Kind(String id, ResourceKey<Enchantment> key, String label, int unlockLevel, int maxLevel) {}

    public static final Kind UNBREAKING = new Kind("minecraft:unbreaking", Enchantments.UNBREAKING, "Unbreaking", 1, 3);
    public static final Kind EFFICIENCY = new Kind("minecraft:efficiency", Enchantments.EFFICIENCY, "Efficiency", 1, 5);
    public static final Kind SHARPNESS = new Kind("minecraft:sharpness", Enchantments.SHARPNESS, "Sharpness", 1, 5);
    public static final Kind PROTECTION = new Kind("minecraft:protection", Enchantments.PROTECTION, "Protection", 1, 4);
    public static final Kind POWER = new Kind("minecraft:power", Enchantments.POWER, "Power", 1, 5);
    public static final Kind FORTUNE = new Kind("minecraft:fortune", Enchantments.FORTUNE, "Fortune",
            EnchanterBalance.SCHOLAR_LEVEL, 3);
    public static final Kind INFINITY = new Kind("minecraft:infinity", Enchantments.INFINITY, "Infinity",
            EnchanterBalance.SCHOLAR_LEVEL, 1);
    public static final Kind MENDING = new Kind("minecraft:mending", Enchantments.MENDING, "Mending",
            EnchanterBalance.ARCANIST_LEVEL, 1);

    /** Every enchantment the enchanter knows, in the order the settings list them. */
    public static final List<Kind> KINDS = List.of(
            UNBREAKING, EFFICIENCY, FORTUNE, MENDING, SHARPNESS, PROTECTION, POWER, INFINITY);

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V"};

    private EnchantBooks() {}

    @Nullable
    public static Kind byId(String id) {
        for (Kind kind : KINDS) {
            if (kind.id().equals(id)) return kind;
        }
        return null;
    }

    public static String roman(int level) {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : Integer.toString(level);
    }

    /** "Efficiency III". */
    public static String name(Kind kind, int level) {
        return kind.maxLevel() == 1 ? kind.label() : kind.label() + " " + roman(level);
    }

    public static Holder<Enchantment> holder(ServerLevel level, Kind kind) {
        return level.registryAccess().registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(kind.key());
    }

    /** A book holding {@code kind} at {@code bookLevel}, readable by anvils and trades. */
    public static ItemStack makeBook(ServerLevel level, Kind kind, int bookLevel) {
        return EnchantedBookItem.createForEnchantment(new EnchantmentInstance(holder(level, kind),
                Math.clamp(bookLevel, 1, kind.maxLevel())));
    }

    public static ItemEnchantments stored(ItemStack book) {
        return book.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
    }

    /** The level of {@code kind} a book stores, 0 for none. */
    public static int storedLevel(ItemStack book, Kind kind) {
        if (!book.is(Items.ENCHANTED_BOOK)) return 0;
        for (var entry : stored(book).entrySet()) {
            if (entry.getKey().is(kind.key())) return entry.getIntValue();
        }
        return 0;
    }

    /** The level of {@code kind} on a tool, 0 for none. */
    public static int toolLevel(ItemStack tool, Kind kind) {
        return ToolSelectionHelper.enchantmentLevel(tool, kind.key());
    }

    /** A readable name for a book, such as "Efficiency III" or "Unbreaking II + Mending". */
    public static String describe(ItemStack book) {
        StringBuilder out = new StringBuilder();
        for (var entry : stored(book).entrySet()) {
            if (out.length() > 0) out.append(" + ");
            Kind kind = null;
            for (Kind k : KINDS) {
                if (entry.getKey().is(k.key())) kind = k;
            }
            out.append(kind != null ? name(kind, entry.getIntValue())
                    : entry.getKey().unwrapKey().map(k -> k.location().getPath()).orElse("?")
                    + " " + roman(entry.getIntValue()));
        }
        return out.length() > 0 ? out.toString() : "blank book";
    }

    /** Whether every enchantment the book stores is at its highest level, such as Unbreaking III. */
    public static boolean isMaxLevelBook(ItemStack book) {
        ItemEnchantments stored = stored(book);
        if (!book.is(Items.ENCHANTED_BOOK) || stored.isEmpty()) return false;
        for (var entry : stored.entrySet()) {
            if (entry.getIntValue() < entry.getKey().value().getMaxLevel()) return false;
        }
        return true;
    }

    /**
     * Emeralds the merchant pays for a top-level book of {@code kind}, from
     * the price table's {@code books} section (see {@code economy/prices.json}).
     */
    public static int price(Kind kind) {
        return com.alex.test.village.economy.MarketPrices.bookPrice(kind.maxLevel() >= 5);
    }

    /**
     * The merchant's normal price for {@code book}: 0 unless every
     * enchantment on it is at its top level; 6 when one of them is level V
     * (Efficiency V, Sharpness V, Power V), 4 for any other top-level book
     * (economy/prices.json, "books"). Today's price is lower after book sales.
     */
    public static int price(ItemStack book) {
        if (!isMaxLevelBook(book)) return 0;
        boolean levelFive = false;
        for (var entry : stored(book).entrySet()) {
            if (entry.getIntValue() >= 5) levelFive = true;
        }
        return com.alex.test.village.economy.MarketPrices.bookPrice(levelFive);
    }

    /**
     * The memory key a sold book's price moves under: one for every enchanted
     * book, so each book sold lowers the price of the next, whatever it holds.
     */
    public static String marketKey(ItemStack book) {
        return "minecraft:enchanted_book";
    }

    /** Whether a book stores {@code kind} at a level that would raise this tool. */
    public static boolean raises(ItemStack tool, Kind kind, int bookLevel) {
        int have = toolLevel(tool, kind);
        return bookLevel > have || (bookLevel == have && have < kind.maxLevel());
    }

    /** Whether applying {@code book} would raise at least one enchantment on {@code tool}. */
    public static boolean bookImproves(ItemStack tool, ItemStack book) {
        return !combined(tool, book).isEmpty();
    }

    /**
     * Applies an enchanted book to {@code tool} with vanilla anvil rules.
     *
     * @return true if the tool changed
     */
    public static boolean applyBook(ItemStack tool, ItemStack book) {
        ItemEnchantments result = combined(tool, book);
        if (result.isEmpty()) return false;
        EnchantmentHelper.updateEnchantments(tool, mutable -> {
            for (var entry : result.entrySet()) {
                mutable.set(entry.getKey(), entry.getIntValue());
            }
        });
        return true;
    }

    /** The enchantments the book would raise on the tool, at their new levels; empty if none. */
    private static ItemEnchantments combined(ItemStack tool, ItemStack book) {
        ItemEnchantments stored = stored(book);
        if (tool.isEmpty() || stored.isEmpty() || !book.is(Items.ENCHANTED_BOOK)) return ItemEnchantments.EMPTY;
        ItemEnchantments current = tool.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        ItemEnchantments.Mutable raised = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (var entry : stored.entrySet()) {
            Holder<Enchantment> enchantment = entry.getKey();
            int bookLevel = entry.getIntValue();
            if (!tool.supportsEnchantment(enchantment)) continue;
            int have = current.getLevel(enchantment);
            if (have == 0 && !EnchantmentHelper.isEnchantmentCompatible(current.keySet(), enchantment)) continue;
            int next = have == bookLevel ? bookLevel + 1 : Math.max(have, bookLevel);
            next = Math.min(next, enchantment.value().getMaxLevel());
            if (next > have) raised.set(enchantment, next);
        }
        return raised.toImmutable();
    }

    /**
     * Whether a tool is worth a book at all. Wooden, stone and golden tools and
     * leather or golden armour wear out in days, so a book on them is soon lost with
     * them; iron and better, bows, shields and shears keep it for weeks.
     */
    public static boolean worthEnchanting(ItemStack tool) {
        if (tool.isEmpty() || !tool.isDamageableItem()) return false;
        int rank = ToolSelectionHelper.rank(tool.getItem());
        if (rank >= 1 && rank <= 3) return false;
        if (tool.is(Items.LEATHER_HELMET) || tool.is(Items.LEATHER_CHESTPLATE)
                || tool.is(Items.LEATHER_LEGGINGS) || tool.is(Items.LEATHER_BOOTS)
                || tool.is(Items.GOLDEN_HELMET) || tool.is(Items.GOLDEN_CHESTPLATE)
                || tool.is(Items.GOLDEN_LEGGINGS) || tool.is(Items.GOLDEN_BOOTS)) return false;
        return rank >= 4 || tool.getItem() instanceof ArmorItem || tool.is(Items.BOW)
                || tool.is(Items.CROSSBOW) || tool.is(Items.SHIELD) || tool.is(Items.SHEARS)
                || tool.is(ItemTags.PICKAXES) || tool.is(ItemTags.AXES) || tool.is(ItemTags.HOES)
                || tool.is(ItemTags.SWORDS);
    }

    /** Whether a tool is down to its last quarter and will be replaced soon. */
    public static boolean nearlyWornOut(ItemStack tool) {
        if (!tool.isDamageableItem() || tool.getMaxDamage() <= 0) return false;
        return (tool.getMaxDamage() - tool.getDamageValue()) <= tool.getMaxDamage() * 0.25f;
    }
}
