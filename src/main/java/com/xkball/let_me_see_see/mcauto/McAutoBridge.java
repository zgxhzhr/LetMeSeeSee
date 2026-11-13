package com.xkball.let_me_see_see.mcauto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.xkball.let_me_see_see.LetMeSeeSee;
import com.xkball.let_me_see_see.client.offscreen.EntityRenders;
import com.xkball.let_me_see_see.client.offscreen.OffScreenFBO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * 给自动化用（mcauto agent）的入口。**必须在渲染线程调用**。
 *
 * 为什么单独开类：agent 是独立 jar 通过反射调用的，不想依赖 GUI 那个 Screen 的构造
 * 与字段初始化（ImageSize 等），也不想让 agent 自己做语言切换与图标编码。
 *
 * 输出与 LMS 的「按 mcmod 格式导出」一致：
 *   <EXPORT_DIR_PATH>/<modid>.json        物品（由原 Screen 负责）
 *   <EXPORT_DIR_PATH>/<modid>_entity.json 实体（本类新增）
 *
 * 实体条目沿用 IRR 的字段名（mcmod 侧按这个格式读）：
 *   {"name": 中文名, "Englishname": 英文名, "mod": 命名空间,
 *    "registerName": "modid:path", "Icon": base64 PNG}
 */
@OnlyIn(Dist.CLIENT)
public final class McAutoBridge {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /** 实体图标两档尺寸，跟物品一致 */
    private static final int ICON_SMALL = 32;
    private static final int ICON_LARGE = 128;

    /**
     * 导出某个命名空间的全部实体。
     *
     * @param modid 命名空间（留空表示全部）
     * @return 写出的文件路径；没有实体时返回 null
     */
    public static String exportEntities(String modid) throws IOException {
        var mc = Minecraft.getInstance();
        if (mc.level == null) {
            throw new IllegalStateException("还没进世界，registryAccess 不可用");
        }
        ClientLanguage zh = ClientLanguage.loadFrom(mc.getResourceManager(), List.of("zh_cn"), false);
        ClientLanguage en = ClientLanguage.loadFrom(mc.getResourceManager(), List.of("en_us"), false);

        Path out = Path.of(LetMeSeeSee.EXPORT_DIR_PATH, (modid.isEmpty() ? "all" : modid) + "_entity.json");
        Files.createDirectories(out.getParent());

        OffScreenFBO large = new OffScreenFBO(ICON_LARGE, ICON_LARGE);
        int count = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<net.minecraft.resources.ResourceKey<EntityType<?>>, EntityType<?>> e
                    : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                ResourceLocation id = e.getKey().location();
                if (!modid.isEmpty() && !id.getNamespace().equals(modid)) {
                    continue;
                }
                EntityType<?> type = e.getValue();
                String descId = type.getDescriptionId();
                JsonObject o = new JsonObject();
                o.addProperty("name", zh.getOrDefault(descId));
                o.addProperty("Englishname", en.getOrDefault(descId));
                o.addProperty("mod", id.getNamespace());
                o.addProperty("registerName", id.toString());
                try {
                    o.addProperty("Icon", EntityRenders.exportEntityAsPng(large, type, 1.0f));
                } catch (Throwable t) {
                    LOGGER.warn("实体 {} 渲染失败: {}", id, t.toString());
                    o.addProperty("Icon", "");
                }
                w.write(GSON.toJson(o));
                w.newLine();
                count++;
            }
        }
        LOGGER.info("mcauto: 导出 {} 个实体 → {}", count, out);
        return count > 0 ? out.toString() : null;
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
