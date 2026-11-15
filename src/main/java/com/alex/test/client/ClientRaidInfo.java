package com.alex.test.client;

import com.alex.test.Tr;
import com.alex.test.village.raid.RaidFrequency;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The raid setting, status line and raid history of the Mayor whose screen is
 * open, as the server last sent them ({@code RaidPayloads.Info}). Unknown
 * until the answer arrives: the screen then shows the default and
 * "Asking the Mayor...". The lines arrive as text components and are read
 * here in this computer's language.
 */
public final class ClientRaidInfo {

    private static int mayorId = -1;
    private static RaidFrequency frequency = RaidFrequency.DEFAULT;
    private static Component status = Component.empty();
    private static int fame;
    private static Component totals = Component.empty();
    private static int bestOmen;
    private static List<Component> history = List.of();

    private ClientRaidInfo() {}

    public static void set(int forMayor, int frequencyIndex, Component statusLine, int fameNow, Component totalsLine,
                           int bestOmenWon, List<Component> historyLines) {
        mayorId = forMayor;
        frequency = RaidFrequency.byIndex(frequencyIndex);
        status = statusLine == null ? Component.empty() : statusLine;
        fame = fameNow;
        totals = totalsLine == null ? Component.empty() : totalsLine;
        bestOmen = bestOmenWon;
        history = historyLines == null ? List.of() : List.copyOf(historyLines);
    }

    /**
     * The Mayor's screen opens: drop the last answer. Entity ids start again in each world, so an answer
     * from another world (or an earlier visit) could match this Mayor and show its raids until the new one came.
     */
    public static void forget() {
        set(-1, RaidFrequency.DEFAULT.ordinal(), Component.empty(), 0, Component.empty(), 0, List.of());
    }

    /** Shows a click at once, before the server's answer. */
    public static void setFrequencyLocally(int forMayor, RaidFrequency value) {
        if (forMayor != mayorId) return;
        frequency = value;
    }

    public static boolean isFor(int forMayor) {
        return mayorId == forMayor;
    }

    public static RaidFrequency frequency(int forMayor) {
        return forMayor == mayorId ? frequency : RaidFrequency.DEFAULT;
    }

    public static String status(int forMayor) {
        return forMayor == mayorId ? status.getString()
                : Tr.s("screen.dynamicvillages.mayor.raids.asking", "Asking the Mayor...");
    }

    public static int fame(int forMayor) {
        return forMayor == mayorId ? fame : 0;
    }

    public static String totals(int forMayor) {
        return forMayor == mayorId ? totals.getString() : "";
    }

    /** The strongest Bad Omen raid won here (1 to 5), 0 for none. */
    public static int bestOmen(int forMayor) {
        return forMayor == mayorId ? bestOmen : 0;
    }

    public static List<String> history(int forMayor) {
        return forMayor == mayorId ? history.stream().map(Component::getString).toList() : List.of();
    }
}
