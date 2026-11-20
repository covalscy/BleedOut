package com.covalscy.bleedout.network;

import com.covalscy.bleedout.SyncedSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

public class BleedOutNetwork
{
    public static void init(IEventBus modEventBus)
    {
        modEventBus.addListener(BleedOutNetwork::register);
    }

    private static void register(final RegisterPayloadHandlersEvent event)
    {
        // "2": the suicide packet format changed and a settings packet was added
        final PayloadRegistrar registrar = event.registrar("2");

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

        registrar.playToClient(
                SettingsSyncPacket.TYPE,
                SettingsSyncPacket.STREAM_CODEC,
                SettingsSyncPacket::handle
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

    public static void sendSettings(ServerPlayer player)
    {
        PacketDistributor.sendToPlayer(player, new SettingsSyncPacket(SyncedSettings.fromConfig()));
    }

    public static void sendSettingsToAll()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            server.execute(() -> PacketDistributor.sendToAllPlayers(new SettingsSyncPacket(SyncedSettings.fromConfig())));
        }
    }

    public static void sendToServer(SuicideRequestPacket packet)
    {
        PacketDistributor.sendToServer(packet);
    }
}
