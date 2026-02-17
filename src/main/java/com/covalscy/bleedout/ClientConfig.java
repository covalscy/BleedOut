package com.covalscy.bleedout;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ClientConfig
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.DoubleValue HUD_SCALE = BUILDER
            .comment("HUD interface scale (text and progress bars)")
            .comment("Масштаб интерфейса HUD (текст и прогресс-бары)")
            .defineInRange("hudScale", 1.5, 0.5, 3.0);

    public static final ForgeConfigSpec.BooleanValue ENABLE_HEARTBEAT_SOUND = BUILDER
            .comment("Enable heartbeat sound during bleeding")
            .comment("Включить звук сердцебиения при кровотечении")
            .define("enableHeartbeatSound", true);

    public static final ForgeConfigSpec.BooleanValue ENABLE_KNOCKED_TEAMMATE_RENDER = BUILDER
            .comment("Enable knocked teammate icon rendering in world")
            .comment("Включить рендер иконки нокнутых союзников в мире")
            .define("enableKnockedTeammateRender", true);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static double hudScale;
    public static boolean enableHeartbeatSound;
    public static boolean enableKnockedTeammateRender;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        // Загружаем только когда это наш CLIENT конфиг
        if (event.getConfig().getType() != net.minecraftforge.fml.config.ModConfig.Type.CLIENT)
            return;
        if (event.getConfig().getSpec() != SPEC)
            return;

        hudScale = HUD_SCALE.get();
        enableHeartbeatSound = ENABLE_HEARTBEAT_SOUND.get();
        enableKnockedTeammateRender = ENABLE_KNOCKED_TEAMMATE_RENDER.get();
    }
}

