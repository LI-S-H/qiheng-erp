package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.AiMessageKind;
import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.ConversationDefaults;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.PlannerMode;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.graph.runtime.RunContext;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 启动侧准备：把"用户提交新需求"变成可执行的运行上下文。
 *
 * <p>只负责启动一轮：校验需求、拒绝覆盖待回答问题、自动标题、落库用户输入、
 * 初始化图状态。恢复场景由 {@link RunResumePreparer} 承担。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Component
@RequiredArgsConstructor
public class RunStartPreparer {

    /** 入口被绕过时仍拒绝超长需求，与请求 DTO 的校验上限一致。 */
    private static final int MAX_INPUT_LENGTH = 10000;

    private final ObjectProvider<CompiledGraph> graphs;
    private final AiConversationMapper conversationMapper;
    private final ConversationMessageStore messageStore;
    private final ConversationThreads threads;
    private final VisibleMessageCodec messageCodec;

    /**
     * 校验并落库一轮新需求，返回可执行的运行上下文。
     *
     * @param request         启动请求
     * @param userId          当前登录用户
     * @param permissionCodes 当前登录用户的权限码，由入口从登录上下文解析
     * @param requestId       本轮请求标识
     * @return 运行上下文
     */
    public RunContext prepare(GraphStartRequest request, long userId, List<String> permissionCodes,
                              String requestId) {
        CompiledGraph graph = requireGraph();
        String query = request.query();
        // 1. 校验需求非空且不超过最大长度
        if (query == null || query.isBlank() || query.length() > MAX_INPUT_LENGTH) {
            throw new BizException("需求不能为空且不能超过10000字符");
        }
        Long conversationId = request.conversationId();
        AiConversation conversation = conversationMapper.selectOwned(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        // 2. 校验线程是否存在
        String threadId = threads.of(userId, conversationId);
        Optional<StateSnapshot> current = graph.stateOf(RunnableConfig.builder().threadId(threadId).build());
        if (current.isPresent()
                && GraphStateKeys.HUMAN_FEEDBACK_NODE.equals(current.get().next())
                && NeedCheckResult.WAITING_HUMAN.name().equals(
                        current.get().state().value(GraphStateKeys.NEED_CHECK_RESULT, ""))) {
            // 新 start 不能悄悄覆盖待回答问题；需求调整仍可以在补充回答中提出。
            throw new BizException("请先回答当前问题，或新建会话发起需求");
        }
        String trimmed = query.trim();
        // 3. 自动标题与落库用户输入
        applyAutoTitle(conversation, trimmed);
        messageStore.append(conversationId, userId, requestId, "user", AiMessageKind.USER_INPUT,
                MessageRunStatus.RUNNING, null, messageCodec.text(trimmed));
        // 4. 初始化图状态；Planner 的输入记忆由节点入口在真实消费后写入
        return RunContext.start(graph, conversationId, userId, requestId, threadId,
                initialState(conversationId, userId, permissionCodes, requestId, trimmed));
    }

    /**
     * 初始化本轮图状态。节点只返回局部更新，这里给出全部业务字段的起点值。
     */
    private Map<String, Object> initialState(Long conversationId, long userId, List<String> permissionCodes,
                                             String requestId, String request) {
        Map<String, Object> initialState = new HashMap<>();
        initialState.put(GraphStateKeys.CONVERSATION_ID, conversationId);
        initialState.put(GraphStateKeys.USER_ID, String.valueOf(userId));
        initialState.put(GraphStateKeys.REQUEST_ID, requestId);
        initialState.put(GraphStateKeys.USER_REQUEST, request);
        // 权限码由服务端从登录上下文解析后写入，客户端不可声明；data_access 节点据此算可见表
        initialState.put(GraphStateKeys.PERMISSION_CODES, permissionCodes);
        initialState.put(GraphStateKeys.CLARIFICATION_HISTORY, List.of());
        initialState.put(GraphStateKeys.CLARIFICATION_QUESTIONS, List.of());
        initialState.put(GraphStateKeys.PLANNER_OUTPUT, "");
        initialState.put(GraphStateKeys.PROPOSAL_OUTPUT, "");
        initialState.put(GraphStateKeys.PLANNER_MODE, PlannerMode.INITIAL.name());
        initialState.put(GraphStateKeys.ROUND_NO, 0);
        initialState.put(GraphStateKeys.REFINE_GAPS, List.of());
        initialState.put(GraphStateKeys.NEEDS, List.of());
        initialState.put(GraphStateKeys.NEED_CHECK_RESULT, "");
        initialState.put(GraphStateKeys.NEED_REVIEW_RESULT, "");
        initialState.put(GraphStateKeys.NEED_SELECTOR_RESULT, "");
        initialState.put(GraphStateKeys.NEED_REVIEW_ERROR, Map.of());
        initialState.put(GraphStateKeys.NEED_REVIEW_OUTPUT, "");
        initialState.put(GraphStateKeys.REPAIR_COUNT, 0);
        initialState.put(GraphStateKeys.REVIEW_COUNT, 0);
        initialState.put(GraphStateKeys.REPAIR_ERROR, Map.of());
        initialState.put(GraphStateKeys.REPAIR_ISSUES, List.of());
        initialState.put(GraphStateKeys.CURRENT_NEED_ID, "");
        initialState.put(GraphStateKeys.CONFIRMED_PROPOSALS, List.of());
        initialState.put(GraphStateKeys.HUMAN_FEEDBACK, "");
        initialState.put(GraphStateKeys.PAUSE_ID, "");
        return initialState;
    }

    /**
     * 会话还叫默认名字时，用首条需求的前若干字当标题，省掉用户手动改名。
     */
    private void applyAutoTitle(AiConversation conversation, String query) {
        if (!ConversationDefaults.TITLE.equals(conversation.getTitle())) {
            return;
        }
        // 需求里的换行和连续空白压成单个空格，标题才不会变成一行乱码
        String single = query.replaceAll("\\s+", " ");
        String title = single.length() <= ConversationDefaults.AUTO_TITLE_LENGTH
                ? single
                : single.substring(0, ConversationDefaults.AUTO_TITLE_LENGTH) + "…";
        conversationMapper.updateTitle(conversation.getId(), title);
    }

    private CompiledGraph requireGraph() {
        CompiledGraph graph = graphs.getIfAvailable();
        if (graph == null) {
            throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return graph;
    }
}
