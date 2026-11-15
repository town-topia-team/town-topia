package com.alex.test.block;

import java.util.List;
import java.util.UUID;

/**
 * Who may have ZalithYT's Piano and its bench (docs/plans/2026-10-24-zalith-piano-lock.md). No Minecraft classes,
 * so JUnit can test it; {@link PianoLock} asks it about real players.
 *
 * <p>Two locks. Where the server checks accounts, the account id must be listed: a rename keeps working and
 * nobody can borrow a name. Where it does not (offline mode, every id made from the name), the name must be
 * listed, in any capitals.
 */
public final class PianoOwner {
    private PianoOwner() {}

    /** An owner's account id (Mojang lookup 10-24, checked id to name and back) and name. */
    public record Owner(UUID id, String name) {}

    /** User 10-24: ZalithYT, Heragoga and UrEasyKill76 (UrEasyKill76 was off the list for part of that day, then back). */
    public static final List<Owner> OWNERS = List.of(
            new Owner(UUID.fromString("da05228d-ec51-4add-af6a-3b3453e6e580"), "ZalithYT"),
            new Owner(UUID.fromString("8771dc79-ffe9-4dc6-a8e6-af4793f04601"), "Heragoga"),
            new Owner(UUID.fromString("51a1540b-fe5c-43c5-9a0b-f2a4e056dbe4"), "UrEasyKill76"));

    public static boolean matches(UUID id, String name, boolean accountsChecked) {
        for (Owner owner : OWNERS) {
            if (accountsChecked ? owner.id().equals(id) : owner.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }
}
