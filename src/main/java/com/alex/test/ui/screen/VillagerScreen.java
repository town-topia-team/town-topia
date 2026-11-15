package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.ui.VillagerMenu;
import com.alex.test.network.GenericSettingsPayloads;
import com.alex.test.network.FarmerSettingsPayloads;
import com.alex.test.network.GuardSettingsPayloads;
import com.alex.test.network.LumberjackSettingsPayloads;
import com.alex.test.network.ProfessionReplacementPayload;
import com.alex.test.village.brain.PersonalityTraits;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.core.ProfessionSkills;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;

/** Profile-style villager UI: portrait and vitals above the inventory workspace. */
public final class VillagerScreen extends AbstractContainerScreen<VillagerMenu> {
    private static final int WIDTH = 256;
    private static final int GUARD_WIDTH = 302;
    private static final int GUARD_INVENTORY_OFFSET = 23;
    private static final int READ_ONLY_HEIGHT = 126;
    private static final int INVENTORY_HEIGHT = 194;
    private static final int REPLACEMENT_HEIGHT = 160;
    private static final int CHOICE_SLOT_X = 12;
    private static final int CHOICE_SLOT_Y = 75;
    private static final int CHOICE_SLOT_WIDTH = 54;
    private static final int CHOICE_SLOT_GAP = 5;
    private static final int CHOICE_SLOT_HEIGHT = 54;

    private static final int HEADER_HEIGHT = 28;
    private static final int PROFILE_Y = 32;
    private static final int PROFILE_HEIGHT = 92;
    private static final int PORTRAIT_WIDTH = 76;
    private static final int INFO_X = 80;
    private static final int INFO_WIDTH = WIDTH - INFO_X;
    private static final int ARMOR_CARD_X = 260;
    private static final int ARMOR_SLOT_X = 273;
    private static final int ARMOR_SLOT_Y = 46;
    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final int INVENTORY_CARD_PADDING = 3;
    private static final int VILLAGER_CARD_X = 44;
    private static final int VILLAGER_CARD_Y = 130;
    private static final int VILLAGER_CARD_WIDTH = InventoryManager.COLUMNS * 18
            + INVENTORY_CARD_PADDING * 2;

    private static final int VILLAGER_SLOT_X = VILLAGER_CARD_X + INVENTORY_CARD_PADDING + 1;
    private static final int VILLAGER_SLOT_Y = VILLAGER_CARD_Y + INVENTORY_CARD_PADDING + 1;

    static final int COL_SHADOW = 0x70000000;
    static final int COL_OUTER = 0xFF080808;
    static final int COL_PANEL = 0xFF202020;
    static final int COL_PANEL_HIGH = 0xFF292929;
    static final int COL_PANEL_DARK = 0xFF141414;
    static final int COL_ROW = 0xFF292929;
    static final int COL_OUTLINE = 0xFF454545;
    static final int COL_SLOT_OUTLINE = 0xFF333333;
    static final int COL_TEXT = 0xFFF3F3F3;
    static final int COL_MUTED = 0xFFAAAAAA;
    static final int COL_ACCENT = 0xFF6BAF72;
    static final int COL_CHOICE_HEADER = 0xFF28342B;
    static final int COL_CHOICE_HOVER = 0xFF3C5641;
    static final int COL_CHOICE_CARD = 0xFF292F2A;
    static final int COL_CHOICE_INSET = 0xFF1D251F;
    static final int COL_CHOICE_GOLD = 0xFFE6D49A;
    static final int COL_CHOICE_OUTLINE = 0xFF4B7252;
    static final int COL_SLOT = 0xFF141414;
    static final int COL_SLOT_HOVER = 0xFF383838;
    static final int COL_TRACK = 0xFF0C0C0C;
    static final int COL_HEALTH = 0xFFFF3B30;
    static final int COL_FOOD = 0xFFFF9500;
    static final int COL_WELLBEING = 0xFF30D158;
    static final int COL_INTELLIGENCE = 0xFF0A84FF;
    /** The favourite job from school: its heart, outline and words. */
    static final int COL_FAVOURITE = 0xFFFF7EB6;
    private static final ResourceLocation HEALTH_HEART_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/heart/full");

    private static final int STAT_X = INFO_X + 8;
    private static final int STAT_WIDTH = 75;
    private static final int[] STAT_Y = {PROFILE_Y + 7, PROFILE_Y + 27, PROFILE_Y + 47, PROFILE_Y + 67};
    private static final Item[] STAT_ICONS = {
            Items.AIR, Items.COOKED_BEEF,
            Items.EMERALD, Items.BOOK
    };
    private static final int[] STAT_COLORS = {COL_HEALTH, COL_FOOD, COL_WELLBEING, COL_INTELLIGENCE};
    private static final int[] STAT_INDICES = {
            VillagerMenu.STAT_HEALTH, VillagerMenu.STAT_FOOD,
            VillagerMenu.STAT_WELLBEING, VillagerMenu.STAT_INTELLIGENCE
    };
    private static final String[] STAT_LABELS = {
            "screen.dynamicvillages.health", "screen.dynamicvillages.food",
            "screen.dynamicvillages.happiness", "screen.dynamicvillages.intelligence"
    };

    private static final int SKILL_X = INFO_X + 101;
    private static final int SKILL_Y = PROFILE_Y + 8;
    private static final int SKILL_SIZE = 30;
    private static final int SKILL_GAP = 5;
    private static final float SKILL_LEVEL_SCALE = 0.75F;
    /** The skill tiles' outer width, outlines included: 67. */
    private static final int SKILL_GRID_OUTER = SKILL_SIZE * 2 + SKILL_GAP + 2;

    /**
     * Personality badges: one row centred under the skill tiles, spaced like the
     * tiles, with 2 px free above (tile outline) and below (panel edge).
     * Sizes are inside the 1 px outline; odd outer widths keep the row exactly centred.
     */
    private static final int TRAIT_SIZE = 11;
    private static final int TRAIT_GAP = SKILL_GAP;
    private static final int TRAIT_Y = SKILL_Y + SKILL_SIZE * 2 + SKILL_GAP + 4;
    private static final float TRAIT_ICON_SCALE = 0.625F;

    /** Darkens the villager screen behind the Skill Progression window. */
    private static final int COL_POPUP_DIM = 0xA0000000;

    @Nullable private SkillProgressionPanel progression;

    public VillagerScreen(VillagerMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = guardProfile() ? GUARD_WIDTH : WIDTH;
        imageHeight = menu.isProfessionReplacement() ? REPLACEMENT_HEIGHT
                : menu.hasInventoryView() ? INVENTORY_HEIGHT : READ_ONLY_HEIGHT;
        titleLabelY = -1000;
        inventoryLabelY = -1000;
    }

    @Override
    protected void init() {
        super.init();
        // Village claims round 7c: settings the ranks keep from this player show greyed, with whose they are.
        boolean claimLocked = !menu.settingsLockedBy().isEmpty();
        if (!(menu.canAccessSettings() || claimLocked) || menu.isProfessionReplacement()) return;
        Profession profession = menu.getProfession();
        boolean tradesman = profession == Profession.TRADESMAN;
        boolean builder = profession == Profession.BUILDER;
        int buttonWidth = settingsButtonWidth();
        // The Builder's button opens its desk (blueprints, projects, and a link to its settings).
        Component label = tradesman ? Tr.c("screen.dynamicvillages.villager.trade_settings", "Trade Settings")
                : builder ? Tr.c("screen.dynamicvillages.villager.builders_desk", "Builder's desk")
                : Component.translatable("screen.dynamicvillages.settings");
        StyledSettingsButton settings = addRenderableWidget(new StyledSettingsButton(
                leftPos + imageWidth - buttonWidth - 8, topPos + 4, buttonWidth, 20, label,
                new ItemStack(builder ? Items.BRICKS : Items.COMPARATOR),
                button -> openProfessionSettings(profession)));
        if (claimLocked) {
            settings.active = false;
            settings.setTooltip(Tooltip.create(Tr.c("screen.dynamicvillages.villager.claim_settings_locked",
                    "%s has not let you change how this villager works.", menu.settingsLockedBy())));
        }
    }

    /** Width of the top-right settings button; wider labels get wider buttons. */
    private int settingsButtonWidth() {
        return switch (menu.getProfession()) {
            case TRADESMAN -> 104;
            case BUILDER -> 100;
            default -> 74;
        };
    }

    private void openProfessionSettings(Profession profession) {
        switch (profession) {
            case LUMBERJACK -> PacketDistributor.sendToServer(
                    new LumberjackSettingsPayloads.Open(menu.getVillagerId()));
            case GUARD, CAPTAIN -> PacketDistributor.sendToServer(
                    new GuardSettingsPayloads.Open(menu.getVillagerId()));
            case FARMER -> PacketDistributor.sendToServer(
                    new FarmerSettingsPayloads.Open(menu.getVillagerId()));
            case BUILDER -> PacketDistributor.sendToServer(
                    new com.alex.test.network.BuilderDeskPayloads.Open(menu.getVillagerId()));
            default -> PacketDistributor.sendToServer(
                    new GenericSettingsPayloads.Open(menu.getVillagerId()));
        }
    }

    private boolean guardProfile() {
        return menu.getProfession().isGuardLike() && !menu.isProfessionReplacement();
    }

    private int inventoryOffset() {
        return guardProfile() ? GUARD_INVENTORY_OFFSET : 0;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        int localMouseX = mouseX - x;
        int localMouseY = mouseY - y;

        if (menu.isProfessionReplacement()) {
            drawProfessionChoice(graphics, x, y, localMouseX, localMouseY);
            return;
        }

        drawPanel(graphics, x, y, imageWidth, HEADER_HEIGHT, COL_PANEL_HIGH);
        graphics.fill(x + 1, y + HEADER_HEIGHT - 2,
                x + imageWidth - 1, y + HEADER_HEIGHT, COL_ACCENT);
        drawHeader(graphics, x, y);

        drawPanel(graphics, x, y + PROFILE_Y, PORTRAIT_WIDTH, PROFILE_HEIGHT, COL_PANEL);
        drawPortrait(graphics, x, y, mouseX, mouseY);

        drawPanel(graphics, x + INFO_X, y + PROFILE_Y, INFO_WIDTH, PROFILE_HEIGHT, COL_PANEL);
        for (int i = 0; i < STAT_Y.length; i++) {
            drawStat(graphics, x + STAT_X, y + STAT_Y[i],
                    STAT_ICONS[i], STAT_COLORS[i], STAT_INDICES[i]);
        }
        graphics.fill(x + INFO_X + 91, y + PROFILE_Y + 8,
                x + INFO_X + 92, y + PROFILE_Y + 84, COL_OUTLINE);
        drawSkillGrid(graphics, x, y, localMouseX, localMouseY);
        drawTraitBadges(graphics, x, y, localMouseX, localMouseY);

        if (guardProfile()) drawArmorPanel(graphics, x, y, localMouseX, localMouseY);

        if (!menu.hasInventoryView()) return;

        int offset = inventoryOffset();

        drawInventoryCard(graphics, x + VILLAGER_CARD_X + offset, y + VILLAGER_CARD_Y,
                VILLAGER_CARD_WIDTH, InventoryManager.ROWS * 18 + INVENTORY_CARD_PADDING * 2);
        for (int row = 0; row < InventoryManager.ROWS; row++) {
            for (int col = 0; col < InventoryManager.COLUMNS; col++) {
                int sx = VILLAGER_SLOT_X + offset + col * 18;
                int sy = VILLAGER_SLOT_Y + row * 18;
                drawSlot(graphics, x + sx, y + sy,
                        inside(localMouseX, localMouseY, sx, sy, 16, 16));
            }
        }
    }

    private void drawArmorPanel(
            GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        drawPanel(graphics, x + ARMOR_CARD_X, y + PROFILE_Y, 42, PROFILE_HEIGHT, COL_PANEL);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.villager.armor", "Armor"), x + ARMOR_CARD_X + 6,
                y + PROFILE_Y + 3, COL_ACCENT, false);
        LivingEntity guard = findDisplayedEntity();
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            int sy = ARMOR_SLOT_Y + i * 18;
            drawSlot(graphics, x + ARMOR_SLOT_X, y + sy,
                    inside(mouseX, mouseY, ARMOR_SLOT_X, sy, 16, 16));
            if (guard != null) {
                ItemStack worn = guard.getItemBySlot(ARMOR_SLOTS[i]);
                if (!worn.isEmpty()) {
                    graphics.renderItem(worn, x + ARMOR_SLOT_X, y + sy);
                    graphics.renderItemDecorations(font, worn, x + ARMOR_SLOT_X, y + sy);
                }
            }
        }
    }

    private void drawProfessionChoice(
            GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        drawPanel(graphics, x, y, WIDTH, REPLACEMENT_HEIGHT, COL_PANEL);
        graphics.fill(x + 1, y + 1, x + WIDTH - 1, y + 32, COL_CHOICE_HEADER);
        graphics.fill(x + 8, y + 5, x + WIDTH - 8, y + 6, 0xFF3A4C3E);
        graphics.fill(x + 10, y + 31, x + WIDTH - 10, y + 33, COL_ACCENT);
        graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.villager.choose_slot", "Choose a slot"),
                x + WIDTH / 2, y + 12, COL_TEXT);
        Profession target = menu.getReplacementTarget();
        // The favourite job from school starts with its head start on top.
        boolean targetFavourite = target == menu.getFavouriteJob();
        int targetLevel = 1 + (targetFavourite && menu.isFavouriteHeadStartPending()
                ? menu.getFavouriteHeadStart() : 0);
        graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.villager.replace_with", "Replace with %1$s (Lv %2$s)",
                        target.shownName(), targetLevel),
                x + WIDTH / 2, y + (targetFavourite ? 42 : 47), COL_ACCENT);
        if (targetFavourite) {
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.villager.its_favourite", "Its favourite job!"),
                    x + WIDTH / 2, y + 54, COL_FAVOURITE);
        }

        graphics.fill(x + 6, y + 69, x + WIDTH - 6, y + 137, COL_CHOICE_INSET);
        graphics.fill(x + 6, y + 69, x + WIDTH - 6, y + 70, COL_PANEL_DARK);
        graphics.fill(x + 6, y + 136, x + WIDTH - 6, y + 137, COL_CHOICE_OUTLINE);

        List<ProfessionSkill> skills = menu.getProfessionSkills();
        for (int i = 0; i < ProfessionSkills.MAX_SKILLS; i++) {
            int sx = CHOICE_SLOT_X + i * (CHOICE_SLOT_WIDTH + CHOICE_SLOT_GAP);
            boolean hovered = inside(mouseX, mouseY, sx, CHOICE_SLOT_Y,
                    CHOICE_SLOT_WIDTH, CHOICE_SLOT_HEIGHT);
            int border = hovered ? COL_ACCENT : COL_OUTLINE;
            graphics.fill(x + sx + 2, y + CHOICE_SLOT_Y + 2,
                    x + sx + CHOICE_SLOT_WIDTH + 2,
                    y + CHOICE_SLOT_Y + CHOICE_SLOT_HEIGHT + 2, COL_SHADOW);
            graphics.fill(x + sx - 1, y + CHOICE_SLOT_Y - 1,
                    x + sx + CHOICE_SLOT_WIDTH + 1, y + CHOICE_SLOT_Y + CHOICE_SLOT_HEIGHT + 1,
                    border);
            graphics.fill(x + sx, y + CHOICE_SLOT_Y,
                    x + sx + CHOICE_SLOT_WIDTH, y + CHOICE_SLOT_Y + CHOICE_SLOT_HEIGHT,
                    hovered ? COL_CHOICE_HOVER : COL_CHOICE_CARD);
            graphics.fill(x + sx + 1, y + CHOICE_SLOT_Y + 1,
                    x + sx + CHOICE_SLOT_WIDTH - 1, y + CHOICE_SLOT_Y + 3,
                    hovered ? COL_ACCENT : COL_CHOICE_OUTLINE);
            graphics.fill(x + sx + 2, y + CHOICE_SLOT_Y + CHOICE_SLOT_HEIGHT - 2,
                    x + sx + CHOICE_SLOT_WIDTH - 2, y + CHOICE_SLOT_Y + CHOICE_SLOT_HEIGHT - 1,
                    COL_PANEL_DARK);
            if (i >= skills.size()) continue;
            ProfessionSkill skill = skills.get(i);
            Item skillIcon = menu.getProfessionIcon(skill.profession());
            if (skillIcon != Items.AIR) {
                graphics.renderFakeItem(new ItemStack(skillIcon),
                        x + sx + (CHOICE_SLOT_WIDTH - 16) / 2, y + CHOICE_SLOT_Y + 4);
            }
            graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.villager.level_short", "Lv %s", skill.level()),
                    x + sx + CHOICE_SLOT_WIDTH / 2, y + CHOICE_SLOT_Y + 24, COL_CHOICE_GOLD);
            boolean favourite = skill.profession() == menu.getFavouriteJob();
            graphics.drawCenteredString(font,
                    fitText(skill.profession().shownName(), CHOICE_SLOT_WIDTH - 4),
                    x + sx + CHOICE_SLOT_WIDTH / 2, y + CHOICE_SLOT_Y + 38, favourite ? COL_FAVOURITE : COL_TEXT);
            if (favourite) drawFavouriteHeart(graphics, x + sx + CHOICE_SLOT_WIDTH - 11, y + CHOICE_SLOT_Y + 4);
        }
        graphics.drawCenteredString(font, Tr.s("screen.dynamicvillages.villager.esc_to_cancel", "Esc to cancel"),
                x + WIDTH / 2, y + 143, COL_MUTED);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (progression != null) {
            // The window is modal: a click outside it closes it.
            if (!progression.contains(mouseX, mouseY)
                    || progression.mouseClicked(mouseX, mouseY, button)) {
                toggleProgression(null);
            }
            return true;
        }
        if (button == 0 && menu.isProfessionReplacement()) {
            int localX = (int) (mouseX - leftPos);
            int localY = (int) (mouseY - topPos);
            for (int i = 0; i < ProfessionSkills.MAX_SKILLS; i++) {
                int sx = CHOICE_SLOT_X + i * (CHOICE_SLOT_WIDTH + CHOICE_SLOT_GAP);
                if (inside(localX, localY, sx, CHOICE_SLOT_Y,
                        CHOICE_SLOT_WIDTH, CHOICE_SLOT_HEIGHT)) {
                    PacketDistributor.sendToServer(
                            new ProfessionReplacementPayload(menu.getVillagerId(), i));
                    return true;
                }
            }
        }
        if (button == 0 && !menu.isProfessionReplacement()) {
            int tile = skillTileAt((int) (mouseX - leftPos), (int) (mouseY - topPos));
            List<ProfessionSkill> skills = menu.getProfessionSkills();
            if (tile >= 0 && tile < skills.size()) {
                toggleProgression(skills.get(tile).profession());
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (progression != null) {
            progression.mouseReleased();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button,
                                double dragX, double dragY) {
        if (progression != null) {
            progression.mouseDragged(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (progression != null) {
            if (progression.contains(mouseX, mouseY)) {
                progression.mouseScrolled(mouseX, mouseY, scrollY, progressionLevel());
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (progression != null && keyCode == InputConstants.KEY_ESCAPE) {
            toggleProgression(null);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Slots behind the Skill Progression window are neither hovered nor clicked while it is open. */
    @Override
    protected boolean isHovering(int x, int y, int width, int height, double mouseX, double mouseY) {
        if (progression != null) return false;
        return super.isHovering(x, y, width, height, mouseX, mouseY);
    }

    /**
     * Opens the Skill Progression window for {@code profession}, or closes it
     * when that one is already open (or {@code profession} is null).
     */
    private void toggleProgression(@Nullable Profession profession) {
        if (profession == null || (progression != null && progression.profession() == profession)) {
            progression = null;
        } else {
            Item token = menu.getProfessionIcon(profession);
            progression = new SkillProgressionPanel(font, profession,
                    token == Items.AIR ? Items.NAME_TAG : token);
            progression.layout(width, height);
        }
    }

    /** The open panel's skill level now; the menu's skills can change while it is open. */
    private int progressionLevel() {
        if (progression == null) return 1;
        for (ProfessionSkill skill : menu.getProfessionSkills()) {
            if (skill.profession() == progression.profession()) return skill.level();
        }
        return 1;
    }

    private int skillTileAt(int localX, int localY) {
        for (int i = 0; i < ProfessionSkills.MAX_SKILLS; i++) {
            int sx = SKILL_X + (i % 2) * (SKILL_SIZE + SKILL_GAP);
            int sy = SKILL_Y + (i / 2) * (SKILL_SIZE + SKILL_GAP);
            if (inside(localX, localY, sx, sy, SKILL_SIZE, SKILL_SIZE)) return i;
        }
        return -1;
    }

    private void drawHeader(GuiGraphics graphics, int x, int y) {
        Item icon = menu.getProfessionIcon(menu.getProfession());
        if (icon == Items.AIR) icon = Items.NAME_TAG;
        graphics.renderFakeItem(new ItemStack(icon), x + 8, y + 6);

        int maxWidth = headerTextWidth();
        graphics.drawString(font, fitText(title.getString(), maxWidth),
                x + 30, y + 4, COL_TEXT, false);
        String job = menu.getProfessionDisplayName().getString();
        graphics.drawString(font, fitText(job, maxWidth), x + 30, y + 15, COL_ACCENT, false);
        String note = menu.getWorkNote();
        int jobWidth = font.width(job);
        if (!note.isEmpty() && jobWidth + 20 < maxWidth) {
            // What it is doing or waiting for, after the job; the whole note on hover.
            // A note with line breaks shows only its first line here.
            int lineEnd = note.indexOf('\n');
            String shown = lineEnd >= 0 ? note.substring(0, lineEnd) : note;
            graphics.drawString(font, fitText(" · " + shown, maxWidth - jobWidth),
                    x + 30 + jobWidth, y + 15, COL_MUTED, false);
        } else if (menu.getProfession() == Profession.NONE && menu.getFavouriteJob() != Profession.NONE
                && jobWidth + 40 < maxWidth) {
            // A grown child without a job yet: what it would love to do, with the same heart as its tile.
            int fx = x + 30 + jobWidth + 6;
            drawFavouriteHeart(graphics, fx, y + 14);
            graphics.drawString(font, fitText(Tr.s("screen.dynamicvillages.villager.favourite_job", "Favourite job: %s",
                            menu.getFavouriteJob().shownName()),
                    maxWidth - jobWidth - 18), fx + 11, y + 15, COL_FAVOURITE, false);
        }
    }

    /** The job line's width in the header, the same rule drawHeader fits it to. */
    private int headerTextWidth() {
        // A wider settings button takes its extra width from the name and job lines.
        if (!menu.canAccessSettings() && menu.settingsLockedBy().isEmpty()) return 216;
        return switch (menu.getProfession()) {
            case TRADESMAN -> 102;
            case BUILDER -> 106;
            default -> 134;
        };
    }

    private void drawPortrait(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        graphics.fill(x + 5, y + PROFILE_Y + 4,
                x + PORTRAIT_WIDTH - 5, y + PROFILE_Y + PROFILE_HEIGHT - 4, COL_OUTER);
        graphics.fill(x + 7, y + PROFILE_Y + 6,
                x + PORTRAIT_WIDTH - 7, y + PROFILE_Y + PROFILE_HEIGHT - 6, COL_PANEL_DARK);
        LivingEntity entity = findDisplayedEntity();
        if (entity != null) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(
                    graphics,
                    x + 7, y + PROFILE_Y + 5,
                    x + PORTRAIT_WIDTH - 7, y + PROFILE_Y + PROFILE_HEIGHT - 5,
                    30, 0.0F, mouseX, mouseY, entity);
            return;
        }
        graphics.renderFakeItem(new ItemStack(Items.VILLAGER_SPAWN_EGG),
                x + PORTRAIT_WIDTH / 2 - 8, y + PROFILE_Y + PROFILE_HEIGHT / 2 - 8);
    }

    @Nullable
    private LivingEntity findDisplayedEntity() {
        if (minecraft == null || minecraft.level == null) return null;
        Entity entity = minecraft.level.getEntity(menu.getVillagerId());
        return entity instanceof LivingEntity living ? living : null;
    }

    private void drawStat(GuiGraphics graphics, int x, int y, Item icon, int color, int stat) {
        if (stat == VillagerMenu.STAT_HEALTH) {
            graphics.blitSprite(HEALTH_HEART_SPRITE, x + 1, y + 1, 13, 13);
        } else {
            graphics.renderFakeItem(new ItemStack(icon), x, y);
        }
        int barX = x + 20;
        int barY = y + 5;
        int barWidth = STAT_WIDTH - 20;
        graphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + 7, COL_OUTLINE);
        graphics.fill(barX, barY, barX + barWidth, barY + 6, COL_TRACK);
        int maximum = Math.max(1, menu.getStatMaximum(stat));
        int value = Math.max(0, Math.min(maximum, menu.getStatValue(stat)));
        int fill = Math.round(value * barWidth / (float) maximum);
        if (fill > 0) {
            graphics.fill(barX, barY, barX + fill, barY + 6, color);
            graphics.fill(barX, barY, barX + fill, barY + 1, lighten(color));
        }
    }

    private void drawSkillGrid(
            GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<ProfessionSkill> skills = menu.getProfessionSkills();
        for (int i = 0; i < ProfessionSkills.MAX_SKILLS; i++) {
            int column = i % 2;
            int row = i / 2;
            int sx = SKILL_X + column * (SKILL_SIZE + SKILL_GAP);
            int sy = SKILL_Y + row * (SKILL_SIZE + SKILL_GAP);
            boolean hovered = inside(mouseX, mouseY, sx, sy, SKILL_SIZE, SKILL_SIZE);
            ProfessionSkill skill = i < skills.size() ? skills.get(i) : null;
            boolean active = skill != null && skill.profession() == menu.getProfession();
            boolean shown = skill != null && progression != null
                    && skill.profession() == progression.profession();
            boolean favourite = skill != null && skill.profession() == menu.getFavouriteJob();

            int outline = shown ? COL_CHOICE_GOLD : active ? COL_ACCENT : favourite ? COL_FAVOURITE : COL_OUTLINE;
            graphics.fill(x + sx - 1, y + sy - 1,
                    x + sx + SKILL_SIZE + 1, y + sy + SKILL_SIZE + 1, outline);
            graphics.fill(x + sx, y + sy, x + sx + SKILL_SIZE, y + sy + 20,
                    hovered ? COL_SLOT_HOVER : COL_SLOT);
            graphics.fill(x + sx, y + sy + 20,
                    x + sx + SKILL_SIZE, y + sy + SKILL_SIZE,
                    active ? 0xFF304336 : COL_PANEL_DARK);
            graphics.fill(x + sx, y + sy + 20,
                    x + sx + SKILL_SIZE, y + sy + 21,
                    active ? COL_ACCENT : COL_SLOT_OUTLINE);

            if (skill == null) {
                continue;
            }

            Item icon = menu.getProfessionIcon(skill.profession());
            if (icon == Items.AIR) icon = Items.BARRIER;
            int iconOffset = (SKILL_SIZE - 16) / 2;
            graphics.renderFakeItem(new ItemStack(icon), x + sx + iconOffset, y + sy + 2);
            String level = Integer.toString(skill.level());
            drawCenteredScaledString(graphics, level,
                    x + sx + SKILL_SIZE / 2.0F, y + sy + 22,
                    SKILL_LEVEL_SCALE, active ? COL_ACCENT : favourite ? COL_FAVOURITE : COL_TEXT);
            if (favourite) drawFavouriteHeart(graphics, x + sx + SKILL_SIZE - 6, y + sy - 3);
        }
    }

    /** One small badge per personality label, drawn over the portrait; hover for what it means. */
    /** Left edge, inside the outline, of badge {@code index} in a centred row of {@code count}. */
    private static int traitX(int index, int count) {
        int outer = TRAIT_SIZE + 2;
        int rowWidth = count * outer + (count - 1) * TRAIT_GAP;
        return SKILL_X - 1 + (SKILL_GRID_OUTER - rowWidth) / 2 + index * (outer + TRAIT_GAP) + 1;
    }

    /** One small badge per personality label, centred under the skill tiles; hover for what it means. */
    private void drawTraitBadges(GuiGraphics graphics, int x, int y, int mouseX, int mouseY) {
        List<PersonalityTraits.Trait> traits = menu.getTraits();
        for (int i = 0; i < traits.size(); i++) {
            int bx = traitX(i, traits.size());
            boolean hovered = inside(mouseX, mouseY, bx - 1, TRAIT_Y - 1, TRAIT_SIZE + 2, TRAIT_SIZE + 2);
            // Outline and fill match the skill tiles above.
            graphics.fill(x + bx - 1, y + TRAIT_Y - 1, x + bx + TRAIT_SIZE + 1, y + TRAIT_Y + TRAIT_SIZE + 1,
                    hovered ? COL_ACCENT : COL_OUTLINE);
            graphics.fill(x + bx, y + TRAIT_Y, x + bx + TRAIT_SIZE, y + TRAIT_Y + TRAIT_SIZE,
                    hovered ? COL_SLOT_HOVER : COL_SLOT);
            graphics.pose().pushPose();
            // A 10 px icon centred in the 11 px badge.
            graphics.pose().translate(x + bx + 0.5F, y + TRAIT_Y + 0.5F, 0.0F);
            graphics.pose().scale(TRAIT_ICON_SCALE, TRAIT_ICON_SCALE, 1.0F);
            graphics.renderFakeItem(new ItemStack(traitIcon(traits.get(i))), 0, 0);
            graphics.pose().popPose();
        }
    }

    private static Item traitIcon(PersonalityTraits.Trait trait) {
        return switch (trait) {
            case QUICK_HANDS -> Items.SUGAR;
            case SLOW_AND_STEADY -> Items.TURTLE_SCUTE;
            case HARD_WORKER -> Items.IRON_PICKAXE;
            case EASYGOING -> Items.POPPY;
            case CHATTY -> Items.BELL;
            case KEEPS_TO_THEMSELVES -> Items.LANTERN;
        };
    }

    /** The favourite job's heart, a small badge over the corner of its tile, above the item. */
    private void drawFavouriteHeart(GuiGraphics graphics, int x, int y) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 200.0F);
        graphics.blitSprite(HEALTH_HEART_SPRITE, x, y, 9, 9);
        graphics.pose().popPose();
    }

    /** Tooltip lines for the favourite job from school. */
    private List<Component> favouriteLines(Profession job) {
        int ahead = menu.getFavouriteHeadStart();
        Component lessons = com.alex.test.village.villagers.teacher.SchoolSubjects.lessonText(job);
        return List.of(
                Tr.c("screen.dynamicvillages.villager.favourite", "Favourite job!").withStyle(ChatFormatting.LIGHT_PURPLE),
                Tr.c("screen.dynamicvillages.villager.favourite.lessons", "It loved %s lessons at school", lessons)
                        .withStyle(ChatFormatting.GRAY),
                (ahead > 0
                        ? Tr.c("screen.dynamicvillages.villager.favourite.head_start", "and got %s extra levels for it.", ahead)
                        : Tr.c("screen.dynamicvillages.villager.favourite.more_than_any", "more than any other."))
                        .withStyle(ChatFormatting.GRAY));
    }

    private void drawCenteredScaledString(
            GuiGraphics graphics, String text, float centerX, float topY, float scale, int color) {
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, topY, 0.0F);
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(font, text, -font.width(text) / 2.0F, 0.0F, color, false);
        graphics.pose().popPose();
    }

    static void drawPanel(GuiGraphics graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x + 2, y + 2, x + width + 2, y + height + 2, COL_SHADOW);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COL_OUTER);
        graphics.fill(x, y, x + width, y + height, color);
        graphics.fill(x, y, x + width, y + 1, COL_OUTLINE);
        graphics.fill(x, y + height - 1, x + width, y + height, COL_PANEL_DARK);
    }

    private void drawInventoryCard(
            GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x + 2, y + 2, x + width + 2, y + height + 2, COL_SHADOW);
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COL_OUTER);
        graphics.fill(x, y, x + width, y + height, COL_PANEL_DARK);
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, COL_ROW);
    }

    private void drawSlot(GuiGraphics graphics, int x, int y, boolean hovered) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, COL_SLOT_OUTLINE);
        graphics.fill(x, y, x + 16, y + 16, hovered ? COL_SLOT_HOVER : COL_SLOT);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Everything is positioned against the profile layout in renderBg.
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        // Opened without its villager's data (VillagerMenu says when): nothing to show.
        if (menu.isMissingOpeningData()) onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (menu.isMissingOpeningData()) return;
        if (progression == null) {
            super.render(graphics, mouseX, mouseY, partialTick);
            // Vanilla item tooltip for the read-only inventory slots.
            renderTooltip(graphics, mouseX, mouseY);
            renderInformationTooltip(graphics, mouseX, mouseY);
            return;
        }
        // The villager screen behind the window shows no hover while it is open.
        super.render(graphics, -1, -1, partialTick);
        int intelligence = menu.getStatValue(VillagerMenu.STAT_INTELLIGENCE);
        progression.layout(width, height);
        graphics.pose().pushPose();
        // Slots draw their items 250 deep and stack counts at 300; the window goes
        // over both. Its own icons then reach ~470, so tooltips are lifted past that.
        graphics.pose().translate(0.0F, 0.0F, 310.0F);
        graphics.fill(0, 0, width, height, COL_POPUP_DIM);
        progression.render(graphics, progressionLevel(), intelligence, mouseX, mouseY);
        graphics.pose().popPose();
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 100.0F);
        progression.renderTooltip(graphics, intelligence, mouseX, mouseY);
        graphics.pose().popPose();
    }

    private void renderInformationTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;

        if (guardProfile()) {
            for (int i = 0; i < ARMOR_SLOTS.length; i++) {
                if (!inside(localX, localY, ARMOR_SLOT_X, ARMOR_SLOT_Y + i * 18, 16, 16)) continue;
                LivingEntity guard = findDisplayedEntity();
                ItemStack worn = guard == null ? ItemStack.EMPTY : guard.getItemBySlot(ARMOR_SLOTS[i]);
                if (!worn.isEmpty()) graphics.renderTooltip(font, worn, mouseX, mouseY);
                return;
            }
        }

        if (menu.isProfessionReplacement()) {
            List<ProfessionSkill> skills = menu.getProfessionSkills();
            for (int i = 0; i < skills.size(); i++) {
                int sx = CHOICE_SLOT_X + i * (CHOICE_SLOT_WIDTH + CHOICE_SLOT_GAP);
                if (inside(localX, localY, sx, CHOICE_SLOT_Y,
                        CHOICE_SLOT_WIDTH, CHOICE_SLOT_HEIGHT)) {
                    ProfessionSkill skill = skills.get(i);
                    Profession target = menu.getReplacementTarget();
                    int targetLevel = 1 + (target == menu.getFavouriteJob() && menu.isFavouriteHeadStartPending()
                            ? menu.getFavouriteHeadStart() : 0);
                    List<Component> lines = new java.util.ArrayList<>();
                    lines.add(Tr.c("screen.dynamicvillages.villager.replace_slot",
                            "Replace %1$s (level %2$s) with %3$s (level %4$s)",
                            skill.profession().shownName(), skill.level(), target.shownName(), targetLevel));
                    if (skill.profession() == menu.getFavouriteJob()) {
                        lines.add(Tr.c("screen.dynamicvillages.villager.replace_favourite", "This is its favourite job!")
                                .withStyle(ChatFormatting.LIGHT_PURPLE));
                        lines.add(Tr.c("screen.dynamicvillages.villager.replace_favourite.desc", "Replacing it loses those levels.")
                                .withStyle(ChatFormatting.GRAY));
                    }
                    graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
                    return;
                }
            }
            return;
        }

        String note = menu.getWorkNote();
        if (!note.isEmpty() && inside(localX, localY, 30, 14, headerTextWidth(), 10)) {
            // One line: the whole note. Several: the first is the short label already
            // shown beside the job, so the box explains with the rest.
            String[] noteLines = note.split("\n");
            List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.literal(menu.getProfessionDisplayName().getString()));
            for (int i = noteLines.length > 1 ? 1 : 0; i < noteLines.length; i++) {
                lines.add(Component.literal(noteLines[i]).withStyle(ChatFormatting.GRAY));
            }
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }
        if (note.isEmpty() && menu.getProfession() == Profession.NONE
                && menu.getFavouriteJob() != Profession.NONE
                && inside(localX, localY, 30, 14, headerTextWidth(), 10)) {
            List<Component> lines = new java.util.ArrayList<>(favouriteLines(menu.getFavouriteJob()));
            lines.add(Tr.c("screen.dynamicvillages.villager.favourite.give_token", "Give it that job's token to use them.")
                    .withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

        List<PersonalityTraits.Trait> traits = menu.getTraits();
        for (int i = 0; i < traits.size(); i++) {
            if (!inside(localX, localY, traitX(i, traits.size()) - 1, TRAIT_Y - 1,
                    TRAIT_SIZE + 2, TRAIT_SIZE + 2)) continue;
            PersonalityTraits.Trait trait = traits.get(i);
            graphics.renderComponentTooltip(font, List.of(
                    Component.literal(trait.title).withStyle(ChatFormatting.GOLD),
                    Component.literal(trait.line1).withStyle(ChatFormatting.GRAY),
                    Component.literal(trait.line2).withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }

        for (int i = 0; i < STAT_Y.length; i++) {
            int stat = STAT_INDICES[i];
            if (inside(localX, localY, STAT_X, STAT_Y[i], STAT_WIDTH, 16)) {
                graphics.renderTooltip(font, Component.translatable(
                        "screen.dynamicvillages.status_value",
                        Component.translatable(STAT_LABELS[i]),
                        menu.getStatValue(stat), menu.getStatMaximum(stat)), mouseX, mouseY);
                return;
            }
        }

        List<ProfessionSkill> skills = menu.getProfessionSkills();
        for (int i = 0; i < ProfessionSkills.MAX_SKILLS; i++) {
            int sx = SKILL_X + (i % 2) * (SKILL_SIZE + SKILL_GAP);
            int sy = SKILL_Y + (i / 2) * (SKILL_SIZE + SKILL_GAP);
            if (!inside(localX, localY, sx, sy, SKILL_SIZE, SKILL_SIZE)) continue;
            if (i >= skills.size()) {
                graphics.renderTooltip(font,
                        Component.translatable("screen.dynamicvillages.empty_skill"), mouseX, mouseY);
                return;
            }
            ProfessionSkill skill = skills.get(i);
            String key = skill.profession() == menu.getProfession()
                    ? "screen.dynamicvillages.skill_level_active"
                    : "screen.dynamicvillages.skill_level";
            boolean shown = progression != null && progression.profession() == skill.profession();
            List<Component> lines = new java.util.ArrayList<>();
            lines.add(Component.translatable(key,
                    Component.translatable(skill.profession().translationKey()),
                    skill.level()));
            if (skill.profession() == menu.getFavouriteJob()) lines.addAll(favouriteLines(skill.profession()));
            lines.add((shown
                    ? Tr.c("screen.dynamicvillages.villager.hide_progression", "Click to hide skill progression")
                    : Tr.c("screen.dynamicvillages.villager.show_progression", "Click to see skill progression"))
                    .withStyle(ChatFormatting.GRAY));
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            return;
        }

    }

    private final class StyledSettingsButton extends Button {
        private final ItemStack icon;

        private StyledSettingsButton(
                int x, int y, int width, int height, Component message, ItemStack icon, OnPress onPress) {
            super(x, y, width, height, message, onPress, DEFAULT_NARRATION);
            this.icon = icon;
            setTooltip(Tooltip.create(message));
        }

        @Override
        protected void renderWidget(
                GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            int border = isHoveredOrFocused() ? 0xFF666666 : COL_OUTLINE;
            int background = !active ? 0xFF222222
                    : isHoveredOrFocused() ? COL_SLOT_HOVER : COL_ROW;
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), border);
            graphics.fill(getX() + 1, getY() + 1,
                    getX() + getWidth() - 1, getY() + getHeight() - 1, background);
            graphics.renderFakeItem(icon, getX() + 3, getY() + 2);
            int textLeft = getX() + 20;
            graphics.drawCenteredString(font, getMessage(),
                    textLeft + (getWidth() - 20) / 2, getY() + 6,
                    active ? COL_TEXT : COL_MUTED);
        }
    }

    static int lighten(int color) {
        int r = Math.min(255, ((color >> 16) & 0xFF) + 28);
        int g = Math.min(255, ((color >> 8) & 0xFF) + 28);
        int b = Math.min(255, (color & 0xFF) + 28);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static boolean inside(int x, int y, int left, int top, int width, int height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private String fitText(String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String suffix = "...";
        return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width(suffix))) + suffix;
    }
}
