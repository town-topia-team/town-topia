package com.alex.test.village.villagers.child;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.villagers.core.Profession;

/**
 * Assignment into the Child pseudo-profession.
 *
 * <p>Deliberately has no token: children are born (or spawned by tests), never
 * appointed. An adult cannot become a child, which is why there is no
 * {@code isChildToken}.
 */
public final class ChildAssignmentHandler {

    private ChildAssignmentHandler() {}

    public static boolean assignFirstTime(NpcEntity villager) {
        if (villager.getProfession() != Profession.NONE) return false;
        villager.setProfession(Profession.CHILD);
        return villager.getProfession() == Profession.CHILD;
    }
}
