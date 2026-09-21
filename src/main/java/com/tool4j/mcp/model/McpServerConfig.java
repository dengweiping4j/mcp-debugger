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

    /** 额外请求头，用于 Authorization 之类的鉴权。 */
    private List<KeyValue> headers = new ArrayList<>();

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
