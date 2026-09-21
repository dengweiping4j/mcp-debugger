package com.tool4j.mcp.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.JBColor;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.model.McpResource;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.model.ToolCallRecord;
import com.tool4j.mcp.model.TransportType;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpClient;
import com.tool4j.mcp.protocol.McpConfigParser;
import com.tool4j.mcp.service.McpProjectService;
import com.tool4j.mcp.settings.LanguageSupport;
import com.tool4j.mcp.settings.McpCallHistory;
import com.tool4j.mcp.settings.McpSettings;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 工具窗口主面板：左边是"当前服务器 → 工具 / 资源 / 提示词"目录树，右边是选中项的详情与调用面板，
 * 底下是可折叠的报文日志。整体交互刻意贴着 Postman 的习惯：
 * 顶部选服务端并连接，左侧点条目，右侧填参数点调用，结果就在同一页看。
 *
 * <p>几条贯穿全局的约定：
 * <ul>
 *   <li>所有 MCP 调用都在后台线程跑（{@link Bg}），界面只在 EDT 上改。</li>
 *   <li>协议层的报文流从 {@link McpClient.Listener} 过来，直接进日志控制台；
 *       控制台自带"显示报文体"开关，长响应不会把界面撑爆。</li>
 *   <li>同一份配置对应同一个会话（会话缓存在 {@link McpProjectService}），
 *       所以关掉工具窗口再打开，之前拉到的工具列表还在，不用重连。</li>
 *   <li><b>布局按宽度自适应</b>：这个窗口绝大多数时候停靠在 PyCharm / IDEA 的右侧边栏，
 *       宽度只有三四百像素。宽了就左右分栏（目录 | 详情），窄了自动改成上下堆叠——
 *       否则目录会被挤成一条缝。工具栏也据此分成两行：下拉独占一行，动作按钮在下一行。</li>
 * </ul>
 */
public final class McpPanel extends SimpleToolWindowPanel implements Disposable {

    private static final String CARD_EMPTY = "empty";
    private static final String CARD_TOOL = "tool";
    private static final String CARD_RESOURCE = "resource";
    private static final String CARD_PROMPT = "prompt";

    /** 日志折叠起来时保存的比例，展开时还原。 */
    private static final float LOG_COLLAPSED_PROPORTION = 0.999f;

    /**
     * 窄布局断点（逻辑像素）。低于它就把"目录 / 详情"从左右改成上下。
     *
     * <p>定这个值的依据：右侧边栏默认只有 300~400px，而"目录 + 详情"并排至少需要
     * 目录 180px + 详情 300px，再算上分隔条，520px 以下并排必然有一边没法看。
     */
    private static final int NARROW_BREAKPOINT = 520;

    /** 目录 / 详情这一条分隔线的比例记忆键；判断"用户以前拖过没有"时也要用同一个键。 */
    private static final String SIDE_SPLIT_KEY = "McpDebugger.sideSplit";

    private final Project project;
    private final McpProjectService service;
    private final McpSettings settings = McpSettings.getInstance();

    // ---------------- 工具栏 ----------------

    private final ComboBox<Slot> serverCombo = new ComboBox<>();
    private final JBLabel statusDot = new JBLabel();
    private final JBLabel statusText = new JBLabel();

    /**
     * 一键切换界面语言的按钮。
     *
     * <p>放在状态条最右侧（EAST），而<b>不是</b>工具栏里：那一排是"操作这台服务器"的动作，
     * 5 个按钮在 300px 的侧边栏里已经很挤；语言是整块面板的属性，挨着状态文字更合适。
     *
     * <p>按钮上写的是"点一下会切到哪"，和工具栏里「连接 / 断开」的呈现方式一致。
     * 用普通 {@link JButton} 就行——平台上"缺图标就画问号"那条只针对工具栏里的 ActionButton。
     */
    private final JButton languageButton = new JButton();

    // ---------------- 开场引导页 ----------------

    /** 空态卡片上的三块文案。做成字段是因为切语言时要重贴，局部变量够不着。 */
    private final JBLabel emptyTitle = new JBLabel();
    private final JBLabel emptyHint = new JBLabel();
    private final JButton emptyAddButton = new JButton(I18n.t("panel.empty.add"), AllIcons.General.Add);
    private final JButton emptyImportButton =
            new JButton(I18n.t("panel.empty.import"), AllIcons.Actions.Download);

    // ---------------- 左侧目录 ----------------

    private final JBLabel leftTitle = new JBLabel();
    private final JBLabel leftSubtitle = Ui.hint(" ");
    private final DefaultMutableTreeNode treeRoot = new DefaultMutableTreeNode("root");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(treeRoot);
    private final Tree catalogTree = new Tree(treeModel);

    // ---------------- 右侧详情 ----------------

    private final CardLayout detailCards = new CardLayout();
    private final JPanel detailHost = new JPanel(detailCards);
    private final ToolDetailPanel toolDetail;
    private final ResourceDetailPanel resourceDetail;
    private final PromptDetailPanel promptDetail;

    // ---------------- 底部日志 ----------------

    private final LogConsole console = new LogConsole();
    private JBSplitter sideSplit;
    private JBSplitter bodySplit;
    // ---------------- 运行状态 ----------------

    /** 面板已释放；后台回调靠它判断还要不要碰界面。 */
    private volatile boolean disposed;
    /** 正在跑后台任务（连接 / 刷新 / 调用），用于状态栏与按钮可用性。 */
    private boolean busy;
    /** 重新填充下拉框时抑制选中事件，避免自触发连接。 */
    private boolean comboReloading;

    /**
     * 状态栏「进行中」那句的来源：<b>存键 + 变量，不存拼好的字符串</b>。
     *
     * <p>它由"正在连接 " + 服务器名拼出来，存成品就会在切换语言后停在旧语言里。
     */
    @Nullable
    private String stageKey;
    @Nullable
    private String stageArg;
    /** 协议层 {@code onStage()} 递上来的成品文案——它已经渲染过了，只能原样显示。 */
    @Nullable
    private String stageRaw;

    /** 工具栏动作组：切语言时要遍历它把每个动作的文案重贴一遍。 */
    private DefaultActionGroup toolbarGroup;
    private ActionToolbar actionToolbar;

    /**
     * 语言变更监听。<b>必须存成字段</b>：{@code I18n.removeListener(this::applyTexts)}
     * 里那个方法引用是另一个对象，remove 不掉——那样每次重开工具窗口都会漏一个订阅。
     */
    private final Runnable languageListener = this::applyTexts;
    /** 日志折叠前记下的比例；-1 表示还没折叠过（展开时就不要动用户拖出来的值）。 */
    private float expandedLogProportion = -1f;
    /** 当前是不是窄布局（目录在上、详情在下）。 */
    private boolean narrowLayout;
    /** 是否已经根据真实宽度定过一次布局；首次定的时候不要覆盖用户拖出来 / 持久化的比例。 */
    private boolean layoutSettled;

    /** 服务器 id → 我们挂上去的监听器，用于在切换/释放时精确摘掉，避免同一条报文被记两次。 */
    private final Map<String, McpClient.Listener> hooks = new HashMap<>();

    /**
     * 记录监听器实际挂在哪个 McpClient 实例上。
     *
     * <p>会话被关掉后 {@code McpProjectService.client()} 会重建一个新实例，此时旧的监听器
     * 还挂在旧实例上。只按 serverId 判断"挂过没有"会导致新实例永远收不到回调——
     * 控制台会整个安静下来。所以这里额外记住实例本身，实例变了就重挂。
     */
    private final Map<String, McpClient> hookedClients = new HashMap<>();

    public McpPanel(@NotNull Project project, @NotNull Disposable parent) {
        super(true);
        this.project = project;
        this.service = McpProjectService.getInstance(project);
        Disposer.register(parent, this);

        this.toolDetail = new ToolDetailPanel(project, this);
        this.resourceDetail = new ResourceDetailPanel(project, this);
        this.promptDetail = new PromptDetailPanel(project, this);
        this.toolDetail.setCallback(this::invokeTool);
        this.resourceDetail.setCallback(this::readResource);
        this.promptDetail.setCallback(this::getPrompt);
        // 日志面板标题栏的折叠箭头：边栏窄的时候，工具栏那一排按钮也可能被压掉，
        // 折叠日志得有个"就近"的入口
        this.console.setCollapseHandler(this::toggleLogVisibility);

        setToolbar(buildToolbar());
        setContent(buildBody());

        // 边栏宽度可以随时被拖动，宽度一变就重排（左右分栏 ↔ 上下堆叠）
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                applyResponsiveLayout();
            }
        });

        McpSettings.subscribe(this, this::reloadServers);
        reloadServers();

        // 语言变了只重贴文案，不重建面板：目录树的展开状态、分隔条比例、正编辑的 JSON
        // 都得留着。整棵树的级联从这一处发起（子面板自己不订阅）。
        I18n.addListener(languageListener);
        applyTexts();
    }

    // ==================================================================
    // 界面搭建
    // ==================================================================

    /**
     * 工具栏。<b>刻意分两行</b>：
     *
     * <pre>
     * 第一行  [ 服务器下拉框 ................ ]
     * 第二行  [连接][刷新][新建][管理▾][日志]   ● 已连接 · 12 个工具
     * </pre>
     *
     * <p>原来是一行里塞下拉框 + 12 个动作 + 状态文字，在右侧边栏（300~400px）里必然溢出，
     * 于是平台把按钮全压成"没有图标就画问号"的样子。现在：
     * <ul>
     *   <li>下拉框独占一行，宽度随边栏伸缩；</li>
     *   <li>动作按钮只留 5 个，全部带平台图标（工具栏是按图标渲染的，缺图标就是问号），
     *       编辑 / 删除 / 导入导出收进「管理」下拉；</li>
     *   <li>状态文字放在按钮右侧的剩余空间里，窄了会自动省略号，不会反过来挤压按钮。</li>
     * </ul>
     */
    private JComponent buildToolbar() {
        serverCombo.setRenderer(new SlotRenderer());
        serverCombo.setToolTipText(I18n.t("panel.combo.tooltip"));
        // 只压最小宽度：宽的时候让它铺满一行，窄的时候允许被压到 90px 而不是撑破边栏
        serverCombo.setMinimumSize(new Dimension(JBUI.scale(90),
                Math.max(JBUI.scale(24), serverCombo.getPreferredSize().height)));
        serverCombo.addActionListener(e -> onServerSelected());

        JPanel pickerRow = new JPanel(new BorderLayout());
        pickerRow.setOpaque(false);
        pickerRow.add(serverCombo, BorderLayout.CENTER);

        toolbarGroup = new DefaultActionGroup();
        toolbarGroup.add(new ConnectAction());
        toolbarGroup.add(new RefreshAction());
        toolbarGroup.add(new AddServerAction());
        toolbarGroup.add(new ManageGroup());
        toolbarGroup.add(new ToggleLogAction());

        actionToolbar = ActionManager.getInstance()
                .createActionToolbar("McpDebuggerToolWindow", toolbarGroup, true);
        actionToolbar.setTargetComponent(this);

        JPanel actionRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        actionRow.setOpaque(false);
        actionRow.add(actionToolbar.getComponent(), BorderLayout.WEST);
        actionRow.add(buildStatusStrip(), BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(2)));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(3, 4, 3, 4));
        panel.add(pickerRow, BorderLayout.NORTH);
        panel.add(actionRow, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 状态指示。放在 {@link BorderLayout#CENTER} 上是有意的：CENTER 拿到的是"剩下的宽度"，
     * 于是窄边栏里它是被省略号截断，而不是把左边的按钮挤出可视区。
     */
    private JComponent buildStatusStrip() {
        statusText.setFont(Ui.smaller(statusText.getFont()));
        JPanel strip = new JPanel(new BorderLayout(JBUI.scale(4), 0));
        strip.setOpaque(false);
        strip.add(statusDot, BorderLayout.WEST);
        strip.add(statusText, BorderLayout.CENTER);

        // 语言按钮固定在最右：状态文字在 CENTER 上被省略号截断，不会把它挤出去
        languageButton.setFocusable(false);
        languageButton.setMargin(JBUI.insets(0, 4, 0, 4));
        languageButton.addActionListener(e -> toggleLanguage());
        strip.add(languageButton, BorderLayout.EAST);
        return strip;
    }

    private JComponent buildBody() {
        detailHost.setOpaque(false);
        detailHost.add(buildEmptyCard(), CARD_EMPTY);
        detailHost.add(toolDetail, CARD_TOOL);
        detailHost.add(resourceDetail, CARD_RESOURCE);
        detailHost.add(promptDetail, CARD_PROMPT);

        sideSplit = new JBSplitter(false, 0.32f);
        sideSplit.setSplitterProportionKey(SIDE_SPLIT_KEY);
        sideSplit.setFirstComponent(buildLeftSide());
        sideSplit.setSecondComponent(detailHost);
        // 这里**不要** setDividerWidth：平台默认 7px，是鼠标能抓住的宽度。
        // 之前设成 2px，分割条的整个"热区"就只有 2px 宽，等于拖不动。
        // 也不要 setHonorComponentsMinimumSize(true)：一旦某个子面板的最小高度大于可用高度，
        // 比例会被死死钳住，同样是"拖了没反应"。

        bodySplit = new JBSplitter(true, 0.72f);
        bodySplit.setSplitterProportionKey("McpDebugger.bodySplit");
        bodySplit.setFirstComponent(sideSplit);
        bodySplit.setSecondComponent(console);

        applyLogVisibility();
        showIdle();
        return bodySplit;
    }

    private JComponent buildLeftSide() {
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        leftTitle.setFont(Ui.bold(leftTitle.getFont()));
        header.add(leftTitle);
        header.add(Ui.vgap(2));
        header.add(leftSubtitle);
        header.add(Ui.vgap(5));
        header.add(Ui.separator());

        catalogTree.setRootVisible(false);
        catalogTree.setShowsRootHandles(true);
        catalogTree.setCellRenderer(new CatalogRenderer());
        catalogTree.getEmptyText().setText(I18n.t("panel.tree.empty"));
        catalogTree.addTreeSelectionListener(e -> onTreeSelection());

        JBScrollPane scroll = new JBScrollPane(catalogTree);
        scroll.setBorder(JBUI.Borders.empty());

        JPanel panel = new JPanel(new BorderLayout(0, JBUI.scale(4)));
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(6, 8, 4, 6));
        panel.add(header, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /**
     * 开场引导页：<b>一个服务器都没配</b>时才出现。
     *
     * <p>已经有服务器但没连上时不走这里，见 {@link #showIdle()}——那张页面要留着请求头入口。
     *
     * <p>文案刻意写成短行 + HTML 定宽 div：右侧边栏只有两三百像素，
     * 不限制宽度的话这段话会被撑成一条横线（甚至把面板顶宽）。
     *
     * <p>顺便把"第一次怎么开始"做成两个真按钮——新用户在这个空页面上最需要的就是这个。
     */
    private JComponent buildEmptyCard() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setOpaque(false);

        JPanel content = new JPanel();
        content.setOpaque(false);
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));

        emptyTitle.setFont(Ui.bold(emptyTitle.getFont()));
        emptyTitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyHint.setForeground(Ui.MUTED);
        emptyHint.setFont(Ui.smaller(emptyHint.getFont()));
        emptyHint.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyAddButton.addActionListener(e -> addServer());
        emptyAddButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        emptyImportButton.addActionListener(e -> importFromDialog(ImportConfigDialog.forFiles(project)));
        emptyImportButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        content.add(emptyTitle);
        content.add(Ui.vgap(6));
        content.add(emptyHint);
        content.add(Ui.vgap(10));
        content.add(emptyAddButton);
        content.add(Ui.vgap(6));
        content.add(emptyImportButton);
        panel.add(content);
        return panel;
    }

    // ==================================================================
    // 服务器下拉框
    // ==================================================================

    /**
     * 下拉框里的一项。用一层包装而不是直接放 {@link McpServerConfig}，
     * 是为了能在"一个服务器都没有"时放一个不可选中的占位项，而不是留个空白框。
     */
    private static final class Slot {
        final McpServerConfig config;

        Slot(McpServerConfig config) {
            this.config = config;
        }

        @Override
        public String toString() {
            if (config == null) {
                return I18n.t("panel.combo.empty");
            }
            TransportType transport = config.getTransport();
            return config.getDisplayName() + "  ·  " + (transport == null ? "?" : transport.getDisplayName());
        }
    }

    private static final class SlotRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof Slot slot) {
                setText(slot.toString());
                if (slot.config == null) {
                    setToolTipText(null);
                    return this;
                }
                setToolTipText(slot.config.getEndpointSummary());
                // 上次连接失败过就标一下——配了十几个服务器时，一眼能看出哪个是坏的
                if (Boolean.FALSE.equals(slot.config.getLastConnectOk())) {
                    // 标记本身（三个空格 + ⚠）是符号，词条只放"那几个字"
                    setText(slot + "   ⚠ " + I18n.t("panel.combo.lastFailed"));
                }
            }
            return this;
        }
    }

    /** 重新填充下拉框（新建 / 编辑 / 删除 / 外部变更之后都走这里）。 */
    private void reloadServers() {
        String wanted = settings.getSelectedServerId();
        comboReloading = true;
        try {
            DefaultComboBoxModel<Slot> model = new DefaultComboBoxModel<>();
            Slot match = null;
            for (McpServerConfig config : settings.getServers()) {
                if (!config.isEnabled()) {
                    continue;
                }
                Slot slot = new Slot(config);
                model.addElement(slot);
                if (config.getId().equals(wanted)) {
                    match = slot;
                }
            }
            if (model.getSize() == 0) {
                model.addElement(new Slot(null));
            }
            serverCombo.setModel(model);
            serverCombo.setSelectedItem(match != null ? match : model.getElementAt(0));
        } finally {
            comboReloading = false;
        }

        McpServerConfig config = currentConfig();
        if (config != null) {
            settings.setSelectedServerId(config.getId());
        }
        rebuildTree();
        if (config != null && config.isAutoConnect() && !service.isConnected(config.getId())) {
            connect(config, false);
        }
    }

    private void onServerSelected() {
        if (comboReloading) {
            return;
        }
        McpServerConfig config = currentConfig();
        settings.setSelectedServerId(config == null ? "" : config.getId());
        rebuildTree();

        if (config != null && config.isAutoConnect() && !service.isConnected(config.getId())) {
            connect(config, false);
        }
    }

    @Nullable
    private McpServerConfig currentConfig() {
        Object selected = serverCombo.getSelectedItem();
        return selected instanceof Slot slot ? slot.config : null;
    }

    /** 当前选中服务器上已经连上的会话，没连上就返回 null。 */
    @Nullable
    private McpClient connectedClient() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            return null;
        }
        McpClient client = service.peek(config.getId());
        return client != null && client.isConnected() ? client : null;
    }

    // ==================================================================
    // 连接 / 刷新
    // ==================================================================

    private void toggleConnection() {
        McpServerConfig config = currentConfig();
        if (config == null || busy) {
            return;
        }
        if (service.isConnected(config.getId())) {
            service.close(config.getId());
            console.appendInfo(I18n.t("panel.log.disconnected", config.getDisplayName()));
            rebuildTree();
        } else {
            connect(config, true);
        }
    }

    private void connect(@NotNull McpServerConfig config, boolean userInitiated) {
        if (busy) {
            return;
        }
        McpClient client = service.client(config);
        hook(config.getId(), client);

        busy = true;
        setStage("panel.stage.connecting", config.getDisplayName());
        console.appendInfo(I18n.t("panel.log.connecting", config.getDisplayName(),
                config.getTransport().getDisplayName(), config.getEndpointSummary()));
        if (userInitiated) {
            showIdle();
        }

        Bg.run(project, I18n.t("panel.task.connect", config.getDisplayName()), true,
                () -> {
                    client.connect();
                    client.refreshAll();
                    return client;
                },
                connected -> {
                    busy = false;
                    clearStage();
                    config.setLastConnectOk(true);
                    rebuildTree();
                    selectFirstLeaf();
                    console.appendInfo(I18n.t("panel.log.connected", config.getDisplayName(),
                            connected.getServerInfo() == null ? I18n.t("panel.log.noServerInfo")
                                    : connected.getServerInfo().getSummary()));
                },
                error -> {
                    busy = false;
                    clearStage();
                    config.setLastConnectOk(false);
                    console.appendError(I18n.t("panel.log.connectFailed", McpException.describe(error)));
                    rebuildTree();
                });
    }

    private void refreshCatalog() {
        McpClient client = connectedClient();
        McpServerConfig config = currentConfig();
        if (client == null || config == null || busy) {
            return;
        }
        busy = true;
        setStage("panel.stage.refreshing", null);
        Bg.run(project, I18n.t("panel.task.refresh"), false,
                () -> {
                    client.refreshAll();
                    return null;
                },
                ignored -> {
                    busy = false;
                    clearStage();
                    rebuildTree();
                    console.appendInfo(I18n.t("panel.log.catalogRefreshed"));
                },
                error -> {
                    busy = false;
                    clearStage();
                    updateStatus();
                    console.appendError(I18n.t("panel.log.refreshFailed", McpException.describe(error)));
                });
    }

    /**
     * 给会话挂上报文监听。同一份配置只挂一次——工具窗口关了又开的时候，
     * 会话还活着（缓存在 service 里），重复挂会让同一条报文记两遍。
     */
    private void hook(@NotNull String serverId, @NotNull McpClient client) {
        McpClient.Listener previous = hooks.get(serverId);
        if (previous != null) {
            if (hookedClients.get(serverId) == client) {
                return; // 已经挂在这个实例上了
            }
            // 换了实例（旧会话被关掉后重建的），把旧监听摘掉再重挂
            McpClient old = hookedClients.get(serverId);
            if (old != null) {
                old.removeListener(previous);
            }
            hooks.remove(serverId);
            hookedClients.remove(serverId);
        }
        McpClient.Listener listener = new McpClient.Listener() {
            @Override
            public void onTraffic(boolean outbound, JsonObject message) {
                onEdt(() -> console.appendTraffic(outbound, message));
            }

            @Override
            public void onStderr(String text) {
                onEdt(() -> console.appendNotice(text));
            }

            @Override
            public void onLog(String text) {
                onEdt(() -> console.appendInfo(text));
            }

            @Override
            public void onStage(String value) {
                onEdt(() -> setStageRaw(value));
            }

            @Override
            public void onServerNotification(String method, JsonElement params) {
                onEdt(() -> console.appendInfo(I18n.t("panel.log.serverNotification", method)));
                // 服务端主动说目录变了就顺手同步一下，省得用户自己点刷新
                if (method != null && method.endsWith("list_changed")) {
                    onEdt(McpPanel.this::refreshCatalog);
                }
            }

            @Override
            public void onDisconnected(String reason) {
                onEdt(() -> {
                    console.appendError(I18n.t("panel.log.connectionLost",
                            reason == null ? I18n.t("panel.log.unknownReason") : reason));
                    clearStage();
                    busy = false;
                    rebuildTree();
                });
            }
        };
        hooks.put(serverId, listener);
        hookedClients.put(serverId, client);
        client.addListener(listener);
    }

    // ==================================================================
    // 目录树
    // ==================================================================

    /** 树里的分组节点（工具 / 资源 / 资源模板 / 提示词）。 */
    private static final class Group {
        final String kind;
        final int count;

        Group(String kind, int count) {
            this.kind = kind;
            this.count = count;
        }

        /**
         * 标题<b>按 kind 现取</b>，而不是构造时存下来。
         *
         * <p>这样切语言时只要 {@code treeModel.nodeChanged(group)} 让这几行重画，
         * 不必重建模型——重建会把用户刚展开的树收回去。
         */
        String title() {
            return I18n.t("panel.group." + kind);
        }

        @Override
        public String toString() {
            return title();
        }
    }

    private void rebuildTree() {
        treeRoot.removeAllChildren();
        McpServerConfig config = currentConfig();
        // 「请求头」页签跟着当前服务器走：stdio 没有这一页，http / sse 才插到 JSON 与定义之间。
        // 这个重排很频繁（连接、刷新、切工具都会走到这），所以面板内部对"同一个配置实例"
        // 只刷新提示文字，不会把用户正敲到一半的内容和光标打回去。
        toolDetail.showConfig(config);
        McpClient client = config == null ? null : service.peek(config.getId());
        boolean connected = client != null && client.isConnected();

        if (connected) {
            addGroup(new Group("tool", client.getTools().size()), client.getTools());
            addGroup(new Group("resource", client.getResources().size()), client.getResources());
            addGroup(new Group("template", client.getResourceTemplates().size()),
                    client.getResourceTemplates());
            addGroup(new Group("prompt", client.getPrompts().size()), client.getPrompts());
        }
        treeModel.reload();

        if (connected) {
            expandAll();
        } else {
            showIdle();
        }

        updateHeader(connected, client);
        updateStatus();
    }

    /**
     * 没有条目可显示时给哪张卡片。
     *
     * <p>一个服务器都没配 → 开场引导页（新建 / 导入），这是新用户唯一该看到的东西。
     *
     * <p>已经有服务器、只是没连上（或没选中条目）→ 直接给工具详情面板的空态。
     * 这不是偷懒：<b>请求头页签就住在那个面板里</b>，而带鉴权的新服务器恰恰是在
     * "还没连上、树是空的"这一刻需要填 token 的（没有 token 就永远连不上）。
     * 如果这时换成另一张欢迎页，那一页上没有任何请求头入口，首次配置就会死锁。
     */
    private void showIdle() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            showCard(CARD_EMPTY);
            return;
        }
        toolDetail.showIdleKey(service.isConnected(config.getId())
                ? "tool.idle.connected"
                : "tool.idle.disconnected");
        showCard(CARD_TOOL);
    }

    /** 空分组不显示，免得列表里一堆 0。 */
    private void addGroup(@NotNull Group group, @NotNull List<?> items) {
        if (items.isEmpty()) {
            return;
        }
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(group);
        for (Object item : items) {
            node.add(new DefaultMutableTreeNode(item));
        }
        treeRoot.add(node);
    }

    private void expandAll() {
        // 展开会让行数变多，所以每次重新取 getRowCount()，不用缓存
        for (int row = 0; row < catalogTree.getRowCount(); row++) {
            catalogTree.expandRow(row);
        }
    }

    private void selectFirstLeaf() {
        if (treeRoot.getChildCount() == 0) {
            return;
        }
        DefaultMutableTreeNode firstGroup = (DefaultMutableTreeNode) treeRoot.getChildAt(0);
        if (firstGroup.getChildCount() == 0) {
            return;
        }
        catalogTree.setSelectionPath(new TreePath(
                ((DefaultMutableTreeNode) firstGroup.getChildAt(0)).getPath()));
    }

    private void onTreeSelection() {
        TreePath path = catalogTree.getSelectionPath();
        Object user = null;
        if (path != null && path.getLastPathComponent() instanceof DefaultMutableTreeNode node) {
            user = node.getUserObject();
        }
        if (user instanceof McpTool tool) {
            toolDetail.showTool(tool);
            showCard(CARD_TOOL);
        } else if (user instanceof McpResource resource) {
            resourceDetail.showResource(resource);
            showCard(CARD_RESOURCE);
        } else if (user instanceof McpPrompt prompt) {
            promptDetail.showPrompt(prompt);
            showCard(CARD_PROMPT);
        } else {
            showIdle();
        }
    }

    private void showCard(String card) {
        detailCards.show(detailHost, card);
    }

    private void updateHeader(boolean connected, @Nullable McpClient client) {
        McpServerConfig config = currentConfig();
        if (config == null) {
            leftTitle.setText(I18n.t("panel.left.none"));
            leftTitle.setToolTipText(null);
            setSubtitle(I18n.t("panel.left.noneHint"));
            return;
        }
        leftTitle.setText(config.getDisplayName());
        leftTitle.setToolTipText(config.getEndpointSummary());
        if (!connected) {
            setSubtitle(config.getEndpointSummary());
            return;
        }
        int tools = client.getTools().size();
        int resources = client.getResources().size() + client.getResourceTemplates().size();
        int prompts = client.getPrompts().size();
        List<String> bits = new ArrayList<>(4);
        bits.add(I18n.t("panel.summary.tools", tools));
        if (resources > 0) {
            bits.add(I18n.t("panel.summary.resources", resources));
        }
        if (prompts > 0) {
            bits.add(I18n.t("panel.summary.prompts", prompts));
        }
        String protocol = client.getServerInfo() == null ? null : client.getServerInfo().getProtocolVersion();
        String summary = String.join(" · ", bits)
                + (protocol == null || protocol.isBlank() ? "" : " · MCP " + protocol);
        setSubtitle(summary);
    }

    /**
     * 只留一行副标题：竖排（窄边栏）时目录区本来就只有两三百像素高，
     * 之前"数量一行 + 协议一行"要吃掉两行。放不下就省略号，完整内容走 tooltip。
     */
    private void setSubtitle(String text) {
        leftSubtitle.setText(text);
        leftSubtitle.setToolTipText(text);
    }

    private void setStage(@Nullable String key, @Nullable String arg) {
        stageKey = key;
        stageArg = arg;
        stageRaw = null;
        updateStatus();
    }

    /** 协议层递上来的成品文案（见 {@link #stageRaw}）。 */
    private void setStageRaw(@Nullable String text) {
        stageKey = null;
        stageArg = null;
        stageRaw = text == null || text.isBlank() ? null : text;
        updateStatus();
    }

    private void clearStage() {
        stageKey = null;
        stageArg = null;
        stageRaw = null;
    }

    @Nullable
    private String stageText() {
        if (stageKey != null) {
            return stageArg == null ? I18n.t(stageKey) : I18n.t(stageKey, stageArg);
        }
        return stageRaw;
    }

    private void updateStatus() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            setStatus(Ui.MUTED, I18n.t("panel.status.none"));
            return;
        }
        if (busy) {
            String current = stageText();
            setStatus(Ui.WARN, current == null ? I18n.t("panel.status.busy") : current);
            return;
        }
        if (!service.isConnected(config.getId())) {
            setStatus(Ui.MUTED, I18n.t("panel.status.disconnected"));
            return;
        }
        McpClient client = service.peek(config.getId());
        int tools = client == null ? 0 : client.getTools().size();
        setStatus(Ui.OK, I18n.t("panel.status.connected", tools));
    }

    /** 状态点 + 文字。文字会被窄边栏截断，所以整段都塞进 tooltip。 */
    private void setStatus(JBColor color, String text) {
        statusDot.setIcon(Ui.dot(color));
        statusText.setForeground(color);
        statusText.setText(text);
        statusText.setToolTipText(text);
    }

    // ==================================================================
    // 详情面板回调
    // ==================================================================

    private void invokeTool(@NotNull McpTool tool, @NotNull JsonObject arguments) {
        McpClient client = connectedClient();
        if (client == null) {
            toolDetail.showInvokeError(I18n.t("panel.err.noConnection"));
            return;
        }
        McpServerConfig config = currentConfig();
        // 留一份快照：历史记的必须是"这次真的发出去了什么"，编辑器里那份接下来怎么变都不该影响它
        JsonObject sent = arguments.deepCopy();
        long started = System.currentTimeMillis();
        Bg.run(project, I18n.t("panel.task.invokeTool", tool.getName()), true,
                () -> client.callTool(tool.getName(), sent),
                result -> {
                    toolDetail.showResult(result);
                    rememberCall(config, tool, sent, result, null, started);
                },
                error -> {
                    String message = McpException.describe(error);
                    toolDetail.showInvokeError(message);
                    rememberCall(config, tool, sent, null, message, started);
                });
    }

    /**
     * 把这次调用记进历史（「历史」页签的数据来源）。
     *
     * <p>成功、服务端报错、本地失败三种都记：调试时最常做的一件事就是"把上一次的入参原样重发"，
     * 而<b>失败那一次的入参往往才是要复用的那一份</b>——只要改掉报错指出的那个值。
     *
     * <p>不记 {@code resources/read} 与 {@code prompts/get}：它们没有入参 JSON
     * （资源读的是一个 URI），「历史」页签帮不上忙，混进来只会让列表失去一致性。
     */
    private void rememberCall(@Nullable McpServerConfig config, @NotNull McpTool tool,
                              @NotNull JsonObject arguments, @Nullable McpCallResult result,
                              @Nullable String failure, long startedAt) {
        ToolCallRecord record = new ToolCallRecord();
        record.serverId = config == null ? "" : config.getId();
        record.toolName = tool.getName();
        record.arguments = JsonUtil.compact(arguments);
        record.timestamp = System.currentTimeMillis();
        if (result != null) {
            record.elapsedMillis = result.getElapsedMillis();
            if (result.getError() != null) {
                record.status = ToolCallRecord.Status.FAILED;
                record.note = abbreviate(result.getError());
            } else if (!result.isSuccess()) {
                record.status = ToolCallRecord.Status.TOOL_ERROR;
                record.note = I18n.t("panel.note.toolError");
            } else {
                record.status = ToolCallRecord.Status.OK;
            }
        } else {
            // 本地失败没有"往返耗时"，用挂钟时间近似——它是从点下「调用」到拿到失败的总时长
            record.status = ToolCallRecord.Status.FAILED;
            record.elapsedMillis = Math.max(0, System.currentTimeMillis() - startedAt);
            record.note = abbreviate(failure);
        }
        McpCallHistory.getInstance().record(record);
    }

    /** 失败原因存进历史前先压成一行并截断：它只服务列表项的 tooltip，不值得占满配置文件。 */
    private static String abbreviate(@Nullable String text) {
        String oneLine = JsonUtil.oneLine(text);
        return oneLine.length() <= McpCallHistory.NOTE_LIMIT
                ? oneLine
                : oneLine.substring(0, McpCallHistory.NOTE_LIMIT) + "…";
    }

    private void readResource(@NotNull McpResource resource, @NotNull String uri) {
        McpClient client = connectedClient();
        if (client == null) {
            resourceDetail.showError(I18n.t("panel.err.noConnection"));
            return;
        }
        Bg.run(project, I18n.t("panel.task.readResource", uri), true,
                () -> client.readResource(uri),
                resourceDetail::showResult,
                error -> resourceDetail.showError(McpException.describe(error)));
    }

    private void getPrompt(@NotNull McpPrompt prompt, @NotNull JsonObject arguments) {
        McpClient client = connectedClient();
        if (client == null) {
            promptDetail.showError(I18n.t("panel.err.noConnection"));
            return;
        }
        Bg.run(project, I18n.t("panel.task.getPrompt", prompt.getName()), true,
                () -> client.getPrompt(prompt.getName(), arguments),
                promptDetail::showResult,
                error -> promptDetail.showError(McpException.describe(error)));
    }

    // ==================================================================
    // 服务器增删改
    // ==================================================================

    private void addServer() {
        ServerEditDialog dialog = new ServerEditDialog(project, null);
        if (!dialog.showAndGet()) {
            return;
        }
        McpServerConfig config = dialog.buildConfig();
        settings.add(config);
        settings.setSelectedServerId(config.getId());
        reloadServers();
        connect(config, true);
    }

    private void editServer() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            return;
        }
        ServerEditDialog dialog = new ServerEditDialog(project, config);
        if (!dialog.showAndGet()) {
            return;
        }
        boolean wasConnected = service.isConnected(config.getId());
        if (wasConnected) {
            // 配置变了，旧会话不能再用（子进程还是按老命令行拉起来的）
            service.close(config.getId());
        }
        dialog.applyTo(config);
        settings.notifyChanged();
        reloadServers();
        if (wasConnected) {
            connect(config, true);
        }
    }

    private void removeServer() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            return;
        }
        // 按钮文案自己给，理由同 CallHistoryPanel.clearAll()
        int answer = Messages.showDialog(project,
                I18n.t("panel.msg.delete.text", config.getDisplayName()),
                I18n.t("panel.msg.delete.title"),
                new String[]{I18n.t("ui.yes"), I18n.t("ui.no")}, 0, Messages.getQuestionIcon());
        if (answer != 0) {
            return;
        }
        service.close(config.getId());
        unhook(config.getId());
        settings.remove(config);
        // 历史按 serverId 归类；配置没了它就再也选不中、也删不掉，一起清掉
        McpCallHistory.getInstance().forgetServer(config.getId());
        settings.setSelectedServerId("");
        reloadServers();
        console.appendInfo(I18n.t("panel.log.deleted", config.getDisplayName()));
    }

    // ==================================================================
    // 导入 / 导出
    // ==================================================================

    private void importFromDialog(@NotNull ImportConfigDialog dialog) {
        List<McpServerConfig> parsed = dialog.showAndGetServers();
        if (parsed == null || parsed.isEmpty()) {
            return;
        }
        int added = 0;
        int skipped = 0;
        McpServerConfig first = null;
        for (McpServerConfig incoming : parsed) {
            if (isDuplicate(incoming)) {
                skipped++;
                continue;
            }
            incoming.setName(settings.uniqueName(incoming.getName()));
            settings.add(incoming);
            added++;
            if (first == null) {
                first = incoming;
            }
        }
        if (first != null) {
            settings.setSelectedServerId(first.getId());
        }
        reloadServers();
        console.appendInfo(I18n.t("panel.log.imported", added,
                skipped > 0 ? I18n.t("panel.log.importedSkipped", skipped) : ""));
        if (added == 0) {
            Messages.showDialog(project, I18n.t("panel.msg.import.none"),
                    I18n.t("panel.msg.import.title"),
                    new String[]{I18n.t("ui.ok")}, 0, Messages.getInformationIcon());
        }
    }

    /** 同名且连接方式完全一样的才算重复，避免把"同名但其实改了参数"的配置默默吞掉。 */
    private boolean isDuplicate(@NotNull McpServerConfig incoming) {
        for (McpServerConfig existing : settings.getServers()) {
            if (!existing.getDisplayName().equalsIgnoreCase(incoming.getDisplayName())) {
                continue;
            }
            if (existing.getTransport() == incoming.getTransport()
                    && existing.getEndpointSummary().equals(incoming.getEndpointSummary())) {
                return true;
            }
        }
        return false;
    }

    private void exportToClipboard() {
        List<McpServerConfig> servers = new ArrayList<>(settings.getServers());
        if (servers.isEmpty()) {
            Messages.showDialog(project, I18n.t("panel.msg.export.none"),
                    I18n.t("panel.msg.export.title"),
                    new String[]{I18n.t("ui.ok")}, 0, Messages.getInformationIcon());
            return;
        }
        Ui.copyToClipboard(McpConfigParser.toPrettyJson(servers));
        console.appendInfo(I18n.t("panel.log.exported", servers.size()));
    }

    // ==================================================================
    // 日志显示
    // ==================================================================

    private void applyLogVisibility() {
        if (settings.isLogVisible()) {
            bodySplit.getDivider().setVisible(true);
            console.setVisible(true);
            if (expandedLogProportion > 0.05f && expandedLogProportion < LOG_COLLAPSED_PROPORTION) {
                bodySplit.setProportion(expandedLogProportion);
            }
            // 没记过值就什么都不做：Splitter 自己会从 splitterProportionKey 里读回上次拖出来的比例
        } else {
            float current = bodySplit.getProportion();
            // 只有当前是个"展开态"的合理比例才记下来，否则保留上一次记的值
            if (current > 0.05f && current < LOG_COLLAPSED_PROPORTION) {
                expandedLogProportion = current;
            }
            // 两道保险：隐藏子组件（Splitter 会把它排成 0 高），再把比例压到底
            console.setVisible(false);
            bodySplit.getDivider().setVisible(false);
            bodySplit.setProportion(LOG_COLLAPSED_PROPORTION);
        }
        bodySplit.revalidate();
        bodySplit.repaint();
    }

    /** 折叠 / 展开底部日志（工具栏开关与日志面板标题栏的小箭头共用）。 */
    private void toggleLogVisibility() {
        settings.setLogVisible(!settings.isLogVisible());
        applyLogVisibility();
    }

    // ==================================================================
    // 语言
    // ==================================================================

    /**
     * 一键切换界面语言（中 ↔ 英）。
     *
     * <p>{@link LanguageSupport#toggle()} 里会 {@code I18n.setLocale(...)}，那是<b>同步广播</b>：
     * 我们这个调用还没返回，整棵界面的 {@code applyTexts()} 就已经跑完了。所以下面记日志时
     * 读到的已经是切换后的语言，那句话本身就是新语言的。
     *
     * <p>刻意不做成"中 → 英 → 跟随 IDE"三态循环：自动是"复位"而不是第三种语言，
     * 混进循环里会让用户猜不到下一次点击变成什么。复位入口在「管理」菜单里。
     */
    private void toggleLanguage() {
        LanguageSupport.toggle();
        logLanguageChange();
    }

    /** 把显式的语言偏好清回"跟随 IDE"。 */
    private void followIdeLanguage() {
        LanguageSupport.setPreference(LanguageSupport.AUTO);
        logLanguageChange();
    }

    private void logLanguageChange() {
        console.appendInfo(I18n.t("lang.switched",
                I18n.t("lang.name." + I18n.getLanguageTag())));
    }

    /** 语言按钮：字面写"点一下会切到哪"，tooltip 说清当前是什么、会变成什么。 */
    private void refreshLanguageButton() {
        boolean chinese = I18n.ZH.equals(I18n.getLanguageTag());
        languageButton.setText(I18n.t(chinese ? "lang.code.en" : "lang.code.zh"));
        languageButton.setToolTipText(I18n.t("lang.action.desc",
                I18n.t(chinese ? "lang.name.zh" : "lang.name.en"),
                I18n.t(chinese ? "lang.name.en" : "lang.name.zh")));
    }

    /**
     * 按当前宽度决定"目录 / 详情"是并排还是上下。
     *
     * <p>只在真的跨过断点、或者还没定过布局时才动 {@link #sideSplit}——每帧都重设比例会把
     * 用户刚刚拖出来的位置冲掉。
     */
    private void applyResponsiveLayout() {
        int width = getWidth();
        if (width <= 0) {
            return;
        }
        boolean narrow = width < JBUI.scale(NARROW_BREAKPOINT);
        if (layoutSettled && narrow == narrowLayout) {
            return;
        }
        boolean first = !layoutSettled;
        layoutSettled = true;
        narrowLayout = narrow;

        sideSplit.setOrientation(narrow);
        // 比例怎么给：跨过断点是"换了一套含义"（0.32 横过来是目录占三成宽，竖过去就变成只占三成高了），
        // 必须重给；首次定布局则优先沿用 JBSplitter 读回来的持久化值——除非压根没存过，
        // 那竖排就给 0.42，目录高一点更好用。
        boolean saved = PropertiesComponent.getInstance().getValue(SIDE_SPLIT_KEY) != null;
        if (!first || (narrow && !saved)) {
            sideSplit.setProportion(narrow ? 0.42f : 0.32f);
        }
        sideSplit.revalidate();
        sideSplit.repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        // JBSplitter 是在 addNotify 时才去读持久化的分隔比例的，会盖掉构造期设的折叠态，
        // 所以这里补一次。只补折叠方向：展开方向让它用用户拖出来的值更好。
        if (bodySplit != null && !settings.isLogVisible()) {
            applyLogVisibility();
        }
        // 同理：第一次拿到真实宽度时再定一次布局（构造期宽度还是 0）
        applyResponsiveLayout();
    }

    private void onEdt(@NotNull Runnable runnable) {
        ApplicationManager.getApplication().invokeLater(() -> {
            if (!disposed) {
                runnable.run();
            }
        });
    }

    @Override
    public void dispose() {
        I18n.removeListener(languageListener);
        disposed = true;
        for (String serverId : new HashSet<>(hooks.keySet())) {
            unhook(serverId);
        }
        hooks.clear();
        hookedClients.clear();
        // 真实会话留在 service 里：关掉工具窗口不该断连，重开还要看到原来的工具列表
    }

    /** 把某个 serverId 上的报文监听摘掉（只动我们这一份，不影响会话本身）。 */
    private void unhook(@NotNull String serverId) {
        McpClient.Listener listener = hooks.remove(serverId);
        McpClient client = hookedClients.remove(serverId);
        if (listener != null && client != null) {
            client.removeListener(listener);
        }
    }

    // ==================================================================
    // 工具栏动作
    // ==================================================================

    /**
     * 所有动作都读 Swing 状态，所以 update 必须跑在 EDT 上。
     *
     * <p><b>每个动作都必须带图标。</b>工具栏里的按钮是按图标渲染的，平台在
     * {@code ActionButton} 里对"图标为空"的处理是拿 {@code AllIcons.Toolbar.Unknown} 顶上——
     * 写没写 text 都一样，界面上就是一排问号（这排问号就是这么来的）。
     * text 仍然要写：它是按钮的 tooltip 和无障碍名称。
     */
    /** 文案能跟着界面语言重贴的动作。 */
    private interface Relocalizable {
        void retranslate();
    }

    /**
     * 工具栏动作的基类：<b>传的是文案键而不是文案</b>，这样切语言时能重贴。
     *
     * <p>重贴的是 {@code getTemplatePresentation()}——按钮的 tooltip 与无障碍名读的就是它。
     * 重贴之后要调一次 {@code ActionToolbar.updateActionsImmediately()}，否则要等下一次
     * 鼠标动到工具栏才刷新。
     */
    private abstract class PanelAction extends DumbAwareAction implements Relocalizable {
        private final String textKey;
        private final String descKey;

        PanelAction(@NotNull String textKey, @NotNull String descKey, @NotNull Icon icon) {
            super(I18n.t(textKey), I18n.t(descKey), icon);
            this.textKey = textKey;
            this.descKey = descKey;
        }

        @Override
        public void retranslate() {
            getTemplatePresentation().setText(I18n.t(textKey));
            getTemplatePresentation().setDescription(I18n.t(descKey));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class ConnectAction extends PanelAction {
        ConnectAction() {
            super("panel.action.connect", "panel.action.connect.on", AllIcons.Actions.Execute);
        }

        @Override
        public void retranslate() {
            boolean connected = currentConfig() != null
                    && service.isConnected(currentConfig().getId());
            getTemplatePresentation().setText(I18n.t(connected
                    ? "panel.action.disconnect" : "panel.action.connect"));
            getTemplatePresentation().setIcon(connected
                    ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
            getTemplatePresentation().setDescription(I18n.t(connected
                    ? "panel.action.connect.off" : "panel.action.connect.on"));
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            McpServerConfig config = currentConfig();
            boolean connected = config != null && service.isConnected(config.getId());
            e.getPresentation().setEnabled(config != null && !busy);
            e.getPresentation().setText(I18n.t(connected
                    ? "panel.action.disconnect" : "panel.action.connect"));
            e.getPresentation().setIcon(connected ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
            e.getPresentation().setDescription(I18n.t(connected
                    ? "panel.action.connect.off"
                    : "panel.action.connect.on"));
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            toggleConnection();
        }
    }

    private final class RefreshAction extends PanelAction {
        RefreshAction() {
            super("panel.action.refresh", "panel.action.refresh.desc", AllIcons.Actions.Refresh);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(connectedClient() != null && !busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            refreshCatalog();
        }
    }

    private final class AddServerAction extends PanelAction {
        AddServerAction() {
            super("panel.action.add", "panel.action.add.desc", AllIcons.General.Add);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            addServer();
        }
    }

    /**
     * 「管理」下拉：编辑 / 删除服务器 + 配置导入导出。
     *
     * <p>这些都不是高频操作，摊在工具栏上只会把主按钮挤没——右侧边栏只有三百多像素宽的时候
     * 尤其明显。收进下拉后，工具栏从 12 个按钮降到 5 个。
     */
    private final class ManageGroup extends DefaultActionGroup implements Relocalizable {
        ManageGroup() {
            super(I18n.t("panel.action.manage"), true);
            getTemplatePresentation().setIcon(AllIcons.General.GearPlain);
            getTemplatePresentation().setDescription(I18n.t("panel.action.manage.desc"));
            add(new EditServerAction());
            add(new RemoveServerAction());
            addSeparator();
            add(new ImportFromFileAction());
            add(new ImportFromPasteAction());
            add(new ExportAction());
            addSeparator();
            add(new FollowIdeLanguageAction());
        }

        @Override
        public void retranslate() {
            getTemplatePresentation().setText(I18n.t("panel.action.manage"));
            getTemplatePresentation().setDescription(I18n.t("panel.action.manage.desc"));
            // 下拉里的条目也是我们自己的动作，得一起重贴。
            // 同样别用 getChildren(null)——平台禁止手动展开动作组。
            for (AnAction child : getChildActionsOrStubs()) {
                if (child instanceof Relocalizable relocalizable) {
                    relocalizable.retranslate();
                }
            }
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class EditServerAction extends PanelAction {
        EditServerAction() {
            super("panel.action.edit", "panel.action.edit.desc", AllIcons.Actions.Edit);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(currentConfig() != null && !busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            editServer();
        }
    }

    private final class RemoveServerAction extends PanelAction {
        RemoveServerAction() {
            super("panel.action.remove", "panel.action.remove.desc", AllIcons.General.Remove);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(currentConfig() != null && !busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            removeServer();
        }
    }

    private final class ImportFromFileAction extends PanelAction {
        ImportFromFileAction() {
            super("panel.action.importFile", "panel.import.scanTip", AllIcons.Actions.Download);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            importFromDialog(ImportConfigDialog.forFiles(project));
        }
    }

    private final class ImportFromPasteAction extends PanelAction {
        ImportFromPasteAction() {
            super("panel.action.importPaste", "panel.action.importPaste.desc", AllIcons.Actions.MenuPaste);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!busy);
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            importFromDialog(ImportConfigDialog.forPaste(project));
        }
    }

    private final class ExportAction extends PanelAction {
        ExportAction() {
            super("panel.action.export", "panel.action.export.desc", AllIcons.Actions.Copy);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!settings.getServers().isEmpty());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            exportToClipboard();
        }
    }

    /**
     * 把一键切换按下去的显式语言偏好清回"跟随 IDE"。
     *
     * <p>这是「自动」唯一的入口：状态条上那个按钮只有中 / 英两态。已经是自动时置灰——
     * 这一项是"复位"，复位过一次就没什么可复的了。
     */
    private final class FollowIdeLanguageAction extends PanelAction {
        FollowIdeLanguageAction() {
            super("lang.auto.text", "lang.auto.desc", AllIcons.General.Reset);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(!LanguageSupport.isAuto());
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            followIdeLanguage();
        }
    }

    private final class ToggleLogAction extends ToggleAction implements Relocalizable {
        ToggleLogAction() {
            super(I18n.t("panel.action.log"), I18n.t("panel.action.log.desc"), AllIcons.Debugger.Console);
        }

        @Override
        public void retranslate() {
            getTemplatePresentation().setText(I18n.t("panel.action.log"));
            getTemplatePresentation().setDescription(I18n.t("panel.action.log.desc"));
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }

        @Override
        public boolean isSelected(@NotNull AnActionEvent e) {
            return settings.isLogVisible();
        }

        @Override
        public void setSelected(@NotNull AnActionEvent e, boolean state) {
            settings.setLogVisible(state);
            applyLogVisibility();
        }
    }

    // ==================================================================
    // 树单元格渲染
    // ==================================================================

    private final class CatalogRenderer extends ColoredTreeCellRenderer {

        @Override
        public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected,
                                          boolean expanded, boolean leaf, int row, boolean hasFocus) {
            Object user = value instanceof DefaultMutableTreeNode node ? node.getUserObject() : null;
            if (user instanceof Group group) {
                setIcon(Ui.dot(catalogColor(group.kind)));
                append(group.title(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
                append("  " + group.count, SimpleTextAttributes.GRAY_ATTRIBUTES);
            } else if (user instanceof McpTool tool) {
                setIcon(Ui.bullet(catalogColor("tool"), 6));
                append(tool.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                appendBadges(tool);
            } else if (user instanceof McpResource resource) {
                setIcon(Ui.bullet(catalogColor(resource.isTemplate() ? "template" : "resource"), 6));
                append(resource.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                appendSuffix(resource.getSubtitle());
            } else if (user instanceof McpPrompt prompt) {
                setIcon(Ui.bullet(catalogColor("prompt"), 6));
                append(prompt.getName(), SimpleTextAttributes.REGULAR_ATTRIBUTES);
                appendSuffix(prompt.getSubtitle());
            }
            setToolTipText(null);
        }

        private void appendBadges(@NotNull McpTool tool) {
            StringBuilder badges = new StringBuilder();
            if (tool.isDestructive()) {
                badges.append(I18n.t("panel.badge.destructive")).append(' ');
            }
            if (tool.isReadOnly()) {
                badges.append(I18n.t("panel.badge.readOnly")).append(' ');
            }
            if (badges.length() > 0) {
                append("  " + badges.toString().trim(), SimpleTextAttributes.GRAY_ITALIC_ATTRIBUTES);
            }
            appendSuffix(tool.getSubtitle());
        }

        private void appendSuffix(@Nullable String text) {
            if (text != null && !text.isBlank()) {
                append("  " + Ui.ellipsize(JsonUtil.oneLine(text), 60), SimpleTextAttributes.GRAY_ATTRIBUTES);
            }
        }
    }

    // ==================================================================
    // 语言
    // ==================================================================

    /**
     * 重新贴一遍整棵界面自己的文案（语言切换时由 {@link I18n} 直接回调）。
     *
     * <p><b>只换字，不重建。</b>这一条是整块面板的硬约束：目录树的展开状态、用户拖出来的
     * 分隔条比例、编辑到一半的参数 JSON 和光标位置，全部得原样留着。任何"因为要换文案
     * 所以 setModel / removeAllChildren / setContent 一遍"的写法都会把这些一起弄丢，
     * 而丢掉的正好是用户攒下来的上下文。
     *
     * <p>所以每个会随状态变的文案都得能从<b>字段</b>重新推导出来：树的分组标题按 kind 现取、
     * 状态栏那句话按 (状态 + 变量) 现拼、空态提示存的是文案键。这就是为什么上面多了
     * stageKey / idleKey 这类字段，而不是就地拼好一个句子。
     */
    public void applyTexts() {
        serverCombo.setToolTipText(I18n.t("panel.combo.tooltip"));
        // 下拉项的文案是渲染时现取的（Slot.toString），所以只要重画
        serverCombo.repaint();
        refreshLanguageButton();

        emptyTitle.setText(I18n.t("panel.empty.title"));
        emptyHint.setText(I18n.t("panel.empty.hint"));
        emptyAddButton.setText(I18n.t("panel.empty.add"));
        emptyAddButton.setToolTipText(I18n.t("panel.empty.add.tip"));
        emptyImportButton.setText(I18n.t("panel.empty.import"));
        emptyImportButton.setToolTipText(I18n.t("panel.import.scanTip"));

        catalogTree.getEmptyText().setText(I18n.t("panel.tree.empty"));
        refreshGroupTitles();
        // 条目上的"只读 / 破坏性"徽标也是渲染时现取的
        catalogTree.repaint();

        // 别用 getChildren(null)：平台会报 "Do not expand action groups manually"。
        // getChildActionsOrStubs() 是平台给出的合法读法（不传事件、不去展开/求值）。
        for (AnAction action : toolbarGroup.getChildActionsOrStubs()) {
            if (action instanceof Relocalizable relocalizable) {
                relocalizable.retranslate();
            }
        }
        actionToolbar.updateActionsImmediately();

        // 详情区三个子面板各自贴自己的那部分
        toolDetail.applyTexts();
        resourceDetail.applyTexts();
        promptDetail.applyTexts();
        // 日志控制台是常驻的（不像详情区那三个会随选中项换），但它的标题、开关、清空
        // 与折叠动作同样得跟着切——它是本面板的字段，所以由本面板负责级联。
        console.applyTexts();

        updateHeaderForCurrentState();
        updateStatus();
    }

    /** 让分组那几行重画。{@code nodeChanged} 只报"这一行变了"，不会动树的展开状态。 */
    private void refreshGroupTitles() {
        for (int i = 0; i < treeRoot.getChildCount(); i++) {
            if (treeRoot.getChildAt(i) instanceof DefaultMutableTreeNode node) {
                treeModel.nodeChanged(node);
            }
        }
    }

    private void updateHeaderForCurrentState() {
        McpServerConfig config = currentConfig();
        McpClient client = config == null ? null : service.peek(config.getId());
        updateHeader(client != null && client.isConnected(), client);
    }

    private static JBColor catalogColor(@NotNull String kind) {
        return switch (kind) {
            case "tool" -> Ui.TRAFFIC_OUT;
            case "resource", "template" -> Ui.TRAFFIC_IN;
            default -> Ui.TRAFFIC_NOTICE;
        };
    }
}
