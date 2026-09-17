package com.tool4j.mcp.model;

/**
 * MCP 支持的三种传输方式。
 *
 * <p>注意枚举常量名会被持久化到 XML 配置里，重命名会导致用户已保存的配置读不出来，
 * 需要改名时必须同时提供 {@link #fromId(String)} 的兼容分支。
 */
public enum TransportType {

    /** 本地子进程，通过 stdin/stdout 交换换行分隔的 JSON-RPC 报文。 */
    STDIO("stdio · 本地子进程", "stdio"),

    /** MCP 2025-03-26 引入的 Streamable HTTP：单一端点，POST 请求，响应可以是 JSON 或 SSE 流。 */
    STREAMABLE_HTTP("http · Streamable HTTP", "http"),

    /** 旧版 HTTP+SSE：先 GET 一条 SSE 长连接拿到 messages 端点，再往该端点 POST 请求。 */
    SSE("sse · 旧版 HTTP+SSE", "sse");

    private final String displayName;
    private final String id;

    TransportType(String displayName, String id) {
        this.displayName = displayName;
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** 写进 JSON 配置时的 type 值，与 Claude Desktop / Cursor / VS Code 的写法保持一致。 */
    public String getId() {
        return id;
    }

    public boolean isHttp() {
        return this != STDIO;
    }

    /** 宽松解析：兼容 {@code stdio} / {@code sse} / {@code http} / {@code streamable-http} / {@code streamableHttp}。 */
    public static TransportType fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return STDIO;
        }
        String v = raw.trim().toLowerCase().replace("_", "").replace("-", "");
        return switch (v) {
            case "sse" -> SSE;
            case "http", "streamablehttp", "streamable" -> STREAMABLE_HTTP;
            default -> STDIO;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
