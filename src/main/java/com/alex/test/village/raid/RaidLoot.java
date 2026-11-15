package com.alex.test.village.raid;

import com.alex.test.GuideBookItem;
import com.alex.test.village.building.StorageContainers;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.building.StorageInventory;
import com.alex.test.village.tokens.VillageTokenItem;
import com.alex.test.village.visitors.MerchantPricing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What a looter carries: stacks taken from one storage container, kept on the
 * raider itself (so they survive its chunk unloading) together with where
 * they came from. A looter killed gives them back to that container; one that
 * gets home puts them in the camp's war chest.
 *
 * <p>Never taken: village tokens, the guide book, enchanted gear and named
 * items. Raids steal, they do not ruin.
 */
public final class RaidLoot {

    private static final String TAG = "dynamicvillages_loot";
    private static final String TAG_SOURCE = "source";
    private static final String TAG_ITEMS = "items";

    private RaidLoot() {}

    /** Whether a raider of this raid may carry {@code stack} off. */
    public static boolean stealable(ItemStack stack, boolean foodOnly) {
        if (foodOnly && !VillageItemOwnership.isVillagerFood(stack)) return false;
        if (stack.isEmpty() || stack.isEnchanted() || stack.has(DataComponents.CUSTOM_NAME)) return false;
        if (stack.getItem() instanceof VillageTokenItem || stack.getItem() instanceof GuideBookItem
                || stack.getItem() instanceof RaidTrophyItem
                || stack.getItem() instanceof com.alex.test.village.raid.warlord.WarlordHelmItem) return false;
        // Never a box or bag with things inside: its whole contents would be lost with it.
        net.minecraft.world.item.component.ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents != null && contents.nonEmptyStream().findAny().isPresent()) return false;
        net.minecraft.world.item.component.BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) return false;
        return MerchantPricing.fullStackPrice(stack.getItem()) > 0;
    }

    /** Emeralds a stack is worth at today's base price. */
    public static double value(ItemStack stack) {
        int price = MerchantPricing.fullStackPrice(stack.getItem());
        return price * (double) stack.getCount() / Math.max(1, stack.getMaxStackSize());
    }

    /** Whether the container at {@code pos} holds anything a looter would take. */
    public static boolean hasLoot(ServerLevel level, BlockPos pos, boolean foodOnly) {
        StorageInventory inventory = StorageContainers.inventory(level, pos);
        if (inventory == null) return false;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (stealable(inventory.peek(slot), foodOnly)) return true;
        }
        return false;
    }

    /**
     * Takes up to {@code maxStacks} of the most valuable stacks from the
     * container at {@code pos}, stopping once {@code valueLeft} emeralds' worth
     * is gone (but always at least one stack). Returns the value taken; 0 when
     * there was nothing to take.
     */
    public static double steal(ServerLevel level, Mob looter, BlockPos pos, int maxStacks, double valueLeft,
                               boolean foodOnly) {
        StorageInventory inventory = StorageContainers.inventory(level, pos);
        if (inventory == null) return 0;
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (stealable(inventory.peek(slot), foodOnly)) slots.add(slot);
        }
        slots.sort(Comparator.comparingDouble((Integer slot) -> value(inventory.peek(slot))).reversed());
        List<ItemStack> taken = new ArrayList<>(carried(looter, level));
        double total = 0;
        for (int slot : slots) {
            if (taken.size() >= maxStacks) break;
            if (total > 0 && total >= valueLeft) break;
            ItemStack peek = inventory.peek(slot);
            // One normal stack at most: a modded slot can hold more than an item stack may save.
            ItemStack stack = inventory.take(slot, Math.min(peek.getCount(), peek.getMaxStackSize()));
            if (stack.isEmpty()) continue;
            total += value(stack);
            taken.add(stack);
        }
        if (total <= 0) return 0;
        save(level, looter, pos, taken);
        level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6F, 0.9F);
        level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6F, 0.9F);
        showCarrying(looter, taken.size());
        return total;
    }

    /** The stacks a looter carries (copies). */
    public static List<ItemStack> carried(Mob looter, ServerLevel level) {
        List<ItemStack> stacks = new ArrayList<>();
        CompoundTag data = looter.getPersistentData();
        if (!data.contains(TAG, Tag.TAG_COMPOUND)) return stacks;
        ListTag items = data.getCompound(TAG).getList(TAG_ITEMS, Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            ItemStack stack = ItemStack.parseOptional(level.registryAccess(), items.getCompound(i));
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    public static boolean isCarrying(Mob looter) {
        CompoundTag data = looter.getPersistentData();
        return data.contains(TAG, Tag.TAG_COMPOUND) && !data.getCompound(TAG).getList(TAG_ITEMS, Tag.TAG_COMPOUND).isEmpty();
    }

    /** "64 iron ingots and 2 more": what a looter carries, for a chat line. */
    public static Component describeText(List<ItemStack> stacks) {
        if (stacks.isEmpty()) return com.alex.test.Tr.c("chat.dynamicvillages.raid.loot.nothing", "nothing");
        ItemStack first = stacks.get(0);
        int more = stacks.size() - 1;
        if (more == 0) {
            return com.alex.test.Tr.c("chat.dynamicvillages.raid.loot.one", "%1$s %2$s", first.getCount(), first.getHoverName());
        }
        return com.alex.test.Tr.c("chat.dynamicvillages.raid.loot.more", "%1$s %2$s and %3$s more",
                first.getCount(), first.getHoverName(), more);
    }

    /**
     * A looter was beaten: what it carried goes back into the container it
     * came from; whatever no longer fits drops where the looter fell.
     * Returns the stacks given back.
     */
    public static int giveBack(ServerLevel level, Mob looter) {
        List<ItemStack> stacks = carried(looter, level);
        if (stacks.isEmpty()) return 0;
        BlockPos source = BlockPos.of(looter.getPersistentData().getCompound(TAG).getLong(TAG_SOURCE));
        StorageInventory inventory = level.hasChunk(source.getX() >> 4, source.getZ() >> 4)
                ? StorageContainers.inventory(level, source) : null;
        for (ItemStack stack : stacks) {
            ItemStack left = inventory != null ? inventory.insert(stack, false) : stack;
            if (!left.isEmpty()) looter.spawnAtLocation(left, 0.5F);
        }
        if (inventory != null) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, source.getX() + 0.5, source.getY() + 1.1,
                    source.getZ() + 0.5, 6, 0.3, 0.2, 0.3, 0.0);
        }
        clear(looter);
        return stacks.size();
    }

    /**
     * A looter got home: its stacks go into the war chest at {@code chest}
     * (or onto the ground there when the chest is gone or full). Returns the
     * stacks carried off.
     */
    public static int stash(ServerLevel level, Mob looter, BlockPos chest) {
        List<ItemStack> stacks = carried(looter, level);
        if (stacks.isEmpty()) return 0;
        StorageInventory inventory = chest != null ? StorageContainers.inventory(level, chest) : null;
        for (ItemStack stack : stacks) {
            ItemStack left = inventory != null ? inventory.insert(stack, false) : stack;
            if (!left.isEmpty()) looter.spawnAtLocation(left, 0.5F);
        }
        clear(looter);
        return stacks.size();
    }

    /** Gold sparkles over a looter's head, so players can tell who to chase. */
    public static void sparkle(ServerLevel level, Mob looter) {
        level.sendParticles(ParticleTypes.WAX_ON, looter.getX(), looter.getY() + looter.getBbHeight() + 0.4,
                looter.getZ(), 2, 0.2, 0.1, 0.2, 0.0);
    }

    private static void save(ServerLevel level, Mob looter, BlockPos source, List<ItemStack> stacks) {
        ListTag items = new ListTag();
        for (ItemStack stack : stacks) items.add(stack.save(level.registryAccess()));
        CompoundTag tag = new CompoundTag();
        tag.putLong(TAG_SOURCE, source.asLong());
        tag.put(TAG_ITEMS, items);
        looter.getPersistentData().put(TAG, tag);
    }

    private static void clear(Mob looter) {
        looter.getPersistentData().remove(TAG);
        looter.setCustomName(null);
        looter.setCustomNameVisible(false);
    }

    private static void showCarrying(Mob looter, int stacks) {
        looter.setCustomName(Component.literal("Looter (" + stacks + (stacks == 1 ? " stack)" : " stacks)")));
        looter.setCustomNameVisible(true);
    }
}
