package club.heiqi.uilib.ui.render;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * GL 自净修复（审查 P2 批）的结构契约：异常窗口必须与正常路径共用同一个保护域。
 *
 * <p>为什么用源码结构而不是替身：{@code UiRenderTarget} / {@code UiRenderContext} / {@code FontShaderProgram}
 * 直接链接 LWJGL 静态方法与固定管线立即模式，没有可注入的状态缝隙，异常分支在纯 JVM 里无法触发；
 * 而这些修复的目标恰恰只有异常路径才体现（正常路径改动前后逐位相同）。
 * 断言只钉「结构不变量」（回绑在 finally 内、保护域在 try 内、复位与 close 解耦），改回去即红。</p>
 *
 * <p>对应条目见 {@code docs/历史报告/审查/2026-09-19-GL使用自净审查.md} 的 N2 / N3 / N5 / N7 / N16。</p>
 */
public class GlStateRecoveryContractTest {

    private static final Path RENDER_TARGET = Paths.get(
            "src/main/java/club/heiqi/uilib/ui/render/UiRenderTarget.java");
    private static final Path CLIP_STACK = Paths.get(
            "src/main/java/club/heiqi/uilib/ui/render/ClipStack.java");
    private static final Path RENDER_CONTEXT = Paths.get(
            "src/main/java/club/heiqi/uilib/ui/render/UiRenderContext.java");
    private static final Path BACKDROP = Paths.get(
            "src/main/java/club/heiqi/uilib/ui/render/UiBackdropFilterRenderer.java");
    private static final Path FONT_SHADER = Paths.get(
            "src/main/java/club/heiqi/uilib/font/shader/FontShaderProgram.java");

    /** N2：三处回贴的纹理回绑必须在 finally 内——异常路径同样要还原入口绑定。 */
    @Test
    public void textureRebindingHappensInsideFinally() throws Exception {
        String source = source(RENDER_TARGET);
        assertEquals("三处回贴都要读回原值并回绑", 3,
                occurrences(source, "GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTextureBinding);"));
        assertTrue("必须真的读回入口绑定", source.contains("int previousTextureBinding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);"));
        assertFalse("回绑必须搬进 finally，不能停在 try 体末尾",
                source.contains("previousTextureBinding);\n        } finally {"));
    }

    /** N3：圆角掩码重建的写掩码复原必须在 finally 内——否则 colorMask 全关被悬挂，本帧后续绘制静默不写色。 */
    @Test
    public void roundedClipMaskRestoresWriteMasksInFinally() throws Exception {
        String body = blockAfter(source(CLIP_STACK), "private static void rebuildRoundedClipMask(");
        int finallyToken = body.indexOf("} finally {");
        assertTrue("掩码重建必须带 finally", finallyToken >= 0);
        assertTrue("colorMask 复原必须在 finally 块体内",
                body.indexOf("ops.colorMask(true, true, true, true);", finallyToken) > finallyToken);
        assertTrue("depthMask 复原必须在 finally 块体内",
                body.indexOf("ops.depthMask(true);", finallyToken) > finallyToken);
    }

    /** N5：文本绘制的矩阵弹出必须包在 finally 内——委托的是第三方字体实现。 */
    @Test
    public void textMatrixPopHappensInsideFinally() throws Exception {
        String source = source(RENDER_CONTEXT);
        assertTrue("至少一处文本绘制把 glPopMatrix 放进了 finally",
                occurrences(source, "} finally {\n            GL11.glPopMatrix();") >= 1);
        assertFalse("不存在 push 后直接 pop 的裸序列",
                source.contains("GL11.glPopMatrix();\n        notifyMainLayerContentChanged();"));
    }

    /** N7：背景滤镜的保护域（clip 栈 + attrib 帧）必须建立在 try 之内。 */
    @Test
    public void backdropProtectionDomainStartsInsideTry() throws Exception {
        String source = source(BACKDROP);
        assertTrue("必须用标志位条件化恢复", source.contains("boolean clipPushed = false;")
                && source.contains("boolean attribPushed = false;"));
        assertFalse("pushClip / pushAttrib 不得留在 try 外",
                source.contains("context.pushClip(left, top, right, bottom, 0);\n        GL11.glPushAttrib"));
    }

    /** N16：初始化失败的复位必须与 close 解耦——close 自身在 GL 入口不可用时也会抛。 */
    @Test
    public void shaderInitializeResetsStateEvenWhenCloseFails() throws Exception {
        String source = source(FONT_SHADER);
        assertEquals("两个失败分支（RuntimeException / Error）都要走同一复位入口", 2,
                occurrences(source, "resetAfterFailedInitialize();"));
        assertTrue("复位必须把 initialized 的归位放进 finally",
                source.contains("try {\n            close();\n        } finally {\n            initialized.set(false);\n        }"));
    }

    /** 读取 UTF-8 生产源码。 */
    private static String source(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    /** 取标记之后第一个 '{' 起配平的块体。 */
    private static String blockAfter(String source, String marker) {
        int markerIndex = source.indexOf(marker);
        assertTrue("缺少标记：" + marker, markerIndex >= 0);
        int openingBrace = source.indexOf('{', markerIndex);
        assertTrue("缺少起始花括号：" + marker, openingBrace >= 0);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(openingBrace + 1, index);
                }
            }
        }
        fail("块体花括号未配平：" + marker);
        return "";
    }

    /** 统计固定源码片段出现次数。 */
    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int index = 0; (index = source.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }
}
