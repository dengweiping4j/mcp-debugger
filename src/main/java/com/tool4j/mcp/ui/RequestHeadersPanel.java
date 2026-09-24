package com.tool4j.mcp.ui;

import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpServerConfig;

import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.List;
import java.util.Objects;

/**
 * 「请求头」页签——<b>工具级</b>请求头的编辑器，与「JSON」「定义」「历史」并列。
 *
 * <p><b>它编辑的是哪一份。请求头分两级：</b>
 * <ul>
 *   <li><b>服务器级</b>（{@link McpServerConfig#getHeaders()}）：带在所有请求上，含握手与
 *   SSE 建连。入口是工具栏第一行那个「请求头 · N」按钮，打开的是 {@link ServerHeadersDialog}——
 *   那一层属于服务器、不属于某个工具，所以它不在这张页签上，也不随工具切换而重载。</li>
 *   <li><b>工具级</b>（本页面）：只在这个工具的 {@code tools/call} 上叠加，同名键覆盖服务器级。
 *   典型用途是工具专属的 {@code X-Trace-Id}，或让不同工具用不同角色 / 额度。</li>
 * </ul>
 *
 * <p><b>为什么工具级留在页签里、服务器级却是弹窗。</b>这一层天然属于"当前选中的这个工具"，
 * 页签正是"选中项的属性"该待的地方——它和旁边的参数 JSON 是同一件事的两个面
 * （Postman 的 Params / Headers 也是这么排的），切工具就该跟着换一份，不需要额外开关一层窗口。
 * 服务器级没有这个归属，摆在详情区里反而像是某个条目的属性，所以它挪进了弹窗。
 *
 * <p><b>为什么是表单而不是 JSON。</b>见 {@link HeadersFormPanel}——手写 JSON 要记住头名、
 * 记住引号，敲错一个引号整块不生效。表单里键是下拉（常用头点一下就有）、值就是输入框。
 *
 * <p><b>为什么改完不用重连。</b>{@code HttpTransport} / {@code SseTransport} 每次请求都重新读
 * {@link McpServerConfig}，所以这里写下去的值对后续调用立刻生效。页签没有"确定"按钮，
 * 敲一下就是一下——它是随调试反复改的东西，多一道确认只会碍事。
 *
 * <p><b>没有"解析失败"这回事了。</b>空键的行会被 {@link HeadersFormPanel#getRows()} 直接滤掉，
 * 所以不存在"改到一半把 token 弄丢"的场景：没填完的行压根不会进配置。
 */
public final class RequestHeadersPanel extends JPanel {

    private final HeadersFormPanel form;
    private final JBLabel state = Ui.hint(" ");

    /** 当前绑定的配置对象；写进去的就是它，传输层每次请求重新读。 */
    private McpServerConfig bound;
    /** 当前绑定到哪个工具——两级里的第二级靠它定位。 */
    private String boundTool = "";
    /** 回填表单时抑制监听，免得把自己的写入当成用户编辑。 */
    private boolean loading;
    /** 内容变化后的回调；{@link ToolDetailPanel} 用它刷新页签标题上的条数。 */
    private Runnable onChanged = () -> {
    };

    private enum HdrState { UNSET, ACTIVE }

    /** 当前提示状态，{@link #applyTexts()} 据此重画。 */
    private HdrState hdrState = HdrState.UNSET;

    public RequestHeadersPanel() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(JBUI.Borders.empty(6));

        // 键列收窄到 96：这个页签常态只有三百多像素宽，得把宽度留给值（token 往往很长）
        form = new HeadersFormPanel(96);
        form.setOnChanged(this::onEdited);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(JBUI.Borders.empty(4, 1, 0, 1));
        footer.add(state, BorderLayout.CENTER);

        add(form, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showConfig(null, null);
        applyTexts();
    }

    /** 内容变化后的回调（只在真的写进配置时触发），用来刷新页签标题。 */
    public void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> {
        } : listener;
    }

    // ------------------------------------------------------------------
    // 绑定 / 回填
    // ------------------------------------------------------------------

    /**
     * 绑定到「某个服务器上的某个工具」。两者任一为 null 都会清空表单。
     *
     * <p>重复绑定<b>同一个 (配置实例, 工具名)</b> 时只刷新提示文字：主面板在连接、刷新、
     * 切工具时都会重排右侧面板，每次都重建表单的话，会把手正敲到一半的内容（以及焦点和光标）
     * 打回去。工具名是判据的一部分——切工具时内容本来就该换。
     */
    public void showConfig(@Nullable McpServerConfig config, @Nullable String toolName) {
        String tool = toolName == null ? "" : toolName;
        if (bound == config && Objects.equals(boundTool, tool)) {
            renderState();
            return;
        }
        bound = config;
        boundTool = tool;
        loading = true;
        try {
            form.setRows(config == null || tool.isEmpty()
                    ? List.of() : config.toolHeadersOf(tool));
        } finally {
            loading = false;
        }
        refreshState();
        onChanged.run();
    }

    /** 当前生效的请求头条数（空键的不算）。 */
    public int count() {
        if (bound == null || boundTool.isEmpty()) {
            return 0;
        }
        return form.count();
    }

    /**
     * 页签标题。把条数写在标题上：不点开也能一眼看出"这个工具额外带了什么"。
     * 服务器级的条数在工具栏那个按钮上，两处分工不同。
     */
    public String tabTitle() {
        String title = I18n.t("hdr.title");
        int n = count();
        return n == 0 ? title : title + " (" + n + ")";
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    /**
     * 表单变了就写进配置。
     *
     * <p>没有"解析失败"这条分支：空键的行在 {@link HeadersFormPanel#getRows()} 里就被滤掉了，
     * 所以敲到一半的字段不会进配置，也就不会出现"把能用的值覆盖成半截"。
     */
    private void onEdited() {
        if (loading || bound == null || boundTool.isEmpty()) {
            return;
        }
        // 空表表示"这个工具不要专属头"，实现里会把整条记录删掉，不留空壳
        bound.setToolHeadersOf(boundTool, form.getRows());
        refreshState();
        onChanged.run();
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    /**
     * 表单下面那行小字。只说三件事：生效几项、什么时候生效、什么时候不生效。
     *
     * <p>放在 {@link BorderLayout#CENTER} 上是有意的——窄边栏里它会自动变省略号，
     * 而不是把整个页签顶宽。
     */
    private void refreshState() {
        if (count() == 0) {
            hdrState = HdrState.UNSET;
            setState(Ui.MUTED, I18n.t("hdr.state.unset"), I18n.t("hdr.state.unsetTip"));
            return;
        }
        hdrState = HdrState.ACTIVE;
        setState(Ui.OK, I18n.t("hdr.state.active", count()), I18n.t("hdr.state.activeTip"));
    }

    private void setState(JBColor color, String text, @Nullable String tooltip) {
        state.setForeground(color);
        state.setText(text == null || text.isEmpty() ? " " : text);
        state.setToolTipText(tooltip);
    }

    /** 按当前语言重画表单与状态小字；不碰任何数据，可随时安全调用。 */
    public void applyTexts() {
        form.applyTexts();
        renderState();
    }

    private void renderState() {
        if (bound == null || boundTool.isEmpty()) {
            state.setForeground(Ui.MUTED);
            state.setText(" ");
            state.setToolTipText(null);
            return;
        }
        switch (hdrState) {
            case UNSET -> setState(Ui.MUTED, I18n.t("hdr.state.unset"), I18n.t("hdr.state.unsetTip"));
            case ACTIVE -> setState(Ui.OK, I18n.t("hdr.state.active", count()),
                    I18n.t("hdr.state.activeTip"));
        }
    }
}
