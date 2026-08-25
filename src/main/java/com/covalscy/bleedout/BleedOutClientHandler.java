package com.covalscy.bleedout;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.ModList;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.HashSet;

class BleedOutOverlay implements IGuiOverlay
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static int leftClickHoldTicks = 0;
    
    private static int bleedoutStartTicks = 0;
    private static long bleedoutStartTime = 0;
    private static boolean wasBleeding = false;
    private static boolean isCurrentlyBleeding = false;
    
    private static int reviveStartTicks = 0;
    private static long reviveStartTime = 0;
    private static boolean wasReviving = false;
    
    private static double lastSinValue = 0.0;
    private static long lastSoundPlayTime = 0;

    @Override
    public void render(ForgeGui gui, GuiGraphics guiGraphics, float partialTick, int width, int height)
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

        int screenWidth = width;
        int screenHeight = height;
        
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
                    float testVolume = Math.max(volume, 1.0f);
                    
                    if (ModSounds.HEARTBEAT.get() != null)
                    {
                        boolean soundPlayed = false;
                        if (player.level() != null)
                        {
                            try
                            {
                                player.level().playSound(player, player.getX(), player.getY(), player.getZ(), 
                                    ModSounds.HEARTBEAT.get(), SoundSource.PLAYERS, testVolume, pitch);
                                soundPlayed = true;
                            }
                            catch (Exception ignored) {}
                        }
                        
                        if (!soundPlayed)
                        {
                            try
                            {
                                player.playSound(ModSounds.HEARTBEAT.get(), testVolume, pitch);
                                soundPlayed = true;
                            }
                            catch (Exception ignored) {}
                        }
                        
                        if (soundPlayed)
                        {
                            lastSoundPlayTime = currentTime;
                        }
                    }
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
                text = net.minecraft.network.chat.Component.translatable("bleedout.hud.bleeding", timeLeftSec).getString();
            }
            else
            {
                text = net.minecraft.network.chat.Component.translatable("bleedout.hud.bleeding_no_timer").getString();
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
                String hintText = net.minecraft.network.chat.Component.translatable("bleedout.hud.hint.suicide").getString();
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
                    com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                    com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
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
        
        if (isReviving && currentReviveTicks > 0)
        {
            int totalReviveTicks = reviveStartTicks;
            float progress = 1.0f - ((float) currentReviveTicks / totalReviveTicks);
            int timeLeftSec = (int) Math.ceil(currentReviveTicks / 20.0);

            boolean localIsBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
            
            String key = localIsBleeding ? "bleedout.hud.being_revived" : "bleedout.hud.reviving";
            String text = net.minecraft.network.chat.Component.translatable(key, timeLeftSec).getString();
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
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA
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
    
    public static void incrementLeftClickTicks() {
        leftClickHoldTicks++;
    }
    
    public static void resetLeftClickTicks() {
        leftClickHoldTicks = 0;
    }
    
    public static int getLeftClickTicks() {
        return leftClickHoldTicks;
    }
    
    public static boolean wasBleedingLastTick() {
        return wasBleeding;
    }
    
    public static void setWasBleeding(boolean value) {
        wasBleeding = value;
    }
}

@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
class BleedOutClientHandler
{
    private static final String DISABLE_ALL_INPUTS = "ALL";
    private static final String DISABLE_ALL_KEYBOARD_INPUTS = "ALL_KEYBOARD";

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

    private static void resetShootingAndAimingForPlayer(LocalPlayer player)
    {
        if (player == null) return;

        player.stopUsingItem();
        player.resetAttackStrengthTicker();

        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null)
        {
            if (mc.options.keyAttack != null) mc.options.keyAttack.setDown(false);
            if (mc.options.keyUse != null) mc.options.keyUse.setDown(false);
        }

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

        if (TaczCompat.isLoaded()) {
            TaczCompat.resetClientShooting(player);
        }
    }
    
    private static boolean isTACZGun(ItemStack stack) {
        if (!tackModChecked || cachedIGunClass == null) return false;
        return cachedIGunClass.isInstance(stack.getItem());
    }
    
    private static boolean isSuperWarfareGrenade(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare")) return false;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return GRENADE_IDS.contains(itemId.toString());
    }
    
    private static boolean isSuperWarfareWeapon(ItemStack stack) {
        if (!ModList.get().isLoaded("superbwarfare")) return false;
        String className = stack.getItem().getClass().getName();
        return className.contains("superbwarfare") && !isSuperWarfareGrenade(stack);
    }

    private static Set<String> getDisabledBleedingInputs()
    {
        Set<String> disabledInputs = new HashSet<>();
        disabledInputs.add("KEY.JUMP");
        disabledInputs.add("KEY.SNEAK");

        List<? extends String> configuredInputs = Config.disabledInputsWhileBleeding;
        if (configuredInputs == null)
        {
            return disabledInputs;
        }

        for (String value : configuredInputs)
        {
            if (value != null)
            {
                String normalized = value.trim();
                if (!normalized.isEmpty())
                {
                    disabledInputs.add(normalized.toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        return disabledInputs;
    }

    private static boolean shouldDisableMouseButtons(Set<String> disabledInputs)
    {
        return disabledInputs != null && disabledInputs.contains(DISABLE_ALL_INPUTS);
    }

    private static boolean shouldDisableAttack(Set<String> disabledInputs)
    {
        if (disabledInputs == null) return false;
        return disabledInputs.contains(DISABLE_ALL_INPUTS) ||
               disabledInputs.contains("KEY.ATTACK") ||
               disabledInputs.contains("ATTACK");
    }

    private static boolean shouldDisableUse(Set<String> disabledInputs)
    {
        if (disabledInputs == null) return false;
        return disabledInputs.contains(DISABLE_ALL_INPUTS) ||
               disabledInputs.contains("KEY.USE") ||
               disabledInputs.contains("USE");
    }

    private static boolean shouldDisableKeyboardInput(Set<String> disabledInputs, KeyMapping keyMapping)
    {
        if (disabledInputs == null || keyMapping == null) return false;
        if (disabledInputs.contains(DISABLE_ALL_INPUTS) || disabledInputs.contains(DISABLE_ALL_KEYBOARD_INPUTS))
        {
            return true;
        }

        String keyName = keyMapping.getName();
        if (keyName != null && disabledInputs.contains(keyName.toUpperCase(java.util.Locale.ROOT)))
        {
            return true;
        }

        String simpleName = keyName != null && keyName.startsWith("key.") ? keyName.substring(4) : keyName;
        return simpleName != null && disabledInputs.contains(simpleName.toUpperCase(java.util.Locale.ROOT));
    }

    private static void releaseConfiguredKeyboardInputs(Minecraft mc, Player player, Set<String> disabledInputs)
    {
        if (disabledInputs == null || disabledInputs.isEmpty()) return;

        boolean disableAllKeyboard = disabledInputs.contains(DISABLE_ALL_KEYBOARD_INPUTS);
        boolean disableAll = disabledInputs.contains(DISABLE_ALL_INPUTS);

        for (KeyMapping keyMapping : mc.options.keyMappings)
        {
            if (keyMapping == mc.options.keyAttack && !shouldDisableAttack(disabledInputs))
            {
                continue;
            }

            if (keyMapping == mc.options.keyUse && !shouldDisableUse(disabledInputs))
            {
                continue;
            }

            if (disableAll || disableAllKeyboard || shouldDisableKeyboardInput(disabledInputs, keyMapping))
            {
                keyMapping.setDown(false);
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

        Set<String> disabledInputs = getDisabledBleedingInputs();
        if (shouldDisableMouseButtons(disabledInputs))
        {
            event.setCanceled(true);
            return;
        }

        ItemStack mainHand = player.getMainHandItem();
        if (isSuperWarfareGrenade(mainHand))
        {
            if (button != 0 || shouldDisableAttack(disabledInputs))
            {
                event.setCanceled(true);
                return;
            }
        }
        else if (isSuperWarfareWeapon(mainHand) || isTACZGun(mainHand))
        {
            if (button == 0 || button == 1)
            {
                event.setCanceled(true);
                return;
            }
        }
        else
        {
            if ((button == 0 && shouldDisableAttack(disabledInputs)) || (button == 1 && shouldDisableUse(disabledInputs)) || button == 1)
            {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;
        
        Player player = event.player;
        if (player == null || !player.level().isClientSide) return;
        
        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        if (isBleeding && player.getPose() != net.minecraft.world.entity.Pose.SWIMMING)
        {
            player.setPose(net.minecraft.world.entity.Pose.SWIMMING);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.START) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        if (isBleeding)
        {
            ItemStack mainHand = player.getMainHandItem();
            boolean holdingWeapon = isTACZGun(mainHand) || isSuperWarfareWeapon(mainHand);
            Set<String> disabledInputs = getDisabledBleedingInputs();

            releaseConfiguredKeyboardInputs(mc, player, disabledInputs);

            if (shouldDisableAttack(disabledInputs) && mc.options.keyAttack.isDown())
            {
                mc.options.keyAttack.setDown(false);
            }

            if (shouldDisableUse(disabledInputs) && mc.options.keyUse.isDown())
            {
                mc.options.keyUse.setDown(false);
            }
            
            if (mc.options.keyJump.isDown() && shouldDisableKeyboardInput(disabledInputs, mc.options.keyJump))
            {
                mc.options.keyJump.setDown(false);
            }

            if (mc.options.keyShift.isDown() && shouldDisableKeyboardInput(disabledInputs, mc.options.keyShift))
            {
                mc.options.keyShift.setDown(false);
            }
            if (shouldDisableKeyboardInput(disabledInputs, mc.options.keyShift))
            {
                player.setShiftKeyDown(false);
            }

            if (Config.disableMovementWhileBleeding)
            {
                if (mc.options.keyUp.isDown()) mc.options.keyUp.setDown(false);
                if (mc.options.keyDown.isDown()) mc.options.keyDown.setDown(false);
                if (mc.options.keyLeft.isDown()) mc.options.keyLeft.setDown(false);
                if (mc.options.keyRight.isDown()) mc.options.keyRight.setDown(false);

                if (player instanceof LocalPlayer localPlayer && localPlayer.input != null)
                {
                    localPlayer.input.forwardImpulse = 0;
                    localPlayer.input.leftImpulse = 0;
                    localPlayer.input.jumping = false;
                    localPlayer.input.shiftKeyDown = false;
                }
            }
            
            if (holdingWeapon)
            {
                if (mc.options.keyAttack.isDown()) mc.options.keyAttack.setDown(false);
                if (mc.options.keyUse.isDown()) mc.options.keyUse.setDown(false);
            }

            if (player instanceof LocalPlayer localPlayer && TaczCompat.isLoaded())
            {
                TaczCompat.tickClientShooting(localPlayer);
            }
        }
    }
    
    @SubscribeEvent
    public static void onClientTickEnd(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null || mc.level == null) return;

        boolean isBleeding = player.getPersistentData().getBoolean("bleedout:bleeding");
        boolean wasBleeding = BleedOutOverlay.wasBleedingLastTick();
        
        if (isBleeding && !wasBleeding && player instanceof LocalPlayer localPlayer)
        {
            resetShootingAndAimingForPlayer(localPlayer);
        }
        
        if (isBleeding)
        {
            ItemStack mainHand = player.getMainHandItem();
            Set<String> disabledInputs = getDisabledBleedingInputs();
            boolean holdingWeapon = isTACZGun(mainHand) || isSuperWarfareWeapon(mainHand);
            
            if (Config.enableSuicide && !holdingWeapon && !shouldDisableAttack(disabledInputs) && mc.options.keyAttack.isDown())
            {
                int suicideTicks = Config.suicideTime * 20;
                if (BleedOutOverlay.getLeftClickTicks() >= suicideTicks)
                {
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
