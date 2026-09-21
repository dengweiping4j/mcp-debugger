package com.tool4j.mcp.model;

/**
 * 一次工具调用的记录：当时发出去的入参 + 最后落到什么结局。
 *
 * <p>它是「历史」页签的数据来源——调试时最常见的动作不是"从零填一遍参数"，而是
 * "把上一次那份原样再发一次"或"在上次的基础上改一个值"，所以入参必须留下来。
 *
 * <p><b>为什么是"只有 public 字段、没有方法"的纯数据结构</b>：它要经 {@code XmlSerializer}
 * 落到 {@code mcp-debugger-history.xml}，而序列化器对"只有 getter、没有 setter"这类成员的
 * 处理在不同平台版本上并不一致，纯字段最稳（{@link McpServerConfig} 也是这个路子）。
 * 展示用的格式化逻辑因此全部放在界面层（{@code CallHistoryPanel}）。
 *
 * <p><b>入参存字符串而不是 {@code JsonObject}</b>：XmlSerializer 不认识 Gson 的类型；
 * 存成紧凑 JSON 原文既稳、又可读——翻配置文件时能直接看出当时发了什么。
 *
 * <p><b>为什么必须带 {@link #serverId}</b>：工具重名太常见了（两台服务器都提供一个叫
 * {@code search} 的工具一点都不稀奇），历史不能串台。
 */
public class ToolCallRecord {

    /**
     * 这次调用最后落在哪一档。
     *
     * <p>三态而不是一个布尔：{@link #TOOL_ERROR} 是"请求到了、工具自己报错"
     * （{@code result.isError = true}），{@link #FAILED} 是"压根没成功发出去或没等到响应"
     * （未连接、超时、子进程挂了、JSON-RPC 层直接返错）。两者的排查方向完全不同，
     * 合并成一个"失败"会把最有用的一半信息丢掉。
     */
    public enum Status {
        /** 正常拿到结果。 */
        OK,
        /** 服务端执行了，但报 isError。 */
        TOOL_ERROR,
        /** 本地/协议层失败，没拿到正常结果。 */
        FAILED
    }

    public String serverId = "";

    public String toolName = "";

    /** 当时发出去的入参，紧凑 JSON 原文（如 {@code {"path":"src/main"}}）。 */
    public String arguments = "{}";

    /** 调用发生的时刻，epoch 毫秒。 */
    public long timestamp;

    /** 往返耗时，毫秒。 */
    public long elapsedMillis;

    public Status status = Status.OK;

    /** 失败原因（已压成一行并截断），只用于列表项的 tooltip；成功时为空。 */
    public String note = "";
}
