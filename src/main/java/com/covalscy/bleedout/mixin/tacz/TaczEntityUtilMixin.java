package com.covalscy.bleedout.mixin.tacz;

import com.covalscy.bleedout.hitbox.KnockedBodyBox;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import com.tacz.guns.util.HitboxHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * TaCZ bullets: precise hit test against the lying body of a prone player, with its own head box
 * for headshots. The lag-compensated box from TaCZ's HitboxHelper is used as the body position.
 * Only applied when TaCZ is installed (see BleedOutMixinPlugin).
 */
@Mixin(value = EntityUtil.class, remap = false)
public abstract class TaczEntityUtilMixin {
    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true)
    private static void bleedout$knockedHit(Projectile bullet, Entity target, Vec3 start, Vec3 end,
                                            CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (!(target instanceof Player player) || !KnockedBodyBox.usesProneHitbox(player)) return;
        KnockedBodyBox body = KnockedBodyBox.of(player, HitboxHelper.getFixedBoundingBox(target, bullet.getOwner()));
        Optional<Vec3> hit = body.clip(start, end);
        cir.setReturnValue(hit.map(pos -> new EntityKineticBullet.EntityResult(target, pos, body.isHeadHit(pos))).orElse(null));
    }
}
