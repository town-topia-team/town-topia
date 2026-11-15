package com.alex.test.village.villagers.blacksmith;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * Old tools melted back into material.
 *
 * <p>When a worker swaps in a better tool from storage, the one it gave up is
 * stamped with the game time and its wear at that moment. If it is still lying
 * there a day later, unused -- its wear unchanged, so nobody has picked it up
 * and worked with it -- the blacksmith takes it and breaks it down. Tools a
 * player puts in storage never carry the stamp and are never touched, and
 * enchanted tools are never melted down.
 *
 * <p>The return is fair rather than full: the material the item was made from,
 * scaled by how much durability it has left and by the smith's recovery rate
 * ({@link BlacksmithBalance#recoveryRate}). Fractions are not lost; the smith
 * keeps a running total per material and banks whole items.
 */
public final class ForgeRecycling {

    /** One in-game day. */
    public static final long WAIT_TICKS = 24000L;

    private static final String KEY_TIME = "dv_recycle_since";
    private static final String KEY_DAMAGE = "dv_recycle_damage";

    /** What one item was made from: {@code count} of {@code material}, plus netherite scrap. */
    public record Salvage(Item material, int count, int netheriteScrap) {}

    private static final Map<Item, Salvage> TABLE = Map.ofEntries(
            // stone tools -> cobblestone
            Map.entry(Items.STONE_AXE, new Salvage(Items.COBBLESTONE, 3, 0)),
            Map.entry(Items.STONE_PICKAXE, new Salvage(Items.COBBLESTONE, 3, 0)),
            Map.entry(Items.STONE_SWORD, new Salvage(Items.COBBLESTONE, 2, 0)),
            Map.entry(Items.STONE_HOE, new Salvage(Items.COBBLESTONE, 2, 0)),
            Map.entry(Items.STONE_SHOVEL, new Salvage(Items.COBBLESTONE, 1, 0)),
            // wooden tools -> planks
            Map.entry(Items.WOODEN_AXE, new Salvage(Items.OAK_PLANKS, 3, 0)),
            Map.entry(Items.WOODEN_PICKAXE, new Salvage(Items.OAK_PLANKS, 3, 0)),
            Map.entry(Items.WOODEN_SWORD, new Salvage(Items.OAK_PLANKS, 2, 0)),
            Map.entry(Items.WOODEN_HOE, new Salvage(Items.OAK_PLANKS, 2, 0)),
            Map.entry(Items.WOODEN_SHOVEL, new Salvage(Items.OAK_PLANKS, 1, 0)),
            // iron
            Map.entry(Items.IRON_AXE, new Salvage(Items.IRON_INGOT, 3, 0)),
            Map.entry(Items.IRON_PICKAXE, new Salvage(Items.IRON_INGOT, 3, 0)),
            Map.entry(Items.IRON_SWORD, new Salvage(Items.IRON_INGOT, 2, 0)),
            Map.entry(Items.IRON_HOE, new Salvage(Items.IRON_INGOT, 2, 0)),
            Map.entry(Items.IRON_SHOVEL, new Salvage(Items.IRON_INGOT, 1, 0)),
            Map.entry(Items.SHEARS, new Salvage(Items.IRON_INGOT, 2, 0)),
            Map.entry(Items.SHIELD, new Salvage(Items.IRON_INGOT, 1, 0)),
            Map.entry(Items.IRON_HELMET, new Salvage(Items.IRON_INGOT, 5, 0)),
            Map.entry(Items.IRON_CHESTPLATE, new Salvage(Items.IRON_INGOT, 8, 0)),
            Map.entry(Items.IRON_LEGGINGS, new Salvage(Items.IRON_INGOT, 7, 0)),
            Map.entry(Items.IRON_BOOTS, new Salvage(Items.IRON_INGOT, 4, 0)),
            // gold
            Map.entry(Items.GOLDEN_AXE, new Salvage(Items.GOLD_INGOT, 3, 0)),
            Map.entry(Items.GOLDEN_PICKAXE, new Salvage(Items.GOLD_INGOT, 3, 0)),
            Map.entry(Items.GOLDEN_SWORD, new Salvage(Items.GOLD_INGOT, 2, 0)),
            Map.entry(Items.GOLDEN_HOE, new Salvage(Items.GOLD_INGOT, 2, 0)),
            Map.entry(Items.GOLDEN_SHOVEL, new Salvage(Items.GOLD_INGOT, 1, 0)),
            Map.entry(Items.GOLDEN_HELMET, new Salvage(Items.GOLD_INGOT, 5, 0)),
            Map.entry(Items.GOLDEN_CHESTPLATE, new Salvage(Items.GOLD_INGOT, 8, 0)),
            Map.entry(Items.GOLDEN_LEGGINGS, new Salvage(Items.GOLD_INGOT, 7, 0)),
            Map.entry(Items.GOLDEN_BOOTS, new Salvage(Items.GOLD_INGOT, 4, 0)),
            // leather
            Map.entry(Items.LEATHER_HELMET, new Salvage(Items.LEATHER, 5, 0)),
            Map.entry(Items.LEATHER_CHESTPLATE, new Salvage(Items.LEATHER, 8, 0)),
            Map.entry(Items.LEATHER_LEGGINGS, new Salvage(Items.LEATHER, 7, 0)),
            Map.entry(Items.LEATHER_BOOTS, new Salvage(Items.LEATHER, 4, 0)),
            // diamond
            Map.entry(Items.DIAMOND_AXE, new Salvage(Items.DIAMOND, 3, 0)),
            Map.entry(Items.DIAMOND_PICKAXE, new Salvage(Items.DIAMOND, 3, 0)),
            Map.entry(Items.DIAMOND_SWORD, new Salvage(Items.DIAMOND, 2, 0)),
            Map.entry(Items.DIAMOND_HOE, new Salvage(Items.DIAMOND, 2, 0)),
            Map.entry(Items.DIAMOND_SHOVEL, new Salvage(Items.DIAMOND, 1, 0)),
            Map.entry(Items.DIAMOND_HELMET, new Salvage(Items.DIAMOND, 5, 0)),
            Map.entry(Items.DIAMOND_CHESTPLATE, new Salvage(Items.DIAMOND, 8, 0)),
            Map.entry(Items.DIAMOND_LEGGINGS, new Salvage(Items.DIAMOND, 7, 0)),
            Map.entry(Items.DIAMOND_BOOTS, new Salvage(Items.DIAMOND, 4, 0)),
            // netherite: the diamond item underneath plus one ingot's four scrap
            Map.entry(Items.NETHERITE_AXE, new Salvage(Items.DIAMOND, 3, 4)),
            Map.entry(Items.NETHERITE_PICKAXE, new Salvage(Items.DIAMOND, 3, 4)),
            Map.entry(Items.NETHERITE_SWORD, new Salvage(Items.DIAMOND, 2, 4)),
            Map.entry(Items.NETHERITE_HOE, new Salvage(Items.DIAMOND, 2, 4)),
            Map.entry(Items.NETHERITE_SHOVEL, new Salvage(Items.DIAMOND, 1, 4)),
            Map.entry(Items.NETHERITE_HELMET, new Salvage(Items.DIAMOND, 5, 4)),
            Map.entry(Items.NETHERITE_CHESTPLATE, new Salvage(Items.DIAMOND, 8, 4)),
            Map.entry(Items.NETHERITE_LEGGINGS, new Salvage(Items.DIAMOND, 7, 4)),
            Map.entry(Items.NETHERITE_BOOTS, new Salvage(Items.DIAMOND, 4, 4)));

    private ForgeRecycling() {}

    /** What this item breaks down into, or null if the smith cannot melt it. */
    @Nullable
    public static Salvage salvageOf(Item item) {
        return TABLE.get(item);
    }

    /**
     * Marks a tool a villager just gave up for a better one. Called where the
     * swap puts the old tool back into storage.
     */
    public static void stampReturned(ItemStack stack, long gameTime) {
        if (stack.isEmpty() || !stack.isDamageableItem() || salvageOf(stack.getItem()) == null) return;
        int damage = stack.getDamageValue();
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putLong(KEY_TIME, gameTime);
            tag.putInt(KEY_DAMAGE, damage);
        });
    }

    /**
     * True once a stamped tool has sat unused for a day. Wear that changed since
     * the stamp means somebody took it and worked with it -- claimed -- so it
     * stays. Enchanted items are never eligible.
     */
    public static boolean isRecyclable(ItemStack stack, long gameTime) {
        if (stack.isEmpty() || stack.isEnchanted() || salvageOf(stack.getItem()) == null) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return false;
        CompoundTag tag = data.getUnsafe();
        if (!tag.contains(KEY_TIME)) return false;
        if (tag.getInt(KEY_DAMAGE) != stack.getDamageValue()) return false;
        return gameTime - tag.getLong(KEY_TIME) >= WAIT_TICKS;
    }

    /** Share of its durability the item still has, 0..1; 1 for undamageable items. */
    public static float durabilityLeft(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) return 1f;
        return (stack.getMaxDamage() - stack.getDamageValue()) / (float) stack.getMaxDamage();
    }
}
