package com.tool4j.mcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.tool4j.mcp.i18n.I18n;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JSON Schema 的轻量解析：只做"把一件工具的 inputSchema 读懂到能生成参数模板"这件事。
 *
 * <p>不做完整校验（那需要一整个 JSON Schema 实现），但把实际会碰到的几类情况都兜住了：
 * <ul>
 *   <li><b>{@code $ref} 本地引用</b> —— {@code #/definitions/X}、{@code #/$defs/X}、任意 JSON Pointer 路径。</li>
 *   <li><b>{@code allOf} 合并</b> —— 很多服务端（Pydantic / zod 生成器）用 allOf 组合基类字段，
 *   不展开的话表单会缺字段。</li>
 *   <li><b>{@code type} 是数组</b> —— {@code ["string","null"]} 这种可空写法，取第一个非 null 类型，
 *   同时记住它可空。</li>
 *   <li><b>没有 {@code type} 但有 {@code properties}</b> —— 直接按 object 处理。</li>
 * </ul>
 *
 * <p>{@code oneOf} / {@code anyOf} 故意不展开：它们是"二选一"，生成表单只能瞎猜，
 * 界面会在那一层退化成 JSON 编辑器，比给一个错的表单诚实。
 */
public final class SchemaUtil {

    /** 引用展开的深度上限，防止服务端 schema 自引用时递归到栈溢出。 */
    private static final int MAX_REF_DEPTH = 12;

    private SchemaUtil() {
    }

    /** 没有 inputSchema 时的兜底：一个"什么都能传"的空对象 schema。 */
    public static JsonObject emptyObjectSchema() {
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", new JsonObject());
        return o;
    }

    /**
     * 展开 {@code $ref} 与 {@code allOf}，得到一份可以直接读 {@code properties} 的 schema。
     *
     * @param root 整个 schema 文档（引用路径的解析起点，通常就是工具的 inputSchema 本身）
     */
    public static JsonObject normalize(JsonObject root, JsonObject schema) {
        return normalize(root, schema, 0);
    }

    private static JsonObject normalize(JsonObject root, JsonObject schema, int depth) {
        if (schema == null) {
            return new JsonObject();
        }
        if (depth > MAX_REF_DEPTH) {
            return schema;
        }

        String ref = JsonUtil.str(schema, "$ref", null);
        if (ref != null) {
            JsonObject target = followRef(root == null ? schema : root, ref);
            if (target != null && target != schema) {
                return normalize(root, target, depth + 1);
            }
            // 外部引用（file:// / http://）无法解析，原样返回，调用方会退化成 JSON 输入
            return schema;
        }

        JsonArray allOf = schema.has("allOf") && schema.get("allOf").isJsonArray()
                ? schema.getAsJsonArray("allOf") : null;
        if (allOf == null || allOf.isEmpty()) {
            return schema;
        }

        JsonObject merged = new JsonObject();
        Set<String> required = new LinkedHashSet<>();
        JsonObject mergedProperties = new JsonObject();

        for (JsonElement part : allOf) {
            if (!part.isJsonObject()) {
                continue;
            }
            JsonObject resolved = normalize(root, part.getAsJsonObject(), depth + 1);
            for (Map.Entry<String, JsonElement> e : resolved.entrySet()) {
                String key = e.getKey();
                switch (key) {
                    case "properties" -> {
                        if (e.getValue().isJsonObject()) {
                            for (Map.Entry<String, JsonElement> p : e.getValue().getAsJsonObject().entrySet()) {
                                mergedProperties.add(p.getKey(), p.getValue());
                            }
                        }
                    }
                    case "required" -> required.addAll(stringList(resolved.get("required")));
                    case "allOf", "$ref" -> {
                        // 已经处理过
                    }
                    default -> {
                        if (!merged.has(key)) {
                            merged.add(key, e.getValue());
                        }
                    }
                }
            }
        }

        // allOf 之外的兄弟字段优先级更高
        for (Map.Entry<String, JsonElement> e : schema.entrySet()) {
            String key = e.getKey();
            if ("allOf".equals(key) || "$ref".equals(key)) {
                continue;
            }
            if ("properties".equals(key) && e.getValue().isJsonObject()) {
                for (Map.Entry<String, JsonElement> p : e.getValue().getAsJsonObject().entrySet()) {
                    mergedProperties.add(p.getKey(), p.getValue());
                }
                continue;
            }
            if ("required".equals(key)) {
                required.addAll(stringList(e.getValue()));
                continue;
            }
            merged.add(key, e.getValue());
        }

        if (!merged.has("type")) {
            merged.addProperty("type", "object");
        }
        merged.add("properties", mergedProperties);
        if (!required.isEmpty()) {
            JsonArray arr = new JsonArray();
            required.forEach(arr::add);
            merged.add("required", arr);
        }
        return merged;
    }

    /** 解析一个本地 JSON Pointer 引用。 */
    private static JsonObject followRef(JsonObject root, String ref) {
        if (root == null || ref == null || !ref.startsWith("#")) {
            return null;
        }
        String pointer = ref.substring(1);
        if (pointer.isEmpty() || "/".equals(pointer)) {
            return root;
        }
        if (!pointer.startsWith("/")) {
            return null;
        }
        JsonElement current = root;
        for (String rawToken : pointer.substring(1).split("/", -1)) {
            String token = rawToken.replace("~1", "/").replace("~0", "~");
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(token);
            if (current == null) {
                return null;
            }
        }
        return current.isJsonObject() ? current.getAsJsonObject() : null;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 归一化后的 type：{@code string} / {@code integer} / {@code number} / {@code boolean} / {@code array} / {@code object} / {@code null}。 */
    public static String typeOf(JsonObject schema) {
        if (schema == null) {
            return "";
        }
        JsonElement type = schema.get("type");
        if (type != null) {
            if (type.isJsonPrimitive()) {
                return type.getAsString();
            }
            if (type.isJsonArray()) {
                for (JsonElement e : type.getAsJsonArray()) {
                    if (e.isJsonPrimitive()) {
                        String s = e.getAsString();
                        if (!"null".equals(s)) {
                            return s;
                        }
                    }
                }
                return "null";
            }
        }
        if (schema.has("properties") || schema.has("additionalProperties")) {
            return "object";
        }
        if (schema.has("items")) {
            return "array";
        }
        JsonArray en = enumValues(schema);
        if (en != null && !en.isEmpty()) {
            JsonElement first = en.get(0);
            if (first.isJsonPrimitive()) {
                JsonPrimitive p = first.getAsJsonPrimitive();
                if (p.isNumber()) {
                    return isIntegral(p) ? "integer" : "number";
                }
                if (p.isBoolean()) {
                    return "boolean";
                }
            }
            return "string";
        }
        // 没有 type 就按 default 猜。Python 侧写出来的 MCP 服务经常整条函数不写类型注解，
        // schema 里就只剩 title / description / default（例如
        // "start_line": {"default": 1, "title": "Start Line"}）。
        // 猜得出类型就能给它一个像样的控件（数字框、布尔下拉），猜不出来才交给调用方兜底。
        JsonElement def = schema.get("default");
        if (def != null && def.isJsonPrimitive()) {
            JsonPrimitive p = def.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return "boolean";
            }
            if (p.isNumber()) {
                return isIntegral(p) ? "integer" : "number";
            }
            if (p.isString()) {
                return "string";
            }
        }
        return "";
    }

    private static boolean isIntegral(JsonPrimitive p) {
        try {
            double d = p.getAsDouble();
            return d == Math.floor(d) && !Double.isInfinite(d);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** type 数组里含 null，说明这个字段可空。 */
    public static boolean isNullable(JsonObject schema) {
        if (schema == null) {
            return false;
        }
        JsonElement type = schema.get("type");
        if (type != null && type.isJsonArray()) {
            for (JsonElement e : type.getAsJsonArray()) {
                if (e.isJsonPrimitive() && "null".equals(e.getAsString())) {
                    return true;
                }
            }
        }
        return false;
    }

    public static JsonArray enumValues(JsonObject schema) {
        if (schema == null || !schema.has("enum") || !schema.get("enum").isJsonArray()) {
            return null;
        }
        return schema.getAsJsonArray("enum");
    }

    public static JsonObject properties(JsonObject schema) {
        if (schema == null || !schema.has("properties") || !schema.get("properties").isJsonObject()) {
            return new JsonObject();
        }
        return schema.getAsJsonObject("properties");
    }

    public static boolean hasProperties(JsonObject schema) {
        return !properties(schema).entrySet().isEmpty();
    }

    /** 字段名 → 是否必填。 */
    public static Set<String> required(JsonObject schema) {
        Set<String> out = new LinkedHashSet<>();
        if (schema != null) {
            out.addAll(stringList(schema.get("required")));
        }
        return out;
    }

    public static JsonObject items(JsonObject schema) {
        if (schema == null || !schema.has("items") || !schema.get("items").isJsonObject()) {
            return null;
        }
        return schema.getAsJsonObject("items");
    }

    public static String titleOf(JsonObject schema, String fallback) {
        String title = JsonUtil.str(schema, "title", null);
        return title != null && !title.isBlank() ? title : fallback;
    }

    public static String descriptionOf(JsonObject schema) {
        return JsonUtil.str(schema, "description", null);
    }

    public static JsonElement defaultValue(JsonObject schema) {
        if (schema == null || JsonUtil.isNull(schema, "default")) {
            return null;
        }
        return schema.get("default");
    }

    // ------------------------------------------------------------------
    // 参数模板：选中工具时预填进 JSON 输入区的那份骨架
    // ------------------------------------------------------------------

    /**
     * 按 schema 生成一份参数模板，把<b>声明的每个参数</b>都列出来。
     *
     * <p>取值顺序：{@code default} → 枚举首项 → 按类型的空值
     * （{@code string→""}、{@code integer/number→0}、{@code boolean→false}、
     * {@code array→[]}、{@code object→}递归，其余一律 {@code ""}）。
     *
     * <p>为什么不写 {@code "string"} 这种示例文本（Swagger UI 的做法）：那是给"看文档"的人看的，
     * 发出去能跑；而这里是要被人直接点「调用」的，示例文本长得太像真数据，容易被连带发走。
     * 空值一眼就知道"这个还得填"，且 JSON 结构完整、随时可以解析。
     *
     * <p>枚举取首项是唯一的例外——它至少保证是 schema 允许的取值，
     * 给 {@code ""} 反而必然被服务端拒掉。
     *
     * <p>注意 {@code type: "null"} 这类字段也给 {@code ""}：{@code null} 在序列化时会被 Gson
     * 整个丢掉（{@code serializeNulls} 默认关），键就会从模板里消失——那就又回到
     * "界面上的参数比协议里声明的少"这个毛病上了。
     */
    public static JsonObject template(JsonObject schema) {
        JsonObject root = schema == null ? emptyObjectSchema() : schema;
        return templateOf(root, normalize(root, root), 0);
    }

    private static JsonObject templateOf(JsonObject root, JsonObject schema, int depth) {
        JsonObject out = new JsonObject();
        if (schema == null || depth > MAX_REF_DEPTH) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : properties(schema).entrySet()) {
            if (!e.getValue().isJsonObject()) {
                continue;
            }
            JsonObject prop = normalize(root, e.getValue().getAsJsonObject(), depth + 1);
            out.add(e.getKey(), sampleOf(root, prop, depth + 1));
        }
        return out;
    }

    /** 单个参数的示例值。 */
    private static JsonElement sampleOf(JsonObject root, JsonObject prop, int depth) {
        JsonElement def = defaultValue(prop);
        if (def != null) {
            return def.deepCopy();
        }
        JsonArray en = enumValues(prop);
        if (en != null && !en.isEmpty()) {
            JsonElement first = en.get(0);
            if (!first.isJsonNull()) {
                return first.deepCopy();
            }
        }
        if (depth > MAX_REF_DEPTH) {
            return new JsonPrimitive("");
        }
        return switch (typeOf(prop)) {
            case "object" -> templateOf(root, prop, depth);
            case "array" -> new JsonArray();
            case "boolean" -> new JsonPrimitive(false);
            case "integer", "number" -> new JsonPrimitive(0);
            default -> new JsonPrimitive("");
        };
    }

    /**
     * 这个子树是否必须退化成"直接写 JSON"。
     *
     * <p>注意 {@code type == ""} 的返回值是 <b>false</b>：服务端没声明类型不等于值是一坨结构，
     * 事实上绝大多数就是"没写注解的字符串/数字"。当成 JSON 处理的话界面上会出现一个
     * 72px 高的等宽输入框，"填个 repo_id"变得比填一段配置还隆重。
     * untyped 字段按普通文本给值即可（见 {@link #template} 的 {@code default} 分支）。
     *
     * @param depth 当前嵌套深度，超过 3 层就交给 JSON 编辑器，否则表单会又深又难用
     */
    public static boolean needsJsonEditor(JsonObject schema, int depth) {
        if (schema == null) {
            return false;
        }
        if (schema.has("oneOf") || schema.has("anyOf") || schema.has("not")) {
            return true;
        }
        String type = typeOf(schema);
        return switch (type) {
            case "object" -> !hasProperties(schema) || schema.has("additionalProperties") || depth >= 3;
            case "array" -> items(schema) == null || needsJsonEditor(items(schema), depth + 1);
            case "" -> false;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 类型提示：表单里每个字段下方那行灰色小字
    // ------------------------------------------------------------------

    public static String describeConstraint(JsonObject schema) {
        if (schema == null) {
            return "";
        }
        List<String> bits = new ArrayList<>();
        String type = typeOf(schema);
        String format = JsonUtil.str(schema, "format", null);

        switch (type) {
            case "integer" -> bits.add(I18n.t("schema.type.integer"));
            case "number" -> bits.add(I18n.t("schema.type.number"));
            case "string" -> bits.add(format != null
                    ? I18n.t("schema.type.stringWithFormat", format)
                    : I18n.t("schema.type.string"));
            case "boolean" -> bits.add(I18n.t("schema.type.boolean"));
            case "null" -> bits.add(I18n.t("schema.type.null"));
            case "array" -> bits.add(I18n.t("schema.type.array"));
            case "object" -> bits.add(I18n.t("schema.type.object"));
            // 服务端什么都没声明：界面上是单行输入，说"未声明类型"比"任意类型"更实在
            default -> bits.add(I18n.t("schema.type.undeclared"));
        }

        if ("array".equals(type)) {
            JsonObject items = items(schema);
            if (items != null) {
                bits.add(I18n.t("schema.items", typeOf(items)));
            }
            Integer min = intOrNull(schema, "minItems");
            Integer max = intOrNull(schema, "maxItems");
            if (min != null || max != null) {
                bits.add(I18n.t("schema.count", range(min, max)));
            }
        }
        if ("string".equals(type)) {
            Integer min = intOrNull(schema, "minLength");
            Integer max = intOrNull(schema, "maxLength");
            if (min != null || max != null) {
                bits.add(I18n.t("schema.length", range(min, max)));
            }
        }
        if ("integer".equals(type) || "number".equals(type)) {
            JsonElement min = schema.get("minimum");
            JsonElement max = schema.get("maximum");
            if (min != null && min.isJsonPrimitive()) {
                bits.add("≥ " + min.getAsString());
            }
            if (max != null && max.isJsonPrimitive()) {
                bits.add("≤ " + max.getAsString());
            }
        }
        JsonArray en = enumValues(schema);
        if (en != null) {
            bits.add(I18n.t("schema.enum", en.size()));
        }
        JsonElement def = defaultValue(schema);
        if (def != null && !def.isJsonNull()) {
            String d = def.isJsonPrimitive() ? def.getAsString() : JsonUtil.compact(def);
            if (d.length() > 40) {
                d = d.substring(0, 37) + "…";
            }
            bits.add(I18n.t("schema.default", d));
        }
        if (isNullable(schema)) {
            bits.add(I18n.t("schema.nullable"));
        }
        // 中文用全角逗号、英文用逗号加空格，所以分隔符本身也得进词表
        return String.join(I18n.t("schema.separator"), bits);
    }

    /** {@code ≥1 ≤10} 这种范围片段。符号本身不翻译，只由调用方决定前面挂什么标签。 */
    private static String range(Integer min, Integer max) {
        return (min == null ? "" : "≥" + min) + (max == null ? "" : " ≤" + max);
    }

    private static Integer intOrNull(JsonObject schema, String key) {
        if (schema == null || !schema.has(key) || !schema.get(key).isJsonPrimitive()) {
            return null;
        }
        try {
            return schema.get(key).getAsInt();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> stringList(JsonElement element) {
        List<String> out = new ArrayList<>();
        if (element != null && element.isJsonArray()) {
            for (JsonElement e : element.getAsJsonArray()) {
                if (e.isJsonPrimitive()) {
                    out.add(e.getAsString());
                }
            }
        }
        return out;
    }
}
