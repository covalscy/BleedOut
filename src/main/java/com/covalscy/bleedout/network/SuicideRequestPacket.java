package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutHandler;
import com.covalscy.bleedout.BleedOutMod;
import com.covalscy.bleedout.Config;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SuicideRequestPacket() implements CustomPacketPayload
{
    public static final SuicideRequestPacket INSTANCE = new SuicideRequestPacket();
    public static final Type<SuicideRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "suicide_request"));

    public static final StreamCodec<FriendlyByteBuf, SuicideRequestPacket> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public void handle(IPayloadContext context)
    {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player)
            {
                if (Config.enableSuicide && BleedOutHandler.isBleeding(player))
                {
                    BleedOutHandler.killBleedingPlayer(player);
                }
            }
        });
    }
}
