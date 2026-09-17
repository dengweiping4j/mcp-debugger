package com.tool4j.mcp.model;

import com.google.gson.JsonObject;
import com.tool4j.mcp.protocol.JsonUtil;
import lombok.Getter;

/** 一个 MCP 资源（来自 {@code resources/list} 或 {@code resources/templates/list}）。 */
@Getter
public class McpResource {

    private final String uri;
    private final String name;
    private final String title;
    private final String description;
    private final String mimeType;
    /** true 表示它是 URI 模板（来自 resources/templates/list），读取前需要先把占位符填掉。 */
    private final boolean template;
    private final JsonObject raw;

    public McpResource(JsonObject raw, boolean template) {
        this.raw = raw == null ? new JsonObject() : raw;
        this.template = template;
        String u = JsonUtil.str(this.raw, template ? "uriTemplate" : "uri", "");
        this.uri = u == null ? "" : u;
        this.name = JsonUtil.str(this.raw, "name", this.uri);
        this.title = JsonUtil.str(this.raw, "title", null);
        this.description = JsonUtil.str(this.raw, "description", null);
        this.mimeType = JsonUtil.str(this.raw, "mimeType", null);
    }

    public String getSubtitle() {
        if (mimeType != null && !mimeType.isBlank()) {
            return mimeType;
        }
        return JsonUtil.firstLine(description);
    }

    @Override
    public String toString() {
        return name == null ? uri : name;
    }
}
