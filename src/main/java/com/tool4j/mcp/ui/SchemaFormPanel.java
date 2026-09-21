package com.tool4j.mcp.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.EditorTextField;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.JBUI;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.SchemaUtil;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 由工具的 {@code inputSchema} 自动生成参数表单。
 *
 * <p><b>当前没有被界面引用</b>：参数区已经改成"只有 JSON 一个输入入口"，
 * 预填模板走 {@link com.tool4j.mcp.protocol.SchemaUtil#template}，不再需要表单。
 * 这个类先留在原地（它这套类型映射与提示渲染的活儿不好重写，将来要恢复表单页签可以少走一段路），
 * 确认不要了整份删掉即可。
 *
 * <p>以下是它的原始说明：这是整个插件里最"值钱"的一块——MCP 工具的入参必须是 JSON，
 * 手写 JSON 调一次工具要反复查 schema、反复试错。这里按 schema 把它变成真正的控件：
 *
 * <table border="1">
 *   <caption>类型映射</caption>
 *   <tr><th>schema</th><th>控件</th></tr>
 *   <tr><td>{@code enum}</td><td>下拉框；选填时多一个「未设置」</td></tr>
 *   <tr><td>{@code string}</td><td>单行输入；{@code format=multiline} 或 {@code maxLength>200} 时改成多行文本域</td></tr>
 *   <tr><td>{@code integer} / {@code number}</td><td>数字输入，提交时按类型解析，不是数字就红字提示</td></tr>
 *   <tr><td>{@code boolean}</td><td>必填用复选框；选填用「未设置 / true / false」，避免手一抖把 false 发出去</td></tr>
 *   <tr><td>{@code array}（元素是标量）</td><td>多行文本，每行一个值（单行时也认逗号分隔）</td></tr>
 *   <tr><td>{@code array}（元素是对象）</td><td>JSON 编辑器</td></tr>
 *   <tr><td>{@code object}</td><td>递归成带缩进的分组；超过 3 层改用 JSON 编辑器</td></tr>
 *   <tr><td>{@code oneOf} / {@code anyOf}</td><td>JSON 编辑器（"二选一"表单表达不了，不猜）</td></tr>
 *   <tr><td>没声明 {@code type}也没 {@code default}</td><td>单行输入，提交时先试着按 JSON 解释（{@code 1} → 数字、{@code true} → 布尔），解释不了就当字符串</td></tr>
 * </table>
 */
public final class SchemaFormPanel extends JPanel implements Disposable {

    /** 构建结果：要么拿到参数，要么拿到一串人话问题。 */
    public static final class Outcome {
        private final JsonObject arguments;
        private final List<String> problems;

        Outcome(JsonObject arguments, List<String> problems) {
            this.arguments = arguments;
            this.problems = problems;
        }

        public boolean isOk() {
            return problems.isEmpty();
        }

        public JsonObject getArguments() {
            return arguments;
        }

        public List<String> getProblems() {
            return problems;
        }
    }

    /** 提示文字换行后的最小宽度，再窄就没法读了。 */
    private static final int HINT_MIN_WIDTH = 140;

    /** 提示文字换行时留给行内边距的余量。 */
    private static final int HINT_RESERVE = 20;

    private final Project project;
    private final JPanel host = new JPanel(new BorderLayout());
    private final JBLabel emptyHint = Ui.hint(I18n.t("sf.empty.noParams"));
    private final List<Field> allFields = new ArrayList<>();

    private JsonObject rootSchema = SchemaUtil.emptyObjectSchema();
    private Node rootNode;

    public SchemaFormPanel(Project project) {
        super(new BorderLayout());
        this.project = project;
        setOpaque(false);

        host.setOpaque(false);
        JBScrollPane scroll = new JBScrollPane(host);
        scroll.setBorder(JBUI.Borders.empty());
        scroll.getVerticalScrollBar().setUnitIncrement(JBUI.scale(16));
        add(scroll, BorderLayout.CENTER);

        // 提示文字是按固定宽度换行的（原因见 TextRow），所以宽度一变就得重算
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                relayoutHints();
            }
        });

        emptyHint.setBorder(JBUI.Borders.empty(8, 4));
        rebuild(null);
        applyTexts();
    }

    /** 重画所有常驻文字（空提示 + 每个字段的标题/提示/错误/编辑器 tooltip），不碰输入值。 */
    public void applyTexts() {
        emptyHint.setText(I18n.t("sf.empty.noParams"));
        for (Field field : allFields) {
            field.applyTexts();
        }
        relayoutHints();
    }

    // ------------------------------------------------------------------
    // 构建
    // ------------------------------------------------------------------

    /** 按 schema 重建表单；{@code initialValues} 用于"把 JSON 同步回表单"。 */
    public void setSchema(JsonObject schema, JsonObject initialValues) {
        this.rootSchema = schema == null ? SchemaUtil.emptyObjectSchema() : schema;
        rebuild(initialValues);
    }

    private void rebuild(JsonObject initialValues) {
        host.removeAll();
        allFields.clear();

        JsonObject normalized = SchemaUtil.normalize(rootSchema, rootSchema);
        rootNode = new Node(normalized);
        buildNode(rootNode, initialValues, 0, "", false);

        if (rootNode.fields.isEmpty()) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(emptyHint, BorderLayout.WEST);
            host.add(wrap, BorderLayout.NORTH);
        } else {
            host.add(rootNode.panel, BorderLayout.NORTH);
        }
        relayoutHints();
        host.revalidate();
        host.repaint();
    }

    /** 按当前可用宽度重算每个字段提示文字的换行宽度。 */
    private void relayoutHints() {
        // 还没上屏（宽度 0）时先按典型边栏宽度估一个，等第一次布局的 resize 事件再纠正，
        // 否则提示文字会一直空着——rebuild() 往往发生在组件拿到真实尺寸之前
        int total = getWidth() > 0 ? getWidth() : JBUI.scale(Ui.INPUT_MAX_WIDTH + 120);
        for (Field field : allFields) {
            field.updateHintWidth(total - field.labelWidth - JBUI.scale(HINT_RESERVE));
        }
    }

    /** 一个 object 层级，字段平铺在一张 GridBagLayout 上。 */
    private final class Node {
        final JsonObject schema;
        final JPanel panel = new JPanel(new GridBagLayout());
        final List<Field> fields = new ArrayList<>();
        int row;

        Node(JsonObject schema) {
            this.schema = schema;
            panel.setOpaque(false);
        }

        void collect(JsonObject into, List<String> problems) {
            for (Field field : fields) {
                field.collect(into, problems);
            }
        }
    }

    private void buildNode(Node node, JsonObject initial, int depth, String pathPrefix, boolean nested) {
        JsonObject properties = SchemaUtil.properties(node.schema);
        var requiredNames = SchemaUtil.required(node.schema);

        for (var entry : properties.entrySet()) {
            String name = entry.getKey();
            if (!entry.getValue().isJsonObject()) {
                continue;
            }
            JsonObject schema = SchemaUtil.normalize(rootSchema, entry.getValue().getAsJsonObject());
            boolean required = requiredNames.contains(name);
            String path = pathPrefix.isEmpty() ? name : pathPrefix + "." + name;
            JsonElement initialValue = initial == null ? null : initial.get(name);

            Field field = createField(name, schema, required, depth, path, initialValue);
            node.fields.add(field);
            allFields.add(field);

            int leftInset = nested ? 16 : 4;

            JComponent label = buildLabel(field, schema, name, required);
            field.labelWidth = label.getPreferredSize().width;

            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = node.row;
            labelConstraints.anchor = GridBagConstraints.NORTHWEST;
            labelConstraints.insets = new Insets(JBUI.scale(4), JBUI.scale(leftInset), JBUI.scale(4), JBUI.scale(8));
            labelConstraints.weightx = 0;
            node.panel.add(label, labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = node.row;
            fieldConstraints.anchor = GridBagConstraints.NORTHWEST;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.weightx = 1;
            fieldConstraints.insets = new Insets(JBUI.scale(4), 0, JBUI.scale(4), JBUI.scale(4));
            node.panel.add(field.cell, fieldConstraints);

            node.row++;
        }

        // 底部垫一个可伸缩的空行，把内容压在顶部而不是垂直居中
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx = 1;
        filler.gridy = node.row;
        filler.weightx = 1;
        filler.weighty = 1;
        filler.fill = GridBagConstraints.BOTH;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        node.panel.add(spacer, filler);
    }

    /**
     * 字段标题：Schema 给了 {@code title} 就优先用它（服务端往往写得更像人话），
     * 同时把真正的字段名用小灰字带上——不然用户对着「JSON」页签会对不上哪个参数是哪个。
     */
    private JComponent buildLabel(Field field, JsonObject schema, String name, boolean required) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));

        String title = SchemaUtil.titleOf(schema, name);
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setToolTipText(I18n.t("sf.tooltip.fieldName") + name);
        field.fieldLabel = label;
        panel.add(label);

        if (!title.equals(name)) {
            JBLabel key = Ui.hint(" " + name);
            key.setToolTipText(I18n.t("sf.tooltip.actualFieldName"));
            field.keyLabel = key;
            panel.add(key);
        }
        if (required) {
            JBLabel star = new JBLabel(" *");
            star.setForeground(JBColor.RED);
            star.setToolTipText(I18n.t("sf.tooltip.required"));
            field.starLabel = star;
            panel.add(star);
        }
        return panel;
    }

    /**
     * 换掉下拉框第 0 项的文案（即"未设置"这个占位项），并保住用户当前的选中项。
     *
     * <p>{@link ComboBox}（继承自 {@code JComboBox}）没有 {@code setItemAt}，
     * 只能删了再插；删第 0 项会把选中项挤掉，所以先记下索引再还原。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceFirstItem(ComboBox combo, String text) {
        int selected = combo.getSelectedIndex();
        combo.removeItemAt(0);
        combo.insertItemAt(text, 0);
        combo.setSelectedIndex(selected);
    }

    // ------------------------------------------------------------------
    // 字段工厂
    // ------------------------------------------------------------------

    private Field createField(String name, JsonObject schema, boolean required, int depth, String path,
                              JsonElement initialValue) {
        String type = SchemaUtil.typeOf(schema);
        JsonArray enumValues = SchemaUtil.enumValues(schema);

        if (enumValues != null && !enumValues.isEmpty() && isScalarType(type)) {
            return new EnumField(name, schema, required, path, enumValues, initialValue);
        }
        if (SchemaUtil.needsJsonEditor(schema, depth)) {
            return new JsonField(name, schema, required, path, initialValue);
        }
        return switch (type) {
            case "string" -> isLongText(schema)
                    ? new TextAreaField(name, schema, required, path, initialValue)
                    : new TextField(name, schema, required, path, initialValue);
            case "integer", "number" -> new NumberField(name, schema, required, path, type, initialValue);
            case "boolean" -> new BoolField(name, schema, required, path, initialValue);
            case "array" -> {
                JsonObject items = SchemaUtil.items(schema);
                if (items != null && isScalarType(SchemaUtil.typeOf(items)) && SchemaUtil.enumValues(items) == null) {
                    yield new ListField(name, schema, required, path, items, initialValue);
                }
                yield new JsonField(name, schema, required, path, initialValue);
            }
            case "object" -> new ObjectField(name, schema, required, path, initialValue, depth);
            // 连 default 都猜不出类型（服务端整个没写）：单行文本，不是 JSON 编辑器
            default -> new UntypedField(name, schema, required, path, initialValue);
        };
    }

    private static boolean isScalarType(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    /**
     * 这个字符串字段要不要用多行文本域。
     *
     * <p><b>只看 schema 自己的声明，不看描述有多长。</b>原来还有一条"描述超过 110 字就改多行"，
     * 那是把"描述写得长"当成了"值会很长"——服务端爱把参数说明写成一段话，
     * 于是 {@code repo_id}、{@code pattern} 这种一个词就填完的参数全被撑成两行高，
     * 一排参数看下来全是空盒子。值真的长不长，schema 里的 {@code format} 和
     * {@code maxLength} 才是能作数的信号。
     */
    private static boolean isLongText(JsonObject schema) {
        String format = JsonUtil.str(schema, "format", "");
        if ("multiline".equals(format) || "textarea".equals(format)) {
            return true;
        }
        if (schema.has("maxLength") && schema.get("maxLength").isJsonPrimitive()) {
            try {
                return schema.get("maxLength").getAsInt() > 200;
            } catch (RuntimeException ignored) {
                return false;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 提交
    // ------------------------------------------------------------------

    /** 校验并构建参数对象。必填缺失 / 类型不对都会变成 {@link Outcome#getProblems()} 里的一条。 */
    public Outcome buildArguments() {
        for (Field field : allFields) {
            field.setError("");
        }
        JsonObject arguments = new JsonObject();
        List<String> problems = new ArrayList<>();
        if (rootNode != null) {
            rootNode.collect(arguments, problems);
        }
        if (!problems.isEmpty()) {
            scrollToFirstError();
        }
        return new Outcome(arguments, problems);
    }

    /** 清空所有输入，回到 schema 默认值。 */
    public void reset() {
        rebuild(null);
    }

    private void scrollToFirstError() {
        for (Field field : allFields) {
            if (field.hasError()) {
                JComponent target = field.cell;
                target.scrollRectToVisible(new Rectangle(0, 0,
                        Math.max(1, target.getWidth()), Math.max(1, target.getHeight())));
                return;
            }
        }
    }

    @Override
    public void dispose() {
        host.removeAll();
        allFields.clear();
        rootNode = null;
    }

    // ==================================================================
    // 字段
    // ==================================================================

    /**
     * 一个参数。子类各自持有控件的类型化视图（{@link #editor}），
     * 这样 {@link #value()} 不需要从组件树里"挖"控件。
     */
    private abstract class Field {
        final String name;
        final String path;
        final JsonObject schema;
        final boolean required;
        final JComponent editor;
        final JBLabel error = new JBLabel("");
        final JPanel cell = new JPanel(new BorderLayout());
        /** 编辑框 + 提示 + 错误三行，按 Y 轴堆起来；错误行按需挂上，没错误就不占高度。 */
        private final JPanel stack = new JPanel();
        private final JPanel errorRow = new TextRow(error);
        /** 提示文字的原始文本；为空表示这个字段没有提示。 */
        private final String hintText;
        private final JBLabel hintLabel;
        /** 未包装的错误文本，宽度变化时要按新宽度重排。 */
        private String errorMessage = "";
        /** 当前可用的文字宽度，提示与错误文字共用。 */
        private int textWidth = JBUI.scale(HINT_MIN_WIDTH);
        /** 标签列宽度，算提示换行宽度时要减掉。 */
        int labelWidth;
        /** 标题 / 字段名 / 必填星号，由 {@link #buildLabel} 回填；没建出来时为 null。 */
        JBLabel fieldLabel;
        JBLabel keyLabel;
        JBLabel starLabel;

        Field(String name, JsonObject schema, boolean required, String path, JComponent editor) {
            this.name = name;
            this.path = path;
            this.schema = schema;
            this.required = required;
            this.editor = editor;

            stack.setOpaque(false);
            stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));

            editor.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(editor);

            this.hintText = buildHint();
            if (hintText.isEmpty()) {
                this.hintLabel = null;
            } else {
                this.hintLabel = Ui.htmlHint("");
                this.hintLabel.setToolTipText(tooltip());
                stack.add(new TextRow(this.hintLabel));
            }

            error.setForeground(JBColor.RED);
            error.setFont(Ui.smaller(error.getFont()));
            errorRow.setAlignmentX(Component.LEFT_ALIGNMENT);

            stack.setAlignmentX(Component.LEFT_ALIGNMENT);
            cell.setOpaque(false);
            cell.add(stack, BorderLayout.CENTER);
        }

        /**
         * 重算提示 / 错误文字的换行宽度。
         *
         * <p>HTML 标签不会自己按可用宽度折行——必须把宽度写进 {@code <div>}，
         * 否则一句 90 字的描述就是一行几百像素，直接把整张表的首选宽度撑爆。
         */
        void updateHintWidth(int available) {
            textWidth = Math.max(JBUI.scale(HINT_MIN_WIDTH), available);
            if (hintLabel != null) {
                hintLabel.setText(wrapHtml(textWidth, hintText));
            }
            if (!errorMessage.isEmpty()) {
                error.setText(wrapHtml(textWidth, "⚠ " + errorMessage));
            }
        }

        private static String wrapHtml(int width, String text) {
            return "<html><div width='" + width + "px'>" + Ui.escapeHtml(text) + "</div></html>";
        }

        private String buildHint() {
            String constraint = SchemaUtil.describeConstraint(schema);
            String description = SchemaUtil.descriptionOf(schema);
            if (description == null || description.isBlank()) {
                return constraint;
            }
            String shortDescription = Ui.ellipsize(JsonUtil.oneLine(description), 90);
            return constraint.isEmpty() ? shortDescription : constraint + "  ·  " + shortDescription;
        }

        String tooltip() {
            StringBuilder sb = new StringBuilder("<html><b>");
            sb.append(Ui.escapeHtml(name)).append("</b>");
            if (required) {
                sb.append(" <font color='#C0392B'>").append(I18n.t("sf.tooltip.required")).append("</font>");
            }
            String constraint = SchemaUtil.describeConstraint(schema);
            if (!constraint.isEmpty()) {
                sb.append("<br>").append(Ui.escapeHtml(constraint));
            }
            String description = SchemaUtil.descriptionOf(schema);
            if (description != null && !description.isBlank()) {
                sb.append("<br><br>").append(Ui.escapeHtml(description.strip()).replace("\n", "<br>"));
            }
            return sb.append("</html>").toString();
        }

        /** 重画本字段的常驻文字（标题/字段名/必填星号 tooltip、编辑器 tooltip、提示与错误），不碰输入值。 */
        void applyTexts() {
            if (fieldLabel != null) {
                fieldLabel.setToolTipText(I18n.t("sf.tooltip.fieldName") + name);
            }
            if (keyLabel != null) {
                keyLabel.setToolTipText(I18n.t("sf.tooltip.actualFieldName"));
            }
            if (starLabel != null) {
                starLabel.setToolTipText(I18n.t("sf.tooltip.required"));
            }
            editor.setToolTipText(tooltip());
            if (hintLabel != null) {
                hintLabel.setText(wrapHtml(textWidth, hintText));
            }
            if (!errorMessage.isEmpty()) {
                setError(errorMessage);
            }
        }

        /** 空 Optional 表示"不提交这个字段"。 */
        abstract Optional<JsonElement> value();

        void collect(JsonObject into, List<String> problems) {
            Optional<JsonElement> box;
            try {
                box = value();
            } catch (IllegalArgumentException e) {
                problems.add(path + I18n.t("sf.error.colon") + e.getMessage());
                setError(e.getMessage());
                return;
            }
            if (box == null || box.isEmpty()) {
                if (required) {
                    problems.add(I18n.t("sf.error.missingRequired", path));
                    setError(I18n.t("sf.error.required"));
                }
                return;
            }
            into.add(name, box.get());
        }

        /**
         * 设/清错误文字。错误行是<b>按需</b>挂上去的：留着一条空行看着无害，
         * 但每个参数都留一行，十来个参数就是一屏的空白。
         */
        void setError(String message) {
            errorMessage = message == null ? "" : message;
            error.setText(errorMessage.isEmpty() ? "" : wrapHtml(textWidth, "⚠ " + errorMessage));
            boolean empty = errorMessage.isEmpty();
            boolean attached = errorRow.getParent() != null;
            if (empty == !attached) {
                return;
            }
            if (empty) {
                stack.remove(errorRow);
            } else {
                stack.add(errorRow);
            }
            stack.revalidate();
            stack.repaint();
        }

        boolean hasError() {
            return !error.getText().isEmpty();
        }

        /** 初始值（或 schema default）转成字符串，用于预填文本类控件。 */
        String initialString(JsonElement initial) {
            JsonElement source = initial != null ? initial : SchemaUtil.defaultValue(schema);
            if (source == null || source.isJsonNull()) {
                return "";
            }
            return source.isJsonPrimitive() ? source.getAsString() : JsonUtil.compact(source);
        }

        JsonElement initialOrNull(JsonElement initial) {
            return initial != null ? initial : SchemaUtil.defaultValue(schema);
        }
    }

    /**
     * 提示 / 错误文字的行容器：承载换行后的文字，但<b>不参与</b>决定表单的首选宽度。
     *
     * <p>原来把 HTML 标签直接塞进 {@code BorderLayout.WEST}，标签的首选宽度就是"这句话一行多长"。
     * 描述稍长一点，整张参数表的首选宽度就被撑到几百像素，参数区的滚动面板于是冒出横向滚动条，
     * 每个输入框都被拉到那个宽度上——边栏里得横着滚才能看全一个输入框；校验失败时那条红字
     * 也会照样把表单顶宽。
     *
     * <p>这里让它的首选宽度恒为 0，宽度交回外层布局决定，文字则靠 HTML 里的固定宽度换行。
     */
    private static final class TextRow extends JPanel {
        TextRow(JBLabel label) {
            super(new BorderLayout());
            setOpaque(false);
            setBorder(JBUI.Borders.emptyTop(2));
            setAlignmentX(Component.LEFT_ALIGNMENT);
            add(label, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(0, super.getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, super.getMaximumSize().height);
        }
    }

    private final class TextField extends Field {

        TextField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, Ui.compactInput(new JBTextField()));
            JBTextField input = (JBTextField) editor;
            input.setText(initialString(initial));
            input.setToolTipText(tooltip());
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((JBTextField) editor).getText();
            return text == null || text.isEmpty() ? Optional.empty() : Optional.of(new JsonPrimitive(text));
        }
    }

    /**
     * 服务端没声明类型的参数。
     *
     * <p>这种字段比想象中多：Python 侧的函数只要不写类型注解，FastMCP 生成的 schema 里就
     * 只剩 {@code title / description / default}，连 {@code type} 都没有（例如
     * {@code read_file(repo_id, start_line=1, end_line=None)} 的三个参数）。
     * 从前这类字段一律落到 JSON 编辑器，界面上就是 {@code repo_id} 配一个 72px 高的等宽框——
     * 十几个参数叠起来能吃掉整屏，而且看着根本不像"填一个值"的地方。
     *
     * <p>现在按普通单行输入渲染（一行高度），提交时先试着当 JSON 解释一遍：
     * {@code 1} → 数字、{@code true} → 布尔、{@code [1,2]} / {@code {"a":1}} → 对应结构，
     * 解释不了（{@code master}、{@code fastapi}）就当字符串发。想强制成字符串加引号即可
     * （{@code "10"}）。这样原来 JSON 编辑器能表达的取值一个没少，高度只剩一行。
     */
    private final class UntypedField extends Field {

        UntypedField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, Ui.compactInput(new JBTextField()));
            JBTextField input = (JBTextField) editor;
            input.setText(initialString(initial));
            input.setToolTipText(tooltip());
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((JBTextField) editor).getText();
            if (text == null || text.isEmpty()) {
                return Optional.empty();
            }
            // tryParse 自带"首字符不像 JSON 就直接放弃"的判断，所以 fastapi / v1.2.3 这类
            // 纯文本原样按字符串发，不会被误解读
            JsonElement parsed = JsonUtil.tryParse(text);
            return Optional.of(parsed != null ? parsed : new JsonPrimitive(text));
        }
    }

    private final class TextAreaField extends Field {

        TextAreaField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, createArea(initialValue(schema, initial)));
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((JBTextArea) editor).getText();
            return text == null || text.isEmpty() ? Optional.empty() : Optional.of(new JsonPrimitive(text));
        }
    }

    private static JBTextArea createArea(String text) {
        JBTextArea area = new JBTextArea(text == null ? "" : text);
        // 两行够看清开头，不够再拖——三行起步会让一个多行参数吃掉半屏
        area.setRows(2);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(Ui.monospace());
        return area;
    }

    private static String initialValue(JsonObject schema, JsonElement initial) {
        JsonElement source = initial != null ? initial : SchemaUtil.defaultValue(schema);
        if (source == null || source.isJsonNull() || !source.isJsonPrimitive()) {
            return "";
        }
        return source.getAsString();
    }

    private final class NumberField extends Field {
        private final String type;

        NumberField(String name, JsonObject schema, boolean required, String path, String type, JsonElement initial) {
            super(name, schema, required, path, Ui.compactInput(new JBTextField()));
            this.type = type;
            JBTextField input = (JBTextField) editor;
            JsonElement source = initial != null ? initial : SchemaUtil.defaultValue(schema);
            input.setText(source == null || source.isJsonNull() || !source.isJsonPrimitive() ? "" : source.getAsString());
            input.setToolTipText(tooltip());
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((JBTextField) editor).getText();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            String trimmed = text.trim();
            try {
                if ("integer".equals(type)) {
                    return Optional.of(new JsonPrimitive(Long.parseLong(trimmed)));
                }
                double parsed = Double.parseDouble(trimmed);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) {
                    throw new NumberFormatException(I18n.t("sf.error.invalidNumber"));
                }
                return Optional.of(new JsonPrimitive(parsed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("integer".equals(type)
                        ? I18n.t("sf.error.notInteger", trimmed)
                        : I18n.t("sf.error.notNumber", trimmed));
            }
        }
    }

    private final class BoolField extends Field {

        BoolField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, createBoolEditor(schema, required, initial));
        }

        @Override
        void applyTexts() {
            super.applyTexts();
            if (editor instanceof JCheckBox box) {
                box.setText(I18n.t("sf.bool.enabled"));
                box.setToolTipText(I18n.t("sf.bool.requiredTooltip"));
            } else {
                ComboBox<?> combo = (ComboBox<?>) editor;
                replaceFirstItem(combo, I18n.t("sf.bool.unset"));
                combo.setToolTipText(I18n.t("sf.bool.optionalTooltip"));
            }
        }

        @Override
        Optional<JsonElement> value() {
            if (editor instanceof JCheckBox box) {
                // 必填布尔：复选框本身就是取值，未勾选就是 false，必须发出去
                return Optional.of(new JsonPrimitive(box.isSelected()));
            }
            ComboBox<?> combo = (ComboBox<?>) editor;
            int index = combo.getSelectedIndex();
            // 选填布尔：保持"未设置"时不发这个字段，避免把服务端的默认值覆盖成 false
            return index <= 0 ? Optional.empty() : Optional.of(new JsonPrimitive(index == 1));
        }
    }

    private static JComponent createBoolEditor(JsonObject schema, boolean required, JsonElement initial) {
        boolean initialBool = initial != null && initial.isJsonPrimitive() && initial.getAsJsonPrimitive().isBoolean()
                ? initial.getAsBoolean()
                : JsonUtil.bool(schema, "default", false);
        if (required) {
            JCheckBox box = new JCheckBox(I18n.t("sf.bool.enabled"));
            box.setSelected(initialBool);
            box.setToolTipText(I18n.t("sf.bool.requiredTooltip"));
            return box;
        }
        ComboBox<String> combo = new ComboBox<>(new String[]{I18n.t("sf.bool.unset"), "true", "false"});
        combo.setSelectedIndex(initial == null ? 0 : (initialBool ? 1 : 2));
        combo.setToolTipText(I18n.t("sf.bool.optionalTooltip"));
        return Ui.compactInput(combo, 160);
    }

    private final class EnumField extends Field {
        private final JsonArray values;
        private final boolean optional;

        EnumField(String name, JsonObject schema, boolean required, String path, JsonArray values, JsonElement initial) {
            super(name, schema, required, path, createEnumCombo(schema, values, required, initial));
            this.values = values;
            this.optional = !required;
        }

        @Override
        void applyTexts() {
            super.applyTexts();
            ComboBox<?> combo = (ComboBox<?>) editor;
            if (optional) {
                replaceFirstItem(combo, I18n.t("sf.bool.unset"));
            }
            combo.setToolTipText(required ? I18n.t("sf.enum.requiredTooltip") : I18n.t("sf.enum.optionalTooltip"));
        }

        @Override
        Optional<JsonElement> value() {
            int index = ((ComboBox<?>) editor).getSelectedIndex();
            if (optional && index == 0) {
                return Optional.empty();
            }
            int valueIndex = optional ? index - 1 : index;
            if (valueIndex < 0 || valueIndex >= values.size()) {
                return Optional.empty();
            }
            // deepCopy：enum 里的 JsonPrimitive 被复用会让后面的 JSON 打印出意外结果
            return Optional.of(values.get(valueIndex).deepCopy());
        }
    }

    private static ComboBox<Object> createEnumCombo(JsonObject schema, JsonArray values, boolean required,
                                                    JsonElement initial) {
        List<Object> items = new ArrayList<>();
        if (!required) {
            items.add(I18n.t("sf.bool.unset"));
        }
        int selected = required ? 0 : 1;
        for (int i = 0; i < values.size(); i++) {
            JsonElement e = values.get(i);
            items.add(e.isJsonPrimitive() ? e.getAsString() : JsonUtil.compact(e));
            if (initial == null && e.equals(SchemaUtil.defaultValue(schema))) {
                selected = required ? i : i + 1;
            } else if (initial != null && initial.equals(e)) {
                selected = required ? i : i + 1;
            }
        }
        ComboBox<Object> combo = new ComboBox<>(items.toArray());
        combo.setSelectedIndex(Math.max(0, Math.min(selected, items.size() - 1)));
        combo.setToolTipText(required ? I18n.t("sf.enum.requiredTooltip") : I18n.t("sf.enum.optionalTooltip"));
        return Ui.compactInput(combo, 200);
    }

    private final class ListField extends Field {
        private final JsonObject itemSchema;

        ListField(String name, JsonObject schema, boolean required, String path, JsonObject itemSchema,
                  JsonElement initial) {
            super(name, schema, required, path, createArea(listText(initial)));
            this.itemSchema = itemSchema;
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((JBTextArea) editor).getText();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            List<String> raw = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) {
                    raw.add(line.strip());
                }
            }
            // 单行时也接受逗号分隔：手写数组最常见的写法
            if (raw.size() == 1 && raw.get(0).contains(",")) {
                raw = new ArrayList<>();
                for (String part : text.split(",")) {
                    if (!part.isBlank()) {
                        raw.add(part.strip());
                    }
                }
            }
            String itemType = SchemaUtil.typeOf(itemSchema);
            JsonArray array = new JsonArray();
            for (int i = 0; i < raw.size(); i++) {
                array.add(convert(raw.get(i), itemType, i + 1));
            }
            return Optional.of(array);
        }

        private JsonElement convert(String raw, String itemType, int position) {
            try {
                return switch (itemType) {
                    case "integer" -> new JsonPrimitive(Long.parseLong(raw));
                    case "number" -> new JsonPrimitive(Double.parseDouble(raw));
                    case "boolean" -> new JsonPrimitive(parseBool(raw));
                    default -> new JsonPrimitive(raw);
                };
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("integer".equals(itemType)
                        ? I18n.t("sf.error.listNotInteger", position, Ui.ellipsize(raw, 20))
                        : I18n.t("sf.error.listNotNumber", position, Ui.ellipsize(raw, 20)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(I18n.t("sf.error.listNotBool", position, Ui.ellipsize(raw, 20)));
            }
        }

        private boolean parseBool(String raw) {
            if ("true".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return false;
            }
            throw new IllegalArgumentException(I18n.t("sf.error.notBool"));
        }

        private static String listText(JsonElement initial) {
            if (initial == null || !initial.isJsonArray()) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (JsonElement e : initial.getAsJsonArray()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(e.isJsonPrimitive() ? e.getAsString() : JsonUtil.compact(e));
            }
            return sb.toString();
        }
    }

    private final class JsonField extends Field {

        JsonField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path,
                    Editors.sized(Editors.jsonEditor(project, jsonText(schema, initial), null), 72));
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((EditorTextField) editor).getText();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            JsonElement parsed = JsonUtil.tryParse(text);
            if (parsed == null) {
                throw new IllegalArgumentException(I18n.t("sf.error.badJson"));
            }
            return Optional.of(parsed);
        }
    }

    private static String jsonText(JsonObject schema, JsonElement initial) {
        JsonElement source = initial != null ? initial : SchemaUtil.defaultValue(schema);
        if (source == null || source.isJsonNull()) {
            return "";
        }
        return JsonUtil.pretty(source);
    }

    private final class ObjectField extends Field {
        private final Node node;

        ObjectField(String name, JsonObject schema, boolean required, String path, JsonElement initial, int depth) {
            super(name, schema, required, path, createBody(schema, initial, depth, path));
            @SuppressWarnings("unchecked")
            Node created = (Node) ((JPanel) editor).getClientProperty(NODE_KEY);
            this.node = created;
        }

        @Override
        Optional<JsonElement> value() {
            JsonObject object = new JsonObject();
            List<String> nestedProblems = new ArrayList<>();
            node.collect(object, nestedProblems);
            // 子字段的问题已经各自标在自己身上了，这里只负责把有值的部分拼出来
            return object.size() == 0 ? Optional.empty() : Optional.of(object);
        }

        @Override
        void collect(JsonObject into, List<String> problems) {
            Optional<JsonElement> box = value();
            if (box.isEmpty()) {
                if (required) {
                    problems.add(I18n.t("sf.error.missingRequired", path));
                    setError(I18n.t("sf.error.requiredObject"));
                }
                return;
            }
            into.add(name, box.get());
        }
    }

    private static final String NODE_KEY = "mcp.schema.node";

    /** 递归构建子层级，并把 Node 挂在面板上供 {@link ObjectField} 取回。 */
    private JComponent createBody(JsonObject schema, JsonElement initial, int depth, String path) {
        JsonObject initialObject = initial != null && initial.isJsonObject() ? initial.getAsJsonObject() : null;
        Node node = new Node(schema);
        buildNode(node, initialObject, depth + 1, path, true);
        node.panel.putClientProperty(NODE_KEY, node);
        return node.panel;
    }
}
