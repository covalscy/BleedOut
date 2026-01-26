package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public class BleedOutNetwork
{
    private static final String PROTOCOL_VERSION = "1";
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    public static void register()
    {
        int id = 0;
        INSTANCE.registerMessage(id++, BleedOutUpdatePacket.class,
            BleedOutUpdatePacket::encode,
            BleedOutUpdatePacket::decode,
            BleedOutUpdatePacket::handle);
        INSTANCE.registerMessage(id++, SuicideRequestPacket.class,
            SuicideRequestPacket::encode,
            SuicideRequestPacket::decode,
            SuicideRequestPacket::handle);
        INSTANCE.registerMessage(id++, ReviveUpdatePacket.class,
            ReviveUpdatePacket::encode,
            ReviveUpdatePacket::decode,
            ReviveUpdatePacket::handle);
    }

    public static void sendToClient(BleedOutUpdatePacket packet, ServerPlayer player)
    {
        INSTANCE.sendTo(packet, player.connection.connection, NetworkDirection.PLAY_TO_CLIENT);
    }
}
