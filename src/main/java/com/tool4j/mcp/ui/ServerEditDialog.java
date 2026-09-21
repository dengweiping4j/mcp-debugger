package com.tool4j.mcp.ui;

import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.model.KeyValue;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.TransportType;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpClient;
import com.tool4j.mcp.settings.McpSettings;
import com.tool4j.mcp.util.PluginInfo;

import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;

/**
 * 新建 / 编辑一个 MCP 服务器。
 *
 * <p>刻意把 stdio 与 HTTP 两组字段放在同一个对话框里、用传输方式切换卡片，
 * 而不是拆成两个对话框：用户改主意"还是走 HTTP 吧"的时候不用重开一遍。
 *
 * <p>「测试连接」是这里最有用的东西——它会真的拉一个临时会话跑完整个握手并列出工具数，
 * 通过之后再保存，避免存进去一份连不上的配置。测试用的会话在结束时会立刻关闭
 * （stdio 的子进程也会被收掉），不会留下来占资源。
 */
public final class ServerEditDialog extends DialogWrapper {

    private final Project project;
    private final McpServerConfig original;
    private final McpSettings settings;

    private final JBTextField nameField = new JBTextField();
    private final ComboBox<TransportType> transportCombo = new ComboBox<>(TransportType.values());

    private final CardLayout transportCards = new CardLayout();
    private final JPanel transportHost = new JPanel(transportCards);

    private final JBTextField commandField = new JBTextField();
    private final JBTextArea argsArea = new JBTextArea();
    private final JBTextField workingDirField = new JBTextField();
    private final JBTextField urlField = new JBTextField();

    private final KeyValueTable envTable =
            new KeyValueTable(I18n.t("srv.env.keyHeader"), I18n.t("srv.env.valueHeader"), true);

    private final JBTextField protocolVersionField = new JBTextField();
    private final JBTextField timeoutField = new JBTextField();
    private final JCheckBox enabledCheck = new JCheckBox(I18n.t("srv.check.enabled"));
    private final JCheckBox autoConnectCheck = new JCheckBox(I18n.t("srv.check.autoConnect"));

    private final JButton testButton = new JButton(I18n.t("srv.test.button"));
    private final JBLabel testResult = new JBLabel(" ");
    private boolean testing;

    public ServerEditDialog(@Nullable Project project, @Nullable McpServerConfig existing) {
        super(project, true);
        this.project = project;
        this.original = existing;
        this.settings = McpSettings.getInstance();

        setTitle(I18n.t(existing == null ? "srv.title.new" : "srv.title.edit"));
        setOKButtonText(I18n.t("srv.ok"));
        // 取消按钮也要显式给文案：不给的话平台会用"IDE 的语言"渲染它，
        // 于是插件切成英文时会出现「OK / 取消」这种半中半英的按钮条。
        setCancelButtonText(I18n.t("ui.cancel"));
        loadValues();
        init();
        updateTransportCard();
    }

    // ------------------------------------------------------------------
    // 取值 / 回填
    // ------------------------------------------------------------------

    private void loadValues() {
        McpServerConfig source = original;
        nameField.setText(source == null ? "" : source.getName());
        transportCombo.setSelectedItem(source == null ? TransportType.STDIO : source.getTransport());
        commandField.setText(source == null ? "" : source.getCommand());
        StringBuilder args = new StringBuilder();
        if (source != null) {
            for (String arg : source.getArgs()) {
                args.append(arg).append('\n');
            }
        }
        argsArea.setText(args.toString());
        workingDirField.setText(source == null ? "" : source.getWorkingDir());
        urlField.setText(source == null ? "" : source.getUrl());
        envTable.setRows(source == null ? List.of() : source.getEnv());
        protocolVersionField.setText(source == null || source.getProtocolVersion() == null
                || source.getProtocolVersion().isBlank()
                ? McpServerConfig.DEFAULT_PROTOCOL_VERSION : source.getProtocolVersion());
        timeoutField.setText(String.valueOf(source == null ? 60 : source.getTimeoutSeconds()));
        enabledCheck.setSelected(source == null || source.isEnabled());
        autoConnectCheck.setSelected(source != null && source.isAutoConnect());

        transportCombo.addActionListener(e -> updateTransportCard());
        transportCombo.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                                                                   int index, boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                setText(value instanceof TransportType type ? type.getDisplayName() : String.valueOf(value));
                return this;
            }
        });
    }

    private void updateTransportCard() {
        TransportType transport = selectedTransport();
        transportCards.show(transportHost, transport.isHttp() ? "http" : "stdio");
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JBTabbedPane tabs = new JBTabbedPane();
        tabs.addTab(I18n.t("srv.tab.connection"), Ui.wrap(buildConnectionTab(), 8, 8, 8, 8));
        tabs.addTab(I18n.t("srv.tab.env"), Ui.wrap(buildExtraTab(), 8, 8, 8, 8));
        tabs.addTab(I18n.t("srv.tab.advanced"), Ui.wrap(buildAdvancedTab(), 8, 8, 8, 8));
        tabs.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(380)));
        return tabs;
    }

    private JComponent buildConnectionTab() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        transportHost.setOpaque(false);
        transportHost.add(buildStdioCard(), "stdio");
        transportHost.add(buildHttpCard(), "http");

        int row = 0;
        row = addRow(panel, row, I18n.t("srv.field.name"), nameField,
                I18n.t("srv.field.name.hint"));
        row = addRow(panel, row, I18n.t("srv.field.transport"), transportCombo,
                I18n.t("srv.field.transport.hint"));
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 1;
        constraints.gridy = row;
        constraints.weightx = 1;
        constraints.weighty = 1;
        constraints.fill = GridBagConstraints.BOTH;
        constraints.anchor = GridBagConstraints.NORTHWEST;
        constraints.insets = new Insets(JBUI.scale(6), 0, 0, 0);
        panel.add(transportHost, constraints);
        return panel;
    }

    private JComponent buildStdioCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        int row = 0;
        row = addRow(panel, row, I18n.t("srv.field.command"), commandField,
                I18n.t("srv.field.command.hint"));

        argsArea.setRows(4);
        argsArea.setFont(Ui.monospace());
        JBScrollPane argsScroll = new JBScrollPane(argsArea);
        argsScroll.setPreferredSize(new Dimension(JBUI.scale(320), JBUI.scale(84)));
        row = addRow(panel, row, I18n.t("srv.field.args"), argsScroll,
                I18n.t("srv.field.args.hint"));

        JPanel dirRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        dirRow.setOpaque(false);
        dirRow.add(workingDirField, BorderLayout.CENTER);
        JButton browse = new JButton(I18n.t("srv.browse"));
        browse.addActionListener(e -> {
            VirtualFile file = FileChooser.chooseFile(
                    FileChooserDescriptorFactory.createSingleFolderDescriptor(), project, null);
            if (file != null) {
                workingDirField.setText(file.getPath());
            }
        });
        dirRow.add(browse, BorderLayout.EAST);
        row = addRow(panel, row, I18n.t("srv.field.workdir"), dirRow,
                I18n.t("srv.field.workdir.hint"));

        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = row;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        panel.add(spacer, filler);
        return panel;
    }

    private JComponent buildHttpCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);
        int row = 0;
        row = addRow(panel, row, I18n.t("srv.field.url"), urlField,
                I18n.t("srv.field.url.hint"));

        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = row;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        panel.add(spacer, filler);
        return panel;
    }

    /**
     * 只有环境变量了。
     *
     * <p>请求头原先也在这里，现在搬到了工具窗口右侧的「请求头」栏（和请求 JSON 挨着）——
     * 它是随调试反复改的东西，放在配置对话框里"改一次要点三层、改完还得保存再重连"。
     * 数据仍然是配置里的同一个 {@link McpServerConfig#getHeaders()}，这里不再提供第二个入口。
     */
    private JComponent buildExtraTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        panel.setOpaque(false);
        panel.add(section(I18n.t("srv.env.section"), envTable, I18n.t("srv.env.hint")),
                BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildAdvancedTab() {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(6)));
        panel.setOpaque(false);

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        int row = 0;
        row = addRow(form, row, I18n.t("srv.field.protocol"), protocolVersionField,
                I18n.t("srv.field.protocol.hint", McpServerConfig.DEFAULT_PROTOCOL_VERSION));
        row = addRow(form, row, I18n.t("srv.field.timeout"), timeoutField,
                I18n.t("srv.field.timeout.hint"));
        JPanel flags = new JPanel();
        flags.setOpaque(false);
        flags.setLayout(new BoxLayout(flags, BoxLayout.Y_AXIS));
        flags.add(enabledCheck);
        flags.add(autoConnectCheck);
        addRow(form, row, I18n.t("srv.field.flags"), flags, null);

        JPanel testRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        testRow.setOpaque(false);
        testRow.setBorder(JBUI.Borders.emptyTop(8));
        testButton.setToolTipText(I18n.t("srv.test.tooltip"));
        testButton.addActionListener(e -> testConnection());
        testResult.setFont(Ui.smaller(testResult.getFont()));
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testResult, BorderLayout.CENTER);

        panel.add(form, BorderLayout.NORTH);
        panel.add(Ui.vbox(testRow, Ui.vgap(6), securityNote()), BorderLayout.CENTER);
        return panel;
    }

    private JComponent securityNote() {
        JBLabel note = Ui.htmlHint(I18n.t("srv.security.note"));
        note.setFont(Ui.smaller(note.getFont()));
        return Ui.wrap(note, 2, 2, 2, 2);
    }

    private JComponent section(String title, JComponent body, String hint) {
        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.emptyBottom(6));

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setOpaque(false);
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        headerRow.add(label, BorderLayout.WEST);
        headerRow.add(Ui.wrap(Ui.htmlHint(Ui.escapeHtml(hint)), 0, 6, 0, 0), BorderLayout.CENTER);

        panel.add(headerRow, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        return panel;
    }

    private int addRow(JPanel panel, int row, String label, JComponent field, String hint) {
        JPanel labelPanel = new JPanel();
        labelPanel.setOpaque(false);
        labelPanel.setLayout(new BoxLayout(labelPanel, BoxLayout.X_AXIS));
        JBLabel name = new JBLabel(label);
        name.setFont(name.getFont().deriveFont(Font.BOLD));
        labelPanel.add(name);

        GridBagConstraints labelConstraints = new GridBagConstraints();
        labelConstraints.gridx = 0;
        labelConstraints.gridy = row;
        labelConstraints.anchor = GridBagConstraints.NORTHWEST;
        labelConstraints.insets = new Insets(JBUI.scale(5), 0, JBUI.scale(5), JBUI.scale(12));
        panel.add(labelPanel, labelConstraints);

        JPanel cell = new JPanel();
        cell.setOpaque(false);
        cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
        cell.add(field);
        if (hint != null && !hint.isBlank()) {
            JPanel hintRow = new JPanel(new BorderLayout());
            hintRow.setOpaque(false);
            hintRow.setBorder(JBUI.Borders.emptyTop(2));
            JBLabel hintLabel = Ui.htmlHint(Ui.escapeHtml(hint));
            hintRow.add(hintLabel, BorderLayout.WEST);
            cell.add(hintRow);
        }

        GridBagConstraints fieldConstraints = new GridBagConstraints();
        fieldConstraints.gridx = 1;
        fieldConstraints.gridy = row;
        fieldConstraints.anchor = GridBagConstraints.NORTHWEST;
        fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
        fieldConstraints.weightx = 1;
        fieldConstraints.insets = new Insets(JBUI.scale(5), 0, JBUI.scale(5), 0);
        panel.add(cell, fieldConstraints);
        return row + 1;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return nameField;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "com.tool4j.mcp.ui.ServerEditDialog";
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Override
    protected @Nullable ValidationInfo doValidate() {
        String name = nameField.getText() == null ? "" : nameField.getText().trim();
        if (name.isEmpty()) {
            return new ValidationInfo(I18n.t("srv.err.nameRequired"), nameField);
        }
        if (settings.isNameTaken(name, original == null ? null : original.getId())) {
            return new ValidationInfo(I18n.t("srv.err.nameTaken", name), nameField);
        }
        TransportType transport = selectedTransport();
        if (!transport.isHttp()) {
            String command = commandField.getText() == null ? "" : commandField.getText().trim();
            if (command.isEmpty()) {
                return new ValidationInfo(I18n.t("srv.err.commandRequired"), commandField);
            }
        } else {
            String url = urlField.getText() == null ? "" : urlField.getText().trim();
            if (url.isEmpty()) {
                return new ValidationInfo(I18n.t("srv.err.urlRequired"), urlField);
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return new ValidationInfo(I18n.t("srv.err.urlScheme"), urlField);
            }
        }
        if (parseTimeout() <= 0) {
            return new ValidationInfo(I18n.t("srv.err.timeout"), timeoutField);
        }
        return null;
    }

    private TransportType selectedTransport() {
        Object selected = transportCombo.getSelectedItem();
        return selected instanceof TransportType type ? type : TransportType.STDIO;
    }

    private int parseTimeout() {
        try {
            return Integer.parseInt(timeoutField.getText() == null ? "" : timeoutField.getText().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 结果
    // ------------------------------------------------------------------

    /** 按表单内容生成配置（不修改原对象）。 */
    public McpServerConfig buildConfig() {
        McpServerConfig config = new McpServerConfig();
        config.setName(nameField.getText() == null ? "" : nameField.getText().trim());
        config.setTransport(selectedTransport());
        config.setCommand(commandField.getText() == null ? "" : commandField.getText().trim());

        List<String> args = new ArrayList<>();
        for (String line : argsArea.getText().split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                args.add(trimmed);
            }
        }
        config.setArgs(args);
        config.setWorkingDir(workingDirField.getText() == null ? "" : workingDirField.getText().trim());
        config.setUrl(urlField.getText() == null ? "" : urlField.getText().trim());
        config.setEnv(envTable.getRows());
        // 请求头不在这个对话框里编辑（见工具窗口的「请求头」栏），但编辑已有服务器时必须原样带过去：
        // applyTo() 是整份覆盖，不带上就等于"点一次保存把 token 抹掉"。
        config.setHeaders(copyHeaders(original));
        String protocolVersion = protocolVersionField.getText() == null
                ? "" : protocolVersionField.getText().trim();
        config.setProtocolVersion(protocolVersion.isEmpty()
                ? McpServerConfig.DEFAULT_PROTOCOL_VERSION : protocolVersion);
        config.setTimeoutSeconds(Math.max(1, parseTimeout()));
        config.setEnabled(enabledCheck.isSelected());
        config.setAutoConnect(autoConnectCheck.isSelected());
        return config;
    }

    /** 编辑时用这个拿结果：在保留 id 的前提下覆盖原对象。 */
    public void applyTo(McpServerConfig target) {
        target.applyFrom(buildConfig());
    }

    private static List<KeyValue> copyHeaders(@Nullable McpServerConfig source) {
        List<KeyValue> out = new ArrayList<>();
        if (source != null) {
            for (KeyValue kv : source.getHeaders()) {
                out.add(kv.copy());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 测试连接
    // ------------------------------------------------------------------

    private void testConnection() {
        if (testing) {
            return;
        }
        ValidationInfo info = doValidate();
        if (info != null) {
            setTestResult(Ui.ERROR, "✘ " + I18n.t("srv.test.validating", info.message));
            return;
        }
        McpServerConfig probe = buildConfig();
        testing = true;
        testButton.setEnabled(false);
        setTestResult(JBColor.GRAY, I18n.t("srv.test.connecting"));

        Bg.run(project, I18n.t("srv.test.task"), true, () -> {
            McpClient client = new McpClient(probe);
            client.setClientVersion(PluginInfo.version());
            try {
                client.connect();
                StringBuilder sb = new StringBuilder(
                        I18n.t("srv.test.ok", client.getServerInfo().getSummary()));
                sb.append(I18n.t("srv.test.tools", client.getTools().size()));
                if (!client.getResources().isEmpty()) {
                    sb.append(I18n.t("srv.test.resources", client.getResources().size()));
                }
                if (!client.getPrompts().isEmpty()) {
                    sb.append(I18n.t("srv.test.prompts", client.getPrompts().size()));
                }
                return sb.toString();
            } finally {
                // 探针会话必须收干净：stdio 会留下子进程，sse 会留下长连接
                client.close();
            }
        }, message -> {
            testing = false;
            testButton.setEnabled(true);
            setTestResult(Ui.OK, "✔ " + message);
        }, error -> {
            testing = false;
            testButton.setEnabled(true);
            setTestResult(Ui.ERROR, "✘ " + JsonUtil.oneLine(McpException.describe(error)));
        });
    }

    private void setTestResult(JBColor color, String text) {
        testResult.setForeground(color);
        testResult.setText("<html>" + Ui.escapeHtml(text) + "</html>");
        testResult.setToolTipText(text);
    }

}
