package com.covalscy.bleedout.mixin.ragdoll;

import com.covalscy.bleedout.compat.ragdoll.SableRagdollClientCompat;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks camera rotation while the local player is knocked out in a ragdoll.
 * Only applied when bleedout_ragdolls is installed (see BleedOutMixinPlugin).
 */
@Mixin(Entity.class)
public abstract class RagdollCameraLockMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void bleedout$lockCameraWhileRagdollKnocked(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer player
                && player.getPersistentData().getBoolean("bleedout:bleeding")
                && SableRagdollClientCompat.isRidingRagdoll(player)) {
            ci.cancel();
        }
    }
}
