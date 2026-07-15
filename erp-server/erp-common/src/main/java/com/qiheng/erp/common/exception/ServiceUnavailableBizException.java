package com.qiheng.erp.common.exception;

/** 依赖能力尚未启用或临时不可用时使用的 HTTP 503 业务异常。 */
public class ServiceUnavailableBizException extends BizException {

    public ServiceUnavailableBizException(ErrorCode errorCode) {
        super(errorCode);
    }
}
