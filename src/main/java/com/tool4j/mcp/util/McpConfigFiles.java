package com.tool4j.mcp.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描本机与当前工程里已知的 MCP 配置文件位置。
 *
 * <p>写 MCP 客户端的人都知道：配置基本都以 {@code mcpServers} 的形式散落在各家客户端里，
 * 与其让用户去翻目录，不如把常见位置扫一遍让他勾选。这里只做"发现"，不读内容——
 * 内容解析交给 {@code McpConfigParser}，两边职责分开也方便单测。
 */
public final class McpConfigFiles {

    /** 一个被发现（且确实存在）的候选配置文件。 */
    public static final class Candidate {
        private final String label;
        private final File file;
        private final boolean projectScoped;

        Candidate(String label, File file, boolean projectScoped) {
            this.label = label;
            this.file = file;
            this.projectScoped = projectScoped;
        }

        /** 例如 {@code 当前工程 · .mcp.json}。 */
        public String getLabel() {
            return label;
        }

        public File getFile() {
            return file;
        }

        public String getPath() {
            return file.getAbsolutePath();
        }

        public boolean isProjectScoped() {
            return projectScoped;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private McpConfigFiles() {
    }

    /**
     * @param projectBasePath 工程根目录，可为 null（此时只扫用户级位置）
     */
    public static List<Candidate> scan(String projectBasePath) {
        List<Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        if (projectBasePath != null && !projectBasePath.isBlank()) {
            Path base = Path.of(projectBasePath);
            add(out, seen, "当前工程", true, base.resolve(".mcp.json"));
            add(out, seen, "当前工程 (Claude Code)", true, base.resolve(".claude.json"));
            add(out, seen, "当前工程 (Cursor)", true, base.resolve(".cursor").resolve("mcp.json"));
            add(out, seen, "当前工程 (VS Code)", true, base.resolve(".vscode").resolve("mcp.json"));
            add(out, seen, "当前工程 (Roo)", true, base.resolve(".roo").resolve("mcp.json"));
            add(out, seen, "当前工程 (Kiro)", true, base.resolve(".kiro").resolve("settings").resolve("mcp.json"));
            add(out, seen, "当前工程 (Gemini CLI)", true, base.resolve(".gemini").resolve("settings.json"));
            add(out, seen, "当前工程", true, base.resolve("mcp.json"));
        }

        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            Path h = Path.of(home);
            add(out, seen, "Cursor (全局)", false, h.resolve(".cursor").resolve("mcp.json"));
            add(out, seen, "Windsurf", false, h.resolve(".codeium").resolve("windsurf").resolve("mcp_config.json"));
            add(out, seen, "Gemini CLI (全局)", false, h.resolve(".gemini").resolve("settings.json"));
            add(out, seen, "Kiro (全局)", false, h.resolve(".kiro").resolve("settings").resolve("mcp.json"));
            add(out, seen, "Amazon Q", false, h.resolve(".aws").resolve("amazonq").resolve("mcp.json"));
            add(out, seen, "Claude Desktop", false,
                    h.resolve("Library").resolve("Application Support").resolve("Claude").resolve("claude_desktop_config.json"));
        }

        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path a = Path.of(appData);
            add(out, seen, "Claude Desktop", false, a.resolve("Claude").resolve("claude_desktop_config.json"));
            add(out, seen, "Cursor (全局)", false, a.resolve("Cursor").resolve("mcp.json"));
        }
        String xdgConfig = System.getenv("XDG_CONFIG_HOME");
        if (xdgConfig != null && !xdgConfig.isBlank()) {
            add(out, seen, "Claude Desktop (XDG)", false,
                    Path.of(xdgConfig).resolve("Claude").resolve("claude_desktop_config.json"));
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            if (!home.isBlank()) {
                add(out, seen, "Claude Desktop (Linux)", false,
                        Path.of(home).resolve(".config").resolve("Claude").resolve("claude_desktop_config.json"));
            }
        }
        return out;
    }

    private static void add(List<Candidate> out, Set<String> seen, String label, boolean projectScoped, Path path) {
        File file = path.toFile();
        if (!file.isFile()) {
            return;
        }
        String key = file.getAbsolutePath().toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            return;
        }
        String shown = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        out.add(new Candidate(label + " · " + shown, file, projectScoped));
    }

    public static String readQuietly(File file) {
        try {
            return Files.readString(file.toPath());
        } catch (Exception e) {
            return "";
        }
    }
}
