package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.common.result.Result;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** SSE建立前仍返回JSON错误，显式指定媒体类型避免Accept:SSE导致错误响应无法序列化。 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = AiAssistantController.class)
public class AiAssistantExceptionHandler {
    @ExceptionHandler(NotLoginException.class)
    public ResponseEntity<Result<Void>> notLoggedIn(NotLoginException exception) {
        return error(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    @ExceptionHandler(NotPermissionException.class)
    public ResponseEntity<Result<Void>> forbidden(NotPermissionException exception) {
        return error(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> unreadable(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Result<Void>> mismatchedArgument(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, ErrorCode.PARAM_ERROR);
    }

    private ResponseEntity<Result<Void>> error(HttpStatus status, ErrorCode code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(code.getCode(), code.getMessage()));
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> business(BizException exception) {
        HttpStatus status = HttpStatus.BAD_REQUEST;
        if (exception instanceof ServiceUnavailableBizException) status = HttpStatus.SERVICE_UNAVAILABLE;
        else if (exception.getCode() == ErrorCode.AI_CONVERSATION_NOT_FOUND.getCode()) status = HttpStatus.NOT_FOUND;
        else if (exception.getCode() == ErrorCode.UNAUTHORIZED.getCode()) status = HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(exception.getCode(), exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> validation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getDefaultMessage()).collect(java.util.stream.Collectors.joining("; "));
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON)
                .body(Result.fail(ErrorCode.PARAM_ERROR.getCode(), message));
    }
}
