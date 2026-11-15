package com.alex.test.village.villagers.builder.blueprint;

/**
 * A blueprint file that could not be read or is not allowed. The message is a
 * short plain sentence the Builder's desk shows the player as it is, so it says
 * what is wrong with the file, never which Java call failed.
 */
public class BlueprintException extends Exception {
    public BlueprintException(String playerMessage) {
        super(playerMessage);
    }

    public BlueprintException(String playerMessage, Throwable cause) {
        super(playerMessage, cause);
    }
}
