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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 大上下文真实模型探针：构造超过 65 万 token 的历史，按生产阈值触发压缩，
 * 观察摘要能否在模型可接受范围内完成、耗时多少、压缩后保留了多少消息。
 *
 * <p>需要本机 {@code .env.ai.yaml} 和可用模型；默认跳过，
 * 用 {@code -DERP_REAL_COMPRESSION_LARGE_PROBE=true} 或同名环境变量开启。
 * 单次会向模型发送约 60 万 token，注意成本。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_COMPRESSION_LARGE_PROBE", matches = "true")
class ContextCompressionLargeContextRealModelIT {

    @Test
    void compressesHistoryBeyondProductionThreshold() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(300), null);
        String prompt = Files.readString(Path.of("src/main/resources/prompts/context-summary.md"));
        // 与生产默认值一致：65 万触发、保留 6 万、摘要输入 80 万。
        var properties = new AiContextProperties(true, 650_000, 60_000, 8, false,
                "flash", 300, 8000, 800_000, 1000, 2000);
        var hook = new ContextCompressionHook(model, properties, prompt, null);

        List<Message> history = largeHistory();
        int before = TokenEstimator.estimate(history);
        System.out.println("LARGE_PROBE_MESSAGES=" + history.size());
        System.out.println("LARGE_PROBE_BEFORE_TOKENS=" + before);

        long startedAt = System.currentTimeMillis();
        AgentCommand command = hook.beforeModel(history,
                RunnableConfig.builder().threadId("compression-large-probe").build());
        long elapsed = System.currentTimeMillis() - startedAt;

        List<Message> compressed = messagesOf(command);
        assertThat(compressed).isNotNull();
        int after = TokenEstimator.estimate(compressed);
        String summary = compressed.getFirst().getText();
        System.out.println("LARGE_PROBE_ELAPSED_MS=" + elapsed);
        System.out.println("LARGE_PROBE_AFTER_TOKENS=" + after);
        System.out.println("LARGE_PROBE_KEPT_MESSAGES=" + (compressed.size() - 1));
        System.out.println("LARGE_PROBE_SUMMARY_CHARS=" + summary.length());
        System.out.println("LARGE_PROBE_SUMMARY_BEGIN");
        System.out.println(summary.length() > 1200 ? summary.substring(0, 1200) : summary);
        System.out.println("LARGE_PROBE_SUMMARY_END");

        assertThat(summary).startsWith(ContextCompressionHook.SUMMARY_PREFIX);
        assertThat(summary).contains("## 目标", "## 工作状态", "## 下一步");
        assertThat(after).isLessThan(before);
    }

    /** 按轮次构造含工具调用与工具结果的历史，直到超过 68 万估算 token。 */
    private static List<Message> largeHistory() {
        List<Message> messages = new ArrayList<>();
        int round = 0;
        while (TokenEstimator.estimate(messages) < 680_000 && round < 200) {
            round++;
            String datasetId = "ds_bulk_" + round;
            messages.add(new UserMessage("第" + round + "轮：分析 A 仓库存，范围全部商品，按件统计，"
                    + "重点检查商品 P" + round + "，数据集是 " + datasetId + "。" + filler(round, 1200)));
            messages.add(AssistantMessage.builder()
                    .content("第" + round + "步：查询库存明细，核对单位和行数。" + filler(round, 400))
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-" + round, "function",
                            "query_sql_data", "{\"goal\":\"第" + round + "轮库存查询\","
                            + "\"dataRequirement\":\"FULL\"}")))
                    .build());
            messages.add(ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse("call-" + round, "query_sql_data",
                            "{\"status\":\"SUCCESS\",\"datasetId\":\"" + datasetId + "\",\"rowCount\":120,"
                                    + "\"columns\":[\"warehouse\",\"product_code\",\"qty\",\"unit\"],"
                                    + "\"rows\":[[\"A仓\",\"P" + round + "\",1200,\"件\"]]}"
                                    + filler(round, 1200))))
                    .build());
            messages.add(new AssistantMessage("第" + round + "轮已取得 " + datasetId + "，共 120 行。"
                    + filler(round, 400)));
        }
        return messages;
    }

    /** 生成有轮次差异的填充文本，避免摘要模型把重复内容当成同一段。 */
    private static String filler(int round, int repeat) {
        return ("第" + round + "轮核对：商品编号与库存数量逐项校验，单位分别记录。").repeat(repeat / 25);
    }

    /** 通过反射读取命令中的消息，框架没有公开读取方法。 */
    @SuppressWarnings("unchecked")
    private static List<Message> messagesOf(AgentCommand command) throws ReflectiveOperationException {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}