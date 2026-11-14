# 这个 fork 改了什么

上游：[xkball/LetMeSeeSee](https://github.com/xkball/LetMeSeeSee)（LGPLv3）

我们只做**增量**：不删上游代码，不改现有导出逻辑。目的是让「进游戏 → 导出 → 收产物」
能被外部程序无人值守地驱动。

## 相对上游的两处新增

### 1. `client/offscreen/EntityRenders.java`（新文件）

实体图标导出。上游只导出物品（`ItemDataExporterScreen` 遍历 `BuiltInRegistries.ITEM`），
实体图标一直要靠 IRR / IconRenderer，而那两个都不支持 1.21.1。

做法沿用上游物品渲染的同一套 FBO 流程，多了三件事：

- **像素级归一化**：宽松渲染 → 扫非透明像素求包围盒 → 裁切 → 缩放。
  按包围盒猜缩放不可靠（射线类实体 bb 很小模型很长、环形弹幕 bb 又虚大）
- **FBO 直接用目标尺寸**，归一化只缩不放，缩小时用 alpha 加权面积平均 ——
  否则 32×32 会从大图降采样出一堆噪点
- **`noVisual` / `suspect` 判定** + 量化指标（coverage / colorStd / aspect / fill），
  给下游判断「这张图能不能用」

### 2. `mcauto/McAutoBridge.java`（新文件）

给外部自动化用的静态入口：

```java
public static String exportEntities(String modid)   // 写 <EXPORT_DIR>/<modid>_entity.json
public static int    countEntities(String modid)
```

单独成类的原因：调用方是独立的 Java agent（反射调用），不希望它依赖 GUI Screen 的
构造与字段初始化（`imageSize` 等字段在 `init()` 里才赋值）。

实体条目字段沿用 IRR 的格式，另加两个我们自己用的：

```json
{"name":"祸殃","Englishname":"Hoyi","mod":"...","registerName":"...:hoyi",
 "Icon":"<base64 128px>","IconSmall":"<base64 32px>",
 "renderer":"...HoyiRenderer","customRenderer":true,
 "noVisual":false,"suspect":false}
```

### 3. `OffScreenFBO.close()`（新增 public 方法）

一次性导出要建/销毁多个 FBO，上游 `deleteFBO()` 是 private。**不影响现有调用路径**。

## 用法（外部驱动）

```powershell
# 由 Java agent 在客户端里调用；也可自己写 mod 调
-javaagent:mcauto-agent.jar=mode=lmsent,timeout=600
-Dmcauto.modid=<要导出的命名空间>
```

agent 负责：等类加载 → 等进世界 → 把任务投到渲染线程 → 调 `McAutoBridge.exportEntities`。
完整流水线见 [mcmod-page](https://github.com/zgxhzhr/mcmod-page) 的 `scripts/mcauto/`。

## 实测

```
MC 1.21.1 / NeoForge 21.1.65
touhou_little_maid_personality  →  9 个实体（4 个判定无可见模型），23 秒
```

## 已知限制

- **部分实体的渲染器依赖运行时状态**，离屏渲染注定画不对，实测：
  - `StarflameRayRenderer`：按射线长度拉伸 ModelPart，静止渲染是一块大平面
  - `EntityBroomRender`（来自 TLM）：渲染「载着女仆的扫帚」，没有乘客画不对
  这类会被 `suspect` 标出来，需要人工出图（流水线支持 `_icons` 手工覆盖）
- 没做批次合批优化，实体数上百时可能偏慢
- 亮度写死 `0xF000F0`，没有按实体自身光照调整

## 关于合并上游

**不建议整体 merge 上游**。这是自用 fork，不是要回馈上游的补丁：

- 上游只维护 Fabric/NeoForge 主线，我们的改动全是「为外部自动化开的洞」
- 整体 merge 会把上游的版本相关改动带进来，我们的 `EntityRenders` 未必跟得上
- 需要上游新功能时，**按需 cherry-pick 单个 commit**，不要 merge

## 许可

上游是 LGPLv3，本 fork 沿用。分发时必须连源码一起给。
本 fork 的新增代码同样是 AI 辅助生成的，合并/使用前请自行验证 —— 我们只做了
「编译通过 + 跑通导出 + 看图对不对」，没有逐行核对 MC 渲染管线的正确做法。
