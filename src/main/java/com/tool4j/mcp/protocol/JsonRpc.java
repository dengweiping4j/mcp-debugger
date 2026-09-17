package com.tool4j.mcp.protocol;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * JSON-RPC 2.0 报文构造/识别。
 *
 * <p>只做 MCP 用到的那部分：请求、通知、成功响应、错误响应、以及"这条报文是什么"的判定。
 * 判定规则来自规范：带 {@code method} 的是请求或通知（带 {@code id} 才是请求），
 * 不带 {@code method} 的是响应。
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    // ---- 规范定义的标准错误码 ----
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    // ---- MCP 自己的错误码（工具执行失败等，落在 JSON-RPC 保留区之外） ----
    public static final int MCP_RESOURCE_NOT_FOUND = -32002;

    private JsonRpc() {
    }

    public static JsonObject request(long id, String method, JsonElement params) {
        JsonObject o = envelope();
        o.addProperty("id", id);
        o.addProperty("method", method);
        if (params != null && !params.isJsonNull()) {
            o.add("params", params);
        }
        return o;
    }

    public static JsonObject notification(String method, JsonElement params) {
        JsonObject o = envelope();
        o.addProperty("method", method);
        if (params != null && !params.isJsonNull()) {
            o.add("params", params);
        }
        return o;
    }

    public static JsonObject success(JsonElement id, JsonElement result) {
        JsonObject o = envelope();
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("result", result == null ? new JsonObject() : result);
        return o;
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        return error(id, code, message, null);
    }

    public static JsonObject error(JsonElement id, int code, String message, JsonElement data) {
        JsonObject err = new JsonObject();
        err.addProperty("code", code);
        err.addProperty("message", message == null ? "" : message);
        if (data != null && !data.isJsonNull()) {
            err.add("data", data);
        }
        JsonObject o = envelope();
        o.add("id", id == null ? JsonNull.INSTANCE : id);
        o.add("error", err);
        return o;
    }

    private static JsonObject envelope() {
        JsonObject o = new JsonObject();
        o.addProperty("jsonrpc", VERSION);
        return o;
    }

    // ------------------------------------------------------------------
    // 判定
    // ------------------------------------------------------------------

    /** 带 method、不带 id → 通知。 */
    public static boolean isNotification(JsonObject m) {
        return m != null && m.has("method") && !hasId(m);
    }

    /** 带 method、带 id → 服务端发起的请求。 */
    public static boolean isServerRequest(JsonObject m) {
        return m != null && m.has("method") && hasId(m);
    }

    /** 不带 method → 响应。 */
    public static boolean isResponse(JsonObject m) {
        return m != null && !m.has("method") && m.has("id");
    }

    public static boolean hasId(JsonObject m) {
        if (m == null || !m.has("id")) {
            return false;
        }
        JsonElement id = m.get("id");
        return id != null && !id.isJsonNull();
    }

    public static JsonElement idOf(JsonObject m) {
        return m == null ? null : m.get("id");
    }

    public static Long idAsLong(JsonObject m) {
        return JsonUtil.idAsLong(idOf(m));
    }

    public static String methodOf(JsonObject m) {
        if (m == null) {
            return null;
        }
        JsonElement e = m.get("method");
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    public static boolean hasError(JsonObject response) {
        return response != null && response.has("error") && response.get("error").isJsonObject();
    }

    /** 把 {@code error} 对象转成一句人话。 */
    public static String describeError(JsonObject error) {
        if (error == null) {
            return "未知错误";
        }
        String msg = JsonUtil.str(error, "message", "");
        Integer code = error.has("code") && error.get("code").isJsonPrimitive()
                ? error.get("code").getAsInt() : null;
        StringBuilder sb = new StringBuilder();
        if (code != null) {
            sb.append("[").append(code).append("] ");
        }
        sb.append(msg.isBlank() ? "服务端返回错误" : msg);
        JsonElement data = error.get("data");
        if (data != null && !data.isJsonNull()) {
            String d = data.isJsonPrimitive() ? data.getAsString() : JsonUtil.compact(data);
            if (d != null && !d.isBlank() && !msg.contains(d)) {
                sb.append(" — ").append(JsonUtil.firstLine(d));
            }
        }
        return sb.toString();
    }

    /** 方法名 → 规范里的中文说明，界面提示用。 */
    public static String describeErrorCode(int code) {
        return switch (code) {
            case PARSE_ERROR -> "报文解析失败";
            case INVALID_REQUEST -> "请求格式非法";
            case METHOD_NOT_FOUND -> "服务端不支持该方法";
            case INVALID_PARAMS -> "参数非法";
            case INTERNAL_ERROR -> "服务端内部错误";
            case MCP_RESOURCE_NOT_FOUND -> "资源不存在";
            default -> "错误";
        };
    }
}
