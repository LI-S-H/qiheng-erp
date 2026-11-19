package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证报告任务内的历史续接、隔离与清理。 */
class ReportMemorySaverTest {
    @Test
    void retainsHistoryIsolatesAgentsAndTasksAndReleases() throws Exception {
        var requests = new ConcurrentLinkedQueue<String>();
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            String history = prompt.getInstructions().stream()
                    .map(message -> message.getText() == null ? "" : message.getText())
                    .reduce("", (left, right) -> left + "\n" + right);
            requests.add(history);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已处理"))));
        });
        MemorySaver saver = new MemorySaver();
        ReactAgent writer = ReactAgent.builder().name("memory_writer").model(model)
                .systemPrompt("撰写").saver(saver).releaseThread(false).build();
        ReactAgent reviewer = ReactAgent.builder().name("memory_reviewer").model(model)
                .systemPrompt("审阅").saver(saver).releaseThread(false).build();
        RunnableConfig writerOne = RunnableConfig.builder().threadId("report:task1:writer").build();
        RunnableConfig reviewerOne = RunnableConfig.builder().threadId("report:task1:reviewer").build();
        RunnableConfig writerTwo = RunnableConfig.builder().threadId("report:task2:writer").build();

        writer.call("任务一初稿内容", writerOne);
        reviewer.call("任务一审阅内容", reviewerOne);
        writer.call("任务一修改意见", writerOne);
        String retry = requests.stream().filter(text -> text.contains("任务一修改意见")).findFirst().orElseThrow();
        assertThat(retry).contains("任务一初稿内容", "任务一修改意见").doesNotContain("任务一审阅内容");

        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> writer.call("任务一并发续写", writerOne));
            var two = executor.submit(() -> writer.call("任务二首次撰写", writerTwo));
            assertThat(one.get(30, TimeUnit.SECONDS)).isNotNull();
            assertThat(two.get(30, TimeUnit.SECONDS)).isNotNull();
        }
        String second = requests.stream().filter(text -> text.contains("任务二首次撰写")).findFirst().orElseThrow();
        assertThat(second).doesNotContain("任务一初稿内容", "任务一修改意见", "任务一审阅内容");
        assertThat(saver.get(writerOne)).isPresent();
        assertThat(saver.get(reviewerOne)).isPresent();
        assertThat(saver.get(writerTwo)).isPresent();
        saver.release(writerOne);
        saver.release(reviewerOne);
        saver.release(writerTwo);
        assertThat(saver.get(writerOne)).isEmpty();
        assertThat(saver.get(reviewerOne)).isEmpty();
        assertThat(saver.get(writerTwo)).isEmpty();
    }
}
