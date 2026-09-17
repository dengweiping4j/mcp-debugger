package com.tool4j.mcp.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.util.messages.Topic;
import com.intellij.util.xmlb.XmlSerializerUtil;

import com.tool4j.mcp.model.McpServerConfig;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 全局设置：服务器列表 + 一点点界面记忆。
 *
 * <p>存在 IDE 配置目录的 {@code mcp-debugger.xml} 里（{@code <IDE 配置>/options/}），
 * 换工程也还在——MCP 服务端本来就是机器级的资源。
 *
 * <p>安全提示会显示在配置对话框上：{@code env} / {@code headers} 里的 token 是明文存的，
 * 这里不做加密（{@code PasswordSafe} 需要用户交互，反而会让"自动化脚本注入配置"这条路走不通），
 * 但界面上默认按敏感值打码显示，也绝不会把值写进报文日志。
 */
@State(name = "McpDebuggerSettings", storages = @Storage("mcp-debugger.xml"))
@Service(Service.Level.APP)
public final class McpSettings implements PersistentStateComponent<McpSettings.State> {

    /** 持久化载体：字段全部 public，保证 XmlSerializer 在任意平台版本下都能读写。 */
    public static class State {
        public List<McpServerConfig> servers = new ArrayList<>();
        public String selectedServerId = "";
        public boolean logVisible = true;
        /** 日志里是否显示完整报文体（关掉后只显示方法名，方便看长响应的上下文）。 */
        public boolean logPayloads = true;
    }

    private State state = new State();

    public static McpSettings getInstance() {
        return ApplicationManager.getApplication().getService(McpSettings.class);
    }

    @Override
    public @NotNull State getState() {
        if (state == null) {
            state = new State();
        }
        if (state.servers == null) {
            state.servers = new ArrayList<>();
        }
        return state;
    }

    @Override
    public void loadState(@NotNull State newState) {
        XmlSerializerUtil.copyBean(newState, getState());
        if (getState().servers == null) {
            getState().servers = new ArrayList<>();
        }
        getState().servers.removeIf(Objects::isNull);
    }

    // ------------------------------------------------------------------

    public @NotNull List<McpServerConfig> getServers() {
        return getState().servers;
    }

    public @Nullable McpServerConfig find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (McpServerConfig c : getServers()) {
            if (id.equals(c.getId())) {
                return c;
            }
        }
        return null;
    }

    public @Nullable McpServerConfig findByName(String name) {
        if (name == null) {
            return null;
        }
        for (McpServerConfig c : getServers()) {
            if (name.equalsIgnoreCase(c.getDisplayName())) {
                return c;
            }
        }
        return null;
    }

    public void add(McpServerConfig config) {
        getServers().add(config);
        notifyChanged();
    }

    public void remove(McpServerConfig config) {
        getServers().remove(config);
        if (config != null && config.getId().equals(getState().selectedServerId)) {
            getState().selectedServerId = "";
        }
        notifyChanged();
    }

    public void notifyChanged() {
        // 工具窗口、设置页都挂在这个 topic 上，谁改了配置就广播一次
        ApplicationManager.getApplication().getMessageBus()
                .syncPublisher(TOPIC)
                .settingsChanged();
    }

    /** 极简变更通知：工具窗口重新渲染服务器下拉框。 */
    public interface ChangeListener {
        void settingsChanged();
    }

    private static final Topic<ChangeListener> TOPIC =
            Topic.create("MCP.Debugger.SettingsChanged", ChangeListener.class);

    /** 订阅配置变更；调用方传入自己的 {@link com.intellij.openapi.Disposable}，随它一起注销。 */
    public static void subscribe(@NotNull com.intellij.openapi.Disposable parent,
                                 @NotNull ChangeListener listener) {
        com.intellij.openapi.application.ApplicationManager.getApplication()
                .getMessageBus()
                .connect(parent)
                .subscribe(TOPIC, listener);
    }

    /** 服务器名字不能重复，否则下拉框里分不清。 */
    public boolean isNameTaken(String name, @Nullable String exceptId) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String target = name.toLowerCase(Locale.ROOT);
        for (McpServerConfig c : getServers()) {
            if (exceptId != null && exceptId.equals(c.getId())) {
                continue;
            }
            if (c.getDisplayName().toLowerCase(Locale.ROOT).equals(target)) {
                return true;
            }
        }
        return false;
    }

    /** 导入时避免重名：已存在就加个后缀。 */
    public String uniqueName(String base) {
        String name = (base == null || base.isBlank()) ? "imported" : base.trim();
        if (!isNameTaken(name, null)) {
            return name;
        }
        for (int i = 2; i < 1000; i++) {
            String candidate = name + "-" + i;
            if (!isNameTaken(candidate, null)) {
                return candidate;
            }
        }
        return name + "-" + System.currentTimeMillis();
    }

    /** 上次选中的服务器（工具窗口重开时恢复到它）。 */
    public String getSelectedServerId() {
        return getState().selectedServerId == null ? "" : getState().selectedServerId;
    }

    public void setSelectedServerId(@Nullable String id) {
        getState().selectedServerId = id == null ? "" : id;
    }

    public boolean isLogVisible() {
        return getState().logVisible;
    }

    public void setLogVisible(boolean visible) {
        getState().logVisible = visible;
    }

    public boolean isLogPayloads() {
        return getState().logPayloads;
    }

    public void setLogPayloads(boolean payloads) {
        getState().logPayloads = payloads;
    }
}
