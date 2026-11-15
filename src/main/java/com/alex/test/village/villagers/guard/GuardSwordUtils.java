package com.alex.test.village.villagers.guard;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.Set;

/** Shared sword inventory/equipment helpers for Guards. */
public final class GuardSwordUtils {

    private GuardSwordUtils() {}

    public static boolean isSword(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.SWORDS);
    }

    public static int findUsableSwordSlot(InventoryManager inventory, Set<Item> allowed) {
        return ToolSelectionHelper.findUsableToolSlot(
                inventory, ItemTags.SWORDS, allowed, ToolSelectionHelper::isUsableByDefault);
    }

    /**
     * The allowed, usable sword that hits hardest ({@link #quality}): the one the
     * guard swings, wears down and keeps when banking.
     */
    public static int findBestUsableSwordSlot(InventoryManager inventory, Set<Item> allowed) {
        return ToolSelectionHelper.findBestToolSlot(inventory, ItemTags.SWORDS, allowed,
                ToolSelectionHelper::isUsableByDefault, GuardSwordUtils::quality);
    }

    /**
     * The guard's sword rule: damage per hit with Sharpness included, then
     * other enchantments, so a diamond Sharpness V sword (10) beats a plain
     * netherite one (8).
     */
    public static long quality(ItemStack sword) {
        return ToolSelectionHelper.byMeasure(attackDamage(sword), sword);
    }

    public static boolean hasUsableAllowedSword(InventoryManager inventory, Set<Item> allowed) {
        return findUsableSwordSlot(inventory, allowed) >= 0;
    }

    public static boolean isUsableAllowedSword(ItemStack stack, Set<Item> allowed) {
        return ToolSelectionHelper.isAllowedUsableTool(
                stack, ItemTags.SWORDS, allowed, ToolSelectionHelper::isUsableByDefault);
    }

    /** Equips the strongest currently allowed sword, or clears a disallowed sword. */
    public static int equipBestAllowedSword(NpcEntity npc, Set<Item> allowed) {
        ItemStack main = npc.getMainHandItem();
        if (isSword(main) && !allowed.contains(main.getItem())) {
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }

        int slot = findBestUsableSwordSlot(npc.getInventory(), allowed);
        if (slot >= 0) {
            npc.setItemSlot(EquipmentSlot.MAINHAND, npc.getInventory().getSlot(slot).copy());
        } else if (isSword(npc.getMainHandItem())) {
            npc.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        }
        return slot;
    }

    /**
     * Vanilla-like sword damage used by GuardActivity's manual attack: the
     * sword's own damage plus vanilla Sharpness (+1 at I, +0.5 a level after,
     * +3 at V). A player's sword has Sharpness at its share (PlayerGear).
     */
    public static float attackDamage(ItemStack sword) {
        if (sword.isEmpty()) return 2.0F;
        return baseDamage(sword.getItem()) + sharpnessBonus(
                ToolSelectionHelper.effectiveEnchantmentLevel(sword, Enchantments.SHARPNESS));
    }

    private static float baseDamage(Item item) {
        if (item == Items.WOODEN_SWORD) return 4.0F;
        if (item == Items.GOLDEN_SWORD) return 4.0F;
        if (item == Items.STONE_SWORD) return 5.0F;
        if (item == Items.IRON_SWORD) return 6.0F;
        if (item == Items.DIAMOND_SWORD) return 7.0F;
        if (item == Items.NETHERITE_SWORD) return 8.0F;
        return 2.0F;
    }

    /**
     * Vanilla Sharpness: 0.5 × level + 0.5, nothing without it. A part level
     * below I (a player's Sharpness I at half) rises in a straight line to I's 1.
     */
    public static float sharpnessBonus(double level) {
        if (level <= 0) return 0F;
        return (float) (level < 1.0 ? level : 0.5 * level + 0.5);
    }

    /**
     * Wears the strongest equipped/allowed sword by one attack, Unbreaking applied
     * the vanilla way, and clears it if it breaks.
     */
    public static boolean damageBestSwordAfterAttack(
            ServerLevel level, InventoryManager inventory, Set<Item> allowed) {
        return ToolSelectionHelper.wearTool(
                level, inventory, findBestUsableSwordSlot(inventory, allowed));
    }
}
