package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.model.ToolCallRecord;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.SchemaUtil;

import org.jetbrains.annotations.Nullable;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * 工具详情：描述 + 参数（JSON）+ 调用 + 结果。
 *
 * <p>参数区是一排页签，<b>JSON 是唯一的输入入口</b>：
 * <ul>
 *   <li><b>JSON</b>：选中工具时按它的 {@code inputSchema} 预填一份模板——声明的每个参数都在，
 *   值取 schema 的 {@code default}，没 default 的按类型给空值。改哪几个值就发哪几个字段，
 *   多余的删掉即可。</li>
 *   <li><b>请求头</b>（仅 http / sse，且选中了具体工具）：<b>这个工具</b>额外带的头，
 *   只作用于它的 {@code tools/call}，同名键覆盖服务器级。和参数 JSON 是同一件事的两个面，
 *   所以放在同一级页签上（Postman 的 Params / Headers 也是这么排的），见
 *   {@link RequestHeadersPanel}。<b>服务器级</b>请求头是另一层，入口在工具栏那个
 *   「请求头 · N」按钮上（打开 {@link ServerHeadersDialog}）——它挂在服务器上，切工具不该动它。</li>
 *   <li><b>定义</b>：工具原始定义（含 annotations 与 outputSchema）。参数的类型、必填、说明、
 *   默认值都能在这里查到，不用另开文档。</li>
 *   <li><b>历史</b>：这个工具每次调用的入参，点条目即填回 JSON 页签，见 {@link CallHistoryPanel}。
 *   它也是挂在<b>工具</b>上的（不是服务器），切工具换一份。</li>
 * </ul>
 *
 * <p>原先还有个「参数」表单页签，已移除：它和 JSON 页签表达的是同一件事，
 * 两套输入只让用户多一层"我现在改动的是哪个页签"的心智负担。
 */
public final class ToolDetailPanel extends JPanel implements Disposable {

    /** 请求头页签在页签栏里的位置：夹在「JSON」和「定义」之间。 */
    private static final int HEADERS_TAB_INDEX = 1;

    /** 调用请求的回调；由主面板实现，负责切后台线程和拿会话。 */
    public interface Callback {
        void invokeTool(McpTool tool, JsonObject arguments);
    }

    private final JBLabel titleLabel = new JBLabel(" ");
    private final JBLabel badgesLabel = Ui.hint(" ");
    /**
     * 工具描述：<b>单行 + 省略号</b>，鼠标移入看全文。
     *
     * <p>原来是 48px 高的 {@code JTextArea} + 滚动条：两行放不下就只剩滚动，而这一栏
     * 只是想让人扫一眼"这工具干什么"，不值得吃掉三行高度（{@link OneLineLabel}）。
     */
    private final OneLineLabel descriptionLabel = new OneLineLabel();
    private final JBLabel statusLabel = Ui.hint("");
    private final JButton invokeButton = new JButton(I18n.t("tool.action.invoke"));
    private final JButton resetButton = new JButton("");
    /** 图标按钮（重置 / 美化）——只有 tooltip，所以文案要能跟着语言换。 */
    private final JButton formatButton = new JButton(AllIcons.Actions.ReformatCode);

    private final EditorTextField jsonEditor;
    private final EditorTextField definitionViewer;
    /**
     * 加进页签的是 {@link #wrapEditor} 包出来的容器，<b>不是编辑器本身</b>。
     *
     * <p>所以换标题必须拿这两个容器去 {@code indexOfComponent}——拿编辑器去找会返回 -1，
     * {@code setTitleAt} 静默不执行，表现为"切语言后「JSON」「定义」两个页签还是旧语言"
     * （「历史」是直接加进去的，没这个问题，所以只有这两个中招）。
     */
    private final JComponent jsonTab;
    private final JComponent definitionTab;
    private final JBTabbedPane tabs = new JBTabbedPane();
    /** 与 JSON / 定义 并列的第三个页签：<b>本工具</b>的请求头；只在 http / sse 且选中了工具时存在。 */
    private final RequestHeadersPanel headersPanel;
    /** 与 JSON / 定义 并列的最后一个页签：这个工具的历史入参。 */
    private final CallHistoryPanel historyPanel;
    private final ResultView resultView;
    private final JBSplitter splitter;

    /** 当前绑定的服务器配置；请求头页签在不在、绑到哪个工具，取决于它 + 当前 {@link #tool}。 */
    @Nullable
    private McpServerConfig serverConfig;
    private McpTool tool;
    private Callback callback;
    private boolean busy;

    // ---------------- 可重贴的文案状态 ----------------

    /**
     * 空态提示语的来源。<b>存键而不是存成品字符串</b>，否则切到另一种语言之后
     * 这块说明会停在旧语言里（它不是某个操作的结果，没人会再去刷新它）。
     */
    @Nullable
    private String idleKey;
    /** 外面直接给现成文本时走这里（{@link #showIdle(String)}）。 */
    @Nullable
    private String idleText;

    /**
     * 状态栏那句话的<b>状态</b>，而不是拼好的字符串。
     *
     * <p>状态栏上的字是拼出来的（参数个数、毫秒、历史时间戳），存字符串就只能在
     * 语言切换后留在旧语言。所以这里存状态 + 变量部分，{@link #renderStatus()}
     * 每次按当前语言重新拼一遍。
     */
    private enum StatusKind { NONE, HISTORY, BAD_JSON, CALLING, FAILED, TOOL_ERROR, DONE }

    private StatusKind statusKind = StatusKind.NONE;
    /** 状态文案里的变量部分（时间、参数个数、毫秒、JSON 解析错误）。 */
    @Nullable
    private String statusArg;

    public ToolDetailPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);
        // 把自己也挂到父级上：dispose() 要收掉结果区的定时器，并注销「历史」页签订阅的
        // 历史变更广播。不挂的话这个 panel 永远不会被 dispose，关掉工具窗口再打开
        // 就会留下一个仍在收消息的旧实例（它引着整棵树，等于每次重开都漏一份）。
        Disposer.register(parent, this);

        jsonEditor = Editors.sized(Editors.jsonEditor(project, "", parent), 260);
        definitionViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 260);
        // 编辑器自带 Keymap，面板级的 Ctrl+Enter 到不了它，得挂进编辑器自己身上
        Editors.onInvokeShortcut(jsonEditor, this::invoke);

        headersPanel = new RequestHeadersPanel();
        headersPanel.setOnChanged(this::refreshHeadersTitle);

        historyPanel = new CallHistoryPanel(project, this);
        historyPanel.setOnLoad(this::loadArguments);
        historyPanel.setOnRerun(this::rerunArguments);
        historyPanel.setCallable(this::canInvoke);
        historyPanel.setOnChanged(this::refreshHistoryTitle);

        jsonTab = wrapEditor(jsonEditor);
        definitionTab = wrapEditor(definitionViewer);
        tabs.addTab(I18n.t("tool.tab.json"), jsonTab);
        tabs.addTab(I18n.t("tool.tab.definition"), definitionTab);
        // 「历史」排在最后：它是"回头看看上次怎么填的"，属于辅助入口，
        // 不该把「JSON」「请求头」这些"这次要发什么"挤到后面去
        tabs.addTab(I18n.t("tool.tab.history"), historyPanel);
        // 请求头页签由 showConfig() 按传输类型插入 / 移除，不在这里加

        JPanel paramArea = new JPanel(new BorderLayout());
        paramArea.setOpaque(false);
        paramArea.add(buildActionRow(), BorderLayout.NORTH);
        paramArea.add(tabs, BorderLayout.CENTER);

        resultView = new ResultView(project, parent);

        splitter = new JBSplitter(true, 0.52f);
        splitter.setFirstComponent(paramArea);
        splitter.setSecondComponent(resultView);
        // 分隔条宽度用平台默认值（7px）。设成 6 之类看着"精致"，但分割条的鼠标热区就是它本身，
        // 窄了手就抓不住——这是"拖不动"的最常见原因。
        splitter.setHonorComponentsMinimumSize(true);
        splitter.setShowDividerControls(false);

        add(buildHeader(), BorderLayout.NORTH);
        add(splitter, BorderLayout.CENTER);

        installShortcuts();
        clear();
    }

    private static JComponent wrapEditor(JComponent editor) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(JBUI.Borders.empty(6));
        wrapper.add(editor, BorderLayout.CENTER);
        return wrapper;
    }

    // ------------------------------------------------------------------
    // 头部 / 工具条
    // ------------------------------------------------------------------

    private JComponent buildHeader() {
        titleLabel.setFont(Ui.bold(titleLabel.getFont()));
        badgesLabel.setFont(Ui.smaller(badgesLabel.getFont()));

        JPanel titleRow = new JPanel();
        titleRow.setOpaque(false);
        titleRow.setLayout(new BoxLayout(titleRow, BoxLayout.X_AXIS));
        titleRow.add(titleLabel);
        titleRow.add(Ui.hgap(8));
        titleRow.add(badgesLabel);
        titleRow.add(Box.createHorizontalGlue());

        descriptionLabel.setForeground(UIUtil.getLabelForeground());
        descriptionLabel.setBorder(JBUI.Borders.emptyTop(2));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.setBorder(JBUI.Borders.empty(6, 8, 4, 8));
        header.add(titleRow, BorderLayout.NORTH);
        header.add(descriptionLabel, BorderLayout.CENTER);
        return header;
    }

    /**
     * 参数区那一排按钮。
     *
     * <p>只有「调用」保留文字（主操作值得占宽度），重置 / 美化换成图标按钮：
     * 在右侧边栏里三个文字按钮能吃掉大半个宽度，把状态文字挤到看不见。
     */
    private JComponent buildActionRow() {
        invokeButton.setToolTipText(I18n.t("tool.action.invoke.tip"));
        invokeButton.addActionListener(e -> invoke());

        resetButton.setIcon(AllIcons.General.Reset);
        resetButton.setToolTipText(I18n.t("tool.action.reset.tip"));
        resetButton.setMargin(JBUI.insets(2, 4, 2, 4));
        resetButton.setFocusable(false);
        resetButton.addActionListener(e -> {
            prefillArguments(tool);
            setStatus(StatusKind.NONE, null);
        });

        formatButton.setToolTipText(I18n.t("tool.action.format.tip"));
        formatButton.setMargin(JBUI.insets(2, 4, 2, 4));
        formatButton.setFocusable(false);
        formatButton.addActionListener(e -> {
            String text = jsonEditor.getText();
            if (text != null && !text.isBlank()) {
                jsonEditor.setText(JsonUtil.formatLenient(text));
            }
        });

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        left.add(invokeButton);
        left.add(Ui.hgap(4));
        left.add(resetButton);
        left.add(Ui.hgap(2));
        left.add(formatButton);

        JPanel row = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        row.setOpaque(false);
        row.setBorder(JBUI.Borders.empty(4, 6, 4, 6));
        row.add(left, BorderLayout.WEST);
        row.add(statusLabel, BorderLayout.CENTER);
        return row;
    }

    /** 面板级快捷键：焦点不在 JSON 编辑器里时（列表、按钮、只读视图）也能触发调用。 */
    private void installShortcuts() {
        InputMap inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = getActionMap();
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "mcp.invoke");
        actionMap.put("mcp.invoke", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                invoke();
            }
        });
    }

    // ------------------------------------------------------------------
    // 展示
    // ------------------------------------------------------------------

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    /**
     * 绑定当前服务器，并据此决定「请求头」页签在不在。
     *
     * <p>服务器级请求头<b>不</b>在这里——它以工具栏那个「请求头 · N」按钮为入口，打开的是
     * {@link ServerHeadersDialog} 弹窗（那一层属于服务器，不属于任何条目）。
     */
    public void showConfig(@Nullable McpServerConfig config) {
        this.serverConfig = config;
        // 历史页签三件传输都要：它记的是"调过什么"，和请求头适不适用无关
        historyPanel.showConfig(config);
        syncHeadersTab();
    }

    /**
     * 让「请求头」页签与当前状态对齐：只有 http / sse <b>且选中了具体工具</b>时才存在。
     *
     * <p>工具级头按工具存，没选中工具时它没有可编辑的对象；一个恒在却无从下手的空页签，
     * 比没有这一页更让人疑惑（stdio 同理——子进程根本没有 HTTP 头这回事）。
     *
     * <p>页签位置固定插在 {@link #HEADERS_TAB_INDEX}，这样"JSON 永远是第一个"不会因为换了
     * 服务器的传输类型而变——肌肉记忆比"页签顺序好看"重要。
     */
    private void syncHeadersTab() {
        boolean applicable = serverConfig != null
                && serverConfig.getTransport() != null && serverConfig.getTransport().isHttp()
                && tool != null;
        headersPanel.showConfig(applicable ? serverConfig : null,
                applicable ? tool.getName() : null);

        int index = tabs.indexOfComponent(headersPanel);
        if (applicable) {
            if (index < 0) {
                tabs.insertTab(headersPanel.tabTitle(), null, headersPanel,
                        I18n.t("tool.tab.headers.tip"),
                        HEADERS_TAB_INDEX);
                // 插入完成后 JTabbedPane 的选中项可能变成"无选中"，兜一下
                if (tabs.getSelectedComponent() == null) {
                    tabs.setSelectedIndex(0);
                }
            } else {
                refreshHeadersTitle();
            }
        } else if (index >= 0) {
            boolean wasSelected = tabs.getSelectedComponent() == headersPanel;
            tabs.remove(headersPanel);
            if (wasSelected) {
                // JTabbedPane 默认会把选中挪给邻页（这里是「定义」），我们更想回到「JSON」
                tabs.setSelectedIndex(0);
            }
        }
    }

    private void refreshHeadersTitle() {
        int index = tabs.indexOfComponent(headersPanel);
        if (index >= 0) {
            tabs.setTitleAt(index, headersPanel.tabTitle());
            tabs.setToolTipTextAt(index, I18n.t("tool.tab.headers.tip"));
        }
    }

    /** 历史页签标题上的条数变了（新调用记入、删除、清空都会走到这）。 */
    private void refreshHistoryTitle() {
        int index = tabs.indexOfComponent(historyPanel);
        if (index >= 0) {
            tabs.setTitleAt(index, historyPanel.tabTitle());
        }
    }

    /**
     * 「历史」页签点条目后，把那次调用的入参填回 JSON 编辑区，<b>并切回「JSON」页签</b>。
     *
     * <p>改写的是隔壁页签里的内容，停在历史页上只会看到"点了没反应"（这一页的列表本身一点没变），
     * 用户很容易以为点空了。切过去，改动就在眼前；「调用」也就在页签上方那条动作栏上，
     * 可以直接 Ctrl+Enter 重发。
     *
     * <p>代价是覆盖掉编辑器里原有的内容，所以动作栏上那句话必须写出来（它也在页签之外，
     * 提示的时候历史页已经不在眼前了）。覆盖可撤销：{@code EditorTextField.setText}
     * 走的是编辑器命令，落在撤销栈上，Ctrl+Z 能找回来。
     */
    private void loadArguments(JsonObject arguments, ToolCallRecord record) {
        jsonEditor.setText(JsonUtil.pretty(arguments));
        jsonEditor.setCaretPosition(0);
        selectDefaultTab();
        setStatus(StatusKind.HISTORY, CallHistoryPanel.timeLabel(record.timestamp));
    }

    /**
     * 「重新调用」：把那一份入参摆回 JSON 页签，然后<b>立刻发出去</b>。
     *
     * <p>为什么还是要把内容写回编辑器：按钮承诺的是"再发一次这一份"，让人看见这次到底发了什么，
     * 下一轮也才能在它的基础上改一个值。写回走 {@code setText}，进撤销栈，Ctrl+Z 能还原。
     * 这一步不能指望"选中那一行会自动带入"——点的若是当前已选中的那行，选择事件不会来。
     *
     * <p>没连上时不会静默失败：{@code McpPanel.invokeTool} 会把"未连接"当作一次失败渲染到结果区。
     */
    private void rerunArguments(JsonObject arguments, ToolCallRecord record) {
        jsonEditor.setText(JsonUtil.pretty(arguments));
        jsonEditor.setCaretPosition(0);
        selectDefaultTab();
        invoke();
    }

    /**
     * 现在能不能发起调用。历史卡片里那个按钮据此画成可用 / 禁用，
     * 它必须和 {@link #invoke()} 自己的前置判断一致，否则会出现"按钮亮着但点了没反应"。
     */
    public boolean canInvoke() {
        return tool != null && !busy && callback != null;
    }

    /**
     * 回到「JSON」页签。两个调用点：换工具之后、从「历史」带入入参之后——
     * 这两处的下一个动作几乎总是填参数、点调用。
     *
     * <p>唯一例外是请求头：正填 token 的时候被顶走会很烦，所以停在那一页不动。
     */
    private void selectDefaultTab() {
        if (tabs.getSelectedComponent() != headersPanel) {
            tabs.setSelectedIndex(0);
        }
    }

    public void clear() {
        showIdleKey("tool.idle.guidance");
    }

    /**
     * 没有选中任何条目时的样子。
     *
     * <p><b>为什么还留着参数区</b>：这时想让人看见的只是那句提示（先连上 / 先选个工具），
     * 而把整块面板换成另一张"欢迎页"，会把「历史」「定义」这些还看得进去的东西一起收走。
     *
     * <p>它不再兼任"请求头入口"：服务器级那层在工具栏上（不受连接状态影响，
     * 见 {@link ServerHeadersDialog}），工具级那层只在选中了具体工具时才出现——
     * 没选中工具时它没有可编辑的对象，而"没 token 就连不上"恰恰要在连上之前解决，
     * 所以那个入口必须挂在不受空态影响的地方。
     */
    public void showIdle(String hint) {
        idleKey = null;
        idleText = hint;
        clearContent();
    }

    /**
     * 空态提示语走文案键的入口。
     *
     * <p>主面板用这个而不是 {@link #showIdle(String)}：提示语是这块面板的常驻文案，
     * 切语言时它得跟着换，所以必须留得住"键"而不是一个已经渲染好的句子。
     */
    public void showIdleKey(String hintKey) {
        idleKey = hintKey;
        idleText = null;
        clearContent();
    }

    private void clearContent() {
        tool = null;
        titleLabel.setText(I18n.t("tool.title.none"));
        badgesLabel.setText("");
        setDescription(idleHintText());
        setStatus(StatusKind.NONE, null);
        prefillArguments(null);
        definitionViewer.setText("");
        historyPanel.showTool(null);
        // 没有选中工具就没有"这个工具的请求头"可编辑，页签一并撤掉
        syncHeadersTab();
        selectDefaultTab();
        setBusy(false);
        resultView.clear();
    }

    private String idleHintText() {
        if (idleKey != null) {
            return I18n.t(idleKey);
        }
        return idleText == null ? "" : idleText;
    }

    public void showTool(McpTool tool) {
        this.tool = tool;
        titleLabel.setText(tool.getName());
        badgesLabel.setText(badges(tool));
        setDescription(toolDescription(tool));
        prefillArguments(tool);
        definitionViewer.setText(JsonUtil.pretty(tool.getRaw()));
        historyPanel.showTool(tool);
        // 工具变了，工具级请求头跟着换一份（服务器级那份在工具栏，不动）
        syncHeadersTab();
        selectDefaultTab();
        setStatus(StatusKind.NONE, null);
        setBusy(false);
        resultView.clear();
    }

    /** 工具描述；服务端没给描述时补一句，别留一片空白。 */
    private static String toolDescription(McpTool tool) {
        String description = tool.getDescription();
        return description == null || description.isBlank()
                ? I18n.t("tool.description.none") : description;
    }

    /**
     * 按工具的 inputSchema 预填参数。
     *
     * <p>以前这里是"切到 JSON 页签时把表单当前内容搬过来"，而表单只在"用户填过值"时才收集字段，
     * 于是没有 default 的参数一个都不会出现在 JSON 里——页签看着一片空白，
     * 用户既不知道有哪些参数，也就无从下手（"为什么这个工具的 JSON 是空的"就是这么来的）。
     * 现在直接按 schema 声明生成，每个参数都在，且永远是一份能解析的 JSON。
     *
     * <p>没有 inputSchema（或 schema 畸形）时给 {@code {}}：宁可给一个空对象让人自己往里写，
     * 也不要留一个没有内容、没有提示的空白编辑区。
     */
    private void prefillArguments(McpTool target) {
        JsonObject template = new JsonObject();
        if (target != null && target.hasInputSchema()) {
            try {
                template = SchemaUtil.template(target.getInputSchema());
            } catch (RuntimeException ignored) {
                // 服务端 schema 再畸形也不该让输入区变成白板，退回空对象即可
            }
        }
        jsonEditor.setText(JsonUtil.pretty(template));
        jsonEditor.setCaretPosition(0);
    }

    private void setDescription(String text) {
        descriptionLabel.setFullText(text);
    }

    private void setBusy(boolean value) {
        this.busy = value;
        // 没选中工具时把调用 / 重置置灰：面板在空态下也会显示（请求头页签要用），
        // 一个亮着但按了没反应的「调用」比置灰更让人困惑
        invokeButton.setEnabled(!value && tool != null);
        invokeButton.setText(I18n.t(value ? "tool.action.invoking" : "tool.action.invoke"));
        resetButton.setEnabled(!value && tool != null);
        // 卡片里的「重新调用」是画上去的，"能不能点"变了必须让列表重画一遍才换样子
        // （历史数据这时没变，refresh() 不会跑）
        historyPanel.repaintActions();
    }

    private static String badges(McpTool tool) {
        List<String> parts = new ArrayList<>();
        // badge 之间的 "  ·  " 两种语言都是这个形态，不单独占一个键
        if (tool.isReadOnly()) {
            parts.add("<font color='#1F7A3D'>" + I18n.t("tool.badge.readOnly") + "</font>");
        }
        if (tool.isDestructive()) {
            parts.add("<font color='#C0392B'>" + I18n.t("tool.badge.destructive") + "</font>");
        }
        if (tool.isIdempotent()) {
            parts.add(I18n.t("tool.badge.idempotent"));
        }
        if (tool.isOpenWorld()) {
            parts.add(I18n.t("tool.badge.openWorld"));
        }
        String subtitle = tool.getSubtitle();
        if (subtitle != null && !subtitle.isBlank()) {
            parts.add(Ui.escapeHtml(Ui.ellipsize(subtitle, 60)));
        }
        return parts.isEmpty() ? "" : "<html>" + String.join("  ·  ", parts) + "</html>";
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /** 触发调用（按钮与 Ctrl+Enter 共用）。 */
    public void invoke() {
        if (tool == null || busy || callback == null) {
            return;
        }
        JsonObject arguments;
        try {
            arguments = JsonUtil.parseObjectLenient(jsonEditor.getText());
        } catch (IllegalArgumentException e) {
            setStatus(StatusKind.BAD_JSON, e.getMessage());
            return;
        }

        setStatus(StatusKind.CALLING, String.valueOf(arguments.size()));
        setBusy(true);
        resultView.showLoading(null);
        applyResultSubject();
        callback.invokeTool(tool, arguments);
    }

    /** 调用结束：渲染结果。 */
    public void showResult(McpCallResult result) {
        setBusy(false);
        resultView.show(result, null);
        applyResultSubject();
        if (result == null) {
            return;
        }
        if (result.getError() != null) {
            setStatus(StatusKind.FAILED, null);
        } else if (!result.isSuccess()) {
            setStatus(StatusKind.TOOL_ERROR, null);
        } else {
            setStatus(StatusKind.DONE, String.valueOf(result.getElapsedMillis()));
        }
    }

    /** 本地异常（未连接、超时、进程挂了）。 */
    public void showInvokeError(String message) {
        setBusy(false);
        // 状态栏只说"失败"，细节（哪一步、什么原因）归结果区
        setStatus(StatusKind.FAILED, null);
        resultView.showFailure(message, null);
        applyResultSubject();
    }

    /**
     * 把结果区状态句里的"被调对象"交给文案键解析（工具名当占位符实参）。
     *
     * <p>不能直接传渲染好的文本——语言一切换，句子换了新语言、这个词还卡在旧语言
     * （会出现"Calling 工具「echo」 …"这种半中半英）。
     */
    private void applyResultSubject() {
        if (tool == null) {
            resultView.setSubjectKey("tool.what.plain");
        } else {
            resultView.setSubjectKey("tool.what.named", tool.getName());
        }
    }

    // ------------------------------------------------------------------
    // 状态栏
    // ------------------------------------------------------------------

    private void setStatus(StatusKind kind, @Nullable String arg) {
        this.statusKind = kind;
        this.statusArg = arg;
        renderStatus();
    }

    /** 按当前语言把状态栏那句话重新拼出来。 */
    private void renderStatus() {
        statusLabel.setForeground(switch (statusKind) {
            case BAD_JSON, FAILED -> Ui.ERROR;
            case TOOL_ERROR -> Ui.WARN;
            case DONE -> Ui.OK;
            default -> JBColor.GRAY;
        });
        statusLabel.setText(switch (statusKind) {
            case HISTORY -> I18n.t("tool.status.historyLoaded", statusArg);
            case BAD_JSON -> I18n.t("tool.status.badJson", statusArg);
            case CALLING -> I18n.t("tool.status.calling", statusArg);
            case FAILED -> I18n.t("tool.status.failed");
            case TOOL_ERROR -> I18n.t("tool.status.toolError");
            case DONE -> I18n.t("tool.status.done", statusArg);
            case NONE -> "";
        });
    }

    // ------------------------------------------------------------------
    // 语言
    // ------------------------------------------------------------------

    /**
     * 重新贴一遍这份面板自己的文案（语言切换时由主面板级联调用）。
     *
     * <p><b>只换字，不重建。</b>页签顺序、编辑区内容与光标、分隔条比例、目录树的展开
     * 状态都得原样留着——切语言的代价本来就只该是"字变了"。任何"因为要换文案所以
     * 重建一遍"的做法都会把这些一起弄丢。
     */
    public void applyTexts() {
        // 子面板各自贴自己的那部分：结果区的状态条、请求头正文、历史列表
        resultView.applyTexts();
        headersPanel.applyTexts();
        historyPanel.applyTexts();

        invokeButton.setText(I18n.t(busy ? "tool.action.invoking" : "tool.action.invoke"));
        invokeButton.setToolTipText(I18n.t("tool.action.invoke.tip"));
        resetButton.setToolTipText(I18n.t("tool.action.reset.tip"));
        formatButton.setToolTipText(I18n.t("tool.action.format.tip"));

        setTabTitle(jsonTab, "tool.tab.json");
        setTabTitle(definitionTab, "tool.tab.definition");
        setTabTitle(historyPanel, "tool.tab.history");
        refreshHeadersTitle();
        refreshHistoryTitle();

        if (tool == null) {
            titleLabel.setText(I18n.t("tool.title.none"));
            setDescription(idleHintText());
        } else {
            // 标题留着工具名（那是服务端给的名字，不翻），描述可能被换过
            badgesLabel.setText(badges(tool));
            setDescription(toolDescription(tool));
        }
        renderStatus();
    }

    /** 按组件换页签标题——用 indexOfComponent 而不是记死下标，页签可以缺（请求头）。 */
    private void setTabTitle(JComponent component, String key) {
        int index = tabs.indexOfComponent(component);
        if (index >= 0) {
            tabs.setTitleAt(index, I18n.t(key));
        }
    }

    @Override
    public void dispose() {
        resultView.dispose();
    }
}
