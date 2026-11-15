package com.alex.test.village.villagers.farmer.crops;

/**
 * Pure discriminator-string handling for the {@code "type"} field inside a
 * crop definition's {@code maturity}/{@code planting}/{@code harvest} JSON
 * objects (e.g. {@code "towntopia:age_property"} or {@code "age_property"}).
 * Deliberately free of any Minecraft/Gson import so it can be unit tested
 * without those on the classpath; {@link CropDefinitionParser} delegates to
 * it rather than duplicating the logic.
 */
public final class CropRuleType {

    private CropRuleType() {}

    /** Strips a leading {@code "namespace:"}, if present, leaving just the path. */
    public static String path(String type) {
        int colon = type.indexOf(':');
        return colon < 0 ? type : type.substring(colon + 1);
    }
}
