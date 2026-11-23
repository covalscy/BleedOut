package com.covalscy.bleedout.compat.ragdoll;

import com.covalscy.bleedout.BleedOutHandler;
import com.covalscy.bleedout.Config;
import dev.leo.sableplayerragdoll.api.DespawnCondition;
import dev.leo.sableplayerragdoll.api.RagdollAPI;
import dev.leo.sableplayerragdoll.api.RagdollEndEvent;
import dev.leo.sableplayerragdoll.api.RagdollInteractEvent;
import dev.leo.sableplayerragdoll.api.RagdollLaunchOptions;
import dev.leo.sableplayerragdoll.api.RagdollLimbConfig;
import dev.leo.sableplayerragdoll.api.RagdollLimbOptions;
import dev.leo.sableplayerragdoll.api.RagdollSession;
import dev.leo.sableplayerragdoll.api.RagdollStartEvent;
import dev.leo.sableplayerragdoll.block.RagdollPartBlock;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.config.RagdollSettings;
import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import dev.leo.sableplayerragdoll.physics.RagdollAssemblyHelper;
import dev.leo.sableplayerragdoll.physics.RagdollBlockOwnership;
import dev.leo.sableplayerragdoll.physics.RagdollControlHelper;
import dev.leo.sableplayerragdoll.physics.RagdollMotorEffects;
import dev.leo.sableplayerragdoll.physics.RagdollSessionManager;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.constraint.ConstraintJointAxis;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.physics.force.ForceTotal;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional compatibility bridge for bleedout_ragdolls.
 * All mod-specific types live inside {@link Impl} so this class can be safely referenced
 * even when bleedout_ragdolls is not installed.
 */
public final class SableRagdollCompat {
    private static final String MOD_ID = "bleedout_ragdolls";
    private static Boolean modLoaded;

    private SableRagdollCompat() {
    }

    public static boolean isAvailable() {
        if (modLoaded == null) {
            modLoaded = ModList.get().isLoaded(MOD_ID);
        }
        return modLoaded;
    }

    public static boolean isEnabled() {
        return isAvailable() && Config.enableRagdollCompat;
    }

    public static void registerEvents() {
        if (isAvailable()) {
            Impl.registerEvents();
        }
    }

    public static boolean isRidingRagdollSeat(Player player) {
        return isAvailable() && Impl.isRidingRagdollSeat(player);
    }

    public static boolean isRagdolled(ServerPlayer player) {
        return isAvailable() && Impl.isRagdolled(player);
    }

    public static boolean isKnockRagdollPendingOrActive(ServerPlayer player) {
        return isAvailable() && Impl.isKnockRagdollPendingOrActive(player);
    }

    public static boolean enterKnockRagdoll(ServerPlayer player, Vec3 initialVelocityBlocksPerTick) {
        return isEnabled() && Impl.enterKnockRagdoll(player, initialVelocityBlocksPerTick);
    }

    public static void exitKnockRagdoll(ServerPlayer player) {
        if (isAvailable()) {
            Impl.exitKnockRagdoll(player);
        }
    }

    public static Vec3 worldPositionOf(ServerPlayer player) {
        return isAvailable() ? Impl.worldPositionOf(player) : player.position();
    }

    public record RagdollRayHit(ServerPlayer player, Vec3 hitPos, boolean headshot, UUID limbSubLevelId) {
    }

    public static @Nullable RagdollRayHit findClosestRagdollHit(
            ServerLevel level, @Nullable Entity shooter, Vec3 start, Vec3 end, double maxDistSqr) {
        return isAvailable() ? Impl.findClosestRagdollHit(level, shooter, start, end, maxDistSqr) : null;
    }

    public static List<RagdollRayHit> collectRagdollHits(
            ServerLevel level, @Nullable Entity shooter, Vec3 start, Vec3 end) {
        return isAvailable() ? Impl.collectRagdollHits(level, shooter, start, end) : List.of();
    }

    public static void recordLimbHit(RagdollRayHit hit) {
        if (isAvailable() && hit != null) {
            Impl.recordLimbHit(hit);
        }
    }

    public static void applyBulletImpulse(ServerPlayer target, Vec3 bulletVelocity, float damage) {
        if (isAvailable() && target != null) {
            Impl.applyBulletImpulse(target, bulletVelocity, damage);
        }
    }

    public static double distance(ServerPlayer a, ServerPlayer b) {
        return worldPositionOf(a).distanceTo(worldPositionOf(b));
    }

    public static boolean isRagdollClick(ServerLevel level, BlockPos pos, @Nullable Direction face) {
        return isAvailable() && Impl.isRagdollClick(level, pos, face);
    }

    private static final class Impl {
        private static final double LIMP_NECK_STIFFNESS = 2.5;
        private static final double LIMP_NECK_DAMPING = 3.5;
        private static final double LIMP_LIMB_STIFFNESS = 0.15;
        private static final double LIMP_LIMB_DAMPING = 2.8;
        private static final long PENDING_TIMEOUT_TICKS = 15L;
        private static final double LIMB_HIT_PADDING = 0.03;
        private static final double MAX_BULLET_IMPULSE = 3.5;
        private static final double MIN_BULLET_IMPULSE = 0.8;

        private record LastLimbHit(UUID limbSubLevelId, Vec3 hitPos, long gameTime) {
        }

        private static final RagdollLimbOptions LIMP_LIMBS = RagdollLimbOptions.builder()
                .limb(BodyPart.HEAD, RagdollLimbConfig.builder()
                        .stiffness(LIMP_NECK_STIFFNESS)
                        .damping(LIMP_NECK_DAMPING)
                        .build())
                .limb(BodyPart.LEFT_ARM, limpLimbConfig())
                .limb(BodyPart.RIGHT_ARM, limpLimbConfig())
                .limb(BodyPart.LEFT_LEG, limpLimbConfig())
                .limb(BodyPart.RIGHT_LEG, limpLimbConfig())
                .build();

        private static final Map<UUID, Long> PENDING_KNOCK_EXPIRES = new ConcurrentHashMap<>();
        private static final Map<UUID, LastLimbHit> LAST_LIMB_HITS = new ConcurrentHashMap<>();
        private static boolean eventsRegistered;

        private static RagdollLimbConfig limpLimbConfig() {
            return RagdollLimbConfig.builder()
                    .stiffness(LIMP_LIMB_STIFFNESS)
                    .damping(LIMP_LIMB_DAMPING)
                    .build();
        }

        static void registerEvents() {
            if (eventsRegistered) return;
            eventsRegistered = true;
            NeoForge.EVENT_BUS.addListener(Impl::onRagdollStart);
            NeoForge.EVENT_BUS.addListener(Impl::onRagdollInteract);
            NeoForge.EVENT_BUS.addListener(Impl::onRagdollEnd);
        }

        static boolean isRidingRagdollSeat(Player player) {
            return player != null && player.getVehicle() instanceof RagdollSeatEntity;
        }

        static boolean isRagdolled(ServerPlayer player) {
            return player != null && RagdollAPI.isRagdolled(player);
        }

        static boolean isKnockRagdollPendingOrActive(ServerPlayer player) {
            if (player == null) return false;
            if (isRagdolled(player)) {
                PENDING_KNOCK_EXPIRES.remove(player.getUUID());
                return true;
            }
            Long expiresAt = PENDING_KNOCK_EXPIRES.get(player.getUUID());
            if (expiresAt == null) return false;
            if (player.serverLevel().getGameTime() > expiresAt) {
                PENDING_KNOCK_EXPIRES.remove(player.getUUID());
                return false;
            }
            return true;
        }

        static boolean enterKnockRagdoll(ServerPlayer player, Vec3 initialVelocityBlocksPerTick) {
            RagdollControlHelper.clearInput(player.getUUID());

            if (RagdollAPI.isRagdolled(player)) {
                PENDING_KNOCK_EXPIRES.remove(player.getUUID());
                configureExistingRagdollForKnock(player);
                return true;
            }

            if (!RagdollSettings.enabled()) {
                return false;
            }

            Vec3 velocityMps = initialVelocityBlocksPerTick.scale(20.0);
            PENDING_KNOCK_EXPIRES.put(player.getUUID(), player.serverLevel().getGameTime() + PENDING_TIMEOUT_TICKS);
            RagdollLaunchOptions options = RagdollLaunchOptions.builder()
                    .autoSeat(true)
                    .lockDismount(true)
                    .despawnConditions(List.of(DespawnCondition.never()))
                    .limbs(LIMP_LIMBS)
                    .build();
            RagdollAPI.launch(player, velocityMps, options);
            return true;
        }

        private static void configureExistingRagdollForKnock(ServerPlayer player) {
            RagdollSession session = RagdollAPI.activeSession(player);
            if (session != null) {
                session.setDismountLocked(true);
            }
            ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
            if (root == null) return;

            RagdollSessionManager.setCustomDespawnConditions(root, List.of(DespawnCondition.never()));
            RagdollMotorEffects.stopWailing(root);
            applyLimpMotors(RagdollBlockOwnership.sessionId(root));
        }

        private static void applyLimpMotors(UUID rootId) {
            for (Map.Entry<BodyPart, RagdollAssemblyHelper.RagdollJoint> entry : RagdollAssemblyHelper.joints(rootId).entrySet()) {
                BodyPart part = entry.getKey();
                if (part == BodyPart.TORSO) continue;

                PhysicsConstraintHandle handle = entry.getValue().handle();
                if (handle == null || !handle.isValid()) continue;

                double stiffness = part == BodyPart.HEAD ? LIMP_NECK_STIFFNESS : LIMP_LIMB_STIFFNESS;
                double damping = part == BodyPart.HEAD ? LIMP_NECK_DAMPING : LIMP_LIMB_DAMPING;
                Vector3dc target = entry.getValue().baseTarget();
                handle.setMotor(ConstraintJointAxis.ANGULAR_X, target.x(), stiffness, damping, false, 0.0);
                handle.setMotor(ConstraintJointAxis.ANGULAR_Y, target.y(), stiffness, damping, false, 0.0);
                handle.setMotor(ConstraintJointAxis.ANGULAR_Z, target.z(), stiffness, damping, false, 0.0);
            }
        }

        static void exitKnockRagdoll(ServerPlayer player) {
            if (player == null) return;
            PENDING_KNOCK_EXPIRES.remove(player.getUUID());
            RagdollControlHelper.clearInput(player.getUUID());
            RagdollSession session = RagdollAPI.activeSession(player);
            if (session != null) {
                session.setDismountLocked(false);
                session.release();
            }
        }

        static Vec3 worldPositionOf(ServerPlayer player) {
            ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(player.serverLevel(), player.getUUID());
            if (root == null || root.isRemoved()) {
                return player.position();
            }
            UUID owner = RagdollBlockOwnership.sessionId(root);
            var torso = RagdollBlockOwnership.findLimb(player.serverLevel(), owner);
            BlockPos pos = torso != null ? torso.getBlockPos() : (root.getPlot() != null ? root.getPlot().getCenterBlock() : null);
            if (pos == null) {
                return player.position();
            }
            Vec3 projected = Sable.HELPER.projectOutOfSubLevel(player.serverLevel(), Vec3.atCenterOf(pos));
            return projected != null ? projected : player.position();
        }

        static @Nullable RagdollRayHit findClosestRagdollHit(
                ServerLevel level, @Nullable Entity shooter, Vec3 start, Vec3 end, double maxDistSqr) {
            RagdollRayHit closest = null;
            double bestDistSqr = maxDistSqr;
            for (RagdollRayHit candidate : raycastAllRagdolls(level, shooter, start, end)) {
                double distSqr = start.distanceToSqr(candidate.hitPos());
                if (distSqr < bestDistSqr) {
                    bestDistSqr = distSqr;
                    closest = candidate;
                }
            }
            return closest;
        }

        static List<RagdollRayHit> collectRagdollHits(
                ServerLevel level, @Nullable Entity shooter, Vec3 start, Vec3 end) {
            return raycastAllRagdolls(level, shooter, start, end);
        }

        private static List<RagdollRayHit> raycastAllRagdolls(
                ServerLevel level, @Nullable Entity shooter, Vec3 start, Vec3 end) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return List.of();
            }

            AABB broadphase = new AABB(start, end).inflate(3.5);
            List<RagdollRayHit> hits = new ArrayList<>();

            for (ServerPlayer target : level.players()) {
                if (!target.isAlive() || target.isSpectator() || target.equals(shooter)) {
                    continue;
                }
                if (shooter != null && target.isPassengerOfSameVehicle(shooter)) {
                    continue;
                }
                ServerSubLevel root = RagdollSessionManager.activeRagdollForPlayer(level, target.getUUID());
                if (root == null || root.isRemoved()) {
                    continue;
                }
                Vector3dc rawTorsoPos = root.logicalPose().position();
                Vec3 torsoPos = root.getPlot() != null
                        ? root.logicalPose().transformPosition(Vec3.atCenterOf(root.getPlot().getCenterBlock()))
                        : new Vec3(rawTorsoPos.x(), rawTorsoPos.y(), rawTorsoPos.z());
                if (!broadphase.contains(torsoPos)) {
                    continue;
                }

                RagdollRayHit playerHit = raycastPlayerLimbs(level, container, target, root.getUniqueId(), start, end);
                if (playerHit != null) {
                    hits.add(playerHit);
                }
            }
            return hits;
        }

        private static @Nullable RagdollRayHit raycastPlayerLimbs(
                ServerLevel level, SubLevelContainer container, ServerPlayer target, UUID rootId, Vec3 start, Vec3 end) {
            RagdollRayHit best = null;
            double bestDistSqr = Double.MAX_VALUE;

            for (UUID partId : RagdollAssemblyHelper.linkedParts(level, rootId)) {
                SubLevel partSub = container.getSubLevel(partId);
                if (!(partSub instanceof ServerSubLevel serverPart) || serverPart.isRemoved() || serverPart.getPlot() == null) {
                    continue;
                }
                BlockPos center = serverPart.getPlot().getCenterBlock();
                BlockState state = level.getBlockState(center);
                if (!(state.getBlock() instanceof RagdollPartBlock)) {
                    continue;
                }
                BodyPart bodyPart = state.getValue(RagdollPartBlock.BODY_PART);
                AABB localBox = state.getShape(level, center).bounds().move(center).inflate(LIMB_HIT_PADDING);

                Vec3 localStart = serverPart.logicalPose().transformPositionInverse(start);
                Vec3 localEnd = serverPart.logicalPose().transformPositionInverse(end);
                Optional<Vec3> localHit = localBox.contains(localStart)
                        ? Optional.of(localStart)
                        : localBox.clip(localStart, localEnd);
                if (localHit.isEmpty()) {
                    continue;
                }

                Vec3 worldHit = serverPart.logicalPose().transformPosition(localHit.get());
                double distSqr = start.distanceToSqr(worldHit);
                if (distSqr < bestDistSqr) {
                    bestDistSqr = distSqr;
                    best = new RagdollRayHit(target, worldHit, bodyPart == BodyPart.HEAD, serverPart.getUniqueId());
                }
            }
            return best;
        }

        static void recordLimbHit(RagdollRayHit hit) {
            LAST_LIMB_HITS.put(
                    hit.player().getUUID(),
                    new LastLimbHit(hit.limbSubLevelId(), hit.hitPos(), hit.player().level().getGameTime())
            );
        }

        static void applyBulletImpulse(ServerPlayer target, Vec3 bulletVelocity, float damage) {
            LastLimbHit hit = LAST_LIMB_HITS.remove(target.getUUID());
            if (hit == null || target.serverLevel().getGameTime() - hit.gameTime() > 2L) {
                return;
            }
            double lenSqr = bulletVelocity.lengthSqr();
            if (lenSqr < 1.0E-6) {
                return;
            }
            SubLevelContainer container = SubLevelContainer.getContainer(target.serverLevel());
            if (container == null) {
                return;
            }
            SubLevel sub = container.getSubLevel(hit.limbSubLevelId());
            if (!(sub instanceof ServerSubLevel limbSub) || limbSub.isRemoved()) {
                return;
            }
            RigidBodyHandle handle = RigidBodyHandle.of(limbSub);
            if (handle == null || !handle.isValid()) {
                return;
            }

            double strength = Math.min(MAX_BULLET_IMPULSE, Math.max(MIN_BULLET_IMPULSE, damage * 0.15));
            Vec3 worldDir = bulletVelocity.normalize().scale(strength);
            Vector3d localImpulse = limbSub.logicalPose().transformNormalInverse(
                    new Vector3d(worldDir.x, worldDir.y, worldDir.z), new Vector3d());
            Vec3 localHitVec = limbSub.logicalPose().transformPositionInverse(hit.hitPos());
            Vector3d localPoint = new Vector3d(localHitVec.x, localHitVec.y, localHitVec.z);

            ForceTotal forceTotal = new ForceTotal();
            forceTotal.applyImpulseAtPoint(limbSub, localPoint, localImpulse);
            handle.applyForcesAndReset(forceTotal);
        }

        private static void onRagdollStart(RagdollStartEvent event) {
            UUID playerId = event.player().getUUID();
            if (PENDING_KNOCK_EXPIRES.containsKey(playerId) && !BleedOutHandler.isBleeding(event.player())) {
                PENDING_KNOCK_EXPIRES.remove(playerId);
                event.setCanceled(true);
            }
        }

        private static void onRagdollInteract(RagdollInteractEvent event) {
            ServerPlayer interactor = event.player();
            if (BleedOutHandler.isBleeding(interactor)) {
                event.setCanceled(true);
                return;
            }
            if (BleedOutHandler.isHoldingCustomReviveTool(interactor)) {
                return;
            }

            ServerLevel level = event.level();
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) return;
            SubLevel rootSubLevel = container.getSubLevel(event.rootId());
            if (!(rootSubLevel instanceof ServerSubLevel serverRoot)) return;

            UUID targetId = RagdollSessionManager.getPlayerId(serverRoot);
            if (targetId == null || targetId.equals(interactor.getUUID())) return;

            ServerPlayer targetPlayer = level.getServer().getPlayerList().getPlayer(targetId);
            if (targetPlayer == null || !BleedOutHandler.isBleeding(targetPlayer)) return;

            if (BleedOutHandler.tryStartRevive(interactor, targetPlayer)) {
                event.setCanceled(true);
            }
        }

        static boolean isRagdollClick(ServerLevel level, BlockPos pos, @Nullable Direction face) {
            if (isInRagdollPlot(level, pos)) {
                return true;
            }
            return face != null && isInRagdollPlot(level, pos.relative(face));
        }

        private static boolean isInRagdollPlot(ServerLevel level, BlockPos pos) {
            SubLevel subLevel = Sable.HELPER.getContaining(level, pos);
            return subLevel != null && RagdollAssemblyHelper.isRagdollPart(level, subLevel.getUniqueId());
        }

        private static void onRagdollEnd(RagdollEndEvent event) {
            ServerPlayer player = event.player();
            PENDING_KNOCK_EXPIRES.remove(player.getUUID());
            LAST_LIMB_HITS.remove(player.getUUID());
            if (event.reason() != RagdollEndEvent.Reason.PLAYER_DEATH && BleedOutHandler.isBleeding(player)) {
                player.setForcedPose(Pose.SWIMMING);
                player.setPose(Pose.SWIMMING);
                player.refreshDimensions();
            }
        }
    }
}
