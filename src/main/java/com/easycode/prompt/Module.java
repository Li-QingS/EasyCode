package com.easycode.prompt;

/** 系统提示模块：优先级数值越小越靠前，content 为空时装配跳过 */
public record Module(String name, int priority, String content) {}
