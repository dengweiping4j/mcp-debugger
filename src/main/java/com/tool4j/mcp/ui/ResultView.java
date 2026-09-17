package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.protocol.JsonUtil;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;

/**
 * 调用结果的渲染区：把 JSON-RPC 的 {@code result} 原样摊开，自带滚动。
 *
 * <p>早先这里按内容块类型分卡片渲染（文本 / 图片 / 内嵌资源 / 音频），再拿一个页签装原始报文。
 * 用下来那层"好读"是多余的：调试台要的就是"服务端到底回了什么"，图片预览那点便利
 * 抵不过"显示的和协议里不完全一致"带来的困惑，多一个页签还要来回切。
 *
 * <p>所以现在只有一份 JSON，状态条右侧只有一个「复制」按钮，复制的是同一份内容。
 * 复制之后按钮会自己变成「已复制 ✓」并染成绿色，1.4 秒后复原——原来点了没有任何反应，
 * 用户没法确认到底进没进剪贴板。
 */
public final class ResultView extends JPanel implements Disposable {

    /** 复制成功后按钮的还原延时。 */
    private static final int COPY_FEEDBACK_MILLIS = 1400;

    private final JBLabel statusDot = new JBLabel();
    private final JBLabel statusText = new JBLabel();
    private final JBLabel statusMeta = Ui.hint("");
    private final JButton copyButton = new JButton("复制");
    /** 复制按钮的字色（LAF 给的），闪一下绿色之后要还原成它。 */
    private final Color copyButtonForeground;
    /** 一次性定时器：只负责把按钮从「已复制 ✓」还原回「复制」。 */
    private final Timer copyFeedbackTimer = new Timer(COPY_FEEDBACK_MILLIS, e -> resetCopyButton());
    private final EditorTextField jsonViewer;

    public ResultView(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);

        // 只读 JSON 视图：竖滚动条在 Editors 里被显式打开（平台默认给它关掉了），长结果拖着看即可。
        // 首选尺寸必须显式给：不给的话编辑器按内容长度报首选尺寸，结果一长就把外层
        // JBSplitter 的上下比例顶歪。这里给的值只是建议，实际高度由 splitter 分配。
        jsonViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 300);

        add(buildStatusStrip(), BorderLayout.NORTH);
        // 空 final 只能在构造器里赋值，取的是 LAF 给按钮的字色
        copyButtonForeground = copyButton.getForeground();
        add(jsonViewer, BorderLayout.CENTER);
        setJson("");
    }

    private JComponent buildStatusStrip() {
        JPanel strip = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        strip.setOpaque(false);
        strip.setBorder(JBUI.Borders.empty(4, 6, 4, 6));

        // 状态文字与补充信息都放在会被"摊开 / 截断"的那一格里：
        // 挤的时候消失的是灰字（耗时、内容块数），红绿状态永远在。反过来把状态压掉是不行的。
        JPanel textPart = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        textPart.setOpaque(false);
        textPart.add(statusText, BorderLayout.WEST);
        textPart.add(statusMeta, BorderLayout.CENTER);

        copyButton.setToolTipText("复制原始结果（JSON）");
        copyButton.setFocusable(false);
        copyButton.setMargin(JBUI.insets(1, 8, 1, 8));
        copyFeedbackTimer.setRepeats(false);
        copyButton.addActionListener(e -> copyJson());

        strip.add(statusDot, BorderLayout.WEST);
        strip.add(textPart, BorderLayout.CENTER);
        strip.add(copyButton, BorderLayout.EAST);
        return strip;
    }

    /** 复制当前 JSON，并让按钮自己报出结果。 */
    private void copyJson() {
        Ui.copyToClipboard(jsonViewer.getText());
        copyButton.setText("已复制 ✓");
        copyButton.setForeground(Ui.OK);
        copyFeedbackTimer.restart();
    }

    /** 把复制按钮还原回常态。 */
    private void resetCopyButton() {
        copyFeedbackTimer.stop();
        copyButton.setText("复制");
        copyButton.setForeground(copyButtonForeground);
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public void showLoading(String what) {
        statusDot.setIcon(Ui.dot(Ui.MUTED));
        statusText.setText("正在调用 " + what + " …");
        statusText.setForeground(JBColor.GRAY);
        statusText.setFont(Ui.bold(statusText.getFont()));
        statusMeta.setText("");
        setJson("");
    }

    public void clear() {
        statusDot.setIcon(null);
        statusText.setText("尚未调用。填好参数后点「调用」。");
        statusText.setForeground(JBColor.GRAY);
        statusMeta.setText("");
        setJson("");
    }

    /** 渲染一次调用结果。 */
    public void show(McpCallResult result, String what) {
        if (result == null) {
            showFailure("没有拿到结果", what);
            return;
        }

        if (!result.isSuccess()) {
            statusDot.setIcon(Ui.dot(Ui.ERROR));
            statusText.setText(result.getError() != null
                    ? "调用失败"
                    : "工具执行失败（isError = true）");
            statusText.setForeground(Ui.ERROR);
        } else {
            statusDot.setIcon(Ui.dot(Ui.OK));
            statusText.setText("调用成功");
            statusText.setForeground(Ui.OK);
        }
        statusText.setFont(Ui.bold(statusText.getFont()));

        int blockCount = result.getContent().size();
        String meta = result.getElapsedMillis() + " ms";
        if (blockCount > 0) {
            meta += " · " + blockCount + " 个内容块";
        }
        if (result.getStructuredContent() != null) {
            meta += " · 含结构化内容";
        }
        statusMeta.setText(meta);

        setJson(jsonOf(result));
    }

    public void showFailure(String message, String what) {
        statusDot.setIcon(Ui.dot(Ui.ERROR));
        statusText.setText("调用 " + what + " 失败");
        statusText.setForeground(Ui.ERROR);
        statusText.setFont(Ui.bold(statusText.getFont()));
        statusMeta.setText("");

        JsonObject error = new JsonObject();
        error.addProperty("error", message == null ? "调用失败" : message);
        setJson(JsonUtil.pretty(error));
    }

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    /**
     * 要显示的 JSON 正文。
     *
     * <p>连不上 / 超时 / 服务端回了 JSON-RPC error 这几种情况在协议层就失败了，
     * {@code raw} 是个空对象——界面上只有 {@code {}} 看不出发生过什么，
     * 所以退化成把错误信息包成一个 JSON 对象显示。
     */
    private static String jsonOf(McpCallResult result) {
        JsonObject raw = result.getRaw();
        if (raw != null && raw.size() > 0) {
            return JsonUtil.pretty(raw);
        }
        JsonObject fallback = new JsonObject();
        fallback.addProperty("error",
                result.getError() == null ? "（服务端没有返回任何内容）" : result.getError());
        return JsonUtil.pretty(fallback);
    }

    /** 换 JSON 正文，顺带同步「复制」按钮的可用状态——空结果没什么可复制的。 */
    private void setJson(String json) {
        // 换了内容，上一次那声「已复制 ✓」就不该再挂着
        resetCopyButton();
        jsonViewer.setText(json == null ? "" : json);
        copyButton.setEnabled(!jsonViewer.getText().isBlank());
    }

    @Override
    public void dispose() {
        copyFeedbackTimer.stop();
        jsonViewer.setText("");
    }
}
