package com.qiheng.erp.common.mq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import lombok.Data;

import java.io.Serializable;

/**
 * 系统异常记录 MQ 消息体。
 *
 * <p>由各异常来源（全局异常拦截器、定时任务、未来的死信监听与 AI 模块）
 * 通过 {@link SystemExceptionMqPublisher} 投递，erp-dashboard 的
 * SystemExceptionRecordConsumer 单点消费写入 system_exception 表。</p>
 *
 * <p>取值常量统一见 {@code SystemExceptionConstants}；exceptionNo 不在发布端生成：
 * 由消费端通过 BillNoGenerator 单点生成，避免多个发布方抢号。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SystemExceptionRecordMessage implements Serializable {

    /** 固定 msgType，便于后续扩展识别 */
    private String msgType = SystemExceptionConstants.MSG_TYPE;

    /** 一次真实异常的雪花ID；字符串传输避免精度丢失，发送及消费重试必须沿用。 */
    private String eventId;

    /** 异常类型，取值见 SystemExceptionConstants.TYPE_* */
    private String exceptionType;

    /** 来源模块：SALES / PURCHASE / WAREHOUSE / SCHEDULER / AI 等 */
    private String sourceModule;

    /** 来源定位信息：HTTP 异常为"方法 URI"，定时任务为任务标识，死信为消息 Key */
    private String sourceNo;

    /** 严重级别：HIGH / MEDIUM / LOW */
    private String severity;

    /** 错误码，如 99999（UNKNOWN）或具体业务错误码 */
    private String errorCode;

    /** 原始错误信息（表字段 NOT NULL，发布端必须兜底非空） */
    private String errorMessage;

    /** 工作台可展示的一句话摘要（表字段 NOT NULL，发布端必须兜底非空） */
    private String detailSummary;

    /** 处理建议，可空 */
    private String resolveHint;

    /** 异常发生时间，Unix 毫秒 */
    private Long occurredAt;
}
