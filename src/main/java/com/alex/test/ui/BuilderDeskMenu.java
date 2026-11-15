package com.alex.test.ui;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.network.BuilderDeskPayloads;
import com.alex.test.village.VillageManager;
import com.alex.test.village.building.Building;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.core.ProfessionSkill;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.alex.test.network.BuilderDeskPayloads;
import java.util.UUID;

/**
 * Server menu plus opening snapshot of the Builder's desk: the Builder's own screen
 * for picking blueprints and, in later rounds, ordering and following builds.
 *
 * <p>The snapshot carries what the client cannot see by itself: which village the
 * Builder belongs to, its border, and every registered building with its shell box
 * (the room with its walls and roof), so the desk can say what the village lacks and,
 * from round 3, where a new building would clash. Everything else (schematic files)
 * lives on the client.
 */
public final class BuilderDeskMenu extends AbstractContainerMenu implements NpcUiMenu {
    /** Sent at most; a village has far fewer, and this keeps the opening packet bounded. */
    private static final int MAX_BUILDINGS = 512;

    /** One registered building: its frame id, name, class, shell box (inclusive) and door. */
    public record BuildingRow(UUID id, String name, String simpleClass, BlockPos shellMin, BlockPos shellMax,
                              BlockPos door) {
        void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(name, 64);
            buffer.writeUtf(simpleClass, 64);
            buffer.writeBlockPos(shellMin);
            buffer.writeBlockPos(shellMax);
            buffer.writeBlockPos(door);
        }

        static BuildingRow read(FriendlyByteBuf buffer) {
            return new BuildingRow(buffer.readUUID(), buffer.readUtf(64), buffer.readUtf(64),
                    buffer.readBlockPos(), buffer.readBlockPos(), buffer.readBlockPos());
        }
    }

    /**
     * Everything the screen opens with. {@code border} is minX, minZ, maxX, maxZ, or null
     * while the village has no map yet; {@code villageId} is null for a Builder without a village.
     */
    public record Snapshot(int villagerId, @Nullable UUID villageId, @Nullable int[] border, int level,
                           List<BuildingRow> buildings, Set<String> buildingTypes,
                           List<List<GenericSettingsMenu.RowLine>> settings) {

        public void write(FriendlyByteBuf buffer) {
            buffer.writeVarInt(villagerId);
            buffer.writeBoolean(villageId != null);
            if (villageId != null) buffer.writeUUID(villageId);
            buffer.writeBoolean(border != null);
            if (border != null) {
                for (int value : border) buffer.writeInt(value);
            }
            buffer.writeVarInt(level);
            buffer.writeVarInt(buildings.size());
            for (BuildingRow row : buildings) row.write(buffer);
            buffer.writeVarInt(buildingTypes.size());
            for (String type : buildingTypes) buffer.writeUtf(type, 64);
            // The Builder's work settings, shown in the desk's Settings tab.
            GenericSettingsMenu.writeLinesTo(buffer, settings);
            // Round 4 appends the project queue here.
        }

        static Snapshot read(FriendlyByteBuf buffer) {
            int villagerId = buffer.readVarInt();
            UUID villageId = buffer.readBoolean() ? buffer.readUUID() : null;
            int[] border = null;
            if (buffer.readBoolean()) {
                border = new int[] {buffer.readInt(), buffer.readInt(), buffer.readInt(), buffer.readInt()};
            }
            int level = buffer.readVarInt();
            int count = Math.min(MAX_BUILDINGS, Math.max(0, buffer.readVarInt()));
            List<BuildingRow> buildings = new ArrayList<>(count);
            for (int i = 0; i < count; i++) buildings.add(BuildingRow.read(buffer));
            int types = Math.min(MAX_BUILDINGS, Math.max(0, buffer.readVarInt()));
            Set<String> buildingTypes = new LinkedHashSet<>();
            for (int i = 0; i < types; i++) buildingTypes.add(buffer.readUtf(64));
            // Kept changeable: the Settings tab updates them in place as the player clicks.
            List<List<GenericSettingsMenu.RowLine>> settings = GenericSettingsMenu.readLinesFor(Profession.BUILDER, buffer);
            return new Snapshot(villagerId, villageId, border, level, List.copyOf(buildings),
                    Set.copyOf(buildingTypes), settings);
        }
    }

    /**
     * Client side: the pieces of the last copy the server sent ({@link BuilderDeskPayloads.CopyResult}
     * then its chunks), put back together for the screen to take.
     */
    public static final class CopyInbox {
        /** A copy with all its bytes (gzipped structure NBT), or with only an error. */
        public record Finished(BuilderDeskPayloads.CopyResult header, byte[] bytes) {}

        @Nullable private BuilderDeskPayloads.CopyResult header;
        @Nullable private byte[][] parts;
        private int received;

        public void header(BuilderDeskPayloads.CopyResult result) {
            header = result;
            boolean fits = result.totalBytes() >= 0 && result.totalBytes() <= BlueprintLimits.MAX_FILE_BYTES;
            parts = new byte[fits ? result.chunks() : 0][];
            received = 0;
        }

        public void chunk(BuilderDeskPayloads.CopyChunk chunk) {
            if (header == null || parts == null || chunk.requestId() != header.requestId()) return;
            int i = chunk.index();
            if (i < 0 || i >= parts.length || parts[i] != null) return;
            parts[i] = chunk.data();
            received++;
        }

        /** The finished copy, once every piece is in; handed out once. */
        @Nullable
        public Finished take() {
            if (header == null || parts == null || received < parts.length) return null;
            int total = 0;
            for (byte[] part : parts) total += part.length;
            byte[] bytes = new byte[total];
            int at = 0;
            for (byte[] part : parts) {
                System.arraycopy(part, 0, bytes, at, part.length);
                at += part.length;
            }
            Finished done = new Finished(header, bytes);
            header = null;
            parts = null;
            return done;
        }
    }

    /**
     * Client side: the heights where villagers walk under a roof or rock
     * ({@link BuilderDeskPayloads.MapFloors}, sent right after the desk opens). The village
     * map's Layers buttons stop at these floors: house floors, cave rooms, mine levels.
     */
    public static final class UndergroundFloors {
        /** Covered walk spots a height needs to count as a floor. */
        private static final int MIN_SPOTS = 10;
        /** Floors closer than this are one floor (stairs, uneven caves). */
        private static final int MERGE = 3;
        private List<Integer> floors = List.of();
        private boolean arrived;

        public void set(BuilderDeskPayloads.MapFloors summary) {
            // Busy heights, grouped when close; each group's busiest height is its floor.
            List<Integer> found = new ArrayList<>();
            int[] counts = summary.counts();
            int groupBest = -1, groupEnd = Integer.MIN_VALUE;
            for (int i = 0; i < counts.length; i++) {
                if (counts[i] < MIN_SPOTS) continue;
                if (groupBest >= 0 && i - groupEnd > MERGE) {
                    found.add(summary.minY() + groupBest);
                    groupBest = -1;
                }
                if (groupBest < 0 || counts[i] > counts[groupBest]) groupBest = i;
                groupEnd = i;
            }
            if (groupBest >= 0) found.add(summary.minY() + groupBest);
            floors = List.copyOf(found);
            arrived = true;
        }

        public boolean arrived() {
            return arrived;
        }

        /** Feet heights of the floors, lowest first. */
        public List<Integer> floors() {
            return floors;
        }
    }

    private final Snapshot snapshot;
    @Nullable private final NpcEntity villager;
    private final CopyInbox copyInbox = new CopyInbox();
    private final UndergroundFloors floors = new UndergroundFloors();
    /** Client side: the Projects tab's rows; null until the server sends them. */
    @Nullable private List<com.alex.test.network.BuildOrderPayloads.ProjectRow> projects;
    /** Client side: what this player gave the Builder, for the Projects tab. */
    private List<com.alex.test.network.BuildOrderPayloads.GivenRow> given = List.of();
    /** Client side: the player keeping this Builder's Free Building on, "" when it is off. */
    private String freeBy = "";
    /** Server side: game time of the last Blocks list answer, so a client cannot ask every tick. */
    private long blocksAnsweredAt = -100;

    /** Server side: opened against the live Builder, with the snapshot already sent. */
    public BuilderDeskMenu(int id, Inventory inventory, NpcEntity villager, Snapshot snapshot) {
        super(DynamicVillages.BUILDER_DESK_MENU.get(), id);
        this.villager = villager;
        this.snapshot = snapshot;
    }

    /** Client side: rebuilt from the opening buffer. */
    public BuilderDeskMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        super(DynamicVillages.BUILDER_DESK_MENU.get(), id);
        this.villager = null;
        this.snapshot = Snapshot.read(buffer);
    }

    /** Reads the village as it is now. Server only. */
    public static Snapshot snapshot(NpcEntity villager) {
        int level = 1;
        for (ProfessionSkill skill : villager.getProfessionSkills()) {
            if (skill.profession() == Profession.BUILDER) level = skill.level();
        }
        VillageManager village = TradesmanSalePolicy.villageOf(villager);
        if (village == null) {
            return new Snapshot(villager.getId(), null, null, level, List.of(), Set.of(),
                    GenericSettingsMenu.currentLines(villager));
        }
        AABB box = village.borderBounds();
        int[] border = box == null ? null : new int[] {
                (int) Math.floor(box.minX), (int) Math.floor(box.minZ),
                (int) Math.floor(box.maxX), (int) Math.floor(box.maxZ)};
        List<BuildingRow> rows = new ArrayList<>();
        Set<String> types = new LinkedHashSet<>();
        for (Building building : village.getBuildings()) {
            if (rows.size() >= MAX_BUILDINGS) break;
            String simple = building.getClass().getSimpleName();
            types.add(simple);
            rows.add(new BuildingRow(building.getItemFrameId(), building.getDisplayName(), simple,
                    building.shellMin(), building.shellMax(), building.getDoorPos()));
        }
        return new Snapshot(villager.getId(), village.getVillageId(), border, level, List.copyOf(rows),
                Set.copyOf(types), GenericSettingsMenu.currentLines(villager));
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public int villagerId() {
        return snapshot.villagerId();
    }

    /** The Builder this desk belongs to; server side only (null on the client). */
    @Nullable
    public NpcEntity villager() {
        return villager;
    }

    /** Client side: where a copy's pieces arrive. */
    public UndergroundFloors floors() {
        return floors;
    }

    /** Client side: this village's ordered builds, or null while they are on their way. */
    @Nullable
    public List<com.alex.test.network.BuildOrderPayloads.ProjectRow> projects() {
        return projects;
    }

    public void setProjects(List<com.alex.test.network.BuildOrderPayloads.ProjectRow> rows,
                            List<com.alex.test.network.BuildOrderPayloads.GivenRow> givenRows) {
        projects = List.copyOf(rows);
        given = List.copyOf(givenRows);
    }

    public List<com.alex.test.network.BuildOrderPayloads.GivenRow> given() {
        return given;
    }

    public String freeBy() {
        return freeBy;
    }

    /** From the server's Projects message, or the Settings tab's own click until that message comes. */
    public void setFreeBy(String name) {
        freeBy = name;
    }

    /**
     * Server side: at most one Blocks list answer every 8 ticks. The desk asks every 10 while
     * the file is still being read; a little under that, so a late packet is never turned away.
     */
    public boolean mayAnswerBlocks(long now) {
        if (now >= blocksAnsweredAt && now - blocksAnsweredAt < 8) return false;
        blocksAnsweredAt = now;
        return true;
    }

    public CopyInbox copyInbox() {
        return copyInbox;
    }

    @Override
    public boolean stillValid(Player player) {
        return villager == null || NpcUiMenu.villagerStillThere(player, villager);
    }

    @Override
    public boolean isForNpc(NpcEntity npc) {
        return villager == npc;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
