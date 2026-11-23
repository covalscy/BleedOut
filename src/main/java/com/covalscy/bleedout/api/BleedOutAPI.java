package com.covalscy.bleedout.api;

import com.covalscy.bleedout.BleedOutHandler;
import com.covalscy.bleedout.Config;
import com.covalscy.bleedout.compat.ragdoll.SableRagdollCompat;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Public API for BleedOut addons.
 */
public final class BleedOutAPI {
    private BleedOutAPI() {
    }

    public static boolean isBleeding(@Nullable Player player) {
        return BleedOutHandler.isBleeding(player);
    }

    public static int getBleedoutTicksLeft(@Nullable Player player) {
        return BleedOutHandler.getBleedoutTicksLeft(player);
    }

    public static boolean isBeingRevived(@Nullable Player player) {
        return BleedOutHandler.isBeingRevived(player);
    }

    public static int getReviveTicksLeft(@Nullable Player player) {
        return BleedOutHandler.getReviveTicksLeft(player);
    }

    public static double getMaxReviveDistance() {
        return Config.reviveDistance;
    }

    public static int getDefaultReviveTicks() {
        return Math.max(1, Config.reviveTime) * 20;
    }

    public static Vec3 getWorldPosition(ServerPlayer player) {
        return SableRagdollCompat.worldPositionOf(player);
    }

    public static double distance(ServerPlayer a, ServerPlayer b) {
        return SableRagdollCompat.distance(a, b);
    }

    /**
     * Revives a knocked/bleeding player with the default 50% max health.
     *
     * @return true if the player was bleeding and revived
     */
    public static boolean revivePlayer(ServerPlayer target) {
        return revivePlayer(target, 0.5F);
    }

    /**
     * Revives a knocked/bleeding player and restores {@code healthFraction * maxHealth}.
     *
     * @param healthFraction fraction of max health in (0, 1]
     * @return true if the player was bleeding and revived
     */
    public static boolean revivePlayer(ServerPlayer target, float healthFraction) {
        return BleedOutHandler.revivePlayerWithHealth(target, healthFraction);
    }

    /**
     * Finds the closest knocked/bleeding player aimed at by {@code reviver} within {@code maxDistance},
     * supporting both standard prone hitboxes and bleedout_ragdolls physics bodies.
     */
    public static @Nullable ServerPlayer findTargetedBleedingPlayer(ServerPlayer reviver, double maxDistance) {
        return BleedOutHandler.findTargetedBleedingPlayer(reviver, maxDistance);
    }
}
