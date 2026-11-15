package com.alex.test.village.building;

import com.alex.test.DynamicVillages;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Which blocks a storage room uses as its chests and barrels, and how to reach their contents.
 *
 * <p>Vanilla chests and barrels (and any block extending them) always count. Other mods'
 * chests come from the {@code dynamicvillages:storage_containers} block tag, which a
 * datapack can extend. A tagged block counts once its contents can be reached: as a
 * vanilla {@link Container} (Iron Chests) or through NeoForge's item handler
 * (Sophisticated Storage). See {@link StorageInventory}.
 */
public final class StorageContainers {
    public static final TagKey<Block> STORAGE_CONTAINERS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "storage_containers"));
    /** Tagged containers that open and sound like a barrel rather than a chest. */
    public static final TagKey<Block> STORAGE_BARRELS = TagKey.create(Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "storage_barrels"));

    /**
     * Stands in for chests while an away step runs ({@code economy/away/AwayBook}): the
     * village's chests answer with copies, so every storage helper works on the copies
     * unchanged. Null outside a step. Server thread only.
     */
    public interface ContainerOverride {
        /** The copy standing in for the container at {@code pos}, or null for the real one. */
        @Nullable StorageInventory at(Level level, BlockPos pos);
    }

    @Nullable private static ContainerOverride override;

    private StorageContainers() {}

    /** Opens ({@code value}) or closes (null) the stand-in; only one is ever open. */
    public static void setOverride(@Nullable ContainerOverride value) {
        if (value != null && override != null && override != value) {
            throw new IllegalStateException("A chest stand-in is already open");
        }
        override = value;
    }

    /** The block is a chest or barrel kind. Safe on the client: block tags are synced. */
    public static boolean isStorageBlock(BlockState state) {
        return isStorageBlock(state.getBlock());
    }

    public static boolean isStorageBlock(Block block) {
        return block instanceof ChestBlock || block instanceof BarrelBlock
                || block.builtInRegistryHolder().is(STORAGE_CONTAINERS);
    }

    /** A chest or barrel whose contents villagers can read and change. */
    public static boolean isStorageContainer(Level level, BlockPos pos) {
        return inventory(level, pos) != null;
    }

    public static boolean isBarrel(BlockState state) {
        return state.getBlock() instanceof BarrelBlock || state.is(STORAGE_BARRELS);
    }

    /**
     * The contents of the chest or barrel at {@code pos}, or null when it is not one or
     * cannot be reached (a Sophisticated chest packed with tape has no contents to offer).
     * Either half of a Sophisticated double chest gives the whole chest; see
     * {@link StorageInventory#key()}.
     */
    @Nullable
    public static StorageInventory inventory(Level level, BlockPos pos) {
        if (override != null && !level.isClientSide) {
            StorageInventory copy = override.at(level, pos);
            if (copy != null) return copy;
        }
        BlockState state = level.getBlockState(pos);
        if (!isStorageBlock(state)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return null;
        if (entity instanceof Container container) return new StorageInventory.OfContainer(container);
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, state, entity, null);
        if (handler == null) return null;
        return new StorageInventory.OfHandler(handler, sharedKey(level, pos, entity));
    }

    /** What every position showing the same handler-backed contents has in common. */
    private static Object sharedKey(Level level, BlockPos pos, BlockEntity entity) {
        UUID contents = SophisticatedCompat.contentsId(entity);
        if (contents != null) return contents;
        // A handler-backed double chest hands out the whole chest from either half.
        BlockPos partner = pairedChestPos(level, pos);
        if (partner != null && partner.asLong() < pos.asLong()) return partner.immutable();
        return pos.immutable();
    }

    /**
     * The other half of a double chest, for any chest block with the vanilla chest-type
     * property (vanilla, Iron Chests, Sophisticated Storage), or null for a single one.
     */
    @Nullable
    public static BlockPos pairedChestPos(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(BlockStateProperties.CHEST_TYPE)
                || state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE) return null;
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            // Where vanilla puts the other half; mods that copy the property copy the layout.
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            Direction side = state.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.LEFT
                    ? facing.getClockWise() : facing.getCounterClockWise();
            if (pairs(state, level.getBlockState(pos.relative(side)))) return pos.relative(side);
        }
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos other = pos.relative(direction);
            if (pairs(state, level.getBlockState(other))) return other;
        }
        return null;
    }

    private static boolean pairs(BlockState state, BlockState candidate) {
        if (candidate.getBlock() != state.getBlock()
                || candidate.getValue(BlockStateProperties.CHEST_TYPE) == ChestType.SINGLE
                || candidate.getValue(BlockStateProperties.CHEST_TYPE)
                        == state.getValue(BlockStateProperties.CHEST_TYPE)) return false;
        return !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || candidate.getValue(BlockStateProperties.HORIZONTAL_FACING)
                        == state.getValue(BlockStateProperties.HORIZONTAL_FACING);
    }

    /**
     * Shows a villager opening or closing the container, with its sound. Barrels (vanilla
     * and modded) flip their open state; chests move their lid through the vanilla block
     * event, except Sophisticated chests, which only listen to their own payload.
     */
    public static void setOpen(ServerLevel level, BlockPos pos, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (!isStorageBlock(state)) return;
        boolean barrel = isBarrel(state);
        if (state.hasProperty(BlockStateProperties.OPEN)) {
            if (state.getValue(BlockStateProperties.OPEN) != open) {
                level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), Block.UPDATE_ALL);
            }
        } else if (!SophisticatedCompat.isSophisticated(level.getBlockEntity(pos))
                || !SophisticatedCompat.sendLid(level, pos, open)) {
            level.blockEvent(pos, state.getBlock(), 1, open ? 1 : 0);
        }
        level.playSound(null, pos, barrel
                        ? (open ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE)
                        : (open ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE),
                SoundSource.BLOCKS, 0.5f, level.random.nextFloat() * 0.1f + 0.9f);
    }
}
