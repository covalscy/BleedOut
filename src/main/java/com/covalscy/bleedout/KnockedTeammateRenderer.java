package com.covalscy.bleedout;

import com.covalscy.bleedout.compat.ragdoll.SableRagdollClientCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;


@EventBusSubscriber(modid = BleedOutMod.MODID, value = Dist.CLIENT)
public class KnockedTeammateRenderer {

    public static double MAX_RENDER_DISTANCE = 50.0;

    private static final ResourceLocation KNOCKED_ICON = ResourceLocation.fromNamespaceAndPath(BleedOutMod.MODID, "textures/overlay/knocked_indicator.png");

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        if (!ClientConfig.enableKnockedTeammateRender)
            return;

        Player localPlayer = mc.player;
        Team localTeam = localPlayer.getTeam();

        if (localTeam == null) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        Quaternionf cameraRotation = event.getCamera().rotation();

        for (Player player : mc.level.players()) {
            if (player == localPlayer || player.isSpectator())
                continue;

            Team playerTeam = player.getTeam();
            if (playerTeam == null || !localTeam.isAlliedTo(playerTeam))
                continue;

            boolean isKnocked = isPlayerKnocked(player);
            if (!isKnocked) continue;

            Vec3 pos = SableRagdollClientCompat.getWorldPosition(player, partialTick);
            double distance = localPlayer.position().distanceTo(pos);
            if (distance > MAX_RENDER_DISTANCE) continue;

            double x = pos.x;
            double y = pos.y;
            double z = pos.z;

            double iconY = y + (SableRagdollClientCompat.isRidingRagdoll(player) ? 0.5 : player.getBbHeight() / 2.0);

            poseStack.pushPose();

            poseStack.translate(x - cameraPos.x, iconY - cameraPos.y, z - cameraPos.z);
            poseStack.mulPose(cameraRotation);

            float baseScale = 0.3f;
            float distScale = (float)(distance / 25.0);
            float scale = Math.max(baseScale, Math.min(baseScale + distScale, 2.0f));
            poseStack.scale(scale, -scale, scale);

            renderIcon(poseStack, KNOCKED_ICON);

            poseStack.popPose();
        }
    }

    private static boolean isPlayerKnocked(Player player) {
        return player.getPersistentData().getBoolean("bleedout:bleeding");
    }

    private static void renderIcon(PoseStack poseStack, ResourceLocation texture) {
        RenderSystem.setShaderTexture(0, texture);
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        Matrix4f matrix = poseStack.last().pose();
        float half = 0.5f;

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder builder = tesselator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        builder.addVertex(matrix, -half, -half, 0).setUv(0, 0);
        builder.addVertex(matrix,  half, -half, 0).setUv(1, 0);
        builder.addVertex(matrix,  half,  half, 0).setUv(1, 1);
        builder.addVertex(matrix, -half,  half, 0).setUv(0, 1);

        BufferUploader.drawWithShader(builder.buildOrThrow());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
