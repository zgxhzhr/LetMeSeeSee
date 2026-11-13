package com.xkball.let_me_see_see.client.offscreen;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

import java.util.Base64;

/**
 * 实体图标渲染（mcmod 用）。
 *
 * 流程：正交投影 → PoseStack 摆位 → EntityRenderDispatcher.render → FBO 取像素
 *      → **按非透明像素包围盒裁切并重新居中缩放** → 输出指定边长。
 *
 * 为什么要在像素层归一化：
 *   1. 实体包围盒和实际可见外形差别很大（弹幕类 bb 很大但只有细线）
 *   2. 大而空的实体（环形弹幕）按 bb 算会超出画面被裁
 *   3. 小实体（箭、弹）按 bb 算又太小
 *   先按宽松倍率渲一遍，量出真实可见区域，再裁切缩放，比猜倍率稳。
 */
@OnlyIn(Dist.CLIENT)
public final class EntityRenders {

    /** 正面偏左的 3/4 视角，与 mcmod 上常见实体图标角度一致 */
    private static final float DEFAULT_YAW = 25.0f;
    /** 先按这个倍率宽松渲染，再裁切 —— 宁可渲小一点也不要裁掉 */
    private static final float RENDER_MARGIN = 1.75f;
    /** 归一化后四周留白比例 */
    private static final float PADDING = 0.06f;

    /**
     * @param size 输出正方形边长（32 / 128）
     * @return base64 PNG；实体没有可见像素时返回空串
     */
    public static String exportEntityAsPng(EntityType<?> type, int size) {
        // FBO 直接用目标尺寸：归一化时缩放接近 1:1，避免降采样噪点
        var fbo = new OffScreenFBO(size, size);
        try {
            fbo.renderOffScreen(() -> renderEntity(type, fbo.getWidth(), fbo.getHeight(), RENDER_MARGIN));
            var raw = fbo.getRenderResult();
            raw.flipY();
            var normalized = normalize(raw, size);
            if (normalized == null) {
                return "";
            }
            return Base64.getEncoder().encodeToString(normalized.asByteArray());
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                fbo.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 裁到非透明包围盒 → 等比缩放到 size×size → 居中留白。
     * 全透明时返回 null（调用方据此判定「无可见模型」）。
     */
    private static NativeImage normalize(NativeImage src, int size) {
        int w = src.getWidth();
        int h = src.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (src.getPixelRGBA(x, y) >>> 24) & 0xFF;
                if (a > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return null;   // 没画出来任何东西
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        int inner = Math.max(1, Math.round(size * (1f - 2 * PADDING)));
        // 只允许缩小，不放大（放大只会变糊）
        float scale = Math.min(1.0f, Math.min(inner / (float) bw, inner / (float) bh));
        int dw = Math.max(1, Math.round(bw * scale));
        int dh = Math.max(1, Math.round(bh * scale));

        var out = new NativeImage(size, size, false);
        int ox = (size - dw) / 2;
        int oy = (size - dh) / 2;
        // 面积平均采样：每个目标像素把覆盖到的源像素做 alpha 加权平均。
        // 直接最近邻会把边缘半透明像素打散成噪点（32×32 尤其明显）。
        float srcPerDstX = bw / (float) dw;
        float srcPerDstY = bh / (float) dh;
        for (int y = 0; y < dh; y++) {
            int y0 = minY + Math.round(y * srcPerDstY);
            int y1 = minY + Math.round((y + 1) * srcPerDstY);
            y1 = Math.max(y1, y0 + 1);
            for (int x = 0; x < dw; x++) {
                int x0 = minX + Math.round(x * srcPerDstX);
                int x1 = minX + Math.round((x + 1) * srcPerDstX);
                x1 = Math.max(x1, x0 + 1);
                long aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                int n = 0;
                for (int sy = y0; sy < Math.min(y1, minY + bh); sy++) {
                    for (int sx = x0; sx < Math.min(x1, minX + bw); sx++) {
                        int argb = src.getPixelRGBA(sx, sy);
                        int a = (argb >>> 24) & 0xFF;
                        if (a <= 8) {
                            n++;
                            continue;
                        }
                        // NativeImage 的像素是 ABGR 排列
                        int b = argb & 0xFF;
                        int g = (argb >>> 8) & 0xFF;
                        int r = (argb >>> 16) & 0xFF;
                        aSum += (long) a * a;
                        rSum += (long) r * a;
                        gSum += (long) g * a;
                        bSum += (long) b * a;
                        n++;
                    }
                }
                if (n == 0 || aSum == 0) {
                    continue;
                }
                int a = (int) Math.min(255, Math.sqrt((double) aSum / n));
                int r = (int) (rSum / (aSum / 255.0));
                int g = (int) (gSum / (aSum / 255.0));
                int b = (int) (bSum / (aSum / 255.0));
                int px = (a << 24) | (Math.min(255, b) << 0) | (Math.min(255, g) << 8) | (Math.min(255, r) << 16);
                out.setPixelRGBA(ox + x, oy + y, px);
            }
        }
        return out;
    }

    /** 按宽松倍率渲染，像素归一化交给 normalize */
    public static void renderEntity(EntityType<?> type, int width, int height, float margin) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity entity = type.create(mc.level);
        if (entity == null) {
            return;
        }
        entity.setYRot(0f);
        entity.setYHeadRot(0f);
        entity.setXRot(0f);
        entity.setPos(0, 0, 0);

        float bbH = Math.max(entity.getBbHeight(), 0.4f);
        float bbW = Math.max(entity.getBbWidth(), 0.4f);
        float span = Math.max(bbH, bbW) * margin;
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
        var buffer = MultiBufferSource.immediate(new ByteBufferBuilder(1536));
        var poseStack = new PoseStack();
        poseStack.pushPose();
        // 画面中心略偏下：让脚底落在 78% 高度处（normalize 会再居中，这里只需不裁掉）
        poseStack.translate(width / 2f, height * 0.78f, 0f);
        poseStack.scale(pxPerBlock, -pxPerBlock, pxPerBlock);
        poseStack.mulPose(Axis.YP.rotationDegrees(DEFAULT_YAW));

        try {
            dispatcher.setRenderShadow(false);
        } catch (Throwable ignored) {
        }
        try {
            dispatcher.render(entity, 0, 0, 0, 0f, 1.0f, poseStack, buffer, 0xF000F0);
            buffer.endBatch();
        } catch (Throwable ignored) {
        }

        poseStack.popPose();
        modelView.popMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private EntityRenders() {
    }
}
