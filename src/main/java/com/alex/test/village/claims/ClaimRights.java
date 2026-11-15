package com.alex.test.village.claims;

import com.alex.test.village.claims.ClaimLock.Act;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which ranks may do what in one village (village claims round 9, the MineColonies grid). The owner switches
 * Officer, Friend and Visitor per row on the Mayor's Land > Rights page; Owner may do everything and Hostile
 * nothing, both fixed. A village starts with the plan's table ({@link #defaults()}). Saved inside the village's
 * {@code claim} tag only when it differs from the defaults, under the acts' lower-case names: never rename an
 * {@link Act} or a {@link ClaimRank}.
 */
public final class ClaimRights {

    /** The grid's rows, top to bottom. */
    public static final List<Act> ROWS = List.of(Act.BUILD, Act.USE, Act.DOOR, Act.PEN_ANIMAL, Act.SHOP, Act.LOOK,
            Act.MANAGE, Act.HARM_RESIDENT);
    /** The ranks the owner switches; Owner (all on) and Hostile (all off) are fixed. */
    public static final List<ClaimRank> COLUMNS = List.of(ClaimRank.OFFICER, ClaimRank.FRIEND, ClaimRank.VISITOR);

    /** Per act (by ordinal), the ranks allowed: bit = rank ordinal. Owner always set, Hostile never. */
    private final int[] allowed = new int[Act.values().length];

    private ClaimRights() {}

    /**
     * The plan's table: Officer all but hurting residents; Friend also not changing how the village runs; Visitor
     * doors and looking at windows. Nobody but the Owner hurts villagers, the Mayor, golems.
     */
    public static ClaimRights defaults() {
        ClaimRights rights = new ClaimRights();
        for (Act act : Act.values()) {
            rights.allowed[act.ordinal()] = defaultMask(act);
        }
        return rights;
    }

    private static int defaultMask(Act act) {
        int mask = bit(ClaimRank.OWNER);
        if (act != Act.HARM_RESIDENT) mask |= bit(ClaimRank.OFFICER);
        if (act != Act.HARM_RESIDENT && act != Act.MANAGE) mask |= bit(ClaimRank.FRIEND);
        if (act == Act.DOOR || act == Act.LOOK) mask |= bit(ClaimRank.VISITOR);
        return mask;
    }

    private static int bit(ClaimRank rank) {
        return 1 << rank.ordinal();
    }

    public ClaimRights copy() {
        ClaimRights rights = new ClaimRights();
        System.arraycopy(allowed, 0, rights.allowed, 0, allowed.length);
        return rights;
    }

    public boolean allows(ClaimRank rank, Act act) {
        if (rank == ClaimRank.OWNER) return true;
        if (rank == ClaimRank.HOSTILE) return false;
        return (allowed[act.ordinal()] & bit(rank)) != 0;
    }

    /** Whether this rank's switch can be changed (Officer, Friend, Visitor). */
    public static boolean switchable(ClaimRank rank) {
        return COLUMNS.contains(rank);
    }

    /** Switches one cell; false when the rank is fixed or nothing changes. */
    boolean set(ClaimRank rank, Act act, boolean on) {
        if (!switchable(rank) || allows(rank, act) == on) return false;
        allowed[act.ordinal()] ^= bit(rank);
        return true;
    }

    /** Every act this rank may not do, as a mask over act ordinals (sent with the land squares). */
    public int refusedActs(ClaimRank rank) {
        int refused = 0;
        for (Act act : Act.values()) {
            if (!allows(rank, act)) refused |= 1 << act.ordinal();
        }
        return refused;
    }

    public boolean isDefault() {
        for (Act act : Act.values()) {
            if (allowed[act.ordinal()] != defaultMask(act)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Saving and sending
    // ---------------------------------------------------------------------

    private static String saveName(Act act) {
        return act.name().toLowerCase(Locale.ROOT);
    }

    /** Each act: the switchable ranks that may do it, by save name. */
    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        for (Act act : Act.values()) {
            ListTag ranks = new ListTag();
            for (ClaimRank rank : COLUMNS) {
                if (allows(rank, act)) ranks.add(StringTag.valueOf(rank.saveName()));
            }
            tag.put(saveName(act), ranks);
        }
        return tag;
    }

    /** An act missing from the tag (saved before it existed) keeps its default. */
    static ClaimRights load(CompoundTag tag) {
        ClaimRights rights = defaults();
        for (Act act : Act.values()) {
            if (!tag.contains(saveName(act), Tag.TAG_LIST)) continue;
            int mask = bit(ClaimRank.OWNER);
            ListTag ranks = tag.getList(saveName(act), Tag.TAG_STRING);
            for (int i = 0; i < ranks.size(); i++) {
                // Matched by hand: bySaveName turns an unknown word into Friend, which would hand out a right.
                for (ClaimRank rank : COLUMNS) {
                    if (rank.saveName().equals(ranks.getString(i))) mask |= bit(rank);
                }
            }
            rights.allowed[act.ordinal()] = mask;
        }
        return rights;
    }

    public void write(FriendlyByteBuf buf) {
        for (int mask : allowed) {
            buf.writeByte(mask);
        }
    }

    public static ClaimRights read(FriendlyByteBuf buf) {
        ClaimRights rights = new ClaimRights();
        for (int i = 0; i < rights.allowed.length; i++) {
            rights.allowed[i] = (buf.readByte() & ~bit(ClaimRank.HOSTILE)) | bit(ClaimRank.OWNER);
        }
        return rights;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ClaimRights rights && Arrays.equals(allowed, rights.allowed);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(allowed);
    }
}
