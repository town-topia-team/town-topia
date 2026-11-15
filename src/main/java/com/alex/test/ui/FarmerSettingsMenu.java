package com.alex.test.ui;

import com.alex.test.Tr;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.farmer.FarmerManager;
import com.alex.test.village.villagers.farmer.crops.HarvestTool;
import com.alex.test.village.villagers.farmer.settings.FarmerPlantingResources;
import com.alex.test.village.villagers.farmer.settings.FarmerSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Server menu and authoritative opening snapshot for Farmer settings. */
public final class FarmerSettingsMenu extends AbstractContainerMenu implements NpcUiMenu {

    public record Setting(ResourceLocation id, String name, int mode) {
        public boolean enabled() { return mode != 0; }
    }

    /**
     * What a Plants row needs besides its mode: the tool its harvest takes
     * (a {@link HarvestTool} ordinal), whether the farmer carries one now,
     * and whether the crop is only ever harvested (glow berries).
     */
    public record PlantInfo(int tool, boolean hasTool, boolean harvestOnly) {
        public HarvestTool harvestTool() {
            HarvestTool[] tools = HarvestTool.values();
            return tool >= 0 && tool < tools.length ? tools[tool] : HarvestTool.NONE;
        }
    }

    private final int villagerId;
    @Nullable private final NpcEntity villager;
    private boolean works;
    private final List<Setting> plants;
    private final List<PlantInfo> plantInfo;
    private final List<Setting> equipment;
    private final List<Setting> behavior;

    public FarmerSettingsMenu(int id, Inventory inventory, NpcEntity villager) {
        super(DynamicVillages.FARMER_SETTINGS_MENU.get(), id);
        this.villagerId = villager.getId();
        this.villager = villager;
        FarmerSettings settings = FarmerSettings.of(villager);
        works = settings.works();
        plants = plantSnapshot(settings);
        plantInfo = plantInfoSnapshot(villager, settings);
        equipment = equipmentSnapshot(settings);
        behavior = behaviorSnapshot(settings);
    }

    public FarmerSettingsMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.FARMER_SETTINGS_MENU.get(), id);
        villagerId = buffer.readVarInt();
        villager = null;
        works = buffer.readBoolean();
        plants = readSettings(buffer);
        plantInfo = readPlantInfo(buffer);
        equipment = readSettings(buffer);
        behavior = readSettings(buffer);
    }

    public static void writeOpeningData(RegistryFriendlyByteBuf buffer, NpcEntity villager) {
        FarmerSettings settings = FarmerSettings.of(villager);
        buffer.writeVarInt(villager.getId());
        buffer.writeBoolean(settings.works());
        writeSettings(buffer, plantSnapshot(settings));
        writePlantInfo(buffer, plantInfoSnapshot(villager, settings));
        writeSettings(buffer, equipmentSnapshot(settings));
        writeSettings(buffer, behaviorSnapshot(settings));
    }

    private static List<Setting> plantSnapshot(FarmerSettings settings) {
        List<Setting> rows = new ArrayList<>();
        for (Item item : FarmerPlantingResources.allSupported()) {
            rows.add(new Setting(BuiltInRegistries.ITEM.getKey(item),
                    item.getDescription().getString(), settings.plantMode(item)));
        }
        return rows;
    }

    /**
     * Equipment-tab row ids, worst-to-best. Exposed statically (independent
     * of a live {@link FarmerSettings}) so placement/order can be unit
     * tested without a live {@code NpcEntity}.
     */
    public static List<ResourceLocation> equipmentSettingIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        ids.add(FarmerSettings.SHEARS_SETTING_ID);
        for (FarmerSettings.HoeTier tier : FarmerSettings.HoeTier.DISPLAY_ORDER) ids.add(tier.settingId);
        for (FarmerSettings.AxeTier tier : FarmerSettings.AxeTier.DISPLAY_ORDER) ids.add(tier.settingId);
        return ids;
    }

    /**
     * Behaviour-tab setting ids in on-screen order, not counting Working or the
     * headings; {@code FarmerSettingsScreen}'s layout points into this list.
     */
    public static List<ResourceLocation> behaviorSettingIds() {
        return List.of(FarmerSettings.TURN_OFF_PLANTING_SETTING_ID,
                FarmerSettings.HARVESTING_SETTING_ID,
                FarmerSettings.TILLING_SETTING_ID,
                FarmerSettings.USE_BONE_MEAL_SETTING_ID,
                FarmerSettings.COMPOST_SETTING_ID,
                FarmerSettings.HOE_CRAFT_SETTING_ID);
    }

    private static List<Setting> equipmentSnapshot(FarmerSettings settings) {
        List<Setting> rows = new ArrayList<>();
        rows.add(new Setting(FarmerSettings.SHEARS_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_shears", "Shears"),
                settings.allowShears() ? 1 : 0));
        for (FarmerSettings.HoeTier tier : FarmerSettings.HoeTier.DISPLAY_ORDER) {
            rows.add(new Setting(tier.settingId, SettingsLabels.titleCase(tier.displayName),
                    settings.isHoeTierAllowed(tier) ? 1 : 0));
        }
        for (FarmerSettings.AxeTier tier : FarmerSettings.AxeTier.DISPLAY_ORDER) {
            rows.add(new Setting(tier.settingId, SettingsLabels.titleCase(tier.displayName),
                    settings.isAxeTierAllowed(tier) ? 1 : 0));
        }
        return rows;
    }

    private static List<Setting> behaviorSnapshot(FarmerSettings settings) {
        List<Setting> rows = new ArrayList<>();
        // Planting and Harvesting are shown flipped so ON means "does it"; the saved flags stay "turn off".
        rows.add(new Setting(FarmerSettings.TURN_OFF_PLANTING_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_turn_off_planting", "Planting"), settings.turnOffPlanting() ? 0 : 1));
        rows.add(new Setting(FarmerSettings.HARVESTING_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_harvesting", "Harvesting"), settings.turnOffHarvesting() ? 0 : 1));
        rows.add(new Setting(FarmerSettings.TILLING_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_tilling", "Tilling"), settings.tilling() ? 1 : 0));
        rows.add(new Setting(FarmerSettings.USE_BONE_MEAL_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_use_bone_meal", "Fertilizing"), settings.useBoneMeal() ? 1 : 0));
        rows.add(new Setting(FarmerSettings.COMPOST_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_compost", "Composting"), settings.compost() ? 1 : 0));
        rows.add(new Setting(FarmerSettings.HOE_CRAFT_SETTING_ID,
                Tr.s("setting.dynamicvillages.farmer_hoe_allow_craft", "Wooden Tool Crafting"), settings.allowHoeCrafting() ? 1 : 0));
        return rows;
    }

    /** One entry per {@link #plantSnapshot} row, in the same order. */
    private static List<PlantInfo> plantInfoSnapshot(NpcEntity villager, FarmerSettings settings) {
        List<PlantInfo> rows = new ArrayList<>();
        for (Item item : FarmerPlantingResources.allSupported()) {
            HarvestTool tool = FarmerManager.harvestToolFor(item);
            rows.add(new PlantInfo(tool.ordinal(),
                    FarmerManager.hasHarvestTool(villager.getInventory(), settings, tool),
                    FarmerSettings.isHarvestOnly(item)));
        }
        return rows;
    }

    private static void writePlantInfo(RegistryFriendlyByteBuf buffer, List<PlantInfo> rows) {
        buffer.writeVarInt(rows.size());
        for (PlantInfo row : rows) {
            buffer.writeVarInt(row.tool());
            buffer.writeBoolean(row.hasTool());
            buffer.writeBoolean(row.harvestOnly());
        }
    }

    private static List<PlantInfo> readPlantInfo(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<PlantInfo> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new PlantInfo(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean()));
        }
        return rows;
    }

    private static void writeSettings(RegistryFriendlyByteBuf buffer, List<Setting> rows) {
        buffer.writeVarInt(rows.size());
        for (Setting row : rows) {
            buffer.writeResourceLocation(row.id());
            buffer.writeUtf(row.name(), 128);
            buffer.writeVarInt(row.mode());
        }
    }

    private static List<Setting> readSettings(RegistryFriendlyByteBuf buffer) {
        int count = buffer.readVarInt();
        List<Setting> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(new Setting(buffer.readResourceLocation(), buffer.readUtf(128),
                    buffer.readVarInt()));
        }
        return rows;
    }

    public int villagerId() { return villagerId; }
    public boolean works() { return works; }
    public List<Setting> plants() { return plants; }
    public List<PlantInfo> plantInfo() { return plantInfo; }
    public List<Setting> equipment() { return equipment; }
    public List<Setting> behavior() { return behavior; }

    public void setWorksClient(boolean enabled) { works = enabled; }

    public void setClient(ResourceLocation id, int mode) {
        if (replace(plants, id, mode)) return;
        if (replace(equipment, id, mode)) return;
        replace(behavior, id, mode);
    }

    private static boolean replace(List<Setting> rows, ResourceLocation id, int mode) {
        for (int i = 0; i < rows.size(); i++) {
            Setting row = rows.get(i);
            if (row.id().equals(id)) {
                rows.set(i, new Setting(id, row.name(), mode));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null || NpcUiMenu.villagerStillThere(player, villager);
    }

    @Override
    public boolean isForNpc(NpcEntity npc) {
        return villager == npc;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
