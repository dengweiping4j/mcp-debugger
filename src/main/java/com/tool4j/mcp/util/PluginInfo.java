package com.tool4j.mcp.util;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;

/**
 * 插件自述信息。
 *
 * <p>{@code clientInfo.version} 会带着真实插件版本发给服务端，服务端日志里能对得上，
 * 排查"到底哪个客户端发的"时很有用。取不到就退回一个常量。
 */
public final class PluginInfo {

    public static final String PLUGIN_ID = "com.tool4j.mcp.debug";
    private static final String FALLBACK_VERSION = "1.0.0";

    private PluginInfo() {
    }

    public static String version() {
        try {
            IdeaPluginDescriptor descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID));
            if (descriptor != null && descriptor.getVersion() != null && !descriptor.getVersion().isBlank()) {
                return descriptor.getVersion();
            }
        } catch (Throwable ignored) {
            // 开发态（runIde）或 descriptor 尚未注册时取不到，退回常量
        }
        return FALLBACK_VERSION;
    }
}
