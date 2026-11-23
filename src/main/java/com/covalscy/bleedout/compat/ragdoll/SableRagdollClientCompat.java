package com.covalscy.bleedout.compat.ragdoll;

import dev.leo.sableplayerragdoll.entity.RagdollSeatEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only bridge for sable_player_ragdoll.
 * Never loads RagdollSeatEntity or Sable client classes unless sable_player_ragdoll is installed.
 */
public final class SableRagdollClientCompat {
    private SableRagdollClientCompat() {
    }

    public static boolean isRidingRagdoll(Player player) {
        return SableRagdollCompat.isAvailable() && Impl.isRidingRagdoll(player);
    }

    public static Vec3 getWorldPosition(Player player, float partialTick) {
        return SableRagdollCompat.isAvailable() ? Impl.getWorldPosition(player, partialTick) : player.getPosition(partialTick);
    }

    private static final class Impl {
        static boolean isRidingRagdoll(Player player) {
            return player != null && player.getVehicle() instanceof RagdollSeatEntity;
        }

        static Vec3 getWorldPosition(Player player, float partialTick) {
            if (isRidingRagdoll(player) && Sable.HELPER.getVehicleSubLevel(player) instanceof ClientSubLevel torso) {
                var plot = torso.getPlot();
                if (plot != null) {
                    return torso.renderPose(partialTick).transformPosition(Vec3.atCenterOf(plot.getCenterBlock()));
                }
            }
            return player.getPosition(partialTick);
        }
    }
}
