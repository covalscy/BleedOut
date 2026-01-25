package com.covalscy.bleedout;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BleedOutHandler
{
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            // Проверяем, не истекает ли игрок уже кровью
            if (bleedingPlayers.containsKey(player.getUUID()))
            {
                // Если игрок уже истекает кровью, разрешаем смерть
                bleedingPlayers.remove(player.getUUID());
                return;
            }

            // Отменяем смерть и начинаем кровотечение
            event.setCanceled(true);
            startBleedout(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide)
            return;

        if (event.player instanceof ServerPlayer player)
        {
            UUID playerId = player.getUUID();
            if (bleedingPlayers.containsKey(playerId))
            {
                int ticksLeft = bleedingPlayers.get(playerId);
                
                // Уменьшаем таймер
                ticksLeft--;
                
                if (ticksLeft <= 0)
                {
                    // Время истекло - убиваем игрока
                    bleedingPlayers.remove(playerId);
                    player.hurt(player.damageSources().generic(), Float.MAX_VALUE);
                }
                else
                {
                    // Обновляем таймер
                    bleedingPlayers.put(playerId, ticksLeft);
                    
                    // Блокируем движение игрока (лежащий)
                    player.setDeltaMovement(0, 0, 0);
                    player.setJumping(false);
                    
                    // Принудительно останавливаем движение, если игрок пытается двигаться
                    if (player.getDeltaMovement().lengthSqr() > 0.01)
                    {
                        player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
                    }
                }
            }
        }
    }

    private static void startBleedout(ServerPlayer player)
    {
        // Конвертируем секунды в тики (20 тиков = 1 секунда)
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(player.getUUID(), bleedoutTicks);
        
        // Устанавливаем здоровье на 0.5, чтобы игрок был близок к смерти
        player.setHealth(0.5f);
        
        // Останавливаем движение игрока
        player.setDeltaMovement(0, 0, 0);
        
        // Блокируем возможность прыгать и двигаться
        player.setJumping(false);
    }

    public static boolean isBleeding(Player player)
    {
        return bleedingPlayers.containsKey(player.getUUID());
    }

    public static int getBleedoutTimeLeft(Player player)
    {
        UUID playerId = player.getUUID();
        if (bleedingPlayers.containsKey(playerId))
        {
            return bleedingPlayers.get(playerId) / 20; // Конвертируем тики в секунды
        }
        return 0;
    }
}
