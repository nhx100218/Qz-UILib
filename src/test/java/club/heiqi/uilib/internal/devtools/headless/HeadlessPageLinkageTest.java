package club.heiqi.uilib.internal.devtools.headless;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;

/**
 * headless 页面装配的<b>端到端</b>门禁：能在最小集类路径上装配的页面就应当真的出图，
 * 且本类交付的每一项能力都要有一条会红的判据。
 *
 * <h3>它守的回归（实测发生过一次）</h3>
 * <p>配置页接入时，它的装配入口与 MC 宿主包装在<b>同一个类</b>里。那个类在最小集类路径
 * （不含 {@code net.minecraft.*}）下<b>根本加载不了</b>——实测
 * {@code Class.forName("…ModernConfigEntry")} 抛
 * {@code NoClassDefFoundError: net/minecraft/client/gui/GuiScreen}，于是出图直接非 0 退出。
 * 修法是按「一个类要么是宿主、要么是装配」拆开（{@code ModernConfigEntry} /
 * {@code ModernConfigAssembly}）。</p>
 *
 * <p>触发点（独立复核的合成实验 + 单行变异定位）：<b>不是</b>「方法签名引用了 MC 类型」——
 * 仅出现在方法签名 / 字段类型 / {@code checkcast} / {@code instanceof} 里的 MC 类型都不触发；
 * 真实原因是校验期的可赋值性检查迫使 JVM 解析缺失的父类型（{@code return new ModernConfigScreen(…)}
 * 要证明它可赋给 {@code GuiScreen}）。故本类只钉端到端事实，不把机制推断写进断言。</p>
 *
 * <h3>为什么 {@code chat} / {@code hud} 的<b>渲染</b>不在覆盖内</h3>
 * <p>它们的探针宿主在<b>方法体</b>里使用 {@code net.minecraft.*}（{@code ChatComponentText} 等），
 * 本来就只在 {@code classpath-full.txt} 上可跑（规划 F21 记录）。那是注入面的事实，不是被测行为；
 * 把「页面需要哪份 classpath」固化成<b>渲染判据</b>只会变成假契约。但「缺件时<b>怎么报</b>」是契约
 * 本身：见 {@code missingMinecraftDependencyReportsContractExitCode}，它刻意用最小集跑 chat 当触发手段。</p>
 *
 * <h3>为什么 playground 也要测</h3>
 * <p>它不是回归对象，是<b>正锚</b>：若最小集本身缺件（natives 未解压、classpath 未重建），
 * 只测配置页会红得看不出成因。playground 通过即排除「环境没搭好」，剩下的红就是页面自己的问题。</p>
 *
 * <h3>为什么 section 轴与临时目录也各占一条</h3>
 * <p>独立复核用变异实测过：把 {@code ConfigScreen.showSection} 改成空操作、或摘掉
 * {@code HeadlessSession.close()} 里的 {@code hostCleanup.run()}，本类其余用例<b>全绿</b>
 * ——即「参数收下了但没接线」「痕迹没清掉」这两类静默降级没有被守住。三条判据各钉一项交付。</p>
 */
public class HeadlessPageLinkageTest {

    private static final Pattern COMMANDS = Pattern.compile("commands=(\\d+)");
    private static final Pattern COLORS = Pattern.compile("colors=(\\d+)");
    private static final String TEMP_DIR_PREFIX = "qz-headless-config-";

    /** 配置页在最小集上必须真的出图（本轮回归的守卫）。 */
    @Test
    public void configPageRendersOnTheMinimalClasspath() throws Exception {
        String output = render("linkage-config", "--page=config");
        Assert.assertTrue("配置页必须下发绘制命令（否则是装配失败而非「页面没内容」）：\n" + output,
                commandsOf(output) > 0);
        Assert.assertTrue("配置页自检必须通过：\n" + output, output.contains("self-check: ok"));
    }

    /** 正锚：最小集本身是好的（含 natives 与 classpath 注入）。 */
    @Test
    public void playgroundPageRendersOnTheMinimalClasspath() throws Exception {
        String output = render("linkage-playground", "--page=playground");
        Assert.assertTrue("playground 也必须出图；它失败说明最小集/注入面坏了，而不是被测页面：\n" + output,
                commandsOf(output) > 0);
    }

    /** section 轴必须真的切换内容（否则 {@code --page-index} 对 config 页是空话）。 */
    @Test
    public void configSectionAxisSwitchesContent() throws Exception {
        String output = render("linkage-section", "--page=config", "--page-indexes=0,1,2");
        Set<String> colors = new LinkedHashSet<String>();
        Matcher matcher = COLORS.matcher(output);
        while (matcher.find()) {
            colors.add(matcher.group(1));
        }
        // 三档各一条 self-check 行；三档颜色数必须两两不同（实测 1156 / 1066 / 1117）。
        Assert.assertEquals("三档 section 必须画出不同内容 —— 三档颜色数全同时说明切换没接线：\n" + output,
                3, colors.size());
    }

    /**
     * 归类判据必须区分 {@code NoClassDefFoundError} 的<b>两种成因</b>：类不在类路径上（换启动器有效）
     * vs 类在、但静态初始化已失败（erroneous 类，换启动器无效）。
     *
     * <p>后者是独立审核给出的 JVM 级反例：{@code <clinit>} 首次失败后，第二次触碰抛的
     * {@code NoClassDefFoundError} 消息是 {@code Could not initialize class X} 而不是类名。若不排除，
     * 那类<b>环境 / 初始化</b>缺陷会被误报成「启动器选错」，把处置指向错误的动作（同一异常类型既可能是
     * 缺件、也可能不是 —— 判据不能只看类型）。本判据不需要 GL 环境与进程外直启：直接构造异常实例。</p>
     */
    @Test
    public void erroneousClassIsNotClassifiedAsMissingClasspath() {
        Assert.assertEquals("erroneous 类不是类路径缺件，应归设施失败 3（带栈）：",
                3, HeadlessShotMain.diagnoseUnexpected(
                        new NoClassDefFoundError("Could not initialize class foo.Bar")));
        Assert.assertEquals("真正的缺件仍归 6：",
                HeadlessShotMain.EXIT_CLASSPATH_INSUFFICIENT,
                HeadlessShotMain.diagnoseUnexpected(
                        new NoClassDefFoundError("net/minecraft/util/IChatComponent")));
        Assert.assertEquals("ClassNotFoundException 同样归 6：",
                HeadlessShotMain.EXIT_CLASSPATH_INSUFFICIENT,
                HeadlessShotMain.diagnoseUnexpected(new ClassNotFoundException("foo.Bar")));
    }

    /**
     * 用最小集启动器跑需要完整类路径的页面（chat）：必须在**契约内**报「类路径缺件」。
     *
     * <p>它守的回归（实测发生过）：装配期抛出的 {@code NoClassDefFoundError} 此前直接冒泡出
     * {@code main}，JVM 以退出码 <b>1</b> 收场；更糟的是<b>逐档独立进程批量</b> —— 子进程各以 1 退出、
     * 父进程把它聚合算成「内容可疑(4)」并继续跑完剩余档。两者都不是「启动器选错」该有的表现。现由
     * {@code HeadlessShotMain.run} 的进程边界兜底归类为
     * {@link HeadlessShotMain#EXIT_CLASSPATH_INSUFFICIENT}，并给出可操作指引。</p>
     *
     * <p>这条判据刻意用 <b>最小集</b> 跑 chat（它触及 {@code net.minecraft.util.IChatComponent}）：
     * 「页面需要哪份 classpath」是注入面事实，此处只拿它当<b>触发手段</b>，钉的是退出码契约与指引 ——
     * 与类头「chat / hud 不在覆盖内」不矛盾：那条讲<b>渲染判据</b>不在最小集上跑，本判据钉的是
     * 「装配失败怎么报」。</p>
     */
    @Test
    public void missingMinecraftDependencyReportsContractExitCode() throws Exception {
        Shot shot = runShot("linkage-classpath", "--page=chat");
        HeadlessShotGate.assumeEnvironmentAvailable("linkage-classpath", shot.exit, shot.output);
        Assert.assertEquals("类路径缺件必须报契约内的 6（此前是未捕获错误 → 退出码 1，契约外）：\n"
                + shot.output, HeadlessShotMain.EXIT_CLASSPATH_INSUFFICIENT, shot.exit);
        Assert.assertTrue("诊断必须自证缺的是哪个类型：\n" + shot.output,
                shot.output.contains("CLASSPATH-INSUFFICIENT"));
        Assert.assertTrue("诊断必须给出可操作指引（换完整集启动器）：\n" + shot.output,
                shot.output.contains("qz-shot-full"));
    }

    /** 磨玻璃实验室在最小集上必须真的出图（页面覆盖：它此前只能靠开游戏看）。 */
    @Test
    public void glassPageRendersOnTheMinimalClasspath() throws Exception {
        String output = render("linkage-glass", "--page=glass");
        Assert.assertTrue("磨玻璃实验室必须下发绘制命令（否则是装配失败而非「页面没内容」）：\n" + output,
                commandsOf(output) > 0);
        Assert.assertTrue("磨玻璃实验室自检必须通过：\n" + output, output.contains("self-check: ok"));
    }

    /**
     * 玻璃必须走 shader 路径 —— 钉住 <b>2026-09-02 至 09-18 的静默降级回归</b>。
     *
     * <p>那段时期 {@code uiBackdropF.frag} 的注释里混入了中文，NVIDIA 编译器直接报
     * {@code error C0000: syntax error, unexpected $undefined}，程序不可用 ⇒ 玻璃一路降级到固定管线
     * （仅模糊、无 vibrancy / 亮边 / 噪点 / 液态折射），日志不报错、画面只是"没那么好看"。
     * 本判据钉的是端到端事实（本帧真的用了 shader），不是源字符集检查 —— 后者是另一条独立门禁
     * （{@code GlslSourceAsciiGuardTest}）。</p>
     */
    @Test
    public void glassBackdropUsesTheShaderPath() throws Exception {
        String output = render("linkage-glass-shader", "--page=glass");
        Assert.assertTrue("玻璃未走 shader 路径（先查 GLSL 资源是否含非 ASCII / 着色器是否编译失败）：\n" + output,
                output.contains("backdrop: path=shader"));
    }

    /**
     * glass 页必须真的接收 {@code --theme}：外观档注入是它的一项交付，不能只停在「参数收下了」。
     *
     * <p>独立复核用变异指出过这个覆盖缺口：把 {@code createHost} 里 glass 分支的 theme 传 {@code null} 时，
     * 本类其余 7 条 + {@code GlassLabHostThemeTest} 8 条 + {@code GlassLabHostTest} 5 条<b>全绿</b>
     * —— 即「glass 接收外观档」这项能力当时没有任何判据守着。本判据按颜色数两两不同来钉端到端事实
     * （实测 dark 16336 / light 14373；与 config 的 section 轴判据同型）。</p>
     */
    @Test
    public void glassThemeAxisSwitchesContent() throws Exception {
        String output = render("linkage-glass-theme", "--page=glass",
                "--themes=liquid-glass-dark,liquid-glass-light");
        Set<String> colors = new LinkedHashSet<String>();
        Matcher matcher = COLORS.matcher(output);
        while (matcher.find()) {
            colors.add(matcher.group(1));
        }
        Assert.assertEquals("两档外观必须画出不同内容（颜色数全同即主题没接到 glass 装配上）：\n" + output,
                2, colors.size());
    }

    /** 玻璃路径读数必须按「本次渲染窗口的请求计数」判定，不得把上一档的残留报成本档事实。 */
    @Test
    public void backdropPathIsNotInheritedAcrossPagesInOneProcess() throws Exception {
        String output = render("linkage-backdrop-shared", "--share-context", "--pages=glass,text-probe");
        Assert.assertEquals("glass 档应报 shader、text-probe 档应报 none（同进程复用时会暴露残留误报）：\n" + output,
                1, countOf(output, "backdrop: path=shader"));
        Assert.assertEquals("无玻璃请求的页面必须如实报 none：\n" + output,
                1, countOf(output, "backdrop: none"));
    }

    /** 出图不得在进程外留痕：临时配置目录跑完必须消失。 */
    @Test
    public void configPageLeavesNoTempDirectory() throws Exception {
        Set<String> before = tempConfigDirs();
        render("linkage-tempdir", "--page=config");
        Set<String> after = tempConfigDirs();
        after.removeAll(before);
        Assert.assertTrue("出图后残留了临时配置目录（会话清理没执行）：" + after, after.isEmpty());
    }

    /** 直启一次出图并返回进程输出；退出码非 0 即断言失败（附完整输出便于定因）。 */
    private static String render(String name, String... extraArgs) throws Exception {
        Shot shot = runShot(name, extraArgs);
        // 运行环境不具备（无 GL / natives 加载不了）时跳过，其余非 0 一律红。
        HeadlessShotGate.assumeEnvironmentAvailable(name, shot.exit, shot.output);
        Assert.assertEquals("headless 直启失败（" + name + " exit=" + shot.exit + "）：\n" + shot.output,
                0, shot.exit);
        // 单档落主名、多档落带轴后缀的名字，故只要求「同前缀至少一个产物」。
        List<Path> produced = sameStem(shot.outputPath);
        Assert.assertFalse("未产出 PNG（" + name + "）：\n" + shot.output, produced.isEmpty());
        return shot.output;
    }

    /**
     * 直启一次出图并返回结果，**不判定退出码** —— 供「预期失败」的判据使用。
     *
     * <p>与 {@link #render} 共用同一条直启链路（同 classpath 注入、同产物清理、同输出捕获），
     * 差别只有「谁来判退出码」：正常路径判 0，契约判据自己判期望值。</p>
     *
     * @param name      用例内产物名
     * @param extraArgs 追加参数
     * @return 退出码 + 完整输出 + 期望产物路径
     */
    private static Shot runShot(String name, String... extraArgs) throws Exception {
        String classpathFile = System.getProperty("qz.headless.classpathFile", "");
        Assume.assumeTrue("未注入 headless 直启 classpath，跳过页面可链接性门禁",
                !classpathFile.isEmpty() && new File(classpathFile).isFile());
        String nativesDir = System.getProperty("qz.headless.nativesDir", "");

        Path out = Paths.get("build", "reports", "headless", name + ".png").toAbsolutePath();
        Files.createDirectories(out.getParent());
        for (Path stale : sameStem(out)) {
            Files.deleteIfExists(stale);
        }

        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable);
        command.add("-Djava.library.path=" + nativesDir);
        command.add("-Xmx2g");
        command.add("-cp");
        command.add(new String(Files.readAllBytes(Paths.get(classpathFile)), StandardCharsets.UTF_8).trim());
        command.add("club.heiqi.uilib.internal.devtools.headless.HeadlessShotMain");
        for (String one : extraArgs) {
            command.add(one);
        }
        command.add("--size=1280x720");
        command.add("--out=" + out);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String text;
        InputStream stream = process.getInputStream();
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            text = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            stream.close();
        }
        int exit = process.waitFor();
        return new Shot(exit, text, out);
    }

    /** 一次直启的结果：退出码 + 完整输出 + 期望产物路径。 */
    private static final class Shot {
        private final int exit;
        private final String output;
        private final Path outputPath;

        Shot(int exit, String output, Path outputPath) {
            this.exit = exit;
            this.output = output;
            this.outputPath = outputPath;
        }
    }

    /** 与给定产物同前缀的 PNG（多档矩阵下会有多个）。 */
    private static List<Path> sameStem(Path out) throws Exception {
        String fileName = out.getFileName().toString();
        final String prefix = fileName.endsWith(".png")
                ? fileName.substring(0, fileName.length() - 4) : fileName;
        List<Path> found = new ArrayList<Path>();
        try (Stream<Path> stream = Files.list(out.getParent())) {
            for (Path path : (Iterable<Path>) stream.filter(candidate -> {
                String candidateName = candidate.getFileName().toString();
                return candidateName.startsWith(prefix) && candidateName.endsWith(".png");
            })::iterator) {
                found.add(path);
            }
        }
        return found;
    }

    /** 当前 {@code java.io.tmpdir} 下的临时配置目录名（用前后差集判定，避免算进并发进程的目录）。 */
    private static Set<String> tempConfigDirs() throws Exception {
        Set<String> found = new LinkedHashSet<String>();
        Path root = Paths.get(System.getProperty("java.io.tmpdir", "."));
        if (!Files.isDirectory(root)) {
            return found;
        }
        try (Stream<Path> stream = Files.list(root)) {
            for (Path path : (Iterable<Path>) stream.filter(candidate -> candidate.getFileName().toString()
                    .startsWith(TEMP_DIR_PREFIX))::iterator) {
                found.add(path.getFileName().toString());
            }
        }
        return found;
    }

    /** 统计子串在输出里出现的次数。 */
    private static int countOf(String output, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = output.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    /** 从输出里取命令面摘要的命令数；没有该行返回 -1。 */
    private static int commandsOf(String output) {
        Matcher matcher = COMMANDS.matcher(output);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : -1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
