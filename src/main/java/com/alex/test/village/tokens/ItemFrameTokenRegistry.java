package com.alex.test.village.tokens;

import com.alex.test.ModItems;
import com.alex.test.village.building.MerchantStallBuilding;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;

/** Shared token-item recognition and item-frame glint cleanup. */
public final class ItemFrameTokenRegistry {

    public enum TokenType {
        TOWN_HALL("town_hall"),
        TWO_BED_HOUSE("2_bed_house", 2),
        FOUR_BED_HOUSE("4_bed_house", 4),
        SIX_BED_HOUSE("6_bed_house", 6),
        STORAGE("storage"),
        GUARD_POST("guard_post"),
        KITCHEN("kitchen"),
        BUTCHERY("butchery"),
        FORGE("forge"),
        MINE("mine"),
        RANCH("ranch"),
        TAVERN("tavern"),
        LIBRARY("library"),
        SCHOOL("school"),
        STALL("stall"),
        BARRACKS("barracks"),
        ARCHERY_RANGE("archery_range"),
        WATCHTOWER("watchtower"),
        // Appended: the mages' room (see MageTowerBuilding).
        MAGE_TOWER("mage_tower");

        private final String savedBuildingType;
        private final int housingCapacity;

        TokenType(String savedBuildingType) {
            this(savedBuildingType, 0);
        }

        TokenType(String savedBuildingType, int housingCapacity) {
            this.savedBuildingType = savedBuildingType;
            this.housingCapacity = housingCapacity;
        }

        public String savedBuildingType() {
            return savedBuildingType;
        }

        public boolean isHouse() {
            return housingCapacity > 0;
        }

        public int housingCapacity() {
            return housingCapacity;
        }
    }

    private ItemFrameTokenRegistry() {}

    @Nullable
    public static TokenType tokenType(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        Item item = stack.getItem();
        if (item == ModItems.TOWN_HALL.get()) return TokenType.TOWN_HALL;
        if (item == ModItems.TWO_BED_HOUSE.get()) return TokenType.TWO_BED_HOUSE;
        if (item == ModItems.FOUR_BED_HOUSE.get()) return TokenType.FOUR_BED_HOUSE;
        if (item == ModItems.SIX_BED_HOUSE.get()) return TokenType.SIX_BED_HOUSE;
        if (item == ModItems.STORAGE_ROOM.get()) return TokenType.STORAGE;
        if (item == ModItems.GUARD_POST.get()) return TokenType.GUARD_POST;
        if (item == ModItems.KITCHEN.get()) return TokenType.KITCHEN;
        if (item == ModItems.BUTCHERY.get()) return TokenType.BUTCHERY;
        if (item == ModItems.FORGE.get()) return TokenType.FORGE;
        if (item == ModItems.MINE.get()) return TokenType.MINE;
        if (item == ModItems.RANCH.get()) return TokenType.RANCH;
        if (item == ModItems.TAVERN.get()) return TokenType.TAVERN;
        if (item == ModItems.LIBRARY.get()) return TokenType.LIBRARY;
        if (item == ModItems.SCHOOL.get()) return TokenType.SCHOOL;
        if (item == ModItems.STALL.get()) return TokenType.STALL;
        if (item == ModItems.BARRACKS.get()) return TokenType.BARRACKS;
        if (item == ModItems.ARCHERY_RANGE.get()) return TokenType.ARCHERY_RANGE;
        if (item == ModItems.WATCHTOWER.get()) return TokenType.WATCHTOWER;
        if (item == ModItems.MAGE_TOWER.get()) return TokenType.MAGE_TOWER;
        return null;
    }

    public static boolean isToken(ItemStack stack) {
        return tokenType(stack) != null;
    }

    /** Door-based room buildings handled by VillageManager's room scan. */
    public static boolean isBuildingToken(ItemStack stack) {
        TokenType type = tokenType(stack);
        return type == TokenType.TOWN_HALL
                || type != null && type.isHouse()
                || type == TokenType.STORAGE
                || type == TokenType.KITCHEN
                || type == TokenType.BUTCHERY
                || type == TokenType.FORGE
                || type == TokenType.TAVERN
                || type == TokenType.LIBRARY
                || type == TokenType.SCHOOL
                || type == TokenType.STALL
                || type == TokenType.BARRACKS
                || type == TokenType.MAGE_TOWER
                // An Archery Range beside a door is a room too (beside a gate, a fenced yard).
                || type == TokenType.ARCHERY_RANGE;
    }

    /**
     * Bare-rock outcrops — no door, no walls, no ceiling.
     *
     * <p>Only the Mine, and it used to be a room token. A mine is a hole in a
     * rock face, and asking a player to wall a room around one produced more
     * invalid Mines than valid ones: see {@code MineBuilding} for the full
     * story. The token now hangs straight on the stone.
     */
    public static boolean isOutcropToken(ItemStack stack) {
        return tokenType(stack) == TokenType.MINE;
    }

    /** Gate-based fenced enclosures (ranches, archery ranges) — no door, no ceiling. */
    public static boolean isEnclosureToken(ItemStack stack) {
        TokenType type = tokenType(stack);
        return type == TokenType.RANCH || type == TokenType.ARCHERY_RANGE;
    }

    /** A Watchtower: the token hangs on the platform itself; no door, no room. */
    public static boolean isTowerToken(ItemStack stack) {
        return tokenType(stack) == TokenType.WATCHTOWER;
    }

    public static boolean isGuardPostToken(ItemStack stack) {
        return tokenType(stack) == TokenType.GUARD_POST;
    }

    public static boolean savedTypeMatches(String savedBuildingType, ItemStack stack) {
        TokenType type = tokenType(stack);
        if (type == null) return false;
        // One Stall token, two shapes: a room stall or an open-air one.
        return type.savedBuildingType().equals(savedBuildingType)
                || type == TokenType.STALL
                && MerchantStallBuilding.OPEN_AIR_SAVED_TYPE.equals(savedBuildingType)
                // One Archery Range token, two shapes too: a fenced yard or a walled room.
                || type == TokenType.ARCHERY_RANGE
                && com.alex.test.village.building.ArcheryRangeBuilding.ROOM_SAVED_TYPE.equals(savedBuildingType);
    }

    public static boolean hasGlintOverride(ItemStack stack) {
        return stack != null && stack.has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
    }

    public static ItemStack withoutRegistrationGlint(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isToken(stack)
                || !hasGlintOverride(stack)) {
            return stack;
        }
        ItemStack copy = stack.copy();
        copy.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        return copy;
    }

    public static void setRegisteredGlint(ItemFrame frame, boolean registered) {
        ItemStack stack = frame.getItem();
        if (!isToken(stack)) return;
        boolean hasOverride = hasGlintOverride(stack);
        if (registered && Boolean.TRUE.equals(stack.get(DataComponents.ENCHANTMENT_GLINT_OVERRIDE))) {
            return;
        }
        if (!registered && !hasOverride) return;

        ItemStack copy = stack.copy();
        if (registered) {
            copy.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        } else {
            copy.remove(DataComponents.ENCHANTMENT_GLINT_OVERRIDE);
        }
        frame.setItem(copy);
    }

    public static void setRegisteredGlintAt(ServerLevel level, BlockPos framePos, boolean registered) {
        ItemFrame frame = findFrameAt(level, framePos);
        if (frame != null) setRegisteredGlint(frame, registered);
    }

    @Nullable
    public static ItemFrame findFrameAt(ServerLevel level, BlockPos framePos) {
        AABB box = new AABB(framePos).inflate(1.0D);
        ItemFrame exact = null;
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (frame.blockPosition().equals(framePos)) return frame;
            if (exact == null) exact = frame;
        }
        return exact;
    }

    @Nullable
    public static ItemFrame findExactFrameAt(ServerLevel level, BlockPos framePos) {
        AABB box = new AABB(framePos).inflate(1.0D);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, box)) {
            if (frame.blockPosition().equals(framePos)) return frame;
        }
        return null;
    }
}
