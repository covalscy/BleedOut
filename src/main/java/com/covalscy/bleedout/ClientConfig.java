package com.covalscy.bleedout;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class ClientConfig
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue HUD_SCALE = BUILDER
            .comment("HUD interface scale (text and progress bars)")
            .comment("Масштаб интерфейса HUD (текст и прогресс-бары)")
            .defineInRange("hudScale", 1.5, 0.5, 3.0);

    public static final ModConfigSpec.BooleanValue ENABLE_HEARTBEAT_SOUND = BUILDER
            .comment("Enable heartbeat sound during bleeding")
            .comment("Включить звук сердцебиения при кровотечении")
            .define("enableHeartbeatSound", true);

    public static final ModConfigSpec.BooleanValue ENABLE_KNOCKED_TEAMMATE_RENDER = BUILDER
            .comment("Enable knocked teammate icon rendering in world")
            .comment("Включить рендер иконки нокнутых союзников в мире")
            .define("enableKnockedTeammateRender", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static double hudScale = 1.5;
    public static boolean enableHeartbeatSound = true;
    public static boolean enableKnockedTeammateRender = true;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        if (event.getConfig().getType() != net.neoforged.fml.config.ModConfig.Type.CLIENT)
            return;
        if (event.getConfig().getSpec() != SPEC)
            return;

        hudScale = HUD_SCALE.get();
        enableHeartbeatSound = ENABLE_HEARTBEAT_SOUND.get();
        enableKnockedTeammateRender = ENABLE_KNOCKED_TEAMMATE_RENDER.get();
    }
}
