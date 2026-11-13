package com.xkball.let_me_see_see.client.offscreen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

/**
 * 实体图标渲染（mcmod 用）。仿 OffScreenRenders 的物品渲染流程：
 * 设正交投影 → 摆 PoseStack → 调 EntityRenderDispatcher.render → 由 FBO 取像素。
 *
 * 与物品的区别：实体原点在脚底，且有包围盒，所以要按 bbHeight/bbWidth 算缩放并居中。
 */
@OnlyIn(Dist.CLIENT)
public final class EntityRenders {

    /** 正面偏左的 3/4 视角，跟 mcmod 上常见的实体图标角度一致 */
    private static final float DEFAULT_YAW = 25.0f;

    public static String exportEntityAsPng(OffScreenFBO fbo, EntityType<?> type, float scaleMul) {
        fbo.renderOffScreen(() -> renderEntity(type, fbo.getWidth(), fbo.getHeight(), scaleMul));
        var result = fbo.getRenderResult();
        try {
            result.flipY();
            return java.util.Base64.getEncoder().encodeToString(result.asByteArray());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void renderEntity(EntityType<?> type, int width, int height, float scaleMul) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = type.create(mc.level);
        if (entity == null) {
            return;
        }
        // 摆正姿态：不继承任何朝向
        entity.setYRot(0f);
        entity.setYHeadRot(0f);
        entity.setXRot(0f);
        entity.setPos(0, 0, 0);

        float bbH = Math.max(entity.getBbHeight(), 0.4f);
        float bbW = Math.max(entity.getBbWidth(), 0.4f);
        // 视野按实体尺寸算：高或宽里较大的那个占画面 78%
        float span = Math.max(bbH, bbW) * 1.28f;
        float size = Math.min(width, height);
        float pxPerBlock = size / span;

        var proj = new Matrix4f();
        proj.setOrtho(0f, size, size, 0f, -2000f, 2000f);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);

        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.set(new Matrix4f());
        RenderSystem.applyModelViewMatrix();

        var dispatcher = mc.getEntityRenderDispatcher();
        boolean oldShadow = true;
        var buffer = net.minecraft.client.renderer.MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        var poseStack = new PoseStack();
        poseStack.pushPose();
        // 居中：水平居中，垂直让脚底落在画面下方 12% 处
        poseStack.translate(width / 2f, height * 0.88f, 0f);
        float s = pxPerBlock * scaleMul;
        // y 翻转（GUI 坐标系向下为正），与物品渲染一致
        poseStack.scale(s, -s, s);
        poseStack.mulPose(Axis.YP.rotationDegrees(DEFAULT_YAW));

        try {
            dispatcher.setRenderShadow(false);
        } catch (Throwable ignored) {
        }
        try {
            dispatcher.render(entity, 0, 0, 0, 0f, 1.0f, poseStack, buffer, 0xF000F0);
            buffer.endBatch();
        } finally {
            try {
                dispatcher.setRenderShadow(oldShadow);
            } catch (Throwable ignored) {
            }
        }

        poseStack.popPose();
        modelView.popMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private EntityRenders() {
    }
}
