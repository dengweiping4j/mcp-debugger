package com.tool4j.mcp.settings;

import com.intellij.DynamicBundle;

import com.tool4j.mcp.i18n.I18n;

import java.util.Locale;

/**
 * 界面语言偏好：检测、持久化、一键切换。
 *
 * <p><b>全工程唯一接触 {@code com.intellij.*} 的语言相关类。</b>其余代码一律走 {@link I18n}，
 * 这样 {@code protocol/}、{@code transport/}、{@code model/} 才能保持零平台依赖。
 *
 * <p><b>自动模式严格跟随 IDE 界面语言。</b>{@code DynamicBundle.getLocale()} 在没有中文语言包时
 * 返回 {@code en}（{@code ourLangTag} 的静态初值就是 {@code Locale.ENGLISH.toLanguageTag()}），
 * 装了中文语言包后由语言包声明的 {@code LanguageBundleEP} 经 {@code loadLocale(...)} 改成
 * {@code zh-CN}。它<b>不返回系统 locale</b>，所以不会出现"英文 IDE + 中文系统"拿到中文界面
 * 这种随宿主环境漂移的行为——因此这里刻意不混 {@code Locale.getDefault()}。
 */
public final class LanguageSupport {

    /** 跟随 IDE 界面语言。 */
    public static final String AUTO = "auto";
    public static final String ZH = "zh";
    public static final String EN = "en";

    private LanguageSupport() {
    }

    /** 把持久化的偏好解析成实际语言。 */
    public static Locale resolve() {
        String preference = getPreference();
        if (ZH.equals(preference)) {
            return Locale.SIMPLIFIED_CHINESE;
        }
        if (EN.equals(preference)) {
            return Locale.ENGLISH;
        }
        return DynamicBundle.getLocale();
    }

    /**
     * 应用当前偏好。
     *
     * <p>界面出现之前至少要调用一次——{@link I18n} 是纯 JDK 的，它自己没法知道 IDE 的语言。
     */
    public static void apply() {
        I18n.setLocale(resolve());
    }

    public static String getPreference() {
        return McpSettings.getInstance().getUiLanguage();
    }

    public static boolean isAuto() {
        return AUTO.equals(getPreference());
    }

    /** 显式指定语言：{@link #AUTO} / {@link #ZH} / {@link #EN}。 */
    public static void setPreference(String preference) {
        McpSettings.getInstance().setUiLanguage(preference);
        apply();
    }

    /**
     * 一键切换：在当前生效语言与另一种语言之间来回切，并落成显式偏好。
     *
     * <p>刻意不做成"中 → 英 → 自动"三态循环：自动是"复位"，不是"第三种语言"，
     * 把它夹在循环里会让用户猜不到下一次点击会变成什么。复位入口放在「管理」菜单里。
     */
    public static void toggle() {
        setPreference(I18n.ZH.equals(I18n.getLanguageTag()) ? EN : ZH);
    }
}
