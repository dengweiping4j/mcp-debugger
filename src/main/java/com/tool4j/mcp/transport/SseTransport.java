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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 */
public class SseTransport extends AbstractTransport {

    private static final Duration ENDPOINT_WAIT = Duration.ofSeconds(25);

    private final McpServerConfig config;

    private volatile HttpClient http;
    private volatile String sseUrl;
    private volatile String messageEndpoint;
    private volatile InputStream stream;

    private final CountDownLatch endpointReady = new CountDownLatch(1);
    private volatile boolean endpointFailed;
    private volatile String endpointFailure = "";

    public SseTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws McpException {
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
        applyHeaders(builder);

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

        this.stream = response.body();
        reportNotice(I18n.t("err.sse.connected", sseUrl));
        daemonThread("mcp-sse-reader-" + shortId(), this::readLoop).start();

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
        reportNotice(I18n.t("err.sse.endpoint", messageEndpoint));
    }

    // ------------------------------------------------------------------

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
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
            if (!userClosed) {
                reportNotice(I18n.t("err.sse.readError", JsonUtil.rootMessage(e)));
            }
        }
        // 到这里说明长连接断了：endpoint 还没等到也要放行，让 start() 报错而不是干等
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
        ensureEndpoint();
        Long id = JsonRpc.idAsLong(request);
        if (id == null) {
            throw new McpException(I18n.t("err.common.missingId", "SSE"));
        }
        String method = JsonRpc.methodOf(request);
        CompletableFuture<JsonObject> box = register(id);
        try {
            post(request, timeoutMillis);
        } catch (McpException e) {
            unregister(id);
            throw e;
        }
        return await(id, box, timeoutMillis, method);
    }

    @Override
    public void send(JsonObject message) throws McpException {
        ensureEndpoint();
        post(message, 20_000L);
    }

    private void post(JsonObject message, long timeoutMillis) throws McpException {
        reportSend(message);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(messageEndpoint))
                .timeout(Duration.ofMillis(Math.max(1000L, timeoutMillis)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtil.compact(message), StandardCharsets.UTF_8));
        applyHeaders(builder);
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

    private void applyHeaders(HttpRequest.Builder builder) {
        for (Map.Entry<String, String> e : config.headerMap().entrySet()) {
            try {
                builder.header(e.getKey(), e.getValue());
            } catch (IllegalArgumentException ex) {
                reportNotice(I18n.t("err.common.headerRejected", e.getKey()));
            }
        }
    }

    private void ensureEndpoint() throws McpException {
        if (userClosed) {
            throw new McpException(I18n.t("err.common.closed"));
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
        return !userClosed && messageEndpoint != null;
    }

    @Override
    public String describe() {
        return "sse · " + sseUrl;
    }

    @Override
    public void close() {
        userClosed = true;
        failPending(I18n.t("err.common.closed"));
        closeQuietly(stream);
        endpointReady.countDown();
        stream = null;
        http = null;
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }
}
