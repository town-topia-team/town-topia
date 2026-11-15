package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.village.building.ArcheryRangeBuilding;
import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.building.WatchtowerBuilding;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain, step by step help for the two tokens players most often hang in the
 * wrong place: the Watchtower and the Archery Range. A failed try says what
 * is wrong in red and how to fix it in grey, in words a first-time player
 * understands; a second failed try adds the whole recipe; a try that got
 * further than the last one says so.
 *
 * <p>Other tokens keep {@link RegistrationFailure}'s wording.
 */
public final class PlacementHelp {

    private PlacementHelp() {}

    /** Why a watchtower counted as too low, for the plainest way to say it. */
    public enum TowerLow {
        /** Measured, and short of the height. */
        SHORT,
        /** The token hangs at ground level with nothing high near it. */
        ON_GROUND,
        /** The token hangs at the foot of a tower whose floor is up above it. */
        TOKEN_AT_BOTTOM,
        /** The token is on a lower floor of a tower with a high enough floor further up. */
        LOWER_FLOOR,
        /** Everything around is as tall as the platform: no ground to measure from. */
        BOXED_IN
    }

    /** What is wrong (after "not working yet: ") and how to fix it; {@code fix} may be null. */
    public record Problem(@javax.annotation.Nullable MutableComponent what,
                          @javax.annotation.Nullable MutableComponent fix) {}

    public static boolean covers(TokenType type) {
        return type == TokenType.WATCHTOWER || type == TokenType.ARCHERY_RANGE;
    }

    /** The plain words for a failure; {@code low} matters only for a tower that is too low. */
    public static Problem problem(TokenType type, RegistrationFailure reason, BlockPos at,
                                  int count, int required, TowerLow low) {
        String where = at.getX() + ", " + at.getY() + ", " + at.getZ();
        switch (reason) {
            case WRONG_TOWN_HALL:
                return new Problem(Tr.c("chat.dynamicvillages.placement.wrong_town_hall", "this token belongs to a different village"),
                        Tr.c("chat.dynamicvillages.placement.wrong_town_hall.fix", "Use a token bought from this village's Mayor."));
            case OUTSIDE_VILLAGE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.outside_village", "this spot is outside your village"),
                        Tr.c("chat.dynamicvillages.placement.outside_village.fix", "Build it closer to the village."));
            case PATH_GRAPH_UNAVAILABLE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.paths", "the villagers are still learning the paths around here"),
                        Tr.c("chat.dynamicvillages.placement.paths.fix", "Wait a few seconds, then right click the token again."));
            case TOWER_NO_PLATFORM:
                return new Problem(Tr.c("chat.dynamicvillages.placement.tower_no_platform", "there is no floor next to the token"),
                        Tr.c("chat.dynamicvillages.placement.tower_no_platform.fix", "Put the token up on the tower's floor, not on the side of the tower. Villagers also need 2 blocks of open space above the floor, so keep any roof high enough."));
            case TOWER_UNREACHABLE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.tower_unreachable", "villagers can't climb up there"),
                        Tr.c("chat.dynamicvillages.placement.tower_unreachable.fix", "Add a ladder or stairs that go all the way from the ground to the top floor."));
            case TOWER_TOO_LOW:
                return switch (low) {
                    case TOKEN_AT_BOTTOM -> new Problem(Tr.c("chat.dynamicvillages.placement.tower_token_at_bottom", "the token is too close to the ground"),
                        Tr.c("chat.dynamicvillages.placement.tower_token_at_bottom.fix", "Climb up and put the token on the top floor of the tower."));
                    case LOWER_FLOOR -> new Problem(Tr.c("chat.dynamicvillages.placement.tower_lower_floor", "the token is on a floor only %s blocks high", count),
                        Tr.c("chat.dynamicvillages.placement.tower_lower_floor.fix", "There is a higher floor above (the green sparkles). Put the token up there."));
                    case ON_GROUND -> new Problem(Tr.c("chat.dynamicvillages.placement.tower_on_ground", "the token is on the ground"),
                        Tr.c("chat.dynamicvillages.placement.tower_on_ground.fix", "A watchtower needs a floor high up. Build one at least %s blocks high and put the token there.", WatchtowerBuilding.MIN_HEIGHT));
                    case BOXED_IN -> new Problem(Tr.c("chat.dynamicvillages.placement.tower_boxed_in", "the buildings around the tower are as tall as it is"),
                        Tr.c("chat.dynamicvillages.placement.tower_boxed_in.fix", "Leave about 3 blocks of open ground around the tower, or build it taller than the buildings next to it."));
                    case SHORT -> {
                        int more = Math.max(1, required - count);
                        yield new Problem(Tr.c("chat.dynamicvillages.placement.tower_short", "it is only %1$s blocks high, and it needs %2$s", count, required),
                        Tr.c("chat.dynamicvillages.placement.tower_short.fix", "Raise the floor %s blocks (or build a floor higher up) and put the token on it. Walls on top do not count: the floor is what is measured.", more));
                    }
                };
            case RANCH_NO_GATE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.no_gate", "there is no gate or door next to the token"),
                        Tr.c("chat.dynamicvillages.placement.no_gate.fix", "Hang the token on the fence right next to the gate. For an indoor range, hang it on the wall right next to the door."));
            case RANCH_OPEN_FENCE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.open_fence", "the fence has a gap"),
                        Tr.c("chat.dynamicvillages.placement.open_fence.fix", "Walk along the fence and close every gap. A single block is not enough: villagers step over it. Use fences, walls, or blocks 2 high."));
            case RANCH_OPEN_OR_TOO_LARGE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.open_or_too_large", "the fence has a gap, or the yard is too big"),
                        Tr.c("chat.dynamicvillages.placement.open_or_too_large.fix", "Close every gap in the fence (fences, walls, or blocks 2 high), and keep the yard to 512 blocks of floor or less, about 20 by 25."));
            case RANCH_TOO_LARGE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.yard_too_large", "the yard is too big"),
                        Tr.c("chat.dynamicvillages.placement.yard_too_large.fix", "Make it smaller: 512 blocks of floor at most, about 20 by 25."));
            case RANCH_BLOCKED_ENTRANCE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.gate_blocked", "the spot just inside the gate is blocked"),
                        Tr.c("chat.dynamicvillages.placement.gate_blocked.fix", "Clear the ground just inside the gate (at %s) so villagers can step in.", where));
            case RANCH_GATE_UNREACHABLE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.gate_unreachable", "villagers can't get to the gate"),
                        Tr.c("chat.dynamicvillages.placement.gate_unreachable.fix", "Make a path from the village to the outside of the gate (at %s).", where));
            case RANCH_CANNOT_ENTER_GATE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.gate_cannot_enter", "villagers can't get through the gate"),
                        Tr.c("chat.dynamicvillages.placement.gate_cannot_enter.fix", "Make sure nothing blocks the gate (at %s), on either side.", where));
            case RANCH_NO_REACHABLE_TILES:
                return new Problem(Tr.c("chat.dynamicvillages.placement.no_floor_inside", "there is no floor inside to stand on"),
                        Tr.c("chat.dynamicvillages.placement.no_floor_inside.fix", "Leave open floor inside the fence."));
            case ROOM_NO_DOOR:
                return new Problem(Tr.c("chat.dynamicvillages.placement.no_door", "there is no door next to the token"),
                        Tr.c("chat.dynamicvillages.placement.no_door.fix", "Hang the token on the wall right next to the door, or on the fence next to a gate."));
            case ROOM_NO_CEILING:
                return new Problem(Tr.c("chat.dynamicvillages.placement.no_roof", "the room has no roof above the door"),
                        Tr.c("chat.dynamicvillages.placement.no_roof.fix", "An indoor range needs walls and a roof. For an outdoor range, use a fence and a gate instead of a door."));
            case ROOM_OPEN_TO_OUTSIDE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.room_hole", "the room has a hole in its walls or roof"),
                        Tr.c("chat.dynamicvillages.placement.room_hole.fix", "Close the hole. Or make an outdoor range instead: a fenced yard with a gate."));
            case ROOM_TOO_LARGE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.room_too_large", "the room is too big"),
                        Tr.c("chat.dynamicvillages.placement.room_too_large.fix", "Make it smaller, or make an outdoor range with a fence and a gate."));
            case ROOM_BAD_FLOOR:
            case ROOM_BLOCKED_HEADROOM:
            case ROOM_UNSAFE_LEDGE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.door_blocked", "villagers can't walk in through the door"),
                        Tr.c("chat.dynamicvillages.placement.door_blocked.fix", "Clear the floor and 2 blocks of space just inside the door (at %s).", where));
            case ROOM_UNREACHABLE:
                return new Problem(Tr.c("chat.dynamicvillages.placement.door_unreachable", "villagers can't get to the door"),
                        Tr.c("chat.dynamicvillages.placement.door_unreachable.fix", "Make a path from the village to the door (at %s).", where));
            case ROOM_NOT_ASSIGNED:
                return new Problem(Tr.c("chat.dynamicvillages.placement.room_taken", "another building's door already uses this room"),
                        Tr.c("chat.dynamicvillages.placement.room_taken.fix", "Give the range its own room and its own door."));
            default:
                return new Problem(null, null);
        }
    }

    /**
     * The chat lines for a failed try: the red problem, the grey fix, and how
     * to try again. {@code closer}: a further step than the last try failed,
     * so say that the earlier part is fixed. {@code recipe}: add the whole
     * recipe (from the second failed try on).
     */
    public static List<Component> failureLines(TokenType type, RegistrationFailure reason, Problem problem,
                                               BlockPos at, int count, int required,
                                               boolean closer, boolean recipe, Component tryAgain) {
        List<Component> lines = new ArrayList<>();
        if (closer) {
            lines.add(Tr.c("chat.dynamicvillages.placement.closer", "[Village] Getting closer! That part is fixed now.")
                    .withStyle(ChatFormatting.GREEN));
        }
        MutableComponent red = problem.what() != null
                ? Tr.c("chat.dynamicvillages.placement.not_working", "%1$s not working yet: %2$s.",
                        BuildingRequirements.name(type), problem.what())
                : reason.component(type, at, count, required);
        lines.add(Component.literal("[Village] ").append(red).withStyle(ChatFormatting.RED));
        if (problem.fix() != null) lines.add(problem.fix().copy().withStyle(ChatFormatting.GRAY));
        if (recipe) lines.addAll(recipe(type));
        lines.add(tryAgain.copy().withStyle(ChatFormatting.DARK_GRAY));
        return lines;
    }

    /** The whole recipe, step by step: one translatable text, its lines split by "
". */
    public static List<Component> recipe(TokenType type) {
        return stepLines(type);
    }

    /**
     * The recipe as one translatable text per token, split into lines. Sent
     * as components, so each step is translated for each player.
     */
    private static List<Component> stepLines(TokenType type) {
        MutableComponent text = type == TokenType.WATCHTOWER
                ? Tr.c("chat.dynamicvillages.placement.recipe.tower",
                        "How to build a watchtower:\n1. Build a floor at least %s blocks above the ground.\n"
                                + "2. Add a ladder or stairs up to it.\n"
                                + "3. Put the token in an item frame on that floor, near the middle.",
                        WatchtowerBuilding.MIN_HEIGHT)
                : Tr.c("chat.dynamicvillages.placement.recipe.range",
                        "How to build an archery range:\n1. Fence in a yard and put a fence gate in the fence.\n"
                                + "2. Put the token in an item frame on the fence, right next to the gate.\n"
                                + "3. Put target blocks inside, with %1$s to %2$s blocks of open floor in front of each.\n"
                                + "Indoors works too: a room with walls, a roof and a door, with the token next to the door.",
                        (int) ArcheryRangeBuilding.MIN_LANE, (int) ArcheryRangeBuilding.MAX_LANE);
        // Chat shows "\n" inside one message as a line break, so one component is enough.
        return List.of(text.withStyle(ChatFormatting.YELLOW));
    }

    /**
     * How far along a try got: a higher number failed at a later step, so the
     * earlier steps are done. 0 for a reason with no place in the order.
     */
    public static int progress(RegistrationFailure reason) {
        return switch (reason) {
            case TOWER_NO_PLATFORM, RANCH_NO_GATE, ROOM_NO_DOOR -> 1;
            case TOWER_UNREACHABLE, RANCH_OPEN_FENCE, RANCH_OPEN_OR_TOO_LARGE, RANCH_TOO_LARGE,
                 ROOM_NO_CEILING, ROOM_OPEN_TO_OUTSIDE, ROOM_TOO_LARGE, ROOM_NOT_ASSIGNED -> 2;
            case TOWER_TOO_LOW, RANCH_BLOCKED_ENTRANCE, ROOM_BAD_FLOOR, ROOM_BLOCKED_HEADROOM,
                 ROOM_UNSAFE_LEDGE -> 3;
            case RANCH_GATE_UNREACHABLE, RANCH_CANNOT_ENTER_GATE, RANCH_NO_REACHABLE_TILES,
                 ROOM_UNREACHABLE -> 4;
            default -> 0;
        };
    }

}
