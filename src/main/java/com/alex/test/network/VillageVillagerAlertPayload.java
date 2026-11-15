package com.alex.test.network;

import com.alex.test.DynamicVillages;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** Identifies a villager whose model should receive a temporary outline. */
public record VillageVillagerAlertPayload(UUID entityUuid, int durationTicks, boolean flashing,
                                          boolean yellowWarning, boolean guard)
        implements CustomPacketPayload {

    public VillageVillagerAlertPayload(UUID entityUuid, int durationTicks, boolean flashing) {
        this(entityUuid, durationTicks, flashing, false, false);
    }

    /** Not a guard: {@code guard} marks a guard, captain or archer a monster hit (blue, flashing when low). */
    public VillageVillagerAlertPayload(UUID entityUuid, int durationTicks, boolean flashing, boolean yellowWarning) {
        this(entityUuid, durationTicks, flashing, yellowWarning, false);
    }

    public static final Type<VillageVillagerAlertPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(DynamicVillages.MODID, "village_villager_alert"));

    public static final StreamCodec<FriendlyByteBuf, VillageVillagerAlertPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public VillageVillagerAlertPayload decode(FriendlyByteBuf buffer) {
                    return new VillageVillagerAlertPayload(buffer.readUUID(), buffer.readVarInt(),
                            buffer.readBoolean(), buffer.readBoolean(), buffer.readBoolean());
                }

                @Override
                public void encode(FriendlyByteBuf buffer, VillageVillagerAlertPayload payload) {
                    buffer.writeUUID(payload.entityUuid());
                    buffer.writeVarInt(payload.durationTicks());
                    buffer.writeBoolean(payload.flashing());
                    buffer.writeBoolean(payload.yellowWarning());
                    buffer.writeBoolean(payload.guard());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
