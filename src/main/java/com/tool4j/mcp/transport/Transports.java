package com.tool4j.mcp.transport;

import com.tool4j.mcp.model.McpServerConfig;

/** 按配置创建对应的传输实现。 */
public final class Transports {

    private Transports() {
    }

    public static McpTransport create(McpServerConfig config) {
        return switch (config.getTransport()) {
            case STDIO -> new StdioTransport(config);
            case SSE -> new SseTransport(config);
            case STREAMABLE_HTTP -> new HttpTransport(config);
        };
    }
}
