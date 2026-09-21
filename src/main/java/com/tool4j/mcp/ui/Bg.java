package com.tool4j.mcp.ui;

import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;

import com.tool4j.mcp.i18n.I18n;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * 把阻塞的 MCP 调用丢到后台任务里跑。
 *
 * <p>约定：<b>所有</b> {@code McpClient} 调用都必须经过这里。MCP 的每一次通信都可能要等秒级
 * （拉起子进程、等远端响应、跑一个慢工具），在 EDT 上直接调会把整个 IDE 卡住。
 *
 * <p>成功/失败回调都在 EDT 上执行，界面代码可以放心改控件。
 */
public final class Bg {

    /** 允许抛受检异常的后台工作单元。 */
    public interface Work<T> {
        T run() throws Exception;
    }

    private Bg() {
    }

    public static <T> void run(@Nullable Project project,
                               @NotNull String title,
                               boolean cancellable,
                               @NotNull Work<T> work,
                               @NotNull Consumer<T> onSuccess,
                               @NotNull Consumer<Throwable> onFailure) {
        new Task.Backgroundable(project, title, cancellable) {
            private T result;
            private Throwable failure;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                try {
                    result = work.run();
                } catch (ProcessCanceledException canceled) {
                    // 交给平台按"用户取消"处理，会走到 onCancel
                    throw canceled;
                } catch (Throwable t) {
                    failure = t;
                }
            }

            @Override
            public void onSuccess() {
                if (failure != null) {
                    onFailure.accept(failure);
                } else {
                    onSuccess.accept(result);
                }
            }

            @Override
            public void onCancel() {
                onFailure.accept(new CancellationException(I18n.t("ui.bg.cancelled")));
            }
        }.queue();
    }
}
