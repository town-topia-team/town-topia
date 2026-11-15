package com.alex.test.village.building;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The few things only Sophisticated Storage can answer, reached by reflection so the mod
 * never needs it to build or run. Every call is safe without the mod installed and
 * answers "nothing special" if its classes ever change shape.
 *
 * <p>Checked against Sophisticated Storage 1.6.0 / Sophisticated Core 1.5.2.
 */
public final class SophisticatedCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PACKAGE = "net.p3pp3rf1y.";

    /**
     * Upgrades that change or delete what goes in: void, compacting, compression,
     * auto-smelting/smoking/blasting, block converter. Villagers put things in such a
     * container only when the player gave it a filter.
     */
    private static final String[] TRANSFORMING_UPGRADES = {
            "VoidUpgrade", "CompactingUpgrade", "CompressionUpgrade", "AutoCookingUpgrade",
            "BlockConverterUpgrade"};

    private static final Map<String, Optional<Method>> METHODS = new ConcurrentHashMap<>();
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private SophisticatedCompat() {}

    public static boolean isSophisticated(@Nullable Object object) {
        return object != null && object.getClass().getName().startsWith(PACKAGE);
    }

    /** The id of the contents a Sophisticated storage shows, shared by both halves of a double chest. */
    @Nullable
    public static UUID contentsId(@Nullable BlockEntity entity) {
        Object wrapper = storageWrapper(entity);
        if (wrapper == null) return null;
        return call(wrapper, "getContentsUuid") instanceof Optional<?> id
                && id.orElse(null) instanceof UUID uuid ? uuid : null;
    }

    /**
     * What a storage locked in its own screen still takes: the items its slots remember.
     * Empty when it is not locked or remembers nothing.
     */
    public static Set<Item> lockedItems(@Nullable BlockEntity entity) {
        if (!isSophisticated(entity) || !(call(entity, "isLocked") instanceof Boolean locked) || !locked) {
            return Set.of();
        }
        Object wrapper = storageWrapper(entity);
        Object settings = wrapper == null ? null : call(wrapper, "getSettingsHandler");
        if (settings == null) return Set.of();
        Object memory;
        try {
            Class<?> category = Class.forName(
                    "net.p3pp3rf1y.sophisticatedcore.settings.memory.MemorySettingsCategory");
            memory = call(settings, "getTypeCategory", new Class<?>[] {Class.class}, category);
        } catch (ClassNotFoundException e) {
            report("memory settings", e);
            return Set.of();
        }
        if (memory == null || !(call(memory, "getSlotIndexes") instanceof Collection<?> slots)) return Set.of();
        Set<Item> items = new HashSet<>();
        for (Object slot : slots) {
            if (call(memory, "getSlotFilterStack", new Class<?>[] {int.class, boolean.class}, slot, true)
                    instanceof Optional<?> filter && filter.orElse(null) instanceof ItemStack stack
                    && !stack.isEmpty()) {
                items.add(stack.getItem());
            }
        }
        return items;
    }

    /** Whether the storage has an upgrade that turns or voids items put into it. */
    public static boolean transformsItems(@Nullable BlockEntity entity) {
        Object wrapper = storageWrapper(entity);
        Object upgrades = wrapper == null ? null : call(wrapper, "getUpgradeHandler");
        if (upgrades == null || !(call(upgrades, "getSlotWrappers") instanceof Map<?, ?> slots)) return false;
        for (Object upgrade : slots.values()) {
            if (upgrade == null) continue;
            String name = upgrade.getClass().getName();
            for (String token : TRANSFORMING_UPGRADES) {
                if (name.contains(token)) return true;
            }
        }
        return false;
    }

    /** The block a Sophisticated storage screen was opened from, or null for any other menu. */
    @Nullable
    public static BlockPos menuPos(@Nullable AbstractContainerMenu menu) {
        if (!isSophisticated(menu)) return null;
        Object pos = call(menu, "getBlockPosition");
        if (pos instanceof Optional<?> optional) pos = optional.orElse(null);
        return pos instanceof BlockPos blockPos ? blockPos : null;
    }

    private static Constructor<?> lidPayload;
    private static boolean lidPayloadMissing;

    /**
     * Opens or closes a Sophisticated chest's lid for everyone watching. Its chests ignore
     * the vanilla lid event; its own payload makes the client call {@code setShouldBeOpen},
     * which also moves the other half of a double chest.
     */
    public static boolean sendLid(ServerLevel level, BlockPos pos, boolean open) {
        if (lidPayloadMissing) return false;
        try {
            if (lidPayload == null) {
                lidPayload = Class.forName("net.p3pp3rf1y.sophisticatedstorage.network.StorageOpennessPayload")
                        .getConstructor(BlockPos.class, boolean.class);
            }
            CustomPacketPayload payload = (CustomPacketPayload) lidPayload.newInstance(pos.immutable(), open);
            PacketDistributor.sendToPlayersTrackingChunk(level, new ChunkPos(pos), payload);
            return true;
        } catch (ReflectiveOperationException | ClassCastException | LinkageError e) {
            lidPayloadMissing = true;
            report("lid payload", e);
            return false;
        }
    }

    /** The storage wrapper holding the contents; the main half's for a double chest. */
    @Nullable
    private static Object storageWrapper(@Nullable BlockEntity entity) {
        if (!isSophisticated(entity)) return null;
        Object main = call(entity, "getMainStorageWrapper");
        return main != null ? main : call(entity, "getStorageWrapper");
    }

    @Nullable
    private static Object call(Object target, String name) {
        return call(target, name, new Class<?>[0]);
    }

    @Nullable
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        Class<?> type = target.getClass();
        Optional<Method> method = METHODS.computeIfAbsent(type.getName() + "#" + name + types.length, key -> {
            try {
                Method found = type.getMethod(name, types);
                found.trySetAccessible();
                return Optional.of(found);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return Optional.empty();
            }
        });
        if (method.isEmpty()) return null;
        try {
            return method.get().invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            report(name, e);
            return null;
        }
    }

    private static void report(String what, Throwable error) {
        if (REPORTED.add(what)) {
            LOGGER.warn("[SophisticatedCompat] {} unavailable; carrying on without it: {}", what, error.toString());
        }
    }
}
