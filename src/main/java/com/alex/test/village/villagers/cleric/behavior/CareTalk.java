package com.alex.test.village.villagers.cleric.behavior;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcUiInteractionHandler;
import com.alex.test.entity.inventory.InventoryManager;
import com.alex.test.village.tokens.VillageItemOwnership;
import com.alex.test.village.brain.Activity;
import com.alex.test.village.brain.VillagerBrain;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtBubbles;
import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * A short chat the cleric has with a villager it came to look after: both
 * stop, face each other and take turns with thought bubbles, the way two
 * villagers talk. The villager's own activity is paused for it, not dropped
 * (VillagerBrain#requestCareHold), so it carries on afterwards.
 *
 * <pre>
 *   bringing food:  cleric  hello
 *                   villager  no food / hungry
 *                   cleric  food      (holds the food up)
 *                   villager  thanks  (holds it up; the food changes hands here)
 *                   cleric  happy
 *
 *   cheering up:    cleric  what's wrong?
 *                   villager  sad
 *                   cleric  love
 *                   villager  relief  (its mood lifts here)
 *                   cleric  happy
 * </pre>
 */
final class CareTalk {

    enum Kind { FEED, CHEER }

    /** Ticks each bubble is up before the other one answers. */
    private static final int LINE_TICKS = 40;
    /** How long a bubble lingers past its turn, so the two overlap a little. */
    private static final int LINGER_TICKS = 12;
    /** The partner walked or was pushed further than this: the chat is off. */
    private static final double MAX_DIST_SQ = 6.0 * 6.0;

    private final Kind kind;
    private final NpcEntity partner;
    private final VillagerBrain partnerBrain;
    private final ConversationScript script;
    /** The food being handed over (FEED only): a copy with the count to give. */
    private final ItemStack gift;
    private final float cheer;
    private int ticks;
    private int lastLine = -1;
    private boolean delivered;
    private boolean broken;

    private CareTalk(Kind kind, NpcEntity partner, VillagerBrain partnerBrain, ConversationScript script,
                     ItemStack gift, float cheer) {
        this.kind = kind;
        this.partner = partner;
        this.partnerBrain = partnerBrain;
        this.script = script;
        this.gift = gift;
        this.cheer = cheer;
    }

    static CareTalk feed(NpcEntity partner, VillagerBrain partnerBrain, ItemStack gift) {
        ThoughtIcon need = partnerBrain.isStarving() ? ThoughtIcon.STATUS_STARVING : ThoughtIcon.STATUS_NO_FOOD;
        return new CareTalk(Kind.FEED, partner, partnerBrain, new ConversationScript(List.of(
                new ConversationScript.Line(true, ThoughtIcon.EMOTE_GREETING),
                new ConversationScript.Line(false, need),
                new ConversationScript.Line(true, ThoughtIcon.TOPIC_FOOD),
                new ConversationScript.Line(false, ThoughtIcon.EMOTE_GRATITUDE),
                new ConversationScript.Line(true, ThoughtIcon.STATUS_HAPPY)), LINE_TICKS), gift, 0f);
    }

    static CareTalk cheer(NpcEntity partner, VillagerBrain partnerBrain, float amount) {
        return new CareTalk(Kind.CHEER, partner, partnerBrain, new ConversationScript(List.of(
                new ConversationScript.Line(true, ThoughtIcon.EMOTE_QUESTION),
                new ConversationScript.Line(false, ThoughtIcon.STATUS_SAD),
                new ConversationScript.Line(true, ThoughtIcon.EMOTE_LOVE),
                new ConversationScript.Line(false, ThoughtIcon.EMOTE_RELIEF),
                new ConversationScript.Line(true, ThoughtIcon.STATUS_HAPPY)), LINE_TICKS), ItemStack.EMPTY, amount);
    }

    Kind kind() { return kind; }

    NpcEntity partner() { return partner; }

    /** Asks the partner to stop for the chat; false when it is busy with something that cannot wait. */
    boolean begin(Activity.Context ctx) {
        ctx.model().stopMoving();
        ctx.npc().setRunning(false);
        return partnerBrain.requestCareHold(ctx.npc(), script.durationTicks() + 20);
    }

    /** Whether the chat ran to its end and did what it came for. */
    boolean succeeded() {
        return !broken && (kind == Kind.CHEER ? lastLine >= 3 : delivered);
    }

    /** @return true once the chat is over, finished or broken off */
    boolean tick(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        if (!partner.isAlive() || partner.isRemoved() || cleric.distanceToSqr(partner) > MAX_DIST_SQ
                || !partnerBrain.careHoldActive()) {
            broken = true;
            return true;
        }
        cleric.holdStillForAnimation();
        NpcUiInteractionHandler.faceEntity(cleric, partner, true);
        int index = script.indexAt(ticks);
        if (index >= 0 && index != lastLine) {
            lastLine = index;
            present(ctx, index);
        }
        return ++ticks >= script.durationTicks();
    }

    private void present(Activity.Context ctx, int index) {
        NpcEntity cleric = ctx.npc();
        ConversationScript.Line line = script.line(index);
        NpcEntity speaker = line.initiator() ? cleric : partner;
        NpcEntity listener = line.initiator() ? partner : cleric;
        listener.clearBubble();
        speaker.showBubble(line.icon(), true, LINE_TICKS + LINGER_TICKS);
        ThoughtBubbles.huh(speaker, ctx.level(), line.icon(), 0.8f);
        speaker.setAnimation(switch (line.icon()) {
            case EMOTE_GREETING -> VillagerAnimationState.WAVE;
            case EMOTE_QUESTION -> VillagerAnimationState.THINK;
            case STATUS_SAD, STATUS_NO_FOOD, STATUS_STARVING -> VillagerAnimationState.SHRUG;
            default -> VillagerAnimationState.ARM_ON_HIP;
        });

        long now = ctx.level().getGameTime();
        if (kind == Kind.FEED && line.icon() == ThoughtIcon.TOPIC_FOOD) {
            cleric.holdDisplayItem(gift, now + LINE_TICKS + LINGER_TICKS);
        } else if (kind == Kind.FEED && line.icon() == ThoughtIcon.EMOTE_GRATITUDE && !delivered) {
            cleric.clearDisplayItem();
            delivered = handOver(cleric, partner, gift);
            if (delivered) partner.holdDisplayItem(gift, now + LINE_TICKS + LINGER_TICKS);
        } else if (kind == Kind.CHEER && line.icon() == ThoughtIcon.EMOTE_RELIEF) {
            partnerBrain.needs().addWellbeing(cheer);
            partnerBrain.remember(MemoryTopic.CARED_FOR, ctx.level());
        }
    }

    /**
     * Moves up to the gift's count of food from the cleric's pack to the
     * partner's, any kind of food, the gift's own kind first. All or nothing per
     * stack: a full pack on the other side keeps the food with the cleric.
     */
    private static boolean handOver(NpcEntity from, NpcEntity to, ItemStack gift) {
        InventoryManager mine = from.getInventory();
        InventoryManager theirs = to.getInventory();
        int left = gift.getCount();
        boolean any = false;
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int i = 0; i < InventoryManager.SIZE && left > 0; i++) {
                ItemStack stack = mine.getSlot(i);
                if (stack.isEmpty() || !mine.mayUse(stack)
                        || VillageItemOwnership.villagerFood(stack, from) == null) continue;
                if (pass == 0 && !ItemStack.isSameItemSameComponents(stack, gift)) continue;
                int give = Math.min(left, stack.getCount());
                ItemStack moved = stack.copyWithCount(give);
                if (!theirs.addItem(moved)) continue;
                mine.reduceSlot(i, give);
                left -= give;
                any = true;
            }
        }
        return any;
    }

    /** Puts everything away: bubbles, props, and the partner's pause. */
    void stop(Activity.Context ctx) {
        NpcEntity cleric = ctx.npc();
        partnerBrain.releaseCareHold(cleric);
        cleric.clearBubble();
        cleric.clearDisplayItem();
        if (cleric.getAnimation() != VillagerAnimationState.IDLE) cleric.setAnimation(VillagerAnimationState.IDLE);
    }
}
