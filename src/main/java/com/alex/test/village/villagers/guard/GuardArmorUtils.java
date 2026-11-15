package com.alex.test.village.villagers.guard;

import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.villagers.blacksmith.ForgeOrderBook;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Set;

/** Allowed guard armor and the four live equipment slots. */
public final class GuardArmorUtils {

    /** The four armor slots in a fixed head/chest/legs/feet order. */
    public static final List<EquipmentSlot> ARMOR_SLOTS =
            List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

    private GuardArmorUtils() {}

    public static TagKey<Item> tagForSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> ItemTags.HEAD_ARMOR;
            case CHEST -> ItemTags.CHEST_ARMOR;
            case LEGS -> ItemTags.LEG_ARMOR;
            case FEET -> ItemTags.FOOT_ARMOR;
            default -> throw new IllegalArgumentException("Not an armor slot: " + slot);
        };
    }

    /** Every vanilla armor material a guard may wear in this slot. No tier settings (yet) — all allowed. */
    public static Set<Item> allowedArmorFor(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> Set.of(Items.LEATHER_HELMET, Items.GOLDEN_HELMET, Items.CHAINMAIL_HELMET,
                    Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET);
            case CHEST -> Set.of(Items.LEATHER_CHESTPLATE, Items.GOLDEN_CHESTPLATE, Items.CHAINMAIL_CHESTPLATE,
                    Items.IRON_CHESTPLATE, Items.DIAMOND_CHESTPLATE, Items.NETHERITE_CHESTPLATE);
            case LEGS -> Set.of(Items.LEATHER_LEGGINGS, Items.GOLDEN_LEGGINGS, Items.CHAINMAIL_LEGGINGS,
                    Items.IRON_LEGGINGS, Items.DIAMOND_LEGGINGS, Items.NETHERITE_LEGGINGS);
            case FEET -> Set.of(Items.LEATHER_BOOTS, Items.GOLDEN_BOOTS, Items.CHAINMAIL_BOOTS,
                    Items.IRON_BOOTS, Items.DIAMOND_BOOTS, Items.NETHERITE_BOOTS);
            default -> Set.of();
        };
    }

    /**
     * How good a piece is, higher is better. The smith's material ladder comes first
     * ({@link ForgeOrderBook#tierOf}: iron, diamond, netherite above leather, gold and
     * chain), so every piece it forges as "better armour for the watch" is an upgrade;
     * then armor points, then toughness; enchantments only break ties. Wear never
     * counts, as with tools: a worn-down piece is kept until it breaks.
     */
    public static long quality(ItemStack stack) {
        if (!(stack.getItem() instanceof ArmorItem armor)) return ToolSelectionHelper.byMeasure(0, stack);
        double measure = ForgeOrderBook.tierOf(stack.getItem()) * 100.0
                + armor.getDefense() + armor.getToughness() / 10.0;
        return ToolSelectionHelper.byMeasure(measure, stack);
    }

    public static boolean isArmorSlotEmpty(NpcEntity npc, EquipmentSlot slot) {
        return npc.getItemBySlot(slot).isEmpty();
    }

    /**
     * Puts on armor the guard already carries, with no storage trip: the best fitting
     * pack piece goes into an empty slot, or in place of a worse worn one, which takes
     * the pack slot just freed (the evening deposit banks it; guards keep no armor).
     * Pack armor gets there on a job change and back ({@code NpcEntity.stowArmorUnlessGuard})
     * and used to wait for that deposit while the guard went without. One slot per call.
     *
     * @return the slot that changed, or null
     */
    @Nullable
    public static EquipmentSlot wearFromPack(NpcEntity npc) {
        InventoryManager pack = npc.getInventory();
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = npc.getItemBySlot(slot);
            if (NpcEntity.isDisplayOnly(worn)) continue;
            long bestQuality = worn.isEmpty() ? Long.MIN_VALUE : quality(worn);
            int best = -1;
            for (int i = 0; i < InventoryManager.SIZE; i++) {
                ItemStack stack = pack.getSlot(i);
                if (!fitsSlot(npc, slot, stack)) continue;
                long q = quality(stack);
                if (q > bestQuality) {
                    bestQuality = q;
                    best = i;
                }
            }
            if (best < 0) continue;
            ItemStack piece = pack.getSlot(best).copy();
            pack.setSlot(best, worn.isEmpty() ? ItemStack.EMPTY : worn.copy());
            npc.setItemSlot(slot, piece);
            return slot;
        }
        return null;
    }

    /** A single usable allowed piece for {@code slot} that the guard's village may use. */
    private static boolean fitsSlot(NpcEntity npc, EquipmentSlot slot, ItemStack stack) {
        return !stack.isEmpty() && stack.getCount() == 1 && stack.is(tagForSlot(slot))
                && allowedArmorFor(slot).contains(stack.getItem())
                && ToolSelectionHelper.isUsableByDefault(stack)
                && !NpcEntity.isDisplayOnly(stack)
                && npc.getInventory().mayUse(stack);
    }

    /** First unfilled armor slot in a fixed head/chest/legs/feet order, or null if fully geared. */
    @Nullable
    public static EquipmentSlot firstMissingSlot(NpcEntity npc) {
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            if (isArmorSlotEmpty(npc, slot)) return slot;
        }
        return null;
    }
}
