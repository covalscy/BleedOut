package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.BleedOutUpdatePacket;
import com.covalscy.bleedout.network.ReviveUpdatePacket;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.GAME)
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

    private record BleedingEffectData(Holder<MobEffect> effect, int amplifier)
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
    public static void onLivingHurt(LivingIncomingDamageEvent event)
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

        if (!(event.getEntity() instanceof Mob mob) || mob.level().isClientSide)
        {
            return;
        }

        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && isBleeding(player))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBleedingPlayerHurtByMob(LivingIncomingDamageEvent event)
    {
        if (!Config.bleedingPlayersIgnoredByMobs)
        {
            return;
        }

        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide)
        {
            return;
        }

        if (!isBleeding(player) || !(event.getSource().getEntity() instanceof Mob mob))
        {
            return;
        }

        mob.setTarget(null);
        mob.setLastHurtByMob(null);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onVehicleDeath(LivingDeathEvent event)
    {
        if (!event.getEntity().level().isClientSide && !(event.getEntity() instanceof ServerPlayer))
        {
            net.minecraft.world.entity.Entity entity = event.getEntity();
            if (isSuperbWarfareVehicle(entity))
            {
                if (event.getSource().getEntity() instanceof ServerPlayer killer)
                {
                    addPointsToMinimap(killer, MINIMAP_POINTS_VEHICLE_DESTROY, "destroy_vehicle");
                }
            }
        }
    }

    private static boolean isSuperbWarfareVehicle(net.minecraft.world.entity.Entity entity)
    {
        Class<?> clazz = entity.getClass();
        while (clazz != null && clazz != Object.class)
        {
            String name = clazz.getName();
            if (name.equals("com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity") ||
                name.equals("com.atsuishio.superbwarfare.entity.vehicle.base.ArtilleryEntity") ||
                name.equals("com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity"))
            {
                return true;
            }
            clazz = clazz.getSuperclass();
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDeath(LivingDeathEvent event)
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
                            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), reviver);
                        }
                    }

                    bleedingPlayers.remove(playerId);
                    revivingPlayers.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    dyingPlayers.remove(playerId);
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
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
                            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), reviver);
                        }
                    }
                    
                    bleedingPlayers.remove(playerId);
                    revivingPlayers.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    dyingPlayers.remove(playerId);
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
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
                        BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), reviver);
                    }

                    BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), player);
                }
                
                sendKillInfoToTabMod(player, source);
                
                bleedingPlayers.remove(playerId);
                revivingPlayers.remove(playerId);
                poseSetForPlayers.remove(playerId);
                dyingPlayers.remove(playerId);
                bleedingDowners.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                return;
            }
            
            if (player.isPassenger())
                player.stopRiding();
            
            startBleedout(player, source);
            
            event.setCanceled(true);
            
            player.setHealth(Config.bleedingHealth);
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event)
    {
        if (event.getEntity().level().isClientSide)
            return;

        if (event.getEntity() instanceof ServerPlayer player)
        {
            UUID playerId = player.getUUID();
            
            if (bleedingPlayers.containsKey(playerId))
            {
                int ticksLeft = bleedingPlayers.get(playerId);
                
                if (Config.enableBleedoutTimer)
                {
                    ticksLeft--;
                }
                
                if (ticksLeft <= 0)
                {
                    if (revivingPlayers.containsKey(playerId))
                    {
                        stopRevive(player, true, true);
                    }
                    
                    dyingPlayers.put(playerId, true);
                    
                    bleedingPlayers.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
                    
                    BleedOutNetwork.sendToTrackingAndSelf(
                        new BleedOutUpdatePacket(playerId, false, 0),
                        player
                    );
                    
                    sendKillInfoToTabMod(player, player.damageSources().genericKill());
                    
                    bleedingDowners.remove(playerId);
                    
                    player.setForcedPose(null);
                    
                    player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
                    
                    dyingPlayers.remove(playerId);
                }
                else
                {
                    bleedingPlayers.put(playerId, ticksLeft);

                    if (player.tickCount % 20 == 0)
                    {
                        applyBleedingEffects(player);
                    }
                    
                    Pose currentPose = player.getPose();
                    if (currentPose != Pose.SWIMMING)
                    {
                        player.setForcedPose(Pose.SWIMMING);
                        player.setPose(Pose.SWIMMING);
                        player.refreshDimensions();
                        poseSetForPlayers.put(playerId, true);
                    }
                    
                    var deltaMovement = player.getDeltaMovement();

                    if (deltaMovement.y > 0)
                    {
                        player.setDeltaMovement(deltaMovement.x, 0, deltaMovement.z);
                        deltaMovement = player.getDeltaMovement();
                    }

                    if (Config.disableMovementWhileBleeding)
                    {
                        player.setDeltaMovement(
                            0,
                            Math.min(deltaMovement.y, 0.1),
                            0
                        );
                    }
                    else
                    {
                        double maxSpeed = 0.01;
                        double horizontalSpeed = Math.sqrt(deltaMovement.x * deltaMovement.x + deltaMovement.z * deltaMovement.z);
                        if (horizontalSpeed > maxSpeed)
                        {
                            double scale = maxSpeed / horizontalSpeed;
                            player.setDeltaMovement(
                                deltaMovement.x * scale,
                                Math.min(deltaMovement.y, 0.1),
                                deltaMovement.z * scale
                            );
                        }
                        else if (horizontalSpeed > 0 && horizontalSpeed < maxSpeed)
                        {
                            player.setDeltaMovement(
                                deltaMovement.x,
                                Math.min(deltaMovement.y, 0.1),
                                deltaMovement.z
                            );
                        }
                    }
                    
                    player.setJumping(false);
                }
            }

            if (revivingPlayers.containsKey(playerId))
            {
                ReviveData reviveData = revivingPlayers.get(playerId);
                ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                
                if (reviver == null || !reviver.isAlive() || 
                    reviver.distanceTo(player) > Config.reviveDistance || 
                    !isBleeding(player))
                {
                    stopRevive(player, true, true);
                    return;
                }
                
                reviveData.ticksLeft--;
                
                if (reviveData.ticksLeft <= 0)
                {
                    stopRevive(player, true, true);

                    sendReviveAssistToTabMod(player, reviver);

                    addPointsToMinimap(reviver, MINIMAP_POINTS_REVIVE_ASSIST, "revive_assist");
                    
                    revivePlayer(player);
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
            if (isBleeding(targetPlayer) && 
                !revivingPlayers.containsKey(targetPlayer.getUUID()) &&
                !targetPlayer.getUUID().equals(interactor.getUUID()) &&
                interactor.distanceTo(targetPlayer) <= Config.reviveDistance)
            {
                int reviveTicks = Config.reviveTime * 20;
                revivingPlayers.put(targetPlayer.getUUID(), new ReviveData(interactor.getUUID(), reviveTicks));
                
                sendReviveState(interactor, true, reviveTicks);
                sendReviveState(targetPlayer, true, reviveTicks);
                
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
        }
    }

    private static void startBleedout(ServerPlayer player, DamageSource source)
    {
        UUID playerId = player.getUUID();
        
        if (bleedingPlayers.containsKey(playerId))
        {
            return;
        }
        
        UUID downerId = null;
        if (source.getEntity() instanceof ServerPlayer downer) {
            if (!downer.getUUID().equals(playerId)) {
                downerId = downer.getUUID();
                bleedingDowners.put(playerId, downerId);
            }
        }
        else if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            if (projectile.getOwner() instanceof ServerPlayer downer) {
                if (!downer.getUUID().equals(playerId)) {
                    downerId = downer.getUUID();
                    bleedingDowners.put(playerId, downerId);
                }
            }
        }

        if (downerId != null)
        {
            ServerPlayer downerPlayer = player.server.getPlayerList().getPlayer(downerId);
            addPointsToMinimap(downerPlayer, MINIMAP_POINTS_KNOCK, "knock");
        }
        
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false);
        
        player.setForcedPose(Pose.SWIMMING);
        player.setPose(Pose.SWIMMING);
        player.refreshDimensions();
        poseSetForPlayers.put(playerId, true);
        
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        
        int initialTicks = bleedingPlayers.get(playerId);
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, true, initialTicks),
            player
        );
        
        player.setJumping(false);
        applyBleedingEffects(player);
    }

    private static void applyBleedingEffects(ServerPlayer player)
    {
        for (BleedingEffectData effectData : getConfiguredBleedingEffects())
        {
            player.addEffect(new MobEffectInstance(
                effectData.effect(),
                BLEEDING_EFFECT_DURATION_TICKS,
                effectData.amplifier(),
                false,
                false,
                true
            ));
        }
    }

    private static void sendReviveState(ServerPlayer player, boolean isReviving, int ticksLeft)
    {
        if (player == null)
        {
            return;
        }

        BleedOutNetwork.sendReviveUpdateToClient(
            new ReviveUpdatePacket(isReviving, ticksLeft),
            player
        );
    }

    private static void stopRevive(ServerPlayer targetPlayer, boolean notifyReviver, boolean notifyTarget)
    {
        if (targetPlayer == null)
        {
            return;
        }

        UUID targetId = targetPlayer.getUUID();
        ReviveData reviveData = revivingPlayers.remove(targetId);
        if (reviveData == null)
        {
            return;
        }

        ServerPlayer reviver = targetPlayer.server.getPlayerList().getPlayer(reviveData.reviverId);
        if (notifyReviver && reviver != null)
        {
            sendReviveState(reviver, false, 0);
        }

        if (notifyTarget)
        {
            sendReviveState(targetPlayer, false, 0);
        }
    }

    private static List<BleedingEffectData> getConfiguredBleedingEffects()
    {
        List<BleedingEffectData> effects = new ArrayList<>();
        for (String rawEntry : Config.bleedingEffects)
        {
            BleedingEffectData effectData = parseBleedingEffect(rawEntry);
            if (effectData != null)
            {
                effects.add(effectData);
            }
        }
        return effects;
    }

    private static BleedingEffectData parseBleedingEffect(String rawEntry)
    {
        if (rawEntry == null)
        {
            return null;
        }

        String entry = rawEntry.trim();
        if (entry.isEmpty())
        {
            return null;
        }

        int amplifier = 0;
        String effectId = entry;
        int lastColon = entry.lastIndexOf(':');
        if (lastColon > 0)
        {
            String amplifierPart = entry.substring(lastColon + 1).trim();
            String possibleEffectId = entry.substring(0, lastColon).trim();
            try
            {
                amplifier = Integer.parseInt(amplifierPart);
                effectId = possibleEffectId;
            }
            catch (NumberFormatException ignored)
            {
                effectId = entry;
            }
        }

        ResourceLocation resourceLocation = ResourceLocation.tryParse(effectId);
        if (resourceLocation == null)
        {
            LOGGER.warn("[BleedOut] Invalid bleeding effect config entry: {}", rawEntry);
            return null;
        }

        Holder<MobEffect> effectHolder = BuiltInRegistries.MOB_EFFECT.getHolder(resourceLocation).orElse(null);
        if (effectHolder == null)
        {
            LOGGER.warn("[BleedOut] Unknown bleeding effect in config: {}", rawEntry);
            return null;
        }

        return new BleedingEffectData(effectHolder, Math.max(0, amplifier));
    }

    public static boolean isBleeding(Player player)
    {
        return bleedingPlayers.containsKey(player.getUUID());
    }
    
    public static int getBleedoutTimeLeft(Player player)
    {
        return bleedingPlayers.getOrDefault(player.getUUID(), 0);
    }
    
    public static boolean isBeingRevived(Player player)
    {
        return revivingPlayers.containsKey(player.getUUID());
    }
    
    public static void instantKill(ServerPlayer player)
    {
        if (player.isPassenger())
            player.stopRiding();

        UUID playerId = player.getUUID();
        
        if (bleedingPlayers.containsKey(playerId))
        {
            killBleedingPlayer(player);
        }
        else
        {
            dyingPlayers.put(playerId, true);
            player.setForcedPose(null);
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        }
    }

    public static void killBleedingPlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        if (bleedingPlayers.containsKey(playerId))
        {
            dyingPlayers.put(playerId, true);
            
            bleedingPlayers.remove(playerId);
            revivingPlayers.remove(playerId);
            poseSetForPlayers.remove(playerId);
            player.getPersistentData().remove("bleedout:bleeding");
            
            BleedOutNetwork.sendToTrackingAndSelf(
                new BleedOutUpdatePacket(playerId, false, 0),
                player
            );
            
            sendKillInfoToTabMod(player, player.damageSources().genericKill());
            
            bleedingDowners.remove(playerId);
            
            player.setForcedPose(null);
            
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
            
            dyingPlayers.remove(playerId);
        }
    }
    
    private static void sendKillInfoToTabMod(ServerPlayer victim, DamageSource source)
    {
        if (!ModList.get().isLoaded("customtab"))
        {
            return;
        }
        
        UUID victimId = victim.getUUID();
        UUID killerId = bleedingDowners.get(victimId);
        
        UUID finisherId = null;
        String msgId = source.getMsgId();
        
        if (!msgId.equals("genericKill") && !msgId.equals("generic"))
        {
            if (source.getEntity() instanceof ServerPlayer finisher)
            {
                if (!finisher.getUUID().equals(victimId))
                {
                    finisherId = finisher.getUUID();
                }
            }
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

        if (finisherId != null && bleedingDowners.containsKey(victimId))
        {
            ServerPlayer finisherPlayer = victim.server.getPlayerList().getPlayer(finisherId);
            addPointsToMinimap(finisherPlayer, MINIMAP_POINTS_FINISH, "finish_knocked");
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
        if (revived == null || reviver == null)
        {
            return;
        }

        if (!ModList.get().isLoaded("customtab"))
        {
            return;
        }

        UUID revivedId = revived.getUUID();
        UUID reviverId = reviver.getUUID();

        if (revivedId.equals(reviverId))
        {
            return;
        }

        try
        {
            net.minecraft.server.MinecraftServer server = revived.server;
            Class<?> handlerClass = Class.forName("com.covalscy.customtab.server.BleedOutKillHandler");
            java.lang.reflect.Method handleMethod = handlerClass.getMethod("handleRevive",
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
        if (player == null || points <= 0)
        {
            return;
        }

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
        }
    }

    private static void revivePlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        stopRevive(player, false, true);
        
        bleedingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        player.setHealth(player.getMaxHealth() * 0.5f);
        
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
        player.refreshDimensions();
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
                    else
                    {
                        source.sendFailure(Component.literal("Only players can execute this command without arguments."));
                        return 0;
                    }
                })
                .then(Commands.argument("target", EntityArgument.player())
                    .executes(context -> {
                        CommandSourceStack source = context.getSource();
                        ServerPlayer target = EntityArgument.getPlayer(context, "target");
                        if (isBleeding(target))
                        {
                            revivePlayer(target);
                            source.sendSuccess(() -> Component.translatable("bleedout.command.revive.success.other", target.getDisplayName()), true);
                            target.sendSystemMessage(Component.translatable("bleedout.command.revive.success.other.notification"));
                            return 1;
                        }
                        else
                        {
                            source.sendFailure(Component.translatable("bleedout.command.revive.failure.other", target.getDisplayName()));
                            return 0;
                        }
                    })
                )
        );
    }
}
