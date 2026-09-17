package com.tool4j.mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.model.McpPrompt;
import com.tool4j.mcp.model.McpResource;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.McpServerInfo;
import com.tool4j.mcp.model.McpTool;
import com.tool4j.mcp.transport.McpTransport;
import com.tool4j.mcp.transport.Transports;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 一个 MCP 服务端的会话。
 *
 * <p>职责边界：传输层只管"把报文送到对面并拿回响应"，这里负责协议语义——
 * 握手、能力协商、把 {@code tools/list} 翻页翻完、调用工具、以及<b>应答服务端反过来发起的请求</b>。
 *
 * <p>线程模型（这几条是它能不能稳定工作的关键）：
 * <ul>
 *   <li>{@link #io} 单线程：所有出站请求都在这条线程上顺序执行，所以"先发 {@code initialized}
 *   再发 {@code tools/list}"这种顺序依赖天然成立，也避免同一会话并发请求。</li>
 *   <li>{@link #inbound} 小线程池：服务端主动发来的 {@code roots/list} 之类的请求在这里应答，
 *   保证应答动作绝不会阻塞"正在等响应"的那条线程——否则对方等我们回、我们等对方回，直接死锁。</li>
 * </ul>
 *
 * <p>所有对外方法都是阻塞的（{@code connect} / {@code callTool} ...），调用方负责放到后台任务里跑，
 * 界面代码一律走 {@code Bg} 工具。
 */
public class McpClient implements AutoCloseable {

    /** 上层（UI / 日志）监听。全部回调都在后台线程上触发。 */
    public interface Listener {

        /** 出站报文。 */
        default void onTraffic(boolean outbound, JsonObject message) {
        }

        /** 传输层信息：子进程 stderr、非协议输出等。 */
        default void onStderr(String text) {
        }

        /** 一句普通日志。 */
        default void onLog(String text) {
        }

        /** 当前正在做什么（"正在握手…"、"正在拉取工具列表…"），用于状态栏。 */
        default void onStage(String stage) {
        }

        /** 服务端主动发来的通知，例如 {@code notifications/tools/list_changed}。 */
        default void onServerNotification(String method, JsonElement params) {
        }

        /** 连接被动断开。 */
        default void onDisconnected(String reason) {
        }
    }

    private final McpServerConfig config;
    private final McpTransport transport;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private final AtomicLong idSequence = new AtomicLong(1);
    private final ExecutorService io;
    private final ExecutorService inbound;

    private volatile McpServerInfo serverInfo;
    private volatile List<McpTool> tools = List.of();
    private volatile List<McpResource> resources = List.of();
    private volatile List<McpResource> resourceTemplates = List.of();
    private volatile List<McpPrompt> prompts = List.of();
    private volatile boolean connected;
    private volatile boolean closed;

    /** 项目根目录之类的 roots，用于应答服务端的 {@code roots/list}。 */
    private volatile List<String> rootUris = List.of();

    private volatile String clientVersion = "1.0.0";

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.transport = Transports.create(config);
        this.transport.setListener(new Bridge());

        // 线程池必须在构造器里建，不能写成字段初始化：字段初始化阶段 config 还没赋值，
        // 而线程名前缀要用 config 的 id（写字段初始化会 NPE）。
        String tag = shortId();
        this.io = Executors.newSingleThreadExecutor(named("mcp-io-" + tag));
        this.inbound = Executors.newFixedThreadPool(2, named("mcp-in-" + tag));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 建立连接并完成握手 + 目录拉取。整个过程是阻塞的，请放在后台任务里调用。
     *
     * <p>握手失败会抛异常并保证已经把子进程/连接收拾干净，不会留下孤儿进程。
     */
    public void connect() throws McpException {
        if (connected) {
            return;
        }
        if (closed) {
            // 走到这里说明上层复用了已经 close() 过的实例。直说，别让它退化成
            // 底层那句没头没尾的"连接已关闭"。
            throw new McpException("会话已关闭，无法重新连接：请重新创建一个连接"
                    + "（已关闭的客户端实例不可复用）");
        }
        try {
            print("正在启动传输通道：" + transport.describe());
            transport.start();

            print("正在发送 initialize 握手…");
            JsonObject initResult = request("initialize", buildInitializeParams());
            this.serverInfo = new McpServerInfo(initResult);
            print("握手完成：" + serverInfo.getSummary()
                    + (serverInfo.getCapabilities().entrySet().isEmpty()
                    ? "" : "，能力：" + String.join(",", serverInfo.getCapabilities().keySet())));

            // 规范要求：initialize 成功后必须补一条 initialized 通知，之后才允许发别的请求
            transport.send(JsonRpc.notification("notifications/initialized", new JsonObject()));
            connected = true;

            if (serverInfo.getInstructions() != null && !serverInfo.getInstructions().isBlank()) {
                print("服务端使用说明：\n" + serverInfo.getInstructions().strip());
            }
            loadCatalog();
        } catch (McpException | RuntimeException e) {
            // 握手阶段失败：把已经起来的进程/连接关掉，避免留下孤儿
            close();
            if (e instanceof McpException me) {
                throw me;
            }
            throw new McpException("连接失败：" + McpException.describe(e), e);
        }
    }

    private JsonObject buildInitializeParams() {
        String version = config.getProtocolVersion() == null || config.getProtocolVersion().isBlank()
                ? McpServerConfig.DEFAULT_PROTOCOL_VERSION : config.getProtocolVersion().trim();

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", version);

        JsonObject capabilities = new JsonObject();
        // 只声明 roots：声明了就得答，声明 sampling / elicitation 又答不了反而更糟
        JsonObject roots = new JsonObject();
        roots.addProperty("listChanged", false);
        capabilities.add("roots", roots);
        params.add("capabilities", capabilities);

        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "MCP Debugger");
        clientInfo.addProperty("version", clientVersion);
        params.add("clientInfo", clientInfo);
        return params;
    }

    /** 依次拉取工具 / 资源 / 提示词。单项失败只记日志，不整体失败——能看到多少算多少。 */
    private void loadCatalog() {
        try {
            refreshTools();
        } catch (McpException e) {
            print("拉取工具列表失败：" + e.getDisplayMessage());
        }
        try {
            refreshResources();
        } catch (McpException e) {
            if (!e.isMethodNotFound()) {
                print("拉取资源列表失败：" + e.getDisplayMessage());
            }
        }
        try {
            refreshPrompts();
        } catch (McpException e) {
            if (!e.isMethodNotFound()) {
                print("拉取提示词列表失败：" + e.getDisplayMessage());
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        connected = false;
        transport.close();
        clearCatalog();
        io.shutdownNow();
        inbound.shutdownNow();
    }

    private void clearCatalog() {
        tools = List.of();
        resources = List.of();
        resourceTemplates = List.of();
        prompts = List.of();
    }

    // ------------------------------------------------------------------
    // 目录
    // ------------------------------------------------------------------

    /** 拉取全部工具（含游标翻页），并缓存。 */
    public List<McpTool> refreshTools() throws McpException {
        stage("正在拉取工具列表…");
        List<McpTool> collected = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null && !cursor.isBlank()) {
                params.addProperty("cursor", cursor);
            }
            JsonObject result = request("tools/list", params);
            for (JsonElement e : JsonUtil.array(result, "tools")) {
                if (e.isJsonObject()) {
                    collected.add(new McpTool(e.getAsJsonObject()));
                }
            }
            cursor = JsonUtil.str(result, "nextCursor", null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        collected.sort(Comparator.comparing(McpTool::getName, String.CASE_INSENSITIVE_ORDER));
        tools = List.copyOf(collected);
        print("已获取 " + tools.size() + " 个工具" + pageSuffix(cursor));
        return tools;
    }

    private static String pageSuffix(String cursor) {
        return cursor == null || cursor.isBlank() ? "" : "（仍有后续分页）";
    }

    public List<McpResource> refreshResources() throws McpException {
        stage("正在拉取资源列表…");
        List<McpResource> list = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null && !cursor.isBlank()) {
                params.addProperty("cursor", cursor);
            }
            JsonObject result = request("resources/list", params);
            for (JsonElement e : JsonUtil.array(result, "resources")) {
                if (e.isJsonObject()) {
                    list.add(new McpResource(e.getAsJsonObject(), false));
                }
            }
            cursor = JsonUtil.str(result, "nextCursor", null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        list.sort(Comparator.comparing(McpResource::getUri, String.CASE_INSENSITIVE_ORDER));
        resources = List.copyOf(list);

        // 资源模板单独一个方法，失败不影响具体资源
        List<McpResource> templates = new ArrayList<>();
        try {
            JsonObject result = request("resources/templates/list", new JsonObject());
            for (JsonElement e : JsonUtil.array(result, "resourceTemplates")) {
                if (e.isJsonObject()) {
                    templates.add(new McpResource(e.getAsJsonObject(), true));
                }
            }
        } catch (McpException e) {
            if (!e.isMethodNotFound()) {
                print("拉取资源模板失败：" + e.getDisplayMessage());
            }
        }
        resourceTemplates = List.copyOf(templates);
        print("已获取 " + resources.size() + " 个资源、" + resourceTemplates.size() + " 个资源模板");
        return resources;
    }

    public List<McpPrompt> refreshPrompts() throws McpException {
        stage("正在拉取提示词列表…");
        List<McpPrompt> list = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 200; page++) {
            JsonObject params = new JsonObject();
            if (cursor != null && !cursor.isBlank()) {
                params.addProperty("cursor", cursor);
            }
            JsonObject result = request("prompts/list", params);
            for (JsonElement e : JsonUtil.array(result, "prompts")) {
                if (e.isJsonObject()) {
                    list.add(new McpPrompt(e.getAsJsonObject()));
                }
            }
            cursor = JsonUtil.str(result, "nextCursor", null);
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        list.sort(Comparator.comparing(McpPrompt::getName, String.CASE_INSENSITIVE_ORDER));
        prompts = List.copyOf(list);
        print("已获取 " + prompts.size() + " 个提示词");
        return prompts;
    }

    /** 重新拉一遍全部目录，弹一次通知级别的操作。 */
    public void refreshAll() throws McpException {
        refreshTools();
        try {
            refreshResources();
        } catch (McpException e) {
            if (!e.isMethodNotFound()) {
                throw e;
            }
        }
        try {
            refreshPrompts();
        } catch (McpException e) {
            if (!e.isMethodNotFound()) {
                throw e;
            }
        }
    }

    // ------------------------------------------------------------------
    // 调用
    // ------------------------------------------------------------------

    /** 调用工具。协议层失败不抛异常，而是落在 {@link McpCallResult#getError()} 上——调试工具要"看到失败"。 */
    public McpCallResult callTool(String name, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        params.add("arguments", arguments == null ? new JsonObject() : arguments);
        return invoke("tools/call", params, "工具 " + name);
    }

    /** 读取一个资源。 */
    public McpCallResult readResource(String uri) {
        JsonObject params = new JsonObject();
        params.addProperty("uri", uri);
        return invoke("resources/read", params, "资源 " + uri);
    }

    /** 取一个提示词（返回的 messages 在 {@link McpCallResult#getRaw()} 里）。 */
    public McpCallResult getPrompt(String name, JsonObject arguments) {
        JsonObject params = new JsonObject();
        params.addProperty("name", name);
        if (arguments != null && arguments.size() > 0) {
            params.add("arguments", arguments);
        }
        return invoke("prompts/get", params, "提示词 " + name);
    }

    private McpCallResult invoke(String method, JsonObject params, String what) {
        long started = System.nanoTime();
        McpCallResult result;
        try {
            stage("正在调用 " + what + " …");
            result = new McpCallResult(request(method, params));
        } catch (McpException e) {
            result = new McpCallResult(null);
            result.setError(e.getDisplayMessage());
        } catch (RuntimeException e) {
            result = new McpCallResult(null);
            result.setError("调用失败：" + McpException.describe(e));
        }
        result.setElapsedMillis((System.nanoTime() - started) / 1_000_000L);
        return result;
    }

    // ------------------------------------------------------------------
    // 请求
    // ------------------------------------------------------------------

    /** 发一条请求并返回 {@code result}；服务端返回 error 时抛 {@link McpException}。 */
    private JsonObject request(String method, JsonObject params) throws McpException {
        if (userClosedOrDead() && !"initialize".equals(method)) {
            throw new McpException("连接已断开，请重新连接");
        }
        long id = idSequence.getAndIncrement();
        JsonObject response = transport.request(JsonRpc.request(id, method, params), timeoutMillis());
        if (response == null) {
            throw new McpException("服务端对 " + method + " 没有返回任何响应");
        }
        if (JsonRpc.hasError(response)) {
            throw McpException.fromRpcResponse(method, response.getAsJsonObject("error"));
        }
        JsonElement result = response.get("result");
        if (result == null || result.isJsonNull()) {
            return new JsonObject();
        }
        if (!result.isJsonObject()) {
            // 极少数服务端会把 result 写成标量/数组，包一层省得上层到处判类型
            JsonObject wrapper = new JsonObject();
            wrapper.add("value", result);
            return wrapper;
        }
        return result.getAsJsonObject();
    }

    private boolean userClosedOrDead() {
        return closed || !transport.isAlive();
    }

    /**
     * 会话是否已经被 {@link #close()} 收掉。
     *
     * <p>已关闭的实例<b>不能复用</b>：底层传输的 userClosed 已经置位，任何请求都会
     * 立刻失败。上层拿到它时必须重新创建一个新的。
     */
    public boolean isClosed() {
        return closed;
    }

    private long timeoutMillis() {
        int seconds = config.getTimeoutSeconds() <= 0 ? 60 : config.getTimeoutSeconds();
        return seconds * 1000L;
    }

    // ------------------------------------------------------------------
    // 服务端主动请求
    // ------------------------------------------------------------------

    /**
     * 服务端发起的请求必须应答，否则对方可能一直等下去。
     *
     * <p>这里是 MCP 客户端里最容易被忽略的一环：只实现"客户端发、服务端收"是跑不通的，
     * 服务端在初始化时就会来问 {@code roots/list}（我们声明了 roots 能力）。
     */
    private void handleServerRequest(JsonObject message) {
        JsonElement id = JsonRpc.idOf(message);
        String method = JsonRpc.methodOf(message);
        JsonObject response;
        switch (method == null ? "" : method) {
            case "ping" -> response = JsonRpc.success(id, new JsonObject());
            case "roots/list" -> {
                JsonArray roots = new JsonArray();
                for (String uri : rootUris) {
                    JsonObject root = new JsonObject();
                    root.addProperty("uri", uri);
                    root.addProperty("name", lastSegment(uri));
                    roots.add(root);
                }
                JsonObject result = new JsonObject();
                result.add("roots", roots);
                response = JsonRpc.success(id, result);
                print("应答服务端 roots/list → " + roots.size() + " 个根目录");
            }
            default -> {
                response = JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND,
                        "MCP Debugger 未实现该方法：" + method);
                print("服务端请求了未实现的方法：" + method + "（已按协议返回 -32601）");
            }
        }
        try {
            transport.send(response);
        } catch (McpException e) {
            print("应答服务端请求 " + method + " 失败：" + e.getDisplayMessage());
        }
    }

    private static String lastSegment(String uri) {
        if (uri == null) {
            return "";
        }
        String s = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        int i = s.lastIndexOf('/');
        return i >= 0 && i + 1 < s.length() ? s.substring(i + 1) : s;
    }

    private void handleNotification(String method, JsonElement params) {
        if (method == null) {
            return;
        }
        switch (method) {
            case "notifications/message" -> {
                // 服务端的结构化日志，尽量还原成人能读的一行
                if (params != null && params.isJsonObject()) {
                    String level = JsonUtil.str(params.getAsJsonObject(), "level", "info");
                    JsonElement data = params.getAsJsonObject().get("data");
                    String text = data == null ? "" : (data.isJsonPrimitive() ? data.getAsString() : JsonUtil.compact(data));
                    print("服务端日志[" + level + "] " + JsonUtil.firstLine(text));
                }
            }
            case "notifications/tools/list_changed" -> print("服务端通知：工具列表已变化");
            case "notifications/resources/list_changed" -> print("服务端通知：资源列表已变化");
            case "notifications/prompts/list_changed" -> print("服务端通知：提示词列表已变化");
            case "notifications/resources/updated" -> print("服务端通知：资源已更新 "
                    + JsonUtil.firstLine(JsonUtil.compact(params)));
            default -> print("服务端通知：" + method);
        }
    }

    // ------------------------------------------------------------------
    // 其它
    // ------------------------------------------------------------------

    private final class Bridge implements McpTransport.Listener {

        @Override
        public void onSend(JsonObject message) {
            listeners.forEach(l -> l.onTraffic(true, message));
        }

        @Override
        public void onReceive(JsonObject message) {
            listeners.forEach(l -> l.onTraffic(false, message));
        }

        @Override
        public void onNotice(String text) {
            listeners.forEach(l -> l.onStderr(text));
        }

        @Override
        public void onProtocolMessage(JsonObject message) {
            if (JsonRpc.isNotification(message)) {
                String method = JsonRpc.methodOf(message);
                JsonElement params = message.get("params");
                listeners.forEach(l -> l.onServerNotification(method, params));
                handleNotification(method, params);
                return;
            }
            if (JsonRpc.isServerRequest(message)) {
                // 放到独立线程应答：当前线程可能正是"在等响应"的那条
                inbound.submit(() -> {
                    try {
                        handleServerRequest(message);
                    } catch (RuntimeException e) {
                        print("应答服务端请求时出错：" + JsonUtil.rootMessage(e));
                    }
                });
            }
        }

        @Override
        public void onClosed(String reason) {
            connected = false;
            listeners.forEach(l -> l.onLog("连接已断开：" + reason));
            listeners.forEach(l -> l.onDisconnected(reason));
        }
    }

    private void print(String text) {
        listeners.forEach(l -> l.onLog(text));
    }

    private void stage(String text) {
        listeners.forEach(l -> l.onStage(text));
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public McpServerInfo getServerInfo() {
        return serverInfo;
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

    public boolean isConnected() {
        return connected && transport.isAlive();
    }

    public boolean isAlive() {
        return transport.isAlive();
    }

    public void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /** 让 {@code roots/list} 能答出当前工程目录。 */
    public void setRootUris(List<String> uris) {
        this.rootUris = uris == null ? List.of() : List.copyOf(uris);
    }

    public void setClientVersion(String version) {
        if (version != null && !version.isBlank()) {
            this.clientVersion = version;
        }
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }

    private static ThreadFactory named(String prefix) {
        return r -> {
            Thread t = new Thread(r, prefix);
            t.setDaemon(true);
            return t;
        };
    }

    /** 便于测试/日志：当前会话的形态。 */
    @Override
    public String toString() {
        return "McpClient[" + config.getDisplayName() + " @ " + transport.describe() + "]";
    }
}
