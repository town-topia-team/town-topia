package com.alex.test.village.villagers.farmer.settings;

import com.alex.test.DynamicVillages;
import com.alex.test.LegacyModId;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.tools.ToolSelectionHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Farmer-owned persistent settings backed by the NPC's saved brain data. */
public final class FarmerSettings {

    public enum HoeTier {
        WOODEN("Wooden hoe", Items.WOODEN_HOE),
        GOLDEN("Golden hoe", Items.GOLDEN_HOE),
        STONE("Stone hoe", Items.STONE_HOE),
        IRON("Iron hoe", Items.IRON_HOE),
        DIAMOND("Diamond hoe", Items.DIAMOND_HOE),
        NETHERITE("Netherite hoe", Items.NETHERITE_HOE);

        /**
         * Explicit worst-to-best display order for the Equipment tab. Deliberately
         * not {@link #values()} (enum declaration order), which historically follows
         * vanilla's item-tag order rather than a power ranking, so a future
         * reordering of the enum constants cannot silently reorder the UI. Mirrors
         * {@link HoeTierRank}'s Minecraft-free ordering exactly (name-for-name),
         * which is what {@code HoeTierOrderTest} pins down without needing
         * Minecraft on the test classpath.
         */
        public static final List<HoeTier> DISPLAY_ORDER =
                List.of(WOODEN, GOLDEN, STONE, IRON, DIAMOND, NETHERITE);

        public final String displayName;
        public final Item hoeItem;
        public final ResourceLocation settingId;

        HoeTier(String displayName, Item hoeItem) {
            this.displayName = displayName;
            this.hoeItem = hoeItem;
            this.settingId = ResourceLocation.fromNamespaceAndPath(
                    DynamicVillages.MODID, "farmer_hoe_" + name().toLowerCase(Locale.ROOT));
        }

        public static HoeTier bySettingId(ResourceLocation id) {
            for (HoeTier tier : values()) if (tier.settingId.equals(id)) return tier;
            return null;
        }

        /** Material-tier rank shared with runtime tool selection; higher is better. */
        public int rank() {
            return ToolSelectionHelper.rank(hoeItem);
        }
    }

    /** Axes a farmer may use; only for harvesting melons and pumpkins. Same pattern as {@link HoeTier}. */
    public enum AxeTier {
        WOODEN("Wooden axe", Items.WOODEN_AXE),
        GOLDEN("Golden axe", Items.GOLDEN_AXE),
        STONE("Stone axe", Items.STONE_AXE),
        IRON("Iron axe", Items.IRON_AXE),
        DIAMOND("Diamond axe", Items.DIAMOND_AXE),
        NETHERITE("Netherite axe", Items.NETHERITE_AXE);

        /** Worst-to-best display order for the Equipment tab, after the hoes. */
        public static final List<AxeTier> DISPLAY_ORDER =
                List.of(WOODEN, GOLDEN, STONE, IRON, DIAMOND, NETHERITE);

        public final String displayName;
        public final Item axeItem;
        public final ResourceLocation settingId;

        AxeTier(String displayName, Item axeItem) {
            this.displayName = displayName;
            this.axeItem = axeItem;
            this.settingId = ResourceLocation.fromNamespaceAndPath(
                    DynamicVillages.MODID, "farmer_axe_" + name().toLowerCase(Locale.ROOT));
        }

        public static AxeTier bySettingId(ResourceLocation id) {
            for (AxeTier tier : values()) if (tier.settingId.equals(id)) return tier;
            return null;
        }
    }

    public static final ResourceLocation WORKS_SETTING_ID = id("farmer_works");
    public static final ResourceLocation HOE_CRAFT_SETTING_ID = id("farmer_hoe_allow_craft");
    public static final ResourceLocation SHEARS_SETTING_ID = id("farmer_shears");
    public static final ResourceLocation TURN_OFF_PLANTING_SETTING_ID = id("farmer_turn_off_planting");
    public static final ResourceLocation USE_BONE_MEAL_SETTING_ID = id("farmer_use_bone_meal");
    public static final ResourceLocation HARVESTING_SETTING_ID = id("farmer_harvesting");
    public static final ResourceLocation TILLING_SETTING_ID = id("farmer_tilling");
    public static final ResourceLocation COMPOST_SETTING_ID = id("farmer_compost");

    public static final int PLANT_LEAVE = 0;
    public static final int PLANT_REPLANT = 1;
    public static final int PLANT_CUT_ONLY = 2;

    private static final String SETTINGS = "FarmerSettings";
    private static final String WORKS = "Works";
    private static final String DISABLED_PLANTS = "DisabledPlants";
    private static final String NO_REPLANT_PLANTS = "NoReplantPlants";
    private static final String TURN_OFF_PLANTING = "TurnOffPlanting";
    private static final String ALLOW_HOE_CRAFT = "AllowHoeCraft";
    private static final String ALLOW_SHEARS = "AllowShears";
    private static final String USE_BONE_MEAL = "UseBoneMeal";
    private static final String TURN_OFF_HARVESTING = "TurnOffHarvesting";
    private static final String TILLING = "Tilling";
    /** Saved by the old "Bone meal only" switch; read once and converted, see {@link #migrate}. */
    private static final String LEGACY_BONE_MEAL_ONLY = "BoneMealOnly";
    private static final String COMPOST = "Compost";
    private static final String HOE_TIER_PREFIX = "AllowHoeTier_";
    private static final String AXE_TIER_PREFIX = "AllowAxeTier_";
    private static final String LEGACY_WORKS = "work_enabled";
    private static final String LEGACY_ALLOWED_CROPS = "allowed_crops";

    private final NpcEntity farmer;

    private FarmerSettings(NpcEntity farmer) {
        this.farmer = farmer;
    }

    public static FarmerSettings of(NpcEntity farmer) {
        return new FarmerSettings(farmer);
    }

    public boolean works() {
        CompoundTag tag = readTag();
        if (tag.contains(WORKS)) return tag.getBoolean(WORKS);
        return !tag.contains(LEGACY_WORKS) || tag.getBoolean(LEGACY_WORKS);
    }

    public void setWorks(boolean enabled) {
        writeTag().putBoolean(WORKS, enabled);
    }

    public boolean isPlantEnabled(Item plantingItem) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(plantingItem);
        return id != null && !disabledPlants().contains(id);
    }

    public int plantMode(Item plantingItem) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(plantingItem);
        if (id == null || disabledPlants().contains(id)) return PLANT_LEAVE;
        // Glow berries and the like are never planted: Harvest only at most.
        if (isHarvestOnly(plantingItem)) return PLANT_CUT_ONLY;
        return noReplantPlants().contains(id) ? PLANT_CUT_ONLY : PLANT_REPLANT;
    }

    /** A crop farmers only harvest and never plant (glow berries). */
    public static boolean isHarvestOnly(Item plantingItem) {
        var definition = com.alex.test.village.villagers.farmer.crops.CropDefinitionRegistry.byPlantingItem(plantingItem);
        return definition != null && definition.planting().harvestOnly();
    }

    public void setPlantMode(Item plantingItem, int mode) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(plantingItem);
        if (id == null || !FarmerPlantingResources.isSupported(plantingItem)
                || mode < PLANT_LEAVE || mode > PLANT_CUT_ONLY
                || mode == PLANT_REPLANT && isHarvestOnly(plantingItem)) return;
        Set<ResourceLocation> disabled = disabledPlants();
        Set<ResourceLocation> noReplant = noReplantPlants();
        if (mode == PLANT_LEAVE) disabled.add(id);
        else disabled.remove(id);
        if (mode == PLANT_CUT_ONLY) noReplant.add(id);
        else noReplant.remove(id);

        writeTag().put(DISABLED_PLANTS, toList(disabled));
        writeTag().put(NO_REPLANT_PLANTS, toList(noReplant));
    }

    private static ListTag toList(Set<ResourceLocation> ids) {
        ListTag list = new ListTag();
        ids.stream().map(ResourceLocation::toString).sorted()
                .map(StringTag::valueOf).forEach(list::add);
        return list;
    }

    public boolean turnOffPlanting() {
        return readTag().getBoolean(TURN_OFF_PLANTING);
    }

    public void setTurnOffPlanting(boolean disabled) {
        writeTag().putBoolean(TURN_OFF_PLANTING, disabled);
    }

    public boolean shouldPlant(Item plantingItem) {
        return !turnOffPlanting() && plantMode(plantingItem) == PLANT_REPLANT;
    }

    /**
     * "Wooden Tool Crafting": wooden hoes and wooden axes. Kept under
     * the old hoe-only id and NBT key so saved settings carry over.
     */
    public boolean allowHoeCrafting() {
        CompoundTag tag = readTag();
        return !tag.contains(ALLOW_HOE_CRAFT) || tag.getBoolean(ALLOW_HOE_CRAFT);
    }

    public void setAllowHoeCrafting(boolean enabled) {
        writeTag().putBoolean(ALLOW_HOE_CRAFT, enabled);
    }

    public boolean allowShears() {
        CompoundTag tag = readTag();
        return !tag.contains(ALLOW_SHEARS) || tag.getBoolean(ALLOW_SHEARS);
    }

    public void setAllowShears(boolean enabled) {
        writeTag().putBoolean(ALLOW_SHEARS, enabled);
    }

    /** "Fertilizing": uses bone meal on crops now and then, more often while the village is short of food. */
    public boolean useBoneMeal() {
        CompoundTag tag = readTag();
        return !tag.contains(USE_BONE_MEAL) || tag.getBoolean(USE_BONE_MEAL);
    }

    public void setUseBoneMeal(boolean enabled) {
        writeTag().putBoolean(USE_BONE_MEAL, enabled);
    }

    /** Saved the way {@link #turnOffPlanting} is: missing means harvesting is on. */
    public boolean turnOffHarvesting() {
        return readTag().getBoolean(TURN_OFF_HARVESTING);
    }

    public void setTurnOffHarvesting(boolean disabled) {
        writeTag().putBoolean(TURN_OFF_HARVESTING, disabled);
    }

    /**
     * "Tilling": turns soil into farmland with the hoe. Off, the farmer plants
     * only on soil that is already farmland and leaves dirt plots alone (it
     * still keeps a hoe, which paces its harvesting and planting). A farmer
     * saved before this switch starts with its Planting value, see {@link #migrate}.
     */
    public boolean tilling() {
        CompoundTag tag = readTag();
        return !tag.contains(TILLING) || tag.getBoolean(TILLING);
    }

    public void setTilling(boolean enabled) {
        writeTag().putBoolean(TILLING, enabled);
    }

    /**
     * Planting off and Tilling on: the farmer tills soil for the village's
     * planting farmers instead of for its own seeds (the tiller).
     */
    public boolean tillsForVillage() {
        return turnOffPlanting() && tilling();
    }

    /**
     * No field work at all: no tilling, planting or harvesting, and no hoe. What
     * is left is bone meal (Fertilizing) and composting, when those are on. Not
     * a switch of its own: it is Planting, Harvesting and Tilling all off.
     */
    public boolean boneMealOnly() {
        return turnOffPlanting() && turnOffHarvesting() && !tilling();
    }

    /** Turns surplus seeds, saplings and other plant scraps from storage into bone meal. */
    public boolean compost() {
        CompoundTag tag = readTag();
        return !tag.contains(COMPOST) || tag.getBoolean(COMPOST);
    }

    public void setCompost(boolean enabled) {
        writeTag().putBoolean(COMPOST, enabled);
    }

    public boolean isHoeTierAllowed(HoeTier tier) {
        CompoundTag tag = readTag();
        String key = HOE_TIER_PREFIX + tier.name();
        return !tag.contains(key) || tag.getBoolean(key);
    }

    public void setHoeTierAllowed(HoeTier tier, boolean enabled) {
        writeTag().putBoolean(HOE_TIER_PREFIX + tier.name(), enabled);
    }

    public Set<Item> getAllowedHoes() {
        Set<Item> allowed = new LinkedHashSet<>();
        for (HoeTier tier : HoeTier.values()) {
            if (isHoeTierAllowed(tier)) allowed.add(tier.hoeItem);
        }
        return allowed;
    }

    public boolean isAxeTierAllowed(AxeTier tier) {
        CompoundTag tag = readTag();
        String key = AXE_TIER_PREFIX + tier.name();
        return !tag.contains(key) || tag.getBoolean(key);
    }

    public void setAxeTierAllowed(AxeTier tier, boolean enabled) {
        writeTag().putBoolean(AXE_TIER_PREFIX + tier.name(), enabled);
    }

    public Set<Item> getAllowedAxes() {
        Set<Item> allowed = new LinkedHashSet<>();
        for (AxeTier tier : AxeTier.values()) {
            if (isAxeTierAllowed(tier)) allowed.add(tier.axeItem);
        }
        return allowed;
    }

    private Set<ResourceLocation> disabledPlants() {
        Set<ResourceLocation> disabled = new LinkedHashSet<>();
        CompoundTag settings = readTag();
        ListTag list = settings.getList(DISABLED_PLANTS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = LegacyModId.parse(list.getString(i));
            if (id != null) disabled.add(id);
        }
        if (!settings.contains(DISABLED_PLANTS)
                && settings.contains(LEGACY_ALLOWED_CROPS, Tag.TAG_COMPOUND)) {
            CompoundTag legacy = settings.getCompound(LEGACY_ALLOWED_CROPS);
            for (String key : legacy.getAllKeys()) {
                ResourceLocation id = LegacyModId.parse(key);
                if (id != null && !legacy.getBoolean(key)) disabled.add(id);
            }
        }
        return disabled;
    }

    private Set<ResourceLocation> noReplantPlants() {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        ListTag list = readTag().getList(NO_REPLANT_PLANTS, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = LegacyModId.parse(list.getString(i));
            if (id != null) result.add(id);
        }
        return result;
    }

    private CompoundTag readTag() {
        CompoundTag tag = farmer.getBrainData().getCompound(SETTINGS);
        migrate(tag);
        return tag;
    }

    private CompoundTag writeTag() {
        CompoundTag brainData = farmer.getBrainData();
        if (!brainData.contains(SETTINGS, Tag.TAG_COMPOUND)) {
            brainData.put(SETTINGS, new CompoundTag());
        }
        CompoundTag tag = brainData.getCompound(SETTINGS);
        migrate(tag);
        return tag;
    }

    /**
     * Old saves: a farmer set to "Bone meal only" becomes Planting off, Harvesting
     * off and Fertilizing on, which behaves the same, and the old key is dropped.
     * A farmer saved before the Tilling switch gets its Planting value: it only
     * ever tilled for its own planting, so Planting off never tilled. Written
     * once, so Tilling stops following Planting from then on.
     * Every read and write goes through here, so it happens once, on first use.
     */
    private static void migrate(CompoundTag tag) {
        if (tag.contains(LEGACY_BONE_MEAL_ONLY)) {
            if (tag.getBoolean(LEGACY_BONE_MEAL_ONLY)) {
                tag.putBoolean(TURN_OFF_PLANTING, true);
                tag.putBoolean(TURN_OFF_HARVESTING, true);
                tag.putBoolean(USE_BONE_MEAL, true);
            }
            tag.remove(LEGACY_BONE_MEAL_ONLY);
        }
        if (!tag.contains(TILLING)) tag.putBoolean(TILLING, !tag.getBoolean(TURN_OFF_PLANTING));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, path);
    }
}
