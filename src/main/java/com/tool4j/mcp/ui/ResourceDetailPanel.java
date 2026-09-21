package com.tool4j.mcp.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpResource;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 资源详情：读取一个 {@code resources/read} 目标。
 *
 * <p>URI 做成<b>可编辑输入框</b>而不是只读文本：资源模板（{@code resources/templates/list}）
 * 拿到的是带占位符的模板，必须先把占位符填掉才能读；就算是普通资源，调试时想换个 URI 试试也很常见。
 */
public final class ResourceDetailPanel extends JPanel implements Disposable {

    public interface Callback {
        void readResource(McpResource resource, String uri);
    }

    private final JBLabel titleLabel = new JBLabel(" ");
    private final JBLabel metaLabel = Ui.hint(" ");
    private final JBTextArea descriptionArea = new JBTextArea();
    private final JBTextField uriField = new JBTextField();
    private final JBLabel statusLabel = Ui.hint("");
    private final JButton readButton = new JButton(I18n.t("res.read.label"));
    private final ResultView resultView;

    private McpResource resource;
    private Callback callback;
    private boolean busy;

    private enum Status { IDLE, MISSING_URI, READING, FAILED, DONE }

    /** 状态行的种类，applyTexts 据此重画，避免语言切换后状态文案卡在旧语言。 */
    private Status status = Status.IDLE;
    /** 最近一次读取的 URI（READING / MISSING_URI 状态要显示）。 */
    private String statusUri;
    /** 最近一次成功的耗时（DONE 状态要显示）。 */
    private long statusElapsed;

    public ResourceDetailPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);

        resultView = new ResultView(project, parent);
        JBSplitter splitter = new JBSplitter(true, 0.42f);
        splitter.setFirstComponent(buildHeader());
        splitter.setSecondComponent(resultView);
        // 分隔条用平台默认宽度（7px）：鼠标热区就是分隔条本身，压窄了会抓不住
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setShowDividerControls(false);
        add(splitter, BorderLayout.CENTER);

        installShortcuts();
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

        uriField.setToolTipText(I18n.t("res.uri.tooltip"));
        uriField.addActionListener(e -> read());

        readButton.setToolTipText(I18n.t("res.read.tooltip"));
        readButton.addActionListener(e -> read());

        JPanel uriRow = new JPanel(new BorderLayout());
        uriRow.setOpaque(false);
        uriRow.setBorder(JBUI.Borders.empty(4, 0, 2, 0));
        uriRow.add(uriField, BorderLayout.CENTER);
        uriRow.add(Ui.wrap(readButton, 0, 6, 0, 0), BorderLayout.EAST);

        JPanel statusRow = new JPanel(new BorderLayout());
        statusRow.setOpaque(false);
        statusRow.add(statusLabel, BorderLayout.WEST);

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        header.add(titleRow, BorderLayout.NORTH);
        header.add(descriptionScroll, BorderLayout.CENTER);
        header.add(Ui.vbox(uriRow, statusRow), BorderLayout.SOUTH);
        return header;
    }

    private void installShortcuts() {
        InputMap inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "mcp.read");
        actionMap.put("mcp.read", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                read();
            }
        });
    }

    // ------------------------------------------------------------------

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    public void clear() {
        resource = null;
        status = Status.IDLE;
        titleLabel.setText(I18n.t("res.title.empty"));
        metaLabel.setText("");
        descriptionArea.setText(I18n.t("res.help.text"));
        descriptionArea.setCaretPosition(0);
        uriField.setText("");
        uriField.setEnabled(false);
        readButton.setEnabled(false);
        statusLabel.setText("");
        resultView.clear();
    }

    public void showResource(McpResource resource) {
        this.resource = resource;
        renderResourceInfo();
        uriField.setEnabled(true);
        uriField.setText(resource.getUri());
        readButton.setEnabled(true);
        status = Status.IDLE;
        statusLabel.setText("");
        resultView.clear();
    }

    /** 从绑定的 resource 重画标题 / 元信息 / 描述（不碰 URI 输入框等数据）。 */
    private void renderResourceInfo() {
        if (resource == null) {
            return;
        }
        titleLabel.setText(resource.getName());
        StringBuilder meta = new StringBuilder();
        if (resource.getMimeType() != null && !resource.getMimeType().isBlank()) {
            meta.append(resource.getMimeType());
        }
        if (resource.isTemplate()) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append(I18n.t("res.meta.template"));
        }
        metaLabel.setText(meta.toString());
        String description = resource.getDescription();
        descriptionArea.setText(description == null || description.isBlank()
                ? I18n.t("res.description.uriPrefix", resource.getUri())
                : description);
        descriptionArea.setCaretPosition(0);
    }

    private void setBusy(boolean value) {
        busy = value;
        readButton.setEnabled(!value && resource != null);
        readButton.setText(value ? I18n.t("res.read.busy") : I18n.t("res.read.label"));
    }

    public void read() {
        if (busy || resource == null || callback == null) {
            return;
        }
        String uri = uriField.getText();
        if (uri == null || uri.isBlank()) {
            status = Status.MISSING_URI;
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText(I18n.t("res.status.missingUri"));
            return;
        }
        setBusy(true);
        status = Status.READING;
        statusUri = uri;
        statusLabel.setForeground(com.intellij.ui.JBColor.GRAY);
        statusLabel.setText(I18n.t("res.status.reading", uri));
        resultView.showLoading(null);
        // 主语交给文案键解析，语言切换后才会跟着换；传渲染好的文本会卡在旧语言
        resultView.setSubjectKey("res.subject");
        callback.readResource(resource, uri.trim());
    }

    public void showResult(McpCallResult result) {
        setBusy(false);
        resultView.show(result, null);
        resultView.setSubjectKey("res.subject");
        if (result != null && result.getError() != null) {
            status = Status.FAILED;
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText(I18n.t("res.status.failed"));
        } else if (result != null) {
            status = Status.DONE;
            statusElapsed = result.getElapsedMillis();
            statusLabel.setForeground(Ui.OK);
            statusLabel.setText(I18n.t("res.status.done", result.getElapsedMillis()));
        }
    }

    public void showError(String message) {
        setBusy(false);
        status = Status.FAILED;
        statusLabel.setForeground(Ui.ERROR);
        statusLabel.setText(I18n.t("res.status.failed"));
        resultView.showFailure(message, null);
        resultView.setSubjectKey("res.subject");
    }

    /** 语言切换时重画所有界面文字；不碰数据，可随时安全调用。 */
    public void applyTexts() {
        uriField.setToolTipText(I18n.t("res.uri.tooltip"));
        readButton.setToolTipText(I18n.t("res.read.tooltip"));
        readButton.setText(I18n.t(busy ? "res.read.busy" : "res.read.label"));

        if (resource == null) {
            titleLabel.setText(I18n.t("res.title.empty"));
            metaLabel.setText("");
            descriptionArea.setText(I18n.t("res.help.text"));
            descriptionArea.setCaretPosition(0);
        } else {
            renderResourceInfo();
        }
        renderStatus();
        // 结果区是常驻子组件，它自己的状态条与复制按钮也要跟着换语言
        resultView.applyTexts();
    }

    private void renderStatus() {
        switch (status) {
            case IDLE:
                statusLabel.setText("");
                break;
            case MISSING_URI:
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText(I18n.t("res.status.missingUri"));
                break;
            case READING:
                statusLabel.setForeground(com.intellij.ui.JBColor.GRAY);
                statusLabel.setText(I18n.t("res.status.reading", statusUri));
                break;
            case FAILED:
                statusLabel.setForeground(Ui.ERROR);
                statusLabel.setText(I18n.t("res.status.failed"));
                break;
            case DONE:
                statusLabel.setForeground(Ui.OK);
                statusLabel.setText(I18n.t("res.status.done", statusElapsed));
                break;
        }
    }

    @Override
    public void dispose() {
        resultView.dispose();
    }
}
