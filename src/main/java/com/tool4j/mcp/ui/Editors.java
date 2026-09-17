package com.tool4j.mcp.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.InputMap;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * 内嵌 IntelliJ 编辑器的构造工厂。
 *
 * <p>为什么用 {@link EditorTextField} 而不是裸 {@code EditorFactory}：它自己管理编辑器生命周期、
 * 跟随 IDE 主题与编辑器字体方案、自带撤销/查找；而且换内容只要 {@code setText}，
 * 不需要重建编辑器——"重建后滚动条失灵"这类经典问题因此不会出现。
 * （滚动条与软换行是平台默认关掉/关着的，由 {@link #tuneScrollbars} 显式打开。）
 *
 * <p>JSON 高亮：这里<b>不</b>引用 {@code com.intellij.json.JsonFileType}。那是 JSON 插件里的类，
 * 直接引用等于给插件加了一个包依赖，装到没启用 JSON 支持的 IDE 上会 NoClassDefFoundError。
 * 改成按扩展名问 {@link FileTypeManager} 要：能问到就用（有高亮），问不到就退回纯文本。
 */
public final class Editors {

    private static volatile FileType jsonFileType;

    private Editors() {
    }

    /** 当前 IDE 认可的 JSON 文件类型；没有 JSON 支持时退化为纯文本。 */
    public static FileType jsonFileType() {
        FileType cached = jsonFileType;
        if (cached != null) {
            return cached;
        }
        FileType resolved = PlainTextFileType.INSTANCE;
        try {
            FileType candidate = FileTypeManager.getInstance().getFileTypeByExtension("json");
            if (candidate != null
                    && candidate != UnknownFileType.INSTANCE
                    && candidate != PlainTextFileType.INSTANCE
                    && !candidate.isBinary()) {
                resolved = candidate;
            }
        } catch (Throwable ignored) {
            // 精简发行版上没有 JSON 类型，纯文本即可
        }
        jsonFileType = resolved;
        return resolved;
    }

    /** 只读 JSON 视图。 */
    public static EditorTextField jsonViewer(Project project, String text) {
        return create(project, text, jsonFileType(), true, null);
    }

    /** 只读 JSON 视图，并绑定到父级 Disposable（面板销毁时编辑器一起释放）。 */
    public static EditorTextField jsonViewer(Project project, String text, @Nullable Disposable parent) {
        return create(project, text, jsonFileType(), true, parent);
    }

    /** 可编辑 JSON 视图（参数的手写模式）。 */
    public static EditorTextField jsonEditor(Project project, String text, @Nullable Disposable parent) {
        return create(project, text, jsonFileType(), false, parent);
    }

    private static EditorTextField create(Project project, String text, FileType fileType, boolean viewer,
                                          @Nullable Disposable parent) {
        // 3 参构造器是这个平台上唯一接受 String 的形态；viewer / oneLineMode 后续设置
        EditorTextField field = new EditorTextField(text == null ? "" : text, project, fileType);
        field.setViewer(viewer);
        field.setOneLineMode(false);
        field.setFontInheritedFromLAF(false);
        // 编辑器是惰性创建的（第一次上屏才 new），挂 provider 一定赶在它前面，见 tuneScrollbars
        field.addSettingsProvider(Editors::tuneScrollbars);
        if (parent != null) {
            field.setDisposedWith(parent);
        }
        return field;
    }

    /**
     * 把 {@link EditorTextField} 默认关掉的两根滚动条按我们需要的形态重新拨一遍。
     *
     * <p>平台的 {@code EditorTextField.setupTextFieldEditor()} 里写死了
     * {@code setHorizontalScrollbarVisible(false)} 和 {@code setVerticalScrollbarVisible(false)}
     * （见 2023.3 的字节码，两个 {@code iconst_0}）——于是结果区那份 JSON
     * "看着是能滚的编辑器，实际既没有滚动条、内容也翻不到底"。
     *
     * <p>settings provider 是 {@code createEditor()} 里<b>最后</b>一步回调的，排在
     * {@code setupTextFieldEditor} 之后，所以这里能把它改回来。
     *
     * <p>竖条给 {@code ALWAYS}（编辑器一贯的观感），横条给 {@code AS_NEEDED}（真溢出了才出现），
     * 再顺手打开软换行：工具窗口常态是 300~400px 的右侧边栏，JSON 里一行动辄比这宽得多，
     * 折行比横着拖好读。软换行万一在这个"编辑器用途位"不生效，还有那根横条兜底，
     * 不至于出现"内容在右边、两边都够不着"。
     */
    private static void tuneScrollbars(EditorEx editor) {
        editor.setVerticalScrollbarVisible(true);
        editor.setHorizontalScrollbarVisible(true);
        editor.getSettings().setUseSoftWraps(true);
    }

    /** 固定高度，宽度交给外层布局拉伸。 */
    public static EditorTextField sized(EditorTextField field, int height) {
        field.setPreferredSize(new Dimension(JBUI.scale(120), JBUI.scale(height)));
        field.setMinimumSize(new Dimension(JBUI.scale(60), JBUI.scale(Math.min(height, 80))));
        return field;
    }

    /**
     * 把 Ctrl+Enter 绑到内嵌编辑器自身。
     *
     * <p>编辑器有自己的一套 Keymap，{@code Ctrl+Enter} 在里边是"在当前行下方另起一行"，
     * 于是外层面板挂在 {@code WHEN_ANCESTOR_OF_FOCUSED_COMPONENT} 上的同名快捷键
     * <b>永远轮不到</b>——参数区整块都成了编辑器之后，"Ctrl+Enter 调用"恰好在这唯一可编辑的地方失效。
     *
     * <p>修法是改编辑器自己的映射：同一个 {@code KeyStroke} 写进它的 {@code InputMap}，
     * 平台查找动作时先命中这里，"另起一行"那条就被顶掉了。
     *
     * <p>必须在编辑器被创建<b>之前</b>挂（settings provider 只在创建时消费），
     * 所以拿不到 field 里那个还没建好的 editor 也没关系。
     */
    public static void onInvokeShortcut(EditorTextField field, Runnable action) {
        field.addSettingsProvider(editor -> {
            JComponent content = editor.getContentComponent();
            InputMap inputMap = content.getInputMap(JComponent.WHEN_FOCUSED);
            ActionMap actionMap = content.getActionMap();
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "mcp.invoke");
            actionMap.put("mcp.invoke", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    action.run();
                }
            });
        });
    }

    public static EditorTextField sized(EditorTextField field) {
        return sized(field, 180);
    }
}
