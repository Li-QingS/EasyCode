package com.easycode.team;

/** 成员工作状态 */
public enum MemberStatus {
    /** 空闲，可接收新任务 */
    IDLE,
    /** 工作中 */
    WORKING,
    /** 等待 Lead 审批 */
    WAITING_APPROVAL
}
