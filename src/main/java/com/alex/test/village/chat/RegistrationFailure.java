package com.alex.test.village.chat;

import com.alex.test.Tr;
import com.alex.test.village.building.BuildingRequirements;
import com.alex.test.village.tokens.ItemFrameTokenRegistry.TokenType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;

/** Player-facing registration reasons. Edit the text here without changing the diagnosis. */
public enum RegistrationFailure {
    WRONG_TOWN_HALL("{building} not registered: this token belongs to a different Town Hall."),
    OUTSIDE_VILLAGE("{building} not registered: this token is outside its Town Hall's village."),
    PATH_GRAPH_UNAVAILABLE("{building} not registered: village paths are unavailable right now. Rebuild the path graph and try again."),
    DUPLICATE_TOWN_HALL("Town Hall not registered: this village already has a Town Hall."),

    ROOM_NO_DOOR("{building} not registered: no door was found beside the token."),
    ROOM_NO_CEILING("{building} not registered: there is no ceiling above the entrance at {x}, {y}, {z}."),
    ROOM_BAD_FLOOR("{building} not registered: villagers cannot stand on the floor just inside the door at {x}, {y}, {z}."),
    ROOM_BLOCKED_HEADROOM("{building} not registered: the entrance at {x}, {y}, {z} needs two clear blocks of headroom."),
    ROOM_UNSAFE_LEDGE("{building} not registered: the floor just inside the door at {x}, {y}, {z} is an unsafe ledge."),
    ROOM_UNREACHABLE("{building} not registered: the entrance has a ceiling, but villagers cannot reach it at {x}, {y}, {z} from the Town Hall."),
    ROOM_OPEN_TO_OUTSIDE("{building} not registered: the room is not enclosed. Check for an opening in its walls or roof."),
    ROOM_TOO_LARGE("{building} not registered: the room scan exceeded its size limit. Divide the space into smaller rooms."),
    ROOM_NOT_ASSIGNED("{building} not registered: no room belongs to this door. Check the entrance and nearby building tokens."),

    RANCH_NO_GATE("{building} not registered: no fence gate was found beside the token."),
    RANCH_BLOCKED_ENTRANCE("{building} not registered: villagers cannot stand just inside the gate at {x}, {y}, {z}."),
    RANCH_OPEN_FENCE("{building} not registered: the fenced area is not enclosed. Check the fence and gate for an opening."),
    RANCH_TOO_LARGE("{building} not registered: the fenced area exceeds the 512-tile limit."),
    RANCH_OPEN_OR_TOO_LARGE("{building} not registered: the fenced area is open or exceeds the 512-tile limit. Check its fence and size."),
    RANCH_GATE_UNREACHABLE("{building} not registered: the fenced area is enclosed, but villagers cannot reach the outside of its gate at {x}, {y}, {z}."),
    RANCH_CANNOT_ENTER_GATE("{building} not registered: villagers can reach the gate at {x}, {y}, {z}, but cannot pass into the fenced area."),
    RANCH_NO_REACHABLE_TILES("{building} not registered: villagers can enter the gate, but there is no reachable place to stand inside the fenced area."),

    MINE_UNREACHABLE("Mine not registered: villagers have no reachable place to stand at the mine entrance near {x}, {y}, {z}."),
    STALL_TOO_FEW_TILES("Merchant Stall not registered: the area in front of the token has only {count} of the {required} reachable tiles needed."),
    GUARD_POST_UNREACHABLE("Guard Post not registered: villagers cannot reach a patrol position near this token."),
    TOWER_NO_PLATFORM("Watchtower not registered: there is no floor a villager can stand on within 3 blocks of the token."),
    TOWER_UNREACHABLE("Watchtower not registered: villagers cannot get up to the platform. Give it stairs or a ladder."),
    TOWER_TOO_LOW("Watchtower not registered: the platform is only {count} blocks above the ground around it. It needs {required}.");

    private final String template;

    RegistrationFailure(String template) { this.template = template; }

    public String message(TokenType type, BlockPos pos) { return message(type, pos, 0, 0); }

    public String message(TokenType type, BlockPos pos, int count, int required) {
        return template.replace("{building}", BuildingRequirements.displayName(type))
                .replace("{x}", Integer.toString(pos.getX()))
                .replace("{y}", Integer.toString(pos.getY()))
                .replace("{z}", Integer.toString(pos.getZ()))
                .replace("{count}", Integer.toString(count))
                .replace("{required}", Integer.toString(required));
    }

    /** {@link #message} as text each player's own game translates. */
    public MutableComponent component(TokenType type, BlockPos pos, int count, int required) {
        // Same order as the {placeholders} in the English: building, x, y, z, count, required.
        Object[] args = {BuildingRequirements.name(type), pos.getX(), pos.getY(), pos.getZ(), count, required};
        return switch (this) {
            case WRONG_TOWN_HALL -> Tr.c("chat.dynamicvillages.failure.wrong_town_hall",
                    "%1$s not registered: this token belongs to a different Town Hall.", args);
            case OUTSIDE_VILLAGE -> Tr.c("chat.dynamicvillages.failure.outside_village",
                    "%1$s not registered: this token is outside its Town Hall's village.", args);
            case PATH_GRAPH_UNAVAILABLE -> Tr.c("chat.dynamicvillages.failure.path_graph_unavailable",
                    "%1$s not registered: village paths are unavailable right now. Rebuild the path graph and try again.", args);
            case DUPLICATE_TOWN_HALL -> Tr.c("chat.dynamicvillages.failure.duplicate_town_hall",
                    "Town Hall not registered: this village already has a Town Hall.", args);
            case ROOM_NO_DOOR -> Tr.c("chat.dynamicvillages.failure.room_no_door",
                    "%1$s not registered: no door was found beside the token.", args);
            case ROOM_NO_CEILING -> Tr.c("chat.dynamicvillages.failure.room_no_ceiling",
                    "%1$s not registered: there is no ceiling above the entrance at %2$s, %3$s, %4$s.", args);
            case ROOM_BAD_FLOOR -> Tr.c("chat.dynamicvillages.failure.room_bad_floor",
                    "%1$s not registered: villagers cannot stand on the floor just inside the door at %2$s, %3$s, %4$s.", args);
            case ROOM_BLOCKED_HEADROOM -> Tr.c("chat.dynamicvillages.failure.room_blocked_headroom",
                    "%1$s not registered: the entrance at %2$s, %3$s, %4$s needs two clear blocks of headroom.", args);
            case ROOM_UNSAFE_LEDGE -> Tr.c("chat.dynamicvillages.failure.room_unsafe_ledge",
                    "%1$s not registered: the floor just inside the door at %2$s, %3$s, %4$s is an unsafe ledge.", args);
            case ROOM_UNREACHABLE -> Tr.c("chat.dynamicvillages.failure.room_unreachable",
                    "%1$s not registered: the entrance has a ceiling, but villagers cannot reach it at %2$s, %3$s, %4$s from the Town Hall.", args);
            case ROOM_OPEN_TO_OUTSIDE -> Tr.c("chat.dynamicvillages.failure.room_open_to_outside",
                    "%1$s not registered: the room is not enclosed. Check for an opening in its walls or roof.", args);
            case ROOM_TOO_LARGE -> Tr.c("chat.dynamicvillages.failure.room_too_large",
                    "%1$s not registered: the room scan exceeded its size limit. Divide the space into smaller rooms.", args);
            case ROOM_NOT_ASSIGNED -> Tr.c("chat.dynamicvillages.failure.room_not_assigned",
                    "%1$s not registered: no room belongs to this door. Check the entrance and nearby building tokens.", args);
            case RANCH_NO_GATE -> Tr.c("chat.dynamicvillages.failure.ranch_no_gate",
                    "%1$s not registered: no fence gate was found beside the token.", args);
            case RANCH_BLOCKED_ENTRANCE -> Tr.c("chat.dynamicvillages.failure.ranch_blocked_entrance",
                    "%1$s not registered: villagers cannot stand just inside the gate at %2$s, %3$s, %4$s.", args);
            case RANCH_OPEN_FENCE -> Tr.c("chat.dynamicvillages.failure.ranch_open_fence",
                    "%1$s not registered: the fenced area is not enclosed. Check the fence and gate for an opening.", args);
            case RANCH_TOO_LARGE -> Tr.c("chat.dynamicvillages.failure.ranch_too_large",
                    "%1$s not registered: the fenced area exceeds the 512-tile limit.", args);
            case RANCH_OPEN_OR_TOO_LARGE -> Tr.c("chat.dynamicvillages.failure.ranch_open_or_too_large",
                    "%1$s not registered: the fenced area is open or exceeds the 512-tile limit. Check its fence and size.", args);
            case RANCH_GATE_UNREACHABLE -> Tr.c("chat.dynamicvillages.failure.ranch_gate_unreachable",
                    "%1$s not registered: the fenced area is enclosed, but villagers cannot reach the outside of its gate at %2$s, %3$s, %4$s.", args);
            case RANCH_CANNOT_ENTER_GATE -> Tr.c("chat.dynamicvillages.failure.ranch_cannot_enter_gate",
                    "%1$s not registered: villagers can reach the gate at %2$s, %3$s, %4$s, but cannot pass into the fenced area.", args);
            case RANCH_NO_REACHABLE_TILES -> Tr.c("chat.dynamicvillages.failure.ranch_no_reachable_tiles",
                    "%1$s not registered: villagers can enter the gate, but there is no reachable place to stand inside the fenced area.", args);
            case MINE_UNREACHABLE -> Tr.c("chat.dynamicvillages.failure.mine_unreachable",
                    "Mine not registered: villagers have no reachable place to stand at the mine entrance near %2$s, %3$s, %4$s.", args);
            case STALL_TOO_FEW_TILES -> Tr.c("chat.dynamicvillages.failure.stall_too_few_tiles",
                    "Merchant Stall not registered: the area in front of the token has only %5$s of the %6$s reachable tiles needed.", args);
            case GUARD_POST_UNREACHABLE -> Tr.c("chat.dynamicvillages.failure.guard_post_unreachable",
                    "Guard Post not registered: villagers cannot reach a patrol position near this token.", args);
            case TOWER_NO_PLATFORM -> Tr.c("chat.dynamicvillages.failure.tower_no_platform",
                    "Watchtower not registered: there is no floor a villager can stand on within 3 blocks of the token.", args);
            case TOWER_UNREACHABLE -> Tr.c("chat.dynamicvillages.failure.tower_unreachable",
                    "Watchtower not registered: villagers cannot get up to the platform. Give it stairs or a ladder.", args);
            case TOWER_TOO_LOW -> Tr.c("chat.dynamicvillages.failure.tower_too_low",
                    "Watchtower not registered: the platform is only %5$s blocks above the ground around it. It needs %6$s.", args);
        };
    }
}
