package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.BleedOutUpdatePacket;
import com.covalscy.bleedout.network.ReviveUpdatePacket;
import com.covalscy.bleedout.network.SuicideRequestPacket;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.GAME)
public class BleedOutHandler
{
    private static final Logger LOGGER = LogUtils.getLogger();
    
    private static final Map<UUID, Integer> bleedingPlayers = new HashMap<>();
    private static final Map<UUID, ReviveData> revivingPlayers = new HashMap<>();
    private static final Set<UUID> dyingPlayers = new HashSet<>();
    private static final Set<UUID> deadPlayers = new HashSet<>();
    private static final Map<UUID, Long> lastVehicleTick = new HashMap<>();
    private static final Map<UUID, Entity> lastVehicle = new HashMap<>();
    private static final long VEHICLE_GRACE_TICKS = 20L;
    private static final Map<UUID, Boolean> poseSetForPlayers = new HashMap<>();
    private static final Map<UUID, UUID> bleedingDowners = new HashMap<>();
    
    private static final Map<UUID, Integer> gracePeriodTicks = new HashMap<>();
    
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

    /** The vehicle the player is sitting in, or sat in during the last second (e.g. thrown out by an explosion). */
    private static Entity getVehicleOf(ServerPlayer player)
    {
        Entity veh = player.getRootVehicle();
        if (veh != null && veh != player)
        {
            return veh;
        }
        Long last = lastVehicleTick.get(player.getUUID());
        if (last != null && player.level().getGameTime() - last <= VEHICLE_GRACE_TICKS)
        {
            return lastVehicle.get(player.getUUID());
        }
        return null;
    }

    private static boolean isVehicleDestroyed(Entity vehicle)
    {
        if (vehicle == null) return true;
        if (vehicle.isRemoved() || !vehicle.isAlive()) return true;
        if (vehicle instanceof LivingEntity living && living.isDeadOrDying()) return true;
        try
        {
            // SuperbWarfare vehicles expose getHealth() but are not LivingEntity
            java.lang.reflect.Method m = vehicle.getClass().getMethod("getHealth");
            Object h = m.invoke(vehicle);
            if (h instanceof Number n && n.floatValue() <= 0.0F) return true;
        }
        catch (Exception ignored) {}
        return false;
    }

    /** Players in a vehicle (or who left/were thrown out of it in the last second) and creative players are never knocked down. */
    private static boolean cannotBeKnocked(ServerPlayer player)
    {
        if (player.isCreative() || player.isSpectator()) return true;
        if (player.isPassenger()) return true;
        Long last = lastVehicleTick.get(player.getUUID());
        return last != null && player.level().getGameTime() - last <= VEHICLE_GRACE_TICKS;
    }

    public static boolean isBleeding(Player player)
    {
        if (player == null) return false;
        // Server: the in-memory map is the only truth. The persistent-data flag is saved with the player,
        // so after a crash it could survive without a running bleedout. Clients only have the synced flag.
        if (player instanceof ServerPlayer) return bleedingPlayers.containsKey(player.getUUID());
        return player.getPersistentData().getBoolean("bleedout:bleeding");
    }

    private static final ResourceLocation SPEED_MODIFIER = ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "knocked_speed");
    private static final ResourceLocation JUMP_MODIFIER = ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "knocked_jump");

    /** Attribute modifiers are synced to the client, so the client's own movement obeys them. */
    private static void updateMovementModifiers(ServerPlayer player, boolean knocked)
    {
        AttributeInstance jump = player.getAttribute(Attributes.JUMP_STRENGTH);
        if (jump != null)
        {
            if (knocked && !jump.hasModifier(JUMP_MODIFIER))
                jump.addTransientModifier(new AttributeModifier(JUMP_MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (!knocked && jump.hasModifier(JUMP_MODIFIER))
                jump.removeModifier(JUMP_MODIFIER);
        }

        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null)
        {
            boolean freeze = knocked && Config.disableMovementWhileBleeding;
            if (freeze && !speed.hasModifier(SPEED_MODIFIER))
                speed.addTransientModifier(new AttributeModifier(SPEED_MODIFIER, -1.0, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
            else if (!freeze && speed.hasModifier(SPEED_MODIFIER))
                speed.removeModifier(SPEED_MODIFIER);
        }
    }

    private static void clearPersistentFlags(Player player)
    {
        player.getPersistentData().remove("bleedout:bleeding");
        player.getPersistentData().remove("bleedout:ticksLeft");
        player.getPersistentData().remove("bleedout:reviving");
        player.getPersistentData().remove("bleedout:reviveTicks");
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            if (!bleedingPlayers.containsKey(player.getUUID()))
            {
                // Leftovers from a crash/restart while knocked
                clearPersistentFlags(player);
                updateMovementModifiers(player, false);
                if (player.getForcedPose() == Pose.SWIMMING)
                {
                    player.setForcedPose(null);
                    player.setPose(Pose.STANDING);
                    player.refreshDimensions();
                }
            }
            BleedOutNetwork.sendSettings(player);
        }
    }

    // ------------------------------------------------------------------ suicide (hold LMB), verified on the server

    private static final Map<UUID, Long> suicideHoldStart = new HashMap<>();
    private static final Set<UUID> suicideConfirmPending = new HashSet<>();

    public static void onSuicideRequest(ServerPlayer player, SuicideRequestPacket.Phase phase)
    {
        UUID id = player.getUUID();
        if (!Config.enableSuicide || !isBleeding(player))
        {
            suicideHoldStart.remove(id);
            suicideConfirmPending.remove(id);
            return;
        }
        long now = player.level().getGameTime();
        switch (phase)
        {
            case START -> {
                suicideHoldStart.put(id, now);
                suicideConfirmPending.remove(id);
            }
            case CANCEL -> {
                suicideHoldStart.remove(id);
                suicideConfirmPending.remove(id);
            }
            case CONFIRM -> {
                Long start = suicideHoldStart.get(id);
                if (start != null)
                {
                    // Small tolerance for network jitter
                    if (now - start >= Config.suicideTime * 20L - 10L)
                    {
                        suicideHoldStart.remove(id);
                        suicideConfirmPending.remove(id);
                        killBleedingPlayer(player);
                    }
                    else
                    {
                        suicideConfirmPending.add(id);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ configurable bleeding effects

    private record EffectSpec(Holder<MobEffect> effect, int amplifier) {}

    private static List<? extends String> parsedEffectsSource;
    private static List<EffectSpec> parsedEffects = List.of();

    private static List<EffectSpec> bleedingEffects()
    {
        List<? extends String> source = Config.bleedingEffects;
        if (source == null) return List.of();
        if (source != parsedEffectsSource)
        {
            List<EffectSpec> result = new ArrayList<>();
            for (String entry : source)
            {
                if (entry == null || entry.isBlank()) continue;
                EffectSpec spec = parseEffect(entry);
                if (spec != null) result.add(spec);
                else LOGGER.warn("[BleedOut] Unknown bleeding effect '{}', expected effect_id[:amplifier]", entry);
            }
            parsedEffects = result;
            parsedEffectsSource = source;
        }
        return parsedEffects;
    }

    private static EffectSpec parseEffect(String entry)
    {
        String id = entry.trim();
        int amplifier = 0;
        int lastColon = id.lastIndexOf(':');
        if (lastColon > 0)
        {
            try
            {
                amplifier = Integer.parseInt(id.substring(lastColon + 1).trim());
                id = id.substring(0, lastColon).trim();
            }
            catch (NumberFormatException ignored)
            {
                // e.g. "minecraft:slowness" - no amplifier given
            }
        }
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        Optional<Holder.Reference<MobEffect>> holder = BuiltInRegistries.MOB_EFFECT.getHolder(rl);
        final int amp = Math.max(0, amplifier);
        return holder.map(h -> new EffectSpec(h, amp)).orElse(null);
    }

    private static void applyConfiguredBleedingEffects(ServerPlayer player)
    {
        for (EffectSpec spec : bleedingEffects())
        {
            player.addEffect(new MobEffectInstance(spec.effect(), 40, spec.amplifier(), false, false));
        }
    }

    private static void removeBleedingEffects(ServerPlayer player)
    {
        for (EffectSpec spec : bleedingEffects())
        {
            MobEffectInstance active = player.getEffect(spec.effect());
            // Only remove our own short, hidden instance, never a longer effect from a potion
            if (active != null && active.getDuration() <= 40 && !active.isVisible())
            {
                player.removeEffect(spec.effect());
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingIncomingDamageEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide)
        {
            return;
        }

        UUID playerId = player.getUUID();
        
        // TACZ applies a bullet as several hurt() calls in a row. If the first one already killed the player,
        // the next one must not process on the corpse again.
        if (deadPlayers.contains(playerId))
        {
            event.setCanceled(true);
            return;
        }
        
        if (dyingPlayers.contains(playerId) || player.isDeadOrDying() || player.isRemoved())
        {
            return;
        }

        DamageSource source = event.getSource();
        if (doesByPass(player, source))
        {
            return;
        }

        if (!bleedingPlayers.containsKey(playerId))
        {
            Entity vehicle = getVehicleOf(player);
            if (vehicle != null && !isVehicleDestroyed(vehicle))
            {
                // Inside a vehicle a player is neither knocked nor killed - only the vehicle exploding can kill him
                event.setCanceled(true);
                return;
            }
        }

        if (bleedingPlayers.containsKey(playerId))
        {
            Integer grace = gracePeriodTicks.get(playerId);
            if (grace != null && grace > 0)
            {
                event.setCanceled(true);
            }
        }

        // Lethal damage is NOT decided here: this event carries the raw amount before armor, enchantments,
        // resistance and absorption, and fires before the Totem of Undying check. Knocking down happens in
        // onPlayerDeath (LivingDeathEvent), where the damage really was lethal.
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
            DamageSource source = event.getSource();
            Entity attacker = source.getEntity();
            Entity directAttacker = source.getDirectEntity();

            // Bullets/arrows (TACZ, SuperbWarfare...) have a projectile as direct entity, but are fired by a player -
            // such damage must NOT be treated as mob damage, otherwise knocked players can't be finished with guns.
            boolean caughtByPlayer = attacker instanceof Player
                    || directAttacker instanceof Player
                    || (directAttacker instanceof Projectile projectile && projectile.getOwner() instanceof Player);

            boolean isMobAttacker = !caughtByPlayer && (attacker != null || directAttacker != null);

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

            boolean noKnock = cannotBeKnocked(player);

            if (player.isPassenger())
            {
                player.stopRiding();
            }

            if (deadPlayers.contains(playerId) || dyingPlayers.contains(playerId))
            {
                return;
            }

            if (noKnock || doesByPass(player, source) || bleedingPlayers.containsKey(playerId))
            {
                dyingPlayers.add(playerId);
                cleanUpBleedingAndDying(player);
                return;
            }

            // Healthy player taking lethal damage -> ENTER BLEEDOUT
            event.setCanceled(true);
            player.setHealth(Math.max(1.0F, Math.min(player.getMaxHealth(), (float) Config.bleedingHealth)));

            ServerPlayer attacker = null;
            if (source.getEntity() instanceof ServerPlayer sAttacker)
            {
                attacker = sAttacker;
            }
            else if (source.getDirectEntity() instanceof ServerPlayer dAttacker)
            {
                attacker = dAttacker;
            }
            else if (source.getDirectEntity() instanceof Projectile projectile && projectile.getOwner() instanceof ServerPlayer pAttacker)
            {
                attacker = pAttacker;
            }

            startBleedout(player, attacker);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerDeathConfirmed(LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide)
        {
            deadPlayers.add(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event)
    {
        if (event.getEntity() instanceof ServerPlayer player && !event.getEntity().level().isClientSide)
        {
            UUID playerId = player.getUUID();
            
            if (player.isPassenger())
            {
                lastVehicleTick.put(playerId, player.level().getGameTime());
                lastVehicle.put(playerId, player.getRootVehicle());
            }
            
            if (player.getHealth() > 0.0F)
            {
                deadPlayers.remove(playerId);
            }
            else if (!deadPlayers.contains(playerId) && !player.isRemoved() && !player.isCreative() && !player.isSpectator())
            {
                // Health is 0 but death was never processed (player would be immortal) - finish it properly.
                if (isBleeding(player))
                {
                    killBleedingPlayer(player);
                }
                else if (!dyingPlayers.contains(playerId))
                {
                    DamageSource killSource = player.damageSources().genericKill();
                    cleanUpBleedingAndDying(player);
                    player.die(killSource);
                }
                return;
            }
            else if (deadPlayers.contains(playerId))
            {
                // Already dead, waiting for respawn - nothing to do (and never call die() again)
                return;
            }
            
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
                if (suicideConfirmPending.contains(playerId))
                {
                    Long start = suicideHoldStart.get(playerId);
                    if (start != null && player.level().getGameTime() - start >= Config.suicideTime * 20L - 10L)
                    {
                        suicideHoldStart.remove(playerId);
                        suicideConfirmPending.remove(playerId);
                        killBleedingPlayer(player);
                        return;
                    }
                }

                int ticksLeft = bleedingPlayers.get(playerId);
                if (Config.enableBleedoutTimer)
                {
                    ticksLeft--;
                }
                
                if (Config.enableBleedoutTimer && ticksLeft <= 0)
                {
                    killBleedingPlayer(player);
                }
                else
                {
                    bleedingPlayers.put(playerId, ticksLeft);

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
                    // Movement is client-authoritative: speed/jump are limited through synced attribute modifiers.
                    updateMovementModifiers(player, true);

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
                                           reviver.level() == player.level() &&
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
                        stopRevive(player);
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
                    }
                    
                    if (player.tickCount % 5 == 0)
                    {
                        if (reviver != null)
                        {
                            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveData.ticksLeft), reviver);
                        }
                        BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveData.ticksLeft), player);
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerInteract(PlayerInteractEvent.EntityInteract event)
    {
        if (event.getEntity() instanceof ServerPlayer interactor && isBleeding(interactor))
        {
            event.setCanceled(true);
            return;
        }

        if (event.getTarget() instanceof ServerPlayer targetPlayer && 
            event.getEntity() instanceof ServerPlayer interactor &&
            event.getHand() == InteractionHand.MAIN_HAND)
        {
            if (isBleeding(targetPlayer))
            {
                event.setCanceled(true);

                if (!isBleeding(interactor) &&
                    !targetPlayer.getUUID().equals(interactor.getUUID()) &&
                    interactor.distanceTo(targetPlayer) <= Config.reviveDistance)
                {
                    if (revivingPlayers.containsKey(targetPlayer.getUUID()))
                    {
                        return;
                    }

                    stopRevivingByReviver(interactor.getUUID(), interactor.server);

                    int reviveTicks = Config.reviveTime * 20;
                    revivingPlayers.put(targetPlayer.getUUID(), new ReviveData(interactor.getUUID(), reviveTicks));
                    
                    targetPlayer.getPersistentData().putBoolean("bleedout:reviving", true);
                    targetPlayer.getPersistentData().putInt("bleedout:reviveTicks", reviveTicks);
                    interactor.getPersistentData().putBoolean("bleedout:reviving", true);
                    interactor.getPersistentData().putInt("bleedout:reviveTicks", reviveTicks);

                    BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveTicks), interactor);
                    BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(true, reviveTicks), targetPlayer);
                }
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerLeftClickBlock(PlayerInteractEvent.LeftClickBlock event)
    {
        if (event.getEntity() instanceof ServerPlayer player && isBleeding(player))
        {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event)
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
            deadPlayers.remove(playerId);
            lastVehicleTick.remove(playerId);
            lastVehicle.remove(playerId);
            cleanUpBleedingAndDying(player);
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
            
            stopRevive(player);
            if (player.server != null)
            {
                stopRevivingByReviver(playerId, player.server);
            }
            
            dyingPlayers.remove(playerId);
            gracePeriodTicks.remove(playerId);
            deadPlayers.remove(playerId);
            lastVehicleTick.remove(playerId);
            lastVehicle.remove(playerId);
            suicideHoldStart.remove(playerId);
            suicideConfirmPending.remove(playerId);
        }
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
        updateMovementModifiers(player, true);

        if (attacker != null)
        {
            bleedingDowners.put(playerId, attacker.getUUID());
        }
        else
        {
            bleedingDowners.remove(playerId);
        }

        stopRevive(player);
        if (player.server != null)
        {
            stopRevivingByReviver(playerId, player.server);
        }
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, true, bleedoutTicks),
            player
        );
        
        applyConfiguredBleedingEffects(player);
    }

    public static void cleanUpBleedingAndDying(ServerPlayer player)
    {
        UUID playerId = player.getUUID();
        stopRevive(player);
        if (player.server != null)
        {
            stopRevivingByReviver(playerId, player.server);
        }

        bleedingPlayers.remove(playerId);
        dyingPlayers.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId);
        gracePeriodTicks.remove(playerId);
        suicideHoldStart.remove(playerId);
        suicideConfirmPending.remove(playerId);
        clearPersistentFlags(player);
        updateMovementModifiers(player, false);
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
        player.refreshDimensions();

        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, false, 0),
            player
        );
    }

    public static void killBleedingPlayer(ServerPlayer player)
    {
        if (player == null) return;
        UUID playerId = player.getUUID();
        
        if (dyingPlayers.contains(playerId) && !bleedingPlayers.containsKey(playerId))
        {
            return;
        }
        
        dyingPlayers.add(playerId);
        stopRevive(player);
        if (player.server != null)
        {
            stopRevivingByReviver(playerId, player.server);
        }
        
        UUID downerId = bleedingDowners.remove(playerId);
        
        bleedingPlayers.remove(playerId);
        gracePeriodTicks.remove(playerId);
        poseSetForPlayers.remove(playerId);
        suicideHoldStart.remove(playerId);
        suicideConfirmPending.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        player.getPersistentData().remove("bleedout:ticksLeft");
        updateMovementModifiers(player, false);
        player.setForcedPose(null);
        player.setPose(Pose.STANDING);
        player.refreshDimensions();
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        // If player already has <= 0 health, hurt() will be rejected by isDeadOrDying().
        // Reset health to 1.0f first so hurt() can proceed through damage logic and call die()
        if (player.getHealth() <= 0.0F)
        {
            player.setHealth(1.0F);
        }
        
        DamageSource killSource = player.damageSources().genericKill();
        boolean hurtSuccess = false;
        
        if (downerId != null && player.server != null)
        {
            ServerPlayer downer = player.server.getPlayerList().getPlayer(downerId);
            if (downer != null && !downer.isRemoved())
            {
                try
                {
                    DamageSource downerSource = player.damageSources().playerAttack(downer);
                    hurtSuccess = player.hurt(downerSource, Float.MAX_VALUE) && player.isDeadOrDying();
                    if (hurtSuccess)
                    {
                        killSource = downerSource;
                    }
                }
                catch (Exception ignored) {}
            }
        }
        
        if (!hurtSuccess && (!player.isDeadOrDying() || player.isAlive()))
        {
            if (player.getHealth() <= 0.0F)
            {
                player.setHealth(1.0F);
            }
            hurtSuccess = player.hurt(killSource, Float.MAX_VALUE);
        }
        
        // Failsafe: if hurt() was canceled, failed, or player is somehow not dead, directly trigger die()
        if (!hurtSuccess || !player.isDeadOrDying() || player.isAlive())
        {
            player.setHealth(0.0F);
            player.die(killSource);
        }
        
        cleanUpBleedingAndDying(player);
    }

    public static void stopRevive(ServerPlayer player)
    {
        if (player == null) return;
        UUID playerId = player.getUUID();
        if (revivingPlayers.containsKey(playerId))
        {
            ReviveData reviveData = revivingPlayers.remove(playerId);
            
            player.getPersistentData().remove("bleedout:reviving");
            player.getPersistentData().remove("bleedout:reviveTicks");
            BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), player);
            
            if (player.server != null)
            {
                ServerPlayer reviver = player.server.getPlayerList().getPlayer(reviveData.reviverId);
                if (reviver != null)
                {
                    reviver.getPersistentData().remove("bleedout:reviving");
                    reviver.getPersistentData().remove("bleedout:reviveTicks");
                    BleedOutNetwork.sendReviveUpdateToClient(new ReviveUpdatePacket(false, 0), reviver);
                }
            }
        }
    }

    public static void stopRevivingByReviver(UUID reviverId, net.minecraft.server.MinecraftServer server)
    {
        if (reviverId == null || server == null) return;
        for (Map.Entry<UUID, ReviveData> entry : new HashMap<>(revivingPlayers).entrySet())
        {
            if (entry.getValue().reviverId.equals(reviverId))
            {
                ServerPlayer target = server.getPlayerList().getPlayer(entry.getKey());
                if (target != null)
                {
                    stopRevive(target);
                }
                else
                {
                    revivingPlayers.remove(entry.getKey());
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
        stopRevive(player);
        
        bleedingPlayers.remove(playerId);
        gracePeriodTicks.remove(playerId);
        poseSetForPlayers.remove(playerId);
        bleedingDowners.remove(playerId);
        dyingPlayers.remove(playerId);
        suicideHoldStart.remove(playerId);
        suicideConfirmPending.remove(playerId);
        player.getPersistentData().remove("bleedout:bleeding");
        player.getPersistentData().remove("bleedout:ticksLeft");
        
        BleedOutNetwork.sendToTrackingAndSelf(
            new BleedOutUpdatePacket(playerId, false, 0),
            player
        );
        
        removeBleedingEffects(player);
        updateMovementModifiers(player, false);
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
