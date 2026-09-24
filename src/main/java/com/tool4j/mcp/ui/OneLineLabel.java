package com.tool4j.mcp.ui;

import com.intellij.ui.components.JBLabel;

import java.awt.Dimension;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.Locale;

/**
 * 单行省略号标签：一行放不下就按像素截断加「…」，完整内容挂在 tooltip 上。
 *
 * <p>是为"工具 / 资源 / 提示词的描述"这类文字做的：它常常是服务端给的一大段话，
 * 全摊开要吃掉三四行高度，而用户点开一个条目时往往只想扫一眼"这是干什么的"，
 * 真想读完再 hover 看全文。
 *
 * <p><b>为什么不用现成的两个控件</b>：
 * <ul>
 *   <li>{@code JBLabel} 既不打省略号也不管宽度 —— 它按整句话报首选宽度，
 *   于是这段话会顺着布局往上传染，把自己所在的那一栏撑宽
 *   （左侧目录/右侧详情那个分隔条能被拖到哪，就是被这类"最小宽度=整句话宽度"的控件顶住的）。</li>
 *   <li>{@code JTextArea} 折行能显示全，代价是固定吃掉 44~48px 高度、还得再套一层滚动条，
 *   而大多数描述其实只有一句话。</li>
 * </ul>
 *
 * <p><b>两条使用约定</b>：
 * <ul>
 *   <li>换内容用 {@link #setFullText(String)}，别直接 {@code setText}——被覆写的显示文字
 *   会在下一次尺寸变化时被重新截断回去。</li>
 *   <li>首选宽度是 0，宽度由外层给（{@code BorderLayout.CENTER}、{@code BoxLayout.X_AXIS} 都行）；
 *   放进 {@code BorderLayout.WEST} 会被压成 0 宽，什么都看不见。</li>
 * </ul>
 */
public final class OneLineLabel extends JBLabel {

    /** tooltip 里正文的折行宽度。不限制的话超长描述会摊成横贯屏幕的一条线，没法读。 */
    private static final int TOOLTIP_WIDTH = 360;

    /** 完整内容（原文，含换行）；显示用的那份是它拍平 + 按当前宽度截断后的结果。 */
    private String fullText = "";

    public OneLineLabel() {
        // 宽度变了就得重算截断点：首次上屏、面板拉宽、切语言（文案变长变短）都会走到这
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayout();
            }
        });
    }

    /** 换内容：显示的是截断版，tooltip 永远是完整版。 */
    public void setFullText(String text) {
        fullText = text == null ? "" : text;
        // 空内容别留一个空 tooltip —— 鼠标移入时弹出一个空气泡比没有更奇怪
        setToolTipText(fullText.isBlank() ? null : tooltipHtml(fullText));
        relayout();
    }

    /**
     * 首选宽度固定报 0：宽度交回外层容器。
     *
     * <p>高度仍按真实文字算（单行，与内容长短无关），外层拿它算行高是对的。
     */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, super.getPreferredSize().height);
    }

    private void relayout() {
        int available = getWidth() - getInsets().left - getInsets().right;
        String flat = flatten(fullText);
        // 还没上屏（宽度 0）时先放完整文字：高度是对的，宽度由外层给；上屏后 componentResized 会再截一次
        String shown = available <= 0 ? flat : Ui.truncateToWidth(this, flat, available);
        // 内容没变就别 setText：换文字会触发一次 revalidate，改宽度又会回到这里
        if (!shown.equals(getText())) {
            super.setText(shown);
        }
    }

    /**
     * 拍平成单行：{@code JLabel}（非 HTML 模式）不认换行符，画出来是一个方框。
     *
     * <p>顺带挡掉"以 {@code <html} 开头"的文字：那种串会被 {@code JLabel} 当标记解析，
     * 等于服务端给的描述有了改界面的能力。补一个前导空格就能让它回到纯文本。
     */
    private static String flatten(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String flat = text.replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ').strip();
        return flat.toLowerCase(Locale.ROOT).startsWith("<html") ? " " + flat : flat;
    }

    /**
     * tooltip 正文：转义 + 保留原有换行。
     *
     * <p>描述里常有分点列表、缩进这类结构，全压成一行会读不出来。所以 tooltip 走 HTML 渲染：
     * {@code <div width>} 让过长的行折行，{@code <br>} 把原来的换行留住。
     */
    private static String tooltipHtml(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return "<html><div width='" + TOOLTIP_WIDTH + "px'>"
                + Ui.escapeHtml(normalized).replace("\n", "<br>")
                + "</div></html>";
    }
}
