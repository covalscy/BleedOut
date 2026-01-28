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

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static double hudScale;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        // Загружаем только когда это наш CLIENT конфиг
        if (event.getConfig().getType() == net.minecraftforge.fml.config.ModConfig.Type.CLIENT)
        {
            hudScale = HUD_SCALE.get();
        }
    }
}
