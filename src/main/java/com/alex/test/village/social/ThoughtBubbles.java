package com.alex.test.village.social;

import com.alex.test.entity.NpcEntity;
import com.alex.test.village.brain.ThoughtState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;

/**
 * Decides when one villager shows a thought over its head, and remembers what
 * it has been through while doing it.
 *
 * <p>A thought is addressed to the player, so it is gated on one: nothing is
 * shown unless somebody is inside {@link #PLAYER_RANGE} to read it, which also
 * keeps a hundred off-screen villagers from syncing entity data at each other
 * all day. The "huh" that accompanies it is the same villager ambient sound
 * conversations already use, pitched down for a complaint and up for good news.
 *
 * <p>Three things get a bubble:
 * <ul>
 *   <li><b>A new problem</b> — the moment {@link ThoughtState#compute} changes
 *       to something worse, once, loudly.</li>
 *   <li><b>A standing problem</b> — re-shown on a long randomised interval, so
 *       a villager with no bed keeps asking without becoming wallpaper.</li>
 *   <li><b>Relief</b> — when a problem stops applying, and occasionally when a
 *       contented villager just feels like it.</li>
 * </ul>
 *
 * <p>Recording into {@link VillagerMemory} happens on the same pass, because
 * the state it would otherwise have to re-derive has just been computed. That
 * memory is what {@link ConversationScripts} later draws conversation topics
 * from — the thought a villager had on Tuesday is the thing it complains about
 * on Thursday.
 */
public final class ThoughtBubbles {

    /** Nobody within this many blocks, nobody to show a thought to. */
    private static final double PLAYER_RANGE = 20.0;

    /** How often the villager's state is re-derived for bubble purposes. */
    private static final int CHECK_INTERVAL = 40;

    /** How long a spontaneous thought stays up. */
    private static final int THOUGHT_TICKS = 70;

    /** Gap before a standing, unresolved problem is raised again. */
    private static final int REPEAT_MIN_TICKS = 500;
    private static final int REPEAT_RANDOM_TICKS = 700;

    /** Gap before a contented villager may show an idle positive thought. */
    private static final int IDLE_MIN_TICKS = 1200;
    private static final int IDLE_RANDOM_TICKS = 2400;

    /** Happiness above which a villager has pleasant idle thoughts. */
    private static final float CONTENT_HAPPINESS = 60f;

    private ThoughtState last = ThoughtState.OK;
    private int checkTimer  = 0;
    private int repeatTimer = 0;
    private int idleTimer   = 0;
    /** Set while an event thought is up, so the state pass does not talk over it. */
    private int holdTimer   = 0;

    /**
     * Advance the timers; returns true on the ticks where the caller should
     * derive a fresh {@link ThoughtState} and hand it to {@link #onState}.
     *
     * <p>Split in two so that {@code ThoughtState.compute} — which walks the
     * village's storage buildings and asks the profession whether it has work —
     * runs once every {@link #CHECK_INTERVAL} ticks rather than every tick, and
     * so that this package does not have to reach back into the brain to get it.
     */
    /** Start the check cycle at {@code phase} so villagers do not all check on one tick. */
    public void staggerChecks(int phase) {
        checkTimer = Math.floorMod(phase, CHECK_INTERVAL);
    }

    public boolean tickTimers() {
        if (holdTimer > 0) holdTimer--;
        if (repeatTimer > 0) repeatTimer--;
        if (idleTimer > 0) idleTimer--;
        if (++checkTimer < CHECK_INTERVAL) return false;
        checkTimer = 0;
        return true;
    }

    /**
     * Act on a freshly computed state.
     *
     * @param happiness current happiness, for idle positive thoughts
     * @param suppressed true while something else owns the bubble (a
     *                   conversation) or the villager is in no state to think
     *                   (asleep)
     */
    public void onState(NpcEntity npc, ServerLevel level, VillagerMemory memory,
                        ThoughtState state, float happiness, boolean suppressed) {
        // Remembering is unconditional. A villager that went hungry while
        // asleep, mid-conversation or alone in the woods still went hungry, and
        // that is exactly the sort of thing it should bring up later.
        long gameTime = level.getGameTime();
        if (state.memory() != null) memory.record(state.memory(), gameTime);
        recordWeather(level, memory, gameTime);

        if (suppressed) {
            // Whoever suppressed us owns the bubble now; just keep the state
            // machine current so the next un-suppressed tick does not fire a
            // stale "new problem" for something that started during a chat.
            last = state;
            return;
        }

        if (!playerNearby(npc, level)) {
            last = state;
            return;
        }

        if (state != last) {
            ThoughtState previous = last;
            last = state;
            if (state != ThoughtState.OK) {
                show(npc, level, state.icon(), THOUGHT_TICKS);
                repeatTimer = REPEAT_MIN_TICKS + npc.getRandom().nextInt(REPEAT_RANDOM_TICKS);
                return;
            }
            if (previous.resolvedIcon() != null) {
                show(npc, level, previous.resolvedIcon(), THOUGHT_TICKS);
                idleTimer = IDLE_MIN_TICKS + npc.getRandom().nextInt(IDLE_RANDOM_TICKS);
                return;
            }
        }

        if (holdTimer > 0 || npc.hasBubble()) return;

        if (state != ThoughtState.OK) {
            if (repeatTimer <= 0) {
                show(npc, level, state.icon(), THOUGHT_TICKS);
                repeatTimer = REPEAT_MIN_TICKS + npc.getRandom().nextInt(REPEAT_RANDOM_TICKS);
            }
            return;
        }

        if (idleTimer <= 0 && happiness >= CONTENT_HAPPINESS) {
            show(npc, level, idleThought(npc.getRandom(), level), THOUGHT_TICKS);
            idleTimer = IDLE_MIN_TICKS + npc.getRandom().nextInt(IDLE_RANDOM_TICKS);
        }
    }

    /**
     * Show something that just happened — being attacked, finishing a meal,
     * levelling up — ahead of whatever the state pass would have picked.
     *
     * <p>Still gated on a nearby player: an event nobody is there to see is not
     * worth a network update, and the villager's {@link VillagerMemory} has
     * already recorded it either way.
     */
    public void showEvent(NpcEntity npc, ServerLevel level, ThoughtIcon icon, int ticks) {
        if (icon == null || !playerNearby(npc, level)) return;
        show(npc, level, icon, ticks);
        holdTimer = ticks;
        // An event overrides the standing-problem rota; give that its full gap
        // again so the villager does not immediately follow up with a complaint.
        repeatTimer = Math.max(repeatTimer, ticks + REPEAT_MIN_TICKS / 2);
    }

    /** True while a nearby player would actually see a bubble. */
    private static boolean playerNearby(NpcEntity npc, ServerLevel level) {
        return level.hasNearbyAlivePlayer(npc.getX(), npc.getY(), npc.getZ(), PLAYER_RANGE);
    }

    // =========================================================================

    private void show(NpcEntity npc, ServerLevel level, @Nullable ThoughtIcon icon, int ticks) {
        if (icon == null) return;
        npc.showBubble(icon, false, ticks);
        huh(npc, level, icon, 0.7f);
    }

    /**
     * The villager noise that draws the player's eye to a new bubble, shared
     * with conversations so a thought and a spoken line sound like the same
     * villager. Pitch carries the valence: a complaint sits below the neutral
     * pitch and good news above it, which is enough to tell them apart across a
     * village square without looking.
     */
    public static void huh(NpcEntity npc, ServerLevel level, ThoughtIcon icon, float volume) {
        float base = switch (icon.valence()) {
            case NEGATIVE -> 0.80f;
            case NEUTRAL  -> 1.00f;
            case POSITIVE -> 1.20f;
        };
        level.playSound(null, npc.getX(), npc.getY(), npc.getZ(),
                SoundEvents.VILLAGER_AMBIENT, SoundSource.NEUTRAL,
                volume, base + npc.getRandom().nextFloat() * 0.12f);
    }

    /** Weather is a shared experience and the cheapest conversation topic going. */
    private static void recordWeather(ServerLevel level, VillagerMemory memory, long gameTime) {
        if (level.isThundering())      memory.record(MemoryTopic.STORM, gameTime);
        else if (level.isRaining())    memory.record(MemoryTopic.RAIN, gameTime);
        else if (level.isDay())        memory.record(MemoryTopic.FINE_WEATHER, gameTime);
    }

    /** What a contented villager thinks about when nothing is wrong. */
    private static ThoughtIcon idleThought(RandomSource rnd, ServerLevel level) {
        if (level.isThundering() && rnd.nextBoolean()) return ThoughtIcon.EMOTE_WARNING;
        if (level.isRaining() && rnd.nextInt(3) == 0)  return ThoughtIcon.TOPIC_WEATHER;
        return switch (rnd.nextInt(6)) {
            case 0  -> ThoughtIcon.STATUS_HAPPY;
            case 1  -> ThoughtIcon.EMOTE_LAUGHTER;
            case 2  -> ThoughtIcon.TOPIC_NATURE;
            case 3  -> ThoughtIcon.EMOTE_ADMIRATION;
            case 4  -> ThoughtIcon.TOPIC_HOME;
            default -> ThoughtIcon.STATUS_SATISFIED;
        };
    }
}
