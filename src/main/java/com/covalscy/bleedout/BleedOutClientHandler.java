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
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
    private static Class<?> cachedIGunClass = null;
    private static Class<?> cachedShootKeyClass = null;
    private static boolean tackModChecked = false;

    private static boolean superbAdsChecked = false;
    private static java.lang.reflect.Field superbZoomField = null;
    private static boolean taczAdsChecked = false;
    private static java.lang.reflect.Method taczFromLocalPlayer = null;
    private static java.lang.reflect.Method taczAimMethod = null;
    
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onMouseButton(InputEvent.MouseButton.Pre event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        if (!isBleeding) return;

        int button = event.getButton();
        if (button == 2 || button == 3 || button == 4) return;

        ItemStack mainHand = player.getMainHandItem();
        boolean holdingWeapon = isTACZGun(mainHand) || isSuperWarfareWeapon(mainHand);

        if (holdingWeapon)
        {
            if (button == 0 || button == 1)
            {
                event.setCanceled(true);
            }
        }
        else if (isSuperWarfareGrenade(mainHand))
        {
            if (button != 0)
            {
                event.setCanceled(true);
            }
        }
        else
        {
            if (button == 1)
            {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;

        if (player == null || mc.level == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean wasBleeding = BleedOutOverlay.wasBleedingLastTick();

        if (isBleeding && !wasBleeding && player instanceof LocalPlayer localPlayer)
        {
            resetAimingForPlayer(localPlayer);
        }
        BleedOutOverlay.setWasBleedingLastTick(isBleeding);

        if (isBleeding)
        {
            if (player.getForcedPose() != Pose.SWIMMING)
            {
                player.setForcedPose(Pose.SWIMMING);
            }

            // Блокируем клавиши прыжка и движения при необходимости
            if (mc.options.keyJump.isDown())
            {
                mc.options.keyJump.setDown(false);
            }
            if (mc.options.keyShift.isDown())
            {
                mc.options.keyShift.setDown(false);
            }
            player.setShiftKeyDown(false);

            if (Config.disableMovementWhileBleeding)
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
                if (Config.disableMovementWhileBleeding)
                {
                    localPlayer.input.forwardImpulse = 0;
                    localPlayer.input.leftImpulse = 0;
                }
            }

            // Блокируем стрельбу из TACZ оружия (SHOOT_KEY)
            ItemStack mainHand = player.getMainHandItem();
            boolean holdingWeapon = isTACZGun(mainHand) || isSuperWarfareWeapon(mainHand);

            if (tackModChecked && cachedShootKeyClass != null && isTACZGun(mainHand))
            {
                try
                {
                    Object shootKey = cachedShootKeyClass.getField("SHOOT_KEY").get(null);
                    if (shootKey instanceof KeyMapping keyMapping)
                    {
                        keyMapping.setDown(false);
                    }
                }
                catch (Exception ignored) {}
            }

            // Логика зажатия ЛКМ для самоубийства (ТОЛЬКО если НЕ держит оружие)
            if (Config.enableSuicide && !holdingWeapon && mc.options.keyAttack.isDown())
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onKeyInput(InputEvent.Key event)
    {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        if (player.getPersistentData().getBoolean("bleedout:bleeding"))
        {
            if (mc.options.keyJump.isDown())
            {
                mc.options.keyJump.setDown(false);
            }
        }
    }
}
