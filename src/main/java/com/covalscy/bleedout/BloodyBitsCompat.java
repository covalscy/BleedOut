package com.covalscy.bleedout;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Необязательная интеграция с модом BloodyBits.
 *
 * <p>Код не использует прямые импорты классов BloodyBits, чтобы BleedOut мог
 * запускаться без установленного BloodyBits. Если мод найден, мы ищем его
 * сущность крови через реестр и создаем её вручную.</p>
 */
public final class BloodyBitsCompat {
    private static final String BLOODY_BITS_MODID = "bloodybits";
    private static final ResourceLocation BLOOD_SPRAY_ID = ResourceLocation.fromNamespaceAndPath(BLOODY_BITS_MODID, "blood_spray");
    private static final int MOVEMENT_BLOOD_INTERVAL_TICKS = 20;
    private static final double MIN_MOVEMENT_SQR = 0.000002D;
    private static final Map<UUID, Integer> DARKNESS_BURST_STAGE = new HashMap<>();
    private static final Map<UUID, Vec3> LAST_BLEEDING_POSITIONS = new HashMap<>();

    private BloodyBitsCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(BLOODY_BITS_MODID);
    }

    public static void tickBleedingPlayer(ServerPlayer player, int ticksLeft) {
        if (!isLoaded() || player.level().isClientSide()) {
            return;
        }

        if (ticksLeft <= 0) {
            return;
        }

        maybeSpawnMovementBlood(player, ticksLeft);
        maybeSpawnDarknessBurst(player, ticksLeft);
    }

    public static void clearBleedingPlayer(ServerPlayer player) {
        DARKNESS_BURST_STAGE.remove(player.getUUID());
        LAST_BLEEDING_POSITIONS.remove(player.getUUID());
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
        spawnBloodSpray(player, stage + 1, true);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void spawnBloodSpray(ServerPlayer player, int count, boolean burst) {
        if (!(player.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        EntityType<?> entityType = ForgeRegistries.ENTITY_TYPES.getValue(BLOOD_SPRAY_ID);
        if (entityType == null) {
            return;
        }

        AABB bounds = player.getBoundingBox();
        double spawnY = Math.max(bounds.minY + 0.22D, player.getY() + (burst ? 0.28D : 0.18D));

        for (int i = 0; i < count; i++) {
            Entity entity = entityType.create(serverLevel);
            if (entity == null) {
                continue;
            }

            double offsetX = (Math.random() - 0.5D) * 0.35D;
            double offsetZ = (Math.random() - 0.5D) * 0.35D;
            entity.moveTo(player.getX() + offsetX, spawnY, player.getZ() + offsetZ, player.getYRot(), player.getXRot());

            if (entity instanceof Projectile projectile) {
                projectile.setOwner(player);
            }

            Vec3 look = player.getLookAngle();
            Vec3 backwards = new Vec3(-look.x, 0.0D, -look.z).normalize().scale(burst ? 0.08D : 0.045D);
            if (Double.isNaN(backwards.x) || Double.isNaN(backwards.z)) {
                backwards = Vec3.ZERO;
            }

            double xVelocity = backwards.x + ((Math.random() - 0.5D) * (burst ? 0.20D : 0.08D));
            double yVelocity = burst ? (0.06D + Math.random() * 0.05D) : (-0.05D - Math.random() * 0.03D);
            double zVelocity = backwards.z + ((Math.random() - 0.5D) * (burst ? 0.20D : 0.08D));

            entity.setDeltaMovement(xVelocity, yVelocity, zVelocity);
            serverLevel.addFreshEntity(entity);
        }
    }
}
