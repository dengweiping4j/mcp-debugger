package com.tool4j.mcp.ui;

import com.google.gson.JsonObject;

import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.fileChooser.FileChooserFactory;
import com.intellij.openapi.fileChooser.FileSaverDescriptor;
import com.intellij.openapi.fileChooser.FileSaverDialog;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileWrapper;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.model.McpCallResult;
import com.tool4j.mcp.protocol.JsonUtil;

import javax.imageio.ImageIO;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 调用结果的渲染区。
 *
 * <p>MCP 的 {@code result.content} 是个多态数组：文本、图片（base64）、内嵌资源、音频都可能出现。
 * 这里按类型分别渲染——文本直接可读、base64 图片直接画出来（还能存盘）、
 * 认不出来的类型退化成原始 JSON——而不是把一坨 JSON 甩给用户。
 *
 * <p>另外永远保留一个「原始 JSON」页签：调试时你需要能对照"协议里到底回了什么"，
 * 尤其在界面按类型渲染过之后。
 */
public final class ResultView extends JPanel implements Disposable {

    private static final int TEXT_MAX_HEIGHT = 320;
    /** 图片预览的上限；真正的上限还要跟当前面板宽度取小（右侧边栏可能只有两三百像素）。 */
    private static final int IMAGE_MAX_WIDTH = 560;
    private static final int IMAGE_MAX_HEIGHT = 420;

    private final Project project;
    private final JBLabel statusDot = new JBLabel();
    private final JBLabel statusText = new JBLabel();
    private final JBLabel statusMeta = Ui.hint("");
    private final JPanel itemHost = new JPanel();
    private final JBTabbedPane tabs = new JBTabbedPane();
    private final JBScrollPane itemsScroll;
    private final EditorTextField rawViewer;

    private McpCallResult lastResult;

    public ResultView(Project project, Disposable parent) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);

        itemHost.setOpaque(false);
        itemHost.setLayout(new BoxLayout(itemHost, BoxLayout.Y_AXIS));

        itemsScroll = new JBScrollPane(itemHost);
        itemsScroll.setBorder(JBUI.Borders.empty());
        itemsScroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));

        rawViewer = Editors.sized(Editors.jsonViewer(project, "", parent), 320);

        JPanel rawPanel = new JPanel(new BorderLayout());
        rawPanel.setOpaque(false);
        rawPanel.add(rawViewer, BorderLayout.CENTER);

        tabs.addTab("结果", itemsScroll);
        tabs.addTab("原始 JSON", rawPanel);

        add(buildStatusStrip(), BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
    }

    private JComponent buildStatusStrip() {
        JPanel strip = new JPanel(new BorderLayout(JBUI.scale(6), 0));
        strip.setOpaque(false);
        strip.setBorder(JBUI.Borders.empty(4, 6, 4, 6));

        // 状态文字与补充信息都放在会被"摊开 / 截断"的那一格里：
        // 挤的时候消失的是灰字（耗时、内容块数），红绿状态永远在。反过来把状态压掉是不行的。
        JPanel textPart = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        textPart.setOpaque(false);
        textPart.add(statusText, BorderLayout.WEST);
        textPart.add(statusMeta, BorderLayout.CENTER);

        // 两个复制按钮做成图标：这段状态条在窄边栏里本来就没多少宽度，
        // "复制文本 / 复制 JSON"两个文字按钮一摆，状态和耗时就被挤没了。
        JButton copyText = iconButton(AllIcons.Actions.Copy, "复制所有文本内容（不含图片 / 资源）");
        copyText.addActionListener(e -> {
            if (lastResult != null) {
                Ui.copyToClipboard(lastResult.joinText());
            }
        });

        JButton copyRaw = iconButton(AllIcons.Actions.Copy, "复制完整的 JSON-RPC 响应（原始报文）");
        copyRaw.addActionListener(e -> Ui.copyToClipboard(rawViewer.getText()));

        JPanel right = new JPanel();
        right.setOpaque(false);
        right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
        right.add(copyText);
        right.add(Ui.hgap(2));
        right.add(copyRaw);

        strip.add(statusDot, BorderLayout.WEST);
        strip.add(textPart, BorderLayout.CENTER);
        strip.add(right, BorderLayout.EAST);
        return strip;
    }

    private static JButton iconButton(javax.swing.Icon icon, String tooltip) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setMargin(JBUI.insets(2, 4, 2, 4));
        return button;
    }

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    public void showLoading(String what) {
        statusDot.setIcon(Ui.dot(Ui.MUTED));
        statusText.setText("正在调用 " + what + " …");
        statusText.setForeground(JBColor.GRAY);
        statusText.setFont(Ui.bold(statusText.getFont()));
        statusMeta.setText("");
        itemHost.removeAll();
        rawViewer.setText("");
        itemHost.revalidate();
        itemHost.repaint();
    }

    public void clear() {
        lastResult = null;
        statusDot.setIcon(null);
        statusText.setText("尚未调用。填好参数后点「调用」。");
        statusText.setForeground(JBColor.GRAY);
        statusMeta.setText("");
        itemHost.removeAll();
        rawViewer.setText("");
        itemHost.revalidate();
        itemHost.repaint();
    }

    /** 渲染一次调用结果。 */
    public void show(McpCallResult result, String what) {
        this.lastResult = result;
        itemHost.removeAll();

        if (result == null) {
            showFailure("没有拿到结果", what);
            return;
        }

        boolean failed = !result.isSuccess();
        if (failed) {
            statusDot.setIcon(Ui.dot(Ui.ERROR));
            statusText.setText(result.getError() != null
                    ? "调用失败"
                    : "工具执行失败（isError = true）");
            statusText.setForeground(Ui.ERROR);
        } else {
            statusDot.setIcon(Ui.dot(Ui.OK));
            statusText.setText("调用成功");
            statusText.setForeground(Ui.OK);
        }
        statusText.setFont(Ui.bold(statusText.getFont()));

        int itemCount = result.getContent().size();
        String meta = result.getElapsedMillis() + " ms";
        if (itemCount > 0) {
            meta += " · " + itemCount + " 个内容块";
        }
        if (result.getStructuredContent() != null) {
            meta += " · 含结构化内容";
        }
        statusMeta.setText(meta);

        if (result.getError() != null) {
            itemHost.add(card("协议层错误", textBody(result.getError(), 0, Ui.ERROR)));
        }

        List<JsonObject> contents = result.getContent();
        for (int i = 0; i < contents.size(); i++) {
            itemHost.add(renderContentItem(contents.get(i), i + 1, contents.size()));
        }

        if (result.getStructuredContent() != null) {
            itemHost.add(card("结构化内容（structuredContent）",
                    textBody(JsonUtil.pretty(result.getStructuredContent()), TEXT_MAX_HEIGHT, null)));
        }

        if (itemHost.getComponentCount() == 0) {
            itemHost.add(card("结果", textBody("（服务端返回了空结果）", 0, null)));
        }

        rawViewer.setText(JsonUtil.pretty(result.getRaw()));
        if (failed) {
            tabs.setSelectedIndex(0);
        }
        itemHost.revalidate();
        itemHost.repaint();
    }

    public void showFailure(String message, String what) {
        lastResult = null;
        statusDot.setIcon(Ui.dot(Ui.ERROR));
        statusText.setText("调用 " + what + " 失败");
        statusText.setForeground(Ui.ERROR);
        statusText.setFont(Ui.bold(statusText.getFont()));
        statusMeta.setText("");
        itemHost.removeAll();
        itemHost.add(card("错误", textBody(message, 0, Ui.ERROR)));
        rawViewer.setText("{\n  \"error\": " + JsonUtil.compact(new com.google.gson.JsonPrimitive(message)) + "\n}");
        itemHost.revalidate();
        itemHost.repaint();
    }

    // ------------------------------------------------------------------
    // 内容块
    // ------------------------------------------------------------------

    private JComponent renderContentItem(JsonObject item, int index, int total) {
        String type = JsonUtil.str(item, "type", "");
        return switch (type) {
            case "text" -> card("文本" + suffix(item, index, total),
                    textBody(JsonUtil.str(item, "text", ""), TEXT_MAX_HEIGHT, null));
            case "image" -> renderImage(item, index, total);
            case "audio" -> renderBinary(item, index, total, "音频", "wav");
            case "resource" -> renderResource(item, index, total);
            case "resource_link" -> card("资源链接" + suffix(item, index, total),
                    textBody(JsonUtil.str(item, "uri", JsonUtil.compact(item)), 0, null));
            default -> card("未知类型 " + (type.isBlank() ? "(未声明)" : type) + suffix(item, index, total),
                    textBody(JsonUtil.pretty(item), TEXT_MAX_HEIGHT, null));
        };
    }

    /**
     * 内容块标题的后缀：先带角色（{@code prompts/get} 的消息才有的 {@code x-role}），再带序号。
     * 有些服务端一屏回好几条消息，不区分角色就分不清谁在说话。
     */
    private static String suffix(JsonObject item, int index, int total) {
        List<String> bits = new ArrayList<>(2);
        String role = JsonUtil.str(item, McpCallResult.ROLE_KEY, null);
        if (role != null && !role.isBlank()) {
            bits.add(role);
        }
        if (total > 1) {
            bits.add(index + "/" + total);
        }
        return bits.isEmpty() ? "" : " " + String.join(" · ", bits);
    }

    private JComponent renderImage(JsonObject item, int index, int total) {
        String mimeType = JsonUtil.str(item, "mimeType", "image/png");
        String data = JsonUtil.str(item, "data", null);
        if (data == null) {
            return card("图片" + suffix(item, index, total) + " · " + mimeType,
                    textBody("服务端没有给出 data 字段\n" + JsonUtil.pretty(item), TEXT_MAX_HEIGHT, null));
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            return card("图片" + suffix(item, index, total) + " · " + mimeType,
                    textBody("data 不是合法的 Base64：\n" + Ui.ellipsize(data, 400), TEXT_MAX_HEIGHT, Ui.ERROR));
        }

        BufferedImage image = null;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception ignored) {
            // 下面按"解不出来"处理
        }
        if (image == null) {
            return card("图片" + suffix(item, index, total) + " · " + mimeType,
                    textBody("无法解码为图片（" + bytes.length + " 字节，当前 IDE/JRE 可能不支持该格式）",
                            0, Ui.ERROR));
        }

        ImageIcon icon = new ImageIcon(image);
        int maxWidth = maxPreviewWidth();
        double scale = 1.0;
        if (image.getWidth() > maxWidth || image.getHeight() > IMAGE_MAX_HEIGHT) {
            scale = Math.min((double) maxWidth / image.getWidth(),
                    (double) IMAGE_MAX_HEIGHT / image.getHeight());
        }
        JLabel preview = new JLabel(scale < 1.0
                ? new ImageIcon(image.getScaledInstance(
                (int) Math.round(image.getWidth() * scale),
                (int) Math.round(image.getHeight() * scale), java.awt.Image.SCALE_SMOOTH))
                : icon);
        preview.setBorder(JBUI.Borders.empty(4));

        JBScrollPane scroll = new JBScrollPane(preview);
        scroll.setBorder(JBUI.Borders.empty());
        int height = Math.min(IMAGE_MAX_HEIGHT, image.getHeight() + JBUI.scale(20));
        scroll.setPreferredSize(new Dimension(0, JBUI.scale(Math.max(80, height))));

        JButton save = new JButton("保存图片…");
        String extension = extensionFor(mimeType);
        save.addActionListener(e -> saveBytes("保存图片", "选择保存位置", extension, bytes));

        String title = "图片" + suffix(item, index, total) + " · " + mimeType
                + " · " + image.getWidth() + "×" + image.getHeight()
                + (scale < 1.0 ? "（预览已缩放）" : "");
        return card(title, scroll, save);
    }

    private JComponent renderBinary(JsonObject item, int index, int total, String kind, String fallbackExt) {
        String mimeType = JsonUtil.str(item, "mimeType", "");
        String data = JsonUtil.str(item, "data", null);
        byte[] bytes = null;
        if (data != null) {
            try {
                bytes = Base64.getDecoder().decode(data);
            } catch (IllegalArgumentException ignored) {
                // 下面按无数据展示
            }
        }
        String extension = extensionFor(mimeType.isBlank() ? fallbackExt : mimeType);
        String text = bytes == null
                ? "服务端没有给出 data 字段"
                : "二进制内容，共 " + bytes.length + " 字节。点右侧按钮保存后查看。";
        JButton save = new JButton("保存…");
        byte[] finalBytes = bytes;
        save.setEnabled(bytes != null);
        save.addActionListener(e -> saveBytes("保存内容", "选择保存位置", extension, finalBytes));
        return card(kind + suffix(item, index, total) + (mimeType.isBlank() ? "" : " · " + mimeType),
                textBody(text, 0, null), save);
    }

    private JComponent renderResource(JsonObject item, int index, int total) {
        JsonObject resource = JsonUtil.object(item, "resource");
        if (resource == null) {
            return card("内嵌资源" + suffix(item, index, total),
                    textBody(JsonUtil.pretty(item), TEXT_MAX_HEIGHT, null));
        }
        String uri = JsonUtil.str(resource, "uri", "(无 uri)");
        String mimeType = JsonUtil.str(resource, "mimeType", "");
        String text = JsonUtil.str(resource, "text", null);
        String blob = JsonUtil.str(resource, "blob", null);

        String title = "内嵌资源" + suffix(item, index, total) + " · " + uri
                + (mimeType.isBlank() ? "" : "  ·  " + mimeType);
        if (text != null) {
            return card(title, textBody(text, TEXT_MAX_HEIGHT, null));
        }
        if (blob != null) {
            byte[] bytes = null;
            try {
                bytes = Base64.getDecoder().decode(blob);
            } catch (IllegalArgumentException ignored) {
                // 按无数据展示
            }
            JButton save = new JButton("保存…");
            byte[] finalBytes = bytes;
            save.setEnabled(bytes != null);
            save.addActionListener(e -> saveBytes("保存资源", "选择保存位置", extensionFor(mimeType), finalBytes));
            return card(title, textBody(bytes == null
                    ? "blob 不是合法的 Base64"
                    : "二进制资源，共 " + bytes.length + " 字节。", 0,
                    bytes == null ? Ui.ERROR : null), save);
        }
        return card(title, textBody(JsonUtil.pretty(resource), TEXT_MAX_HEIGHT, null));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 一个内容块：标题行（含右侧操作按钮）+ 主体。 */
    private JComponent card(String title, JComponent body, JComponent... actions) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(JBUI.Borders.empty(4, 6, 8, 6));

        JBLabel titleLabel = new JBLabel(title);
        titleLabel.setFont(Ui.smaller(titleLabel.getFont().deriveFont(Font.BOLD)));
        titleLabel.setForeground(com.intellij.util.ui.UIUtil.getContextHelpForeground());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(titleLabel, BorderLayout.WEST);
        if (actions != null && actions.length > 0) {
            JPanel right = new JPanel();
            right.setOpaque(false);
            right.setLayout(new BoxLayout(right, BoxLayout.X_AXIS));
            for (JComponent action : actions) {
                if (action != null) {
                    right.add(action);
                    right.add(Ui.hgap(4));
                }
            }
            header.add(right, BorderLayout.EAST);
        }

        panel.add(header, BorderLayout.NORTH);
        panel.add(body, BorderLayout.CENTER);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    /**
     * 文本块。行数少就直接铺开（外层滚动条统一滚），行数多就套一个固定高度的内层滚动区，
     * 免得超长文本把整个布局撑爆。
     *
     * @param maxHeight &lt;= 0 表示强制铺开
     */
    public static JComponent textBody(String text, int maxHeight, JBColor colorOverride) {
        String content = text == null ? "" : text;
        JBTextArea area = new JBTextArea(content);
        area.setEditable(false);
        area.setFont(Ui.monospace());
        area.setLineWrap(true);
        area.setWrapStyleWord(false);
        area.setOpaque(false);
        area.setBorder(JBUI.Borders.empty(2, 4));
        if (colorOverride != null) {
            area.setForeground(colorOverride);
        }

        int lineHeight = Math.max(12, area.getFontMetrics(area.getFont()).getHeight());
        int lines = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines++;
            }
        }
        // 文本块永远套内层滚动：长行会软换行，行数不等于显示行数，靠 rows 算高度必错
        JBScrollPane scroll = new JBScrollPane(area);
        scroll.setBorder(JBUI.Borders.empty());
        int natural = lines * lineHeight + JBUI.scale(14);
        int limit = maxHeight > 0 ? JBUI.scale(maxHeight) : natural;
        scroll.setPreferredSize(new Dimension(0, Math.min(Math.max(natural, JBUI.scale(36)), limit)));
        return scroll;
    }

    private void saveBytes(String title, String description, String extension, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return;
        }
        try {
            FileSaverDialog dialog = FileChooserFactory.getInstance()
                    .createSaveFileDialog(new FileSaverDescriptor(title, description, extension), project);
            // 强转一下：save 有 (VirtualFile, String) 与 (Path, String) 两个重载，传裸 null 会歧义
            VirtualFileWrapper wrapper = dialog.save((VirtualFile) null, "mcp-result." + extension);
            if (wrapper == null) {
                return;
            }
            File target = wrapper.getFile();
            Files.write(target.toPath(), bytes);
        } catch (Exception e) {
            com.intellij.openapi.ui.Messages.showErrorDialog(project,
                    "保存失败：" + McpException.describe(e), title);
        }
    }

    /**
     * 图片预览能用的宽度：不能硬按 {@link #IMAGE_MAX_WIDTH} 缩放——右侧边栏只有两三百像素时，
     * 那样缩出来的预览图比可视区还宽，得横向拖滚动条才看得全。
     */
    private int maxPreviewWidth() {
        int available = itemsScroll.getViewport().getWidth();
        int cap = available > 0 ? available - JBUI.scale(24) : IMAGE_MAX_WIDTH;
        return Math.max(JBUI.scale(120), Math.min(IMAGE_MAX_WIDTH, cap));
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return "bin";
        }
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        int slash = lower.indexOf('/');
        String subtype = slash >= 0 ? lower.substring(slash + 1) : lower;
        int semicolon = subtype.indexOf(';');
        if (semicolon >= 0) {
            subtype = subtype.substring(0, semicolon);
        }
        return switch (subtype) {
            case "jpeg", "jpg" -> "jpg";
            case "svg+xml" -> "svg";
            case "plain", "text" -> "txt";
            case "mpeg" -> "mp3";
            default -> subtype.isBlank() ? "bin" : subtype.replaceAll("[^a-z0-9]", "");
        };
    }

    @Override
    public void dispose() {
        itemHost.removeAll();
        lastResult = null;
    }
}
