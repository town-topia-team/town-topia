package com.alex.test.village.villagers.rancher;

import com.alex.test.Tr;
import com.alex.test.village.building.RanchBuilding;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.EntityType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The last few things that happened at each pen, for the Ranch window (round 7 of
 * docs/plans/2026-10-24-ranch-window.md): a pair fed, eggs, wool, leather and milk collected, an
 * animal led in or given up on the way, the Butcher culling one of its animals. In memory only, so
 * a restart starts every pen empty; the away calculator adds nothing.
 */
public final class RanchHappenings {

    /** Lines kept per pen. */
    public static final int KEPT = 5;
    /** The same thing by the same villager to the same kind within this long joins the newest line: 3 minutes. */
    private static final long JOIN_TICKS = 3600L;

    public enum Kind { BRED, EGG, WOOL, HIDE, MILK, BROUGHT_BACK, ROUNDED_UP, GAVE_UP, BUTCHER_TOOK }

    /** Why a lead walk into the pen was given up; NONE for every other kind. */
    public enum GaveUp {
        NONE, REACH, LEAD_ON, STUCK, KEEP_UP, BACK_TO_IT, NO_WAY_IN, WOULD_NOT_COME_IN, GATE_HELD, SADDLED,
        CALLED_AWAY, LEAD_OFF, DIED
    }

    /**
     * One line: {@code who} did it, to {@code animal} (an entity type id), {@code count} times;
     * {@code amount} the wool or leather got in all (0 for none); {@code at} the game time of the
     * newest time it happened.
     */
    public record Happening(Kind kind, String who, String animal, int count, int amount, GaveUp why, long at) {}

    /** Server thread only; a pen that is gone takes its lines with it. */
    private static final Map<RanchBuilding, Deque<Happening>> LOG = new WeakHashMap<>();

    private RanchHappenings() {}

    public static void note(RanchBuilding ranch, Kind kind, String who, EntityType<?> animal, int amount, long now) {
        note(ranch, kind, who, animal, amount, GaveUp.NONE, now);
    }

    public static void note(RanchBuilding ranch, Kind kind, String who, EntityType<?> animal, int amount,
                            GaveUp why, long now) {
        String type = EntityType.getKey(animal).toString();
        Deque<Happening> lines = LOG.computeIfAbsent(ranch, r -> new ArrayDeque<>());
        Happening last = lines.peekFirst();
        if (last != null && last.kind() == kind && last.who().equals(who) && last.animal().equals(type)
                && last.why() == why && now - last.at() <= JOIN_TICKS) {
            lines.pollFirst();
            lines.addFirst(new Happening(kind, who, type, last.count() + 1, last.amount() + amount, why, now));
            return;
        }
        lines.addFirst(new Happening(kind, who, type, 1, amount, why, now));
        while (lines.size() > KEPT) lines.pollLast();
    }

    /** The pen's lines, newest first. */
    public static List<Happening> recent(RanchBuilding ranch) {
        Deque<Happening> lines = LOG.get(ranch);
        return lines == null ? List.of() : List.copyOf(new ArrayList<>(lines));
    }

    /** "Ada fed a pair of cows", "Bo collected 3 eggs", "Cy gave up leading a pig: it got stuck". */
    public static MutableComponent text(Happening line) {
        String who = line.who();
        int n = line.count();
        boolean one = n == 1;
        return switch (line.kind()) {
            case BRED -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.bred", "%s fed a pair of %s", who, animal(line.animal(), true))
                    : Tr.c("screen.dynamicvillages.ranch.happen.bred_n", "%s fed %s pairs of %s", who, n, animal(line.animal(), true));
            case EGG -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.egg", "%s collected an egg", who)
                    : Tr.c("screen.dynamicvillages.ranch.happen.egg_n", "%s collected %s eggs", who, n);
            case WOOL -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.wool", "%s sheared a sheep: %s wool", who, line.amount())
                    : Tr.c("screen.dynamicvillages.ranch.happen.wool_n", "%s sheared %s sheep: %s wool", who, n, line.amount());
            case HIDE -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.hide", "%s combed %s leather off a cow", who, line.amount())
                    : Tr.c("screen.dynamicvillages.ranch.happen.hide_n", "%s combed %s leather off %s cows", who, line.amount(), n);
            case MILK -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.milk", "%s milked a cow", who)
                    : Tr.c("screen.dynamicvillages.ranch.happen.milk_n", "%s milked %s cows", who, n);
            case BROUGHT_BACK -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.back", "%s brought a %s back in", who, animal(line.animal(), false))
                    : Tr.c("screen.dynamicvillages.ranch.happen.back_n", "%s brought %s %s back in", who, n, animal(line.animal(), true));
            case ROUNDED_UP -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.round_up", "%s rounded up a loose %s", who, animal(line.animal(), false))
                    : Tr.c("screen.dynamicvillages.ranch.happen.round_up_n", "%s rounded up %s loose %s", who, n, animal(line.animal(), true));
            case GAVE_UP -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.gave_up", "%s gave up leading a %s: %s", who, animal(line.animal(), false), why(line.why()))
                    : Tr.c("screen.dynamicvillages.ranch.happen.gave_up_n", "%s gave up leading a %s %s times: %s", who, animal(line.animal(), false), n, why(line.why()));
            case BUTCHER_TOOK -> one
                    ? Tr.c("screen.dynamicvillages.ranch.happen.butcher", "%s, the Butcher, culled a %s", who, animal(line.animal(), false))
                    : Tr.c("screen.dynamicvillages.ranch.happen.butcher_n", "%s, the Butcher, culled %s %s", who, n, animal(line.animal(), true));
        };
    }

    /** Whether the line is a walk given up (shown gold). */
    public static boolean isTrouble(Happening line) {
        return line.kind() == Kind.GAVE_UP;
    }

    /** "just now", "5 minutes ago", "2 hours ago": real time, rounded down. */
    public static MutableComponent ago(long ticks) {
        long minutes = Math.max(0, ticks) / 1200;
        if (minutes < 1) return Tr.c("screen.dynamicvillages.ranch.happen.now", "just now");
        if (minutes == 1) return Tr.c("screen.dynamicvillages.ranch.happen.minute", "1 minute ago");
        if (minutes < 60) return Tr.c("screen.dynamicvillages.ranch.happen.minutes", "%s minutes ago", minutes);
        long hours = minutes / 60;
        return hours == 1 ? Tr.c("screen.dynamicvillages.ranch.happen.hour", "1 hour ago")
                : Tr.c("screen.dynamicvillages.ranch.happen.hours", "%s hours ago", hours);
    }

    /** The animal in a sentence: "cow" or "cows"; a kind of no ranch by its own name. */
    private static String animal(String type, boolean many) {
        return switch (type) {
            case "minecraft:cow" -> many ? Tr.s("screen.dynamicvillages.ranch.happen.cows", "cows")
                    : Tr.s("screen.dynamicvillages.ranch.happen.cow", "cow");
            case "minecraft:sheep" -> Tr.s("screen.dynamicvillages.ranch.happen.sheep", "sheep");
            case "minecraft:pig" -> many ? Tr.s("screen.dynamicvillages.ranch.happen.pigs", "pigs")
                    : Tr.s("screen.dynamicvillages.ranch.happen.pig", "pig");
            case "minecraft:chicken" -> many ? Tr.s("screen.dynamicvillages.ranch.happen.chickens", "chickens")
                    : Tr.s("screen.dynamicvillages.ranch.happen.chicken", "chicken");
            default -> EntityType.byString(type).map(t -> t.getDescription().getString()).orElse(type);
        };
    }

    private static MutableComponent why(GaveUp why) {
        return switch (why) {
            case NONE, CALLED_AWAY -> Tr.c("screen.dynamicvillages.ranch.happen.why.called_away", "it was called away");
            case REACH -> Tr.c("screen.dynamicvillages.ranch.happen.why.reach", "could not reach it");
            case LEAD_ON -> Tr.c("screen.dynamicvillages.ranch.happen.why.lead_on", "could not put the lead on");
            case STUCK -> Tr.c("screen.dynamicvillages.ranch.happen.why.stuck", "it got stuck");
            case KEEP_UP -> Tr.c("screen.dynamicvillages.ranch.happen.why.keep_up", "it could not keep up");
            case BACK_TO_IT -> Tr.c("screen.dynamicvillages.ranch.happen.why.back_to_it", "could not get back to it");
            case NO_WAY_IN -> Tr.c("screen.dynamicvillages.ranch.happen.why.no_way_in", "no way into the pen");
            case WOULD_NOT_COME_IN -> Tr.c("screen.dynamicvillages.ranch.happen.why.would_not_come_in", "it would not come in");
            case GATE_HELD -> Tr.c("screen.dynamicvillages.ranch.happen.why.gate_held", "held the gate open too long");
            case SADDLED -> Tr.c("screen.dynamicvillages.ranch.happen.why.saddled", "a player saddled it");
            case LEAD_OFF -> Tr.c("screen.dynamicvillages.ranch.happen.why.lead_off", "the lead came off");
            case DIED -> Tr.c("screen.dynamicvillages.ranch.happen.why.died", "it died on the way");
        };
    }
}
