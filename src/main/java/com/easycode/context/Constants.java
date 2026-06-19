package com.easycode.context;

/** ch08 硬编码常量集合（F36）。调整这些值属于代码变更，不暴露为配置项。 */
public final class Constants {
    private Constants() {}

    /** 单条工具结果落盘阈值（字节，UTF-8 编码后） */
    public static final int SINGLE_RESULT_THRESHOLD_BYTES = 20_000;

    /** 单轮（单条消息内）工具结果聚合落盘阈值（字节） */
    public static final int MESSAGE_AGGREGATE_THRESHOLD_BYTES = 200_000;

    /** 摘要本身预留的输出 token 空间 */
    public static final int SUMMARY_OUTPUT_RESERVE = 20_000;

    /** 自动触发安全余量（token，防估算误差） */
    public static final int AUTO_SAFETY_MARGIN = 13_000;

    /** 手动触发安全余量（token） */
    public static final int MANUAL_SAFETY_MARGIN = 3_000;

    /** 近期原文保留 token 下界 */
    public static final int KEEP_RECENT_TOKEN_MIN = 10_000;

    /** 近期原文保留消息条数下界 */
    public static final int KEEP_RECENT_MESSAGE_MIN = 5;

    /** 恢复段中最大文件快照数量 */
    public static final int MAX_RECENT_FILES = 5;

    /** 单个文件快照的 token 上限 */
    public static final int FILE_SNAPSHOT_TOKEN_MAX = 5_000;

    /** 自动摘要连续失败熔断阈值 */
    public static final int AUTO_SUMMARY_CIRCUIT_BREAKER = 3;

    /** PTL 重试——直接重试上限 */
    public static final int PTL_DIRECT_RETRY_MAX = 3;

    /** PTL 重试——每次丢消息组的比例步长 */
    public static final double PTL_DROP_RATIO = 0.2;

    /** 字符→token 估算比例（chars per token） */
    public static final double ESTIMATE_CHARS_PER_TOKEN = 3.5;

    /** 预览体头部字节上限 */
    public static final int PREVIEW_HEAD_BYTES_MAX = 2048;

    /** 预览体头部行数上限 */
    public static final int PREVIEW_HEAD_LINES_MAX = 20;

    /** 摘要超时秒数 */
    public static final int SUMMARY_TIMEOUT_SEC = 60;
}
