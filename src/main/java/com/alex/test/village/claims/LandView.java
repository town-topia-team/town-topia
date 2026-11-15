package com.alex.test.village.claims;

import com.alex.test.village.VillageManager;
import com.alex.test.village.VillageManagerRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * What the Mayor's Land tab shows (village claims round 3, look only): the owner, the village's square and the
 * villages around it. Sent once with the Mayor's opening data; the map's ground is read on the client.
 * Round 4 adds the people the owner listed, round 9 the village's rights grid (the Rights page, and what the
 * window greys), round 10 its land guards (the Protection page).
 */
public record LandView(boolean claimsOn, @Nullable UUID ownerId, String ownerName,
                       Square square, BlockPos townHall, List<Neighbour> neighbours, List<Person> people,
                       ClaimRights rights, int guardsOff) {

    /** Chunks shown around the village's own square on the map. */
    public static final int VIEW_MARGIN = 3;
    /** At most this many neighbours are sent (villages never overlap, so a handful at most fit the view). */
    private static final int MAX_NEIGHBOURS = 16;

    /** A square of chunks, both ends included. */
    public record Square(int minX, int minZ, int maxX, int maxZ) {
        public int widthChunks() {
            return maxX - minX + 1;
        }

        public int depthChunks() {
            return maxZ - minZ + 1;
        }

        public boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }

        public boolean overlaps(Square other) {
            return other.maxX >= minX && other.minX <= maxX && other.maxZ >= minZ && other.minZ <= maxZ;
        }

        public Square grow(int chunks) {
            return new Square(minX - chunks, minZ - chunks, maxX + chunks, maxZ + chunks);
        }

        @Nullable
        static Square of(@Nullable int[] chunks) {
            return chunks == null ? null : new Square(chunks[0], chunks[1], chunks[2], chunks[3]);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeVarInt(minX);
            buf.writeVarInt(minZ);
            buf.writeVarInt(maxX);
            buf.writeVarInt(maxZ);
        }

        static Square read(FriendlyByteBuf buf) {
            return new Square(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }
    }

    /** Another village whose square shows on the map: its owner's name ("" for none) and whether it is paused. */
    public record Neighbour(Square square, String ownerName, boolean paused) {}

    /** A player the owner listed (round 4), never the owner. */
    public record Person(UUID id, String name, ClaimRank rank) {}

    /** The player's rank here: Owner, their listed rank, or Visitor. */
    public ClaimRank rankOf(UUID playerId) {
        if (playerId.equals(ownerId)) return ClaimRank.OWNER;
        for (Person person : people) {
            if (person.id().equals(playerId)) return person.rank();
        }
        return ClaimRank.VISITOR;
    }

    /**
     * The rank the Mayor's window goes by: as {@link #rankOf} while claims are on and someone owns the village,
     * else Owner (claims off, or nobody's village: every tab as before claims).
     */
    public ClaimRank windowRank(UUID playerId) {
        return claimsOn && ownerId != null ? rankOf(playerId) : ClaimRank.OWNER;
    }

    /** The square shown on the map: the village's own and {@link #VIEW_MARGIN} chunks round it. */
    public Square view() {
        return square.grow(VIEW_MARGIN);
    }

    /** The Land tab's facts for this village; null for one with no Town Hall (or not in the registry's level). */
    @Nullable
    public static LandView of(ServerLevel level, @Nullable VillageManager village) {
        if (village == null || level.getServer() == null) return null;
        Square square = Square.of(village.landChunks());
        BlockPos townHall = village.getBellPos();
        if (square == null || townHall == null) return null;
        VillageManagerRegistry registry = VillageManagerRegistry.get(level.getServer());
        Square view = square.grow(VIEW_MARGIN);
        List<Neighbour> neighbours = new ArrayList<>();
        for (VillageManager other : registry.all()) {
            if (other == village || other.getLevel() == null
                    || !other.getLevel().dimension().equals(level.dimension())) continue;
            Square theirs = Square.of(other.landChunks());
            if (theirs == null || !theirs.overlaps(view)) continue;
            neighbours.add(new Neighbour(theirs, other.claim().ownerName(),
                    registry.isVillageInactive(other.getVillageId())));
            if (neighbours.size() >= MAX_NEIGHBOURS) break;
        }
        VillageClaim claim = village.claim();
        List<Person> people = new ArrayList<>();
        claim.members().forEach((id, member) -> people.add(new Person(id, member.name(), member.rank())));
        return new LandView(registry.claimsEnabled(), claim.ownerId(), claim.ownerName(), square,
                townHall.immutable(), List.copyOf(neighbours), List.copyOf(people), claim.rights(),
                claim.guardsOff());
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeBoolean(claimsOn);
        buf.writeBoolean(ownerId != null);
        if (ownerId != null) buf.writeUUID(ownerId);
        buf.writeUtf(ownerName);
        square.write(buf);
        buf.writeBlockPos(townHall);
        buf.writeVarInt(neighbours.size());
        for (Neighbour neighbour : neighbours) {
            neighbour.square().write(buf);
            buf.writeUtf(neighbour.ownerName());
            buf.writeBoolean(neighbour.paused());
        }
        buf.writeVarInt(people.size());
        for (Person person : people) {
            buf.writeUUID(person.id());
            buf.writeUtf(person.name());
            buf.writeVarInt(person.rank().ordinal());
        }
        rights.write(buf);
        buf.writeVarInt(guardsOff);
    }

    public static LandView read(FriendlyByteBuf buf) {
        boolean claimsOn = buf.readBoolean();
        UUID ownerId = buf.readBoolean() ? buf.readUUID() : null;
        String ownerName = buf.readUtf();
        Square square = Square.read(buf);
        BlockPos townHall = buf.readBlockPos();
        int count = buf.readVarInt();
        List<Neighbour> neighbours = new ArrayList<>(Math.min(count, MAX_NEIGHBOURS));
        for (int i = 0; i < count; i++) {
            neighbours.add(new Neighbour(Square.read(buf), buf.readUtf(), buf.readBoolean()));
        }
        int peopleCount = buf.readVarInt();
        List<Person> people = new ArrayList<>(Math.min(peopleCount, VillageClaim.MAX_MEMBERS));
        for (int i = 0; i < peopleCount; i++) {
            people.add(new Person(buf.readUUID(), buf.readUtf(), ClaimRank.byIndex(buf.readVarInt())));
        }
        return new LandView(claimsOn, ownerId, ownerName, square, townHall, List.copyOf(neighbours), List.copyOf(people),
                ClaimRights.read(buf), buf.readVarInt());
    }

    /** Whether this land guard is on (round 10). */
    public boolean guards(LandGuard.Kind kind) {
        return (guardsOff & 1 << kind.ordinal()) == 0;
    }

    /** Whether this player only looks at the village: claims on, owned, and they are a Visitor or Hostile here. */
    public boolean isVisitor(UUID playerId) {
        ClaimRank rank = windowRank(playerId);
        return rank == ClaimRank.VISITOR || rank == ClaimRank.HOSTILE;
    }
}
