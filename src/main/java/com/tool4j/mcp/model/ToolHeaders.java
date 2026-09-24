package com.tool4j.mcp.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某个工具专属的请求头。
 *
 * <p>MCP 里 {@code tools/call} 就是一次普通的 JSON-RPC 请求，发到同一个端点上，所以
 * "这个工具额外带哪些头"在协议上完全成立——无非是给那一次 POST 换一组头。真正需要想清楚的
 * 是生效范围：建连（{@code initialize}、SSE 的 GET 长连接）时还不知道要调哪个工具，
 * 所以这里的东西<b>只作用于 {@code tools/call}</b>。
 *
 * <p>与服务器级的合并规则是"逐键合并，同名以工具级为准"（见
 * {@link McpServerConfig#headerMapFor(String)}）。不做"删除"语义：要盖掉某个头就给它一个
 * 空值，需要真删除时再加开关不迟。
 *
 * <p>仍然存 {@code List<KeyValue>} 而不是 {@code Map}：平台 {@code XmlSerializer} 对 Map 的
 * 支持依赖版本，List&lt;POJO&gt; 才是各版本都稳的持久化形态（理由同 {@link KeyValue} 的类注释）。
 */
@Getter
@Setter
@NoArgsConstructor
public class ToolHeaders {

    /** 服务端给出的工具名。只做精确匹配（trim 后比较），不做 {@code *} 通配。 */
    private String toolName = "";

    private List<KeyValue> headers = new ArrayList<>();

    public ToolHeaders(String toolName) {
        this.toolName = toolName;
    }

    /** 老配置里这个字段可能缺失，读出来是 null——惰性兜成空表，序列化才不会有 NPE。 */
    public List<KeyValue> getHeaders() {
        if (headers == null) {
            headers = new ArrayList<>();
        }
        return headers;
    }

    public boolean isEmpty() {
        return toolName == null || toolName.isBlank();
    }

    /** 非空键值对的数量（卡片标题上的计数用它，空键的行不算）。 */
    public int count() {
        int n = 0;
        for (KeyValue kv : getHeaders()) {
            if (kv != null && !kv.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /** 转成可直接加到头上的 Map（已剔除空键）。 */
    public Map<String, String> headerMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (KeyValue kv : getHeaders()) {
            if (kv != null && !kv.isEmpty()) {
                map.put(kv.getKey().trim(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
        return map;
    }

    public ToolHeaders copy() {
        ToolHeaders c = new ToolHeaders(toolName);
        for (KeyValue kv : getHeaders()) {
            if (kv != null) {
                c.headers.add(kv.copy());
            }
        }
        return c;
    }

    @Override
    public String toString() {
        return toolName + " (" + count() + ")";
    }
}
