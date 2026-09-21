package com.tool4j.mcp.model;

import com.tool4j.mcp.i18n.I18n;

/**
 * MCP 支持的三种传输方式。
 *
 * <p>注意枚举常量名会被持久化到 XML 配置里，重命名会导致用户已保存的配置读不出来，
 * 需要改名时必须同时提供 {@link #fromId(String)} 的兼容分支。
 */
public enum TransportType {

    /** 本地子进程，通过 stdin/stdout 交换换行分隔的 JSON-RPC 报文。 */
    STDIO("stdio", "model.transport.stdio"),

    /** MCP 2025-03-26 引入的 Streamable HTTP：单一端点，POST 请求，响应可以是 JSON 或 SSE 流。 */
    STREAMABLE_HTTP("http", "model.transport.http"),

    /** 旧版 HTTP+SSE：先 GET 一条 SSE 长连接拿到 messages 端点，再往该端点 POST 请求。 */
    SSE("sse", "model.transport.sse");

    private final String id;
    /**
     * 显示名的词表键。
     *
     * <p><b>存键，不存翻好的文案。</b>枚举常量只在类初始化时构造一次，把文案存进字段就等于
     * 把首次用到它时的语言冻住——之后无论怎么切语言，下拉框里都还是老文案。
     * 这是"双语改造"里最典型的一处漏水，凡是 `static final` 或枚举字段上挂文案都要按这条处理。
     */
    private final String displayKey;

    TransportType(String id, String displayKey) {
        this.id = id;
        this.displayKey = displayKey;
    }

    public String getDisplayName() {
        return I18n.t(displayKey);
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
        return getDisplayName();
    }
}
