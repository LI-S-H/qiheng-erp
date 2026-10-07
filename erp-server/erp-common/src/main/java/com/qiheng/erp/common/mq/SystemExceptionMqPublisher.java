package com.qiheng.erp.common.mq;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 系统异常记录消息发送组件。
 *
 * <p>所有异常来源统一通过本组件投递 {@link SystemExceptionRecordMessage}，
 * 由 erp-dashboard 的消费者单点写库，各模块不需要依赖 dashboard 的 mapper。</p>
 *
 * <p>异常记录属于 best-effort：MQ 未装配（未配置 name-server）时跳过发送，
 * 发送失败只记日志绝不向外抛——调用方多处于异常处理路径（如全局异常拦截器），
 * 二次抛出会吞掉用户本该拿到的错误响应。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemExceptionMqPublisher {

    /** URI 首段无法识别模块时的兜底来源 */
    private static final String MODULE_FALLBACK = "SYSTEM";

    private final ObjectProvider<SystemExceptionRocketMQTemplate> templateProvider;

    @Value("${system-exception.rocketmq.topic:erp-system-exception-record}")
    private String topic;

    /**
     * 上报全局拦截器捕获的 HTTP 未知异常。
     *
     * <p>sourceModule 从当前请求 ServletPath 首段推断（如 /sales/order → SALES），
     * 非 HTTP 线程或无法解析时兜底 SYSTEM。</p>
     *
     * <p>sourceNo 记录请求方法与完整 URI：HTTP 线程为 "POST /erp/sales/order/123"，
     * 非 HTTP 线程兜底为 "SYSTEM"。</p>
     *
     * @param e 全局拦截器兜底捕获的未知异常
     */
    public void publishSystemError(Exception e) {
        // 1. 获取当前 HTTP 请求（非 HTTP 线程返回 null）
        HttpServletRequest request = currentRequest();
        // 2. 从 ServletPath 首段推断来源模块，无法解析时兜底 SYSTEM
        String sourceModule = resolveSourceModule(request);
        // 3. 构建来源标识：HTTP 线程为 "POST /erp/sales/order/123"，非 HTTP 线程兜底 SYSTEM
        String sourceNo = request == null ? MODULE_FALLBACK
                : request.getMethod() + " " + request.getRequestURI();
        // 4. 提取异常信息，空时用类名兜底，超 2000 字符截断防消息过大
        String errorMessage = e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getName() : e.getMessage();
        if (errorMessage.length() > 2000) {
            errorMessage = errorMessage.substring(0, 2000) + "...";
        }
        // 5. 组装异常记录消息
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setEventId(IdWorker.getIdStr());
        message.setExceptionType(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
        message.setSourceModule(sourceModule);
        message.setSourceNo(sourceNo);
        message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
        // 错误码用于定位异常类型，不承担事件判重；同类型同秒的真实异常也应分别记录。
        message.setErrorCode(e.getClass().getSimpleName());
        message.setErrorMessage(errorMessage);
        message.setDetailSummary(sourceModule + "模块接口 " + sourceNo + " 抛出 " + e.getClass().getSimpleName());
        message.setResolveHint("查看应用日志 ERROR 级堆栈定位根因");
        message.setOccurredAt(System.currentTimeMillis());
        // 6. 投递消息
        publish(message);
    }

    /**
     * 上报定时任务执行失败。
     *
     * <p>只应在任务最终失败（重试用尽、无重试逻辑的 catch）时调用一次，
     * 每次中间重试失败不重复上报。</p>
     *
     * @param taskNo 任务标识，如 dashboard-daily-snapshot
     * @param detailSummary 工作台可展示的问题摘要
     * @param resolveHint 处理建议
     * @param severity 严重级别，建议有自愈兜底的任务传 LOW，否则 HIGH
     */
    public void publishJobFailure(String taskNo, String detailSummary,
                                  String resolveHint, String severity) {
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setEventId(IdWorker.getIdStr());
        message.setExceptionType(SystemExceptionConstants.TYPE_JOB_FAILED);
        message.setSourceModule(SystemExceptionConstants.MODULE_SCHEDULER);
        message.setSourceNo(taskNo);
        message.setSeverity(severity);
        message.setErrorCode(SystemExceptionConstants.CODE_JOB_EXECUTION_FAILED);
        message.setErrorMessage(detailSummary);
        message.setDetailSummary(detailSummary);
        message.setResolveHint(resolveHint);
        message.setOccurredAt(System.currentTimeMillis());
        publish(message);
    }

    /**
     * 异步发送异常记录消息。
     *
     * <p>Template 未装配或发送过程任何环节失败均只记日志，不向外抛；
     * 消息 Key 使用 eventId，tag 使用 exceptionType，便于按事件或类型检索；Key 本身不负责去重。</p>
     *
     * <p>发送采用 RocketMQ 原生异步（asyncSend）：发送 IO 不阻塞调用线程——
     * 调用方多处于 HTTP 请求线程和单线程调度池，同步等待 broker ack 最坏
     * 3 秒超时 × 2 次重试会拖慢用户错误响应并连锁推迟其他定时任务。
     * 成功与失败结果在回调中记日志，失败由
     * {@code retry-times-when-send-async-failed} 内建重试。</p>
     *
     * <p>直接传递对象给 RocketMQMessageConverter 统一序列化，与 Consumer 端反序列化使用同一个
     * 框架默认 ObjectMapper，避免手动序列化（应用 ObjectMapper Long→String）与框架反序列化
     * （默认 ObjectMapper 不做 Long→String）的不对称风险。</p>
     *
     * @param message 组装完成的异常记录消息
     */
    public void publish(SystemExceptionRecordMessage message) {
        if (message == null || message.getExceptionType() == null) {
            log.warn("系统异常消息缺失 exceptionType，跳过发送");
            return;
        }
        try {
            // 事件身份由创建消息的入口确定；发送层只校验，不补号、不修改重发消息。
            if (message.getEventId() == null || !message.getEventId().matches("[1-9][0-9]{0,18}")
                    || (message.getEventId().length() == 19
                    && message.getEventId().compareTo("9223372036854775807") > 0)) {
                log.error("系统异常事件ID非法，跳过发送 eventId={}", message.getEventId());
                return;
            }
            SystemExceptionRocketMQTemplate template = templateProvider.getIfAvailable();
            if (template == null) {
                log.warn("未配置 rocketmq.name-server，系统异常消息仅记录日志 eventId={} type={} sourceNo={} summary={}",
                        message.getEventId(), message.getExceptionType(), message.getSourceNo(), message.getDetailSummary());
                return;
            }
            // 直接传对象，由框架的 RocketMQMessageConverter 统一完成 JSON 序列化
            Message<SystemExceptionRecordMessage> msg = MessageBuilder.withPayload(message)
                    .setHeader(RocketMQHeaders.KEYS, message.getEventId()).build();
            template.asyncSend(topic + ":" + message.getExceptionType(), msg,
                    new SendCallback() {
                        @Override
                        public void onSuccess(SendResult sendResult) {
                            log.info("系统异常消息已投递 eventId={} type={} sourceNo={}",
                                    message.getEventId(), message.getExceptionType(), message.getSourceNo());
                        }

                        @Override
                        public void onException(Throwable ex) {
                            // best-effort：异步重试耗尽仍失败只记日志，不影响调用方
                            log.error("系统异常消息发送失败 eventId={} type={} sourceNo={}",
                                    message.getEventId(), message.getExceptionType(), message.getSourceNo(), ex);
                        }
                    },
                    template.getProducer().getSendMsgTimeout());
        } catch (Exception ex) {
            // best-effort：提交异步发送本身失败（如客户端在途消息超限）只记日志
            log.error("系统异常消息提交失败 eventId={} type={} sourceNo={}",
                    message.getEventId(), message.getExceptionType(), message.getSourceNo(), ex);
        }
    }

    /** 获取当前 HTTP 请求；非 HTTP 线程（定时任务等）返回 null。 */
    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.currentRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return attrs.getRequest();
            }
        } catch (IllegalStateException ex) {
            // 无请求上下文，走兜底来源
        }
        return null;
    }

    /** 从请求 ServletPath 首段推断来源模块（/sales/xxx → SALES），无法解析时兜底 SYSTEM。 */
    private String resolveSourceModule(HttpServletRequest request) {
        if (request == null) {
            return MODULE_FALLBACK;
        }
        // 使用 getServletPath() 而非 getRequestURI()，已排除 context path
        String[] segments = request.getServletPath().split("/");
        if (segments.length < 2 || segments[1].isBlank()) {
            return MODULE_FALLBACK;
        }
        return segments[1].toUpperCase();
    }
}
