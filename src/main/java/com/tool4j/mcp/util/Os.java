package com.tool4j.mcp.util;

import java.io.File;
import java.util.Locale;

/**
 * 只依赖 JDK 的操作系统判断。
 *
 * <p>协议层刻意不引用 {@code com.intellij.openapi.util.SystemInfo}：这样 transport / protocol
 * 包可以脱离 IDE 单测，也避免以后想在 CLI 里复用这段 MCP 客户端时被平台 API 绑死。
 */
public final class Os {

    public static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private Os() {
    }

    /**
     * Windows 上是否需要套一层 {@code cmd /c}。
     *
     * <p>{@code ProcessBuilder} 走的是 CreateProcess，只能直接执行 PE 文件；
     * {@code npx}、{@code uvx}、{@code pnpm} 这类命令实际是 {@code .cmd} 批处理，
     * 必须由 cmd.exe 解释。判断规则：扩展名是原生可执行（exe/com）或指向一个真实存在的文件时直接执行，
     * 其余一律交给 {@code cmd /c}。
     */
    public static boolean needsShellWrapper(String command) {
        if (!IS_WINDOWS || command == null) {
            return false;
        }
        String cmd = command.trim();
        if (cmd.isEmpty()) {
            return false;
        }
        String lower = cmd.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".exe") || lower.endsWith(".com")) {
            return false;
        }
        if (lower.endsWith(".bat") || lower.endsWith(".cmd")) {
            // 批处理也得靠 cmd 解释，但不要重复包：cmd /c xx.cmd 是合法的
            return true;
        }
        return !new File(cmd).isFile();
    }

    /** 子进程默认工作目录：没配就返回空串（交给 IDE 进程当前目录）。 */
    public static String orEmpty(String path) {
        return path == null ? "" : path.trim();
    }
}
