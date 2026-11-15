package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.entity.inventory.VillagerInventoryContainer;
import com.alex.test.village.brain.PersonalityTraits;
import com.alex.test.village.stats.HungerSystem;
import com.alex.test.village.stats.VillagerSkills;
import com.alex.test.village.stats.WellbeingSystem;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.registry.VillagerJobManager;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Synchronized information for regular villagers and Nomads. Village members show
 * their inventory read-only: players can look and hover, never take or insert.
 */
public class VillagerMenu extends AbstractContainerMenu implements NpcUiMenu {
    public static final int STAT_HEALTH = 0;
    public static final int STAT_FOOD = 1;
    public static final int STAT_WELLBEING = 2;
    public static final int STAT_INTELLIGENCE = 3;

    private static final int STAT_COUNT = 4;
    private static final int DATA_COUNT = STAT_COUNT * 2;
    private static final int GUARD_INVENTORY_OFFSET = 23;
    private static final int VILLAGER_SLOT_X = 48;
    private static final int VILLAGER_SLOT_Y = 134;

    private final int villagerId;
    private final Profession openingProfession;
    private final List<ProfessionSkill> professionSkills;
    /** What the villager is doing or waiting for, in a few words; empty for jobs that do not say. */
    private final String workNote;
    /** A grown child's favourite job from school (NONE without one): its skill tile gets a heart. */
    private final Profession favouriteJob;
    /** Levels school put it ahead in that job. */
    private final int favouriteHeadStart;
    /** The head start is still to come: it lands when the villager gets that skill. */
    private final boolean favouritePending;
    /** Personality labels for the portrait badges; often empty. */
    private final List<PersonalityTraits.Trait> traits;
    private final boolean inventoryView;
    private final boolean settingsAccess;
    /** Village claims round 7c: the owner's name when the ranks keep this player from the settings; "" otherwise. */
    private final String settingsLockedBy;
    private final boolean professionReplacement;
    private final Profession replacementTarget;
    @Nullable private final NpcEntity villager;
    private final ContainerData statsData;
    /** Client only: opened without our data (see the client constructor); nothing to show. */
    private final boolean missingOpeningData;

    public VillagerMenu(int id, NpcEntity villager, Player player) {
        super(DynamicVillages.VILLAGER_MENU.get(), id);
        this.missingOpeningData = false;
        this.villagerId = villager.getId();
        this.openingProfession = villager.getProfession();
        this.villager = villager;
        this.professionSkills = List.copyOf(villager.getProfessionSkills());
        this.workNote = workNote(villager);
        Profession favourite = villager.schoolFavouriteJob();
        this.favouriteJob = favourite != null ? favourite : Profession.NONE;
        this.favouriteHeadStart = favourite != null ? villager.schoolHeadStart() : 0;
        this.favouritePending = favourite != null && villager.schoolHeadStartPending();
        this.traits = List.copyOf(PersonalityTraits.of(villager.personality()));
        NpcEntity.PendingProfessionChange pending = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? villager.pendingProfessionChange(serverPlayer) : null;
        this.professionReplacement = pending != null;
        this.replacementTarget = pending == null ? Profession.NONE : pending.target();
        this.inventoryView = villager.canPlayerManage(player) && !professionReplacement;
        this.settingsAccess = villager.canPlayerAccessSettings(player) && !professionReplacement;
        this.settingsLockedBy = professionReplacement ? "" : villager.settingsClaimOwner(player);
        this.statsData = createStatsData(villager);
        addDataSlots(statsData);
        addInventoryViewSlots(new VillagerInventoryContainer(villager.getInventory()));
    }

    /**
     * Client side. {@code buffer} is null when the window was opened without our opening data
     * (Minecraft's plain {@code openMenu}, as a spectator's right-click did before 10-22): an
     * empty window that {@code VillagerScreen} closes on its first tick, instead of a crash.
     */
    public VillagerMenu(int id, Inventory playerInventory, @Nullable RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.VILLAGER_MENU.get(), id);
        this.missingOpeningData = buffer == null;
        if (missingOpeningData) {
            DynamicVillages.LOGGER.warn("[VillagerMenu] Window {} came without its villager data; closing it.", id);
        }
        boolean none = missingOpeningData;
        this.villagerId = none ? -1 : buffer.readVarInt();
        this.openingProfession = none ? Profession.NONE : Profession.fromNetworkId(buffer.readVarInt());
        this.inventoryView = !none && buffer.readBoolean();
        this.settingsAccess = !none && buffer.readBoolean();
        this.professionReplacement = !none && buffer.readBoolean();
        this.replacementTarget = none ? Profession.NONE : Profession.fromNetworkId(buffer.readVarInt());
        int skillCount = none ? 0 : Math.min(4, Math.max(0, buffer.readVarInt()));
        List<ProfessionSkill> skills = new ArrayList<>(skillCount);
        for (int i = 0; i < skillCount; i++) {
            skills.add(new ProfessionSkill(
                    Profession.fromNetworkId(buffer.readVarInt()), buffer.readVarInt()));
        }
        this.professionSkills = List.copyOf(skills);
        this.workNote = none ? "" : buffer.readUtf(256);
        this.favouriteJob = none ? Profession.NONE : Profession.fromNetworkId(buffer.readVarInt());
        this.favouriteHeadStart = none ? 0 : buffer.readVarInt();
        this.favouritePending = !none && buffer.readBoolean();
        int traitCount = none ? 0 : Math.min(PersonalityTraits.MAX, Math.max(0, buffer.readVarInt()));
        List<PersonalityTraits.Trait> traitList = new ArrayList<>(traitCount);
        for (int i = 0; i < traitCount; i++) {
            PersonalityTraits.Trait trait = PersonalityTraits.Trait.fromNetworkId(buffer.readVarInt());
            if (trait != null) traitList.add(trait);
        }
        this.traits = List.copyOf(traitList);
        this.settingsLockedBy = none ? "" : buffer.readUtf(64);
        this.villager = null;
        this.statsData = new SimpleContainerData(DATA_COUNT);
        addDataSlots(statsData);
        addInventoryViewSlots(new SimpleContainer(InventoryManager.SIZE));
    }

    public static void writeOpeningData(
            RegistryFriendlyByteBuf buffer, NpcEntity villager, Player player) {
        villager.ensureProfessionSkillsInitialized();
        buffer.writeVarInt(villager.getId());
        buffer.writeVarInt(villager.getProfession().ordinal());
        NpcEntity.PendingProfessionChange pending = player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                ? villager.pendingProfessionChange(serverPlayer) : null;
        buffer.writeBoolean(villager.canPlayerManage(player) && pending == null);
        buffer.writeBoolean(villager.canPlayerAccessSettings(player) && pending == null);
        buffer.writeBoolean(pending != null);
        buffer.writeVarInt(pending == null ? Profession.NONE.ordinal() : pending.target().ordinal());
        List<ProfessionSkill> skills = villager.getProfessionSkills();
        buffer.writeVarInt(skills.size());
        for (ProfessionSkill skill : skills) {
            buffer.writeVarInt(skill.profession().ordinal());
            buffer.writeVarInt(skill.level());
        }
        buffer.writeUtf(workNote(villager), 256);
        Profession favourite = villager.schoolFavouriteJob();
        buffer.writeVarInt((favourite != null ? favourite : Profession.NONE).ordinal());
        buffer.writeVarInt(favourite != null ? Math.max(0, villager.schoolHeadStart()) : 0);
        buffer.writeBoolean(favourite != null && villager.schoolHeadStartPending());
        List<PersonalityTraits.Trait> traits = PersonalityTraits.of(villager.personality());
        buffer.writeVarInt(traits.size());
        for (PersonalityTraits.Trait trait : traits) buffer.writeVarInt(trait.ordinal());
        buffer.writeUtf(pending != null ? "" : villager.settingsClaimOwner(player), 64);
    }

    /**
     * The job's own status line: the enchanter says what it writes, or why it waits.
     * A note with line breaks shows only its first line beside the job; the rest is
     * in the hover box.
     */
    private static String workNote(NpcEntity villager) {
        String note = switch (villager.getProfession()) {
            case ENCHANTER -> com.alex.test.village.villagers.enchanter.behavior.EnchanterActivity.status(villager);
            case BUTCHER -> com.alex.test.village.villagers.butcher.ButcherStatus.get(villager);
            case MAGE -> com.alex.test.village.villagers.mage.MageReagents.status(villager);
            case LUMBERJACK -> com.alex.test.village.villagers.lumberjack.LumberjackBigTrees.workNote(
                    villager, villager.level().getGameTime());
            default -> "";
        };
        return note.length() > 200 ? note.substring(0, 200) : note;
    }

    private static ContainerData createStatsData(NpcEntity villager) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> safePercent(villager.getHealth(), villager.getMaxHealth());
                    case 1 -> safeBrainValue(villager, HungerSystem.TAG_HUNGER);
                    case 2 -> safeBrainValue(villager, WellbeingSystem.TAG_WELLBEING);
                    case 3 -> safeBrainValue(villager, VillagerSkills.TAG_INTELLIGENCE);
                    case 4, 5, 6, 7 -> 100;
                    default -> 0;
                };
            }

            @Override public void set(int index, int value) {}
            @Override public int getCount() { return DATA_COUNT; }
        };
    }

    private static int safeBrainValue(NpcEntity villager, String key) {
        if (!villager.getBrainData().contains(key)) return 0;
        return safeValue(villager.getBrainData().getFloat(key), 100f);
    }

    private static int safePercent(float value, float maximum) {
        if (!Float.isFinite(value) || !Float.isFinite(maximum) || maximum <= 0f) return 0;
        return Math.max(0, Math.min(100, Math.round(value * 100f / maximum)));
    }

    private static int safeValue(float value, float maximum) {
        if (!Float.isFinite(value) || !Float.isFinite(maximum) || maximum <= 0f) return 0;
        return Math.max(0, Math.min(Math.round(maximum), Math.round(value)));
    }

    /** Display-only slots: they exist so vanilla syncs, renders and tooltips the items. */
    private void addInventoryViewSlots(Container inventory) {
        if (!inventoryView) return;
        int offset = openingProfession.isGuardLike() ? GUARD_INVENTORY_OFFSET : 0;
        for (int i = 0; i < InventoryManager.SIZE; i++) {
            addSlot(new Slot(inventory, i,
                    VILLAGER_SLOT_X + offset + (i % InventoryManager.COLUMNS) * 18,
                    VILLAGER_SLOT_Y + (i / InventoryManager.COLUMNS) * 18) {
                @Override public boolean mayPlace(ItemStack stack) { return false; }
                @Override public boolean mayPickup(Player player) { return false; }
            });
        }
    }

    public int getStatValue(int stat) { return statsData.get(stat); }
    public int getStatMaximum(int stat) { return statsData.get(STAT_COUNT + stat); }
    public int getVillagerId() { return villagerId; }
    public Profession getProfession() { return openingProfession; }
    public Component getProfessionDisplayName() {
        return Component.translatable(openingProfession.translationKey());
    }
    public List<ProfessionSkill> getProfessionSkills() { return professionSkills; }
    public String getWorkNote() { return workNote; }
    /** The favourite job from school, or NONE. */
    public Profession getFavouriteJob() { return favouriteJob; }
    public List<PersonalityTraits.Trait> getTraits() { return traits; }
    public int getFavouriteHeadStart() { return favouriteHeadStart; }
    public boolean isFavouriteHeadStartPending() { return favouritePending; }
    public Item getProfessionIcon(Profession profession) { return VillagerJobManager.tokenItem(profession); }
    public boolean hasInventoryView() { return inventoryView; }
    public boolean canAccessSettings() { return settingsAccess; }
    /** Village claims round 7c: non-empty = this villager has settings, but only this owner and their Officers change them. */
    public String settingsLockedBy() { return settingsLockedBy; }
    public boolean isProfessionReplacement() { return professionReplacement; }
    public Profession getReplacementTarget() { return replacementTarget; }
    public boolean isMissingOpeningData() { return missingOpeningData; }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (professionReplacement && villager != null
                && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            villager.cancelProfessionChange(serverPlayer);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null || (NpcUiMenu.villagerStillThere(player, villager)
                && (!(inventoryView || professionReplacement)
                    || villager.canPlayerManage(player)));
    }

    @Override
    public boolean isForNpc(NpcEntity npc) { return villager == npc; }

    /**
     * Every click is ignored, on both sides: no pickup, shift-move, hotbar swap,
     * drop, drag, double-click collect or creative clone can touch the view.
     */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {}

    @Override
    public boolean canDragTo(Slot slot) { return false; }

    @Override
    public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }

}
