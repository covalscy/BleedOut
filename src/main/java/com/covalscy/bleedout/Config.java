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
            .defineInRange("bleedoutTime", 10, 1, 3000);

    public static final ForgeConfigSpec.IntValue REVIVE_TIME = BUILDER
            .comment("Время возрождения в секундах (ПКМ по лежащему игроку)")
            .defineInRange("reviveTime", 5, 1, 60);

    public static final ForgeConfigSpec.DoubleValue REVIVE_DISTANCE = BUILDER
            .comment("Максимальная дальность для начала и продолжения возрождения в блоках")
            .defineInRange("reviveDistance", 5.0, 1.0, 20.0);

    public static final ForgeConfigSpec.IntValue SUICIDE_TIME = BUILDER
            .comment("Время удержания ЛКМ для самоубийства в секундах")
            .defineInRange("suicideTime", 3, 1, 10);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int bleedoutTime;
    public static int reviveTime;
    public static double reviveDistance;
    public static int suicideTime;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        bleedoutTime = BLEEDOUT_TIME.get();
        reviveTime = REVIVE_TIME.get();
        reviveDistance = REVIVE_DISTANCE.get();
        suicideTime = SUICIDE_TIME.get();
    }
}
