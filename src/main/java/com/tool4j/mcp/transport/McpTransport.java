package com.tool4j.mcp.transport;

import com.google.gson.JsonObject;

import com.tool4j.mcp.protocol.McpException;

/**
 * 一条 MCP 连接的传输通道。
 *
 * <p>接口刻意做成"同步的 request / 异步的 send"：MCP 客户端逻辑（握手、翻页、调用）写起来像普通
 * 函数调用，而服务端反向请求（{@code roots/list}、{@code ping}）的应答又能立刻发出去。
 *
 * <p>三种实现的差异全部收在各自内部：
 * <ul>
 *   <li>{@link StdioTransport}：子进程 + 换行分隔 JSON-RPC</li>
 *   <li>{@link HttpTransport}：Streamable HTTP，一次 POST 换一次响应</li>
 *   <li>{@link SseTransport}：旧版 HTTP+SSE，GET 长连接收响应，POST 到 endpoint 发请求</li>
 * </ul>
 */
public interface McpTransport extends AutoCloseable {

    /** 传输层回调。所有回调都在各自的 IO 线程上执行，实现方不要在回调里做耗时操作。 */
    interface Listener {

        /** 即将发出一条报文（用于报文日志）。 */
        void onSend(JsonObject message);

        /** 收到一条报文（用于报文日志）。 */
        void onReceive(JsonObject message);

        /** 传输层自己的信息：子进程 stderr、非 JSON 输出、被忽略的请求头等。 */
        void onNotice(String text);

        /** 服务端主动发起的请求或通知（非响应）。 */
        void onProtocolMessage(JsonObject message);

        /** 连接被动断开（进程退出、SSE 断开）。主动 {@link #close()} 不触发。 */
        void onClosed(String reason);
    }

    /** 建立连接（stdio 启动子进程、HTTP 校验地址、SSE 拿到 messages 端点）。 */
    void start() throws McpException;

    /**
     * 发一条请求并等待它的响应。已经是 JSON-RPC 响应对象，可能带 {@code error} 字段，
     * 由调用方决定怎么处理。
     *
     * @param request       已带 {@code id} 的请求报文
     * @param timeoutMillis 等待响应的超时
     */
    JsonObject request(JsonObject request, long timeoutMillis) throws McpException;

    /** 发一条不需要响应的报文（通知，或对服务端请求的应答）。 */
    void send(JsonObject message) throws McpException;

    /** 通道是否还活着。HTTP 是无状态的，只要没 close 就算活着。 */
    boolean isAlive();

    /** 供日志/界面显示的一句话描述，例如 {@code stdio · npx -y server-filesystem}。 */
    String describe();

    void setListener(Listener listener);

    @Override
    void close();
}
