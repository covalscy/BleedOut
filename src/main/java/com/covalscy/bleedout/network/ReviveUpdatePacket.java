package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record ReviveUpdatePacket(boolean isReviving, int ticksLeft) implements CustomPacketPayload
{
    public static final Type<ReviveUpdatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "revive_update"));

    public static final StreamCodec<FriendlyByteBuf, ReviveUpdatePacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, ReviveUpdatePacket::isReviving,
            ByteBufCodecs.INT, ReviveUpdatePacket::ticksLeft,
            ReviveUpdatePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public void handle(IPayloadContext context)
    {
        context.enqueueWork(() -> {
            ClientPacketHandler.handleReviveUpdate(isReviving, ticksLeft);
        });
    }
}
