package com.easycode.team;

/** 成员运行后端类型 */
public enum RuntimeBackend {
    /** 同进程轻量执行 */
    IN_PROCESS,
    /** 独立终端窗格强隔离 */
    TERMINAL_WINDOW
}
