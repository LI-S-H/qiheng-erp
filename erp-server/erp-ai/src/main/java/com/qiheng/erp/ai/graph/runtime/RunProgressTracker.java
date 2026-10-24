package com.qiheng.erp.ai.graph.runtime;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.vo.RunArtifactEventVO;
import com.qiheng.erp.ai.domain.vo.RunFinishedEventVO;
import com.qiheng.erp.ai.domain.vo.RunNodeEventVO;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;

/**
 * 单轮运行的进度事件追踪：把图执行快照翻译成前端进度事件。
 *
 * <p>每次运行创建一个实例，私有持有"节点名 → 开始毫秒数"的簿记，
 * 不跨运行共享可变状态。只有宣告过开始的节点才会收尾：
 * 人工暂停会让同一节点再次产出快照，不能重复发事件。</p>
 *
 * <p>发布拍写入 State 的产物投影在这里补齐检查点标识后转成内容事件，
 * 同一产物按「类型@版本」只转发一次：PLAN 投影走 {@code artifact}，
 * 澄清投影走 {@code clarification_required}；终态事件由 finished/stopped 出口发布。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Slf4j
class RunProgressTracker {

    private final RunProgressPublisher publisher;
    private final long conversationId;
    private final RunProgressPublisher.Channel channel;
    private final Map<String, Long> nodeStartedAt = new HashMap<>();

    /** 已转发的产物指纹，人工暂停重放同一快照时不重复推送内容事件。 */
    private final Set<String> publishedArtifacts = ConcurrentHashMap.newKeySet();

    private RunProgressTracker(RunProgressPublisher publisher, long conversationId,
                               RunProgressPublisher.Channel channel) {
        this.publisher = publisher;
        this.conversationId = conversationId;
        this.channel = channel;
    }

    /**
     * 为一次运行创建追踪器。
     *
     * @param publisher      进度事件发布器
     * @param conversationId 会话 ID
     * @param channel        本次运行的输出通道
     * @return 单轮追踪器
     */
    static RunProgressTracker of(RunProgressPublisher publisher, long conversationId,
                                 RunProgressPublisher.Channel channel) {
        return new RunProgressTracker(publisher, conversationId, channel);
    }

    /**
     * 宣告节点开始。已经在跑的节点不重复宣告：图起点的快照会把首节点再说一遍。
     *
     * <p>这里播报的是图的第一个节点（数据范围节点）。图拓扑改动时这里要跟着改，
     * 否则前端会先收到一个还没执行的节点。</p>
     */
    void begin() {
        publishStart(GraphStateKeys.DATA_ACCESS_NODE);
    }

    /**
     * 处理一步图产物：先转发本拍已定稿的产物，再收尾刚结束的节点并宣告下一个节点。
     *
     * <p>产物由发布拍节点写进 State，产物事件要在节点收尾之前推出，
     * 前端先看到内容再看到节点结束。</p>
     *
     * <p>只收尾「本类宣告过开始」的节点：人工暂停等场景会让同一个节点再产出一份快照，
     * 没有开始时间就说明它已经收尾过，不重复发事件。</p>
     */
    void onSnapshot(NodeOutput output) {
        if (output instanceof StateSnapshot snapshot) {
            // 处理产物,转发产物事件
            publishArtifactIfAny(snapshot);
        }
        String finishedNode = output.node();
        if (isWorkingNode(finishedNode)) {
            Long startedAt = nodeStartedAt.remove(finishedNode);
            if (startedAt != null) {
                long durationMillis = System.currentTimeMillis() - startedAt;
                publisher.publish(conversationId, channel, RunProgressPublisher.EVENT_NODE_END,
                        RunNodeEventVO.finished(finishedNode, (int) durationMillis));
            }
        }
        String nextNode = output instanceof StateSnapshot snapshot ? snapshot.next() : null;
        if (isWorkingNode(nextNode)) {
            publishStart(nextNode);
        }
    }

    /**
     * 推送计划就绪终态事件（薄载荷：状态与展示告警，正文以流水为准）。
     */
    void finished(String status, String warning) {
        publisher.terminal(channel, RunProgressPublisher.EVENT_RUN_FINISHED,
                new RunFinishedEventVO(status, warning));
    }

    /**
     * 推送未完成终态事件：格式重试耗尽或执行失败。
     *
     * @param status  终态状态
     * @param reason  可直接展示的失败说明；无额外说明时为 null
     * @param warning 展示级告警；无告警时为 null
     */
    void stopped(String status, String reason, String warning) {
        publisher.terminal(channel, RunProgressPublisher.EVENT_RUN_STOPPED,
                new RunStoppedEventVO(status, reason, warning));
    }

    /**
     * 转发发布拍写入的产物投影，并补齐本拍对应的检查点标识。
     *
     * <p>同一产物可能随重放出现多次，先用类型与版本识别已转发内容；
     * 恢复轮的首帧会回放起点检查点，其中可能携带上一轮遗留的产物投影，
     * 投影自带 requestId 归属标识，只转发属于本轮请求的产物。
     * 投影缺失或形态异常只记告警并跳过，不影响节点进度事件。</p>
     */
    private void publishArtifactIfAny(StateSnapshot snapshot) {
        // 显式用 Class 重载，避免 (key, null) 因重载解析落到 Class 版本返回 Optional
        Object artifactValue = snapshot.state()
                .value(GraphStateKeys.PUBLISHED_ARTIFACT, Object.class)
                .orElse(null);
        if (artifactValue == null) {
            return;
        }
        // 产物必须是 Map, 且包含 type 和 version 键
        if (!(artifactValue instanceof Map<?, ?> artifact)) {
            log.warn("产物投影形态异常，跳过内容事件发布 conversationId={} artifactType={}",
                    conversationId, artifactValue.getClass().getName());
            return;
        }
        Object type = artifact.get("type");
        Object version = artifact.get("version");
        if (type == null) {
            log.warn("产物投影缺少类型，跳过内容事件发布 conversationId={}", conversationId);
            return;
        }
        // 归属过滤：投影 requestId 与当前 State 请求不一致时视为上一轮遗留，不转发
        Object artifactRequestId = artifact.get("requestId");
        Object currentRequestId = snapshot.state().value(GraphStateKeys.REQUEST_ID, Object.class).orElse(null);
        if (artifactRequestId != null && currentRequestId != null
                && !artifactRequestId.equals(currentRequestId)) {
            return;
        }
        // 指纹只做同一 run 内的防重，跨 run 由 threadId 天然隔离
        String fingerprint = type + "@" + (version == null ? "" : version);
        if (!publishedArtifacts.add(fingerprint)) {
            return;
        }
        // 澄清投影即本轮暂停信号：以独立事件名推送，前端据此转入待补充表单
        String eventName = "CLARIFICATION".equals(type.toString())
                ? RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED
                : RunProgressPublisher.EVENT_ARTIFACT;
        publisher.publish(conversationId, channel, eventName,
                new RunArtifactEventVO(type.toString(),
                        snapshot.config().checkPointId().orElse(null), artifact.get("content")));
    }

    /**
     * 发布节点开始事件。
     */
    private void publishStart(String node) {
        if (nodeStartedAt.containsKey(node)) {
            return;
        }
        nodeStartedAt.put(node, System.currentTimeMillis());
        publisher.updateNode(conversationId, channel, new RunStateVO(node, RunNodeEventVO.started(node).label()));
        publisher.publish(conversationId, channel, RunProgressPublisher.EVENT_NODE_START,
                RunNodeEventVO.started(node));
    }

    /**
     * 只有需要展示给用户的业务节点才算运行中，图的起止占位节点和暂停节点跳过。
     */
    private boolean isWorkingNode(String node) {
        return node != null && !START.equals(node) && !END.equals(node)
                && !GraphStateKeys.HUMAN_FEEDBACK_NODE.equals(node);
    }
}
