package com.alex.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The mod id was "villagermod" until 2026-09-25 and "dynamicvillages" until 2026-10-22, now
 * {@link DynamicVillages#MODID}. Worlds saved under an old id name every villager, token and block
 * with that namespace; without this they load with the villagers skipped and the tokens deleted.
 * Never remove the aliases: a chunk nobody visits for years still holds the old names.
 * <p>
 * Everything the mod writes into a world that nobody sees (saved-data files, labels on chests,
 * mobs and items, attribute modifier ids) keeps {@link #SAVE_PREFIX} instead of following the mod
 * id, so a world reads back exactly what it wrote. Our own language keys ("screen.dynamicvillages.*")
 * keep the old name too. Token names already stamped as item.villagermod.* / item.dynamicvillages.*
 * are covered by assets/villagermod/lang and assets/dynamicvillages/lang.
 */
public final class LegacyModId {
    /** The mod id until 2026-09-25. */
    public static final String OLD = "villagermod";
    /** The mod id from 2026-09-25 until the TownTopia rename on 2026-10-22. */
    public static final String PREVIOUS = "dynamicvillages";
    /** Prefix of the names saved inside worlds; stays "dynamicvillages" whatever the mod id is. */
    public static final String SAVE_PREFIX = PREVIOUS;

    private static final List<String> OLD_NAMESPACES = List.of(PREVIOUS, OLD);

    private static final Logger LOGGER = LogUtils.getLogger();

    private LegacyModId() {}

    static void register(IEventBus modEventBus) {
        // LOWEST: our DeferredRegisters fill each registry first, so every entry gets an alias.
        modEventBus.addListener(EventPriority.LOWEST, LegacyModId::aliasOldNames);
        // HIGHEST: before anything opens the saved data during level load.
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LegacyModId::copyOldSavedData);
    }

    /** Entries renamed after release, new path -> old path; the old path under any namespace still loads. */
    private static final Map<String, String> RENAMED_FROM = Map.of(
            "builder_token", "architect_token"); // the Architect job became the Builder, 2026-10-17

    /** villagermod:npc and dynamicvillages:npc -> towntopia:npc, and so on for items, menus and tabs. */
    private static void aliasOldNames(RegisterEvent event) {
        Registry<?> registry = event.getRegistry();
        for (ResourceLocation id : List.copyOf(registry.keySet())) {
            if (!id.getNamespace().equals(DynamicVillages.MODID)) continue;
            // A set: NeoForge throws when the same alias is added twice.
            Set<ResourceLocation> oldIds = new LinkedHashSet<>();
            for (String namespace : OLD_NAMESPACES) {
                oldIds.add(ResourceLocation.fromNamespaceAndPath(namespace, id.getPath()));
            }
            String oldPath = RENAMED_FROM.get(id.getPath());
            if (oldPath != null) {
                oldIds.add(ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, oldPath));
                for (String namespace : OLD_NAMESPACES) {
                    oldIds.add(ResourceLocation.fromNamespaceAndPath(namespace, oldPath));
                }
            }
            oldIds.remove(id);
            for (ResourceLocation oldId : oldIds) registry.addAlias(oldId, id);
        }
    }

    /**
     * data/villagermod_registry.dat -> data/dynamicvillages_registry.dat, etc. Copied, not
     * moved, so the old file stays as a backup; never overwrites an existing new file.
     */
    private static void copyOldSavedData(ServerAboutToStartEvent event) {
        Path dataDir = event.getServer().getWorldPath(LevelResource.ROOT).resolve("data");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dataDir, OLD + "_*.dat")) {
            for (Path oldFile : files) {
                String name = oldFile.getFileName().toString();
                Path newFile = dataDir.resolve(SAVE_PREFIX + name.substring(OLD.length()));
                if (Files.exists(newFile)) continue;
                Files.copy(oldFile, newFile);
                LOGGER.info("Migrated saved data {} -> {}", name, newFile.getFileName());
            }
        } catch (NoSuchFileException noDataYet) {
            // Brand-new world.
        } catch (IOException e) {
            LOGGER.error("Could not migrate {} saved data in {}", OLD, dataDir, e);
        }
    }

    /**
     * config/dynamicvillages-common.toml (or villagermod-common.toml) -> config/towntopia-common.toml,
     * once, so the player's settings carry over. Call before registering that config.
     */
    static void copyOldConfig(ModConfig.Type type) {
        Path dir = FMLPaths.CONFIGDIR.get();
        String suffix = "-" + type.name().toLowerCase(Locale.ROOT) + ".toml";
        Path newFile = dir.resolve(DynamicVillages.MODID + suffix);
        if (Files.exists(newFile)) return;
        for (String namespace : OLD_NAMESPACES) {
            Path oldFile = dir.resolve(namespace + suffix);
            if (oldFile.equals(newFile) || !Files.exists(oldFile)) continue;
            try {
                Files.copy(oldFile, newFile);
                LOGGER.info("Migrated config {} -> {}", oldFile.getFileName(), newFile.getFileName());
            } catch (IOException e) {
                LOGGER.error("Could not migrate config {}", oldFile, e);
            }
            return;
        }
    }

    /** The mod's folder in the game directory before the rename ({@code <game>/dynamicvillages}); still read. */
    public static Path oldGameFolder(Path gameDir) {
        return gameDir.resolve(PREVIOUS);
    }

    /** Ids from our data packs (crop definitions) that old saves stored under an old namespace. */
    @Nullable
    public static ResourceLocation parse(String raw) {
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null || !OLD_NAMESPACES.contains(id.getNamespace())) return id;
        return ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, id.getPath());
    }

    /** Moves a value saved under an old key to its new key, in place. */
    public static void renameKey(CompoundTag tag, String oldKey, String newKey) {
        if (!tag.contains(oldKey)) return;
        if (!tag.contains(newKey)) tag.put(newKey, tag.get(oldKey));
        tag.remove(oldKey);
    }
}
