package com.alex.test.village.villagers.lumberjack.settings;

import com.alex.test.DynamicVillages;
import com.alex.test.LegacyModId;
import com.alex.test.entity.NpcEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Small persistent settings wrapper backed by the villager's existing BrainData. */
public final class LumberjackSettings {

    // =========================================================================
    // Axe tier enum
    // =========================================================================

    public enum AxeTier {
        WOODEN("Wooden axe", Items.WOODEN_AXE),
        STONE("Stone axe", Items.STONE_AXE),
        IRON("Iron axe", Items.IRON_AXE),
        GOLDEN("Golden axe", Items.GOLDEN_AXE),
        DIAMOND("Diamond axe", Items.DIAMOND_AXE),
        NETHERITE("Netherite axe", Items.NETHERITE_AXE);

        public final String displayName;
        public final Item axeItem;
        /** ResourceLocation used as the setting identifier in the UI list. */
        public final ResourceLocation settingId;

        AxeTier(String displayName, Item axeItem) {
            this.displayName = displayName;
            this.axeItem = axeItem;
            this.settingId = ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "axe_" + name().toLowerCase(Locale.ROOT));
        }

        public static AxeTier bySettingId(ResourceLocation id) {
            for (AxeTier t : values()) if (t.settingId.equals(id)) return t;
            return null;
        }
    }

    /** Setting ID for the axe-crafting toggle (used in the UI list). */
    public static final ResourceLocation AXE_CRAFT_SETTING_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "axe_allow_craft");

    /** Setting ID for the sapling planting toggle (used in the UI list). */
    public static final ResourceLocation PLANT_SAPLINGS_SETTING_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "plant_saplings");

    /** Sentinel ID used to render a non-clickable section divider in the settings list. */
    public static final ResourceLocation SEPARATOR_ID =
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "separator");

    // =========================================================================

    private static final String SETTINGS       = "LumberjackSettings";
    private static final String WORKS          = "Works";
    private static final String DISABLED_TREES = "DisabledTrees";
    private static final String NO_REPLANT_TREES = "NoReplantTrees";
    private static final String ALLOW_AXE_CRAFT = "AllowAxeCraft";
    private static final String PLANT_SAPLINGS = "PlantSaplings";
    private static final String AXE_TIER_PREFIX = "AllowAxeTier_";

    private final NpcEntity villager;

    private LumberjackSettings(NpcEntity villager) {
        this.villager = villager;
    }

    public static LumberjackSettings of(NpcEntity villager) {
        return new LumberjackSettings(villager);
    }

    public boolean works() {
        CompoundTag tag = readTag();
        return !tag.contains(WORKS) || tag.getBoolean(WORKS);
    }

    public void setWorks(boolean enabled) {
        writeTag().putBoolean(WORKS, enabled);
    }

    public boolean isTreeEnabled(ResourceLocation treeType) {
        return !disabledTrees().contains(treeType);
    }

    public static final int TREE_SKIP = 0;
    public static final int TREE_CUT_AND_PLANT = 1;
    public static final int TREE_CUT_ONLY = 2;

    /** Existing villagers default to cutting and planting every enabled tree. */
    public int treeMode(ResourceLocation treeType) {
        if (disabledTrees().contains(treeType)) return TREE_SKIP;
        return noReplantTrees().contains(treeType) ? TREE_CUT_ONLY : TREE_CUT_AND_PLANT;
    }

    public void setTreeMode(ResourceLocation treeType, int mode) {
        if (mode < TREE_SKIP || mode > TREE_CUT_ONLY) return;
        Set<ResourceLocation> disabled = disabledTrees();
        Set<ResourceLocation> noReplant = noReplantTrees();
        if (mode == TREE_SKIP) disabled.add(treeType);
        else disabled.remove(treeType);
        if (mode == TREE_CUT_ONLY) noReplant.add(treeType);
        else noReplant.remove(treeType);

        writeTag().put(DISABLED_TREES, toList(disabled));
        writeTag().put(NO_REPLANT_TREES, toList(noReplant));
    }

    private static ListTag toList(Set<ResourceLocation> types) {
        ListTag list = new ListTag();
        types.stream()
                .map(ResourceLocation::toString)
                .sorted()
                .map(StringTag::valueOf)
                .forEach(list::add);
        return list;
    }

    private Set<ResourceLocation> disabledTrees() {
        return readTreeSet(DISABLED_TREES);
    }

    private Set<ResourceLocation> noReplantTrees() {
        return readTreeSet(NO_REPLANT_TREES);
    }

    private Set<ResourceLocation> readTreeSet(String key) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        ListTag list = readTag().getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = LegacyModId.parse(list.getString(i));
            if (id != null) result.add(id);
        }
        return result;
    }

    /** Whether the villager replants matching saplings after chopping. Default: true. */
    public boolean plantSaplings() {
        CompoundTag tag = readTag();
        return !tag.contains(PLANT_SAPLINGS) || tag.getBoolean(PLANT_SAPLINGS);
    }

    public void setPlantSaplings(boolean enabled) {
        writeTag().putBoolean(PLANT_SAPLINGS, enabled);
    }

    /** The global planting switch takes precedence over each tree's mode. */
    public boolean shouldReplant(ResourceLocation treeType) {
        return plantSaplings() && treeMode(treeType) == TREE_CUT_AND_PLANT;
    }

    // =========================================================================
    // Axe settings
    // =========================================================================

    /** Whether the villager may craft an axe when none is available in storage. Default: true. */
    public boolean allowAxeCrafting() {
        CompoundTag tag = readTag();
        return !tag.contains(ALLOW_AXE_CRAFT) || tag.getBoolean(ALLOW_AXE_CRAFT);
    }

    public void setAllowAxeCrafting(boolean allow) {
        writeTag().putBoolean(ALLOW_AXE_CRAFT, allow);
    }

    /** Whether the villager may use/take axes of the given tier. Default: true for all tiers. */
    public boolean isAxeTierAllowed(AxeTier tier) {
        CompoundTag tag = readTag();
        String key = AXE_TIER_PREFIX + tier.name();
        return !tag.contains(key) || tag.getBoolean(key);
    }

    public void setAxeTierAllowed(AxeTier tier, boolean allowed) {
        writeTag().putBoolean(AXE_TIER_PREFIX + tier.name(), allowed);
    }

    /** Returns the set of axe Items that this lumberjack is permitted to use. */
    public Set<Item> getAllowedAxes() {
        Set<Item> allowed = new LinkedHashSet<>();
        for (AxeTier tier : AxeTier.values()) {
            if (isAxeTierAllowed(tier)) allowed.add(tier.axeItem);
        }
        return allowed;
    }

    // =========================================================================

    private CompoundTag readTag() {
        return villager.getBrainData().getCompound(SETTINGS);
    }

    private CompoundTag writeTag() {
        CompoundTag brainData = villager.getBrainData();
        if (!brainData.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            brainData.put(SETTINGS, new CompoundTag());
        }
        return brainData.getCompound(SETTINGS);
    }
}
