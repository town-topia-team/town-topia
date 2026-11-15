package com.alex.test.village.villagers.tools;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.supplies.ProfessionSupplyRules;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import com.alex.test.village.tokens.PlayerGear;

import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/** Shared item-tier and inventory helpers for profession tools. */
public final class ToolSelectionHelper {

    private ToolSelectionHelper() {}

    /** Higher value means a preferred tool or weapon. */
    public static int rank(Item item) {
        if (item == Items.WOODEN_AXE || item == Items.WOODEN_SWORD
                || item == Items.WOODEN_HOE || item == Items.WOODEN_PICKAXE
                || item == Items.WOODEN_SHOVEL) return 1;
        if (item == Items.GOLDEN_AXE || item == Items.GOLDEN_SWORD
                || item == Items.GOLDEN_HOE || item == Items.GOLDEN_PICKAXE
                || item == Items.GOLDEN_SHOVEL) return 2;
        if (item == Items.STONE_AXE || item == Items.STONE_SWORD
                || item == Items.STONE_HOE || item == Items.STONE_PICKAXE
                || item == Items.STONE_SHOVEL) return 3;
        if (item == Items.IRON_AXE || item == Items.IRON_SWORD
                || item == Items.IRON_HOE || item == Items.IRON_PICKAXE
                || item == Items.IRON_SHOVEL) return 4;
        if (item == Items.DIAMOND_AXE || item == Items.DIAMOND_SWORD
                || item == Items.DIAMOND_HOE || item == Items.DIAMOND_PICKAXE
                || item == Items.DIAMOND_SHOVEL) return 5;
        if (item == Items.NETHERITE_AXE || item == Items.NETHERITE_SWORD
                || item == Items.NETHERITE_HOE || item == Items.NETHERITE_PICKAXE
                || item == Items.NETHERITE_SHOVEL) return 6;
        return 0;
    }

    /**
     * Enchantment levels added up, curses counting against, clamped to ±99. A
     * player's gear counts only the share that works (PlayerGear), and a point
     * less on top, so the village's own wins between equals and a villager
     * swaps a player's iron axe for the village's iron axe.
     */
    public static int enchantPoints(ItemStack stack) {
        int enchants = 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            enchants += entry.getKey().is(EnchantmentTags.CURSE) ? -2 * entry.getIntValue() : entry.getIntValue();
        }
        if (PlayerGear.isPenalised(stack)) {
            enchants = (int) Math.round(enchants * PlayerGear.penalty(stack).enchantShare) - 1;
        }
        return Math.max(-99, Math.min(99, enchants));
    }

    /**
     * The default rule for how good a tool is, higher is better: tier, then
     * enchantments. Jobs that time their work by the tool replace it with
     * speed ({@link ToolSpeed#quality}), guards with damage. Durability is left
     * out on purpose -- a worn tool is never traded for a fresher copy of
     * itself, it is used until it breaks; it only settles ties between equals.
     * See {@link ProfessionToolRequirement#quality}.
     */
    public static long quality(ItemStack stack) {
        return rank(stack.getItem()) * 1_000L + enchantPoints(stack);
    }

    /**
     * A job's own measure of a tool (work speed, damage) as a quality number:
     * the measure first, to four decimals, then enchantment points.
     */
    public static long byMeasure(double measure, ItemStack stack) {
        return Math.round(measure * 10_000) * 1_000L + enchantPoints(stack);
    }

    /**
     * The level of enchantment {@code key} on {@code stack}, 0 for none. Needs no
     * registry, so offline estimates and pack checks can use it too.
     */
    public static int enchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) return 0;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(key)) return entry.getIntValue();
        }
        return 0;
    }

    /**
     * The level of {@code key} that really works: {@link #enchantmentLevel},
     * cut to a player's share (Efficiency III on a player's iron pick is 1.5).
     */
    public static double effectiveEnchantmentLevel(ItemStack stack, ResourceKey<Enchantment> key) {
        return PlayerGear.scaledLevel(stack, key, enchantmentLevel(stack, key));
    }

    /** Uses left before the tool breaks; unbreakable items count as unlimited. */
    public static int durabilityLeft(ItemStack stack) {
        return stack.isDamageableItem()
                ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
    }

    public static boolean isProfessionTool(ItemStack stack) {
        return !stack.isEmpty()
                && (stack.is(ItemTags.AXES)
                || stack.is(ItemTags.SWORDS)
                || stack.is(ItemTags.HOES)
                || stack.is(ItemTags.PICKAXES)
                || stack.is(ItemTags.SHOVELS)
                || stack.is(Items.BOW)
                || stack.is(Items.CROSSBOW)
                || stack.is(Items.SHEARS));
    }

    public static boolean isToolForProfession(ItemStack stack, Profession profession) {
        if (!isProfessionTool(stack)) return false;
        if (profession == Profession.RANCHER) return stack.is(Items.SHEARS);
        if (profession == Profession.BUTCHER) return stack.is(ItemTags.SWORDS);
        return ProfessionSupplyRules.toolRequirements(profession).stream()
                .anyMatch(requirement -> stack.is(requirement.toolTag()));
    }

    /**
     * One use of vanilla wear on the tool in {@code slot}: one point, reduced by
     * Unbreaking the vanilla way; a player's tool more (PlayerGear.wear).
     * Clears the slot if the tool breaks.
     *
     * @return true if the tool survived, false if it broke or was not there
     */
    public static boolean wearTool(ServerLevel level, InventoryManager inventory, int slot) {
        if (slot < 0) return false;
        ItemStack stack = inventory.getSlot(slot);
        if (stack.isEmpty()) return false;
        if (!stack.isDamageableItem()) return true;
        int damage = PlayerGear.wear(level, stack, 1);
        if (damage <= 0) return true;
        int newDamage = stack.getDamageValue() + damage;
        if (newDamage >= stack.getMaxDamage()) {
            inventory.setSlot(slot, ItemStack.EMPTY);
            return false;
        }
        stack.setDamageValue(newDamage);
        return true;
    }

    public static boolean isUsableByDefault(ItemStack stack) {
        return !stack.isEmpty()
                && (!stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage());
    }

    public static int findUsableToolSlot(
            InventoryManager inventory,
            TagKey<Item> tag,
            Set<Item> allowed,
            Predicate<ItemStack> usable) {
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (inventory.mayUse(stack) && isAllowedUsableTool(stack, tag, allowed, usable)) return i;
        }
        return -1;
    }

    /** {@link #findBestToolSlot} by the default {@link #quality}: tier, then enchantments. */
    public static int findBestUsableToolSlot(
            InventoryManager inventory,
            TagKey<Item> tag,
            Set<Item> allowed,
            Predicate<ItemStack> usable) {
        return findBestToolSlot(inventory, tag, allowed, usable, ToolSelectionHelper::quality);
    }

    /**
     * The one rule every tool choice goes through: the allowed, usable tool in
     * the pack that is best by the job's {@code quality}, and on a tie the one
     * with the most uses left. -1 when there is none. The tool in the hand, the
     * one worn down, the one kept when banking and the one compared against
     * storage all come from here, so they are always the same tool.
     */
    public static int findBestToolSlot(
            InventoryManager inventory,
            TagKey<Item> tag,
            Set<Item> allowed,
            Predicate<ItemStack> usable,
            ToLongFunction<ItemStack> quality) {
        return findBestToolSlot(inventory,
                stack -> isAllowedUsableTool(stack, tag, allowed, usable), quality);
    }

    /** {@link #findBestToolSlot} over every pack item that {@code candidate} accepts. */
    public static int findBestToolSlot(
            InventoryManager inventory,
            Predicate<ItemStack> candidate,
            ToLongFunction<ItemStack> quality) {
        int bestSlot = -1;
        long bestQuality = Long.MIN_VALUE;
        int bestDurability = -1;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            ItemStack stack = inventory.getSlot(i);
            if (stack.isEmpty() || !inventory.mayUse(stack) || !candidate.test(stack)) continue;
            long q = quality.applyAsLong(stack);
            int durability = durabilityLeft(stack);
            if (q > bestQuality || (q == bestQuality && durability > bestDurability)) {
                bestQuality = q;
                bestDurability = durability;
                bestSlot = i;
            }
        }
        return bestSlot;
    }

    public static boolean isAllowedUsableTool(
            ItemStack stack,
            TagKey<Item> tag,
            Set<Item> allowed,
            Predicate<ItemStack> usable) {
        return !stack.isEmpty()
                && stack.is(tag)
                && allowed.contains(stack.getItem())
                && usable.test(stack);
    }
}
