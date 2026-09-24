package com.tool4j.mcp.model;

import com.tool4j.mcp.i18n.I18n;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一个 MCP 服务端连接配置。字段刻意做成扁平结构：stdio 用 cmd/args/env，HTTP 用 url/headers，
 * 由 {@link #getTransport()} 决定哪一组生效——这样配置文件里一眼能看全，UI 也只需要一个对话框。
 */
@Getter
@Setter
@NoArgsConstructor
public class McpServerConfig {

    /** 客户端默认声明的协议版本；服务端可以回一个它支持的版本，我们会照单全收。 */
    public static final String DEFAULT_PROTOCOL_VERSION = "2025-06-18";

    private String id = UUID.randomUUID().toString();
    private String name = "";

    private TransportType transport = TransportType.STDIO;

    // ---------------- stdio ----------------

    /** 可执行文件名或绝对路径，例如 {@code npx}、{@code uvx}、{@code java}。 */
    private String command = "";

    /** 命令参数，按行编辑。 */
    private List<String> args = new ArrayList<>();

    /** 追加/覆盖子进程环境变量；未列出的变量继承 IDE 进程环境。 */
    private List<KeyValue> env = new ArrayList<>();

    /** 子进程工作目录，留空表示项目根目录。 */
    private String workingDir = "";

    // ---------------- http / sse ----------------

    /** 服务端地址。Streamable HTTP 填完整端点（如 {@code http://127.0.0.1:3000/mcp}）；SSE 填 SSE 地址。 */
    private String url = "";

    /** 服务器级额外请求头，用于 Authorization 之类的鉴权。带在<b>所有</b>请求上（含握手与建连）。 */
    private List<KeyValue> headers = new ArrayList<>();

    /**
     * 工具级请求头：只在 {@code tools/call} 且工具名命中时叠加，同名键覆盖服务器级。
     *
     * <p>用 {@code List<POJO>}（而不是 {@code Map<String, List<KeyValue>>}）是一条硬约定，
     * 见 {@link ToolHeaders} 的类注释。
     */
    private List<ToolHeaders> toolHeaders = new ArrayList<>();

    // ---------------- 通用 ----------------

    /** 握手时声明的协议版本。 */
    private String protocolVersion = DEFAULT_PROTOCOL_VERSION;

    /** 单次请求（含工具调用）超时时间，秒。 */
    private int timeoutSeconds = 60;

    /** 是否启用（禁用的服务器不出现在可连接列表里）。 */
    private boolean enabled = true;

    /** 在服务器下拉框里选中它时自动连接。 */
    private boolean autoConnect = false;

    /**
     * 上次连接是否成功。{@code null} 表示还没连过——三态而不是布尔，否则从没连过的服务器
     * 会被当成"上次失败"，下拉框里就全是警告了。
     */
    private Boolean lastConnectOk;

    public List<String> getArgs() {
        if (args == null) {
            args = new ArrayList<>();
        }
        return args;
    }

    public List<KeyValue> getEnv() {
        if (env == null) {
            env = new ArrayList<>();
        }
        return env;
    }

    public List<KeyValue> getHeaders() {
        if (headers == null) {
            headers = new ArrayList<>();
        }
        return headers;
    }

    public List<ToolHeaders> getToolHeaders() {
        if (toolHeaders == null) {
            toolHeaders = new ArrayList<>();
        }
        return toolHeaders;
    }

    /** 命令 + 参数的展示形态，用在服务器列表与日志里。 */
    public String getCommandLine() {
        StringBuilder sb = new StringBuilder(command == null ? "" : command);
        for (String a : getArgs()) {
            if (a == null || a.isEmpty()) {
                continue;
            }
            sb.append(' ').append(a.indexOf(' ') >= 0 ? '"' + a + '"' : a);
        }
        return sb.toString();
    }

    /** 一句话摘要：stdio 显示命令行，HTTP 显示 URL。 */
    public String getEndpointSummary() {
        return transport == TransportType.STDIO ? getCommandLine() : (url == null ? "" : url);
    }

    /** 转成真正传给子进程的环境变量 Map（已剔除空键）。 */
    public Map<String, String> envMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (KeyValue kv : getEnv()) {
            if (!kv.isEmpty()) {
                map.put(kv.getKey().trim(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
        return map;
    }

    /** 转成真正加到请求上的 header Map（已剔除空键）。 */
    public Map<String, String> headerMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (KeyValue kv : getHeaders()) {
            if (!kv.isEmpty()) {
                map.put(kv.getKey().trim(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
        return map;
    }

    // ---------------- 工具级请求头 ----------------

    /** 工具名比较前统一 trim：配置里手敲出来的 "name " 不该被当成另一个工具。 */
    private static String normalizeTool(String toolName) {
        return toolName == null ? "" : toolName.trim();
    }

    /**
     * 某个工具当前的请求头条目。
     *
     * <p>用<b>只读</b>空列表表示"这个工具没有专属头"，而不是当场建一条空记录塞进配置：
     * 界面每换一次服务器/工具都会来读一遍，顺手写配置会让配置文件里堆满空壳。
     * 要写入请走 {@link #setToolHeadersOf}。
     */
    public List<KeyValue> toolHeadersOf(String toolName) {
        String key = normalizeTool(toolName);
        if (key.isEmpty()) {
            return List.of();
        }
        for (ToolHeaders entry : getToolHeaders()) {
            if (entry != null && key.equals(normalizeTool(entry.getToolName()))) {
                return entry.getHeaders();
            }
        }
        return List.of();
    }

    /**
     * 覆盖某个工具的请求头。
     *
     * <p>传空列表表示"清掉这个工具的专属头"，此时整条记录会被移除——不留空壳，
     * 配置文件里就只剩真正在用的那几条。服务端工具改名/下线会留下对不上的旧条目，
     * 这里<b>刻意不做清理</b>：服务端临时挂了不该把用户配好的头抹掉。
     */
    public void setToolHeadersOf(String toolName, List<KeyValue> headers) {
        String key = normalizeTool(toolName);
        if (key.isEmpty()) {
            return;
        }
        List<KeyValue> incoming = new ArrayList<>();
        if (headers != null) {
            for (KeyValue kv : headers) {
                if (kv != null && !kv.isEmpty()) {
                    incoming.add(kv.copy());
                }
            }
        }
        List<ToolHeaders> list = getToolHeaders();
        for (int i = 0; i < list.size(); i++) {
            ToolHeaders entry = list.get(i);
            if (entry != null && key.equals(normalizeTool(entry.getToolName()))) {
                if (incoming.isEmpty()) {
                    list.remove(i);
                } else {
                    entry.setHeaders(new ArrayList<>(incoming));
                }
                return;
            }
        }
        if (!incoming.isEmpty()) {
            ToolHeaders entry = new ToolHeaders(key);
            entry.setHeaders(new ArrayList<>(incoming));
            list.add(entry);
        }
    }

    /**
     * 真正要带在这次请求上的头：服务器级打底，工具级同名覆盖。
     *
     * <p>{@code toolName} 传 null / 空（握手、{@code tools/list} 这类请求）时就是纯服务器级——
     * 那些请求压根不属于任何工具。
     */
    public Map<String, String> headerMapFor(String toolName) {
        Map<String, String> map = headerMap();
        String key = normalizeTool(toolName);
        if (key.isEmpty()) {
            return map;
        }
        for (ToolHeaders entry : getToolHeaders()) {
            if (entry != null && key.equals(normalizeTool(entry.getToolName()))) {
                map.putAll(entry.headerMap());
                return map;
            }
        }
        return map;
    }

    /** 下拉框里显示的名字。没起名时退化成 command / url，所以取词在调用时做，不存字段。 */
    public String getDisplayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        String unnamed = I18n.t("model.server.unnamed");
        if (transport == TransportType.STDIO) {
            String c = command == null ? "" : command.trim();
            return c.isEmpty() ? unnamed : c;
        }
        return (url == null || url.isBlank()) ? unnamed : url;
    }

    public McpServerConfig copy() {
        McpServerConfig c = new McpServerConfig();
        c.id = UUID.randomUUID().toString();
        c.name = name;
        c.transport = transport;
        c.command = command;
        c.args = new ArrayList<>(getArgs());
        c.env = copyList(getEnv());
        c.workingDir = workingDir;
        c.url = url;
        c.headers = copyList(getHeaders());
        c.toolHeaders = copyToolHeaders(getToolHeaders());
        c.protocolVersion = protocolVersion;
        c.timeoutSeconds = timeoutSeconds;
        c.enabled = enabled;
        c.autoConnect = autoConnect;
        c.lastConnectOk = lastConnectOk;
        return c;
    }

    /** 原地覆盖，保留 id —— 用于"编辑"场景。 */
    public void applyFrom(McpServerConfig other) {
        this.name = other.name;
        this.transport = other.transport;
        this.command = other.command;
        this.args = new ArrayList<>(other.getArgs());
        this.env = copyList(other.getEnv());
        this.workingDir = other.workingDir;
        this.url = other.url;
        this.headers = copyList(other.getHeaders());
        this.toolHeaders = copyToolHeaders(other.getToolHeaders());
        this.protocolVersion = other.protocolVersion;
        this.timeoutSeconds = other.timeoutSeconds;
        this.enabled = other.enabled;
        this.autoConnect = other.autoConnect;
        // 连接参数变了，上一次的连接结果就不再代表这台服务器，重置成"还没连过"
        this.lastConnectOk = null;
    }

    private static List<KeyValue> copyList(List<KeyValue> src) {
        List<KeyValue> out = new ArrayList<>(src.size());
        for (KeyValue kv : src) {
            out.add(kv.copy());
        }
        return out;
    }

    private static List<ToolHeaders> copyToolHeaders(List<ToolHeaders> src) {
        List<ToolHeaders> out = new ArrayList<>(src.size());
        for (ToolHeaders entry : src) {
            if (entry != null) {
                out.add(entry.copy());
            }
        }
        return out;
    }

    public static McpServerConfig stdio(String name, String command, String... args) {
        McpServerConfig c = new McpServerConfig();
        c.name = name;
        c.transport = TransportType.STDIO;
        c.command = command;
        c.args = new ArrayList<>(List.of(args));
        return c;
    }

    public static McpServerConfig http(String name, String url) {
        McpServerConfig c = new McpServerConfig();
        c.name = name;
        c.transport = TransportType.STREAMABLE_HTTP;
        c.url = url;
        return c;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }
}
