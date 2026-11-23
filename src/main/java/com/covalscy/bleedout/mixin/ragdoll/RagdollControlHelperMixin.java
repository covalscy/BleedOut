package com.covalscy.bleedout.mixin.ragdoll;

import com.covalscy.bleedout.BleedOutHandler;
import dev.leo.sableplayerragdoll.block.entity.RagdollPartBlockEntity.BodyPart;
import dev.leo.sableplayerragdoll.physics.RagdollControlHelper;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/**
 * Disables all ragdoll movement, arm reaching, and arm grabbing while a player is knocked out.
 * Only applied when bleedout_ragdolls is installed (see BleedOutMixinPlugin).
 */
@Mixin(value = RagdollControlHelper.class, remap = false)
public abstract class RagdollControlHelperMixin {
    @Shadow
    @Final
    private static Map<UUID, ?> INPUTS;

    @Shadow
    @Final
    private static Map<UUID, ?> ARM_INPUTS;

    @Shadow
    @Nullable
    private static ServerPlayer controllingPlayer(ServerLevel level, ServerSubLevel torsoSubLevel) {
        return null;
    }

    @Inject(method = "updateInput", at = @At("HEAD"), cancellable = true)
    private static void bleedout$blockMoveInputWhileBleeding(ServerPlayer player, float strafe, float forward, CallbackInfo ci) {
        if (BleedOutHandler.isBleeding(player)) {
            INPUTS.remove(player.getUUID());
            ci.cancel();
        }
    }

    @Inject(method = "updateArmInput", at = @At("HEAD"), cancellable = true)
    private static void bleedout$blockArmInputWhileBleeding(
            ServerPlayer player, boolean leftReach, boolean rightReach,
            boolean leftGrab, boolean rightGrab, float lookX, float lookY, float lookZ, CallbackInfo ci) {
        if (BleedOutHandler.isBleeding(player)) {
            ARM_INPUTS.remove(player.getUUID());
            ci.cancel();
        }
    }

    @Inject(method = "applyArm", at = @At("HEAD"), cancellable = true)
    private static void bleedout$blockArmReachWhileBleeding(
            ServerSubLevel armSubLevel, RigidBodyHandle handle, double timeStep, BodyPart bodyPart, CallbackInfo ci) {
        ServerPlayer player = controllingPlayer(armSubLevel.getLevel(), armSubLevel);
        if (player != null && BleedOutHandler.isBleeding(player)) {
            ci.cancel();
        }
    }

    @Inject(method = "apply", at = @At("HEAD"), cancellable = true)
    private static void bleedout$blockTorsoControlWhileBleeding(
            ServerSubLevel torsoSubLevel, RigidBodyHandle handle, double timeStep, CallbackInfo ci) {
        ServerPlayer player = controllingPlayer(torsoSubLevel.getLevel(), torsoSubLevel);
        if (player != null && BleedOutHandler.isBleeding(player)) {
            ci.cancel();
        }
    }
}
