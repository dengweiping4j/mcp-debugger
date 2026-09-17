package com.tool4j.mcp.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
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

import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.model.McpResource;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.model.TransportType;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpClient;
import com.tool4j.mcp.protocol.McpConfigParser;
import com.tool4j.mcp.service.McpProjectService;
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
    private final JBLabel statusText = new JBLabel("未连接");
    // ---------------- 左侧目录 ----------------

    private final JBLabel leftTitle = new JBLabel("未选择服务器");
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
    private String stage = "";
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
        serverCombo.setToolTipText("选择要调试的 MCP 服务器；新建与导入在下面一排的「+」和「管理」里");
        // 只压最小宽度：宽的时候让它铺满一行，窄的时候允许被压到 90px 而不是撑破边栏
        serverCombo.setMinimumSize(new Dimension(JBUI.scale(90),
                Math.max(JBUI.scale(24), serverCombo.getPreferredSize().height)));
        serverCombo.addActionListener(e -> onServerSelected());

        JPanel pickerRow = new JPanel(new BorderLayout());
        pickerRow.setOpaque(false);
        pickerRow.add(serverCombo, BorderLayout.CENTER);

        DefaultActionGroup actions = new DefaultActionGroup();
        actions.add(new ConnectAction());
        actions.add(new RefreshAction());
        actions.add(new AddServerAction());
        actions.add(new ManageGroup());
        actions.add(new ToggleLogAction());

        ActionToolbar toolbar = ActionManager.getInstance()
                .createActionToolbar("McpDebuggerToolWindow", actions, true);
        toolbar.setTargetComponent(this);

        JPanel actionRow = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        actionRow.setOpaque(false);
        actionRow.add(toolbar.getComponent(), BorderLayout.WEST);
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
        showCard(CARD_EMPTY);
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
        catalogTree.getEmptyText().setText("先在工具栏选服务器并连接");
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
     * 空态卡片。文案刻意写成短行 + HTML 定宽 div：右侧边栏只有两三百像素，
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

        JBLabel title = new JBLabel("还没有可调试的内容");
        title.setFont(Ui.bold(title.getFont()));
        title.setAlignmentX(Component.CENTER_ALIGNMENT);

        JBLabel hint = new JBLabel("<html><div style='text-align:center;width:176px'>"
                + "连接服务器后，这里会按工具的<br>"
                + "入参 Schema 生成表单，填好点「调用」<br>"
                + "就能看到结果。"
                + "</div></html>");
        hint.setForeground(Ui.MUTED);
        hint.setFont(Ui.smaller(hint.getFont()));
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton addButton = new JButton("新建服务器", AllIcons.General.Add);
        addButton.setToolTipText("添加一个 stdio 或 HTTP 的 MCP 服务器");
        addButton.addActionListener(e -> addServer());
        addButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        JButton importButton = new JButton("导入配置…", AllIcons.Actions.Download);
        importButton.setToolTipText("扫本机 Claude Desktop / Cursor / VS Code / 工程内的 MCP 配置");
        importButton.addActionListener(e -> importFromDialog(ImportConfigDialog.forFiles(project)));
        importButton.setAlignmentX(Component.CENTER_ALIGNMENT);

        content.add(title);
        content.add(Ui.vgap(6));
        content.add(hint);
        content.add(Ui.vgap(10));
        content.add(addButton);
        content.add(Ui.vgap(6));
        content.add(importButton);
        content.add(Ui.vgap(2));
        content.add(new JBLabel("<html><div style='text-align:center;width:176px'>"
                + "也可以直接点左侧列表里的工具<br>开始调试。"
                + "</div></html>"));
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
                return "（还没有服务器）";
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
                    setText(slot + "   ⚠ 上次连接失败");
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
            console.appendInfo("已断开 " + config.getDisplayName());
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
        stage = "正在连接 " + config.getDisplayName() + "…";
        updateStatus();
        console.appendInfo("正在连接 " + config.getDisplayName() + "（" + config.getTransport().getDisplayName()
                + "）· " + config.getEndpointSummary());
        if (userInitiated) {
            showCard(CARD_EMPTY);
        }

        Bg.run(project, "连接 MCP 服务器 " + config.getDisplayName(), true,
                () -> {
                    client.connect();
                    client.refreshAll();
                    return client;
                },
                connected -> {
                    busy = false;
                    stage = "";
                    config.setLastConnectOk(true);
                    rebuildTree();
                    selectFirstLeaf();
                    console.appendInfo("已连接 " + config.getDisplayName()
                            + "：" + (connected.getServerInfo() == null ? "无服务端信息"
                            : connected.getServerInfo().getSummary()));
                },
                error -> {
                    busy = false;
                    stage = "";
                    config.setLastConnectOk(false);
                    console.appendError("连接失败：" + McpException.describe(error));
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
        stage = "正在刷新目录…";
        updateStatus();
        Bg.run(project, "刷新 MCP 目录", false,
                () -> {
                    client.refreshAll();
                    return null;
                },
                ignored -> {
                    busy = false;
                    stage = "";
                    rebuildTree();
                    console.appendInfo("目录已刷新");
                },
                error -> {
                    busy = false;
                    stage = "";
                    updateStatus();
                    console.appendError("刷新失败：" + McpException.describe(error));
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
                onEdt(() -> {
                    stage = value == null ? "" : value;
                    updateStatus();
                });
            }

            @Override
            public void onServerNotification(String method, JsonElement params) {
                onEdt(() -> console.appendInfo("服务端通知：" + method));
                // 服务端主动说目录变了就顺手同步一下，省得用户自己点刷新
                if (method != null && method.endsWith("list_changed")) {
                    onEdt(McpPanel.this::refreshCatalog);
                }
            }

            @Override
            public void onDisconnected(String reason) {
                onEdt(() -> {
                    console.appendError("连接已断开：" + (reason == null ? "未知原因" : reason));
                    stage = "";
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
        final String title;
        final int count;

        Group(String kind, String title, int count) {
            this.kind = kind;
            this.title = title;
            this.count = count;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private void rebuildTree() {
        treeRoot.removeAllChildren();
        McpServerConfig config = currentConfig();
        McpClient client = config == null ? null : service.peek(config.getId());
        boolean connected = client != null && client.isConnected();

        if (connected) {
            addGroup(new Group("tool", "工具", client.getTools().size()), client.getTools());
            addGroup(new Group("resource", "资源", client.getResources().size()), client.getResources());
            addGroup(new Group("template", "资源模板", client.getResourceTemplates().size()),
                    client.getResourceTemplates());
            addGroup(new Group("prompt", "提示词", client.getPrompts().size()), client.getPrompts());
        }
        treeModel.reload();

        if (connected) {
            expandAll();
        } else {
            showCard(CARD_EMPTY);
        }

        updateHeader(connected, client);
        updateStatus();
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
            showCard(CARD_EMPTY);
        }
    }

    private void showCard(String card) {
        detailCards.show(detailHost, card);
    }

    private void updateHeader(boolean connected, @Nullable McpClient client) {
        McpServerConfig config = currentConfig();
        if (config == null) {
            leftTitle.setText("未选择服务器");
            leftTitle.setToolTipText(null);
            setSubtitle("上面还没有可选的 MCP 服务器");
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
        bits.add(tools + " 工具");
        if (resources > 0) {
            bits.add(resources + " 资源");
        }
        if (prompts > 0) {
            bits.add(prompts + " 提示词");
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

    private void updateStatus() {
        McpServerConfig config = currentConfig();
        if (config == null) {
            setStatus(Ui.MUTED, "还没有配置服务器");
            return;
        }
        if (busy) {
            setStatus(Ui.WARN, stage.isEmpty() ? "进行中…" : stage);
            return;
        }
        if (!service.isConnected(config.getId())) {
            setStatus(Ui.MUTED, "未连接");
            return;
        }
        McpClient client = service.peek(config.getId());
        int tools = client == null ? 0 : client.getTools().size();
        setStatus(Ui.OK, "已连接 · " + tools + " 个工具");
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
            toolDetail.showInvokeError("当前没有连上的服务器。先在工具栏点「连接」。");
            return;
        }
        Bg.run(project, "调用工具 " + tool.getName(), true,
                () -> client.callTool(tool.getName(), arguments),
                toolDetail::showResult,
                error -> toolDetail.showInvokeError(McpException.describe(error)));
    }

    private void readResource(@NotNull McpResource resource, @NotNull String uri) {
        McpClient client = connectedClient();
        if (client == null) {
            resourceDetail.showError("当前没有连上的服务器。先在工具栏点「连接」。");
            return;
        }
        Bg.run(project, "读取资源 " + uri, true,
                () -> client.readResource(uri),
                resourceDetail::showResult,
                error -> resourceDetail.showError(McpException.describe(error)));
    }

    private void getPrompt(@NotNull McpPrompt prompt, @NotNull JsonObject arguments) {
        McpClient client = connectedClient();
        if (client == null) {
            promptDetail.showError("当前没有连上的服务器。先在工具栏点「连接」。");
            return;
        }
        Bg.run(project, "获取提示词 " + prompt.getName(), true,
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
        int answer = Messages.showYesNoDialog(project,
                "删除服务器「" + config.getDisplayName() + "」？\n连接会一起断开，配置从设置里移除。",
                "删除 MCP 服务器", Messages.getQuestionIcon());
        if (answer != Messages.YES) {
            return;
        }
        service.close(config.getId());
        unhook(config.getId());
        settings.remove(config);
        settings.setSelectedServerId("");
        reloadServers();
        console.appendInfo("已删除服务器 " + config.getDisplayName());
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
        console.appendInfo("导入完成：新增 " + added + " 个" + (skipped > 0 ? "，跳过重复 " + skipped + " 个" : ""));
        if (added == 0) {
            Messages.showInfoMessage(project, "这些服务器都已经在列表里了，没有新增。", "导入 MCP 配置");
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
            Messages.showInfoMessage(project, "还没有可导出的服务器。", "导出 MCP 配置");
            return;
        }
        Ui.copyToClipboard(McpConfigParser.toPrettyJson(servers));
        console.appendInfo("已把 " + servers.size() + " 个服务器配置复制到剪贴板"
                + "（Claude Desktop / Cursor 的 mcpServers 格式）");
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
    private abstract class PanelAction extends DumbAwareAction {
        PanelAction(@NotNull String text, @NotNull String description, @NotNull Icon icon) {
            super(text, description, icon);
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class ConnectAction extends PanelAction {
        ConnectAction() {
            super("连接", "连接当前服务器并拉取工具列表；已连接时点击会断开", AllIcons.Actions.Execute);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            McpServerConfig config = currentConfig();
            boolean connected = config != null && service.isConnected(config.getId());
            e.getPresentation().setEnabled(config != null && !busy);
            e.getPresentation().setText(connected ? "断开" : "连接");
            e.getPresentation().setIcon(connected ? AllIcons.Actions.Suspend : AllIcons.Actions.Execute);
            e.getPresentation().setDescription(connected
                    ? "断开当前连接（子进程会一起收掉）"
                    : "连接当前服务器并拉取工具列表");
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            toggleConnection();
        }
    }

    private final class RefreshAction extends PanelAction {
        RefreshAction() {
            super("刷新", "重新拉取工具 / 资源 / 提示词列表", AllIcons.Actions.Refresh);
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
            super("新建服务器", "添加一个 stdio 或 HTTP 的 MCP 服务器", AllIcons.General.Add);
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
    private final class ManageGroup extends DefaultActionGroup {
        ManageGroup() {
            super("管理", true);
            getTemplatePresentation().setIcon(AllIcons.General.GearPlain);
            getTemplatePresentation().setDescription("编辑 / 删除服务器，导入导出配置");
            add(new EditServerAction());
            add(new RemoveServerAction());
            addSeparator();
            add(new ImportFromFileAction());
            add(new ImportFromPasteAction());
            add(new ExportAction());
        }

        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }

    private final class EditServerAction extends PanelAction {
        EditServerAction() {
            super("编辑服务器", "修改当前服务器的连接参数", AllIcons.Actions.Edit);
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
            super("删除服务器", "从配置里移除当前服务器", AllIcons.General.Remove);
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
            super("从配置文件导入…", "扫描本机 Claude Desktop / Cursor / VS Code / 工程内的 MCP 配置",
                    AllIcons.Actions.Download);
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
            super("粘贴 JSON 导入…", "把一段 mcpServers 配置粘进来导入", AllIcons.Actions.MenuPaste);
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
            super("复制配置到剪贴板", "按 Claude Desktop 的 mcpServers 格式导出全部服务器",
                    AllIcons.Actions.Copy);
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

    private final class ToggleLogAction extends ToggleAction {
        ToggleLogAction() {
            super("报文日志", "显示 / 隐藏底部的 JSON-RPC 报文日志", AllIcons.Debugger.Console);
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
                append(group.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
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
                badges.append("破坏性 ");
            }
            if (tool.isReadOnly()) {
                badges.append("只读 ");
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

    private static JBColor catalogColor(@NotNull String kind) {
        return switch (kind) {
            case "tool" -> Ui.TRAFFIC_OUT;
            case "resource", "template" -> Ui.TRAFFIC_IN;
            default -> Ui.TRAFFIC_NOTICE;
        };
    }
}
