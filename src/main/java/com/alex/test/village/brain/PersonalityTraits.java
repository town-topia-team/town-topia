package com.alex.test.village.brain;

import java.util.ArrayList;
import java.util.List;

/**
 * The personality a player can see: a label only for a value in the top or
 * bottom fifth of its range, so most villagers show one label or none and a
 * label always means something noticeable. Shown as badges on the villager
 * screen's portrait.
 */
public final class PersonalityTraits {

    public enum Trait {
        QUICK_HANDS("Quick hands", "Gets through each job step", "a bit faster than most."),
        SLOW_AND_STEADY("Slow and steady", "Takes a bit longer than most", "over each job step."),
        HARD_WORKER("Hard worker", "Picks work over a break", "more often than most."),
        EASYGOING("Easygoing", "Takes a break from work", "more often than most."),
        CHATTY("Chatty", "Looks for company more often", "and goes out more in the evenings."),
        KEEPS_TO_THEMSELVES("Keeps to themselves", "Looks for company less often", "and stays home more in the evenings.");

        public final String title;
        public final String line1;
        public final String line2;

        Trait(String title, String line1, String line2) {
            this.title = title;
            this.line1 = line1;
            this.line2 = line2;
        }

        public static Trait fromNetworkId(int id) {
            Trait[] all = values();
            return id >= 0 && id < all.length ? all[id] : null;
        }
    }

    /** Most labels one villager can show: one per value. */
    public static final int MAX = 3;

    // A fifth of each range from the Personality roll.
    static final float QUICK_HANDS_AT = 1.12f, SLOW_AND_STEADY_AT = 0.88f;   // workSpeed 0.80-1.20
    static final float HARD_WORKER_AT = 0.96f, EASYGOING_AT = 0.84f;         // workDrive 0.80-1.00
    static final float CHATTY_AT = 0.94f, KEEPS_TO_THEMSELVES_AT = 0.76f;    // sociability 0.70-1.00

    private PersonalityTraits() {}

    /** This personality's labels, in badge order: work speed, work drive, sociability. */
    public static List<Trait> of(Personality p) {
        List<Trait> traits = new ArrayList<>(MAX);
        if (p.workSpeed >= QUICK_HANDS_AT) traits.add(Trait.QUICK_HANDS);
        else if (p.workSpeed <= SLOW_AND_STEADY_AT) traits.add(Trait.SLOW_AND_STEADY);
        if (p.workDrive >= HARD_WORKER_AT) traits.add(Trait.HARD_WORKER);
        else if (p.workDrive <= EASYGOING_AT) traits.add(Trait.EASYGOING);
        if (p.sociability >= CHATTY_AT) traits.add(Trait.CHATTY);
        else if (p.sociability <= KEEPS_TO_THEMSELVES_AT) traits.add(Trait.KEEPS_TO_THEMSELVES);
        return traits;
    }
}
