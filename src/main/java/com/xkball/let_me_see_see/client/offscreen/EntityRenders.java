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
 * 流程：正交投影 → 光照 setupForEntityInInventory → PoseStack 摆位 →
 *      EntityRenderDispatcher.render → FBO 取像素 → 按可见区裁切归一化。
 *
 * 两个坑（实测踩过）：
 *  1. 不调 Lighting.setupForEntityInInventory()，依赖光照的渲染器会画错/画成纯色块
 *  2. 按包围盒猜缩放不可靠（射线类实体 bb 很小、模型很长；环形弹幕 bb 又虚大）
 *     所以改成「宽松渲染 + 像素级裁切」，见 normalize()
 */
@OnlyIn(Dist.CLIENT)
public final class EntityRenders {

    public static final float DEFAULT_YAW = 25.0f;
    private static final float RENDER_MARGIN = 1.75f;
    private static final float PADDING = 0.06f;

    /** 渲染结果 + 质量指标 */
    public static final class Result {
        public final String png;
        /** 非透明像素占画面比例；1.0 附近说明画满了整屏（多半是画崩了） */
        public final float coverage;
        /** 非透明像素的颜色标准差（0~255）；很小说明是单色块 */
        public final float colorStd;

        public Result(String png, float coverage, float colorStd) {
            this.png = png;
            this.coverage = coverage;
            this.colorStd = colorStd;
        }

        /** 可见区包围盒宽高比（w/h）；人形约 0.3~0.7，扁平四边形接近 1 */
        public float bboxAspect = 1f;
        /** 可见像素在包围盒内的填充率；实心四边形接近 1，立体模型约 0.3~0.6 */
        public float bboxFill = 1f;
        /** 可见区尺寸 / 按包围盒推算的期望尺寸；远大于 1 说明模型尺寸与碰撞箱不符 */
        public float sizeRatio = 1f;

        /** 渲染器类名，便于判断是否自定义渲染器 */
        public String renderer = "";
        /** 自定义渲染器（不在 net.minecraft 包下）：更容易依赖特定渲染状态 */
        public boolean customRenderer = false;

        /** 判定依据（命中哪条规则），空串表示正常 */
        public String suspectReason = "";

        /**
         * 判定「这个图标不可信」。
         *
         * 阈值是在 Touhou Little Maid Personality 的 9 个实体上量出来的（样本小，见下），
         * 每条都对应一类已知的画崩形态：
         *
         *   画满屏      coverage > 0.85
         *               渲染器把东西画成铺满整个画面
         *   单色块      colorStd < 6
         *               没有贴图/纯色填充
         *   扁平四边形  std < 32 且 aspect ∈ [0.85, 1.25]
         *               实测 starflame_ray：射线按长度拉伸成一块近方形大平面，
         *               颜色变化低（std 29）但又有纹理，所以抓不到前两条
         *   极扁长      aspect > 3.0
         *               实测 maid_broom：模型横躺，宽高比 3.86
         *
         * 量到的对照值：hoyi std 37.9 aspect 0.58 / lost_fairy 56.6, 0.69 /
         *              spring_flower_arrow 59.4, 1.19 —— 三者都不会被误判。
         *
         * ⚠️ 样本只有 5 个可见实体，换模组后可能需要调整；误判就用 _icons 手工覆盖。
         */
        public String suspectDetail() {
            if (png.isEmpty()) {
                return "";
            }
            if (coverage > 0.85f) {
                return "coverage";
            }
            if (colorStd < 6f) {
                return "flat-color";
            }
            if (colorStd < 32f && bboxAspect >= 0.85f && bboxAspect <= 1.25f) {
                return "flat-quad";
            }
            if (bboxAspect > 3.0f) {
                return "extreme-aspect";
            }
            return "";
        }

        public boolean suspect() {
            return !suspectDetail().isEmpty();
        }
    }

    public static Result exportEntity(EntityType<?> type, int size) {
        return exportEntity(type, size, DEFAULT_YAW);
    }

    public static Result exportEntity(EntityType<?> type, int size, float yaw) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            return new Result("", 0f, 0f);
        }
        Entity probe = type.create(mc.level);
        if (probe == null) {
            return new Result("", 0f, 0f);
        }
        var fbo = new OffScreenFBO(size, size);
        Result r;
        try {
            fbo.renderOffScreen(() -> renderEntity(probe, fbo.getWidth(), fbo.getHeight(), RENDER_MARGIN, yaw));
            var raw = fbo.getRenderResult();
            raw.flipY();
            float[] stats = analyze(raw);
            var normalized = normalize(raw, size);
            String png = normalized == null
                    ? ""
                    : Base64.getEncoder().encodeToString(normalized.asByteArray());
            r = new Result(png, stats[0], stats[1]);
            r.bboxAspect = stats[2];
            r.bboxFill = stats[3];
            // 可见区最长边 vs 按碰撞箱推算的期望像素：模型明显比碰撞箱大 = 渲染不受控
            float bbMax = Math.max(Math.max(probe.getBbHeight(), 0.4f), Math.max(probe.getBbWidth(), 0.4f));
            float expectedPx = size / (bbMax * RENDER_MARGIN) * bbMax;   // = size / margin
            float actualPx = Math.max(stats[4], stats[5]);
            r.sizeRatio = actualPx / Math.max(expectedPx, 0.001f);
        } catch (Throwable t) {
            r = new Result("", 0f, 0f);
        } finally {
            try {
                fbo.close();
            } catch (Throwable ignored) {
            }
        }
        // 记下用了哪个渲染器：自定义渲染器更容易依赖特定渲染状态，图标不可信时便于定位
        try {
            var renderer = mc.getEntityRenderDispatcher().getRenderer(probe);
            r.renderer = renderer.getClass().getName();
            r.customRenderer = !r.renderer.startsWith("net.minecraft.");
        } catch (Throwable ignored) {
        }
        return r;
    }

    /** 兼容旧调用 */
    public static String exportEntityAsPng(EntityType<?> type, int size) {
        return exportEntity(type, size).png;
    }

    /** @return {coverage, colorStd, bboxAspect, bboxFill, bboxW, bboxH} */
    private static float[] analyze(NativeImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int total = w * h;
        int opaque = 0;
        double sR = 0, sG = 0, sB = 0, sR2 = 0, sG2 = 0, sB2 = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getPixelRGBA(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a <= 8) {
                    continue;
                }
                opaque++;
                int b = argb & 0xFF, g = (argb >>> 8) & 0xFF, r = (argb >>> 16) & 0xFF;
                sR += r; sG += g; sB += b;
                sR2 += (double) r * r; sG2 += (double) g * g; sB2 += (double) b * b;
            }
        }
        if (opaque == 0) {
            return new float[]{0f, 0f, 1f, 1f, 0f, 0f};
        }
        double n = opaque;
        double varR = Math.max(0, sR2 / n - (sR / n) * (sR / n));
        double varG = Math.max(0, sG2 / n - (sG / n) * (sG / n));
        double varB = Math.max(0, sB2 / n - (sB / n) * (sB / n));
        double std = Math.sqrt((varR + varG + varB) / 3.0);

        // 包围盒与填充率
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (img.getPixelRGBA(x, y) >>> 24) & 0xFF;
                if (a > 8) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        int bw = Math.max(1, maxX - minX + 1);
        int bh = Math.max(1, maxY - minY + 1);
        float aspect = bw / (float) bh;
        float fill = opaque / (float) (bw * bh);
        return new float[]{(float) opaque / total, (float) std, aspect, fill, bw, bh};
    }

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
            return null;
        }
        int bw = maxX - minX + 1;
        int bh = maxY - minY + 1;
        int inner = Math.max(1, Math.round(size * (1f - 2 * PADDING)));
        // 只缩不放：放大只会更糊
        float scale = Math.min(1.0f, Math.min(inner / (float) bw, inner / (float) bh));
        int dw = Math.max(1, Math.round(bw * scale));
        int dh = Math.max(1, Math.round(bh * scale));

        var out = new NativeImage(size, size, false);
        int ox = (size - dw) / 2;
        int oy = (size - dh) / 2;
        float srcPerDstX = bw / (float) dw;
        float srcPerDstY = bh / (float) dh;
        for (int y = 0; y < dh; y++) {
            int y0 = minY + Math.round(y * srcPerDstY);
            int y1 = Math.max(minY + Math.round((y + 1) * srcPerDstY), y0 + 1);
            for (int x = 0; x < dw; x++) {
                int x0 = minX + Math.round(x * srcPerDstX);
                int x1 = Math.max(minX + Math.round((x + 1) * srcPerDstX), x0 + 1);
                long aSum = 0, rSum = 0, gSum = 0, bSum = 0;
                int n = 0;
                for (int sy = y0; sy < Math.min(y1, minY + bh); sy++) {
                    for (int sx = x0; sx < Math.min(x1, minX + bw); sx++) {
                        int argb = src.getPixelRGBA(sx, sy);
                        int a = (argb >>> 24) & 0xFF;
                        n++;
                        if (a <= 8) {
                            continue;
                        }
                        int b = argb & 0xFF, g = (argb >>> 8) & 0xFF, r = (argb >>> 16) & 0xFF;
                        aSum += (long) a * a;
                        rSum += (long) r * a;
                        gSum += (long) g * a;
                        bSum += (long) b * a;
                    }
                }
                if (n == 0 || aSum == 0) {
                    continue;
                }
                int a = (int) Math.min(255, Math.sqrt((double) aSum / n));
                double wsum = aSum / 255.0;
                int r = (int) Math.min(255, rSum / wsum);
                int g = (int) Math.min(255, gSum / wsum);
                int b = (int) Math.min(255, bSum / wsum);
                // NativeImage 像素是 ABGR
                out.setPixelRGBA(ox + x, oy + y, (a << 24) | (b << 0) | (g << 8) | (r << 16));
            }
        }
        return out;
    }

    public static void renderEntity(Entity entity, int width, int height, float margin) {
        renderEntity(entity, width, height, margin, DEFAULT_YAW);
    }

    public static void renderEntity(Entity entity, int width, int height, float margin, float yaw) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || entity == null) {
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
        poseStack.translate(width / 2f, height * 0.78f, 0f);
        poseStack.scale(pxPerBlock, -pxPerBlock, pxPerBlock);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));

        try {
            // 不用 Lighting.setupForEntityInInventory：实测会把 atlas 状态带进来，
            // 使部分实体出现残影（迷失妖精）。保持与物品渲染一致的环境更稳。
            dispatcher.setRenderShadow(false);
            dispatcher.render(entity, 0, 0, 0, 0f, 1.0f, poseStack, buffer, 0xF000F0);
            buffer.endBatch();
        } catch (Throwable ignored) {
        } finally {
            // 无光照状态需要恢复
        }

        poseStack.popPose();
        modelView.popMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    private EntityRenders() {
    }
}
