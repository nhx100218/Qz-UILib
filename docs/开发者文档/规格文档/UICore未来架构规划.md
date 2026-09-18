# UICore 未来架构规划

**状态：在办（2026-09-18 立项）** — 本文件声明目标、判据与必经状态，不构成现行实现要求。实施按 §六 批次推进，每批落地后就地记录实际结果。局部行为规范以各规格文档为准，设计意图以最近的代码注释为准，本文件不复制二者。

## 零、这份文件防的是什么

「独立成 UICore」「脱离 GTNH 构建流」「解耦 lwjgl3ify」「做桌面宿主」是四件不同层级的事：**只有第一件是目标，其余三件是手段**。手段最容易冒充目的——拆完 Gradle 模块、摘掉 convention 插件、把 lwjgl3ify 挪到隔壁之后，完全可能得到一个**编译通过但换不了宿主**的库，因为真正的门槛是宿主契约是否完备，而契约完备性与构建结构无关。

故本文件先写死目标与判据，再让手段去对齐判据。**凡是无法用 §二 判据判定成败的工作，不进批次。**

## 一、目标

### 1.1 终局状态

> **UICore 是一个宿主可替换的 UI 引擎**：同一份 UI 代码（建树 → 布局 → 生成不可变绘制计划）能在不同宿主上运行，宿主差异全部收敛在一组已声明的端口背后。

三个可判定的终局事实：

1. UICore 在**只有 JDK** 的编译类路径下编译通过。
2. 新增一个宿主**只实现端口、不改 UICore 一行代码**。
3. UICore 的回归验证**不启动游戏、不依赖 GTNH Maven、不需要真实 GL 上下文**。

### 1.2 为什么是这个目标

| 收益 | 可验证判据 |
|---|---|
| 可验证性前移 | 不启动游戏即可出像素、跑回归；回归防线从真机前移到 CI |
| 迭代速度 | 改 UICore 不触发 RFG 重编译 MC 与 mixin AP |
| MC 版本解绑 | MC 侧升级时 UICore 零改动 |
| 受众扩展 | 非 MC 的 Java 宿主复用同一份 UI 代码 |

### 1.3 非目标

- **不是**通用 GUI 应用框架：窗口管理、系统菜单、无障碍、安装包不在范围。
- **不是**纯 Java 光栅化：软件后端继续只服务验收（现定位见 `font/render/software`），不作生产渲染路径。
- **不是**重写 scene 栈：`ui/scene` 191 文件是资产，目标是给它一个显式边界，不是换掉它。
- **不是**第三方插件生态的扩展点设计。

## 二、判据

三层，各自可独立验证：

- **J1 结构判据**：UC 模块的编译类路径只含 JDK（含 Java2D）。违反者**不能编译**，而不是"扫描后发现"。
- **J2 契约判据**：宿主可观测的每一项差异都有一个具名端口；端口总数有限且已列举；端口在 UC 侧声明、宿主侧实现。
- **J3 验收判据**：新宿主接入 = 实现端口清单 + 零内核改动。

> **J3 使「桌面应用」从产品目标降级为验收手段**：它是用来证伪"契约已完备"的。若接入桌面宿主必须改 UC 内核，结论是 J2 未达成，**而不是**"桌面适配工作量大"。

## 三、必经状态（不变量）

1. **依赖方向单向，且由结构强制**——classpath 隔离优先于源码扫描守卫。现有 `ScenePackageIsolationTest` / `MarkdownLayerGuardTest`（含反空跑正对照）保留，但它们从"唯一防线"降级为"第二道网"。
2. **端口先于实现**：桌面宿主暴露的每个缺口，先判定"缺契约"还是"缺实现"；缺契约补端口，缺实现写实现。**不允许在宿主侧特判绕过**。
3. **每个端口有 ≥2 个真实实现，或明确标注「待第二实现」并写明触发条件**。
4. **端口语义不含宿主词汇**：方法名与参数不得引用具体平台的回调形状或事件模型（§五 G1 即违反此条）。
5. **接入点唯一**：端口注入走构造依赖，不引入服务定位器、静态 getter 或线程上下文查找。

## 四、目标形态

### 4.1 三个模块（按契约边界切，不按包路径切）

| 模块 | 职责 | 编译类路径 |
|---|---|---|
| `uicore` | 场景树、布局、响应式、文本度量与排版、绘制计划、环境端口 | 仅 JDK + Java2D |
| `uicore-host` | 渲染后端、输入采集、剪贴板/光标、站点窗口装配 | uicore + **显式声明的 LWJGL2** |
| `uilib-mc` | MC 适配、mixin、聊天业务、传输实现、mod 入口 | uicore + host + MC/Forge |

`net/api`+`codec`+`core`+`store`+`client`（53 文件，零 MC 依赖）是**同一模式的成功先例**——传输抽象已经证明这条边界在本仓可行。

### 4.2 宿主端口清单（现状）

"实现数"指 `src/main` 内的真实实现；状态列按 §三 不变量 3 判定。`SceneTextMeasurer` 是内核内部端口（度量真源在 UC 字体层），不随宿主变化，未列入本表。

| 端口 | 声明位置 | 实现数 | 状态 |
|---|---|---|---|
| `UiEnvironment` | `ui/env` | 2（Process / Headless） | ✅ **范本**，见 §4.3 |
| `PlatformInputSource` | `ui/scene/input` | 2（Lwjgl / Headless） | ✅ |
| `KeyboardTextInputSource` | `ui/scene/input` | 2（同上） | ✅ |
| `GlApi` | `font/page` | 1 生产（Lwjgl）+ 1 验收（Software） | ✅ 双后端已实证同源像素 |
| `UiSurface` | `ui/scene` | 3 | ⚠ 语义绑宿主回调形状（G1） |
| `UiRenderBackend` | `ui/render` | 2（均为 GL） | ⚠ 待非 GL 实现 |
| `ClipboardBackend` | `ui/scene/input` | 1（Lwjgl） | ⚠ 待第二实现 |
| `CursorBackend` | `ui/scene/input` | 1（Lwjgl） | ⚠ 待第二实现 |
| `UiCursorHost` | `ui/host` | 1（SystemUiCursorHost） | ⚠ 待第二实现 |
| `PlatformStateReader` | `ui/scene/host/lwjgl` | 1（Lwjgl） | ⚠ 端口住在宿主包内（G4） |
| `PointerEventInputSource` | `ui/scene/input` | 1（LwjglInputSource） | ⚠ 待第二实现 |
| `SceneImageSource` | `ui/scene/image` | 1（`HostImageSource`，依赖 `ItemStack`） | ⚠ 实现越界（G4） |
| `ThreadOracle` | `config/ui/field` | 1（`MinecraftMainThreadOracle`） | ⚠ MC 专有实现（G4） |

### 4.3 端口的标准形态

`UiEnvironment` 是已落地的正确范本，后续端口照此办理：

- **域化 + 缺席等价**：域访问器为 `default`，返回该域缺席实现 → 新增域不破坏既有实现类；
- **缺席语义逐位定义**：`EMPTY` 与"未安装态"逐位等价，缺席值写在各域 javadoc；
- **值读纪律**：O(1)、零分配、帧内直读，禁止缓存进构造期字段或 `Computed` 快照；
- **注入点唯一**：构造依赖 `SceneRuntime(SceneTextMeasurer, UiEnvironment)`，无静态查找；
- **方向自证**：上行（宿主→框架）与下行（runtime→节点，如 `SceneFontEnvironment`）不得互相顶替。

## 五、差距清单（按「离目标多远」排序，不按改动大小）

### G1 `UiSurface` 语义未解耦 —— 最关键

`UiSurface` 位于 `ui/scene` 顶层，编译期零宿主 import，但**方法集是 MC 回调的形状**：

- `onKeyTyped(char, int)`——javadoc 自述"透传 MC keyTyped 事件"；
- `pushText(String)`——自述"推入 lwjgl3ify 文本旁路事件"；
- `setExternalTextMode(boolean)`——自述"设置 lwjgl3ify 外部文本模式"；
- `onPointerButton(...)`——自述"透传 MC mouseClicked/mouseMovedOrUp 回调"。

**这是"编译干净但语义未解耦"的典型**：内核接口要求宿主把 MC 的回调形状喂进来，桌面宿主必须假装自己有 `keyTyped` 与 `mouseMovedOrUp`。违反 §三 不变量 4。

正确形态：内核定义**语义事件**（指针按下/移动/抬起、字符输入、文本提交、模式切换），宿主负责把平台回调翻译成语义事件。`SceneTextBridgeLifecycle` 已把注册/幂等/close 语义抽出（`Registration`/`Mode` 窄接口），可作为事件通道端口化的起点。

**这是公共 API 变更，需裁定（§八 R1）。**

### G2 单实现端口

上表中单实现的端口共 **7 个**（`ClipboardBackend`、`CursorBackend`、`UiCursorHost`、`PointerEventInputSource`、`PlatformStateReader`、`SceneImageSource`、`ThreadOracle`），且均绑定具体平台。判定顺序：先问「桌面宿主能否原样实现」，能则属**缺实现**，不能则属**缺契约**（回到 G1/G3）。

### G3 缺契约

桌面宿主需要、但内核**尚无对应端口**的能力：

- **窗口创建与生命周期**：`SceneHostWindow` 是装配类（class），宿主如何提供/销毁一个绘制面尚无端口；
- **帧驱动方向**：现为 `UiSurface.render(...)`「宿主调我」，缺少"内核声明下一帧何时需要"的反向契约；
- **缩放/DPI 变更通知**：GUI Scale 现只在 host 边界成对转换，但无变更信号端口（违反"静态量应有变更信号"）；
- **文本事件通道**：见 G1；
- **图像/资源解码**：`SceneImageSource` 是绘制值类型端口，资源加载侧无端口。

**G3 是「桌面应用不轻松」的真正原因**——不是渲染后端要重写，是这些契约不存在。

### G4 边界违规（编译期可查）

- `PlatformStateReader` 声明在 `ui/scene/host/lwjgl/`——端口住在宿主实现包内；
- `SceneImageSource` 的唯一实现 `HostImageSource` 依赖 `net.minecraft.item.ItemStack`；
- `ThreadOracle` 声明在 config 层，唯一实现是 `MinecraftMainThreadOracle`。

### G5 符号来源不确定

`org.lwjgl.*` 的**编译期符号来自 lwjgl3ify shim**，运行期是真 LWJGL2（`GlOffscreenSurface` 注释自证："编译类路径上的 `DisplayMode` 来自 lwjgl3ify shim（无 `(int,int)` 构造）"）。此不一致目前仅靠注释传递，违反"约束要在引用处自证"。同类：headless 直启 classpath 由 `compileClasspath` 按文件名过滤提取（`build.gradle.kts`），属隐式依赖且**未写明删除条件**。

### G6 构建结构（服务于上述，非目标本身）

单源集混装 UC 内核 / 宿主实现 / MC 适配 / 开发工具；`gtnhconvention` 因此作用于整个代码库；CI 挂在 GTNH reusable workflow 上；`enableModernJavaSyntax = jabel` 为 1.7.10 产物所需而对 UC 无必要。

## 六、批次

每批以 §二 判据判定成败；未达判据不得进入下一批。

| 批次 | 内容 | 判据 | 验证 | 回滚 |
|---|---|---|---|---|
| **B0** | 加 `uicore` sourceSet，compileClasspath 只含 JDK + Java2D + snakeyaml，先纳入 `ui/scene` `font` `net/{api,codec,core,store}` | 编译通过（J1） | `compileUicoreJava`；**编译错误清单即 G3/G4 的真实补集** | 删除 sourceSet，零产物影响 |
| **B1** | 按 B0 清单补端口/修边界；`UiSurface` 语义事件化（需 R1） | J2 部分达成 | 前端完成端口清单复核 | 端口为增量，可逐个回退 |
| **B2** | 符号来源确定化：显式 LWJGL2 依赖；headless classpath 改显式 configuration 并写明删除条件 | J1 在 host 模块成立 | 编译 + headless 出图对拍 | 保留旧路径一版 |
| **B3** | 拆模块或独立仓（需 R2）；MC 侧消费方式定案（需 R3） | J1 全量成立 | 两模块独立 build | 分支保留 |
| **B4** | 桌面宿主：开真窗口跑 1000 帧，**记录每一次"必须改内核"的位置** | **J3** | 帧计数 + 改动清单（清单为空才是通过） | 纯增量 |

**B0 的产出就是答案**：编译错误清单 = 未被发现的隐藏宿主依赖。若一处错误都没有，说明边界早已干净，直接进 B3——**零错误同样是有效结论**。

## 七、二级目标清单（防走偏）

以下均为手段。判定基准是：**做完它，§二 的哪条判据从"未达成"变成"达成"？** 答不上来就不做。

| 二级目标 | 为何不是目标 | 走偏形态 |
|---|---|---|
| 拆 Gradle 模块 | 拆了不等于契约完备 | 编译通过但桌面跑不起来 |
| 去掉 `gtnhconvention` | 只让 UC 能脱离构建流 | 摘掉了但 UC 仍依赖 MC 类型 |
| 解耦 lwjgl3ify | **代码已是零编译期依赖**（23 个文件全是反射字符串 + 降级） | 花大力气重构一个已隔离的东西 |
| 让构建变快 | 速度是 J1 的副产品 | 优化了构建但边界仍错 |
| 纯 Java 光栅化 | 只是可验证性的手段 | 为"纯 Java"重写渲染管线 |
| 支持更多 MC 版本 | 不在本目标内 | 提前背负兼容矩阵 |

**特别提醒**：任何以"先拆模块再补契约"为序的排期都属走偏——契约是目标，模块是它的显现方式。

## 八、需用户裁定项

- **R1｜`UiSurface` 语义事件化**：改公共 API，影响 MC 侧全部宿主调用点。是否本轮做、是否保留兼容过渡层。
- **R2｜拆模块 / 拆仓**：同仓多模块改动小，但 `gtnhconvention` 的多模块支持未经验证；独立仓更彻底但需新建发布通道。属核心架构边界 + 发布策略。
- **R3｜MC 侧的消费方式**：`uicore` 打入 mod jar 还是作为已发布依赖（UC 不需 `rfg.deobf`，普通依赖即可）。
- **R4｜版本线**：UICore 独立版本号，还是与 `qz_uilib` 共用（影响 `acceptableRemoteVersions` 一类兼容声明口径）。
- **R5｜`separateMixinSourceSet`**：填上可加速编译并让 mixin 与主源集编译期分离，但会移动 mixin 包结构。

## 附：本规划的取证基线

结论基于 2026-09-18 对 `src/main/java`（672 文件 / 149481 行）与 `src/test/java` 的静态扫描：MC 硬依赖 72 文件（10.7%，集中于 mod 装配、mixin、聊天业务、传输实现）；直接调 GL 29 文件 / 682 处；`ui/scene` 191 文件零 MC 硬依赖（仅 2 个反射类在 `host/lwjgl`）。基线随代码变化，重新取证比引用旧数字可靠。
