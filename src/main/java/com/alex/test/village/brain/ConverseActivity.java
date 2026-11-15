package com.alex.test.village.brain;

import com.alex.test.animation.VillagerAnimationState;
import com.alex.test.entity.NpcEntity;
import com.alex.test.entity.NpcUiInteractionHandler;
import com.alex.test.village.social.ConversationScript;
import com.alex.test.village.social.MemoryTopic;
import com.alex.test.village.social.ThoughtBubbles;
import com.alex.test.village.social.ThoughtIcon;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;
import java.util.function.BiFunction;

/**
 * Two villagers stand facing each other and exchange thought icons.
 *
 * <p>The exchange itself is decided once, at pairing time, and handed to both
 * sides as the same {@link ConversationScript} — see that class for why. This
 * activity is only the playback head: every {@code lineTicks} it advances to
 * the next line and, when the line belongs to this villager, floats the icon
 * over its head with a villager noise and a gesture. The partner's instance is
 * doing the same thing with the other lines of the same script, so the two read
 * as one conversation without either side having to consult the other.
 *
 * <p>With no script (the pre-existing behaviour, still used if a caller has
 * nothing to say) the two simply alternate noises and gestures on a fixed
 * cadence.
 *
 * <p>When {@code noLimits} is true (conversation triggered during
 * {@link SocializeActivity}) the daily social-action counter is not incremented
 * and happiness can recover up to a higher ceiling — socialising is the
 * primary purpose of that activity.
 */
public final class ConverseActivity extends Activity {

    /** A cancelled Mayor chat shorter than this does not earn its bonus. */
    private static final int MIN_MAYOR_REWARD_TICKS = 60;

    /** How long a spoken icon outlives its turn, so bubbles overlap slightly. */
    private static final int LINE_LINGER_TICKS = 12;

    /**
     * How far apart two villagers may stand and still be talking, horizontally.
     *
     * <p>Measured flat rather than as a straight-line distance, and with a
     * floor as well as a ceiling. A single 3D radius paired a villager with one
     * standing on the step above it, or on the floor below in a two-storey
     * tavern, and the pair then faced each other through the ceiling; and with
     * no floor it paired two that collision had pressed together, which reads
     * as one villager talking to itself. The floor sits just under a block:
     * villagers are 0.6 wide and two on adjacent nav tiles settle about 1.0
     * apart, so anything higher would reject ordinary pairs as they jostle.
     */
    private static final double MIN_PAIR_DIST_SQ = 0.81;  // 0.9 blocks
    private static final double MAX_PAIR_DIST_SQ = 6.25;  // two and a half

    /**
     * The most two partners' feet may differ in height.
     *
     * <p>Under one block, so a pair on the same floor qualifies however their
     * hitboxes happen to be settled, and a pair a step apart does not: the look
     * control would have one craning up at the other, and from the player's
     * side the two bubbles stop reading as a row.
     */
    private static final double MAX_PAIR_HEIGHT = 0.9;

    /**
     * Whether two villagers are standing where a conversation would look like
     * one: on the same level, a block or two apart rather than toe to toe.
     *
     * <p>Failing this rejects where they are, not who they are. Both callers
     * try again later -- {@link SocializeActivity} drifts to another spot in
     * the venue every few hundred ticks, and the Mayor re-approaches.
     */
    public static boolean wellPlaced(NpcEntity a, NpcEntity b) {
        if (a == null || b == null) return false;
        if (Math.abs(a.getY() - b.getY()) > MAX_PAIR_HEIGHT) return false;
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        double flat = dx * dx + dz * dz;
        if (flat >= MIN_PAIR_DIST_SQ && flat <= MAX_PAIR_DIST_SQ) return true;
        // Two villagers seated across a table (a little further apart) can
        // talk if each can turn its head to the other and nothing solid is
        // between them. The sight check runs only for this narrow case.
        return flat > MAX_PAIR_DIST_SQ && flat <= SEATED_PAIR_DIST_SQ
                && a.isChairSeated() && b.isChairSeated()
                && withinHeadTurn(a, b) && withinHeadTurn(b, a)
                && a.hasLineOfSight(b);
    }

    /** Two seated villagers talk from up to 3.6 blocks apart: across a 2-wide table, or its diagonal. */
    private static final double SEATED_PAIR_DIST_SQ = 12.96;

    /** Whether a seated villager can turn its head (75 degrees each way) to {@code other}. */
    private static boolean withinHeadTurn(NpcEntity self, NpcEntity other) {
        float yaw = (float) Math.toDegrees(Math.atan2(other.getZ() - self.getZ(), other.getX() - self.getX())) - 90f;
        return Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw - self.yBodyRot)) <= 75f;
    }

    private final NpcEntity partner;
    private final boolean   speaksFirst;
    private final int       duration;
    /** True when this converse was initiated while socialising; skips the daily cap. */
    private final boolean   noLimits;
    /** True only on the ordinary villager's side of a Mayor conversation. */
    private final boolean   mayorConversationReward;
    /** The agreed exchange, or null for the plain gesture-and-noise fallback. */
    @Nullable
    private final ConversationScript script;

    private static final VillagerAnimationState[] GESTURES = {
        VillagerAnimationState.THINK,
        VillagerAnimationState.ARM_ON_HIP,
        VillagerAnimationState.SHRUG,
    };

    private int     ticks        = 0;
    private int     gestureTimer = 0;
    private boolean rewarded     = false;
    /** Index of the last script line acted on, so each line fires exactly once. */
    private int     lastLine     = -1;
    /** Business plays both sides here: a visiting merchant has no villager brain. */
    @Nullable private BiFunction<Context, Integer, ThoughtIcon> businessLine;
    private int partnerGestureTimer;
    private boolean playbackPaused;
    @Nullable private ThoughtIcon spokenIcon;

    /** Reuse scripted playback without leisure rewards or social cooldown changes. */
    public static ConverseActivity business(NpcEntity partner, ConversationScript script,
                                             BiFunction<Context, Integer, ThoughtIcon> onLine) {
        ConverseActivity activity = new ConverseActivity(partner, true,
                script.startTick(script.size()) + 1, false, false, script);
        activity.businessLine = java.util.Objects.requireNonNull(onLine);
        return activity;
    }

    /** Pause the clock; resuming repeats presentation only, never the line's transaction. */
    public void pausePlayback() {
        playbackPaused = true;
        gestureTimer = 0;
        partnerGestureTimer = 0;
    }

    public int currentLine() { return lastLine; }

    public ConverseActivity(NpcEntity partner, boolean speaksFirst, int duration, boolean noLimits) {
        this(partner, speaksFirst, duration, noLimits, false, null);
    }

    public ConverseActivity(NpcEntity partner, boolean speaksFirst, int duration,
                            boolean noLimits, boolean mayorConversationReward) {
        this(partner, speaksFirst, duration, noLimits, mayorConversationReward, null);
    }

    public ConverseActivity(NpcEntity partner, boolean speaksFirst, int duration,
                            boolean noLimits, boolean mayorConversationReward,
                            @Nullable ConversationScript script) {
        this.partner     = partner;
        this.speaksFirst = speaksFirst;
        this.duration    = duration;
        this.noLimits    = noLimits;
        this.mayorConversationReward = mayorConversationReward;
        this.script      = script;
    }

    /** An evening chat (home, tavern, visit): shorter cooldown after it. */
    private boolean eveningChat;
    /** A chat between a visiting guest and its host: longer, finished even past bedtime. */
    private boolean visitChat;
    /** A player is close enough to see it: only then are bubbles, gestures and sounds shown. */
    private boolean seen;
    /** Somebody asleep nearby: sounds are kept low. */
    private boolean quiet;
    /** How near a player must be to see a chat, the same range the coordinator used to require. */
    private static final double SEEN_RANGE = 32.0;

    /** Marks an evening chat, and whether it is a guest-and-host visit chat. */
    public ConverseActivity evening(boolean visit) {
        this.eveningChat = true;
        this.visitChat = visit;
        return this;
    }

    public boolean isVisitChat() { return visitChat; }

    /** Two villagers who already know where they are (housemates, or already talked tonight): no hello wave. */
    private boolean helloWave = true;

    public ConverseActivity noHello() {
        this.helloWave = false;
        return this;
    }

    /**
     * Whether a greeting icon at {@code index} is a real hello or goodbye,
     * worth a wave: the opening pair or the last two lines of a visit chat,
     * or the first line of a chat between villagers who have not seen each
     * other. A greeting in the middle of a chat, or between housemates, is
     * only an ordinary gesture.
     */
    private boolean helloOrGoodbye(int index) {
        if (visitChat) return index <= 1 || index >= script.size() - 2;
        return index == 0 && helloWave;
    }

    @Override
    protected void start(Context ctx) {
        ctx.model().stopMoving();
        if (businessLine != null) return;
        if (partner != null && partner.getNpcId() != null) ctx.brain().onChatWith(partner.getNpcId());
        if (visitChat) {
            ctx.brain().onVisitChat();
            String partnerId = partner != null && partner.getNpcId() != null
                    ? partner.getNpcId().toString().substring(0, 8) : "?";
            ctx.brain().diary().log(String.format("%s VISIT chat with %s topics=%s lines=%d ticks=%d",
                    VillagerDiary.ts(ctx.level()), partnerId,
                    script != null ? String.join(",", script.topics()) : "-",
                    script != null ? script.size() : 0, duration));
        }
    }

    /**
     * Looked at once per line: whether a player can see this chat, and
     * whether anyone nearby sleeps. Chats away from players still run, pay
     * off and cool down; they just show nothing until a player comes close.
     */
    private void refreshAudience(Context ctx) {
        NpcEntity npc = ctx.npc();
        seen = ctx.level().hasNearbyAlivePlayer(npc.getX(), npc.getY(), npc.getZ(), SEEN_RANGE);
        quiet = seen && EveningActivity.sleeperNear(ctx, npc.blockPosition(), EveningActivity.SLEEPER_QUIET_RADIUS);
    }

    @Override
    protected Status tick(Context ctx) {
        if (partner == null || !partner.isAlive()
                || ctx.npc().distanceToSqr(partner) > 64.0) {
            return Status.DONE;
        }

        if (businessLine != null) {
            // Detailed routes can leave a direct MoveControl waypoint behind.
            // Cancel it as well as navigation so it cannot turn or move a speaker.
            ctx.npc().getMoveControl().setWantedPosition(ctx.npc().getX(), ctx.npc().getY(), ctx.npc().getZ(), 0);
            partner.getMoveControl().setWantedPosition(partner.getX(), partner.getY(), partner.getZ(), 0);
            ctx.npc().holdStillForAnimation();
            partner.holdStillForAnimation();
            NpcUiInteractionHandler.faceEntity(ctx.npc(), partner, true);
            NpcUiInteractionHandler.faceEntity(partner, ctx.npc(), true);
            if (playbackPaused) {
                playbackPaused = false;
                refreshAudience(ctx);
                // A greeting already happened: repositioning must not wave again.
                if (lastLine >= 0 && spokenIcon != null && spokenIcon != ThoughtIcon.EMOTE_GREETING && seen)
                    presentScriptedLine(ctx, lastLine, spokenIcon);
            }
        } else {
            ctx.npc().getLookControl().setLookAt(partner, 30f, 30f);
        }

        if (script != null) speakScriptedLine(ctx);
        else                speakAmbient(ctx);

        if (gestureTimer > 0 && --gestureTimer == 0) {
            ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        }
        if (partnerGestureTimer > 0 && --partnerGestureTimer == 0) {
            partner.setAnimation(VillagerAnimationState.IDLE);
        }

        if (++ticks >= duration) {
            reward(ctx);
            return Status.DONE;
        }
        return Status.RUNNING;
    }

    /**
     * Advance the playback head. Both villagers step through the same line
     * indices at the same ticks (pauses included); each acts only on the
     * lines it owns, and the one listening puts its icon away.
     */
    private void speakScriptedLine(Context ctx) {
        int index = script.indexAt(ticks);
        if (index < 0 || index == lastLine || index >= script.size()) return;
        lastLine = index;
        ThoughtIcon icon = businessLine != null ? businessLine.apply(ctx, index) : script.line(index).icon();
        spokenIcon = icon;
        refreshAudience(ctx);
        if (!seen) return;

        presentScriptedLine(ctx, index, icon);
    }

    private void presentScriptedLine(Context ctx, int index, ThoughtIcon icon) {
        ConversationScript.Line line = script.line(index);
        if (businessLine == null && line.initiator() != speaksFirst) {
            // The partner's turn: put this icon away so the two take turns
            // instead of talking over each other, and look at the speaker.
            ctx.npc().clearBubble();
            return;
        }

        NpcEntity speaker = businessLine != null && !line.initiator() ? partner : ctx.npc();
        if (businessLine != null) {
            NpcEntity listener = speaker == partner ? ctx.npc() : partner;
            listener.clearBubble();
        }
        speaker.showBubble(icon, true, script.lineTicks() + LINE_LINGER_TICKS);
        ThoughtBubbles.huh(speaker, ctx.level(), icon, quiet ? 0.3f : 0.8f);
        VillagerAnimationState fitting = switch (icon) {
            // A greeting or goodbye is a wave; a question is a thinking pose;
            // doubt or disagreement a shrug.
            case EMOTE_GREETING -> businessLine != null || helloOrGoodbye(index) ? VillagerAnimationState.WAVE : null;
            case EMOTE_QUESTION, EMOTE_IDEA -> VillagerAnimationState.THINK;
            case EMOTE_DISAGREE, EMOTE_DOUBT, EMOTE_CONFUSION -> VillagerAnimationState.SHRUG;
            case TOPIC_TRADING -> businessLine != null ? VillagerAnimationState.ARM_ON_HIP : null;
            // A yes or a thank-you never comes with a random shrug.
            case EMOTE_AGREE, EMOTE_GRATITUDE, EMOTE_CELEBRATION ->
                    businessLine != null ? VillagerAnimationState.ARM_ON_HIP : null;
            default -> null;
        };
        if (fitting != null) {
            speaker.setAnimation(fitting);
            int gestureTicks = (int) VillagerAnimationState.getDuration(fitting);
            if (businessLine != null) gestureTicks = Math.min(gestureTicks, 200);
            if (speaker == ctx.npc()) gestureTimer = gestureTicks;
            else partnerGestureTimer = gestureTicks;
        } else {
            gesture(speaker);
        }
    }

    /** The scriptless fallback: alternating noises on a fixed cadence. */
    private void speakAmbient(Context ctx) {
        int phase = ticks % 60;
        if (phase != (speaksFirst ? 0 : 30)) return;
        refreshAudience(ctx);
        if (!seen) return;

        ctx.level().playSound(null, ctx.npc().getX(), ctx.npc().getY(), ctx.npc().getZ(),
                SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL, quiet ? 0.3f : 0.8f,
                0.9f + ctx.npc().getRandom().nextFloat() * 0.3f);
        gesture(ctx);
    }

    private void gesture(Context ctx) {
        gesture(ctx.npc());
    }

    private void gesture(NpcEntity speaker) {
        boolean partnerSpeaking = speaker == partner;
        if ((partnerSpeaking ? partnerGestureTimer : gestureTimer) > 0) return;
        VillagerAnimationState g = GESTURES[speaker.getRandom().nextInt(GESTURES.length)];
        speaker.setAnimation(g);
        int gestureTicks = (int) VillagerAnimationState.getDuration(g);
        if (businessLine != null) gestureTicks = Math.min(gestureTicks, 200);
        if (partnerSpeaking) partnerGestureTimer = gestureTicks;
        else gestureTimer = gestureTicks;
    }

    @Override
    public void stop(Context ctx) {
        ctx.npc().setAnimation(VillagerAnimationState.IDLE);
        ctx.npc().clearBubble();
        if (businessLine != null) {
            partner.setAnimation(VillagerAnimationState.IDLE);
            partner.clearBubble();
            return;
        }
        reward(ctx);
        // Leisure villagers (noLimits) bypass the daily cap, so give them a much
        // longer cooldown to prevent two idling villagers from spamming each other.
        // Evening chats (home, tavern, visits) cool down in 400-900 ticks so a
        // guest and host, or neighbours at the tavern, can talk more than once.
        int cd = eveningChat
                ? 400 + ctx.npc().getRandom().nextInt(501)
                : noLimits
                ? 1200 + ctx.npc().getRandom().nextInt(2400)
                : 400  + ctx.npc().getRandom().nextInt(800);
        ctx.brain().setConversationCooldown(cd);
    }

    @Override
    public String name() { return "converse"; }

    /** Two villagers paired from neighbouring chairs talk from where they sit. */
    @Override
    public boolean keepsChairSeat() { return true; }

    private void reward(Context ctx) {
        if (businessLine != null) return;
        if (rewarded) return;
        if (mayorConversationReward && ticks < MIN_MAYOR_REWARD_TICKS) return;
        rewarded = true;
        String partnerId = partner.getNpcId() != null
                ? partner.getNpcId().toString().substring(0, 8) : "?";
        if (mayorConversationReward) {
            ctx.brain().needs().onMayorConversationComplete(
                    5f + ctx.npc().getRandom().nextInt(6));
            ctx.brain().remember(MemoryTopic.MAYOR_VISIT, ctx.level());
        } else {
            ctx.brain().needs().onConversationComplete(noLimits);
            ctx.brain().onConversationLearned();
            ctx.brain().remember(MemoryTopic.GOOD_COMPANY, ctx.level());
            if (eveningChat) ctx.brain().onEveningChat();
            if (!noLimits) ctx.brain().recordSocialSuccess();
        }
        ctx.brain().diary().log(String.format("%s converse  with %s%s → happy=%.1f%s",
                VillagerDiary.ts(ctx.level()), partnerId,
                mayorConversationReward ? " (Mayor)"
                        : noLimits ? " (socialising)" : "",
                ctx.brain().needs().wellbeing,
                script == null ? "" : " | " + script.describe()));
    }
}
