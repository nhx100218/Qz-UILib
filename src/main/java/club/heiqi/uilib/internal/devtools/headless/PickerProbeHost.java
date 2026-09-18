package club.heiqi.uilib.internal.devtools.headless;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import club.heiqi.config.ui.editor.SearchPickerData;
import club.heiqi.config.ui.editor.VisualAdapter;
import club.heiqi.uilib.ui.env.UiEnvironment;
import club.heiqi.uilib.ui.reactive.Signal;
import club.heiqi.uilib.ui.scene.control.ScenePickerPanel;
import club.heiqi.uilib.ui.scene.host.AbstractSceneHostWidget;
import club.heiqi.uilib.ui.scene.host.SceneHostAssembly;
import club.heiqi.uilib.ui.scene.input.PlatformInputSource;
import club.heiqi.uilib.ui.scene.node.SceneNode;
import club.heiqi.uilib.ui.scene.runtime.SceneRuntime;
import club.heiqi.uilib.ui.scene.text.SceneTextMeasurer;
import club.heiqi.uilib.ui.scene.theme.SceneTheme;
import club.heiqi.uilib.ui.scene.theme.SceneThemes;

/**
 * 搜索选择器（{@link ScenePickerPanel}）的 headless 探针宿主。
 *
 * <p><b>为什么需要它</b>：picker 是库内体量最大的控件族（面板 + 分类导航 + 虚拟网格 + 成员带 +
 * 信息条 + 密度档），但生产配置页的 schema 里<b>没有任何字段挂 {@code SearchPickerSpec}</b>
 * （{@code Values.searchPicker} 目前只出现在测试里）⇒ 改 picker 之后 agent 既不能从
 * {@code --page=config} 看到它，也没有别的出图入口。本宿主补上这一档：控件级装配，候选数据由
 * 探针自备，零 Minecraft 依赖。</p>
 *
 * <p><b>与真机的关系（边界）</b>：这里建的是<b>控件本身</b>（{@code ScenePickerPanel.create}），
 * 不是配置页字段接线（那条走 {@code SearchPickerFieldSupport}，需要 {@code ValueSpec} +
 * {@code Registry} 的完整接入面）。故本页出图对「面板的布局 / 外观 / 交互」有效，对
 * 「字段外壳与行触发器的接线」无覆盖 —— 后者要等生产 schema 出现 picker 字段才有入口。</p>
 *
 * <p><b>三个状态</b>（{@code --page-index}）：0 = 全部候选（默认）/ 1 = 查询过滤后 / 2 = 空结果态。
 * 覆盖网格布局、过滤收缩与空态三种形态。</p>
 */
final class PickerProbeHost extends AbstractSceneHostWidget {

    /** 演示候选数：足够铺满网格并触发多行（默认单元 64px + 间距 8 + 5 行可见）。 */
    private static final int CANDIDATE_COUNT = 24;
    /** 过滤态用的查询串。 */
    private static final String FILTER_QUERY = "stone";
    /** 空态用的查询串。 */
    private static final String EMPTY_QUERY = "zzzz";

    /** runtime 默认主题信号（外壳与面板表面的来源，构造期确定）。 */
    private final Signal<SceneTheme> themeSignal;
    /** 宿主根（全屏底；面板走 overlay 栈，自带居中 70% 定位）。 */
    private final SceneNode root;
    /** 查询文本受控源。 */
    private final Signal<String> querySignal = Signal.create("");
    /** 结果受控源（探针自备演示候选 —— 不接候选源 SPI，面板不自行查询）。 */
    private final Signal<SearchPickerData.SearchResult> resultsSignal;
    /** 面板开合受控源：恒 true = 出图即面板态。 */
    private final Signal<Boolean> openSignal = Signal.create(Boolean.TRUE);

    /**
     * 创建探针宿主（环境与外观都可注入）。
     *
     * <p><b>刻意不提供单参构造</b>：headless 生产包不得回落到生产环境单例
     * （{@code HeadlessEnvironmentInjectionGuardTest} 的禁则：源码里出现
     * {@code defaultEnvironment} 即红），漏接环境应当<b>编译失败</b>而不是静默取生产配置 ——
     * 与 {@code TextProbeHost} / {@code ChatSceneProbeHost} / {@code HudSceneProbeHost} 同口径。
     * 这也是本类与 {@code glass} 包 {@code GlassLabHost} 的差别：那个类不在本包，保留单参构造
     * 在生产路径是正确语义（游戏内打开实验室就该用生产环境）。</p>
     *
     * @param input       平台输入源，可为 null
     * @param environment 宿主环境端口，不可为 null
     * @param theme       初始外观档；{@code null} = 宿主默认（{@link SceneThemes#DEFAULT}）
     */
    public PickerProbeHost(PlatformInputSource input, UiEnvironment environment, SceneTheme theme) {
        this(SceneHostAssembly.defaultMeasurer(), input, environment, theme);
    }

    /**
     * 完整注入构造。
     *
     * @param measurer    文本度量端口
     * @param input       平台输入源，可为 null
     * @param environment 宿主环境端口，不可为 null
     * @param theme       初始外观档；{@code null} = 宿主默认
     */
    public PickerProbeHost(SceneTextMeasurer measurer, PlatformInputSource input, UiEnvironment environment,
            SceneTheme theme) {
        super(measurer, input, environment);
        this.themeSignal = Signal.create(theme == null ? SceneThemes.DEFAULT : theme);
        this.resultsSignal = Signal.create(allCandidates());
        this.root = buildThemedTree();
    }

    @Override
    protected SceneNode getRoot() {
        return root;
    }

    /**
     * 切换到指定演示状态（{@code --page-index}）。
     *
     * @param stateIndex 0 = 全部候选 / 1 = 过滤态 / 2 = 空结果态；越界按 0 处理
     */
    public void showState(int stateIndex) {
        if (stateIndex == 1) {
            querySignal.set(FILTER_QUERY);
            resultsSignal.set(filteredCandidates(FILTER_QUERY));
        } else if (stateIndex == 2) {
            querySignal.set(EMPTY_QUERY);
            resultsSignal.set(new SearchPickerData.SearchResult(
                    Collections.<SearchPickerData.Candidate>emptyList()));
        } else {
            querySignal.set("");
            resultsSignal.set(allCandidates());
        }
    }

    /** 主题接线 + 建树（同 {@code GlassLabHost} 口径：主题先于建树安装，建树包进 root 作用域）。 */
    private SceneNode buildThemedTree() {
        runtime.__enableMotion();
        SceneThemes.install(runtime, themeSignal);
        final SceneNode[] holder = new SceneNode[1];
        runtime.__runRoot(() -> {
            SceneNode shell = SceneNode.column();
            shell.setFillParentWidth(true);
            shell.setFillParentHeight(true);
            // 全屏不透明承托底：面板是居中 70% 的浮层，背后必须有稳定底色（同 glass 页的诊断承托底口径）。
            shell.setBackgroundColor(0xFF0E1014);
            holder[0] = shell;
            mountPanel();
        });
        return holder[0];
    }

    /** 装配 picker 面板（控件级入口；面板自管 overlay 栈与居中定位）。 */
    private void mountPanel() {
        VisualAdapter adapter = new VisualAdapter() {
            @Override
            public String candidateLabel(SearchPickerData.Candidate candidate) {
                return candidate.label();
            }

            @Override
            public String variantLabel(SearchPickerData.Variant variant) {
                return variant.label();
            }
        };
        ScenePickerPanel.Props props = ScenePickerPanel.Props
                .builder(querySignal, resultsSignal, Signal.create(Boolean.TRUE),
                        querySignal::set, selection -> { }, adapter)
                .open(openSignal)
                .build();
        // create 内部把面板挂到 runtime 的 overlay 栈（居中 70% portal），故此处不再手动 mount；
        // 状态由 showState 经 query/results/open 信号驱动，不依赖命中坐标。
        ScenePickerPanel.create(runtime, props);
    }

    /** 全部演示候选（方块 id + 中文名，贴近真机 picker 的候选形态）。 */
    private static SearchPickerData.SearchResult allCandidates() {
        String[][] samples = {
                { "minecraft:stone", "石头" },
                { "minecraft:cobblestone", "圆石" },
                { "minecraft:stone_bricks", "石砖" },
                { "minecraft:mossy_stone_bricks", "苔石砖" },
                { "minecraft:oak_planks", "橡木木板" },
                { "minecraft:spruce_planks", "云杉木板" },
                { "minecraft:birch_planks", "白桦木板" },
                { "minecraft:glass", "玻璃" },
                { "minecraft:glass_pane", "玻璃板" },
                { "minecraft:sand", "沙子" },
                { "minecraft:gravel", "砂砾" },
                { "minecraft:dirt", "泥土" },
                { "minecraft:grass_block", "草方块" },
                { "minecraft:oak_log", "橡木原木" },
                { "minecraft:spruce_log", "云杉原木" },
                { "minecraft:iron_block", "铁块" },
                { "minecraft:gold_block", "金块" },
                { "minecraft:diamond_block", "钻石块" },
                { "minecraft:emerald_block", "绿宝石块" },
                { "minecraft:redstone_block", "红石块" },
                { "minecraft:lapis_block", "青金石块" },
                { "minecraft:coal_block", "煤炭块" },
                { "minecraft:quartz_block", "石英块" },
                { "minecraft:obsidian", "黑曜石" },
        };
        List<SearchPickerData.Candidate> candidates = new ArrayList<SearchPickerData.Candidate>();
        for (int i = 0; i < Math.min(CANDIDATE_COUNT, samples.length); i++) {
            candidates.add(new SearchPickerData.Candidate(samples[i][0], samples[i][1],
                    Collections.<SearchPickerData.Variant>emptyList()));
        }
        return new SearchPickerData.SearchResult(candidates);
    }

    /** 按查询串过滤（不区分大小写，命中 key 或 label）—— 面板走结果信号路径，过滤由装配层负责。 */
    private static SearchPickerData.SearchResult filteredCandidates(String query) {
        String needle = query.toLowerCase(java.util.Locale.ROOT);
        List<SearchPickerData.Candidate> matched = new ArrayList<SearchPickerData.Candidate>();
        for (SearchPickerData.Candidate candidate : allCandidates().candidates()) {
            if (candidate.key().toLowerCase(java.util.Locale.ROOT).contains(needle)
                    || candidate.label().toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                matched.add(candidate);
            }
        }
        return new SearchPickerData.SearchResult(matched);
    }
}
