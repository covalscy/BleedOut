package com.covalscy.bleedout;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Quaternionf;


@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, value = Dist.CLIENT)
public class KnockedTeammateRenderer {


    public static double MAX_RENDER_DISTANCE = 50.0;

    @SuppressWarnings("removal")
    private static final ResourceLocation KNOCKED_ICON = new ResourceLocation(BleedOutMod.MODID, "textures/overlay/knocked_indicator.png");

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
        float partialTick = event.getPartialTick();

        Quaternionf cameraRotation = event.getCamera().rotation();

        for (Player player : mc.level.players()) {
            if (player == localPlayer || player.isSpectator())
                continue;

            Team playerTeam = player.getTeam();
            if (playerTeam == null || !localTeam.isAlliedTo(playerTeam))
                continue;

            boolean isKnocked = isPlayerKnocked(player);
            if (!isKnocked) continue;

            double distance = localPlayer.position().distanceTo(player.position());
            if (distance > MAX_RENDER_DISTANCE) continue;

            double x = player.xo + (player.getX() - player.xo) * partialTick;
            double y = player.yo + (player.getY() - player.yo) * partialTick;
            double z = player.zo + (player.getZ() - player.zo) * partialTick;
            double iconY = y + player.getBbHeight() / 2.0;

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

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);

        builder.vertex(matrix, -half, -half, 0).uv(0, 0).endVertex();
        builder.vertex(matrix,  half, -half, 0).uv(1, 0).endVertex();
        builder.vertex(matrix,  half,  half, 0).uv(1, 1).endVertex();
        builder.vertex(matrix, -half,  half, 0).uv(0, 1).endVertex();

        BufferUploader.drawWithShader(builder.end());

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
