package com.covalscy.bleedout;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

/**
 * Оверлей для отображения состояния кровотечения и прогресс-бара самоубийства
 */
class BleedOutOverlay implements IGuiOverlay
{
    private static int leftClickHoldTicks = 0; // Таймер зажатия ЛКМ
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
        
        if (!isBleeding && !isCurrentlyBleeding && !isReviving)
            return;
        
        // Обновляем состояние
        if (isBleeding && !isCurrentlyBleeding)
        {
            bleedoutStartTime = System.currentTimeMillis();
            isCurrentlyBleeding = true;
        }
        else if (!isBleeding && isCurrentlyBleeding)
        {
            isCurrentlyBleeding = false;
        }

        int screenWidth = width;
        int screenHeight = height;
        
        PoseStack poseStack = guiGraphics.pose();
        
        // === ОТРИСОВКА ДЛЯ КРОВОТОЧАЩЕГО ИГРОКА ===
        if (isBleeding || isCurrentlyBleeding)
        {
            // Вычисляем оставшееся время в миллисекундах
            long currentTime = System.currentTimeMillis();
            long elapsedTime = currentTime - bleedoutStartTime;
            int totalTimeMs = Config.bleedoutTime * 1000;
            long timeLeftMs = Math.max(0, totalTimeMs - elapsedTime);
            int timeLeftSec = (int) Math.ceil(timeLeftMs / 1000.0); // Округляем вверх
            
            // Отображаем оставшееся время кровотечения (уменьшенный текст)
            String text = "Кровотечение: " + timeLeftSec + " сек";
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f; // Уменьшаем текст на 30%
            poseStack.scale(textScale, textScale, textScale);
            // Корректируем позицию с учётом масштаба
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            float scaledY = (screenHeight / 2.0f + 30) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FF0000, false); // Красный с прозрачностью 75%
            poseStack.popPose();
            
            // Отображаем подсказку "ЛКМ чтобы умереть"
            String hintText = "ЛКМ чтобы умереть";
            int hintWidth = mc.font.width(hintText);
            
            poseStack.pushPose();
            float hintScale = 0.6f; // Еще меньше текст
            poseStack.scale(hintScale, hintScale, hintScale);
            float hintX = (screenWidth / 2.0f - (hintWidth * hintScale) / 2.0f) / hintScale;
            float hintY = (screenHeight / 2.0f + 45) / hintScale; // Ниже основного текста
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
                int barWidth = 33;
                int barHeight = 3;
                int barX = (int)(centerX - barWidth / 2.0f);
                int barY = (int)(centerY + 15); // Ближе к прицелу
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
                guiGraphics.fill(barX, barY - 1, barX + barWidth, barY, 0xC0808080); // Верх
                guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + 1, 0xC0808080); // Низ
                guiGraphics.fill(barX - 1, barY, barX, barY + barHeight, 0xC0808080); // Лево
                guiGraphics.fill(barX + barWidth, barY, barX + barWidth + 1, barY + barHeight, 0xC0808080); // Право
                
                RenderSystem.disableBlend();
            }
        }
        
        // === ОТРИСОВКА ДЛЯ ВОЗРОЖДАЮЩЕГО ИГРОКА ===
        if (isReviving && currentReviveTicks > 0)
        {
            int totalReviveTicks = reviveStartTicks;
            float progress = 1.0f - ((float) currentReviveTicks / totalReviveTicks);
            int timeLeftSec = (int) Math.ceil(currentReviveTicks / 20.0);
            
            // Отображаем текст возрождения
            String text = "Возрождение: " + timeLeftSec + " сек";
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f;
            poseStack.scale(textScale, textScale, textScale);
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            float scaledY = (screenHeight / 2.0f + 50) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FFFFFF, false); // Белый с прозрачностью 75%
            poseStack.popPose();
            
            // Рисуем белый прогресс-бар возрождения
            float centerX = screenWidth / 2.0f;
            float centerY = screenHeight / 2.0f;
            
            int barWidth = 33;
            int barHeight = 3;
            int barX = (int)(centerX - barWidth / 2.0f);
            int barY = (int)(centerY + 65); // Ниже текста возрождения
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
            guiGraphics.fill(barX, barY - 1, barX + barWidth, barY, 0xC0808080);
            guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + 1, 0xC0808080);
            guiGraphics.fill(barX - 1, barY, barX, barY + barHeight, 0xC0808080);
            guiGraphics.fill(barX + barWidth, barY, barX + barWidth + 1, barY + barHeight, 0xC0808080);
            
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
                // Проверяем, держит ли игрок гранату в главной руке
                if (event.isAttack() && ModList.get().isLoaded("superbwarfare"))
                {
                    ItemStack mainHand = player.getMainHandItem();
                    ResourceLocation mainHandId = BuiltInRegistries.ITEM.getKey(mainHand.getItem());
                    String mainHandIdStr = mainHandId.toString();
                    
                    boolean isGrenade = mainHandIdStr.equals("superbwarfare:rgo_grenade") ||
                                       mainHandIdStr.equals("superbwarfare:hand_grenade") ||
                                       mainHandIdStr.equals("superbwarfare:m18_smoke_grenade");
                    
                    if (isGrenade)
                    {
                        // Разрешаем атаку с гранатой (ЛКМ)
                        return;
                    }
                }
                
                // Блокируем ВСЁ остальное (как в PlayerRevive)
                event.setCanceled(true);
            }
        }
    }
    
    // Блокируем использование оружия SuperbWarfare при кровотечении
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        // Не блокируем клики если открыто меню или GUI
        if (mc.screen != null || player == null)
            return;
        
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        if (isBleeding)
        {
            // Проверяем, держит ли игрок что-то из SuperbWarfare в главной руке
            if (ModList.get().isLoaded("superbwarfare"))
            {
                ItemStack mainHand = player.getMainHandItem();
                
                // Получаем ID предмета в главной руке
                ResourceLocation mainHandId = BuiltInRegistries.ITEM.getKey(mainHand.getItem());
                String mainHandIdStr = mainHandId.toString();
                
                // Проверяем, является ли предмет в главной руке оружием SuperbWarfare
                String mainHandClass = mainHand.getItem().getClass().getName();
                
                if (mainHandClass.contains("superbwarfare"))
                {
                    // Проверяем, является ли это гранатой (только для главной руки)
                    boolean isGrenade = mainHandIdStr.equals("superbwarfare:rgo_grenade") ||
                                       mainHandIdStr.equals("superbwarfare:hand_grenade") ||
                                       mainHandIdStr.equals("superbwarfare:claymore_mine") ||
                                       mainHandIdStr.equals("superbwarfare:tm_62") ||
                                       mainHandIdStr.equals("superbwarfare:c4_bomb") ||
                                       mainHandIdStr.equals("superbwarfare:blu_43_mine") ||
                                       mainHandIdStr.equals("superbwarfare:m18_smoke_grenade");
                    
                    if (isGrenade)
                    {
                        // Для гранаты разрешаем только ЛКМ (button 0)
                        if (event.getButton() != 0) // Если не ЛКМ - блокируем
                        {
                            event.setCanceled(true);
                            return;
                        }
                    }
                    else
                    {
                        // Для остального оружия блокируем все клики
                        event.setCanceled(true);
                        return;
                    }
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

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
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
        
        // Отслеживаем начало кровотечения
        if (isBleeding && !wasBleeding)
        {
            BleedOutOverlay.setBleedoutStartTime(System.currentTimeMillis());
        }
        
        // Отслеживаем конец кровотечения
        if (!isBleeding && wasBleeding)
        {
            BleedOutOverlay.stopBleeding();
        }
        
        // Обрабатываем ЛКМ
        if (isBleeding)
        {
            if (mc.options.keyAttack.isDown())
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
