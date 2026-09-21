package com.tool4j.mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.model.KeyValue;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.model.TransportType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置的 JSON 互转。
 *
 * <p>这是插件里"最省事"的一块功能：用户手上基本都已经有一份能跑的配置了，
 * 没必要重新手敲 {@code npx -y @modelcontextprotocol/server-filesystem /data}。
 * 所以这里把各家客户端用过的写法都吃进来：
 *
 * <pre>
 * 1) Claude Desktop / Cursor / Roo      { "mcpServers": { "名字": { "command":.., "args":[..], "env":{..} } } }
 * 2) VS Code (.vscode/mcp.json)         { "servers":    { "名字": { "type":"stdio"|"http", "url":.., "headers":{..} } } }
 * 3) 裸映射                              { "名字": { ... } }
 * 4) 本插件导出的数组                     [ { "name":.., "transport":"stdio", ... } ]
 * </pre>
 *
 * <p>解析时不会因为某一条坏配置就整体失败：坏的跳过并记一条 warning，好的照常导入。
 */
public final class McpConfigParser {

    private McpConfigParser() {
    }

    /** 解析结果。 */
    public static final class Parsed {
        private final List<McpServerConfig> servers = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();

        public List<McpServerConfig> getServers() {
            return servers;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public boolean isEmpty() {
            return servers.isEmpty();
        }
    }

    public static Parsed parse(String jsonText) {
        Parsed parsed = new Parsed();
        String text = jsonText == null ? "" : jsonText.strip();
        if (text.isEmpty()) {
            parsed.warnings.add(I18n.t("cfg.warn.empty"));
            return parsed;
        }

        JsonElement root;
        try {
            root = JsonUtil.parseLenient(text);
        } catch (RuntimeException e) {
            // 直接用它自己的消息：JsonUtil.parseLenient 抛的 IllegalArgumentException 正文
            // 已经是「JSON 解析失败：<细节>」了，这里再拼一次前缀会变成"解析失败：解析失败：…"。
            parsed.warnings.add(JsonUtil.rootMessage(e));
            return parsed;
        }
        if (root == null || root.isJsonNull()) {
            parsed.warnings.add(I18n.t("cfg.warn.jsonEmpty"));
            return parsed;
        }

        if (root.isJsonArray()) {
            // 本插件导出的数组形态
            for (JsonElement e : root.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    parseFlatEntry(e.getAsJsonObject(), parsed);
                }
            }
            return parsed;
        }
        if (!root.isJsonObject()) {
            parsed.warnings.add(I18n.t("cfg.warn.notObjectOrArray"));
            return parsed;
        }

        JsonObject obj = root.getAsJsonObject();
        JsonObject map = null;
        if (obj.has("mcpServers") && obj.get("mcpServers").isJsonObject()) {
            map = obj.getAsJsonObject("mcpServers");
        } else if (obj.has("servers") && obj.get("servers").isJsonObject()) {
            map = obj.getAsJsonObject("servers");
        } else if (obj.has("mcp") && obj.get("mcp").isJsonObject()) {
            // 少数客户端用 mcp.servers
            JsonObject mcp = obj.getAsJsonObject("mcp");
            if (mcp.has("servers") && mcp.get("servers").isJsonObject()) {
                map = mcp.getAsJsonObject("servers");
            }
        }

        if (map != null) {
            for (Map.Entry<String, JsonElement> e : map.entrySet()) {
                if (e.getValue().isJsonObject()) {
                    parseNamedEntry(e.getKey(), e.getValue().getAsJsonObject(), parsed);
                } else {
                    parsed.warnings.add(I18n.t("cfg.warn.skippedNotObject", e.getKey()));
                }
            }
            return parsed;
        }

        // 裸映射：顶层每个字段都是一个服务器（前提是看不出它是别的元信息字段）
        boolean looksBare = true;
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            if (!e.getValue().isJsonObject()) {
                looksBare = false;
                break;
            }
        }
        if (looksBare && !obj.entrySet().isEmpty()) {
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                parseNamedEntry(e.getKey(), e.getValue().getAsJsonObject(), parsed);
            }
            return parsed;
        }

        // 也可能本身就是"单个服务器的配置"
        if (obj.has("command") || obj.has("url")) {
            parseNamedEntry(JsonUtil.str(obj, "name", "imported"), obj, parsed);
            return parsed;
        }

        parsed.warnings.add(I18n.t("cfg.warn.noServerMap"));
        return parsed;
    }

    // ------------------------------------------------------------------

    private static void parseNamedEntry(String name, JsonObject entry, Parsed parsed) {
        McpServerConfig config = new McpServerConfig();
        config.setName(name);
        if (!fill(config, entry, parsed, name)) {
            return;
        }
        parsed.servers.add(config);
    }

    /** 本插件导出的扁平数组元素：字段直接摊平在对象上。 */
    private static void parseFlatEntry(JsonObject entry, Parsed parsed) {
        McpServerConfig config = new McpServerConfig();
        config.setName(JsonUtil.str(entry, "name", "imported"));
        if (!fill(config, entry, parsed, config.getName())) {
            return;
        }
        parsed.servers.add(config);
    }

    private static boolean fill(McpServerConfig config, JsonObject entry, Parsed parsed, String label) {
        String command = JsonUtil.str(entry, "command", null);
        String url = JsonUtil.str(entry, "url", null);
        String typeHint = JsonUtil.str(entry, "transport", null);
        if (typeHint == null) {
            typeHint = JsonUtil.str(entry, "type", null);
        }

        TransportType transport;
        if (typeHint != null && !typeHint.isBlank()) {
            transport = TransportType.fromId(typeHint);
        } else {
            transport = (url != null && command == null) ? TransportType.STREAMABLE_HTTP : TransportType.STDIO;
        }

        if (transport == TransportType.STDIO && (command == null || command.isBlank())) {
            parsed.warnings.add(I18n.t("cfg.warn.skipNoCommand", label));
            return false;
        }
        if (transport != TransportType.STDIO && (url == null || url.isBlank())) {
            parsed.warnings.add(I18n.t("cfg.warn.skipNoUrl", label));
            return false;
        }

        config.setTransport(transport);
        if (command != null) {
            config.setCommand(command);
        }
        if (url != null) {
            config.setUrl(url);
        }
        config.setArgs(stringList(entry.get("args")));
        config.setEnv(keyValues(entry.get("env")));
        config.setHeaders(keyValues(entry.get("headers")));
        config.setWorkingDir(JsonUtil.str(entry, "cwd", JsonUtil.str(entry, "workingDir", "")));

        if (entry.has("disabled") && JsonUtil.bool(entry, "disabled", false)) {
            config.setEnabled(false);
        }
        if (entry.has("enabled")) {
            config.setEnabled(JsonUtil.bool(entry, "enabled", true));
        }
        int timeout = JsonUtil.intOr(entry, "timeoutSeconds", 0);
        if (timeout > 0) {
            config.setTimeoutSeconds(timeout);
        }
        String protocolVersion = JsonUtil.str(entry, "protocolVersion", null);
        if (protocolVersion != null) {
            config.setProtocolVersion(protocolVersion);
        }

        warnAboutPlaceholders(config, parsed, label);
        return true;
    }

    /** {@code ${VAR}} / {@code ${env:VAR}} 这类占位符我们不展开，得明确告诉用户，否则他会以为连不上是插件的问题。 */
    private static void warnAboutPlaceholders(McpServerConfig config, Parsed parsed, String label) {
        List<String> hits = new ArrayList<>();
        for (KeyValue kv : config.getEnv()) {
            if (kv.getValue() != null && kv.getValue().contains("${")) {
                hits.add(kv.getKey());
            }
        }
        for (KeyValue kv : config.getHeaders()) {
            if (kv.getValue() != null && kv.getValue().contains("${")) {
                hits.add(kv.getKey());
            }
        }
        for (String arg : config.getArgs()) {
            if (arg != null && arg.contains("${")) {
                hits.add("args");
                break;
            }
        }
        if (!hits.isEmpty()) {
            parsed.warnings.add(I18n.t("cfg.warn.placeholders", label, String.join("/", hits)));
        }
    }

    private static List<String> stringList(JsonElement element) {
        List<String> out = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return out;
        }
        if (element.isJsonPrimitive()) {
            // 有的配置把 args 写成单个字符串
            out.add(element.getAsString());
            return out;
        }
        if (element.isJsonArray()) {
            for (JsonElement e : element.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }

    private static List<KeyValue> keyValues(JsonElement element) {
        List<KeyValue> out = new ArrayList<>();
        if (element == null || !element.isJsonObject()) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            JsonElement v = e.getValue();
            out.add(new KeyValue(e.getKey(), v == null || v.isJsonNull() ? "" : (v.isJsonPrimitive() ? v.getAsString() : JsonUtil.compact(v))));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 导出
    // ------------------------------------------------------------------

    /** 导出成 Claude Desktop / Cursor 也能直接用的 {@code mcpServers} 形态。 */
    public static String toPrettyJson(List<McpServerConfig> servers) {
        JsonObject map = new JsonObject();
        for (McpServerConfig config : servers) {
            JsonObject entry = new JsonObject();
            entry.addProperty("type", config.getTransport().getId());
            if (config.getTransport() == TransportType.STDIO) {
                entry.addProperty("command", config.getCommand());
                if (!config.getArgs().isEmpty()) {
                    JsonArray args = new JsonArray();
                    config.getArgs().forEach(args::add);
                    entry.add("args", args);
                }
                if (!config.getEnv().isEmpty()) {
                    entry.add("env", toJsonObject(config.getEnv()));
                }
                if (config.getWorkingDir() != null && !config.getWorkingDir().isBlank()) {
                    entry.addProperty("cwd", config.getWorkingDir());
                }
            } else {
                entry.addProperty("url", config.getUrl());
                if (!config.getHeaders().isEmpty()) {
                    entry.add("headers", toJsonObject(config.getHeaders()));
                }
            }
            if (!config.isEnabled()) {
                entry.addProperty("disabled", true);
            }
            map.add(config.getDisplayName(), entry);
        }
        JsonObject root = new JsonObject();
        root.add("mcpServers", map);
        return JsonUtil.pretty(root);
    }

    private static JsonObject toJsonObject(List<KeyValue> pairs) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (KeyValue kv : pairs) {
            if (!kv.isEmpty()) {
                ordered.put(kv.getKey().trim(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
        JsonObject o = new JsonObject();
        ordered.forEach(o::addProperty);
        return o;
    }
}
