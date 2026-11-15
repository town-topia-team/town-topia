package com.alex.test.ui.screen.mayor;

import com.alex.test.DynamicVillages;
import com.alex.test.Tr;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.village.DefenderTeamRules;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.miner.settings.MinerSettingsSchema;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * Settings > Professions: the jobs with a setting for the whole village, and each one's own page
 * (the Miner's side tunnels, a defender job's fighting rules). One page open at a time; while one is,
 * the screen fits it as a fixed page (MINER) instead of a list.
 */
final class ProfessionSettingsPage extends MayorPart {

    private record ProfessionSummary(String name, String iconItemId, int count) {}

    private static final int PROFESSION_ROW_H = 30;
    private static final int FULL_PROFESSION_ROWS = 5;
    private static final int PROFESSION_LIST_Y = 115;
    /** A job's page top, its "< Professions" link. */
    private static final int PAGE_Y = 88;
    /** The Miner page: its explanation card, and the control row below it (from the page's top). */
    private static final int MINER_CARD_H = 50;
    private static final int MINER_CONTROL_Y = 40 + MINER_CARD_H + 6;
    /** A defender job's page: how it answers fights, and the most of it on one monster. */
    private static final int DEFENDER_ANSWER_Y = MINER_CONTROL_Y;
    private static final int DEFENDER_MOST_Y = DEFENDER_ANSWER_Y + RULE_ROW_H + 2;

    /**
     * Professions with a setting for the whole village, and their icons: the
     * only ones this page lists. Every profession used to be listed, and all
     * but the Miner just said their settings were per villager.
     */
    private static final Map<String, String> VILLAGE_WIDE_PROFESSIONS = Map.of(
            "Miner", DynamicVillages.MODID + ":miner_token",
            "Guard", DynamicVillages.MODID + ":guard_token",
            "Captain", DynamicVillages.MODID + ":captain_token",
            "Archer", DynamicVillages.MODID + ":archer_token",
            "Mage", DynamicVillages.MODID + ":mage_token");

    private final SettingsTab settings;
    private int listScroll;
    private boolean minerOpen;
    /** The defender job whose page is open, or null. */
    @Nullable private DefenderTeamRules.Role defenderRoleOpen;

    /** The job list (its thin bar sits inside the list's right edge). */
    final ScrollList list = new ScrollList() {
        @Override public boolean shown() {
            return screen.tab() == MayorStatsScreen.Tab.SETTINGS
                    && settings.section() == SettingsTab.Section.PROFESSIONS && !subPageOpen();
        }
        @Override public int x() { return left() + CONTENT_X + 6; }
        @Override public int y() { return top() + PROFESSION_LIST_Y; }
        @Override public int width() { return innerW(); }
        @Override public int height() { return listH(); }
        @Override public boolean sideBar() { return false; }
        @Override public int scroll() { return listScroll; }
        @Override public void setScroll(int value) { listScroll = value; }
        @Override public int maxScroll() { return Math.max(0, professionSummaries().size() - visibleRows()); }
        @Override public int thumbHeight() { return Math.max(16, (listH() - 8) / (maxScroll() + 1)); }
    };

    ProfessionSettingsPage(MayorStatsScreen screen, SettingsTab settings) {
        super(screen);
        this.settings = settings;
    }

    /** The Miner's or a defender job's page is open. */
    boolean subPageOpen() {
        return minerOpen || defenderRoleOpen != null;
    }

    /** Back to the list (the Village rules button shuts them too). */
    void closeSubPages() {
        minerOpen = false;
        defenderRoleOpen = null;
    }

    // ---------------------------------------------------------------------
    // Fitting small screens
    // ---------------------------------------------------------------------

    private int visibleRows() {
        return FULL_PROFESSION_ROWS - screen.rowsLost(PROFESSION_ROW_H, FULL_PROFESSION_ROWS, 1);
    }

    private int listH() {
        return INFO_BOTTOM - PROFESSION_LIST_Y - (FULL_PROFESSION_ROWS - visibleRows()) * PROFESSION_ROW_H;
    }

    /** How much shorter the window is drawn while the list shows. */
    int listCut() {
        return (FULL_PROFESSION_ROWS - visibleRows()) * PROFESSION_ROW_H;
    }

    // ---------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------

    private List<ProfessionSummary> professionSummaries() {
        List<ProfessionSummary> entries = new ArrayList<>();
        for (Map.Entry<String, String> profession : VILLAGE_WIDE_PROFESSIONS.entrySet()) {
            int count = (int) menu.snapshot().villagers().stream()
                    .filter(villager -> villager.profession().equalsIgnoreCase(profession.getKey()))
                    .count();
            entries.add(new ProfessionSummary(profession.getKey(), profession.getValue(), count));
        }
        entries.sort(Comparator.comparing(ProfessionSummary::name, String.CASE_INSENSITIVE_ORDER));
        return entries;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top() + PAGE_Y;
        int width = innerW();
        if (defenderRoleOpen != null) {
            renderDefenderRolePage(graphics, defenderRoleOpen, mouseX, mouseY);
            return;
        }
        if (minerOpen) {
            renderMinerPage(graphics, mouseX, mouseY);
            return;
        }
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.settings_for_the_whole_village", "Settings for the whole village"), x, y, GOLD, false);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.one_villager_s_own_settings_right", "One villager's own settings: right click it."),
                x, y + 12, MUTED, false);

        List<ProfessionSummary> entries = professionSummaries();
        int listY = top() + PROFESSION_LIST_Y;
        int listH = listH();
        drawListFrame(graphics, x, listY, width, listH);
        int maxScroll = Math.max(0, entries.size() - visibleRows());
        listScroll = Math.min(listScroll, maxScroll);
        graphics.enableScissor(x + 1, listY + 1, x + width - 1, listY + listH - 1);
        for (int row = 0; row < visibleRows(); row++) {
            int index = listScroll + row;
            if (index >= entries.size()) break;
            ProfessionSummary entry = entries.get(index);
            int rowY = listY + row * PROFESSION_ROW_H;
            boolean hovered = inside(mouseX, mouseY, x + 1, rowY + 1, width - 2, PROFESSION_ROW_H - 1);
            graphics.fill(x + 1, rowY + 1, x + width - 1, rowY + PROFESSION_ROW_H - 1,
                    hovered ? ROW_HOVER : ROW);
            graphics.renderFakeItem(icon(entry.iconItemId()), x + 7, rowY + 8);
            graphics.drawString(font, professionName(entry.name()), x + 31, rowY + 5, TEXT, false);
            String count = entry.count() == 1 ? Tr.s("screen.dynamicvillages.mayor.one_villager", "1 villager") : Tr.s("screen.dynamicvillages.mayor.n_villagers", "%s villagers", entry.count());
            graphics.drawString(font, count, x + width - 10 - font.width(count), rowY + 5, GOLD, false);
            graphics.drawString(font, entry.name().equals("Miner")
                            ? Tr.s("screen.dynamicvillages.mayor.professions.miner", "Longest side tunnel in any mine") + "  >"
                            : DefenderTeamRules.Role.byLabel(entry.name()) != null
                            ? Tr.s("screen.dynamicvillages.mayor.professions.defender", "How they answer fights") + "  >"
                            : Tr.s("screen.dynamicvillages.mayor.professions.other", "Settings for the whole village") + "  >",
                    x + 31, rowY + 18, MUTED, false);
        }
        graphics.disableScissor();
        if (entries.isEmpty()) {
            drawEmpty(graphics, x, listY, width, listH, Tr.s("screen.dynamicvillages.mayor.professions.none", "No professions assigned yet"));
        }
        if (maxScroll > 0) drawScrollbar(graphics, x + width - 5, listY + 4,
                listH - 8, listScroll, maxScroll);
    }

    private void renderMinerPage(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top() + PAGE_Y;
        int width = innerW();
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.professions", "< Professions"), x, y, GOLD, false);
        graphics.drawString(font, Profession.MINER.shownName(), x, y + 23, TEXT, false);
        // Wrapped, not cut short: the card used to keep room for a button it
        // does not have, and its one line of explanation ended in "...".
        drawWrappedCard(graphics, x, y + 40, width, MINER_CARD_H, Items.IRON_PICKAXE,
                Tr.s("screen.dynamicvillages.mayor.miner.side_tunnels", "Side tunnel length"),
                Tr.s("screen.dynamicvillages.mayor.miner.side_tunnels.desc", "Miners dig a short side tunnel every 3 blocks along a mine's main tunnel, to the left and right. This sets the longest they get."));
        int value = menu.snapshot().minerSideBranchDistance();
        int controlY = y + MINER_CONTROL_Y;
        graphics.fill(x, controlY, x + width, controlY + 32, PANEL_BORDER);
        graphics.fill(x + 1, controlY + 1, x + width - 1, controlY + 31, ROW);
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.longest_side_tunnel", "Longest side tunnel"), x + 9, controlY + 12, TEXT, false);
        drawSegment(graphics, x + width - 116, controlY + 7, 25,
                false, "-", mouseX, mouseY);
        String amount = value == 1 ? Tr.s("screen.dynamicvillages.mayor.miner.one_block", "1 block") : Tr.s("screen.dynamicvillages.mayor.miner.blocks", "%s blocks", value);
        graphics.drawString(font, amount, x + width - 83, controlY + 12, GOLD, false);
        drawSegment(graphics, x + width - 27, controlY + 7, 25,
                false, "+", mouseX, mouseY);
        int noteY = controlY + 40;
        for (FormattedCharSequence line : font.split(Tr.c("screen.dynamicvillages.mayor.miner.note", "0 means no side tunnels. Two mines close together split the rock between them, so their side tunnels may stop early."),
                width - 4)) {
            graphics.drawString(font, line, x + 2, noteY, MUTED, false);
            noteY += 10;
        }
        noteY += 4;
        for (FormattedCharSequence line : font.split(Tr.c("screen.dynamicvillages.mayor.miner.own_mine",
                        "No mine digs longer side tunnels than this. To give one mine shorter ones, right-click its token, or use Manage mine under Buildings."),
                width - 4)) {
            graphics.drawString(font, line, x + 2, noteY, MUTED, false);
            noteY += 10;
        }
    }

    private void renderDefenderRolePage(GuiGraphics graphics, DefenderTeamRules.Role role, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int y = top() + PAGE_Y;
        int width = innerW();
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.professions", "< Professions"), x, y, GOLD, false);
        graphics.drawString(font, role.profession().shownName(), x, y + 23, TEXT, false);
        String plural = rolePlural(role);
        drawWrappedCard(graphics, x, y + 40, width, MINER_CARD_H,
                role == DefenderTeamRules.Role.ARCHER ? Items.BOW
                        : role == DefenderTeamRules.Role.MAGE ? Items.BLAZE_ROD : Items.IRON_SWORD,
                Tr.s("screen.dynamicvillages.mayor.role.fighting", "Fighting monsters"),
                Tr.s("screen.dynamicvillages.mayor.role.fighting.desc", "Which %s go when a monster comes. The closest defenders go, as many as Defenders per monster in Village rules says. Every team gets a guard or captain.", plural));
        DefenderTeamRules teams = menu.snapshot().defenderTeams();
        DefenderTeamRules.RoleRule rule = teams.role(role);
        drawRuleRow(graphics, x, y + DEFENDER_ANSWER_Y, width, Items.BELL, Tr.s("screen.dynamicvillages.mayor.role.answers", "Answers fights"),
                switch (rule.answer()) {
                    case CLOSEST -> Tr.s("screen.dynamicvillages.mayor.goes_when_it_is_one_of", "Goes when it is one of the closest");
                    case EVERY_FIGHT -> Tr.s("screen.dynamicvillages.mayor.goes_to_every_fight_in_the", "Goes to every fight in the village");
                    case NEARBY_ONLY -> Tr.s("screen.dynamicvillages.mayor.only_monsters_close_by_or_hitting", "Only monsters close by or hitting it");
                }, ruleWord(rule.answer().label()), rule.answer() != DefenderTeamRules.Answer.NEARBY_ONLY, mouseX, mouseY);
        drawRuleRow(graphics, x, y + DEFENDER_MOST_Y, width, Items.ZOMBIE_HEAD, Tr.s("screen.dynamicvillages.mayor.role.most", "Most on one monster"),
                rule.mostPerMonster() <= 0 ? Tr.s("screen.dynamicvillages.mayor.role.most.any", "As many as the team needs")
                        : Tr.s("screen.dynamicvillages.mayor.role.most.at_most", "At most %1$s %2$s on one monster", rule.mostPerMonster(), plural),
                ruleWord(rule.mostLabel()), rule.mostPerMonster() <= 0, mouseX, mouseY);
        int noteY = y + DEFENDER_MOST_Y + RULE_ROW_H + 6;
        String note = teams.teamSize() == DefenderTeamRules.TeamSize.EVERYONE
                ? Tr.s("screen.dynamicvillages.mayor.role.note.everyone", "Defenders per monster is set to Everyone, so every defender goes to every fight.")
                : Tr.s("screen.dynamicvillages.mayor.role.note", "A %s always fights a monster that hits it or comes close, whatever is set here.", role.profession().shownName().toLowerCase(Locale.ROOT));
        for (FormattedCharSequence line : font.split(Component.literal(note), width - 4)) {
            graphics.drawString(font, line, x + 2, noteY, MUTED, false);
            noteY += 10;
        }
        if (inside(mouseX, mouseY, x, y + DEFENDER_ANSWER_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.answers", "Closest: goes when it is one of the closest.\nEvery fight: goes to every monster in town.\nNearby only: never sent across the village.\nRight click to go back one."))));
        } else if (inside(mouseX, mouseY, x, y + DEFENDER_MOST_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.most", "The most %s sent to one monster.\nOther jobs fill the rest of the team.\nNo limit: as many as the team needs.\nRight click to go back one.", plural))));
        }
    }

    /** A card whose explanation wraps onto as many lines as fit, instead of ending in "...". */
    private void drawWrappedCard(GuiGraphics graphics, int x, int y, int width, int height, Item icon,
                                 String title, String description) {
        graphics.fill(x, y, x + width, y + height, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, ROW);
        graphics.renderFakeItem(new ItemStack(icon), x + 8, y + (height - 16) / 2);
        graphics.drawString(font, title, x + 31, y + 6, TEXT, false);
        int lineY = y + 17;
        for (FormattedCharSequence line : font.split(Component.literal(description), width - 39)) {
            if (lineY + 9 > y + height - 2) break;
            graphics.drawString(font, line, x + 31, lineY, MUTED, false);
            lineY += 10;
        }
    }

    /** The thin bar inside the list's right edge. */
    private static void drawScrollbar(GuiGraphics graphics, int x, int y, int height,
                                      int scroll, int maxScroll) {
        graphics.fill(x, y, x + 3, y + height, 0xFF080808);
        int thumbH = Math.max(16, height / (maxScroll + 1));
        int thumbY = y + (height - thumbH) * scroll / maxScroll;
        graphics.fill(x, thumbY, x + 3, thumbY + thumbH, 0xFF858585);
    }

    /** A job's English name from the server's list ("Miner") in the player's language. */
    private static String professionName(String english) {
        for (Profession profession : Profession.values()) {
            if (profession.displayName().equalsIgnoreCase(english)) return profession.shownName();
        }
        return english;
    }

    private static String rolePlural(DefenderTeamRules.Role role) {
        return switch (role) {
            case GUARD -> Tr.s("screen.dynamicvillages.mayor.role.guards", "guards");
            case CAPTAIN -> Tr.s("screen.dynamicvillages.mayor.role.captains", "captains");
            case ARCHER -> Tr.s("screen.dynamicvillages.mayor.role.archers", "archers");
            case MAGE -> Tr.s("screen.dynamicvillages.mayor.role.mages", "mages");
        };
    }

    // ---------------------------------------------------------------------
    // Clicks
    // ---------------------------------------------------------------------

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentX = left() + CONTENT_X + 6;
        int y = top();
        if (defenderRoleOpen != null) {
            int detailY = y + PAGE_Y;
            if (inside(mouseX, mouseY, contentX, detailY, 85, 15)) {
                defenderRoleOpen = null;
                return true;
            }
            int buttonX = contentX + innerW() - RULE_TOGGLE_W - 6;
            DefenderTeamRules teams = menu.snapshot().defenderTeams();
            DefenderTeamRules.RoleRule rule = teams.role(defenderRoleOpen);
            if (inside(mouseX, mouseY, buttonX, detailY + DEFENDER_ANSWER_Y + 5, RULE_TOGGLE_W, 18)) {
                settings.sendDefenderTeams(teams.withRole(defenderRoleOpen, rule.withAnswer(
                        button == 1 ? rule.answer().previous() : rule.answer().next())));
                return true;
            }
            if (inside(mouseX, mouseY, buttonX, detailY + DEFENDER_MOST_Y + 5, RULE_TOGGLE_W, 18)) {
                int steps = DefenderTeamRules.MAX_PER_MONSTER_LIMIT + 1;
                int next = Math.floorMod(rule.mostPerMonster() + (button == 1 ? -1 : 1), steps);
                settings.sendDefenderTeams(teams.withRole(defenderRoleOpen, rule.withMostPerMonster(next)));
                return true;
            }
            return false;
        }
        if (minerOpen) {
            int detailY = y + PAGE_Y;
            int width = innerW();
            if (inside(mouseX, mouseY, contentX, detailY, 85, 15)) {
                minerOpen = false;
                return true;
            }
            int controlY = detailY + MINER_CONTROL_Y;
            int delta = inside(mouseX, mouseY, contentX + width - 116,
                    controlY + 7, 25, 18) ? -1
                    : inside(mouseX, mouseY, contentX + width - 27,
                    controlY + 7, 25, 18) ? 1 : 0;
            if (delta != 0) {
                int current = menu.snapshot().minerSideBranchDistance();
                int next = Math.max(0, Math.min(
                        MinerSettingsSchema.MAX_SIDE_BRANCH_DISTANCE, current + delta));
                if (next != current) {
                    menu.setMinerSideBranchDistanceLocally(next);
                    PacketDistributor.sendToServer(
                            new MayorTradePayloads.SetMinerBranchDistance(menu.mayorId(), next));
                }
                return true;
            }
            return false;
        }
        if (inside(mouseX, mouseY, contentX, y + PROFESSION_LIST_Y,
                innerW(), listH())) {
            int row = ((int) mouseY - (y + PROFESSION_LIST_Y)) / PROFESSION_ROW_H;
            int index = listScroll + row;
            List<ProfessionSummary> entries = professionSummaries();
            if (row >= 0 && row < visibleRows()
                    && index >= 0 && index < entries.size()) {
                String name = entries.get(index).name();
                if (name.equals("Miner")) minerOpen = true;
                else defenderRoleOpen = DefenderTeamRules.Role.byLabel(name);
            }
            return true;
        }
        return false;
    }
}
