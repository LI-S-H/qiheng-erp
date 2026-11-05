package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;

/** 在框架解析数据工具参数前，把 JSON 格式问题作为工具结果交给模型。 */
public final class ToolJsonInterceptor extends ToolInterceptor {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ToolJsonInterceptor.class);
    public static final String ERROR_COUNT = "toolJsonErrorCount";
    private final ObjectMapper mapper;
    private final SystemExceptionMqPublisher publisher;
    public static final String ERROR_MESSAGE = "工具参数格式错误：必须是一个完整的 JSON 对象。请参考工具参数说明修正后重新调用。";

    /** 连续格式错误达到上限，供调用记录区分一般工具异常。 */
    public static final class ArgumentsExhaustedException extends IllegalStateException {
        public ArgumentsExhaustedException() {
            super("工具参数多次格式错误，本轮已停止，请稍后重试。");
        }
    }

    public ToolJsonInterceptor(ObjectMapper mapper) {
        this(mapper, null);
    }

    public ToolJsonInterceptor(ObjectMapper mapper, SystemExceptionMqPublisher publisher) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.publisher = publisher;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!Set.of("query_sql_data", "read_dataset").contains(request.getToolName())) {
            return handler.call(request);
        }
        try {
            var arguments = mapper.readTree(request.getArguments());
            if (arguments == null || !arguments.isObject()) {
                return invalid(request);
            }
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return invalid(request);
        }
        request.getExecutionContext().flatMap(context -> context.config().metadata(ERROR_COUNT))
                .ifPresent(value -> ((AtomicInteger) value).set(0));
        return handler.call(request);
    }

    /** 同一轮多次格式错误时终止，避免模型无期限重复调用。 */
    private ToolCallResponse invalid(ToolCallRequest request) {
        var count = request.getExecutionContext().flatMap(context -> context.config().metadata(ERROR_COUNT));
        if (count.isPresent() && ((AtomicInteger) count.get()).incrementAndGet() > 3) {
            report(request);
            throw new ArgumentsExhaustedException();
        }
        return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                ERROR_MESSAGE);
    }

    /** 异步尽力上报，不记录模型生成的参数原文。 */
    private void report(ToolCallRequest request) {
        if (publisher == null) {
            return;
        }
        try {
            var message = new SystemExceptionRecordMessage();
            var config = request.getExecutionContext().orElseThrow().config();
            String runId = String.valueOf(config.metadata("runId").orElse("unknown"));
            Object raw = request.getContext().get(SqlQueryTool.CONTEXT_KEY);
            String conversationId = raw instanceof SqlToolContext context
                    ? Long.toString(context.conversationId()) : "unknown";
            message.setEventId(IdWorker.getIdStr());
            message.setExceptionType("AI_TOOL_ARGUMENT_INVALID");
            message.setSourceModule("AI");
            message.setSourceNo("AI_TOOL/" + runId);
            message.setSeverity("MEDIUM");
            message.setErrorCode("AI_TOOL_ARGUMENT_INVALID");
            message.setErrorMessage("工具参数连续四次不是有效 JSON 对象，本轮已停止。");
            message.setDetailSummary("runId=" + runId + " conversationId=" + conversationId
                    + " tool=" + request.getToolName() + " toolCallId=" + request.getToolCallId()
                    + " 连续错误次数=4，类别=JSON格式或顶层类型错误");
            message.setResolveHint("检查工具参数 Schema、模型输出及参数解析链路。");
            message.setOccurredAt(System.currentTimeMillis());
            publisher.publish(message);
        } catch (RuntimeException error) {
            // 上报故障不能覆盖原始工具错误。
            log.warn("工具参数异常上报失败 tool={} callId={}", request.getToolName(), request.getToolCallId(), error);
        }
    }

    @Override
    public String getName() {
        return "tool_json_arguments";
    }
}
