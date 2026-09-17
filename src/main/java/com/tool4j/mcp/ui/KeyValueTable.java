package com.tool4j.mcp.ui;

import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

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

    public KeyValueTable(String keyTitle, String valueTitle, boolean initiallyVisible) {
        super(new BorderLayout());
        setOpaque(false);

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

        reveal = new JBCheckBox("显示敏感值", false);
        reveal.setToolTipText("键名含 token / key / secret / auth 等的值默认打码显示，这只是显示效果");
        reveal.addActionListener(e -> table.repaint());

        JButton add = new JButton("添加");
        add.addActionListener(e -> {
            model.addRow(new Object[]{"", ""});
            int last = model.getRowCount() - 1;
            table.getSelectionModel().setSelectionInterval(last, last);
            if (!table.isEditing()) {
                table.editCellAt(last, 0);
            }
        });

        JButton remove = new JButton("删除");
        remove.addActionListener(e -> {
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
        buttons.add(add);
        buttons.add(Ui.hgap(4));
        buttons.add(remove);
        buttons.add(javax.swing.Box.createHorizontalGlue());
        buttons.add(reveal);
        buttons.setBorder(JBUI.Borders.emptyTop(4));

        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        if (initiallyVisible) {
            reveal.setSelected(true);
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
                    shown = text.isEmpty() ? "" : "••••••••（" + text.length() + " 字符）";
                }
            }
            return super.getTableCellRendererComponent(source, shown, selected, focused, row, column);
        }
    }
}
