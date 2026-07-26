package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import net.minecraftforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class BleedOutHandler
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String GUNGAME_TEAM_NAME = "gungame";
    private static final String MINIMAP_MODID = "covalscy_minimap";
    private static final int MINIMAP_POINTS_KNOCK = 30;
    private static final int MINIMAP_POINTS_REVIVE_ASSIST = 20;
    private static final int MINIMAP_POINTS_FINISH = 15;
    private static final int MINIMAP_POINTS_VEHICLE_DESTROY = 120;
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();
    private static final Map<UUID, ReviveData> revivingPlayers = new HashMap<>();
    private static final Map<UUID, Boolean> poseSetForPlayers = new HashMap<>();
    private static final Map<UUID, Boolean> dyingPlayers = new HashMap<>();
    private static final Map<UUID, UUID> bleedingDowners = new HashMap<>();
    private static final int BLEEDING_EFFECT_DURATION_TICKS = 40;

    private record BleedingEffectData(MobEffect effect, int amplifier)
    {
    }

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

    private static boolean doesByPass(ServerPlayer player, DamageSource source)
    {
        if (isInGunGameTeam(player))
            return true;

        String msgId = source.getMsgId();
        if (msgId.equals("genericKill") || msgId.equals("generic"))
            return true;
        if (msgId.equals("vehicle_explosion") || msgId.equals("air_crash"))
            return true;
        if (msgId.equals("projectile_explosion") || msgId.equals("custom_explosion") || 
            msgId.equals("superbwarfare:lunge_mine") || msgId.equals("superbwarfare:mine") || msgId.equals("superbwarfare:vehicle_strike"))
            return true;
        if (msgId.equals("beast"))
            return true;
        return false;
    }

    private static boolean isInGunGameTeam(ServerPlayer player)
    {
        return player.getTeam() != null && GUNGAME_TEAM_NAME.equalsIgnoreCase(player.getTeam().getName());
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            UUID playerId = player.getUUID();
            
            if (dyingPlayers.containsKey(playerId))
                return;
            
            if (bleedingPlayers.containsKey(playerId))
            {
                if (doesByPass(player, source))
                    return;
                
                int ticksLeft = bleedingPlayers.get(playerId);
                int bleedoutTicks = Config.bleedoutTime * 20;
                int downedTime = bleedoutTicks - ticksLeft;
                
                if (ticksLeft <= 0)
                {
                    event.setCanceled(true);
                    return;
                }
                
                if (downedTime <= Config.initialDamageCooldown)
                {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event)
    {
        if (!Config.bleedingPlayersIgnoredByMobs)
        {
            return;
        }

        if (!(event.getNewTarget() instanceof ServerPlayer player) || !isBleeding(player))
        {
            return;
        }

        event.setNewTarget(null);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBleedingPlayerHurtByMob(LivingHurtEvent event)
    {
        if (!Config.bleedingPlayersIgnoredByMobs)
        {
            return;
        }

        if (event.getEntity() instanceof ServerPlayer player && isBleeding(player))
        {
            net.minecraft.world.entity.Entity attacker = event.getSource().getEntity();
            net.minecraft.world.entity.Entity directAttacker = event.getSource().getDirectEntity();

            boolean isMobAttacker = (attacker != null && !(attacker instanceof Player)) || 
                                   (directAttacker != null && !(directAttacker instanceof Player));

            if (isMobAttacker)
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onVehicleDeath(LivingDeathEvent event)
    {
        if (!event.getEntity().level().isClientSide && !(event.getEntity() instanceof ServerPlayer))
        {
            net.minecraft.world.entity.Entity entity = event.getEntity();
            if (isSuperbWarfareVehicle(entity))
            {
                net.minecraft.world.entity.Entity killer = event.getSource().getEntity();
                if (killer instanceof ServerPlayer killerPlayer)
                {
                    addPointsToMinimap(killerPlayer, MINIMAP_POINTS_VEHICLE_DESTROY, "vehicle_destruction");
                }
            }
        }
    }

    private static boolean isSuperbWarfareVehicle(net.minecraft.world.entity.Entity entity)
    {
        if (!ModList.get().isLoaded("superbwarfare") || entity == null)
            return false;

        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return typeId != null && typeId.getNamespace().equals("superbwarfare");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            UUID playerId = player.getUUID();

            if (player.isPassenger())
            {
                player.stopRiding();
                sendKillInfoToTabMod(player, source);

                if (bleedingPlayers.containsKey(playerId) || dyingPlayers.containsKey(playerId))
                {
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
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
                    BloodyBitsCompat.clearBleedingPlayer(player);
                }
                return;
            }
            
            if (doesByPass(player, source))
            {
                sendKillInfoToTabMod(player, source);
                
                if (player.isPassenger())
                    player.stopRiding();
                
                if (bleedingPlayers.containsKey(playerId) || dyingPlayers.containsKey(playerId))
                {
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
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
                    BloodyBitsCompat.clearBleedingPlayer(player);
                }
                return;
            }
            
            if (bleedingPlayers.containsKey(playerId) || dyingPlayers.containsKey(playerId))
            {
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
                bleedingDowners.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                BloodyBitsCompat.clearBleedingPlayer(player);
                return;
            }
            
            event.setCanceled(true);
            player.setHealth(Config.bleedingHealth);
            
            ServerPlayer attacker = null;
            if (source.getEntity() instanceof ServerPlayer sAttacker)
            {
                attacker = sAttacker;
            }
            else if (source.getDirectEntity() instanceof ServerPlayer dAttacker)
            {
                attacker = dAttacker;
            }
            
            startBleedout(player, attacker);
        }
    }

    private static void startBleedout(ServerPlayer player, ServerPlayer attacker)
    {
        UUID playerId = player.getUUID();
        
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false);
        
        player.setPose(Pose.SWIMMING);
        poseSetForPlayers.put(playerId, true);
        
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        player.getPersistentData().putInt("bleedout:ticksLeft", bleedoutTicks);
        
        BloodyBitsCompat.setBleedingPlayer(player);

        if (attacker != null)
        {
            bleedingDowners.put(playerId, attacker.getUUID());
            addPointsToMinimap(attacker, MINIMAP_POINTS_KNOCK, "knockdown");
        }
        else
        {
            bleedingDowners.remove(playerId);
        }
        
        com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, true, bleedoutTicks),
            player
        );
        
        applyConfiguredBleedingEffects(player);
    }

    public static void killBleedingPlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        
        dyingPlayers.put(playerId, true);
        
        stopRevive(player, true, true);
        
        bleedingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        BloodyBitsCompat.clearBleedingPlayer(player);
        
        com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        sendKillInfoToTabMod(player, player.damageSources().genericKill());
        
        bleedingDowners.remove(playerId);
        
        player.setForcedPose(null);
        
        player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        
        dyingPlayers.remove(playerId);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        List<UUID> toRemove = new ArrayList<>();
        
        for (Map.Entry<UUID, Integer> entry : bleedingPlayers.entrySet())
        {
            UUID playerId = entry.getKey();
            int ticksLeft = entry.getValue() - 1;
            
            ServerPlayer player = getServerPlayer(playerId);
            if (player == null)
            {
                toRemove.add(playerId);
                continue;
            }
            
            if (ticksLeft <= 0)
            {
                toRemove.add(playerId);
                killBleedingPlayer(player);
            }
            else
            {
                entry.setValue(ticksLeft);
                
                if (player.tickCount % 20 == 0)
                {
                    applyConfiguredBleedingEffects(player);
                }
                
                if (player.getPose() != Pose.SWIMMING)
                {
                    player.setPose(Pose.SWIMMING);
                    poseSetForPlayers.put(playerId, true);
                }
                
                if (Config.disableMovementWhileBleeding)
                {
                    var deltaMovement = player.getDeltaMovement();
                    player.setDeltaMovement(
                        0,
                        Math.min(deltaMovement.y, 0.1),
                        0
                    );
                }
                else
                {
                    var deltaMovement = player.getDeltaMovement();
                    double maxSpeed = 0.01;
                    net.minecraft.world.phys.Vec3 horizMovement = new net.minecraft.world.phys.Vec3(deltaMovement.x, 0, deltaMovement.z);

                    if (horizMovement.length() > maxSpeed)
                    {
                        horizMovement = horizMovement.normalize().scale(maxSpeed);
                        player.setDeltaMovement(horizMovement.x, Math.min(deltaMovement.y, 0.1), horizMovement.z);
                    }
                }

                player.getPersistentData().putInt("bleedout:ticksLeft", ticksLeft);
                
                if (player.tickCount % 5 == 0)
                {
                    com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
                        new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, true, ticksLeft),
                        player
                    );
                }
            }
        }
        
        for (UUID id : toRemove)
        {
            bleedingPlayers.remove(id);
            poseSetForPlayers.remove(id);
        }
        
        List<UUID> revivesToRemove = new ArrayList<>();
        
        for (Map.Entry<UUID, ReviveData> entry : revivingPlayers.entrySet())
        {
            UUID revivedId = entry.getKey();
            ReviveData reviveData = entry.getValue();
            
            reviveData.ticksLeft--;
            
            ServerPlayer revivedPlayer = getServerPlayer(revivedId);
            ServerPlayer reviverPlayer = getServerPlayer(reviveData.reviverId);
            
            boolean canContinueRevive = revivedPlayer != null && 
                                       reviverPlayer != null && 
                                       !reviverPlayer.isDeadOrDying() && 
                                       !isBleeding(reviverPlayer) && 
                                       reviverPlayer.distanceTo(revivedPlayer) <= (Config.reviveDistance + 1.0);
            
            if (!canContinueRevive || reviveData.ticksLeft <= 0)
            {
                revivesToRemove.add(revivedId);
                
                if (reviveData.ticksLeft <= 0 && canContinueRevive)
                {
                    revivePlayer(revivedPlayer);
                    sendReviveAssistToTabMod(revivedPlayer, reviverPlayer);
                    addPointsToMinimap(reviverPlayer, MINIMAP_POINTS_REVIVE_ASSIST, "revive_assist");
                }
                else
                {
                    stopRevive(revivedPlayer, false, false);
                }
            }
            else
            {
                revivedPlayer.getPersistentData().putBoolean("bleedout:reviving", true);
                revivedPlayer.getPersistentData().putInt("bleedout:reviveTicks", reviveData.ticksLeft);
                
                reviverPlayer.getPersistentData().putBoolean("bleedout:reviving", true);
                reviverPlayer.getPersistentData().putInt("bleedout:reviveTicks", reviveData.ticksLeft);
                
                if (revivedPlayer.tickCount % 5 == 0)
                {
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(true, reviveData.ticksLeft),
                        reviverPlayer.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(true, reviveData.ticksLeft),
                        revivedPlayer.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                }
            }
        }
        
        for (UUID id : revivesToRemove)
        {
            revivingPlayers.remove(id);
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getTarget() instanceof ServerPlayer targetPlayer && 
            event.getEntity() instanceof ServerPlayer interactor &&
            event.getHand() == InteractionHand.MAIN_HAND)
        {
            if (isBleeding(targetPlayer) && 
                !revivingPlayers.containsKey(targetPlayer.getUUID()) &&
                !targetPlayer.getUUID().equals(interactor.getUUID()) &&
                interactor.distanceTo(targetPlayer) <= Config.reviveDistance)
            {
                int reviveTicks = Config.reviveTime * 20;
                revivingPlayers.put(targetPlayer.getUUID(), new ReviveData(interactor.getUUID(), reviveTicks));
                
                com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                    new com.covalscy.bleedout.network.ReviveUpdatePacket(true, reviveTicks),
                    interactor.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
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
            
            if (bleedingPlayers.containsKey(playerId))
            {
                killBleedingPlayer(player);
            }
            
            if (revivingPlayers.containsKey(playerId))
            {
                stopRevive(player, false, false);
            }
        }
    }

    private static void stopRevive(ServerPlayer player, boolean notifyReviver, boolean notifyTarget)
    {
        if (player == null) return;
        UUID playerId = player.getUUID();
        if (revivingPlayers.containsKey(playerId))
        {
            ReviveData reviveData = revivingPlayers.remove(playerId);
            
            player.getPersistentData().remove("bleedout:reviving");
            player.getPersistentData().remove("bleedout:reviveTicks");
            
            if (notifyTarget)
            {
                com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                    new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                    player.connection.connection,
                    net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                );
            }
            
            ServerPlayer reviver = getServerPlayer(reviveData.reviverId);
            if (reviver != null)
            {
                reviver.getPersistentData().remove("bleedout:reviving");
                reviver.getPersistentData().remove("bleedout:reviveTicks");
                
                if (notifyReviver)
                {
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendTo(
                        new com.covalscy.bleedout.network.ReviveUpdatePacket(false, 0),
                        reviver.connection.connection,
                        net.minecraftforge.network.NetworkDirection.PLAY_TO_CLIENT
                    );
                }
            }
        }
    }

    private static ServerPlayer getServerPlayer(UUID playerId)
    {
        for (ServerLevel level : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getAllLevels())
        {
            ServerPlayer player = level.getPlayerByUUID(playerId);
            if (player != null)
                return player;
        }
        return null;
    }

    private static void applyConfiguredBleedingEffects(ServerPlayer player)
    {
        List<? extends String> configuredEffects = Config.bleedingEffects;
        if (configuredEffects == null || configuredEffects.isEmpty())
            return;

        List<BleedingEffectData> effectDataList = parseBleedingEffects(configuredEffects);
        for (BleedingEffectData data : effectDataList)
        {
            player.addEffect(new MobEffectInstance(
                data.effect,
                BLEEDING_EFFECT_DURATION_TICKS,
                data.amplifier,
                false,
                false
            ));
        }
    }

    private static List<BleedingEffectData> parseBleedingEffects(List<? extends String> configuredEffects)
    {
        List<BleedingEffectData> list = new ArrayList<>();
        for (String rawValue : configuredEffects)
        {
            if (rawValue == null || rawValue.isBlank())
                continue;

            String trimmed = rawValue.trim();
            String idPart = trimmed;
            int amplifier = 0;

            if (trimmed.contains(":"))
            {
                String[] parts = trimmed.split(":");
                if (parts.length >= 2)
                {
                    idPart = parts[0].trim();
                    try
                    {
                        amplifier = Math.max(0, Integer.parseInt(parts[1].trim()));
                    }
                    catch (NumberFormatException ignored) {}
                }
            }

            ResourceLocation effectId = ResourceLocation.tryParse(idPart);
            if (effectId == null)
                continue;

            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(effectId);
            if (effect != null)
            {
                list.add(new BleedingEffectData(effect, amplifier));
            }
        }
        return list;
    }

    private static void sendKillInfoToTabMod(ServerPlayer victim, DamageSource source)
    {
        if (victim == null) return;
        if (!ModList.get().isLoaded("customtab")) return;

        UUID victimId = victim.getUUID();
        UUID killerId = bleedingDowners.get(victimId);
        
        UUID finisherId = null;
        if (source != null)
        {
            if (source.getEntity() instanceof ServerPlayer sAttacker)
            {
                finisherId = sAttacker.getUUID();
            }
            else if (source.getDirectEntity() instanceof ServerPlayer dAttacker)
            {
                finisherId = dAttacker.getUUID();
            }
        }

        UUID assistId = null;

        if (killerId == null && finisherId != null)
        {
            killerId = finisherId;
            finisherId = null;
            
            ServerPlayer killerPlayer = victim.server.getPlayerList().getPlayer(killerId);
            addPointsToMinimap(killerPlayer, MINIMAP_POINTS_FINISH, "instant_kill");
        }
        else if (killerId != null && finisherId != null)
        {
            assistId = finisherId;
        }

        if (killerId == null && assistId == null)
        {
            return;
        }
        
        try
        {
            net.minecraft.server.MinecraftServer server = victim.server;
            Class<?> handlerClass = Class.forName("com.covalscy.customtab.server.BleedOutKillHandler");
            java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleKill", 
                net.minecraft.server.MinecraftServer.class, UUID.class, UUID.class, UUID.class);
            handleMethod.invoke(null, server, victimId, killerId, assistId);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending kill info to TAB-mod: " + e.getMessage());
        }
    }

    private static void sendReviveAssistToTabMod(ServerPlayer revived, ServerPlayer reviver)
    {
        if (revived == null || reviver == null) return;
        if (!ModList.get().isLoaded("customtab")) return;

        UUID revivedId = revived.getUUID();
        UUID reviverId = reviver.getUUID();

        if (revivedId.equals(reviverId)) return;

        try
        {
            net.minecraft.server.MinecraftServer server = revived.server;
            Class<?> handlerClass = Class.forName("com.covalscy.customtab.server.BleedOutKillHandler");
            java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleReviveAssist", 
                net.minecraft.server.MinecraftServer.class, UUID.class, UUID.class);
            handleMethod.invoke(null, server, revivedId, reviverId);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending revive assist to TAB-mod: " + e.getMessage());
        }
    }

    private static void addPointsToMinimap(ServerPlayer player, int points, String reason)
    {
        if (player == null || points <= 0) return;
        if (!ModList.get().isLoaded(MINIMAP_MODID)) return;

        try
        {
            Class<?> trackerClass = Class.forName("xaero.common.minimap.highlight.DamageTracker");
            java.lang.reflect.Method addPointsMethod = trackerClass.getMethod("addPoints", Player.class, int.class);
            addPointsMethod.invoke(null, player, points);
        }
        catch (Exception e)
        {
            LOGGER.error("[BleedOut] Error sending points to MinimapMod (reason=" + reason + "): " + e.getMessage());
        }
    }

    public static boolean isBleeding(Player player)
    {
        if (player == null) return false;
        return bleedingPlayers.containsKey(player.getUUID()) || player.getPersistentData().getBoolean("bleedout:bleeding");
    }

    private static void revivePlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        stopRevive(player, false, true);
        
        bleedingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        BloodyBitsCompat.clearBleedingPlayer(player);
        
        com.covalscy.bleedout.network.BleedOutNetwork.sendToTrackingAndSelf(
            new com.covalscy.bleedout.network.BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        player.setHealth(player.getMaxHealth() * 0.5f);
        
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
    }

    public static int getReviveTimeLeft(Player player)
    {
        UUID playerId = player.getUUID();
        if (revivingPlayers.containsKey(playerId))
        {
            return revivingPlayers.get(playerId).ticksLeft / 20;
        }
        return 0;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        
        dispatcher.register(
            Commands.literal("revive")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    CommandSourceStack source = context.getSource();
                    if (source.getEntity() instanceof ServerPlayer player)
                    {
                        if (isBleeding(player))
                        {
                            revivePlayer(player);
                            source.sendSuccess(() -> Component.translatable("bleedout.command.revive.success.self"), true);
                            return 1;
                        }
                        else
                        {
                            source.sendFailure(Component.translatable("bleedout.command.revive.failure.self"));
                            return 0;
                        }
                    }
                    return 0;
                })
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        ServerPlayer target = EntityArgument.getPlayer(context, "target");
                        if (isBleeding(target))
                        {
                            revivePlayer(target);
                            source.sendSuccess(() -> Component.translatable("bleedout.command.revive.success.other", target.getName().getString()), true);
                            target.sendSystemMessage(Component.translatable("bleedout.command.revive.success.other.notification"));
                            return 1;
                        }
                        else
                        {
                            source.sendFailure(Component.translatable("bleedout.command.revive.failure.other", target.getName().getString()));
                            return 0;
                        }
                    })
                )
        );
    }
}
