package com.covalscy.bleedout.mixin;

import com.covalscy.bleedout.hitbox.KnockedBodyBox;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Vanilla ray checks (arrows, tridents, crosshair / melee targeting) read the target's box via
 * getBoundingBox().inflate(...).clip(...). For prone players we hand back a box that follows
 * the lying body; its inflate/clip/contains overrides do the precise test.
 * Collision and everything else keep the normal small box.
 */
@Mixin(ProjectileUtil.class)
public abstract class ProjectileUtilMixin {
    @WrapOperation(
            method = {
                    "getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;",
                    "getEntityHitResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;F)Lnet/minecraft/world/phys/EntityHitResult;"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getBoundingBox()Lnet/minecraft/world/phys/AABB;"),
            require = 2
    )
    private static AABB bleedout$knockedBodyBox(Entity target, Operation<AABB> original) {
        AABB box = original.call(target);
        if (target instanceof Player player && KnockedBodyBox.usesProneHitbox(player)) {
            return KnockedBodyBox.of(player, box);
        }
        return box;
    }
}
