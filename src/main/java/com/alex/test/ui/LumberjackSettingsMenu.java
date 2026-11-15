package com.alex.test.ui;

import com.alex.test.Tr;
import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.lumberjack.AxeUtils;
import com.alex.test.village.villagers.lumberjack.data.TreeTypeRegistry;
import com.alex.test.village.villagers.lumberjack.settings.LumberjackSettings;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Server menu plus opening snapshot for one Lumberjack settings screen. */
public final class LumberjackSettingsMenu extends AbstractContainerMenu implements NpcUiMenu {

    public record TreeSetting(ResourceLocation id, String name, int mode) {
        public boolean enabled() { return mode != 0; }
    }

    private static final int BEHAVIOR_SETTINGS_COUNT = 2;
    private static final int EQUIPMENT_SETTINGS_COUNT = LumberjackSettings.AxeTier.values().length;

    private final int villagerId;
    @Nullable private final NpcEntity villager;
    private boolean works;
    private final List<TreeSetting> trees;
    /** The axe it chops with when the screen opened; AIR for none. For the big-tree hover text. */
    private final Item axe;

    public LumberjackSettingsMenu(int id, Inventory inventory, NpcEntity villager) {
        super(DynamicVillages.LUMBERJACK_SETTINGS_MENU.get(), id);
        this.villagerId = villager.getId();
        this.villager = villager;
        LumberjackSettings settings = LumberjackSettings.of(villager);
        this.works = settings.works();
        this.trees = snapshot(settings);
        this.axe = choppingAxe(villager, settings);
    }

    public LumberjackSettingsMenu(
            int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.LUMBERJACK_SETTINGS_MENU.get(), id);
        this.villagerId = buffer.readVarInt();
        this.villager = null;
        this.works = buffer.readBoolean();
        int count = buffer.readVarInt();
        this.trees = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            trees.add(new TreeSetting(
                    buffer.readResourceLocation(),
                    buffer.readUtf(128),
                    buffer.readVarInt()));
        }
        this.axe = BuiltInRegistries.ITEM.byId(buffer.readVarInt());
    }

    public static void writeOpeningData(
            RegistryFriendlyByteBuf buffer, NpcEntity villager) {
        LumberjackSettings settings = LumberjackSettings.of(villager);
        List<TreeSetting> trees = snapshot(settings);
        buffer.writeVarInt(villager.getId());
        buffer.writeBoolean(settings.works());
        buffer.writeVarInt(trees.size());
        for (TreeSetting tree : trees) {
            buffer.writeResourceLocation(tree.id());
            buffer.writeUtf(tree.name(), 128);
            buffer.writeVarInt(tree.mode());
        }
        buffer.writeVarInt(BuiltInRegistries.ITEM.getId(choppingAxe(villager, settings)));
    }

    /** The axe it chops with ({@link AxeUtils#findChoppingAxeSlot}); AIR when it has none. */
    private static Item choppingAxe(NpcEntity villager, LumberjackSettings settings) {
        int slot = AxeUtils.findChoppingAxeSlot(villager.getInventory(), settings.getAllowedAxes());
        return slot < 0 ? Items.AIR : villager.getInventory().getSlot(slot).getItem();
    }

    private static List<TreeSetting> snapshot(LumberjackSettings settings) {
        List<TreeSetting> list = new ArrayList<>();

        list.add(new TreeSetting(
                LumberjackSettings.PLANT_SAPLINGS_SETTING_ID,
                Tr.s("setting.dynamicvillages.plant_saplings", "Planting"),
                settings.plantSaplings() ? 1 : 0));
        list.add(new TreeSetting(
                LumberjackSettings.AXE_CRAFT_SETTING_ID,
                Tr.s("setting.dynamicvillages.axe_allow_craft", "Wooden Tool Crafting"),
                settings.allowAxeCrafting() ? 1 : 0));

        for (LumberjackSettings.AxeTier tier : LumberjackSettings.AxeTier.values()) {
            list.add(new TreeSetting(
                    tier.settingId,
                    SettingsLabels.titleCase(tier.displayName),
                    settings.isAxeTierAllowed(tier) ? 1 : 0));
        }

        list.add(new TreeSetting(LumberjackSettings.SEPARATOR_ID,
                Tr.s("setting.dynamicvillages.separator", "Tree types"), 0));

        List<TreeSetting> treeRows = TreeTypeRegistry.all().stream()
                .map(type -> new TreeSetting(
                        type.id(), type.displayName(), settings.treeMode(type.id())))
                .sorted((a, b) -> {
                    boolean aVanilla = "minecraft".equals(a.id().getNamespace());
                    boolean bVanilla = "minecraft".equals(b.id().getNamespace());
                    if (aVanilla != bVanilla) return aVanilla ? -1 : 1;
                    return a.name().compareToIgnoreCase(b.name());
                })
                .toList();
        list.addAll(treeRows);

        return list;
    }

    public int villagerId() { return villagerId; }
    public boolean works() { return works; }
    public Item axe() { return axe; }
    public List<TreeSetting> trees() { return trees; }

    public List<TreeSetting> behaviorSettings() {
        return trees.subList(0, Math.min(BEHAVIOR_SETTINGS_COUNT, trees.size()));
    }

    public List<TreeSetting> equipmentSettings() {
        int start = Math.min(BEHAVIOR_SETTINGS_COUNT, trees.size());
        int end = Math.min(start + EQUIPMENT_SETTINGS_COUNT, trees.size());
        return trees.subList(start, end);
    }

    public List<TreeSetting> treeSettings() {
        int firstTreeIndex = Math.min(
                BEHAVIOR_SETTINGS_COUNT + EQUIPMENT_SETTINGS_COUNT + 1,
                trees.size());
        return trees.subList(firstTreeIndex, trees.size());
    }

    public void setWorksClient(boolean value) { works = value; }

    public void setTreeClient(ResourceLocation id, int mode) {
        for (int i = 0; i < trees.size(); i++) {
            TreeSetting tree = trees.get(i);
            if (tree.id().equals(id)) {
                trees.set(i, new TreeSetting(id, tree.name(), mode));
                return;
            }
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null
                || NpcUiMenu.villagerStillThere(player, villager);
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
