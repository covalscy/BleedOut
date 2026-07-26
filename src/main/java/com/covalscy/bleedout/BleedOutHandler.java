package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.BleedOutUpdatePacket;
import com.covalscy.bleedout.network.ReviveUpdatePacket;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class BleedOutHandler
{
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();
    private static final Map<UUID, ReviveData> revivingPlayers = new HashMap<>();
    private static final Set<UUID> dyingPlayers = new HashSet<>();
    private static final Map<UUID, Boolean> poseSetForPlayers = new HashMap<>();
    private static final Map<UUID, UUID> bleedingDowners = new HashMap<>();
    
    private static final Map<UUID, Integer> gracePeriodTicks = new HashMap<>();
    
    private static final String MINIMAP_MODID = "xaerominimap";
    private static final int MINIMAP_POINTS_DOWN = 5;
    private static final int MINIMAP_POINTS_FINISH = 5;
    private static final int MINIMAP_POINTS_REVIVE = 10;
    
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

    public static boolean isBleeding(Player player)
    {
        if (player == null) return false;
        return bleedingPlayers.containsKey(player.getUUID()) || player.getPersistentData().getBoolean("bleedout:bleeding");
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingIncomingDamageEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide)
        {
            return;
        }

        DamageSource source = event.getSource();
        UUID playerId = player.getUUID();
        
        if (doesByPass(player, source))
        {
            return;
        }

        if (dyingPlayers.contains(playerId))
        {
            return;
        }

        float currentHealth = player.getHealth();
        float damageAmount = event.getAmount();

        if (bleedingPlayers.containsKey(playerId))
        {
            if (gracePeriodTicks.containsKey(playerId) && gracePeriodTicks.get(playerId) > 0)
            {
                event.setCanceled(true);
                return;
            }
            return;
        }

        if (currentHealth - damageAmount <= 0)
        {
            event.setCanceled(true);
            
            player.setHealth(1.0f);
            
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

    @SubscribeEvent
    public static void onLivingChangeTarget(LivingChangeTargetEvent event)
    {
        if (!Config.bleedingPlayersIgnoredByMobs)
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

        if (event.getEntity() instanceof ServerPlayer player && isBleeding(player))
        {
            Entity attacker = event.getSource().getEntity();
            Entity directAttacker = event.getSource().getDirectEntity();

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
            Entity entity = event.getEntity();
            if (isSuperbWarfareVehicle(entity))
            {
                Entity killer = event.getSource().getEntity();
                if (killer instanceof ServerPlayer killerPlayer)
                {
                    addPointsToMinimap(killerPlayer, MINIMAP_POINTS_FINISH, "vehicle_destruction");
                }
            }
        }
    }

    private static boolean isSuperbWarfareVehicle(Entity entity)
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

                if (bleedingPlayers.containsKey(playerId) || dyingPlayers.contains(playerId))
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
                
                if (bleedingPlayers.containsKey(playerId) || dyingPlayers.contains(playerId))
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
            
            if (bleedingPlayers.containsKey(playerId) || dyingPlayers.contains(playerId))
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
                return;
            }
            
            event.setCanceled(true);
            player.setHealth(1.0f);
            
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

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            UUID playerId = player.getUUID();
            
            if (gracePeriodTicks.containsKey(playerId))
            {
                int ticks = gracePeriodTicks.get(playerId);
                if (ticks > 1)
                {
                    gracePeriodTicks.put(playerId, ticks - 1);
                }
                else
                {
                    gracePeriodTicks.remove(playerId);
                }
            }

            if (bleedingPlayers.containsKey(playerId))
            {
                int ticksLeft = bleedingPlayers.get(playerId) - 1;
                
                if (ticksLeft <= 0)
                {
                    killBleedingPlayer(player);
                }
                else
                {
                    bleedingPlayers.put(playerId, ticksLeft);

                    if (player.tickCount % 20 == 0)
                    {
                        applyBleedingEffects(player);
                    }
                    
                    if (player.getForcedPose() != Pose.SWIMMING)
                    {
                        player.setForcedPose(Pose.SWIMMING);
                    }
                    
                    player.setJumping(false);
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
                        Vec3 horizMovement = new Vec3(deltaMovement.x, 0, deltaMovement.z);

                        if (horizMovement.length() > maxSpeed)
                        {
                            horizMovement = horizMovement.normalize().scale(maxSpeed);
                            player.setDeltaMovement(horizMovement.x, Math.min(deltaMovement.y, 0.1), horizMovement.z);
                        }
                    }

                    player.getPersistentData().putInt("bleedout:ticksLeft", ticksLeft);
                    
                    if (player.tickCount % 5 == 0)
                    {
                        BleedOutNetwork.sendToTrackingAndSelf(
                            new BleedOutUpdatePacket(playerId, true, ticksLeft),
                            player
                        );
                    }
                }
            }
            
            if (revivingPlayers.containsKey(playerId))
            {
                ReviveData reviveData = revivingPlayers.get(playerId);
                reviveData.ticksLeft--;
                
                ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                
                boolean canContinueRevive = reviver != null && 
                                           !reviver.isDeadOrDying() && 
                                           !isBleeding(reviver) && 
                                           reviver.distanceTo(player) <= (Config.reviveDistance + 1.0);
                
                if (!canContinueRevive || reviveData.ticksLeft <= 0)
                {
                    if (reviveData.ticksLeft <= 0 && canContinueRevive)
                    {
                        revivePlayer(player);
                        sendReviveAssistToTabMod(player, reviver);
                        addPointsToMinimap(reviver, MINIMAP_POINTS_REVIVE, "revive");
                    }
                    else
                    {
                        stopRevive(player, false, false);
                    }
                }
                else
                {
                    player.getPersistentData().putBoolean("bleedout:reviving", true);
                    player.getPersistentData().putInt("bleedout:reviveTicks", reviveData.ticksLeft);
                    
                    if (reviver != null)
                    {
                        reviver.getPersistentData().putBoolean("bleedout:reviving", true);
                        reviver.getPersistentData().putInt("bleedout:reviveTicks", reviveData.ticksLeft);
                        
                        if (player.tickCount % 5 == 0)
                        {
                            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveData.ticksLeft), reviver);
                            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveData.ticksLeft), player);
                        }
                    }
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
                
                BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveTicks), interactor);
                BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveTicks), targetPlayer);
                
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

    private static void applyBleedingEffects(ServerPlayer player)
    {
        player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 40, 0, false, false));
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, false));
    }

    private static void startBleedout(ServerPlayer player, ServerPlayer attacker)
    {
        UUID playerId = player.getUUID();
        
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false);
        
        player.setForcedPose(Pose.SWIMMING);
        poseSetForPlayers.put(playerId, true);
        
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        player.getPersistentData().putInt("bleedout:ticksLeft", bleedoutTicks);
        
        gracePeriodTicks.put(playerId, 10);

        if (attacker != null)
        {
            bleedingDowners.put(playerId, attacker.getUUID());
            addPointsToMinimap(attacker, MINIMAP_POINTS_DOWN, "knockdown");
        }
        else
        {
            bleedingDowners.remove(playerId);
        }
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, true, bleedoutTicks),
            player
        );
        
        applyBleedingEffects(player);
    }

    public static void killBleedingPlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        
        dyingPlayers.add(playerId);
        
        stopRevive(player, true, true);
        
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

    private static void stopRevive(ServerPlayer player, boolean notifyReviver, boolean notifyTarget)
    {
        UUID playerId = player.getUUID();
        if (revivingPlayers.containsKey(playerId))
        {
            ReviveData reviveData = revivingPlayers.remove(playerId);
            
            player.getPersistentData().remove("bleedout:reviving");
            player.getPersistentData().remove("bleedout:reviveTicks");
            
            if (notifyTarget)
            {
                BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), player);
            }
            
            ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
            if (reviver != null)
            {
                reviver.getPersistentData().remove("bleedout:reviving");
                reviver.getPersistentData().remove("bleedout:reviveTicks");
                
                if (notifyReviver)
                {
                    BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), reviver);
                }
            }
        }
    }

    private static boolean doesByPass(ServerPlayer player, DamageSource source)
    {
        if (source == null) return false;
        
        if (source.is(DamageTypes.FELL_OUT_OF_WORLD)) return true;
        if (source.is(DamageTypes.GENERIC_KILL)) return true;
        
        return false;
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
        var dispatcher = event.getDispatcher();
        
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
