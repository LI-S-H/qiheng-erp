package com.qiheng.erp.ai.report.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportReadTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 真实模型与报告图，读取工具仅返回合成材料，不访问业务库。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_REPORT_SYNTHETIC_IT", matches = "true")
class RealReportSyntheticIT {
    @Test
    void generatesAndReviewsMultipleSyntheticSources() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), null);
        var datasets = mock(ReadDatasetTool.class);
        var results = mock(AnalysisResultTool.class);
        var reads = new AtomicInteger();
        when(results.readAnalysisResult(anyString(), any(ToolContext.class))).thenAnswer(call -> {
            reads.incrementAndGet();
            String id = call.getArgument(0);
            if ("result_stock".equals(id)) {
                return Map.of("resultId", id, "name", "损失分析", "content",
                        "仓库甲商品A：确认损失12件，计算基数600件，损失率2%；原因不明8件未计入。");
            }
            if ("result_trend".equals(id)) {
                return Map.of("resultId", id, "name", "两月对比", "content",
                        "仓库甲商品A：9月确认损失率1%，10月2%，上升1个百分点。");
            }
            return Map.of("status", "UNAVAILABLE", "message", "材料不存在");
        });
        var writerPrompt = Files.readString(Path.of("src", "main", "resources", "prompts", "report-writer.md"));
        var reviewerPrompt = Files.readString(Path.of("src", "main", "resources", "prompts", "report-reviewer.md"));
        var agents = new ReportAgents(model, new ReportReadTools(datasets, results), new ObjectMapper(),
                writerPrompt, reviewerPrompt);
        var identity = new AgentToolContext(1001, 2001);
        var graph = new ReportGraph(state -> agents.write(state, identity), state -> agents.review(state, identity));
        var output = graph.run(Map.of("userRequest", "分析10月仓库甲商品A损失，并比较9月。",
                "confirmedConditions", "按件统计，原因不明的8件不计入确认损失。",
                "analysisNarrative", "10月损失12件、基数600件、损失率2%；9月为1%，上升1个百分点。请读取结果核对。",
                "datasetIds", List.of(), "resultIds", List.of("result_stock", "result_trend")));
        System.out.println("REAL_REPORT_RESULT=" + new ObjectMapper().writeValueAsString(output));
        System.out.println("REAL_REPORT_TOOL_READS=" + reads.get());
        assertThat(output).containsKey("status").containsKey("content");
    }
}
