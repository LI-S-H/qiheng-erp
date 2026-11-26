package com.qiheng.erp.ai.clarification;

import java.util.List;

/**
 * 追问校验的流水写入入口；只写 ai_agent_trace，不写用户可见消息。
 *
 * @author Li
 * @since 2026-11-26
 */
public interface ClarificationTraceSink {

    /**
     * 记录一次非法追问参数。
     *
     * @param callId 工具调用编号
     * @param name 工具名称
     * @param arguments 原始参数
     * @param errors 字段级错误
     */
    void traceAskUserValidationFailure(String callId, String name, String arguments,
                                       List<ClarificationRules.FieldError> errors);

    /**
     * 记录一次追问工具结果；仅用于终止路径，其他路径由模型调用前统一记录。
     *
     * @param callId 工具调用编号
     * @param name 工具名称
     * @param responseData 结果内容
     */
    void traceAskUserToolResult(String callId, String name, String responseData);

    /** 标记本次运行因追问参数连续失败而终止。 */
    void markClarificationValidationFailed();
}