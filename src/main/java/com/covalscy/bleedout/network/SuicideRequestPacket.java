package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutHandler;
import com.covalscy.bleedout.BleedOutMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: the player started / stopped holding the suicide button, or finished holding it.
 * The server measures the hold time itself, so a modified client can't skip it.
 */
public record SuicideRequestPacket(Phase phase) implements CustomPacketPayload
{
    public enum Phase { START, CANCEL, CONFIRM }

    public static final Type<SuicideRequestPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "suicide_request"));

    public static final StreamCodec<FriendlyByteBuf, SuicideRequestPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> buf.writeEnum(packet.phase()),
            buf -> new SuicideRequestPacket(buf.readEnum(Phase.class)));

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
                BleedOutHandler.onSuicideRequest(player, phase);
            }
        });
    }
}
