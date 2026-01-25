package com.covalscy.bleedout;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.IntValue BLEEDOUT_TIME = BUILDER
            .comment("Время кровотечения в секундах перед смертью")
            .defineInRange("bleedoutTime", 10, 1, 300);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int bleedoutTime;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        bleedoutTime = BLEEDOUT_TIME.get();
    }
}
