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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 工具详情：描述 + 参数 + 调用 + 结果。
 *
 * <p>参数区有一组页签，<b>以当前所在页签为准</b>：
 * <ul>
 *   <li><b>参数</b>：由 schema 生成的真实表单（推荐，必填有标记、类型有提示、校验有红字）。</li>
 *   <li><b>JSON</b>：直接写原始 JSON，适合塞复杂结构或从别处粘贴一段参数。第一次从这个页签切过来时，
 *   会自动把表单当前内容填进去当起点，不用手敲。</li>
 *   <li><b>定义</b>：工具原始定义（含 annotations 与 outputSchema），排查"服务端到底声明了什么"时用。</li>
 * </ul>
 */
public final class ToolDetailPanel extends JPanel implements Disposable {

    /** 调用请求的回调；由主面板实现，负责切后台线程和拿会话。 */
    public interface Callback {
        void invokeTool(McpTool tool, JsonObject arguments);
    }

    private final JBLabel titleLabel = new JBLabel(" ");
    private final JBLabel badgesLabel = Ui.hint(" ");
    private final JBTextArea descriptionArea = new JBTextArea();
    private final JBLabel usageHint = Ui.hint("");
    private final JBLabel statusLabel = Ui.hint("");
    private final JButton invokeButton = new JButton("调用");
    private final JButton resetButton = new JButton("清空");

    private final SchemaFormPanel form;
    private final EditorTextField jsonEditor;
    private final EditorTextField definitionViewer;
    private final JBTabbedPane paramTabs = new JBTabbedPane();
    private final ResultView resultView;
    private final JBSplitter splitter;

    private McpTool tool;
    private Callback callback;
    private boolean busy;
    /** JSON 页签是否已被自动填充过，避免每次切换都覆盖用户的改动。 */
    private boolean jsonInitialized;

    public ToolDetailPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);

        form = new SchemaFormPanel(project);
        jsonEditor = Editors.sized(Editors.jsonEditor(project, "", parent), 260);
        definitionViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 260);

        paramTabs.addTab("参数", form);
        paramTabs.addTab("JSON", wrapEditor(jsonEditor));
        paramTabs.addTab("定义", wrapEditor(definitionViewer));
        paramTabs.addChangeListener(e -> onTabChanged());

        JPanel paramArea = new JPanel(new BorderLayout());
        paramArea.setOpaque(false);
        paramArea.add(buildActionRow(), BorderLayout.NORTH);
        paramArea.add(paramTabs, BorderLayout.CENTER);

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

        // 用量提示（"将使用哪个页签的参数 · 几个参数"）原来挤在按钮行里，
        // 边栏一窄就被挤没了；放到标题下面单独一行，宽度不够时大不了省略号。
        JPanel headerTop = new JPanel();
        headerTop.setOpaque(false);
        headerTop.setLayout(new BoxLayout(headerTop, BoxLayout.Y_AXIS));
        titleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        usageHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerTop.add(titleRow);
        headerTop.add(usageHint);

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
        header.add(headerTop, BorderLayout.NORTH);
        header.add(descriptionScroll, BorderLayout.CENTER);
        return header;
    }

    /**
     * 参数区那一排按钮。
     *
     * <p>只有「调用」保留文字（主操作值得占宽度），清空 / 格式化换成图标按钮：
     * 在右侧边栏里"调用 清空 格式化 JSON"三个文字按钮能吃掉大半个宽度，
     * 把状态文字挤到看不见。usageHint 挪去了标题下方。
     */
    private JComponent buildActionRow() {
        invokeButton.setToolTipText("调用该工具（Ctrl+Enter）");
        invokeButton.addActionListener(e -> invoke());

        resetButton.setIcon(AllIcons.General.Reset);
        resetButton.setText("");
        resetButton.setToolTipText("清空所有参数，回到 schema 的默认值");
        resetButton.setMargin(JBUI.insets(2, 4, 2, 4));
        resetButton.setFocusable(false);
        resetButton.addActionListener(e -> {
            form.reset();
            jsonEditor.setText("");
            jsonInitialized = false;
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
        setDescription("从左侧列表里点开任意工具，这里会按它的 JSON Schema 生成参数表单。"
                + "只想手动写 JSON 的话，切到「JSON」页签即可。");
        usageHint.setText("");
        statusLabel.setText("");
        form.setSchema(SchemaUtil.emptyObjectSchema(), null);
        jsonEditor.setText("");
        definitionViewer.setText("");
        jsonInitialized = false;
        paramTabs.setSelectedIndex(0);
        setBusy(false);
        resultView.clear();
    }

    public void showTool(McpTool tool) {
        this.tool = tool;
        titleLabel.setText(tool.getName());
        badgesLabel.setText(badges(tool));
        String description = tool.getDescription();
        setDescription(description == null || description.isBlank() ? "（该工具没有提供描述）" : description);
        form.setSchema(tool.getInputSchema(), null);
        jsonEditor.setText("");
        definitionViewer.setText(JsonUtil.pretty(tool.getRaw()));
        jsonInitialized = false;
        paramTabs.setSelectedIndex(0);
        statusLabel.setText("");
        setBusy(false);
        resultView.clear();
        updateUsageHint();
    }

    private void setDescription(String text) {
        descriptionArea.setText(text);
        descriptionArea.setCaretPosition(0);
    }

    private void onTabChanged() {
        int index = paramTabs.getSelectedIndex();
        if (index == 1 && !jsonInitialized) {
            jsonInitialized = true;
            // 以表单当前内容为起点，避免用户对着空白页签从零开始敲
            JsonObject fromForm = form.buildArguments().getArguments();
            jsonEditor.setText(fromForm.size() == 0 ? "" : JsonUtil.pretty(fromForm));
        }
        updateUsageHint();
    }

    private void updateUsageHint() {
        if (tool == null) {
            usageHint.setText("");
            return;
        }
        JsonObject schema = tool.hasInputSchema()
                ? SchemaUtil.normalize(tool.getInputSchema(), tool.getInputSchema())
                : SchemaUtil.emptyObjectSchema();
        Set<String> requiredNames = SchemaUtil.required(schema);
        int total = SchemaUtil.properties(schema).entrySet().size();

        List<String> bits = new ArrayList<>();
        bits.add(paramTabs.getSelectedIndex() == 1 ? "将使用「JSON」页签的参数" : "将使用「参数」页签的表单");
        bits.add(total == 0 ? "该工具无需参数" : total + " 个参数" + (requiredNames.isEmpty() ? "" : "（含必填）"));
        usageHint.setText(String.join("  ·  ", bits));
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
        boolean fromJson = paramTabs.getSelectedIndex() == 1;
        JsonObject arguments;
        if (fromJson) {
            try {
                arguments = JsonUtil.parseObjectLenient(jsonEditor.getText());
            } catch (IllegalArgumentException e) {
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText("JSON 参数有误：" + e.getMessage());
                return;
            }
        } else {
            SchemaFormPanel.Outcome outcome = form.buildArguments();
            if (!outcome.isOk()) {
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText("参数校验未通过（" + outcome.getProblems().size() + " 处，已在表单里标红）");
                return;
            }
            arguments = outcome.getArguments();
        }

        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setText((fromJson ? "以 JSON 页签的参数调用" : "以表单参数调用")
                + "（" + arguments.size() + " 个字段）");
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
        form.dispose();
        resultView.dispose();
    }
}
