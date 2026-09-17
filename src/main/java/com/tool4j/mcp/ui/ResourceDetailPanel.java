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
    private final JButton readButton = new JButton("读取");
    private final ResultView resultView;

    private McpResource resource;
    private Callback callback;
    private boolean busy;

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

        uriField.setToolTipText("要读取的 URI；资源模板需要先把占位符填掉");
        uriField.addActionListener(e -> read());

        readButton.setToolTipText("读取该资源（Ctrl+Enter）");
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
        titleLabel.setText("选择一个资源");
        metaLabel.setText("");
        descriptionArea.setText("资源是服务端暴露的可读数据（文件、数据库行、日志…），点左侧列表里的资源即可读取。"
                + "带 {占位符} 的是资源模板，读取前把占位符换成真实值。");
        descriptionArea.setCaretPosition(0);
        uriField.setText("");
        uriField.setEnabled(false);
        readButton.setEnabled(false);
        statusLabel.setText("");
        resultView.clear();
    }

    public void showResource(McpResource resource) {
        this.resource = resource;
        titleLabel.setText(resource.getName());
        StringBuilder meta = new StringBuilder();
        if (resource.getMimeType() != null && !resource.getMimeType().isBlank()) {
            meta.append(resource.getMimeType());
        }
        if (resource.isTemplate()) {
            meta.append(meta.length() > 0 ? "  ·  " : "").append("URI 模板，需要填占位符");
        }
        metaLabel.setText(meta.toString());
        String description = resource.getDescription();
        descriptionArea.setText(description == null || description.isBlank()
                ? "URI：" + resource.getUri()
                : description);
        descriptionArea.setCaretPosition(0);
        uriField.setEnabled(true);
        uriField.setText(resource.getUri());
        readButton.setEnabled(true);
        statusLabel.setText("");
        resultView.clear();
    }

    private void setBusy(boolean value) {
        busy = value;
        readButton.setEnabled(!value && resource != null);
        readButton.setText(value ? "读取中…" : "读取");
    }

    public void read() {
        if (busy || resource == null || callback == null) {
            return;
        }
        String uri = uriField.getText();
        if (uri == null || uri.isBlank()) {
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("请填写 URI");
            return;
        }
        setBusy(true);
        statusLabel.setForeground(com.intellij.ui.JBColor.GRAY);
        statusLabel.setText("正在读取 " + uri);
        resultView.showLoading("资源 " + uri);
        callback.readResource(resource, uri.trim());
    }

    public void showResult(McpCallResult result) {
        setBusy(false);
        resultView.show(result, "资源");
        if (result != null && result.getError() != null) {
            statusLabel.setForeground(Ui.ERROR);
            statusLabel.setText("读取失败");
        } else if (result != null) {
            statusLabel.setForeground(Ui.OK);
            statusLabel.setText("完成 · " + result.getElapsedMillis() + " ms");
        }
    }

    public void showError(String message) {
        setBusy(false);
        statusLabel.setForeground(Ui.ERROR);
        statusLabel.setText("读取失败");
        resultView.showFailure(message, "资源");
    }

    @Override
    public void dispose() {
        resultView.dispose();
    }
}
