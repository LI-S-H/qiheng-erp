package com.qiheng.erp.common.handler;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 全局异常处理
 *
 * <p>仅 handleException 兜底路径上报系统异常消息（写入 system_exception 表）；
 * 业务异常、参数校验、重复键等属于用户操作问题，只记日志不入异常表，
 * 避免工作台"系统异常"待办被用户操作刷屏。</p>
 */
@Slf4j
@RequiredArgsConstructor
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Pattern DUPLICATE_KEY_PATTERN = Pattern.compile("Duplicate entry '(.*?)' for key");

    private final SystemExceptionMqPublisher systemExceptionMqPublisher;

    @ExceptionHandler(ServiceUnavailableBizException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Result<Void> handleServiceUnavailableBizException(ServiceUnavailableBizException e) {
        log.warn("依赖能力不可用: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 业务异常
     */
    @ExceptionHandler(BizException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleBizException(BizException e) {
        log.warn("业务异常: code={}, message={}", e.getCode(), e.getMessage());
        return Result.fail(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验异常（@RequestBody）
     * DTO 字段加注解 + Controller 参数加 @Valid
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleValidationException(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        log.error("参数校验异常: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 参数校验异常（@RequestParam / @PathVariable）
     * Controller 类加 @Validated + 参数加校验注解
     */
    @ExceptionHandler(ConstraintViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleConstraintViolationException(ConstraintViolationException e) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("; "));
        log.error("参数校验异常: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 参数类型转换异常
     * 1. 路径参数或查询参数类型不匹配（如 Long 传了非数字字符串）
     * 2. 枚举类接收到错误值
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Result<Void> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException e) {
        String paramName = e.getName();
        Class<?> requiredType = e.getRequiredType();
        Object value = e.getValue();

        String message;
        if (requiredType != null && requiredType.isEnum()) {
            String validValues = Arrays.stream(requiredType.getEnumConstants())
                    .map(Object::toString)
                    .collect(Collectors.joining(", "));
            message = String.format("参数错误：%s 值 [%s] 无效，可选值：%s", paramName, value, validValues);
        } else if (requiredType != null && Number.class.isAssignableFrom(requiredType)) {
            message = String.format("参数错误：%s 必须是有效的数字格式，当前值：[%s]", paramName, value);
        } else {
            message = String.format("参数错误：%s 类型无效，当前值：[%s]", paramName, value);
        }

        log.error("参数类型转换异常: {}", message);
        return Result.fail(ErrorCode.PARAM_ERROR.getCode(), message);
    }

    /**
     * 唯一键冲突异常
     */
    @ExceptionHandler(DuplicateKeyException.class)
    @ResponseStatus(HttpStatus.OK)
    public Result<Void> handleDuplicateKeyException(DuplicateKeyException e) {
        String message = extractDuplicateMessage(e.getMessage());
        log.warn("数据重复: {}", message);
        return Result.fail(ErrorCode.DATA_DUPLICATE.getCode(), message);
    }

    private String extractDuplicateMessage(String errorMsg) {
        Matcher matcher = DUPLICATE_KEY_PATTERN.matcher(errorMsg);
        if (matcher.find()) {
            return "数据重复，值 '" + matcher.group(1) + "' 已存在";
        }
        return ErrorCode.DATA_DUPLICATE.getMessage();
    }

    /**
     * SSE 客户端断开。
     *
     * <p>浏览器、网关或代理关闭连接后，服务端继续写事件会抛出该异常；
     * 这属于客户端状态，不是系统故障，记录 DEBUG 即可，不能写入异常中心。</p>
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleAsyncRequestNotUsable(AsyncRequestNotUsableException e) {
        log.debug("SSE 客户端已断开: uri={}, message={}", currentRequestUri(), e.getMessage());
    }

    /**
     * IO 异常。
     *
     * <p>仅把 SSE 接口上的 broken pipe / 客户端中止连接视为正常断开；
     * 其他 IO 异常仍按未知异常上报，避免掩盖真正的服务端故障。</p>
     */
    @ExceptionHandler(IOException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleIOException(IOException e) {
        if (isSseClientDisconnect(e)) {
            log.debug("SSE 客户端已断开: uri={}, message={}", currentRequestUri(), e.getMessage());
            return null;
        }
        log.error("IO 异常", e);
        systemExceptionMqPublisher.publishSystemError(e);
        return Result.fail(ErrorCode.UNKNOWN.getCode(), ErrorCode.UNKNOWN.getMessage());
    }

    /**
     * 未知异常
     *
     * <p>除原有日志与响应外，异步投递系统异常消息（发送失败由 Publisher 内部吞掉，
     * 不影响本方法返回用户可读的错误响应）。</p>
     */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("未知异常", e);
        systemExceptionMqPublisher.publishSystemError(e);
        return Result.fail(ErrorCode.UNKNOWN.getCode(), ErrorCode.UNKNOWN.getMessage());
    }

    /** 判断是否为 SSE 客户端主动断开，避免把正常断连写入系统异常表。 */
    private boolean isSseClientDisconnect(Throwable error) {
        if (error instanceof AsyncRequestNotUsableException) {
            return true;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        boolean aborted = message.contains("broken pipe")
                || message.contains("你的主机中的软件中止了一个已建立的连接")
                || message.contains("connection reset by peer")
                || message.contains("an established connection was aborted")
                || error.getClass().getName().contains("ClientAbortException");
        return aborted && isSseRequest();
    }

    /** 只有 AI 的事件流接口才按客户端断开处理。 */
    private boolean isSseRequest() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri.contains("/ai/assistant/conversations/")
                && (uri.endsWith("/events") || uri.endsWith("/messages/stream"));
    }

    /** 当前请求的“方法 URI”，用于 DEBUG 定位。 */
    private String currentRequestUri() {
        HttpServletRequest request = currentRequest();
        return request == null ? "" : request.getMethod() + " " + request.getRequestURI();
    }

    /** 获取当前 HTTP 请求；异步或非 HTTP 线程返回 null。 */
    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attributes) {
                return attributes.getRequest();
            }
        } catch (IllegalStateException e) {
            // 无请求上下文，按非 SSE 请求处理
        }
        return null;
    }
}