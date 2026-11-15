package com.alex.test.village.visitors;

import com.alex.test.DynamicVillages;
import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.visitor.AbstractTravelerEntity;
import com.alex.test.entity.visitor.VisitorEntity;
import com.alex.test.village.VillageManager;
import com.alex.test.village.VillagerModel;
import com.alex.test.village.social.ThoughtIcon;
import com.alex.test.village.villagers.core.Profession;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * A visitor chatting with a villager in the evening, at the tavern or the camp:
 * four short turns of speech bubbles, both facing each other.
 *
 * <p>What they talk about depends on the villager's day ({@link MadeToday}):
 * <ul>
 *   <li><b>Showing off</b>: the villager holds up the best thing it made today
 *       and the visitor admires it; a merchant puts a price on it, the item in
 *       its own bubble.</li>
 *   <li><b>A question</b>: the visitor asks about the thing (the item in its
 *       bubble) and the villager holds it up.</li>
 *   <li><b>A bad day</b>: nothing made today; the villager shrugs and the
 *       visitor cheers it up.</li>
 *   <li><b>A gift</b>, now and then: a cook holds out some bread to a nomad.</li>
 *   <li><b>The visitor's own talk</b>: a merchant on trade and far places, a
 *       nomad on the road and wanting a home (a hint to recruit them).</li>
 * </ul>
 *
 * <p>Held items are display copies ({@link NpcEntity#holdDisplayItem}), never
 * a villager's real things, and only when its hand is free. Transient: a chat
 * never outlives a reload.
 */
public final class TravelerTalk {
    private enum Show { NONE, HAND, BUBBLE }

    /** One turn: who speaks, with which icon, showing the item how, with which gesture. */
    private record Beat(boolean byVisitor, @Nullable ThoughtIcon icon, Show show,
                        @Nullable VillagerAnimationState gesture) {}

    private static final class Talk {
        final NpcEntity partner;
        final List<Beat> beats;
        final ItemStack item;
        int beat = -1;
        int timer;

        Talk(NpcEntity partner, List<Beat> beats, ItemStack item) {
            this.partner = partner;
            this.beats = beats;
            this.item = item;
        }
    }

    /** Ticks one turn lasts (2.5 s). */
    private static final int BEAT_TICKS = 50;
    /** How near a villager has to be to be talked to. */
    private static final double TALK_RANGE = 3.5;
    /** A chat ends if the two drift further apart than this. */
    private static final double MAX_APART_SQ = 5.0 * 5.0;
    /** Between one visitor's chats: 20 to 50 s. */
    private static final int COOLDOWN = 400;
    private static final int COOLDOWN_JITTER = 600;
    /** A villager talked to rests from visitors this long (1 min). */
    private static final long PARTNER_REST = 1_200L;
    /** Out of 100: a cook's gift to a nomad. */
    private static final int GIFT_CHANCE = 15;

    private static final WeakHashMap<AbstractTravelerEntity, Talk> TALKS = new WeakHashMap<>();
    private static final WeakHashMap<AbstractTravelerEntity, Long> NEXT_TALK_AT = new WeakHashMap<>();
    private static final Map<UUID, Long> PARTNER_RESTS_UNTIL = new HashMap<>();

    private TravelerTalk() {}

    /**
     * Runs or starts a chat for {@code visitor}, which is standing in the
     * tavern or by its fire. True while it is chatting: the caller leaves the
     * visitor's facing and gestures to the chat.
     */
    public static boolean tick(AbstractTravelerEntity visitor, ServerLevel level, VillageManager village) {
        long now = level.getGameTime();
        Talk talk = TALKS.get(visitor);
        if (talk == null) {
            Long next = NEXT_TALK_AT.get(visitor);
            if (next == null) {
                NEXT_TALK_AT.put(visitor, now + 100 + visitor.getRandom().nextInt(300));
                return false;
            }
            if (now < next) return false;
            NEXT_TALK_AT.put(visitor, now + COOLDOWN + visitor.getRandom().nextInt(COOLDOWN_JITTER));
            talk = start(visitor, level, village, now);
            if (talk == null) return false;
            TALKS.put(visitor, talk);
        }
        NpcEntity partner = talk.partner;
        if (!partner.isAlive() || partner.isRemoved() || partner.isSleeping()
                || partner.distanceToSqr(visitor) > MAX_APART_SQ) {
            stop(visitor);
            return false;
        }
        face(visitor, partner);
        partner.getLookControl().setLookAt(visitor, 30f, 30f);
        if (talk.beat < 0 || ++talk.timer >= BEAT_TICKS) {
            talk.beat++;
            talk.timer = 0;
            if (talk.beat >= talk.beats.size()) {
                stop(visitor);
                return false;
            }
            play(visitor, talk, talk.beats.get(talk.beat), now);
        }
        return true;
    }

    /** Ends {@code visitor}'s chat, if any, and puts a held-up item away. */
    public static void stop(AbstractTravelerEntity visitor) {
        Talk talk = TALKS.remove(visitor);
        if (talk != null) talk.partner.clearDisplayItem();
    }

    @Nullable
    private static Talk start(AbstractTravelerEntity visitor, ServerLevel level, VillageManager village, long now) {
        NpcEntity partner = null;
        double bestSq = TALK_RANGE * TALK_RANGE;
        for (VillagerModel model : village.getVillagers()) {
            NpcEntity npc = model.puppet();
            if (npc == null || !npc.isAlive() || npc.isSleeping() || npc.hasBubble()) continue;
            // Settled down for the night (on a floor, not in a bed) is asleep too.
            if (model.brain().currentActivity() instanceof com.alex.test.village.brain.GoHomeActivity home
                    && home.isResting()) continue;
            if (PARTNER_RESTS_UNTIL.getOrDefault(npc.getUUID(), 0L) > now || isTalkingWithAVisitor(npc)) continue;
            double d = npc.distanceToSqr(visitor);
            // Never through a wall: a camp can lie just outside a house.
            if (d < bestSq && visitor.hasLineOfSight(npc)) {
                bestSq = d;
                partner = npc;
            }
        }
        if (partner == null) return null;
        PARTNER_RESTS_UNTIL.put(partner.getUUID(), now + PARTNER_REST);
        PARTNER_RESTS_UNTIL.values().removeIf(until -> until < now);

        RandomSource random = visitor.getRandom();
        boolean merchant = visitor instanceof VisitorEntity;
        ItemStack made = MadeToday.of(partner, level);
        int roll = random.nextInt(100);
        List<Beat> beats;
        ItemStack item = made;
        String kind;
        if (!merchant && partner.getProfession() == Profession.COOK && roll < GIFT_CHANCE) {
            kind = "gift";
            item = new ItemStack(Items.BREAD);
            beats = List.of(
                    new Beat(false, ThoughtIcon.EMOTE_GREETING, Show.NONE, VillagerAnimationState.WAVE),
                    new Beat(false, ThoughtIcon.TOPIC_COOKING, Show.HAND, null),
                    new Beat(true, ThoughtIcon.EMOTE_GRATITUDE, Show.NONE, VillagerAnimationState.WAVE),
                    new Beat(true, ThoughtIcon.EMOTE_LOVE, Show.NONE, null));
        } else if (made.isEmpty() && roll < 60) {
            kind = "bad day";
            beats = List.of(
                    new Beat(true, ThoughtIcon.EMOTE_GREETING, Show.NONE, VillagerAnimationState.WAVE),
                    new Beat(false, ThoughtIcon.STATUS_SAD, Show.NONE, VillagerAnimationState.SHRUG),
                    new Beat(true, ThoughtIcon.EMOTE_LAUGHTER, Show.NONE, VillagerAnimationState.ARM_ON_HIP),
                    new Beat(false, ThoughtIcon.EMOTE_GRATITUDE, Show.NONE, null));
        } else if (!made.isEmpty() && roll < 40) {
            kind = "show off";
            beats = merchant
                    ? List.of(
                            new Beat(false, ThoughtIcon.EMOTE_PRIDE, Show.HAND, VillagerAnimationState.ARM_ON_HIP),
                            new Beat(true, null, Show.BUBBLE, VillagerAnimationState.THINK),
                            new Beat(true, ThoughtIcon.TOPIC_TRADING, Show.NONE, null),
                            new Beat(false, ThoughtIcon.STATUS_HAPPY, Show.NONE, null))
                    : List.of(
                            new Beat(false, ThoughtIcon.EMOTE_PRIDE, Show.HAND, VillagerAnimationState.ARM_ON_HIP),
                            new Beat(true, ThoughtIcon.EMOTE_ADMIRATION, Show.NONE, null),
                            new Beat(false, ThoughtIcon.STATUS_HAPPY, Show.NONE, null),
                            new Beat(true, ThoughtIcon.EMOTE_AGREE, Show.NONE, null));
        } else if (!made.isEmpty() && roll < 75) {
            kind = "question";
            beats = List.of(
                    new Beat(true, ThoughtIcon.EMOTE_QUESTION, Show.NONE, VillagerAnimationState.THINK),
                    new Beat(true, null, Show.BUBBLE, null),
                    new Beat(false, ThoughtIcon.EMOTE_AGREE, Show.HAND, null),
                    new Beat(true, ThoughtIcon.EMOTE_SURPRISED, Show.NONE, null));
        } else if (merchant) {
            kind = "merchant talk";
            beats = List.of(
                    new Beat(true, ThoughtIcon.EMOTE_GREETING, Show.NONE, VillagerAnimationState.WAVE),
                    new Beat(true, ThoughtIcon.TOPIC_TRADING, Show.NONE, null),
                    new Beat(true, ThoughtIcon.TOPIC_NATURE, Show.NONE, VillagerAnimationState.THINK),
                    new Beat(false, ThoughtIcon.EMOTE_SURPRISED, Show.NONE, null));
        } else {
            kind = "nomad talk";
            beats = List.of(
                    new Beat(true, ThoughtIcon.EMOTE_GREETING, Show.NONE, VillagerAnimationState.WAVE),
                    new Beat(true, ThoughtIcon.TOPIC_NATURE, Show.NONE, VillagerAnimationState.THINK),
                    new Beat(true, ThoughtIcon.TOPIC_HOME, Show.NONE, VillagerAnimationState.SHRUG),
                    new Beat(false, ThoughtIcon.EMOTE_IDEA, Show.NONE, null));
        }
        DynamicVillages.LOGGER.debug("[Talk] {} {} chats with {} ({}, {}).",
                merchant ? "Merchant" : "Nomad", TravelerApproach.shortId(visitor),
                partner.getUUID().toString().substring(0, 8), kind, item.isEmpty() ? "nothing" : item.getItem());
        return new Talk(partner, beats, item);
    }

    private static void play(AbstractTravelerEntity visitor, Talk talk, Beat beat, long now) {
        NpcEntity speaker = beat.byVisitor() ? visitor : talk.partner;
        if (beat.show() == Show.BUBBLE && !talk.item.isEmpty()) {
            speaker.showItemBubble(talk.item, BEAT_TICKS + 10);
        } else if (beat.icon() != null) {
            speaker.showBubble(beat.icon(), true, BEAT_TICKS + 10);
        }
        if (beat.show() == Show.HAND && !talk.item.isEmpty()) {
            ItemStack inHand = speaker.getMainHandItem();
            // Only into a free hand: a real tool or item is never swapped out.
            if (inHand.isEmpty() || NpcEntity.isDisplayOnly(inHand)) {
                speaker.holdDisplayItem(talk.item, now + BEAT_TICKS * 2L);
            }
        }
        if (beat.gesture() != null && !speaker.isChairSeated()) speaker.setAnimation(beat.gesture());
    }

    private static void face(AbstractTravelerEntity visitor, NpcEntity partner) {
        double dx = partner.getX() - visitor.getX();
        double dz = partner.getZ() - visitor.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        visitor.setYRot(yaw);
        visitor.setYHeadRot(yaw);
        visitor.setYBodyRot(yaw);
    }

    private static boolean isTalkingWithAVisitor(NpcEntity npc) {
        for (Talk talk : TALKS.values()) {
            if (talk.partner == npc) return true;
        }
        return false;
    }
}
