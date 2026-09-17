package com.tool4j.mcp.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.protocol.McpClient;
import com.tool4j.mcp.util.PluginInfo;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 承载当前工程里活着的 MCP 会话。
 *
 * <p>为什么挂在 <b>project</b> 上而不是 application 上：stdio 传输会拉起真实子进程，
 * 如果挂在 application 级，关掉工程后这些子进程会继续赖着不走。挂在工程级，
 * 工程一关 {@link #dispose()} 就会被调用，所有连接（以及它们拉起的子进程）一并收掉。
 *
 * <p>工具窗口关闭/重开不应该断连——会话在这里，所以重开窗口能直接看到上一次拉到的工具列表。
 */
@Service(Service.Level.PROJECT)
public final class McpProjectService implements Disposable {

    private final Project project;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    public McpProjectService(Project project) {
        this.project = project;
    }

    public static McpProjectService getInstance(Project project) {
        return project.getService(McpProjectService.class);
    }

    /**
     * 取（必要时创建）某个服务器配置对应的会话。
     *
     * <p>同一份配置始终复用同一个会话，这样重开工具窗口还能看到上次拉到的目录。
     * 但<b>已关闭的会话不能复用</b>——{@link McpClient#connect()} 失败时会自动
     * {@code close()} 自己（见其实现），如果把它原样还回去，用户之后再点"连接"
     * 就会一直撞在底层那句"连接已关闭"上，且不做任何网络请求，看起来像插件坏了。
     * 所以这里发现实例已关闭就地重建。
     */
    @NotNull
    public McpClient client(@NotNull McpServerConfig config) {
        return clients.compute(config.getId(), (id, existing) -> {
            if (existing != null && !existing.isClosed()) {
                return existing;
            }
            if (existing != null) {
                existing.close(); // 幂等，顺手确认资源已回收
            }
            McpClient client = new McpClient(config);
            client.setClientVersion(PluginInfo.version());
            client.setRootUris(projectRootUris());
            return client;
        });
    }

    @Nullable
    public McpClient peek(@Nullable String serverId) {
        return serverId == null ? null : clients.get(serverId);
    }

    /** 断开并丢弃会话——配置被改过之后必须调它，否则新配置不会生效。 */
    public void close(@Nullable String serverId) {
        if (serverId == null) {
            return;
        }
        McpClient client = clients.remove(serverId);
        if (client != null) {
            client.close();
        }
    }

    public boolean isConnected(@Nullable String serverId) {
        McpClient client = peek(serverId);
        return client != null && client.isConnected();
    }

    /** 当前工程的根目录，用于应答服务端的 {@code roots/list}。 */
    public List<String> projectRootUris() {
        List<String> uris = new ArrayList<>(1);
        String base = project.getBasePath();
        if (base != null && !base.isBlank()) {
            try {
                // File.toURI() 给出的是 file:/D:/x 这种形式，补一个斜杠更规范
                String uri = new File(base).toURI().toString();
                uris.add(uri);
            } catch (RuntimeException ignored) {
                // 路径畸形就算了，roots 是可选能力
            }
        }
        return uris;
    }

    @Override
    public void dispose() {
        for (McpClient client : clients.values()) {
            client.close();
        }
        clients.clear();
    }
}
