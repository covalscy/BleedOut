package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.SuicideRequestPacket;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Оверлей для отображения состояния кровотечения и прогресс-бара самоубийства в оригинальном стиле BleedOut
 */
class BleedOutOverlay implements LayeredDraw.Layer
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int leftClickHoldTicks = 0;
    
    private static int bleedoutStartTicks = 0;
    private static long bleedoutStartTime = 0;
    private static boolean isCurrentlyBleeding = false;
    
    private static int reviveStartTicks = 0;
    private static long reviveStartTime = 0;
    private static boolean wasReviving = false;
    
    private static double lastSinValue = 0.0;
    private static long lastSoundPlayTime = 0;
    private static boolean wasBleedingLastTick = false;

    public static boolean wasBleedingLastTick()
    {
        return wasBleedingLastTick;
    }

    public static void setWasBleedingLastTick(boolean val)
    {
        wasBleedingLastTick = val;
    }

    public static int getLeftClickTicks()
    {
        return leftClickHoldTicks;
    }

    public static void incrementLeftClickTicks()
    {
        leftClickHoldTicks++;
    }

    public static void resetLeftClickTicks()
    {
        leftClickHoldTicks = 0;
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null || mc.level == null)
            return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean isReviving = player.getPersistentData().getBoolean("bleedout:reviving");
        int reviveTicksFromServer = player.getPersistentData().getInt("bleedout:reviveTicks");
        
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
        
        int currentReviveTicks = 0;
        if (isReviving && reviveStartTicks > 0)
        {
            long elapsedMs = System.currentTimeMillis() - reviveStartTime;
            int elapsedTicks = (int)(elapsedMs / 50);
            currentReviveTicks = Math.max(0, reviveStartTicks - elapsedTicks);
        }
        
        boolean wasBleedingLast = wasBleedingLastTick();
        int ticksLeftFromServer = player.getPersistentData().getInt("bleedout:ticksLeft");
        
        if (isBleeding && !wasBleedingLast)
        {
            if (ticksLeftFromServer > 0)
            {
                bleedoutStartTicks = ticksLeftFromServer;
            }
            else
            {
                bleedoutStartTicks = Config.bleedoutTime * 20;
            }
            bleedoutStartTime = System.currentTimeMillis();
            isCurrentlyBleeding = true;
            lastSinValue = -1.0;
        }
        else if (isBleeding && wasBleedingLast)
        {
            if (ticksLeftFromServer > 0 && (bleedoutStartTicks <= 0 || ticksLeftFromServer != bleedoutStartTicks))
            {
                bleedoutStartTicks = ticksLeftFromServer;
                bleedoutStartTime = System.currentTimeMillis();
            }
            else if (bleedoutStartTicks <= 0 && bleedoutStartTime == 0)
            {
                bleedoutStartTicks = Config.bleedoutTime * 20;
                bleedoutStartTime = System.currentTimeMillis();
            }
        }
        else if (!isBleeding)
        {
            isCurrentlyBleeding = false;
            bleedoutStartTicks = 0;
            bleedoutStartTime = 0;
            lastSinValue = 0.0;
            lastSoundPlayTime = 0;
        }
        
        int currentBleedoutTicks = 0;
        if (isBleeding)
        {
            int ticksToUse = bleedoutStartTicks > 0 ? bleedoutStartTicks : (Config.bleedoutTime * 20);
            
            if (ticksToUse > 0 && bleedoutStartTime > 0)
            {
                long elapsedMs = System.currentTimeMillis() - bleedoutStartTime;
                int elapsedTicks = (int)(elapsedMs / 50);
                currentBleedoutTicks = Math.max(0, ticksToUse - elapsedTicks);
            }
            else if (ticksToUse > 0)
            {
                currentBleedoutTicks = ticksToUse;
            }
            else
            {
                currentBleedoutTicks = Config.bleedoutTime * 20;
            }
        }
        
        if (!isBleeding && !isCurrentlyBleeding && !isReviving)
            return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        PoseStack poseStack = guiGraphics.pose();
        float uiScale = (float) Math.max(0.5, Math.min(3.0, ClientConfig.hudScale));
        
        // === ОТРИСОВКА ДЛЯ КРОВОТОЧАЩЕГО ИГРОКА ===
        if (isBleeding)
        {
            float bleedoutProgress;
            if (Config.enableBleedoutTimer)
            {
                int totalBleedoutTicks = bleedoutStartTicks > 0 ? bleedoutStartTicks : (Config.bleedoutTime * 20);
                bleedoutProgress = totalBleedoutTicks > 0 ? 1.0f - ((float) currentBleedoutTicks / totalBleedoutTicks) : 0.0f;
                bleedoutProgress = Math.max(0.0f, Math.min(1.0f, bleedoutProgress));
            }
            else
            {
                long elapsedMs = System.currentTimeMillis() - bleedoutStartTime;
                bleedoutProgress = Math.min(1.0f, (float) elapsedMs / 5000.0f) * 0.25f;
            }
            
            float baseDarkness = bleedoutProgress * 1.0f;
            float pulseProgress = bleedoutProgress * 0.6f;
            long currentTime = System.currentTimeMillis();
            double heartbeatFrequency = 0.2;
            double heartbeatAmplitude = 0.1 + (pulseProgress * 0.3); 
            double sinValue = Math.sin(currentTime * heartbeatFrequency * 0.01);
            double baseOffset = pulseProgress * 0.002; 
            double heartbeatPulse = baseOffset + (sinValue * heartbeatAmplitude);
            
            if (player instanceof LocalPlayer)
            {
                boolean crossedZeroUp = (sinValue > 0.05 && lastSinValue < -0.05);
                boolean atPeak = (sinValue > 0.9 && lastSinValue <= 0.9);
                boolean shouldPlaySound = crossedZeroUp || atPeak;
                boolean soundEnabled = ClientConfig.enableHeartbeatSound;
                boolean enoughTimePassed = (currentTime - lastSoundPlayTime) >= 500;
                
                if (shouldPlaySound && enoughTimePassed && soundEnabled)
                {
                    float volume = 0.6f + (pulseProgress * 0.2f);
                    float pitch = 1.0f;
                    
                    if (ModSounds.HEARTBEAT.get() != null)
                    {
                        mc.level.playSound(
                            player,
                            player.getX(), player.getY(), player.getZ(),
                            ModSounds.HEARTBEAT.get(),
                            SoundSource.PLAYERS,
                            volume,
                            pitch
                        );
                    }
                    lastSoundPlayTime = currentTime;
                }
            }
            
            lastSinValue = sinValue;
            
            float finalDarkness = (float) Math.max(0.0, Math.min(1.0, baseDarkness + heartbeatPulse));
            int alpha = (int) (finalDarkness * 255);
            int darkColor = (alpha << 24) | 0x000000;
            
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            guiGraphics.fill(0, 0, screenWidth, screenHeight, darkColor);
            RenderSystem.disableBlend();
            
            // Текст таймера кровотечения (красный с полупрозрачностью 75%)
            String text;
            if (Config.enableBleedoutTimer)
            {
                int timeLeftSec = (int) Math.ceil(currentBleedoutTicks / 20.0);
                text = Component.translatable("bleedout.hud.bleeding", timeLeftSec).getString();
            }
            else
            {
                text = Component.translatable("bleedout.hud.bleeding_no_timer").getString();
            }
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f * uiScale;
            poseStack.scale(textScale, textScale, textScale);
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            float scaledY = (screenHeight / 2.0f + (20 * uiScale)) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FF0000, false);
            poseStack.popPose();
            
            // Подсказка для самоубийства (серый полупрозрачный мелкий текст)
            if (Config.enableSuicide)
            {
                String hintText = Component.translatable("bleedout.hud.hint.suicide").getString();
                int hintWidth = mc.font.width(hintText);
                
                poseStack.pushPose();
                float hintScale = 0.6f * uiScale;
                poseStack.scale(hintScale, hintScale, hintScale);
                float hintX = (screenWidth / 2.0f - (hintWidth * hintScale) / 2.0f) / hintScale;
                float hintY = (screenHeight / 2.0f + (35 * uiScale)) / hintScale;
                poseStack.translate(hintX, hintY, 0);
                guiGraphics.drawString(mc.font, hintText, 0, 0, 0x80808080, false);
                poseStack.popPose();
            }
            
            // Прямоугольный миниатюрный прогресс-бар самоубийства (только если удерживается ЛКМ)
            if (Config.enableSuicide && isBleeding && mc.options.keyAttack.isDown() && leftClickHoldTicks > 0)
            {
                int suicideTicks = Config.suicideTime * 20;
                float progress = Math.min(1.0f, (float) leftClickHoldTicks / suicideTicks);
                
                float centerX = screenWidth / 2.0f;
                float centerY = screenHeight / 2.0f;
                
                int barWidth = Math.max(1, (int) Math.round(33 * uiScale));
                int barHeight = Math.max(1, (int) Math.round(3 * uiScale));
                int barX = (int)(centerX - barWidth / 2.0f);
                int barY = (int)(centerY + (8 * uiScale));
                int progressWidth = (int)(barWidth * progress);
                
                RenderSystem.enableBlend();
                RenderSystem.blendFunc(
                    GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
                );
                
                guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xC0202020);
                
                if (progressWidth > 0) {
                    guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xC0FF0000);
                }
                
                int border = Math.max(1, (int) Math.round(1 * uiScale));
                guiGraphics.fill(barX, barY - border, barX + barWidth, barY, 0xC0808080);
                guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + border, 0xC0808080);
                guiGraphics.fill(barX - border, barY, barX, barY + barHeight, 0xC0808080);
                guiGraphics.fill(barX + barWidth, barY, barX + barWidth + border, barY + barHeight, 0xC0808080);
                
                RenderSystem.disableBlend();
            }
        }
        
        // === ОТРИСОВКА ДЛЯ ВОЗРОЖДАЮЩЕГО И ВОЗРОЖДАЕМОГО ИГРОКА ===
        if (isReviving && currentReviveTicks > 0)
        {
            int totalReviveTicks = reviveStartTicks > 0 ? reviveStartTicks : (Config.reviveTime * 20);
            float progress = 1.0f - ((float) currentReviveTicks / totalReviveTicks);
            int timeLeftSec = (int) Math.ceil(currentReviveTicks / 20.0);

            boolean localIsBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
            
            String key = localIsBleeding ? "bleedout.hud.being_revived" : "bleedout.hud.reviving";
            String text = Component.translatable(key, timeLeftSec).getString();
            int textWidth = mc.font.width(text);
            
            poseStack.pushPose();
            float textScale = 0.7f * uiScale;
            poseStack.scale(textScale, textScale, textScale);
            float scaledX = (screenWidth / 2.0f - (textWidth * textScale) / 2.0f) / textScale;
            float scaledY = (screenHeight / 2.0f + (35 * uiScale)) / textScale;
            poseStack.translate(scaledX, scaledY, 0);
            guiGraphics.drawString(mc.font, text, 0, 0, 0xC0FFFFFF, false);
            poseStack.popPose();
            
            float centerX = screenWidth / 2.0f;
            float centerY = screenHeight / 2.0f;
            
            int barWidth = Math.max(1, (int) Math.round(33 * uiScale));
            int barHeight = Math.max(1, (int) Math.round(3 * uiScale));
            int barX = (int)(centerX - barWidth / 2.0f);
            int barY = (int)(centerY + (45 * uiScale));
            int progressWidth = (int)(barWidth * progress);
            
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
            );
            
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xC0202020);
            
            if (progressWidth > 0) {
                guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xC0FFFFFF);
            }
            
            int border = Math.max(1, (int) Math.round(1 * uiScale));
            guiGraphics.fill(barX, barY - border, barX + barWidth, barY, 0xC0808080);
            guiGraphics.fill(barX, barY + barHeight, barX + barWidth, barY + barHeight + border, 0xC0808080);
            guiGraphics.fill(barX - border, barY, barX, barY + barHeight, 0xC0808080);
            guiGraphics.fill(barX + barWidth, barY, barX + barWidth + border, barY + barHeight, 0xC0808080);
            
            RenderSystem.disableBlend();
        }
    }
}

@EventBusSubscriber(modid = BleedOutMod.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class BleedOutClientHandler
{
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.level == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        BleedOutOverlay.setWasBleedingLastTick(isBleeding);

        if (isBleeding)
        {
            player.setForcedPose(Pose.SWIMMING);
            disableInputsForBleedingPlayer(mc);

            if (Config.enableSuicide && mc.options.keyAttack.isDown())
            {
                int suicideTicks = Config.suicideTime * 20;
                if (BleedOutOverlay.getLeftClickTicks() >= suicideTicks)
                {
                    BleedOutNetwork.sendToServer(new SuicideRequestPacket());
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
            if (player.getForcedPose() == Pose.SWIMMING)
            {
                player.setForcedPose(null);
            }
        }
    }

    private static void disableInputsForBleedingPlayer(Minecraft mc)
    {
        List<? extends String> disabledInputs = Config.disabledInputsWhileBleeding;
        if (disabledInputs == null || disabledInputs.isEmpty()) return;

        boolean disableAllKeyboard = disabledInputs.contains("ALL_KEYBOARD");
        boolean disableAll = disabledInputs.contains("ALL");

        if (disableAll || disableAllKeyboard)
        {
            KeyMapping.releaseAll();
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return;

        if (player.getPersistentData().getBoolean("bleedout:bleeding"))
        {
            List<? extends String> disabledInputs = Config.disabledInputsWhileBleeding;
            if (disabledInputs != null && (disabledInputs.contains("ALL") || disabledInputs.contains("ALL_KEYBOARD")))
            {
                KeyMapping.releaseAll();
            }
        }
    }
}
