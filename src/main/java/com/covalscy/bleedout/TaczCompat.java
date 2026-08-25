package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunMeleeEvent;
import com.tacz.guns.api.event.common.GunReloadEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.client.input.ShootKey;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Field;

public final class TaczCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TACZ_MODID = "tacz";

    private static boolean registered = false;
    private static Field lastTimeShootSuccessField = null;
    private static Field controllerShootDownField = null;
    private static boolean reflectionInitialized = false;

    private TaczCompat() {}

    public static boolean isLoaded() {
        return ModList.get().isLoaded(TACZ_MODID);
    }

    public static void init() {
        if (registered || !isLoaded()) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(new CommonEvents());
        LOGGER.info("[BleedOut] Registered TACZ compatibility events");
    }

    private static void ensureReflection() {
        if (reflectionInitialized) return;
        reflectionInitialized = true;
        try {
            lastTimeShootSuccessField = ShootKey.class.getDeclaredField("lastTimeShootSuccess");
            lastTimeShootSuccessField.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.debug("[BleedOut] Could not access ShootKey.lastTimeShootSuccess: {}", t.getMessage());
        }
        try {
            controllerShootDownField = ShootKey.class.getDeclaredField("controllerShootDown");
            controllerShootDownField.setAccessible(true);
        } catch (Throwable t) {
            LOGGER.debug("[BleedOut] Could not access ShootKey.controllerShootDown: {}", t.getMessage());
        }
    }

    public static void resetClientShooting(LocalPlayer player) {
        if (!isLoaded() || player == null) return;
        ensureReflection();

        try {
            ShootKey.SHOOT_KEY.setDown(false);
            if (lastTimeShootSuccessField != null) {
                lastTimeShootSuccessField.setBoolean(null, false);
            }
            if (controllerShootDownField != null) {
                controllerShootDownField.setBoolean(null, false);
            }
        } catch (Throwable ignored) {}

        try {
            IClientPlayerGunOperator operator = IClientPlayerGunOperator.fromLocalPlayer(player);
            if (operator != null) {
                operator.aim(false);
                operator.chargeShoot(false);
                if (operator.getDataHolder() != null) {
                    operator.getDataHolder().reset();
                }
            }
        } catch (Throwable ignored) {}
    }

    public static void tickClientShooting(LocalPlayer player) {
        if (!isLoaded() || player == null) return;
        ensureReflection();

        try {
            if (ShootKey.SHOOT_KEY.isDown()) {
                ShootKey.SHOOT_KEY.setDown(false);
            }
            if (lastTimeShootSuccessField != null) {
                lastTimeShootSuccessField.setBoolean(null, false);
            }
            if (controllerShootDownField != null) {
                controllerShootDownField.setBoolean(null, false);
            }
        } catch (Throwable ignored) {}

        try {
            IClientPlayerGunOperator operator = IClientPlayerGunOperator.fromLocalPlayer(player);
            if (operator != null) {
                operator.chargeShoot(false);
                if (operator.getDataHolder() != null) {
                    operator.getDataHolder().isCharging = false;
                    operator.getDataHolder().isShootRecorded = false;
                    operator.getDataHolder().clientStateLock = false;
                }
            }
        } catch (Throwable ignored) {}
    }

    public static class CommonEvents {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onGunShoot(GunShootEvent event) {
            LivingEntity shooter = event.getShooter();
            if (shooter instanceof Player player && BleedOutHandler.isBleeding(player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onGunFire(GunFireEvent event) {
            LivingEntity shooter = event.getShooter();
            if (shooter instanceof Player player && BleedOutHandler.isBleeding(player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onGunReload(GunReloadEvent event) {
            LivingEntity entity = event.getEntity();
            if (entity instanceof Player player && BleedOutHandler.isBleeding(player)) {
                event.setCanceled(true);
            }
        }

        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onGunMelee(GunMeleeEvent event) {
            LivingEntity shooter = event.getShooter();
            if (shooter instanceof Player player && BleedOutHandler.isBleeding(player)) {
                event.setCanceled(true);
            }
        }
    }
}
