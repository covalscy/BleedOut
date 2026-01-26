package com.covalscy.bleedout.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

import java.util.UUID;
import java.util.function.Supplier;

public class BleedOutUpdatePacket
{
    private final UUID playerId;
    private final boolean isBleeding;

    public BleedOutUpdatePacket(UUID playerId, boolean isBleeding)
    {
        this.playerId = playerId;
        this.isBleeding = isBleeding;
    }

    public void encode(FriendlyByteBuf buffer)
    {
        buffer.writeUUID(playerId);
        buffer.writeBoolean(isBleeding);
    }

    public static BleedOutUpdatePacket decode(FriendlyByteBuf buffer)
    {
        return new BleedOutUpdatePacket(
            buffer.readUUID(),
            buffer.readBoolean()
        );
    }

    public void handle(Supplier<NetworkEvent.Context> context)
    {
        context.get().enqueueWork(() -> {
            if (context.get().getDirection().getReceptionSide().isClient())
            {
                Minecraft mc = Minecraft.getInstance();
                if (mc.level != null)
                {
                    Player player = mc.level.getPlayerByUUID(playerId);
                    if (player != null)
                    {
                        // Обновляем PersistentData на клиенте
                        if (isBleeding)
                        {
                            player.getPersistentData().putBoolean("bleedout:bleeding", true);
                        }
                        else
                        {
                            player.getPersistentData().remove("bleedout:bleeding");
                        }
                    }
                }
            }
        });
        context.get().setPacketHandled(true);
    }
}
