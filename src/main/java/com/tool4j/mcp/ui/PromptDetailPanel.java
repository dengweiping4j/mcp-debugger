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
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.protocol.JsonUtil;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
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
    private final JBTextArea descriptionArea = new JBTextArea();
    private final JBLabel statusLabel = Ui.hint("");
    private final JPanel argsHost = new JPanel(new GridBagLayout());
    private final JButton fetchButton = new JButton("获取");
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JPanel messagesHost = new JPanel();
    private final com.intellij.ui.EditorTextField rawViewer;

    private final Map<String, JBTextField> argFields = new LinkedHashMap<>();

    private McpPrompt prompt;
    private Callback callback;
    private boolean busy;

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

        tabs.addTab("消息", messagesScroll);
        tabs.addTab("原始 JSON", rawPanel);

        JBSplitter splitter = new JBSplitter(true, 0.45f);
        splitter.setFirstComponent(buildHeader());
        splitter.setSecondComponent(tabs);
        // 分隔条用平台默认宽度（7px）：鼠标热区就是分隔条本身，压窄了会抓不住
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);

        clear();
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
        descriptionScroll.setPreferredSize(new Dimension(0, JBUI.scale(44)));

        fetchButton.setToolTipText("获取该提示词（Ctrl+Enter）");
        fetchButton.addActionListener(e -> fetch());

        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.setOpaque(false);
        buttonRow.setBorder(JBUI.Borders.empty(4, 0, 0, 0));
        buttonRow.add(Ui.hbox(fetchButton, Ui.hgap(8), statusLabel), BorderLayout.WEST);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        header.add(titleRow, BorderLayout.NORTH);
        header.add(descriptionScroll, BorderLayout.CENTER);
        header.add(Ui.vbox(argsHost, buttonRow), BorderLayout.SOUTH);
        return header;
    }

    // ------------------------------------------------------------------

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void clear() {
        prompt = null;
        titleLabel.setText("选择一个提示词");
        metaLabel.setText("");
        descriptionArea.setText("提示词是服务端预置的模板，取回来就是一组可直接塞进对话的消息。"
                + "点左侧列表里的提示词，填好参数后点「获取」。");
        descriptionArea.setCaretPosition(0);
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
        titleLabel.setText(prompt.getName());
        metaLabel.setText(prompt.getSubtitle());
        String description = prompt.getDescription();
        descriptionArea.setText(description == null || description.isBlank() ? "（该提示词没有提供描述）" : description);
        descriptionArea.setCaretPosition(0);
        statusLabel.setText("");
        messagesHost.removeAll();
        rawViewer.setText("");

        argFields.clear();
        argsHost.removeAll();
        if (prompt.getArguments().isEmpty()) {
            JBLabel none = Ui.hint("该提示词没有参数");
            none.setBorder(JBUI.Borders.empty(4, 2));
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
                    star.setToolTipText("必填");
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
        setBusy(false);
    }

    private void setBusy(boolean value) {
        busy = value;
        fetchButton.setEnabled(!value && prompt != null);
        fetchButton.setText(value ? "获取中…" : "获取");
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
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("缺少必填参数：" + String.join("、", missing));
            return;
        }

        setBusy(true);
        statusLabel.setForeground(JBColor.GRAY);
        statusLabel.setText("正在获取…");
        messagesHost.removeAll();
        messagesHost.revalidate();
        callback.getPrompt(prompt, arguments);
    }

    public void showResult(McpCallResult result) {
        setBusy(false);
        messagesHost.removeAll();
        if (result == null) {
            return;
        }
        rawViewer.setText(JsonUtil.pretty(result.getRaw()));

        if (result.getError() != null) {
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("获取失败");
            messagesHost.add(Ui.textBody(result.getError(), 0, Ui.ERROR));
        } else {
            statusLabel.setForeground(Ui.OK);
            statusLabel.setText("完成 · " + result.getElapsedMillis() + " ms");
            String description = JsonUtil.str(result.getRaw(), "description", null);
            if (description != null && !description.isBlank()) {
                messagesHost.add(messageCard("说明", description));
            }
            JsonArray messages = JsonUtil.array(result.getRaw(), "messages");
            int index = 1;
            for (JsonElement element : messages) {
                if (element.isJsonObject()) {
                    messagesHost.add(renderMessage(element.getAsJsonObject(), index++));
                }
            }
            if (messages.isEmpty()) {
                messagesHost.add(messageCard("结果", "服务端没有返回 messages。"));
            }
        }
        messagesHost.revalidate();
        messagesHost.repaint();
    }

    public void showError(String message) {
        setBusy(false);
        statusLabel.setForeground(Ui.ERROR);
        statusLabel.setText("获取失败");
        messagesHost.removeAll();
        messagesHost.add(Ui.textBody(message, 0, Ui.ERROR));
        messagesHost.revalidate();
        messagesHost.repaint();
    }

    private JComponent renderMessage(JsonObject message, int index) {
        String role = JsonUtil.str(message, "role", "unknown");
        JsonElement content = message.get("content");
        String text;
        if (content == null || content.isJsonNull()) {
            text = "（无内容）";
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
        String title = "消息 " + index + "  ·  " + role;
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
