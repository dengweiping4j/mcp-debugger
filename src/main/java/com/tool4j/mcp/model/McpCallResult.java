package com.tool4j.mcp.model;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.tool4j.mcp.protocol.JsonUtil;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 {@code tools/call}（或 {@code resources/read} / {@code prompts/get}）的结果。
 *
 * <p>刻意保留原始 {@link #getRaw()} 响应对象：调试工具的价值一半在于"看到的和协议里的一模一样"，
 * 界面直接把这一份 result 渲染成 JSON。
 *
 * <p>三种响应虽然都是 JSON-RPC 的 {@code result}，但装内容的字段名各不相同：
 * <ul>
 *   <li>{@code tools/call} → {@code result.content[]}（标准内容块）</li>
 *   <li>{@code resources/read} → {@code result.contents[]}（资源对象，带 text 或 blob）</li>
 *   <li>{@code prompts/get} → {@code result.messages[]}（带 role 的消息，content 可能是块或块数组）</li>
 * </ul>
 * 所以这里在构造时把三者**归一化**成同一份 {@link #getContent()} 内容块列表，
 * 让"这次回了几个内容块、有没有结构化内容"这类判断不必再为每种方法写一条分支。
 */
@Getter
public class McpCallResult {

    /**
     * 非标准键：{@code prompts/get} 里这条内容属于哪个角色。
     *
     * <p>把 role 挂在内容块上而不是另开一个结构，是为了让"内容块"这一种形态能同时表达
     * 工具结果与提示词消息——统计内容块时不必先分清这是哪一种响应。
     */
    public static final String ROLE_KEY = "x-role";

    /** JSON-RPC result 对象；调用在协议层就失败时为 null。 */
    private final JsonObject raw;
    /** 归一化后的内容块列表，三种方法共用。 */
    private final List<JsonObject> content = new ArrayList<>();
    /** {@code result.structuredContent}，MCP 2025-06-18 的结构化输出。 */
    private final JsonObject structuredContent;

    /** 协议层 / 业务层错误信息；非 null 表示这次调用没有拿到正常结果。 */
    @Setter
    private String error;
    /** result.isError —— 服务器自己报的工具执行失败（参数错、下游报错等）。 */
    private final boolean toolError;

    @Setter
    private long elapsedMillis;

    public McpCallResult(JsonObject rawResponse) {
        this.raw = rawResponse == null ? new JsonObject() : rawResponse;

        // 三种形态都试一遍：正常响应只会命中其中一种，全都不命中就是空结果
        collectBlocks(this.raw.get("content"));
        collectResourceContents(this.raw.get("contents"));
        collectPromptMessages(this.raw.get("messages"));

        JsonElement sc = this.raw.get("structuredContent");
        this.structuredContent = sc != null && sc.isJsonObject() ? sc.getAsJsonObject() : null;

        JsonElement isErr = this.raw.get("isError");
        this.toolError = isErr != null && isErr.isJsonPrimitive() && isErr.getAsJsonPrimitive().isBoolean()
                && isErr.getAsBoolean();
    }

    public boolean isSuccess() {
        return error == null && !toolError;
    }

    // ------------------------------------------------------------------
    // 归一化
    // ------------------------------------------------------------------

    /** {@code tools/call} 的 {@code content[]}：原样收下。 */
    private void collectBlocks(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement e : element.getAsJsonArray()) {
            if (e.isJsonObject()) {
                content.add(e.getAsJsonObject());
            }
        }
    }

    /** {@code resources/read} 的 {@code contents[]}：每项包成标准的 {@code resource} 内容块。 */
    private void collectResourceContents(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement e : element.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject block = new JsonObject();
            block.addProperty("type", "resource");
            block.add("resource", e.getAsJsonObject());
            content.add(block);
        }
    }

    /** {@code prompts/get} 的 {@code messages[]}：摊平成内容块，并把角色带上。 */
    private void collectPromptMessages(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return;
        }
        for (JsonElement e : element.getAsJsonArray()) {
            if (!e.isJsonObject()) {
                continue;
            }
            JsonObject message = e.getAsJsonObject();
            String role = JsonUtil.str(message, "role", null);
            JsonElement body = message.get("content");
            if (body == null) {
                continue;
            }
            if (body.isJsonArray()) {
                for (JsonElement block : body.getAsJsonArray()) {
                    addBlock(block, role);
                }
            } else {
                addBlock(body, role);
            }
        }
    }

    private void addBlock(JsonElement block, String role) {
        if (block.isJsonObject()) {
            JsonObject copy = block.getAsJsonObject().deepCopy();
            if (role != null && !role.isBlank()) {
                copy.addProperty(ROLE_KEY, role);
            }
            content.add(copy);
        } else if (block.isJsonPrimitive()) {
            // 有些服务端把 content 直接写成字符串
            content.add(textBlock(block.getAsString(), role));
        }
    }

    private static JsonObject textBlock(String text, String role) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text == null ? "" : text);
        if (role != null && !role.isBlank()) {
            block.addProperty(ROLE_KEY, role);
        }
        return block;
    }
}
