package com.covalscy.bleedout.compat.tacz;

import com.covalscy.bleedout.compat.ragdoll.SableRagdollCompat;
import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Optional bridge applying physics impulse to ragdoll limbs hit by TaCZ bullets.
 * Only invoked when both TaCZ and bleedout_ragdolls are loaded.
 */
public final class TaczRagdollCompat {
    private TaczRagdollCompat() {
    }

    public static void registerEvents() {
        Impl.registerEvents();
    }

    private static final class Impl {
        private static boolean registered;

        static void registerEvents() {
            if (registered) {
                return;
            }
            registered = true;
            NeoForge.EVENT_BUS.addListener(Impl::onGunHurtPost);
            NeoForge.EVENT_BUS.addListener(Impl::onGunKill);
        }

        private static void onGunHurtPost(EntityHurtByGunEvent.Post event) {
            if (event.getLogicalSide().isServer() && event.getHurtEntity() instanceof ServerPlayer target) {
                Entity bullet = event.getBullet();
                if (bullet != null) {
                    SableRagdollCompat.applyBulletImpulse(target, bullet.getDeltaMovement(), event.getAmount());
                }
            }
        }

        private static void onGunKill(EntityKillByGunEvent event) {
            if (event.getLogicalSide().isServer() && event.getKilledEntity() instanceof ServerPlayer target) {
                Entity bullet = event.getBullet();
                if (bullet != null) {
                    SableRagdollCompat.applyBulletImpulse(target, bullet.getDeltaMovement(), event.getBaseDamage());
                }
            }
        }
    }
}
