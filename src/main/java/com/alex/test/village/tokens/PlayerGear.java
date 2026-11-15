package com.alex.test.village.tokens;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Tools, weapons and armor a player brings: villagers may use them, worse
 * than the village's own. Up to iron the enchantments work at half strength
 * and the item wears twice as fast; gold, bows, crossbows and shields get
 * half that penalty. Diamond and netherite stay in the chest. Base stats
 * (tier speed, armor points, sword damage) are untouched.
 */
public final class PlayerGear {
    private PlayerGear() {}

    public enum Penalty {
        NONE(1.0, 1.0),
        /** Wood, stone and iron tools, shears, leather, chainmail and iron armor. */
        FULL(0.5, 2.0),
        /** Gold tools and armor, bows, crossbows, shields. */
        HALF(0.75, 1.5);

        /** Share of each enchantment level that still works. */
        public final double enchantShare;
        /** Durability lost per point of vanilla wear. */
        public final double wearFactor;

        Penalty(double enchantShare, double wearFactor) {
            this.enchantShare = enchantShare;
            this.wearFactor = wearFactor;
        }
    }

    private static final Set<Item> FULL_PENALTY = Set.of(
            Items.WOODEN_AXE, Items.WOODEN_PICKAXE, Items.WOODEN_HOE, Items.WOODEN_SHOVEL, Items.WOODEN_SWORD,
            Items.STONE_AXE, Items.STONE_PICKAXE, Items.STONE_HOE, Items.STONE_SHOVEL, Items.STONE_SWORD,
            Items.IRON_AXE, Items.IRON_PICKAXE, Items.IRON_HOE, Items.IRON_SHOVEL, Items.IRON_SWORD,
            Items.SHEARS,
            Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS,
            Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS,
            Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS,
            Items.TURTLE_HELMET);

    private static final Set<Item> HALF_PENALTY = Set.of(
            Items.GOLDEN_AXE, Items.GOLDEN_PICKAXE, Items.GOLDEN_HOE, Items.GOLDEN_SHOVEL, Items.GOLDEN_SWORD,
            Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS,
            Items.BOW, Items.CROSSBOW, Items.SHIELD);

    private static final Set<Item> ARROWS = Set.of(Items.ARROW, Items.SPECTRAL_ARROW, Items.TIPPED_ARROW);

    /** A player's tool, weapon, armor piece or arrow a villager may use. */
    public static boolean isPlayerGear(ItemStack stack) {
        Item item = stack.getItem();
        return FULL_PENALTY.contains(item) || HALF_PENALTY.contains(item) || ARROWS.contains(item);
    }

    public static boolean isArrow(ItemStack stack) {
        return ARROWS.contains(stack.getItem());
    }

    /** How much worse this stack is than the village's own; NONE for the village's own. */
    public static Penalty penalty(ItemStack stack) {
        if (stack.isEmpty() || VillageTokenBinding.isBound(stack)) return Penalty.NONE;
        Item item = stack.getItem();
        if (FULL_PENALTY.contains(item)) return Penalty.FULL;
        if (HALF_PENALTY.contains(item)) return Penalty.HALF;
        return Penalty.NONE;
    }

    public static boolean isPenalised(ItemStack stack) {
        return penalty(stack) != Penalty.NONE;
    }

    /**
     * The enchantment level that really works, as an exact share: Efficiency
     * III on a player's iron pick is 1.5. Silk Touch cannot be halved, so on
     * a penalised item it does nothing.
     */
    public static double scaledLevel(ItemStack stack, Holder<Enchantment> enchantment, int level) {
        Penalty penalty = penalty(stack);
        if (penalty == Penalty.NONE || level <= 0) return level;
        if (enchantment.is(Enchantments.SILK_TOUCH)) return 0;
        return level * penalty.enchantShare;
    }

    public static double scaledLevel(ItemStack stack, ResourceKey<Enchantment> key, int level) {
        Penalty penalty = penalty(stack);
        if (penalty == Penalty.NONE || level <= 0) return level;
        if (key == Enchantments.SILK_TOUCH) return 0;
        return level * penalty.enchantShare;
    }

    /**
     * A whole number from an exact one, fairly: 1.5 is 1 half the time and 2
     * the other half, 0.5 is 0 or 1. On average it is the exact value.
     */
    public static int roll(double value, RandomSource random) {
        if (value <= 0) return 0;
        int whole = (int) Math.floor(value);
        return whole + (random.nextDouble() < value - whole ? 1 : 0);
    }

    /**
     * The stack as it works this time: the village's own unchanged, a
     * player's a copy with every enchantment level rolled down to its share
     * (Fortune I at half is Fortune I half the time, nothing the other half)
     * and Silk Touch gone. Hand this to vanilla loot, experience, Infinity
     * and Mending, never back into an inventory.
     */
    public static ItemStack effective(ItemStack stack, RandomSource random) {
        Penalty penalty = penalty(stack);
        if (penalty == Penalty.NONE || !stack.isEnchanted()) return stack;
        ItemEnchantments.Mutable weakened = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : stack.getEnchantments().entrySet()) {
            int level = roll(scaledLevel(stack, entry.getKey(), entry.getIntValue()), random);
            if (level > 0) weakened.set(entry.getKey(), level);
        }
        ItemStack copy = stack.copy();
        copy.set(DataComponents.ENCHANTMENTS, weakened.toImmutable());
        return copy;
    }

    /**
     * Durability {@code amount} points of use really cost: vanilla's, with
     * Unbreaking at its share, then twice as much on a player's iron (one and
     * a half on gold, bows and shields).
     */
    public static int wear(ServerLevel level, ItemStack stack, int amount) {
        int damage = EnchantmentHelper.processDurabilityChange(level, effective(stack, level.random), amount);
        Penalty penalty = penalty(stack);
        return penalty == Penalty.NONE ? damage : roll(damage * penalty.wearFactor, level.random);
    }

    /**
     * Runs one hit with the main hand holding the weapon as it really works:
     * a player's sword at its share, so Looting on whatever the hit kills is
     * weakened (vanilla reads it off the killer's hand). The hand gets its
     * own stack back straight after, in the same tick, so nothing is sent.
     */
    public static <T> T withWorkingMainHand(LivingEntity entity, Supplier<T> hit) {
        ItemStack held = entity.getMainHandItem();
        ItemStack working = effective(held, entity.getRandom());
        if (working == held) return hit.get();
        entity.setItemSlot(EquipmentSlot.MAINHAND, working);
        try {
            return hit.get();
        } finally {
            entity.setItemSlot(EquipmentSlot.MAINHAND, held);
        }
    }

    /** Durability a repair really gives back: the penalised share of it. */
    public static int repair(ItemStack stack, int amount, RandomSource random) {
        Penalty penalty = penalty(stack);
        return penalty == Penalty.NONE ? amount : roll(amount * penalty.enchantShare, random);
    }
}
