package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** 强制首轮格式错误，观察真实模型修正前收到的消息及角色。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_REPAIR_MESSAGE_PROBE", matches = "true")
class RealFormatRepairMessageProbeIT {
    @Test
    void observesActualMessageRolesDuringRepair() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), null);
        var requests = new ArrayList<List<Message>>();
        var calls = new AtomicInteger();
        var observer = new ModelInterceptor() {
            @Override
            public ModelResponse interceptModel(ModelRequest request, ModelCallHandler handler) {
                requests.add(List.copyOf(request.getMessages()));
                if (calls.getAndIncrement() == 0) {
                    // 固定制造一次格式错误，以保证观察到 Hook 的修正分支。
                    return ModelResponse.of(new AssistantMessage("{\"completion\":"));
                }
                return handler.call(request);
            }

            @Override
            public String getName() {
                return "repair_message_probe";
            }
        };
        var hook = new AnalysisHandoffValidationHook(new ObjectMapper());
        var agent = ReactAgent.builder().name("repair_message_probe").model(model)
                .systemPrompt("请按要求交接分析结果。")
                .outputType(AnalysisHandoff.class).hooks(hook).interceptors(observer)
                .saver(new MemorySaver()).releaseThread(false).build();
        var config = RunnableConfig.builder().threadId("repair-message-probe")
                .addMetadata(AnalysisHandoffValidationHook.REPAIR_COUNT, new AtomicInteger()).build();
        String answer = agent.call("已有两条库存记录，请回答记录数。", config).getText();

        assertThat(requests).hasSizeGreaterThanOrEqualTo(2);
        List<Message> retry = requests.get(1);
        for (int i = 0; i < retry.size(); i++) {
            Message message = retry.get(i);
            String text = message.getText() == null ? "" : message.getText();
            System.out.println("REPAIR_REQUEST_MESSAGE index=" + i + " role=" + message.getMessageType()
                    + " text=" + text);
        }
        assertThat(retry.stream().anyMatch(message -> message instanceof AssistantMessage
                && "{\"completion\":".equals(message.getText()))).isTrue();
        assertThat(retry.stream().anyMatch(message -> message instanceof UserMessage
                && message.getText().contains("最终交接结果的 JSON 格式错误，请重新生成。"))).isTrue();
        assertThat(hook.parse(answer).summary()).isNotBlank();
        System.out.println("REPAIR_REAL_MODEL_FINAL=" + answer);
    }
}
