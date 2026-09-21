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
            throw new McpException(I18n.t("err.http.noUrl"));
        }
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed);
            if (uri.getScheme() == null) {
                throw new McpException(I18n.t("err.http.noScheme", trimmed));
            }
        } catch (IllegalArgumentException e) {
            throw new McpException(I18n.t("err.http.badUrl", trimmed));
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
        reportNotice(I18n.t("err.http.endpoint", trimmed));
    }

    // ------------------------------------------------------------------

    @Override
    public JsonObject request(JsonObject request, long timeoutMillis) throws McpException {
        HttpResponse<InputStream> response = exchange(request, timeoutMillis);
        int status = response.statusCode();
        String contentType = header(response, "content-type").toLowerCase(Locale.ROOT);

        if (status >= 400) {
            String body = readAll(response.body());
            throw new McpException(body.isBlank()
                    ? I18n.t("err.http.callFailed", JsonRpc.methodOf(request), status)
                    : I18n.t("err.http.callFailedBody", JsonRpc.methodOf(request), status,
                            JsonUtil.firstLine(body)));
        }
        if (contentType.contains("text/event-stream")) {
            return readSseForResponse(response.body(), JsonRpc.methodOf(request), timeoutMillis);
        }

        String body = readAll(response.body());
        if (body.isBlank()) {
            throw new McpException(I18n.t("err.http.emptyBody", status, JsonRpc.methodOf(request)));
        }
        JsonElement parsed;
        try {
            parsed = JsonUtil.parse(body);
        } catch (RuntimeException e) {
            throw new McpException(I18n.t("err.http.badJson", JsonUtil.firstLine(body)));
        }
        if (!parsed.isJsonObject()) {
            throw new McpException(I18n.t("err.http.notObject", JsonUtil.firstLine(body)));
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
            throw new McpException(I18n.t("err.http.sendFailed", JsonRpc.methodOf(message), status));
        }
    }

    private HttpResponse<InputStream> exchange(JsonObject message, long timeoutMillis) throws McpException {
        if (http == null || url == null) {
            throw new McpException(I18n.t("err.http.notStarted"));
        }
        if (userClosed) {
            throw new McpException(I18n.t("err.common.closed"));
        }
        reportSend(message);
        HttpRequest httpRequest = buildRequest(message, timeoutMillis);
        try {
            HttpResponse<InputStream> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            String sid = header(response, SESSION_HEADER);
            if (!sid.isBlank()) {
                if (sessionId == null) {
                    reportNotice(I18n.t("err.http.sessionId", sid));
                }
                sessionId = sid;
            }
            return response;
        } catch (IOException e) {
            throw new McpException(I18n.t("err.http.requestFailed", describeIoFailure(e), url), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(I18n.t("err.http.requestInterrupted"));
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
                reportNotice(I18n.t("err.common.headerRejected", e.getKey()));
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
                            reportNotice(I18n.t("err.sse.unparsable", JsonUtil.firstLine(payload)));
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
                done.completeExceptionally(new McpException(I18n.t("err.sse.streamEnded", method)));
            } catch (IOException e) {
                done.completeExceptionally(new McpException(
                        I18n.t("err.sse.readFailed", JsonUtil.rootMessage(e)), e));
            } catch (RuntimeException e) {
                done.completeExceptionally(new McpException(
                        I18n.t("err.sse.handleFailed", JsonUtil.rootMessage(e)), e));
            }
        }).start();

        try {
            return done.get(Math.max(1000L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 关掉流，让还在 readLine 上阻塞的线程退出
            closeQuietly(ref.get());
            throw new McpException(I18n.t("err.sse.awaitTimeout", method, timeoutMillis / 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(I18n.t("err.sse.awaitInterrupted", method));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) {
                throw me;
            }
            throw new McpException(I18n.t("err.sse.awaitFailed", method, JsonUtil.rootMessage(e)), cause);
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
        failPending(I18n.t("err.common.closed"));
        http = null;
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }
}
