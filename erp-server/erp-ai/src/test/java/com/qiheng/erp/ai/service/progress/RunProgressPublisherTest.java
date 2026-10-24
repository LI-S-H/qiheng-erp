package com.qiheng.erp.ai.service.progress;

import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RunProgressPublisherTest {

    @Test
    void rejectedRequestCannotReplaceOrCloseActiveChannel() {
        RunProgressPublisher publisher = new RunProgressPublisher();
        var active = publisher.create("active");
        var rejected = publisher.create("rejected");
        publisher.register(10, active);
        publisher.terminal(rejected, RunProgressPublisher.EVENT_RUN_STOPPED,
                new RunStoppedEventVO("FAILED", "正在执行", null));
        publisher.close(10, rejected);
        assertThat(publisher.hasActiveRun(10)).isTrue();
        publisher.terminal(active, RunProgressPublisher.EVENT_RUN_FINISHED, "完成");
        var events = publisher.subscribe(10).collectList().block(Duration.ofSeconds(1));
        assertThat(events).hasSize(1);
        assertThat(events.getFirst().event()).isEqualTo("run_finished");
        publisher.close(10, active);
        assertThat(publisher.hasActiveRun(10)).isFalse();
    }

    @Test
    void browserDisconnectDoesNotRemoveBackgroundTask() {
        RunProgressPublisher publisher = new RunProgressPublisher();
        var channel = publisher.create("request");
        publisher.register(20, channel);
        var browser = publisher.subscribe(20).subscribe();
        browser.dispose();
        assertThat(publisher.hasActiveRun(20)).isTrue();
        publisher.publish(20, channel, RunProgressPublisher.EVENT_NODE_START, "继续执行");
        publisher.terminal(channel, RunProgressPublisher.EVENT_RUN_FINISHED, "完成");
        assertThat(publisher.subscribe(20).collectList().block(Duration.ofSeconds(1)))
                .hasSize(2);
        publisher.close(20, channel);
    }

    @Test
    void innerNodeProgressGoesOnlyToActiveConversation() {
        RunProgressPublisher publisher = new RunProgressPublisher();
        // 没有活动任务时静默跳过：压缩等能力不该假设自己一定跑在图里
        publisher.nodeStarted(40, com.qiheng.erp.ai.constant.GraphStateKeys.MEMORY_COMPACT_NODE);
        publisher.nodeFinished(40, com.qiheng.erp.ai.constant.GraphStateKeys.MEMORY_COMPACT_NODE, 5);

        var channel = publisher.create("compact");
        publisher.register(40, channel);
        var stream = publisher.subscribe(40);
        publisher.nodeStarted(40, com.qiheng.erp.ai.constant.GraphStateKeys.MEMORY_COMPACT_NODE);
        publisher.nodeFinished(40, com.qiheng.erp.ai.constant.GraphStateKeys.MEMORY_COMPACT_NODE, 12);
        publisher.close(40, channel);

        var events = stream.collectList().block(Duration.ofSeconds(1));
        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("node-start", "node-end");
        // 伪节点的展示文案由后端下发，前端不硬编码
        assertThat(events.getFirst().data()).hasFieldOrPropertyWithValue("label", "正在压缩历史记忆");
    }

    @Test
    void clarificationRequiredDoesNotTruncateFollowingFrames() {
        // 暂停轮不发终态事件：澄清帧之后的收尾帧必须完整投递，流的结束交给通道关闭
        RunProgressPublisher publisher = new RunProgressPublisher();
        var channel = publisher.create("pause");
        publisher.register(30, channel);
        var stream = publisher.subscribe(30);
        publisher.publish(30, channel, RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED, "澄清");
        publisher.publish(30, channel, RunProgressPublisher.EVENT_NODE_END, "收尾");
        publisher.close(30, channel);
        assertThat(stream.collectList().block(Duration.ofSeconds(1)))
                .extracting(ServerSentEvent::event)
                .containsExactly("clarification_required", "node-end");
    }
}
