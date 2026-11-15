package com.alex.test.client;

import com.alex.test.village.log.VillageLog;

import java.util.List;

/**
 * The village log of the Mayor whose window is open, as the server last sent it
 * ({@code VillageLogPayloads.Log}), oldest first. Unknown until the answer arrives.
 */
public final class ClientVillageLog {

    private static int mayorId = -1;
    /** The server's version of this list; -1 for none, so the next ask always gets an answer. */
    private static long version = -1;
    private static List<VillageLog.Entry> entries = List.of();

    private ClientVillageLog() {}

    public static void set(int forMayor, long newVersion, List<VillageLog.Entry> newEntries) {
        mayorId = forMayor;
        version = newVersion;
        entries = newEntries == null ? List.of() : List.copyOf(newEntries);
    }

    /**
     * The Mayor's window opens: drop the last answer. Entity ids start again in each world, so an answer
     * from another world (or an earlier visit) could match this Mayor.
     */
    public static void forget() {
        set(-1, -1, List.of());
    }

    public static boolean isFor(int forMayor) {
        return mayorId == forMayor;
    }

    /** What to tell the server we have: -1 when the list is for another Mayor (or none came yet). */
    public static long version(int forMayor) {
        return forMayor == mayorId ? version : -1;
    }

    public static List<VillageLog.Entry> entries(int forMayor) {
        return forMayor == mayorId ? entries : List.of();
    }
}
