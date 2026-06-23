package com.easycode.team.runtime;

import com.easycode.team.MemberStatus;

/** 成员运行后端接口 */
public interface MemberRuntime {

    /** 启动成员执行 */
    void start();

    /** 停止成员 */
    void stop();

    /** 是否正在运行 */
    boolean isRunning();

    /** 获取当前状态 */
    MemberStatus getStatus();

    /** 获取会话 ID，供上下文恢复使用 */
    String getSessionId();
}
