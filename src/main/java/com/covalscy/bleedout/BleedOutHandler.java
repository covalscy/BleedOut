package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BleedOutHandler
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();
    // Ключ - UUID возрождаемого игрока, Значение - пара (UUID возрождающего, тики до завершения)
    private static final Map<UUID, ReviveData> revivingPlayers = new HashMap<>();
    // Отслеживаем, была ли поза установлена для игрока
    private static final Map<UUID, Boolean> poseSetForPlayers = new HashMap<>();
    // Отслеживаем игроков, которые должны умереть (флаг для предотвращения нового кровотечения)
    private static final Map<UUID, Boolean> dyingPlayers = new HashMap<>();

    private static class ReviveData
    {
        final UUID reviverId;
        int ticksLeft;

        ReviveData(UUID reviverId, int ticksLeft)
        {
            this.reviverId = reviverId;
            this.ticksLeft = ticksLeft;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingDamageEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            String msgId = source.getMsgId();
            UUID playerId = player.getUUID();
            
            // LOGGER.info("[BleedOut] LivingDamageEvent: Player={}, Health={}, Damage={}, Source={}, IsBleeding={}, IsDying={}", 
            //     player.getName().getString(), player.getHealth(), event.getAmount(), msgId, 
            //     bleedingPlayers.containsKey(playerId), dyingPlayers.containsKey(playerId));
            
            // Если игрок помечен как умирающий - пропускаем урон и разрешаем смерть
            if (dyingPlayers.containsKey(playerId))
            {
                // LOGGER.info("[BleedOut] Player {} is marked as dying, allowing damage", player.getName().getString());
                return;
            }
            
            // Проверяем, не истекает ли игрок уже кровью
            if (bleedingPlayers.containsKey(playerId))
            {
                // LOGGER.info("[BleedOut] Player {} is already bleeding", player.getName().getString());
                
                // Если урон от другого игрока - разрешаем (можно добить)
                if (source.getEntity() instanceof ServerPlayer && source.getEntity() != player)
                {
                    // LOGGER.info("[BleedOut] Damage from other player {}, allowing kill", 
                    //     ((ServerPlayer)source.getEntity()).getName().getString());
                    return;
                }
                
                // Если это урон типа genericKill/generic - разрешаем (самоубийство через ЛКМ или истечение таймера)
                if (msgId.equals("genericKill") || msgId.equals("generic"))
                {
                    // LOGGER.info("[BleedOut] Generic/kill damage detected ({}), allowing death for bleeding player", msgId);
                    return;
                }
                
                // Блокируем ВСЕ остальные источники урона (регенерация, отравление и т.д.)
                // LOGGER.debug("[BleedOut] Blocking damage from {} for bleeding player {}", 
                //     msgId, player.getName().getString());
                event.setCanceled(true);
                return;
            }
            
            // Не обрабатываем фатальный урон здесь - пусть идет в LivingDeathEvent
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            String msgId = event.getSource().getMsgId();
            UUID playerId = player.getUUID();
            boolean wasBleedingBefore = bleedingPlayers.containsKey(playerId);
            boolean isDying = dyingPlayers.containsKey(playerId);
            
            // LOGGER.info("[BleedOut] LivingDeathEvent: Player={}, AlreadyBleeding={}, IsDying={}, Source={}", 
            //     player.getName().getString(), wasBleedingBefore, isDying, msgId);
            
            // Если игрок помечен как умирающий - разрешаем смерть
            if (isDying)
            {
                // LOGGER.info("[BleedOut] Player {} is marked as dying, allowing death", player.getName().getString());
                
                // Если игрока возрождали - отменяем возрождение и уведомляем возрождающего
                if (revivingPlayers.containsKey(playerId))
                {
                    ReviveData reviveData = revivingPlayers.get(playerId);
                    ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                    
                    if (reviver != null)
                    {
                        // LOGGER.info("[BleedOut] Cancelling revive for dying player {}, notifying reviver {}", 
                        //     player.getName().getString(), reviver.getName().getString());
                        
                        // Отправляем пакет возрождающему, что возрождение прервано
                        com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                            new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                            reviver.connection.connection,
                            net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                        );
                    }
                }
                
                // Очищаем все флаги
                bleedingPlayers.remove(playerId);
                revivingPlayers.remove(playerId);
                poseSetForPlayers.remove(playerId);
                dyingPlayers.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                // Разрешаем смерть
                return;
            }
            
            // Если игрок был в кровотечении и это урон типа genericKill/generic - разрешаем смерть
            if (wasBleedingBefore && (msgId.equals("genericKill") || msgId.equals("generic")))
            {
                // LOGGER.info("[BleedOut] Generic/kill death for bleeding player ({}), allowing death", msgId);
                // Убираем игрока из всех списков
                bleedingPlayers.remove(playerId);
                revivingPlayers.remove(playerId);
                poseSetForPlayers.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                // Разрешаем смерть
                return;
            }
            
            // Проверяем, не истекает ли игрок уже кровью
            if (wasBleedingBefore)
            {
                // LOGGER.info("[BleedOut] Player {} was bleeding, allowing death", player.getName().getString());
                // Если игрок уже истекает кровью, разрешаем смерть
                bleedingPlayers.remove(playerId);
                revivingPlayers.remove(playerId);
                poseSetForPlayers.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                return;
            }

            // LOGGER.info("[BleedOut] Player {} died, starting bleedout (fallback)", player.getName().getString());
            // Отменяем смерть и начинаем кровотечение (на случай, если событие урона не сработало)
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
            
            // Обработка кровотечения
            if (bleedingPlayers.containsKey(playerId))
            {
                int ticksLeft = bleedingPlayers.get(playerId);
                
                // Уменьшаем таймер
                ticksLeft--;
                
                if (ticksLeft <= 0)
                {
                    // LOGGER.info("[BleedOut] Bleedout time expired for player {}, killing", player.getName().getString());
                    
                    // Если игрока возрождали - отменяем возрождение и уведомляем возрождающего
                    if (revivingPlayers.containsKey(playerId))
                    {
                        ReviveData reviveData = revivingPlayers.get(playerId);
                        ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                        
                        if (reviver != null)
                        {
                            // LOGGER.info("[BleedOut] Cancelling revive for expired bleedout player {}, notifying reviver {}", 
                            //     player.getName().getString(), reviver.getName().getString());
                            
                            // Отправляем пакет возрождающему, что возрождение прервано
                            com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                                new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                                reviver.connection.connection,
                                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                            );
                        }
                    }
                    
                    // Время истекло - убиваем игрока
                    // Помечаем игрока как умирающего ПЕРЕД удалением из списка
                    dyingPlayers.put(playerId, true);
                    
                    bleedingPlayers.remove(playerId);
                    revivingPlayers.remove(playerId); // Убираем из возрождения, если было
                    poseSetForPlayers.remove(playerId);
                    // Убираем флаг из PersistentData
                    player.getPersistentData().remove("bleedout:bleeding");
                    
                    // Отправляем пакет на клиент для синхронизации состояния
                    com.covalscy.bleedout.network.BleedOutNetwork.sendToClient(
                        new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false),
                        player
                    );
                    
                    // Восстанавливаем позу перед смертью
                    player.setForcedPose(null);
                    
                    // LOGGER.info("[BleedOut] Dealing lethal damage to player {}", player.getName().getString());
                    player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
                    
                    // Удаляем флаг умирания после нанесения урона
                    dyingPlayers.remove(playerId);
                }
                else
                {
                    // Обновляем таймер
                    bleedingPlayers.put(playerId, ticksLeft);
                    
                    // Устанавливаем позу ползания (лежащий) - используем setForcedPose как в PlayerRevive
                    Pose currentPose = player.getPose();
                    if (currentPose != Pose.SWIMMING)
                    {
                        // LOGGER.debug("[BleedOut] Setting forced pose to SWIMMING for player {} (was {}), ticksLeft={}", 
                        //     player.getName().getString(), currentPose, ticksLeft);
                        player.setForcedPose(Pose.SWIMMING);
                        // Также устанавливаем обычную позу для синхронизации
                        player.setPose(Pose.SWIMMING);
                        poseSetForPlayers.put(playerId, true);
                    }
                    
                    // Блокируем регенерацию здоровья - устанавливаем здоровье обратно на 0.5
                    if (player.getHealth() > 0.5f)
                    {
                        player.setHealth(0.5f);
                    }
                    
                    // Ограничиваем скорость движения (медленное ползание)
                    var deltaMovement = player.getDeltaMovement();
                    double maxSpeed = 0.05; // Очень медленная скорость ползания
                    
                    // Запрещаем прыжки (сбрасываем вертикальную скорость если она положительная)
                    if (deltaMovement.y > 0)
                    {
                        player.setDeltaMovement(deltaMovement.x, 0, deltaMovement.z);
                        deltaMovement = player.getDeltaMovement();
                    }
                    
                    // Ограничиваем горизонтальную скорость
                    double horizontalSpeed = Math.sqrt(deltaMovement.x * deltaMovement.x + deltaMovement.z * deltaMovement.z);
                    if (horizontalSpeed > maxSpeed)
                    {
                        double scale = maxSpeed / horizontalSpeed;
                        player.setDeltaMovement(
                            deltaMovement.x * scale,
                            Math.min(deltaMovement.y, 0.1), // Ограничиваем вертикальную скорость
                            deltaMovement.z * scale
                        );
                    }
                    else if (horizontalSpeed > 0 && horizontalSpeed < maxSpeed)
                    {
                        // Если скорость уже медленная, оставляем как есть, но ограничиваем вертикальную
                        player.setDeltaMovement(
                            deltaMovement.x,
                            Math.min(deltaMovement.y, 0.1),
                            deltaMovement.z
                        );
                    }
                    
                    // Блокируем прыжки
                    player.setJumping(false);
                    
                    // Отправляем пакет на клиент каждые 5 тиков для синхронизации (как в PlayerRevive)
                    if (ticksLeft % 5 == 0)
                    {
                        com.covalscy.bleedout.network.BleedOutNetwork.sendToClient(
                            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, true),
                            player
                        );
                    }
                    
                    // Логируем каждые 20 тиков (1 секунда) для отладки
                    if (ticksLeft % 20 == 0)
                    {
                        // LOGGER.info("[BleedOut] Player {} bleeding: {} ticks left ({} sec), pose={}, health={}, deltaMovement={}", 
                        //     player.getName().getString(), ticksLeft, ticksLeft / 20, player.getPose(), 
                        //     player.getHealth(), player.getDeltaMovement());
                    }
                }
            }

            // Обработка возрождения
            if (revivingPlayers.containsKey(playerId))
            {
                ReviveData reviveData = revivingPlayers.get(playerId);
                ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                
                // Проверяем, что возрождающий игрок все еще рядом и жив
                // НЕ проверяем направление взгляда - игрок может отвести экран
                if (reviver == null || !reviver.isAlive() || 
                    reviver.distanceTo(player) > Config.reviveDistance || 
                    !isBleeding(player))
                {
                    // Прерываем возрождение только если возрождающий отошел слишком далеко или умер
                    revivingPlayers.remove(playerId);
                    
                    // Отправляем пакет возрождающему, что возрождение прервано
                    if (reviver != null)
                    {
                        com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                            new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                            reviver.connection.connection,
                            net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                        );
                    }
                    return;
                }
                
                // Продолжаем возрождение - уменьшаем таймер
                reviveData.ticksLeft--;
                
                if (reviveData.ticksLeft <= 0)
                {
                    // Возрождение завершено
                    // LOGGER.info("[BleedOut] Revive completed for player {}", player.getName().getString());
                    revivingPlayers.remove(playerId);
                    
                    // Отправляем пакет возрождающему, что возрождение завершено
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                        reviver.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                    
                    revivePlayer(player);
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onLivingUpdate(LivingEvent.LivingTickEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            UUID playerId = player.getUUID();
            
            // Если игрок истекает кровью, принудительно устанавливаем позу и здоровье
            if (bleedingPlayers.containsKey(playerId))
            {
                Pose currentPose = player.getPose();
                if (currentPose != Pose.SWIMMING)
                {
                    // Поза была сброшена - восстанавливаем принудительно
                    // LOGGER.debug("[BleedOut] Pose was reset to {} for player {}, restoring forced SWIMMING", 
                    //     currentPose, player.getName().getString());
                    player.setForcedPose(Pose.SWIMMING);
                }
                
                // Блокируем регенерацию - устанавливаем здоровье обратно на 0.5
                if (player.getHealth() > 0.5f)
                {
                    player.setHealth(0.5f);
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getTarget() instanceof ServerPlayer targetPlayer && 
            event.getEntity() instanceof ServerPlayer interactor &&
            event.getHand() == InteractionHand.MAIN_HAND)
        {
            // Проверяем, что цель истекает кровью, не возрождается уже, и это не сам игрок
            // Также проверяем расстояние - игрок должен быть рядом
            if (isBleeding(targetPlayer) && 
                !revivingPlayers.containsKey(targetPlayer.getUUID()) &&
                !targetPlayer.getUUID().equals(interactor.getUUID()) &&
                interactor.distanceTo(targetPlayer) <= Config.reviveDistance)
            {
                // Начинаем возрождение (одно нажатие ПКМ)
                int reviveTicks = Config.reviveTime * 20;
                // LOGGER.info("[BleedOut] Starting revive: {} reviving {} ({} ticks)", 
                //     interactor.getName().getString(), targetPlayer.getName().getString(), reviveTicks);
                revivingPlayers.put(targetPlayer.getUUID(), new ReviveData(interactor.getUUID(), reviveTicks));
                
                // Отправляем пакет возрождающему о начале возрождения
                com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                    new com.covalscy.bleedout.network.ReviveUpdatePacket(true, reviveTicks),
                    interactor.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
                
                event.setCanceled(true);
            }
        }
    }

    private static void startBleedout(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        
        // Проверяем, не начали ли мы уже кровотечение для этого игрока
        if (bleedingPlayers.containsKey(playerId))
        {
            // LOGGER.warn("[BleedOut] Player {} already in bleeding list! Current ticks: {}", 
            //     player.getName().getString(), bleedingPlayers.get(playerId));
            return;
        }
        
        // LOGGER.info("[BleedOut] Starting bleedout for player {} (bleedoutTime={}s)", 
        //     player.getName().getString(), Config.bleedoutTime);
        
        // Конвертируем секунды в тики (20 тиков = 1 секунда)
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false); // Пока не установили позу
        // LOGGER.info("[BleedOut] Added player {} to bleeding list with {} ticks", 
        //     player.getName().getString(), bleedoutTicks);
        
        // Устанавливаем здоровье на 0.5, чтобы игрок был близок к смерти
        player.setHealth(0.5f);
        // LOGGER.info("[BleedOut] Set health to 0.5 for player {} (was {})", 
        //     player.getName().getString(), player.getHealth());
        
        // Устанавливаем позу ползания (лежащий) - используем setForcedPose как в PlayerRevive
        Pose oldPose = player.getPose();
        player.setForcedPose(Pose.SWIMMING);
        // Также устанавливаем обычную позу для синхронизации с клиентом
        player.setPose(Pose.SWIMMING);
        poseSetForPlayers.put(playerId, true);
        
        // Устанавливаем флаг в PersistentData для синхронизации с клиентом (как в PlayerRevive)
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        
        // Отправляем пакет на клиент для синхронизации состояния
        com.covalscy.bleedout.network.BleedOutNetwork.sendToClient(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, true),
            player
        );
        
        // LOGGER.info("[BleedOut] Set forced pose to SWIMMING for player {} (was {}), also set normal pose and PersistentData flag, sent packet", 
        //     player.getName().getString(), oldPose);
        
        // Игрок может медленно ползать, не блокируем движение полностью
        player.setJumping(false);
    }

    public static boolean isBleeding(Player player)
    {
        return bleedingPlayers.containsKey(player.getUUID());
    }
    

    public static void killBleedingPlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        if (bleedingPlayers.containsKey(playerId))
        {
            // LOGGER.info("[BleedOut] Killing bleeding player {} (suicide request from client)", player.getName().getString());
            
            // Помечаем игрока как умирающего
            dyingPlayers.put(playerId, true);
            
            // Удаляем из всех списков
            bleedingPlayers.remove(playerId);
            revivingPlayers.remove(playerId);
            poseSetForPlayers.remove(playerId);
            player.getPersistentData().remove("bleedout:bleeding");
            
            // Отправляем пакет на клиент для синхронизации состояния
            com.covalscy.bleedout.network.BleedOutNetwork.sendToClient(
                new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false),
                player
            );
            
            // Восстанавливаем позу перед смертью
            player.setForcedPose(null);
            
            // Убиваем игрока
            // LOGGER.info("[BleedOut] Dealing lethal damage to player {} (suicide)", player.getName().getString());
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
            
            // Удаляем флаг умирания после нанесения урона
            dyingPlayers.remove(playerId);
        }
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

    private static void revivePlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        // LOGGER.info("[BleedOut] Reviving player {}", player.getName().getString());
        
        // Убираем из кровотечения
        bleedingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        
        // Убираем флаг из PersistentData для синхронизации с клиентом (как в PlayerRevive)
        player.getPersistentData().remove("bleedout:bleeding");
        
        // Отправляем пакет на клиент для синхронизации состояния
        com.covalscy.bleedout.network.BleedOutNetwork.sendToClient(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false),
            player
        );
        
        // LOGGER.info("[BleedOut] Removed player {} from bleeding list and PersistentData flag, sent packet", player.getName().getString());
        
        // Восстанавливаем здоровье
        player.setHealth(player.getMaxHealth() * 0.5f); // Восстанавливаем до половины здоровья
        // LOGGER.info("[BleedOut] Restored health to {} for player {}", player.getHealth(), player.getName().getString());
        
        // Возвращаем нормальную позу - снимаем принудительную позу
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
        // LOGGER.info("[BleedOut] Removed forced pose and set to STANDING for player {}", player.getName().getString());
        
        // Движение разблокируется автоматически, так как игрок больше не в списке кровотечения
    }

    public static boolean isBeingRevived(Player player)
    {
        return revivingPlayers.containsKey(player.getUUID());
    }

    public static int getReviveTimeLeft(Player player)
    {
        UUID playerId = player.getUUID();
        if (revivingPlayers.containsKey(playerId))
        {
            return revivingPlayers.get(playerId).ticksLeft / 20; // Конвертируем тики в секунды
        }
        return 0;
    }
}
