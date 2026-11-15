package com.alex.test.village.visitors;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.tokens.VillageItemOwnership;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The best thing a villager banked today: what it shows off when a visitor
 * chats with it ({@link TravelerTalk}). Recorded when the villager puts its
 * work into storage, the one step every job shares, and kept with the
 * villager's saved data; a new village day starts it over.
 *
 * <p>"Best" weighs rarity over amount: one diamond beats a stack of cobble,
 * and a forged tool beats a pile of logs.
 */
public final class MadeToday {
    private static final String TAG_DAY = "madeTodayDay";
    private static final String TAG_ITEM = "madeTodayItem";
    private static final String TAG_SCORE = "madeTodayScore";

    private MadeToday() {}

    /** {@code npc} banked {@code count} of {@code item}. */
    public static void record(NpcEntity npc, Item item, int count, ServerLevel level) {
        if (count <= 0 || item == Items.AIR) return;
        // A tool or armour piece banked by anyone but the blacksmith is a spare
        // being returned, not today's work: nobody shows off a used hoe.
        if (new ItemStack(item).isDamageableItem()
                && npc.getProfession() != com.alex.test.village.villagers.core.Profession.BLACKSMITH) return;
        CompoundTag data = npc.getBrainData();
        long today = TravelerLifecycleRules.dayIndex(level.getDayTime());
        int score = weight(item) * Math.min(count, 64);
        boolean sameDay = data.contains(TAG_DAY) && data.getLong(TAG_DAY) == today;
        if (sameDay && data.getInt(TAG_SCORE) >= score) return;
        data.putLong(TAG_DAY, today);
        data.putString(TAG_ITEM, BuiltInRegistries.ITEM.getKey(item).toString());
        data.putInt(TAG_SCORE, score);
    }

    /** What {@code npc} banked today worth showing, or empty for a day with nothing to show. */
    public static ItemStack of(NpcEntity npc, ServerLevel level) {
        CompoundTag data = npc.getBrainData();
        if (!data.contains(TAG_DAY) || data.getLong(TAG_DAY) != TravelerLifecycleRules.dayIndex(level.getDayTime())) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(data.getString(TAG_ITEM));
        Item item = id == null ? Items.AIR : BuiltInRegistries.ITEM.get(id);
        return item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static int weight(Item item) {
        ItemStack stack = new ItemStack(item);
        if (item == Items.DIAMOND || item == Items.EMERALD || item == Items.NETHERITE_INGOT
                || item == Items.NETHERITE_SCRAP || item == Items.ENCHANTED_BOOK) return 60;
        if (stack.isDamageableItem()) return 25; // a tool, weapon or armour piece made today
        if (item == Items.GOLD_INGOT || item == Items.IRON_INGOT || item == Items.RAW_GOLD
                || item == Items.RAW_IRON || item == Items.COPPER_INGOT || item == Items.LAPIS_LAZULI
                || item == Items.REDSTONE || item == Items.POTION) return 12;
        if (stack.is(ItemTags.LOGS) || VillageItemOwnership.isVillagerFood(stack)) return 3;
        return 1;
    }
}
