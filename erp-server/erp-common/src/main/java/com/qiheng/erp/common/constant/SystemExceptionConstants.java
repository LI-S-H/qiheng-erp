package com.qiheng.erp.common.constant;

/**
 * 系统异常记录常量。
 *
 * <p>消息类型、来源模块等取值与 {@code system_exception.exception_type} 等字段的
 * 取值约定保持一致；消息体 {@code SystemExceptionRecordMessage} 只保留字段，
 * 常量统一收敛在本类，避免常量参与消息序列化。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
public final class SystemExceptionConstants {

    /** 消息类型标识，便于后续扩展识别 */
    public static final String MSG_TYPE = "SYSTEM_ERROR_RECORD";

    /** 消费端生成的异常编号前缀，格式 SE{yyyyMMdd}{5位序号} */
    public static final String EXCEPTION_NO_PREFIX = "SE";

    /** 异常类型：HTTP 未知异常（全局拦截器兜底捕获） */
    public static final String TYPE_SYSTEM_ERROR = "SYSTEM_ERROR";

    /** 异常类型：定时任务执行失败 */
    public static final String TYPE_JOB_FAILED = "JOB_FAILED";

    /** 异常类型：AI 模块 MCP 工具/大模型调用失败 */
    public static final String TYPE_MCP_TOOL_FAILED = "MCP_TOOL_FAILED";

    /** 异常类型：MQ 消费重试耗尽进入死信队列 */
    public static final String TYPE_MQ_DEAD_LETTER = "MQ_DEAD_LETTER";

    /** 异常类型：第三方回调处理失败 */
    public static final String TYPE_EXT_CALLBACK_FAILED = "EXT_CALLBACK_FAILED";

    /** 异常类型：补偿任务重试仍失败 */
    public static final String TYPE_COMPENSATION_FAILED = "COMPENSATION_FAILED";

    /** 严重级别：高，需要人工尽快介入 */
    public static final String SEVERITY_HIGH = "HIGH";

    /** 严重级别：中 */
    public static final String SEVERITY_MEDIUM = "MEDIUM";

    /** 严重级别：低，仅记录趋势 */
    public static final String SEVERITY_LOW = "LOW";

    /** 定时任务来源模块固定值 */
    public static final String MODULE_SCHEDULER = "SCHEDULER";

    /** 定时任务失败错误码 */
    public static final String CODE_JOB_EXECUTION_FAILED = "JOB_EXECUTION_FAILED";

    private SystemExceptionConstants() {
    }
}
