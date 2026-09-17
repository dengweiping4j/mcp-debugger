package com.tool4j.mcp.model;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个键值对，用于环境变量与 HTTP 请求头。
 *
 * <p>这里刻意不用 {@code Map<String,String>}：IntelliJ 的 {@code XmlSerializer} 对 Map 的支持
 * 依赖平台版本，List&lt;POJO&gt; 才是各版本都稳的持久化形态；而且配置界面上表格本来就需要"有序的
 * 可变行列表"，List 更贴合。
 */
@Getter
@Setter
@NoArgsConstructor
public class KeyValue {

    /** 名字里带这些关键字的键，在 UI 上默认按敏感值处理（打码显示、不进日志）。 */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i).*(token|secret|password|passwd|pwd|api[-_]?key|apikey|auth|credential|bearer|session[-_]?id|private[-_]?key).*");

    private String key = "";
    private String value = "";

    public KeyValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public static KeyValue of(String key, String value) {
        return new KeyValue(key, value);
    }

    public boolean isEmpty() {
        return key == null || key.isBlank();
    }

    /**
     * 按名字猜这个键是不是敏感值。
     *
     * <p>纯启发式：配置界面用它决定默认打码、"不把值写进日志"。猜错不影响功能——
     * 用户在表格上点一下「显示」就能看到原文，所以宁可多打码也不要漏。
     */
    public static boolean looksSensitive(String name) {
        return name != null && !name.isBlank() && SENSITIVE.matcher(name).matches();
    }

    public KeyValue copy() {
        return new KeyValue(key, value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof KeyValue other)) {
            return false;
        }
        return Objects.equals(key, other.key) && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key, value);
    }
}
