package com.tool4j.mcp.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 词表与门面的守卫测试。
 *
 * <p>这组测试存在的意义就一条：<b>两份词表的键集合必须永远相同</b>。缺键不会报错
 * （会静默回落到英文），所以没有测试的话，漏翻只能靠用户发现。
 *
 * <p>{@link I18n} 是纯 JDK 的，所以这里不需要任何 IntelliJ 运行时，直接跑。
 */
class I18nTest {

    private static final String DIR = "/messages/";
    private static final String NAME = "McpBundle";

    @AfterEach
    void restoreEnglish() {
        I18n.setLocale(Locale.ENGLISH);
    }

    @Test
    @DisplayName("中文词表覆盖了英文词表的每一个键（不多、不少）")
    void zhCoversEveryEnglishKey() {
        Set<String> en = keysOf(null);
        Set<String> zh = keysOf("zh");

        assertTrue(en.size() >= 5, "英文基础词表看起来是空的，检查 processResources 是否把 messages/ 打进去了");

        Set<String> missing = new TreeSet<>(en);
        missing.removeAll(zh);
        Set<String> extra = new TreeSet<>(zh);
        extra.removeAll(en);

        assertTrue(missing.isEmpty(), "中文词表缺少这些键（会静默回落到英文）：" + missing);
        assertTrue(extra.isEmpty(), "中文词表多出这些键（拼写错误？）：" + extra);
    }

    @Test
    @DisplayName("两份词表都没有空值")
    void noBlankValues() {
        for (String tag : new String[]{null, "zh"}) {
            Properties props = load(tag);
            for (String key : props.stringPropertyNames()) {
                String value = props.getProperty(key);
                assertFalse(value == null || value.isBlank(),
                        (tag == null ? "en" : tag) + " 词表里 " + key + " 的值是空的");
            }
        }
    }

    @Test
    @DisplayName("漏翻时显示键名本身，而不是空白")
    void missingKeyShowsTheKeyItself() {
        assertEquals("this.key.does.not.exist", I18n.t("this.key.does.not.exist"));
    }

    @Test
    @DisplayName("占位符 {0} {1} 按普通替换生效，且英文里的单引号不会吃掉占位符")
    void placeholdersArePlainSubstitution() {
        String desc = I18n.t("err.call.failed", "中文", "English");
        assertTrue(desc.contains("中文"), "第一个占位符没被替换：" + desc);
        assertTrue(desc.contains("English"), "第二个占位符没被替换：" + desc);
        assertFalse(desc.contains("{0}") || desc.contains("{1}"), "占位符没被替换干净：" + desc);
    }

    @Test
    @DisplayName("切到中文后取到的是中文文案，切回来恢复英文")
    void switchingLanguageChangesTheText() {
        I18n.setLocale(Locale.ENGLISH);
        String english = I18n.t("lang.menu.text");
        assertEquals(I18n.EN, I18n.getLanguageTag());

        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals(I18n.ZH, I18n.getLanguageTag());
        assertNotEquals(english, I18n.t("lang.menu.text"), "切到中文后文案没变");

        I18n.setLocale(Locale.ENGLISH);
        assertEquals(english, I18n.t("lang.menu.text"), "切回英文后文案没恢复");
    }

    @Test
    @DisplayName("没有对应词表的语言一律回落到英文")
    void unknownLocaleFallsBackToEnglish() {
        I18n.setLocale(Locale.GERMANY);
        assertEquals(I18n.EN, I18n.getLanguageTag());
        assertFalse(I18n.t("lang.menu.text").isEmpty());
    }

    @Test
    @DisplayName("zh-CN / zh-TW / zh-Hans 都归到中文")
    void chineseVariantsAllMapToChinese() {
        for (Locale locale : new Locale[]{
                Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, new Locale("zh", "Hans")}) {
            I18n.setLocale(locale);
            assertEquals(I18n.ZH, I18n.getLanguageTag(), locale + " 没有归到中文");
        }
    }

    @Test
    @DisplayName("同一种语言重复设置不重复广播（否则整棵界面会白重贴一次）")
    void settingTheSameLanguageDoesNotNotifyAgain() {
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;
        I18n.addListener(listener);
        try {
            I18n.setLocale(Locale.ENGLISH);
            int afterFirst = notifications.get();

            I18n.setLocale(Locale.ENGLISH);
            assertEquals(afterFirst, notifications.get(), "重复设置同一语言不该再次广播");

            I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(afterFirst + 1, notifications.get(), "换语言必须广播一次");
        } finally {
            I18n.removeListener(listener);
        }
    }

    @Test
    @DisplayName("英文约束分隔符保留逗号后的空格（值里写成 \\u0020 转义，防止被编辑器清掉）")
    void englishSeparatorKeepsItsSpace() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals(", ", I18n.t("schema.separator"), "逗号后的空格丢了，英文约束会粘成一坨");
        I18n.setLocale(Locale.SIMPLIFIED_CHINESE);
        assertEquals("，", I18n.t("schema.separator"));
    }

    // ------------------------------------------------------------------

    private static Set<String> keysOf(String tag) {
        return new TreeSet<>(load(tag).stringPropertyNames());
    }

    private static Properties load(String tag) {
        String path = DIR + NAME + (tag == null ? "" : "_" + tag) + ".properties";
        Properties props = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream(path)) {
            assertTrue(in != null, "词表不在 classpath 上：" + path);
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (Exception e) {
            throw new AssertionError("读取词表失败：" + path, e);
        }
        return props;
    }
}
