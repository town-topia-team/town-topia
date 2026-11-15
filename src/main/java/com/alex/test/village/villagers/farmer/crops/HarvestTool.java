package com.alex.test.village.villagers.farmer.crops;

/**
 * The tool a farmer must carry to harvest a crop family, from the crop's
 * {@code "harvest_tool"} JSON field. Farmers plant and bone meal without it;
 * only the harvest waits for the tool.
 */
public enum HarvestTool {
    NONE,
    SHEARS,
    AXE;

    /** Parses the JSON value; absent means {@link #NONE}. */
    public static HarvestTool parse(String raw) {
        return switch (raw) {
            case "shears" -> SHEARS;
            case "axe" -> AXE;
            case "none", "" -> NONE;
            default -> throw new IllegalArgumentException("Unknown harvest_tool: " + raw);
        };
    }
}
