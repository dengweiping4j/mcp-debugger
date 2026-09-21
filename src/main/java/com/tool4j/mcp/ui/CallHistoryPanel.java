package com.tool4j.mcp.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.model.ToolCallRecord;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.settings.McpCallHistory;

import org.jetbrains.annotations.Nullable;

import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.ScrollPaneConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 「历史」页签：当前工具每次调用留下的入参，<b>点一下就能填回 JSON 页签</b>。
 *
 * <p><b>为什么是页签而不是一个下拉框。</b>选历史本质上是"看一批、挑一条"，下拉框一次只露一行、
 * 还得按住才不消失；而填写参数的 JSON 就在隔壁页签，两者本该是同一级的东西。
 * 页签标题带条数（{@code 历史 (3)}），和「请求头」一致——不点开也知道这里有没有东西。
 *
 * <p><b>点条目即带入，并自动切回「JSON」页签。</b>被改写的是隔壁那一页的内容，而这一页的列表
 * 一点没变——停在历史页上只能看到"点了没反应"，很容易以为点空了。切过去，改动就在眼前，
 * 且「调用」按钮就在页签上方那条动作栏上，可以直接 Ctrl+Enter 重发。
 * 代价是"在历史里连着试几条"要多点一下回来取。被覆盖的 JSON 内容可 Ctrl+Z 找回
 * （{@code EditorTextField.setText} 走的是编辑器命令，进撤销栈），
 * 所以那句提示现在必须写在页签之外的动作栏上——提示的时候历史页已经不在眼前了。
 *
 * <p><b>列表只给一眼能扫的信息。</b>两行：上行"时刻 + 结局"，下行是入参的
 * {@code k=v} 摘要（完整 JSON 在 tooltip 与「JSON」页签里）。之所以不直接把整份 JSON 塞进列表：
 * 窄边栏里它会被吃掉一半，而 {@code path=src/main  ·  limit=10} 这种写法
 * 不用展开就能分辨是哪一次调用——列表是索引，详情在别处。
 *
 * <p>历史按 <b>serverId + toolName</b> 存（见 {@link McpCallHistory}）。它挂在工具上而不是服务器上：
 * 同一个服务器的十几个工具共用一份历史没有意义，翻起来只会互相干扰。
 */
public final class CallHistoryPanel extends JPanel {

    /** 「带入」回调：把某条记录里的入参填回 JSON 页签。 */
    public interface Loader {
        void loadArguments(JsonObject arguments, ToolCallRecord record);
    }

    /** 一行摘要里最多列几个参数——窄边栏里写到第 5 个也看不见，剩下的折成"共 N 个参数"。 */
    private static final int SUMMARY_KEYS = 4;
    /** 单个参数值的显示长度上限。 */
    private static final int VALUE_LIMIT = 28;
    /**
     * 列表单元里文字之外占掉的像素：左右内边距 8 + 状态圆点连间隙约 15 + 留一点余量。
     *
     * <p>不算滚动条的宽度——宽度基数取的是 {@code visibleRect}，那里已经被视口扣掉了，
     * 再扣一次等于白扔十几个像素的文字空间。
     */
    private static final int ROW_CHROME = 26;

    /** 状态行的六种场景，供 applyTexts 在切语言时按同一份输入重渲染。 */
    private static final int STATE_NO_TOOL = 0;
    private static final int STATE_NO_RECORD = 1;
    private static final int STATE_LOADED = 2;
    private static final int STATE_SELECTED = 3;
    private static final int STATE_RECORDS = 4;
    private static final int STATE_INVALID = 5;
    private static final int STATE_COPIED = 6;

    private static final SimpleDateFormat TIME_TODAY = new SimpleDateFormat("HH:mm:ss");
    private static final SimpleDateFormat TIME_OTHER = new SimpleDateFormat("MM-dd HH:mm");
    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyyMMdd");

    private final Project project;
    private final McpCallHistory history = McpCallHistory.getInstance();

    private final DefaultListModel<ToolCallRecord> model = new DefaultListModel<>();
    private final JBList<ToolCallRecord> list = new RecordList(model);
    private final JBLabel state = Ui.hint(" ");
    private final JButton loadButton = new JButton(I18n.t("hist.loadArgs"));
    private final JButton copyButton = new JButton(AllIcons.Actions.Copy);
    private final JButton removeButton = new JButton(AllIcons.General.Remove);
    private final JButton clearButton = new JButton(AllIcons.Actions.GC);

    /** 当前绑定的服务器（只用来算历史归属，不参与展示）。 */
    private String serverId = "";
    /** 当前选中的工具。空字符串表示没选中任何工具，此时列表为空。 */
    private String toolName = "";

    private Loader loader;
    /** 最近一次真的被带入编辑器的记录；只用于"带上去了没有"这句提示，不参与取数。 */
    private ToolCallRecord loaded;
    /** 程序性改选中（刷新列表）时置位，避免把它当成用户点选而把编辑器里的内容换掉。 */
    private boolean syncing;
    /** 状态行当前场景；applyTexts 据此重渲染，不丢任何数据。 */
    private int stateKind = STATE_NO_RECORD;
    /** 状态行里需要跟着时间走的时间戳（带入 / 选中 / 复制时）。 */
    private long stateTs;
    /** 状态行 tooltip 要带的原始数据：入参非法的那条记录的原 JSON。 */
    private String stateDataTip;
    /** 页签标题需要刷新时的回调（条数变了要改标题）。 */
    private Runnable onChanged = () -> {
    };

    public CallHistoryPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);
        setBorder(JBUI.Borders.empty(6));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RecordRenderer());
        list.setEmptyText(I18n.t("hist.empty.title"));
        list.addListSelectionListener(e -> {
            // 拖动选择时别每动一下就改写编辑器
            if (e.getValueIsAdjusting()) {
                return;
            }
            onSelectionChanged();
        });

        JBScrollPane scroll = new JBScrollPane(list);
        scroll.setBorder(JBUI.Borders.empty());
        // 横向条永不出现：内容是程序算好宽度裁出来的，出现横向条只说明算错了，
        // 而它一出现就会再吃掉十几像素，恶性循环
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setPreferredSize(new Dimension(JBUI.scale(120), JBUI.scale(150)));
        scroll.setMinimumSize(new Dimension(JBUI.scale(60), JBUI.scale(60)));

        // 提示文字单独占一行、按钮另起一行：这里那句话（"覆盖了 JSON 页签"）必须读得完整，
        // 和按钮抢同一行的宽度时它最先被省略号吃掉，那恰恰是最该看见的一句
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setOpaque(false);
        buttons.add(buildButtons(), BorderLayout.WEST);

        JPanel footer = new JPanel(new BorderLayout(0, JBUI.scale(3)));
        footer.setOpaque(false);
        footer.setBorder(JBUI.Borders.emptyTop(4));
        footer.add(state, BorderLayout.NORTH);
        footer.add(buttons, BorderLayout.CENTER);

        add(scroll, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);

        // 新调用记入 / 单条删除 / 清空都会广播，列表据此刷新
        McpCallHistory.subscribe(parent, this::refresh);
        updateState();
        updateEnabledState();
        applyTexts();
    }

    /**
     * 一排按钮：文字按钮只留给主操作「带入参数」，其余用图标。
     *
     * <p>这个页签常态宽度就是两三百像素，四个文字按钮会把中间那行状态提示挤到看不见；
     * 而状态提示恰恰是回答"我刚才那一带到底生效了没"的地方。
     *
     * <p>点条目已经会带入，这个按钮不是多余的：<b>点已选中的那一行不会再触发选中事件</b>，
     * 而"先切到 JSON 改几笔、再切回历史想把原来那份要回来"是个正常操作——
     * 那一下只能靠按钮。
     */
    private JComponent buildButtons() {
        loadButton.setToolTipText(I18n.t("hist.loadTooltip"));
        loadButton.setMargin(JBUI.insets(2, 8, 2, 8));
        loadButton.setFocusable(false);
        loadButton.addActionListener(e -> loadSelected());

        iconButton(copyButton, I18n.t("hist.copyTooltip"));
        copyButton.addActionListener(e -> copySelected());

        iconButton(removeButton, I18n.t("hist.removeTooltip"));
        removeButton.addActionListener(e -> removeSelected());

        iconButton(clearButton, I18n.t("hist.clearTooltip"));
        clearButton.addActionListener(e -> clearAll());

        JPanel row = new JPanel();
        row.setOpaque(false);
        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.add(loadButton);
        row.add(Ui.hgap(4));
        row.add(copyButton);
        row.add(Ui.hgap(2));
        row.add(removeButton);
        row.add(Ui.hgap(2));
        row.add(clearButton);
        return row;
    }

    private static void iconButton(JButton button, String tooltip) {
        button.setText("");
        button.setToolTipText(tooltip);
        button.setMargin(JBUI.insets(2, 4, 2, 4));
        button.setFocusable(false);
    }

    // ------------------------------------------------------------------
    // 绑定
    // ------------------------------------------------------------------

    /** 带入回调；由 {@link ToolDetailPanel} 实现（往 JSON 编辑器里写）。 */
    public void setOnLoad(Loader loader) {
        this.loader = loader;
    }

    /** 页签标题需要刷新时的回调（条数变了要改标题）。 */
    public void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> {
        } : listener;
    }

    /**
     * 绑定当前服务器。
     *
     * <p>同一个实例重复绑定直接返回：{@link McpPanel} 在连接、刷新、切工具时都会重排右侧面板，
     * 每次重刷列表会把手刚点中的那一行、以及滚动位置打回去。
     */
    public void showConfig(@Nullable McpServerConfig config) {
        String id = config == null ? "" : config.getId();
        if (id.equals(serverId)) {
            return;
        }
        serverId = id;
        refresh();
    }

    /** 绑定当前工具。历史挂在工具上，切工具必须换一份。 */
    public void showTool(@Nullable McpTool tool) {
        String name = tool == null ? "" : tool.getName();
        if (name.equals(toolName)) {
            return;
        }
        toolName = name;
        loaded = null;
        refresh();
    }

    /** 页签标题。条数写在标题上，不点开也能看出这个工具调过几次。 */
    public String tabTitle() {
        int n = model.size();
        return n == 0 ? I18n.t("hist.tab") : I18n.t("hist.tabCount", n);
    }

    // ------------------------------------------------------------------
    // 列表
    // ------------------------------------------------------------------

    /**
     * 重新取数并铺进列表。
     *
     * <p>刷新<b>不会</b>把内容带回编辑器：{@code syncing} 期间的选择变化一律忽略。
     * 否则一次"新调用记入"就会顺手把用户正敲的 JSON 换掉，而那是发生在自己眼皮底下的
     * 静默破坏，比点错一下更难发现。
     */
    public void refresh() {
        List<ToolCallRecord> records = history.recordsOf(serverId, toolName);
        ToolCallRecord selected = list.getSelectedValue();

        syncing = true;
        try {
            model.clear();
            for (ToolCallRecord record : records) {
                model.addElement(record);
            }
            // 用恒等比较恢复选中：recordsOf 交回来的就是同一批实例，
            // 记录被裁掉时 indexOf 返回 -1，选中自然落空
            if (selected != null) {
                int index = records.indexOf(selected);
                if (index >= 0) {
                    list.setSelectedIndex(index);
                }
            }
        } finally {
            syncing = false;
        }

        updateState();
        updateEnabledState();
        onChanged.run();
    }

    private void onSelectionChanged() {
        updateState();
        updateEnabledState();
        if (!syncing) {
            // 点一下条目就带入，不必再点按钮——这是"一键"的那一下
            loadSelected();
        }
    }

    private void updateEnabledState() {
        boolean selected = list.getSelectedValue() != null;
        loadButton.setEnabled(selected && loader != null);
        copyButton.setEnabled(selected);
        removeButton.setEnabled(selected);
        clearButton.setEnabled(!model.isEmpty());
    }

    /** 重贴所有用户可见文案；语言切换时由外层统一级联调用，幂等且不碰数据。 */
    public void applyTexts() {
        list.setEmptyText(I18n.t("hist.empty.title"));
        loadButton.setText(I18n.t("hist.loadArgs"));
        loadButton.setToolTipText(I18n.t("hist.loadTooltip"));
        copyButton.setToolTipText(I18n.t("hist.copyTooltip"));
        removeButton.setToolTipText(I18n.t("hist.removeTooltip"));
        clearButton.setToolTipText(I18n.t("hist.clearTooltip"));
        renderState();
        onChanged.run();
    }

    /**
     * 状态行。三句话分别对应三种处境：没工具、没记录、有记录。
     *
     * <p>它独占一行（按钮在下一行），为的就是这句话读得完整——窄边栏里它一旦被挤成省略号，
     * "覆盖了 JSON 页签"这条最要紧的提示就没了。整句仍然挂 tooltip，把来龙去脉写全。
     */
    private void updateState() {
        if (toolName.isEmpty()) {
            stateKind = STATE_NO_TOOL;
            stateTs = 0;
            renderState();
            return;
        }
        if (model.isEmpty()) {
            stateKind = STATE_NO_RECORD;
            stateTs = 0;
            renderState();
            return;
        }
        ToolCallRecord selected = list.getSelectedValue();
        if (selected != null && selected == loaded) {
            // 覆盖了编辑器内容这件事必须说出来：Ctrl+Z 能找回来，但用户得先知道丢了什么。
            // 这句话只在"代入之后又切回历史页"时才看得到（代入当时这一页已经被切走了，
            // 那一下的提示在页签外的动作栏上）。
            stateKind = STATE_LOADED;
            stateTs = selected.timestamp;
            renderState();
        } else if (selected != null) {
            stateKind = STATE_SELECTED;
            stateTs = selected.timestamp;
            renderState();
        } else {
            stateKind = STATE_RECORDS;
            renderState();
        }
    }

    /**
     * 按当前场景与已存字段重画状态行。切换语言时由 applyTexts 调同一份逻辑，保证不丢数据。
     */
    private void renderState() {
        String text;
        String tip;
        switch (stateKind) {
            case STATE_NO_TOOL:
                text = I18n.t("hist.state.noTool");
                tip = text;
                state.setForeground(Ui.MUTED);
                break;
            case STATE_NO_RECORD:
                text = I18n.t("hist.state.noRecord");
                tip = text;
                state.setForeground(Ui.MUTED);
                break;
            case STATE_LOADED:
                text = I18n.t("hist.state.loaded", timeLabel(stateTs));
                tip = I18n.t("hist.state.loadedTip");
                state.setForeground(Ui.MUTED);
                break;
            case STATE_SELECTED:
                text = I18n.t("hist.state.selected", model.size(), timeLabel(stateTs));
                tip = I18n.t("hist.state.selectedTip");
                state.setForeground(Ui.MUTED);
                break;
            case STATE_RECORDS:
                text = I18n.t("hist.state.records", model.size());
                tip = I18n.t("hist.state.recordsTip");
                state.setForeground(Ui.MUTED);
                break;
            case STATE_INVALID:
                text = I18n.t("hist.invalidJson");
                tip = stateDataTip;
                state.setForeground(Ui.ERROR);
                break;
            case STATE_COPIED:
                text = I18n.t("hist.copied", timeLabel(stateTs));
                tip = null;
                state.setForeground(Ui.OK);
                break;
            default:
                text = " ";
                tip = null;
                state.setForeground(Ui.MUTED);
        }
        state.setText(text == null || text.isEmpty() ? " " : text);
        state.setToolTipText(tip);
    }

    // ------------------------------------------------------------------
    // 操作
    // ------------------------------------------------------------------

    /** 把选中的那条记录带入 JSON 页签。 */
    private void loadSelected() {
        ToolCallRecord record = list.getSelectedValue();
        if (record == null || loader == null) {
            return;
        }
        JsonObject arguments = parse(record.arguments);
        if (arguments == null) {
            stateKind = STATE_INVALID;
            stateTs = 0;
            stateDataTip = record.arguments;
            renderState();
            return;
        }
        loaded = record;
        loader.loadArguments(arguments, record);
        updateState();
    }

    private void copySelected() {
        ToolCallRecord record = list.getSelectedValue();
        if (record == null) {
            return;
        }
        JsonObject arguments = parse(record.arguments);
        Ui.copyToClipboard(JsonUtil.pretty(arguments == null ? new JsonObject() : arguments));
        stateKind = STATE_COPIED;
        stateTs = record.timestamp;
        renderState();
    }

    private void removeSelected() {
        ToolCallRecord record = list.getSelectedValue();
        if (record != null) {
            // 会广播出来，列表由 refresh() 自己收尾
            history.remove(record);
        }
    }

    private void clearAll() {
        if (model.isEmpty()) {
            return;
        }
        // 用 showDialog 自己给按钮文案，不用 showYesNoDialog——后者的「是 / 否」
        // 由平台按"IDE 的语言"渲染，插件切成另一种语言时会和别处对不上。
        int answer = Messages.showDialog(project,
                I18n.t("hist.clearConfirm", toolName, model.size()) + "\n"
                        + I18n.t("hist.clearHint"),
                I18n.t("hist.clearTitle"),
                new String[]{I18n.t("ui.yes"), I18n.t("ui.no")}, 0, Messages.getQuestionIcon());
        if (answer == 0) {
            history.clearTool(serverId, toolName);
        }
    }

    // ------------------------------------------------------------------
    // 格式化
    // ------------------------------------------------------------------

    /** 时刻：当天只给时分秒，隔天的补上月日——两者混在一起也能一眼分清。 */
    public static String timeLabel(long timestamp) {
        Date when = new Date(timestamp <= 0 ? System.currentTimeMillis() : timestamp);
        boolean today = DAY.format(when).equals(DAY.format(new Date()));
        return today ? TIME_TODAY.format(when) : TIME_OTHER.format(when);
    }

    /** 上行那半句：结局 + 耗时。 */
    private static String statusLabel(ToolCallRecord record) {
        if (record == null || record.status == null) {
            return I18n.t("hist.statusUnknown");
        }
        return switch (record.status) {
            case OK -> I18n.t("hist.statusOk", record.elapsedMillis);
            case TOOL_ERROR -> I18n.t("hist.statusToolError");
            case FAILED -> I18n.t("hist.statusFailed");
        };
    }

    private static JBColor statusColor(ToolCallRecord record) {
        if (record == null || record.status == null) {
            return Ui.MUTED;
        }
        return switch (record.status) {
            case OK -> Ui.OK;
            case TOOL_ERROR -> Ui.WARN;
            case FAILED -> Ui.ERROR;
        };
    }

    /**
     * 入参摘要：{@code path=src/main  ·  limit=10}。
     *
     * <p>不用原始 JSON 是有意的：列表是索引，要的是"这条和上一条哪里不一样"，
     * 而 {@code {"path":"src/main","limit":10}} 在窄边栏里念到第二个键就被吃掉了。
     * 对象/数组类参数退化成它们的紧凑 JSON（截断），因为"里面有什么"比"有几个键"有用。
     */
    private static String summarize(@Nullable String argumentsJson) {
        JsonObject arguments = parse(argumentsJson);
        if (arguments == null || arguments.size() == 0) {
            return I18n.t("hist.noParams");
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, JsonElement> entry : arguments.entrySet()) {
            if (shown >= SUMMARY_KEYS) {
                sb.append("  ").append(I18n.t("hist.summaryMore", arguments.size()));
                break;
            }
            if (shown > 0) {
                sb.append("  ·  ");
            }
            sb.append(entry.getKey()).append('=').append(valueText(entry.getValue()));
            shown++;
        }
        return sb.toString();
    }

    private static String valueText(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonPrimitive()) {
            return cut(JsonUtil.oneLine(value.getAsJsonPrimitive().getAsString()), VALUE_LIMIT);
        }
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < Math.min(array.size(), 3); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(valueText(array.get(i)));
            }
            if (array.size() > 3) {
                sb.append(", …");
            }
            return sb.append(']').toString();
        }
        return cut(JsonUtil.oneLine(JsonUtil.compact(value)), VALUE_LIMIT);
    }

    private static String cut(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, Math.max(1, limit - 1)) + "…";
    }

    /** 把存下来的入参解析回对象；记录被手工改坏时返回 null，由调用方给出可读的提示。 */
    private static @Nullable JsonObject parse(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtil.parseObjectLenient(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 完整内容走 tooltip：一行摘要装不下的东西（每个参数、失败原因）都在这里。 */
    private static String tooltipOf(ToolCallRecord record) {
        if (record == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html><b>")
                .append(Ui.escapeHtml(timeLabel(record.timestamp)))
                .append("  ·  ")
                .append(Ui.escapeHtml(statusLabel(record)))
                .append("</b>");
        if (record.note != null && !record.note.isBlank()) {
            sb.append("<br>").append(Ui.escapeHtml(JsonUtil.oneLine(record.note)));
        }
        sb.append("<br><br>");
        JsonObject arguments = parse(record.arguments);
        sb.append(indented(arguments == null ? record.arguments : JsonUtil.pretty(arguments)));
        return sb.append("</html>").toString();
    }

    /**
     * 把多行 JSON 变成 tooltip 里那种"等宽对齐"的样子。
     *
     * <p>Swing 的 HTML 不认 {@code <pre>}，缩进只能拿 {@code &nbsp;} 顶；
     * 又只对每行开头的空格这么做——整行都换的话长 JSON 就没法折行了。
     */
    private static String indented(String text) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        int limit = Math.min(lines.length, 30);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            String escaped = Ui.escapeHtml(lines[i]);
            int indent = 0;
            while (indent < escaped.length() && escaped.charAt(indent) == ' ') {
                indent++;
            }
            for (int j = 0; j < indent; j++) {
                sb.append("&nbsp;");
            }
            sb.append(escaped.substring(indent)).append("<br>");
        }
        if (lines.length > limit) {
            sb.append("…");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 列表渲染
    // ------------------------------------------------------------------

    /** 让 tooltip 跟着鼠标下面那一行走（{@code JList} 默认只认整表一个 tooltip）。 */
    private static final class RecordList extends JBList<ToolCallRecord> {
        RecordList(DefaultListModel<ToolCallRecord> model) {
            super(model);
        }

        @Override
        public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            if (index < 0 || index >= getModel().getSize()) {
                return null;
            }
            Rectangle bounds = getCellBounds(index, index);
            if (bounds == null || !bounds.contains(event.getPoint())) {
                return null;
            }
            return tooltipOf(getModel().getElementAt(index));
        }
    }

    /**
     * 两行一个单元：上行"时刻 + 结局"（状态圆点按成功/报错/失败着色），下行入参摘要。
     *
     * <p>文字宽度是<b>按像素算好</b>再截断的（{@link #fit}），不是靠布局裁剪：
     * {@code JBLabel} 首选宽度会跟着内容长，直接丢给列表会把整张表撑宽、逼出一条横向滚动条；
     * 而且裁剪只在右边切一刀，不如省略号能说明"后面还有"。
     *
     * <p>配色全部取自 {@code JList} 自己的前景/背景/选中色：渲染器是复用的，
     * 每一格都要按选中状态重设，漏一个就会出现"选中的那行还是黑字压蓝底"。
     */
    private final class RecordRenderer extends JPanel implements ListCellRenderer<ToolCallRecord> {

        private final JBLabel head = new JBLabel();
        private final JBLabel body = new JBLabel();

        RecordRenderer() {
            setOpaque(true);
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(JBUI.Borders.empty(3, 4, 3, 4));
            head.setFont(Ui.smaller(head.getFont()));
            body.setFont(Ui.monospace(smallerLabelSize()));
            // 两个标签必须真的挂上来。早先只把它们量了个尺寸、拿去算 setPreferredSize，
            // 行高因此是对的、内容却是空的 —— 表现是"页签标题写着 历史 (2)，点开一片空白"，
            // 而且不报任何错（面板透明背景 + 无子组件 = 静静地什么都不画）。
            head.setAlignmentX(LEFT_ALIGNMENT);
            body.setAlignmentX(LEFT_ALIGNMENT);
            add(head);
            add(body);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ToolCallRecord> list,
                                                      ToolCallRecord value, int index,
                                                      boolean selected, boolean hasFocus) {
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());

            // 宽度取"看得见的那块"而不是控件自身宽度：列表被塞在视口里，出现纵向滚动条时
            // 控件会比可视区宽十几像素，按控件宽度算出来的文字会正好被滚动条压住最后一两个字
            Rectangle visible = list.getVisibleRect();
            int listWidth = visible.width > 0 ? visible.width : list.getWidth();
            int width = Math.max(JBUI.scale(60), listWidth - JBUI.scale(ROW_CHROME));

            head.setIcon(Ui.dot(statusColor(value)));
            // 未选中时上行按结局着色，选中时统一走选中前景色——深红压在蓝底上是看不清的，
            // 那种时候"是哪一次调用"由圆点负责
            head.setForeground(selected ? foreground : statusColor(value));
            head.setText(fit(head, timeLabel(value == null ? 0 : value.timestamp)
                    + "   " + statusLabel(value), width));

            body.setForeground(selected ? foreground : Ui.MUTED);
            body.setText(fit(body, summarize(value == null ? null : value.arguments), width));

            // 高度必须把边框算进去，否则每个单元比内容矮 6px，两行文字的下一行会被切掉半截。
            // 宽度固定 100 是故意的：JList 因此不去跟随内容宽度，而是跟随视口宽度
            // —— 否则长文字会把整张表撑宽，逼出一条横向滚动条。
            Insets insets = getInsets();
            setPreferredSize(new Dimension(JBUI.scale(100),
                    head.getPreferredSize().height + body.getPreferredSize().height
                            + insets.top + insets.bottom));
            return this;
        }

        private float smallerLabelSize() {
            Font base = UIManager.getFont("Label.font");
            float size = base == null ? 12f : base.getSize2D();
            return Math.max(9f, size - 1f);
        }
    }

    /** 截到给定像素宽，放不下就在末尾加省略号。二分找最长的能放下的前缀。 */
    private static String fit(JBLabel label, String text, int width) {
        String value = text == null ? "" : text;
        if (value.isEmpty() || width <= 0) {
            return value;
        }
        FontMetrics metrics = label.getFontMetrics(label.getFont());
        if (metrics.stringWidth(value) <= width) {
            return value;
        }
        int available = width - metrics.stringWidth("…");
        if (available <= 0) {
            return "…";
        }
        int low = 0;
        int high = value.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (metrics.stringWidth(value.substring(0, mid)) <= available) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return value.substring(0, low) + "…";
    }
}
