package com.covalscy.bleedout;

import com.covalscy.bleedout.network.BleedOutNetwork;
import com.covalscy.bleedout.network.SuicideRequestPacket;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.util.Set;

class BleedOutOverlay implements LayeredDraw.Layer
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int leftClickHoldTicks = 0;
    private static boolean suicideSent = false;
    
    private static int bleedoutStartTicks = 0;
    private static long bleedoutStartTime = 0;
    private static boolean isCurrentlyBleeding = false;
    
    private static int reviveStartTicks = 0;
    private static long reviveStartTime = 0;
    private static boolean wasReviving = false;
    private static int serverReviveTicksLeft = 0;
    private static long lastRevivePacketTime = 0;
    private static boolean activeReviving = false;
    
    private static double lastSinValue = 0.0;
    private static long lastSoundPlayTime = 0;
    private static boolean wasBleedingLastTick = false;

    public static void onRevivePacket(boolean isReviving, int ticksLeft)
    {
        if (isReviving)
        {
            boolean isNewRevive = !activeReviving || !wasReviving || reviveStartTicks <= 0 || (ticksLeft > serverReviveTicksLeft + 5);
            activeReviving = true;
            wasReviving = true;
            serverReviveTicksLeft = ticksLeft;
            lastRevivePacketTime = System.currentTimeMillis();

            if (isNewRevive)
            {
                reviveStartTicks = ticksLeft > 0 ? ticksLeft : (SyncedSettings.reviveTime() * 20);
                reviveStartTime = System.currentTimeMillis();
            }
        }
        else
        {
            resetRevive();
        }
    }

    public static void resetRevive()
    {
        activeReviving = false;
        wasReviving = false;
        reviveStartTicks = 0;
        reviveStartTime = 0;
        serverReviveTicksLeft = 0;
        lastRevivePacketTime = 0;
    }

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

    public static boolean isSuicideSent()
    {
        return suicideSent;
    }

    public static void setSuicideSent(boolean val)
    {
        suicideSent = val;
    }

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        
        if (player == null || mc.level == null)
            return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean isReviving = activeReviving || player.getPersistentData().getBoolean("bleedout:reviving");
        int reviveTicksFromServer = player.getPersistentData().getInt("bleedout:reviveTicks");
        
        if (isReviving && !wasReviving)
        {
            int initialTicks = serverReviveTicksLeft > 0 ? serverReviveTicksLeft : reviveTicksFromServer;
            reviveStartTicks = initialTicks > 0 ? initialTicks : (SyncedSettings.reviveTime() * 20);
            reviveStartTime = System.currentTimeMillis();
            serverReviveTicksLeft = reviveStartTicks;
            lastRevivePacketTime = System.currentTimeMillis();
            wasReviving = true;
            activeReviving = true;
        }
        else if (isReviving)
        {
            if (reviveStartTicks <= 0 || reviveStartTime == 0)
            {
                int initialTicks = serverReviveTicksLeft > 0 ? serverReviveTicksLeft : reviveTicksFromServer;
                reviveStartTicks = initialTicks > 0 ? initialTicks : (SyncedSettings.reviveTime() * 20);
                reviveStartTime = System.currentTimeMillis();
                serverReviveTicksLeft = reviveStartTicks;
                lastRevivePacketTime = System.currentTimeMillis();
            }
            else if (reviveTicksFromServer > 0 && reviveTicksFromServer > serverReviveTicksLeft + 5)
            {
                reviveStartTicks = reviveTicksFromServer;
                reviveStartTime = System.currentTimeMillis();
                serverReviveTicksLeft = reviveTicksFromServer;
                lastRevivePacketTime = System.currentTimeMillis();
            }
        }
        else if (!isReviving && wasReviving)
        {
            resetRevive();
        }
        
        int currentReviveTicks = 0;
        if (isReviving)
        {
            if (lastRevivePacketTime > 0 && serverReviveTicksLeft > 0)
            {
                long elapsedMs = System.currentTimeMillis() - lastRevivePacketTime;
                int elapsedTicks = (int)(elapsedMs / 50);
                currentReviveTicks = Math.max(0, serverReviveTicksLeft - elapsedTicks);
            }
            else if (reviveStartTime > 0 && reviveStartTicks > 0)
            {
                long elapsedMs = System.currentTimeMillis() - reviveStartTime;
                int elapsedTicks = (int)(elapsedMs / 50);
                currentReviveTicks = Math.max(0, reviveStartTicks - elapsedTicks);
            }
            else if (reviveTicksFromServer > 0)
            {
                currentReviveTicks = reviveTicksFromServer;
            }
            else
            {
                currentReviveTicks = SyncedSettings.reviveTime() * 20;
            }
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
                bleedoutStartTicks = SyncedSettings.bleedoutTime() * 20;
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
                bleedoutStartTicks = SyncedSettings.bleedoutTime() * 20;
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
            int ticksToUse = bleedoutStartTicks > 0 ? bleedoutStartTicks : (SyncedSettings.bleedoutTime() * 20);
            
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
                currentBleedoutTicks = SyncedSettings.bleedoutTime() * 20;
            }
        }
        
        if (!isBleeding && !isCurrentlyBleeding && !isReviving)
            return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        
        PoseStack poseStack = guiGraphics.pose();
        float uiScale = (float) Math.max(0.5, Math.min(3.0, ClientConfig.hudScale));
        
        if (isBleeding)
        {
            float bleedoutProgress;
            if (SyncedSettings.enableBleedoutTimer())
            {
                int totalBleedoutTicks = bleedoutStartTicks > 0 ? bleedoutStartTicks : (SyncedSettings.bleedoutTime() * 20);
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
            
            String text;
            if (SyncedSettings.enableBleedoutTimer())
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
            
            if (SyncedSettings.enableSuicide() && !isReviving)
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
            
            if (SyncedSettings.enableSuicide() && isBleeding && (BleedOutClientHandler.isLeftClickPressed(mc) || suicideSent) && (leftClickHoldTicks > 0 || suicideSent))
            {
                int suicideTicks = SyncedSettings.suicideTime() * 20;
                float progress = suicideSent ? 1.0f : Math.min(1.0f, (float) leftClickHoldTicks / suicideTicks);
                
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
        
        if (isReviving)
        {
            int totalReviveTicks = SyncedSettings.reviveTime() > 0 ? (SyncedSettings.reviveTime() * 20) : 100;
            if (reviveStartTicks > totalReviveTicks)
            {
                totalReviveTicks = reviveStartTicks;
            }
            float progress = totalReviveTicks > 0 ? 1.0f - ((float) currentReviveTicks / totalReviveTicks) : 1.0f;
            progress = Math.max(0.0f, Math.min(1.0f, progress));
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
    private static final String DISABLE_ALL_INPUTS = "ALL";
    private static final String DISABLE_ALL_KEYBOARD_INPUTS = "ALL_KEYBOARD";

    private static Class<?> cachedIGunClass = null;
    private static Class<?> cachedShootKeyClass = null;
    private static Class<?> cachedAimKeyClass = null;
    private static Class<?> cachedMeleeKeyClass = null;
    private static Class<?> cachedReloadKeyClass = null;
    private static boolean tackModChecked = false;

    private static boolean superbAdsChecked = false;
    private static java.lang.reflect.Field superbZoomField = null;
    private static boolean taczAdsChecked = false;
    private static java.lang.reflect.Method taczFromLocalPlayer = null;
    private static java.lang.reflect.Method taczAimMethod = null;

    private static java.lang.reflect.Field isLeftPressedField = null;
    private static java.lang.reflect.Field isRightPressedField = null;
    private static boolean mouseFieldsInitialized = false;

    private static boolean leftMouseButtonDown = false;
    private static boolean requireLmbReleaseForSuicide = false;
    
    private static final Set<String> GRENADE_IDS = Set.of(
        "superbwarfare:rgo_grenade",
        "superbwarfare:hand_grenade",
        "superbwarfare:claymore_mine",
        "superbwarfare:tm_62",
        "superbwarfare:c4_bomb",
        "superbwarfare:blu_43_mine",
        "superbwarfare:m18_smoke_grenade"
    );

    static {
        if (ModList.get().isLoaded("tacz")) {
            try {
                cachedIGunClass = Class.forName("com.tacz.guns.api.item.IGun");
                cachedShootKeyClass = Class.forName("com.tacz.guns.client.input.ShootKey");
                cachedAimKeyClass = Class.forName("com.tacz.guns.client.input.AimKey");
                cachedMeleeKeyClass = Class.forName("com.tacz.guns.client.input.MeleeKey");
                cachedReloadKeyClass = Class.forName("com.tacz.guns.client.input.ReloadKey");
                tackModChecked = true;
            } catch (ClassNotFoundException e) {
                tackModChecked = false;
            }
        }
    }

    private static boolean isTACZGun(ItemStack stack) {
        if (!tackModChecked || cachedIGunClass == null || stack.isEmpty()) return false;
        return cachedIGunClass.isInstance(stack.getItem());
    }

    private static boolean isSuperWarfareGrenade(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare") || stack.isEmpty()) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return GRENADE_IDS.contains(itemId.toString());
    }

    private static boolean isSuperWarfareWeapon(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare") || stack.isEmpty()) return false;
        String className = stack.getItem().getClass().getName();
        return className.contains("superbwarfare") && !isSuperWarfareGrenade(stack);
    }

    private static void releaseKeyMapping(KeyMapping keyMapping)
    {
        if (keyMapping == null) return;
        keyMapping.setDown(false);
        while (keyMapping.consumeClick()) {}
    }

    private static void releaseStaticKeyMapping(Class<?> clazz, String fieldName)
    {
        if (clazz == null) return;
        try
        {
            Object key = clazz.getField(fieldName).get(null);
            if (key instanceof KeyMapping keyMapping)
            {
                releaseKeyMapping(keyMapping);
            }
        }
        catch (Exception ignored) {}
    }

    private static void resetMouseHandlerPressed(Minecraft mc)
    {
        if (mc == null || mc.mouseHandler == null) return;
        if (!mouseFieldsInitialized)
        {
            mouseFieldsInitialized = true;
            try
            {
                isLeftPressedField = MouseHandler.class.getDeclaredField("isLeftPressed");
                isLeftPressedField.setAccessible(true);
            }
            catch (Exception ignored) {}
            try
            {
                isRightPressedField = MouseHandler.class.getDeclaredField("isRightPressed");
                isRightPressedField.setAccessible(true);
            }
            catch (Exception ignored) {}
        }
        try
        {
            if (isLeftPressedField != null) isLeftPressedField.setBoolean(mc.mouseHandler, false);
            if (isRightPressedField != null) isRightPressedField.setBoolean(mc.mouseHandler, false);
        }
        catch (Exception ignored) {}
    }

    public static void releaseAttackAndUseInputs(Minecraft mc, Player player)
    {
        if (mc.options != null)
        {
            releaseKeyMapping(mc.options.keyAttack);
            releaseKeyMapping(mc.options.keyUse);
            releaseKeyMapping(mc.options.keyPickItem);
        }

        try
        {
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_LEFT), false);
            KeyMapping.set(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT), false);
        }
        catch (Exception ignored) {}

        resetMouseHandlerPressed(mc);

        if (tackModChecked)
        {
            releaseStaticKeyMapping(cachedShootKeyClass, "SHOOT_KEY");
            releaseStaticKeyMapping(cachedAimKeyClass, "AIM_KEY");
            releaseStaticKeyMapping(cachedMeleeKeyClass, "MELEE_KEY");
            releaseStaticKeyMapping(cachedReloadKeyClass, "RELOAD_KEY");
        }

        if (player instanceof LocalPlayer localPlayer)
        {
            resetAimingForPlayer(localPlayer);
        }

        if (player != null && player.isUsingItem())
        {
            player.stopUsingItem();
        }

        if (mc.gameMode != null)
        {
            mc.gameMode.stopDestroyBlock();
        }
    }

    private static Set<String> getDisabledBleedingInputs()
    {
        Set<String> disabledInputs = new java.util.HashSet<>();
        disabledInputs.add("KEY.JUMP");
        disabledInputs.add("KEY.SNEAK");

        java.util.List<String> configuredInputs = SyncedSettings.disabledInputsWhileBleeding();
        if (configuredInputs == null)
            return disabledInputs;

        for (String value : configuredInputs)
        {
            if (value == null || value.isBlank())
                continue;

            String normalized = value.trim();
            if (!normalized.isEmpty())
            {
                disabledInputs.add(normalized.toUpperCase(java.util.Locale.ROOT));
            }
        }
        return disabledInputs;
    }

    private static boolean shouldDisableKeyboardInput(Set<String> disabledInputs, KeyMapping keyMapping)
    {
        if (disabledInputs == null || keyMapping == null) return false;
        boolean isMouse = keyMapping.getKey().getType() == InputConstants.Type.MOUSE;
        if (disabledInputs.contains(DISABLE_ALL_INPUTS))
            return true;
        if (disabledInputs.contains(DISABLE_ALL_KEYBOARD_INPUTS) && !isMouse)
            return true;

        String keyName = keyMapping.getName();
        if (keyName != null && disabledInputs.contains(keyName.toUpperCase(java.util.Locale.ROOT)))
            return true;

        String simpleName = keyName != null && keyName.startsWith("key.") ? keyName.substring(4) : keyName;
        return simpleName != null && disabledInputs.contains(simpleName.toUpperCase(java.util.Locale.ROOT));
    }

    private static void releaseConfiguredKeyboardInputs(Minecraft mc, Player player, Set<String> disabledInputs)
    {
        if (disabledInputs == null || disabledInputs.isEmpty() || mc.options == null) return;

        for (KeyMapping keyMapping : mc.options.keyMappings)
        {
            if (shouldDisableKeyboardInput(disabledInputs, keyMapping))
            {
                releaseKeyMapping(keyMapping);
            }
        }
    }

    private static void resetAimingForPlayer(LocalPlayer player) {
        if (player == null) return;

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
                } catch (Exception ignored) {}
            }
        }

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
                } catch (Exception ignored) {}
            }
        }
    }

    public static boolean isLeftClickPressed(Minecraft mc)
    {
        if (leftMouseButtonDown)
            return true;
        if (mc.getWindow() != null)
        {
            long window = mc.getWindow().getWindow();
            if (window != 0)
            {
                if (GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS)
                {
                    return true;
                }
                if (mc.options != null && mc.options.keyAttack != null)
                {
                    InputConstants.Key attackKey = mc.options.keyAttack.getKey();
                    if (attackKey != null)
                    {
                        if (attackKey.getType() == InputConstants.Type.MOUSE)
                        {
                            if (GLFW.glfwGetMouseButton(window, attackKey.getValue()) == GLFW.GLFW_PRESS)
                            {
                                return true;
                            }
                        }
                        else if (attackKey.getValue() > 0)
                        {
                            if (InputConstants.isKeyDown(window, attackKey.getValue()))
                            {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        int button = event.getButton();
        int action = event.getAction();
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");

        if (button == 0)
        {
            if (action == GLFW.GLFW_RELEASE)
            {
                if (isBleeding && BleedOutOverlay.getLeftClickTicks() > 0 && !BleedOutOverlay.isSuicideSent())
                {
                    BleedOutNetwork.sendToServer(new SuicideRequestPacket(SuicideRequestPacket.Phase.CANCEL));
                }
                leftMouseButtonDown = false;
                requireLmbReleaseForSuicide = false;
                BleedOutOverlay.resetLeftClickTicks();
                BleedOutOverlay.setSuicideSent(false);
            }
            else
            {
                leftMouseButtonDown = true;
            }
        }

        if (!isBleeding) return;

        // NEVER cancel mouse release events so vanilla Minecraft and mods properly update their released state!
        if (action == GLFW.GLFW_RELEASE)
        {
            return;
        }

        if (button == 2 || button == 3 || button == 4) return;

        // Cancel attack and item use presses while bleeding so guns don't shoot & items aren't used
        if (button == 0 || button == 1)
        {
            event.setCanceled(true);
            releaseAttackAndUseInputs(mc, player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onInteractionKeyMapping(InputEvent.InteractionKeyMappingTriggered event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        if (player.getPersistentData().getBoolean("bleedout:bleeding"))
        {
            event.setCanceled(true);
            event.setSwingHand(false);
            releaseAttackAndUseInputs(mc, player);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientAttackEntity(AttackEntityEvent event)
    {
        if (event.getEntity() instanceof Player player && player.getPersistentData().getBoolean("bleedout:bleeding"))
        {
            event.setCanceled(true);
        }
    }

    /**
     * Runs at the start of the client tick, before Minecraft handles key presses, so blocked keys never fire.
     */
    @SubscribeEvent
    public static void onClientTickPre(ClientTickEvent.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;
        if (!player.getPersistentData().getBoolean("bleedout:bleeding")) return;

        releaseAttackAndUseInputs(mc, player);
        if (mc.screen == null)
        {
            releaseConfiguredKeyboardInputs(mc, player, getDisabledBleedingInputs());
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.level == null)
        {
            BleedOutOverlay.resetRevive();
            return;
        }

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean wasBleeding = BleedOutOverlay.wasBleedingLastTick();

        if (isBleeding && !wasBleeding)
        {
            if (isLeftClickPressed(mc))
            {
                requireLmbReleaseForSuicide = true;
            }
            BleedOutOverlay.resetLeftClickTicks();
            BleedOutOverlay.setSuicideSent(false);
            releaseAttackAndUseInputs(mc, player);
            if (player instanceof LocalPlayer localPlayer && localPlayer.input != null)
            {
                localPlayer.input.forwardImpulse = 0;
                localPlayer.input.leftImpulse = 0;
                localPlayer.input.jumping = false;
                localPlayer.input.shiftKeyDown = false;
            }
        }
        BleedOutOverlay.setWasBleedingLastTick(isBleeding);

        if (isBleeding)
        {
            releaseAttackAndUseInputs(mc, player);

            if (player.getPose() != Pose.SWIMMING || player.getForcedPose() != Pose.SWIMMING)
            {
                player.setForcedPose(Pose.SWIMMING);
                player.setPose(Pose.SWIMMING);
            }

            Set<String> disabledInputs = getDisabledBleedingInputs();
            releaseConfiguredKeyboardInputs(mc, player, disabledInputs);

            if (mc.options.keyJump.isDown())
            {
                mc.options.keyJump.setDown(false);
            }
            if (mc.options.keyShift.isDown())
            {
                mc.options.keyShift.setDown(false);
            }
            player.setShiftKeyDown(false);

            if (SyncedSettings.disableMovementWhileBleeding())
            {
                mc.options.keyUp.setDown(false);
                mc.options.keyDown.setDown(false);
                mc.options.keyLeft.setDown(false);
                mc.options.keyRight.setDown(false);
            }

            if (player instanceof LocalPlayer localPlayer && localPlayer.input != null)
            {
                localPlayer.input.jumping = false;
                localPlayer.input.shiftKeyDown = false;
                if (SyncedSettings.disableMovementWhileBleeding())
                {
                    localPlayer.input.forwardImpulse = 0;
                    localPlayer.input.leftImpulse = 0;
                }
            }

            boolean isLeftClicking = isLeftClickPressed(mc);

            if (requireLmbReleaseForSuicide)
            {
                if (!isLeftClicking)
                {
                    requireLmbReleaseForSuicide = false;
                }
                BleedOutOverlay.resetLeftClickTicks();
            }
            else if (SyncedSettings.enableSuicide() && isLeftClicking)
            {
                int suicideTicks = SyncedSettings.suicideTime() * 20;
                if (BleedOutOverlay.getLeftClickTicks() == 0 && !BleedOutOverlay.isSuicideSent())
                {
                    // The server starts its own timer here and checks it on CONFIRM
                    BleedOutNetwork.sendToServer(new SuicideRequestPacket(SuicideRequestPacket.Phase.START));
                }
                if (BleedOutOverlay.getLeftClickTicks() >= suicideTicks)
                {
                    if (!BleedOutOverlay.isSuicideSent())
                    {
                        BleedOutOverlay.setSuicideSent(true);
                        BleedOutNetwork.sendToServer(new SuicideRequestPacket(SuicideRequestPacket.Phase.CONFIRM));
                    }
                }
                else if (!BleedOutOverlay.isSuicideSent())
                {
                    BleedOutOverlay.incrementLeftClickTicks();
                }
            }
            else
            {
                if (BleedOutOverlay.getLeftClickTicks() > 0 && !BleedOutOverlay.isSuicideSent())
                {
                    BleedOutNetwork.sendToServer(new SuicideRequestPacket(SuicideRequestPacket.Phase.CANCEL));
                }
                BleedOutOverlay.resetLeftClickTicks();
                BleedOutOverlay.setSuicideSent(false);
            }
        }
        else
        {
            leftMouseButtonDown = false;
            requireLmbReleaseForSuicide = false;
            BleedOutOverlay.resetLeftClickTicks();
            BleedOutOverlay.setSuicideSent(false);
            if (player.getForcedPose() == Pose.SWIMMING)
            {
                player.setForcedPose(null);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        SyncedSettings.clear();
        BleedOutOverlay.resetRevive();
        leftMouseButtonDown = false;
        requireLmbReleaseForSuicide = false;
        BleedOutOverlay.resetLeftClickTicks();
        BleedOutOverlay.setSuicideSent(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        if (player.getPersistentData().getBoolean("bleedout:bleeding"))
        {
            releaseAttackAndUseInputs(mc, player);
            Set<String> disabledInputs = getDisabledBleedingInputs();
            releaseConfiguredKeyboardInputs(mc, player, disabledInputs);
            if (mc.options.keyJump.isDown())
            {
                mc.options.keyJump.setDown(false);
            }
            if (mc.options.keyShift.isDown())
            {
                mc.options.keyShift.setDown(false);
            }
            player.setShiftKeyDown(false);
        }
    }

    public static void onRevivePacket(boolean isReviving, int ticksLeft)
    {
        BleedOutOverlay.onRevivePacket(isReviving, ticksLeft);
    }
}
