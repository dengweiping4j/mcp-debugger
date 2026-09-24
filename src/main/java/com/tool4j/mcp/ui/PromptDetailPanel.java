package com.tool4j.mcp.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.protocol.JsonUtil;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词详情：填参数 → {@code prompts/get} → 看服务端返回的消息序列。
 *
 * <p>MCP 规范里提示词参数只有名字、描述、是否必填，<b>没有类型信息</b>，而且
 * {@code prompts/get} 的 arguments 必须是"字符串到字符串"，所以这里一律按字符串输入即可，
 * 不需要像工具那样生成复杂表单。
 *
 * <p>结果按 {@code messages} 逐条渲染（role + 内容），同时保留原始 JSON 页签。
 */
public final class PromptDetailPanel extends JPanel implements Disposable {

    public interface Callback {
        void getPrompt(McpPrompt prompt, JsonObject arguments);
    }

    private final Project project;
    private final JBLabel titleLabel = new JBLabel(" ");
    private final JBLabel metaLabel = Ui.hint(" ");
    /** 提示词描述：单行 + 省略号，鼠标移入看全文；见 {@link OneLineLabel}。 */
    private final OneLineLabel descriptionLabel = new OneLineLabel();
    private final JBLabel statusLabel = Ui.hint("");
    private final JPanel argsHost = new JPanel(new GridBagLayout());
    private final JButton fetchButton = new JButton(I18n.t("prompt.fetch.label"));
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JPanel messagesHost = new JPanel();
    private final com.intellij.ui.EditorTextField rawViewer;

    private final Map<String, JBTextField> argFields = new LinkedHashMap<>();

    private McpPrompt prompt;
    private Callback callback;
    private boolean busy;

    private enum PromptStatus { IDLE, MISSING_REQUIRED, FETCHING, FAILED, DONE }

    /** 状态行种类，applyTexts 据此重画。 */
    private PromptStatus promptStatus = PromptStatus.IDLE;
    /** 缺失的必填参数名（MISSING_REQUIRED 状态要显示，需按语言分隔符重新拼接）。 */
    private java.util.List<String> missingRequired;
    /** 最近一次成功结果（用于重画消息区，不含网络调用）。 */
    private McpCallResult lastResult;
    /** true 表示最近一次是 showError（显示错误正文），而非 showResult。 */
    private boolean showErrorMode;
    /** showError 传入的错误正文。 */
    private String lastErrorBody;
    /** 必填星号标签，applyTexts 只更新它们的 tooltip。 */
    private final java.util.List<JBLabel> requiredStars = new java.util.ArrayList<>();
    /** 「该提示词没有参数」提示，applyTexts 只更新它的文字。 */
    private JBLabel noArgsHint;

    public PromptDetailPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);

        argsHost.setOpaque(false);

        messagesHost.setOpaque(false);
        messagesHost.setLayout(new BoxLayout(messagesHost, BoxLayout.Y_AXIS));
        JBScrollPane messagesScroll = new JBScrollPane(messagesHost);
        messagesScroll.setBorder(JBUI.Borders.empty());
        messagesScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));

        rawViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 300);
        JPanel rawPanel = new JPanel(new BorderLayout());
        rawPanel.setOpaque(false);
        rawPanel.setBorder(JBUI.Borders.empty(6));
        rawPanel.add(rawViewer, BorderLayout.CENTER);

        tabs.addTab(I18n.t("prompt.tab.messages"), messagesScroll);
        tabs.addTab(I18n.t("prompt.tab.raw"), rawPanel);

        JBSplitter splitter = new JBSplitter(true, 0.45f);
        splitter.setFirstComponent(buildHeader());
        splitter.setSecondComponent(tabs);
        // 分隔条用平台默认宽度（7px）：鼠标热区就是分隔条本身，压窄了会抓不住
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);

        clear();
        applyTexts();
    }

    private JComponent buildHeader() {
        titleLabel.setFont(Ui.bold(titleLabel.getFont()));
        metaLabel.setFont(Ui.smaller(metaLabel.getFont()));

        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.add(titleLabel);
        titleRow.add(Ui.hgap(8));
        titleRow.add(metaLabel);
        titleRow.add(Box.createHorizontalGlue());

        descriptionLabel.setForeground(UIUtil.getLabelForeground());
        descriptionLabel.setBorder(JBUI.Borders.emptyTop(2));

        fetchButton.setToolTipText(I18n.t("prompt.fetch.tooltip"));
        fetchButton.addActionListener(e -> fetch());

        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.setOpaque(false);
        buttonRow.setBorder(JBUI.Borders.empty(4, 0, 0, 0));
        buttonRow.add(Ui.hbox(fetchButton, Ui.hgap(8), statusLabel), BorderLayout.WEST);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        header.add(titleRow, BorderLayout.NORTH);
        header.add(descriptionLabel, BorderLayout.CENTER);
        header.add(Ui.vbox(argsHost, buttonRow), BorderLayout.SOUTH);
        return header;
    }

    // ------------------------------------------------------------------

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void clear() {
        prompt = null;
        promptStatus = PromptStatus.IDLE;
        showErrorMode = false;
        lastResult = null;
        lastErrorBody = null;
        requiredStars.clear();
        noArgsHint = null;
        titleLabel.setText(I18n.t("prompt.title.empty"));
        metaLabel.setText("");
        descriptionLabel.setFullText(I18n.t("prompt.help.text"));
        argFields.clear();
        argsHost.removeAll();
        argsHost.revalidate();
        argsHost.repaint();
        fetchButton.setEnabled(false);
        statusLabel.setText("");
        messagesHost.removeAll();
        rawViewer.setText("");
    }

    public void showPrompt(McpPrompt prompt) {
        this.prompt = prompt;
        renderPromptInfo();
        statusLabel.setText("");
        messagesHost.removeAll();
        rawViewer.setText("");

        argFields.clear();
        argsHost.removeAll();
        requiredStars.clear();
        noArgsHint = null;
        if (prompt.getArguments().isEmpty()) {
            JBLabel none = Ui.hint(I18n.t("prompt.args.none"));
            none.setBorder(JBUI.Borders.empty(4, 2));
            noArgsHint = none;
            argsHost.add(none, new GridBagConstraints());
        } else {
            int row = 0;
            for (McpPrompt.Argument argument : prompt.getArguments()) {
                if (argument.getName() == null || argument.getName().isBlank()) {
                    continue;
                }
                JBTextField field = Ui.compactInput(new JBTextField());
                field.setToolTipText(argument.getDescription());
                argFields.put(argument.getName(), field);

                JPanel label = new JPanel();
                label.setOpaque(false);
                label.setLayout(new BoxLayout(label, BoxLayout.X_AXIS));
                JBLabel name = new JBLabel(argument.getName());
                name.setFont(name.getFont().deriveFont(Font.BOLD));
                label.add(name);
                if (argument.isRequired()) {
                    JBLabel star = new JBLabel(" *");
                    star.setForeground(JBColor.RED);
                    star.setToolTipText(I18n.t("prompt.arg.required"));
                    requiredStars.add(star);
                    label.add(star);
                }

                GridBagConstraints labelConstraints = new GridBagConstraints();
                labelConstraints.gridx = 0;
                labelConstraints.gridy = row;
                labelConstraints.anchor = GridBagConstraints.NORTHWEST;
                labelConstraints.insets = new Insets(JBUI.scale(4), JBUI.scale(2), JBUI.scale(4), JBUI.scale(10));
                argsHost.add(label, labelConstraints);

                JPanel cell = new JPanel();
                cell.setOpaque(false);
                cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
                cell.add(field);
                if (argument.getDescription() != null && !argument.getDescription().isBlank()) {
                    JBLabel hint = Ui.htmlHint(Ui.escapeHtml(Ui.ellipsize(JsonUtil.oneLine(argument.getDescription()), 80)));
                    hint.setToolTipText(argument.getDescription());
                    JPanel hintRow = new JPanel(new BorderLayout());
                    hintRow.setOpaque(false);
                    hintRow.add(hint, BorderLayout.WEST);
                    cell.add(hintRow);
                }

                GridBagConstraints fieldConstraints = new GridBagConstraints();
                fieldConstraints.gridx = 1;
                fieldConstraints.gridy = row;
                fieldConstraints.anchor = GridBagConstraints.NORTHWEST;
                fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
                fieldConstraints.weightx = 1;
                fieldConstraints.insets = new Insets(JBUI.scale(4), 0, JBUI.scale(4), JBUI.scale(4));
                argsHost.add(cell, fieldConstraints);
                row++;
            }
            GridBagConstraints filler = new GridBagConstraints();
            filler.gridx = 1;
            filler.gridy = row;
            filler.weightx = 1;
            filler.weighty = 1;
            filler.fill = GridBagConstraints.BOTH;
            JPanel spacer = new JPanel();
            spacer.setOpaque(false);
            argsHost.add(spacer, filler);
        }
        argsHost.revalidate();
        argsHost.repaint();
        fetchButton.setEnabled(true);
        promptStatus = PromptStatus.IDLE;
        setBusy(false);
    }

    /** 从绑定的 prompt 重画标题 / 副标题 / 描述（不碰参数区，那会清掉用户已输入的值）。 */
    private void renderPromptInfo() {
        if (prompt == null) {
            return;
        }
        titleLabel.setText(prompt.getName());
        metaLabel.setText(prompt.getSubtitle());
        String description = prompt.getDescription();
        descriptionLabel.setFullText(description == null || description.isBlank()
                ? I18n.t("prompt.description.empty") : description);
    }

    private void setBusy(boolean value) {
        busy = value;
        fetchButton.setEnabled(!value && prompt != null);
        fetchButton.setText(value ? I18n.t("prompt.fetch.busy") : I18n.t("prompt.fetch.label"));
    }

    // ------------------------------------------------------------------

    public void fetch() {
        if (busy || prompt == null || callback == null) {
            return;
        }
        JsonObject arguments = new JsonObject();
        for (Map.Entry<String, JBTextField> entry : argFields.entrySet()) {
            String value = entry.getValue().getText();
            if (value != null && !value.isEmpty()) {
                arguments.addProperty(entry.getKey(), value);
            }
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (McpPrompt.Argument argument : prompt.getArguments()) {
            if (argument.isRequired() && !arguments.has(argument.getName())) {
                missing.add(argument.getName());
            }
        }
        if (!missing.isEmpty()) {
            promptStatus = PromptStatus.MISSING_REQUIRED;
            missingRequired = missing;
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText(I18n.t("prompt.status.missingRequired",
                    String.join(I18n.t("prompt.arg.separator"), missing)));
            return;
        }

        setBusy(true);
        promptStatus = PromptStatus.FETCHING;
        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setText(I18n.t("prompt.status.fetching"));
        messagesHost.removeAll();
        messagesHost.revalidate();
        callback.getPrompt(prompt, arguments);
    }

    public void showResult(McpCallResult result) {
        setBusy(false);
        lastResult = result;
        showErrorMode = false;
        if (result == null) {
            messagesHost.removeAll();
            messagesHost.revalidate();
            messagesHost.repaint();
            return;
        }
        rawViewer.setText(JsonUtil.pretty(result.getRaw()));

        if (result.getError() != null) {
            promptStatus = PromptStatus.FAILED;
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText(I18n.t("prompt.status.failed"));
        } else {
            promptStatus = PromptStatus.DONE;
            statusLabel.setForeground(Ui.OK);
            statusLabel.setText(I18n.t("prompt.status.done", result.getElapsedMillis()));
        }
        renderMessages();
    }

    public void showError(String message) {
        setBusy(false);
        showErrorMode = true;
        lastErrorBody = message;
        lastResult = null;
        promptStatus = PromptStatus.FAILED;
        statusLabel.setForeground(Ui.ERROR);
        statusLabel.setText(I18n.t("prompt.status.failed"));
        renderMessages();
    }

    /** 按当前语言重画消息区；只重放已存结果，不触发网络、不碰用户输入。 */
    private void renderMessages() {
        messagesHost.removeAll();
        if (showErrorMode) {
            if (lastErrorBody != null) {
                messagesHost.add(Ui.textBody(lastErrorBody, 0, Ui.ERROR));
            }
        } else if (lastResult != null) {
            if (lastResult.getError() != null) {
                messagesHost.add(Ui.textBody(lastResult.getError(), 0, Ui.ERROR));
            } else {
                String description = JsonUtil.str(lastResult.getRaw(), "description", null);
                if (description != null && !description.isBlank()) {
                    messagesHost.add(messageCard(I18n.t("prompt.card.description"), description));
                }
                JsonArray messages = JsonUtil.array(lastResult.getRaw(), "messages");
                int index = 1;
                for (JsonElement element : messages) {
                    if (element.isJsonObject()) {
                        messagesHost.add(renderMessage(element.getAsJsonObject(), index++));
                    }
                }
                if (messages.isEmpty()) {
                    messagesHost.add(messageCard(I18n.t("prompt.card.result"), I18n.t("prompt.empty.messages")));
                }
            }
        }
        messagesHost.revalidate();
        messagesHost.repaint();
    }

    /** 语言切换时重画所有界面文字；不碰参数区与用户输入，可随时安全调用。 */
    public void applyTexts() {
        fetchButton.setToolTipText(I18n.t("prompt.fetch.tooltip"));
        fetchButton.setText(I18n.t(busy ? "prompt.fetch.busy" : "prompt.fetch.label"));
        tabs.setTitleAt(0, I18n.t("prompt.tab.messages"));
        tabs.setTitleAt(1, I18n.t("prompt.tab.raw"));

        if (prompt == null) {
            titleLabel.setText(I18n.t("prompt.title.empty"));
            metaLabel.setText("");
            descriptionLabel.setFullText(I18n.t("prompt.help.text"));
        } else {
            renderPromptInfo();
        }

        if (noArgsHint != null) {
            noArgsHint.setText(I18n.t("prompt.args.none"));
        }
        for (JBLabel star : requiredStars) {
            star.setToolTipText(I18n.t("prompt.arg.required"));
        }

        renderPromptStatus();
        renderMessages();
    }

    private void renderPromptStatus() {
        switch (promptStatus) {
            case IDLE:
                statusLabel.setText("");
                break;
            case MISSING_REQUIRED:
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText(I18n.t("prompt.status.missingRequired",
                        String.join(I18n.t("prompt.arg.separator"), missingRequired)));
                break;
            case FETCHING:
                statusLabel.setForeground(JBColor.GRAY);
                statusLabel.setText(I18n.t("prompt.status.fetching"));
                break;
            case FAILED:
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText(I18n.t("prompt.status.failed"));
                break;
            case DONE:
                statusLabel.setForeground(Ui.OK);
                statusLabel.setText(I18n.t("prompt.status.done",
                        lastResult == null ? 0 : lastResult.getElapsedMillis()));
                break;
        }
    }

    private JComponent renderMessage(JsonObject message, int index) {
        String role = JsonUtil.str(message, "role", "unknown");
        JsonElement content = message.get("content");
        String text;
        if (content == null || content.isJsonNull()) {
            text = I18n.t("prompt.card.noContent");
        } else if (content.isJsonPrimitive()) {
            text = content.getAsString();
        } else if (content.isJsonObject()) {
            JsonObject contentObject = content.getAsJsonObject();
            // 文本型 content 最常见，直接取出 text 更可读；其余类型老老实实打 JSON
            String type = JsonUtil.str(contentObject, "type", "");
            String inner = JsonUtil.str(contentObject, "text", null);
            text = inner != null && ("text".equals(type) || type.isEmpty())
                    ? inner
                    : JsonUtil.pretty(contentObject);
        } else {
            text = JsonUtil.pretty(content);
        }
        String title = I18n.t("prompt.card.message", index, role);
        return messageCard(title, text);
    }

    private JComponent messageCard(String title, String text) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(4, 6, 8, 6));

        JBLabel label = new JBLabel(title);
        label.setFont(Ui.smaller(label.getFont().deriveFont(Font.BOLD)));
        label.setForeground(UIUtil.getContextHelpForeground());
        panel.add(label, BorderLayout.NORTH);
        panel.add(Ui.textBody(text, 260, null), BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    public McpPrompt getPrompt() {
        return prompt;
    }

    @Override
    public void dispose() {
        messagesHost.removeAll();
        argFields.clear();
    }
}
