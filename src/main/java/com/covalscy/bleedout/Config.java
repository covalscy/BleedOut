package com.covalscy.bleedout;

import java.util.List;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.MOD)
public class Config
{
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue BLEEDOUT_TIME = BUILDER
            .comment("Bleedout duration in seconds before death")
            .comment("Время кровотечения в секундах перед смертью")
            .defineInRange("bleedoutTime", 45, 1, 300);

    public static final ModConfigSpec.IntValue REVIVE_TIME = BUILDER
            .comment("Revival time in seconds (right-click on downed player)")
            .comment("Время возрождения в секундах (ПКМ по лежащему игроку)")
            .defineInRange("reviveTime", 5, 1, 60);

    public static final ModConfigSpec.DoubleValue REVIVE_DISTANCE = BUILDER
            .comment("Maximum distance in blocks to start and continue revival")
            .comment("Максимальная дальность для начала и продолжения возрождения в блоках")
            .defineInRange("reviveDistance", 5.0, 1.0, 20.0);

    public static final ModConfigSpec.IntValue SUICIDE_TIME = BUILDER
            .comment("Time to hold left-click for suicide in seconds")
            .comment("Время удержания ЛКМ для самоубийства в секундах")
            .defineInRange("suicideTime", 3, 1, 10);

    public static final ModConfigSpec.IntValue BLEEDING_HEALTH = BUILDER
            .comment("Health in half-hearts when downed (6 = 3 hearts)")
            .comment("Здоровье в половинках сердец когда игрок лежит (6 = 3 сердца)")
            .defineInRange("bleedingHealth", 6, 1, 20);

    public static final ModConfigSpec.IntValue INITIAL_DAMAGE_COOLDOWN = BUILDER
            .comment("Initial invulnerability time in ticks after being downed (10 = 0.5 seconds)")
            .comment("Время начальной неуязвимости в тиках после падения (10 = 0.5 секунды)")
            .defineInRange("initialDamageCooldown", 10, 0, 100);

    public static final ModConfigSpec.BooleanValue DISABLE_MOVEMENT_WHILE_BLEEDING = BUILDER
            .comment("Disable movement while bleeding")
            .comment("Отключить движение во время кровотечения")
            .define("disableMovementWhileBleeding", false);

    public static final ModConfigSpec.BooleanValue BLEEDING_PLAYERS_IGNORED_BY_MOBS = BUILDER
            .comment("Make bleeding players ignored by mob targeting")
            .comment("Мобы игнорируют игроков во время кровотечения как цель")
            .define("bleedingPlayersIgnoredByMobs", false);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLEEDING_EFFECTS = BUILDER
            .comment("Effects applied while bleeding. Format: effect_id[:amplifier], amplifier starts at 0")
            .comment("Эффекты во время кровотечения. Формат: effect_id[:amplifier], уровень начинается с 0")
            .defineListAllowEmpty(
                    "bleedingEffects",
                    List.of("minecraft:slowness:1"),
                    value -> value instanceof String
            );

    public static final ModConfigSpec.ConfigValue<List<? extends String>> DISABLED_INPUTS_WHILE_BLEEDING = BUILDER
            .comment("Keyboard and mouse inputs disabled while bleeding")
            .comment("Special values: ALL_KEYBOARD disables all keyboard input, ALL disables keyboard and mouse buttons")
            .comment("Examples: key.jump, key.sneak, key.inventory, key.attack, key.use")
            .comment("Клавиши и кнопки мыши, отключаемые во время кровотечения")
            .defineListAllowEmpty(
                    "disabledInputsWhileBleeding",
                    List.of(
                            "key.parcool.Enable",
                            "key.parcool.Crawl",
                            "key.parcool.ClingToCliff",
                            "key.parcool.Breakfall",
                            "key.parcool.FastRun",
                            "key.parcool.Flipping",
                            "key.parcool.Vault",
                            "key.parcool.Dodge",
                            "key.parcool.RideZipline",
                            "key.parcool.WallJump",
                            "key.parcool.HangDown",
                            "key.parcool.WallSlide",
                            "key.parcool.HideInBlock",
                            "key.parcool.HorizontalWallRun",
                            "key.parcool.QuickTurn",
                            "key.parcool.openSetting"
                    ),
                    value -> value instanceof String
            );

    public static final ModConfigSpec.BooleanValue ENABLE_SUICIDE = BUILDER
            .comment("Enable suicide option while bleeding")
            .comment("Разрешить самоубийство во время кровотечения")
            .define("enableSuicide", true);

    public static final ModConfigSpec.BooleanValue ENABLE_BLEEDOUT_TIMER = BUILDER
            .comment("Enable bleedout timer (if false, players will bleed indefinitely until revived or killed)")
            .comment("Включить таймер кровотечения (если отключено, игроки будут лежать бесконечно, пока их не поднимут или не убьют)")
            .define("enableBleedoutTimer", true);

    public static final ModConfigSpec.BooleanValue ENABLE_RAGDOLL_COMPAT = BUILDER
            .comment("Enable limp ragdoll physics for knocked players when sable_player_ragdoll is installed")
            .comment("Включить вялый физический регдолл для нокнутых игроков, если установлен мод sable_player_ragdoll")
            .define("enableRagdollCompat", true);

    static final ModConfigSpec SPEC = BUILDER.build();

    public static int bleedoutTime = 45;
    public static int reviveTime = 5;
    public static double reviveDistance = 5.0;
    public static int suicideTime = 3;
    public static int bleedingHealth = 6;
    public static int initialDamageCooldown = 10;
    public static boolean disableMovementWhileBleeding = false;
    public static boolean bleedingPlayersIgnoredByMobs = false;
    public static List<? extends String> bleedingEffects = List.of("minecraft:slowness:1");
    public static List<? extends String> disabledInputsWhileBleeding = List.of(
            "key.parcool.Enable",
            "key.parcool.Crawl",
            "key.parcool.ClingToCliff",
            "key.parcool.Breakfall",
            "key.parcool.FastRun",
            "key.parcool.Flipping",
            "key.parcool.Vault",
            "key.parcool.Dodge",
            "key.parcool.RideZipline",
            "key.parcool.WallJump",
            "key.parcool.HangDown",
            "key.parcool.WallSlide",
            "key.parcool.HideInBlock",
            "key.parcool.HorizontalWallRun",
            "key.parcool.QuickTurn",
            "key.parcool.openSetting"
    );
    public static boolean enableSuicide = true;
    public static boolean enableBleedoutTimer = true;
    public static boolean enableRagdollCompat = true;

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event)
    {
        if (event.getConfig().getType() != net.neoforged.fml.config.ModConfig.Type.COMMON)
            return;
        if (event.getConfig().getSpec() != SPEC)
            return;

        bleedoutTime = BLEEDOUT_TIME.get();
        reviveTime = REVIVE_TIME.get();
        reviveDistance = REVIVE_DISTANCE.get();
        suicideTime = SUICIDE_TIME.get();
        bleedingHealth = BLEEDING_HEALTH.get();
        initialDamageCooldown = INITIAL_DAMAGE_COOLDOWN.get();
        disableMovementWhileBleeding = DISABLE_MOVEMENT_WHILE_BLEEDING.get();
        bleedingPlayersIgnoredByMobs = BLEEDING_PLAYERS_IGNORED_BY_MOBS.get();
        bleedingEffects = BLEEDING_EFFECTS.get();
        disabledInputsWhileBleeding = DISABLED_INPUTS_WHILE_BLEEDING.get();
        enableSuicide = ENABLE_SUICIDE.get();
        enableBleedoutTimer = ENABLE_BLEEDOUT_TIMER.get();
        enableRagdollCompat = ENABLE_RAGDOLL_COMPAT.get();

        if (event instanceof ModConfigEvent.Reloading)
        {
            com.covalscy.bleedout.network.BleedOutNetwork.sendSettingsToAll();
        }
    }
}
