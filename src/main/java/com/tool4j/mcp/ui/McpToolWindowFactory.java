package com.tool4j.mcp.ui;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;

import org.jetbrains.annotations.NotNull;

/**
 * 把 {@link McpPanel} 装进工具窗口。
 *
 * <p>面板的生命周期挂到 {@link ToolWindow#getDisposable()} 上：窗口关掉（或 IDE 退出）时
 * 面板的 {@code dispose()} 会被调用，从而摘掉报文监听、注销消息总线订阅。
 *
 * <p>真实的服务端会话不在这里维护——它们活在 {@code McpProjectService} 里，
 * 所以关掉工具窗口再打开，上一次拉到的工具列表还在。
 */
public final class McpToolWindowFactory implements ToolWindowFactory {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        McpPanel panel = new McpPanel(project, toolWindow.getDisposable());
        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        // 任何工程类型里都能用：MCP 面向前端 / 脚本 / 知识库类工具，跟语言无关
        return true;
    }
}
