package com.covalscy.bleedout.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class BleedOutNetwork
{
    public static void init(IEventBus modEventBus)
    {
        modEventBus.addListener(BleedOutNetwork::register);
    }

    private static void register(final RegisterPayloadHandlersEvent event)
    {
        final PayloadRegistrar registrar = event.registrar("1");

        registrar.playToClient(
                BleedOutUpdatePacket.TYPE,
                BleedOutUpdatePacket.STREAM_CODEC,
                BleedOutUpdatePacket::handle
        );

        registrar.playToClient(
                ReviveUpdatePacket.TYPE,
                ReviveUpdatePacket.STREAM_CODEC,
                ReviveUpdatePacket::handle
        );

        registrar.playToServer(
                SuicideRequestPacket.TYPE,
                SuicideRequestPacket.STREAM_CODEC,
                SuicideRequestPacket::handle
        );
    }

    public static void sendToClient(BleedOutUpdatePacket packet, ServerPlayer player)
    {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToTrackingAndSelf(BleedOutUpdatePacket packet, ServerPlayer player)
    {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, packet);
    }

    public static void sendReviveUpdateToClient(ReviveUpdatePacket packet, ServerPlayer player)
    {
        PacketDistributor.sendToPlayer(player, packet);
    }

    public static void sendToServer(SuicideRequestPacket packet)
    {
        PacketDistributor.sendToServer(packet);
    }
}
