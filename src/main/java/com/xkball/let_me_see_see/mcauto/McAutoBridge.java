package com.xkball.let_me_see_see.mcauto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import com.xkball.let_me_see_see.LetMeSeeSee;
import com.xkball.let_me_see_see.client.offscreen.EntityRenders;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 给自动化用（mcauto agent）的入口。**必须在渲染线程调用**。
 *
 * 输出与 LMS 的「按 mcmod 格式导出」一致：
 *   <EXPORT_DIR_PATH>/<modid>.json        物品（原 Screen 负责）
 *   <EXPORT_DIR_PATH>/<modid>_entity.json 实体（本类新增）
 *
 * 实体条目沿用 IRR 的字段名，另加两个我们自己用的：
 *   name / Englishname / mod / registerName / Icon(base64 128×128)
 *   IconSmall  32×32 base64
 *   noVisual   true 表示该实体渲染不出任何像素（弹幕、座位、伤害源之类），
 *              下游不要把它当图标用
 */
@OnlyIn(Dist.CLIENT)
public final class McAutoBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int ICON_LARGE = 128;
    private static final int ICON_SMALL = 32;

    public static String exportEntities(String modid) throws IOException {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            throw new IllegalStateException("还没进世界，registryAccess 不可用");
        }
        ClientLanguage zh = ClientLanguage.loadFrom(mc.getResourceManager(), List.of("zh_cn"), false);
        ClientLanguage en = ClientLanguage.loadFrom(mc.getResourceManager(), List.of("en_us"), false);

        Path out = Path.of(LetMeSeeSee.EXPORT_DIR_PATH, (modid.isEmpty() ? "all" : modid) + "_entity.json");
        Files.createDirectories(out.getParent());

        int count = 0, noVisual = 0, suspect = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> e
                    : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                ResourceLocation id = e.getKey().location();
                if (!modid.isEmpty() && !id.getNamespace().equals(modid)) {
                    continue;
                }
                EntityType<?> type = e.getValue();
                String descId = type.getDescriptionId();
                JsonObject o = new JsonObject();
                o.addProperty("name", resolve(zh, descId));
                o.addProperty("Englishname", resolve(en, descId));
                o.addProperty("mod", id.getNamespace());
                o.addProperty("registerName", id.toString());

                // 手工图标覆盖：<EXPORT>/_icons/<modid>/<path>.png。
                // 有些实体的渲染器依赖运行时状态（射线类按长度拉伸、坐骑类要乘客），
                // 离屏渲染注定画不对，这种情况就放一张自己做好的图进来。
                var overrideFile = Path.of(LetMeSeeSee.EXPORT_DIR_PATH, "_icons",
                        id.getNamespace(), id.getPath() + ".png");
                if (Files.isRegularFile(overrideFile)) {
                    try {
                        String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(overrideFile));
                        o.addProperty("name", resolve(zh, descId));
                        o.addProperty("Englishname", resolve(en, descId));
                        o.addProperty("mod", id.getNamespace());
                        o.addProperty("registerName", id.toString());
                        o.addProperty("Icon", b64);
                        var overrideSmall = Path.of(LetMeSeeSee.EXPORT_DIR_PATH, "_icons",
                                id.getNamespace(), id.getPath() + "_32.png");
                        o.addProperty("IconSmall", Files.isRegularFile(overrideSmall)
                                ? Base64.getEncoder().encodeToString(Files.readAllBytes(overrideSmall))
                                : b64);
                        o.addProperty("iconSource", "override");
                        w.write(GSON.toJson(o));
                        w.newLine();
                        count++;
                        continue;
                    } catch (IOException ex) {
                        LOGGER.warn("读取图标覆盖失败 {}: {}", overrideFile, ex.toString());
                    }
                }

                var large = new EntityRenders.Result("", 0f, 0f);
                try {
                    large = EntityRenders.exportEntity(type, ICON_LARGE);
                } catch (Throwable t) {
                    LOGGER.warn("实体 {} 渲染失败: {}", id, t.toString());
                }
                if (large.png.isEmpty()) {
                    // 渲不出东西：要么没有可见模型，要么渲染抛错。两种都不该被当图标用
                    o.addProperty("Icon", "");
                    o.addProperty("IconSmall", "");
                    o.addProperty("noVisual", true);
                    noVisual++;
                } else {
                    String small;
                    try {
                        small = EntityRenders.exportEntity(type, ICON_SMALL).png;
                    } catch (Throwable t) {
                        small = "";
                    }
                    o.addProperty("Icon", large.png);
                    o.addProperty("IconSmall", small);
                    if (!large.renderer.isEmpty()) {
                        o.addProperty("renderer", large.renderer);
                        if (large.customRenderer) {
                            o.addProperty("customRenderer", true);
                        }
                    }
                    // 画满整屏 / 单色块 —— 多半是该实体的渲染器依赖额外状态，图标不可信
                    if (large.suspect()) {
                        o.addProperty("suspect", true);
                        o.addProperty("iconCoverage", Math.round(large.coverage * 100) / 100f);
                        o.addProperty("iconColorStd", Math.round(large.colorStd * 10) / 10f);
                        suspect++;
                    }
                }
                w.write(GSON.toJson(o));
                w.newLine();
                count++;
            }
        }
        LOGGER.info("mcauto: 导出 {} 个实体（{} 个无可见模型，{} 个图标可疑）→ {}",
                count, noVisual, suspect, out);
        return count > 0 ? out.toString() : null;
    }

    /**
     * 取本地化名。有些实体只注册了 entity.xxx.name 而不是 entity.xxx，
     * 直接 getOrDefault 会吐出原始 lang 键，看起来像 bug。
     */
    private static String resolve(ClientLanguage lang, String descId) {
        String n = lang.getOrDefault(descId);
        if (n == null || n.startsWith("entity.") || n.startsWith("item.")) {
            String alt = lang.getOrDefault(descId + ".name");
            if (alt != null && !alt.startsWith("entity.") && !alt.startsWith("item.")) {
                return alt;
            }
            return "";
        }
        return n;
    }

    /** 只报数量，供 agent 判断有没有东西可导 */
    public static int countEntities(String modid) {
        int n = 0;
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            if (modid.isEmpty() || id.getNamespace().equals(modid)) {
                n++;
            }
        }
        return n;
    }

    private McAutoBridge() {
    }
}
