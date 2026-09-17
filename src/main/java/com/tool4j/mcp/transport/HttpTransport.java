package com.tool4j.mcp.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Streamable HTTP 传输（MCP 2025-03-26 起）。
 *
 * <p>形态是"一次 POST 换一次回答"：客户端把 JSON-RPC 报文 POST 到同一个端点，
 * 服务端可以用 {@code application/json} 直接回一条报文，也可以用 {@code text/event-stream}
 * 回一小段 SSE 流（里面可能夹着服务端通知，最后才是我们要的响应）。
 * 握手时服务端可能通过 {@code Mcp-Session-Id} 响应头下发会话 id，之后每个请求都要带回去。
 *
 * <p>用 JDK 自带的 {@link HttpClient}，不引入额外的 HTTP 库；这也让 transport 包保持零第三方依赖。
 */
public class HttpTransport extends AbstractTransport {

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    /** 通知类报文的发送超时：它们不需要等业务响应，给短一点，避免卡住上层。 */
    private static final long SEND_TIMEOUT_MILLIS = 20_000L;

    private final McpServerConfig config;

    private volatile HttpClient http;
    private volatile String url;
    private volatile String sessionId;

    public HttpTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws McpException {
        String raw = config.getUrl();
        if (raw == null || raw.isBlank()) {
            throw new McpException("未配置服务端地址（URL）");
        }
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null) {
                throw new McpException("服务端地址缺少协议前缀，应形如 http://127.0.0.1:3000/mcp：" + trimmed);
            }
        } catch (IllegalArgumentException e) {
            throw new McpException("服务端地址不是合法 URL：" + trimmed);
        }
        this.url = trimmed;
        this.http = HttpClient.newBuilder()
                // 必须钉死 HTTP/1.1。JDK 的 HttpClient 默认走 HTTP/2，明文 http 下会先
                // 发一轮 h2c Upgrade 协商（Connection: Upgrade, HTTP2-Settings / Upgrade: h2c）。
                // 很多服务端不实现 h2c，其中最典型的是 Python 生态的 uvicorn（httptools/h11）：
                // 它解析不了这组头，会把请求体当成空的交给上层，于是 MCP 服务端报
                // "Parse error: Expecting value: line 1 column 1" 并回 400。
                // MCP 报文都很小，用不上 HTTP/2，钉 1.1 换来的是最大兼容性。
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        reportNotice("Streamable HTTP 端点：" + trimmed);
    }

    // ------------------------------------------------------------------

    @Override
    public JsonObject request(JsonObject request, long timeoutMillis) throws McpException {
        HttpResponse<InputStream> response = exchange(request, timeoutMillis);
        int status = response.statusCode();
        String contentType = header(response, "content-type").toLowerCase(Locale.ROOT);

        if (status >= 400) {
            String body = readAll(response.body());
            throw new McpException("调用 " + JsonRpc.methodOf(request) + " 失败：HTTP " + status
                    + (body.isBlank() ? "" : " — " + JsonUtil.firstLine(body)));
        }
        if (contentType.contains("text/event-stream")) {
            return readSseForResponse(response.body(), JsonRpc.methodOf(request), timeoutMillis);
        }

        String body = readAll(response.body());
        if (body.isBlank()) {
            throw new McpException("服务端返回 HTTP " + status + " 但没有响应体，无法完成 "
                    + JsonRpc.methodOf(request) + "（该端点可能需要以 SSE 方式连接）");
        }
        JsonElement parsed;
        try {
            parsed = JsonUtil.parse(body);
        } catch (RuntimeException e) {
            throw new McpException("服务端响应不是合法 JSON：" + JsonUtil.firstLine(body));
        }
        if (!parsed.isJsonObject()) {
            throw new McpException("服务端响应不是 JSON 对象：" + JsonUtil.firstLine(body));
        }
        JsonObject message = parsed.getAsJsonObject();
        handleIncoming(message);
        return message;
    }

    @Override
    public void send(JsonObject message) throws McpException {
        HttpResponse<InputStream> response = exchange(message, SEND_TIMEOUT_MILLIS);
        int status = response.statusCode();
        // 一定要把 body 读掉/关掉，否则连接不会归还池子
        readAll(response.body());
        if (status >= 400) {
            throw new McpException("发送 " + JsonRpc.methodOf(message) + " 失败：HTTP " + status);
        }
    }

    private HttpResponse<InputStream> exchange(JsonObject message, long timeoutMillis) throws McpException {
        if (http == null || url == null) {
            throw new McpException("HTTP 传输尚未启动");
        }
        if (userClosed) {
            throw new McpException("连接已关闭");
        }
        reportSend(message);
        HttpRequest httpRequest = buildRequest(message, timeoutMillis);
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String sid = header(response, SESSION_HEADER);
            if (!sid.isBlank()) {
                if (sessionId == null) {
                    reportNotice("服务端下发会话 id：" + sid);
                }
                sessionId = sid;
            }
            return response;
        } catch (IOException e) {
            throw new McpException("HTTP 请求失败：" + describeIoFailure(e)
                    + "\n地址：" + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("HTTP 请求被中断");
        }
    }

    private HttpRequest buildRequest(JsonObject message, long timeoutMillis) throws McpException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(1000L, timeoutMillis)))
                .header("Content-Type", "application/json")
                // 两个 Accept 都要给：服务端才知道它可以回 SSE 流
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.compact(message), StandardCharsets.UTF_8));

        for (Map.Entry<String, String> e : config.headerMap().entrySet()) {
            try {
                builder.header(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ex) {
                // JDK 禁止应用层设置 Connection / Host / Content-Length 之类的头
                reportNotice("请求头 " + e.getKey() + " 被 JDK 限制，已忽略");
            }
        }
        if (sessionId != null && !sessionId.isBlank()) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------
    // SSE 响应流
    // ------------------------------------------------------------------

    /**
     * 读一段 SSE 流，直到读到响应报文为止。
     *
     * <p>必须在独立线程里读：{@code HttpRequest.timeout} 只覆盖到响应头到达，
     * 之后读取 body 是不受它约束的，如果直接在调用线程上 {@code readLine}，
     * 服务端不吐数据就会永久挂住。这里的做法是异步读 + 到点关流解锁。
     */
    private JsonObject readSseForResponse(InputStream stream, String method, long timeoutMillis) throws McpException {
        AtomicReference<InputStream> ref = new AtomicReference<>(stream);
        CompletableFuture<JsonObject> done = new CompletableFuture<>();

        daemonThread("mcp-http-sse-" + shortId(), () -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        String payload = data.toString();
                        data.setLength(0);
                        if (payload.isBlank()) {
                            continue;
                        }
                        JsonElement parsed = JsonUtil.tryParse(payload);
                        if (parsed == null || !parsed.isJsonObject()) {
                            reportNotice("SSE 中出现无法解析的数据，已忽略：" + JsonUtil.firstLine(payload));
                            continue;
                        }
                        JsonObject message = parsed.getAsJsonObject();
                        boolean isResponse = JsonRpc.isResponse(message);
                        handleIncoming(message);
                        if (isResponse) {
                            done.complete(message);
                            return;
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    if (line.startsWith("data:")) {
                        if (data.length() > 0) {
                            data.append('\n');
                        }
                        data.append(stripLeadingSpace(line.substring(5)));
                    }
                }
                done.completeExceptionally(new McpException("SSE 流已结束，但没有收到 " + method + " 的响应"));
            } catch (IOException e) {
                done.completeExceptionally(new McpException("读取 SSE 响应失败：" + JsonUtil.rootMessage(e), e));
            } catch (RuntimeException e) {
                done.completeExceptionally(new McpException("处理 SSE 响应失败：" + JsonUtil.rootMessage(e), e));
            }
        }).start();

        try {
            return done.get(Math.max(1000L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 关掉流，让还在 readLine 上阻塞的线程退出
            closeQuietly(ref.get());
            throw new McpException("等待 " + method + " 的 SSE 响应超时（" + (timeoutMillis / 1000) + " 秒）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("等待 " + method + " 的响应被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) {
                throw me;
            }
            throw new McpException("等待 " + method + " 的响应失败：" + JsonUtil.rootMessage(e), cause);
        }
    }

    // ------------------------------------------------------------------

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse("");
    }

    private static String stripLeadingSpace(String s) {
        return s.startsWith(" ") ? s.substring(1) : s;
    }

    private static String readAll(InputStream in) {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    @Override
    public boolean isAlive() {
        return !userClosed && http != null;
    }

    @Override
    public String describe() {
        return "http · " + url;
    }

    @Override
    public void close() {
        userClosed = true;
        failPending("连接已关闭");
        http = null;
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }
}
