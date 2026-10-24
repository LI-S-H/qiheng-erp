package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.RunStatuses;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.ai.graph.runtime.RunOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 会话运行状态的唯一读取入口：活动任务 → 检查点 → 展示流水，逐级回退。
 *
 * <p>详情读取允许 Redis 降级到 MySQL；判定规则与收尾状态机共用 {@link RunOutcome}，
 * 读取与写入不会出现两套口径。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunStateResolver {

    private final RunProgressPublisher progressPublisher;
    private final ConversationMessageStore messageStore;
    private final ObjectProvider<CompiledGraph> graphs;
    private final VisibleMessageCodec messageCodec;
    private final ConversationThreads threads;

    /**
     * 读取会话当前的运行状态，用于页面重新进入时还原进度显示。
     *
     * @param conversationId 会话 ID
     * @param userId         当前登录用户
     * @return 当前任务状态；没有任务历史时返回 {@link RunStateVO#IDLE}
     */
    public RunStateVO resolve(Long conversationId, Long userId) {
        if (progressPublisher.hasActiveRun(conversationId)) {
            // 正在运行，内存里就有实时状态（哪个节点在跑、什么标签）
            return progressPublisher.state(conversationId);
        }
        // 未运行，从数据库读取最新的用户输入(若无则说明是新会话，状态为 IDLE)
        AiMessage latest = messageStore.findLatestInput(conversationId, userId);
        if (latest == null) {
            return RunStateVO.IDLE;
        }
        // 未运行，根据最新的用户输入判断状态。
        if (MessageRunStatus.FAILED.equals(latest.getRunStatus())) {
            return new RunStateVO(RunStatuses.FAILED, null, null);
        }
        // 查 Redis 检查点
        CompiledGraph graph = graphs.getIfAvailable();
        if (graph != null) {
            Optional<StateSnapshot> stored = readCheckpointSafely(graph, conversationId, userId);
            if (stored.isPresent()) {
                StateSnapshot snapshot = stored.get();
                String requestId = snapshot.state().value(GraphStateKeys.REQUEST_ID, "");
                // 旧检查点缺少 requestId 时只兼容已完成轮次，不能把上一轮暂停当成本轮结果。
                boolean matches = latest.getRequestId().equals(requestId)
                        || (requestId.isBlank() && MessageRunStatus.COMPLETED.equals(latest.getRunStatus()));
                RunOutcome outcome = RunOutcome.of(snapshot);
                String status = matches ? outcome.status() : null;
                if (status != null) {
                    return new RunStateVO(status, null, null);
                }
            }
        }
        // 查 MySQL 展示流水
        AiMessage saved = messageStore.findFinal(conversationId, userId, latest.getRequestId());
        if (saved != null) {
            // 旧实现曾把失败状态写在助手行，读取时兼容，不继续写入这种数据。
            if (MessageRunStatus.FAILED.equals(saved.getRunStatus())) {
                return new RunStateVO(RunStatuses.FAILED, null, null);
            }
            if (MessageRunStatus.INTERRUPTED.equals(saved.getRunStatus())
                    || MessageRunStatus.CANCELLED.equals(saved.getRunStatus())) {
                return new RunStateVO(RunStatuses.INTERRUPTED, null, null);
            }
            VisibleMessageContent content = messageCodec.decode(saved.getContent());
            String type = content.type();
            if ("CLARIFICATION".equals(type)) {
                // 问题仍可展示，但缺少有效暂停检查点时不能继续提交。
                return new RunStateVO(RunStatuses.INTERRUPTED, null, null);
            }
            if ("ERROR".equals(type)) {
                // 未完成提示自带状态标记；运行失败提示没有标记，区分不依赖检查点编号
                String status = RunStatuses.FORMAT_RETRY_EXHAUSTED.equals(content.data().get("status"))
                        ? RunStatuses.FORMAT_RETRY_EXHAUSTED : RunStatuses.FAILED;
                return new RunStateVO(status, null, null);
            }
            if ("NEEDS".equals(type)) {
                return new RunStateVO(RunStatuses.NEEDS_READY, null, null);
            }
            // 只有明确保存的需求卡才能宣称需求拆解完成，历史纯文本不是完成拆解的证据。
            return RunStateVO.IDLE;
        }
        return new RunStateVO(RunStatuses.INTERRUPTED, null, null);
    }

    /** 详情读取可降级到 MySQL；执行入口仍严格依赖 Redis，不能绕过恢复校验。 */
    private Optional<StateSnapshot> readCheckpointSafely(CompiledGraph graph, long conversationId, long userId) {
        try {
            return graph.stateOf(RunnableConfig.builder().threadId(threads.of(userId, conversationId)).build());
        } catch (RuntimeException error) {
            log.warn("读取运行检查点失败，使用展示流水判定状态 conversationId={}", conversationId, error);
            return Optional.empty();
        }
    }
}
