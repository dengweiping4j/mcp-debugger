package com.tool4j.mcp.transport;

import com.google.gson.JsonObject;

import com.tool4j.mcp.i18n.I18n;
import com.tool4j.mcp.protocol.JsonRpc;
import com.tool4j.mcp.protocol.JsonUtil;
import com.tool4j.mcp.protocol.McpException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 三种传输共用的骨架：报文的收/发上报、请求与响应的配对、超时与断线善后。
 *
 * <p>关键约定：<b>先注册再发送</b>。响应可能在我们写完 stdout 的那一刻就回来了，
 * 如果先 send 再 register，就会漏掉这个响应并一直等到超时——这是手写 MCP 客户端最常见的坑。
 */
public abstract class AbstractTransport implements McpTransport {

    /** 已发出、尚未收到响应的请求：id → 等待箱。 */
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private final AtomicBoolean closedReported = new AtomicBoolean();

    protected volatile Listener listener;

    /** 我们自己调用 {@link #close()} 置位；置位后不再上报"被动断开"。 */
    protected volatile boolean userClosed;

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    // ------------------------------------------------------------------
    // 收报文
    // ------------------------------------------------------------------

    /**
     * 收到一条报文。
     *
     * @return true 表示这是响应（已经尝试配对等待箱；配对不上说明是本次 HTTP 请求的响应，
     * 由调用方直接取用）
     */
    protected boolean handleIncoming(JsonObject message) {
        Listener l = listener;
        if (l != null) {
            report(() -> l.onReceive(message));
        }
        if (JsonRpc.isResponse(message)) {
            Long id = JsonRpc.idAsLong(message);
            if (id != null) {
                CompletableFuture<JsonObject> box = pending.remove(id);
                if (box != null) {
                    box.complete(message);
                }
            }
            return true;
        }
        if (l != null) {
            report(() -> l.onProtocolMessage(message));
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 发报文
    // ------------------------------------------------------------------

    protected void reportSend(JsonObject message) {
        Listener l = listener;
        if (l != null) {
            report(() -> l.onSend(message));
        }
    }

    protected void reportNotice(String text) {
        Listener l = listener;
        if (l != null) {
            report(() -> l.onNotice(text));
        }
    }

    /** 监听器是上层 UI 代码，出异常不能把传输通道带崩。 */
    private void report(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 上报失败不影响协议流程
        }
    }

    // ------------------------------------------------------------------
    // 请求 / 响应配对
    // ------------------------------------------------------------------

    protected CompletableFuture<JsonObject> register(long id) {
        CompletableFuture<JsonObject> box = new CompletableFuture<>();
        pending.put(id, box);
        return box;
    }

    /** 发送阶段就失败时，把已经登记的等待箱撤掉，免得它一直挂着。 */
    protected void unregister(long id) {
        pending.remove(id);
    }

    protected JsonObject await(long id, CompletableFuture<JsonObject> box, long timeoutMillis, String method)
            throws McpException {
        try {
            return box.get(Math.max(1000L, timeoutMillis), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpException(I18n.t("err.call.timeout", method, timeoutMillis / 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException(I18n.t("err.call.interrupted", method));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpException me) {
                throw me;
            }
            throw new McpException(I18n.t("err.call.failed", method, JsonUtil.rootMessage(e)), cause);
        } finally {
            pending.remove(id);
        }
    }

    protected void failPending(String reason) {
        List<Long> ids = new ArrayList<>(pending.keySet());
        for (Long id : ids) {
            CompletableFuture<JsonObject> box = pending.remove(id);
            if (box != null) {
                box.completeExceptionally(new McpException(reason));
            }
        }
    }

    // ------------------------------------------------------------------
    // 断线
    // ------------------------------------------------------------------

    /** 被动断开：只上报一次。 */
    protected void reportClosedOnce(String reason) {
        if (userClosed) {
            return;
        }
        if (closedReported.compareAndSet(false, true)) {
            Listener l = listener;
            if (l != null) {
                report(() -> l.onClosed(reason));
            }
        }
    }

    /** 起一个守护线程：IDE 退出时不能因为 MCP 的读写线程卡住进程。 */
    protected static Thread daemonThread(String name, Runnable body) {
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    /**
     * 把网络 IO 失败翻译成人话。
     *
     * <p>不能直接用 {@code JsonUtil.rootMessage}：它取的是<b>最深</b>的那层 cause，
     * 而连接被拒时最深的是 {@code ClosedChannelException}——类名对用户毫无信息量。
     * 真正有用的是链中间的 {@code ConnectException} / {@code UnknownHostException}。
     * 所以这里沿整条 cause 链找第一个"认得出来"的，按它的语义给话。
     */
    protected static String describeIoFailure(IOException e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof java.net.ConnectException) {
                return I18n.t("err.io.connect");
            }
            if (t instanceof java.net.UnknownHostException) {
                return I18n.t("err.io.unknownHost", t.getMessage());
            }
            if (t instanceof java.net.SocketTimeoutException) {
                return I18n.t("err.io.connectTimeout");
            }
            if (t instanceof javax.net.ssl.SSLException) {
                return I18n.t("err.io.tls", t.getMessage());
            }
        }
        return JsonUtil.rootMessage(e);
    }

    protected static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // 关闭异常无所谓
        }
    }
}
