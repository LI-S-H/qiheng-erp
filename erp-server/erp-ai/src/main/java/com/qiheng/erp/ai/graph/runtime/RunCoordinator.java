package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.constant.RunStatuses;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.ai.graph.runtime.RunContext;
import com.qiheng.erp.ai.graph.runtime.RunOutcome;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;

/**
 * 一轮图执行的编排器：会话锁 → 通道绑定 → 准备 → 执行 → 收尾，失败统一在此收敛。
 *
 * <p>本类只做编排与横切动作（会话归属校验、加锁、通道注册与清理、终态事件）：
 * 准备与进度翻译委托给准备器与进度追踪器，收尾在这里闭环（读图结局、结清用户输入行、
 * 发布终态事件），失败统一交由失败处理器落库。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunCoordinator {

    private final ConversationExecutionLock executionLock;
    private final RunProgressPublisher progressPublisher;
    private final AiConversationMapper conversationMapper;
    private final ConversationThreads threads;
    private final RunStartPreparer startPreparer;
    private final RunResumePreparer resumePreparer;
    private final RunFailureHandler failureHandler;
    private final ConversationMessageStore messageStore;

    /**
     * 提交一轮执行并返回 SSE 事件流；拿锁与图执行在独立 worker 中完成。
     *
     * @param userId          发起人
     * @param permissionCodes 发起人的权限码，由入口从登录上下文解析
     * @param start           启动请求，恢复场景为 null
     * @param resume          恢复请求，启动场景为 null
     * @return SSE 事件流，结束时发出 run_finished、run_stopped 或 clarification_required
     */
    public Flux<ServerSentEvent<Object>> submit(Long userId, List<String> permissionCodes,
                                                 GraphStartRequest start, GraphResumeRequest resume) {
        return Flux.defer(() -> {
            String requestId = UUID.randomUUID().toString();
            // 必须先建通道再启 worker:worker 可能瞬间就发出第一个 node 事件,sink 必须先存在,否则事件丢失
            RunProgressPublisher.Channel channel = progressPublisher.create(requestId);
            // 异步执行（不阻塞SSE流返回）
            Mono.fromRunnable(() -> execute(userId, permissionCodes, start, resume, requestId, channel))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(unused -> { }, error -> log.error("AI 异步执行入口异常", error));
            return progressPublisher.events(channel);
        });
    }

    /**
     * 接续订阅会话正在执行的进度；没有在跑的任务时立即结束。
     *
     * @param conversationId 会话 ID
     * @param userId         当前登录用户
     * @return SSE 事件流
     */
    public Flux<ServerSentEvent<Object>> subscribeProgress(Long conversationId, Long userId) {
        requireOwned(conversationId, userId);
        return progressPublisher.subscribe(conversationId);
    }

    /**
     * 图执行与收尾都在独立 worker 中完成，浏览器断开或取消订阅不会取消执行。
     *
     * <p>失败在 SSE 里以 run_stopped 事件收尾，不再向订阅端抛出。</p>
     */
    private void execute(Long userId, List<String> permissionCodes, GraphStartRequest start, GraphResumeRequest resume,
                         String requestId, RunProgressPublisher.Channel channel) {
        long conversationId = -1; // 解析失败的兜底值(close 时无害 no-op)
        RLock lock = null;
        boolean locked = false;
        RunContext run = null;  // 是否已进入"业务阶段"(准备成功)
        try {
            conversationId = start != null ? start.conversationId() : threads.parse(resume.threadId(), userId);
            // 忙锁提示也不能泄漏其他用户是否存在正在执行的会话。
            requireOwned(conversationId, userId);
            lock = executionLock.of(conversationId);
            locked = lock.tryLock();
            if (!locked) {
                throw new BizException("该会话正在执行，请稍后再试");
            }
            requireOwned(conversationId, userId);
            // 绑定会话与通道，此后节点进度才能推到正确的前端连接
            progressPublisher.register(conversationId, channel);
            // 运行上下文准备(落库用户输入,节点记忆,初始化图状态)
            run = start != null
                    ? startPreparer.prepare(start, userId, permissionCodes, requestId)
                    : resumePreparer.prepare(resume, userId, permissionCodes, requestId);
            // 进度追踪器开始
            RunProgressTracker tracker = RunProgressTracker.of(progressPublisher, conversationId, channel);
            // 更新节点状态为运行中
            tracker.begin();
            // 执行图
            run.graph().streamSnapshots(run.initialState(), run.execConfig())
                    .doOnNext(tracker::onSnapshot)
                    .blockLast();
            // 收尾：图结局在此读取，用户输入行在此结清
            finishRun(run, tracker);
        } catch (RuntimeException error) {
            if (run != null) {
                failureHandler.fail(run, error);
            } else if (locked) {
                failureHandler.markFailedSafely(conversationId, userId, requestId);
            }
            String text = error instanceof BizException ? error.getMessage() : RunFailureHandler.FAILED_MESSAGE_TEXT;
            progressPublisher.terminal(channel, RunProgressPublisher.EVENT_RUN_STOPPED,
                    new RunStoppedEventVO(RunStatuses.FAILED, text, null));
        } finally {
            progressPublisher.close(conversationId, channel);
            if (locked) {
                lock.unlock();
            }
        }
    }

    /**
     * 收尾：读取图结局、结清本轮用户输入行并发布终态事件。
     *
     * <p>正常结束的唯一证据是图到达 END；暂停轮不发终态事件，澄清内容已随
     * clarification_required 推送，事件流由 finally 的 close() 结束。
     * 图既未到达 END 也未暂停属于异常收口，抛错交给失败路径处理，不静默关闭事件流。</p>
     */
    private void finishRun(RunContext run, RunProgressTracker tracker) {
        RunOutcome outcome = RunOutcome.of(run.graph().getState(
                RunnableConfig.builder().threadId(run.threadId()).build()));
        String status = outcome.status();
        if (!outcome.graphEnded() && !outcome.waitingHuman()) {
            throw new IllegalStateException("图既未到达 END 也未暂停在人工节点");
        }
        if (outcome.graphEnded() && status == null) {
            throw new IllegalStateException("图到达 END 但检查点未给出业务结局");
        }
        // 结清本轮用户输入行的终态(节点状态更新) , 到达这里说明图到达 END 或暂停轮人工节点
        settleRequest(run);
        if (outcome.waitingHuman()) {
            // 暂停轮不发终态事件
            return;
        }
        // 正常结束
        if (RunStatuses.NEEDS_READY.equals(status)) {
            tracker.finished(status, null);
        } else {
            tracker.stopped(status, null, null);
        }
    }

    /**
     * 结清本轮用户输入行的终态：只改行状态，不写消息（消息已由各发布拍节点落库）。
     *
     * <p>结清失败只记日志，不改变执行结论——检查点与展示消息都已保存。</p>
     */
    private void settleRequest(RunContext run) {
        try {
            messageStore.finishRequest(run.conversationId(), run.userId(), run.requestId(),
                    MessageRunStatus.COMPLETED, null);
        } catch (RuntimeException error) {
            log.warn("本轮用户输入行结清失败 conversationId={} requestId={}",
                    run.conversationId(), run.requestId(), error);
        }
    }

    /**
     * 校验会话归属当前用户，避免跨用户读写检查点与流水。
     */
    private void requireOwned(long conversationId, long userId) {
        AiConversation conversation = conversationMapper.selectOwned(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
    }
}
