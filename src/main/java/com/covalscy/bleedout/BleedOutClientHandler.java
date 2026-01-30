package com.covalscy.bleedout;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;

import java.util.Set;

/**
 * Оверлей для отображения состояния кровотечения и прогресс-бара самоубийства
 */
class BleedOutOverlay implements IGuiOverlay
{
    private static int leftClickHoldTicks = 0; // Таймер зажатия ЛКМ
    
    // Локальное отслеживание времени кровотечения
    private static int bleedoutStartTicks = 0; // Начальное количество тиков кровотечения с сервера
    private static long bleedoutStartTime = 0; // Время начала кровотечения в миллисекундах
    private static boolean wasBleeding = false;
    private static boolean isCurrentlyBleeding = false;
    
    // Локальное отслеживание времени возрождения
    private static int reviveStartTicks = 0; // Начальное количество тиков возрождения
    private static long reviveStartTime = 0; // Время начала возрождения в миллисекундах
    private static boolean wasReviving = false;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int width, int height)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null || mc.level == null)
            return;

        // Проверяем состояние кровотечения через PersistentData
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        
        // Проверяем состояние возрождения
        boolean isReviving = player.getPersistentData().getBoolean("bleedout:reviving");
        int reviveTicksFromServer = player.getPersistentData().getInt("bleedout:reviveTicks");
        
        // Отслеживаем начало возрождения
        if (isReviving && !wasReviving)
        {
            reviveStartTicks = reviveTicksFromServer;
            reviveStartTime = System.currentTimeMillis();
            wasReviving = true;
        }
        else if (!isReviving && wasReviving)
        {
            wasReviving = false;
            reviveStartTicks = 0;
        }
        
        // Вычисляем текущее время возрождения локально
        int currentReviveTicks = 0;
        if (isReviving && reviveStartTicks > 0)
        {
            long elapsedMs = System.currentTimeMillis() - reviveStartTime;
            int elapsedTicks = (int)(elapsedMs / 50); // 50ms = 1 tick
            currentReviveTicks = Math.max(0, reviveStartTicks - elapsedTicks);
        }
        
        // Отслеживаем начало/конец кровотечения локально
        // Используем wasBleeding из onClientTickEnd (через wasBleedingLastTick), а не локальную переменную
        boolean wasBleedingLast = wasBleedingLastTick();
        
        // Всегда читаем актуальное значение из PersistentData (может обновиться через пакет)
        int ticksLeftFromServer = player.getPersistentData().getInt("bleedout:ticksLeft");
        
        if (isBleeding && !wasBleedingLast)
        {
            // Начало кровотечения - устанавливаем начальные значения
            if (ticksLeftFromServer > 0)
            {
                bleedoutStartTicks = ticksLeftFromServer;
            }
            else
            {
                // Пакет ещё не пришёл - используем конфиг как fallback
                bleedoutStartTicks = Config.bleedoutTime * 20;
            }
            bleedoutStartTime = System.currentTimeMillis();
            isCurrentlyBleeding = true;
        }
        else if (isBleeding && wasBleedingLast)
        {
            // Кровотечение продолжается - проверяем, не обновилось ли значение через пакет
            // Если значение из PersistentData изменилось (пакет пришёл с задержкой), пересчитываем
            if (ticksLeftFromServer > 0 && (bleedoutStartTicks <= 0 || ticksLeftFromServer != bleedoutStartTicks))
            {
                // Пакет пришёл позже или обновился - пересчитываем время начала
                bleedoutStartTicks = ticksLeftFromServer;
                bleedoutStartTime = System.currentTimeMillis();
            }
            // Если bleedoutStartTicks всё ещё 0 (пакет не пришёл), но мы уже в кровотечении - используем fallback
            else if (bleedoutStartTicks <= 0 && bleedoutStartTime == 0)
            {
                bleedoutStartTicks = Config.bleedoutTime * 20;
                bleedoutStartTime = System.currentTimeMillis();
            }
        }
        else if (!isBleeding)
        {
            // Сбрасываем состояние кровотечения, если isBleeding стал false
            isCurrentlyBleeding = false;
            bleedoutStartTicks = 0;
            bleedoutStartTime = 0;
        }
        
        // Вычисляем текущее время кровотечения локально
        int currentBleedoutTicks = 0;
        if (isBleeding)
        {
            // Если bleedoutStartTicks не установлен, используем конфиг как fallback
            int ticksToUse = bleedoutStartTicks > 0 ? bleedoutStartTicks : (Config.bleedoutTime * 20);
            
            if (ticksToUse > 0 && bleedoutStartTime > 0)
            {
                // Локальный расчёт работает - используем его
                long elapsedMs = System.currentTimeMillis() - bleedoutStartTime;
                int elapsedTicks = (int)(elapsedMs / 50); // 50ms = 1 tick
                currentBleedoutTicks = Math.max(0, ticksToUse - elapsedTicks);
            }
            else if (ticksToUse > 0)
            {
                // bleedoutStartTime ещё не установлен (пакет не пришёл) - используем fallback напрямую
                currentBleedoutTicks = ticksToUse;
            }
            else
            {
                // Последний fallback - конфиг
                currentBleedoutTicks = Config.bleedoutTime * 20;
            }
        }
        
        if (!isBleeding && !isCurrentlyBleeding && !isReviving)
            return;

        int screenWidth = width;
        int screenHeight = height;
        
        PoseStack poseStack = guiGraphics.pose();

        float uiScale = (float) Math.max(0.5, Math.min(3.0, ClientConfig.hudScale));
        
        // === ОТРИСОВКА ДЛЯ КРОВОТОЧАЩЕГО ИГРОКА ===
        if (isBleeding)
        {
            // Используем локально вычисленное время
            int timeLeftSec = (int) Math.ceil(currentBleedoutTicks / 20.0); // Конвертируем тики в секунды, округляем вверх
            
            // Отображаем оставшееся время кровотечения (уменьшенный текст)
            String text = net.minecraft.network.chat.Component.translatable("bleedout.hud.bleeding", timeLeftSec).getString();
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f * uiScale; // Базовый масштаб * масштаб из конфига
            poseStack.scale(textScale, textScale, textScale);
            // Корректируем позицию с учётом масштаба
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            // Поднимаем текст ближе к центру
            float scaledY = (screenHeight / 2.0f + (20 * uiScale)) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FF0000, false); // Красный с прозрачностью 75%
            poseStack.popPose();
            
            // Отображаем подсказку "ЛКМ чтобы умереть"
            String hintText = net.minecraft.network.chat.Component.translatable("bleedout.hud.hint.suicide").getString();
            int hintWidth = mc.font.width(hintText);
            
            poseStack.pushPose();
            float hintScale = 0.6f * uiScale; // Базовый масштаб * масштаб из конфига
            poseStack.scale(hintScale, hintScale, hintScale);
            float hintX = (screenWidth / 2.0f - (hintWidth * hintScale) / 2.0f) / hintScale;
            // Поднимаем подсказку тоже
            float hintY = (screenHeight / 2.0f + (35 * uiScale)) / hintScale; // Ниже основного текста
            poseStack.translate(hintX, hintY, 0);
            guiGraphics.drawString(mc.font, hintText, 0, 0, 0x80808080, false); // Серый полупрозрачный
            poseStack.popPose();
            
            // Отображаем прямоугольный прогресс-бар самоубийства, если ЛКМ зажат
            if (isBleeding && mc.options.keyAttack.isDown() && leftClickHoldTicks > 0)
            {
                int suicideTicks = Config.suicideTime * 20;
                float progress = Math.min(1.0f, (float) leftClickHoldTicks / suicideTicks);
                
                // Рисуем прямоугольный прогресс-бар по центру экрана (ближе к прицелу)
                float centerX = screenWidth / 2.0f;
                float centerY = screenHeight / 2.0f;
                
                // Размеры прогресс-бара (в 3 раза меньше)
                int barWidth = Math.max(1, (int) Math.round(33 * uiScale));
                int barHeight = Math.max(1, (int) Math.round(3 * uiScale));
                int barX = (int)(centerX - barWidth / 2.0f);
                // Поднимаем прогрессбар ближе к центру
                int barY = (int)(centerY + (8 * uiScale)); // Ближе к прицелу
                int progressWidth = (int)(barWidth * progress);
                
                // Включаем блендинг с явным указанием функций (как в minimap)
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                );
                
                // Рисуем фон (темно-серый, менее прозрачный) - 0xAARRGGBB формат
                guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xC0202020);
                
                // Рисуем прогресс (красный, менее прозрачный)
                if (progressWidth > 0) {
                    guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xC0FF0000);
                }
                
                // Рисуем рамку (серая, менее прозрачная)
                int border = Math.max(1, (int) Math.round(1 * uiScale));
                guiGraphics.fill(barX, barY - border, barX + barWidth, barY, 0xC0808080); // Верх
                guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + border, 0xC0808080); // Низ
                guiGraphics.fill(barX - border, barY, barX, barY + barHeight, 0xC0808080); // Лево
                guiGraphics.fill(barX + barWidth, barY, barX + barWidth + border, barY + barHeight, 0xC0808080); // Право
                
                RenderSystem.disableBlend();
            }
        }
        
        // === ОТРИСОВКА ДЛЯ ВОЗРОЖДАЮЩЕГО ИГРОКА ===
        if (isReviving && currentReviveTicks > 0)
        {
            int totalReviveTicks = reviveStartTicks;
            float progress = 1.0f - ((float) currentReviveTicks / totalReviveTicks);
            int timeLeftSec = (int) Math.ceil(currentReviveTicks / 20.0);

            boolean localIsBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
            
            // Отображаем текст возрождения
            String key = localIsBleeding ? "bleedout.hud.being_revived" : "bleedout.hud.reviving";
            String text = net.minecraft.network.chat.Component.translatable(key, timeLeftSec).getString();
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f * uiScale;
            poseStack.scale(textScale, textScale, textScale);
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            // Поднимаем текст немного выше (ближе к центру)
            float scaledY = (screenHeight / 2.0f + (35 * uiScale)) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FFFFFF, false); // Белый с прозрачностью 75%
            poseStack.popPose();
            
            // Рисуем белый прогресс-бар возрождения
            float centerX = screenWidth / 2.0f;
            float centerY = screenHeight / 2.0f;
            
            int barWidth = Math.max(1, (int) Math.round(33 * uiScale));
            int barHeight = Math.max(1, (int) Math.round(3 * uiScale));
            int barX = (int)(centerX - barWidth / 2.0f);
            // Поднимаем прогрессбар ближе к тексту
            int barY = (int)(centerY + (45 * uiScale)); // Ниже текста возрождения, но выше чем было
            int progressWidth = (int)(barWidth * progress);
            
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
            
            // Рисуем фон (темно-серый)
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xC0202020);
            
            // Рисуем прогресс (белый)
            if (progressWidth > 0) {
                guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xC0FFFFFF);
            }
            
            // Рисуем рамку (серая)
            int border = Math.max(1, (int) Math.round(1 * uiScale));
            guiGraphics.fill(barX, barY - border, barX + barWidth, barY, 0xC0808080);
            guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + border, 0xC0808080);
            guiGraphics.fill(barX - border, barY, barX, barY + barHeight, 0xC0808080);
            guiGraphics.fill(barX + barWidth, barY, barX + barWidth + border, barY + barHeight, 0xC0808080);
            
            RenderSystem.disableBlend();
        }
    }
    
    // Публичные методы для доступа к таймеру ЛКМ из обработчика тиков
    public static void incrementLeftClickTicks() {
        leftClickHoldTicks++;
    }
    
    public static void resetLeftClickTicks() {
        leftClickHoldTicks = 0;
    }
    
    public static int getLeftClickTicks() {
        return leftClickHoldTicks;
    }
    
    public static void setBleedoutStartTime(long time) {
        bleedoutStartTime = time;
        isCurrentlyBleeding = true;
    }
    
    public static void stopBleeding() {
        isCurrentlyBleeding = false;
        leftClickHoldTicks = 0;
    }
    
    public static boolean wasBleedingLastTick() {
        return wasBleeding;
    }
    
    public static void setWasBleeding(boolean value) {
        wasBleeding = value;
    }
}

/**
 * Обработчик клиентских тиков для управления состоянием кровотечения
 */
@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
class BleedOutClientHandler
{
    // === ОПТИМИЗАЦИЯ: Кешируем классы для рефлексии ===
    private static Class<?> cachedIGunClass = null;
    private static Class<?> cachedShootKeyClass = null;
    private static boolean tackModChecked = false;

    // === СБРОС ПРИЦЕЛИВАНИЯ (ADS) ===
    private static boolean superbAdsChecked = false;
    private static java.lang.reflect.Field superbZoomField = null;
    private static boolean taczAdsChecked = false;
    private static java.lang.reflect.Method taczFromLocalPlayer = null;
    private static java.lang.reflect.Method taczAimMethod = null;
    
    // === ОПТИМИЗАЦИЯ: Set для быстрой проверки гранат ===
    private static final Set<String> GRENADE_IDS = Set.of(
        "superbwarfare:rgo_grenade",
        "superbwarfare:hand_grenade",
        "superbwarfare:claymore_mine",
        "superbwarfare:tm_62",
        "superbwarfare:c4_bomb",
        "superbwarfare:blu_43_mine",
        "superbwarfare:m18_smoke_grenade"
    );
    
    // Инициализация кешированных классов TACZ
    static {
        if (ModList.get().isLoaded("tacz")) {
            try {
                cachedIGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                cachedShootKeyClass = Class.forName("com.tacz.guns.client.input.ShootKey");
                tackModChecked = true;
            } catch (ClassNotFoundException e) {
                // TACZ не найден или версия несовместима
                tackModChecked = false;
            }
        }
    }

    /**
     * Сбрасывает прицеливание для SuperbWarfare и TACZ (клиент).
     * Вызывается один раз при входе в кровотечение.
     */
    private static void resetAimingForPlayer(LocalPlayer player)
    {
        if (player == null)
            return;

        // SuperbWarfare: ClientEventHandler.zoom = false
        if (ModList.get().isLoaded("superbwarfare")) {
            if (!superbAdsChecked) {
                superbAdsChecked = true;
                try {
                    Class<?> clientEventHandlerClass = Class.forName("com.atsuishio.superbwarfare.event.ClientEventHandler");
                    java.lang.reflect.Field zoomField = clientEventHandlerClass.getDeclaredField("zoom");
                    zoomField.setAccessible(true);
                    superbZoomField = zoomField;
                } catch (Exception ignored) {
                    superbZoomField = null;
                }
            }
            if (superbZoomField != null) {
                try {
                    superbZoomField.setBoolean(null, false);
                } catch (Exception ignored) {
                }
            }
        }

        // TACZ: IClientPlayerGunOperator.fromLocalPlayer(player).aim(false)
        if (ModList.get().isLoaded("tacz")) {
            if (!taczAdsChecked) {
                taczAdsChecked = true;
                try {
                    Class<?> clientOperatorClass = Class.forName("com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator");
                    taczFromLocalPlayer = clientOperatorClass.getDeclaredMethod("fromLocalPlayer", LocalPlayer.class);
                    taczAimMethod = clientOperatorClass.getDeclaredMethod("aim", boolean.class);
                } catch (Exception ignored) {
                    taczFromLocalPlayer = null;
                    taczAimMethod = null;
                }
            }
            if (taczFromLocalPlayer != null && taczAimMethod != null) {
                try {
                    Object operator = taczFromLocalPlayer.invoke(null, player);
                    if (operator != null)
                        taczAimMethod.invoke(operator, false);
                } catch (Exception ignored) {
                }
            }
        }
    }
    
    // Проверка, является ли предмет оружием TACZ (оптимизированная)
    private static boolean isTACZGun(ItemStack stack) {
        if (!tackModChecked || cachedIGunClass == null) return false;
        return cachedIGunClass.isInstance(stack.getItem());
    }
    
    // Проверка, является ли предмет гранатой SuperbWarfare (оптимизированная)
    private static boolean isSuperWarfareGrenade(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare")) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return GRENADE_IDS.contains(itemId.toString());
    }
    
    // Проверка, является ли предмет оружием SuperbWarfare (не гранатой)
    private static boolean isSuperWarfareWeapon(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare")) return false;
        String className = stack.getItem().getClass().getName();
        return className.contains("superbwarfare") && !isSuperWarfareGrenade(stack);
    }
    
    // Блокируем все клики мышью (атаку, использование предметов, взаимодействие) при кровотечении
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractionKeyMapping(InteractionKeyMappingTriggered event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player != null)
        {
            boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
            if (isBleeding)
            {
                // Блокируем ВСЁ при кровотечении (взаимодействие, атаку, использование)
                // Гранаты обрабатываются отдельно в onMouseButton
                event.setCanceled(true);
            }
        }
    }
    
    // Блокируем использование оружия SuperbWarfare и TACZ при кровотечении
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        // Не блокируем клики если открыто меню или GUI
        if (mc.screen != null || player == null)
            return;
        
        int button = event.getButton();
        
        // Всегда разрешаем СКМ (2), кнопку 4 (3), кнопку 5 (4) - независимо от состояния
        if (button == 2 || button == 3 || button == 4)
        {
            return; // Не блокируем эти кнопки
        }
        
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        if (isBleeding)
        {
            ItemStack mainHand = player.getMainHandItem();
            
            // Проверяем, держит ли игрок что-то из SuperbWarfare в главной руке
            if (isSuperWarfareGrenade(mainHand))
            {
                // Для гранаты разрешаем только ЛКМ (button 0)
                // ПКМ (1) блокируем, остальные (2, 3, 4) уже разрешены выше
                if (button != 0)
                {
                    event.setCanceled(true);
                    return;
                }
            }
            else if (isSuperWarfareWeapon(mainHand) || isTACZGun(mainHand))
            {
                // Для оружия блокируем ЛКМ (0) и ПКМ (1)
                // СКМ (2), кнопка 4 (3), кнопка 5 (4) уже разрешены выше
                if (button == 0 || button == 1)
                {
                    event.setCanceled(true);
                    return;
                }
            }
            else
            {
                // Если в руках нет оружия/гранат, но игрок в кровотечении
                // Блокируем только ПКМ (1), ЛКМ (0) разрешён для самоубийства
                // СКМ (2), кнопка 4 (3), кнопка 5 (4) уже разрешены выше
                if (button == 1)
                {
                    event.setCanceled(true);
                    return;
                }
            }
        }
    }
    

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        
        Player player = event.player;
        if (player == null || !player.level().isClientSide)
            return;
        
        // Проверяем состояние кровотечения через PersistentData
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        
        // Устанавливаем позу на клиенте - ВСЕГДА если isBleeding
        if (isBleeding && player.getPose() != net.minecraft.world.entity.Pose.SWIMMING)
        {
            player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START)
            return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null || mc.level == null)
            return;

        // Проверяем состояние кровотечения через PersistentData
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        
        // Принудительно сбрасываем кнопки при кровотечении
        if (isBleeding)
        {
            ItemStack mainHand = player.getMainHandItem();
            
            // Блокируем прыжки - сбрасываем клавишу пробела
            if (mc.options.keyJump.isDown())
            {
                mc.options.keyJump.setDown(false);
            }
            
            // Проверяем TACZ оружие и сбрасываем SHOOT_KEY (оптимизированная проверка)
            if (tackModChecked && cachedShootKeyClass != null && isTACZGun(mainHand))
            {
                try
                {
                    // Получаем SHOOT_KEY из TACZ и принудительно "отпускаем" его
                    Object shootKey = cachedShootKeyClass.getField("SHOOT_KEY").get(null);
                    if (shootKey instanceof net.minecraft.client.KeyMapping keyMapping)
                    {
                        // Принудительно устанавливаем состояние "не нажата"
                        keyMapping.setDown(false);
                    }
                    
                    // Также сбрасываем стандартные кнопки атаки и использования
                    mc.options.keyAttack.setDown(false);
                    mc.options.keyUse.setDown(false);
                }
                catch (Exception e)
                {
                    // Игнорируем ошибки рефлексии
                }
            }
        }
    }
    
    @SubscribeEvent
    public static void onClientTickEnd(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null || mc.level == null)
            return;

        // Проверяем состояние кровотечения через PersistentData
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean wasBleeding = BleedOutOverlay.wasBleedingLastTick();
        
        // Отслеживаем начало кровотечения только для сброса прицеливания;
        // сам оверлей сам определяет момент старта по своему состоянию.
        if (isBleeding && !wasBleeding && player instanceof LocalPlayer localPlayer)
        {
            resetAimingForPlayer(localPlayer);
        }
        
        // Обрабатываем ЛКМ для самоубийства (только если НЕ держим оружие)
        if (isBleeding)
        {
            ItemStack mainHand = player.getMainHandItem();
            
            // Оптимизированная проверка оружия
            boolean holdingWeapon = isTACZGun(mainHand) || isSuperWarfareWeapon(mainHand);
            
            // Обрабатываем самоубийство только если НЕ держим оружие
            if (!holdingWeapon && mc.options.keyAttack.isDown())
            {
                int suicideTicks = Config.suicideTime * 20;
                if (BleedOutOverlay.getLeftClickTicks() >= suicideTicks)
                {
                    // Отправляем запрос на сервер
                    com.covalscy.bleedout.network.BleedOutNetwork.INSTANCE.sendToServer(
                        new com.covalscy.bleedout.network.SuicideRequestPacket()
                    );
                    BleedOutOverlay.resetLeftClickTicks();
                }
                else
                {
                    BleedOutOverlay.incrementLeftClickTicks();
                }
            }
            else
            {
                BleedOutOverlay.resetLeftClickTicks();
            }
        }
        else
        {
            BleedOutOverlay.resetLeftClickTicks();
        }
        
        BleedOutOverlay.setWasBleeding(isBleeding);
    }
}
