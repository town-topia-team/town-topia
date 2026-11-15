package com.alex.test.village.social;

import java.util.List;

/**
 * A whole exchange of icons between two villagers, decided up front.
 *
 * <p>Both halves of a conversation get the same script object and each plays
 * only the lines it owns, so the two sides cannot drift apart: they are two
 * independent {@code ConverseActivity} instances ticking in the same village,
 * and anything that derived a reply on the fly would have to round-trip state
 * between them every couple of seconds. Deciding the exchange once, at pairing
 * time, makes the timing exact and the whole thing inspectable in one place.
 *
 * @param lines     ordered turns; {@code initiator} says whose line it is
 * @param lineTicks how long each turn is held before the next one starts
 * @param pauses    extra quiet ticks before each line (empty for none), so a
 *                  long chat does not run like a slideshow
 * @param topics    what the chat is about, for diaries (empty if unnamed)
 */
public record ConversationScript(List<Line> lines, int lineTicks, List<Integer> pauses, List<String> topics) {

    /** How long a conversation lingers after its last icon has been shown. */
    private static final int TAIL_TICKS = 30;

    /** An evenly paced script with no named topics. */
    public ConversationScript(List<Line> lines, int lineTicks) {
        this(lines, lineTicks, List.of(), List.of());
    }

    /**
     * One turn.
     *
     * @param initiator true for the villager that started the conversation
     *                  (the one constructed with {@code speaksFirst})
     * @param icon      what it shows over its head for this turn
     */
    public record Line(boolean initiator, ThoughtIcon icon) {}

    private int pauseBefore(int index) {
        return index < pauses.size() ? pauses.get(index) : 0;
    }

    /** Tick, relative to conversation start, at which {@code index} is shown. */
    public int startTick(int index) {
        int t = 0;
        for (int i = 0; i < index; i++) t += lineTicks + pauseBefore(i + 1);
        return t + pauseBefore(0);
    }

    /**
     * The line being spoken at {@code tick}, or -1 during a pause, before the
     * first line or after the last.
     */
    public int indexAt(int tick) {
        int t = 0;
        for (int i = 0; i < lines.size(); i++) {
            t += pauseBefore(i);
            if (tick < t) return -1;
            if (tick < t + lineTicks) return i;
            t += lineTicks;
        }
        return -1;
    }

    /** Total conversation length, including the pauses and the pause after the last line. */
    public int durationTicks() {
        int total = lines.size() * lineTicks + TAIL_TICKS;
        for (int i = 0; i < lines.size(); i++) total += pauseBefore(i);
        return total;
    }

    public int size() { return lines.size(); }

    public Line line(int index) { return lines.get(index); }

    /** Readable form for diaries and debug commands: {@code A:hungry B:sad …}. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Line line : lines) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(line.initiator() ? "A:" : "B:").append(line.icon().id());
        }
        return sb.toString();
    }
}
