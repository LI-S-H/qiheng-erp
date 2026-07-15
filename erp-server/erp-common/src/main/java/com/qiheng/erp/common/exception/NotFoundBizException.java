package com.qiheng.erp.common.exception;

/** 需要以 HTTP 404 返回、同时保留统一业务错误码的异常。 */
public class NotFoundBizException extends BizException {

    public NotFoundBizException(ErrorCode errorCode) {
        super(errorCode);
    }
}
