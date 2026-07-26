package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutHandler;
import com.covalscy.bleedout.Config;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class SuicideRequestPacket
{
    public SuicideRequestPacket()
    {
    }

    public void encode(FriendlyByteBuf buffer)
    {
       
    }

    public static SuicideRequestPacket decode(FriendlyByteBuf buffer)
    {
        return new SuicideRequestPacket();
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        context.get().enqueueWork(() -> {
            ServerPlayer player = context.get().getSender();
            if (Config.enableSuicide && player != null && BleedOutHandler.isBleeding(player))
            {
               
                BleedOutHandler.killBleedingPlayer(player);
            }
        });
        context.get().setPacketHandled(true);
    }
}
