package com.covalscy.bleedout.network;

import com.covalscy.bleedout.BleedOutMod;
import com.covalscy.bleedout.SyncedSettings;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

/** Server → client: the server's BleedOut settings that client-side logic depends on. */
public record SettingsSyncPacket(SyncedSettings.Values values) implements CustomPacketPayload
{
    public static final Type<SettingsSyncPacket> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "settings_sync"));

    public static final StreamCodec<FriendlyByteBuf, SettingsSyncPacket> STREAM_CODEC = StreamCodec.of(
            (buf, packet) -> {
                SyncedSettings.Values v = packet.values();
                buf.writeVarInt(v.bleedoutTime());
                buf.writeVarInt(v.reviveTime());
                buf.writeVarInt(v.suicideTime());
                buf.writeBoolean(v.enableSuicide());
                buf.writeBoolean(v.enableBleedoutTimer());
                buf.writeBoolean(v.disableMovementWhileBleeding());
                buf.writeVarInt(v.disabledInputsWhileBleeding().size());
                for (String key : v.disabledInputsWhileBleeding()) buf.writeUtf(key);
            },
            buf -> {
                int bleedoutTime = buf.readVarInt();
                int reviveTime = buf.readVarInt();
                int suicideTime = buf.readVarInt();
                boolean enableSuicide = buf.readBoolean();
                boolean enableTimer = buf.readBoolean();
                boolean disableMovement = buf.readBoolean();
                int n = buf.readVarInt();
                List<String> inputs = new ArrayList<>(n);
                for (int i = 0; i < n; i++) inputs.add(buf.readUtf());
                return new SettingsSyncPacket(new SyncedSettings.Values(bleedoutTime, reviveTime, suicideTime,
                        enableSuicide, enableTimer, disableMovement, List.copyOf(inputs)));
            });

    @Override
    public Type<? extends CustomPacketPayload> type()
    {
        return TYPE;
    }

    public void handle(IPayloadContext context)
    {
        context.enqueueWork(() -> SyncedSettings.set(values));
    }
}
