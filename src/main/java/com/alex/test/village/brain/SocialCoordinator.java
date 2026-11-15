package com.alex.test.village.brain;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.ConversationScripts;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * Pairs eligible villagers into conversations.
 *
 * <p>Runs every {@link #SCAN_INTERVAL} ticks. A villager is eligible when:
 * <ul>
 *   <li>It is in {@link SocializeActivity} (via {@link LeisureActivity}), or</li>
 *   <li>It is in {@link WorkActivity} during its movement phase (walking to the work site), or</li>
 *   <li>It is settled in an {@link EveningActivity}: home, the tavern or a neighbour's.</li>
 * </ul>
 *
 * <p>Two eligible villagers standing well for it -- see
 * {@link ConverseActivity#wellPlaced} --
 * with a player within {@link #PLAYER_RANGE} are paired into a
 * {@link ConverseActivity}.
 * When either villager is socialising the conversation is flagged {@code noLimits}
 * so the daily social-action cap is not consumed.
 *
 * <p>Pairing is also where the conversation's content is decided: this is the
 * one place that holds both villagers at once, so it builds the
 * {@link ConversationScript} from their memories and professions and hands the
 * same script to both halves. The conversation's length then comes from the
 * script rather than a random roll, so it ends on its last line.
 */
public final class SocialCoordinator {

    private static final int    SCAN_INTERVAL = 40;
    private static final double PLAYER_RANGE  = 32.0;

    private int timer = 0;

    public void tick(VillageManager mgr, ServerLevel level) {
        if (++timer < SCAN_INTERVAL) return;
        timer = 0;

        List<VillagerModel> open = new ArrayList<>();
        for (VillagerModel m : mgr.getVillagers()) {
            if (m.puppet() != null && m.puppet().isAlive()
                    && m.brain().isOpenToConversation()) {
                open.add(m);
            }
        }
        if (open.size() < 2) return;

        // A visiting guest and its host come first.
        for (VillagerModel g : open) {
            if (!(g.brain().currentActivity() instanceof EveningActivity guest)
                    || guest.mode() != EveningActivity.Mode.VISIT) continue;
            for (VillagerModel h : open) {
                if (h == g || !(h.brain().currentActivity() instanceof EveningActivity host)
                        || host.mode() != EveningActivity.Mode.HOME || host.venue() != guest.venue()) continue;
                if (!ConverseActivity.wellPlaced(g.puppet(), h.puppet())) continue;
                startVisitChat(g, guest, h, level);
                return;
            }
        }

        for (int i = 0; i < open.size(); i++) {
            for (int j = i + 1; j < open.size(); j++) {
                VillagerModel a = open.get(i), b = open.get(j);
                NpcEntity na = a.puppet(), nb = b.puppet();
                if (!ConverseActivity.wellPlaced(na, nb)) continue;

                // Evening chats happen whether or not a player is near (they
                // only show once one is); other chats still need a player.
                boolean evening = isEvening(a) && isEvening(b);
                double mx = (na.getX() + nb.getX()) / 2;
                double my = (na.getY() + nb.getY()) / 2;
                double mz = (na.getZ() + nb.getZ()) / 2;
                if (!evening && !level.hasNearbyAlivePlayer(mx, my, mz, PLAYER_RANGE)) continue;

                // The exchange is scripted here, where both villagers' memories
                // and jobs are in hand, and both sides are handed the same
                // object; see ConversationScript.
                // Housemates, and two who already talked today, just carry on:
                // no opening hello and no wave.
                boolean familiar = familiar(mgr, a, b, level);
                ConversationScript script = ConversationScripts.between(
                        a.brain().memory(), na.getProfession(),
                        b.brain().memory(), nb.getProfession(),
                        level.getGameTime(), na.getRandom(), !familiar);

                int     duration   = script.durationTicks();
                boolean aNoLimits  = isSocializing(a);
                boolean bNoLimits  = isSocializing(b);

                ConverseActivity aSide = new ConverseActivity(nb, true,  duration, aNoLimits, false, script);
                ConverseActivity bSide = new ConverseActivity(na, false, duration, bNoLimits, false, script);
                if (familiar) {
                    aSide.noHello();
                    bSide.noHello();
                }
                if (evening) {
                    aSide.evening(false);
                    bSide.evening(false);
                }
                a.brain().forceActivity(aSide);
                b.brain().forceActivity(bSide);

                return; // one pair per scan
            }
        }
    }

    /**
     * A guest and its host: a visit chat about twice an ordinary one, opened
     * with both saying hello when nobody waved at the door, as long as the
     * host has that long before bed. Otherwise an ordinary evening chat.
     */
    static void startVisitChat(VillagerModel g, EveningActivity guest, VillagerModel h, ServerLevel level) {
        NpcEntity ng = g.puppet(), nh = h.puppet();
        boolean long_ = h.brain().hostTicksLeft(level.getDayTime()) >= VisitRules.VISIT_CHAT_TICKS;
        boolean greet = g.brain().visitChats() == 0 && !guest.greetedAtDoor();
        ConversationScript script = long_
                ? ConversationScripts.visit(g.brain().memory(), ng.getProfession(),
                        h.brain().memory(), nh.getProfession(), level.getGameTime(), ng.getRandom(), greet)
                : ConversationScripts.between(g.brain().memory(), ng.getProfession(),
                        h.brain().memory(), nh.getProfession(), level.getGameTime(), ng.getRandom());
        int duration = script.durationTicks();
        g.brain().forceActivity(new ConverseActivity(nh, true, duration, true, false, script).evening(long_));
        h.brain().forceActivity(new ConverseActivity(ng, false, duration, true, false, script).evening(long_));
    }

    /** Housemates (same home), or two villagers who already talked today. */
    private static boolean familiar(VillageManager mgr, VillagerModel a, VillagerModel b, ServerLevel level) {
        long day = EveningSchedule.dayIndex(level.getDayTime());
        if (a.brain().chattedTodayWith(b.npcId, day) || b.brain().chattedTodayWith(a.npcId, day)) return true;
        com.alex.test.village.building.Building homeA = mgr.getAssignedBedBuilding(a.npcId);
        com.alex.test.village.building.Building homeB = mgr.getAssignedBedBuilding(b.npcId);
        return homeA != null && homeB != null && homeA.getItemFrameId().equals(homeB.getItemFrameId());
    }

    private static boolean isEvening(VillagerModel m) {
        return m.brain().currentActivity() instanceof EveningActivity;
    }

    /**
     * True if the villager is socialising: the Socialize sub-phase of Leisure,
     * or settled in for the evening at home, the tavern or a neighbour's.
     * Those conversations skip the daily cap and take the long cooldown.
     */
    private static boolean isSocializing(VillagerModel m) {
        Activity cur = m.brain().currentActivity();
        return (cur instanceof LeisureActivity l && l.currentSub() instanceof SocializeActivity)
                || cur instanceof EveningActivity;
    }
}
