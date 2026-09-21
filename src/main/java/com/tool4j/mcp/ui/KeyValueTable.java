package com.tool4j.mcp.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.KeyValue;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境变量 / 请求头的键值表格。
 *
 * <p>带一个「显示敏感值」开关：名字里含 token / key / secret / auth 之类的值默认打成
 * {@code ••••}，需要核对的时候再打开——MCP 往往要在 IDE 里长时间开着，顺手挡住肩窥是有意义的。
 * 注意这只影响<b>显示</b>，真正发出去的值始终是原文。
 */
public final class KeyValueTable extends JPanel {

    private final DefaultTableModel model;
    private final JTable table;
    private final JBCheckBox reveal;
    private final JButton addButton;
    private final JButton removeButton;
    /** 列表头文案由调用方传入，存下来以便切语言时重新设置（不触碰数据）。 */
    private final String colKeyTitle;
    private final String colValueTitle;

    public KeyValueTable(String keyTitle, String valueTitle, boolean initiallyVisible) {
        super(new BorderLayout());
        setOpaque(false);
        this.colKeyTitle = keyTitle;
        this.colValueTitle = valueTitle;

        model = new DefaultTableModel(new Object[]{keyTitle, valueTitle}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return true;
            }
        };
        table = new JTable(model);
        table.setRowHeight(JBUI.scale(22));
        table.setFillsViewportHeight(true);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        table.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(140));
        table.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(220));
        table.setDefaultRenderer(Object.class, new MaskingRenderer());

        reveal = new JBCheckBox(I18n.t("ui.reveal"), false);
        reveal.setToolTipText(I18n.t("ui.revealTooltip"));
        reveal.addActionListener(e -> table.repaint());

        addButton = new JButton(I18n.t("ui.row.add"));
        addButton.addActionListener(e -> {
            model.addRow(new Object[]{"", ""});
            int last = model.getRowCount() - 1;
            table.getSelectionModel().setSelectionInterval(last, last);
            if (!table.isEditing()) {
                table.editCellAt(last, 0);
            }
        });

        removeButton = new JButton(I18n.t("ui.row.remove"));
        removeButton.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                if (rows[i] >= 0 && rows[i] < model.getRowCount()) {
                    model.removeRow(rows[i]);
                }
            }
        });

        JBScrollPane scroll = new JBScrollPane(table);
        scroll.setPreferredSize(new Dimension(JBUI.scale(400), JBUI.scale(160)));

        JPanel buttons = new JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new javax.swing.BoxLayout(buttons, javax.swing.BoxLayout.X_AXIS));
        buttons.add(addButton);
        buttons.add(Ui.hgap(4));
        buttons.add(removeButton);
        buttons.add(javax.swing.Box.createHorizontalGlue());
        buttons.add(reveal);
        buttons.setBorder(JBUI.Borders.emptyTop(4));

        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        if (initiallyVisible) {
            reveal.setSelected(true);
        }
        applyTexts();
    }

    /** 重贴所有用户可见文案；语言切换时由外层统一级联调用，幂等且不碰数据。 */
    public void applyTexts() {
        reveal.setText(I18n.t("ui.reveal"));
        reveal.setToolTipText(I18n.t("ui.revealTooltip"));
        addButton.setText(I18n.t("ui.row.add"));
        removeButton.setText(I18n.t("ui.row.remove"));
        if (table.getTableHeader() != null) {
            table.getColumnModel().getColumn(0).setHeaderValue(colKeyTitle);
            table.getColumnModel().getColumn(1).setHeaderValue(colValueTitle);
            table.getTableHeader().repaint();
        }
    }

    public void setRows(List<KeyValue> rows) {
        model.setRowCount(0);
        for (KeyValue kv : rows) {
            model.addRow(new Object[]{kv.getKey(), kv.getValue()});
        }
    }

    /** 读取表格内容；空键的行会被丢掉（用户在最后一行留空是常事）。 */
    public List<KeyValue> getRows() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<KeyValue> out = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            Object key = model.getValueAt(i, 0);
            Object value = model.getValueAt(i, 1);
            String k = key == null ? "" : key.toString().trim();
            if (k.isEmpty()) {
                continue;
            }
            out.add(new KeyValue(k, value == null ? "" : value.toString()));
        }
        return out;
    }

    /** 处理打码的单元格渲染器。 */
    private final class MaskingRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            Object shown = value;
            if (column == 1 && !reveal.isSelected()) {
                Object key = source.getModel().getValueAt(row, 0);
                String keyText = key == null ? "" : key.toString();
                if (KeyValue.looksSensitive(keyText)) {
                    String text = value == null ? "" : value.toString();
                    shown = text.isEmpty() ? "" : I18n.t("ui.masked", text.length());
                }
            }
            return super.getTableCellRendererComponent(source, shown, selected, focused, row, column);
        }
    }
}
