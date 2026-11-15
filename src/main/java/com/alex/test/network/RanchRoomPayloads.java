package com.alex.test.network;

import com.alex.test.DynamicVillages;
import com.alex.test.ui.RanchRoomMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.UUID;

/** The Ranch screen's packets: only the server's fresh snapshot for now, as the screen is look only. */
public final class RanchRoomPayloads {
    private RanchRoomPayloads() {}

    public record Sync(UUID frameId, RanchRoomMenu.Snapshot snapshot) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(
                DynamicVillages.MODID, "ranch_sync"));
        public static final StreamCodec<FriendlyByteBuf, Sync> CODEC = new StreamCodec<>() {
            @Override public Sync decode(FriendlyByteBuf buf) {
                return new Sync(buf.readUUID(), RanchRoomMenu.readSnapshot(buf));
            }
            @Override public void encode(FriendlyByteBuf buf, Sync payload) {
                buf.writeUUID(payload.frameId());
                RanchRoomMenu.writeSnapshot(buf, payload.snapshot());
            }
        };
        @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    public static void register(PayloadRegistrar registrar) {
        registrar.playToClient(Sync.TYPE, Sync.CODEC, (payload, ctx) -> ctx.enqueueWork(() -> {
            // Runs on the client; the open menu is common code, so no client class is needed.
            if (ctx.player() != null && ctx.player().containerMenu instanceof RanchRoomMenu menu
                    && menu.frameId().equals(payload.frameId())) {
                menu.applySnapshot(payload.snapshot());
            }
        }));
    }
}
