package com.covalscy.bleedout.hitbox;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * Hit box of a prone player (knocked, crawling) that follows the lying body, at any angle.
 *
 * The body is described in its own frame (x = side, y = up, z = towards the head) and the ray is
 * rotated into that frame before clipping, so diagonals are exact. The AABB bounds of this object
 * are the outer box enclosing the rotated body, which keeps broad-phase checks working.
 *
 * Dimensions follow vanilla's crawl rendering: feet ~1.0 behind the position, head ~0.7 ahead,
 * body 0.1-0.5 above the ground.
 */
public class KnockedBodyBox extends AABB {
    private static final AABB HEAD = new AABB(-0.27D, 0.04D, 0.18D, 0.27D, 0.58D, 0.74D);
    private static final AABB BODY = new AABB(-0.40D, 0.06D, -1.04D, 0.40D, 0.50D, 0.20D);

    private final Vec3 origin;
    private final float yawDeg;
    private final double grow;
    private final double sin;
    private final double cos;

    public KnockedBodyBox(Vec3 origin, float yawDeg, double grow) {
        super(outer(origin, yawDeg, grow, true), outer(origin, yawDeg, grow, false));
        this.origin = origin;
        this.yawDeg = yawDeg;
        this.grow = grow;
        double rad = yawDeg * Mth.DEG_TO_RAD;
        this.sin = Math.sin(rad);
        this.cos = Math.cos(rad);
    }

    /**
     * Any player lying on the ground: knocked down, crawling under a trapdoor/slab, or TaCZ crawl
     * (TaCZ also uses the SWIMMING pose). Real swimming in water is excluded: there the body is tilted
     * by the look pitch and the vanilla box fits better.
     */
    public static boolean usesProneHitbox(Player player) {
        return player.getPose() == Pose.SWIMMING && !player.isInWater() && !player.isSpectator() && !player.isPassenger();
    }

    /** Body box of a knocked player standing at the bottom-center of {@code base} (may be a lag-compensated box). */
    public static KnockedBodyBox of(Player player, AABB base) {
        Vec3 origin = new Vec3((base.minX + base.maxX) * 0.5D, base.minY, (base.minZ + base.maxZ) * 0.5D);
        return new KnockedBodyBox(origin, player.yBodyRot, 0.0D);
    }

    // ------------------------------------------------------------------ frame conversion
    // forward (towards head) for yaw φ is (-sin φ, 0, cos φ), side is (cos φ, 0, sin φ)

    private Vec3 toLocal(Vec3 p) {
        double dx = p.x - origin.x;
        double dz = p.z - origin.z;
        return new Vec3(dx * cos + dz * sin, p.y - origin.y, -dx * sin + dz * cos);
    }

    private Vec3 toWorld(Vec3 l) {
        return new Vec3(origin.x + l.x * cos - l.z * sin, origin.y + l.y, origin.z + l.x * sin + l.z * cos);
    }

    private static Vec3 rotate(double x, double y, double z, double sin, double cos, Vec3 origin) {
        return new Vec3(origin.x + x * cos - z * sin, origin.y + y, origin.z + x * sin + z * cos);
    }

    private static Vec3 outer(Vec3 origin, float yawDeg, double grow, boolean min) {
        double rad = yawDeg * Mth.DEG_TO_RAD;
        double s = Math.sin(rad), c = Math.cos(rad);
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (AABB part : new AABB[]{HEAD, BODY}) {
            AABB p = part.inflate(grow);
            for (int i = 0; i < 8; i++) {
                Vec3 v = rotate((i & 1) == 0 ? p.minX : p.maxX, (i & 2) == 0 ? p.minY : p.maxY,
                        (i & 4) == 0 ? p.minZ : p.maxZ, s, c, origin);
                minX = Math.min(minX, v.x); minY = Math.min(minY, v.y); minZ = Math.min(minZ, v.z);
                maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y); maxZ = Math.max(maxZ, v.z);
            }
        }
        return min ? new Vec3(minX, minY, minZ) : new Vec3(maxX, maxY, maxZ);
    }

    // ------------------------------------------------------------------ precise tests

    @Override
    public Optional<Vec3> clip(Vec3 from, Vec3 to) {
        Vec3 lf = toLocal(from), lt = toLocal(to);
        Optional<Vec3> head = HEAD.inflate(grow).clip(lf, lt);
        Optional<Vec3> body = BODY.inflate(grow).clip(lf, lt);
        Optional<Vec3> best;
        if (head.isPresent() && body.isPresent()) {
            best = head.get().distanceToSqr(lf) <= body.get().distanceToSqr(lf) ? head : body;
        } else {
            best = head.isPresent() ? head : body;
        }
        return best.map(this::toWorld);
    }

    @Override
    public boolean contains(double x, double y, double z) {
        Vec3 l = toLocal(new Vec3(x, y, z));
        return HEAD.inflate(grow).contains(l) || BODY.inflate(grow).contains(l);
    }

    /** True if a world-space hit point lies in the head part. */
    public boolean isHeadHit(Vec3 worldHit) {
        return HEAD.inflate(grow + 0.02D).contains(toLocal(worldHit));
    }

    @Override
    public AABB inflate(double value) {
        return new KnockedBodyBox(origin, yawDeg, grow + value);
    }

    @Override
    public AABB inflate(double x, double y, double z) {
        return new KnockedBodyBox(origin, yawDeg, grow + Math.max(x, Math.max(y, z)));
    }
}
