package com.tool4j.mcp.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import com.intellij.util.xmlb.XmlSerializerUtil;

import com.tool4j.mcp.model.ToolCallRecord;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具调用历史（「历史」页签的数据源）。
 *
 * <p><b>为什么单独一个 Service / 单独一个文件</b>：它和 {@link McpSettings} 的写节奏完全不是一回事——
 * 设置是"用户偶尔改一次"，历史是"每点一次调用就写一次，还要按上限裁剪"。混在一份 xml 里，
 * 每次调用都会让整份服务器配置重新落盘。分开存还有一个好处：历史是可以随手清掉的东西，
 * 清空它不该去碰用户的服务器配置。
 *
 * <p>作用域是<b>应用级</b>而不是工程级：MCP 服务端本来就是机器级资源（{@link McpSettings} 同理），
 * 换个工程还想复用上一次的入参，是这条功能的正常用法。
 *
 * <p>存 {@code List<POJO>} 而不是 {@code Map}：XmlSerializer 对 Map 的支持一直不可靠，
 * 而"按 serverId + toolName 过滤"是一次线性扫，几百条量级根本不值得为它建索引。
 */
@State(name = "McpDebuggerCallHistory", storages = @Storage("mcp-debugger-history.xml"))
@Service(Service.Level.APP)
public final class McpCallHistory implements PersistentStateComponent<McpCallHistory.State> {

    /**
     * 每个工具保留的最近条数。
     *
     * <p>20 不是随手定的：这个数字要够到"翻得回来上周调过的那次"，又不能多到让
     * 「历史」页签变成一屏需要搜索的列表。再多也没有人逐条看，而它是有代价的——
     * 每一条都要写进配置文件。
     */
    private static final int MAX_PER_TOOL = 20;

    /** 全局上限。几十台服务器 × 几百个工具，不封顶会把配置目录写成一份巨大的 xml。 */
    private static final int MAX_TOTAL = 400;

    /** {@link ToolCallRecord#note} 的最大长度：它只服务 tooltip，留够看清原因即可。 */
    public static final int NOTE_LIMIT = 200;

    /**
     * {@link ToolCallRecord#response} 的最大长度。
     *
     * <p>比 {@link #NOTE_LIMIT} 宽一些：失败原因是一句话，而响应预览常常是
     * {@code {"ok":true,"count":12,...}} 这种「前几个键就说明白了」的形状，200 字符往往
     * 刚够看到第一个键。封顶 240 之后，满配 400 条约占 100 KB，对一份 IDE 配置可以接受。
     */
    public static final int RESPONSE_LIMIT = 240;

    /** 持久化载体：字段全部 public，保证 XmlSerializer 在任意平台版本下都能读写。 */
    public static class State {
        /**
         * 全部记录，<b>按时间倒序</b>（最新的在下标 0）。
         *
         * <p>顺序即约定：新记录前插、裁剪从尾巴丢、取某个工具的历史时直接保持这个顺序。
         * 不用"插入后排序"，因为时间戳来自不同的调用，排序反而会让"连着重试的那几条"乱序。
         */
        public List<ToolCallRecord> records = new ArrayList<>();
    }

    private State state = new State();

    public static McpCallHistory getInstance() {
        return ApplicationManager.getApplication().getService(McpCallHistory.class);
    }

    @Override
    public @NotNull State getState() {
        if (state == null) {
            state = new State();
        }
        if (state.records == null) {
            state.records = new ArrayList<>();
        }
        return state;
    }

    @Override
    public void loadState(@NotNull State newState) {
        XmlSerializerUtil.copyBean(newState, getState());
        getState().records.removeIf(Objects::isNull);
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    /**
     * 某个工具的调用历史，新的在前。
     *
     * <p>返回的是新建的列表（元素仍是原对象），界面直接拿去当列表模型，改它不会影响存储。
     */
    public @NotNull List<ToolCallRecord> recordsOf(@Nullable String serverId, @Nullable String toolName) {
        List<ToolCallRecord> out = new ArrayList<>();
        if (serverId == null || serverId.isEmpty() || toolName == null || toolName.isEmpty()) {
            return out;
        }
        for (ToolCallRecord record : getState().records) {
            if (record != null && serverId.equals(record.serverId) && toolName.equals(record.toolName)) {
                out.add(record);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 写
    // ------------------------------------------------------------------

    /**
     * 记一次调用。
     *
     * <p><b>连续用同一份入参调同一个工具时不新增记录</b>，而是把最上面那条刷新成这次的时刻与结果。
     * 否则"失败 → 原样重试 → 再点一次"就会在历史里堆出一屏一模一样的条目，
     * 真正有用的、上一条不同入参的记录反而被挤得看不见。
     *
     * <p>成功与失败都记：失败那一次的入参往往才是要复用的那一份（改一个值就好了）。
     */
    public void record(@NotNull ToolCallRecord record) {
        List<ToolCallRecord> all = getState().records;
        ToolCallRecord top = all.isEmpty() ? null : all.get(0);
        if (top != null
                && Objects.equals(top.serverId, record.serverId)
                && Objects.equals(top.toolName, record.toolName)
                && Objects.equals(top.arguments, record.arguments)) {
            top.timestamp = record.timestamp;
            top.elapsedMillis = record.elapsedMillis;
            top.status = record.status;
            top.note = record.note;
            // 响应必须一起刷新：同一份入参重发，结果完全可能变了（上次超时、这次通了），
            // 而卡片上"响应预览"正是用来看这个变化的那一格。漏了它就会出现
            // "状态写着失败、预览却还留着上次成功的内容"这种自相矛盾的卡片。
            top.response = record.response;
        } else {
            all.add(0, record);
        }
        trim();
        notifyChanged();
    }

    public void remove(@Nullable ToolCallRecord record) {
        if (record != null && getState().records.remove(record)) {
            notifyChanged();
        }
    }

    /** 清空某个工具的历史。 */
    public void clearTool(@Nullable String serverId, @Nullable String toolName) {
        if (serverId == null || toolName == null) {
            return;
        }
        boolean changed = getState().records.removeIf(record -> record != null
                && serverId.equals(record.serverId) && toolName.equals(record.toolName));
        if (changed) {
            notifyChanged();
        }
    }

    /**
     * 服务器被删掉之后，它的历史就没有归属了，一起清掉。
     *
     * <p>不清的话会留下永远看不见、也永远删不掉的死记录——「历史」页签是按
     * serverId + toolName 取的，配置没了就再也选不中那个工具。
     */
    public void forgetServer(@Nullable String serverId) {
        if (serverId == null || serverId.isEmpty()) {
            return;
        }
        boolean changed = getState().records.removeIf(record -> record != null && serverId.equals(record.serverId));
        if (changed) {
            notifyChanged();
        }
    }

    /**
     * 按上限裁剪：先"每个工具只留最近 {@link #MAX_PER_TOOL} 条"，再压全局总数。
     *
     * <p>两级都要：只按工具裁，配了二十台服务器时总量仍然会失控；只按总数裁，
     * 一个高频工具能把其他所有工具的历史挤没——那种情况下"清空历史"会变成常态操作。
     */
    private void trim() {
        List<ToolCallRecord> all = getState().records;
        all.removeIf(Objects::isNull);

        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            ToolCallRecord record = all.get(i);
            int count = seen.merge(record.serverId + '\u0000' + record.toolName, 1, Integer::sum);
            if (count > MAX_PER_TOOL) {
                all.remove(i--);
            }
        }
        while (all.size() > MAX_TOTAL) {
            all.remove(all.size() - 1);
        }
    }

    public void notifyChanged() {
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(TOPIC)
                .historyChanged();
    }

    /** 历史变了——「历史」页签据此刷新列表（新调用记入、单条删除、清空都会广播）。 */
    public interface ChangeListener {
        void historyChanged();
    }

    private static final Topic<ChangeListener> TOPIC =
            Topic.create("MCP.Debugger.CallHistoryChanged", ChangeListener.class);

    /** 订阅历史变更；调用方传入自己的 {@link com.intellij.openapi.Disposable}，随它一起注销。 */
    public static void subscribe(@NotNull com.intellij.openapi.Disposable parent,
                                 @NotNull ChangeListener listener) {
        ApplicationManager.getApplication().getMessageBus()
                .connect(parent)
                .subscribe(TOPIC, listener);
    }
}
