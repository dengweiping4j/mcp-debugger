package com.tool4j.mcp.protocol;

import com.google.gson.JsonObject;

/**
 * MCP 调用过程中所有可预期的失败。
 *
 * <p>刻意保留 {@link #getCode()}：JSON-RPC 错误码是排障时最有信息量的东西
 * （{@code -32601} 说明方法不存在，而不是服务器挂了），界面会把它显示出来。
 * {@code code == 0} 表示这不是协议层错误，而是本地问题（进程起不来、超时、网络不通）。
 */
public class McpException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 本地/传输层错误（非 JSON-RPC 错误码）。 */
    public static final int LOCAL = 0;

    private final int code;
    private final boolean methodNotFound;

    public McpException(String message) {
        this(LOCAL, message, null);
    }

    public McpException(String message, Throwable cause) {
        this(LOCAL, message, cause);
    }

    public McpException(int code, String message) {
        this(code, message, null);
    }

    public McpException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.methodNotFound = code == JsonRpc.METHOD_NOT_FOUND;
    }

    public int getCode() {
        return code;
    }

    /** 服务端不认识这个方法——拉资源/提示词时遇到它可以直接忽略。 */
    public boolean isMethodNotFound() {
        return methodNotFound;
    }

    public static McpException fromRpcResponse(String method, JsonObject error) {
        int code = JsonUtil.intOr(error, "code", JsonRpc.INTERNAL_ERROR);
        String prefix = "调用 " + method + " 失败（" + JsonRpc.describeErrorCode(code) + "）：";
        // 用 JsonRpc.describeError 拼消息，error.data 里服务端放的校验细节才不会丢
        return new McpException(code, prefix + JsonRpc.describeError(error), null);
    }

    /** 界面/日志里显示用的完整描述。 */
    public String getDisplayMessage() {
        if (code == LOCAL) {
            return getMessage();
        }
        return getMessage() + "（JSON-RPC " + code + "）";
    }

    /**
     * 把一个异常整理成给用户看的<b>一句话</b>。界面和测试统一走这里。
     *
     * <p>为什么不能直接用 {@link JsonUtil#rootMessage}：它取的是最深的 cause，
     * 而深层 cause 往往是 {@code ClosedChannelException}、{@code SocketException}
     * 这种纯 JDK 类型——类名对用户没有任何信息量。而 {@code McpException} 携带的消息
     * 是沿途一层层写清楚的（哪个方法、什么错误码、连的哪个地址），
     * 用深层 cause 去覆盖它等于把最有用的信息扔了。
     *
     * <p>所以：链上只要有 {@code McpException} 就用它的消息，否则才退回最深的 cause
     * （比如纯 IO 异常，最深的那个通常最具体）。
     */
    public static String describe(Throwable t) {
        for (Throwable cur = t; cur != null && cur != cur.getCause(); cur = cur.getCause()) {
            if (cur instanceof McpException me) {
                return me.getDisplayMessage();
            }
        }
        return JsonUtil.rootMessage(t);
    }
}
