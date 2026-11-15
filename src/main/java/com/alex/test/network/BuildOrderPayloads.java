package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.village.villagers.builder.blueprint.BlueprintLimits;
import com.alex.test.village.villagers.builder.project.BlueprintUploads;
import com.alex.test.village.villagers.builder.project.BuildOrders;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ordering a build (round 4 of the blueprint plan, lean): the client sends the blueprint in
 * pieces ({@link UploadBegin}, {@link UploadChunk}), then {@link Order} with the spot; the
 * server answers {@link OrderResult}. {@link ProjectsSync} fills the desk's Projects tab,
 * {@link ProjectCancel} removes one, {@link Sites} gives every player the outlines.
 */
public final class BuildOrderPayloads {
    private BuildOrderPayloads() {}

    /** Most rows one message carries (8 per village, many villages). */
    private static final int MAX_ROWS = 256;

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, path);
    }

    public record UploadBegin(int uploadId, int size) implements CustomPacketPayload {
        public static final Type<UploadBegin> TYPE = new Type<>(id("build_upload_begin"));
        public static final StreamCodec<FriendlyByteBuf, UploadBegin> CODEC = new StreamCodec<>() {
            @Override public UploadBegin decode(FriendlyByteBuf buffer) {
                return new UploadBegin(buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, UploadBegin payload) {
                buffer.writeVarInt(payload.uploadId());
                buffer.writeVarInt(payload.size());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record UploadChunk(int uploadId, byte[] data) implements CustomPacketPayload {
        public static final Type<UploadChunk> TYPE = new Type<>(id("build_upload_chunk"));
        public static final StreamCodec<FriendlyByteBuf, UploadChunk> CODEC = new StreamCodec<>() {
            @Override public UploadChunk decode(FriendlyByteBuf buffer) {
                return new UploadChunk(buffer.readVarInt(), buffer.readByteArray(BlueprintUploads.CHUNK_BYTES));
            }
            @Override public void encode(FriendlyByteBuf buffer, UploadChunk payload) {
                buffer.writeVarInt(payload.uploadId());
                buffer.writeByteArray(payload.data());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Build the uploaded blueprint, turned and mirrored like this, with its lowest corner at {@code origin}. */
    public record Order(int uploadId, int villagerId, String name, BlockPos origin, Rotation rotation, Mirror mirror,
                        boolean give)
            implements CustomPacketPayload {
        public static final Type<Order> TYPE = new Type<>(id("build_order"));
        public static final StreamCodec<FriendlyByteBuf, Order> CODEC = new StreamCodec<>() {
            @Override public Order decode(FriendlyByteBuf buffer) {
                return new Order(buffer.readVarInt(), buffer.readVarInt(), buffer.readUtf(64), buffer.readBlockPos(),
                        buffer.readEnum(Rotation.class), buffer.readEnum(Mirror.class), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, Order payload) {
                buffer.writeVarInt(payload.uploadId());
                buffer.writeVarInt(payload.villagerId());
                String name = payload.name();
                buffer.writeUtf(name.length() > 64 ? name.substring(0, 64) : name, 64);
                buffer.writeBlockPos(payload.origin());
                buffer.writeEnum(payload.rotation());
                buffer.writeEnum(payload.mirror());
                buffer.writeBoolean(payload.give());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The answer to a desk button (Give, take back): shown on the open desk, else over the hotbar. */
    public record Notice(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = new Type<>(id("build_notice"));
        public static final StreamCodec<FriendlyByteBuf, Notice> CODEC = new StreamCodec<>() {
            @Override public Notice decode(FriendlyByteBuf buffer) {
                return new Notice(buffer.readBoolean(), buffer.readUtf(512));
            }
            @Override public void encode(FriendlyByteBuf buffer, Notice payload) {
                buffer.writeBoolean(payload.ok());
                String message = payload.message();
                buffer.writeUtf(message.length() > 512 ? message.substring(0, 512) : message, 512);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record OrderResult(boolean ok, String message) implements CustomPacketPayload {
        public static final Type<OrderResult> TYPE = new Type<>(id("build_order_result"));
        public static final StreamCodec<FriendlyByteBuf, OrderResult> CODEC = new StreamCodec<>() {
            @Override public OrderResult decode(FriendlyByteBuf buffer) {
                return new OrderResult(buffer.readBoolean(), buffer.readUtf(512));
            }
            @Override public void encode(FriendlyByteBuf buffer, OrderResult payload) {
                buffer.writeBoolean(payload.ok());
                String message = payload.message();
                buffer.writeUtf(message.length() > 512 ? message.substring(0, 512) : message, 512);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One order as the desk's Projects tab lists it. */
    public record ProjectRow(UUID id, String name, BlockPos origin, int sizeX, int sizeY, int sizeZ, int blocks,
                             String status, String orderedBy, int progress, boolean paused) {
        static ProjectRow read(FriendlyByteBuf buffer) {
            return new ProjectRow(buffer.readUUID(), buffer.readUtf(64), buffer.readBlockPos(), buffer.readVarInt(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(),
                    buffer.readUtf(128), buffer.readUtf(64), buffer.readVarInt(), buffer.readBoolean());
        }

        void write(FriendlyByteBuf buffer) {
            buffer.writeUUID(id);
            buffer.writeUtf(cut(name, 64), 64);
            buffer.writeBlockPos(origin);
            buffer.writeVarInt(sizeX);
            buffer.writeVarInt(sizeY);
            buffer.writeVarInt(sizeZ);
            buffer.writeVarInt(blocks);
            buffer.writeUtf(cut(status, 128), 128);
            buffer.writeUtf(cut(orderedBy, 64), 64);
            buffer.writeVarInt(Math.max(0, Math.min(100, progress)));
            buffer.writeBoolean(paused);
        }
    }

    /** One item the viewing player gave this village's Builder, and how many are left. */
    public record GivenRow(ResourceLocation item, int count) {}

    /** Most given items one message carries. */
    private static final int MAX_GIVEN = 64;

    /**
     * To the client: the Projects tab of the open desk ({@code containerId}): the orders, what
     * the viewing player gave the Builder, and who keeps its Free Building on ("" when off).
     */
    public record ProjectsSync(int containerId, List<ProjectRow> rows, List<GivenRow> given, String freeBy)
            implements CustomPacketPayload {
        public static final Type<ProjectsSync> TYPE = new Type<>(id("build_projects_sync"));
        public static final StreamCodec<FriendlyByteBuf, ProjectsSync> CODEC = new StreamCodec<>() {
            @Override public ProjectsSync decode(FriendlyByteBuf buffer) {
                int containerId = buffer.readVarInt();
                int count = Math.min(buffer.readVarInt(), MAX_ROWS);
                List<ProjectRow> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) rows.add(ProjectRow.read(buffer));
                int givenCount = Math.min(buffer.readVarInt(), MAX_GIVEN);
                List<GivenRow> given = new ArrayList<>(givenCount);
                for (int i = 0; i < givenCount; i++) given.add(new GivenRow(buffer.readResourceLocation(), buffer.readVarInt()));
                return new ProjectsSync(containerId, rows, given, buffer.readUtf(64));
            }
            @Override public void encode(FriendlyByteBuf buffer, ProjectsSync payload) {
                buffer.writeVarInt(payload.containerId());
                int count = Math.min(payload.rows().size(), MAX_ROWS);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) payload.rows().get(i).write(buffer);
                int givenCount = Math.min(payload.given().size(), MAX_GIVEN);
                buffer.writeVarInt(givenCount);
                for (int i = 0; i < givenCount; i++) {
                    buffer.writeResourceLocation(payload.given().get(i).item());
                    buffer.writeVarInt(payload.given().get(i).count());
                }
                buffer.writeUtf(cut(payload.freeBy(), 64), 64);
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** From the Projects tab: the player's own given blocks of one item back into their inventory. */
    public record GivenTakeBack(int villagerId, ResourceLocation item) implements CustomPacketPayload {
        public static final Type<GivenTakeBack> TYPE = new Type<>(id("build_given_take_back"));
        public static final StreamCodec<FriendlyByteBuf, GivenTakeBack> CODEC = new StreamCodec<>() {
            @Override public GivenTakeBack decode(FriendlyByteBuf buffer) {
                return new GivenTakeBack(buffer.readVarInt(), buffer.readResourceLocation());
            }
            @Override public void encode(FriendlyByteBuf buffer, GivenTakeBack payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeResourceLocation(payload.item());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * From the Projects tab's give bar: up to {@code amount} of one block for one order, out of the
     * player's inventory. Only a wish: the server caps it like Give.
     */
    public record GiveItem(int villagerId, UUID projectId, ResourceLocation item, int amount)
            implements CustomPacketPayload {
        public static final Type<GiveItem> TYPE = new Type<>(id("build_give_item"));
        public static final StreamCodec<FriendlyByteBuf, GiveItem> CODEC = new StreamCodec<>() {
            @Override public GiveItem decode(FriendlyByteBuf buffer) {
                return new GiveItem(buffer.readVarInt(), buffer.readUUID(), buffer.readResourceLocation(),
                        buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, GiveItem payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeUUID(payload.projectId());
                buffer.writeResourceLocation(payload.item());
                buffer.writeVarInt(payload.amount());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public record ProjectCancel(int villagerId, UUID projectId) implements CustomPacketPayload {
        public static final Type<ProjectCancel> TYPE = new Type<>(id("build_project_cancel"));
        public static final StreamCodec<FriendlyByteBuf, ProjectCancel> CODEC = new StreamCodec<>() {
            @Override public ProjectCancel decode(FriendlyByteBuf buffer) {
                return new ProjectCancel(buffer.readVarInt(), buffer.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buffer, ProjectCancel payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeUUID(payload.projectId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static final int PAUSE = 0, UP = 1, DOWN = 2, GIVE = 3;

    /** How many of these items the village storage has for the Builder. */
    public record MaterialsRequest(int requestId, int villagerId, List<ResourceLocation> items) implements CustomPacketPayload {
        public static final Type<MaterialsRequest> TYPE = new Type<>(id("build_materials_request"));
        public static final StreamCodec<FriendlyByteBuf, MaterialsRequest> CODEC = new StreamCodec<>() {
            @Override public MaterialsRequest decode(FriendlyByteBuf buffer) {
                int requestId = buffer.readVarInt(), villagerId = buffer.readVarInt();
                int count = Math.min(buffer.readVarInt(), MAX_ROWS);
                List<ResourceLocation> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) items.add(buffer.readResourceLocation());
                return new MaterialsRequest(requestId, villagerId, items);
            }
            @Override public void encode(FriendlyByteBuf buffer, MaterialsRequest payload) {
                buffer.writeVarInt(payload.requestId());
                buffer.writeVarInt(payload.villagerId());
                int count = Math.min(payload.items().size(), MAX_ROWS);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) buffer.writeResourceLocation(payload.items().get(i));
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** The answer: one count per asked item, same order; empty when the Builder can't be asked. */
    public record MaterialsReply(int requestId, int[] counts) implements CustomPacketPayload {
        public static final Type<MaterialsReply> TYPE = new Type<>(id("build_materials_reply"));
        public static final StreamCodec<FriendlyByteBuf, MaterialsReply> CODEC = new StreamCodec<>() {
            @Override public MaterialsReply decode(FriendlyByteBuf buffer) {
                return new MaterialsReply(buffer.readVarInt(), buffer.readVarIntArray(MAX_ROWS));
            }
            @Override public void encode(FriendlyByteBuf buffer, MaterialsReply payload) {
                buffer.writeVarInt(payload.requestId());
                buffer.writeVarIntArray(payload.counts());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** From the Projects tab's Blocks list: what this order still needs. Asked again every 2 s while it shows. */
    public record OrderBlocksRequest(int villagerId, UUID projectId) implements CustomPacketPayload {
        public static final Type<OrderBlocksRequest> TYPE = new Type<>(id("build_order_blocks_request"));
        public static final StreamCodec<FriendlyByteBuf, OrderBlocksRequest> CODEC = new StreamCodec<>() {
            @Override public OrderBlocksRequest decode(FriendlyByteBuf buffer) {
                return new OrderBlocksRequest(buffer.readVarInt(), buffer.readUUID());
            }
            @Override public void encode(FriendlyByteBuf buffer, OrderBlocksRequest payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeUUID(payload.projectId());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * One kind of block an order still places: {@code need} from the blueprint, {@code have} in
     * the village storage plus the Builder's pack, {@code given} by players.
     */
    public record BlockNeed(ResourceLocation item, int need, int have, int given) {}

    /** The answer; {@code ready} is false while the order's blueprint is still being read. */
    public record OrderBlocksReply(UUID projectId, boolean ready, List<BlockNeed> rows) implements CustomPacketPayload {
        public static final Type<OrderBlocksReply> TYPE = new Type<>(id("build_order_blocks_reply"));
        public static final StreamCodec<FriendlyByteBuf, OrderBlocksReply> CODEC = new StreamCodec<>() {
            @Override public OrderBlocksReply decode(FriendlyByteBuf buffer) {
                UUID projectId = buffer.readUUID();
                boolean ready = buffer.readBoolean();
                int count = Math.min(buffer.readVarInt(), MAX_ROWS);
                List<BlockNeed> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    rows.add(new BlockNeed(buffer.readResourceLocation(), buffer.readVarInt(), buffer.readVarInt(),
                            buffer.readVarInt()));
                }
                return new OrderBlocksReply(projectId, ready, rows);
            }
            @Override public void encode(FriendlyByteBuf buffer, OrderBlocksReply payload) {
                buffer.writeUUID(payload.projectId());
                buffer.writeBoolean(payload.ready());
                int count = Math.min(payload.rows().size(), MAX_ROWS);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    BlockNeed row = payload.rows().get(i);
                    buffer.writeResourceLocation(row.item());
                    buffer.writeVarInt(row.need());
                    buffer.writeVarInt(row.have());
                    buffer.writeVarInt(row.given());
                }
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** From the Projects tab: {@link #PAUSE} (toggles), {@link #UP}, {@link #DOWN} or {@link #GIVE}. */
    public record ProjectAction(int villagerId, UUID projectId, int action) implements CustomPacketPayload {
        public static final Type<ProjectAction> TYPE = new Type<>(id("build_project_action"));
        public static final StreamCodec<FriendlyByteBuf, ProjectAction> CODEC = new StreamCodec<>() {
            @Override public ProjectAction decode(FriendlyByteBuf buffer) {
                return new ProjectAction(buffer.readVarInt(), buffer.readUUID(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, ProjectAction payload) {
                buffer.writeVarInt(payload.villagerId());
                buffer.writeUUID(payload.projectId());
                buffer.writeVarInt(payload.action());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** One ordered build's box, for the outline in the world. */
    public record SiteRow(UUID id, String name, BlockPos origin, int sizeX, int sizeY, int sizeZ) {}

    /** To the client: every ordered build in its world (replaces the old list). */
    public record Sites(List<SiteRow> rows) implements CustomPacketPayload {
        public static final Type<Sites> TYPE = new Type<>(id("build_sites"));
        public static final StreamCodec<FriendlyByteBuf, Sites> CODEC = new StreamCodec<>() {
            @Override public Sites decode(FriendlyByteBuf buffer) {
                int count = Math.min(buffer.readVarInt(), MAX_ROWS);
                List<SiteRow> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    rows.add(new SiteRow(buffer.readUUID(), buffer.readUtf(64), buffer.readBlockPos(),
                            buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));
                }
                return new Sites(rows);
            }
            @Override public void encode(FriendlyByteBuf buffer, Sites payload) {
                int count = Math.min(payload.rows().size(), MAX_ROWS);
                buffer.writeVarInt(count);
                for (int i = 0; i < count; i++) {
                    SiteRow row = payload.rows().get(i);
                    buffer.writeUUID(row.id());
                    buffer.writeUtf(cut(row.name(), 64), 64);
                    buffer.writeBlockPos(row.origin());
                    buffer.writeVarInt(row.sizeX());
                    buffer.writeVarInt(row.sizeY());
                    buffer.writeVarInt(row.sizeZ());
                }
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    private static String cut(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(UploadBegin.TYPE, UploadBegin.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            if (!BlueprintUploads.begin(player, payload.uploadId(), payload.size())) {
                PacketDistributor.sendToPlayer(player, new OrderResult(false, "That blueprint is bigger than "
                        + BlueprintLimits.MAX_FILE_BYTES / (1024 * 1024) + " MB."));
            }
        }));
        registrar.playToServer(UploadChunk.TYPE, UploadChunk.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) BlueprintUploads.chunk(player, payload.uploadId(), payload.data());
        }));
        registrar.playToServer(Order.TYPE, Order.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                BuildOrders.order(player, payload.villagerId(), payload.uploadId(), payload.name(), payload.origin(),
                        payload.rotation(), payload.mirror(), payload.give());
            }
        }));
        registrar.playToServer(ProjectCancel.TYPE, ProjectCancel.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) BuildOrders.cancel(player, payload.villagerId(), payload.projectId());
        }));
        registrar.playToServer(ProjectAction.TYPE, ProjectAction.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                BuildOrders.act(player, payload.villagerId(), payload.projectId(), payload.action());
            }
        }));
        registrar.playToServer(GivenTakeBack.TYPE, GivenTakeBack.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(payload.item())
                    .ifPresent(item -> BuildOrders.takeBack(player, payload.villagerId(), item));
        }));
        registrar.playToServer(GiveItem.TYPE, GiveItem.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(payload.item()).ifPresent(item ->
                    BuildOrders.giveItem(player, payload.villagerId(), payload.projectId(), item, payload.amount()));
        }));
        registrar.playToServer(MaterialsRequest.TYPE, MaterialsRequest.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            List<net.minecraft.world.item.Item> items = new ArrayList<>();
            for (ResourceLocation key : payload.items()) {
                items.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(key));
            }
            PacketDistributor.sendToPlayer(player, new MaterialsReply(payload.requestId(),
                    BuildOrders.storageCounts(player, payload.villagerId(), items)));
        }));
        registrar.playToServer(OrderBlocksRequest.TYPE, OrderBlocksRequest.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                BuildOrders.sendOrderBlocks(player, payload.villagerId(), payload.projectId());
            }
        }));
        registrar.playToClient(OrderBlocksReply.TYPE, OrderBlocksReply.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.ui.screen.BuildOrderClient.orderBlocks(payload)));
        registrar.playToClient(MaterialsReply.TYPE, MaterialsReply.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.ui.screen.BuildOrderClient.materialsReply(payload.requestId(), payload.counts())));
        registrar.playToClient(OrderResult.TYPE, OrderResult.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.ui.screen.BuildOrderClient.result(payload.ok(), payload.message())));
        registrar.playToClient(Notice.TYPE, Notice.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.ui.screen.BuildOrderClient.notice(payload.ok(), payload.message())));
        registrar.playToClient(ProjectsSync.TYPE, ProjectsSync.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.ui.screen.BuildOrderClient.projects(payload.containerId(), payload.rows(), payload.given(),
                        payload.freeBy())));
        registrar.playToClient(Sites.TYPE, Sites.CODEC, (payload, ctx) -> ctx.enqueueWork(() ->
                com.alex.test.client.ConstructionSiteOutline.set(payload.rows())));
    }
}
