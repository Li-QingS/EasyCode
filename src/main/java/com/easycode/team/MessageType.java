package com.easycode.team;

/** 消息类型 */
public enum MessageType {
    /** 普通文本消息 */
    TEXT,
    /** 审批请求（成员→Lead） */
    APPROVAL_REQUEST,
    /** 审批回复（Lead→成员，body 以 APPROVED|REJECTED 开头） */
    APPROVAL_REPLY,
    /** 状态通知（成员状态变更） */
    STATUS,
    /** 任务指派 */
    ASSIGNMENT
}
