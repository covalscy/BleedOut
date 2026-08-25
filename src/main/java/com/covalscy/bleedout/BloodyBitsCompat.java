package com.covalscy.bleedout;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Необязательная интеграция с модом BloodyBits (версия с системой частиц).
 *
 * <p>Код не использует прямые импорты классов BloodyBits во время компиляции,
 * чтобы BleedOut мог спокойно запускаться и работать без установленного BloodyBits.
 * Если мод найден, мы создаем объекты ParticleOptions (BloodSprayParticleOptions / BloodDripParticleOptions)
 * через рефлексию и отправляем их игрокам через ServerLevel.sendParticles.</p>
 */
public final class BloodyBitsCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String BLOODY_BITS_MODID = "bloodybits";
    private static final String DEFAULT_BLOOD_COLOR = "#96000F";

    private static final int MOVEMENT_BLOOD_INTERVAL_TICKS = 20;
    private static final int PERIODIC_DRIP_INTERVAL_TICKS = 30;
    private static final double MIN_MOVEMENT_SQR = 0.000002D;

    private static final Map<UUID, Integer> DARKNESS_BURST_STAGE = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_BLEEDING_POSITIONS = new HashMap<>();

    private static boolean initialized = false;
    private static boolean available = false;
    private static Constructor<?> sprayConstructorVec3 = null;
    private static Constructor<?> sprayConstructorDoubles = null;
    private static Constructor<?> dripConstructor = null;

    private BloodyBitsCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(BLOODY_BITS_MODID);
    }

    private static synchronized boolean ensureInitialized() {
        if (initialized) {
            return available;
        }
        initialized = true;

        if (!isLoaded()) {
            available = false;
            return false;
        }

        try {
            Class<?> sprayClass = Class.forName("com.cravencraft.bloodybits.client.particle.spray.BloodSprayParticleOptions");
            try {
                sprayConstructorVec3 = sprayClass.getConstructor(String.class, Vec3.class, float.class);
            } catch (NoSuchMethodException e) {
                try {
                    sprayConstructorDoubles = sprayClass.getConstructor(String.class, Double.class, Double.class, Double.class, Float.class);
                } catch (NoSuchMethodException ignored) {
                }
            }

            try {
                Class<?> dripClass = Class.forName("com.cravencraft.bloodybits.client.particle.drip.BloodDripParticleOptions");
                dripConstructor = dripClass.getConstructor(String.class, int.class, float.class);
            } catch (Exception ignored) {
            }

            available = (sprayConstructorVec3 != null || sprayConstructorDoubles != null);
            if (available) {
                LOGGER.info("[BleedOut] Successfully initialized BloodyBits particle integration");
            }
        } catch (Throwable t) {
            LOGGER.warn("[BleedOut] Failed to initialize BloodyBits particle integration: {}", t.getMessage());
            available = false;
        }

        return available;
    }

    public static void setBleedingPlayer(ServerPlayer player) {
        if (!isLoaded() || player == null || player.level().isClientSide()) {
            return;
        }

        UUID playerId = player.getUUID();
        LAST_BLEEDING_POSITIONS.put(playerId, player.position());
        DARKNESS_BURST_STAGE.put(playerId, 0);

        spawnBloodSpray(player, 3, true);
    }

    public static void tickBleedingPlayer(ServerPlayer player, int ticksLeft) {
        if (!isLoaded() || player == null || player.level().isClientSide()) {
            return;
        }

        if (ticksLeft <= 0) {
            return;
        }

        maybeSpawnMovementBlood(player, ticksLeft);
        maybeSpawnPeriodicDrip(player, ticksLeft);
        maybeSpawnDarknessBurst(player, ticksLeft);
    }

    public static void onDeathBurst(ServerPlayer player) {
        if (!isLoaded() || player == null || player.level().isClientSide()) {
            return;
        }

        spawnBloodSpray(player, 6, true);
    }

    public static void clearBleedingPlayer(ServerPlayer player) {
        if (player == null) {
            return;
        }
        UUID playerId = player.getUUID();
        DARKNESS_BURST_STAGE.remove(playerId);
        LAST_BLEEDING_POSITIONS.remove(playerId);
    }

    private static void maybeSpawnMovementBlood(ServerPlayer player, int ticksLeft) {
        UUID playerId = player.getUUID();
        Vec3 movement = player.getDeltaMovement();
        double horizontalMovementSqr = movement.x * movement.x + movement.z * movement.z;

        Vec3 currentPos = player.position();
        Vec3 previousPos = LAST_BLEEDING_POSITIONS.put(playerId, currentPos);
        double positionalMovementSqr = 0.0D;
        if (previousPos != null) {
            double dx = currentPos.x - previousPos.x;
            double dz = currentPos.z - previousPos.z;
            positionalMovementSqr = dx * dx + dz * dz;
        }

        if (horizontalMovementSqr < MIN_MOVEMENT_SQR && positionalMovementSqr < MIN_MOVEMENT_SQR) {
            return;
        }

        if (ticksLeft % MOVEMENT_BLOOD_INTERVAL_TICKS != 0) {
            return;
        }

        spawnBloodSpray(player, 1, false);
    }

    private static void maybeSpawnPeriodicDrip(ServerPlayer player, int ticksLeft) {
        if (ticksLeft % PERIODIC_DRIP_INTERVAL_TICKS != 0) {
            return;
        }

        spawnBloodDrip(player);
    }

    private static void maybeSpawnDarknessBurst(ServerPlayer player, int ticksLeft) {
        int totalBleedoutTicks = Math.max(1, Config.bleedoutTime * 20);
        float bleedoutProgress = 1.0F - ((float) ticksLeft / totalBleedoutTicks);

        int stage = 0;
        if (bleedoutProgress >= 0.85F) {
            stage = 3;
        } else if (bleedoutProgress >= 0.65F) {
            stage = 2;
        } else if (bleedoutProgress >= 0.40F) {
            stage = 1;
        }

        UUID playerId = player.getUUID();
        int previousStage = DARKNESS_BURST_STAGE.getOrDefault(playerId, 0);
        if (stage <= previousStage) {
            return;
        }

        DARKNESS_BURST_STAGE.put(playerId, stage);
        spawnBloodSpray(player, stage + 2, true);
    }

    private static void spawnBloodSpray(ServerPlayer player, int count, boolean burst) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ensureInitialized()) {
            return;
        }

        AABB bounds = player.getBoundingBox();
        double spawnY = Math.max(bounds.minY + 0.15D, player.getY() + (burst ? 0.25D : 0.12D));
        double spawnX = player.getX();
        double spawnZ = player.getZ();
        String bloodColor = getPlayerBloodColor(player);

        for (int i = 0; i < count; i++) {
            double offsetX = (serverLevel.random.nextDouble() - 0.5D) * 0.35D;
            double offsetZ = (serverLevel.random.nextDouble() - 0.5D) * 0.35D;

            Vec3 sprayVector;
            if (burst) {
                sprayVector = new Vec3(
                    (serverLevel.random.nextDouble() - 0.5D) * 0.3D,
                    0.08D + serverLevel.random.nextDouble() * 0.12D,
                    (serverLevel.random.nextDouble() - 0.5D) * 0.3D
                );
            } else {
                Vec3 look = player.getLookAngle();
                Vec3 backwards = new Vec3(-look.x, 0.0D, -look.z).normalize().scale(0.05D);
                if (Double.isNaN(backwards.x) || Double.isNaN(backwards.z)) {
                    backwards = Vec3.ZERO;
                }
                sprayVector = new Vec3(
                    backwards.x + (serverLevel.random.nextDouble() - 0.5D) * 0.1D,
                    -0.03D - serverLevel.random.nextDouble() * 0.05D,
                    backwards.z + (serverLevel.random.nextDouble() - 0.5D) * 0.1D
                );
            }

            ParticleOptions particleOptions = createBloodSprayOptions(bloodColor, sprayVector, 1.0F);
            if (particleOptions != null) {
                serverLevel.sendParticles(
                    particleOptions,
                    spawnX + offsetX,
                    spawnY,
                    spawnZ + offsetZ,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
                );
            }
        }
    }

    private static void spawnBloodDrip(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!ensureInitialized()) {
            return;
        }

        AABB bounds = player.getBoundingBox();
        double spawnY = Math.max(bounds.minY + 0.1D, player.getY() + 0.1D);
        double offsetX = (serverLevel.random.nextDouble() - 0.5D) * 0.25D;
        double offsetZ = (serverLevel.random.nextDouble() - 0.5D) * 0.25D;
        String bloodColor = getPlayerBloodColor(player);

        ParticleOptions dripOptions = createBloodDripOptions(bloodColor, Direction.DOWN.get3DDataValue(), 1.0F);
        if (dripOptions != null) {
            serverLevel.sendParticles(
                dripOptions,
                player.getX() + offsetX,
                spawnY,
                player.getZ() + offsetZ,
                1,
                0.0D,
                0.0D,
                0.0D,
                0.0D
            );
        } else {
            // Резервный вариант: маленький направленный вниз спрей
            ParticleOptions sprayOptions = createBloodSprayOptions(bloodColor, new Vec3(0.0D, -0.04D, 0.0D), 0.7F);
            if (sprayOptions != null) {
                serverLevel.sendParticles(
                    sprayOptions,
                    player.getX() + offsetX,
                    spawnY,
                    player.getZ() + offsetZ,
                    1,
                    0.0D,
                    0.0D,
                    0.0D,
                    0.0D
                );
            }
        }
    }

    private static ParticleOptions createBloodSprayOptions(String color, Vec3 direction, float scale) {
        if (!ensureInitialized()) {
            return null;
        }
        try {
            if (sprayConstructorVec3 != null) {
                return (ParticleOptions) sprayConstructorVec3.newInstance(color, direction, scale);
            } else if (sprayConstructorDoubles != null) {
                return (ParticleOptions) sprayConstructorDoubles.newInstance(color, direction.x, direction.y, direction.z, scale);
            }
        } catch (Throwable t) {
            LOGGER.debug("[BleedOut] Error creating BloodSprayParticleOptions: {}", t.getMessage());
        }
        return null;
    }

    private static ParticleOptions createBloodDripOptions(String color, int direction, float alpha) {
        if (!ensureInitialized() || dripConstructor == null) {
            return null;
        }
        try {
            return (ParticleOptions) dripConstructor.newInstance(color, direction, alpha);
        } catch (Throwable t) {
            LOGGER.debug("[BleedOut] Error creating BloodDripParticleOptions: {}", t.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String getPlayerBloodColor(ServerPlayer player) {
        if (player == null) {
            return DEFAULT_BLOOD_COLOR;
        }
        try {
            Class<?> registryClass = Class.forName("com.cravencraft.bloodybits.registries.BloodTypeRegistry");
            Method getBloodTypesMethod = registryClass.getMethod("getBloodTypes");
            List<?> bloodTypes = (List<?>) getBloodTypesMethod.invoke(null);
            if (bloodTypes != null) {
                for (Object bt : bloodTypes) {
                    if (bt == null) continue;
                    Method entityTagMethod = bt.getClass().getMethod("entityTag");
                    Method colorMethod = bt.getClass().getMethod("color");
                    TagKey<EntityType<?>> tag = (TagKey<EntityType<?>>) entityTagMethod.invoke(bt);
                    if (tag != null && player.getType().is(tag)) {
                        String color = (String) colorMethod.invoke(bt);
                        if (color != null && !color.isBlank()) {
                            return color;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_BLOOD_COLOR;
    }
}
