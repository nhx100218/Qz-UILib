# headless 出图指南（不开游戏渲染 UI）

**用途**：agent 与开发者不开游戏、不启窗口，一条命令把 UI 页面渲染成 PNG，并同时拿到「画了什么」与「画出来没有」的证据。
形态对标 Qt `-platform offscreen`：**同一份 UI 代码**、同一套生产渲染链路（`UiRenderContext` / `SceneFramePipeline` /
`SceneHostAssembly`），只是换了个平台宿主。

## 快速开始

```bat
gradlew exportHeadlessClasspath
build\headless\qz-shot.bat --page=playground --size=1280x720 --out=out\shot.png
```

`exportHeadlessClasspath` 生成两个启动器（内含 classpath 参数文件与 natives 路径）：

| 启动器 | 类路径 | 用途 |
|---|---|---|
| `qz-shot.bat` / `qz-shot.sh` | 最小集（107 项） | **agent 默认**：快、不含 Minecraft 静态初始化风险 |
| `qz-shot-full.bat` / `qz-shot-full.sh` | 完整开发类路径（137 项，含重编译 Minecraft 类） | 页面渲染一旦触及 MC 类型（如 `IChatComponent`）时使用 |

**冷启动约 1.6~2.0 s**；`gradlew` 跑单张图要 12~22 s（配置与编译开销），批量出图请一律用启动器。

## 不弹窗（离屏语义）

出图期间屏幕上**不会出现任何窗口**，也不抢焦点——可以一边出图一边继续用电脑。

GL 上下文需要窗口句柄，而 LWJGL2 的 `Display.create()` 会创建**真实可见窗口**（旧行为，标题 `Qz-UILib headless`，实测会挡住屏幕）。现在把 Display 挂到一个**从不显示**的 AWT `Canvas` 上：`pack()` 只为拿到 native peer，顶层容器始终不可见；渲染目标本来就是自建 FBO，那个窗口对出图没有任何贡献。

实测（出图期间每 50 ms 枚举本进程顶层窗口）：**可见窗口峰值 0 个**；进程内确有 AWT 隐藏容器（`SunAwtFrame`，visible=false）与驱动自建的离屏窗口（`NVOGLDC invisible` / `__wglDummyWindowFodder`，均 visible=false）。

两条约束：

- **不要设 `-Djava.awt.headless=true`**：离屏窗口句柄由 AWT 提供，headless 模式下会直接报错并说明原因；
- 出图结束会显式释放 GL 上下文与隐藏容器（`GlOffscreenSurface.shutdownContext`）。少了这一步，`System.exit` 会停在 AWT 的退出钩子里——表现为「图已经写出来了，进程却不退出」。

## 参数

| 参数 | 说明 |
|---|---|
| `--page=A\|B\|…` | 单页面；`playground` = 测试场地，`text-probe` = 单行文本探针，`chat` = 聊天 3.0 内容树，`chat-input` = 聊天**输入屏**形态（生产 `ChatInputSurface`，见下节），`hud` = 同一内容树走 HUD 宿主装配，`config` = 生产配置页，`glass` = 磨玻璃实验室（backdrop-filter 观感验收），`picker` = 搜索选择器（控件级，见下节） |
| `--pages=A,B,…` | **多页面矩阵**（与尺寸 / 外观 / 字号轴同构）；与 `--page` 同时给出时本参数胜 |
| `--page-index=N` / `--page-indexes=0,1,…` | `playground` 子页下标（0 总览 / 1 单行文本 / 2 多行文本 / 3 浮层 / 4 响应式 / 5 富文本 / 6 控制字符 / 7 LaTeX / 8 Markdown）；`hud` 页当作**锚点**（0 左上 / 1 右上 / 2 左下 / 3 右下），`config` 页当作 **section 下标**，`picker` 页当作**演示状态**（0 全部 / 1 过滤 / 2 空态），`chat-input` 页**忽略**该参数，`chat` / `text-probe` / `glass` 忽略 |
| `--size=WxH` / `--sizes=WxH,…` | 单档 / 分辨率矩阵（360P~2K 任意尺寸，渲染到自建 FBO，与窗口无关） |
| `--out=path` | 输出 PNG；矩阵出图时按轴追加后缀：`-pg<页面名>`（多页面时）/ `-p<下标>` / `-th<外观档>` / `-bq<玻璃档>` / `-fs<百分比>` / `-WxH`。各段在**整体为批量**时按「该轴是否给出 / 是否偏离缺省」进入；**尺寸段恒进**（同一页面不同尺寸没有「缺省尺寸」可言），字号段只在非 100 时进 |
| `--actions="…"` / `--script=file` | 输入脚本（见下） |
| `--text=…` | `text-probe` 的文本；`chat` 的消息串（语法见「聊天页」） |
| `--bg=RRGGBB\|transparent` | 宿主背景，默认不透明深色；透明底请显式指定 |
| `--frames=N` / `--settle=N` / `--max-frames=N` | 帧计划：最少帧数 / 稳定判据（连续 N 帧像素一致）/ 硬上限 |
| `--clock=epochMillis` | 虚拟墙钟基准（默认 `2024-01-01T00:00:00Z`）：**最后一条**消息的到达时刻 + 帧时钟起点，见「出图确定性」 |
| `--theme=NAME` / `--themes=NAME,…` | 外观档（`liquid-glass-dark` / `liquid-glass-light` / `solid-dark`）；不给 = 各页面用自己的默认外观，见「环境矩阵」 |
| `--font-scale=P` / `--font-scales=P,…` | 用户级字号缩放百分比（100 = 不缩放，域 100~200）：单档 / 字号矩阵，见「环境矩阵」 |
| `--backdrop-quality=NAME` / `--backdrop-qualities=NAME,…` | 玻璃质量档：`full`（13 抽头）/ `eco`（9 抽头）/ `solid`（不装滤镜）；不给 = 维持进程当前档位（默认 `full`）。档位决定 shader 卷积核与产物，见「玻璃质量档」 |
| `--debug` | 打开诊断采样：摘要多一行 `perf:`（帧内阶段耗时与计数）。**不改变绘制**，像素与关闭态逐位相同 |
| `--share-context` | 批量时同进程复用 GL/字体上下文（快，但产物带 atlas 历史依赖，见「出图确定性」）。默认逐档独立进程 |
| `--probe` | 只打印能力（GL 版本、stencil、字体数量）不出图 |
| `--nodes[=all]` | **目标寻址**：打印节点事实表（默认只列有可见尺寸的可命中节点；`=all` 打印完整树），先推进一帧拿布局，**不产出 PNG**；多档轴逐档报告 |
| `--find=TEXT` | 按可见文本找可命中节点（大小写不敏感子串），给出地址与中心点；无命中 exit 4 |
| `--center=PATH` | 解节点地址取中心点（如 `--center=r2/1/0/8`） |

页面、外观、玻璃档、字号、尺寸五个维度可同时给，按笛卡尔积出图（产物命名规则见「环境矩阵」）。

多页面一次出图：

```bat
build\headless\qz-shot.bat --pages=playground,chat,hud --size=1280x720 --out=out\all.png
:: 产出 out\all-pgplayground-1280x720.png / -pgchat-… / -pghud-…
```

**命令面契约（有门禁钉住，改动会红）**：批量轴的产物后缀只收「**偏离缺省的维度** + 尺寸」——
尺寸段恒进（同一页面不同尺寸没有「缺省尺寸」可言），字号段只在**非 100** 时进（`100 = 不缩放` 是缺省水位），
页面段只在多页面时进，下标 / 外观 / 玻璃档在给出时进；**单档不加任何后缀**（既有命令的产物路径逐字不变）。
`--frames=N` 是**最少帧数**（实际帧数 ≥ N，`--max-frames` 是读数分母）；`--frames` 大于 `--max-frames`
是**参数错误**（exit 2）。`--script=file` 与 `--actions` 同源：同一段脚本（支持 `#` 注释与 `;` 分隔）
两种给法**同摘要**（比的是产物 sha256 前 8 字节），空文件等价于不给脚本；文件读不到是**参数错误**
（exit 2，消息带路径），不是设施失败。

## 目标寻址（不数像素）

agent 出图后常要「点某个按钮再出图」。此前只能硬编码坐标（`move 315 88`），页面稍改即失效。
寻址把「画布上有什么、在哪、能不能点」变成可读事实：

```bat
:: 1) 看有哪些可点的东西（不含 PNG 产物）
build\headless\qz-shot-full.bat --page=playground --size=1280x720 --nodes
:: 2) 按文案找目标，拿到地址与中心点
build\headless\qz-shot-full.bat --page=playground --size=1280x720 --find=Markdown
::   [headless] find "Markdown": 1 个命中（其中可点目标 1 个）
::   [headless]   r0/1/0/8 SceneNode "Markdown 渲染" @879,64 148x40 fs=16 [target] center=953,84
:: 3) 用中心点点击（或用 --center=r0/1/0/8 复核地址）
build\headless\qz-shot-full.bat --page=playground --size=1280x720 ^
  --actions="move 953 84; frame; click; wait 6" --out=out\md.png
```

**地址语义**：`r<树根序号>[/<子下标>]…`。地址空间由**两个语义权威**拼成，不做几何筛选：

- `r0`… 是**装配树根**的装配顺序（`SceneHostAssembly.attachTree` 声明的那些树）；
- 之后是**活跃浮层根**（当前浮层栈顺序，即 z-order）：浮层开着就有、关掉就没了。

`mount` / `show` 挂上去的**内容根不占根号** —— 它们在 `r0/…` 路径里本来就已可达，另占一个号只会让同一控件
有两个地址、且随页重建漂移。

地址在**同一环境档内稳定**：跨进程、跨分辨率、跨外观档、跨滚动、**跨浮层开关**都不变；**改字号倍率时坐标变而路径不变**。
（为什么不承诺「永远不变」：更早的两版判据都出过反例。第一版拿「环境根登记顺序」当地址，页重建会把内容根摘除后
重新追加，于是改字号 / 切页 / 点一次导航都会让 `r1` 与 `r2` 互换；第二版改为「只取无父的根」，但浮层根本来就无父、
而卸载后的内容根也无父 —— 判据分不开这两者，同一个对话框的地址随后在 `r1`/`r2`/`r3` 之间漂移，且根数随开关无上限增长。
现版本不再用任何几何判据，装配身份与浮层身份各自由其声明点供给，两个反例都已实测转正。）
因此**跨字号脚本请按路径用 `--center=` 重新取坐标，不要把某次运行的中心点写死**。

**名称来自子树**（无障碍树口径）：可交互节点通常自己不持文本，文案挂在子 label 上，故名称取「自身文本为空时
向下第一个非空文本」。输出按目标质量标注 —— **标注取自真实命中**（点该行中心点时事件实际打到谁，
与真实输入派发同一处实现，含浮层优先级与滚动裁剪），不是几何推断：

| 标注 | 含义 | 该不该点 |
|---|---|---|
| `[target]` | 有可见尺寸、可命中，且**中心点命中它自己** | **是**，点下去事件就到它 |
| `[container]` | 中心点命中的是自己的后代（事件仍冒泡经过它） | 看用途：适合整块区域，不适合「精确点某个控件」 |
| `[blocked] hit=…` | 可命中、有尺寸，但中心被别的东西接走了 | 否，除非先按 `hit=` 处置。`hit=NONE` = 该坐标没有节点接住（多半被滚动容器裁掉）；`hit=<地址>` = 被该节点接走（浮层遮罩 / 更深的兄弟），该地址可直接喂给 `--center=` |
| `[non-interactive]` | 不参与命中（布局容器、装饰叶），仅 `--nodes=all` 会列出 | 否 |

举个真实例子：字号 100% 时「Markdown 渲染」是 `[target]`（中心 `953,84`，点击真的切页）；字号 125% 时同一地址
变成 `[blocked] hit=r0`（中心 `1078,95`，在该点点击**画面无任何变化**）—— 导航条是定宽容器，放大后按钮被挤出其外。
这是旧判据（「没有同为可命中的后代」）看不见的一类，它会把点不到的目标排在候选最前面。

`--find` 的结果按「`[target]` 优先」排序（同档内保持 DFS 顺序），并给出「其中可点目标 N 个」；
一个可点目标都没有时会显式提示各类成因。

**三条使用纪律**：

- **必须先推进过一帧**：坐标来自布局结果（`cachedLayout`），未布局时投影如实报 0。`--nodes` / `--find` / `--center`
  内部会自动推进，进程内自建的寻址需自己先 render；
- **坐标就是命中口径**：绝对坐标走 `SceneGeometry.absoluteBox`（跨层换算权威单点，含祖先滚动偏移注入，与命中测试同源）
  —— 投影里报出的中心点，就是点击会命中的位置；
- **多档轴会逐档报告**：`--sizes=A,B --find=X` 会对每一档各出一段（带 `--- playground@640x360 ---` 分隔行），
  与出图矩阵同语义（早期版本会静默只处理第一档）；某一档设施失败（如地址越界）会**立即返回**，后续档不再报告。
- **查询路径会先把输入脚本跑完再投影**：`--nodes` / `--find` / `--center` 至少推进 `--frames` 帧，
  此后只要脚本还有待办（含 `wait` 尚未走完的帧边界）就继续推进，上限 `--max-frames`。
  所以「先点开对话框再看 `--nodes`」不必手算帧数；脚本在 `--max-frames` 内跑不完会**直接报错**，
  不会给你一份点击之前的树（早期版本会，且输出看起来完全正常 —— 静默空测）。
  查询路径同样消费 GL 错误：坏帧即失败，不打印看似正常的事实表。

实测（`--page=playground --size=1280x720`）：寻址得 `r0/1/0/8` 中心 `953,84` → 按该坐标点击后出图，
命令面与 `--page-index=8` 直接切页**逐项相同**（`commands=101 segments=62 bounds=1,0..1279,751 frames=22`），
像素差 15942/921600（1.7%，集中在文本反锯齿区，来自 atlas 历史而非寻址路径）。

**坐标 vs 可点性是两个事实，各报各的**：`@x,y wxh` 一律是**画布坐标下的未裁剪绝对盒**（布局事实，便于排查
「它为什么不在画面上」）；「点得中吗」由 `[target]`/`[blocked]` 标注回答（命中事实）。二者会不一致，而那正是
有用信息：`@234,709 49x32 [blocked] hit=NONE` 读作「它在布局里占这块位置，但滚出视口了」。
不把坐标直接改成裁剪后的可见盒，是因为那会让「在树里」与「在画面上」两个问题都答不好 —— 排查布局问题时
你需要的恰恰是未裁剪的盒。零尺寸节点在交互筛选下被剔除。

**浮层的坐标已换算到画布**：浮层根布局在自己的坐标空间里（锚点 + 相对倍率），局部 `(0,0)` 不在画布原点。
投影与 `--center=` 都报换算后的画布坐标，可直接喂给 `move x y`；换算只有一处实现
（`SceneInputRouter.__toCanvasBox`，与命中测试同源）。实测：右键菜单锚在 `634,488` 时，其首项报 `@638,493`
（局部 `4,4` + 锚点），点该中心能真正触发该项动作。

## 输入脚本

每条语句换行或 `;` 分隔，`#` 起注释：

```
move 315 88        # 移动到逻辑坐标
moveby 10 -5       # 相对移动
down / up [BUTTON] # 按下 / 抬起（默认 LEFT）
click              # 点击（按下与抬起自动跨帧，更贴近真实输入）
dblclick           # 双击
scroll 3           # 滚轮（纵） / scroll 2 3（横纵）
key ENTER          # 按键（跨帧） / keydown / keyup
type abc           # 逐字符输入（char 路径）
compose 中文输入    # 整串提交（外部文本接管 / IME 语义）
cancel             # 指针取消（失焦）
frame / wait 4     # 帧边界 / 在**此位置**空推进 4 帧（后续语句顺延到 4 帧之后）
```

示例：点开「单行文本」页再出图

```bat
build\headless\qz-shot.bat --page=playground --actions="move 315 88; frame; click; wait 4" --out=out\nav.png
```

**实测（playground 首页点导航「Markdown 渲染」）**：`--find=Markdown` 报
`r0/1/0/8 SceneNode "Markdown 渲染" @879,64 148x40 fs=16 [target] center=953,84`，`--center=r0/1/0/8`
解出同一个 `953,84`；用该坐标跑 `--actions="move 953 84; frame; click; wait 8"` 后读数从基线
`segments=0` 变为 `segments=62`（真的切到 Markdown 页），产物 827799 B → 747778 B。
越界地址（真实父路径 + 越界下标，如 `r0/1/0/99`）按契约以**退出码 3** 报 `地址越界`，不会静默回落到
某个存在的节点。这条链路有门禁守着：`HeadlessPageLinkageTest.actionScriptDrivesAClickThatChangesTheShot`
（脚本真的派发、点击真的改变画面）与 `findAddressResolvesToTheSameCenterAndOutOfRangeFails`
（`--find` 的地址与 `--center` 解出的中心点必须逐字一致）。


**查询框吃输入脚本**（F45）：点一下查询框再键入即可驱动过滤，与 `--page-index=1` 的演示态同源
（装配层订阅查询变更重算候选）。实测（最小集，配合 `--nodes` 读 `Search results (N)`）：

| 脚本 | 结果数 |
|---|---|
| 无 | 24 |
| `--actions="move 446 138; frame; click; frame; type stone; wait 12"` | 5 |
| `compose stone` | 5 |
| `type zzzz` | 0 |
| `type stone` + `key BACKSPACE`×5 | 24 |

门禁：`HeadlessPageLinkageTest.pickerQueryInputFiltersResults` 与 `keyStatementEditsTheQuery`。

**滚轮方向**：`scroll` 的**负 wheelDelta 才是「内容向下滚」**（内容上移），config 页实测 `scroll -5`
让内容区节点 y 位移 5px（出图 80361 → 80802 B）；`scroll 10`（内容已在顶部）不产生位移 —— 别把
「正向无效」当成「语句无效」（规划 F45 记录了这条被独立审核推翻的误判）。


**修饰键**：`keydown` **按住**、`keyup` **释放**；按住期间，后续**指针事件**（`move` / `down` / `up` /
`scroll` / `cancel`）与键盘事件都会带上该修饰位。实测：按住 `CONTROL_LEFT` 后这几类指针事件都是
「带 Ctrl、不带 Shift / Alt / Meta」；按住 `ALT_LEFT` 时是「带 Alt、不带 Meta」。门禁
`HeadlessInputDeviceTest.pointerModifiersFollowHeldKeysWithoutSwapping` 钉住它（补判据前，四条指针路径的
Ctrl/Shift 参数位是互换的：脚本里按 Ctrl 会变成按 Shift，依赖修饰键的控件静默走错分支；规划 F46）。


端到端也有判据：`HeadlessPageLinkageTest.shiftClickExtendsSelectionAndCtrlClickDoesNot` —— 在 playground
单行文本页上，`Shift+点击` 必须与`拖拽到同一点`逐字节相同、`Ctrl+点击` 必须与`无修饰的两次点击`
逐字节相同（三条断言，含反空跑自检）。它守的是设备层判据看不到的那层：**事件载荷对了、控件不读修饰位**
（规划 F48 有变异证据）。注意它依赖 F47 的帧时钟虚拟化 —— 动画相位漂移时逐字节对拍不成立。


**双击选词 / 三击选整行 / `cancel` 终止拖选**（F49）：`dblclick` 语句此前在设备层与页面级都零覆盖；
`cancel` 语句在设备层只在修饰键用例里顺带钉过载荷，文本控件的 `POINTER_CANCEL` 分支在控件层此前
零覆盖。控件层的 `clickCount == 2 / >= 3` 分支**已有**单点单元判据（`SceneTextInputTest` /
`SceneTextAreaTest`），本用例补的是「真实页面栈 + 真实指针合成链路 + 像素产物」这一层。
实测（1280x720、fs=16、最小集；单行页 `--page-index=1` 与多行页 `--page-index=2` 同型，y 恒 180）：

| 脚本 | 单行页 | 多行页 |
|---|---|---|
| `dblclick` 落同词内两点（250 / 270） | 产物**逐字节相同**（`Hello`） | 产物逐字节相同（`第一行`） |
| `dblclick` 落另一个词（单行 300 / 多行 310） | 与前者不同（`Qz`） | 与前者不同（`欢迎使用`） |
| `dblclick` 落词间分隔符（单行 280 / 多行 290） | **等于同点单击**（折叠为插入符，不误选相邻词） | 同左 |
| 三击（三次跨帧 `down`/`up`） | 等于 `Ctrl+A` 全选 | 同一逻辑行**不同词**两点相同（选**逻辑行**） |
| 三击 vs `Ctrl+A` 全选（多行页） | —（两者等价） | **必须不同**：三击是逻辑行、全选是全文 |
| `down → move → cancel` | 等于「拖到同一点后正常 `up`」（cancel **不丢弃**已拖出的选区） | 同左 |
| `down → move → cancel → 继续 move` | 等于「拖完即 cancel」 | 同左；不 cancel 时那次移动会扩展选区 |

三击没有专用关键字，按三次跨帧 `down`/`up` 展开写即可（`dblclick` 本身就是两条 `click` 的语法糖）。
落点不是按字宽推算的，是从「双击产物随 x 变化的分段恒定结构」里量出来的（单行页 `240..270` 同段、
`280` 落分隔符、`290..300` 同段、`320..350` 同段；多行页 `240` 另成一段、`250/270/280` 同段、
`290/300` 同段、`310/330/350` 同段）。

**收尾空帧数不影响产物**（实测：`click` 后 `wait` 0/6/12/18/24/32/40 同摘要、`cancel` 后
0/9/15/24/40 同摘要）—— 插入符闪烁相位不参与渲染，脚本尾部多空转几帧不会改图。门禁
`HeadlessPageLinkageTest.doubleClickSelectsWordsTripleClickSelectsLinesAndCancelAbortsDrag`
（单行页 13 次、多行页 14 次出图，共 27 次，单用例约 75 s）。判据的等价对拍走 `renderDistinct`
**成组出图**：组内脚本两两必须不同（自检比的是**实际传入的脚本**）—— 否则等价式恒真、整套静默全绿。

## 环境矩阵（外观 × 玻璃档 × 字号 × 分辨率 × 诊断）

出图的观感不只由页面决定，也由**环境事实**决定。请求可声明五类环境量，与尺寸一样按档位扫：

```bat
:: 外观矩阵
build\headless\qz-shot.bat --page=chat --size=1280x720 --themes=liquid-glass-dark,liquid-glass-light,solid-dark --out=out\theme.png
:: 字号矩阵（作用点 = 解析出口的倍率层，参与布局而不只是把字画大）
build\headless\qz-shot.bat --page=chat --size=1280x720 --font-scales=100,150,200 --out=out\fs.png
:: 360P~2K 分辨率矩阵
build\headless\qz-shot.bat --page=hud --sizes=640x360,854x480,960x540,1280x720,1600x900,1920x1080,2560x1440 --out=out\hud.png
:: 一帧花在哪（采样摘要随本次出图给出）
build\headless\qz-shot.bat --page=hud --size=1920x1080 --debug
```

**产物命名 = 「偏离缺省的维度」+ 尺寸**：`out\hud-640x360.png`、`out\theme-thsolid-dark-1280x720.png`、
`out\fs-fs150-1280x720.png`（外观/字号非缺省才带 `-th<档名>` / `-fs<P>`，页下标在指定时带 `-p<N>`，
**多页面**时每档带 `-pg<页面名>`）。缺省命令的路径因此逐字不变。

四类环境量的**接入时机不同**，这不是实现细节而是语义差别：

| 环境量 | 时机 | 为什么 |
|---|---|---|
| 外观档 `--theme` | **装配期**（建树前） | 控件的配方派生在构建期捕获主题信号对象；换一个信号对象只影响此后构建的控件，已建树的部分不会重算 |
| 字号倍率 `--font-scale` | 装配后、首帧前 | 走 `SceneRuntime.setFontScale`：写一次即广播字体环境失效，chat3 侧再由 `composer()` / `messageList()` 的**有效字号指纹**加一次整树重建接住 —— 行内 code 字号、引用缩进、分隔线厚、表格内衬这些**段流量**随新倍率重取（F42 前它们停在旧值，该环境轴对 chat 段流整体不生效） |
| 玻璃档 `--backdrop-quality` | **装配期** | 档位在渲染热路径上被直读（`BackdropQualityService.current()`），装配期写一次即本档全部帧一致；换档必须早于本档首帧 |
| 诊断 `--debug` | 每帧表态 | 帧是采样的管辖单位，值读是帧内直读 |

字号倍率的管辖范围是**全部长度设计量**（正文、行高、行内 code 字号、引用缩进与竖条、分隔线厚、
表格内衬与边框、标题增量），不是「把画出来的字放大」：同一档里各处的换算口径一致，
如 chat 页行内 `code` 与正文按同一倍率走（100% 是 12 / 13，200% 是 24 / 26）。
**要验证「某处字号没跟上倍率」就用同一条消息的 `--font-scales=100,200` 对照出图**——
这正是一次真实缺陷的发现方式（chat3 的行内 code 曾恒停 12：200% 下被正文挤成小字并折行，已修）。
另：改倍率会改变断行点，所以字号矩阵同时也是**重排**的可视化手段。

**`--theme` 只对读主题系统的树有效**，别拿它当「所有页面都能换配色」：

| 页面 | 换档效果 | 原因 |
|---|---|---|
| `playground` | **变色**（默认 vs 浅色档 685824/921600 像素不同） | 外壳与 9 个演示页都经 `SceneThemes` 取配方 |
| `chat` / `hud` | **逐像素相同**（实测 0/921600） | chat3 的 HUD 形态配色来自它自己的进程级色板 `ChatMarkdownSettings`（气泡底/正文/组头…），不读 runtime 默认主题 |
| `chat-input` | **无效**（不接收，命令层会显式提示） | 输入屏在自己的构造里建树，页内没有主题安装时机（`SceneThemes` 必须在建树前安装）；实测四档主题产物逐字节相同 |
| `text-probe` | 无效 | 前景色写死，连安装都不做 |
| `config` | **无效**（不接收） | 配置页在页壳树构建前安装自己的偏好信号（`ConfigThemePreference`，默认平面档）——主题对它是**配置内容**而非请求级环境量 |
| `picker` | **变色**（实测 dark colors=1410 / light colors=847；字节 356569 / 240753） | 面板表面经主题配方派生（PANEL / OVERLAY / GROUP 等角色） |
| `glass` | **变色**（实测 dark vs light 585339/1152000 像素不同） | 外壳/卡片表面唯一写入者是 `SceneSurfaceBinder`，配方取来源主题的 PANEL/GROUP；采样场色带与材质阶梯是被测样本，按契约保留显式取值 |

出处：`HeadlessThemes` 的类注释记着这条边界与实测值。要用 `--theme` 看效果，请用 `playground` 或走主题系统的业务页面。

四条实测性质，可直接当回归判据：

| 性质 | 实测 |
|---|---|
| `--debug` 不改变像素 | 同一命令加/不加 `--debug`：**0 / 921600** 像素不同 |
| 不给 `--theme` 不改变像素 | 与引入本轴之前的产物逐像素相同（`--theme=` 的缺省是「不干预」而非「选默认档」） |
| `--font-scale` 改变像素 | 100 与 150：**176911 / 921600** 像素不同（跨过标题/换行等布局差异） |
| 同命令逐像素可复现 | 连跑两次：**0 / 921600**（见「出图确定性」） |

环境事实**全部来自请求**：headless 生产包内不得回落生产环境单例（`HeadlessEnvironmentInjectionGuardTest`
守着这条）。违反的后果是静默的——同一命令在不同 `Config` 下出图不同，且 `--debug` 变成一句空话
（采样器永远打不开，命令本身不报错）。

### 小视口下只显示最新几条是预期

HUD 形态的堆叠高度上限是**视口高 × 0.5**（`hudMaxHeightRatio`），超出时按到达时刻剔除更旧的组
（`ChatSceneController#trimHudGroupsByHeight`，设计意图「刷屏不侵占半屏以上」）。故 360P 这类小视口下
只会保留最新的一两组——这是设计行为，不是出图故障。需要看完整内容就用足够大的尺寸，或减少 `--text` 的消息数。

探针的消息按 **1 秒一条**的节奏到达（`ARRIVAL_SPACING_MILLIS`），最后一条恰为 `--clock=` 基准。
真实聊天不可能多条同刻到达，而「同刻到达」会让上述剔除逻辑一次越过全部组 ⇒ 整树为空、出图只剩背景
（实测 `--page=chat --size=1100x720` 命令面 0 条，而同一内容在 `1200x720` 有 24 条）。

## 聊天页（chat）

用生产内容构建入口（`ChatSceneController.buildContent`，与真机 HUD 注册工厂同一方法）渲染 chat3 内容树，
出图含气泡、组头、markdown、行内公式、链接与自动换行。

```bat
build\headless\qz-shot-full.bat --page=chat --size=1280x720 --out=out\chat.png
```

`--text` 是消息串，消息之间用 `;;` 分隔（不取 `|`：那是 Windows 命令行管道符），每条消息三选一：

| 形式 | 含义 |
|---|---|
| `发送者:内容` | 玩家消息（气泡 + 组头）；内容走 markdown 管道 |
| `md:内容` | markdown 系统行（左对齐、无气泡、无组头） |
| 其余 | 普通系统文本（居中一行） |

消息里的字面 `\n` 会转成换行。示例：

```bat
build\headless\qz-shot.bat --page=chat --text="Steve:**粗体** 与 `code`;;md:## 标题\n- 列表项" --out=out\chat.png
```

**必须用完整集启动器**（`qz-shot-full.bat`）：chat3 内容树在方法体里使用 `net.minecraft.*`（`IChatComponent` 等），
最小集类路径下装配会抛 `NoClassDefFoundError`，进程以**退出码 6**（`CLASSPATH-INSUFFICIENT`）收场并给出换启动器的指引。
`hud` 页同理。

### 聊天输入屏（chat-input）

`--page=chat-input` 装配**生产** `ChatInputSurface`（`ChatInputScreen` 的渲染面）：滚轮滚动历史、
Shift 降为单行、拖选与点击行都挂在这一层，`--page=chat` 的内容树形态看不到它们。

```bat
build\headless\qz-shot-full.bat --page=chat-input --size=1280x720 --out=out\chat-input.png
:: Shift+滚轮：先按住再滚
build\headless\qz-shot-full.bat --page=chat-input --size=1280x720 ^
  --actions="move 170 500; frame; keydown SHIFT_LEFT; frame; scroll 10; wait 6" --out=out\ci-shift.png
```

读数 `[headless] scroll: offset=N max=M visible=K`：`offset` = 自底部向上的偏移行数（0 = 停在最新）、
`max` = 偏移上限、`visible` = 当前可视行数。非 Shift 滚轮按 ×7 行、Shift 按 ×1 行（原版语义；
实测 `scroll 10` ⇒ `offset=7`、加 Shift ⇒ `offset=1`）。**需要完整集启动器**（该页触及 `net.minecraft.*`）。

对拍请用读数而不是像素：本页 render 用**墙钟**驱动开合动画（生产语义，未接入出图设施的虚拟帧时钟），
默认设置下像素实测 7/7 逐字节相同，但**帧上限内未收敛时相位会漏进像素**（`--frames=1 --max-frames=1`
三次三种哈希）。

两点注意：

- 消息组首次合成有 **180 ms 入场动画**（组节点 opacity 0→1），动画期间整树不可见、像素逐帧不变，
  「连续 N 帧像素一致」的稳定判据会把这段误判成「已收敛」。故 `chat` 页在未显式给 `--frames` 时
  默认最少 20 帧（≈320 ms 虚拟时间）；自己指定帧数时要覆盖动画时长。
- 本页只渲染内容根，**不套 HUD 外壳、不做四角锚定与倍率缩放**，内容贴在左上角，与真机 HUD 的放置位置不同；
  要出「HUD 放置后的画面」用 `--page=hud`（下节）。

## HUD 页（hud）

同一份聊天内容树走**生产 HUD 宿主装配**（`ui.scene.host.SceneHostWindow`：外壳 + 内容 + 装饰层 + 帧管线），
并按四角锚定放置——即「同一份内容代码，换宿主」的对照，观感与真机 HUD 一致。

```bat
build\headless\qz-shot-full.bat --page=hud --size=1280x720 --out=out\hud.png
build\headless\qz-shot-full.bat --page=hud --page-indexes=0,1,2,3 --out=out\hud.png
```

- `--page-index` 选锚点：**0 = 左上 / 1 = 右上 / 2 = 左下（默认，与原版聊天同位）/ 3 = 右下**；
  `--page-indexes=0,1,2,3` 一次出四角矩阵；
- 消息语法、默认消息集、入场动画与最小帧数都与 `chat` 页相同；
- 外壳与真机**同源**：`SceneHostWindow.Shell.HUD_DEFAULT`（内边距 7/6 + 半透明底）是客户端
  `SceneHudHost` 与 headless 共用的同一份默认外壳，不是两处各写一份；
- **本页无输入**：HUD 窗口的契约就是不持有输入源、不参与命中仲裁，故 `--actions` 对本页不生效；
- **`--text=`（空消息集）出纯背景是预期**：内容空尺寸 ⇒ 整窗（含外壳）隐藏，输出
  `commands=0 / bounds=(empty) / colors=1`。这与 `chat` 页的同款现象含义不同——那里 `commands=0` 是缺陷信号（见排查表）。

## 配置页（config）

生产配置页 UI（`club.heiqi.config.ui.ConfigScreen`）在无游戏进程里装配、出图——**字段定制与游戏内
同一个入口**（`ModernConfigAssembly.buildScreen`：fontSort 专用 renderer、characterFontRules 三栏编辑器），
所以图里的字段形态就是真机形态，不是照着真机再写一遍的近似。

```bat
build\headless\qz-shot.bat --page=config --size=1280x720 --out=out\config.png
build\headless\qz-shot.bat --page=config --page-indexes=0,1,2 --out=out\config-section.png
```

- **用最小集即可**（`qz-shot.bat`）：配置页的装配链零 `net.minecraft` 依赖。实测 1280×720：
  `commands=59 [fill=1 surface=33 text=25/226ch] bounds=0,0..1280,745 colors=1156`、自检 ok、约 0.9 s（8 次复跑 758~967 ms，单次样本会抖）；
- `--page-index` 选 **section 下标**：走屏幕公开入口 `showSection(int)`（与导航点击写同一个受控源），
  不依赖命中坐标。三档实测命令面与颜色数两两不同（59/1156、58/1066、41/1117）⇒ 切换真的生效；
- **配置真源落临时目录**，随会话关闭整体删除：一次出图不改写你的真实配置，也不在进程外留痕。
  文件初始不存在 ⇒ 出图是**默认配置下的配置页**，这正是可复现的那个状态；
- **不订阅保存/重载回调**：`ConfigSaveListener` 会把保存结果回灌**本进程运行态**并触发字体 reload，
  那是「游戏客户端」这个宿主的职责。故点「保存」只写临时文件，不改变任何进程状态；
- **字体运行态也不回灌**（「不订阅」的必然代价，不是缺陷）：真机路径订阅后由 coordinator 做 initial
  apply（仓库注释自述「可能随后把 FontConfig 清为空」），而 headless 保持进程当前的字体发现态
  （实测 507 个字体顺序）。故同一页面在这两条路径上的**字体解析可能不同**；
- **不接收 `--theme`**：见「环境矩阵」的页面表。命令层会给一条显式提示，不静默忽略——
  「跑了没变化」与「参数没接线」在产物上不可区分；
- 默认最小帧数同聊天系页面（20）：标题与字段 presentation shell 在布局发布后有 opacity 级联进入，
  帧数给少会截到半成品（与 chat 页同因）。

**为什么装配与 MC 宿主是两个类**：装配入口 `ModernConfigAssembly` 与宿主包装 `ModernConfigEntry`
分开，不是行数取舍而是**加载事实**——宿主类在最小集类路径下 `Class.forName` 就抛
`NoClassDefFoundError: net/minecraft/client/gui/GuiScreen`（实测）。

触发点**不是**「方法签名引用了 MC 类型」——独立复核的合成实验证明：仅出现在方法签名 / 字段类型 /
`checkcast` / `instanceof` 里的 MC 类型都不触发；真实原因是**校验期的可赋值性检查**：
`return new ModernConfigScreen(...)` 要证明它可赋给 `GuiScreen`，于是被迫解析缺失的父类型。
故判据不是「有没有 import MC」，而是**有没有把子类型收敛到缺失的父类型**。

门禁 `HeadlessPageLinkageTest` 直启出图钉四条：`config` 出图 / `playground` 正锚（用来区分
「页面坏了」与「最小集/注入面坏了」）/ `--page-indexes=0,1,2` 三档颜色数两两不同 / 出图后临时配置
目录无新增。后两条是补的：独立复核实测「把 `showSection` 改成空操作」「摘掉会话清理」时，
只钉前两条的门禁**全绿**——即交付了三项能力却只守住一项。

## 磨玻璃实验室（glass）

`backdrop-filter` 观感验收页（`internal.devtools.glass.GlassLabHost`）：采样场色带 + 参数台滑杆 + 探针玻璃带 +
诊断卡。它是**液态玻璃观感**的主验收面，零 `net.minecraft` 依赖，最小集即可跑。

```bat
build\headless\qz-shot.bat --page=glass --size=1280x900 --out=out\glass.png
```

- **尺寸建议 ≥ 1280×900**：实验室内容高约 788 px（1280 宽），720P 下底部诊断卡落到视口外，
  输出里会出现 `outsideViewport=1`（是提示不是失败）。实测 1280×720 的 `bounds=0,0..1280,788`；
- `--page-index` 对本页无意义（忽略）：实验室没有子页/分节；
- **接收 `--theme`**：外壳/卡片表面唯一写入者是 `SceneSurfaceBinder`，配方取来源主题 PANEL/GROUP，换档真的变色；
  采样场色带、玻璃 tint/亮边、材质阶梯是**被测样本**，按契约保留显式取值、不随主题变；
- **参数台滑杆不能被 `--find` 找到**：滑杆的标签文本挂在非交互兄弟节点上（`r0/0/2/3` 是 `[non-interactive]`），
  `--find=模糊半径` 命中 0 个。正确链路是 `--nodes=all` 取可命中节点地址 → `--center=<地址>` 取中心 → 输入脚本拖拽。
  实测：`--center=r0/0/2/3/1/0` ⇒ `452,452`；`--actions="move 452 452; frame; down; move 540 452; frame; up; wait 6"`
  ⇒ 标签「模糊半径 18」变「模糊半径 60」（同型可把材质档拖到 LiquidGlass）；
- **诊断卡读的是本帧玻璃路径**（见「怎么读输出」的 `backdrop:` 行）——它是判断这张图的玻璃证据属于哪一档的入口。

实测（1280×900，本机 RTX 5070 Ti / NVIDIA 610.74）：

| 命令 | commands | colors | backdrop |
|---|---|---|---|
| `--page=glass`（默认外观） | 62 | 16336 | `path=shader requests=72` |
| `--themes=liquid-glass-light` | 62 | 14373 | `path=shader requests=72` |
| `--themes=solid-dark` | 62 | 15756 | `path=shader requests=8` |

`solid-dark` 档请求数少是预期：实色档下多数表面不带滤镜配方，故只提交少量玻璃请求。

## 玻璃质量档（backdrop quality）

玻璃滤镜的 shader 抽头预算由**进程级档位**决定，headless 侧可按请求覆盖：

| 档位 | 抽头预算 | 语义 |
|---|---|---|
| `full`（默认） | 13 | 完整档：`#if UIB_TAP_BUDGET >= 13` 分支，与引入档位前的核一致 |
| `eco` | 9 | 省电档：`#else` 分支的 9 抽头向日葵螺旋核（覆盖半径与加权 RMS 同量级，片元采样次数 -30.8%） |
| `solid` | （同 13） | 不装滤镜：玻璃请求被策略禁用，画面无 backdrop 绘制 |

```bat
:: 两档卷积核一次对比（产物 out\bq-bqfull-1280x900.png / out\bq-bqeco-1280x900.png）
build\headless\qz-shot.bat --page=glass --size=1280x900 --backdrop-qualities=full,eco --out=out\bq.png
```

- **写入时机是装配期**（与 `--theme` 同类）：档位在渲染热路径上直读，装配期写一次即本档全部帧一致。
  逐档独立进程下产物是请求的函数；`--share-context` 同进程批量下每档 open 各写一次（同值幂等），多档仍各自正确；
- **档名拼错直接失败**（退出码 2 并列出可选值）：`BackdropQuality.parse` 的契约是「未知值回落 full」（配置容错语义），
  命令行沿用它会静默变成另一档 —— 与 `--theme` 的档名校验同口径；
- **实测（1280×900）**：

| 命令 | `backdrop` 读数 | colors |
|---|---|---|
| `--backdrop-quality=full` | `quality=full taps=13 path=shader requests=72` | 16336 |
| `--backdrop-quality=eco` | `quality=eco taps=9 path=shader requests=72` | 16340 |
| `--backdrop-quality=solid` | `quality=solid taps=13 path=none requests=90 detail=disabled by page policy` | 1072 |

- **两档真的走了不同的核**：`--backdrop-qualities=full,eco` 的产物逐像素差 **157190/1152000（13.64%）**；
- `eco` 的 `path=shader` 是它**唯一**的证据来源：落到 `fixed-pipeline` 就说明 9 抽头变体编译/链接失败
  （该分支在设施里此前从未被执行过，见规划 F40）；
- 档位只影响**有玻璃请求的页面**：`config` 页本就没有 backdrop 请求（读数恒 `none`），给它设档位不改变产物。

**chat / hud 也吃档位**（F43 实测，此前只在 `glass` 页验证过）：chat 的玻璃在气泡表面
（`ChatMessageList` 的 bubbleSurface → `UiBackdrop`），hud 是同一棵内容树走 HUD 宿主装配。

| 页面（1280×720，`--text="Steve:glass probe"`） | `full` | `eco` | `solid` |
|---|---|---|---|
| `chat` | `quality=full taps=13 path=shader requests=22` · 11563 B | `quality=eco taps=9 path=shader requests=22` · 11568 B | `quality=solid taps=13 path=none`（策略禁用） · 9501 B |
| `hud` | `quality=full taps=13 path=shader requests=21` · 11841 B | `quality=eco taps=9 path=shader requests=21` · 11881 B | `quality=solid taps=13 path=none` · 9866 B |

三档产物两两不同（`full` vs `eco`：chat 差 5 B、hud 差 40 B；`solid` 分别少 17.8% / 16.7%）——档位确实进了这两页的像素，
门禁 `HeadlessPageLinkageTest.backdropQualityAxisAppliesToChatAndHud` 钉住这组读数与三档产物差异。

## 搜索选择器（picker）

`ScenePickerPanel`（库内体量最大的控件族：面板 + 分类导航 + 虚拟网格 + 成员带 + 信息条 + 密度档）的
**控件级**出图入口。补它的理由：生产配置页 schema 里**没有字段挂 `SearchPickerSpec`**
（`Values.searchPicker` 目前只出现在测试里），改 picker 之后从 `--page=config` 也看不到它 ——
等于没有出图入口。

```bat
build\headless\qz-shot.bat --page=picker --size=1280x720 --out=out\picker.png
build\headless\qz-shot.bat --page=picker --page-indexes=0,1,2 --out=out\picker-state.png
```

- `--page-index` = **演示状态**：0 = 全部候选（默认，24 项）/ 1 = 查询 `stone` 过滤后 / 2 = 空结果态
  （查询 `zzzz`）；三态分别覆盖网格布局、过滤收缩与空态。走宿主公开入口切换，不依赖命中坐标；
- 候选数据由探针自备（方块 id + 中文名），**不接候选源 SPI**：面板走结果信号路径、过滤由装配层负责 ——
  与真机「装配层持候选、面板只渲染」的分工一致；
- 面板是**居中 70% 浮层**（overlay 栈自管），宿主根只提供全屏承托底。

**实测（1280×720）**：

| 状态 | commands | colors | 画面要点 |
|---|---|---|---|
| 全部候选 | 116 | 1410 | 顶栏 `24 results`、左栏 `All 24`、网格 4×6 |
| 过滤后（`--page-index=1`） | 40 | 1133 | 查询串 `stone`、结果收缩 |
| 空结果（`--page-index=2`） | 21 | 1061 | 中栏 `No matching results`、`0 results` |

命令面 116 > 40 > 21（结果越少画得越少）、三档 colors 两两不同 ⇒ 状态切换真的改了内容。
门禁 `pickerPageRendersAllThreeStates` 就按这两条钉（变异「`showState` 空操作」⇒ FAILED）。

**边界（别据此下结论）**：

- 这是**控件级**装配，不是配置页字段接线 —— 字段外壳、行触发器、值与选择的写回
  （`SearchPickerFieldSupport` + `ValueSpec` + `Registry`）**无覆盖**；那条要等生产 schema 出现
  picker 字段才有入口；
- 候选项里的**物品图标不渲染**（探针的 `VisualAdapter` 只提供文本标签，headless 没有物品贴图通路），
  格内是空槽 + 下方名称 —— **不要**据本页出图判断图标渲染；
- **悬停 tooltip 的内容看不到**：底栏那行 `Hover a result to see its full name and ID` 是**常驻文案**
  （默认出图就可见），看不到的是指针停留后弹出的 tooltip（完整名称与 ID）—— 那需要输入脚本悬停；
- **分类导航只有 `All` 一行**：探针不注入 `categories` / `categoryOf` ⇒ 分类行与分类维度切换未覆盖；
- **密度档只有 AUTO 求解出的一档**（`Density STANDARD`）：探针不注入 `densityPreference` ⇒
  compact / standard / roomy 三档未覆盖；
- 受控 `open` 恒真且未接 `onCloseRequest` ⇒ **关闭与提交路径不可演示**（当前无害：关不掉也不崩）；
- 成员带（`listMembers` 模式）与候选源 SPI 路径未覆盖（探针只走结果信号路径）。

## 出图确定性

**同一命令在同一台机器上逐像素可复现**（实测：相隔 73 秒的两次 `--page=hud` 出图差异 0 像素，
且其中一次落在跨分钟边界上）。这不是自动成立的，靠三条约束：

- **时间事实只来自请求**：消息到达时刻与帧时钟起点都取 `--clock=` 给的虚拟墙钟基准（两者同源），
  headless 生产包内**不得出现 `System.currentTimeMillis()`**（`HeadlessWallClockGuardTest` 守着）。
  踩过的坑：组头时间戳取进程当前时刻 ⇒ 同一命令在 13:05 与 13:07 出图差 9590 像素，
  差异集中在时间戳及其相邻区 —— 「改一行 → 出图对拍」这条主用途随之失效；
- **帧计划确定**：`--frames` / `--settle` / `--max-frames` 给定后帧数确定；**帧时间也已虚拟化**
  （规划 F47）：headless 每个会话注入「`--clock` 基准 + 帧序号 × 16 ms」的虚拟帧时钟，动画与插入符相位
  不再随机器负载漂 —— **带输入脚本的出图同样逐字节可复现**（实测同一命令连跑 8 次同一哈希）。
  修复前实测是 8 次 4 种产物（差异恒为 16 px 的 caret 竖线相位族，加大 `--settle` 不能消除）。
  注意两点：① **`frames` 读数本身仍可能抖**（同一产物下出现 8/9、31/32 之类），别把帧数写进锚点；
  ② 逐字节锚点仍限「同一机器 + 同一命令」，跨机器/跨 GL 驱动/跨字体环境不保证（见本章末）。
- **每个产物从冷进程起算**：字体 atlas 是**进程级按需资源** —— 字形落在 atlas 的哪个位置取决于「此前
  生成过哪些字形」。同一 JVM 内渲染第 N 档时 atlas 里已有前面各档的字形，本档字形的 UV 因此不同，
  边缘采样出现 **±1~±5 微差**。实测：批量 `[640x360,2560x1440]` 的 2560 档与单跑差 31534/3686400 像素，
  把前置档从 640 换成 1280 又得到第三个结果 —— 产物取决于它在进程内的渲染次序，而不只取决于请求。
  故**批量默认逐档独立进程**（每档 +1.9 s 左右启动开销）；`--share-context` 换回同进程复用（快，
  但带上述历史依赖，只建议扫观感用）；
- **输出抖动（已定位并修复）**：同一命令曾偶发产出两种结果之一。定位（2026-09-17）：历史命令
  `--page=playground --page-index=8 --size=1280x720 --font-scale=100`，本机 50 次得 **48 次**
  `a8fb1156c3978823…`（`commands=101 segments=62`）、**2 次** `2669a01c4c59cc42…`
  （`commands=100 segments=61`）；独立复核在修复前基线上另跑 32 次得 29:3、16 次得 14:2 ——
  即 **4% ~ 12.5%，合并样本 7/98 ≈ 7%，该比率随机器负载浮动，不要当固定值**。两个 hash 与当初审核
  记录的一对完全相同；像素差异是滚动条滑块底端圆角的 8×6 px（30 像素，行剖面 2/2/4/8/8/6）。
  **根因**：`TextLayoutService.tryAcquireWidthMissBudget()` 把宽度缓存 miss 预算绑在 **16ms 真实
  时间窗**（`System.nanoTime()`）上，超预算（默认 64/窗口）的码点按**空格宽近似**排版。冷启动一帧
  要测量的码点远超预算，**哪些码点被近似取决于这一次运行的真实耗时** ⇒ 布局宽度随机器负载漂移。
  **定论依据不是「跑 50 次没复现」**（4% 基线下 0/50 的偶然概率约 0.13，单独不足以定论），而是
  只改预算的剂量-反应对照（各 16 次）：默认 64 → 14:2；**10^8（永不耗尽）→ 16/16 全同**；
  **1（几乎必耗尽）→ 产物换成第三种分布**（全部 `commands=100`，从不出现锚点值）。预算不可耗尽
  时其它真实时间源（字形老化队列、图页上传排空）仍在跑却稳定 ⇒ 排除「真凶另有其人」。
  **修复**：headless 会话解除该预算（`HeadlessSession.open` 里置
  `FontConfig.widthCacheMissBudgetPerWindow = 0`），全部码点走精确测量。耗时无退化（均值 +0.9%，
  Welch t=1.43；含 JVM 启动的 wall 中位均约 3.5 s）。
  **加大 `--settle` 消除不了**：settle 由 2 提到 20（帧数 10~12 → 27~30）后少数派仍 3/18 ⇒ 不是
  「没来得及收敛」，而是被近似的码点拿不到真值、`widthConvergeEpoch` 不抬（`GlyphRuntimeTables`
  只在债务计数**恰好归零**时自增），近似布局被**固化进最终帧**。
  > 生产侧口径未变：游戏内该预算仍是「按时间窗降级」（卡顿时文本宽度会被近似），且同样会被固化。
  > 这是**框架正确性**问题（同一输入的产出取决于机器负载），是否把降级口径改成与时间无关的量
  > （按帧 / 按渲染代际）或按码点粒度驱动失效，需单独决策。
  **「逐字节相同」仍非跨机器保证**：锚点对拍请在同机同命令下连跑取多数值（≥5 次），或比对内容量
  （`bounds` / `commands` 数 / 文本字符数）。

默认基准按本机时区显示为 `08:00`（东八区）。要观察其它时刻的观感用 `--clock=`：

```bat
build\headless\qz-shot-full.bat --page=hud --clock=1735689600000 --out=out\hud-2025.png
```

**不保证**跨机器、跨 GL 驱动、跨字体环境的逐像素一致 —— 那三个变量不在设施控制内；
逐像素对拍只在「同一机器 + 同一命令」下成立。

## 怎么读输出

```
[headless] request: page=playground#1 size=1280x720 frames=2 background=FF0E1014 settle=2 maxFrames=60 clock=1704067200000 fontScale=100% debug=false theme=(page default) backdropQuality=(process current)
[headless] capabilities: gl=4.6.0 … stencil=8 maxTexture=32768 fonts=245 awtHeadless=false
[headless] input: dispatched=3 pending=0 pointer=315,88 clock=133ms
[headless] commands: commands=62 [fill=0 surface=16 border=0 text=46/1050ch segments=0 image=0] … bounds=1,1..1279,717 outsideViewport=0
[headless] output: …png (200031 bytes)
[headless] self-check: ok 1280x720 ink=100.00% inkPx=921600 opaquePx=921600 meanAlpha=255.0 colors=1290 glError=0
[headless] backdrop: quality=full taps=13 path=shader requests=64 detail=blur=4, saturation=1.00, family=LIQUID_GLASS, material=DARK_ULTRA_THIN vibrancy=1.18 lens=0.21, …
[headless] frames: 4/60
[headless] elapsed: 615 ms
```

- `commands`（命令面）与 `self-check`（像素面）是**两条独立证据**：命令说有绘制、像素说画出来了。只有一边成立即为设施故障，
  不是「UI 画得不好」；
- `backdrop`：**本次渲染窗口的玻璃事实**。行首 `quality=<档> taps=<抽头预算>` 是本次生效的档位与卷积核（13 / 9），
  它**没有玻璃请求时也会打印**（否则「档位设了但没请求」与「档位没接线」不可区分）；其后 `path=shader` 是完整路径；`fixed-pipeline` / `tint-fallback` 是**降级档**
  （前者只有多重采样模糊、无 vibrancy/亮边/噪点/液态折射，后者是 tint 兜底）；`none` 表示**本窗口最后一次
  玻璃请求没有成功路径**——既可能一次请求都没发起，也可能是最后一次请求被策略/档位/几何/裁剪短路；
  两者按同行 `requests=` 的**有无**区分（有 = 发生过请求、无 = 未发起；判定用请求计数差，见下条，避免把上一档的残留报成本档）。`detail` 段含 `rev=N` 等过程量，
  但它们同样只由请求决定：**实测同命令多次运行该行逐字节相同**，可参与对拍；
- 玻璃请求计数按**窗口差**判定：静态"最近一次路径"是最后值，批量同进程出图会把上一档的残留报成本档事实
  （`--share-context --pages=glass,text-probe` 即可复现），故读数取 `UiRenderContext#getBackdropFilterInvocationCount()` 的前后差；
- `outsideViewport>0`：有矩形命令完全落在视口外（小视口溢出提示，不判失败）；
- `frames: N/上限`：等于上限说明未收敛（动画/时间源持续变化），不是错误但内容可能还在变；
- 批量模式末尾有 `batch: N/M ok — page#0@1280x720=ok …` 汇总行。

## 退出码

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 2 | 参数错误 |
| 3 | 设施失败（诊断带阶段标签：能力探测 / scene 装配 / 帧推进 / 像素读回 / PNG 编码）。未捕获的未预期错误也归此码，诊断行是 `UNEXPECTED-ERROR` + 完整栈、**不带**阶段标签 |
| 4 | 像素自检未通过，或批量中存在失败档位 |
| 5 | **运行环境不具备**出图能力（natives 加载失败 / AWT 无窗口句柄能力 / GL 上下文建不起来）。诊断里带 `ENV-UNAVAILABLE` 行；处置是换 JDK 或加 Xvfb，不是查 UI |
| 6 | **当前类路径缺少请求所需的类型**（通常是启动器选错）。诊断里带 `CLASSPATH-INSUFFICIENT` 行与缺失类型名；处置是换 `qz-shot-full.bat`（Linux/macOS 为 `.sh`），不是查 UI 也不是换机器 |

三种「没出成图」的处置互不相同，故各自独立成码：**3** 查设施 / UI，**5** 换运行环境，
**6** 换启动器。**用错启动器**（如用最小集跑 `chat` / `hud`）现在报 **6**：

```
[headless] CLASSPATH-INSUFFICIENT：当前类路径缺少类型 net.minecraft.util.IChatComponent
[headless] 处置：若该页面触及 Minecraft 类型（chat / hud），换用完整开发类路径启动器 build\headless\qz-shot-full.bat…
```

（6 之前是未捕获的 `NoClassDefFoundError` 冒泡 ⇒ 退出码 1，不在契约内；2026-09-18 由进程边界兜底收口，见规划 F39。
**逐档独立进程**批量下报 6 会**就地终止**并写明「后续档未执行」（类路径缺件换档不会变好）；
**同进程批量**（`--share-context`）则在装配处直接终止、不打印汇总行 —— 两者都只在契约内报了码。）

测试侧据此分流（`HeadlessShotGate`）：5 跳过，3 / 4 / 6 红。唯一例外是**类路径契约判据**本身
（`missingMinecraftDependencyReportsContractExitCode`）—— 它按设计就用最小集跑 `chat`，并把 6 断言为**正确**结果。

## 排查

| 现象 | 原因与处理 |
|---|---|
| `[GL 上下文] 创建 GL 上下文失败` | 缺 natives（启动器已带 `-Djava.library.path`）；Linux 无桌面环境需 Xvfb |
| `[GL 上下文] … libjawt.so: version 'SUNWprivate_1.1' not found` | JDK 发行版不兼容：Zulu 的 `libjawt.so` 不导出该版本符号，而 LWJGL2 的 `liblwjgl64.so` 在加载期就依赖它（实测 CI 的 Zulu 17）。换 Temurin / Oracle JDK；此类失败以退出码 5 报出（`ENV-UNAVAILABLE`），不是 UI 缺陷 |
| `fonts=0`、出图豆腐块或字体初始化失败 | 环境无系统字体（AWT 字体子系统全有或全无）：装 fontconfig + 字体包后重启进程 |
| 整帧全透明且自检 FAILED | 帧前置语义 / 帧缓冲绑定问题，属设施缺陷，请带自检输出报障 |
| 文字残缺（只出部分字形） | 字形异步生成尚未就绪：提高 `--settle` / `--max-frames`（默认已自动收敛） |
| `--page=chat` 出全背景、`colors=1` | 帧数不足：入场动画（180 ms）期间整树不可见且像素不变，会被判成「已收敛」。提高 `--frames`（≥20） |
| `--page=chat` 出全背景且 `commands=0` | 入场动画**整段未起播**：组 opacity 恒 0 → 零透明子树被 paint 跳过 → 命令面为空。历史上的根因是虚拟时钟起点早于消息出生时刻（构造期控制器初始化耗时数百 ms 的时序竞态，规划 F23）；2026-09-18 起两者同源于 `--clock=` 注入的基准，该竞态已根除。若仍复现，按「出图确定性」一节查时间源，不要再往帧数上加 |
| `--page=hud --text=`（空消息集）出纯背景 | **预期行为**而非故障：内容空尺寸 ⇒ 整窗（含外壳）隐藏，`commands=0 / colors=1` 正确。给非空 `--text` 即出外壳与内容 |
| 需要一次出多张 | `--sizes=` / `--page-indexes=`：同进程内多档，字体与 GL 上下文只初始化一次 |
| 出图完成但进程不退出 | 历史缺陷（GL 上下文挂在隐藏 AWT 容器上，未显式释放时 `System.exit` 停在 AWT 退出钩子）；现已由 `HeadlessShotMain` 出图后调 `GlOffscreenSurface.shutdownContext()` 处理。若复现请报障 |
| 屏幕上出现窗口 / 抢焦点 | 不应发生（可见窗口峰值 0）。若复现，检查是否有人改回 `Display.create()` 直连路径，见「不弹窗」 |
| 批量各档互相不一致 / 与单跑不一致 | 检查是否用了 `--share-context`：该模式下产物带字体 atlas 历史依赖（±1~5 微差）。默认的逐档独立进程模式已无此问题 |
| 玻璃「只有模糊、没有质感」 | 先看 `backdrop:` 行：`path=fixed-pipeline` / `tint-fallback` 是**降级档**（无 vibrancy / 亮边 / 噪点 / 液态折射），`path=shader` 才是完整路径。历史事故：2026-09-02~09-18 `uiBackdropF.frag` 注释里混入中文，NVIDIA 编译器报 `error C0000: syntax error, unexpected $undefined`，玻璃**静默降级 16 天**；现由 `GlslSourceAsciiGuardTest`（源字符集）与 `HeadlessPageLinkageTest`（端到端路径）两条门禁守着 |
| 两次出图像素不同 | 先确认命令逐字相同：`--clock=` 不同本就该不同（时间戳/动画轴变了），`--frames` 不同也会不同（动画进度不同）。同机同命令仍不同则是设施缺陷，带两份命令与产物报障 |

## 边界（不要据此下结论）

- 出图**不代表**真机 MC 宿主 / Angelica / lwjgl3ify 上下文；
- **同机同命令逐像素可复现**（时间已解耦，见「出图确定性」）；但**不作为跨机器 / 跨 GL 驱动 / 跨字体环境的金样** —— 那三个变量不在设施控制内；
- **玻璃路径是环境事实、不是承诺**：本机实测 `--page=glass` 走 `shader`，换驱动/机型可能落到 `fixed-pipeline`
  （只剩模糊）。`backdrop:` 行如实报告当次路径，**别拿一张图当跨机器金样**；
- 不覆盖原版包装类禁令（Tessellator 等）一类问题；
- 不经过 `LwjglInputSource` 的 poll 差分语义——headless 注入的是帧，桥内部的差分/边沿类缺陷不在覆盖范围内；
- **chat3 已纳入**（`--page=chat`）：走的是生产同一入口 `ChatSceneController.buildContent`。
  仍未覆盖的是 MC 宿主侧接线（输入屏、网络消息来源、原版聊天接管），它们不属于渲染面。
- **HUD 已纳入**（`--page=hud`）：窗口宿主已上提为宿主无关的 `ui.scene.host.SceneHostWindow`
  （外壳 + 内容 + 装饰层 + 帧管线 + 空内容语义），与客户端 `client.hud.SceneHudHost.RetainedWindow` 共用同一份装配。
  仍未覆盖的是 MC 宿主侧接线（注册表生命周期、工具栏注册表、倍率设置持久化），它们不属于渲染面。
