package com.tool4j.mcp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tool4j.mcp.protocol.JsonUtil;
import lombok.Getter;

/**
 * 一个 MCP 工具（来自 {@code tools/list} 返回的 tools 数组元素）。
 *
 * <p>只保留调试需要的东西：名字、标题、描述、输入/输出 Schema、注解。
 * 注解里的四个 hint 直接曝成布尔值，工具列表上按需显示成小标签。
 */
@Getter
public class McpTool {

    private final String name;
    private final String title;
    private final String description;
    /** 入参 JSON Schema，为空时视为 {@code {"type":"object","properties":{}}}。 */
    private final JsonObject inputSchema;
    /** 可选的结构化输出 Schema（MCP 2025-06-18 起）。 */
    private final JsonObject outputSchema;
    /** 原始 tool 对象，界面上"原始 JSON"页签直接展示它。 */
    private final JsonObject raw;

    public McpTool(JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw;
        this.name = JsonUtil.str(this.raw, "name", "(未命名工具)");
        this.title = JsonUtil.str(this.raw, "title", null);
        this.description = JsonUtil.str(this.raw, "description", null);
        this.inputSchema = asObject(this.raw.get("inputSchema"));
        this.outputSchema = asObject(this.raw.get("outputSchema"));
    }

    private static JsonObject asObject(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public boolean hasInputSchema() {
        return inputSchema != null && !inputSchema.entrySet().isEmpty();
    }

    private boolean hint(String key) {
        JsonObject ann = asObject(raw.get("annotations"));
        if (ann == null || !ann.has(key)) {
            return false;
        }
        JsonElement v = ann.get(key);
        return v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    /** 只读：不修改任何状态，可以放心反复调用。 */
    public boolean isReadOnly() {
        return hint("readOnlyHint");
    }

    /** 破坏性：可能删改数据，界面上要提醒。 */
    public boolean isDestructive() {
        return hint("destructiveHint");
    }

    public boolean isIdempotent() {
        return hint("idempotentHint");
    }

    public boolean isOpenWorld() {
        return hint("openWorldHint");
    }

    /** 列表里展示的副标题：优先 title，其次描述首行。 */
    public String getSubtitle() {
        if (title != null && !title.isBlank()) {
            return title;
        }
        return JsonUtil.firstLine(description);
    }

    @Override
    public String toString() {
        return name;
    }
}
