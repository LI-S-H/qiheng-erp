package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 探测框架流取消的传播；阻塞工具是替身，不代表 JDBC 已取消。 */
class MainAgentCancellationProbeTest {
    @Test
    void observesCancellationDuringStreamingModel() throws Exception {
        var subscribed = new CountDownLatch(1);
        var cancelled = new CountDownLatch(1);
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.<ChatResponse>never()
                .doOnSubscribe(s -> subscribed.countDown()).doOnCancel(cancelled::countDown));
        var agent = ReactAgent.builder().name("cancel-model").model(model).build();
        var config = RunnableConfig.builder().threadId("model-" + UUID.randomUUID()).build();
        var disposable = agent.stream("测试", config).subscribeOn(Schedulers.boundedElastic()).subscribe();
        try {
            assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
            agent.interrupt(config);
            boolean interruptCancelled = cancelled.await(300, TimeUnit.MILLISECONDS);
            disposable.dispose();
            boolean disposeCancelled = cancelled.await(2, TimeUnit.SECONDS);
            assertThat(disposeCancelled).isTrue();
            System.out.printf("MODEL interruptCancelled=%s disposeCancelled=%s%n", interruptCancelled, disposeCancelled);
        } finally {
            disposable.dispose();
        }
    }

    @Test
    void observesCancellationDuringBlockingTool() throws Exception {
        observeBlockingTool(false);
        observeBlockingTool(true);
    }

    /** 分别观察 interrupt 与 dispose，避免混合操作导致无法判断来源。 */
    private void observeBlockingTool(boolean dispose) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var calls = new AtomicInteger();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("call-1", "function", "blocking_test", "{}");
                var message = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            }
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
        });
        var agent = ReactAgent.builder().name("cancel-tool").model(model)
                .methodTools(new BlockingTool(entered, release, exited, interrupted)).build();
        var config = RunnableConfig.builder().threadId("tool-" + UUID.randomUUID()).build();
        var disposable = agent.stream("测试", config).subscribeOn(Schedulers.boundedElastic()).subscribe();
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            if (dispose) disposable.dispose();
            else agent.interrupt(config);
            boolean exitedBeforeRelease = exited.await(500, TimeUnit.MILLISECONDS);
            release.countDown();
            assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
            // 给后续模型节点一个受控观察窗口，不把瞬时计数当作最终结论。
            Thread.sleep(300);
            System.out.printf("TOOL action=%s exitedBeforeRelease=%s interrupted=%s modelCallsAfter300ms=%d%n",
                    dispose ? "dispose" : "interrupt", exitedBeforeRelease, interrupted.get(), calls.get());
        } finally {
            release.countDown();
            disposable.dispose();
        }
    }

    /** 用闩锁模拟不可立即完成的同步工具。 */
    public static final class BlockingTool {
        private final CountDownLatch entered, release, exited;
        private final AtomicBoolean interrupted;
        BlockingTool(CountDownLatch entered, CountDownLatch release, CountDownLatch exited, AtomicBoolean interrupted) {
            this.entered = entered; this.release = release; this.exited = exited; this.interrupted = interrupted;
        }
        @Tool(name = "blocking_test", description = "测试阻塞工具")
        public String execute() {
            entered.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                exited.countDown();
            }
            return "完成";
        }
    }
}
