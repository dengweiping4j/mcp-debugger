package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.protocol.JsonUtil;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p><b>状态条为什么记"形态"而不记句子。</b>切语言时这个面板不重建（重建会把 JSON 正文、
 * 滚动位置一起弄丢），{@link #applyTexts()} 得能把上一句换成新语言。所以状态只存
 * {@link StatusKind} 加几个参数，文案每次由 {@link #renderStatus()} 现推——任何地方
 * 都不许自己拼好一句再 {@code setText}，否则那句话就永远停在切换前的那门语言里。
 */
public final class ResultView extends JPanel implements Disposable {

    /** 复制成功后按钮的还原延时。 */
    private static final int COPY_FEEDBACK_MILLIS = 1400;

    /** 补充信息各段之间的分隔。纯标点，不进词表。 */
    private static final String META_SEPARATOR = " · ";

    /** 状态条的形态。语言切换后据此重算文案。 */
    private enum StatusKind {
        /** 还没调用过。 */
        IDLE,
        /** 请求已经发出，等结果。 */
        LOADING,
        /** 调用成功。 */
        SUCCESS,
        /** 拿到了一个失败的结果（协议层失败，或服务端回了 isError）。 */
        FAILURE_RESULT,
        /** 本地就没走通（连接失败、超时…），结果区直接显示错误信息。 */
        FAILURE_MESSAGE
    }

    private final JBLabel statusDot = new JBLabel();
    private final JBLabel statusText = new JBLabel();
    private final JBLabel statusMeta = Ui.hint("");
    private final JButton copyButton = new JButton();
    /** 复制按钮的字色（LAF 给的），闪一下绿色之后要还原成它。 */
    private final Color copyButtonForeground;
    /** 一次性定时器：只负责把按钮从「已复制 ✓」还原回「复制」。 */
    private final Timer copyFeedbackTimer = new Timer(COPY_FEEDBACK_MILLIS, e -> resetCopyButton());
    private final EditorTextField jsonViewer;

    private StatusKind statusKind = StatusKind.IDLE;
    /** 状态文案里的那个面板名（工具 / 资源 / 提示词），由调用方给。 */
    private String statusWhat = "";
    /**
     * 状态句主语的**文案键**（如 {@code res.subject}），优先于 {@link #statusWhat}。
     *
     * <p>调用方如果给的是"面板名"这种会随语言变的词，就必须给键而不是给它渲染好的文本——
     * 否则语言一切换，句子换新语言、主语还卡在旧语言（"Calling 资源 …"）。
     * 工具名这类真实数据不用给键，走 {@link #statusWhat} 原样显示。
     */
    private String statusWhatKey;
    /** {@link #statusWhatKey} 的占位符实参（如工具名）；无占位符时为 null。 */
    private Object[] statusWhatArgs;
    /** {@code FAILURE_RESULT} 时区分「协议层失败」与「服务端说这次调用失败」。 */
    private boolean statusErrorNull;
    private int statusBlockCount;
    private long statusElapsedMillis;
    private boolean statusHasStructured;

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
        applyTexts();
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

        // 提示文字在 applyTexts() 里贴，这里只负责装配
        copyButton.setFocusable(false);
        copyButton.setMargin(JBUI.insets(1, 8, 1, 8));
        copyFeedbackTimer.setRepeats(false);
        copyButton.addActionListener(e -> copyJson());

        strip.add(statusDot, BorderLayout.WEST);
        strip.add(textPart, BorderLayout.CENTER);
        strip.add(copyButton, BorderLayout.EAST);
        return strip;
    }

    /**
     * 重贴这份面板的文案（语言切换时由 {@link ToolDetailPanel} 级联调用）。
     *
     * <p>只换字：JSON 正文是服务端原样返回的内容，不翻译、也不重新请求。
     */
    public void applyTexts() {
        copyButton.setToolTipText(I18n.t("ui.copyTooltip"));
        resetCopyButton();
        renderStatus();
    }

    /** 复制当前 JSON，并让按钮自己报出结果。 */
    private void copyJson() {
        Ui.copyToClipboard(jsonViewer.getText());
        copyButton.setText(I18n.t("ui.copied"));
        copyButton.setForeground(Ui.OK);
        copyFeedbackTimer.restart();
    }

    /** 把复制按钮还原回常态。 */
    private void resetCopyButton() {
        copyFeedbackTimer.stop();
        copyButton.setText(I18n.t("ui.copy"));
        copyButton.setForeground(copyButtonForeground);
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public void showLoading(String what) {
        statusKind = StatusKind.LOADING;
        statusWhat = what == null ? "" : what;
        renderStatus();
        setJson("");
    }

    public void clear() {
        statusKind = StatusKind.IDLE;
        statusWhat = "";
        statusWhatKey = null;
        statusWhatArgs = null;
        statusErrorNull = false;
        statusBlockCount = 0;
        statusElapsedMillis = 0;
        statusHasStructured = false;
        renderStatus();
        setJson("");
    }

    /** 渲染一次调用结果。 */
    public void show(McpCallResult result, String what) {
        if (result == null) {
            showFailure(I18n.t("rv.error.noResult"), what);
            return;
        }

        statusWhat = what == null ? "" : what;
        statusBlockCount = result.getContent().size();
        statusElapsedMillis = result.getElapsedMillis();
        statusHasStructured = result.getStructuredContent() != null;

        if (!result.isSuccess()) {
            statusKind = StatusKind.FAILURE_RESULT;
            // error 为空说明是服务端自己说这次调用失败（isError = true），
            // 有 error 才是协议层就没走通。两者文案不同，别弄反。
            statusErrorNull = result.getError() == null;
        } else {
            statusKind = StatusKind.SUCCESS;
            statusErrorNull = false;
        }
        renderStatus();

        setJson(jsonOf(result));
    }

    public void showFailure(String message, String what) {
        statusKind = StatusKind.FAILURE_MESSAGE;
        statusWhat = what == null ? "" : what;
        renderStatus();

        JsonObject error = new JsonObject();
        error.addProperty("error", message == null ? I18n.t("rv.status.failure") : message);
        setJson(JsonUtil.pretty(error));
    }

    /**
     * 让状态句的主语走文案键解析，这样语言切换后主语会跟着换。
     *
     * <p>调用时机是 {@link #show}/{@link #showLoading}/{@link #showFailure} 之后——它只重画状态条。
     * 工具名这类真实数据别调这里，直接当 {@code what} 传进去原样显示即可。
     */
    public void setSubjectKey(String key, Object... args) {
        this.statusWhatKey = key;
        this.statusWhatArgs = args;
        renderStatus();
    }

    /** 状态句的主语：给了键就现解析（能随语言变），否则用调用方给的现成文本。 */
    private String subject() {
        return statusWhatKey == null ? statusWhat : I18n.t(statusWhatKey, statusWhatArgs);
    }

    /**
     * 按"当前是哪种状态"把状态条重贴一遍。
     *
     * <p>这是状态文字的唯一出口——别处想改状态请调 {@link #show}/{@link #showLoading} 等，
     * 让它们改 {@link #statusKind} 再回到这里。
     */
    private void renderStatus() {
        if (statusKind == StatusKind.LOADING) {
            statusDot.setIcon(Ui.dot(Ui.MUTED));
            statusText.setText(I18n.t("rv.status.loading", subject()));
            statusText.setForeground(JBColor.GRAY);
        } else if (statusKind == StatusKind.SUCCESS) {
            statusDot.setIcon(Ui.dot(Ui.OK));
            statusText.setText(I18n.t("rv.status.success"));
            statusText.setForeground(Ui.OK);
        } else if (statusKind == StatusKind.FAILURE_RESULT) {
            statusDot.setIcon(Ui.dot(Ui.ERROR));
            statusText.setText(I18n.t(statusErrorNull ? "rv.status.toolError" : "rv.status.failure"));
            statusText.setForeground(Ui.ERROR);
        } else if (statusKind == StatusKind.FAILURE_MESSAGE) {
            statusDot.setIcon(Ui.dot(Ui.ERROR));
            statusText.setText(I18n.t("rv.status.failureWithName", subject()));
            statusText.setForeground(Ui.ERROR);
        } else {
            statusDot.setIcon(null);
            statusText.setText(I18n.t("rv.status.idle"));
            statusText.setForeground(JBColor.GRAY);
        }
        statusText.setFont(Ui.bold(statusText.getFont()));
        statusMeta.setText(buildMeta());
    }

    /** 状态条右侧那句灰字：耗时 · 内容块数 · 有没有结构化内容。 */
    private String buildMeta() {
        if (statusKind != StatusKind.SUCCESS && statusKind != StatusKind.FAILURE_RESULT) {
            return "";
        }
        List<String> parts = new ArrayList<>(3);
        parts.add(I18n.t("rv.meta.elapsed", statusElapsedMillis));
        if (statusBlockCount > 0) {
            parts.add(I18n.t("rv.meta.blocks", statusBlockCount));
        }
        if (statusHasStructured) {
            parts.add(I18n.t("rv.meta.structured"));
        }
        return String.join(META_SEPARATOR, parts);
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
                result.getError() == null ? I18n.t("rv.json.noContent") : result.getError());
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
