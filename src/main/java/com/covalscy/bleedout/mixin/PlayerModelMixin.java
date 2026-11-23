package com.covalscy.bleedout.mixin;

import com.covalscy.bleedout.client.KnockedCrawlAnimation;
import com.covalscy.bleedout.compat.ragdoll.SableRagdollClientCompat;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs after vanilla (and after other mods that pose arms in HumanoidModel.setupAnim, e.g. TaCZ),
 * so the knocked crawl pose always wins while a player is bleeding out.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("TAIL"))
    private void bleedout$knockedCrawl(LivingEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                                       float netHeadYaw, float headPitch, CallbackInfo ci) {
        if (entity instanceof Player player
                && player.getPersistentData().getBoolean("bleedout:bleeding")
                && !SableRagdollClientCompat.isRidingRagdoll(player)) {
            KnockedCrawlAnimation.apply((PlayerModel<?>) (Object) this, limbSwing, limbSwingAmount, ageInTicks);
        }
    }
}
