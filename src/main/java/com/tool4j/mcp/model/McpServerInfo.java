package com.tool4j.mcp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tool4j.mcp.protocol.JsonUtil;
import lombok.Getter;

/** {@code initialize} 的协商结果。 */
@Getter
public class McpServerInfo {

    private final String serverName;
    private final String serverVersion;
    /** 服务端最终选定的协议版本（可能与我们请求的不同，那是合法的）。 */
    private final String protocolVersion;
    /** 服务端给模型的使用说明，有些服务端会塞很多内容进来。 */
    private final String instructions;
    private final JsonObject capabilities;

    public McpServerInfo(JsonObject initResult) {
        JsonObject r = initResult == null ? new JsonObject() : initResult;
        JsonObject info = r.has("serverInfo") && r.get("serverInfo").isJsonObject()
                ? r.getAsJsonObject("serverInfo") : new JsonObject();
        this.serverName = JsonUtil.str(info, "name", "unknown");
        this.serverVersion = JsonUtil.str(info, "version", "");
        this.protocolVersion = JsonUtil.str(r, "protocolVersion", "");
        this.instructions = JsonUtil.str(r, "instructions", null);
        this.capabilities = r.has("capabilities") && r.get("capabilities").isJsonObject()
                ? r.getAsJsonObject("capabilities") : new JsonObject();
    }

    /** 服务端是否声明支持某个能力（tools / resources / prompts / logging / completions ...）。 */
    public boolean supports(String capability) {
        JsonElement e = capabilities.get(capability);
        return e != null && e.isJsonObject();
    }

    public String getSummary() {
        String v = (serverVersion == null || serverVersion.isBlank()) ? "" : (" v" + serverVersion);
        return serverName + v + " · MCP " + (protocolVersion == null || protocolVersion.isBlank() ? "?" : protocolVersion);
    }
}
