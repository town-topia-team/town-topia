package com.alex.test.village.tokens;

import com.alex.test.LegacyModId;
import com.alex.test.village.VillageManager;
import net.minecraft.core.component.DataComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * The permanent identity shared by a Town Hall token, its village and its supplies.
 * The townHallId is the logical village UUID, never the replacement frame's UUID.
 * Custom data survives inventory transfers, frame drops, stacking and world saves.
 */
public record VillageTokenBinding(UUID townHallId, String dimension) {
    private static final String KEY = "dynamicvillages:town_hall_binding";
    /** Tokens, frames and chests bound before the mod id rename. */
    private static final String LEGACY_KEY = LegacyModId.OLD + ":town_hall_binding";
    private static final String TAG_ID = "townHallId";
    private static final String TAG_DIMENSION = "dimension";
    private static final String GENERATION_KEY = "dynamicvillages:town_hall_generation";
    private static final String REPLACED_KEY = "dynamicvillages:town_hall_replaced";

    public static VillageTokenBinding of(VillageManager village) {
        return new VillageTokenBinding(village.getVillageId(),
                village.getLevel().dimension().location().toString());
    }

    @Nullable
    public static VillageTokenBinding read(ItemStack stack) {
        return read(view(stack));
    }

    @Nullable
    private static VillageTokenBinding read(CompoundTag data) {
        // A binding still under the old mod id counts as the new key, as migrated() would make it.
        String key = data.contains(KEY) || !data.contains(LEGACY_KEY) ? KEY : LEGACY_KEY;
        if (!data.contains(key, Tag.TAG_COMPOUND)) return null;
        CompoundTag binding = data.getCompound(key);
        if (!binding.hasUUID(TAG_ID) || !binding.contains(TAG_DIMENSION, Tag.TAG_STRING)) return null;
        String dimension = binding.getString(TAG_DIMENSION);
        if (dimension.isBlank() || net.minecraft.resources.ResourceLocation.tryParse(dimension) == null) return null;
        return new VillageTokenBinding(binding.getUUID(TAG_ID), dimension);
    }

    public static boolean isBound(ItemStack stack) {
        // Malformed ownership is rejected as well; it must never become an unbound token.
        CompoundTag data = view(stack);
        return data.contains(KEY) || data.contains(LEGACY_KEY);
    }

    public static boolean matches(ItemStack stack, VillageManager village) {
        return village.getLevel() != null && of(village).equals(read(stack));
    }

    public static boolean belongsToAnotherVillage(ItemStack stack, VillageManager village) {
        return read(stack) != null && !matches(stack, village);
    }

    /** Use only at trusted issuance points, such as the mayor shop and founding chest. */
    public static boolean bind(ItemStack stack, VillageManager village) {
        return village.getLevel() != null && bindTrusted(stack, of(village));
    }

    /** Callers must establish trusted issuance or production before binding. */
    static boolean bindTrusted(ItemStack stack, VillageTokenBinding owner) {
        if (stack.isEmpty()) return false;
        if (isBound(stack)) {
            if (!owner.equals(read(stack))) return false;
            applyName(stack);
            return true;
        }
        CompoundTag data = data(stack);
        data.put(KEY, owner.save());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
        applyName(stack);
        return true;
    }

    /**
     * Which issue of its village's Town Hall token this stack is. Each
     * replacement counts up, and a token from before the latest no longer
     * works. Kept beside the binding, not in it: chests and other tokens
     * compare bindings, and must keep matching across replacements.
     */
    public static int townHallGeneration(ItemStack stack) {
        return view(stack).getInt(GENERATION_KEY);
    }

    /** Callers own the village's count; see the registry's Town Hall token issue. */
    public static void setTownHallGeneration(ItemStack stack, int generation) {
        CompoundTag data = data(stack);
        data.putInt(GENERATION_KEY, generation);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    /** Set on the server once a newer Town Hall token exists, so the tooltip can say so. */
    public static boolean isMarkedReplaced(ItemStack stack) {
        return view(stack).getBoolean(REPLACED_KEY);
    }

    public static void markReplaced(ItemStack stack) {
        CompoundTag data = data(stack);
        data.putBoolean(REPLACED_KEY, true);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
    }

    private static void applyName(ItemStack stack) {
        net.minecraft.network.chat.Component name = stack.getItem().getName(stack);
        if (VillageFood.isVillagerMade(stack)) {
            // The item's own name, not the stack's: the stack may already
            // carry the green name, which would otherwise be prefixed twice.
            name = net.minecraft.network.chat.Component.literal(VillageFood.POOR_PREFIX)
                    .append(stack.getItem().getName(stack.getItem().getDefaultInstance()));
        }
        stack.set(DataComponents.ITEM_NAME, name.copy().withStyle(ChatFormatting.GREEN));
    }

    /** Re-applies the green village name, for a stack whose maker was just sorted out. */
    static void refreshName(ItemStack stack) {
        if (isBound(stack)) applyName(stack);
    }

    public static boolean bindFrame(ItemFrame frame, VillageManager village) {
        if (frame.level() != village.getLevel() || !village.contains(frame.blockPosition())) return false;
        return isBound(frame.getItem()) && matches(frame.getItem(), village);
    }

    /** A fresh token cannot impersonate the old Town Hall by reusing its empty frame. */
    public static boolean bindTownHallFrame(ItemFrame frame, VillageManager village) {
        if (!isBound(frame.getItem()) && migrated(frame.getPersistentData()).contains(KEY)) return false;
        if (!bindFrame(frame, village)) return false;
        migrated(frame.getPersistentData()).put(KEY, of(village).save());
        return true;
    }

    public static boolean bindSavedTownHallFrame(ItemFrame frame, VillageManager village) {
        if (!isBound(frame.getItem()) && !village.hasSavedTownHall(frame.getUUID())) return false;
        ItemStack token = frame.getItem().copy();
        if (!isBound(token) && !bind(token, village)) return false;
        if (!matches(token, village)) return false;
        frame.setItem(token);
        migrated(frame.getPersistentData()).put(KEY, of(village).save());
        return true;
    }

    /** Undo the provisional frame identity if founding failed before a village was saved. */
    public static void clearFailedFounding(ItemFrame frame) {
        migrated(frame.getPersistentData()).remove(KEY);
    }

    /** Stores the same owner on the supply chest, without changing ordinary chest access. */
    public static boolean bindChest(BlockEntity chest, VillageManager village) {
        if (chest.getLevel() != village.getLevel()) return false;
        CompoundTag data = migrated(chest.getPersistentData());
        VillageTokenBinding owner = of(village);
        if (data.contains(KEY)) return owner.equals(read(data));
        data.put(KEY, owner.save());
        chest.setChanged();
        return true;
    }

    /** An owned chest can only participate in storage marked by the same Town Hall. */
    public static boolean canUseChest(BlockEntity chest, ItemStack storageToken) {
        CompoundTag data = migrated(chest.getPersistentData());
        if (!data.contains(KEY)) return true;
        VillageTokenBinding owner = read(data);
        return owner != null && owner.equals(read(storageToken));
    }

    /** Shared tag for stacks with no custom data. Read only: never hand it out or write to it. */
    private static final CompoundTag NO_DATA = new CompoundTag();

    /**
     * The stack's custom data without copying it, for checks that only read.
     * A plain item (most of them) costs nothing. Callers that write use
     * {@link #data}, which copies and migrates.
     */
    private static CompoundTag view(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? NO_DATA : data.getUnsafe();
    }

    private static CompoundTag data(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? new CompoundTag() : migrated(data.copyTag());
    }

    private static CompoundTag migrated(CompoundTag tag) {
        LegacyModId.renameKey(tag, LEGACY_KEY, KEY);
        return tag;
    }

    private CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID(TAG_ID, townHallId);
        tag.putString(TAG_DIMENSION, dimension);
        return tag;
    }
}
