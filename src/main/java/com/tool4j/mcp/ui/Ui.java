package com.tool4j.mcp.ui;

import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.datatransfer.StringSelection;

/**
 * 界面里反复用到的小零件：颜色、字体、间距、小圆点图标。
 *
 * <p>刻意只用最稳的 Swing / IntelliJ 基础 API（{@code JBColor}、{@code JBUI}、{@code UIManager}），
 * 不碰版本之间容易变动的主题常量，这样在 2023.3 之后的新版本上也不会因为一个主题类改名就崩。
 * 所有颜色都成对声明（亮色主题 / 暗色主题），运行时由 {@link JBColor} 自动切换。
 */
public final class Ui {

    /** 已连接 / 成功。 */
    public static final JBColor OK = new JBColor(new Color(0x1F7A3D), new Color(0x4CAF50));
    /** 提示 / 进行中。 */
    public static final JBColor WARN = new JBColor(new Color(0xB8791A), new Color(0xE0A44A));
    /** 失败 / 破坏性操作。 */
    public static final JBColor ERROR = new JBColor(new Color(0xC0392B), new Color(0xFF6B68));
    /** 次要信息。 */
    public static final JBColor MUTED = new JBColor(new Color(0x7A7E85), new Color(0x9BA1A8));
    /** 出站报文。 */
    public static final JBColor TRAFFIC_OUT = new JBColor(new Color(0x1A5FB4), new Color(0x7CB2F0));
    /** 入站报文。 */
    public static final JBColor TRAFFIC_IN = new JBColor(new Color(0x1F7A3D), new Color(0x63C58A));
    /** 子进程 stderr / 传输层提示。 */
    public static final JBColor TRAFFIC_NOTICE = new JBColor(new Color(0x9A6A00), new Color(0xD9A94A));

    private Ui() {
    }

    // ------------------------------------------------------------------
    // 布局
    // ------------------------------------------------------------------

    /** 给组件套一圈内边距，省掉到处写空的 JPanel。 */
    public static JPanel wrap(JComponent content, int top, int left, int bottom, int right) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(top, left, bottom, right));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    public static JPanel wrap(JComponent content, int topBottom, int leftRight) {
        return wrap(content, topBottom, leftRight, topBottom, leftRight);
    }

    /** 水平排列，剩余空间给最后一个可见组件。 */
    public static JPanel hbox(Component... components) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        for (Component c : components) {
            panel.add(c);
        }
        return panel;
    }

    /** 垂直排列。 */
    public static JPanel vbox(Component... components) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (Component c : components) {
            panel.add(c);
        }
        return panel;
    }

    public static Component hgap(int width) {
        return Box.createRigidArea(new Dimension(JBUI.scale(width), JBUI.scale(1)));
    }

    public static Component vgap(int height) {
        return Box.createRigidArea(new Dimension(JBUI.scale(1), JBUI.scale(height)));
    }

    // ------------------------------------------------------------------
    // 表单控件尺寸
    // ------------------------------------------------------------------

    /**
     * 参数类单行控件的紧凑高度。
     *
     * <p>不给尺寸时走 LAF 默认，在窄边栏里偏胖；统一压到 22，一行省几像素，
     * 十来个参数叠起来就是"一屏能多看两三个"。
     */
    public static final int INPUT_HEIGHT = 22;

    /** 参数类单行控件的默认宽度上限。跟着可用宽度走，但窗口拉宽了也不让它无限变长。 */
    public static final int INPUT_MAX_WIDTH = 220;

    public static <T extends JComponent> T compactInput(T component) {
        return compactInput(component, INPUT_MAX_WIDTH);
    }

    /**
     * 把单行控件压成紧凑尺寸：高度固定，宽度可拉伸但有上限。
     *
     * <p>宽度上限靠 {@code maximumSize} 生效——这类字段通常被塞在 Y 轴 {@code BoxLayout}
     * 里，而它正是按 {@code maximumSize} 决定要不要横向拉满的。
     */
    public static <T extends JComponent> T compactInput(T component, int maxWidth) {
        int height = JBUI.scale(INPUT_HEIGHT);
        int width = JBUI.scale(maxWidth);
        component.setPreferredSize(new Dimension(width, height));
        component.setMinimumSize(new Dimension(JBUI.scale(60), height));
        component.setMaximumSize(new Dimension(width, height));
        return component;
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    /** 灰色小字提示。 */
    public static JBLabel hint(String text) {
        JBLabel label = new JBLabel(text);
        label.setForeground(MUTED);
        label.setFont(smaller(label.getFont()));
        return label;
    }

    /** 灰色小字提示，支持 HTML 换行。 */
    public static JBLabel htmlHint(String html) {
        JBLabel label = new JBLabel("<html>" + html + "</html>");
        label.setForeground(MUTED);
        label.setFont(smaller(label.getFont()));
        return label;
    }

    /**
     * 等宽只读文本块，外层套滚动条。
     *
     * <p>行数少就直接铺开（交给外层滚动条统一滚），行数多就套一个固定高度的内层滚动区，
     * 免得超长文本把整个布局撑爆。
     *
     * <p>之所以永远套内层滚动：长行会软换行，行数不等于显示行数，靠 {@code rows} 算高度必错。
     *
     * @param maxHeight &lt;= 0 表示强制铺开
     */
    public static JComponent textBody(String text, int maxHeight, JBColor colorOverride) {
        String content = text == null ? "" : text;
        JBTextArea area = new JBTextArea(content);
        area.setEditable(false);
        area.setFont(monospace());
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty(2, 4));
        if (colorOverride != null) {
            area.setForeground(colorOverride);
        }

        int lineHeight = Math.max(12, area.getFontMetrics(area.getFont()).getHeight());
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setBorder(JBUI.Borders.empty());
        int natural = lines * lineHeight + JBUI.scale(14);
        int limit = maxHeight > 0 ? JBUI.scale(maxHeight) : natural;
        scroll.setPreferredSize(new Dimension(0, Math.min(Math.max(natural, JBUI.scale(36)), limit)));
        return scroll;
    }

    /** 段落标题：加粗 + 下面一条细分隔线。 */
    public static JComponent sectionTitle(String text) {
        JBLabel label = new JBLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(label, BorderLayout.WEST);
        panel.setBorder(BorderFactory.createCompoundBorder(
                JBUI.Borders.emptyTop(2),
                JBUI.Borders.emptyBottom(4)));
        return panel;
    }

    public static Font smaller(Font base) {
        return base.deriveFont(Math.max(9f, base.getSize2D() - 1f));
    }

    public static Font monospace(float size) {
        Font base = UIManager.getFont("Label.font");
        float s = size > 0 ? size : (base == null ? 12f : base.getSize2D());
        return new Font(Font.MONOSPACED, Font.PLAIN, Math.round(s));
    }

    public static Font monospace() {
        return monospace(0);
    }

    /** 标题用的加粗字体。 */
    public static Font bold(Font base) {
        return base.deriveFont(Font.BOLD);
    }

    // ------------------------------------------------------------------
    // 颜色
    // ------------------------------------------------------------------

    public static String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ------------------------------------------------------------------
    // 小图标
    // ------------------------------------------------------------------

    /** 状态用的小圆点。 */
    public static Icon dot(Color color, int diameter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(color);
                    int d = JBUI.scale(diameter);
                    g2.fillOval(x, y + (getIconHeight() - d) / 2, d, d);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return JBUI.scale(diameter + 3);
            }

            @Override
            public int getIconHeight() {
                return JBUI.scale(14);
            }
        };
    }

    public static Icon dot(JBColor color) {
        return dot((Color) color, 8);
    }

    /** 空心小圆点：树里的叶子节点用它，实心点只留给分组标题，视觉上有主次。 */
    public static Icon bullet(JBColor color, int diameter) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int d = JBUI.scale(diameter);
                    g2.setColor(color);
                    g2.setStroke(new java.awt.BasicStroke(1.2f));
                    g2.drawOval(x + 1, y + (getIconHeight() - d) / 2, d, d);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return JBUI.scale(diameter + 3);
            }

            @Override
            public int getIconHeight() {
                return JBUI.scale(14);
            }
        };
    }

    // ------------------------------------------------------------------
    // 工具栏小零件
    // ------------------------------------------------------------------

    /**
     * 一个即点即走的图标动作（不需要 update 状态的按钮用它）。
     *
     * <p><b>图标一定要给。</b>平台在 {@code ActionButton} 里的逻辑是
     * "拿不到图标就用 {@code AllIcons.Toolbar.Unknown} 顶上"，那就是界面上那一排问号的来源；
     * 这跟有没有写 text 无关，工具栏是按图标渲染的。
     */
    public static DumbAwareAction iconAction(@NotNull String text, @NotNull String description,
                                             @NotNull Icon icon, @NotNull Runnable onClick) {
        return new DumbAwareAction(text, description, icon) {
            @Override
            public void actionPerformed(@NotNull AnActionEvent e) {
                onClick.run();
            }
        };
    }

    /**
     * 一排图标按钮。走平台的 {@link ActionToolbar} 而不是裸 {@code JButton}：
     * 只有这条路才会拿到 IDE 一致的悬停高亮、禁用态和 tooltip 行为。
     */
    public static JComponent iconToolbar(@NotNull String place, @NotNull JComponent target,
                                         @NotNull AnAction... actions) {
        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar(place, new DefaultActionGroup(actions), true);
        toolbar.setTargetComponent(target);
        return toolbar.getComponent();
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    public static void copyToClipboard(String text) {
        if (text == null) {
            text = "";
        }
        CopyPasteManager.getInstance().setContents(new StringSelection(text));
    }

    public static String ellipsize(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        return t.length() <= max ? t : t.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** 分隔线，颜色跟随 LAF。 */
    public static JComponent separator() {
        JPanel line = new JPanel();
        line.setOpaque(true);
        line.setBackground(JBColor.border());
        line.setPreferredSize(new Dimension(1, JBUI.scale(1)));
        line.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(1)));
        return line;
    }
}
