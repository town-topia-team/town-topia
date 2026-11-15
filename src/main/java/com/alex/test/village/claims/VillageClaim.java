package com.alex.test.village.claims;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Who owns a village (village claims, docs/plans/2026-10-24-village-claims.md). The player who hung the Town Hall
 * token that founded it, or one an operator named; none for a village that founded itself (vanilla takeover) or
 * one from before claims. Saved with the village, so it stays when the token moves or the village pauses.
 * Round 4 adds the people the owner listed (Officer, Friend, Hostile). Round 5 locks the land by it ({@link ClaimLock}):
 * every change here tells the games again ({@code ClaimLock.markDirty}).
 * Round 9 adds the rights grid ({@link ClaimRights}): which ranks may do what here, switched by the owner.
 * Round 10 adds the land guards ({@link LandGuard}): explosions, fire, endermen, each on unless the owner says no.
 */
public final class VillageClaim {

    private static final String TAG_OWNER = "owner";
    private static final String TAG_OWNER_NAME = "ownerName";
    private static final String TAG_MEMBERS = "members";
    private static final String TAG_ID = "id";
    private static final String TAG_NAME = "name";
    private static final String TAG_RANK = "rank";
    private static final String TAG_RIGHTS = "rights";
    private static final String TAG_GUARDS_OFF = "guardsOff";
    /** A list longer than this is refused, so the Mayor's opening data stays small. */
    public static final int MAX_MEMBERS = 64;

    /** A listed player: their name when last seen and the rank the owner gave them. */
    public record Member(String name, ClaimRank rank) {}

    @Nullable private UUID ownerId;
    /** The owner's name when last seen, so screens can name an owner who is offline. */
    private String ownerName = "";
    /** Listed players in the order they were added; never the owner, never a Visitor or Owner rank. */
    private final Map<UUID, Member> members = new LinkedHashMap<>();
    /** Which ranks may do what here (round 9); back to the defaults when nobody owns the village. */
    private ClaimRights rights = ClaimRights.defaults();
    /** The land guards the owner switched off (bit = {@link LandGuard.Kind} ordinal, round 10); 0 = all on. */
    private int guardsOff;

    public boolean hasOwner() {
        return ownerId != null;
    }

    @Nullable
    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public boolean isOwner(UUID playerId) {
        return playerId.equals(ownerId);
    }

    public void setOwner(UUID playerId, String name) {
        ownerId = playerId;
        ownerName = name == null ? "" : name;
        members.remove(playerId);
        ClaimLock.markDirty();
    }

    /** No owner, and so nobody listed either: whoever claims it next starts with an empty list. */
    public void clearOwner() {
        ownerId = null;
        ownerName = "";
        members.clear();
        rights = ClaimRights.defaults();
        guardsOff = 0;
        ClaimLock.markDirty();
    }

    /** The player's rank here: Owner, their listed rank, or Visitor. */
    public ClaimRank rankOf(UUID playerId) {
        if (isOwner(playerId)) return ClaimRank.OWNER;
        Member member = members.get(playerId);
        return member != null ? member.rank() : ClaimRank.VISITOR;
    }

    public Map<UUID, Member> members() {
        return Collections.unmodifiableMap(members);
    }

    /** Whether this player may do this here, by their rank and this village's rights grid (round 9). */
    public boolean allows(UUID playerId, ClaimLock.Act act) {
        return rights.allows(rankOf(playerId), act);
    }

    /** Everything this player may not do here, as a mask over act ordinals. */
    public int refusedActs(UUID playerId) {
        return rights.refusedActs(rankOf(playerId));
    }

    /** A copy of the grid, for the Land tab. */
    public ClaimRights rights() {
        return rights.copy();
    }

    /** Whether this land guard is on here (round 10): on unless the owner switched it off. */
    public boolean guards(LandGuard.Kind kind) {
        return (guardsOff & 1 << kind.ordinal()) == 0;
    }

    /** The guards switched off, for the Land tab (bit = kind ordinal). */
    public int guardsOff() {
        return guardsOff;
    }

    /** Switches one land guard; false when nobody owns the village or nothing changes. */
    public boolean setGuard(LandGuard.Kind kind, boolean on) {
        if (ownerId == null || guards(kind) == on) return false;
        guardsOff ^= 1 << kind.ordinal();
        return true;
    }

    /** Switches one cell of the grid; false for a fixed rank (Owner, Hostile) or no change. */
    public boolean setRight(ClaimRank rank, ClaimLock.Act act, boolean on) {
        if (ownerId == null || !rights.set(rank, act, on)) return false;
        ClaimLock.markDirty();
        return true;
    }

    public boolean isFull() {
        return members.size() >= MAX_MEMBERS;
    }

    /**
     * Lists the player with this rank, or changes the rank of one already listed. Refused (false) for the owner,
     * a rank no one can be given (Owner, Visitor), a new name on a full list, or when nothing would change.
     */
    public boolean setMember(UUID playerId, String name, ClaimRank rank) {
        if (ownerId == null || isOwner(playerId) || !rank.isListed()) return false;
        Member old = members.get(playerId);
        if (old == null && isFull()) return false;
        String keptName = name == null || name.isEmpty() ? old != null ? old.name() : "" : name;
        if (old != null && old.rank() == rank && old.name().equals(keptName)) return false;
        members.put(playerId, new Member(keptName, rank));
        ClaimLock.markDirty();
        return true;
    }

    public boolean removeMember(UUID playerId) {
        if (members.remove(playerId) == null) return false;
        ClaimLock.markDirty();
        return true;
    }

    /**
     * The owner hands the village to a listed Officer or Friend: they become the owner, the old owner an Officer.
     * False when that player is not listed, or is Hostile.
     */
    public boolean handOver(UUID newOwnerId) {
        Member member = members.get(newOwnerId);
        if (ownerId == null || member == null || member.rank() == ClaimRank.HOSTILE) return false;
        UUID oldOwner = ownerId;
        String oldName = ownerName;
        members.remove(newOwnerId);
        ownerId = newOwnerId;
        ownerName = member.name();
        members.put(oldOwner, new Member(oldName, ClaimRank.OFFICER));
        ClaimLock.markDirty();
        return true;
    }

    /** The player played under another name: true when a saved name (owner or listed) changed. */
    public boolean renamePlayer(UUID playerId, String name) {
        if (name == null) return false;
        boolean changed = false;
        if (isOwner(playerId) && !name.equals(ownerName)) {
            ownerName = name;
            changed = true;
        }
        Member member = members.get(playerId);
        if (member != null && !name.equals(member.name())) {
            members.put(playerId, new Member(name, member.rank()));
            changed = true;
        }
        if (changed) ClaimLock.markDirty();
        return changed;
    }

    /** Empty when nobody owns the village, so older saves and unowned villages write nothing. */
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (ownerId != null) {
            tag.putUUID(TAG_OWNER, ownerId);
            tag.putString(TAG_OWNER_NAME, ownerName);
            if (!members.isEmpty()) {
                ListTag list = new ListTag();
                for (Map.Entry<UUID, Member> entry : members.entrySet()) {
                    CompoundTag one = new CompoundTag();
                    one.putUUID(TAG_ID, entry.getKey());
                    one.putString(TAG_NAME, entry.getValue().name());
                    one.putString(TAG_RANK, entry.getValue().rank().saveName());
                    list.add(one);
                }
                tag.put(TAG_MEMBERS, list);
            }
            if (!rights.isDefault()) tag.put(TAG_RIGHTS, rights.save());
            if (guardsOff != 0) {
                ListTag off = new ListTag();
                for (LandGuard.Kind kind : LandGuard.Kind.values()) {
                    if (!guards(kind)) off.add(net.minecraft.nbt.StringTag.valueOf(kind.saveName()));
                }
                tag.put(TAG_GUARDS_OFF, off);
            }
        }
        return tag;
    }

    public void load(@Nullable CompoundTag tag) {
        clearOwner();
        if (tag == null || !tag.hasUUID(TAG_OWNER)) return;
        setOwner(tag.getUUID(TAG_OWNER), tag.getString(TAG_OWNER_NAME));
        ListTag list = tag.getList(TAG_MEMBERS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size() && members.size() < MAX_MEMBERS; i++) {
            CompoundTag one = list.getCompound(i);
            if (!one.hasUUID(TAG_ID)) continue;
            UUID id = one.getUUID(TAG_ID);
            ClaimRank rank = ClaimRank.bySaveName(one.getString(TAG_RANK));
            if (isOwner(id) || !rank.isListed()) continue;
            members.put(id, new Member(one.getString(TAG_NAME), rank));
        }
        if (tag.contains(TAG_RIGHTS, Tag.TAG_COMPOUND)) rights = ClaimRights.load(tag.getCompound(TAG_RIGHTS));
        ListTag off = tag.getList(TAG_GUARDS_OFF, Tag.TAG_STRING);
        for (int i = 0; i < off.size(); i++) {
            for (LandGuard.Kind kind : LandGuard.Kind.values()) {
                if (kind.saveName().equals(off.getString(i))) guardsOff |= 1 << kind.ordinal();
            }
        }
    }
}
