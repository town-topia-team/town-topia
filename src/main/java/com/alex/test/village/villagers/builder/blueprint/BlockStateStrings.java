package com.alex.test.village.villagers.builder.blueprint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Splits a block state written as text, the way Sponge schematics store their
 * palette: {@code minecraft:oak_stairs[facing=north,half=bottom]}.
 *
 * <p>Never throws: a pair without {@code =} is skipped and a missing closing
 * bracket is forgiven, because one bad property should not lose the block.
 * Kept free of Minecraft classes so the JUnit tests can run it.
 */
public final class BlockStateStrings {
    private BlockStateStrings() {
    }

    /** A block id and its properties in written order. */
    public record Parsed(String name, Map<String, String> properties) {
    }

    /** The parsed text, or null when it holds no block id at all. */
    public static Parsed parse(String text) {
        if (text == null) {
            return null;
        }
        String s = text.trim();
        int open = s.indexOf('[');
        String name = (open < 0 ? s : s.substring(0, open)).trim();
        if (name.isEmpty()) {
            return null;
        }
        if (open < 0) {
            return new Parsed(name, Collections.emptyMap());
        }
        int close = s.lastIndexOf(']');
        String inside = close > open ? s.substring(open + 1, close) : s.substring(open + 1);
        Map<String, String> props = new LinkedHashMap<>();
        for (String pair : inside.split(",")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                props.put(key, value);
            }
        }
        return new Parsed(name, Collections.unmodifiableMap(props));
    }

    /** Writes a state back as text; the inverse of {@link #parse}. */
    public static String format(String name, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : properties.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }
}
