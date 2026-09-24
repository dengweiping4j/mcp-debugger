package com.tool4j.mcp.service;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;

import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.model.McpResource;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpServerInfo;
import com.tool4j.mcp.model.McpTool;
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
 * 承载当前工程里活着的 MCP 会话，以及每个会话最后一次拉到的目录快照。
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

    /** serverId → 最后一次成功拉到的目录。见 {@link Catalog}。 */
    private final Map<String, Catalog> catalogs = new ConcurrentHashMap<>();

    public McpProjectService(Project project) {
        this.project = project;
    }

    public static McpProjectService getInstance(Project project) {
        return project.getService(McpProjectService.class);
    }

    /**
     * 一次成功拉取的目录快照。
     *
     * <p>它存在的唯一理由：<b>断连不该让工具列表消失</b>。传输层死了、会话被重建之后，
     * {@link McpClient} 自己的缓存已经归零，但"这台服务器上有哪些工具"并没有因此改变——
     * 那些条目只是暂时不能调用。所以快照独立于会话存活，界面在断连时照样渲染它，
     * 只标一下"已断开"。
     *
     * <p>几个 list 都是 {@code List.copyOf} 出来的不可变对象，直接持有引用即可，
     * 不必再拷一层。
     */
    public static final class Catalog {

        private final List<McpTool> tools;
        private final List<McpResource> resources;
        private final List<McpResource> resourceTemplates;
        private final List<McpPrompt> prompts;
        private final McpServerInfo serverInfo;

        Catalog(List<McpTool> tools, List<McpResource> resources, List<McpResource> resourceTemplates,
                List<McpPrompt> prompts, McpServerInfo serverInfo) {
            this.tools = safe(tools);
            this.resources = safe(resources);
            this.resourceTemplates = safe(resourceTemplates);
            this.prompts = safe(prompts);
            this.serverInfo = serverInfo;
        }

        private static <T> List<T> safe(List<T> src) {
            return src == null ? List.of() : List.copyOf(src);
        }

        public List<McpTool> getTools() {
            return tools;
        }

        public List<McpResource> getResources() {
            return resources;
        }

        public List<McpResource> getResourceTemplates() {
            return resourceTemplates;
        }

        public List<McpPrompt> getPrompts() {
            return prompts;
        }

        @Nullable
        public McpServerInfo getServerInfo() {
            return serverInfo;
        }

        public boolean isEmpty() {
            return tools.isEmpty() && resources.isEmpty() && resourceTemplates.isEmpty() && prompts.isEmpty();
        }
    }

    /**
     * 取（必要时创建）某个服务器配置对应的会话。
     *
     * <p>同一份配置始终复用同一个会话，这样重开工具窗口还能看到上次拉到的目录。
     * 但可复用的前提是它<b>真的还活着</b>：{@link McpClient#isReusable()} 同时挡住两种死法——
     * 已经 {@code close()} 过的，以及连上过但传输层已经断了的（SSE 长连接被掐）。
     * 尤其是后者：以前这里只看 {@code isClosed()}，于是断线之后每次点「连接」都先把那个
     * 死实例原样拿回来，必然失败一次，然后才轮到重建。
     */
    @NotNull
    public McpClient client(@NotNull McpServerConfig config) {
        return clients.compute(config.getId(), (id, existing) -> {
            if (existing != null && existing.isReusable()) {
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

    /** 断开并丢弃会话，但<b>保留目录快照</b>——断线不等于这台服务器上没工具了。 */
    public void close(@Nullable String serverId) {
        if (serverId == null) {
            return;
        }
        McpClient client = clients.remove(serverId);
        if (client != null) {
            client.close();
        }
    }

    /**
     * 连会话带快照一起丢掉。配置被改过 / 服务器被删掉时用它。
     *
     * <p>跟 {@link #close} 的区别就是那份快照：改过命令行或 URL 的服务器，旧目录已经不代表它了，
     * 留着只会显示一批点不动的条目。
     */
    public void forget(@Nullable String serverId) {
        close(serverId);
        if (serverId != null) {
            catalogs.remove(serverId);
        }
    }

    public boolean isConnected(@Nullable String serverId) {
        McpClient client = peek(serverId);
        return client != null && client.isConnected();
    }

    // ------------------------------------------------------------------
    // 目录快照
    // ------------------------------------------------------------------

    /** 记下一次成功的目录拉取。调用方是界面（每次重建目录树时顺手记一遍）。 */
    public void recordCatalog(@NotNull String serverId, @NotNull McpClient client) {
        catalogs.put(serverId, new Catalog(client.getTools(), client.getResources(),
                client.getResourceTemplates(), client.getPrompts(), client.getServerInfo()));
    }

    /** 最后一次成功拉到的目录；从没连上过就是 null。 */
    @Nullable
    public Catalog catalog(@Nullable String serverId) {
        return serverId == null ? null : catalogs.get(serverId);
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
        catalogs.clear();
    }
}
