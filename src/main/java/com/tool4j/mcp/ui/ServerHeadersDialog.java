package com.tool4j.mcp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.TransportType;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpClient;
import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.util.PluginInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * <b>服务器级</b>请求头的编辑弹窗。
 *
 * <p><b>它编辑的是哪一份：</b>请求头分两级，这里管的是{@link McpServerConfig#getHeaders()
 * 服务器级}——带在<b>所有</b>请求上，含 {@code initialize} 握手与 SSE 的建连。工具级的
 * （只作用于某个工具的 {@code tools/call}）在工具详情的「请求头」页签上，见
 * {@link RequestHeadersPanel}。两者表单长得一样，共用 {@link HeadersFormPanel}。
 *
 * <p><b>为什么是弹窗。</b>它原先住在详情区的一张卡片里，改了就要点开、看完还要再点回去，
 * 而这一层和"当前选中哪个工具"没有关系，摆在详情区里总让人以为它属于某个条目。挪进弹窗后，
 * 两级的归属一目了然：服务器的归服务器（工具栏那个按钮 / 本弹窗），工具的归工具（页签）。
 *
 * <p><b>写入时机是「确定」。</b>取消 / 直接关窗都不动配置里的旧值。这一点和原先的卡片相反
 * （那边每敲一个字就写一次），换来的是"改坏了能退回去"——服务器级请求头里往往就一个 token，
 * 边改边写意味着手一滑就把能连上的那个值盖掉了。
 *
 * <p><b>「测试连接」用的是表单里未保存的值。</b>填完 token 最自然的下一步就是试一次，
 * 要是只能先确定再回主面板点连接，那这个按钮就没必要存在了。所以它在配置的<b>副本</b>
 * 上挂表单当前的头去握手，探针会话用完立刻关掉。
 */
public final class ServerHeadersDialog extends DialogWrapper {

    private final Project project;
    /** 要写回的配置对象。{@code null} 的副本不会被写出去（弹窗打开期间服务器被删的情形）。 */
    private final McpServerConfig config;
    private final HeadersFormPanel form;

    private final JBLabel stateLabel = Ui.hint(" ");
    private final JButton testButton = new JButton(I18n.t("srv.test.button"));
    private final JBLabel testResult = new JBLabel(" ");
    private boolean testing;

    public ServerHeadersDialog(@Nullable Project project, @NotNull McpServerConfig config) {
        super(project, true);
        this.project = project;
        this.config = config;

        setTitle(I18n.t("panel.headers.title"));
        // 两个按钮都要显式给文案：只设 OK 的话平台会用自己的语言渲染取消键，
        // 插件切成英文时就会出现「OK / 取消」这种半中半英的按钮条。
        setOKButtonText(I18n.t("ui.ok"));
        setCancelButtonText(I18n.t("ui.cancel"));

        // 键列给得比页签宽：弹窗有五百多像素，Authorization / Content-Type 这类名字该看全
        form = new HeadersFormPanel(150);
        form.setRows(config.getHeaders());
        form.setOnChanged(this::updateState);

        init();
        updateState();
    }

    // ------------------------------------------------------------------
    // 界面
    // ------------------------------------------------------------------

    @Override
    protected @Nullable JComponent createCenterPanel() {
        // 说明文字走 HTML 定宽 div：不限宽的话这一整段不会折行，会把弹窗顶得很宽
        JBLabel hint = Ui.htmlHint("<div style='width:" + JBUI.scale(500) + "px'>"
                + Ui.escapeHtml(I18n.t("panel.headers.hint")) + "</div>");
        hint.setBorder(JBUI.Borders.emptyBottom(6));

        testButton.setFocusable(false);
        testButton.addActionListener(e -> testConnection());
        testResult.setFont(Ui.smaller(testResult.getFont()));

        JPanel testRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        testRow.setOpaque(false);
        testRow.setBorder(JBUI.Borders.emptyTop(8));
        testRow.add(testButton, BorderLayout.WEST);
        testRow.add(testResult, BorderLayout.CENTER);

        JPanel south = new JPanel();
        south.setOpaque(false);
        south.setLayout(new BoxLayout(south, BoxLayout.Y_AXIS));
        south.add(testRow);
        south.add(Ui.vgap(2));
        south.add(stateLabel);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setPreferredSize(new Dimension(JBUI.scale(560), JBUI.scale(340)));
        panel.add(hint, BorderLayout.NORTH);
        panel.add(form, BorderLayout.CENTER);
        panel.add(south, BorderLayout.SOUTH);
        return panel;
    }

    @Override
    protected @Nullable String getDimensionServiceKey() {
        return "com.tool4j.mcp.ui.ServerHeadersDialog";
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    @Override
    protected void doOKAction() {
        // 只有点「确定」才落盘。传输层每次请求都重新读 config，所以写完立刻生效、不用重连
        // （SSE 长连接上的头是建连时带的，那条连接要重连才会换）。
        config.setHeaders(form.getRows());
        super.doOKAction();
    }

    /** 生效条数。文案每次都现拼，切语言的场景由"重开弹窗"覆盖（模态对话框一律每回 new 一个）。 */
    private void updateState() {
        int n = form.count();
        stateLabel.setForeground(n == 0 ? Ui.MUTED : Ui.OK);
        stateLabel.setText(n == 0
                ? I18n.t("panel.headers.state.unset")
                : I18n.t("panel.headers.state.active", n));
        stateLabel.setToolTipText(I18n.t("panel.headers.state.tip"));
    }

    // ------------------------------------------------------------------
    // 测试连接
    // ------------------------------------------------------------------

    /**
     * 用"表单里当前的头"试一次握手。
     *
     * <p>走配置的副本，<b>不</b>动用户配置：测试是只读的，点完发现白测一场还要手动清掉
     * 就本末倒置了。副本走 {@link McpServerConfig#copy()}，url / 传输方式 / 超时都带上，
     * 只有 headers 换成表单里这一份。
     */
    private void testConnection() {
        if (testing) {
            return;
        }
        TransportType transport = config.getTransport();
        if (transport == null || !transport.isHttp()) {
            setTestResult(Ui.ERROR, I18n.t("panel.headers.noHttp"));
            return;
        }
        if (config.getUrl() == null || config.getUrl().isBlank()) {
            // 请求头再全，没有地址也没得试。这里直接点破，别让用户等一圈握手失败
            setTestResult(Ui.ERROR, I18n.t("srv.err.urlRequired"));
            return;
        }

        McpServerConfig probe = config.copy();
        probe.setHeaders(form.getRows());

        testing = true;
        testButton.setEnabled(false);
        setTestResult(JBColor.GRAY, I18n.t("srv.test.connecting"));
        Bg.run(project, I18n.t("srv.test.task"), true, () -> {
            McpClient client = new McpClient(probe);
            client.setClientVersion(PluginInfo.version());
            try {
                client.connect();
                return I18n.t("srv.test.ok", client.getServerInfo().getSummary())
                        + I18n.t("srv.test.tools", client.getTools().size());
            } finally {
                // 探针会话必须收干净：http / sse 都会留下连接
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
