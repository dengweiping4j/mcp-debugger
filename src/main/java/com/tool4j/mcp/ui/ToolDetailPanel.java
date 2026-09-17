package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.SchemaUtil;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具详情：描述 + 参数（JSON）+ 调用 + 结果。
 *
 * <p>参数区只有两个页签，<b>JSON 是唯一的输入入口</b>：
 * <ul>
 *   <li><b>JSON</b>：选中工具时按它的 {@code inputSchema} 预填一份模板——声明的每个参数都在，
 *   值取 schema 的 {@code default}，没 default 的按类型给空值。改哪几个值就发哪几个字段，
 *   多余的删掉即可。</li>
 *   <li><b>定义</b>：工具原始定义（含 annotations 与 outputSchema）。参数的类型、必填、说明、
 *   默认值都能在这里查到，不用另开文档。</li>
 * </ul>
 *
 * <p>原先还有个「参数」表单页签，已移除：它和 JSON 页签表达的是同一件事，
 * 两套输入只让用户多一层"我现在改动的是哪个页签"的心智负担。
 */
public final class ToolDetailPanel extends JPanel implements Disposable {

    /** 调用请求的回调；由主面板实现，负责切后台线程和拿会话。 */
    public interface Callback {
        void invokeTool(McpTool tool, JsonObject arguments);
    }

    private final JBLabel titleLabel = new JBLabel(" ");
    private final JBLabel badgesLabel = Ui.hint(" ");
    private final JBTextArea descriptionArea = new JBTextArea();
    private final JBLabel statusLabel = Ui.hint("");
    private final JButton invokeButton = new JButton("调用");
    private final JButton resetButton = new JButton("重置");

    private final EditorTextField jsonEditor;
    private final EditorTextField definitionViewer;
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final ResultView resultView;
    private final JBSplitter splitter;

    private McpTool tool;
    private Callback callback;
    private boolean busy;

    public ToolDetailPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);

        jsonEditor = Editors.sized(Editors.jsonEditor(project, "", parent), 260);
        definitionViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 260);
        // 编辑器自带 Keymap，面板级的 Ctrl+Enter 到不了它，得挂进编辑器自己身上
        Editors.onInvokeShortcut(jsonEditor, this::invoke);

        tabs.addTab("JSON", wrapEditor(jsonEditor));
        tabs.addTab("定义", wrapEditor(definitionViewer));

        JPanel paramArea = new JPanel(new BorderLayout());
        paramArea.setOpaque(false);
        paramArea.add(buildActionRow(), BorderLayout.NORTH);
        paramArea.add(tabs, BorderLayout.CENTER);

        resultView = new ResultView(project, parent);

        splitter = new JBSplitter(true, 0.52f);
        splitter.setFirstComponent(paramArea);
        splitter.setSecondComponent(resultView);
        // 分隔条宽度用平台默认值（7px）。设成 6 之类看着"精致"，但分割条的鼠标热区就是它本身，
        // 窄了手就抓不住——这是"拖不动"的最常见原因。
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setShowDividerControls(false);

        add(buildHeader(), BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        installShortcuts();
        clear();
    }

    private static JComponent wrapEditor(JComponent editor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(JBUI.Borders.empty(6));
        wrapper.add(editor, BorderLayout.CENTER);
        return wrapper;
    }

    // ------------------------------------------------------------------
    // 头部 / 工具条
    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        titleLabel.setFont(Ui.bold(titleLabel.getFont()));
        badgesLabel.setFont(Ui.smaller(badgesLabel.getFont()));

        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.add(titleLabel);
        titleRow.add(Ui.hgap(8));
        titleRow.add(badgesLabel);
        titleRow.add(Box.createHorizontalGlue());

        descriptionArea.setEditable(false);
        descriptionArea.setLineWrap(true);
        descriptionArea.setWrapStyleWord(true);
        descriptionArea.setOpaque(false);
        descriptionArea.setForeground(UIUtil.getLabelForeground());
        descriptionArea.setBorder(JBUI.Borders.empty(2, 0));
        JBScrollPane descriptionScroll = new JBScrollPane(descriptionArea);
        descriptionScroll.setBorder(JBUI.Borders.empty());
        descriptionScroll.setOpaque(false);
        descriptionScroll.getViewport().setOpaque(false);
        descriptionScroll.setPreferredSize(new Dimension(0, JBUI.scale(48)));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        header.add(titleRow, BorderLayout.NORTH);
        header.add(descriptionScroll, BorderLayout.CENTER);
        return header;
    }

    /**
     * 参数区那一排按钮。
     *
     * <p>只有「调用」保留文字（主操作值得占宽度），重置 / 美化换成图标按钮：
     * 在右侧边栏里三个文字按钮能吃掉大半个宽度，把状态文字挤到看不见。
     */
    private JComponent buildActionRow() {
        invokeButton.setToolTipText("调用该工具（Ctrl+Enter）");
        invokeButton.addActionListener(e -> invoke());

        resetButton.setIcon(AllIcons.General.Reset);
        resetButton.setText("");
        resetButton.setToolTipText("重置为按工具定义生成的参数");
        resetButton.setMargin(JBUI.insets(2, 4, 2, 4));
        resetButton.setFocusable(false);
        resetButton.addActionListener(e -> {
            prefillArguments(tool);
            statusLabel.setText("");
        });

        JButton formatJson = new JButton(AllIcons.Actions.ReformatCode);
        formatJson.setToolTipText("JSON字符串美化");
        formatJson.setMargin(JBUI.insets(2, 4, 2, 4));
        formatJson.setFocusable(false);
        formatJson.addActionListener(e -> {
            String text = jsonEditor.getText();
            if (text != null && !text.isBlank()) {
                jsonEditor.setText(JsonUtil.formatLenient(text));
            }
        });

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(invokeButton);
        left.add(Ui.hgap(4));
        left.add(resetButton);
        left.add(Ui.hgap(2));
        left.add(formatJson);

        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        row.add(left, BorderLayout.WEST);
        row.add(statusLabel, BorderLayout.CENTER);
        return row;
    }

    /** 面板级快捷键：焦点不在 JSON 编辑器里时（列表、按钮、只读视图）也能触发调用。 */
    private void installShortcuts() {
        InputMap inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "mcp.invoke");
        actionMap.put("mcp.invoke", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                invoke();
            }
        });
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void clear() {
        tool = null;
        titleLabel.setText("选择一个工具");
        badgesLabel.setText("");
        setDescription("从左侧列表里点开任意工具，这里会按它的定义预填一份参数 JSON。"
                + "参数的类型、必填和说明在「定义」页签里。");
        statusLabel.setText("");
        prefillArguments(null);
        definitionViewer.setText("");
        tabs.setSelectedIndex(0);
        setBusy(false);
        resultView.clear();
    }

    public void showTool(McpTool tool) {
        this.tool = tool;
        titleLabel.setText(tool.getName());
        badgesLabel.setText(badges(tool));
        String description = tool.getDescription();
        setDescription(description == null || description.isBlank() ? "（该工具没有提供描述）" : description);
        prefillArguments(tool);
        definitionViewer.setText(JsonUtil.pretty(tool.getRaw()));
        tabs.setSelectedIndex(0);
        statusLabel.setText("");
        setBusy(false);
        resultView.clear();
    }

    /**
     * 按工具的 inputSchema 预填参数。
     *
     * <p>以前这里是"切到 JSON 页签时把表单当前内容搬过来"，而表单只在"用户填过值"时才收集字段，
     * 于是没有 default 的参数一个都不会出现在 JSON 里——页签看着一片空白，
     * 用户既不知道有哪些参数，也就无从下手（"为什么这个工具的 JSON 是空的"就是这么来的）。
     * 现在直接按 schema 声明生成，每个参数都在，且永远是一份能解析的 JSON。
     *
     * <p>没有 inputSchema（或 schema 畸形）时给 {@code {}}：宁可给一个空对象让人自己往里写，
     * 也不要留一个没有内容、没有提示的空白编辑区。
     */
    private void prefillArguments(McpTool target) {
        JsonObject template = new JsonObject();
        if (target != null && target.hasInputSchema()) {
            try {
                template = SchemaUtil.template(target.getInputSchema());
            } catch (RuntimeException ignored) {
                // 服务端 schema 再畸形也不该让输入区变成白板，退回空对象即可
            }
        }
        jsonEditor.setText(JsonUtil.pretty(template));
        jsonEditor.setCaretPosition(0);
    }

    private void setDescription(String text) {
        descriptionArea.setText(text);
        descriptionArea.setCaretPosition(0);
    }

    private void setBusy(boolean value) {
        this.busy = value;
        invokeButton.setEnabled(!value);
        invokeButton.setText(value ? "调用中…" : "调用");
        resetButton.setEnabled(!value);
    }

    private static String badges(McpTool tool) {
        List<String> parts = new ArrayList<>();
        if (tool.isReadOnly()) {
            parts.add("<font color='#1F7A3D'>只读</font>");
        }
        if (tool.isDestructive()) {
            parts.add("<font color='#C0392B'>破坏性</font>");
        }
        if (tool.isIdempotent()) {
            parts.add("幂等");
        }
        if (tool.isOpenWorld()) {
            parts.add("开放世界");
        }
        String subtitle = tool.getSubtitle();
        if (subtitle != null && !subtitle.isBlank()) {
            parts.add(Ui.escapeHtml(Ui.ellipsize(subtitle, 60)));
        }
        return parts.isEmpty() ? "" : "<html>" + String.join("  ·  ", parts) + "</html>";
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /** 触发调用（按钮与 Ctrl+Enter 共用）。 */
    public void invoke() {
        if (tool == null || busy || callback == null) {
            return;
        }
        JsonObject arguments;
        try {
            arguments = JsonUtil.parseObjectLenient(jsonEditor.getText());
        } catch (IllegalArgumentException e) {
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("JSON 参数有误：" + e.getMessage());
            return;
        }

        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setText("以 " + arguments.size() + " 个参数调用");
        setBusy(true);
        resultView.showLoading("工具 " + tool.getName());
        callback.invokeTool(tool, arguments);
    }

    /** 调用结束：渲染结果。 */
    public void showResult(McpCallResult result) {
        setBusy(false);
        String what = tool == null ? "工具" : "工具 " + tool.getName();
        resultView.show(result, what);
        if (result == null) {
            return;
        }
        if (result.getError() != null) {
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("调用失败");
        } else if (!result.isSuccess()) {
            statusLabel.setForeground(Ui.WARN);
            statusLabel.setText("服务端返回 isError = true");
        } else {
            statusLabel.setForeground(Ui.OK);
            statusLabel.setText("完成 · " + result.getElapsedMillis() + " ms");
        }
    }

    /** 本地异常（未连接、超时、进程挂了）。 */
    public void showInvokeError(String message) {
        setBusy(false);
        statusLabel.setForeground(Ui.ERROR);
        statusLabel.setText("调用失败");
        resultView.showFailure(message, tool == null ? "工具" : "工具 " + tool.getName());
    }

    @Override
    public void dispose() {
        resultView.dispose();
    }
}
