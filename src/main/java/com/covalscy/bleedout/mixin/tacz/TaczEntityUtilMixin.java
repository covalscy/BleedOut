package com.covalscy.bleedout.mixin.tacz;

import com.covalscy.bleedout.compat.ragdoll.SableRagdollCompat;
import com.covalscy.bleedout.hitbox.KnockedBodyBox;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.util.EntityUtil;
import com.tacz.guns.util.HitboxHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TaCZ bullets: precise hit test against prone players ({@link KnockedBodyBox}) and against
 * articulated physics ragdoll limbs ({@link SableRagdollCompat}) with per-limb headshot detection.
 * Only applied when TaCZ is installed (see BleedOutMixinPlugin).
 */
@Mixin(value = EntityUtil.class, remap = false)
public abstract class TaczEntityUtilMixin {
    @Inject(method = "getHitResult", at = @At("HEAD"), cancellable = true)
    private static void bleedout$knockedHit(Projectile bullet, Entity target, Vec3 start, Vec3 end,
                                            CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (target instanceof Player player && SableRagdollCompat.isRidingRagdollSeat(player)) {
            cir.setReturnValue(null);
            return;
        }
        if (!(target instanceof Player player) || !KnockedBodyBox.usesProneHitbox(player)) return;
        KnockedBodyBox body = KnockedBodyBox.of(player, HitboxHelper.getFixedBoundingBox(target, bullet.getOwner()));
        Optional<Vec3> hit = body.clip(start, end);
        cir.setReturnValue(hit.map(pos -> new EntityKineticBullet.EntityResult(target, pos, body.isHeadHit(pos))).orElse(null));
    }

    @Inject(method = "findEntityOnPath", at = @At("RETURN"), cancellable = true)
    private static void bleedout$findRagdollOnPath(Projectile bullet, Vec3 start, Vec3 end,
                                                   CallbackInfoReturnable<EntityKineticBullet.EntityResult> cir) {
        if (!(bullet.level() instanceof ServerLevel serverLevel) || !SableRagdollCompat.isAvailable()) {
            return;
        }
        EntityKineticBullet.EntityResult existing = cir.getReturnValue();
        double maxDistSqr = existing != null ? start.distanceToSqr(existing.getHitPos()) : Double.MAX_VALUE;
        SableRagdollCompat.RagdollRayHit hit = SableRagdollCompat.findClosestRagdollHit(
                serverLevel, bullet.getOwner(), start, end, maxDistSqr);
        if (hit != null) {
            SableRagdollCompat.recordLimbHit(hit);
            cir.setReturnValue(new EntityKineticBullet.EntityResult(hit.player(), hit.hitPos(), hit.headshot()));
        }
    }

    @Inject(method = "findEntitiesOnPath", at = @At("RETURN"), cancellable = true)
    private static void bleedout$findRagdollsOnPath(Projectile bullet, Vec3 start, Vec3 end,
                                                    CallbackInfoReturnable<List<EntityKineticBullet.EntityResult>> cir) {
        if (!(bullet.level() instanceof ServerLevel serverLevel) || !SableRagdollCompat.isAvailable()) {
            return;
        }
        List<SableRagdollCompat.RagdollRayHit> ragdollHits = SableRagdollCompat.collectRagdollHits(
                serverLevel, bullet.getOwner(), start, end);
        if (ragdollHits.isEmpty()) {
            return;
        }
        List<EntityKineticBullet.EntityResult> existing = cir.getReturnValue();
        List<EntityKineticBullet.EntityResult> merged = existing != null ? new ArrayList<>(existing) : new ArrayList<>();
        Set<Entity> seen = new HashSet<>();
        for (EntityKineticBullet.EntityResult r : merged) {
            seen.add(r.getEntity());
        }
        boolean changed = false;
        for (SableRagdollCompat.RagdollRayHit hit : ragdollHits) {
            if (seen.add(hit.player())) {
                SableRagdollCompat.recordLimbHit(hit);
                merged.add(new EntityKineticBullet.EntityResult(hit.player(), hit.hitPos(), hit.headshot()));
                changed = true;
            }
        }
        if (changed) {
            cir.setReturnValue(merged);
        }
    }
}
