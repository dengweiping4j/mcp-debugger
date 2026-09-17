package com.tool4j.mcp.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.project.Project;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.model.KeyValue;
import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.protocol.JsonUtil;

import org.jetbrains.annotations.Nullable;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「请求头」页签：与「JSON」「定义」并列的第三个页签，形态对齐 Postman 的 Headers 页签。
 *
 * <p><b>它是什么。</b>一段 JSON 对象（{@code {"Authorization": "Bearer xxx"}}），写进去的就是
 * {@link McpServerConfig#getHeaders()} 本身。原来的实现是详情区顶部一条可折叠的横条，
 * 现在收进页签：横条在视觉上自成一层，看着像"另一块区域"；而它其实和参数 JSON 是同一件事的
 * 两个面——都是"这次请求要带什么"。同一级页签才是它的正确位置。
 *
 * <p><b>为什么不是每个工具一份。</b>请求头挂在服务器（会话）上，不是挂在某个工具上：
 * 同一台服务器的所有工具共用一份，{@link com.tool4j.mcp.ui.ToolDetailPanel#showConfig} 换服务器时才重载。
 *
 * <p><b>为什么改完不用重连。</b>{@code HttpTransport} / {@code SseTransport} 每次请求都重新读
 * {@link McpServerConfig}，所以这里写下去的值对后续请求立刻生效（SSE 的长连接是在建连时带的头，
 * 那个连接要重连才会换）。
 *
 * <p><b>解析失败只提示、不写入。</b>手还在敲的时候必然解析不了，这时保留上一次能用的值，
 * 免得"改到一半把 token 弄丢、连也连不上"。
 */
public final class RequestHeadersPanel extends JPanel {

    private final EditorTextField editor;
    private final JBLabel state = Ui.hint(" ");

    /** 当前绑定的配置对象；写进去的就是它，传输层每次请求重新读。 */
    private McpServerConfig bound;
    /** 回填编辑器时抑制监听，免得把自己的写入当成用户编辑。 */
    private boolean loading;
    /** 内容变化后的回调；{@link ToolDetailPanel} 用它刷新页签标题上的条数。 */
    private Runnable onChanged = () -> {
    };

    public RequestHeadersPanel(Project project, Disposable parent) {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(JBUI.Borders.empty(6));

        editor = Editors.sized(Editors.jsonEditor(project, "{}", parent), 160);
        editor.addDocumentListener(new DocumentListener() {
            @Override
            public void documentChanged(DocumentEvent event) {
                onEdited();
            }
        });

        JPanel footer = new JPanel(new BorderLayout());
        footer.setOpaque(false);
        footer.setBorder(JBUI.Borders.empty(3, 1, 0, 1));
        footer.add(state, BorderLayout.CENTER);

        add(editor, BorderLayout.CENTER);
        add(footer, BorderLayout.SOUTH);
        showConfig(null);
    }

    /** 内容变化后的回调（只在真的写进配置时触发），用来刷新页签标题。 */
    public void setOnChanged(Runnable listener) {
        this.onChanged = listener == null ? () -> {
        } : listener;
    }

    // ------------------------------------------------------------------
    // 绑定 / 回填
    // ------------------------------------------------------------------

    /**
     * 绑定到当前服务器。stdio 没有请求头这回事，传 null 即可。
     *
     * <p>重复绑定<b>同一个实例</b>时只刷新提示文字：传输层每次请求都会重新读这份配置，
     * 而 {@link com.tool4j.mcp.ui.McpPanel} 在连接、刷新、切工具时都会重排右侧面板，
     * 每次都重载编辑器的话，会把手正敲到一半的内容（以及光标位置）打回去。
     */
    public void showConfig(@Nullable McpServerConfig config) {
        bound = config;
        if (config == null) {
            loading = true;
            try {
                editor.setText("{}");
            } finally {
                loading = false;
            }
            return;
        }
        loadFromBound();
    }

    private void loadFromBound() {
        loading = true;
        try {
            editor.setText(jsonOf(bound.getHeaders()));
            editor.setCaretPosition(0);
        } finally {
            loading = false;
        }
        refreshState();
        onChanged.run();
    }

    /** 当前生效的请求头条数（空键的不算）。 */
    public int count() {
        if (bound == null) {
            return 0;
        }
        int n = 0;
        for (KeyValue kv : bound.getHeaders()) {
            if (kv != null && !kv.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    /**
     * 页签标题。把条数写在标题上：不点开也能一眼看出"token 到底在不在"，
     * 这是切换服务器、导入配置之后最常确认的一件事。
     */
    public String tabTitle() {
        int n = count();
        return n == 0 ? "请求头" : "请求头 (" + n + ")";
    }

    // ------------------------------------------------------------------
    // 编辑
    // ------------------------------------------------------------------

    private void onEdited() {
        if (loading || bound == null) {
            return;
        }
        JsonObject parsed;
        try {
            parsed = JsonUtil.parseObjectLenient(editor.getText());
        } catch (IllegalArgumentException e) {
            // 敲到一半必然解析不了，这里只提示，不动配置里的旧值
            setState(Ui.ERROR, "JSON 无效，未生效", e.getMessage());
            return;
        }
        List<KeyValue> headers = new ArrayList<>(parsed.size());
        for (Map.Entry<String, JsonElement> entry : parsed.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                continue;
            }
            headers.add(new KeyValue(key, asText(entry.getValue())));
        }
        bound.setHeaders(headers);
        refreshState();
        onChanged.run();
    }

    /** 请求头的值必须是字符串；写成数字 / 对象也照单收下，转成文本发出去。 */
    private static String asText(@Nullable JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString() : JsonUtil.compact(value);
    }

    // ------------------------------------------------------------------
    // 提示
    // ------------------------------------------------------------------

    /**
     * 编辑器下面那行小字。只说三件事：生效几项、什么时候生效、错了是哪一步错了。
     *
     * <p>放在 {@link BorderLayout#CENTER} 上是有意的——窄边栏里它会自动变省略号，
     * 而不是把整个页签顶宽（HTML 标签不做宽度约束时不折行，首选宽度会把布局撑爆）。
     */
    private void refreshState() {
        if (count() == 0) {
            setState(Ui.MUTED, "未设置 · 例如 {\"Authorization\": \"Bearer xxx\"}",
                    "只对 http / sse 生效。这里写下去的值立刻用于后续请求，不用重连"
                            + "（SSE 的长连接要重连才会换）。键名会显示在页签上。");
            return;
        }
        setState(Ui.OK, "已生效 · " + count() + " 项，改动立刻用于后续请求",
                "内容以明文保存在 IDE 配置目录的 mcp-debugger.xml 里，注意别把带真 token 的截图贴出去。");
    }

    private void setState(JBColor color, String text, @Nullable String tooltip) {
        state.setForeground(color);
        state.setText(text == null || text.isEmpty() ? " " : text);
        state.setToolTipText(tooltip);
    }

    private static String jsonOf(List<KeyValue> headers) {
        JsonObject object = new JsonObject();
        for (KeyValue kv : headers) {
            if (kv != null && !kv.isEmpty()) {
                object.addProperty(kv.getKey().trim(), kv.getValue() == null ? "" : kv.getValue());
            }
        }
        return JsonUtil.pretty(object);
    }
}
