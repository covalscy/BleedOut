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

/**
 * Рендерит иконку медицинского крестика над головой нокнутых союзников в 3D мире.
 * Отображается только для игроков в одной команде (scoreboard team).
 * Иконка видна сквозь стены (без теста глубины) и всегда повёрнута к камере (billboard).
 */
@Mod.EventBusSubscriber(modid = BleedOutMod.MODID, value = Dist.CLIENT)
public class KnockedTeammateRenderer {

    /**
     * Максимальная дистанция отображения иконки "нокнут" (в блоках).
     * Можно изменять для настройки.
     */
    public static double MAX_RENDER_DISTANCE = 50.0;

    @SuppressWarnings("removal")
    private static final ResourceLocation KNOCKED_ICON = new ResourceLocation(BleedOutMod.MODID, "textures/overlay/knocked_indicator.png");

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Рендерим после полупрозрачных блоков, чтобы иконка была поверх
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        if (!ClientConfig.enableKnockedTeammateRender)
            return;

        Player localPlayer = mc.player;
        Team localTeam = localPlayer.getTeam();

        // Если игрок не в команде — не отображаем ничего
        if (localTeam == null) return;

        Vec3 cameraPos = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        float partialTick = event.getPartialTick();

        // Получаем поворот камеры для billboard-эффекта
        Quaternionf cameraRotation = event.getCamera().rotation();

        for (Player player : mc.level.players()) {
            if (player == localPlayer || player.isSpectator())
                continue;

            // Проверяем, что игрок — союзник (в одной команде)
            Team playerTeam = player.getTeam();
            if (playerTeam == null || !localTeam.isAlliedTo(playerTeam))
                continue;

            // Проверяем, что игрок "нокнут"
            boolean isKnocked = isPlayerKnocked(player);
            if (!isKnocked) continue;

            // Проверяем дистанцию
            double distance = localPlayer.position().distanceTo(player.position());
            if (distance > MAX_RENDER_DISTANCE) continue;

            // Интерполяция позиции игрока
            double x = player.xo + (player.getX() - player.xo) * partialTick;
            double y = player.yo + (player.getY() - player.yo) * partialTick;
            double z = player.zo + (player.getZ() - player.zo) * partialTick;

            // Позиция в центре хитбокса
            double iconY = y + player.getBbHeight() / 2.0;

            // Рендерим billboard в мировых координатах
            poseStack.pushPose();

            // Переводим в координаты относительно камеры
            poseStack.translate(x - cameraPos.x, iconY - cameraPos.y, z - cameraPos.z);

            // Billboard: разворачиваем к камере
            poseStack.mulPose(cameraRotation);

            // Масштаб иконки: увеличивается с дистанцией, чтобы было видно издалека
            // Базовый размер + пропорция от расстояния (как в ESP-mod)
            float baseScale = 0.3f;
            float distScale = (float)(distance / 25.0); // Чем дальше, тем больше
            float scale = Math.max(baseScale, Math.min(baseScale + distScale, 2.0f)); // Ограничиваем min/max
            // -scale по Y, т.к. OpenGL ось Y инвертирована для текстур
            poseStack.scale(scale, -scale, scale);

            // Рисуем текстурированный quad
            renderIcon(poseStack, KNOCKED_ICON);

            poseStack.popPose();
        }
    }

    /**
     * Проверяет, является ли игрок "нокнутым" (в состоянии кровотечения).
     * Используем только синхронизированное сервером состояние через PersistentData,
     * чтобы не ловить ложные срабатывания на обычную лежачую позу.
     */
    private static boolean isPlayerKnocked(Player player) {
        return player.getPersistentData().getBoolean("bleedout:bleeding");
    }

    /**
     * Рендерит текстурированный quad (иконку) как billboard в 3D мире.
     * Без теста глубины — видно сквозь стены.
     */
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

        // Восстанавливаем состояние рендеринга
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
    }
}
