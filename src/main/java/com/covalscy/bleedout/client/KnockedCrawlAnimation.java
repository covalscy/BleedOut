package com.covalscy.bleedout.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Elbow crawl for knocked players. The body is already lying prone thanks to the forced SWIMMING pose;
 * here we only replace the limb animation (vanilla would play the swimming stroke).
 *
 * In the prone frame: arm xRot 0 = along the body towards the feet, -PI/2 = straight into the ground,
 * -PI = forward past the head. Elbow crawl swings each arm between "reached forward" and "pulled under
 * the shoulder", alternating left and right, while the legs just drag.
 */
public final class KnockedCrawlAnimation {
    private static final float ARM_IDLE = -2.25F;
    private static final float ARM_SWING = 0.5F;
    private static final float ELBOW_OUT = 0.45F;
    private static final float MOVE_DEADZONE = 0.04F;

    public static void apply(PlayerModel<?> m, float limbSwing, float limbSwingAmount, float age) {
        float w = m.swimAmount; // 0..1, vanilla eases this in over ~0.5 s: free "falling down" transition
        if (w <= 0.0F) return;

        // Dead zone: while lying still the server keeps nudging the position by tiny amounts, which made
        // limbSwingAmount flicker around zero and the arms twitch. Below the threshold the arms are fully still.
        float move = Mth.clamp((limbSwingAmount - MOVE_DEADZONE) * 5.0F, 0.0F, 1.0F);
        move = move * move * (3.0F - 2.0F * move); // smoothstep: eases in/out instead of snapping
        float phase = limbSwing * 1.6F;
        float s = Mth.sin(phase);
        float c = Mth.cos(phase);
        float breath = Mth.sin(age * 0.12F) * 0.04F;

        // arms: alternate reach / pull; the reaching arm also flares out a bit
        float rightX = ARM_IDLE + ARM_SWING * s * move;
        float leftX = ARM_IDLE - ARM_SWING * s * move;
        float rightZ = ELBOW_OUT + 0.15F * Math.max(0.0F, s) * move;
        float leftZ = -ELBOW_OUT - 0.15F * Math.max(0.0F, -s) * move;
        blend(m.rightArm, w, rightX, -0.2F, rightZ);
        blend(m.leftArm, w, leftX, 0.2F, leftZ);

        // legs: limp, slightly spread, a small drag wobble opposite to the arms
        blend(m.rightLeg, w, 0.08F + 0.1F * c * move, 0.0F, 0.12F);
        blend(m.leftLeg, w, 0.08F - 0.1F * c * move, 0.0F, -0.12F);

        // head: keep vanilla look direction, add a heavy bob with each pull and breathing
        m.head.xRot += (0.08F * Math.abs(s) * move + breath) * w;
        m.head.zRot = Mth.lerp(w, m.head.zRot, 0.08F * s * move);

        m.hat.copyFrom(m.head);
        m.rightSleeve.copyFrom(m.rightArm);
        m.leftSleeve.copyFrom(m.leftArm);
        m.rightPants.copyFrom(m.rightLeg);
        m.leftPants.copyFrom(m.leftLeg);
        m.jacket.copyFrom(m.body);
    }

    private static void blend(ModelPart part, float w, float x, float y, float z) {
        part.xRot = Mth.lerp(w, part.xRot, x);
        part.yRot = Mth.lerp(w, part.yRot, y);
        part.zRot = Mth.lerp(w, part.zRot, z);
    }

    private KnockedCrawlAnimation() {
    }
}
