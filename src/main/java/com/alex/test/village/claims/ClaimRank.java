package com.alex.test.village.claims;

import java.util.List;
import java.util.Locale;

/**
 * A player's standing in a claimed village (round 4). The owner is {@link VillageClaim#ownerId}; Officer, Friend and
 * Hostile are the ranks an owner hands out; everyone not listed is a Visitor. Ranks lock nothing yet: the land lock
 * (rounds 5-6) and window rights (round 7) will read them.
 */
public enum ClaimRank {
    OWNER, OFFICER, FRIEND, VISITOR, HOSTILE;

    /** The ranks a listed player can have, in the order the People page cycles through them. */
    public static final List<ClaimRank> LISTED = List.of(OFFICER, FRIEND, HOSTILE);

    public boolean isListed() {
        return LISTED.contains(this);
    }

    /** The next listed rank after this one, for the People page's rank button. */
    public ClaimRank nextListed() {
        int at = LISTED.indexOf(this);
        return LISTED.get((at + 1) % LISTED.size());
    }

    /** The name written into the world's save; never translated, never renamed. */
    public String saveName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** A saved rank back; an unknown word (a newer save) reads as Friend. */
    public static ClaimRank bySaveName(String saved) {
        for (ClaimRank rank : values()) {
            if (rank.saveName().equals(saved)) return rank;
        }
        return FRIEND;
    }

    public static ClaimRank byIndex(int index) {
        ClaimRank[] all = values();
        return index >= 0 && index < all.length ? all[index] : VISITOR;
    }
}
