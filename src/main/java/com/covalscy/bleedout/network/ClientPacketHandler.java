package com.covalscy.bleedout.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.ModList;

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
                // Обновляем PersistentData на клиенте
                if (isBleeding)
                {
                    player.getPersistentData().putBoolean("bleedout:bleeding", true);
                    player.getPersistentData().putInt("bleedout:ticksLeft", ticksLeft);
                    
                    // Сброс прицеливания для локального игрока
                    if (player == mc.player && player instanceof LocalPlayer localPlayer)
                    {
                        resetAimingForPlayer(localPlayer);
                    }
                }
                else
                {
                    player.getPersistentData().remove("bleedout:bleeding");
                    player.getPersistentData().remove("bleedout:ticksLeft");
                }
            }
        }
    }
    
    /**
     * Сбрасывает прицеливание для SuperbWarfare и TACZ
     */
    private static void resetAimingForPlayer(LocalPlayer player)
    {
        try
        {
            // Сброс прицеливания для SuperbWarfare
            if (ModList.get().isLoaded("superbwarfare"))
            {
                Class<?> clientEventHandlerClass = Class.forName("com.atsuishio.superbwarfare.event.ClientEventHandler");
                java.lang.reflect.Field zoomField = clientEventHandlerClass.getDeclaredField("zoom");
                zoomField.setAccessible(true);
                zoomField.setBoolean(null, false);
            }
        }
        catch (Exception e)
        {
            // Игнорируем ошибки, если мод не установлен или структура изменилась
        }
        
        try
        {
            // Сброс прицеливания для TACZ
            if (ModList.get().isLoaded("tacz"))
            {
                Class<?> clientOperatorClass = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
                java.lang.reflect.Method fromLocalPlayerMethod = clientOperatorClass.getDeclaredMethod("fromLocalPlayer", LocalPlayer.class);
                Object operator = fromLocalPlayerMethod.invoke(null, player);
                
                java.lang.reflect.Method aimMethod = clientOperatorClass.getDeclaredMethod("aim", boolean.class);
                aimMethod.invoke(operator, false);
            }
        }
        catch (Exception e)
        {
            // Игнорируем ошибки, если мод не установлен или структура изменилась
        }
    }

    public static void handleReviveUpdate(boolean isReviving, int ticksLeft)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player != null)
        {
            // Сохраняем данные о возрождении в PersistentData
            player.getPersistentData().putBoolean("bleedout:reviving", isReviving);
            player.getPersistentData().putInt("bleedout:reviveTicks", ticksLeft);
        }
    }
}
