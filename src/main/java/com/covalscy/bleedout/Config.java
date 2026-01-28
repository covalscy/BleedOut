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
            .comment("Bleedout duration in seconds before death")
            .comment("Время кровотечения в секундах перед смертью")
            .defineInRange("bleedoutTime", 45, 1, 300);

    public static final ForgeConfigSpec.IntValue REVIVE_TIME = BUILDER
            .comment("Revival time in seconds (right-click on downed player)")
            .comment("Время возрождения в секундах (ПКМ по лежащему игроку)")
            .defineInRange("reviveTime", 5, 1, 60);

    public static final ForgeConfigSpec.DoubleValue REVIVE_DISTANCE = BUILDER
            .comment("Maximum distance in blocks to start and continue revival")
            .comment("Максимальная дальность для начала и продолжения возрождения в блоках")
            .defineInRange("reviveDistance", 5.0, 1.0, 20.0);

    public static final ForgeConfigSpec.IntValue SUICIDE_TIME = BUILDER
            .comment("Time to hold left-click for suicide in seconds")
            .comment("Время удержания ЛКМ для самоубийства в секундах")
            .defineInRange("suicideTime", 3, 1, 10);

    public static final ForgeConfigSpec.IntValue BLEEDING_HEALTH = BUILDER
            .comment("Health in half-hearts when downed (6 = 3 hearts)")
            .comment("Здоровье в половинках сердец когда игрок лежит (6 = 3 сердца)")
            .defineInRange("bleedingHealth", 6, 1, 20);

    public static final ForgeConfigSpec.IntValue INITIAL_DAMAGE_COOLDOWN = BUILDER
            .comment("Initial invulnerability time in ticks after being downed (10 = 0.5 seconds)")
            .comment("Время начальной неуязвимости в тиках после падения (10 = 0.5 секунды)")
            .defineInRange("initialDamageCooldown", 10, 0, 100);

    static final ForgeConfigSpec SPEC = BUILDER.build();

    public static int bleedoutTime;
    public static int reviveTime;
    public static double reviveDistance;
    public static int suicideTime;
    public static int bleedingHealth;
    public static int initialDamageCooldown;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        // Загружаем только когда это наш COMMON конфиг
        if (event.getConfig().getType() != net.minecraftforge.fml.config.ModConfig.Type.COMMON)
            return;
        if (event.getConfig().getSpec() != SPEC)
            return;

        bleedoutTime = BLEEDOUT_TIME.get();
        reviveTime = REVIVE_TIME.get();
        reviveDistance = REVIVE_DISTANCE.get();
        suicideTime = SUICIDE_TIME.get();
        bleedingHealth = BLEEDING_HEALTH.get();
        initialDamageCooldown = INITIAL_DAMAGE_COOLDOWN.get();
    }
}
