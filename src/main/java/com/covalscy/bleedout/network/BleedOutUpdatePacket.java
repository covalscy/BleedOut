package com.covalscy.bleedout.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class BleedOutUpdatePacket
{
    private final UUID playerId;
    private final boolean isBleeding;
    private final int ticksLeft; // Оставшееся время в тиках с сервера

    public BleedOutUpdatePacket(UUID playerId, boolean isBleeding, int ticksLeft)
    {
        this.playerId = playerId;
        this.isBleeding = isBleeding;
        this.ticksLeft = ticksLeft;
    }

    public void encode(FriendlyByteBuf buffer)
    {
        buffer.writeUUID(playerId);
        buffer.writeBoolean(isBleeding);
        buffer.writeInt(ticksLeft);
    }

    public static BleedOutUpdatePacket decode(FriendlyByteBuf buffer)
    {
        return new BleedOutUpdatePacket(
            buffer.readUUID(),
            buffer.readBoolean(),
            buffer.readInt()
        );
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        context.get().enqueueWork(() -> {
            if (context.get().getDirection().getReceptionSide().isClient())
            {
                // Вызываем клиентский код только на клиенте
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> 
                    ClientPacketHandler.handleBleedOutUpdate(playerId, isBleeding, ticksLeft)
                );
            }
        });
        context.get().setPacketHandled(true);
    }
}
