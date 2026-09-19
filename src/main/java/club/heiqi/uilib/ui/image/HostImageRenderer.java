package club.heiqi.uilib.ui.image;

/**
 * 普通宿主图片渲染委托。
 *
 * <p>该轻量路径只处理 texture 与 bitmap；ItemStack 图标由 {@link ItemIconRenderer} 当帧直绘。
 * 自定义实现属于受信任的窄委托：架构禁令禁用原版包装类（Tessellator 等），绘制走直接 GL
 * 或 UILib 自有管线。</p>
 *
 * <p><strong>实现必须自己守恒的状态</strong>（attrib 帧与调用方事务都回收不了，实测口径见
 * {@code docs/历史报告/审查/2026-09-19-GL使用自净审查.md}）：client array
 * （{@code glEnableClientState} 一族）与 <strong>矩阵栈内容</strong>（栈帧本身不受 attrib 帧管辖），
 * 外加不得遗留 GL error。program 与 VAO/VBO 由帧围栏的显式快照兜底
 * （{@link club.heiqi.uilib.ui.host.UiFrameGlStateFence} 捕获并恢复 program、VAO、
 * array/element buffer），但在没有帧围栏的宿主路径上仍应由实现自己还原。
 * matrix mode <em>不</em>在此列——attrib 帧与帧围栏都能恢复它，实现仍应尽量还原。</p>
 *
 * <p><strong>固定管线状态由调用方回收</strong>：实现可以为自己这一次绘制设置 FFP 状态
 * （enable 位、blendFunc、colorMask/depthMask、color、depthFunc、纹理绑定、matrix mode 等），
 * 前提是调用点外层持有 {@code UiRenderTarget.begin()/end()}（含 attrib 帧）或
 * {@link club.heiqi.uilib.ui.host.UiFrameGlStateFence} 级别的回收。现网唯一调用点
 * {@code UiRenderContext.drawUncachedHostImage} 就在该事务内，并另行显式还原 matrix mode；
 * 新增调用点必须先满足该前提，否则本委托设置的 FFP 状态会直接漂移到宿主。</p>
 */
public interface HostImageRenderer extends AutoCloseable {

    /**
     * 在指定区域渲染宿主图片。
     *
     * @param source 图片源
     * @param left 目标区域左边界
     * @param top 目标区域上边界
     * @param right 目标区域右边界
     * @param bottom 目标区域下边界
     */
    void render(HostImageSource source, int left, int top, int right, int bottom);

    /** 释放 renderer 自身拥有的宿主资源。 */
    @Override
    default void close() {
        // 大多数普通图片 renderer 不持有资源。
    }
}
