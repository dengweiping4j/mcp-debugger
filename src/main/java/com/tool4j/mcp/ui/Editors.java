package com.tool4j.mcp.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import com.intellij.openapi.fileTypes.UnknownFileType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.ui.EditorTextField;
import com.intellij.util.ui.JBUI;

import org.jetbrains.annotations.Nullable;

import java.awt.Dimension;

/**
 * 内嵌 IntelliJ 编辑器的构造工厂。
 *
 * <p>为什么用 {@link EditorTextField} 而不是裸 {@code EditorFactory}：它自己管理编辑器生命周期、
 * 跟随 IDE 主题与编辑器字体方案、自带撤销/查找/软换行；而且换内容只要 {@code setText}，
 * 不需要重建编辑器——"重建后滚动条失灵"这类经典问题因此不会出现。
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
        if (parent != null) {
            field.setDisposedWith(parent);
        }
        return field;
    }

    /** 固定高度，宽度交给外层布局拉伸。 */
    public static EditorTextField sized(EditorTextField field, int height) {
        field.setPreferredSize(new Dimension(JBUI.scale(120), JBUI.scale(height)));
        field.setMinimumSize(new Dimension(JBUI.scale(60), JBUI.scale(Math.min(height, 80))));
        return field;
    }

    public static EditorTextField sized(EditorTextField field) {
        return sized(field, 180);
    }
}
