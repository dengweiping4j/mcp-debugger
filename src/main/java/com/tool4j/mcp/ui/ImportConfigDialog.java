package com.tool4j.mcp.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.protocol.McpConfigParser;
import com.tool4j.mcp.util.McpConfigFiles;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把别处已有的 MCP 配置搬进来。
 *
 * <p>两条路都留着，因为实际用起来是两种场景：
 * <ul>
 *   <li><b>从文件导入</b>——机器上已经装了 Claude Desktop / Cursor / VS Code，或者工程里已经有
 *       {@code .mcp.json}，直接扫出来勾一个就行，不用手抄。</li>
 *   <li><b>粘贴 JSON</b>——从文档、Issue、聊天记录里复制一段 {@code mcpServers} 过来。</li>
 * </ul>
 *
 * <p>这个对话框只负责"解析成配置列表"，重名去重与落盘交给调用方（{@code McpPanel}），
 * 那边能看到全局状态，也能在日志里报告"跳过几个重复"。
 */
public final class ImportConfigDialog extends DialogWrapper {

    /** 从本机 / 工程的配置文件导入。 */
    public static ImportConfigDialog forFiles(@Nullable Project project) {
        return new ImportConfigDialog(project, Source.FILES);
    }

    /** 粘贴一段 JSON 导入。 */
    public static ImportConfigDialog forPaste(@Nullable Project project) {
        return new ImportConfigDialog(project, Source.PASTE);
    }

    private enum Source { FILES, PASTE }

    private final Project project;
    private final Disposable editorHolder;
    private final JBTabbedPane tabs = new JBTabbedPane();

    private final DefaultListModel<McpConfigFiles.Candidate> fileModel = new DefaultListModel<>();
    private final JBList<McpConfigFiles.Candidate> fileList = new JBList<>(fileModel);
    private final JBLabel fileHint = Ui.hint(" ");
    private final JBTextArea filePreview = previewArea();

    private final EditorTextField pasteField;

    private ImportConfigDialog(@Nullable Project project, @NotNull Source initial) {
        super(project, true);
        this.project = project;
        // DialogWrapper 本身不是 Disposable，给内嵌编辑器单独开一个生命周期
        this.editorHolder = Disposer.newDisposable("McpImportDialogEditors");
        this.pasteField = Editors.jsonEditor(project, "", editorHolder);

        // 这个对话框每次导入都新建一个，所以构造时取一次文案就够，不需要 applyTexts()
        setTitle(I18n.t("imp.title"));
        setOKButtonText(I18n.t("imp.ok"));
        setCancelButtonText(I18n.t("ui.cancel"));

        init();
        tabs.setSelectedIndex(initial == Source.FILES ? 0 : 1);
    }

    @Override
    public void dispose() {
        Disposer.dispose(editorHolder);
        super.dispose();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        tabs.addTab(I18n.t("imp.tab.files"), Ui.wrap(buildFilesTab(), 8, 8, 8, 8));
        tabs.addTab(I18n.t("imp.tab.paste"), Ui.wrap(buildPasteTab(), 8, 8, 8, 8));
        tabs.addChangeListener(e -> updatePreview());
        tabs.setPreferredSize(new Dimension(JBUI.scale(620), JBUI.scale(400)));

        scanFiles();
        fileList.addListSelectionListener(e -> updatePreview());
        if (fileModel.getSize() > 0) {
            fileList.setSelectedIndex(0);
        } else {
            updatePreview();
        }
        return tabs;
    }

    private JComponent buildFilesTab() {
        fileList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        fileList.setCellRenderer(new SimpleListCellRenderer<McpConfigFiles.Candidate>() {
            @Override
            public void customize(@NotNull JList<? extends McpConfigFiles.Candidate> list,
                                  McpConfigFiles.Candidate value, int index, boolean selected, boolean hasFocus) {
                if (value == null) {
                    return;
                }
                setText(value.getLabel());
                setToolTipText(value.getPath());
            }
        });

        JBScrollPane listScroll = new JBScrollPane(fileList);
        listScroll.setPreferredSize(new Dimension(JBUI.scale(600), JBUI.scale(120)));

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setOpaque(false);
        panel.add(fileHint, BorderLayout.NORTH);
        panel.add(listScroll, BorderLayout.CENTER);

        JBScrollPane previewScroll = new JBScrollPane(filePreview);
        previewScroll.setPreferredSize(new Dimension(JBUI.scale(600), JBUI.scale(150)));
        previewScroll.setBorder(JBUI.Borders.customLine(JBColor.border()));
        panel.add(Ui.vbox(Ui.sectionTitle(I18n.t("imp.preview.title")), previewScroll), BorderLayout.SOUTH);
        return panel;
    }

    private JComponent buildPasteTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setOpaque(false);
        panel.add(Ui.htmlHint(I18n.t("imp.paste.hint")), BorderLayout.NORTH);
        panel.add(Editors.sized(pasteField, 320), BorderLayout.CENTER);
        return panel;
    }

    private static JBTextArea previewArea() {
        JBTextArea area = new JBTextArea();
        area.setEditable(false);
        area.setFont(Ui.monospace());
        area.setBorder(JBUI.Borders.empty(4, 6));
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        return area;
    }

    // ------------------------------------------------------------------
    // 扫描 / 解析
    // ------------------------------------------------------------------

    private void scanFiles() {
        String basePath = project == null ? null : project.getBasePath();
        // 拷一份再排序：scan() 不保证返回可变列表
        List<McpConfigFiles.Candidate> candidates = new ArrayList<>(McpConfigFiles.scan(basePath));
        // 工程内的配置排前面：用户在这个工程里调试，多半就是想连这个工程自己的 MCP 服务
        candidates.sort(Comparator.comparingInt(
                (McpConfigFiles.Candidate candidate) -> candidate.isProjectScoped() ? 0 : 1));
        for (McpConfigFiles.Candidate candidate : candidates) {
            fileModel.addElement(candidate);
        }
        if (candidates.isEmpty()) {
            fileHint.setText(I18n.t("imp.files.empty"));
        } else {
            fileHint.setText(I18n.t("imp.files.found", candidates.size()));
        }
    }

    /** 当前页签将要导入的配置；解析失败或没选中时返回空列表。 */
    private List<McpServerConfig> parsed() {
        return parse(currentText()).getServers();
    }

    private McpConfigParser.Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return McpConfigParser.parse("");
        }
        return McpConfigParser.parse(text);
    }

    @Nullable
    private String currentText() {
        if (tabs.getSelectedIndex() == 1) {
            return pasteField.getText();
        }
        McpConfigFiles.Candidate selected = fileList.getSelectedValue();
        return selected == null ? null : McpConfigFiles.readQuietly(selected.getFile());
    }

    private void updatePreview() {
        if (tabs.getSelectedIndex() == 1) {
            // 粘贴页签的内容随打随变，实时解析会把大段 JSON 反复跑一遍，这里只在点「导入」时校验
            filePreview.setText(I18n.t("imp.preview.pasteDeferred"));
            return;
        }
        McpConfigFiles.Candidate selected = fileList.getSelectedValue();
        if (selected == null) {
            filePreview.setText(I18n.t("imp.preview.noSelection"));
            return;
        }
        renderPreview(selected.getLabel(), selected.getPath(), parse(currentText()));
    }

    private void renderPreview(String label, String path, @NotNull McpConfigParser.Parsed result) {
        StringBuilder sb = new StringBuilder();
        sb.append(label).append('\n').append(path).append("\n\n");
        if (result.getServers().isEmpty()) {
            sb.append(I18n.t("imp.preview.noServers")).append('\n');
            sb.append(I18n.t("imp.preview.noServersReason")).append('\n');
        } else {
            sb.append(I18n.t("imp.preview.serverCount", result.getServers().size())).append('\n');
            for (McpServerConfig config : result.getServers()) {
                sb.append("  · ").append(config.getDisplayName())
                        .append("  [").append(config.getTransport().getDisplayName()).append("]  ")
                        .append(Ui.ellipsize(config.getEndpointSummary(), 70))
                        .append('\n');
            }
        }
        for (String warning : result.getWarnings()) {
            sb.append("\n").append(I18n.t("imp.preview.warning")).append(warning).append('\n');
        }
        filePreview.setText(sb.toString());
        filePreview.setCaretPosition(0);
    }

    // ------------------------------------------------------------------
    // 校验 / 结果
    // ------------------------------------------------------------------

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (tabs.getSelectedIndex() == 1) {
            String text = pasteField.getText();
            if (text == null || text.isBlank()) {
                return new ValidationInfo(I18n.t("imp.validate.pasteEmpty"), pasteField);
            }
            if (parse(text).getServers().isEmpty()) {
                return new ValidationInfo(I18n.t("imp.validate.pasteNoServers"),
                        pasteField);
            }
            return null;
        }
        McpConfigFiles.Candidate selected = fileList.getSelectedValue();
        if (selected == null) {
            return new ValidationInfo(I18n.t("imp.validate.noFileSelected"), fileList);
        }
        if (parse(currentText()).getServers().isEmpty()) {
            return new ValidationInfo(I18n.t("imp.validate.fileNoServers"), fileList);
        }
        return null;
    }

    /**
     * 弹出对话框并返回解析结果。
     *
     * @return 用户点了「导入」且校验通过时的服务器列表；取消返回 {@code null}
     */
    @Nullable
    public List<McpServerConfig> showAndGetServers() {
        if (!showAndGet()) {
            return null;
        }
        List<McpServerConfig> servers = parsed();
        return servers.isEmpty() ? null : servers;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "com.tool4j.mcp.ui.ImportConfigDialog";
    }
}
