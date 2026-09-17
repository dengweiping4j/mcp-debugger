package com.tool4j.mcp.model;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tool4j.mcp.protocol.JsonUtil;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/** 一个 MCP 提示词（来自 {@code prompts/list}）。 */
@Getter
public class McpPrompt {

    private final String name;
    private final String title;
    private final String description;
    private final List<Argument> arguments;
    private final JsonObject raw;

    public McpPrompt(JsonObject raw) {
        this.raw = raw == null ? new JsonObject() : raw;
        this.name = JsonUtil.str(this.raw, "name", "(未命名提示词)");
        this.title = JsonUtil.str(this.raw, "title", null);
        this.description = JsonUtil.str(this.raw, "description", null);
        this.arguments = new ArrayList<>();
        JsonElement args = this.raw.get("arguments");
        if (args != null && args.isJsonArray()) {
            JsonArray arr = args.getAsJsonArray();
            for (JsonElement e : arr) {
                if (e.isJsonObject()) {
                    arguments.add(new Argument(e.getAsJsonObject()));
                }
            }
        }
    }

    public String getSubtitle() {
        if (!arguments.isEmpty()) {
            long required = arguments.stream().filter(Argument::isRequired).count();
            return arguments.size() + " 个参数" + (required > 0 ? "（必填 " + required + "）" : "");
        }
        return JsonUtil.firstLine(description);
    }

    @Override
    public String toString() {
        return name;
    }

    /** 提示词参数：MCP 只给了名字/描述/是否必填，没有类型信息，所以界面上一律按字符串输入。 */
    @Getter
    public static class Argument {
        private final String name;
        private final String description;
        private final boolean required;

        public Argument(JsonObject raw) {
            this.name = JsonUtil.str(raw, "name", "");
            this.description = JsonUtil.str(raw, "description", null);
            JsonElement req = raw.get("required");
            this.required = req != null && req.isJsonPrimitive() && req.getAsJsonPrimitive().isBoolean() && req.getAsBoolean();
        }
    }
}
