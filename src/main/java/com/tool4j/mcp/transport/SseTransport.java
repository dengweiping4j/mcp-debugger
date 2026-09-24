package com.tool4j.mcp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.protocol.JsonRpc;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 旧版 HTTP+SSE 传输（MCP 2024-11-05 的形态，2025-03-26 起被 Streamable HTTP 取代）。
 *
 * <p>流程是两条连接：
 * <ol>
 *   <li>GET 服务端地址并声明 {@code Accept: text/event-stream}，服务端先推一个
 *       {@code event: endpoint} 事件告诉我们"消息往哪发"（通常是带 sessionId 的相对路径）；</li>
 *   <li>请求 POST 到那个 endpoint（一般是 202 空响应），真正的响应从刚才那条 SSE 长连接上回来。</li>
 * </ol>
 *
 * <p>所以这条通道天生需要"长连接读线程 + 请求响应配对"，跟 stdio 共用了 {@link AbstractTransport} 的机制。
 *
 * <p><b>存活判据</b>是那条 GET 长连接本身（{@link #sseAlive}），不是"拿到过 endpoint"。
 * 长连接被对端（服务端、反向代理、本地代理）关掉时 endpoint 字段还在，只看它会让传输层
 * 坚称自己活着——上层于是把死会话原样还回来，用户点"重连"必然先失败一次。
 */
public class SseTransport extends AbstractTransport {

    private static final Duration ENDPOINT_WAIT = Duration.ofSeconds(25);

    private final McpServerConfig config;

    private volatile HttpClient http;
    private volatile String sseUrl;
    private volatile String messageEndpoint;
    private volatile InputStream stream;

    /** 端点事件只等一次；每次 {@link #start()} 都换一个新的 latch（同一个实例可重入）。 */
    private volatile CountDownLatch endpointReady = new CountDownLatch(1);
    private volatile boolean endpointFailed;
    private volatile String endpointFailure = "";

    /**
     * GET 长连接的读线程是否还在跑——<b>这才是 SSE 的存活判据</b>。
     *
     * <p>{@code messageEndpoint != null} 不能当判据：断线之后那个字段依然在，
     * 于是"已断开"的传输会被当成"还活着"，上面的会话层把死实例原样还回来。
     */
    private volatile boolean sseAlive;

    /**
     * 连接代次。每次 {@link #start()} 自增，读线程记住自己启动时的那一代，
     * 退出时只有"自己仍是当前代"才允许改状态 / 上报断开。
     *
     * <p>没有它就会这样：重连时关掉旧流，旧读线程从 {@code readLine} 里抛出来，
     * 把这次<b>主动</b>重连报成一条"连接已断开"，顺带把刚置活的 {@code sseAlive} 又抹成 false。
     */
    private final AtomicLong generation = new AtomicLong();

    public SseTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws McpException {
        // 可重入：先把上一次会话的残留拆干净。不这么做的话，endpointReady 是个已经放行的
        // latch（await 立刻返回）、messageEndpoint 还是上一个会话的地址、userClosed /
        // closedReported 还停在"已关"——initialize 会带着旧 endpoint 发出去，服务端当孤儿
        // 丢掉就是干等超时，于是"重连必先失败一次"。
        long gen = resetSession();

        String raw = config.getUrl();
        if (raw == null || raw.isBlank()) {
            throw new McpException(I18n.t("err.sse.noUrl"));
        }
        this.sseUrl = raw.trim();
        try {
            URI uri = URI.create(sseUrl);
            if (uri.getScheme() == null) {
                throw new McpException(I18n.t("err.sse.noScheme", sseUrl));
            }
        } catch (IllegalArgumentException e) {
            throw new McpException(I18n.t("err.sse.badUrl", sseUrl));
        }

        this.http = HttpClient.newBuilder()
                // 同 HttpTransport：必须钉死 HTTP/1.1，否则 JDK 会先做 h2c Upgrade 协商，
                // 而 uvicorn 一类服务端解析不了这组头（表现为请求体被当成空的）。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(sseUrl))
                .header("Accept", "text/event-stream")
                .GET();
        // 建连时还不知道要调哪个工具，所以 GET 只带服务器级请求头
        applyHeaders(builder, null);

        HttpResponse<InputStream> response;
        try {
            // 这条 GET 是长连接，不能设超时：HttpRequest.timeout 会跟着流一起生效
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new McpException(I18n.t("err.sse.connectFailed", describeIoFailure(e), sseUrl), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(I18n.t("err.sse.connectInterrupted"));
        }

        int status = response.statusCode();
        if (status >= 400) {
            String body = new String(safeReadAll(response.body()), StandardCharsets.UTF_8);
            String detail = body.isBlank() ? "" : I18n.t("err.sse.badStatusDetail", JsonUtil.firstLine(body));
            throw new McpException(I18n.t("err.sse.badStatus", status, detail));
        }

        InputStream body = response.body();
        this.stream = body;
        this.sseAlive = true;
        reportNotice(I18n.t("err.sse.connected", sseUrl));
        // 流当参数传进去，让读线程读自己的那一份：只用字段的话，线程已创建但还没被调度进来时
        // 撞上 close() / 下一次 start() 把 stream 置成 null，就是一次 NullPointerException
        // （不是 IOException，下面那个 catch 接不住，只会打到未捕获处理器上）。
        daemonThread("mcp-sse-reader-" + shortId(), () -> readLoop(gen, body)).start();

        try {
            if (!endpointReady.await(ENDPOINT_WAIT.toSeconds(), TimeUnit.SECONDS)) {
                close();
                throw new McpException(I18n.t("err.sse.endpointTimeout", ENDPOINT_WAIT.toSeconds()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new McpException(I18n.t("err.sse.endpointInterrupted"));
        }

        if (endpointFailed) {
            close();
            throw new McpException(I18n.t("err.sse.noEndpoint", endpointFailure));
        }
        // 拿到了 endpoint，但长连接可能在这期间就断了——别报一条"成功"，交给下一次请求去说
        if (!sseAlive) {
            close();
            throw new McpException(I18n.t("err.sse.disconnected"));
        }
        reportNotice(I18n.t("err.sse.endpoint", messageEndpoint));
    }

    // ------------------------------------------------------------------

    /**
     * 拆掉上一次会话，并把"一次性"的状态复位，返回新的代次。
     *
     * <p>顺序有意如此：<b>先置 {@code userClosed}</b> 再关流，让还在 {@code readLine} 上
     * 阻塞的旧读线程安静退出（它醒来时看到 gen 已经不是自己那一代，直接返回）；
     * 否则它会往日志里丢一条误导性的"读取异常 / 连接已断开"。
     */
    private long resetSession() {
        long gen = generation.incrementAndGet();
        sseAlive = false;
        userClosed = true;
        closeQuietly(stream);
        stream = null;
        http = null;
        messageEndpoint = null;
        endpointFailed = false;
        endpointFailure = "";
        endpointReady = new CountDownLatch(1);
        resetClosedState();
        return gen;
    }

    private void readLoop(long gen, InputStream source) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatchEvent(event, data.toString());
                    event = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue; // 心跳注释
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // id: / retry: 这里用不到
                    }
                }
            }
        } catch (IOException e) {
            if (generation.get() != gen) {
                return; // 上一代的读线程，正被 start() 拆掉，不是断线
            }
            if (!userClosed) {
                reportNotice(I18n.t("err.sse.readError", JsonUtil.rootMessage(e)));
            }
        }
        if (generation.get() != gen) {
            return; // 同上：这次的退出属于已经被取代的旧连接
        }
        // 到这里说明长连接断了（或正在被我们拆）：endpoint 还没等到也要放行，
        // 让 start() 报错而不是干等
        sseAlive = false;
        endpointReady.countDown();
        if (!userClosed) {
            String reason = I18n.t("err.sse.disconnected");
            failPending(reason);
            reportClosedOnce(reason);
        }
    }

    private void dispatchEvent(String event, String data) {
        if (data.isBlank()) {
            return;
        }
        if ("endpoint".equals(event)) {
            String resolved = resolveEndpoint(data.trim());
            if (resolved.isBlank()) {
                endpointFailed = true;
                endpointFailure = I18n.t("err.sse.endpointEmpty");
            } else {
                messageEndpoint = resolved;
            }
            endpointReady.countDown();
            return;
        }
        if (event != null && !"message".equals(event)) {
            reportNotice(I18n.t("err.sse.unknownEvent", event, JsonUtil.firstLine(data)));
            return;
        }
        // event 为空或 message：按 JSON-RPC 报文处理
        JsonElement parsed = JsonUtil.tryParse(data);
        if (parsed == null || !parsed.isJsonObject()) {
            reportNotice(I18n.t("err.sse.unparsable", JsonUtil.firstLine(data)));
            return;
        }
        handleIncoming(parsed.getAsJsonObject());
    }

    /** 服务端给的 endpoint 往往是相对路径，按 SSE 地址解析成绝对地址。 */
    private String resolveEndpoint(String data) {
        try {
            return URI.create(sseUrl).resolve(data).toString();
        } catch (IllegalArgumentException e) {
            return data;
        }
    }

    // ------------------------------------------------------------------

    @Override
    public JsonObject request(JsonObject request, long timeoutMillis) throws McpException {
        return request(request, timeoutMillis, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code extraHeaders} 是这一次 {@code tools/call} 的工具级请求头；它只影响这次 POST。
     * 响应仍旧从那条 GET 长连接上回来，所以工具级头加在 POST 上是能到服务端的
     * （服务端已经把这次请求和它那条 SSE 流关联起来了）。
     */
    @Override
    public JsonObject request(JsonObject request, long timeoutMillis, Map<String, String> extraHeaders)
            throws McpException {
        ensureEndpoint();
        Long id = JsonRpc.idAsLong(request);
        if (id == null) {
            throw new McpException(I18n.t("err.common.missingId", "SSE"));
        }
        String method = JsonRpc.methodOf(request);
        CompletableFuture<JsonObject> box = register(id);
        try {
            post(request, timeoutMillis, extraHeaders);
        } catch (McpException e) {
            unregister(id);
            throw e;
        }
        return await(id, box, timeoutMillis, method);
    }

    @Override
    public void send(JsonObject message) throws McpException {
        ensureEndpoint();
        post(message, 20_000L, null);
    }

    private void post(JsonObject message, long timeoutMillis, Map<String, String> extraHeaders)
            throws McpException {
        reportSend(message);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(messageEndpoint))
                .timeout(Duration.ofMillis(Math.max(1000L, timeoutMillis)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.compact(message), StandardCharsets.UTF_8));
        applyHeaders(builder, extraHeaders);
        try {
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            // 旧版规范里 POST 的响应体是空的（消息从 SSE 流回来），读掉即可
            safeReadAll(response.body());
            int status = response.statusCode();
            if (status >= 400) {
                throw new McpException(I18n.t("err.sse.postFailed", JsonRpc.methodOf(message), status));
            }
        } catch (IOException e) {
            throw new McpException(I18n.t("err.sse.postIoFailed", describeIoFailure(e)), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(I18n.t("err.sse.postInterrupted"));
        }
    }

    /**
     * 加请求头：服务器级打底，工具级（{@code extraHeaders}）同名覆盖。
     *
     * <p>用 {@code setHeader} 而不是 {@code header}：后者是追加，同一个键会出现两个值，
     * 而"覆盖"才是这里的语义。
     */
    private void applyHeaders(HttpRequest.Builder builder, Map<String, String> extraHeaders) {
        Map<String, String> merged = new LinkedHashMap<>(config.headerMap());
        if (extraHeaders != null) {
            merged.putAll(extraHeaders);
        }
        for (Map.Entry<String, String> e : merged.entrySet()) {
            try {
                builder.setHeader(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ex) {
                reportNotice(I18n.t("err.common.headerRejected", e.getKey()));
            }
        }
    }

    private void ensureEndpoint() throws McpException {
        if (userClosed) {
            throw new McpException(I18n.t("err.common.closed"));
        }
        if (!sseAlive) {
            // 长连接已经断了。以前这里只看 endpoint 在不在，于是断线之后每个请求都白跑一趟
            // 才开始失败——现在第一句话就说清楚。
            throw new McpException(I18n.t("err.sse.disconnected"));
        }
        if (messageEndpoint == null || messageEndpoint.isBlank()) {
            throw new McpException(I18n.t("err.sse.noEndpointYet"));
        }
    }

    private static byte[] safeReadAll(InputStream in) {
        if (in == null) {
            return new byte[0];
        }
        try (InputStream stream = in) {
            return stream.readAllBytes();
        } catch (IOException e) {
            return new byte[0];
        }
    }

    @Override
    public boolean isAlive() {
        // 只认长连接本身。看 endpoint 在不在是错的：断线后它还在（见 sseAlive 的注释）。
        return !userClosed && sseAlive;
    }

    @Override
    public String describe() {
        return "sse · " + sseUrl;
    }

    @Override
    public void close() {
        userClosed = true;
        // 先让代次过期，再关流：还在 readLine 上的读线程醒来后不会再改状态、也不会误报断线
        generation.incrementAndGet();
        sseAlive = false;
        failPending(I18n.t("err.common.closed"));
        closeQuietly(stream);
        endpointReady.countDown();
        stream = null;
        messageEndpoint = null;
        http = null;
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }
}
