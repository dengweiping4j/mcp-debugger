package com.tool4j.mcp.ui;

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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;

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
 * <p><b>一条记录是一张卡片，卡片上只有一行。</b>那一行是「状态点 + 状态词 …… 时刻 · 耗时」，
 * 再加右下角一个「重新调用」按钮。<b>入参与响应都不印在卡片上</b>——窄边栏里它们只会被吃掉一半，
 * 而"状态 + 时刻 + 耗时"这三样已经足够回答"哪一次是成功的、哪一次卡住了"。
 * 详情（完整入参、响应预览、失败原因）全部交给悬停气泡：列表是索引，详情在别处。
 *
 * <p><b>代价：两条入参、状态、耗时都一样的记录，在列表里长得一模一样。</b>
 * 这是显式选择——保持卡片能被一眼扫过去，代价是分辨它们要多悬停一下。
 * 卡片上的按钮因此有点用：拿不准是哪条时，点一下看结果即可。
 *
 * <p><b>卡片里的按钮是自绘的。</b>列表渲染器是"橡皮图章"——同一个组件被反复复用，
 * 往里塞真的 {@code JButton} 只会让它跟着最后一次渲染跑（而且按钮根本收不到事件）。
 * 所以这里<b>画</b>一个按钮，点击靠 {@link RecordList} 自己做命中判定；
 * 绘制与判定共用同一个 {@link #actionRect}，免得两处公式走样。
 *
 * <p>历史按 <b>serverId + toolName</b> 存（见 {@link McpCallHistory}）。它挂在工具上而不是服务器上：
 * 同一个服务器的十几个工具共用一份历史没有意义，翻起来只会互相干扰。
 */
public final class CallHistoryPanel extends JPanel {

    /** 「带入」回调：把某条记录里的入参填回 JSON 页签。 */
    public interface Loader {
        void loadArguments(JsonObject arguments, ToolCallRecord record);
    }

    /**
     * 「重新调用」回调：拿某条记录的入参<b>立刻再发一次</b>。
     *
     * <p>与 {@link Loader} 的区别是它要真的发请求，不是只把内容摆回编辑器；
     * 所以带副作用的工具（写文件、下单、发消息）点下去就会真执行，不再有确认环节。
     */
    public interface Rerunner {
        void rerun(JsonObject arguments, ToolCallRecord record);
    }

    /** 卡片左侧那条状态色条的宽度。状态只靠它和圆点表达，文字一律用普通前景色（理由见渲染器）。 */
    private static final int ACCENT_WIDTH = 3;
    /** 卡片内边距。左边多留一点，让正文离状态色条有呼吸。 */
    private static final int CARD_TOP = 7;
    private static final int CARD_LEFT = 10;
    private static final int CARD_BOTTOM = 7;
    private static final int CARD_RIGHT = 9;
    /** 卡片之间的垂直间距。 */
    private static final int CARD_GAP = 5;
    /** 卡片圆角半径。 */
    private static final int CARD_ARC = 9;
    /** 「重新调用」按钮的高度。比 {@link Ui#INPUT_HEIGHT} 矮一点点，它是卡片里的次要动作。 */
    private static final int ACTION_HEIGHT = 22;
    /** 按钮文字到左右描边的内边距（单边）。 */
    private static final int ACTION_PAD_X = 10;
    /** 状态行与按钮之间的间距。 */
    private static final int ACTION_GAP = 7;
    /** 按钮圆角。比卡片小一号，嵌在卡片里才不像又一个卡片。 */
    private static final int ACTION_ARC = 6;

    /**
     * 卡片里文字之外占掉的像素：状态色条 3 + 左右内边距 19 + 留一点余量。
     *
     * <p>不算滚动条的宽度——宽度基数取的是 {@code visibleRect}，那里已经被视口扣掉了，
     * 再扣一次等于白扔十几个像素的文字空间。
     */
    private static final int CARD_CHROME = ACCENT_WIDTH + CARD_LEFT + CARD_RIGHT + 4;

    /** 悬停气泡里两段的行首标记。是符号不是文字，不占词条。 */
    private static final String REQUEST_MARK = "↑ ";
    private static final String RESPONSE_MARK = "↓ ";
    /** 气泡里入参最多展开几行——整份 JSON 动辄几十行，气泡不该比列表还高。 */
    private static final int TIP_ARG_LINES = 12;

    /**
     * 没有响应可显示时的占位。
     *
     * <p>1.0.5 之前的历史没有 {@link ToolCallRecord#response}。留空会被读成"响应是空的"，
     * 一条破折号至少说明"这条当时没记"。
     */
    private static final String NO_RESPONSE = "—";

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

    private Rerunner rerunner;

    /**
     * 「现在能不能调用」的判据。卡片里的按钮据此画成可用 / 禁用；
     * 由 {@link com.tool4j.mcp.ui.ToolDetailPanel} 提供（未连上、或上一次调用还没回来时都是 false）。
     */
    private BooleanSupplier callable = () -> false;
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

    /** 重新调用回调；由 {@link ToolDetailPanel} 实现（填回编辑器后直接发起调用）。 */
    public void setOnRerun(Rerunner rerunner) {
        this.rerunner = rerunner;
    }

    /**
     * 传入"现在能不能调用"的判据。
     *
     * <p>按钮画成禁用态比点了没反应好得多：后者会让人以为插件坏了。
     */
    public void setCallable(BooleanSupplier supplier) {
        this.callable = supplier == null ? () -> false : supplier;
    }

    /**
     * 让列表重画一遍，用于"能不能调用"这件事在外面变了（连上 / 断开 / 调用中）
     * 而历史数据本身没变的情况——那种时候 {@link #refresh()} 不会跑，按钮却该换样子。
     */
    public void repaintActions() {
        list.repaint();
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
        // 卡片里的按钮是自绘的，文字与宽度都在渲染时才取；换语言后必须逼列表重画一遍，
        // 否则按钮上还是上一门语言（而且宽度对不上，画和判会错开）
        list.repaint();
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

    /**
     * 卡片里「重新调用」被点中。
     *
     * <p><b>先选中再调。</b>选中既是"点的是哪条"的反馈，也让下方工具栏那一排跟着作用于同一条。
     * 这一下会顺带触发 {@link #onSelectionChanged()} 里的带入——那恰好是本按钮承诺的一半
     * （填回 JSON 页签），另一半（真的发出去）由 {@link Rerunner} 负责。
     *
     * <p>注意选中那一下<b>可能不触发</b>：如果点的就是当前已选中的那行，选择事件不会来。
     * 所以"填回 JSON"这一步不能指望它，{@link Rerunner} 那边必须自己再写一次。
     */
    private void rerunFromCard(int index) {
        ToolCallRecord record = model.get(index);
        if (record == null || rerunner == null) {
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
        list.setSelectedIndex(index);
        rerunner.rerun(arguments, record);
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

    /** 结局的短词。耗时在卡片上另占一列（靠右），所以这里不带上它。 */
    private static String statusWord(@Nullable ToolCallRecord record) {
        if (record == null || record.status == null) {
            return I18n.t("hist.statusUnknown");
        }
        return switch (record.status) {
            case OK -> I18n.t("hist.statusOk");
            case TOOL_ERROR -> I18n.t("hist.statusToolError");
            case FAILED -> I18n.t("hist.statusFailed");
        };
    }

    /**
     * 耗时：1 秒以下给毫秒，以上给秒（一位小数）。
     *
     * <p>不统一成毫秒是有意的——{@code 2413 ms} 要心算一下才知道是两秒多，
     * 而"这次怎么这么慢"正是扫列表时最想看的那一眼。
     */
    private static String elapsedLabel(@Nullable ToolCallRecord record) {
        long millis = record == null ? 0 : Math.max(0, record.elapsedMillis);
        if (millis < 1000) {
            return I18n.t("hist.durMs", millis);
        }
        // 固定 ROOT：默认 locale 会把小数点渲染成逗号（德语等），数字列跟着等宽字体一起歪
        return I18n.t("hist.durSec", String.format(Locale.ROOT, "%.1f", millis / 1000.0));
    }

    /**
     * 卡片与 tooltip 共用的响应文本。
     *
     * <p>老记录（没有存响应）走占位符，而不是空串——空串会被读成"响应确实是空的"。
     */
    private static String responseText(@Nullable ToolCallRecord record) {
        if (record == null || record.response == null || record.response.isBlank()) {
            return NO_RESPONSE;
        }
        return record.response;
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

    /**
     * 完整内容走 tooltip：卡片上只有一行，装不下的东西（每个参数、响应、失败原因）全在这里。
     *
     * <p>这是现在分辨两条"看起来一样"的记录<b>唯一</b>的出口，所以入参按原样展开、不再折成摘要。
     */
    private static String tooltipOf(ToolCallRecord record) {
        if (record == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("<html><b>")
                .append(Ui.escapeHtml(statusWord(record)))
                .append("  ·  ")
                .append(Ui.escapeHtml(timeLabel(record.timestamp)))
                .append("  ·  ")
                .append(Ui.escapeHtml(elapsedLabel(record)))
                .append("</b>");
        if (record.note != null && !record.note.isBlank()) {
            sb.append("<br>").append(Ui.escapeHtml(JsonUtil.oneLine(record.note)));
        }
        JsonObject arguments = parse(record.arguments);
        sb.append("<br><br>")
                .append(REQUEST_MARK).append(Ui.escapeHtml(I18n.t("hist.tipReq")))
                .append("<br>")
                .append(indented(arguments == null ? record.arguments
                        : JsonUtil.pretty(arguments), TIP_ARG_LINES))
                .append("<br>")
                .append(RESPONSE_MARK).append(Ui.escapeHtml(I18n.t("hist.tipResp")))
                .append("<br>")
                .append(indented(responseText(record), TIP_ARG_LINES));
        return sb.append("</html>").toString();
    }

    /**
     * 把多行 JSON 变成 tooltip 里那种"等宽对齐"的样子。
     *
     * <p>Swing 的 HTML 不认 {@code <pre>}，缩进只能拿 {@code &nbsp;} 顶；
     * 又只对每行开头的空格这么做——整行都换的话长 JSON 就没法折行了。
     */
    private static String indented(String text, int limit) {
        String[] lines = (text == null ? "" : text).split("\n", -1);
        int shown = Math.min(lines.length, limit);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < shown; i++) {
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
        if (lines.length > shown) {
            sb.append("…");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 列表渲染
    // ------------------------------------------------------------------

    /**
     * 列表本体。除了让 tooltip 跟着鼠标走（{@code JList} 默认只认整表一个 tooltip），
     * 还负责卡片里那个自绘按钮的<b>命中判定</b>。
     *
     * <p>渲染器只是<b>画</b>出按钮，事件它一个也收不到——它是被反复复用的橡皮图章，
     * 也不是活的组件树成员。所以悬停高亮、按下反馈、点击触发全由这里的鼠标监听驱动：
     * 算出按钮矩形、判断鼠标是不是在里头，然后只重画受影响的那一行。
     *
     * <p>矩形由 {@link #actionRect} 算，和绘制用的是同一个方法——各写一份迟早会错开半格，
     * 表现就是"看着在按钮上，点了没反应"。
     */
    private final class RecordList extends JBList<ToolCallRecord> {

        /** 鼠标所在的行；-1 表示不在任何行上。 */
        private int hoverIndex = -1;
        /** 鼠标是不是正落在按钮上（不可调用时恒为 false，禁用按钮不响应）。 */
        private boolean hoverOnAction;
        /** 按下但还没松手的行；-1 表示没有。松手时人已经移开了就不触发，点错了能取消。 */
        private int pressedIndex = -1;

        RecordList(DefaultListModel<ToolCallRecord> model) {
            super(model);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    updateHover(e);
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    updateHover(e);
                    if (hoverOnAction) {
                        pressedIndex = hoverIndex;
                        repaintCell(pressedIndex);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    int index = pressedIndex;
                    pressedIndex = -1;
                    updateHover(e);
                    repaintCell(index);
                    if (index >= 0 && index == hoverIndex && hoverOnAction) {
                        rerunFromCard(index);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    int old = hoverIndex;
                    hoverIndex = -1;
                    hoverOnAction = false;
                    pressedIndex = -1;
                    repaintCell(old);
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        private void updateHover(MouseEvent e) {
            int index = locationToIndex(e.getPoint());
            boolean onAction = false;
            if (index >= 0 && index < getModel().getSize()) {
                Rectangle action = actionBounds(index);
                onAction = action != null && action.contains(e.getPoint()) && callable.getAsBoolean();
            }
            int oldIndex = hoverIndex;
            boolean oldOnAction = hoverOnAction;
            hoverIndex = index;
            hoverOnAction = onAction;
            if (oldIndex != index || oldOnAction != onAction) {
                repaintCell(oldIndex);
                repaintCell(index);
            }
        }

        /** 按钮在这一行里的绝对坐标；绘制用的是同一套常量算出的相对坐标。 */
        private Rectangle actionBounds(int index) {
            Rectangle cell = getCellBounds(index, index);
            if (cell == null) {
                return null;
            }
            // 单元格比卡片高出一个 CARD_GAP：那是渲染器外层留出来的卡片间距
            Rectangle r = actionRect(cell.width, cell.height - JBUI.scale(CARD_GAP), actionWidth(this));
            r.translate(cell.x, cell.y);
            return r;
        }

        private void repaintCell(int index) {
            if (index < 0 || index >= getModel().getSize()) {
                return;
            }
            Rectangle cell = getCellBounds(index, index);
            if (cell != null) {
                repaint(cell);
            }
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
            // 悬停在按钮上就说按钮本身的事（含"为什么现在点不了"），
            // 其余地方才是这条记录的详情——一个气泡不该同时承担两种回答
            if (hoverIndex == index && hoverOnAction) {
                return I18n.t("hist.rerunTip");
            }
            Rectangle action = actionBounds(index);
            if (action != null && action.contains(event.getPoint())) {
                return callable.getAsBoolean()
                        ? I18n.t("hist.rerunTip")
                        : I18n.t("hist.rerunDisabled");
            }
            return tooltipOf(getModel().getElementAt(index));
        }
    }

    /**
     * 「重新调用」按钮在<b>卡片坐标系</b>里的矩形。绘制（{@link CardPanel#paintAction}）与
     * 命中判定（{@link RecordList#actionBounds}）都只认这一个方法——各写一份迟早错开半格，
     * 症状是"看着在按钮上，点了没反应"。
     */
    private static Rectangle actionRect(int cardWidth, int cardHeight, int buttonWidth) {
        int height = JBUI.scale(ACTION_HEIGHT);
        return new Rectangle(cardWidth - JBUI.scale(CARD_RIGHT) - buttonWidth,
                cardHeight - JBUI.scale(CARD_BOTTOM) - height,
                buttonWidth, height);
    }

    /**
     * 按钮宽度：文字（跟着语言走）+ 左右内边距。
     *
     * <p>画的时候和算命中矩形的时候都从这里取，所以切语言后两边不会一个宽一个窄。
     */
    private static int actionWidth(JList<?> list) {
        FontMetrics metrics = list.getFontMetrics(Ui.smaller(list.getFont()));
        return metrics.stringWidth(I18n.t("hist.rerun")) + 2 * JBUI.scale(ACTION_PAD_X);
    }

    /**
     * 卡片本体：自绘圆角背景 + 左侧状态色条 + 右下角那个"不是按钮的按钮"。
     *
     * <p>为什么自绘：{@code JPanel} 画出来的背景永远是矩形，而"像张卡片"一半来自圆角；
     * 拿 {@code BorderFactory} 拼一圈线框只会得到直角盒子。左侧那条色带也按同一形状裁一刀，
     * 否则它的左上角会从圆角里探出一个直角。
     */
    private static final class CardPanel extends JPanel {

        private Color accent = Ui.MUTED;
        /** 卡片本身是否被悬停（描边加重）。 */
        private boolean hovered;
        /** 「重新调用」按钮的文字、宽度与三态。宽度由外部量好传进来，避免画与算两次测量。 */
        private String actionText = "";
        private int actionWidth;
        private Font actionFont;
        private boolean actionHovered;
        private boolean actionPressed;
        private boolean actionEnabled = true;

        CardPanel() {
            super(new BorderLayout());
            // 背景自己画，所以对平台声明"我是透明的"——不然 LAF 会先铺一层矩形底色
            setOpaque(false);
        }

        void setAccent(Color color) {
            Color next = color == null ? Ui.MUTED : color;
            if (!next.equals(accent)) {
                accent = next;
                repaint();
            }
        }

        void setHovered(boolean value) {
            hovered = value;
        }

        void setAction(String text, int width, Font font,
                       boolean onAction, boolean pressed, boolean enabled) {
            actionText = text == null ? "" : text;
            actionWidth = width;
            actionFont = font;
            actionHovered = onAction;
            actionPressed = pressed;
            actionEnabled = enabled;
        }

        /**
         * 画那个"不是按钮的按钮"。
         *
         * <p>三态全部落在同一处：禁用只描最淡的一档边 + 灰字；可用且悬停叠一层半透明填充；
         * 按下再加深。填充<b>必须半透明</b>——它要盖在列表底色 / 选中底色 / 卡片底色上，
         * 写死一个实色换个主题就是一块脏斑。
         */
        private void paintAction(Graphics2D g2, int width, int height) {
            Rectangle r = actionRect(width, height, actionWidth);
            Shape shape = new RoundRectangle2D.Float(r.x + 0.5f, r.y + 0.5f, r.width - 1f, r.height - 1f,
                    JBUI.scale(ACTION_ARC), JBUI.scale(ACTION_ARC));
            Color text = actionEnabled ? getForeground() : Ui.MUTED;
            if (actionEnabled && actionPressed) {
                g2.setColor(Ui.PRESS_FILL);
                g2.fill(shape);
            } else if (actionEnabled && actionHovered) {
                g2.setColor(Ui.HOVER_FILL);
                g2.fill(shape);
            }
            g2.setColor(JBColor.border());
            g2.draw(shape);

            g2.setColor(text);
            g2.setFont(actionFont == null ? getFont() : actionFont);
            FontMetrics metrics = g2.getFontMetrics();
            int baseline = r.y + (r.height - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(actionText, r.x + (r.width - metrics.stringWidth(actionText)) / 2, baseline);
        }

        @Override
        public void setBackground(Color bg) {
            super.setBackground(bg);
            // 自己画背景的组件得自己触发重绘：平台只在 opaque 时才替它 repaint
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // 内缩 0.5px：1px 的描边画在整数坐标上会被劈成两条半透明的边
                Shape shape = new RoundRectangle2D.Float(0.5f, 0.5f, w - 1f, h - 1f,
                        JBUI.scale(CARD_ARC), JBUI.scale(CARD_ARC));
                Color fill = getBackground();
                g2.setColor(fill == null ? Ui.MUTED : fill);
                g2.fill(shape);

                g2.setClip(shape);
                g2.setColor(accent);
                g2.fillRect(0, 0, JBUI.scale(ACCENT_WIDTH), h);
                g2.setClip(null);

                // 悬停把描边提亮一档：气泡属于哪一卡，得先让人看清鼠标在哪张卡上
                g2.setColor(hovered ? Ui.MUTED : JBColor.border());
                g2.setStroke(new BasicStroke(1f));
                g2.draw(shape);

                if (actionWidth > 0) {
                    paintAction(g2, w, h);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * 一张卡片 = 一行 + 一个按钮：状态行「状态点 + 状态词 …… 时刻 · 耗时」，
     * 右下角是「重新调用」。入参与响应都不印在卡片上，全在悬停气泡里（理由见类头）。
     *
     * <p>文字宽度是<b>按像素算好</b>再截断的（{@link #fit}），不是靠布局裁剪：
     * {@code JBLabel} 首选宽度会跟着内容长，直接丢给列表会把整张表撑宽、逼出一条横向滚动条；
     * 而且裁剪只在右边切一刀，不如省略号能说明"后面还有"。
     *
     * <p><b>状态只用两处表达：左侧色条与状态圆点。</b>状态词本身一律用普通前景色——
     * 早先是把文字也染成状态色，选中时深红压在蓝色选中底上根本读不出来。现在文字只分
     * "正常前景色"与"弱化的灰"两档，选中时整卡切到选中前景色，任何主题下都读得清。
     *
     * <p>配色全部取自 {@code JList} 自己的前景/背景/选中色：渲染器是复用的，
     * 每一格都要按选中状态重设，漏一个就会出现"选中的那卡还是白底黑字"。
     */
    private final class RecordRenderer extends JPanel implements ListCellRenderer<ToolCallRecord> {

        private final CardPanel card = new CardPanel();
        private final JBLabel dot = new JBLabel();
        private final JBLabel status = new JBLabel();
        private final JBLabel time = new JBLabel();
        private final JBLabel elapsed = new JBLabel();

        RecordRenderer() {
            super(new BorderLayout());
            // 卡片之间的缝靠外层的 bottom border 留出来；外层必须不透明，否则缝里会漏出上一帧
            setOpaque(true);
            setBorder(JBUI.Borders.emptyBottom(CARD_GAP));
            card.setBorder(JBUI.Borders.empty(CARD_TOP, CARD_LEFT, CARD_BOTTOM, CARD_RIGHT));

            time.setFont(Ui.smaller(time.getFont()));
            elapsed.setFont(Ui.monospace(smallerLabelSize()));

            JPanel leading = new JPanel();
            leading.setOpaque(false);
            leading.setLayout(new BoxLayout(leading, BoxLayout.X_AXIS));
            leading.add(dot);
            leading.add(Ui.hgap(5));
            leading.add(status);

            // 时刻与耗时凑成右对齐的一列：扫一眼就能比出哪次慢，不用在行首行尾来回跳
            JPanel tail = new JPanel();
            tail.setOpaque(false);
            tail.setLayout(new BoxLayout(tail, BoxLayout.X_AXIS));
            tail.add(time);
            tail.add(Ui.hgap(7));
            tail.add(elapsed);

            JPanel head = new JPanel(new BorderLayout(JBUI.scale(8), 0));
            head.setOpaque(false);
            head.setAlignmentX(LEFT_ALIGNMENT);
            head.add(leading, BorderLayout.WEST);
            head.add(tail, BorderLayout.EAST);

            // 按钮区只留高度：按钮本体是"画"上去的（CardPanel.paintAction），
            // 它不在组件树里，也就不会自己占地方。高度必须等于 ACTION_HEIGHT + ACTION_GAP，
            // 否则画出来的按钮会压到状态行上。
            JPanel actionSlot = new JPanel();
            actionSlot.setOpaque(false);
            actionSlot.setPreferredSize(new Dimension(0, JBUI.scale(ACTION_HEIGHT + ACTION_GAP)));

            card.add(head, BorderLayout.NORTH);
            card.add(actionSlot, BorderLayout.SOUTH);
            add(card, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ToolCallRecord> list,
                                                      ToolCallRecord value, int index,
                                                      boolean selected, boolean hasFocus) {
            Color foreground = selected ? list.getSelectionForeground() : list.getForeground();
            Color muted = selected ? foreground : Ui.MUTED;
            // 类型必须是 JBColor 而不是 Color：Ui.dot(...) 的单参重载只认 JBColor
            // （它要跟主题走），收窄成 Color 就找不到重载、编译不过。
            JBColor accent = statusColor(value);

            setBackground(list.getBackground());
            card.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            card.setAccent(accent);

            // 宽度取"看得见的那块"而不是控件自身宽度：列表被塞在视口里，出现纵向滚动条时
            // 控件会比可视区宽十几像素，按控件宽度算出来的文字会正好被滚动条压住最后一两个字
            Rectangle visible = list.getVisibleRect();
            int listWidth = visible.width > 0 ? visible.width : list.getWidth();
            int textWidth = Math.max(JBUI.scale(60), listWidth - JBUI.scale(CARD_CHROME));

            // 悬停 / 按下 / 能不能点 三件事都从列表那边读：事件只有它收得到（见 RecordList 说明）
            RecordList records = list instanceof RecordList ? (RecordList) list : null;
            boolean hovered = records != null && records.hoverIndex == index;
            boolean onAction = hovered && records.hoverOnAction;
            boolean pressed = onAction && records.pressedIndex == index;
            boolean enabled = callable.getAsBoolean();
            card.setHovered(hovered);
            card.setAction(I18n.t("hist.rerun"), actionWidth(list), Ui.smaller(list.getFont()),
                    onAction, pressed, enabled);

            dot.setIcon(Ui.dot(accent));
            status.setForeground(foreground);
            status.setText(fit(status, statusWord(value), textWidth));
            time.setForeground(muted);
            time.setText(value == null ? "" : timeLabel(value.timestamp));
            elapsed.setForeground(muted);
            elapsed.setText(elapsedLabel(value));

            return this;
        }

        /**
         * 只覆盖宽度，高度交给布局自己算。
         *
         * <p>宽度写死一个小值是有意的：{@code JList} 因此<b>不</b>跟随单元格内容的宽度，
         * 而是跟随视口宽度——否则长文字会把整张表撑宽，逼出一条横向滚动条。
         *
         * <p>高度则不再手工累加子组件高度：早先那么算漏掉了边框内边距，每格比内容矮 6px，
         * 最后一行文字的下半截被切成一条。交给 {@code BorderLayout} 算就不会漏。
         */
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(JBUI.scale(100), super.getPreferredSize().height);
        }

        private float smallerLabelSize() {
            Font base = UIManager.getFont("Label.font");
            float size = base == null ? 12f : base.getSize2D();
            return Math.max(9f, size - 1f);
        }
    }

    /**
     * 截到给定像素宽，放不下就在末尾加省略号。
     *
     * <p>算法搬到了 {@link Ui#truncateToWidth}——详情页那边的单行描述标签（{@link OneLineLabel}）
     * 用的是同一件事，两处各写一遍迟早会走样。
     */
    private static String fit(JBLabel label, String text, int width) {
        return Ui.truncateToWidth(label, text, width);
    }
}
