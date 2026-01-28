package com.covalscy.bleedout.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Пакет для синхронизации состояния возрождения с клиентом
 */
public class ReviveUpdatePacket
{
    private final boolean isReviving;
    private final int ticksLeft;

    public ReviveUpdatePacket(boolean isReviving, int ticksLeft)
    {
        this.isReviving = isReviving;
        this.ticksLeft = ticksLeft;
    }

    public static void encode(ReviveUpdatePacket packet, FriendlyByteBuf buffer)
    {
        buffer.writeBoolean(packet.isReviving);
        buffer.writeInt(packet.ticksLeft);
    }

    public static ReviveUpdatePacket decode(FriendlyByteBuf buffer)
    {
        boolean isReviving = buffer.readBoolean();
        int ticksLeft = buffer.readInt();
        return new ReviveUpdatePacket(isReviving, ticksLeft);
    }

    public static void handle(ReviveUpdatePacket packet, Supplier<NetworkEvent.Context> contextSupplier)
    {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Вызываем клиентский код только на клиенте
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> 
                ClientPacketHandler.handleReviveUpdate(packet.isReviving, packet.ticksLeft)
            );
        });
        context.setPacketHandled(true);
    }
}
