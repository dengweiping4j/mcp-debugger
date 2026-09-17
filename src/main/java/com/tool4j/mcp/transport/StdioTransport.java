package com.tool4j.mcp.transport;

import com.google.gson.JsonObject;

import com.tool4j.mcp.model.McpServerConfig;
import com.tool4j.mcp.protocol.JsonRpc;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpException;
import com.tool4j.mcp.util.Os;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * stdio 传输：把服务端当子进程拉起来，通过 stdin / stdout 交换<b>换行分隔</b>的 JSON-RPC 报文。
 *
 * <p>规范里 stdio 的帧格式就是"一行一条 JSON"，不是 LSP 那种 {@code Content-Length} 头，
 * 所以这里按行读写即可；报文内部不允许出现裸换行，序列化时 Gson 会把字符串里的换行转义掉，天然满足。
 *
 * <p>三个实际会遇到的坑，都已经处理：
 * <ol>
 *   <li>子进程往 stdout 打启动日志（非 JSON 行）——跳过并记进日志，不当成协议错误。</li>
 *   <li>stdout 读到 null 就代表进程退出，此时所有在途请求必须立刻失败，而不是等超时。</li>
 *   <li>Windows 上 {@code npx} / {@code uvx} 是 .cmd 批处理，需要 {@code cmd /c} 包装，见 {@link Os#needsShellWrapper}。</li>
 * </ol>
 */
public class StdioTransport extends AbstractTransport {

    private final McpServerConfig config;

    private volatile Process process;
    private volatile BufferedWriter stdin;
    private final Object writeLock = new Object();

    public StdioTransport(McpServerConfig config) {
        this.config = config;
    }

    @Override
    public void start() throws McpException {
        String command = config.getCommand();
        if (command == null || command.isBlank()) {
            throw new McpException("未配置启动命令");
        }

        List<String> argv = new ArrayList<>();
        if (Os.needsShellWrapper(command)) {
            argv.add("cmd");
            argv.add("/c");
        }
        argv.add(command.trim());
        for (String arg : config.getArgs()) {
            if (arg != null && !arg.isEmpty()) {
                argv.add(arg);
            }
        }

        ProcessBuilder pb = new ProcessBuilder(argv);
        // 只覆盖/追加用户显式配置的变量，其余继承 IDE 进程（PATH、HOME 等服务器基本都要）
        pb.environment().putAll(config.envMap());
        String dir = Os.orEmpty(config.getWorkingDir());
        if (!dir.isEmpty()) {
            File f = new File(dir);
            if (!f.isDirectory()) {
                throw new McpException("工作目录不存在：" + dir);
            }
            pb.directory(f);
        }

        try {
            process = pb.start();
        } catch (IOException e) {
            throw new McpException("启动子进程失败：" + JsonUtil.rootMessage(e)
                    + "\n命令：" + String.join(" ", argv)
                    + "\n（若提示找不到命令，请确认该命令在 PATH 中，或填写绝对路径）", e);
        }

        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        daemonThread("mcp-stdio-out-" + shortId(), this::readStdout).start();
        daemonThread("mcp-stdio-err-" + shortId(), this::readStderr).start();
        reportNotice("已启动子进程：pid=" + process.pid() + " → " + String.join(" ", argv));
    }

    // ------------------------------------------------------------------

    private void readStdout() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line.strip();
                if (text.isEmpty()) {
                    continue;
                }
                if (!text.startsWith("{")) {
                    // 子进程的启动日志、进度输出等等：留着看，但不参与协议
                    reportNotice("stdout(非协议输出)：" + JsonUtil.firstLine(text));
                    continue;
                }
                JsonObject message;
                try {
                    message = JsonUtil.parse(text).getAsJsonObject();
                } catch (RuntimeException e) {
                    reportNotice("stdout 报文解析失败，已忽略：" + JsonUtil.firstLine(text));
                    continue;
                }
                handleIncoming(message);
            }
        } catch (IOException e) {
            if (!userClosed) {
                reportNotice("stdout 读取异常：" + JsonUtil.rootMessage(e));
            }
        }
        // stdout 关闭 == 子进程结束了
        if (!userClosed) {
            String reason = "MCP 服务端进程已退出" + exitSuffix();
            failPending(reason);
            reportClosedOnce(reason);
        }
    }

    private void readStderr() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    reportNotice("stderr：" + line);
                }
            }
        } catch (IOException ignored) {
            // 进程结束时读到异常是正常的
        }
    }

    private String exitSuffix() {
        Process p = process;
        if (p == null) {
            return "（进程未启动）";
        }
        try {
            if (!p.isAlive()) {
                return "（exitCode=" + p.exitValue() + "）";
            }
        } catch (IllegalThreadStateException ignored) {
            // 刚好还在跑
        }
        return "";
    }

    // ------------------------------------------------------------------

    @Override
    public JsonObject request(JsonObject request, long timeoutMillis) throws McpException {
        ensureRunning();
        Long id = JsonRpc.idAsLong(request);
        if (id == null) {
            throw new McpException("内部错误：stdio 请求缺少数字 id");
        }
        String method = JsonRpc.methodOf(request);
        // 先注册再发送：响应完全可能在我们写完 stdout 的瞬间就回来了
        CompletableFuture<JsonObject> box = register(id);
        try {
            write(request);
        } catch (McpException e) {
            unregister(id);
            throw e;
        }
        return await(id, box, timeoutMillis, method);
    }

    @Override
    public void send(JsonObject message) throws McpException {
        ensureRunning();
        write(message);
    }

    private void write(JsonObject message) throws McpException {
        String payload = JsonUtil.compact(message);
        reportSend(message);
        synchronized (writeLock) {
            try {
                stdin.write(payload);
                stdin.write('\n');
                stdin.flush();
            } catch (IOException e) {
                throw new McpException("向服务端进程写入失败：" + JsonUtil.rootMessage(e)
                        + "（子进程可能已经退出）", e);
            }
        }
    }

    private void ensureRunning() throws McpException {
        if (userClosed) {
            throw new McpException("连接已关闭");
        }
        Process p = process;
        if (p == null) {
            throw new McpException("子进程尚未启动");
        }
        if (!p.isAlive()) {
            throw new McpException("服务端进程已经退出" + exitSuffix());
        }
    }

    @Override
    public boolean isAlive() {
        Process p = process;
        return !userClosed && p != null && p.isAlive();
    }

    @Override
    public String describe() {
        return "stdio · " + config.getCommandLine();
    }

    @Override
    public void close() {
        userClosed = true;
        failPending("连接已关闭");
        Process p = process;
        if (p != null) {
            closeQuietly(stdin);
            p.destroy();
            // 给服务端一点时间优雅退出，然后再强杀；MCP 服务端卡住不退出很常见
            try {
                if (!p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        process = null;
        stdin = null;
    }

    private String shortId() {
        String id = config.getId();
        return id == null ? "x" : id.substring(0, Math.min(8, id.length()));
    }
}
