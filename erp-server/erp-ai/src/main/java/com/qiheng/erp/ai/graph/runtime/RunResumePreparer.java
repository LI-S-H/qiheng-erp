package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.AiMessageKind;
import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.graph.runtime.RunContext;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 恢复侧准备：把"人工补充回答"变成可执行的运行上下文。
 *
 * <p>只负责恢复一轮：校验回答与暂停点有效性、落库回答、把回答写回检查点。
 * 启动场景由 {@link RunStartPreparer} 承担。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Component
@RequiredArgsConstructor
public class RunResumePreparer {

    /** 入口被绕过时仍拒绝超长回答，与请求 DTO 的校验上限一致。 */
    private static final int MAX_INPUT_LENGTH = 10000;

    private final ObjectProvider<CompiledGraph> graphs;
    private final AiConversationMapper conversationMapper;
    private final ConversationMessageStore messageStore;
    private final ConversationThreads threads;
    private final NodeMemoryRecorder nodeMemoryRecorder;

    /**
     * 校验暂停点并落库补充回答，返回从当前检查点恢复的运行上下文。
     *
     * @param request         恢复请求
     * @param userId          当前登录用户
     * @param permissionCodes 当前登录用户的权限码；暂停期间权限可能已变，恢复时按当前会话刷新
     * @param requestId       本轮请求标识
     * @return 运行上下文
     */
    public RunContext prepare(GraphResumeRequest request, long userId, List<String> permissionCodes,
                              String requestId) {
        CompiledGraph graph = requireGraph();
        String answer = request.answer();
        if (answer == null || answer.isBlank() || answer.length() > MAX_INPUT_LENGTH) {
            throw new BizException("补充回答不能为空且不能超过10000字符");
        }
        if (!threads.isOwned(request.threadId(), userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }

        // 不带检查点编号获取最新快照，禁止从历史分支恢复过期问题
        RunnableConfig threadConfig = RunnableConfig.builder().threadId(request.threadId()).build();
        // 取最新快照
        StateSnapshot latest = graph.stateOf(threadConfig)
                .orElseThrow(() -> new BizException(ErrorCode.DATA_NOT_FOUND));
        if (!String.valueOf(userId).equals(latest.state().value(GraphStateKeys.USER_ID, ""))) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        if (!GraphStateKeys.HUMAN_FEEDBACK_NODE.equals(latest.next())
                || !NeedCheckResult.WAITING_HUMAN.name().equals(
                        latest.state().value(GraphStateKeys.NEED_CHECK_RESULT, ""))) {
            throw new BizException("当前任务不在等待人工补充阶段");
        }
        // 防"过期问题答错问题"：暂停授权由节点生成的 pauseId 绑定，恢复消费后旧值即失效
        if (!request.pauseId().equals(latest.state().value(GraphStateKeys.PAUSE_ID, ""))) {
            throw new BizException("该问题已过期，请使用当前暂停点提交回答");
        }

        long conversationId = threads.parse(request.threadId(), userId);
        if (conversationMapper.selectOwned(conversationId, userId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        String trimmed = answer.trim();

        // 关联原问题卡片，页面回显回答时能挂到对应问题上
        AiMessage source = messageStore.findClarification(conversationId, userId, request.pauseId());
        Map<String, Object> answerData = new LinkedHashMap<>();
        answerData.put("text", trimmed);
        if (source != null) {
            answerData.put("replyToMessageId", String.valueOf(source.getId()));
        }
        messageStore.append(conversationId, userId, requestId, "user", AiMessageKind.USER_INPUT,
                MessageRunStatus.RUNNING, null, new VisibleMessageContent("TEXT", answerData));
        // 节点输入记忆由 PlannerNode 在图内真实消费回答后写入；本类只补一条过程记录：
        // 回答条目在检查点写回成功后落库，读侧按 pauseId 与暂停拍的问题配对

        RunnableConfig resumed = withHumanFeedback(graph, latest, trimmed, requestId, permissionCodes);
        nodeMemoryRecorder.clarificationAnswered(conversationId, userId, requestId, request.pauseId(), trimmed);
        return RunContext.resume(graph, conversationId, userId, requestId, request.threadId(), resumed);
    }

    /**
     * 把人工回答写回检查点。框架这里抛受检异常，统一转成非受检异常向上传递。
     */
    private RunnableConfig withHumanFeedback(CompiledGraph graph, StateSnapshot latest, String answer,
                                             String requestId, List<String> permissionCodes) {
        try {
            return graph.updateState(latest.config(), Map.of(GraphStateKeys.HUMAN_FEEDBACK, answer,
                    GraphStateKeys.REQUEST_ID, requestId, GraphStateKeys.NEED_CHECK_RESULT, "",
                    // 恢复即消费本次暂停授权：翻新暂停点前旧 pauseId 不可再用
                    GraphStateKeys.PAUSE_ID, "",
                    // 暂停期间权限可能已变，恢复时按当前会话刷新可见范围
                    GraphStateKeys.PERMISSION_CODES, permissionCodes));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("恢复图任务失败", exception);
        }
    }

    private CompiledGraph requireGraph() {
        CompiledGraph graph = graphs.getIfAvailable();
        if (graph == null) {
            throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return graph;
    }
}
