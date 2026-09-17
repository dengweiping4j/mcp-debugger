package com.tool4j.mcp.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

/**
 * Gson 的集中包装。
 *
 * <p>两个容易踩的点，在这里一次性处理掉：
 * <ul>
 *   <li><b>disableHtmlEscaping</b>：Gson 默认会把 {@code < > = '} 转成 {@code \u003c} 这种转义，
 *   展示报文时会变得不可读，这里全局关掉。</li>
 *   <li><b>宽松解析</b>：手工敲参数时经常出现单引号、不带引号的键、多余逗号，
 *   解析用户输入统一走 {@link #parseLenient(String)}。</li>
 * </ul>
 *
 * <p>报文序列化（{@link #compact(JsonElement)}）保持紧凑，方便在日志里和抓包结果对照。
 */
public final class JsonUtil {

    /** 走网络的形态：紧凑、不转义 HTML、不输出 null（null 字段在 JSON-RPC 里通常没必要发）。 */
    private static final Gson COMPACT = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    /** 给人看的形态：缩进 2 空格。 */
    private static final Gson PRETTY = new GsonBuilder()
            .disableHtmlEscaping()
            .setPrettyPrinting()
            .create();

    /** 解析用户输入：缩进输出 + 宽松语法。 */
    private static final Gson LENIENT = new GsonBuilder()
            .disableHtmlEscaping()
            .setLenient()
            .setPrettyPrinting()
            .create();

    private JsonUtil() {
    }

    public static String compact(JsonElement element) {
        return COMPACT.toJson(element);
    }

    public static String pretty(JsonElement element) {
        if (element == null) {
            return "";
        }
        return PRETTY.toJson(element);
    }

    /** 严格解析，用于解析服务端报文。 */
    public static JsonElement parse(String text) {
        return JsonParser.parseString(text);
    }

    /** 宽松解析，用于解析用户手写的 JSON。 */
    public static JsonElement parseLenient(String text) {
        return JsonParser.parseString(text);
    }

    /** 宽松解析成对象；不是对象就抛 {@link IllegalArgumentException}，附带人话提示。 */
    public static JsonObject parseObjectLenient(String text) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return new JsonObject();
        }
        JsonElement e;
        try {
            e = LENIENT.fromJson(t, JsonElement.class);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("JSON 解析失败：" + rootMessage(ex));
        }
        if (e == null || e.isJsonNull()) {
            return new JsonObject();
        }
        if (!e.isJsonObject()) {
            throw new IllegalArgumentException("需要一个 JSON 对象（以 { 开头），当前是 "
                    + (e.isJsonArray() ? "数组" : "标量"));
        }
        return e.getAsJsonObject();
    }

    /** 尝试解析，失败返回 null —— 用于"能解析就高亮、不能就按纯文本显示"的场景。 */
    public static JsonElement tryParse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        char c = t.charAt(0);
        if (c != '{' && c != '[' && c != '"' && c != '-' && !Character.isDigit(c)
                && t.charAt(0) != 't' && t.charAt(0) != 'f' && t.charAt(0) != 'n') {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(t);
            return e == null || e.isJsonNull() ? null : e;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public static String formatLenient(String text) {
        JsonElement e = tryParse(text);
        return e == null ? text : PRETTY.toJson(e);
    }

    // ------------------------------------------------------------------
    // 取值工具：MCP 各家的 result 里字段类型五花八门，取错类型不能炸
    // ------------------------------------------------------------------

    public static String str(JsonObject obj, String key, String def) {
        if (obj == null) {
            return def;
        }
        JsonElement e = obj.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) {
            return def;
        }
        String s = e.getAsString();
        return s == null || s.isEmpty() ? def : s;
    }

    public static boolean bool(JsonObject obj, String key, boolean def) {
        if (obj == null) {
            return def;
        }
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isBoolean()) {
            return def;
        }
        return e.getAsBoolean();
    }

    public static int intOr(JsonObject obj, String key, int def) {
        if (obj == null) {
            return def;
        }
        JsonElement e = obj.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return def;
        }
        try {
            return e.getAsInt();
        } catch (RuntimeException ex) {
            return def;
        }
    }

    /** 把 id 解析成 long；不是数字（服务端用了字符串 id）时返回 null。 */
    public static Long idAsLong(JsonElement id) {
        if (id == null || id.isJsonNull() || !id.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive p = id.getAsJsonPrimitive();
        try {
            if (p.isNumber()) {
                return p.getAsLong();
            }
            if (p.isString()) {
                return Long.parseLong(p.getAsString().trim());
            }
        } catch (RuntimeException ignored) {
            // 字符串 id 直接落到 null，交给调用方按字符串处理
        }
        return null;
    }

    public static JsonArray array(JsonObject obj, String key) {
        if (obj == null) {
            return new JsonArray();
        }
        JsonElement e = obj.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    public static JsonObject object(JsonObject obj, String key) {
        if (obj == null) {
            return null;
        }
        JsonElement e = obj.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static boolean isNull(JsonObject obj, String key) {
        return obj == null || !obj.has(key) || obj.get(key).isJsonNull();
    }

    /** 取首行并截断，用于列表副标题。 */
    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String t = text.strip();
        int nl = t.indexOf('\n');
        if (nl >= 0) {
            t = t.substring(0, nl).strip();
        }
        return t.length() > 120 ? t.substring(0, 117) + "…" : t;
    }

    public static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null || m.isBlank() ? cur.getClass().getSimpleName() : m;
    }

    /** 把多行文本压成一行，用于把报错塞进单行状态栏。 */
    public static String oneLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }
}
