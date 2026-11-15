package com.alex.test.village.villagers.lumberjack.data;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Discovers complete log/leaves/sapling families from loaded block registries. */
public final class TreeTypeRegistry {

    public record TreeType(
            ResourceLocation id, Set<Block> logs, Set<Block> leaves, Block sapling) {

        public String displayName() {
            StringBuilder result = new StringBuilder();
            for (String word : id.getPath().split("_")) {
                if (!result.isEmpty()) result.append(' ');
                if (!word.isEmpty()) {
                    result.append(Character.toUpperCase(word.charAt(0)))
                            .append(word.substring(1).toLowerCase(Locale.ROOT));
                }
            }
            if (!id.getNamespace().equals("minecraft")) {
                result.append(" (").append(id.getNamespace()).append(')');
            }
            return result.toString();
        }
    }

    private static Catalog catalog;

    private TreeTypeRegistry() {}

    public static List<TreeType> all() {
        return catalog().types;
    }

    @Nullable
    public static TreeType byId(ResourceLocation id) {
        return catalog().byId.get(id);
    }

    public static List<TreeType> candidatesForLog(Block log) {
        return catalog().byLog.getOrDefault(log, List.of());
    }

    public static boolean sameLogFamily(Block first, Block second) {
        return candidatesForLog(first).stream().anyMatch(type -> type.logs().contains(second));
    }

    /**
     * The log a felled tree of this kind gives: named "_log" or "_stem" when
     * there is one. {@code logs()} also holds the stripped logs and the
     * bark-on-every-side "_wood"/"_hyphae" blocks, which no tree is made of.
     */
    @Nullable
    public static Block plainLog(TreeType type) {
        Block fallback = null;
        for (Block block : type.logs()) {
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            if (block.asItem() == net.minecraft.world.item.Items.AIR || path.startsWith("stripped_")) continue;
            if (path.endsWith("_log") || path.endsWith("_stem")) return block;
            if (fallback == null && !path.endsWith("_wood") && !path.endsWith("_hyphae")) fallback = block;
        }
        return fallback;
    }

    private static Catalog catalog() {
        if (catalog == null) catalog = buildCatalog();
        return catalog;
    }

    private static Catalog buildCatalog() {
        Map<ResourceLocation, Builder> builders = new LinkedHashMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            String family = familyName(blockId.getPath());
            if (family == null) continue;

            ResourceLocation familyId =
                    ResourceLocation.fromNamespaceAndPath(blockId.getNamespace(), family);
            Builder builder = builders.computeIfAbsent(familyId, ignored -> new Builder());
            if (block.defaultBlockState().is(BlockTags.LOGS)) builder.logs.add(block);
            if (block.defaultBlockState().is(BlockTags.LEAVES)) builder.leaves.add(block);
            if (block.defaultBlockState().is(BlockTags.SAPLINGS)
                    || blockId.getPath().endsWith("_sapling")
                    || blockId.getPath().endsWith("_propagule")) {
                builder.sapling = block;
            }
        }

        Map<ResourceLocation, TreeType> types = new LinkedHashMap<>();
        for (Map.Entry<ResourceLocation, Builder> entry : builders.entrySet()) {
            Builder builder = entry.getValue();
            if (!builder.logs.isEmpty() && !builder.leaves.isEmpty() && builder.sapling != null) {
                types.put(entry.getKey(), builder.build(entry.getKey()));
            }
        }

        TreeType oak = types.get(ResourceLocation.withDefaultNamespace("oak"));
        if (oak != null) {
            ResourceLocation id = ResourceLocation.withDefaultNamespace("azalea");
            types.put(id, new TreeType(
                    id, oak.logs(),
                    Set.of(Blocks.AZALEA_LEAVES, Blocks.FLOWERING_AZALEA_LEAVES),
                    Blocks.AZALEA));
        }

        List<TreeType> sorted = types.values().stream()
                .sorted(Comparator.comparing(type -> type.id().toString()))
                .toList();
        Map<Block, List<TreeType>> byLog = new HashMap<>();
        for (TreeType type : sorted) {
            for (Block log : type.logs()) {
                byLog.computeIfAbsent(log, ignored -> new ArrayList<>()).add(type);
            }
        }
        byLog.replaceAll((ignored, values) -> List.copyOf(values));
        return new Catalog(sorted, Map.copyOf(types), Map.copyOf(byLog));
    }

    @Nullable
    private static String familyName(String path) {
        String normalized = path.startsWith("stripped_")
                ? path.substring("stripped_".length())
                : path;
        for (String suffix : List.of(
                "_log", "_wood", "_stem", "_hyphae",
                "_leaves", "_leaf", "_sapling", "_propagule")) {
            if (normalized.endsWith(suffix) && normalized.length() > suffix.length()) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        return null;
    }

    private static final class Builder {
        private final Set<Block> logs = new LinkedHashSet<>();
        private final Set<Block> leaves = new LinkedHashSet<>();
        private Block sapling;

        private TreeType build(ResourceLocation id) {
            return new TreeType(id, Set.copyOf(logs), Set.copyOf(leaves), sapling);
        }
    }

    private record Catalog(
            List<TreeType> types,
            Map<ResourceLocation, TreeType> byId,
            Map<Block, List<TreeType>> byLog) {}
}
