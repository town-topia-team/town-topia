package com.alex.test.ui.screen;

import com.alex.test.Tr;
import com.alex.test.ui.GuardSettingsMenu;
import com.alex.test.network.GuardSettingsPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

/** Compact Guard-only settings screen with the same manually rendered toggle style. */
public final class GuardSettingsScreen extends AbstractContainerScreen<GuardSettingsMenu> {

    private static final int WIDTH = 302;
    private static final int HEIGHT = 205;
    private static final int CONTENT_X = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_W = 92;
    private static final int TAB_H = 23;
    private static final int TAB_GAP = 5;
    private static final int CONTENT_WIDTH = TAB_W * 3 + TAB_GAP * 2;
    private static final int LIST_TOP = 57;
    private static final int LIST_BOTTOM = 195;
    private static final int ROW_HEIGHT = 23;
    private static final int SCROLLBAR_BOTTOM_PADDING = 3;
    private static final int BODY = 0xFF242424;
    private static final int INSET = 0xFF111111;
    private static final int BORDER = 0xFF121212;
    private static final int TAB_BORDER = 0xFF3B3B3B;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFAAAAAA;
    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;
    private static final int ROW = 0xFF292929;
    private static final int ROW_HOVER = 0xFF383838;
    private static final int TAB_SELECTED = 0xFF3B4E3E;
    private static final int ACCENT = 0xFF6BAF72;
    private static final int DISABLED_ROW = 0xFF202020;

    /**
     * Behaviour tab, top to bottom; the numbers point into {@link GuardSettingsMenu#behaviors()}
     * (0 Defending You, 1 Following You, 2 Fighting, 3 Working Nights, 4 Guard Posts).
     * No Working row: a guard has no master switch. No Additional Behaviour yet, so no heading at all.
     */
    private static final BehaviourLayout BEHAVIOUR = BehaviourLayout.of(
            BehaviourLayout.setting(2),
            BehaviourLayout.setting(3),
            BehaviourLayout.setting(4),
            BehaviourLayout.stepper(),    // Time at each guard post, under Guard Posts
            BehaviourLayout.setting(0),
            BehaviourLayout.setting(1));

    private int activeTab;
    private int swordScroll;
    private int mobScroll;
    private boolean draggingScrollbar;

    public GuardSettingsScreen(GuardSettingsMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 10;
        titleLabelY = 9;
        inventoryLabelY = -1000;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics, leftPos, topPos, WIDTH, HEIGHT);
        tab(graphics, leftPos + CONTENT_X, topPos + TAB_Y, TAB_W, Tr.s("screen.dynamicvillages.guard_settings.tab.swords", "Swords"), Items.IRON_SWORD,
                activeTab == 0, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + TAB_W + TAB_GAP, topPos + TAB_Y, TAB_W, Tr.s("screen.dynamicvillages.guard_settings.tab.mobs", "Mobs"),
                Items.ZOMBIE_HEAD, activeTab == 1, mouseX, mouseY);
        tab(graphics, leftPos + CONTENT_X + (TAB_W + TAB_GAP) * 2, topPos + TAB_Y, TAB_W,
                Tr.s("screen.dynamicvillages.settings.tab.behaviour", "Behaviour"), Items.COMPASS, activeTab == 2, mouseX, mouseY);

        int listX = leftPos + CONTENT_X;
        int listY = topPos + LIST_TOP;
        graphics.fill(
                leftPos + CONTENT_X, topPos + LIST_TOP - 2,
                leftPos + WIDTH - 8, topPos + LIST_BOTTOM,
                INSET);

        int scroll = currentScroll();
        int rowCount = rowCount();
        int visibleRows = visibleRows();
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        for (int visible = 0; visible < visibleRows; visible++) {
            int index = scroll + visible;
            if (index >= rowCount) break;
            int rowY = topPos + LIST_TOP + visible * ROW_HEIGHT;
            if (activeTab == 0) {
                GuardSettingsMenu.SwordSetting sword = menu.swords().get(index);
                com.alex.test.village.villagers.guard.settings.GuardSettings.SwordTier tier =
                        com.alex.test.village.villagers.guard.settings.GuardSettings.SwordTier.bySettingId(sword.id());
                row(graphics, rowX, rowY, rowWidth,
                        SettingNames.tool(tier != null ? tier.swordItem : null, sword.id(), sword.name()),
                        sword.enabled(), mouseX, mouseY);
            } else if (activeTab == 1) {
                GuardSettingsMenu.MobSetting mob = menu.mobs().get(index);
                row(graphics, rowX, rowY, rowWidth,
                        SettingNames.mob(mob.id(), mob.name()), mob.enabled(), mouseX, mouseY);
            } else {
                BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
                if (entry.kind() == BehaviourLayout.Kind.HEADING) {
                    SettingsHeading.draw(graphics, font, rowX, rowY, rowWidth, entry.shown());
                } else if (entry.kind() == BehaviourLayout.Kind.SETTING) {
                    GuardSettingsMenu.BehaviorSetting behavior = menu.behaviors().get(entry.setting());
                    boolean active = behaviorEnabled(behavior);
                    row(graphics, rowX, rowY, rowWidth,
                            SettingNames.own(behavior.id(), behavior.name()), behavior.enabled(), active, mouseX, mouseY);
                } else {
                    timeRow(graphics, rowX, rowY, rowWidth, mouseX, mouseY);
                }
            }
        }

        int maxScroll = maxScroll();
        if (maxScroll > 0) {
            int x = leftPos + WIDTH - 14;
            int height = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
            graphics.fill(x, topPos + LIST_TOP, x + 4, topPos + LIST_TOP + height, 0xFF333333);
            int thumbHeight = Math.max(18, height * visibleRows / rowCount);
            int thumbY = topPos + LIST_TOP + (height - thumbHeight) * scroll / maxScroll;
            graphics.fill(x, thumbY, x + 4, thumbY + thumbHeight, 0xFF888888);
        }
    }

    private void tab(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            Item icon,
            boolean selected,
            int mouseX,
            int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + TAB_H;
        int color = selected ? TAB_SELECTED : hovered ? ROW_HOVER : ROW;
        graphics.fill(x, y, x + width, y + TAB_H, TAB_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + TAB_H, color);
        if (selected) graphics.fill(x + 1, y + TAB_H - 2, x + width - 1, y + TAB_H, ACCENT);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 3);
        graphics.drawString(font, label, x + 27, y + 8, TEXT, false);
    }

    private void row(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            boolean enabled,
            int mouseX,
            int mouseY) {
        row(graphics, x, y, width, label, enabled, true, mouseX, mouseY);
    }

    private void row(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            String label,
            boolean enabled,
            boolean active,
            int mouseX,
            int mouseY) {
        boolean hovered = mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20,
                !active ? DISABLED_ROW : hovered ? 0xFF303030 : 0xFF282828);
        graphics.drawString(font, fit(label, width - 78), x + 7, y + 6,
                active ? TEXT : MUTED, false);

        int switchX = x + width - 43;
        int switchColor = !active ? 0xFF3A3A3A : enabled ? GREEN : 0xFF71312E;
        graphics.fill(switchX, y + 4, switchX + 34, y + 16, switchColor);
        int knobX = enabled ? switchX + 22 : switchX + 2;
        graphics.fill(knobX, y + 5, knobX + 10, y + 15,
                active ? 0xFFFFFFFF : 0xFF777777);
        graphics.drawString(
                font, enabled ? SettingNames.on() : SettingNames.off(), switchX - 24, y + 6,
                !active ? MUTED : enabled ? GREEN : RED, false);
    }

    private void timeRow(
            GuiGraphics graphics,
            int x,
            int y,
            int width,
            int mouseX,
            int mouseY) {
        boolean enabled = menu.useGuardPostsClient() && !menu.followPlayerClient();
        boolean hovered = enabled && mouseX >= x && mouseX < x + width
                && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + width, y + 20,
                !enabled ? DISABLED_ROW : hovered ? 0xFF303030 : 0xFF282828);
        int textColor = enabled ? TEXT : MUTED;
        graphics.drawString(font, fit(Tr.s("screen.dynamicvillages.guard_settings.post_time", "Time at each guard post"), width - 110),
                x + 7, y + 6, textColor, false);

        int minusX = x + width - 83;
        int valueX = x + width - 59;
        int plusX = x + width - 23;
        int buttonColor = enabled ? 0xFF3B3B3B : 0xFF2A2A2A;
        graphics.fill(minusX, y + 3, minusX + 18, y + 17, buttonColor);
        graphics.fill(plusX, y + 3, plusX + 18, y + 17, buttonColor);
        graphics.drawString(font, "-", minusX + 7, y + 6, textColor, false);
        graphics.drawString(font, "+", plusX + 6, y + 6, textColor, false);

        String value = Tr.s("screen.dynamicvillages.settings.seconds", "%ss", menu.guardPostTimeSeconds());
        graphics.drawString(font, value, valueX + (30 - font.width(value)) / 2,
                y + 6, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && maxScroll() > 0 && insideScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            updateScrollFromMouse(mouseY);
            return true;
        }

        if (button == 0 && mouseY >= topPos + TAB_Y && mouseY < topPos + TAB_Y + TAB_H) {
            if (mouseX >= leftPos + CONTENT_X && mouseX < leftPos + CONTENT_X + TAB_W) {
                activeTab = 0;
                return true;
            }
            if (mouseX >= leftPos + CONTENT_X + TAB_W + TAB_GAP
                    && mouseX < leftPos + CONTENT_X + TAB_W * 2 + TAB_GAP) {
                activeTab = 1;
                return true;
            }
            if (mouseX >= leftPos + CONTENT_X + (TAB_W + TAB_GAP) * 2
                    && mouseX < leftPos + CONTENT_X + TAB_W * 3 + TAB_GAP * 2) {
                activeTab = 2;
                return true;
            }
        }
        int rowX = leftPos + 10;
        int rowWidth = rowWidth();
        if (button == 0 && mouseX >= rowX && mouseX < rowX + rowWidth
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            int index = currentScroll() + ((int) mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
            if (activeTab == 0 && index < menu.swords().size()) {
                GuardSettingsMenu.SwordSetting sword = menu.swords().get(index);
                boolean enabled = !sword.enabled();
                menu.setSwordClient(sword.id(), enabled);
                PacketDistributor.sendToServer(
                        GuardSettingsPayloads.Update.sword(menu.villagerId(), sword.id(), enabled));
                return true;
            }
            if (activeTab == 1 && index < menu.mobs().size()) {
                GuardSettingsMenu.MobSetting mob = menu.mobs().get(index);
                boolean enabled = !mob.enabled();
                menu.setMobClient(mob.id(), enabled);
                PacketDistributor.sendToServer(
                        GuardSettingsPayloads.Update.mob(menu.villagerId(), mob.id(), enabled));
                return true;
            }
            BehaviourLayout.Entry entry = activeTab == 2 ? BEHAVIOUR.at(index) : null;
            if (entry != null && entry.kind() == BehaviourLayout.Kind.SETTING) {
                GuardSettingsMenu.BehaviorSetting behavior = menu.behaviors().get(entry.setting());
                if (!behaviorEnabled(behavior)) return true;
                boolean enabled = !behavior.enabled();
                menu.setBehaviorClient(behavior.id(), enabled);
                PacketDistributor.sendToServer(
                        GuardSettingsPayloads.Update.behavior(
                                menu.villagerId(), behavior.id(), enabled));
                return true;
            }
            if (entry != null && entry.kind() == BehaviourLayout.Kind.STEPPER
                    && menu.useGuardPostsClient()
                    && !menu.followPlayerClient()) {
                int minusX = rowX + rowWidth - 83;
                int plusX = rowX + rowWidth - 23;
                int current = menu.guardPostTimeSeconds();
                int next = current;
                if (mouseX >= minusX && mouseX < minusX + 18) {
                    next = current - 5;
                } else if (mouseX >= plusX && mouseX < plusX + 18) {
                    next = current + 5;
                }
                if (next != current) {
                    menu.setGuardPostTimeSecondsClient(next);
                    PacketDistributor.sendToServer(
                            new GuardSettingsPayloads.UpdateGuardPostTime(
                                    menu.villagerId(), menu.guardPostTimeSeconds()));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingScrollbar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= leftPos + CONTENT_X
                && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM) {
            setCurrentScroll(Math.max(0, Math.min(maxScroll(), currentScroll() + (scrollY < 0 ? 1 : -1))));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        SettingsTooltip.render(graphics, font, hoveredTooltip(mouseX, mouseY), mouseX, mouseY);
    }

    /** The hover text of the row under the mouse, on whichever tab is open. */
    private String hoveredTooltip(int mouseX, int mouseY) {
        int rowX = leftPos + 10;
        if (mouseX < rowX || mouseX >= rowX + rowWidth()
                || mouseY < topPos + LIST_TOP || mouseY >= topPos + LIST_BOTTOM) return null;
        if ((mouseY - topPos - LIST_TOP) % ROW_HEIGHT >= 20) return null;
        int index = currentScroll() + (mouseY - topPos - LIST_TOP) / ROW_HEIGHT;
        if (index >= rowCount()) return null;
        if (activeTab == 0) return Tr.s("screen.dynamicvillages.guard_settings.sword.desc", "ON: may use this sword.\nOFF: never uses it.");
        if (activeTab == 1) return Tr.s("screen.dynamicvillages.guard_settings.mob.desc", "ON: attacks this mob.\nOFF: leaves it alone.");
        BehaviourLayout.Entry entry = BEHAVIOUR.at(index);
        if (entry.kind() == BehaviourLayout.Kind.HEADING) return null;
        if (entry.kind() == BehaviourLayout.Kind.STEPPER) {
            return Tr.s("screen.dynamicvillages.guard_settings.post_time.desc", "How long it waits at each post\nbefore walking to the next.");
        }
        net.minecraft.resources.ResourceLocation id = menu.behaviors().get(entry.setting()).id();
        if (GuardSettingsMenu.PROTECT_PLAYER_ID.equals(id)) {
            return Tr.s("setting.dynamicvillages.protect_player.desc", "ON: runs to help when a mob hurts\nyou, even off shift.\nOFF: ignores your fights.");
        }
        if (GuardSettingsMenu.FOLLOW_PLAYER_ID.equals(id)) {
            return Tr.s("setting.dynamicvillages.follow_player.desc", "ON: walks with you during its\nshift. Only works while Defending\nYou is on.");
        }
        if (GuardSettingsMenu.ATTACK_HOSTILE_MOBS_ID.equals(id)) {
            return Tr.s("setting.dynamicvillages.attack_hostile_mobs.desc", "ON: attacks the mobs switched on\nin the Mobs tab.\n"
                    + "OFF: only fights when the village\nbell rings.");
        }
        if (GuardSettingsMenu.PATROL_AT_NIGHT_ONLY_ID.equals(id)) {
            return Tr.s("setting.dynamicvillages.patrol_at_night_only.desc", "ON: guards at night and rests\nby day.\nOFF: guards by day and rests\nat night.");
        }
        if (GuardSettingsMenu.USE_GUARD_POSTS_ID.equals(id)) {
            return Tr.s("setting.dynamicvillages.use_guard_posts.desc", "ON: walks between guard posts and\nwaits at each.\n"
                    + "OFF: walks around the village.\nLocked while Following You is on.");
        }
        return null;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, TEXT, false);
    }

    private int visibleRows() {
        return (LIST_BOTTOM - LIST_TOP) / ROW_HEIGHT;
    }

    private int maxScroll() {
        return Math.max(0, rowCount() - visibleRows());
    }

    private int rowWidth() {
        return maxScroll() > 0 ? WIDTH - 26 : WIDTH - 20;
    }

    private int rowCount() {
        if (activeTab == 0) return menu.swords().size();
        if (activeTab == 1) return menu.mobs().size();
        return BEHAVIOUR.size();
    }

    private int currentScroll() {
        if (activeTab == 0) return swordScroll;
        if (activeTab == 1) return mobScroll;
        return 0;
    }

    private void setCurrentScroll(int value) {
        if (activeTab == 0) swordScroll = value;
        else if (activeTab == 1) mobScroll = value;
    }

    private boolean insideScrollbar(double mouseX, double mouseY) {
        return mouseX >= leftPos + WIDTH - 16 && mouseX < leftPos + WIDTH - 8
                && mouseY >= topPos + LIST_TOP && mouseY < topPos + LIST_BOTTOM;
    }

    private void updateScrollFromMouse(double mouseY) {
        int maxScroll = maxScroll();
        if (maxScroll <= 0) return;
        int rowCount = rowCount();
        int trackY = topPos + LIST_TOP;
        int trackHeight = LIST_BOTTOM - LIST_TOP - SCROLLBAR_BOTTOM_PADDING;
        int thumbHeight = Math.max(18, trackHeight * visibleRows() / rowCount);
        double t = (mouseY - trackY - thumbHeight / 2.0) / Math.max(1, trackHeight - thumbHeight);
        setCurrentScroll(Math.max(0, Math.min(maxScroll, (int) Math.round(t * maxScroll))));
    }

    private boolean behaviorEnabled(GuardSettingsMenu.BehaviorSetting behavior) {
        if (GuardSettingsMenu.FOLLOW_PLAYER_ID.equals(behavior.id())) {
            return menu.protectPlayerClient();
        }
        if (GuardSettingsMenu.USE_GUARD_POSTS_ID.equals(behavior.id())) {
            return !menu.followPlayerClient();
        }
        return true;
    }

    private String fit(String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, BORDER);
        graphics.fill(x, y, x + width, y + height, BODY);
    }
}
