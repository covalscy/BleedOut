package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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
    private static final String MINIMAP_MODID = "covalscy_minimap";
    private static final int MINIMAP_POINTS_KNOCK = 30;
    private static final int MINIMAP_POINTS_REVIVE_ASSIST = 20;
    private static final int MINIMAP_POINTS_FINISH = 15;
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();
    // Ключ - UUID возрождаемого игрока, Значение - пара (UUID возрождающего, тики до завершения)
    private static final Map<UUID, ReviveData> revivingPlayers = new HashMap<>();
    // Отслеживаем, была ли поза установлена для игрока
    private static final Map<UUID, Boolean> poseSetForPlayers = new HashMap<>();
    // Отслеживаем игроков, которые должны умереть (флаг для предотвращения нового кровотечения)
    private static final Map<UUID, Boolean> dyingPlayers = new HashMap<>();
    // Отслеживаем кто положил игрока в кровотечение: UUID упавшего -> UUID того, кто положил (downer)
    private static final Map<UUID, UUID> bleedingDowners = new HashMap<>();

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

    // Проверка обходных источников урона (как в PlayerRevive)
    private static boolean doesByPass(ServerPlayer player, DamageSource source)
    {
        String msgId = source.getMsgId();
        // Разрешаем урон типа genericKill/generic (самоубийство, истечение таймера)
        if (msgId.equals("genericKill") || msgId.equals("generic"))
            return true;
        // Разрешаем урон от взрыва техники SuperbWarfare - сразу убиваем, не кладем в кровотечение
        if (msgId.equals("vehicle_explosion") || msgId.equals("air_crash"))
            return true;
        // Разрешаем урон от взрывов снарядов SuperbWarfare (РПГ, ракеты, танковые снаряды)
        if (msgId.equals("projectile_explosion") || msgId.equals("custom_explosion") || 
        msgId.equals("superbwarfare:lunge_mine") || msgId.equals("superbwarfare:mine") || msgId.equals("superbwarfare:vehicle_strike"))
            return true;
        // Разрешаем урон от Beast (специальное оружие SuperbWarfare)
        if (msgId.equals("beast"))
            return true;
        return false;
    }

    @SubscribeEvent
    public static void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            UUID playerId = player.getUUID();
            
            // Если игрок помечен как умирающий - разрешаем урон
            if (dyingPlayers.containsKey(playerId))
                return;
            
            // Обрабатываем ТОЛЬКО игроков УЖЕ в кровотечении (как в PlayerRevive)
            if (bleedingPlayers.containsKey(playerId))
            {
                // Если это обходной путь (genericKill/generic) - разрешаем урон
                if (doesByPass(player, source))
                    return;
                
                int ticksLeft = bleedingPlayers.get(playerId);
                int bleedoutTicks = Config.bleedoutTime * 20;
                int downedTime = bleedoutTicks - ticksLeft;
                
                // Если таймер истек (0 тиков осталось) - блокируем весь урон (игрок должен умереть от нашего кода)
                if (ticksLeft <= 0)
                {
                    event.setCanceled(true);
                    return;
                }
                
                // Начальная неуязвимость (первые 10 тиков = 0.5 сек) - защита от мгновенного добивания
                if (downedTime <= Config.initialDamageCooldown)
                {
                    event.setCanceled(true);
                    return;
                }
                
                // После начальной неуязвимости - разрешаем урон (игрока могут добить)
                // НЕ блокируем урон, чтобы игрок мог быть добит другими игроками или мобами
            }
            
            // Получение урона возрождающим больше НЕ прерывает процесс возрождения.
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            UUID playerId = player.getUUID();
            
            // LOGGER.info("[BleedOut] LivingDeathEvent: Player={}, IsBleeding={}, IsDying={}, Source={}", 
            //     player.getName().getString(), bleedingPlayers.containsKey(playerId), 
            //     dyingPlayers.containsKey(playerId), source.getMsgId());
            
            // Если это обходной путь (как в PlayerRevive) - НЕ отменяем смерть
            if (doesByPass(player, source))
            {
                // LOGGER.info("[BleedOut] Bypass source detected, allowing death");
                
                // Отправляем информацию о килле в TAB-mod (если установлен)
                // Это важно для засчитывания киллов от взрывов ракет, РПГ, танковых снарядов и т.д.
                sendKillInfoToTabMod(player, source);
                
                // Force dismount to prevent glitches where player is teleported back to vehicle after respawn
                if (player.isPassenger())
                    player.stopRiding();
                
                // Очищаем все флаги если игрок был в системе
                if (bleedingPlayers.containsKey(playerId) || dyingPlayers.containsKey(playerId))
                {
                    // Если игрока возрождали - уведомляем возрождающего
                    if (revivingPlayers.containsKey(playerId))
                    {
                        ReviveData reviveData = revivingPlayers.get(playerId);
                        ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                        
                        if (reviver != null)
                        {
                            com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                                new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                                reviver.connection.connection,
                                net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                            );
                        }
                    }
                    
                    bleedingPlayers.remove(playerId);
                    revivingPlayers.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    dyingPlayers.remove(playerId);
                    bleedingDowners.remove(playerId); // Очищаем информацию о downer
                    player.getPersistentData().remove("bleedout:bleeding");
                }
                return;
            }
            
            // Если игрок УЖЕ в кровотечении или умирает - разрешаем смерть (как в PlayerRevive)
            if (bleedingPlayers.containsKey(playerId) || dyingPlayers.containsKey(playerId))
            {
                // LOGGER.info("[BleedOut] Player {} was bleeding/dying, allowing death", player.getName().getString());
                
                // Если игрока возрождали - уведомляем возрождающего
                if (revivingPlayers.containsKey(playerId))
                {
                    ReviveData reviveData = revivingPlayers.get(playerId);
                    ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                    
                    if (reviver != null)
                    {
                        com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                            new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                            reviver.connection.connection,
                            net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                        );
                    }

                    // Также уведомляем самого возрождаемого (player), что прогрессбар нужно скрыть
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );

                    // Также уведомляем самого возрождаемого (player), что прогрессбар нужно скрыть
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                }
                
                // Отправляем информацию о килле в TAB-mod (если установлен)
                sendKillInfoToTabMod(player, source);
                
                bleedingPlayers.remove(playerId);
                revivingPlayers.remove(playerId);
                poseSetForPlayers.remove(playerId);
                dyingPlayers.remove(playerId);
                bleedingDowners.remove(playerId); // Очищаем информацию о downer
                player.getPersistentData().remove("bleedout:bleeding");
                return;
            }

            // LOGGER.info("[BleedOut] Player {} died, starting bleedout", player.getName().getString());
            
            // Останавливаем игрока, если он был пассажиром (как в PlayerRevive)
            if (player.isPassenger())
                player.stopRiding();
            
            // Начинаем кровотечение (добавляем в список, устанавливаем позу)
            // Передаем DamageSource для сохранения информации о том, кто положил игрока
            startBleedout(player, source);
            
            // Отменяем смерть (как в PlayerRevive)
            event.setCanceled(true);
            
            // Устанавливаем здоровье ПОСЛЕ отмены смерти (как в PlayerRevive)
            player.setHealth(Config.bleedingHealth);
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

                        // И возрождаемому
                        com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                            new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                            player.connection.connection,
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
                    com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
                        new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false, 0),
                        player
                    );
                    
                    // Отправляем информацию о килле в TAB-mod (таймер истек - нет finisher)
                    // Используем genericKill как источник урона (finisher = null)
                    sendKillInfoToTabMod(player, player.damageSources().genericKill());
                    
                    // Очищаем информацию о downer после отправки пакета
                    bleedingDowners.remove(playerId);
                    
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
                    
                    // Блокируем регенерацию здоровья - устанавливаем здоровье обратно на 0.5, пока что не надо
                    // if (player.getHealth() > 0.5f)
                    // {
                    //     player.setHealth(0.5f);
                    // }
                    
                    // Ограничиваем скорость движения (медленное ползание)
                    var deltaMovement = player.getDeltaMovement();
                    double maxSpeed = 0.01; // Очень медленная скорость ползания
                    
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

                    // И возрождаемому
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                        player.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );

                    // Начисляем assist сразу в момент успешного поднятия
                    sendReviveAssistToTabMod(player, reviver);

                    // Начисляем очки в MinimapMod за успешное поднятие
                    addPointsToMinimap(reviver, MINIMAP_POINTS_REVIVE_ASSIST, "revive_assist");
                    
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
                // Отключено: игрок может получать урон и умирать во время кровотечения
                // if (player.getHealth() > 0.5f)
                // {
                //     player.setHealth(0.5f);
                // }
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

                // И возрождаемому, чтобы он видел, что его поднимают
                com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                    new com.covalscy.bleedout.network.ReviveUpdatePacket(true, reviveTicks),
                    targetPlayer.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
                
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            UUID playerId = player.getUUID();
            
            // Если игрок находится в состоянии кровотечения при выходе - убиваем его
            if (bleedingPlayers.containsKey(playerId))
            {
                // LOGGER.info("[BleedOut] Player {} logged out while bleeding, killing", player.getName().getString());
                
                // Убиваем игрока перед отключением
                killBleedingPlayer(player);
            }

        }
    }

    private static void startBleedout(ServerPlayer player, DamageSource source)
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
        
        // Сохраняем информацию о том, кто положил игрока (downer)
        UUID downerId = null;
        if (source.getEntity() instanceof ServerPlayer downer) {
            // Проверяем что это не самоубийство
            if (!downer.getUUID().equals(playerId)) {
                downerId = downer.getUUID();
                bleedingDowners.put(playerId, downerId);
                // LOGGER.info("[BleedOut] Saved downer {} for bleeding player {}", downerId, playerId);
            }
        }
        // Также проверяем прямой источник урона (для снарядов)
        else if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            if (projectile.getOwner() instanceof ServerPlayer downer) {
                if (!downer.getUUID().equals(playerId)) {
                    downerId = downer.getUUID();
                    bleedingDowners.put(playerId, downerId);
                    // LOGGER.info("[BleedOut] Saved downer {} (from projectile) for bleeding player {}", downerId, playerId);
                }
            }
        }

        // Начисляем очки за нок в MinimapMod
        if (downerId != null)
        {
            ServerPlayer downerPlayer = player.server.getPlayerList().getPlayer(downerId);
            addPointsToMinimap(downerPlayer, MINIMAP_POINTS_KNOCK, "knock");
        }
        
        // Конвертируем секунды в тики (20 тиков = 1 секунда)
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false); // Пока не установили позу
        // LOGGER.info("[BleedOut] Added player {} to bleeding list with {} ticks", 
        //     player.getName().getString(), bleedoutTicks);
        
        // Здоровье устанавливается в LivingDeathEvent ПОСЛЕ отмены смерти (как в PlayerRevive)
        // player.setHealth() вызывается там, а не здесь
        
        // Устанавливаем позу ползания (лежащий) - используем setForcedPose как в PlayerRevive
        Pose oldPose = player.getPose();
        player.setForcedPose(Pose.SWIMMING);
        // Также устанавливаем обычную позу для синхронизации с клиентом
        player.setPose(Pose.SWIMMING);
        poseSetForPlayers.put(playerId, true);
        
        // Устанавливаем флаг в PersistentData для синхронизации с клиентом (как в PlayerRevive)
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        
        // Отправляем пакет на клиент для синхронизации состояния
        int initialTicks = bleedingPlayers.get(playerId);
        com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, true, initialTicks),
            player
        );
        
        // LOGGER.info("[BleedOut] Set forced pose to SWIMMING for player {} (was {}), also set normal pose and PersistentData flag, sent packet", 
        //     player.getName().getString(), oldPose);
        
        // Игрок может медленно ползать, не блокируем движение полностью
        player.setJumping(false);
    }

    /**
     * API method for other mods to check if a player is bleeding.
     * @param player The player to check
     * @return true if the player is bleeding, false otherwise
     */
    public static boolean isBleeding(Player player)
    {
        return bleedingPlayers.containsKey(player.getUUID());
    }
    
    /**
     * API method for other mods to get remaining bleedout time in ticks.
     * @param player The player to check
     * @return remaining ticks, or 0 if not bleeding
     */
    public static int getBleedoutTimeLeft(Player player)
    {
        return bleedingPlayers.getOrDefault(player.getUUID(), 0);
    }
    
    /**
     * API method for other mods to check if a player is being revived.
     * @param player The player to check
     * @return true if the player is being revived, false otherwise
     */
    public static boolean isBeingRevived(Player player)
    {
        return revivingPlayers.containsKey(player.getUUID());
    }
    
    /**
     * API method for other mods to instantly kill a player.
     * If player is NOT bleeding: kills instantly without bleedout.
     * If player IS bleeding: finishes them off (allows death).
     * 
     * @param player The player to kill
     */
    public static void instantKill(ServerPlayer player)
    {
        // Force dismount to prevent glitches
        if (player.isPassenger())
            player.stopRiding();

        UUID playerId = player.getUUID();
        
        if (bleedingPlayers.containsKey(playerId))
        {
            // Игрок УЖЕ в кровотечении - добиваем
            // LOGGER.info("[BleedOut] Instant kill for bleeding player {}", player.getName().getString());
            killBleedingPlayer(player);
        }
        else
        {
            // Игрок НЕ в кровотечении - помечаем для bypass и убиваем моментально
            // LOGGER.info("[BleedOut] Instant kill for non-bleeding player {}", player.getName().getString());
            
            // Помечаем как умирающего, чтобы bypass сработал
            dyingPlayers.put(playerId, true);
            
            // Убираем принудительную позу, если была установлена
            player.setForcedPose(null);
            
            // Убиваем игрока с genericKill (это bypass источник)
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        }
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
            com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
                new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false, 0),
                player
            );
            
            // Отправляем информацию о килле в TAB-mod (самоубийство - нет finisher)
            // Используем genericKill как источник урона (finisher = null)
            sendKillInfoToTabMod(player, player.damageSources().genericKill());
            
            // Очищаем информацию о downer после отправки пакета
            bleedingDowners.remove(playerId);
            
            // Восстанавливаем позу перед смертью
            player.setForcedPose(null);
            
            // Убиваем игрока
            // LOGGER.info("[BleedOut] Dealing lethal damage to player {} (suicide)", player.getName().getString());
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
            
            // Удаляем флаг умирания после нанесения урона
            dyingPlayers.remove(playerId);
        }
    }
    
    /**
     * Отправляет информацию о килле в TAB-mod (если установлен)
     * @param victim Игрок, который умер
     * @param source Источник урона при окончательной смерти
     */
    private static void sendKillInfoToTabMod(ServerPlayer victim, DamageSource source)
    {
        // Проверяем наличие TAB-mod
        if (!ModList.get().isLoaded("customtab"))
        {
            return; // TAB-mod не установлен, не отправляем пакет
        }
        
        UUID victimId = victim.getUUID();
        UUID killerId = bleedingDowners.get(victimId); // Тот, кто положил игрока (downer), может быть null
        
        // Определяем finisher (кто добил) из DamageSource
        UUID finisherId = null;
        String msgId = source.getMsgId();
        
        // Если это самоубийство или падение - finisher = null
        // Если это genericKill (истечение таймера) - finisher = null
        if (!msgId.equals("genericKill") && !msgId.equals("generic") && !msgId.equals("fall"))
        {
            // Проверяем есть ли добивший игрок
            if (source.getEntity() instanceof ServerPlayer finisher)
            {
                // Проверяем что это не самоубийство
                if (!finisher.getUUID().equals(victimId))
                {
                    finisherId = finisher.getUUID();
                }
            }
            // Также проверяем прямой источник урона (для снарядов)
            else if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile)
            {
                if (projectile.getOwner() instanceof ServerPlayer finisher)
                {
                    if (!finisher.getUUID().equals(victimId))
                    {
                        finisherId = finisher.getUUID();
                    }
                }
            }
        }

        // Очки за добивание нокнутого игрока в MinimapMod: +15 тому, кто добил.
        // Засчитываем только если у жертвы был downer (т.е. смерть реально после нока).
        if (finisherId != null && bleedingDowners.containsKey(victimId))
        {
            ServerPlayer finisherPlayer = victim.server.getPlayerList().getPlayer(finisherId);
            addPointsToMinimap(finisherPlayer, MINIMAP_POINTS_FINISH, "finish_knocked");
        }
        
        // Если нет downer (bypass-смерть без кровотечения), то finisher становится killer
        UUID assistId = null;
        if (killerId == null && finisherId != null)
        {
            // Bypass-смерть: игрок умер моментально от взрыва/урона
            // Тот, кто нанес урон, становится killer
            killerId = finisherId;
            finisherId = null; // assist не нужен
        }
        else if (killerId != null && finisherId != null)
        {
            // Обычная смерть через кровотечение: downer = killer, finisher = assist
            assistId = finisherId;
        }

        // Если нет killer И нет assist - не отправляем пакет
        if (killerId == null && assistId == null)
        {
            // LOGGER.warn("[BleedOut] No killer and no assist found for victim {}, skipping kill info", victimId);
            return;
        }
        
        // Отправляем информацию в TAB-mod напрямую через рефлексию
        try
        {
            // Вызываем метод обработки килла в TAB-mod напрямую
            // Передаем сервер из victim.server для доступа к игрокам
            net.minecraft.server.MinecraftServer server = victim.server;
            Class<?> handlerClass = Class.forName("com.covalscy.customtab.server.BleedOutKillHandler");
            java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleKill", 
                net.minecraft.server.MinecraftServer.class, UUID.class, UUID.class, UUID.class);
            handleMethod.invoke(null, server, victimId, killerId, assistId);
            
            // LOGGER.info("[BleedOut] Sent kill info to TAB-mod: victim={}, killer={}, assist={}", 
            //     victimId, killerId, assistId);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending kill info to TAB-mod: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Начисляет assist в TAB-mod сразу при успешном поднятии игрока.
     * Используем текущий интеграционный хендлер TAB через рефлексию.
     */
    private static void sendReviveAssistToTabMod(ServerPlayer revived, ServerPlayer reviver)
    {
        if (revived == null || reviver == null)
        {
            return;
        }

        // Проверяем наличие TAB-mod
        if (!ModList.get().isLoaded("customtab"))
        {
            return;
        }

        UUID revivedId = revived.getUUID();
        UUID reviverId = reviver.getUUID();

        // Защита от некорректного кейса (сам себя поднял)
        if (revivedId.equals(reviverId))
        {
            return;
        }

        try
        {
            net.minecraft.server.MinecraftServer server = revived.server;
            Class<?> handlerClass = Class.forName("com.covalscy.customtab.server.BleedOutKillHandler");
            java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleKill",
                net.minecraft.server.MinecraftServer.class, UUID.class, UUID.class, UUID.class);

            // killer = null, assist = reviver: ассист без привязки к отдельному киллу в этом моде
            handleMethod.invoke(null, server, revivedId, null, reviverId);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending revive assist to TAB-mod: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Начисляет очки в MinimapMod через DamageTracker.addPoints(player, points).
     * Если MinimapMod не установлен — ничего не делает.
     */
    private static void addPointsToMinimap(ServerPlayer player, int points, String reason)
    {
        if (player == null || points <= 0)
        {
            return;
        }

        // Не отправляем ничего, если мод не установлен
        if (!ModList.get().isLoaded(MINIMAP_MODID))
        {
            return;
        }

        try
        {
            Class<?> damageTrackerClass = Class.forName("com.covalscy.minimap.kits.DamageTracker");
            java.lang.reflect.Method addPointsMethod = damageTrackerClass.getMethod(
                "addPoints",
                net.minecraft.world.entity.player.Player.class,
                int.class
            );

            addPointsMethod.invoke(null, player, points);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending points to MinimapMod (reason=" + reason + "): " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void revivePlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        // LOGGER.info("[BleedOut] Reviving player {}", player.getName().getString());
        
        // Убираем из кровотечения
        bleedingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId); // Очищаем информацию о downer при возрождении
        player.getPersistentData().remove("bleedout:bleeding");
        
        // Отправляем пакет на клиент для синхронизации состояния
        com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false, 0),
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
