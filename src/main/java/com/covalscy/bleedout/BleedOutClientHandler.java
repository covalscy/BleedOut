package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.SuicideRequestPacket;
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
 * Оверлей для отображения состояния кровотечения и прогресс-бара самоубийства
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

        int width = mc.getWindow().getGuiScaledWidth();
        int height = mc.getWindow().getGuiScaledHeight();
        
        PoseStack poseStack = guiGraphics.pose();
        float uiScale = (float) Math.max(0.5, Math.min(3.0, ClientConfig.hudScale));
        
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
            
            float currentDarkness = (float) Math.max(0.0, Math.min(1.0, baseDarkness + heartbeatPulse));
            int alpha = (int) (currentDarkness * 255);
            int color = (alpha << 24);
            
            guiGraphics.fill(0, 0, width, height, color);
            
            poseStack.pushPose();
            poseStack.scale(uiScale, uiScale, 1.0f);
            
            int scaledWidth = (int) (width / uiScale);
            int scaledHeight = (int) (height / uiScale);
            
            int textY = scaledHeight / 2 - 40;
            
            int secondsLeft = Math.max(0, (currentBleedoutTicks + 19) / 20);
            
            Component bleedText;
            if (Config.enableBleedoutTimer)
            {
                bleedText = Component.translatable("bleedout.hud.bleeding_timer", secondsLeft);
            }
            else
            {
                bleedText = Component.translatable("bleedout.hud.bleeding_no_timer");
            }
            
            int textWidth = mc.font.width(bleedText);
            int textX = (scaledWidth - textWidth) / 2;
            
            guiGraphics.drawString(mc.font, bleedText, textX + 1, textY + 1, 0xAA000000, false);
            guiGraphics.drawString(mc.font, bleedText, textX, textY, 0xFFFF3333, false);

            if (Config.enableSuicide)
            {
                Component suicideText = Component.translatable("bleedout.hud.suicide_prompt");
                int suicideTextWidth = mc.font.width(suicideText);
                int suicideTextX = (scaledWidth - suicideTextWidth) / 2;
                int suicideTextY = textY + 15;
                
                guiGraphics.drawString(mc.font, suicideText, suicideTextX + 1, suicideTextY + 1, 0xAA000000, false);
                guiGraphics.drawString(mc.font, suicideText, suicideTextX, suicideTextY, 0xFFFFAA00, false);

                int suicideMaxTicks = Config.suicideTime * 20;
                float progress = Math.min(1.0f, (float) leftClickHoldTicks / suicideMaxTicks);
                
                if (progress > 0)
                {
                    int barWidth = 100;
                    int barHeight = 8;
                    int barX = (scaledWidth - barWidth) / 2;
                    int barY = suicideTextY + 15;
                    
                    guiGraphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xFF000000);
                    guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF555555);
                    
                    int progressWidth = (int) (barWidth * progress);
                    if (progressWidth > 0)
                    {
                        guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xFFFF0000);
                    }
                }
            }
            
            poseStack.popPose();
        }

        if (isReviving)
        {
            poseStack.pushPose();
            poseStack.scale(uiScale, uiScale, 1.0f);
            
            int scaledWidth = (int) (width / uiScale);
            int scaledHeight = (int) (height / uiScale);
            
            int reviveY = isBleeding ? (scaledHeight / 2 + 20) : (scaledHeight / 2 - 20);
            
            int totalReviveTicks = reviveStartTicks > 0 ? reviveStartTicks : (Config.reviveTime * 20);
            int currentTicks = currentReviveTicks > 0 ? currentReviveTicks : reviveTicksFromServer;
            
            float reviveProgress = 1.0f - ((float) currentTicks / totalReviveTicks);
            reviveProgress = Math.max(0.0f, Math.min(1.0f, reviveProgress));
            
            int secondsLeft = Math.max(0, (currentTicks + 19) / 20);
            
            Component reviveText = Component.translatable("bleedout.hud.reviving", secondsLeft);
            int reviveTextWidth = mc.font.width(reviveText);
            int reviveTextX = (scaledWidth - reviveTextWidth) / 2;
            
            guiGraphics.drawString(mc.font, reviveText, reviveTextX + 1, reviveY + 1, 0xAA000000, false);
            guiGraphics.drawString(mc.font, reviveText, reviveTextX, reviveY, 0xFF55FF55, false);

            int barWidth = 120;
            int barHeight = 10;
            int barX = (scaledWidth - barWidth) / 2;
            int barY = reviveY + 15;
            
            guiGraphics.fill(barX - 1, barY - 1, barX + barWidth + 1, barY + barHeight + 1, 0xFF000000);
            guiGraphics.fill(barX, barY, barX + barWidth, barY + barHeight, 0xFF333333);
            
            int progressWidth = (int) (barWidth * reviveProgress);
            if (progressWidth > 0)
            {
                guiGraphics.fill(barX, barY, barX + progressWidth, barY + barHeight, 0xFF00FF00);
            }
            
            poseStack.popPose();
        }
    }

    public static void tickLeftClickHold()
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null)
        {
            leftClickHoldTicks = 0;
            return;
        }

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        
        if (isBleeding && Config.enableSuicide && mc.options.keyAttack.isDown())
        {
            leftClickHoldTicks++;
            
            int suicideMaxTicks = Config.suicideTime * 20;
            if (leftClickHoldTicks >= suicideMaxTicks)
            {
                BleedOutNetwork.sendToServer(new SuicideRequestPacket());
                leftClickHoldTicks = 0;
            }
        }
        else
        {
            leftClickHoldTicks = 0;
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

        if (player == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        BleedOutOverlay.setWasBleedingLastTick(isBleeding);

        BleedOutOverlay.tickLeftClickHold();

        if (isBleeding)
        {
            player.setForcedPose(Pose.SWIMMING);
            disableInputsForBleedingPlayer(mc);
        }
        else if (player.getForcedPose() == Pose.SWIMMING)
        {
            player.setForcedPose(null);
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
