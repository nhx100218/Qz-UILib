# 规划-headless 运行时（Qt offscreen 式）与验收设施（立项草案）

**状态：** **已冻结**（2026-09-17，第三轮：§六 七个岔路已由用户裁定，见 §六）。M0 技术闸门已在本地实测打通（F7）；
本档自冻结起构成 M1 施工依据，施工中新出现的岔路回填 §六 并标注日期。
**定位（用户补充，2026-09-17）：** 对齐 **Qt `-platform offscreen`** 的形态——**同一份 UI 代码**在无游戏、无窗口的
进程里装配、布局、绘制，可脚本化注入输入、可导出像素。**首要用途是 agent 与开发者的快速迭代**
（不开游戏、改一行→秒级出图→看图判断），**测试/CI 回归是顺带用途，不是主用途**。
**目标仓：** Qz-UILib（branch `4.0`；MC 1.7.10 / Java 8 语法基线 / GTNH `2.9.0-beta-3`）。
**来源：** 用户发起「QzUILib 立项：完善 headless 设施，分辨率覆盖 360P~2K，不再采用纯软光栅，可用 OpenGL」，
补充口径「需要包含完整的 headless 纯代码输入（鼠标键盘等）方式」「非 CI 用途，主要类似 QT 的无头模式快速验证功能，
以及方便 agent 不开游戏快速渲染出图」。
**基线：** 本档全部事实为 2026-09-17 一手实测（探针脚本 + 只读命令），**未跑真机**。

## 一、用途与需求口径

| 编号 | 用途 | 场景 | 验收目标 |
|---|---|---|---|
| P1 | **agent 快速出图** | agent 改完 UI 代码，不开游戏，一条命令拿 PNG 自行判读 | 冷启动到出图 **≤ 3 s**（快路径）；页面/尺寸/输出可参数化 |
| P2 | **开发者快速验证功能** | 不启游戏验证布局、交互与观感 | 可指定页面与输入脚本；产物路径可预期；失败可诊断 |
| P3 | 回归验收（顺带） | 本地/CI 批量多分辨率冒烟 | 见 §四 B / §四 D |
| R1 | 完善 headless 设施 | — | §三 四件套 |
| R2 | 分辨率覆盖 360P~2K | — | §四 B |
| R3 | 不再纯软光栅，可用 OpenGL | — | §四 A |
| R4 | 完整纯代码输入（鼠标键盘等） | — | §四 C |

**与 Qt offscreen 的已知差距（诚实声明，非对齐项）：**

1. Qt offscreen 在 Linux **无需 X**；本仓 LWJGL2 路径在 Linux **必须 Xvfb**（F8）——本地 Windows agent 不受影响，
   只有无桌面的 Linux 服务器才需要；
2. Qt 有成熟的 `grab()` 与 `QTest` 输入注入；本仓要自建（§四 C/D）。

## 二、立项前一手核查事实

### F1 现有 headless 有两条互不相通的通道，都不覆盖「整帧」

- **字体出图通道**（`src/test/java/club/heiqi/uilib/font/render/software/`）：与生产共享 `TextLayoutService` /
  `GlyphGenerator` / `DefaultFontRendererAdapter.renderSegmentsToCollector` / `GlyphBatchCollector` /
  `GlyphPage`（真 skyline 槽位分配 + 真上传路径，只把 `GlApi` 换成 `SoftwareGlApi`），
  **唯一分叉在尾端**：真机 `FontBatchRenderer.flush` 走 GL，headless 走 `FontSoftwareRasterizer`（CPU 逐像素）。
- **scene 通道**（`ui/scene/testkit/ScenePaintCapture`）：`layout → paint → replay → RecordingRenderBackend`，
  **只记录 draw call，不产像素**。testkit 包注释明文：「变换后的最终像素位置属 GPU 顶点层，纯 JUnit mock
  backend 不可观测」。

### F2 帧驱动已平台无关，缺的只是「真后端 + 真上下文」

- `AbstractSceneHostWidget.render(int w, int h, UiRenderBackend ctx, int absX, int absY)` 接受任意后端
  （宿主子类只调 `super.render`，此处是唯一挂点）。
- `SceneHostAssembly.assemble(measurer, inputSource)` 是 runtime / layoutEngine / paintEngine / replayer / pipeline
  五件套唯一装配点；`inputSource` 可为 `null`（无输入退化模式）。
- `UiRenderBackend` 的**抽象方法只有 13 个**：`fillRect` / `drawSurface` / `drawBorder` / `pushClip` /
  `popClip` / `drawText`×2 / `pushGroupOpacity` / `popGroupOpacity` / `pushTransform` / `popTransform` /
  `pushTransformLayer` / `popTransformLayer`（源码逐行复核）。另有 7 个 `default` 方法：`scaled` /
  `publishTextDemand` / `drawImage` / `drawSurface`（分角圆角重载）/ `drawText`（两处 7 参重载）/ `drawSegments`。
  **后端可替换面很窄，是本立项最有利的结构事实**；但要留意 `drawImage` / `drawSegments` /
  `publishTextDemand` 都带**静默 no-op 兜底**——headless 后端不显式实现时，缺图与缺富文本不会报错，
  只会静默少内容（本仓反复踩的「能力探测静默降级」模式）。故出图完整性必须有显式判据，不能只看「没抛异常」。

### F3 输入事件模型完整，注入入口不完整

- `RawInputEvent`（`ui/scene/input/`）：`RawEventKind` = KEY / POINTER / TEXT 三类，已含 4 修饰键、
  `nativeKeyCode`、`nativeScanCode`、`wheelDelta`、`deltaX`/`deltaY`、`SceneMouseButton`（LEFT/RIGHT/MIDDLE/
  BUTTON_4/BUTTON_5/NONE）、`ScenePointerAction.CANCEL`（窗口失焦）。
- 生产链路：`PlatformInputSource.drainFrame()` → `SceneInputFrame` → `SceneRuntime.route`。
- 注入入口 `SceneInteractionHarness` 已覆盖 `click` / `press` / `release` / `pressReleaseAcrossFrames` /
  `moveTo` / `moveAt` / `scroll` / `pressKey` / `typeText` / `clickAt`；**消费者规模 261 处 / 65 个测试类**。
  **缺口**：按钮硬编码 `LEFT`、无修饰键、无 `KEY_RELEASED`/`REPEAT`、无右键/中键、无拖拽、无双击/三击、
  无横向滚轮、无 `CANCEL`（失焦）、无 IME/外部文本模式切换、时间戳恒 `1000L`（做不了长按与双击时间窗）。

### F4 测试 JVM 的 GL 现状（实测）

- 测试运行时 classpath **有** `com.github.GTNewHorizons:lwjgl3ify:3.0.31`（jar 名 `lwjgl3ify-3.0.31-dev.jar`），提供
  `org.lwjgl.opengl.Display|GLContext|PixelFormat|ContextCapabilities`、`org.lwjglx.*`、
  `org.lwjgl.input.Keyboard|Mouse`。
- **没有 `org.lwjgl.opengl.GL11`**（LWJGL2 主 API 不在 test classpath），也没有 LWJGL3 实体
  （`org.lwjgl.glfw.GLFW`、`org.lwjgl.opengl.GL` 均 `ClassNotFoundException`）
  → **生产渲染代码在测试 JVM 里链接不上**。
- lwjgl3ify 的 `org.lwjgl.* → org.lwjglx.*` redirect 由 RFB transformer + FML coremod 在启动期织入，
  **不是可以直接丢进 test classpath 的替代品**；3.x 的 `Display` 已改 SDL3 后端。
- LWJGL2 制品已在本地 Gradle 缓存（项目经 MC 依赖已拉取，可离线使用）：
  `org.lwjgl.lwjgl:lwjgl:2.9.4-nightly-20150209`、`lwjgl_util:2.9.4-nightly-20150209`、
  `lwjgl-platform:2.9.4-nightly-20150209:natives-{windows,linux,osx}`。
- 测试 JVM 为 JDK 17（RFG + Jabel 编 Java 8 字节码），`maxHeapSize = 1024m`。

### F5 CI 环境（一手核实 GTNH 共享工作流 pin `8d2e9d2`）

- 跑测试的步骤是
  `xvfb-run --server-args="-screen 0 1366x768x24" ./gradlew --build-cache --info --stacktrace build`，
  预装 `mesa-utils xvfb x11-xserver-utils`。
- 即 **CI 的 `test` 天生有 DISPLAY + Mesa(llvmpipe)**；但 **Xvfb 屏幕固定 1366×768×24**，低于 2K 目标。
- 硬约束：**分辨率覆盖必须走 FBO 离屏**（`GL_MAX_TEXTURE_SIZE` 量级足够），**不得依赖窗口或默认 framebuffer 尺寸**。
- 注意：CI 方案在本轮重定位后**降级为可选**（P3 顺带用途），不再是立项主驱动。

### F6 现有软光栅的真实代价与缺口（实测 + 历史）

- **规模实测**：`build/reports/` 现存 **191 张 PNG / 50.1 MPx**；单张最大 `2536×2733 = 6.93 MPx`（`@4x` 放大图）。
- 即 CPU 软光栅**已经在跑比单个 2K 帧（3.69 MPx）更大的画布** → **性能不是本次升级的主因**。
- 主因是**保真度面**：软光栅只覆盖字形 quad（`FontSoftwareRasterizer` 类头自述「多抽头 AA 与 smoothstep
  属真机 shader 的抗锯齿近似，软件侧默认不做」）；scene 侧的圆角 band、裁剪/stencil、group opacity、
  backdrop 玻璃快照链**一条都不覆盖**；顶点层变换在 mock 后端不可观测。
- 历史实证：`ERROR-20260905-software-rasterizer-half-quad-rotated-sampling`（半 quad 180° 旋转采样致全部历史
  出图不可读）；`ERROR-20260818-overlay-toast-full-width-and-top-align`（headless 全绿而真机两处显示缺陷）；
  `docs/反馈层/踩坑记录.md`「测试盲区：为什么 headless 全绿却真机翻车」。

### F7 M0 技术闸门已本地实测通过（2026-09-17）

在**真实 gradle test JVM**（JDK 17.0.19，与 CI 同版本）里，用 init script 临时注入 LWJGL2 依赖与 natives
（**不改仓库构建文件**；探针测试类用完即删），测得：

| 观测项 | 结果 |
|---|---|
| `Display.create(PixelFormat stencil=8)` | OK（约 0.3~0.4 s） |
| `GL_VERSION` / `GL_RENDERER` | `4.6.0 NVIDIA` / `RTX 5070 Ti Laptop GPU` |
| `GL_STENCIL_BITS` | 8 |
| `1920×1080` / `2560×1440` FBO | `GL_FRAMEBUFFER_COMPLETE`（36053） |
| **`UiRenderContext` 构造** | **OK**（无需 MC 运行环境） |
| **生产 `UiRenderContext.fillRect` 画到自建 FBO** | **OK**；读回中心像素 `33,66,cc,ff` = 传入的 `0xFF3366CC` |
| 端到端（建上下文 + 2K FBO + 绘制 + `glReadPixels` + PNG 编码） | **534 ms**（读回 4 ms、PNG 编码 97 ms） |

结论：**「不再纯软光栅、改用 OpenGL」技术可行，且生产渲染后端零改动即可复用**。

### F8 Linux / CI 侧的 GL 边界（调研核实，2026-09-17）

- LWJGL2 的 Linux native（`liblwjgl64.so`）动态依赖 `libX11.so.6` / `libGL.so.1` / `libXrandr` /
  `libXcursor` / `libXxf86vm`，并引用 `XOpenDisplay` + `glXGetProcAddress`：**只有 GLX 一条通路**，
  无 EGL/OSMesa 后端 → **Linux（含 CI）必须有 X server**；既有 CI 已用 `xvfb-run`（F5）。
- LWJGL2 的三种「无窗口」手段都不解决 Linux：`Display` 无隐藏窗口开关；`Display.setParent(Canvas)` 仍要真实
  X 窗口；`Pbuffer` 是已弃用的 GLX pbuffer，同样要 X。
- **线程约束**：GL 调用必须固定在创建上下文的那个线程上（JUnit 测试线程即主线程，天然满足）。
- **反证澄清**：把 `lwjgl3ify` 当普通依赖丢进 test classpath 不可行（redirect 需 RFB system classloader +
  UniMixins 全套 JVM 参数，dev jar 内含 446 个 `org.lwjglx` 类）。真无 X 的兜底只有 LWJGL3 null 平台 + OSMesa
  （需 `libosmesa6`，与生产 LWJGL2 API 面不兼容），**不作主路径**。
- **生态先例**：`headlesshq/mc-runtime-test`（HeadlessMC + Xvfb 跑 1.7.10 客户端）——「Xvfb + Mesa 跑 1.7.10 渲染」
  是通行做法。
- **保真上限**：软件光栅器（llvmpipe / OSMesa / SwiftShader）本身也是 native 库，与真 GPU 驱动在纹理过滤、
  混合精度、多重采样上存在实现差异；**硬件级保真只有真 GPU**——这是 §五 分层的依据。

### F9 不开游戏能渲染到什么程度（实测：MC 依赖分布）

| 域 | 类数 | 含 `net.minecraft` |
|---|---|---|
| `ui/scene/*`（node/layout/paint/text/theme/form/overlay/image/control） | 137 | **0** |
| `ui/render`（圆角 / clip / backdrop / 快照 / 渲染上下文） | 40 | **0** |
| `ui/hud` | 24 | **0** |
| `ui/text` + `ui/markdown` + `ui/base` | 20 | **0** |
| `font` | 126 | 3（`FontRegistry` / `FontGenerationBuildRequest` / `MarkdownBlockParser`） |
| `internal/devtools/playground` | 16 | 2（仅 `TestPlaygroundEntry` / `TestPlaygroundScreen` 两个宿主入口） |
| `internal/chat3` | 47 | 14 |
| `client` | 14 | 4（HUD 监听与环境） |

- **整个 scene 栈 + 渲染栈 + HUD + 文本栈零 MC 依赖** → 可 headless 出图的面远大于「只渲染 playground 页」。
- `TestPlaygroundHost` 已在 **main 域**（`extends AbstractSceneHostWidget`，构造只吃 `PlatformInputSource`），
  可直接作为 headless 宿主骨架。

### F10 「快」的真正瓶颈不是 GL（实测）

- GL 侧（F7）：上下文 + 2K FBO + 绘制 + 读回 + PNG = **534 ms**（读回 4 ms、PNG 97 ms）。
- 而走 `gradlew test --tests ...` 的实测墙钟为 **12~22 s**（GL 探针 12 s、`MarkdownSoftwareRenderTest` 22.2 s），
  **Gradle 配置与编译是大头，GL 占比不到 5%**。
- → 「快速出图」的关键是**绕开每次启动的 Gradle 开销**（一次性导出 classpath 后直启 JVM，或常驻进程），
  而不是优化渲染本身。
- 启动侧另有两笔**一手核实**的固定成本（`ERROR-20260904-dedicated-server-font-bootstrap-crash`）：
  - `FontService.getInstance()` 一次调用即常驻 **150.12 MiB**（字形表 123.25 MiB + worker 侧 `long[]` 等）；
  - AWT 字体子系统**全有或全无**：零 fontconfig 环境（Alpine / 精简容器）在 `FontManagerFactory` 初始化即抛
    `Fontconfig head is null`，`getAllFonts` / `createFont` / 逻辑字体一起失效，**没有绕过路径**。
- 这两笔成本**只付一次**的性质，支持「常驻进程 + 请求出图」作为 agent 工作流的目标形态（§六-3）。

### F11 运行期 classpath 里没有 LWJGL2（实测，2026-09-17）

用 init script 探针（`--no-configuration-cache`，不改仓库）打印四个 configuration 的文件名：

| configuration | 文件数 | 含 lwjgl 的文件 |
|---|---|---|
| `compileClasspath` | 115 | `lwjgl-2.9.4-nightly-20150209.jar`、`lwjgl_util-2.9.4-nightly-20150209.jar`、`lwjgl-platform-…-natives-{linux,osx,windows}.jar`、`librarylwjglopenal-20100824.jar`、`lwjgl3ify-3.0.31-{api,dev}.jar` |
| `testCompileClasspath` | 63 | 同上（除 `-api`） |
| `runtimeClasspath` | 100 | 仅 `librarylwjglopenal-20100824.jar`、`lwjgl3ify-3.0.31-dev.jar` |
| `testRuntimeClasspath` | 105 | 仅 `librarylwjglopenal-20100824.jar`、`lwjgl3ify-3.0.31-dev.jar` |

两条结论：

1. **编译期零改动成立**：生产渲染用的 `org.lwjgl.opengl.GL11` 在 `compileClasspath` 与 `testCompileClasspath` **都已在**，
   落 main 域或 test 域都**不需要新增编译依赖**；
2. **运行期不成立**：LWJGL2 主 jar 与 natives **不在任何 runtime classpath 上**（真机由 MC 客户端 / lwjgl3ify 供给）——
   headless 直启必须自带补充 classpath（LWJGL2 + natives）。这正是 M0 探针当初必须用 init script 注入的原因，
   也是 §六-2 与 M1 的必做项，而不是可选项。

### F12 打包链形状（实测，2026-09-17）

`tasks.withType(AbstractArchiveTask)` 实测出 7 个产物任务，主线是：

```
jar (classifier dev-preshadow, src/main/java)
  → shadowJar (classifier dev, ShadowJar ← Jar；输入含 main / test / mcLauncher / patchedMc / injectedTags)
      → reobfJar (发布 jar；输入 = 上面的 dev jar)
```

| 任务 | 类型 | 产物 |
|---|---|---|
| `jar` | `org.gradle.api.tasks.bundling.Jar` | `…-dev-preshadow.jar` |
| `shadowJar` | `com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar`（继承 `Jar`） | `…-dev.jar` |
| `sourcesJar` / `apiJar` | `Jar` | `…-sources.jar` / `…-api.jar` |
| `reobfJar` | `com.gtnewhorizons.retrofuturagradle.mcp.ReobfuscatedJar`（**不是 `Jar` 子类**） | `qz_uilib-….jar`（发布物） |

两点施工含义：

1. **排除写在 `tasks.withType<Jar>` 上即可覆盖 `reobfJar`**：`reobfJar` 自身没有 CopySpec / `exclude`，但它吃的是
   `shadowJar` 的产物，排除随输入传播——M1 必须用 `jar tf` 逐产物实测确认，不靠推断；
2. **test 域不是「物理进不了包」**：`shadowJar` 的输入里含 `test` 源集输出——所以隔离一律靠显式排除 + 门禁，
   不能靠目录位置（这推翻了「落 test 域天然隔离」的先前假设）。

### F13 M1 落地实测：两处「宿主语义」缺口（2026-09-17）

M1 已落地（main 域 `internal.devtools.headless`，提交 `c7264618`）。第一次跑通时出现「GL 正常、字体正常、却整帧全透明」，
排查出的两处都不是 headless 独有缺陷，而是**照抄了渲染调用、漏抄了宿主语义**：

| 缺口 | 现象 | 根因 | 修法 |
|---|---|---|---|
| 帧前置语义 | 绘制无像素（自检报整帧全透明，`glError=0`） | 生产宿主在 `surface.render` 前设正交投影与 viewport；headless 自建这一段时顶点落在单位矩阵下被整体裁掉 | 帧前置上提 `UiHostRenderSupport.beginMainUiFrame`，MC 宿主与 headless 共用同一入口（`McScreenBridge` 改用之） |
| 宿主背景语义 | 有像素但 alpha≈18/255，导出后「白底淡字」 | UI 面板是半透明玻璃配方，真机叠在游戏世界之上；headless 从全透明开始时面板 alpha 停在极低值 | `HeadlessRequest.background`（默认不透明中性深色，`--bg=transparent` 可选）；自检增加 alpha 统计并对半透明底给出提示 |

附带修一处无 FML 宿主缺陷：`LaunchSide.isDedicatedServer()` 原先读 `Side.SERVER` 常量，非 FML classpath 上没有该类，
字体渲染 bootstrap 判定直接 `NoClassDefFoundError`；改为 `"SERVER".equals(side.name())`（按名字比较，行为等价）。

实测（本机 RTX 5070 Ti / GL 4.6.0）：1280×720 playground 首页、2 帧，**459 ms**（含字体初始化）；
像素自检 `ink=100% opaquePx=921600 meanAlpha=255 colors=1290 glError=0`；完整 `build` 通过
（5748 tests；`verifyHeadlessNotPackaged` 逐个校验 7 个产物均不含该包——门禁在首次运行时就抓出并修掉了 `apiJar` 漏排）。

### F14 M2 命令面判据与三档实测（2026-09-17）

出图完整性升级为**两条独立证据的交叉判据**：命令面（`HeadlessDrawSummary`：本帧下发了哪些绘制命令、
覆盖什么范围）+ 像素面（自检：有多少墨迹）。记录用 `RecordingUiRenderContext extends UiRenderContext`——
**必须是子类而非装饰器**：仓内多处按 `instanceof UiRenderContext` 解析像素上下文，装饰器会让玻璃/圆角路径静默降级。

判据（`HeadlessSelfCheck` 交叉段）：

- 命令数 > 0 而墨迹为 0 → **失败**（帧前置 / 帧缓冲绑定 / 裁剪栈问题，而非「UI 没画」）；
- 矩形命令全部落在视口外 → **失败**；部分越界 → 提示（不判失败）；
- 像素有内容而命令面为空 → 提示（记录器未挂上）。

三档实测（同一 playground 首页、2 帧、宿主背景 FF0E1014）：

| 尺寸 | 命令数 | 文本 | 几何 bounds | 视口外矩形 | 耗时 | 自检 |
|---|---|---|---|---|---|---|
| 640×360 | 82 | 48 条 / 924 字符 | 1,1..**832,451** | **2** | 401 ms | ok（ink=100%） |
| 1280×720 | 124 | 92 条 / 2100 字符 | 1,1..1279,717 | 0 | 615 ms | ok（ink=100%） |
| 2560×1440 | 124 | 92 条 / 2100 字符 | 1,1..2559,1437 | 0 | 694 ms | ok（ink=100%） |

两条结论：

1. **字体 GL 尾端确已上屏**：92 条文本命令 / 2100 字符对应像素 100% 覆盖、1290 种颜色（含抗锯齿灰阶），
   不再是「字体初始化成功但画不出来」；
2. **360P 暴露首个真实问题**：几何 bounds 832×451 超出 640×360、2 条矩形完全在视口外——小视口下 playground
   外壳（分段导航 9 项标签等）存在溢出。这不是 headless 设施缺陷，而是分辨率矩阵本该发现的东西，转入 M4 处理。

### F15 帧稳定语义与文本探针（2026-09-17）

M2 前半段暴露的隐患：**固定帧数出图会静默产出残缺内容**。同一次文本探针（同文本、同字号）：

| 帧数 | 墨迹颜色数 | PNG | 结果 |
|---|---|---|---|
| 1 | 1 | 1139 B | 整帧只有背景（文本完全没画） |
| 2 | 30 | 2309 B | 只有首个字形「Q」 |
| 10 / 40 | 180 | 8623 B | 收敛（完整文本） |

根因：字形由字体 worker 异步生成，固定帧数只保证「推进了几帧」，不保证「内容已就绪」；
而像素自检此前只看「有没有墨迹」，残缺文本照样判 ok——**这是比全透明更隐蔽的失败模式**。

修法（作为请求语义，不是补丁）：

- `HeadlessRequest` 增 `settleFrames`（默认 2）与 `maxFrames`（默认 60）：`frames` 降级为「最少帧数」，
  出图条件是**连续 settleFrames 帧像素指纹一致**（FNV 采样哈希），上限兜底；
- 命令面摘要改为「最后一帧」语义（`reset()`），与像素说的是同一帧；
- artifact 输出 `frames: 实际/上限`，达到上限即标注「未收敛」；
- 新增 `text-probe` 页面（`--page=text-probe --text=…`）：一行固定字号文本，给字体路径一个可归因的对照物。

实测（800×200，文本 `Qz UILib 对拍样本 Ag123`，字号 32）：默认 settle 自动推进 **6 帧**收敛（577 ms），
`--settle=1` 4 帧（525 ms），`--frames=20 --settle=3` 23 帧（583 ms）——三者输出**逐字节一致**（8623 B / 180 色）。

顺带修探针自身缺陷：`SceneLabel` 在零宽约束下会把文本裁到只剩首字符，探针必须显式给可用宽度。

### F16 与软光栅出图对拍（2026-09-17，M2 收口）

新增 `HeadlessTextParityTest`（test 域 `font/render/software`，与软光栅套件同包）：

- GL 侧走**进程外直启** `HeadlessShotMain --page=text-probe`（与 agent 真实路径一致），直启所需 classpath 文件与
  natives 目录由 Gradle 经 system property 注入 test JVM（`tasks.withType<Test>` + `dependsOn exportHeadlessClasspath`）——
  **test JVM 自身不需要 LWJGL2**，也避开 lwjgl3ify shim 与真 LWJGL2 在同一条 classpath 上的先后之争；
- 软光栅侧走既有验收通道 `LatexSoftwareRenderKit.render(text, 32, true)`；
- 判据是**几何**而非逐像素（两者 AA 实现不同）：墨迹宽度比 ∈ [0.75, 1.25]、高度比 ∈ [0.6, 1.4]，实测值打进测试输出。

实测（文本 `Qz UILib 对拍样本 Ag123`，字号 32，背景 `0xFF202020`）：

| 通道 | 墨迹 | 说明 |
|---|---|---|
| GL（headless 直启） | **393 × 36** | 真机路径：FBO + 生产 `UiRenderContext` |
| 软光栅（既有验收） | **382 × 34** | `LatexSoftwareRenderKit`，advance=389 |
| 比值 | 宽 **1.029** / 高 **1.059** | 差异 3~6%，属 AA 实现差异，度量链路一致 |

**M2 至此收口**：字体 GL 尾端上屏有据（F14）、出图完整性判据落地（F14）、帧稳定语义堵住残缺出图（F15）、
跨通道几何对拍通过（本则）。下一步 M3：纯代码输入设备模型与脚本化输入。

### F17 M3 纯代码输入设备模型与输入脚本（2026-09-17）

设备模型表达**用户动作**，而不是平台事件：

- `HeadlessInputDevice`：`moveTo / moveBy / press / release / click / doubleClick / scroll / keyDown / keyUp /
  pressKey / type / compose / cancelPointer / frame / wait`；时间轴按 16.67ms 单调推进；
  修饰键由「当前按住的键集合」推导；`click` / `pressKey` 自动插入帧边界（跨帧）。
- `HeadlessInputScript`：可读脚本，每条语句换行或 `;` 分隔、`#` 注释；语法错误显式失败（不静默跳过）。
  关键字：`move / moveby / down / up / click / dblclick / scroll / keydown / keyup / key / type / compose / cancel / frame / wait`。
- `HeadlessInputSource`：实现 `PlatformInputSource` + `KeyboardTextInputSource`，事件经**生产 `InputFrameBuilder`**
  封板（不另造帧构造）；`drainFrame()` 内部先推进设备再封板，因此**帧划分与生产帧管线天然对齐**，
  调用方不需要手工对齐帧号。整串文本（`compose`）与逐字符（`type`）两条文本路径都在。

端到端实测（一条命令）：`--actions="move 315 88; frame; click; wait 4"` 完成「移到导航 → 点击 → 切页 → 出图」：

| 指标 | 基线（无脚本） | 点击导航第 2 项 |
|---|---|---|
| input | dispatched=0 | **dispatched=3**，pointer=315,88 |
| 命令/文本 | 62 / 46 条 / 1050 字符 | 54 / 30 条 / **413 字符** |
| PNG | 200 031 B | 108 587 B |
| 差分 | — | **170 495 像素（18.5%）**，页面切到「单行文本」 |

契约测试 `HeadlessInputDeviceTest`（5 项）：跨帧点击、修饰键跟随按住键、`wait` 语义、整串文本单帧交付、
非法脚本显式失败。

踩坑记录：`wait` 最初实现在**动作队列之前**生效，导致「队列里还有动作时先空转」——实测 `dispatched=0`、
脚本整段静默不执行。已改为「队列耗尽后才空转」，并由 `waitRunsAfterQueuedActions` 钉住。

### F18 启动器、分辨率矩阵与「上下文是进程级资源」（2026-09-17）

**启动器**：`exportHeadlessClasspath` 现在同时生成 `build/headless/{classpath.txt, shot-args.txt, qz-shot.bat, qz-shot.sh}`
——用 Java `@argfile` 承载超长 classpath，agent 直接 `qz-shot.bat --page=… --size=… --out=…`。
实测冷启动 **1.62 s**（含 JVM 启动 + 8 帧渲染 + PNG），达成 P1「≤ 3 s」目标（Gradle 路径为 12~22 s）。

**分辨率矩阵**：CLI 支持 `--sizes=WxH,WxH,…`，一次进程内跑多档；多档时 `--out` 自动加尺寸后缀，末尾给矩阵汇总行。

| 尺寸 | 命令 / 文本 | 几何 bounds | 越界 | 颜色数 | 自检 |
|---|---|---|---|---|---|
| 640×360 | 41 / 24 条 / 462 字符 | 1,1..**832,451** | **1** | 1232 | ok |
| 1280×720 | 62 / 46 条 / 1050 字符 | 1,1..1279,717 | 0 | 1290 | ok |
| 1920×1080 | 62 / 46 条 / 1050 字符 | 1,1..1919,1077 | 0 | 1276 | ok |
| 2560×1440 | 62 / 46 条 / 1050 字符 | 1,1..2559,1437 | 0 | 1289 | ok |

整批（四档）墙钟 **2.42 s**；矩阵汇总行：`matrix: 4/4 ok`。

**修复：GL 上下文必须是进程级资源**。矩阵首跑时第 2 档起全部 `glError=1281`（GL_INVALID_VALUE）、颜色数从 1290
掉到 217（文字大面积丢失）——根因是会话 `close()` 里调了 `Display.destroy()`：字体 atlas 等全局 GL 对象挂在上下文上，
下一个会话拿到的是失效纹理。改为 `ensureContext()`（进程级复用）+ `close()` 只释放 FBO/纹理/renderbuffer，
`shutdownContext()` 留给显式收尾。修后 4/4 ok、各档颜色数恢复一致。

**360P 溢出根因（F14 的发现至此收口为业务待办）**：`PlaygroundKit.MAX_CONTENT_WIDTH = 860`，而 640×360 下可用内容宽
只有「视口宽 − 2×padding = 608」；分段导航 9 项标签各按「文本宽 + 2×PAD_LG」取 preferredWidth 且无法收缩 → 横向溢出
（几何 bounds 832 > 640）。这是**业务外壳的小视口适配问题**，不是设施缺陷——headless 的职责是把它暴露出来
（`outsideViewport=1` + 提示）。修法需业务侧决策（内容最大宽与父约束取 min，或分段导航加滚动/换行），设施不擅自改 UI。

### F19 消费者域扩展：9 个 playground 页面批量出图（2026-09-17）

页面维度进入请求语义：`HeadlessRequest.pageIndex`（-1 = 由页面自身决定），CLI `--page-index=N` / `--page-indexes=0,1,…`，
与 `--sizes` 组成「页面 × 尺寸」笛卡尔积。

切页走宿主**公开入口** `TestPlaygroundHost.showPage(index)`——signal-first，与用户点击导航**同一条通道**，
不依赖命中坐标（窄画布下点击会静默 miss），也不新增第二条切页路径。

实测（9 个 playground 页面 × 1280×720，一次进程）：

| 页 | 0 总览 | 1 单行文本 | 2 多行文本 | 3 浮层 | 4 响应式 | 5 富文本 | 6 控制字符 | 7 LaTeX | 8 Markdown |
|---|---|---|---|---|---|---|---|---|---|
| 颜色数 | 1290 | 1277 | 1261 | 1430 | 1298 | 1765 | 1172 | 1154 | 2098 |
| 收敛帧 | 4 | 11 | 12 | 12 | 13 | 12 | 11 | 13 | 8 |

`batch: 9/9 ok`，整批墙钟 **4.56 s**（含 JVM 启动）。

两个副产物：

1. **settle 在页面维度同样必要**：切页后各页收敛帧从 4（首页）升到 8~13——固定帧数出图会截到半成品；
2. 复杂页（Markdown 渲染）在 headless 下完整可读：标题层级、围栏代码块、嵌套引用、滚动条均在位。

### F20 M5 收口：失败语义、退出码与成本基线（2026-09-17）

**失败语义实测（三连测）**：

| 场景 | 期望 | 实测 |
|---|---|---|
| 无 natives（等价无 GL） | 显式失败 + 可操作指引，不产出图 | exit **3**；`[GL 上下文] 加载 LWJGL2 natives 失败：请确认 natives 已解压且 -Djava.library.path 指向该目录（qz-shot.bat 已自带）` |
| 未知页面 | 显式失败 | exit **3**；`[能力探测] 未知页面：no-such-page` |
| 透明背景 | 成功 + 提示 | exit **0**；自检 `meanAlpha=4.8`、不透明占比 1.15% + 提示「若期望不透明判读，请把宿主背景设为不透明（--bg=RRGGBB）」 |

顺带修掉两处语义缺陷：

1. **退出码分流**：原先所有失败都返回 4（自检失败），环境/上下文失败也是 4，与接口契约（3 = 能力/上下文/渲染失败）
   不符——agent 无法区分「环境没准备好」与「UI 有问题」。现按失败类型分流（3 与 4 分开）；
2. **诊断可读性**：`UnsatisfiedLinkError` 会把整条 `java.library.path`（数千字符）拼进消息。现由 `HeadlessFailure.brief`
   统一截断到 200 字符（唯一实现，诊断输出与上下文创建共用）。

**软光栅回退语义修正（原 A2）**：软光栅只覆盖字形通道（`FontSoftwareRasterizer` 消费 `SoftwareRenderFrame`），
**不渲染 scene 命令**（面板 / 圆角 / 裁剪 / 组不透明 / 合成）→ **像素级回退在技术上不成立**。
无 GL 时的正确行为是「显式失败 + 可操作指引」，而不是静默降级成半张图。
A2 与 §六-6 据此修正为：保留软光栅作为**字体侧既有验收通道**，不作为 headless 出图的回退路径。

**成本基线**（统一口径：启动器直启，含 JVM 启动）：

| 场景 | 墙钟 |
|---|---|
| 单张 1280×720 | **1.96 s** |
| 分辨率矩阵四档（360P/720P/1080P/2K） | **2.31 s** |
| 九个 playground 页面 | **4.58 s** |
| 单帧 GL 侧（M0 实测，2K） | 534 ms（读回 4 ms、PNG 编码 97 ms） |

内存口径（RGBA 单帧）：360P 0.88 MiB → 2K 14.06 MiB；字体系统首次初始化常驻约 150 MiB（`FontService`，进程内只付一次）。

### F21 完整开发类路径供给与 chat3/HUD 边界结论（2026-09-17）

**新增完整开发类路径供给**：`exportHeadlessClasspath` 现在额外产出 `classpath-full.txt` + `qz-shot-full.bat`
（137 项 = 最小集 107 项 + `compileClasspath` + `patchedMc` / `mcLauncher` 源集输出，即重编译后的 Minecraft 类）。

- 起因（实测）：渲染触及 MC 类型的页面时链接失败——`NoClassDefFoundError: net/minecraft/util/IChatComponent`；
  根因是 **MC 类不在 `compileClasspath` 上**（RFG 把重编译产物放在 `patchedMc` 源集输出）；
- 补上 `patchedMc` + `mcLauncher` 后链接成功（同一命令从 exit 1 变为正常启动）；
- 定位：**最小集仍是 agent 默认**（快、无 MC 静态初始化风险），完整集给「页面本来就要 MC 类型」的场景。

**chat3 / HUD 边界结论（2026-09-17 初判 → 2026-09-18 修正）**：

| 消费者 | 可 headless 性 | 证据 |
|---|---|---|
| chat3 视图 | **已纳入**（`--page=chat`） | 走生产同一入口 `ChatSceneController.buildContent(SceneRuntime)`。2026-09-17 判为「空画面（`commands=0`/`colors=1`）说明内容挂在宿主装配链上」**结论有误**：真实成因是两处接线——① 漏写 `setHostViewport` → `chatWidthFor(0)` 收敛到 1px；② `--frames` 默认 2 帧时消息组 180 ms 入场动画期间整树 `opacity=0` 且像素逐帧不变，被「连续 N 帧像素一致」的稳定判据误判为已收敛。补齐后 1280×720 实测 `commands=23 [surface=5 segments=18]`、`colors=1318`，出图含气泡 / 组头 / markdown / 公式 / 链接 / 折行 |
| HUD | **否（宿主在 MC 域）** | `ui/hud` API 层零 MC 依赖，但宿主装配 `SceneHudHost` / `HudRegistry` / `ClientHudServiceImpl` 全在 `client/` 包；在 headless 复刻等于新增一套 HUD 虚拟窗口装配，违背「复用生产链路」原则 |

结论修正：**chat3 不需要新抽象**——它的内容构建入口（`buildContent(SceneRuntime)`）本来就是宿主无关的，
缺的只是接线与时间语义（视口先写入、帧数覆盖入场动画）。
**HUD 需要一处搬迁 + 依赖倒置**：把 `client.hud.SceneHudHost.RetainedWindow`（外壳 + 五件套 + 空内容语义）
上提为 `ui.scene.host` 里宿主无关的单窗口宿主，由 client 与 headless 共用；四角锚定数学已在宿主无关的
`SceneAnchorResolver`。在 headless 复刻第二套 HUD 装配仍是禁止项。
「有命令无像素 / 有像素无命令」两个自检提示保留，本次空画面正是被它们点出来的。

### F22 环境面落地：宿主环境端口（2026-09-18）

**裁定**：环境事实走**注入端口**（构造依赖），不做单例直读、不做服务定位器。判据是
「静态量必须有失效通道」——框架今日获取环境事实的两条路各有缺口：进程级静态/单例直读
（`Config.useDebug` 被 23 处每帧读、`LanguageEpochService` 自述「不是 signal 通道」）
在 headless 出图与测试里**无法替换**、在运行期**无法通知**消费者；构造期注入
（`HudScaleSetting`、`SceneThemes.install(runtime, signal)`）已被证明可用，却各自为政、每加一个
环境量就要多改一次宿主构造签名。环境面把后者上升为统一语义，给前者一个明确归属。

**落地**（`club.heiqi.uilib.ui.env`）：`UiEnvironment` 端口 + 三域
`DiagnosticsEnvironment` / `LocaleEnvironment` / `ResourceEnvironment`（含各自缺席实现）
+ 缺席态 `UiEnvironment.empty()` + 生产适配器 `ProcessUiEnvironment`（**无状态**转发：
`Config.useDebug` / 语言代际 / 资源代际，因此多实例语义等价，无需单点装配）。

注入点是**构造依赖**：`SceneRuntime(SceneTextMeasurer, UiEnvironment)` 成为唯一公开构造
（传 null 快速失败，不给「缺省即静默缺席」的路径），`SceneHostAssembly.assemble` 加第三参，
`SceneHostAssembly.defaultEnvironment()` 与既有的 `defaultMeasurer()` 对称、是全仓唯一生产环境装配点。

三条不变量（写在 `UiEnvironment` javadoc）：① 只读且方向**自外向内**（宿主 → runtime；
与 `SceneFontEnvironment` 的 runtime → 节点下行暴露方向相反，不得互相顶替）；② 缺席态与
「未安装态」**逐位等价**（各域 javadoc 写明具体缺席值）；③ 环境值是 O(1) 帧内直读，
**禁止**缓存进构造期字段或 `Computed` 快照（后者即仓库内已实测的静默失效事故同型）。

**接线范围（本版）**：帧管线 `phaseReplay` 的采样开关由 `Config.useDebug` 静态直读改为
`runtime.environment().diagnostics().debugEnabled()`（有 runtime 引用可直达，行为等价，
且 headless / 测试自此可注入自己的诊断实现）。其余读取点的**接线进度与前提**写在
`ProcessUiEnvironment` javadoc，按可达性分两类，下次接手不必重新盘点：
有 runtime 引用但改端口读解决不了的（`registerDebugHud` 的 Computed 快照，需先给诊断域补
可订阅通道）；需先补「节点 → 环境」通道的（控件内 `Config.useDebug` 采样点、
`UiPerformanceMonitor`、`HostImageSource`、`PickerIconResolver` / `PickerRevisionBridge` 的代际比对）。

**测试侧**：`SceneRuntime` 旧的无参 / 单参构造删除，**283 处**测试构造点机械迁移到 testkit 收口工厂
`SceneTestEnvironments.runtime(...)`（103 个文件，纯机械替换 + import 补插，断言零改动；
`SceneInteractionHarness` 同包免 import）。`UiSamplingRenderSemanticsTest` 是唯一需要语义改动的：
它原本靠改 `Config.useDebug` 静态字段驱动帧管线，现改为**注入诊断环境**（不再污染进程静态态）；
`UiPerformanceMonitor` 侧仍是静态直读（未接线），故该测试两侧同时打开以构成完整「采样开启」语义。

**本版边界**：不提供环境量的**订阅（signal）**通道，只提供值读与代际；需要响应式派生的域接入时
再补（届时 `UiEnvironment` 的 default 域访问器保证新增域不破坏既有实现）。

### F23 chat 页出图空画面的根因：虚拟时钟与消息出生时刻的时序竞态（2026-09-18）

**现象**：`--page=chat` 出图纯色（`commands=0`、`colors=1`、PNG 5308 bytes），与 F21 记录的
`commands=23 / colors=1318` 不符；同机 `--page=playground` 正常（`commands=62`、`colors=1290`）。

**排查链**（每步都是一手实测）：

1. **与工作区改动无关**：在 F21 当时的提交 `a6396aff` 上另建 worktree 复跑，输出 PNG 与当前
   **逐位同字节数**（5308）；
2. **树不空**：临时探针显示内容根布局盒 320×322、节点 41 个、消息 6 条（默认消息集），全部有尺寸；
3. **组 opacity 恒 0**：6 个消息组节点 `opacity=0.0`（精确 0，不是极小值），22 帧不变；
4. **整树被跳过**：paint 对「零透明子树」有跳过优化，全部组为零 ⇒ `commands=0`、`bounds=(empty)`；
5. **直接量出生时刻与帧时钟**：`born=…970385 now=…970032 delta=-353` —— 出生时刻比帧时钟起点**晚 353ms**。

**根因**：`ChatSceneProbeHost` 的虚拟时钟 `clockMillis` 用**字段初始化器**取值，它执行于
`super(...)` 之后、`ChatSceneController` 创建与消息 append **之前**；而控制器初始化（度量 / 段解析装配）
实测可耗数百毫秒。入场动画进度 = `(帧时钟 − 组出生时刻) / 180ms`，出生时刻落在起点之后 ⇒ 进度恒为负
⇒ 每组 opacity 恒 0。默认 `frames=20`（≈320ms）追不回 353ms 的偏差，于是稳定出空图。

**为什么 F21 当时能出图**：这是**时序竞态**而非恒坏——控制器初始化耗时随字体 / 类加载预热而变，
预热后偏差趋近 0，入场动画照常播完。同一个二进制在不同时刻可以得到两种结果，
F21 的 `commands=23` 与本次的 `commands=0` **都是真实测量**。

**修复**：把 `clockMillis` 的取值挪到内容构建完成之后（构造体末尾），并写明「初值不能放字段初始化器」
的理由。修复后同一命令实测 `commands=23 [surface=5 segments=18]`、`colors=1286`、
PNG 49906 bytes，与 F21 记录量级一致。

**可复用教训**：凡「虚拟时钟 + wall-clock 出生时间戳」混用处，时钟起点必须晚于所有出生时间戳的
产生点；字段初始化器是最容易踩的位置（它在 `super()` 之后、构造体之前）。

### F24 出图取证的第二个产出：气泡内文本换行宽与气泡内宽不同源（2026-09-18）

**现象**：F23 修复后出图已正常，但人眼在图上发现「最后一条消息的第二行文字伸出了气泡右缘」。

**取证**（像素级，不靠肉眼）：逐行分离「气泡底色像素」与「文字亮像素」求各自右边界。
修正前视口 1280：链接消息气泡止于 x=253、文字画到 266（溢出 13px）；长文本消息气泡止于
x=254、文字画到 298（溢出 44px）；两条短消息内边距恒为 10~11px 正常。

**根因**：气泡**换行宽**取自 composer 的 `maxLine = chatWidthFor(v) − 2×bubblePaddingX`，
那是「气泡外宽上限」的**父口径**（未乘 `bubbleMaxWidthRatio`）；而气泡节点与行节点实际被钳到
`maxBubbleWidthPx = round(父口径 × 0.85)`。两者相差 0.85 倍 ⇒ 文本按 300 换行、按 255 显示。
**只有内容宽超过气泡上限的消息触发**，短消息一切正常，故长期未暴露；生产与 headless 同路
（`ChatHudWindow` 无参构造 ⇒ `segmentFlowWrapper = null`，与探针宿主一致），**不是 headless 特有**。

**修复**：`ChatMessageList.buildGroupNode` 改为由 `maxBubbleWidthPx` 派生换行宽（同式扣 accent
条常量；逐行缩进仍由行节点 padding/reserve 处理），换行宽与钳宽自此同源。

**既有测试为何没拦住**：`longSelfMessageClampsBubbleToMaxWidthAndKeepsAccentInside` 的注释与期望
把错误口径写成了事实（「maxBubble = 119；行切分宽 = 140」），且断言只量**节点宽**——节点宽被钳到
97，文字仍按 140 换行画出去。已改为逐行累加段落实宽的溢出锁。

**可复用教训**：

1. 「上限」与「内容宽」是两个量：任何 `min(内容, 上限)` 式的钳制必须让**内容按钳制后的宽重排**，
   否则钳制只作用于外框、内容照样画出去；
2. **只量节点宽量不出溢出**——溢出是「内容超出节点盒」，断言必须量内容（段落实宽）；
3. headless 出图的价值不止「出一张图」：像素级测量能发现生产代码里长期潜伏的口径缺陷。

### F25 宿主窗口上提：SceneHostWindow 与 headless HUD 页（2026-09-18）

**问题**：HUD 宿主的可复用单元（外壳 + 内容 + 装饰层 + 独立帧管线）原本是
`client.hud.SceneHudHost.RetainedWindow` 的私有内部类，直接读三处 client 事实
（`MyMod.LOG` / `HudTokens` / `HudToolbarService`）⇒ headless 无法复现「HUD 放置后的画面」，
只剩「在 headless 里照抄第二套装配」这条禁止项。

**上提**：新增 `ui.scene.host.SceneHostWindow`——**独立类型，不继承 `AbstractSceneHostWidget`**：
后者是「Widget 派生页面宿主：有输入源、随屏幕生命周期」，前者是「保留式窗口：无输入、内容空即隐」，
两者只共用 `SceneHostAssembly` 的装配口径。三处外部事实倒置为构造参数：
`failureSink`（原 `MyMod.LOG`）、`Shell`（原 `HudTokens` 内边距 + 外壳底色，`Shell.HUD_DEFAULT`
成为全仓唯一一份默认外壳）、`ContentDecorator`（原 `HudToolbarService.mountLayer`，失败单点隔离）。
`RetainedWindow` 退化为薄包装，工具栏注册表版本与工具栏层探针留在 client。

**headless 消费**：新增 `--page=hud`（`HudSceneProbeHost implements UiSurface`）——同一份聊天
内容树套 HUD 外壳并按四角锚定放置（`--page-index` 0/1/2/3 = 左上/右上/左下/右下，默认左下）。
`HeadlessSession` 的宿主类型由 `AbstractSceneHostWidget` 放宽为 `UiSurface`：页面宿主有两种形态，
会话只驱动渲染面，不假定宿主内部结构（否则只能二选一：要么让 HUD 页继承基类从而跑起两条管线，
要么永远出不了 HUD 图）。

**验收（一手实测）**：

- `--page=hud` 默认左下 `bounds=4,364..338,716`，`--page-index=0` 左上 `bounds=4,4..338,356`；
  外壳宽 334 = 内容 320 + 2×7 内边距（与生产外壳几何同源）；
- 空消息集 `--text=` → `commands=0 / bounds=(empty) / colors=1`：整窗（含外壳）隐藏，
  空窗路径在 headless 下可见、可与「设施没出图」区分；
- 客户端既有防线 `SceneHudPipelineTest`（608 行：工具栏外框、空窗自愈、倍率缩放逐命令对拍）
  **零改动**通过；新增 `SceneHostWindowTest` 只钉上提后新增/易退化的语义（外壳开关、装饰层隔离与
  测量、空内容判定、环境根成对、null 快速失败），不重复镜像既有覆盖。

**可复用教训**：判断「能不能上提」的判据不是代码行数，而是**外部事实的条数**——把三处外部事实
变成构造参数后，同一份装配即可在客户端与无游戏进程下运行；反过来，任何仍读静态单例的装配点，
都是下一个不可复用单元。

### F26 诊断单源收敛：帧管辖采样与调试浮层订阅（2026-09-18）

**问题**：调试开关有三处来源——帧管线的采样判定已走环境端口（F22），而 `UiPerformanceMonitor`
的计数记录与统计读取仍静态直读 `Config.useDebug`，控件内 8 处采样埋点同样直读。注入自定义诊断域时
只开一边会得到「有会话无计数」的半开态。另有真事故：`UiHudRenderListener` 用
`Computed.create(() -> Config.uiDebug)` 决定调试浮层显隐 —— Computed 读非 signal 量**没有任何依赖**，
首次 flush 后永不重算，关闭→开启后浮层再也不出现（静默失效）。

**定语义（自顶向下）**：

1. 诊断域从单值读升级为「调试事实」双成员：`debugEnabled()`（是否**采集**数据）与
   `debugOverlayEnabled()`（是否**显示**调试浮层）。二者在配置面本就是两个字段，只采不显与只显不采都合法，
   故不合并为单值；
2. 订阅**按需暴露**：只有确有响应式派生消费方的成员才配订阅通道（`debugOverlayChanges()`），
   不预先铺无人消费的通道；
3. **帧是采样的管辖单位**：`beginFrame(...)` 必须显式表态本帧诊断域（旧五参重载删除，缺席须显式传
   `DiagnosticsEnvironment.EMPTY`）。会话持**域引用而非开关快照**——帧中途改开关即时生效，
   与旧的每处静态直读语义逐位一致；嵌套帧沿用最外层会话与域，内层传什么域都不改变本帧管辖；
4. 帧外样本（面板构建、候选枚举）落入有界待折叠桶，取舍由**将要折叠进的那一帧**决定：关闭的帧在
   `beginFrame` 处作废该桶，故关闭期记录不会泄漏进后续开启的帧。

**写入口唯一**：值读权威仍是配置字段（O(1) 帧内直读）；订阅通道是它的投影，由
`ProcessUiEnvironment.publishUiDebug` 在 `ConfigValueBridge.applyGeneral`（启动加载 / 配置页保存 /
磁盘热更三条通道的唯一汇合点）内投影一次。控件侧采样门控改取 `rt.environment().diagnostics()`；
拿不到 runtime 的持有方（`PickerIconCache`）改为构造注入诊断域；无环境引用的静态工厂
（`HostImageSource.itemIcon`）不再自带埋点，计数归持有环境的调用方。

**验收（一手实测）**：

- 全量 `gradlew build` 通过（含 `checkstyleMain`/`checkstyleTest` 与 `verifyHeadlessNotPackaged`）；
- 新增 `DiagnosticsSourceGuardTest` 源码门禁：剥离注释后，`src/main/java` 中直读
  `Config.useDebug`/`Config.uiDebug` 的文件只允许「环境适配器」与「配置回灌」两处；带**正锚**
  （先证明读到了真源码）避免扫描范围写错后空集全绿；另从「必须订阅」侧守调试浮层消费点；
- `UiPerformanceMonitorTest` 全部用例只经帧入口注入诊断域驱动（不再写任何配置字段）——这本身就是
  「单源」的可判定证据；新增三例：帧中途翻转开关（本帧仍结算、其后记录即时停止）、嵌套帧域被忽略、
  null 快速失败；
- `UiEnvironmentContractTest` 补诊断域缺席值、共享订阅源单例、publish 投影链路；
- 出图回归：命令面与上一批次**逐位一致**（`--page=hud` 默认左下 `commands=25`、
  `bounds=4,364..338,716`、`clip=6/10`、`segments=19`；`--page-index=0` 左上 `bounds=4,4..338,356`；
  `--text=` 空集 `commands=0 / bounds=(empty)`）。

**顺带发现（已在 F27 定位并修复）**：headless 出图当时存在「非确定性」——同一二进制、同一命令行在不同
时间窗运行得到不同像素形态（**同一分钟窗内连续 5 次逐像素相同**），同代码跨窗差异 9590 px
（x=12..247、166 行）。当时推断为「字形栅格化 / 字体回退的跨进程顺序差异」，**是错的**：真实原因是
组头时间戳取进程当前时刻。根因链与修法见 F27。

**可复用教训**：静态开关的「收敛」不是把一处直读换成另一处直读——只要判定点还散在 N 处，就还是 N 个源。
**唯一入口 + 域引用（而非值快照）+ 帧级管辖**，三者缺一都会留下半开态。

### F27 出图确定性：虚拟墙钟解耦（2026-09-18）

**问题**：同一命令在不同分钟出图得到不同像素。观测链：同一二进制连续 5 次逐像素相同、隔几分钟再跑就变，
差异集中在组头时间戳及其相邻区（9590 px / 166 行）。第一版推断「字形栅格化的跨进程顺序差异」**是错的**
——决定性证据来自把出图的组头区域放大 8 倍：图里写着 `Steve 12:24`，而该 PNG 的 mtime 正是 `12:24:39`。

**根因**：两个探针（`ChatSceneProbeHost` / `HudSceneProbeHost`）用 `System.currentTimeMillis()` 作虚拟
时钟起点，而消息的到达时刻也取进程当前时刻；组头时间戳 = `ChatClock.format(到达时刻)` = `HH:mm`
⇒ 出图内容随真实时间变化。此前用差异像素数、行分布、跨版本对照做的归因全部指向错误方向，因为那些统计对
「时间戳在变」与「字体顺序在变」同样成立。

**修法**：把墙钟变成**请求的事实** —— `HeadlessRequest.clockMillis()`（默认
`2024-01-01T00:00:00Z`，`--clock=` 可覆盖），消息到达时刻与帧时钟起点**同源**取它。顺带根除 F23 的时序
竞态：两者既然是同一个值，「帧时钟起点早于消息出生时刻」不再可能，不必再依赖「内容构建后再取初值」这种
顺序约束。

**验收（一手实测）**：

- 相隔 73 秒（跨分钟：13:05:51 → 13:07:04）的两次 `--page=hud` 出图**差异 0 像素**；
- 组头时间戳显示固定本地时间 `08:00`（东八区 = 默认基准），命令面与修复前一致
  （`commands=25` / `bounds=4,364..338,716` / `segments=19`）；
- 新增 `HeadlessWallClockGuardTest`：headless 生产包内不得出现 `System.currentTimeMillis()`
  （`System.nanoTime()` 只用于耗时度量，不受限），带正锚。

**可复用教训**：排查「非确定性」时先把两次运行的差异**放大到看得见** —— 一眼读出「时间戳 == 文件时间」
胜过多轮统计推断；统计量（差异像素数、行分布、跨版本对照）对多种成因同样成立，不能用来定因。

### F28 环境矩阵：请求级环境事实（2026-09-18）

**问题**：出图的观感不只由页面决定，也由环境事实决定（字号倍率、诊断开关），而请求没有这些入口 ——
字号恒为不缩放、诊断开关恒取生产配置，于是「同一命令在不同机器/配置下出图不同」与「不开游戏就没法看
一帧花在哪」都无从解决。`package-info` 的不变量 3 早已声明「尺寸、缩放、帧数、输出路径全部来自请求；
不读全局单例的隐藏状态」，这一轮补的是它**已声明、未落地**的那一半。

**修法（自顶向下：环境事实进请求，会话投影到装配点）**：

- `HeadlessRequest` 增 `fontScalePercent`（域引用 `SceneRuntime` 常量，越界 fail-fast 不静默钳制）与
  `diagnostics`；缺省值取各自的中性水位，与「未声明」逐位等价；
- 新增 `HeadlessEnvironment`（`UiEnvironment` 实现）：**只覆盖诊断域**。语言/资源域按缺席 —— headless 进程
  没有那两个服务，伪造语言码只会让消费者读到不存在的事实；调试浮层开关保持 `false` 并写明「这是事实而非
  缺省」（浮层是 client HUD 注册表里的一份内容，headless 没有注册表）；
- 四个页面宿主全部改为**构造依赖**注入环境（`playground` 补环境可注入构造；chat/hud/text-probe 的探针宿主
  各加一个环境参数）；`AbstractSceneHostWidget.runtime()` 公开（与 `SceneHostWindow.runtime()` 对齐），
  字号倍率经它投影到 runtime，由 runtime 自己的失效通道通知消费者，宿主不复制环境字段；
- CLI 增 `--font-scale=P` / `--font-scales=P,…`（矩阵第三维）与 `--debug`；产物后缀规则 =「偏离缺省的维度 +
  尺寸」，故既有命令的路径逐字不变。

**顺带发现（矩阵的第一次真实产出）**：`--page=chat`/`hud` 在「内容总高 &gt; 视口高 × 0.5」时**整屏为空**。
高度裁剪 `trimHudGroupsByHeight` 按到达时刻取一个只进不退的阈值剔除更旧的组，而探针把 N 条消息的到达
时刻压成了同一个值 ⇒ 阈值一次越过全部组。实测 `1100x720` 命令面 0 条、同一内容在 `1200x720` 有 24 条，
差别只是内容总高刚好越过 `0.5×720=360` 这条裁剪线。**这是探针输入失真而非生产缺陷**（真实聊天不会同刻
到达）：改为按 1 秒一条的节奏到达、最后一条恰为 `--clock` 基准后，360P~2K 全档有内容，小视口只保留最新的
一两组 —— 那正是「刷屏不侵占半屏以上」的设计意图。

**验收（一手实测）**：

| 项 | 结果 |
|---|---|
| 360P~2K 七档 | 全部有内容（修复前 640x360 / 854x480 / 960x540 三档纯背景：1753 → 21052 bytes） |
| `--debug` 不改变像素 | 0 / 921600 差异（同一命令加不加 `--debug`） |
| 同命令两次 | 0 / 921600（F27 的确定性保持） |
| `--font-scale` 生效 | 100 vs 150：176911 / 921600 差异 |
| `--debug` 产出 | `perf:` 行含帧时间、阶段耗时（`frame.REPLAY` 等）与计数器 |

**门禁**：`HeadlessEnvironmentInjectionGuardTest`（headless 生产包不得回落 `ProcessUiEnvironment` /
`defaultEnvironment`，且「请求 → 环境 → 宿主」「请求 → runtime」的接线必须存在，带正锚）、
`HeadlessEnvironmentTest`（环境三态、请求缺省与越界校验）。

### F29 离屏不弹窗：Display 挂到不显示的 AWT 容器（2026-09-18）

**问题（用户报告）**：每次出图都弹出一个窗口挡住屏幕、干扰使用。根因：`GlOffscreenSurface.ensureContext()`
走 `Display.create()`，而 LWJGL2 的 `Display` **本身就是真实窗口**（标题 `Qz-UILib headless`）——
渲染目标其实是自建 FBO，那个窗口对出图没有任何贡献。

**修法**：`Display.setParent(从不显示的 AWT Canvas)`。`pack()` 是必要步骤而非尺寸设定 —— 它触发
`addNotify` 建出 native peer（否则 `Canvas.isDisplayable()` 为 false，LWJGL 拒绝挂载），而顶层 `Frame`
始终 `visible=false`，故即使 LWJGL 把 Canvas 置为可见，其祖先容器不可见，屏幕上什么都不出现。

**为什么不用 `Pbuffer`（本该更正统的离屏路径）**：本项目是双 classpath —— 编译期解析 lwjgl3ify shim、
运行期用真 LWJGL2。`javap` 核实：shim 的 `Pbuffer` 只有无参构造，真 LWJGL2 的只有
`Pbuffer(int,int,PixelFormat,Drawable)`，**没有共有签名**，任选其一都会在另一端抛 `NoSuchMethodError`；
而 `Display.setParent(Canvas)` 两边签名一致。

**顺带修掉一个退出死锁**：引入 AWT 后 `System.exit` 会停在 AWT 的退出钩子里 —— 实测表现为「PNG 已经写出、
`elapsed: 1024 ms` 也打了，进程却一直不退出」（jstack 显示 `main` 在 `ApplicationShutdownHooks.runHooks`
里 join）。修法：`HeadlessShotMain` 出图后显式 `GlOffscreenSurface.shutdownContext()`，释放隐藏容器并销毁上下文。

**验收（一手实测）**：出图期间每 50 ms 枚举本进程顶层窗口 —— **可见窗口峰值 0**；进程内 9 个窗口全部
`visible=false`（AWT 的 `SunAwtFrame` 隐藏容器、驱动自建的 `NVOGLDC invisible` / `__wglDummyWindowFodder`）；
进程 4.8 s 内正常 `exit=0`；命令面与修复前一致（`commands=25` / `bounds=4,364..338,716`）。

### F30 外观轴：装配期环境量与「主题只对读主题系统的树有效」（2026-09-18）

**问题**：请求无法声明外观档，出图恒取库默认配色；而「换个配色长什么样」正是出图矩阵该回答的问题。

**关键机制（决定了接入时机）**：{@code SceneThemes.install} 把主题信号写进 runtime 根作用域，而控件的
配方派生在**构建期**捕获该信号对象 —— 「重复安装同一信号对象、只改其值」能让已建树的重算，但**换一个
信号对象**只影响此后构建的控件。故外观是**装配期**环境量，必须早于内容构建安装；这与字号倍率
（`SceneRuntime.setFontScale`，自带失效通道、可装配后写）是两类不同的环境量，不能混为一谈。

**修法**：`--theme=NAME` / `--themes=NAME,…`（档名 → `SceneTheme` 的映射表落在设施内，见
`HeadlessThemes`）；三个页面宿主构造期安装（`hud` 页的安装点落在 `SceneHostWindow` 的内容工厂回调里
—— 它在 runtime 建好后、建内容前执行，正好是唯一合法时机，故**零改动**窗口类）；`text-probe` 不接收
（前景色写死，收了也是空参数）。

**顺带查清的边界（本轮最有价值的一条）**：**`--theme` 只对读 `SceneThemes` 的树有效**。

| 页面 | 换档效果（实测） | 原因 |
|---|---|---|
| `playground` | 默认 vs 浅色档 **685824/921600** 像素不同 | 外壳与 9 个演示页都经 `SceneThemes` 取配方 |
| `chat` / `hud` | **0/921600**（逐像素相同） | chat3 的 HUD 形态配色来自它自己的进程级色板 `ChatMarkdownSettings`（气泡底、正文、组头…），不读 runtime 默认主题；只有容器形态走 `SceneThemes` |
| `text-probe` | 无效 | 前景色写死 |

这条边界只能靠**矩阵实跑**发现：机制完全正确（playground 换档即变色）、门禁全绿，但 chat 页出图逐像素
不变。若只看「装了没装」会得出错误结论 —— chat3 存在两套配色来源，是它自己的架构现状，不是本轴的缺陷。

**另外两条实测性质**：不给 `--theme` 时与引入本轴之前的产物**逐像素相同**（缺省是「不干预」而非「选默认档」）；
`playground` 缺省与显式 `--theme=liquid-glass-dark` 逐像素相同（该页默认恰是 `SceneThemes.DEFAULT`）。
非法档名在请求构建期 fail-fast（退出码 2），报错列全可选档。

### F31 批量档位的历史依赖与「每档独立进程」（2026-09-18）

**发现路径**：F30 提交后做跨提交回归对拍 —— `hud@640x360` 与 `chat@1280x720` 与上一提交产物
**逐像素相同**，但 `hud@2560x1440` 差 31855 像素。基线那张是上一轮的**批量第 7 档**，本次是**单跑**。

**根因链（四步实测收口）**：

1. 单跑两次 2560x1440：**0 差异**（单跑自身确定）→ 排除随机性；
2. 抓收敛帧数：批量各档与单跑**都是 `frames: 22/60`** → 排除「停在不同帧」；
3. 改动前置档内容（目标恒为 2560x1440）：前置 `640x360` vs 前置 `1280x720` → 目标档差 **31724**
   → **产物取决于它在进程内的渲染次序**；
4. 差异空间分布：集中在内容区（y=1129..1420、x=12..407，即左下角文字块），颜色对以
   `(19,23,27)↔(20,24,28)` 这类 **±1~±5 通道差**为主 → 字形边缘的双线性采样微差。

**机制**：字体 atlas 是**进程级按需资源**：字形落在 atlas 的哪个位置取决于「此前生成过哪些字形」。
同一 JVM 内渲染第 N 档时，atlas 里已有前面各档的字形 ⇒ 本档字形 UV 不同 ⇒ 边缘采样微差。
字体侧没有「复位到冷状态」的公开入口（`FontService.reload` 是配置重载语义，不宜挪用），
而 atlas 布局本就与历史相关 —— 这不是 bug，是共享图集的固有性质。

**修法（按冲突裁决顺序：框架正确 > 效率）**：批量**默认逐档独立进程** —— 产物只依赖请求，
与不变量 3 一致；`--share-context` 保留同进程复用（快，但带历史依赖），只建议扫观感。
实现上把「轴展开」与「渲染调度」解耦：请求列表一次收口（校验 + 产物命名），隔离与同进程两条路径
消费同一份列表，子进程参数由 `argsOf(request)` 从请求反推（保证与主进程解析出的请求逐字段等价）。

**验收（一手实测）**：

| 场景 | 结果 |
|---|---|
| 批量默认：640 档 vs 单跑 | **0 / 230400** |
| 批量默认：2560 档 vs 单跑 | **0 / 3686400** |
| 档序无关：`[640,2560]` 的 2560 档 vs `[2560,640]` 的 2560 档 | **0 / 3686400** |
| `--share-context`：2560 档 vs 单跑 | 31534 / 3686400（旧行为复现） |
| 成本 | 2 档 7.3 s（隔离）vs 3.5 s（同进程），每档约 +1.9 s |

**可复用教训**：跨提交对拍时，**基线必须与本次同一条命令**。「批量第 N 档」与「单跑」是两条命令，
把它们当成同一口径会既误报又漏报 —— 本次正是靠这条不一致才发现缺口。

### F32 环境读取点接线收口：代际比对改走端口（2026-09-18）

**背景**：F22 落环境面时列出「仍未接线的读取点只剩**代际比对类**」—— `PickerIconResolver` 的资源代际、
`PickerRevisionBridge` 的资源与语言代际，共三处直读进程单例。后果：headless 出图与测试无法替换这些
环境事实，而「换代际 → 缓存失效」这条链在无头进程里只能读到真实进程状态。

**关键前提**：两个适配器的装配点（`SearchPickerFieldSupport.visualAdapterOf` / `wireRevisionAndQuery`）
**都持有 `SceneRuntime`** ⇒ 环境可达 ⇒ 正解是构造注入端口，而不是给单例加测试口。

**修法**：

- `PickerIconResolver` 构造加 `ResourceEnvironment`（保留便捷二参构造委托缺席态，与 `PickerIconCache`
  同形态）；`of(provider)` → `of(provider, resources)`，单参删除使装配点**编译期**强制给环境；
- `PickerRevisionBridge.forSource(source)` → `forSource(source, UiEnvironment)`（语言域与资源域各自取）；
- `ProcessUiEnvironment` 的「接线进度」段由「剩代际比对类」改为「已无遗留读取点」。

**顺带的测试收益**：`PickerIconResolverTest` 的资源代际失效用例原本靠
`ResourceReloadService.getInstance().onResourceManagerReload(null)` 驱动、并在 setUp/tearDown 反复复位
进程单例；改用注入的假环境后**不再触碰全局状态**，用例本身也更强（直接驱动代际）。

**门禁**：新增 `EnvironmentReadSiteGuardTest` —— 生产源码中 `ResourceReloadService.getInstance()` /
`LanguageEpochService.getInstance()` 只允许环境适配器、宿主写入口（`ClientProxy`）与两个服务自身；
带正锚（适配器必须转发两个代际 + 装配点必须真的把环境注进去）。首跑即抓到白名单路径写错
（`ClientProxy` 在 `club/heiqi/uilib/` 而非 `client/` 子包）—— 门禁在工作的直接证据。

### F33 字号域下界放开到 0，与「宿主外框未登记环境根」的顺带修复（2026-09-18）

**目标**：`FontSizeLimits` 的字号下界与 `SceneRuntime.FONT_SCALE_MIN_PERCENT` 的缺口（字段注释自述
「允许缩小方向的缺口」）一起放开，让「字号 0」成为可表达的值。等价判据 = **非零输入逐位不变**。

**语义先行（0 是什么）**：字号 0 = 该文本不参与布局且不上屏。实现不靠「在下游逐一放宽 50 处
`Math.max(1, …)`」，而是**在两条语义边界各短路一次**：

- 度量边界 `TextMeasureServiceSceneAdapter`：`fontSizePx <= 0` ⇒ 宽 0 / 行高 0 / 上下度量 0 /
  不拆行 / 不裁剪 / 无链接区域（9 处入口）；
- 绘制边界 `ScenePaintEngine`：字号 ≤ 0 ⇒ 不产 TEXT 与 SEGMENTS 命令（含不产链接热区）。

下游字形路径里的 `Math.max(1, …)`（atlas 槽位、纹理尺寸、栅格化尺寸）**保持不动**：它们是「进入
栅格化之后」的内部尺寸，字号 0 经上述短路不会走到；逐一放宽会让 0 在每个层级被重新解释成不同的最小值。

**验收（一手实测）**：

| 项 | 结果 |
|---|---|
| 非零输入逐位不变 | playground@1280x720 **0/921600**、chat@1280x720 **0/921600**、hud@640x360 **0/230400**（对拍改前产物） |
| 零字号归零 | text-probe 1→**0** 条、playground 2→**0** 条、chat 与 hud 的 text/segments 均 **0** |
| 非零倍率不变 | playground `--font-scale=50` 仍 46 条 / 1050 字符 |

**顺带发现并修复（本轮最有价值的一条）**：验证「零字号」时发现
`--page=text-probe --font-scale=0` 与不缩放**逐像素完全相同**，且 playground 每页都残留外壳那 2 条
文本 —— 即这些文本的字号**根本没被倍率影响**。根因：`attachTree` 全库只有 3 处调用
（`SceneHostWindow` / `ChatSceneProbeHost` / 定义处），页面宿主基类 `AbstractSceneHostWidget`
**从未登记环境根**；走 `SceneRuntime.mount` 的内容根由 mount 自己登记，而宿主的自有外框
（`buildShell` / `buildRoot` 直接建树）不经过 mount ⇒ `SceneNode#resolveFontEnvironment` 沿父链
找不到持有者 ⇒ 该子树读不到层 3 默认字号与用户倍率，**不报错、不改尺寸**，只有把两次出图对拍才看得出来。

修法：基类 `render` 内幂等登记（root 未变时不重复），全部页面宿主派生类自动覆盖。
`SceneHostAssembly` 的 javadoc 早就写过「守卫需钉『各装配路径必须出现 attachTree』」，但**该守卫此前
并不存在** —— 本轮补上 `EnvironmentRootAttachmentGuardTest`。

**可复用教训**：把「新能力的验收」当探针用。「字号能到 0 了吗」这个问题，实际问出的是「倍率对哪些树
根本不生效」—— 同一个验证动作同时暴露了另一处静默失效。若只验证「域接受 0」（改常量 + 单测），
这两处都不会被发现。

### F34 常驻进程形态的前置条件调研（2026-09-18，未实施）

**动机**：F10 的结论是「快」的瓶颈在每次启动的固定成本（JVM 约 1.6 s、GL 上下文创建、
`FontService` 一次调用即常驻 150.12 MiB），故 §六-3 把「常驻进程 + 请求出图」列为 M4 候选。

**本轮实测的成本基线**（隔离口径，2 档）：

| 形态 | 耗时 | 每档边际 |
|---|---|---|
| 批量逐档独立进程 | 7.3 s | 3.4 s |
| 批量同进程（`--share-context`） | 3.5 s | **1.5 s** |

边际差约 **1.9 s/张**，即常驻形态理论上能省下的部分（`capture()` 本身 0.8~1.0 s）。

**但与 F31 直接冲突**：同进程多次渲染带字体 atlas 历史依赖（产物 = f(请求, 进程历史)），
而独立进程保证产物 = f(请求)。**常驻不是速度档位，而是确定性语义的另一档**。

**前置条件（本轮核实：不具备）**：常驻要既快又确定，需要字体侧「复位到冷状态」的能力（清 atlas 页、
字形缓存、派生字体缓存、宽度缓存）。查证结果：

- `FontService.reload(FontReloadRequest)` 是**配置重载**语义（发布 desired state signal、异步 reconcile、
  需 render thread 参与 tick），不是「回到冷状态」；
- `GlyphPageManager` 无公开 reset / clear（仅内部 mailbox 清理）；
- `GlyphGenerationDispatcher.reset()` 存在，但属关停 / 重载路径的一环，不构成完整复位。

**结论**：常驻形态**暂不实施**。正确顺序是先在字体侧补「复位到冷状态」能力（独立工程），再谈常驻；
在那之前它只能作为「快但产物带历史依赖」的档位存在，与 `--share-context` 同性质，不得替代对拍口径。

**下一项**：多页面轴 `--pages=`（与既有 `--page-indexes` / `--sizes` / `--themes` / `--font-scales` 同构）。

### F35 多页面轴 `--pages=`（2026-09-18）

**缺口**：`--page` 只接单页面，跨页面矩阵（页面 × 尺寸 × 外观 × 字号）只能靠 shell 循环 —— 每轮循环
一个独立进程，既丢失「一次调用一份汇总」的可读性，也让 agent 侧要写循环。

**修法**：页面升为与其它三维同构的轴（`pageNameTargets`），共用既有的「轴展开 → 请求列表 → 分发」
骨架，故隔离 / 同进程两条路径、退出码聚合、汇总行都自动覆盖。命名规则随之补一段：**页面段只在多页面
时进后缀**（`-pg<name>`）—— 单页面时页面名已在默认文件名前缀里，再加是冗余；多页面共用同一个
`--out` 时必须能区分。

**顺带修掉一处会成为陷阱的东西**：聊天系页面（chat / hud）的「默认演示消息集」与「最小帧数提到 20」
原本是**全局**替换的。多页面时会串味（chat 的演示消息集被塞给 playground）。改为按页计算
（`defaultTextFor` / `defaultFramesFor`），单页面行为逐位不变。

**验收（一手实测）**：

| 项 | 结果 |
|---|---|
| 命名 | `--pages=playground,chat --out=out/mp.png` ⇒ `mp-pgplayground-1280x720.png` / `mp-pgchat-1280x720.png` |
| 与单跑逐像素一致 | playground **0/921600**、chat **0/921600**（后者证明按页默认值生效：chat 在多页面矩阵里仍拿到演示消息集与 frames=20） |
| 既有命令不变 | `--page=chat` 与单跑 **0/921600**；`--pages=chat` 与 `--page=chat` **0/921600** |
| 汇总行 | `batch: 2/2 ok (逐档独立进程) — playground@1280x720=ok chat@1280x720=ok` |

### F36 markdown 页的几何/渲染分叉：一个被「字号 0」牵出来的既有缺陷（2026-09-18，已修）

**怎么发现的**：独立审核在 `af52f4a4` 上指出「`Markdown 页 fs=0` 的 bounds=885 反而大于 fs=100 的 751」，
并归因于 markdown 模块的 `Math.max(1, baseFontSizePx)`。本轮按它的复现步骤追下去，结论是**现象属实、
归属不同**。

**实测（`--page=playground --page-index=8 --size=1280x720`）**：

| 倍率 | commands 修前→修后 | text | segments | bounds 修前 → 修后 |
|---|---|---|---|---|
| 100 | 101 → 101 | 16/185ch | 62 → 62 | 1279 x 751 → **1279 x 751（同图）** |
| 150 | 101 → 100 | 16/185ch | 62 → 63 | 1309 x 818 → 1309 x 951 |
| 200 | 101 → 99 | 14/152ch | 62 → 66 | **1592** x 885 → 1592 x 801 |
| 0 | 24 → 27 | **0/0ch** | **0** | 1279 x **885** → 1279 x **716** |

**关键读数**：fs=200 的宽度 **1592 已横向溢出 1280 视口** —— 也就是说**这不是「字号 0」的问题**，
而是任何非 100% 倍率下都存在的既有缺陷，只是「字号 0」把它放大到肉眼可见（装饰仍在、文字消失、
卡片被拉长）。

**修后结论**：

- fs=100 与改前**逐字节相同**（SHA256 `A8FB1156C3978823C7B5E1E1FA3BE5203596C6BBB061B91783B38F5BF003B497`，
  110800 bytes）—— 回归锚成立；
- fs=150/200 的字形**真正随倍率放大**且不再重叠。修前 fs=150 的字号其实**根本没放大**（几何按 14px 算、
  排布也按 14px 算，倍率只作用在节点声明层而没进入几何），观感与 100% 几乎无差 —— 「高倍率出图正常」
  本身就是分叉的伪装；
- fs=0 的装饰不再按 14px 排布，bounds 885 → 716；
- fs=200 的 1592 宽仍溢出 1280 视口，但同参数换 `--size=2000x1200` 后 `outsideViewport=0`、
  `text=18/214ch`（1280 下是 14/152ch）⇒ 少掉的字符是**视口裁剪**，不是内容丢失。高倍率内容自然更宽，
  溢出视口属预期。

**根因（两层，缺一层都修不动）**：

1. **调用方**：`MarkdownPage` 把声明值 `BASE_FONT_PX` 交给 L2 布局（换行基准、样式表基准、行高/行宽、
   围栏块与代码块几何），而节点在场景里按 `effectiveFontSize()` = 声明 × 倍率渲染 ⇒ 几何与渲染不同尺度。
   审核者引的 `Math.max(1, baseFontSizePx)` 那几处**不是**元凶（那里的 `baseFontSizePx` 是 14，不是 0）。
2. **搬运层**：`MarkdownPageContent.nodes()` 把 `command.getTextStyle().getFontSize()` 直接写进节点
   `setFontSize`。该命令字号处于**生效尺度**（L2 用生效基准算出来的），写进声明层后会被解析出口
   **再乘一次倍率**（2×2 重复缩放）。

第 2 层是第一版修法**自己踩出来**的：把节点字号改成生效值后，fs=150 出现修前**没有**的行重叠
（行框按 21px 算、字形按 32px 画）—— 靠「新图 vs 旧图」逐张目检抓到。这正是 `ChatFontMetrics` javadoc
早已写明的 RC-06 症状（「文字放大、行框与气泡不变」），口径是：**声明层永远写设计值，只有几何用生效值**。

**顺带分辨了审核列的另外几处**（同一轮里逐一看过上下文）：

| 位置 | 判定 |
|---|---|
| `TextLinePlan:67` 行高保底 | **真分叉**，已修（保底只对 fontSizePx > 0 生效） |
| `TextLayoutService` 宽度/行高入口 | **真分叉**，已修（字号 ≤ 0 短路；markdown 等模块绕过 scene 度量适配器直接用它） |
| `PickerMetrics.fontSizeFor` | **真分叉**，已修（`rendered <= 0` 时不再夹到 FONT_FLOOR —— 其 javadoc 本就承诺「几何必须与渲染逐值相同」） |
| `ChatSceneController:908` / `ChatContainer:439` | **非分叉**：`Math.max(1, 行高)` 是 `36.0 / lineHeight`、`chatPx / lineHeight` 的**防除零**，去掉会除以零 |
| `GridMetrics:96,151` | **非分叉**：`trackHeight` 的 1px 下限是网格单元的**可点击下限**，属交互约束 |

**可复用教训**：审核给的落点清单要**逐一看上下文再归类**。「凡是 `Math.max(1, 字号派生量)` 都是分叉」
这条判据过宽 —— 其中既有真分叉，也有防除零与交互下限；照单全改会把防御性代码改成缺陷。

**修法（两个提交）**：

1. `[Refactor]` 把「声明 → 生效」解析式收敛到字号域唯一出口
   `FontSizeLimits.effectiveFontSizePx(声明值, 倍率)`，替换 `SceneNode.effectiveFontSize()` /
   `ChatFontMetrics.scalePx` / `PickerMetrics.fontSizeFor` 三处各自手写、只能靠注释互相对齐的同式
   （行为逐位不变）。此前三份实现的注释都写着「与出口同式」—— 同一语义靠注释维持一致，本身就是分叉源。
2. `[Fix]` `MarkdownPage`：几何（L2 换行基准、样式表基准、行高/行宽、围栏块与代码块几何）改用生效字号
   `layoutFontPx(rt)`，节点声明层仍写 `BASE_FONT_PX`；`MarkdownPageContent.create` 把「声明字号 / 布局字号」
  拆成两个参数，`nodes()` 的盒宽/盒高取**命令字号**（生效尺度）、声明层写设计基准。
   `epochs` 组合 `textMeasureEpoch()` 与 `fontEpoch()`（前者不含倍率，只订阅它则倍率变化不重算）。

架构上**早已支持**「字号变化 → 重算内容布局」——`MarkdownPageContent.layout(measurer, width, font, epoch)`
的缓存键本就含字号，缺的只是实参来源。

**验收判据（已达成）**：

- [x] fs=100 与改前**逐字节一致**（SHA256 相同）—— 生效字号在 100% 时 == 声明值，回归锚成立；
- [x] fs=0 的 bounds 885 → 716（装饰不再按 14px 排布）；
- [x] fs=150/200 字形真正随倍率放大、无行重叠（新旧图逐张对照）；
- [x] 宽视口 `--size=2000x1200` fs=200 `outsideViewport=0`、内容完整；
- [x] 完整 `gradlew build` 通过（含 test）。

**新增教训**：改字号分叉时先问「这个量在哪一层 —— 声明层还是几何层」。把生效值写回声明层的修法比原缺陷
更糟（原缺陷只是几何偏差，错修会引入**重叠**），而只看 bounds 数字**发现不了**（重叠时 bounds 甚至更小）；
必须多倍率出图对照。上一轮预研把它判成「独立批次」是对的 —— 真正的难点不在改哪几行，而在判准层归属。

**独立审核的反驳与后续修正（f30c78e0..d2bb1358，零上下文子代理）**：

| 反驳 / 发现 | 判定 | 处置 |
|---|---|---|
| 「修前 fs=150 的字号根本没放大」 | **部分不成立，且我的第一次更正自身又错** | 实测 h1–h5 = 24→31 / 21→28 / 18→25 / 16→23 / 15→22（1.29~1.47×）——**放大了，但差额固定**（delta 不随倍率），只有 h6 与正文是 1.5×；缺的是**几何**放大，那才是重叠来源。第一次更正写成「h1–h5 确实未放大」把现象说反，本行是第二次更正 |
| `layoutFontPx` 是 create 期捕获值 ⇒ `fontEpoch` 订阅是无效功 | **成立** | 运行期改倍率会「用旧字号重算一遍」：文字按新倍率放大而行框停在旧尺度（F36 复发）。修法：宿主在字号代际**真正抬升**时 `refreshPage()`（订阅挂 rootOwner，见 `TestPlaygroundHost`）；headless 是「先 setFontScale 再建页」，走不到该路径，故由 `TestPlaygroundHostTest` 的行为用例守 |
| 契约 javadoc 引用不存在的方法 `effectiveFontPx`，且陈述旧实现 | **成立** | 已改写为当前实现的准确契约（声明层写设计基准、盒几何取命令字号、`layoutFontPx` 捕获值语义） |
| 标题 delta 不随倍率缩放 ⇒ 标题层级被压缩 | **成立** | 实测 h1 字号 24/31/38（= 14×scale + 10），h1/正文 1.71→1.48→1.36。修法：设计增量随倍率等比换算（现已收归 `MarkdownStyleTable.scaledDesignMetrics`），h1 ≈ 24×倍率 —— 因 `round(14s) + round(10s)` 两次取整，201 档中 196 档与 `24s` 差 ≤1px（pct=125 时 31 vs 30），不是逐值恒等 |
| 「第一版修法导致 fs=150 重叠」 | **无法判定** | 该中间态不在 git 中（reflog 无记录），当时只有新旧图对照。这是「出图对照」作为证据的固有局限：能证明现象，不能复现过程 |
| `--page-index=8` fs=100 偶发两种输出 | **成立 → 已定位并修复** | 基线复现：50 次 48:2、32 次 29:3、16 次 14:2（4%~12.5%，随负载浮动）。根因：`TextLayoutService.tryAcquireWidthMissBudget()` 的宽度缓存 miss 预算按 **16ms 真实时间窗**限流，超预算的码点按空格宽近似排版 ⇒ 哪些码点被近似取决于本次运行的真实耗时，布局宽度随机器负载漂移。定论靠剂量-反应（只改预算，各 16 次）：64 → 14:2；10^8 → 16/16 全同；1 → 第三种分布。修复：headless 会话解除预算（`HeadlessSession.open` 置 `FontConfig.widthCacheMissBudgetPerWindow = 0`），耗时无退化（+0.9%，t=1.43）。另发现：加大 settle（2→20）不消除少数派 ⇒ 债务清偿不保证收敛，近似被固化进最终帧；生产侧同口径，是否改为与时间无关的降级量待决策 |

审核同时确认成立的：三处解析式提取逐位等价、fs=100 与改前逐字节相同（审核自建 `f30c78e0` worktree 取真基线）、
150%/200% 重叠消除、fs=0 装饰塌缩、测试未被削弱、build/test 通过、提交信息里的全部数字与 SHA256。

**第二轮独立审核（`ea6ed656`，零上下文子代理）的结论与处置**：

- **确认**：picker 零字号贯穿且**更快**（fs=0 首个候选即满足 soft：268ns/call vs fs=11 的 1182ns/call，
  「走满降级阶梯导致性能退化」的担心不成立）；宿主重建无白重建、订阅归属正确
  （effect 数 0 → 397 → dispose 后 0）、9 个注册页全部重建成功；两个新测试在对应旧提交上实跑失败（非自证）
- **反驳 1**：「连跑 6 次 5:1」不可复现 —— 审核 48 次得 **47:1**（约 2%）；若真为 1/6，48 次里 ≤1 次的
  概率约 0.0017。已按 48 次样本改写使用文档，并写明不要据小样本推断概率
- **反驳 2**：「h1 恒为 24×倍率」只到 ±1px 量化（`round(14s) + round(10s)` 两次取整，201 档中 196 档
  与 `24s` 有偏差）。措辞已改为「≈24×倍率，量化误差 ≤1px」
- **反驳 3**：上表第一行那次更正**自身又错**（写「h1–h5 未放大」），已在表内二次更正
- **新发现（最该先处理，已修）**：行内 code 段字号是设计常量 12，且样式表的长度量既无公共 setter、
  也无统一换算口 ⇒ fs=200 时 code 12px 而正文 28px（应 24）、fs=50 时 code 比正文还大。修法：样式表开
  `scaledDesignMetrics(float)` 作为「设计 → 生效」唯一换算面（公共面哨兵 19→20，只加这一个口），
  `MarkdownPage` 改为「先按设计值登记、再统一换算」。**未覆盖**：chat3 侧 `chatStyleTable()` 是静态无参、
  拿不到倍率上下文，其行内 code 仍留 12，属独立批次
- **待处理限定**：`fontSizeFor(声明, pct)` 在 pct∈[5,87]（83/201 档）仍与内容根真实生效字号不等 ——
  残留分叉，需先查 `ScenePickerPanel` 的声明值夹取语义再定修法；`labelBudgetPx(fs=0)` 返回 `2*pad=4`
  而非 0（javadoc 已改准确）

### F37 配置页纳入 headless：装配与 MC 宿主拆类（2026-09-18）

**动机**：配置页是 agent 改 UI 后**唯一无法出图验证**的主要界面——页面表当时只有
playground / text-probe / chat / hud。

**一手事实（决定接入成本）**：

- `club/heiqi/config/ui` 全树（`ui` + `field` + `editor` + `theme`）`import net.minecraft` **实测 0 条**
  ⇒ 配置页 UI 是纯 uilib 新架构，不需要 MC 域、不需要完整类路径；
- `ConfigUI.buildScreen(manager, input, …)` 的 javadoc 早就写着「input 平台输入源，可为 null（headless）」
  —— 接入是设计内路径，不是外挂；
- `ConfigScreen extends AbstractSceneHostWidget implements UiSurface` ⇒ 天然是 headless 会话接受的渲染面。

**接入形态**：`--page=config`；`--page-index` = **section 下标**（走屏幕公开入口 `showSection(int)`，
与导航点击写同一个受控源，不依赖命中坐标）。配置真源落**随会话删除的临时目录**（不改写用户真实配置），
文件初始不存在 ⇒ 出图是**默认配置下的配置页**。**不订阅** `ConfigSaveListener`：它把保存结果回灌
本进程运行态并触发字体 reload，那是宿主的职责（订阅还会向 `ModernConfigApplyCoordinator` 注册全局
Registration）。外观档不接收（见下）。

**装配与宿主拆成两个类（本轮的关键修法）**：首次接入把 `buildScreen` 与 `createScreen(GuiScreen)`
放在同一个 `ModernConfigEntry` 里，最小集下**出图直接失败**——实测
`Class.forName("…ModernConfigEntry")` ⇒ `NoClassDefFoundError: net/minecraft/client/gui/GuiScreen`。
**触发点经独立复核定位（并推翻了我的初版解释）**：不是「方法签名含 GuiScreen」——合成实验证明仅出现在
方法签名 / 字段类型 / `checkcast` / `instanceof` / `invokevirtual` 里的 MC 类型都不触发，真实变异也照样
出图；真实原因是**校验期的可赋值性检查**：`return new ModernConfigScreen(parent, screen)` 要证明
`ModernConfigScreen → McScreenBridge → GuiScreen` 可赋值，于是被迫解析缺失的父类型；把那一句改成先赋
`Object` 再强转，`Class.forName` 立刻恢复 OK（单行变异）。判据由此改为「有没有把子类型收敛到缺失的
父类型」，不是「有没有 import MC」。修法：拆出零 MC 依赖的
`ModernConfigAssembly`（Schema → 字段定制 → ConfigScreen），`ModernConfigEntry` 只留 MC 宿主包装
（定位配置文件、订阅回调、包 GuiScreen）。判据与 F25「宿主窗口上提」同源：**一个类要么是宿主、
要么是装配**。与 F21 的区别要分清：chat/hud 的探针宿主在**方法体**里用 MC 类型，因而只在完整集可跑
——那是注入面事实；本轮撞的是**类加载**层面，连最小集都进不去。

**接入时自己踩的清理缺陷**：`createConfigHost` 初版用 `catch (ConfigException)` +
`catch (RuntimeException)` 清理临时目录，而当时真实抛出的是 `NoClassDefFoundError`（`Error` 不是
`RuntimeException`）⇒ 临时目录残留（实测残留 1 个目录 / 0 文件）。改为 `handedOff` 标志 + `finally`：
正常路径把删除动作交给会话（`HostBinding.cleanup()`），其余一切出口在 `finally` 里删。
**类型枚举的失效方式是静默漏一类，`finally` 不是。**

**复核补的两处结构缺口（已收口）**：① `open()` 里 `createHost` 交棒后到构造会话之间抛
Error/RuntimeException 时 `binding.cleanup()` 无人执行（会话还没建出来）——现包一层 catch 执行清理
并释放离屏上下文；② `close()` 的清理排在 `host.dispose()` / `surface.close()` 之后且不在 `finally`
内，那两步抛 Error 即跳过清理——现收进 `finally`。两处原先都无实测触发手段，属结构缺口而非已发生缺陷。

**门禁与它的区分力（含一条被复核补上的覆盖缺口，如实记录）**：新增 `HeadlessPageLinkageTest`——直启出图，
断言 `--page=config` 与 `--page=playground`（正锚，用来区分「页面坏了」与「最小集/注入面坏了」）
在**最小集**上都 exit=0 且落出 PNG。首版只钉这两条，独立复核用变异指出**覆盖缺口**：
把 `ConfigScreen.showSection` 改成空操作、或摘掉 `HeadlessSession.close()` 里的 `hostCleanup.run()`，
门禁**全绿**——本轮交付了页面可出图 / section 轴生效 / 不留痕三项能力，却只守住一项。
已补两条判据并各自变异验证：G1 空操作 ⇒ `configSectionAxisSwitchesContent` FAILED（其余三条绿）；
G2 摘清理 ⇒ `configPageLeavesNoTempDirectory` FAILED（其余三条绿）。四条判据各钉一项交付。
另：给装配类加「仅方法签名引用 MC 类型」的探针方法时门禁仍绿（作者与复核两方一致）——那不是判据失效，
而是**它不是机制推断**：它钉的是端到端事实。

**验收（一手实测）**：

| 项 | 结果 |
|---|---|
| 单页 1280×720（最小集） | `commands=59 [fill=1 surface=33 text=25/226ch]`、`bounds=0,0..1280,745`、`outsideViewport=0`、`colors=1156`、自检 ok、**917 ms** |
| 三 section（`--page-indexes=0,1,2`） | `batch: 3/3 ok`；命令面 / 颜色数两两不同（59/1156、58/1066、41/1117）⇒ 切换真的生效 |
| 无进程外痕迹 | 会话关闭后临时目录 0 残留；仓库内 `qzuilib-modern.yaml` 未生成 |
| 门禁 | `HeadlessPageLinkageTest` 首版 2/2（`skipped=0`，产物 81089 B / 200031 B）；复核补两条后 **4/4**，两条新判据各自变异验证会红 |

**外观档边界**：配置页不接收 `--theme`——它在页壳树构建前安装自己的偏好信号
（`ConfigThemePreference`，默认平面档）。主题对配置页是**配置内容**而非请求级环境量，要换档得改配置
真源。已记入指南的环境矩阵页面表；命令层现给**显式提示**（复核指出原先属静默忽略，与本仓
「不许静默降级」的取向相反）。

**语义边界（复核追问后补齐）**：「不订阅」的必然代价是**字体运行态也不回灌**——真机路径订阅后由
coordinator 做 initial apply（仓库注释自述「可能随后把 FontConfig 清为空」），而 headless 保持进程当前的
字体发现态（实测 507 个字体顺序）。同一页面在这两条路径上的字体解析**可能不同**，可见程度本机无法
判定（不能开游戏）。已写进指南。

**顺带的公共面变更**：`ConfigScreen` / `ConfigUI` 增加**环境可注入**构造与重载（旧构造保留并委托
`SceneHostAssembly.defaultEnvironment()`，生产行为逐位不变）。理由不只是 headless：F22 已定「宿主环境
是构造依赖」，而配置页此前只有单参 `super(input)` ⇒ 永远读生产单例，且这一处**不受 headless 包的环境
门禁覆盖**（门禁只扫 headless 生产包自身）。

### F38 磨玻璃实验室纳入 headless，与一个藏了 16 天的玻璃 shader 静默降级（2026-09-18）

**动机**：`glass`（磨玻璃实验室）是**液态玻璃观感的主验收面**，却是唯一没有进 headless 页面表的主要界面——
agent 改玻璃渲染后无法出图自证。它是页面覆盖缺口里最后一块大件。

**一手事实（决定接入成本）**：

- `GlassLabHost` 全类 `import net.minecraft` **0 条**，且宿主无关（与 `TestPlaygroundHost` 同为
  `AbstractSceneHostWidget` 子类）⇒ 最小集即可跑，与 F37 的 config 页同类；
- `GlassLabEntry` / `GlassLabScreen` 是 MC 宿主包装，headless 不碰它们（F37 的「一个类要么是宿主、
  要么是装配」判据自动满足）；
- 缺口在于**注入面**：原构造只有 `(input)` 与 `(measurer, input)`，主题信号是字段初值 `SceneThemes.DEFAULT`
  ⇒ 无法按请求装配外观档。

**接入形态**：`--page=glass`；新增 `GlassLabHost(input, environment, theme)` 与
`GlassLabHost(measurer, input, environment, theme)`，旧构造改为委托（环境取生产默认、外观取宿主默认，
生产行为逐位不变）。与 F30「外观是装配期环境量」同口径：主题信号必须在建树前确定。`--page-index` 忽略
（实验室无子页）。

**头号发现：玻璃 shader 自 2026-09-02 起从未编译成功（静默降级 16 天，覆盖该文件 21 个版本中的后 17 版）**

接入后第一次出图，诊断卡给出的是 `backdrop 路径: fixed-pipeline | 诊断: shader-unavailable, samples=8,
effect-degraded(no-vibrancy)` —— 即玻璃走的是**固定管线降级档**（多重采样叠加模糊，无 vibrancy / 亮边 /
噪点 / 液态折射）。诊断文本被投影截断，故写了一次性反射探针直读程序状态（探针已清理）：

| 探针读数（初版只做到单阶段编译；复核补齐到 **program** 级） | 结果 |
|---|---|
| `FULL_BUDGET_PROGRAM.ensureInitialized()`（编译 + 链接 + 校验） | **false** |
| 失败消息 | `UI backdrop 着色器编译失败: 0(19) : error C0000: syntax error, unexpected $undefined at token "<undefined>"` |
| `uiBackdropF.frag` 原样编译（注入抽头预算后） | **FAIL**（同上） |
| 同一 frag 把非 ASCII 逐字符换成 `?` | **OK** |
| `uiBackdropV.vert` 原样**单阶段**编译 | **OK** —— 但**整程序不是 OK**，见下 |

**根因**：GLSL 1.20 的源字符集是 ASCII，而 `uiBackdropF.frag` 的中文注释（首行 `#version` 之后的
两段说明）被 NVIDIA 编译器判为语法错误。行号 `0(19)` 与源文件行号不一一对应（整份源 ASCII 化后立刻通过，
故不必追到具体字符）。

**「vert 中文无害」是初版结论，已被独立复核推翻（本轮修正）**：初版探针只做到单阶段
`glCompileShader`，据此写过「`uiBackdropV.vert` 有 4 行中文却通过 ⇒ 非 ASCII 一律致命不成立」。
复核用**程序级**探针（编译 + 链接 + `glValidateProgram`）实测：中文 vert + ASCII frag 时两个 stage
**各自编译都返回 OK**，但**整个 program 链接失败**（`LINK-FAIL Vertex info`）—— NVIDIA 把完整编译推迟到
link 阶段；把非 ASCII 换成 `?` 后 program 立刻恢复 OK。也就是说**本机驱动上非 ASCII 落在任一阶段都让程序
不可用**，门禁取严（任何非 ASCII 即红）的理由比初版写的更充分，而"分阶段看起来没事"不能作为放行依据。

**回溯 git（逐版本统计非 ASCII 行数）**：

| 版本 | 非 ASCII 行数 | 说明 |
|---|---|---|
| `502ce419`…`ec0429a3`（2026-04-28 ~ 06-13） | **0** | shader 一直可编译 |
| `ac1582a2`（2026-09-02 01:25） | **5** | 首次写入中文注释 ⇒ **本日起 shader 不可用** |
| `b26399c2`（2026-09-13） | 127 | 最后一次写入 |

也就是说：2026-09-02 之后所有玻璃材质档（vibrancy / tint 门控 / SDF 亮边 / 反 banding 抖噪 / Liquid Glass
折射）**从未在屏幕上出现过**，而这期间的多个提交恰恰在按真机观感调这些参数。

**为什么能藏 16 天**：降级是**设计内**行为（`shader-unavailable → fixed-pipeline`），既不抛异常也不打日志；
画面只是「没那么好看」。观测面上唯一的分叉是诊断卡文本，而它此前只有开游戏才看得到 ——
**这正是「UI 改动必须能出图自证」的用例本身**。

**修法**：把 `uiBackdropF.frag`（127 行）与 `uiBackdropV.vert`（4 行）的注释按行翻成 ASCII 英文；
代码行**逐字节不变**（用「行号 → 替换行」映射脚本改，并断言行数不变、目标行原本含非 ASCII、替换后无非 ASCII、
行尾仍为 LF）。修复后两个档位程序 `ensureInitialized=true`。
（第二轮复核指出缩进恢复脚本曾给 frag 误加一个文件末尾换行，已还原 —— 现在两份文件的行数、末尾换行状态都与
HEAD 一致，剥离注释后代码逐字节相同。）

**顺带新增的读数：`backdrop:` 行**（本次窗口的玻璃事实）：

- 读的是 `UiRenderContext.getLastBackdropFilterRenderPath()` + `getLastBackdropFilterDetail()`；
- 但**不能直接用最后值**：静态「最近一次路径」在批量同进程出图时会把上一档的残留报成本档事实。
  故新增 `UiBackdropFilterRenderer` 的**请求计数**（完整入口自增，被策略/档位短路、快照不可用、降级、
  tint 兜底都计入）与 `UiRenderContext.getBackdropFilterInvocationCount()`，读数取窗口前后差；
- 语义三种：`path=shader`（完整路径）/ `path=fixed-pipeline|tint-fallback`（降级档）/ `none`（**本窗口最后一次玻璃请求没有成功路径**：可能是没发起请求，也可能是被策略/档位/几何/裁剪短路 —— 由同行 `requests=` 的**有无**区分：有 `requests=` 即发生过请求、无则未发起）；
- `detail` 段含主层内容版本号（实测 `rev=N`，来自 `MainLayerSnapshot.getContentRevision()`，不是帧序号 —— 帧数是同行 `frames:` 读数）等过程量，但它们同样只由请求决定：**实测同命令多次运行整行逐字节相同**，可参与对拍（不进 PNG）。

**门禁（5 条）**——其中 4 条已验证「变异即红」，`glassPageRendersOnTheMinimalClasspath` 是**对照锚**（缺陷态下仍绿，正是它与另两条各钉一项的证明）：

| 判据 | 钉住的事实 | 变异验证 |
|---|---|---|
| `GlslSourceAsciiGuardTest` | GLSL 源（全部 `.frag`/`.vert`）纯 ASCII 且无 BOM；目录扫空即失败（防改名静默空转） | 在 vert 插入一个中文 ⇒ **FAILED**（1 failed） |
| `glassPageRendersOnTheMinimalClasspath` | 实验室在最小集真的出图 | 缺陷态下**仍绿**（页面能出图、只是降级）——与下一条各钉一项 |
| `glassBackdropUsesTheShaderPath` | 端到端：本帧玻璃真的走 shader | frag 切回中文缺陷态 ⇒ **FAILED** |
| `backdropPathIsNotInheritedAcrossPagesInOneProcess` | `--share-context --pages=glass,text-probe` 下 glass 报 shader、text-probe 报 none | 判定改成「最后值」⇒ **FAILED**（1 failed，其余 7 绿） |
| `glassThemeAxisSwitchesContent` | glass 真的接收 `--theme`（两档颜色数须不同） | `createHost` 里 theme 传 `null` ⇒ **FAILED**（8 tests / 1 failed）。此条是**独立复核指出的覆盖缺口**：此前该能力无任何判据守着（20 条测试全绿） |

**验收（一手实测）**：

| 项 | 修前 | 修后 |
|---|---|---|
| `--page=glass --size=1280x900` 路径 | `fixed-pipeline`（`effect-degraded(no-vibrancy)`、samples=8） | **`shader`**（`blur=18, saturation=1.00, family=CLASSIC, material=REGULAR vibrancy=1.45`） |
| 同图读数 | `colors=7327`、277892 B | `colors=16336`、739951 B |
| 命令面 | `commands=62 … clip=19/37` | `commands=62 … clip=19/19`（固定管线逐 quad 叠加的入口计数消失） |
| 多页面批量 | — | `--pages=playground,chat,hud,config,glass` ⇒ **5/5 ok**，playground/chat/hud/glass 均为 `path=shader`，config 为 `none`（配置页 UI 无玻璃请求） |
| 外观轴 | — | dark vs light **585339/1152000** 像素不同；solid-dark 请求数 8（实色档多数表面不带滤镜配方） |
| 同命令可复现 | — | 同参数两次 `0/1152000` |

**独立审核与处置（零上下文子代理，本轮流程）**：

审核总判 **有条件通过**；它独立复现了全部关键主张（自建仓外探针、5 组变异、`cleanTest test` 实跑、
逐项核对文档数字），并给出 1 条【应当修】+ 若干建议。处置如下：

| 审核意见 | 处置 |
|---|---|
| 【应当修】「vert 中文无害 ⇒ 非 ASCII 一律致命不成立」失实（程序级：两 stage 各自编译 OK 但 program 链接失败） | 已改本节根因段与 `GlslSourceAsciiGuardTest` 类注释，门禁取严的理由改为「任一阶段非 ASCII 都让程序不可用」 |
| 【建议】`detail` 段「逐次不同、不参与对拍」失实（同命令三连跑逐字节相同） | 已改 `HeadlessArtifact` javadoc 与使用指南：该行**可参与对拍** |
| 【建议】`none` 混两种状态（未发起 / 请求全被短路） | javadoc 与指南改为「本窗口**最后一次**玻璃请求没有成功路径」，并说明按同行 `requests=` 的有无区分（第二轮复核把口径精确到「最后一次」） |
| 【建议】glass 的 `--theme` 注入无判据（变异传 `null` 时 20 条测试全绿） | **补判据** `glassThemeAxisSwitchesContent`（两档颜色数须不同，变异验证会红）；并把 glass 行补进 `HeadlessThemes` 类注释，使指南「出处」重新成立 |
| 【建议】91 行注释缩进被改（与「行号→替换行」方法不符） | 已按 HEAD 原缩进逐行恢复；自查：剥离注释后代码 156 / 10 行**逐字节相同** |
| 【建议】「跨 21 个提交」口径偏大 | 已改为「覆盖该文件 21 个版本中的后 17 版」 |
| 【建议】双 dispose 处没有指向记录 | 改为**直接清理**（复核已证幂等；处置记录见下方「边界与未做项」第 3 条） |
| 【建议】门禁新开 `gl.shader` 包、与同族测试分家；硬编码相对路径 | **不改**：本类判据是「资源字符集」（扫源目录全量字符），与 `ui.render` 的着色器语法测试不同族；路径取 Gradle 工程根（test 工作目录），且「目录扫空即判失败」已防静默空转 |
**第二轮独立复核（针对上述处置）** 总判仍为**有条件通过**，指出 4 点，提交前已全部处置：

1. F38 节内两处旧句（`none` 语义、「`detail` 不参与对拍」）未随代码注释与使用指南同步，与本节处置表自相矛盾
   —— 已就地改写为与代码一致的表述；
2. `none` 的新措辞「本窗口没有成功路径」**强于实现**：判定取的是**最后一次请求**的路径，先成功、最后一次
   被短路时会报 `none`（复核在约 38 次出图里未复现，属措辞精度）—— 已全部改为「本窗口**最后一次**玻璃请求
   没有成功路径」；
3. 缩进恢复脚本给 frag 误加了一个**文件末尾换行**（HEAD 无尾换行），使「只改注释」在字节级不成立
   —— 已还原并复核（见「修法」段末）；
4. 门禁段标题「4 条，全部变异验证会红」与表内对照锚「缺陷态下仍绿」相抵 —— 已改为「5 条，其中 4 条验证会红、
   1 条是对照锚」，并把复核指出的覆盖缺口判据 `glassThemeAxisSwitchesContent` 补进表内。
**边界与未做项（如实登记）**：

1. **材质档 shader 从未在真机跑过**：本机 headless 只能证明「编译通过、路径为 shader、像素画出来了」，
   **不能替代观感验收** —— 玻璃观感（vibrancy 强度、亮边形态、抖噪颗粒度）需用户在真机复验；
   这也意味着修复后**玻璃观感相对 09-02 以来会有明显变化**，这不是新特性而是设计意图首次生效；
2. 本机 headless 走 `shader` 是**环境事实**（RTX 5070 Ti / NVIDIA 610.74），换驱动仍可能落到固定管线，
   故 `backdrop:` 行必须逐次如实报告，使用文档已写明「别当跨机器金样」；
3. **（已处置，保留记录）`HeadlessSession.close()` 的重复 `host.dispose()` 已在本轮清理**（独立复核确认宿主 dispose 幂等：
   `Owner` 回收有 disposed 短路，第二次调用恒为 no-op）。原计划「留待下一轮」，复核给出幂等证据后一并删除，
   属零行为差异的清理；
4. **退出码契约缺口**：用错启动器（最小集跑 `chat` / `hud`）时装配抛未捕获的
   `NoClassDefFoundError: net/minecraft/...`，进程以**退出码 1** 收场 —— 不在契约的 0/2/3/4/5 内。
   本轮先在使用文档写明处置（换 `qz-shot-full.bat`），收口见 **F39**；
5. GLSL 里 `#if UIB_TAP_BUDGET` 两档（13/9）都实测可编译，但 headless 只跑到完整档（`BackdropQualityService`
   默认档）；9 档的端到端出图未覆盖。

### F39 失败语义收口：类路径缺件不再破坏退出码契约（2026-09-18）

**动机**：F38 的独立复核顺带指出 —— 用最小集启动器跑 `chat` / `hud` 时装配抛未捕获的
`NoClassDefFoundError`，直接冒泡出 `main`，JVM 以**退出码 1** 收场。1 不在契约（0/2/3/4/5）内，
脚本与 `HeadlessShotGate` 无从分流：既不能判「环境不具备」跳过，也不能按「设施失败」定位。

**修法（三条，缺一条都不闭环）**：

1. **进程边界兜底**：`HeadlessShotMain.run` 拆成「兜底包装 + `runRequest` 主体」，新增
   `catch (Throwable)` → `diagnoseUnexpected`。兜底落点**单一**是刻意的：probe / 查询 / 单档 / 逐档隔离
   四个出口各写一遍必然漂移（同 `exitCodeOf` 的理由）。
2. **新退出码 6**（`EXIT_CLASSPATH_INSUFFICIENT`）：判据是**类型 + 报错形态**（沿 cause 链找
   `NoClassDefFoundError` / `ClassNotFoundException`，并**排除** `Could not initialize class …` 这一
   erroneous 类形态 —— 见下方「独立审核」），不靠人读日志。**为什么独立于 3 与 5**：三种失败的处置
   互不相同 —— 3 查代码、5 换环境、6 换启动器；混进 3 会让 agent 去查一个没坏的 UI，混进 5 会让
   调用方把配置错误当环境问题跳过。
3. **聚合一致**：逐档独立进程路径（`runIsolated`）里子进程返回 6 时**就地终止并原样上报** ——
   6 是请求级不可恢复（换档不会变好），落进既有的 `else` 分支会被算成「内容可疑(4)」，
   把配置错误伪装成 UI 问题。

**可操作指引**：诊断打印缺失类型名 + 「换 `qz-shot-full.bat`」；若缺的不是 MC 类型，则提示检查
`exportHeadlessClasspath` 的导出是否完整。

**验收（一手实测，最小集 `qz-shot.bat`）**：

| 命令 | 修前 | 修后 |
|---|---|---|
| `--page=chat` | 未捕获 `NoClassDefFoundError` ⇒ **exit=1** | `CLASSPATH-INSUFFICIENT：当前类路径缺少类型 net.minecraft.util.IChatComponent` + 指引 ⇒ **exit=6** |
| `--page=chat --sizes=640x360,1280x720`（逐档独立进程） | 子进程各以 1 退出 ⇒ 父进程既有聚合把它算成「内容可疑」⇒ **exit=4**（且两档都跑完、无就地终止） | **exit=6** + `batch: 因类路径缺件终止，后续档未执行（chat@640x360）` |
| `--page=playground` | 0 | **0**（不变） |
| `--page=nosuchpage` | 3 | **3**（不变，未知页面仍是能力探测失败） |

**门禁**：`HeadlessPageLinkageTest.missingMinecraftDependencyReportsContractExitCode` —— 最小集跑 chat，
断言退出码 = 6、输出含 `CLASSPATH-INSUFFICIENT` 与 `qz-shot-full`；变异验证：把归类返回值改成 1
（模拟「未归类、冒泡成 JVM 退出码 1」）⇒ **FAILED**（9 tests / 1 failed，其余 8 绿）。为此把测试的直启
辅助拆成 `runShot`（不判退出码）+ `render`（判 0），共用同一条直链。

**独立审核与处置（零上下文子代理）**：总判 **有条件通过**（3 条【应当修】+ 6 条【建议】，提交前均已处置；
逐条处置与流程细节留在提交说明，本节只留结论）。审核独立复现了 10 条通路的退出码（单档 / 逐档隔离 /
同进程批量 / 目标寻址 / `--probe` / `--find` / `--pages` 均 6，playground 与 `--pages=playground,glass` 为 0，
未知页面 3 —— **全部落在契约内**）、聚合不吞并 6、真实缺陷（`StackOverflowError` / `AssertionError` /
`OutOfMemoryError` 等）落 3 且带完整栈、变异在最终 revision 上为 10 tests / 2 failed 且可字节级还原、
`cleanTest test build` 全绿（5815 tests / 0 failures / 7 skipped）。处置要点：

- `missingTypeOf` 排除 erroneous 类形态（`Could not initialize class …`，类在但 `<clinit>` 已失败）并补单元判据
  `erroneousClassIsNotClassifiedAsMissingClasspath`（erroneous → 3、真缺件 → 6、`ClassNotFoundException` → 6）；
- `exitCodeOf` 写明「被包裹的缺件」边界（行为不变：当前不可达，将来若把缺件包进 `HeadlessFailure` 须同步判）；
- `runIsolated` 的 `@return` 与 `HeadlessShotGate` 类注释补 6；
- 指南区分「逐档隔离」与「同进程批量」报 6 的行为（前者打印「后续档未执行」，后者直接终止）；
- 6 分支补打完整栈：判据含报错消息形态，漏网时只有一行伪类型名、无从定位。

**刻意不改的**：`HeadlessShotGate` 仍只对 5 跳过、6 一律红 —— 除本页的类路径契约判据（它按设计就用
最小集跑 chat、并把 6 断言为**正确**结果）外，门禁用的是正确启动器，出现 6 即测试配置错误，应当红。

### F40 玻璃质量档纳入 headless：eco 卷积核变体首次端到端执行（2026-09-18）

**动机**：`BackdropQuality`（`full` 13 抽头 / `eco` 9 抽头 / `solid` 不装滤镜）是进程级档位，
headless 此前没有请求级入口 —— 于是 eco 走的 `#if UIB_TAP_BUDGET >= 13 → #else` 分支**从未在出图设施里
执行过**。F38 修好 shader 编译后 13 抽头路径已有证据，9 抽头变体仍是盲区：它的程序能否编译/链接、
降档后是否仍走 shader，都没有读数。这是「覆盖缺口」而非新功能。

**接入**：

- 新请求参数 `--backdrop-quality=NAME` / `--backdrop-qualities=NAME,…`（与 theme / font-scale / size / page
  同构的第五个轴，产物后缀 `-bq<档>`）；
- 装配期写入 `BackdropQualityService.applyConfigured`（进程级唯一写入口，含非法值告警与同值幂等短路）；
- 档名在请求构建期校验：未知档名直接失败（参数错误 → 退出码 2），不静默回落 `full`
  （`BackdropQuality.parse` 的契约是配置容错语义，命令行拼错不可接受）；
- `backdrop:` 读数行首加 `quality=<档> taps=<预算>`：这是「这张图用的是哪套卷积核」的唯一入口，
  且没有玻璃请求时也会打印（否则「档位设了但没请求」与「档位没接线」不可区分）。

**验收（一手实测，1280×900）**：

| 档位 | 读数 | colors | 说明 |
|---|---|---|---|
| `full` | `quality=full taps=13 path=shader requests=72` | 16336 | 默认档 |
| `eco` | `quality=eco taps=9 path=shader requests=72` | 16340 | **9 抽头变体编译/链接通过并真的走了 shader** |
| `solid` | `quality=solid taps=13 path=none requests=90 detail=disabled by page policy` | 1072 | 实色档下玻璃请求被策略禁用（预期） |

- **两档卷积核真的不同**：`--backdrop-qualities=full,eco` 的产物逐像素差 **157190/1152000（13.64%）**；
- 非法档名：`--backdrop-quality=bogus` ⇒ 退出码 2 + 可选值清单（`full / eco / solid`）；
- 多档汇总行：`batch: 2/2 ok (逐档独立进程) — glass@1280x900 bq=full=ok glass@1280x900 bq=eco=ok`。

**门禁**：`HeadlessPageLinkageTest.backdropQualityAxisSelectsTheKernelVariant`（eco ⇒ `quality=eco taps=9`
且仍 `path=shader`）；变异（摘掉装配期接线）⇒ **FAILED**（11 tests / 1 failed，其余 10 绿）。

**顺带修正**：两条既有判据（`glassBackdropUsesTheShaderPath` / `backdropPathIsNotInheritedAcrossPagesInOneProcess`）
的断言写死了行首 `backdrop: path=`，读数加档位段后它们变红 —— 判据在守，但也暴露断言绑定了行首段。
已改为不依赖行首的 ` path=shader`。教训：**读数行是可扩展的，断言不应绑定它的段序**。

**独立审核（零上下文子代理）**：总判 **有条件通过**（1 条【应当修】+ 3 条【建议】，均已处置）。
它一手复现了三档读数、13.64% 像素差、`--share-context` 四个档位序列的产物与逐档独立进程**逐字节相同**
（跨越 OFF 档往返也无残留 ⇒「产物是请求的函数」在该轴成立）、唯一写入口无旁路、门禁变异恰好红在那一条、
`cleanTest test build` 全绿（5816 tests / 0 failures / 7 skipped）。【应当修】是使用指南的轴清单 / 命名契约 /
环境矩阵未随第五轴同步（4 处）；另有一条隐含前提已补进代码注释：**`applyEnvironment` 必须留在
`createHost` 之后**（配置通道也会写档位，顺序反了会被静默盖成另一档）。

### F41 搜索选择器（picker）纳入 headless：控件级出图入口（2026-09-18）

**动机**：picker 是库内体量最大的控件族（面板 + 分类导航 + 虚拟网格 + 成员带 + 信息条 + 密度档），
而**生产配置页 schema 里没有任何字段挂 `SearchPickerSpec`**（`Values.searchPicker` 只在测试里出现）
⇒ 改 picker 之后既不能从 `--page=config` 看到它，也没有别的出图入口。它是主要界面里最后一个
没有出图入口的控件。

**接入**：新建 `PickerProbeHost`（headless 包，宿主无关、零 MC 依赖）：

- 控件级装配 `ScenePickerPanel.create(rt, Props.builder(...).open(恒真).build())`；
- 候选数据由探针自备（24 个方块 id + 中文名），不接候选源 SPI —— 面板走结果信号路径，
  与真机「装配层持候选、面板只渲染」的分工一致；
- `--page-index` = 演示状态（0 全部 / 1 过滤 / 2 空态），走宿主公开入口 `showState`，
  与 playground 的 `showPage`、config 的 `showSection` 同口径（不依赖命中坐标）。

**为什么是控件级而不是走 `SearchPickerFieldSupport`**：后者要 `ValueSpec` + `Registry` + `Codec` /
`SearchFunction` / `CurrentValuePresenter` 的完整接入面，而**生产 schema 没有 picker 字段** ——
照测试夹具搭一套的话，出图反映的是「按测试拼出来的接线」，不是真机字段形态（与 F37 配置页
「字段定制与游戏内同一个入口」的取向相反）。故本页明确限定为**控件级**，并在使用文档里写明无覆盖的部分。

**验收（一手实测，1280×720）**：

| 状态 | commands | colors | bytes | 关键读数 |
|---|---|---|---|---|
| 全部候选 | 116 | 1410 | 356569 | 顶栏 `24 results`、左栏 `All 24`、网格 4×6 |
| 过滤后（`--page-index=1`） | 40 | 1133 | 354578 | 查询 `stone`，结果收缩 |
| 空结果（`--page-index=2`） | 21 | 1061 | 355873 | 中栏 `No matching results`、`0 results` |

命令面 116 > 40 > 21（结果越少画得越少）、三档 colors 两两不同 ⇒ 状态切换真的改了内容。

**门禁**：`HeadlessPageLinkageTest.pickerPageRendersAllThreeStates`（三态命令面严格递减 + 三态 colors
两两不同 —— 只测「exit=0 有产物」的话，把 `showState` 改成空操作也能全绿）；变异（`showState` 空操作）
⇒ **FAILED**（12 tests / 1 failed，其余 11 绿）。

**过程中被门禁拦住一次（如实记录）**：`PickerProbeHost` 初版带了一个单参构造（回落
`SceneHostAssembly.defaultEnvironment()`，照 glass 包 `GlassLabHost` 的形态写），
`HeadlessEnvironmentInjectionGuardTest` 立刻红 —— 该禁则要求 headless 生产包内不得出现
`defaultEnvironment` / `ProcessUiEnvironment`（源码级扫描、剥注释后匹配）。修法是删掉单参构造：
同包的 `TextProbeHost` / `ChatSceneProbeHost` / `HudSceneProbeHost` 都没有它，漏接环境即**编译失败**。
**跨包差别**：`GlassLabHost` 保留单参构造在生产路径是正确语义（游戏内打开实验室就该用生产环境），
它不在本包故不受此禁则约束 —— 照抄邻近类的形态而不看禁则作用域，正是这次踩到的点。

**边界（如实登记）**：

1. 控件级：字段外壳 / 行触发器 / 值与选择写回（`SearchPickerFieldSupport` 那条链）**无覆盖**；
2. **物品图标不渲染**：探针 `VisualAdapter` 只提供文本标签，headless 没有物品贴图通路 ⇒ 格内是空槽 +
   下方名称，**不能**据本页出图判断图标渲染；
3. **悬停 tooltip 的内容**看不到（底栏 `Hover a result to see its full name and ID` 是常驻文案、默认可见，
   看不到的是悬停后弹出的完整名称与 ID）—— 需要输入脚本悬停；
4. **分类导航只有 `All` 一行**：探针不注入 `categories` / `categoryOf` ⇒ 分类行与分类维度切换未覆盖；
5. **密度档只有 AUTO 求解出的一档**（`Density STANDARD`）：探针不注入 `densityPreference` ⇒
   compact / standard / roomy 三档未覆盖；
6. 受控 `open` 恒真且未接 `onCloseRequest` ⇒ **关闭与提交路径不可演示**（当前无害：关不掉也不崩）；
7. 成员带（`listMembers` 模式）与候选源 SPI 路径未覆盖（探针只走结果信号路径）。

**独立审核与处置（零上下文子代理）**：总判 **通过**（无【应当修】，4 条【建议】，提交前均已处置）。
它一手复现了三态读数与退出码、矩阵命令与单跑逐字节相同、过滤 vs 空态像素差 149199/921600、
门禁影子类变异恰好红在预期断言（12 tests / 1 failed / 11 绿）且 `classpath.txt` 逐字节还原、
`cleanTest test build` 全绿。4 条建议的处置：① 指南与本节把「悬停提示默认看不到」改成准确表述
（常驻文案可见、看不到的是 tooltip 内容）—— 这是本轮唯一一处失实；② 补登记漏掉的两条覆盖边界
（分类导航只有 `All`、密度档只有 AUTO 一档）与关闭路径不可演示；③ **picker 实际吃 `--theme`**
（dark colors=1410 / light colors=847），补进环境矩阵页面表与 `HeadlessThemes` 类注释（该表出处），
并按 glass 的先例**补门禁** `pickerThemeAxisSwitchesContent`；④ `PickerProbeHost` 收紧为包内可见
（同包其余三个探针宿主同口径）。审核另附一条归因结论：`--share-context` 多页序列「第 2 页起」的微小
像素漂移由**页面切换本身**引起、与本轮无关（`text-probe` / `config` 同样复现），登记为既有现象。

### F42 chat3 段流的倍率环境轴收口：行内 code 字号 + 字号变化失效通道（2026-09-18）

**动机（两个缺陷，同一条链）**：

1. F36 立了「设计量 → 生效量」唯一换算面（`MarkdownStyleTable.scaledDesignMetrics`），chat3 侧却漏了入口：
   `ChatMarkdownPipeline.chatStyleTable()` 是**静态无参**方法、恒取 `MarkdownStyleTable.defaults()`
   ⇒ **行内 code 字号永远停在设计值 12**，而正文按倍率走（100% 13 / 200% 26）。
2. 更外层：`ChatSceneController` 的失效通道只到「切分器 / 合成器」（`composer()` 用有效字号做指纹），
   **消息列表没有指纹**，而段流里的长度设计量（行内 code 字号、引用缩进与竖条、分隔线厚、表格内衬）都是列表在
   **构建期**向管道取用的；组节点又走 keyed diff，在同一个挂载点上重算只会复用旧节点。结果是字号环境变化后
   行节点解析字号已到 26，段样式仍停在 12。headless 的 `--font-scale` 在**装配之后、首帧之前**写入
   （`HeadlessSession.applyEnvironment`），而探针宿主在装配期就建了树，正好落在这条失效通道里
   ⇒ **该环境轴对 chat 页的段流量整体不生效**。

**接入**：

- `chatStyleTable()` → `chatStyleTable(float fontScale)`：先 `defaults().scaledDesignMetrics(fontScale)`，
  再叠 chat3 覆盖（引用色、横线文本置空）；
- 倍率沿真实数据流显式下传：`ContentEntry` 增 `fontScale` 字段（结构探测恒按 `1.0F`，字号不影响结构）、
  `sameProjection` 增 `entry.fontScale == fontScale`、`layoutContent` 两个重载与 `layout` 增参、
  `logicalCached` 增参；**两级缓存 key 都带倍率**（`cacheKey` 的 `#fs<float>` 段）；
- `ChatMessageList.messageList()` 与 `composer()` **同式**补「有效字号指纹」（`messageListFontPx`）：
  字号变了就用新字号重建列表实例，段流随之重取；
- `ChatSceneController.tick` 的字号环境分支（原 RC-06 的 fontEpoch 检测）在置 `composer = null` 之外
  置 `fontRebuildPending`，与形态切换同路走一次 `rebuildTree` —— 组节点走 keyed diff，**换挂载点才会重建**；
- 生产调用点两处都传 `rt.fontScale()`：`ChatMessageList` L1110（`layoutContent`，内容路）与 L1139
  （`layout`，气泡行路）。

**验收（固定命令 + 一手读数）**：

```bat
build\headless\qz-shot-full.bat --page=chat --size=900x400 --text="Steve:正文与 `code` 对照" --font-scales=100,200 --out=out\f42final.png
```

| 产物 | 修复态 | 缺陷态（变异：`chatStyleTable` 钉 `scaledDesignMetrics(1.0F)`，含 `compileJava`） |
|---|---|---|
| `out\f42final-900x400.png`（fs=100） | 9934 B · `8b5ba9a6…` | 9934 B · `8b5ba9a6…`（**逐字节相同**：回归锚） |
| `out\f42final-fs200-900x400.png`（fs=200） | 21798 B · `13af1490…` | 19511 B · `1db66d85…` |

fs=200 档的产物差异（+11.7%）就是行内 code 12 → 24 的可见后果；fs=100 档逐字节不变，说明修复不碰不缩放档。

**门禁（四条，各钉一条链）**：

| 判据 | 钉什么 | 变异反应（脚本带 try/finally + 还原逐字节校验） |
|---|---|---|
| `ChatMarkdownPipelineFontScaleTest.inlineCodeFontSizeFollowsFontScale` | 管道：倍率进管道即生效 | 变异 `scaledDesignMetrics(fontScale)`→`(1.0F)` ⇒ 红 |
| `ChatMessageListTest.inlineCodeSegmentFontFollowsFontScale` | 气泡行路 `layout()` 的生产调用点 | 同上 ⇒ 红（该变异会让本条与管道判据、内容路判据同时红） |
| `ChatMessageListTest.inlineCodeFontFollowsFontScaleAfterAssembly` | **运行中**切倍率（装配后写入）时段流重建 | 变异「去掉 `fontRebuildPending`」⇒ 红 |
| `ChatMarkdownTableConsumerTest.tableInlineCodeFontFollowsFontScale` | 内容路 `layoutContent()` 的生产调用点（表格 / display math） | 变异「只把 `layoutContent` 那处 `rt.fontScale()` 换常量」⇒ 红 |

最后一条是**独立审核逼出来的覆盖缺口**：补之前，把 `layoutContent` 那处换成常量时其余判据全绿
（审核者实测 + Lead 复核）。四条判据都避开 `MarkdownStyleTable.getCodeFontSizePx()`（包内可见，跨包不可读）。

**过程中的三个坑（如实记录）**：

1. **出图启动器不编译**：缺陷态出图必须先 `gradlew compileJava`，否则启动器用的还是上一次编译的类 ——
   首次做修复前/后对照时两态产物逐字节相同，差点把「修复无效」写成结论。
2. **内容路判据不能在 `ChatMessageListTest` 里造**：表格 / display math 路由要求消息是 `printMarkdown`
   递交形（`MARKDOWN_LEFT`），普通 `ChatLineRecord` 只会落系统路（树上只有原始文本段）。该判据改放在已有
   `printMarkdown` 夹具的 `ChatMarkdownTableConsumerTest` 里。
3. **表格单元里的行内 code 会被换行拆段**（实测段文本落在 `gradle` / `build` 两段），判据按
   `TextStyle.isCodeSpan()` 定位而不是文本匹配。

**边界（如实登记）**：

1. 换算面本身齐、消费方已逐页核：生产侧只有两处入口 —— `MarkdownPage`（`180-182` / `336-341`）早已
   `scaledDesignMetrics(rt.fontScale())`，`ChatMarkdownPipeline` 本轮修；两个静态兜底表
   （`MarkdownInlineParser.CODE_TABLE_FALLBACK`、`MarkdownDocument.FALLBACK_TABLE`）只在调用方传
   `styles == null` 的兼容入口用到（无倍率来源，属既有设计）；行内 code 字号的唯一生产来源就是样式表。
2. **表格内衬 / 边框厚等表格几何的倍率换算未单独钉住**（独立审核附注）：表格单元经 `LayoutContent.getTables()`
   另行送达 L2，本轮判据钉的是表格里的行内 code 段。
3. `--share-context` 多页序列「第 2 页起」的微小像素漂移是既有现象（与页面切换本身有关，F41 已登记），本轮未复核。

**独立审核与处置（零上下文子代理，2026-09-18）**：总判**有条件通过**。它一手复现了设计字号 13 / 12 与
200% 的 26 / 24、确认**无双重缩放**（`resolveEffectiveFontSizePx` 显式绝对值优先、不乘倍率）、缓存 key 与调用点
无遗漏，并做了 6 组变异（含它追加的缓存维度）与全量门禁（5800+ 用例绿）。它指出的四条【应当修】均已处置：

1. **`layoutContent` 链零门禁**（只改那处时其余判据全绿）⇒ 补
   `ChatMarkdownTableConsumerTest.tableInlineCodeFontFollowsFontScale`，复跑变异确认变红；
2. **运行中切倍率段流不刷新应修、不宜降级为下一轮** ⇒ 本轮修（列表字号指纹 + 字号变化整树重建），补
   `inlineCodeFontFollowsFontScaleAfterAssembly` 判据，并用 headless 出图确认环境轴恢复生效；
3. **验收数据与文件名失实** ⇒ 删除无法复现的旧读数（11228 / 21592 / 26656 B 与 `-fs100` 后缀名），改用
   上面的固定命令 + 可复现读数（9934 / 21798 / 19511 B 与 sha256）；
4. **门禁表两条描述与实测相反** ⇒ 更正：变异「样式表钉 1.0F」会让**多层判据同时红**（Lead 复核确认）；变异
   「两处调用点换常量」会让气泡行路判据红（审核者该组读数与 Lead 两次复现不一致，以一手复现为准）。

它另有两条【建议】未在本轮落地并如实登记：100% 档的自动回归锚（现由产物 sha256 人工比对承担）、
表格几何倍率的独立确认（见边界 2）。

### F43 玻璃质量档覆盖 chat / hud：验证型增量（2026-09-18）

**动机**：`--backdrop-quality` 是 F40 引入的请求级玻璃档，但当时只在 `glass` 页有判据；工作站任务清单里
「`--backdrop-quality` 与 chat/hud 的组合未测」一直是覆盖缺口（该清单在工作站仓，不在本仓）。这两页的玻璃形态与 glass 页不同：
chat 页的玻璃在**气泡表面**（`ChatMessageList` 的 bubbleSurface → `UiBackdrop.liquidGlass(DARK_REGULAR…)`），
hud 页是同一棵内容树走 `SceneHostWindow` 宿主装配（外壳 + 四角锚定 + 帧管线）。

**实测（1280×720，`--backdrop-qualities=full,eco,solid`，逐档独立进程）**：

| 页面 | full | eco | solid |
|---|---|---|---|
| chat | `taps=13 path=shader requests=22` · 11563 B | `taps=9 path=shader requests=22` · 11568 B | `taps=13 path=none`（策略禁用） · 9501 B |
| hud | `taps=13 path=shader requests=21` · 11841 B | `taps=9 path=shader requests=21` · 11881 B | `taps=13 path=none` · 9866 B |

三档产物两两不同（full vs eco：chat 差 5 B、hud 差 40 B；solid 分别少 17.8% / 16.7%）⇒ 档位真的进了这两页的像素；
`eco` 档同样 `path=shader`（9 抽头变体在这两页的材质参数下也编译/链接通过）。

**门禁**：`HeadlessPageLinkageTest.backdropQualityAxisAppliesToChatAndHud` —— 遍历 chat / hud 两页，
断言三档读数各自正确（full 13 抽头 / eco 9 抽头 / solid `path=none`）、两个非 solid 档的
`path=shader` 计数为 2、三档产物 sha256 两两不同。

**顺带补的测试通道**：chat / hud 触及 `net.minecraft.*`，最小集直启以退出码 6 收场（那是
`missingMinecraftDependencyReportsContractExitCode` 的工况）。故本判据走**完整集** classpath
（`classpath-full.txt`，与最小集同目录）直启，文件缺失时 `Assume` 跳过；`runShotWith(Path, …)`
是从 `runShot` 抽出的公共实现，两条路共用注入 / 清理 / 输出捕获。

**变异验证（各自独立脚本 + try/finally + 还原逐字节校验）**：
① 去掉 `HeadlessSession.applyEnvironment` 的档位写入 ⇒ 判据红；
② 去掉 chat 气泡的玻璃装配（`ChatMessageList` 的 `builder.backdrop(chatBubbleGlass(...))` → `null`）⇒ 判据红。
两条都说明判据钉的是**装配与档位写入本身**，而不是「exit=0 有产物」。

**边界（如实登记）**：hud 页的玻璃来自同一份气泡装配（判据覆盖两页，根因同一处）；
`--share-context` 下三档同进程复用的档位写入语义沿用 F40 的登记（每档 open 各写一次、同值幂等），本轮未重复验证。
**独立审核与处置（零上下文子代理，2026-09-18）**：总判**有条件通过** —— 判据真实性、覆盖面、读数、
变异可红性与全量门禁均经独立复核，无假绿实证；唯一条件是用文档表格缺 `--text` 前置条件（不带 `--text`
时走 `CHAT_DEFAULT_TEXT`：chat 三档实测 69927 / 69958 / 51607 B、hud 70792 / 70786 / 52972 B，与本表
数值差约 6 倍，表格数字无法自复现）。它另做了 4 组变异，其中它追加的「`tapBudget` 恒 13」（读数不变、
像素相同）让 glass 既有判据仍绿、只有本判据的**像素断言**变红 ⇒ 证明像素断言补的正是读数断言的盲区。
处置：① 表格补 `--text="Steve:glass probe"` 前置条件与 `quality=` 前缀，差值改为逐页（chat 5 B /
hud 40 B；solid 少 17.8% / 16.7%）；② 按建议给 `build.gradle.kts` 的 test `doFirst` 补
`classpath-full.txt` 存在性 check（与那段注释声明的「构建期 fail 一次，别让门禁静默跳过」同旨，
消除本轮新增的跳过通道）；③ full / eco 档断言收紧为带 `path=shader` 的子串；④ 删掉小节末尾多余空行；
⑤ 本节「任务清单」改为「工作站任务清单」并注明不在本仓（审核者无法在仓内检索到该出处）。

**未采纳的建议（如实登记）**：审核者指出 full / eco 断言是纯子串匹配、无玻璃请求时也可能通过；
本轮以「收紧为带 `path=shader`」+ `countOf(" path=shader") == 2` + solid 的 `path=none` 三者共同承担，
不再额外重复断言。

**残留边界**：`--share-context` 下三档的档位写入语义仍是 F40 的登记（本轮未重复验证）；
无 GL / natives 不可用环境下的 `Assume` 跳过链路只在静态层面推导过（本机有 GL）。

### F44 输入脚本与目标寻址入门禁：交互证据能力的空白补上（2026-09-18）

**动机（覆盖缺口）**：`--actions` / `--script`（输入脚本）与 `--nodes` / `--find` / `--center`（目标寻址）
是 agent 用 headless 做「交互后出图」的全部手段；使用文档有整章示例，但**测试里零覆盖**（全仓 grep：
`src/test` 只命中 `--bg`）—— 没有判据守着「脚本真的派发事件、点击真的改变画面、地址与中心点自洽、
越界按契约失败」。设施的核心承诺是「产物是请求的函数」，交互请求尤其依赖这条链。

**实测（playground 首页，最小集启动器）**：

| 观察 | 读数 |
|---|---|
| 基线（无脚本） | `input: dispatched=0 pointer=0,0`；命令面 `segments=0`；产物 827799 B |
| `--actions="move 953 84; frame; click; wait 8"` | `dispatched=3 pending=2 pointer=953,84`；`segments=62`（Markdown 页）；产物 747778 B |
| `--find=Markdown` | `r0/1/0/8 SceneNode "Markdown 渲染" @879,64 148x40 fs=16 [target] center=953,84` |
| `--center=r0/1/0/8` | `center r0/1/0/8 = 953,84`（与 `--find` 逐字一致） |
| `--center=r0/1/0/99`（真实父路径 + 越界下标） | exit **3** + `[能力探测] 地址越界：… 在第 2 层要下标 99，但该节点只有 9 个子节点` |
| `--page-index=8`（对照：直接跳到 Markdown 页） | `segments=62`、产物 743933 B（`--page-index=1` 是另一页：`segments=0`、742951 B）—— 与点击路接近但不同（点击路带 hover / 焦点态） |

**门禁（两条）**：

- `HeadlessPageLinkageTest.actionScriptDrivesAClickThatChangesTheShot`：基线 `segments=0` → 脚本点击后
  `segments>0`、指针落在脚本声明的坐标、`dispatched=` 非空（钉「脚本真的派发、点击真的改变画面」）；
- `HeadlessPageLinkageTest.findAddressResolvesToTheSameCenterAndOutOfRangeFails`：`--find` 报出的地址与
  中心点，`--center` 必须解出**同一串**坐标；越界地址必须按契约以退出码 3 失败（钉「地址 → 中心点」
  这条链自洽，不许静默回落）。

**顺带补的测试通道**：`--find` / `--center` / `--nodes` 是**查询路径，不产出 PNG**（使用文档写明），
故新增 `renderQuery(...)`：只判退出码、不要求产物 —— 直接复用 `render(...)` 会误报「未产出 PNG」
（本轮实际踩到一次）。

**变异验证（各自独立脚本 + try/finally + 还原逐字节校验）**：
① `HeadlessInputSource.drainFrame()` 不推进设备（脚本事件不进帧）⇒ 判据 1 红；
② `HeadlessNodePath.resolve` 的越界检查改成「静默夹取最后一个子节点」⇒ 判据 2 红。
**如实记录一次「变异未生效导致误判」**：越界断言的变异第一次改的是 `resolve` 里的取值行
（`current = children.get(index)`），而**边界检查就在上一行、仍在生效** ⇒ 变异等于没做、判据全绿；我据此
写下「虚构深路径抓不住静默回落」的因果，**这个因果是错的** —— 独立审核按字面实现真变异后复现：
`--center=r0/9/9` 同样 exit 0 + `center = 1066,410`，旧写法照样会红。真正的教训有两条：
① 变异必须落在**被钉的那句语义**上（边界检查），而不是相邻的取值行；
② 判「判据抓不住」之前，必须先确认**变异真的改变了行为**（本例用 `--center` 的退出码就地确认）。
断言侧同时收紧：越界现在除 `exit == 3` 外还要求失败**自证成因**是「地址越界」—— 退出码 3 是
「未捕获的未预期错误」与「全部能力失败」的公共码，单钉退出码挡不住「夹取到未布局节点」这类静默回落。

**独立审核与处置（零上下文子代理，2026-09-18）**：总判**有条件通过**。它独立复核了改动集（3 文件、
114 插入、0 删除、既有 `render(...)` 的 15 个调用点未被触碰）、判据真执行（XML `tests=2 skipped=0`）、
一手读数（逐字复核，均属实）、三条变异（含它自选的「`click` 去掉 press/release」）与全量门禁
（5825 用例 / 0 失败 / 7 跳过）。5 条【应当修】全部处置：

1. 使用文档新增的「实测」段落在 ```bat 围栏**内部**（渲染成一坨代码）⇒ 移到围栏之后；
2. 本节 `--page-index=1` 行的 `segments` 读数失实（实测为 0；Markdown 页是 `--page-index=8`）⇒ 更正为
   `--page-index=8`（`segments=62`、743933 B），并把 index=1 的真相并列；
3. 本节「判据自身失败」段的因果不成立 ⇒ 按上文如实改写（真因是变异锚点选错、且未确认变异生效）；
4. 判据 1 的 `contains("dispatched=")` 无判别力（基线自带 `dispatched=0`；变异 C 下 `dispatched=1` 也过）
   ⇒ 改为解析数字并断言 >0；
5. 判据 2 只钉 `exit == 3` ⇒ 补「失败必须自证成因是『地址越界』」。

它另有 4 条【建议】，本轮采纳 2 条、登记 2 条：`segmentsOf(base)==0` 已在断言处注明「前提而非被测行为」；
新增判据 `queryPathsDoNotProduceAShot`（查询路径不得产出 PNG —— 它指出 `renderQuery` 注释里的契约本身
没有判据守着）。**未采纳并登记为下一轮候选**：`--script=file` 的封口（与 `--actions` 同源，价值有限）、
设备模型其它语句（`down/up/dblclick/scroll/key/type/compose/cancel`）的覆盖。

**边界（如实登记）**：`--script=file`（文件通道）与 `--actions` 同源，本轮只钉后者；`--nodes` 的节点
事实表格式未单独钉（已有 `--find` 的地址一致性作间接覆盖）；交互覆盖只有 playground 导航一次点击，
chat / picker 的输入脚本未覆盖。

### F45 picker 查询输入闭环：`type` / `compose` / `key` 首次驱动过滤（2026-09-18）

**动机（能力缺口 + 输入语句零覆盖）**：设备模型的 `type` 与 `key` 语句在测试里从未被验证过
（`compose` 只有设备层单条判据 `HeadlessInputDeviceTest.composeDeliversWholeTextInOneFrame`，没有
「输入 → 状态变化」的端到端覆盖；F44 只钉了 `move` / `click`）。picker 页的查询框是「文本输入 → 状态变化」的天然工况 ——
实测却发现**输入进去了但不生效**：查询框节点文本确实变成 `stone`（`--nodes` 可读），候选集却恒为 24 条。

**根因**：`PickerProbeHost` 有 `filteredCandidates(query)` 与查询回写（面板 `Props.onQuery`），但**没有订阅
查询变更** —— `--page-index=1` 的过滤态是手动同时写 `query` 与 `results` 两个信号绕出来的，于是
「输入脚本键入」与「切演示态」是两条路，前者不重算候选。修法：装配期加一条绑定（查询变更 ⇒ 重算候选），
`showState` 收缩为只设查询串 —— 与真机「装配层持候选、面板只渲染」的分工一致，不是新增第二套接线。

**实测（picker 页，最小集，用 `--nodes` 读节点事实表的 `Search results (N)`）**：

| 脚本 | 结果数 |
|---|---|
| 无（基线） | 24 |
| `--actions="move 446 138; frame; click; frame; type stone; wait 12"` | **5** |
| `compose stone`（整串提交，前缀同上） | **5** |
| `type zzzz`（无命中） | **0** |
| `type stone` + `key BACKSPACE`×5 | **24**（逐字符编辑回全量） |

**门禁（三条）**：`pickerQueryInputFiltersResults`（基线 24 → `type stone` 5 → `compose stone` 5 →
`type zzzz` 0 → **`type glass_` 1**）、`keyStatementEditsTheQuery`（`type stone` + 5 次退格回到 24）、
`scrollStatementMovesContent`（`scroll -5` 让内容区节点上移 5px；主证据取 `--nodes` 的 y 位移而非像素哈希）。
`glass_` 那条是**审核指出的盲区补丁**：只断言 `stone`=5 时，「type 少派发末尾 1~3 字符」会伪装通过
（`st`/`sto`/`ston` 都是 5 条），而 `glass_`=1 / `glass`=2 能把它区分开。变异（各自独立脚本 +
try/finally + 还原逐字节校验）：① 断开查询绑定 ⇒ 判据 1 红；② `key` 不派发 ⇒ 判据 2 红；
③ `scroll` 不派发 ⇒ 判据 3 红；④ `type` 少派发末尾字符 ⇒ 判据 1 红（补 `glass_` 前该变异**存活**）。

**过程中的坑（如实记录）**：改完 `PickerProbeHost` 后直接跑 `qz-shot.bat` 观察 —— **没有任何变化**，
因为出图启动器用的是 `build/classes` 里的旧类、不会自动编译（F42 已记过同一条坑，本轮又踩一次）；
`gradlew compileJava` 之后 24 → 5 立刻出现。

**一次被审核推翻的「负面实测」（如实记录全过程）**：本轮一度登记「`scroll` 在 config 页不产生任何
像素变化」，依据是几组对照（含 `scroll -5 @700,200`）出图**同哈希**。独立审核按最小复现复核后**推翻**：
同一工况下 `scroll -5` 出图 80802 B（对照 80361 B），`--nodes` 里内容区节点 y **位移 5px**；Lead 复核一致
（`scroll -5 @700,350`：80802 B / `0d2dd12b…`；@700,200：80810 B / `7b221e0a…`；内容区
`r0/0/2/1/0/0/0/1/0/2` 的 y 205 → 200）。真相是**方向语义**：负 wheelDelta（内容向下滚）才有效，
`scroll 10`（内容已在顶部）不产生位移 —— 把「正向无效」读成「语句无效」是错的。
教训：**否定性结论必须换工况/换方向复现**，单点同哈希不足以判定「无效果」。

**边界（如实登记）**：`dblclick` / `down` / `up` / `cancel` 仍未覆盖；picker 的成员带 / 候选源 SPI /
分类导航 / 密度档仍未覆盖（F41 登记）；`--script=file` 仍只由 `--actions` 间接代表（F44 登记）。
**独立审核与处置（零上下文子代理，2026-09-18）**：总判**有条件通过**。它独立复核了改动集（本轮 4 文件；
并正确排除了他人正在改的 `docs/开发者文档/规格文档/` 下文件）、两条判据真执行（JUnit `tests=2 skipped=0`）、
数字反例（逐个前缀验算匹配数）与三条变异，并**推翻**了本轮的「scroll 无效果」结论（见上）。
4 条【应当修】全部处置：

1. 「scroll 无效果」段与指南「实测提示」段失实 ⇒ 订正为方向语义，并保留误判过程作为教训；
2. 「`type`/`compose`/`key` 零覆盖」措辞夸大 ⇒ 改为「`type`/`key` 零覆盖；`compose` 仅有设备层判据」；
3. 「不给 `scroll` 写判据」的理由不成立 ⇒ 补 `scrollStatementMovesContent`（主证据用节点 y 位移）；
4. 判据 1 的「末尾字符丢失」盲区 ⇒ 补 `type glass_` → 1 的前缀敏感断言（该变异从「存活」变「红」）。

它另有 3 条【建议】本轮采纳 1 条（滚动判据用 `--nodes` 位移做主证据），其余 2 条登记
（`--nodes` 与 `--out` 同用 exit=2 的文档说明、`EMPTY_QUERY` 的前提注释）。
**它报告的一处本轮外既有缺陷（本轮未改，登记为下一轮候选）**：`HeadlessInputDevice` 的
`press` / `release` / `scroll` / `cancelPointer` 调 `ofPointer` 时把 `(shiftDown, controlDown)` 传进了
`(controlDown, shiftDown)` 的参数位（`moveTo` / `keyDown` 顺序正确）⇒ 带修饰键时 control / shift 互换。
## 三、目标形态

**四件套 + 一个出口：**

| 件 | 内容 | 现状 |
|---|---|---|
| 驱动 | 多帧推进 + 帧时间可控（注入而非 `System.nanoTime` 独裁） | `render` 已有；时间源需可控化 |
| 输入 | 纯代码设备模型：鼠标（移动/按键/滚轮/拖拽）、键盘（按下/释放/重复/修饰键）、文本（char/整串/IME 接管）、焦点、`CANCEL` | 事件模型齐、注入入口缺 |
| 渲染 | 真 GL 离屏（FBO）→ 像素；路由与生产同源 | 缺上下文与后端 |
| 观测 | PNG 人眼判读 + 自检信号 + 命令轨迹 + 结构化诊断 | 字体侧有、scene 侧无 |
| **出口** | **一条命令出图**（页面 × 尺寸 × 输入脚本 × 输出路径） | **无**（P1 的核心缺口） |

## 四、方案骨架

### A 渲染载体（R3）

- **A1（推荐）**：LWJGL2 原生 + natives（F7 已实测）；`Display.create(PixelFormat)` 取上下文，
  **渲染目标一律自建 FBO**（尺寸 = 目标分辨率，与窗口/Xvfb 屏幕解耦），`glReadPixels` → PNG。
  - `PixelFormat` 需含 **stencil**（`ClipStack` 走 scissor + stencil mask）与 alpha；窗口尺寸只需最小。
  - **线程约束**：GL 调用固定在创建上下文的线程。
  - 像素路径**直接复用生产 `UiRenderContext`**（F7 已证可构造、可绘），不另造第二套后端。
- **A2（修正：无 GL 时不作像素回退）**：软光栅只覆盖字形通道、不渲染 scene 命令，**像素级回退不成立**；
- **A3（不采用）**：测试域复用 lwjgl3ify / LWJGL3（F4、F8 已给反证）。
- **代表性边界**：A1 的 GL 是桌面 LWJGL2 + WGL/GLX，**不代表**真机 MC 宿主、Angelica 或 lwjgl3ify 上下文；
  它代表顶点变换后的像素位置、clip/stencil 语义、混合、shader（字体 AA、玻璃）与多分辨率布局。

### B 分辨率矩阵（R2）

- 建议档位（待裁）：`640×360` / `1280×720` / `1920×1080` / `2560×1440`（可扩 `854×480` / `960×540` / `1600×900`）。
- 内存口径（RGBA 单帧，Python 验算）：360P 0.88 MiB → 2K **14.06 MiB**；2K 相对 360P 为 **16 倍像素**；
  全档同时持有 35.4 MiB → **约束不在内存，在串行与复用**。
- 第二维：MC GUI Scale（1/2/3/4）只在宿主边界验收需要（`HostViewportScale.compose`）；默认矩阵不含。
- 与密度档位联动：`PickerDensityPreference.AUTO` 按视口求解密度档，矩阵应覆盖 AUTO 的档位切换边界
  （现仅 `PickerDensityPanelWiringTest` 断过 1920×1080 → 标准档）。
- **新定位下**：尺寸首先是**命令行参数**（P1/P2），矩阵是它的批量展开。

### C 输入设备模型（R4）

- **C1 设备模型**：`HeadlessInputDevice`（`moveTo` / `press` / `release` / `click(button)` /
  `scroll(dx,dy)` / `keyDown` / `keyUp` / `type` / `compose(text)` / `focus` / `cancel`），
  内部按帧累积 `RawInputEvent` 并产出 `SceneInputFrame`。
- **C2 时间轴**：事件时间戳可控（默认每帧 +16.67ms 单调推进），支持长按、双击时间窗、跨帧 DOWN/UP。
- **C3 脚本化（P2 的关键）**：输入序列可由外部脚本描述（如「移动到按钮 → 点击 → 出图」），
  使 agent 一次调用即可完成「交互 + 出图」。
- **C4 兼容与纪律**：保留 `SceneInteractionHarness` 的节点快捷方法作为语法糖（261 处调用点零破坏）；
  必须走生产链路 `PlatformInputSource → SceneInputFrame → SceneRuntime.route`，**不旁路 router**。
- **C5 焦点与文本**：`KeyboardTextInputSource` 的 `pushText` / `setExternalTextMode` 已提供「外部文本接管 ↔
  char 路径回落」两态，两种模式都要能驱动。

### D 观测与产物

- PNG：`build/reports/headless/<suite>/<case>-<WxH>.png`（沿用既有 `build/reports` 约定）。
- **自检信号（P1 必需）**：agent 判读前先要能区分「图有效」与「图没画出来」——至少给非空墨水率、绘制越界、
  缺字形计数、`glGetError`；否则「空白图」会被误判成代码 bug（对照 F2 的静默降级）。
- 机判（**不做字面快照**）：结构不变量（如「面板不超出视口」）。
- 命令轨迹：`RecordingRenderBackend` 保留为「路由/命令面」证据，与像素证据**不可互替**。

### E 出图入口与速度分层（P1/P2 的核心，本轮新增）

| 层 | 形态 | 预计冷启动 | 说明 |
|---|---|---|---|
| 快路径 | 一次性导出 classpath 到文件 → `java -cp @cp <入口> --page=… --size=… --out=…` | **1~3 s** | 绕开 Gradle；agent 默认路径 |
| 常驻模式（候选） | 长驻进程按请求出图 | 单请求 **≤ 1 s** | 摊掉 150 MiB 字体常驻与上下文创建（F10） |
| Gradle 路径 | `JavaExec` 任务（`gradlew qzShot -Ppage=…`） | 3~8 s | classpath 由 Gradle 托管 |
| 测试域路径 | `gradlew test --tests …` | 12~22 s | 回归用（P3） |

四者**共用同一套 headless 装配与后端**，区别只在启动壳。

**设施落点（§六-1 已裁定）**：main 域 `internal.devtools.headless`（与 `playground`/`glass` 同层），
**硬约束是不进打包产物**：

- **编译期零依赖改动**（F11 实测）：`org.lwjgl.opengl.GL11` 已在 `compileClasspath`；
- **打包隔离**：`tasks.withType<Jar>` 上 `exclude("club/heiqi/uilib/internal/devtools/headless/**")`——覆盖
  `jar` / `shadowJar` / `sourcesJar` / `apiJar`，并经 dev jar 传播到 `reobfJar`（F12）；
- **隔离门禁**：新增 `verifyHeadlessNotPackaged` 挂到 `check`，逐个打开上述产物断言不含该包（对照 `addon.late.gradle`
  的 `verifyRunClasspathIsolation` 先例）——排除是意图，门禁才是保证；
- **运行期补充 classpath**：LWJGL2 + natives 不在 runtime classpath（F11），直启壳需自带（§六-2 的解压任务产出固定目录，
  `-Djava.library.path` 指向它）。

## 五、已声明的边界（不能声称的）

1. FBO 出图**不代表**真机 MC 宿主 / Angelica / lwjgl3ify 上下文；
2. **不覆盖**原版包装类禁令（Tessellator 等）一类问题；
3. GL 输出依赖驱动实现，**不作为跨机器 / 跨驱动 / 跨字体环境的逐像素金样**；反过来，
   **同一机器 + 同一命令下逐像素可复现**（时间事实已解耦，见 F27）——这一条是「改一行→出图对拍」
   能成立的前提，别把它连同「跨环境金样」一起放弃；
4. **不经过** `LwjglInputSource` 的 poll 差分与宿主回调旁路语义——headless 注入的是帧，
   桥内部的差分/边沿丢失类缺陷不在覆盖范围内；
5. **保真分层，不可越级声称**：契约层（`RecordingRenderBackend` / FakeGl，必跑）→ 几何与语义层
   （llvmpipe 渲染 + 容差或结构断言）→ 观感层（真机截图基线，仅用户可判）。headless GL 出图属**第二层**；
6. **环境依赖**：字体引擎走 AWT 系统字体（`FontRegistry` ← `GraphicsEnvironment.getAllFonts()`），
   生产与 headless **同源**；但**环境缺字体即不可用**（F10）——容器/CI 出图需装字体（含中文字体），
   否则出图出现豆腐块或直接初始化失败，**不得当作代码缺陷**；
7. Linux 无桌面环境下需要 Xvfb（与 Qt offscreen 的实质差距，见 §一）。

## 六、岔路裁定（2026-09-17 用户裁定，已冻结）

用户口径（原文）：「1 不进生产包放哪都可以」→「只要不影响打包体积放 main 域也可以」；「其余采纳建议」。

| # | 岔路 | 裁定 |
|---|---|---|
| 1 | **设施归属** | **main 域 `internal.devtools.headless`**（与 `playground`/`glass` 同层），**且不影响打包体积**：`tasks.withType<Jar>` 排除该包 + `check` 挂 `verifyHeadlessNotPackaged` 门禁（口径见 §四 E，传播路径见 F12）。位置由用户明确为次要项，「不进生产包 / 不影响打包体积」是硬约束 |
| 2 | **natives 供给** | 采纳建议：Gradle 任务一次解压到固定目录 + 文档化路径，运行期以 `-Djava.library.path` 指过去（F11：runtime classpath 不含 natives，必须自带） |
| 3 | **快路径形态** | 采纳建议：M1 先直启 JVM（`java -cp @cp <入口> --page=… --size=… --out=…`），常驻进程留作 M4 候选（F10：150 MiB 字体常驻是常驻模式的收益来源） |
| 4 | **首批可渲染范围** | 采纳建议：M1 只做 playground 首页 + 核心控件冒烟；配置页 / chat3 / HUD 分批进 M4 |
| 5 | **分辨率档位** | 采纳建议：360P / 720P / 1080P / 2K 四档；854×480 / 960×540 / 1600×900 按需扩 |
| 6 | **软光栅去留** | 采纳建议：保留为「无 GL 回退」（A2），退出主路径；其语义测试与既有出图不受影响 |
| 7 | **CI 策略** | 采纳建议：本轮不接 CI，需要时再开独立任务（F5 的 Xvfb + Mesa 环境仍可用） |

裁定顺带修正的三条口径（施工时必须遵守）：

1. 「落 main 域 ⇒ 零依赖改动」**只在编译期成立**；运行期必须补 LWJGL2 + natives（F11）；
2. 「落 test 域天然不进包」**不成立**（F12：`shadowJar` 吃 test 输出）——隔离一律靠排除 + 门禁；
3. 门禁必须遍历 `AbstractArchiveTask` 产物，而不是硬编码任务名——构建链新增打包任务时硬编码门禁会静默失效。

## 七、建议分批

- **M0 闸门（可行性）**：**已通过**（F7，本地）。CI 侧不再是闸门（降级为可选）。
- **M1 出图入口与最小闭环（P1 核心）**：**已完成**（提交 `c7264618`，实测见 F13：459 ms，自检全绿）。
  范围：main 域 `internal.devtools.headless`——上下文 + FBO + `UiRenderContext` 复用 + PNG 落盘 + 自检信号
  + 命令行参数（页面/尺寸/输出/背景/帧数）；**目标「一条命令渲染 playground 首页出图 ≤ 3 s」已达成**。
  实际落地顺序（每步独立验证，先证「不影响打包」再往里加代码）：
  1. **打包隔离 + 门禁先行**（§六-1 硬约束）：`tasks.withType<Jar>` 排除该包 + `verifyHeadlessNotPackaged` 挂 `check`，
     用 `jar tf` 对 `jar` / `shadowJar` / `sourcesJar` / `apiJar` / `reobfJar` 逐个产物确认（先放一个哨兵类即可验证链路）；
  2. **natives 解压任务**（§六-2）：把 LWJGL2 natives 解到固定目录（建议 `build/headless/natives`），文档化路径；
  3. **headless 运行期 classpath**（F11）：一次性导出「main 输出 + LWJGL2 主 jar + natives」到 classpath 文件，供直启壳使用
     （F10：绕开 Gradle 才是「快」的关键）；
  4. **入口与最小闭环**：上下文 + FBO + PNG + 自检信号（墨水率 / 缺字形 / `glGetError`）+ 命令行参数；冷启动实测回填 §八-4。
- **M2 渲染地基**：**已完成**（F14 / F15 / F16）——字体 GL 尾端上屏有据（命令面 × 像素面）；出图完整性判据落地；
  帧稳定语义（settle）与文本探针（`text-probe`）堵住「固定帧数出图静默残缺」；与软光栅出图几何对拍通过
  （宽比 1.029 / 高比 1.059），并由此把 headless 出图接进了既有验收测试体系。
- **M3 输入设备模型**：**已完成**（F17）——C1 设备模型 / C2 时间轴 / C3 脚本化 / C4 走生产链路不旁路 / C5 文本两态；
  端到端以「一条命令完成点击切页并出图」收口，契约测试 5 项钉住时序。
- **M4 铺开**：**已完成**（F18 / F19）——启动器（冷启动 1.62 s）、分辨率矩阵 4 档（2.42 s、4/4 ok）、
  GL 上下文进程级复用修复、消费者域扩展（9 个 playground 页面批量 9/9 ok、4.56 s）。
  常驻进程形态经实测后判定**不需要**：直启冷启动 1.62 s 已满足 P1，常驻只省 1 s 却引入进程管理成本。
  360P 外壳溢出已定根因并转业务待办（不属设施）。
- **M5 收口**：**已完成**（F20）——失败语义与退出码分流、软光栅回退语义修正（不作像素回退）、成本基线表、
  使用文档落点（`docs/使用文档/headless出图指南.md`）。CI 接线按 §六-7 裁定不做。

## 八、技术未知项状态

| # | 未知项 | 状态 |
|---|---|---|
| 1 | LWJGL2 `Display.create()` 在 JDK 17 下能否初始化 | **已实测通过**（F7） |
| 2 | `UiRenderContext` 在无 MC 环境下的可构造性 | **已实测通过**（F7） |
| 3 | 2K 一帧的 GL 侧耗时 | **已实测**：534 ms（本机 GPU） |
| 4 | 快路径（直启 JVM）的真实冷启动耗时 | **已实测**：启动器直启含 JVM **1.96 s** 出图（内部 capture 459~615 ms）；Gradle 路径 12~22 s（F18/F20） |
| 5 | 完整 scene（含玻璃/裁剪/字体）单帧耗时 | **已实测**：生产链路端到端 1280×720 每张 459~615 ms（含字体初始化与 settle 多帧，F14/F18） |
| 6 | 无字体环境下的失败语义与自检表现 | **部分实测**：能力探测已报 `fonts=0` 并在自检提示；零 fontconfig 容器的实际崩溃形态本机无法复现（已知 `ERROR-20260904`） |
| 7 | 非 Windows 平台（Linux/llvmpipe、macOS） | **未实测**（本机为 Windows；Linux 需 Xvfb + Mesa，路径已由 F8 调研确认，待外部环境验证） |
| 8 | CI 侧（Xvfb + llvmpipe） | **未实测**，已降级为可选 |
