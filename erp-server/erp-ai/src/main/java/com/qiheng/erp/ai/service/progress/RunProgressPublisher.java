package com.qiheng.erp.ai.service.progress;

import com.qiheng.erp.ai.domain.vo.RunNodeEventVO;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单实例活动任务事件通道；浏览器取消订阅不会取消后台任务。 */
@Slf4j
@Service
public class RunProgressPublisher {

    public static final String EVENT_NODE_START = "node-start";
    public static final String EVENT_NODE_END = "node-end";
    public static final String EVENT_ARTIFACT = "artifact";
    /** 暂停轮的内容事件：等待用户补充，本轮不再发终态事件。 */
    public static final String EVENT_CLARIFICATION_REQUIRED = "clarification_required";
    /** 计划就绪终态事件（薄载荷：状态与告警，消息正文以流水为准）。 */
    public static final String EVENT_RUN_FINISHED = "run_finished";
    /** 未完成终态事件（格式耗尽或执行失败，薄载荷）。 */
    public static final String EVENT_RUN_STOPPED = "run_stopped";
    private final Map<Long, Channel> active = new ConcurrentHashMap<>();

    /** 本请求先拥有独立通道，拿锁失败也不会影响已运行请求。 */
    public Channel create(String requestId) {
        return new Channel(requestId, Sinks.many().replay().limit(128));
    }

    /**
     * 注册活动任务通道。
     */
    public void register(long conversationId, Channel channel) {
        if (active.putIfAbsent(conversationId, channel) != null) {
            throw new IllegalStateException("该会话已有活动任务");
        }
    }

    /** 订阅活动任务通道。 */
    public Flux<ServerSentEvent<Object>> subscribe(long conversationId) {
        Channel channel = active.get(conversationId);
        return channel == null ? Flux.empty() : events(channel);
    }

    /** 是否正在运行。 */
    public boolean hasActiveRun(long conversationId) {
        return active.containsKey(conversationId);
    }

    /** 获取会话运行状态。 */
    public RunStateVO state(long conversationId) {
        Channel channel = active.get(conversationId);
        return channel == null ? RunStateVO.IDLE : channel.state;
    }

    /**
     * 组装会话事件流：心跳与通道合并，收到终态事件或通道关闭后结束。
     *
     * <p>只有 run_finished 与 run_stopped 会主动截断流；暂停轮以
     * clarification_required 作为最后一帧内容，不截断其后仍在投递的收尾帧，
     * 流的结束交给通道关闭完成：通道终结时心跳同步结束，合并流干净收尾。</p>
     */
    public Flux<ServerSentEvent<Object>> events(Channel channel) {
        Flux<ServerSentEvent<Object>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(tick -> ServerSentEvent.<Object>builder().comment("heartbeat").build())
                // 只响应终结信号：通道关闭时结束心跳，普通事件不打断心跳
                .takeUntilOther(channel.sink.asFlux().ignoreElements());
        return Flux.merge(heartbeat, channel.sink.asFlux())
                .takeUntil(event -> EVENT_RUN_FINISHED.equals(event.event())
                        || EVENT_RUN_STOPPED.equals(event.event()))
                .take(Duration.ofMinutes(30));
    }

    /**
     * 节点内部发布「某节点开始」：节点拿不到 Channel，按会话定位当前活动通道。
     *
     * <p>没有活动任务时静默跳过——记忆压缩等能力不该假设自己一定跑在图里。
     *
     * @param conversationId 会话 ID
     * @param node           节点编码，可为图中的伪节点
     */
    public void nodeStarted(long conversationId, String node) {
        Channel channel = active.get(conversationId);
        if (channel != null) {
            emit(channel, EVENT_NODE_START, RunNodeEventVO.started(node));
        }
    }

    /**
     * 节点内部发布「某节点结束」。
     *
     * @param conversationId 会话 ID
     * @param node           节点编码
     * @param durationMillis 节点耗时毫秒
     */
    public void nodeFinished(long conversationId, String node, int durationMillis) {
        Channel channel = active.get(conversationId);
        if (channel != null) {
            emit(channel, EVENT_NODE_END, RunNodeEventVO.finished(node, durationMillis));
        }
    }

    /**
     * 发布进度事件。
     */
    public void publish(long conversationId, Channel channel, String eventName, Object data) {
        if (active.get(conversationId) == channel) {
            emit(channel, eventName, data);
        }
    }

    /**
     * 更新节点状态。
     */
    public void updateNode(long conversationId, Channel channel, RunStateVO state) {
        if (active.get(conversationId) == channel) {
            channel.state = state;
        }
    }

    /** 只清理自己注册的活动通道；旧任务不能关闭新任务。 */
    public void close(long conversationId, Channel channel) {
        active.remove(conversationId, channel);
        channel.sink.tryEmitComplete();
    }

    /**
     * 发布终端事件。
     */
    public void terminal(Channel channel, String eventName, Object data) {
        emit(channel, eventName, data);
        channel.sink.tryEmitComplete();
    }

    /**
     * 发送进度事件。
     */
    private void emit(Channel channel, String eventName, Object data) {
        Sinks.EmitResult result = channel.sink.tryEmitNext(
                ServerSentEvent.<Object>builder().event(eventName).data(data).build());
        if (result.isFailure()) {
            log.debug("进度事件未送达 requestId={} reason={}", channel.requestId, result);
        }
    }

    public static final class Channel {

        private final String requestId;
        private final Sinks.Many<ServerSentEvent<Object>> sink;
        private volatile RunStateVO state = new RunStateVO("RUNNING", null, "准备执行");

        private Channel(String requestId, Sinks.Many<ServerSentEvent<Object>> sink) {
            this.requestId = requestId;
            this.sink = sink;
        }
    }
}
