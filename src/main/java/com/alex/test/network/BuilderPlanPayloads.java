package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.entity.NpcEntity;
import com.alex.test.ui.BuilderDeskMenu;
import com.alex.test.ui.NpcUiMenu;
import com.alex.test.village.VillageManager;
import com.alex.test.village.villagers.builder.copy.BuildingCopier;
import com.alex.test.village.villagers.builder.plan.BuilderPlans;
import com.alex.test.village.villagers.core.Profession;
import com.alex.test.village.villagers.tradesman.settings.TradesmanSalePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Messages of the Builder's plan item: {@link TakePlan} from the desk, {@link PlanAction}
 * (confirm, back to the desk, put away) from the player holding it, and {@link PlanEnded}
 * back so the client stops drawing the ghost. The ghost itself lives on the client (the
 * blueprint file is there); the server only hands out and takes back the item.
 */
public final class BuilderPlanPayloads {
    private BuilderPlanPayloads() {}

    public static final int CONFIRM = 0, BACK_TO_DESK = 1, CANCEL = 2;

    /** From the open desk: give me a plan of the picked blueprint. */
    public record TakePlan(int villagerId, String blueprintName, boolean copy) implements CustomPacketPayload {
        public static final Type<TakePlan> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_take_plan"));
        public static final StreamCodec<FriendlyByteBuf, TakePlan> CODEC = new StreamCodec<>() {
            @Override public TakePlan decode(FriendlyByteBuf buffer) {
                return new TakePlan(buffer.readVarInt(), buffer.readUtf(64), buffer.readBoolean());
            }
            @Override public void encode(FriendlyByteBuf buffer, TakePlan payload) {
                buffer.writeVarInt(payload.villagerId());
                String name = payload.blueprintName();
                buffer.writeUtf(name.length() > 64 ? name.substring(0, 64) : name, 64);
                buffer.writeBoolean(payload.copy());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * From the player holding the plan: {@link #CONFIRM} the spot (its lowest corner and
     * footprint size), go {@link #BACK_TO_DESK}, or {@link #CANCEL} (put it away).
     */
    public record PlanAction(int action, BlockPos origin, int sizeX, int sizeZ) implements CustomPacketPayload {
        public static final Type<PlanAction> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_plan_action"));
        public static final StreamCodec<FriendlyByteBuf, PlanAction> CODEC = new StreamCodec<>() {
            @Override public PlanAction decode(FriendlyByteBuf buffer) {
                return new PlanAction(buffer.readVarInt(), buffer.readBlockPos(), buffer.readVarInt(), buffer.readVarInt());
            }
            @Override public void encode(FriendlyByteBuf buffer, PlanAction payload) {
                buffer.writeVarInt(payload.action());
                buffer.writeBlockPos(payload.origin());
                buffer.writeVarInt(payload.sizeX());
                buffer.writeVarInt(payload.sizeZ());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * From a player holding a plan for copying: copy what is at {@code pick} (a clicked block),
     * sides grown as in {@code grow}. Answered with the desk's own copy messages.
     */
    public record PlanCopyScan(int requestId, BlockPos pick, int[] grow, @javax.annotation.Nullable BlockPos corner)
            implements CustomPacketPayload {
        public static final Type<PlanCopyScan> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_plan_copy_scan"));
        public static final StreamCodec<FriendlyByteBuf, PlanCopyScan> CODEC = new StreamCodec<>() {
            @Override public PlanCopyScan decode(FriendlyByteBuf buffer) {
                int requestId = buffer.readVarInt();
                BlockPos pick = buffer.readBlockPos();
                int[] grow = new int[BuildingCopier.SIDES];
                for (int i = 0; i < grow.length; i++) grow[i] = buffer.readVarInt();
                BlockPos corner = buffer.readBoolean() ? buffer.readBlockPos() : null;
                return new PlanCopyScan(requestId, pick, grow, corner);
            }
            @Override public void encode(FriendlyByteBuf buffer, PlanCopyScan payload) {
                buffer.writeVarInt(payload.requestId());
                buffer.writeBlockPos(payload.pick());
                for (int i = 0; i < BuildingCopier.SIDES; i++) {
                    buffer.writeVarInt(i < payload.grow().length ? payload.grow()[i] : 0);
                }
                buffer.writeBoolean(payload.corner() != null);
                if (payload.corner() != null) buffer.writeBlockPos(payload.corner());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** To the client: the plan was put away (or never given); stop drawing it. */
    public record PlanEnded() implements CustomPacketPayload {
        public static final Type<PlanEnded> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "builder_plan_ended"));
        public static final StreamCodec<FriendlyByteBuf, PlanEnded> CODEC = StreamCodec.unit(new PlanEnded());
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToServer(TakePlan.TYPE, TakePlan.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) take(player, payload);
        }));
        registrar.playToServer(PlanAction.TYPE, PlanAction.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) act(player, payload);
        }));
        registrar.playToServer(PlanCopyScan.TYPE, PlanCopyScan.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer player)) return;
            BuilderPlans.Plan plan = BuilderPlans.active(player);
            if (plan == null || !plan.copy) return;
            NpcEntity villager = builderOf(player, plan);
            if (villager == null) {
                player.displayClientMessage(Component.literal("Go closer to the Builder to copy."), true);
                return;
            }
            BuilderDeskPayloads.copyAndSend(player, villager, payload.requestId(), null, payload.pick(), payload.grow(),
                    payload.corner());
        }));
        registrar.playToClient(PlanEnded.TYPE, PlanEnded.CODEC, (payload, ctx) -> ctx.enqueueWork(
                com.alex.test.ui.screen.BuilderPlanClient::ended));
    }

    /** Server: the same checks as copying: this Builder's desk is open and the player may use it. */
    private static void take(ServerPlayer player, TakePlan payload) {
        if (!(player.containerMenu instanceof BuilderDeskMenu desk) || desk.villagerId() != payload.villagerId()) return;
        NpcEntity villager = desk.villager();
        if (villager == null || !villager.isAlive() || villager.getProfession() != Profession.BUILDER
                || !NpcUiMenu.villagerStillThere(player, villager) || !villager.canPlayerAccessSettings(player)
                || desk.snapshot().border() == null) {
            return;
        }
        String refused = BuilderPlans.give(player, villager, payload.blueprintName(), payload.copy());
        if (refused != null) {
            player.displayClientMessage(Component.literal(refused), true);
            PacketDistributor.sendToPlayer(player, new PlanEnded());
            return;
        }
        player.closeContainer();
    }

    private static void act(ServerPlayer player, PlanAction payload) {
        BuilderPlans.Plan plan = BuilderPlans.active(player);
        if (plan == null) {
            PacketDistributor.sendToPlayer(player, new PlanEnded());
            return;
        }
        NpcEntity villager = builderOf(player, plan);
        if (payload.action() == CONFIRM && villager != null && outsideBorder(villager, payload)) {
            // The client checks every column before it lets the player confirm; this only
            // catches a client that did not. The full check comes with ordering a build.
            player.displayClientMessage(Component.literal("That spot is outside the village border."), true);
            return;
        }
        BuilderPlans.end(player, payload.action() == CANCEL ? "Builder's plan put away." : null);
        if (payload.action() == CANCEL) return;
        if (villager == null || !villager.canPlayerAccessSettings(player)) {
            player.displayClientMessage(Component.literal(payload.action() == CONFIRM
                    ? "Spot kept. Open the Builder's desk to see it there."
                    : "The Builder is too far away to open the desk."), true);
            return;
        }
        BuilderDeskPayloads.openDesk(player, villager);
    }

    /** The plan's Builder when it is loaded, alive, still a Builder and the player may use it; else null. */
    @javax.annotation.Nullable
    static NpcEntity builderOf(ServerPlayer player, BuilderPlans.Plan plan) {
        return player.serverLevel().getEntity(plan.villagerId) instanceof NpcEntity npc && npc.isAlive()
                && npc.getProfession() == Profession.BUILDER && npc.canPlayerAccessSettings(player) ? npc : null;
    }

    private static boolean outsideBorder(NpcEntity villager, PlanAction payload) {
        VillageManager village = TradesmanSalePolicy.villageOf(villager);
        AABB box = village == null ? null : village.borderBounds();
        if (box == null) return false;
        int x = payload.origin().getX() + Math.max(0, payload.sizeX()) / 2;
        int z = payload.origin().getZ() + Math.max(0, payload.sizeZ()) / 2;
        return x < Math.floor(box.minX) || x > Math.floor(box.maxX) || z < Math.floor(box.minZ) || z > Math.floor(box.maxZ);
    }
}
