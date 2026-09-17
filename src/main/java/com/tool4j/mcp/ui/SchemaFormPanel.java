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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 由工具的 {@code inputSchema} 自动生成参数表单。
 *
 * <p>这是整个插件里最"值钱"的一块：MCP 工具的入参必须是 JSON，手写 JSON 调一次工具要反复查
 * schema、反复试错。这里按 schema 把它变成真正的控件：
 *
 * <table border="1">
 *   <caption>类型映射</caption>
 *   <tr><th>schema</th><th>控件</th></tr>
 *   <tr><td>{@code enum}</td><td>下拉框；选填时多一个「未设置」</td></tr>
 *   <tr><td>{@code string}</td><td>单行输入；描述很长或 {@code format=multiline} 时改成多行文本域</td></tr>
 *   <tr><td>{@code integer} / {@code number}</td><td>数字输入，提交时按类型解析，不是数字就红字提示</td></tr>
 *   <tr><td>{@code boolean}</td><td>必填用复选框；选填用「未设置 / true / false」，避免手一抖把 false 发出去</td></tr>
 *   <tr><td>{@code array}（元素是标量）</td><td>多行文本，每行一个值（单行时也认逗号分隔）</td></tr>
 *   <tr><td>{@code array}（元素是对象）</td><td>JSON 编辑器</td></tr>
 *   <tr><td>{@code object}</td><td>递归成带缩进的分组；超过 3 层改用 JSON 编辑器</td></tr>
 *   <tr><td>{@code oneOf} / {@code anyOf}</td><td>JSON 编辑器（"二选一"表单表达不了，不猜）</td></tr>
 * </table>
 *
 * <p>必填项在标签后跟一个红 {@code *}，字段下方一行灰字给出类型约束（枚举项数、范围、默认值…），
 * 校验失败时同一位置变红字并把视图滚动到第一个出错的字段。
 *
 * <p>表单与 JSON 故意做成两个页签、<b>以当前所在页签为准</b>，不做自动双向同步：
 * "切到 JSON 改两笔再切回表单，改动被覆盖"是这类工具最容易踩的坑，与其让用户踩一次不如不做。
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

    private final Project project;
    private final JPanel host = new JPanel(new BorderLayout());
    private final JBLabel emptyHint = Ui.hint("该工具不需要参数，直接点「调用」即可。");
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

        emptyHint.setBorder(JBUI.Borders.empty(8, 4));
        rebuild(null);
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
        host.revalidate();
        host.repaint();
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

            GridBagConstraints labelConstraints = new GridBagConstraints();
            labelConstraints.gridx = 0;
            labelConstraints.gridy = node.row;
            labelConstraints.anchor = GridBagConstraints.NORTHWEST;
            labelConstraints.insets = new Insets(JBUI.scale(5), JBUI.scale(leftInset), JBUI.scale(5), JBUI.scale(10));
            labelConstraints.weightx = 0;
            node.panel.add(buildLabel(schema, name, required), labelConstraints);

            GridBagConstraints fieldConstraints = new GridBagConstraints();
            fieldConstraints.gridx = 1;
            fieldConstraints.gridy = node.row;
            fieldConstraints.anchor = GridBagConstraints.NORTHWEST;
            fieldConstraints.fill = GridBagConstraints.HORIZONTAL;
            fieldConstraints.weightx = 1;
            fieldConstraints.insets = new Insets(JBUI.scale(5), 0, JBUI.scale(5), JBUI.scale(6));
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
    private JComponent buildLabel(JsonObject schema, String name, boolean required) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));

        String title = SchemaUtil.titleOf(schema, name);
        JBLabel label = new JBLabel(title);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setToolTipText("字段名：" + name);
        panel.add(label);

        if (!title.equals(name)) {
            JBLabel key = Ui.hint(" " + name);
            key.setToolTipText("实际发送给服务端的字段名");
            panel.add(key);
        }
        if (required) {
            JBLabel star = new JBLabel(" *");
            star.setForeground(JBColor.RED);
            star.setToolTipText("必填");
            panel.add(star);
        }
        return panel;
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
            default -> new JsonField(name, schema, required, path, initialValue);
        };
    }

    private static boolean isScalarType(String type) {
        return "string".equals(type) || "integer".equals(type) || "number".equals(type) || "boolean".equals(type);
    }

    private static boolean isLongText(JsonObject schema) {
        String format = JsonUtil.str(schema, "format", "");
        if ("multiline".equals(format) || "textarea".equals(format)) {
            return true;
        }
        String description = SchemaUtil.descriptionOf(schema);
        if (description != null && description.length() > 110) {
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

        Field(String name, JsonObject schema, boolean required, String path, JComponent editor) {
            this.name = name;
            this.path = path;
            this.schema = schema;
            this.required = required;
            this.editor = editor;

            JPanel stack = new JPanel();
            stack.setOpaque(false);
            stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));

            editor.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(editor);

            String hint = buildHint();
            if (!hint.isEmpty()) {
                JBLabel hintLabel = Ui.htmlHint(Ui.escapeHtml(hint));
                hintLabel.setToolTipText(tooltip());
                JPanel hintRow = new JPanel(new BorderLayout());
                hintRow.setOpaque(false);
                hintRow.setBorder(JBUI.Borders.emptyTop(2));
                hintRow.add(hintLabel, BorderLayout.WEST);
                hintRow.setAlignmentX(Component.LEFT_ALIGNMENT);
                stack.add(hintRow);
            }

            error.setForeground(JBColor.RED);
            error.setFont(Ui.smaller(error.getFont()));
            JPanel errorRow = new JPanel(new BorderLayout());
            errorRow.setOpaque(false);
            errorRow.add(error, BorderLayout.WEST);
            errorRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            stack.add(errorRow);

            stack.setAlignmentX(Component.LEFT_ALIGNMENT);
            cell.setOpaque(false);
            cell.add(stack, BorderLayout.CENTER);
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
                sb.append(" <font color='#C0392B'>必填</font>");
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

        /** 空 Optional 表示"不提交这个字段"。 */
        abstract Optional<JsonElement> value();

        void collect(JsonObject into, List<String> problems) {
            Optional<JsonElement> box;
            try {
                box = value();
            } catch (IllegalArgumentException e) {
                problems.add(path + "：" + e.getMessage());
                setError(e.getMessage());
                return;
            }
            if (box == null || box.isEmpty()) {
                if (required) {
                    problems.add("缺少必填参数 " + path);
                    setError("必填，请填写");
                }
                return;
            }
            into.add(name, box.get());
        }

        void setError(String message) {
            error.setText(message == null || message.isEmpty() ? "" : "⚠ " + message);
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

    private final class TextField extends Field {

        TextField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, new JBTextField());
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
        area.setRows(3);
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
            super(name, schema, required, path, new JBTextField());
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
                    throw new NumberFormatException("非法数值");
                }
                return Optional.of(new JsonPrimitive(parsed));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("「" + trimmed + "」不是合法的"
                        + ("integer".equals(type) ? "整数" : "数字"));
            }
        }
    }

    private final class BoolField extends Field {

        BoolField(String name, JsonObject schema, boolean required, String path, JsonElement initial) {
            super(name, schema, required, path, createBoolEditor(schema, required, initial));
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
            JCheckBox box = new JCheckBox("启用");
            box.setSelected(initialBool);
            box.setToolTipText("必填布尔值：勾选为 true，不勾选为 false");
            return box;
        }
        ComboBox<String> combo = new ComboBox<>(new String[]{"未设置", "true", "false"});
        combo.setSelectedIndex(initial == null ? 0 : (initialBool ? 1 : 2));
        combo.setToolTipText("选填布尔值：保持「未设置」就不会把该字段发给服务端");
        combo.setMaximumSize(new Dimension(JBUI.scale(180), JBUI.scale(28)));
        return combo;
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
            items.add("未设置");
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
        combo.setToolTipText(required ? "必填枚举" : "选填枚举：保持「未设置」就不会把该字段发给服务端");
        combo.setMaximumSize(new Dimension(JBUI.scale(300), JBUI.scale(28)));
        return combo;
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
                throw new IllegalArgumentException("第 " + position + " 个元素「" + Ui.ellipsize(raw, 20)
                        + "」不是合法的" + ("integer".equals(itemType) ? "整数" : "数字"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("第 " + position + " 个元素「" + Ui.ellipsize(raw, 20)
                        + "」不是布尔值（只能用 true / false）");
            }
        }

        private boolean parseBool(String raw) {
            if ("true".equalsIgnoreCase(raw)) {
                return true;
            }
            if ("false".equalsIgnoreCase(raw)) {
                return false;
            }
            throw new IllegalArgumentException("不是布尔值");
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
                    Editors.sized(Editors.jsonEditor(project, jsonText(schema, initial), null), 96));
        }

        @Override
        Optional<JsonElement> value() {
            String text = ((EditorTextField) editor).getText();
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            JsonElement parsed = JsonUtil.tryParse(text);
            if (parsed == null) {
                throw new IllegalArgumentException("不是合法的 JSON（要么清空，要么补完）");
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
                    problems.add("缺少必填参数 " + path);
                    setError("必填对象，请至少填写一个子字段");
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
