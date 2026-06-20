package com.easycode.command;

/** 命令执行模式分类 */
public enum CommandType {
    /** 纯本地操作：不改变界面状态，不发送提示词给 AI */
    LOCAL,
    /** 影响界面状态：切换模式、清屏等，需要调用 UiController 改变 UI */
    UI,
    /** 预设提示词：把一段预设文本当作用户消息送进对话，交给 AI 处理 */
    PROMPT
}
