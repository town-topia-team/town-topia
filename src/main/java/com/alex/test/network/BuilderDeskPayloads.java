package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.ui.NpcUiMenu;
import com.alex.test.ui.VillagerMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.builder.copy.BuildingCopier;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import com.alex.test.village.villagers.builder.project.BuildProjects;
import com.alex.test.village.villagers.builder.project.FreeBuild;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import com.alex.test.village.NavGraph;
import com.alex.test.village.NavNode;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Messages of the Builder's desk: {@link Open} from the villager screen, and copying a
 * building ({@link CopyScan} up, {@link CopyResult} plus {@link CopyChunk}s down).
 * Ordering and the project queue (round 4) join here. Every message from a player
 * re-checks the open desk and {@code NpcEntity.canPlayerAccessSettings}, like the
 * settings screens do.
 */
public final class BuilderDeskPayloads {
    private BuilderDeskPayloads() {}

    /** Bytes per {@link CopyChunk}; a copy is at most {@link BlueprintLimits#MAX_FILE_BYTES}, so 8 chunks. */
    public static final int COPY_CHUNK_BYTES = 256 * 1024;
    /** Ticks between two copies by one player: a copy reads up to a few hundred thousand blocks. */
    private static final int COPY_COOLDOWN_TICKS = 3;
    private static final Map<UUID, Long> LAST_COPY = new HashMap<>();

    /** Open the desk of this Builder, from its villager screen. */
    public record Open(int villagerId) implements CustomPacketPayload {
        public static final Type<Open> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "open_builder_desk"));
        public static final StreamCodec<FriendlyByteBuf, Open> CODEC = new StreamCodec<>() {
            @Override public Open decode(FriendlyByteBuf buffer) {
                return new Open(buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, Open payload) {
                buffer.writeVarInt(payload.villagerId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The Settings tab's Free Building switch; taken only from a player in Creative ({@code FreeBuild}). */
    public record FreeBuilding(int villagerId, boolean on) implements CustomPacketPayload {
        public static final Type<FreeBuilding> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_free_building"));
        public static final StreamCodec<FriendlyByteBuf, FreeBuilding> CODEC = new StreamCodec<>() {
            @Override public FreeBuilding decode(FriendlyByteBuf buffer) {
                return new FreeBuilding(buffer.readVarInt(), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, FreeBuilding payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeBoolean(payload.on());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Copy a building: by its frame id, or the building under a clicked map column
     * ({@code pick}: x, the top the player saw, z). {@code grow} moves each side of the box
     * out (+) or in (-), in {@link BuildingCopier#SIDES} order. The answer echoes {@code requestId}.
     */
    public record CopyScan(int villagerId, int requestId, @Nullable UUID buildingId, @Nullable BlockPos pick,
                           int[] grow) implements CustomPacketPayload {
        public static final Type<CopyScan> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_copy_scan"));
        public static final StreamCodec<FriendlyByteBuf, CopyScan> CODEC = new StreamCodec<>() {
            @Override public CopyScan decode(FriendlyByteBuf buffer) {
                int villagerId = buffer.readVarInt();
                int requestId = buffer.readVarInt();
                UUID building = buffer.readBoolean() ? buffer.readUUID() : null;
                BlockPos pick = buffer.readBoolean() ? buffer.readBlockPos() : null;
                int[] grow = new int[BuildingCopier.SIDES];
                for (int i = 0; i < grow.length; i++) grow[i] = buffer.readVarInt();
                return new CopyScan(villagerId, requestId, building, pick, grow);
            }
            @Override public void encode(FriendlyByteBuf buffer, CopyScan payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeVarInt(payload.requestId());
                buffer.writeBoolean(payload.buildingId() != null);
                if (payload.buildingId() != null) buffer.writeUUID(payload.buildingId());
                buffer.writeBoolean(payload.pick() != null);
                if (payload.pick() != null) buffer.writeBlockPos(payload.pick());
                for (int i = 0; i < BuildingCopier.SIDES; i++) {
                    buffer.writeVarInt(i < payload.grow().length ? payload.grow()[i] : 0);
                }
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * What a copy found, sent before its {@code chunks} {@link CopyChunk}s. With an
     * {@code error} (player-readable) there are no chunks and the rest is empty.
     * {@code noFloor}: the building has no room right now, so it was copied without its floor.
     * {@code cut}: one short line on what kept part of the building out, "" when nothing did.
     */
    public record CopyResult(int requestId, @Nullable String error, BlockPos origin, int sizeX, int sizeY, int sizeZ,
                             int blocks, String cut, boolean noFloor, String label, int[] grow, int chunks,
                             int totalBytes)
            implements CustomPacketPayload {
        public static final Type<CopyResult> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_copy_result"));
        public static final StreamCodec<FriendlyByteBuf, CopyResult> CODEC = new StreamCodec<>() {
            @Override public CopyResult decode(FriendlyByteBuf buffer) {
                int requestId = buffer.readVarInt();
                String error = buffer.readBoolean() ? buffer.readUtf(256) : null;
                BlockPos origin = buffer.readBlockPos();
                int sx = buffer.readVarInt(), sy = buffer.readVarInt(), sz = buffer.readVarInt();
                int blocks = buffer.readVarInt();
                String cut = buffer.readUtf(256);
                boolean noFloor = buffer.readBoolean();
                String label = buffer.readUtf(64);
                int[] grow = new int[BuildingCopier.SIDES];
                for (int i = 0; i < grow.length; i++) grow[i] = buffer.readVarInt();
                int chunks = Math.min(64, Math.max(0, buffer.readVarInt()));
                int total = buffer.readVarInt();
                return new CopyResult(requestId, error, origin, sx, sy, sz, blocks, cut, noFloor, label, grow, chunks,
                        total);
            }
            @Override public void encode(FriendlyByteBuf buffer, CopyResult payload) {
                buffer.writeVarInt(payload.requestId());
                buffer.writeBoolean(payload.error() != null);
                if (payload.error() != null) buffer.writeUtf(payload.error(), 256);
                buffer.writeBlockPos(payload.origin());
                buffer.writeVarInt(payload.sizeX());
                buffer.writeVarInt(payload.sizeY());
                buffer.writeVarInt(payload.sizeZ());
                buffer.writeVarInt(payload.blocks());
                buffer.writeUtf(payload.cut(), 256);
                buffer.writeBoolean(payload.noFloor());
                buffer.writeUtf(payload.label(), 64);
                for (int i = 0; i < BuildingCopier.SIDES; i++) buffer.writeVarInt(payload.grow()[i]);
                buffer.writeVarInt(payload.chunks());
                buffer.writeVarInt(payload.totalBytes());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One piece of a copy's file (gzipped structure NBT), in order. */
    public record CopyChunk(int requestId, int index, byte[] data) implements CustomPacketPayload {
        public static final Type<CopyChunk> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_copy_chunk"));
        public static final StreamCodec<FriendlyByteBuf, CopyChunk> CODEC = new StreamCodec<>() {
            @Override public CopyChunk decode(FriendlyByteBuf buffer) {
                return new CopyChunk(buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(COPY_CHUNK_BYTES));
            }
            @Override public void encode(FriendlyByteBuf buffer, CopyChunk payload) {
                buffer.writeVarInt(payload.requestId());
                buffer.writeVarInt(payload.index());
                buffer.writeByteArray(payload.data());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Where villagers walk under a roof or rock, as a height summary for the map's Layers:
     * {@code counts[i]} is how many walk spots at feet height {@code minY + i} have something
     * above them (house floors, cave rooms, mine levels). A few hundred numbers, sent once
     * right after the desk opens.
     */
    public record MapFloors(int containerId, int minY, int[] counts) implements CustomPacketPayload {
        /** Heights sent at most (a world is 384 tall). */
        static final int MAX_HEIGHTS = 4096;
        public static final Type<MapFloors> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_map_floors"));
        public static final StreamCodec<FriendlyByteBuf, MapFloors> CODEC = new StreamCodec<>() {
            @Override public MapFloors decode(FriendlyByteBuf buffer) {
                int containerId = buffer.readVarInt();
                int minY = buffer.readInt();
                int length = Math.min(MAX_HEIGHTS, Math.max(0, buffer.readVarInt()));
                int[] counts = new int[length];
                for (int i = 0; i < length; i++) counts[i] = buffer.readVarInt();
                return new MapFloors(containerId, minY, counts);
            }
            @Override public void encode(FriendlyByteBuf buffer, MapFloors payload) {
                int length = Math.min(MAX_HEIGHTS, payload.counts().length);
                buffer.writeVarInt(payload.containerId());
                buffer.writeInt(payload.minY());
                buffer.writeVarInt(length);
                for (int i = 0; i < length; i++) buffer.writeVarInt(payload.counts()[i]);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server: counts the village's walk spots that have something above them, per feet height. */
    private static MapFloors coveredWalkHeights(int containerId, ServerPlayer player, NpcEntity villager) {
        VillageManager village = TradesmanSalePolicy.villageOf(villager);
        NavGraph graph = village == null ? null : village.graph();
        ServerLevel level = player.serverLevel();
        int minY = level.getMinBuildHeight();
        int[] counts = new int[Math.min(MapFloors.MAX_HEIGHTS, level.getHeight())];
        if (graph != null) {
            for (NavNode node : graph.allNodes()) {
                int i = node.feetY - minY;
                if (i < 0 || i >= counts.length || !level.hasChunk(node.x >> 4, node.z >> 4)) continue;
                // Under a roof, a floor above or rock: open ground has nothing above its feet.
                if (node.feetY < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, node.x, node.z)) {
                    counts[i]++;
                }
            }
        }
        return new MapFloors(containerId, minY, counts);
    }

    /**
     * Opens this Builder's desk for the player, with its opening data and the floors summary.
     * The caller has checked that the player may (from the villager screen, or a Builder's plan).
     */
    public static void openDesk(ServerPlayer player, NpcEntity villager) {
        BuilderDeskMenu.Snapshot snapshot = BuilderDeskMenu.snapshot(villager);
        villager.markUiOpened(player);
        player.openMenu(new SimpleMenuProvider(
                        (id, inventory, ignored) -> new BuilderDeskMenu(id, inventory, villager, snapshot),
                        Component.literal("Builder's desk · " + villager.getDisplayName().getString())),
                buffer -> snapshot.write(buffer));
        if (player.containerMenu instanceof BuilderDeskMenu desk) {
            PacketDistributor.sendToPlayer(player, coveredWalkHeights(desk.containerId, player, villager));
            com.alex.test.village.villagers.builder.project.BuildOrders.sendProjects(player, desk);
        }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(Open.TYPE, Open.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // The same checks as opening the settings screen: the sender has this very
            // villager's screen open and may change its settings.
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof VillagerMenu menu)
                    || menu.getVillagerId() != payload.villagerId()
                    || !menu.canAccessSettings()
                    || !(player.level().getEntity(payload.villagerId()) instanceof NpcEntity villager)
                    || !villager.isAlive()
                    || !NpcUiMenu.villagerStillThere(player, villager)
                    || villager.getProfession() != Profession.BUILDER
                    || !villager.canPlayerAccessSettings(player)) {
                return;
            }
            openDesk(player, villager);
        }));
        registrar.playToServer(FreeBuilding.TYPE, FreeBuilding.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)
                    || !(player.containerMenu instanceof BuilderDeskMenu desk)
                    || desk.villagerId() != payload.villagerId()) {
                return;
            }
            if (!player.isCreative()
                    || !(player.level().getEntity(payload.villagerId()) instanceof NpcEntity villager)
                    || !villager.isAlive()
                    || !NpcUiMenu.villagerStillThere(player, villager)
                    || villager.getProfession() != Profession.BUILDER
                    || !villager.canPlayerAccessSettings(player)) {
                // The tab flipped its switch already: put the truth back.
                BuildOrders.sendProjects(player, desk);
                return;
            }
            FreeBuild.set(villager, payload.on() ? player : null);
            DynamicVillages.LOGGER.info("[Builder] {}: free building {} by {}", villager.getDisplayName().getString(),
                    payload.on() ? "on" : "off", player.getGameProfile().getName());
            UUID villageId = desk.snapshot().villageId();
            if (payload.on() && villageId != null) {
                // Orders waiting for blocks look again now, not after their 30 s wait.
                for (BuildProjects.Project project : BuildProjects.get(player.server).forVillage(villageId)) {
                    if (project.waitUntil != Long.MAX_VALUE) project.waitUntil = 0;
                    project.builderWaits.clear();
                }
            }
            if (villageId != null) BuildOrders.changed(player.server, villageId);
            else BuildOrders.sendProjects(player, desk);
        }));
        registrar.playToClient(MapFloors.TYPE, MapFloors.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player().containerMenu instanceof BuilderDeskMenu desk && desk.containerId == payload.containerId()) {
                desk.floors().set(payload);
            }
        }));
        registrar.playToServer(CopyScan.TYPE, CopyScan.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) copy(player, payload);
        }));
        registrar.playToClient(CopyResult.TYPE, CopyResult.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // The desk's copy, or one made with a Builder's plan in the world.
            if (ctx.player().containerMenu instanceof BuilderDeskMenu desk) {
                desk.copyInbox().header(payload);
            } else {
                com.alex.test.ui.screen.BuilderPlanClient.copyInbox().header(payload);
            }
        }));
        registrar.playToClient(CopyChunk.TYPE, CopyChunk.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player().containerMenu instanceof BuilderDeskMenu desk) {
                desk.copyInbox().chunk(payload);
            } else {
                com.alex.test.ui.screen.BuilderPlanClient.copyInbox().chunk(payload);
            }
        }));
    }

    /** Server: checks the sender, copies, and sends the result back in pieces. */
    private static void copy(ServerPlayer player, CopyScan payload) {
        // The sender has this Builder's desk open and may still change its settings.
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != payload.villagerId()) return;
        NpcEntity villager = desk.villager();
        if (villager == null || !villager.isAlive() || villager.getProfession() != Profession.BUILDER
                || !NpcUiMenu.villagerStillThere(player, villager) || !villager.canPlayerAccessSettings(player)) {
            return;
        }
        copyAndSend(player, villager, payload.requestId(), payload.buildingId(), payload.pick(), payload.grow());
    }

    /**
     * Server: copies for a player already checked (an open desk, or a Builder's plan for copying)
     * and sends the result back in pieces; the cooldown applies to both.
     */
    public static void copyAndSend(ServerPlayer player, NpcEntity villager, int requestId, @Nullable UUID buildingId,
                                   @Nullable BlockPos pick, int[] growIn) {
        copyAndSend(player, villager, requestId, buildingId, pick, growIn, null);
    }

    /** With {@code corner}: a box copy from {@code pick} to {@code corner} (the Builder's plan). */
    public static void copyAndSend(ServerPlayer player, NpcEntity villager, int requestId, @Nullable UUID buildingId,
                                   @Nullable BlockPos pick, int[] growIn, @Nullable BlockPos corner) {
        long now = player.serverLevel().getGameTime();
        Long last = LAST_COPY.get(player.getUUID());
        if (last != null && now >= last && now - last < COPY_COOLDOWN_TICKS) {
            sendFailure(player, requestId, "Copying too fast. Try again.");
            return;
        }
        LAST_COPY.put(player.getUUID(), now);
        VillageManager village = TradesmanSalePolicy.villageOf(villager);
        if (village == null || village.getLevel() != player.serverLevel()) {
            sendFailure(player, requestId, "This Builder has no village to copy from.");
            return;
        }
        int[] grow = Arrays.copyOf(growIn, BuildingCopier.SIDES);
        BuildingCopier.Result result = corner != null && pick != null
                ? BuildingCopier.copyBox(player.serverLevel(), village, pick, corner, grow)
                : BuildingCopier.copy(player.serverLevel(), village, buildingId, pick, grow);
        if (result.error() != null) {
            sendFailure(player, requestId, result.error());
            return;
        }
        byte[] bytes = result.bytes();
        int chunks = (bytes.length + COPY_CHUNK_BYTES - 1) / COPY_CHUNK_BYTES;
        PacketDistributor.sendToPlayer(player, new CopyResult(requestId, null, result.origin(),
                result.sizeX(), result.sizeY(), result.sizeZ(), result.blocks(), result.cut(), result.noFloor(),
                result.label(), result.grow(), chunks, bytes.length));
        for (int i = 0; i < chunks; i++) {
            int from = i * COPY_CHUNK_BYTES;
            PacketDistributor.sendToPlayer(player, new CopyChunk(requestId, i,
                    Arrays.copyOfRange(bytes, from, Math.min(bytes.length, from + COPY_CHUNK_BYTES))));
        }
    }

    private static void sendFailure(ServerPlayer player, int requestId, String error) {
        PacketDistributor.sendToPlayer(player, new CopyResult(requestId, error, BlockPos.ZERO, 0, 0, 0, 0, "", false, "",
                new int[BuildingCopier.SIDES], 0, 0));
    }
}
