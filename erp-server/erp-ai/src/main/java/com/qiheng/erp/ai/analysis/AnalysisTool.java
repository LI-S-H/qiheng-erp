package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Conditional;

/**
 * 将主智能体交付的数据分析任务交给独立分析智能体。
 *
 * @author Li
 * @since 2026-11-10
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class AnalysisTool {

    /** 工具注册名；主智能体侧据此识别分析调用。 */
    public static final String TOOL_NAME = "analyzeData";

    private final AnalysisAgentService analysis;

    /** 注入负责分析任务执行及数据交接的服务。 */
    public AnalysisTool(AnalysisAgentService analysis) {
        this.analysis = analysis;
    }

    /**
     * 只从运行上下文取得可信身份与过程通道，再提交分析目标。
     *
     * @param goal 交办的分析目标
     * @param context 已确认的业务条件，可为空
     * @param toolContext 运行上下文，提供可信身份与过程通道
     * @return 结构化分析结果
     */
    @Tool(name = TOOL_NAME, description = "把一项分析目标交给独立的数据分析专家执行。"
            + "只需描述业务问题，并附已确认的范围、口径和相关数据集 ID；专家会自行补数、计算和核对，不需要你先查同样的数据。"
            + "返回 completion、summary、数据集与分析结果 ID；同一目标不要重复交办。")
    public AnalysisResult analyzeData(
            @ToolParam(description = "必填：详细说明要回答的业务问题、需要分析的对象和期望得到的结论；"
                    + "应描述分析目标而不是只给出一个笼统主题") String goal,
            @ToolParam(required = false, description = "可选：提供用户或已有上下文明确确认的业务范围、"
                    + "时间范围、指标口径、单位、相关数据集 ID 及用途；没有已知条件时省略，不要编造") String context,
            ToolContext toolContext) {
        var identity = ToolContextHelper.getMetadata(toolContext, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信分析工具上下文"));
        var progress = (AnalysisProgress) toolContext.getContext().get(AnalysisProgress.CONTEXT_KEY);
        if (progress != null) {
            progress.start(goal);
        }
        return analysis.analyzeForMainAgent(new AnalysisCommand(goal, context, identity, progress));
    }
}
