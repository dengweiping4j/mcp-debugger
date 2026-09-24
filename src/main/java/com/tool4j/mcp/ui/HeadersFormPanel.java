package com.tool4j.mcp.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.KeyValue;

import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;

/**
 * 请求头的<b>表单</b>编辑器——一行一个头，两处共用。
 *
 * <p><b>为什么两级共用同一套控件。</b>服务器级（{@code ServerHeadersDialog} 弹窗）与工具级
 * （{@link RequestHeadersPanel} 页签）编辑的是同一件事、同一份数据结构，差别只在"住在哪儿"
 * 和"什么时候落盘"。表单本身<b>不碰任何配置对象</b>：它只负责把 {@code List<KeyValue>} 显示出来、
 * 让用户改、再交回去（{@link #setRows} / {@link #getRows}）。何时写进配置、写进哪一级，
 * 由外层决定——弹窗那边等"确定"，页签那边每敲一下都写。
 *
 * <p><b>为什么不做成 JSON 输入框。</b>手写 JSON 要记住头名、记住引号，敲错一个引号整块不生效；
 * 而请求头天然是"名字 + 值"两张表。键那一列带一个下拉（{@link #COMMON_HEADERS}），
 * 常用头点一下就有，值也顺手带上推荐内容（选 Authorization 自动填 {@code Bearer }），
 * 剩下的手填即可——和 Postman 的用法一致。
 *
 * <p><b>打码只影响显示。</b>键名命中 {@link KeyValue#looksSensitive} 时值默认打成圆点，
 * 「显示敏感值」勾上就还原。真正发出去、存进配置的始终是原文。
 * 默认<b>明文</b>（勾选框初始为选中）：这里是开发者自己的调试台，token 看不见反而让人以为没填上；
 * 需要演示 / 截图时再取消勾选。
 */
public final class HeadersFormPanel extends JPanel {

    private static final String CARD_LIST = "list";
    private static final String CARD_EMPTY = "empty";

    /**
     * 输入框高度。
     *
     * <p><b>刻意不取 {@link Ui#INPUT_HEIGHT}</b>（22，参数表单那边统一用的紧凑值）：
     * 那边一行动辄十来个控件，高度得省着用；这里一行只有「键 + 值」两个，而键列还要占掉
     * 一截宽度，值框常常只剩一两百像素——高度给足一点，才像个"能填长 token 的地方"。
     * 两处的取舍不同，所以不要为了"统一"把它们拉回同一个值。
     */
    private static final int FIELD_HEIGHT = 28;
    /** 行间距。用行的 bottom border 实现，这样 BorderLayout 会把内容区精确限制在 FIELD_HEIGHT。 */
    private static final int ROW_GAP = 4;
    /** 行尾删除按钮的宽度。 */
    private static final int REMOVE_WIDTH = 20;

    /**
     * 打码字符。{@code (char) 0} 是 {@code JPasswordField.setEchoChar} 约定的"原文显示"，
     * 所以明 / 暗切换只要改这一个值，不必换控件（换控件会把焦点和光标一起弄丢）。
     */
    private static final char MASK_CHAR = '\u2022';

    /**
     * 下拉里给出的常用头：{@code {名称, 选中后自动填入的推荐值}}。
     *
     * <p>空串表示"只填名字"——像 {@code X-Trace-Id} 这种没有通用取值的，硬塞一个反而要删。
     * 名称是 HTTP 协议里的固定写法，<b>不翻译</b>，所以不占词条。
     */
    private static final String[][] COMMON_HEADERS = {
            {"Accept", "application/json, text/event-stream"},
            {"Authorization", "Bearer "},
            {"Content-Type", "application/json"},
            {"MCP-Protocol-Version", ""},
            {"User-Agent", "mcp-debugger"},
            {"X-Api-Key", ""},
            {"X-Request-Id", ""},
            {"X-Trace-Id", ""},
    };

    /** 键列的宽度（逻辑像素）。弹窗里给得宽些，页签里只有两三百像素，得省着用。 */
    private final int keyWidth;

    private final JPanel rowsHost = new JPanel();
    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JBLabel emptyLabel;
    private final JBScrollPane scroll;
    private final JButton addButton = new JButton();
    private final JBCheckBox reveal = new JBCheckBox();

    private final List<Row> rows = new ArrayList<>();

    /** 回填时抑制监听：{@code setText} 自己也会发文档事件，不能把它当成用户编辑。 */
    private boolean loading;
    /** 内容变化回调；由外层决定拿它干什么（写配置、刷新页签标题上的条数）。 */
    private Runnable onChanged = () -> {
    };

    /**
     * @param keyWidth 键列宽度（逻辑像素）
     */
    public HeadersFormPanel(int keyWidth) {
        super(new BorderLayout());
        setOpaque(false);
        this.keyWidth = keyWidth;

        rowsHost.setOpaque(false);
        rowsHost.setLayout(new BoxLayout(rowsHost, BoxLayout.Y_AXIS));

        scroll = new JBScrollPane(rowsHost);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setPreferredSize(new Dimension(JBUI.scale(240), JBUI.scale(150)));

        emptyLabel = Ui.hint(I18n.t("hdr.form.empty"));

        content.setOpaque(false);
        content.add(scroll, CARD_LIST);
        content.add(Ui.wrap(emptyLabel, 8, 6, 8, 6), CARD_EMPTY);

        addButton.setFocusable(false);
        addButton.addActionListener(e -> appendRow());

        reveal.setOpaque(false);
        // 默认明文：看不见 token 会让人以为"没填上"。要挡肩窥时取消勾选即可。
        reveal.setSelected(true);
        reveal.addActionListener(e -> applyMasking());

        JPanel footer = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        footer.setOpaque(false);
        footer.setBorder(JBUI.Borders.emptyTop(4));
        footer.add(addButton, BorderLayout.WEST);
        footer.add(reveal, BorderLayout.EAST);

        add(content, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        applyTexts();
        syncEmptyState();
    }

    /** 内容变化回调（只在用户真的改了东西时触发，回填不算）。 */
    public void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> {
        } : listener;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    /**
     * 全量回填。传 null / 空表即清空。
     *
     * <p>回填期间所有事件都被当成"不是用户改的"——否则每次刷新面板都会顺手把配置重写一遍
     * （列表顺序、trim 过的键名都会变），用户正在敲的那行还可能被打断。
     */
    public void setRows(@Nullable List<KeyValue> values) {
        loading = true;
        try {
            for (Row row : new ArrayList<>(rows)) {
                detach(row);
            }
            rows.clear();
            rowsHost.removeAll();
            if (values != null) {
                for (KeyValue kv : values) {
                    if (kv != null) {
                        attach(new Row(kv.getKey() == null ? "" : kv.getKey(),
                                kv.getValue() == null ? "" : kv.getValue()));
                    }
                }
            }
        } finally {
            loading = false;
        }
        syncEmptyState();
        rowsHost.revalidate();
        rowsHost.repaint();
    }

    /** 当前内容：空键的行会被丢掉（点了「添加一行」却没填是常事）。 */
    public List<KeyValue> getRows() {
        List<KeyValue> out = new ArrayList<>(rows.size());
        for (Row row : rows) {
            String key = row.keyText();
            if (key.isEmpty()) {
                continue;
            }
            out.add(new KeyValue(key, row.valueText()));
        }
        return out;
    }

    /** 生效条数（空键不算）。 */
    public int count() {
        return getRows().size();
    }

    // ------------------------------------------------------------------
    // 增删
    // ------------------------------------------------------------------

    /** 「添加一行」：新行直接给焦点，省得再点一下才能敲键名。 */
    private void appendRow() {
        Row row = new Row("", "");
        attach(row);
        syncEmptyState();
        rowsHost.revalidate();
        rowsHost.repaint();
        row.focusKey();
    }

    /**
     * 把一行挂进列表。<b>刻意不回调</b>：一个空键的行不算内容变化（{@link #getRows()} 会把它滤掉，
     * 条数不变），为它通知一次外层等于让页签标题白刷一遍。
     */
    private void attach(Row row) {
        rows.add(row);
        rowsHost.add(row.panel);
        row.applyMasking();
    }

    /** 摘掉一行。真正的通知由调用方（{@link #removeRow}）负责，回填走的路径不需要它。 */
    private void detach(Row row) {
        rows.remove(row);
        rowsHost.remove(row.panel);
    }

    private void removeRow(Row row) {
        if (loading) {
            return;
        }
        detach(row);
        syncEmptyState();
        rowsHost.revalidate();
        rowsHost.repaint();
        fireChanged();
    }

    private void fireChanged() {
        if (!loading) {
            onChanged.run();
        }
    }

    /** 一个头都没有时给一句提示，别留一片空白让人猜这里能不能点。 */
    private void syncEmptyState() {
        cards.show(content, rows.isEmpty() ? CARD_EMPTY : CARD_LIST);
    }

    private void applyMasking() {
        for (Row row : rows) {
            row.applyMasking();
        }
    }

    /** 按当前语言重贴本文案；不碰任何数据，可随时安全调用。 */
    public void applyTexts() {
        addButton.setText(I18n.t("hdr.form.add"));
        reveal.setText(I18n.t("ui.reveal"));
        reveal.setToolTipText(I18n.t("ui.revealTooltip"));
        emptyLabel.setText(I18n.t("hdr.form.empty"));
        for (Row row : rows) {
            row.applyTexts();
        }
    }

    // ------------------------------------------------------------------
    // 一行
    // ------------------------------------------------------------------

    private final class Row {

        private final JPanel panel = new JPanel(new BorderLayout());
        private final JBTextField keyField = new JBTextField();
        private final JButton pickButton = new JButton();
        private final JBPasswordField valueField = new JBPasswordField();
        private final JButton removeButton = new JButton();

        Row(String key, String value) {
            panel.setOpaque(false);
            // 间距走 bottom border：BorderLayout 扣掉 insets 之后，内容区正好是 FIELD_HEIGHT
            panel.setBorder(JBUI.Borders.emptyBottom(ROW_GAP));
            panel.setPreferredSize(new Dimension(JBUI.scale(160), JBUI.scale(FIELD_HEIGHT + ROW_GAP)));
            panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, JBUI.scale(FIELD_HEIGHT + ROW_GAP)));

            keyField.setText(key);
            keyField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    onKeyEdited();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    onKeyEdited();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    onKeyEdited();
                }
            });

            pickButton.setIcon(chevron());
            pickButton.setFocusable(false);
            pickButton.setMargin(JBUI.insets(0));
            pickButton.setPreferredSize(new Dimension(JBUI.scale(REMOVE_WIDTH), JBUI.scale(FIELD_HEIGHT)));
            pickButton.addActionListener(e -> showCommonHeaders());

            JPanel keyBox = new JPanel(new BorderLayout(JBUI.scale(2), 0));
            keyBox.setOpaque(false);
            keyBox.add(keyField, BorderLayout.CENTER);
            keyBox.add(pickButton, BorderLayout.EAST);
            keyBox.setPreferredSize(new Dimension(JBUI.scale(keyWidth), JBUI.scale(FIELD_HEIGHT)));

            valueField.setEchoChar(MASK_CHAR);
            valueField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    fireChanged();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    fireChanged();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    fireChanged();
                }
            });

            removeButton.setIcon(cross());
            removeButton.setFocusable(false);
            removeButton.setMargin(JBUI.insets(0));
            removeButton.setPreferredSize(new Dimension(JBUI.scale(REMOVE_WIDTH), JBUI.scale(FIELD_HEIGHT)));
            removeButton.addActionListener(e -> removeRow(this));

            panel.add(keyBox, BorderLayout.WEST);
            panel.add(valueField, BorderLayout.CENTER);
            panel.add(removeButton, BorderLayout.EAST);

            applyTexts();
            applyMasking();
        }

        String keyText() {
            String text = keyField.getText();
            return text == null ? "" : text.trim();
        }

        /** 走 {@code getPassword()} 而不是 {@code getText()}：后者在 JPasswordField 上是废弃 API。 */
        String valueText() {
            char[] chars = valueField.getPassword();
            return chars == null ? "" : new String(chars);
        }

        void focusKey() {
            keyField.requestFocusInWindow();
        }

        void applyTexts() {
            keyField.getEmptyText().setText(I18n.t("hdr.form.keyEmpty"));
            valueField.getEmptyText().setText(I18n.t("hdr.form.valueEmpty"));
            pickButton.setToolTipText(I18n.t("hdr.form.pick"));
            removeButton.setToolTipText(I18n.t("hdr.form.remove"));
        }

        /** 键名变了要重算这一行该不该打码（token / key / secret 那几类才打）。 */
        private void onKeyEdited() {
            applyMasking();
            fireChanged();
        }

        void applyMasking() {
            boolean mask = !reveal.isSelected() && KeyValue.looksSensitive(keyText());
            valueField.setEchoChar(mask ? MASK_CHAR : (char) 0);
        }

        /** 常用头下拉。只列名字太干，选完顺手把推荐值填上（已有内容则一律不动）。 */
        private void showCommonHeaders() {
            JPopupMenu menu = new JPopupMenu();
            for (String[] preset : COMMON_HEADERS) {
                JMenuItem item = new JMenuItem(preset[0]);
                item.addActionListener(e -> {
                    keyField.setText(preset[0]);
                    if (!preset[1].isEmpty() && valueText().isEmpty()) {
                        valueField.setText(preset[1]);
                    }
                    // 光标落到值末尾：选完接着敲 token，不用先按 End
                    valueField.setCaretPosition(valueText().length());
                    valueField.requestFocusInWindow();
                });
                menu.add(item);
            }
            menu.show(pickButton, 0, pickButton.getHeight());
        }
    }

    // ------------------------------------------------------------------
    // 自绘小图标
    // ------------------------------------------------------------------

    /**
     * 键列那个下拉箭头。
     *
     * <p>自己画而不是用 {@code AllIcons}：平台图标常量名跨版本会动，而这个形状简单到不值得
     * 为此承担一次 {@code NoSuchFieldError} 的风险。
     */
    private static Icon chevron() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c.getForeground());
                    int w = JBUI.scale(7);
                    int h = JBUI.scale(4);
                    int left = x + (getIconWidth() - w) / 2;
                    int top = y + (getIconHeight() - h) / 2;
                    g2.fillPolygon(new int[]{left, left + w, left + w / 2},
                            new int[]{top, top, top + h}, 3);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return JBUI.scale(9);
            }

            @Override
            public int getIconHeight() {
                return JBUI.scale(9);
            }
        };
    }

    /** 行尾那个删除叉，理由同上（自绘，不依赖平台图标名）。 */
    private static Icon cross() {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(c.getForeground());
                    g2.setStroke(new BasicStroke(1.4f));
                    int pad = JBUI.scale(3);
                    int size = JBUI.scale(7);
                    g2.drawLine(x + pad, y + pad, x + pad + size, y + pad + size);
                    g2.drawLine(x + pad + size, y + pad, x + pad, y + pad + size);
                } finally {
                    g2.dispose();
                }
            }

            @Override
            public int getIconWidth() {
                return JBUI.scale(13);
            }

            @Override
            public int getIconHeight() {
                return JBUI.scale(13);
            }
        };
    }
}
