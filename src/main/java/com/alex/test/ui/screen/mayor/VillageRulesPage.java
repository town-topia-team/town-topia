package com.alex.test.ui.screen.mayor;

import com.alex.test.Tr;
import com.alex.test.client.ClientRaidInfo;
import com.alex.test.network.MayorTradePayloads;
import com.alex.test.network.RaidPayloads;
import com.alex.test.town.MayorShop;
import com.alex.test.village.DefenderTeamRules;
import com.alex.test.village.VillageRules;
import com.alex.test.village.chat.TownAlertLevel;
import com.alex.test.village.raid.RaidFrequency;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

import static com.alex.test.ui.screen.mayor.MayorLook.*;

/**
 * Settings > Village rules: one row per rule, its button on the right. The Raids row opens its own page
 * ({@link SettingsTab#openRaids}). Performance is its own tab since 10-22 ({@link PerformanceTab}).
 */
final class VillageRulesPage extends MayorPart {

    // Rows measured from the top of the screen. Kept tight;
    // the rows run past INFO_BOTTOM, so the page scrolls. "Villager limbs" moved to Performance > This computer (10-22).
    private static final int RULE_INFO_H = 23;
    private static final int RULE_BIRTHS_Y = 99;
    private static final int RULE_MARKET_Y = RULE_BIRTHS_Y + RULE_ROW_H + 2;
    private static final int RULE_RAIDS_Y = RULE_MARKET_Y + RULE_INFO_H + 2;
    private static final int RULE_BELL_HEADING_Y = RULE_RAIDS_Y + RULE_ROW_H + 4;
    private static final int RULE_PLAYER_HURT_Y = RULE_BELL_HEADING_Y + 11;
    private static final int RULE_ALARM_Y = RULE_PLAYER_HURT_Y + RULE_ROW_H + 2;
    private static final int RULE_DEFENDERS_HEADING_Y = RULE_ALARM_Y + RULE_ROW_H + 4;
    private static final int RULE_TEAM_SIZE_Y = RULE_DEFENDERS_HEADING_Y + 11;
    private static final int RULE_RESTING_Y = RULE_TEAM_SIZE_Y + RULE_ROW_H + 2;
    private static final int RULE_ALERTS_HEADING_Y = RULE_RESTING_Y + RULE_ROW_H + 4;
    private static final int RULE_ALERTS_Y = RULE_ALERTS_HEADING_Y + 11;
    private static final int RULE_EVERY_INJURY_Y = RULE_ALERTS_Y + RULE_ROW_H + 2;
    static final int RULES_BOTTOM = RULE_EVERY_INJURY_Y + RULE_ROW_H;
    /** Widest line of the Raids row's tooltip status text (GUI pixels). */
    private static final int RAIDS_TIP_STATUS_W = 180;

    private final SettingsTab settings;

    VillageRulesPage(MayorStatsScreen screen, SettingsTab settings) {
        super(screen);
        this.settings = settings;
    }

    void render(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = left() + CONTENT_X + 6;
        int top = top();
        int width = innerW();
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.village", "Village"), x, top + RULE_BIRTHS_Y - 11, GOLD, false);
        boolean births = menu.snapshot().birthsEnabled();
        drawRuleRow(graphics, x, top + RULE_BIRTHS_Y, width, Items.RED_BED, Tr.s("screen.dynamicvillages.mayor.rule.births", "Allow births"),
                Tr.s("screen.dynamicvillages.mayor.rule.births.desc", "Villagers may grow the village naturally"), ruleWord(births ? "On" : "Off"), births, mouseX, mouseY);
        drawCompactInfoCard(graphics, x, top + RULE_MARKET_Y, width, Items.EMERALD,
                Tr.s("screen.dynamicvillages.mayor.rule.market", "Dynamic market"), Tr.s("screen.dynamicvillages.mayor.rule.market.desc", "Tokens x%1$s for size, +%2$s%% per one you own", String.format(java.util.Locale.ROOT, "%.2f", Math.min(MayorShop.MAX_PRICE_MULTIPLIER,
                        MayorShop.multiplierAt(menu.snapshot().villageSize()))), Math.round(MayorShop.PRICE_GROWTH_PER_OWNED * 100)));
        // How often raids come (RaidPayloads asks the server; the line says how things stand).
        RaidFrequency raids = ClientRaidInfo.frequency(menu.mayorId());
        drawRuleRow(graphics, x, top + RULE_RAIDS_Y, width, Items.CROSSBOW, Tr.s("screen.dynamicvillages.mayor.raids", "Raids"),
                ClientRaidInfo.status(menu.mayorId()), ruleWord(raids.label()),
                raids != RaidFrequency.OFF, mouseX, mouseY);

        // The watchtower bell's two rules (see VillageRules).
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.watchtower_bell", "Watchtower bell"), x, top + RULE_BELL_HEADING_Y, GOLD, false);
        VillageRules.PlayerHurtBell hurtRule = VillageRules.PlayerHurtBell.byIndex(menu.snapshot().playerHurtBell());
        drawRuleRow(graphics, x, top + RULE_PLAYER_HURT_Y, width, Items.BELL, Tr.s("screen.dynamicvillages.mayor.rule.player_hurt", "Your injuries ring the bell"),
                switch (hurtRule) {
                    case NEVER -> Tr.s("screen.dynamicvillages.mayor.only_hurt_villagers_count", "Only hurt villagers count");
                    case BADLY_HURT -> Tr.s("screen.dynamicvillages.mayor.also_when_you_drop_to_4", "Also when you drop to 4 hearts");
                    case ALWAYS -> Tr.s("screen.dynamicvillages.mayor.also_every_time_a_monster_hits", "Also every time a monster hits you");
                }, ruleWord(hurtRule.label()), hurtRule != VillageRules.PlayerHurtBell.NEVER, mouseX, mouseY);
        boolean endsWhenClear = menu.snapshot().towerAlarmEndsWhenClear();
        drawRuleRow(graphics, x, top + RULE_ALARM_Y, width, Items.CLOCK, Tr.s("screen.dynamicvillages.mayor.rule.alarm", "Alarm ends when clear"),
                endsWhenClear ? Tr.s("screen.dynamicvillages.mayor.rule.alarm.on", "Stops 30 s after the village is clear")
                        : Tr.s("screen.dynamicvillages.mayor.rule.alarm.off", "Lasts while any monster is near"),
                ruleWord(endsWhenClear ? "On" : "Off"), endsWhenClear, mouseX, mouseY);

        // How the defenders split up to fight (DefenderTeamRules); each job's own rule is under Professions.
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.defenders", "Defenders"), x, top + RULE_DEFENDERS_HEADING_Y, GOLD, false);
        DefenderTeamRules teams = menu.snapshot().defenderTeams();
        drawRuleRow(graphics, x, top + RULE_TEAM_SIZE_Y, width, Items.IRON_SWORD, Tr.s("screen.dynamicvillages.mayor.rule.team_size", "Defenders per monster"),
                switch (teams.teamSize()) {
                    case FEW -> Tr.s("screen.dynamicvillages.mayor.1_for_a_zombie_4_for", "1 for a zombie, 4 for a ravager");
                    case NORMAL -> Tr.s("screen.dynamicvillages.mayor.2_for_a_zombie_5_for", "2 for a zombie, 5 for a ravager");
                    case MANY -> Tr.s("screen.dynamicvillages.mayor.3_for_a_zombie_6_for", "3 for a zombie, 6 for a ravager");
                    case EVERYONE -> Tr.s("screen.dynamicvillages.mayor.every_defender_goes_to_every_fight", "Every defender goes to every fight");
                }, ruleWord(teams.teamSize().label()), teams.teamSize() != DefenderTeamRules.TeamSize.EVERYONE,
                mouseX, mouseY);
        drawRuleRow(graphics, x, top + RULE_RESTING_Y, width, Items.WHITE_BED, Tr.s("screen.dynamicvillages.mayor.rule.resting", "Resting defenders"),
                switch (teams.resting()) {
                    case WHEN_SHORT -> Tr.s("screen.dynamicvillages.mayor.come_out_when_too_few_are", "Come out when too few are on duty");
                    case EVERY_FIGHT -> Tr.s("screen.dynamicvillages.mayor.come_out_for_every_monster", "Come out for every monster");
                    case BELL_ONLY -> Tr.s("screen.dynamicvillages.mayor.only_the_bell_or_a_monster", "Only the bell or a monster close by");
                }, ruleWord(teams.resting().label()), teams.resting() != DefenderTeamRules.RestingCallOut.EVERY_FIGHT,
                mouseX, mouseY);

        // The player's own setting, not the village's (TownAlertLevel).
        graphics.drawString(font, Tr.s("screen.dynamicvillages.mayor.just_for_you", "Just for you"), x, top + RULE_ALERTS_HEADING_Y, GOLD, false);
        TownAlertLevel alerts = TownAlertLevel.byIndex(menu.townAlerts());
        drawRuleRow(graphics, x, top + RULE_ALERTS_Y, width, Items.GOAT_HORN, Tr.s("screen.dynamicvillages.mayor.rule.alerts", "Town alerts"),
                alertDescription(alerts), ruleWord(alerts.label()), alerts != TownAlertLevel.OFF, mouseX, mouseY);
        boolean everyInjury = menu.everyInjury();
        drawRuleRow(graphics, x, top + RULE_EVERY_INJURY_Y, width, Items.CACTUS, Tr.s("screen.dynamicvillages.mayor.rule.every_injury", "No injury cooldown"),
                Tr.s("screen.dynamicvillages.mayor.rule.every_injury.desc", "Every hurt villager, may spam chat"), ruleWord(everyInjury ? "On" : "Off"), everyInjury, mouseX, mouseY);

        // What each bell rule means, on hover: one line under the title is too short to say it.
        // Deferred: on a small screen this page is drawn clipped, which would cut the box.
        if (inside(mouseX, mouseY, x, top + RULE_RAIDS_Y, width, RULE_ROW_H)) {
            // The status line can be long: wrapped narrow, so the box stays inside the window.
            List<FormattedCharSequence> tip = new ArrayList<>(
                    font.split(Component.literal(ClientRaidInfo.status(menu.mayorId())), RAIDS_TIP_STATUS_W));
            tip.addAll(tooltipLines(join(
                    linesOf(Tr.s("screen.dynamicvillages.mayor.tip.raids", "How often raiders come on their own.\nOff: they never come by themselves.\nRare: about every 9 to 14 days.\nNormal: about every 5 to 8 days.\nOften: about every 3 to 5 days.")),
                    new Component[] {Component.literal("")},
                    linesOf(Tr.s("screen.dynamicvillages.mayor.tip.raids.omen", "Bad Omen always brings a raid,\neven when this is set to Off."), ChatFormatting.GOLD),
                    linesOf(Tr.s("screen.dynamicvillages.mayor.tip.raids.click", "Click the row for past raids and fame."), ChatFormatting.GRAY))));
            screen.setDeferredTooltip(tip);
        } else if (inside(mouseX, mouseY, x, top + RULE_PLAYER_HURT_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.player_hurt", "When a monster inside the village\nhurts you, does the watchtower\nlookout ring the bell?\nBadly hurt: at 4 hearts or less."))));
        } else if (inside(mouseX, mouseY, x, top + RULE_ALARM_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.alarm", "On: a lookout's alarm stops 30 s\nafter the last monster inside the\nvillage is gone.\nOff: it lasts while any monster is near."))));
        } else if (inside(mouseX, mouseY, x, top + RULE_TEAM_SIZE_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(join(
                    linesOf(Tr.s("screen.dynamicvillages.mayor.tip.team_size", "How many defenders go to one monster.\nThe closest go, the rest keep patrolling.\nEveryone: all defenders go to every fight.")),
                    linesOf(Tr.s("screen.dynamicvillages.mayor.tip.team_size.jobs", "Each job has its own rule under Professions."), ChatFormatting.GRAY))));
        } else if (inside(mouseX, mouseY, x, top + RULE_RESTING_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.resting", "Do defenders who are off duty or asleep\ncome out for a fight?\nWhen short: only if too few are on duty.\nBell only: the bell or a monster close by."))));
        } else if (inside(mouseX, mouseY, x, top + RULE_ALERTS_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.alerts", "Just for you, not other players.\nAll: every hit and sighting.\nNormal: fight bar plus a few lines.\nDeaths: fight bar plus deaths.\nOff: no bar and no alerts."))));
        } else if (inside(mouseX, mouseY, x, top + RULE_EVERY_INJURY_Y, width, RULE_ROW_H)) {
            screen.setDeferredTooltip(tooltipLines(linesOf(Tr.s("screen.dynamicvillages.mayor.tip.every_injury", "Hurt villagers are already told in chat,\nwith a cooldown so chat stays clear.\nOn: every single time, no cooldown.\nCan spam chat. Monsters not included."))));
        }
    }

    private static String alertDescription(TownAlertLevel alerts) {
        return switch (alerts) {
            case ALL -> Tr.s("screen.dynamicvillages.mayor.alerts.all", "Every hit and sighting in chat");
            case NORMAL -> Tr.s("screen.dynamicvillages.mayor.alerts.normal", "Fight bar, start, deaths and end");
            case DEATHS -> Tr.s("screen.dynamicvillages.mayor.alerts.deaths", "Fight bar and deaths only");
            case OFF -> Tr.s("screen.dynamicvillages.mayor.alerts.off", "No fight bar and no alerts");
        };
    }

    /** An information line, kept low so the village rules fit above it. */
    private void drawCompactInfoCard(GuiGraphics graphics, int x, int y, int width, Item icon,
                                     String title, String description) {
        graphics.fill(x, y, x + width, y + RULE_INFO_H, PANEL_BORDER);
        graphics.fill(x + 1, y + 1, x + width - 1, y + RULE_INFO_H - 1, ROW);
        graphics.renderFakeItem(new ItemStack(icon), x + 6, y + 3);
        graphics.drawString(font, title, x + 28, y + 3, TEXT, false);
        graphics.drawString(font, trim(description, width - 34), x + 28, y + 13, MUTED, false);
    }

    boolean mouseClicked(double mouseX, double mouseY, int button) {
        int contentX = left() + CONTENT_X + 6;
        int y = top();
        // The buttons sit at the right end of each rule row (drawRuleRow).
        int buttonX = contentX + innerW() - RULE_TOGGLE_W - 6;
        if (inside(mouseX, mouseY, buttonX, y + RULE_BIRTHS_Y + 5, RULE_TOGGLE_W, 18)) {
            boolean next = !menu.snapshot().birthsEnabled();
            PacketDistributor.sendToServer(new MayorTradePayloads.SetBirths(menu.mayorId(), next));
            menu.setBirthsEnabledLocally(next);
            return true;
        }
        if (inside(mouseX, mouseY, buttonX, y + RULE_RAIDS_Y + 5, RULE_TOGGLE_W, 18)) {
            // Not until the server has said the real setting: a click on the
            // placeholder would overwrite it with the default's neighbour.
            if (!ClientRaidInfo.isFor(menu.mayorId())) return true;
            RaidFrequency now = ClientRaidInfo.frequency(menu.mayorId());
            RaidFrequency next = button == 1 ? now.previous() : now.next();
            PacketDistributor.sendToServer(new RaidPayloads.SetFrequency(
                    menu.mayorId(), next.ordinal()));
            ClientRaidInfo.setFrequencyLocally(menu.mayorId(), next);
            return true;
        }
        // The rest of the Raids row opens the Raids page.
        if (inside(mouseX, mouseY, contentX, y + RULE_RAIDS_Y, innerW(), RULE_ROW_H)) {
            settings.openRaids();
            return true;
        }
        int hurtRule = menu.snapshot().playerHurtBell();
        boolean endsWhenClear = menu.snapshot().towerAlarmEndsWhenClear();
        if (inside(mouseX, mouseY, buttonX, y + RULE_PLAYER_HURT_Y + 5, RULE_TOGGLE_W, 18)) {
            int next = VillageRules.PlayerHurtBell.byIndex(hurtRule).next().ordinal();
            PacketDistributor.sendToServer(new MayorTradePayloads.SetBellRules(
                    menu.mayorId(), next, endsWhenClear));
            menu.setBellRulesLocally(next, endsWhenClear);
            return true;
        }
        if (inside(mouseX, mouseY, buttonX, y + RULE_ALARM_Y + 5, RULE_TOGGLE_W, 18)) {
            PacketDistributor.sendToServer(new MayorTradePayloads.SetBellRules(
                    menu.mayorId(), hurtRule, !endsWhenClear));
            menu.setBellRulesLocally(hurtRule, !endsWhenClear);
            return true;
        }
        DefenderTeamRules teams = menu.snapshot().defenderTeams();
        if (inside(mouseX, mouseY, buttonX, y + RULE_TEAM_SIZE_Y + 5, RULE_TOGGLE_W, 18)) {
            settings.sendDefenderTeams(teams.withTeamSize(button == 1
                    ? teams.teamSize().previous() : teams.teamSize().next()));
            return true;
        }
        if (inside(mouseX, mouseY, buttonX, y + RULE_RESTING_Y + 5, RULE_TOGGLE_W, 18)) {
            settings.sendDefenderTeams(teams.withResting(button == 1
                    ? teams.resting().previous() : teams.resting().next()));
            return true;
        }
        if (inside(mouseX, mouseY, buttonX, y + RULE_ALERTS_Y + 5, RULE_TOGGLE_W, 18)) {
            int next = TownAlertLevel.byIndex(menu.townAlerts()).next().ordinal();
            PacketDistributor.sendToServer(new MayorTradePayloads.SetTownAlerts(next));
            menu.setTownAlertsLocally(next);
            return true;
        }
        if (inside(mouseX, mouseY, buttonX, y + RULE_EVERY_INJURY_Y + 5, RULE_TOGGLE_W, 18)) {
            boolean next = !menu.everyInjury();
            PacketDistributor.sendToServer(new MayorTradePayloads.SetEveryInjuryAlerts(next));
            menu.setEveryInjuryLocally(next);
            return true;
        }
        return false;
    }
}
