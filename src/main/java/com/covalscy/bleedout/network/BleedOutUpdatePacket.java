package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutMod;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.UUID;

public record BleedOutUpdatePacket(UUID playerId, boolean isBleeding, int ticksLeft) implements CustomPacketPayload
{
    public static final Type<BleedOutUpdatePacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "bleedout_update"));

    public static final StreamCodec<FriendlyByteBuf, BleedOutUpdatePacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BleedOutUpdatePacket::playerId,
            ByteBufCodecs.BOOL, BleedOutUpdatePacket::isBleeding,
            ByteBufCodecs.INT, BleedOutUpdatePacket::ticksLeft,
            BleedOutUpdatePacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public void handle(IPayloadContext context)
    {
        context.enqueueWork(() -> {
            ClientPacketHandler.handleBleedOutUpdate(playerId, isBleeding, ticksLeft);
        });
    }
}
