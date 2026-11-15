package com.alex.test.village.claims;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;

/** Server-side questions and actions about village claims (plan docs/plans/2026-10-24-village-claims.md). */
public final class VillageClaims {
    private static final Logger LOGGER = LogUtils.getLogger();

    private VillageClaims() {}

    public enum ClaimResult {
        /** The player owns the village now. */
        CLAIMED,
        /** The player already owned it (a second press of Claim): nothing changed. */
        ALREADY_YOURS,
        /** Someone else claimed it first. */
        TAKEN,
        /** Claims are off, or this is no registered village. */
        NOT_CLAIMABLE
    }

    /**
     * Whether the Mayor of this running village shows only its Claim page (round 2): claims on in this world,
     * a village of the registry (not the debug village), and nobody owns it.
     */
    public static boolean showsClaimPage(ServerLevel level, @Nullable VillageManager village) {
        if (village == null || level.getServer() == null) return false;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        return registry.claimsEnabled()
                && registry.get(village.getVillageId()) == village
                && !village.claim().hasOwner();
    }

    /** The player pressed Claim at the Mayor: the first press owns the village. */
    public static ClaimResult claim(ServerPlayer player, @Nullable VillageManager village) {
        if (player instanceof FakePlayer || !showsClaimPageOrOwned(player.serverLevel(), village)) {
            return ClaimResult.NOT_CLAIMABLE;
        }
        VillageClaim claim = village.claim();
        if (claim.isOwner(player.getUUID())) return ClaimResult.ALREADY_YOURS;
        if (claim.hasOwner()) return ClaimResult.TAKEN;
        claim.setOwner(player.getUUID(), player.getGameProfile().getName());
        village.markPersistentDataDirty();
        LOGGER.info("[Claims] Village {} claimed at the Mayor by {}.",
                village.getVillageId().toString().substring(0, 8), player.getGameProfile().getName());
        return ClaimResult.CLAIMED;
    }

    /**
     * Owner offline (plan docs/plans/2026-10-25-owner-offline-no-production.md): whether this owned village has its
     * owner or one of its Officers on the server, in any dimension (spectators too). While none is, it rests.
     */
    public static boolean ownerOrOfficerOnline(MinecraftServer server, VillageClaim claim) {
        PlayerList players = server.getPlayerList();
        UUID owner = claim.ownerId();
        if (owner != null && players.getPlayer(owner) != null) return true;
        for (Map.Entry<UUID, VillageClaim.Member> member : claim.members().entrySet()) {
            if (member.getValue().rank() == ClaimRank.OFFICER && players.getPlayer(member.getKey()) != null) return true;
        }
        return false;
    }

    /**
     * Whether this player hears the village's own news (the welcome-back lines): anyone while claims are off or
     * nobody owns it, else the owner, Officers and Friends; a Visitor walking through is not welcomed back.
     */
    public static boolean hearsNews(ServerPlayer player, VillageManager village) {
        if (player.getServer() == null || !VillageManagerRegistry.get(player.getServer()).claimsEnabled()) return true;
        VillageClaim claim = village.claim();
        if (!claim.hasOwner()) return true;
        ClaimRank rank = claim.rankOf(player.getUUID());
        return rank == ClaimRank.OWNER || rank == ClaimRank.OFFICER || rank == ClaimRank.FRIEND;
    }

    /** As {@link #showsClaimPage}, but an owned village still counts: Claim pressed twice, or by two players at once. */
    private static boolean showsClaimPageOrOwned(ServerLevel level, @Nullable VillageManager village) {
        if (village == null || level.getServer() == null) return false;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        return registry.claimsEnabled() && registry.get(village.getVillageId()) == village;
    }

    // ---------------------------------------------------------------------
    // People (round 4): only the owner changes the list; nothing is locked by it yet
    // ---------------------------------------------------------------------

    /** What the People page asked for. */
    public enum PeopleAction {
        ADD_NAME, ADD_ONLINE, SET_RANK, REMOVE, HAND_OVER, GIVE_UP;

        @Nullable
        public static PeopleAction byIndex(int index) {
            PeopleAction[] all = values();
            return index >= 0 && index < all.length ? all[index] : null;
        }
    }

    /** How a People change went; the client words it (LandTab). */
    public enum PeopleReply {
        ADDED, RANK_SET, REMOVED, HANDED_OVER, GAVE_UP,
        ALREADY_LISTED, NOT_FOUND, IS_OWNER, FULL, NOT_OWNER, NOT_LISTED, HOSTILE_HEIR,
        /** Rights page (round 9): a switch saved; a switch of Owner or Hostile, which are fixed. */
        RIGHT_SET, RIGHT_FIXED,
        /** Protection page (round 10): a land guard switched. */
        GUARD_SET;

        public boolean success() {
            return ordinal() <= GAVE_UP.ordinal() || this == RIGHT_SET || this == GUARD_SET;
        }

        public static PeopleReply byIndex(int index) {
            PeopleReply[] all = values();
            return index >= 0 && index < all.length ? all[index] : NOT_FOUND;
        }
    }

    /** Whether the player may change this village's people list: its owner only (an Officer may not, plan). */
    public static boolean managesPeople(ServerPlayer player, @Nullable VillageManager village) {
        if (player instanceof FakePlayer || village == null || player.getServer() == null) return false;
        VillageManagerRegistry registry = VillageManagerRegistry.get(player.getServer());
        return registry.get(village.getVillageId()) == village && village.claim().isOwner(player.getUUID());
    }

    /** Lists a player (found online or by name) with this rank; one already listed keeps theirs. */
    public static PeopleReply addPerson(ServerPlayer owner, VillageManager village, UUID id, String name, ClaimRank rank) {
        VillageClaim claim = village.claim();
        if (claim.isOwner(id)) return PeopleReply.IS_OWNER;
        if (claim.members().containsKey(id)) return PeopleReply.ALREADY_LISTED;
        if (claim.isFull()) return PeopleReply.FULL;
        if (!claim.setMember(id, name, rank.isListed() ? rank : ClaimRank.FRIEND)) return PeopleReply.NOT_FOUND;
        changed(owner, village, "listed " + name + " as " + claim.rankOf(id).saveName());
        return PeopleReply.ADDED;
    }

    public static PeopleReply setRank(ServerPlayer owner, VillageManager village, UUID id, ClaimRank rank) {
        VillageClaim claim = village.claim();
        VillageClaim.Member member = claim.members().get(id);
        if (member == null) return PeopleReply.NOT_LISTED;
        if (!rank.isListed()) return PeopleReply.NOT_FOUND;
        if (member.rank() == rank) return PeopleReply.RANK_SET;
        claim.setMember(id, member.name(), rank);
        changed(owner, village, "made " + member.name() + " " + rank.saveName());
        return PeopleReply.RANK_SET;
    }

    public static PeopleReply remove(ServerPlayer owner, VillageManager village, UUID id) {
        VillageClaim.Member member = village.claim().members().get(id);
        if (member == null || !village.claim().removeMember(id)) return PeopleReply.NOT_LISTED;
        changed(owner, village, "took " + member.name() + " off the list");
        return PeopleReply.REMOVED;
    }

    /** The owner hands the village to a listed Officer or Friend; the old owner stays on as an Officer. */
    public static PeopleReply handOver(ServerPlayer owner, VillageManager village, UUID id) {
        VillageClaim.Member member = village.claim().members().get(id);
        if (member == null) return PeopleReply.NOT_LISTED;
        if (member.rank() == ClaimRank.HOSTILE) return PeopleReply.HOSTILE_HEIR;
        if (!village.claim().handOver(id)) return PeopleReply.NOT_LISTED;
        changed(owner, village, "handed the village to " + member.name());
        return PeopleReply.HANDED_OVER;
    }

    /** Nobody owns it any more, and nobody is listed: with claims on the Mayor shows the Claim page again. */
    public static PeopleReply giveUp(ServerPlayer owner, VillageManager village) {
        village.claim().clearOwner();
        changed(owner, village, "gave the village up");
        return PeopleReply.GAVE_UP;
    }

    /** One switch of the rights grid (round 9). Owner and Hostile are fixed; no change still counts as saved. */
    public static PeopleReply setRight(ServerPlayer owner, VillageManager village, ClaimRank rank, ClaimLock.Act act,
                                       boolean on) {
        if (!ClaimRights.switchable(rank)) return PeopleReply.RIGHT_FIXED;
        if (village.claim().setRight(rank, act, on)) {
            changed(owner, village, (on ? "let " : "stopped ") + rank.saveName() + " " + act.name().toLowerCase(java.util.Locale.ROOT));
        }
        return PeopleReply.RIGHT_SET;
    }

    /** One land guard on or off (round 10); no change still counts as saved. */
    public static PeopleReply setGuard(ServerPlayer owner, VillageManager village, LandGuard.Kind kind, boolean on) {
        if (village.claim().setGuard(kind, on)) {
            changed(owner, village, "turned the " + kind.saveName() + " guard " + (on ? "on" : "off"));
        }
        return PeopleReply.GUARD_SET;
    }

    private static void changed(ServerPlayer owner, VillageManager village, String what) {
        village.markPersistentDataDirty();
        LOGGER.info("[Claims] Village {}: {} {}.", village.getVillageId().toString().substring(0, 8),
                owner.getGameProfile().getName(), what);
    }
}
