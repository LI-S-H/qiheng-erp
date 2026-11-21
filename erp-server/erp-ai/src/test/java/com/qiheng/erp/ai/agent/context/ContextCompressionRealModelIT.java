package com.qiheng.erp.ai.agent.context;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实模型的摘要质量探针：构造一段带工具结果和数据集 ID 的 ERP 历史，
 * 触发压缩并打印摘要全文，核对七段模板与关键引用是否保留。
 *
 * <p>需要本机 {@code .env.ai.yaml} 和可用的模型网关；默认跳过，
 * 用 {@code -DERP_REAL_COMPRESSION_PROBE=true} 或同名环境变量开启。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_COMPRESSION_PROBE", matches = "true")
class ContextCompressionRealModelIT {

    @Test
    void summarizesRealHistoryWithTemplateAndReferences() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(120), null);
        String prompt = Files.readString(Path.of("src/main/resources/prompts/context-summary.md"));
        var properties = new AiContextProperties(true, 200, 40, 2, false,
                "flash", 120, 4000, 10_000, 1000, 2000);
        var hook = new ContextCompressionHook(model, properties, prompt, null);

        AgentCommand command = hook.beforeModel(history(),
                RunnableConfig.builder().threadId("compression-real-probe").build());
        List<Message> compressed = messagesOf(command);

        assertThat(compressed).isNotNull();
        String summary = compressed.getFirst().getText();
        System.out.println("COMPRESSION_SUMMARY_BEGIN");
        System.out.println(summary);
        System.out.println("COMPRESSION_SUMMARY_END");
        System.out.println("COMPRESSION_KEPT_MESSAGES=" + (compressed.size() - 1));

        assertThat(summary).startsWith(ContextCompressionHook.SUMMARY_PREFIX);
        assertThat(summary).contains("## 目标");
        assertThat(summary).contains("## 工作状态");
        assertThat(summary).contains("## 下一步");
    }

    private static List<Message> history() {
        var call = new AssistantMessage.ToolCall("call-stock-1", "function", "query_sql_data",
                "{\"goal\":\"查询 A、B 两仓库存余额\",\"dataRequirement\":\"FULL\"}");
        String rows = "{\"status\":\"SUCCESS\",\"datasetId\":\"ds_stock_001\",\"rowCount\":42,"
                + "\"columns\":[\"warehouse\",\"product_code\",\"qty\",\"unit\"],"
                + "\"rows\":[[\"A仓\",\"P001\",1200,\"件\"],[\"A仓\",\"P002\",80,\"件\"],"
                + "[\"B仓\",\"P001\",900,\"件\"],[\"B仓\",\"P003\",30,\"卷\"]]}";
        return List.of(
                new UserMessage("分析 A 仓和 B 仓的库存差异，范围是全部商品，按件和卷分别统计，"
                        + "需要找出低于安全库存的商品"),
                AssistantMessage.builder().content("我先查询两仓的库存余额，确认数据范围和单位")
                        .toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-stock-1",
                                "query_sql_data", rows))).build(),
                new AssistantMessage("已取得两仓共 4 条库存记录，A 仓 P002 只有 80 件，"
                        + "B 仓 P003 是 30 卷；还需要安全库存阈值才能判断是否不足"),
                new UserMessage("继续，安全库存阈值在商品主档里，请补查"),
                new AssistantMessage("好的，我先补查商品主档的安全库存阈值"));
    }

    /** 通过反射读取命令中的消息，框架没有公开读取方法。 */
    @SuppressWarnings("unchecked")
    private static List<Message> messagesOf(AgentCommand command) throws ReflectiveOperationException {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}