package com.alex.test.village.villagers.miner;

import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.tokens.PlayerGear;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Set;

/**
 * Pickaxe choice, wear and Mending for the miner. Books go on at the
 * blacksmith's anvil (see {@code EnchantBooks} and {@code BookFitting}).
 *
 * <p>Every enchantment effect goes through vanilla's own hooks -- Unbreaking
 * through {@link EnchantmentHelper#processDurabilityChange}, Mending through
 * {@link EnchantmentHelper#modifyDurabilityToRepairFromXp}, Fortune through the
 * block loot tables -- so the pickaxe behaves in a miner's hands exactly as it
 * would in a player's.
 */
public final class PickaxeUtils {

    private PickaxeUtils() {}

    /** Vanilla durability: a pickaxe is usable until its last point is spent. */
    public static boolean isUsablePickaxe(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(ItemTags.PICKAXES)) return false;
        return !stack.isDamageableItem() || stack.getDamageValue() < stack.getMaxDamage();
    }

    public static double enchantmentLevel(ServerLevel level, ItemStack stack,
                                       ResourceKey<Enchantment> key) {
        if (stack.isEmpty()) return 0;
        Holder<Enchantment> holder = level.registryAccess()
                .registryOrThrow(Registries.ENCHANTMENT).getHolderOrThrow(key);
        return PlayerGear.scaledLevel(stack, holder, EnchantmentHelper.getItemEnchantmentLevel(holder, stack));
    }

    public static double efficiencyLevel(ServerLevel level, ItemStack stack) {
        return enchantmentLevel(level, stack, Enchantments.EFFICIENCY);
    }

    /**
     * The miner's tool rule: fewest ticks per block (swing count and swing
     * speed together), then the faster vanilla speed, then enchantments.
     * Needs no registry, so the pack pick, storage and offline estimates all
     * share it.
     */
    public static long quality(ItemStack stack) {
        double efficiency = ToolSelectionHelper.effectiveEnchantmentLevel(stack, Enchantments.EFFICIENCY);
        return (1_000L - MinerBalance.mineTicks(stack.getItem(), efficiency)) * 1_000_000_000L
                + Math.round(MinerBalance.miningSpeed(stack.getItem(), efficiency) * 100) * 1_000L
                + ToolSelectionHelper.enchantPoints(stack);
    }

    /**
     * The allowed, usable pickaxe best by {@link #quality}: the one that breaks a
     * block in the fewest ticks; on a full tie, the one with the most uses left.
     * {@code level} is no longer needed and only kept for existing callers.
     */
    public static int findFastestUsablePickaxeSlot(
            InventoryManager inventory, Set<Item> allowed, ServerLevel level) {
        return ToolSelectionHelper.findBestToolSlot(inventory, ItemTags.PICKAXES, allowed,
                PickaxeUtils::isUsablePickaxe, PickaxeUtils::quality);
    }

    /**
     * Wears the pickaxe in {@code slot} by the vanilla one point per block,
     * with Unbreaking applied the vanilla way. Clears the slot if it breaks.
     *
     * @return true if the pickaxe survived, false if it broke or was not there
     */
    public static boolean damageForBlock(ServerLevel level, InventoryManager inventory, int slot) {
        ItemStack stack = inventory.getSlot(slot);
        if (!inventory.mayUse(stack) || !stack.is(ItemTags.PICKAXES)) return false;
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

    /**
     * Vanilla Mending: experience that would have come out of a block repairs
     * the pickaxe instead, at Mending's two durability per point. A villager
     * cannot collect orbs, so the orb step is skipped; without Mending the
     * experience is simply lost, as it is today.
     *
     * @return durability restored
     */
    public static int repairFromExperience(ServerLevel level, ItemStack stack, int experience) {
        if (experience <= 0 || stack.isEmpty() || !stack.isDamaged()) return 0;
        if (!EnchantmentHelper.has(stack, EnchantmentEffectComponents.REPAIR_WITH_XP)) return 0;
        int repair = PlayerGear.repair(stack,
                EnchantmentHelper.modifyDurabilityToRepairFromXp(level, stack, experience), level.random);
        int applied = Math.min(repair, stack.getDamageValue());
        if (applied > 0) stack.setDamageValue(stack.getDamageValue() - applied);
        return applied;
    }
}
