package com.alex.test.village.shelter;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Word of a raid spreading by mouth, while no bell has been rung: a villager
 * who sees a raider shouts, and the villagers near it hear the shout a moment
 * later, run for shelter and shout in turn. The warning ripples out from where
 * the raiders were first seen, a few hops at most, so the far side of the
 * village may not hear it at all. Only raiders, only while the village is not
 * alarmed: an ordinary night is unchanged.
 */
public final class RaidShout {

    /** Villagers this close to a shout hear it. */
    private static final double SHOUT_RADIUS = 14.0;
    private static final double SHOUT_RADIUS_SQ = SHOUT_RADIUS * SHOUT_RADIUS;
    /** How many times a shout is passed on: the first one's hearers are hop 1. */
    private static final int MAX_HOPS = 4;
    /** Hearing, turning, taking it in: 0.5 to 1.5 s before a hearer runs. */
    private static final int REACT_MIN_TICKS = 10;
    private static final int REACT_JITTER_TICKS = 20;
    /** One villager shouts at most this often. */
    private static final int SHOUT_COOLDOWN_TICKS = 100;
    /** A heard shout not acted on in this long is forgotten. */
    private static final int HEARD_TTL_TICKS = 200;

    /** Villager NPC id -> the shout it heard. */
    private static final Map<UUID, Heard> HEARD = new HashMap<>();
    /** Villager NPC id -> when it may shout again. */
    private static final Map<UUID, Long> NEXT_SHOUT = new HashMap<>();

    /** A shout {@code npc} heard: the raider it was about, when it takes it in, and how far it has come. */
    public record Heard(UUID raiderId, UUID fromNpcId, long actAt, int hops) {}

    private RaidShout() {}

    /**
     * {@code npc} has seen (or been hit by) {@code raider}: it shouts, and the
     * villagers around it will hear it. {@code hops} is how many shouts the
     * news has already passed through (0 for one who saw the raider).
     */
    public static void shout(VillageManager manager, ServerLevel level, NpcEntity npc, LivingEntity raider, int hops) {
        long now = level.getGameTime();
        Long next = NEXT_SHOUT.get(npc.getNpcId());
        if (next != null && now < next) return;
        NEXT_SHOUT.put(npc.getNpcId(), now + SHOUT_COOLDOWN_TICKS);
        if (NEXT_SHOUT.size() > 256) NEXT_SHOUT.values().removeIf(t -> t < now);

        npc.showBubble(ThoughtIcon.EMOTE_WARNING, true, 40);
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(), SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL,
                1.6F, 1.2F + level.random.nextFloat() * 0.2F);
        if (hops >= MAX_HOPS) return;

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity other = model.puppet();
            if (other == null || other == npc || !other.isAlive()) continue;
            if (other.getProfession().isDefender()) continue;
            if (other.distanceToSqr(npc) > SHOUT_RADIUS_SQ) continue;
            if (model.brain() != null && model.brain().isTakingShelter()) continue;
            Heard already = HEARD.get(other.getNpcId());
            if (already != null && already.hops() <= hops + 1) continue;
            HEARD.put(other.getNpcId(), new Heard(raider.getUUID(), npc.getNpcId(),
                    now + REACT_MIN_TICKS + rng.nextInt(REACT_JITTER_TICKS + 1), hops + 1));
        }
        if (HEARD.size() > 256) HEARD.values().removeIf(h -> now - h.actAt() > HEARD_TTL_TICKS);
    }

    /**
     * A roar from {@code raider} (the Warlord): every villager out under the sky
     * within {@code radius} hears it as if a neighbour had shouted, and runs for
     * shelter the same way, shouting on. Defenders and villagers already taking
     * shelter are left alone; as with any shout, nobody reacts once the alarm
     * has been rung (they are sheltering already).
     */
    public static void hearRoar(VillageManager manager, ServerLevel level, LivingEntity raider, double radius) {
        long now = level.getGameTime();
        double radiusSq = radius * radius;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (VillagerModel model : manager.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.getProfession().isDefender()) continue;
            if (npc.distanceToSqr(raider) > radiusSq) continue;
            if (model.brain() != null && model.brain().isTakingShelter()) continue;
            if (!level.canSeeSky(net.minecraft.core.BlockPos.containing(npc.getEyePosition()))) continue;
            Heard already = HEARD.get(npc.getNpcId());
            if (already != null && already.hops() <= 1) continue;
            HEARD.put(npc.getNpcId(), new Heard(raider.getUUID(), raider.getUUID(),
                    now + REACT_MIN_TICKS + rng.nextInt(REACT_JITTER_TICKS + 1), 1));
        }
        if (HEARD.size() > 256) HEARD.values().removeIf(h -> now - h.actAt() > HEARD_TTL_TICKS);
    }

    /**
     * The shout {@code npc} heard, once it has taken it in (null before that,
     * and when it heard none). Taking it forgets it.
     */
    @Nullable
    public static Heard takeHeard(NpcEntity npc, long now) {
        Heard heard = HEARD.get(npc.getNpcId());
        if (heard == null || now < heard.actAt()) return null;
        HEARD.remove(npc.getNpcId());
        return now - heard.actAt() > HEARD_TTL_TICKS ? null : heard;
    }

}
