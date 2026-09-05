package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.BleedOutUpdatePacket;
import com.covalscy.bleedout.network.ReviveUpdatePacket;
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
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
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
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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

        UUID playerId = player.getUUID();
        
        if (dyingPlayers.contains(playerId) || player.isDeadOrDying() || player.isRemoved())
        {
            return;
        }

        DamageSource source = event.getSource();
        if (doesByPass(player, source))
        {
            return;
        }

        if (bleedingPlayers.containsKey(playerId))
        {
            if (gracePeriodTicks.containsKey(playerId) && gracePeriodTicks.get(playerId) > 0)
            {
                event.setCanceled(true);
                return;
            }
            // After grace period, let damage through so the knocked player can be finished
            return;
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerDeath(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            DamageSource source = event.getSource();
            UUID playerId = player.getUUID();

            if (dyingPlayers.contains(playerId))
            {
                // Already in death pipeline, let vanilla death proceed
                return;
            }

            if (player.isPassenger())
            {
                player.stopRiding();

                if (bleedingPlayers.containsKey(playerId))
                {
                    dyingPlayers.add(playerId);
                    stopRevive(player, true, true);

                    bleedingPlayers.remove(playerId);
                    gracePeriodTicks.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
                    player.setForcedPose(null);
                    player.setPose(Pose.STANDING);
                    player.refreshDimensions();

                    BleedOutNetwork.sendToTrackingAndSelf(
                        new BleedOutUpdatePacket(playerId, false, 0),
                        player
                    );
                }
                return;
            }
            
            if (doesByPass(player, source))
            {
                if (player.isPassenger())
                    player.stopRiding();
                
                if (bleedingPlayers.containsKey(playerId))
                {
                    dyingPlayers.add(playerId);
                    stopRevive(player, true, true);

                    bleedingPlayers.remove(playerId);
                    gracePeriodTicks.remove(playerId);
                    poseSetForPlayers.remove(playerId);
                    bleedingDowners.remove(playerId);
                    player.getPersistentData().remove("bleedout:bleeding");
                    player.setForcedPose(null);
                    player.setPose(Pose.STANDING);
                    player.refreshDimensions();

                    BleedOutNetwork.sendToTrackingAndSelf(
                        new BleedOutUpdatePacket(playerId, false, 0),
                        player
                    );
                }
                return;
            }
            
            if (bleedingPlayers.containsKey(playerId))
            {
                // Knocked player is being finished
                dyingPlayers.add(playerId);
                stopRevive(player, true, true);
                
                bleedingPlayers.remove(playerId);
                gracePeriodTicks.remove(playerId);
                poseSetForPlayers.remove(playerId);
                bleedingDowners.remove(playerId);
                player.getPersistentData().remove("bleedout:bleeding");
                player.setForcedPose(null);
                player.setPose(Pose.STANDING);
                player.refreshDimensions();

                BleedOutNetwork.sendToTrackingAndSelf(
                    new BleedOutUpdatePacket(playerId, false, 0),
                    player
                );
                
                // Do NOT cancel event! Player actually dies.
                return;
            }
            
            // Healthy player taking lethal damage -> ENTER BLEEDOUT
            event.setCanceled(true);
            player.setHealth(Math.max(1.0f, (float) Config.bleedingHealth));
            
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
                int currentTicks = bleedingPlayers.get(playerId);
                int ticksLeft = Config.enableBleedoutTimer ? (currentTicks - 1) : currentTicks;
                
                if (Config.enableBleedoutTimer && ticksLeft <= 0)
                {
                    killBleedingPlayer(player);
                }
                else
                {
                    if (Config.enableBleedoutTimer)
                    {
                        bleedingPlayers.put(playerId, ticksLeft);
                    }

                    if (player.tickCount % 20 == 0)
                    {
                        applyConfiguredBleedingEffects(player);
                    }
                    
                    if (player.getPose() != Pose.SWIMMING || player.getForcedPose() != Pose.SWIMMING)
                    {
                        player.setForcedPose(Pose.SWIMMING);
                        player.setPose(Pose.SWIMMING);
                        player.refreshDimensions();
                        poseSetForPlayers.put(playerId, true);
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerAttackEntity(AttackEntityEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && isBleeding(player))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerInteractItem(PlayerInteractEvent.RightClickItem event)
    {
        if (event.getEntity() instanceof ServerPlayer player && isBleeding(player))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide)
        {
            UUID playerId = player.getUUID();
            dyingPlayers.remove(playerId);
            bleedingPlayers.remove(playerId);
            gracePeriodTicks.remove(playerId);
            revivingPlayers.remove(playerId);
            poseSetForPlayers.remove(playerId);
            bleedingDowners.remove(playerId);
            
            player.getPersistentData().remove("bleedout:bleeding");
            player.getPersistentData().remove("bleedout:ticksLeft");
            player.getPersistentData().remove("bleedout:reviving");
            player.getPersistentData().remove("bleedout:reviveTicks");
            
            player.setForcedPose(null);
            player.setPose(Pose.STANDING);
            player.refreshDimensions();
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
            
            dyingPlayers.remove(playerId);
            gracePeriodTicks.remove(playerId);
        }
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
                40,
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
                if (parts.length >= 3)
                {
                    idPart = parts[0].trim() + ":" + parts[1].trim();
                    try
                    {
                        amplifier = Math.max(0, Integer.parseInt(parts[2].trim()));
                    }
                    catch (NumberFormatException ignored) {}
                }
                else if (parts.length == 2)
                {
                    if (parts[0].contains(":") || parts[0].equals("minecraft") || ResourceLocation.tryParse(trimmed) != null)
                    {
                        idPart = trimmed;
                        amplifier = 0;
                    }
                    else
                    {
                        idPart = parts[0].trim();
                        try
                        {
                            amplifier = Math.max(0, Integer.parseInt(parts[1].trim()));
                        }
                        catch (NumberFormatException ignored) {}
                    }
                }
            }

            ResourceLocation effectId = ResourceLocation.tryParse(idPart);
            if (effectId == null)
                continue;

            var holderOpt = BuiltInRegistries.MOB_EFFECT.getHolder(effectId);
            if (holderOpt.isPresent())
            {
                list.add(new BleedingEffectData(holderOpt.get(), amplifier));
            }
        }
        return list;
    }

    private static void startBleedout(ServerPlayer player, ServerPlayer attacker)
    {
        UUID playerId = player.getUUID();
        
        int bleedoutTicks = Config.bleedoutTime * 20;
        bleedingPlayers.put(playerId, bleedoutTicks);
        poseSetForPlayers.put(playerId, false);
        
        player.setForcedPose(Pose.SWIMMING);
        player.setPose(Pose.SWIMMING);
        player.refreshDimensions();
        poseSetForPlayers.put(playerId, true);
        
        player.getPersistentData().putBoolean("bleedout:bleeding", true);
        player.getPersistentData().putInt("bleedout:ticksLeft", bleedoutTicks);
        player.stopUsingItem();
        
        int cooldown = Math.max(0, Config.initialDamageCooldown);
        if (cooldown > 0)
        {
            gracePeriodTicks.put(playerId, cooldown);
        }
        else
        {
            gracePeriodTicks.remove(playerId);
        }

        if (attacker != null)
        {
            bleedingDowners.put(playerId, attacker.getUUID());
        }
        else
        {
            bleedingDowners.remove(playerId);
        }
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, true, bleedoutTicks),
            player
        );
        
        applyConfiguredBleedingEffects(player);
    }

    public static void killBleedingPlayer(ServerPlayer player)
    {
        if (player == null) return;
        UUID playerId = player.getUUID();
        
        dyingPlayers.add(playerId);
        
        stopRevive(player, true, true);
        
        bleedingPlayers.remove(playerId);
        gracePeriodTicks.remove(playerId);
        poseSetForPlayers.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        player.getPersistentData().remove("bleedout:ticksLeft");
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        UUID downerId = bleedingDowners.remove(playerId);
        
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
        player.refreshDimensions();
        
        boolean killedByDowner = false;
        if (downerId != null && player.server != null)
        {
            ServerPlayer downer = player.server.getPlayerList().getPlayer(downerId);
            if (downer != null && !downer.isRemoved())
            {
                try
                {
                    player.hurt(player.damageSources().playerAttack(downer), Float.MAX_VALUE);
                    killedByDowner = player.isDeadOrDying();
                }
                catch (Exception ignored) {}
            }
        }
        
        if (!killedByDowner && !player.isDeadOrDying())
        {
            player.hurt(player.damageSources().genericKill(), Float.MAX_VALUE);
        }
        
        if (!player.isDeadOrDying())
        {
            player.kill();
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
        
        String msgId = source.getMsgId();
        if (msgId != null)
        {
            if (msgId.equals("genericKill") || msgId.equals("generic") || msgId.equals("outOfWorld"))
                return true;
        }
        
        return false;
    }

    private static void revivePlayer(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        stopRevive(player, false, true);
        
        bleedingPlayers.remove(playerId);
        gracePeriodTicks.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId);
        dyingPlayers.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        player.getPersistentData().remove("bleedout:ticksLeft");
        
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
