package com.covalscy.bleedout.network;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * Client-only packet handler to avoid loading client classes on server
 */
public class ClientPacketHandler
{
    public static void handleBleedOutUpdate(UUID playerId, boolean isBleeding, int ticksLeft)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null)
        {
            Player player = mc.level.getPlayerByUUID(playerId);
            if (player != null)
            {
                if (isBleeding)
                {
                    player.getPersistentData().putBoolean("bleedout:bleeding", true);
                    player.getPersistentData().putInt("bleedout:ticksLeft", ticksLeft);
                }
                else
                {
                    player.getPersistentData().remove("bleedout:bleeding");
                    player.getPersistentData().remove("bleedout:ticksLeft");
                }
            }
        }
    }

    public static void handleReviveUpdate(boolean isReviving, int ticksLeft)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player != null)
        {
            player.getPersistentData().putBoolean("bleedout:reviving", isReviving);
            player.getPersistentData().putInt("bleedout:reviveTicks", ticksLeft);
        }
    }
}
