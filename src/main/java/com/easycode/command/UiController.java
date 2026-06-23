package com.easycode.command;

import com.easycode.permission.PermissionMode;
import java.util.List;

/**
 * 界面控制接口：让命令实现不绑定具体渲染框架。
 * Tui 实现此接口，命令通过此接口操作 UI 而不直接依赖终端细节。
 */
public interface UiController {
    void showMessage(String message);
    void sendUserMessage(String message);
    void switchMode(PermissionMode mode);
    PermissionMode currentMode();
    int[] tokenUsage();
    void refreshStatus();
    void clearScreen();
    String triggerCompact();
    String sessionId();
    long startTimeMs();
    /** 加载会话历史到当前 AgentLoop（用于 /session resume） */
    void loadSessionHistory(List<com.easycode.conversation.MessageRecord> messages);
}
