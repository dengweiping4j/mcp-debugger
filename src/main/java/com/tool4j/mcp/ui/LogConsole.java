package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.protocol.JsonRpc;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.settings.McpSettings;

import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 报文日志控制台。
 *
 * <p>对调试 MCP 来说，这个面板的价值不低于调用面板本身：大多数"工具调不通"其实是协议层的问题
 * （没发 initialized、方法名不对、返回 -32602、子进程 stderr 里在报错），而这些从报文里一眼就能看出来。
 *
 * <p>四个来源分开着色：出站报文、入站报文、子进程 stderr / 传输层提示、以及本插件自己的报错。
 * 用 {@link JTextPane} 是为了拿 {@link StyledDocument} 上色（平台没有 JBTextPane，而 JBTextArea
 * 是 JTextArea，只有 PlainDocument）。背景/前景显式取自 {@link UIUtil}，跟随 IDE 主题。
 *
 * <p>标题栏按"窄边栏"设计：折叠箭头是图标按钮，左边只留"箭头 + 标题"，
 * 计数放中间（宽度不够时被省略号截断的就是它），右边的开关与清空永远够得着。
 */
public final class LogConsole extends JPanel {

    /** 文档超过这个长度就从头部裁掉，避免跑久了把 EDT 拖死。 */
    private static final int MAX_CHARS = 400_000;
    /** 单条报文最多显示这么长；完整报文永远能在「原始 JSON」页签里看。 */
    private static final int PAYLOAD_LIMIT = 4_000;

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS");

    private final JTextPane area = new JTextPane();
    private final JBLabel title = new JBLabel("报文日志");
    private final JBLabel summary = Ui.hint("暂无日志");
    private final JBCheckBox payloadToggle = new JBCheckBox("报文体", true);

    /** 点折叠箭头时回调（主面板负责隐藏本面板并把分隔条归位）。 */
    private Runnable collapseHandler;

    private int requestCount;
    private int responseCount;
    private int errorCount;

    public LogConsole() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(UIUtil.getPanelBackground());

        area.setEditable(false);
        area.setFont(Ui.monospace());
        area.setBackground(UIUtil.getPanelBackground());
        area.setForeground(UIUtil.getLabelForeground());
        area.setBorder(JBUI.Borders.empty(2, 6));

        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setBorder(JBUI.Borders.empty());
        // 高度由外面的分隔条决定，这里只兜一个"不会被压成 0"的最小值
        scroll.setMinimumSize(new Dimension(0, JBUI.scale(40)));

        add(buildHeader(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        payloadToggle.setSelected(McpSettings.getInstance().isLogPayloads());
        payloadToggle.addActionListener(e -> McpSettings.getInstance().setLogPayloads(payloadToggle.isSelected()));
    }

    /** 点折叠箭头之后做什么，由主面板决定。 */
    public void setCollapseHandler(@NotNull Runnable handler) {
        this.collapseHandler = handler;
    }

    private JComponent buildHeader() {
        title.setFont(Ui.bold(title.getFont()));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0));
        left.setOpaque(false);
        // 折叠入口就挨着标题：边栏窄的时候工具栏那一排也可能被压掉，这是"就近"的那个入口
        // （重新展开走工具栏的「报文日志」开关）。
        left.add(Ui.iconToolbar("McpDebuggerLogHeader", area,
                Ui.iconAction("折叠日志", "折叠底部的日志区（工具栏的「报文日志」可以再打开）",
                        AllIcons.General.CollapseComponent, this::requestCollapse)));
        left.add(title);

        payloadToggle.setToolTipText("在日志里显示完整报文体；关掉后只留方法名，看长响应更清爽");
        payloadToggle.setFont(Ui.smaller(payloadToggle.getFont()));

        JButton clear = new JButton("清空");
        clear.setToolTipText("清空日志（不会断开连接）");
        clear.setFont(Ui.smaller(clear.getFont()));
        clear.setMargin(JBUI.insets(2, 6, 2, 6));
        clear.addActionListener(e -> clear());

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0));
        right.setOpaque(false);
        right.add(payloadToggle);
        right.add(clear);

        JPanel header = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(2, 4, 2, 4));
        header.add(left, BorderLayout.WEST);
        header.add(summary, BorderLayout.CENTER);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private void requestCollapse() {
        if (collapseHandler != null) {
            collapseHandler.run();
        }
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    /** 一条 JSON-RPC 报文。 */
    public void appendTraffic(boolean outbound, JsonObject message) {
        if (message == null) {
            return;
        }
        if (outbound) {
            requestCount++;
        } else if (JsonRpc.hasError(message)) {
            errorCount++;
        } else {
            responseCount++;
        }

        String method = JsonRpc.methodOf(message);
        String action;
        Color color;
        if (method != null) {
            action = method;
            color = outbound ? Ui.TRAFFIC_OUT : Ui.TRAFFIC_IN;
        } else if (JsonRpc.hasError(message)) {
            JsonObject error = message.getAsJsonObject("error");
            int code = JsonUtil.intOr(error, "code", 0);
            action = "错误 " + code + " " + JsonUtil.firstLine(JsonUtil.str(error, "message", ""));
            color = Ui.ERROR;
        } else {
            action = "响应";
            color = Ui.TRAFFIC_IN;
        }

        StringBuilder line = new StringBuilder(TIME.format(new Date()))
                .append("  ").append(outbound ? "→" : "←").append("  ").append(action);
        if (payloadToggle.isSelected()) {
            line.append('\n').append("        ").append(abbreviate(JsonUtil.compact(message)));
        }
        write(line.append('\n').toString(), color);
    }

    /** 传输层信息（子进程 stderr、非协议输出、被忽略的请求头）。 */
    public void appendNotice(String text) {
        write(TIME.format(new Date()) + "  ·  " + text + "\n", Ui.TRAFFIC_NOTICE);
    }

    /** 普通信息。 */
    public void appendInfo(String text) {
        write(TIME.format(new Date()) + "  ·  " + text + "\n", UIUtil.getContextHelpForeground());
    }

    /** 本插件 / 协议层错误。 */
    public void appendError(String text) {
        errorCount++;
        write(TIME.format(new Date()) + "  ✗  " + text + "\n", Ui.ERROR);
    }

    public void clear() {
        area.setText("");
        requestCount = 0;
        responseCount = 0;
        errorCount = 0;
        updateSummary();
    }

    // ------------------------------------------------------------------

    private void write(String text, Color color) {
        // 传输层的回调可能在任意线程上触发，统一切回 EDT
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> write(text, color));
            return;
        }
        StyledDocument document = area.getStyledDocument();
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        try {
            document.insertString(document.getLength(), text, attributes);
            trim(document);
        } catch (BadLocationException ignored) {
            // 并发裁剪时正好落在边界上，忽略
        }
        area.setCaretPosition(document.getLength());
        updateSummary();
    }

    private void trim(StyledDocument document) {
        int length = document.getLength();
        if (length <= MAX_CHARS) {
            return;
        }
        try {
            int keep = MAX_CHARS / 2;
            String head = document.getText(0, length - keep);
            int cut = head.indexOf('\n');
            document.remove(0, cut < 0 ? length - keep : cut + 1);
        } catch (BadLocationException ignored) {
            // 忽略
        }
    }

    private void updateSummary() {
        int total = requestCount + responseCount;
        String text = total == 0
                ? "暂无日志"
                : "请求 " + requestCount + " · 响应 " + responseCount
                + (errorCount > 0 ? " · 错误 " + errorCount : "");
        // 计数放在中间那一格，窄边栏里它最先被挤没；顺手挂到标题的 tooltip 上，
        // 免得"报文统计"在窄窗口里彻底看不到
        title.setToolTipText("报文日志 · " + text);
        if (ApplicationManager.getApplication().isDispatchThread()) {
            summary.setText(text);
        } else {
            ApplicationManager.getApplication().invokeLater(() -> summary.setText(text));
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= PAYLOAD_LIMIT
                ? text
                : text.substring(0, PAYLOAD_LIMIT) + " …（已截断，完整共 " + text.length() + " 字符）";
    }
}
