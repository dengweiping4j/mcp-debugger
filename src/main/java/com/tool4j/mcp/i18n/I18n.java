package com.tool4j.mcp.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 界面文案的唯一入口。
 *
 * <p><b>刻意只用 JDK。</b>{@code protocol/}、{@code transport/}、{@code model/} 三个包不允许引用
 * 任何 {@code com.intellij.*}（这样它们能用裸 {@code javac} / JUnit 直接测），而报错文案恰恰最需要
 * 双语，所以词表入口不能建在平台的 {@code AbstractBundle} 上——那个类本身就是 {@code com.intellij.*}。
 * 「IDE 现在是什么语言」这件事归 {@link com.tool4j.mcp.settings.LanguageSupport}，它把结果喂进来。
 *
 * <p>词表是<b>扁平的一份</b>（{@code messages/McpBundle.properties} + {@code _zh}），
 * 键按前缀分区：{@code panel.} / {@code tool.} / {@code err.} / {@code cfg.} ……
 * 单词表比按模块拆多份更好维护：查找只需一处，也不会出现两份文件里同名键互相覆盖。
 *
 * <p>兜底链是 <b>当前语言 → 英文 → 键名本身</b>。最后一级直接显示键名（如 {@code panel.connect}），
 * 漏翻在界面上一眼可见，比显示空白或 {@code !key!} 好排查。
 */
public final class I18n {

    /** 英文：基础词表，也是所有语言的兜底。 */
    public static final String EN = "en";
    /** 中文。 */
    public static final String ZH = "zh";

    private static final String BUNDLE_DIR = "/messages/";
    private static final String BUNDLE_NAME = "McpBundle";

    /** 兜底词表。字段顺序有讲究：它必须在 {@link #active} 之前初始化。 */
    private static final Map<String, String> FALLBACK = load(null);

    private static volatile Map<String, String> zhTable;

    /** 当前生效的「语言标签 + 词表」。打包成一个不可变对象，避免读到标签与词表不匹配的中间态。 */
    private record Bundle(String tag, Map<String, String> table) {
    }

    private static volatile Bundle active = new Bundle(EN, FALLBACK);

    /** 语言变更时要重贴文案的界面。用 {@link Runnable} 而不是平台 topic，是为了保持本类的纯 JDK 属性。 */
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private I18n() {
    }

    /** 取词。{@code {0}} / {@code {1}} 是占位符。 */
    public static String t(String key, Object... args) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        Map<String, String> table = active.table();
        String pattern = table.get(key);
        if (pattern == null) {
            pattern = FALLBACK.get(key);
        }
        if (pattern == null) {
            return key;
        }
        return args == null || args.length == 0 ? pattern : fill(pattern, args);
    }

    /** 当前生效的语言标签（{@code en} / {@code zh}）。 */
    public static String getLanguageTag() {
        return active.tag();
    }

    /**
     * 切换语言。标签没变就什么都不做（避免重复广播导致整棵界面白重贴一遍）。
     */
    public static void setLocale(Locale locale) {
        String tag = normalize(locale);
        if (tag.equals(active.tag())) {
            return;
        }
        active = new Bundle(tag, ZH.equals(tag) ? zhTable() : FALLBACK);
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    public static void addListener(Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /**
     * 归一化到我们真正有两份文案的语言。
     *
     * <p>{@code zh-CN} / {@code zh-TW} / {@code zh-Hans} 全部落到 {@code zh}：我们只有简体一份，
     * 给繁体用户看简体也比回落英文更友好。其余一律 {@code en}。
     */
    private static String normalize(Locale locale) {
        if (locale == null) {
            return EN;
        }
        String language = locale.getLanguage();
        return language != null && language.toLowerCase(Locale.ROOT).startsWith("zh") ? ZH : EN;
    }

    private static Map<String, String> zhTable() {
        Map<String, String> table = zhTable;
        if (table == null) {
            table = load(ZH);
            zhTable = table;
        }
        return table;
    }

    /**
     * 载入一份词表，失败就返回空表（界面上会显示键名，而不是抛异常把插件带崩）。
     *
     * <p>不用 {@link java.util.ResourceBundle}：它按 locale 缓存，运行期换语言时拿到的还是旧内容，
     * 而清缓存只能按 ClassLoader 整体清。载成 Map 之后，切换语言只是一次引用赋值，查词也不走 IO。
     */
    private static Map<String, String> load(String tag) {
        String path = BUNDLE_DIR + BUNDLE_NAME + (tag == null ? "" : "_" + tag) + ".properties";
        Map<String, String> table = new HashMap<>();
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            Properties props = new Properties();
            // 必须包一层 Reader：Properties.load(InputStream) 是按 ISO-8859-1 解码的。
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
            for (String name : props.stringPropertyNames()) {
                table.put(name, props.getProperty(name));
            }
        } catch (IOException | RuntimeException e) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(table);
    }

    /**
     * 替换 {@code {0}} / {@code {1}} 占位符。
     *
     * <p>刻意不用 {@link java.text.MessageFormat}：它把单引号当转义字符，英文文案里一个
     * {@code it's} 就足以吞掉后面的占位符（甚至抛异常）。手写替换没有这个坑。
     */
    private static String fill(String pattern, Object[] args) {
        String result = pattern;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", args[i] == null ? "" : String.valueOf(args[i]));
        }
        return result;
    }
}
